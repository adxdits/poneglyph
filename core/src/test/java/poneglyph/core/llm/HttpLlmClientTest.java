package poneglyph.core.llm;

import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;
import poneglyph.core.CancelToken;
import poneglyph.core.CancelledException;
import poneglyph.core.testutil.FakeOpenAiServer;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpLlmClientTest {

    private static LlmConfig config(FakeOpenAiServer server, LlmConfig.ApiMode mode, String apiKey, Duration timeout) {
        return new LlmConfig(server.baseUrl(), "test-model", apiKey, mode, timeout, 0.0, 128);
    }

    @Test
    void chatRouteSendsSystemAndUserMessagesAndParsesContent() throws Exception {
        try (FakeOpenAiServer server = new FakeOpenAiServer()) {
            server.reply("```c\nint f(void){return 1;}\n```");
            HttpLlmClient client = new HttpLlmClient(config(server, LlmConfig.ApiMode.CHAT, "secret", Duration.ofSeconds(5)));

            String out = client.complete(LlmRequest.decompile("SYS", "USER"), CancelToken.NONE);

            assertEquals("```c\nint f(void){return 1;}\n```", out);
            FakeOpenAiServer.Recorded r = server.recorded().get(0);
            assertEquals("/v1/chat/completions", r.path());
            assertEquals("test-model", r.body().get("model").getAsString());
            assertFalse(r.body().get("stream").getAsBoolean());
            JsonArray messages = r.body().getAsJsonArray("messages");
            assertEquals(2, messages.size());
            assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
            assertEquals("SYS", messages.get(0).getAsJsonObject().get("content").getAsString());
            assertEquals("user", messages.get(1).getAsJsonObject().get("role").getAsString());
            assertEquals("USER", messages.get(1).getAsJsonObject().get("content").getAsString());
            assertEquals("Bearer secret", r.headers().get("Authorization").get(0));
            assertTrue(client.describe().contains("test-model"));
        }
    }

    @Test
    void completionRouteSendsSinglePromptAndReadsText() throws Exception {
        try (FakeOpenAiServer server = new FakeOpenAiServer()) {
            server.reply("int g(void) { return 2; }");
            HttpLlmClient client = new HttpLlmClient(config(server, LlmConfig.ApiMode.COMPLETION, null, Duration.ofSeconds(5)));

            String out = client.complete(LlmRequest.decompile("SYS", "USER"), CancelToken.NONE);

            assertEquals("int g(void) { return 2; }", out);
            FakeOpenAiServer.Recorded r = server.recorded().get(0);
            assertEquals("/v1/completions", r.path());
            assertEquals("SYS\n\nUSER", r.body().get("prompt").getAsString());
            assertNull(r.body().get("messages"));
            assertNull(r.headers().get("Authorization"));
        }
    }

    @Test
    void httpErrorsBecomeLlmExceptions() throws Exception {
        try (FakeOpenAiServer server = new FakeOpenAiServer()) {
            server.replyRaw(404, "{\"error\":{\"message\":\"model 'nope' not found\"}}");
            server.replyRaw(200, "{\"error\":{\"message\":\"boom\"}}");
            server.replyRaw(200, "<html>not json</html>");
            server.replyRaw(200, "{\"choices\":[]}");
            HttpLlmClient client = new HttpLlmClient(config(server, LlmConfig.ApiMode.CHAT, null, Duration.ofSeconds(5)));
            LlmRequest req = LlmRequest.decompile("", "x");

            LlmException e1 = assertThrows(LlmException.class, () -> client.complete(req, CancelToken.NONE));
            assertTrue(e1.getMessage().contains("404") && e1.getMessage().contains("model 'nope' not found"), e1.getMessage());
            LlmException e2 = assertThrows(LlmException.class, () -> client.complete(req, CancelToken.NONE));
            assertTrue(e2.getMessage().contains("boom"), e2.getMessage());
            LlmException e3 = assertThrows(LlmException.class, () -> client.complete(req, CancelToken.NONE));
            assertTrue(e3.getMessage().contains("not JSON"), e3.getMessage());
            LlmException e4 = assertThrows(LlmException.class, () -> client.complete(req, CancelToken.NONE));
            assertTrue(e4.getMessage().contains("no choices"), e4.getMessage());
        }
    }

    @Test
    void connectionRefusedGivesActionableMessage() throws Exception {
        String url;
        try (FakeOpenAiServer server = new FakeOpenAiServer()) {
            url = server.baseUrl();
        }
        HttpLlmClient client = new HttpLlmClient(new LlmConfig(url, "m", null, LlmConfig.ApiMode.CHAT,
                Duration.ofSeconds(5), 0, 10));
        LlmException e = assertThrows(LlmException.class,
                () -> client.complete(LlmRequest.decompile("", "x"), CancelToken.NONE));
        assertTrue(e.getMessage().toLowerCase().contains("connect"), e.getMessage());
    }

    @Test
    void cancelTokenAbortsAnInFlightRequest() throws Exception {
        try (FakeOpenAiServer server = new FakeOpenAiServer()) {
            server.replyAfter(5000, "too late");
            HttpLlmClient client = new HttpLlmClient(config(server, LlmConfig.ApiMode.CHAT, null, Duration.ofSeconds(30)));
            AtomicBoolean cancelled = new AtomicBoolean(false);
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                    // fine
                }
                cancelled.set(true);
            });
            t.start();
            long start = System.nanoTime();
            assertThrows(CancelledException.class,
                    () -> client.complete(LlmRequest.decompile("", "x"), cancelled::get));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs < 3000, "cancel took " + elapsedMs + "ms");
        }
    }

    @Test
    void timeoutGivesActionableMessage() throws Exception {
        try (FakeOpenAiServer server = new FakeOpenAiServer()) {
            server.replyAfter(3000, "too late");
            HttpLlmClient client = new HttpLlmClient(config(server, LlmConfig.ApiMode.CHAT, null, Duration.ofMillis(500)));
            LlmException e = assertThrows(LlmException.class,
                    () -> client.complete(LlmRequest.decompile("", "x"), CancelToken.NONE));
            assertTrue(e.getMessage().contains("Timed out"), e.getMessage());
        }
    }

    @Test
    void parsesReasoningContentFallback() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"reasoning_content\":\"int r(void){return 0;}\"}}]}";
        assertEquals("int r(void){return 0;}", HttpLlmClient.parseResponse(body, java.net.URI.create("http://x")));
    }
}
