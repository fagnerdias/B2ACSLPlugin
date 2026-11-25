package com.example.gui.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.DefaultCaret;

/** Raw output window (live Frama-C output, generation log); appends must happen on the EDT. */
public final class LogViewerDialog extends JDialog {

    private final JTextArea area = Ui.monoArea(28, 110);
    private final Path defaultFile;

    public LogViewerDialog(Window owner, String title, String initialText, Path defaultFile) {
        super(owner, title, ModalityType.MODELESS);
        this.defaultFile = defaultFile;
        area.setText(initialText == null ? "" : initialText);
        ((DefaultCaret) area.getCaret()).setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
        JScrollPane scroll = Ui.scroll(area);
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(Ui.padding(12, 12, 12, 12));
        root.add(scroll, BorderLayout.CENTER);
        root.add(Ui.buttonBar(Ui.button("Save Log (.txt)", e -> save()), Ui.button("Close", e -> dispose())),
                BorderLayout.SOUTH);
        setContentPane(root);
        setMinimumSize(new Dimension(520, 320));
        pack();
        setLocationRelativeTo(owner);
    }

    public void append(String line) {
        area.append(line);
        area.append("\n");
    }

    public static LogViewerDialog open(Window owner, String title, String text, Path defaultFile) {
        LogViewerDialog d = new LogViewerDialog(owner, title, text, defaultFile);
        SwingUtilities.invokeLater(() -> d.setVisible(true));
        return d;
    }

    private void save() {
        Path file = chooseSaveFile(this, defaultFile, "log.txt");
        if (file == null) {
            return;
        }
        try {
            Files.writeString(file, area.getText(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Could not save the log: " + e.getMessage(), "Save Log",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /** A "save as" chooser starting at {@code suggested}; asks before overwriting. */
    public static Path chooseSaveFile(java.awt.Component parent, Path suggested, String fallbackName) {
        JFileChooser chooser = new JFileChooser();
        if (suggested != null) {
            chooser.setCurrentDirectory(suggested.getParent() == null ? null : suggested.getParent().toFile());
            chooser.setSelectedFile(suggested.toFile());
        } else {
            chooser.setSelectedFile(new java.io.File(fallbackName));
        }
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        Path file = chooser.getSelectedFile().toPath();
        if (Files.exists(file)
                && JOptionPane.showConfirmDialog(parent, file.getFileName() + " already exists. Replace it?",
                        "Save", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return null;
        }
        return file;
    }
}
