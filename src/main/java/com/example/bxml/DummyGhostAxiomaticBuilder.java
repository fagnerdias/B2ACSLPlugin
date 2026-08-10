package com.example.bxml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.example.AcslLibSymbolDependencyMap;
import com.example.B2AcslLibraryPaths;

/**
 * Gera o bloco comentado {@code axiomatic dummy_ghost} para {@code ghost_operations.ci} com base
 * nos símbolos da {@code B2ACSLLib} efetivamente referenciados nos contratos ghost (prefixo
 * {@code dummy_}), em vez de declarar um conjunto fixo de funções/tipos.
 */
final class DummyGhostAxiomaticBuilder {

    /** Nomes usados na especificação ghost que mapeiam para outro símbolo na biblioteca. */
    static final Map<String, String> LIB_SYMBOL_ALIASES = Map.of();

    private static final Pattern DUMMY_LIB_CALL =
            Pattern.compile("\\bdummy_([A-Za-z][A-Za-z0-9_]*)\\b");

    private static final Pattern DUMMY_FUNCTION_TYPE =
            Pattern.compile("\\bdummy_(Function_\\w+)\\b");

    private static final Pattern DECL_LINE =
            Pattern.compile(
                    "^(logic|predicate|type)\\s+(.+)$", Pattern.MULTILINE);

    private static final Pattern DECL_NAME_BEFORE_PAREN =
            Pattern.compile("\\b([A-Za-z][A-Za-z0-9_]*)(?:<[^>]*>)?\\s*$");

    private final AcslLibSymbolDependencyMap symMap;

    DummyGhostAxiomaticBuilder(AcslLibSymbolDependencyMap symMap) {
        this.symMap = symMap != null ? symMap : AcslLibSymbolDependencyMap.instance();
    }

    String format(
            List<String> ghostEnsureLines,
            List<String> abstractVarNames,
            Map<String, String> varTypes,
            int maxSetComprehensionIndex,
            Element machineEl,
            BxmlTranslateContext ctx) {
        return format(
                ghostEnsureLines,
                abstractVarNames,
                varTypes,
                maxSetComprehensionIndex,
                machineEl,
                ctx,
                null);
    }

    String format(
            List<String> ghostEnsureLines,
            List<String> abstractVarNames,
            Map<String, String> varTypes,
            int maxSetComprehensionIndex,
            Element machineEl,
            BxmlTranslateContext ctx,
            Path bxmlDirectory) {
        return format(ghostEnsureLines, abstractVarNames, varTypes, maxSetComprehensionIndex,
                machineEl, ctx, bxmlDirectory, Map.of());
    }

