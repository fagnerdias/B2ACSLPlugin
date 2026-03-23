package com.example;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
import com.example.bxml.BxmlInvariantTranslator;
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

        List<String> invariantNames = BxmlInvariantTranslator.listInvariantPredicateNames(machineEl, ctx);
        List<String> extraAssigns = extraAssignTargetsForExample(baseName);
        InitialisationAcsl init = isAbstraction
                ? BxmlInitialisationTranslator.translate(machineEl, extraAssigns, ctx)
                : null;
        List<OperationAcsl> operations = isAbstraction
                ? BxmlOperationsTranslator.translateOperations(machineEl, ctx, invariantNames)
                : List.of();

        StringBuilder sb = new StringBuilder();
        sb.append("/* ACSL gerado a partir de ").append(baseName).append(".bxml (BXML 1.0) */\n");
        sb.append("/* Biblioteca: usar ACSL_Lib em resources, p.ex.:\n");
        sb.append(" *   set_functions/empty.acsl, union.acsl, singleton.acsl, inclusion.acsl\n");
        sb.append(" *   set_functions/card.acsl\n");
        sb.append(" */\n\n");

        // 1) Todos os blocos axiomatic (compreensões)
        if (!ctx.comprehensions().isEmpty()) {
            sb.append(ctx.comprehensions().formatAxiomaticBlock(baseName, ctx.types()));
            sb.append("\n");
        }
        Set<String> comprehensionFingerprintsSeen = new HashSet<>();
        ctx.comprehensions().collectDistinctFingerprints(ctx.types(), comprehensionFingerprintsSeen);
        appendMergedAxiomaticBlocksOnly(sb, mergePaths, comprehensionFingerprintsSeen, gluing);

        // 2) Todos os predicate (invariantes)
        String invariantPredicates = BxmlInvariantTranslator.formatInvariantPredicates(machineEl, ctx);
        if (!invariantPredicates.isBlank()) {
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

        Files.writeString(acslFile, sb.toString());
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

    /** Apenas blocos {@code axiomatic} de refinamentos/implementações (sem {@code predicate}). */
    private static void appendMergedAxiomaticBlocksOnly(
            StringBuilder sb,
            List<Path> mergePaths,
            Set<String> comprehensionFingerprintsSeen,
            Map<String, String> gluing)
            throws Exception {
        if (mergePaths.isEmpty()) return;
        for (Path p : mergePaths) {
            Element mel = parseMachineElement(p);
            String src = mel.getAttribute("name");
            BxmlTranslateContext ctx = BxmlTranslateContext.forMachine(mel, gluing);
            String axiomatic =
                    ctx.comprehensions()
                            .formatAxiomaticBlockUnlessFullyCovered(
                                    src, ctx.types(), comprehensionFingerprintsSeen);
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
            sb.append("\n/* --- ").append(src).append(": invariante --- */\n");
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
     * Para o exemplo OddEvenCounter: variáveis de implementação referenciadas no contrato (C).
     * Em geral viriam de refinamento / mapeamento B→C.
     */
    private static List<String> extraAssignTargetsForExample(String machineName) {
        if ("OddEvenCounter".equals(machineName)) {
            return List.of(
                    "OddEvenCounter__odd_counter",
                    "OddEvenCounter__even_counter");
        }
        return List.of();
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
