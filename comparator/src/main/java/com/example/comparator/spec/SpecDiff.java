package com.example.comparator.spec;

import com.example.comparator.report.Reports.ClauseSets;
import com.example.comparator.report.Reports.SpecComparison;
import com.example.comparator.report.Reports.SpecSummary;
import com.example.comparator.report.Reports.UnitComparison;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Compares the specification files of the two tools: structurally (units and clauses, see {@link
 * AcslUnitExtractor}/{@link GhostCiExtractor}) and textually (whitespace-normalized unified diff
 * of files with the same name).
 *
 * <p>Units of all files of one side are merged before comparing, because B2ACSLPlugin spreads
 * content across auxiliary files ({@code _M_lib_includes.acsl}, {@code M_tuple_types.acsl}, ...)
 * that B2ACSLRules does not have.
 */
public final class SpecDiff {

    public static final String GHOST_CI = "ghost_operations.ci";

    private static final List<String> KIND_ORDER =
            List.of("include", "type", "logic", "predicate", "inductive", "axiom", "lemma", "axiomatic", "function");

    private SpecDiff() {}

    /** Specification files of a directory: top-level {@code *.acsl} and {@code ghost_operations.ci}. */
    public static List<Path> specFiles(Path dir) throws IOException {
        return FileTrees.listFiles(
                dir, p -> p.getFileName().toString().endsWith(".acsl") || p.getFileName().toString().equals(GHOST_CI));
    }

    public static SpecComparison compare(Path pluginSpecDir, Path rulesSpecDir) throws IOException {
        List<Path> pluginFiles = specFiles(pluginSpecDir);
        List<Path> rulesFiles = specFiles(rulesSpecDir);
        Map<String, SpecUnit> plugin = extractUnits(pluginFiles);
        Map<String, SpecUnit> rules = extractUnits(rulesFiles);

        TreeSet<String> keys = new TreeSet<>(SpecDiff::compareKeys);
        keys.addAll(plugin.keySet());
        keys.addAll(rules.keySet());

        List<UnitComparison> units = new ArrayList<>();
        int[] unitCounts = new int[4]; // equal, different, onlyPlugin, onlyRules
        int clausesEqual = 0;
        int clausesOnlyPlugin = 0;
        int clausesOnlyRules = 0;
        for (String key : keys) {
            Map<String, String> p = plugin.containsKey(key) ? plugin.get(key).clauses() : Map.of();
            Map<String, String> r = rules.containsKey(key) ? rules.get(key).clauses() : Map.of();
            List<String> equal = new ArrayList<>();
            List<String> onlyPlugin = new ArrayList<>();
            List<String> onlyRules = new ArrayList<>();
            p.forEach((canonical, display) -> (r.containsKey(canonical) ? equal : onlyPlugin).add(display));
            r.forEach((canonical, display) -> {
                if (!p.containsKey(canonical)) {
                    onlyRules.add(display);
                }
            });
            clausesEqual += equal.size();
            clausesOnlyPlugin += onlyPlugin.size();
            clausesOnlyRules += onlyRules.size();

            String status;
            if (!rules.containsKey(key)) {
                status = "ONLY_PLUGIN";
                unitCounts[2]++;
            } else if (!plugin.containsKey(key)) {
                status = "ONLY_RULES";
                unitCounts[3]++;
            } else if (onlyPlugin.isEmpty() && onlyRules.isEmpty()) {
                status = "EQUAL";
                unitCounts[0]++;
            } else {
                status = "DIFFERENT";
                unitCounts[1]++;
            }
            int clauseCount = equal.size() + onlyPlugin.size() + onlyRules.size();
            ClauseSets clauses = "EQUAL".equals(status) ? null : new ClauseSets(equal, onlyPlugin, onlyRules);
            units.add(new UnitComparison(key, status, clauseCount, clauses));
        }

        SpecSummary summary =
                new SpecSummary(
                        keys.size(),
                        unitCounts[0],
                        unitCounts[1],
                        unitCounts[2],
                        unitCounts[3],
                        clausesEqual,
                        clausesOnlyPlugin,
                        clausesOnlyRules);
        return new SpecComparison(
                summary, units, textDiffs(pluginFiles, rulesFiles), filesOnlyIn(pluginFiles, rulesFiles));
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
