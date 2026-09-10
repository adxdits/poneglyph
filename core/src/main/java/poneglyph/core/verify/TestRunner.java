package poneglyph.core.verify;

import poneglyph.core.CancelToken;
import poneglyph.core.CodeExtractor;
import poneglyph.core.Text;
import poneglyph.core.compile.CompileResult;
import poneglyph.core.compile.Compiler;
import poneglyph.core.compile.CompilerNotFoundException;
import poneglyph.core.compile.Harness;
import poneglyph.core.compile.ProcessRunner;
import poneglyph.core.llm.LlmClient;
import poneglyph.core.llm.LlmException;
import poneglyph.core.llm.LlmRequest;
import poneglyph.core.prompt.Prompts;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks a model for assert-based tests, compiles them together with the refined function and runs
 * the binary. Test generation problems never throw: they are reported through
 * {@link GeneratedTests#problem()} so the loop can mark the turn YELLOW and move on.
 */
public final class TestRunner {

    public static final Duration DEFAULT_RUN_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_OUTPUT_CHARS = 64 * 1024;
    private static final Pattern RESULT_LINE = Pattern.compile(
            Pattern.quote(Harness.RESULT_MARKER) + "\\s*(?:(\\d+) of (\\d+) checks failed|all (\\d+) checks passed)");
    private static final Pattern MAIN_DEF = Pattern.compile("\\bint\\s+main\\s*\\(");

    /** Result of asking the model for tests. Exactly one of {@code code} / {@code problem} is non-null. */
    public record GeneratedTests(String code, String rawResponse, String problem) {
        public boolean ok() {
            return code != null;
        }
    }

    private final LlmClient testModel;
    private final Compiler compiler;
    private final Prompts prompts;
    private final Duration runTimeout;

    public TestRunner(LlmClient testModel, Compiler compiler, Prompts prompts, Duration runTimeout) {
        this.testModel = Objects.requireNonNull(testModel, "testModel");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.runTimeout = runTimeout == null ? DEFAULT_RUN_TIMEOUT : runTimeout;
    }

    public LlmClient testModel() {
        return testModel;
    }

    /**
     * Asks the model for 3-5 assert-based tests for {@code refinedCode}. Never throws for model or
     * format problems; inspect {@link GeneratedTests#problem()}.
     */
    public GeneratedTests generateTests(String refinedCode, String pseudoCode, String functionName, CancelToken cancel) {
        LlmRequest request = prompts.testGeneration(refinedCode, pseudoCode, functionName);
        String raw;
        try {
            raw = testModel.complete(request, cancel);
        } catch (LlmException e) {
            return new GeneratedTests(null, null, "test generation request failed: " + e.getMessage());
        }
        Optional<String> code = CodeExtractor.extract(raw);
        if (code.isEmpty()) {
            return new GeneratedTests(null, raw, "model response contained no C code");
        }
        String tests = code.get();
        if (!MAIN_DEF.matcher(tests).find()) {
            return new GeneratedTests(null, raw, "generated tests have no main() function");
        }
        if (!tests.contains("assert") && !tests.contains("RD_CHECK")) {
            return new GeneratedTests(null, raw, "generated tests contain no assert() calls");
        }
        return new GeneratedTests(stripDuplicateDefinitions(tests, refinedCode), raw, null);
    }

    /**
     * Compiles {@code test.c} + {@code function.c} in {@code workDir} and runs the binary.
     *
     * @throws CompilerNotFoundException if gcc cannot be executed at all
     */
    public TestResult run(Path workDir, String refinedCode, String testCode, CancelToken cancel)
            throws CompilerNotFoundException {
        long start = System.nanoTime();
        try {
            Harness.writeFunction(workDir, refinedCode);
            Harness.writeTest(workDir, testCode);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write test sources into " + workDir + ": " + e.getMessage(), e);
        }
        CompileResult compile = compiler.compileTestBinary(workDir, cancel);
        if (!compile.ok()) {
            return TestResult.compileError(compile.errors(), elapsed(start));
        }
        ProcessRunner.Output out;
        try {
            out = ProcessRunner.run(List.of(workDir.resolve(Harness.TEST_BINARY).toAbsolutePath().toString()),
                    workDir, runTimeout, cancel, MAX_OUTPUT_CHARS);
        } catch (IOException e) {
            return new TestResult(TestResult.TestStatus.RUNTIME_ERROR, 0, 0, List.of(), -1, "",
                    "could not start test binary: " + e.getMessage(), "", elapsed(start));
        }
        return classify(out, elapsed(start));
    }

    /** Package-private for tests. */
    static TestResult classify(ProcessRunner.Output out, long durationMs) {
        String stdout = out.stdout();
        String stderr = out.stderr();
        List<String> failed = new ArrayList<>();
        for (String line : Text.normalizeNewlines(stdout).split("\n")) {
            if (line.startsWith(Harness.FAIL_MARKER)) {
                failed.add(line.strip());
            }
        }
        int checks = 0;
        int failures = 0;
        boolean sawResult = false;
        Matcher m = RESULT_LINE.matcher(stdout);
        if (m.find()) {
            sawResult = true;
            if (m.group(3) != null) {
                checks = Integer.parseInt(m.group(3));
            } else {
                failures = Integer.parseInt(m.group(1));
                checks = Integer.parseInt(m.group(2));
            }
        }

        if (out.timedOut()) {
            return new TestResult(TestResult.TestStatus.TIMEOUT, checks, failures, failed, out.exitCode(),
                    stdout, stderr, "", durationMs);
        }
        if (out.killedBySignal()) {
            // A real assert() that escaped our override aborts the process: still a test failure.
            if (stderr.contains("Assertion failed") || stderr.contains("assertion") && stderr.contains("failed")) {
                failed.add(stderr.strip());
                return new TestResult(TestResult.TestStatus.FAILED, Math.max(checks, 1), failures + 1, failed,
                        out.exitCode(), stdout, stderr, "", durationMs);
            }
            return new TestResult(TestResult.TestStatus.RUNTIME_ERROR, checks, failures, failed, out.exitCode(),
                    stdout, "crashed with " + out.signalName() + (stderr.isBlank() ? "" : "\n" + stderr), "",
                    durationMs);
        }
        if (failures > 0 || (!failed.isEmpty() && out.exitCode() != 0)) {
            return new TestResult(TestResult.TestStatus.FAILED, checks, Math.max(failures, failed.size()), failed,
                    out.exitCode(), stdout, stderr, "", durationMs);
        }
        if (out.exitCode() == 0) {
            if (!sawResult || checks == 0) {
                return new TestResult(TestResult.TestStatus.NO_CHECKS, checks, 0, failed, 0, stdout, stderr, "",
                        durationMs);
            }
            return new TestResult(TestResult.TestStatus.PASSED, checks, 0, List.of(), 0, stdout, stderr, "",
                    durationMs);
        }
        if (out.exitCode() == 1) {
            // The model's main returned 1 itself, typically after printing which case failed.
            return new TestResult(TestResult.TestStatus.FAILED, Math.max(checks, 1), Math.max(failures, 1),
                    failed.isEmpty() ? List.of(out.combinedOutput().strip()) : failed, 1, stdout, stderr, "",
                    durationMs);
        }
        return new TestResult(TestResult.TestStatus.RUNTIME_ERROR, checks, failures, failed, out.exitCode(),
                stdout, stderr, "", durationMs);
    }

    /**
     * Models sometimes paste the function under test into the test file. Since {@code test.c}
     * includes {@code function.c}, that would be a duplicate definition, so such definitions are
     * removed here by name.
     */
    static String stripDuplicateDefinitions(String tests, String refinedCode) {
        List<String> names = CodeExtractor.findFunctionNames(refinedCode);
        if (names.isEmpty()) {
            return tests;
        }
        String[] lines = Text.normalizeNewlines(tests).split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String defName = definedFunctionName(line);
            if (defName != null && names.contains(defName) && !defName.equals("main")) {
                // Skip until the matching closing brace.
                int depth = 0;
                boolean seenBrace = false;
                while (i < lines.length) {
                    for (char c : lines[i].toCharArray()) {
                        if (c == '{') {
                            depth++;
                            seenBrace = true;
                        } else if (c == '}') {
                            depth--;
                        }
                    }
                    i++;
                    if (seenBrace && depth <= 0) {
                        break;
                    }
                }
                sb.append("/* duplicate definition of ").append(defName).append(" removed by Poneglyph */\n");
                continue;
            }
            sb.append(line).append('\n');
            i++;
        }
        return sb.toString();
    }

    private static String definedFunctionName(String line) {
        Optional<String> n = CodeExtractor.findFunctionName(line + "\n{\n}");
        return n.orElse(null);
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
