package com.example.bxml;

import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Traduz predicados BXML ({@code pred_group}) para expressões ACSL.
 *
 * <p>{@code v : iseq(T)} / {@code v : seq(T)} → {@code iSeq} / {@code is_seq_of};
 * {@code ss : POW(S)} → {@code belongs(ss, pow_set(S))}; {@code ss : FIN(ss)} → {@code is_finite(ss)};
 * {@code ss : POW1(S)} → {@code belongs(ss, pow_set(S)) && card(ss) > 0} (mesma base de POW, mais
 * não-vazio via {@code card}); {@code ss : FIN1(S)} → {@code is_finite(ss) && card(ss) > 0}
 * (mesma base de FIN);
 * {@code x : BOOL} → {@code belongs(x, BOOL)}; {@code x : NAT} → {@code belongs(x, NAT)};
 * {@code x : INT} → {@code belongs(x, INT)};
 * {@code x /: s} → {@code not_belongs(x, s)}; {@code ss <: tt} → {@code inclusion(ss, tt)};
 * {@code ss /<: tt} → {@code not_inclusion(ss, tt)}; {@code ss <<: tt} → {@code
 * strict_inclusion(ss, tt)}; {@code ss /<<: tt} → {@code not_strict_inclusion(ss, tt)};
 * comparadores inteiros {@code <=i}, {@code <i} →
 * {@code <=}, {@code <}; igualdade entre conjuntos ({@code Set<…>}, {@code ran} sobre relação, …) →
 * {@code equals(a, b)}; escalares (ex.: {@code card}) mantêm {@code ==}.
 *
 * @see <a href="https://www.atelierb.eu/wp-content/uploads/2023/10/bxml-1.0.html">BXML 1.0</a>
 */
public final class BxmlPredicateToAcsl {

    private BxmlPredicateToAcsl() {}

    public static List<String> translatePredicateBlock(Element predParent, BxmlTranslateContext ctx) {
        List<String> out = new ArrayList<>();
        Element first = BxmlDomUtils.firstPredChild(predParent);
        if (first != null) {
            String t = translatePred(first, ctx);
            if (!t.isBlank()) out.add(t);
        }
        return out;
    }

    /**
     * Conteúdo de {@code <Invariant>}: primeiro elemento preditivo (ex.: {@code Exp_Comparison}, {@code Nary_Pred}).
     */
    public static String translateInvariantContent(Element invariantEl, BxmlTranslateContext ctx) {
        Element p = BxmlDomUtils.firstPredChild(invariantEl);
        if (p == null) return "";
        return translatePred(p, ctx);
    }

    /**
     * Como {@link #translateInvariantContent}, mas devolve os conjunctos de topo separadamente em
     * vez de uma única string unida por {@code " && "} — um {@code B: P & Q & R} vira 3 entradas
     * {@code [P, Q, R]}. Usado por {@link BxmlInvariantTranslator} para declarar múltiplos
     * predicados nomeados ({@code Machine_invariant_1}, {@code Machine_invariant_2}, …) em vez de
     * um só predicado com corpo conjuntivo, e por {@link BxmlLoopTranslator} para gerar uma
     * {@code loop invariant} por conjuncto em vez de uma só cláusula que embrulha vários — cada
     * conjuncto vira o seu próprio goal de WP, em vez de um goal único que falha/passa em bloco.
     */
    public static List<String> translateInvariantConjuncts(Element invariantEl, BxmlTranslateContext ctx) {
        List<String> out = new ArrayList<>();
        Element p = BxmlDomUtils.firstPredChild(invariantEl);
        collectTopLevelAndConjuncts(p, ctx, out);
        return out;
    }

