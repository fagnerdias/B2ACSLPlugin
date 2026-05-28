package com.example.bxml;

import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Traduz a cláusula {@code <Initialisation>} (BXML 1.0) para contratos ACSL, usando funções da
 * biblioteca {@code ACSL_Lib} em resources (ex.: {@code empty}, {@code set_union}, {@code singleton}).
 *
 * <p>{@code <Initialisation>} contém exatamente uma substituição ({@code Sub}); em particular
 * {@code Assignement_Sub} representa {@code v := E}, e {@code Nary_Sub} com {@code op=";"} sequencia
 * substituições.
 *
 * @see <a href="https://www.atelierb.eu/wp-content/uploads/2023/10/bxml-1.0.html">BXML 1.0 — Initialisation</a>
 */
public final class BxmlInitialisationTranslator {

    private BxmlInitialisationTranslator() {}

    /**
     * Extrai {@code ensures} a partir do corpo de uma operação ({@code <Body>}), reutilizando a mesma tradução
     * de substituições que em {@code Initialisation}.
     */
    public static void appendEnsuresFromBody(Element body, List<String> ensures, BxmlTranslateContext ctx) {
        Element sub = firstSubChild(body);
        walkSubstitution(sub, ensures, ctx);
    }

    /**
     * @param machineEl elemento raiz {@code <Machine>}
     * @param additionalAssignTargets nomes para {@code assigns} (ex.: {@code NomeAbstrata__v} a partir
     *        de {@code Concrete_Variables} das máquinas de implementação fundidas)
     */
    public static InitialisationAcsl translate(
            Element machineEl, List<String> additionalAssignTargets, BxmlTranslateContext ctx) {
        String machineName = machineEl.getAttribute("name");

        List<String> ensures = new ArrayList<>();
        Element init = firstChildElement(machineEl, "Initialisation");
        if (init != null) {
            walkSubstitution(firstSubChild(init), ensures, ctx);
        }

        String functionName = machineName + "__INITIALISATION";
        return new InitialisationAcsl(
                functionName, ensures, new ArrayList<>(additionalAssignTargets), false, List.of());
    }

    private static void walkSubstitution(Element sub, List<String> ensures, BxmlTranslateContext ctx) {
        if (sub == null) return;
        String ln = sub.getLocalName();
        switch (ln) {
            case "Assignement_Sub" -> parseAssignementSub(sub, ensures, ctx);
            case "Nary_Sub" -> {
                String op = sub.getAttribute("op");
                if (";".equals(op)) {
                    NodeList children = sub.getChildNodes();
                    for (int i = 0; i < children.getLength(); i++) {
                        Node n = children.item(i);
                        if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                        Element ch = (Element) n;
                        if ("Attr".equals(ch.getLocalName())) continue;
                        walkSubstitution(ch, ensures, ctx);
                    }
                } else if ("||".equals(op)) {
                    // simultâneo: um ensures conjuntivo
                    parseSimultaneous(sub, ensures, ctx);
                } else {
                    walkSubstitution(firstSubChild(sub), ensures, ctx);
                }
            }
            case "Skip" -> { /* nada */ }
            case "Bloc_Sub" -> walkSubstitution(firstSubChild(sub), ensures, ctx);
            case "ANY_Sub" -> {
                /* Tratado como contrato ghost por GhostOperationsCiGenerator; nada a emitir aqui. */
            }
            default -> { /* outras substituições: extensão futura */ }
        }
    }

    /**
     * Procura {@code ANY_Sub} no topo de um {@code <Body>} (atravessando {@code Bloc_Sub}); útil para o
     * gerador de operações ghost decidir se a operação tem contrato ghost derivado de {@code ANY_Sub}.
     */
    public static Element findTopLevelAnySub(Element body) {
        if (body == null) return null;
        Element sub = firstSubChild(body);
        while (sub != null && "Bloc_Sub".equals(sub.getLocalName())) {
            sub = firstSubChild(sub);
        }
        if (sub != null && "ANY_Sub".equals(sub.getLocalName())) {
            return sub;
        }
        return null;
    }

