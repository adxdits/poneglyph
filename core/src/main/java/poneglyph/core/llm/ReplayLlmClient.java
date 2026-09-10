package poneglyph.core.llm;

import poneglyph.core.CancelToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Replays canned model responses instead of calling a server. Used by the CLI's {@code --replay}
 * flag so the verification loop can be demonstrated (and tested) without any model at all.
 *
 * <p>Replay file format: responses separated by lines of the form {@code === decompile ===} or
 * {@code === tests ===}. Responses are consumed in order per kind; when a kind runs out, its last
 * response is repeated. Example:
 *
 * <pre>
 * === decompile ===
 * int add(int a, int b) { return a + b }   // broken on purpose
 * === tests ===
 * #include &lt;assert.h&gt;
 * int main(void) { assert(add(1, 2) == 3); return 0; }
 * === decompile ===
 * int add(int a, int b) { return a + b; }
 * </pre>
 */
public final class ReplayLlmClient implements LlmClient {

    private static final Pattern HEADER = Pattern.compile("^===+\\s*(decompile|tests?|test_gen|testgen)\\s*===+\\s*$",
            Pattern.CASE_INSENSITIVE);

    private final Deque<String> decompileResponses = new ArrayDeque<>();
    private final Deque<String> testResponses = new ArrayDeque<>();
    private String lastDecompile;
    private String lastTest;
    private final List<LlmRequest> requests = new ArrayList<>();

    public ReplayLlmClient(List<String> decompile, List<String> tests) {
        decompileResponses.addAll(Objects.requireNonNull(decompile));
        testResponses.addAll(Objects.requireNonNull(tests));
    }

    public static ReplayLlmClient fromFile(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static ReplayLlmClient parse(String text) {
        List<String> decompile = new ArrayList<>();
        List<String> tests = new ArrayList<>();
        StringBuilder current = null;
        List<String> target = null;
        for (String line : text.replace("\r\n", "\n").split("\n")) {
            var m = HEADER.matcher(line.trim());
            if (m.matches()) {
                if (current != null && target != null) {
                    target.add(finish(current));
                }
                current = new StringBuilder();
                target = m.group(1).toLowerCase().startsWith("decompile") ? decompile : tests;
                continue;
            }
            if (current == null) {
                // Text before the first header is treated as a decompile response.
                current = new StringBuilder();
                target = decompile;
            }
            current.append(line).append('\n');
        }
        if (current != null && target != null && !current.toString().isBlank()) {
            target.add(finish(current));
        }
        return new ReplayLlmClient(decompile, tests);
    }

    /** Collapses trailing blank lines to a single newline. */
    private static String finish(StringBuilder section) {
        String s = section.toString();
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '\n') {
            end--;
        }
        return s.substring(0, end) + "\n";
    }

    /** Every request that was replayed, in order. Useful for tests and the CLI log. */
    public List<LlmRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public String describe() {
        return "replay (" + decompileResponses.size() + " decompile, " + testResponses.size() + " test responses queued)";
    }

    @Override
    public synchronized String complete(LlmRequest request, CancelToken cancel) throws LlmException {
        if (cancel != null) {
            cancel.throwIfCancelled();
        }
        requests.add(request);
        if (request.kind() == LlmRequest.Kind.TEST_GEN) {
            if (!testResponses.isEmpty()) {
                lastTest = testResponses.poll();
            }
            if (lastTest == null) {
                throw new LlmException("Replay file has no '=== tests ===' section");
            }
            return lastTest;
        }
        if (!decompileResponses.isEmpty()) {
            lastDecompile = decompileResponses.poll();
        }
        if (lastDecompile == null) {
            throw new LlmException("Replay file has no '=== decompile ===' section");
        }
        return lastDecompile;
    }
}
