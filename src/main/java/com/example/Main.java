package com.example;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ponto de entrada do plugin B2ACSL para Atelier B.
 *
 * <p>Uso: B2ACSLExec &lt;caminho-bdp&gt;
 *
 * <p>Passos executados:
 * <ol>
 *   <li>Ler arquivos .bxml na pasta bdp passada como parâmetro</li>
 *   <li>Gerar arquivos temporários .acsl com especificação de cada máquina (abstração, refinamento, etc.)</li>
 *   <li>Obter arquivos .c em lang/c/ (mesmo path, trocando bdp por lang)</li>
 *   <li>Executar Frama-C: acsl-importer e WP</li>
 *   <li>Retornar valor para Atelier B</li>
 * </ol>
 *
 * <p>Modo mock (b2acsl.mock=true): simula Frama-C quando não há .c ou frama-c não está instalado.
 */
public class Main {
    public static void main(String[] args) throws Exception {
        Path bdpPath;
        if (args.length >= 1) {
            bdpPath = Path.of(args[0]).toAbsolutePath().normalize();
        } else {
            // Dados mockados para execução de exemplo
            Path mockBdp = Path.of(System.getProperty("user.dir"), "src", "main", "resources");
            if (!Files.isDirectory(mockBdp)) {
                System.err.println("Uso: B2ACSLExec <caminho-bdp>");
                System.err.println("  O caminho deve apontar para a pasta que contém os arquivos .bxml");
                System.exit(2);
            }
            bdpPath = mockBdp;
            System.out.println("[B2ACSL] Modo exemplo: usando " + bdpPath);
        }

        int exitCode = B2ACSLPipeline.run(bdpPath);
        System.exit(exitCode);
    }
}
