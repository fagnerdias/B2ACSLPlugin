package com.example.gui.ui.components;

import com.example.gui.model.FunctionStatus;
import com.example.gui.ui.Palette;
import com.example.gui.ui.Theme;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** One function in the progress window: icon, "function (file)" and its status. */
public final class OperationRow extends JPanel {

    private final JLabel name = new JLabel();
    private final JLabel status = new JLabel();

    public OperationRow(String text) {
        super(new BorderLayout(10, 0));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(3, 2, 3, 2));
        name.setText(text);
        name.setFont(Theme.font(Font.PLAIN, Theme.BASE_SIZE));
        name.setIconTextGap(8);
        status.setFont(Theme.font(Font.BOLD, 12.5f));
        status.setHorizontalAlignment(JLabel.RIGHT);
        add(name, BorderLayout.CENTER);
        add(status, BorderLayout.EAST);
        setStatus(FunctionStatus.PENDING, null);
    }

    /** @param detail optional text after the status (e.g. "16 / 18") */
    public void setStatus(FunctionStatus s, String detail) {
        Color color = switch (s) {
            case PENDING -> Palette.GRAY;
            case RUNNING -> Palette.BLUE;
            case PROVED -> Palette.GREEN;
            case FAILED -> Palette.RED;
        };
        name.setIcon(switch (s) {
            case PENDING -> Icons.circle(Palette.GRAY);
            case RUNNING -> Icons.play(Palette.BLUE);
            case PROVED -> Icons.check(Palette.GREEN);
            case FAILED -> Icons.cross(Palette.RED);
        });
        name.setForeground(s == FunctionStatus.PENDING ? Palette.SECONDARY : Palette.TEXT);
        status.setForeground(color);
        status.setFont(Theme.font(s == FunctionStatus.PENDING ? Font.PLAIN : Font.BOLD, 12.5f));
        status.setText(detail == null || detail.isEmpty() ? s.label() : s.label() + "  (" + detail + ")");
    }
}
