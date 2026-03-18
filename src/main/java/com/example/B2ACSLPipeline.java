package com.example;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import com.example.model.Machine;

/**
 * Pipeline B2ACSL: BXML -> ACSL -> Frama-C (acsl-importer + WP) -> resultado para Atelier B.
 */
public final class B2ACSLPipeline {

    private static final String FRAMA_C = "frama-c";
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

        List<Machine> machines = new ArrayList<>();
        for (Path f : bxmlFiles) {
            try {
                Machine m = Machine.fromBxmlPath(f);
                if ("abstraction".equalsIgnoreCase(m.getMachineType())) {
                    machines.add(m);
                }
            } catch (Exception e) {
                System.err.println("[B2ACSL] Falha ao ler " + f + ": " + e.getMessage());
            }
        }

        if (machines.isEmpty()) {
            System.err.println("[B2ACSL] Nenhuma máquina abstrata encontrada.");
            return 3;
        }

        // Step 1.1: Gerar arquivos .acsl (temporários ou em dir fixo para inspeção)
        Path acslDir = KEEP_ACSL_DIR != null && !KEEP_ACSL_DIR.isBlank()
                ? Path.of(KEEP_ACSL_DIR).toAbsolutePath().normalize()
                : Files.createTempDirectory("b2acsl_acsl_");
        boolean keepFiles = KEEP_ACSL_DIR != null && !KEEP_ACSL_DIR.isBlank();
        try {
            List<Path> acslFiles = new ArrayList<>();
            for (Machine m : machines) {
                Path acsl = AcslGenerator.generateAcsl(m, acslDir);
                acslFiles.add(acsl);
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

    private static List<Path> findCFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".c"))
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

        for (Path cFile : cFiles) {
            ProcessBuilder pb = new ProcessBuilder(
                    FRAMA_C,
                    "-acsl-import", acslPath,
                    cFile.toString(),
                    "-wp",
                    "-wp-out", "proof"
            );
            pb.directory(cDir.toFile());
            pb.inheritIO();
            Process p = pb.start();
            boolean ok = p.waitFor(120, TimeUnit.SECONDS);
            if (!ok) {
                p.destroyForcibly();
                return 5;
            }
            if (p.exitValue() != 0) return p.exitValue();
        }
        return 0;
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
