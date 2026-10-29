package com.example.comparator.gen;

import com.example.B2ACSLPipeline;
import com.example.B2ACSLPipeline.ProjectStructure;
import com.example.FramaCRunner;
import com.example.SpecificationTypesCollector;
import com.example.comparator.Side;
import com.example.comparator.Workspace;
import com.example.comparator.spec.SpecDiff;
import com.example.comparator.util.FileTrees;
import com.example.comparator.util.ProcessRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B2ACSLRules (ATL), as a separate process: {@code java -jar rules/dist/b2acsl.jar <bdp> <lang/c>}.
 * Running it out of process keeps the OSGi/EMF/ATL classes of its fat jar off the plugin's
 * classpath.
 *
 * <p>After the translation it prepares what the plugin pipeline would: the B2ACSLLib under {@code
 * import/} (the generated includes are {@code include "import/..."}), {@code
 * specification_types.txt} and the {@code -acsl-import} order derived from the same SEES/IMPORTS
 * graphs.
 */
public final class RulesSpecGenerator implements SpecGenerator {

    private static final Pattern WARNING = Pattern.compile("(?m)^AVISO:[ \\t]*([^\\n]*)");
    private static final Pattern ERROR = Pattern.compile("(?m)^ERRO.*$");
    private static final Pattern INCLUDE = Pattern.compile("include\\s+\"([^\"]+)\"");

    private final Path rulesJar;
    private final Path libDir;
    private final int timeoutSeconds;

    public RulesSpecGenerator(Path rulesJar, Path libDir, int timeoutSeconds) {
        this.rulesJar = rulesJar;
        this.libDir = libDir;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public GeneratedSpec generate(Workspace workspace) {
        long start = System.nanoTime();
        if (!Files.isRegularFile(rulesJar)) {
            return GeneratedSpec.failed(
                    "NOT_AVAILABLE", -1, 0, List.of(),
                    "B2ACSLRules jar not found: " + rulesJar + " (run: make build-rules)");
        }
        Path bdp = workspace.bdp(Side.RULES);
        Path cDir = workspace.cDir(Side.RULES);
        try {
            ProcessRunner.Result run =
                    ProcessRunner.run(
                            List.of(ProcessRunner.javaExecutable(), "-jar", rulesJar.toString(), bdp.toString(), cDir.toString()),
                            workspace.sideRoot(Side.RULES),
                            workspace.sideRoot(Side.RULES).resolve("rules-translation.log"),
                            timeoutSeconds);
            List<String> warnings = matches(WARNING, run.output(), 1);
            if (run.timedOut()) {
                return GeneratedSpec.failed(
                        "TIMEOUT", -1, PluginSpecGenerator.elapsedMs(start), warnings,
                        "B2ACSLRules translation exceeded " + timeoutSeconds + "s");
            }
            if (run.exitCode() != 0) {
                String status = switch (run.exitCode()) {
                    case 2 -> "TRANSLATION_ERROR";
                    case 3 -> "IO_ERROR";
                    default -> "ERROR";
                };
                return GeneratedSpec.failed(
                        status, run.exitCode(), PluginSpecGenerator.elapsedMs(start), warnings,
                        String.join("\n", matches(ERROR, run.output(), 0)));
            }
            List<Path> mainAcsl = mainAcslFiles(cDir);
            if (mainAcsl.isEmpty()) {
                return GeneratedSpec.failed(
                        "TRANSLATION_ERROR", 0, PluginSpecGenerator.elapsedMs(start), warnings,
                        "B2ACSLRules produced no .acsl file");
            }
            long translationMs = PluginSpecGenerator.elapsedMs(start);
            return prepareForVerification(workspace, cDir, mainAcsl, warnings, translationMs);
        } catch (Exception e) {
            return GeneratedSpec.failed("ERROR", -1, PluginSpecGenerator.elapsedMs(start), List.of(), e.toString());
        }
    }

    private GeneratedSpec prepareForVerification(
            Workspace workspace, Path cDir, List<Path> mainAcsl, List<String> warnings, long translationMs)
            throws Exception {
        FileTrees.copyTree(libDir, cDir.resolve("import"), p -> true);

        ProjectStructure structure = B2ACSLPipeline.loadProjectStructure(workspace.bdp(Side.RULES));
        Path ghostCi = cDir.resolve(SpecDiff.GHOST_CI);
        List<Path> specFiles = SpecDiff.specFiles(cDir);
        List<String> texts = new ArrayList<>();
        for (Path f : specFiles) {
            texts.add(Files.readString(f, StandardCharsets.UTF_8));
        }
        List<String> types = SpecificationTypesCollector.collectUsedTypes(texts, structure.abstractMachineRoots());
        SpecificationTypesCollector.writeTypesList(cDir.resolve(SpecificationTypesCollector.OUTPUT_FILE_NAME), types);

        List<Path> cFiles =
                FileTrees.listFiles(cDir, FileTrees.hasExtension(".c")).stream()
                        .filter(p -> !p.getFileName().toString().equals(FramaCRunner.MERGED_CODE_FILE_NAME))
                        .toList();
        Set<String> topLevelNames = new LinkedHashSet<>(structure.topLevelMachineNames());
        List<Path> topLevel = mainAcsl.stream().filter(p -> topLevelNames.contains(baseName(p))).toList();
        if (topLevel.isEmpty()) {
            topLevel = mainAcsl;
        }
        List<Path> importOrder =
                FramaCRunner.orderAcslFilesForImport(
                        cFiles, cDir, structure.seesGraph(), structure.importsGraph(), topLevel, mainAcsl);
        return new GeneratedSpec(
                GeneratedSpec.OK,
                0,
                translationMs,
                cDir,
                importOrder,
                ghostCi,
                cFiles,
                types,
                specFiles.stream().map(p -> p.getFileName().toString()).toList(),
                warnings,
                null);
    }

    /** Generated {@code .acsl} files that no other generated file includes (e.g. not {@code connection.acsl}). */
    static List<Path> mainAcslFiles(Path cDir) throws IOException {
        List<Path> acsl = FileTrees.listFiles(cDir, FileTrees.hasExtension(".acsl"));
        Set<String> included = new LinkedHashSet<>();
        for (Path f : acsl) {
            Matcher m = INCLUDE.matcher(Files.readString(f, StandardCharsets.UTF_8));
            while (m.find()) {
                included.add(Path.of(m.group(1)).getFileName().toString());
            }
        }
        return acsl.stream().filter(p -> !included.contains(p.getFileName().toString())).toList();
    }

    private static String baseName(Path p) {
        String name = p.getFileName().toString();
        return name.endsWith(".acsl") ? name.substring(0, name.length() - ".acsl".length()) : name;
    }

    private static List<String> matches(Pattern pattern, String text, int group) {
        List<String> out = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            out.add(m.group(group).trim());
        }
        return out;
    }
}
