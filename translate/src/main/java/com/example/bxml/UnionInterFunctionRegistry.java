package com.example.bxml;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Registo de funções lógicas geradas para os quantificadores generalizados de conjunto de B —
 * {@code UNION}, {@code INTER} — extraídos durante a tradução BXML→ACSL. Irmã de {@link
 * SigmaFunctionRegistry} (mesmo esquema de fold sobre domínio finito), mas com uma assimetria
 * central que {@code SigmaFunctionRegistry} não tem: {@code UNION} tem neutro real ({@code
 * empty_set}), {@code INTER} não tem em geral — {@code empty_set} é o ABSORVENTE de ∩, não o
 * neutro, e usá-lo como caso base faria toda interseção colapsar para vazio. O tipo de retorno é
 * sempre {@code Set<T>} (nunca escalar), com {@code T} inferido do tipo de {@code E(z)} — {@code
 * E} tem de ser set-valued, verificado no chamador antes de registar.
 *
 * <ul>
 *   <li><b>interval</b> — {@code D} é {@code lo..hi}, filtro opcional. {@code UNION}: função
 *       lógica RECURSIVA total ({@code empty(witness)} no caso vazio). {@code INTER}: duas
 *       estratégias — com universo tipado {@code U_T} disponível (conjunto diferido/enumerado da
 *       máquina), função RECURSIVA total ({@code U_T} no caso vazio, análogo a {@code UNION}); sem
 *       universo, declaração+axiomas guardados (parcial — {@code lo>hi} fica indefinido, tal como
 *       B rejeita a interseção vazia).</li>
 *   <li><b>set</b> — {@code D} é um conjunto qualquer: axiomatização estrutural sobre {@code
 *       empty}/{@code set_union(singleton(x), S)}. Ao contrário de {@link SigmaFunctionRegistry},
 *       o axioma de adição NÃO precisa de {@code not_belongs(x, S)}: ∪ e ∩ são idempotentes, o
 *       resultado não muda com repetição. {@code INTER} não tem axioma de conjunto vazio (mesma
 *       razão do caso intervalo).</li>
 * </ul>
 */
public final class UnionInterFunctionRegistry {

    public enum Op {
        UNION("union_func", "set_union"),
        INTER("inter_func", "set_intersection");

        final String namePrefix;
        final String combineOp;

        Op(String namePrefix, String combineOp) {
            this.namePrefix = namePrefix;
            this.combineOp = combineOp;
        }

        String combine(String a, String b) {
            return combineOp + "(" + a + ", " + b + ")";
        }
    }

    private enum Kind { INTERVAL, SET }

    /**
     * @param elementType tipo ACSL do elemento de {@code E(z)} ({@code T} em {@code Set<T>}).
     * @param bodyForLo INTERVAL: {@code E} com a variável ligada substituída por {@code lo}.
     * @param bodyForHi INTERVAL: idem, substituída por {@code hi} (lema {@code _peel_last}).
     * @param filterForLo INTERVAL §2b/§3b: {@code Q} substituído por {@code lo}; {@code null} ⇒
     *     sem filtro.
     * @param filterForHi INTERVAL: idem, substituída por {@code hi}.
     * @param hasUniverse INTER apenas: {@code true} ⇒ Estratégia 1 (universo tipado disponível).
     * @param universeExpr INTER com {@code hasUniverse}: expressão ACSL do universo {@code U_T}
     *     (ex. {@code RulerOfTheSeas_ISLAND}).
     * @param congrVarName INTERVAL: nome do único parâmetro livre função-tipada quando {@code E}
     *     tem a forma {@code function_apply(congrVarName, ...)} — habilita {@code _congr}.
     * @param boundVarType SET: tipo ACSL do elemento do domínio {@code S} (não de {@code E}).
     * @param bodyForX SET: {@code E} com a variável ligada substituída por {@code x}.
     * @param domainExprText SET: texto ACSL do domínio {@code S}.
     */
    private record Entry(
            String name,
            Op op,
            Kind kind,
            List<String> freeVarNames,
            List<String> freeVarTypes,
            String sourceComment,
            String elementType,
            String bodyForLo,
            String bodyForHi,
            String filterForLo,
            String filterForHi,
            boolean hasUniverse,
            String universeExpr,
            String congrVarName,
            String congrVarType,
            String boundVarType,
            String bodyForX,
            String domainExprText) {}

