package com.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Monomorphiza declarações ACSL genéricas em {@code merged_code.c}.
 *
 * <p>Cada bloco {@code axiomatic} com declarações polimórficas (parâmetros de tipo {@code <A>},
 * {@code <A,B>}, {@code <A,B,C>}) é substituído por uma ou mais versões concretas — uma por
 * combinação de tipos usada na especificação.
 *
 * <p>Exemplos de transformações:
 * <pre>
 *   predicate belongs&lt;A&gt;(A xx, Set&lt;A&gt; ss)
 *   →  predicate belongs(integer xx, Set&lt;integer&gt; ss)       (A=integer)
 *      predicate belongs(Set&lt;integer&gt; xx, Set&lt;Set&lt;integer&gt;&gt; ss)  (A=Set&lt;integer&gt;)
 *
 *   axiom fst_couple&lt;A, B&gt;: \forall A x, B y; first(couple(x,y)) == x;
 *   →  axiom fst_couple: \forall integer x, integer y; first(couple(x,y)) == x;
 * </pre>
 */
public final class SpecificationAxiomaticInstantiator {

    /** Bloco {@code axiomatic new_types} mantém-se polimórfico (é o construtor de tipos). */
    private static final String NEW_TYPES_MARKER = "axiomatic new_types";

    /**
     * Identifica o sufixo genérico de tipo numa declaração lógica / axioma:
     * {@code belongs<A>(}, {@code couple<A, B>(}, {@code def_empty<A>:}.
     * O lookahead exige {@code (} ou {@code :} após {@code >}.
     */
    private static final Pattern GENERIC_DECL_SUFFIX = Pattern.compile(
            "\\b(\\w+)(<\\s*[A-Z](?:\\s*,\\s*[A-Z])*\\s*>)\\s*(?=[:(])");

    /**
     * Analisa identificadores de tipo legados no formato underscore:
     * {@code Relation_integer_boolean}, {@code Function_int_int}, etc.
     * Captura: grupo 1 = tipo A, grupo 2 = tipo B.
     * Quantificador lazy no grupo 1 para separar corretamente (ex.: {@code integer_boolean} → A=integer, B=boolean).
     */
    private static final Pattern LEGACY_PAIR_TYPE = Pattern.compile(
            "^(?:Relation|Function)_([a-z][a-z0-9_]*?)_([a-z][a-z0-9_]*)$");

    /** Ver {@link com.example.bxml.BxmlTypeRegistry#TUPLE_CODOMAIN_RELATION_NAME}. */
    private static final Pattern TUPLE_CODOMAIN_RELATION_NAME =
            com.example.bxml.BxmlTypeRegistry.TUPLE_CODOMAIN_RELATION_NAME;

    /** Ver {@link com.example.bxml.BxmlTypeRegistry#TUPLE_DOMAIN_RELATION_NAME}. */
    private static final Pattern TUPLE_DOMAIN_RELATION_NAME =
            com.example.bxml.BxmlTypeRegistry.TUPLE_DOMAIN_RELATION_NAME;

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private SpecificationAxiomaticInstantiator() {}

    /** Normaliza {@code int} → {@code integer} para nomes de segmento simples. */
    private static String normalizeLegacySegment(String seg) {
        return "int".equals(seg) ? "integer" : seg;
    }

    /** Verifica se {@code t} é uma variável de tipo genérica (letra maiúscula isolada, ex.: "A"). */
    private static boolean isTypeVariable(String t) {
        return t != null && t.trim().matches("[A-Z]");
    }

    /** Rejeita strings que contêm caracteres típicos de predicados ACSL, não de nomes de tipos. */
    private static boolean containsStatementChars(String t) {
        return t != null && (t.contains(";") || t.contains("=") || t.contains("(") || t.contains(")"));
    }

    /** Rejeita tipos legados do ghost_operations.ci (prefixo D: DTuple, DRelation, DSet, dummy_*). */
    private static boolean isLegacyDType(String t) {
        return t != null && (t.startsWith("DTuple") || t.startsWith("DRelation")
                || t.startsWith("DSet") || t.startsWith("dummy_"));
    }

    // ── Contexto de instanciação ──────────────────────────────────────────────

