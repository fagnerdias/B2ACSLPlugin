package com.example;

import java.io.InputStream;
import java.io.IOException;
import java.io.BufferedReader;
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
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.bxml.BxmlGluingNormalizer;
import com.example.bxml.BxmlImportsGraph;
import com.example.bxml.BxmlSeesGraph;
import com.example.bxml.GhostOperationsCiGenerator;
import com.example.model.Machine;
import com.example.ui.FormalVerificationReportDialog;
import com.example.ui.VerificationReportData;
import com.example.analysis.LoopUnrollLevelEstimator;
import com.example.ui.WpOptionsDialog;
import com.example.ui.WpOptionsDialog.WpOptions;

import org.w3c.dom.Element;

/**
 * Pipeline B2ACSL: BXML -> ACSL -> {@code ghost_operations.ci} -> Frama-C ({@code -acsl-import} +
 * {@code merged_code.c} + WP) -> resultado para Atelier B.
 */
public final class B2ACSLPipeline {

    private record MachineFile(Machine machine, Path bxmlPath) {}

    private static final String FRAMA_C = "frama-c";

    /**
     * Linhas de diagnóstico que o Frama-C escreve no stdout antes do C gerado com {@code -print}
     * (ex.: {@code [kernel] Parsing ...}, {@code [acsl-import] Success ...}).
     */
    private static final Pattern FRAMA_C_STDOUT_TAG_LINE =
            Pattern.compile("^\\[[^\\]]+\\]\\s*.*");

    /** Marca o bloco {@code axiomatic new_types} importado (ex. de {@code types.acsl}). */
    private static final String AXIOMATIC_NEW_TYPES_MARKER = "axiomatic new_types";

    /** Primeira utilização da lib {@code list_to_function(} (não {@code dummy_list_to_function}). */
    private static final Pattern LIST_TO_FUNCTION_LIB_CALL =
            Pattern.compile("(?<![A-Za-z0-9_])list_to_function\\s*\\(");

    /** Lemas admitidos da B2ACSLLib (anexados ao fim do merge Frama-C). */
    private static final String ACSL_LIB_LEMMAS_RESOURCE = B2AcslLibraryPaths.classpathResource("lemmas.acsl");

    private static final boolean MOCK_MODE = isMockEnabled();

    private static boolean isMockEnabled() {
        String sys = System.getProperty("b2acsl.mock");
        if (sys != null && !sys.isBlank()) return Boolean.parseBoolean(sys);
        try (InputStream in = B2ACSLPipeline.class.getResourceAsStream("/META-INF/b2acsl.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("b2acsl.mock");
                if (v != null && !v.isBlank()) return Boolean.parseBoolean(v.trim());
            }
        } catch (Exception ignored) {}
        return true;
    }
    /** Se definido, grava os .acsl neste diretório e não os remove (para inspeção) */
    private static final String KEEP_ACSL_DIR = System.getProperty("b2acsl.keepAcsl");

    private B2ACSLPipeline() {}

    /**
     * Executa o pipeline completo.
     *
     * @param bdpPath Caminho da pasta bdp (contém os .bxml)
     * @return Código de retorno para o Atelier B (0=sucesso, !=0=falha)
     */
    public static int run(Path bdpPath) throws Exception {
        Path bdp = bdpPath.toAbsolutePath().normalize();
        if (!Files.isDirectory(bdp)) {
            System.err.println("[B2ACSL] Caminho inválido (não é diretório): " + bdp);
            return 1;
        }

        // Step 1: Ler arquivos .bxml
        List<Path> bxmlFiles = findBxmlFiles(bdp);
        if (bxmlFiles.isEmpty()) {
            System.err.println("[B2ACSL] Nenhum arquivo .bxml encontrado em: " + bdp);
            return 2;
        }

        List<MachineFile> machines = new ArrayList<>();
        for (Path f : bxmlFiles) {
            try {
                Machine m = Machine.fromBxmlPath(f);
                machines.add(new MachineFile(m, f));
            } catch (Exception e) {
                System.err.println("[B2ACSL] Falha ao ler " + f + ": " + e.getMessage());
            }
        }

        if (machines.isEmpty()) {
            System.err.println("[B2ACSL] Nenhuma máquina válida encontrada nos ficheiros .bxml.");
            return 3;
        }

        Map<String, String> invariantGluingSubstitutions = BxmlGluingNormalizer.collectFromAllBxmlFiles(bxmlFiles);

        BxmlSeesGraph seesGraph = BxmlSeesGraph.fromBxmlDirectory(bdp);

        // Mapa máquina -> nome em <Abstraction> (refinamento / implementação)
        Map<String, String> abstractionParentByMachine = buildAbstractionParentMap(bxmlFiles);
        BxmlImportsGraph importsGraph =
                BxmlImportsGraph.fromBxmlDirectory(bdp, abstractionParentByMachine);
        logSeesRelations(seesGraph);
        logImportsRelations(importsGraph);
        // Ficheiros BXML de refinamento/implementação a fundir na máquina abstrata raiz
        Map<String, List<Path>> mergePathsByRootAbstract = new HashMap<>();
        for (Path f : bxmlFiles) {
            try {
                Element root = AcslGenerator.parseMachineElement(f);
                if (AcslGenerator.getAbstractionReferenceName(root).isEmpty()) continue;
                String source = root.getAttribute("name");
                if (source == null || source.isBlank()) continue;
                String rootAbstract = resolveRootAbstractName(source, abstractionParentByMachine);
                mergePathsByRootAbstract.computeIfAbsent(rootAbstract, k -> new ArrayList<>()).add(f);
            } catch (Exception e) {
                System.err.println("[B2ACSL] Falha ao indexar merge de " + f + ": " + e.getMessage());
            }
        }
        for (List<Path> paths : mergePathsByRootAbstract.values()) {
            paths.sort(
                    Comparator.comparingInt(
                            path -> {
                                try {
                                    String n =
                                            AcslGenerator.parseMachineElement(path)
                                                    .getAttribute("name");
                                    return refinementDepthToRoot(n, abstractionParentByMachine);
                                } catch (Exception e) {
                                    return 0;
                                }
                            }));
        }

        // Step 1.1: Gerar arquivos .acsl (temporários ou em dir fixo para inspeção)
        Path acslDir = KEEP_ACSL_DIR != null && !KEEP_ACSL_DIR.isBlank()
                ? Path.of(KEEP_ACSL_DIR).toAbsolutePath().normalize()
                : Files.createTempDirectory("b2acsl_acsl_");
        boolean keepFiles = KEEP_ACSL_DIR != null && !KEEP_ACSL_DIR.isBlank();
        try {
            List<Path> acslFiles = new ArrayList<>();
            Set<String> abstractMachineNames = new LinkedHashSet<>();
            Set<String> dependencyOnlyMachineNames =
                    dependencyOnlyMachineNames(seesGraph, importsGraph);
            List<MachineFile> machinesForAcsl =
                    orderMachinesForAcslGeneration(machines, dependencyOnlyMachineNames);
            for (MachineFile mf : machinesForAcsl) {
                Element machineRoot = AcslGenerator.parseMachineElement(mf.bxmlPath());
                if (AcslGenerator.getAbstractionReferenceName(machineRoot).isPresent()) {
                    continue;
                }
                String machineName = mf.machine().getMachineName();
                abstractMachineNames.add(machineName);
                List<Path> mergePaths =
                        mergePathsByRootAbstract.getOrDefault(machineName, List.of());
                Optional<Path> acsl =
                        AcslGenerator.generateAcsl(
                                mf.machine(),
                                mf.bxmlPath(),
                                acslDir,
                                mergePaths,
                                invariantGluingSubstitutions,
                                dependencyOnlyMachineNames,
                                seesGraph,
                                importsGraph);
                acsl.ifPresent(acslFiles::add);
            }
            List<String> topLevelImportMachines =
                    topLevelImportMachineNames(abstractMachineNames, dependencyOnlyMachineNames);
            List<Path> topLevelAcslFiles =
                    filterAcslFilesByMachineNames(acslFiles, topLevelImportMachines);
            if (!topLevelImportMachines.isEmpty()) {
                System.out.println(
                        "[B2ACSL] Importação ACSL Frama-C (raiz SEES): " + topLevelImportMachines);
            }
            if (keepFiles) {
                System.out.println("[B2ACSL] ACSL gravados em: " + acslDir);
                for (Path p : acslFiles) System.out.println("  - " + p);
            }

            // Step 2: Obter arquivos .c em lang/c/ (mesmo path, trocando bdp por lang)
            String bdpStr = bdpPathToString(bdp);
            int idx = bdpStr.lastIndexOf("bdp");
            Path langPath = idx >= 0
                    ? Path.of(bdpStr.substring(0, idx) + "lang" + bdpStr.substring(idx + 3))
                    : bdp.getParent().resolve("lang");
            Path cDir = langPath.resolve("c");
            for (MachineFile mf : machines) {
                Element mr = AcslGenerator.parseMachineElement(mf.bxmlPath());
                if (AcslGenerator.getAbstractionReferenceName(mr).isPresent()) {
                    continue;
                }
                String machineName = mf.machine().getMachineName();
                List<Element> mergedEls = new ArrayList<>();
                for (Path mp : mergePathsByRootAbstract.getOrDefault(machineName, List.of())) {
                    mergedEls.add(AcslGenerator.parseMachineElement(mp));
                }
                GhostOperationsCiGenerator.write(
                        cDir, mr, invariantGluingSubstitutions, bdp, mergedEls);
                break;
            }

            // Step 2.1: Lista de tipos utilizados na especificação (.acsl + ghost_operations.ci + BXML)
            List<Element> abstractMachineRoots = new ArrayList<>();
            for (MachineFile mf : machines) {
                Element machineRoot = AcslGenerator.parseMachineElement(mf.bxmlPath());
                if (AcslGenerator.getAbstractionReferenceName(machineRoot).isPresent()) {
                    continue;
                }
                abstractMachineRoots.add(machineRoot);
            }
            List<String> specificationUsedTypes =
                    writeSpecificationTypesList(acslDir, cDir, acslFiles, abstractMachineRoots);

            List<Path> cFiles = findCFiles(cDir);

            if (cFiles.isEmpty() && !MOCK_MODE) {
                System.err.println("[B2ACSL] Nenhum arquivo .c encontrado em: " + cDir);
                return 4;
            }

            // Step 3: Executar Frama-C (acsl-importer + WP)
            int framaResult;
            String projectName = inferProjectNameFromBdp(bdp);
            if (MOCK_MODE) {
                framaResult = runMockFramaC(topLevelAcslFiles, cFiles, cDir);
            } else {
                WpOptions wpOptions = WpOptionsDialog.promptWpOptions(projectName);
                if (wpOptions == null) {
                    System.err.println("[B2ACSL] Execution cancelled by user.");
                    return 7;
                }
                String selectedProjectName = wpOptions.projectName();
                framaResult =
                        runFramaC(
                                topLevelAcslFiles,
                                acslFiles,
                                acslDir,
                                seesGraph,
                                importsGraph,
                                cFiles,
                                cDir,
                                specificationUsedTypes,
                                wpOptions,
                                selectedProjectName);
            }

            // Step 4: Retornar valor para Atelier B
            return framaResult;
        } finally {
            if (!keepFiles) deleteRecursive(acslDir);
        }
    }