    private static final Pattern FUNCTION_APPLY_HEAD =
            Pattern.compile("^function_apply\\(([A-Za-z_][A-Za-z0-9_]*),\\s*.*\\)$");

    private int counter = 0;
    private final List<Entry> entries = new ArrayList<>();

    /** §2/§2b/§3/§3b: domínio-intervalo. */
    public String registerInterval(
            Op op, List<String> freeVarNames, List<String> freeVarTypes, String sourceComment,
            String elementType, String bodyForLo, String bodyForHi, String filterForLo,
            String filterForHi, boolean hasUniverse, String universeExpr,
            String congrVarName, String congrVarType) {
        counter++;
        String name = String.format("%s%02d", op.namePrefix, counter);
        entries.add(new Entry(
                name, op, Kind.INTERVAL,
                List.copyOf(freeVarNames),
                freeVarTypes == null ? List.of() : List.copyOf(freeVarTypes),
                sourceComment, elementType, bodyForLo, bodyForHi, filterForLo, filterForHi,
                hasUniverse, universeExpr, congrVarName, congrVarType, null, null, null));
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

    /** §4: domínio-conjunto qualquer. */
    public String registerSet(
            Op op, List<String> freeVarNames, List<String> freeVarTypes, String sourceComment,
            String elementType, String boundVarType, String bodyForX, String domainExprText) {
        counter++;
        String name = String.format("%s%02d", op.namePrefix, counter);
        entries.add(new Entry(
                name, op, Kind.SET,
                List.copyOf(freeVarNames),
                freeVarTypes == null ? List.of() : List.copyOf(freeVarTypes),
                sourceComment, elementType, null, null, null, null, false, null, null, null,
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

    public String formatAxiomaticBlockFrom(int fromIndex) {
        List<Entry> slice = entries.subList(fromIndex, entries.size());
        if (slice.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("axiomatic generalized_union_inter_functions {\n");
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
        String setType = BxmlTypeRegistry.wrapSetType(e.elementType());
        List<String> typedFree = typedParams(e.freeVarNames(), e.freeVarTypes());
        String fp = typedFree.isEmpty() ? "" : String.join(", ", typedFree) + ", ";
        String fa = e.freeVarNames().isEmpty() ? "" : String.join(", ", e.freeVarNames()) + ", ";
        boolean total = op == Op.UNION || e.hasUniverse();

        sb.append("  /* ").append(e.sourceComment()).append(" */\n");

        if (op == Op.INTER && e.hasUniverse()) {
            sb.append("  /* neutro = universo do tipo de E: ").append(e.universeExpr()).append(" */\n");
        }

        if (total) {
            String neutral = op == Op.UNION ? "empty(" + e.bodyForLo() + ")" : e.universeExpr();
            String contributionLo = e.filterForLo() != null
                    ? "(" + e.filterForLo() + " ? " + e.bodyForLo() + " : " + neutral + ")"
                    : e.bodyForLo();
            sb.append("  logic ").append(setType).append(" ").append(name).append("(").append(fp)
                    .append("integer lo, integer hi) =\n")
                    .append("      lo > hi ? ").append(neutral).append("\n")
                    .append("              : ")
                    .append(op.combine(contributionLo, name + "(" + fa + "lo + 1, hi)"))
                    .append(";\n")
                    .append("  // decreases hi - lo;\n\n");
            if (op == Op.INTER) {
                sb.append("  lemma ").append(name).append("_typed:\n")
                        .append("    \\forall ").append(fp).append("integer k;\n")
                        .append("      inclusion(").append(replaceWord(e.bodyForLo(), "lo", "k"))
                        .append(", ").append(e.universeExpr()).append(");\n\n");
            }
        } else {
            // INTER sem universo: parcial (declaração + axiomas guardados), tal como B rejeita a
            // interseção vazia — lo>hi fica sem axioma, valor não especificado por construção.
            if (e.filterForLo() != null) {
                sb.append("  /* TODO(b2acsl): INTER com filtro e sem universo tipado — não ")
                        .append("suportado (ver §3b); domínio não convertido para a forma indexada ")
                        .append("por conjunto automaticamente */\n\n");
                return;
            }
            sb.append("  logic ").append(setType).append(" ").append(name).append("(").append(fp)
                    .append("integer lo, integer hi);\n\n");
            sb.append("  axiom ").append(name).append("_single:\n")
                    .append("    \\forall ").append(fp).append("integer lo;\n")
                    .append("      equals(").append(name).append("(").append(fa)
                    .append("lo, lo), ").append(e.bodyForLo()).append(");\n\n");
            sb.append("  axiom ").append(name).append("_peel_front:\n")
                    .append("    \\forall ").append(fp).append("integer lo, hi;\n")
                    .append("      lo < hi ==>\n")
                    .append("        equals(").append(name).append("(").append(fa).append("lo, hi), ")
                    .append(op.combine(e.bodyForLo(), name + "(" + fa + "lo + 1, hi)"))
                    .append(");\n\n");
        }

        appendBridgeLemmas(sb, e, fp, fa, total);
    }

    private void appendBridgeLemmas(StringBuilder sb, Entry e, String fp, String fa, boolean total) {
        Op op = e.op();
        String name = e.name();
        String x = e.elementType() + " x";
        String bodyForK = replaceWord(e.bodyForLo(), "lo", "k");
        String filterForK = e.filterForLo() != null ? replaceWord(e.filterForLo(), "lo", "k") : null;

        // _mem — a caracterização mais importante: provas de igualdade de conjuntos passam por
        // extensionalidade + pertinência (equals já É extensionalidade, por definição na lib), não
        // por desenrolar a recursão.
        sb.append("  lemma ").append(name).append("_mem:\n");
        if (op == Op.UNION) {
            String existsBody = filterForK != null
                    ? "(" + filterForK + ") && belongs(x, " + bodyForK + ")"
                    : "belongs(x, " + bodyForK + ")";
            sb.append("    \\forall ").append(fp).append("integer lo, hi, ").append(x).append(";\n")
                    .append("      belongs(x, ").append(name).append("(").append(fa).append("lo, hi))\n")
                    .append("        <==> (\\exists integer k; lo <= k <= hi && ").append(existsBody)
                    .append(");\n");
        } else {
            String forallBody = filterForK != null
                    ? "(lo <= k <= hi && (" + filterForK + ")) ==> belongs(x, " + bodyForK + ")"
                    : "lo <= k <= hi ==> belongs(x, " + bodyForK + ")";
            sb.append("    \\forall ").append(fp).append("integer lo, hi, ").append(x).append(";\n")
                    .append("      lo <= hi ==>\n")
                    .append("        (belongs(x, ").append(name).append("(").append(fa).append("lo, hi))\n")
                    .append("          <==> (\\forall integer k; ").append(forallBody).append("));\n");
        }
        sb.append("  /* prova: indução em hi-lo — Coq/Why3 */\n\n");

        // _empty (só quando o caso vazio é bem definido: UNION sempre, INTER só com universo).
        if (total) {
            String neutral = op == Op.UNION ? "empty(" + e.bodyForLo() + ")" : e.universeExpr();
            sb.append("  lemma ").append(name).append("_empty:\n")
                    .append("    \\forall ").append(fp).append("integer lo, hi;\n")
                    .append("      lo > hi ==> equals(").append(name).append("(").append(fa)
                    .append("lo, hi), ").append(neutral).append(");\n\n");
        } else {
            sb.append("  /* ").append(name).append(" não tem caso vazio: INTER sem universo tipado ")
                    .append("exige domínio não-vazio (B rejeita a interseção vazia) */\n\n");
        }

        // _peel_last — a definição descasca pela frente, mas o invariante de loop fala do prefixo já
        // percorrido, que descasca pelo fim. INTER parcial: só vale para lo<hi (lo==hi já coberto
        // pelo axioma _single; incluir lo==hi aqui pediria inter_funcNN(lo,lo-1), indefinido).
        String hiContribution = e.filterForHi() != null
                ? (total
                        ? "(" + e.filterForHi() + " ? " + e.bodyForHi() + " : "
                                + (op == Op.UNION ? "empty(" + e.bodyForHi() + ")" : e.universeExpr()) + ")"
                        : e.bodyForHi())
                : e.bodyForHi();
        String peelGuard = (op == Op.INTER && !total) ? "lo < hi" : "lo <= hi";
        sb.append("  lemma ").append(name).append("_peel_last:\n")
                .append("    \\forall ").append(fp).append("integer lo, hi;\n")
                .append("      ").append(peelGuard).append(" ==> equals(").append(name).append("(")
                .append(fa).append("lo, hi), ")
                .append(op.combine(name + "(" + fa + "lo, hi - 1)", hiContribution))
                .append(");\n  /* prova: indução em hi-lo — Coq/Why3 */\n\n");

        // _split — guardas DIFERENTES: UNION admite metades vazias (idempotência/neutro cobre);
        // INTER exige ambas as metades não-vazias (mesmo com universo, fica-se pelo guardado do
        // §6 por simplicidade — é seguro nos dois casos, só não é o mais forte possível).
        String splitGuard = op == Op.UNION ? "lo <= m && m <= hi + 1" : "lo < m && m <= hi";
        sb.append("  lemma ").append(name).append("_split:\n")
                .append("    \\forall ").append(fp).append("integer lo, m, hi;\n")
                .append("      ").append(splitGuard).append(" ==> equals(").append(name).append("(")
                .append(fa).append("lo, hi), ")
                .append(op.combine(name + "(" + fa + "lo, m - 1)", name + "(" + fa + "m, hi)"))
                .append(");\n  /* prova: indução em hi-lo — Coq/Why3 */\n\n");

        if (op == Op.UNION) {
            // _split_overlap — só para UNION: idempotência torna válido um split COM sobreposição
            // (perdoa erros de ±1 em invariantes de loop).
            sb.append("  lemma ").append(name).append("_split_overlap:\n")
                    .append("    \\forall ").append(fp).append("integer lo, m, hi;\n")
                    .append("      lo <= m && m <= hi ==> equals(").append(name).append("(")
                    .append(fa).append("lo, hi), ")
                    .append(op.combine(name + "(" + fa + "lo, m)", name + "(" + fa + "m, hi)"))
                    .append(");\n  /* prova: indução em hi-lo — Coq/Why3 */\n\n");
        }

        // _ubound (UNION) / _lbound (INTER) — cerco: cada parcela limita o resultado, direções opostas.
        String boundLemmaName = op == Op.UNION ? "_ubound" : "_lbound";
        String bodyForM = replaceWord(e.bodyForLo(), "lo", "m");
        sb.append("  lemma ").append(name).append(boundLemmaName).append(":\n")
                .append("    \\forall ").append(fp).append("integer lo, hi, m;\n")
                .append("      lo <= m && m <= hi ==>\n");
        if (op == Op.UNION) {
            sb.append("        inclusion(").append(bodyForM).append(", ").append(name)
                    .append("(").append(fa).append("lo, hi));\n");
        } else {
            sb.append("        inclusion(").append(name).append("(").append(fa)
                    .append("lo, hi), ").append(bodyForM).append(");\n");
        }
        sb.append("  /* prova: indução em hi-lo — Coq/Why3 */\n\n");

        // _mono (UNION) / _anti (INTER) — monotonia no domínio, direções opostas.
        String monoLemmaName = op == Op.UNION ? "_mono" : "_anti";
        sb.append("  lemma ").append(name).append(monoLemmaName).append(":\n")
                .append("    \\forall ").append(fp).append("integer lo, hi;\n")
                .append("      lo <= hi ==>\n");
        if (op == Op.UNION) {
            sb.append("        inclusion(").append(name).append("(").append(fa)
                    .append("lo, hi), ").append(name).append("(").append(fa).append("lo, hi + 1));\n");
        } else {
            sb.append("        inclusion(").append(name).append("(").append(fa)
                    .append("lo, hi + 1), ").append(name).append("(").append(fa).append("lo, hi));\n");
        }
        sb.append("  /* prova: indução em hi-lo — Coq/Why3 */\n\n");

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
                .append("      (\\forall integer k; lo <= k <= hi ==> equals(").append(bodyForK1)
                .append(", ").append(bodyForK2).append(")) ==>\n")
                .append("        equals(").append(name).append("(").append(args1).append("lo, hi), ")
                .append(name).append("(").append(args2).append("lo, hi));\n")
                .append("  /* prova: indução em hi-lo — Coq/Why3 */\n\n");
    }

    // ─────────────────────────────── SET ───────────────────────────────

    private void appendSetEntry(StringBuilder sb, Entry e) {
        Op op = e.op();
        String name = e.name();
        String setType = BxmlTypeRegistry.wrapSetType(e.elementType());
        List<String> typedFree = typedParams(e.freeVarNames(), e.freeVarTypes());
        String fp = typedFree.isEmpty() ? "" : String.join(", ", typedFree) + ", ";
        String fa = e.freeVarNames().isEmpty() ? "" : String.join(", ", e.freeVarNames()) + ", ";
        String T = e.boundVarType() == null || e.boundVarType().isBlank() ? "integer" : e.boundVarType();
        // wrapSetType (não "Set<" + T + ">" à mão): T pode já ser Set<...> (ex.: domínio FAM :
        // Set<Set<integer>>, cada elemento ss : Set<integer>) — sem o espaço antes do "> " de fecho,
        // o lexer ACSL lê "Set<Set<integer>>" como terminando em ">>" (um único token) e aborta com
        // "[Syntax error] >>." (mesmo caso documentado em BxmlTypeRegistry#wrapSetType).
        String domSetType = BxmlTypeRegistry.wrapSetType(T);

        sb.append("  /* ").append(e.sourceComment()).append(" */\n");
        sb.append("  logic ").append(setType).append(" ").append(name).append("(").append(fp)
                .append(domSetType).append(" dom);\n\n");

        if (op == Op.UNION) {
            // Dois witnesses de tipos DIFERENTES: "witness" só fixa o tipo do DOMÍNIO (Set<T>, ex.
            // Set<integer> de índices de jogador), "resultWitness" fixa o tipo do RESULTADO
            // (Set<elementType>, ex. Set<integer> de ilhas) — em geral T != elementType (domínio e
            // "elemento do conjunto-resultado" são conceptualmente independentes). Usar
            // e.bodyForX() aqui (como fazia antes) referenciava "x", a variável ligada do corpo, que
            // NÃO está no escopo deste axioma (só _add a declara) — "unbound logic variable x".
            sb.append("  axiom ").append(name).append("_empty:\n")
                    .append("    \\forall ").append(fp).append(domSetType)
                    .append(" witness, ").append(setType).append(" resultWitness;\n")
                    .append("      equals(").append(name).append("(").append(fa)
                    .append("empty(witness)), empty(resultWitness));\n\n");
            // Sem not_belongs(x, dom): ∪ é idempotente — o axioma vale mesmo com repetição de
            // inserção, ao contrário do análogo em SigmaFunctionRegistry.
            sb.append("  axiom ").append(name).append("_add:\n")
                    .append("    \\forall ").append(fp).append(domSetType).append(" dom, ")
                    .append(T).append(" x;\n")
                    .append("      is_finite(dom) ==>\n")
                    .append("        equals(").append(name).append("(").append(fa)
                    .append("set_union(singleton(x), dom)), ")
                    .append(op.combine(e.bodyForX(), name + "(" + fa + "dom)")).append(");\n\n");
        } else {
            // INTER: sem axioma de conjunto vazio (mesma razão do caso intervalo — empty_set é o
            // absorvente de ∩, não o neutro). Idempotência ainda dispensa not_belongs no passo.
            sb.append("  axiom ").append(name).append("_single:\n")
                    .append("    \\forall ").append(fp).append(T).append(" x;\n")
                    .append("      equals(").append(name).append("(").append(fa)
                    .append("singleton(x)), ").append(e.bodyForX()).append(");\n\n");
            sb.append("  axiom ").append(name).append("_add:\n")
                    .append("    \\forall ").append(fp).append(domSetType).append(" dom, ")
                    .append(T).append(" x;\n")
                    .append("      is_finite(dom) && !equals(dom, empty(dom)) ==>\n")
                    .append("        equals(").append(name).append("(").append(fa)
                    .append("set_union(singleton(x), dom)), ")
                    .append(op.combine(e.bodyForX(), name + "(" + fa + "dom)")).append(");\n\n");
        }

        sb.append("  /* TODO(b2acsl): finitude de ").append(e.domainExprText())
                .append(" garantida pela tipagem de B (UNION/INTER exigem domínio finito); se o WP ")
                .append("não a derivar do contexto, adicione 'requires is_finite(")
                .append(e.domainExprText()).append(");' à operação que usa ").append(name);
        if (op == Op.INTER) {
            sb.append(" — INTER exige também domínio NÃO-VAZIO: 'requires !equals(")
                    .append(e.domainExprText()).append(", empty(").append(e.domainExprText())
                    .append("));'");
        }
        sb.append(" */\n\n");
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
