package com.example.gui.parsing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Validates a project folder chosen in step 1 and lists its functions. */
public final class ProjectScanner {

    /**
     * @param folder the project root actually used (the parent when {@code bdp/} itself was chosen)
     * @param problem why the folder cannot be used, {@code null} when it is valid
     */
    public record ProjectScan(Path folder, String problem, List<String> functions) {

        public boolean valid() {
            return problem == null;
        }
    }

    private ProjectScanner() {}

    public static ProjectScan scan(Path chosen) {
        if (chosen == null || !Files.isDirectory(chosen)) {
            return new ProjectScan(chosen, "The folder does not exist.", List.of());
        }
        Path folder = chosen.toAbsolutePath().normalize();
        if (folder.getFileName() != null
                && folder.getFileName().toString().equals("bdp")
                && folder.getParent() != null
                && Files.isDirectory(folder.getParent().resolve("lang").resolve("c"))) {
            folder = folder.getParent();
        }
        Path bdp = folder.resolve("bdp");
        Path cDir = folder.resolve("lang").resolve("c");
        if (!Files.isDirectory(bdp)) {
            return new ProjectScan(folder, "No bdp/ folder: choose the root of an Atelier B project.", List.of());
        }
        if (!hasBxml(bdp)) {
            return new ProjectScan(folder, "No .bxml file in bdp/: export the B components to BXML first.", List.of());
        }
        if (!Files.isDirectory(cDir)) {
            return new ProjectScan(folder, "No lang/c/ folder: generate the C code of the project first.", List.of());
        }
        try {
            List<String> functions = CFunctionScanner.scan(cDir);
            if (functions.isEmpty()) {
                return new ProjectScan(
                        folder, "No function named <Machine>__<Operation> was found in lang/c/*.c.", List.of());
            }
            return new ProjectScan(folder, null, functions);
        } catch (IOException e) {
            return new ProjectScan(folder, "Could not read lang/c: " + e.getMessage(), List.of());
        }
    }

    private static boolean hasBxml(Path bdp) {
        try (Stream<Path> files = Files.walk(bdp)) {
            return files.anyMatch(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".bxml"));
        } catch (IOException e) {
            return false;
        }
    }
}
