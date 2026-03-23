package com.example;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Detecta símbolos da {@code ACSL_Lib} no texto ACSL gerado e produz linhas {@code include "..."}
 * para os ficheiros correspondentes em {@code src/main/resources/ACSL_Lib}.
 *
 * <p>Propriedades opcionais (JVM / {@code META-INF/b2acsl.properties}):
 * <ul>
 *   <li>{@code b2acsl.acslLibIncludeBase} — caminho absoluto ou relativo até à pasta que contém
 *       {@code set_functions/}, {@code relation_functions/}, etc. (tipicamente .../ACSL_Lib).
 *       Se vazio, o caminho no {@code include} é só o segmento relativo (+ meio opcional).</li>
 *   <li>{@code b2acsl.acslLibIncludeMiddle} — segmento opcional entre a base e o caminho da lib,
 *       ex.: {@code import} para obter {@code .../import/set_functions/belongs.acsl}.</li>
 * </ul>
 */
public final class AcslLibIncludes {

    private AcslLibIncludes() {}

    /**
     * Símbolo ACSL (nome antes de '(') → caminho relativo dentro de ACSL_Lib (usa '/').
     */
    private static final Map<String, String> SYMBOL_TO_FILE = Map.ofEntries(
            Map.entry("belongs", "set_functions/belongs.acsl"),
            Map.entry("not_belongs", "set_functions/belongs.acsl"),
            Map.entry("inclusion", "set_functions/inclusion.acsl"),
            Map.entry("set_union", "set_functions/union.acsl"),
            Map.entry("singleton", "set_functions/singleton.acsl"),
            Map.entry("empty", "set_functions/empty.acsl"),
            Map.entry("card", "set_functions/card.acsl"),
            Map.entry("disjoint", "set_functions/disjoint.acsl"),
            Map.entry("intersection", "set_functions/intersection.acsl"),
            Map.entry("difference", "set_functions/difference.acsl"),
            Map.entry("dom", "relation_functions/domain.acsl"),
            Map.entry("ran", "relation_functions/range.acsl"),
            Map.entry("relation_inverse", "relation_functions/inverse.acsl"),
            Map.entry("domain_restriction", "relation_functions/domain_restriction.acsl"),
            Map.entry("range_restriction", "relation_functions/range_restriction.acsl"),
            Map.entry("iseq", "sequence_functions/iseq.acsl"),
            Map.entry("iSeq", "sequence_functions/iseq.acsl"));

    /**
     * Ordem estável para {@code include}: dependências simples (ex. {@code belongs} antes de
     * {@code inclusion}) e agrupamento por pasta.
     */
    private static final List<String> FILE_ORDER = List.of(
            "set_functions/belongs.acsl",
            "set_functions/inclusion.acsl",
            "set_functions/empty.acsl",
            "set_functions/singleton.acsl",
            "set_functions/card.acsl",
            "set_functions/union.acsl",
            "set_functions/disjoint.acsl",
            "set_functions/intersection.acsl",
            "set_functions/difference.acsl",
            "relation_functions/domain.acsl",
            "relation_functions/range.acsl",
            "relation_functions/inverse.acsl",
            "relation_functions/domain_restriction.acsl",
            "relation_functions/range_restriction.acsl",
            "sequence_functions/iseq.acsl");

    /**
     * @return bloco de linhas {@code include "...";} terminado em {@code \n}, ou vazio se nada
     *         corresponder
     */
    public static String formatIncludeBlock(String acslText) {
        List<String> lines = collectIncludeLines(acslText);
        if (lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }

    static List<String> collectIncludeLines(String acslText) {
        LinkedHashSet<String> files = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : SYMBOL_TO_FILE.entrySet()) {
            if (containsSymbolCall(acslText, e.getKey())) {
                files.add(e.getValue());
            }
        }
        if (files.isEmpty()) return List.of();

        List<String> ordered = new ArrayList<>();
        for (String path : FILE_ORDER) {
            if (files.contains(path)) ordered.add(path);
        }
        for (String f : files) {
            if (!ordered.contains(f)) ordered.add(f);
        }

        String base = propertyOrEmpty("b2acsl.acslLibIncludeBase");
        String middle = propertyOrEmpty("b2acsl.acslLibIncludeMiddle");

        List<String> lines = new ArrayList<>();
        for (String rel : ordered) {
            lines.add(buildIncludeLine(base, middle, rel));
        }
        return lines;
    }

    private static Properties cachedProps;

    private static String propertyOrEmpty(String key) {
        String sys = System.getProperty(key);
        if (sys != null && !sys.isBlank()) return sys.trim();
        try {
            if (cachedProps == null) {
                cachedProps = new Properties();
                try (InputStream in = AcslLibIncludes.class.getResourceAsStream("/META-INF/b2acsl.properties")) {
                    if (in != null) cachedProps.load(in);
                }
            }
            String v = cachedProps.getProperty(key);
            if (v != null && !v.isBlank()) return v.trim();
        } catch (IOException ignored) {
        }
        return "";
    }

    static String buildIncludeLine(String base, String middle, String relativePath) {
        String rel = relativePath.replace('\\', '/');
        StringBuilder path = new StringBuilder();
        if (!base.isEmpty()) {
            String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
            path.append(b);
        }
        if (!middle.isEmpty()) {
            if (path.length() > 0) path.append('/');
            path.append(middle);
        }
        if (!rel.isEmpty()) {
            if (path.length() > 0) path.append('/');
            path.append(rel);
        }
        return "include \"" + path + "\";";
    }

    private static boolean containsSymbolCall(String text, String symbol) {
        // Evita coincidências no meio de identificadores; exige '(' após o símbolo.
        Pattern p = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(symbol) + "\\s*\\(");
        return p.matcher(text).find();
    }
}
