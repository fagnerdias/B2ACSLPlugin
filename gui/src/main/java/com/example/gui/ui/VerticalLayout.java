package com.example.gui.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import javax.swing.JComponent;

/**
 * Stacks the children top to bottom, each stretched to the container width at its preferred
 * height; children marked with {@link #fill(JComponent)} share the remaining height.
 */
public final class VerticalLayout implements LayoutManager {

    private static final String FILL = "VerticalLayout.fill";
    private final int gap;

    public VerticalLayout(int gap) {
        this.gap = gap;
    }

    public static <T extends JComponent> T fill(T component) {
        component.putClientProperty(FILL, Boolean.TRUE);
        return component;
    }

    /** Turns the "share the remaining height" behaviour of a child on or off. */
    public static void setFill(JComponent component, boolean fill) {
        component.putClientProperty(FILL, fill ? Boolean.TRUE : null);
    }

    @Override
    public void addLayoutComponent(String name, Component comp) {}

    @Override
    public void removeLayoutComponent(Component comp) {}

    @Override
    public Dimension preferredLayoutSize(Container parent) {
        return size(parent, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
        return size(parent, false);
    }

    private Dimension size(Container parent, boolean preferred) {
        Insets in = parent.getInsets();
        int w = 0;
        int h = 0;
        int n = 0;
        for (Component c : parent.getComponents()) {
            if (!c.isVisible()) {
                continue;
            }
            Dimension d = preferred ? c.getPreferredSize() : c.getMinimumSize();
            w = Math.max(w, d.width);
            h += d.height;
            n++;
        }
        h += Math.max(0, n - 1) * gap;
        return new Dimension(w + in.left + in.right, h + in.top + in.bottom);
    }

    @Override
    public void layoutContainer(Container parent) {
        Insets in = parent.getInsets();
        int width = parent.getWidth() - in.left - in.right;
        int available = parent.getHeight() - in.top - in.bottom;
        int used = 0;
        int fills = 0;
        int n = 0;
        for (Component c : parent.getComponents()) {
            if (!c.isVisible()) {
                continue;
            }
            used += c.getPreferredSize().height;
            n++;
            if (isFill(c)) {
                fills++;
            }
        }
        used += Math.max(0, n - 1) * gap;
        int extra = fills == 0 ? 0 : (available - used) / fills;
        int y = in.top;
        for (Component c : parent.getComponents()) {
            if (!c.isVisible()) {
                continue;
            }
            int h = c.getPreferredSize().height;
            if (isFill(c)) {
                h = Math.max(c.getMinimumSize().height, h + extra);
            }
            c.setBounds(in.left, y, width, h);
            y += h + gap;
        }
    }

    private static boolean isFill(Component c) {
        return c instanceof JComponent jc && Boolean.TRUE.equals(jc.getClientProperty(FILL));
    }
}
