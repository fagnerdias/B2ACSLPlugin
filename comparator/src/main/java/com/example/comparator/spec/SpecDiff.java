package com.example.comparator.spec;

import com.example.comparator.report.Reports.ClauseSets;
import com.example.comparator.report.Reports.SpecComparison;
import com.example.comparator.report.Reports.SpecSummary;
import com.example.comparator.report.Reports.UnitComparison;
import com.example.comparator.spec.meaning.MeaningComparator;
import com.example.comparator.spec.meaning.MeaningContext;
import com.example.comparator.spec.meaning.MeaningRule;
import com.example.comparator.util.FileTrees;
import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Compares the specification files of the two tools: structurally (units and clauses, see {@link
 * AcslUnitExtractor}/{@link GhostCiExtractor}) and textually (whitespace-normalized unified diff
 * of files with the same name).
 *
 * <p>Each unit that differs is classified by {@link MeaningComparator}: either it differs only in
 * form (the same meaning, written differently) or in meaning.
 *
 * <p>Units of all files of one side are merged before comparing, because B2ACSLPlugin spreads
 * content across auxiliary files ({@code _M_lib_includes.acsl}, {@code M_tuple_types.acsl}, ...)
 * that B2ACSLRules does not have.
 */
public final class SpecDiff {

    public static final String GHOST_CI = "ghost_operations.ci";

    /** Unparsed clauses kept in a report (the rest only count). */
    private static final int MAX_UNPARSED = 200;

    private static final List<String> KIND_ORDER =
            List.of("include", "type", "logic", "predicate", "inductive", "axiom", "lemma", "axiomatic", "function");

    private SpecDiff() {}

    /** Specification files of a directory: top-level {@code *.acsl} and {@code ghost_operations.ci}. */
    public static List<Path> specFiles(Path dir) throws IOException {
        return FileTrees.listFiles(
                dir, p -> p.getFileName().toString().endsWith(".acsl") || p.getFileName().toString().equals(GHOST_CI));
    }

    public static SpecComparison compare(Path pluginSpecDir, Path rulesSpecDir) throws IOException {
        return compare(pluginSpecDir, rulesSpecDir, Set.of());
    }

    /**
     * @param machineNames names of the B machines of the project (they qualify names on one side and
     *     not on the other, see {@link MeaningRule#MACHINE_PREFIX})
     */
    public static SpecComparison compare(Path pluginSpecDir, Path rulesSpecDir, Set<String> machineNames)
            throws IOException {
        List<Path> pluginFiles = specFiles(pluginSpecDir);
        List<Path> rulesFiles = specFiles(rulesSpecDir);
        Map<String, SpecUnit> plugin = extractUnits(pluginFiles);
        Map<String, SpecUnit> rules = extractUnits(rulesFiles);
        MeaningContext context = MeaningContext.build(plugin.values(), rules.values(), machineNames);
        MeaningComparator meaning = new MeaningComparator(context, plugin.values(), rules.values());

        List<UnitComparison> units = new ArrayList<>();
        Counts counts = new Counts();
        Map<String, Integer> formRules = new TreeMap<>();
        for (String[] pair : pairUnits(plugin, rules, meaning)) {
            UnitComparison unit = compareUnit(plugin.get(pair[0]), rules.get(pair[1]), meaning, counts);
            units.add(unit);
            if (MeaningComparator.FORM.equals(unit.difference())) {
                unit.formRules().forEach(rule -> formRules.merge(rule, 1, Integer::sum));
            }
        }

        Map<String, String> unparsed = new TreeMap<>();
        context.parseFailures().entrySet().stream()
                .limit(MAX_UNPARSED)
                .forEach(e -> unparsed.put(e.getKey(), e.getValue()));
        return new SpecComparison(
                counts.summary(units.size()),
                units,
                textDiffs(pluginFiles, rulesFiles),
                filesOnlyIn(pluginFiles, rulesFiles),
                formRules,
                context.renamings(),
                unparsed);
    }

