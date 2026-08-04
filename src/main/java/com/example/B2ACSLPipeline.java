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

    private static final String FRAMA_C = "frama-c";

    /**
     * Linhas de diagnóstico que o Frama-C escreve no stdout antes do C gerado com {@code -print}
     * (ex.: {@code [kernel] Parsing ...}, {@code [acsl-import] Success ...}).
     */
    private static final Pattern FRAMA_C_STDOUT_TAG_LINE =
            Pattern.compile("^\\[[^\\]]+\\]\\s*.*");

    /** Linha {@code [wp] N goals scheduled} — nº de provas que o WP agendou para a função corrente. */
    private static final Pattern WP_GOALS_SCHEDULED_LINE =
            Pattern.compile("^\\[wp\\]\\s+(\\d+)\\s+goals?\\s+scheduled\\s*$");

    /** Marca o bloco {@code axiomatic new_types} importado (ex. de {@code types.acsl}). */
    private static final String AXIOMATIC_NEW_TYPES_MARKER = "axiomatic new_types";

    /** Primeira utilização da lib {@code list_to_function(} (não {@code dummy_list_to_function}). */
    private static final Pattern LIST_TO_FUNCTION_LIB_CALL =
            Pattern.compile("(?<![A-Za-z0-9_])list_to_function\\s*\\(");

    /** Lemas admitidos da B2ACSLLib (anexados ao fim do merge Frama-C). */
    private static final String ACSL_LIB_LEMMAS_RESOURCE = B2AcslLibraryPaths.classpathResource("lemmas.acsl");

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
        // (moveMemoryDependentVariableBlocksAfterTheirGlobals) resolve isso — a própria função C
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
        moveTupleCodomainAxiomaticBlocksAfterNewTypes(mergedCode);
        appendLemmasAcslLibToMergedEnd(mergedCode, allowedLibSymbolsForLemmas);
        SpecificationAxiomaticInstantiator.monomorphizeGenericAcslBlocks(
                mergedCode, specificationUsedTypes);
        SpecificationAxiomaticInstantiator.renameParameterizedTypesToConcrete(
                mergedCode, specificationUsedTypes);
        SpecificationAxiomaticInstantiator.normalizeLegacyMachineTypeIdentifiers(mergedCode);
        ensureSequenceListToFunctionDeclBeforeFirstUse(mergedCode);
        moveMemoryDependentVariableBlocksAfterTheirGlobals(mergedCode);
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

    private static final Pattern TUPLE_TYPES_AXIOMATIC_HEADER =
            Pattern.compile("axiomatic\\s+\\w*_tuple_types\\s*\\{");

    /**
     * Move cada bloco {@code axiomatic <machine>_tuple_types { ... }} (ver
     * {@code AcslGenerator#generateAcsl}/{@code TupleCodomainTypeRegistry}, tipos de codomínio-tupla
     * descobertos por máquina) para logo após {@code axiomatic new_types} — que
     * {@link #moveNewTypesAxiomaticBlockAfterPreamble} já fixou no preâmbulo. Sem isto, o bloco fica
     * onde {@link #reorderLibAxiomaticBlocksPerAcslLibIncludesOrder} o deixar (não é um nome
     * conhecido de {@code AcslLibIncludes}, cai numa posição tardia arbitrária) — tarde demais para
     * os blocos genéricos da lib (ex. {@code Relation_domain}, {@code tuple_couple}) que a
     * referenciam já monomorphizados para este tipo, dando {@code no such type} no Frama-C.
     */
    private static void moveTupleCodomainAxiomaticBlocksAfterNewTypes(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        int newTypesIdx = content.indexOf(AXIOMATIC_NEW_TYPES_MARKER);
        if (newTypesIdx < 0) {
            return;
        }
        int newTypesOpenBrace = content.indexOf('{', newTypesIdx);
        if (newTypesOpenBrace < 0) {
            return;
        }
        int newTypesCloseBrace = findMatchingBrace(content, newTypesOpenBrace);
        if (newTypesCloseBrace < 0) {
            return;
        }
        int newTypesCommentEnd = content.indexOf("*/", newTypesCloseBrace);
        if (newTypesCommentEnd < 0) {
            return;
        }
        int insertAt = skipNewlineAfter(newTypesCommentEnd + 2, content);

        boolean changed = false;
        Matcher m = TUPLE_TYPES_AXIOMATIC_HEADER.matcher(content);
        while (m.find(insertAt)) {
            int headerIdx = m.start();
            int blockStart = content.lastIndexOf("/*@", headerIdx);
            if (blockStart < 0 || blockStart < insertAt) {
                break;
            }
            int openBrace = content.indexOf('{', headerIdx);
            if (openBrace < 0) break;
            int closeBrace = findMatchingBrace(content, openBrace);
            if (closeBrace < 0) break;
            int commentEnd = content.indexOf("*/", closeBrace);
            if (commentEnd < 0) break;
            int blockEnd = skipNewlineAfter(commentEnd + 2, content);

            if (blockStart == insertAt) {
                insertAt = blockEnd;
                m = TUPLE_TYPES_AXIOMATIC_HEADER.matcher(content);
                continue;
            }

            String block = content.substring(blockStart, blockEnd);
            String without = content.substring(0, blockStart) + content.substring(blockEnd);
            String sepBefore = insertAt > 0 && without.charAt(insertAt - 1) != '\n' ? "\n" : "";
            String sepAfter = block.endsWith("\n") ? "" : "\n";
            content = without.substring(0, insertAt) + sepBefore + block + sepAfter + without.substring(insertAt);
            insertAt = insertAt + sepBefore.length() + block.length() + sepAfter.length();
            changed = true;
            m = TUPLE_TYPES_AXIOMATIC_HEADER.matcher(content);
        }
        if (changed) {
            Files.writeString(mergedC, content, StandardCharsets.UTF_8);
        }
    }

    /**
     * Frama-C imprime uma constante lógica (ou predicado) que lê memória mutável, direta ou
     * transitivamente, com um parâmetro de label implícito {@code {L}} (ex.: {@code logic integer
     * index{L}= iter_services__index;}, {@code predicate iter_services_i_invariant{L}=
     * \at(... index ..., L);}). Num merge multi-ficheiro, a ordem relativa entre os {@code .acsl}
     * importados e os {@code .c} pode colocar um desses blocos ANTES do que ele referencia — a
     * própria declaração C do global (ex.: {@code static int32_t iter_services__index;} mais adiante
     * no ficheiro) ou outro bloco {@code {L}=} do qual depende (ex.: {@code index} antes de
     * {@code iter_services_i_invariant} usá-lo) — causando {@code unbound logic variable} no
     * Frama-C. Move cada bloco assim afetado para logo após a última coisa de que depende.
     */
    private static void moveMemoryDependentVariableBlocksAfterTheirGlobals(Path mergedC) throws IOException {
        if (!Files.isRegularFile(mergedC)) {
            return;
        }
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        String updated = content;
        // Cada movimentação pode revelar outra violação de ordem (dependências em cadeia); repete
        // até estabilizar (limite de segurança para não entrar em loop infinito num caso anómalo).
        for (int pass = 0; pass < 40; pass++) {
            String next = moveOneMemoryDependentVariableBlockIfNeeded(updated);
            if (next.equals(updated)) {
                break;
            }
            updated = next;
        }
        if (!updated.equals(content)) {
            Files.writeString(mergedC, updated, StandardCharsets.UTF_8);
        }
    }

    /** Casa o nome declarado por um bloco {@code nome{L}= ...} impresso pelo Frama-C. */
    private static final Pattern LABELED_DECL_NAME = Pattern.compile("([A-Za-z_]\\w*)\\{L\\}\\s*=");

    /** Casa a referência direta a um global C na forma {@code nome{L}= GLOBAL;}. */
    private static final Pattern LABELED_LOGIC_CONST_REF =
            Pattern.compile("\\w+\\{L\\}\\s*=\\s*([A-Za-z_]\\w*)\\s*;");

    /**
     * Casa a referência a um global C dentro de {@code array_to_function_int}/{@code
     * array_to_function_bool} (ex.: {@code array_to_function_int((int32_t *)Price__price_i, 1)}),
     * forma típica de {@code logic Function_..._..._ name{L}= \at(array_to_function_...(...), L);}
     * gerada para variáveis concretas array/função (ex.: {@code Price__price_i}). Diferente de
     * {@link #LABELED_LOGIC_CONST_REF}: aqui o global está dentro de uma chamada com cast, não
     * numa atribuição direta {@code nome{L}= GLOBAL;}.
     */
    private static final Pattern LABELED_ARRAY_TO_FUNCTION_REF =
            Pattern.compile("array_to_function_\\w+\\(\\([^()]*\\)\\s*([A-Za-z_]\\w*)\\s*,");

    /**
     * {@code true} se {@code name} aparece em {@code text} como PARÂMETRO FORMAL de uma declaração
     * {@code logic}/{@code predicate} própria (ex.: {@code Relation_int_int array} num {@code logic
     * \list<integer> lambda_func01(Relation_int_int array, integer lo, integer hi) = …}) — nesse
     * caso todas as ocorrências de {@code name} dentro de {@code text} referem-se ao PARÂMETRO
     * local, não a uma variável/constante global de mesmo nome ({@code array{L}= …}). Sem esta
     * distinção, um lambda "sequence builder" cujo parâmetro livre reusa o nome de uma variável de
     * OUTRA máquina (ex. {@code array}, parâmetro de {@code lambda_func01} vs a variável concreta
     * {@code array} de {@code VArray}) cria uma dependência de ordenação ESPÚRIA — o texto "contém a
     * palavra array", mas não depende de facto da declaração global — e como essa falsa dependência
     * contradiz a ordenação real (o global tem de vir DEPOIS do seu próprio C {@code static}, mas
     * "antes do lambda" que na realidade não o usa), as duas regras de reposicionamento entram em
     * oscilação perpétua sem nunca convergir.
     */
    private static boolean isNameShadowedAsParameter(String text, String name) {
        Pattern paramDecl =
                Pattern.compile(
                        "(?:[A-Za-z_]\\w*|\\\\list\\s*<[^()]*>)\\s+"
                                + Pattern.quote(name)
                                + "\\s*[,)]");
        return paramDecl.matcher(text).find();
    }

    private static String moveOneMemoryDependentVariableBlockIfNeeded(String content) {
        List<AcsCommentSpan> spans = findAllAcsCommentSpans(content);
        // Nome declarado {L}= -> span que o declara, para localizar dependências entre blocos
        // (ex.: "iter_services_i_invariant{L}=" depende de "index{L}=" declarado noutro bloco).
        Map<String, AcsCommentSpan> declaringSpan = new HashMap<>();
        for (AcsCommentSpan sp : spans) {
            Matcher dm = LABELED_DECL_NAME.matcher(sp.text);
            if (dm.find()) {
                declaringSpan.putIfAbsent(dm.group(1), sp);
            }
        }
        for (AcsCommentSpan sp : spans) {
            Matcher declMatcher = LABELED_DECL_NAME.matcher(sp.text);
            if (!declMatcher.find()) {
                continue; // só reordena blocos que o Frama-C marcou como dependentes de memória
            }
            String ownName = declMatcher.group(1);
            int latestDepEnd = -1;

            Matcher refMatcher = LABELED_LOGIC_CONST_REF.matcher(sp.text);
            while (refMatcher.find()) {
                int end = lastStaticDeclEndOutsideSpan(content, refMatcher.group(1), spans);
                if (end > latestDepEnd) {
                    latestDepEnd = end;
                }
            }
            Matcher arrayFnMatcher = LABELED_ARRAY_TO_FUNCTION_REF.matcher(sp.text);
            while (arrayFnMatcher.find()) {
                int end = lastStaticDeclEndOutsideSpan(content, arrayFnMatcher.group(1), spans);
                if (end > latestDepEnd) {
                    latestDepEnd = end;
                }
            }
            for (Map.Entry<String, AcsCommentSpan> e : declaringSpan.entrySet()) {
                String depName = e.getKey();
                AcsCommentSpan depSpan = e.getValue();
                if (depName.equals(ownName) || depSpan == sp) {
                    continue;
                }
                if (Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(depName) + "(?![A-Za-z0-9_])")
                        .matcher(sp.text)
                        .find()
                        && !isNameShadowedAsParameter(sp.text, depName)
                        && depSpan.end > latestDepEnd) {
                    latestDepEnd = depSpan.end;
                }
            }

            if (latestDepEnd > sp.start) {
                String without = content.substring(0, sp.start) + content.substring(sp.end);
                int insertAt = latestDepEnd - (sp.end - sp.start);
                if (insertAt < 0 || insertAt > without.length()) {
                    continue;
                }
                insertAt = skipNewlineAfter(insertAt, without);
                String sepBefore = insertAt > 0 && without.charAt(insertAt - 1) != '\n' ? "\n" : "";
                String sepAfter = sp.text.endsWith("\n") ? "" : "\n";
                return without.substring(0, insertAt) + sepBefore + sp.text + sepAfter + without.substring(insertAt);
            }
        }

        // Passo B: um bloco {L}= também precisa vir ANTES do primeiro uso do seu nome em
        // QUALQUER outro ponto do ficheiro — incluindo contratos de função soltos (ex.:
        // "requires main_fuel_invariant;" acima de uma função, que não se move: está ligado à
        // posição da própria função C). Sem isto, só se corrige a ordem quando quem usa o nome
        // é OUTRO bloco {L}=; um "requires"/"ensures" comum ficaria sempre fora de alcance.
        for (Map.Entry<String, AcsCommentSpan> e : declaringSpan.entrySet()) {
            String name = e.getKey();
            AcsCommentSpan declSpan = e.getValue();
            int earliestUseStart = Integer.MAX_VALUE;
            for (AcsCommentSpan sp : spans) {
                if (sp == declSpan || sp.start >= declSpan.end) {
                    continue;
                }
                if (Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])")
                        .matcher(sp.text)
                        .find()
                        && !isNameShadowedAsParameter(sp.text, name)) {
                    earliestUseStart = Math.min(earliestUseStart, sp.start);
                }
            }
            if (earliestUseStart < declSpan.start) {
                String without =
                        content.substring(0, declSpan.start) + content.substring(declSpan.end);
                // earliestUseStart está antes de declSpan.start, logo não é afetado pela remoção.
                int insertAt = earliestUseStart;
                String sepBefore =
                        insertAt > 0 && without.charAt(insertAt - 1) != '\n' ? "\n" : "";
                String sepAfter = declSpan.text.endsWith("\n") ? "" : "\n";
                return without.substring(0, insertAt)
                        + sepBefore
                        + declSpan.text
                        + sepAfter
                        + without.substring(insertAt);
            }
        }
        return content;
    }

    /**
     * Fim da última declaração de variável global {@code [static] TIPO <globalName>[...];} fora de
     * qualquer comentário ACSL. {@code static} é opcional: nem toda máquina B2ACSL gera variáveis
     * concretas com {@code static} (ex.: máquinas sem cadeia de refinamento podem sair como globais
     * simples, {@code int32_t nome;}), e exigi-lo deixava essas declarações invisíveis a esta
     * varredura. Sem {@code static}, porém, o padrão "PALAVRA nome;" também casa cláusulas de
     * contrato ACSL como {@code assigns nome;}/{@code ensures nome;} — por isso os spans {@code
     * /*@ … *&#47;} (que é onde essas cláusulas sempre vivem; uma declaração C genuína nunca está
     * dentro de um) são explicitamente excluídos.
     */
    private static int lastStaticDeclEndOutsideSpan(
            String content, String globalName, List<AcsCommentSpan> acslSpans) {
        Pattern declPattern =
                Pattern.compile(
                        "(?m)^\\s*(?:static\\s+)?[A-Za-z_]\\w*\\s+"
                                + Pattern.quote(globalName)
                                + "\\s*(?:\\[[^\\]]*\\])?\\s*;\\s*$");
        Matcher dm = declPattern.matcher(content);
        int lastEnd = -1;
        while (dm.find()) {
            int matchStart = dm.start();
            boolean insideAcslComment = false;
            for (AcsCommentSpan sp : acslSpans) {
                if (matchStart >= sp.start && matchStart < sp.end) {
                    insideAcslComment = true;
                    break;
                }
            }
            if (insideAcslComment) {
                continue;
            }
            lastEnd = dm.end();
        }
        return lastEnd;
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
    private static final Pattern INITIALISATION_FUNCTION_DEFINITION =
            Pattern.compile("\\bvoid\\s+[A-Za-z_]\\w*__INITIALISATION\\s*\\([^;{}]*\\)\\s*\\{");

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
            content = moveMachineAxiomaticsAfterSurroundingLibBlocks(content, rank);
        }
        content = moveStandaloneMachinePredicatesAfterMachineAxiomatics(content, rank);
        content = moveMachineAcslBlocksBeforeFirstGhostBlock(content, rank);
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

    /**
     * Conjuntos lógicos globais da ACSL_Lib ({@code set_functions/variables.acsl}) referenciados
     * como identificador NU (sem parênteses), ex.: {@code inclusion(s, BOOL)} — {@link
     * #LEMMA_LIB_STYLE_CALL} só apanha dependências no estilo chamada ({@code nome(}), então um
     * lema cuja única chamada permitida (ex.: {@code inclusion}) esconde uma dependência de {@code
     * BOOL} escapava ao filtro por completo, sobrevivendo mesmo em projetos (ex.:
     * BirthdayRegister) que nunca usam boolean em lado nenhum — "BOOL" nunca fica {@code allowed},
     * mas o lema não era rejeitado, dando "unbound logic variable BOOL" no Frama-C.
     */
    private static final List<String> BARE_GLOBAL_LOGIC_SET_NAMES = List.of("BOOL", "NAT1", "NAT", "INT");

    private static boolean lemmaBodyUsesDisallowedLibCall(String admitLemmaChunk, Set<String> allowed) {
        Matcher m = LEMMA_LIB_STYLE_CALL.matcher(admitLemmaChunk);
        while (m.find()) {
            String name = m.group(1);
            if (allowed.contains(name)) {
                continue;
            }
            return true;
        }
        for (String bareName : BARE_GLOBAL_LOGIC_SET_NAMES) {
            if (allowed.contains(bareName)) {
                continue;
            }
            if (Pattern.compile("\\b" + bareName + "\\b").matcher(admitLemmaChunk).find()) {
                return true;
            }
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
            List.of("range_function", "sequence_iseq", "sequence_last");

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

    /**
     * Move blocos axiomáticos de máquina (não-lib) que estejam intercalados entre blocos de lib
     * para depois do último bloco de lib que os segue. Isso evita referências a símbolos de lib
     * (ex.: {@code relation_inverse}) antes da respetiva declaração.
     */
    private static String moveMachineAxiomaticsAfterSurroundingLibBlocks(
            String content, Map<String, Integer> rank) {
        List<AcsCommentSpan> spans = findAllAcsCommentSpans(content);
        // Identify spans that are non-lib but surrounded (before and after) by lib spans.
        // "Surrounded" = has at least one lib span before AND at least one lib span after.
        Set<String> libAndAxioms = new LinkedHashSet<>(rank.keySet());
        for (String n : rank.keySet()) {
            libAndAxioms.add(n + "_axioms");
        }
        int lastLibIdx = -1;
        for (int i = spans.size() - 1; i >= 0; i--) {
            AcsCommentSpan sp = spans.get(i);
            if (sp.axiomaticName != null && libAndAxioms.contains(sp.axiomaticName)) {
                lastLibIdx = i;
                break;
            }
        }
        if (lastLibIdx < 0) return content;

        boolean seenLib = false;
        List<AcsCommentSpan> toMove = new ArrayList<>();
        for (int i = 0; i < lastLibIdx; i++) {
            AcsCommentSpan sp = spans.get(i);
            if (sp.axiomaticName != null && libAndAxioms.contains(sp.axiomaticName)) {
                seenLib = true;
            } else if (seenLib && sp.axiomaticName != null
                    && !libAndAxioms.contains(sp.axiomaticName)
                    && i < lastLibIdx) {
                // Non-lib block after at least one lib block, and more lib blocks follow
                toMove.add(sp);
            }
        }
        if (toMove.isEmpty()) return content;

        // Remove moved spans in reverse order (so earlier offsets remain valid)
        List<AcsCommentSpan> rev = new ArrayList<>(toMove);
        rev.sort(Comparator.comparingInt((AcsCommentSpan s) -> s.start).reversed());
        String result = content;
        List<String> movedTexts = new ArrayList<>();
        for (AcsCommentSpan sp : rev) {
            movedTexts.add(0, result.substring(sp.start, sp.end));
            result = result.substring(0, sp.start) + result.substring(sp.end);
        }

        // Find the new position of the last lib span in the modified content
        List<AcsCommentSpan> newSpans = findAllAcsCommentSpans(result);
        int insertAfter = -1;
        for (int i = newSpans.size() - 1; i >= 0; i--) {
            AcsCommentSpan sp = newSpans.get(i);
            if (sp.axiomaticName != null && libAndAxioms.contains(sp.axiomaticName)) {
                insertAfter = sp.end;
                break;
            }
        }
        if (insertAfter < 0) return content; // safety fallback

        StringBuilder insert = new StringBuilder();
        for (String t : movedTexts) {
            insert.append(t);
        }
        return result.substring(0, insertAfter) + insert + result.substring(insertAfter);
    }

    /**
     * Move predicados ACSL standalone (sem wrapper axiomatic) que estejam intercalados entre
     * blocos de lib para depois do último bloco axiomatic do arquivo (incluindo axiomáticos de
     * máquina). Isso corrige o caso onde Frama-C emite {@code predicate Biblioteca_invariant}
     * antes dos axiomáticos de máquina que declaram as variáveis que o predicado referencia.
     */
    private static String moveStandaloneMachinePredicatesAfterMachineAxiomatics(
            String content, Map<String, Integer> rank) {
        List<AcsCommentSpan> spans = findAllAcsCommentSpans(content);

        Set<String> libAndAxioms = new LinkedHashSet<>(rank.keySet());
        for (String n : rank.keySet()) libAndAxioms.add(n + "_axioms");

        // Find the last lib span index in the spans list
        int lastLibIdx = -1;
        for (int i = spans.size() - 1; i >= 0; i--) {
            AcsCommentSpan sp = spans.get(i);
            if (sp.axiomaticName != null && libAndAxioms.contains(sp.axiomaticName)) {
                lastLibIdx = i;
                break;
            }
        }
        if (lastLibIdx < 0) return content;

        // Collect standalone predicate blocks that appear between lib blocks
        boolean seenLib = false;
        List<AcsCommentSpan> toMove = new ArrayList<>();
        for (int i = 0; i < lastLibIdx; i++) {
            AcsCommentSpan sp = spans.get(i);
            if (sp.axiomaticName != null && libAndAxioms.contains(sp.axiomaticName)) {
                seenLib = true;
            } else if (seenLib && sp.axiomaticName == null
                    && LOGIC_OR_PREDICATE_IN_BLOCK.matcher(sp.text).find()) {
                toMove.add(sp);
            }
        }
        if (toMove.isEmpty()) return content;

        // Remove them in reverse order to preserve offsets
        List<AcsCommentSpan> rev = new ArrayList<>(toMove);
        rev.sort(Comparator.comparingInt((AcsCommentSpan s) -> s.start).reversed());
        String result = content;
        List<String> movedTexts = new ArrayList<>();
        for (AcsCommentSpan sp : rev) {
            movedTexts.add(0, result.substring(sp.start, sp.end));
            result = result.substring(0, sp.start) + result.substring(sp.end);
        }

        // Insert after the last axiomatic block (machine or lib) in the modified content
        List<AcsCommentSpan> newSpans = findAllAcsCommentSpans(result);
        int insertAfter = -1;
        for (int i = newSpans.size() - 1; i >= 0; i--) {
            if (newSpans.get(i).axiomaticName != null) {
                insertAfter = newSpans.get(i).end;
                break;
            }
        }
        if (insertAfter < 0) return content;

        StringBuilder insert = new StringBuilder();
        for (String t : movedTexts) insert.append(t);
        return result.substring(0, insertAfter) + insert + result.substring(insertAfter);
    }

    /**
     * Move todos os blocos ACSL de máquina (axiomáticos não-lib e predicados standalone) que
     * aparecem após o primeiro bloco ghost ({@code /*@ ghost ... *\/}) para antes desse bloco.
     * Isso garante que variáveis lógicas de máquina (ex.: {@code books}, {@code copyOf}) estejam
     * declaradas quando o Frama-C processa os contratos de funções ghost.
     */
    private static String moveMachineAcslBlocksBeforeFirstGhostBlock(
            String content, Map<String, Integer> rank) {
        List<AcsCommentSpan> spans = findAllAcsCommentSpans(content);

        // Find the first ghost block (/*@ ghost ... */)
        int firstGhostStart = -1;
        for (AcsCommentSpan sp : spans) {
            if (sp.axiomaticName == null && sp.text.startsWith("/*@ ghost")) {
                firstGhostStart = sp.start;
                break;
            }
        }
        if (firstGhostStart < 0) return content;

        Set<String> libAndAxioms = new LinkedHashSet<>(rank.keySet());
        for (String n : rank.keySet()) libAndAxioms.add(n + "_axioms");

        // Collect machine-specific ACSL blocks that appear AFTER the first ghost block
        List<AcsCommentSpan> toMove = new ArrayList<>();
        for (AcsCommentSpan sp : spans) {
            if (sp.start <= firstGhostStart) continue;
            boolean isLibAxioms = sp.axiomaticName != null
                    && sp.axiomaticName.endsWith("_axioms")
                    && libAndAxioms.contains(resolveParentAxiomaticName(sp.axiomaticName, spans));
            boolean isMachineAxiomatic = sp.axiomaticName != null
                    && !libAndAxioms.contains(sp.axiomaticName)
                    && !isLibAxioms;
            boolean isStandalonePredicate = sp.axiomaticName == null
                    && LOGIC_OR_PREDICATE_IN_BLOCK.matcher(sp.text).find();
            if (isMachineAxiomatic || isStandalonePredicate) {
                toMove.add(sp);
            }
        }
        if (toMove.isEmpty()) return content;

        // Remove them in reverse order to preserve earlier offsets
        List<AcsCommentSpan> rev = new ArrayList<>(toMove);
        rev.sort(Comparator.comparingInt((AcsCommentSpan s) -> s.start).reversed());
        String result = content;
        List<String> movedTexts = new ArrayList<>();
        for (AcsCommentSpan sp : rev) {
            movedTexts.add(0, result.substring(sp.start, sp.end));
            result = result.substring(0, sp.start) + result.substring(sp.end);
        }

        // Find new position of first ghost block in modified content
        List<AcsCommentSpan> newSpans = findAllAcsCommentSpans(result);
        int newGhostStart = -1;
        for (AcsCommentSpan sp : newSpans) {
            if (sp.axiomaticName == null && sp.text.startsWith("/*@ ghost")) {
                newGhostStart = sp.start;
                break;
            }
        }
        if (newGhostStart < 0) return content;

        StringBuilder insertSb = new StringBuilder();
        for (String t : movedTexts) insertSb.append(t);
        return result.substring(0, newGhostStart) + insertSb + result.substring(newGhostStart);
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
        // Caso: base começa com o nome de uma declaração conhecida seguida de "_"
        // ex.: array_to_function_int_axioms → base = array_to_function_int
        //      → começa com "array_to_function_" → pai = array_to_function
        for (String n : declNames) {
            if (base.startsWith(n + "_")) {
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
        // sequence_list_to_function_axioms usa outros símbolos da lib no seu PRÓPRIO corpo (ex.
        // length(list_to_function(l)), is_sequence(...), function_apply(...)) — mover o bloco para
        // logo antes do seu primeiro USO real, sem olhar para essas dependências, pode reintroduzir a
        // MESMA violação declare-antes-de-usar que este passo tenta resolver, só que para o símbolo
        // dependente (ex. "length") em vez de para list_to_function. Empurra o ponto de inserção para
        // depois de qualquer declaração (logic/predicate) já presente no ficheiro de que
        // list_to_function dependa transitivamente.
        insertAt = pushInsertAfterSymbolDeclDependencies(cut, insertAt, "list_to_function");

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

    /**
     * Empurra {@code insertAt} para depois de qualquer declaração ({@code logic}/{@code predicate})
     * já presente em {@code content}, de um símbolo do qual {@code rootSymbol} dependa transitivamente
     * (ver {@link AcslLibSymbolDependencyMap#transitiveDependencySymbols(String)}) — necessário quando
     * se reposiciona {@code rootSymbol} (ex. {@code list_to_function}) para logo antes do seu próprio
     * primeiro USO: se um símbolo de que ele depende (ex. {@code length}, referenciado dentro do
     * próprio corpo dos axiomas de {@code list_to_function}) já estiver declarado MAIS À FRENTE no
     * ficheiro do que o ponto de inserção calculado, mover para lá deixaria essa dependência por
     * declarar nesse ponto — a mesma violação declare-antes-de-usar que esta função tenta resolver,
     * só que para o símbolo dependente. Devolve {@code insertAt} inalterado se nenhuma dependência
     * conhecida tiver uma declaração no ficheiro em posição igual/posterior.
     */
    private static int pushInsertAfterSymbolDeclDependencies(
            String content, int insertAt, String rootSymbol) {
        Set<String> deps =
                AcslLibSymbolDependencyMap.instance().transitiveDependencySymbols(rootSymbol);
        if (deps.isEmpty()) {
            return insertAt;
        }
        List<AcsCommentSpan> spans = findAllAcsCommentSpans(content);
        int result = insertAt;
        for (String dep : deps) {
            if (dep.equals(rootSymbol)) {
                continue;
            }
            Pattern declPattern =
                    Pattern.compile(
                            "(?m)^.*\\b(?:logic|predicate)\\b.*?\\b"
                                    + Pattern.quote(dep)
                                    + "\\s*\\(");
            for (AcsCommentSpan sp : spans) {
                if (sp.end <= result) {
                    continue;
                }
                if (declPattern.matcher(sp.text).find()) {
                    result = Math.max(result, sp.end);
                }
            }
        }
        return result;
    }

}
