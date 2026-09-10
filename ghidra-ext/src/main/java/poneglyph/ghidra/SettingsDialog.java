package poneglyph.ghidra;

import docking.DialogComponentProvider;
import docking.widgets.checkbox.GCheckBox;
import docking.widgets.combobox.GComboBox;
import docking.widgets.label.GLabel;
import docking.widgets.textfield.IntegerTextField;
import ghidra.util.Swing;
import ghidra.util.layout.PairLayout;
import poneglyph.core.CancelToken;
import poneglyph.core.compile.Compiler;
import poneglyph.core.llm.HttpLlmClient;
import poneglyph.core.llm.LlmConfig;
import poneglyph.core.llm.LlmException;
import poneglyph.core.llm.LlmRequest;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.time.Duration;

/** Modal settings dialog backed by {@link PoneglyphSettings}. */
final class SettingsDialog extends DialogComponentProvider {

    private final PoneglyphSettings settings;

    private final JTextField endpoint = new JTextField(32);
    private final JTextField model = new JTextField(32);
    private final JTextField testModel = new JTextField(32);
    private final GComboBox<String> apiMode = new GComboBox<>(new String[] { "chat", "completion" });
    private final JTextField apiKey = new JTextField(32);
    private final IntegerTextField maxTurns = new IntegerTextField(6);
    private final JTextField gccPath = new JTextField(32);
    private final JTextField gccFlags = new JTextField(32);
    private final IntegerTextField llmTimeout = new IntegerTextField(6);
    private final IntegerTextField compileTimeout = new IntegerTextField(6);
    private final IntegerTextField runTimeout = new IntegerTextField(6);
    private final IntegerTextField decompileTimeout = new IntegerTextField(6);
    private final GCheckBox writeComment = new GCheckBox("Store refined C and verdict as the function comment after each run");
    private final JTextField promptsDir = new JTextField(32);

    SettingsDialog(PoneglyphSettings settings) {
        super("Poneglyph Settings", true);
        this.settings = settings;
        addWorkPanel(buildPanel());
        JButton testModelButton = new JButton("Test model");
        testModelButton.addActionListener(e -> testModel());
        addButton(testModelButton);
        JButton testGccButton = new JButton("Test gcc");
        testGccButton.addActionListener(e -> testGcc());
        addButton(testGccButton);
        addOKButton();
        addCancelButton();
        setRememberSize(false);
        load();
    }