    String format(
            List<String> ghostEnsureLines,
            List<String> abstractVarNames,
            Map<String, String> varTypes,
            int maxSetComprehensionIndex,
            Element machineEl,
            BxmlTranslateContext ctx,
            Path bxmlDirectory,
            Map<String, String> abstractConstDecls) {
        String ghostText = joinLines(ghostEnsureLines);
        List<BxmlSetsTranslator.EnumeratedSetInfo> enumeratedSets =
                machineEl == null
                        ? List.of()
                        : bxmlDirectory != null
                                ? BxmlSetsTranslator.listEnumeratedSetsWithSees(
                                        machineEl, bxmlDirectory)
                                : BxmlSetsTranslator.listEnumeratedSets(machineEl);
        Set<String> reservedNames =
                reservedDummyNames(abstractVarNames, machineEl, maxSetComprehensionIndex, enumeratedSets);

        Map<String, String> ghostNameToLibSymbol = collectGhostNameToLibSymbol(ghostText, reservedNames);
        Set<String> functionTypeAliases = collectFunctionTypeAliases(ghostText);
        boolean needsNat = Pattern.compile("\\bdummy_NAT\\b").matcher(ghostText).find();
        boolean needsNat1 = Pattern.compile("\\bdummy_NAT1\\b").matcher(ghostText).find();
        boolean needsInt = ghostText.contains("dummy_INT");
        boolean needsBool = ghostText.contains("dummy_BOOL");
        if (!enumeratedSets.isEmpty()) {
            ghostNameToLibSymbol.putIfAbsent("belongs", "belongs");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("/*@\n");
        sb.append("    axiomatic dummy_ghost {\n\n");

        if (!ghostNameToLibSymbol.isEmpty()
                || needsNat
                || needsNat1
                || needsInt
                || needsBool
                || !functionTypeAliases.isEmpty()
                || !enumeratedSets.isEmpty()) {
            appendBaseDummyTypes(sb);
        }

        for (String fnType : functionTypeAliases) {
            sb.append("        type dummy_").append(fnType).append(" = DRelation<integer, integer>;\n\n");
        }

        // Predicados/funções da lib (ex. dummy_belongs) antes dos axiomas que os referenciam.
        appendDummyLibSignatures(sb, ghostNameToLibSymbol);

        appendDummyEnumeratedSets(sb, enumeratedSets);

        for (String v : abstractVarNames) {
            String t = varTypes.getOrDefault(v, "integer");
            String d = dummyAxiomaticLogicType(t);
            sb.append("        logic ").append(d).append(" dummy_").append(v).append(";\n\n");
        }

        appendDummyConcreteConstantDeclarations(sb, machineEl, ctx);

        appendSeenConcreteConstantDeclarations(sb, ghostText, machineEl, bxmlDirectory);

        if (maxSetComprehensionIndex > 0) {
            String machineName = machineEl == null ? "" : machineEl.getAttribute("name");
            if (machineName == null) {
                machineName = "";
            } else {
                machineName = machineName.trim();
            }
            for (int k = 1; k <= maxSetComprehensionIndex; k++) {
                sb.append("        logic DSet<integer> dummy_")
                        .append(BxmlComprehensionRegistry.comprehensionSetName(machineName, k));
                // Aridade tem de bater com a declaração REAL (referenceName/formatAxiomaticBlock):
                // uma compreensão parametrizada (ex. set_comprehension_2(pp), RulerOfTheSeas's
                // InvestOnResources) referenciada em texto ghost como dummy_..._2(pp) contra uma
                // declaração de aridade zero aqui dava "too many arguments" — ver
                // BxmlComprehensionRegistry#freeVarsForIndex.
                if (ctx != null && ctx.comprehensions() != null) {
                    List<String>[] free = ctx.comprehensions().freeVarsForIndex(k, ctx);
                    if (!free[0].isEmpty()) {
                        sb.append("(");
                        for (int i = 0; i < free[0].size(); i++) {
                            if (i > 0) sb.append(", ");
                            sb.append(dummyAxiomaticLogicType(free[1].get(i)))
                                    .append(" ")
                                    .append(free[0].get(i));
                        }
                        sb.append(")");
                    }
                }
                sb.append(";\n\n");
            }
        }

        if (needsNat) {
            sb.append("        logic DSet<integer> dummy_NAT;\n\n");
        }

        if (needsNat1) {
            sb.append("        logic DSet<integer> dummy_NAT1;\n\n");
        }

        if (needsInt) {
            sb.append("        logic DSet<integer> dummy_INT;\n\n");
        }

        if (needsBool) {
            sb.append("        logic DSet<integer> dummy_BOOL;\n\n");
        }

        sb.append("    }\n*/\n");

        // Abstract constants declared separately so the axiomatic survives post-processing
        // of merged_code.c (dummy_ghost is stripped, but this block is not).
        if (abstractConstDecls != null && !abstractConstDecls.isEmpty()) {
            String machineName = machineEl == null ? "" : machineEl.getAttribute("name");
            if (machineName == null) machineName = "";
            sb.append("/*@\n");
            sb.append("    axiomatic ").append(machineName.trim()).append("_ci_abstract_constants {\n\n");
            for (Map.Entry<String, String> e : abstractConstDecls.entrySet()) {
                sb.append("        ").append(e.getValue()).append(";\n\n");
            }
            sb.append("    }\n*/\n");
        }

        return sb.toString();
    }

    private static void appendBaseDummyTypes(StringBuilder sb) {
        sb.append("        type DSet<A>;\n\n");
        sb.append("        type DTuple<A, B>;\n\n");
        sb.append("        type DRelation<A, B> = DSet<DTuple<A, B> >;\n\n");
    }

    private void appendDummyLibSignatures(
            StringBuilder sb, Map<String, String> ghostNameToLibSymbol) {
        LinkedHashSet<String> emitted = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : ghostNameToLibSymbol.entrySet()) {
            String ghostName = e.getKey();
            String libSymbol = e.getValue();
            String decl = loadDummySignature(libSymbol, ghostName);
            if (decl != null && !decl.isBlank() && emitted.add(ghostName)) {
                sb.append("        ").append(decl).append("\n\n");
            }
        }
    }

