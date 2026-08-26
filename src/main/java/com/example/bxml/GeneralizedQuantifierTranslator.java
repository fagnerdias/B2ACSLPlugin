package com.example.bxml;

import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Tradução de quantificadores generalizados B ({@code SIGMA}/{@code PI}/{@code MIN}/{@code MAX},
 * {@code UNION}/{@code INTER}) e lambdas ({@code sequence builder}/mapa) para ACSL. Extraído de
 * {@code BxmlExpressionToAcsl} (WMC=498) por extract-class puro: nenhuma linha de lógica mudou,
 * só o arquivo em que vive.
 */
final class GeneralizedQuantifierTranslator {

    private GeneralizedQuantifierTranslator() {}

    /**
     * Nomes B que nunca são variáveis livres de uma lambda (conjuntos primitivos, literais, etc.).
     * Filtrados durante a detecção de variáveis livres em {@link #translateQuantifiedExp}.
     */
    private static final java.util.Set<String> B_NON_PARAM_IDS = java.util.Set.of(
            "NAT", "NAT1", "INTEGER", "INT", "BOOL", "REAL", "STRING",
            "TRUE", "FALSE", "MAXINT", "MININT");

    /**
     * B lambda {@code % x.(P | E)} → função lógica nomeada registada em {@link LambdaFunctionRegistry}.
     * Nunca {@code \lambda} nativo do ACSL (não compõe com o sistema de tipos B2ACSL baseado em ADTs).
     *
     * <p>Classifica o domínio {@code P} (ver {@link #intervalDomainBounds}) para escolher a forma:
     * <ul>
     *   <li>Uma só variável ligada com domínio {@code x : lo..hi} (intervalo literal) → {@code E} é
     *       o elemento de uma SEQUÊNCIA B — forma "sequence builder" ({@link
     *       #translateSequenceBuilderLambda}): função lógica recursiva sobre {@code \list}, com
     *       lemas-ponte {@code _length}/{@code _nth}.</li>
     *   <li>Caso contrário (múltiplas variáveis ligadas, ou domínio não é um intervalo literal) →
     *       modelo de MAPA ({@link #translateMapLambda}): função/predicado ponto-a-ponto, guardado
     *       pelo domínio quando este não é trivial.</li>
     * </ul>
     */
    static String translateQuantifiedExp(Element qe, BxmlTranslateContext ctx) {
        String type = qe.getAttribute("type");
        SigmaFunctionRegistry.Op generalizedOp = switch (type) {
            case "iSIGMA" -> SigmaFunctionRegistry.Op.SIGMA;
            case "iPI" -> SigmaFunctionRegistry.Op.PI;
            case "iMIN" -> SigmaFunctionRegistry.Op.MIN;
            case "iMAX" -> SigmaFunctionRegistry.Op.MAX;
            default -> null;
        };
        if (generalizedOp != null) {
            return translateGeneralizedQuantifiedExp(qe, generalizedOp, ctx);
        }
        UnionInterFunctionRegistry.Op setOp = switch (type) {
            case "UNION" -> UnionInterFunctionRegistry.Op.UNION;
            case "INTER" -> UnionInterFunctionRegistry.Op.INTER;
            default -> null;
        };
        if (setOp != null) {
            return translateUnionInterExp(qe, setOp, ctx);
        }
        if (!"%".equals(type)) {
            return "/* TODO: Quantified_Exp type=" + type + " */";
        }

        // 1. Variáveis ligadas pelo %
        Element varsEl = BxmlExpressionToAcsl.childByLocalName(qe, "Variables");
        java.util.List<String> boundVarNames = new java.util.ArrayList<>();
        if (varsEl != null) {
            NodeList nl = varsEl.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element idEl = (Element) n;
                if ("Attr".equals(idEl.getLocalName()) || !"Id".equals(idEl.getLocalName())) continue;
                boundVarNames.add(idEl.getAttribute("value"));
            }
        }
        if (boundVarNames.isEmpty()) return "/* TODO: lambda vars */";

        // 2. Guarda (domínio) do % — lado esquerdo do "|" em "% x.(P | E)". B trata % como função
        // PARCIAL: fora de P a lambda é indefinida, por isso a guarda tem de restringir a definição
        // gerada, não pode ser descartada.
        Element guardEl = BxmlExpressionToAcsl.childByLocalName(qe, "Pred");
        Element guardPred = guardEl != null ? BxmlExpressionToAcsl.firstExpChild(guardEl) : null;

        // 3. Corpo da lambda
        Element bodyEl = BxmlExpressionToAcsl.childByLocalName(qe, "Body");
        if (bodyEl == null) return "/* TODO: lambda body */";
        Element bodyExp = BxmlExpressionToAcsl.firstExpChild(bodyEl);
        if (bodyExp == null) return "/* TODO: lambda body */";

