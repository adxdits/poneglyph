package poneglyph.core.llm;

import java.time.Duration;
import java.util.Objects;

/**
 * Connection settings for an OpenAI-compatible endpoint (Ollama, vLLM, LM Studio, llama.cpp
 * server, ...). Nothing here is ever sent anywhere except {@code baseUrl}.
 */
public record LlmConfig(
        String baseUrl,
        String model,
        String apiKey,
        ApiMode apiMode,
        Duration timeout,
        double temperature,
        int maxTokens) {

    /** Which OpenAI-compatible route to use. */
    public enum ApiMode {
        /** {@code POST /chat/completions} with system + user messages. Right for instruct models. */
        CHAT,
        /**
         * {@code POST /completions} with a single prompt string. Right for completion-style models such
         * as LLM4Decompile-ref, which were not trained on a chat template.
         */
        COMPLETION;

        public static ApiMode parse(String s) {
            if (s == null) {
                return CHAT;
            }
            switch (s.trim().toLowerCase()) {
                case "completion":
                case "completions":
                case "text":
                    return COMPLETION;
                case "chat":
                case "":
                    return CHAT;
                default:
                    throw new IllegalArgumentException("Unknown API mode '" + s + "' (expected chat or completion)");
            }
        }
    }

    public static final String DEFAULT_BASE_URL = "http://localhost:11434/v1";
    public static final String DEFAULT_MODEL = "qwen2.5-coder:7b";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(180);
    public static final double DEFAULT_TEMPERATURE = 0.0;
    public static final int DEFAULT_MAX_TOKENS = 2048;

    public LlmConfig {
        baseUrl = normalizeBaseUrl(Objects.requireNonNull(baseUrl, "baseUrl"));
        model = Objects.requireNonNull(model, "model").trim();
        apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey.trim();
        apiMode = apiMode == null ? ApiMode.CHAT : apiMode;
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        if (maxTokens <= 0) {
            maxTokens = DEFAULT_MAX_TOKENS;
        }
    }

    public static LlmConfig defaults() {
        return new LlmConfig(DEFAULT_BASE_URL, DEFAULT_MODEL, null, ApiMode.CHAT, DEFAULT_TIMEOUT,
                DEFAULT_TEMPERATURE, DEFAULT_MAX_TOKENS);
    }

    public LlmConfig withModel(String newModel) {
        return new LlmConfig(baseUrl, newModel, apiKey, apiMode, timeout, temperature, maxTokens);
    }

    public LlmConfig withApiMode(ApiMode mode) {
        return new LlmConfig(baseUrl, model, apiKey, mode, timeout, temperature, maxTokens);
    }

    /**
     * Accepts {@code http://host:port}, {@code http://host:port/} or {@code http://host:port/v1} and
     * always returns the form without a trailing slash and with the {@code /v1} prefix present, since
     * every supported server exposes the OpenAI routes under {@code /v1}.
     */
    static String normalizeBaseUrl(String url) {
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        if (u.isEmpty()) {
            throw new IllegalArgumentException("Endpoint URL is empty");
        }
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "http://" + u;
        }
        // Only append /v1 if the URL has no path component at all.
        int schemeEnd = u.indexOf("://") + 3;
        int firstSlash = u.indexOf('/', schemeEnd);
        if (firstSlash < 0) {
            u = u + "/v1";
        }
        return u;
    }
}
