package poneglyph.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import poneglyph.core.testutil.FakeOpenAiServer;
import poneglyph.core.testutil.Gcc;
import poneglyph.core.testutil.Samples;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliEndToEndTest {

    private static final class Run {
        final int exit;
        final String out;
        final String err;

        Run(int exit, String out, String err) {
            this.exit = exit;
            this.out = out;
            this.err = err;
        }
    }

    private static Run cli(String... args) {
        return cliWithInput("", args);
    }

    private static Run cliWithInput(String stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = Main.run(args, new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static String[] withGcc(String... args) {
        List<String> all = new ArrayList<>(List.of("--gcc", Gcc.path()));
        all.addAll(List.of(args));
        return all.toArray(new String[0]);
    }

    @Test
    void replayCountBitsFailsTestsThenGoesGreen() {
        Gcc.assumeAvailable();
        Run r = cli(withGcc("--replay", Samples.replay("count_bits").toString(), Samples.pseudo("count_bits").toString()));
        assertEquals(0, r.exit, r.out + r.err);
        assertTrue(r.out.contains("TEST_MISMATCH"), r.out);
        assertTrue(r.out.contains("count_bits(0xF0) == 4"), r.out);
        assertTrue(r.out.contains("=== GREEN: compiles and passes tests (best turn 2 of 2) ==="), r.out);
        assertTrue(r.out.contains("count += value & 1u;"), r.out);
    }

    @Test
    void replaySumArrayHasCompileErrorThenGoesGreen() {
        Gcc.assumeAvailable();
        Run r = cli(withGcc("--replay", Samples.replay("sum_array").toString(), Samples.pseudo("sum_array").toString()));
        assertEquals(0, r.exit, r.out + r.err);
        assertTrue(r.out.contains("COMPILE_ERROR [RED]"), r.out);
        assertTrue(r.out.contains("OK [GREEN]"), r.out);
    }

    @Test
    void replayStrReverseIsGreenOnTurnOne() {
        Gcc.assumeAvailable();
        Run r = cli(withGcc("--replay", Samples.replay("str_reverse").toString(), Samples.pseudo("str_reverse").toString()));
        assertEquals(0, r.exit, r.out + r.err);
        assertTrue(r.out.contains("best turn 1 of 1"), r.out);
    }

    @Test
    void jsonReportAndOutFile(@TempDir Path dir) throws Exception {
        Gcc.assumeAvailable();
        Path outFile = dir.resolve("refined.c");
        Run r = cli(withGcc("--json", "--out", outFile.toString(), "--replay", Samples.replay("count_bits").toString(),
                Samples.pseudo("count_bits").toString()));
        assertEquals(0, r.exit, r.out + r.err);
        JsonObject json = JsonParser.parseString(r.out).getAsJsonObject();
        assertEquals("GREEN", json.get("status").getAsString());
        assertEquals("OK", json.get("stage").getAsString());
        assertEquals(2, json.get("bestTurn").getAsInt());
        assertEquals(2, json.getAsJsonArray("turns").size());
        assertEquals("TEST_MISMATCH", json.getAsJsonArray("turns").get(0).getAsJsonObject().get("stage").getAsString());
        assertTrue(Files.readString(outFile).contains("uint32_t count_bits(uint32_t value)"));
    }

    @Test
    void liveEndpointFlowThroughFakeServer() throws Exception {
        Gcc.assumeAvailable();
        try (FakeOpenAiServer server = new FakeOpenAiServer()) {
            server.reply("```c\n#include <stdint.h>\nuint32_t count_bits(uint32_t v)\n{\n    uint32_t n = 0;\n"
                    + "    while (v) { n += v & 1u; v >>= 1 }\n    return n;\n}\n```");
            server.reply("```c\n#include <stdint.h>\nuint32_t count_bits(uint32_t v)\n{\n    uint32_t n = 0;\n"
                    + "    while (v) { n += v & 1u; v >>= 1; }\n    return n;\n}\n```");
            server.reply("```c\n#include <assert.h>\nint main(void)\n{\n    assert(count_bits(0) == 0);\n"
                    + "    assert(count_bits(255) == 8);\n    return 0;\n}\n```");
            Run r = cli(withGcc("--endpoint", server.baseUrl(), "--model", "fake-coder", "--quiet",
                    Samples.pseudo("count_bits").toString()));
            assertEquals(0, r.exit, r.out + r.err);
            assertTrue(r.out.startsWith("GREEN"), r.out);
            assertEquals(3, server.requestCount());
            assertEquals("/v1/chat/completions", server.recorded().get(0).path());
            assertEquals("fake-coder", server.recorded().get(0).body().get("model").getAsString());
        }
    }

    @Test
    void completionModeAndSeparateTestModel() throws Exception {
        Gcc.assumeAvailable();
        try (FakeOpenAiServer server = new FakeOpenAiServer()) {
            server.reply("#include <stdint.h>\nuint32_t count_bits(uint32_t v)\n{\n    uint32_t n = 0;\n"
                    + "    while (v) { n += v & 1u; v >>= 1; }\n    return n;\n}\n");
            server.reply("```c\n#include <assert.h>\nint main(void)\n{\n    assert(count_bits(3) == 2);\n    return 0;\n}\n```");
            Run r = cli(withGcc("--endpoint", server.baseUrl(), "--model", "llm4decompile", "--api-mode", "completion",
                    "--test-model", "coder", "--quiet", Samples.pseudo("count_bits").toString()));
            assertEquals(0, r.exit, r.out + r.err);
            assertEquals("/v1/completions", server.recorded().get(0).path());
            assertEquals("llm4decompile", server.recorded().get(0).body().get("model").getAsString());
            assertEquals("/v1/chat/completions", server.recorded().get(1).path());
            assertEquals("coder", server.recorded().get(1).body().get("model").getAsString());
        }
    }

    @Test
    void usageErrors() {
        assertEquals(3, cli("--bogus").exit);
        assertEquals(3, cli().exit);
        assertEquals(3, cli("--max-turns", "zero", "x.c").exit);
        Run help = cli("--help");
        assertEquals(0, help.exit);
        assertTrue(help.out.contains("--replay"));
        Run missing = cli("--replay", Samples.replay("count_bits").toString(), "/nonexistent/input.c");
        assertEquals(3, missing.exit);
        assertTrue(missing.err.contains("cannot read"), missing.err);
    }

    @Test
    void missingCompilerExitsWithHint() {
        Run r = cli("--gcc", "/nonexistent/dir/gcc-xyz", "--replay", Samples.replay("count_bits").toString(),
                Samples.pseudo("count_bits").toString());
        assertEquals(3, r.exit);
        assertTrue(r.err.contains("/nonexistent/dir/gcc-xyz"), r.err);
        assertTrue(r.err.contains("--gcc"), r.err);
        assertTrue(r.err.contains("settings"), r.err);
    }

    @Test
    void readsPseudoCodeFromStdin() throws Exception {
        Gcc.assumeAvailable();
        String pseudo = Samples.read(Samples.pseudo("count_bits"));
        Run r = cliWithInput(pseudo, withGcc("--replay", Samples.replay("count_bits").toString(), "-"));
        assertEquals(0, r.exit, r.out + r.err);
        assertTrue(r.out.contains("input:    standard input"), r.out);
        assertTrue(r.out.contains("best turn 2 of 2"), r.out);
    }

    @Test
    void emptyStdinIsRejected() {
        Run r = cliWithInput("   \n", withGcc("--replay", Samples.replay("count_bits").toString(), "-"));
        assertEquals(3, r.exit);
        assertTrue(r.err.contains("standard input is empty"), r.err);
    }

    @Test
    void stdinAndAFileAreMutuallyExclusive() {
        Run r = cli("-", Samples.pseudo("count_bits").toString());
        assertEquals(3, r.exit);
        assertTrue(r.err.contains("cannot read both standard input"), r.err);
    }

    @Test
    void versionFlag() {
        Run r = cli("--version");
        assertEquals(0, r.exit);
        assertEquals("Poneglyph CLI " + Main.VERSION, r.out.strip());
    }

    @Test
    void exportPrompts(@TempDir Path dir) {
        Run r = cli("--export-prompts", dir.resolve("prompts").toString());
        assertEquals(0, r.exit, r.err);
        assertTrue(Files.exists(dir.resolve("prompts").resolve("initial.txt")));
    }
}
