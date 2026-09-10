package poneglyph.core.llm;

import java.util.Objects;

/**
 * One prompt sent to a model. {@code kind} lets replay/mocks and the optional second "test model"
 * distinguish decompilation requests from test-generation requests.
 */
public record LlmRequest(Kind kind, String systemPrompt, String userPrompt) {

    public enum Kind {
        /** Rewrite pseudo-code into C (initial turn or refinement turn). */
        DECOMPILE,
        /** Generate assert-based unit tests for a function. */
        TEST_GEN
    }

    public LlmRequest {
        Objects.requireNonNull(kind, "kind");
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        userPrompt = Objects.requireNonNull(userPrompt, "userPrompt");
    }

    public static LlmRequest decompile(String systemPrompt, String userPrompt) {
        return new LlmRequest(Kind.DECOMPILE, systemPrompt, userPrompt);
    }

    public static LlmRequest testGen(String systemPrompt, String userPrompt) {
        return new LlmRequest(Kind.TEST_GEN, systemPrompt, userPrompt);
    }

    /** System and user prompt joined for completion-style (non-chat) endpoints. */
    public String asSinglePrompt() {
        if (systemPrompt.isBlank()) {
            return userPrompt;
        }
        return systemPrompt.stripTrailing() + "\n\n" + userPrompt;
    }
}
