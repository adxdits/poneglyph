package poneglyph.core.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import poneglyph.core.Stage;
import poneglyph.core.llm.LlmRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptsTest {

    @Test
    void defaultsContainEveryKey() {
        Prompts p = Prompts.defaults();
        for (String key : Prompts.KEYS) {
            assertFalse(p.get(key).isBlank(), key + " should not be blank");
        }
        assertThrows(IllegalArgumentException.class, () -> p.get("nope"));
    }

    @Test
    void initialPromptFillsPlaceholders() {
        LlmRequest r = Prompts.defaults().initial("int FUN_1(void)\n{\n  return 1;\n}", "FUN_1");
        assertEquals(LlmRequest.Kind.DECOMPILE, r.kind());
        assertTrue(r.userPrompt().contains("int FUN_1(void)"));
        assertTrue(r.userPrompt().contains("named FUN_1"));
        assertFalse(r.userPrompt().contains("{pseudo_code}"));
        assertFalse(r.userPrompt().contains("{function_name}"));
        assertFalse(r.systemPrompt().isBlank());
    }

    @Test
    void refinePromptIncludesPreviousCodeAndFeedback() {
        LlmRequest r = Prompts.defaults().refine("PSEUDO", "int f(void) { return 1 }", "FEEDBACK TEXT", "f");
        assertTrue(r.userPrompt().contains("PSEUDO"));
        assertTrue(r.userPrompt().contains("int f(void) { return 1 }"));
        assertTrue(r.userPrompt().contains("FEEDBACK TEXT"));
    }

    @Test
    void feedbackTemplatesUseTheAgreedWording() {
        Prompts p = Prompts.defaults();
        String compile = p.feedback(Stage.COMPILE_ERROR, "function.c:3:1: error: expected ';'");
        assertTrue(compile.contains("The generated code contains compilation errors, and the specific error messages are:"));
        assertTrue(compile.contains("function.c:3:1: error: expected ';'"));
        assertTrue(compile.contains("Please analyze the errors and regenerate the code."));

        String runtime = p.feedback(Stage.RUNTIME_ERROR, "crashed with SIGSEGV");
        assertTrue(runtime.contains("runtime errors"));
        assertTrue(runtime.contains("crashed with SIGSEGV"));
        assertTrue(runtime.contains("Please analyze the errors and regenerate the code."));

        String mismatch = p.feedback(Stage.TEST_MISMATCH, "FAILED: add(1, 2) == 3");
        assertTrue(mismatch.contains("There are incorrect outputs when performing unit testing on the generated code. Specifically:"));
        assertTrue(mismatch.contains("FAILED: add(1, 2) == 3"));
        assertTrue(mismatch.contains("Please analyze the errors and regenerate the code."));

        assertTrue(p.feedback(Stage.INVALID_CODE, null).contains("did not contain a complete C function"));
        assertThrows(IllegalArgumentException.class, () -> p.feedback(Stage.OK, ""));
    }

    @Test
    void testGenerationPromptAsksForAsserts() {
        LlmRequest r = Prompts.defaults().testGeneration("int f(void) { return 1; }", "PSEUDO", "f");
        assertEquals(LlmRequest.Kind.TEST_GEN, r.kind());
        assertTrue(r.userPrompt().contains("assert("));
        assertTrue(r.userPrompt().contains("int f(void) { return 1; }"));
        assertTrue(r.userPrompt().contains("PSEUDO"));
    }

    @Test
    void overrideDirectoryWinsAndMissingFilesFallBack(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("initial.txt"), "CUSTOM {function_name}: {pseudo_code}");
        Prompts p = Prompts.load(dir);
        assertEquals("CUSTOM f: code", p.initial("code", "f").userPrompt());
        assertEquals(Prompts.defaults().get(Prompts.SYSTEM), p.get(Prompts.SYSTEM));
        assertEquals(dir, p.overrideDir());
        // A missing directory is not an error.
        assertEquals(Prompts.defaults().get(Prompts.INITIAL), Prompts.load(dir.resolve("missing")).get(Prompts.INITIAL));
    }

    @Test
    void exportDefaultsWritesEveryTemplateOnce(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("system.txt"), "KEEP ME");
        Prompts.exportDefaults(dir);
        for (String key : Prompts.KEYS) {
            assertTrue(Files.exists(dir.resolve(key + ".txt")), key);
        }
        assertEquals("KEEP ME", Files.readString(dir.resolve("system.txt")));
    }
}
