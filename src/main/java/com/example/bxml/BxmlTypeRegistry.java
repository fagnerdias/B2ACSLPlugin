package com.example.bxml;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Constrói o mapa {@code typref} → descrição de tipo a partir de {@code <TypeInfos>} (BXML 1.0).
 *
 * @see <a href="https://www.atelierb.eu/wp-content/uploads/2023/10/bxml-1.0.html">BXML 1.0</a>
 */
public final class BxmlTypeRegistry {

    /**
     * Nome achatado (ver {@link #flattenGenericTypeExprToIdentifier}) para uma relação de domínio
     * escalar e codomínio tupla de N&gt;=2 elementos, qualquer mistura de inteiro/booleano,
     * aninhada à esquerda: grupo 1 = domínio, grupo 2 = os {@code "_Tuple"} (contagem = N-1), grupo
     * 3 = os N tipos-folha em ordem. {@code Tuple} maiúsculo garante que nunca colide com o padrão
     * legado {@code Relation_X_Y} (só aceita segmentos minúsculos). Partilhado por
     * {@link DummyGhostAxiomaticBuilder} (mesmo pacote) e {@code
     * com.example.SpecificationAxiomaticInstantiator} (pacote diferente — referência pública) para
     * reconhecer o mesmo nome na direção inversa (nome → par de tipos).
     */
    public static final Pattern TUPLE_CODOMAIN_RELATION_NAME = Pattern.compile(
            "^(?:Relation|Function)_(integer|boolean)((?:_Tuple)+)((?:_(?:integer|boolean))+)$");

    private final Map<Integer, String> idToDisplay = new HashMap<>();

    public static BxmlTypeRegistry fromMachine(Element machineEl) {
        BxmlTypeRegistry r = new BxmlTypeRegistry();
        NodeList typeInfos = machineEl.getElementsByTagNameNS("*", "TypeInfos");
        if (typeInfos.getLength() == 0) return r;
        Element ti = (Element) typeInfos.item(0);
        NodeList types = ti.getElementsByTagNameNS("*", "Type");
        for (int i = 0; i < types.getLength(); i++) {
            Element t = (Element) types.item(i);
            String idStr = t.getAttribute("id");
            if (idStr.isBlank()) continue;
            int id = Integer.parseInt(idStr.trim());
            r.idToDisplay.put(id, typeToString(t));
        }
        return r;
    }

    /**
     * Elemento de tipo de um conjunto vazio / expressão, ex.: {@code POW(INTEGER)} → usado para {@code empty(...)}.
     */
    public String elementTypeNameForSetTypref(int typref) {
        String full = idToDisplay.getOrDefault(typref, "UNKNOWN");
        // POW(T) → T
        if (full.startsWith("POW(") && full.endsWith(")")) {
            String inner = full.substring(4, full.length() - 1).trim();
            // B: NAT ⊆ ℤ; POW(NAT) para conjuntos de naturais
            if ("INTEGER".equals(inner)) return "NAT";
            return inner;
        }
        return full;
    }

    public String getRawType(int typref) {
        return idToDisplay.getOrDefault(typref, "UNKNOWN");
    }

    /**
     * Tipo ACSL para declarações {@code logic} de variáveis B (a partir de {@code typref} em
     * {@code Abstract_Variables} / {@code Concrete_Variables}).
     *
     * <p>Ex.: {@code POW(INTEGER)} → {@code Set<integer>}; {@code POW(INTEGER*INTEGER)} (produto
     * sob {@code POW}, ex. sequência como relação) → {@code Relation_int_int}; {@code INTEGER} →
     * {@code integer}.
     */
    public String acslVariableLogicTypeFromTypref(int typref) {
        if (typref < 0) return "integer";
        return rawBTypeToAcslVariableLogicType(getRawType(typref));
    }

    /**
     * Converte o texto de tipo B (de {@link #getRawType}) num tipo de lógica ACSL para variáveis.
     */
    public static String rawBTypeToAcslVariableLogicType(String raw) {
        if (raw == null || raw.isBlank() || "UNKNOWN".equals(raw)) return "integer";
        String r = raw.trim();
        if (isScalarBTypeName(r)) {
            return acslElementTypeNameStatic(r);
        }
        if (r.startsWith("POW(") && r.endsWith(")")) {
            String inner = r.substring(4, r.length() - 1).trim();
            if (inner.contains("*")) {
                return powCartesianProductToAcslRelationType(inner);
            }
            if (inner.startsWith("POW(")) {
                return "Set<" + rawBTypeToAcslVariableLogicType(inner) + ">";
            }
            return "Set<" + acslElementTypeNameStatic(inner) + ">";
        }
        return "integer";
    }

