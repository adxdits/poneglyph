package poneglyph.ghidra;

import docking.ActionContext;
import docking.action.DockingAction;
import docking.action.MenuData;
import docking.tool.ToolConstants;
import ghidra.app.context.ProgramLocationActionContext;
import ghidra.app.plugin.PluginCategoryNames;
import ghidra.app.plugin.ProgramPlugin;
import ghidra.app.plugin.core.decompile.DecompilerActionContext;
import ghidra.framework.plugintool.PluginInfo;
import ghidra.framework.plugintool.PluginTool;
import ghidra.framework.plugintool.util.PluginStatus;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.util.Msg;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Adds "AI Decompile &amp; Verify" to the Decompiler (and Listing) popup menu, a dockable results
 * panel and a settings dialog. All model and compiler work runs on a background thread.
 */
//@formatter:off
@PluginInfo(
    status = PluginStatus.STABLE,
    packageName = PoneglyphPluginPackage.NAME,
    category = PluginCategoryNames.ANALYSIS,
    shortDescription = "AI Decompile & Verify",
    description = "Rewrites Ghidra's decompiler output into clean C with a local LLM, then compiles the result "
            + "and runs model-generated tests, feeding errors back for up to N refinement turns. "
            + "Works fully offline against any OpenAI-compatible server (Ollama, vLLM, LM Studio)."
)
//@formatter:on
public class PoneglyphPlugin extends ProgramPlugin {

    public static final String ACTION_NAME = "AI Decompile & Verify";

    private final PoneglyphSettings settings;
    private final PoneglyphProvider provider;
    private final ExecutorService executor;
    private DockingAction runAction;
    private DockingAction settingsAction;
    private volatile Future<?> running;

    public PoneglyphPlugin(PluginTool tool) {
        super(tool);
        settings = new PoneglyphSettings(tool);
        provider = new PoneglyphProvider(this);
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Poneglyph-worker");
            t.setDaemon(true);
            return t;
        });
        createActions();
    }

    private void createActions() {
        runAction = new DockingAction(ACTION_NAME, getName()) {
            @Override
            public void actionPerformed(ActionContext context) {
                Target target = targetOf(context);
                if (target != null) {
                    run(target.program(), target.function());
                }
            }

            @Override
            public boolean isEnabledForContext(ActionContext context) {
                return targetOf(context) != null;
            }

            @Override
            public boolean isAddToPopup(ActionContext context) {
                return context instanceof ProgramLocationActionContext;
            }
        };
        runAction.setPopupMenuData(new MenuData(new String[] { ACTION_NAME }, "Decompile"));
        runAction.setDescription("Rewrite this function as clean C with a local LLM and verify it by compiling and testing.");
        runAction.markHelpUnnecessary();
        tool.addAction(runAction);

        settingsAction = new DockingAction("Poneglyph Settings", getName()) {
            @Override
            public void actionPerformed(ActionContext context) {
                showSettings();
            }
        };
        settingsAction.setMenuBarData(new MenuData(new String[] { ToolConstants.MENU_TOOLS, "Poneglyph Settings..." },
                "Poneglyph"));
        settingsAction.setDescription("Configure the model endpoint, compiler and refinement loop.");
        settingsAction.markHelpUnnecessary();
        tool.addAction(settingsAction);
    }

    /** The function the user right-clicked, or null when the action does not apply. */
    static Target targetOf(ActionContext context) {
        if (context instanceof DecompilerActionContext dc) {
            if (dc.isDecompiling()) {
                return null;
            }
            Function f = dc.getFunction();
            return f == null ? null : new Target(dc.getProgram(), f);
        }
        if (context instanceof ProgramLocationActionContext pc) {
            Program program = pc.getProgram();
            Address address = pc.getAddress();
            if (program == null || address == null) {
                return null;
            }
            Function f = program.getFunctionManager().getFunctionContaining(address);
            return f == null ? null : new Target(program, f);
        }
        return null;
    }

    record Target(Program program, Function function) {
    }

    public PoneglyphSettings settings() {
        return settings;
    }

    public boolean isRunning() {
        Future<?> f = running;
        return f != null && !f.isDone();
    }

    /** Starts a run for {@code function}; shows the results panel immediately. */
    public void run(Program program, Function function) {
        if (isRunning()) {
            Msg.showInfo(this, provider.getComponent(), "Poneglyph",
                    "A run is already in progress. Cancel it from the Poneglyph panel first.");
            tool.showComponentProvider(provider, true);
            return;
        }
        provider.startRun(program, function, settings.model());
        tool.showComponentProvider(provider, true);
        PoneglyphJob job = new PoneglyphJob(this, program, function, settings, provider);
        running = executor.submit(job);
    }

    public void showSettings() {
        tool.showDialog(new SettingsDialog(settings));
    }

    @Override
    protected void dispose() {
        provider.cancelRun();
        executor.shutdownNow();
        if (runAction != null) {
            tool.removeAction(runAction);
        }
        if (settingsAction != null) {
            tool.removeAction(settingsAction);
        }
        provider.removeFromTool();
        super.dispose();
    }
}
