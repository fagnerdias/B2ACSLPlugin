package com.example.gui.ui.components;

import com.example.gui.ui.Palette;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import javax.swing.Icon;

/** Small vector icons (independent of the glyphs the UI font has). */
public final class Icons {

    public enum Shape {
        CHECK,
        CROSS,
        PLAY,
        CIRCLE,
        HALF,
        CLOCK,
        DASH,
        INFO,
        WARNING,
        ERROR
    }

    private Icons() {}

    public static Icon check(Color c) {
        return new VectorIcon(Shape.CHECK, c, 14);
    }

    public static Icon cross(Color c) {
        return new VectorIcon(Shape.CROSS, c, 14);
    }

    public static Icon play(Color c) {
        return new VectorIcon(Shape.PLAY, c, 14);
    }

    public static Icon circle(Color c) {
        return new VectorIcon(Shape.CIRCLE, c, 14);
    }

    /** Half-filled circle (status Partial). */
    public static Icon half(Color c) {
        return new VectorIcon(Shape.HALF, c, 14);
    }

    /** Stopwatch (status Timeout). */
    public static Icon clock(Color c) {
        return new VectorIcon(Shape.CLOCK, c, 14);
    }

    /** The icon of a function status (○ ▶ ✓ ◐ ⏱ ✗), in the status colour. */
    public static Icon status(com.example.gui.model.FunctionStatus s) {
        return switch (s) {
            case PENDING -> circle(s.color());
            case RUNNING -> play(s.color());
            case PROVED -> check(s.color());
            case PARTIAL -> half(s.color());
            case TIMEOUT -> clock(s.color());
            case FAILED -> cross(s.color());
        };
    }

    public static Icon dash(Color c) {
        return new VectorIcon(Shape.DASH, c, 14);
    }

    public static Icon info() {
        return new VectorIcon(Shape.INFO, Palette.BLUE, 16);
    }

    public static Icon warning() {
        return new VectorIcon(Shape.WARNING, new Color(0xE0A100), 18);
    }

    public static Icon error(int size) {
        return new VectorIcon(Shape.ERROR, Palette.RED, size);
    }

    public static Icon square(Color fill, Color border) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                g.setColor(fill);
                g.fillRect(x, y + 1, 11, 11);
                g.setColor(border);
                g.drawRect(x, y + 1, 11, 11);
            }

            @Override
            public int getIconWidth() {
                return 12;
            }

            @Override
            public int getIconHeight() {
                return 13;
            }
        };
    }

    private record VectorIcon(Shape shape, Color color, int size) implements Icon {

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.translate(x, y);
            float s = size;
            g2.setColor(color);
            switch (shape) {
                case CHECK -> {
                    g2.setStroke(new BasicStroke(s / 7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    Path2D p = new Path2D.Float();
                    p.moveTo(s * 0.18, s * 0.55);
                    p.lineTo(s * 0.42, s * 0.78);
                    p.lineTo(s * 0.84, s * 0.24);
                    g2.draw(p);
                }
                case CROSS -> {
                    g2.setStroke(new BasicStroke(s / 7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawLine(Math.round(s * 0.24f), Math.round(s * 0.24f), Math.round(s * 0.76f), Math.round(s * 0.76f));
                    g2.drawLine(Math.round(s * 0.76f), Math.round(s * 0.24f), Math.round(s * 0.24f), Math.round(s * 0.76f));
                }
                case PLAY -> {
                    Path2D p = new Path2D.Float();
                    p.moveTo(s * 0.25, s * 0.18);
                    p.lineTo(s * 0.82, s * 0.5);
                    p.lineTo(s * 0.25, s * 0.82);
                    p.closePath();
                    g2.fill(p);
                }
                case CIRCLE -> {
                    g2.setStroke(new BasicStroke(1.3f));
                    g2.drawOval(Math.round(s * 0.18f), Math.round(s * 0.18f), Math.round(s * 0.64f), Math.round(s * 0.64f));
                }
                case HALF -> {
                    int d = Math.round(s * 0.66f);
                    int o = Math.round(s * 0.17f);
                    g2.setStroke(new BasicStroke(1.3f));
                    g2.drawOval(o, o, d, d);
                    g2.fillArc(o, o, d, d, 90, 180);
                }
                case CLOCK -> {
                    int d = Math.round(s * 0.66f);
                    int o = Math.round(s * 0.22f);
                    g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawOval(Math.round(s * 0.17f), o, d, d);
                    int cx = Math.round(s * 0.17f) + d / 2;
                    int cy = o + d / 2;
                    g2.drawLine(cx, cy, cx, cy - Math.round(d * 0.32f));
                    g2.drawLine(cx, cy, cx + Math.round(d * 0.26f), cy);
                    g2.drawLine(cx - 2, Math.round(s * 0.08f), cx + 2, Math.round(s * 0.08f));
                }
                case DASH -> {
                    g2.setStroke(new BasicStroke(s / 8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawLine(Math.round(s * 0.28f), Math.round(s * 0.5f), Math.round(s * 0.72f), Math.round(s * 0.5f));
                }
                case INFO, ERROR -> {
                    g2.fillOval(0, 0, size - 1, size - 1);
                    letter(g2, shape == Shape.INFO ? "i" : "!", s, 0f);
                }
                case WARNING -> {
                    Path2D p = new Path2D.Float();
                    p.moveTo(s * 0.5, s * 0.06);
                    p.lineTo(s * 0.97, s * 0.9);
                    p.lineTo(s * 0.03, s * 0.9);
                    p.closePath();
                    g2.fill(p);
                    letter(g2, "!", s * 0.95f, s * 0.08f);
                }
            }
            g2.dispose();
        }

        private static void letter(Graphics2D g2, String text, float s, float dy) {
            g2.setColor(Color.WHITE);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.round(s * 0.72f)));
            FontMetrics fm = g2.getFontMetrics();
            int tx = Math.round((s - fm.stringWidth(text)) / 2f);
            int ty = Math.round((s - fm.getHeight()) / 2f + fm.getAscent() + dy);
            g2.drawString(text, tx, ty);
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }
}
