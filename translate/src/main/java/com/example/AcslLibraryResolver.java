package com.example;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Abstraction over deciding which {@code B2ACSLLib} files to include/copy for a generated {@code
 * .acsl} file. Extracted as a pure interface over the static methods of {@link AcslLibIncludes}
 * (and, transitively, {@link AcslLibSymbolDependencyMap}) actually invoked across class
 * boundaries by {@link B2ACSLPipeline}, {@link FramaCRunner}, {@link AxiomaticTierSorter} and
 * {@link AcslGenerator} — no behavior change, only a seam for those callers to depend on an
 * interface instead of the static methods directly.
 */
public interface AcslLibraryResolver {

    String removeLibIncludesFromPreamble(String acslText);

    String acslBodyAfterPreambleIncludes(String acslText);

    String formatIncludeBlock(String acslText, String extraTextForSymbolScan);

    String formatIncludeBlock(
            String acslText, String extraTextForSymbolScan, Collection<String> additionalLibRelPaths);

    void copyReferencedLibraryFiles(
            String acslText, Path generatedAcslFile, String extraTextForSymbolScan, Path targetAcslDir)
            throws IOException;

    void copyReferencedLibraryFiles(
            String acslText,
            Path generatedAcslFile,
            String extraTextForSymbolScan,
            Collection<String> additionalLibRelPaths,
            Path targetAcslDir)
            throws IOException;

    Set<String> allowedLibSymbolsForTransitiveIncludes(String acslText, String extraTextForSymbolScan)
            throws IOException;

    List<String> orderedLibFunctionAxiomaticNames() throws IOException;

    void resetLibraryBundleUnderOutput(Path outputDirectory) throws IOException;

    List<String> parseLibIncludeRelativePathsFromPreamble(String acslText);
}
