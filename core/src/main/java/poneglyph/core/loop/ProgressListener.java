package poneglyph.core.loop;

/** Callbacks for UI progress. All methods have no-op defaults; invoked on the worker thread. */
public interface ProgressListener {

    ProgressListener NONE = new ProgressListener() {
    };

    /** Free-form status text, e.g. "turn 2: asking model". */
    default void onMessage(String message) {
    }

    default void onTurnStart(int turn, int maxTurns) {
    }

    default void onTurnEnd(Turn turn) {
    }
}
