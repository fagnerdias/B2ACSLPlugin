package com.example;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Mapa de dependências entre símbolos da {@code B2ACSLLib} (funções/predicados {@code logic}/{@code
 * predicate}), gerado por {@code scripts/generate_acsl_symbol_dependency_map.py}.
 *
 * <p><b>Semântica do grafo {@code includes_from}:</b> contém tanto os {@code include "..."} explícitos
 * dos ficheiros da biblioteca como arestas implícitas adicionadas automaticamente pelo script Python
 * para ficheiros de axiomas que usam símbolos cujos ficheiros definidores não estão no fecho
 * explícito (ex.: {@code relation_axioms/relation_apply.acsl} → {@code tuple_functions/accessors.acsl}
 * porque o axioma chama {@code second(t)} sobre um {@code Tuple}).
 *
 * <p>O campo {@code symbol_to_all_files} (mapa 1:N) regista todos os ficheiros que declaram um
 * símbolo, suportando sobrecarga por tipo (ex.: {@code first} existe tanto em
 * {@code sequence_functions/first.acsl} como em {@code tuple_functions/accessors.acsl}).
 * {@link #directIncludeClosureForSymbol(String)} usa todos os ficheiros definidores para garantir
 * que todas as sobrecargas estão disponíveis.
 *
 * <p>Uso típico: dado um símbolo usado na especificação, {@link
 * #directIncludeClosureForSymbol(String)} devolve os caminhos relativos (ex.
 * {@code relation_functions/domain.acsl}, {@code tuple_functions/tuple_couple.acsl}) necessários —
 * fecho transitivo do grafo {@code includes_from} a partir de cada ficheiro definidor do símbolo.
 */
public final class AcslLibSymbolDependencyMap {

    private static final String RESOURCE = B2AcslLibraryPaths.SYMBOL_DEPENDENCY_MAP_RESOURCE;

    private static volatile AcslLibSymbolDependencyMap instance;

    private final Map<String, List<String>> dependencies;
    private final Map<String, String> symbolToDefiningFile;
    private final Map<String, List<String>> symbolToAllFiles;
    private final Map<String, List<String>> includesFrom;

    private AcslLibSymbolDependencyMap(
            Map<String, List<String>> dependencies,
            Map<String, String> symbolToDefiningFile,
            Map<String, List<String>> symbolToAllFiles,
            Map<String, List<String>> includesFrom) {
        this.dependencies = dependencies;
        this.symbolToDefiningFile = symbolToDefiningFile;
        this.symbolToAllFiles = symbolToAllFiles;
        this.includesFrom = includesFrom;
    }

    /** Instância lazy; falha silenciosa → mapa vazio se o JSON não existir no classpath. */
    public static AcslLibSymbolDependencyMap instance() {
        AcslLibSymbolDependencyMap local = instance;
        if (local != null) {
            return local;
        }
        synchronized (AcslLibSymbolDependencyMap.class) {
            if (instance == null) {
                instance = loadOrEmpty();
            }
            return instance;
        }
    }

    public boolean isLoaded() {
        return !symbolToDefiningFile.isEmpty();
    }

    /** Dependências diretas (símbolos da lib chamados no corpo agregado). */
    public List<String> directDependencySymbols(String symbol) {
        if (symbol == null) {
            return List.of();
        }
        List<String> d = dependencies.get(symbol);
        return d == null ? List.of() : List.copyOf(d);
    }

    /** Fecho transitivo de dependências ao nível de símbolo (BFS). */
    public Set<String> transitiveDependencySymbols(String rootSymbol) {
        if (rootSymbol == null || rootSymbol.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        ArrayDeque<String> q = new ArrayDeque<>();
        q.add(rootSymbol);
        while (!q.isEmpty()) {
            String s = q.removeFirst();
            if (!out.add(s)) {
                continue;
            }
            for (String d : directDependencySymbols(s)) {
                if (!out.contains(d)) {
                    q.add(d);
                }
            }
        }
        return out;
    }

    /** Ficheiro canónico (primeira declaração) para o símbolo, ou {@code null} se não encontrado. */
    public String definingFile(String symbol) {
        if (symbol == null) {
            return null;
        }
        return symbolToDefiningFile.get(symbol);
    }

    /**
     * Todos os ficheiros que declaram o símbolo (incluindo sobrecargas).
     * Para símbolos não sobrecarregados, lista com um único elemento.
     */
    public List<String> allFilesForSymbol(String symbol) {
        if (symbol == null) {
            return List.of();
        }
        List<String> all = symbolToAllFiles.get(symbol);
        if (all != null && !all.isEmpty()) {
            return List.copyOf(all);
        }
        String canonical = symbolToDefiningFile.get(symbol);
        return canonical != null ? List.of(canonical) : List.of();
    }

    /**
     * Fecho transitivo do grafo {@code includes_from} a partir de todos os ficheiros que declaram
     * o símbolo. Inclui sobrecargas: para {@code first}, retorna o fecho de
     * {@code sequence_functions/first.acsl} e de {@code tuple_functions/accessors.acsl}.
     */
    public Set<String> directIncludeClosureForSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return Set.of();
        }
        List<String> defs = allFilesForSymbol(symbol);
        if (defs.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String f : defs) {
            out.addAll(transitiveIncludeClosureFromFile(f));
        }
        return out;
    }

    /**
     * União de {@link #directIncludeClosureForSymbol(String)} para cada símbolo.
     */
    public Set<String> directIncludeClosureForSymbols(Collection<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String symbol : symbols) {
            if (symbol != null && !symbol.isBlank()) {
                out.addAll(directIncludeClosureForSymbol(symbol));
            }
        }
        return out;
    }

    /**
     * União de {@link #directIncludeClosureForSymbol} para cada símbolo, expandindo também as
     * dependências de símbolo transitivas (útil para resolução completa via grafo de símbolos).
     */
    public Set<String> transitiveLibRelativePathsForSymbol(String symbol) {
        return transitiveLibRelativePathsForSymbols(List.of(symbol));
    }

    /**
     * União de {@link #transitiveLibRelativePathsForSymbol(String)} para cada símbolo.
     */
    public Set<String> transitiveLibRelativePathsForSymbols(Collection<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> symClosure = new LinkedHashSet<>();
        for (String s : symbols) {
            if (s != null && !s.isBlank()) {
                symClosure.addAll(transitiveDependencySymbols(s));
            }
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : symClosure) {
            out.addAll(directIncludeClosureForSymbol(s));
        }
        return out;
    }

    /** Fecho transitivo do grafo {@code includes_from} a partir de um ficheiro. */
    public Set<String> transitiveIncludeClosureFromFile(String relativeLibPath) {
        String start = relativeLibPath == null ? "" : relativeLibPath.replace('\\', '/');
        if (start.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        ArrayDeque<String> q = new ArrayDeque<>();
        q.add(start);
        while (!q.isEmpty()) {
            String u = q.removeFirst();
            if (!seen.add(u)) {
                continue;
            }
            List<String> inc = includesFrom.get(u);
            if (inc == null) {
                continue;
            }
            for (String v : inc) {
                if (v != null && !seen.contains(v)) {
                    q.add(v.replace('\\', '/'));
                }
            }
        }
        return seen;
    }

    public Set<String> allKnownSymbols() {
        return Collections.unmodifiableSet(symbolToDefiningFile.keySet());
    }

    private static AcslLibSymbolDependencyMap loadOrEmpty() {
        try (InputStream in = AcslLibSymbolDependencyMap.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return empty();
            }
            ObjectMapper om = new ObjectMapper();
            JsonNode root = om.readTree(in);
            Map<String, List<String>> deps = parseStringListMap(root.path("dependencies"));
            Map<String, String> symFile = parseStringStringMap(root.path("symbol_to_defining_file"));
            Map<String, List<String>> allFiles = parseStringListMap(root.path("symbol_to_all_files"));
            Map<String, List<String>> inc = parseStringListMap(root.path("includes_from"));
            return new AcslLibSymbolDependencyMap(deps, symFile, allFiles, inc);
        } catch (IOException e) {
            return empty();
        }
    }

    private static AcslLibSymbolDependencyMap empty() {
        return new AcslLibSymbolDependencyMap(Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static Map<String, List<String>> parseStringListMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(
                e -> {
                    List<String> list = new ArrayList<>();
                    JsonNode arr = e.getValue();
                    if (arr != null && arr.isArray()) {
                        for (JsonNode x : arr) {
                            if (x.isTextual()) {
                                list.add(x.asText());
                            }
                        }
                    }
                    out.put(e.getKey(), list);
                });
        return out;
    }

    private static Map<String, String> parseStringStringMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        node.fields()
                .forEachRemaining(
                        e -> {
                            JsonNode v = e.getValue();
                            if (v != null && v.isTextual()) {
                                out.put(e.getKey(), v.asText());
                            }
                        });
        return out;
    }

    /** Expõe o grafo de dependência direta (imutável) para testes ou ferramentas. */
    public Map<String, List<String>> rawDependencies() {
        return dependencies.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    }

    /** Expõe includes diretos por ficheiro. */
    public Map<String, List<String>> rawIncludesFrom() {
        return includesFrom.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    }

    static void resetForTests() {
        synchronized (AcslLibSymbolDependencyMap.class) {
            instance = null;
        }
    }
}
