package poneglyph.core.compile;

import poneglyph.core.CancelToken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Thin wrapper around gcc (or any gcc-compatible driver such as clang). Writes sources to a work
 * directory, invokes the compiler through {@link ProcessRunner} and returns the diagnostics.
 */
public final class Compiler {

    public static final String DEFAULT_GCC = "gcc";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_OUTPUT_CHARS = 64 * 1024;

    /** Flags used for every compilation. Warnings are silenced: only hard errors are fed back to the model. */
    static final List<String> BASE_FLAGS = List.of(
            "-std=gnu11", "-w", "-fno-diagnostics-color", "-O0", "-fno-strict-aliasing",
            "-Werror=implicit-function-declaration", "-Werror=implicit-int", "-Werror=return-type");

    private final String gccPath;
    private final Duration timeout;
    private final List<String> extraFlags;

    public Compiler(String gccPath, Duration timeout, List<String> extraFlags) {
        this.gccPath = gccPath == null || gccPath.isBlank() ? DEFAULT_GCC : gccPath.trim();
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        this.extraFlags = extraFlags == null ? List.of() : List.copyOf(extraFlags);
    }

    public static Compiler defaults() {
        return new Compiler(DEFAULT_GCC, DEFAULT_TIMEOUT, List.of());
    }

    public String gccPath() {
        return gccPath;
    }

    /** Runs {@code gcc --version}; throws with an actionable message if that fails. */
    public void checkAvailable() throws CompilerNotFoundException {
        try {
            ProcessRunner.Output out = ProcessRunner.run(List.of(gccPath, "--version"), null,
                    Duration.ofSeconds(15), CancelToken.NONE, 4096);
            if (out.exitCode() != 0) {
                throw new CompilerNotFoundException(gccPath, "'--version' exited with " + out.exitCode()
                        + ": " + out.combinedOutput().strip());
            }
        } catch (IOException e) {
            throw new CompilerNotFoundException(gccPath, e.getMessage());
        }
    }

    public boolean isAvailable() {
        try {
            checkAvailable();
            return true;
        } catch (CompilerNotFoundException e) {
            return false;
        }
    }

    /** First line of {@code gcc --version}, or a short failure description. */
    public String version() {
        try {
            ProcessRunner.Output out = ProcessRunner.run(List.of(gccPath, "--version"), null,
                    Duration.ofSeconds(15), CancelToken.NONE, 4096);
            String first = out.stdout().strip().split("\n", 2)[0];
            return first.isBlank() ? out.stderr().strip() : first;
        } catch (IOException e) {
            return "unavailable: " + e.getMessage();
        }
    }

    /**
     * Compiles the refined function on its own (as an object file) to check that it is valid C.
     * The source is written to {@code workDir/function.c} together with the standard prelude.
     */
    public CompileResult compileFunction(Path workDir, String refinedCode, CancelToken cancel)
            throws CompilerNotFoundException {
        Path source = writeQuietly(workDir, Harness.FUNCTION_FILE, Harness.functionSource(refinedCode));
        Path object = workDir.resolve("function.o");
        return compile(workDir, List.of("-c"), List.of(source), object, cancel);
    }

    /**
     * Compiles {@code test.c} (which {@code #include}s {@code function.c}) into an executable.
     * Both files must already exist in {@code workDir}; see {@link Harness}.
     */
    public CompileResult compileTestBinary(Path workDir, CancelToken cancel) throws CompilerNotFoundException {
        Path source = workDir.resolve(Harness.TEST_FILE);
        Path binary = workDir.resolve(Harness.TEST_BINARY);
        return compile(workDir, List.of(), List.of(source), binary, cancel, List.of("-lm"));
    }

    public CompileResult compile(Path workDir, List<String> flags, List<Path> sources, Path output, CancelToken cancel)
            throws CompilerNotFoundException {
        return compile(workDir, flags, sources, output, cancel, List.of());
    }

    public CompileResult compile(Path workDir, List<String> flags, List<Path> sources, Path output, CancelToken cancel,
                                 List<String> trailingFlags) throws CompilerNotFoundException {
        Path dir = Objects.requireNonNull(workDir, "workDir").toAbsolutePath().normalize();
        Path out = output.toAbsolutePath().normalize();
        List<String> cmd = new ArrayList<>();
        cmd.add(gccPath);
        cmd.addAll(BASE_FLAGS);
        cmd.addAll(extraFlags);
        cmd.addAll(flags);
        for (Path p : sources) {
            cmd.add(dir.relativize(p.toAbsolutePath().normalize()).toString());
        }
        cmd.add("-o");
        cmd.add(dir.relativize(out).toString());
        cmd.addAll(trailingFlags);
        try {
            ProcessRunner.Output result = ProcessRunner.run(cmd, dir, timeout, cancel, MAX_OUTPUT_CHARS);
            boolean ok = result.exitCode() == 0 && !result.timedOut() && Files.exists(out);
            return new CompileResult(ok, result.exitCode(), result.stdout(), result.stderr(), List.copyOf(cmd),
                    result.timedOut(), result.durationMs());
        } catch (IOException e) {
            throw new CompilerNotFoundException(gccPath, e.getMessage());
        }
    }

    private static Path writeQuietly(Path dir, String name, String content) {
        try {
            Files.createDirectories(dir);
            Path p = dir.resolve(name);
            Files.writeString(p, content);
            return p;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + name + " into " + dir + ": " + e.getMessage(), e);
        }
    }
}