    /**
     * Conjuntos enumerados B → {@code dummy_<Maquina>__<Conjunto>} e {@code dummy_<Maquina>__<Valor>}.
     */
    private void appendDummyEnumeratedSets(
            StringBuilder sb, List<BxmlSetsTranslator.EnumeratedSetInfo> enumeratedSets) {
        for (BxmlSetsTranslator.EnumeratedSetInfo set : enumeratedSets) {
            String dummySet = set.dummySetLogicName();
            sb.append("        logic DSet<integer> ").append(dummySet).append(";\n\n");
            List<String> dummyVals = new ArrayList<>();
            for (String v : set.valueNames()) {
                String dv = set.dummyValueLogicName(v);
                dummyVals.add(dv);
                sb.append("        logic integer ").append(dv).append(";\n\n");
            }
            sb.append("        axiom ").append(dummySet).append("_values:\n");
            for (String dv : dummyVals) {
                sb.append("            dummy_belongs(").append(dv).append(", ").append(dummySet).append(")\n");
                sb.append("            &&\n");
            }
            sb.append("            \\forall integer x;\n");
            sb.append("                dummy_belongs(x, ").append(dummySet).append(") ==>\n");
            sb.append("                (");
            for (int k = 0; k < dummyVals.size(); k++) {
                if (k > 0) sb.append(" || ");
                sb.append("x == ").append(dummyVals.get(k));
            }
            sb.append(");\n\n");
        }
    }

    private Map<String, String> collectGhostNameToLibSymbol(String ghostText, Set<String> reservedNames) {
        Map<String, String> out = new LinkedHashMap<>();
        if (ghostText == null || ghostText.isBlank()) {
            return out;
        }
        Matcher m = DUMMY_LIB_CALL.matcher(ghostText);
        while (m.find()) {
            String ghostName = m.group(1);
            if (reservedNames.contains(ghostName)) {
                continue;
            }
            if (ghostName.startsWith("Function_")) {
                continue;
            }
            if (ghostName.contains("set_comprehension_")) {
                continue;
            }
            if ("NAT".equals(ghostName)) {
                continue;
            }
            String libSymbol = resolveLibSymbol(ghostName);
            if (libSymbol != null) {
                out.putIfAbsent(ghostName, libSymbol);
            }
        }
        return out;
    }