    /**
     * Tipos concretos extraídos de {@code specification_types.txt}.
     *
     * @param setElemTypes fecho sob {@code pow_set} ({@link #closeSetElemUnderPowSet}): inclui um
     *     {@code Set<T>} sintético a mais por tipo natural, disponível como TEXTO (2º argumento de
     *     {@code belongs}, tipo de retorno de {@code pow_set}) — nunca deve ser usado como lista de
     *     candidatos de instanciação (ver {@link #naturalSetElemTypes}).
     * @param naturalSetElemTypes {@code setElemTypes} ANTES do fecho — só tipos com evidência
     *     direta na especificação (declarados, ou par explícito). É esta lista (não {@code
     *     setElemTypes}) que {@link SpecificationAxiomaticInstantiator#buildSubstitutions} deve
     *     usar como candidatos de instanciação de blocos aridade-1 ({@code belongs<A>}, {@code
     *     pow_set<A>}, {@code empty<A>}, …): instanciar TAMBÉM sobre o topo sintético do fecho (ex.
     *     {@code Set<Set<T>>} quando só {@code Set<T>} é natural) exigiria {@code belongs} para o
     *     nível SEGUINTE ({@code Set<Set<Set<T>>>}), que por sua vez pediria mais um — sem fim,
     *     porque o topo do fecho anterior alimenta o próximo fecho. Cada nível sintético só serve
     *     de tipo de RESULTADO para o nível abaixo, nunca precisa ser ele próprio um ponto de
     *     instanciação. Com {@code POW} aninhado (B: {@code FAM : POW1(POW(ELEM))}), o natural já
     *     alcança {@code Set<Set<T>>} (o próprio tipo de {@code FAM}) — o fecho de 1 nível dá
     *     exatamente o {@code Set<Set<Set<T>>>} que falta como texto, sem cascata.
     * @param pairTypes pares (A,B) NATURAIS (com evidência direta na especificação) mais
     *     simétricos ({@link #symmetrizePairs}) — candidatos de instanciação para blocos
     *     aridade-2 ({@link #buildSubstitutions}). NUNCA inclui os pares "envolvidos" de {@link
     *     #addRelationCompositionAliasPairs} (ver {@code aliasPairTypes}): se incluísse, {@code
     *     rel}/{@code fnc}/{@code prj1}/{@code prj2} seriam instanciados também SOBRE os seus
     *     próprios pares envolvidos (ex. {@code (A, Set<B>)}), que por sua vez pedem mais um
     *     nível de envolvimento ({@code Set<Set<B>>}) nunca derivado — cascata sem fim, mesma
     *     classe de bug que {@code naturalSetElemTypes} evita para blocos aridade-1.
     * @param aliasPairTypes {@code pairTypes} mais os pares envolvidos derivados por {@link
     *     #addRelationCompositionAliasPairs} — usado APENAS por {@code buildTypeRenameMap} para
     *     garantir que existe um alias concreto (ex. {@code Relation_Tuple_integer_integer_integer})
     *     para os tipos que {@code rel}/{@code fnc}/{@code prj1}/{@code prj2} referenciam na
     *     PRÓPRIA assinatura, sem alimentar a seleção de candidatos de instanciação (só
     *     {@code pairTypes} faz isso).
     * @param tripleTypes trios (A,B,C) NATURAIS — para cada dois pares (A,B) e (A,C) com o MESMO
     *     primeiro componente A (ex.: {@code RA:Relation<integer,integer>} e
     *     {@code RC:Relation<integer,integer>} do B {@code ><}, cv_rel) — únicos candidatos de
     *     instanciação para {@code direct_product<A,B,C>(Relation<A,B>,Relation<A,C>):
     *     Relation<A,Tuple<B,C>>} (arity 3; ver {@code buildSubstitutions}). Nenhum outro bloco
     *     aridade-3 da lib usa este campo (mantêm-se sem instanciação automática, como antes).
     */
    private record MonoContext(
            List<String> setElemTypes,
            List<String> naturalSetElemTypes,
            List<String> listElemTypes,
            List<List<String>> pairTypes,
            List<List<String>> aliasPairTypes,
            List<List<String>> tripleTypes) {

        /**
         * Orquestrador: classifica cada entrada crua ({@link #classifyType}) e depois aplica os
         * passos de pós-processamento em sequência. Extraído de um único método de 212 linhas
         * (PMD: cyclomatic complexity 65, cognitive complexity 153, NPath 492240 — de longe o
         * pior método do projeto) em extract-method puro — nenhuma linha de LÓGICA mudou, só a
         * organização; cada passo abaixo é byte-a-byte o que estava inline antes, agora com nome
         * próprio.
         */
        static MonoContext from(List<String> specTypes) {
            return from(specTypes, true, true, true, true);
        }

        /**
         * @param needsPowSetClosure {@code false} quando {@code pow_set} não é sequer usado nesta
         *        especificação (nenhum {@code POW(...)} B traduzido, logo {@code pow_set.acsl}
         *        nunca incluído — ver {@link com.example.AcslLibIncludes}): salta {@link
         *        #closeSetElemUnderPowSet}, evitando sintetizar {@code Set<T>} (e, em cascata,
         *        overloads {@code Set<Set<T>>} de {@code belongs}/{@code empty}/{@code singleton}/
         *        {@code set_union}/{@code set_intersection}/{@code card}) que {@code pow_set} nunca
         *        vai precisar — só existiam "por prevenção" mesmo quando nada os referencia.
         * @param needsPairSymmetrization {@code false} quando o operador de inversão relacional B
         *        ({@code ~}, traduzido para {@code relation_inverse}) não é sequer usado nesta
         *        especificação: salta {@link #symmetrizePairs}, evitando registar o par REVERSO
         *        (B,A) para cada par (A,B) — só necessário para {@code relation_inverse<A,B>
         *        (Relation<A,B>) : Relation<B,A>} ter o alias do RESULTADO disponível. Sem uso de
         *        {@code ~}, o par reverso nunca é consultado por ninguém, só polui
         *        setElemTypes/pairTypes com combinações nunca usadas (ex.: cv_rel regista
         *        Relation_Set_integer_integer, nunca referenciado, ao lado do real
         *        Relation_integer_Set_integer — mesma classe de "só existia por prevenção" que
         *        {@code needsPowSetClosure} já trata para pow_set).
         * @param needsRelFncPrjAliases {@code false} quando nem {@code rel}/{@code fnc}/{@code
         *        prj1}/{@code prj2} são sequer usados nesta especificação: salta {@link
         *        #addRelationCompositionAliasPairs}, que SENÃO gera, para TODO par natural (a,b)
         *        — mesmo sem NENHUMA relação com rel/fnc/prj1/prj2 —, um par envolvido "fantasma"
         *        (a, Set&lt;b&gt;) só numa direção (nunca simetrizado como os pares reais). Esse
         *        par fantasma acaba instanciando OUTROS blocos aridade-2 genuínos (ex.
         *        {@code relation_inverse}, {@code couple}/{@code first}/{@code second}) só numa
         *        direção, deixando a direção simétrica em falta quando outro bloco a exige — bug
         *        descoberto em Biblioteca (sem nenhum uso de rel/fnc/prj1/prj2): "no such
         *        predicate or logic function second(Tuple&lt;Set&lt;ℤ&gt;, ℤ&gt;)" vindo do
         *        axioma de {@code relation_inverse}, que simetriza esse par fantasma introduzido
         *        só por existir OUTRA relação (a,b) natural qualquer na especificação.
         * @param needsDirectProductTriples {@code false} quando {@code direct_product} (B:
         *        {@code ><}) não é sequer usado nesta especificação: salta {@link
         *        #deriveDirectProductTriples}/{@link #addDirectProductResultAliasPairs}, pela
         *        mesma razão que {@code needsRelFncPrjAliases} — trios (A,B,C) derivados de TODO
         *        par com A comum poluiriam aliasPairTypes mesmo sem nenhum uso de {@code ><}.
         */
        static MonoContext from(
                List<String> specTypes,
                boolean needsPowSetClosure,
                boolean needsPairSymmetrization,
                boolean needsRelFncPrjAliases,
                boolean needsDirectProductTriples) {
            Set<String> setElem = new LinkedHashSet<>();
            Set<String> listElem = new LinkedHashSet<>();
            Set<List<String>> pairs = new LinkedHashSet<>();

            for (String raw : specTypes) {
                classifyType(raw, setElem, listElem, pairs);
            }

            derivePairsFromSetElemIfEmpty(setElem, pairs);
            Set<List<String>> naturalPairsForDerivation = new LinkedHashSet<>(pairs);
            Set<List<String>> triples =
                    needsDirectProductTriples
                            ? deriveDirectProductTriples(naturalPairsForDerivation)
                            : Set.of();
            if (needsPairSymmetrization) {
                pairs = symmetrizePairs(pairs);
            }
            pairs = renormalizePairWhitespace(pairs);
            Set<List<String>> aliasPairs = new LinkedHashSet<>(pairs);
            if (needsRelFncPrjAliases) {
                addRelationCompositionAliasPairs(naturalPairsForDerivation, aliasPairs);
            }
            if (needsDirectProductTriples) {
                addDirectProductResultAliasPairs(triples, aliasPairs);
            }
            aliasPairs = renormalizePairWhitespace(aliasPairs);
            // aliasPairs (não pairs): prj1/prj2 quantificam o seu próprio axioma sobre
            // Tuple<Tuple<A,B>,A>/Tuple<Tuple<A,B>,B> (o par envolvido (Tuple<A,B>,A/B) derivado
            // por addRelationCompositionAliasPairs), exigindo belongs<A> (aridade 1) também nesse
            // tipo aninhado como elemento de Set — sem isto, "no such predicate or logic function
            // belongs(Tuple<Tuple<...>,...>, Set<Tuple<Tuple<...>,...>>)".
            addPairComponentsToSetElem(aliasPairs, setElem);
            addListElemTypesToSetElem(listElem, setElem);
            List<String> natural = List.copyOf(setElem);
            if (needsPowSetClosure) {
                closeSetElemUnderPowSet(setElem);
            }

            return new MonoContext(
                    List.copyOf(setElem),
                    natural,
                    List.copyOf(listElem),
                    List.copyOf(pairs),
                    List.copyOf(aliasPairs),
                    List.copyOf(triples));
        }

        /**
         * Para cada par NATURAL (A,B), deriva o trio (A,B,B) — único candidato de instanciação de
         * {@code direct_product<A,B,C>} (arity 3, ver {@code tripleTypes}). B: {@code R1 >< R2}
         * exige domínio comum (o A de ambos os operandos), mas B/C podem em teoria ser tipos
         * DIFERENTES — uma versão anterior cruzava TODOS os pares naturais com o MESMO A entre si
         * (ex.: RA:(integer,integer) × FF:(integer,Set<integer>) → também (integer,integer,
         * Set<integer>) e (integer,Set<integer>,integer)), mesmo sem nenhum {@code RA >< FF} real
         * na especificação — combinado com {@code addRelationCompositionAliasPairs}, isso inflava
         * aliasPairTypes (dom/ran/function_apply/couple/cartesian_product/… todos instanciados
         * nesses pares nunca usados) e, por tabela, {@code naturalSetElemTypes} (belongs/
         * singleton/empty/… idem) — 270/318 tipos e dezenas de overloads nunca chamados por
         * ninguém no cv_rel. Restringir ao par consigo mesmo (o único caso realmente evidenciado
         * até hoje: {@code direct_product(RA,RC)}, ambos {@code Relation<integer,integer>}) é a
         * leitura mais conservadora da evidência disponível — um exemplo futuro que precise de
         * {@code ><} entre dois tipos GENUINAMENTE diferentes exigirá alargar isto de propósito,
         * não por defeito.
         */
        private static Set<List<String>> deriveDirectProductTriples(Set<List<String>> naturalPairs) {
            Set<List<String>> triples = new LinkedHashSet<>();
            for (List<String> p : naturalPairs) {
                triples.add(List.of(p.get(0), p.get(1), p.get(1)));
            }
            return triples;
        }

        /**
         * Para cada trio (A,B,C), regista dois pares alias-only (ver {@code aliasPairTypes}) que o
         * axioma {@code direct_product_def<A,B,C>} precisa mas nunca são pares NATURAIS por si só:
         * {@code (A, Tuple<B,C>)} — o TIPO DE RETORNO {@code Relation<A,Tuple<B,C>>} de
         * {@code direct_product<A,B,C>}, sem o qual {@code buildTypeRenameMap} nunca gera o alias
         * concreto (ex. {@code Relation_integer_Tuple_integer_integer}); e {@code (B, C)} — o
         * axioma constrói {@code couple(b, c)} (b:B, c:C) para formar o elemento
         * {@code Tuple<B,C>} do resultado, exigindo {@code couple<B,C>} também instanciado nesse
         * par, mesmo que (B,C) nunca apareça como relação natural na especificação.
         */
        private static void addDirectProductResultAliasPairs(
                Set<List<String>> triples, Set<List<String>> aliasPairs) {
            for (List<String> t : triples) {
                String a = normalizeTypeWhitespace(t.get(0));
                String b = normalizeTypeWhitespace(t.get(1));
                String c = normalizeTypeWhitespace(t.get(2));
                aliasPairs.add(List.of(a, normalizeTypeWhitespace("Tuple<" + b + "," + c + ">")));
                aliasPairs.add(List.of(b, c));
            }
        }

        /**
         * Classifica UMA entrada crua de {@code specTypes} (ex. {@code "Set<integer>"}, {@code
         * "Relation_int_int"}, …) e acumula o resultado nas coleções mutáveis passadas — o corpo
         * do laço de {@link #from}, uma cadeia de até 6 formas de tipo reconhecidas.
         */
        private static void classifyType(
                String raw, Set<String> setElem, Set<String> listElem, Set<List<String>> pairs) {
            if (raw == null) return;
            String t = normalizeTypeWhitespace(raw.trim());
            if (t.isBlank() || t.contains("→") || t.startsWith("#")) return;

            if (isSetType(t)) {
                classifySetType(t, setElem, pairs);
            } else if (isListType(t)) {
                classifyListType(t, listElem);
            } else if (isGenericPairType(t)) {
                classifyGenericPairType(t, pairs);
            } else if (isFlattenedTupleCodomainType(t)) {
                classifyFlattenedTupleCodomainType(t, setElem, pairs);
            } else if (isFlattenedTupleDomainType(t)) {
                classifyFlattenedTupleDomainType(t, setElem, pairs);
            } else if (isLegacyPairType(t)) {
                classifyLegacyPairType(t, setElem, pairs);
            }
        }

        private static boolean isSetType(String t) {
            return t.startsWith("Set<") && t.endsWith(">");
        }

        private static boolean isListType(String t) {
            return t.startsWith("\\list<") && t.endsWith(">");
        }

        private static boolean isGenericPairType(String t) {
            return (t.startsWith("Tuple<") || t.startsWith("Relation<") || t.startsWith("Function<"))
                    && t.endsWith(">");
        }

        private static boolean isFlattenedTupleCodomainType(String t) {
            return !t.contains("<") && TUPLE_CODOMAIN_RELATION_NAME.matcher(t).matches();
        }

        private static boolean isFlattenedTupleDomainType(String t) {
            return !t.contains("<") && TUPLE_DOMAIN_RELATION_NAME.matcher(t).matches();
        }

        private static boolean isLegacyPairType(String t) {
            return !t.contains("<") && (t.startsWith("Relation_") || t.startsWith("Function_"));
        }

        /**
         * {@code Set<X>}: X vira elemento de setElem — mas o tipo COMPLETO {@code t} (ex.
         * {@code Set<Set<integer>>}, não só {@code Set<integer>}) TAMBÉM entra, senão uma
         * declaração diretamente aninhada (B: {@code FAM : POW1(POW(ELEM))}, ACSL:
         * {@code logic Set<Set<integer>> FAM;}) nunca contribui o SEU PRÓPRIO nível como
         * candidato de instanciação — só o nível interior (via {@code inner} abaixo), deixando
         * {@code belongs(FAM, pow_set(pow_set(ELEM)))} sem overload (o nível de {@code FAM}
         * precisa ser {@code A} em {@code belongs<A>}, não só aparecer como {@code Set<A>} de
         * outro nível). Se X for {@code Tuple<A,B>}, também expressa uma relação (A,B): sem
         * registar o par aqui, blocos de aridade 2 (dom, ran, cartesian_product,
         * is_total_function, …) nunca são instanciados para pares só vistos nesta forma
         * "expandida" — como os aliases sempre presentes de types.acsl (ex.:
         * Relation_int_bool = Set<Tuple<integer,boolean> >), que hoje só alimentam setElemTypes
         * (via este mesmo ramo) e nunca pairTypes, deixando dom/ran inconsistentes com os tipos
         * listados em axiomatic new_types.
         */
        private static void classifySetType(String t, Set<String> setElem, Set<List<String>> pairs) {
            String inner = t.substring(4, t.length() - 1).trim();
            // Ignora variáveis de tipo (letras maiúsculas isoladas, ex.: "A") e tipos legados D*
            if (isTypeVariable(inner) || containsStatementChars(inner) || isLegacyDType(inner)) {
                return;
            }
            setElem.add(t);
            setElem.add(inner);
            if (inner.startsWith("Tuple<") && inner.endsWith(">")) {
                List<String> tupleParts = splitTopComma(inner.substring(6, inner.length() - 1));
                if (tupleParts.size() == 2) {
                    List<String> p = tupleParts.stream().map(String::trim).toList();
                    if (p.stream().noneMatch(SpecificationAxiomaticInstantiator::isTypeVariable)
                            && p.stream().noneMatch(SpecificationAxiomaticInstantiator::containsStatementChars)
                            && p.stream().noneMatch(SpecificationAxiomaticInstantiator::isLegacyDType)) {
                        pairs.add(p);
                    }
                }
            }
        }

        /** {@code \list<X>}: X vira elemento de listElem. */
        private static void classifyListType(String t, Set<String> listElem) {
            String inner = t.substring(6, t.length() - 1).trim();
            if (!isTypeVariable(inner) && !containsStatementChars(inner) && !isLegacyDType(inner)) {
                listElem.add(inner);
            }
        }

        /** {@code Tuple<A,B>}/{@code Relation<A,B>}/{@code Function<A,B>} escritos por extenso: (A,B) vira par. */
        private static void classifyGenericPairType(String t, Set<List<String>> pairs) {
            int open = t.indexOf('<');
            List<String> parts = splitTopComma(t.substring(open + 1, t.length() - 1));
            if (parts.size() != 2) return;
            List<String> p = parts.stream().map(String::trim).toList();
            if (p.stream().noneMatch(SpecificationAxiomaticInstantiator::isTypeVariable)
                    && p.stream().noneMatch(SpecificationAxiomaticInstantiator::containsStatementChars)
                    && p.stream().noneMatch(SpecificationAxiomaticInstantiator::isLegacyDType)) {
                pairs.add(p);
            }
        }

        /**
         * Nome achatado gerado dinamicamente por BxmlTypeRegistry#powCartesianProductToAcslRelationType
         * para um codomínio tupla de N>=2 elementos (qualquer mistura de inteiro/booleano,
         * ex.: "Relation_integer_Tuple_Tuple_integer_integer_integer" para N=3 all-integer,
         * ou "Relation_integer_Tuple_boolean_integer" para N=2 misto) — não bate com
         * LEGACY_PAIR_TYPE (que só aceita segmentos minúsculos; "Tuple" é maiúsculo de
         * propósito para nunca ser confundido com esse caminho). Desfaz o achatamento:
         * conta quantos "_Tuple" existem (= aridade do codomínio - 1) e os tipos-folha
         * seguintes (integer/boolean, na ordem), reconstruindo o tipo aninhado à
         * esquerda que os blocos axiomatic<A,B> da lib esperam.
         */
        private static void classifyFlattenedTupleCodomainType(
                String t, Set<String> setElem, Set<List<String>> pairs) {
            Matcher tcm = TUPLE_CODOMAIN_RELATION_NAME.matcher(t);
            tcm.matches();
            String domain = tcm.group(1);
            int nestingCount = countOccurrences(tcm.group(2), "_Tuple");
            List<String> leaves = new ArrayList<>();
            for (String leaf : tcm.group(3).split("_")) {
                if (!leaf.isBlank()) leaves.add(leaf);
            }
            if (nestingCount != leaves.size() - 1) return;
            String codomain = leaves.get(0);
            for (int i = 1; i < leaves.size(); i++) {
                // Cada nível de aninhamento intermédio (ex.: o par-base (leaf0, leaf1) que constrói
                // Tuple<leaf0,leaf1> antes de aninhar leaf2) também precisa da sua própria declaração
                // couple/first/second — sem isto só o par final (domain, codomainCompleto) é
                // registado e o Frama-C rejeita "couple(leaf0, leaf1)" usado para CONSTRUIR o
                // elemento intermédio com "no such predicate or logic function couple(...)".
                pairs.add(List.of(codomain, leaves.get(i)));
                codomain = "Tuple<" + codomain + "," + leaves.get(i) + ">";
            }
            pairs.add(List.of(domain, codomain));
            setElem.add(domain);
            setElem.addAll(leaves);
        }

        /**
         * Espelho do ramo acima para o caso oposto: domínio composto (matriz
         * característica, ex. "Relation_Tuple_integer_integer_boolean" para
         * player_islands_i : PLAYER*ISLAND --> BOOL) + codomínio escalar — ver
         * BxmlTypeRegistry#TUPLE_DOMAIN_RELATION_NAME.
         */
        private static void classifyFlattenedTupleDomainType(
                String t, Set<String> setElem, Set<List<String>> pairs) {
            Matcher tdm = TUPLE_DOMAIN_RELATION_NAME.matcher(t);
            tdm.matches();
            int nestingCount = countOccurrences(tdm.group(1), "Tuple_");
            List<String> domainLeaves = new ArrayList<>();
            for (String leaf : tdm.group(2).split("_")) {
                if (!leaf.isBlank()) domainLeaves.add(leaf);
            }
            String codomain = tdm.group(3);
            if (nestingCount != domainLeaves.size() - 1) return;
            String domain = domainLeaves.get(0);
            for (int i = 1; i < domainLeaves.size(); i++) {
                // Espelho do fix em classifyFlattenedTupleCodomainType: regista também cada par
                // intermédio (ex.: o par-base (leaf0, leaf1) que constrói Tuple<leaf0,leaf1> antes de
                // aninhar leaf2), não só o par final (domainCompleto, codomain) — sem isto,
                // function_apply(f, couple(xx, yy)) usado para CONSTRUIR o elemento de domínio a
                // partir dos argumentos escalares não tem overload de couple e o Frama-C rejeita com
                // "no such predicate or logic function couple(...)".
                pairs.add(List.of(domain, domainLeaves.get(i)));
                domain = "Tuple<" + domain + "," + domainLeaves.get(i) + ">";
            }
            pairs.add(List.of(domain, codomain));
            setElem.add(codomain);
            setElem.addAll(domainLeaves);
        }

        /** Tipos legados no formato underscore: Relation_int_int, Function_integer_boolean, etc. */
        private static void classifyLegacyPairType(
                String t, Set<String> setElem, Set<List<String>> pairs) {
            Matcher legacyM = LEGACY_PAIR_TYPE.matcher(t);
            if (!legacyM.matches()) return;
            String a = normalizeLegacySegment(legacyM.group(1));
            String b = normalizeLegacySegment(legacyM.group(2));
            if (isTypeVariable(a) || isTypeVariable(b)
                    || containsStatementChars(a) || containsStatementChars(b)
                    || isLegacyDType(a) || isLegacyDType(b)) {
                return;
            }
            pairs.add(List.of(a, b));
            // Adiciona tipos individuais a setElem para que axiomas de aridade 1
            // (belongs, singleton, etc.) também sejam instanciados para esses tipos.
            if (!a.contains("<")) setElem.add(a);
            if (!b.contains("<")) setElem.add(b);
        }

        /**
         * Se não existem pares explícitos, deriva de setElemTypes × setElemTypes.
         * Usa apenas tipos "folha" (sem parâmetros de tipo, i.e. sem '<') para evitar
         * pares como ["Tuple<integer,integer>","Tuple<integer,integer>"] que emergem quando
         * o Frama-C já expõe Set<Tuple<A,B>> no output e que não correspondem a nenhuma
         * relação concreta da especificação.
         */
        private static void derivePairsFromSetElemIfEmpty(Set<String> setElem, Set<List<String>> pairs) {
            if (!pairs.isEmpty()) return;
            for (String e : setElem) {
                if (!e.contains("<")) {
                    pairs.add(List.of(e, e));
                }
            }
        }

        /**
         * Simetriza: para cada par (A, B) garante também (B, A). O bloco genérico
         * Relation_inverse<A,B> (relation_functions/inverse.acsl) devolve Relation<B,A> e
         * quantifica sobre Tuple<B,A> no seu axioma — como esse bloco é instanciado para
         * todo par em pairTypes (independentemente de relation_inverse ser chamado com esse
         * par na especificação), sem o par invertido as declarações first/second/couple/equals
         * de Tuple<B,A> nunca são geradas, e o Frama-C rejeita o merge com
         * "no such predicate or logic function second(Tuple<B,A>)".
         */
        private static Set<List<String>> symmetrizePairs(Set<List<String>> pairs) {
            Set<List<String>> withSwapped = new LinkedHashSet<>(pairs);
            for (List<String> pair : pairs) {
                withSwapped.add(List.of(pair.get(1), pair.get(0)));
            }
            return withSwapped;
        }

        /**
         * Para cada par NATURAL (A,B) (capturado ANTES de {@link #symmetrizePairs} — nunca deriva a
         * partir de um par sintético/invertido, só multiplicaria a sobregeração) regista também os
         * pares "envolvidos" que blocos aridade-2 como {@code rel<A,B>(Relation<A,Set<B>> R)} /
         * {@code fnc<A,B>(Relation<A,B> R) : Function<A,Set<B>>} / {@code prj1<A,B>(Set<A>,Set<B>) :
         * Relation<Tuple<A,B>,A>} / {@code prj2<A,B>(...) : Relation<Tuple<A,B>,B>} referenciam na
         * PRÓPRIA assinatura — um nível de aninhamento além do par base. Sem isto, instanciar estes
         * blocos em (A,B) (via o candidato aridade-2 normal, {@code ctx.pairTypes()}, sem filtragem
         * especial) pede um alias (ex. {@code Relation_Tuple_integer_integer_integer}) que nunca
         * seria gerado por {@link #buildTypeRenameMap}, dando "no such type". Ao contrário de tentar
         * RESTRINGIR os candidatos a pares cujo envolvido já esteja registado (tentativa anterior:
         * falhava sempre para prj1/prj2, já que nada MAIS na especificação regista naturalmente um
         * par com {@code Tuple<...>} como primeiro componente — só a própria assinatura de prj1/prj2
         * o precisa), esta função GERA os pares envolvidos diretamente. Fecho de UM nível só (sem
         * recursão): custo é só overloads extra não usados quando (A,B) não é realmente consumido por
         * rel/fnc/prj1/prj2 — mesma filosofia de sobregeração tolerada já usada para pow_set/símbolos
         * de biblioteca sempre presentes.
         */
        private static void addRelationCompositionAliasPairs(
                Set<List<String>> naturalPairs, Set<List<String>> pairs) {
            Set<List<String>> derived = new LinkedHashSet<>();
            for (List<String> p : naturalPairs) {
                if (p.size() != 2) continue;
                String a = normalizeTypeWhitespace(p.get(0));
                String b = normalizeTypeWhitespace(p.get(1));
                derived.add(List.of(a, normalizeTypeWhitespace("Set<" + b + ">")));
                String tuple = normalizeTypeWhitespace("Tuple<" + a + "," + b + ">");
                derived.add(List.of(tuple, a));
                derived.add(List.of(tuple, b));
            }
            pairs.addAll(derived);
        }

        /**
         * Renormaliza espaçamento de vírgula em cada elemento do par: normalizeTypeWhitespace
         * só é aplicada aos textos CRUS lidos de specTypes — pares construídos por
         * OUTROS caminhos (ex. registos de tipo dinâmicos por-máquina de BxmlTypeRegistry, como
         * Relation<boolean, Tuple<integer,integer>>) chegam aqui com o espaçamento que a chamada
         * de origem usou, potencialmente inconsistente com uma entrada semanticamente igual mas
         * textualmente diferente já presente (ex. "Tuple<integer,integer>" vs "Tuple<integer,
         * integer>"). Como pairs é um Set<List<String>>, dois elementos que só diferem neste
         * espaçamento cosmético NÃO deduplicam (List.equals é elemento-a-elemento por String) —
         * cada um gera a sua PRÓPRIA declaração "logic Tuple<...> couple(...)" em
         * merged_code.c, e o Frama-C rejeita a segunda ("already declared with the same
         * profile"). Só descoberto ao correr RulerOfTheSeas (primeiro exemplo com dois sítios de
         * registo colidindo neste tipo específico) — mesma causa raiz do fix em
         * buildConcreteNewTypesBlock/normalizeTypeSpacing, aplicada aqui na fonte para não
         * precisar repetir o mesmo patch em cada consumidor downstream de pairTypes.
         */
        private static Set<List<String>> renormalizePairWhitespace(Set<List<String>> pairs) {
            Set<List<String>> normalizedPairs = new LinkedHashSet<>();
            for (List<String> pair : pairs) {
                normalizedPairs.add(
                        pair.stream()
                                .map(SpecificationAxiomaticInstantiator::normalizeTypeWhitespace)
                                .toList());
            }
            return normalizedPairs;
        }

        /**
         * Para cada par (A, B), adiciona Tuple<A, B> como tipo elemento de Set.
         * Necessário para instanciar axiomas de conjunto puro (belongs, dom, ran, etc.)
         * com Tuple<A,B> como tipo concreto de A (e.g. belongs_Tuple_integer_integer).
         * NÃO é usado em axiomas com \list<A> (ver buildSubstitutions).
         *
         * Também adiciona A e B individualmente: cartesian_product_def_A_B (instanciado para
         * TODO par em pairTypes, ver a simetrização acima) usa "belongs(y, tt)" com
         * tt : Set<B> — se B for ele próprio um tipo composto (ex.: Set<integer>, típico de uma
         * relação PLAYER +-> POW(ISLAND), codomínio Set<integer>) e nunca aparecer como entrada
         * CRUA de specTypes, belongs nunca ganha o overload (Set<integer>, Set<Set<integer>>) —
         * "no such predicate or logic function belongs(Set<ℤ>, Set<Set<ℤ>>)". Só descoberto ao
         * correr RulerOfTheSeas (primeiro exemplo com uma relação de codomínio Set<...>).
         */
        private static void addPairComponentsToSetElem(Set<List<String>> pairs, Set<String> setElem) {
            Set<String> tupleElems = new LinkedHashSet<>();
            for (List<String> pair : pairs) {
                if (pair.size() == 2
                        && !isTypeVariable(pair.get(0))
                        && !isTypeVariable(pair.get(1))) {
                    tupleElems.add("Tuple<" + pair.get(0) + ", " + pair.get(1) + ">");
                    tupleElems.add(pair.get(0));
                    tupleElems.add(pair.get(1));
                }
            }
            setElem.addAll(tupleElems);
        }

        /**
         * Cada tipo de elemento de lista (ex. {@code \list<integer>} → {@code integer} entra em
         * listElem) também precisa de {@code belongs<A>} (aridade 1, candidatos vindos de
         * naturalSetElemTypes, não listElemTypes — ver {@code buildSubstitutions}): axiomas
         * {@code \list<A>}-genéricos como {@code is_seq_of<A>}/{@code is_sequence<A>} são
         * instanciados para TODO A em listElemTypes ({@code buildSubstitutions}, ramo {@code
         * hasList}), e o corpo do axioma indutivo {@code is_seq_of_cons<A>} chama {@code
         * belongs(h, s)} com {@code h:A, s:Set<A>} — sem A também em setElem, essa chamada fica
         * sem overload de belongs. Mesmo princípio de {@link #addPairComponentsToSetElem} (que
         * resolve o caso análogo para prj1/prj2 sobre pares aninhados), aqui para sequências
         * aninhadas (ex.: {@code \list<\list<integer>>}, sequência-de-sequências: A =
         * {@code \list<integer>} precisa de belongs mesmo sem nenhum uso NATURAL de
         * {@code Set<\list<integer>>} na especificação).
         *
         * <p>Também adiciona {@code \list<T>} (o tipo de LISTA inteiro, não só o elemento T) para
         * cada T aqui: axiomas cujo corpo chama {@code belongs} sobre o valor de LISTA em si, não
         * sobre o seu elemento — ex. {@code seq_def<A>: belongs(l, seq(d))} com
         * {@code l : \list<A>} — precisam de {@code belongs<\list<A>>}, um nível acima do que o
         * laço anterior cobre. Sem isto: {@code seq<A>} (sequence_functions/seq.acsl) instanciado
         * em A = {@code \list<integer>} (sequência-de-sequências, mesmo cenário de
         * {@code is_seq_of} acima) exige {@code belongs(\list<\list<integer>>,
         * Set<\list<\list<integer>>>)}, que ainda faltava.
         */
        private static void addListElemTypesToSetElem(Set<String> listElem, Set<String> setElem) {
            for (String t : listElem) {
                if (!isTypeVariable(t)) {
                    setElem.add(t);
                    setElem.add("\\list<" + t + ">");
                }
            }
        }

        /**
         * pow_set (POW(X) em posição de valor) é um bloco genérico de UM parâmetro de tipo,
         * instanciado automaticamente para TODO tipo em setElem — incluindo tipos-base sempre
         * presentes como boolean, mesmo quando o único POW(...) da especificação é sobre outro
         * tipo (ex.: PLAYER/integer). pow_set_def_T usa "belongs(s, pow_set(universe))" com
         * s, universe : Set<T> — exige belongs(Set<T>, Set<Set<T>>), ou seja, Set<T> precisa
         * ele próprio estar em setElem. Sem este fecho, só o T literal do POW(...) da
         * especificação ganhava Set<T> (via o ramo de pairs acima) e as instanciações pow_set
         * para os DEMAIS tipos-base (ex. boolean) ficavam com belongs em falta. Fecho de UM nível
         * só (não recursivo) — mesmo com {@code POW} aninhado (B: {@code FAM : POW1(POW(ELEM))}),
         * já basta: os níveis intermédios da especificação (ex. {@code Set<integer>},
         * {@code Set<Set<integer>>}) já são NATURAIS (declarados, ver {@link
         * MonoContext#naturalSetElemTypes}), só o nível seguinte ao maior deles precisa deste
         * fecho como texto disponível. Ver {@link SpecificationAxiomaticInstantiator#buildSubstitutions}
         * para o porquê de {@code naturalSetElemTypes} (não este {@code setElem} já fechado) ser a
         * lista de candidatos de instanciação — usar o já-fechado geraria uma cascata sem fim.
         */
        private static void closeSetElemUnderPowSet(Set<String> setElem) {
            Set<String> setClosure = new LinkedHashSet<>();
            for (String e : setElem) {
                if (!isTypeVariable(e)) {
                    setClosure.add("Set<" + e + ">");
                }
            }
            setElem.addAll(setClosure);
        }

        /** União de todos os tipos elemento (Set + list). */
        List<String> singleTypes() {
            Set<String> all = new LinkedHashSet<>(setElemTypes);
            all.addAll(listElemTypes);
            return List.copyOf(all);
        }

        /**
         * Tipos T tais que (T,T) é um par NATURAL (pairTypes, não aliasPairTypes — nunca um par
         * "envolvido" derivado, ver {@link #addRelationCompositionAliasPairs}/{@link
         * #addDirectProductResultAliasPairs}): candidatos para blocos aridade-1 sobre
         * {@code Relation<A,A>} (endorrelações homogéneas) como {@code id<A>}/{@code closure1<A>}/
         * {@code closure<A>}/{@code iterate<A>}/{@code relation_composition<A>} — ver
         * {@code buildSubstitutions}. Deliberadamente mais estreito que
         * {@link #naturalSetElemTypes} (que inclui QUALQUER tipo alguma vez envolto em
         * {@code Set<...>}, não só domínios de relação quadrada): usar naturalSetElemTypes aqui
         * causava uma cascata — {@code id<A>} instanciado num T "de mais" (ex.
         * {@code Set<integer>}, presente só porque ND é um conjunto de integers) exige
         * {@code couple<T,T>}, que por sua vez é adicionado como novo par "natural" fantasma, que
         * por sua vez instancia closure1/closure/iterate/relation_composition NESSE T também — e
         * cada um desses introduz mais um nível de {@code Set<Tuple<T,T>>} aninhado, sem nunca
         * convergir. Restringir a pares JÁ genuinamente naturais (ex.: RR:Relation&lt;integer,
         * integer&gt; → (integer,integer) já está em pairTypes por si só) evita a cascata por
         * construção: nenhum destes blocos consegue "criar" um novo T não visto na especificação.
         */
        List<String> squareRelationDomainTypes() {
            Set<String> out = new LinkedHashSet<>();
            for (List<String> pair : pairTypes) {
                if (pair.size() == 2 && pair.get(0).equals(pair.get(1)) && !isTypeVariable(pair.get(0))) {
                    out.add(pair.get(0));
                }
            }
            return List.copyOf(out);
        }
    }

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Substitui todas as declarações ACSL genéricas em {@code mergedC} por versões concretas,
     * com base nos tipos usados na especificação ({@code specTypes}).
     *
     * <p>O contexto é também enriquecido com todos os tipos concretos já presentes no
     * {@code merged_code.c} (e.g., {@code Set<boolean>} de {@code set_variables}).
     */
    public static void monomorphizeGenericAcslBlocks(
            Path mergedC, List<String> specTypes) throws IOException {
        if (!Files.isRegularFile(mergedC)) return;
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);

