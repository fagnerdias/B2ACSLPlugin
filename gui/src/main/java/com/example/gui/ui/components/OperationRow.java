package com.example.gui.ui.components;

import com.example.gui.model.FunctionStatus;
import com.example.gui.ui.Palette;
import com.example.gui.ui.Theme;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * One function in the progress window, laid out as in the pipeline's progress dialog: status icon,
 * "function (file)", the goals ("30 goals" while running, "27 / 30 goals" when done) and the status.
 */
public final class OperationRow extends JPanel {

    private final JLabel name = new JLabel();
    private final JLabel goals = new JLabel("", JLabel.RIGHT);
    private final JLabel status = new JLabel("", JLabel.RIGHT);

    public OperationRow(String text) {
        super(new BorderLayout(10, 0));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(3, 2, 3, 6));
        name.setText(text);
        name.setFont(Theme.font(Font.PLAIN, Theme.BASE_SIZE));
        name.setIconTextGap(8);
        goals.setFont(Theme.font(Font.PLAIN, 11.5f));
        goals.setForeground(Palette.TEXT);
        goals.setPreferredSize(new Dimension(110, 18));
        status.setPreferredSize(new Dimension(82, 18));
        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.X_AXIS));
        right.add(goals);
        right.add(Box.createHorizontalStrut(6));
        right.add(status);
        add(name, BorderLayout.CENTER);
        add(right, BorderLayout.EAST);
        setStatus(FunctionStatus.PENDING, null);
    }

    /** @param goalsText e.g. "30 goals", "27 / 30 goals", "no goals"; {@code null} for none */
    public void setStatus(FunctionStatus s, String goalsText) {
        name.setIcon(Icons.status(s));
        name.setForeground(s == FunctionStatus.PENDING ? Palette.SECONDARY : Palette.TEXT);
        status.setForeground(s.color());
        status.setFont(Theme.font(s == FunctionStatus.PENDING ? Font.PLAIN : Font.BOLD, 12.5f));
        status.setText(s.label());
        goals.setText(goalsText == null ? "" : goalsText);
    }

    /** Updates only the goals text (the "[wp] N goals scheduled" line of a running function). */
    public void setGoals(String goalsText) {
        goals.setText(goalsText == null ? "" : goalsText);
    }
}
