package poneglyph.core.loop;

import poneglyph.core.CancelToken;
import poneglyph.core.CancelledException;
import poneglyph.core.CodeExtractor;
import poneglyph.core.Stage;
import poneglyph.core.Text;
import poneglyph.core.compile.CompileResult;
import poneglyph.core.compile.Compiler;
import poneglyph.core.compile.CompilerNotFoundException;
import poneglyph.core.llm.LlmClient;
import poneglyph.core.llm.LlmException;
import poneglyph.core.llm.LlmRequest;
import poneglyph.core.prompt.Prompts;
import poneglyph.core.verify.TestResult;
import poneglyph.core.verify.TestRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The decompile → compile → test → feedback loop.
 *
 * <pre>
 * turn 1: prompt = initial(pseudo)          -> code? compile? tests?
 * turn n: prompt = refine(pseudo, previous, feedback(stage))
 * stop when stage is OK / TESTS_UNKNOWN, or maxTurns reached; return the best turn seen.
 * </pre>
 *
 * Tests are generated once, from the first version that compiles, and reused for later turns so
 * that every turn is judged against the same cases. If a later turn breaks the test build (for
 * example the model renamed the function), tests are regenerated one more time.
 */
public final class RefinementLoop {

    private final LlmClient decompiler;
    private final TestRunner testRunner;
    private final Compiler compiler;
    private final Prompts prompts;
    private final LoopConfig config;

    public RefinementLoop(LlmClient decompiler, TestRunner testRunner, Compiler compiler, Prompts prompts,
                          LoopConfig config) {
        this.decompiler = Objects.requireNonNull(decompiler, "decompiler");
        this.testRunner = Objects.requireNonNull(testRunner, "testRunner");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.config = config == null ? LoopConfig.defaults() : config;
    }

    public LoopConfig config() {
        return config;
    }

    /**
     * Runs the loop on one function.
     *
     * @param pseudoCode   Ghidra decompiler output
     * @param functionName optional name for the prompts; derived from the pseudo-code when null
     * @throws CompilerNotFoundException if gcc cannot be executed (checked before the first model call)
     * @throws LlmException              if the very first model call fails; later failures end the run
     *                                   early and are reported via {@link RefinementResult#abortReason()}
     */
    public RefinementResult run(String pseudoCode, String functionName, ProgressListener listener, CancelToken cancel)
            throws CompilerNotFoundException, LlmException {
        Objects.requireNonNull(pseudoCode, "pseudoCode");
        ProgressListener progress = listener == null ? ProgressListener.NONE : listener;
        CancelToken token = cancel == null ? CancelToken.NONE : cancel;
        if (Text.isBlank(pseudoCode)) {
            throw new IllegalArgumentException("pseudo-code is empty");
        }

        progress.onMessage("checking C compiler (" + compiler.gccPath() + ")");
        compiler.checkAvailable();

        String name = Text.isBlank(functionName)
                ? CodeExtractor.findFunctionName(pseudoCode).orElse("the function")
                : functionName.trim();

        Path work = createWorkDir();
        List<Turn> turns = new ArrayList<>();
        String testCode = null;
        boolean testsRegenerated = false;
        String previousCode = null;
        String feedback = null;
        String abortReason = null;
        boolean cancelled = false;

        try {
            for (int n = 1; n <= config.maxTurns(); n++) {
                token.throwIfCancelled();
                progress.onTurnStart(n, config.maxTurns());
                long start = System.nanoTime();

                LlmRequest request = n == 1
                        ? prompts.initial(pseudoCode, name)
                        : prompts.refine(pseudoCode, previousCode, feedback, name);

                progress.onMessage("turn " + n + "/" + config.maxTurns() + ": asking " + decompiler.describe());
                String raw;
                try {
                    raw = decompiler.complete(request, token);
                } catch (LlmException e) {
                    if (turns.isEmpty()) {
                        throw e;
                    }
                    abortReason = "model request failed on turn " + n + ": " + e.getMessage();
                    progress.onMessage(abortReason);
                    break;
                }

                Path turnDir = work.resolve("turn" + n);
                Optional<String> extracted = CodeExtractor.extract(raw);
                Turn turn;
                if (extracted.isEmpty()) {
                    feedback = prompts.feedback(Stage.INVALID_CODE, null);
                    turn = new Turn(n, Stage.INVALID_CODE, request.userPrompt(), raw, null,
                            "model output contained no C function", feedback, null, null, elapsed(start));
                } else {
                    String code = extracted.get();
                    previousCode = code;
                    progress.onMessage("turn " + n + ": compiling");
                    CompileResult compile = compiler.compileFunction(turnDir, code, token);
                    if (!compile.ok()) {
                        String errors = compile.errors(config.maxFeedbackChars());
                        feedback = prompts.feedback(Stage.COMPILE_ERROR, errors);
                        turn = new Turn(n, Stage.COMPILE_ERROR, request.userPrompt(), raw, code,
                                errors, feedback, compile, null, elapsed(start));
                    } else {
                        // Tests: generate once, reuse, regenerate at most once if the harness stops building.
                        String testProblem = null;
                        if (testCode == null) {
                            progress.onMessage("turn " + n + ": generating tests with " + testRunner.testModel().describe());
                            TestRunner.GeneratedTests gen = generateTests(code, pseudoCode, name, token, progress);
                            testCode = gen.code();
                            testProblem = gen.problem();
                        }
                        TestResult tests = null;
                        if (testCode != null) {
                            progress.onMessage("turn " + n + ": running tests");
                            tests = testRunner.run(turnDir, code, testCode, token);
                            if (tests.status() == TestResult.TestStatus.TEST_COMPILE_ERROR && !testsRegenerated) {
                                testsRegenerated = true;
                                progress.onMessage("turn " + n + ": tests no longer build, regenerating them");
                                TestRunner.GeneratedTests gen = generateTests(code, pseudoCode, name, token, progress);
                                if (gen.ok()) {
                                    testCode = gen.code();
                                    tests = testRunner.run(turnDir, code, testCode, token);
                                } else {
                                    testProblem = gen.problem();
                                }
                            }
                        }
                        turn = classify(n, request, raw, code, compile, tests, testProblem, elapsed(start));
                        feedback = turn.feedback();
                    }
                }
                turns.add(turn);
                progress.onTurnEnd(turn);
                if (!turn.stage().isRetryable()) {
                    break;
                }
            }
        } catch (CancelledException e) {
            cancelled = true;
            progress.onMessage("cancelled");
        } finally {
            cleanup(work);
        }

        Turn best = pickBest(turns);
        return new RefinementResult(turns, best, testCode, cancelled, abortReason);
    }

