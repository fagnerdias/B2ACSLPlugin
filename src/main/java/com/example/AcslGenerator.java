package com.example;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.example.bxml.BxmlInitialisationTranslator;
import com.example.bxml.BxmlInitialisationTranslator.InitialisationAcsl;
import com.example.bxml.BxmlConnectionAcsl;
import com.example.bxml.GhostOperationsCiGenerator;
import com.example.bxml.BxmlComprehensionRegistry;
import com.example.bxml.BxmlConstantsAndProperties;
import com.example.bxml.BxmlInvariantTranslator;
import com.example.bxml.BxmlMachineVariables;
import com.example.bxml.BxmlOperationsTranslator;
import com.example.bxml.BxmlOperationsTranslator.OperationAcsl;
import com.example.bxml.BxmlImportsGraph;
import com.example.bxml.BxmlSeesGraph;
import com.example.bxml.BxmlSetsTranslator;
import com.example.bxml.BxmlTranslateContext;
import com.example.bxml.LambdaFunctionRegistry;
import com.example.model.Machine;

/**
 * Gera arquivos ACSL a partir de BXML e funções da biblioteca {@code B2ACSLLib} em
 * {@code src/main/resources/lib/B2ACSLLib}.
 *
 * <p>Refinamentos e implementações com {@code <Abstraction>…</Abstraction>} <strong>não</strong> geram
 * {@code .acsl} próprio; os blocos {@code axiomatic} (compreensões) e {@code predicate} (invariantes)
 * dessas máquinas são traduzidos e <strong>anexados</strong> ao ficheiro da máquina abstrata raiz
 * (cadeia {@code Abstraction} resolvida no pipeline).
 *
 * <p>Na máquina abstrata: compreensões e invariantes locais entram no {@code .acsl}; já
 * {@code Initialisation} e {@code Operations} só quando {@code type="abstraction"}.
 * Os invariantes fundidos dos refinamentos/implementações repetem-se em {@code requires} e
 * {@code ensures} de cada operação (e em {@code ensures} da inicialização), juntamente com o
 * invariante da abstrata.
 *
 * <p>{@code Concrete_Constants} → {@code axiomatic Nome_constants}; {@code Properties} →
 * {@code axiomatic Nome_properties}; {@code Values} (implementações) → {@code axiomatic Nome_values}
 * ({@link BxmlConstantsAndProperties}); em seguida
 * {@code include "connection.acsl"} se existir refinamento fundido na abstração ({@link BxmlConnectionAcsl},
 * só elo abstração→refinamento); na raiz de importação Frama-C ({@code SEES}/{@code IMPORTS}), um único
 * bloco {@code include "Dep.acsl"} com o fecho transitivo em ordem topológica
 * ({@link BxmlSetsTranslator#formatTransitiveDependencyIncludeBlock}); máquinas só dependentes não
 * repetem includes.
 *
 * <p>Variáveis: um bloco {@code axiomatic NomeMaquina_variables} por máquina (abstrata e cada
 * refinamento/implementação fundido), tipos inferidos quando possível ({@link BxmlMachineVariables});
 * nas implementações fundidas, cada variável concreta declara-se com {@code = RaizAbstrata__nome}.
 */
public final class AcslGenerator {

    private AcslGenerator() {}

    /**
     * Raiz XML {@code <Machine>} do ficheiro BXML.
     */
    public static Element parseMachineElement(Path bxmlPath) throws Exception {
        return parseXml(bxmlPath).getDocumentElement();
    }

