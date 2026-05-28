package com.example.bxml;

import java.util.ArrayList;
import java.util.List;

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
    public static String formatSetsBlock(Element machineEl) {
        Element setsEl = firstChildElement(machineEl, "Sets");
        if (setsEl == null) return "";

        String machineName = machineEl.getAttribute("name");
        if (machineName == null || machineName.isBlank()) return "";

        List<String> decls = new ArrayList<>();
        NodeList ch = setsEl.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element setEl = (Element) n;
            if (!"Set".equals(setEl.getLocalName())) continue;
            NodeList setChildren = setEl.getChildNodes();
            for (int j = 0; j < setChildren.getLength(); j++) {
                Node sn = setChildren.item(j);
                if (sn.getNodeType() != Node.ELEMENT_NODE) continue;
                Element idEl = (Element) sn;
                if (!"Id".equals(idEl.getLocalName())) continue;
                String name = idEl.getAttribute("value");
                if (name != null && !name.isBlank()) {
                    decls.add("    logic Set<integer> " + name.trim() + ";");
                }
            }
        }
        if (decls.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("axiomatic ").append(machineName).append("_sets {\n");
        for (String line : decls) {
            sb.append(line).append("\n");
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
