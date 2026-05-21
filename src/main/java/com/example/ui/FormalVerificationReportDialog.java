package com.example.ui;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.BorderFactory;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;

public final class FormalVerificationReportDialog {

    private FormalVerificationReportDialog() {}

    public static void show(
            String projectName, String analyzedFileName, long elapsedMs, VerificationReportData reportData) {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("[B2ACSL] Verified project: " + projectName);
            System.out.println("[B2ACSL] Analyzed file: " + analyzedFileName);
            System.out.println("[B2ACSL] Execution time: " + formatElapsedSeconds(elapsedMs));
            System.out.println(
                    "[B2ACSL] Summary -> total="
                            + reportData.totalGoals()
                            + ", proved="
                            + reportData.provedGoals()
                            + ", failures="
                            + reportData.failures()
                            + ", timeouts="
                            + reportData.timeouts());
            System.out.println(reportData.detailsAsText());
            System.out.println(reportData.fullOutputAsText());
            return;
        }

        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        root.add(buildHeader(projectName, analyzedFileName, elapsedMs), BorderLayout.NORTH);
        root.add(buildSummary(reportData), BorderLayout.CENTER);
        root.add(buildDetails(reportData), BorderLayout.SOUTH);

        Object[] options = {"Close", "Save Full Output (.txt)"};
        while (true) {
            int choice =
                    JOptionPane.showOptionDialog(
                            null,
                            root,
                            "Verification Report",
                            JOptionPane.DEFAULT_OPTION,
                            JOptionPane.INFORMATION_MESSAGE,
                            null,
                            options,
                            options[0]);
            if (choice != 1) {
                break;
            }
            saveFullOutput(projectName, reportData.fullOutputAsText());
        }
    }

    private static JPanel buildHeader(String projectName, String analyzedFileName, long elapsedMs) {
        JPanel header = new JPanel(new GridLayout(0, 1, 4, 4));
        JLabel title = new JLabel("Verification Report");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));

        header.add(title);
        header.add(new JLabel("Verified project: " + projectName));
        header.add(new JLabel("Analyzed file: " + analyzedFileName));
        header.add(new JLabel("Execution time: " + formatElapsedSeconds(elapsedMs)));
        return header;
    }

    private static JPanel buildSummary(VerificationReportData reportData) {
        JPanel summary = new JPanel(new GridLayout(1, 4, 8, 8));
        summary.setBorder(BorderFactory.createTitledBorder("Verification Summary"));

        summary.add(buildCard("Total Goals", Integer.toString(reportData.totalGoals())));
        summary.add(buildCard("Proved", Integer.toString(reportData.provedGoals())));
        summary.add(buildCard("Failures", Integer.toString(reportData.failures())));
        summary.add(buildCard("Timeouts", Integer.toString(reportData.timeouts())));
        return summary;
    }

    private static JPanel buildCard(String label, String value) {
        JPanel card = new JPanel(new BorderLayout());
        card.setBorder(BorderFactory.createEtchedBorder());

        JLabel top = new JLabel(label, SwingConstants.CENTER);
        JLabel bottom = new JLabel(value, SwingConstants.CENTER);
        bottom.setFont(bottom.getFont().deriveFont(Font.BOLD, 18f));

        card.add(top, BorderLayout.NORTH);
        card.add(bottom, BorderLayout.CENTER);
        return card;
    }

    private static JPanel buildDetails(VerificationReportData reportData) {
        JPanel detailsPanel = new JPanel(new BorderLayout());
        detailsPanel.setBorder(BorderFactory.createTitledBorder("Output Details"));

        JTextArea detailsArea = new JTextArea(reportData.detailsAsText(), 14, 96);
        detailsArea.setEditable(false);
        detailsArea.setLineWrap(true);
        detailsArea.setWrapStyleWord(true);

        detailsPanel.add(new JScrollPane(detailsArea), BorderLayout.CENTER);
        return detailsPanel;
    }

    private static void saveFullOutput(String projectName, String fullOutput) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save full verification output");
        chooser.setSelectedFile(Path.of(defaultFilename(projectName)).toFile());

        int result = chooser.showSaveDialog(null);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path outputPath = chooser.getSelectedFile().toPath();
        try {
            Files.writeString(outputPath, fullOutput, StandardCharsets.UTF_8);
            JOptionPane.showMessageDialog(
                    null,
                    "Saved full output to:\n" + outputPath,
                    "Export complete",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(
                    null,
                    "Could not save file:\n" + e.getMessage(),
                    "Export failed",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String defaultFilename(String projectName) {
        String base = projectName == null || projectName.isBlank() ? "project" : projectName.trim();
        String safe = base.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe + "_verification_output.txt";
    }

    private static String formatElapsedSeconds(long elapsedMs) {
        return String.format("%.2fs", elapsedMs / 1000.0);
    }
}
