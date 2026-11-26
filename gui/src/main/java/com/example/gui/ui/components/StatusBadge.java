package com.example.gui.ui.components;

import com.example.gui.ui.Palette;
import com.example.gui.ui.Theme;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.JLabel;

/** Small rounded badge ("Ready", "Failed", "3 / 3 done", "Same result"). */
public final class StatusBadge extends JLabel {

    public enum Kind {
        SUCCESS(Palette.SUCCESS_BG, Palette.SUCCESS_FG),
        WARNING(Palette.WARNING_BG, Palette.WARNING_FG),
        ERROR(Palette.ERROR_BG, Palette.ERROR_FG),
        /** Yellow: timeouts without failures. */
        TIMEOUT(Palette.TIMEOUT_BG, Palette.TIMEOUT_FG),
        INFO(Palette.INFO_BG, Palette.INFO_FG);

        final Color background;
        final Color foreground;

        Kind(Color background, Color foreground) {
            this.background = background;
            this.foreground = foreground;
        }
    }

    private Kind kind;

    public StatusBadge(String text, Kind kind) {
        super(text);
        setFont(Theme.font(Font.BOLD, 11f));
        setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        setOpaque(false);
        setKind(kind);
    }

    public void setKind(Kind kind) {
        this.kind = kind;
        setForeground(kind.foreground);
        repaint();
    }

    public void set(String text, Kind kind) {
        setText(text);
        setKind(kind);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(kind.background);
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
        g2.dispose();
        super.paintComponent(g);
    }
}
