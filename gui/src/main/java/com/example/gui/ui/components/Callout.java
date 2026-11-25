package com.example.gui.ui.components;

import com.example.gui.ui.Palette;
import com.example.gui.ui.Theme;
import com.example.gui.ui.VerticalLayout;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** Info (blue) or warning (yellow) box with an icon, an optional bold title, text and links. */
public final class Callout extends JPanel {

    public enum Kind {
        INFO,
        WARNING
    }

    private final JPanel body = new JPanel(new VerticalLayout(3));
    private final JLabel icon = new JLabel();
    private final int width;

    public Callout(Kind kind, String title, String text, int width) {
        super(new BorderLayout(8, 0));
        this.width = width;
        body.setOpaque(false);
        icon.setVerticalAlignment(JLabel.TOP);
        add(icon, BorderLayout.WEST);
        add(body, BorderLayout.CENTER);
        setKind(kind);
        set(title, text, List.of());
    }

    public void setKind(Kind kind) {
        boolean info = kind == Kind.INFO;
        setBackground(info ? Palette.CALLOUT_INFO_BG : Palette.CALLOUT_WARNING_BG);
        Color border = info ? Palette.CALLOUT_INFO_BORDER : Palette.CALLOUT_WARNING_BORDER;
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border), BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        icon.setIcon(info ? Icons.info() : Icons.warning());
        setOpaque(true);
    }

    /** Success style: green background and a check mark (comparison verdict when all match). */
    public void setSuccess() {
        setBackground(Palette.SUCCESS_BG);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xA5D6A7)), BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        icon.setIcon(Icons.check(Palette.GREEN));
    }

    /** Replaces the content; {@code lines} are extra wrapped lines (bullets) or components. */
    public void set(String title, String text, List<?> extra) {
        body.removeAll();
        if (title != null && !title.isEmpty()) {
            JLabel t = new JLabel(title);
            t.setFont(Theme.font(Font.BOLD, Theme.BASE_SIZE));
            t.setForeground(Palette.TEXT);
            body.add(t);
        }
        if (text != null && !text.isEmpty()) {
            body.add(com.example.gui.ui.Ui.wrap(text, width, Palette.TEXT, Theme.font(Font.PLAIN, 12.5f)));
        }
        for (Object o : extra) {
            if (o instanceof JComponent c) {
                body.add(c);
            } else {
                body.add(com.example.gui.ui.Ui.wrap("• " + o, width, Palette.TEXT, Theme.font(Font.PLAIN, 12.5f)));
            }
        }
        revalidate();
        repaint();
    }
}
