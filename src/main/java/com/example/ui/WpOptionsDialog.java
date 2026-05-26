package com.example.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.EmptyBorder;

public final class WpOptionsDialog {

    private static final String DEFAULT_WP_PROVER = "CVC5";
    private static final int DEFAULT_WP_TIMEOUT_SECONDS = 100;
    private static final String DEFAULT_WP_OUTPUT = "status";
    private static final String DEFAULT_PROJECT_NAME = "Project";

    private WpOptionsDialog() {}

    public record WpOptions(String projectName, String prover, int timeoutSeconds, String outputFlag) {}

    public static WpOptions promptWpOptions() {
        return promptWpOptions(DEFAULT_PROJECT_NAME);
    }

    public static WpOptions promptWpOptions(String defaultProjectName) {
        if (!isUiAvailable()) {
            return readHeadlessOptions(defaultProjectName);
        }
        try {
            JTextField projectField =
                new JTextField(
                        defaultProjectName == null || defaultProjectName.isBlank()
                                ? DEFAULT_PROJECT_NAME
                                : defaultProjectName);
            projectField.setEditable(false);
            JComboBox<String> proverCombo = new JComboBox<>(new String[] {"CVC5", "Z3", "Alt-Ergo"});
            proverCombo.setSelectedItem(DEFAULT_WP_PROVER);

            JSpinner timeoutSpinner =
                new JSpinner(new SpinnerNumberModel(DEFAULT_WP_TIMEOUT_SECONDS, 1, 36000, 1));
            JComponent editor = timeoutSpinner.getEditor();
            if (editor instanceof JSpinner.DefaultEditor defaultEditor) {
                defaultEditor.getTextField().setColumns(5);
            }

            JComboBox<String> outputCombo = new JComboBox<>(new String[] {"status", "print"});
            outputCombo.setSelectedItem(DEFAULT_WP_OUTPUT);

            JPanel root = new JPanel(new BorderLayout());
            root.setBorder(new EmptyBorder(0, 0, 0, 0));

            JPanel header = new JPanel();
            header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
            header.setBorder(new EmptyBorder(14, 16, 14, 16));        
            JLabel title = new JLabel("Frama-C WP Config");
            title.setForeground(Color.BLACK);
            title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
            JLabel subtitle = new JLabel("Configure prover and output");
            subtitle.setForeground(Color.BLACK);
            subtitle.setFont(subtitle.getFont().deriveFont(Font.PLAIN, 14f));
            header.add(title);
            header.add(Box.createVerticalStrut(4));
            header.add(subtitle);
            root.add(header, BorderLayout.NORTH);

            JPanel content = new JPanel(new GridBagLayout());
            content.setBorder(new EmptyBorder(16, 16, 10, 16));
            content.setBackground(new Color(246, 247, 251));
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(0, 0, 10, 10);
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;

            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.gridwidth = 2;
            content.add(field("Project name", projectField), gbc);

            gbc.gridy = 1;
            content.add(field("Prover", proverCombo), gbc);

            gbc.gridy = 2;
            gbc.gridwidth = 1;
            gbc.insets = new Insets(0, 0, 0, 10);
            content.add(field("Timeout (sec)", timeoutSpinner), gbc);

            gbc.gridx = 1;
            gbc.insets = new Insets(0, 0, 0, 0);
            content.add(field("Output type", outputCombo), gbc);
            root.add(content, BorderLayout.CENTER);

            Object[] options = {"Cancel", "Run Verification"};
            while (true) {
                int result =
                        JOptionPane.showOptionDialog(
                                null,
                                root,
                                "Frama-C WP Configuration",
                                JOptionPane.DEFAULT_OPTION,
                                JOptionPane.PLAIN_MESSAGE,
                                null,
                                options,
                                options[1]);
                if (result != 1) {
                    return null;
                }

                String project = projectField.getText();
                String prover = (String) proverCombo.getSelectedItem();
                int timeout = ((Number) timeoutSpinner.getValue()).intValue();
                String output = (String) outputCombo.getSelectedItem();
                WpOptions optionsModel = buildWpOptions(project, prover, timeout, output);
                return optionsModel;
            }
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            System.err.println(
                    "[B2ACSL] GUI is unavailable in this native runtime; using headless WP options.");
            return readHeadlessOptions(defaultProjectName);
        }
    }

    private static boolean isUiAvailable() {
        try {
            return !GraphicsEnvironment.isHeadless();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static WpOptions readHeadlessOptions(String defaultProjectName) {
        String projectName =
                System.getProperty(
                        "b2acsl.wp.project",
                        defaultProjectName == null || defaultProjectName.isBlank()
                                ? DEFAULT_PROJECT_NAME
                                : defaultProjectName);
        String prover = System.getProperty("b2acsl.wp.prover", DEFAULT_WP_PROVER);
        int timeout = Integer.getInteger("b2acsl.wp.timeout", DEFAULT_WP_TIMEOUT_SECONDS);
        String output = System.getProperty("b2acsl.wp.output", DEFAULT_WP_OUTPUT);
        return buildWpOptions(projectName, prover, timeout, output);
    }

    private static JPanel field(String labelText, JComponent input) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        JLabel label = new JLabel(labelText);
        label.setBorder(new EmptyBorder(0, 2, 4, 0));
        p.add(label);
        input.setBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(191, 198, 214)),
                        new EmptyBorder(new Insets(6, 8, 6, 8))));
        if (input instanceof JComboBox<?> combo) {
            combo.setBackground(Color.WHITE);
        }
        if (input instanceof JTextField field) {
            field.setColumns(28);
        }
        input.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        p.add(input);
        return p;
    }

    private static WpOptions buildWpOptions(
            String projectName, String prover, int timeoutSeconds, String outputMode) {
        String normalizedProjectName = projectName == null ? "" : projectName.trim();
        if (normalizedProjectName.isBlank()) {
            normalizedProjectName = DEFAULT_PROJECT_NAME;
        }
        String normalizedProver =
                switch (prover == null ? "" : prover.trim().toUpperCase()) {
                    case "Z3" -> "Z3";
                    case "ALT-ERGO" -> "Alt-Ergo";
                    case "CVC5" -> "CVC5";
                    default -> DEFAULT_WP_PROVER;
                };
        int normalizedTimeout = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_WP_TIMEOUT_SECONDS;
        String normalizedOutput =
                switch (outputMode == null ? "" : outputMode.trim().toLowerCase()) {
                    case "print", "-wp-print" -> "-wp-print";
                    case "status", "-wp-status" -> "-wp-status";
                    default -> "-wp-" + DEFAULT_WP_OUTPUT;
                };
        return new WpOptions(normalizedProjectName, normalizedProver, normalizedTimeout, normalizedOutput);
    }
}
