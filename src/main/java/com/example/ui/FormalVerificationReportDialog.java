package com.example.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.HeadlessException;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;

public final class FormalVerificationReportDialog {

    private static final Color STATUS_OK = new Color(28, 130, 48);
    private static final Color STATUS_FAIL = new Color(180, 36, 36);
    private static final Color STATUS_TIMEOUT = new Color(176, 128, 18);

    private FormalVerificationReportDialog() {}

    public static void show(
            String projectName, String analyzedFileName, long elapsedMs, VerificationReportData reportData) {
        if (!isUiAvailable()) {
            printHeadlessReport(projectName, analyzedFileName, elapsedMs, reportData);
            return;
        }

        try {
            runReportFrame(projectName, analyzedFileName, elapsedMs, reportData);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError | HeadlessException e) {
            System.err.println(
                    "[B2ACSL] GUI is unavailable in this native runtime; printing verification report in console.");
            printHeadlessReport(projectName, analyzedFileName, elapsedMs, reportData);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            printHeadlessReport(projectName, analyzedFileName, elapsedMs, reportData);
        }
    }

    /**
     * Constrói e exibe o relatório numa janela real (JFrame), com botões próprios de
     * Close/Save no lugar dos botões do JOptionPane — assim a janela ganha minimizar/maximizar
     * como qualquer outra janela do SO. Bloqueia a thread chamadora (via latch) até o usuário
     * fechar a janela, pois o processo termina (System.exit) logo após este método retornar.
     */
    private static void runReportFrame(
            String projectName, String analyzedFileName, long elapsedMs, VerificationReportData reportData)
            throws InterruptedException {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.add(buildSummary(reportData));
        JPanel perFunction = buildPerFunctionSummary(reportData);
        if (perFunction != null) {
            center.add(perFunction);
        }

        root.add(buildHeader(projectName, analyzedFileName, elapsedMs), BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        root.add(buildDetails(reportData), BorderLayout.SOUTH);

        JScrollPane scrollPane = new JScrollPane(root);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(16);

        JButton saveButton = new JButton("Save Full Log (.txt)");
        JButton closeButton = new JButton("Close");
        JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));        
        buttonBar.add(closeButton);
        buttonBar.add(saveButton);

        JPanel contentPane = new JPanel(new BorderLayout());
        contentPane.add(scrollPane, BorderLayout.CENTER);
        contentPane.add(buttonBar, BorderLayout.SOUTH);

        JFrame frame = new JFrame("Verification Report");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        CountDownLatch latch = new CountDownLatch(1);
        Runnable finish =
                () -> {
                    frame.dispose();
                    latch.countDown();
                };
        closeButton.addActionListener(e -> finish.run());
        frame.addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        finish.run();
                    }
                });
        saveButton.addActionListener(e -> saveFullOutput(projectName, reportData.fullOutputAsText()));

        frame.setContentPane(contentPane);
        frame.setMinimumSize(new Dimension(900, 600));
        frame.setSize(new Dimension(1100, 760));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        latch.await();
    }

    private static boolean isUiAvailable() {
        try {
            return !GraphicsEnvironment.isHeadless();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void printHeadlessReport(
            String projectName, String analyzedFileName, long elapsedMs, VerificationReportData reportData) {
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
                        + reportData.timeouts()
                        + ", smoke tests="
                        + reportData.smokeTestsDisplay());
        System.out.println(reportData.detailsAsText());
        System.out.println(reportData.fullOutputAsText());
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
        JPanel summary = new JPanel(new GridLayout(1, 5, 4, 4));
        TitledBorder titled = BorderFactory.createTitledBorder("Verification Summary");
        titled.setTitleFont(titled.getTitleFont().deriveFont(Font.PLAIN, 11f));
        summary.setBorder(titled);

        summary.add(buildCard("Total Goals", Integer.toString(reportData.totalGoals())));
        summary.add(buildCard("Proved", Integer.toString(reportData.provedGoals())));
        summary.add(buildCard("Failures", Integer.toString(reportData.failures())));
        summary.add(buildCard("Timeouts", Integer.toString(reportData.timeouts())));
        summary.add(buildCard("Smoke Tests", reportData.smokeTestsDisplay()));
        return summary;
    }

    private static JPanel buildCard(String label, String value) {
        return buildCard(label, value, null, null);
    }

    private static JPanel buildCard(
            String label, String value, Color valueColor, Color backgroundColor) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createEtchedBorder(),
                        new EmptyBorder(4, 2, 4, 2)));
        if (backgroundColor != null) {
            card.setOpaque(true);
            card.setBackground(backgroundColor);
        }

        JLabel top = new JLabel(label, SwingConstants.CENTER);
        top.setAlignmentX(JLabel.CENTER_ALIGNMENT);
        top.setFont(top.getFont().deriveFont(Font.PLAIN, 10f));

        JLabel bottom = new JLabel(value, SwingConstants.CENTER);
        bottom.setAlignmentX(JLabel.CENTER_ALIGNMENT);
        bottom.setFont(bottom.getFont().deriveFont(Font.BOLD, 18f));
        if (valueColor != null) {
            bottom.setForeground(valueColor);
        }

        card.add(top);
        card.add(bottom);
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

    private static JPanel buildPerFunctionSummary(VerificationReportData reportData) {
        var functionSummaries = reportData.functionSummaries();
        if (functionSummaries.size() <= 1) {
            return null;
        }
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(BorderFactory.createTitledBorder("Per-function verification summary"));

        JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));

        for (VerificationReportData.FunctionSummary functionSummary : functionSummaries) {
            FunctionStatus status = functionStatus(functionSummary);
            JPanel row = new JPanel(new GridLayout(1, 5, 4, 4));
            TitledBorder rowBorder =
                    BorderFactory.createTitledBorder(
                            status.icon() + " " + functionSummary.functionName());
            rowBorder.setTitleColor(status.color());
            row.setBorder(rowBorder);
            row.add(buildCard("Total Goals", Integer.toString(functionSummary.totalGoals())));
            row.add(buildCard("Proved", Integer.toString(functionSummary.provedGoals())));
            row.add(buildCard("Failures", Integer.toString(functionSummary.failures())));
            row.add(buildCard("Timeouts", Integer.toString(functionSummary.timeouts())));
            row.add(buildCard("Smoke Tests", functionSummary.smokeTestsDisplay()));
            rows.add(row);
        }

        wrapper.add(rows, BorderLayout.CENTER);
        return wrapper;
    }

    private static FunctionStatus functionStatus(VerificationReportData.FunctionSummary summary) {
        if (summary.failures() > 0) {
            return new FunctionStatus("✗", STATUS_FAIL);
        }
        if (summary.timeouts() > 0) {
            return new FunctionStatus("?", STATUS_TIMEOUT);
        }
        return new FunctionStatus("✓", STATUS_OK);
    }

    private record FunctionStatus(String icon, Color color) {}

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
