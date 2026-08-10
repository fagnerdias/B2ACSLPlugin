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
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.bxml.BxmlGluingNormalizer;
import com.example.bxml.BxmlMachineVariables;
import com.example.bxml.BxmlImportsGraph;
import com.example.bxml.BxmlSeesGraph;
import com.example.bxml.GhostOperationsCiGenerator;
import com.example.model.Machine;
import com.example.ui.FormalVerificationReportDialog;
import com.example.ui.VerificationProgressDialog;
import com.example.ui.VerificationReportData;
import com.example.analysis.LoopUnrollLevelEstimator;
import com.example.ui.WpOptionsDialog;
import com.example.ui.WpOptionsDialog.WpOptions;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Pipeline B2ACSL: BXML -> ACSL -> {@code ghost_operations.ci} -> Frama-C ({@code -acsl-import} +
 * {@code merged_code.c} + WP) -> resultado para Atelier B.
 */
public final class B2ACSLPipeline {

    private record MachineFile(Machine machine, Path bxmlPath) {}

    /**
     * Nome C do operador de exponenciação inteira B ({@code **i} no BXML) — quando um projeto usa
     * {@code **}, o código C não tem operador nativo e chama esta função auxiliar de runtime (ex.
     * {@code b_pow.c}/{@code b_pow.h}, gerados por fora deste plugin). Sem especificação ACSL
     * própria, WP não sabe o que {@code b_pow} calcula e não consegue provar pós-condições de
     * operações como {@code pow_a} que dependem do seu resultado.
     */
    private static final String INTEGER_POWER_HELPER_FUNCTION_NAME = "b_pow";

    /**
     * Especificação ACSL (formato de esboço {@code function X: contract: ...} usado por todo o
     * pipeline, convertido para comentário ACSL real pelo {@code -acsl-import}) para a função
     * auxiliar de runtime {@code b_pow(op1, op2)} — {@code op1^op2} via loop, correspondendo
     * exatamente aos nomes de parâmetros/variáveis locais do {@code b_pow.c} gerado.
     *
     * <p>SEM {@code include "import/math.acsl";} próprio de propósito: {@code b_pow.acsl} é sempre
     * anexado ao FIM de {@code topLevelAcslFiles} (depois de todos os .acsl das máquinas), e
     * qualquer máquina que use {@code **} já produz {@code integer_pow(...)} no seu PRÓPRIO
     * contrato (via {@code BxmlExpressionToAcsl}'s "**i" → "integer_pow"), o que já dispara o
     * include de {@code math.acsl} nesse ficheiro. Repeti-lo aqui causa
     * {@code Duplicated axiomatics math_functions} no Frama-C — includes idênticos só são
     * deduplicados dentro da MESMA árvore de includes, não entre ficheiros de topo distintos do
     * {@code -acsl-import}.
     */
    private static final String INTEGER_POWER_HELPER_ACSL_SKETCH =
            "axiomatic pow_bounds_lemmas {\n"
            +     "admit lemma pow_prefix_in_INT:\n"
            +     "\\forall integer b, e;\n"
            +         "0 <= e && belongs(integer_pow(b, e), INT) ==>\n"
            +         "\\forall integer k; 0 <= k <= e ==>\n"
            +         "-2147483648 <= integer_pow(b, k) <= 2147483647;\n"
            +     "}\n"
            + "function b_pow:\n"
                    + "contract:\n"
                    + "    requires 0 <= op2;\n"
                    + "    requires \\forall integer k; 0 <= k <= op2 ==>\n"
                    + "        -2147483648 <= integer_pow(op1, k) <= 2147483647;\n"
                    + "    assigns \\nothing;\n"
                    + "    ensures \\result == integer_pow(op1, op2);\n"
                    + "    at loop 1:\n"
                    + "        loop invariant 0 <= i <= op2;\n"
                    + "        loop invariant val == integer_pow(op1, i);\n"
                    + "        loop assigns i, val;\n"
                    + "        loop variant op2 - i;\n";

