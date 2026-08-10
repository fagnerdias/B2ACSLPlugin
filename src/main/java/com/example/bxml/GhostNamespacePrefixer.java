package com.example.bxml;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reescrita de namespace para o universo {@code dummy_ghost}: prefixa valores de enum,
 * conjuntos enumerados/globais, funções da lib ACSL, tipos de função, e uncurry de constantes
 * abstratas aplicadas — a última etapa antes de {@code ghost_operations.ci} ficar pronto para o
 * Frama-C isolado o analisar. Extraído de {@code GhostOperationsCiGenerator} (WMC=746, o maior do
 * projeto) por extract-class puro: nenhuma linha de lógica mudou, só o arquivo em que vive.
 */
final class GhostNamespacePrefixer {

    private GhostNamespacePrefixer() {}

    /**
     * Em contratos ghost ({@code ghost_operations.ci}), tipos de função na biblioteca ACSL aparecem
     * com prefixo {@code dummy_} (ex.: {@code Function_int_int} → {@code dummy_Function_int_int}),
     * alinhados ao alias declarado em {@link DummyGhostAxiomaticBuilder} dentro de {@code dummy_ghost}.
     *
     * <p>Apenas reescreve o token quando não está já prefixado por {@code dummy_}.
     */
    private static String prefixFunctionTypesForGhost(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String s = text.replaceAll("(?<!dummy_)\\bFunction_(\\w+)\\b", "dummy_Function_$1");
        // Set<A> → DSet<A>, Tuple<A,B> → DTuple<A,B> no mundo ghost
        s = s.replaceAll("(?<!D)\\bSet<", "DSet<");
        s = s.replaceAll("(?<!D)\\bTuple<", "DTuple<");
        // Instanciação genérica Relation<A,B>/Function<A,B> → DRelation<A,B> (mesmo alvo do lado
        // achatado — ver DummyGhostAxiomaticBuilder#dummyAxiomaticLogicType): o front-end ghost
        // isolado não vê type Relation<A,B>/Function<A,B> de types.acsl.
        s = s.replaceAll("(?<!D)\\bRelation<", "DRelation<");
        s = s.replaceAll("(?<!D)\\bFunction<", "DRelation<");
        return s;
    }

    /**
     * Prefixa {@code set_comprehension_<k>} com {@code dummy_} (alinhado às declarações
     * {@code logic DSet<integer> dummy_set_comprehension_k} em {@link DummyGhostAxiomaticBuilder}).
     */
    static String prefixSetComprehensionsForGhost(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.replaceAll(
                "(?<!dummy_)\\b((?:\\w+__)?set_comprehension_\\d+)\\b", "dummy_$1");
    }

    /**
     * Prefixa cada variável abstrata com {@code dummy_} sem usar {@code \\old(...)}: aplicável a
     * contratos ghost de operações que NÃO mutam o estado abstrato (ex.: corpo {@code ANY_Sub} sem
     * atribuição a variáveis abstratas — {@code dummy_<v>} é igual a {@code \\old(dummy_<v>)} aqui).
     */
    static String prefixAbstractVarsForGhost(String text, Set<String> abstractVars) {
        if (text == null || text.isEmpty() || abstractVars == null || abstractVars.isEmpty()) {
            return text;
        }
        List<String> names = new ArrayList<>(abstractVars);
        names.sort((a, b) -> Integer.compare(b.length(), a.length()));
        String out = text;
        for (String v : names) {
            out =
                    out.replaceAll(
                            "(?<!dummy_)\\b" + Pattern.quote(v) + "\\b", "dummy_" + v);
        }
        return out;
    }

