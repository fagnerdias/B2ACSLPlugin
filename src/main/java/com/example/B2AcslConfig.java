package com.example;

import java.io.InputStream;
import java.util.Properties;

/**
 * Configuração {@code b2acsl.*} tipada, lida de uma vez a partir das propriedades de sistema (com
 * fallback, para {@code b2acsl.mock}, ao ficheiro de classpath {@code /META-INF/b2acsl.properties}
 * — o mesmo recurso lido ad hoc antes desta extração), em vez de chamadas dispersas a {@code
 * System.getProperty(...)} em {@link B2ACSLPipeline} e {@link com.example.ui.WpOptionsDialog}.
 *
 * <p>Extract-class puro: os mesmos defaults e a mesma precedência (propriedade de sistema
 * sobrepõe-se ao default) de antes — nenhum comportamento observável muda.
 *
 * <p>Nota de âmbito: {@code b2acsl.targetAcslDir} (lido por {@link AcslLibIncludes}) e as
 * restantes chaves {@code b2acsl.acslLib*} continuam a ser lidas diretamente por {@code
 * AcslLibIncludes} (não migradas para aqui) — {@code targetAcslDir}, em particular, é escrito
 * dinamicamente a meio da execução por {@link B2ACSLPipeline#run} (uma por chamada de {@code
 * run()}, dependente do caminho {@code bdp} recebido), pelo que um valor capturado uma única vez
 * por esta config deixaria de refletir esse {@code System.setProperty} — ver {@code
 * AcslLibIncludes#propertyOrEmpty}.
 */
public record B2AcslConfig(
        boolean mock,
        String wpProjectNameProperty,
        String wpProver,
        int wpTimeoutSeconds,
        String wpOutputFlag,
        boolean wpLoopSimplification,
        boolean wpSmokeTests,
        boolean wpVerifyPerOperation,
        boolean wpCounterExamples,
        boolean wpSplitGoals) {

    private static final String DEFAULT_WP_PROVER = "CVC5";
    private static final int DEFAULT_WP_TIMEOUT_SECONDS = 10;
    private static final String DEFAULT_WP_OUTPUT = "status";

    /**
     * Lê todas as propriedades {@code b2acsl.*} correntes (propriedade de sistema sobrepõe-se ao
     * default, exatamente como cada chamada individual fazia antes desta extração).
     */
    public static B2AcslConfig fromSystemProperties() {
        return new B2AcslConfig(
                readMockFlag(),
                System.getProperty("b2acsl.wp.project"),
                System.getProperty("b2acsl.wp.prover", DEFAULT_WP_PROVER),
                Integer.getInteger("b2acsl.wp.timeout", DEFAULT_WP_TIMEOUT_SECONDS),
                System.getProperty("b2acsl.wp.output", DEFAULT_WP_OUTPUT),
                Boolean.parseBoolean(System.getProperty("b2acsl.wp.loopSimplification", "false")),
                Boolean.parseBoolean(System.getProperty("b2acsl.wp.smokeTests", "true")),
                Boolean.parseBoolean(System.getProperty("b2acsl.wp.verifyPerOperation", "false")),
                Boolean.parseBoolean(System.getProperty("b2acsl.wp.counterExamples", "true")),
                Boolean.parseBoolean(System.getProperty("b2acsl.wp.splitGoals", "true")));
    }

    /**
     * {@code b2acsl.mock}: propriedade de sistema primeiro; senão o recurso de classpath {@code
     * /META-INF/b2acsl.properties}; senão {@code true} — mesma ordem de precedência de {@code
     * B2ACSLPipeline#isMockEnabled} antes desta extração.
     */
    private static boolean readMockFlag() {
        String sys = System.getProperty("b2acsl.mock");
        if (sys != null && !sys.isBlank()) {
            return Boolean.parseBoolean(sys);
        }
        try (InputStream in = B2AcslConfig.class.getResourceAsStream("/META-INF/b2acsl.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("b2acsl.mock");
                if (v != null && !v.isBlank()) {
                    return Boolean.parseBoolean(v.trim());
                }
            }
        } catch (Exception ignored) {
            // sem recurso/entrada válida: cai no default abaixo, como antes.
        }
        return true;
    }
}
