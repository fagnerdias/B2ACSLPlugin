package com.example.gui.generation;

import com.example.gui.model.GeneratedSpecification;
import com.example.gui.model.Generator;
import com.example.gui.model.ProjectInfo;
import com.example.gui.verification.Cancellation;
import java.util.function.Consumer;

/** Produces one merged C file with ACSL annotations for a project. */
public interface SpecificationGenerator {

    Generator generator();

    /**
     * Runs off the Event Dispatch Thread.
     *
     * @param log receives the generator output, line by line, as it is produced
     */
    GeneratedSpecification generate(ProjectInfo project, RunDirectory run, Consumer<String> log, Cancellation cancel)
            throws SpecificationGenerationException;
}
