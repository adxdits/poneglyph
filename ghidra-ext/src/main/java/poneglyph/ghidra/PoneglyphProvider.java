package poneglyph.ghidra;

import ghidra.framework.plugintool.ComponentProviderAdapter;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.util.Msg;
import ghidra.util.task.TaskMonitor;
import ghidra.util.task.TaskMonitorComponent;
import poneglyph.core.Stage;
import poneglyph.core.Status;
import poneglyph.core.Text;
import poneglyph.core.loop.RefinementResult;
import poneglyph.core.loop.Turn;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

/**
 * Dockable results panel: status badge, original pseudo-code next to the refined C, a collapsible
 * per-turn log and an embedded progress bar with a cancel button. All methods must be called on the
 * Swing thread.
 */
public class PoneglyphProvider extends ComponentProviderAdapter {

    private static final Color RED = new Color(0xC0392B);
    private static final Color YELLOW = new Color(0xB7950B);
    private static final Color GREEN = new Color(0x1E8449);
    private static final Color NEUTRAL = new Color(0x6C7A89);
    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 12);

    private final PoneglyphPlugin plugin;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JLabel badge = new JLabel("IDLE", SwingConstants.CENTER);
    private final JLabel header = new JLabel("Right-click a function in the Decompiler and choose \"AI Decompile & Verify\".");
    private final JTextArea originalArea = codeArea();
    private final JTextArea refinedArea = codeArea();
    private final JPanel logPanel = new JPanel();
    private final TaskMonitorComponent monitor = new TaskMonitorComponent(true, true);
    private final JButton rerunButton = new JButton("Run again");
    private final JButton copyButton = new JButton("Copy C");
    private final JButton commentButton = new JButton("Write as comment");
    private final JButton settingsButton = new JButton("Settings...");

    private Program program;
    private Function function;
    private RefinementResult lastResult;
    private String lastModel;

    public PoneglyphProvider(PoneglyphPlugin plugin) {
        super(plugin.getTool(), "Poneglyph", plugin.getName());
        this.plugin = plugin;
        setTitle("Poneglyph");
        setWindowGroup("Poneglyph");
        build();
        addToTool();
    }

    @Override
    public JComponent getComponent() {
        return root;
    }

    /** The monitor the background job reports to; its cancel button drives cancellation. */
    public TaskMonitor monitor() {
        return monitor;
    }

    public void cancelRun() {
        monitor.cancel();
    }

    // --- state transitions (Swing thread) ------------------------------------------------------

    public void startRun(Program prog, Function func, String model) {
        this.program = prog;
        this.function = func;
        this.lastResult = null;
        this.lastModel = model;
        setBadge("RUNNING", NEUTRAL);
        header.setText(func.getName() + " @ " + func.getEntryPoint() + "   model: " + model);
        originalArea.setText("");
        refinedArea.setText("");
        logPanel.removeAll();
        logPanel.revalidate();
        logPanel.repaint();
        monitor.clearCanceled();
        monitor.setCancelEnabled(true);
        monitor.setTaskName("Poneglyph");
        monitor.setMessage("starting");
        monitor.setIndeterminate(true);
        monitor.showProgress(true);
        rerunButton.setEnabled(false);
        copyButton.setEnabled(false);
        commentButton.setEnabled(false);
    }

    public void showOriginal(String pseudoCode) {
        originalArea.setText(Text.nullToEmpty(pseudoCode));
        originalArea.setCaretPosition(0);
    }

    public void addTurn(Turn turn) {
        // Show the newest code as it arrives so the user can watch the refinement progress.
        if (turn.code() != null) {
            refinedArea.setText(turn.code());
            refinedArea.setCaretPosition(0);
        }
        logPanel.add(new TurnSection(turn, false));
        logPanel.revalidate();
        logPanel.repaint();
    }

    public void finish(RefinementResult result, String modelDescription) {
        this.lastResult = result;
        this.lastModel = modelDescription;
        Status status = result.status();
        setBadge(status + " - " + result.stage().description(), colorOf(status));
        header.setText(function.getName() + " @ " + function.getEntryPoint() + "   " + result.summary()
                + "   model: " + modelDescription);
        if (result.hasCode()) {
            refinedArea.setText(result.bestCode());
            refinedArea.setCaretPosition(0);
        } else {
            refinedArea.setText("(no C code was produced)");
        }
        rebuildLog(result);
        endProgress("done: " + result.summary());
        copyButton.setEnabled(result.hasCode());
        commentButton.setEnabled(result.hasCode());
    }

    public void finishCancelled(RefinementResult partial) {
        this.lastResult = partial;
        setBadge("CANCELLED", NEUTRAL);
        if (partial != null && partial.hasCode()) {
            refinedArea.setText(partial.bestCode());
            rebuildLog(partial);
            copyButton.setEnabled(true);
            commentButton.setEnabled(true);
        }
        endProgress("cancelled");
    }

    public void finishWithError(String message) {
        setBadge("ERROR", RED);
        refinedArea.setText(message);
        endProgress("failed");
        Msg.showError(this, root, "Poneglyph", message);
    }

    private void endProgress(String message) {
        monitor.setMessage(message);
        monitor.setIndeterminate(false);
        monitor.showProgress(false);
        monitor.setCancelEnabled(false);
        rerunButton.setEnabled(function != null);
    }

    // --- UI construction -----------------------------------------------------------------------

    private void build() {
        badge.setOpaque(true);
        badge.setForeground(Color.WHITE);
        badge.setFont(badge.getFont().deriveFont(Font.BOLD));
        badge.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        setBadge("IDLE", NEUTRAL);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        rerunButton.setEnabled(false);
        copyButton.setEnabled(false);
        commentButton.setEnabled(false);
        rerunButton.addActionListener(e -> {
            if (program != null && function != null) {
                plugin.run(program, function);
            }
        });
        copyButton.addActionListener(e -> copyRefined());
        commentButton.addActionListener(e -> writeComment());
        settingsButton.addActionListener(e -> plugin.showSettings());
        buttons.add(rerunButton);
        buttons.add(copyButton);
        buttons.add(commentButton);
        buttons.add(settingsButton);

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        top.add(badge, BorderLayout.WEST);
        top.add(header, BorderLayout.CENTER);
        top.add(buttons, BorderLayout.EAST);

        JSplitPane code = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                titled("Ghidra pseudo-code", new JScrollPane(originalArea)),
                titled("Refined C", new JScrollPane(refinedArea)));
        code.setResizeWeight(0.5);
        code.setContinuousLayout(true);

        logPanel.setLayout(new BoxLayout(logPanel, BoxLayout.Y_AXIS));
        JPanel logHolder = new JPanel(new BorderLayout());
        logHolder.add(logPanel, BorderLayout.NORTH);
        JScrollPane logScroll = new JScrollPane(logHolder);
        logScroll.getVerticalScrollBar().setUnitIncrement(16);

        JSplitPane main = new JSplitPane(JSplitPane.VERTICAL_SPLIT, code, titled("Refinement log", logScroll));
        main.setResizeWeight(0.7);
        main.setContinuousLayout(true);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        bottom.add(monitor, BorderLayout.CENTER);
        monitor.showProgress(false);
        monitor.setCancelEnabled(false);

        root.add(top, BorderLayout.NORTH);
        root.add(main, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);
        root.setPreferredSize(new Dimension(900, 600));
    }

    private void rebuildLog(RefinementResult result) {
        logPanel.removeAll();
        Turn best = result.best();
        for (Turn t : result.turns()) {
            logPanel.add(new TurnSection(t, t == best));
        }
        if (result.testCode() != null) {
            logPanel.add(new Section("Generated tests", result.testCode(), false));
        }
        if (result.abortReason() != null) {
            logPanel.add(new Section("Stopped early", result.abortReason(), true));
        }
        logPanel.revalidate();
        logPanel.repaint();
    }

    private void copyRefined() {
        if (lastResult != null && lastResult.hasCode()) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(lastResult.bestCode()), null);
            monitor.setMessage("copied refined C to clipboard");
        }
    }

    private void writeComment() {
        if (lastResult == null || !lastResult.hasCode() || program == null || function == null) {
            return;
        }
        try {
            PoneglyphJob.writeComment(program, function, lastResult, lastModel);
            monitor.setMessage("wrote function comment for " + function.getName());
        } catch (RuntimeException e) {
            Msg.showError(this, root, "Poneglyph", "Could not write the function comment: " + e.getMessage());
        }
    }

    private void setBadge(String text, Color color) {
        badge.setText(text);
        badge.setBackground(color);
    }

    private static Color colorOf(Status status) {
        switch (status) {
            case GREEN:
                return GREEN;
            case YELLOW:
                return YELLOW;
            default:
                return RED;
        }
    }

    private static JTextArea codeArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(MONO);
        area.setTabSize(4);
        return area;
    }

    private static JPanel titled(String title, JComponent content) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder(title));
        p.add(content, BorderLayout.CENTER);
        return p;
    }

    /** A collapsible section: a toggle header and a text body. */
    private static class Section extends JPanel {
        Section(String title, String body, boolean expanded) {
            super(new BorderLayout());
            setAlignmentX(LEFT_ALIGNMENT);
            JToggleButton toggle = new JToggleButton((expanded ? "▾ " : "▸ ") + title, expanded);
            toggle.setHorizontalAlignment(SwingConstants.LEFT);
            toggle.setFocusPainted(false);
            JTextArea text = codeArea();
            text.setText(Text.nullToEmpty(body));
            text.setCaretPosition(0);
            text.setLineWrap(true);
            text.setWrapStyleWord(false);
            JScrollPane scroll = new JScrollPane(text);
            scroll.setPreferredSize(new Dimension(100, Math.min(320, 40 + 16 * Math.max(1, countLines(body)))));
            scroll.setVisible(expanded);
            toggle.addActionListener(e -> {
                boolean on = toggle.isSelected();
                scroll.setVisible(on);
                toggle.setText((on ? "▾ " : "▸ ") + title);
                revalidate();
                repaint();
            });
            add(toggle, BorderLayout.NORTH);
            add(scroll, BorderLayout.CENTER);
            add(Box.createVerticalStrut(2), BorderLayout.SOUTH);
        }

        private static int countLines(String s) {
            if (s == null || s.isEmpty()) {
                return 1;
            }
            int n = 1;
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) == '\n') {
                    n++;
                }
            }
            return n;
        }
    }

    /** One refinement turn rendered as a collapsible section. */
    private static final class TurnSection extends Section {
        TurnSection(Turn turn, boolean expanded) {
            super(titleOf(turn), bodyOf(turn), expanded);
        }

        private static String titleOf(Turn turn) {
            return "Turn " + turn.number() + ": " + turn.stage() + " [" + turn.status() + "]  "
                    + Text.seconds(turn.durationMs()) + "  " + turn.stage().description();
        }

        private static String bodyOf(Turn turn) {
            StringBuilder sb = new StringBuilder();
            if (turn.detail() != null && !turn.detail().isBlank()) {
                sb.append(turn.detail().strip()).append("\n\n");
            }
            if (turn.tests() != null && turn.stage() != Stage.OK) {
                String out = turn.tests().combinedOutput();
                if (!out.isBlank()) {
                    sb.append("--- test output ---\n").append(out.strip()).append("\n\n");
                }
            }
            if (turn.feedback() != null) {
                sb.append("--- feedback sent to the model ---\n").append(turn.feedback().strip()).append("\n\n");
            }
            sb.append("--- prompt ---\n").append(Text.nullToEmpty(turn.prompt()).strip()).append("\n\n");
            sb.append("--- raw model response ---\n").append(Text.nullToEmpty(turn.rawResponse()).strip());
            return sb.toString();
        }
    }
}
