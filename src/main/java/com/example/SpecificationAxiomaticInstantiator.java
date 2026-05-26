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

    private SpecificationAxiomaticInstantiator() {}

    /** Verifica se {@code t} é uma variável de tipo genérica (letra maiúscula isolada, ex.: "A"). */
    private static boolean isTypeVariable(String t) {
        return t != null && t.trim().matches("[A-Z]");
    }

    // ── Contexto de instanciação ──────────────────────────────────────────────

    /** Tipos concretos extraídos de {@code specification_types.txt}. */
    private record MonoContext(
            List<String> setElemTypes,
            List<String> listElemTypes,
            List<List<String>> pairTypes) {

        static MonoContext from(List<String> specTypes) {
            Set<String> setElem = new LinkedHashSet<>();
            Set<String> listElem = new LinkedHashSet<>();
            Set<List<String>> pairs = new LinkedHashSet<>();

            for (String raw : specTypes) {
                if (raw == null) continue;
                String t = normalizeTypeWhitespace(raw.trim());
                if (t.isBlank() || t.contains("→") || t.startsWith("#")) continue;

                if (t.startsWith("Set<") && t.endsWith(">")) {
                    String inner = t.substring(4, t.length() - 1).trim();
                    // Ignora variáveis de tipo (letras maiúsculas isoladas, ex.: "A")
                    if (!isTypeVariable(inner)) setElem.add(inner);
                } else if (t.startsWith("\\list<") && t.endsWith(">")) {
                    String inner = t.substring(6, t.length() - 1).trim();
                    if (!isTypeVariable(inner)) listElem.add(inner);
                } else if ((t.startsWith("Tuple<") || t.startsWith("Relation<")
                        || t.startsWith("Function<")) && t.endsWith(">")) {
                    int open = t.indexOf('<');
                    List<String> parts = splitTopComma(t.substring(open + 1, t.length() - 1));
                    if (parts.size() == 2) {
                        List<String> p = parts.stream().map(String::trim).toList();
                        if (p.stream().noneMatch(SpecificationAxiomaticInstantiator::isTypeVariable)) {
                            pairs.add(p);
                        }
                    }
                }
            }

            // Se não existem pares explícitos, deriva de setElemTypes × setElemTypes.
            // Usa apenas tipos "folha" (sem parâmetros de tipo, i.e. sem '<') para evitar
            // pares como ["Tuple<integer,integer>","Tuple<integer,integer>"] que emergem quando
            // o Frama-C já expõe Set<Tuple<A,B>> no output e que não correspondem a nenhuma
            // relação concreta da especificação.
            if (pairs.isEmpty()) {
                for (String e : setElem) {
                    if (!e.contains("<")) {
                        pairs.add(List.of(e, e));
                    }
                }
            }

            // Para cada par (A, B), adiciona Tuple<A, B> como tipo elemento de Set.
            // Necessário para instanciar axiomas de conjunto puro (belongs, dom, ran, etc.)
            // com Tuple<A,B> como tipo concreto de A (e.g. belongs_Tuple_integer_integer).
            // NÃO é usado em axiomas com \list<A> (ver buildSubstitutions).
            Set<String> tupleElems = new LinkedHashSet<>();
            for (List<String> pair : pairs) {
                if (pair.size() == 2
                        && !isTypeVariable(pair.get(0))
                        && !isTypeVariable(pair.get(1))) {
                    tupleElems.add("Tuple<" + pair.get(0) + ", " + pair.get(1) + ">");
                }
            }
            setElem.addAll(tupleElems);

            return new MonoContext(List.copyOf(setElem), List.copyOf(listElem), List.copyOf(pairs));
        }

        /** União de todos os tipos elemento (Set + list). */
        List<String> singleTypes() {
            Set<String> all = new LinkedHashSet<>(setElemTypes);
            all.addAll(listElemTypes);
            return List.copyOf(all);
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

        MonoContext ctx = MonoContext.from(augmented);
        if (ctx.singleTypes().isEmpty() && ctx.pairTypes().isEmpty()) return;

        content = processAllBlocks(content, ctx);
        Files.writeString(mergedC, content, StandardCharsets.UTF_8);
    }

    /**
     * Varre o texto do merge à procura de todos os {@code Set<X>}, {@code \list<X>},
     * {@code Tuple<X,Y>} concretos (onde X, Y não são variáveis de tipo) e acrescenta-os
     * à lista de tipos da especificação para enriquecer o contexto de instanciação.
     */
    private static List<String> augmentWithConcreteTypesFromText(
            List<String> specTypes, String text) {
        Set<String> extra = new LinkedHashSet<>();
        // Captura Set<...>, \list<...>, Tuple<...> com argumento concreto (um nível de nesting)
        Matcher m = Pattern.compile(
                "(?:Set|\\\\list|Tuple|Relation|Function)<([^<>]+(?:<[^<>]*>)?[^<>]*)>")
                .matcher(text);
        while (m.find()) {
            String whole = normalizeTypeWhitespace(m.group(0).trim());
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

    /** Verifica se o tipo contém variáveis de tipo isoladas (A, B, C …). */
    private static boolean containsTypeVariables(String type) {
        return type != null && Pattern.compile("(?<![A-Za-z_0-9])[A-Z](?![A-Za-z_0-9])").matcher(type).find();
    }

    // ── Processamento de blocos ───────────────────────────────────────────────

    private static String processAllBlocks(String content, MonoContext ctx) {
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
            result.append(processBlock(block, ctx));
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

    private static String processBlock(String block, MonoContext ctx) {
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
     * Divide o corpo do axiomatic em grupos de linhas, cada grupo pertencendo a uma aridade
     * de parâmetros de tipo.  Declarações multi-linha são mantidas unidas antes da
     * detecção de aridade.
     */
    private static Map<Integer, List<String>> groupLinesByArity(String body) {
        // 1. Agrupa linhas em declarações lógicas completas
        List<List<String>> logicalDecls = splitIntoLogicalDeclarations(body);

        // 2. Para cada declaração completa, detecta a aridade e acumula as linhas
        Map<Integer, List<String>> groups = new LinkedHashMap<>();
        for (List<String> declLines : logicalDecls) {
            String full = String.join("\n", declLines);
            int arity = detectArityFromFullDecl(full);
            groups.computeIfAbsent(arity, k -> new ArrayList<>()).addAll(declLines);
        }
        return groups;
    }

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

    private static List<List<String>> buildSubstitutions(
            int arity, String content, MonoContext ctx) {
        if (arity == 1) {
            Set<String> candidates = new LinkedHashSet<>();
            boolean hasList = content.contains("\\list<A");
            boolean hasSet  = content.contains("Set<A");

            if (hasList) {
                // Axiomas/lemas de sequência (têm \list<A>): o parâmetro A é um tipo
                // de elemento de lista. Usa apenas listElemTypes para não gerar instâncias
                // para Tuple<...> ou boolean que causam erros de tipo no Frama-C.
                candidates.addAll(ctx.listElemTypes());
            } else if (hasSet) {
                // Axiomas de conjunto puros (Set<A>, sem \list<A>): A pode ser qualquer
                // elemento de conjunto, incluindo Tuple<...> (e.g. belongs_Tuple_integer_integer
                // é necessário para os axiomas de relação dom/ran).
                candidates.addAll(ctx.setElemTypes());
            } else {
                // Fallback: axiomas sem Set<A>/\list<A> (e.g. is_sequence_def_B<A>).
                // Usa listElemTypes para evitar instâncias problemáticas com Tuple/boolean.
                if (!ctx.listElemTypes().isEmpty()) {
                    candidates.addAll(ctx.listElemTypes());
                } else {
                    ctx.singleTypes().stream()
                            .filter(t -> !t.startsWith("Tuple<"))
                            .forEach(candidates::add);
                }
            }
            return candidates.stream().map(List::of).toList();
        }
        if (arity == 2) {
            return ctx.pairTypes().stream().map(List::copyOf).toList();
        }
        if (arity == 3) {
            // Apenas instancia se o contexto tiver tripletos explícitos vindos da especificação.
            // Caso contrário, mantém o bloco genérico (não o remove).
            return List.of();
        }
        return List.of();
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
        MonoContext ctx = MonoContext.from(augmented);

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

        // Pares explícitos da especificação
        for (List<String> pair : ctx.pairTypes()) {
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

        return content.substring(0, blockStart)
                + buildConcreteNewTypesBlock(renames, ctx)
                + content.substring(blockEnd);
    }

    private static String buildConcreteNewTypesBlock(
            Map<String, String> renames, MonoContext ctx) {

        // ── comentários para Set<A> e Tuple<A,B> ─────────────────────────────
        List<String> setInstances = ctx.setElemTypes().stream()
                .filter(e -> !containsTypeVariables(e))
                .map(e -> "Set<" + e + ">")
                .distinct()
                .sorted()
                .toList();

        // Instâncias de Tuple vindas explicitamente dos pairTypes
        List<String> tupleInstances = ctx.pairTypes().stream()
                .filter(p -> p.stream().noneMatch(SpecificationAxiomaticInstantiator::isTypeVariable))
                .map(p -> "Tuple<" + p.get(0) + ", " + p.get(1) + ">")
                .distinct()
                .toList();

        // ── linhas de Relation e Function ────────────────────────────────────
        // Agrupados por par (inner), para intercalar Relation + Function
        // mantendo a ordem de inserção do rename map
        record RelFun(String relOrig, String relName, String funOrig, String funName) {}
        Map<String, RelFun> byInner = new LinkedHashMap<>();

        for (Map.Entry<String, String> e : renames.entrySet()) {
            String orig = e.getKey(), name = e.getValue();
            if (orig.startsWith("Relation<") && orig.endsWith(">")) {
                String inner = orig.substring("Relation<".length(), orig.length() - 1);
                byInner.merge(inner,
                        new RelFun(orig, name, null, null),
                        (existing, n) -> new RelFun(n.relOrig(), n.relName(),
                                existing.funOrig(), existing.funName()));
            } else if (orig.startsWith("Function<") && orig.endsWith(">")) {
                String inner = orig.substring("Function<".length(), orig.length() - 1);
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

        // Set<A> com comentário de linha (// evita conflito com o delimitador /*@ ... */)
        String setComment = setInstances.isEmpty() ? ""
                : "  // " + String.join(", ", setInstances);
        sb.append("  type Set<A>;").append(setComment).append("\n");

        // Tuple<A, B> com comentário de linha
        String tupleComment = tupleInstances.isEmpty() ? ""
                : "  // " + String.join(", ", tupleInstances);
        sb.append("  type Tuple<A, B>;").append(tupleComment).append("\n");

        if (!byInner.isEmpty()) {
            sb.append("\n");
            for (Map.Entry<String, RelFun> entry : byInner.entrySet()) {
                String inner = entry.getKey();   // ex.: "integer, integer"
                RelFun rf = entry.getValue();

                if (rf.relName() != null) {
                    // Garante que '>>' nunca apareça: adiciona espaço entre >
                    String rhs = fixDoubleAngle("Set<Tuple<" + inner + "> >");
                    sb.append("  type ").append(rf.relName())
                            .append(" = ").append(rhs).append(" ;")
                            .append("  // ").append(rf.relOrig())
                            .append("\n");
                }
                if (rf.funName() != null) {
                    String rhs = rf.relName() != null ? rf.relName()
                            : fixDoubleAngle("Set<Tuple<" + inner + "> >");
                    sb.append("  type ").append(rf.funName())
                            .append(" = ").append(rhs).append(" ;")
                            .append("  // ").append(rf.funOrig())
                            .append("\n");
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
