package com.example;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.example.bxml.BxmlImportsGraph;
import com.example.bxml.BxmlSeesGraph;
import com.example.ui.WpOptionsDialog.WpOptions;

/**
 * Default {@link ExternalVerifierRunner} implementation: delegates straight to the existing
 * static {@link FramaCRunner#runFramaC}. Pure delegation, no logic moved or rewritten.
 */
final class FramaCVerifierRunner implements ExternalVerifierRunner {

    @Override
    public int runFramaC(
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
            throws IOException, InterruptedException {
        return FramaCRunner.runFramaC(
                topLevelAcslFiles,
                allAcslFiles,
                acslDir,
                seesGraph,
                importsGraph,
                cFiles,
                cDir,
                specificationUsedTypes,
                wpOptions,
                projectName);
    }
}