    /**
     * Produto cartesiano B sob {@code POW} (ex.: {@code INTEGER*INTEGER}) → tipo relação na
     * ACSL_Lib ({@code Relation_int_int}, {@code Relation_int_bool}, …), não {@code \list}.
     *
     * <p>{@code innerProduct} vem de {@link #bxmlTypeExprToString} achatado (sem parênteses) — para
     * um codomínio tupla de N&gt;=2 elementos (ex.: {@code PERSON +-> (DAY*MONTH*YEAR)}, B associa
     * produtos à esquerda: {@code (DAY*MONTH)*YEAR}), {@code innerProduct} chega como
     * {@code "PERSON*DAY*MONTH*YEAR"} (achatado, 4 partes) — não como 2. Antes desta função existir
     * só se olhava para a PRIMEIRA parte (domínio) e o RESTO inteiro como uma única string "rhs"
     * (que nunca batia com {@code INTEGER}/{@code BOOL}, caindo sempre no default {@code
     * Relation_int_int} — tipo errado, silencioso: WP só rejeitava mais tarde, num ponto de uso,
     * com "incompatible types").
     *
     * <p>Para os 3 pares escalar-escalar mais comuns ({@code int_int}/{@code int_bool}/{@code
     * bool_int}) usa os aliases estáticos pré-declarados em {@code types.acsl} (evita gerar um
     * ficheiro extra por máquina para o caso mais frequente). Para QUALQUER outra combinação —
     * {@code bool_bool}, ou um codomínio tupla de N&gt;=2 elementos com qualquer mistura de
     * inteiro/booleano — não há como pré-declarar todas as combinações possíveis (explosão
     * combinatória: 2^N tipos de codomínio × 2 tipos de domínio × N aridades), então regista o tipo
     * em {@link TupleCodomainTypeRegistry} para ser escrito num {@code .acsl} próprio da máquina e
     * incluído localmente por ela (ver {@link AcslGenerator#generateAcsl}) — {@code -acsl-import}
     * só aceita instanciação genérica concreta ({@code Relation<integer, Tuple<...>>}) dentro de um
     * ficheiro alcançado via {@code include}, nunca inline num ficheiro de topo (confirmado
     * empiricamente).
     */
    public static String powCartesianProductToAcslRelationType(String innerProduct) {
        if (innerProduct == null || !innerProduct.contains("*")) {
            return "Relation_int_int";
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (String p : innerProduct.split("\\*")) {
            String t = p.trim();
            if (!t.isEmpty()) parts.add(t);
        }
        if (parts.size() < 2) {
            return "Relation_int_int";
        }
        java.util.List<String> acslTypes = new java.util.ArrayList<>();
        for (String part : parts) {
            acslTypes.add(acslElementTypeNameStatic(part));
        }

        if (parts.size() == 2) {
            String le = acslTypes.get(0);
            String re = acslTypes.get(1);
            if ("integer".equals(le) && "integer".equals(re)) {
                return "Relation_int_int";
            }
            if ("integer".equals(le) && "boolean".equals(re)) {
                return "Relation_int_bool";
            }
            if ("boolean".equals(le) && "integer".equals(re)) {
                return "Relation_bool_int";
            }
            // boolean_boolean: não pré-declarado — cai no caminho genérico abaixo em vez do
            // default errado antigo.
        }

        // Domínio escalar + codomínio tupla de N>=2 elementos (aqui N = parts.size()-1), B associa
        // à esquerda: Tuple<Tuple<...,tipoN-1>,tipoN> — qualquer mistura de integer/boolean.
        String domainType = acslTypes.get(0);
        String codomainType = acslTypes.get(1);
        for (int i = 2; i < acslTypes.size(); i++) {
            codomainType = "Tuple<" + codomainType + "," + acslTypes.get(i) + ">";
        }
        String genericTypeExpr = "Relation<" + domainType + ", " + codomainType + ">";
        String flatName = flattenGenericTypeExprToIdentifier(genericTypeExpr);
        String definition =
                spaceOutAdjacentClosingAngleBrackets(
                        "Set<Tuple<" + domainType + ", " + codomainType + "> >");
        TupleCodomainTypeRegistry.register(flatName, definition);
        // Function_X declarado com a MESMA definição concreta (Set<Tuple<...>>), não como
        // "= Relation_X" (sinónimo cruzado): confirmado empiricamente que o Frama-C reordena
        // declarações "type A = B;"/"type B = ...;" de forma NÃO DETERMINÍSTICA entre execuções do
        // MESMO binário com o MESMO input (mesmo dentro de um único axiomatic block, mesmo com
        // Relation_X sempre escrito antes de Function_X no ficheiro-fonte) — quando Function_X
        // acaba impresso/processado antes de Relation_X estar resolvido, function_apply(<variável
        // Relation_X>, ...) falha com "no such predicate or logic function" apesar da sobrecarga
        // declarada usar Function_X (que É sinónimo de Relation_X). Duas declarações
        // INDEPENDENTES, cada uma expandindo diretamente para Set<Tuple<...>> sem referenciar a
        // outra, não têm essa dependência de ordem — nenhuma precisa que a outra já esteja
        // resolvida.
        String functionFlatName = "Function_" + flatName.substring("Relation_".length());
        TupleCodomainTypeRegistry.register(functionFlatName, definition);
        return flatName;
    }

    /**
     * Insere um espaço entre {@code ">"} adjacentes (ex.: {@code "integer>>"} →
     * {@code "integer> >"}) — o lexer do Frama-C tokeniza {@code ">>"} como um único operador
     * (shift), não como dois fechos de genérico consecutivos, o mesmo problema clássico de C++
     * antes de C++11. {@code types.acsl} já evita isto manualmente (ex.: {@code "Set<Tuple<A,B>
     * >"}), mas o aninhamento à esquerda de N&gt;=3 tipos-tupla PRODUZ ">>" adjacentes onde o
     * fecho do {@code Tuple} interno encontra o fecho do {@code Set} externo — usa lookahead
     * (não consome o {@code ">"} seguinte) para lidar corretamente com 3+ fechos consecutivos
     * numa só passagem.
     */
    private static String spaceOutAdjacentClosingAngleBrackets(String typeExpr) {
        return typeExpr.replaceAll(">(?=>)", "> ");
    }

    /**
     * Achata uma expressão de tipo genérico concreto (ex.: {@code "Relation<integer,
     * Tuple<Tuple<integer,integer>,integer>>"}) para um identificador ACSL válido (ex.: {@code
     * "Relation_integer_Tuple_Tuple_integer_integer_integer"}). Tem de replicar byte-a-byte o
     * algoritmo de {@code SpecificationAxiomaticInstantiator#typeToIdentifier} (achata {@code <},
     * {@code >}, vírgula e espaço para {@code "_"}, colapsa repetições, remove {@code "_"} final) —
     * a monomorphização (fase separada, sobre {@code merged_code.c}) usa aquele algoritmo para
     * nomear o MESMO par de tipos de forma independente; um nome que não bata dá "no such type" no
     * Frama-C mesmo com {@code -acsl-import} já tendo aceitado a declaração original.
     */
    static String flattenGenericTypeExprToIdentifier(String genericTypeExpr) {
        String s = genericTypeExpr;
        s = s.replaceAll("[<>]", "_");
        s = s.replaceAll("[^A-Za-z0-9_]", "_");
        s = s.replaceAll("_+", "_");
        s = s.replaceAll("_$", "");
        return s;
    }

    private static boolean isScalarBTypeName(String r) {
        return switch (r) {
            case "INTEGER", "INT", "NAT", "BOOL" -> true;
            default -> false;
        };
    }

    private static String acslElementTypeNameStatic(String bName) {
        if (bName == null || bName.isBlank()) return "integer";
        return switch (bName) {
            case "INTEGER", "INT", "NAT" -> "integer";
            case "BOOL" -> "boolean";
            default -> "integer"; // conjuntos deferidos B (ex.: BOOK, COPY) → integer
        };
    }

    /** Tipo em lógica ACSL para valores (ex.: {@code \forall integer x}). */
    public String acslLogicTypeForValueTypref(int typref) {
        if (typref < 0) return "integer";
        return acslElementTypeName(getRawType(typref));
    }

    /** Nome de elemento ACSL (minúsculas) para {@code Set<...>}. */
    public String acslElementTypeName(String bName) {
        if (bName == null || bName.isBlank()) return "integer";
        return switch (bName) {
            case "INTEGER", "INT", "NAT" -> "integer";
            case "BOOL" -> "boolean";
            default -> bName.toLowerCase();
        };
    }

    private static String typeToString(Element typeEl) {
        NodeList children = typeEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element child = (Element) n;
            if ("Attr".equals(child.getLocalName())) continue;
            return bxmlTypeExprToString(child);
        }
        return "UNKNOWN";
    }

