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

    private static final float LABEL_SIZE = 11f;
    private static final float VALUE_SIZE = 20f;
    private final JLabel label;
    private final JLabel value = new JLabel();

    public SummaryCard(String label, String value) {
        this(label, value, Palette.TEXT);
    }

    public SummaryCard(String label, String value, Color valueColor) {
        super(new BorderLayout(0, 2));
        setBackground(Palette.CARD_BG);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Palette.CARD_BORDER), BorderFactory.createEmptyBorder(5, 7, 6, 7)));
        this.label = new JLabel(label);
        this.label.setFont(Theme.font(Font.PLAIN, LABEL_SIZE));
        this.label.setForeground(Palette.TEXT);
        this.value.setFont(Theme.font(Font.BOLD, VALUE_SIZE));
        add(this.label, BorderLayout.NORTH);
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

    /** In a narrow window the label and the value get a smaller font instead of being cut ("12 /…"). */
    @Override
    public void doLayout() {
        super.doLayout();
        fit(label, LABEL_SIZE, 9f);
        fit(value, VALUE_SIZE, 12f);
    }

    private static void fit(JLabel l, float max, float min) {
        int available = l.getWidth();
        if (available <= 0 || l.getText() == null) {
            return;
        }
        float size = max;
        while (size > min && l.getFontMetrics(l.getFont().deriveFont(size)).stringWidth(l.getText()) > available) {
            size -= 0.5f;
        }
        if (l.getFont().getSize2D() != size) {
            l.setFont(l.getFont().deriveFont(size));
        }
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(Math.max(d.width, 84), Math.max(d.height, 54));
    }
}
