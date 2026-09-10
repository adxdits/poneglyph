package poneglyph.ghidra;

import ghidra.framework.options.OptionType;
import ghidra.framework.options.OptionsChangeListener;
import ghidra.framework.options.ToolOptions;
import ghidra.framework.plugintool.PluginTool;
import poneglyph.core.compile.Compiler;
import poneglyph.core.llm.LlmConfig;
import poneglyph.core.loop.LoopConfig;
import poneglyph.core.prompt.Prompts;
import poneglyph.core.verify.TestRunner;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * All user settings, stored in the tool's options under the "Poneglyph" category so they are
 * persisted with the tool and also editable through Edit &gt; Tool Options.
 */
public final class PoneglyphSettings {

    public static final String CATEGORY = "Poneglyph";

    static final String ENDPOINT = "Model endpoint URL";
    static final String MODEL = "Model name";
    static final String TEST_MODEL = "Test-generation model";
    static final String API_MODE = "API mode";
    static final String API_KEY = "API key";
    static final String MAX_TURNS = "Max refinement turns";
    static final String GCC_PATH = "gcc path";
    static final String GCC_FLAGS = "Extra gcc flags";
    static final String LLM_TIMEOUT = "Model timeout (seconds)";
    static final String COMPILE_TIMEOUT = "Compile timeout (seconds)";
    static final String RUN_TIMEOUT = "Test run timeout (seconds)";
    static final String DECOMPILE_TIMEOUT = "Ghidra decompile timeout (seconds)";
    static final String WRITE_COMMENT = "Write result as function comment";
    static final String PROMPTS_DIR = "Prompt override directory";

    static final int DEFAULT_DECOMPILE_TIMEOUT = 60;

    private final ToolOptions options;

    public PoneglyphSettings(PluginTool tool) {
        this.options = tool.getOptions(CATEGORY);
        register();
    }

    private void register() {
        options.registerOption(ENDPOINT, OptionType.STRING_TYPE, LlmConfig.DEFAULT_BASE_URL, null,
                "Base URL of an OpenAI-compatible server, e.g. http://localhost:11434/v1 (Ollama), "
                        + "http://localhost:8000/v1 (vLLM) or http://localhost:1234/v1 (LM Studio). "
                        + "Nothing is ever sent anywhere else.");
        options.registerOption(MODEL, OptionType.STRING_TYPE, LlmConfig.DEFAULT_MODEL, null,
                "Model used to rewrite the pseudo-code.");
        options.registerOption(TEST_MODEL, OptionType.STRING_TYPE, "", null,
                "Optional instruct model used to write unit tests. Leave blank to use the main model. "
                        + "Recommended when the main model is a completion-style decompiler model such as LLM4Decompile.");
        options.registerOption(API_MODE, OptionType.STRING_TYPE, "chat", null,
                "'chat' for /chat/completions (instruct models) or 'completion' for /completions "
                        + "(completion-style models such as LLM4Decompile-ref).");
        options.registerOption(API_KEY, OptionType.STRING_TYPE, "", null,
                "Bearer token sent as Authorization header, if your server needs one.");
        options.registerOption(MAX_TURNS, OptionType.INT_TYPE, LoopConfig.DEFAULT_MAX_TURNS, null,
                "Maximum number of model calls per function (initial attempt plus refinements).");
        options.registerOption(GCC_PATH, OptionType.STRING_TYPE, Compiler.DEFAULT_GCC, null,
                "Path to gcc (clang also works). Used to compile and run the generated tests.");
        options.registerOption(GCC_FLAGS, OptionType.STRING_TYPE, "", null,
                "Extra flags passed to the compiler, space separated (e.g. -fsanitize=address,undefined).");
        options.registerOption(LLM_TIMEOUT, OptionType.INT_TYPE, (int) LlmConfig.DEFAULT_TIMEOUT.toSeconds(), null,
                "Per-request timeout for the model server.");
        options.registerOption(COMPILE_TIMEOUT, OptionType.INT_TYPE, (int) Compiler.DEFAULT_TIMEOUT.toSeconds(), null,
                "Timeout for one gcc invocation.");
        options.registerOption(RUN_TIMEOUT, OptionType.INT_TYPE, (int) TestRunner.DEFAULT_RUN_TIMEOUT.toSeconds(), null,
                "Timeout for running the generated test binary (guards against infinite loops).");
        options.registerOption(DECOMPILE_TIMEOUT, OptionType.INT_TYPE, DEFAULT_DECOMPILE_TIMEOUT, null,
                "Timeout for Ghidra's own decompiler when fetching the pseudo-code.");
        options.registerOption(WRITE_COMMENT, OptionType.BOOLEAN_TYPE, Boolean.FALSE, null,
                "After a run, store the refined C and its verification status as the function's comment.");
        options.registerOption(PROMPTS_DIR, OptionType.STRING_TYPE, "", null,
                "Directory containing prompt template overrides (<key>.txt). Blank uses the built-in prompts. "
                        + "Use the CLI's --export-prompts to get editable copies.");
    }

    public void addChangeListener(OptionsChangeListener listener) {
        options.addOptionsChangeListener(listener);
    }

    public void removeChangeListener(OptionsChangeListener listener) {
        options.removeOptionsChangeListener(listener);
    }

    // --- getters -------------------------------------------------------------------------------

