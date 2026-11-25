package com.example.gui.ui;

import com.example.gui.model.ErrorReport;
import com.example.gui.model.ErrorReport.PipelineStep;
import com.example.gui.model.Generator;
import com.example.gui.ui.components.Callout;
import com.example.gui.ui.components.Icons;
import com.example.gui.ui.components.TitledGroup;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/**
 * Modal error dialog (mockup E): what failed, where, the details, the output and how to fix it.
 * The same layout serves every error, only the texts change.
 */
public final class ErrorDialog extends JDialog {

    private static final int TEXT_WIDTH = 430;

    /**
     * @param onRetry "Try Again" ({@code null} hides it)
     * @param onContinue "Continue with &lt;generator&gt; Only" (shown when the report names one)
     * @param logs text of "View Logs" ({@code null} hides it)
     */
    public record Actions(Runnable onRetry, Consumer<Generator> onContinue, Supplier<String> logs) {}

    private ErrorDialog(Window owner, ErrorReport report, Actions actions) {
        super(owner, report.windowTitle(), ModalityType.APPLICATION_MODAL);
        JPanel content = Ui.stack(12);

        JPanel head = new JPanel(new BorderLayout(12, 0));
        head.setOpaque(false);
        JLabel icon = new JLabel(Icons.error(32));
        icon.setVerticalAlignment(JLabel.TOP);
        head.add(icon, BorderLayout.WEST);
        head.add(Ui.stack(4, Ui.title(report.title(), 17f),
                Ui.wrap(report.sentence(), TEXT_WIDTH - 40, Palette.TEXT, Theme.font(Font.PLAIN, 12.5f))),
                BorderLayout.CENTER);
        content.add(head);

        JPanel steps = Ui.stack(5);
        for (PipelineStep step : report.steps()) {
            JLabel l = new JLabel(step.label());
            l.setIconTextGap(8);
            switch (step.state()) {
                case DONE -> {
                    l.setIcon(Icons.check(Palette.GREEN));
                    l.setForeground(Palette.TEXT);
                }
                case FAILED -> {
                    l.setIcon(Icons.cross(Palette.RED));
                    l.setForeground(Palette.RED);
                    l.setFont(Theme.font(Font.BOLD, Theme.BASE_SIZE));
                }
                case NOT_STARTED -> {
                    l.setIcon(Icons.dash(Palette.GRAY));
                    l.setForeground(Palette.GRAY);
                }
            }
            steps.add(l);
        }
        content.add(new TitledGroup("Where it failed", steps, new java.awt.Color(0xF7F7F7)));

        JPanel details = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 0, 2, 24);
        int row = 0;
        for (var e : report.details().entrySet()) {
            c.gridx = 0;
            c.gridy = row;
            c.weightx = 0;
            details.add(Ui.secondary(e.getKey()), c);
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            JLabel v = Ui.bold(e.getValue());
            v.setToolTipText(e.getValue());
            details.add(v, c);
            c.fill = GridBagConstraints.NONE;
            row++;
        }
        content.add(new TitledGroup("Error details", details, new java.awt.Color(0xF7F7F7)));

        if (report.output() != null && !report.output().isBlank()) {
            JTextArea out = Ui.monoArea(6, 60);
            out.setText(report.output());
            out.setLineWrap(true);
            out.setCaretPosition(0);
            out.setBackground(Palette.ERROR_BG);
            out.setForeground(new java.awt.Color(0x8B1A1A));
            JScrollPane sp = Ui.scroll(out);
            sp.setBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(0xF2B8B8)));
            sp.getViewport().setBackground(Palette.ERROR_BG);
            content.add(Ui.stack(6, Ui.bold(report.title().startsWith("Specification") ? "Generator output" : "Output"),
                    VerticalLayout.fill(sp)));
        }
        if (!report.howToFix().isEmpty()) {
            Callout fix = new Callout(Callout.Kind.INFO, "How to fix", null, TEXT_WIDTH - 30);
            fix.set("How to fix", null, report.howToFix());
            content.add(fix);
        }

        List<JComponent> buttons = new ArrayList<>();
        buttons.add(Ui.button("Copy Details", e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(report.asText()), null)));
        if (actions.logs() != null) {
            buttons.add(Ui.button("View Logs", e -> LogViewerDialog.open(this, "Logs", actions.logs().get(), null)));
        }
        buttons.add(Ui.button("Close", e -> dispose()));
        if (report.canRetry() && actions.onRetry() != null) {
            buttons.add(Ui.button("Try Again", e -> {
                dispose();
                actions.onRetry().run();
            }));
        }
        if (report.continueWith() != null && actions.onContinue() != null) {
            JButton cont = Ui.primary("Continue with " + report.continueWith().label() + " Only", e -> {
                dispose();
                actions.onContinue().accept(report.continueWith());
            });
            buttons.add(cont);
            getRootPane().setDefaultButton(cont);
        }

        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBorder(Ui.padding(16, 16, 14, 16));
        root.add(content, BorderLayout.CENTER);
        root.add(Ui.buttonBar(buttons.toArray(JComponent[]::new)), BorderLayout.SOUTH);
        setContentPane(root);
        pack();
        setMinimumSize(getPreferredSize());
        setLocationRelativeTo(owner);
    }

    public static void show(Window owner, ErrorReport report, Actions actions) {
        create(owner, report, actions).setVisible(true);
    }

    /** The dialog, laid out but not shown. */
    public static ErrorDialog create(Window owner, ErrorReport report, Actions actions) {
        return new ErrorDialog(owner, report, actions);
    }
}
