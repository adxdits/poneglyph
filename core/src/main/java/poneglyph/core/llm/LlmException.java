package poneglyph.core.llm;

/** The model endpoint could not be reached, timed out, or returned an error/unparseable body. */
public class LlmException extends Exception {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
