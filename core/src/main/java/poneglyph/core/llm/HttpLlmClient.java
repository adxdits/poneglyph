package poneglyph.core.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import poneglyph.core.CancelToken;
import poneglyph.core.CancelledException;
import poneglyph.core.Text;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Talks to any OpenAI-compatible server using only {@link java.net.http.HttpClient} and Gson.
 * Supports both the chat route and the legacy completion route; see {@link LlmConfig.ApiMode}.
 */
public final class HttpLlmClient implements LlmClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final long CANCEL_POLL_MS = 200;

    private final LlmConfig config;
    private final HttpClient http;

    public HttpLlmClient(LlmConfig config) {
        this(config, HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    public HttpLlmClient(LlmConfig config, HttpClient http) {
        this.config = Objects.requireNonNull(config, "config");
        this.http = Objects.requireNonNull(http, "http");
    }

    public LlmConfig config() {
        return config;
    }

    @Override
    public String describe() {
        return config.model() + " @ " + config.baseUrl() + " (" + config.apiMode().name().toLowerCase() + ")";
    }

    @Override
    public String complete(LlmRequest request, CancelToken cancel) throws LlmException {
        Objects.requireNonNull(request, "request");
        CancelToken token = cancel == null ? CancelToken.NONE : cancel;
        token.throwIfCancelled();

        String route = config.apiMode() == LlmConfig.ApiMode.CHAT ? "/chat/completions" : "/completions";
        URI uri = URI.create(config.baseUrl() + route);
        String body = buildBody(request);

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(config.timeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (config.apiKey() != null) {
            builder.header("Authorization", "Bearer " + config.apiKey());
        }

        HttpResponse<String> response = send(builder.build(), token, uri);

        if (response.statusCode() / 100 != 2) {
            throw new LlmException("Model endpoint " + uri + " returned HTTP " + response.statusCode()
                    + ": " + Text.truncate(extractErrorMessage(response.body()), 500));
        }
        return parseResponse(response.body(), uri);
    }

    private HttpResponse<String> send(HttpRequest httpRequest, CancelToken token, URI uri) throws LlmException {
        CompletableFuture<HttpResponse<String>> future =
                http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            while (true) {
                if (token.isCancelled()) {
                    future.cancel(true);
                    throw new CancelledException();
                }
                try {
                    return future.get(CANCEL_POLL_MS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException poll) {
                    // keep polling the cancel token
                }
            }
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new CancelledException();
        } catch (CancellationException e) {
            throw new CancelledException();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof HttpTimeoutException) {
                throw new LlmException("Timed out after " + config.timeout().toSeconds()
                        + "s waiting for " + uri + ". Increase the timeout or use a smaller model.", cause);
            }
            if (cause instanceof ConnectException) {
                throw new LlmException("Could not connect to " + uri
                        + ". Is the model server running? Check the endpoint URL in settings.", cause);
            }
            if (cause instanceof IOException) {
                throw new LlmException("I/O error talking to " + uri + ": " + cause.getMessage(), cause);
            }
            throw new LlmException("Request to " + uri + " failed: " + cause, cause);
        }
    }

    String buildBody(LlmRequest request) {
        JsonObject root = new JsonObject();
        root.addProperty("model", config.model());
        root.addProperty("temperature", config.temperature());
        root.addProperty("max_tokens", config.maxTokens());
        root.addProperty("stream", false);
        if (config.apiMode() == LlmConfig.ApiMode.CHAT) {
            JsonArray messages = new JsonArray();
            if (!request.systemPrompt().isBlank()) {
                messages.add(message("system", request.systemPrompt()));
            }
            messages.add(message("user", request.userPrompt()));
            root.add("messages", messages);
        } else {
            root.addProperty("prompt", request.asSinglePrompt());
        }
        return root.toString();
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    /** Extracts the generated text from either response shape. Package-private for tests. */
    static String parseResponse(String body, URI uri) throws LlmException {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                throw new LlmException("Unexpected response from " + uri + ": " + Text.truncate(body, 300));
            }
            root = parsed.getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            throw new LlmException("Response from " + uri + " is not JSON: " + Text.truncate(body, 300), e);
        }
        if (root.has("error") && !root.get("error").isJsonNull()) {
            throw new LlmException("Model endpoint reported an error: " + extractErrorMessage(body));
        }
        JsonArray choices = root.has("choices") && root.get("choices").isJsonArray()
                ? root.getAsJsonArray("choices") : null;
        if (choices == null || choices.isEmpty()) {
            throw new LlmException("Response from " + uri + " has no choices: " + Text.truncate(body, 300));
        }
        JsonObject first = choices.get(0).getAsJsonObject();
        // chat shape
        if (first.has("message") && first.get("message").isJsonObject()) {
            JsonObject msg = first.getAsJsonObject("message");
            String content = stringOrNull(msg, "content");
            if (content == null || content.isBlank()) {
                // Some servers put reasoning-model output here when content is empty.
                content = stringOrNull(msg, "reasoning_content");
            }
            if (content != null) {
                return content;
            }
        }
        // completion shape
        String text = stringOrNull(first, "text");
        if (text != null) {
            return text;
        }
        throw new LlmException("Could not find generated text in response from " + uri + ": "
                + Text.truncate(body, 300));
    }

    private static String stringOrNull(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return null;
        }
        return el.getAsString();
    }

    private static String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "(empty body)";
        }
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed.isJsonObject()) {
                JsonObject obj = parsed.getAsJsonObject();
                JsonElement err = obj.get("error");
                if (err != null) {
                    if (err.isJsonPrimitive()) {
                        return err.getAsString();
                    }
                    if (err.isJsonObject() && err.getAsJsonObject().has("message")) {
                        return err.getAsJsonObject().get("message").getAsString();
                    }
                }
                if (obj.has("message") && obj.get("message").isJsonPrimitive()) {
                    return obj.get("message").getAsString();
                }
            }
        } catch (RuntimeException ignored) {
            // fall through and return the raw body
        }
        return body;
    }
}
