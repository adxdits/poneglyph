package poneglyph.core;

/** Thrown when a {@link CancelToken} reports cancellation while work is in progress. */
public class CancelledException extends RuntimeException {

    public CancelledException() {
        super("Operation cancelled");
    }
}