        // 4. Classificação: sequência (caso a/b) vs mapa (caso c) — ver javadoc.
        String[] intervalBounds = boundVarNames.size() == 1
                ? intervalDomainBounds(guardPred, boundVarNames.get(0), ctx)
                : null;
        if (intervalBounds != null) {
            return translateSequenceBuilderLambda(
                    boundVarNames.get(0), intervalBounds[0], intervalBounds[1], bodyExp, guardPred, ctx);
        }
        return translateMapLambda(boundVarNames, guardPred, bodyExp, ctx);
    }

    /**
     * Se {@code guardPred} for exactamente {@code boundVarName : lo..hi} ({@code Exp_Comparison
     * op=':'} com o lado direito um {@code Binary_Exp op='..'} literal), devolve {@code [lo, hi]}
     * já traduzidos para ACSL; senão {@code null} (domínio não é um intervalo simples — cai no
     * modelo de mapa). {@code lo} pode ser {@code 1} (caso "a" da classificação, sequência B
     * padrão) ou outro valor (caso "b", sequência reindexada).
     */
    private static String[] intervalDomainBounds(
            Element guardPred, String boundVarName, BxmlTranslateContext ctx) {
        if (guardPred == null || !"Exp_Comparison".equals(guardPred.getLocalName())) {
            return null;
        }
        if (!":".equals(guardPred.getAttribute("op"))) {
            return null;
        }
        Element[] pair = BxmlExpressionToAcsl.twoDirectExpChildren(guardPred);
        if (pair[0] == null || pair[1] == null) {
            return null;
        }
        if (!"Id".equals(pair[0].getLocalName()) || !boundVarName.equals(pair[0].getAttribute("value"))) {
            return null;
        }
        Element domain = pair[1];
        if (!BxmlExpressionToAcsl.isIntervalBinaryExp(domain)) {
            return null;
        }
        Element[] bounds = BxmlExpressionToAcsl.twoDirectExpChildren(domain);
        if (bounds[0] == null || bounds[1] == null) {
            return null;
        }
        String lo = BxmlExpressionToAcsl.translate(bounds[0], ctx);
        String hi = BxmlExpressionToAcsl.translate(bounds[1], ctx);
        if (lo == null || lo.isBlank() || hi == null || hi.isBlank()) {
            return null;
        }
        return new String[] {lo.trim(), hi.trim()};
    }

    // ══════════════ SIGMA / PI / MIN / MAX (Quantified_Exp type='iSIGMA'/'iPI'/'iMIN'/'iMAX') ══════════════

    private static String opBSyntax(SigmaFunctionRegistry.Op op) {
        return switch (op) {
            case SIGMA -> "SIGMA";
            case PI -> "PI";
            case MIN -> "MIN";
            case MAX -> "MAX";
        };
    }

    /** Classificação do domínio {@code D} de {@code OP(z).(z : D | E)} — ver {@link #classifyDomain}. */
    private enum DomainKind { INTERVAL, SET }

    private record DomainClassification(
            DomainKind kind, String lo, String hi, String filterTemplate, String domainExprText) {}

    /**
     * B {@code SIGMA/PI/MIN/MAX(z).(P | E)} → função lógica nomeada registada em {@link
     * SigmaFunctionRegistry}. Classifica {@code P} (ver {@link #classifyDomain}) para escolher entre
     * recursão em intervalo (com filtro opcional) e axiomatização sobre conjunto qualquer; duas
     * variáveis ligadas geram um par de funções aninhadas (ver {@link
     * #translateNestedGeneralizedSum}).
     */
    private static String translateGeneralizedQuantifiedExp(
            Element qe, SigmaFunctionRegistry.Op op, BxmlTranslateContext ctx) {
        Element varsEl = BxmlExpressionToAcsl.childByLocalName(qe, "Variables");
        java.util.List<String> boundVarNames = new java.util.ArrayList<>();
        java.util.List<Integer> boundVarTyprefs = new java.util.ArrayList<>();
        if (varsEl != null) {
            NodeList nl = varsEl.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element idEl = (Element) n;
                if (!"Id".equals(idEl.getLocalName())) continue;
                boundVarNames.add(idEl.getAttribute("value"));
                String tr = idEl.getAttribute("typref");
                boundVarTyprefs.add(tr.isBlank() ? -1 : Integer.parseInt(tr.trim()));
            }
        }
        if (boundVarNames.isEmpty()) {
            return "/* TODO(b2acsl): " + opBSyntax(op) + " sem variáveis ligadas */";
        }

        Element guardEl = BxmlExpressionToAcsl.childByLocalName(qe, "Pred");
        Element guardPred = guardEl != null ? BxmlExpressionToAcsl.firstExpChild(guardEl) : null;

        Element bodyEl = BxmlExpressionToAcsl.childByLocalName(qe, "Body");
        if (bodyEl == null) return "/* TODO(b2acsl): " + opBSyntax(op) + " sem corpo */";
        Element bodyExp = BxmlExpressionToAcsl.firstExpChild(bodyEl);
        if (bodyExp == null) return "/* TODO(b2acsl): " + opBSyntax(op) + " sem corpo */";

        SigmaFunctionRegistry registry = ctx.sigmaRegistry();
        if (registry == null) {
            return "/* TODO(b2acsl): " + opBSyntax(op) + " sem registro no contexto */";
        }

        if (boundVarNames.size() > 2) {
            return "/* TODO(b2acsl): " + opBSyntax(op) + " com mais de 2 variáveis ligadas não suportado */";
        }
        if (boundVarNames.size() == 2) {
            return translateNestedGeneralizedSum(
                    op, boundVarNames.get(0), boundVarNames.get(1), guardPred, bodyExp, ctx, registry);
        }

        String boundVarName = boundVarNames.get(0);
        DomainClassification dc = classifyDomain(guardPred, boundVarName, ctx);
        if (dc == null) {
            return "/* TODO(b2acsl): domínio de " + opBSyntax(op) + "(" + boundVarName + ") não reconhecido */";
        }
        String sourceComment = describeGeneralizedSum(op, boundVarName, guardPred, bodyExp, ctx);
        if (dc.kind() == DomainKind.SET) {
            return registerSetDomainSum(
                    op, boundVarName, boundVarTyprefs.get(0), dc, bodyExp, guardPred, sourceComment, ctx,
                    registry);
        }
        return registerIntervalDomainSum(op, boundVarName, dc, bodyExp, guardPred, sourceComment, ctx, registry);
    }

    /** Comentário {@code OP(z).(P | E)} (reconstruído do BXML) citando a construção B de origem. */
    private static String describeGeneralizedSum(
            SigmaFunctionRegistry.Op op, String boundVarNames, Element guardPred, Element bodyExp,
            BxmlTranslateContext ctx) {
        String predText;
        try {
            predText = guardPred != null ? BxmlPredicateToAcsl.translatePropertyPred(guardPred, ctx) : "?";
        } catch (RuntimeException ex) {
            predText = "?";
        }
        String bodyText;
        try {
            bodyText = BxmlExpressionToAcsl.translate(bodyExp, ctx);
        } catch (RuntimeException ex) {
            bodyText = "?";
        }
        return opBSyntax(op) + "(" + boundVarNames + ").(" + predText + " | " + bodyText + ")";
    }

    /** Predicados conjuntados por {@code Nary_Pred op='&'}, ou {@code [pred]} se não for uma conjunção. */
    private static java.util.List<Element> conjunctsOf(Element pred) {
        java.util.List<Element> out = new java.util.ArrayList<>();
        if (pred == null) return out;
        if ("Nary_Pred".equals(pred.getLocalName()) && "&".equals(pred.getAttribute("op"))) {
            NodeList nl = pred.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element e = (Element) n;
                if ("Attr".equals(e.getLocalName())) continue;
                out.add(e);
            }
            return out;
        }
        out.add(pred);
        return out;
    }

    /**
     * {@code z : S} com {@code S} um conjunto qualquer NÃO intervalo (caso (c)/(b) de §1) — {@code
     * null} se {@code c} não for dessa forma.
     */
    private static String membershipSetExpr(Element c, String boundVarName, BxmlTranslateContext ctx) {
        if (c == null || !"Exp_Comparison".equals(c.getLocalName()) || !":".equals(c.getAttribute("op"))) {
            return null;
        }
        Element[] pair = BxmlExpressionToAcsl.twoDirectExpChildren(c);
        if (pair[0] == null || pair[1] == null) return null;
        if (!"Id".equals(pair[0].getLocalName()) || !boundVarName.equals(pair[0].getAttribute("value"))) {
            return null;
        }
        if (BxmlExpressionToAcsl.isIntervalBinaryExp(pair[1])) return null;
        return BxmlExpressionToAcsl.translate(pair[1], ctx);
    }

    /**
     * Classifica o domínio {@code P} de {@code OP(z).(P | E)} — ver §1 da especificação:
     * <ul>
     *   <li>(a) {@code z : lo..hi} → {@link DomainKind#INTERVAL}, sem filtro.</li>
     *   <li>(b) {@code z : lo..hi & Q(z)} (ou {@code z:lo..hi & z:S}) → {@link DomainKind#INTERVAL},
     *       filtro = conjunção dos restantes conjuntos traduzida normalmente.</li>
     *   <li>(c) {@code z : S} (conjunto qualquer, ou interseção de vários {@code z:S_i}) → {@link
     *       DomainKind#SET}.</li>
     * </ul>
     * {@code null} se nenhuma forma reconhecida se aplicar (não improvisa — cai em TODO no chamador).
     */
    private static DomainClassification classifyDomain(
            Element guardPred, String boundVarName, BxmlTranslateContext ctx) {
        java.util.List<Element> conjuncts = conjunctsOf(guardPred);

        int intervalIdx = -1;
        String lo = null;
        String hi = null;
        for (int i = 0; i < conjuncts.size(); i++) {
            String[] bounds = intervalDomainBounds(conjuncts.get(i), boundVarName, ctx);
            if (bounds != null) {
                intervalIdx = i;
                lo = bounds[0];
                hi = bounds[1];
                break;
            }
        }
        if (intervalIdx >= 0) {
            java.util.List<Element> rest = new java.util.ArrayList<>(conjuncts);
            rest.remove(intervalIdx);
            if (rest.isEmpty()) {
                return new DomainClassification(DomainKind.INTERVAL, lo, hi, null, null);
            }
            java.util.List<String> parts = new java.util.ArrayList<>();
            for (Element r : rest) parts.add(BxmlPredicateToAcsl.translatePropertyPred(r, ctx));
            return new DomainClassification(DomainKind.INTERVAL, lo, hi, String.join(" && ", parts), null);
        }

        int setIdx = -1;
        String setExpr = null;
        for (int i = 0; i < conjuncts.size(); i++) {
            String s = membershipSetExpr(conjuncts.get(i), boundVarName, ctx);
            if (s != null) {
                setIdx = i;
                setExpr = s;
                break;
            }
        }
        if (setIdx < 0) return null;
        java.util.List<Element> rest = new java.util.ArrayList<>(conjuncts);
        rest.remove(setIdx);
        for (Element r : rest) {
            String extra = membershipSetExpr(r, boundVarName, ctx);
            if (extra == null) {
                // Conjunto extra não é 'z:S_i' — forma não coberta por §1; não improvisa.
                return null;
            }
            setExpr = "set_intersection(" + setExpr + ", " + extra + ")";
        }
        return new DomainClassification(DomainKind.SET, null, null, null, setExpr);
    }

    /**
     * Variáveis livres de {@code E}/{@code P} (§2): IDs de {@code scanRoots} menos ligadas/estado/
     * conjuntos. Pacote-visível: reutilizado por {@link BxmlComprehensionRegistry} para detectar
     * compreensões {@code { x | P }} cujo {@code P} referencia uma variável de um {@code ANY}/lambda
     * envolvente (não pode ser axiomatizada como constante global fechada nesse caso).
     */
    static java.util.List<String>[] freeVarsAndTypes(
            java.util.List<String> boundVarNames, BxmlTranslateContext ctx, Element... scanRoots) {
        java.util.LinkedHashSet<String> allIds = new java.util.LinkedHashSet<>();
        for (Element root : scanRoots) {
            if (root != null) collectIdValues(root, allIds);
        }
        allIds.removeAll(boundVarNames);
        allIds.removeAll(ctx.variableLogicTypes().keySet());
        allIds.removeAll(ctx.declaredSetNames());
        allIds.removeAll(B_NON_PARAM_IDS);
        java.util.List<String> freeVarNames = new java.util.ArrayList<>(allIds);
        java.util.List<String> freeVarTypes = new java.util.ArrayList<>();
        for (String v : freeVarNames) {
            String t = ctx.variableLogicTypes().get(v);
            if (t == null || t.isBlank()) t = ctx.crossMachineVariableLogicTypes().get(v);
            freeVarTypes.add(t == null || t.isBlank() ? "integer" : t);
        }
        @SuppressWarnings("unchecked")
        java.util.List<String>[] out = new java.util.List[] {freeVarNames, freeVarTypes};
        return out;
    }

    /** §2/§2b: domínio-intervalo — recursão (SIGMA/PI) ou declaração+axiomas guardados (MIN/MAX). */
    private static String registerIntervalDomainSum(
            SigmaFunctionRegistry.Op op, String boundVarName, DomainClassification dc, Element bodyExp,
            Element guardPred, String sourceComment, BxmlTranslateContext ctx, SigmaFunctionRegistry registry) {
        String bodyStr = BxmlExpressionToAcsl.translate(bodyExp, ctx);
        String bodyForLo = replaceWordBoundary(bodyStr, boundVarName, "lo");
        String bodyForHi = replaceWordBoundary(bodyStr, boundVarName, "hi");
        String filterForLo = dc.filterTemplate() != null
                ? replaceWordBoundary(dc.filterTemplate(), boundVarName, "lo") : null;
        String filterForHi = dc.filterTemplate() != null
                ? replaceWordBoundary(dc.filterTemplate(), boundVarName, "hi") : null;

        java.util.List<String>[] ft = freeVarsAndTypes(
                java.util.List.of(boundVarName), ctx, bodyExp, guardPred);
        java.util.List<String> freeVarNames = ft[0];
        java.util.List<String> freeVarTypes = ft[1];

        String[] congr = SigmaFunctionRegistry.detectCongrVar(bodyStr, freeVarNames);
        String congrVar = null;
        String congrType = null;
        if (congr != null) {
            congrVar = congr[0];
            int idx = freeVarNames.indexOf(congrVar);
            congrType = idx >= 0 && idx < freeVarTypes.size() ? freeVarTypes.get(idx) : "integer";
        }

        String name = registry.registerInterval(
                op, freeVarNames, freeVarTypes, sourceComment, bodyForLo, bodyForHi, filterForLo, filterForHi,
                congrVar, congrType);
        String freeArgs = freeVarNames.isEmpty() ? "" : String.join(", ", freeVarNames) + ", ";
        return name + "(" + freeArgs + dc.lo() + ", " + dc.hi() + ")";
    }

    /** §3: domínio-conjunto qualquer — axiomatização estrutural sobre {@code empty}/{@code singleton+union}. */
    private static String registerSetDomainSum(
            SigmaFunctionRegistry.Op op, String boundVarName, int boundVarTypref, DomainClassification dc,
            Element bodyExp, Element guardPred, String sourceComment, BxmlTranslateContext ctx,
            SigmaFunctionRegistry registry) {
        String bodyStr = BxmlExpressionToAcsl.translate(bodyExp, ctx);
        String bodyForX = replaceWordBoundary(bodyStr, boundVarName, "x");

        // NÃO escanear guardPred aqui: para DomainKind.SET, classifyDomain exige que TODOS os
        // conjuntos da guarda sejam da forma "z:S_i" (senão devolve null, cai em TODO) — logo a
        // guarda inteira já foi consumida em dc.domainExprText() (o argumento "dom" passado no
        // call site), sem sobrar nenhum filtro textual que entre no CORPO da função gerada. Incluir
        // guardPred aqui capturava o próprio identificador do domínio (ex.: FAM em "ss:FAM") como
        // um parâmetro livre espúrio — duplicado com o "dom" já passado — e o Frama-C rejeitava a
        // chamada gerada (ex. inter_func01(FAM, FAM)) com "incompatible types" (FAM : Set<Set<A>>
        // usado onde se esperava o tipo do parâmetro livre espúrio).
        java.util.List<String>[] ft = freeVarsAndTypes(
                java.util.List.of(boundVarName), ctx, bodyExp);
        java.util.List<String> freeVarNames = ft[0];
        java.util.List<String> freeVarTypes = ft[1];

        // acslVariableLogicTypeFromTypref (não acslLogicTypeForValueTypref): conjuntos B diferidos
        // (ex. ISLAND) não têm tipo ACSL próprio declarado — a máquina declara-os como Set<integer>
        // (ver RulerOfTheSeas_ISLAND), logo o elemento tem de ser 'integer', não 'island'.
        String boundVarType = ctx.types().acslVariableLogicTypeFromTypref(boundVarTypref);

        String name = registry.registerSet(
                op, freeVarNames, freeVarTypes, sourceComment, boundVarType, bodyForX, dc.domainExprText());
        String freeArgs = freeVarNames.isEmpty() ? "" : String.join(", ", freeVarNames) + ", ";
        return name + "(" + freeArgs + dc.domainExprText() + ")";
    }

    /**
     * §4: duas variáveis ligadas — gera uma função interna (indexada pelo valor corrente da externa,
     * parâmetro extra {@code x}) e uma externa que a chama, ambas via {@link
     * #registerIntervalDomainSum}-like recursão em {@link SigmaFunctionRegistry#registerInterval}.
     * Suporta apenas a forma mais simples: ambas as variáveis com domínio {@code lo..hi} literal, sem
     * filtro adicional (os limites da interna podem referenciar a externa) — qualquer outra forma cai
     * em TODO, não é improvisada.
     */
    private static String translateNestedGeneralizedSum(
            SigmaFunctionRegistry.Op op, String outerVar, String innerVar, Element guardPred, Element bodyExp,
            BxmlTranslateContext ctx, SigmaFunctionRegistry registry) {
        java.util.List<Element> conjuncts = conjunctsOf(guardPred);
        String[] outerBounds = null;
        String[] innerBounds = null;
        java.util.List<Element> rest = new java.util.ArrayList<>();
        for (Element c : conjuncts) {
            if (outerBounds == null) {
                String[] b = intervalDomainBounds(c, outerVar, ctx);
                if (b != null) {
                    outerBounds = b;
                    continue;
                }
            }
            if (innerBounds == null) {
                String[] b = intervalDomainBounds(c, innerVar, ctx);
                if (b != null) {
                    innerBounds = b;
                    continue;
                }
            }
            rest.add(c);
        }
        if (outerBounds == null || innerBounds == null || !rest.isEmpty()) {
            return "/* TODO(b2acsl): domínio de " + opBSyntax(op) + "(" + outerVar + "," + innerVar
                    + ") não reconhecido — suportado apenas '" + outerVar + ":lo..hi & " + innerVar
                    + ":lo..hi' (sem filtro extra) */";
        }

        String bodyStr = BxmlExpressionToAcsl.translate(bodyExp, ctx);
        java.util.List<String>[] ft = freeVarsAndTypes(
                java.util.List.of(outerVar, innerVar), ctx, bodyExp, guardPred);
        java.util.List<String> freeVarNames = ft[0];
        java.util.List<String> freeVarTypes = ft[1];
        String freeArgs = freeVarNames.isEmpty() ? "" : String.join(", ", freeVarNames) + ", ";

        // Interna: parâmetro extra "x" representa o valor corrente da variável externa.
        java.util.List<String> innerFreeNames = new java.util.ArrayList<>(freeVarNames);
        innerFreeNames.add("x");
        java.util.List<String> innerFreeTypes = new java.util.ArrayList<>(freeVarTypes);
        innerFreeTypes.add("integer");
        String innerBodyForLo =
                replaceWordBoundary(replaceWordBoundary(bodyStr, innerVar, "lo"), outerVar, "x");
        String innerBodyForHi =
                replaceWordBoundary(replaceWordBoundary(bodyStr, innerVar, "hi"), outerVar, "x");
        String innerComment = opBSyntax(op) + "(" + innerVar + ") interno de "
                + describeGeneralizedSum(op, outerVar + "," + innerVar, guardPred, bodyExp, ctx);
        String innerName = registry.registerInterval(
                op, innerFreeNames, innerFreeTypes, innerComment, innerBodyForLo, innerBodyForHi, null, null,
                null, null);

        String innerLoAtLo = replaceWordBoundary(innerBounds[0], outerVar, "lo");
        String innerHiAtLo = replaceWordBoundary(innerBounds[1], outerVar, "lo");
        String innerLoAtHi = replaceWordBoundary(innerBounds[0], outerVar, "hi");
        String innerHiAtHi = replaceWordBoundary(innerBounds[1], outerVar, "hi");
        String outerBodyForLo = innerName + "(" + freeArgs + "lo, " + innerLoAtLo + ", " + innerHiAtLo + ")";
        String outerBodyForHi = innerName + "(" + freeArgs + "hi, " + innerLoAtHi + ", " + innerHiAtHi + ")";

        String outerComment = describeGeneralizedSum(op, outerVar + "," + innerVar, guardPred, bodyExp, ctx);
        String outerName = registry.registerInterval(
                op, freeVarNames, freeVarTypes, outerComment, outerBodyForLo, outerBodyForHi, null, null,
                null, null);
        return outerName + "(" + freeArgs + outerBounds[0] + ", " + outerBounds[1] + ")";
    }

    // ══════════════ UNION / INTER (Quantified_Exp type='UNION'/'INTER') ══════════════

    private static String unionInterOpName(UnionInterFunctionRegistry.Op op) {
        return op == UnionInterFunctionRegistry.Op.UNION ? "UNION" : "INTER";
    }

    /**
     * B {@code UNION/INTER(z).(P | E)} → função lógica nomeada registada em {@link
     * UnionInterFunctionRegistry}. {@code E} tem de ser set-valued (verificado aqui); classifica o
     * domínio {@code P} reusando {@link #classifyDomain} (mesma lógica de {@code
     * translateGeneralizedQuantifiedExp}) para escolher entre recursão em intervalo e
     * axiomatização sobre conjunto qualquer.
     */
    private static String translateUnionInterExp(
            Element qe, UnionInterFunctionRegistry.Op op, BxmlTranslateContext ctx) {
        Element varsEl = BxmlExpressionToAcsl.childByLocalName(qe, "Variables");
        java.util.List<String> boundVarNames = new java.util.ArrayList<>();
        java.util.List<Integer> boundVarTyprefs = new java.util.ArrayList<>();
        if (varsEl != null) {
            NodeList nl = varsEl.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element idEl = (Element) n;
                if (!"Id".equals(idEl.getLocalName())) continue;
                boundVarNames.add(idEl.getAttribute("value"));
                String tr = idEl.getAttribute("typref");
                boundVarTyprefs.add(tr.isBlank() ? -1 : Integer.parseInt(tr.trim()));
            }
        }
        if (boundVarNames.isEmpty()) {
            return "/* TODO(b2acsl): " + unionInterOpName(op) + " sem variáveis ligadas */";
        }

        Element guardEl = BxmlExpressionToAcsl.childByLocalName(qe, "Pred");
        Element guardPred = guardEl != null ? BxmlExpressionToAcsl.firstExpChild(guardEl) : null;

        Element bodyEl = BxmlExpressionToAcsl.childByLocalName(qe, "Body");
        if (bodyEl == null) return "/* TODO(b2acsl): " + unionInterOpName(op) + " sem corpo */";
        Element bodyExp = BxmlExpressionToAcsl.firstExpChild(bodyEl);
        if (bodyExp == null) return "/* TODO(b2acsl): " + unionInterOpName(op) + " sem corpo */";

        // isSetValued (genérico) só reconhece operadores ESTRUTURAIS de conjunto (união/interseção/
        // …), não uma aplicação funcional cujo CODOMÍNIO é um conjunto (ex.: player_islands(pp) :
        // Set<integer>, via player_islands : Relation<integer, Set<integer>>) — por isso a
        // verificação aqui é pelo TIPO (typref do próprio corpo), não pela forma estrutural.
        if (!isUnionInterBodySetValued(bodyExp, ctx)) {
            return "/* TODO(b2acsl): " + unionInterOpName(op)
                    + " com corpo não set-valued — construção B mal tipada, não improvisado */";
        }

        UnionInterFunctionRegistry registry = ctx.unionInterRegistry();
        if (registry == null) {
            return "/* TODO(b2acsl): " + unionInterOpName(op) + " sem registro no contexto */";
        }

        if (boundVarNames.size() > 1) {
            return "/* TODO(b2acsl): " + unionInterOpName(op)
                    + " com mais de 1 variável ligada não suportado */";
        }

        String boundVarName = boundVarNames.get(0);
        DomainClassification dc = classifyDomain(guardPred, boundVarName, ctx);
        if (dc == null) {
            return "/* TODO(b2acsl): domínio de " + unionInterOpName(op) + "(" + boundVarName
                    + ") não reconhecido */";
        }
        String elementType = elementTypeOfSetValuedExp(bodyExp, ctx);
        String sourceComment = describeUnionInter(op, boundVarName, guardPred, bodyExp, ctx);

        if (dc.kind() == DomainKind.SET) {
            return registerUnionInterSetDomain(
                    op, boundVarName, boundVarTyprefs.get(0), dc, bodyExp, guardPred, elementType,
                    sourceComment, ctx, registry);
        }
        return registerUnionInterIntervalDomain(
                op, boundVarName, dc, bodyExp, guardPred, elementType, sourceComment, ctx, registry);
    }

    /** Comentário {@code OP(z).(P | E)} citando a construção B de origem — ver {@link #describeGeneralizedSum}. */
    private static String describeUnionInter(
            UnionInterFunctionRegistry.Op op, String boundVarNames, Element guardPred, Element bodyExp,
            BxmlTranslateContext ctx) {
        String predText;
        try {
            predText = guardPred != null ? BxmlPredicateToAcsl.translatePropertyPred(guardPred, ctx) : "?";
        } catch (RuntimeException ex) {
            predText = "?";
        }
        String bodyText;
        try {
            bodyText = BxmlExpressionToAcsl.translate(bodyExp, ctx);
        } catch (RuntimeException ex) {
            bodyText = "?";
        }
        return unionInterOpName(op) + "(" + boundVarNames + ").(" + predText + " | " + bodyText + ")";
    }

    /**
     * {@code true} se o TIPO de {@code exp} (via o seu próprio {@code typref}) for {@code Set<...>}
     * — ao contrário de {@link #isSetValued}, que só reconhece operadores ESTRUTURAIS de conjunto
     * (união/interseção/…), esta verificação cobre também uma aplicação funcional cujo CODOMÍNIO é
     * um conjunto (ex.: {@code player_islands(pp)}, via {@code player_islands :
     * Relation<integer, Set<integer>>}), o caso mais comum de corpo de {@code UNION}/{@code INTER}.
     */
    private static boolean isUnionInterBodySetValued(Element exp, BxmlTranslateContext ctx) {
        String tr = exp.getAttribute("typref");
        if (tr.isBlank()) return false;
        int typref = Integer.parseInt(tr.trim());
        String full = ctx.types().acslVariableLogicTypeFromTypref(typref);
        return full != null && full.startsWith("Set<") && full.endsWith(">");
    }

    /**
     * Tipo ACSL do elemento de uma expressão set-valued (o {@code T} de {@code Set<T>}), a partir do
     * seu próprio {@code typref} (representa {@code POW(X)}) — {@link
     * BxmlTypeRegistry#acslVariableLogicTypeFromTypref} já resolve {@code POW(X)} para {@code
     * Set<...>}; extrai-se aqui só o elemento.
     */
    private static String elementTypeOfSetValuedExp(Element exp, BxmlTranslateContext ctx) {
        String tr = exp.getAttribute("typref");
        int typref = tr.isBlank() ? -1 : Integer.parseInt(tr.trim());
        String full = typref >= 0 ? ctx.types().acslVariableLogicTypeFromTypref(typref) : "Set<integer>";
        if (full.startsWith("Set<") && full.endsWith(">")) {
            return full.substring(4, full.length() - 1).trim();
        }
        return "integer";
    }

    /**
     * Nome B cru do elemento de uma expressão set-valued (ex. {@code ISLAND}, não {@code integer}) —
     * para procurar um universo tipado {@code U_T} em {@link BxmlTranslateContext#enumeratedSetRenames()}
     * (só tem entradas para conjuntos diferidos/enumerados da máquina, nunca para {@code INTEGER}/
     * {@code NAT}/{@code BOOL} — por isso a procura falha "naturalmente" para tipos sem universo finito).
     */
    private static String rawElementNameOfSetValuedExp(Element exp, BxmlTranslateContext ctx) {
        String tr = exp.getAttribute("typref");
        if (tr.isBlank()) return null;
        int typref = Integer.parseInt(tr.trim());
        return ctx.types().elementTypeNameForSetTypref(typref);
    }

    /** §2/§2b/§3/§3b: domínio-intervalo. */
    private static String registerUnionInterIntervalDomain(
            UnionInterFunctionRegistry.Op op, String boundVarName, DomainClassification dc, Element bodyExp,
            Element guardPred, String elementType, String sourceComment, BxmlTranslateContext ctx,
            UnionInterFunctionRegistry registry) {
        String bodyStr = BxmlExpressionToAcsl.translate(bodyExp, ctx);
        String bodyForLo = replaceWordBoundary(bodyStr, boundVarName, "lo");
        String bodyForHi = replaceWordBoundary(bodyStr, boundVarName, "hi");
        String filterForLo = dc.filterTemplate() != null
                ? replaceWordBoundary(dc.filterTemplate(), boundVarName, "lo") : null;
        String filterForHi = dc.filterTemplate() != null
                ? replaceWordBoundary(dc.filterTemplate(), boundVarName, "hi") : null;

        java.util.List<String>[] ft = freeVarsAndTypes(
                java.util.List.of(boundVarName), ctx, bodyExp, guardPred);
        java.util.List<String> freeVarNames = ft[0];
        java.util.List<String> freeVarTypes = ft[1];

        boolean hasUniverse = false;
        String universeExpr = null;
        if (op == UnionInterFunctionRegistry.Op.INTER) {
            String rawElem = rawElementNameOfSetValuedExp(bodyExp, ctx);
            String qualified = rawElem != null ? ctx.enumeratedSetRenames().get(rawElem) : null;
            if (qualified != null) {
                hasUniverse = true;
                universeExpr = qualified;
            }
        }

        String[] congr = UnionInterFunctionRegistry.detectCongrVar(bodyStr, freeVarNames);
        String congrVar = null;
        String congrType = null;
        if (congr != null) {
            congrVar = congr[0];
            int idx = freeVarNames.indexOf(congrVar);
            congrType = idx >= 0 && idx < freeVarTypes.size() ? freeVarTypes.get(idx) : "integer";
        }

        // INTER sem universo tipado E com filtro: UnionInterFunctionRegistry#appendIntervalEntry
        // (chamado bem mais tarde, ao formatar o bloco axiomático a partir dos Entry acumulados) não
        // gera declaração nenhuma para este caso — só um comentário TODO (ver §3b). Se
        // registerInterval fosse chamado mesmo assim, o nome alocado (ex. interNN) já ficaria
        // embutido AQUI na expressão ACSL antes dessa decisão, produzindo uma referência pendurada
        // a um símbolo nunca declarado ("unbound logic function" no -acsl-import, sem pista da causa
        // real). Verificar a MESMA condição aqui, antes de alocar o nome, evita o símbolo pendurado —
        // troca por um TODO visível, igual ao resto deste ficheiro para formas não suportadas.
        if (op == UnionInterFunctionRegistry.Op.INTER && !hasUniverse && filterForLo != null) {
            return "/* TODO(b2acsl): INTER com filtro e sem universo tipado — não suportado (ver "
                    + "UnionInterFunctionRegistry#appendIntervalEntry §3b) */";
        }
        String name = registry.registerInterval(
                op, freeVarNames, freeVarTypes, sourceComment, elementType, bodyForLo, bodyForHi,
                filterForLo, filterForHi, hasUniverse, universeExpr, congrVar, congrType);
        String freeArgs = freeVarNames.isEmpty() ? "" : String.join(", ", freeVarNames) + ", ";
        return name + "(" + freeArgs + dc.lo() + ", " + dc.hi() + ")";
    }

    /** §4: domínio-conjunto qualquer. */
    private static String registerUnionInterSetDomain(
            UnionInterFunctionRegistry.Op op, String boundVarName, int boundVarTypref, DomainClassification dc,
            Element bodyExp, Element guardPred, String elementType, String sourceComment,
            BxmlTranslateContext ctx, UnionInterFunctionRegistry registry) {
        String bodyStr = BxmlExpressionToAcsl.translate(bodyExp, ctx);
        String bodyForX = replaceWordBoundary(bodyStr, boundVarName, "x");

        // NÃO escanear guardPred — mesmo motivo de registerSetDomainSum (SIGMA): para
        // DomainKind.SET a guarda inteira já foi consumida em dc.domainExprText() (o "dom" passado
        // no call site), sem filtro residual que entre no corpo da função gerada. Escanear guardPred
        // capturava o próprio identificador do domínio (ex.: FAM em "ss:FAM") como parâmetro livre
        // espúrio, gerando chamadas mal tipadas como inter_func01(FAM, FAM) — Frama-C rejeitava com
        // "incompatible types".
        java.util.List<String>[] ft = freeVarsAndTypes(
                java.util.List.of(boundVarName), ctx, bodyExp);
        java.util.List<String> freeVarNames = ft[0];
        java.util.List<String> freeVarTypes = ft[1];

        // Tipo do elemento do DOMÍNIO (não de E) — para \forall x/Set<T> dom da axiomatização §4.
        // Mesma resolução que SIGMA usa (registerSetDomainSum): deferred sets não têm tipo ACSL
        // próprio declarado (só Set<integer>), por isso acslVariableLogicTypeFromTypref (não
        // acslLogicTypeForValueTypref) — cai corretamente em "integer" via o seu fallback.
        String boundVarType = ctx.types().acslVariableLogicTypeFromTypref(boundVarTypref);

        String name = registry.registerSet(
                op, freeVarNames, freeVarTypes, sourceComment, elementType, boundVarType, bodyForX,
                dc.domainExprText());
        String freeArgs = freeVarNames.isEmpty() ? "" : String.join(", ", freeVarNames) + ", ";
        return name + "(" + freeArgs + dc.domainExprText() + ")";
    }

    /**
     * Forma "sequence builder": {@code % x.(x : lo..hi | E)} → função lógica recursiva sobre
     * {@code \list}, registada em {@link LambdaFunctionRegistry#registerSequenceBuilder}. Ver
     * {@link #translateQuantifiedExp}.
     */
    private static String translateSequenceBuilderLambda(
            String boundVarName, String loExpr, String hiExpr, Element bodyExp, Element guardPred,
            BxmlTranslateContext ctx) {
        String bodyStr = BxmlExpressionToAcsl.translate(bodyExp, ctx);

        // Variáveis livres: IDs no corpo E na guarda (que contém lo/hi, ex. card(array)) que não
        // são a variável ligada nem estado da PRÓPRIA máquina (reads directo, sem parametrizar).
        // Ao contrário do modelo de mapa, aqui NÃO se exclui crossMachineVariableNames: a função
        // gerada é pura e genérica (nunca usa reads de variável global — ver
        // LambdaFunctionRegistry#registerSequenceBuilder), logo uma variável de OUTRA máquina (ex.
        // array, de VArray, importada por Fifo_i_2) tem de entrar como parâmetro tipado explícito.
        java.util.LinkedHashSet<String> allIds = new java.util.LinkedHashSet<>();
        collectIdValues(bodyExp, allIds);
        if (guardPred != null) collectIdValues(guardPred, allIds);
        allIds.remove(boundVarName);
        allIds.removeAll(ctx.variableLogicTypes().keySet());
        allIds.removeAll(ctx.declaredSetNames());
        allIds.removeAll(B_NON_PARAM_IDS);
        java.util.List<String> freeVarNames = new java.util.ArrayList<>(allIds);
        java.util.List<String> freeVarTypes = new java.util.ArrayList<>();
        for (String v : freeVarNames) {
            String t = ctx.crossMachineVariableLogicTypes().get(v);
            freeVarTypes.add(t == null || t.isBlank() ? "integer" : t);
        }

        String bodyForLo = replaceWordBoundary(bodyStr, boundVarName, "lo");
        String bodyForLoPlusK = replaceWordBoundary(bodyStr, boundVarName, "(lo + k)");

        LambdaFunctionRegistry registry = ctx.lambdaRegistry();
        if (registry == null) {
            return "/* TODO: sequence lambda sem registro no contexto */";
        }
        String name = registry.registerSequenceBuilder(
                freeVarNames, freeVarTypes, "integer", bodyForLo, bodyForLoPlusK);
        java.util.List<String> args = new java.util.ArrayList<>(freeVarNames);
        args.add(loExpr);
        args.add(hiExpr);
        return name + "(" + String.join(", ", args) + ")";
    }

    /**
     * Forma "mapa": {@code % x.(P | E)} com domínio não-intervalo, ou múltiplas variáveis ligadas —
     * função/predicado ponto-a-ponto registada em {@link LambdaFunctionRegistry#register}/{@link
     * LambdaFunctionRegistry#registerFunction}. Comportamento anterior a {@link
     * #translateSequenceBuilderLambda} existir; ver {@link #translateQuantifiedExp}.
     */
    private static String translateMapLambda(
            java.util.List<String> boundVarNames, Element guardPred, Element bodyExp,
            BxmlTranslateContext ctx) {
        String guardStr = guardPred != null ? BxmlPredicateToAcsl.translatePropertyPred(guardPred, ctx) : null;

        boolean isBooleanBody = "Boolean_Exp".equals(bodyExp.getLocalName());
        // % x.(P | {y|Q}): o corpo E é ele próprio uma compreensão — o valor por x é um CONJUNTO,
        // não escalar (ex. player_islands = %pp.(pp:players | {ii|ii:ISLAND&player_islands_i(pp,ii)
        // =TRUE})). BxmlExpressionToAcsl.translate(bodyExp,ctx) já produz a referência correta (parametrizada por x, ver
        // BxmlComprehensionRegistry) — só falta (a) não confundir a variável ligada PRÓPRIA da
        // compreensão (ii) com uma variável livre do % exterior, e (b) não fixar o tipo de retorno
        // em "integer" (só correto para o caso escalar).
        boolean isSetValuedBody = "Quantified_Set".equals(bodyExp.getLocalName());
        String bodyStr;
        Element scanRoot; // elemento cuja sub-árvore será varrida para IDs livres
        if (isBooleanBody) {
            Element innerPred = BxmlExpressionToAcsl.firstExpChild(bodyExp);
            if (innerPred == null) return "/* TODO: lambda body */";
            bodyStr = BxmlPredicateToAcsl.translatePropertyPred(innerPred, ctx);
            scanRoot = innerPred;
        } else {
            bodyStr = BxmlExpressionToAcsl.translate(bodyExp, ctx);
            scanRoot = bodyExp;
        }

        // Variáveis livres: IDs no corpo E na guarda que não são ligadas nem estado
        // abstracto/concreto (ex.: card(array) na guarda referencia array, tal como o corpo pode).
        java.util.Set<String> boundSet = new java.util.LinkedHashSet<>(boundVarNames);
        if (isSetValuedBody) {
            boundSet.addAll(quantifiedSetOwnBoundVarNames(bodyExp));
        }
        java.util.LinkedHashSet<String> allIds = new java.util.LinkedHashSet<>();
        collectIdValues(scanRoot, allIds);
        if (guardPred != null) collectIdValues(guardPred, allIds);
        allIds.removeAll(boundSet);
        allIds.removeAll(ctx.variableLogicTypes().keySet());
        allIds.removeAll(ctx.crossMachineVariableNames());
        allIds.removeAll(ctx.declaredSetNames());
        allIds.removeAll(B_NON_PARAM_IDS);
        java.util.List<String> freeVarNames = new java.util.ArrayList<>(allIds);
        java.util.List<String> freeVarTypes = new java.util.ArrayList<>();
        for (String v : freeVarNames) {
            String t = ctx.variableLogicTypes().get(v);
            if (t == null || t.isBlank()) t = ctx.crossMachineVariableLogicTypes().get(v);
            freeVarTypes.add(t == null || t.isBlank() ? "integer" : t);
        }

        LambdaFunctionRegistry registry = ctx.lambdaRegistry();
        if (registry != null) {
            String name;
            if (isBooleanBody) {
                name = registry.register(freeVarNames, freeVarTypes, boundVarNames, bodyStr, guardStr);
            } else {
                String returnType = isSetValuedBody ? setValuedLambdaReturnType(bodyExp, ctx) : "integer";
                name = registry.registerFunction(
                        freeVarNames, freeVarTypes, boundVarNames, bodyStr, returnType, guardStr);
            }
            java.util.List<String> args = new java.util.ArrayList<>(freeVarNames);
            args.addAll(boundVarNames);
            return name + "(" + String.join(", ", args) + ")";
        }
        // Fallback inline (sem registro disponível no contexto)
        java.util.List<String> decls = new java.util.ArrayList<>();
        for (String v : boundVarNames) decls.add("integer " + v);
        return "\\lambda " + String.join(", ", decls) + "; " + bodyStr;
    }

    /** Nomes das variáveis ligadas PRÓPRIAS de {@code { x | P }} (o seu próprio {@code Variables}). */
    private static java.util.List<String> quantifiedSetOwnBoundVarNames(Element quantifiedSet) {
        Element vars = BxmlExpressionToAcsl.childByLocalName(quantifiedSet, "Variables");
        java.util.List<String> names = new java.util.ArrayList<>();
        if (vars == null) return names;
        NodeList nl = vars.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Id".equals(e.getLocalName())) names.add(e.getAttribute("value"));
        }
        return names;
    }

    /** {@code Set<T>} para o corpo {@code { y | Q }} de um lambda "mapa" valorado-em-conjunto. */
    private static String setValuedLambdaReturnType(Element quantifiedSet, BxmlTranslateContext ctx) {
        String tr = quantifiedSet.getAttribute("typref");
        int typref = tr.isBlank() ? -1 : Integer.parseInt(tr.trim());
        String elem = ctx.types().elementTypeNameForSetTypref(typref);
        String inner = ctx.types().acslElementTypeName(elem);
        return "Set<" + inner + ">";
    }

    /**
     * {@code V = %x.(x:D | {y|Q})} — lambda "mapa" valorado-em-conjunto (ver {@link
     * #translateMapLambda}). Uma igualdade nua {@code V == lambda_funcNN(x)} não faz sentido: {@code
     * V} é a RELAÇÃO INTEIRA, {@code lambda_funcNN(x)} é só o valor NUM ponto {@code x} — a relação
     * correta é pontual, restrita ao domínio {@code D}:
     * {@code \forall Tx x; D ==> equals(function_apply(V,x), lambda_funcNN(x))}. Devolve {@code
     * null} se {@code lambdaEl} não tiver exatamente esta forma (chamador cai no caminho genérico —
     * ex. lambda "mapa" escalar comum, já correto sem este envolvimento).
     */
    static String setValuedMapLambdaPointwiseEquality(
            Element varEl, Element lambdaEl, BxmlTranslateContext ctx) {
        if (!"Quantified_Exp".equals(lambdaEl.getLocalName()) || !"%".equals(lambdaEl.getAttribute("type"))) {
            return null;
        }
        Element varsEl = BxmlExpressionToAcsl.childByLocalName(lambdaEl, "Variables");
        Element boundIdEl = null;
        int boundCount = 0;
        if (varsEl != null) {
            NodeList nl = varsEl.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element idEl = (Element) n;
                if (!"Id".equals(idEl.getLocalName())) continue;
                boundCount++;
                boundIdEl = idEl;
            }
        }
        if (boundCount != 1) return null; // só a forma de 1 variável ligada, por agora
        Element bodyEl = BxmlExpressionToAcsl.childByLocalName(lambdaEl, "Body");
        Element bodyExp = bodyEl != null ? BxmlExpressionToAcsl.firstExpChild(bodyEl) : null;
        if (bodyExp == null || !"Quantified_Set".equals(bodyExp.getLocalName())) {
            return null;
        }
        Element guardEl = BxmlExpressionToAcsl.childByLocalName(lambdaEl, "Pred");
        Element guardPred = guardEl != null ? BxmlExpressionToAcsl.firstExpChild(guardEl) : null;

        String boundVar = boundIdEl.getAttribute("value");
        String trAttr = boundIdEl.getAttribute("typref");
        int typref = trAttr.isBlank() ? -1 : Integer.parseInt(trAttr.trim());
        String acslT = ctx.types().acslLogicTypeForValueTypref(typref);

        String varStr = BxmlExpressionToAcsl.translate(varEl, ctx);
        // BxmlExpressionToAcsl.translate() regista o lambda em ctx.lambdaRegistry() e devolve a chamada já correta
        // (ex. "lambda_func01(pp)") — reaproveita translateMapLambda em vez de duplicar a lógica.
        String lambdaCall = BxmlExpressionToAcsl.translate(lambdaEl, ctx);
        String guardStr = guardPred != null ? BxmlPredicateToAcsl.translatePropertyPred(guardPred, ctx) : "\\true";

        return "(\\forall " + acslT + " " + boundVar + "; (" + guardStr + ") ==> "
                + "equals(function_apply(" + varStr + ", " + boundVar + "), " + lambdaCall + "))";
    }

    /** Substitui {@code name} por {@code replacement} em {@code text}, por fronteira de palavra. */
    private static String replaceWordBoundary(String text, String name, String replacement) {
        return text.replaceAll(
                "(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(name) + "(?![A-Za-z0-9_])",
                java.util.regex.Matcher.quoteReplacement(replacement));
    }

    /** Recolhe recursivamente todos os valores {@code value} de nós {@code Id}. */
    private static void collectIdValues(Element e, java.util.Set<String> out) {
        if ("Id".equals(e.getLocalName())) {
            String v = e.getAttribute("value");
            if (v != null && !v.isBlank()) out.add(v.trim());
        }
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            collectIdValues((Element) n, out);
        }
    }
}
