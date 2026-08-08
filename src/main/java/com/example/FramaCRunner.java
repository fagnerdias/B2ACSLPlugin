package com.example;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.bxml.BxmlImportsGraph;
import com.example.bxml.BxmlSeesGraph;
import com.example.bxml.GhostOperationsCiGenerator;
import com.example.ui.FormalVerificationReportDialog;
import com.example.ui.VerificationProgressDialog;
import com.example.ui.VerificationReportData;
import com.example.analysis.LoopUnrollLevelEstimator;
import com.example.ui.WpOptionsDialog.WpOptions;

/**
 * Invocação do Frama-C ({@code -acsl-import} + {@code -wp}), com retry de auto-cura para colisões
 * de palavra reservada e execução opcional por-operação. Extraído de {@code B2ACSLPipeline}
 * (WMC=607) por extract-class puro: nenhuma linha de lógica mudou, só o arquivo em que vive.
 */
final class FramaCRunner {

    private FramaCRunner() {}

    private static final String FRAMA_C = "frama-c";

    /** Linha {@code [wp] N goals scheduled} — nº de provas que o WP agendou para a função corrente. */
    private static final Pattern WP_GOALS_SCHEDULED_LINE =
            Pattern.compile("^\\[wp\\]\\s+(\\d+)\\s+goals?\\s+scheduled\\s*$");

    private static final Pattern ACSL_OPERATION_CONTRACT_FUNCTION =
            Pattern.compile("(?m)^\\s*function\\s+([A-Za-z_]\\w*)\\s*:");

    private static String abstractMachineNameFromCFile(Path cFile) {
        String base = cFile.getFileName().toString();
        if (base.endsWith(".c")) {
            base = base.substring(0, base.length() - 2);
        }
        if (base.endsWith("_i") || base.endsWith("_r")) {
            return base.substring(0, base.length() - 2);
        }
        return base;
    }

