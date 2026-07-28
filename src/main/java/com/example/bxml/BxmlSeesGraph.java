package com.example.bxml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Element;

import com.example.AcslGenerator;

/**
 * Grafo {@code SEES} do projeto B: qual máquina abstrata <em>vê</em> ({@code <Sees>}) quais outras.
 *
 * <p>Ex.: {@code Airlock} vê {@code Airlock_pressure_bs}. Máquinas que só aparecem como vistas
 * noutro {@code SEES} não são raízes de importação Frama-C ({@link #topLevelImportMachineNames});
 * a especificação entra via {@code include "MaquinaVista.acsl"} no {@code .acsl} da máquina que vê.
 */
public final class BxmlSeesGraph {

    /** Par ordenado viewer → seen (uma aresta por máquina referenciada em {@code SEES}). */
    public record SeesRelation(String viewer, String seen) {}

    private final Map<String, List<String>> viewerToSeen;
    private final Map<String, List<String>> seenToViewers;

    private BxmlSeesGraph(
            Map<String, List<String>> viewerToSeen, Map<String, List<String>> seenToViewers) {
        this.viewerToSeen = Map.copyOf(viewerToSeen);
        this.seenToViewers = Map.copyOf(seenToViewers);
    }

    /**
     * Constrói o grafo a partir de todos os {@code .bxml} numa pasta (máquinas abstratas com
     * ficheiro próprio; refinamentos com {@code <Abstraction>} são ignorados).
     */
    public static BxmlSeesGraph fromBxmlDirectory(Path bxmlDirectory) {
        if (bxmlDirectory == null || !Files.isDirectory(bxmlDirectory)) {
            return empty();
        }
        LinkedHashMap<String, List<String>> viewerToSeen = new LinkedHashMap<>();
        try (var stream = Files.list(bxmlDirectory)) {
            List<Path> bxmlFiles =
                    stream.filter(p -> p.getFileName().toString().endsWith(".bxml")).sorted().toList();
            for (Path bxml : bxmlFiles) {
                try {
                    Element root = BxmlSetsTranslator.parseMachineElement(bxml);
                    if (AcslGenerator.getAbstractionReferenceName(root).isPresent()) {
                        continue;
                    }
                    String viewer = root.getAttribute("name");
                    if (viewer == null || viewer.isBlank()) {
                        continue;
                    }
                    viewer = viewer.trim();
                    List<String> seen = BxmlSetsTranslator.listReferencedMachineNames(root);
                    if (!seen.isEmpty()) {
                        viewerToSeen.put(viewer, List.copyOf(seen));
                    }
                } catch (Exception ignored) {
                    // ignora ficheiro inválido
                }
            }
        } catch (Exception e) {
            return empty();
        }
        return fromViewerToSeen(viewerToSeen);
    }

    public static BxmlSeesGraph fromViewerToSeen(Map<String, List<String>> viewerToSeen) {
        LinkedHashMap<String, List<String>> vts = new LinkedHashMap<>();
        LinkedHashMap<String, List<String>> stv = new LinkedHashMap<>();
        if (viewerToSeen != null) {
            for (Map.Entry<String, List<String>> e : viewerToSeen.entrySet()) {
                String viewer = e.getKey();
                if (viewer == null || viewer.isBlank()) {
                    continue;
                }
                List<String> seenList = e.getValue() == null ? List.of() : e.getValue();
                if (seenList.isEmpty()) {
                    continue;
                }
                vts.put(viewer, List.copyOf(seenList));
                for (String seen : seenList) {
                    if (seen == null || seen.isBlank()) {
                        continue;
                    }
                    stv.computeIfAbsent(seen.trim(), k -> new ArrayList<>()).add(viewer);
                }
            }
        }
        vts.replaceAll((k, v) -> List.copyOf(v));
        stv.replaceAll((k, v) -> List.copyOf(v));
        return new BxmlSeesGraph(vts, stv);
    }

    private static BxmlSeesGraph empty() {
        return new BxmlSeesGraph(Map.of(), Map.of());
    }

    /** Todas as relações {@code viewer} vê {@code seen}. */
    public List<SeesRelation> relations() {
        List<SeesRelation> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : viewerToSeen.entrySet()) {
            for (String seen : e.getValue()) {
                out.add(new SeesRelation(e.getKey(), seen));
            }
        }
        return List.copyOf(out);
    }

    /** Máquinas referenciadas em {@code <Sees>} de {@code viewer} (pode ser vazio). */
    public List<String> seenBy(String viewer) {
        if (viewer == null) {
            return List.of();
        }
        return viewerToSeen.getOrDefault(viewer.trim(), List.of());
    }

    /** {@code true} se alguma máquina do projeto a referencia em {@code <Sees>}. */
    public boolean isReferencedBySees(String machineName) {
        return machineName != null && seenToViewers.containsKey(machineName.trim());
    }

    /**
     * Máquinas que só são vistas (não são raiz de importação ACSL quando outra máquina as inclui).
     */
    public Set<String> seenOnlyMachineNames() {
        return Set.copyOf(seenToViewers.keySet());
    }

    /**
     * Raízes de importação Frama-C entre as abstrações do projeto: abstrações que <em>não</em> são
     * apenas alvo de {@code SEES} de outra (ex. {@code Airlock}, não {@code Airlock_pressure_bs}).
     *
     * @param abstractMachineNames nomes das máquinas abstratas com {@code .acsl} gerado
     */
    public List<String> topLevelImportMachineNames(Set<String> abstractMachineNames) {
        if (abstractMachineNames == null || abstractMachineNames.isEmpty()) {
            return List.of();
        }
        Set<String> seenOnly = seenOnlyMachineNames();
        List<String> out = new ArrayList<>();
        for (String name : abstractMachineNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String n = name.trim();
            if (!seenOnly.contains(n)) {
                out.add(n);
            }
        }
        return List.copyOf(out);
    }

    public Map<String, List<String>> viewerToSeenMap() {
        return viewerToSeen;
    }
}
