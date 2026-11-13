package com.example.comparator.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Small file-system helpers used to build the isolated workspaces. */
public final class FileTrees {

    private FileTrees() {}

    /** Copies every regular file under {@code source} accepted by {@code filter}, keeping relative paths. */
    public static void copyTree(Path source, Path target, Predicate<Path> filter) throws IOException {
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).filter(filter).toList()) {
                Path dest = target.resolve(source.relativize(file).toString());
                Files.createDirectories(dest.getParent());
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Regular files directly inside {@code dir} (not recursive) accepted by {@code filter}, sorted by name. */
    public static List<Path> listFiles(Path dir, Predicate<Path> filter) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(filter)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    public static Predicate<Path> hasExtension(String... extensions) {
        return p -> {
            String name = p.getFileName().toString().toLowerCase();
            for (String ext : extensions) {
                if (name.endsWith(ext)) {
                    return true;
                }
            }
            return false;
        };
    }
}
