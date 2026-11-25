package com.example.gui.ui.components;

import com.example.gui.ui.Palette;
import com.example.gui.ui.Theme;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** "1. Project › 2. Specification › 3. WP Config": current blue and bold, done green, next grey. */
public final class WizardStepIndicator extends JPanel {

    private final List<JLabel> steps = new ArrayList<>();

    public WizardStepIndicator(String... names) {
        super(new FlowLayout(FlowLayout.LEFT, 0, 0));
        setOpaque(false);
        for (int i = 0; i < names.length; i++) {
            if (i > 0) {
                JLabel sep = new JLabel("  ›  ");
                sep.setFont(Theme.font(Font.PLAIN, 11.5f));
                sep.setForeground(Palette.GRAY);
                add(sep);
            }
            JLabel l = new JLabel((i + 1) + ". " + names[i]);
            steps.add(l);
            add(l);
        }
        setCurrent(0);
    }

    /** @param index 0-based current step */
    public void setCurrent(int index) {
        for (int i = 0; i < steps.size(); i++) {
            JLabel l = steps.get(i);
            l.setFont(Theme.font(i == index ? Font.BOLD : Font.PLAIN, 11.5f));
            l.setForeground(i == index ? Palette.BLUE : i < index ? Palette.GREEN : Palette.GRAY);
        }
    }
}