    /**
     * Pairs of keys {@code [pluginKey, rulesKey]} (one of them {@code null} for a unit only one side
     * has), in report order: units with the same key, then units only one side has whose names the
     * renamings map to the same key ({@code logic:Goods_GOODS} and {@code logic:GOODS}).
     */
    private static List<String[]> pairUnits(Map<String, SpecUnit> plugin, Map<String, SpecUnit> rules, MeaningComparator meaning) {
        Map<String, List<String>> pluginOnly = new HashMap<>();
        Map<String, List<String>> rulesOnly = new HashMap<>();
        plugin.forEach((key, unit) -> {
            if (!rules.containsKey(key)) {
                pluginOnly.computeIfAbsent(meaning.mappedKey(unit), k -> new ArrayList<>()).add(key);
            }
        });
        rules.forEach((key, unit) -> {
            if (!plugin.containsKey(key)) {
                rulesOnly.computeIfAbsent(meaning.mappedKey(unit), k -> new ArrayList<>()).add(key);
            }
        });
        Map<String, String> renamedRules = new HashMap<>(); // plugin key → rules key
        pluginOnly.forEach((mapped, keys) -> {
            List<String> other = rulesOnly.get(mapped);
            if (keys.size() == 1 && other != null && other.size() == 1) {
                renamedRules.put(keys.get(0), other.get(0));
            }
        });
        Set<String> pairedRules = new HashSet<>(renamedRules.values());

        TreeMap<String, String[]> ordered = new TreeMap<>(SpecDiff::compareKeys);
        plugin.keySet().forEach(key ->
                ordered.put(key, new String[] {key, rules.containsKey(key) ? key : renamedRules.get(key)}));
        rules.keySet().forEach(key -> {
            if (!plugin.containsKey(key) && !pairedRules.contains(key)) {
                ordered.put(key, new String[] {null, key});
            }
        });
        return new ArrayList<>(ordered.values());
    }

    private static UnitComparison compareUnit(SpecUnit p, SpecUnit r, MeaningComparator meaning, Counts counts) {
        Map<String, String> pc = p == null ? Map.of() : p.clauses();
        Map<String, String> rc = r == null ? Map.of() : r.clauses();
        List<String> equal = new ArrayList<>();
        List<String> onlyPlugin = new ArrayList<>();
        List<String> onlyRules = new ArrayList<>();
        pc.forEach((canonical, display) -> (rc.containsKey(canonical) ? equal : onlyPlugin).add(display));
        rc.forEach((canonical, display) -> {
            if (!pc.containsKey(canonical)) {
                onlyRules.add(display);
            }
        });
        counts.clausesEqual += equal.size();
        counts.clausesOnlyPlugin += onlyPlugin.size();
        counts.clausesOnlyRules += onlyRules.size();

        String key = p != null ? p.key() : r.key();
        String rulesKey = p != null && r != null && !p.key().equals(r.key()) ? r.key() : null;
        String status;
        if (r == null) {
            status = "ONLY_PLUGIN";
        } else if (p == null) {
            status = "ONLY_RULES";
        } else if (rulesKey == null && onlyPlugin.isEmpty() && onlyRules.isEmpty()) {
            status = "EQUAL";
        } else {
            status = "DIFFERENT";
        }
        counts.byStatus.merge(status, 1, Integer::sum);
        int clauseCount = equal.size() + onlyPlugin.size() + onlyRules.size();
        if ("EQUAL".equals(status)) {
            return new UnitComparison(key, status, clauseCount, null, null, null, null);
        }

        MeaningComparator.Result result = meaning.compare(p, r, onlyPlugin, onlyRules);
        if (MeaningComparator.FORM.equals(result.difference())) {
            counts.sameMeaning++;
        } else {
            counts.meaningByStatus.merge(status, 1, Integer::sum);
        }
        counts.clausesMeaningPlugin += result.meaningPlugin().size();
        counts.clausesMeaningRules += result.meaningRules().size();
        counts.clausesSameMeaning += onlyPlugin.size() - result.meaningPlugin().size()
                + onlyRules.size() - result.meaningRules().size();
        ClauseSets clauses = new ClauseSets(equal, onlyPlugin, onlyRules, result.meaningPlugin(), result.meaningRules());
        return new UnitComparison(
                key, status, clauseCount, clauses, rulesKey, result.difference(), result.formRules());
    }

    /** Running totals of {@link #compare}. */
    private static final class Counts {
        final Map<String, Integer> byStatus = new HashMap<>();
        final Map<String, Integer> meaningByStatus = new HashMap<>();
        int sameMeaning;
        int clausesEqual;
        int clausesOnlyPlugin;
        int clausesOnlyRules;
        int clausesSameMeaning;
        int clausesMeaningPlugin;
        int clausesMeaningRules;

