package poneglyph.core.testutil;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;

/**
 * In-process OpenAI-compatible server built on the JDK's HttpServer. Serves both
 * {@code /v1/chat/completions} and {@code /v1/completions}, shaping each queued response to match
 * the route that was called. Records every request so tests can assert on what was sent.
 */
public final class FakeOpenAiServer implements AutoCloseable {

    /** A queued reply. */
    public record Reply(int status, String content, boolean raw, long delayMs) {
    }

    /** A recorded request. */
    public record Recorded(String path, Map<String, List<String>> headers, JsonObject body) {
    }

    private final HttpServer server;
    private final ConcurrentLinkedDeque<Reply> replies = new ConcurrentLinkedDeque<>();
    private final List<Recorded> recorded = Collections.synchronizedList(new ArrayList<>());

    public FakeOpenAiServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    public FakeOpenAiServer reply(String content) {
        replies.add(new Reply(200, content, false, 0));
        return this;
    }

    public FakeOpenAiServer replyAfter(long delayMs, String content) {
        replies.add(new Reply(200, content, false, delayMs));
        return this;
    }

    public FakeOpenAiServer replyRaw(int status, String body) {
        replies.add(new Reply(status, body, true, 0));
        return this;
    }

    public List<Recorded> recorded() {
        return new ArrayList<>(recorded);
    }

    public int requestCount() {
        return recorded.size();
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject json;
        try {
            json = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            json = new JsonObject();
            json.addProperty("_unparseable", body);
        }
        recorded.add(new Recorded(ex.getRequestURI().getPath(), ex.getRequestHeaders(), json));

        Reply reply = replies.poll();
        if (reply == null) {
            send(ex, 500, "{\"error\":{\"message\":\"FakeOpenAiServer: no scripted reply left\"}}");
            return;
        }
        if (reply.delayMs() > 0) {
            try {
                Thread.sleep(reply.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (reply.raw()) {
            send(ex, reply.status(), reply.content());
            return;
        }
        boolean chat = ex.getRequestURI().getPath().endsWith("/chat/completions");
        JsonObject root = new JsonObject();
        root.addProperty("id", "fake-" + recorded.size());
        root.addProperty("object", chat ? "chat.completion" : "text_completion");
        root.addProperty("model", json.has("model") ? json.get("model").getAsString() : "fake");
        JsonArray choices = new JsonArray();
        JsonObject choice = new JsonObject();
        choice.addProperty("index", 0);
        choice.addProperty("finish_reason", "stop");
        if (chat) {
            JsonObject message = new JsonObject();
            message.addProperty("role", "assistant");
            message.addProperty("content", reply.content());
            choice.add("message", message);
        } else {
            choice.addProperty("text", reply.content());
        }
        choices.add(choice);
        root.add("choices", choices);
        send(ex, 200, root.toString());
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