    /** Achata {@code Nary_Pred op='&'} de topo (recursivamente); outros nós viram uma folha só. */
    private static void collectTopLevelAndConjuncts(
            Element p, BxmlTranslateContext ctx, List<String> out) {
        if (p == null) return;
        if ("Nary_Pred".equals(p.getLocalName()) && "&".equals(p.getAttribute("op"))) {
            NodeList nl = p.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element e = (Element) n;
                if ("Attr".equals(e.getLocalName())) continue;
                collectTopLevelAndConjuncts(e, ctx, out);
            }
            return;
        }
        String t = translatePred(p, ctx);
        if (!t.isBlank()) out.add(t);
    }

    /**
     * Filho preditivo direto de {@code Properties} (ex.: {@code Exp_Comparison}, {@code Nary_Pred}).
     */
    public static String translatePropertyPred(Element predElement, BxmlTranslateContext ctx) {
        return translatePred(predElement, ctx);
    }

    /**
     * Lado direito {@code S --> T} de uma restrição {@code v : S --> T} dentro do predicado (ex.
     * cláusula {@code WHERE} de {@code ANY_Sub}), ou {@code null}.
     */
    public static Element findFunctionArrowRhsForVariable(Element predRoot, String varName) {
        if (predRoot == null || varName == null || varName.isBlank()) {
            return null;
        }
        return findFunctionArrowRhsForVariable0(predRoot, varName.trim());
    }

    /**
     * Tipo {@code logic} para quantificador {@code \\forall} (ex. {@code Function_int_int}) a partir
     * de {@code <Pred>} e do {@code Id} da variável de {@code ANY_Sub}: usa {@code v : A --> B} no
     * predicado; senão cai no {@code typref} (ex. {@code Relation_int_int} → {@code Function_int_int}).
     */
    public static String acslQuantifierLogicTypeForAnyVariable(
            Element predParent, Element varId, BxmlTranslateContext ctx) {
        if (varId == null || ctx == null) {
            return "integer";
        }
        String name = varId.getAttribute("value");
        if (name == null) {
            name = "";
        }
        name = name.trim();
        Element p0 = BxmlDomUtils.firstPredChild(predParent);
        Element arrow = p0 != null ? findFunctionArrowRhsForVariable(p0, name) : null;
        if (arrow != null) {
            return functionArrowBinaryToAcslFunctionType(arrow);
        }
        String trAttr = varId.getAttribute("typref");
        if (trAttr != null && !trAttr.isBlank()) {
            try {
                int tr = Integer.parseInt(trAttr.trim());
                String t = ctx.types().acslVariableLogicTypeFromTypref(tr);
                return relationOrFallbackQuantifierType(t);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return "integer";
    }

    private static String relationOrFallbackQuantifierType(String t) {
        if (t == null || t.isBlank()) {
            return "integer";
        }
        String trimmed = t.trim();
        // Instanciação genérica (ex.: Relation<integer,integer>) → Function<A,B>, resolvida via
        // type Function<A,B> = Relation<A,B>; em types.acsl, para QUALQUER par A,B.
        if (trimmed.startsWith("Relation<")) {
            return "Function<" + trimmed.substring("Relation<".length());
        }
        // Nome achatado legado (codomínio/domínio tupla, declarado em par pelo TupleCodomainTypeRegistry).
        if (trimmed.startsWith("Relation_")) {
            return "Function_" + trimmed.substring("Relation_".length());
        }
        return trimmed;
    }

    private static String functionArrowBinaryToAcslFunctionType(Element arrow) {
        Element[] dr = BxmlExpressionToAcsl.twoDirectExpChildren(arrow);
        if (dr[0] == null || dr[1] == null) {
            return "Function<integer,integer>";
        }
        String lhs = arrowEndToBNameForProduct(dr[0]);
        String rhs = arrowEndToBNameForProduct(dr[1]);
        String relation = BxmlTypeRegistry.powCartesianProductToAcslRelationType(lhs + "*" + rhs);
        return relationOrFallbackQuantifierType(relation);
    }

    /** Extremidade B de {@code -->} como nome de tipo escalar para {@link BxmlTypeRegistry}. */
    private static String arrowEndToBNameForProduct(Element e) {
        if (e == null) {
            return "INTEGER";
        }
        String ln = e.getLocalName();
        if ("Binary_Exp".equals(ln) && BxmlExpressionToAcsl.isIntervalBinaryExp(e)) {
            return "INTEGER";
        }
        if ("Id".equals(ln)) {
            String v = e.getAttribute("value").trim();
            return switch (v) {
                case "BOOL" -> "BOOL";
                case "NAT", "NAT1", "INTEGER", "INT" -> "INTEGER";
                default -> "INTEGER";
            };
        }
        if ("Unary_Exp".equals(ln)) {
            String u = e.getAttribute("op");
            if ("seq".equals(u) || "iseq".equals(u)) {
                return "INTEGER";
            }
        }
        return "INTEGER";
    }

    private static Element findFunctionArrowRhsForVariable0(Element pred, String varName) {
        if (pred == null) {
            return null;
        }
        String ln = pred.getLocalName();
        if ("Exp_Comparison".equals(ln) && isColonTypingComparison(pred)) {
            Element[] pair = BxmlExpressionToAcsl.twoDirectExpChildren(pred);
            if (pair[0] != null
                    && "Id".equals(pair[0].getLocalName())
                    && varName.equals(pair[0].getAttribute("value").trim())
                    && pair[1] != null
                    && BxmlExpressionToAcsl.isFunctionArrowType(pair[1])) {
                return pair[1];
            }
            return null;
        }
        if ("Nary_Pred".equals(ln)) {
            NodeList nl = pred.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element ch = (Element) n;
                if ("Attr".equals(ch.getLocalName())) continue;
                Element found = findFunctionArrowRhsForVariable0(ch, varName);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if ("Unary_Pred".equals(ln)) {
            return findFunctionArrowRhsForVariable0(BxmlDomUtils.firstPredChild(pred), varName);
        }
        if ("Binary_Pred".equals(ln)) {
            Element[] pair = BxmlDomUtils.twoDirectPredChildren(pred);
            if (pair[0] != null) {
                Element f = findFunctionArrowRhsForVariable0(pair[0], varName);
                if (f != null) {
                    return f;
                }
            }
            if (pair[1] != null) {
                return findFunctionArrowRhsForVariable0(pair[1], varName);
            }
        }
        return null;
    }

    private static boolean isColonTypingComparison(Element cmp) {
        String op = cmp.getAttribute("op");
        return op != null && ":".equals(op.trim());
    }

    private static String translatePred(Element p, BxmlTranslateContext ctx) {
        String ln = p.getLocalName();
        return switch (ln) {
            case "Exp_Comparison" -> translateExpComparison(p, ctx);
            case "Nary_Pred" -> translateNaryPred(p, ctx);
            case "Unary_Pred" -> translateUnaryPred(p, ctx);
            case "Binary_Pred" -> translateBinaryPred(p, ctx);
            case "Quantified_Pred" -> translateQuantifiedPred(p, ctx);
            default -> "";
        };
    }

    private static String translateExpComparison(Element cmp, BxmlTranslateContext ctx) {
        String op = normalizeExpComparisonOp(cmp.getAttribute("op"));
        Element[] pair = BxmlExpressionToAcsl.twoDirectExpChildren(cmp);
        if (pair[0] == null || pair[1] == null) return "";
        Element leftEl = pair[0];
        Element rightEl = pair[1];
        if ("/:".equals(op)) {
            return translateNotBelongsComparison(leftEl, rightEl, ctx);
        }
        if (":".equals(op)) {
            return translateMembershipComparison(leftEl, rightEl, ctx);
        }
        String left = BxmlExpressionToAcsl.translate(leftEl, ctx);
        String right = BxmlExpressionToAcsl.translate(rightEl, ctx);
        if ("<:".equals(op)) {
            return "inclusion(" + left + ", " + right + ")";
        }
        if ("/<:".equals(op)) {
            return "not_inclusion(" + left + ", " + right + ")";
        }
        if ("<<:".equals(op)) {
            return "strict_inclusion(" + left + ", " + right + ")";
        }
        if ("/<<:".equals(op)) {
            return "not_strict_inclusion(" + left + ", " + right + ")";
        }
        if ("=".equals(op)) {
            return translateEqualityComparison(leftEl, rightEl, left, right, ctx);
        }
        if ("!=".equals(op)) {
            if (BxmlExpressionToAcsl.isSetValued(leftEl, ctx)
                    && BxmlExpressionToAcsl.isSetValued(rightEl, ctx)) {
                return "!equals(" + left + ", " + right + ")";
            }
        }
        return "(" + left + " " + op + " " + right + ")";
    }

    private static String translateNotBelongsComparison(
            Element leftEl, Element rightEl, BxmlTranslateContext ctx) {
        String left = BxmlExpressionToAcsl.translate(leftEl, ctx);
        String right = BxmlExpressionToAcsl.translate(rightEl, ctx);
        return "not_belongs(" + left + ", " + right + ")";
    }

    private static String translateMembershipComparison(
            Element leftEl, Element rightEl, BxmlTranslateContext ctx) {
        // ss : POW(S) → belongs(ss, pow_set(S)); ss : FIN(ss) → is_finite(ss)
        if ("Unary_Exp".equals(rightEl.getLocalName())) {
            String uop = rightEl.getAttribute("op");
            if ("POW".equals(uop)) {
                String left = BxmlExpressionToAcsl.translate(leftEl, ctx);
                Element inner = BxmlDomUtils.firstNonAttrElementChild(rightEl);
                String setAtom = powDomainSetAtom(inner, ctx);
                return powSetMembership(left, setAtom);
            }
            if ("FIN".equals(uop) || "fin".equals(uop)) {
                String left = BxmlExpressionToAcsl.translate(leftEl, ctx);
                return "is_finite(" + left + ")";
            }
            // ss : POW1(S) — subconjuntos NÃO-VAZIOS de S (POW menos {}); mesma base de POW
            // (pow_set), acrescida de card(ss) > 0.
            if ("POW1".equals(uop) || "pow1".equals(uop)) {
                String left = BxmlExpressionToAcsl.translate(leftEl, ctx);
                Element inner = BxmlDomUtils.firstNonAttrElementChild(rightEl);
                String setAtom = powDomainSetAtom(inner, ctx);
                return powSetMembership(left, setAtom) + " && (card(" + left + ") > 0)";
            }
            // ss : FIN1(S) — subconjuntos finitos NÃO-VAZIOS de S; mesma base de FIN (is_finite,
            // domínio S não verificado — idem à limitação já existente em FIN), mais card(ss) > 0.
            if ("FIN1".equals(uop) || "fin1".equals(uop)) {
                String left = BxmlExpressionToAcsl.translate(leftEl, ctx);
                return "is_finite(" + left + ") && (card(" + left + ") > 0)";
            }
            if ("iseq".equals(uop)) {
                String left = BxmlExpressionToAcsl.translate(leftEl, ctx);
                return "iSeq(" + left + ")";
            }
            if ("seq".equals(uop)) {
                String left = BxmlExpressionToAcsl.translate(leftEl, ctx);
                Element typeArg = BxmlDomUtils.firstNonAttrElementChild(rightEl);
                String setAtom = bTypeArgToSeqOfSetName(typeArg, ctx);
                return "is_seq_of(" + left + ", " + setAtom + ")";
            }
        }
        // r : (S <-> T) — relação (conjunto de TODOS os pares (x,y), x:S, y:T, sem exigir
        // funcionalidade/totalidade): r é QUALQUER subconjunto de S*T. equivale a
        // belongs(r, pow_set(cartesian_product(S,T))), mas inclusion(r, cartesian_product(S,T))
        // evita instanciar belongs num nível de Set<Set<...>> extra (mesma cascata de
        // monomorphização já vista para POW aninhado) para um caso que não precisa dela — pow_set_def
        // já reduz a exatamente isto (belongs(s,pow_set(u)) <==> inclusion(s,u)).
        if (BxmlExpressionToAcsl.isRelationArrowType(rightEl)) {
            Element[] domRng = BxmlExpressionToAcsl.twoDirectExpChildren(rightEl);
            if (domRng[0] == null || domRng[1] == null) return "";
            String rel = BxmlExpressionToAcsl.translate(leftEl, ctx);
            String domainSet = BxmlExpressionToAcsl.intervalOrSetComprehensionRef(domRng[0], ctx);
            String rangeSet = functionArrowRangeSet(domRng[1], ctx);
            return "inclusion(" + rel + ", cartesian_product(" + domainSet + ", " + rangeSet + "))";
        }
        // f : (S --> T) com S intervalo/compreensão — função total (ACSL_Lib function_functions/is_total.acsl)
        if (BxmlExpressionToAcsl.isFunctionArrowType(rightEl)) {
            Element[] domRng = BxmlExpressionToAcsl.twoDirectExpChildren(rightEl);
            if (domRng[0] == null || domRng[1] == null) return "";
            String fun = BxmlExpressionToAcsl.translate(leftEl, ctx);
            String domainSet = BxmlExpressionToAcsl.intervalOrSetComprehensionRef(domRng[0], ctx);
            String rangeSet = functionArrowRangeSet(domRng[1], ctx);
            return "is_total_function(" + fun + ", " + domainSet + ", " + rangeSet + ")";
        }
        // f : (S +-> T) — função PARCIAL (ACSL_Lib function_functions/is_partial.acsl); mesma
        // forma que S --> T acima, só troca is_total_function por is_partial_function. Faltava
        // por completo antes (nenhum isPartialFunctionArrowType existia): caía no fallback
        // genérico belongs(f, translate(S +-> T)), e translateBinary não tem caso para "+->",
        // deixando o operador B cru vazar para o texto ACSL ("[Syntax error] ->.").
        if (BxmlExpressionToAcsl.isPartialFunctionArrowType(rightEl)) {
            Element[] domRng = BxmlExpressionToAcsl.twoDirectExpChildren(rightEl);
            if (domRng[0] == null || domRng[1] == null) return "";
            String fun = BxmlExpressionToAcsl.translate(leftEl, ctx);
            String domainSet = BxmlExpressionToAcsl.intervalOrSetComprehensionRef(domRng[0], ctx);
            String rangeSet = functionArrowRangeSet(domRng[1], ctx);
            return "is_partial_function(" + fun + ", " + domainSet + ", " + rangeSet + ")";
        }
        // f : (S -->> T) — função total surjetiva (ACSL_Lib function_functions/is_total.acsl + is_surjective.acsl)
        if (BxmlExpressionToAcsl.isTotalSurjectionArrowType(rightEl)) {
            Element[] domRng = BxmlExpressionToAcsl.twoDirectExpChildren(rightEl);
            if (domRng[0] == null || domRng[1] == null) return "";
            String fun = BxmlExpressionToAcsl.translate(leftEl, ctx);
            String domainSet = BxmlExpressionToAcsl.intervalOrSetComprehensionRef(domRng[0], ctx);
            String rangeSet = functionArrowRangeSet(domRng[1], ctx);
            return "is_total_function(" + fun + ", " + domainSet + ", " + rangeSet + ")"
                    + " && is_surjective(" + fun + ", " + rangeSet + ")";
        }
        // f : (S >+> T) — injeção PARCIAL (ACSL_Lib is_partial.acsl + is_injective.acsl); is_injective
        // não recebe domínio/imagem (só quantifica sobre o próprio par), mesmo formato de composição
        // de is_total_function/is_surjective acima para -->>.
        if (BxmlExpressionToAcsl.isPartialInjectionArrowType(rightEl)) {
            Element[] domRng = BxmlExpressionToAcsl.twoDirectExpChildren(rightEl);
            if (domRng[0] == null || domRng[1] == null) return "";
            String fun = BxmlExpressionToAcsl.translate(leftEl, ctx);
            String domainSet = BxmlExpressionToAcsl.intervalOrSetComprehensionRef(domRng[0], ctx);
            String rangeSet = functionArrowRangeSet(domRng[1], ctx);
            return "is_partial_function(" + fun + ", " + domainSet + ", " + rangeSet + ")"
                    + " && is_injective(" + fun + ")";
        }
        // f : (S >-> T) — injeção TOTAL (ACSL_Lib is_total.acsl + is_injective.acsl)
        if (BxmlExpressionToAcsl.isTotalInjectionArrowType(rightEl)) {
            Element[] domRng = BxmlExpressionToAcsl.twoDirectExpChildren(rightEl);
            if (domRng[0] == null || domRng[1] == null) return "";
            String fun = BxmlExpressionToAcsl.translate(leftEl, ctx);
            String domainSet = BxmlExpressionToAcsl.intervalOrSetComprehensionRef(domRng[0], ctx);
            String rangeSet = functionArrowRangeSet(domRng[1], ctx);
            return "is_total_function(" + fun + ", " + domainSet + ", " + rangeSet + ")"
                    + " && is_injective(" + fun + ")";
        }
        // f : (S +->> T) — sobrejeção PARCIAL (ACSL_Lib is_partial.acsl + is_surjective.acsl)
        if (BxmlExpressionToAcsl.isPartialSurjectionArrowType(rightEl)) {
            Element[] domRng = BxmlExpressionToAcsl.twoDirectExpChildren(rightEl);
            if (domRng[0] == null || domRng[1] == null) return "";
            String fun = BxmlExpressionToAcsl.translate(leftEl, ctx);
            String domainSet = BxmlExpressionToAcsl.intervalOrSetComprehensionRef(domRng[0], ctx);
            String rangeSet = functionArrowRangeSet(domRng[1], ctx);
            return "is_partial_function(" + fun + ", " + domainSet + ", " + rangeSet + ")"
                    + " && is_surjective(" + fun + ", " + rangeSet + ")";
        }
        // f : (S >->> T) — bijeção TOTAL (ACSL_Lib is_total.acsl + is_bijective.acsl, que já compõe
        // is_injective + is_surjective).
        if (BxmlExpressionToAcsl.isTotalBijectionArrowType(rightEl)) {
            Element[] domRng = BxmlExpressionToAcsl.twoDirectExpChildren(rightEl);
            if (domRng[0] == null || domRng[1] == null) return "";
            String fun = BxmlExpressionToAcsl.translate(leftEl, ctx);
            String domainSet = BxmlExpressionToAcsl.intervalOrSetComprehensionRef(domRng[0], ctx);
            String rangeSet = functionArrowRangeSet(domRng[1], ctx);
            return "is_total_function(" + fun + ", " + domainSet + ", " + rangeSet + ")"
                    + " && is_bijective(" + fun + ", " + rangeSet + ")";
        }
        String left = BxmlExpressionToAcsl.translate(leftEl, ctx);
        if (BxmlExpressionToAcsl.isIntervalBinaryExp(rightEl)) {
            String right = BxmlExpressionToAcsl.intervalOrSetComprehensionRef(rightEl, ctx);
            return "belongs(" + left + ", " + right + ")";
        }
        String right = BxmlExpressionToAcsl.translate(rightEl, ctx);
        // x : T — pertença (ex.: nn : NAT → belongs(nn, NAT)) — ACSL_Lib/set_functions/belongs.acsl
        if (isPrimitiveTypeName(right)) {
            if ("NAT".equals(right)) return "belongs(" + left + ", NAT)";
            if ("BOOL".equals(right)) return "belongs(" + left + ", BOOL)";
            if ("INT".equals(right)) return "belongs(" + left + ", INT)";
            return "(" + left + " /* : " + right + " */)";
        }
        return "belongs(" + left + ", " + right + ")";
    }

    private static String translateEqualityComparison(
            Element leftEl, Element rightEl, String left, String right, BxmlTranslateContext ctx) {
        // <==> tem precedência menor que && em ACSL: sem parênteses envolvendo TODA a
        // bicondicional, "A && flag != 0 <==> B && C" analisa como "(A && flag != 0) <==>
        // (B && C)" em vez de "A && (flag != 0 <==> B) && C" quando este predicado é um
        // conjunto de um Nary_Pred op='&' maior (ex.: invariante de loop). O operador nativo
        // "<=>" do B (mais abaixo) já envolve o resultado inteiro; esta forma derivada
        // ("v = bool(P)" -> "v <==> P") precisa do mesmo tratamento.
        if ("Boolean_Exp".equals(rightEl.getLocalName())) {
            Element predEl = BxmlDomUtils.firstNonAttrElementChild(rightEl);
            if (predEl != null) {
                return "((" + left + " != 0) <==> " + translatePred(predEl, ctx) + ")";
            }
        }
        if ("Boolean_Exp".equals(leftEl.getLocalName())) {
            Element predEl = BxmlDomUtils.firstNonAttrElementChild(leftEl);
            if (predEl != null) {
                return "((" + right + " != 0) <==> " + translatePred(predEl, ctx) + ")";
            }
        }
        if ("Id".equals(leftEl.getLocalName())
                && "Boolean_Literal".equals(rightEl.getLocalName())) {
            return "("
                    + BxmlExpressionToAcsl.translate(leftEl, ctx)
                    + " == "
                    + BxmlExpressionToAcsl.booleanLiteralRhsForVariable(
                            leftEl, rightEl.getAttribute("value"), ctx)
                    + ")";
        }
        if ("Id".equals(rightEl.getLocalName())
                && "Boolean_Literal".equals(leftEl.getLocalName())) {
            return "("
                    + BxmlExpressionToAcsl.translate(rightEl, ctx)
                    + " == "
                    + BxmlExpressionToAcsl.booleanLiteralRhsForVariable(
                            rightEl, leftEl.getAttribute("value"), ctx)
                    + ")";
        }
        if (BxmlExpressionToAcsl.isListValued(leftEl, ctx)
                && BxmlExpressionToAcsl.isRelationOrFunctionValued(rightEl, ctx)) {
            return "(" + right + " == list_to_function(" + left + "))";
        }
        if (BxmlExpressionToAcsl.isListValued(rightEl, ctx)
                && BxmlExpressionToAcsl.isRelationOrFunctionValued(leftEl, ctx)) {
            return "(" + left + " == list_to_function(" + right + "))";
        }
        // V = %x.(x:D | {y|Q}) — lambda "mapa" valorado-em-conjunto (ver
        // BxmlExpressionToAcsl#setValuedMapLambdaPointwiseEquality): V é a relação inteira, o
        // corpo é só o valor pontual — precisa de \forall pontual, não igualdade nua.
        String setValuedLambdaEq =
                GeneralizedQuantifierTranslator.setValuedMapLambdaPointwiseEquality(leftEl, rightEl, ctx);
        if (setValuedLambdaEq != null) return setValuedLambdaEq;
        setValuedLambdaEq =
                GeneralizedQuantifierTranslator.setValuedMapLambdaPointwiseEquality(rightEl, leftEl, ctx);
        if (setValuedLambdaEq != null) return setValuedLambdaEq;
        if (BxmlExpressionToAcsl.isSetValued(leftEl, ctx)
                && BxmlExpressionToAcsl.isSetValued(rightEl, ctx)) {
            return "equals(" + left + ", " + right + ")";
        }
        return "(" + left + " == " + right + ")";
    }

    /**
     * Operadores BXML (ex.: {@code &lt;=i}, {@code &lt;i}) → ACSL numérico {@code <=}, {@code <}.
     */
    private static String normalizeExpComparisonOp(String raw) {
        if (raw == null) return "";
        String o = raw.trim();
        return switch (o) {
            case "&lt;:" -> "<:";
            case "/&lt;:", "/<:" -> "/<:";
            case "&lt;&lt;:", "<<:" -> "<<:";
            case "/&lt;&lt;:", "/<<:" -> "/<<:";
            case "&lt;=i", "<=i" -> "<=";
            case "&lt;i", "<i" -> "<";
            case "&gt;=i", ">=i" -> ">=";
            case "&gt;i", ">i" -> ">";
            case "/=" -> "!=";
            default -> o;
        };
    }

    private static String translateNaryPred(Element np, BxmlTranslateContext ctx) {
        String op = np.getAttribute("op");
        List<String> parts = new ArrayList<>();
        NodeList nl = np.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            parts.add(translatePred(e, ctx));
        }
        if ("&".equals(op)) return String.join(" && ", parts);
        if ("or".equals(op)) return String.join(" || ", parts);
        return String.join(" && ", parts);
    }

    private static String translateUnaryPred(Element up, BxmlTranslateContext ctx) {
        String op = up.getAttribute("op");
        Element child = BxmlDomUtils.firstPredChild(up);
        if (child == null) return "";
        String c = translatePred(child, ctx);
        if ("not".equals(op)) return "!(" + c + ")";
        return c;
    }

    private static String translateBinaryPred(Element bp, BxmlTranslateContext ctx) {
        String op = bp.getAttribute("op");
        Element[] pair = BxmlDomUtils.twoDirectPredChildren(bp);
        if (pair[0] == null || pair[1] == null) return "";
        String a = translatePred(pair[0], ctx);
        String b = translatePred(pair[1], ctx);
        if ("=>".equals(op)) return "(" + a + " ==> " + b + ")";
        if ("<=>".equals(op)) return "(" + a + " <==> " + b + ")";
        return "(" + a + " " + op + " " + b + ")";
    }

    private static boolean isPrimitiveTypeName(String right) {
        return switch (right) {
            case "NAT", "INTEGER", "BOOL", "INT", "REAL" -> true;
            default -> false;
        };
    }

    /** Codomínio B (ex. {@code NAT}) → nome do conjunto ACSL da lib (ex. {@code NAT}). */
    private static String functionArrowRangeSet(Element codomainEl, BxmlTranslateContext ctx) {
        if (codomainEl != null && "Id".equals(codomainEl.getLocalName())) {
            String v = codomainEl.getAttribute("value");
            if (v == null) {
                v = "";
            }
            return switch (v.trim()) {
                case "NAT", "INTEGER", "INT" -> "NAT";
                case "BOOL" -> "BOOL";
                default -> BxmlExpressionToAcsl.translate(codomainEl, ctx);
            };
        }
        return BxmlExpressionToAcsl.translate(codomainEl, ctx);
    }

    /** Segundo argumento de {@code is_seq_of}/{@code inclusion} (conjunto ACSL da lib, ex. {@code NAT}). */
    private static String bTypeArgToSeqOfSetName(Element typeArg, BxmlTranslateContext ctx) {
        if (typeArg != null && "Id".equals(typeArg.getLocalName())) {
            String v = typeArg.getAttribute("value");
            if (v == null || v.isBlank()) return "NAT";
            return switch (v) {
                case "NAT", "INTEGER", "INT" -> "NAT";
                case "BOOL" -> "BOOL";
                default -> {
                    if (ctx != null) {
                        String renamed = ctx.enumeratedSetRenames().get(v);
                        if (renamed != null) yield renamed;
                    }
                    yield v;
                }
            };
        }
        return "NAT";
    }

    /**
     * Domínio de {@code POW(S)}/{@code POW1(S)}: nome simples ({@code ELEM}) → {@link
     * #bTypeArgToSeqOfSetName}; expressão aninhada (ex. {@code POW(ELEM)} em {@code POW1(POW(ELEM))})
     * → tradutor de expressões genérico ({@code pow_set(ELEM)}).
     */
    private static String powDomainSetAtom(Element typeArg, BxmlTranslateContext ctx) {
        return typeArg != null && "Id".equals(typeArg.getLocalName())
                ? bTypeArgToSeqOfSetName(typeArg, ctx)
                : BxmlExpressionToAcsl.translate(typeArg, ctx);
    }

    /** {@code ss : POW(S)} / {@code ss : POW1(S)} → {@code belongs(ss, pow_set(S))} (set_functions/pow_set.acsl). */
    private static String powSetMembership(String left, String setAtom) {
        return "belongs(" + left + ", pow_set(" + setAtom + "))";
    }

    /**
     * B {@code !x.(P => Q)} / {@code #x.(P)} → ACSL {@code (\forall integer x; body)} /
     * {@code (\exists integer x; body)}.
     *
     * <p>Suporta múltiplas variáveis ({@code !x,y.(…)}).
     *
     * <p>Caso especial: {@code #v.(v = E & P)} (uma só variável ligada, definida por igualdade
     * dentro do próprio corpo) → {@code \let v = E; P}, evitando um existencial desnecessário
     * quando a testemunha é univocamente determinada — ver
     * {@link #tryExistsLetElimination(String, Element, BxmlTranslateContext)}.
     */
    private static String translateQuantifiedPred(Element qp, BxmlTranslateContext ctx) {
        String type = qp.getAttribute("type");
        String quant = "!".equals(type) ? "\\forall" : "\\exists";

        Element varsEl = childByName(qp, "Variables");
        List<String> varDecls = new ArrayList<>();
        List<String> varNames = new ArrayList<>();
        if (varsEl != null) {
            NodeList nl = varsEl.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element e = (Element) n;
                if ("Attr".equals(e.getLocalName()) || !"Id".equals(e.getLocalName())) continue;
                varNames.add(e.getAttribute("value"));
                varDecls.add("integer " + e.getAttribute("value"));
            }
        }
        if (varDecls.isEmpty()) return "/* quantified pred */";

        Element bodyEl = childByName(qp, "Body");
        if (bodyEl == null) return "/* quantified pred */";
        Element bodyPred = BxmlDomUtils.firstPredChild(bodyEl);
        if (bodyPred == null) return "/* quantified pred */";

        if ("#".equals(type) && varNames.size() == 1) {
            String letForm = tryExistsLetElimination(varNames.get(0), bodyPred, ctx);
            if (letForm != null) return letForm;
        }

        String body = translatePred(bodyPred, ctx);
        return "(" + quant + " " + String.join(", ", varDecls) + "; " + body + ")";
    }

    /**
     * Se {@code bodyPred} for {@code v = E} (directamente, ou um conjunto {@code Nary_Pred op='&'}
     * contendo exactamente um conjunto {@code v = E} nesse formato, com {@code v} ausente de
     * {@code E}), devolve {@code \let v = E; P} já traduzido, onde {@code P} é o resto do corpo
     * (ou {@code \true} se {@code v = E} for o único conjunto); senão {@code null} (cai no
     * {@code \exists} normal).
     */
    private static String tryExistsLetElimination(String varName, Element bodyPred, BxmlTranslateContext ctx) {
        List<Element> conjuncts = new ArrayList<>();
        if ("Nary_Pred".equals(bodyPred.getLocalName()) && "&".equals(bodyPred.getAttribute("op"))) {
            NodeList nl = bodyPred.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element e = (Element) n;
                if ("Attr".equals(e.getLocalName())) continue;
                conjuncts.add(e);
            }
        } else {
            conjuncts.add(bodyPred);
        }

        Element witnessExp = null;
        int witnessIndex = -1;
        for (int i = 0; i < conjuncts.size(); i++) {
            Element w = equalityDefiningVar(conjuncts.get(i), varName);
            if (w != null) {
                witnessExp = w;
                witnessIndex = i;
                break;
            }
        }
        if (witnessExp == null) return null;

        String witness = BxmlExpressionToAcsl.translate(witnessExp, ctx);
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < conjuncts.size(); i++) {
            if (i == witnessIndex) continue;
            rest.add(translatePred(conjuncts.get(i), ctx));
        }
        String p = rest.isEmpty() ? "\\true" : String.join(" && ", rest);
        return "(\\let " + varName + " = " + witness + "; " + p + ")";
    }

    /**
     * Se {@code pred} for {@code Exp_Comparison op='='} com um dos lados exactamente
     * {@code Id(varName)} e {@code varName} não ocorrer no outro lado, devolve o outro lado
     * (a definição/testemunha {@code E}); senão {@code null}.
     */
    private static Element equalityDefiningVar(Element pred, String varName) {
        if (pred == null || !"Exp_Comparison".equals(pred.getLocalName())) return null;
        if (!"=".equals(normalizeExpComparisonOp(pred.getAttribute("op")))) return null;
        Element[] pair = BxmlExpressionToAcsl.twoDirectExpChildren(pred);
        if (pair[0] == null || pair[1] == null) return null;
        if ("Id".equals(pair[0].getLocalName())
                && varName.equals(pair[0].getAttribute("value"))
                && !containsIdValue(pair[1], varName)) {
            return pair[1];
        }
        if ("Id".equals(pair[1].getLocalName())
                && varName.equals(pair[1].getAttribute("value"))
                && !containsIdValue(pair[0], varName)) {
            return pair[0];
        }
        return null;
    }

    /** {@code true} se algum nó {@code Id} na sub-árvore de {@code e} tiver o valor {@code varName}. */
    private static boolean containsIdValue(Element e, String varName) {
        if ("Id".equals(e.getLocalName()) && varName.equals(e.getAttribute("value"))) {
            return true;
        }
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            if (containsIdValue((Element) n, varName)) return true;
        }
        return false;
    }

    private static Element childByName(Element parent, String localName) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (localName.equals(e.getLocalName())) return e;
        }
        return null;
    }

    /** Predicado completo de um {@code <Body>} de operação (ex.: dentro de {@code Quantified_Set}). */
    public static String translateBodyPredicate(Element body, BxmlTranslateContext ctx) {
        Element p = BxmlDomUtils.firstPredChild(body);
        if (p == null) return "";
        return translatePred(p, ctx);
    }

}
