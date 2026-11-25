package com.example.gui.ui;

import com.example.gui.comparison.SpecificationDiffer;
import com.example.gui.comparison.SpecificationDiffer.SpecDiff;
import com.example.gui.model.FunctionResult;
import com.example.gui.model.FunctionStatus;
import com.example.gui.model.GeneratedSpecification;
import com.example.gui.model.GenerationMode;
import com.example.gui.model.Generator;
import com.example.gui.model.ProjectInfo;
import com.example.gui.model.VerificationRun;
import com.example.gui.model.WpConfig;
import com.example.gui.parsing.ContractExtractor;
import com.example.gui.ui.components.Icons;
import com.example.gui.ui.components.OperationRow;
import com.example.gui.ui.components.StatusBadge;
import com.example.gui.ui.components.TitledGroup;
import com.example.gui.ui.report.ComparisonReportWindow;
import com.example.gui.ui.report.VerificationReportWindow;
import com.example.gui.verification.Cancellation;
import com.example.gui.verification.VerificationService;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;

/** Verification progress (mockup 4): one row per function and specification, live log, cancel. */
public final class ProgressWindow extends JFrame {

    private sealed interface Event permits Started, Line, Finished {}

    private record Started(Generator generator, String function) implements Event {}

    private record Line(String text) implements Event {}

    private record Finished(FunctionResult result) implements Event {}

    /** What the report needs: the runs and, in compare mode, the contract diff of each function. */
    private record Result(List<VerificationRun> runs, Map<String, SpecDiff> diffs) {}

    private final ProjectInfo project;
    private final GenerationMode mode;
    private final List<GeneratedSpecification> specs;
    private final WpConfig config;
    private final Path runRoot;
    private final Runnable onReturn;
    private final Map<Generator, Map<String, OperationRow>> rows = new EnumMap<>(Generator.class);
    private final Map<Generator, StatusBadge> groupBadges = new EnumMap<>(Generator.class);
    private final Map<Generator, Integer> doneBy = new EnumMap<>(Generator.class);
    private final Map<Generator, Boolean> allProvedBy = new EnumMap<>(Generator.class);
    private final JLabel counter = Ui.bold(" ");
    private final JProgressBar bar = new JProgressBar(0, 100);
    private final StringBuilder rawLog = new StringBuilder();
    private final Cancellation cancellation = new Cancellation();
    private final int total;
    private LogViewerDialog logViewer;
    private SwingWorker<Result, Event> worker;
    private int done;
    private boolean closing;

