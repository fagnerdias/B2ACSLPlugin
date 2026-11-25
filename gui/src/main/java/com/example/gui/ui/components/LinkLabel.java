package com.example.gui.ui.components;

import com.example.gui.ui.Palette;
import com.example.gui.ui.Theme;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.util.Map;
import javax.swing.JLabel;

/** Blue clickable text ("View error details ›", "Compare ›"). */
public final class LinkLabel extends JLabel {

    private Runnable action;

    public LinkLabel(String text, Runnable action) {
        this(text, action, true);
    }

    public LinkLabel(String text, Runnable action, boolean bold) {
        super(text);
        this.action = action;
        Font base = Theme.font(bold ? Font.BOLD : Font.PLAIN, 12.5f);
        setFont(base);
        setForeground(Palette.BLUE);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Font underlined = base.deriveFont(Map.of(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (LinkLabel.this.action != null && isEnabled()) {
                    LinkLabel.this.action.run();
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                setFont(underlined);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                setFont(base);
            }
        });
    }

    public void setAction(Runnable action) {
        this.action = action;
    }
}
