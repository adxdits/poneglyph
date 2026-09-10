package poneglyph.core.compile;

import poneglyph.core.Text;

import java.util.List;

/** What gcc said. */
public record CompileResult(boolean ok, int exitCode, String stdout, String stderr, List<String> command,
                            boolean timedOut, long durationMs) {

    /** Diagnostics suitable for feeding back to the model. */
    public String errors() {
        if (timedOut) {
            return "compiler timed out";
        }
        String s = stderr.isBlank() ? stdout : stderr;
        return s.strip();
    }

    public String errors(int maxChars) {
        return Text.truncate(errors(), maxChars);
    }

    public String commandLine() {
        return String.join(" ", command);
    }
}
