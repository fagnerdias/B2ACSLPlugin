package com.example.comparator.util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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

    /**
     * Same as {@link #run(List, Path, Path, long)}, but also hands every output line to {@code
     * lineSink} as it is produced (for live logs). If the calling thread is interrupted, the process
     * and its descendants are killed before the {@link InterruptedException} propagates.
     *
     * @param lineSink receives each combined stdout/stderr line; {@code null} behaves like the
     *     4-argument overload
     */
    public static Result run(
            List<String> command, Path workingDir, Path logFile, long timeoutSeconds, Consumer<String> lineSink)
            throws IOException, InterruptedException {
        if (lineSink == null) {
            return run(command, workingDir, logFile, timeoutSeconds);
        }
        Files.createDirectories(logFile.toAbsolutePath().getParent());
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        pb.redirectErrorStream(true);
        Process process = pb.start();
        Thread pump = new Thread(() -> pump(process, logFile, lineSink), "process-output-pump");
        pump.setDaemon(true);
        pump.start();
        boolean finished = false;
        try {
            if (timeoutSeconds > 0) {
                finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            } else {
                process.waitFor();
                finished = true;
            }
        } finally {
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
        pump.join(TimeUnit.SECONDS.toMillis(10));
        String output = Files.readString(logFile, StandardCharsets.UTF_8);
        return new Result(finished ? process.exitValue() : -1, !finished, output);
    }

    private static void pump(Process process, Path logFile, Consumer<String> lineSink) {
        try (BufferedReader in = process.inputReader(StandardCharsets.UTF_8);
                BufferedWriter out = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                out.write(line);
                out.newLine();
                lineSink.accept(line);
            }
        } catch (IOException e) {
            // stream closed because the process was killed: the log keeps what was read so far
        }
    }

    /** The {@code java} executable of the running JVM. */
    public static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
