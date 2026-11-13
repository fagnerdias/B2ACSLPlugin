package com.example.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;

/**
 * Diálogo mostrado quando o Frama-C falha ANTES da fase de prova WP começar — tipicamente
 * {@code -acsl-import} rejeitando o {@code .acsl}/{@code .c} gerado com um erro de sintaxe ou de
 * tipagem. Até esta classe existir, essa falha só produzia um exit code silencioso: a mensagem
 * real do Frama-C ficava presa em {@code merged_code.c} (sobre-escrita pelo {@code -print} de
 * saída), nunca visível para quem invoca o plugin a partir do Atelier B (sem console).
 */
public final class PreVerificationErrorDialog {

    private static final Color STATUS_FAIL = new Color(180, 36, 36);

    private PreVerificationErrorDialog() {}

    /**
     * @param stage rótulo curto da etapa que falhou, ex. {@code "Frama-C -acsl-import"}
     * @param command comando exato que foi executado (para reprodutibilidade/depuração)
     * @param rawOutput saída bruta capturada do Frama-C (stdout+stderr) para essa etapa
     */
    public static void show(String projectName, String stage, String command, String rawOutput) {
        String headline = extractHeadline(rawOutput);
        if (!isUiAvailable()) {
            printHeadlessReport(projectName, stage, command, headline, rawOutput);
            return;
        }
        try {
            runErrorFrame(projectName, stage, command, headline, rawOutput);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError | HeadlessException e) {
            System.err.println(
                    "[B2ACSL] GUI is unavailable in this native runtime; printing pre-verification error in console.");
            printHeadlessReport(projectName, stage, command, headline, rawOutput);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            printHeadlessReport(projectName, stage, command, headline, rawOutput);
        }
    }

    /**
     * Isola, do output bruto do Frama-C, as linhas de diagnóstico (User Error / Failure /
     * aborted / linhas prefixadas por {@code [plugin]}) que realmente importam para o
     * utilizador — descartando ruído como "[kernel] Parsing X (with preprocessing)". Se nada
     * casar, cai para as últimas linhas não vazias do output (o Frama-C tipicamente termina com
     * a mensagem mais relevante).
     */
    static String extractHeadline(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return "Frama-C failed but produced no output.";
        }
        List<String> matches = new ArrayList<>();
        boolean previousLineMatched = false;
        for (String line : rawOutput.split("\\R")) {
            if (line.isBlank()) {
                previousLineMatched = false;
                continue;
            }
            String trimmed = line.strip();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            boolean isDiagnostic =
                    lower.contains("user error")
                            || lower.contains("failure")
                            || lower.contains("aborted")
                            || lower.contains("syntax error");
            // Frama-C often wraps a diagnostic's actual detail (e.g. "unbound logic variable
            // SBOOL...") on an indented continuation line right after "Failure:" — keep it too,
            // or the headline would show only the uninformative "Failure:" line.
            boolean isIndentedContinuation = previousLineMatched && line.startsWith(" ");
            if (isDiagnostic || isIndentedContinuation) {
                matches.add(trimmed);
                previousLineMatched = true;
            } else {
                previousLineMatched = false;
            }
        }
        if (!matches.isEmpty()) {
            return String.join("\n", matches);
        }
        List<String> nonEmpty = new ArrayList<>();
        for (String line : rawOutput.split("\\R")) {
            if (!line.isBlank()) {
                nonEmpty.add(line.strip());
            }
        }
        if (nonEmpty.isEmpty()) {
            return "Frama-C failed but produced no output.";
        }
        int from = Math.max(0, nonEmpty.size() - 5);
        return String.join("\n", nonEmpty.subList(from, nonEmpty.size()));
    }

    private static void runErrorFrame(
            String projectName, String stage, String command, String headline, String rawOutput)
            throws InterruptedException {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        root.add(buildHeader(projectName, stage), BorderLayout.NORTH);
        root.add(buildHeadlinePanel(headline), BorderLayout.CENTER);
        root.add(buildOutputPanel(command, rawOutput), BorderLayout.SOUTH);

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

        JFrame frame = new JFrame("Verification could not start");
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
        saveButton.addActionListener(e -> saveFullOutput(projectName, command, rawOutput));

        frame.setContentPane(contentPane);
        frame.setMinimumSize(new Dimension(900, 600));
        frame.setSize(new Dimension(1000, 700));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        latch.await();
    }

    private static JPanel buildHeader(String projectName, String stage) {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(STATUS_FAIL_ICON + " Verification could not start");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        title.setForeground(STATUS_FAIL);
        header.add(title);
        header.add(new JLabel("Project: " + projectName));
        header.add(new JLabel("Failed stage: " + stage));
        return header;
    }

    private static final String STATUS_FAIL_ICON = "✗";

    private static JPanel buildHeadlinePanel(String headline) {
        JPanel panel = new JPanel(new BorderLayout());
        TitledBorder titled = BorderFactory.createTitledBorder("What went wrong");
        titled.setTitleFont(titled.getTitleFont().deriveFont(Font.PLAIN, 11f));
        panel.setBorder(titled);

        JTextArea area = new JTextArea(headline, 6, 96);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setForeground(STATUS_FAIL);
        area.setFont(area.getFont().deriveFont(Font.BOLD));
        panel.add(new JScrollPane(area), BorderLayout.CENTER);
        return panel;
    }

    private static JPanel buildOutputPanel(String command, String rawOutput) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Full Frama-C output"));

        JTextArea commandArea = new JTextArea("Command: " + command, 2, 96);
        commandArea.setEditable(false);
        commandArea.setLineWrap(true);
        commandArea.setWrapStyleWord(true);
        commandArea.setBorder(new EmptyBorder(0, 0, 4, 0));

        JTextArea outputArea = new JTextArea(rawOutput, 16, 96);
        outputArea.setEditable(false);
        outputArea.setLineWrap(true);
        outputArea.setWrapStyleWord(true);

        JPanel inner = new JPanel(new BorderLayout());
        inner.add(commandArea, BorderLayout.NORTH);
        inner.add(new JScrollPane(outputArea), BorderLayout.CENTER);
        panel.add(inner, BorderLayout.CENTER);
        return panel;
    }

    private static boolean isUiAvailable() {
        try {
            return !GraphicsEnvironment.isHeadless();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void printHeadlessReport(
            String projectName, String stage, String command, String headline, String rawOutput) {
        System.err.println("[B2ACSL] =============================================");
        System.err.println("[B2ACSL] Verification could not start for project: " + projectName);
        System.err.println("[B2ACSL] Failed stage: " + stage);
        System.err.println("[B2ACSL] " + headline.replace("\n", "\n[B2ACSL] "));
        System.err.println("[B2ACSL] Command: " + command);
        System.err.println("[B2ACSL] Full output:");
        System.err.println(rawOutput);
        System.err.println("[B2ACSL] =============================================");
    }

    private static void saveFullOutput(String projectName, String command, String rawOutput) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save Frama-C error log");
        chooser.setSelectedFile(Path.of(defaultFilename(projectName)).toFile());

        int result = chooser.showSaveDialog(null);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path outputPath = chooser.getSelectedFile().toPath();
        try {
            Files.writeString(
                    outputPath,
                    "Command: " + command + System.lineSeparator() + System.lineSeparator() + rawOutput,
                    StandardCharsets.UTF_8);
            JOptionPane.showMessageDialog(
                    null,
                    "Saved error log to:\n" + outputPath,
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
        return safe + "_acsl_import_error.txt";
    }
}
