package com.example.bxml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Inferência de tipos C/ACSL para parâmetros e constantes ghost de {@link GhostOperationsCiGenerator}:
 * que tipo C um parâmetro/variável B ganha no protótipo {@code void op(...)} gerado, e o tipo
 * {@code logic} ACSL correspondente. Extraído de {@code GhostOperationsCiGenerator} (WMC=746, o
 * maior do projeto) por extract-class puro: nenhuma linha de lógica mudou, só o arquivo em que vive.
 */
final class GhostParamTypeResolver {

    private GhostParamTypeResolver() {}

    static Map<String, String> buildAbstractConstantDecls(
            Element machineEl,
            BxmlTranslateContext ctx,
            Map<String, List<String>> lambdaParams) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        Element block = BxmlDomUtils.firstChildElement(machineEl, "Abstract_Constants");
        if (block == null) return result;
        org.w3c.dom.NodeList ch = block.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            org.w3c.dom.Node n = ch.item(i);
            if (n.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName()) || !"Id".equals(e.getLocalName())) continue;
            String name = e.getAttribute("value");
            if (name == null || name.isBlank()) continue;
            name = name.trim();
            List<String> params = lambdaParams.get(name);
            if (params != null && !params.isEmpty()) {
                String paramStr = params.stream()
                        .map(v -> "integer " + v)
                        .collect(java.util.stream.Collectors.joining(", "));
                result.put(name, "logic boolean " + name + "(" + paramStr + ")");
            } else {
                String tr = e.getAttribute("typref");
                int typref = (tr == null || tr.isBlank()) ? -1 : Integer.parseInt(tr.trim());
                String logicType = ctx.types().acslVariableLogicTypeFromTypref(typref);
                result.put(name, "logic " + logicType + " " + name);
            }
        }
        return result;
    }

    /**
     * Como {@link #appendOutputParametersAsPointers(List, Element, boolean)}, com o tipo ENUM real
     * (ex. {@code RobustFifo__REPORT *}) quando aplicável — usado pelo {@code predicate ghost__<op>}
     * do bloco axiomático real (importado DEPOIS dos {@code .c}, onde o typedef já é visível).
     */
    static List<GhostOperationsCiGenerator.Param> appendOutputParametersAsPointers(
            List<GhostOperationsCiGenerator.Param> base, Element operation) {
        return appendOutputParametersAsPointers(base, operation, true);
    }

    /**
     * Acrescenta os {@code Output_Parameters} como ponteiros C ({@code int *<name>}, ou o tipo ENUM
     * real quando {@code useConcreteEnumTypes} e o parâmetro é de um conjunto ENUMERADO) à lista de
     * parâmetros (na ordem de declaração no BXML); usado por contratos ghost derivados de
     * {@code ANY_Sub} (e respetivo {@code predicate ghost__<op>}).
     *
     * @param useConcreteEnumTypes {@code false} para o {@code void <op>(...)} dummy dentro de {@code
     *     ghost_operations.ci}: esse ficheiro é analisado pelo Frama-C como front-end ISOLADO, ANTES
     *     de qualquer {@code .c} (ver {@link GhostOperationsCiGenerator#write}) — um typedef de enum como {@code
     *     RobustFifo__REPORT}, só definido dentro de {@code RobustFifo_i.c}, é literalmente
     *     desconhecido nesse ponto, e usá-lo aqui produz {@code syntax error ... before or at token:
     *     RobustFifo__REPORT} no pré-processamento do {@code .ci}. {@code true} para o {@code
     *     predicate ghost__<op>(...)} do bloco axiomático real (emitido no {@code .acsl}, importado
     *     DEPOIS de todos os {@code .c} — aí o typedef já é visível, e usar {@code int *} geraria
     *     "invalid implicit conversion" no local de chamada real ({@code assert ghost__op(…, rr)}).
     */
    static List<GhostOperationsCiGenerator.Param> appendOutputParametersAsPointers(
            List<GhostOperationsCiGenerator.Param> base, Element operation, boolean useConcreteEnumTypes) {
        List<GhostOperationsCiGenerator.Param> out = new ArrayList<>(base);
        if (operation == null) return out;
        Element outEl = BxmlDomUtils.firstChildElement(operation, "Output_Parameters");
        if (outEl == null) return out;
        NodeList ch = outEl.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            if (!"Id".equals(e.getLocalName())) continue;
            String name = e.getAttribute("value");
            if (name == null || name.isBlank()) continue;
            String cPtrType = cPointerTypeFromTypref(findAncestorMachine(operation), e);
            if (!useConcreteEnumTypes && !"int *".equals(cPtrType) && !"_Bool *".equals(cPtrType)) {
                cPtrType = "int *";
            }
            out.add(new GhostOperationsCiGenerator.Param(cPtrType, sanitizeCIdent(name.trim())));
        }
        return out;
    }

    /**
     * Tipo ponteiro C para um parâmetro de saída a partir do seu {@code typref}: BOOL → {@code _Bool *};
     * qualquer outro escalar/conjunto → {@code int *}.
     */
    private static String cPointerTypeFromTypref(Element machine, Element paramId) {
        if (machine == null) return "int *";
        String tr = paramId.getAttribute("typref");
        if (tr == null || tr.isBlank()) return "int *";
        try {
            int id = Integer.parseInt(tr.trim());
            BxmlTypeRegistry types = BxmlTypeRegistry.fromMachine(machine);
            String raw = types.getRawType(id);
            String rawTrim = raw == null ? "" : raw.trim();
            if ("BOOL".equals(rawTrim)) {
                return "_Bool *";
            }
            // Conjunto ENUMERADO declarado nesta máquina (ex.: "REPORT" em RobustFifo.bxml, com
            // Enumerated_Values ok/failed): o C real gerado tipa o parâmetro como
            // "<Machine>__<Set> *" (ex. "RobustFifo__REPORT *"), um enum de verdade — não "int *".
            // Sem isto, "predicate ghost__op(..., int *rr)" não casa com o protótipo C real usado
            // no local de chamada ("at return: assert ghost__op(..., rr);"), e o Frama-C rejeita com
            // "invalid implicit conversion from '<Machine>__<Set> *' to 'int *'". Conjuntos DIFERIDOS
            // (sem Enumerated_Values, ex. Fifo_ctx__ELEM) não entram aqui — mapeiam para {@code int}
            // simples no C gerado, compatível com o "int *" por omissão.
            if (BxmlSetsTranslator.buildEnumeratedSetNames(machine).contains(rawTrim)) {
                String machineName = machine.getAttribute("name");
                if (machineName != null && !machineName.isBlank()) {
                    return machineName.trim() + "__" + rawTrim + " *";
                }
            }
        } catch (NumberFormatException ignored) {}
        return "int *";
    }

    static List<GhostOperationsCiGenerator.Param> listInputParameters(Element operation) {
        List<GhostOperationsCiGenerator.Param> out = new ArrayList<>();
        Element in = BxmlDomUtils.firstChildElement(operation, "Input_Parameters");
        if (in == null) return out;
        NodeList ch = in.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            if ("Id".equals(e.getLocalName())) {
                String name = e.getAttribute("value");
                if (name == null || name.isBlank()) continue;
                String cType = ghostParamTypeFromPrecondition(operation, name.trim());
                if (cType == null || cType.isBlank()) {
                    cType = cGhostParamTypeFromTypref(operation, e);
                }
                out.add(new GhostOperationsCiGenerator.Param(cType, sanitizeCIdent(name)));
            }
        }
        return out;
    }

    /**
     * Tipo C do parâmetro ghost a partir do {@code Precondition} (ex. {@code ee : NAT} →
     * {@code int}; {@code xx : S --> T} → {@code int *}; relação {@code POW(A*B)} → {@code int *}).
     * Nos {@code ensures}, escalares {@code int} são convertidos para {@code integer} via cast.
     */
    private static String ghostParamTypeFromPrecondition(Element operation, String paramName) {
        Element pre = BxmlDomUtils.firstChildElement(operation, "Precondition");
        if (pre == null) {
            return null;
        }
        return findMembershipTypeForParam(pre, paramName);
    }

    private static String findMembershipTypeForParam(Element pred, String paramName) {
        if (pred == null) {
            return null;
        }
        String ln = pred.getLocalName();
        if ("Precondition".equals(ln)) {
            NodeList nl = pred.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element ch = (Element) n;
                if ("Attr".equals(ch.getLocalName())) continue;
                String t = findMembershipTypeForParam(ch, paramName);
                if (t != null && !t.isBlank()) {
                    return t;
                }
            }
            return null;
        }
        if ("Exp_Comparison".equals(ln)) {
            String op = BxmlDomUtils.normalizeColonLikeOp(pred.getAttribute("op"));
            if (":".equals(op)) {
                Element[] pair = BxmlExpressionToAcsl.twoDirectExpChildren(pred);
                if (pair[0] != null
                        && "Id".equals(pair[0].getLocalName())
                        && paramName.equals(pair[0].getAttribute("value").trim())
                        && pair[1] != null) {
                    return cGhostParamTypeFromMembershipRhs(pair[1]);
                }
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
                String t = findMembershipTypeForParam(ch, paramName);
                if (t != null && !t.isBlank()) {
                    return t;
                }
            }
            return null;
        }
        if ("Unary_Pred".equals(ln)) {
            Element inner = GhostOperationsCiGenerator.firstPredChildElement(pred);
            return findMembershipTypeForParam(inner, paramName);
        }
        if ("Binary_Pred".equals(ln)) {
            Element[] pair = BxmlDomUtils.twoDirectPredChildren(pred);
            if (pair[0] != null) {
                String t = findMembershipTypeForParam(pair[0], paramName);
                if (t != null && !t.isBlank()) {
                    return t;
                }
            }
            if (pair[1] != null) {
                return findMembershipTypeForParam(pair[1], paramName);
            }
        }
        return null;
    }

    /**
     * Lado direito de {@code v : RHS} no precondition → tipo ACSL do parâmetro em
     * {@code ghost_operations.ci} (sem tipos lógicos como {@code Relation_*}).
     */
    private static String cGhostParamTypeFromMembershipRhs(Element rhs) {
        if (rhs == null) {
            return null;
        }
        if ("Id".equals(rhs.getLocalName())) {
            String v = rhs.getAttribute("value");
            if (v == null) {
                v = "";
            }
            return switch (v.trim()) {
                case "NAT", "INTEGER", "INT" -> "int";
                case "BOOL" -> "int";
                default -> null;
            };
        }
        if (BxmlExpressionToAcsl.isFunctionArrowType(rhs)) {
            return "int *";
        }
        if ("Unary_Exp".equals(rhs.getLocalName()) && "POW".equals(rhs.getAttribute("op"))) {
            Element inner = BxmlDomUtils.firstNonAttrElementChild(rhs);
            if (inner != null
                    && "Binary_Exp".equals(inner.getLocalName())
                    && "*".equals(trimOp(inner.getAttribute("op")))) {
                return "int *";
            }
        }
        return null;
    }

    private static String trimOp(String op) {
        return op == null ? "" : op.trim();
    }

    /** Fallback a partir de {@code typref} + {@code TypeInfos}: relações → {@code integer *}. */
    private static String cGhostParamTypeFromTypref(Element operation, Element paramId) {
        Element machine = findAncestorMachine(operation);
        if (machine == null) {
            return "int";
        }
        String tr = paramId.getAttribute("typref");
        if (tr == null || tr.isBlank()) {
            return "int";
        }
        try {
            int id = Integer.parseInt(tr.trim());
            BxmlTypeRegistry types = BxmlTypeRegistry.fromMachine(machine);
            String acsl = types.acslVariableLogicTypeFromTypref(id);
            if (acsl != null && acsl.startsWith("Relation_")) {
                return "int *";
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return "int";
    }

    static Element findAncestorMachine(Element el) {
        Node n = el;
        while (n != null) {
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                Element e = (Element) n;
                if ("Machine".equals(e.getLocalName())) {
                    return e;
                }
            }
            n = n.getParentNode();
        }
        return null;
    }

    /**
     * Em cláusulas {@code ensures} ghost, parâmetros C {@code int} usam-se como {@code (integer)x}
     * ao serem passados a funções do universo {@code dummy_ghost} (tipos matemáticos ACSL).
     */
    static String castScalarIntGhostParamsInEnsure(String ensure, List<GhostOperationsCiGenerator.Param> params) {
        if (ensure == null || ensure.isBlank() || params == null || params.isEmpty()) {
            return ensure;
        }
        List<GhostOperationsCiGenerator.Param> scalarInts = new ArrayList<>();
        for (GhostOperationsCiGenerator.Param p : params) {
            if (p.type() != null && "int".equals(p.type().trim())) {
                scalarInts.add(p);
            }
        }
        if (scalarInts.isEmpty()) {
            return ensure;
        }
        scalarInts.sort((a, b) -> Integer.compare(b.name().length(), a.name().length()));
        String out = ensure;
        for (GhostOperationsCiGenerator.Param p : scalarInts) {
            String name = p.name();
            out =
                    out.replaceAll(
                            "(?<!\\(integer\\)\\s*)(?<![A-Za-z0-9_])"
                                    + Pattern.quote(name)
                                    + "\\b",
                            Matcher.quoteReplacement("(integer)" + name));
        }
        return out;
    }

    /**
     * Declaração de parâmetro C em {@code ghost_operations.ci}: {@code int *xx} (sem espaço entre
     * {@code *} e o identificador); escalares mantêm espaço ({@code int ee}).
     */
    static String formatCParameterDecl(String type, String name) {
        if (type == null || type.isBlank()) {
            return name;
        }
        String t = type.trim();
        if (t.endsWith("*")) {
            return t + name;
        }
        return t + " " + name;
    }

    static String sanitizeCIdent(String name) {
        return name.replace('-', '_');
    }

    static String sanitizeGhostFunctionName(String opName) {
        if (opName == null || opName.isBlank()) return "operation";
        if ("INITIALISATION".equalsIgnoreCase(opName)) return "initialisation";
        return opName.replace('-', '_').toLowerCase();
    }

    /** Tipo C para {@code //@ ghost T ghost_<v>;} alinhado ao {@code logic} da máquina. */
    static String ghostCTypeFromLogicType(String logicType) {
        if (logicType == null || logicType.isBlank()) {
            return "int";
        }
        return switch (logicType.trim()) {
            case "boolean" -> "_Bool";
            case "real" -> "double";
            default -> "int";
        };
    }

    /** Tipo {@code logic} ACSL para {@code dummy_ghost_<v>} / variáveis ghost. */
    static String ghostLogicTypeFromInferred(String logicType) {
        if (logicType == null || logicType.isBlank()) {
            return "integer";
        }
        String t = logicType.trim();
        if ("boolean".equals(t) || "integer".equals(t) || "real".equals(t) || "int".equals(t)) {
            return t.equals("int") ? "integer" : t.equals("boolean") ? "integer" : t;
        }
        if (t.startsWith("\\list") || t.startsWith("Set<")) {
            return "integer";
        }
        return "integer";
    }
}