    /** {@code nomeDaMaquina -> nomeEm<Abstraction>} para seguir a cadeia até à abstrata raiz. */
    private static Map<String, String> buildAbstractionParentMap(List<Path> bxmlFiles) throws Exception {
        Map<String, String> map = new HashMap<>();
        for (Path f : bxmlFiles) {
            try {
                Element root = AcslGenerator.parseMachineElement(f);
                String name = root.getAttribute("name");
                if (name == null || name.isBlank()) continue;
                AcslGenerator.getAbstractionReferenceName(root)
                        .ifPresent(parent -> map.put(name.trim(), parent.trim()));
            } catch (Exception ignored) {
                // ficheiro ignorado; já reportado ao ler Machine
            }
        }
        return map;
    }

    /**
     * Segue {@code <Abstraction>} até à máquina que não referencia outra (raiz da cadeia de refinamento).
     */
    private static String resolveRootAbstractName(String machineName, Map<String, String> parentOf) {
        String current = machineName;
        for (int i = 0; i < 256; i++) {
            String p = parentOf.get(current);
            if (p == null || p.isBlank()) return current;
            current = p;
        }
        return current;
    }

    /** Número de saltos até à raiz (refinamento = 1, implementação sobre refinamento = 2, …). */
    private static int refinementDepthToRoot(String machineName, Map<String, String> parentOf) {
        int d = 0;
        String current = machineName;
        for (int i = 0; i < 256; i++) {
            String p = parentOf.get(current);
            if (p == null || p.isBlank()) return d;
            d++;
            current = p;
        }
        return d;
    }

    private static String bdpPathToString(Path p) {
        return p.toString().replace('\\', '/');
    }

    private static List<Path> findBxmlFiles(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".bxml"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    /**
     * Passo 2.1: identifica tipos ACSL e tipos B ({@code TypeInfos}) usados na especificação e grava
     * {@link SpecificationTypesCollector#OUTPUT_FILE_NAME} em {@code acslDir} e em {@code cDir}.
     */
    private static List<String> writeSpecificationTypesList(
            Path acslDir,
            Path cDir,
            List<Path> acslFiles,
            List<Element> abstractMachineRoots)
            throws IOException {
        List<String> specTexts = new ArrayList<>();
        for (Path p : acslFiles) {
            if (Files.isRegularFile(p)) {
                specTexts.add(Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        Path ghostCi = GhostOperationsCiGenerator.targetPath(cDir);
        if (Files.isRegularFile(ghostCi)) {
            specTexts.add(Files.readString(ghostCi, StandardCharsets.UTF_8));
        }
        List<String> types =
                SpecificationTypesCollector.collectUsedTypes(specTexts, abstractMachineRoots);
        Path typesInAcslDir = acslDir.resolve(SpecificationTypesCollector.OUTPUT_FILE_NAME);
        SpecificationTypesCollector.writeTypesList(typesInAcslDir, types);
        Path typesInCDir = cDir.resolve(SpecificationTypesCollector.OUTPUT_FILE_NAME);
        if (!typesInCDir.toAbsolutePath().normalize().equals(typesInAcslDir.toAbsolutePath().normalize())) {
            SpecificationTypesCollector.writeTypesList(typesInCDir, types);
        }
        if (KEEP_ACSL_DIR != null && !KEEP_ACSL_DIR.isBlank()) {
            System.out.println("[B2ACSL] Tipos da especificação: " + typesInAcslDir);
            if (!typesInAcslDir.equals(typesInCDir)) {
                System.out.println("  (cópia em " + typesInCDir + ")");
            }
        }
        return types;
    }

    /** Saída gerada pelo Frama-C {@code -print} no mesmo diretório — não é ficheiro-fonte. */
    private static final String MERGED_CODE_FILE_NAME = "merged_code.c";

    private static List<Path> findCFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".c"))
                    .filter(p -> !MERGED_CODE_FILE_NAME.equalsIgnoreCase(p.getFileName().toString()))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static int runMockFramaC(List<Path> acslFiles, List<Path> cFiles, Path cDir) {
        System.out.println("[B2ACSL] [MOCK] ACSL gerados: " + acslFiles.size());
        System.out.println("[B2ACSL] [MOCK] Arquivos C: " + cFiles.size());
        System.out.println("[B2ACSL] [MOCK] Simulando acsl-importer + WP -> OK");
        return 0;
    }

    /**
     * Gera primeiro os {@code .acsl} das máquinas só vistas em {@code SEES} ou importadas em {@code
     * IMPORTS}, para a máquina que vê/importa poder fundir os respetivos {@code include} da biblioteca.
     */
    private static List<MachineFile> orderMachinesForAcslGeneration(
            List<MachineFile> machines, Set<String> dependencyOnlyMachineNames) {
        if (machines == null || machines.isEmpty()) {
            return List.of();
        }
        Set<String> dependencyOnly =
                dependencyOnlyMachineNames == null ? Set.of() : dependencyOnlyMachineNames;
        List<MachineFile> dependencyFirst = new ArrayList<>();
        List<MachineFile> rest = new ArrayList<>();
        List<MachineFile> refinements = new ArrayList<>();
        for (MachineFile mf : machines) {
            try {
                Element root = AcslGenerator.parseMachineElement(mf.bxmlPath());
                if (AcslGenerator.getAbstractionReferenceName(root).isPresent()) {
                    refinements.add(mf);
                    continue;
                }
                String name = mf.machine().getMachineName();
                if (dependencyOnly.contains(name)) {
                    dependencyFirst.add(mf);
                } else {
                    rest.add(mf);
                }
            } catch (Exception e) {
                rest.add(mf);
            }
        }
        List<MachineFile> ordered =
                new ArrayList<>(dependencyFirst.size() + rest.size() + refinements.size());
        ordered.addAll(dependencyFirst);
        ordered.addAll(rest);
        ordered.addAll(refinements);
        return ordered;
    }

    private static Set<String> dependencyOnlyMachineNames(
            BxmlSeesGraph seesGraph, BxmlImportsGraph importsGraph) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (seesGraph != null) {
            names.addAll(seesGraph.seenOnlyMachineNames());
        }
        if (importsGraph != null) {
            names.addAll(importsGraph.importedOnlyMachineNames());
        }
        return Set.copyOf(names);
    }

    private static List<String> topLevelImportMachineNames(
            Set<String> abstractMachineNames, Set<String> dependencyOnlyMachineNames) {
        if (abstractMachineNames == null || abstractMachineNames.isEmpty()) {
            return List.of();
        }
        Set<String> dependencyOnly =
                dependencyOnlyMachineNames == null ? Set.of() : dependencyOnlyMachineNames;
        List<String> out = new ArrayList<>();
        for (String name : abstractMachineNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String n = name.trim();
            if (!dependencyOnly.contains(n)) {
                out.add(n);
            }
        }
        return List.copyOf(out);
    }

    private static void logSeesRelations(BxmlSeesGraph seesGraph) {
        if (seesGraph == null) {
            return;
        }
        for (BxmlSeesGraph.SeesRelation r : seesGraph.relations()) {
            System.out.println("[B2ACSL] SEES: " + r.viewer() + " → " + r.seen());
        }
    }

    private static void logImportsRelations(BxmlImportsGraph importsGraph) {
        if (importsGraph == null) {
            return;
        }
        for (BxmlImportsGraph.ImportsRelation r : importsGraph.relations()) {
            System.out.println("[B2ACSL] IMPORTS: " + r.importer() + " → " + r.imported());
        }
    }

    private static List<Path> filterAcslFilesByMachineNames(
            List<Path> acslFiles, List<String> machineNames) {
        if (acslFiles == null || acslFiles.isEmpty()) {
            return List.of();
        }
        if (machineNames == null || machineNames.isEmpty()) {
            return List.copyOf(acslFiles);
        }
        Set<String> names = new LinkedHashSet<>(machineNames);
        return acslFiles.stream()
                .filter(
                        p -> {
                            String fn = p.getFileName().toString();
                            if (!fn.endsWith(".acsl")) {
                                return false;
                            }
                            String mn = fn.substring(0, fn.length() - ".acsl".length());
                            return names.contains(mn);
                        })
                .toList();
    }

    /**
     * Nome da máquina abstrata a partir do ficheiro C (ex. {@code Airlock_i.c} → {@code Airlock}).
     */
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
            return List.copyOf(topLevelAcslFiles);
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

    private static int runFramaC(
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

        Path mergedCode = cDir.resolve(MERGED_CODE_FILE_NAME);

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

        // frama-c -acsl-import <acsl>… [ghost_operations.ci] <c>… -print -no-unicode
        List<String> importCmd = new ArrayList<>();
        importCmd.add(FRAMA_C);
        importCmd.add("-acsl-import");
        for (Path acslImport : acslImportFiles) {
            importCmd.add(acslImport.toString());
        }
        if (Files.isRegularFile(ghostCi)) {
            importCmd.add(ghostCi.toString());
        }
        for (Path cFile : cFiles) {
            importCmd.add(cFile.toString());
        }
        importCmd.add("-print");
        importCmd.add("-no-unicode");
        System.out.println("[B2ACSL] Frama-C acsl-import: " + String.join(" ", importCmd));

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
        if (pImport.exitValue() != 0) {
            return pImport.exitValue();
        }

        stripLeadingFramaCNonCOutput(mergedCode);
        moveNewTypesAxiomaticBlockAfterPreamble(mergedCode);
        removeGhostPatternAxiomaticBlocks(mergedCode);
        stripDummyPrefixFromMergedCode(mergedCode);
        insertGhostVariableDeclarationsFromGhostCi(mergedCode, ghostCi);
        replaceAssertGhostWithGhostKeyword(mergedCode);
        replaceEnsuresGhostVarWithAssignsInMerged(mergedCode);
        addParenthesesToVoidGhostCalls(mergedCode);
        placeGhostOperationSpecsAboveFunctions(mergedCode, ghostCi);
        liftPureGhostEnsuresToOperationContracts(mergedCode);
        reorderLibAxiomaticBlocksPerAcslLibIncludesOrder(mergedCode);
        appendLemmasAcslLibToMergedEnd(mergedCode, allowedLibSymbolsForLemmas);
        SpecificationAxiomaticInstantiator.monomorphizeGenericAcslBlocks(
                mergedCode, specificationUsedTypes);
        SpecificationAxiomaticInstantiator.renameParameterizedTypesToConcrete(
                mergedCode, specificationUsedTypes);
        SpecificationAxiomaticInstantiator.normalizeLegacyMachineTypeIdentifiers(mergedCode);
        ensureSequenceListToFunctionDeclBeforeFirstUse(mergedCode);

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

        List<String> wpFunctionsToRun =
                operationFunctionNames.isEmpty()
                        ? List.of((String) null)
                        : operationFunctionNames;
        int failingExitCode = 0;
        for (String functionName : wpFunctionsToRun) {
            List<String> wpCmd =
                    buildWpCommand(mergedCode, wpOptions, functionName, cSourcesLabel);
            ProcessBuilder wpPb = new ProcessBuilder(wpCmd);
            wpPb.directory(cDir.toFile());
            wpPb.redirectErrorStream(true);

            ProcessResult wpResult = runProcessWithCapturedOutput(wpPb, 600, TimeUnit.SECONDS);
            String sourceName =
                    functionName == null
                            ? mergedCode.getFileName().toString() + " (" + cSourcesLabel + ")"
                            : functionName + " (" + mergedCode.getFileName().toString() + ")";
            reportData.absorbOutput(wpResult.output(), sourceName);
            if (!wpResult.completed()) {
                reportData.addTimeout(
                        "Timeout while executing WP"
                                + (functionName == null ? "" : " for function " + functionName)
                                + " (600s limit).");
                showVerificationReport(
                        projectName, mergedCode.getFileName().toString(), wpStartNanos, reportData);
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
            }
        }
        if (failingExitCode != 0) {
            showVerificationReport(projectName, mergedCode.getFileName().toString(), wpStartNanos, reportData);
            return failingExitCode;
        }
        showVerificationReport(projectName, mergedCode.getFileName().toString(), wpStartNanos, reportData);
        return 0;
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
        wpCmd.add("-wp-rte");
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
            ProcessBuilder processBuilder, long timeout, TimeUnit timeoutUnit)
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
            String projectName, String analyzedFileName, long startNanos, VerificationReportData reportData) {
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        FormalVerificationReportDialog.show(projectName, analyzedFileName, elapsedMs, reportData);
    }