    private JPanel buildPanel() {
        JPanel form = new JPanel(new PairLayout(6, 10));
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));

        form.add(new GLabel("Model endpoint URL:"));
        form.add(endpoint);
        endpoint.setToolTipText("OpenAI-compatible base URL. Ollama: http://localhost:11434/v1, vLLM: http://localhost:8000/v1, LM Studio: http://localhost:1234/v1");

        form.add(new GLabel("Model name:"));
        form.add(model);

        form.add(new GLabel("API mode:"));
        form.add(apiMode);
        apiMode.setToolTipText("chat = /chat/completions (instruct models); completion = /completions (LLM4Decompile-ref, AutoDecompiler)");

        form.add(new GLabel("Test-generation model (optional):"));
        form.add(testModel);
        testModel.setToolTipText("Instruct model that writes the unit tests. Leave blank to reuse the main model. Strongly recommended with completion-style decompiler models.");

        form.add(new GLabel("API key (optional):"));
        form.add(apiKey);

        form.add(new GLabel("Max refinement turns:"));
        form.add(maxTurns.getComponent());
        maxTurns.setAllowNegativeValues(false);

        form.add(new GLabel("gcc path:"));
        form.add(gccPath);
        gccPath.setToolTipText("gcc or clang executable. Required for verification.");

        form.add(new GLabel("Extra gcc flags:"));
        form.add(gccFlags);
        gccFlags.setToolTipText("Optional, space separated. Example: -fsanitize=address,undefined");

        form.add(new GLabel("Model timeout (seconds):"));
        form.add(llmTimeout.getComponent());

        form.add(new GLabel("Compile timeout (seconds):"));
        form.add(compileTimeout.getComponent());

        form.add(new GLabel("Test run timeout (seconds):"));
        form.add(runTimeout.getComponent());

        form.add(new GLabel("Ghidra decompile timeout (seconds):"));
        form.add(decompileTimeout.getComponent());

        form.add(new GLabel("Prompt override directory:"));
        form.add(promptsDir);
        promptsDir.setToolTipText("Directory with <key>.txt prompt templates. Blank = built-in. Create one with: java -jar poneglyph-cli.jar --export-prompts DIR");

        form.add(new GLabel(""));
        form.add(writeComment);

        JPanel panel = new JPanel(new java.awt.BorderLayout());
        panel.add(form, java.awt.BorderLayout.CENTER);
        GLabel note = new GLabel("Nothing is sent anywhere except the endpoint above. No telemetry.");
        note.setBorder(BorderFactory.createEmptyBorder(0, 10, 8, 10));
        panel.add(note, java.awt.BorderLayout.SOUTH);
        return panel;
    }

    private void load() {
        endpoint.setText(settings.endpoint());
        model.setText(settings.model());
        testModel.setText(settings.testModel());
        apiMode.setSelectedItem(settings.apiMode().name().toLowerCase());
        apiKey.setText(settings.apiKey());
        maxTurns.setValue(settings.maxTurns());
        gccPath.setText(settings.gccPath());
        gccFlags.setText(settings.gccFlags());
        llmTimeout.setValue(settings.llmTimeoutSeconds());
        compileTimeout.setValue(settings.compileTimeoutSeconds());
        runTimeout.setValue(settings.runTimeoutSeconds());
        decompileTimeout.setValue(settings.decompileTimeoutSeconds());
        writeComment.setSelected(settings.writeComment());
        promptsDir.setText(settings.promptsDir());
    }

    private boolean validateFields() {
        if (endpoint.getText().isBlank()) {
            setStatusText("The model endpoint URL is required.");
            return false;
        }
        if (model.getText().isBlank()) {
            setStatusText("The model name is required.");
            return false;
        }
        if (maxTurns.getIntValue() < 1) {
            setStatusText("Max refinement turns must be at least 1.");
            return false;
        }
        if (gccPath.getText().isBlank()) {
            setStatusText("The gcc path is required (gcc or clang).");
            return false;
        }
        return true;
    }

    @Override
    protected void okCallback() {
        if (!validateFields()) {
            return;
        }
        settings.setEndpoint(endpoint.getText());
        settings.setModel(model.getText());
        settings.setTestModel(testModel.getText());
        settings.setApiMode(LlmConfig.ApiMode.parse((String) apiMode.getSelectedItem()));
        settings.setApiKey(apiKey.getText());
        settings.setMaxTurns(maxTurns.getIntValue());
        settings.setGccPath(gccPath.getText());
        settings.setGccFlags(gccFlags.getText());
        settings.setLlmTimeoutSeconds(Math.max(1, llmTimeout.getIntValue()));
        settings.setCompileTimeoutSeconds(Math.max(1, compileTimeout.getIntValue()));
        settings.setRunTimeoutSeconds(Math.max(1, runTimeout.getIntValue()));
        settings.setDecompileTimeoutSeconds(Math.max(1, decompileTimeout.getIntValue()));
        settings.setWriteComment(writeComment.isSelected());
        settings.setPromptsDir(promptsDir.getText());
        close();
    }

    /** Sends a one-word prompt to the configured endpoint on a background thread. */
    private void testModel() {
        if (endpoint.getText().isBlank() || model.getText().isBlank()) {
            setStatusText("Enter an endpoint URL and model name first.");
            return;
        }
        LlmConfig cfg;
        try {
            cfg = new LlmConfig(endpoint.getText(), model.getText(), apiKey.getText(),
                    LlmConfig.ApiMode.parse((String) apiMode.getSelectedItem()), Duration.ofSeconds(60), 0.0, 16);
        } catch (IllegalArgumentException e) {
            setStatusText(e.getMessage());
            return;
        }
        setStatusText("Contacting " + cfg.baseUrl() + " ...");
        Thread t = new Thread(() -> {
            String message;
            try {
                long start = System.nanoTime();
                String reply = new HttpLlmClient(cfg).complete(
                        LlmRequest.decompile("", "Reply with the single word OK."), CancelToken.NONE);
                long ms = (System.nanoTime() - start) / 1_000_000;
                String shown = reply.strip().replace('\n', ' ');
                message = "Model responded in " + ms + " ms: \"" + (shown.length() > 60 ? shown.substring(0, 60) + "..." : shown) + "\"";
            } catch (LlmException e) {
                message = e.getMessage();
            } catch (RuntimeException e) {
                message = "Request failed: " + e;
            }
            String finalMessage = message;
            Swing.runLater(() -> setStatusText(finalMessage));
        }, "Poneglyph-test-connection");
        t.setDaemon(true);
        t.start();
    }

    private void testGcc() {
        Compiler c = new Compiler(gccPath.getText(), Duration.ofSeconds(15), java.util.List.of());
        setStatusText(c.isAvailable() ? "Compiler OK: " + c.version() : "Compiler not found: " + c.version());
    }
}