    public String endpoint() {
        return options.getString(ENDPOINT, LlmConfig.DEFAULT_BASE_URL);
    }

    public String model() {
        return options.getString(MODEL, LlmConfig.DEFAULT_MODEL);
    }

    public String testModel() {
        return options.getString(TEST_MODEL, "").trim();
    }

    public boolean hasSeparateTestModel() {
        String t = testModel();
        return !t.isEmpty() && !t.equals(model().trim());
    }

    public LlmConfig.ApiMode apiMode() {
        try {
            return LlmConfig.ApiMode.parse(options.getString(API_MODE, "chat"));
        } catch (IllegalArgumentException e) {
            return LlmConfig.ApiMode.CHAT;
        }
    }

    public String apiKey() {
        return options.getString(API_KEY, "");
    }

    public int maxTurns() {
        return Math.max(1, options.getInt(MAX_TURNS, LoopConfig.DEFAULT_MAX_TURNS));
    }

    public String gccPath() {
        String p = options.getString(GCC_PATH, Compiler.DEFAULT_GCC);
        return p == null || p.isBlank() ? Compiler.DEFAULT_GCC : p.trim();
    }

    public String gccFlags() {
        return options.getString(GCC_FLAGS, "");
    }

    public int llmTimeoutSeconds() {
        return Math.max(1, options.getInt(LLM_TIMEOUT, (int) LlmConfig.DEFAULT_TIMEOUT.toSeconds()));
    }

    public int compileTimeoutSeconds() {
        return Math.max(1, options.getInt(COMPILE_TIMEOUT, (int) Compiler.DEFAULT_TIMEOUT.toSeconds()));
    }

    public int runTimeoutSeconds() {
        return Math.max(1, options.getInt(RUN_TIMEOUT, (int) TestRunner.DEFAULT_RUN_TIMEOUT.toSeconds()));
    }

    public int decompileTimeoutSeconds() {
        return Math.max(1, options.getInt(DECOMPILE_TIMEOUT, DEFAULT_DECOMPILE_TIMEOUT));
    }

    public boolean writeComment() {
        return options.getBoolean(WRITE_COMMENT, false);
    }

    public String promptsDir() {
        return options.getString(PROMPTS_DIR, "").trim();
    }

    // --- setters (used by the settings dialog) --------------------------------------------------

    public void setEndpoint(String v) {
        options.setString(ENDPOINT, v.trim());
    }

    public void setModel(String v) {
        options.setString(MODEL, v.trim());
    }

    public void setTestModel(String v) {
        options.setString(TEST_MODEL, v.trim());
    }

    public void setApiMode(LlmConfig.ApiMode mode) {
        options.setString(API_MODE, mode.name().toLowerCase());
    }

    public void setApiKey(String v) {
        options.setString(API_KEY, v.trim());
    }

    public void setMaxTurns(int v) {
        options.setInt(MAX_TURNS, Math.max(1, v));
    }

    public void setGccPath(String v) {
        options.setString(GCC_PATH, v.trim());
    }

    public void setGccFlags(String v) {
        options.setString(GCC_FLAGS, v.trim());
    }

    public void setLlmTimeoutSeconds(int v) {
        options.setInt(LLM_TIMEOUT, Math.max(1, v));
    }

    public void setCompileTimeoutSeconds(int v) {
        options.setInt(COMPILE_TIMEOUT, Math.max(1, v));
    }

    public void setRunTimeoutSeconds(int v) {
        options.setInt(RUN_TIMEOUT, Math.max(1, v));
    }

    public void setDecompileTimeoutSeconds(int v) {
        options.setInt(DECOMPILE_TIMEOUT, Math.max(1, v));
    }

    public void setWriteComment(boolean v) {
        options.setBoolean(WRITE_COMMENT, v);
    }

    public void setPromptsDir(String v) {
        options.setString(PROMPTS_DIR, v.trim());
    }

    // --- core object factories -----------------------------------------------------------------

    public LlmConfig llmConfig() {
        return new LlmConfig(endpoint(), model(), apiKey(), apiMode(), Duration.ofSeconds(llmTimeoutSeconds()),
                LlmConfig.DEFAULT_TEMPERATURE, LlmConfig.DEFAULT_MAX_TOKENS);
    }

    /** Config for the test-generation model; always uses the chat route since tests need an instruct model. */
    public LlmConfig testLlmConfig() {
        return llmConfig().withModel(testModel()).withApiMode(LlmConfig.ApiMode.CHAT);
    }

    public Compiler compiler() {
        return new Compiler(gccPath(), Duration.ofSeconds(compileTimeoutSeconds()), splitFlags(gccFlags()));
    }

    public LoopConfig loopConfig() {
        return LoopConfig.defaults().withMaxTurns(maxTurns());
    }

    public Duration runTimeout() {
        return Duration.ofSeconds(runTimeoutSeconds());
    }

    public Prompts prompts() throws IOException {
        String dir = promptsDir();
        return dir.isEmpty() ? Prompts.defaults() : Prompts.load(Path.of(dir));
    }

    static List<String> splitFlags(String flags) {
        List<String> out = new ArrayList<>();
        if (flags == null) {
            return out;
        }
        for (String f : flags.trim().split("\\s+")) {
            if (!f.isEmpty()) {
                out.add(f);
            }
        }
        return out;
    }
}
