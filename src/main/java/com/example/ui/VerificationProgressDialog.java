package com.example.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.HeadlessException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;

/**
 * Janela não-modal exibida assim que a verificação WP começa, mostrando o progresso
 * função/operação a função/operação e permitindo consultar o log (combinado, ao vivo) gerado
 * pelo Frama-C durante o processo.
 *
 * <p>Todas as atualizações vindas da thread que efetivamente dispara os processos do Frama-C
 * (não é a EDT) são marshalled para a EDT via {@link SwingUtilities#invokeLater(Runnable)}.
 */
public final class VerificationProgressDialog {

    /** Status possíveis de uma função/operação sendo verificada. */
    public enum Status {
        PENDING(" Pending", new Color(120, 120, 120)),
        RUNNING(" Running…", new Color(30, 90, 200)),
        PROVED(" Proved", new Color(28, 130, 48)),
        PARTIAL(" Partial", new Color(255, 127, 36)),
        TIMEOUT(" Timeout", new Color(176, 128, 18)),
        FAILED(" Failed", new Color(180, 36, 36));

        private final String label;
        private final Color color;

        Status(String label, Color color) {
            this.label = label;
            this.color = color;
        }
    }

    private final JFrame window;
    private final JLabel headerStatusLabel;
    private final JProgressBar progressBar;
    private final JTextArea logArea;
    private final JPanel rowsPanel;
    private final JPanel logPanel;
    private final Map<String, Row> rowsByName = new LinkedHashMap<>();
    private final String projectName;
    private final boolean headless;
    private int completedCount = 0;
    private final int totalCount;

    private record Row(JLabel statusIcon, JLabel statusText, JLabel goalsLabel) {}

    /** Handle "no-op" seguro para ambientes headless: nenhum componente Swing é criado. */
    private VerificationProgressDialog() {
        this.headless = true;
        this.projectName = null;
        this.totalCount = 0;
        this.window = null;
        this.headerStatusLabel = null;
        this.progressBar = null;
        this.logArea = null;
        this.rowsPanel = null;
        this.logPanel = null;
    }

    private VerificationProgressDialog(String projectName, List<String> functionNames) {
        this.headless = false;
        this.projectName = projectName;
        this.totalCount = functionNames.size();

        window = new JFrame("Verification Progress");
        window.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel header = new JPanel(new GridLayout(0, 1, 2, 2));
        JLabel title = new JLabel("Verifying “" + projectName + "”");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        headerStatusLabel = new JLabel(progressText());
        header.add(title);
        header.add(headerStatusLabel);
        root.add(header, BorderLayout.NORTH);

        progressBar = new JProgressBar(0, Math.max(totalCount, 1));
        progressBar.setValue(0);
        progressBar.setStringPainted(true);

        rowsPanel = new JPanel();
        rowsPanel.setLayout(new BoxLayout(rowsPanel, BoxLayout.Y_AXIS));
        for (String functionName : functionNames) {
            rowsPanel.add(buildRow(functionName));
        }
        JScrollPane rowsScroll = new JScrollPane(rowsPanel);
        rowsScroll.setBorder(BorderFactory.createTitledBorder("Operations"));
        rowsScroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel center = new JPanel(new BorderLayout(6, 6));
        center.add(progressBar, BorderLayout.NORTH);
        center.add(rowsScroll, BorderLayout.CENTER);
        root.add(center, BorderLayout.CENTER);

        logArea = new JTextArea(16, 90);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);

        logPanel = new JPanel(new BorderLayout());
        TitledBorder logBorder = BorderFactory.createTitledBorder("Log");
        logPanel.setBorder(logBorder);
        logPanel.add(logScroll, BorderLayout.CENTER);
        JPanel logActions = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        JButton saveLogButton = new JButton("Save Log (.txt)");
        saveLogButton.addActionListener(e -> saveLog());
        logActions.add(saveLogButton);
        logPanel.add(logActions, BorderLayout.SOUTH);
        logPanel.setVisible(false);

