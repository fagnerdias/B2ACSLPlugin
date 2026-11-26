package com.example.gui.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionListener;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.border.Border;

/** Small factory methods shared by the windows. */
public final class Ui {

    /** The size every window of the tool opens with (smaller only when the screen is smaller). */
    public static final java.awt.Dimension WINDOW_SIZE = new java.awt.Dimension(960, 720);

    /** Width of the column that holds the wizard's forms (and of the wizard window itself). */
    public static final int WIZARD_COLUMN = 760;

    /** The wizard window: as tall as the others, as wide as its forms (no empty bands at the sides). */
    public static final java.awt.Dimension WIZARD_SIZE = new java.awt.Dimension(WIZARD_COLUMN, WINDOW_SIZE.height);

    private Ui() {}

    /**
     * Gives a window the standard size, centred on the screen, so that moving from the wizard to the
     * progress window, a report or a dialog keeps the same size and position.
     */
    public static void standardSize(java.awt.Window w) {
        sized(w, WINDOW_SIZE);
    }

    /** The wizard's size: {@link #WIZARD_SIZE}, centred. */
    public static void wizardSize(java.awt.Window w) {
        sized(w, WIZARD_SIZE);
    }

    /**
     * A dialog over another window takes that window's size and place (the error dialog over the
     * wizard is as wide as the wizard); without a visible owner it gets the standard size.
     */
    public static void sameAs(java.awt.Window w, java.awt.Window owner) {
        if (owner == null || !owner.isShowing()) {
            standardSize(w);
            return;
        }
        w.setMinimumSize(new java.awt.Dimension(Math.min(620, owner.getWidth()), Math.min(540, owner.getHeight())));
        w.setSize(owner.getSize());
        w.setLocation(owner.getLocation());
    }

    private static void sized(java.awt.Window w, java.awt.Dimension size) {
        java.awt.Rectangle screen = java.awt.GraphicsEnvironment.isHeadless()
                ? new java.awt.Rectangle(0, 0, 1920, 1080)
                : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int width = Math.min(size.width, screen.width - 40);
        int height = Math.min(size.height, screen.height - 40);
        w.setMinimumSize(new java.awt.Dimension(Math.min(620, width), Math.min(540, height)));
        w.setSize(width, height);
        w.setLocationRelativeTo(null);
    }

    /** Width available for wrapped text in a window as wide as {@code owner} (or the standard one). */
    public static int textWidth(java.awt.Window owner, int margin) {
        int width = owner != null && owner.isShowing() ? owner.getWidth() : WINDOW_SIZE.width;
        return Math.max(300, width - margin);
    }

    public static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Palette.TEXT);
        return l;
    }

    public static JLabel bold(String text) {
        JLabel l = label(text);
        l.setFont(Theme.font(Font.BOLD, Theme.BASE_SIZE));
        return l;
    }

    public static JLabel secondary(String text) {
        JLabel l = label(text);
        l.setForeground(Palette.SECONDARY);
        l.setFont(Theme.font(Font.PLAIN, 12f));
        return l;
    }

    public static JLabel colored(String text, Color color, boolean bold) {
        JLabel l = label(text);
        l.setForeground(color);
        l.setFont(Theme.font(bold ? Font.BOLD : Font.PLAIN, Theme.BASE_SIZE));
        return l;
    }

    /** Monospaced, secondary colour (file names). */
    public static JLabel file(String text) {
        JLabel l = label(text);
        l.setForeground(Palette.SECONDARY);
        l.setFont(Theme.mono(12f));
        return l;
    }

    public static JLabel title(String text, float size) {
        JLabel l = label(text);
        l.setFont(Theme.font(Font.BOLD, size));
        return l;
    }

    /**
     * A label that wraps at {@code width} screen pixels. Swing's HTML renderer scales CSS {@code px}
     * by 1.3, so the CSS width is divided by that factor to wrap where asked.
     */
    public static JLabel wrap(String text, int width, Color color, Font font) {
        int cssWidth = Math.round(width / 1.3f);
        JLabel l = new JLabel("<html><div style='width:" + cssWidth + "px'>" + html(text) + "</div></html>");
        l.setForeground(color);
        l.setFont(font);
        return l;
    }

    public static String html(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }

    public static JButton button(String text, ActionListener action) {
        JButton b = new JButton(text);
        if (action != null) {
            b.addActionListener(action);
        }
        return b;
    }

    /** The blue primary button. */
    public static JButton primary(String text, ActionListener action) {
        JButton b = button(text, action);
        b.putClientProperty("primary", Boolean.TRUE);
        b.setForeground(Color.WHITE);
        b.setFont(Theme.font(Font.BOLD, Theme.BASE_SIZE));
        return b;
    }

    /** Centred buttons below a separator, as at the bottom of every window. */
    public static JPanel buttonBar(JComponent... buttons) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        row.setOpaque(false);
        for (JComponent b : buttons) {
            row.add(b);
        }
        JPanel bar = new JPanel(new BorderLayout(0, 10));
        bar.setOpaque(false);
        bar.add(new JSeparator(), BorderLayout.NORTH);
        bar.add(row, BorderLayout.CENTER);
        return bar;
    }

    public static JPanel stack(int gap, Component... children) {
        JPanel p = new JPanel(new VerticalLayout(gap));
        p.setOpaque(false);
        for (Component c : children) {
            p.add(c);
        }
        return p;
    }

    public static JPanel row(int gap, Component... children) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, gap, 0));
        p.setOpaque(false);
        for (Component c : children) {
            p.add(c);
        }
        return p;
    }

    public static JTextArea monoArea(int rows, int columns) {
        JTextArea a = new JTextArea(rows, columns);
        a.setEditable(false);
        a.setFont(Theme.mono());
        a.setForeground(Palette.TEXT);
        a.setBackground(Palette.WHITE);
        a.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        return a;
    }

    /**
     * A vertically scrolling page whose content always takes the viewport's width (no horizontal
     * scrolling: rows, cards and tables shrink to the window instead of being cut off).
     */
    public static JScrollPane page(JComponent content) {
        JPanel tracking = new TrackingPanel(content);
        JScrollPane s = new JScrollPane(tracking, javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        s.setBorder(null);
        s.getViewport().setBackground(Palette.WINDOW);
        s.getVerticalScrollBar().setUnitIncrement(16);
        return s;
    }

    private static final class TrackingPanel extends JPanel implements javax.swing.Scrollable {
        TrackingPanel(JComponent content) {
            super(new BorderLayout());
            setOpaque(false);
            add(content, BorderLayout.CENTER);
        }

        @Override
        public java.awt.Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle r, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle r, int orientation, int direction) {
            return Math.max(16, r.height - 32);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() != null && getParent().getHeight() > getPreferredSize().height;
        }
    }

    public static JScrollPane scroll(Component c) {
        JScrollPane s = new JScrollPane(c);
        s.setBorder(BorderFactory.createLineBorder(Palette.INPUT_BORDER));
        s.getVerticalScrollBar().setUnitIncrement(16);
        return s;
    }

    public static Border padding(int top, int left, int bottom, int right) {
        return BorderFactory.createEmptyBorder(top, left, bottom, right);
    }

    /** "16,54s" with the default locale's decimal separator, as in the mockups. */
    public static String seconds(long millis) {
        return String.format("%.2fs", millis / 1000.0);
    }
}
