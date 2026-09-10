package poneglyph.ghidra;

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.decompiler.component.DecompilerUtils;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.util.Msg;
import ghidra.util.Swing;
import ghidra.util.task.TaskMonitor;
import poneglyph.core.CancelToken;
import poneglyph.core.CancelledException;
import poneglyph.core.compile.Compiler;
import poneglyph.core.compile.CompilerNotFoundException;
import poneglyph.core.llm.HttpLlmClient;
import poneglyph.core.llm.LlmClient;
import poneglyph.core.llm.LlmException;
import poneglyph.core.loop.ProgressListener;
import poneglyph.core.loop.RefinementLoop;
import poneglyph.core.loop.RefinementResult;
import poneglyph.core.loop.Turn;
import poneglyph.core.prompt.Prompts;
import poneglyph.core.verify.TestRunner;

import java.io.IOException;

/**
 * One background run: fetch Ghidra's pseudo-code for the function, run the refinement loop, and
 * report back to the panel. Never touches Swing directly except through {@link Swing#runLater}.
 */
final class PoneglyphJob implements Runnable {

    static final String COMMENT_BEGIN = "--- Poneglyph ---";
    static final String COMMENT_END = "--- end Poneglyph ---";

    private final PoneglyphPlugin plugin;
    private final Program program;
    private final Function function;
    private final PoneglyphSettings settings;
    private final PoneglyphProvider provider;

    PoneglyphJob(PoneglyphPlugin plugin, Program program, Function function, PoneglyphSettings settings,
                   PoneglyphProvider provider) {
        this.plugin = plugin;
        this.program = program;
        this.function = function;
        this.settings = settings;
        this.provider = provider;
    }

    @Override
    public void run() {
        TaskMonitor monitor = provider.monitor();
        CancelToken cancel = monitor::isCancelled;
        String modelDescription = settings.model() + " @ " + settings.endpoint();
        try {
            monitor.setIndeterminate(true);
            monitor.setMessage("Decompiling " + function.getName() + " with Ghidra");
            String pseudo = decompile(monitor);
            Swing.runLater(() -> provider.showOriginal(pseudo));
            cancel.throwIfCancelled();

            Prompts prompts = loadPrompts();
            Compiler compiler = settings.compiler();
            LlmClient decompiler = new HttpLlmClient(settings.llmConfig());
            LlmClient tester = settings.hasSeparateTestModel() ? new HttpLlmClient(settings.testLlmConfig()) : decompiler;
            modelDescription = decompiler.describe();
            TestRunner testRunner = new TestRunner(tester, compiler, prompts, settings.runTimeout());
            RefinementLoop loop = new RefinementLoop(decompiler, testRunner, compiler, prompts, settings.loopConfig());

            ProgressListener listener = new ProgressListener() {
                @Override
                public void onMessage(String message) {
                    monitor.setMessage(message);
                }

                @Override
                public void onTurnStart(int turn, int maxTurns) {
                    monitor.setMessage("turn " + turn + "/" + maxTurns);
                }

                @Override
                public void onTurnEnd(Turn turn) {
                    Swing.runLater(() -> provider.addTurn(turn));
                }
            };

            RefinementResult result = loop.run(pseudo, function.getName(), listener, cancel);
            if (result.cancelled()) {
                Swing.runLater(() -> provider.finishCancelled(result));
                return;
            }
            if (settings.writeComment() && result.hasCode()) {
                writeComment(program, function, result, modelDescription);
            }
            String description = modelDescription;
            Swing.runLater(() -> provider.finish(result, description));
        } catch (CancelledException e) {
            Swing.runLater(() -> provider.finishCancelled(null));
        } catch (CompilerNotFoundException e) {
            Swing.runLater(() -> provider.finishWithError(e.getMessage()
                    + "\n\nOpen Tools > Poneglyph Settings... and set the 'gcc path' field to a working C compiler."));
        } catch (LlmException e) {
            Swing.runLater(() -> provider.finishWithError("Model request failed: " + e.getMessage()
                    + "\n\nCheck the endpoint URL and model name in Tools > Poneglyph Settings..."));
        } catch (IOException e) {
            Swing.runLater(() -> provider.finishWithError("Could not load prompt templates: " + e.getMessage()));
        } catch (RuntimeException e) {
            Msg.error(this, "Poneglyph run failed", e);
            Swing.runLater(() -> provider.finishWithError("Unexpected error: " + e));
        }
    }

    private Prompts loadPrompts() throws IOException {
        try {
            return settings.prompts();
        } catch (IOException e) {
            throw new IOException(e.getMessage() + " (prompt override directory: " + settings.promptsDir() + ")", e);
        }
    }

    /** Runs Ghidra's decompiler for the function and returns its C text. */
    private String decompile(TaskMonitor monitor) {
        DecompInterface ifc = new DecompInterface();
        try {
            DecompileOptions options = DecompilerUtils.getDecompileOptions(plugin.getTool(), program);
            ifc.setOptions(options);
            ifc.toggleCCode(true);
            ifc.toggleSyntaxTree(false);
            ifc.setSimplificationStyle("decompile");
            if (!ifc.openProgram(program)) {
                throw new IllegalStateException("Ghidra's decompiler failed to start: " + ifc.getLastMessage());
            }
            DecompileResults results = ifc.decompileFunction(function, settings.decompileTimeoutSeconds(), monitor);
            if (results.isCancelled()) {
                throw new CancelledException();
            }
            if (!results.decompileCompleted() || results.getDecompiledFunction() == null) {
                throw new IllegalStateException("Ghidra could not decompile " + function.getName() + ": "
                        + results.getErrorMessage());
            }
            return results.getDecompiledFunction().getC();
        } finally {
            ifc.dispose();
        }
    }

    /** Stores the refined code and its verdict as the function comment, replacing any earlier Poneglyph block. */
    static void writeComment(Program program, Function function, RefinementResult result, String modelDescription) {
        String block = COMMENT_BEGIN + "\n"
                + result.status() + ": " + result.stage().description()
                + " (turn " + result.bestTurnNumber() + " of " + result.turns().size() + ", model " + modelDescription + ")\n"
                + result.bestCode().strip() + "\n"
                + COMMENT_END;
        String merged = mergeComment(function.getComment(), block);
        int tx = program.startTransaction("Poneglyph: function comment");
        boolean ok = false;
        try {
            function.setComment(merged);
            ok = true;
        } finally {
            program.endTransaction(tx, ok);
        }
    }

    /** Package-private for tests: replaces an existing Poneglyph block or appends a new one. */
    static String mergeComment(String existing, String block) {
        if (existing == null || existing.isBlank()) {
            return block;
        }
        int begin = existing.indexOf(COMMENT_BEGIN);
        int end = existing.indexOf(COMMENT_END);
        if (begin >= 0 && end > begin) {
            String before = existing.substring(0, begin).stripTrailing();
            String after = existing.substring(end + COMMENT_END.length()).stripLeading();
            StringBuilder sb = new StringBuilder();
            if (!before.isEmpty()) {
                sb.append(before).append("\n\n");
            }
            sb.append(block);
            if (!after.isEmpty()) {
                sb.append("\n\n").append(after);
            }
            return sb.toString();
        }
        return existing.stripTrailing() + "\n\n" + block;
    }
}
