package com.example.bxml;

import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Traduz {@code <Invariant>} (BXML 1.0) para predicados ACSL nomeados
 * {@code <Machine>_invariant} (ou {@code _invariant_1}, … se vários blocos) e fornece os nomes
 * para uso em {@code requires}/{@code ensures} das operações.
 */
public final class BxmlInvariantTranslator {

    private BxmlInvariantTranslator() {}

    /** Filhos diretos {@code <Invariant>} da máquina, na ordem do documento. */
    public static List<Element> listDirectInvariants(Element machineEl) {
        List<Element> out = new ArrayList<>();
        NodeList ch = machineEl.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Invariant".equals(e.getLocalName())) out.add(e);
        }
        return out;
    }

    /**
     * Nomes ACSL dos predicados de invariante (mesma ordem que em {@link #formatInvariantPredicates}).
     */
    public static List<String> listInvariantPredicateNames(Element machineEl, BxmlTranslateContext ctx) {
        String machineName = machineEl.getAttribute("name");
        List<Element> invs = listDirectInvariants(machineEl);
        List<String> names = new ArrayList<>();
        if (invs.isEmpty()) return names;

        if (invs.size() == 1) {
            String body = BxmlPredicateToAcsl.translateInvariantContent(invs.get(0), ctx);
            if (!body.isBlank()) names.add(machineName + "_invariant");
            return names;
        }
        for (int i = 0; i < invs.size(); i++) {
            String body = BxmlPredicateToAcsl.translateInvariantContent(invs.get(i), ctx);
            if (!body.isBlank()) names.add(machineName + "_invariant_" + (i + 1));
        }
        return names;
    }

    /**
     * Texto ACSL: um ou mais {@code predicate Nome = expr;} (vazio se não houver invariante traduzível).
     */
    public static String formatInvariantPredicates(Element machineEl, BxmlTranslateContext ctx) {
        String machineName = machineEl.getAttribute("name");
        List<Element> invs = listDirectInvariants(machineEl);
        if (invs.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        if (invs.size() == 1) {
            String body = BxmlPredicateToAcsl.translateInvariantContent(invs.get(0), ctx);
            if (body.isBlank()) return "";
            sb.append("predicate ").append(machineName).append("_invariant =\n");
            sb.append("    ").append(body).append(";\n");
            return sb.toString();
        }
        for (int i = 0; i < invs.size(); i++) {
            String body = BxmlPredicateToAcsl.translateInvariantContent(invs.get(i), ctx);
            if (body.isBlank()) continue;
            sb.append("predicate ").append(machineName).append("_invariant_").append(i + 1).append(" =\n");
            sb.append("    ").append(body).append(";\n");
            sb.append("\n");
        }
        return sb.toString().replaceAll("\\n+$", "\n");
    }
}