    private static String inferProjectNameFromBdp(Path bdp) {
        Path normalized = bdp.toAbsolutePath().normalize();
        Path name = normalized.getFileName();
        if (name != null
                && "bdp".equalsIgnoreCase(name.toString())
                && normalized.getParent() != null
                && normalized.getParent().getFileName() != null) {
            return normalized.getParent().getFileName().toString();
        }
        return name != null ? name.toString() : "Project";
    }

    /**
     * Remove do início de {@code merged_code.c} linhas em branco e linhas de log Frama-C
     * {@code [etiqueta] …} até à primeira linha que não corresponde a esse padrão (código C / ACSL).
     */
    private static void stripLeadingFramaCNonCOutput(Path mergedC) throws IOException {
        List<String> lines = Files.readAllLines(mergedC, StandardCharsets.UTF_8);
        int start = 0;
        while (start < lines.size()) {
            String trimmed = lines.get(start).trim();
            if (trimmed.isEmpty()) {
                start++;
                continue;
            }
            if (FRAMA_C_STDOUT_TAG_LINE.matcher(trimmed).matches()) {
                start++;
                continue;
            }
            break;
        }
        if (start == 0) {
            return;
        }
        Files.write(mergedC, lines.subList(start, lines.size()), StandardCharsets.UTF_8);
    }

    /**
     * Coloca o comentário ACSL {@code axiomatic new_types} logo após o preâmbulo (comentário gerado,
     * {@code #include}, linhas em branco), antes dos restantes blocos {@code axiomatic} em ACSL.
     */
    private static void moveNewTypesAxiomaticBlockAfterPreamble(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        int typesKeywordIdx = content.indexOf(AXIOMATIC_NEW_TYPES_MARKER);
        if (typesKeywordIdx < 0) {
            return;
        }
        int blockStart = content.lastIndexOf("/*@", typesKeywordIdx);
        if (blockStart < 0) {
            return;
        }
        int openBrace = content.indexOf('{', typesKeywordIdx);
        if (openBrace < 0) {
            return;
        }
        int closeBrace = findMatchingBrace(content, openBrace);
        if (closeBrace < 0) {
            return;
        }
        int commentEnd = content.indexOf("*/", closeBrace);
        if (commentEnd < 0) {
            return;
        }
        int blockEnd = commentEnd + 2;
        blockEnd = skipNewlineAfter(blockEnd, content);

        String block = content.substring(blockStart, blockEnd);
        String without = content.substring(0, blockStart) + content.substring(blockEnd);
        int insertAt = findPreambleInsertIndex(without);
        String sepBefore =
                insertAt > 0 && without.charAt(insertAt - 1) != '\n' ? "\n" : "";
        String sepAfter = block.endsWith("\n") ? "" : "\n";
        String result = without.substring(0, insertAt) + sepBefore + block + sepAfter + without.substring(insertAt);
        Files.writeString(mergedC, result, StandardCharsets.UTF_8);
    }

    /**
     * Remove blocos ACSL {@code axiomatic dummy_ghost} e {@code axiomatic <M>_ghost_patterns} do merge
     * Frama-C (gerados a partir de {@code ghost_operations.ci}).
     */
    private static void removeGhostPatternAxiomaticBlocks(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        content = removeAllAxiomaticBlocksNamed(content, "dummy_ghost");
        Pattern ghostPatterns = Pattern.compile("axiomatic\\s+\\w+_ghost_patterns\\b");
        Matcher m = ghostPatterns.matcher(content);
        while (m.find()) {
            content = removeAxiomaticCommentBlockContaining(content, m.start());
            m = ghostPatterns.matcher(content);
        }
        Files.writeString(mergedC, content, StandardCharsets.UTF_8);
    }

    private static String removeAllAxiomaticBlocksNamed(String content, String axiomaticName) {
        String marker = "axiomatic " + axiomaticName;
        int idx;
        while ((idx = content.indexOf(marker)) >= 0) {
            content = removeAxiomaticCommentBlockContaining(content, idx);
        }
        return content;
    }

    /**
     * Remove o bloco de comentário ACSL (aberto com {@code slash-star-at}) que contém {@code keywordIdx}
     * (por exemplo o nome {@code axiomatic ...}).
     */
    private static String removeAxiomaticCommentBlockContaining(String content, int keywordIdx) {
        int blockStart = content.lastIndexOf("/*@", keywordIdx);
        if (blockStart < 0) {
            return content;
        }
        int openBrace = content.indexOf('{', keywordIdx);
        if (openBrace < 0) {
            return content;
        }
        int closeBrace = findMatchingBrace(content, openBrace);
        if (closeBrace < 0) {
            return content;
        }
        int commentEnd = content.indexOf("*/", closeBrace);
        if (commentEnd < 0) {
            return content;
        }
        int blockEnd = skipNewlineAfter(commentEnd + 2, content);
        return content.substring(0, blockStart) + content.substring(blockEnd);
    }

    /** Remove o prefixo {@code dummy_} de identificadores no C/ACSL fundido. */
    private static void stripDummyPrefixFromMergedCode(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        content = content.replaceAll("\\bdummy_", "");
        Files.writeString(mergedC, content, StandardCharsets.UTF_8);
    }

