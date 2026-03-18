package com.example;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.example.model.Machine;
import com.example.model.Variables;

/**
 * Gera arquivos ACSL temporários a partir das máquinas B abstratas.
 */
public final class AcslGenerator {

    private AcslGenerator() {}

    /**
     * Gera um arquivo .acsl para a máquina e retorna o caminho do arquivo criado.
     */
    public static Path generateAcsl(Machine machine, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        String baseName = machine.getMachineName();
        Path acslFile = outputDir.resolve(baseName + ".acsl");

        StringBuilder sb = new StringBuilder();
        sb.append("/* ACSL gerado a partir de ").append(baseName).append(" (type=")
                .append(machine.getMachineType()).append(") */\n\n");

        for (Variables v : machine.getVariables()) {
            String cType = mapBTypeToC(v.getVariableType());
            sb.append("// ").append(v.getVariableName()).append(" : ").append(v.getVariableType())
                    .append(" (abstract=").append(v.isAbstract()).append(")\n");
            sb.append("// @ logic ").append(cType).append(" ").append(v.getVariableName()).append(";\n\n");
        }

        sb.append("/* Invariantes (especificação simplificada) */\n");
        for (Variables v : machine.getVariables()) {
            if ("NAT".equalsIgnoreCase(v.getVariableType()) || "POW(INTEGER)".equals(v.getVariableType())) {
                sb.append("// @ invariant \\valid(").append(v.getVariableName()).append(");\n");
            }
        }

        Files.writeString(acslFile, sb.toString());
        return acslFile;
    }

    private static String mapBTypeToC(String bType) {
        if (bType == null) return "int";
        return switch (bType.toUpperCase()) {
            case "NAT", "INTEGER" -> "integer";
            case "BOOL" -> "boolean";
            default -> bType.contains("POW") ? "set" : "int";
        };
    }
}
