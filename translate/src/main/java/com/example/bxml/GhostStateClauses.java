package com.example.bxml;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cláusulas do corpo de uma operação abstrata que a função ghost pode garantir, e onde chamá-la.
 *
 * <p>A função ghost só atribui as variáveis abstratas com gêmeo ghost ({@code assigns ghost_v}).
 * Uma cláusula {@code ensures} dela que fala só do estado real (variáveis concretas da abstração,
 * ou abstratas colapsadas num array da implementação) não é garantida por ninguém: no WP ela vira
 * uma suposição sobre a memória real no ponto da chamada. Se o C a satisfaz, é redundante (o
 * contrato real já tem a mesma cláusula); se não satisfaz, a chamada fica sem estado sucessor
 * possível e tudo o que vem depois é provado no vazio. Ex.: elevsub {@code cr_step} (o C já fez
 * {@code cr_floor++} quando o ghost, chamado no {@code return}, "garantia"
 * {@code cr_floor == \old(cr_floor) + 1}): smoke test {@code Doomed}.
 *
 * <p>Pelo mesmo motivo, uma cláusula sobre variável ghost que lê {@code \old(c)} de uma variável
 * real {@code c} que a própria operação altera precisa do valor de ENTRADA de {@code c}: no
 * {@code return} o C já a alterou. Nesse caso a chamada ghost vai para o início da função
 * ({@code at 1:} no {@code .acsl}), antes de o C mexer em {@code c}.
 */
final class GhostStateClauses {

    private GhostStateClauses() {}

    /**
     * Tira de {@code ensures} as cláusulas que só falam do estado real: citam (fora de
     * {@code \old}) alguma variável de {@code realStateVariables} e nenhuma de
     * {@code ghostAssignedVariables}. As demais (inclusive as que só falam de saídas ou
     * parâmetros) ficam como estão.
     */
    static List<String> withoutRealStateOnlyClauses(
            List<String> ensures,
            Collection<String> ghostAssignedVariables,
            Collection<String> realStateVariables) {
        if (ensures == null || ensures.isEmpty() || realStateVariables == null || realStateVariables.isEmpty()) {
            return ensures == null ? List.of() : ensures;
        }
        List<String> kept = new ArrayList<>();
        for (String e : ensures) {
            String postState = withoutOldTerms(e);
            boolean realOnly =
                    mentionsAny(postState, realStateVariables)
                            && !mentionsAny(postState, ghostAssignedVariables);
            if (!realOnly) {
                kept.add(e);
            }
        }
        return kept;
    }

    /**
     * {@code true} quando a chamada ghost deve ir para o início da função: alguma cláusula
     * mantida lê {@code \old(c)} com {@code c} em {@code realStateAssignedByOperation}, nenhuma
     * cita {@code c} no estado final (no início ele ainda vale o de entrada) e a operação não tem
     * saída (o C ainda não a escreveu no início).
     */
    static boolean callAtEntry(
            List<String> keptEnsures, Set<String> realStateAssignedByOperation, boolean hasOutputs) {
        if (hasOutputs || keptEnsures == null || realStateAssignedByOperation == null
                || realStateAssignedByOperation.isEmpty()) {
            return false;
        }
        boolean readsOldValue = false;
        for (String e : keptEnsures) {
            if (mentionsAny(withoutOldTerms(e), realStateAssignedByOperation)) {
                return false;
            }
            if (mentionsAny(oldTerms(e), realStateAssignedByOperation)) {
                readsOldValue = true;
            }
        }
        return readsOldValue;
    }

    static boolean mentionsAny(String text, Collection<String> names) {
        if (text == null || text.isEmpty() || names == null) {
            return false;
        }
        for (String n : names) {
            if (n != null && !n.isEmpty() && identifier(n).matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static Pattern identifier(String name) {
        return Pattern.compile("(?<![A-Za-z0-9_$\\\\])" + Pattern.quote(name) + "(?![A-Za-z0-9_])");
    }

    /** O texto sem os termos {@code \old(...)} (parênteses balanceados). */
    static String withoutOldTerms(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int at = s.indexOf("\\old(", i);
            if (at < 0) {
                out.append(s, i, s.length());
                break;
            }
            out.append(s, i, at);
            int end = closingParen(s, at + "\\old".length());
            i = end < 0 ? s.length() : end + 1;
        }
        return out.toString();
    }

    /** Só os termos {@code \old(...)} do texto, concatenados. */
    static String oldTerms(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (true) {
            int at = s.indexOf("\\old(", i);
            if (at < 0) {
                break;
            }
            int end = closingParen(s, at + "\\old".length());
            if (end < 0) {
                out.append(s, at, s.length());
                break;
            }
            out.append(s, at, end + 1).append(' ');
            i = end + 1;
        }
        return out.toString();
    }

    private static int closingParen(String s, int openIdx) {
        int depth = 0;
        for (int k = openIdx; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return k;
                }
            }
        }
        return -1;
    }
}
