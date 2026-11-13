package com.example.bxml;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.w3c.dom.Element;

/**
 * Saídas de uma chamada {@code pp <-- op(a)} no corpo de uma operação abstrata: quando o corpo de
 * {@code op} (máquina vista ou importada) é {@code pp := E}, a chamada vira a cláusula {@code pp ==
 * E[a/x]} do contrato do chamador (ex. Customer.buy: {@code *pp == function_apply(price, gg)}).
 *
 * <p>Só vale enquanto {@link #bind} estiver ativo, o que o {@code AcslGenerator} faz durante a
 * tradução das operações do contrato REAL. O texto ghost ({@code ghost_operations.ci}) é traduzido
 * fora desse intervalo e não recebe a cláusula: a função ghost não tem corpo e o WP assume o seu
 * {@code ensures} — se ela afirmasse {@code *pp == ...}, a cláusula do contrato real seria provada
 * por essa suposição em vez de pelo C.
 *
 * <p>Escopo conservador, igual ao do B2ACSLRules: o corpo da operação chamada tem de ser uma
 * atribuição só às saídas, e {@code E} só pode citar entradas formais, constantes da máquina chamada
 * e variáveis dela que o chamador enxerga. Fora disso a chamada continua sem cláusula.
 */
public final class OperationCallOutputSpecs {

    private static Path bxmlDirectory;
    private static List<String> machineNames = List.of();
    private static int depth;

    private OperationCallOutputSpecs() {}

    /** Ativa a resolução para as máquinas {@code names} (vistas e importadas, transitivas). */
    public static void bind(Path directory, List<String> names) {
        bxmlDirectory = directory;
        machineNames = names == null ? List.of() : List.copyOf(names);
    }

    public static void unbind() {
        bxmlDirectory = null;
        machineNames = List.of();
    }

    /**
     * Cláusulas {@code saídaReal == E[a/x]} da chamada {@code call}, nos nomes do chamador (sem o
     * {@code *} das saídas: o pós-passe do contrato já o põe). Lista vazia se a resolução não estiver
     * ativa ou a chamada não tiver a forma aceita.
     */
    static List<String> outputClauses(Element call, BxmlTranslateContext callerCtx) {
        if (bxmlDirectory == null || call == null || callerCtx == null || depth > 0) {
            return List.of();
        }
        depth++;
        try {
            return resolve(call, callerCtx);
        } catch (RuntimeException e) {
            return List.of();
        } finally {
            depth--;
        }
    }

    private static List<String> resolve(Element call, BxmlTranslateContext callerCtx) {
        String opName = callName(call);
        if (opName == null) return List.of();
        List<Element> actualIns = paramExps(call, "Input_Parameters");
        List<Element> actualOuts = paramExps(call, "Output_Parameters");
        for (String m : machineNames) {
            Element calleeMachine = BxmlSetsTranslator.findAbstractMachineElement(m, bxmlDirectory);
            if (calleeMachine == null) continue;
            Element op = findOperation(calleeMachine, opName);
            if (op == null) continue;
            return clausesFor(op, calleeMachine, actualIns, actualOuts, callerCtx);
        }
        return List.of();
    }

    private static List<String> clausesFor(
            Element op, Element calleeMachine, List<Element> actualIns, List<Element> actualOuts,
            BxmlTranslateContext callerCtx) {
        List<String> formalIns = idValues(paramExps(op, "Input_Parameters"));
        List<String> formalOuts = idValues(paramExps(op, "Output_Parameters"));
        if (formalIns.size() != actualIns.size() || formalOuts.size() != actualOuts.size()) return List.of();
        for (Element a : actualOuts) {
            if (!"Id".equals(a.getLocalName())) return List.of();
        }
        Element assign = outputAssignment(op);
        if (assign == null) return List.of();
        List<Element> vars = BxmlDomUtils.directExpChildren(BxmlDomUtils.firstChildElement(assign, "Variables"));
        List<Element> vals = BxmlDomUtils.directExpChildren(BxmlDomUtils.firstChildElement(assign, "Values"));
        if (vars.isEmpty() || vars.size() != vals.size()) return List.of();

        Set<String> visible = visibleNames(calleeMachine, formalIns);
        BxmlTranslateContext calleeCtx =
                BxmlTranslateContext.forMachine(calleeMachine)
                        .withEnumRenames(callerCtx.enumValueRenames())
                        .withEnumeratedSetRenames(callerCtx.enumeratedSetRenames())
                        .withEnumeratedSetNames(callerCtx.enumeratedSetNames());
        Map<String, String> actualByFormal = new LinkedHashMap<>();
        for (int i = 0; i < formalIns.size(); i++) {
            String t = BxmlExpressionToAcsl.translate(actualIns.get(i), callerCtx);
            actualByFormal.put(formalIns.get(i), "Id".equals(actualIns.get(i).getLocalName()) ? t : "(" + t + ")");
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < vars.size(); i++) {
            Element v = vars.get(i);
            if (!"Id".equals(v.getLocalName())) return List.of();
            int k = formalOuts.indexOf(v.getAttribute("value"));
            if (k < 0 || !rewritable(vals.get(i), visible)) return List.of();
            String lhs = actualOuts.get(k).getAttribute("value");
            String rhs = substitute(BxmlExpressionToAcsl.translate(vals.get(i), calleeCtx), actualByFormal);
            out.add(BxmlExpressionToAcsl.isSetValued(vals.get(i), calleeCtx)
                    ? "equals(" + lhs + ", " + rhs + ")"
                    : lhs + " == " + rhs);
        }
        return out;
    }

