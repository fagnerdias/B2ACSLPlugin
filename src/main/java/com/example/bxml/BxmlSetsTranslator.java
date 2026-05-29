package com.example.bxml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import com.example.AcslLibIncludes;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Traduz a tag {@code <Sets>} do BXML 1.0 para um bloco {@code axiomatic} ACSL.
 *
 * <p>Cada conjunto deferido {@code <Set><Id value='S'/></Set>} origina uma declaração
 * {@code logic Set<integer> S;} no bloco gerado:
 *
 * <pre>
 * axiomatic NomeMaquina_sets {
 *     logic Set&lt;integer&gt; BOOK;
 *     logic Set&lt;integer&gt; COPY;
 * }
 * </pre>
 *
 * <p>O bloco deve ser posicionado imediatamente após os {@code include}s do ficheiro {@code .acsl},
 * antes de qualquer outra axiomática, para que os nomes sejam visíveis em todas as declarações
 * subsequentes.
 */
public final class BxmlSetsTranslator {

    private BxmlSetsTranslator() {}

    /**
     * Gera o bloco {@code axiomatic NomeMaquina_sets { … }} a partir de {@code <Sets>}.
     *
     * @return texto ACSL do bloco, ou {@code ""} se a máquina não tiver {@code <Sets>} ou não
     *         contiver conjuntos deferidos
     */
    /**
     * Retorna os nomes B dos conjuntos que possuem {@code <Enumerated_Values>} (ex.: {@code "POSITION"}).
     * Usado para detectar parâmetros/variáveis C cujo tipo é um enum e que precisam de cast {@code (integer)}
     * nas anotações ACSL.
     */
    /**
     * Conjunto B com valores enumerados (ex. {@code PHASE = {ACQ, CTRL}} na máquina {@code Airlock}).
     */
    public record EnumeratedSetInfo(String machineName, String setName, List<String> valueNames) {

        /** {@code dummy_<machineName>__<setName>} (universo {@code dummy_ghost}). */
        public String dummySetLogicName() {
            return dummyEnumeratedSetName(machineName, setName);
        }

        /** {@code dummy_<machineName>__<valueName>}. */
        public String dummyValueLogicName(String valueName) {
            return dummyEnumeratedValueName(machineName, valueName);
        }
    }

    public static String dummyEnumeratedSetName(String machineName, String setName) {
        return "dummy_" + machineName + "__" + setName;
    }

    public static String dummyEnumeratedValueName(String machineName, String valueName) {
        return "dummy_" + machineName + "__" + valueName;
    }

