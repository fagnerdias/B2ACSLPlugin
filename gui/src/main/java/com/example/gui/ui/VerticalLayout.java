package com.example.gui.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import javax.swing.JComponent;

/**
 * Stacks the children top to bottom, each stretched to the container width at its preferred
 * height; children marked with {@link #fill(JComponent)} share the remaining height. When the
 * height is short, children marked with {@link #shrink(JComponent)} give up height first (down to
 * their minimum), then the fill children (down to theirs); the others keep their preferred height.
 */
public final class VerticalLayout implements LayoutManager {

    private static final String FILL = "VerticalLayout.fill";
    private static final String SHRINK = "VerticalLayout.shrink";
    private final int gap;

    public VerticalLayout(int gap) {
        this.gap = gap;
    }

    public static <T extends JComponent> T fill(T component) {
        component.putClientProperty(FILL, Boolean.TRUE);
        return component;
    }

    /** A child that gives up height first when the container is too short (e.g. a long list). */
    public static <T extends JComponent> T shrink(T component) {
        component.putClientProperty(SHRINK, Boolean.TRUE);
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
        java.util.List<Component> visible = new java.util.ArrayList<>();
        for (Component c : parent.getComponents()) {
            if (c.isVisible()) {
                visible.add(c);
            }
        }
        int[] heights = new int[visible.size()];
        int used = Math.max(0, visible.size() - 1) * gap;
        for (int i = 0; i < heights.length; i++) {
            heights[i] = visible.get(i).getPreferredSize().height;
            used += heights[i];
        }
        int extra = available - used;
        if (extra > 0) {
            int fills = (int) visible.stream().filter(VerticalLayout::isFill).count();
            for (int i = 0; fills > 0 && i < heights.length; i++) {
                if (isFill(visible.get(i))) {
                    heights[i] += extra / fills;
                }
            }
        } else if (extra < 0) {
            int deficit = take(visible, heights, -extra, true);
            take(visible, heights, deficit, false);
        }
        int y = in.top;
        for (int i = 0; i < heights.length; i++) {
            visible.get(i).setBounds(in.left, y, width, heights[i]);
            y += heights[i] + gap;
        }
    }

    /**
     * Takes up to {@code deficit} pixels from the shrinkable children ({@code shrinkable}) or from
     * the fill-only children, in proportion to how far each is above its minimum height.
     *
     * @return the part of the deficit that could not be taken
     */
    private static int take(java.util.List<Component> visible, int[] heights, int deficit, boolean shrinkable) {
        if (deficit <= 0) {
            return 0;
        }
        int[] room = new int[heights.length];
        int total = 0;
        for (int i = 0; i < heights.length; i++) {
            Component c = visible.get(i);
            boolean matches = shrinkable ? isShrink(c) : isFill(c) && !isShrink(c);
            if (matches) {
                room[i] = Math.max(0, heights[i] - c.getMinimumSize().height);
                total += room[i];
            }
        }
        if (total == 0) {
            return deficit;
        }
        int wanted = Math.min(deficit, total);
        int taken = 0;
        for (int i = 0; i < heights.length; i++) {
            if (room[i] > 0) {
                int t = Math.min(room[i], (int) Math.ceil((double) wanted * room[i] / total));
                t = Math.min(t, wanted - taken);
                heights[i] -= t;
                taken += t;
            }
        }
        return deficit - taken;
    }

    private static boolean isShrink(Component c) {
        return c instanceof JComponent jc && Boolean.TRUE.equals(jc.getClientProperty(SHRINK));
    }

    private static boolean isFill(Component c) {
        return c instanceof JComponent jc && Boolean.TRUE.equals(jc.getClientProperty(FILL));
    }
}