    /**
     * Ordena {@code cFiles} pela mesma ordem topológica (dependências antes de quem as usa) de
     * {@code acslImportFiles}, em vez da ordem alfabética de {@link #findCFiles}. Máquinas sem
     * posição conhecida em {@code acslImportFiles} mantêm a ordem relativa original (sort estável).
     */
    private static List<Path> orderCFilesByAcslImportOrder(List<Path> cFiles, List<Path> acslImportFiles) {
        Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < acslImportFiles.size(); i++) {
            String fileName = acslImportFiles.get(i).getFileName().toString();
            if (fileName.endsWith(".acsl")) {
                rank.putIfAbsent(fileName.substring(0, fileName.length() - ".acsl".length()), i);
            }
        }
        List<Path> ordered = new ArrayList<>(cFiles);
        ordered.sort(
                Comparator.comparingInt(
                        (Path p) -> rank.getOrDefault(abstractMachineNameFromCFile(p), Integer.MAX_VALUE)));
        return ordered;
    }

    /**
     * Palavras que o próprio B2ACSL usa estruturalmente no formato intermédio {@code function X:
     * contract: requires …; ensures …;} passado ao {@code -acsl-import} (e nos blocos {@code
     * axiomatic}/{@code ghost} gerados). Se o token reportado como erro de sintaxe for uma delas, a
     * colisão não é curável por renomeação simples — renomear quebraria o próprio formato.
     */
    private static final Set<String> PROTECTED_ACSL_STRUCTURAL_WORDS =
            Set.of(
                    "requires", "ensures", "assigns", "contract", "function", "at", "assert",
                    "ghost", "loop", "invariant", "variant", "axiom", "axiomatic", "predicate",
                    "logic", "type", "include", "behavior", "reads", "admit", "lemma", "assumes",
                    "return");

    /** Casa {@code [acsl-import] ficheiro:linha: User Error: [Syntax error] <token>.} no output do Frama-C. */
    private static final Pattern ACSL_IMPORT_SYNTAX_ERROR_TOKEN =
            Pattern.compile("\\[acsl-import\\][^\\n]*\\[Syntax error\\]\\s*([A-Za-z_]\\w*)\\s*\\.");

    private static String extractAcslImportSyntaxErrorToken(String framaCOutput) {
        if (framaCOutput == null) {
            return null;
        }
        Matcher m = ACSL_IMPORT_SYNTAX_ERROR_TOKEN.matcher(framaCOutput);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Renomeia (fronteira de palavra, sufixo {@code _b}) todas as ocorrências de {@code badToken}
     * nos {@code .acsl} importados e no {@code ghost_operations.ci} — identificador B (variável,
     * parâmetro, …) que colide com uma palavra reservada do ACSL (ex.: {@code set}).
     */
    private static void renameReservedWordCollision(List<Path> acslImportFiles, Path ghostCi, String badToken)
            throws IOException {
        Pattern wordPattern =
                Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(badToken) + "(?![A-Za-z0-9_])");
        String replacement = badToken + "_b";
        List<Path> targets = new ArrayList<>(acslImportFiles);
        if (ghostCi != null && Files.isRegularFile(ghostCi)) {
            targets.add(ghostCi);
        }
        for (Path p : targets) {
            if (p == null || !Files.isRegularFile(p)) {
                continue;
            }
            String text = Files.readString(p, StandardCharsets.UTF_8);
            String renamed = wordPattern.matcher(text).replaceAll(Matcher.quoteReplacement(replacement));
            if (!renamed.equals(text)) {
                Files.writeString(p, renamed, StandardCharsets.UTF_8);
            }
        }
    }

    /**
     * {@code .acsl} para {@code -acsl-import} numa única invocação Frama-C: raízes SEES quando
     * existem; senão união (ordem estável) dos ficheiros resolvidos por cada {@code .c}.
     */
    private static List<Path> resolveAcslImportForAllCFiles(
            List<Path> cFiles,
            Path acslDir,
            BxmlSeesGraph seesGraph,
            BxmlImportsGraph importsGraph,
            List<Path> topLevelAcslFiles,
            List<Path> allAcslFiles)
            throws IOException {
        if (topLevelAcslFiles != null && !topLevelAcslFiles.isEmpty()) {
            LinkedHashSet<Path> ordered = new LinkedHashSet<>();
            for (Path rootAcsl : topLevelAcslFiles) {
                if (rootAcsl == null || !Files.isRegularFile(rootAcsl)) {
                    continue;
                }
                String fileName = rootAcsl.getFileName().toString();
                if (!fileName.endsWith(".acsl")) {
                    ordered.add(rootAcsl);
                    continue;
                }
                String rootName = fileName.substring(0, fileName.length() - ".acsl".length());
                for (String dep :
                        com.example.bxml.BxmlSetsTranslator.transitiveDependencyMachineNames(
                                rootName, seesGraph, importsGraph)) {
                    Path depAcsl = acslDir.resolve(dep + ".acsl");
                    if (Files.isRegularFile(depAcsl)) {
                        ordered.add(depAcsl);
                    }
                }
                ordered.add(rootAcsl);
            }
            if (!ordered.isEmpty()) {
                return List.copyOf(ordered);
            }
        }
        LinkedHashSet<Path> ordered = new LinkedHashSet<>();
        for (Path cFile : cFiles) {
            ordered.addAll(
                    resolveAcslImportForCFile(
                            cFile,
                            acslDir,
                            seesGraph,
                            importsGraph,
                            topLevelAcslFiles,
                            allAcslFiles));
        }
        return List.copyOf(ordered);
    }

    /**
     * {@code .acsl} para {@code -acsl-import}: o da máquina do {@code .c}, se existir; senão raízes
     * SEES/IMPORTS ({@code topLevelAcslFiles}).
     */
    private static List<Path> resolveAcslImportForCFile(
            Path cFile,
            Path acslDir,
            BxmlSeesGraph seesGraph,
            BxmlImportsGraph importsGraph,
            List<Path> topLevelAcslFiles,
            List<Path> allAcslFiles)
            throws IOException {
        String machine = abstractMachineNameFromCFile(cFile);
        Path own = acslDir.resolve(machine + ".acsl");
        if (Files.isRegularFile(own)) {
            if (isDependencyOnlyMachine(machine, seesGraph, importsGraph)) {
                Optional<Path> libSidecar =
                        AcslGenerator.writeLibIncludesSidecarForSeenMachine(machine, acslDir);
                if (libSidecar.isPresent()) {
                    return List.of(libSidecar.get(), own);
                }
            }
            return List.of(own);
        }
        if (topLevelAcslFiles != null && !topLevelAcslFiles.isEmpty()) {
            return topLevelAcslFiles;
        }
        return allAcslFiles == null ? List.of() : allAcslFiles;
    }

    private static boolean isDependencyOnlyMachine(
            String machine, BxmlSeesGraph seesGraph, BxmlImportsGraph importsGraph) {
        return (seesGraph != null && seesGraph.isReferencedBySees(machine))
                || (importsGraph != null && importsGraph.isReferencedByImports(machine));
    }

    static int runFramaC(
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
        if (cFiles.isEmpty()) return 0;
        VerificationReportData reportData = new VerificationReportData();
        long wpStartNanos = System.nanoTime();

        Path mergedCode = cDir.resolve(B2ACSLPipeline.MERGED_CODE_FILE_NAME);

        Path ghostCi = GhostOperationsCiGenerator.targetPath(cDir);
        StringBuilder specScanForLemmas = new StringBuilder();
        if (allAcslFiles != null) {
            for (Path ap : allAcslFiles) {
                if (ap != null && Files.isRegularFile(ap)) {
                    specScanForLemmas.append(Files.readString(ap, StandardCharsets.UTF_8)).append('\n');
                }
            }
        }
        String ghostCiText =
                Files.isRegularFile(ghostCi)
                        ? Files.readString(ghostCi, StandardCharsets.UTF_8)
                        : "";
        Set<String> allowedLibSymbolsForLemmas =
                AcslLibIncludes.allowedLibSymbolsForTransitiveIncludes(
                        specScanForLemmas.toString(), ghostCiText);

        List<Path> acslImportFiles =
                resolveAcslImportForAllCFiles(
                        cFiles,
                        acslDir,
                        seesGraph,
                        importsGraph,
                        topLevelAcslFiles,
                        allAcslFiles);
        if (acslImportFiles.isEmpty()) {
            System.err.println("[B2ACSL] Nenhum .acsl para importar.");
            return 4;
        }

        // frama-c -acsl-import="f1,f2,…" [ghost_operations.ci] <c>… -print -no-unicode
        // O ghost_operations.ci vem antes dos .c: declarações do dummy_ghost (ex.: IS_VALID) são
        // processadas primeiro; o -acsl-import do .acsl aceita redeclarações com perfil idêntico.
        List<String> importCmd = new ArrayList<>();
        importCmd.add(FRAMA_C);
        String acslImportList =
                acslImportFiles.stream().map(Path::toString).collect(java.util.stream.Collectors.joining(","));
        importCmd.add("-acsl-import=" + acslImportList);
        if (Files.isRegularFile(ghostCi)) {
            importCmd.add(ghostCi.toString());
        }
        // Ordena os .c pela MESMA ordem topológica (dependências antes de quem as usa) do
        // -acsl-import, em vez da ordem alfabética de findCFiles: senão, os globais C de uma
        // máquina "vista"/"importada" (ex.: main_fuel) podem acabar impressos DEPOIS da função de
        // outra máquina que a usa (ex.: entry_point), e nenhum reordenamento de comentários ACSL
        // (MemoryDependentBlockReorderer.moveMemoryDependentVariableBlocksAfterTheirGlobals) resolve isso — a própria função C
        // já está na ordem errada.
        for (Path cFile : orderCFilesByAcslImportOrder(cFiles, acslImportFiles)) {
            importCmd.add(cFile.toString());
        }
        importCmd.add("-print");
        importCmd.add("-no-unicode");
        System.out.println("[B2ACSL] Frama-C acsl-import: " + String.join(" ", importCmd));

        // Alguns identificadores B (ex.: uma variável abstrata chamada "set") coincidem com
        // palavras reservadas do ACSL — o -acsl-import falha com "[Syntax error] <token>.". Em vez
        // de manter uma lista estática (sempre incompleta) de palavras reservadas, deteta-se o
        // token exato reportado pelo próprio Frama-C, renomeia-se (sufixo "_b") em todos os .acsl
        // importados e no ghost_operations.ci, e tenta-se de novo — self-healing, sem risco de
        // adivinhar mal a lista. Só NÃO se tenta curar se o token colidir for uma das palavras
        // estruturais que o próprio B2ACSL usa (requires/ensures/logic/…): renomeá-las corromperia
        // o formato intermédio que o -acsl-import espera.
        int importExitCode = -1;
        // Tokens curados nesta ronda (ex.: "set"): ghost_operations.ci normalmente só tem a forma
        // "dummy_set" (não colide), mas stripDummyPrefixFromMergedCode remove o prefixo "dummy_"
        // DEPOIS do -acsl-import já ter passado, reintroduzindo a mesma palavra reservada de forma
        // crua em merged_code.c — o -wp (invocação Frama-C separada) volta a rejeitá-la, só que com
        // uma mensagem que já não nomeia o token ("unexpected token ','"). Por isso lembra-se aqui
        // cada token curado para reaplicar a mesma renomeação já feita nos .acsl, depois do strip.
        Set<String> healedReservedWords = new LinkedHashSet<>();
        for (int attempt = 0; attempt < 5; attempt++) {
            ProcessBuilder importPb = new ProcessBuilder(importCmd);
            importPb.directory(cDir.toFile());
            importPb.redirectOutput(mergedCode.toFile());
            importPb.redirectError(ProcessBuilder.Redirect.INHERIT);

            Process pImport = importPb.start();
            boolean importOk = pImport.waitFor(120, TimeUnit.SECONDS);
            if (!importOk) {
                pImport.destroyForcibly();
                return 5;
            }
            importExitCode = pImport.exitValue();
            if (importExitCode == 0) {
                break;
            }
            String importOutput =
                    Files.isRegularFile(mergedCode)
                            ? Files.readString(mergedCode, StandardCharsets.UTF_8)
                            : "";
            String badToken = extractAcslImportSyntaxErrorToken(importOutput);
            if (badToken == null || PROTECTED_ACSL_STRUCTURAL_WORDS.contains(badToken)) {
                return importExitCode;
            }
            System.out.println(
                    "[B2ACSL] '"
                            + badToken
                            + "' colide com palavra reservada do ACSL; renomeando para '"
                            + badToken
                            + "_b' em todos os .acsl importados e tentando novamente...");
            renameReservedWordCollision(acslImportFiles, ghostCi, badToken);
            healedReservedWords.add(badToken);
        }
        if (importExitCode != 0) {
            return importExitCode;
        }

        MergedCodeStructuralPlacement.stripLeadingFramaCNonCOutput(mergedCode);
        MergedCodeStructuralPlacement.moveNewTypesAxiomaticBlockAfterPreamble(mergedCode);
        B2ACSLPipeline.removeGhostPatternAxiomaticBlocks(mergedCode);
        B2ACSLPipeline.stripDummyPrefixFromMergedCode(mergedCode);
        B2ACSLPipeline.insertGhostVariableDeclarationsFromGhostCi(mergedCode, ghostCi);
        B2ACSLPipeline.replaceAssertGhostWithGhostKeyword(mergedCode);
        B2ACSLPipeline.replaceEnsuresGhostVarWithAssignsInMerged(mergedCode);
        B2ACSLPipeline.addParenthesesToVoidGhostCalls(mergedCode);
        B2ACSLPipeline.placeGhostOperationSpecsAboveFunctions(mergedCode, ghostCi);
        B2ACSLPipeline.liftPureGhostEnsuresToOperationContracts(mergedCode);
        LibAxiomaticBlockReorderer.reorderLibAxiomaticBlocksPerAcslLibIncludesOrder(mergedCode);
        MergedCodeStructuralPlacement.moveTupleCodomainAxiomaticBlocksAfterNewTypes(mergedCode);
        LemmaLibraryFilter.appendLemmasAcslLibToMergedEnd(mergedCode, allowedLibSymbolsForLemmas);
        SpecificationAxiomaticInstantiator.monomorphizeGenericAcslBlocks(
                mergedCode, specificationUsedTypes);
        SpecificationAxiomaticInstantiator.renameParameterizedTypesToConcrete(
                mergedCode, specificationUsedTypes);
        SpecificationAxiomaticInstantiator.normalizeLegacyMachineTypeIdentifiers(mergedCode);
        LemmaLibraryFilter.ensureSequenceListToFunctionDeclBeforeFirstUse(mergedCode);
        MemoryDependentBlockReorderer.moveMemoryDependentVariableBlocksAfterTheirGlobals(mergedCode);
        // Última etapa: várias transformações acima (ex. placeGhostOperationSpecsAboveFunctions)
        // fazem a SUA PRÓPRIA remoção do prefixo "dummy_" ao inserir texto lido diretamente de
        // ghost_operations.ci, reintroduzindo tarde uma palavra reservada do ACSL (ex. "dummy_set"
        // → "set") já curada mais cedo no .acsl fonte. Reaplica-se aqui, no fim, para cobrir
        // qualquer reintrodução independentemente de qual passo a causou.
        for (String healed : healedReservedWords) {
            renameReservedWordCollision(List.of(mergedCode), null, healed);
        }

        String cSourcesLabel =
                cFiles.stream().map(p -> p.getFileName().toString()).reduce((a, b) -> a + ", " + b).orElse("");

        List<String> operationFunctionNames =
                wpOptions.verifyPerOperation()
                        ? resolveOperationFunctionNamesForWp(acslImportFiles)
                        : List.of();
        if (wpOptions.verifyPerOperation() && !operationFunctionNames.isEmpty()) {
            System.out.println(
                    "[B2ACSL] Per-operation WP enabled; functions: " + operationFunctionNames);
        }

        List<String> wpFunctionsToRun;
        if (operationFunctionNames.isEmpty()) {
            // List.of(null) throws NPE by contract; a single whole-file WP run is requested
            // with a null function name (buildWpCommand omits -wp-fct in that case).
            wpFunctionsToRun = new ArrayList<>();
            wpFunctionsToRun.add(null);
        } else {
            wpFunctionsToRun = operationFunctionNames;
        }
        List<String> wpSourceNames = new ArrayList<>();
        for (String functionName : wpFunctionsToRun) {
            wpSourceNames.add(wpSourceName(mergedCode, functionName, cSourcesLabel));
        }
        VerificationProgressDialog progress = VerificationProgressDialog.show(projectName, wpSourceNames);

        int failingExitCode = 0;
        for (String functionName : wpFunctionsToRun) {
            List<String> wpCmd =
                    buildWpCommand(mergedCode, wpOptions, functionName, cSourcesLabel);
            ProcessBuilder wpPb = new ProcessBuilder(wpCmd);
            wpPb.directory(cDir.toFile());
            wpPb.redirectErrorStream(true);

            String sourceName = wpSourceName(mergedCode, functionName, cSourcesLabel);
            progress.markRunning(sourceName);
            progress.appendLogHeader(sourceName);

            ProcessResult wpResult =
                    runProcessWithCapturedOutput(
                            wpPb,
                            600,
                            TimeUnit.SECONDS,
                            line -> {
                                progress.appendLogLine(line);
                                Matcher scheduled = WP_GOALS_SCHEDULED_LINE.matcher(line);
                                if (scheduled.matches()) {
                                    progress.markGoalsScheduled(
                                            sourceName, Integer.parseInt(scheduled.group(1)));
                                }
                            });
            reportData.absorbOutput(wpResult.output(), sourceName);
            if (!wpResult.completed()) {
                reportData.addTimeout(
                        "Timeout while executing WP"
                                + (functionName == null ? "" : " for function " + functionName)
                                + " (600s limit).");
                progress.markCompleted(
                        sourceName, VerificationProgressDialog.Status.TIMEOUT, 0, 0, "timed out (600s)");
                progress.finish(false);
                showVerificationReport(
                        projectName, mergedCode.getFileName().toString(), wpStartNanos, reportData, progress);
                return 6;
            }
            if (wpResult.exitCode() != 0) {
                reportData.addFailure(
                        "WP returned exit code "
                                + wpResult.exitCode()
                                + (functionName == null ? "" : " for function " + functionName)
                                + ".");
                if (failingExitCode == 0) {
                    failingExitCode = wpResult.exitCode();
                }
                progress.markCompleted(
                        sourceName,
                        VerificationProgressDialog.Status.FAILED,
                        0,
                        0,
                        "WP exit code " + wpResult.exitCode());
            } else {
                markCompletedFromSummary(progress, reportData, sourceName);
            }
        }
        if (failingExitCode != 0) {
            progress.finish(false);
            showVerificationReport(
                    projectName, mergedCode.getFileName().toString(), wpStartNanos, reportData, progress);
            return failingExitCode;
        }
        progress.finish(true);
        showVerificationReport(
                projectName, mergedCode.getFileName().toString(), wpStartNanos, reportData, progress);
        return 0;
    }

    private static String wpSourceName(Path mergedCode, String functionName, String cSourcesLabel) {
        return functionName == null
                ? mergedCode.getFileName().toString() + " (" + cSourcesLabel + ")"
                : functionName + " (" + mergedCode.getFileName().toString() + ")";
    }

    /**
     * Classifica o resultado de uma função/operação já absorvida em {@code reportData} (WP
     * terminou com exit code 0) e atualiza a janela de progresso de acordo com as contagens de
     * goals provados/total daquela fonte especificamente.
     */
    private static void markCompletedFromSummary(
            VerificationProgressDialog progress, VerificationReportData reportData, String sourceName) {
        VerificationReportData.FunctionSummary summary =
                reportData.functionSummaries().stream()
                        .filter(s -> s.functionName().equals(sourceName))
                        .findFirst()
                        .orElse(null);
        if (summary == null || summary.totalGoals() == 0) {
            progress.markCompleted(sourceName, VerificationProgressDialog.Status.PROVED, 0, 0, "no goals");
            return;
        }
        int proved = summary.provedGoals();
        int total = summary.totalGoals();
        VerificationProgressDialog.Status status;
        if (proved >= total) {
            status = VerificationProgressDialog.Status.PROVED;
        } else if (summary.timeouts() > 0 && summary.failures() == 0) {
            status = VerificationProgressDialog.Status.TIMEOUT;
        } else if (proved > 0) {
            status = VerificationProgressDialog.Status.PARTIAL;
        } else {
            status = VerificationProgressDialog.Status.FAILED;
        }
        progress.markCompleted(sourceName, status, proved, total, null);
    }

    private static List<String> buildWpCommand(
            Path mergedCode, WpOptions wpOptions, String functionName, String cSourcesLabel) throws IOException {
        List<String> wpCmd = new ArrayList<>();
        wpCmd.add(FRAMA_C);
        if (wpOptions.loopSimplification()) {
            int ulevel = LoopUnrollLevelEstimator.computeUlevel(mergedCode);
            System.out.println(
                    "[B2ACSL] Loop simplification: -ulevel "
                            + ulevel
                            + " (max loop size + 1 in "
                            + mergedCode.getFileName()
                            + ")");
            wpCmd.add("-ulevel");
            wpCmd.add(Integer.toString(ulevel));
            wpCmd.add(mergedCode.getFileName().toString());
            wpCmd.add("-then");
        }
        wpCmd.add("-wp");
        wpCmd.add(mergedCode.getFileName().toString());
        if (functionName != null && !functionName.isBlank()) {
            wpCmd.add("-wp-fct");
            wpCmd.add(functionName);
        }
        wpCmd.add("-wp-prover");
        wpCmd.add(wpOptions.proversArgument());
        if (wpOptions.smokeTests()) {
            wpCmd.add("-wp-smoke-tests");
        }
        if (wpOptions.counterExamples()) {
            wpCmd.add("-wp-counter-examples");
        }
        wpCmd.add("-wp-rte");
        if (wpOptions.splitGoals()) {
            wpCmd.add("-wp-split");
        }
        wpCmd.add("-wp-timeout");
        wpCmd.add(Integer.toString(wpOptions.timeoutSeconds()));
        wpCmd.add(wpOptions.outputFlag());
        System.out.println(
                "[B2ACSL] Frama-C WP"
                        + (functionName == null ? "" : " (" + functionName + ")")
                        + ": "
                        + String.join(" ", wpCmd)
                        + " // sources: "
                        + cSourcesLabel);
        return wpCmd;
    }

    private static List<String> resolveOperationFunctionNamesForWp(List<Path> acslImportFiles)
            throws IOException {
        if (acslImportFiles == null || acslImportFiles.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> orderedFunctions = new LinkedHashSet<>();
        for (Path acslFile : acslImportFiles) {
            if (acslFile == null || !Files.isRegularFile(acslFile)) {
                continue;
            }
            String acslText = Files.readString(acslFile, StandardCharsets.UTF_8);
            Matcher fnMatcher = ACSL_OPERATION_CONTRACT_FUNCTION.matcher(acslText);
            while (fnMatcher.find()) {
                String functionName = fnMatcher.group(1);
                if (functionName == null || functionName.isBlank()) {
                    continue;
                }
                orderedFunctions.add(functionName);
            }
        }
        return List.copyOf(orderedFunctions);
    }

    private record ProcessResult(boolean completed, int exitCode, String output) {}

    private static ProcessResult runProcessWithCapturedOutput(
            ProcessBuilder processBuilder, long timeout, TimeUnit timeoutUnit, Consumer<String> lineConsumer)
            throws IOException, InterruptedException {
        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();

        Thread reader =
                new Thread(
                        () -> {
                            try (BufferedReader br =
                                    new BufferedReader(
                                            new InputStreamReader(
                                                    process.getInputStream(), StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = br.readLine()) != null) {
                                    output.append(line).append('\n');
                                    System.out.println(line);
                                    if (lineConsumer != null) {
                                        lineConsumer.accept(line);
                                    }
                                }
                            } catch (IOException e) {
                                output.append("[B2ACSL] Falha ao ler saida do processo: ")
                                        .append(e.getMessage())
                                        .append('\n');
                            }
                        },
                        "b2acsl-process-output-reader");
        reader.setDaemon(true);
        reader.start();

        boolean completed = process.waitFor(timeout, timeoutUnit);
        if (!completed) {
            process.destroyForcibly();
        }

        reader.join(2000);
        int exitCode = completed ? process.exitValue() : -1;
        return new ProcessResult(completed, exitCode, output.toString());
    }

    private static void showVerificationReport(
            String projectName,
            String analyzedFileName,
            long startNanos,
            VerificationReportData reportData,
            VerificationProgressDialog progress) {
        progress.close();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        FormalVerificationReportDialog.show(projectName, analyzedFileName, elapsedMs, reportData);
    }
}
