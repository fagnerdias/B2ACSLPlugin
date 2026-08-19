package com.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fase A do plano de padronização de ordem de axiomáticas ({@code
 * plans/execute-o-projeto-cv-sets-squishy-patterson.md}): passe único que reorganiza os blocos
 * {@code /*@ ... *&#47;} SEGUROS de mover em {@code merged_code.c} em 3 regiões contíguas — TIPO,
 * ASSINATURA(+MISTO), AXIOMA —, substituindo a classificação por NOME dispersa usada pelos 8+
 * passes antigos ({@link LibAxiomaticBlockReorderer}, {@link MergedCodeStructuralPlacement}) por
 * uma classificação baseada no CONTEÚDO real ({@link AxiomaticBlockClassifier}).
 *
 * <p>Aditivo, não destrutivo (Fase A do plano): corre DEPOIS dos passes antigos (que continuam
 * intactos), no mesmo ponto onde corriam antes da monomorfização — reconstrói o layout do zero a
 * partir da classificação, tornando o resultado dos passes antigos, na prática, redundante (mas
 * ainda presente, para comparação/validação byte-a-byte antes de os retirar na Fase B).
 *
 * <p>Garantia central: nenhum bloco AXIOMA precisa aparecer antes do seu "uso" — um {@code axiom}
 * não declara nenhum símbolo que outro bloco possa referenciar pelo nome (ao contrário de
 * {@code logic}/{@code predicate}), só acrescenta um facto que o WP pode usar. Por isso a camada
 * AXIOMA pode viver inteira DEPOIS de toda a camada ASSINATURA (e de todo o código C), sem
 * precisar de nenhuma lógica de "acoplar ao pai" ou "antecipar antes de Connection_*" que os
 * passes antigos precisavam.
 *
 * <p>Exceção — blocos ANCORADOS (fora deste sort, ficam exatamente onde já estão): além dos
 * blocos {@code NOME{L}=} (já geridos, corretamente, por {@link
 * MemoryDependentBlockReorderer}), o projeto usa uma SEGUNDA forma de ligação a memória —
 * {@code logic TIPO nome reads GLOBAL;} (variável lógica ligada a uma ghost var C, ex.: AddRunner
 * {@code logic Set<integer> ss reads ghost_ss;}) — sem análogo existente que a reposicione. Um
 * bloco assim NUNCA pode ser tratado como assinatura comum: TUDO que o referencia (pelo nome) tem
 * de ficar tão perto dele quanto já está hoje, recursivamente (ex.: AddRunner: {@code ss_r} chama
 * {@code ss}; qualquer outro bloco que chame {@code ss_r} teria também de ficar excluído). Ver
 * {@link #computeTransitivelyAnchoredExclusions}.
 */
final class AxiomaticTierSorter {

    private AxiomaticTierSorter() {}

    /** Mesmo padrão de deteção de bloco memory-anchored ({@code NOME{L}=}) que {@link
     * MemoryDependentBlockReorderer} usa. */
    private static final Pattern MEMORY_ANCHORED_DECL = Pattern.compile("[A-Za-z_]\\w*\\{L\\}\\s*=");

    /** {@code logic TIPO nome reads GLOBAL;} — segunda forma de ligação a memória (ver javadoc da
     * classe). Descoberto ao correr AddRunner (1º exemplo com esta forma). */
    private static final Pattern READS_CLAUSE_ANCHORED_DECL = Pattern.compile("\\breads\\s+\\w+\\s*;");

    /** {@code /*@ ghost ... *&#47;} nunca entra neste sort — não é axiomático nem predicado
     * standalone, é a especificação de uma função ghost, presa à posição da função C. */
    private static boolean isGhostBlock(String text) {
        return text.startsWith("/*@ ghost");
    }

    private static boolean isDirectlyAnchored(String text) {
        return MEMORY_ANCHORED_DECL.matcher(text).find() || READS_CLAUSE_ANCHORED_DECL.matcher(text).find();
    }

    static void sortIntoThreeTiers(Path mergedC) throws IOException {
        if (!Files.isRegularFile(mergedC)) {
            return;
        }
        Map<String, Integer> rank = new HashMap<>();
        List<String> orderedNames = AcslLibIncludes.orderedLibFunctionAxiomaticNames();
        for (int i = 0; i < orderedNames.size(); i++) {
            rank.put(orderedNames.get(i), i);
        }

        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        List<AcsCommentSpan> spans = AcslCommentSpanScanner.findAllAcsCommentSpans(content);

        List<AcsCommentSpan> classifiable = new ArrayList<>();
        for (AcsCommentSpan sp : spans) {
            if (!isGhostBlock(sp.text)
                    && AxiomaticBlockClassifier.classify(sp.text) != AxiomaticBlockClassifier.Tier.OTHER) {
                classifiable.add(sp);
            }
        }

        Set<AcsCommentSpan> anchoredExclusions = computeTransitivelyAnchoredExclusions(classifiable);

        List<AcsCommentSpan> candidates = new ArrayList<>();
        for (AcsCommentSpan sp : classifiable) {
            if (!anchoredExclusions.contains(sp)) {
                candidates.add(sp);
            }
        }
        // Nada para reorganizar: 0 ou 1 bloco classificável não tem ordem relativa a corrigir.
        if (candidates.size() <= 1) {
            return;
        }

        // "candidates" já está em ordem de ficheiro (spans vêm de uma varredura sequencial única).
        List<AcsCommentSpan> typeSpans = new ArrayList<>();
        List<AcsCommentSpan> sigLibSpans = new ArrayList<>();
        // MISTO fica junto da assinatura de máquina, na ordem de ficheiro ORIGINAL (não separado
        // para o fim da camada) — um bloco de assinatura pura pode depender de um símbolo
        // declarado dentro de um bloco MISTO que o precede (ex.: AddRunner: "AddRunner_r_variables"
        // chama return_valid_ss_r, declarado dentro do MISTO "Connection_AddRunner" que vem antes
        // no ficheiro). Só os blocos de BIBLIOTECA (genéricos, nunca dependem de nada específico de
        // máquina) são hasteados para o INÍCIO da camada, ordenados por rank — todo o resto
        // (assinatura de máquina + MISTO) preserva a ordem relativa original entre si, cobrindo
        // tanto a dependência sinatura->misto acima como a dependência misto->misto já documentada
        // no plano (lambda_functions -> <Máquina>_comprehension_sets).
        List<AcsCommentSpan> machineAndMixedSpans = new ArrayList<>();
        List<AcsCommentSpan> axiomSpans = new ArrayList<>();
        for (AcsCommentSpan sp : candidates) {
            switch (AxiomaticBlockClassifier.classify(sp.text)) {
                case TYPE -> typeSpans.add(sp);
                case MIXED -> machineAndMixedSpans.add(sp);
                case AXIOM -> axiomSpans.add(sp);
                case SIGNATURE -> {
                    if (sp.axiomaticName != null && rank.containsKey(sp.axiomaticName)) {
                        sigLibSpans.add(sp);
                    } else {
                        machineAndMixedSpans.add(sp);
                    }
                }
                case OTHER -> {
                    // inalcançável: candidates já filtrou OTHER acima.
                }
            }
        }

        // TIPO: new_types primeiro (mesma convenção de sempre); resto na ordem original (sort
        // estável — Collections/List.sort do Java garante estabilidade).
        typeSpans.sort(Comparator.comparingInt(s -> "new_types".equals(s.axiomaticName) ? 0 : 1));

        // ASSINATURA: blocos de lib pela ordem de FILE_ORDER (mesmo rank que os passes antigos já
        // usavam) primeiro — sempre seguro, biblioteca é genérica e nunca depende de nada
        // específico de máquina —, depois TODO o resto (assinatura de máquina + MISTO,
        // já em ordem de ficheiro original graças ao laço acima).
        sigLibSpans.sort(
                Comparator.comparingInt(s -> LibAxiomaticBlockReorderer.libAxiomaticRank(rank, s.axiomaticName)));
        List<AcsCommentSpan> signatureTier = new ArrayList<>();
        signatureTier.addAll(sigLibSpans);
        signatureTier.addAll(machineAndMixedSpans);

        // AXIOMA: ordem relativa original (estável). Ao contrário dos passes antigos, não precisa
        // de nenhuma lógica de "acoplar Foo_axioms ao Foo pai" — nada depois desta camada
        // referencia um axioma pelo nome, e tudo que os PRÓPRIOS axiomas referenciam (símbolos
        // ASSINATURA) já está garantido a vir antes, por construção (camada anterior).
        // axiomSpans já está em ordem de ficheiro.

        // Remove todos os candidatos do conteúdo original — o que sobra ("esqueleto") é código C,
        // blocos ghost e blocos ancorados (memory-anchored ou reads-anchored, incl. a exclusão
        // transitiva acima), exatamente na mesma ordem relativa de hoje.
        List<AcsCommentSpan> removalOrder = new ArrayList<>(candidates);
        removalOrder.sort(Comparator.comparingInt((AcsCommentSpan s) -> s.start).reversed());
        String skeleton = content;
        for (AcsCommentSpan sp : removalOrder) {
            skeleton = skeleton.substring(0, sp.start) + skeleton.substring(sp.end);
        }

        StringBuilder typeAndSignature = new StringBuilder();
        for (AcsCommentSpan sp : typeSpans) {
            typeAndSignature.append(sp.text);
        }
        for (AcsCommentSpan sp : signatureTier) {
            typeAndSignature.append(sp.text);
        }
        StringBuilder axiomText = new StringBuilder();
        for (AcsCommentSpan sp : axiomSpans) {
            axiomText.append(sp.text);
        }

        // TIPO+ASSINATURA vai para o preâmbulo (mesma posição que new_types já ocupa hoje, via
        // MergedCodeStructuralPlacement#moveNewTypesAxiomaticBlockAfterPreamble) — garante que
        // TUDO no esqueleto (código C, ghost, ancorado) vem depois de toda declaração de
        // tipo/assinatura de que possa precisar. AXIOMA vai para o FIM do ficheiro — nenhum uso
        // forward depende de um axioma existir mais cedo (ver javadoc da classe).
        int preambleIdx = AcslCommentSpanScanner.findPreambleInsertIndex(skeleton);
        String withTypeAndSignature =
                skeleton.substring(0, preambleIdx) + typeAndSignature + skeleton.substring(preambleIdx);
        String result = withTypeAndSignature + axiomText;

        if (!result.equals(content)) {
            Files.writeString(mergedC, result, StandardCharsets.UTF_8);
        }
    }

    /**
     * Fecho transitivo dos blocos que têm de ficar EXATAMENTE onde já estão: começa nos
     * diretamente ancorados ({@code NOME{L}=} ou {@code reads GLOBAL;}), depois propaga a
     * qualquer OUTRO bloco classificável que referencie (pelo nome, palavra inteira) um símbolo
     * declarado por um bloco já excluído — e repete, porque o nome que ESSE bloco declara pode
     * por sua vez ser usado por um terceiro (ex.: AddRunner: {@code ss} ancorado ->
     * {@code AddRunner_r_variables} (declara {@code ss_r}, usa {@code ss}) fica excluído ->
     * qualquer bloco que use {@code ss_r} ficaria excluído também). Limite de segurança (20
     * passadas) na mesma linha do usado em {@link MemoryDependentBlockReorderer} — nunca deve
     * ser atingido na prática (a cadeia de dependência é tipicamente 1-2 níveis), só evita um
     * laço infinito num caso anómalo não previsto.
     */
    private static Set<AcsCommentSpan> computeTransitivelyAnchoredExclusions(
            List<AcsCommentSpan> classifiable) {
        Set<AcsCommentSpan> excluded = new LinkedHashSet<>();
        Set<String> excludedNames = new LinkedHashSet<>();
        for (AcsCommentSpan sp : classifiable) {
            if (isDirectlyAnchored(sp.text)) {
                excluded.add(sp);
                excludedNames.addAll(extractDeclaredNames(sp.text));
            }
        }
        for (int pass = 0; pass < 20; pass++) {
            boolean changed = false;
            for (AcsCommentSpan sp : classifiable) {
                if (excluded.contains(sp)) {
                    continue;
                }
                for (String name : excludedNames) {
                    if (referencesWholeWord(sp.text, name)) {
                        excluded.add(sp);
                        excludedNames.addAll(extractDeclaredNames(sp.text));
                        changed = true;
                        break;
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
        return excluded;
    }

    private static boolean referencesWholeWord(String text, String name) {
        return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])")
                .matcher(text)
                .find();
    }

    /** Delimita cada declaração {@code logic}/{@code predicate} dentro de um bloco (início da
     * palavra-chave até o próximo início de palavra-chave, ou fim do bloco). */
    private static final Pattern LOGIC_OR_PREDICATE_KEYWORD = Pattern.compile("\\b(logic|predicate)\\b");

    /**
     * Nomes declarados por TODAS as declarações {@code logic}/{@code predicate} num bloco —
     * usado só para propagar a exclusão acima (não precisa de ser exaustivamente correto para
     * TODA forma ACSL possível, só para as formas simples que este projeto gera em blocos de
     * variável/constante de máquina: {@code logic TIPO nome;}, {@code logic TIPO nome = EXPR;},
     * {@code logic TIPO nome reads GLOBAL;}, {@code predicate nome = EXPR;},
     * {@code predicate nome(...) = EXPR;}). Blocos genéricos de biblioteca ({@code <A,B>}) nunca
     * entram nesta lista como ANCORADOS (nunca têm {@code {L}=}/{@code reads}), por isso nunca
     * precisam disto — o scanner é tolerante (devolve {@code null} em vez de adivinhar) quando a
     * forma não bate com o esperado.
     */
    private static List<String> extractDeclaredNames(String blockText) {
        List<String> names = new ArrayList<>();
        Matcher m = LOGIC_OR_PREDICATE_KEYWORD.matcher(blockText);
        List<Integer> starts = new ArrayList<>();
        while (m.find()) {
            starts.add(m.start());
        }
        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i);
            int end = i + 1 < starts.size() ? starts.get(i + 1) : blockText.length();
            String chunk = blockText.substring(start, end);
            int semi = chunk.indexOf(';');
            String decl = semi >= 0 ? chunk.substring(0, semi + 1) : chunk;
            String name =
                    decl.startsWith("predicate")
                            ? firstIdentifier(decl.substring("predicate".length()))
                            : scanLogicDeclaredName(decl.substring("logic".length()));
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    private static String firstIdentifier(String s) {
        Matcher m = Pattern.compile("[A-Za-z_]\\w*").matcher(s);
        return m.find() ? m.group() : null;
    }

    /**
     * Devolve o identificador de nível 0 (fora de {@code <...>}/{@code [...]}, para saltar o
     * TIPO de retorno — ex. {@code Relation<A,B>} — e parâmetros genéricos) imediatamente seguido
     * (a menos de espaço em branco) por {@code (}, {@code ;}, {@code =}, ou pela palavra {@code
     * reads} — a forma do NOME declarado numa declaração {@code logic}. Devolve {@code null} se
     * nenhum candidato bater (forma não reconhecida — propagação simplesmente não avança por essa
     * declaração, viés conservador: nunca EXCLUI de menos por engano na direção perigosa, só pode
     * deixar de excluir algo que precisaria, o que a validação de suite completa apanha).
     */
    private static String scanLogicDeclaredName(String s) {
        int depth = 0;
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '<' || c == '[') {
                depth++;
                i++;
                continue;
            }
            if (c == '>' || c == ']') {
                depth--;
                i++;
                continue;
            }
            if (depth == 0 && (Character.isLetter(c) || c == '_')) {
                int identStart = i;
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                    i++;
                }
                String ident = s.substring(identStart, i);
                int j = i;
                while (j < n && Character.isWhitespace(s.charAt(j))) {
                    j++;
                }
                if (j < n && (s.charAt(j) == '(' || s.charAt(j) == ';' || s.charAt(j) == '=')) {
                    return ident;
                }
                if (s.regionMatches(j, "reads", 0, 5)) {
                    return ident;
                }
                continue;
            }
            i++;
        }
        return null;
    }
}
