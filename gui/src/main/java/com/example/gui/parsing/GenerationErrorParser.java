package com.example.gui.parsing;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort extraction of the function and the ATL rule a generation error refers to, from the
 * error message and the generator output. Returns {@code null} when the text does not name one.
 */
public final class GenerationErrorParser {

    private static final Pattern FUNCTION = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*?__[A-Za-z0-9_]+)\\b");
    private static final Pattern RULE =
            Pattern.compile(
                    "(?i)(?:\\brule\\s+['\"]?|\\bregra\\s+['\"]?|\\bat\\s+rule\\s+|\\bcalled rule\\s+|\\blazy rule\\s+)([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern ATL_STACK = Pattern.compile("(?i)\\bBXML2ACSL(?:\\.atl)?[#:.]\\s*([A-Za-z_][A-Za-z0-9_]*)");

    private GenerationErrorParser() {}

    public static String function(String message, String output) {
        String found = first(FUNCTION, message);
        return found != null ? found : first(FUNCTION, errorLines(output));
    }

    public static String rule(String message, String output) {
        for (String text : new String[] {message, errorLines(output)}) {
            String r = first(RULE, text);
            if (r != null && !r.equalsIgnoreCase("the") && !r.equalsIgnoreCase("a")) {
                return r;
            }
            r = first(ATL_STACK, text);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /** The lines that look like errors (ERRO, Exception, error, failed), joined. */
    static String errorLines(String output) {
        if (output == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : output.split("\\R")) {
            String l = line.toLowerCase(Locale.ROOT);
            if (l.startsWith("erro") || l.contains("exception") || l.contains("error") || l.contains("failed")
                    || l.contains("falhou") || line.startsWith("\tat ")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static String first(Pattern p, String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