    /**
     * Texto do primeiro {@code <Abstraction>} não vazio, se existir.
     */
    public static Optional<String> getAbstractionReferenceName(Element machineEl) {
        NodeList children = machineEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (!"Abstraction".equals(e.getLocalName())) continue;
            String t = e.getTextContent();
            if (t != null && !t.trim().isBlank()) return Optional.of(t.trim());
        }
        return Optional.empty();
    }

    /**
     * Gera {@code <machineName>.acsl} quando aplicável.
     *
     * @param mergeBxmlPathsFromDescendants ficheiros BXML de refinamento/implementação cuja cadeia
     *        {@code Abstraction} aponta para esta máquina (conteúdo fundido no fim; compreensões
     *        duplicadas face à abstrata são omitidas)
     * @param gluingSubstitutionsFromInvariants ex.: {@code ran(numbers_s) → numbers} de todos os
     *        invariantes (para alinhar fingerprints com a abstração)
     * @return caminho do ficheiro criado, ou vazio se a máquina for refinamento/implementação com
     *         {@code <Abstraction>} (não se cria ficheiro novo)
     */
    public static Optional<Path> generateAcsl(
            Machine machine,
            Path bxmlPath,
            Path outputDir,
            List<Path> mergeBxmlPathsFromDescendants,
            Map<String, String> gluingSubstitutionsFromInvariants)
            throws Exception {
        return generateAcsl(
                machine,
                bxmlPath,
                outputDir,
                mergeBxmlPathsFromDescendants,
                gluingSubstitutionsFromInvariants,
                Set.of());
    }

    /**
     * @param seenOnlyMachineNames máquinas só referenciadas em {@code SEES} ou {@code IMPORTS} de outra
     *     (ex. {@code Airlock_pressure_bs}); o {@code .acsl} gerado não repete includes da
     *     {@code B2ACSLLib} — ficam na máquina que vê/importa.
     */
    public static Optional<Path> generateAcsl(
            Machine machine,
            Path bxmlPath,
            Path outputDir,
            List<Path> mergeBxmlPathsFromDescendants,
            Map<String, String> gluingSubstitutionsFromInvariants,
            Set<String> seenOnlyMachineNames)
            throws Exception {
        return generateAcsl(
                machine,
                bxmlPath,
                outputDir,
                mergeBxmlPathsFromDescendants,
                gluingSubstitutionsFromInvariants,
                seenOnlyMachineNames,
                null,
                null);
    }

    /**
     * @param seesGraph grafo {@code SEES} do projeto (pode ser {@code null})
     * @param importsGraph grafo {@code IMPORTS} do projeto (pode ser {@code null})
     */
    public static Optional<Path> generateAcsl(
            Machine machine,
            Path bxmlPath,
            Path outputDir,
            List<Path> mergeBxmlPathsFromDescendants,
            Map<String, String> gluingSubstitutionsFromInvariants,
            Set<String> seenOnlyMachineNames,
            BxmlSeesGraph seesGraph,
            BxmlImportsGraph importsGraph)
            throws Exception {
        return generateAcsl(
                machine,
                bxmlPath,
                outputDir,
                mergeBxmlPathsFromDescendants,
                gluingSubstitutionsFromInvariants,
                seenOnlyMachineNames,
                seesGraph,
                importsGraph,
                "",
                "");
    }

    /**
     * @param libIncludeCarrierMachineName única máquina cujo {@code .acsl} traz includes da
     *     {@code B2ACSLLib} (importação Frama-C multi-ficheiro); vazio = comportamento legado por
     *     máquina
     * @param libIncludeScanRootMachineName raiz do projeto para varredura transitiva de símbolos da
     *     lib (ex. {@code entry_point} quando o portador é {@code ctx})
     */
    public static Optional<Path> generateAcsl(
            Machine machine,
            Path bxmlPath,
            Path outputDir,
            List<Path> mergeBxmlPathsFromDescendants,
            Map<String, String> gluingSubstitutionsFromInvariants,
            Set<String> seenOnlyMachineNames,
            BxmlSeesGraph seesGraph,
            BxmlImportsGraph importsGraph,
            String libIncludeCarrierMachineName,
            String libIncludeScanRootMachineName)
            throws Exception {
        Document doc = parseXml(bxmlPath);
        Element machineEl = doc.getDocumentElement();
        if (referencesAbstractMachineViaAbstractionTag(machineEl)) {
            return Optional.empty();
        }

        Files.createDirectories(outputDir);
        String baseName = machine.getMachineName();
        Path acslFile = outputDir.resolve(baseName + ".acsl");
        boolean libCarrier =
                libIncludeCarrierMachineName != null
                        && !libIncludeCarrierMachineName.isBlank()
                        && baseName.equals(libIncludeCarrierMachineName.trim());
        boolean omitLibIncludesFromPreamble =
                libIncludeCarrierMachineName != null && !libIncludeCarrierMachineName.isBlank()
                        ? !libCarrier
                        : seenOnlyMachineNames != null && seenOnlyMachineNames.contains(baseName);

        List<Path> mergePaths =
                mergeBxmlPathsFromDescendants == null ? List.of() : mergeBxmlPathsFromDescendants;
        Map<String, String> gluing =
                gluingSubstitutionsFromInvariants == null ? Map.of() : gluingSubstitutionsFromInvariants;

        boolean isAbstraction = isAbstractMachine(machineEl);

        Set<String> abstractVariableNamesForGhost =
                new LinkedHashSet<>(GhostOperationsCiGenerator.listAbstractVariableNames(machineEl));

        List<Element> mergedMachineElements = new ArrayList<>();
        for (Path p : mergePaths) {
            mergedMachineElements.add(parseMachineElement(p));
        }

        List<Element> comprehensionChain = new ArrayList<>();
        comprehensionChain.add(machineEl);
        comprehensionChain.addAll(mergedMachineElements);
        BxmlComprehensionRegistry sharedComprehensions =
                BxmlComprehensionRegistry.fromMachineChain(comprehensionChain, gluing);
        sharedComprehensions.assignDedupIndices();

        Path bxmlDirectory = bxmlPath.getParent();
        BxmlTranslateContext ctx =
                BxmlTranslateContext.forMachineWithSharedComprehensions(
                        machineEl, sharedComprehensions, gluing)
                        .withLambdaRegistry(new LambdaFunctionRegistry())
                        .withEnumRenames(
                                BxmlSetsTranslator.buildEnumRenamesWithSees(
                                        machineEl, mergedMachineElements, bxmlDirectory))
                        .withEnumeratedSetRenames(
                                BxmlSetsTranslator.buildEnumeratedSetRenamesWithSees(
                                        machineEl, mergedMachineElements, bxmlDirectory))
                        .withEnumeratedSetNames(
                                BxmlSetsTranslator.buildEnumeratedSetNames(machineEl));

        List<String> allInvariantPredicateNames =
                listAllInvariantPredicateNames(machineEl, ctx, mergedMachineElements, gluing);
        List<String> implementationAssignTargets =
                BxmlMachineVariables.listInitialisationAssignTargets(
                        baseName, machineEl, mergedMachineElements, ctx);
        boolean useGhostAbstraction =
                BxmlMachineVariables.needsGhostAbstraction(machineEl, mergedMachineElements);
        Set<String> operationStateVariableNames =
                new LinkedHashSet<>(GhostOperationsCiGenerator.listAbstractVariableNames(machineEl));
        if (!useGhostAbstraction) {
            operationStateVariableNames.addAll(
                    BxmlMachineVariables.declaredVariableNames(machineEl));
        }
        InitialisationAcsl initBare =
                BxmlInitialisationTranslator.translate(
                        machineEl, implementationAssignTargets, ctx);
        boolean initGhostAssert =
                useGhostAbstraction
                        && GhostOperationsCiGenerator.initialisationAssignsAbstract(
                                machineEl, abstractVariableNamesForGhost);
        List<String> dummyGhostVarsForInit =
                initGhostAssert
                        ? GhostOperationsCiGenerator.listAbstractVariableNames(machineEl)
                        : List.of();
        List<String> initEnsuresForContract =
                initGhostAssert ? List.of() : new ArrayList<>(initBare.ensures());
        InitialisationAcsl initMarked =
                new InitialisationAcsl(
                        initBare.functionName(),
                        initEnsuresForContract,
                        initBare.assignsTargets(),
                        initGhostAssert,
                        dummyGhostVarsForInit);
        InitialisationAcsl init =
                isAbstraction
                        ? withInvariantEnsures(initMarked, allInvariantPredicateNames)
                        : null;
        StringBuilder libScanRemovedBodies = new StringBuilder();
        if (initGhostAssert) {
            for (String e : initBare.ensures()) {
                if (e != null && !e.isBlank()) {
                    libScanRemovedBodies.append(e).append('\n');
                }
            }
        }
        List<OperationAcsl> operations =
                isAbstraction
                        ? BxmlOperationsTranslator.translateOperations(
                                machineEl,
                                ctx,
                                allInvariantPredicateNames,
                                operationStateVariableNames,
                                libScanRemovedBodies,
                                baseName,
                                mergedMachineElements,
                                gluing,
                                useGhostAbstraction)
                        : List.of();

        StringBuilder sb = new StringBuilder();
        sb.append("/* ACSL gerado a partir de ").append(baseName).append(".bxml (BXML 1.0) */\n");
        sb.append(
                "/* Biblioteca B2ACSLLib: includes gerados automaticamente (AcslLibIncludes); "
                        + "opções: b2acsl.acslLibIncludeBase, b2acsl.acslLibIncludeMiddle. */\n\n");
        int headerLen = sb.length();

        // 0) Conjuntos deferred (Sets) — posicionados logo após os includes
        String setsBlock = BxmlSetsTranslator.formatSetsBlock(machineEl, mergedMachineElements, bxmlDirectory);
        if (!setsBlock.isBlank()) {
            sb.append(setsBlock);
            sb.append("\n");
        }

        // 1) Constantes e propriedades (só máquina abstrata raiz deste ficheiro)
        String concreteConstants = BxmlConstantsAndProperties.formatConcreteConstantsBlock(machineEl, ctx);
        if (!concreteConstants.isBlank()) {
            sb.append(concreteConstants);
            if (!concreteConstants.endsWith("\n")) sb.append("\n");
            sb.append("\n");
        }
        String propertiesBlock = BxmlConstantsAndProperties.formatPropertiesBlock(machineEl, ctx);
        if (!propertiesBlock.isBlank()) {
            sb.append(propertiesBlock);
            if (!propertiesBlock.endsWith("\n")) sb.append("\n");
            sb.append("\n");
        }
        String valuesRoot = BxmlConstantsAndProperties.formatValuesBlock(machineEl, ctx);
        if (!valuesRoot.isBlank()) {
            sb.append(valuesRoot);
            if (!valuesRoot.endsWith("\n")) sb.append("\n");
            sb.append("\n");
        }

        Optional<Path> connectionAcsl =
                BxmlConnectionAcsl.writeConnectionAcsl(
                        outputDir, baseName, machineEl, mergedMachineElements, gluing);
        connectionAcsl.ifPresent(
                p -> sb.append("include \"").append(p.getFileName().toString()).append("\";\n\n"));

        String ghostPatternsBlock =
                useGhostAbstraction
                        ? GhostOperationsCiGenerator.formatGhostPatternsAxiomaticBlock(
                                machineEl, baseName, ctx.variableLogicTypes())
                        : "";
        if (!ghostPatternsBlock.isBlank()) {
            sb.append(ghostPatternsBlock);
            if (!ghostPatternsBlock.endsWith("\n")) sb.append("\n");
            sb.append("\n");
        }

        // 1b) Variáveis em sequência: abstrata → refinamentos → implementação.
        // Cada bloco _r_variables / _i_variables pode referenciar variáveis dos blocos anteriores
        // (e.g. numbers_s usa numbers), por isso todos os blocos de variáveis devem vir ANTES das
        // compreensões e das constantes/propriedades das máquinas fundidas.
        String concreteLinkRoot =
                BxmlMachineVariables.anyImplementationUsesAbstractVariablesOnly(
                                machineEl, mergedMachineElements)
                        ? baseName
                        : null;
        String varsAbstract =
                BxmlMachineVariables.implementationMirrorsAbstractVariables(
                                machineEl, mergedMachineElements)
                        ? ""
                        : BxmlMachineVariables.formatAxiomaticBlockWithGhostDummyReads(
                                machineEl, ctx, concreteLinkRoot, abstractVariableNamesForGhost);
        if (!varsAbstract.isBlank()) {
            sb.append(varsAbstract);
            if (!varsAbstract.endsWith("\n")) sb.append("\n");
            sb.append("\n");
        }
        // Variáveis das máquinas fundidas (r, i) — em ordem de cadeia, antes das compreensões.
        Element refinementChainParent = machineEl;
        for (Element mel : mergedMachineElements) {
            BxmlTranslateContext mctx =
                    BxmlTranslateContext.forMachineWithSharedComprehensions(
                            mel, ctx.comprehensions(), gluing, machineEl);
            String varsMerged =
                    BxmlMachineVariables.formatAxiomaticBlock(
                            mel, mctx, baseName, refinementChainParent, gluing);
            refinementChainParent = mel;
            if (!varsMerged.isBlank()) {
                sb.append(varsMerged);
                if (!varsMerged.endsWith("\n")) sb.append("\n");
                sb.append("\n");
            }
        }

        // Constantes e propriedades das máquinas fundidas — depois das variáveis, antes das
        // compreensões, para que constantes como MAX_COPY estejam declaradas quando necessário.
        for (Element mel : mergedMachineElements) {
            BxmlTranslateContext mctx =
                    BxmlTranslateContext.forMachineWithSharedComprehensions(
                            mel, ctx.comprehensions(), gluing, machineEl);
            String constsMerged = BxmlConstantsAndProperties.formatConcreteConstantsBlock(mel, mctx);
            if (!constsMerged.isBlank()) {
                sb.append(constsMerged);
                if (!constsMerged.endsWith("\n")) sb.append("\n");
                sb.append("\n");
            }
            String propsMerged = BxmlConstantsAndProperties.formatPropertiesBlock(mel, mctx);
            if (!propsMerged.isBlank()) {
                sb.append(propsMerged);
                if (!propsMerged.endsWith("\n")) sb.append("\n");
                sb.append("\n");
            }
        }

        // Compreensões: todas as variáveis e constantes já estão declaradas.
        // set_comprehension_k deve estar declarado antes de _i_values (que o referencia).
        if (!ctx.comprehensions().isEmpty()) {
            sb.append(ctx.comprehensions().formatAxiomaticBlock(baseName, ctx));
            sb.append("\n");
        }
        // Values das máquinas fundidas (podem referenciar compreensões).
        for (Element mel : mergedMachineElements) {
            BxmlTranslateContext mctx =
                    BxmlTranslateContext.forMachineWithSharedComprehensions(
                            mel, ctx.comprehensions(), gluing, machineEl);
            String valuesMerged = BxmlConstantsAndProperties.formatValuesBlock(mel, mctx);
            if (!valuesMerged.isBlank()) {
                sb.append(valuesMerged);
                if (!valuesMerged.endsWith("\n")) sb.append("\n");
                sb.append("\n");
            }
        }

        // 2) Todos os predicate (invariantes)
        String invariantPredicates = BxmlInvariantTranslator.formatInvariantPredicates(machineEl, ctx);
        if (!invariantPredicates.isBlank()) {
            sb.append("\n");
            sb.append(invariantPredicates);
            if (!invariantPredicates.endsWith("\n")) sb.append("\n");
            sb.append("\n");
        }
        appendMergedInvariantPredicatesOnly(
                sb, mergedMachineElements, gluing, ctx.comprehensions(), machineEl);

        // 2b) Bloco axiomatic das funções lambda extraídas durante a tradução
        LambdaFunctionRegistry lambdaRegistry = ctx.lambdaRegistry();
        if (lambdaRegistry != null && !lambdaRegistry.isEmpty()) {
            sb.append("\n");
            sb.append(lambdaRegistry.formatAxiomaticBlock());
            sb.append("\n");
        }

        // 3) Funções: inicialização e operações
        if (isAbstraction && init != null) {
            sb.append("\n");
            sb.append(init.toContractText());
            if (!operations.isEmpty()) {
                sb.append("\n");
                for (OperationAcsl op : operations) {
                    sb.append("\n").append(op.toContractSketch());
                }
            }
        }

        String extraLibSymbolScan =
                libScanRemovedBodies.length() == 0 ? null : libScanRemovedBodies.toString();
        String bodyForLibScan = sb.substring(headerLen);
        String dependencyMachinesScan;
        List<String> dependencyLibIncludePaths;
        String libScanRoot =
                libCarrier
                                && libIncludeScanRootMachineName != null
                                && !libIncludeScanRootMachineName.isBlank()
                        ? libIncludeScanRootMachineName.trim()
                        : baseName;
        if (seesGraph != null || importsGraph != null) {
            dependencyMachinesScan =
                    BxmlSetsTranslator.collectTransitiveDependencyMachinesTextForIncludeScan(
                            libScanRoot, seesGraph, importsGraph, bxmlDirectory, outputDir);
            if (libCarrier && !libScanRoot.equals(baseName)) {
                dependencyMachinesScan =
                        joinNonBlank(
                                dependencyMachinesScan,
                                BxmlSetsTranslator.collectMachineTextForIncludeScan(
                                        libScanRoot, bxmlDirectory, outputDir));
            }
            dependencyLibIncludePaths =
                    new ArrayList<>(
                            BxmlSetsTranslator.collectLibIncludePathsFromTransitiveDependencies(
                                    libScanRoot, seesGraph, importsGraph, outputDir));
        } else {
            String seesMachinesScan =
                    BxmlSetsTranslator.collectSeesMachinesTextForIncludeScan(
                            machineEl, bxmlDirectory, outputDir);
            String importsMachinesScan =
                    BxmlSetsTranslator.collectImportedMachinesTextForIncludeScan(
                            machineEl, mergedMachineElements, bxmlDirectory, outputDir);
            dependencyMachinesScan = joinNonBlank(seesMachinesScan, importsMachinesScan);
            dependencyLibIncludePaths = new ArrayList<>();
            dependencyLibIncludePaths.addAll(
                    BxmlSetsTranslator.collectLibIncludePathsFromSeenMachines(
                            machineEl, bxmlDirectory, outputDir));
            dependencyLibIncludePaths.addAll(
                    BxmlSetsTranslator.collectLibIncludePathsFromImportedMachines(
                            machineEl, mergedMachineElements, bxmlDirectory, outputDir));
        }
        String combinedExtraScan =
                joinNonBlank(extraLibSymbolScan, dependencyMachinesScan);
        String libIncludes =
                AcslLibIncludes.formatIncludeBlock(
                        bodyForLibScan, combinedExtraScan, dependencyLibIncludePaths);
        String machineDependencyIncludes;
        if (seesGraph != null || importsGraph != null) {
            // Includes de outras máquinas ficam a cargo do -acsl-import multi-ficheiro (B2ACSLPipeline).
            machineDependencyIncludes = "";
        } else {
            String seesIncludes =
                    BxmlSetsTranslator.formatSeesIncludeBlock(machineEl, bxmlDirectory);
            String importsIncludes =
                    BxmlSetsTranslator.formatImportsIncludeBlock(
                            machineEl, mergedMachineElements, bxmlDirectory);
            StringBuilder legacy = new StringBuilder();
            appendAcslMachineIncludes(legacy, seesIncludes);
            appendAcslMachineIncludes(legacy, importsIncludes);
            machineDependencyIncludes = legacy.toString();
        }
        StringBuilder preambleIncludes = new StringBuilder();
        if (!omitLibIncludesFromPreamble && !libIncludes.isEmpty()) {
            preambleIncludes.append(libIncludes);
        }
        appendAcslMachineIncludes(preambleIncludes, machineDependencyIncludes);
        if (!preambleIncludes.isEmpty()) {
            sb.insert(headerLen, preambleIncludes);
        }
        String fullAcsl = sb.toString();
        if (omitLibIncludesFromPreamble) {
            fullAcsl = AcslLibIncludes.removeLibIncludesFromPreamble(fullAcsl);
        }
        Files.writeString(acslFile, fullAcsl);
        if (!omitLibIncludesFromPreamble) {
            AcslLibIncludes.copyReferencedLibraryFiles(
                    fullAcsl, acslFile, combinedExtraScan, dependencyLibIncludePaths);
        }
        return Optional.of(acslFile);
    }

    /**
     * Ficheiro auxiliar só com {@code include "import/…"} para verificação Frama-C isolada de uma
     * máquina vista em {@code SEES} (o {@code .acsl} principal não traz esses includes).
     */
    public static Optional<Path> writeLibIncludesSidecarForSeenMachine(
            String machineName, Path acslDirectory) throws IOException {
        if (machineName == null || machineName.isBlank() || acslDirectory == null) {
            return Optional.empty();
        }
        Path mainAcsl = acslDirectory.resolve(machineName + ".acsl");
        if (!Files.isRegularFile(mainAcsl)) {
            return Optional.empty();
        }
        String body = AcslLibIncludes.acslBodyAfterPreambleIncludes(Files.readString(mainAcsl));
        String includes = AcslLibIncludes.formatIncludeBlock(body, null);
        if (includes.isBlank()) {
            return Optional.empty();
        }
        Path sidecar = acslDirectory.resolve("_" + machineName + "_lib_includes.acsl");
        String sidecarText =
                "/* B2ACSL: includes da biblioteca para verificação isolada de "
                        + machineName
                        + " */\n\n"
                        + includes;
        Files.writeString(sidecar, sidecarText);
        AcslLibIncludes.copyReferencedLibraryFiles(sidecarText + "\n" + body, sidecar, null);
        return Optional.of(sidecar);
    }

    /**
     * Compatível com chamadas sem blocos fundidos / sem gluing.
     */
    public static Optional<Path> generateAcsl(Machine machine, Path bxmlPath, Path outputDir) throws Exception {
        return generateAcsl(machine, bxmlPath, outputDir, List.of(), Map.of());
    }

    public static Optional<Path> generateAcsl(
            Machine machine, Path bxmlPath, Path outputDir, List<Path> mergeBxmlPathsFromDescendants)
            throws Exception {
        return generateAcsl(machine, bxmlPath, outputDir, mergeBxmlPathsFromDescendants, Map.of());
    }

    /** Apenas {@code predicate} de invariantes de refinamentos/implementações. */
    private static void appendMergedInvariantPredicatesOnly(
            StringBuilder sb,
            List<Element> mergedMachineRoots,
            Map<String, String> gluing,
            BxmlComprehensionRegistry sharedComprehensions,
            Element rootAbstractMachineEl) {
        if (mergedMachineRoots == null || mergedMachineRoots.isEmpty()) {
            return;
        }
        for (Element mel : mergedMachineRoots) {
            BxmlTranslateContext ctx =
                    BxmlTranslateContext.forMachineWithSharedComprehensions(
                            mel, sharedComprehensions, gluing, rootAbstractMachineEl);
            String inv = BxmlInvariantTranslator.formatInvariantPredicates(mel, ctx);
            if (inv.isBlank()) continue;
            sb.append("\n");
            sb.append(inv);
            if (!inv.endsWith("\n")) sb.append("\n");
        }
    }

    /**
     * {@code <Abstraction>NomeDaMaquinaAbstrata</Abstraction>} indica refinamento ou implementação;
     * não se gera {@code .acsl} próprio para esse componente.
     */
    private static boolean referencesAbstractMachineViaAbstractionTag(Element machineEl) {
        return getAbstractionReferenceName(machineEl).isPresent();
    }

    private static boolean isAbstractMachine(Element machineEl) {
        String t = machineEl.getAttribute("type");
        return t != null && "abstraction".equalsIgnoreCase(t.trim());
    }

    /**
     * Invariante(s) da abstrata seguido(s) dos invariantes de cada refinamento/implementação em
     * {@code mergedMachineRoots} (mesma ordem que em {@link #appendMergedInvariantPredicatesOnly}), para
     * repetir em {@code requires}/{@code ensures} das operações e em {@code ensures} da inicialização.
     */
    private static List<String> listAllInvariantPredicateNames(
            Element machineEl,
            BxmlTranslateContext ctx,
            List<Element> mergedMachineRoots,
            Map<String, String> gluing) {
        List<String> out = new ArrayList<>(BxmlInvariantTranslator.listInvariantPredicateNames(machineEl, ctx));
        if (mergedMachineRoots == null) {
            return out;
        }
        for (Element mel : mergedMachineRoots) {
            BxmlTranslateContext mctx =
                    BxmlTranslateContext.forMachineWithSharedComprehensions(
                            mel, ctx.comprehensions(), gluing, machineEl);
            out.addAll(BxmlInvariantTranslator.listInvariantPredicateNames(mel, mctx));
        }
        return out;
    }

    private static InitialisationAcsl withInvariantEnsures(
            InitialisationAcsl init, List<String> invariantPredicateNames) {
        if (invariantPredicateNames.isEmpty()) return init;
        List<String> ensures = new ArrayList<>(init.ensures());
        for (String inv : invariantPredicateNames) {
            ensures.add(inv);
        }
        return new InitialisationAcsl(
                init.functionName(),
                ensures,
                init.assignsTargets(),
                init.includeGhostBehaviorAssert(),
                init.dummyGhostEnsureVarNames());
    }

    private static void appendAcslMachineIncludes(StringBuilder preamble, String includeBlock) {
        if (includeBlock == null || includeBlock.isEmpty()) {
            return;
        }
        if (!preamble.isEmpty() && !preamble.toString().endsWith("\n\n")) {
            if (!preamble.toString().endsWith("\n")) {
                preamble.append("\n");
            }
            preamble.append("\n");
        }
        preamble.append(includeBlock);
    }

    private static String joinNonBlank(String a, String b) {
        boolean aBlank = a == null || a.isBlank();
        boolean bBlank = b == null || b.isBlank();
        if (aBlank && bBlank) {
            return "";
        }
        if (aBlank) {
            return b;
        }
        if (bBlank) {
            return a;
        }
        return a + "\n" + b;
    }

    private static Document parseXml(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        // Segurança básica
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {}
        DocumentBuilder builder = factory.newDocumentBuilder();
        try (InputStream in = Files.newInputStream(path)) {
            Document doc = builder.parse(in);
            doc.getDocumentElement().normalize();
            return doc;
        }
    }
}
