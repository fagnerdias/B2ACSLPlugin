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
 * Grafo {@code IMPORTS} do projeto B: qual máquina abstrata <em>importa</em> ({@code <Imports>}) quais
 * outras.
 *
 * <p>A cláusula {@code IMPORTS} aparece tipicamente em implementações/refinamentos; as arestas são
 * atribuídas à máquina abstrata raiz da cadeia {@code <Abstraction>}. Máquinas que só são alvo de
 * {@code IMPORTS} não são raízes de importação Frama-C ({@link #importedOnlyMachineNames}); a
 * especificação entra via {@code include "MaquinaImportada.acsl"} no {@code .acsl} da máquina que
 * importa.
 */
public final class BxmlImportsGraph {

    /** Par ordenado importer → imported (uma aresta por máquina referenciada em {@code IMPORTS}). */
    public record ImportsRelation(String importer, String imported) {}

    private final Map<String, List<String>> importerToImported;
    private final Map<String, List<String>> importedToImporters;

    private BxmlImportsGraph(
            Map<String, List<String>> importerToImported,
            Map<String, List<String>> importedToImporters) {
        this.importerToImported = Map.copyOf(importerToImported);
        this.importedToImporters = Map.copyOf(importedToImporters);
    }

    /**
     * Constrói o grafo a partir de todos os {@code .bxml} numa pasta. Em abstrações, lê {@code
     * <Imports>} localmente; em refinamentos/implementações, mapeia para a raiz abstrata via {@code
     * abstractionParentByMachine}.
     */
    public static BxmlImportsGraph fromBxmlDirectory(
            Path bxmlDirectory, Map<String, String> abstractionParentByMachine) {
        if (bxmlDirectory == null || !Files.isDirectory(bxmlDirectory)) {
            return empty();
        }
        LinkedHashMap<String, List<String>> importerToImported = new LinkedHashMap<>();
        try (var stream = Files.list(bxmlDirectory)) {
            List<Path> bxmlFiles =
                    stream.filter(p -> p.getFileName().toString().endsWith(".bxml")).sorted().toList();
            for (Path bxml : bxmlFiles) {
                try {
                    Element root = BxmlSetsTranslator.parseMachineElement(bxml);
                    String sourceRaw = root.getAttribute("name");
                    if (sourceRaw == null || sourceRaw.isBlank()) {
                        continue;
                    }
                    final String source = sourceRaw.trim();
                    List<String> imported = BxmlSetsTranslator.listImportedMachineNames(root);
                    if (imported.isEmpty()) {
                        continue;
                    }
                    String importer =
                            AcslGenerator.getAbstractionReferenceName(root)
                                    .map(
                                            ignored ->
                                                    resolveRootAbstractName(
                                                            source, abstractionParentByMachine))
                                    .orElse(source);
                    mergeImports(importerToImported, importer, imported);
                } catch (Exception ignored) {
                    // ignora ficheiro inválido
                }
            }
        } catch (Exception e) {
            return empty();
        }
        return fromImporterToImported(importerToImported);
    }

    public static BxmlImportsGraph fromImporterToImported(Map<String, List<String>> importerToImported) {
        LinkedHashMap<String, List<String>> iti = new LinkedHashMap<>();
        LinkedHashMap<String, List<String>> ito = new LinkedHashMap<>();
        if (importerToImported != null) {
            for (Map.Entry<String, List<String>> e : importerToImported.entrySet()) {
                String importer = e.getKey();
                if (importer == null || importer.isBlank()) {
                    continue;
                }
                List<String> importedList = e.getValue() == null ? List.of() : e.getValue();
                if (importedList.isEmpty()) {
                    continue;
                }
                iti.put(importer, List.copyOf(importedList));
                for (String imported : importedList) {
                    if (imported == null || imported.isBlank()) {
                        continue;
                    }
                    ito.computeIfAbsent(imported.trim(), k -> new ArrayList<>()).add(importer);
                }
            }
        }
        iti.replaceAll((k, v) -> List.copyOf(v));
        ito.replaceAll((k, v) -> List.copyOf(v));
        return new BxmlImportsGraph(iti, ito);
    }

    private static void mergeImports(
            Map<String, List<String>> importerToImported,
            String importer,
            List<String> imported) {
        LinkedHashSet<String> merged =
                new LinkedHashSet<>(importerToImported.getOrDefault(importer, List.of()));
        merged.addAll(imported);
        importerToImported.put(importer, List.copyOf(merged));
    }

    private static BxmlImportsGraph empty() {
        return new BxmlImportsGraph(Map.of(), Map.of());
    }

    private static String resolveRootAbstractName(
            String machineName, Map<String, String> parentOf) {
        String current = machineName;
        for (int i = 0; i < 256; i++) {
            String p = parentOf == null ? null : parentOf.get(current);
            if (p == null || p.isBlank()) {
                return current;
            }
            current = p;
        }
        return current;
    }

    public List<ImportsRelation> relations() {
        List<ImportsRelation> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : importerToImported.entrySet()) {
            for (String imported : e.getValue()) {
                out.add(new ImportsRelation(e.getKey(), imported));
            }
        }
        return List.copyOf(out);
    }

    public List<String> importedBy(String importer) {
        if (importer == null) {
            return List.of();
        }
        return importerToImported.getOrDefault(importer.trim(), List.of());
    }

    public boolean isReferencedByImports(String machineName) {
        return machineName != null && importedToImporters.containsKey(machineName.trim());
    }

    /** Máquinas que só são importadas (não são raiz de importação ACSL quando outra as inclui). */
    public Set<String> importedOnlyMachineNames() {
        return Set.copyOf(importedToImporters.keySet());
    }

    public Map<String, List<String>> importerToImportedMap() {
        return importerToImported;
    }
}
