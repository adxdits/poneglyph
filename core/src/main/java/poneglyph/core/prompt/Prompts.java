package poneglyph.core.prompt;

import poneglyph.core.Stage;
import poneglyph.core.Text;
import poneglyph.core.llm.LlmRequest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Prompt templates. Defaults ship as {@code prompts/<key>.txt} on the classpath; any of them can be
 * overridden by dropping a file with the same name into a user directory (see {@link #load}).
 *
 * <p>Placeholders: {@code {pseudo_code}}, {@code {code}}, {@code {errors}}, {@code {failed_cases}},
 * {@code {feedback}}, {@code {function_name}}.
 */
public final class Prompts {

    public static final String SYSTEM = "system";
    public static final String INITIAL = "initial";
    public static final String REFINE = "refine";
    public static final String FEEDBACK_INVALID_CODE = "feedback_invalid_code";
    public static final String FEEDBACK_COMPILE_ERROR = "feedback_compile_error";
    public static final String FEEDBACK_RUNTIME_ERROR = "feedback_runtime_error";
    public static final String FEEDBACK_TEST_MISMATCH = "feedback_test_mismatch";
    public static final String TEST_SYSTEM = "test_system";
    public static final String TEST_GENERATION = "test_generation";

    public static final List<String> KEYS = List.of(SYSTEM, INITIAL, REFINE, FEEDBACK_INVALID_CODE,
            FEEDBACK_COMPILE_ERROR, FEEDBACK_RUNTIME_ERROR, FEEDBACK_TEST_MISMATCH, TEST_SYSTEM, TEST_GENERATION);

    private static final String RESOURCE_DIR = "prompts/";

    private final Map<String, String> templates;
    private final Path overrideDir;

    private Prompts(Map<String, String> templates, Path overrideDir) {
        this.templates = templates;
        this.overrideDir = overrideDir;
    }

    /** The built-in templates. */
    public static Prompts defaults() {
        Map<String, String> map = new LinkedHashMap<>();
        for (String key : KEYS) {
            map.put(key, loadResource(key));
        }
        return new Prompts(map, null);
    }

    /**
     * Built-in templates, with any {@code <key>.txt} found in {@code overrideDir} taking precedence.
     * A null or missing directory simply yields the defaults.
     */
    public static Prompts load(Path overrideDir) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        for (String key : KEYS) {
            String value = null;
            if (overrideDir != null) {
                Path file = overrideDir.resolve(key + ".txt");
                if (Files.isRegularFile(file)) {
                    value = Files.readString(file, StandardCharsets.UTF_8);
                }
            }
            map.put(key, value != null ? value : loadResource(key));
        }
        return new Prompts(map, overrideDir);
    }

    /** Writes the built-in templates into {@code dir} so users have something to edit. Existing files are kept. */
    public static void exportDefaults(Path dir) throws IOException {
        Files.createDirectories(dir);
        for (String key : KEYS) {
            Path file = dir.resolve(key + ".txt");
            if (!Files.exists(file)) {
                Files.writeString(file, loadResource(key), StandardCharsets.UTF_8);
            }
        }
    }

    public Path overrideDir() {
        return overrideDir;
    }

    public String get(String key) {
        String t = templates.get(key);
        if (t == null) {
            throw new IllegalArgumentException("Unknown prompt key: " + key);
        }
        return t;
    }

    public Prompts with(String key, String template) {
        Map<String, String> copy = new LinkedHashMap<>(templates);
        copy.put(Objects.requireNonNull(key), Objects.requireNonNull(template));
        return new Prompts(copy, overrideDir);
    }

    public String render(String key, Map<String, String> vars) {
        return render(get(key), vars, true);
    }

    static String render(String template, Map<String, String> vars, boolean unused) {
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", Text.nullToEmpty(e.getValue()));
        }
        return out;
    }

    public LlmRequest initial(String pseudoCode, String functionName) {
        Map<String, String> vars = Map.of(
                "pseudo_code", pseudoCode.strip(),
                "function_name", Text.nullToEmpty(functionName));
        return LlmRequest.decompile(render(SYSTEM, vars), render(INITIAL, vars));
    }

    public LlmRequest refine(String pseudoCode, String previousCode, String feedback, String functionName) {
        Map<String, String> vars = Map.of(
                "pseudo_code", pseudoCode.strip(),
                "code", Text.nullToEmpty(previousCode).strip(),
                "feedback", Text.nullToEmpty(feedback).strip(),
                "function_name", Text.nullToEmpty(functionName));
        return LlmRequest.decompile(render(SYSTEM, vars), render(REFINE, vars));
    }

    /** The feedback paragraph for a failed stage; {@code detail} is the error text or failed cases. */
    public String feedback(Stage stage, String detail) {
        String d = Text.nullToEmpty(detail).strip();
        Map<String, String> vars = Map.of("errors", d, "failed_cases", d);
        switch (stage) {
            case INVALID_CODE:
                return render(FEEDBACK_INVALID_CODE, vars);
            case COMPILE_ERROR:
                return render(FEEDBACK_COMPILE_ERROR, vars);
            case RUNTIME_ERROR:
                return render(FEEDBACK_RUNTIME_ERROR, vars);
            case TEST_MISMATCH:
                return render(FEEDBACK_TEST_MISMATCH, vars);
            default:
                throw new IllegalArgumentException("No feedback template for stage " + stage);
        }
    }

    public LlmRequest testGeneration(String refinedCode, String pseudoCode, String functionName) {
        Map<String, String> vars = Map.of(
                "code", refinedCode.strip(),
                "pseudo_code", Text.nullToEmpty(pseudoCode).strip(),
                "function_name", Text.nullToEmpty(functionName));
        return LlmRequest.testGen(render(TEST_SYSTEM, vars), render(TEST_GENERATION, vars));
    }

    private static String loadResource(String key) {
        String name = RESOURCE_DIR + key + ".txt";
        try (InputStream in = Prompts.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing built-in prompt resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read built-in prompt resource " + name, e);
        }
    }
}