        SpecSummary summary(int units) {
            return new SpecSummary(
                    units,
                    byStatus.getOrDefault("EQUAL", 0),
                    byStatus.getOrDefault("DIFFERENT", 0),
                    byStatus.getOrDefault("ONLY_PLUGIN", 0),
                    byStatus.getOrDefault("ONLY_RULES", 0),
                    clausesEqual,
                    clausesOnlyPlugin,
                    clausesOnlyRules,
                    sameMeaning,
                    meaningByStatus.getOrDefault("DIFFERENT", 0),
                    meaningByStatus.getOrDefault("ONLY_PLUGIN", 0),
                    meaningByStatus.getOrDefault("ONLY_RULES", 0),
                    clausesSameMeaning,
                    clausesMeaningPlugin,
                    clausesMeaningRules);
        }
    }

    /** Names of the B machines of a project: the {@code .bxml} files under its {@code bdp} folder. */
    public static Set<String> machineNames(Path bdpDir) throws IOException {
        Set<String> out = new TreeSet<>();
        if (!Files.isDirectory(bdpDir)) {
            return out;
        }
        try (java.util.stream.Stream<Path> files = Files.walk(bdpDir)) {
            files.map(f -> f.getFileName().toString())
                    .filter(n -> n.endsWith(".bxml"))
                    .forEach(n -> out.add(n.substring(0, n.length() - ".bxml".length())));
        }
        return out;
    }

    static Map<String, SpecUnit> extractUnits(List<Path> files) throws IOException {
        Map<String, SpecUnit> byKey = new LinkedHashMap<>();
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            List<SpecUnit> units =
                    file.getFileName().toString().equals(GHOST_CI)
                            ? GhostCiExtractor.extract(text)
                            : AcslUnitExtractor.extract(text, "");
            for (SpecUnit unit : units) {
                SpecUnit existing = byKey.putIfAbsent(unit.key(), unit);
                if (existing != null) {
                    existing.mergeFrom(unit);
                }
            }
        }
        return byKey;
    }

    private static Map<String, String> textDiffs(List<Path> pluginFiles, List<Path> rulesFiles) throws IOException {
        Map<String, Path> rulesByName = byName(rulesFiles);
        Map<String, String> diffs = new TreeMap<>();
        for (Path pluginFile : pluginFiles) {
            String name = pluginFile.getFileName().toString();
            Path rulesFile = rulesByName.get(name);
            if (rulesFile == null) {
                continue;
            }
            List<String> a = normalizedLines(pluginFile);
            List<String> b = normalizedLines(rulesFile);
            Patch<String> patch = DiffUtils.diff(a, b);
            if (patch.getDeltas().isEmpty()) {
                diffs.put(name, "");
            } else {
                diffs.put(
                        name,
                        String.join(
                                "\n",
                                UnifiedDiffUtils.generateUnifiedDiff(
                                        "plugin/" + name, "rules/" + name, a, patch, 3)));
            }
        }
        return diffs;
    }

    /** Lines with whitespace collapsed; blank lines dropped. */
    static List<String> normalizedLines(Path file) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String normalized = line.strip().replaceAll("\\s+", " ");
            if (!normalized.isEmpty()) {
                out.add(normalized);
            }
        }
        return out;
    }

    private static Map<String, List<String>> filesOnlyIn(List<Path> pluginFiles, List<Path> rulesFiles) {
        Map<String, Path> plugin = byName(pluginFiles);
        Map<String, Path> rules = byName(rulesFiles);
        Map<String, List<String>> out = new LinkedHashMap<>();
        out.put("plugin", plugin.keySet().stream().filter(n -> !rules.containsKey(n)).sorted().toList());
        out.put("rules", rules.keySet().stream().filter(n -> !plugin.containsKey(n)).sorted().toList());
        return out;
    }

    private static Map<String, Path> byName(List<Path> files) {
        Map<String, Path> out = new LinkedHashMap<>();
        for (Path f : files) {
            out.put(f.getFileName().toString(), f);
        }
        return out;
    }

    /** Orders keys by kind (includes first, then declarations, axiomatics, functions, ghost), then name. */
    private static int compareKeys(String a, String b) {
        return Comparator.<String>comparingInt(SpecDiff::kindRank)
                .thenComparing(Comparator.naturalOrder())
                .compare(a, b);
    }

    private static int kindRank(String key) {
        String kind = key.substring(0, key.indexOf(':'));
        boolean ghost = kind.startsWith(GhostCiExtractor.KIND_PREFIX);
        String base = ghost ? kind.substring(GhostCiExtractor.KIND_PREFIX.length()) : kind;
        int rank = KIND_ORDER.indexOf(base);
        return (ghost ? 100 : 0) + (rank < 0 ? KIND_ORDER.size() : rank);
    }
}
