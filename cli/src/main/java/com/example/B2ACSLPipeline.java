package com.example;

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

    private static final boolean MOCK_MODE = B2AcslConfig.fromSystemProperties().mock();

    private B2ACSLPipeline() {}

    /**
     * Resultado da fase de geração ({@link #generate}): tudo o que o passo de verificação
     * (Frama-C) precisa, sem ainda o ter executado. {@code exitCode != 0} indica que a geração
     * parou antes do fim (mesmos códigos de {@link #run}) e os demais campos podem estar vazios.
     */
    public record GenerationResult(
            int exitCode,
            Path bdp,
            Path cDir,
            Path acslDir,
            List<Path> topLevelAcslFiles,
            List<Path> acslFiles,
            BxmlSeesGraph seesGraph,
            BxmlImportsGraph importsGraph,
            List<Path> cFiles,
            List<String> specificationUsedTypes,
            String projectName) {

        static GenerationResult failed(int exitCode, Path bdp) {
            return new GenerationResult(
                    exitCode, bdp, null, null, List.of(), List.of(), null, null, List.of(), List.of(),
                    inferProjectNameFromBdp(bdp));
        }
    }

    /**
     * Estrutura de um projeto B lida dos {@code .bxml} (grafos SEES/IMPORTS e máquinas abstratas
     * raiz), sem gerar nada — para quem precisa ordenar/verificar {@code .acsl} produzidos por outra
     * ferramenta a partir da mesma entrada BXML.
     */
    public record ProjectStructure(
            BxmlSeesGraph seesGraph,
            BxmlImportsGraph importsGraph,
            List<String> topLevelMachineNames,
            List<Element> abstractMachineRoots) {}

    /** Lê a estrutura (SEES/IMPORTS, máquinas abstratas raiz) dos {@code .bxml} de {@code bdp}. */
    public static ProjectStructure loadProjectStructure(Path bdpPath) throws Exception {
        Path bdp = bdpPath.toAbsolutePath().normalize();
        List<Path> bxmlFiles = findBxmlFiles(bdp);
        BxmlSeesGraph seesGraph = BxmlSeesGraph.fromBxmlDirectory(bdp);
        BxmlImportsGraph importsGraph =
                BxmlImportsGraph.fromBxmlDirectory(bdp, buildAbstractionParentMap(bxmlFiles));
        List<Element> abstractMachineRoots = new ArrayList<>();
        Set<String> abstractMachineNames = new LinkedHashSet<>();
        for (Path f : bxmlFiles) {
            Element root = AcslGenerator.parseMachineElement(f);
            if (AcslGenerator.getAbstractionReferenceName(root).isPresent()) {
                continue;
            }
            abstractMachineRoots.add(root);
            abstractMachineNames.add(root.getAttribute("name"));
        }
        List<String> topLevel =
                topLevelImportMachineNames(
                        abstractMachineNames, dependencyOnlyMachineNames(seesGraph, importsGraph));
        return new ProjectStructure(
                seesGraph, importsGraph, topLevel, List.copyOf(abstractMachineRoots));
    }

    /**
     * Executa o pipeline completo.
     *
     * @param bdpPath Caminho da pasta bdp (contém os .bxml)
     * @return Código de retorno para o Atelier B (0=sucesso, !=0=falha)
     */
    public static int run(Path bdpPath) throws Exception {
        GenerationResult generation = generate(bdpPath);
        if (generation.exitCode() != 0) {
            return generation.exitCode();
        }

        // Step 3: Executar Frama-C (acsl-importer + WP)
        int framaResult;
        if (MOCK_MODE) {
            framaResult =
                    runMockFramaC(
                            generation.topLevelAcslFiles(), generation.cFiles(), generation.cDir());
        } else {
            WpOptions wpOptions = WpOptionsDialog.promptWpOptions(generation.projectName());
            if (wpOptions == null) {
                System.err.println("[B2ACSL] Execution cancelled by user.");
                return 7;
            }
            String selectedProjectName = wpOptions.projectName();
            ExternalVerifierRunner verifierRunner = new FramaCVerifierRunner();
            framaResult =
                    verifierRunner.runFramaC(
                            generation.topLevelAcslFiles(),
                            generation.acslFiles(),
                            generation.acslDir(),
                            generation.seesGraph(),
                            generation.importsGraph(),
                            generation.cFiles(),
                            generation.cDir(),
                            generation.specificationUsedTypes(),
                            wpOptions,
                            selectedProjectName);
        }

        // Step 4: Retornar valor para Atelier B
        return framaResult;
    }

    /**
     * Fase de geração do pipeline (passos 1 a 2.1): BXML → {@code ghost_operations.ci} → {@code
     * .acsl} → lista de tipos da especificação, gravados em {@code <proj>/lang/c}. Não executa o
     * Frama-C — ver {@link #run} para o pipeline completo.
     *
     * @param bdpPath Caminho da pasta bdp (contém os .bxml)
     */
    public static GenerationResult generate(Path bdpPath) throws Exception {
        Path bdp = bdpPath.toAbsolutePath().normalize();
        if (!Files.isDirectory(bdp)) {
            System.err.println("[B2ACSL] Caminho inválido (não é diretório): " + bdp);
            return GenerationResult.failed(1, bdp);
        }

        // Step 1: Ler arquivos .bxml
        List<Path> bxmlFiles = findBxmlFiles(bdp);
        if (bxmlFiles.isEmpty()) {
            System.err.println("[B2ACSL] Nenhum arquivo .bxml encontrado em: " + bdp);
            return GenerationResult.failed(2, bdp);
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
            return GenerationResult.failed(3, bdp);
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
        // Staging dos ficheiros da lib sob cDir (elimina cópias redundantes em target/) — cDir
        // flui como o parâmetro Path outputDir já passado a AcslGenerator.generateAcsl (abaixo),
        // que por sua vez o repassa a AcslLibIncludes.copyReferencedLibraryFiles(...) como
        // targetAcslDir; já não é preciso um System.setProperty (era o único escritor de
        // "b2acsl.targetAcslDir" em todo o projeto — ver AcslLibIncludes#resolveTargetAcslStagingRoot).
        // Um projeto pode ter MAIS DE UMA IMPLEMENTATION alternativa da mesma máquina abstrata
        // (ex.: Seats_i e Seats_i_2, ambas "REFINES Seats" — duas estratégias de refinamento
        // diferentes guardadas lado a lado). mergePathsByRootAbstract (acima) inclui TODAS sem
        // filtrar, o que faz o .acsl gerado declarar variáveis/invariantes das DUAS (ex.
        // Seats_i_variables E Seats_i_2_variables) mesmo quando só uma delas tem código C
        // efetivamente gerado em cDir — a outra referencia globais C inexistentes ("unbound logic
        // variable Seats__available_i"). Filtra para a(s) implementação(ões) cujo .c já existe em
        // cDir, quando isso reduz a lista sem a esvaziar (nunca filtra o caso normal de uma só
        // implementação, nem o modo mock sem nenhum .c ainda gerado).
        filterMergePathsToAvailableImplementations(mergePathsByRootAbstract, cDir);
        // Pré-passo: regista qual máquina primeiro reivindica cada nome de variável abstrata,
        // entre as máquinas que precisam de abstração ghost — detecta colisões de nome entre
        // máquinas B SEM relação SEES/IMPORTS entre si que coincidentemente usam o mesmo nome de
        // variável (ex.: "enregistrementCroisillon" em rmc_deplacement/rmc_deplacement_1/
        // rmc_entrees no projeto railmap). Sem isto, ghost_operations.ci e os .acsl de cada
        // máquina acabam com MAIS DE UMA declaração global do MESMO símbolo lógico — o Frama-C
        // rejeita com "already declared with the same profile" quando os tipos divergem, e
        // quando calham a bater tipo (aceite silenciosamente) as duas máquinas ficam a partilhar
        // sem querer um único símbolo ghost. Ordem de iteração = ordem de "machines" abaixo, logo
        // determinística: a 1ª máquina a reivindicar um nome mantém-no tal-e-qual (sem
        // qualificação, comportamento inalterado); só a 2ª+ ocorrência do MESMO nome é
        // qualificada (ver qualifyCollidingGhostNamesInAppendedSection/qualifyCollidingRealAcslNames
        // abaixo) — projetos sem nenhuma colisão (a esmagadora maioria) ficam byte-a-byte iguais.
        Map<String, String> machineByOwnedVarName = new HashMap<>();
        for (MachineFile mf : machines) {
            Element mr = mf.machine().getMachineElement();
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
            for (String v : ownAbstractVariableNames(mr, mergedEls)) {
                machineByOwnedVarName.putIfAbsent(v, machineName);
            }
        }

        // Limpa antes do loop: GhostOperationsCiGenerator.write() ACRESCENTA (não sobrescreve) a
        // cada chamada — ver comentário abaixo — por isso o ficheiro precisa de começar vazio a
        // cada execução, senão conteúdo de uma run anterior ficaria duplicado.
        Path ghostCiTargetPath = GhostOperationsCiGenerator.targetPath(cDir);
        Files.deleteIfExists(ghostCiTargetPath);
        boolean anyNeedsGhost = false;
        for (MachineFile mf : machines) {
            Element mr = mf.machine().getMachineElement();
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
            Set<String> collidingVars =
                    collidingAbstractVarNames(mr, mergedEls, machineName, machineByOwnedVarName);
            String ghostCiBefore =
                    Files.isRegularFile(ghostCiTargetPath)
                            ? Files.readString(ghostCiTargetPath, StandardCharsets.UTF_8)
                            : "";
            // write() ACRESCENTA ao ghost_operations.ci (não sobrescreve): num projeto com VÁRIAS
            // máquinas a precisar de abstração ghost (ex.: Customer_estr tem Customer E Set), cada
            // chamada usava Files.writeString sem APPEND, e só a ÚLTIMA máquina processada
            // sobrevivia no ficheiro final — as declarações ghost_purchases/ghost_limit de Customer
            // desapareciam silenciosamente, substituídas pelas de Set, dando "unbound logic
            // variable ghost_purchases" no Frama-C. O bloco "axiomatic dummy_ghost { ... }" (mesmo
            // nome/boilerplate genérico em toda chamada) que cada write() acrescenta é fundido num
            // só logo a seguir ao loop, para não duplicar "type DSet<A>;" etc.
            GhostOperationsCiGenerator.write(
                    cDir, mr, invariantGluingSubstitutions, bdp, mergedEls, seesGraph, importsGraph);
            if (!collidingVars.isEmpty()) {
                qualifyCollidingGhostNamesInAppendedSection(
                        ghostCiTargetPath, ghostCiBefore, machineName, collidingVars);
            }
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
            AcslLibraryResolver libraryResolver = new DefaultAcslLibraryResolver();
            libraryResolver.resetLibraryBundleUnderOutput(acslDir);
            List<Path> acslFiles = new ArrayList<>();
            // ** (Binary_Exp op='**i') não tem operador C nativo — verifica em TODAS as máquinas
            // do projeto (abstratas E implementações/refinamentos, não só as que geram .acsl
            // próprio) se alguma a usa, para decidir se b_pow.acsl (especificação da função de
            // runtime auxiliar b_pow, sem a qual WP não prova nada sobre operações como pow_a que
            // chamam essa função) precisa de ser gerado.
            boolean usesIntegerPowerOperator = false;
            for (MachineFile mf : machines) {
                if (containsIntegerPowerOperator(mf.machine().getMachineElement())) {
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
                Element machineRoot = mf.machine().getMachineElement();
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
                Element machineRoot = mf.machine().getMachineElement();
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
                if (acsl.isPresent()) {
                    List<Element> mergedEls = new ArrayList<>();
                    for (Path mp : mergePaths) {
                        mergedEls.add(AcslGenerator.parseMachineElement(mp));
                    }
                    Set<String> collidingVars =
                            collidingAbstractVarNames(
                                    machineRoot, mergedEls, machineName, machineByOwnedVarName);
                    if (!collidingVars.isEmpty()) {
                        qualifyCollidingRealAcslNames(acsl.get(), machineName, collidingVars);
                    }
                }
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
                Element machineRoot = mf.machine().getMachineElement();
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
                return GenerationResult.failed(4, bdp);
            }

            return new GenerationResult(
                    0,
                    bdp,
                    cDir,
                    acslDir,
                    List.copyOf(topLevelAcslFiles),
                    List.copyOf(acslFiles),
                    seesGraph,
                    importsGraph,
                    cFiles,
                    specificationUsedTypes,
                    inferProjectNameFromBdp(bdp));
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

    private static List<Path> findCFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".c"))
                    .filter(p -> !FramaCRunner.MERGED_CODE_FILE_NAME.equalsIgnoreCase(p.getFileName().toString()))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    /**
     * Filtra {@code mergePathsByRootAbstract} (mutado in-place) para remover IMPLEMENTATIONs
     * alternativas (mesma máquina abstrata, {@code type='implementation'} em ambas) sem {@code .c}
     * gerado em {@code cDir} — ver comentário no ponto de chamada. Só age quando há 2+
     * implementações concorrentes para a mesma raiz E pelo menos uma delas tem {@code .c}; nunca
     * esvazia a lista por completo (se nenhuma bater, mantém tudo como estava).
     */
    private static void filterMergePathsToAvailableImplementations(
            Map<String, List<Path>> mergePathsByRootAbstract, Path cDir) {
        for (Map.Entry<String, List<Path>> entry : mergePathsByRootAbstract.entrySet()) {
            List<Path> paths = entry.getValue();
            List<Path> implementationPaths = new ArrayList<>();
            for (Path p : paths) {
                try {
                    Element el = AcslGenerator.parseMachineElement(p);
                    if ("implementation".equalsIgnoreCase(el.getAttribute("type"))) {
                        implementationPaths.add(p);
                    }
                } catch (Exception ignored) {
                    // ficheiro inválido; ignorado aqui, já reportado noutro ponto do pipeline
                }
            }
            if (implementationPaths.size() < 2) {
                continue;
            }
            List<Path> withGeneratedC = new ArrayList<>();
            for (Path p : implementationPaths) {
                try {
                    String machineName = AcslGenerator.parseMachineElement(p).getAttribute("name");
                    if (machineName != null && !machineName.isBlank() && hasGeneratedCFile(cDir, machineName.trim())) {
                        withGeneratedC.add(p);
                    }
                } catch (Exception ignored) {
                }
            }
            if (!withGeneratedC.isEmpty() && withGeneratedC.size() < implementationPaths.size()) {
                List<Path> filtered = new ArrayList<>(paths);
                filtered.removeIf(p -> implementationPaths.contains(p) && !withGeneratedC.contains(p));
                entry.setValue(filtered);
            }
        }
    }

    private static boolean hasGeneratedCFile(Path cDir, String machineName) {
        if (!Files.isDirectory(cDir)) {
            return false;
        }
        String target = machineName + ".c";
        try (var stream = Files.walk(cDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .anyMatch(p -> p.getFileName().toString().equalsIgnoreCase(target));
        } catch (IOException e) {
            return false;
        }
    }

    private static int runMockFramaC(List<Path> acslFiles, List<Path> cFiles, Path cDir) {
        System.out.println("[B2ACSL] [MOCK] ACSL gerados: " + acslFiles.size());
        System.out.println("[B2ACSL] [MOCK] Arquivos C: " + cFiles.size());
        System.out.println("[B2ACSL] [MOCK] Simulando acsl-importer + WP -> OK");
        return 0;
    }

    /**
     * Nomes das variáveis abstratas PRÓPRIAS de {@code machineEl} (excluindo as colapsadas na
     * implementação — ver {@link BxmlMachineVariables#collapsedIntoImplementationVariableNames}),
     * exatamente como {@link GhostOperationsCiGenerator#write} as calcula internamente — reutilizado
     * aqui para o pré-passo de deteção de colisão de nomes entre máquinas (ver comentário acima do
     * loop que constrói {@code machineByOwnedVarName}).
     */
    private static List<String> ownAbstractVariableNames(
            Element machineEl, List<Element> mergedMachineElements) {
        Set<String> collapsed =
                BxmlMachineVariables.collapsedIntoImplementationVariableNames(
                        machineEl, mergedMachineElements);
        return GhostOperationsCiGenerator.listAbstractVariableNames(machineEl).stream()
                .filter(v -> !collapsed.contains(v))
                .toList();
    }

    /**
     * Variáveis abstratas PRÓPRIAS de {@code machineEl} cujo nome já foi reivindicado por OUTRA
     * máquina em {@code machineByOwnedVarName} — candidatas a qualificação (ver {@link
     * #qualifyCollidingGhostNamesInAppendedSection}/{@link #qualifyCollidingRealAcslNames}).
     */
    private static Set<String> collidingAbstractVarNames(
            Element machineEl,
            List<Element> mergedMachineElements,
            String machineName,
            Map<String, String> machineByOwnedVarName) {
        Set<String> colliding = new LinkedHashSet<>();
        for (String v : ownAbstractVariableNames(machineEl, mergedMachineElements)) {
            String owner = machineByOwnedVarName.get(v);
            if (owner != null && !owner.equals(machineName)) {
                colliding.add(v);
            }
        }
        return colliding;
    }

    /**
     * Qualifica, com o prefixo {@code <machineName>__}, as referências ghost ({@code dummy_<v>}/
     * {@code ghost_<v>}) de {@code collidingVars} — mas SÓ dentro do trecho que a chamada a {@link
     * GhostOperationsCiGenerator#write} que acabou de correr ACRESCENTOU a {@code ghostCiPath}
     * (identificado por {@code ghostCiTextBeforeWrite}, o conteúdo do ficheiro capturado
     * imediatamente antes dessa chamada — {@code write()} só faz append, nunca reescreve o que já lá
     * estava). Evita renomear a ocorrência já existente (bare, da 1ª máquina a reivindicar o nome)
     * doutra secção do ficheiro.
     */
    private static void qualifyCollidingGhostNamesInAppendedSection(
            Path ghostCiPath, String ghostCiTextBeforeWrite, String machineName, Set<String> collidingVars)
            throws IOException {
        if (!Files.isRegularFile(ghostCiPath)) {
            return;
        }
        String full = Files.readString(ghostCiPath, StandardCharsets.UTF_8);
        if (full.length() <= ghostCiTextBeforeWrite.length()) {
            return;
        }
        String head = full.substring(0, ghostCiTextBeforeWrite.length());
        String tail = full.substring(ghostCiTextBeforeWrite.length());
        String qualifiedTail = tail;
        for (String v : collidingVars) {
            String suffix = machineName + "__" + v;
            qualifiedTail =
                    Pattern.compile("\\bdummy_" + Pattern.quote(v) + "\\b")
                            .matcher(qualifiedTail)
                            .replaceAll(Matcher.quoteReplacement("dummy_" + suffix));
            qualifiedTail =
                    Pattern.compile("\\bghost_" + Pattern.quote(v) + "\\b")
                            .matcher(qualifiedTail)
                            .replaceAll(Matcher.quoteReplacement("ghost_" + suffix));
        }
        if (!qualifiedTail.equals(tail)) {
            Files.writeString(ghostCiPath, head + qualifiedTail, StandardCharsets.UTF_8);
        }
    }

    /**
     * Qualifica, com o prefixo {@code <machineName>__}, as declarações/referências do símbolo lógico
     * real ({@code <v>}) e do respetivo helper {@code dummy_ghost_<v>} (usado na cláusula
     * {@code reads}) de {@code collidingVars}, dentro do {@code .acsl} já gerado para esta máquina —
     * mesmo problema que {@link #qualifyCollidingGhostNamesInAppendedSection}, mas do lado REAL
     * (não-isolado): cada máquina declara {@code logic <T> <v> reads dummy_ghost_<v>;} no seu
     * PRÓPRIO {@code .acsl}, e todos os {@code .acsl} do projeto são carregados juntos numa ÚNICA
     * invocação {@code -acsl-import} (sem isolamento de namespace por ficheiro) — confirmado que sem
     * esta qualificação o Frama-C rejeita com o mesmo "already declared with the same profile" que
     * o lado ghost, só que mais tarde. {@code dummy_ghost_<v>} é substituído PRIMEIRO: a boundary
     * regex do passo seguinte (bare {@code <v>}) não volta a casar dentro do resultado já
     * qualificado (o "_" antes de {@code <v>} em "...__<v>" nunca é fronteira de palavra).
     */
    private static void qualifyCollidingRealAcslNames(
            Path acslFile, String machineName, Set<String> collidingVars) throws IOException {
        String content = Files.readString(acslFile, StandardCharsets.UTF_8);
        String updated = content;
        for (String v : collidingVars) {
            String suffix = machineName + "__" + v;
            updated =
                    Pattern.compile("\\bdummy_ghost_" + Pattern.quote(v) + "\\b")
                            .matcher(updated)
                            .replaceAll(Matcher.quoteReplacement("dummy_ghost_" + suffix));
            updated =
                    Pattern.compile("\\b" + Pattern.quote(v) + "\\b")
                            .matcher(updated)
                            .replaceAll(Matcher.quoteReplacement(suffix));
        }
        if (!updated.equals(content)) {
            Files.writeString(acslFile, updated, StandardCharsets.UTF_8);
        }
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
                Element root = mf.machine().getMachineElement();
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

}