    /**
     * Após {@link #stripDummyPrefixFromMergedCode}, {@code ensures dummy_ghost_<v>} passa a
     * {@code ensures ghost_<v>}; troca por {@code assigns ghost_<v>;} (variáveis ghost no merge).
     * Inclui a forma que o Frama-C emite para não-nulo: {@code ensures ghost_<v> != 0;}.
     */
    private static final Pattern ENSURES_GHOST_VAR =
            Pattern.compile("ensures\\s+(ghost_\\w+)\\s*(?:;|!=\\s*0\\s*;)");

    private static void replaceEnsuresGhostVarWithAssignsInMerged(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        content = ENSURES_GHOST_VAR.matcher(content).replaceAll("assigns $1;");
        Files.writeString(mergedC, content, StandardCharsets.UTF_8);
    }

    /**
     * Coloca no início do ficheiro (logo após o preâmbulo) as linhas de declaração ghost lidas de
     * {@code ghost_operations.ci}, removendo duplicados equivalentes já presentes no merge.
     */
    private static void insertGhostVariableDeclarationsFromGhostCi(Path mergedC, Path ghostCi)
            throws IOException {
        if (!Files.isRegularFile(ghostCi)) {
            return;
        }
        List<String> ghostLines = new ArrayList<>();
        for (String line : Files.readAllLines(ghostCi, StandardCharsets.UTF_8)) {
            String t = line.stripLeading();
            if (t.startsWith("//@") && t.contains("ghost")) {
                ghostLines.add(line.stripTrailing());
            }
        }
        if (ghostLines.isEmpty()) {
            return;
        }
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        content = removeExistingGhostVariableDeclarationsFromMerged(content);
        int insertAt = findPreambleInsertIndex(content);
        String block = String.join("\n", ghostLines);
        String sepBefore = insertAt > 0 && content.charAt(insertAt - 1) != '\n' ? "\n" : "";
        String sepAfter = insertAt < content.length() && content.charAt(insertAt) != '\n' ? "\n" : "";
        String result = content.substring(0, insertAt) + sepBefore + block + "\n" + sepAfter + content.substring(insertAt);
        Files.writeString(mergedC, result, StandardCharsets.UTF_8);
    }

    private static final Pattern GHOST_ANNOTATION_LINE = Pattern.compile("(?m)^\\s*//@\\s+ghost[^\\n]*\\R?");

    /**
     * Bloco {@code /*@ ghost ... star-slash} — candidato a duplicado de <strong>variável</strong> ghost
     * no merge; contratos de operações (assigns, ensures, {@code void} …) são filtrados em
     * {@link #shouldRemoveDuplicateGhostVarBlock}.
     */
    private static final Pattern GHOST_VAR_BLOCK_COMMENT =
            Pattern.compile("/\\*@\\s*ghost\\b[\\s\\S]*?\\*/\\s*\\R?");

