package com.example;

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
import com.example.bxml.BxmlTranslateContext;
import com.example.model.Machine;

/**
 * Gera arquivos ACSL a partir de BXML e funções da biblioteca {@code ACSL_Lib} em {@code src/main/resources/ACSL_Lib}.
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
 * só elo abstração→refinamento).
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
        Document doc = parseXml(bxmlPath);
        Element machineEl = doc.getDocumentElement();
        if (referencesAbstractMachineViaAbstractionTag(machineEl)) {
            return Optional.empty();
        }

        Files.createDirectories(outputDir);
        String baseName = machine.getMachineName();
        Path acslFile = outputDir.resolve(baseName + ".acsl");

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

        BxmlTranslateContext ctx =
                BxmlTranslateContext.forMachineWithSharedComprehensions(
                        machineEl, sharedComprehensions, gluing);

        List<String> allInvariantPredicateNames =
                listAllInvariantPredicateNames(machineEl, ctx, mergedMachineElements, gluing);
        List<String> implementationAssignTargets =
                BxmlMachineVariables.listImplementationAssignTargets(baseName, mergedMachineElements);
        InitialisationAcsl initBare =
                BxmlInitialisationTranslator.translate(
                        machineEl, implementationAssignTargets, ctx);
        boolean initGhostAssert =
                GhostOperationsCiGenerator.initialisationAssignsAbstract(
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
                                abstractVariableNamesForGhost,
                                libScanRemovedBodies,
                                baseName,
                                mergedMachineElements,
                                gluing)
                        : List.of();

        StringBuilder sb = new StringBuilder();
        sb.append("/* ACSL gerado a partir de ").append(baseName).append(".bxml (BXML 1.0) */\n");
        sb.append(
                "/* Biblioteca ACSL_Lib: includes gerados automaticamente (AcslLibIncludes); "
                        + "opções: b2acsl.acslLibIncludeBase, b2acsl.acslLibIncludeMiddle. */\n\n");
        int headerLen = sb.length();

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
                GhostOperationsCiGenerator.formatGhostPatternsAxiomaticBlock(machineEl, baseName);
        if (!ghostPatternsBlock.isBlank()) {
            sb.append(ghostPatternsBlock);
            if (!ghostPatternsBlock.endsWith("\n")) sb.append("\n");
            sb.append("\n");
        }

        // 1b) Variáveis: um bloco axiomatic por máquina (abstrata, depois cada fundida) + compreensões
        String varsAbstract =
                BxmlMachineVariables.formatAxiomaticBlockWithGhostDummyReads(
                        machineEl, ctx, abstractVariableNamesForGhost);
        if (!varsAbstract.isBlank()) {
            sb.append(varsAbstract);
            if (!varsAbstract.endsWith("\n")) sb.append("\n");
            sb.append("\n");
        }
        Element refinementChainParent = machineEl;
        for (Element mel : mergedMachineElements) {
            BxmlTranslateContext mctx =
                    BxmlTranslateContext.forMachineWithSharedComprehensions(mel, ctx.comprehensions(), gluing);
            String valuesMerged = BxmlConstantsAndProperties.formatValuesBlock(mel, mctx);
            if (!valuesMerged.isBlank()) {
                sb.append(valuesMerged);
                if (!valuesMerged.endsWith("\n")) sb.append("\n");
                sb.append("\n");
            }
            String varsMerged =
                    BxmlMachineVariables.formatAxiomaticBlock(
                            mel, mctx, baseName, refinementChainParent, gluing);
            refinementChainParent = mel;
            if (varsMerged.isBlank()) continue;
            sb.append(varsMerged);
            if (!varsMerged.endsWith("\n")) sb.append("\n");
            sb.append("\n");
        }
        if (!ctx.comprehensions().isEmpty()) {
            sb.append(ctx.comprehensions().formatAxiomaticBlock(baseName));
            sb.append("\n");
        }

        // 2) Todos os predicate (invariantes)
        String invariantPredicates = BxmlInvariantTranslator.formatInvariantPredicates(machineEl, ctx);
        if (!invariantPredicates.isBlank()) {
            sb.append("\n");
            sb.append(invariantPredicates);
            if (!invariantPredicates.endsWith("\n")) sb.append("\n");
            sb.append("\n");
        }
        appendMergedInvariantPredicatesOnly(sb, mergedMachineElements, gluing, ctx.comprehensions());

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
        String includes =
                AcslLibIncludes.formatIncludeBlock(sb.substring(headerLen), extraLibSymbolScan);
        if (!includes.isEmpty()) {
            sb.insert(headerLen, includes);
        }
        String fullAcsl = sb.toString();
        Files.writeString(acslFile, fullAcsl);
        AcslLibIncludes.copyReferencedLibraryFiles(fullAcsl, acslFile, extraLibSymbolScan);
        return Optional.of(acslFile);
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
            BxmlComprehensionRegistry sharedComprehensions) {
        if (mergedMachineRoots == null || mergedMachineRoots.isEmpty()) {
            return;
        }
        for (Element mel : mergedMachineRoots) {
            BxmlTranslateContext ctx =
                    BxmlTranslateContext.forMachineWithSharedComprehensions(mel, sharedComprehensions, gluing);
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
                            mel, ctx.comprehensions(), gluing);
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