    /** {@code true} se {@code node} (ou algum descendente) for {@code Binary_Exp op='**i'}. */
    private static boolean containsIntegerPowerOperator(Node node) {
        if (node == null) return false;
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            Element el = (Element) node;
            if ("Binary_Exp".equals(el.getLocalName()) && "**i".equals(el.getAttribute("op"))) {
                return true;
            }
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (containsIntegerPowerOperator(children.item(i))) {
                return true;
            }
        }
        return false;
    }

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

        // Pre-step: Computar cDir e gerar ghost_operations.ci antes dos .acsl para que os
        // símbolos ghost (ex. set_difference via dummy_set_difference) sejam detectados no
        // scan de includes da lib.
        String bdpStr = bdpPathToString(bdp);
        int bdpIdx = bdpStr.lastIndexOf("bdp");
        Path langPath = bdpIdx >= 0
                ? Path.of(bdpStr.substring(0, bdpIdx) + "lang" + bdpStr.substring(bdpIdx + 3))
                : bdp.getParent().resolve("lang");
        Path cDir = langPath.resolve("c");
        // Staging dos ficheiros da lib sob cDir (elimina cópias redundantes em target/)
        System.setProperty("b2acsl.targetAcslDir", cDir.toAbsolutePath().normalize().toString());
        // Limpa antes do loop: GhostOperationsCiGenerator.write() ACRESCENTA (não sobrescreve) a
        // cada chamada — ver comentário abaixo — por isso o ficheiro precisa de começar vazio a
        // cada execução, senão conteúdo de uma run anterior ficaria duplicado.
        Files.deleteIfExists(GhostOperationsCiGenerator.targetPath(cDir));
        boolean anyNeedsGhost = false;
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
            if (!BxmlMachineVariables.needsGhostAbstraction(mr, mergedEls)
                    && !GhostOperationsCiGenerator.machineHasAnySubOperations(mr)) {
                continue;
            }
            anyNeedsGhost = true;
            // write() ACRESCENTA ao ghost_operations.ci (não sobrescreve): num projeto com VÁRIAS
            // máquinas a precisar de abstração ghost (ex.: Customer_estr tem Customer E Set), cada
            // chamada usava Files.writeString sem APPEND, e só a ÚLTIMA máquina processada
            // sobrevivia no ficheiro final — as declarações ghost_purchases/ghost_limit de Customer
            // desapareciam silenciosamente, substituídas pelas de Set, dando "unbound logic
            // variable ghost_purchases" no Frama-C. O bloco "axiomatic dummy_ghost { ... }" (mesmo
            // nome/boilerplate genérico em toda chamada) que cada write() acrescenta é fundido num
            // só logo a seguir ao loop, para não duplicar "type DSet<A>;" etc.
            GhostOperationsCiGenerator.write(
                    cDir, mr, invariantGluingSubstitutions, bdp, mergedEls);
        }
        if (!anyNeedsGhost) {
            Files.deleteIfExists(GhostOperationsCiGenerator.targetPath(cDir));
        } else {
            GhostOperationsCiGenerator.mergeDuplicateDummyGhostBlocks(
                    GhostOperationsCiGenerator.targetPath(cDir));
        }
        Path ghostCiPath = GhostOperationsCiGenerator.targetPath(cDir);
        String ghostCiStripped =
                Files.isRegularFile(ghostCiPath)
                        ? GhostOperationsCiGenerator.stripDummyPrefixForMergedGhostSpecs(
                                Files.readString(ghostCiPath, java.nio.charset.StandardCharsets.UTF_8))
                        : null;

        // Step 1.1: Gerar arquivos .acsl na pasta lang/c (junto aos ficheiros C)
        Path acslDir = cDir;
        try {
            AcslLibIncludes.resetLibraryBundleUnderOutput(acslDir);
            List<Path> acslFiles = new ArrayList<>();
            // ** (Binary_Exp op='**i') não tem operador C nativo — verifica em TODAS as máquinas
            // do projeto (abstratas E implementações/refinamentos, não só as que geram .acsl
            // próprio) se alguma a usa, para decidir se b_pow.acsl (especificação da função de
            // runtime auxiliar b_pow, sem a qual WP não prova nada sobre operações como pow_a que
            // chamam essa função) precisa de ser gerado.
            boolean usesIntegerPowerOperator = false;
            for (MachineFile mf : machines) {
                if (containsIntegerPowerOperator(AcslGenerator.parseMachineElement(mf.bxmlPath()))) {
                    usesIntegerPowerOperator = true;
                    break;
                }
            }
            Set<String> abstractMachineNames = new LinkedHashSet<>();
            Set<String> dependencyOnlyMachineNames =
                    dependencyOnlyMachineNames(seesGraph, importsGraph);
            List<MachineFile> machinesForAcsl =
                    orderMachinesForAcslGeneration(machines, dependencyOnlyMachineNames);
            List<String> topLevelImportMachinesPreview = new ArrayList<>();
            for (MachineFile mf : machinesForAcsl) {
                Element machineRoot = AcslGenerator.parseMachineElement(mf.bxmlPath());
                if (AcslGenerator.getAbstractionReferenceName(machineRoot).isPresent()) {
                    continue;
                }
                String machineName = mf.machine().getMachineName();
                if (!dependencyOnlyMachineNames.contains(machineName)) {
                    topLevelImportMachinesPreview.add(machineName);
                }
            }
            String libIncludeScanRootMachineName =
                    topLevelImportMachinesPreview.isEmpty()
                            ? ""
                            : topLevelImportMachinesPreview.get(0);
            String libIncludeCarrierMachineName =
                    resolveLibIncludeCarrierMachineName(
                            libIncludeScanRootMachineName, seesGraph, importsGraph);
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
                                importsGraph,
                                libIncludeCarrierMachineName,
                                libIncludeScanRootMachineName,
                                ghostCiStripped);
                acsl.ifPresent(acslFiles::add);
            }
            List<String> topLevelImportMachines =
                    topLevelImportMachineNames(abstractMachineNames, dependencyOnlyMachineNames);
            List<Path> topLevelAcslFiles = new ArrayList<>(
                    filterAcslFilesByMachineNames(acslFiles, topLevelImportMachines));
            if (usesIntegerPowerOperator) {
                Path bPowAcsl = acslDir.resolve(INTEGER_POWER_HELPER_FUNCTION_NAME + ".acsl");
                Files.writeString(bPowAcsl, INTEGER_POWER_HELPER_ACSL_SKETCH, StandardCharsets.UTF_8);
                acslFiles.add(bPowAcsl);
                topLevelAcslFiles.add(bPowAcsl);
            }
            if (!topLevelImportMachines.isEmpty()) {
                System.out.println(
                        "[B2ACSL] Importação ACSL Frama-C (raiz SEES): " + topLevelImportMachines);
            }
            System.out.println("[B2ACSL] ACSL gravados em: " + acslDir);
            for (Path p : acslFiles) System.out.println("  - " + p);

            // Step 2: ghost_operations.ci já gerado no pre-step; cDir já calculado acima.

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
                        FramaCRunner.runFramaC(
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
     * {@link SpecificationTypesCollector#OUTPUT_FILE_NAME} em {@code cDir}.
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
        Path typesFile = cDir.resolve(SpecificationTypesCollector.OUTPUT_FILE_NAME);
        SpecificationTypesCollector.writeTypesList(typesFile, types);
        return types;
    }

    /** Saída gerada pelo Frama-C {@code -print} no mesmo diretório — não é ficheiro-fonte. */
    static final String MERGED_CODE_FILE_NAME = "merged_code.c";

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

    /**
     * Portador único dos includes da {@code B2ACSLLib} na importação Frama-C multi-ficheiro: primeira
     * dependência transitiva da raiz, ou a própria raiz se não houver dependências.
     */
    private static String resolveLibIncludeCarrierMachineName(
            String rootMachineName, BxmlSeesGraph seesGraph, BxmlImportsGraph importsGraph) {
        if (rootMachineName == null || rootMachineName.isBlank()) {
            return "";
        }
        List<String> deps =
                com.example.bxml.BxmlSetsTranslator.transitiveDependencyMachineNames(
                        rootMachineName.trim(), seesGraph, importsGraph);
        if (!deps.isEmpty()) {
            return deps.get(0);
        }
        return rootMachineName.trim();
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
     * Remove blocos ACSL {@code axiomatic dummy_ghost} e {@code axiomatic <M>_ghost_patterns} do merge
     * Frama-C (gerados a partir de {@code ghost_operations.ci}).
     */
    static void removeGhostPatternAxiomaticBlocks(Path mergedC) throws IOException {
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
        int closeBrace = AcslCommentSpanScanner.findMatchingBrace(content, openBrace);
        if (closeBrace < 0) {
            return content;
        }
        int commentEnd = content.indexOf("*/", closeBrace);
        if (commentEnd < 0) {
            return content;
        }
        int blockEnd = AcslCommentSpanScanner.skipNewlineAfter(commentEnd + 2, content);
        return content.substring(0, blockStart) + content.substring(blockEnd);
    }

    /** Remove o prefixo {@code dummy_} de identificadores no C/ACSL fundido. */
    static void stripDummyPrefixFromMergedCode(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        content = content.replaceAll("\\bdummy_", "");
        // DSet<A>/DTuple<A,B> foram introduzidos no ghost_operations.ci; no merged_code.c
        // os tipos reais Set<A>/Tuple<A,B> já estão disponíveis via ACSL imports.
        content = content.replaceAll("\\bDSet<", "Set<");
        content = content.replaceAll("\\bDTuple<", "Tuple<");
        Files.writeString(mergedC, content, StandardCharsets.UTF_8);
    }

    /**
     * Após {@link #stripDummyPrefixFromMergedCode}, {@code ensures dummy_ghost_<v>} passa a
     * {@code ensures ghost_<v>}; troca por {@code assigns ghost_<v>;} (variáveis ghost no merge).
     * Inclui a forma que o Frama-C emite para não-nulo: {@code ensures ghost_<v> != 0;}.
     */
    private static final Pattern ENSURES_GHOST_VAR =
            Pattern.compile("ensures\\s+(ghost_\\w+)\\s*(?:;|!=\\s*0\\s*;)");

    static void replaceEnsuresGhostVarWithAssignsInMerged(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        content = ENSURES_GHOST_VAR.matcher(content).replaceAll("assigns $1;");
        Files.writeString(mergedC, content, StandardCharsets.UTF_8);
    }

    /**
     * Coloca no início do ficheiro (logo após o preâmbulo) as linhas de declaração ghost lidas de
     * {@code ghost_operations.ci}, removendo duplicados equivalentes já presentes no merge.
     */
    static void insertGhostVariableDeclarationsFromGhostCi(Path mergedC, Path ghostCi)
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
        int insertAt = AcslCommentSpanScanner.findPreambleInsertIndex(content);
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

    /**
     * Substitui {@code assert ghost__} por {@code ghost } nas anotações Frama-C.
     *
     * <p>Usa {@code \s+} (não um espaço literal) entre {@code assert} e {@code ghost__}: o
     * pretty-printer do {@code -print} do Frama-C QUEBRA a linha quando é longa o suficiente (ex.:
     * {@code attackplayer}, cuja lista de 4 parâmetros é mais longa que a de qualquer outra operação
     * ghost neste projeto) — {@code "assert\n        ghost__attackplayer(...)"}, com quebra de linha
     * e indentação em vez de um único espaço. Um {@code String.replace} literal (a versão anterior)
     * nunca casava nesse caso, deixando {@code ghost__attackplayer} para trás como uma referência a
     * PREDICADO ACSL normal dentro do {@code assert} — mas só {@code void attackplayer(...)} (a
     * função ghost em si, sem o prefixo {@code ghost__}) sobrevive até {@code merged_code.c}, daí
     * "unbound logic predicate ghost__attackplayer". Só descoberto ao correr RulerOfTheSeas
     * (primeira operação ghost deste projeto cuja lista de parâmetros é longa o suficiente para
     * quebrar linha).
     */
    static void replaceAssertGhostWithGhostKeyword(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        content = content.replaceAll("assert\\s+ghost__", "ghost ");
        Files.writeString(mergedC, content, StandardCharsets.UTF_8);
    }

    /** Garante {@code ghost op();} em vez de {@code ghost op;} para chamadas ghost sem argumentos. */
    static void addParenthesesToVoidGhostCalls(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        content =
                Pattern.compile("\\bghost\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;")
                        .matcher(content)
                        .replaceAll("ghost $1();");
        Files.writeString(mergedC, content, StandardCharsets.UTF_8);
    }

    private static final Pattern GHOST_OP_BLOCK_IN_CI =
            Pattern.compile("(?s)/\\*@\\s*ghost\\b.*?\\bvoid\\s+([A-Za-z_]\\w*)\\s*\\([^;{}]*\\)\\s*;\\s*\\*/");
    private static final Pattern INITIALISATION_FUNCTION_DEFINITION =
            Pattern.compile("\\bvoid\\s+[A-Za-z_]\\w*__INITIALISATION\\s*\\([^;{}]*\\)\\s*\\{");

    /**
     * Novo passo pré-WP: move especificações ghost de operações para imediatamente acima da função C
     * correspondente (ex.: bloco {@code initialisation} acima de {@code Deck__INITIALISATION}).
     */
    static void placeGhostOperationSpecsAboveFunctions(Path mergedC, Path ghostCi) throws IOException {
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
            if (opName.toLowerCase().endsWith("__initialisation")) {
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
        if (opName.toLowerCase().endsWith("__initialisation")) {
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
    static void liftPureGhostEnsuresToOperationContracts(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        // Group 1 usa [^*]*(?:\*(?!/)[^*]*)* — "loop desenrolado" equivalente a (?:[^*]|\*(?!/))*
        // (mesma linguagem: qualquer texto até o primeiro "*/" literal) mas SEM repetir um GRUPO de
        // alternação via "*": em Java, Pattern$Loop recursa uma stack frame por repetição de um
        // GRUPO (Pattern$GroupHead/GroupTail/Branch/BranchConn no stack trace), então o "(?:X|Y)*"
        // original recursava uma vez por CARACTER do bloco ghost — StackOverflowError em blocos
        // ghost grandes (ex.: RulerOfTheSeas, com muito mais conteúdo ghost que os exemplos
        // anteriores — nunca disparou até rodar esse exemplo). A forma desenrolada só recursa uma
        // vez por "*" literal (raro em texto ACSL), com o grosso do texto consumido pelas classes de
        // caracteres [^*]* (repetição eficiente, não-recursiva-por-caractere em Java). Mesma proteção
        // do original contra a cauda "\*/" ser ultrapassada por backtracking: um "*" só é consumido
        // pelo grupo interno quando NÃO é seguido de "/".
        Pattern ghostThenNormalBeforeDefinition =
                Pattern.compile(
                        "(/\\*@\\s*ghost\\b[^*]*(?:\\*(?!/)[^*]*)*\\*/\\s*)"
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

}
