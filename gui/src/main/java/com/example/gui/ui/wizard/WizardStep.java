package com.example.gui.ui.wizard;

import com.example.gui.ui.CenteredColumnLayout;
import com.example.gui.ui.Palette;
import com.example.gui.ui.Theme;
import com.example.gui.ui.Ui;
import com.example.gui.ui.components.WizardStepIndicator;
import java.awt.BorderLayout;
import java.awt.Font;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSeparator;

/**
 * Common frame of a wizard step: header, step indicator, content and the button bar, in a centred
 * column of the standard window.
 */
abstract class WizardStep extends JPanel {

    static final String[] STEPS = {"Project", "Specification", "WP Config"};

    protected final WizardFrame frame;
    protected final WizardState state;
    private final JPanel column = new JPanel(new BorderLayout(0, 12));

    WizardStep(WizardFrame frame, WizardState state, int index, String subtitle) {
        super(new CenteredColumnLayout(Ui.WIZARD_COLUMN));
        this.frame = frame;
        this.state = state;
        column.setOpaque(false);
        column.setBorder(Ui.padding(18, 18, 16, 18));
        add(column);
        WizardStepIndicator indicator = new WizardStepIndicator(STEPS);
        indicator.setCurrent(index);
        JPanel head = Ui.stack(4,
                Ui.title("Frama-C WP Config", 20f),
                Ui.colored(subtitle, Palette.TEXT, false),
                Ui.stack(0, javax.swing.Box.createVerticalStrut(6)),
                indicator,
                Ui.stack(0, javax.swing.Box.createVerticalStrut(6)),
                new JSeparator());
        head.getComponent(1).setFont(Theme.font(Font.PLAIN, 13f));
        column.add(head, BorderLayout.NORTH);
    }

    protected void setContent(JComponent content) {
        column.add(content, BorderLayout.CENTER);
    }

    protected void setButtons(JComponent... buttons) {
        column.add(Ui.buttonBar(buttons), BorderLayout.SOUTH);
    }

    /** Called every time the step becomes visible. */
    void onShow() {}
}
