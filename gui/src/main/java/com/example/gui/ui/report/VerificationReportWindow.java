package com.example.gui.ui.report;

import com.example.gui.model.FunctionResult;
import com.example.gui.model.FunctionStatus;
import com.example.gui.model.ProjectInfo;
import com.example.gui.model.VerificationRun;
import com.example.gui.ui.Palette;
import com.example.gui.ui.Theme;
import com.example.gui.ui.Ui;
import com.example.gui.ui.components.Icons;
import com.example.gui.ui.components.TitledGroup;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.List;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.WindowConstants;

/** Report of a single generator (mockups 5A/5P). */
public final class VerificationReportWindow extends JFrame {

    private final Runnable onClose;

    public VerificationReportWindow(ProjectInfo project, VerificationRun run, Path runRoot, Runnable onClose) {
        super("Verification Report");
        this.onClose = onClose;

        JLabel file = ReportSupport.headerLine("Analyzed file", run.spec().displayPath());
        file.setToolTipText(run.spec().mergedFile().toString());
        JPanel header = Ui.stack(3,
                Ui.title("Verification Report", 17f),
                ReportSupport.headerLine("Verified project", project.name()),
                file,
                ReportSupport.headerLine("Specification", run.generator().label()),
                ReportSupport.headerLine("Execution time", Ui.seconds(run.elapsedMs())));

        JPanel perFunction = Ui.stack(12);
        for (FunctionResult r : run.results()) {
            FunctionStatus status = r.status();
            JLabel title = Ui.colored(r.function() + " (" + run.spec().displayPath() + ")  ·  " + status.label(),
                    status.color(), true);
            title.setIcon(Icons.status(status));
            title.setIconTextGap(6);
            perFunction.add(Ui.stack(5, title, ReportSupport.cards(r)));
        }

        List<String> issues = ReportSupport.issueLines(run, null);
        JScrollPane details = Ui.scroll(ReportSupport.details(issues, 6));

        JPanel body = Ui.stack(12,
                new TitledGroup("Verification Summary", ReportSupport.cards(run)),
                new TitledGroup("Per-function verification summary", perFunction),
                new TitledGroup("Output Details", details));
        body.setBorder(Ui.padding(0, 0, 0, 6));
        JScrollPane scroll = new JScrollPane(body, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(Palette.WINDOW);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(Ui.padding(14, 16, 14, 16));
        root.add(header, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        root.add(Ui.buttonBar(
                Ui.button("Close", e -> close()),
                Ui.button("Save Full Log (.txt)", e -> ReportSupport.saveText(this,
                        ReportSupport.suggested(runRoot, project.name() + "-verification-log.txt"),
                        ReportSupport.fullLog(List.of(run)), "log"))), BorderLayout.SOUTH);
        setContentPane(root);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                close();
            }
        });
        pack();
        Dimension pref = getPreferredSize();
        setSize(Math.max(pref.width + 20, 640), Math.min(pref.height, 760));
        setLocationRelativeTo(null);
        header.getComponent(0).setFont(Theme.font(Font.BOLD, 17f));
    }

    private void close() {
        dispose();
        onClose.run();
    }
}
