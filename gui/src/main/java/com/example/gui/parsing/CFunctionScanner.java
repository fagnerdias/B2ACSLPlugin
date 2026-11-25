package com.example.gui.parsing;

import com.example.CSourceFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the C functions named {@code <Machine>__<Operation>} that a project defines (Atelier B's
 * generated {@code lang/c/*.c}): definitions only, one per line as the code generator prints them;
 * prototypes ({@code ...);}) and indented calls are ignored.
 */
public final class CFunctionScanner {

    static final Pattern DEFINITION =
            Pattern.compile("^[A-Za-z_][\\w \\t*]*?\\b([A-Za-z_]\\w*?__\\w+)\\s*\\(([^;{}]*)\\)\\s*\\{?\\s*$");

    private CFunctionScanner() {}

    /** The functions defined in the {@code .c} files under {@code cDir}, in file order. */
    public static List<String> scan(Path cDir) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        for (Path c : CSourceFiles.list(cDir, ".c")) {
            out.addAll(scanText(Files.readString(c, StandardCharsets.ISO_8859_1)));
        }
        return List.copyOf(out);
    }

    public static List<String> scanText(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            Matcher m = DEFINITION.matcher(line);
            if (m.matches() && !line.startsWith("return") && !out.contains(m.group(1))) {
                out.add(m.group(1));
            }
        }
        return out;
    }
}