    /** {@code x := E} (ou {@code BEGIN x := E END}) só sobre as saídas; {@code null} noutra forma. */
    private static Element outputAssignment(Element op) {
        Element body = BxmlDomUtils.firstChildElement(op, "Body");
        Element sub = body == null ? null : BxmlDomUtils.firstSubChild(body);
        while (sub != null && "Bloc_Sub".equals(sub.getLocalName())) {
            sub = BxmlDomUtils.firstSubChild(sub);
        }
        return sub != null && "Assignement_Sub".equals(sub.getLocalName()) ? sub : null;
    }

    /**
     * Nomes que {@code E} pode citar: entradas formais e as constantes, conjuntos e variáveis da
     * máquina chamada. Ela é vista ou importada pelo chamador, cujo {@code .acsl} inclui o dela, onde
     * esses nomes são declarados (ex. {@code price}, já usado nos {@code requires} de Customer.buy).
     */
    private static Set<String> visibleNames(Element calleeMachine, List<String> formalIns) {
        Set<String> out = new LinkedHashSet<>(formalIns);
        out.addAll(BxmlConstantsAndProperties.abstractConstantNames(calleeMachine));
        out.addAll(BxmlConstantsAndProperties.concreteConstantNames(calleeMachine));
        out.addAll(BxmlSetsTranslator.declaredSetNames(calleeMachine));
        out.addAll(BxmlMachineVariables.declaredVariableNames(calleeMachine));
        return out;
    }

    /** Só identificadores visíveis, literais e operações binárias/unárias sobre eles. */
    private static boolean rewritable(Element e, Set<String> visible) {
        switch (e.getLocalName()) {
            case "Id":
                return visible.contains(e.getAttribute("value"));
            case "Integer_Literal", "Boolean_Literal":
                return true;
            case "Binary_Exp", "Unary_Exp":
                for (Element ch : BxmlDomUtils.directExpChildren(e)) {
                    if (!rewritable(ch, visible)) return false;
                }
                return true;
            default:
                return false;
        }
    }

    /** Troca cada entrada formal pelo argumento real, de uma vez só (sem encadear substituições). */
    private static String substitute(String text, Map<String, String> actualByFormal) {
        if (actualByFormal.isEmpty()) return text;
        StringBuilder alt = new StringBuilder();
        for (String f : actualByFormal.keySet()) {
            if (alt.length() > 0) alt.append('|');
            alt.append(Pattern.quote(f));
        }
        Matcher m = Pattern.compile("(?<![\\w$])(" + alt + ")(?![\\w$])").matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(actualByFormal.get(m.group(1))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String callName(Element call) {
        Element name = BxmlDomUtils.firstChildElement(call, "Name");
        Element id = name == null ? null : BxmlDomUtils.firstChildElement(name, "Id");
        String v = id == null ? null : id.getAttribute("value");
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static List<Element> paramExps(Element el, String clause) {
        Element c = BxmlDomUtils.firstChildElement(el, clause);
        return c == null ? List.of() : BxmlDomUtils.directExpChildren(c);
    }

    private static List<String> idValues(List<Element> ids) {
        List<String> out = new ArrayList<>();
        for (Element e : ids) {
            out.add("Id".equals(e.getLocalName()) ? e.getAttribute("value") : "");
        }
        return out;
    }

    private static Element findOperation(Element machine, String name) {
        Element ops = BxmlDomUtils.firstChildElement(machine, "Operations");
        if (ops == null) return null;
        org.w3c.dom.NodeList nl = ops.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i) instanceof Element op
                    && "Operation".equals(op.getLocalName())
                    && name.equals(op.getAttribute("name"))) {
                return op;
            }
        }
        return null;
    }
}
