package poneglyph.core.loop;

import java.nio.file.Path;

/**
 * Tunables for {@link RefinementLoop}.
 *
 * @param maxTurns             upper bound on model calls for decompilation (default 3)
 * @param testGenerationAttempts how many times to ask for tests before giving up (default 2)
 * @param workRoot             directory in which per-run temp dirs are created; null = system temp
 * @param keepWorkDir          leave the temp dir behind for debugging
 * @param maxFeedbackChars     cap on error text fed back to the model per turn
 */
public record LoopConfig(int maxTurns, int testGenerationAttempts, Path workRoot, boolean keepWorkDir,
                         int maxFeedbackChars) {

    public static final int DEFAULT_MAX_TURNS = 3;
    public static final int DEFAULT_TEST_ATTEMPTS = 2;
    public static final int DEFAULT_MAX_FEEDBACK_CHARS = 4000;

    public LoopConfig {
        if (maxTurns < 1) {
            maxTurns = 1;
        }
        if (testGenerationAttempts < 1) {
            testGenerationAttempts = 1;
        }
        if (maxFeedbackChars < 200) {
            maxFeedbackChars = 200;
        }
    }

    public static LoopConfig defaults() {
        return new LoopConfig(DEFAULT_MAX_TURNS, DEFAULT_TEST_ATTEMPTS, null, false, DEFAULT_MAX_FEEDBACK_CHARS);
    }

    public LoopConfig withMaxTurns(int turns) {
        return new LoopConfig(turns, testGenerationAttempts, workRoot, keepWorkDir, maxFeedbackChars);
    }

    public LoopConfig withWorkRoot(Path root, boolean keep) {
        return new LoopConfig(maxTurns, testGenerationAttempts, root, keep, maxFeedbackChars);
    }
}
