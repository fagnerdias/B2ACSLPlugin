package com.example.comparator.gen;

import com.example.comparator.report.Reports.GenerationReport;
import java.nio.file.Path;
import java.util.List;

/**
 * What a {@link SpecGenerator} produced, with everything the verifier needs.
 *
 * @param acslImportFiles {@code .acsl} files in {@code -acsl-import} order (see {@code
 *     FramaCRunner.orderAcslFilesForImport})
 * @param ghostCi path of {@code ghost_operations.ci} in {@code cDir}, even when the tool did not
 *     produce one: like {@code FramaCRunner.runFramaC}, callers pass the conventional path and the
 *     verifier checks whether the file exists ({@code null} is not handled by the post-processing)
 */
public record GeneratedSpec(
        String status,
        int exitCode,
        long elapsedMs,
        Path cDir,
        List<Path> acslImportFiles,
        Path ghostCi,
        List<Path> cFiles,
        List<String> specificationUsedTypes,
        List<String> files,
        List<String> warnings,
        String message) {

    public static final String OK = "OK";

    public boolean succeeded() {
        return OK.equals(status);
    }

    public static GeneratedSpec failed(String status, int exitCode, long elapsedMs, List<String> warnings, String message) {
        return new GeneratedSpec(
                status, exitCode, elapsedMs, null, List.of(), null, List.of(), List.of(), List.of(), warnings, message);
    }

    public GenerationReport toReport() {
        return new GenerationReport(
                status,
                exitCode,
                elapsedMs,
                files,
                acslImportFiles.stream().map(p -> p.getFileName().toString()).toList(),
                warnings,
                message);
    }
}