    public ProgressWindow(
            ProjectInfo project,
            GenerationMode mode,
            List<GeneratedSpecification> specs,
            WpConfig config,
            Path runRoot,
            Runnable onReturn) {
        super("Verification Progress");
        this.project = project;
        this.mode = mode;
        this.specs = List.copyOf(specs);
        this.config = config;
        this.runRoot = runRoot;
        this.onReturn = onReturn;
        this.total = specs.size() * project.functions().size();

        JPanel content = Ui.stack(10);
        content.add(Ui.title("Verifying “" + project.name() + "” · " + mode.shortLabel(), 16f));
        content.add(counter);
        bar.setStringPainted(true);
        bar.setPreferredSize(new Dimension(440, 24));
        content.add(bar);
        for (GeneratedSpecification spec : this.specs) {
            Generator g = spec.generator();
            JPanel list = Ui.stack(2);
            Map<String, OperationRow> byFn = new LinkedHashMap<>();
            for (String fn : project.functions()) {
                OperationRow row = new OperationRow(fn + " (" + spec.displayPath() + ")");
                byFn.put(fn, row);
                list.add(row);
            }
            rows.put(g, byFn);
            doneBy.put(g, 0);
            allProvedBy.put(g, true);
            if (mode.isCompare()) {
                StatusBadge badge = new StatusBadge("0 / " + project.functions().size() + " done", StatusBadge.Kind.INFO);
                groupBadges.put(g, badge);
                content.add(new TitledGroup(g.label() + " specification", list).withHeader(badge));
            } else {
                content.add(new TitledGroup("Operations", list));
            }
        }
        if (mode.isCompare()) {
            JLabel note = Ui.secondary("The results will be compared automatically when both verifications finish.");
            note.setIcon(Icons.info());
            note.setIconTextGap(8);
            content.add(note);
        }
        updateCounter();

        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBorder(Ui.padding(14, 16, 14, 16));
        root.add(content, BorderLayout.CENTER);
        root.add(Ui.buttonBar(Ui.button("Close", e -> requestClose()), Ui.button("View Logs", e -> viewLogs())),
                BorderLayout.SOUTH);
        setContentPane(root);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                requestClose();
            }
        });
        pack();
        setMinimumSize(new Dimension(Math.max(getWidth(), 480), getHeight()));
        setLocationRelativeTo(null);
    }

    public void start() {
        setVisible(true);
        worker = new SwingWorker<>() {
            @Override
            protected Result doInBackground() throws Exception {
                List<VerificationRun> runs = VerificationService.run(
                        project.name(), specs, project.functions(), config, new VerificationService.Listener() {
                            @Override
                            public void functionStarted(Generator generator, String function) {
                                publish(new Started(generator, function));
                            }

                            @Override
                            public void logLine(Generator generator, String function, String line) {
                                publish(new Line(line));
                            }

                            @Override
                            public void functionFinished(FunctionResult result) {
                                publish(new Finished(result));
                            }
                        }, cancellation);
                Map<String, SpecDiff> diffs = new LinkedHashMap<>();
                if (runs.size() == 2) {
                    for (String fn : project.functions()) {
                        diffs.put(fn, SpecificationDiffer.diff(
                                fn,
                                ContractExtractor.extract(runs.get(0).spec().mergedFile(), fn),
                                ContractExtractor.extract(runs.get(1).spec().mergedFile(), fn)));
                    }
                }
                writeLog(runs);
                return new Result(runs, diffs);
            }

            @Override
            protected void process(List<Event> events) {
                for (Event e : events) {
                    handle(e);
                }
            }

            @Override
            protected void done() {
                finished(this);
            }
        };
        worker.execute();
    }

    private void handle(Event e) {
        switch (e) {
            case Started s -> {
                rows.get(s.generator()).get(s.function()).setStatus(FunctionStatus.RUNNING, null);
                StatusBadge badge = groupBadges.get(s.generator());
                if (badge != null) {
                    badge.setKind(StatusBadge.Kind.INFO);
                }
            }
            case Line l -> {
                rawLog.append(l.text()).append('\n');
                if (logViewer != null && logViewer.isDisplayable()) {
                    logViewer.append(l.text());
                }
            }
            case Finished f -> {
                FunctionResult r = f.result();
                FunctionStatus s = r.status();
                rows.get(r.generator()).get(r.function())
                        .setStatus(s, s == FunctionStatus.PROVED ? null : r.proved() + " / " + r.totalGoals());
                done++;
                doneBy.merge(r.generator(), 1, Integer::sum);
                if (s != FunctionStatus.PROVED) {
                    allProvedBy.put(r.generator(), false);
                }
                StatusBadge badge = groupBadges.get(r.generator());
                if (badge != null) {
                    int n = project.functions().size();
                    int d = doneBy.get(r.generator());
                    boolean complete = d == n;
                    badge.set(d + " / " + n + " done",
                            !complete ? StatusBadge.Kind.INFO
                                    : allProvedBy.get(r.generator()) ? StatusBadge.Kind.SUCCESS : StatusBadge.Kind.WARNING);
                }
                updateCounter();
            }
        }
    }

    private void updateCounter() {
        counter.setText("Verified " + done + " / " + total + " operation(s)");
        counter.setFont(Theme.font(Font.BOLD, 12.5f));
        int pct = total == 0 ? 100 : Math.round(100f * done / total);
        bar.setValue(pct);
        bar.setString(pct + "%");
    }

    private void finished(SwingWorker<Result, Event> w) {
        if (closing || cancellation.isCancelled()) {
            return;
        }
        try {
            Result result = w.get();
            closeLogViewer();
            dispose();
            if (mode.isCompare() && result.runs().size() == 2) {
                new ComparisonReportWindow(project, result.runs().get(0), result.runs().get(1), result.diffs(), runRoot,
                        onReturn).setVisible(true);
            } else {
                new VerificationReportWindow(project, result.runs().get(0), runRoot, onReturn).setVisible(true);
            }
        } catch (CancellationException | InterruptedException e) {
            dispose();
            onReturn.run();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof CancellationException) {
                dispose();
                onReturn.run();
                return;
            }
            boolean missingTool = cause instanceof VerificationService.ToolMissingException;
            ErrorDialog.show(this,
                    missingTool
                            ? ErrorReports.framaCNotFound(cause.getMessage())
                            : ErrorReports.verificationFailed("The verification failed",
                                    "Frama-C WP could not complete the verification of " + project.name() + ".",
                                    String.valueOf(cause) + "\n\n" + tail(rawLog.toString()), false),
                    new ErrorDialog.Actions(null, null, rawLog::toString));
            closeLogViewer();
            dispose();
            onReturn.run();
        }
    }

    private void requestClose() {
        if (worker != null && !worker.isDone()) {
            int answer = JOptionPane.showConfirmDialog(this,
                    "The verification is still running. Stop it and close this window?", "Close",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) {
                return;
            }
            closing = true;
            cancellation.cancel();
            worker.cancel(false);
        }
        closeLogViewer();
        dispose();
        onReturn.run();
    }

    private void viewLogs() {
        if (logViewer == null || !logViewer.isDisplayable()) {
            logViewer = LogViewerDialog.open(this, "Frama-C output", rawLog.toString(),
                    runRoot == null ? null : runRoot.resolve("verification.log"));
        } else {
            logViewer.toFront();
        }
    }

    private void closeLogViewer() {
        if (logViewer != null) {
            logViewer.dispose();
        }
    }

    private void writeLog(List<VerificationRun> runs) {
        if (runRoot == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (VerificationRun run : runs) {
            sb.append("##### ").append(run.generator().label()).append(" (").append(run.spec().displayPath())
                    .append(") #####\n").append(run.rawLog()).append('\n');
        }
        try {
            Files.writeString(runRoot.resolve("verification.log"), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // the report can still save the log
        }
    }

    private static String tail(String text) {
        List<String> lines = text.lines().toList();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
    }
}