    private static Set<String> collectFunctionTypeAliases(String ghostText) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (ghostText == null || ghostText.isBlank()) {
            return out;
        }
        Matcher m = DUMMY_FUNCTION_TYPE.matcher(ghostText);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static Set<String> reservedDummyNames(
            List<String> abstractVarNames,
            Element machineEl,
            int maxSetComp,
            List<BxmlSetsTranslator.EnumeratedSetInfo> enumeratedSets) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (abstractVarNames != null) {
            out.addAll(abstractVarNames);
        }
        if (machineEl != null) {
            Element block = BxmlDomUtils.firstChildElement(machineEl, "Concrete_Constants");
            if (block != null) {
                NodeList ch = block.getChildNodes();
                for (int i = 0; i < ch.getLength(); i++) {
                    Node n = ch.item(i);
                    if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                    Element e = (Element) n;
                    if ("Id".equals(e.getLocalName())) {
                        String name = e.getAttribute("value");
                        if (name != null && !name.isBlank()) {
                            out.add(name.trim());
                        }
                    }
                }
            }
        }
        for (BxmlSetsTranslator.EnumeratedSetInfo set : enumeratedSets) {
            out.add(set.setName());
            out.addAll(set.valueNames());
        }
        String machineName = machineEl == null ? "" : machineEl.getAttribute("name");
        if (machineName == null) {
            machineName = "";
        } else {
            machineName = machineName.trim();
        }
        for (int k = 1; k <= maxSetComp; k++) {
            out.add(BxmlComprehensionRegistry.comprehensionSetName(machineName, k));
        }
        return out;
    }

    private String resolveLibSymbol(String ghostName) {
        String aliased = LIB_SYMBOL_ALIASES.getOrDefault(ghostName, ghostName);
        if (symMap.definingFile(aliased) != null) {
            return aliased;
        }
        if (symMap.definingFile(ghostName) != null) {
            return ghostName;
        }
        return null;
    }

    /**
     * Alguns símbolos da lib têm MAIS DE UM ficheiro definidor — sobrecargas por aridade/tipo (ex.
     * {@code becomes_element_of}: 3 args genérico {@code Function<A,B>} em {@code
     * function_functions/}, 2 args concreto {@code integer} em {@code set_functions/}). Antes só se
     * emitia a assinatura do {@code definingFile} "canónico" (o primeiro), então um projeto cujo
     * texto ghost só usasse a OUTRA sobrecarga (ex.: {@code ee :: ELEM} → forma de 2 args) ficava
     * sem a declaração dummy certa — Frama-C via a chamada de 2 args contra a assinatura de 3 args
     * declarada e rejeitava com "incompatible types". Usa {@link
     * AcslLibSymbolDependencyMap#allFilesForSymbol} (já usado para resolução de includes do lado
     * não-ghost) e emite uma declaração dummy por ficheiro — ACSL permite sobrecarga por
     * aridade/tipo, tal como o lado não-ghost já faz com dois blocos {@code axiomatic} separados.
     */
    private String loadDummySignature(String libSymbol, String ghostName) {
        List<String> files = symMap.allFilesForSymbol(libSymbol);
        if (files.isEmpty()) {
            return null;
        }
        List<String> decls = new ArrayList<>();
        for (String rel : files) {
            if (rel == null || rel.isBlank()) {
                continue;
            }
            String text = readLibFile(rel);
            if (text == null || text.isBlank()) {
                continue;
            }
            String signature = extractSignature(text, libSymbol);
            if (signature == null || signature.isBlank()) {
                continue;
            }
            String rewritten = rewriteSignatureForDummy(signature, libSymbol, ghostName);
            if (rewritten != null && !rewritten.isBlank() && !decls.contains(rewritten)) {
                decls.add(rewritten);
            }
        }
        if (decls.isEmpty()) {
            return null;
        }
        return String.join("\n\n        ", decls);
    }

    private static String extractSignature(String libText, String symbolName) {
        String body = stripComments(libText);
        Matcher block = Pattern.compile("axiomatic\\s+\\w+\\s*\\{").matcher(body);
        while (block.find()) {
            int start = block.end();
            int end = findMatchingBrace(body, start - 1);
            if (end < 0) {
                continue;
            }
            String axiomaticBody = body.substring(start, end);
            for (String line : axiomaticBody.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()
                        || trimmed.startsWith("include ")
                        || trimmed.startsWith("axiom ")
                        || trimmed.startsWith("axiomatic ")) {
                    continue;
                }
                Matcher dm = DECL_LINE.matcher(trimmed);
                if (!dm.find()) {
                    continue;
                }
                String decl = dm.group(2).trim();
                decl = stripDefaultBody(decl);
                if (!decl.endsWith(";")) {
                    decl = decl + ";";
                }
                String declName = declarationName(trimmed);
                if (symbolName.equals(declName)) {
                    return (trimmed.startsWith("predicate") ? "predicate " : "logic ") + decl;
                }
            }
        }
        return null;
    }

    private static String rewriteSignatureForDummy(String signature, String libSymbol, String ghostName) {
        String kind;
        String rest;
        if (signature.startsWith("predicate ")) {
            kind = "predicate ";
            rest = signature.substring("predicate ".length());
        } else if (signature.startsWith("logic ")) {
            kind = "logic ";
            rest = signature.substring("logic ".length());
        } else {
            kind = "";
            rest = signature;
        }

        rest = rest.replaceFirst("^" + Pattern.quote(libSymbol) + "\\b", "dummy_" + ghostName);
        rest = rewriteDummyTypes(rest);
        rest = prefixLibCallsInSignature(rest);
        return kind + rest;
    }

    private static String declarationName(String trimmed) {
        int paren = trimmed.indexOf('(');
        if (paren < 0) {
            return null;
        }
        String before = trimmed.substring(0, paren).trim();
        Matcher m = DECL_NAME_BEFORE_PAREN.matcher(before);
        return m.find() ? m.group(1) : null;
    }

    private static String rewriteDummyTypes(String sig) {
        String s = sig;
        s = s.replaceAll("\\b(Function_int_\\w+)\\b", "dummy_$1");
        s = s.replaceAll("\\bRelation_int_\\w+\\b", "DRelation<integer, integer>");
        s = s.replaceAll("\\bFunction<", "DRelation<");
        s = s.replaceAll("\\bRelation<", "DRelation<");
        s = s.replaceAll("\\bSet<", "DSet<");
        s = s.replaceAll("\\bTuple<", "DTuple<");
        return s;
    }

    static String prefixLibCallsInSignature(String sig) {
        AcslLibSymbolDependencyMap map = AcslLibSymbolDependencyMap.instance();
        List<String> symbols = new ArrayList<>(map.allKnownSymbols());
        symbols.sort((a, b) -> Integer.compare(b.length(), a.length()));
        String out = sig;
        for (String sym : symbols) {
            out =
                    out.replaceAll(
                            "(?<!dummy_)(?<!\\\\)\\b" + Pattern.quote(sym) + "\\b",
                            "dummy_" + ghostNameForLibSymbol(sym));
        }
        for (Map.Entry<String, String> alias : LIB_SYMBOL_ALIASES.entrySet()) {
            out =
                    out.replaceAll(
                            "(?<!dummy_)\\b" + Pattern.quote(alias.getKey()) + "\\b",
                            "dummy_" + alias.getValue());
        }
        return out;
    }

    private static String ghostNameForLibSymbol(String libSymbol) {
        return libSymbol;
    }

    /**
     * Tipo da variável {@code dummy_<v>} no axiomatic ghost: alinhado a
     * {@link BxmlMachineVariables#inferVariableLogicTypes} (bloco {@code _variables} do .acsl).
     * Tipos da B2ACSLLib ({@code Set}, {@code Relation_*}, {@code Function_*}) mapeiam para
     * {@code DSet} / {@code DRelation}; escalares e outros tipos não-lib mantêm-se iguais
     * ({@code integer}, {@code boolean}, {@code \list<…>}, …).
     */
    private static String dummyAxiomaticLogicType(String inferred) {
        if (inferred == null || inferred.isBlank()) {
            return "integer";
        }
        String t = inferred.trim();
        if (t.startsWith("\\list")) {
            return t;
        }
        if (isScalarLogicType(t)) {
            return t;
        }
        if (t.startsWith("Set<") && t.endsWith(">")) {
            String elem = t.substring(4, t.length() - 1).trim();
            return "DSet<" + elem + ">";
        }
        // Instanciação genérica Relation<A,B>/Function<A,B> (ver
        // BxmlTypeRegistry#powCartesianProductToAcslRelationType, par escalar-escalar) — o universo
        // ghost isolado não vê "type Relation<A,B> = Set<Tuple<A,B> >;" de types.acsl (front-end
        // isolado, sem símbolos de -acsl-import), por isso precisa do equivalente local DRelation<A,B>.
        if ((t.startsWith("Relation<") || t.startsWith("Function<")) && t.endsWith(">")) {
            String inner = t.substring(t.indexOf('<') + 1, t.length() - 1).trim();
            return "DRelation<" + inner + ">";
        }
        // Nome achatado (ver BxmlTypeRegistry#powCartesianProductToAcslRelationType /
        // #flattenGenericTypeExprToIdentifier) para um codomínio tupla de N>=2 elementos, qualquer
        // mistura de inteiro/booleano — tem múltiplos segmentos com "Tuple" maiúsculo, não pode
        // passar por acslTypeParamName/split("_",2) como o caso simples abaixo; desfaz o
        // achatamento e reconstrói a forma ghost diretamente (DTuple aninhado à esquerda, mesma
        // associação usada no lado real). Ao contrário do lado real, o universo ghost aceita
        // instanciação genérica inline sem precisar de nenhum alias pré-declarado.
        Matcher tupleCodomainMatch = BxmlTypeRegistry.TUPLE_CODOMAIN_RELATION_NAME.matcher(t);
        if (tupleCodomainMatch.matches()) {
            String domain = tupleCodomainMatch.group(1);
            List<String> leaves = new ArrayList<>();
            for (String leaf : tupleCodomainMatch.group(3).split("_")) {
                if (!leaf.isBlank()) leaves.add(leaf);
            }
            String codomain = leaves.get(0);
            for (int i = 1; i < leaves.size(); i++) {
                codomain = "DTuple<" + codomain + ", " + leaves.get(i) + ">";
            }
            return "DRelation<" + domain + ", " + codomain + " >";
        }
        // Espelho do ramo acima para o caso oposto: domínio composto (matriz característica, ex.
        // player_islands_i : PLAYER*ISLAND --> BOOL) + codomínio escalar — ver
        // BxmlTypeRegistry#TUPLE_DOMAIN_RELATION_NAME.
        Matcher tupleDomainMatch = BxmlTypeRegistry.TUPLE_DOMAIN_RELATION_NAME.matcher(t);
        if (tupleDomainMatch.matches()) {
            List<String> domainLeaves = new ArrayList<>();
            for (String leaf : tupleDomainMatch.group(2).split("_")) {
                if (!leaf.isBlank()) domainLeaves.add(leaf);
            }
            String codomain = tupleDomainMatch.group(3);
            String domain = domainLeaves.get(0);
            for (int i = 1; i < domainLeaves.size(); i++) {
                domain = "DTuple<" + domain + ", " + domainLeaves.get(i) + ">";
            }
            String result = "DRelation<" + domain + ", " + codomain + " >";
            return result.replaceAll(">(?=>)", "> ");
        }
        if (t.startsWith("Relation_") || t.startsWith("Function_")) {
            String suffix =
                    t.startsWith("Relation_")
                            ? t.substring("Relation_".length())
                            : t.substring("Function_".length());
            int firstUnderscore = suffix.indexOf('_');
            if (firstUnderscore > 0) {
                String domainToken = suffix.substring(0, firstUnderscore);
                String codomainSuffix = suffix.substring(firstUnderscore + 1);
                String result =
                        "DRelation<"
                                + acslTypeParamName(domainToken)
                                + ", "
                                + dummyTypeFromFlattenedSuffix(codomainSuffix)
                                + ">";
                // Codomínio DSet<...> aninhado produz ">>" adjacente (ex.: "DSet<integer>>") — o
                // lexer do Frama-C tokeniza-o como shift, não dois fechos de genérico (mesmo
                // problema que BxmlTypeRegistry#spaceOutAdjacentClosingAngleBrackets evita do lado
                // real); lookahead para lidar com 2+ fechos consecutivos numa só passagem.
                return result.replaceAll(">(?=>)", "> ");
            }
            return "DRelation<integer, integer>";
        }
        return t;
    }

    /**
     * Parte achatada de um codomínio (ver {@code BxmlTypeRegistry#resolveCartesianPartType}), ex.
     * {@code Set_integer} (de {@code Set<integer>}, um domínio/codomínio ele próprio um conjunto —
     * {@code PLAYER --> POW(ISLAND)}) → {@code DSet<integer>}, recursivo para {@code POW(POW(...))}
     * ({@code Set_Set_integer} → {@code DSet<DSet<integer>>}). Sem isto, {@code split("_", 2)}
     * simples tratava {@code "Set_integer"} inteiro como um único nome de tipo escalar desconhecido,
     * caindo no default silencioso de {@link #acslTypeParamName} ({@code integer}) — {@code
     * DRelation<integer, integer>} errado em vez de {@code DRelation<integer, DSet<integer>>}, só
     * rejeitado tarde pelo front-end isolado com "incompatible types".
     */
    private static String dummyTypeFromFlattenedSuffix(String suffix) {
        if (suffix.startsWith("Set_") && suffix.length() > 4) {
            return "DSet<" + dummyTypeFromFlattenedSuffix(suffix.substring(4)) + ">";
        }
        return acslTypeParamName(suffix);
    }

    private static boolean isScalarLogicType(String t) {
        return switch (t) {
            case "integer", "boolean", "real", "int" -> true;
            default -> false;
        };
    }

    private static String acslTypeParamName(String token) {
        return switch (token.toLowerCase()) {
            case "int", "integer" -> "integer";
            case "bool", "boolean" -> "boolean";
            default -> "integer";
        };
    }

    /**
     * Para cada constante concreta das máquinas em {@code SEES} que apareça como identificador bare
     * (sem prefixo {@code dummy_}) no texto ghost, emite {@code logic integer <NAME>;} no bloco
     * axiomatic.  Estas constantes são declaradas nos ficheiros {@code .acsl} das máquinas vistas
     * (carregados via {@code -acsl-import}), pelo que não são abstraídas com {@code dummy_}.
     */
    private static void appendSeenConcreteConstantDeclarations(
            StringBuilder sb, String ghostText, Element machineEl, Path bxmlDirectory) {
        if (ghostText == null || ghostText.isBlank() || machineEl == null || bxmlDirectory == null) {
            return;
        }
        List<String> seenConstants =
                BxmlSetsTranslator.listSeenMachineConcreteConstantNames(machineEl, bxmlDirectory);
        for (String name : seenConstants) {
            // ghostDummyConcreteRefs already replaced bare <NAME> with dummy_<NAME>
            Pattern pat =
                    Pattern.compile(
                            "(?<![A-Za-z0-9_])dummy_" + Pattern.quote(name) + "(?![A-Za-z0-9_])");
            if (pat.matcher(ghostText).find()) {
                sb.append("        logic integer dummy_").append(name).append(";\n\n");
            }
        }
        // Conjuntos diferidos vistos (ex. Goods_GOODS, declarado em SETS na máquina vista) — o
        // mesmo tratamento acima, mas com o tipo lógico DSet<integer> (não escalar).
        List<String> seenDeferredSets =
                BxmlSetsTranslator.listSeenMachineDeferredSetQualifiedNames(machineEl, bxmlDirectory);
        for (String name : seenDeferredSets) {
            Pattern pat =
                    Pattern.compile(
                            "(?<![A-Za-z0-9_])dummy_" + Pattern.quote(name) + "(?![A-Za-z0-9_])");
            if (pat.matcher(ghostText).find()) {
                sb.append("        logic DSet<integer> dummy_").append(name).append(";\n\n");
            }
        }
        // Conjuntos diferidos da PRÓPRIA máquina (ex. RulerOfTheSeas_ISLAND, já qualificados por
        // ctx.enumeratedSetRenames() antes do texto chegar aqui — ver buildEnumeratedSetRenamesWithSees,
        // que apesar do nome também funde buildDeferredSetRenames): mesmo tratamento, sem o qual o
        // front-end isolado rejeita com "unbound logic variable" (a referência já vem prefixada
        // dummy_ por ghostDummyConcreteRefs, mas nunca é DECLARADA).
        for (String name : BxmlSetsTranslator.buildDeferredSetRenames(machineEl).values()) {
            Pattern pat =
                    Pattern.compile(
                            "(?<![A-Za-z0-9_])dummy_" + Pattern.quote(name) + "(?![A-Za-z0-9_])");
            if (pat.matcher(ghostText).find()) {
                sb.append("        logic DSet<integer> dummy_").append(name).append(";\n\n");
            }
        }
    }

    private static void appendDummyConcreteConstantDeclarations(
            StringBuilder sb, Element machineEl, BxmlTranslateContext ctx) {
        if (machineEl == null || ctx == null) {
            return;
        }
        Element block = BxmlDomUtils.firstChildElement(machineEl, "Concrete_Constants");
        if (block == null) {
            return;
        }
        NodeList ch = block.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            if (!"Id".equals(e.getLocalName())) continue;
            String name = e.getAttribute("value");
            if (name == null || name.isBlank()) continue;
            String logicType = "integer";
            String tr = e.getAttribute("typref");
            if (!tr.isBlank()) {
                try {
                    int typref = Integer.parseInt(tr.trim());
                    String t = ctx.types().acslVariableLogicTypeFromTypref(typref);
                    if (t != null && !t.isBlank()) {
                        logicType = t;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            sb.append("        logic ").append(logicType).append(" dummy_").append(name.trim()).append(";\n\n");
        }
    }

    private static String readLibFile(String relPath) {
        Path disk = B2AcslLibraryPaths.devRoot().resolve(relPath);
        if (Files.isRegularFile(disk)) {
            try {
                return Files.readString(disk);
            } catch (IOException ignored) {
            }
        }
        try (InputStream in =
                DummyGhostAxiomaticBuilder.class.getResourceAsStream(
                        B2AcslLibraryPaths.classpathResource(relPath))) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static String stripComments(String text) {
        return text.replaceAll("/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");
    }

    private static int findMatchingBrace(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** Remove corpo default {@code = …} ou {@code =} pendente (declaração multilinha na lib). */
    private static String stripDefaultBody(String decl) {
        int eq = indexOfTopLevelEquals(decl);
        if (eq >= 0) {
            decl = decl.substring(0, eq).trim();
        }
        int semi = decl.indexOf(';');
        if (semi >= 0) {
            decl = decl.substring(0, semi + 1);
        }
        return decl.replaceAll("\\s*=\\s*$", "").trim();
    }

    private static int indexOfTopLevelEquals(String decl) {
        int depth = 0;
        for (int i = 0; i < decl.length(); i++) {
            char c = decl.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == '=' && depth == 0) {
                if (i + 1 < decl.length() && decl.charAt(i + 1) == '=') {
                    continue;
                }
                return i;
            }
        }
        return -1;
    }

    private static String joinLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line != null && !line.isBlank()) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(line);
            }
        }
        return sb.toString();
    }

}