        // Enriquece specTypes com tipos concretos já presentes no merge
        List<String> augmented = augmentWithConcreteTypesFromText(specTypes, content);

        MonoContext ctx = MonoContext.from(
                augmented,
                content.contains("pow_set("),
                content.contains("relation_inverse("),
                REL_FNC_PRJ_SELF_REFERENCE.matcher(content).find(),
                content.contains("direct_product"));
        if (ctx.singleTypes().isEmpty() && ctx.pairTypes().isEmpty()) return;

        // Passe 1: aridade 2/3 (rel/fnc/prj1/prj2/direct_product/couple/dom/ran/…) — usa ctx,
        // que inclui aliasPairTypes/tripleTypes (os pares "envolvidos" que as PRÓPRIAS
        // assinaturas/corpos destes blocos referenciam, ex. Relation<A,Set<B>> em rel<A,B>).
        content = processAllBlocks(content, ctx, false);

        // Passe 2: aridade 1 (belongs/singleton/empty/set_union/…) — usa um contexto RECALCULADO
        // por SCAN do texto já instanciado pelo passe 1 (bracket-depth-aware, ver
        // augmentWithConcreteTypesFromText), não por DERIVAÇÃO (aliasPairTypes/tripleTypes
        // desligados aqui: needsRelFncPrjAliases=false, needsDirectProductTriples=false). A esta
        // altura, todo Set/Tuple "envolvido" que os blocos aridade 2/3 realmente precisaram já
        // está escrito como texto literal nas suas assinaturas/corpos — dá para LER em vez de
        // voltar a DERIVAR. Derivar aqui geraria overloads aridade-1 para toda combinação
        // CRUZADA entre pares naturais da especificação (ex.: para cv_rel, com FF:(integer,
        // Set<integer>) e RA:(integer,integer), tripleTypes cruza os dois mesmo nunca havendo
        // direct_product(FF,RA) nenhum) — chegou a gerar 31 overloads de belongs no cv_rel, a
        // maioria nunca chamada por ninguém, antes deste 2º passe existir.
        List<String> augmented2 = augmentWithConcreteTypesFromText(specTypes, content);
        MonoContext ctx2 = MonoContext.from(
                augmented2,
                content.contains("pow_set("),
                content.contains("relation_inverse("),
                false,
                false);
        content = processAllBlocks(content, ctx2, true);

