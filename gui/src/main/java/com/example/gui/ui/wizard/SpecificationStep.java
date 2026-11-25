package com.example.gui.ui.wizard;

import com.example.gui.generation.GenerationService;
import com.example.gui.generation.GenerationService.GenerationOutcome;
import com.example.gui.model.GeneratedSpecification;
import com.example.gui.model.GenerationMode;
import com.example.gui.model.Generator;
import com.example.gui.ui.ErrorDialog;
import com.example.gui.ui.ErrorReports;
import com.example.gui.ui.Palette;
import com.example.gui.ui.Theme;
import com.example.gui.ui.Ui;
import com.example.gui.ui.VerticalLayout;
import com.example.gui.ui.components.Callout;
import com.example.gui.ui.components.Icons;
import com.example.gui.ui.components.LinkLabel;
import com.example.gui.ui.components.StatusBadge;
import com.example.gui.ui.components.TitledGroup;
import com.example.gui.verification.Cancellation;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import javax.swing.text.DefaultCaret;

/** Step 2: choose the generator(s), generate, see the log; opens the error dialog on failure. */
final class SpecificationStep extends WizardStep {

    private final Map<GenerationMode, JRadioButton> radios = new EnumMap<>(GenerationMode.class);
    private final Callout compareNote = new Callout(Callout.Kind.INFO, null,
            "The verification runs once for each specification, so it takes about twice as long.", 600);
    private final JButton generate = Ui.button("Generate Specification", e -> startGeneration());
    private final JLabel status = new JLabel(" ");
    private final LinkLabel errorLink = new LinkLabel("View error details ›", this::showError);
    private final JPanel specList = new JPanel(new VerticalLayout(4));
    private final JTextArea log = Ui.monoArea(7, 50);
    private final JButton back;
    private final JButton cancel;
    private final JButton nextButton;
    private SwingWorker<GenerationOutcome, String> worker;
    private Cancellation cancellation;

