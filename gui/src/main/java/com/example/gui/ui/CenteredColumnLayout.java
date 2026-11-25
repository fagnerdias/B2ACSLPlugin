package com.example.gui.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;

/** Lays out a single child at full height, at most {@code maxWidth} wide, centred horizontally. */
public final class CenteredColumnLayout implements LayoutManager {

    private final int maxWidth;

    public CenteredColumnLayout(int maxWidth) {
        this.maxWidth = maxWidth;
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
        if (parent.getComponentCount() == 0) {
            return new Dimension(in.left + in.right, in.top + in.bottom);
        }
        Component c = parent.getComponent(0);
        Dimension d = preferred ? c.getPreferredSize() : c.getMinimumSize();
        return new Dimension(Math.min(d.width, maxWidth) + in.left + in.right, d.height + in.top + in.bottom);
    }

    @Override
    public void layoutContainer(Container parent) {
        if (parent.getComponentCount() == 0) {
            return;
        }
        Insets in = parent.getInsets();
        int available = parent.getWidth() - in.left - in.right;
        int width = Math.min(available, maxWidth);
        int x = in.left + (available - width) / 2;
        parent.getComponent(0).setBounds(x, in.top, width, parent.getHeight() - in.top - in.bottom);
    }
}