        Files.writeString(mergedC, content, StandardCharsets.UTF_8);
    }

    /**
     * Varre o texto do merge à procura de todos os {@code Set<X>}, {@code \list<X>},
     * {@code Tuple<X,Y>} concretos (onde X, Y não são variáveis de tipo) e acrescenta-os
     * à lista de tipos da especificação para enriquecer o contexto de instanciação.
     */
    private static final Pattern CONCRETE_TYPE_CONSTRUCTOR_START =
            Pattern.compile("(?<![A-Za-z_])(Set|\\\\list|Tuple|Relation|Function)<");

    private static List<String> augmentWithConcreteTypesFromText(
            List<String> specTypes, String text) {
        Set<String> extra = new LinkedHashSet<>();
        // Captura Set<...>, \list<...>, Tuple<...>, Relation<...>, Function<...> com argumento
        // concreto, em QUALQUER profundidade de aninhamento (bracket-depth-aware via
        // findMatchingAngleBracket) — não só 1 nível. Um regex de 1 nível (versão anterior) corta
        // cedo demais em tipos com 2+ níveis (ex. Relation<integer, Set<Set<integer> > >, do
        // PRÓPRIO parâmetro de rel<A,B>(Relation<A,Set<B>> R) instanciado em B=Set<integer>),
        // deixando esse par por classificar aqui — round 2 (renameParameterizedTypesToConcrete)
        // dependia então de RE-DERIVAR o par via addRelationCompositionAliasPairs para compensar,
        // mas re-derivar a partir de conteúdo JÁ instanciado (por definição, round 2 só corre
        // DEPOIS de round 1 já ter escrito tudo) realimenta o próprio mecanismo de derivação e
        // gerava 270 dos 318 aliases de tipo do cv_rel sem NENHUM uso no ficheiro. Ler o texto
        // corretamente (este fix) elimina essa necessidade: o par já está lá, só faltava sabê-lo
        // ler por inteiro.
        Matcher m = CONCRETE_TYPE_CONSTRUCTOR_START.matcher(text);
        while (m.find()) {
            int openIdx = m.end() - 1;
            int closeIdx = findMatchingAngleBracket(text, openIdx);
            if (closeIdx < 0) continue;
            String whole = normalizeTypeWhitespace(text.substring(m.start(), closeIdx + 1).trim());
            // Ignora entradas que ainda contêm variáveis de tipo (A, B, C)
            if (containsTypeVariables(whole)) continue;
            extra.add(whole);
        }
        List<String> result = new ArrayList<>(specTypes);
        for (String e : extra) {
            if (!result.contains(e)) result.add(e);
        }
        return result;
    }

    /** Índice do {@code '>'} que fecha o {@code '<'} em {@code openIdx}, contando profundidade
     * (análogo a {@link AcslCommentSpanScanner#findMatchingBrace} mas para {@code <}/{@code >}). */
    private static int findMatchingAngleBracket(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Verifica se o tipo contém variáveis de tipo isoladas (A, B, C …). */
    private static boolean containsTypeVariables(String type) {
        return type != null && Pattern.compile("(?<![A-Za-z_0-9])[A-Z](?![A-Za-z_0-9])").matcher(type).find();
    }

    // ── Processamento de blocos ───────────────────────────────────────────────

    /**
     * @param onlyArity1 {@code true}: processa SÓ declarações aridade 1 (belongs, singleton,
     *        empty, set_union, …), deixando aridade 2/3 intactas (ainda genéricas); {@code
     *        false}: o inverso. Ver {@link #monomorphizeGenericAcslBlocks} para o porquê dos 2
     *        passes separados.
     */
    private static String processAllBlocks(String content, MonoContext ctx, boolean onlyArity1) {
        StringBuilder result = new StringBuilder();
        int pos = 0;

        while (pos < content.length()) {
            int blockStart = content.indexOf("/*@", pos);
            if (blockStart < 0) {
                result.append(content, pos, content.length());
                break;
            }
            result.append(content, pos, blockStart);

            int blockEnd = findBlockEnd(content, blockStart);
            if (blockEnd < 0) {
                result.append(content, blockStart, content.length());
                break;
            }

            String block = content.substring(blockStart, blockEnd);
            result.append(processBlock(block, ctx, onlyArity1));
            pos = blockEnd;
        }

        return result.toString();
    }

    private static int findBlockEnd(String content, int start) {
        int i = start + 2;
        while (i < content.length() - 1) {
            if (content.charAt(i) == '*' && content.charAt(i + 1) == '/') {
                return i + 2;
            }
            i++;
        }
        return -1;
    }

    private static String processBlock(String block, MonoContext ctx, boolean onlyArity1) {
        // Mantém new_types polimórfico
        if (block.contains(NEW_TYPES_MARKER)) return block;

        // Sem declarações genéricas → mantém intacto
        if (!GENERIC_DECL_SUFFIX.matcher(block).find()) return block;

        // Nome do axiomatic (pode ser null para blocos predicate/lemma soltos)
        Matcher am = Pattern.compile("axiomatic\\s+(\\w+)\\s*\\{").matcher(block);
        String axiomaticName = am.find() ? am.group(1) : null;

        // Conteúdo interno (sem /*@ e */)
        String inner = block.substring(3, block.length() - 2);

        // Corpo do axiomatic (sem header/footer)
        String body = axiomaticName != null ? stripAxiomaticWrapper(inner, axiomaticName) : inner;

        // Processa cada declaração individualmente para que os candidatos de substituição
        // sejam derivados apenas do conteúdo dessa declaração (e não do grupo inteiro).
        // Isto evita que list_to_function_nil<A> (com \list<A>) seja instanciado para
        // Tuple<...> apenas porque outro lema no mesmo axiomatic usa Set<A>.
        List<List<String>> logicalDecls = splitIntoLogicalDeclarations(body);

        boolean hasAnyGeneric = logicalDecls.stream()
                .anyMatch(lines -> detectArityFromFullDecl(String.join("\n", lines)) > 0);

        if (!hasAnyGeneric) return block;

        StringBuilder combinedBody = new StringBuilder();

        for (List<String> declLines : logicalDecls) {
            String declContent = String.join("\n", declLines);
            int arity = detectArityFromFullDecl(declContent);

            if (arity == 0) {
                combinedBody.append(declContent).append("\n");
                continue;
            }
            // Só processa a aridade deste passe (ver onlyArity1 em processAllBlocks); a outra
            // fica intacta (ainda genérica) para o passe seguinte.
            if (onlyArity1 != (arity == 1)) {
                combinedBody.append(declContent).append("\n");
                continue;
            }

            List<String> formalParams = detectFormalParams(declContent, arity);
            List<List<String>> substitutions = buildSubstitutions(arity, declContent, ctx);

            if (substitutions.isEmpty()) continue;

            boolean multipleSubst = substitutions.size() > 1;

            for (List<String> concrete : substitutions) {
                Map<String, String> subst = buildSubstMap(formalParams, concrete);
                String suffix = multipleSubst ? buildSuffix(concrete) : "";
                String instantiated = applySubstitution(declContent, subst, formalParams, suffix);
                combinedBody.append(instantiated).append("\n");
            }
        }

        return combinedBody.isEmpty() ? block : wrapAxiomatic(axiomaticName, combinedBody.toString(), "");
    }

    // ── Agrupamento de declarações por aridade ────────────────────────────────

    /**
     * Padrão multi-linha para detectar o sufixo genérico numa declaração completa
     * (permite espaço/quebra de linha entre {@code >} e {@code (} ou {@code :}).
     */
    private static final Pattern GENERIC_DECL_MULTILINE = Pattern.compile(
            "\\b\\w+(<\\s*[A-Z](?:\\s*,\\s*[A-Z])*\\s*>)\\s*(?=[:(])",
            Pattern.DOTALL);

    /** Padrão de início de nova declaração lógica (predicate / logic / axiom / lemma). */
    private static final Pattern DECL_START = Pattern.compile(
            "^\\s*(?:admit\\s+)?(?:predicate|logic|axiom|lemma)\\s+");

    /**
     * Divide as linhas do corpo em segmentos onde cada segmento é uma declaração lógica
     * completa (e.g., {@code predicate …;}, {@code axiom …;}).
     */
    private static List<List<String>> splitIntoLogicalDeclarations(String body) {
        List<List<String>> result = new ArrayList<>();
        List<String> current = new ArrayList<>();

        for (String line : body.split("\n", -1)) {
            boolean isNewDecl = DECL_START.matcher(line).find();
            if (isNewDecl && !current.isEmpty()) {
                result.add(new ArrayList<>(current));
                current.clear();
            }
            current.add(line);
        }
        if (!current.isEmpty()) {
            result.add(current);
        }
        return result;
    }

    /** Detecta a aridade do sufixo genérico numa declaração completa (possivelmente multi-linha). */
    private static int detectArityFromFullDecl(String decl) {
        Matcher m = GENERIC_DECL_MULTILINE.matcher(decl);
        if (!m.find()) return 0;
        String paramList = m.group(1); // e.g. "<A, B>"
        return 1 + (int) paramList.chars().filter(c -> c == ',').count();
    }

    /** Extrai os nomes formais dos parâmetros de tipo para uma aridade (ex.: [A, B]). */
    private static List<String> detectFormalParams(String content, int arity) {
        Matcher m = GENERIC_DECL_MULTILINE.matcher(content);
        while (m.find()) {
            String raw = m.group(1).replaceAll("[<>\\s]", "");
            List<String> params = Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (params.size() == arity) return params;
        }
        return switch (arity) {
            case 1 -> List.of("A");
            case 2 -> List.of("A", "B");
            case 3 -> List.of("A", "B", "C");
            default -> List.of();
        };
    }

    // ── Substituições concretas ───────────────────────────────────────────────

    /**
     * Sinal de que {@code A} é o tipo de elemento de uma sequência B codificada como função total
     * {@code integer --> A} (ex.: {@code is_sequence_def_B<A>}), mesmo sem {@code \list<A>} literal.
     *
     * <p>Aceita tanto a forma não expandida ({@code Function<integer, A>}, como escrita nos
     * ficheiros-fonte da lib) quanto a forma que o {@code frama-c -print} realmente produz no
     * {@code merged_code.c}: o sinónimo de tipo {@code Function<A,B> = Relation<A,B> =
     * Set<Tuple<A,B>>} (ver {@code types.acsl}) já vem CANONICALIZADO para {@code Set<Tuple<integer,
     * A>>} nesse ponto — sem este segundo padrão, a deteção nunca disparava para nenhum axioma real
     * (só funcionaria em texto sintético/de teste com "Function<...>" literal), caindo sempre no
     * ramo genérico de {@code Set<A>} (que usa {@code setElemTypes()}, mais amplo, incluindo
     * {@code Tuple<...>} sintetizados que não fazem sentido como elemento de sequência) — isto fazia
     * {@code is_sequence_def_B<A>} instanciar para {@code A = Tuple<integer,integer>} em qualquer
     * projeto com uma relação {@code Relation_int_int} (ex. RobustFifo/VArray), cujo corpo então
     * precisa de {@code dom}/{@code ran} para o par {@code (integer, Tuple<integer,integer>)} — par
     * que nunca é instanciado, dando {@code "no such predicate or logic function dom(...)"} no WP.
     */
    private static final Pattern SEQUENCE_ENCODED_AS_FUNCTION_OF_A =
            Pattern.compile(
                    "Function\\s*<\\s*integer\\s*,\\s*A\\s*>"
                            + "|Set\\s*<\\s*Tuple\\s*<\\s*integer\\s*,\\s*A\\s*>\\s*>");

    /**
     * Sinal de que {@code A} é o domínio de uma endorrelação homogénea {@code Relation<A,A>} —
     * {@code id<A>}/{@code closure1<A>}/{@code closure<A>}/{@code iterate<A>}/{@code
     * relation_composition<A>} usam todos esta forma na própria assinatura. Aceita a forma não
     * expandida ({@code Relation<A,A>}, como escrita nos ficheiros-fonte da lib) e a forma
     * canonicalizada pelo {@code frama-c -print} ({@code Set<Tuple<A,A>>}), mesmo padrão duplo de
     * {@link #SEQUENCE_ENCODED_AS_FUNCTION_OF_A} acima e pela mesma razão (o sinónimo de tipo
     * {@code Relation<A,B> = Set<Tuple<A,B>>} de {@code types.acsl} é canonicalizado cedo).
     */
    private static final Pattern SQUARE_RELATION_AA =
            Pattern.compile(
                    "Relation\\s*<\\s*A\\s*,\\s*A\\s*>"
                            + "|Set\\s*<\\s*Tuple\\s*<\\s*A\\s*,\\s*A\\s*>\\s*>");

    /**
     * Chamada a {@code id}/{@code closure1}/{@code closure}/{@code iterate}/{@code
     * relation_composition} — cobre os AXIOMAS de cada bloco (ex. {@code id_belongs<A>: \forall
     * Set<A> s, A x; ... id(s) ...}), cujo texto NÃO repete a assinatura {@code Relation<A,A>} do
     * {@code logic}/{@code predicate} irmão (cada declaração é classificada isoladamente, ver
     * {@code processBlock}: "candidatos derivados apenas do CONTEÚDO DESSA declaração") — sem
     * isto, só a declaração `logic` em si ficava restrita a squareRelationDomainTypes; os seus
     * próprios axiomas continuavam a cair no ramo largo (naturalSetElemTypes), reintroduzindo a
     * mesma cascata que {@link #SQUARE_RELATION_AA} evita. Mesmo padrão de lookbehind negativo de
     * {@link #REL_FNC_PRJ_SELF_REFERENCE} (não casa "valid(" só por terminar em "id(").
     */
    private static final Pattern SQUARE_RELATION_FUNCTION_CALL =
            Pattern.compile(
                    "(?<![A-Za-z0-9_])(id|closure1|closure|iterate|relation_composition)\\(");

    /**
     * Casa a declaração/uso PRÓPRIO de {@code rel}/{@code fnc}/{@code prj1}/{@code prj2}
     * (ex. {@code rel<A,B>(...)}, {@code rel(R)}) — nunca um nome que só termina nessas letras
     * (ex. {@code dom_of_empty_rel<A,B>} em lemmas.acsl), graças ao lookbehind negativo que
     * exige que "rel"/"fnc"/"prj1"/"prj2" não seja precedido de um caractere de identificador.
     */
    private static final Pattern REL_FNC_PRJ_SELF_REFERENCE =
            Pattern.compile("(?<![A-Za-z0-9_])(rel|fnc|prj1|prj2)[(<]");


    private static List<List<String>> buildSubstitutions(
            int arity, String content, MonoContext ctx) {
        if (arity == 1) {
            Set<String> candidates = new LinkedHashSet<>();
            // "[|" é o delimitador ACSL de literal de sequência (ex.: "[| n |]"); axiomas como
            // ran_singleton_explicit<A>/front_singleton<A> usam-no em vez de um "\list<A>"
            // explícito, mas continuam a ser sobre sequências, não sobre Set<A> genérico.
            boolean hasList = content.contains("\\list<A") || content.contains("[|");
            boolean hasSet  = content.contains("Set<A");
            boolean hasSequenceFunction = SEQUENCE_ENCODED_AS_FUNCTION_OF_A.matcher(content).find();
            boolean hasSquareRelation =
                    SQUARE_RELATION_AA.matcher(content).find()
                            || SQUARE_RELATION_FUNCTION_CALL.matcher(content).find();

            if (hasSquareRelation) {
                // id<A>/closure1<A>/closure<A>/iterate<A>/relation_composition<A>: A é o domínio
                // de uma endorrelação Relation<A,A>. Candidatos vêm de squareRelationDomainTypes
                // (pares NATURAIS (T,T) já em pairTypes), não de naturalSetElemTypes — ver javadoc
                // de MonoContext#squareRelationDomainTypes para o porquê (naturalSetElemTypes
                // causava uma cascata: id instanciado num T "de mais" exige couple<T,T>, que vira
                // par natural fantasma, que por sua vez re-instancia closure1/closure/iterate/
                // relation_composition NESSE T também, sem nunca convergir).
                candidates.addAll(ctx.squareRelationDomainTypes());
            } else if (hasList || (!hasSet && hasSequenceFunction)) {
                // Axiomas/lemas de sequência (\list<A>, ou Function<integer,A> como em
                // is_sequence_def_B<A>): o parâmetro A é um tipo de elemento de lista. Usa
                // apenas listElemTypes para não gerar instâncias para Tuple<...> ou boolean que
                // não fazem sentido nessa codificação.
                candidates.addAll(ctx.listElemTypes());
            } else {
                // Axiomas de conjunto (Set<A> literal), ou catch-all genérico sem nenhum sinal
                // textual de tipo (ex.: singleton_membership<A>, que só referencia singleton(yy)
                // sem repetir "Set<A>" no corpo, mas cujo singleton() está instanciado para TODOS
                // os tipos de elemento de conjunto, incluindo Tuple<...>). Sem uma verificação de
                // "isto é mesmo uma sequência" específica (hasSequenceFunction), assumir que A
                // percorre apenas listElemTypes — como o código fazia antes — sub-instanciava
                // catch-alls genuinamente genéricos sempre que a especificação usasse \list em
                // QUALQUER outro ponto (ex.: só {@code integer}, mesmo quando o axioma também
                // precisa de {@code boolean}/{@code Tuple<...>}).
                //
                // naturalSetElemTypes (não setElemTypes, o fecho pós-pow_set): instanciar TAMBÉM
                // sobre o topo sintético do fecho (Set<T> um nível acima do maior tipo natural)
                // exigiria belongs para MAIS um nível ainda (o topo do fecho vira, por si, um novo
                // "maior tipo" a fechar) — cascata sem fim que só termina cortando o fecho na
                // origem, nunca reagindo a ela depois de instanciada. Ver javadoc de
                // MonoContext#naturalSetElemTypes.
                candidates.addAll(ctx.naturalSetElemTypes());
                // pow_set<A> instancia-se SEMPRE em tipos-base (mesmo quando Set<T> não é natural —
                // ver comentário em closeSetElemUnderPowSet: "incluindo tipos-base sempre presentes
                // como boolean"), por design desde antes desta sessão. belongs<A> (e os restantes
                // blocos aridade-1 que partilham este candidates) precisa então do candidato Set<T>
                // para cada tipo-base T também, senão o PRÓPRIO corpo de pow_set_def em T (que chama
                // belongs(s, pow_set(universe)), s/universe:Set<T>, pow_set(universe):Set<Set<T>>)
                // fica com belongs(Set<T>,Set<Set<T>>) em falta (ex.: boolean é sempre candidato de
                // pow_set mesmo nunca aparecendo em POW(...) nenhum do B de origem — sem este
                // acréscimo, belongs(Set<boolean>,Set<Set<boolean>>) nunca é gerado). Só tipos-base
                // (sem "<"): envolver um tipo já composto reintroduziria a cascata que
                // naturalSetElemTypes evita acima — e não precisa, pow_set nunca se auto-instancia em
                // composto sem o próprio Set<T> já ser natural (guarda abaixo).
                for (String t : ctx.naturalSetElemTypes()) {
                    if (!t.contains("<")) {
                        candidates.add(normalizeTypeWhitespace("Set<" + t + ">"));
                    }
                }
                // pow_set<A> ("belongs(s, pow_set(universe))", s/universe : Set<A>) instancia sobre
                // os MESMOS candidatos que belongs (naturalSetElemTypes) — mas só faz sentido
                // instanciar pow_set num tipo COMPOSTO T (contém "<": Set<...>, Tuple<...>, ou
                // qualquer outro construtor genérico) quando Set<T> (o RESULTADO de pow_set<T>) é
                // ele próprio um tipo natural, i.e. algo na especificação realmente usa/compara um
                // valor desse tipo (ex.: FAM : Set<Set<integer>>, comparado com
                // pow_set(pow_set(ELEM)) : Set<Set<Set<integer>>> — instancia pow_set em T=integer e
                // T=Set<integer>, mas NÃO em T=Set<Set<integer>>, já que Set<Set<Set<integer>>> não é
                // natural). Sem esta condição, pow_set auto-instancia-se no topo do fecho
                // (pow_set_def_Set_boolean = POW(POW(boolean)), nunca usado por B aqui), cujo corpo
                // por sua vez precisaria de belongs(Set<Set<T>>, Set<Set<Set<T>>>) — mesma cascata
                // sem fim que a mudança acima já evita para belongs, mas reintroduzida aqui se pow_set
                // continuasse a aceitar QUALQUER composto presente em naturalSetElemTypes. Restringir
                // a "Set<"-prefixados só (em vez de "contém '<'") deixava passar o par-base SEMPRE
                // presente Tuple<integer,integer> (de types.acsl's Relation_integer_integer,
                // adicionado a naturalSetElemTypes mesmo quando a especificação nunca usa relações)
                // como candidato de pow_set, pedindo belongs(Set<Tuple<integer,integer>>,
                // Set<Set<Tuple<integer,integer>>>) nunca gerado — mesma classe de erro.
                //
                // Gatilho declaresOrCalls (não só "pow_set(" cru): a PRÓPRIA declaração de pow_set
                // ("logic Set<Set<A>> pow_set<A>(Set<A> universe);") contém "pow_set<A>(", não
                // "pow_set(" — só o AXIOMA que a usa ("belongs(s, pow_set(universe))") contém a
                // forma sem sufixo genérico. Sem cobrir também "pow_set<", esta guarda nunca disparava
                // para a declaração da própria função, deixando-a instanciada sem esta restrição
                // (inconsistente com o axioma correspondente, embora inofensivo por si só, já que uma
                // função declarada sem axioma não gera obrigações de prova).
                if (declaresOrCalls(content, "pow_set")) {
                    candidates.removeIf(t -> t.contains("<") && !naturalContainsSetOf(ctx, t, 1));
                }
                // general_union<A>/general_inter<A>: Set<Set<A>> -> Set<A> (união/interseção
                // generalizada de uma FAMÍLIA de conjuntos — B: union(ENS)/inter(ENS)). Corpo do
                // axioma (\exists/\forall Set<A> yy; belongs(yy, ens) && belongs(xx, yy)) precisa de
                // belongs em DOIS níveis a partir de A: (A,Set<A>) — já coberto por belongs<A> na
                // mesma A — e (Set<A>,Set<Set<A>>), um nível MAIS FUNDO que pow_set's própria
                // necessidade. Mesmo raciocínio da guarda de pow_set, mas testando Set<Set<T>> (não
                // só Set<T>) contra naturalSetElemTypes: só instancia em T se a família de conjuntos
                // Set<Set<T>> for ela própria um tipo natural (ex. FAM). Para cv_sets isto restringe
                // a T=integer (o único general_union(FAM) real), descartando T=Set<integer> e
                // T=Set<Set<integer>> que senão pediriam belongs um ou dois níveis além do disponível.
                if (declaresOrCalls(content, "general_union") || declaresOrCalls(content, "general_inter")) {
                    candidates.removeIf(t -> !naturalContainsSetOf(ctx, t, 2));
                }
            }
            return candidates.stream().map(List::of).toList();
        }
        if (arity == 2) {
            // rel<A,B>/fnc<A,B>/prj1<A,B>/prj2<A,B> referenciam, na PRÓPRIA assinatura, um par
            // "envolvido" um nível além do par base (A,B) — Relation<A,Set<B>> (rel/fnc),
            // Relation<Tuple<A,B>,A/B> (prj1/prj2). O B pode aplicar QUALQUER operador de relação
            // (function_apply, dom, ran, is_total_function, cartesian_product, couple, …)
            // DIRETAMENTE ao resultado de um destes 4 — ex. cv_rel: "prj1(0..2,0..2)(1|->2)"
            // traduz para function_apply(prj1(...), couple(1,2)), exigindo function_apply
            // instanciado no par envolvido (Tuple<integer,integer>, integer). Por isso QUALQUER
            // declaração/axioma REAL de operador (logic/predicate/axiom "core", não catch-all) usa
            // ctx.aliasPairTypes() (pairTypes + pares envolvidos derivados por
            // MonoContext#addRelationCompositionAliasPairs) por omissão — só 2 exceções ficam em
            // ctx.pairTypes() (natural + simétrico, nunca envolvido):
            //   (1) rel/fnc/prj1/prj2 eles próprios — candidato nos SEUS PRÓPRIOS pares envolvidos
            //       pediria mais um nível de envolvimento, cascata sem fim;
            //   (2) blocos "admit lemma" (lemmas.acsl) — nunca chamados DIRETAMENTE pela
            //       especificação B, só fornecem factos extra ao prover; instanciá-los também nos
            //       pares envolvidos (ex. dom_of_empty_rel_integer_Set_Set_integer, nunca chamado
            //       por ninguém) gerava 270 dos 318 aliases de tipo do cv_rel sem NENHUM uso.
            boolean isRelFncPrjSymbol = REL_FNC_PRJ_SELF_REFERENCE.matcher(content).find();
            boolean isAdmitLemma = content.stripLeading().startsWith("admit");
            List<List<String>> pool =
                    (isRelFncPrjSymbol || isAdmitLemma) ? ctx.pairTypes() : ctx.aliasPairTypes();
            return pool.stream().map(List::copyOf).toList();
        }
        if (arity == 3) {
            // direct_product<A,B,C> (B: R1 >< R2) é o único bloco aridade-3 da lib com uso real
            // hoje (cv_rel); os restantes (cartesian_product_def_nested, tuple triplos comentados)
            // continuam SEM instanciação automática — mantêm o bloco genérico intacto, como antes.
            // ctx.tripleTypes() já é o único candidato calculado (par a par com A comum, ver
            // MonoContext#deriveDirectProductTriples); sem essa restrição por nome, qualquer outro
            // bloco aridade-3 apanharia os mesmos trios sem ligação nenhuma ao seu próprio uso.
            if (content.contains("direct_product")) {
                return ctx.tripleTypes().stream().map(List::copyOf).toList();
            }
            // Apenas instancia se o contexto tiver tripletos explícitos vindos da especificação.
            // Caso contrário, mantém o bloco genérico (não o remove).
            return List.of();
        }
        return List.of();
    }

    /** {@code name(} (chamada) ou {@code name<} (declaração/axioma genérico do próprio símbolo). */
    private static boolean declaresOrCalls(String content, String name) {
        return content.contains(name + "(") || content.contains(name + "<");
    }

    /**
     * {@code true} se {@code Set<...Set<t>...>} (com {@code wrapCount} camadas {@code Set<>}) for,
     * ele próprio, um tipo natural (evidência direta na especificação — ver {@link
     * MonoContext#naturalSetElemTypes}), comparando via {@link #normalizeTypeWhitespace} para
     * tolerar variações de espaçamento entre formas equivalentes do mesmo tipo (ex.
     * {@code "Set<Set<integer> >"} vs {@code "Set<Set<integer>>"}).
     */
    private static boolean naturalContainsSetOf(MonoContext ctx, String t, int wrapCount) {
        String wrapped = t;
        for (int i = 0; i < wrapCount; i++) {
            wrapped = "Set<" + wrapped + ">";
        }
        String target = normalizeTypeWhitespace(wrapped);
        for (String n : ctx.naturalSetElemTypes()) {
            if (normalizeTypeWhitespace(n).equals(target)) return true;
        }
        return false;
    }

    private static Map<String, String> buildSubstMap(
            List<String> formalParams, List<String> concreteTypes) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < formalParams.size() && i < concreteTypes.size(); i++) {
            map.put(formalParams.get(i), concreteTypes.get(i));
        }
        return map;
    }

    // ── Aplicação da substituição ─────────────────────────────────────────────

    /**
     * Aplica a substituição de tipos ao conteúdo de um grupo de declarações:
     * <ol>
     *   <li>Remove {@code <A, B, ...>} dos nomes de funções/axiomas (seguidos de {@code (} ou
     *       {@code :}).</li>
     *   <li>Renomeia axiomas se houver múltiplas instanciações (acrescenta o sufixo de tipo).</li>
     *   <li>Substitui as variáveis de tipo por word-boundary.</li>
     * </ol>
     */
    private static String applySubstitution(
            String content,
            Map<String, String> subst,
            List<String> formalParams,
            String axiomNameSuffix) {

        // 1. Remove sufixo genérico de nomes (e.g., belongs<A> → belongs, couple<A,B> → couple)
        String paramPatternStr = formalParams.stream()
                .map(Pattern::quote)
                .collect(Collectors.joining("\\s*,\\s*"));
        content = content.replaceAll("<\\s*" + paramPatternStr + "\\s*>(?=\\s*[:(])", "");

        // 2. Renomeia nomes de axiomas/lemas se há mais de uma instanciação
        if (!axiomNameSuffix.isEmpty()) {
            String suf = Matcher.quoteReplacement(axiomNameSuffix);
            // axiom name: / lemma name: / admit lemma name:
            content = content.replaceAll(
                    "(?m)((?:admit\\s+)?(?:axiom|lemma)\\s+)(\\w+)(:)",
                    "$1$2_" + suf + "$3");
        }

        // 3. Substitui variáveis de tipo (word boundary)
        for (Map.Entry<String, String> e : subst.entrySet()) {
            String typeVar = e.getKey();
            String concrete = Matcher.quoteReplacement(e.getValue());
            content = content.replaceAll("(?<![A-Za-z_0-9])" + typeVar + "(?![A-Za-z_0-9])", concrete);
        }

        // 4. Garante espaço entre '>' consecutivos (evita token ">>" no parser ACSL/C)
        //    Aplica iterativamente até não haver mais ">>" (trata nesting múltiplo)
        String prev;
        do {
            prev = content;
            content = content.replace(">>", "> >");
        } while (!content.equals(prev));

        return content;
    }

    // ── Montagem do bloco ACSL ────────────────────────────────────────────────

    private static String wrapAxiomatic(String axiomaticName, String body, String suffix) {
        StringBuilder sb = new StringBuilder();
        sb.append("/*@\n");
        if (axiomaticName != null) {
            String name = suffix.isEmpty() ? axiomaticName : axiomaticName + "_" + suffix;
            sb.append("axiomatic ").append(name).append(" {\n");
            sb.append(body.stripTrailing()).append("\n");
            sb.append("}\n");
        } else {
            sb.append(body.stripTrailing()).append("\n");
        }
        sb.append(" */\n");
        return sb.toString();
    }

    private static String stripAxiomaticWrapper(String inner, String axiomaticName) {
        // Remove "axiomatic Name {" header
        String stripped = inner.replaceFirst(
                "(?m)^[^\\n]*axiomatic\\s+\\Q" + axiomaticName + "\\E\\s*\\{[^\\n]*\\n?", "");
        // Remove trailing "}"
        int lastBrace = stripped.lastIndexOf('}');
        if (lastBrace >= 0) {
            stripped = stripped.substring(0, lastBrace);
        }
        return stripped;
    }

    // ── Utilitários ───────────────────────────────────────────────────────────

    /**
     * Normaliza espaçamento numa expressão de tipo genérico para garantir forma canónica:
     * sem espaços após {@code <} e antes de {@code >}, vírgula seguida de um espaço.
     * Exemplo: {@code Tuple<integer,integer>} → {@code Tuple<integer, integer>}.
     */
    static String normalizeTypeWhitespace(String type) {
        if (type == null || type.isBlank()) return type == null ? "" : type;
        return type
                .replaceAll("\\s*<\\s*", "<")
                .replaceAll("\\s*>", ">")
                .replaceAll("\\s*,\\s*", ", ");
    }

    /** Codifica um conjunto de tipos concretos como sufixo de identificador. */
    private static String buildSuffix(List<String> types) {
        return types.stream()
                .map(SpecificationAxiomaticInstantiator::typeToIdentifier)
                .collect(Collectors.joining("_"));
    }

    /** Substitui {@code >>} por {@code > >} iterativamente (evita token de bit-shift no ACSL). */
    private static String fixDoubleAngle(String s) {
        String prev;
        do { prev = s; s = s.replace(">>", "> >"); } while (!s.equals(prev));
        return s;
    }

    static String typeToIdentifier(String type) {
        return type
                .replace("\\list", "list")
                .replace("\\", "")
                .replaceAll("[<>]", "_")
                .replaceAll("[^A-Za-z0-9_]", "_")
                .replaceAll("_+", "_")
                .replaceAll("_$", "");
    }

    // ── Renomeação de tipos parametrizados → nomes concretos ─────────────────────

    /**
     * Substitui todas as referências a tipos parametrizados ({@code Set<integer>},
     * {@code Tuple<integer, integer>}, etc.) por nomes concretos ({@code Set_integer},
     * {@code Tuple_integer_integer}, etc.) e atualiza o bloco {@code axiomatic new_types}
     * com declarações concretas de tipos opacos e aliases.
     */
    public static void renameParameterizedTypesToConcrete(
            Path mergedC, List<String> specTypes) throws IOException {
        if (!Files.isRegularFile(mergedC)) return;
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);

        List<String> augmented = augmentWithConcreteTypesFromText(specTypes, content);
        // needsRelFncPrjAliases/needsDirectProductTriples SEMPRE false aqui (ao contrário de
        // monomorphizeGenericAcslBlocks): por esta altura do pipeline, os pares "envolvidos" que
        // rel/fnc/prj1/prj2/direct_product precisam já foram escritos como TEXTO literal nos
        // corpos dos axiomas que monomorphizeGenericAcslBlocks acabou de gerar — o scan normal de
        // augmentWithConcreteTypesFromText (via classifySetType/classifyGenericPairType) já os
        // captura diretamente, sem precisar re-derivar. Reativar a derivação aqui cria um ciclo de
        // realimentação: augmentWithConcreteTypesFromText lê o conteúdo JÁ instanciado (incluindo
        // os pares envolvidos que a PRÓPRIA derivação escreveu antes), volta a tratá-los como
        // "pares naturais", e deriva mais um nível de envolvimento a partir deles — cascata
        // silenciosa (sem erro do Frama-C, os tipos extra são só nunca referenciados) que gerava
        // 270 dos 318 aliases de tipo do cv_rel (85%) sem NENHUM uso em todo o ficheiro.
        MonoContext ctx = MonoContext.from(
                augmented,
                content.contains("pow_set("),
                content.contains("relation_inverse("),
                false,
                false);

        Map<String, String> renames = buildTypeRenameMap(ctx);
        if (renames.isEmpty()) return;

        content = applyTypeRenames(content, renames);
        content = replaceNewTypesBlock(content, renames, ctx);

        Files.writeString(mergedC, content, StandardCharsets.UTF_8);
    }

    /**
     * Alinha identificadores de tipo “máquina” legados ({@code Function_int_int},
     * {@code Relation_int_tuple_…}) ao padrão pós-instanciação ({@code Function_integer_integer}),
     * onde cada segmento {@code int} (tipo B/C {@code int} mapeado para ACSL {@code integer})
     * passa a {@code integer}.
     *
     * <p>Isto corrige texto que ainda referencia nomes gerados pelo {@link GhostOperationsCiGenerator}
     * ou por camadas antigas, depois de {@link #renameParameterizedTypesToConcrete(Path, List)}.
     */
    public static void normalizeLegacyMachineTypeIdentifiers(Path mergedC) throws IOException {
        if (!Files.isRegularFile(mergedC)) {
            return;
        }
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        String updated =
                LEGACY_MACHINE_TYPE_ID
                        .matcher(content)
                        .replaceAll(SpecificationAxiomaticInstantiator::replaceLegacyMachineTypeId);
        if (!updated.equals(content)) {
            Files.writeString(mergedC, updated, StandardCharsets.UTF_8);
        }
    }

    /**
     * Identificadores compostos por underscore cujo primeiro construtor é um dos tipos lógicos
     * usados na B2ACSLLib instanciada (opcionalmente prefixados por {@code dummy_}).
     */
    private static final Pattern LEGACY_MACHINE_TYPE_ID =
            Pattern.compile("(?<![A-Za-z0-9_])((?:dummy_)?(?:Function|Relation|Tuple|Set)(?:_[A-Za-z0-9]+)+)(?![A-Za-z0-9_])");

    private static String replaceLegacyMachineTypeId(MatchResult m) {
        return normalizeSegmentsIntToInteger(m.group(1));
    }

    /** Substitui cada segmento exatamente {@code int} por {@code integer} (preserva {@code integer}, {@code interface}, etc.). */
    static String normalizeSegmentsIntToInteger(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return "";
        }
        String[] parts = identifier.split("_", -1);
        for (int i = 0; i < parts.length; i++) {
            if ("int".equals(parts[i])) {
                parts[i] = "integer";
            }
        }
        return String.join("_", parts);
    }

    /**
     * Constrói o mapa de renomeação: apenas {@code Relation<A,B>} e {@code Function<A,B>}
     * são renomeados para nomes concretos achatados ({@code Relation_integer_integer}, etc.).
     *
     * <p>{@code Set<X>} e {@code Tuple<X,Y>} NÃO são renomeados — continuam a ser
     * expressões de tipo normais suportadas pelo construtor genérico {@code type Set<A>} e
     * {@code type Tuple<A,B>} do ACSL.
     */
    private static Map<String, String> buildTypeRenameMap(MonoContext ctx) {
        Set<String> types = new LinkedHashSet<>();

        // Pares explícitos da especificação + pares "envolvidos" derivados (ver
        // MonoContext#aliasPairTypes) — precisos aqui para que rel/fnc/prj1/prj2 tenham um alias
        // concreto disponível para os tipos que referenciam na própria assinatura, sem que esses
        // pares derivados alimentem também a seleção de candidatos de instanciação (buildSubstitutions
        // usa só ctx.pairTypes(), não este campo).
        for (List<String> pair : ctx.aliasPairTypes()) {
            String a = pair.get(0), b = pair.get(1);
            types.add("Relation<" + a + ", " + b + ">");
            types.add("Function<" + a + ", " + b + ">");
        }

        // Ordena do mais longo para o mais curto para evitar substituições parciais
        List<String> sorted = types.stream()
                .filter(t -> !containsTypeVariables(t))
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .toList();

        Map<String, String> result = new LinkedHashMap<>();
        for (String t : sorted) {
            result.put(t, typeToIdentifier(t));
        }
        return result;
    }

    /**
     * Aplica o mapa de renomeação ao conteúdo completo do ficheiro usando padrões regex
     * que permitem espaços opcionais em torno de {@code <}, {@code >} e {@code ,}.
     */
    private static String applyTypeRenames(String content, Map<String, String> renames) {
        for (Map.Entry<String, String> e : renames.entrySet()) {
            String pattern = typeExprToRegexPattern(e.getKey());
            content = content.replaceAll(pattern, Matcher.quoteReplacement(e.getValue()));
        }
        return content;
    }

    /** Constrói um padrão regex que aceita espaços opcionais em {@code <}, {@code >}, {@code ,}. */
    private static String typeExprToRegexPattern(String typeExpr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < typeExpr.length(); i++) {
            char c = typeExpr.charAt(i);
            switch (c) {
                case '<' -> sb.append("\\s*<\\s*");
                case '>' -> sb.append("\\s*>");
                case ',' -> sb.append("\\s*,\\s*");
                case ' ' -> { /* espaços ao redor de punctuação já são tratados por \s* */ }
                default -> {
                    if (".*+?[](){}^$|\\".indexOf(c) >= 0) sb.append("\\");
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /**
     * Substitui o bloco {@code axiomatic new_types} pelo formato concreto:
     * <ul>
     *   <li>{@code type Set<A>;} com comentário listando instâncias usadas</li>
     *   <li>{@code type Tuple<A, B>;} com comentário listando instâncias usadas</li>
     *   <li>Aliases concretos: {@code type Relation_X_Y = Set<Tuple<X, Y> >;}</li>
     *   <li>Aliases concretos: {@code type Function_X_Y = Relation_X_Y;}</li>
     * </ul>
     */
    private static String replaceNewTypesBlock(
            String content, Map<String, String> renames, MonoContext ctx) {
        int markerIdx = content.indexOf("axiomatic new_types");
        if (markerIdx < 0) return content;

        int blockStart = content.lastIndexOf("/*@", markerIdx);
        if (blockStart < 0) return content;

        int blockEnd = content.indexOf("*/", markerIdx);
        if (blockEnd < 0) return content;
        blockEnd += 2;

        // Consome newline seguinte ao */
        while (blockEnd < content.length()
                && (content.charAt(blockEnd) == '\n' || content.charAt(blockEnd) == '\r')) {
            blockEnd++;
        }

        String outsideBlock = content.substring(0, blockStart) + content.substring(blockEnd);
        Set<String> alreadyDeclaredElsewhere = standaloneTypeDeclNames(outsideBlock);

        return content.substring(0, blockStart)
                + buildConcreteNewTypesBlock(renames, ctx, alreadyDeclaredElsewhere)
                + content.substring(blockEnd);
    }

    /**
     * Nomes de {@code type NAME = ...;} declarados FORA de {@code axiomatic new_types} (ex.:
     * o {@code .acsl} próprio de uma máquina com um tipo de codomínio-tupla, incluído via
     * {@code include}; ver {@link com.example.bxml.TupleCodomainTypeRegistry}). Reemitir estes
     * nomes dentro do bloco reconstruído duplicaria a declaração no merged_code.c.
     */
    private static final Pattern STANDALONE_TYPE_DECL = Pattern.compile("\\btype\\s+(\\w+)\\s*=");

    private static Set<String> standaloneTypeDeclNames(String text) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = STANDALONE_TYPE_DECL.matcher(text);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    /**
     * Normaliza o espaçamento após vírgulas dentro de um tipo genérico (ex. {@code "integer,integer"}
     * e {@code "integer, integer"} ambos viram {@code "integer, integer"}). Necessário porque {@code
     * byInner} (abaixo) deduplica por este texto — duas chamadas de sítios diferentes construindo o
     * MESMO tipo (ex. {@code Relation<boolean, Tuple<integer, integer>>}) mas com espaçamento
     * cosmético diferente geravam DUAS entradas para o mesmo nome monomorfizado, e o {@code
     * merged_code.c} saía com {@code type Relation_boolean_Tuple_integer_integer = ...;} declarado
     * duas vezes — Frama-C rejeita a segunda ("unexpected token"), só descoberto ao correr
     * RulerOfTheSeas (primeiro exemplo com dois sítios de registo colidindo neste tipo específico).
     */
    private static String normalizeTypeSpacing(String s) {
        return s == null ? null : s.replaceAll(",\\s*", ", ").trim();
    }

    private static String buildConcreteNewTypesBlock(
            Map<String, String> renames, MonoContext ctx, Set<String> alreadyDeclaredElsewhere) {

        // (comentários de instâncias removidos: // ... dentro de /*@ */ confundia o parser do Frama-C)

        // ── linhas de Relation e Function ────────────────────────────────────
        // Agrupados por par (inner), para intercalar Relation + Function
        // mantendo a ordem de inserção do rename map
        record RelFun(String relOrig, String relName, String funOrig, String funName) {}
        Map<String, RelFun> byInner = new LinkedHashMap<>();

        for (Map.Entry<String, String> e : renames.entrySet()) {
            String orig = e.getKey(), name = e.getValue();
            if (orig.startsWith("Relation<") && orig.endsWith(">")) {
                String inner = normalizeTypeSpacing(orig.substring("Relation<".length(), orig.length() - 1));
                byInner.merge(inner,
                        new RelFun(orig, name, null, null),
                        (existing, n) -> new RelFun(n.relOrig(), n.relName(),
                                existing.funOrig(), existing.funName()));
            } else if (orig.startsWith("Function<") && orig.endsWith(">")) {
                String inner = normalizeTypeSpacing(orig.substring("Function<".length(), orig.length() - 1));
                byInner.merge(inner,
                        new RelFun(null, null, orig, name),
                        (existing, n) -> new RelFun(existing.relOrig(), existing.relName(),
                                n.funOrig(), n.funName()));
            }
        }

        // ── construção do bloco ───────────────────────────────────────────────
        StringBuilder sb = new StringBuilder();
        sb.append("/*@\n");
        sb.append("axiomatic new_types {\n");

        sb.append("  type Set<A>;\n");
        sb.append("  type Tuple<A, B>;\n");

        if (!byInner.isEmpty()) {
            sb.append("\n");
            for (Map.Entry<String, RelFun> entry : byInner.entrySet()) {
                String inner = entry.getKey();   // ex.: "integer, integer"
                RelFun rf = entry.getValue();

                // Já declarado standalone fora deste bloco (ex.: codomínio-tupla no .acsl próprio
                // da máquina — ver TupleCodomainTypeRegistry/AcslGenerator#generateAcsl, necessário
                // porque -acsl-import não aceita instanciação genérica inline num ficheiro de
                // topo). Reemitir aqui duplicaria a declaração no merged_code.c.
                boolean relAlready = rf.relName() != null && alreadyDeclaredElsewhere.contains(rf.relName());
                boolean funAlready = rf.funName() != null && alreadyDeclaredElsewhere.contains(rf.funName());

                if (rf.relName() != null && !relAlready) {
                    String rhs = fixDoubleAngle("Set<Tuple<" + inner + "> >");
                    sb.append("  type ").append(rf.relName())
                            .append(" = ").append(rhs).append(" ;\n");
                }
                if (rf.funName() != null && !funAlready) {
                    String rhs = rf.relName() != null ? rf.relName()
                            : fixDoubleAngle("Set<Tuple<" + inner + "> >");
                    sb.append("  type ").append(rf.funName())
                            .append(" = ").append(rhs).append(" ;\n");
                }
            }
        }

        sb.append("}\n");
        sb.append(" */\n");
        return sb.toString();
    }

    /** Divide {@code s} pelas vírgulas de nível top, respeitando {@code < >} aninhados. */
    static List<String> splitTopComma(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }
}
