package poneglyph.core;

/**
 * Cooperative cancellation. The refinement loop, the LLM client and the process runner poll this
 * between and during long operations. Implementations must be thread safe.
 */
@FunctionalInterface
public interface CancelToken {

    /** A token that is never cancelled. */
    CancelToken NONE = () -> false;

    boolean isCancelled();

    /** Throws {@link CancelledException} if the token has been cancelled. */
    default void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancelledException();
        }
    }
}
