package com.example.bxml;

/**
 * Marcador único de construção B sem tradução ACSL. A tradução continua (o texto é usado também
 * para nomes, impressões digitais e varreduras), mas a unidade que o contém — conjunção de
 * invariante, propriedade, conjunção da PRE, corpo da operação, invariante de laço, conjunção da
 * colagem — é omitida na montagem, com um AVISO ({@link TranslationWarnings}), como no
 * B2ACSLRules. Antes, marcadores como {@code /* TODO: Binary_Exp op='-->' *&#47;} iam para o ACSL
 * e quebravam o {@code -acsl-import} do projeto inteiro.
 *
 * <p>{@link #noRegistry} é outro marcador: o contexto de tradução não tem registo de funções (só
 * acontece nas traduções auxiliares de nomes/varreduras); não conta como "não traduzível", para a
 * numeração {@code M_invariant_k} ser a mesma em todos os contextos.
 */
public final class Unsupported {

    public static final String TAG = "B2ACSL-UNSUPPORTED";
    public static final String NO_REGISTRY_TAG = "B2ACSL-NOREG";

    private Unsupported() {}

    /** {@code /* B2ACSL-UNSUPPORTED: motivo *&#47;} */
    public static String marker(String reason) {
        return "/* " + TAG + ": " + clean(reason) + " */";
    }

    /** {@code /* B2ACSL-NOREG: motivo *&#47;} */
    public static String noRegistry(String reason) {
        return "/* " + NO_REGISTRY_TAG + ": " + clean(reason) + " */";
    }

    /** O texto traduzido tem uma construção sem tradução? */
    public static boolean in(String text) {
        return text != null && text.contains(TAG);
    }

    private static String clean(String reason) {
        return reason == null ? "" : reason.replace("*/", "* /").replace("/*", "/ *").trim();
    }
}
