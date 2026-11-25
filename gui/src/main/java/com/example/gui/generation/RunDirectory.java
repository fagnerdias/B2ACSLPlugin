package com.example.gui.generation;

import com.example.gui.model.GenerationMode;
import com.example.gui.model.Generator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Where one run writes its files: {@code <project>/b2acsl-wp/<yyyyMMdd_HHmmss>/}, with the published
 * {@code merged_code.c} ({@code plugin/} and {@code atl/} in compare mode), the logs and the
 * intermediate workspaces ({@code work-<generator>/}). The project's {@code bdp/} and {@code lang/c/}
 * are never written.
 */
public record RunDirectory(Path root, GenerationMode mode) {

    public static final String FOLDER = "b2acsl-wp";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public static RunDirectory create(Path projectFolder, GenerationMode mode) throws IOException {
        Path base = projectFolder.resolve(FOLDER);
        String stamp = LocalDateTime.now().format(STAMP);
        Path root = base.resolve(stamp);
        for (int i = 2; Files.exists(root); i++) {
            root = base.resolve(stamp + "-" + i);
        }
        Files.createDirectories(root);
        return new RunDirectory(root, mode);
    }

    /** Workspace of one generator; must not exist yet (the comparator's Workspace.prepare creates it). */
    public Path workDir(Generator generator) {
        return root.resolve("work-" + generator.id());
    }

    public Path publishedFile(Generator generator) {
        return mode.isCompare() ? root.resolve(generator.id()).resolve("merged_code.c") : root.resolve("merged_code.c");
    }

    /** {@code merged_code.c}, {@code plugin/merged_code.c} or {@code atl/merged_code.c}. */
    public String displayPath(Generator generator) {
        return root.relativize(publishedFile(generator)).toString().replace('\\', '/');
    }

    public Path resultFile(Generator generator) {
        return root.resolve(generator.id() + "-generation.json");
    }

    public Path generationLog() {
        return root.resolve("generation.log");
    }

    public Path verificationLog() {
        return root.resolve("verification.log");
    }
}
