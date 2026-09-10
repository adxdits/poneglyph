package poneglyph.core.llm;

import poneglyph.core.CancelToken;
import poneglyph.core.CancelledException;

/**
 * Minimal abstraction over a text-generation model. The core only ever needs "send a prompt, get
 * text back", which keeps the refinement loop trivially mockable.
 */
public interface LlmClient {

    /**
     * Sends one request and returns the raw model text (fences, prose and all).
     *
     * @throws LlmException       if the endpoint could not be reached or returned an error
     * @throws CancelledException if {@code cancel} fired while waiting
     */
    String complete(LlmRequest request, CancelToken cancel) throws LlmException;

    /** Human readable description for logs, e.g. {@code "llama3 @ http://localhost:11434/v1"}. */
    default String describe() {
        return getClass().getSimpleName();
    }
}
