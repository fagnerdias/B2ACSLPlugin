package com.example.bxml;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Registo de funções lógicas geradas para os quantificadores generalizados de B — {@code SIGMA},
 * {@code PI}, {@code MIN}, {@code MAX} — extraídos durante a tradução BXML→ACSL. Cada ocorrência de
 * {@code Quantified_Exp type='iSIGMA'/'iPI'/'iMIN'/'iMAX'} vira uma função lógica nomeada ({@code
 * sigma_func01}, {@code pi_func01}, {@code min_func01}, {@code max_func01}, contador PRÓPRIO por
 * prefixo, na ordem de registo), com os lemas-ponte obrigatórios sem os quais nenhuma prova de loop
 * que percorre o domínio fecha (o WP não faz indução em {@code hi - lo} sozinho).
 *
 * <p>Duas formas, conforme o domínio {@code D} de {@code OP(z).(z : D | E)}:
 * <ul>
 *   <li><b>interval</b> — {@code D} é {@code lo..hi} (literal ou reindexado), com filtro {@code
 *       Q(z)} opcional no corpo. {@code SIGMA}/{@code PI} usam função lógica RECURSIVA total
 *       (neutro bem definido — {@code 0}/{@code 1} — para o domínio vazio). {@code MIN}/{@code MAX}
 *       não têm neutro representável em {@code integer} ({@code +inf}/{@code -inf} da especificação
 *       matemática não existem em ACSL) — B já exige domínio não-vazio para eles por obrigação de
 *       prova própria, por isso usam-se declaração+axiomas guardados (parciais: indefinidos para
 *       {@code lo > hi}, tal como em B).</li>
 *   <li><b>set</b> — {@code D} é um conjunto qualquer (ex. {@code dom(f)}, {@code f(x)}):
 *       axiomatização estrutural sobre {@code empty}/{@code set_union(singleton(x), S)} (ACSL_Lib
 *       não tem {@code add_set} — usa-se a forma real da lib), guardada por {@code is_finite}.</li>
 * </ul>
 */
public final class SigmaFunctionRegistry {

    /** Operador do quantificador generalizado (monoide subjacente). */
    public enum Op {
        SIGMA("sigma_func", "0", true),
        PI("pi_func", "1", true),
        MIN("min_func", null, false),
        MAX("max_func", null, false);

        final String namePrefix;
        final String neutral;
        final boolean hasNeutral;

        Op(String namePrefix, String neutral, boolean hasNeutral) {
            this.namePrefix = namePrefix;
            this.neutral = neutral;
            this.hasNeutral = hasNeutral;
        }

        String combine(String a, String b) {
            return switch (this) {
                case SIGMA -> "(" + a + ") + (" + b + ")";
                case PI -> "(" + a + ") * (" + b + ")";
                case MIN -> "((" + a + ") <= (" + b + ") ? (" + a + ") : (" + b + "))";
                case MAX -> "((" + a + ") >= (" + b + ") ? (" + a + ") : (" + b + "))";
            };
        }
    }

    private enum Kind { INTERVAL, SET }

    /**
     * @param bodyForLo INTERVAL: {@code E} com a variável ligada substituída por {@code lo}.
     * @param bodyForHi INTERVAL: {@code E} com a variável ligada substituída por {@code hi} (para o
     *     lema {@code _peel_last} — a definição descasca pela frente, mas o invariante de loop fala
     *     do prefixo já percorrido, que descasca pelo fim).
     * @param filterForLo INTERVAL §2b: {@code Q} (filtro) com a variável ligada substituída por
     *     {@code lo}; {@code null} ⇒ sem filtro (§2a).
     * @param filterForHi INTERVAL §2b: idem, substituída por {@code hi}.
     * @param congrVarName INTERVAL: nome do único parâmetro livre função-tipada quando {@code E}
     *     tem exatamente a forma {@code function_apply(congrVarName, ...)} — habilita o lema
     *     {@code _congr}; {@code null} em qualquer outra forma de corpo (uma congruência segura
     *     exigiria isolar semanticamente qual parâmetro livre varia, o que não é feito em geral).
     * @param boundVarType SET: tipo ACSL do elemento do domínio (ex. {@code island}).
     * @param bodyForX SET: {@code E} com a variável ligada substituída por {@code x}.
     * @param domainExprText SET: texto ACSL do domínio {@code S} (para o comentário TODO de
     *     finitude).
     */
    private record Entry(
            String name,
            Op op,
            Kind kind,
            List<String> freeVarNames,
            List<String> freeVarTypes,
            String sourceComment,
            String bodyForLo,
            String bodyForHi,
            String filterForLo,
            String filterForHi,
            String congrVarName,
            String congrVarType,
            String boundVarType,
            String bodyForX,
            String domainExprText) {}

