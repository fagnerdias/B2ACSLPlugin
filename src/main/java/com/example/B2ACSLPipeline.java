package com.example;

import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.bxml.BxmlGluingNormalizer;
import com.example.bxml.GhostOperationsCiGenerator;
import com.example.model.Machine;

import org.w3c.dom.Element;

/**
 * Pipeline B2ACSL: BXML -> ACSL -> {@code ghost_operations.ci} -> Frama-C ({@code -acsl-import} +
 * {@code merged_code.c} + WP) -> resultado para Atelier B.
 */
public final class B2ACSLPipeline {

    private static final String FRAMA_C = "frama-c";

    /**
     * Linhas de diagnóstico que o Frama-C escreve no stdout antes do C gerado com {@code -print}
     * (ex.: {@code [kernel] Parsing ...}, {@code [acsl-import] Success ...}).
     */
    private static final Pattern FRAMA_C_STDOUT_TAG_LINE =
            Pattern.compile("^\\[[^\\]]+\\]\\s*.*");

    /** Marca o bloco {@code axiomatic new_types} importado (ex. de {@code types.acsl}). */
    private static final String AXIOMATIC_NEW_TYPES_MARKER = "axiomatic new_types";

    /** Lemas admitidos da B2ACSLLib (anexados ao fim do merge Frama-C). */
    private static final String ACSL_LIB_LEMMAS_RESOURCE = B2AcslLibraryPaths.classpathResource("lemmas.acsl");

    private static final boolean MOCK_MODE = isMockEnabled();

    private static boolean isMockEnabled() {
        String sys = System.getProperty("b2acsl.mock");
        if (sys != null && !sys.isBlank()) return Boolean.parseBoolean(sys);
        try (InputStream in = B2ACSLPipeline.class.getResourceAsStream("/META-INF/b2acsl.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("b2acsl.mock");
                if (v != null && !v.isBlank()) return Boolean.parseBoolean(v.trim());
            }
        } catch (Exception ignored) {}
        return true;
    }
    /** Se definido, grava os .acsl neste diretório e não os remove (para inspeção) */
    private static final String KEEP_ACSL_DIR = System.getProperty("b2acsl.keepAcsl");

    private B2ACSLPipeline() {}

    /**
     * Executa o pipeline completo.
     *
     * @param bdpPath Caminho da pasta bdp (contém os .bxml)
     * @return Código de retorno para o Atelier B (0=sucesso, !=0=falha)
     */
    public static int run(Path bdpPath) throws Exception {
        Path bdp = bdpPath.toAbsolutePath().normalize();
        if (!Files.isDirectory(bdp)) {
            System.err.println("[B2ACSL] Caminho inválido (não é diretório): " + bdp);
            return 1;
        }

        // Step 1: Ler arquivos .bxml
        List<Path> bxmlFiles = findBxmlFiles(bdp);
        if (bxmlFiles.isEmpty()) {
            System.err.println("[B2ACSL] Nenhum arquivo .bxml encontrado em: " + bdp);
            return 2;
        }

        record MachineFile(Machine machine, Path bxmlPath) {}
        List<MachineFile> machines = new ArrayList<>();
        for (Path f : bxmlFiles) {
            try {
                Machine m = Machine.fromBxmlPath(f);
                machines.add(new MachineFile(m, f));
            } catch (Exception e) {
                System.err.println("[B2ACSL] Falha ao ler " + f + ": " + e.getMessage());
            }
        }

        if (machines.isEmpty()) {
            System.err.println("[B2ACSL] Nenhuma máquina válida encontrada nos ficheiros .bxml.");
            return 3;
        }

        Map<String, String> invariantGluingSubstitutions = BxmlGluingNormalizer.collectFromAllBxmlFiles(bxmlFiles);

        // Mapa máquina -> nome em <Abstraction> (refinamento / implementação)
        Map<String, String> abstractionParentByMachine = buildAbstractionParentMap(bxmlFiles);
        // Ficheiros BXML de refinamento/implementação a fundir na máquina abstrata raiz
        Map<String, List<Path>> mergePathsByRootAbstract = new HashMap<>();
        for (Path f : bxmlFiles) {
            try {
                Element root = AcslGenerator.parseMachineElement(f);
                if (AcslGenerator.getAbstractionReferenceName(root).isEmpty()) continue;
                String source = root.getAttribute("name");
                if (source == null || source.isBlank()) continue;
                String rootAbstract = resolveRootAbstractName(source, abstractionParentByMachine);
                mergePathsByRootAbstract.computeIfAbsent(rootAbstract, k -> new ArrayList<>()).add(f);
            } catch (Exception e) {
                System.err.println("[B2ACSL] Falha ao indexar merge de " + f + ": " + e.getMessage());
            }
        }
        for (List<Path> paths : mergePathsByRootAbstract.values()) {
            paths.sort(
                    Comparator.comparingInt(
                            path -> {
                                try {
                                    String n =
                                            AcslGenerator.parseMachineElement(path)
                                                    .getAttribute("name");
                                    return refinementDepthToRoot(n, abstractionParentByMachine);
                                } catch (Exception e) {
                                    return 0;
                                }
                            }));
        }

        // Step 1.1: Gerar arquivos .acsl (temporários ou em dir fixo para inspeção)
        Path acslDir = KEEP_ACSL_DIR != null && !KEEP_ACSL_DIR.isBlank()
                ? Path.of(KEEP_ACSL_DIR).toAbsolutePath().normalize()
                : Files.createTempDirectory("b2acsl_acsl_");
        boolean keepFiles = KEEP_ACSL_DIR != null && !KEEP_ACSL_DIR.isBlank();
        try {
            List<Path> acslFiles = new ArrayList<>();
            for (MachineFile mf : machines) {
                Element machineRoot = AcslGenerator.parseMachineElement(mf.bxmlPath());
                if (AcslGenerator.getAbstractionReferenceName(machineRoot).isPresent()) {
                    continue;
                }
                String machineName = mf.machine().getMachineName();
                List<Path> mergePaths =
                        mergePathsByRootAbstract.getOrDefault(machineName, List.of());
                Optional<Path> acsl =
                        AcslGenerator.generateAcsl(
                                mf.machine(), mf.bxmlPath(), acslDir, mergePaths, invariantGluingSubstitutions);
                acsl.ifPresent(acslFiles::add);
            }
            if (keepFiles) {
                System.out.println("[B2ACSL] ACSL gravados em: " + acslDir);
                for (Path p : acslFiles) System.out.println("  - " + p);
            }

            // Step 2: Obter arquivos .c em lang/c/ (mesmo path, trocando bdp por lang)
            String bdpStr = bdpPathToString(bdp);
            int idx = bdpStr.lastIndexOf("bdp");
            Path langPath = idx >= 0
                    ? Path.of(bdpStr.substring(0, idx) + "lang" + bdpStr.substring(idx + 3))
                    : bdp.getParent().resolve("lang");
            Path cDir = langPath.resolve("c");
            for (MachineFile mf : machines) {
                Element mr = AcslGenerator.parseMachineElement(mf.bxmlPath());
                if (AcslGenerator.getAbstractionReferenceName(mr).isPresent()) {
                    continue;
                }
                GhostOperationsCiGenerator.write(cDir, mr, invariantGluingSubstitutions);
                break;
            }

            List<Path> cFiles = findCFiles(cDir);

            if (cFiles.isEmpty() && !MOCK_MODE) {
                System.err.println("[B2ACSL] Nenhum arquivo .c encontrado em: " + cDir);
                return 4;
            }

            // Step 3: Executar Frama-C (acsl-importer + WP)
            int framaResult;
            if (MOCK_MODE) {
                framaResult = runMockFramaC(acslFiles, cFiles, cDir);
            } else {
                framaResult = runFramaC(acslFiles, cFiles, cDir);
            }

            // Step 4: Retornar valor para Atelier B
            return framaResult;
        } finally {
            if (!keepFiles) deleteRecursive(acslDir);
        }
    }

