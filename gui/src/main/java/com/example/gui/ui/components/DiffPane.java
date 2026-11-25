package com.example.gui.ui.components;

import com.example.gui.ui.Palette;
import com.example.gui.ui.Theme;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;

/**
 * Read-only code pane with a line-number gutter and one coloured background per row. Two panes
 * showing the aligned rows of a diff have the same number of rows (a {@code null} text is an empty
 * placeholder) and scroll together with {@link #synchronize(JScrollPane, JScrollPane)}.
 */
public final class DiffPane extends JComponent implements Scrollable {

    /**
     * @param number line number shown in the gutter ("" for none)
     * @param text {@code null} for an empty placeholder row
     * @param background row colour, {@code null} for white
     */
    public record Line(String number, String text, Color background) {}

    private static final Color GUTTER_TEXT = new Color(0x9A9A9A);
    private static final Color GUTTER_LINE = new Color(0xDDDDDD);
    private List<Line> lines = List.of();

    public DiffPane() {
        setFont(Theme.mono());
        setOpaque(true);
        setBackground(Palette.WHITE);
    }

    public void setLines(List<Line> lines) {
        this.lines = new ArrayList<>(lines);
        revalidate();
        repaint();
    }

    private int rowHeight() {
        return getFontMetrics(getFont()).getHeight() + 6;
    }

    private int gutterWidth() {
        int digits = 2;
        for (Line l : lines) {
            digits = Math.max(digits, l.number() == null ? 0 : l.number().length());
        }
        return getFontMetrics(getFont()).charWidth('0') * digits + 18;
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        int w = 0;
        for (Line l : lines) {
            if (l.text() != null) {
                w = Math.max(w, fm.stringWidth(l.text()));
            }
        }
        return new Dimension(gutterWidth() + w + 20, Math.max(rowHeight() * lines.size(), rowHeight()));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setColor(getBackground());
        g2.fillRect(0, 0, getWidth(), getHeight());
        FontMetrics fm = g2.getFontMetrics(getFont());
        g2.setFont(getFont());
        int rh = rowHeight();
        int gutter = gutterWidth();
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            int y = i * rh;
            Color bg = l.text() == null ? Palette.DIFF_PLACEHOLDER : l.background();
            if (bg != null) {
                g2.setColor(bg);
                g2.fillRect(0, y, getWidth(), rh);
            }
            int baseline = y + (rh - fm.getHeight()) / 2 + fm.getAscent();
            if (l.number() != null && !l.number().isEmpty() && l.text() != null) {
                g2.setColor(GUTTER_TEXT);
                g2.drawString(l.number(), gutter - 10 - fm.stringWidth(l.number()), baseline);
            }
            if (l.text() != null) {
                g2.setColor(Palette.TEXT);
                g2.drawString(l.text(), gutter + 8, baseline);
            }
        }
        g2.setColor(GUTTER_LINE);
        g2.drawLine(gutter, 0, gutter, getHeight());
        g2.dispose();
    }

    /** Scrolls so that row {@code index} is visible, with a few rows of context above it. */
    public void scrollToRow(int index) {
        int rh = rowHeight();
        int y = Math.max(0, (index - 3) * rh);
        int h = getParent() == null ? rh : getParent().getHeight();
        scrollRectToVisible(new Rectangle(0, y, 1, h));
    }

    /** Keeps the vertical position of two scroll panes in step. */
    public static void synchronize(JScrollPane a, JScrollPane b) {
        boolean[] busy = {false};
        a.getVerticalScrollBar().addAdjustmentListener(e -> follow(busy, b, e.getValue()));
        b.getVerticalScrollBar().addAdjustmentListener(e -> follow(busy, a, e.getValue()));
    }

    private static void follow(boolean[] busy, JScrollPane other, int value) {
        if (busy[0]) {
            return;
        }
        busy[0] = true;
        try {
            other.getVerticalScrollBar().setValue(value);
        } finally {
            busy[0] = false;
        }
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return new Dimension(360, rowHeight() * 10);
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? rowHeight() : 16;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? visibleRect.height - rowHeight() : visibleRect.width / 2;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return getParent() != null && getParent().getWidth() > getPreferredSize().width;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return getParent() != null && getParent().getHeight() > getPreferredSize().height;
    }
}