    /**
     * Remove do merge só duplicados de declaração de variável ghost ({@code //@ ghost int …} ou bloco
     * ACSL equivalente). Não remove contratos / protótipos de operações ghost não puras.
     */
    private static String removeExistingGhostVariableDeclarationsFromMerged(String content) {
        content = GHOST_ANNOTATION_LINE.matcher(content).replaceAll("");
        Matcher m = GHOST_VAR_BLOCK_COMMENT.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String block = m.group();
            if (shouldRemoveDuplicateGhostVarBlock(block)) {
                m.appendReplacement(sb, "");
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(block));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * {@code true} se o bloco for só declaração de variável ghost a eliminar antes de reinserir a linha
     * vinda de {@code ghost_operations.ci}; {@code false} para contratos (assigns, ensures, …) ou
     * protótipos {@code void op(...)}.
     */
    private static boolean shouldRemoveDuplicateGhostVarBlock(String block) {
        if (block.contains("assigns")
                || block.contains("ensures")
                || block.contains("requires")
                || block.contains("assumes")
                || block.contains("behavior")) {
            return false;
        }
        if (block.contains("/@") || block.contains("@/")) {
            return false;
        }
        if (Pattern.compile("\\bvoid\\s+[A-Za-z_]\\w*\\s*\\(").matcher(block).find()) {
            return false;
        }
        return true;
    }

    /** Substitui {@code assert ghost__} por {@code ghost } nas anotações Frama-C. */
    private static void replaceAssertGhostWithGhostKeyword(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        content = content.replace("assert ghost__", "ghost ");
        Files.writeString(mergedC, content, StandardCharsets.UTF_8);
    }

    /** Garante {@code ghost op();} em vez de {@code ghost op;} para chamadas ghost sem argumentos. */
    private static void addParenthesesToVoidGhostCalls(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        content =
                Pattern.compile("\\bghost\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;")
                        .matcher(content)
                        .replaceAll("ghost $1();");
        Files.writeString(mergedC, content, StandardCharsets.UTF_8);
    }

    private static final Pattern GHOST_OP_BLOCK_IN_CI =
            Pattern.compile("(?s)/\\*@\\s*ghost\\b.*?\\bvoid\\s+([A-Za-z_]\\w*)\\s*\\([^;{}]*\\)\\s*;\\s*\\*/");
    private static final Pattern ACSL_OPERATION_CONTRACT_FUNCTION =
            Pattern.compile("(?m)^\\s*function\\s+([A-Za-z_]\\w*)\\s*:");
    private static final Pattern GHOST_INITIALISATION_BLOCK_IN_CI =
            Pattern.compile("(?s)/\\*@\\s*ghost\\b.*?\\bvoid\\s+initialisation\\s*\\([^;{}]*\\)\\s*;\\s*\\*/");
    private static final Pattern GHOST_INITIALISATION_BLOCK_IN_MERGED =
            Pattern.compile("(?s)/\\*@\\s*ghost\\b.*?\\bvoid\\s+initialisation\\s*\\([^;{}]*\\)\\s*;\\s*\\*/\\s*");
    private static final Pattern INITIALISATION_FUNCTION_DEFINITION =
            Pattern.compile("\\bvoid\\s+[A-Za-z_]\\w*__INITIALISATION\\s*\\([^;{}]*\\)\\s*\\{");
    private static final String INITIALISATION_GHOST_CALL_MARKER = "/*@ ghost initialisation(); */";

    /**
     * Novo passo pré-WP: move especificações ghost de operações para imediatamente acima da função C
     * correspondente (ex.: bloco {@code initialisation} acima de {@code Deck__INITIALISATION}).
     */
    private static void placeGhostOperationSpecsAboveFunctions(Path mergedC, Path ghostCi) throws IOException {
        if (!Files.isRegularFile(ghostCi)) {
            return;
        }
        String merged = Files.readString(mergedC, StandardCharsets.UTF_8);
        String ghostText =
                GhostOperationsCiGenerator.normalizeIntegerBoolComparisonsInMergedGhostSpecs(
                        GhostOperationsCiGenerator.stripDummyPrefixForMergedGhostSpecs(
                                Files.readString(ghostCi, StandardCharsets.UTF_8)));

        Matcher bm = GHOST_OP_BLOCK_IN_CI.matcher(ghostText);
        List<String> opNames = new ArrayList<>();
        List<String> blocks = new ArrayList<>();
        while (bm.find()) {
            opNames.add(bm.group(1));
            blocks.add(bm.group().stripTrailing() + "\n\n");
        }
        if (opNames.isEmpty()) {
            return;
        }

        // Remove blocos ghost de operação já presentes para evitar duplicação.
        Matcher existingGhostBlocks = GHOST_VAR_BLOCK_COMMENT.matcher(merged);
        StringBuilder cleaned = new StringBuilder();
        while (existingGhostBlocks.find()) {
            String block = existingGhostBlocks.group();
            if (Pattern.compile("\\bvoid\\s+[A-Za-z_]\\w*\\s*\\(").matcher(block).find()) {
                existingGhostBlocks.appendReplacement(cleaned, "");
            } else {
                existingGhostBlocks.appendReplacement(cleaned, Matcher.quoteReplacement(block));
            }
        }
        existingGhostBlocks.appendTail(cleaned);
        merged = cleaned.toString();

        for (int i = 0; i < opNames.size(); i++) {
            String opName = opNames.get(i);
            String ghostBlock = blocks.get(i);
            if ("initialisation".equalsIgnoreCase(opName)) {
                merged = placeInitialisationGhostAndContract(merged, ghostBlock);
            } else {
                int insertAt = findFunctionStartForGhostSpec(merged, opName);
                if (insertAt < 0) {
                    continue;
                }
                merged = merged.substring(0, insertAt) + ghostBlock + merged.substring(insertAt);
            }
        }
        Files.writeString(mergedC, merged, StandardCharsets.UTF_8);
    }

    /**
     * Tratamento especial para {@code initialisation}: o Frama-C costuma colocar o contrato de
     * {@code Deck__INITIALISATION} no topo do ficheiro, separado da sua definição. Este método
     * remove o contrato flutuante e o reinsere, junto com o bloco ghost, imediatamente acima da
     * definição da função, produzindo a ordem: bloco-ghost → contrato → definição.
     */
    private static String placeInitialisationGhostAndContract(String content, String ghostBlock) {
        // Find the function definition (with body).
        Matcher defMatcher = INITIALISATION_FUNCTION_DEFINITION.matcher(content);
        if (!defMatcher.find()) {
            return content;
        }
        int defStart = defMatcher.start();

        // Check if a contract is already immediately adjacent (just before the definition).
        int lastSpecStart = content.lastIndexOf("/*@", defStart);
        if (lastSpecStart >= 0) {
            int lastSpecEnd = content.indexOf("*/", lastSpecStart);
            if (lastSpecEnd >= 0 && lastSpecEnd < defStart) {
                String between = content.substring(lastSpecEnd + 2, defStart).trim();
                if (between.isEmpty()) {
                    // Contract is adjacent — just insert ghost block above the contract.
                    int lineStart = content.lastIndexOf('\n', lastSpecStart);
                    int insertAt = lineStart < 0 ? 0 : lineStart + 1;
                    return content.substring(0, insertAt) + ghostBlock + content.substring(insertAt);
                }
            }
        }

        // Contract is NOT adjacent. Look for a floating ensures/assigns contract before the definition.
        Pattern floatingContractPat =
                Pattern.compile("(?s)/\\*@(?!\\s*(?:ghost|axiomatic)\\b)([\\s\\S]*?)\\*/");
        Matcher fcm = floatingContractPat.matcher(content.substring(0, defStart));
        String floatingContract = null;
        int fcStart = -1;
        int fcEnd = -1;
        while (fcm.find()) {
            String blk = fcm.group();
            if (blk.contains("ensures") || blk.contains("assigns")) {
                floatingContract = blk;
                fcStart = fcm.start();
                fcEnd = fcm.end();
            }
        }

        if (floatingContract == null) {
            // No floating contract found — just insert ghost block before the definition.
            int lineStart = content.lastIndexOf('\n', defStart);
            int insertAt = lineStart < 0 ? 0 : lineStart + 1;
            return content.substring(0, insertAt) + ghostBlock + content.substring(insertAt);
        }

        // Expand removal range to consume surrounding blank lines neatly.
        int removeStart = fcStart;
        while (removeStart > 0 && content.charAt(removeStart - 1) == '\n') {
            removeStart--;
        }
        int removeEnd = fcEnd;
        while (removeEnd < content.length() && content.charAt(removeEnd) == '\n') {
            removeEnd++;
        }

        String withoutContract = content.substring(0, removeStart) + content.substring(removeEnd);

        // Re-locate definition (offsets shifted after removal).
        Matcher defMatcher2 = INITIALISATION_FUNCTION_DEFINITION.matcher(withoutContract);
        if (!defMatcher2.find()) {
            return content; // Safety fallback — restore original.
        }
        int newDefStart = defMatcher2.start();
        int lineStart = withoutContract.lastIndexOf('\n', newDefStart);
        int insertAt = lineStart < 0 ? 0 : lineStart + 1;

        String toInsert = ghostBlock + floatingContract + "\n";
        return withoutContract.substring(0, insertAt) + toInsert + withoutContract.substring(insertAt);
    }

    private static int findFunctionStartForGhostSpec(String content, String opName) {
        if (content == null || content.isBlank() || opName == null || opName.isBlank()) {
            return -1;
        }
        List<Pattern> candidates = new ArrayList<>();
        if ("initialisation".equalsIgnoreCase(opName)) {
            candidates.add(
                    Pattern.compile(
                            "\\bvoid\\s+[A-Za-z_]\\w*__INITIALISATION\\s*\\([^;{}]*\\)\\s*\\{"));
        }
        candidates.add(
                Pattern.compile(
                        "\\bvoid\\s+[A-Za-z_]\\w*__(?i:"
                                + Pattern.quote(opName)
                                + ")\\s*\\([^;{}]*\\)\\s*\\{"));
        for (Pattern p : candidates) {
            Matcher m = p.matcher(content);
            if (!m.find()) {
                continue;
            }
            int idx = m.start();
            int specStart = content.lastIndexOf("/*@", idx);
            if (specStart >= 0) {
                int specEnd = content.indexOf("*/", specStart);
                if (specEnd >= 0 && specEnd < idx) {
                    String between = content.substring(specEnd + 2, idx).trim();
                    if (between.isEmpty()) {
                        idx = specStart;
                    }
                }
            }
            int lineStart = content.lastIndexOf('\n', idx);
            return lineStart < 0 ? 0 : lineStart + 1;
        }
        return -1;
    }

    /**
     * Para operações cujo contrato ghost não altera variáveis abstratas
     * ({@code assigns \nothing;}), remove o bloco ghost e injeta os seus
     * {@code ensures} no contrato normal da operação imediatamente adjacente.
     */
    private static void liftPureGhostEnsuresToOperationContracts(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        // Group 1 uses (?:[^*]|\*(?!/))* instead of [\s\S]*? to prevent the lazy quantifier from
        // backtracking past the closing */ of the ghost annotation. Without this, a short ghost
        // call like /*@ ghost add(ee); */ inside a function body could extend (via backtracking)
        // all the way to the next /*@…*/ block, consuming the C body of the preceding function.
        Pattern ghostThenNormalBeforeDefinition =
                Pattern.compile(
                        "(/\\*@\\s*ghost\\b(?:[^*]|\\*(?!/))*\\*/\\s*)"
                                + "(?s)(/\\*@(?!\\s*(?:ghost|axiomatic)\\b)[\\s\\S]*?\\*/\\s*)"
                                + "(void\\s+[A-Za-z_]\\w*__([A-Za-z_]\\w*)\\s*\\([^;{}]*\\)\\s*\\{)");
        Pattern pureGhostAssignsNothing =
                Pattern.compile("(?m)^\\s*/?@\\s*assigns\\s+\\\\nothing\\s*;");
        Pattern ghostEnsureLine = Pattern.compile("(?m)^\\s*@\\s*ensures\\s+(.+?)\\s*;\\s*$");

        Matcher m = ghostThenNormalBeforeDefinition.matcher(content);
        StringBuilder sb = new StringBuilder();
        Set<String> liftedOps = new LinkedHashSet<>();
        while (m.find()) {
            String ghostBlock = m.group(1);
            String normalContract = m.group(2);
            String functionDef = m.group(3);
            String opSuffix = m.group(4);

            if (!pureGhostAssignsNothing.matcher(ghostBlock).find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }

            Matcher em = ghostEnsureLine.matcher(ghostBlock);
            List<String> ensuresToLift = new ArrayList<>();
            while (em.find()) {
                ensuresToLift.add(em.group(1).trim());
            }
            if (ensuresToLift.isEmpty()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(normalContract + functionDef));
                continue;
            }

            int contractEnd = normalContract.lastIndexOf("*/");
            if (contractEnd < 0) {
                m.appendReplacement(sb, Matcher.quoteReplacement(normalContract + functionDef));
                continue;
            }

            StringBuilder liftedEnsures = new StringBuilder();
            for (String ensure : ensuresToLift) {
                liftedEnsures.append("  @ ensures ").append(ensure).append(";\n");
            }
            String mergedContract =
                    normalContract.substring(0, contractEnd)
                            + liftedEnsures
                            + normalContract.substring(contractEnd);
            liftedOps.add(opSuffix);
            m.appendReplacement(sb, Matcher.quoteReplacement(mergedContract + functionDef));
        }
        m.appendTail(sb);
        String rewritten = sb.toString();
        for (String opSuffix : liftedOps) {
            rewritten = removeGhostCallFromMergedCode(rewritten, opSuffix);
        }
        Files.writeString(mergedC, rewritten, StandardCharsets.UTF_8);
    }

    /**
     * Remove do merge a chamada de anotação ghost
     * (ex.: {@code slash-star-at ghost op(...); star-slash}) referente à própria operação quando
     * o contrato ghost foi incorporado ao contrato normal.
     */
    private static String removeGhostCallFromMergedCode(String content, String opSuffix) {
        if (content == null || content.isEmpty() || opSuffix == null || opSuffix.isBlank()) {
            return content;
        }
        Pattern ghostCall =
                Pattern.compile(
                        "/\\*@\\s*ghost\\s+"
                                + Pattern.quote(opSuffix)
                                + "\\s*\\([^;{}]*\\)\\s*;\\s*\\*/\\s*");
        return ghostCall.matcher(content).replaceAll("");
    }

    /**
     * Garante especificamente o bloco ghost de {@code initialisation} imediatamente acima de
     * {@code Deck__INITIALISATION} (ou equivalente), mesmo se etapas anteriores falharem em casos
     * degenerados do merge.
     */
    private static void enforceInitialisationGhostSpecPlacement(Path mergedC, Path ghostCi) throws IOException {
        if (!Files.isRegularFile(ghostCi)) {
            return;
        }
        String ghostText =
                GhostOperationsCiGenerator.normalizeIntegerBoolComparisonsInMergedGhostSpecs(
                        GhostOperationsCiGenerator.stripDummyPrefixForMergedGhostSpecs(
                                Files.readString(ghostCi, StandardCharsets.UTF_8)));
        Matcher gm = GHOST_INITIALISATION_BLOCK_IN_CI.matcher(ghostText);
        if (!gm.find()) {
            return;
        }
        String initGhostBlock = gm.group().stripTrailing() + "\n\n";

        String merged = Files.readString(mergedC, StandardCharsets.UTF_8);
        merged = GHOST_INITIALISATION_BLOCK_IN_MERGED.matcher(merged).replaceAll("");

        int insertAt = findInitialisationAnchorBeforeDefinition(merged);
        if (insertAt < 0) {
            insertAt = findInitialisationAnchorFromGhostCall(merged);
        }
        if (insertAt < 0) {
            Files.writeString(mergedC, merged, StandardCharsets.UTF_8);
            return;
        }
        merged = merged.substring(0, insertAt) + initGhostBlock + merged.substring(insertAt);
        Files.writeString(mergedC, merged, StandardCharsets.UTF_8);
    }

