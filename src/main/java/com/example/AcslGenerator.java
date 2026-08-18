package com.example;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import com.example.bxml.InitialisationAcsl;
import com.example.bxml.BxmlConnectionAcsl;
import com.example.bxml.GhostContractPredicates;
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
import com.example.bxml.BxmlTypeRegistry;
import com.example.bxml.LambdaFunctionRegistry;
import com.example.bxml.MachineAxiomaticBlockFormatter;
import com.example.bxml.MachineIncludeScanCollector;
import com.example.bxml.SigmaFunctionRegistry;
import com.example.bxml.UnionInterFunctionRegistry;
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
 * bloco {@code include "Dep.acsl"} com o fecho transitivo em ordem topológica; máquinas só
 * dependentes não repetem includes.
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
        return generateAcsl(
                machine,
                bxmlPath,
                outputDir,
                mergeBxmlPathsFromDescendants,
                gluingSubstitutionsFromInvariants,
                seenOnlyMachineNames,
                seesGraph,
                importsGraph,
                libIncludeCarrierMachineName,
                libIncludeScanRootMachineName,
                null);
    }

    /**
     * Como {@link #generateAcsl(Machine, Path, Path, List, Map, Set, BxmlSeesGraph, BxmlImportsGraph, String, String)},
     * mas com texto extra para varredura de símbolos da lib (ex. conteúdo stripped do
     * {@code ghost_operations.ci}, gerado antes dos {@code .acsl}).
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
            String libIncludeScanRootMachineName,
            String extraLibScanText)
            throws Exception {
        Document doc = parseXml(bxmlPath);
        Element machineEl = doc.getDocumentElement();
        if (referencesAbstractMachineViaAbstractionTag(machineEl)) {
            return Optional.empty();
        }
        AcslGenerationState state =
                prepareGenerationState(
                        machine,
                        bxmlPath,
                        outputDir,
                        mergeBxmlPathsFromDescendants,
                        gluingSubstitutionsFromInvariants,
                        seenOnlyMachineNames,
                        seesGraph,
                        importsGraph,
                        libIncludeCarrierMachineName,
                        machineEl);
        appendAcslBody(state);
        Path acslFile =
                finalizeAndWriteAcslFile(
                        state, seesGraph, importsGraph, libIncludeScanRootMachineName, extraLibScanText);
        return Optional.of(acslFile);
    }

    /** Estado mutável partilhado pelas fases de {@link #generateAcsl}, ver javadoc do método. */
    private static final class AcslGenerationState {
        Element machineEl;
        Path outputDir;
        String baseName;
        Path acslFile;
        boolean libCarrier;
        boolean omitLibIncludesFromPreamble;
        Map<String, String> gluing;
        boolean isAbstraction;
        List<Element> mergedMachineElements;
        Set<String> collapsedVariableNames;
        Set<String> abstractVariableNamesForGhost;
        Path bxmlDirectory;
        List<String> transitiveImportedMachineNames;
        Set<String> invariantSourceMachineNames;
        Map<String, String> refinementChainVariableLogicTypes;
        BxmlTranslateContext ctx;
        Map<String, String> varRhsOverrides;
        boolean useGhostAbstraction;
        InitialisationAcsl init;
        StringBuilder libScanRemovedBodies;
        List<OperationAcsl> operations;
        StringBuilder sb;
        int headerLen;
        String propertiesBlock;
        Map<Element, Map<String, String>> mergedVariableRenames;
        List<BDefinitionsTranslator.ResolvedDefinition> bDefinitions = List.of();
    }

    /**
     * Fase 1 de {@link #generateAcsl}: contexto de tradução, invariantes, {@code Initialisation} e
     * {@code Operations} — tudo o que as fases seguintes ({@link #appendAcslBody} e
     * {@link #finalizeAndWriteAcslFile}) precisam para montar e escrever o {@code .acsl}.
     */
    private static AcslGenerationState prepareGenerationState(
            Machine machine,
            Path bxmlPath,
            Path outputDir,
            List<Path> mergeBxmlPathsFromDescendants,
            Map<String, String> gluingSubstitutionsFromInvariants,
            Set<String> seenOnlyMachineNames,
            BxmlSeesGraph seesGraph,
            BxmlImportsGraph importsGraph,
            String libIncludeCarrierMachineName,
            Element machineEl)
            throws Exception {
        AcslGenerationState state = new AcslGenerationState();
        state.machineEl = machineEl;
        state.outputDir = outputDir;
        initializeBasicMachineState(
                state, machine, outputDir, mergeBxmlPathsFromDescendants,
                gluingSubstitutionsFromInvariants, seenOnlyMachineNames, libIncludeCarrierMachineName);
        buildMachineTranslateContext(state, bxmlPath, seesGraph, importsGraph);
        buildInitialisationAndOperationsForState(state);
        return state;
    }

    private static void initializeBasicMachineState(
            AcslGenerationState state,
            Machine machine,
            Path outputDir,
            List<Path> mergeBxmlPathsFromDescendants,
            Map<String, String> gluingSubstitutionsFromInvariants,
            Set<String> seenOnlyMachineNames,
            String libIncludeCarrierMachineName)
            throws Exception {
        Element machineEl = state.machineEl;

        Files.createDirectories(outputDir);
        String baseName = machine.getMachineName();
        state.baseName = baseName;
        Path acslFile = outputDir.resolve(baseName + ".acsl");
        state.acslFile = acslFile;
        // Âmbito desta chamada: tipos de codomínio-tupla (ver TupleCodomainTypeRegistry) descobertos
        // ao inferir os tipos das variáveis desta máquina (mais abaixo) são escritos num .acsl
        // próprio dela e incluídos localmente, perto do fim desta função — limpa aqui para não
        // herdar entradas de uma máquina anterior processada no mesmo processo.
        com.example.bxml.TupleCodomainTypeRegistry.clear();
        boolean libCarrier =
                libIncludeCarrierMachineName != null
                        && !libIncludeCarrierMachineName.isBlank()
                        && baseName.equals(libIncludeCarrierMachineName.trim());
        state.libCarrier = libCarrier;
        boolean omitLibIncludesFromPreamble =
                libIncludeCarrierMachineName != null && !libIncludeCarrierMachineName.isBlank()
                        ? !libCarrier
                        : seenOnlyMachineNames != null && seenOnlyMachineNames.contains(baseName);
        state.omitLibIncludesFromPreamble = omitLibIncludesFromPreamble;

        List<Path> mergePaths =
                mergeBxmlPathsFromDescendants == null ? List.of() : mergeBxmlPathsFromDescendants;
        Map<String, String> gluing =
                gluingSubstitutionsFromInvariants == null ? Map.of() : gluingSubstitutionsFromInvariants;
        state.gluing = gluing;

        boolean isAbstraction = isAbstractMachine(machineEl);
        state.isAbstraction = isAbstraction;

        List<Element> mergedMachineElements = new ArrayList<>();
        for (Path p : mergePaths) {
            mergedMachineElements.add(parseMachineElement(p));
        }
        state.mergedMachineElements = mergedMachineElements;
    }

    private static void buildMachineTranslateContext(
            AcslGenerationState state, Path bxmlPath, BxmlSeesGraph seesGraph, BxmlImportsGraph importsGraph) {
        Element machineEl = state.machineEl;
        List<Element> mergedMachineElements = state.mergedMachineElements;
        Map<String, String> gluing = state.gluing;

        // Variáveis com Concrete_Variables de MESMO NOME na implementação, tipada array/função
        // total nessa camada (ex. "limit" em Customer E Customer_i): não criar duas variáveis ACSL
        // (ghost abstrata + array-backed concreta) para o que o B já trata como uma só variável
        // refinada — usar SÓ a definição array-backed da implementação, com o nome original (sem
        // sufixo _i/_r), preservando as propriedades/invariantes da abstração aplicadas a ela. Ver
        // BxmlMachineVariables#collapsedIntoImplementationVariableNames.
        Set<String> collapsedVariableNames =
                BxmlMachineVariables.collapsedIntoImplementationVariableNames(
                        machineEl, mergedMachineElements);
        state.collapsedVariableNames = collapsedVariableNames;

        Set<String> abstractVariableNamesForGhost =
                new LinkedHashSet<>(GhostOperationsCiGenerator.listAbstractVariableNames(machineEl));
        abstractVariableNamesForGhost.removeAll(collapsedVariableNames);
        state.abstractVariableNamesForGhost = abstractVariableNamesForGhost;

        List<Element> comprehensionChain = new ArrayList<>();
        comprehensionChain.add(machineEl);
        comprehensionChain.addAll(mergedMachineElements);
        BxmlComprehensionRegistry sharedComprehensions =
                BxmlComprehensionRegistry.fromMachineChain(comprehensionChain, gluing);
        sharedComprehensions.assignDedupIndices();

        Path bxmlDirectory = bxmlPath.getParent();
        state.bxmlDirectory = bxmlDirectory;
        // IMPORTS é transitivo em B: se esta máquina importa X e X importa Y, esta máquina também
        // depende do invariante e do assigns de Y (X's próprias operações já a chamam
        // internamente). listImportedMachineNamesFromChain só olha um nível — usado aqui só para
        // requires/assigns "herdados", não para varRhsOverrides (ligação direta por invariante,
        // que é sempre com um import direto).
        List<String> transitiveImportedMachineNames =
                com.example.bxml.BxmlSetsTranslator.listImportedMachineNamesTransitive(
                        machineEl, mergedMachineElements, importsGraph);
        state.transitiveImportedMachineNames = transitiveImportedMachineNames;
        // SEES também precisa do invariante da máquina vista como requires: uma operação pode
        // chamar a operação de uma máquina só vista (ex. Customer__buy chama Price__pricequery,
        // que requires Price_invariant/Price_i_invariant) — sem isto a pré-condição da chamada
        // fica sem hipótese e o WP não consegue prová-la. SEES é transitivo tal como IMPORTS
        // (Customer vê Price, Price vê Goods): ver listSeenMachineNamesTransitive. Ao contrário de
        // IMPORTS, SEES é só-leitura — não precisa propagar assigns, só requires.
        List<String> transitiveSeenMachineNames =
                com.example.bxml.BxmlSetsTranslator.listSeenMachineNamesTransitive(
                        machineEl, mergedMachineElements, seesGraph);
        LinkedHashSet<String> invariantSourceMachineNames =
                new LinkedHashSet<>(transitiveImportedMachineNames);
        invariantSourceMachineNames.addAll(transitiveSeenMachineNames);
        state.invariantSourceMachineNames = invariantSourceMachineNames;
        // Tipos de TODAS as camadas da cadeia de refinamento (abstrata, cada _r, a _i) — não só
        // machineEl + abstrata raiz (o que forMachineWithSharedComprehensions já cobre). Sem isto,
        // o invariante de uma camada que referencia a variável de OUTRA camada intermédia (ex.:
        // _i_invariant usando ss_r, declarada só em _r) cai no fallback por typref cru em
        // isListValued, que não distingue \list de Relation (ambas POW(INTEGER*T) em B) — gerando
        // sequence_ran/relation_ran trocados.
        Map<String, String> refinementChainVariableLogicTypes =
                refinementChainVariableLogicTypes(machineEl, mergedMachineElements);
        state.refinementChainVariableLogicTypes = refinementChainVariableLogicTypes;
        Set<String> enumeratedSetNamesForCtx = BxmlSetsTranslator.buildEnumeratedSetNames(machineEl);
        BxmlTranslateContext ctx =
                BxmlTranslateContext.forMachineWithSharedComprehensions(
                        machineEl, sharedComprehensions, gluing)
                        .withLambdaRegistry(new LambdaFunctionRegistry())
                        .withSigmaRegistry(new SigmaFunctionRegistry())
                        .withUnionInterRegistry(new UnionInterFunctionRegistry())
                        .withEnumRenames(
                                BxmlSetsTranslator.buildEnumRenamesWithSees(
                                        machineEl, mergedMachineElements, bxmlDirectory))
                        .withEnumeratedSetRenames(
                                BxmlSetsTranslator.buildEnumeratedSetRenamesWithSees(
                                        machineEl, mergedMachineElements, bxmlDirectory))
                        .withEnumeratedSetNames(enumeratedSetNamesForCtx)
                        .withCrossMachineVariableNames(
                                BxmlMachineVariables.transitiveCrossMachineVariableNames(
                                        invariantSourceMachineNames, bxmlDirectory))
                        .withCrossMachineVariableLogicTypes(
                                BxmlMachineVariables.transitiveCrossMachineVariableLogicTypes(
                                        invariantSourceMachineNames, bxmlDirectory))
                        .withAdditionalVariableLogicTypes(refinementChainVariableLogicTypes);
        state.ctx = ctx;
        // deferredSetTypedParameterNames NÃO é preenchido aqui: é por-OPERAÇÃO (nomes de parâmetro
        // colidem entre operações diferentes da mesma máquina com papéis diferentes — ver javadoc
        // de BxmlMachineVariables.deferredSetTypedOperationParameterNames), preenchido dentro do
        // laço de BxmlOperationsTranslator#translateOperations com a Precondition de cada operação.
    }

    private static void buildInitialisationAndOperationsForState(AcslGenerationState state) {
        Element machineEl = state.machineEl;
        String baseName = state.baseName;
        Path bxmlDirectory = state.bxmlDirectory;
        List<Element> mergedMachineElements = state.mergedMachineElements;
        Map<String, String> gluing = state.gluing;
        BxmlTranslateContext ctx = state.ctx;
        boolean isAbstraction = state.isAbstraction;
        Set<String> collapsedVariableNames = state.collapsedVariableNames;
        Set<String> abstractVariableNamesForGhost = state.abstractVariableNamesForGhost;
        List<String> transitiveImportedMachineNames = state.transitiveImportedMachineNames;
        Set<String> invariantSourceMachineNames = state.invariantSourceMachineNames;

        List<String> allInvariantPredicateNames =
                listAllInvariantPredicateNames(machineEl, ctx, mergedMachineElements, gluing);
        List<String> importedMachineNames =
                com.example.bxml.BxmlSetsTranslator.listImportedMachineNamesFromChain(
                        machineEl, mergedMachineElements);
        List<String> importedInvariantPredicateNames =
                BxmlInvariantTranslator.listImportedMachineInvariantPredicateNames(
                        new ArrayList<>(invariantSourceMachineNames), bxmlDirectory);
        Map<String, String> varRhsOverrides =
                BxmlMachineVariables.buildAbstractVarRhsOverrides(
                        machineEl, mergedMachineElements, importedMachineNames, bxmlDirectory);
        state.varRhsOverrides = varRhsOverrides;
        List<String> implementationAssignTargets = new java.util.ArrayList<>(
                com.example.bxml.ConcreteAssignTargetResolver.listInitialisationAssignTargets(
                        baseName, machineEl, mergedMachineElements, ctx, varRhsOverrides,
                        bxmlDirectory));
        implementationAssignTargets.addAll(
                BxmlMachineVariables.listImportedMachineConcreteAssigns(
                        transitiveImportedMachineNames, bxmlDirectory));
        List<String> seenMachineNames =
                com.example.bxml.BxmlSetsTranslator.listSeenMachineNamesFromChain(
                        machineEl, mergedMachineElements);
        Map<String, List<String>> importedOpAssigns =
                BxmlMachineVariables.buildImportedOperationAssignsMap(
                        transitiveImportedMachineNames, seenMachineNames, bxmlDirectory);
        boolean useGhostAbstraction =
                BxmlMachineVariables.needsGhostAbstraction(machineEl, mergedMachineElements);
        state.useGhostAbstraction = useGhostAbstraction;
        Set<String> operationStateVariableNames =
                new LinkedHashSet<>(GhostOperationsCiGenerator.listAbstractVariableNames(machineEl));
        if (!useGhostAbstraction) {
            operationStateVariableNames.addAll(
                    BxmlMachineVariables.declaredVariableNames(machineEl));
        }
        operationStateVariableNames.removeAll(collapsedVariableNames);
        Map<String, Long> knownIntegerConstants = collectAllIntegerValuations(bxmlDirectory);
        InitialisationAcsl initBare =
                BxmlInitialisationTranslator.translate(
                        machineEl, implementationAssignTargets, ctx, knownIntegerConstants,
                        mergedMachineElements, bxmlDirectory);
        boolean initGhostAssert =
                useGhostAbstraction
                        && GhostContractPredicates.initialisationAssignsAbstract(
                                machineEl, abstractVariableNamesForGhost);
        List<String> dummyGhostVarsForInit =
                initGhostAssert
                        ? GhostOperationsCiGenerator.listAbstractVariableNames(machineEl).stream()
                                .filter(v -> !collapsedVariableNames.contains(v))
                                .toList()
                        : List.of();
        boolean machineHasNoImports = machineHasNoImports(machineEl, mergedMachineElements);
        // Quando a INITIALISATION usa ghost assert (para as variáveis QUE têm camada ghost), o
        // ensures "real" completo (initBare.ensures()) é descartado — mas isso apagaria também a
        // especificação de variáveis SEM camada ghost (colapsadas, ex. limit): ficam sem ensures
        // algum em lado nenhum. Filtra a mesma tradução para incluir só as cláusulas dessas
        // variáveis — ver BxmlInitialisationTranslator#ensuresForNonGhostVariables. Inclui também
        // as variáveis ghost diretamente atribuídas na inicialização abstrata (ex.: RobustFifo's
        // "queue := []"): como as operações (BxmlOperationsTranslator#translateOperations mantém o
        // ensures funcional junto do "ensures dummy_ghost_<v>"), essas variáveis já têm uma função
        // lógica de topo própria ("logic … v reads dummy_ghost_v;" — ver
        // MachineAxiomaticBlockFormatter), então "ensures dummy_ghost_v;" sozinho não basta: fica
        // sem nenhuma promessa sobre o valor de v.
        Set<String> initEnsuresKeepNames = new LinkedHashSet<>(collapsedVariableNames);
        initEnsuresKeepNames.addAll(dummyGhostVarsForInit);
        List<String> initEnsuresForContract =
                initGhostAssert
                        ? BxmlInitialisationTranslator.ensuresForNonGhostVariables(
                                machineEl, ctx, initEnsuresKeepNames)
                        : new ArrayList<>(initBare.ensures());
        InitialisationAcsl initMarked =
                new InitialisationAcsl(
                        initBare.functionName(),
                        initEnsuresForContract,
                        initBare.assignsTargets(),
                        initGhostAssert,
                        dummyGhostVarsForInit,
                        initBare.loopSpecs(),
                        initBare.explicitLoops(),
                        machineHasNoImports);
        InitialisationAcsl init =
                isAbstraction
                        ? withInvariantEnsures(initMarked, allInvariantPredicateNames)
                        : null;
        state.init = init;
        StringBuilder libScanRemovedBodies = new StringBuilder();
        state.libScanRemovedBodies = libScanRemovedBodies;
        if (initGhostAssert) {
            for (String e : initBare.ensures()) {
                if (e != null && !e.isBlank()) {
                    libScanRemovedBodies.append(e).append('\n');
                }
            }
        }
        List<OperationAcsl> operations =
                isAbstraction
                        ? new ArrayList<>(
                                BxmlOperationsTranslator.translateOperations(
                                        machineEl,
                                        ctx,
                                        allInvariantPredicateNames,
                                        operationStateVariableNames,
                                        libScanRemovedBodies,
                                        baseName,
                                        mergedMachineElements,
                                        gluing,
                                        useGhostAbstraction,
                                        importedOpAssigns,
                                        importedInvariantPredicateNames,
                                        varRhsOverrides,
                                        bxmlDirectory,
                                        new ArrayList<>(invariantSourceMachineNames),
                                        collapsedVariableNames))
                        : new ArrayList<>();
        // LOCAL_OPERATIONS (ex. cv_struct_i's lclear): funções auxiliares privadas da
        // implementação, sem contraparte na máquina abstrata — translateOperations acima nunca as
        // visita (itera só <Operations> de machineEl, a máquina ABSTRATA). Ver
        // BxmlOperationsTranslator#translateLocalOperations.
        if (isAbstraction) {
            for (Element mel : mergedMachineElements) {
                if ("implementation".equalsIgnoreCase(mel.getAttribute("type"))) {
                    operations.addAll(
                            BxmlOperationsTranslator.translateLocalOperations(
                                    mel, baseName, ctx, allInvariantPredicateNames));
                }
            }
        }
        state.operations = operations;
    }

    /**
     * Fase 2 de {@link #generateAcsl}: monta o texto ACSL completo em {@code state.sb} (conjuntos,
     * constantes, variáveis, compreensões, invariantes, lambdas/SIGMA/PI/UNION/INTER, funções).
     */
    private static void appendAcslBody(AcslGenerationState state) throws Exception {
        appendPreambleAndConstantsBlocks(state);
        appendMachineVariableBlocks(state);
        appendMergedConstantsPropertiesAndValuesBlocks(state);
        appendInvariantsAndQuantifierBlocks(state);
        appendFunctionsSection(state);
    }

    private static void appendPreambleAndConstantsBlocks(AcslGenerationState state) throws Exception {
        Element machineEl = state.machineEl;
        Path outputDir = state.outputDir;
        String baseName = state.baseName;
        Path bxmlDirectory = state.bxmlDirectory;
        List<Element> mergedMachineElements = state.mergedMachineElements;
        Map<String, String> gluing = state.gluing;
        BxmlTranslateContext ctx = state.ctx;
        boolean useGhostAbstraction = state.useGhostAbstraction;
        StringBuilder libScanRemovedBodies = state.libScanRemovedBodies;

        StringBuilder sb = new StringBuilder();
        sb.append("/* ACSL gerado a partir de ").append(baseName).append(".bxml (BXML 1.0) */\n");
        sb.append(
                "/* Biblioteca B2ACSLLib: includes gerados automaticamente (AcslLibIncludes); "
                        + "opções: b2acsl.acslLibIncludeBase, b2acsl.acslLibIncludeMiddle. */\n\n");
        int headerLen = sb.length();
        state.sb = sb;
        state.headerLen = headerLen;

        // 0) Conjuntos deferred (Sets) — posicionados logo após os includes
        String setsBlock = BxmlSetsTranslator.formatSetsBlock(machineEl, mergedMachineElements, bxmlDirectory);
        if (!setsBlock.isBlank()) {
            sb.append(setsBlock);
            sb.append("\n");
        }

        // 0b) DEFINITIONS B (ver BDefinitionsTranslator — só existe como texto em expand_src/, o
        // BXML já vem com as macros totalmente expandidas). guardado em state.bDefinitions para
        // finalizeAndWriteAcslFile reescrever os pontos de uso já gerados (ensures/invariant/…)
        // para referenciar os nomes simbólicos em vez dos valores literais.
        BDefinitionsTranslator.parse(bxmlDirectory, baseName)
                .ifPresent(
                        t -> {
                            appendBlockWithSpacing(sb, t.axiomaticBlock());
                            state.bDefinitions = t.defs();
                        });

        // 1) Constantes e propriedades (só máquina abstrata raiz deste ficheiro)
        // Lambda-defined constants used in ANY_Sub ghost ops are declared in ghost_operations.ci's
        // separate axiomatic (not dummy_ghost); exclude them here to avoid "already declared" in Frama-C.
        Set<String> ciDeclaredAbstractConsts =
                GhostOperationsCiGenerator.machineHasAnySubOperations(machineEl)
                        ? BxmlConstantsAndProperties.collectLambdaDefsFromProperties(machineEl).keySet()
                        : Set.of();
        String abstractConstants = BxmlConstantsAndProperties.formatAbstractConstantsBlock(
                machineEl, ctx, ciDeclaredAbstractConsts);
        appendBlockWithSpacing(sb, abstractConstants);
        String concreteConstants = BxmlConstantsAndProperties.formatConcreteConstantsBlock(machineEl, ctx);
        appendBlockWithSpacing(sb, concreteConstants);
        // Traduz properties antecipadamente para popular o lambdaRegistry (lambdas de operações
        // já registados em translateOperations acima). O bloco properties é emitido mais tarde,
        // depois das variáveis e dos lambda_functions, pois lambda_functions pode referenciar
        // variáveis de estado (ex.: copyOf) que só ficam declaradas após as variáveis.
        String propertiesBlock = BxmlConstantsAndProperties.formatPropertiesBlock(machineEl, ctx);
        state.propertiesBlock = propertiesBlock;
        String valuesRoot = BxmlConstantsAndProperties.formatValuesBlock(machineEl, ctx);
        appendBlockWithSpacing(sb, valuesRoot);

        Optional<Path> connectionAcsl =
                BxmlConnectionAcsl.writeConnectionAcsl(
                        outputDir, baseName, machineEl, mergedMachineElements, gluing);
        connectionAcsl.ifPresent(
                p -> {
                    sb.append("include \"").append(p.getFileName().toString()).append("\";\n\n");
                    // connection.acsl é escrito directamente em disco (só o include entra em sb) —
                    // sem isto, símbolos da lib usados só lá dentro (ex.: sequence_ran nos axiomas
                    // de existência de testemunha) nunca aparecem no texto varrido para decidir os
                    // includes do ficheiro raiz, e o Frama-C rejeita com "unbound logic function".
                    try {
                        libScanRemovedBodies.append(Files.readString(p)).append('\n');
                    } catch (java.io.IOException ignored) {
                        // conteúdo só de conveniência para a varredura; falha aqui não é fatal
                    }
                });

        String ghostPatternsBlock =
                useGhostAbstraction
                        ? GhostContractPredicates.formatGhostPatternsAxiomaticBlock(
                                machineEl, baseName, ctx.variableLogicTypes())
                        : "";
        appendBlockWithSpacing(sb, ghostPatternsBlock);
    }

    /**
     * Anexa {@code block} a {@code sb} seguido de linha em branco, se não for vazio/blank — idioma
     * repetido ~10x ao longo da montagem do corpo ACSL (constantes, propriedades, variáveis, values).
     */
    private static void appendBlockWithSpacing(StringBuilder sb, String block) {
        if (block == null || block.isBlank()) {
            return;
        }
        sb.append(block);
        if (!block.endsWith("\n")) sb.append("\n");
        sb.append("\n");
    }

    /**
     * Contexto de tradução por-máquina-fundida, reconstruído a partir de {@code ctx} (comprehensions,
     * lambda/sigma/unionInter registries partilhados) para cada elemento da cadeia de refinamento —
     * mesmo padrão nas 3 chamadas em {@link #appendMachineVariableBlocks} e
     * {@link #appendMergedConstantsPropertiesAndValuesBlocks}.
     */
    private static BxmlTranslateContext buildMergedElementContext(
            Element mel,
            BxmlTranslateContext ctx,
            Map<String, String> gluing,
            Element machineEl,
            Map<String, String> refinementChainVariableLogicTypes) {
        return BxmlTranslateContext.forMachineWithSharedComprehensions(
                        mel, ctx.comprehensions(), gluing, machineEl)
                .withEnumeratedSetRenames(ctx.enumeratedSetRenames())
                .withLambdaRegistry(ctx.lambdaRegistry())
                .withSigmaRegistry(ctx.sigmaRegistry())
                .withUnionInterRegistry(ctx.unionInterRegistry())
                .withCrossMachineVariableNames(ctx.crossMachineVariableNames())
                .withCrossMachineVariableLogicTypes(ctx.crossMachineVariableLogicTypes())
                .withAdditionalVariableLogicTypes(refinementChainVariableLogicTypes);
    }

    private static void appendMachineVariableBlocks(AcslGenerationState state) {
        Element machineEl = state.machineEl;
        String baseName = state.baseName;
        Path bxmlDirectory = state.bxmlDirectory;
        List<Element> mergedMachineElements = state.mergedMachineElements;
        BxmlTranslateContext ctx = state.ctx;
        Set<String> collapsedVariableNames = state.collapsedVariableNames;
        Set<String> abstractVariableNamesForGhost = state.abstractVariableNamesForGhost;
        Map<String, String> varRhsOverrides = state.varRhsOverrides;
        StringBuilder sb = state.sb;

        // 1b) Variáveis em sequência: abstrata → refinamentos → implementação.
        // Cada bloco _r_variables / _i_variables pode referenciar variáveis dos blocos anteriores
        // (e.g. numbers_s usa numbers), por isso todos os blocos de variáveis devem vir ANTES das
        // compreensões e das constantes/propriedades das máquinas fundidas.
        String concreteLinkRoot =
                BxmlMachineVariables.anyImplementationUsesAbstractVariablesOnly(
                                machineEl, mergedMachineElements)
                        || BxmlMachineVariables.abstractMachineIsSelfContainedArrayBacked(
                                machineEl, mergedMachineElements)
                        ? baseName
                        : null;
        String varsAbstract =
                BxmlMachineVariables.implementationMirrorsAbstractVariables(
                                machineEl, mergedMachineElements)
                        ? ""
                        : MachineAxiomaticBlockFormatter.formatAxiomaticBlockWithGhostDummyReads(
                                machineEl, ctx, concreteLinkRoot, abstractVariableNamesForGhost,
                                varRhsOverrides, bxmlDirectory, collapsedVariableNames);
        appendBlockWithSpacing(sb, varsAbstract);
        // Variáveis das máquinas fundidas (r, i) — em ordem de cadeia, antes das compreensões.
        //
        // Nomes de variáveis já declarados (começa pela abstrata): uma variável de refinamento ou
        // implementação B pode reusar o MESMO nome da variável abstrata que refina (namespaces B
        // são por máquina, e.g. "limit" tanto em Customer quanto em Customer_i) — válido em B, mas
        // ao fundir tudo num único ACSL plano isso declara "limit" duas vezes com o mesmo perfil e
        // o Frama-C rejeita ("already declared with the same profile"). Deteta-se a colisão e
        // renomeia-se (sufixo _r/_i) só a declaração dessa camada — a mesma renomeação é depois
        // aplicada ao invariante dessa camada em appendMergedInvariantPredicatesOnly.
        // Só semeia com os nomes abstratos se o bloco abstrato foi mesmo emitido: quando a
        // implementação espelha as variáveis abstratas (varsAbstract == "", acima), esses nomes
        // NUNCA aparecem no texto gerado — quem declara "is_outdoor_door_openable" bare é o
        // próprio bloco da máquina fundida (mirroring). Semear aqui mesmo assim faria o loop
        // abaixo "detetar" uma colisão inexistente e renomear essa declaração para _i/_r sem
        // necessidade, deixando o nome bare usado nos requires/ensures sem declaração nenhuma.
        Set<String> declaredVariableNames =
                varsAbstract.isBlank()
                        ? new LinkedHashSet<>()
                        : new LinkedHashSet<>(
                                BxmlMachineVariables.inferVariableLogicTypes(machineEl, ctx).keySet());
        // Variáveis colapsadas na implementação (ver acima) nunca são "declaradas" pela camada
        // abstrata (varsAbstract já as omite) — não semear aqui evita que o loop abaixo detete uma
        // colisão inexistente e renomeie a declaração da implementação para _i/_r sem necessidade.
        declaredVariableNames.removeAll(collapsedVariableNames);
        Map<Element, Map<String, String>> mergedVariableRenames = new LinkedHashMap<>();
        Element refinementChainParent = machineEl;
        for (Element mel : mergedMachineElements) {
            appendOneMergedVariableBlock(
                    state, mel, refinementChainParent, declaredVariableNames, mergedVariableRenames);
            refinementChainParent = mel;
        }
        state.mergedVariableRenames = mergedVariableRenames;
    }

    /**
     * Uma iteração do laço de {@link #appendMachineVariableBlocks}: formata e anexa o bloco
     * {@code axiomatic Nome_variables} de UM elemento fundido (refinamento/implementação),
     * detetando e aplicando a renomeação {@code _r}/{@code _i} para colisões com nomes já
     * declarados (ver comentário original em {@link #appendMachineVariableBlocks}).
     */
    private static void appendOneMergedVariableBlock(
            AcslGenerationState state,
            Element mel,
            Element refinementChainParent,
            Set<String> declaredVariableNames,
            Map<Element, Map<String, String>> mergedVariableRenames) {
        BxmlTranslateContext ctx = state.ctx;
        BxmlTranslateContext mctx =
                buildMergedElementContext(
                        mel, ctx, state.gluing, state.machineEl, state.refinementChainVariableLogicTypes);
        String varsMerged =
                MachineAxiomaticBlockFormatter.formatAxiomaticBlock(
                        mel, mctx, state.baseName, refinementChainParent, state.gluing, state.bxmlDirectory);

        Set<String> ownNames = BxmlMachineVariables.inferVariableLogicTypes(mel, mctx).keySet();
        Map<String, String> renameMap = new LinkedHashMap<>();
        String suffix = "implementation".equalsIgnoreCase(mel.getAttribute("type")) ? "_i" : "_r";
        for (String name : ownNames) {
            if (declaredVariableNames.contains(name)) {
                renameMap.put(name, name + suffix);
            }
        }
        if (!renameMap.isEmpty()) {
            varsMerged = renameWholeWordIdentifiers(varsMerged, renameMap);
            mergedVariableRenames.put(mel, renameMap);
        }
        for (String name : ownNames) {
            declaredVariableNames.add(renameMap.getOrDefault(name, name));
        }

        appendBlockWithSpacing(state.sb, varsMerged);
    }

    private static void appendMergedConstantsPropertiesAndValuesBlocks(AcslGenerationState state) {
        List<Element> mergedMachineElements = state.mergedMachineElements;
        BxmlTranslateContext ctx = state.ctx;
        StringBuilder sb = state.sb;

        // Constantes e propriedades das máquinas fundidas — depois das variáveis, antes das
        // compreensões, para que constantes como MAX_COPY estejam declaradas quando necessário.
        for (Element mel : mergedMachineElements) {
            appendMergedConstantsAndPropertiesForElement(state, mel);
        }

        // Compreensões: todas as variáveis e constantes já estão declaradas.
        // set_comprehension_k deve estar declarado antes de _i_values (que o referencia).
        if (!ctx.comprehensions().isEmpty()) {
            sb.append(ctx.comprehensions().formatAxiomaticBlock(state.baseName, ctx));
            sb.append("\n");
        }
        // Values das máquinas fundidas (podem referenciar compreensões).
        for (Element mel : mergedMachineElements) {
            appendMergedValuesForElement(state, mel);
        }
    }

    private static void appendMergedConstantsAndPropertiesForElement(AcslGenerationState state, Element mel) {
        BxmlTranslateContext mctx =
                buildMergedElementContext(
                        mel, state.ctx, state.gluing, state.machineEl, state.refinementChainVariableLogicTypes);
        StringBuilder sb = state.sb;
        String constsMerged = BxmlConstantsAndProperties.formatConcreteConstantsBlock(mel, mctx);
        appendBlockWithSpacing(sb, constsMerged);
        String propsMerged = BxmlConstantsAndProperties.formatPropertiesBlock(mel, mctx);
        appendBlockWithSpacing(sb, propsMerged);
    }

    private static void appendMergedValuesForElement(AcslGenerationState state, Element mel) {
        BxmlTranslateContext mctx =
                buildMergedElementContext(
                        mel, state.ctx, state.gluing, state.machineEl, state.refinementChainVariableLogicTypes);
        String valuesMerged =
                BxmlConstantsAndProperties.formatValuesBlock(mel, mctx, state.machineEl);
        appendBlockWithSpacing(state.sb, valuesMerged);
    }

    private static void appendInvariantsAndQuantifierBlocks(AcslGenerationState state) throws IOException {
        Element machineEl = state.machineEl;
        Path outputDir = state.outputDir;
        String baseName = state.baseName;
        List<Element> mergedMachineElements = state.mergedMachineElements;
        Map<String, String> gluing = state.gluing;
        BxmlTranslateContext ctx = state.ctx;
        Map<String, String> refinementChainVariableLogicTypes = state.refinementChainVariableLogicTypes;
        Map<Element, Map<String, String>> mergedVariableRenames = state.mergedVariableRenames;
        StringBuilder libScanRemovedBodies = state.libScanRemovedBodies;
        StringBuilder sb = state.sb;

        // 2) Todos os predicate (invariantes) — traduzidos já aqui (podem registar lambdas, ex. um
        // invariante de colagem que chama directamente uma "sequence builder"), mas o texto fica em
        // buffers separados e só é anexado a 'sb' DEPOIS do bloco lambda_functions abaixo: um
        // invariante como Fifo_i_2_invariant pode referenciar lambda_func01 directamente (não só
        // através de Properties), e ACSL exige declare-antes-de-usar.
        String invariantPredicates = BxmlInvariantTranslator.formatInvariantPredicates(machineEl, ctx);
        StringBuilder mergedInvariantsSb = new StringBuilder();
        appendMergedInvariantPredicatesOnly(
                mergedInvariantsSb, mergedMachineElements, gluing, ctx.comprehensions(), machineEl,
                ctx.enumeratedSetRenames(), mergedVariableRenames, ctx.lambdaRegistry(), ctx.sigmaRegistry(),
                ctx.unionInterRegistry(), ctx.crossMachineVariableNames(),
                ctx.crossMachineVariableLogicTypes(), refinementChainVariableLogicTypes);

        // 2b) Lambdas (emissão tardia, após variáveis): lambda_functions pode referenciar variáveis
        // de estado (ex.: copyOf) que só estão declaradas nos blocos de variáveis acima. Emitido
        // antes dos invariantes/properties bufferizados acima/abaixo, que podem referenciar
        // predicados/funções lambda.
        int lambdaEmittedUpTo = 0;
        LambdaFunctionRegistry lambdaRegistry = ctx.lambdaRegistry();
        if (lambdaRegistry != null && lambdaRegistry.size() > lambdaEmittedUpTo) {
            sb.append("\n");
            sb.append(lambdaRegistry.formatAxiomaticBlockFrom(lambdaEmittedUpTo));
            sb.append("\n");
        }
        // 2c) SIGMA/PI/MIN/MAX (mesma emissão tardia que os lambdas, pelo mesmo motivo: podem
        // referenciar variáveis de estado só declaradas nos blocos de variáveis acima). Ao
        // contrário dos lambdas (só predicate/logic escalares), este bloco usa Set<A> genérico
        // (ex.: Set<integer> no caso de domínio-conjunto) — -acsl-import só aceita instanciação
        // genérica concreta dentro de um ficheiro alcançado via include, nunca inline num ficheiro
        // de topo (confirmado empiricamente: "[Syntax error] <" na primeira ocorrência de "Set<" se
        // deixado inline aqui — mesma restrição que TupleCodomainTypeRegistry já contorna para
        // "type X = Y;"). Escreve num .acsl próprio da máquina e inclui NESTA MESMA posição (não no
        // preâmbulo: o bloco referencia variáveis de estado só declaradas ANTES deste ponto).
        int sigmaEmittedUpTo = 0;
        SigmaFunctionRegistry sigmaRegistry = ctx.sigmaRegistry();
        if (sigmaRegistry != null && sigmaRegistry.size() > sigmaEmittedUpTo) {
            String sigmaBlock = sigmaRegistry.formatAxiomaticBlockFrom(sigmaEmittedUpTo);
            String sigmaFileName = baseName + "_sigma_functions.acsl";
            Files.writeString(outputDir.resolve(sigmaFileName), sigmaBlock);
            // O conteúdo saiu de 'sb' (só o include entra) — sem isto, símbolos da lib usados só lá
            // dentro (is_finite, belongs, singleton, …) nunca apareceriam no texto varrido para
            // decidir os includes do ficheiro raiz (mesmo padrão de connection.acsl, ver acima).
            libScanRemovedBodies.append(sigmaBlock).append('\n');
            sb.append("\n");
            sb.append("include \"").append(sigmaFileName).append("\";\n");
            sb.append("\n");
        }
        // 2d) UNION/INTER (mesmo motivo e mesma técnica de include separado que 2c — Set<T> genérico).
        int unionInterEmittedUpTo = 0;
        UnionInterFunctionRegistry unionInterRegistry = ctx.unionInterRegistry();
        if (unionInterRegistry != null && unionInterRegistry.size() > unionInterEmittedUpTo) {
            String unionInterBlock = unionInterRegistry.formatAxiomaticBlockFrom(unionInterEmittedUpTo);
            String unionInterFileName = baseName + "_union_inter_functions.acsl";
            Files.writeString(outputDir.resolve(unionInterFileName), unionInterBlock);
            libScanRemovedBodies.append(unionInterBlock).append('\n');
            sb.append("\n");
            sb.append("include \"").append(unionInterFileName).append("\";\n");
            sb.append("\n");
        }
        if (!invariantPredicates.isBlank()) {
            sb.append("\n");
            sb.append(invariantPredicates);
            if (!invariantPredicates.endsWith("\n")) sb.append("\n");
            sb.append("\n");
        }
        sb.append(mergedInvariantsSb);
        String propertiesBlock = state.propertiesBlock;
        if (!propertiesBlock.isBlank()) {
            sb.append(propertiesBlock);
            if (!propertiesBlock.endsWith("\n")) sb.append("\n");
            sb.append("\n");
        }
    }

    private static void appendFunctionsSection(AcslGenerationState state) {
        StringBuilder sb = state.sb;
        // 3) Funções: inicialização e operações
        if (state.isAbstraction && state.init != null) {
            sb.append("\n");
            sb.append(state.init.toContractText());
            if (!state.operations.isEmpty()) {
                sb.append("\n");
                for (OperationAcsl op : state.operations) {
                    sb.append("\n").append(op.toContractSketch());
                }
            }
        }
    }

    /**
     * Fase 3 de {@link #generateAcsl}: varredura de símbolos da lib, resolução de includes, ficheiro
     * de tipos-tupla e escrita final do {@code .acsl} em disco.
     */
    private static Path finalizeAndWriteAcslFile(
            AcslGenerationState state,
            BxmlSeesGraph seesGraph,
            BxmlImportsGraph importsGraph,
            String libIncludeScanRootMachineName,
            String extraLibScanText)
            throws Exception {
        LibIncludeResolution libResolution =
                resolveLibIncludesAndDependencyScan(
                        state, seesGraph, importsGraph, libIncludeScanRootMachineName, extraLibScanText);
        String machineDependencyIncludes = resolveMachineDependencyIncludes(state, seesGraph, importsGraph);
        String tupleTypesInclude = writeTupleTypesFileIfNeeded(state);

        StringBuilder sb = state.sb;
        int headerLen = state.headerLen;
        boolean omitLibIncludesFromPreamble = state.omitLibIncludesFromPreamble;

        StringBuilder preambleIncludes = new StringBuilder();
        // import/types.acsl PRIMEIRO: declara os tipos genéricos Set<A>/Tuple<A,B> em si (ver
        // "axiomatic new_types" nesse ficheiro — "type Set<A>;"/"type Tuple<A,B>;", sem corpo). O
        // ficheiro de tipos-tupla desta máquina só usa "Set<Tuple<...>>" já assumindo que ambos
        // estão conhecidos — invertido, o front-end do acsl-import (mais estrito que o "-print" só
        // do kernel, usado para verificar progresso durante o desenvolvimento) rejeita com
        // "[Syntax error] <" logo no primeiro uso, por não saber ainda que "Set"/"Tuple" são
        // genéricos (confirmado: isolar SÓ o alias mais antigo, já usado noutros exemplos, sem
        // nenhum tipo introduzido por esta sessão, reproduz o mesmo erro na mesma posição).
        if (!omitLibIncludesFromPreamble && !libResolution.libIncludes().isEmpty()) {
            preambleIncludes.append(libResolution.libIncludes());
        }
        if (!tupleTypesInclude.isEmpty()) {
            preambleIncludes.append(tupleTypesInclude);
        }
        appendAcslMachineIncludes(preambleIncludes, machineDependencyIncludes);
        if (!preambleIncludes.isEmpty()) {
            sb.insert(headerLen, preambleIncludes);
        }
        String fullAcsl = sb.toString();
        if (omitLibIncludesFromPreamble) {
            fullAcsl = AcslLibIncludes.removeLibIncludesFromPreamble(fullAcsl);
        }
        if (!state.bDefinitions.isEmpty()) {
            // Reescreve os pontos de uso (ensures/invariant/…, já gerados a partir do BXML
            // totalmente expandido) para referenciar os nomes simbólicos de DEFINITIONS em vez dos
            // valores literais que o BXML só conhece — ver BDefinitionsTranslator.
            fullAcsl = BDefinitionsTranslator.rewriteCallSites(fullAcsl, state.bDefinitions);
        }
        Path acslFile = state.acslFile;
        Files.writeString(acslFile, fullAcsl);
        if (!omitLibIncludesFromPreamble) {
            AcslLibIncludes.copyReferencedLibraryFiles(
                    fullAcsl, acslFile, libResolution.combinedExtraScan(), libResolution.dependencyLibIncludePaths());
        }
        return acslFile;
    }

    private record LibIncludeResolution(
            String combinedExtraScan, String libIncludes, List<String> dependencyLibIncludePaths) {}

    private static LibIncludeResolution resolveLibIncludesAndDependencyScan(
            AcslGenerationState state,
            BxmlSeesGraph seesGraph,
            BxmlImportsGraph importsGraph,
            String libIncludeScanRootMachineName,
            String extraLibScanText) {
        Element machineEl = state.machineEl;
        Path outputDir = state.outputDir;
        String baseName = state.baseName;
        Path bxmlDirectory = state.bxmlDirectory;
        List<Element> mergedMachineElements = state.mergedMachineElements;
        boolean libCarrier = state.libCarrier;
        StringBuilder libScanRemovedBodies = state.libScanRemovedBodies;

        String extraLibSymbolScan =
                libScanRemovedBodies.length() == 0 ? null : libScanRemovedBodies.toString();
        String bodyForLibScan = state.sb.substring(state.headerLen);
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
                    MachineIncludeScanCollector.collectTransitiveDependencyMachinesTextForIncludeScan(
                            libScanRoot, seesGraph, importsGraph, bxmlDirectory, outputDir);
            if (libCarrier && !libScanRoot.equals(baseName)) {
                dependencyMachinesScan =
                        joinNonBlank(
                                dependencyMachinesScan,
                                MachineIncludeScanCollector.collectMachineTextForIncludeScan(
                                        libScanRoot, bxmlDirectory, outputDir));
            }
            dependencyLibIncludePaths =
                    new ArrayList<>(
                            MachineIncludeScanCollector.collectLibIncludePathsFromTransitiveDependencies(
                                    libScanRoot, seesGraph, importsGraph, outputDir));
        } else {
            String seesMachinesScan =
                    MachineIncludeScanCollector.collectSeesMachinesTextForIncludeScan(
                            machineEl, bxmlDirectory, outputDir);
            String importsMachinesScan =
                    MachineIncludeScanCollector.collectImportedMachinesTextForIncludeScan(
                            machineEl, mergedMachineElements, bxmlDirectory, outputDir);
            dependencyMachinesScan = joinNonBlank(seesMachinesScan, importsMachinesScan);
            dependencyLibIncludePaths = new ArrayList<>();
            dependencyLibIncludePaths.addAll(
                    MachineIncludeScanCollector.collectLibIncludePathsFromSeenMachines(
                            machineEl, bxmlDirectory, outputDir));
            dependencyLibIncludePaths.addAll(
                    MachineIncludeScanCollector.collectLibIncludePathsFromImportedMachines(
                            machineEl, mergedMachineElements, bxmlDirectory, outputDir));
        }
        String combinedExtraScan =
                joinNonBlank(
                        joinNonBlank(extraLibScanText, extraLibSymbolScan),
                        dependencyMachinesScan);
        String libIncludes =
                AcslLibIncludes.formatIncludeBlock(
                        bodyForLibScan, combinedExtraScan, dependencyLibIncludePaths);
        return new LibIncludeResolution(combinedExtraScan, libIncludes, dependencyLibIncludePaths);
    }

    private static String resolveMachineDependencyIncludes(
            AcslGenerationState state, BxmlSeesGraph seesGraph, BxmlImportsGraph importsGraph) {
        if (seesGraph != null || importsGraph != null) {
            // Includes de outras máquinas ficam a cargo do -acsl-import multi-ficheiro (B2ACSLPipeline).
            return "";
        }
        Element machineEl = state.machineEl;
        Path bxmlDirectory = state.bxmlDirectory;
        List<Element> mergedMachineElements = state.mergedMachineElements;
        String seesIncludes = BxmlSetsTranslator.formatSeesIncludeBlock(machineEl, bxmlDirectory);
        String importsIncludes =
                BxmlSetsTranslator.formatImportsIncludeBlock(machineEl, mergedMachineElements, bxmlDirectory);
        StringBuilder legacy = new StringBuilder();
        appendAcslMachineIncludes(legacy, seesIncludes);
        appendAcslMachineIncludes(legacy, importsIncludes);
        return legacy.toString();
    }

    /**
     * Tipos de codomínio-tupla (ver TupleCodomainTypeRegistry/BxmlTypeRegistry#powCartesianProductToAcslRelationType)
     * descobertos ao inferir os tipos das variáveis desta máquina — sem alias estático possível
     * (explosão combinatória de tipo x aridade), escritos num .acsl próprio desta máquina e
     * incluídos localmente (confirmado que -acsl-import aceita "type X = Y;" alcançado via
     * include). Agrupados dentro de UM axiomatic block (em vez de declarações soltas): a
     * impressão do Frama-C ("-acsl-import ... -print") reordena declarações "type X = Y;"
     * soltas de forma NÃO DETERMINÍSTICA entre execuções (confirmado empiricamente: mesmo jar,
     * mesmo input, Function_X às vezes impresso antes de Relation_X apesar do ficheiro-fonte
     * sempre os ter na mesma ordem) — quando isso acontece, o Frama-C falha a unificar o
     * sinónimo Function_X=Relation_X na resolução de sobrecarga de function_apply. Um
     * axiomatic block imprime o seu conteúdo em ordem estável (como o "axiomatic new_types" da
     * própria lib, nunca visto reordenado nas várias execuções desta sessão).
     */
    private static String writeTupleTypesFileIfNeeded(AcslGenerationState state) throws IOException {
        Map<String, String> extraTupleTypes = com.example.bxml.TupleCodomainTypeRegistry.snapshotAndClear();
        if (extraTupleTypes.isEmpty()) {
            return "";
        }
        String baseName = state.baseName;
        Path tupleTypesFile = state.outputDir.resolve(baseName + "_tuple_types.acsl");
        StringBuilder tupleTypesContent = new StringBuilder();
        tupleTypesContent.append("axiomatic ").append(baseName).append("_tuple_types {\n");
        for (Map.Entry<String, String> e : extraTupleTypes.entrySet()) {
            tupleTypesContent
                    .append("  type ")
                    .append(e.getKey())
                    .append(" = ")
                    .append(e.getValue())
                    .append(";\n");
        }
        tupleTypesContent.append("}\n");
        Files.writeString(tupleTypesFile, tupleTypesContent.toString());
        return "include \"" + baseName + "_tuple_types.acsl\";\n";
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

    /**
     * União dos tipos {@code logic} de variáveis de TODAS as camadas da cadeia de refinamento
     * ({@code machineEl} abstrata + cada elemento de {@code mergedMachineElements}, ex. {@code _r},
     * {@code _i}) — ao contrário de {@link BxmlTranslateContext#variableLogicTypes()} normal, que só
     * cobre a própria máquina sendo traduzida (mais a abstrata raiz, quando fundida via {@code
     * forMachineWithSharedComprehensions}). Primeira camada a declarar um nome vence (nomes não
     * deviam colidir entre camadas sem já terem sido renomeados por {@code AcslGenerator}).
     */
    private static Map<String, String> refinementChainVariableLogicTypes(
            Element machineEl, List<Element> mergedMachineElements) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        List<Element> chain = new ArrayList<>();
        chain.add(machineEl);
        if (mergedMachineElements != null) {
            chain.addAll(mergedMachineElements);
        }
        for (Element m : chain) {
            BxmlTypeRegistry types = BxmlTypeRegistry.fromMachine(m);
            BxmlTranslateContext tmp =
                    new BxmlTranslateContext(types, BxmlComprehensionRegistry.emptyForFingerprinting());
            for (Map.Entry<String, String> e : BxmlMachineVariables.inferVariableLogicTypes(m, tmp).entrySet()) {
                out.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /**
     * Apenas {@code predicate} de invariantes de refinamentos/implementações.
     *
     * @param variableRenamesByMachine mesma renomeação (por colisão de nome com a variável
     *        abstrata, ex.: {@code limit} → {@code limit_i}) aplicada ao bloco de variáveis dessa
     *        camada em {@link #generateAcsl}; aplica-se aqui ao invariante da MESMA camada para
     *        que ambos refiram a variável renomeada consistentemente.
     */
    private static void appendMergedInvariantPredicatesOnly(
            StringBuilder sb,
            List<Element> mergedMachineRoots,
            Map<String, String> gluing,
            BxmlComprehensionRegistry sharedComprehensions,
            Element rootAbstractMachineEl,
            Map<String, String> enumeratedSetRenames,
            Map<Element, Map<String, String>> variableRenamesByMachine,
            LambdaFunctionRegistry lambdaRegistry,
            SigmaFunctionRegistry sigmaRegistry,
            UnionInterFunctionRegistry unionInterRegistry,
            Set<String> crossMachineVariableNames,
            Map<String, String> crossMachineVariableLogicTypes,
            Map<String, String> refinementChainVariableLogicTypes) {
        if (mergedMachineRoots == null || mergedMachineRoots.isEmpty()) {
            return;
        }
        for (Element mel : mergedMachineRoots) {
            BxmlTranslateContext ctx =
                    BxmlTranslateContext.forMachineWithSharedComprehensions(
                            mel, sharedComprehensions, gluing, rootAbstractMachineEl)
                    .withEnumeratedSetRenames(enumeratedSetRenames)
                    .withLambdaRegistry(lambdaRegistry)
                    .withSigmaRegistry(sigmaRegistry)
                    .withUnionInterRegistry(unionInterRegistry)
                    .withCrossMachineVariableNames(crossMachineVariableNames)
                    .withCrossMachineVariableLogicTypes(crossMachineVariableLogicTypes)
                    .withAdditionalVariableLogicTypes(refinementChainVariableLogicTypes);
            String inv = BxmlInvariantTranslator.formatInvariantPredicates(mel, ctx);
            if (inv.isBlank()) continue;
            Map<String, String> renameMap = variableRenamesByMachine.get(mel);
            if (renameMap != null && !renameMap.isEmpty()) {
                inv = renameWholeWordIdentifiers(inv, renameMap);
            }
            sb.append("\n");
            sb.append(inv);
            if (!inv.endsWith("\n")) sb.append("\n");
        }
    }

    /** Substitui (fronteira de palavra) cada chave de {@code renameMap} pelo respetivo valor em {@code text}. */
    private static String renameWholeWordIdentifiers(String text, Map<String, String> renameMap) {
        String result = text;
        for (Map.Entry<String, String> e : renameMap.entrySet()) {
            result =
                    result.replaceAll(
                            "(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(e.getKey()) + "(?![A-Za-z0-9_])",
                            java.util.regex.Matcher.quoteReplacement(e.getValue()));
        }
        return result;
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
                init.dummyGhostEnsureVarNames(),
                init.loopSpecs(),
                init.explicitLoops(),
                init.emitMinimalContract());
    }

    private static boolean machineHasNoImports(Element machineEl, List<Element> mergedMachineElements) {
        if (!com.example.bxml.BxmlSetsTranslator.listImportedMachineNames(machineEl).isEmpty()) {
            return false;
        }
        if (mergedMachineElements != null) {
            for (Element mel : mergedMachineElements) {
                if (!com.example.bxml.BxmlSetsTranslator.listImportedMachineNames(mel).isEmpty()) {
                    return false;
                }
            }
        }
        return true;
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



    /**
     * Varre todos os arquivos {@code *_i.bxml} no diretório e coleta valuations de constantes inteiras
     * literais (ex. {@code NN = 10}), para uso em anotações que exigem expressão constante (loop unfold).
     */
    private static Map<String, Long> collectAllIntegerValuations(Path bxmlDirectory) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (bxmlDirectory == null || !Files.isDirectory(bxmlDirectory)) return result;
        try (var stream = Files.list(bxmlDirectory)) {
            for (Path p : stream.filter(f -> f.getFileName().toString().endsWith("_i.bxml"))
                    .sorted().toList()) {
                try {
                    Element el = parseMachineElement(p);
                    result.putAll(BxmlConstantsAndProperties.extractLiteralIntegerValuations(el));
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return result;
    }
}
