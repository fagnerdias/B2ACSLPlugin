package com.example;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Fontes C de um projeto ({@code lang/c}), procuradas recursivamente.
 *
 * <p>O código de uma máquina BASIC (sem implementação B) é escrito à mão e pode ficar numa
 * subpasta de {@code lang/c} (ex. {@code lang/c/base/Digit_input.c}). Por isso a busca desce nas
 * subpastas, mas ignora as que o próprio pipeline gera: {@code import/} (cópia da B2ACSLLib),
 * {@code .frama-c/} e pastas ocultas, além do {@code merged_code.c}.
 */
public final class CSourceFiles {

    private static final Set<String> GENERATED_DIRS = Set.of("import");

    private CSourceFiles() {}

    /** Arquivos regulares sob {@code root} com uma das extensões dadas, em ordem de caminho. */
    public static List<Path> list(Path root, String... extensions) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> hasExtension(p, extensions))
                    .filter(p -> !FramaCRunner.MERGED_CODE_FILE_NAME.equalsIgnoreCase(p.getFileName().toString()))
                    .filter(p -> !isInGeneratedDir(root, p))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    /** {@code true} se algum diretório entre {@code root} e {@code file} for gerado ou oculto. */
    static boolean isInGeneratedDir(Path root, Path file) {
        Path rel = root.relativize(file);
        for (int i = 0; i < rel.getNameCount() - 1; i++) {
            String dir = rel.getName(i).toString();
            if (dir.startsWith(".") || GENERATED_DIRS.contains(dir)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasExtension(Path p, String... extensions) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : extensions) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }
}