    /**
     * Reescreve uma cláusula {@code ensures} derivada de {@code ANY_Sub} para o universo
     * {@code dummy_ghost}: prefixa tipos de função, funções da lib, conjuntos em compreensão,
     * constantes concretas e variáveis abstratas com {@code dummy_}; substitui o conjunto
     * {@code NAT} por {@code dummy_NAT}; e converte parâmetros de saída do tipo função
     * (modelados como {@code int *} em C) para {@code dummy_Function_*} via
     * {@code dummy_array_to_function(...)} ao serem comparados, com {@code equals}, à variável da
     * cláusula {@code WHERE} (ex.: {@code equals(ee, xx)} → {@code equals(dummy_array_to_function(ee, len), xx)}).
     *
     * <p>Não envolve em {@code \\old(...)} porque a operação não muta variáveis abstratas (caso
     * típico de {@code get OUT ee, ii := ANY ... THEN ...}).
     */
    static String rewriteAnySubEnsureForGhost(
            String text,
            Set<String> abstractVars,
            Set<String> concreteConstantNames,
            Element operation,
            Element anySub,
            BxmlTranslateContext ctx,
            Map<String, List<String>> abstractConstParams) {
        String s = prefixFunctionTypesForGhost(text);
        s = uncurryAbstractConstantApplications(s, abstractConstParams);
        s = fixForallQuantifierTypeAfterUncurry(s, abstractConstParams);
        s = prefixAcslLibFunctionsForGhost(s);
        s = prefixEnumValuesForGhost(s, ctx.enumValueRenames());
        s = prefixGlobalLogicSetsForGhost(s);
        s = prefixSetComprehensionsForGhost(s);
        s = GhostOperationsCiGenerator.ghostDummyConcreteRefs(s, concreteConstantNames);
        s = prefixAbstractVarsForGhost(s, abstractVars);
        s = wrapOutputArraysInEquals(s, operation, anySub, ctx, concreteConstantNames);
        s = wrapEqualsForRelationEqualities(s);
        s = dereferenceScalarOutputParams(s, operation);
        return prefixAcslLibFunctionsForGhost(s);
    }

    /**
     * Funções na ACSL_Lib (e respetivas {@code dummy_*}) cujo valor é uma relação/função; usadas
     * por {@link #wrapEqualsForRelationEqualities} para detetar comparações onde {@code ==} deve
     * ser substituído por {@code equals(...)} (no universo {@code dummy_ghost}).
     */
    private static final Set<String> RELATION_TYPED_FUNCTIONS_GHOST = Set.of(
            "domain_restriction",
            "dummy_domain_restriction",
            "dummy_list_to_function",
            "dummy_array_to_function",
            "list_to_function",
            "array_to_function");

    /**
     * Reescreve {@code <relCall> == <relCall>} para {@code equals(<relCall>, <relCall>)} (set/relation
     * em ACSL não suporta {@code ==} estrutural — ver {@code ACSL_Lib/set_functions/equals.acsl}).
     */
    private static String wrapEqualsForRelationEqualities(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String s = text;
        boolean changed = true;
        while (changed) {
            changed = false;
            int i = 0;
            while (i < s.length()) {
                int callStart = findFunctionCallStart(s, i, RELATION_TYPED_FUNCTIONS_GHOST);
                if (callStart < 0) break;
                int openParen = s.indexOf('(', callStart);
                if (openParen < 0) {
                    i = callStart + 1;
                    continue;
                }
                int closeLeft = GhostOperationsCiGenerator.findMatchingClose(s, openParen);
                if (closeLeft < 0) {
                    i = callStart + 1;
                    continue;
                }
                int k = closeLeft + 1;
                while (k < s.length() && Character.isWhitespace(s.charAt(k))) k++;
                if (k + 1 >= s.length() || s.charAt(k) != '=' || s.charAt(k + 1) != '=') {
                    i = closeLeft + 1;
                    continue;
                }
                int m = k + 2;
                while (m < s.length() && Character.isWhitespace(s.charAt(m))) m++;
                int rightStart = findFunctionCallStart(s, m, RELATION_TYPED_FUNCTIONS_GHOST);
                if (rightStart != m) {
                    i = closeLeft + 1;
                    continue;
                }
                int rightOpen = s.indexOf('(', rightStart);
                if (rightOpen < 0) {
                    i = closeLeft + 1;
                    continue;
                }
                int closeRight = GhostOperationsCiGenerator.findMatchingClose(s, rightOpen);
                if (closeRight < 0) {
                    i = closeLeft + 1;
                    continue;
                }
                String left = s.substring(callStart, closeLeft + 1);
                String right = s.substring(rightStart, closeRight + 1);
                s =
                        s.substring(0, callStart)
                                + "equals(" + left + ", " + right + ")"
                                + s.substring(closeRight + 1);
                changed = true;
                break;
            }
        }
        return s;
    }