    /** {@code nomeDaMaquina -> nomeEm<Abstraction>} para seguir a cadeia até à abstrata raiz. */
    private static Map<String, String> buildAbstractionParentMap(List<Path> bxmlFiles) throws Exception {
        Map<String, String> map = new HashMap<>();
        for (Path f : bxmlFiles) {
            try {
                Element root = AcslGenerator.parseMachineElement(f);
                String name = root.getAttribute("name");
                if (name == null || name.isBlank()) continue;
                AcslGenerator.getAbstractionReferenceName(root)
                        .ifPresent(parent -> map.put(name.trim(), parent.trim()));
            } catch (Exception ignored) {
                // ficheiro ignorado; já reportado ao ler Machine
            }
        }
        return map;
    }

    /**
     * Segue {@code <Abstraction>} até à máquina que não referencia outra (raiz da cadeia de refinamento).
     */
    private static String resolveRootAbstractName(String machineName, Map<String, String> parentOf) {
        String current = machineName;
        for (int i = 0; i < 256; i++) {
            String p = parentOf.get(current);
            if (p == null || p.isBlank()) return current;
            current = p;
        }
        return current;
    }

    /** Número de saltos até à raiz (refinamento = 1, implementação sobre refinamento = 2, …). */
    private static int refinementDepthToRoot(String machineName, Map<String, String> parentOf) {
        int d = 0;
        String current = machineName;
        for (int i = 0; i < 256; i++) {
            String p = parentOf.get(current);
            if (p == null || p.isBlank()) return d;
            d++;
            current = p;
        }
        return d;
    }

    private static String bdpPathToString(Path p) {
        return p.toString().replace('\\', '/');
    }

