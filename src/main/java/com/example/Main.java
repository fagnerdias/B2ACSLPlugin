package com.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import com.example.model.Machine;
import com.example.model.Variables;

public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Uso: java -jar <app>.jar <caminho-do-projeto-com-bxml>");
            System.exit(2);
        }

        Path projectPath = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(projectPath)) {
            System.err.println("Caminho inválido (não é diretório): " + projectPath);
            System.exit(2);
        }

        List<Path> bxmlFiles;
        try (var stream = Files.walk(projectPath)) {
            bxmlFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".bxml"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }

        if (bxmlFiles.isEmpty()) {
            System.out.println("Nenhum arquivo .bxml encontrado em: " + projectPath);
            return;
        }

        int ok = 0;
        int failed = 0;

        for (Path file : bxmlFiles) {
            Path relative = projectPath.relativize(file);
            try {
                Machine machine = Machine.fromBxmlPath(file);
                ok++;

                System.out.println("Arquivo: " + relative);
                System.out.println("Machine: machineType=" + machine.getMachineType()
                        + ", machineName=" + machine.getMachineName());

                if (machine.getVariables().isEmpty()) {
                    System.out.println("  (sem variáveis mapeadas)");
                } else {
                    for (Variables v : machine.getVariables()) {
                        System.out.println("  Variable: machineType=" + v.getMachineType()
                                + ", machineName=" + v.getMachineName()
                                + ", variableName=" + v.getVariableName()
                                + ", variableType=" + v.getVariableType()
                                + ", isAbstract=" + v.isAbstract()
                                + ", isConcrete=" + v.isConcrete());
                    }
                }
                System.out.println();
            } catch (Exception e) {
                failed++;
                System.err.println("Falha ao processar " + relative + ": " + e.getMessage());
            }
        }

        System.out.println("Processamento concluído: ok=" + ok + ", failed=" + failed + ", total=" + bxmlFiles.size());
    }
}
