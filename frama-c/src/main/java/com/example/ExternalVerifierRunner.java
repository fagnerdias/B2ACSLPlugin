package com.example;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.example.bxml.BxmlImportsGraph;
import com.example.bxml.BxmlSeesGraph;
import com.example.ui.WpOptionsDialog.WpOptions;

/**
 * Abstraction over invoking the external formal verifier (Frama-C {@code -acsl-import} + {@code
 * -wp}) for a project's generated ACSL/C sources. Extracted as a pure interface over {@link
 * FramaCRunner#runFramaC(List, List, Path, BxmlSeesGraph, BxmlImportsGraph, List, Path, List,
 * WpOptions, String)} — no behavior change, only a seam for callers to depend on an interface
 * instead of the static method directly.
 */
interface ExternalVerifierRunner {

    int runFramaC(
            List<Path> topLevelAcslFiles,
            List<Path> allAcslFiles,
            Path acslDir,
            BxmlSeesGraph seesGraph,
            BxmlImportsGraph importsGraph,
            List<Path> cFiles,
            Path cDir,
            List<String> specificationUsedTypes,
            WpOptions wpOptions,
            String projectName)
            throws IOException, InterruptedException;
}