    SpecificationStep(WizardFrame frame, WizardState state) {
        super(frame, state, 1, "Generate the formal specification");

        ButtonGroup group = new ButtonGroup();
        JPanel modes = Ui.stack(6);
        for (GenerationMode m : GenerationMode.values()) {
            JRadioButton r = new JRadioButton(m.label());
            r.setOpaque(false);
            r.addActionListener(e -> modeChosen(m));
            group.add(r);
            radios.put(m, r);
            JLabel description = Ui.wrap(m.description(), 620, Palette.SECONDARY, Theme.font(Font.PLAIN, 12f));
            description.setBorder(Ui.padding(0, 22, 0, 0));
            modes.add(Ui.stack(0, r, description));
        }
        modes.add(compareNote);
        TitledGroup modeGroup = new TitledGroup("Specification generation", modes);

        status.setFont(Theme.font(Font.BOLD, 12.5f));
        JPanel generateRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        generateRow.setOpaque(false);
        generateRow.add(generate);
        generateRow.add(javax.swing.Box.createHorizontalStrut(12));
        generateRow.add(status);
        generateRow.add(javax.swing.Box.createHorizontalStrut(8));
        generateRow.add(errorLink);

        specList.setOpaque(true);
        specList.setBackground(Palette.WHITE);
        specList.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createLineBorder(Palette.INPUT_BORDER), Ui.padding(6, 8, 6, 8)));

        ((DefaultCaret) log.getCaret()).setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
        log.setFont(Theme.mono(11.5f));
        JScrollPane logScroll = Ui.scroll(log);
        logScroll.setPreferredSize(new Dimension(420, 120));

        logScroll.setMinimumSize(new Dimension(200, 60));
        setContent(Ui.stack(10, modeGroup, generateRow, specList,
                VerticalLayout.fill(Ui.stack(5, Ui.bold("Generation log"), VerticalLayout.fill(logScroll)))));
        back = Ui.button("Back", e -> frame.back());
        cancel = Ui.button("Cancel", e -> frame.requestClose());
        nextButton = Ui.button("Next", e -> next());
        setButtons(back, cancel, nextButton);
    }

    @Override
    void onShow() {
        radios.get(state.mode).setSelected(true);
        refresh();
    }

    boolean isGenerating() {
        return worker != null && !worker.isDone();
    }

    void cancelGeneration() {
        if (cancellation != null) {
            cancellation.cancel();
        }
    }

    /** After "Continue with X Only": select the new single mode without discarding the generation. */
    void showModeKeepingGeneration() {
        radios.get(state.mode).setSelected(true);
        refresh();
    }

    private void modeChosen(GenerationMode m) {
        if (m == state.mode) {
            return;
        }
        state.mode = m;
        state.invalidateGeneration();
        log.setText("");
        refresh();
    }

    private void startGeneration() {
        state.invalidateGeneration();
        log.setText("");
        cancellation = new Cancellation();
        Cancellation token = cancellation;
        GenerationMode mode = state.mode;
        worker = new SwingWorker<>() {
            @Override
            protected GenerationOutcome doInBackground() throws Exception {
                return GenerationService.generate(state.project, mode, this::publish, token);
            }

            @Override
            protected void process(List<String> lines) {
                for (String line : lines) {
                    log.append(line);
                    log.append("\n");
                    for (Generator g : mode.generators()) {
                        if (line.startsWith(g.logPrefix() + " Generating ACSL")) {
                            setRow(g, RowState.RUNNING);
                        }
                    }
                }
            }

            @Override
            protected void done() {
                try {
                    GenerationOutcome outcome = get();
                    if (token.isCancelled()) {
                        return;
                    }
                    state.generation = outcome;
                    if (outcome.complete()) {
                        state.specsToVerify =
                                mode.generators().stream().map(outcome.successes()::get).toList();
                    }
                    refresh();
                    if (!outcome.failures().isEmpty()) {
                        showError();
                    }
                } catch (InterruptedException | ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    log.append("ERROR: " + cause + "\n");
                    refresh();
                }
            }
        };
        refresh();
        worker.execute();
    }

    private void showError() {
        GenerationOutcome outcome = state.generation;
        if (outcome == null || outcome.failures().isEmpty()) {
            return;
        }
        ErrorDialog.show(frame, ErrorReports.generationFailed(state.project, outcome),
                new ErrorDialog.Actions(this::startGeneration, frame::continueWith, log::getText));
    }

    private void next() {
        frame.next();
    }

    private enum RowState {
        NOT_GENERATED,
        RUNNING,
        READY,
        FAILED
    }

    private final Map<Generator, JPanel> rows = new EnumMap<>(Generator.class);

    /** Rebuilds the specification list, the status text and the buttons from the state. */
    private void refresh() {
        boolean running = isGenerating();
        GenerationMode mode = state.mode;
        GenerationOutcome outcome = state.generation;
        compareNote.setVisible(mode.isCompare());
        generate.setText(mode.isCompare() ? "Generate Specifications" : "Generate Specification");
        generate.setEnabled(!running);
        radios.values().forEach(r -> r.setEnabled(!running));
        back.setEnabled(!running);

        specList.removeAll();
        rows.clear();
        List<Generator> shown = state.specsToVerify != null && !mode.isCompare()
                ? state.specsToVerify.stream().map(GeneratedSpecification::generator).toList()
                : mode.generators();
        for (Generator g : shown) {
            RowState rs = RowState.NOT_GENERATED;
            if (!running && outcome != null && outcome.successes().containsKey(g)) {
                rs = RowState.READY;
            } else if (outcome != null && outcome.failures().containsKey(g)) {
                rs = RowState.FAILED;
            }
            JPanel row = new JPanel(new BorderLayout(10, 0));
            row.setOpaque(false);
            rows.put(g, row);
            specList.add(row);
            fillRow(g, row, rs);
        }

        int total = mode.generators().size();
        int ok = outcome == null ? 0 : (int) mode.generators().stream().filter(outcome.successes()::containsKey).count();
        boolean failed = outcome != null && !outcome.failures().isEmpty();
        if (running) {
            status.setText(total > 1 ? "Generating specifications..." : "Generating specification...");
            status.setForeground(Palette.BLUE);
        } else if (state.specsToVerify != null) {
            status.setText(total > 1 ? total + " of " + total + " specifications generated" : "Specification generated");
            status.setForeground(Palette.GREEN);
        } else if (failed) {
            status.setText(total > 1 ? ok + " of " + total + " specifications generated" : "Specification not generated");
            status.setForeground(Palette.RED);
        } else {
            status.setText(" ");
        }
        errorLink.setVisible(!running && failed && state.specsToVerify == null);
        nextButton.setEnabled(!running && state.specsToVerify != null
                && state.specsToVerify.size() == mode.generators().size());
        specList.revalidate();
        specList.repaint();
        revalidate();
        repaint();
    }

    private void setRow(Generator g, RowState rs) {
        JPanel row = rows.get(g);
        if (row != null) {
            fillRow(g, row, rs);
            row.revalidate();
            row.repaint();
        }
    }

    private void fillRow(Generator g, JPanel row, RowState rs) {
        row.removeAll();
        GenerationOutcome outcome = state.generation;
        JLabel name = Ui.bold(g.label());
        name.setIconTextGap(8);
        String file;
        StatusBadge badge = null;
        switch (rs) {
            case READY -> {
                name.setIcon(Icons.check(Palette.GREEN));
                file = outcome.successes().get(g).displayPath();
                badge = new StatusBadge("Ready", StatusBadge.Kind.SUCCESS);
            }
            case FAILED -> {
                name.setIcon(Icons.cross(Palette.RED));
                file = "not generated";
                badge = new StatusBadge("Failed", StatusBadge.Kind.ERROR);
            }
            case RUNNING -> {
                name.setIcon(Icons.play(Palette.BLUE));
                file = "generating...";
                badge = new StatusBadge("Running", StatusBadge.Kind.INFO);
            }
            default -> {
                name.setIcon(Icons.circle(Palette.GRAY));
                file = expectedFile(g);
            }
        }
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);
        name.setPreferredSize(new Dimension(Math.max(name.getPreferredSize().width, 110), name.getPreferredSize().height));
        left.add(name);
        left.add(javax.swing.Box.createHorizontalStrut(10));
        left.add(Ui.file(file));
        row.add(left, BorderLayout.CENTER);
        if (badge != null) {
            row.add(badge, BorderLayout.EAST);
        }
    }

    private String expectedFile(Generator g) {
        if (state.mode.isCompare()) {
            return g.id() + "/merged_code.c";
        }
        return "merged_code.c";
    }

}