    /** Árvore de tipo B em string (ex.: {@code POW(INTEGER*INTEGER)}, {@code POW(POW(INTEGER))}). */
    private static String bxmlTypeExprToString(Element e) {
        String ln = e.getLocalName();
        if ("Id".equals(ln)) {
            return e.getAttribute("value");
        }
        if ("Unary_Exp".equals(ln) && "POW".equals(e.getAttribute("op"))) {
            Element inner = firstNonAttrElementChild(e);
            return "POW(" + bxmlTypeExprToString(inner) + ")";
        }
        if ("Binary_Exp".equals(ln) && "*".equals(e.getAttribute("op"))) {
            Element[] pair = twoNonAttrElementChildren(e);
            if (pair[0] != null && pair[1] != null) {
                return bxmlTypeExprToString(pair[0]) + "*" + bxmlTypeExprToString(pair[1]);
            }
        }
        return "UNKNOWN";
    }

    private static Element firstNonAttrElementChild(Element parent) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            if ("Attr".equals(el.getLocalName())) continue;
            return el;
        }
        return null;
    }

    private static Element[] twoNonAttrElementChildren(Element parent) {
        Element[] out = new Element[2];
        int k = 0;
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            if ("Attr".equals(el.getLocalName())) continue;
            out[k++] = el;
            if (k == 2) break;
        }
        return out;
    }
}
