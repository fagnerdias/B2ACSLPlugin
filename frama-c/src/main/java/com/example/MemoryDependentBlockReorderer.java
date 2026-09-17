package com.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reordenação de blocos {@code nome{L}= ...} em {@code merged_code.c} que o Frama-C pode imprimir
 * antes da declaração C global (ou de outro bloco {@code {L}=}) de que dependem, causando
 * {@code unbound logic variable}. Extraído de {@code B2ACSLPipeline} (WMC=607) por extract-class
 * puro: nenhuma linha de lógica mudou, só o arquivo em que vive.
 */
final class MemoryDependentBlockReorderer {

    private MemoryDependentBlockReorderer() {}

    /**
     * Frama-C imprime uma constante lógica (ou predicado) que lê memória mutável, direta ou
     * transitivamente, com um parâmetro de label implícito {@code {L}} (ex.: {@code logic integer
     * index{L}= iter_services__index;}, {@code predicate iter_services_i_invariant{L}=
     * \at(... index ..., L);}). Num merge multi-ficheiro, a ordem relativa entre os {@code .acsl}
     * importados e os {@code .c} pode colocar um desses blocos ANTES do que ele referencia — a
     * própria declaração C do global (ex.: {@code static int32_t iter_services__index;} mais adiante
     * no ficheiro) ou outro bloco {@code {L}=} do qual depende (ex.: {@code index} antes de
     * {@code iter_services_i_invariant} usá-lo) — causando {@code unbound logic variable} no
     * Frama-C. Move cada bloco assim afetado para logo após a última coisa de que depende.
     */
    static void moveMemoryDependentVariableBlocksAfterTheirGlobals(Path mergedC) throws IOException {
        if (!Files.isRegularFile(mergedC)) {
            return;
        }
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        String updated = content;
        // Cada movimentação pode revelar outra violação de ordem (dependências em cadeia); repete
        // até estabilizar (limite de segurança para não entrar em loop infinito num caso anómalo).
        for (int pass = 0; pass < 40; pass++) {
            String next = moveOneMemoryDependentVariableBlockIfNeeded(updated);
            if (next.equals(updated)) {
                break;
            }
            updated = next;
        }
        if (!updated.equals(content)) {
            Files.writeString(mergedC, updated, StandardCharsets.UTF_8);
        }
    }

    /** Casa o nome declarado por um bloco {@code nome{L}= ...} impresso pelo Frama-C. */
    private static final Pattern LABELED_DECL_NAME = Pattern.compile("([A-Za-z_]\\w*)\\{L\\}\\s*=");

    /** Casa a referência direta a um global C na forma {@code nome{L}= GLOBAL;}. */
    private static final Pattern LABELED_LOGIC_CONST_REF =
            Pattern.compile("\\w+\\{L\\}\\s*=\\s*([A-Za-z_]\\w*)\\s*;");

    /**
     * Casa a referência a um global C dentro de {@code array_to_function_int}/{@code
     * array_to_function_bool} (ex.: {@code array_to_function_int((int32_t *)Price__price_i, 1)}),
     * forma típica de {@code logic Function_..._..._ name{L}= \at(array_to_function_...(...), L);}
     * gerada para variáveis concretas array/função (ex.: {@code Price__price_i}). Diferente de
     * {@link #LABELED_LOGIC_CONST_REF}: aqui o global está dentro de uma chamada com cast, não
     * numa atribuição direta {@code nome{L}= GLOBAL;}.
     */
    private static final Pattern LABELED_ARRAY_TO_FUNCTION_REF =
            Pattern.compile("array_to_function_\\w+\\(\\([^()]*\\)\\s*([A-Za-z_]\\w*)\\s*,");

