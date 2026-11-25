package com.example.gui.comparison;

import com.example.gui.model.FunctionComparison;
import com.example.gui.model.FunctionResult;
import com.example.gui.model.Generator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** "Export Comparison (.csv)": one row per function and generator. */
public final class CsvExporter {

    public static final String HEADER = "function,generator,total goals,proved,failures,timeouts,smoke tests,result";

    private CsvExporter() {}

    public static String toCsv(List<FunctionComparison> rows) {
        StringBuilder sb = new StringBuilder(HEADER).append("\r\n");
        for (FunctionComparison row : rows) {
            String result = row.same() ? "Same result" : "Different";
            for (Generator g : Generator.values()) {
                FunctionResult r = row.result(g);
                sb.append(cell(row.function())).append(',').append(cell(g.label())).append(',');
                if (r == null) {
                    sb.append(",,,,");
                } else {
                    sb.append(r.totalGoals()).append(',').append(r.proved()).append(',').append(r.failures())
                            .append(',').append(r.timeouts()).append(',')
                            .append(r.hasSmokeTests() ? cell(r.smokePassed() + "/" + r.smokeTotal()) : "");
                }
                sb.append(',').append(cell(result)).append("\r\n");
            }
        }
        return sb.toString();
    }

    public static void write(List<FunctionComparison> rows, Path file) throws IOException {
        Files.writeString(file, toCsv(rows), StandardCharsets.UTF_8);
    }

    /** RFC 4180 quoting when needed. */
    static String cell(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