    /**
     * Lista conjuntos com {@code <Enumerated_Values>} na ordem do BXML.
     */
    public static List<EnumeratedSetInfo> listEnumeratedSets(Element machineEl) {
        Element setsEl = firstChildElement(machineEl, "Sets");
        if (setsEl == null) return List.of();
        String machineName = machineEl.getAttribute("name");
        if (machineName == null || machineName.isBlank()) {
            return List.of();
        }
        machineName = machineName.trim();
        List<EnumeratedSetInfo> out = new ArrayList<>();
        NodeList ch = setsEl.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element setEl = (Element) n;
            if (!"Set".equals(setEl.getLocalName())) continue;
            Element enumEl = firstChildElement(setEl, "Enumerated_Values");
            if (enumEl == null) continue;
            String setName = null;
            NodeList setChildren = setEl.getChildNodes();
            for (int j = 0; j < setChildren.getLength(); j++) {
                Node sn = setChildren.item(j);
                if (sn.getNodeType() != Node.ELEMENT_NODE) continue;
                Element idEl = (Element) sn;
                if (!"Id".equals(idEl.getLocalName())) continue;
                String name = idEl.getAttribute("value");
                if (name != null && !name.isBlank()) {
                    setName = name.trim();
                    break;
                }
            }
            if (setName == null) continue;
            List<String> values = new ArrayList<>();
            NodeList enumChildren = enumEl.getChildNodes();
            for (int j = 0; j < enumChildren.getLength(); j++) {
                Node en = enumChildren.item(j);
                if (en.getNodeType() != Node.ELEMENT_NODE) continue;
                Element ev = (Element) en;
                if (!"Id".equals(ev.getLocalName())) continue;
                String val = ev.getAttribute("value");
                if (val != null && !val.isBlank()) {
                    values.add(val.trim());
                }
            }
            if (!values.isEmpty()) {
                out.add(new EnumeratedSetInfo(machineName, setName, values));
            }
        }
        return out;
    }

    public static Set<String> buildEnumeratedSetNames(Element machineEl) {
        Element setsEl = firstChildElement(machineEl, "Sets");
        if (setsEl == null) return Set.of();
        Set<String> names = new LinkedHashSet<>();
        NodeList ch = setsEl.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element setEl = (Element) n;
            if (!"Set".equals(setEl.getLocalName())) continue;
            if (firstChildElement(setEl, "Enumerated_Values") == null) continue;
            NodeList setChildren = setEl.getChildNodes();
            for (int j = 0; j < setChildren.getLength(); j++) {
                Node sn = setChildren.item(j);
                if (sn.getNodeType() != Node.ELEMENT_NODE) continue;
                Element idEl = (Element) sn;
                if (!"Id".equals(idEl.getLocalName())) continue;
                String name = idEl.getAttribute("value");
                if (name != null && !name.isBlank()) {
                    names.add(name.trim());
                    break;
                }
            }
        }
        return names;
    }

    /**
     * Constrói o mapa B-name → ACSL-name para os valores enumerados de todos os conjuntos de uma
     * máquina. Ex.: {@code "normal" → "switch__normal"}.
     *
     * @return mapa (ordem de inserção preservada), vazio se não houver conjuntos enumerados
     */
    /** Nomes em {@code <Sees>/<Referenced_Machine>/<Name>} (máquinas vistas). */
    public static List<String> listReferencedMachineNames(Element machineEl) {
        Element sees = firstChildElement(machineEl, "Sees");
        if (sees == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        NodeList ch = sees.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element e = (Element) n;
            if (!"Referenced_Machine".equals(e.getLocalName())) {
                continue;
            }
            Element nameEl = firstChildElement(e, "Name");
            if (nameEl == null) {
                continue;
            }
            String name = nameEl.getTextContent();
            if (name != null && !name.isBlank()) {
                names.add(name.trim());
            }
        }
        return names;
    }

    /**
     * Conjuntos enumerados da máquina e das máquinas em {@code SEES} (mesma pasta de {@code .bxml}).
     */
    public static List<EnumeratedSetInfo> listEnumeratedSetsWithSees(
            Element machineEl, Path bxmlDirectory) {
        List<EnumeratedSetInfo> merged = new ArrayList<>(listEnumeratedSets(machineEl));
        if (bxmlDirectory == null || !Files.isDirectory(bxmlDirectory)) {
            return merged;
        }
        for (String seen : listReferencedMachineNames(machineEl)) {
            Path p = bxmlDirectory.resolve(seen + ".bxml");
            if (!Files.isRegularFile(p)) {
                continue;
            }
            try {
                merged.addAll(listEnumeratedSets(parseMachineElement(p)));
            } catch (Exception ignored) {
                // ignora SEES inacessível
            }
        }
        return merged;
    }

    /**
     * Renomeação de valores enumerados da máquina e das máquinas em {@code SEES} (ficheiros
     * {@code Nome.bxml} no mesmo diretório que a abstrata).
     */
    public static Map<String, String> buildEnumRenamesWithSees(
            Element machineEl, Path bxmlDirectory) {
        LinkedHashMap<String, String> merged =
                new LinkedHashMap<>(buildEnumRenames(machineEl));
        if (bxmlDirectory == null || !Files.isDirectory(bxmlDirectory)) {
            return merged;
        }
        for (String seen : listReferencedMachineNames(machineEl)) {
            Path p = bxmlDirectory.resolve(seen + ".bxml");
            if (!Files.isRegularFile(p)) {
                continue;
            }
            try {
                merged.putAll(buildEnumRenames(parseMachineElement(p)));
            } catch (Exception ignored) {
                // ignora SEES inacessível
            }
        }
        return merged;
    }

    /**
     * Linhas {@code include "MaquinaVista.acsl";} para cada máquina em {@code <Sees>} que gera
     * ficheiro próprio (abstração / componente sem {@code <Abstraction>}).
     */
    public static String formatSeesIncludeBlock(Element machineEl, Path bxmlDirectory) {
        List<String> seenNames = listReferencedMachineNames(machineEl);
        if (seenNames.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String seen : seenNames) {
            if (bxmlDirectory == null) {
                sb.append("include \"").append(seen).append(".acsl\";\n");
                continue;
            }
            Path bxml = bxmlDirectory.resolve(seen + ".bxml");
            if (!Files.isRegularFile(bxml)) {
                continue;
            }
            try {
                Element seenEl = parseMachineElement(bxml);
                if (!machineGeneratesOwnAcslFile(seenEl)) {
                    continue;
                }
            } catch (Exception ignored) {
                continue;
            }
            sb.append("include \"").append(seen).append(".acsl\";\n");
        }
        if (sb.isEmpty()) {
            return "";
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * Texto das máquinas em {@code SEES} para deteção de includes da {@code B2ACSLLib} na máquina que
     * vê (corpo dos {@code .acsl} já gerados, sem repetir o preâmbulo de {@code include}).
     */
    public static String collectSeesMachinesTextForIncludeScan(
            Element viewerMachineEl, Path bxmlDirectory, Path acslDirectory) {
        StringBuilder sb = new StringBuilder();
        for (String seen : listReferencedMachineNames(viewerMachineEl)) {
            if (acslDirectory != null) {
                Path acsl = acslDirectory.resolve(seen + ".acsl");
                if (Files.isRegularFile(acsl)) {
                    try {
                        sb.append(
                                AcslLibIncludes.acslBodyAfterPreambleIncludes(
                                        Files.readString(acsl)));
                        sb.append('\n');
                    } catch (Exception ignored) {
                        // ignora
                    }
                    continue;
                }
            }
            if (bxmlDirectory != null) {
                Path bxml = bxmlDirectory.resolve(seen + ".bxml");
                if (Files.isRegularFile(bxml)) {
                    try {
                        sb.append(
                                collectBxmlTextForIncludeScan(parseMachineElement(bxml)));
                        sb.append('\n');
                    } catch (Exception ignored) {
                        // ignora
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Includes da {@code B2ACSLLib} já emitidos nos preâmbulos dos {@code .acsl} das máquinas vistas
     * (caminhos relativos, para fusão sem duplicar na máquina que vê).
     */
    public static List<String> collectLibIncludePathsFromSeenMachines(
            Element viewerMachineEl, Path bxmlDirectory, Path acslDirectory) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (String seen : listReferencedMachineNames(viewerMachineEl)) {
            if (acslDirectory != null) {
                Path acsl = acslDirectory.resolve(seen + ".acsl");
                if (Files.isRegularFile(acsl)) {
                    try {
                        merged.addAll(
                                AcslLibIncludes.parseLibIncludeRelativePathsFromPreamble(
                                        Files.readString(acsl)));
                    } catch (Exception ignored) {
                        // ignora
                    }
                }
            }
        }
        return List.copyOf(merged);
    }

    /** Fragmento BXML traduzido só para varredura de símbolos da lib (conjuntos + invariantes). */
    private static String collectBxmlTextForIncludeScan(Element machineEl) throws Exception {
        String machineName = machineEl.getAttribute("name");
        if (machineName == null || machineName.isBlank()) {
            return "";
        }
        com.example.bxml.BxmlTranslateContext ctx =
                com.example.bxml.BxmlTranslateContext.forMachine(machineEl);
        StringBuilder sb = new StringBuilder();
        String sets = formatSetsBlock(machineEl);
        if (!sets.isBlank()) {
            sb.append(sets).append('\n');
        }
        for (org.w3c.dom.Element inv :
                com.example.bxml.BxmlInvariantTranslator.listDirectInvariants(machineEl)) {
            String body =
                    com.example.bxml.BxmlPredicateToAcsl.translateInvariantContent(inv, ctx);
            if (body != null && !body.isBlank()) {
                sb.append(body).append('\n');
            }
        }
        return sb.toString();
    }

    /** {@code false} para refinamento/implementação com {@code <Abstraction>} preenchido. */
    private static boolean machineGeneratesOwnAcslFile(Element machineEl) {
        Element abs = firstChildElement(machineEl, "Abstraction");
        if (abs == null) {
            return true;
        }
        String t = abs.getTextContent();
        return t == null || t.isBlank();
    }

    /** Raiz {@code <Machine>} com parser namespace-aware (BXML 1.0 com {@code xmlns}). */
    static Element parseMachineElement(Path bxmlPath) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
            // opcional
        }
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(bxmlPath.toFile());
        Element root = doc.getDocumentElement();
        root.normalize();
        return root;
    }

    public static Map<String, String> buildEnumRenames(Element machineEl) {
        Element setsEl = firstChildElement(machineEl, "Sets");
        if (setsEl == null) return Map.of();
        String machineName = machineEl.getAttribute("name");
        if (machineName == null || machineName.isBlank()) return Map.of();

        LinkedHashMap<String, String> renames = new LinkedHashMap<>();
        NodeList ch = setsEl.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element setEl = (Element) n;
            if (!"Set".equals(setEl.getLocalName())) continue;
            Element enumEl = firstChildElement(setEl, "Enumerated_Values");
            if (enumEl == null) continue;
            NodeList enumChildren = enumEl.getChildNodes();
            for (int j = 0; j < enumChildren.getLength(); j++) {
                Node en = enumChildren.item(j);
                if (en.getNodeType() != Node.ELEMENT_NODE) continue;
                Element ev = (Element) en;
                if (!"Id".equals(ev.getLocalName())) continue;
                String val = ev.getAttribute("value");
                if (val != null && !val.isBlank()) {
                    String bName = val.trim();
                    renames.put(bName, machineName + "__" + bName);
                }
            }
        }
        return renames;
    }

    public static String formatSetsBlock(Element machineEl) {
        Element setsEl = firstChildElement(machineEl, "Sets");
        if (setsEl == null) return "";

        String machineName = machineEl.getAttribute("name");
        if (machineName == null || machineName.isBlank()) return "";

        List<String> logics = new ArrayList<>();
        List<String> axioms = new ArrayList<>();

        NodeList ch = setsEl.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element setEl = (Element) n;
            if (!"Set".equals(setEl.getLocalName())) continue;

            // Nome do conjunto (primeiro <Id> filho direto de <Set>)
            String setName = null;
            NodeList setChildren = setEl.getChildNodes();
            for (int j = 0; j < setChildren.getLength(); j++) {
                Node sn = setChildren.item(j);
                if (sn.getNodeType() != Node.ELEMENT_NODE) continue;
                Element idEl = (Element) sn;
                if (!"Id".equals(idEl.getLocalName())) continue;
                String name = idEl.getAttribute("value");
                if (name != null && !name.isBlank()) {
                    setName = name.trim();
                    logics.add("    logic Set<integer> " + setName + ";");
                    break;
                }
            }

            // Valores enumerados → logic integer machineName__val + um único axioma combinado
            if (setName != null) {
                Element enumEl = firstChildElement(setEl, "Enumerated_Values");
                if (enumEl != null) {
                    List<String> prefixedVals = new ArrayList<>();
                    NodeList enumChildren = enumEl.getChildNodes();
                    for (int j = 0; j < enumChildren.getLength(); j++) {
                        Node en = enumChildren.item(j);
                        if (en.getNodeType() != Node.ELEMENT_NODE) continue;
                        Element ev = (Element) en;
                        if (!"Id".equals(ev.getLocalName())) continue;
                        String val = ev.getAttribute("value");
                        if (val == null || val.isBlank()) continue;
                        String prefixed = machineName + "__" + val.trim();
                        prefixedVals.add(prefixed);
                    }
                    if (!prefixedVals.isEmpty()) {
                        // axiom SETNAME_values: belongs(v1, S) && ... && \forall integer x; belongs(x, S) ==> (x==v1 || ...)
                        StringBuilder ax = new StringBuilder();
                        ax.append("    axiom ").append(setName).append("_values:\n");
                        for (String pv : prefixedVals) {
                            ax.append("        belongs(").append(pv).append(", ").append(setName).append(")\n");
                            ax.append("        &&\n");
                        }
                        ax.append("        \\forall integer x;\n");
                        ax.append("            belongs(x, ").append(setName).append(") ==>\n");
                        ax.append("            (");
                        for (int k = 0; k < prefixedVals.size(); k++) {
                            if (k > 0) ax.append(" || ");
                            ax.append("x == ").append(prefixedVals.get(k));
                        }
                        ax.append(");");
                        axioms.add(ax.toString());
                    }
                }
            }
        }
        if (logics.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("axiomatic ").append(machineName).append("_sets {\n");
        for (String line : logics) sb.append(line).append("\n");
        if (!axioms.isEmpty()) {
            sb.append("\n");
            for (String line : axioms) sb.append(line).append("\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static Element firstChildElement(Element parent, String localName) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (localName.equals(e.getLocalName())) return e;
        }
        return null;
    }
}
