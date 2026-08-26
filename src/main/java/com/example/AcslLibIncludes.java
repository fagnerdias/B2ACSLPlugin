package com.example;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
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
 * transitivo de ficheiros da biblioteca necessários (includes explícitos no ficheiro definidor).
 * Em seguida, {@link AcslLibSymbolDependencyMap#transitiveLibRelativePathsForSymbols(java.util.Collection)}
 * expande com o grafo {@code dependencies} do JSON, para símbolos usados apenas no corpo de
 * predicados sem {@code include} correspondente (ex.: {@code is_total_function} →
 * {@code is_partial_function}, {@code equals}). Símbolos sobrecarregados (ex.: {@code first}, {@code ran},
 * {@code equals}) têm todos os seus ficheiros definidores incluídos — são sobrecargas de tipo
 * distintas e coexistem sem conflito em ACSL.
 *
 * <p>Usos de {@code NAT}, {@code NAT1}, {@code INT} ou {@code BOOL} como identificadores incluem
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

    /** Funções matemáticas auxiliares (ex.: {@code pow(...)}). */
    private static final String MATH_LIB_REL = "math.acsl";

    /** Conjuntos globais {@code NAT}, {@code NAT1}, {@code INT}, {@code BOOL}. */
    private static final String VARIABLES_LIB_REL = "set_functions/variables.acsl";

    private static final Pattern GLOBAL_SET_CONSTANT_ID =
            Pattern.compile("(?<![A-Za-z0-9_])(?:NAT1|NAT|INT|BOOL)(?![A-Za-z0-9_])");

    /** Relação predefinida do B {@code succ} ({@code Function<integer,integer>}, {@code x |-> x+1}). */
    private static final String SUCC_LIB_REL = "relation_functions/succ.acsl";

    /** Relação predefinida do B {@code pred} ({@code Function<integer,integer>}, {@code x |-> x-1}). */
    private static final String PRED_LIB_REL = "relation_functions/pred.acsl";

    /**
     * {@code succ}/{@code pred} do B usados como identificador solto (ex.: {@code (nn|->nn+1):succ}
     * → {@code belongs(couple(nn, nn+1), succ)}) — mesmo esquema de deteção por identificador bare
     * usado para {@link #GLOBAL_SET_CONSTANT_ID}, já que nenhum dos dois aparece em forma de chamada
     * {@code succ(...)}/{@code pred(...)} no texto ACSL gerado.
     */
    private static final Pattern SUCC_ID =
            Pattern.compile("(?<![A-Za-z0-9_])succ(?![A-Za-z0-9_])");

    private static final Pattern PRED_ID =
            Pattern.compile("(?<![A-Za-z0-9_])pred(?![A-Za-z0-9_])");

    /**
     * Ordem preferida dos {@code include} para o {@code .acsl} gerado: dependências lógicas
     * da {@code B2ACSLLib} (tuple → set → relation → function → sequence).
     */
    private static final List<String> FILE_ORDER = List.of(
            "types.acsl",
            "math.acsl",
            "tuple_functions/tuple_couple.acsl",
            "tuple_functions/accessors.acsl",
            "tuple_functions/equals.acsl",
            "set_functions/belongs.acsl",
            "set_functions/variables.acsl",
            "set_functions/interval_set.acsl",
            "set_functions/empty.acsl",
            "set_functions/singleton.acsl",
            "set_functions/union.acsl",
            "set_functions/pair.acsl",
            "set_functions/intersection.acsl",
            "set_functions/difference.acsl",
            "set_functions/card.acsl",
            "set_functions/finite.acsl",
            "set_functions/inclusion.acsl",            
            "set_functions/pow.acsl",            
            "set_functions/equals.acsl",
            "set_functions/min.acsl",
            "set_functions/max.acsl",
            "set_functions/cartesian_product.acsl",
            "set_functions/pow_set.acsl",
            "set_functions/generalized_union_intersection.acsl",
            "set_functions/disjoint.acsl",
            "relation_functions/singleton.acsl",
            "relation_functions/domain.acsl",
            "relation_functions/range.acsl",
            "relation_functions/inverse.acsl",
            "relation_functions/apply.acsl",
            "relation_functions/domain_restriction.acsl",
            "relation_functions/range_restriction.acsl",
            "relation_functions/is_relation.acsl",
            "relation_functions/domain_subtraction.acsl",
            "relation_functions/range_subtraction.acsl",
            "relation_functions/overwrite.acsl",
            "relation_functions/rel.acsl",
            "relation_functions/fnc.acsl",
            "relation_functions/prj1.acsl",
            "relation_functions/prj2.acsl",
            "relation_functions/direct_product.acsl",
            "relation_functions/composition.acsl",
            "relation_functions/closure1.acsl",
            "relation_functions/succ.acsl",
            "relation_functions/pred.acsl",
            "function_functions/is_function.acsl",
            "function_functions/is_function_of.acsl",
            "function_functions/is_partial.acsl",
            "function_functions/is_total.acsl",
            "function_functions/apply.acsl",
            "function_functions/id.acsl",
            // closure/iterate dependem de id() (identidade) — precisam de vir DEPOIS de
            // function_functions/id.acsl na lista de includes (a ordem de FILE_ORDER é a ordem
            // textual de "include" no .acsl gerado; sem isto, "unbound logic function id" em
            // relation_axioms/relation_closure.acsl, id() referenciado antes de declarado).
            "relation_functions/closure.acsl",
            "relation_functions/iterate.acsl",
            "function_functions/is_injective.acsl",
            "function_functions/is_surjective.acsl",
            "function_functions/is_bijective.acsl",
            "function_functions/becomes_element_of.acsl",
            "function_functions/array_to_function_int.acsl",
            "function_functions/array_to_function_bool.acsl",
            "function_functions/array2d_to_relation_int.acsl",
            "function_functions/array2d_to_relation_bool.acsl",
            "sequence_functions/first.acsl",
            "sequence_functions/front.acsl",
            "sequence_functions/conc.acsl",
            "sequence_functions/rev.acsl",
            "sequence_functions/is_sequence.acsl",
            "sequence_functions/length.acsl",
            "sequence_functions/function_to_list.acsl",
            "sequence_functions/get.acsl",
            "sequence_functions/is_seq_of.acsl",
            "sequence_functions/seq.acsl",
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
        return formatIncludeBlock(acslText, extraTextForSymbolScan, null);
    }

    /**
     * Como {@link #formatIncludeBlock(String, String)}, fundindo caminhos relativos da
     * {@code B2ACSLLib} vindos de máquinas {@code SEES} (sem duplicar linhas {@code include}).
     */
    public static String formatIncludeBlock(
            String acslText, String extraTextForSymbolScan, java.util.Collection<String> additionalLibRelPaths) {
        List<String> lines = collectIncludeLines(acslText, extraTextForSymbolScan, additionalLibRelPaths);
        if (lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }

    private static final Pattern FIRST_AXIOMATIC_OR_FUNCTION =
            Pattern.compile("(?m)^\\s*(axiomatic|function)\\s+");

    /**
     * Caminhos relativos na {@code B2ACSLLib} declarados no preâmbulo ({@code include "import/…"}),
     * excluindo {@code include "OutraMaquina.acsl"}.
     */
    public static List<String> parseLibIncludeRelativePathsFromPreamble(String acslText) {
        if (acslText == null || acslText.isBlank()) {
            return List.of();
        }
        int preambleEnd = acslText.length();
        Matcher cut = FIRST_AXIOMATIC_OR_FUNCTION.matcher(acslText);
        if (cut.find()) {
            preambleEnd = cut.start();
        }
        String preamble = acslText.substring(0, preambleEnd);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher m = INCLUDE_IN_LIB.matcher(preamble);
        while (m.find()) {
            String includePath = m.group(1).replace('\\', '/').trim();
            if (includePath.isEmpty()) {
                continue;
            }
            if (isOtherMachineAcslInclude(includePath)) {
                continue;
            }
            String rel = includePathToLibRelativePath(includePath);
            if (!rel.isBlank()) {
                out.add(rel);
            }
        }
        return List.copyOf(out);
    }

    /** {@code MaquinaVista.acsl} no mesmo diretório — não é include da biblioteca. */
    private static boolean isOtherMachineAcslInclude(String includePath) {
        String p = includePath.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        String base = slash >= 0 ? p.substring(slash + 1) : p;
        return base.endsWith(".acsl")
                && slash < 0
                && !TYPES_LIB_REL.equals(p)
                && !p.equals("types.acsl");
    }

    /** {@code import/set_functions/belongs.acsl} → {@code set_functions/belongs.acsl}. */
    private static String includePathToLibRelativePath(String includePath) {
        String p = includePath.replace('\\', '/');
        String middle = propertyOrEmpty("b2acsl.acslLibIncludeMiddle");
        String base = propertyOrEmpty("b2acsl.acslLibIncludeBase");
        if (!middle.isEmpty()) {
            String midPrefix = middle.endsWith("/") ? middle : middle + "/";
            if (p.startsWith(midPrefix)) {
                p = p.substring(midPrefix.length());
            }
        }
        if (!base.isEmpty()) {
            String basePrefix = base.endsWith("/") ? base : base + "/";
            if (p.startsWith(basePrefix)) {
                p = p.substring(basePrefix.length());
            }
            if (!middle.isEmpty()) {
                String midPrefix = middle.endsWith("/") ? middle : middle + "/";
                if (p.startsWith(midPrefix)) {
                    p = p.substring(midPrefix.length());
                }
            }
        }
        return p;
    }

    /**
     * Remove linhas {@code include "import/…"} / {@code include "…/…/*.acsl"} do preâmbulo,
     * mantendo comentários iniciais e o corpo ({@code axiomatic}, {@code function}, …).
     */
    public static String removeLibIncludesFromPreamble(String acslText) {
        if (acslText == null || acslText.isBlank()) {
            return acslText == null ? "" : acslText;
        }
        Matcher cut = FIRST_AXIOMATIC_OR_FUNCTION.matcher(acslText);
        int bodyStart = cut.find() ? cut.start() : acslText.length();
        String header = acslText.substring(0, bodyStart);
        String body = acslText.substring(bodyStart);
        StringBuilder keptHeader = new StringBuilder();
        for (String line : header.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("include \"")) {
                String path = trimmed.substring("include \"".length());
                int end = path.indexOf('"');
                if (end >= 0) {
                    path = path.substring(0, end);
                }
                if (!isOtherMachineAcslInclude(path)) {
                    continue;
                }
            }
            keptHeader.append(line).append('\n');
        }
        String h = keptHeader.toString();
        if (!h.isBlank() && !h.endsWith("\n\n")) {
            if (!h.endsWith("\n")) {
                h = h + "\n";
            }
            h = h + "\n";
        }
        return h + body;
    }

    /** Corpo ACSL após o bloco de {@code include} do preâmbulo (para varredura de símbolos). */
    public static String acslBodyAfterPreambleIncludes(String acslText) {
        if (acslText == null || acslText.isBlank()) {
            return "";
        }
        Matcher cut = FIRST_AXIOMATIC_OR_FUNCTION.matcher(acslText);
        if (cut.find()) {
            return acslText.substring(cut.start());
        }
        return acslText;
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
     * Apaga a pasta da biblioteca espelhada ({@code import/} por defeito) sob o diretório de saída
     * dos {@code .acsl}, para sincronização completa antes de nova cópia na mesma execução.
     */
    public static void resetLibraryBundleUnderOutput(Path outputDirectory) throws IOException {
        deleteLibraryBundleRecursive(libraryBundleRootUnderOutput(outputDirectory));
    }

    private static void deleteLibraryBundleRecursive(Path bundleRoot) throws IOException {
        if (bundleRoot == null || !Files.exists(bundleRoot)) {
            return;
        }
        try (var stream = Files.walk(bundleRoot)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /**
     * Copia a biblioteca referenciada (fecho transitivo de {@code include}) para
     * {@code generatedAcslFile.getParent()/…} e espelha em {@link #resolveTargetAcslStagingRoot()}.
     */
    public static void copyReferencedLibraryFiles(String acslText, Path generatedAcslFile)
            throws IOException {
        copyReferencedLibraryFiles(acslText, generatedAcslFile, null, null);
    }

    /**
     * @param extraTextForSymbolScan texto extra só para descobrir ficheiros da lib a copiar
     */
    public static void copyReferencedLibraryFiles(
            String acslText, Path generatedAcslFile, String extraTextForSymbolScan)
            throws IOException {
        copyReferencedLibraryFiles(acslText, generatedAcslFile, extraTextForSymbolScan, null);
    }

    /**
     * Copia para {@code import/} o fecho transitivo dos ficheiros declarados no preâmbulo do
     * {@code .acsl} gerado (e suplementos de varredura), alinhado com {@link #formatIncludeBlock}.
     *
     * @param additionalLibRelPaths caminhos relativos extra (ex. de máquinas {@code SEES}/{@code IMPORTS})
     */
    public static void copyReferencedLibraryFiles(
            String acslText,
            Path generatedAcslFile,
            String extraTextForSymbolScan,
            java.util.Collection<String> additionalLibRelPaths)
            throws IOException {
        LinkedHashSet<String> toCopy =
                collectLibRelativePathsToMaterialize(
                        acslText, extraTextForSymbolScan, additionalLibRelPaths);
        if (toCopy.isEmpty()) {
            return;
        }

        Path diskRoot = resolveAcslLibRootOnDisk();
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

    /**
     * Conjunto de caminhos relativos na {@code B2ACSLLib} a materializar em {@code import/}:
     * includes do preâmbulo do {@code .acsl}, varredura de símbolos e fecho transitivo interno
     * (inclui {@code *_axioms/} referenciados pelos ficheiros copiados).
     */
    private static LinkedHashSet<String> collectLibRelativePathsToMaterialize(
            String acslText,
            String extraTextForSymbolScan,
            java.util.Collection<String> additionalLibRelPaths)
            throws IOException {
        LinkedHashSet<String> fileSeeds = new LinkedHashSet<>();
        fileSeeds.addAll(parseLibIncludeRelativePathsFromPreamble(acslText));

        String scan = acslText == null ? "" : acslText;
        if (extraTextForSymbolScan != null && !extraTextForSymbolScan.isBlank()) {
            scan = scan + "\n" + extraTextForSymbolScan;
        }
        fileSeeds.addAll(orderedLibRelativePaths(scan));
        if (additionalLibRelPaths != null) {
            for (String rel : additionalLibRelPaths) {
                if (rel != null && !rel.isBlank()) {
                    fileSeeds.add(rel.replace('\\', '/').trim());
                }
            }
        }
        if (fileSeeds.isEmpty()) {
            return new LinkedHashSet<>();
        }

        Path diskRoot = resolveAcslLibRootOnDisk();
        fileSeeds.add(TYPES_LIB_REL);
        fileSeeds.removeIf(rel -> !libFileExists(diskRoot, rel));

        List<String> seedsWithTypes = new ArrayList<>(fileSeeds.size());
        if (fileSeeds.contains(TYPES_LIB_REL)) {
            seedsWithTypes.add(TYPES_LIB_REL);
        }
        for (String s : fileSeeds) {
            if (!TYPES_LIB_REL.equals(s)) {
                seedsWithTypes.add(s);
            }
        }

        LinkedHashSet<String> toCopy = new LinkedHashSet<>();
        toCopy.add(TYPES_LIB_REL);
        toCopy.addAll(transitiveAcslLibPaths(diskRoot, seedsWithTypes));
        toCopy.removeIf(rel -> !libFileExists(diskRoot, rel.replace('\\', '/')));
        return toCopy;
    }

    static List<String> collectIncludeLines(String acslText) {
        return collectIncludeLines(acslText, null);
    }

    static List<String> collectIncludeLines(String acslText, String extraTextForSymbolScan) {
        return collectIncludeLines(acslText, extraTextForSymbolScan, null);
    }

    static List<String> collectIncludeLines(
            String acslText,
            String extraTextForSymbolScan,
            java.util.Collection<String> additionalLibRelPaths) {
        String scan = acslText == null ? "" : acslText;
        if (extraTextForSymbolScan != null && !extraTextForSymbolScan.isBlank()) {
            scan = scan + "\n" + extraTextForSymbolScan;
        }
        LinkedHashSet<String> fileSeeds = new LinkedHashSet<>(orderedLibRelativePaths(scan));
        if (additionalLibRelPaths != null) {
            for (String rel : additionalLibRelPaths) {
                if (rel != null && !rel.isBlank()) {
                    fileSeeds.add(rel.replace('\\', '/').trim());
                }
            }
        }
        return emitIncludeLinesFromLibFileSeeds(fileSeeds);
    }

    private static List<String> emitIncludeLinesFromLibFileSeeds(LinkedHashSet<String> fileSeeds) {
        if (fileSeeds.isEmpty()) {
            return List.of();
        }
        fileSeeds.add(TYPES_LIB_REL);

        Path diskRoot = resolveAcslLibRootOnDisk();
        fileSeeds.removeIf(rel -> !libFileExists(diskRoot, rel));
        if (fileSeeds.isEmpty()) {
            return List.of();
        }

        List<String> orderedSeeds = new ArrayList<>();
        for (String path : FILE_ORDER) {
            if (fileSeeds.contains(path)) {
                orderedSeeds.add(path);
            }
        }
        for (String f : fileSeeds) {
            if (!orderedSeeds.contains(f)) {
                orderedSeeds.add(f);
            }
        }

        List<String> seedsWithTypes = new ArrayList<>(orderedSeeds.size() + 1);
        seedsWithTypes.add(TYPES_LIB_REL);
        for (String s : orderedSeeds) {
            if (!TYPES_LIB_REL.equals(s)) {
                seedsWithTypes.add(s);
            }
        }

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
    private static boolean warnedUnloadedSymbolDependencyMap = false;

    private static List<String> orderedLibRelativePaths(String acslText) {
        AcslLibSymbolDependencyMap m = AcslLibSymbolDependencyMap.instance();
        LinkedHashSet<String> files = new LinkedHashSet<>();

        if (!m.isLoaded() && !warnedUnloadedSymbolDependencyMap) {
            // Sem este mapa, TODO include baseado em símbolo da lib (belongs, couple,
            // function_apply, …) é omitido em silêncio — só os 4 casos hardcoded abaixo
            // (NAT/succ/pred/integer_pow) sobrevivem. O sintoma real só aparece bem mais tarde,
            // como "unbound logic function"/"unbound logic variable" no -acsl-import, sem
            // qualquer pista de que a causa é este recurso em falta — avisar aqui, uma vez por
            // execução, poupa esse desvio de diagnóstico.
            warnedUnloadedSymbolDependencyMap = true;
            System.err.println(
                    "[B2ACSL] AVISO: b2acsl/symbol_dependency_map.json não carregou (recurso "
                            + "ausente/inválido no classpath) — includes da lib baseados em símbolo "
                            + "ficam TODOS omitidos; regenerar com "
                            + "scripts/generate_acsl_symbol_dependency_map.py e recompilar.");
        }

        if (m.isLoaded()) {
            LinkedHashSet<String> foundSymbols = new LinkedHashSet<>();
            for (String sym : m.allKnownSymbols()) {
                if (containsSymbolCall(acslText, sym)) {
                    foundSymbols.add(sym);
                    files.addAll(m.directIncludeClosureForSymbol(sym));
                }
            }
            // Corpos de predicados/funções da lib referenciam outros símbolos sem include explícito
            // no ficheiro (ex.: is_total.acsl → is_partial_function, equals(...)). O grafo
            // "dependencies" + transitiveLibRelativePathsForSymbols cobre esses ficheiros.
            if (!foundSymbols.isEmpty()) {
                files.addAll(m.transitiveLibRelativePathsForSymbols(foundSymbols));
            }
        }

        if (GLOBAL_SET_CONSTANT_ID.matcher(acslText).find()) {
            files.add(VARIABLES_LIB_REL);
        }
        if (SUCC_ID.matcher(acslText).find()) {
            files.add(SUCC_LIB_REL);
        }
        if (PRED_ID.matcher(acslText).find()) {
            files.add(PRED_LIB_REL);
        }
        if (containsSymbolCall(acslText, "integer_pow")) {
            files.add(MATH_LIB_REL);
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

}
