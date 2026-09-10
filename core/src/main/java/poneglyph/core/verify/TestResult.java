package poneglyph.core.verify;

import poneglyph.core.Text;

import java.util.List;

/** Outcome of compiling and running the generated tests against one refined function. */
public record TestResult(
        TestStatus status,
        int checks,
        int failures,
        List<String> failedCases,
        int exitCode,
        String stdout,
        String stderr,
        String compileErrors,
        long durationMs) {

    public enum TestStatus {
        /** All checks passed. */
        PASSED,
        /** At least one check failed. */
        FAILED,
        /** The binary crashed (signal) or exited with an unexpected code. */
        RUNTIME_ERROR,
        /** The binary did not finish within the timeout. */
        TIMEOUT,
        /** The binary ran fine but executed zero checks, so nothing was verified. */
        NO_CHECKS,
        /** The test file itself did not compile against the refined code. */
        TEST_COMPILE_ERROR
    }

    public TestResult {
        failedCases = failedCases == null ? List.of() : List.copyOf(failedCases);
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
        compileErrors = compileErrors == null ? "" : compileErrors;
    }

    public static TestResult compileError(String errors, long durationMs) {
        return new TestResult(TestStatus.TEST_COMPILE_ERROR, 0, 0, List.of(), -1, "", "", errors, durationMs);
    }

    /** One-line human summary, e.g. {@code 2 of 5 checks failed}. */
    public String summary() {
        switch (status) {
            case PASSED:
                return "all " + checks + " checks passed";
            case FAILED:
                return failures + " of " + checks + " checks failed";
            case RUNTIME_ERROR:
                return "test binary crashed or exited abnormally (exit code " + exitCode + ")";
            case TIMEOUT:
                return "test binary timed out";
            case NO_CHECKS:
                return "test binary ran but performed no checks";
            case TEST_COMPILE_ERROR:
                return "generated tests did not compile";
            default:
                return status.name();
        }
    }

    /** Text handed back to the model for a test mismatch: the failed cases, or the raw output. */
    public String failedCasesText(int maxChars) {
        if (!failedCases.isEmpty()) {
            return Text.truncate(String.join("\n", failedCases), maxChars);
        }
        return Text.truncate(combinedOutput(), maxChars);
    }

    public String combinedOutput() {
        if (stderr.isBlank()) {
            return stdout.strip();
        }
        if (stdout.isBlank()) {
            return stderr.strip();
        }
        return stdout.strip() + "\n--- stderr ---\n" + stderr.strip();
    }
}
