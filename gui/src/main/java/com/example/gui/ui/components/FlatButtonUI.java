package com.example.gui.ui.components;

import com.example.gui.ui.Palette;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import javax.swing.AbstractButton;
import javax.swing.ButtonModel;
import javax.swing.JComponent;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicButtonUI;

/**
 * Rounded light buttons with a blue-grey border; a button with the client property {@code
 * primary=true} is painted blue with white text.
 */
public final class FlatButtonUI extends BasicButtonUI {

    private static final Color BORDER = new Color(0x8FA3B8);
    private static final Color DISABLED_BORDER = new Color(0xC6CED6);
    private static final Color DISABLED_TEXT = new Color(0x9A9A9A);
    private static final Color PRIMARY_PRESSED = new Color(0x1F4DA3);
    private static final Color PRIMARY_DISABLED = new Color(0x9DB6E3);

    public static ComponentUI createUI(JComponent c) {
        return new FlatButtonUI();
    }

    @Override
    public void installDefaults(AbstractButton b) {
        super.installDefaults(b);
        b.setOpaque(false);
        b.setContentAreaFilled(false);
        b.setBorder(new EmptyBorder(5, 14, 5, 14));
        b.setRolloverEnabled(true);
        b.setFocusPainted(false);
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        AbstractButton b = (AbstractButton) c;
        ButtonModel m = b.getModel();
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = c.getWidth() - 1;
        int h = c.getHeight() - 1;
        boolean primary = Boolean.TRUE.equals(b.getClientProperty("primary"));
        if (primary) {
            g2.setColor(!m.isEnabled() ? PRIMARY_DISABLED : m.isPressed() ? PRIMARY_PRESSED : Palette.BLUE);
            g2.fillRoundRect(0, 0, w, h, 6, 6);
            g2.setColor(m.isEnabled() ? PRIMARY_PRESSED : PRIMARY_DISABLED);
            g2.drawRoundRect(0, 0, w, h, 6, 6);
        } else {
            Color top = !m.isEnabled() ? new Color(0xF4F4F4) : m.isPressed() ? new Color(0xDCE3EA) : new Color(0xFCFCFC);
            Color bottom = !m.isEnabled() ? new Color(0xEEEEEE) : m.isPressed() ? new Color(0xD2DAE2) : new Color(0xE9ECEF);
            g2.setPaint(new GradientPaint(0, 0, top, 0, h, bottom));
            g2.fillRoundRect(0, 0, w, h, 6, 6);
            g2.setColor(!m.isEnabled() ? DISABLED_BORDER : m.isRollover() || b.hasFocus() ? Palette.BLUE : BORDER);
            g2.drawRoundRect(0, 0, w, h, 6, 6);
        }
        g2.dispose();
        super.paint(g, c);
    }

    @Override
    protected void paintText(Graphics g, AbstractButton b, Rectangle textRect, String text) {
        boolean primary = Boolean.TRUE.equals(b.getClientProperty("primary"));
        g.setFont(b.getFont());
        if (b.getModel().isEnabled()) {
            g.setColor(primary ? Color.WHITE : b.getForeground());
        } else {
            g.setColor(primary ? new Color(0xF0F4FB) : DISABLED_TEXT);
        }
        ((Graphics2D) g).setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.drawString(text, textRect.x, textRect.y + g.getFontMetrics().getAscent());
    }
}