    /**
     * {@code ANY v WHERE P THEN Q} → {@code \forall T v; P ==> (tradução de Q)} (uma única cláusula).
     *
     * <p>A tradução das atribuições em {@code Then} reusa {@link #walkSubstitution} (mesmas regras
     * que {@code Initialisation} / corpos de operação).
     */
    public static String translateAnySubAsForall(Element anySub, BxmlTranslateContext ctx) {
        if (anySub == null) return "";
        Element vars = firstChildElement(anySub, "Variables");
        Element predEl = firstChildElement(anySub, "Pred");
        Element thenEl = firstChildElement(anySub, "Then");
        if (vars == null || predEl == null || thenEl == null) {
            return "";
        }
        String p = BxmlPredicateToAcsl.translateInvariantContent(predEl, ctx).trim();
        if (p.isBlank()) {
            p = "\\true";
        }
        List<Element> varIds = directExpChildren(vars);
        List<String> binders = new ArrayList<>();
        for (Element vid : varIds) {
            if (!"Id".equals(vid.getLocalName())) {
                continue;
            }
            String vn = vid.getAttribute("value").trim();
            if (vn.isBlank()) {
                continue;
            }
            String ty = BxmlPredicateToAcsl.acslQuantifierLogicTypeForAnyVariable(predEl, vid, ctx);
            binders.add(ty + " " + vn);
        }
        if (binders.isEmpty()) {
            return "";
        }
        List<String> inner = new ArrayList<>();
        walkSubstitution(firstSubChild(thenEl), inner, ctx);
        String q = inner.isEmpty() ? "\\true" : String.join(" && ", inner);
        return "\\forall " + String.join(", ", binders) + "; " + p + " ==> (" + q + ")";
    }

    private static void parseSimultaneous(Element narySub, List<String> ensures, BxmlTranslateContext ctx) {
        // Cada filho Sub é uma substituição paralela
        NodeList children = narySub.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element ch = (Element) n;
            if ("Attr".equals(ch.getLocalName())) continue;
            if ("Assignement_Sub".equals(ch.getLocalName())) {
                parseAssignementSub(ch, ensures, ctx);
            } else if ("If_Sub".equals(ch.getLocalName())) {
                parseIfSubAsConditionalEnsures(ch, ensures, ctx);
            }
        }
    }

    /**
     * {@code IF cond THEN body END} em corpo paralelo → uma cláusula {@code ensures} condicional
     * por efeito: {@code (cond) ==> (ensures_do_then)}.
     *
     * <p>Apenas o ramo {@code Then} é usado; o ramo {@code Else} (se existir) pode ser adicionado
     * simetricamente com {@code !(cond) ==> (ensures_do_else)}.
     */
    private static void parseIfSubAsConditionalEnsures(
            Element ifSub, List<String> ensures, BxmlTranslateContext ctx) {
        Element condEl = firstChildElement(ifSub, "Condition");
        Element thenEl = firstChildElement(ifSub, "Then");
        if (condEl == null || thenEl == null) return;

        String cond = BxmlPredicateToAcsl.translateBodyPredicate(condEl, ctx);
        if (cond == null || cond.isBlank()) return;

        List<String> thenEnsures = new ArrayList<>();
        walkSubstitution(firstSubChild(thenEl), thenEnsures, ctx);
        for (String e : thenEnsures) {
            ensures.add("(" + cond + ") ==> (" + e + ")");
        }
    }

    private static void parseAssignementSub(Element assign, List<String> ensures, BxmlTranslateContext ctx) {
        Element vars = firstChildElement(assign, "Variables");
        Element vals = firstChildElement(assign, "Values");
        if (vars == null || vals == null) return;
        List<Element> lhs = directExpChildren(vars);
        List<Element> rhs = directExpChildren(vals);
        int n = Math.min(lhs.size(), rhs.size());
        for (int i = 0; i < n; i++) {
            ensures.add(BxmlExpressionToAcsl.formatEquality(lhs.get(i), rhs.get(i), ctx));
        }
    }

    private static List<Element> directExpChildren(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            out.add(e);
        }
        return out;
    }

    private static Element firstSubChild(Element parent) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            return e;
        }
        return null;
    }

    private static Element firstChildElement(Element parent, String localName) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (localName.equals(e.getLocalName())) return e;
        }
        return null;
    }

    /**
     * Texto de contrato no estilo pedido (função + contract + ensures + assigns).
     */
    public record InitialisationAcsl(
            String functionName,
            List<String> ensures,
            List<String> assignsTargets,
            boolean includeGhostBehaviorAssert,
            /**
             * Sufixos de variável abstrata (ex. {@code ss}) para cláusulas {@code ensures dummy_ghost_<v>;}
             * em inicialização não pura face ao modelo ghost.
             */
            List<String> dummyGhostEnsureVarNames) {

        public InitialisationAcsl {
            dummyGhostEnsureVarNames =
                    dummyGhostEnsureVarNames == null ? List.of() : List.copyOf(dummyGhostEnsureVarNames);
        }

        public String toContractText() {
            StringBuilder sb = new StringBuilder();
            sb.append("function ").append(functionName).append(":\n");
            sb.append("contract:    \n");
            for (String e : ensures) {
                sb.append("    ensures  ").append(e).append(";\n");
            }
            for (String v : dummyGhostEnsureVarNames) {
                sb.append("    ensures  dummy_ghost_").append(v).append(";\n");
            }
            for (String a : assignsTargets) {
                sb.append("    assigns ").append(a).append(";\n");
            }
            if (includeGhostBehaviorAssert) {
                sb.append("    at 1: assert ghost__initialisation;\n");
            }
            return sb.toString();
        }
    }
}
