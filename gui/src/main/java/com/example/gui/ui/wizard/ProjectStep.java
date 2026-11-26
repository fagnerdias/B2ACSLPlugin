package com.example.gui.ui.wizard;

import com.example.gui.model.ProjectInfo;
import com.example.gui.parsing.ProjectScanner;
import com.example.gui.parsing.ProjectScanner.ProjectScan;
import com.example.gui.ui.ErrorDialog;
import com.example.gui.ui.ErrorReports;
import com.example.gui.ui.Palette;
import com.example.gui.ui.Theme;
import com.example.gui.ui.Ui;
import com.example.gui.ui.VerticalLayout;
import java.awt.Font;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/**
 * Step 1: the project to verify, as the pipeline receives it: the folder comes from the command line
 * (the project root or its {@code bdp/}, as Atelier B passes it), and every function the project
 * defines is verified. The step only shows the folder, the name and the functions.
 */
final class ProjectStep extends WizardStep {

    private final JTextField folder = new JTextField(24);
    private final JTextField name = new JTextField(24);
    private final JLabel problem = new JLabel(" ");
    private final JPanel functionList = new JPanel(new VerticalLayout(4));
    private final JLabel counter = Ui.secondary(" ");
    private final JButton nextButton;
    private ProjectScan scan;

    ProjectStep(WizardFrame frame, WizardState state, Path project) {
        super(frame, state, 0, "Project to verify");

        folder.setEditable(false);
        name.setEditable(false);
        folder.setText(project == null ? "" : project.toAbsolutePath().normalize().toString());
        problem.setForeground(Palette.RED);
        problem.setFont(Theme.font(Font.PLAIN, 11.5f));

        functionList.setBackground(Palette.WHITE);
        functionList.setOpaque(true);
        functionList.setBorder(Ui.padding(6, 10, 6, 10));
        JScrollPane listScroll = Ui.scroll(functionList);

        setContent(Ui.stack(5,
                Ui.bold("Project folder"), folder, problem,
                Ui.bold("Project name"), name,
                Ui.stack(0, javax.swing.Box.createVerticalStrut(4)),
                Ui.bold("Functions / operations"), VerticalLayout.fill(listScroll), counter));
        nextButton = Ui.button("Next", e -> next());
        nextButton.setEnabled(false);
        setButtons(Ui.button("Cancel", e -> frame.requestClose()), nextButton);

        scan(project);
    }

    /** Reads the project off the EDT; opens the error dialog when it is not a usable project. */
    private void scan(Path project) {
        if (project == null) {
            apply(new ProjectScan(null, "No project folder was given.", List.of()));
            return;
        }
        new SwingWorker<ProjectScan, Void>() {
            @Override
            protected ProjectScan doInBackground() {
                return ProjectScanner.scan(project);
            }

            @Override
            protected void done() {
                try {
                    apply(get());
                } catch (InterruptedException | ExecutionException e) {
                    apply(new ProjectScan(project, String.valueOf(e.getCause()), List.of()));
                }
            }
        }.execute();
    }

    private void apply(ProjectScan result) {
        scan = result;
        functionList.removeAll();
        if (!result.valid()) {
            problem.setText(result.problem());
            counter.setText(" ");
            // after the wizard is on screen (the first scan may finish while it is being built)
            SwingUtilities.invokeLater(() -> ErrorDialog.show(frame,
                    ErrorReports.invalidProject(result.folder(), result.problem()),
                    new ErrorDialog.Actions(null, null, null)));
        } else {
            problem.setText(" ");
            folder.setText(result.folder().toString());
            name.setText(result.folder().getFileName().toString());
            for (String fn : result.functions()) {
                JLabel l = Ui.label(fn);
                l.setFont(Theme.font(Font.PLAIN, Theme.BASE_SIZE));
                functionList.add(l);
            }
            int n = result.functions().size();
            counter.setText(n + (n == 1 ? " function" : " functions") + " will be verified");
        }
        functionList.revalidate();
        functionList.repaint();
        updateNext();
    }

    private void updateNext() {
        nextButton.setEnabled(scan != null && scan.valid() && !name.getText().isBlank());
    }

    private void next() {
        ProjectInfo project = new ProjectInfo(scan.folder(), name.getText().trim(), scan.functions());
        if (state.project == null || !state.project.folder().equals(project.folder())) {
            state.invalidateGeneration();
        }
        state.project = project;
        frame.next();
    }
}
