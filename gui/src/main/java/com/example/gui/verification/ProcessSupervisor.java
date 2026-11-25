package com.example.gui.verification;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs an external command off the Event Dispatch Thread: combined stdout/stderr is collected and
 * handed line by line to a callback, with an optional wall-clock limit and cancellation that kills
 * the whole process tree.
 */
public final class ProcessSupervisor {

    /**
     * @param exitCode exit code, {@code -1} when the process was killed
     * @param timedOut killed because the wall-clock limit was reached
     * @param cancelled killed through the {@link Cancellation}
     */
    public record Outcome(int exitCode, boolean timedOut, boolean cancelled, String output) {}

    private ProcessSupervisor() {}

    /**
     * @param timeoutSeconds {@code <= 0} waits until the process ends or is cancelled
     * @throws IOException when the command cannot be started (e.g. not installed)
     */
    public static Outcome run(
            List<String> command, Path workingDir, long timeoutSeconds, Consumer<String> lineSink, Cancellation cancel)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
        Process process = pb.start();
        Cancellation token = cancel == null ? new Cancellation() : cancel;
        token.register(process);
        StringBuilder output = new StringBuilder();
        Thread pump = new Thread(() -> pump(process, output, lineSink), "process-output-pump");
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
                Cancellation.killTree(process);
                process.waitFor(10, TimeUnit.SECONDS);
            }
            token.unregister(process);
        }
        pump.join(TimeUnit.SECONDS.toMillis(10));
        boolean cancelled = token.isCancelled();
        String text;
        synchronized (output) {
            text = output.toString();
        }
        int exit = finished && !cancelled ? process.exitValue() : -1;
        return new Outcome(exit, !finished && !cancelled, cancelled, text);
    }

    private static void pump(Process process, StringBuilder output, Consumer<String> lineSink) {
        try (BufferedReader in = process.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                synchronized (output) {
                    output.append(line).append('\n');
                }
                if (lineSink != null) {
                    lineSink.accept(line);
                }
            }
        } catch (IOException e) {
            // stream closed because the process was killed
        }
    }

    private static java.io.File nullDevice() {
        return new java.io.File(System.getProperty("os.name", "").startsWith("Windows") ? "NUL" : "/dev/null");
    }
}
