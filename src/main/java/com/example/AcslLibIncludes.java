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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detecta símbolos da {@code B2ACSLLib} no texto ACSL gerado e produz linhas {@code include "..."}
 * para os ficheiros correspondentes. Toda a resolução de dependências é feita exclusivamente a
 * partir de {@code src/main/resources/b2acsl/symbol_dependency_map.json} (gerado por
 * {@code scripts/generate_acsl_symbol_dependency_map.py}) via {@link AcslLibSymbolDependencyMap}.
 *
 * <p>Para cada símbolo {@code s} presente no texto da especificação gerada,
 * {@link AcslLibSymbolDependencyMap#directIncludeClosureForSymbol(String)} devolve o fecho
 * transitivo de ficheiros da biblioteca necessários (includes explícitos + dependências implícitas
 * detectadas pelo script Python). Símbolos sobrecarregados (ex.: {@code first}, {@code ran},
 * {@code equals}) têm todos os seus ficheiros definidores incluídos — são sobrecargas de tipo
 * distintas e coexistem sem conflito em ACSL.
 *
 * <p>Usos de {@code NAT}, {@code NAT1} ou {@code BOOL} como identificadores incluem
 * {@code set_functions/variables.acsl}.
 *
 * <p>Propriedades opcionais (JVM / {@code META-INF/b2acsl.properties}):
 * <ul>
 *   <li>{@code b2acsl.acslLibIncludeBase} — caminho até à pasta que contém {@code set_functions/}.</li>
 *   <li>{@code b2acsl.acslLibIncludeMiddle} — segmento opcional (ex. {@code import}).</li>
 *   <li>{@code b2acsl.acslLibSourceDir} — raiz no disco com o mesmo layout que {@code B2ACSLLib}.</li>
 *   <li>{@code b2acsl.targetAcslDir} — pasta de saída (default: {@code target/b2acsl-acsl}).</li>
 * </ul>
 *
 * <p>O bloco {@code include} inserido na especificação lista o fecho transitivo dos ficheiros da lib
 * (ordem estável com {@link #FILE_ORDER}), exceto ficheiros em pastas {@code *_axioms/} — esses
 * entram apenas via {@code include} nos ficheiros-pai na cópia {@code import/}.
 */
public final class AcslLibIncludes {

    private AcslLibIncludes() {}

    /** Ficheiros da lib que não devem aparecer no bloco include do .acsl gerado. */
    private static final Set<String> OMIT_FROM_EMITTED_SPEC_INCLUDES =
            Set.of("function_functions/singleton.acsl");

    private static final Pattern INCLUDE_IN_LIB =
            Pattern.compile("include\\s+\"([^\"]+)\"\\s*;", Pattern.MULTILINE);

    private static final Pattern FIRST_AXIOMATIC_NAME_IN_LIB_FILE =
            Pattern.compile("axiomatic\\s+(\\w+)\\s*\\{");

    /** {@code types.acsl} — tipos base {@code Set}, {@code Tuple}, {@code Relation}, {@code Function}. */
    private static final String TYPES_LIB_REL = "types.acsl";

    /** Conjuntos globais {@code NAT}, {@code NAT1}, {@code BOOL}. */
    private static final String VARIABLES_LIB_REL = "set_functions/variables.acsl";

    private static final Pattern GLOBAL_SET_CONSTANT_ID =
            Pattern.compile("(?<![A-Za-z0-9_])(?:NAT1|NAT|BOOL)(?![A-Za-z0-9_])");

    /**
     * Ordem preferida dos {@code include} para o {@code .acsl} gerado: dependências lógicas
     * da {@code B2ACSLLib} (tuple → set → relation → function → sequence).
     */
    private static final List<String> FILE_ORDER = List.of(
            "types.acsl",
            "tuple_functions/tuple_couple.acsl",
            "tuple_functions/accessors.acsl",
            "tuple_functions/equals.acsl",
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
            "relation_functions/singleton.acsl",
            "relation_functions/domain.acsl",
            "relation_functions/range.acsl",
            "relation_functions/inverse.acsl",
            "relation_functions/apply.acsl",
            "relation_functions/domain_restriction.acsl",
            "relation_functions/range_restriction.acsl",
            "function_functions/is_function.acsl",
            "function_functions/is_function_of.acsl",
            "function_functions/is_partial.acsl",
            "function_functions/is_total.acsl",
            "function_functions/apply.acsl",
            "function_functions/is_injective.acsl",
            "function_functions/is_surjective.acsl",
            "function_functions/is_bijective.acsl",
            "function_functions/becomes_element_of.acsl",
            "function_functions/array_to_function.acsl",
            "sequence_functions/first.acsl",
            "sequence_functions/front.acsl",
            "sequence_functions/is_sequence.acsl",
            "sequence_functions/length.acsl",
            "sequence_functions/function_to_list.acsl",
            "sequence_functions/get.acsl",
            "sequence_functions/is_seq_of.acsl",
            "sequence_functions/range.acsl",
            "sequence_functions/iseq.acsl",
            "sequence_functions/last.acsl",
            "sequence_functions/list_to_function.acsl",
            "sequence_functions/restrict_front.acsl",
            "sequence_functions/restrict_tail.acsl",
            "sequence_functions/tail.acsl",
            "sequence_axioms/utils_axioms.acsl"
    );

    public static String formatIncludeBlock(String acslText) {
        return formatIncludeBlock(acslText, null);
    }

    /**
     * Como {@link #formatIncludeBlock(String)}, mas concatena {@code extraTextForSymbolScan} só para
     * detetar símbolos da biblioteca (includes e {@link #copyReferencedLibraryFiles}); esse texto
     * não entra no ficheiro gerado.
     */
    public static String formatIncludeBlock(String acslText, String extraTextForSymbolScan) {
        List<String> lines = collectIncludeLines(acslText, extraTextForSymbolScan);
        if (lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }

    /**
     * Caminhos relativos na {@code B2ACSLLib} no fecho transitivo de {@code include}.
     */
    public static Set<String> transitiveLibRelativePathsForScan(String acslText, String extraTextForSymbolScan)
            throws IOException {
        String scan = acslText == null ? "" : acslText;
        if (extraTextForSymbolScan != null && !extraTextForSymbolScan.isBlank()) {
            scan = scan + "\n" + extraTextForSymbolScan;
        }
        List<String> seeds = orderedLibRelativePaths(scan);
        List<String> seedsWithTypes = new ArrayList<>(seeds.size() + 1);
        seedsWithTypes.add(TYPES_LIB_REL);
        seedsWithTypes.addAll(seeds);
        Path diskRoot = resolveAcslLibRootOnDisk();
        List<String> all = transitiveAcslLibPaths(diskRoot, seedsWithTypes);
        return new LinkedHashSet<>(all);
    }

    /**
     * Símbolos da biblioteca conhecidos cujo ficheiro definidor está no fecho transitivo calculado
     * por {@link #transitiveLibRelativePathsForScan}. Usa {@link AcslLibSymbolDependencyMap} para
     * o mapeamento file → symbols.
     */
    public static Set<String> allowedLibSymbolsForTransitiveIncludes(
            String acslText, String extraTextForSymbolScan) throws IOException {
        Set<String> paths = transitiveLibRelativePathsForScan(acslText, extraTextForSymbolScan);
        if (paths.isEmpty()) {
            return Set.of();
        }
        AcslLibSymbolDependencyMap m = AcslLibSymbolDependencyMap.instance();
        Set<String> out = new LinkedHashSet<>();
        for (String sym : m.allKnownSymbols()) {
            String defFile = m.definingFile(sym);
            if (defFile != null && paths.contains(defFile)) {
                out.add(sym);
            }
        }
        return out;
    }

    /**
     * Nomes dos blocos {@code axiomatic} definidos nos ficheiros de {@link #FILE_ORDER},
     * na mesma ordem que os {@code include} da lib.
     */
    public static List<String> orderedLibFunctionAxiomaticNames() throws IOException {
        List<String> names = new ArrayList<>();
        Path diskRoot = resolveAcslLibRootOnDisk();
        for (String rel : FILE_ORDER) {
            String text = readAcslLibText(diskRoot, rel);
            if (text == null) {
                continue;
            }
            Matcher m = FIRST_AXIOMATIC_NAME_IN_LIB_FILE.matcher(text);
            if (m.find()) {
                names.add(m.group(1));
            }
        }
        return List.copyOf(names);
    }

    /**
     * Copia a biblioteca referenciada (fecho transitivo de {@code include}) para
     * {@code generatedAcslFile.getParent()/…} e espelha em {@link #resolveTargetAcslStagingRoot()}.
     */
    public static void copyReferencedLibraryFiles(String acslText, Path generatedAcslFile)
            throws IOException {
        copyReferencedLibraryFiles(acslText, generatedAcslFile, null);
    }

    /**
     * @param extraTextForSymbolScan texto extra só para descobrir ficheiros da lib a copiar
     */
    public static void copyReferencedLibraryFiles(
            String acslText, Path generatedAcslFile, String extraTextForSymbolScan) throws IOException {
        String scan = acslText == null ? "" : acslText;
        if (extraTextForSymbolScan != null && !extraTextForSymbolScan.isBlank()) {
            scan = scan + "\n" + extraTextForSymbolScan;
        }
        List<String> seeds = orderedLibRelativePaths(scan);
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
        return collectIncludeLines(acslText, null);
    }

    static List<String> collectIncludeLines(String acslText, String extraTextForSymbolScan) {
        String scan = acslText == null ? "" : acslText;
        if (extraTextForSymbolScan != null && !extraTextForSymbolScan.isBlank()) {
            scan = scan + "\n" + extraTextForSymbolScan;
        }
        List<String> seeds = orderedLibRelativePaths(scan);
        if (seeds.isEmpty()) return List.of();

        List<String> seedsWithTypes = new ArrayList<>(seeds.size() + 1);
        seedsWithTypes.add(TYPES_LIB_REL);
        seedsWithTypes.addAll(seeds);

        Path diskRoot = resolveAcslLibRootOnDisk();
        List<String> transitive;
        try {
            transitive = transitiveAcslLibPaths(diskRoot, seedsWithTypes);
        } catch (IOException e) {
            transitive = seedsWithTypes;
        }

        transitive = omitAxiomFolderIncludesFromEmittedSpec(transitive);

        List<String> merged = mergeTransitiveIncludesForEmit(transitive);

        String base = propertyOrEmpty("b2acsl.acslLibIncludeBase");
        String middle = propertyOrEmpty("b2acsl.acslLibIncludeMiddle");

        List<String> lines = new ArrayList<>(merged.size());
        for (String rel : merged) {
            lines.add(buildIncludeLine(base, middle, rel));
        }
        return lines;
    }

    /**
     * Ordena o fecho transitivo para emissão: {@link #TYPES_LIB_REL} primeiro, depois entradas de
     * {@link #FILE_ORDER} presentes no fecho, depois o restante na ordem do BFS.
     */
    private static List<String> mergeTransitiveIncludesForEmit(List<String> transitiveBfsOrder) {
        LinkedHashSet<String> present = new LinkedHashSet<>(transitiveBfsOrder);
        List<String> out = new ArrayList<>(transitiveBfsOrder.size());
        if (present.contains(TYPES_LIB_REL)) {
            out.add(TYPES_LIB_REL);
        }
        for (String path : FILE_ORDER) {
            if (present.contains(path) && !TYPES_LIB_REL.equals(path)) {
                out.add(path);
            }
        }
        for (String f : transitiveBfsOrder) {
            if (!out.contains(f)) {
                out.add(f);
            }
        }
        return out;
    }

    /**
     * Não emite {@code include "…_axioms/…"} na especificação gerada — esses axiomas são puxados
     * pelos {@code include} relativos dentro dos ficheiros de funções/definições.
     */
    private static List<String> omitAxiomFolderIncludesFromEmittedSpec(List<String> relPaths) {
        List<String> out = new ArrayList<>(relPaths.size());
        for (String rel : relPaths) {
            String normalized = rel == null ? "" : rel.replace('\\', '/');
            if (!isUnderLibAxiomFolder(normalized)
                    && !OMIT_FROM_EMITTED_SPEC_INCLUDES.contains(normalized)) {
                out.add(rel);
            }
        }
        return out;
    }

    /** {@code true} para caminhos sob {@code *_axioms/} na B2ACSLLib. */
    static boolean isUnderLibAxiomFolder(String relativeLibPath) {
        if (relativeLibPath == null || relativeLibPath.isBlank()) {
            return false;
        }
        return relativeLibPath.replace('\\', '/').contains("_axioms/");
    }

    /**
     * Resolve os caminhos relativos de ficheiros da biblioteca necessários para o texto ACSL dado.
     * Usa exclusivamente o {@link AcslLibSymbolDependencyMap} (gerado a partir da análise da
     * {@code B2ACSLLib}); não contém casos especiais por símbolo.
     */
    private static List<String> orderedLibRelativePaths(String acslText) {
        AcslLibSymbolDependencyMap m = AcslLibSymbolDependencyMap.instance();
        LinkedHashSet<String> files = new LinkedHashSet<>();

        if (m.isLoaded()) {
            for (String sym : m.allKnownSymbols()) {
                if (containsSymbolCall(acslText, sym)) {
                    files.addAll(m.directIncludeClosureForSymbol(sym));
                }
            }
        }

        if (GLOBAL_SET_CONSTANT_ID.matcher(acslText).find()) {
            files.add(VARIABLES_LIB_REL);
        }

        if (files.isEmpty()) return List.of();

        files.add(TYPES_LIB_REL);

        Path diskRoot = resolveAcslLibRootOnDisk();
        files.removeIf(rel -> !libFileExists(diskRoot, rel));
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
        String slash = B2AcslLibraryPaths.classpathResource(rel);
        InputStream in = AcslLibIncludes.class.getResourceAsStream(slash);
        if (in != null) return in;
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        if (tccl != null) {
            in = tccl.getResourceAsStream(B2AcslLibraryPaths.classpathResourceStreamName(rel));
            if (in != null) return in;
        }
        return ClassLoader.getSystemResourceAsStream(B2AcslLibraryPaths.classpathResourceStreamName(rel));
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
                System.err.println("[B2ACSL] B2ACSLLib em falta no classpath: " + rel);
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
        Path dev = B2AcslLibraryPaths.devRoot();
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

    /**
     * Identifica chamadas {@code symbol(} no texto. O operador E-ACSL {@code \\length(...)} sobre
     * {@code \\list} não é confundido com {@code length} da biblioteca (sequência como função).
     */
    private static boolean containsSymbolCall(String text, String symbol) {
        if ("length".equals(symbol)) {
            return containsLibraryLengthCall(text);
        }
        Pattern pat =
                Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(symbol) + "\\s*\\(");
        return pat.matcher(text).find();
    }

    /**
     * {@code true} se existir {@code length(} como símbolo da lib; {@code false} para apenas
     * {@code \\length(} (comprimento de lista em lógica ACSL).
     */
    private static boolean containsLibraryLengthCall(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        Matcher m = Pattern.compile("(?<![A-Za-z0-9_])length\\s*\\(").matcher(text);
        while (m.find()) {
            int i = m.start();
            if (i > 0 && text.charAt(i - 1) == '\\') {
                continue;
            }
            return true;
        }
        return false;
    }

    /** Mapeia ficheiros de biblioteca para seus símbolos (reverse de {@code symbol_to_defining_file}). */
    @SuppressWarnings("unused")
    static Map<String, List<String>> buildFileToSymbolsMap() {
        AcslLibSymbolDependencyMap m = AcslLibSymbolDependencyMap.instance();
        java.util.LinkedHashMap<String, List<String>> out = new java.util.LinkedHashMap<>();
        for (String sym : m.allKnownSymbols()) {
            String f = m.definingFile(sym);
            if (f != null) {
                out.computeIfAbsent(f, k -> new ArrayList<>()).add(sym);
            }
        }
        return out;
    }
}