    private static List<Path> findBxmlFiles(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".bxml"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    /** Saída gerada pelo Frama-C {@code -print} no mesmo diretório — não é ficheiro-fonte. */
    private static final String MERGED_CODE_FILE_NAME = "merged_code.c";

    private static List<Path> findCFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".c"))
                    .filter(p -> !MERGED_CODE_FILE_NAME.equalsIgnoreCase(p.getFileName().toString()))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static int runMockFramaC(List<Path> acslFiles, List<Path> cFiles, Path cDir) {
        System.out.println("[B2ACSL] [MOCK] ACSL gerados: " + acslFiles.size());
        System.out.println("[B2ACSL] [MOCK] Arquivos C: " + cFiles.size());
        System.out.println("[B2ACSL] [MOCK] Simulando acsl-importer + WP -> OK");
        return 0;
    }

    private static int runFramaC(List<Path> acslFiles, List<Path> cFiles, Path cDir) throws IOException, InterruptedException {
        if (cFiles.isEmpty()) return 0;

        String acslPath = acslFiles.stream()
                .map(Path::toString)
                .reduce((a, b) -> a + " " + b)
                .orElse("");

        Path mergedCode = cDir.resolve(MERGED_CODE_FILE_NAME);

        Path ghostCi = GhostOperationsCiGenerator.targetPath(cDir);
        StringBuilder specScanForLemmas = new StringBuilder();
        for (String part : acslPath.split("\\s+")) {
            if (part == null || part.isBlank()) {
                continue;
            }
            Path ap = Path.of(part);
            if (Files.isRegularFile(ap)) {
                specScanForLemmas.append(Files.readString(ap, StandardCharsets.UTF_8)).append('\n');
            }
        }
        String ghostCiText =
                Files.isRegularFile(ghostCi)
                        ? Files.readString(ghostCi, StandardCharsets.UTF_8)
                        : "";
        Set<String> allowedLibSymbolsForLemmas =
                AcslLibIncludes.allowedLibSymbolsForTransitiveIncludes(
                        specScanForLemmas.toString(), ghostCiText);

        for (Path cFile : cFiles) {
            // frama-c -acsl-import <acsl> [ghost_operations.ci] <c> -print -no-unicode  (saída → merged_code.c)
            List<String> importCmd = new ArrayList<>();
            importCmd.add(FRAMA_C);
            importCmd.add("-acsl-import");
            importCmd.add(acslPath);
            if (Files.isRegularFile(ghostCi)) {
                importCmd.add(ghostCi.toString());
            }
            importCmd.add(cFile.toString());
            importCmd.add("-print");
            importCmd.add("-no-unicode");
            ProcessBuilder importPb = new ProcessBuilder(importCmd);
            importPb.directory(cDir.toFile());
            importPb.redirectOutput(mergedCode.toFile());
            importPb.redirectError(ProcessBuilder.Redirect.INHERIT);

            Process pImport = importPb.start();
            boolean importOk = pImport.waitFor(120, TimeUnit.SECONDS);
            if (!importOk) {
                pImport.destroyForcibly();
                return 5;
            }
            if (pImport.exitValue() != 0) {
                return pImport.exitValue();
            }

            stripLeadingFramaCNonCOutput(mergedCode);
            moveNewTypesAxiomaticBlockAfterPreamble(mergedCode);
            removeGhostPatternAxiomaticBlocks(mergedCode);
            stripDummyPrefixFromMergedCode(mergedCode);            
            insertGhostVariableDeclarationsFromGhostCi(mergedCode, ghostCi);
            replaceAssertGhostWithGhostKeyword(mergedCode);
            replaceEnsuresGhostVarWithAssignsInMerged(mergedCode);
            addParenthesesToGhostInitialisationCall(mergedCode);
            placeGhostOperationSpecsAboveFunctions(mergedCode, ghostCi);
            liftPureGhostEnsuresToOperationContracts(mergedCode);
            reorderLibAxiomaticBlocksPerAcslLibIncludesOrder(mergedCode);
            appendLemmasAcslLibToMergedEnd(mergedCode, allowedLibSymbolsForLemmas);

            // frama-c -wp merged_code.c -wp-prover CVC5 --wp-smoke-tests -wp-rte -wp-status
            ProcessBuilder wpPb =
                    new ProcessBuilder(
                            FRAMA_C,
                            "-wp",
                            mergedCode.getFileName().toString(),
                            "-wp-prover",
                            "CVC5",
                            "-wp-smoke-tests",
                            "-wp-rte",
                            "-wp-timeout",
                            "30",
                            "-wp-status");
            wpPb.directory(cDir.toFile());
            wpPb.inheritIO();

            Process pWp = wpPb.start();
            boolean wpOk = pWp.waitFor(600, TimeUnit.SECONDS);
            if (!wpOk) {
                pWp.destroyForcibly();
                return 6;
            }
            if (pWp.exitValue() != 0) {
                return pWp.exitValue();
            }
        }
        return 0;
    }

    /**
     * Remove do início de {@code merged_code.c} linhas em branco e linhas de log Frama-C
     * {@code [etiqueta] …} até à primeira linha que não corresponde a esse padrão (código C / ACSL).
     */
    private static void stripLeadingFramaCNonCOutput(Path mergedC) throws IOException {
        List<String> lines = Files.readAllLines(mergedC, StandardCharsets.UTF_8);
        int start = 0;
        while (start < lines.size()) {
            String trimmed = lines.get(start).trim();
            if (trimmed.isEmpty()) {
                start++;
                continue;
            }
            if (FRAMA_C_STDOUT_TAG_LINE.matcher(trimmed).matches()) {
                start++;
                continue;
            }
            break;
        }
        if (start == 0) {
            return;
        }
        Files.write(mergedC, lines.subList(start, lines.size()), StandardCharsets.UTF_8);
    }

    /**
     * Coloca o comentário ACSL {@code axiomatic new_types} logo após o preâmbulo (comentário gerado,
     * {@code #include}, linhas em branco), antes dos restantes blocos {@code axiomatic} em ACSL.
     */
    private static void moveNewTypesAxiomaticBlockAfterPreamble(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        int typesKeywordIdx = content.indexOf(AXIOMATIC_NEW_TYPES_MARKER);
        if (typesKeywordIdx < 0) {
            return;
        }
        int blockStart = content.lastIndexOf("/*@", typesKeywordIdx);
        if (blockStart < 0) {
            return;
        }
        int openBrace = content.indexOf('{', typesKeywordIdx);
        if (openBrace < 0) {
            return;
        }
        int closeBrace = findMatchingBrace(content, openBrace);
        if (closeBrace < 0) {
            return;
        }
        int commentEnd = content.indexOf("*/", closeBrace);
        if (commentEnd < 0) {
            return;
        }
        int blockEnd = commentEnd + 2;
        blockEnd = skipNewlineAfter(blockEnd, content);

        String block = content.substring(blockStart, blockEnd);
        String without = content.substring(0, blockStart) + content.substring(blockEnd);
        int insertAt = findPreambleInsertIndex(without);
        String sepBefore =
                insertAt > 0 && without.charAt(insertAt - 1) != '\n' ? "\n" : "";
        String sepAfter = block.endsWith("\n") ? "" : "\n";
        String result = without.substring(0, insertAt) + sepBefore + block + sepAfter + without.substring(insertAt);
        Files.writeString(mergedC, result, StandardCharsets.UTF_8);
    }

    /**
     * Remove blocos ACSL {@code axiomatic dummy_ghost} e {@code axiomatic <M>_ghost_patterns} do merge
     * Frama-C (gerados a partir de {@code ghost_operations.ci}).
     */
    private static void removeGhostPatternAxiomaticBlocks(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        content = removeAllAxiomaticBlocksNamed(content, "dummy_ghost");
        Pattern ghostPatterns = Pattern.compile("axiomatic\\s+\\w+_ghost_patterns\\b");
        Matcher m = ghostPatterns.matcher(content);
        while (m.find()) {
            content = removeAxiomaticCommentBlockContaining(content, m.start());
            m = ghostPatterns.matcher(content);
        }
        Files.writeString(mergedC, content, StandardCharsets.UTF_8);
    }

    private static String removeAllAxiomaticBlocksNamed(String content, String axiomaticName) {
        String marker = "axiomatic " + axiomaticName;
        int idx;
        while ((idx = content.indexOf(marker)) >= 0) {
            content = removeAxiomaticCommentBlockContaining(content, idx);
        }
        return content;
    }

    /**
     * Remove o bloco de comentário ACSL (aberto com {@code slash-star-at}) que contém {@code keywordIdx}
     * (por exemplo o nome {@code axiomatic ...}).
     */
    private static String removeAxiomaticCommentBlockContaining(String content, int keywordIdx) {
        int blockStart = content.lastIndexOf("/*@", keywordIdx);
        if (blockStart < 0) {
            return content;
        }
        int openBrace = content.indexOf('{', keywordIdx);
        if (openBrace < 0) {
            return content;
        }
        int closeBrace = findMatchingBrace(content, openBrace);
        if (closeBrace < 0) {
            return content;
        }
        int commentEnd = content.indexOf("*/", closeBrace);
        if (commentEnd < 0) {
            return content;
        }
        int blockEnd = skipNewlineAfter(commentEnd + 2, content);
        return content.substring(0, blockStart) + content.substring(blockEnd);
    }

    /** Remove o prefixo {@code dummy_} de identificadores no C/ACSL fundido. */
    private static void stripDummyPrefixFromMergedCode(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        content = content.replaceAll("\\bdummy_", "");
        Files.writeString(mergedC, content, StandardCharsets.UTF_8);
    }

    /**
     * Após {@link #stripDummyPrefixFromMergedCode}, {@code ensures dummy_ghost_<v>} passa a
     * {@code ensures ghost_<v>}; troca por {@code assigns ghost_<v>;} (variáveis ghost no merge).
     * Inclui a forma que o Frama-C emite para não-nulo: {@code ensures ghost_<v> != 0;}.
     */
    private static final Pattern ENSURES_GHOST_VAR =
            Pattern.compile("ensures\\s+(ghost_\\w+)\\s*(?:;|!=\\s*0\\s*;)");

    private static void replaceEnsuresGhostVarWithAssignsInMerged(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        content = ENSURES_GHOST_VAR.matcher(content).replaceAll("assigns $1;");
        Files.writeString(mergedC, content, StandardCharsets.UTF_8);
    }

    /**
     * Coloca no início do ficheiro (logo após o preâmbulo) as linhas de declaração ghost lidas de
     * {@code ghost_operations.ci}, removendo duplicados equivalentes já presentes no merge.
     */
    private static void insertGhostVariableDeclarationsFromGhostCi(Path mergedC, Path ghostCi)
            throws IOException {
        if (!Files.isRegularFile(ghostCi)) {
            return;
        }
        List<String> ghostLines = new ArrayList<>();
        for (String line : Files.readAllLines(ghostCi, StandardCharsets.UTF_8)) {
            String t = line.stripLeading();
            if (t.startsWith("//@") && t.contains("ghost")) {
                ghostLines.add(line.stripTrailing());
            }
        }
        if (ghostLines.isEmpty()) {
            return;
        }
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        content = removeExistingGhostVariableDeclarationsFromMerged(content);
        int insertAt = findPreambleInsertIndex(content);
        String block = String.join("\n", ghostLines);
        String sepBefore = insertAt > 0 && content.charAt(insertAt - 1) != '\n' ? "\n" : "";
        String sepAfter = insertAt < content.length() && content.charAt(insertAt) != '\n' ? "\n" : "";
        String result = content.substring(0, insertAt) + sepBefore + block + "\n" + sepAfter + content.substring(insertAt);
        Files.writeString(mergedC, result, StandardCharsets.UTF_8);
    }

    private static final Pattern GHOST_ANNOTATION_LINE = Pattern.compile("(?m)^\\s*//@\\s+ghost[^\\n]*\\R?");

    /**
     * Bloco {@code /*@ ghost ... star-slash} — candidato a duplicado de <strong>variável</strong> ghost
     * no merge; contratos de operações (assigns, ensures, {@code void} …) são filtrados em
     * {@link #shouldRemoveDuplicateGhostVarBlock}.
     */
    private static final Pattern GHOST_VAR_BLOCK_COMMENT =
            Pattern.compile("/\\*@\\s*ghost\\b[\\s\\S]*?\\*/\\s*\\R?");

    /**
     * Remove do merge só duplicados de declaração de variável ghost ({@code //@ ghost int …} ou bloco
     * ACSL equivalente). Não remove contratos / protótipos de operações ghost não puras.
     */
    private static String removeExistingGhostVariableDeclarationsFromMerged(String content) {
        content = GHOST_ANNOTATION_LINE.matcher(content).replaceAll("");
        Matcher m = GHOST_VAR_BLOCK_COMMENT.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String block = m.group();
            if (shouldRemoveDuplicateGhostVarBlock(block)) {
                m.appendReplacement(sb, "");
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(block));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * {@code true} se o bloco for só declaração de variável ghost a eliminar antes de reinserir a linha
     * vinda de {@code ghost_operations.ci}; {@code false} para contratos (assigns, ensures, …) ou
     * protótipos {@code void op(...)}.
     */
    private static boolean shouldRemoveDuplicateGhostVarBlock(String block) {
        if (block.contains("assigns")
                || block.contains("ensures")
                || block.contains("requires")
                || block.contains("assumes")
                || block.contains("behavior")) {
            return false;
        }
        if (block.contains("/@") || block.contains("@/")) {
            return false;
        }
        if (Pattern.compile("\\bvoid\\s+[A-Za-z_]\\w*\\s*\\(").matcher(block).find()) {
            return false;
        }
        return true;
    }

    /** Substitui {@code assert ghost__} por {@code ghost } nas anotações Frama-C. */
    private static void replaceAssertGhostWithGhostKeyword(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        content = content.replace("assert ghost__", "ghost ");
        Files.writeString(mergedC, content, StandardCharsets.UTF_8);
    }

    /** Garante {@code ghost initialisation();} em vez de {@code ghost initialisation;}. */
    private static void addParenthesesToGhostInitialisationCall(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        content =
                Pattern.compile("\\bghost\\s+initialisation\\s*;")
                        .matcher(content)
                        .replaceAll("ghost initialisation();");
        Files.writeString(mergedC, content, StandardCharsets.UTF_8);
    }

    private static final Pattern GHOST_OP_BLOCK_IN_CI =
            Pattern.compile("(?s)/\\*@\\s*ghost\\b.*?\\bvoid\\s+([A-Za-z_]\\w*)\\s*\\([^;{}]*\\)\\s*;\\s*\\*/");
    private static final Pattern GHOST_INITIALISATION_BLOCK_IN_CI =
            Pattern.compile("(?s)/\\*@\\s*ghost\\b.*?\\bvoid\\s+initialisation\\s*\\([^;{}]*\\)\\s*;\\s*\\*/");
    private static final Pattern GHOST_INITIALISATION_BLOCK_IN_MERGED =
            Pattern.compile("(?s)/\\*@\\s*ghost\\b.*?\\bvoid\\s+initialisation\\s*\\([^;{}]*\\)\\s*;\\s*\\*/\\s*");
    private static final Pattern INITIALISATION_FUNCTION_DEFINITION =
            Pattern.compile("\\bvoid\\s+[A-Za-z_]\\w*__INITIALISATION\\s*\\([^;{}]*\\)\\s*\\{");
    private static final String INITIALISATION_GHOST_CALL_MARKER = "/*@ ghost initialisation(); */";

    /**
     * Novo passo pré-WP: move especificações ghost de operações para imediatamente acima da função C
     * correspondente (ex.: bloco {@code initialisation} acima de {@code Deck__INITIALISATION}).
     */
    private static void placeGhostOperationSpecsAboveFunctions(Path mergedC, Path ghostCi) throws IOException {
        if (!Files.isRegularFile(ghostCi)) {
            return;
        }
        String merged = Files.readString(mergedC, StandardCharsets.UTF_8);
        String ghostText = Files.readString(ghostCi, StandardCharsets.UTF_8).replace("dummy_", "");

        Matcher bm = GHOST_OP_BLOCK_IN_CI.matcher(ghostText);
        List<String> opNames = new ArrayList<>();
        List<String> blocks = new ArrayList<>();
        while (bm.find()) {
            opNames.add(bm.group(1));
            blocks.add(bm.group().stripTrailing() + "\n\n");
        }
        if (opNames.isEmpty()) {
            return;
        }

        // Remove blocos ghost de operação já presentes para evitar duplicação.
        Matcher existingGhostBlocks = GHOST_VAR_BLOCK_COMMENT.matcher(merged);
        StringBuilder cleaned = new StringBuilder();
        while (existingGhostBlocks.find()) {
            String block = existingGhostBlocks.group();
            if (Pattern.compile("\\bvoid\\s+[A-Za-z_]\\w*\\s*\\(").matcher(block).find()) {
                existingGhostBlocks.appendReplacement(cleaned, "");
            } else {
                existingGhostBlocks.appendReplacement(cleaned, Matcher.quoteReplacement(block));
            }
        }
        existingGhostBlocks.appendTail(cleaned);
        merged = cleaned.toString();

        for (int i = 0; i < opNames.size(); i++) {
            String opName = opNames.get(i);
            String ghostBlock = blocks.get(i);
            if ("initialisation".equalsIgnoreCase(opName)) {
                merged = placeInitialisationGhostAndContract(merged, ghostBlock);
            } else {
                int insertAt = findFunctionStartForGhostSpec(merged, opName);
                if (insertAt < 0) {
                    continue;
                }
                merged = merged.substring(0, insertAt) + ghostBlock + merged.substring(insertAt);
            }
        }
        Files.writeString(mergedC, merged, StandardCharsets.UTF_8);
    }

    /**
     * Tratamento especial para {@code initialisation}: o Frama-C costuma colocar o contrato de
     * {@code Deck__INITIALISATION} no topo do ficheiro, separado da sua definição. Este método
     * remove o contrato flutuante e o reinsere, junto com o bloco ghost, imediatamente acima da
     * definição da função, produzindo a ordem: bloco-ghost → contrato → definição.
     */
    private static String placeInitialisationGhostAndContract(String content, String ghostBlock) {
        // Find the function definition (with body).
        Matcher defMatcher = INITIALISATION_FUNCTION_DEFINITION.matcher(content);
        if (!defMatcher.find()) {
            return content;
        }
        int defStart = defMatcher.start();

        // Check if a contract is already immediately adjacent (just before the definition).
        int lastSpecStart = content.lastIndexOf("/*@", defStart);
        if (lastSpecStart >= 0) {
            int lastSpecEnd = content.indexOf("*/", lastSpecStart);
            if (lastSpecEnd >= 0 && lastSpecEnd < defStart) {
                String between = content.substring(lastSpecEnd + 2, defStart).trim();
                if (between.isEmpty()) {
                    // Contract is adjacent — just insert ghost block above the contract.
                    int lineStart = content.lastIndexOf('\n', lastSpecStart);
                    int insertAt = lineStart < 0 ? 0 : lineStart + 1;
                    return content.substring(0, insertAt) + ghostBlock + content.substring(insertAt);
                }
            }
        }

        // Contract is NOT adjacent. Look for a floating ensures/assigns contract before the definition.
        Pattern floatingContractPat =
                Pattern.compile("(?s)/\\*@(?!\\s*(?:ghost|axiomatic)\\b)([\\s\\S]*?)\\*/");
        Matcher fcm = floatingContractPat.matcher(content.substring(0, defStart));
        String floatingContract = null;
        int fcStart = -1;
        int fcEnd = -1;
        while (fcm.find()) {
            String blk = fcm.group();
            if (blk.contains("ensures") || blk.contains("assigns")) {
                floatingContract = blk;
                fcStart = fcm.start();
                fcEnd = fcm.end();
            }
        }

        if (floatingContract == null) {
            // No floating contract found — just insert ghost block before the definition.
            int lineStart = content.lastIndexOf('\n', defStart);
            int insertAt = lineStart < 0 ? 0 : lineStart + 1;
            return content.substring(0, insertAt) + ghostBlock + content.substring(insertAt);
        }

        // Expand removal range to consume surrounding blank lines neatly.
        int removeStart = fcStart;
        while (removeStart > 0 && content.charAt(removeStart - 1) == '\n') {
            removeStart--;
        }
        int removeEnd = fcEnd;
        while (removeEnd < content.length() && content.charAt(removeEnd) == '\n') {
            removeEnd++;
        }

        String withoutContract = content.substring(0, removeStart) + content.substring(removeEnd);

        // Re-locate definition (offsets shifted after removal).
        Matcher defMatcher2 = INITIALISATION_FUNCTION_DEFINITION.matcher(withoutContract);
        if (!defMatcher2.find()) {
            return content; // Safety fallback — restore original.
        }
        int newDefStart = defMatcher2.start();
        int lineStart = withoutContract.lastIndexOf('\n', newDefStart);
        int insertAt = lineStart < 0 ? 0 : lineStart + 1;

        String toInsert = ghostBlock + floatingContract + "\n";
        return withoutContract.substring(0, insertAt) + toInsert + withoutContract.substring(insertAt);
    }

    private static int findFunctionStartForGhostSpec(String content, String opName) {
        if (content == null || content.isBlank() || opName == null || opName.isBlank()) {
            return -1;
        }
        List<Pattern> candidates = new ArrayList<>();
        if ("initialisation".equalsIgnoreCase(opName)) {
            candidates.add(
                    Pattern.compile(
                            "\\bvoid\\s+[A-Za-z_]\\w*__INITIALISATION\\s*\\([^;{}]*\\)\\s*\\{"));
        }
        candidates.add(
                Pattern.compile(
                        "\\bvoid\\s+[A-Za-z_]\\w*__(?i:"
                                + Pattern.quote(opName)
                                + ")\\s*\\([^;{}]*\\)\\s*\\{"));
        for (Pattern p : candidates) {
            Matcher m = p.matcher(content);
            if (!m.find()) {
                continue;
            }
            int idx = m.start();
            int specStart = content.lastIndexOf("/*@", idx);
            if (specStart >= 0) {
                int specEnd = content.indexOf("*/", specStart);
                if (specEnd >= 0 && specEnd < idx) {
                    String between = content.substring(specEnd + 2, idx).trim();
                    if (between.isEmpty()) {
                        idx = specStart;
                    }
                }
            }
            int lineStart = content.lastIndexOf('\n', idx);
            return lineStart < 0 ? 0 : lineStart + 1;
        }
        return -1;
    }

    /**
     * Para operações cujo contrato ghost não altera variáveis abstratas
     * ({@code assigns \nothing;}), remove o bloco ghost e injeta os seus
     * {@code ensures} no contrato normal da operação imediatamente adjacente.
     */
    private static void liftPureGhostEnsuresToOperationContracts(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        Pattern ghostThenNormalBeforeDefinition =
                Pattern.compile(
                        "(?s)"
                                + "(/\\*@\\s*ghost\\b[\\s\\S]*?\\*/\\s*)"
                                + "(/\\*@(?!\\s*(?:ghost|axiomatic)\\b)[\\s\\S]*?\\*/\\s*)"
                                + "(void\\s+[A-Za-z_]\\w*__([A-Za-z_]\\w*)\\s*\\([^;{}]*\\)\\s*\\{)");
        Pattern pureGhostAssignsNothing =
                Pattern.compile("(?m)^\\s*/?@\\s*assigns\\s+\\\\nothing\\s*;");
        Pattern ghostEnsureLine = Pattern.compile("(?m)^\\s*@\\s*ensures\\s+(.+?)\\s*;\\s*$");

        Matcher m = ghostThenNormalBeforeDefinition.matcher(content);
        StringBuilder sb = new StringBuilder();
        Set<String> liftedOps = new LinkedHashSet<>();
        while (m.find()) {
            String ghostBlock = m.group(1);
            String normalContract = m.group(2);
            String functionDef = m.group(3);
            String opSuffix = m.group(4);

            if (!pureGhostAssignsNothing.matcher(ghostBlock).find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }

            Matcher em = ghostEnsureLine.matcher(ghostBlock);
            List<String> ensuresToLift = new ArrayList<>();
            while (em.find()) {
                ensuresToLift.add(em.group(1).trim());
            }
            if (ensuresToLift.isEmpty()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(normalContract + functionDef));
                continue;
            }

            int contractEnd = normalContract.lastIndexOf("*/");
            if (contractEnd < 0) {
                m.appendReplacement(sb, Matcher.quoteReplacement(normalContract + functionDef));
                continue;
            }

            StringBuilder liftedEnsures = new StringBuilder();
            for (String ensure : ensuresToLift) {
                liftedEnsures.append("  @ ensures ").append(ensure).append(";\n");
            }
            String mergedContract =
                    normalContract.substring(0, contractEnd)
                            + liftedEnsures
                            + normalContract.substring(contractEnd);
            liftedOps.add(opSuffix);
            m.appendReplacement(sb, Matcher.quoteReplacement(mergedContract + functionDef));
        }
        m.appendTail(sb);
        String rewritten = sb.toString();
        for (String opSuffix : liftedOps) {
            rewritten = removeGhostCallFromMergedCode(rewritten, opSuffix);
        }
        Files.writeString(mergedC, rewritten, StandardCharsets.UTF_8);
    }

    /**
     * Remove do merge a chamada de anotação ghost
     * (ex.: {@code slash-star-at ghost op(...); star-slash}) referente à própria operação quando
     * o contrato ghost foi incorporado ao contrato normal.
     */
    private static String removeGhostCallFromMergedCode(String content, String opSuffix) {
        if (content == null || content.isEmpty() || opSuffix == null || opSuffix.isBlank()) {
            return content;
        }
        Pattern ghostCall =
                Pattern.compile(
                        "/\\*@\\s*ghost\\s+"
                                + Pattern.quote(opSuffix)
                                + "\\s*\\([^;{}]*\\)\\s*;\\s*\\*/\\s*");
        return ghostCall.matcher(content).replaceAll("");
    }

    /**
     * Garante especificamente o bloco ghost de {@code initialisation} imediatamente acima de
     * {@code Deck__INITIALISATION} (ou equivalente), mesmo se etapas anteriores falharem em casos
     * degenerados do merge.
     */
    private static void enforceInitialisationGhostSpecPlacement(Path mergedC, Path ghostCi) throws IOException {
        if (!Files.isRegularFile(ghostCi)) {
            return;
        }
        String ghostText = Files.readString(ghostCi, StandardCharsets.UTF_8).replace("dummy_", "");
        Matcher gm = GHOST_INITIALISATION_BLOCK_IN_CI.matcher(ghostText);
        if (!gm.find()) {
            return;
        }
        String initGhostBlock = gm.group().stripTrailing() + "\n\n";

        String merged = Files.readString(mergedC, StandardCharsets.UTF_8);
        merged = GHOST_INITIALISATION_BLOCK_IN_MERGED.matcher(merged).replaceAll("");

        int insertAt = findInitialisationAnchorBeforeDefinition(merged);
        if (insertAt < 0) {
            insertAt = findInitialisationAnchorFromGhostCall(merged);
        }
        if (insertAt < 0) {
            Files.writeString(mergedC, merged, StandardCharsets.UTF_8);
            return;
        }
        merged = merged.substring(0, insertAt) + initGhostBlock + merged.substring(insertAt);
        Files.writeString(mergedC, merged, StandardCharsets.UTF_8);
    }

    private static int findInitialisationAnchorBeforeDefinition(String content) {
        Matcher def = INITIALISATION_FUNCTION_DEFINITION.matcher(content);
        if (!def.find()) {
            return -1;
        }
        int idx = def.start();
        int specStart = content.lastIndexOf("/*@", idx);
        if (specStart >= 0) {
            int specEnd = content.indexOf("*/", specStart);
            if (specEnd >= 0 && specEnd < idx) {
                String between = content.substring(specEnd + 2, idx).trim();
                if (between.isEmpty()) {
                    idx = specStart;
                }
            }
        }
        int lineStart = content.lastIndexOf('\n', idx);
        return lineStart < 0 ? 0 : lineStart + 1;
    }

    /**
     * Fallback robusto: localiza o marcador {@code ghost initialisation();} no corpo da função e
     * recua até a definição de {@code __INITIALISATION}, ancorando acima da especificação contígua.
     */
    private static int findInitialisationAnchorFromGhostCall(String content) {
        int callIdx = content.indexOf(INITIALISATION_GHOST_CALL_MARKER);
        if (callIdx < 0) {
            return -1;
        }
        Matcher def = INITIALISATION_FUNCTION_DEFINITION.matcher(content);
        int defStart = -1;
        while (def.find()) {
            if (def.start() > callIdx) {
                break;
            }
            defStart = def.start();
        }
        if (defStart < 0) {
            return -1;
        }
        int idx = defStart;
        int specStart = content.lastIndexOf("/*@", idx);
        if (specStart >= 0) {
            int specEnd = content.indexOf("*/", specStart);
            if (specEnd >= 0 && specEnd < idx) {
                String between = content.substring(specEnd + 2, idx).trim();
                if (between.isEmpty()) {
                    idx = specStart;
                }
            }
        }
        int lineStart = content.lastIndexOf('\n', idx);
        return lineStart < 0 ? 0 : lineStart + 1;
    }

    /**
     * Garante a ordem canónica imediatamente antes da definição de função:
     * bloco ghost da operação -> bloco de especificação da função -> definição com corpo.
     */
    private static void normalizeGhostBlockOrderBeforeFunctionDefinitions(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        Pattern p =
                Pattern.compile(
                        "(?s)(/\\*@\\s*(?!ghost\\b)[\\s\\S]*?\\*/\\s*)" // spec normal
                                + "(/\\*@\\s*ghost\\b[\\s\\S]*?\\*/\\s*)" // spec ghost
                                + "(void\\s+[A-Za-z_]\\w*__\\w+\\s*\\([^;{}]*\\)\\s*\\{)"); // definição
        Matcher m = p.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String replacement = m.group(2) + m.group(1) + m.group(3);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        Files.writeString(mergedC, sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Passo 6: reordena no merge os blocos {@code /*@ axiomatic X { ... } star-slash} que declaram
     * {@code logic} ou {@code predicate}, quando {@code X} é o primeiro axiomatic de um ficheiro da
     * sequência {@link AcslLibIncludes#orderedLibFunctionAxiomaticNames()}. Em seguida agrupa
     * {@code is_seq_of} e {@code range_function} imediatamente a seguir a {@code sequence_iseq}
     * e, quando presente, posiciona {@code mapping_function} imediatamente abaixo de
     * {@code axiomatic new_types}. A ordem da lib,
     * (ordem da lib), porque a permutação global só envolve blocos com nomes idênticos aos da lib —
     * blocos intermédios com outros nomes (ex. axiomas Frama-C) deixavam {@code range_function} longe
     * de {@code iSeq}/{@code is_seq_of}. Por fim, se existir {@code Connection_*}, antecipa o trio de
     * sequência para antes desse bloco quando ainda estiverem depois dele. Depois, todos os
     * {@code axiomatic …_axioms} são movidos para imediatamente a seguir ao último {@code axiomatic}
     * não-{@code _axioms} que precede {@code Connection_*} (se houver), senão ao último não-{@code _axioms}
     * do ficheiro.
     */
    private static void reorderLibAxiomaticBlocksPerAcslLibIncludesOrder(Path mergedC) throws IOException {
        List<String> orderedNames = AcslLibIncludes.orderedLibFunctionAxiomaticNames();
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        if (!orderedNames.isEmpty()) {
            Map<String, Integer> rank = new HashMap<>();
            for (int i = 0; i < orderedNames.size(); i++) {
                rank.put(orderedNames.get(i), i);
            }
            content = reorderLibAxiomaticBlocksInMerged(content, rank);
        }
        content = placeMappingFunctionImmediatelyAfterNewTypes(content);
        content = clusterSequenceListAxiomaticsAfterIseq(content);
        content = moveSequenceListAxiomaticsBeforeConnection(content);
        content = deferAxiomsSuffixBlocksAfterNonAxioms(content);
        Files.writeString(mergedC, content, StandardCharsets.UTF_8);
    }

    /**
     * Passo 10–11: lê {@code ACSL_Lib/lemmas.acsl}; passo 11 remove cada {@code admit lemma} que invoque
     * (como chamada {@code nome(}) algum símbolo de biblioteca mapeado em {@link AcslLibIncludes} que não
     * esteja no fecho de includes deduzido do texto dos ficheiros {@code .acsl} importados e do
     * {@code ghost_operations.ci}. Passo 10 anexa o resultado ao fim de {@code merged_code.c} num
     * comentário ACSL.
     *
     * @param allowedLibSymbols símbolos permitidos (ex.: {@code belongs}, {@code ran}); vazio → não anexa
     */
    private static void appendLemmasAcslLibToMergedEnd(Path mergedC, Set<String> allowedLibSymbols)
            throws IOException {
        if (allowedLibSymbols == null || allowedLibSymbols.isEmpty()) {
            return;
        }
        Optional<String> lemmas = readAcslLibLemmasText();
        if (lemmas.isEmpty() || lemmas.get().isBlank()) {
            return;
        }
        String filtered = filterLemmasByAllowedLibSymbols(lemmas.get(), allowedLibSymbols);
        if (filtered.isBlank()) {
            return;
        }
        String wrapped = "/*@\n" + filtered.stripTrailing() + "\n */\n";
        String existing = Files.readString(mergedC, StandardCharsets.UTF_8);
        String sep = existing.endsWith("\n") ? "" : "\n";
        Files.writeString(mergedC, existing + sep + wrapped, StandardCharsets.UTF_8);
    }

    /**
     * Chamadas {@code identificador(} típicas da ACSL_Lib (identificador ASCII minúsculo, não precedido
     * de {@code \} nem de carácter alfanumérico).
     */
    private static final Pattern LEMMA_LIB_STYLE_CALL =
            Pattern.compile("(?<![A-Za-z0-9_\\\\])([a-z][a-z0-9_]*)\\s*\\(");

    /**
     * Mantém apenas lemas cujo corpo não contém chamadas a símbolos de lib fora de {@code allowed}.
     */
    private static String filterLemmasByAllowedLibSymbols(String lemmasFileText, Set<String> allowed) {
        int open = lemmasFileText.indexOf('{');
        int close = lemmasFileText.lastIndexOf('}');
        if (open < 0 || close <= open) {
            return lemmasFileText.stripTrailing();
        }
        String head = lemmasFileText.substring(0, open + 1);
        String inner = lemmasFileText.substring(open + 1, close);
        String tail = lemmasFileText.substring(close);
        String[] chunks = inner.split("(?m)(?=^\\s*admit\\s+lemma\\s+)");
        List<String> kept = new ArrayList<>();
        for (String chunk : chunks) {
            String t = chunk.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (!t.startsWith("admit")) {
                continue;
            }
            if (lemmaBodyUsesDisallowedLibCall(t, allowed)) {
                continue;
            }
            kept.add(t);
        }
        if (kept.isEmpty()) {
            return "";
        }
        return head + "\n\n    " + String.join("\n\n    ", kept) + "\n\n" + tail.stripLeading();
    }

    private static boolean lemmaBodyUsesDisallowedLibCall(String admitLemmaChunk, Set<String> allowed) {
        Matcher m = LEMMA_LIB_STYLE_CALL.matcher(admitLemmaChunk);
        while (m.find()) {
            String name = m.group(1);
            if (allowed.contains(name)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static Optional<String> readAcslLibLemmasText() throws IOException {
        try (InputStream in = B2ACSLPipeline.class.getResourceAsStream(ACSL_LIB_LEMMAS_RESOURCE)) {
            if (in != null) {
                return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        Path dev = B2AcslLibraryPaths.devRoot().resolve("lemmas.acsl");
        if (Files.isRegularFile(dev)) {
            return Optional.of(Files.readString(dev, StandardCharsets.UTF_8));
        }
        return Optional.empty();
    }

    /**
     * Cauda de {@link AcslLibIncludes#orderedLibFunctionAxiomaticNames()} para listas: declarações que
     * devem preceder {@code Connection_*} quando o Frama-C as coloca depois.
     */
    private static final List<String> SEQUENCE_LIST_AXIOMATIC_ORDER =
            List.of("sequence_iseq", "is_seq_of", "range_function");

    /**
     * Garante a ordem {@code sequence_iseq} → {@code is_seq_of} → {@code range_function} com os dois
     * últimos colados ao fim do bloco {@code sequence_iseq}. Remove {@code is_seq_of} e
     * {@code range_function} das posições actuais (da direita para a esquerda) e reinsere-os após
     * {@code sequence_iseq}.
     */
    private static String clusterSequenceListAxiomaticsAfterIseq(String content) {
        List<AcsCommentSpan> spans = findAllAcsCommentSpans(content);
        AcsCommentSpan seq = null;
        AcsCommentSpan isof = null;
        AcsCommentSpan range = null;
        for (AcsCommentSpan sp : spans) {
            if (sp.axiomaticName == null || !LOGIC_OR_PREDICATE_IN_BLOCK.matcher(sp.text).find()) {
                continue;
            }
            if ("sequence_iseq".equals(sp.axiomaticName) && seq == null) {
                seq = sp;
            } else if ("is_seq_of".equals(sp.axiomaticName) && isof == null) {
                isof = sp;
            } else if ("range_function".equals(sp.axiomaticName) && range == null) {
                range = sp;
            }
        }
        if (seq == null || range == null) {
            return content;
        }
        List<AcsCommentSpan> toRemove = new ArrayList<>();
        if (isof != null) {
            toRemove.add(isof);
        }
        toRemove.add(range);
        toRemove.sort(Comparator.comparingInt((AcsCommentSpan s) -> s.start).reversed());
        String cut = content;
        for (AcsCommentSpan sp : toRemove) {
            cut = cut.substring(0, sp.start) + cut.substring(sp.end);
        }
        spans = findAllAcsCommentSpans(cut);
        AcsCommentSpan seqAfter = null;
        for (AcsCommentSpan sp : spans) {
            if ("sequence_iseq".equals(sp.axiomaticName)
                    && LOGIC_OR_PREDICATE_IN_BLOCK.matcher(sp.text).find()) {
                seqAfter = sp;
                break;
            }
        }
        if (seqAfter == null) {
            return cut;
        }
        StringBuilder insert = new StringBuilder();
        if (isof != null) {
            insert.append(isof.text);
        }
        insert.append(range.text);
        return cut.substring(0, seqAfter.end) + insert + cut.substring(seqAfter.end);
    }

    /**
     * Quando o bloco {@code axiomatic mapping_function} existir no merge, move-o para imediatamente
     * depois de {@code axiomatic new_types} para manter as definições de mapeamento de listas junto
     * da declaração de tipos base.
     */
    private static String placeMappingFunctionImmediatelyAfterNewTypes(String content) {
        List<AcsCommentSpan> spans = findAllAcsCommentSpans(content);
        AcsCommentSpan mapping = null;
        AcsCommentSpan newTypes = null;
        for (AcsCommentSpan sp : spans) {
            if ("mapping_function".equals(sp.axiomaticName)
                    && LOGIC_OR_PREDICATE_IN_BLOCK.matcher(sp.text).find()
                    && mapping == null) {
                mapping = sp;
            } else if ("new_types".equals(sp.axiomaticName) && newTypes == null) {
                newTypes = sp;
            }
        }
        if (mapping == null || newTypes == null) {
            return content;
        }

        String cut = content.substring(0, mapping.start) + content.substring(mapping.end);
        List<AcsCommentSpan> cutSpans = findAllAcsCommentSpans(cut);
        AcsCommentSpan newTypesAfter = null;
        for (AcsCommentSpan sp : cutSpans) {
            if ("new_types".equals(sp.axiomaticName)) {
                newTypesAfter = sp;
                break;
            }
        }
        if (newTypesAfter == null) {
            return content;
        }
        return cut.substring(0, newTypesAfter.end) + mapping.text + cut.substring(newTypesAfter.end);
    }

    /**
     * Remove da posição atual os blocos de sequência (lista) que estão depois do primeiro
     * {@code axiomatic Connection_*} e reinsere-os imediatamente antes desse bloco, na ordem da lib.
     */
    private static String moveSequenceListAxiomaticsBeforeConnection(String content) {
        List<AcsCommentSpan> spans = findAllAcsCommentSpans(content);
        int connectionStart = -1;
        for (AcsCommentSpan sp : spans) {
            if (sp.axiomaticName != null && sp.axiomaticName.startsWith("Connection_")) {
                connectionStart = sp.start;
                break;
            }
        }
        if (connectionStart < 0) {
            return content;
        }
        List<AcsCommentSpan> toMove = new ArrayList<>();
        for (String name : SEQUENCE_LIST_AXIOMATIC_ORDER) {
            for (AcsCommentSpan sp : spans) {
                if (name.equals(sp.axiomaticName)
                        && sp.start > connectionStart
                        && LOGIC_OR_PREDICATE_IN_BLOCK.matcher(sp.text).find()) {
                    toMove.add(sp);
                    break;
                }
            }
        }
        if (toMove.isEmpty()) {
            return content;
        }
        toMove.sort(Comparator.comparingInt((AcsCommentSpan s) -> s.start).reversed());
        String cut = content;
        for (AcsCommentSpan sp : toMove) {
            cut = cut.substring(0, sp.start) + cut.substring(sp.end);
        }
        List<AcsCommentSpan> after = findAllAcsCommentSpans(cut);
        int newConn = -1;
        for (AcsCommentSpan sp : after) {
            if (sp.axiomaticName != null && sp.axiomaticName.startsWith("Connection_")) {
                newConn = sp.start;
                break;
            }
        }
        if (newConn < 0) {
            return cut;
        }
        StringBuilder insert = new StringBuilder();
        for (String name : SEQUENCE_LIST_AXIOMATIC_ORDER) {
            for (AcsCommentSpan sp : toMove) {
                if (name.equals(sp.axiomaticName)) {
                    insert.append(sp.text);
                    break;
                }
            }
        }
        return cut.substring(0, newConn) + insert + cut.substring(newConn);
    }

    /**
     * Move todos os comentários {@code /*@ axiomatic Nome_axioms …} para imediatamente depois do último
     * (por posição no ficheiro) comentário {@code axiomatic} com nome que <strong>não</strong> termina em
     * {@code _axioms}, restringido aos que aparecem <strong>antes</strong> do primeiro
     * {@code Connection_*} (se existir), para os axiomas da lib ficarem antes do elo de refinamento.
     * Preserva a ordem relativa entre os blocos {@code _axioms}. Sem {@code Connection_*}, usa o último
     * {@code axiomatic} não-{@code _axioms} de todo o ficheiro.
     */
    private static String deferAxiomsSuffixBlocksAfterNonAxioms(String content) {
        List<AcsCommentSpan> spans = findAllAcsCommentSpans(content);
        List<AcsCommentSpan> axiomSuffixBlocks = new ArrayList<>();
        for (AcsCommentSpan sp : spans) {
            if (sp.axiomaticName == null) {
                continue;
            }
            if (sp.axiomaticName.endsWith("_axioms")) {
                axiomSuffixBlocks.add(sp);
            }
        }
        if (axiomSuffixBlocks.isEmpty()) {
            return content;
        }
        axiomSuffixBlocks.sort(Comparator.comparingInt((AcsCommentSpan a) -> a.start));
        StringBuilder axiomBlob = new StringBuilder();
        for (AcsCommentSpan sp : axiomSuffixBlocks) {
            axiomBlob.append(sp.text);
        }
        axiomSuffixBlocks.sort(
                Comparator.comparingInt((AcsCommentSpan s) -> s.start).reversed());
        String cut = content;
        for (AcsCommentSpan sp : axiomSuffixBlocks) {
            cut = cut.substring(0, sp.start) + cut.substring(sp.end);
        }
        List<AcsCommentSpan> after = findAllAcsCommentSpans(cut);
        int connAfter = -1;
        for (AcsCommentSpan sp : after) {
            if (sp.axiomaticName != null && sp.axiomaticName.startsWith("Connection_")) {
                connAfter = sp.start;
                break;
            }
        }
        AcsCommentSpan anchor = null;
        for (AcsCommentSpan sp : after) {
            if (sp.axiomaticName == null || sp.axiomaticName.endsWith("_axioms")) {
                continue;
            }
            if (connAfter >= 0 && sp.start >= connAfter) {
                continue;
            }
            if (anchor == null || sp.start > anchor.start) {
                anchor = sp;
            }
        }
        int insertAt =
                anchor != null
                        ? anchor.end
                        : findPreambleInsertIndex(cut);
        return cut.substring(0, insertAt) + axiomBlob + cut.substring(insertAt);
    }

    private static final Pattern AXIOMATIC_NAME_IN_ACSL_COMMENT =
            Pattern.compile("axiomatic\\s+(\\w+)");

    private static final Pattern LOGIC_OR_PREDICATE_IN_BLOCK =
            Pattern.compile("\\b(logic|predicate)\\s+");

    private static String reorderLibAxiomaticBlocksInMerged(String content, Map<String, Integer> rank) {
        List<AcsCommentSpan> spans = findAllAcsCommentSpans(content);
        List<AcsCommentSpan> sortable = new ArrayList<>();
        for (AcsCommentSpan sp : spans) {
            if (sp.axiomaticName != null
                    && rank.containsKey(sp.axiomaticName)
                    && LOGIC_OR_PREDICATE_IN_BLOCK.matcher(sp.text).find()) {
                sortable.add(sp);
            }
        }
        if (sortable.size() <= 1) {
            return content;
        }
        List<AcsCommentSpan> byFile = new ArrayList<>(sortable);
        byFile.sort(Comparator.comparingInt(a -> a.start));
        List<AcsCommentSpan> byRank = new ArrayList<>(sortable);
        byRank.sort(
                Comparator.comparingInt((AcsCommentSpan a) -> rank.get(a.axiomaticName))
                        .thenComparingInt(a -> a.start));
        Map<Integer, String> replacement = new HashMap<>();
        for (int i = 0; i < byFile.size(); i++) {
            replacement.put(byFile.get(i).start, byRank.get(i).text);
        }
        StringBuilder sb = new StringBuilder();
        int cursor = 0;
        for (AcsCommentSpan sp : spans) {
            sb.append(content, cursor, sp.start);
            String rep = replacement.get(sp.start);
            if (rep != null) {
                sb.append(rep);
            } else {
                sb.append(sp.text);
            }
            cursor = sp.end;
        }
        sb.append(content, cursor, content.length());
        return sb.toString();
    }

    private static List<AcsCommentSpan> findAllAcsCommentSpans(String content) {
        List<AcsCommentSpan> spans = new ArrayList<>();
        int from = 0;
        while (from < content.length()) {
            int start = content.indexOf("/*@", from);
            if (start < 0) {
                break;
            }
            int close = content.indexOf("*/", start + 3);
            if (close < 0) {
                break;
            }
            int end = skipNewlineAfter(close + 2, content);
            String text = content.substring(start, end);
            Matcher m = AXIOMATIC_NAME_IN_ACSL_COMMENT.matcher(text);
            String axName = m.find() ? m.group(1) : null;
            spans.add(new AcsCommentSpan(start, end, text, axName));
            from = end;
        }
        return spans;
    }

    private static final class AcsCommentSpan {
        final int start;
        final int end;
        final String text;
        final String axiomaticName;

        AcsCommentSpan(int start, int end, String text, String axiomaticName) {
            this.start = start;
            this.end = end;
            this.text = text;
            this.axiomaticName = axiomaticName;
        }
    }

    private static int skipNewlineAfter(int pos, String s) {
        int i = pos;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '\n') {
                return i + 1;
            }
            if (ch == '\r') {
                i++;
                if (i < s.length() && s.charAt(i) == '\n') {
                    i++;
                }
                return i;
            }
            break;
        }
        return i;
    }

    private static int findMatchingBrace(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Índice do primeiro {@code /*@} após o preâmbulo inicial; se não houver, o fim do texto. */
    private static int findPreambleInsertIndex(String s) {
        int i = 0;
        while (i < s.length()) {
            int lineStart = i;
            int nl = s.indexOf('\n', i);
            int lineEnd = nl < 0 ? s.length() : nl + 1;
            String line = s.substring(i, lineEnd);
            String left = line.stripLeading();
            if (left.startsWith("/*@")) {
                return lineStart;
            }
            String t = line.strip();
            if (t.isEmpty()
                    || t.startsWith("#include")
                    || t.startsWith("/* Generated")
                    || t.startsWith("//")) {
                i = lineEnd;
                continue;
            }
            i = lineEnd;
        }
        return s.length();
    }

    private static void deleteRecursive(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {}
                    });
                }
            }
        } catch (IOException ignored) {}
    }
}
