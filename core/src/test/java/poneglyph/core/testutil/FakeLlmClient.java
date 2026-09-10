package poneglyph.core.testutil;

import poneglyph.core.CancelToken;
import poneglyph.core.llm.LlmClient;
import poneglyph.core.llm.LlmException;
import poneglyph.core.llm.LlmRequest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Scripted model: responses are queued per request kind and consumed in order. Running out of
 * scripted responses is a test bug, so it fails loudly.
 */
public final class FakeLlmClient implements LlmClient {

    private final Deque<Object> decompile = new ArrayDeque<>();
    private final Deque<Object> tests = new ArrayDeque<>();
    public final List<LlmRequest> requests = new ArrayList<>();

    public FakeLlmClient decompile(String... responses) {
        decompile.addAll(List.of(responses));
        return this;
    }

    public FakeLlmClient tests(String... responses) {
        tests.addAll(List.of(responses));
        return this;
    }

    public FakeLlmClient decompileFailure(String message) {
        decompile.add(new LlmException(message));
        return this;
    }

    public FakeLlmClient testFailure(String message) {
        tests.add(new LlmException(message));
        return this;
    }

    public List<LlmRequest> requestsOf(LlmRequest.Kind kind) {
        List<LlmRequest> out = new ArrayList<>();
        for (LlmRequest r : requests) {
            if (r.kind() == kind) {
                out.add(r);
            }
        }
        return out;
    }

    @Override
    public String describe() {
        return "fake";
    }

    @Override
    public String complete(LlmRequest request, CancelToken cancel) throws LlmException {
        if (cancel != null) {
            cancel.throwIfCancelled();
        }
        requests.add(request);
        Deque<Object> queue = request.kind() == LlmRequest.Kind.TEST_GEN ? tests : decompile;
        Object next = queue.poll();
        if (next == null) {
            throw new IllegalStateException("FakeLlmClient: no scripted " + request.kind() + " response left (request #"
                    + requests.size() + ")");
        }
        if (next instanceof LlmException) {
            throw (LlmException) next;
        }
        return (String) next;
    }
}
