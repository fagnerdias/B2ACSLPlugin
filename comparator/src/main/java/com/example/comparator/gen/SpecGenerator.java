package com.example.comparator.gen;

import com.example.comparator.Workspace;

/** A B → ACSL translator under comparison. */
public interface SpecGenerator {

    /**
     * Translates the BXML of the workspace into specification files in its {@code lang/c} folder.
     * Failures are reported through {@link GeneratedSpec#status()}, not thrown.
     */
    GeneratedSpec generate(Workspace workspace);
}
