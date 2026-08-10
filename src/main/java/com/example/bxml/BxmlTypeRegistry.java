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
     * Instanciação genérica default de {@code Relation<A,B>} ({@code integer,integer}) — resolvida
     * diretamente via {@code type Relation<A,B> = Set<Tuple<A,B> >;} em {@code types.acsl}, sem
     * depender de um alias achatado {@code Relation_int_int} estático (que não existe nessa forma).
     */
    private static final String RELATION_INT_INT = "Relation<integer,integer>";

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

    /**
     * Espelho de {@link #TUPLE_CODOMAIN_RELATION_NAME} para o caso oposto: domínio composto (tupla
     * de N&gt;=2 elementos, qualquer mistura de inteiro/booleano) + codomínio escalar — ex. {@code
     * "Relation_Tuple_integer_integer_boolean"} para a matriz característica {@code PLAYER*ISLAND
     * --> BOOL}. Grupo 1 = os {@code "Tuple_"} (contagem = N-1), grupo 2 = os N tipos-folha do
     * domínio em ordem (cada um com {@code "_"} à direita), grupo 3 = o tipo escalar do codomínio.
     * {@code Tuple} logo após {@code Relation_}/{@code Function_} (sem nenhum escalar antes) é o que
     * distingue este caso do de {@link #TUPLE_CODOMAIN_RELATION_NAME} (que exige um escalar
     * IMEDIATAMENTE após o prefixo) — mutuamente exclusivos por construção, nunca ambos batem na
     * mesma string. Ver {@link #compoundDomainCartesianProductToAcslRelationType}.
     */
    public static final Pattern TUPLE_DOMAIN_RELATION_NAME = Pattern.compile(
            "^(?:Relation|Function)_((?:Tuple_)+)((?:(?:integer|boolean)_)+)(integer|boolean)$");

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
     * bool_int}) usa a instanciação genérica {@code Relation<A,B>} já declarada em {@code
     * types.acsl} ({@code type Relation<A,B> = Set<Tuple<A,B> >;}) — não um alias achatado
     * {@code Relation_X_Y} estático, que não existe em {@code types.acsl} e falharia no
     * {@code -acsl-import} com "no such type" (evita gerar um ficheiro extra por máquina para o caso
     * mais frequente). Para QUALQUER outra combinação —
     * {@code bool_bool}, ou um codomínio tupla de N&gt;=2 elementos com qualquer mistura de
     * inteiro/booleano — não há como pré-declarar todas as combinações possíveis (explosão
     * combinatória: 2^N tipos de codomínio × 2 tipos de domínio × N aridades), então regista o tipo
     * em {@link TupleCodomainTypeRegistry} para ser escrito num {@code .acsl} próprio da máquina e
     * incluído localmente por ela (ver {@link AcslGenerator#generateAcsl}) — {@code -acsl-import}
     * só aceita instanciação genérica concreta ({@code Relation<integer, Tuple<...>>}) dentro de um
     * ficheiro alcançado via {@code include}, nunca inline num ficheiro de topo (confirmado
     * empiricamente).
     */
    /**
     * Tipo ACSL de uma parte do produto cartesiano flattened (ver {@link
     * #powCartesianProductToAcslRelationType}): {@code POW(X)} (ex.: domínio/codomínio ele próprio
     * um conjunto, como em {@code PLAYER --> POW(ISLAND)}) → {@code Set<...>} recursivo; senão
     * escalar via {@link #acslElementTypeNameStatic}. Sem isto, uma parte {@code POW(X)} caía como
     * string opaca em {@code acslElementTypeNameStatic} (nunca bate INTEGER/INT/NAT/BOOL) e resolvia
     * silenciosamente para {@code integer} — {@code Relation_int_int} errado em vez de {@code
     * Relation<integer, Set<integer>>}, só rejeitado tarde por WP com "incompatible types".
     */
    private static String resolveCartesianPartType(String part) {
        if (part != null && part.startsWith("POW(") && part.endsWith(")")) {
            String inner = part.substring(4, part.length() - 1).trim();
            return "Set<" + resolveCartesianPartType(inner) + ">";
        }
        return acslElementTypeNameStatic(part);
    }

    public static String powCartesianProductToAcslRelationType(String innerProduct) {
        if (innerProduct == null || !innerProduct.contains("*")) {
            return RELATION_INT_INT;
        }
        String trimmed = innerProduct.trim();
        if (trimmed.startsWith("(")) {
            return compoundDomainCartesianProductToAcslRelationType(trimmed);
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (String p : innerProduct.split("\\*")) {
            String t = p.trim();
            if (!t.isEmpty()) parts.add(t);
        }
        if (parts.size() < 2) {
            return RELATION_INT_INT;
        }
        java.util.List<String> acslTypes = new java.util.ArrayList<>();
        for (String part : parts) {
            acslTypes.add(resolveCartesianPartType(part));
        }

        if (parts.size() == 2) {
            String le = acslTypes.get(0);
            String re = acslTypes.get(1);
            if ("integer".equals(le) && "integer".equals(re)) {
                return RELATION_INT_INT;
            }
            if ("integer".equals(le) && "boolean".equals(re)) {
                return "Relation<integer,boolean>";
            }
            if ("boolean".equals(le) && "integer".equals(re)) {
                return "Relation<boolean,integer>";
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
        return registerCartesianRelationType(domainType, codomainType);
    }

    /**
     * Domínio composto entre parênteses (marcado por {@link #bxmlTypeExprToString} apenas no split
     * de topo quando o filho ESQUERDO já é ele próprio um produto — ex. {@code
     * "(PLAYER*ISLAND)*BOOL"} para a matriz característica {@code PLAYER*ISLAND --> BOOL}) →
     * {@code Relation<Tuple<...>, ...>}, o oposto estrutural do caso tratado acima (domínio escalar
     * + codomínio tupla, ex. {@code PERSON +-> (DAY*MONTH*YEAR)}). Sem este marcador as duas formas
     * achatavam para a MESMA string (ex. {@code "PLAYER*ISLAND*BOOL"}) e o caminho acima assumia
     * sempre a segunda forma — ver memória {@code project_union_inter_generalized_quantifiers}.
     */
    private static String compoundDomainCartesianProductToAcslRelationType(String innerProduct) {
        int depth = 0;
        int closeIdx = -1;
        for (int i = 0; i < innerProduct.length(); i++) {
            char c = innerProduct.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    closeIdx = i;
                    break;
                }
            }
        }
        if (closeIdx < 0
                || closeIdx + 2 > innerProduct.length()
                || innerProduct.charAt(closeIdx + 1) != '*') {
            return RELATION_INT_INT;
        }
        String domainInner = innerProduct.substring(1, closeIdx).trim();
        String codomainInner = innerProduct.substring(closeIdx + 2).trim();
        String domainType = leftNestedTupleType(domainInner);
        String codomainType = leftNestedTupleType(codomainInner);
        if (domainType == null || codomainType == null) {
            return RELATION_INT_INT;
        }
        return registerCartesianRelationType(domainType, codomainType);
    }

    /**
     * {@code "A*B*C"} (achatado, sem parênteses) → {@code Tuple<Tuple<tipoA,tipoB>,tipoC>} aninhado
     * à esquerda (mesma associação B); uma única parte → o próprio tipo resolvido (escalar ou
     * {@code Set<...>} via {@link #resolveCartesianPartType}).
     */
    private static String leftNestedTupleType(String flatProduct) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (String p : flatProduct.split("\\*")) {
            String t = p.trim();
            if (!t.isEmpty()) parts.add(t);
        }
        if (parts.isEmpty()) return null;
        String type = resolveCartesianPartType(parts.get(0));
        for (int i = 1; i < parts.size(); i++) {
            type = "Tuple<" + type + "," + resolveCartesianPartType(parts.get(i)) + ">";
        }
        return type;
    }

    /**
     * Regista {@code domainType}/{@code codomainType} (já resolvidos para ACSL, ex. {@code
     * Tuple<integer,integer>}/{@code boolean}) como um par {@code Relation_X}/{@code Function_X} em
     * {@link TupleCodomainTypeRegistry}, devolvendo o nome achatado de {@code Relation_X}. Partilhado
     * pelos dois caminhos (domínio escalar+codomínio tupla, e domínio composto — ver {@link
     * #compoundDomainCartesianProductToAcslRelationType}) — a forma de registo/nomeação não depende
     * de qual dos dois lados é o composto.
     */
    private static String registerCartesianRelationType(String domainType, String codomainType) {
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

    /**
     * Tipo de elemento ACSL para {@code Set<...>}/{@code \forall}. Alinhado a {@link
     * #acslElementTypeNameStatic}: conjuntos B diferidos/enumerados (ex.: {@code ISLAND}, {@code
     * BOOK}) nunca ganham um tipo ACSL próprio declarado neste codebase — são sempre modelados como
     * {@code Set<integer>} (ver {@code axiomatic <Machine>_sets}), por isso o default tem de cair em
     * {@code "integer"}, não {@code bName.toLowerCase()} (que produzia um tipo nunca declarado, ex.
     * {@code Set<island>}, rejeitado como "unexpected token"/"unbound" pelo Frama-C).
     */
    public String acslElementTypeName(String bName) {
        if (bName == null || bName.isBlank()) return "integer";
        return switch (bName) {
            case "INTEGER", "INT", "NAT" -> "integer";
            case "BOOL" -> "boolean";
            default -> "integer";
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
        return bxmlTypeExprToString(e, true);
    }

    /**
     * {@code topLevelProduct} distingue o split de topo (domínio×codomínio de um {@code A --> B},
     * ex. {@code POW(A*B)}) de qualquer {@code *} aninhado ENCONTRADO DENTRO de um dos dois lados
     * (ex. um codomínio tupla {@code (DAY*MONTH)*YEAR} de {@code PERSON +-> (DAY*MONTH*YEAR)}).
     * B associa produtos não-parentizados à ESQUERDA, então tanto {@code (A*B)-->C} (domínio
     * composto, ex. {@code PLAYER*ISLAND --> BOOL}) como {@code A-->(B*C)} (codomínio composto,
     * ex. {@code PERSON +-> (DAY*MONTH*YEAR)}) achatam para a MESMA string sem marcação alguma
     * ({@code "A*B*C"}) — apesar de terem formas AST diferentes (filho esquerdo composto vs filho
     * direito composto). Sem distinguir, {@link #powCartesianProductToAcslRelationType} assumia
     * sempre a segunda forma (domínio escalar), dando um tipo errado para a primeira (ex.
     * {@code player_islands_i}, uma matriz característica {@code PLAYER*ISLAND --> BOOL}). Corrigido
     * parentizando o filho ESQUERDO apenas quando (a) estamos no split de TOPO e (b) esse filho é
     * ele próprio um produto — nunca nos splits recursivos internos (senão contaminaria também o
     * caso já-correto do codomínio-tupla, cujo próprio filho esquerdo interno também é composto por
     * associatividade à esquerda). Produz no máx. 1 nível de parênteses (nunca aninhados): a
     * recursão do lado esquerdo já usa {@code topLevelProduct=false}, então mesmo um domínio composto
     * de aridade N&gt;=3 (ex. {@code (A*B)*C --> D}) achata o seu próprio interior sem parênteses
     * antes de ser envolvido UMA vez pelo nível de topo (ex. {@code "(A*B*C)*D"}).
     */
    private static String bxmlTypeExprToString(Element e, boolean topLevelProduct) {
        String ln = e.getLocalName();
        if ("Id".equals(ln)) {
            return e.getAttribute("value");
        }
        if ("Unary_Exp".equals(ln) && "POW".equals(e.getAttribute("op"))) {
            Element inner = BxmlDomUtils.firstNonAttrElementChild(e);
            return "POW(" + bxmlTypeExprToString(inner, topLevelProduct) + ")";
        }
        if ("Binary_Exp".equals(ln) && "*".equals(e.getAttribute("op"))) {
            Element[] pair = twoNonAttrElementChildren(e);
            if (pair[0] != null && pair[1] != null) {
                String left = bxmlTypeExprToString(pair[0], false);
                String right = bxmlTypeExprToString(pair[1], false);
                boolean leftIsCompound =
                        "Binary_Exp".equals(pair[0].getLocalName())
                                && "*".equals(pair[0].getAttribute("op"));
                if (topLevelProduct && leftIsCompound) {
                    left = "(" + left + ")";
                }
                return left + "*" + right;
            }
        }
        return "UNKNOWN";
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
