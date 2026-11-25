package com.example.gui.ui.wizard;

import com.example.gui.model.ProjectInfo;
import com.example.gui.parsing.ProjectScanner;
import com.example.gui.parsing.ProjectScanner.ProjectScan;
import com.example.gui.ui.AppPreferences;
import com.example.gui.ui.ErrorDialog;
import com.example.gui.ui.ErrorReports;
import com.example.gui.ui.Palette;
import com.example.gui.ui.Theme;
import com.example.gui.ui.Ui;
import com.example.gui.ui.VerticalLayout;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Step 1: project folder, name and the functions to verify. */
final class ProjectStep extends WizardStep {

    private final AppPreferences prefs;
    private final JTextField folder = new JTextField(24);
    private final JTextField name = new JTextField(24);
    private final JLabel problem = new JLabel(" ");
    private final JPanel functionList = new JPanel(new VerticalLayout(2));
    private final JLabel counter = Ui.secondary("0 of 0 selected");
    private final List<JCheckBox> boxes = new ArrayList<>();
    private final JButton nextButton;
    private final Timer rescan = new Timer(400, e -> scan(false));
    private ProjectScan scan;
    private int scanGeneration;
    /** The folder text changed and its scan has not finished: Next must wait for it. */
    private boolean scanPending;

    ProjectStep(WizardFrame frame, WizardState state, AppPreferences prefs) {
        super(frame, state, 0, "Select the project to verify");
        this.prefs = prefs;
        rescan.setRepeats(false);

        JPanel folderRow = new JPanel(new BorderLayout(8, 0));
        folderRow.setOpaque(false);
        folderRow.add(folder, BorderLayout.CENTER);
        folderRow.add(Ui.button("Browse...", e -> browse()), BorderLayout.EAST);
        problem.setForeground(Palette.RED);
        problem.setFont(Theme.font(Font.PLAIN, 11.5f));

        functionList.setBackground(Palette.WHITE);
        functionList.setOpaque(true);
        functionList.setBorder(Ui.padding(4, 6, 4, 6));
        JScrollPane listScroll = Ui.scroll(functionList);
        listScroll.setPreferredSize(new Dimension(300, 96));

        JPanel content = Ui.stack(5,
                Ui.bold("Project folder"), folderRow, problem,
                Ui.bold("Project name"), name,
                Ui.stack(0, javax.swing.Box.createVerticalStrut(4)),
                Ui.bold("Functions / operations"), VerticalLayout.fill(listScroll), counter);
        setContent(content);
        nextButton = Ui.button("Next", e -> next());
        setButtons(Ui.button("Cancel", e -> frame.requestClose()), nextButton);

        folder.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                folderEdited();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                folderEdited();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                folderEdited();
            }
        });
        name.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateNext();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateNext();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateNext();
            }
        });
        String last = prefs.lastFolder();
        if (!last.isBlank()) {
            folder.setText(last);
            rescan.stop();
            scan(false);
        }
        updateNext();
    }

    private void folderEdited() {
        scanPending = true;
        updateNext();
        rescan.restart();
    }

    private void browse() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select the project folder");
        String current = folder.getText().trim();
        File start = current.isEmpty() ? null : new File(current);
        if (start != null && start.isDirectory()) {
            chooser.setCurrentDirectory(start.getParentFile());
            chooser.setSelectedFile(start);
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            folder.setText(chooser.getSelectedFile().getAbsolutePath());
            rescan.stop();
            scan(true);
        }
    }

    /** Scans the folder off the EDT; {@code report} opens the error dialog when it is not a project. */
    private void scan(boolean report) {
        String text = folder.getText().trim();
        int generation = ++scanGeneration;
        if (text.isEmpty()) {
            apply(null, false);
            return;
        }
        Path path = Path.of(text);
        new SwingWorker<ProjectScan, Void>() {
            @Override
            protected ProjectScan doInBackground() {
                return ProjectScanner.scan(path);
            }

            @Override
            protected void done() {
                if (generation != scanGeneration) {
                    return;
                }
                try {
                    apply(get(), report);
                } catch (InterruptedException | ExecutionException e) {
                    apply(new ProjectScan(path, String.valueOf(e.getCause()), List.of()), report);
                }
            }
        }.execute();
    }

    private void apply(ProjectScan result, boolean report) {
        scanPending = false;
        Path previous = scan == null ? null : scan.folder();
        scan = result;
        boxes.clear();
        functionList.removeAll();
        if (result == null) {
            problem.setText(" ");
        } else if (!result.valid()) {
            problem.setText(result.problem());
            if (report) {
                ErrorDialog.show(frame, ErrorReports.invalidProject(result.folder(), result.problem()),
                        new ErrorDialog.Actions(null, null, null));
            }
        } else {
            problem.setText(" ");
            List<String> keep = state.project != null && result.folder().equals(state.project.folder())
                    ? state.project.functions() : null;
            for (String fn : result.functions()) {
                JCheckBox box = new JCheckBox(fn, keep == null || keep.contains(fn));
                box.setBackground(Palette.WHITE);
                box.setOpaque(false);
                box.addActionListener(e -> updateNext());
                boxes.add(box);
                functionList.add(box);
            }
            if (!Objects.equals(previous, result.folder()) || name.getText().isBlank()) {
                name.setText(state.project != null && result.folder().equals(state.project.folder())
                        ? state.project.name()
                        : result.folder().getFileName().toString());
            }
        }
        functionList.revalidate();
        functionList.repaint();
        updateNext();
    }

    private List<String> selected() {
        return boxes.stream().filter(JCheckBox::isSelected).map(JCheckBox::getText).toList();
    }

    private void updateNext() {
        int n = boxes.size();
        int s = selected().size();
        counter.setText(s + " of " + n + " selected");
        nextButton.setEnabled(!scanPending && scan != null && scan.valid() && s > 0 && !name.getText().isBlank());
    }

    private void next() {
        ProjectInfo project = new ProjectInfo(scan.folder(), name.getText().trim(), selected());
        if (state.project == null || !state.project.folder().equals(project.folder())) {
            state.invalidateGeneration();
        }
        state.project = project;
        prefs.setLastFolder(project.folder().toString());
        frame.next();
    }
}
