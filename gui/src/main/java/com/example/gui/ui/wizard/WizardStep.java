package com.example.gui.ui.wizard;

import com.example.gui.ui.Palette;
import com.example.gui.ui.Theme;
import com.example.gui.ui.Ui;
import com.example.gui.ui.components.WizardStepIndicator;
import java.awt.BorderLayout;
import java.awt.Font;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSeparator;

/** Common frame of a wizard step: header, step indicator, content and the button bar. */
abstract class WizardStep extends JPanel {

    static final String[] STEPS = {"Project", "Specification", "WP Config"};

    protected final WizardFrame frame;
    protected final WizardState state;

    WizardStep(WizardFrame frame, WizardState state, int index, String subtitle) {
        super(new BorderLayout(0, 12));
        this.frame = frame;
        this.state = state;
        setBorder(Ui.padding(14, 14, 14, 14));
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
        add(head, BorderLayout.NORTH);
    }

    protected void setContent(JComponent content) {
        add(content, BorderLayout.CENTER);
    }

    protected void setButtons(JComponent... buttons) {
        add(Ui.buttonBar(buttons), BorderLayout.SOUTH);
    }

    /** Called every time the step becomes visible. */
    void onShow() {}
}
