package com.example.gui.ui.wizard;

import com.example.gui.generation.GenerationService.GenerationOutcome;
import com.example.gui.model.GeneratedSpecification;
import com.example.gui.model.GenerationMode;
import com.example.gui.model.ProjectInfo;
import com.example.gui.model.WpConfig;
import com.example.gui.verification.ToolDetector.ToolStatus;
import java.nio.file.Path;
import java.util.List;

/** What the three steps have chosen so far. Only touched on the Event Dispatch Thread. */
final class WizardState {

    ProjectInfo project;
    GenerationMode mode = GenerationMode.PLUGIN;
    /** Last generation; {@code null} when there is none or it was invalidated. */
    GenerationOutcome generation;
    /** The specifications step 3 will verify ({@code null} until step 2 is complete). */
    List<GeneratedSpecification> specsToVerify;
    WpConfig wpConfig;
    /** {@code null} while the start-up check is running. */
    ToolStatus tools;

    void invalidateGeneration() {
        generation = null;
        specsToVerify = null;
    }

    Path runRoot() {
        return generation == null ? null : generation.run().root();
    }
}