    /**
     * {@code true} se {@code name} aparece em {@code text} como PARÂMETRO FORMAL de uma declaração
     * {@code logic}/{@code predicate} própria (ex.: {@code Relation_int_int array} num {@code logic
     * \list<integer> lambda_func01(Relation_int_int array, integer lo, integer hi) = …}) — nesse
     * caso todas as ocorrências de {@code name} dentro de {@code text} referem-se ao PARÂMETRO
     * local, não a uma variável/constante global de mesmo nome ({@code array{L}= …}). Sem esta
     * distinção, um lambda "sequence builder" cujo parâmetro livre reusa o nome de uma variável de
     * OUTRA máquina (ex. {@code array}, parâmetro de {@code lambda_func01} vs a variável concreta
     * {@code array} de {@code VArray}) cria uma dependência de ordenação ESPÚRIA — o texto "contém a
     * palavra array", mas não depende de facto da declaração global — e como essa falsa dependência
     * contradiz a ordenação real (o global tem de vir DEPOIS do seu próprio C {@code static}, mas
     * "antes do lambda" que na realidade não o usa), as duas regras de reposicionamento entram em
     * oscilação perpétua sem nunca convergir.
     */
    private static boolean isNameShadowedAsParameter(String text, String name) {
        Pattern paramDecl =
                Pattern.compile(
                        "(?:[A-Za-z_]\\w*|\\\\list\\s*<[^()]*>)\\s+"
                                + Pattern.quote(name)
                                + "\\s*[,)]");
        return paramDecl.matcher(text).find();
    }

    private static String moveOneMemoryDependentVariableBlockIfNeeded(String content) {
        List<AcsCommentSpan> spans = AcslCommentSpanScanner.findAllAcsCommentSpans(content);
        // Nome declarado {L}= -> span que o declara, para localizar dependências entre blocos
        // (ex.: "iter_services_i_invariant{L}=" depende de "index{L}=" declarado noutro bloco).
        Map<String, AcsCommentSpan> declaringSpan = new HashMap<>();
        for (AcsCommentSpan sp : spans) {
            // while, não if: um único span pode declarar VÁRIOS nomes {L}= (ex.: "axiomatic
            // RegisterI_variables { bday{L}=…; bmonth{L}=…; byear{L}=…; }" é UM comentário só) — só
            // registar o primeiro deixava "bmonth"/"byear" sem entrada em declaringSpan, então
            // nenhum predicado que os referencia (ex.: RegisterI_invariant_2/_3) era detetado como
            // dependente do bloco e nunca era movido para depois dele.
            Matcher dm = LABELED_DECL_NAME.matcher(sp.text);
            while (dm.find()) {
                declaringSpan.putIfAbsent(dm.group(1), sp);
            }
        }
        for (AcsCommentSpan sp : spans) {
            Matcher declMatcher = LABELED_DECL_NAME.matcher(sp.text);
            if (!declMatcher.find()) {
                continue; // só reordena blocos que o Frama-C marcou como dependentes de memória
            }
            String ownName = declMatcher.group(1);
            int latestDepEnd = -1;

            Matcher refMatcher = LABELED_LOGIC_CONST_REF.matcher(sp.text);
            while (refMatcher.find()) {
                int end = lastStaticDeclEndOutsideSpan(content, refMatcher.group(1), spans);
                if (end > latestDepEnd) {
                    latestDepEnd = end;
                }
            }
            Matcher arrayFnMatcher = LABELED_ARRAY_TO_FUNCTION_REF.matcher(sp.text);
            while (arrayFnMatcher.find()) {
                int end = lastStaticDeclEndOutsideSpan(content, arrayFnMatcher.group(1), spans);
                if (end > latestDepEnd) {
                    latestDepEnd = end;
                }
            }
            for (Map.Entry<String, AcsCommentSpan> e : declaringSpan.entrySet()) {
                String depName = e.getKey();
                AcsCommentSpan depSpan = e.getValue();
                if (depName.equals(ownName) || depSpan == sp) {
                    continue;
                }
                if (Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(depName) + "(?![A-Za-z0-9_])")
                        .matcher(sp.text)
                        .find()
                        && !isNameShadowedAsParameter(sp.text, depName)
                        && depSpan.end > latestDepEnd) {
                    latestDepEnd = depSpan.end;
                }
            }

            if (latestDepEnd > sp.start) {
                String without = content.substring(0, sp.start) + content.substring(sp.end);
                int insertAt = latestDepEnd - (sp.end - sp.start);
                if (insertAt < 0 || insertAt > without.length()) {
                    continue;
                }
                insertAt = AcslCommentSpanScanner.skipNewlineAfter(insertAt, without);
                String sepBefore = insertAt > 0 && without.charAt(insertAt - 1) != '\n' ? "\n" : "";
                String sepAfter = sp.text.endsWith("\n") ? "" : "\n";
                return without.substring(0, insertAt) + sepBefore + sp.text + sepAfter + without.substring(insertAt);
            }
        }

