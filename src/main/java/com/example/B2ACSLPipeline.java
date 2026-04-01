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
import java.util.regex.Pattern;

import com.example.bxml.BxmlGluingNormalizer;
import com.example.model.Machine;

import org.w3c.dom.Element;

/**
 * Pipeline B2ACSL: BXML -> ACSL -> Frama-C ({@code -acsl-import} + {@code merged_code.c} + WP) -> resultado para Atelier B.
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

        for (Path cFile : cFiles) {
            // frama-c -acsl-import <acsl> <c> -print -no-unicode  (saída → merged_code.c)
            ProcessBuilder importPb =
                    new ProcessBuilder(
                            FRAMA_C,
                            "-acsl-import",
                            acslPath,
                            cFile.toString(),
                            "-print",
                            "-no-unicode");
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
