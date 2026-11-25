package com.example.gui.model;

import java.nio.file.Path;
import java.util.List;

/**
 * A generated specification: C code merged with its ACSL annotations, ready for WP.
 *
 * @param mergedFile absolute path of the published {@code merged_code.c}
 * @param displayPath the path shown to the user, relative to the run directory (e.g. {@code
 *     atl/merged_code.c})
 * @param functionsWithContract functions that have an ACSL contract in this specification
 * @param warnings translator warnings (untranslated constructs)
 */
public record GeneratedSpecification(
        Generator generator,
        Path mergedFile,
        String displayPath,
        List<String> functionsWithContract,
        List<String> warnings,
        long elapsedMs) {

    public GeneratedSpecification {
        functionsWithContract = List.copyOf(functionsWithContract);
        warnings = List.copyOf(warnings);
    }
}
