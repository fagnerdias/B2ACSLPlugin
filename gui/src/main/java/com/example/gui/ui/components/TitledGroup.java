package com.example.gui.ui.components;

import com.example.gui.ui.Palette;
import com.example.gui.ui.Theme;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** Bordered box (#B8CFE5) with a title line and optional components beside the title. */
public final class TitledGroup extends JPanel {

    private final JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private final JLabel title = new JLabel();

    public TitledGroup(String title, JComponent content) {
        this(title, content, null);
    }

    public TitledGroup(String title, JComponent content, Color background) {
        super(new BorderLayout(0, 8));
        setOpaque(background != null);
        if (background != null) {
            setBackground(background);
        }
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Palette.GROUP_BORDER), BorderFactory.createEmptyBorder(8, 10, 10, 10)));
        header.setOpaque(false);
        ((FlowLayout) header.getLayout()).setHgap(0);
        this.title.setText(title);
        this.title.setFont(Theme.font(Font.PLAIN, 12.5f));
        this.title.setForeground(Palette.TEXT);
        header.add(this.title);
        if (title != null && !title.isEmpty()) {
            add(header, BorderLayout.NORTH);
        }
        content.setOpaque(false);
        add(content, BorderLayout.CENTER);
    }

    /** Bold title (e.g. "Plugin" in the summary columns). */
    public TitledGroup boldTitle() {
        title.setFont(Theme.font(Font.BOLD, 13f));
        return this;
    }

    /** Adds a component (badge) right after the title. */
    public TitledGroup withHeader(JComponent component) {
        header.add(javax.swing.Box.createHorizontalStrut(10));
        header.add(component);
        return this;
    }

    public void setTitle(String text) {
        title.setText(text);
    }
}
