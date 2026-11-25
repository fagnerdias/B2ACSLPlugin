package com.example.gui.verification;

import com.example.gui.model.FunctionResult;
import com.example.gui.model.GeneratedSpecification;
import com.example.gui.model.Generator;
import com.example.gui.model.Issue;
import com.example.gui.model.VerificationRun;
import com.example.gui.model.WpConfig;
import com.example.gui.parsing.WpOutputParser;
import com.example.ui.WpOptionsDialog.WpOptions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * Runs {@code frama-c -wp} once per selected function and per specification, sequentially (Plugin
 * first), reporting progress through a {@link Listener}. Must not run on the Event Dispatch Thread.
 */
public final class VerificationService {

    /** Progress callbacks, called from the worker thread. */
    public interface Listener {
        void functionStarted(Generator generator, String function);

        void logLine(Generator generator, String function, String line);

        void functionFinished(FunctionResult result);
    }

    /** Frama-C could not be started at all (not installed, not on PATH). */
    public static final class ToolMissingException extends IOException {
        public ToolMissingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private VerificationService() {}

    /**
     * @throws CancellationException when {@code cancel} was triggered
     */
    public static List<VerificationRun> run(
            String projectName,
            List<GeneratedSpecification> specs,
            List<String> functions,
            WpConfig config,
            Listener listener,
            Cancellation cancel)
            throws IOException, InterruptedException {
        WpOptions options = config.toWpOptions(projectName);
        int wallClock = options.functionTimeoutSeconds();
        List<VerificationRun> runs = new ArrayList<>();
        for (GeneratedSpecification spec : specs) {
            long specStart = System.nanoTime();
            StringBuilder raw = new StringBuilder();
            List<FunctionResult> results = new ArrayList<>();
            for (String fn : functions) {
                if (cancel.isCancelled()) {
                    throw new CancellationException();
                }
                listener.functionStarted(spec.generator(), fn);
                List<String> cmd = WpCommandFactory.command(spec.mergedFile(), options, fn);
                String header = "===== " + fn + " (" + spec.displayPath() + ") =====";
                String commandLine = "$ " + String.join(" ", cmd);
                raw.append(header).append('\n').append(commandLine).append('\n');
                listener.logLine(spec.generator(), fn, header);
                listener.logLine(spec.generator(), fn, commandLine);
                long start = System.nanoTime();
                ProcessSupervisor.Outcome outcome;
                try {
                    outcome = ProcessSupervisor.run(
                            cmd,
                            spec.mergedFile().getParent(),
                            wallClock,
                            line -> listener.logLine(spec.generator(), fn, line),
                            cancel);
                } catch (IOException e) {
                    throw new ToolMissingException("Frama-C could not be started: " + e.getMessage(), e);
                }
                if (outcome.cancelled()) {
                    throw new CancellationException();
                }
                raw.append(outcome.output());
                String killed = outcome.timedOut() ? "Frama-C did not finish within " + wallClock + " s" : null;
                FunctionResult result = WpOutputParser.parse(
                        fn,
                        spec.generator(),
                        outcome.output(),
                        spec.displayPath(),
                        config.timeoutSeconds(),
                        outcome.exitCode(),
                        killed,
                        (System.nanoTime() - start) / 1_000_000);
                if (!spec.functionsWithContract().isEmpty() && !spec.functionsWithContract().contains(fn)) {
                    result = withIssue(result, new Issue(
                            Issue.Kind.NO_CONTRACT, fn, fn + ": has no ACSL contract in " + spec.displayPath()));
                }
                results.add(result);
                listener.functionFinished(result);
            }
            runs.add(new VerificationRun(spec, results, (System.nanoTime() - specStart) / 1_000_000, raw.toString()));
        }
        return runs;
    }

    private static FunctionResult withIssue(FunctionResult r, Issue issue) {
        List<Issue> issues = new ArrayList<>();
        issues.add(issue);
        issues.addAll(r.issues());
        return new FunctionResult(
                r.function(), r.generator(), r.totalGoals(), r.proved(), r.failures(), r.timeouts(),
                r.smokePassed(), r.smokeTotal(), r.processFailed(), issues, r.elapsedMs());
    }
}