        JButton toggleLogButton = new JButton("View Logs");
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> window.setVisible(false));

        JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));        
        buttonBar.add(closeButton);
        buttonBar.add(toggleLogButton);

        toggleLogButton.addActionListener(
                e -> {
                    boolean nowVisible = !logPanel.isVisible();
                    logPanel.setVisible(nowVisible);
                    toggleLogButton.setText(nowVisible ? "Hide Logs" : "View Logs");
                    window.pack();
                });

        JPanel southWrapper = new JPanel(new BorderLayout());
        southWrapper.add(logPanel, BorderLayout.CENTER);
        southWrapper.add(buttonBar, BorderLayout.SOUTH);
        root.add(southWrapper, BorderLayout.SOUTH);

        window.setContentPane(root);
        window.setMinimumSize(new Dimension(640, 420));
        window.pack();
        window.setLocationRelativeTo(null);
    }

    private JPanel buildRow(String functionName) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setBorder(new EmptyBorder(3, 6, 3, 6));

        JLabel statusIcon = new JLabel(iconFor(Status.PENDING));
        statusIcon.setForeground(Status.PENDING.color);
        statusIcon.setFont(statusIcon.getFont().deriveFont(Font.BOLD, 13f));
        statusIcon.setPreferredSize(new Dimension(18, 18));

        JLabel nameLabel = new JLabel(functionName);
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.PLAIN, 12f));

        JLabel statusText = new JLabel(Status.PENDING.label, SwingConstants.RIGHT);
        statusText.setForeground(Status.PENDING.color);
        statusText.setPreferredSize(new Dimension(90, 18));

        JLabel goalsLabel = new JLabel("", SwingConstants.RIGHT);
        goalsLabel.setPreferredSize(new Dimension(110, 18));
        goalsLabel.setFont(goalsLabel.getFont().deriveFont(Font.PLAIN, 11f));

        JPanel right = new JPanel();
        right.setLayout(new BoxLayout(right, BoxLayout.X_AXIS));
        right.add(goalsLabel);
        right.add(statusText);

        row.add(statusIcon, BorderLayout.WEST);
        row.add(nameLabel, BorderLayout.CENTER);
        row.add(right, BorderLayout.EAST);

        rowsByName.put(functionName, new Row(statusIcon, statusText, goalsLabel));
        return row;
    }

    private static String iconFor(Status status) {
        return switch (status) {
            case PENDING -> "○"; // ○
            case RUNNING -> "▶"; // ▶
            case PROVED -> "✓"; // ✓
            case PARTIAL -> "◐"; // ◐
            case TIMEOUT -> "⏱"; // ⏱
            case FAILED -> "✗"; // ✗
        };
    }

    private String progressText() {
        return "Verified " + completedCount + " / " + totalCount + " operation(s)";
    }

    /**
     * Cria e exibe a janela de progresso (não-bloqueante). Em ambiente headless imprime apenas
     * uma linha de log e retorna um handle "no-op" seguro de usar.
     */
    public static VerificationProgressDialog show(String projectName, List<String> functionNames) {
        if (!isUiAvailable()) {
            System.out.println(
                    "[B2ACSL] Starting verification of "
                            + functionNames.size()
                            + " operation(s) for project '"
                            + projectName
                            + "'.");
            return new VerificationProgressDialog();
        }
        try {
            VerificationProgressDialog progress = new VerificationProgressDialog(projectName, functionNames);
            SwingUtilities.invokeLater(() -> progress.window.setVisible(true));
            return progress;
        } catch (UnsatisfiedLinkError | NoClassDefFoundError | HeadlessException e) {
            System.err.println(
                    "[B2ACSL] GUI is unavailable in this native runtime; verification progress will only be printed"
                            + " to the console.");
            return new VerificationProgressDialog();
        }
    }

    private boolean isHeadless() {
        return headless;
    }

    /** Marca a função/operação como em execução. */
    public void markRunning(String functionName) {
        if (isHeadless()) {
            System.out.println("[B2ACSL] Verifying: " + functionName);
            return;
        }
        SwingUtilities.invokeLater(
                () -> {
                    Row row = rowsByName.get(functionName);
                    if (row == null) {
                        return;
                    }
                    row.statusIcon().setText(iconFor(Status.RUNNING));
                    row.statusIcon().setForeground(Status.RUNNING.color);
                    row.statusText().setText(Status.RUNNING.label);
                    row.statusText().setForeground(Status.RUNNING.color);
                    row.goalsLabel().setText("");
                });
    }

    /**
     * Informa quantas provas o WP agendou para a função/operação em execução (linha {@code N goals
     * scheduled} do próprio Frama-C) — chamado assim que essa linha aparece no log ao vivo, antes de
     * {@link #markCompleted} (que só chega no fim, com o resultado). Sem efeito se a função já
     * tiver sido concluída (ex. linha atrasada chegando após o processo terminar).
     */
    public void markGoalsScheduled(String functionName, int scheduled) {
        if (isHeadless()) {
            System.out.println("[B2ACSL] " + functionName + ": " + scheduled + " goal(s) scheduled");
            return;
        }
        SwingUtilities.invokeLater(
                () -> {
                    Row row = rowsByName.get(functionName);
                    if (row == null) {
                        return;
                    }
                    row.goalsLabel().setText(scheduled + (scheduled == 1 ? " goal" : " goals"));
                });
    }

    /** Marca a função/operação como concluída, com o resultado observado. */
    public void markCompleted(
            String functionName, Status status, int proved, int total, String extraNote) {
        completedCount++;
        String goalsText =
                total > 0 ? (proved + " / " + total + " goals") : (extraNote == null ? "" : extraNote);
        if (isHeadless()) {
            System.out.println(
                    "[B2ACSL] " + functionName + " -> " + status.label + (goalsText.isBlank() ? "" : " (" + goalsText + ")"));
            return;
        }
        SwingUtilities.invokeLater(
                () -> {
                    Row row = rowsByName.get(functionName);
                    if (row != null) {
                        row.statusIcon().setText(iconFor(status));
                        row.statusIcon().setForeground(status.color);
                        row.statusText().setText(status.label);
                        row.statusText().setForeground(status.color);
                        row.goalsLabel().setText(goalsText);
                    }
                    progressBar.setValue(Math.min(completedCount, totalCount));
                    headerStatusLabel.setText(progressText());
                });
    }

    /** Anexa uma linha de saída bruta do Frama-C/WP ao painel de log, ao vivo. */
    public void appendLogLine(String line) {
        if (isHeadless() || logArea == null) {
            return;
        }
        SwingUtilities.invokeLater(
                () -> {
                    logArea.append(line);
                    logArea.append("\n");
                    logArea.setCaretPosition(logArea.getDocument().getLength());
                });
    }

    /** Marca o cabeçalho de uma nova fonte de log (ex.: início da verificação de uma função). */
    public void appendLogHeader(String sourceName) {
        appendLogLine("===== " + sourceName + " =====");
    }

    /** Marca o processo completo como finalizado (todas as funções processadas). */
    public void finish(boolean success) {
        if (isHeadless()) {
            System.out.println(
                    "[B2ACSL] Verification finished: " + (success ? "all goals attempted." : "stopped early."));
            return;
        }
        SwingUtilities.invokeLater(
                () -> {
                    headerStatusLabel.setText(
                            (success ? "Finished — " : "Stopped — ") + progressText());
                    window.setTitle("Verification Progress — Done");
                });
    }

    /** Fecha (esconde e descarta) a janela de progresso, ex.: antes de exibir o relatório final. */
    public void close() {
        if (isHeadless()) {
            return;
        }
        SwingUtilities.invokeLater(window::dispose);
    }

    private void saveLog() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save verification log");
        String base = projectName == null || projectName.isBlank() ? "project" : projectName.trim();
        String safe = base.replaceAll("[^A-Za-z0-9._-]", "_");
        chooser.setSelectedFile(Path.of(safe + "_verification_log.txt").toFile());

        int result = chooser.showSaveDialog(window);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path outputPath = chooser.getSelectedFile().toPath();
        try {
            Files.writeString(outputPath, logArea.getText(), StandardCharsets.UTF_8);
            JOptionPane.showMessageDialog(
                    window,
                    "Saved log to:\n" + outputPath,
                    "Export complete",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(
                    window,
                    "Could not save file:\n" + e.getMessage(),
                    "Export failed",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private static boolean isUiAvailable() {
        try {
            return !GraphicsEnvironment.isHeadless();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