    private static int findInitialisationAnchorBeforeDefinition(String content) {
        Matcher def = INITIALISATION_FUNCTION_DEFINITION.matcher(content);
        if (!def.find()) {
            return -1;
        }
        int idx = def.start();
        int specStart = content.lastIndexOf("/*@", idx);
        if (specStart >= 0) {
            int specEnd = content.indexOf("*/", specStart);
            if (specEnd >= 0 && specEnd < idx) {
                String between = content.substring(specEnd + 2, idx).trim();
                if (between.isEmpty()) {
                    idx = specStart;
                }
            }
        }
        int lineStart = content.lastIndexOf('\n', idx);
        return lineStart < 0 ? 0 : lineStart + 1;
    }

    /**
     * Fallback robusto: localiza o marcador {@code ghost initialisation();} no corpo da função e
     * recua até a definição de {@code __INITIALISATION}, ancorando acima da especificação contígua.
     */
    private static int findInitialisationAnchorFromGhostCall(String content) {
        int callIdx = content.indexOf(INITIALISATION_GHOST_CALL_MARKER);
        if (callIdx < 0) {
            return -1;
        }
        Matcher def = INITIALISATION_FUNCTION_DEFINITION.matcher(content);
        int defStart = -1;
        while (def.find()) {
            if (def.start() > callIdx) {
                break;
            }
            defStart = def.start();
        }
        if (defStart < 0) {
            return -1;
        }
        int idx = defStart;
        int specStart = content.lastIndexOf("/*@", idx);
        if (specStart >= 0) {
            int specEnd = content.indexOf("*/", specStart);
            if (specEnd >= 0 && specEnd < idx) {
                String between = content.substring(specEnd + 2, idx).trim();
                if (between.isEmpty()) {
                    idx = specStart;
                }
            }
        }
        int lineStart = content.lastIndexOf('\n', idx);
        return lineStart < 0 ? 0 : lineStart + 1;
    }

