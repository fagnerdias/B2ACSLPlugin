package com.example.gui.generation;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Command line of a headless child JVM that runs a class of this application. */
final class ChildJvm {

    private ChildJvm() {}

    static List<String> command(Class<?> mainClass, List<String> args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-Djava.awt.headless=true");
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-Dstdout.encoding=UTF-8");
        cmd.add("-Dstderr.encoding=UTF-8");
        System.getProperties().stringPropertyNames().stream()
                .filter(k -> k.startsWith("b2acsl.") && !k.startsWith("b2acsl.gui."))
                .sorted()
                .forEach(k -> cmd.add("-D" + k + "=" + System.getProperty(k)));
        cmd.add("-cp");
        cmd.add(classPath());
        cmd.add(mainClass.getName());
        cmd.addAll(args);
        return cmd;
    }

    /** The running class path with absolute entries (the child may start in another folder). */
    private static String classPath() {
        List<String> out = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                out.add(Path.of(entry).toAbsolutePath().normalize().toString());
            }
        }
        return String.join(File.pathSeparator, out);
    }
}
