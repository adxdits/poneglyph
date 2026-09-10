package poneglyph.core;

/**
 * Outcome of a single refinement turn, declared in increasing order of quality. The ordinal is used
 * to pick the best turn of a run, so the declaration order is significant: a turn whose tests
 * partially fail is ranked above one whose tests could not be run at all, because it carries more
 * information about correctness.
 */
public enum Stage {
    /** The model output did not contain anything that looks like a C function. */
    INVALID_CODE("no C function found in the model output"),
    /** gcc rejected the code. */
    COMPILE_ERROR("does not compile"),
    /** Compiled, but the test binary crashed, hung or exited abnormally. */
    RUNTIME_ERROR("compiles, but tests crashed or timed out"),
    /** Compiled, but no usable tests could be generated so correctness is unknown. */
    TESTS_UNKNOWN("compiles, tests unavailable"),
    /** Compiled and ran, but at least one generated assertion failed. */
    TEST_MISMATCH("compiles, but some tests fail"),
    /** Compiled and all generated tests passed. */
    OK("compiles and passes tests");

    private final String description;

    Stage(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    /** Maps the fine-grained stage to the three-colour badge shown in the UI. */
    public Status status() {
        switch (this) {
            case INVALID_CODE:
            case COMPILE_ERROR:
                return Status.RED;
            case RUNTIME_ERROR:
            case TEST_MISMATCH:
            case TESTS_UNKNOWN:
                return Status.YELLOW;
            case OK:
                return Status.GREEN;
            default:
                throw new IllegalStateException("unhandled stage " + this);
        }
    }

    /** True when the loop should keep going because there is actionable feedback for the model. */
    public boolean isRetryable() {
        return this == INVALID_CODE || this == COMPILE_ERROR || this == RUNTIME_ERROR || this == TEST_MISMATCH;
    }
}
