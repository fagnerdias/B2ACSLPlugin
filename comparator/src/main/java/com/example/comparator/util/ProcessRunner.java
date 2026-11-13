package com.example.comparator.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs an external command, sending its combined stdout/stderr to a log file. */
public final class ProcessRunner {

    /**
     * @param exitCode process exit code ({@code -1} when it timed out)
     * @param timedOut {@code true} if the process was killed for exceeding the timeout
     * @param output the full combined output (also written to the log file)
     */
    public record Result(int exitCode, boolean timedOut, String output) {}

    private ProcessRunner() {}

    /**
     * @param timeoutSeconds {@code <= 0} waits indefinitely
     */
    public static Result run(List<String> command, Path workingDir, Path logFile, long timeoutSeconds)
            throws IOException, InterruptedException {
        Files.createDirectories(logFile.toAbsolutePath().getParent());
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile.toFile());
        Process process = pb.start();
        boolean finished;
        if (timeoutSeconds > 0) {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } else {
            process.waitFor();
            finished = true;
        }
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        String output = Files.readString(logFile, StandardCharsets.UTF_8);
        return new Result(finished ? process.exitValue() : -1, !finished, output);
    }

    /** The {@code java} executable of the running JVM. */
    public static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