    private static final Pattern FUNCTION_APPLY_HEAD =
            Pattern.compile("^function_apply\\(([A-Za-z_][A-Za-z0-9_]*),\\s*.*\\)$");

    private int counter = 0;
    private final List<Entry> entries = new ArrayList<>();

    /** §2/§2b/§4: domínio-intervalo (recursão para SIGMA/PI; declaração+axiomas para MIN/MAX). */
    public String registerInterval(
            Op op, List<String> freeVarNames, List<String> freeVarTypes, String sourceComment,
            String bodyForLo, String bodyForHi, String filterForLo, String filterForHi,
            String congrVarName, String congrVarType) {
        counter++;
        String name = String.format("%s%02d", op.namePrefix, counter);
        entries.add(new Entry(
                name, op, Kind.INTERVAL,
                List.copyOf(freeVarNames),
                freeVarTypes == null ? List.of() : List.copyOf(freeVarTypes),
                sourceComment, bodyForLo, bodyForHi, filterForLo, filterForHi,
                congrVarName, congrVarType, null, null, null));
        return name;
    }

    /** Deteta a forma {@code function_apply(freeVar, ...)} em {@code bodyForLo} (não substituído). */
    public static String[] detectCongrVar(String rawBodyBeforeSubstitution, List<String> freeVarNames) {
        if (rawBodyBeforeSubstitution == null) return null;
        var m = FUNCTION_APPLY_HEAD.matcher(rawBodyBeforeSubstitution.trim());
        if (m.matches() && freeVarNames.contains(m.group(1))) {
            return new String[] {m.group(1)};
        }
        return null;
    }