    /** Posição do início do identificador de uma chamada de função em {@code names} a partir de {@code from}. */
    private static int findFunctionCallStart(String s, int from, Set<String> names) {
        int n = s.length();
        for (int i = from; i < n; i++) {
            char c = s.charAt(i);
            if (!(c == '_' || Character.isLetter(c))) continue;
            if (i > 0) {
                char prev = s.charAt(i - 1);
                if (prev == '_' || Character.isLetterOrDigit(prev)) continue;
            }
            int j = i;
            while (j < n) {
                char cj = s.charAt(j);
                if (cj == '_' || Character.isLetterOrDigit(cj)) {
                    j++;
                } else {
                    break;
                }
            }
            if (j >= n || s.charAt(j) != '(') {
                continue;
            }
            String name = s.substring(i, j);
            if (names.contains(name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Chamadas a {@code sigma_funcNN}/{@code lambda_funcNN}/{@code union_funcNN}/{@code
     * inter_funcNN} (nomes sintéticos de {@code SigmaFunctionRegistry}/{@code
     * LambdaFunctionRegistry}/{@code UnionInterFunctionRegistry}, sempre {@code prefixo + 2
     * dígitos}) — precisam do MESMO prefixo {@code ghost_} que {@link #renameGhostAxiomaticBlock}
     * já aplica à DECLARAÇÃO destas funções dentro do bloco axiomatic do registo. Sem isto, texto
     * ghost construído FORA desse bloco (ex.: a asserção de {@code ghost__attackplayer}, que chama
     * {@code sigma_func01}/{@code sigma_func02} tal como o {@code ensures} real que a originou)
     * continua a referenciar o nome NÃO renomeado — "unbound logic function sigma_func02" na fase
     * {@code -acsl-import} original (aqui a isolação do {@code .ci} SE aplica: só descoberto ao
     * corrigir {@link #renameGhostAxiomaticBlock} e re-rodar RulerOfTheSeas). {@code (?&lt;!ghost_)}
     * evita prefixar duas vezes texto que já veio de dentro do bloco já renomeado.
     */
    private static final Pattern GENERALIZED_QUANTIFIER_CALL_NAME =
            Pattern.compile("(?<!ghost_)\\b(?:sigma_func|lambda_func|union_func|inter_func)\\d{2}\\b");

    /**
     * Funções da {@code ACSL_Lib} usadas nos contratos ghost: prefixo {@code dummy_} alinhado com
     * {@link DummyGhostAxiomaticBuilder}; mais o prefixo {@code ghost_} em chamadas às funções
     * geradoras de quantificador generalizado (ver {@link #GENERALIZED_QUANTIFIER_CALL_NAME}).
     */
    static String prefixAcslLibFunctionsForGhost(String text) {
        String prefixed = DummyGhostAxiomaticBuilder.prefixLibCallsInSignature(text);
        return GENERALIZED_QUANTIFIER_CALL_NAME.matcher(prefixed).replaceAll("ghost_$0");
    }

    /**
     * Renomeia o wrapper {@code axiomatic <name> { ... }} PARA {@code axiomatic ghost_<name> { ... }}
     * — E TAMBÉM cada {@code axiom}/{@code lemma}/{@code admit lemma} DECLARADO dentro do bloco
     * (ex. {@code axiom sigma_func01_empty:} → {@code axiom ghost_sigma_func01_empty:}).
     * {@code LambdaFunctionRegistry}/{@code SigmaFunctionRegistry}/{@code UnionInterFunctionRegistry}
     * usam nomes FIXOS (não qualificados por máquina, ao contrário de {@code
     * BxmlComprehensionRegistry}/{@code BxmlMachineVariables}, cujos blocos já levam o nome da
     * máquina) — partilhados entre esta cópia ghost e a cópia REAL emitida por {@code
     * AcslGenerator}. Quando ambas têm conteúdo não-vazio no MESMO projeto (ex.: {@code Attack} usa
     * {@code sigma_func01}/{@code sigma_func02} tanto no ensures real como no ghost), o KERNEL do
     * Frama-C — não o parser do {@code -acsl-import}, uma fase ainda mais tardia — rejeita com
     * {@code Failure: trying to register twice property 'axiomatic <name>'} (bloco) OU
     * {@code 'axiom <name>'} (axioma/lema individual, um erro DIFERENTE encontrado só depois de
     * corrigir o primeiro — cada axioma/lema é a SUA PRÓPRIA "property" com estado de prova
     * rastreado, tal como o bloco): nomes de propriedade são globalmente únicos mesmo entre o
     * front-end isolado do {@code .ci} e o ficheiro {@code -acsl-import} principal, apesar de tudo
     * o resto nesse front-end estar isolado (dummy-prefixado, {@code DSet} em vez de {@code Set},
     * …). Os nomes de SÍMBOLO ({@code logic}/{@code predicate}, ex. {@code sigma_func01} em si)
     * também precisam do MESMO tratamento — ao contrário do que uma nota anterior aqui assumia
     * ("podem coexistir redeclarados de forma idêntica entre os dois front-ends sem erro"): essa
     * conclusão vinha de testar só a fase {@code -acsl-import} original (isolamento genuíno entre
     * {@code .ci} e {@code .acsl}); a fase SEGUINTE do WP (ver {@link B2ACSLPipeline}) reimprime
     * TUDO junto num único {@code merged_code.c} plano e reanalisa esse ficheiro do zero — sem
     * fronteira de front-end nenhuma nesse ponto, dois {@code logic integer sigma_func01(...)}
     * idênticos (um do lado real, outro daqui) colidem com "logic function sigma_func01 is already
     * declared with the same profile". Só exposto ao correr RulerOfTheSeas (primeiro exemplo cujo
     * SIGMA ghost e real ambos sobrevivem até o merge da fase WP). Renomeia cada nome declarado via
     * {@code logic ... NOME(} / {@code predicate NOME(} para {@code ghost_NOME}, por fronteira de
     * palavra, tanto na própria declaração quanto em toda chamada dentro deste MESMO bloco — seguro
     * porque {@code LambdaFunctionRegistry}/{@code SigmaFunctionRegistry}/
     * {@code UnionInterFunctionRegistry} usam nomes sintéticos ({@code sigma_func01}, …) que nunca
     * colidem com identificadores B reais.
     */
    static String renameGhostAxiomaticBlock(String block, String name) {
        if (block == null) return null;
        String renamed =
                block.replaceFirst(
                        "(?m)^axiomatic\\s+" + java.util.regex.Pattern.quote(name) + "\\s*\\{",
                        "axiomatic ghost_" + name + " {");
        renamed = renamed.replaceAll(
                "(?m)^(\\s*)((?:admit\\s+)?(?:axiom|lemma))(\\s+)([A-Za-z_]\\w*)",
                "$1$2$3ghost_$4");
        java.util.regex.Matcher declMatcher =
                java.util.regex.Pattern.compile("(?m)^\\s*(?:logic\\s+\\S.*?|predicate)\\s+([A-Za-z_]\\w*)\\s*\\(")
                        .matcher(renamed);
        java.util.LinkedHashSet<String> declaredNames = new java.util.LinkedHashSet<>();
        while (declMatcher.find()) {
            declaredNames.add(declMatcher.group(1));
        }
        for (String declared : declaredNames) {
            renamed = renamed.replaceAll(
                    "(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(declared) + "(?![A-Za-z0-9_])",
                    "ghost_" + declared);
        }
        return renamed;
    }

    /**
     * Adapta um bloco {@code axiomatic} gerado por {@link LambdaFunctionRegistry}/{@link
     * SigmaFunctionRegistry} para o universo {@code dummy_ghost}: mesmo tratamento que uma linha de
     * {@code ensures} ghost normal (ver {@link #rewriteAnySubEnsureForGhost}), já que este bloco não
     * passa pelo pipeline por-linha (é uma declaração à parte, não uma cláusula de contrato).
     */
    static String prefixLocalAxiomaticBlockForGhost(
            String rawBlock, BxmlTranslateContext ctx, Set<String> concreteConstantNames,
            Set<String> abstractVars) {
        String s = stripCommentLines(rawBlock);
        s = prefixAcslLibFunctionsForGhost(s);
        s = prefixEnumValuesForGhost(s, ctx.enumValueRenames());
        s = prefixGlobalLogicSetsForGhost(s);
        s = prefixSetComprehensionsForGhost(s);
        s = GhostOperationsCiGenerator.ghostDummyConcreteRefs(s, concreteConstantNames);
        s = prefixAbstractVarsForGhost(s, abstractVars);
        // Set<A> não existe neste front-end isolado — só o tipo-sombra DSet<A> (ver
        // DummyGhostAxiomaticBuilder#rewriteDummyTypes, mesma substituição). \list<T> é builtin
        // nativo da ACSL (não da lib), por isso não precisa de sombra equivalente.
        return s.replaceAll("\\bSet<", "DSet<");
    }

    /**
     * Remove linhas que são inteiramente um comentário de uma linha só (uma linha por comentário —
     * ver {@link SigmaFunctionRegistry}) antes de embrulhar um bloco no comentário de anotação
     * {@code .ci}: comentários ACSL não aninham, e o texto livre de um TODO pode colidir com o regex
     * de {@link DummyGhostAxiomaticBuilder#prefixLibCallsInSignature} (ex.: fronteira de palavra
     * ASCII não trata acentos — "domínio" casava com o símbolo da lib {@code dom}).
     */
    private static String stripCommentLines(String text) {
        if (text == null || text.isEmpty()) return text;
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.startsWith("/*") && t.endsWith("*/") && t.length() >= 4) continue;
            out.append(lines[i]);
            if (i < lines.length - 1) out.append("\n");
        }
        return out.toString();
    }

    /**
     * Valores enumerados → {@code dummy_<Maquina>__<Valor>} (ex. {@code dummy_Airlock__ACQ}), alinhado a
     * {@link DummyGhostAxiomaticBuilder} e a {@link BxmlSetsTranslator#buildEnumRenamesWithSees}.
     */
    static String prefixEnumValuesForGhost(
            String text, Map<String, String> enumValueRenames) {
        if (text == null || text.isEmpty() || enumValueRenames == null || enumValueRenames.isEmpty()) {
            return text;
        }
        List<Map.Entry<String, String>> entries = new ArrayList<>(enumValueRenames.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue().length(), a.getValue().length()));
        String out = text;
        for (Map.Entry<String, String> e : entries) {
            String bName = e.getKey();
            if (bName == null || bName.isBlank()) {
                continue;
            }
            String acslName = e.getValue();
            String dummyVal =
                    acslName != null && !acslName.isBlank() ? "dummy_" + acslName : "dummy_" + bName;
            if (acslName != null && !acslName.isBlank()) {
                out =
                        out.replaceAll(
                                "(?<!dummy_)\\b" + Pattern.quote(acslName) + "\\b",
                                Matcher.quoteReplacement(dummyVal));
            }
            out =
                    out.replaceAll(
                            "(?<!dummy_)\\b" + Pattern.quote(bName) + "\\b",
                            Matcher.quoteReplacement(dummyVal));
        }
        return out;
    }

    /**
     * Reescreve {@code function_apply(NAME, couple(...))} → {@code NAME(arg1, arg2, ...)} para
     * constantes abstratas declaradas como funções lógicas (não como relações B).
     */
    private static String uncurryAbstractConstantApplications(
            String text, Map<String, List<String>> abstractConstParams) {
        if (text == null || abstractConstParams == null || abstractConstParams.isEmpty()) {
            return text;
        }
        String result = text;
        for (String prefix : new String[]{"function_apply(", "dummy_function_apply("}) {
            StringBuilder sb = new StringBuilder();
            int idx = 0;
            while (idx < result.length()) {
                int callStart = result.indexOf(prefix, idx);
                if (callStart < 0) {
                    sb.append(result.substring(idx));
                    break;
                }
                if (callStart > 0) {
                    char before = result.charAt(callStart - 1);
                    if (Character.isLetterOrDigit(before) || before == '_') {
                        sb.append(result.charAt(idx));
                        idx = callStart + 1;
                        continue;
                    }
                }
                int openParen = callStart + prefix.length() - 1;
                int closeParen = GhostOperationsCiGenerator.findMatchingClose(result, openParen);
                if (closeParen < 0) {
                    sb.append(result.substring(idx, openParen + 1));
                    idx = openParen + 1;
                    continue;
                }
                String argsStr = result.substring(openParen + 1, closeParen);
                int commaIdx = findTopLevelCommaInText(argsStr, 0);
                if (commaIdx < 0) {
                    sb.append(result.substring(idx, closeParen + 1));
                    idx = closeParen + 1;
                    continue;
                }
                String constName = argsStr.substring(0, commaIdx).trim();
                if (!abstractConstParams.containsKey(constName)) {
                    sb.append(result.substring(idx, closeParen + 1));
                    idx = closeParen + 1;
                    continue;
                }
                String coupleExpr = argsStr.substring(commaIdx + 1).trim();
                List<String> flatArgs = extractCoupleArgs(coupleExpr);
                sb.append(result.substring(idx, callStart));
                sb.append(constName).append("(").append(String.join(", ", flatArgs)).append(")");
                idx = closeParen + 1;
            }
            result = sb.toString();
        }
        return result;
    }

    private static List<String> extractCoupleArgs(String expr) {
        String trimmed = expr.trim();
        boolean isCouple = trimmed.startsWith("dummy_couple(") || trimmed.startsWith("couple(");
        if (!isCouple) {
            return new ArrayList<>(List.of(trimmed));
        }
        int openParen = trimmed.indexOf('(');
        int closeParen = GhostOperationsCiGenerator.findMatchingClose(trimmed, openParen);
        if (closeParen < 0 || closeParen != trimmed.length() - 1) {
            return new ArrayList<>(List.of(trimmed));
        }
        String inner = trimmed.substring(openParen + 1, closeParen);
        int comma = findTopLevelCommaInText(inner, 0);
        if (comma < 0) {
            return new ArrayList<>(List.of(trimmed));
        }
        List<String> result = new ArrayList<>(extractCoupleArgs(inner.substring(0, comma).trim()));
        result.add(inner.substring(comma + 1).trim());
        return result;
    }

    private static int findTopLevelCommaInText(String text, int start) {
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) return i;
        }
        return -1;
    }

    /**
     * Quando um quantificador {@code \\forall integer VAR} é seguido de {@code VAR == NAME(...)}
     * onde NAME é uma constante abstrata que retorna boolean, muda para {@code \\forall boolean VAR}.
     */
    private static String fixForallQuantifierTypeAfterUncurry(
            String text, Map<String, List<String>> abstractConstParams) {
        if (text == null || abstractConstParams.isEmpty()) return text;
        java.util.regex.Pattern forallPat =
                java.util.regex.Pattern.compile("\\\\forall\\s+integer\\s+(\\w+)\\b");
        java.util.regex.Matcher m = forallPat.matcher(text);
        while (m.find()) {
            String varName = m.group(1);
            for (String name : abstractConstParams.keySet()) {
                if (text.contains(varName + " == " + name + "(")
                        || text.contains(varName + "==" + name + "(")) {
                    text = text.replaceFirst(
                            "\\\\forall\\s+integer\\s+" + java.util.regex.Pattern.quote(varName) + "\\b",
                            "\\\\forall boolean " + varName);
                    m = forallPat.matcher(text);
                    break;
                }
            }
        }
        return text;
    }

    /**
     * Conjuntos enumerados B → {@code dummy_<Maquina>__<Conjunto>} nos {@code ensures} ghost (ex.
     * {@code belongs(v, PRESSURE)} → {@code belongs(v, dummy_Airlock_pressure_bs__PRESSURE)}).
     */
    static String prefixEnumeratedSetsForGhost(
            String text, List<BxmlSetsTranslator.EnumeratedSetInfo> enumeratedSets) {
        if (text == null || text.isEmpty() || enumeratedSets == null || enumeratedSets.isEmpty()) {
            return text;
        }
        List<BxmlSetsTranslator.EnumeratedSetInfo> sorted = new ArrayList<>(enumeratedSets);
        sorted.sort(
                (a, b) ->
                        Integer.compare(
                                b.setName().length(), a.setName().length()));
        String out = text;
        for (BxmlSetsTranslator.EnumeratedSetInfo set : sorted) {
            String dummySet = set.dummySetLogicName();
            String setName = set.setName();
            if (setName == null || setName.isBlank()) {
                continue;
            }
            out =
                    out.replaceAll(
                            "(?<!dummy_)\\b" + Pattern.quote(setName) + "\\b",
                            Matcher.quoteReplacement(dummySet));
            String acslSetRef = BxmlSetsTranslator.enumeratedSetAcslName(set.machineName(), setName);
            out =
                    out.replaceAll(
                            "(?<!dummy_)\\b" + Pattern.quote(acslSetRef) + "\\b",
                            Matcher.quoteReplacement(dummySet));
        }
        return out;
    }

    /**
     * Conjuntos globais da ACSL_Lib ({@code NAT}, {@code NAT1}, {@code INT}, {@code BOOL}) →
     * variáveis lógicas {@code dummy_NAT} / {@code dummy_NAT1} / {@code dummy_INT} /
     * {@code dummy_BOOL} declaradas em {@link DummyGhostAxiomaticBuilder}.
     */
    static String prefixGlobalLogicSetsForGhost(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out =
                text.replaceAll(
                        "(?<!dummy_)\\bNAT1\\b", Matcher.quoteReplacement("dummy_NAT1"));
        out =
                out.replaceAll(
                        "(?<!dummy_)\\bNAT\\b", Matcher.quoteReplacement("dummy_NAT"));
        out =
                out.replaceAll(
                        "(?<!dummy_)\\bINT\\b", Matcher.quoteReplacement("dummy_INT"));
        return out.replaceAll(
                "(?<!dummy_)\\bBOOL\\b", Matcher.quoteReplacement("dummy_BOOL"));
    }

    /**
     * Em ACSL os parâmetros de saída são modelados como ponteiros C ({@code int *}); os escalares
     * (não-relação/função) precisam de desreferenciamento ao serem usados como valor (ex.:
     * {@code ii == \length(...)} → {@code *ii == \length(...)}).
     *
     * <p>Aplica-se apenas dentro de cláusulas {@code ensures} ghost geradas a partir de
     * {@code ANY_Sub}, onde a referência ao output é por valor.
     */
    static String dereferenceScalarOutputParams(String text, Element operation) {
        if (text == null || text.isEmpty() || operation == null) {
            return text;
        }
        Element outEl = BxmlDomUtils.firstChildElement(operation, "Output_Parameters");
        if (outEl == null) return text;
        String result = text;
        NodeList ch = outEl.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            if (!"Id".equals(e.getLocalName())) continue;
            String name = e.getAttribute("value");
            if (name == null || name.isBlank()) continue;
            name = name.trim();
            if (outputParamIsFunctionTyped(operation, name)) continue;
            result =
                    result.replaceAll(
                            "(?<!\\*)\\b" + Pattern.quote(name) + "\\b",
                            Matcher.quoteReplacement("*" + name));
        }
        return result;
    }

    /**
     * Para cada par {@code (output_pointer, anySubVar)}, onde o {@code output_pointer} é um
     * parâmetro de saída de tipo função (relação em B) e {@code anySubVar} é uma variável quantificada
     * de função na cláusula {@code WHERE}, reescreve as comparações
     * {@code equals(output_pointer, anySubVar)} (e simétrica) para envolver o ponteiro com
     * {@code dummy_array_to_function(<param>, <len>)}, onde {@code <len>} provém do intervalo do
     * domínio em {@code WHERE}.
     */
    private static String wrapOutputArraysInEquals(
            String text,
            Element operation,
            Element anySub,
            BxmlTranslateContext ctx,
            Set<String> concreteConstantNames) {
        if (text == null || text.isEmpty() || operation == null || anySub == null || ctx == null) {
            return text;
        }
        List<String> outputs = GhostContractPredicates.listOutputParameterNames(operation);
        if (outputs.isEmpty()) return text;
        Element vars = BxmlDomUtils.firstChildElement(anySub, "Variables");
        if (vars == null) return text;
        Element predWrapper = BxmlDomUtils.firstChildElement(anySub, "Pred");
        Element predRoot = predWrapper != null ? BxmlDomUtils.firstSubChild(predWrapper) : null;
        if (predRoot == null) return text;
        String result = text;
        for (Element v : BxmlDomUtils.directExpChildren(vars)) {
            if (!"Id".equals(v.getLocalName())) continue;
            String qName = v.getAttribute("value");
            if (qName == null) continue;
            qName = qName.trim();
            if (qName.isBlank()) continue;
            Element domain =
                    GhostDomainRestrictionRewriter.partialFunctionDomainFromPreconditionInPred(
                            predRoot, qName);
            if (domain == null) continue;
            String len =
                    GhostDomainRestrictionRewriter.arrayLengthAcslFromDomain(
                            domain, ctx, concreteConstantNames);
            if (len == null || len.isBlank()) continue;
            for (String out : outputs) {
                if (!outputParamIsFunctionTyped(operation, out)) continue;
                String wrapped = "dummy_array_to_function(" + out + ", " + len + ")";
                String escOut = Pattern.quote(out);
                String escQ = Pattern.quote(qName);
                result =
                        result.replaceAll(
                                "equals\\(\\s*" + escOut + "\\s*,\\s*" + escQ + "\\s*\\)",
                                Matcher.quoteReplacement(
                                        "equals(" + wrapped + ", " + qName + ")"));
                result =
                        result.replaceAll(
                                "equals\\(\\s*" + escQ + "\\s*,\\s*" + escOut + "\\s*\\)",
                                Matcher.quoteReplacement(
                                        "equals(" + qName + ", " + wrapped + ")"));
            }
        }
        return result;
    }

    /**
     * {@code true} se o parâmetro de saída {@code paramName} for um tipo função/relação (modelado
     * como {@code int *} em C); falso para escalares ({@code int}).
     */
    private static boolean outputParamIsFunctionTyped(Element operation, String paramName) {
        if (operation == null || paramName == null || paramName.isBlank()) {
            return false;
        }
        Element outEl = BxmlDomUtils.firstChildElement(operation, "Output_Parameters");
        if (outEl == null) return false;
        NodeList ch = outEl.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            if (!"Id".equals(e.getLocalName())) continue;
            String name = e.getAttribute("value");
            if (name == null || !paramName.equals(name.trim())) continue;
            String tr = e.getAttribute("typref");
            if (tr == null || tr.isBlank()) return false;
            try {
                int id = Integer.parseInt(tr.trim());
                Element machine = GhostParamTypeResolver.findAncestorMachine(operation);
                if (machine == null) return false;
                BxmlTypeRegistry types = BxmlTypeRegistry.fromMachine(machine);
                String acsl = types.acslVariableLogicTypeFromTypref(id);
                return acsl != null
                        && (acsl.startsWith("Relation_")
                                || acsl.startsWith("Function_")
                                || acsl.startsWith("Relation<")
                                || acsl.startsWith("Function<"));
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

}
