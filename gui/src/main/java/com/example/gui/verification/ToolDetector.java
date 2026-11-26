package com.example.gui.verification;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks on start-up that Frama-C and the provers are installed. Provers come from {@code why3
 * config list-provers} (read-only; {@code why3 config detect} would rewrite {@code ~/.why3.conf}),
 * with a fallback to the executables on {@code PATH} when Why3 itself is missing.
 */
public final class ToolDetector {

    /** The provers offered in step 3, in this order, by their {@code -wp-prover} name. */
    public static final List<String> PROVERS = List.of("CVC5", "Alt-Ergo", "Z3");

    private static final Map<String, String> EXECUTABLES = Map.of("Alt-Ergo", "alt-ergo", "CVC5", "cvc5", "Z3", "z3");
    private static final Pattern WHY3_PROVER = Pattern.compile("^(Alt-Ergo|CVC5|Z3) (\\S+)(?: \\((.+)\\))?\\s*$");

    /**
     * @param framaCVersion {@code null} when Frama-C was not found
     * @param provers installed provers → version ("" when the version is unknown)
     */
    public record ToolStatus(String framaCVersion, Map<String, String> provers) {

        public boolean framaCFound() {
            return framaCVersion != null;
        }

        public boolean installed(String prover) {
            return provers.containsKey(prover);
        }
    }

    private ToolDetector() {}

    public static ToolStatus detect() {
        String frama = firstLine(run(List.of("frama-c", "-version")));
        Map<String, String> provers;
        String why3 = run(List.of("why3", "config", "list-provers"));
        if (why3 != null) {
            provers = parseWhy3Provers(why3);
        } else {
            provers = new LinkedHashMap<>();
            for (String p : PROVERS) {
                if (onPath(EXECUTABLES.get(p))) {
                    provers.put(p, "");
                }
            }
        }
        return new ToolStatus(frama == null || frama.isBlank() ? null : frama.trim(), provers);
    }

    /** "CVC5 1.1.2" → {CVC5=1.1.2}; variants such as "(counterexamples)" are ignored. */
    static Map<String, String> parseWhy3Provers(String output) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            Matcher m = WHY3_PROVER.matcher(line.trim());
            if (m.matches()) {
                out.putIfAbsent(m.group(1), m.group(2));
            }
        }
        return out;
    }

    private static String run(List<String> command) {
        try {
            ProcessSupervisor.Outcome o = ProcessSupervisor.run(command, null, 30, null, null);
            return o.exitCode() == 0 ? o.output() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstLine(String text) {
        if (text == null) {
            return null;
        }
        return text.lines().filter(l -> !l.isBlank()).findFirst().orElse(null);
    }

    private static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (Files.isExecutable(Path.of(dir, exe))) {
                return true;
            }
        }
        return false;
    }
}
