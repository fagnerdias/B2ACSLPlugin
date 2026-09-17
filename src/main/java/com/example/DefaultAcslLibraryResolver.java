package com.example;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Default {@link AcslLibraryResolver} implementation: delegates straight to the existing static
 * methods on {@link AcslLibIncludes}. Pure delegation, no logic moved or rewritten.
 */
final class DefaultAcslLibraryResolver implements AcslLibraryResolver {

    @Override
    public String removeLibIncludesFromPreamble(String acslText) {
        return AcslLibIncludes.removeLibIncludesFromPreamble(acslText);
    }

    @Override
    public String acslBodyAfterPreambleIncludes(String acslText) {
        return AcslLibIncludes.acslBodyAfterPreambleIncludes(acslText);
    }

    @Override
    public String formatIncludeBlock(String acslText, String extraTextForSymbolScan) {
        return AcslLibIncludes.formatIncludeBlock(acslText, extraTextForSymbolScan);
    }

    @Override
    public String formatIncludeBlock(
            String acslText, String extraTextForSymbolScan, Collection<String> additionalLibRelPaths) {
        return AcslLibIncludes.formatIncludeBlock(acslText, extraTextForSymbolScan, additionalLibRelPaths);
    }

    @Override
    public void copyReferencedLibraryFiles(String acslText, Path generatedAcslFile, String extraTextForSymbolScan)
            throws IOException {
        AcslLibIncludes.copyReferencedLibraryFiles(acslText, generatedAcslFile, extraTextForSymbolScan);
    }

    @Override
    public void copyReferencedLibraryFiles(
            String acslText,
            Path generatedAcslFile,
            String extraTextForSymbolScan,
            Collection<String> additionalLibRelPaths)
            throws IOException {
        AcslLibIncludes.copyReferencedLibraryFiles(
                acslText, generatedAcslFile, extraTextForSymbolScan, additionalLibRelPaths);
    }

    @Override
    public Set<String> allowedLibSymbolsForTransitiveIncludes(String acslText, String extraTextForSymbolScan)
            throws IOException {
        return AcslLibIncludes.allowedLibSymbolsForTransitiveIncludes(acslText, extraTextForSymbolScan);
    }

    @Override
    public List<String> orderedLibFunctionAxiomaticNames() throws IOException {
        return AcslLibIncludes.orderedLibFunctionAxiomaticNames();
    }

    @Override
    public void resetLibraryBundleUnderOutput(Path outputDirectory) throws IOException {
        AcslLibIncludes.resetLibraryBundleUnderOutput(outputDirectory);
    }
}
