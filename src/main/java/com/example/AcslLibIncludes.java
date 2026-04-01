package com.example;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detecta símbolos da {@code ACSL_Lib} no texto ACSL gerado e produz linhas {@code include "..."}
 * para os ficheiros correspondentes em {@code src/main/resources/ACSL_Lib}. Usos de {@code NAT},
 * {@code NAT1} ou {@code BOOL} como identificadores incluem {@code set_functions/variables.acsl}.
 *
 * <p>Propriedades opcionais (JVM / {@code META-INF/b2acsl.properties}):
 * <ul>
 *   <li>{@code b2acsl.acslLibIncludeBase} — caminho até à pasta que contém {@code set_functions/},
 *       etc.</li>
 *   <li>{@code b2acsl.acslLibIncludeMiddle} — segmento opcional (ex. {@code import}).</li>
 *   <li>{@code b2acsl.acslLibSourceDir} — raiz no disco com o mesmo layout que {@code ACSL_Lib}
 *       (para {@code cp} e fecho transitivo sem JAR).</li>
 *   <li>{@code b2acsl.targetAcslDir} — pasta sob {@code user.dir} onde espelhar especificação e
 *       {@code import/} (default: {@code target/b2acsl-acsl}).</li>
 * </ul>
 *
 * <p>Durante a geração, os ficheiros necessários (funções + {@code include} transitivos, p.ex.
 * axiomas) são copiados para junto do {@code .acsl} e para {@code b2acsl.targetAcslDir}. Com
 * ficheiros no disco, usa-se o comando {@code cp}; em fallback (só classpath/JAR), usa-se
 * {@link Files#copy}.
 */
public final class AcslLibIncludes {

    private AcslLibIncludes() {}

    private static final Pattern INCLUDE_IN_LIB =
            Pattern.compile("include\\s+\"([^\"]+)\"\\s*;", Pattern.MULTILINE);

    /** Raiz da {@code ACSL_Lib}: tipos {@code Set}, {@code Tuple}, relações indexadas (sempre antes dos outros includes). */
    private static final String TYPES_LIB_REL = "types.acsl";

    /** Conjuntos globais {@code NAT}, {@code NAT1}, {@code BOOL} ({@code set_functions/variables.acsl}). */
    private static final String VARIABLES_LIB_REL = "set_functions/variables.acsl";

    /**
     * Referências a identificadores {@code NAT}, {@code NAT1} ou {@code BOOL} (não parte de nomes mais longos).
     */
    private static final Pattern GLOBAL_SET_CONSTANT_ID =
            Pattern.compile("(?<![A-Za-z0-9_])(?:NAT1|NAT|BOOL)(?![A-Za-z0-9_])");

    /**
     * Símbolo ACSL (nome antes de '(') → caminho relativo dentro de ACSL_Lib (usa '/').
     */
    private static final Map<String, String> SYMBOL_TO_FILE = Map.ofEntries(
            Map.entry("belongs", "set_functions/belongs.acsl"),
            Map.entry("not_belongs", "set_functions/belongs.acsl"),
            Map.entry("inclusion", "set_functions/inclusion.acsl"),
            Map.entry("set_union", "set_functions/union.acsl"),
            Map.entry("singleton", "set_functions/singleton.acsl"),
            Map.entry("empty", "set_functions/empty.acsl"),
            Map.entry("card", "set_functions/card.acsl"),
            Map.entry("is_finite", "set_functions/finite.acsl"),
            Map.entry("disjoint", "set_functions/disjoint.acsl"),
            Map.entry("intersection", "set_functions/intersection.acsl"),
            Map.entry("difference", "set_functions/difference.acsl"),
            Map.entry("pair", "set_functions/pair.acsl"),
            Map.entry("is_pow_of", "set_functions/pow.acsl"),
            Map.entry("equals", "set_functions/equals.acsl"),
            Map.entry("cartesian_product", "set_functions/cartesian_product.acsl"),
            Map.entry("dom", "relation_functions/domain.acsl"),
            Map.entry("relation_inverse", "relation_functions/inverse.acsl"),
            Map.entry("domain_restriction", "relation_functions/domain_restriction.acsl"),
            Map.entry("range_restriction", "relation_functions/range_restriction.acsl"),
            Map.entry("iSeq", "sequence_functions/iseq.acsl"),
            Map.entry("is_seq_of", "sequence_functions/is_seq_of.acsl"));

    /**
     * Ordem dos {@code include} para {@code set_functions/} (dependências lógicas da ACSL_Lib), depois
     * relações e sequências.
     */
    private static final List<String> FILE_ORDER = List.of(
            "set_functions/belongs.acsl",
            "set_functions/variables.acsl",
            "set_functions/empty.acsl",
            "set_functions/singleton.acsl",
            "set_functions/union.acsl",
            "set_functions/pair.acsl",
            "set_functions/intersection.acsl",
            "set_functions/difference.acsl",
            "set_functions/card.acsl",
            "set_functions/inclusion.acsl",
            "set_functions/pow.acsl",
            "set_functions/finite.acsl",
            "set_functions/equals.acsl",
            "set_functions/cartesian_product.acsl",
            "set_functions/disjoint.acsl",
            "relation_functions/domain.acsl",
            "relation_functions/range.acsl",
            "relation_functions/inverse.acsl",
            "relation_functions/domain_restriction.acsl",
            "relation_functions/range_restriction.acsl",
            "sequence_functions/iseq.acsl",
            "sequence_functions/is_seq_of.acsl",
            "sequence_functions/range.acsl");

    public static String formatIncludeBlock(String acslText) {
        List<String> lines = collectIncludeLines(acslText);
        if (lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }

    /**
     * Copia a biblioteca referenciada (fecho transitivo de {@code include} dentro de {@code ACSL_Lib})
     * para {@code generatedAcslFile.getParent()/…} e espelha o mesmo em {@link #resolveTargetAcslStagingRoot()}.
     * Se o diretório de saída do {@code .acsl} não for o alvo, copia também o ficheiro de especificação
     * para o alvo (com {@code cp} quando aplicável).
     */
    public static void copyReferencedLibraryFiles(String acslText, Path generatedAcslFile)
            throws IOException {
        List<String> seeds = orderedLibRelativePaths(acslText);
        if (seeds.isEmpty()) return;

        Path diskRoot = resolveAcslLibRootOnDisk();
        List<String> seedsWithTypes = new ArrayList<>(seeds.size() + 1);
        seedsWithTypes.add(TYPES_LIB_REL);
        seedsWithTypes.addAll(seeds);
        List<String> allRel = transitiveAcslLibPaths(diskRoot, seedsWithTypes);
        LinkedHashSet<String> toCopy = new LinkedHashSet<>();
        toCopy.add(TYPES_LIB_REL);
        toCopy.addAll(allRel);
        if (toCopy.stream().noneMatch(rel -> libFileExists(diskRoot, rel.replace('\\', '/')))) {
            return;
        }

        Path outParent = generatedAcslFile.toAbsolutePath().getParent().normalize();
        Path bundleOut = libraryBundleRootUnderOutput(outParent);
        Path targetRoot = resolveTargetAcslStagingRoot();
        Path bundleTarget = libraryBundleRootUnderOutput(targetRoot);
        boolean sameBundle =
                bundleOut.toAbsolutePath().normalize().equals(bundleTarget.toAbsolutePath().normalize());

        for (String rel : toCopy) {
            String n = rel.replace('\\', '/');
            if (!libFileExists(diskRoot, n)) {
                continue;
            }
            materializeLibFile(n, bundleOut.resolve(n), diskRoot);
            if (!sameBundle) {
                materializeLibFile(n, bundleTarget.resolve(n), diskRoot);
            }
        }

        if (!outParent.toAbsolutePath().normalize().equals(targetRoot.toAbsolutePath().normalize())) {
            Files.createDirectories(targetRoot);
            Path specDest = targetRoot.resolve(generatedAcslFile.getFileName());
            copyFileWithCpPreferred(generatedAcslFile, specDest);
        }
    }

    static List<String> collectIncludeLines(String acslText) {
        List<String> ordered = orderedLibRelativePaths(acslText);
        if (ordered.isEmpty()) return List.of();

        String base = propertyOrEmpty("b2acsl.acslLibIncludeBase");
        String middle = propertyOrEmpty("b2acsl.acslLibIncludeMiddle");

        List<String> lines = new ArrayList<>(ordered.size() + 1);
        lines.add(buildIncludeLine(base, middle, TYPES_LIB_REL));
        for (String rel : ordered) {
            lines.add(buildIncludeLine(base, middle, rel));
        }
        return lines;
    }

    private static List<String> orderedLibRelativePaths(String acslText) {
        LinkedHashSet<String> files = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : SYMBOL_TO_FILE.entrySet()) {
            if (containsSymbolCall(acslText, e.getKey())) {
                files.add(e.getValue());
            }
        }
        addRangeIncludesForRan(acslText, files);
        if (GLOBAL_SET_CONSTANT_ID.matcher(acslText).find()) {
            files.add(VARIABLES_LIB_REL);
        }
        if (files.isEmpty()) return List.of();

        List<String> ordered = new ArrayList<>();
        for (String path : FILE_ORDER) {
            if (files.contains(path)) ordered.add(path);
        }
        for (String f : files) {
            if (!ordered.contains(f)) ordered.add(f);
        }
        return List.copyOf(ordered);
    }

    /**
     * B {@code ran} traduz-se para {@code ran(...)} em ACSL: há duas libs homónimas —
     * {@code relation_functions/range.acsl} ({@code ran(Relation_*)}) e
     * {@code sequence_functions/range.acsl} ({@code ran(\list<...>)}).
     */
    private static void addRangeIncludesForRan(String acslText, LinkedHashSet<String> files) {
        if (!containsSymbolCall(acslText, "ran")) {
            return;
        }
        boolean listCase = acslText.contains("\\list<");
        boolean relationCase = acslText.contains("Relation_");
        if (listCase) {
            files.add("sequence_functions/range.acsl");
        }
        if (relationCase) {
            files.add("relation_functions/range.acsl");
        }
        if (!listCase && !relationCase) {
            files.add("relation_functions/range.acsl");
        }
    }

    private static List<String> transitiveAcslLibPaths(Path diskRoot, List<String> seeds)
            throws IOException {
        ArrayDeque<String> q = new ArrayDeque<>();
        LinkedHashSet<String> all = new LinkedHashSet<>();
        for (String s : seeds) {
            String n = s.replace('\\', '/');
            if (libFileExists(diskRoot, n)) {
                q.add(n);
                all.add(n);
            }
        }
        while (!q.isEmpty()) {
            String rel = q.removeFirst();
            String text = readAcslLibText(diskRoot, rel);
            if (text == null) continue;
            Path parent = Path.of(rel).getParent();
            if (parent == null) {
                parent = Path.of("");
            }
            Matcher m = INCLUDE_IN_LIB.matcher(text);
            while (m.find()) {
                String inc = m.group(1).trim();
                if (inc.isEmpty() || inc.startsWith("/")) continue;
                if (inc.startsWith("import/")) continue;
                Path child = parent.resolve(inc).normalize();
                String childStr = child.toString().replace('\\', '/');
                if (childStr.startsWith("..")) continue;
                if (!libFileExists(diskRoot, childStr)) continue;
                if (all.add(childStr)) {
                    q.add(childStr);
                }
            }
        }
        return new ArrayList<>(all);
    }

    private static boolean libFileExists(Path diskRoot, String rel) {
        if (diskRoot != null && Files.isRegularFile(diskRoot.resolve(rel))) {
            return true;
        }
        return openClasspathAcslLib(rel) != null;
    }

    private static String readAcslLibText(Path diskRoot, String rel) throws IOException {
        if (diskRoot != null) {
            Path f = diskRoot.resolve(rel);
            if (Files.isRegularFile(f)) {
                return Files.readString(f);
            }
        }
        try (InputStream in = openClasspathAcslLib(rel)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static InputStream openClasspathAcslLib(String rel) {
        String slash = "/ACSL_Lib/" + rel;
        InputStream in = AcslLibIncludes.class.getResourceAsStream(slash);
        if (in != null) return in;
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        if (tccl != null) {
            in = tccl.getResourceAsStream("ACSL_Lib/" + rel);
            if (in != null) return in;
        }
        return ClassLoader.getSystemResourceAsStream("ACSL_Lib/" + rel);
    }

    private static void materializeLibFile(String rel, Path dest, Path diskRoot) throws IOException {
        Files.createDirectories(dest.getParent());
        Path srcDisk = diskRoot != null ? diskRoot.resolve(rel) : null;
        if (srcDisk != null && Files.isRegularFile(srcDisk)) {
            runCp(srcDisk, dest);
            return;
        }
        try (InputStream in = openClasspathAcslLib(rel)) {
            if (in == null) {
                System.err.println("[B2ACSL] ACSL_Lib em falta no classpath: " + rel);
                return;
            }
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void copyFileWithCpPreferred(Path src, Path dest) throws IOException {
        Files.createDirectories(dest.getParent());
        if (Files.isRegularFile(src)) {
            runCp(src.toAbsolutePath(), dest.toAbsolutePath());
        } else {
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Invoca {@code cp origem destino} (Linux/macOS). */
    private static void runCp(Path absoluteSrc, Path absoluteDest) throws IOException {
        ProcessBuilder pb =
                new ProcessBuilder(
                        "cp",
                        absoluteSrc.toString(),
                        absoluteDest.toString());
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process proc = pb.start();
        try {
            boolean finished = proc.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                throw new IOException("cp: timeout");
            }
            if (proc.exitValue() != 0) {
                String err = new String(proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new IOException("cp falhou (" + proc.exitValue() + "): " + err.trim());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("cp interrompido", e);
        }
    }

    static Path libraryBundleRootUnderOutput(Path outputDirectory) {
        Path out = outputDirectory.toAbsolutePath().normalize();
        String middle = propertyOrEmpty("b2acsl.acslLibIncludeMiddle");
        if (middle.isBlank()) {
            return out;
        }
        if (middle.contains("..") || Path.of(middle).isAbsolute()) {
            return out.resolve("import");
        }
        return out.resolve(middle);
    }

    private static Path resolveAcslLibRootOnDisk() {
        String p = propertyOrEmpty("b2acsl.acslLibSourceDir");
        if (!p.isBlank()) {
            Path x = Path.of(p).toAbsolutePath().normalize();
            if (Files.isDirectory(x.resolve("set_functions"))) {
                return x;
            }
        }
        Path dev =
                Path.of(System.getProperty("user.dir", "."))
                        .resolve("src/main/resources/ACSL_Lib")
                        .toAbsolutePath()
                        .normalize();
        if (Files.isDirectory(dev.resolve("set_functions"))) {
            return dev;
        }
        return null;
    }

    private static Path resolveTargetAcslStagingRoot() {
        String p = propertyOrEmpty("b2acsl.targetAcslDir");
        if (p.isBlank()) {
            p = "target/b2acsl-acsl";
        }
        Path base = Path.of(p);
        if (!base.isAbsolute()) {
            base = Path.of(System.getProperty("user.dir", ".")).resolve(base);
        }
        return base.toAbsolutePath().normalize();
    }

    private static Properties cachedProps;

    private static String propertyOrEmpty(String key) {
        String sys = System.getProperty(key);
        if (sys != null && !sys.isBlank()) return sys.trim();
        try {
            if (cachedProps == null) {
                cachedProps = new Properties();
                try (InputStream in = AcslLibIncludes.class.getResourceAsStream("/META-INF/b2acsl.properties")) {
                    if (in != null) cachedProps.load(in);
                }
            }
            String v = cachedProps.getProperty(key);
            if (v != null && !v.isBlank()) return v.trim();
        } catch (IOException ignored) {
        }
        return "";
    }

    static String buildIncludeLine(String base, String middle, String relativePath) {
        String rel = relativePath.replace('\\', '/');
        StringBuilder path = new StringBuilder();
        if (!base.isEmpty()) {
            String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
            path.append(b);
        }
        if (!middle.isEmpty()) {
            if (path.length() > 0) path.append('/');
            path.append(middle);
        }
        if (!rel.isEmpty()) {
            if (path.length() > 0) path.append('/');
            path.append(rel);
        }
        return "include \"" + path + "\";";
    }

    private static boolean containsSymbolCall(String text, String symbol) {
        Pattern pat =
                Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(symbol) + "\\s*\\(");
        return pat.matcher(text).find();
    }
}
