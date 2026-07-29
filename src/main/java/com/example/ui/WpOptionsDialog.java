package com.example.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.HeadlessException;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.EmptyBorder;

public final class WpOptionsDialog {

    private static final String DEFAULT_WP_PROVER = "CVC5";
    private static final int DEFAULT_WP_TIMEOUT_SECONDS = 10;
    private static final String DEFAULT_WP_OUTPUT = "status";
    private static final String DEFAULT_PROJECT_NAME = "Project";

    private WpOptionsDialog() {}

    public record WpOptions(
            String projectName,
            List<String> provers,
            int timeoutSeconds,
            String outputFlag,
            boolean loopSimplification,
            boolean smokeTests,
            boolean verifyPerOperation,
            boolean counterExamples,
            boolean splitGoals) {

        /** Argumento único para {@code -wp-prover} (lista separada por vírgulas). */
        public String proversArgument() {
            if (provers == null || provers.isEmpty()) {
                return DEFAULT_WP_PROVER;
            }
            return String.join(",", provers);
        }
    }

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

            JCheckBox altErgoBox = new JCheckBox("Alt-Ergo", false);
            JCheckBox cvc5Box = new JCheckBox("CVC5", true);            
            JCheckBox z3Box = new JCheckBox("Z3", false);            

            JSpinner timeoutSpinner =
                    new JSpinner(new SpinnerNumberModel(DEFAULT_WP_TIMEOUT_SECONDS, 1, 36000, 1));
            JComponent editor = timeoutSpinner.getEditor();
            if (editor instanceof JSpinner.DefaultEditor defaultEditor) {
                defaultEditor.getTextField().setColumns(5);
            }

            JComboBox<String> outputCombo = new JComboBox<>(new String[] {"status", "print"});
            outputCombo.setSelectedItem(DEFAULT_WP_OUTPUT);

            JCheckBox loopSimplificationBox = new JCheckBox("Enable Loop Simplification", false);
            JCheckBox smokeTestsBox = new JCheckBox("Enable Smoke Tests (-wp-smoke-tests)", true);
            JCheckBox counterExamplesBox = new JCheckBox("Counter Examples (-wp-counter-examples)", true);
            JCheckBox splitGoalsBox = new JCheckBox("Split Goals (-wp-split)", false);
            JCheckBox verifyPerOperationBox =
                    new JCheckBox("Verify each operation separately (-wp-fct)", true);

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
            content.add(proversField(altErgoBox, cvc5Box, z3Box), gbc);

            gbc.gridy = 2;
            gbc.gridwidth = 1;
            gbc.insets = new Insets(0, 0, 0, 10);
            content.add(field("Timeout (sec)", timeoutSpinner), gbc);

            gbc.gridx = 1;
            gbc.insets = new Insets(0, 0, 0, 0);
            content.add(field("Output type", outputCombo), gbc);

            gbc.gridx = 0;
            gbc.gridy = 3;
            gbc.gridwidth = 2;
            gbc.insets = new Insets(4, 2, 0, 0);
            smokeTestsBox.setOpaque(false);
            content.add(smokeTestsBox, gbc);

            // gbc.gridy = 4;
            // counterExamplesBox.setOpaque(false);
            // content.add(counterExamplesBox, gbc);

            // gbc.gridy = 5;
            // splitGoalsBox.setOpaque(false);
            // content.add(splitGoalsBox, gbc);

            root.add(content, BorderLayout.CENTER);

            JButton cancelButton = new JButton("Cancel");
            JButton runButton = new JButton("Run Verification");
            JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
            buttonBar.add(cancelButton);
            buttonBar.add(runButton);
            root.add(buttonBar, BorderLayout.SOUTH);

            JFrame frame = new JFrame("Frama-C WP Configuration");
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

            CountDownLatch latch = new CountDownLatch(1);
            WpOptions[] resultHolder = new WpOptions[1];
            Runnable cancel =
                    () -> {
                        resultHolder[0] = null;
                        frame.dispose();
                        latch.countDown();
                    };
            cancelButton.addActionListener(e -> cancel.run());
            frame.addWindowListener(
                    new WindowAdapter() {
                        @Override
                        public void windowClosing(WindowEvent e) {
                            cancel.run();
                        }
                    });
            runButton.addActionListener(
                    e -> {
                        List<String> selectedProvers = selectedProvers(cvc5Box, altErgoBox, z3Box);
                        if (selectedProvers.isEmpty()) {
                            JOptionPane.showMessageDialog(
                                    frame,
                                    "Select at least one prover.",
                                    "Frama-C WP Configuration",
                                    JOptionPane.WARNING_MESSAGE);
                            return;
                        }
                        String project = projectField.getText();
                        int timeout = ((Number) timeoutSpinner.getValue()).intValue();
                        String output = (String) outputCombo.getSelectedItem();
                        resultHolder[0] =
                                buildWpOptions(
                                        project,
                                        selectedProvers,
                                        timeout,
                                        output,
                                        loopSimplificationBox.isSelected(),
                                        smokeTestsBox.isSelected(),
                                        verifyPerOperationBox.isSelected(),
                                        counterExamplesBox.isSelected(),
                                        splitGoalsBox.isSelected());
                        frame.dispose();
                        latch.countDown();
                    });

