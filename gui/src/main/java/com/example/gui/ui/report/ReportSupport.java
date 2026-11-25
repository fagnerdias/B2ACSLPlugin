package com.example.gui.ui.report;

import com.example.gui.model.FunctionResult;
import com.example.gui.model.Issue;
import com.example.gui.model.VerificationRun;
import com.example.gui.ui.LogViewerDialog;
import com.example.gui.ui.Palette;
import com.example.gui.ui.Theme;
import com.example.gui.ui.Ui;
import com.example.gui.ui.components.SummaryCard;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;

/** Pieces shared by the two report windows. */
final class ReportSupport {

    static final String NO_OCCURRENCES = "No relevant occurrences were found in WP output.";

    private ReportSupport() {}

    /** "Key: value" header line. */
    static JLabel headerLine(String key, String value) {
        JLabel l = new JLabel("<html><b>" + Ui.html(key) + ":</b> " + Ui.html(value) + "</html>");
        l.setFont(Theme.font(Font.PLAIN, 12.5f));
        l.setForeground(Palette.TEXT);
        return l;
    }

    /** The five cards: Total Goals, Proved, Failures, Timeouts, Smoke Tests. */
    static JPanel cards(int total, int proved, int failures, int timeouts, String smoke) {
        JPanel p = new JPanel(new GridLayout(1, 5, 8, 0));
        p.setOpaque(false);
        p.add(new SummaryCard("Total Goals", Integer.toString(total)));
        p.add(new SummaryCard("Proved", Integer.toString(proved)));
        p.add(SummaryCard.alert("Failures", failures));
        p.add(SummaryCard.alert("Timeouts", timeouts));
        p.add(new SummaryCard("Smoke Tests", smoke));
        return p;
    }

    static JPanel cards(VerificationRun run) {
        return cards(run.totalGoals(), run.proved(), run.failures(), run.timeouts(), run.smokeDisplay());
    }

    static JPanel cards(FunctionResult r) {
        return cards(r.totalGoals(), r.proved(), r.failures(), r.timeouts(), r.smokeDisplay());
    }

    static JTextArea details(List<String> lines, int rows) {
        JTextArea a = Ui.monoArea(rows, 80);
        a.setText(lines.isEmpty() ? NO_OCCURRENCES : String.join("\n", lines));
        a.setCaretPosition(0);
        return a;
    }

    static List<String> issueLines(VerificationRun run, String prefix) {
        return run.issues().stream().map(Issue::text).map(t -> prefix == null ? t : prefix + " " + t).toList();
    }

    /** All raw WP output of the runs, with a header per specification. */
    static String fullLog(List<VerificationRun> runs) {
        StringBuilder sb = new StringBuilder();
        for (VerificationRun run : runs) {
            sb.append("##### ").append(run.generator().label()).append(" (").append(run.spec().displayPath())
                    .append(") #####\n").append(run.rawLog()).append('\n');
        }
        return sb.toString();
    }

    static void saveText(Component parent, Path suggested, String text, String what) {
        Path file = LogViewerDialog.chooseSaveFile(parent, suggested, suggested == null ? "log.txt" : suggested.getFileName().toString());
        if (file == null) {
            return;
        }
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(parent, "Could not save the " + what + ": " + e.getMessage(), "Save",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    static Path suggested(Path runRoot, String name) {
        return runRoot == null ? Path.of(name).toAbsolutePath() : runRoot.resolve(name);
    }
}
