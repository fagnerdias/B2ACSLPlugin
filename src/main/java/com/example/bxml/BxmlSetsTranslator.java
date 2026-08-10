package com.example.bxml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

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
     * Nome ACSL do conjunto enumerado, ex. {@code ctx_ALARM_STATUS}. Usa {@code _} (não {@code __}) para
     * não colidir com tipos enum C gerados pelo B ({@code ctx__ALARM_STATUS}).
     */
    public static String enumeratedSetAcslName(String machineName, String setName) {
        if (machineName == null || machineName.isBlank()) {
            return setName;
        }
        return machineName.trim() + "_" + setName;
    }

    /**
     * Lista conjuntos com {@code <Enumerated_Values>} na ordem do BXML.
     */
    public static List<EnumeratedSetInfo> listEnumeratedSets(Element machineEl) {
        Element setsEl = BxmlDomUtils.firstChildElement(machineEl, "Sets");
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
            Element enumEl = BxmlDomUtils.firstChildElement(setEl, "Enumerated_Values");
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
        Element setsEl = BxmlDomUtils.firstChildElement(machineEl, "Sets");
        if (setsEl == null) return Set.of();
        Set<String> names = new LinkedHashSet<>();
        NodeList ch = setsEl.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element setEl = (Element) n;
            if (!"Set".equals(setEl.getLocalName())) continue;
            if (BxmlDomUtils.firstChildElement(setEl, "Enumerated_Values") == null) continue;
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
     * Nomes de TODOS os conjuntos declarados em {@code <Sets>} da máquina (deferred E enumerados) —
     * ao contrário de {@link #buildEnumeratedSetNames}, que só cobre os enumerados. Usado por
     * {@link BxmlTranslateContext} para excluir nomes de conjunto (ex.: {@code COPY}, um deferred
     * set) da deteção de "variável livre" de uma lambda B {@code %} — sem isto, {@code %cc.(cc :
     * COPY | …)} trata {@code COPY} (o próprio conjunto do domínio) como um parâmetro extra da
     * função lógica gerada, quando é apenas um identificador de conjunto fixo, resolvível
     * diretamente em ACSL ({@code Biblioteca_COPY}), nunca um valor que precise de ser passado.
     */
    public static Set<String> declaredSetNames(Element machineEl) {
        Element setsEl = BxmlDomUtils.firstChildElement(machineEl, "Sets");
        if (setsEl == null) return Set.of();
        Set<String> names = new LinkedHashSet<>();
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
        return listReferencedMachineNamesFromClause(machineEl, "Sees");
    }

    /** Nomes em {@code <Imports>/<Referenced_Machine>/<Name>} (máquinas importadas). */
    public static List<String> listImportedMachineNames(Element machineEl) {
        return listReferencedMachineNamesFromClause(machineEl, "Imports");
    }

    /**
     * {@code IMPORTS} da máquina abstrata e da cadeia fundida (implementações/refinamentos), sem
     * duplicar nomes.
     */
    public static List<String> listImportedMachineNamesFromChain(
            Element rootMachineEl, List<Element> mergedMachineElements) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.addAll(listImportedMachineNames(rootMachineEl));
        if (mergedMachineElements != null) {
            for (Element mel : mergedMachineElements) {
                names.addAll(listImportedMachineNames(mel));
            }
        }
        return List.copyOf(names);
    }

    /**
     * Como {@link #listImportedMachineNamesFromChain(Element, List)}, mas fecha transitivamente
     * {@code IMPORTS} (só imports, não {@code SEES}): se {@code entry_point IMPORTS array} e
     * {@code array IMPORTS iter_services}, {@code iter_services} entra no resultado mesmo sem
     * {@code entry_point} importá-la diretamente — B trata IMPORTS como uma cadeia de
     * inicialização/estado que se propaga (a máquina importadora herda a necessidade de respeitar
     * o invariante e o {@code assigns} de tudo o que a sua importada, por sua vez, importa).
     *
     * @param importsGraph grafo de IMPORTS do projeto inteiro ({@link BxmlImportsGraph}); se
     *        {@code null}, cai no comportamento de um nível de {@link
     *        #listImportedMachineNamesFromChain(Element, List)}.
     */
    public static List<String> listImportedMachineNamesTransitive(
            Element rootMachineEl, List<Element> mergedMachineElements, BxmlImportsGraph importsGraph) {
        List<String> direct = listImportedMachineNamesFromChain(rootMachineEl, mergedMachineElements);
        if (importsGraph == null) {
            return direct;
        }
        LinkedHashSet<String> reachable = new LinkedHashSet<>();
        ArrayDeque<String> frontier = new ArrayDeque<>(direct);
        while (!frontier.isEmpty()) {
            String current = frontier.poll();
            if (current == null || current.isBlank() || !reachable.add(current.trim())) {
                continue;
            }
            for (String next : importsGraph.importedBy(current.trim())) {
                if (next != null && !next.isBlank() && !reachable.contains(next.trim())) {
                    frontier.add(next.trim());
                }
            }
        }
        return List.copyOf(reachable);
    }

    /**
     * {@code SEES} da máquina abstrata e da cadeia fundida (implementações/refinamentos), sem
     * duplicar nomes.
     */
    public static List<String> listSeenMachineNamesFromChain(
            Element rootMachineEl, List<Element> mergedMachineElements) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.addAll(listReferencedMachineNames(rootMachineEl));
        if (mergedMachineElements != null) {
            for (Element mel : mergedMachineElements) {
                names.addAll(listReferencedMachineNames(mel));
            }
        }
        return List.copyOf(names);
    }

    /**
     * Como {@link #listSeenMachineNamesFromChain(Element, List)}, mas fecha transitivamente
     * {@code SEES}: se {@code Customer} vê {@code Price} e {@code Price} vê {@code Goods},
     * {@code Goods} entra no resultado mesmo sem {@code Customer} a ver diretamente — uma operação
     * que chama outra de uma máquina vista (ex. {@code Customer__buy} chama
     * {@code Price__pricequery}) precisa do {@code requires} do invariante dessa máquina vista
     * (senão a pré-condição da chamada fica sem hipótese alguma e o WP não consegue prová-la), tal
     * como {@link #listImportedMachineNamesTransitive} já fazia para {@code IMPORTS}.
     *
     * @param seesGraph grafo de SEES do projeto inteiro ({@link BxmlSeesGraph}); se {@code null},
     *        cai no comportamento de um nível de {@link #listSeenMachineNamesFromChain(Element, List)}.
     */
    public static List<String> listSeenMachineNamesTransitive(
            Element rootMachineEl, List<Element> mergedMachineElements, BxmlSeesGraph seesGraph) {
        List<String> direct = listSeenMachineNamesFromChain(rootMachineEl, mergedMachineElements);
        if (seesGraph == null) {
            return direct;
        }
        LinkedHashSet<String> reachable = new LinkedHashSet<>();
        ArrayDeque<String> frontier = new ArrayDeque<>(direct);
        while (!frontier.isEmpty()) {
            String current = frontier.poll();
            if (current == null || current.isBlank() || !reachable.add(current.trim())) {
                continue;
            }
            for (String next : seesGraph.seenBy(current.trim())) {
                if (next != null && !next.isBlank() && !reachable.contains(next.trim())) {
                    frontier.add(next.trim());
                }
            }
        }
        return List.copyOf(reachable);
    }

    private static List<String> listReferencedMachineNamesFromClause(
            Element machineEl, String clauseName) {
        Element clause = BxmlDomUtils.firstChildElement(machineEl, clauseName);
        if (clause == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        NodeList ch = clause.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element e = (Element) n;
            if (!"Referenced_Machine".equals(e.getLocalName())) {
                continue;
            }
            Element nameEl = BxmlDomUtils.firstChildElement(e, "Name");
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
     * Máquinas em {@code SEES} (abstrata) e {@code IMPORTS} (abstrata + cadeia fundida) cujos tipos
     * enumerados entram na tradução.
     */
    private static List<String> dependencyMachineNamesForTranslation(
            Element machineEl, List<Element> mergedMachineElements) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.addAll(listReferencedMachineNames(machineEl));
        names.addAll(listImportedMachineNamesFromChain(machineEl, mergedMachineElements));
        return List.copyOf(names);
    }

    /**
     * Conjuntos enumerados da máquina e das máquinas em {@code SEES} (mesma pasta de {@code .bxml}).
     */
    public static List<EnumeratedSetInfo> listEnumeratedSetsWithSees(
            Element machineEl, Path bxmlDirectory) {
        return listEnumeratedSetsWithSees(machineEl, List.of(), bxmlDirectory);
    }

    /**
     * Conjuntos enumerados da máquina, {@code SEES} e {@code IMPORTS} (cadeia fundida).
     */
    public static List<EnumeratedSetInfo> listEnumeratedSetsWithSees(
            Element machineEl, List<Element> mergedMachineElements, Path bxmlDirectory) {
        List<EnumeratedSetInfo> merged = new ArrayList<>(listEnumeratedSets(machineEl));
        if (bxmlDirectory == null || !Files.isDirectory(bxmlDirectory)) {
            return merged;
        }
        for (String dep : dependencyMachineNamesForTranslation(machineEl, mergedMachineElements)) {
            Path p = bxmlDirectory.resolve(dep + ".bxml");
            if (!Files.isRegularFile(p)) {
                continue;
            }
            try {
                merged.addAll(listEnumeratedSets(parseMachineElement(p)));
            } catch (Exception ignored) {
                // ignora dependência inacessível
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
        return buildEnumRenamesWithSees(machineEl, List.of(), bxmlDirectory);
    }

    /**
     * Renomeação de valores enumerados da máquina, {@code SEES} e {@code IMPORTS} (cadeia fundida).
     */
    public static Map<String, String> buildEnumRenamesWithSees(
            Element machineEl, List<Element> mergedMachineElements, Path bxmlDirectory) {
        LinkedHashMap<String, String> merged =
                new LinkedHashMap<>(buildEnumRenames(machineEl));
        if (bxmlDirectory == null || !Files.isDirectory(bxmlDirectory)) {
            return merged;
        }
        for (String dep : dependencyMachineNamesForTranslation(machineEl, mergedMachineElements)) {
            Path p = bxmlDirectory.resolve(dep + ".bxml");
            if (!Files.isRegularFile(p)) {
                continue;
            }
            try {
                merged.putAll(buildEnumRenames(parseMachineElement(p)));
            } catch (Exception ignored) {
                // ignora dependência inacessível
            }
        }
        return merged;
    }

    /**
     * Mapa B-name → ACSL-name para conjuntos enumerados da máquina (ex. {@code ALARM_STATUS} →
     * {@code ctx__ALARM_STATUS}).
     */
    public static Map<String, String> buildEnumeratedSetRenames(Element machineEl) {
        String machineName = machineEl.getAttribute("name");
        if (machineName == null || machineName.isBlank()) {
            return Map.of();
        }
        machineName = machineName.trim();
        LinkedHashMap<String, String> renames = new LinkedHashMap<>();
        for (EnumeratedSetInfo info : listEnumeratedSets(machineEl)) {
            renames.put(info.setName(), enumeratedSetAcslName(machineName, info.setName()));
        }
        return renames;
    }

    /**
     * Renomeação de conjuntos diferidos (sem {@code <Enumerated_Values>}) para o nome ACSL qualificado
     * (ex. {@code COPY} → {@code Biblioteca_COPY}). Esses conjuntos são declarados com prefixo de
     * máquina em {@code axiomatic NomeMaquina_sets} e devem ser referenciados pelo nome completo.
     */
    public static Map<String, String> buildDeferredSetRenames(Element machineEl) {
        String machineName = machineEl.getAttribute("name");
        if (machineName == null || machineName.isBlank()) return Map.of();
        machineName = machineName.trim();
        Element setsEl = BxmlDomUtils.firstChildElement(machineEl, "Sets");
        if (setsEl == null) return Map.of();
        LinkedHashMap<String, String> renames = new LinkedHashMap<>();
        NodeList ch = setsEl.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element setEl = (Element) n;
            if (!"Set".equals(setEl.getLocalName())) continue;
            if (BxmlDomUtils.firstChildElement(setEl, "Enumerated_Values") != null) continue;
            NodeList setChildren = setEl.getChildNodes();
            for (int j = 0; j < setChildren.getLength(); j++) {
                Node sn = setChildren.item(j);
                if (sn.getNodeType() != Node.ELEMENT_NODE) continue;
                Element idEl = (Element) sn;
                if (!"Id".equals(idEl.getLocalName())) continue;
                String name = idEl.getAttribute("value");
                if (name != null && !name.isBlank()) {
                    renames.put(name.trim(), enumeratedSetAcslName(machineName, name.trim()));
                    break;
                }
            }
        }
        return renames;
    }

    /**
     * Renomeação de conjuntos enumerados da máquina e das dependências {@code SEES}/{@code IMPORTS}.
     * Dependências primeiro; a máquina atual sobrescreve colisões (ex. {@code entry_point} e {@code ctx}
     * com o mesmo nome B).
     */
    public static Map<String, String> buildEnumeratedSetRenamesWithSees(
            Element machineEl, Path bxmlDirectory) {
        return buildEnumeratedSetRenamesWithSees(machineEl, List.of(), bxmlDirectory);
    }

    public static Map<String, String> buildEnumeratedSetRenamesWithSees(
            Element machineEl, List<Element> mergedMachineElements, Path bxmlDirectory) {
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        if (bxmlDirectory != null && Files.isDirectory(bxmlDirectory)) {
            for (String dep : dependencyMachineNamesForTranslation(machineEl, mergedMachineElements)) {
                Path p = bxmlDirectory.resolve(dep + ".bxml");
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                try {
                    Element depEl = parseMachineElement(p);
                    merged.putAll(buildEnumeratedSetRenames(depEl));
                    merged.putAll(buildDeferredSetRenames(depEl));
                } catch (Exception ignored) {
                    // ignora dependência inacessível
                }
            }
        }
        merged.putAll(buildEnumeratedSetRenames(machineEl));
        merged.putAll(buildDeferredSetRenames(machineEl));
        return merged;
    }

    /**
     * Linhas {@code include "MaquinaVista.acsl";} para cada máquina em {@code <Sees>} que gera
     * ficheiro próprio (abstração / componente sem {@code <Abstraction>}).
     */
    public static String formatSeesIncludeBlock(Element machineEl, Path bxmlDirectory) {
        return formatMachineAcslIncludeBlock(listReferencedMachineNames(machineEl), bxmlDirectory);
    }

    /**
     * Linhas {@code include "MaquinaImportada.acsl";} para cada máquina em {@code <Imports>} (abstrata
     * + cadeia fundida) que gera ficheiro próprio.
     */
    public static String formatImportsIncludeBlock(
            Element rootMachineEl, List<Element> mergedMachineElements, Path bxmlDirectory) {
        return formatMachineAcslIncludeBlock(
                listImportedMachineNamesFromChain(rootMachineEl, mergedMachineElements),
                bxmlDirectory);
    }

    /**
     * Mapa unificado viewer/importer → dependências diretas ({@code SEES} ∪ {@code IMPORTS}).
     */
    public static Map<String, List<String>> combinedDependencyMap(
            BxmlSeesGraph seesGraph, BxmlImportsGraph importsGraph) {
        LinkedHashMap<String, List<String>> merged = new LinkedHashMap<>();
        if (seesGraph != null) {
            for (Map.Entry<String, List<String>> e : seesGraph.viewerToSeenMap().entrySet()) {
                mergeDependencyList(merged, e.getKey(), e.getValue());
            }
        }
        if (importsGraph != null) {
            for (Map.Entry<String, List<String>> e : importsGraph.importerToImportedMap().entrySet()) {
                mergeDependencyList(merged, e.getKey(), e.getValue());
            }
        }
        return Map.copyOf(merged);
    }

    /**
     * Fecho transitivo de {@code SEES}/{@code IMPORTS} em ordem topológica (dependências antes de
     * dependentes), para o preâmbulo do {@code .acsl} raiz de importação Frama-C.
     */
    public static List<String> transitiveDependencyMachineNames(
            String rootMachineName, BxmlSeesGraph seesGraph, BxmlImportsGraph importsGraph) {
        if (rootMachineName == null || rootMachineName.isBlank()) {
            return List.of();
        }
        Map<String, List<String>> deps = combinedDependencyMap(seesGraph, importsGraph);
        LinkedHashSet<String> reachable = new LinkedHashSet<>();
        ArrayDeque<String> stack = new ArrayDeque<>();
        stack.push(rootMachineName.trim());
        while (!stack.isEmpty()) {
            String current = stack.pop();
            for (String dep : deps.getOrDefault(current, List.of())) {
                if (dep == null || dep.isBlank()) {
                    continue;
                }
                String d = dep.trim();
                if (reachable.add(d)) {
                    stack.push(d);
                }
            }
        }
        return topologicalSortDependencies(reachable, deps);
    }

    private static void mergeDependencyList(
            Map<String, List<String>> merged, String viewer, List<String> deps) {
        if (viewer == null || viewer.isBlank() || deps == null || deps.isEmpty()) {
            return;
        }
        LinkedHashSet<String> names = new LinkedHashSet<>(merged.getOrDefault(viewer.trim(), List.of()));
        for (String dep : deps) {
            if (dep != null && !dep.isBlank()) {
                names.add(dep.trim());
            }
        }
        merged.put(viewer.trim(), List.copyOf(names));
    }

    private static List<String> topologicalSortDependencies(
            Set<String> nodes, Map<String, List<String>> viewerToDeps) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (String n : nodes) {
            inDegree.put(n, 0);
        }
        for (String m : nodes) {
            for (String d : viewerToDeps.getOrDefault(m, List.of())) {
                if (d == null || d.isBlank() || !nodes.contains(d.trim())) {
                    continue;
                }
                String dep = d.trim();
                adj.computeIfAbsent(dep, k -> new ArrayList<>()).add(m);
                inDegree.merge(m, 1, Integer::sum);
            }
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) {
                ready.add(e.getKey());
            }
        }
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String n = ready.removeFirst();
            order.add(n);
            for (String dependent : adj.getOrDefault(n, List.of())) {
                int next = inDegree.merge(dependent, -1, Integer::sum);
                if (next == 0) {
                    ready.addLast(dependent);
                }
            }
        }
        if (order.size() != nodes.size()) {
            LinkedHashSet<String> fallback = new LinkedHashSet<>(order);
            for (String n : nodes) {
                fallback.add(n);
            }
            return List.copyOf(fallback);
        }
        return List.copyOf(order);
    }

    private static String formatMachineAcslIncludeBlock(
            List<String> machineNames, Path bxmlDirectory) {
        if (machineNames == null || machineNames.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String name : machineNames) {
            if (bxmlDirectory == null) {
                sb.append("include \"").append(name).append(".acsl\";\n");
                continue;
            }
            Path bxml = bxmlDirectory.resolve(name + ".bxml");
            if (!Files.isRegularFile(bxml)) {
                continue;
            }
            try {
                Element depEl = parseMachineElement(bxml);
                if (!MachineIncludeScanCollector.machineGeneratesOwnAcslFile(depEl)) {
                    continue;
                }
            } catch (Exception ignored) {
                continue;
            }
            sb.append("include \"").append(name).append(".acsl\";\n");
        }
        if (sb.isEmpty()) {
            return "";
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * Nomes das constantes concretas ({@code Concrete_Constants}) declaradas nas máquinas em
     * {@code SEES} da máquina {@code machineEl}.  Usado pelo gerador de {@code ghost_operations.ci}
     * para emitir {@code logic integer <NAME>;} quando tais constantes aparecem bare nos contratos
     * ghost (sem prefixo {@code dummy_}).
     */
    public static List<String> listSeenMachineConcreteConstantNames(
            Element machineEl, Path bxmlDirectory) {
        if (machineEl == null || bxmlDirectory == null || !Files.isDirectory(bxmlDirectory)) {
            return List.of();
        }
        List<String> seenNames = listReferencedMachineNames(machineEl);
        List<String> result = new ArrayList<>();
        for (String seenName : seenNames) {
            Path p = bxmlDirectory.resolve(seenName + ".bxml");
            if (!Files.isRegularFile(p)) {
                continue;
            }
            try {
                Element seenEl = parseMachineElement(p);
                Element block = BxmlDomUtils.firstChildElement(seenEl, "Concrete_Constants");
                if (block == null) {
                    continue;
                }
                NodeList ch = block.getChildNodes();
                for (int i = 0; i < ch.getLength(); i++) {
                    Node n = ch.item(i);
                    if (n.getNodeType() != Node.ELEMENT_NODE) {
                        continue;
                    }
                    Element e = (Element) n;
                    if (!"Id".equals(e.getLocalName())) {
                        continue;
                    }
                    String name = e.getAttribute("value");
                    if (name != null && !name.isBlank()) {
                        result.add(name.trim());
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    /**
     * Nomes ACSL qualificados (ex. {@code Goods_GOODS}) de conjuntos diferidos (sem
     * {@code Enumerated_Values}) declarados nas máquinas em {@code SEES} de {@code machineEl}.
     * Espelha {@link #listSeenMachineConcreteConstantNames}: tal como as constantes concretas
     * vistas, estes conjuntos são globais partilhados (declarados no {@code .acsl} da máquina
     * vista, carregado via {@code -acsl-import}) e o gerador de {@code ghost_operations.ci}
     * precisa de os tratar como referências {@code dummy_} locais — o parser externo do
     * {@code .ci} não partilha o ambiente lógico do {@code -acsl-import}.
     */
    public static List<String> listSeenMachineDeferredSetQualifiedNames(
            Element machineEl, Path bxmlDirectory) {
        if (machineEl == null || bxmlDirectory == null || !Files.isDirectory(bxmlDirectory)) {
            return List.of();
        }
        List<String> seenNames = listReferencedMachineNames(machineEl);
        List<String> result = new ArrayList<>();
        for (String seenName : seenNames) {
            Path p = bxmlDirectory.resolve(seenName + ".bxml");
            if (!Files.isRegularFile(p)) {
                continue;
            }
            try {
                Element seenEl = parseMachineElement(p);
                result.addAll(buildDeferredSetRenames(seenEl).values());
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    /**
     * Encontra o ficheiro {@code .bxml} de IMPLEMENTAÇÃO de {@code abstractMachineName} em
     * {@code bxmlDirectory}, identificando-o pelo próprio conteúdo BXML ({@code type='implementation'}
     * e {@code <Abstraction>} apontando para o nome da máquina abstrata) em vez de por convenção de
     * nome de ficheiro. Sufixos de nome de ficheiro ({@code "_i"}, {@code "_imp"}, {@code "I"}, …)
     * variam por projeto — ex.: BirthdayRegister usa {@code ContextI}/{@code RegisterI} (sem
     * separador), diferente de outros exemplos que usam {@code Foo_i} — qualquer lista fixa de
     * sufixos fica incompleta mais cedo ou mais tarde (confirmado: a cardinalidade de {@code PERSON}
     * nunca resolvia para BirthdayRegister porque {@code "Context_i.bxml"}/{@code "Context_imp.bxml"}
     * nunca existiram, só {@code "ContextI.bxml"}).
     */
    static Element findImplementationMachineElement(String abstractMachineName, Path bxmlDirectory) {
        if (abstractMachineName == null || abstractMachineName.isBlank()
                || bxmlDirectory == null || !Files.isDirectory(bxmlDirectory)) {
            return null;
        }
        List<Path> candidates;
        try (Stream<Path> files = Files.list(bxmlDirectory)) {
            candidates = files
                    .filter(p -> p.getFileName().toString().endsWith(".bxml"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return null;
        }
        for (Path p : candidates) {
            try {
                Element el = parseMachineElement(p);
                if (!"implementation".equalsIgnoreCase(el.getAttribute("type"))) {
                    continue;
                }
                Element abs = BxmlDomUtils.firstChildElement(el, "Abstraction");
                String absName = abs != null ? abs.getTextContent().trim() : null;
                if (abstractMachineName.equals(absName)) {
                    return el;
                }
            } catch (Exception ignored) {
                // ficheiro malformado ou não-BXML; ignora e tenta o próximo
            }
        }
        return null;
    }

    /**
     * Encontra o ficheiro {@code .bxml} ABSTRATO ({@code type='abstraction'}, {@code name=machineName})
     * em {@code bxmlDirectory} — irmão de {@link #findImplementationMachineElement}, para quando o
     * tipo {@code logic} de uma variável só está disponível no invariante da camada ABSTRATA (ex.:
     * {@code contents : seq(Fifo_ctx_ELEM)} em {@code Fifo.bxml}), não na implementação (cujo próprio
     * invariante de colagem usa {@code contents} sem o retipar).
     */
    static Element findAbstractMachineElement(String machineName, Path bxmlDirectory) {
        if (machineName == null || machineName.isBlank()
                || bxmlDirectory == null || !Files.isDirectory(bxmlDirectory)) {
            return null;
        }
        List<Path> candidates;
        try (Stream<Path> files = Files.list(bxmlDirectory)) {
            candidates = files
                    .filter(p -> p.getFileName().toString().endsWith(".bxml"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return null;
        }
        for (Path p : candidates) {
            try {
                Element el = parseMachineElement(p);
                if (!"abstraction".equalsIgnoreCase(el.getAttribute("type"))) {
                    continue;
                }
                if (machineName.equals(el.getAttribute("name"))) {
                    return el;
                }
            } catch (Exception ignored) {
                // ficheiro malformado ou não-BXML; ignora e tenta o próximo
            }
        }
        return null;
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
        Element setsEl = BxmlDomUtils.firstChildElement(machineEl, "Sets");
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
            Element enumEl = BxmlDomUtils.firstChildElement(setEl, "Enumerated_Values");
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
        return formatSetsBlock(machineEl, List.of());
    }

    /**
     * Overload com dependências: para conjuntos enumerados herdados via SEES/IMPORTS, usa o prefixo
     * C da máquina que define o conjunto (acessível globalmente no Frama-C) em vez do prefixo da
     * máquina atual (presente em apenas um arquivo C).
     */
    public static String formatSetsBlock(
            Element machineEl, List<Element> mergedMachineElements, Path bxmlDirectory) {
        List<Element> depMachineEls = new ArrayList<>();
        if (bxmlDirectory != null && Files.isDirectory(bxmlDirectory)) {
            for (String dep : dependencyMachineNamesForTranslation(machineEl, mergedMachineElements)) {
                Path p = bxmlDirectory.resolve(dep + ".bxml");
                if (!Files.isRegularFile(p)) continue;
                try {
                    depMachineEls.add(parseMachineElement(p));
                } catch (Exception ignored) {}
            }
        }
        return formatSetsBlock(machineEl, depMachineEls);
    }

    public static String formatSetsBlock(Element machineEl, List<Element> depMachineEls) {
        Element setsEl = BxmlDomUtils.firstChildElement(machineEl, "Sets");
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
            appendOneSetTranslation(setEl, machineName, depMachineEls, logics, axioms);
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

    private static void appendOneSetTranslation(
            Element setEl, String machineName, List<Element> depMachineEls,
            List<String> logics, List<String> axioms) {
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
                String acslSetName = enumeratedSetAcslName(machineName, setName);
                logics.add("    logic Set<integer> " + acslSetName + ";");
                break;
            }
        }

        // Valores enumerados — usa prefixo C do dono real do conjunto (dep que o define)
        if (setName == null) return;
        appendEnumeratedSetValues(setEl, setName, machineName, depMachineEls, logics, axioms);
    }

    private static void appendEnumeratedSetValues(
            Element setEl, String setName, String machineName, List<Element> depMachineEls,
            List<String> logics, List<String> axioms) {
        String acslSetName = enumeratedSetAcslName(machineName, setName);
        Element enumEl = BxmlDomUtils.firstChildElement(setEl, "Enumerated_Values");
        if (enumEl == null) return;

        // Prefixo C: dependência que também define o mesmo conjunto (acessível globalmente
        // no Frama-C por estar em múltiplos .c), ou a própria máquina se não houver.
        String valueMachineName = findSetOwnerMachineName(setName, depMachineEls);
        if (valueMachineName == null) valueMachineName = machineName;

        List<String> prefixedVals = new ArrayList<>();
        NodeList enumChildren = enumEl.getChildNodes();
        for (int j = 0; j < enumChildren.getLength(); j++) {
            Node en = enumChildren.item(j);
            if (en.getNodeType() != Node.ELEMENT_NODE) continue;
            Element ev = (Element) en;
            if (!"Id".equals(ev.getLocalName())) continue;
            String val = ev.getAttribute("value");
            if (val == null || val.isBlank()) continue;
            prefixedVals.add(valueMachineName + "__" + val.trim());
        }
        if (prefixedVals.isEmpty()) return;

        if (valueMachineName.equals(machineName)) {
            for (int idx = 0; idx < prefixedVals.size(); idx++) {
                logics.add("    logic integer " + prefixedVals.get(idx) + " = " + idx + ";");
            }
        }
        StringBuilder ax = new StringBuilder();
        ax.append("    axiom ").append(acslSetName).append("_values:\n");
        for (String pv : prefixedVals) {
            ax.append("        belongs(").append(pv).append(", ").append(acslSetName).append(")\n");
            ax.append("        &&\n");
        }
        ax.append("        \\forall integer x;\n");
        ax.append("            belongs(x, ").append(acslSetName).append(") ==>\n");
        ax.append("            (");
        for (int k = 0; k < prefixedVals.size(); k++) {
            if (k > 0) ax.append(" || ");
            ax.append("x == ").append(prefixedVals.get(k));
        }
        ax.append(");");
        axioms.add(ax.toString());
    }

    private static String findSetOwnerMachineName(String setName, List<Element> depMachineEls) {
        for (Element depEl : depMachineEls) {
            for (EnumeratedSetInfo info : listEnumeratedSets(depEl)) {
                if (setName.equals(info.setName())) {
                    return info.machineName();
                }
            }
        }
        return null;
    }

}