            frame.setContentPane(root);
            frame.pack();
            frame.setMinimumSize(frame.getSize());
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);

            latch.await();
            return resultHolder[0];
        } catch (UnsatisfiedLinkError | NoClassDefFoundError | HeadlessException e) {
            System.err.println(
                    "[B2ACSL] GUI is unavailable in this native runtime; using headless WP options.");
            return readHeadlessOptions(defaultProjectName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return readHeadlessOptions(defaultProjectName);
        }
    }

    private static List<String> selectedProvers(
            JCheckBox cvc5, JCheckBox altErgo, JCheckBox z3) {
        List<String> out = new ArrayList<>();
        if (cvc5.isSelected()) {
            out.add("CVC5");
        }
        if (altErgo.isSelected()) {
            out.add("Alt-Ergo");
        }
        if (z3.isSelected()) {
            out.add("Z3");
        }        
        return out;
    }

    private static JPanel proversField(
            JCheckBox cvc5, JCheckBox altErgo, JCheckBox z3) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        JLabel label = new JLabel("Provers (Select multiple):");
        label.setBorder(new EmptyBorder(0, 2, 6, 0));
        p.add(label);
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setOpaque(false);
        row.setBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(191, 198, 214)),
                        new EmptyBorder(new Insets(8, 10, 8, 10))));
        row.setBackground(Color.WHITE);
        for (JCheckBox box : new JCheckBox[] {cvc5, altErgo, z3}) {
            box.setOpaque(false);
            box.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            row.add(box);
        }
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        p.add(row);
        return p;
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
        String proverProp = System.getProperty("b2acsl.wp.prover", DEFAULT_WP_PROVER);
        List<String> provers = parseProverList(proverProp);
        int timeout = Integer.getInteger("b2acsl.wp.timeout", DEFAULT_WP_TIMEOUT_SECONDS);
        String output = System.getProperty("b2acsl.wp.output", DEFAULT_WP_OUTPUT);
        boolean loopSimplification =
                Boolean.parseBoolean(System.getProperty("b2acsl.wp.loopSimplification", "false"));
        boolean smokeTests =
                Boolean.parseBoolean(System.getProperty("b2acsl.wp.smokeTests", "true"));
        boolean verifyPerOperation =
                Boolean.parseBoolean(System.getProperty("b2acsl.wp.verifyPerOperation", "false"));
        boolean counterExamples =
                Boolean.parseBoolean(System.getProperty("b2acsl.wp.counterExamples", "true"));
        boolean splitGoals =
                Boolean.parseBoolean(System.getProperty("b2acsl.wp.splitGoals", "true"));
        return buildWpOptions(
                projectName,
                provers,
                timeout,
                output,
                loopSimplification,
                smokeTests,
                verifyPerOperation,
                counterExamples,
                splitGoals);
    }

    private static List<String> parseProverList(String raw) {
        Set<String> ordered = new LinkedHashSet<>();
        if (raw != null) {
            for (String part : raw.split("[,;]")) {
                String p = part.trim();
                if (!p.isBlank()) {
                    ordered.add(normalizeProverName(p));
                }
            }
        }
        if (ordered.isEmpty()) {
            ordered.add(DEFAULT_WP_PROVER);
        }
        return List.copyOf(ordered);
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
            field.setBackground(Color.WHITE);
        }
        if (input instanceof JSpinner spinner) {
            spinner.setBackground(Color.WHITE);
        }
        input.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        p.add(input);
        return p;
    }

    private static WpOptions buildWpOptions(
            String projectName,
            List<String> provers,
            int timeoutSeconds,
            String outputMode,
            boolean loopSimplification,
            boolean smokeTests,
            boolean verifyPerOperation,
            boolean counterExamples,
            boolean splitGoals) {
        String normalizedProjectName = projectName == null ? "" : projectName.trim();
        if (normalizedProjectName.isBlank()) {
            normalizedProjectName = DEFAULT_PROJECT_NAME;
        }
        List<String> normalizedProvers = new ArrayList<>();
        if (provers != null) {
            for (String p : provers) {
                if (p != null && !p.isBlank()) {
                    normalizedProvers.add(normalizeProverName(p.trim()));
                }
            }
        }
        if (normalizedProvers.isEmpty()) {
            normalizedProvers.add(DEFAULT_WP_PROVER);
        }
        int normalizedTimeout = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_WP_TIMEOUT_SECONDS;
        String normalizedOutput =
                switch (outputMode == null ? "" : outputMode.trim().toLowerCase()) {
                    case "print", "-wp-print" -> "-wp-print";
                    case "status", "-wp-status" -> "-wp-status";
                    default -> "-wp-" + DEFAULT_WP_OUTPUT;
                };
        return new WpOptions(
                normalizedProjectName,
                List.copyOf(normalizedProvers),
                normalizedTimeout,
                normalizedOutput,
                loopSimplification,
                smokeTests,
                verifyPerOperation,
                counterExamples,
                splitGoals);
    }

    private static String normalizeProverName(String prover) {
        return switch (prover.toUpperCase()) {
            case "Z3" -> "Z3";
            case "ALT-ERGO", "ALTERGO" -> "Alt-Ergo";
            case "COQ" -> "Coq";
            case "CVC5" -> "CVC5";
            default -> DEFAULT_WP_PROVER;
        };
    }
}
