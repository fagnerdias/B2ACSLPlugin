package com.example.gui.ui.components;

import com.example.gui.ui.Palette;
import com.example.gui.ui.Theme;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** Card with a small label and a big value (Total Goals, Proved, Failures, Timeouts, Smoke Tests). */
public final class SummaryCard extends JPanel {

    private final JLabel value = new JLabel();

    public SummaryCard(String label, String value) {
        this(label, value, Palette.TEXT);
    }

    public SummaryCard(String label, String value, Color valueColor) {
        super(new BorderLayout(0, 2));
        setBackground(Palette.CARD_BG);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Palette.CARD_BORDER), BorderFactory.createEmptyBorder(5, 8, 6, 8)));
        JLabel l = new JLabel(label);
        l.setFont(Theme.font(Font.PLAIN, 11f));
        l.setForeground(Palette.TEXT);
        this.value.setFont(Theme.font(Font.BOLD, 20f));
        add(l, BorderLayout.NORTH);
        add(this.value, BorderLayout.CENTER);
        setValue(value, valueColor);
    }

    /** A count shown in red when it is not zero (Failures, Timeouts). */
    public static SummaryCard alert(String label, int count) {
        return new SummaryCard(label, Integer.toString(count), count > 0 ? Palette.RED : Palette.TEXT);
    }

    public void setValue(String text, Color color) {
        value.setText(text);
        value.setForeground(color);
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(Math.max(d.width, 84), d.height);
    }
}
