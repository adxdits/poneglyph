package poneglyph.core.llm;

import org.junit.jupiter.api.Test;
import poneglyph.core.CancelToken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayLlmClientTest {

    @Test
    void parsesSectionsAndConsumesThemPerKind() throws LlmException {
        ReplayLlmClient c = ReplayLlmClient.parse(String.join("\n",
                "=== decompile ===",
                "first",
                "=== tests ===",
                "tests one",
                "=== decompile ===",
                "second",
                ""));
        LlmRequest d = LlmRequest.decompile("", "x");
        LlmRequest t = LlmRequest.testGen("", "x");
        assertEquals("first\n", c.complete(d, CancelToken.NONE));
        assertEquals("tests one\n", c.complete(t, CancelToken.NONE));
        assertEquals("second\n", c.complete(d, CancelToken.NONE));
        // Exhausted kinds repeat their last response.
        assertEquals("second\n", c.complete(d, CancelToken.NONE));
        assertEquals("tests one\n", c.complete(t, CancelToken.NONE));
        assertEquals(5, c.requests().size());
    }

    @Test
    void textBeforeFirstHeaderIsADecompileResponse() throws LlmException {
        ReplayLlmClient c = ReplayLlmClient.parse("int f(void) { return 1; }\n");
        assertEquals("int f(void) { return 1; }\n", c.complete(LlmRequest.decompile("", "x"), CancelToken.NONE));
        LlmException e = assertThrows(LlmException.class, () -> c.complete(LlmRequest.testGen("", "x"), CancelToken.NONE));
        assertTrue(e.getMessage().contains("tests"));
    }
}
