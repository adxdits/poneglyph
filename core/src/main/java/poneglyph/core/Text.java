package poneglyph.core;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Small string helpers shared across the core module. */
public final class Text {

    private Text() {
    }

    /** Truncates {@code s} to at most {@code max} characters, appending a marker if anything was cut. */
    public static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, Math.max(0, max)) + "\n... [truncated " + (s.length() - max) + " characters]";
    }

    /** Normalises line endings to '\n' and strips a UTF-8 byte order mark. */
    public static String normalizeNewlines(String s) {
        if (s == null) {
            return "";
        }
        String out = s.replace("\r\n", "\n").replace('\r', '\n');
        if (!out.isEmpty() && out.charAt(0) == (char) 0xFEFF) {
            out = out.substring(1);
        }
        return out;
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static String nullToEmpty(String s) {
        return Objects.requireNonNullElse(s, "");
    }

    public static byte[] utf8(String s) {
        return nullToEmpty(s).getBytes(StandardCharsets.UTF_8);
    }

    /** Formats a duration in milliseconds as e.g. {@code 1.2s}. */
    public static String seconds(long millis) {
        return String.format("%.1fs", millis / 1000.0);
    }
}