    private TestRunner.GeneratedTests generateTests(String code, String pseudoCode, String name, CancelToken token,
                                                    ProgressListener progress) {
        TestRunner.GeneratedTests last = null;
        for (int attempt = 1; attempt <= config.testGenerationAttempts(); attempt++) {
            token.throwIfCancelled();
            last = testRunner.generateTests(code, pseudoCode, name, token);
            if (last.ok()) {
                return last;
            }
            progress.onMessage("test generation attempt " + attempt + " failed: " + last.problem());
        }
        return last;
    }

    private Turn classify(int n, LlmRequest request, String raw, String code, CompileResult compile,
                          TestResult tests, String testProblem, long durationMs) {
        if (tests == null) {
            String detail = "compiles; tests unavailable" + (testProblem == null ? "" : " (" + testProblem + ")");
            return new Turn(n, Stage.TESTS_UNKNOWN, request.userPrompt(), raw, code, detail, null, compile, null,
                    durationMs);
        }
        switch (tests.status()) {
            case PASSED: {
                return new Turn(n, Stage.OK, request.userPrompt(), raw, code, tests.summary(), null, compile, tests,
                        durationMs);
            }
            case FAILED: {
                String cases = tests.failedCasesText(config.maxFeedbackChars());
                String feedback = prompts.feedback(Stage.TEST_MISMATCH, cases);
                return new Turn(n, Stage.TEST_MISMATCH, request.userPrompt(), raw, code,
                        tests.summary() + "\n" + cases, feedback, compile, tests, durationMs);
            }
            case RUNTIME_ERROR:
            case TIMEOUT: {
                String output = Text.truncate(tests.combinedOutput(), config.maxFeedbackChars());
                String detail = tests.summary() + (output.isBlank() ? "" : "\n" + output);
                String feedback = prompts.feedback(Stage.RUNTIME_ERROR, detail);
                return new Turn(n, Stage.RUNTIME_ERROR, request.userPrompt(), raw, code, detail, feedback, compile,
                        tests, durationMs);
            }
            case NO_CHECKS:
            case TEST_COMPILE_ERROR:
            default: {
                String detail = "compiles; " + tests.summary()
                        + (tests.compileErrors().isBlank() ? "" : "\n" + Text.truncate(tests.compileErrors(), 1500));
                return new Turn(n, Stage.TESTS_UNKNOWN, request.userPrompt(), raw, code, detail, null, compile, tests,
                        durationMs);
            }
        }
    }

    static Turn pickBest(List<Turn> turns) {
        Turn best = null;
        for (Turn t : turns) {
            if (t.isBetterThan(best)) {
                best = t;
            }
        }
        return best;
    }

    private Path createWorkDir() {
        try {
            if (config.workRoot() != null) {
                Files.createDirectories(config.workRoot());
                return Files.createTempDirectory(config.workRoot(), "poneglyph-");
            }
            return Files.createTempDirectory("poneglyph-");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create a temporary work directory: " + e.getMessage(), e);
        }
    }

    private void cleanup(Path work) {
        if (config.keepWorkDir() || work == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(work)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
