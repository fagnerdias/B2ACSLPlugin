package com.example.gui.ui.report;

import com.example.gui.comparison.CsvExporter;
import com.example.gui.comparison.ResultComparator;
import com.example.gui.comparison.SpecificationDiffer.SpecDiff;
import com.example.gui.model.FunctionComparison;
import com.example.gui.model.ProjectInfo;
import com.example.gui.model.VerificationRun;
import com.example.gui.ui.LogViewerDialog;
import com.example.gui.ui.Palette;
import com.example.gui.ui.Ui;
import java.awt.BorderLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.WindowConstants;

/** Comparison report (mockups 5 and 6): Results and Specifications tabs. */
public final class ComparisonReportWindow extends JFrame {

    private final Runnable onClose;
    private final JTabbedPane tabs = new JTabbedPane();
    private final ResultsTab results;
    private final SpecificationsTab specifications;

    public ComparisonReportWindow(
            ProjectInfo project,
            VerificationRun plugin,
            VerificationRun atl,
            Map<String, SpecDiff> diffs,
            Path runRoot,
            Runnable onClose) {
        super("Verification Report");
        this.onClose = onClose;
        List<FunctionComparison> rows = ResultComparator.compare(project.functions(), plugin, atl);

        JPanel header = Ui.stack(3,
                Ui.title("Comparison Report", 17f),
                ReportSupport.headerLine("Verified project", project.name()),
                ReportSupport.headerLine("Compared specifications",
                        "Plugin (" + plugin.spec().displayPath() + ")  ·  ATL Rules (" + atl.spec().displayPath() + ")"),
                ReportSupport.headerLine("Execution time",
                        "Plugin " + Ui.seconds(plugin.elapsedMs()) + "  ·  ATL Rules " + Ui.seconds(atl.elapsedMs())));

        results = new ResultsTab(plugin, atl, rows, this::showSpecifications);
        specifications = new SpecificationsTab(plugin, atl, rows, diffs, this::showResults);
        tabs.addTab("Results", results);
        tabs.addTab("Specifications", specifications);

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(Ui.padding(14, 16, 14, 16));
        root.add(header, BorderLayout.NORTH);
        root.add(tabs, BorderLayout.CENTER);
        root.add(Ui.buttonBar(
                Ui.button("Close", e -> close()),
                Ui.button("Save Full Log (.txt)", e -> ReportSupport.saveText(this,
                        ReportSupport.suggested(runRoot, project.name() + "-verification-log.txt"),
                        ReportSupport.fullLog(List.of(plugin, atl)), "log")),
                Ui.button("Export Comparison (.csv)", e -> exportCsv(rows, runRoot, project.name()))),
                BorderLayout.SOUTH);
        setContentPane(root);
        root.setBackground(Palette.WINDOW);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                close();
            }
        });
        Ui.standardSize(this);
    }

    private void showSpecifications(String function) {
        specifications.select(function);
        tabs.setSelectedComponent(specifications);
    }

    private void showResults(String function) {
        results.select(function);
        tabs.setSelectedComponent(results);
    }

    private void exportCsv(List<FunctionComparison> rows, Path runRoot, String project) {
        Path file = LogViewerDialog.chooseSaveFile(this, ReportSupport.suggested(runRoot, project + "-comparison.csv"),
                project + "-comparison.csv");
        if (file == null) {
            return;
        }
        try {
            CsvExporter.write(rows, file);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Could not export the comparison: " + e.getMessage(), "Export",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void close() {
        dispose();
        onClose.run();
    }
}
