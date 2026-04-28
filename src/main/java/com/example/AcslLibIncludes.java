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
 * Detecta símbolos da {@code ACSL_Lib} no texto ACSL gerado e produz linhas {@code include "..."}
 * para os ficheiros correspondentes em {@code src/main/resources/ACSL_Lib}. Usos de {@code NAT},
 * {@code NAT1} ou {@code BOOL} como identificadores incluem {@code set_functions/variables.acsl}.
 * O operador lógico {@code \\length} (E-ACSL) sobre {@code \\list} não confunde com
 * {@code length} da {@code sequence_functions/length.acsl} (sequência como função int → int).
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
 * <p>O bloco {@code include} inserido na <strong>especificação {@code .acsl} gerada</strong> lista o
 * fecho transitivo dos ficheiros da lib (ordem estável com {@link #FILE_ORDER} quando aplicável), exceto
 * ficheiros em pastas {@code *_axioms/} — esses entram apenas via {@code include} nos ficheiros-pai na
 * cópia {@code import/}; {@link #copyReferencedLibraryFiles} continua a materializar todos os ficheiros
 * necessários.
 *
 * <p>Os ficheiros são também copiados para junto do {@code .acsl} e para {@code b2acsl.targetAcslDir}.
 * Com ficheiros no disco, usa-se {@code cp}; em fallback (só classpath/JAR), usa-se {@link Files#copy}.
 */
public final class AcslLibIncludes {

    private AcslLibIncludes() {}

    /** Ficheiros da lib que não devem aparecer no bloco include do .acsl gerado. */
    private static final Set<String> OMIT_FROM_EMITTED_SPEC_INCLUDES =
            Set.of(
                    "function_functions/singleton.acsl",
                    "sequence_functions/list_to_function.acsl",
                    "sequence_functions/function_to_list.acsl");

    private static final Pattern INCLUDE_IN_LIB =
            Pattern.compile("include\\s+\"([^\"]+)\"\\s*;", Pattern.MULTILINE);

    /** Primeiro bloco {@code axiomatic Name} num ficheiro da ACSL_Lib (ex.: {@code set_belongs}, {@code range_function}). */
    private static final Pattern FIRST_AXIOMATIC_NAME_IN_LIB_FILE =
            Pattern.compile("axiomatic\\s+(\\w+)\\s*\\{");

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
            Map.entry("is_seq_of", "sequence_functions/is_seq_of.acsl"),
            Map.entry("list_to_function", "sequence_functions/mapping_functions.acsl"),
            Map.entry("function_to_list", "sequence_functions/mapping_functions.acsl"),
            Map.entry("array_to_function", "function_functions/array_to_function.acsl"),
            Map.entry("function_apply", "function_functions/apply.acsl"),
            Map.entry("is_total_function", "function_functions/is_total.acsl"));

    /**
     * Ordem dos {@code include} para {@code set_functions/} (dependências lógicas da ACSL_Lib), depois
     * relações e sequências.
     */
    private static final List<String> FILE_ORDER = List.of(
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
            "relation_functions/domain.acsl",
            "relation_functions/range.acsl",
            "function_functions/is_function.acsl",
            "function_functions/is_partial.acsl",
            "function_functions/is_total.acsl",
            "function_functions/apply.acsl",
            "function_functions/array_to_function.acsl",            
            "relation_functions/inverse.acsl",
            "relation_functions/domain_restriction.acsl",
            "relation_functions/range_restriction.acsl",
            "sequence_functions/iseq.acsl",
            "sequence_functions/is_seq_of.acsl",
            "sequence_functions/length.acsl",
            "sequence_functions/is_sequence.acsl",            
            "sequence_functions/mapping_functions.acsl",            
            "sequence_functions/range.acsl");

    public static String formatIncludeBlock(String acslText) {
        return formatIncludeBlock(acslText, null);
    }

    /**
     * Como {@link #formatIncludeBlock(String)}, mas concatena {@code extraTextForSymbolScan} só para
     * detetar símbolos da ACSL_Lib (includes e {@link #copyReferencedLibraryFiles}); esse texto não
     * entra no ficheiro gerado.
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
     * Caminhos relativos na {@code ACSL_Lib} no fecho transitivo de {@code include}, com as mesmas
     * sementes que {@link #copyReferencedLibraryFiles} (tipos + ficheiros deduzidos do texto +
     * {@code extraTextForSymbolScan}).
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
     * Símbolos da biblioteca mapeados em {@link #SYMBOL_TO_FILE} cujo ficheiro está no fecho
     * {@link #transitiveLibRelativePathsForScan(String, String)}. Inclui {@code ran} quando entra
     * {@code sequence_functions/range.acsl} ou {@code relation_functions/range.acsl}.
     */
    public static Set<String> allowedLibSymbolsForTransitiveIncludes(
            String acslText, String extraTextForSymbolScan) throws IOException {
        Set<String> paths = transitiveLibRelativePathsForScan(acslText, extraTextForSymbolScan);
        if (paths.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : SYMBOL_TO_FILE.entrySet()) {
            if (paths.contains(e.getValue())) {
                out.add(e.getKey());
            }
        }
        if (paths.contains("sequence_functions/range.acsl") || paths.contains("relation_functions/range.acsl")) {
            out.add("ran");
        }
        return out;
    }

    /**
     * Nomes dos blocos {@code axiomatic} definidos nos ficheiros de {@link #FILE_ORDER} (funções /
     * predicados da biblioteca), na mesma ordem que os {@code include} da lib — para reordenar o merge
     * Frama-C.
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
     * Copia a biblioteca referenciada (fecho transitivo de {@code include} dentro de {@code ACSL_Lib})
     * para {@code generatedAcslFile.getParent()/…} e espelha o mesmo em {@link #resolveTargetAcslStagingRoot()}.
     * Se o diretório de saída do {@code .acsl} não for o alvo, copia também o ficheiro de especificação
     * para o alvo (com {@code cp} quando aplicável).
     */
    public static void copyReferencedLibraryFiles(String acslText, Path generatedAcslFile)
            throws IOException {
        copyReferencedLibraryFiles(acslText, generatedAcslFile, null);
    }

    /**
     * @param extraTextForSymbolScan texto extra (ex. {@code ensures} omitidos do contrato) só para
     *     descobrir ficheiros da lib a copiar
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
     * Ordena o fecho transitivo para emissão no {@code .acsl}: {@link #TYPES_LIB_REL} primeiro, depois
     * entradas de {@link #FILE_ORDER} presentes no fecho, depois o restante na ordem do BFS em
     * {@link #transitiveAcslLibPaths}.
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
     * Para o texto da especificação gerada: não emitir {@code include "…​_axioms/…​"} — esses axiomas já
     * são puxados pelos {@code include} relativos dentro dos ficheiros de funções/definições.
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

    /** {@code true} para caminhos sob {@code *_axioms/} na ACSL_Lib (ex. {@code set_axioms/card_axioms.acsl}). */
    static boolean isUnderLibAxiomFolder(String relativeLibPath) {
        if (relativeLibPath == null || relativeLibPath.isBlank()) {
            return false;
        }
        return relativeLibPath.replace('\\', '/').contains("_axioms/");
    }

    private static List<String> orderedLibRelativePaths(String acslText) {
        LinkedHashSet<String> files = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : SYMBOL_TO_FILE.entrySet()) {
            if (containsSymbolCall(acslText, e.getKey())) {
                files.add(e.getValue());
            }
        }
        addRangeIncludesForRan(acslText, files);
        addIsTotalFunctionLibIncludes(acslText, files);
        addArrayToFunctionLibIncludes(acslText, files);
        addDependencyMapIncludes(acslText, files);
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
    /**
     * Acrescenta ficheiros da {@code ACSL_Lib} segundo {@link AcslLibSymbolDependencyMap}: para cada
     * símbolo da biblioteca presente no texto, inclui o fecho transitivo de dependências de símbolo e de
     * {@code include} (ex.: {@code dom} → {@code couple} → {@code tuple_functions/tuple_couple.acsl}).
     */
    private static void addDependencyMapIncludes(String acslText, LinkedHashSet<String> files) {
        AcslLibSymbolDependencyMap m = AcslLibSymbolDependencyMap.instance();
        if (!m.isLoaded()) {
            return;
        }
        for (String sym : m.allKnownSymbols()) {
            if (containsSymbolCall(acslText, sym)) {
                files.addAll(m.transitiveLibRelativePathsForSymbol(sym));
            }
        }
    }

    /**
     * {@code is_total_function} depende de predicados noutros ficheiros da lib sem cadeia de
     * {@code include} explícita entre axiomatics.
     */
    private static void addIsTotalFunctionLibIncludes(String acslText, LinkedHashSet<String> files) {
        if (!containsSymbolCall(acslText, "is_total_function")) {
            return;
        }
        files.add("relation_functions/domain.acsl");
        files.add("relation_functions/range.acsl");
        files.add("set_functions/inclusion.acsl");
        files.add("set_functions/equals.acsl");
        files.add("tuple_functions/tuple_couple.acsl");
        files.add("function_functions/is_function.acsl");
        files.add("function_functions/is_partial.acsl");
        files.add("function_functions/is_total.acsl");
    }

    /**
     * {@code array_to_function} usa {@code function_apply} nos axiomas; garante {@code apply.acsl}
     * na lista de includes quando o texto referencia {@code array_to_function}, alinhado ao mapa JSON.
     */
    private static void addArrayToFunctionLibIncludes(String acslText, LinkedHashSet<String> files) {
        if (!containsSymbolCall(acslText, "array_to_function")) {
            return;
        }
        files.add("function_functions/apply.acsl");
    }

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

    /**
     * Identifica chamadas {@code symbol(} da ACSL_Lib no texto. Tratamento especial: {@code length}
     * da biblioteca (sequência vista como função) não deve confundir-se com o operador E-ACSL
     * {@code \\length(...)} sobre {@code \\list}.
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
     * {@code \\length(} (comprimento de lista em lógica ACSL, sem {@code sequence_functions/length.acsl}).
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
}
