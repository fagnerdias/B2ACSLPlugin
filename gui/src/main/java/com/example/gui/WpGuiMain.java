package com.example.gui;

import com.example.gui.ui.AppPreferences;
import com.example.gui.ui.Theme;
import com.example.gui.ui.wizard.WizardFrame;
import javax.swing.SwingUtilities;

/** Entry point of the Frama-C WP wizard ({@code make gui}). */
public final class WpGuiMain {

    private WpGuiMain() {}

    public static void main(String[] args) {
        Theme.install();
        SwingUtilities.invokeLater(() -> new WizardFrame(new AppPreferences()).start());
    }
}
