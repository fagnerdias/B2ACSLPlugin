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

    private Ui() {}

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

    /** A label that wraps at {@code width} pixels. */
    public static JLabel wrap(String text, int width, Color color, Font font) {
        JLabel l = new JLabel("<html><div style='width:" + width + "px'>" + html(text) + "</div></html>");
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
