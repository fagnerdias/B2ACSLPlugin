package com.example.gui.ui.wizard;

import com.example.gui.model.WpConfig;
import com.example.gui.ui.Palette;
import com.example.gui.ui.Ui;
import com.example.gui.ui.VerticalLayout;
import com.example.gui.ui.components.Callout;
import com.example.gui.verification.ToolDetector;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/** Step 3: provers, timeout, output type, smoke tests and counterexamples. */
final class WpConfigStep extends WizardStep {

    private final JLabel projectLabel = Ui.secondary(" ");
    private final JLabel specLabel = Ui.secondary(" ");
    private final Map<String, JCheckBox> provers = new LinkedHashMap<>();
    private final Callout missing = new Callout(Callout.Kind.WARNING, null, " ", 230);
    private final JSpinner timeout = new JSpinner(new SpinnerNumberModel(10, 1, 3600, 1));
    private final JComboBox<String> outputType = new JComboBox<>(WpConfig.OUTPUT_TYPES.toArray(String[]::new));
    private final JCheckBox smoke = new JCheckBox("Enable Smoke Tests (-wp-smoke-tests)");
    private final JCheckBox counterExamples = new JCheckBox("Counter Examples (-wp-counter-examples)");
    private final JButton run;

    WpConfigStep(WizardFrame frame, WizardState state) {
        super(frame, state, 2, "Configure prover and output");

        JPanel proverBox = new JPanel(new VerticalLayout(2));
        proverBox.setOpaque(true);
        proverBox.setBackground(new java.awt.Color(0xF7F7F7));
        proverBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Palette.INPUT_BORDER), Ui.padding(4, 6, 4, 10)));
        for (String p : ToolDetector.PROVERS) {
            JCheckBox box = new JCheckBox(p);
            box.setOpaque(false);
            box.addActionListener(e -> updateRun());
            provers.put(p, box);
            proverBox.add(box);
        }
        JPanel proverRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        proverRow.setOpaque(false);
        proverRow.add(proverBox);
        missing.setVisible(false);

        JPanel options = new JPanel(new GridBagLayout());
        options.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 4, 14);
        c.gridx = 0;
        c.gridy = 0;
        options.add(Ui.bold("Timeout (sec)"), c);
        c.gridx = 1;
        c.insets = new Insets(0, 0, 4, 0);
        options.add(Ui.bold("Output type"), c);
        c.gridy = 1;
        c.gridx = 0;
        c.weightx = 1;
        c.insets = new Insets(0, 0, 0, 14);
        timeout.setPreferredSize(new java.awt.Dimension(150, timeout.getPreferredSize().height + 4));
        options.add(timeout, c);
        c.gridx = 1;
        c.insets = new Insets(0, 0, 0, 0);
        outputType.setPreferredSize(new java.awt.Dimension(120, timeout.getPreferredSize().height));
        options.add(outputType, c);

        smoke.setOpaque(false);
        counterExamples.setOpaque(false);
        JLabel proversTitle = Ui.bold("Provers (Select multiple):");
        setContent(Ui.stack(8,
                Ui.stack(2, projectLabel, specLabel),
                Ui.stack(5, proversTitle, proverRow, missing),
                options,
                Ui.stack(2, smoke, counterExamples)));
        run = Ui.button("Run Verification", e -> runVerification());
        setButtons(Ui.button("Back", e -> frame.back()), Ui.button("Cancel", e -> frame.requestClose()), run);
        load(state.wpConfig);
    }

    @Override
    void onShow() {
        projectLabel.setText("Project: " + state.project.name());
        specLabel.setText("Specification: " + state.mode.label());
        toolsDetected();
    }

    /** Disables the provers that are not installed (with a warning); called when the check ends. */
    void toolsDetected() {
        List<String> notInstalled = new ArrayList<>();
        for (var e : provers.entrySet()) {
            JCheckBox box = e.getValue();
            boolean installed = state.tools == null || state.tools.installed(e.getKey());
            box.setEnabled(installed);
            if (!installed) {
                box.setSelected(false);
                box.setText(e.getKey() + "  (not installed)");
                box.setToolTipText(e.getKey() + " was not found by \"why3 config list-provers\" nor on the PATH.");
                notInstalled.add(e.getKey());
            } else {
                box.setText(e.getKey());
                String version = state.tools == null ? "" : state.tools.provers().get(e.getKey());
                box.setToolTipText(version == null || version.isEmpty() ? null : e.getKey() + " " + version);
            }
        }
        missing.setVisible(!notInstalled.isEmpty());
        if (!notInstalled.isEmpty()) {
            missing.set(null, "Not installed: " + String.join(", ", notInstalled)
                    + ". Install it and restart the tool to use it.", List.of());
        }
        updateRun();
        if (isShowing()) {
            frame.pack();
        }
    }

    private void load(WpConfig config) {
        for (var e : provers.entrySet()) {
            e.getValue().setSelected(config.provers().contains(e.getKey()));
        }
        timeout.setValue(config.timeoutSeconds());
        outputType.setSelectedItem(config.outputType());
        smoke.setSelected(config.smokeTests());
        counterExamples.setSelected(config.counterExamples());
        updateRun();
    }

    private WpConfig current() {
        List<String> chosen = provers.entrySet().stream()
                .filter(e -> e.getValue().isEnabled() && e.getValue().isSelected())
                .map(Map.Entry::getKey)
                .toList();
        return new WpConfig(chosen, (Integer) timeout.getValue(), (String) outputType.getSelectedItem(),
                smoke.isSelected(), counterExamples.isSelected());
    }

    private void updateRun() {
        if (run != null) {
            run.setEnabled(!current().provers().isEmpty());
        }
    }

    private void runVerification() {
        try {
            timeout.commitEdit();
        } catch (java.text.ParseException e) {
            // keeps the last valid value
        }
        state.wpConfig = current();
        frame.runVerification();
    }
}
