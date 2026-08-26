package com.example.bxml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Carrega dados de OUTRAS máquinas {@code .bxml} do projeto (não a que está a ser traduzida):
 * assigns concretos, operações com {@code Becomes_In}/{@code __fc_random_counter} propagado
 * transitivamente. Extraído de {@code BxmlMachineVariables} (WMC=588) por extract-class puro:
 * nenhuma linha de lógica mudou, só o arquivo em que vive.
 */
final class CrossMachineBxmlLoader {

    private CrossMachineBxmlLoader() {}

    /**
     * Operações da máquina cujo corpo de implementação chama alguma operação de máquina importada
     * que usa {@code Becomes_In} (ex.: {@code measure_level} com {@code v :: S}).
     * Permite propagar {@code __fc_random_counter} para chamadores de uma máquina intermediária.
     */
    static Set<String> loadOperationNamesCallingRandOps(
            String machineName, Path bxmlDirectory) {
        // Carregar implementação
        Element implEl = BxmlSetsTranslator.findImplementationMachineElement(machineName, bxmlDirectory);
        if (implEl == null) return Set.of();

        // Operações rand das máquinas importadas pela implementação, IMPORTS transitivo: se X importa
        // Y e só Y (não X) tem Becomes_In direto, uma operação de X que só chama Y (sem Becomes_In
        // próprio) precisa entrar em randOpNames também — mesmo padrão recursivo com "visited" (contra
        // ciclos) já usado por loadConcreteAssignsForImportedMachine, nesta mesma classe.
        Set<String> randOpNames = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        for (String imported : BxmlSetsTranslator.listImportedMachineNames(implEl)) {
            collectRandOpNamesTransitively(imported, bxmlDirectory, randOpNames, visited);
        }
        if (randOpNames.isEmpty()) return Set.of();

        // Para cada operação da máquina abstrata, checar se a implementação chama alguma rand op
        Path abstractPath = bxmlDirectory.resolve(machineName + ".bxml");
        if (!Files.exists(abstractPath)) return Set.of();
        try {
            Element abstractEl = BxmlSetsTranslator.parseMachineElement(abstractPath);
            Set<String> result = new LinkedHashSet<>();
            NodeList ops = abstractEl.getElementsByTagNameNS("*", "Operations");
            if (ops.getLength() == 0) return Set.of();
            Element operationsEl = (Element) ops.item(0);
            NodeList children = operationsEl.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node n = children.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element op = (Element) n;
                if (!"Operation".equals(op.getLocalName())) continue;
                String opName = op.getAttribute("name");
                Element implOp = BxmlLoopTranslator.findImplementationOperation(
                        List.of(implEl), opName);
                if (implOp == null) continue;
                Element implBody = BxmlDomUtils.firstChildElement(implOp, "Body");
                if (implBody != null && elementCallsAnyOf(implBody, randOpNames)) {
                    result.add(opName);
                }
            }
            return result;
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    /**
     * Acrescenta a {@code randOpNames} as operações com {@code Becomes_In} de {@code machineName} e
     * recursa nos IMPORTS da SUA PRÓPRIA implementação — {@code visited} evita repetir/ciclar (B não
     * permite ciclos de IMPORTS, mas não custa proteger).
     */
    private static void collectRandOpNamesTransitively(
            String machineName, Path bxmlDirectory, Set<String> randOpNames, Set<String> visited) {
        if (machineName == null || machineName.isBlank() || !visited.add(machineName.trim())) {
            return;
        }
        randOpNames.addAll(loadOperationNamesWithBecomesIn(machineName, bxmlDirectory));
        Element importedImplEl =
                BxmlSetsTranslator.findImplementationMachineElement(machineName, bxmlDirectory);
        if (importedImplEl == null) {
            return;
        }
        for (String next : BxmlSetsTranslator.listImportedMachineNames(importedImplEl)) {
            collectRandOpNamesTransitively(next, bxmlDirectory, randOpNames, visited);
        }
    }

    private static boolean elementCallsAnyOf(Element el, Set<String> opNames) {
        if (el == null) return false;
        if ("Operation_Call".equals(el.getLocalName())) {
            Element nameEl = BxmlDomUtils.firstChildElement(el, "Name");
            if (nameEl != null) {
                Element idEl = BxmlDomUtils.firstChildElement(nameEl, "Id");
                if (idEl != null) {
                    String v = idEl.getAttribute("value");
                    if (v != null && opNames.contains(v.trim())) return true;
                }
            }
        }
        NodeList nl = el.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            if (elementCallsAnyOf((Element) n, opNames)) return true;
        }
        return false;
    }

    /** Nomes das operações da máquina abstrata cujo corpo contém {@code Becomes_In} ({@code v :: S}). */
    static Set<String> loadOperationNamesWithBecomesIn(String machineName, Path bxmlDirectory) {
        Path path = bxmlDirectory.resolve(machineName + ".bxml");
        if (!Files.exists(path)) return Set.of();
        try {
            Element machineEl = BxmlSetsTranslator.parseMachineElement(path);
            Set<String> result = new LinkedHashSet<>();
            NodeList ops = machineEl.getElementsByTagNameNS("*", "Operations");
            if (ops.getLength() == 0) return Set.of();
            Element operationsEl = (Element) ops.item(0);
            NodeList children = operationsEl.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node n = children.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element op = (Element) n;
                if (!"Operation".equals(op.getLocalName())) continue;
                String opName = op.getAttribute("name");
                Element body = BxmlDomUtils.firstChildElement(op, "Body");
                if (body != null && elementContainsBecomesIn(body)) {
                    result.add(opName);
                }
            }
            return result;
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private static boolean elementContainsBecomesIn(Element el) {
        if (el == null) return false;
        if ("Becomes_In".equals(el.getLocalName())) return true;
        NodeList nl = el.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            if (elementContainsBecomesIn((Element) n)) return true;
        }
        return false;
    }

