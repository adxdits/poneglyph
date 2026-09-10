package poneglyph.core.loop;

import poneglyph.core.Stage;
import poneglyph.core.Status;

import java.util.List;

/**
 * Result of a whole refinement run. {@code best} is the highest-ranked turn (see
 * {@link Turn#isBetterThan}); it is null only when the run was cancelled or aborted before the
 * first turn completed.
 */
public record RefinementResult(
        List<Turn> turns,
        Turn best,
        String testCode,
        boolean cancelled,
        String abortReason) {

    public RefinementResult {
        turns = turns == null ? List.of() : List.copyOf(turns);
    }

    public Status status() {
        return best == null ? Status.RED : best.status();
    }

    public Stage stage() {
        return best == null ? Stage.INVALID_CODE : best.stage();
    }

    /** The best refined code seen, or null if no turn produced any C. */
    public String bestCode() {
        return best == null ? null : best.code();
    }

    public boolean hasCode() {
        return bestCode() != null;
    }

    public int bestTurnNumber() {
        return best == null ? 0 : best.number();
    }

    /** One-line summary such as {@code GREEN: compiles and passes tests (best turn 2 of 3)}. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(status()).append(": ").append(stage().description());
        if (best != null) {
            sb.append(" (best turn ").append(best.number()).append(" of ").append(turns.size()).append(')');
        }
        if (cancelled) {
            sb.append(" [cancelled]");
        }
        if (abortReason != null) {
            sb.append(" [stopped early: ").append(abortReason).append(']');
        }
        return sb.toString();
    }
}
