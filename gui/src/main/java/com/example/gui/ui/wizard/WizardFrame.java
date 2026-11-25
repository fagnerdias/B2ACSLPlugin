package com.example.gui.ui.wizard;

import com.example.gui.model.GeneratedSpecification;
import com.example.gui.model.GenerationMode;
import com.example.gui.model.Generator;
import com.example.gui.ui.AppPreferences;
import com.example.gui.ui.ErrorDialog;
import com.example.gui.ui.ErrorReports;
import com.example.gui.ui.ProgressWindow;
import com.example.gui.verification.ToolDetector;
import com.example.gui.verification.ToolDetector.ToolStatus;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.concurrent.ExecutionException;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;

/**
 * The wizard window (CardLayout): 1 Project → 2 Specification → 3 WP Configuration, then the
 * progress window and the report. The window takes the size of the visible step.
 */
public final class WizardFrame extends JFrame {

    private final AppPreferences prefs;
    private final WizardState state = new WizardState();
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new JPanel(cardLayout) {
        @Override
        public Dimension getPreferredSize() {
            Component visible = steps[current];
            Dimension d = visible.getPreferredSize();
            Insets in = getInsets();
            return new Dimension(d.width + in.left + in.right, d.height + in.top + in.bottom);
        }
    };
    private final WizardStep[] steps;
    private int current;

    public WizardFrame(AppPreferences prefs) {
        super("Frama-C WP Configuration");
        this.prefs = prefs;
        state.mode = prefs.mode();
        state.wpConfig = prefs.wpConfig();
        steps = new WizardStep[] {
            new ProjectStep(this, state, prefs), new SpecificationStep(this, state), new WpConfigStep(this, state)
        };
        for (int i = 0; i < steps.length; i++) {
            cards.add(steps[i], Integer.toString(i));
        }
        setContentPane(cards);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                requestClose();
            }
        });
    }

    public void start() {
        showStep(0);
        setLocationRelativeTo(null);
        setVisible(true);
        detectTools();
    }

    void showStep(int index) {
        current = index;
        cardLayout.show(cards, Integer.toString(index));
        steps[index].onShow();
        pack();
    }

    void next() {
        if (current < steps.length - 1) {
            showStep(current + 1);
        }
    }

    void back() {
        if (current > 0) {
            showStep(current - 1);
        }
    }

    /** Cancel / window close: stops a running generation (after confirmation) and quits. */
    void requestClose() {
        SpecificationStep spec = (SpecificationStep) steps[1];
        if (spec.isGenerating()) {
            int answer = JOptionPane.showConfirmDialog(this,
                    "A specification is being generated. Stop it and close?", "Close",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) {
                return;
            }
            spec.cancelGeneration();
        }
        prefs.setMode(state.mode);
        prefs.flush();
        dispose();
        System.exit(0);
    }

    /** "Continue with &lt;generator&gt; Only" from the error dialog: single mode, keep its spec, go to step 3. */
    void continueWith(Generator generator) {
        GeneratedSpecification spec = state.generation.successes().get(generator);
        state.mode = GenerationMode.single(generator);
        state.specsToVerify = List.of(spec);
        prefs.setMode(state.mode);
        ((SpecificationStep) steps[1]).showModeKeepingGeneration();
        showStep(2);
    }

    void runVerification() {
        if (state.tools != null && !state.tools.framaCFound()) {
            ErrorDialog.show(this, ErrorReports.framaCNotFound(null),
                    new ErrorDialog.Actions(this::detectTools, null, null));
            return;
        }
        prefs.setWpConfig(state.wpConfig);
        prefs.setMode(state.mode);
        prefs.flush();
        setVisible(false);
        new ProgressWindow(state.project, state.mode, state.specsToVerify, state.wpConfig, state.runRoot(),
                this::returnFromVerification).start();
    }

    /** Back from the progress window (cancelled) or a report (closed): step 3, specs kept. */
    private void returnFromVerification() {
        setVisible(true);
        showStep(2);
        toFront();
    }

    private void detectTools() {
        new SwingWorker<ToolStatus, Void>() {
            @Override
            protected ToolStatus doInBackground() {
                return ToolDetector.detect();
            }

            @Override
            protected void done() {
                try {
                    state.tools = get();
                } catch (InterruptedException | ExecutionException e) {
                    state.tools = new ToolStatus(null, java.util.Map.of());
                }
                ((WpConfigStep) steps[2]).toolsDetected();
                if (!state.tools.framaCFound()) {
                    ErrorDialog.show(WizardFrame.this, ErrorReports.framaCNotFound(null),
                            new ErrorDialog.Actions(WizardFrame.this::detectTools, null, null));
                }
            }
        }.execute();
    }
}
