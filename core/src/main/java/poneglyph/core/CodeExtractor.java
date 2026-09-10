package poneglyph.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls C source out of a chatty model response: strips {@code <think>} blocks, Markdown fences and
 * surrounding prose. If nothing resembling a C function definition remains, {@link #extract}
 * returns empty and the caller treats the turn as "invalid code".
 */
public final class CodeExtractor {

    private static final Pattern THINK_BLOCK = Pattern.compile("<think>.*?</think>", Pattern.DOTALL);
    /** An opening fence line (any info string), the body, then the closing fence. */
    private static final Pattern FENCED_BLOCK = Pattern.compile("```[^\\n]*\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern FENCE_LINE = Pattern.compile("(?m)^[ \\t]*```.*$");

    /**
     * A line that starts a C function definition: an identifier-ish return type, a name, a
     * parenthesised parameter list and then either an opening brace or the end of the line (brace on
     * the next line). Prototypes are excluded by forbidding ';'.
     */
    private static final Pattern FUNCTION_DEF = Pattern.compile(
            "^[ \\t]*(?:[A-Za-z_][A-Za-z0-9_]*[ \\t*]+)+\\**\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^;{}]*)\\)\\s*(?:\\{.*)?$");

    private static final Pattern CODE_START = Pattern.compile(
            "^[ \\t]*(?:#\\s*(?:include|define|if|ifdef|ifndef|pragma)\\b|typedef\\b|struct\\s+[A-Za-z_]\\w*\\s*\\{|union\\s+[A-Za-z_]\\w*\\s*\\{|enum\\s+[A-Za-z_]\\w*\\s*\\{|//|/\\*|static\\b|extern\\b|inline\\b)");

    private static final Set<String> NOT_A_RETURN_TYPE = Set.of(
            "if", "else", "while", "for", "switch", "return", "do", "case", "sizeof", "goto", "break", "continue");

    private CodeExtractor() {
    }

    /** Returns the C source found in {@code modelOutput}, or empty if no function definition is present. */
    public static Optional<String> extract(String modelOutput) {
        if (Text.isBlank(modelOutput)) {
            return Optional.empty();
        }
        String text = Text.normalizeNewlines(modelOutput);
        text = THINK_BLOCK.matcher(text).replaceAll("");
        if (countFences(text) % 2 == 1) {
            // Output was cut off (max_tokens) or the model forgot the closing fence: close it ourselves.
            text = text + "\n```";
        }

        List<String> fenced = new ArrayList<>();
        Matcher m = FENCED_BLOCK.matcher(text);
        while (m.find()) {
            fenced.add(m.group(1));
        }

        String candidate = null;
        if (!fenced.isEmpty()) {
            // Prefer the longest fenced block that defines a function, and keep any earlier
            // declaration-only blocks (typedefs, #includes) in front of it.
            int bestIndex = -1;
            for (int i = 0; i < fenced.size(); i++) {
                String block = fenced.get(i);
                if (containsFunctionDefinition(block)
                        && (bestIndex < 0 || block.length() > fenced.get(bestIndex).length())) {
                    bestIndex = i;
                }
            }
            if (bestIndex >= 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < bestIndex; i++) {
                    String block = fenced.get(i);
                    if (!containsFunctionDefinition(block) && looksLikeDeclarations(block)) {
                        sb.append(block.strip()).append("\n\n");
                    }
                }
                sb.append(fenced.get(bestIndex).strip());
                candidate = sb.toString();
            }
        }
        if (candidate == null) {
            // No usable fenced block: treat the whole response as code with fence lines removed.
            candidate = FENCE_LINE.matcher(text).replaceAll("");
        }

        String trimmed = trimProse(candidate);
        if (!containsFunctionDefinition(trimmed)) {
            return Optional.empty();
        }
        return Optional.of(trimmed.strip() + "\n");
    }

    /** True if the text contains at least one function definition (signature followed by a body). */
    public static boolean containsFunctionDefinition(String text) {
        if (text == null || text.indexOf('{') < 0 || text.indexOf('}') < 0) {
            return false;
        }
        for (String line : text.split("\n")) {
            if (isFunctionDefinitionLine(line)) {
                return true;
            }
        }
        return false;
    }

    /** Name of the first function defined in {@code code}, e.g. {@code FUN_00101149} in Ghidra output. */
    public static Optional<String> findFunctionName(String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (String line : Text.normalizeNewlines(code).split("\n")) {
            Matcher m = FUNCTION_DEF.matcher(line);
            if (m.matches() && !NOT_A_RETURN_TYPE.contains(firstWord(line)) && !NOT_A_RETURN_TYPE.contains(m.group(1))) {
                return Optional.of(m.group(1));
            }
        }
        return Optional.empty();
    }

    /** Names of every function defined in {@code code}, in order of appearance. */
    public static List<String> findFunctionNames(String code) {
        List<String> names = new ArrayList<>();
        if (code == null) {
            return names;
        }
        for (String line : Text.normalizeNewlines(code).split("\n")) {
            Matcher m = FUNCTION_DEF.matcher(line);
            if (m.matches() && !NOT_A_RETURN_TYPE.contains(firstWord(line)) && !NOT_A_RETURN_TYPE.contains(m.group(1))) {
                names.add(m.group(1));
            }
        }
        return names;
    }

    private static boolean isFunctionDefinitionLine(String line) {
        if (line.indexOf('(') < 0) {
            return false;
        }
        String first = firstWord(line);
        if (NOT_A_RETURN_TYPE.contains(first)) {
            return false;
        }
        Matcher m = FUNCTION_DEF.matcher(line);
        return m.matches() && !NOT_A_RETURN_TYPE.contains(m.group(1));
    }

    private static int countFences(String text) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf("```", idx)) >= 0) {
            count++;
            idx += 3;
        }
        return count;
    }

    private static String firstWord(String line) {
        String t = line.strip();
        int i = 0;
        while (i < t.length() && (Character.isLetterOrDigit(t.charAt(i)) || t.charAt(i) == '_')) {
            i++;
        }
        return t.substring(0, i);
    }

    private static boolean looksLikeDeclarations(String block) {
        String s = block.strip();
        return !s.isEmpty() && (s.contains(";") || s.startsWith("#"));
    }

    /**
     * Drops leading lines until something that looks like C starts, and trailing lines after the last
     * line that plausibly ends C code (a closing brace or a semicolon).
     */
    static String trimProse(String text) {
        String[] lines = Text.normalizeNewlines(text).split("\n", -1);
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (CODE_START.matcher(line).find() || isFunctionDefinitionLine(line)) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return "";
        }
        int end = -1;
        for (int i = lines.length - 1; i >= start; i--) {
            String t = lines[i].strip();
            if (t.equals("}") || t.startsWith("}") || t.endsWith("}") || t.endsWith(";")) {
                end = i;
                break;
            }
        }
        if (end < start) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }
}
