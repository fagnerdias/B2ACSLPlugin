package com.example.comparator.gen;

import com.example.B2ACSLPipeline;
import com.example.B2ACSLPipeline.GenerationResult;
import com.example.FramaCRunner;
import com.example.bxml.GhostOperationsCiGenerator;
import com.example.comparator.Side;
import com.example.comparator.Workspace;
import com.example.comparator.spec.SpecDiff;
import java.nio.file.Path;
import java.util.List;

/**
 * B2ACSLPlugin, in-process: {@link B2ACSLPipeline#generate} followed by the same {@code
 * -acsl-import} ordering that {@code FramaCRunner.runFramaC} applies in the normal pipeline.
 */
public final class PluginSpecGenerator implements SpecGenerator {

    @Override
    public GeneratedSpec generate(Workspace workspace) {
        long start = System.nanoTime();
        try {
            GenerationResult g = B2ACSLPipeline.generate(workspace.bdp(Side.PLUGIN));
            if (g.exitCode() != 0) {
                String status = g.exitCode() == 4 ? "NO_C_FILES" : "GENERATION_ERROR";
                return GeneratedSpec.failed(
                        status, g.exitCode(), elapsedMs(start), List.of(),
                        "B2ACSLPipeline.generate returned " + g.exitCode());
            }
            List<Path> importOrder =
                    FramaCRunner.orderAcslFilesForImport(
                            g.cFiles(),
                            g.acslDir(),
                            g.seesGraph(),
                            g.importsGraph(),
                            g.topLevelAcslFiles(),
                            g.acslFiles());
            return new GeneratedSpec(
                    GeneratedSpec.OK,
                    0,
                    elapsedMs(start),
                    g.cDir(),
                    importOrder,
                    GhostOperationsCiGenerator.targetPath(g.cDir()),
                    g.cFiles(),
                    g.specificationUsedTypes(),
                    SpecDiff.specFiles(g.cDir()).stream().map(p -> p.getFileName().toString()).toList(),
                    List.of(),
                    null);
        } catch (Exception e) {
            return GeneratedSpec.failed("GENERATION_ERROR", -1, elapsedMs(start), List.of(), e.toString());
        }
    }

    static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
