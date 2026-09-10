package poneglyph.core.llm;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmConfigTest {

    @Test
    void baseUrlIsNormalised() {
        assertEquals("http://localhost:11434/v1", LlmConfig.normalizeBaseUrl("http://localhost:11434"));
        assertEquals("http://localhost:11434/v1", LlmConfig.normalizeBaseUrl("http://localhost:11434/"));
        assertEquals("http://localhost:11434/v1", LlmConfig.normalizeBaseUrl("http://localhost:11434/v1/"));
        assertEquals("http://localhost:8000/v1", LlmConfig.normalizeBaseUrl("localhost:8000"));
        assertEquals("https://gpu-box.internal/api/v1", LlmConfig.normalizeBaseUrl("https://gpu-box.internal/api/v1"));
        assertEquals("http://host/openai", LlmConfig.normalizeBaseUrl("http://host/openai/"));
        assertThrows(IllegalArgumentException.class, () -> LlmConfig.normalizeBaseUrl("  "));
    }

    @Test
    void apiModeParsing() {
        assertEquals(LlmConfig.ApiMode.CHAT, LlmConfig.ApiMode.parse(null));
        assertEquals(LlmConfig.ApiMode.CHAT, LlmConfig.ApiMode.parse("chat"));
        assertEquals(LlmConfig.ApiMode.COMPLETION, LlmConfig.ApiMode.parse("completion"));
        assertEquals(LlmConfig.ApiMode.COMPLETION, LlmConfig.ApiMode.parse(" Completions "));
        assertThrows(IllegalArgumentException.class, () -> LlmConfig.ApiMode.parse("bogus"));
    }

    @Test
    void blankApiKeyBecomesNullAndDefaultsApply() {
        LlmConfig c = new LlmConfig("http://x:1", "m", "  ", null, null, 0.2, 0);
        assertNull(c.apiKey());
        assertEquals(LlmConfig.ApiMode.CHAT, c.apiMode());
        assertEquals(LlmConfig.DEFAULT_TIMEOUT, c.timeout());
        assertEquals(LlmConfig.DEFAULT_MAX_TOKENS, c.maxTokens());
        assertEquals("other", c.withModel("other").model());
        assertEquals(Duration.ofSeconds(180), LlmConfig.defaults().timeout());
    }
}