    /** §3: domínio-conjunto qualquer (axiomatização estrutural {@code empty}/{@code add}). */
    public String registerSet(
            Op op, List<String> freeVarNames, List<String> freeVarTypes, String sourceComment,
            String boundVarType, String bodyForX, String domainExprText) {
        counter++;
        String name = String.format("%s%02d", op.namePrefix, counter);
        entries.add(new Entry(
                name, op, Kind.SET,
                List.copyOf(freeVarNames),
                freeVarTypes == null ? List.of() : List.copyOf(freeVarTypes),
                sourceComment, null, null, null, null, null, null,
                boundVarType, bodyForX, domainExprText));
        return name;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public String formatAxiomaticBlock() {
        return formatAxiomaticBlockFrom(0);
    }

    /** Como {@link LambdaFunctionRegistry#formatAxiomaticBlockFrom}: só as entradas a partir de {@code fromIndex}. */
    public String formatAxiomaticBlockFrom(int fromIndex) {
        List<Entry> slice = entries.subList(fromIndex, entries.size());
        if (slice.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("axiomatic generalized_quantifier_functions {\n");
        for (Entry e : slice) {
            if (e.kind() == Kind.SET) {
                appendSetEntry(sb, e);
            } else {
                appendIntervalEntry(sb, e);
            }
        }
        sb.append("}\n");
        return sb.toString();
    }

    // ───────────────────────────── INTERVAL ─────────────────────────────

    private void appendIntervalEntry(StringBuilder sb, Entry e) {
        Op op = e.op();
        String name = e.name();
        List<String> typedFree = typedParams(e.freeVarNames(), e.freeVarTypes());
        String fp = typedFree.isEmpty() ? "" : String.join(", ", typedFree) + ", ";
        String fa = e.freeVarNames().isEmpty() ? "" : String.join(", ", e.freeVarNames()) + ", ";

        sb.append("  /* ").append(e.sourceComment()).append(" */\n");

        if (op.hasNeutral) {
            String contributionLo = e.filterForLo() != null
                    ? "(" + e.filterForLo() + " ? " + e.bodyForLo() + " : " + op.neutral + ")"
                    : e.bodyForLo();
            sb.append("  logic integer ").append(name).append("(").append(fp)
                    .append("integer lo, integer hi) =\n")
                    .append("      lo > hi ? ").append(op.neutral).append("\n")
                    .append("              : ")
                    .append(op.combine(contributionLo, name + "(" + fa + "lo + 1, hi)"))
                    .append(";\n")
                    .append("  // decreases hi - lo;\n\n");
        } else {
            // MIN/MAX: sem neutro representável em integer; domínio não-vazio garantido por
            // obrigação de prova B — declaração sem corpo + axiomas guardados (parcial, tal como %).
            sb.append("  logic integer ").append(name).append("(").append(fp)
                    .append("integer lo, integer hi);\n\n");
            sb.append("  axiom ").append(name).append("_base:\n")
                    .append("    \\forall ").append(fp).append("integer lo;\n");
            if (e.filterForLo() != null) {
                sb.append("      (").append(e.filterForLo()).append(") ==> ")
                        .append(name).append("(").append(fa).append("lo, lo) == ")
                        .append(e.bodyForLo()).append(";\n\n");
            } else {
                sb.append("      ").append(name).append("(").append(fa).append("lo, lo) == ")
                        .append(e.bodyForLo()).append(";\n\n");
            }
            sb.append("  axiom ").append(name).append("_rec:\n")
                    .append("    \\forall ").append(fp).append("integer lo, hi;\n")
                    .append("      lo < hi ==>\n")
                    .append("        ").append(name).append("(").append(fa).append("lo, hi) == ");
            String recStep = op.combine(e.bodyForLo(), name + "(" + fa + "lo + 1, hi)");
            if (e.filterForLo() != null) {
                String skipStep = name + "(" + fa + "lo + 1, hi)";
                recStep = "(" + e.filterForLo() + " ? " + recStep + " : " + skipStep + ")";
            }
            sb.append(recStep).append(";\n\n");
        }

        appendBridgeLemmas(sb, e, fp, fa, typedFree);
        appendBoundLemmas(sb, e, fp, fa);
    }

    /** §5: lemas-ponte obrigatórios ({@code _empty}, {@code _peel_last}, {@code _split}, {@code _congr}). */
    private void appendBridgeLemmas(
            StringBuilder sb, Entry e, String fp, String fa, List<String> typedFree) {
        Op op = e.op();
        String name = e.name();

        if (op.hasNeutral) {
            sb.append("  lemma ").append(name).append("_empty:\n")
                    .append("    \\forall ").append(fp).append("integer lo, hi;\n")
                    .append("      lo > hi ==> ").append(name).append("(").append(fa)
                    .append("lo, hi) == ").append(op.neutral).append(";\n\n");
        } else {
            sb.append("  /* ").append(name).append(" não tem caso vazio: MIN/MAX exige domínio ")
                    .append("não-vazio em B (obrigação de prova na origem do SIGMA/PI/MIN/MAX) */\n\n");
        }

        String bodyHiContribution;
        if (e.filterForHi() != null && op.hasNeutral) {
            bodyHiContribution = "(" + e.filterForHi() + " ? " + e.bodyForHi() + " : " + op.neutral + ")";
        } else {
            bodyHiContribution = e.bodyForHi();
        }
        sb.append("  lemma ").append(name).append("_peel_last:\n")
                .append("    \\forall ").append(fp).append("integer lo, hi;\n")
                .append("      lo <= hi ==> ").append(name).append("(").append(fa).append("lo, hi) == ")
                .append(op.combine(name + "(" + fa + "lo, hi - 1)", bodyHiContribution))
                .append(";\n  /* prova: indução em hi-lo — Coq/Why3 */\n\n");

        String splitPremise = op.hasNeutral ? "lo <= m && m <= hi + 1" : "lo < m && m <= hi";
        sb.append("  lemma ").append(name).append("_split:\n")
                .append("    \\forall ").append(fp).append("integer lo, m, hi;\n")
                .append("      ").append(splitPremise).append(" ==> ").append(name).append("(")
                .append(fa).append("lo, hi) == ")
                .append(op.combine(name + "(" + fa + "lo, m - 1)", name + "(" + fa + "m, hi)"))
                .append(";\n  /* prova: indução em hi-lo — Coq/Why3 */\n\n");

        if (e.congrVarName() != null) {
            appendCongrLemma(sb, e, name);
        }
    }

    private void appendCongrLemma(StringBuilder sb, Entry e, String name) {
        String cv = e.congrVarName();
        String cvType = e.congrVarType() == null || e.congrVarType().isBlank() ? "integer" : e.congrVarType();
        List<String> otherNames = new ArrayList<>();
        List<String> otherTypes = new ArrayList<>();
        for (int i = 0; i < e.freeVarNames().size(); i++) {
            if (!e.freeVarNames().get(i).equals(cv)) {
                otherNames.add(e.freeVarNames().get(i));
                otherTypes.add(freeVarType(e.freeVarTypes(), i));
            }
        }
        List<String> otherTyped = typedParams(otherNames, otherTypes);
        String otherParamsDecl = otherTyped.isEmpty() ? "" : String.join(", ", otherTyped) + ", ";
        String bodyForK = replaceWord(e.bodyForLo(), "lo", "k");
        String bodyForK1 = replaceWord(bodyForK, cv, cv + "1");
        String bodyForK2 = replaceWord(bodyForK, cv, cv + "2");
        String args1 = argsWithRenamed(e.freeVarNames(), cv, cv + "1");
        String args2 = argsWithRenamed(e.freeVarNames(), cv, cv + "2");
        sb.append("  lemma ").append(name).append("_congr:\n")
                .append("    \\forall ").append(otherParamsDecl).append(cvType).append(" ").append(cv)
                .append("1, ").append(cv).append("2, integer lo, hi;\n")
                .append("      (\\forall integer k; lo <= k <= hi ==> (").append(bodyForK1)
                .append(") == (").append(bodyForK2).append(")) ==>\n")
                .append("        ").append(name).append("(").append(args1).append("lo, hi) == ")
                .append(name).append("(").append(args2).append("lo, hi);\n")
                .append("  /* prova: indução em hi-lo — Coq/Why3 */\n\n");
    }

    /** §6: lemas de cota, emitidos apenas para funções de domínio-intervalo. */
    private void appendBoundLemmas(StringBuilder sb, Entry e, String fp, String fa) {
        Op op = e.op();
        String name = e.name();
        String bodyForK = replaceWord(e.bodyForLo(), "lo", "k");

        if (op == Op.SIGMA) {
            sb.append("  lemma ").append(name).append("_nonneg:\n")
                    .append("    \\forall ").append(fp).append("integer lo, hi;\n")
                    .append("      (\\forall integer k; lo <= k <= hi ==> (").append(bodyForK)
                    .append(") >= 0) ==>\n        ").append(name).append("(").append(fa)
                    .append("lo, hi) >= 0;\n  /* prova: indução em hi-lo — Coq/Why3 */\n\n");
            sb.append("  lemma ").append(name).append("_ubound:\n")
                    .append("    \\forall ").append(fp).append("integer lo, hi, bound;\n")
                    .append("      lo <= hi && (\\forall integer k; lo <= k <= hi ==> (").append(bodyForK)
                    .append(") <= bound) ==>\n        ").append(name).append("(").append(fa)
                    .append("lo, hi) <= (hi - lo + 1) * bound;\n")
                    .append("  /* prova: indução em hi-lo — Coq/Why3; guarda 'lo<=hi' necessária: sem ela ")
                    .append("(hi-lo+1) fica negativo e o lema fica indemonstrável no caso vazio */\n\n");
        } else if (op == Op.PI) {
            sb.append("  lemma ").append(name).append("_nonneg:\n")
                    .append("    \\forall ").append(fp).append("integer lo, hi;\n")
                    .append("      (\\forall integer k; lo <= k <= hi ==> (").append(bodyForK)
                    .append(") >= 0) ==>\n        ").append(name).append("(").append(fa)
                    .append("lo, hi) >= 0;\n")
                    .append("  /* prova: indução em hi-lo — Coq/Why3; cota superior (bound^(hi-lo+1)) ")
                    .append("omitida de propósito: depende do sinal dos fatores, não generalizável sem risco */\n\n");
        } else {
            sb.append("  lemma ").append(name).append("_nonneg:\n")
                    .append("    \\forall ").append(fp).append("integer lo, hi;\n")
                    .append("      lo <= hi && (\\forall integer k; lo <= k <= hi ==> (").append(bodyForK)
                    .append(") >= 0) ==>\n        ").append(name).append("(").append(fa)
                    .append("lo, hi) >= 0;\n  /* prova: indução em hi-lo — Coq/Why3 */\n\n");
            sb.append("  lemma ").append(name).append("_ubound:\n")
                    .append("    \\forall ").append(fp).append("integer lo, hi, bound;\n")
                    .append("      lo <= hi && (\\forall integer k; lo <= k <= hi ==> (").append(bodyForK)
                    .append(") <= bound) ==>\n        ").append(name).append("(").append(fa)
                    .append("lo, hi) <= bound;\n  /* prova: indução em hi-lo — Coq/Why3 */\n\n");
        }
    }

    // ─────────────────────────────── SET ───────────────────────────────

    private void appendSetEntry(StringBuilder sb, Entry e) {
        Op op = e.op();
        String name = e.name();
        List<String> typedFree = typedParams(e.freeVarNames(), e.freeVarTypes());
        String fp = typedFree.isEmpty() ? "" : String.join(", ", typedFree) + ", ";
        String fa = e.freeVarNames().isEmpty() ? "" : String.join(", ", e.freeVarNames()) + ", ";
        String T = e.boundVarType() == null || e.boundVarType().isBlank() ? "integer" : e.boundVarType();

        sb.append("  /* ").append(e.sourceComment()).append(" */\n");
        sb.append("  logic integer ").append(name).append("(").append(fp)
                .append("Set<").append(T).append("> dom);\n\n");

        if (op.hasNeutral) {
            sb.append("  axiom ").append(name).append("_empty:\n")
                    .append("    \\forall ").append(fp).append("Set<").append(T).append("> witness;\n")
                    .append("      ").append(name).append("(").append(fa)
                    .append("empty(witness)) == ").append(op.neutral).append(";\n\n");
            sb.append("  axiom ").append(name).append("_add:\n")
                    .append("    \\forall ").append(fp).append("Set<").append(T).append("> dom, ")
                    .append(T).append(" x;\n")
                    .append("      is_finite(dom) && not_belongs(x, dom) ==>\n")
                    .append("        ").append(name).append("(").append(fa)
                    .append("set_union(singleton(x), dom)) == ")
                    .append(op.combine(e.bodyForX(), name + "(" + fa + "dom)")).append(";\n\n");
        } else {
            // MIN/MAX sobre domínio-conjunto: sem ordem para "descascar" um elemento; axiomatiza-se
            // pelo caso base singleton + indução estrutural por união de dois sub-conjuntos
            // não-vazios (evita escolher arbitrariamente qual elemento sai primeiro).
            sb.append("  axiom ").append(name).append("_singleton:\n")
                    .append("    \\forall ").append(fp).append(T).append(" x;\n")
                    .append("      ").append(name).append("(").append(fa)
                    .append("singleton(x)) == ").append(e.bodyForX()).append(";\n\n");
            sb.append("  axiom ").append(name).append("_union:\n")
                    .append("    \\forall ").append(fp).append("Set<").append(T).append("> s, t;\n")
                    .append("      is_finite(s) && is_finite(t) && !equals(s, empty(s)) && !equals(t, empty(t)) ==>\n")
                    .append("        ").append(name).append("(").append(fa).append("set_union(s, t)) == ")
                    .append(op.combine(name + "(" + fa + "s)", name + "(" + fa + "t)")).append(";\n\n");
        }

        sb.append("  /* TODO(b2acsl): finitude de ").append(e.domainExprText())
                .append(" garantida pela tipagem de B (SIGMA/PI/MIN/MAX exigem domínio finito); se o WP ")
                .append("não a derivar do contexto, adicione 'requires is_finite(")
                .append(e.domainExprText()).append(");' à operação que usa ").append(name).append(" */\n\n");
    }

    // ──────────────────────────── utilidades ────────────────────────────

    private static List<String> typedParams(List<String> freeVarNames, List<String> freeVarTypes) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < freeVarNames.size(); i++) {
            out.add(freeVarType(freeVarTypes, i) + " " + freeVarNames.get(i));
        }
        return out;
    }

    private static String freeVarType(List<String> freeVarTypes, int index) {
        if (freeVarTypes == null || index >= freeVarTypes.size()) return "integer";
        String t = freeVarTypes.get(index);
        return t == null || t.isBlank() ? "integer" : t;
    }

    private static String argsWithRenamed(List<String> freeVarNames, String from, String to) {
        if (freeVarNames.isEmpty()) return "";
        List<String> out = new ArrayList<>();
        for (String v : freeVarNames) out.add(v.equals(from) ? to : v);
        return String.join(", ", out) + ", ";
    }

    private static String replaceWord(String text, String name, String replacement) {
        if (text == null) return null;
        return text.replaceAll(
                "(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])",
                java.util.regex.Matcher.quoteReplacement(replacement));
    }
}