    static List<String> loadConcreteAssignsForImportedMachine(String machineName, Path bxmlDirectory) {
        return loadConcreteAssignsForImportedMachine(machineName, bxmlDirectory, new LinkedHashSet<>());
    }

    /**
     * @param visited nomes de máquina já expandidos nesta chamada (evita recursão infinita se o
     *        grafo de IMPORTS tiver um ciclo, o que B não permite mas não custa proteger contra).
     *        IMPORTS é transitivo: os alvos de {@code machineName} incluem também os de tudo que
     *        {@code machineName} importa (ex.: {@code array} importa {@code iter_services} — quem
     *        chama {@code array__set_array_value} de fora precisa do assigns de ambas).
     */
    private static List<String> loadConcreteAssignsForImportedMachine(
            String machineName, Path bxmlDirectory, Set<String> visited) {
        if (machineName == null || machineName.isBlank() || !visited.add(machineName.trim())) {
            return List.of();
        }
        Element abstractEl = null;
        Path abstractPath = bxmlDirectory.resolve(machineName + ".bxml");
        if (Files.exists(abstractPath)) {
            try { abstractEl = BxmlSetsTranslator.parseMachineElement(abstractPath); } catch (Exception ignored) {}
        }

        Element implEl = BxmlSetsTranslator.findImplementationMachineElement(machineName, bxmlDirectory);

        if (implEl == null) return List.of();

        // ctx da PRÓPRIA máquina importada (não null): implementationAssignTargetWithRange precisa
        // dele para resolver o domínio da variável concreta (array/função total) e gerar
        // "Raiz__v[..]" — com ctx null, cai sempre no alvo sem colchetes ("Raiz__v"), que o
        // Frama-C rejeita como "not an assignable left value" quando v é, de facto, um array.
        BxmlTranslateContext implCtx = BxmlTranslateContext.forMachine(implEl);
        List<String> result =
                new ArrayList<>(
                        ConcreteAssignTargetResolver.listImplementationAssignTargets(
                                machineName, List.of(implEl), implCtx, bxmlDirectory));

        // Implementação sem Concrete_Variables próprias (usa a variável abstrata diretamente, ex.
        // "array"/"array_i" — mesmo caso de anyImplementationUsesAbstractVariablesOnly): os alvos
        // vêm da declaração da ABSTRATA, e a fatia [low..high] (quando a variável é array/função
        // total) também é resolvida pelo invariante da ABSTRATA — a implementação não tem
        // <Invariant> ao nível da máquina (o único <Invariant> em array_i.bxml, p.ex., é o de um
        // loop dentro de <Operations>, não filho direto de <Machine>: firstChildElement não o acha).
        // SÓ se aplica quando a variável é mesmo array/C-backed: se a máquina precisa de ghost
        // abstraction (ex.: "contents" de Fifo, \list-valued sem storage C nenhum), "sem
        // Concrete_Variables" significa GHOST, não "reusa a abstrata como C global" — gerar
        // "Fifo__contents" aqui produz um alvo de assigns inexistente ("unbound logic variable" no
        // Frama-C); o alvo correto (bloco abaixo) já é "ghost_contents".
        if (result.isEmpty()
                && abstractEl != null
                && !BxmlMachineVariables.needsGhostAbstraction(abstractEl, List.of(implEl))) {
            for (String v : BxmlMachineVariables.declaredVariableNames(abstractEl)) {
                String base = machineName + "__" + v;
                String ranged =
                        ConcreteAssignTargetResolver.implementationAssignTargetWithRange(
                                base, v, abstractEl, implCtx, bxmlDirectory);
                result.add(ranged == null ? base : ranged);
            }
        }

        if (abstractEl != null && BxmlMachineVariables.needsGhostAbstraction(abstractEl, List.of(implEl))) {
            for (String v : GhostOperationsCiGenerator.listAbstractVariableNames(abstractEl)) {
                result.add("ghost_" + v);
            }
        }

        for (String transitiveImport : BxmlSetsTranslator.listImportedMachineNames(implEl)) {
            result.addAll(
                    loadConcreteAssignsForImportedMachine(transitiveImport, bxmlDirectory, visited));
        }

        return result;
    }

    static List<String> loadOperationNamesForMachine(String machineName, Path bxmlDirectory) {
        Path path = bxmlDirectory.resolve(machineName + ".bxml");
        if (!Files.exists(path)) return List.of();
        try {
            Element machineEl = BxmlSetsTranslator.parseMachineElement(path);
            List<String> names = new ArrayList<>();
            NodeList ops = machineEl.getElementsByTagNameNS("*", "Operations");
            if (ops.getLength() == 0) return List.of();
            Element operationsEl = (Element) ops.item(0);
            NodeList children = operationsEl.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node n = children.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element ch = (Element) n;
                if ("Operation".equals(ch.getLocalName())) {
                    String name = ch.getAttribute("name");
                    if (name != null && !name.isBlank()) names.add(name.trim());
                }
            }
            return names;
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
