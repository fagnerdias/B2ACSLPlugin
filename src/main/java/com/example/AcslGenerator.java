package com.example;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
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
 * {@code axiomatic Nome_properties} com axiomas ({@link BxmlConstantsAndProperties}).
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

        BxmlTranslateContext ctx = BxmlTranslateContext.forMachine(machineEl, gluing);
        boolean isAbstraction = isAbstractMachine(machineEl);

        List<Element> mergedMachineElements = new ArrayList<>();
        for (Path p : mergePaths) {
            mergedMachineElements.add(parseMachineElement(p));
        }

        List<String> allInvariantPredicateNames =
                listAllInvariantPredicateNames(machineEl, ctx, mergePaths, gluing);
        List<String> implementationAssignTargets =
                BxmlMachineVariables.listImplementationAssignTargets(baseName, mergedMachineElements);
        InitialisationAcsl init = isAbstraction
                ? withInvariantEnsures(
                        BxmlInitialisationTranslator.translate(
                                machineEl, implementationAssignTargets, ctx),
                        allInvariantPredicateNames)
                : null;
        List<OperationAcsl> operations = isAbstraction
                ? BxmlOperationsTranslator.translateOperations(machineEl, ctx, allInvariantPredicateNames)
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

        // 1b) Variáveis: um bloco axiomatic por máquina (abstrata, depois cada fundida) + compreensões
        String varsAbstract = BxmlMachineVariables.formatAxiomaticBlock(machineEl, ctx);
        if (!varsAbstract.isBlank()) {
            sb.append(varsAbstract);
            if (!varsAbstract.endsWith("\n")) sb.append("\n");
            sb.append("\n");
        }
        for (Element mel : mergedMachineElements) {
            BxmlTranslateContext mctx = BxmlTranslateContext.forMachine(mel, gluing);
            String varsMerged =
                    BxmlMachineVariables.formatAxiomaticBlock(mel, mctx, baseName);
            if (varsMerged.isBlank()) continue;
            sb.append(varsMerged);
            if (!varsMerged.endsWith("\n")) sb.append("\n");
            sb.append("\n");
        }
        if (!ctx.comprehensions().isEmpty()) {
            sb.append(ctx.comprehensions().formatAxiomaticBlock(baseName, ctx.types()));
            sb.append("\n");
        }
        Set<String> comprehensionFingerprintsSeen = new HashSet<>();
        ctx.comprehensions().collectDistinctFingerprints(ctx.types(), comprehensionFingerprintsSeen);
        appendMergedAxiomaticBlocksOnly(sb, mergedMachineElements, comprehensionFingerprintsSeen, gluing);

        // 2) Todos os predicate (invariantes)
        String invariantPredicates = BxmlInvariantTranslator.formatInvariantPredicates(machineEl, ctx);
        if (!invariantPredicates.isBlank()) {
            sb.append("\n");
            sb.append(invariantPredicates);
            if (!invariantPredicates.endsWith("\n")) sb.append("\n");
            sb.append("\n");
        }
        appendMergedInvariantPredicatesOnly(sb, mergePaths, gluing);

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

        String includes = AcslLibIncludes.formatIncludeBlock(sb.substring(headerLen));
        if (!includes.isEmpty()) {
            sb.insert(headerLen, includes);
        }
        String fullAcsl = sb.toString();
        Files.writeString(acslFile, fullAcsl);
        AcslLibIncludes.copyReferencedLibraryFiles(fullAcsl, acslFile);
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

    /** Compreensões de refinamentos/implementações (variáveis já emitidas por máquina). */
    private static void appendMergedAxiomaticBlocksOnly(
            StringBuilder sb,
            List<Element> mergedMachineElements,
            Set<String> comprehensionFingerprintsSeen,
            Map<String, String> gluing) {
        if (mergedMachineElements.isEmpty()) return;
        for (Element mel : mergedMachineElements) {
            String src = mel.getAttribute("name");
            BxmlTranslateContext mctx = BxmlTranslateContext.forMachine(mel, gluing);
            String axiomatic =
                    mctx.comprehensions()
                            .formatAxiomaticBlockUnlessFullyCovered(
                                    src, mctx.types(), comprehensionFingerprintsSeen);
            if (axiomatic.isBlank()) continue;
            sb.append("\n/* --- ").append(src).append(": compreensões --- */\n");
            sb.append(axiomatic);
            if (!axiomatic.endsWith("\n")) sb.append("\n");
        }
    }

    /** Apenas {@code predicate} de invariantes de refinamentos/implementações. */
    private static void appendMergedInvariantPredicatesOnly(
            StringBuilder sb, List<Path> mergePaths, Map<String, String> gluing) throws Exception {
        if (mergePaths.isEmpty()) return;
        for (Path p : mergePaths) {
            Element mel = parseMachineElement(p);
            String src = mel.getAttribute("name");
            BxmlTranslateContext ctx = BxmlTranslateContext.forMachine(mel, gluing);
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
     * {@code mergePaths} (mesma ordem que em {@link #appendMergedInvariantPredicatesOnly}), para
     * repetir em {@code requires}/{@code ensures} das operações e em {@code ensures} da inicialização.
     */
    private static List<String> listAllInvariantPredicateNames(
            Element machineEl,
            BxmlTranslateContext ctx,
            List<Path> mergePaths,
            Map<String, String> gluing)
            throws Exception {
        List<String> out = new ArrayList<>(BxmlInvariantTranslator.listInvariantPredicateNames(machineEl, ctx));
        for (Path p : mergePaths) {
            Element mel = parseMachineElement(p);
            BxmlTranslateContext mctx = BxmlTranslateContext.forMachine(mel, gluing);
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
        return new InitialisationAcsl(init.functionName(), ensures, init.assignsTargets());
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