        // Passo B: um bloco {L}= também precisa vir ANTES do primeiro uso do seu nome em
        // QUALQUER outro ponto do ficheiro — incluindo contratos de função soltos (ex.:
        // "requires main_fuel_invariant;" acima de uma função, que não se move: está ligado à
        // posição da própria função C). Sem isto, só se corrige a ordem quando quem usa o nome
        // é OUTRO bloco {L}=; um "requires"/"ensures" comum ficaria sempre fora de alcance.
        for (Map.Entry<String, AcsCommentSpan> e : declaringSpan.entrySet()) {
            String name = e.getKey();
            AcsCommentSpan declSpan = e.getValue();
            int earliestUseStart = Integer.MAX_VALUE;
            for (AcsCommentSpan sp : spans) {
                if (sp == declSpan || sp.start >= declSpan.end) {
                    continue;
                }
                if (Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])")
                        .matcher(sp.text)
                        .find()
                        && !isNameShadowedAsParameter(sp.text, name)) {
                    earliestUseStart = Math.min(earliestUseStart, sp.start);
                }
            }
            if (earliestUseStart < declSpan.start) {
                String without =
                        content.substring(0, declSpan.start) + content.substring(declSpan.end);
                // earliestUseStart está antes de declSpan.start, logo não é afetado pela remoção.
                int insertAt = earliestUseStart;
                String sepBefore =
                        insertAt > 0 && without.charAt(insertAt - 1) != '\n' ? "\n" : "";
                String sepAfter = declSpan.text.endsWith("\n") ? "" : "\n";
                return without.substring(0, insertAt)
                        + sepBefore
                        + declSpan.text
                        + sepAfter
                        + without.substring(insertAt);
            }
        }
        return content;
    }

    /**
     * Fim da última declaração de variável global {@code [static] TIPO <globalName>[...];} fora de
     * qualquer comentário ACSL. {@code static} é opcional: nem toda máquina B2ACSL gera variáveis
     * concretas com {@code static} (ex.: máquinas sem cadeia de refinamento podem sair como globais
     * simples, {@code int32_t nome;}), e exigi-lo deixava essas declarações invisíveis a esta
     * varredura. Sem {@code static}, porém, o padrão "PALAVRA nome;" também casa cláusulas de
     * contrato ACSL como {@code assigns nome;}/{@code ensures nome;} — por isso os spans {@code
     * /*@ … *&#47;} (que é onde essas cláusulas sempre vivem; uma declaração C genuína nunca está
     * dentro de um) são explicitamente excluídos.
     */
    private static int lastStaticDeclEndOutsideSpan(
            String content, String globalName, List<AcsCommentSpan> acslSpans) {
        Pattern declPattern =
                Pattern.compile(
                        "(?m)^\\s*(?:static\\s+)?[A-Za-z_]\\w*\\s+"
                                + Pattern.quote(globalName)
                                + "\\s*(?:\\[[^\\]]*\\])?\\s*;\\s*$");
        Matcher dm = declPattern.matcher(content);
        int lastEnd = -1;
        while (dm.find()) {
            int matchStart = dm.start();
            boolean insideAcslComment = false;
            for (AcsCommentSpan sp : acslSpans) {
                if (matchStart >= sp.start && matchStart < sp.end) {
                    insideAcslComment = true;
                    break;
                }
            }
            if (insideAcslComment) {
                continue;
            }
            lastEnd = dm.end();
        }
        return lastEnd;
    }
}
