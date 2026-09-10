package poneglyph.core.loop;

import poneglyph.core.Stage;
import poneglyph.core.Status;
import poneglyph.core.compile.CompileResult;
import poneglyph.core.verify.TestResult;

/**
 * Everything that happened in one refinement turn. {@code code} is null when the model produced
 * nothing usable; {@code feedback} is the text that will be handed to the model on the next turn,
 * or null when the loop stops here.
 */
public record Turn(
        int number,
        Stage stage,
        String prompt,
        String rawResponse,
        String code,
        String detail,
        String feedback,
        CompileResult compile,
        TestResult tests,
        long durationMs) {

    public Status status() {
        return stage.status();
    }

    /**
     * Ranking used to choose the best turn of a run: higher stage wins; among test mismatches fewer
     * failures win; otherwise the earlier turn is kept.
     */
    public boolean isBetterThan(Turn other) {
        if (other == null) {
            return true;
        }
        if (stage != other.stage) {
            return stage.ordinal() > other.stage.ordinal();
        }
        if (stage == Stage.TEST_MISMATCH && tests != null && other.tests != null) {
            return tests.failures() < other.tests.failures();
        }
        return false;
    }

    /** Short one-line summary for logs, e.g. {@code turn 2: COMPILE_ERROR (1.3s)}. */
    public String headline() {
        return "turn " + number + ": " + stage + (detail == null || detail.isBlank() ? "" : " - " + firstLine(detail));
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}
