package com.example;

import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
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

    /** Lemas admitidos da ACSL_Lib (anexados ao fim do merge Frama-C). */
    private static final String ACSL_LIB_LEMMAS_RESOURCE = "/ACSL_Lib/lemmas.acsl";

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
            reorderLibAxiomaticBlocksPerAcslLibIncludesOrder(mergedCode);
            appendLemmasAcslLibToMergedEnd(mergedCode);

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
                            "20",
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

    /**
     * Passo 6: reordena no merge os blocos {@code /*@ axiomatic X { ... } star-slash} que declaram
     * {@code logic} ou {@code predicate}, quando {@code X} é o primeiro axiomatic de um ficheiro da
     * sequência {@link AcslLibIncludes#orderedLibFunctionAxiomaticNames()}. Depois, se existir bloco
     * {@code Connection_*}, antecipa {@code sequence_iseq}, {@code is_seq_of} e {@code range_function}
     * (ordem da lib) para antes desse bloco — caso contrário a permutação não desloca declarações para
     * antes de anotações que já usam {@code ran} / {@code is_seq_of}.
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
        content = moveSequenceListAxiomaticsBeforeConnection(content);
        Files.writeString(mergedC, content, StandardCharsets.UTF_8);
    }

    /**
     * Passo 10: anexa ao fim de {@code merged_code.c} o texto de {@code ACSL_Lib/lemmas.acsl} (classpath
     * ou {@code src/main/resources} em desenvolvimento), dentro de um comentário ACSL
     * {@code slash-star-at … star-slash}.
     */
    private static void appendLemmasAcslLibToMergedEnd(Path mergedC) throws IOException {
        Optional<String> lemmas = readAcslLibLemmasText();
        if (lemmas.isEmpty() || lemmas.get().isBlank()) {
            return;
        }
        String body = lemmas.get().stripTrailing();
        String wrapped = "/*@\n" + body + "\n */\n";
        String existing = Files.readString(mergedC, StandardCharsets.UTF_8);
        String sep = existing.endsWith("\n") ? "" : "\n";
        Files.writeString(mergedC, existing + sep + wrapped, StandardCharsets.UTF_8);
    }

    private static Optional<String> readAcslLibLemmasText() throws IOException {
        try (InputStream in = B2ACSLPipeline.class.getResourceAsStream(ACSL_LIB_LEMMAS_RESOURCE)) {
            if (in != null) {
                return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        Path dev =
                Path.of(System.getProperty("user.dir", "."))
                        .resolve("src/main/resources/ACSL_Lib/lemmas.acsl")
                        .toAbsolutePath()
                        .normalize();
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
