package com.example.bxml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     * Conjunctos de topo de todos os blocos {@code <Invariant>} da máquina, em ordem — achata tanto
     * vários blocos {@code <Invariant>} irmãos quanto, dentro de cada um, uma conjunção B de topo
     * ({@code P & Q & R}, ver {@link BxmlPredicateToAcsl#translateInvariantConjuncts}) — cada
     * conjuncto vira o seu próprio predicado nomeado em {@link #formatInvariantPredicates}/{@link
     * #listInvariantPredicateNames}, em vez de um só predicado com corpo conjuntivo.
     */
    private static List<String> allInvariantConjuncts(Element machineEl, BxmlTranslateContext ctx) {
        List<String> conjuncts = new ArrayList<>();
        for (Element inv : listDirectInvariants(machineEl)) {
            conjuncts.addAll(BxmlPredicateToAcsl.translateInvariantConjuncts(inv, ctx));
        }
        return conjuncts;
    }

    /**
     * Nomes ACSL dos predicados de invariante (mesma ordem que em {@link #formatInvariantPredicates}).
     */
    public static List<String> listInvariantPredicateNames(Element machineEl, BxmlTranslateContext ctx) {
        String machineName = machineEl.getAttribute("name");
        List<String> conjuncts = allInvariantConjuncts(machineEl, ctx);
        List<String> names = new ArrayList<>();
        if (conjuncts.isEmpty()) return names;
        if (conjuncts.size() == 1) {
            names.add(machineName + "_invariant");
            return names;
        }
        for (int i = 0; i < conjuncts.size(); i++) {
            names.add(machineName + "_invariant_" + (i + 1));
        }
        return names;
    }

    /**
     * Texto ACSL: um ou mais {@code predicate Nome = expr;} (vazio se não houver invariante traduzível).
     */
    public static String formatInvariantPredicates(Element machineEl, BxmlTranslateContext ctx) {
        String machineName = machineEl.getAttribute("name");
        List<String> conjuncts = allInvariantConjuncts(machineEl, ctx);
        if (conjuncts.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        if (conjuncts.size() == 1) {
            sb.append("predicate ").append(machineName).append("_invariant =\n");
            sb.append("    ").append(conjuncts.get(0)).append(";\n");
            return sb.toString();
        }
        for (int i = 0; i < conjuncts.size(); i++) {
            sb.append("predicate ").append(machineName).append("_invariant_").append(i + 1).append(" =\n");
            sb.append("    ").append(conjuncts.get(i)).append(";\n");
            sb.append("\n");
        }
        return sb.toString().replaceAll("\\n+$", "\n");
    }

    /**
     * Coleta os nomes dos predicados de invariante de todas as máquinas importadas
     * (abstrata + implementação), para adicionar como {@code requires} nas operações
     * da máquina que as importa.
     */
    public static List<String> listImportedMachineInvariantPredicateNames(
            List<String> importedMachineNames, Path bxmlDirectory) {
        if (importedMachineNames == null || importedMachineNames.isEmpty() || bxmlDirectory == null) {
            return List.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String machineName : importedMachineNames) {
            result.addAll(loadInvariantPredicateNamesForMachine(machineName, bxmlDirectory));
        }
        return List.copyOf(result);
    }

    private static List<String> loadInvariantPredicateNamesForMachine(
            String machineName, Path bxmlDirectory) {
        List<String> result = new ArrayList<>();

        Path abstractPath = bxmlDirectory.resolve(machineName + ".bxml");
        if (Files.exists(abstractPath)) {
            try {
                Element abstractEl = BxmlSetsTranslator.parseMachineElement(abstractPath);
                BxmlTranslateContext ctx = BxmlTranslateContext.forMachine(abstractEl, Map.of());
                result.addAll(listInvariantPredicateNames(abstractEl, ctx));
            } catch (Exception ignored) {}
        }

        Element implEl = BxmlSetsTranslator.findImplementationMachineElement(machineName, bxmlDirectory);
        if (implEl != null) {
            try {
                BxmlTranslateContext ctx = BxmlTranslateContext.forMachine(implEl, Map.of());
                result.addAll(listInvariantPredicateNames(implEl, ctx));
            } catch (Exception ignored) {}
        }

        return result;
    }
}
