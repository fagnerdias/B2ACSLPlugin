package com.example.comparator.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Identifies the git revision of a working tree, so reports can be reproduced. */
public final class GitInfo {

    public static final String UNKNOWN = "unknown";

    private GitInfo() {}

    /**
     * Short commit hash of {@code dir}'s working tree, with a {@code -dirty} suffix when tracked
     * files have uncommitted changes; {@link #UNKNOWN} if {@code dir} is not inside a git repository.
     */
    public static String describe(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return UNKNOWN;
        }
        String sha = git(dir, "rev-parse", "--short", "HEAD");
        if (sha == null || sha.isBlank()) {
            return UNKNOWN;
        }
        String status = git(dir, "status", "--porcelain", "--untracked-files=no");
        return sha.trim() + (status != null && !status.isBlank() ? "-dirty" : "");
    }

    private static String git(Path dir, String... args) {
        try {
            List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", dir.toString()));
            cmd.addAll(List.of(args));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
                return null;
            }
            return out;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