    /**
     * Garante a ordem canónica imediatamente antes da definição de função:
     * bloco ghost da operação -> bloco de especificação da função -> definição com corpo.
     */
    private static void normalizeGhostBlockOrderBeforeFunctionDefinitions(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        Pattern p =
                Pattern.compile(
                        "(?s)(/\\*@\\s*(?!ghost\\b)[\\s\\S]*?\\*/\\s*)" // spec normal
                                + "(/\\*@\\s*ghost\\b[\\s\\S]*?\\*/\\s*)" // spec ghost
                                + "(void\\s+[A-Za-z_]\\w*__\\w+\\s*\\([^;{}]*\\)\\s*\\{)"); // definição
        Matcher m = p.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String replacement = m.group(2) + m.group(1) + m.group(3);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        Files.writeString(mergedC, sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Passo 6: reordena no merge os blocos {@code /*@ axiomatic X { ... } star-slash} que declaram
     * {@code logic} ou {@code predicate}, quando {@code X} pertence a
     * {@link AcslLibIncludes#orderedLibFunctionAxiomaticNames()}; coloca {@code mapping_function} após
     * {@code new_types}; acopla cada {@code X_axioms} ao axiomatic pai {@code X}; agrupa blocos de
     * sequência ({@code sequence_is_seq_of}, {@code range_function}, {@code sequence_iseq}) na ordem da
     * lib; e antecipa qualquer bloco da lib (incluindo {@code *_axioms}) que o Frama-C tenha deixado
     * depois de {@code Connection_*}, para declarações como {@code is_seq_of} existirem antes do uso.
     */
    private static void reorderLibAxiomaticBlocksPerAcslLibIncludesOrder(Path mergedC) throws IOException {
        List<String> orderedNames = AcslLibIncludes.orderedLibFunctionAxiomaticNames();
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        Map<String, Integer> rank = new HashMap<>();
        if (!orderedNames.isEmpty()) {
            for (int i = 0; i < orderedNames.size(); i++) {
                rank.put(orderedNames.get(i), i);
            }
            content = reorderLibAxiomaticBlocksInMerged(content, rank);
        }
        content = placeMappingFunctionImmediatelyAfterNewTypes(content);
        content = clusterSequenceAxiomaticsAfterIsSeqOf(content);
        content = attachAxiomsBlocksAfterParentAxiomatic(content);
        if (!rank.isEmpty()) {
            content = moveLibAxiomaticBlocksBeforeConnection(content, rank);
        }
        Files.writeString(mergedC, content, StandardCharsets.UTF_8);
    }

    /**
     * Passo 10–11: lê {@code ACSL_Lib/lemmas.acsl}; passo 11 remove cada {@code admit lemma} que invoque
     * (como chamada {@code nome(}) algum símbolo de biblioteca mapeado em {@link AcslLibIncludes} que não
     * esteja no fecho de includes deduzido do texto dos ficheiros {@code .acsl} importados e do
     * {@code ghost_operations.ci}. Passo 10 anexa o resultado ao fim de {@code merged_code.c} num
     * comentário ACSL.
     *
     * @param allowedLibSymbols símbolos permitidos (ex.: {@code belongs}, {@code ran}); vazio → não anexa
     */
    private static void appendLemmasAcslLibToMergedEnd(Path mergedC, Set<String> allowedLibSymbols)
            throws IOException {
        if (allowedLibSymbols == null || allowedLibSymbols.isEmpty()) {
            return;
        }
        Optional<String> lemmas = readAcslLibLemmasText();
        if (lemmas.isEmpty() || lemmas.get().isBlank()) {
            return;
        }
        String filtered = filterLemmasByAllowedLibSymbols(lemmas.get(), allowedLibSymbols);
        if (filtered.isBlank()) {
            return;
        }
        // filtered already contains individually wrapped /*@ axiomatic X { ... } */ blocks
        String existing = Files.readString(mergedC, StandardCharsets.UTF_8);
        String sep = existing.endsWith("\n") ? "" : "\n";
        Files.writeString(mergedC, existing + sep + filtered, StandardCharsets.UTF_8);
    }

    /**
     * Chamadas {@code identificador(} típicas da ACSL_Lib (identificador ASCII minúsculo, não precedido
     * de {@code \} nem de carácter alfanumérico).
     */
    private static final Pattern LEMMA_LIB_STYLE_CALL =
            Pattern.compile("(?<![A-Za-z0-9_\\\\])([a-z][a-z0-9_]*)\\s*\\(");

    private static final Pattern LEMMA_AXIOMATIC_HEADER =
            Pattern.compile("(?m)^\\s*axiomatic\\s+(\\w+)\\s*\\{");

    /**
     * Mantém apenas lemas cujo corpo não contém chamadas a símbolos de lib fora de {@code allowed}.
     *
     * <p>Processa cada bloco {@code axiomatic} do ficheiro de lemas individualmente, emitindo
     * cada um como um bloco {@code /*@ axiomatic X \{ … \} *\/} separado. Isso evita que o
     * conteúdo estrutural (fechamentos {@code \}} e cabeçalhos {@code axiomatic Name \{}) dos
     * blocos intermédios seja copiado repetidamente durante a monomorphização genérica.
     */
    private static String filterLemmasByAllowedLibSymbols(String lemmasFileText, Set<String> allowed) {
        Matcher headerMatcher = LEMMA_AXIOMATIC_HEADER.matcher(lemmasFileText);
        StringBuilder result = new StringBuilder();

        while (headerMatcher.find()) {
            String blockName = headerMatcher.group(1);
            int bodyStart = headerMatcher.end(); // posição após '{'

            // Encontra o '}' correspondente (profundidade de chaves)
            int depth = 1;
            int pos = bodyStart;
            while (pos < lemmasFileText.length() && depth > 0) {
                char c = lemmasFileText.charAt(pos);
                if (c == '{') depth++;
                else if (c == '}') depth--;
                pos++;
            }
            if (depth != 0) continue; // chave sem par — ignora

            String blockBody = lemmasFileText.substring(bodyStart, pos - 1);

            // Filtra os lemas deste bloco individualmente
            String[] chunks = blockBody.split("(?m)(?=^\\s*admit\\s+lemma\\s+)");
            List<String> kept = new ArrayList<>();
            for (String chunk : chunks) {
                String t = chunk.trim();
                if (t.isEmpty() || !t.startsWith("admit")) continue;
                if (!lemmaBodyUsesDisallowedLibCall(t, allowed)) {
                    kept.add(t);
                }
            }
            if (kept.isEmpty()) continue;

            // Cada bloco axiomatic é emitido como um /*@ ... */ independente
            result.append("/*@\n")
                    .append("axiomatic ").append(blockName).append(" {\n\n    ")
                    .append(String.join("\n\n    ", kept))
                    .append("\n\n}\n */\n");
        }

        return result.toString();
    }

    private static boolean lemmaBodyUsesDisallowedLibCall(String admitLemmaChunk, Set<String> allowed) {
        Matcher m = LEMMA_LIB_STYLE_CALL.matcher(admitLemmaChunk);
        while (m.find()) {
            String name = m.group(1);
            if (allowed.contains(name)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static Optional<String> readAcslLibLemmasText() throws IOException {
        try (InputStream in = B2ACSLPipeline.class.getResourceAsStream(ACSL_LIB_LEMMAS_RESOURCE)) {
            if (in != null) {
                return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        Path dev = B2AcslLibraryPaths.devRoot().resolve("lemmas.acsl");
        if (Files.isRegularFile(dev)) {
            return Optional.of(Files.readString(dev, StandardCharsets.UTF_8));
        }
        return Optional.empty();
    }

    /**
     * Ordem {@link AcslLibIncludes#FILE_ORDER} para ficheiros de sequência: {@code is_seq_of.acsl},
     * {@code range.acsl}, {@code iseq.acsl}.
     */
    private static final List<String> SEQUENCE_CLUSTER_AFTER_IS_SEQ_OF =
            List.of("range_function", "sequence_iseq");

    /**
     * Garante {@code range_function} e {@code sequence_iseq} (se existirem) imediatamente após
     * {@code sequence_is_seq_of}, alinhado à ordem dos includes da lib.
     */
    private static String clusterSequenceAxiomaticsAfterIsSeqOf(String content) {
        List<AcsCommentSpan> spans = findAllAcsCommentSpans(content);
        AcsCommentSpan isSeqOf = null;
        Map<String, AcsCommentSpan> followers = new HashMap<>();
        for (AcsCommentSpan sp : spans) {
            if (sp.axiomaticName == null || !LOGIC_OR_PREDICATE_IN_BLOCK.matcher(sp.text).find()) {
                continue;
            }
            if ("sequence_is_seq_of".equals(sp.axiomaticName) && isSeqOf == null) {
                isSeqOf = sp;
            } else if (SEQUENCE_CLUSTER_AFTER_IS_SEQ_OF.contains(sp.axiomaticName)
                    && !followers.containsKey(sp.axiomaticName)) {
                followers.put(sp.axiomaticName, sp);
            }
        }
        if (isSeqOf == null || followers.isEmpty()) {
            return content;
        }
        List<AcsCommentSpan> toRemove = new ArrayList<>(followers.values());
        for (AcsCommentSpan follower : followers.values()) {
            collectAxiomsBlocksForParent(spans, follower.axiomaticName, toRemove);
        }
        toRemove.sort(Comparator.comparingInt((AcsCommentSpan s) -> s.start).reversed());
        String cut = content;
        for (AcsCommentSpan sp : toRemove) {
            cut = cut.substring(0, sp.start) + cut.substring(sp.end);
        }
        spans = findAllAcsCommentSpans(cut);
        AcsCommentSpan anchor = null;
        for (AcsCommentSpan sp : spans) {
            if ("sequence_is_seq_of".equals(sp.axiomaticName)
                    && LOGIC_OR_PREDICATE_IN_BLOCK.matcher(sp.text).find()) {
                anchor = sp;
                break;
            }
        }
        if (anchor == null) {
            return cut;
        }
        StringBuilder insert = new StringBuilder();
        for (String name : SEQUENCE_CLUSTER_AFTER_IS_SEQ_OF) {
            AcsCommentSpan sp = followers.get(name);
            if (sp == null) {
                continue;
            }
            insert.append(sp.text);
            for (AcsCommentSpan ax : toRemove) {
                if (ax.axiomaticName == null || !ax.axiomaticName.endsWith("_axioms")) {
                    continue;
                }
                if (name.equals(resolveParentAxiomaticName(ax.axiomaticName, spans))) {
                    insert.append(ax.text);
                }
            }
        }
        return cut.substring(0, anchor.end) + insert + cut.substring(anchor.end);
    }

    private static void collectAxiomsBlocksForParent(
            List<AcsCommentSpan> spans, String parentName, List<AcsCommentSpan> out) {
        for (AcsCommentSpan sp : spans) {
            if (sp.axiomaticName == null || !sp.axiomaticName.endsWith("_axioms")) {
                continue;
            }
            if (parentName.equals(resolveParentAxiomaticName(sp.axiomaticName, spans))
                    && out.stream().noneMatch(x -> x.start == sp.start)) {
                out.add(sp);
            }
        }
    }

    /**
     * Quando o bloco {@code axiomatic mapping_function} existir no merge, move-o para imediatamente
     * depois de {@code axiomatic new_types} para manter as definições de mapeamento de listas junto
     * da declaração de tipos base.
     */
    private static String placeMappingFunctionImmediatelyAfterNewTypes(String content) {
        List<AcsCommentSpan> spans = findAllAcsCommentSpans(content);
        AcsCommentSpan mapping = null;
        AcsCommentSpan newTypes = null;
        for (AcsCommentSpan sp : spans) {
            if ("mapping_function".equals(sp.axiomaticName)
                    && LOGIC_OR_PREDICATE_IN_BLOCK.matcher(sp.text).find()
                    && mapping == null) {
                mapping = sp;
            } else if ("new_types".equals(sp.axiomaticName) && newTypes == null) {
                newTypes = sp;
            }
        }
        if (mapping == null || newTypes == null) {
            return content;
        }

        String cut = content.substring(0, mapping.start) + content.substring(mapping.end);
        List<AcsCommentSpan> cutSpans = findAllAcsCommentSpans(cut);
        AcsCommentSpan newTypesAfter = null;
        for (AcsCommentSpan sp : cutSpans) {
            if ("new_types".equals(sp.axiomaticName)) {
                newTypesAfter = sp;
                break;
            }
        }
        if (newTypesAfter == null) {
            return content;
        }
        return cut.substring(0, newTypesAfter.end) + mapping.text + cut.substring(newTypesAfter.end);
    }

    /**
     * Coloca cada bloco {@code axiomatic Foo_axioms} imediatamente após o axiomatic pai {@code Foo}
     * (mesmo nome sem o sufixo {@code _axioms}), como nos ficheiros da lib que fazem {@code include}
     * do axioma a seguir à declaração.
     */
    private static String attachAxiomsBlocksAfterParentAxiomatic(String content) {
        List<AcsCommentSpan> spans = findAllAcsCommentSpans(content);
        List<String[]> toAttach = new ArrayList<>();
        List<AcsCommentSpan> toRemove = new ArrayList<>();
        for (AcsCommentSpan sp : spans) {
            if (sp.axiomaticName == null || !sp.axiomaticName.endsWith("_axioms")) {
                continue;
            }
            String parent = resolveParentAxiomaticName(sp.axiomaticName, spans);
            toAttach.add(new String[] {parent, sp.text});
            toRemove.add(sp);
        }
        if (toRemove.isEmpty()) {
            return content;
        }
        toRemove.sort(Comparator.comparingInt((AcsCommentSpan s) -> s.start).reversed());
        String cut = content;
        for (AcsCommentSpan sp : toRemove) {
            cut = cut.substring(0, sp.start) + cut.substring(sp.end);
        }
        for (String[] pair : toAttach) {
            String parentName = pair[0];
            String axiomText = pair[1];
            AcsCommentSpan parent = findDeclAxiomaticBlock(findAllAcsCommentSpans(cut), parentName);
            if (parent != null) {
                cut = cut.substring(0, parent.end) + axiomText + cut.substring(parent.end);
            } else {
                int idx = findPreambleInsertIndex(cut);
                cut = cut.substring(0, idx) + axiomText + cut.substring(idx);
            }
        }
        return cut;
    }

    /**
     * Antecipa blocos da lib (declarações e {@code *_axioms}) que o Frama-C deixou depois do primeiro
     * {@code Connection_*}, na ordem de {@link AcslLibIncludes#orderedLibFunctionAxiomaticNames()}.
     */
    private static String moveLibAxiomaticBlocksBeforeConnection(
            String content, Map<String, Integer> rank) {
        List<AcsCommentSpan> spans = findAllAcsCommentSpans(content);
        int connectionStart = -1;
        for (AcsCommentSpan sp : spans) {
            if (sp.axiomaticName != null && sp.axiomaticName.startsWith("Connection_")) {
                connectionStart = sp.start;
                break;
            }
        }
        if (connectionStart < 0) {
            return content;
        }
        Set<String> libNames = new LinkedHashSet<>(rank.keySet());
        for (String n : rank.keySet()) {
            libNames.add(n + "_axioms");
        }
        List<AcsCommentSpan> toMove = new ArrayList<>();
        for (AcsCommentSpan sp : spans) {
            if (sp.axiomaticName == null || sp.start <= connectionStart) {
                continue;
            }
            boolean libDecl = libNames.contains(sp.axiomaticName);
            boolean libAxioms =
                    sp.axiomaticName.endsWith("_axioms")
                            && libNames.contains(
                                    resolveParentAxiomaticName(sp.axiomaticName, spans));
            if (libDecl || libAxioms) {
                toMove.add(sp);
            }
        }
        if (toMove.isEmpty()) {
            return content;
        }
        toMove.sort(Comparator.comparingInt((AcsCommentSpan s) -> s.start).reversed());
        String cut = content;
        for (AcsCommentSpan sp : toMove) {
            cut = cut.substring(0, sp.start) + cut.substring(sp.end);
        }
        List<AcsCommentSpan> after = findAllAcsCommentSpans(cut);
        int newConn = -1;
        for (AcsCommentSpan sp : after) {
            if (sp.axiomaticName != null && sp.axiomaticName.startsWith("Connection_")) {
                newConn = sp.start;
                break;
            }
        }
        if (newConn < 0) {
            return cut;
        }
        List<AcsCommentSpan> orderedMove = new ArrayList<>(toMove);
        orderedMove.sort(
                Comparator.comparingInt((AcsCommentSpan s) -> libAxiomaticRank(rank, s.axiomaticName))
                        .thenComparingInt(s -> s.start));
        StringBuilder insert = new StringBuilder();
        for (AcsCommentSpan sp : orderedMove) {
            insert.append(sp.text);
        }
        return cut.substring(0, newConn) + insert + cut.substring(newConn);
    }

    private static AcsCommentSpan findDeclAxiomaticBlock(List<AcsCommentSpan> spans, String name) {
        for (AcsCommentSpan sp : spans) {
            if (name.equals(sp.axiomaticName)) {
                return sp;
            }
        }
        Pattern decl = Pattern.compile("axiomatic\\s+" + Pattern.quote(name) + "\\b");
        for (AcsCommentSpan sp : spans) {
            if (decl.matcher(sp.text).find()) {
                return sp;
            }
        }
        return null;
    }

    /**
     * Nome do axiomatic de declaração correspondente a {@code Foo_axioms}. Na lib o sufixo nem sempre
     * é um prefixo exacto do pai (ex.: {@code range_axioms} → {@code range_function}).
     */
    private static String resolveParentAxiomaticName(
            String axiomsName, List<AcsCommentSpan> spans) {
        if (!axiomsName.endsWith("_axioms")) {
            return axiomsName;
        }
        String base = axiomsName.substring(0, axiomsName.length() - "_axioms".length());
        Set<String> declNames = new LinkedHashSet<>();
        for (AcsCommentSpan sp : spans) {
            if (sp.axiomaticName != null && !sp.axiomaticName.endsWith("_axioms")) {
                declNames.add(sp.axiomaticName);
            }
        }
        if (declNames.contains(base)) {
            return base;
        }
        String withFunction = base + "_function";
        if (declNames.contains(withFunction)) {
            return withFunction;
        }
        String withSequence = "sequence_" + base;
        if (declNames.contains(withSequence)) {
            return withSequence;
        }
        for (String n : declNames) {
            if (axiomsName.equals(n + "_axioms")) {
                return n;
            }
        }
        return base;
    }

    private static int libAxiomaticRank(Map<String, Integer> rank, String axiomaticName) {
        if (rank.containsKey(axiomaticName)) {
            return rank.get(axiomaticName);
        }
        if (axiomaticName.endsWith("_axioms")) {
            String base = axiomaticName.substring(0, axiomaticName.length() - "_axioms".length());
            if (rank.containsKey(base)) {
                return rank.get(base);
            }
            if (rank.containsKey(base + "_function")) {
                return rank.get(base + "_function");
            }
            String withSequence = "sequence_" + base;
            if (rank.containsKey(withSequence)) {
                return rank.get(withSequence);
            }
            for (String n : rank.keySet()) {
                if (axiomaticName.equals(n + "_axioms")) {
                    return rank.get(n);
                }
            }
        }
        return Integer.MAX_VALUE;
    }

    private static final Pattern AXIOMATIC_NAME_IN_ACSL_COMMENT =
            Pattern.compile("axiomatic\\s+(\\w+)");

    private static final Pattern LOGIC_OR_PREDICATE_IN_BLOCK =
            Pattern.compile("\\b(logic|predicate)\\s+");

    private static String reorderLibAxiomaticBlocksInMerged(String content, Map<String, Integer> rank) {
        List<AcsCommentSpan> spans = findAllAcsCommentSpans(content);
        List<AcsCommentSpan> sortable = new ArrayList<>();
        for (AcsCommentSpan sp : spans) {
            if (sp.axiomaticName != null
                    && rank.containsKey(sp.axiomaticName)
                    && LOGIC_OR_PREDICATE_IN_BLOCK.matcher(sp.text).find()) {
                sortable.add(sp);
            }
        }
        if (sortable.size() <= 1) {
            return content;
        }
        List<AcsCommentSpan> byFile = new ArrayList<>(sortable);
        byFile.sort(Comparator.comparingInt(a -> a.start));
        List<AcsCommentSpan> byRank = new ArrayList<>(sortable);
        byRank.sort(
                Comparator.comparingInt((AcsCommentSpan a) -> rank.get(a.axiomaticName))
                        .thenComparingInt(a -> a.start));
        Map<Integer, String> replacement = new HashMap<>();
        for (int i = 0; i < byFile.size(); i++) {
            replacement.put(byFile.get(i).start, byRank.get(i).text);
        }
        StringBuilder sb = new StringBuilder();
        int cursor = 0;
        for (AcsCommentSpan sp : spans) {
            sb.append(content, cursor, sp.start);
            String rep = replacement.get(sp.start);
            if (rep != null) {
                sb.append(rep);
            } else {
                sb.append(sp.text);
            }
            cursor = sp.end;
        }
        sb.append(content, cursor, content.length());
        return sb.toString();
    }

    private static List<AcsCommentSpan> findAllAcsCommentSpans(String content) {
        List<AcsCommentSpan> spans = new ArrayList<>();
        int from = 0;
        while (from < content.length()) {
            int start = content.indexOf("/*@", from);
            if (start < 0) {
                break;
            }
            int close = content.indexOf("*/", start + 3);
            if (close < 0) {
                break;
            }
            int end = skipNewlineAfter(close + 2, content);
            String text = content.substring(start, end);
            Matcher m = AXIOMATIC_NAME_IN_ACSL_COMMENT.matcher(text);
            String axName = m.find() ? m.group(1) : null;
            spans.add(new AcsCommentSpan(start, end, text, axName));
            from = end;
        }
        return spans;
    }

    private static final class AcsCommentSpan {
        final int start;
        final int end;
        final String text;
        final String axiomaticName;

        AcsCommentSpan(int start, int end, String text, String axiomaticName) {
            this.start = start;
            this.end = end;
            this.text = text;
            this.axiomaticName = axiomaticName;
        }
    }

    private static int skipNewlineAfter(int pos, String s) {
        int i = pos;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '\n') {
                return i + 1;
            }
            if (ch == '\r') {
                i++;
                if (i < s.length() && s.charAt(i) == '\n') {
                    i++;
                }
                return i;
            }
            break;
        }
        return i;
    }

    private static int findMatchingBrace(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Índice do primeiro {@code /*@} após o preâmbulo inicial; se não houver, o fim do texto. */
    private static int findPreambleInsertIndex(String s) {
        int i = 0;
        while (i < s.length()) {
            int lineStart = i;
            int nl = s.indexOf('\n', i);
            int lineEnd = nl < 0 ? s.length() : nl + 1;
            String line = s.substring(i, lineEnd);
            String left = line.stripLeading();
            if (left.startsWith("/*@")) {
                return lineStart;
            }
            String t = line.strip();
            if (t.isEmpty()
                    || t.startsWith("#include")
                    || t.startsWith("/* Generated")
                    || t.startsWith("//")) {
                i = lineEnd;
                continue;
            }
            i = lineEnd;
        }
        return s.length();
    }

    /**
     * Após monomorfização / renomeações, o Frama-C pode deixar
     * {@code axiomatic sequence_list_to_function…} depois de blocos (ex.
     * {@code sequence_utils_axioms}) que já chamam {@code list_to_function(}. Este passo move a
     * declaração (e {@code sequence_list_to_function_axioms…}) para imediatamente antes do primeiro
     * {@code list_to_function(} real, ou após {@code new_types} se não houver uso.
     */
    private static void ensureSequenceListToFunctionDeclBeforeFirstUse(Path mergedC) throws IOException {
        if (!Files.isRegularFile(mergedC)) {
            return;
        }
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        String updated = ensureSequenceListToFunctionDeclBeforeFirstUseInString(content);
        if (!updated.equals(content)) {
            Files.writeString(mergedC, updated, StandardCharsets.UTF_8);
        }
    }

    private static String ensureSequenceListToFunctionDeclBeforeFirstUseInString(String content) {
        List<AcsCommentSpan> spans = findAllAcsCommentSpans(content);
        List<AcsCommentSpan> declOnly = new ArrayList<>();
        List<AcsCommentSpan> axiomOnly = new ArrayList<>();
        for (AcsCommentSpan sp : spans) {
            if (sp.axiomaticName == null) {
                continue;
            }
            String n = sp.axiomaticName;
            if (n.startsWith("sequence_list_to_function_axioms")) {
                axiomOnly.add(sp);
            } else if (n.equals("sequence_list_to_function") || n.startsWith("sequence_list_to_function_")) {
                declOnly.add(sp);
            }
        }
        List<AcsCommentSpan> toMove = new ArrayList<>(declOnly.size() + axiomOnly.size());
        declOnly.sort(Comparator.comparingInt(s -> s.start));
        axiomOnly.sort(Comparator.comparingInt(s -> s.start));
        toMove.addAll(declOnly);
        toMove.addAll(axiomOnly);
        if (toMove.isEmpty()) {
            return content;
        }

        List<AcsCommentSpan> removeOrder = new ArrayList<>(toMove);
        removeOrder.sort(Comparator.comparingInt((AcsCommentSpan s) -> s.start).reversed());
        String cut = content;
        for (AcsCommentSpan sp : removeOrder) {
            cut = cut.substring(0, sp.start) + cut.substring(sp.end);
        }

        Matcher use = LIST_TO_FUNCTION_LIB_CALL.matcher(cut);
        int insertAt;
        if (use.find()) {
            int at = use.start();
            insertAt = cut.lastIndexOf("/*@", at);
            if (insertAt < 0) {
                insertAt = findInsertAfterNewTypesBlockEnd(cut);
            }
        } else {
            insertAt = findInsertAfterNewTypesBlockEnd(cut);
        }
        if (insertAt < 0) {
            insertAt = findPreambleInsertIndex(cut);
        }

        StringBuilder sb = new StringBuilder();
        for (AcsCommentSpan sp : toMove) {
            sb.append(sp.text);
        }
        return cut.substring(0, insertAt) + sb + cut.substring(insertAt);
    }

    /** Índice imediatamente após o span {@code axiomatic new_types} (ou primeiro {@code /*@}). */
    private static int findInsertAfterNewTypesBlockEnd(String cut) {
        List<AcsCommentSpan> ss = findAllAcsCommentSpans(cut);
        for (AcsCommentSpan sp : ss) {
            if ("new_types".equals(sp.axiomaticName)) {
                return sp.end;
            }
        }
        return findPreambleInsertIndex(cut);
    }

    private static void deleteRecursive(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {}
                    });
                }
            }
        } catch (IOException ignored) {}
    }
}
