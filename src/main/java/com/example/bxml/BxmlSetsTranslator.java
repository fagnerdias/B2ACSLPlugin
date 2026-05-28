package com.example.bxml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
