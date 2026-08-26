package com.example.bxml;

import com.example.AcslLibSymbolDependencyMap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
 * Gera {@code ghost_operations.ci}: variáveis ghost por variável abstrata, axiomatica {@code dummy_ghost}
 * com tipo paralelo ao da especificação ({@code DSet<…>} para {@code Set<…>}, {@code \\list<…>} para
 * sequências), declarações {@code logic DSet<integer> set_comprehension_k} para índices usados nos
 * {@code ensures} ghost (e até ao máximo do registo de compreensões da máquina), e contratos ghost
 * para inicialização e operações que atribuem a variáveis abstratas.
 *
 * <p>Para atribuições do tipo sequência definida por restrição de domínio sobre um array (função parcial em
 * B), o {@code ensures} ghost compara relações com {@code dummy_list_to_function(dummy_<seq>)} e
 * {@code array_to_function(<param>, <tamanho>)} — o tamanho infere-se do intervalo do domínio na pré-condição
 * ({@code low..high}); constantes concretas da máquina aparecem na axiomatica como {@code dummy_<c>} e nas
 * expressões de comprimento.
 */
public final class GhostOperationsCiGenerator {

    private static final String GHOST_FILE = "ghost_operations.ci";

    private GhostOperationsCiGenerator() {}

    public static Path targetPath(Path cDir) {
        return cDir.resolve(GHOST_FILE);
    }

    /**
     * Escreve {@link #GHOST_FILE} em {@code cDir} a partir da máquina abstrata raiz.
     */
    public static void write(Path cDir, Element abstractMachineEl, Map<String, String> gluing)
            throws IOException {
        write(cDir, abstractMachineEl, gluing, null);
    }

    /**
     * @param bxmlDirectory pasta com os {@code .bxml} (ex. {@code src/main/resources}) para fundir
     *        valores enumerados das máquinas em {@code SEES}
     */
    public static void write(
            Path cDir,
            Element abstractMachineEl,
            Map<String, String> gluing,
            Path bxmlDirectory)
            throws IOException {
        write(cDir, abstractMachineEl, gluing, bxmlDirectory, List.of());
    }

    /**
     * @param mergedMachineElements refinamentos/implementações fundidos na abstrata; se a
     *        implementação espelha as variáveis abstratas, não gera {@code ghost_operations.ci}
     */
    public static void write(
            Path cDir,
            Element abstractMachineEl,
            Map<String, String> gluing,
            Path bxmlDirectory,
            List<Element> mergedMachineElements)
            throws IOException {
        write(cDir, abstractMachineEl, gluing, bxmlDirectory, mergedMachineElements, null, null);
    }

    /**
     * @param seesGraph/importsGraph grafos do projeto inteiro; permitem resolver constantes
     *     concretas e conjuntos diferidos vistos transitivamente (SEES∪IMPORTS, não só um salto de
     *     SEES) para o {@code dummy_ghost} axiomático — ver {@link
     *     BxmlSetsTranslator#listSeenMachineConcreteConstantNames(Element, List, Path,
     *     BxmlSeesGraph, BxmlImportsGraph)}.
     */
    public static void write(
            Path cDir,
            Element abstractMachineEl,
            Map<String, String> gluing,
            Path bxmlDirectory,
            List<Element> mergedMachineElements,
            BxmlSeesGraph seesGraph,
            BxmlImportsGraph importsGraph)
            throws IOException {
        if (cDir == null || abstractMachineEl == null) return;
        Path target = cDir.resolve(GHOST_FILE);
        boolean needsGhost = BxmlMachineVariables.needsGhostAbstraction(
                abstractMachineEl, mergedMachineElements);
        boolean hasAnySubOps = machineHasAnySubOperations(abstractMachineEl);
        if (!needsGhost && !hasAnySubOps) {
            // NÃO apaga target: write() é chamado uma vez por máquina e ACRESCENTA ao mesmo
            // ghost_operations.ci (ver comentário abaixo); apagar aqui destruiria o conteúdo já
            // acrescentado por outras máquinas processadas antes desta no mesmo projeto. A
            // limpeza (uma só vez, antes do loop) e a decisão "nada precisa de ghost" são
            // responsabilidade do chamador (B2ACSLPipeline).
            return;
        }
        String machineName = abstractMachineEl.getAttribute("name");
        if (machineName == null || machineName.isBlank()) return;

        BxmlTranslateContext ctx =
                BxmlTranslateContext.forMachine(abstractMachineEl, gluing)
                        .withLambdaRegistry(new LambdaFunctionRegistry())
                        .withSigmaRegistry(new SigmaFunctionRegistry())
                        .withUnionInterRegistry(new UnionInterFunctionRegistry())
                        .withEnumRenames(
                                BxmlSetsTranslator.buildEnumRenamesWithSees(
                                        abstractMachineEl, mergedMachineElements, bxmlDirectory,
                                        seesGraph, importsGraph))
                        .withEnumeratedSetRenames(
                                BxmlSetsTranslator.buildEnumeratedSetRenamesWithSees(
                                        abstractMachineEl, mergedMachineElements, bxmlDirectory,
                                        seesGraph, importsGraph))
                        .withEnumeratedSetNames(
                                BxmlSetsTranslator.buildEnumeratedSetNames(abstractMachineEl));
        // Variáveis colapsadas na implementação (mesmo nome abstrata/concreta, array-backed — ver
        // BxmlMachineVariables#collapsedIntoImplementationVariableNames) não têm ghost_<v>/
        // dummy_ghost_<v>: usam só a definição direta array-backed da camada da implementação, sem
        // camada ghost paralela. Excluídas aqui em bloco, upstream de tudo (ghost_<v> C global,
        // dummy_ghost_<v> axiomático, e a deteção de "atribuída" em buildGhostOperations), para não
        // ter de replicar o filtro em cada função abaixo.
        Set<String> collapsedVariableNames =
                BxmlMachineVariables.collapsedIntoImplementationVariableNames(
                        abstractMachineEl, mergedMachineElements);
        List<String> abstractVarNames =
                listAbstractVariableNames(abstractMachineEl).stream()
                        .filter(v -> !collapsedVariableNames.contains(v))
                        .toList();
        Set<String> abstractSet = new LinkedHashSet<>(abstractVarNames);
        Map<String, String> varTypes =
                BxmlMachineVariables.inferVariableLogicTypes(abstractMachineEl, ctx);
        Set<String> concreteConstants = new LinkedHashSet<>(concreteConstantNames(abstractMachineEl));
        concreteConstants.addAll(
                BxmlSetsTranslator.listSeenMachineConcreteConstantNames(
                        abstractMachineEl, mergedMachineElements, bxmlDirectory,
                        seesGraph, importsGraph));
        // Conjuntos diferidos vistos (ex. Goods_GOODS) também são globais partilhados só
        // disponíveis via -acsl-import; tratam-se como as constantes concretas vistas acima
        // (renomeados para dummy_<nome> no texto ghost por ghostDummyConcreteRefs).
        concreteConstants.addAll(
                BxmlSetsTranslator.listSeenMachineDeferredSetQualifiedNames(
                        abstractMachineEl, mergedMachineElements, bxmlDirectory,
                        seesGraph, importsGraph));
        // Conjuntos diferidos da PRÓPRIA máquina (ex. RulerOfTheSeas_ISLAND, já renomeados de ISLAND
        // para o nome qualificado por ctx.enumeratedSetRenames() — ver buildEnumeratedSetRenamesWithSees,
        // que apesar do nome também funde buildDeferredSetRenames): sem isto ficam sem dummy_,
        // "unbound logic variable" no front-end isolado (ex. INITIALISATION referenciando o próprio
        // ISLAND da máquina, não só de uma máquina SEEN).
        concreteConstants.addAll(BxmlSetsTranslator.buildDeferredSetRenames(abstractMachineEl).values());

        List<BxmlSetsTranslator.EnumeratedSetInfo> enumeratedSetsForGhost =
                BxmlSetsTranslator.listEnumeratedSetsWithSees(
                        abstractMachineEl, mergedMachineElements, bxmlDirectory,
                        seesGraph, importsGraph);
        Map<String, List<String>> abstractConstParams =
                BxmlConstantsAndProperties.collectLambdaDefsFromProperties(abstractMachineEl);
        Map<String, String> abstractConstDecls =
                GhostParamTypeResolver.buildAbstractConstantDecls(abstractMachineEl, ctx, abstractConstParams);
        List<GhostOp> ghostOps =
                buildGhostOperations(
                        abstractMachineEl,
                        abstractSet,
                        ctx,
                        varTypes,
                        concreteConstants,
                        enumeratedSetsForGhost,
                        abstractConstParams);
        if (ghostOps.isEmpty()) {
            // Idem: não apagar — ver comentário no early-return acima.
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("/* ghost_operations.ci — operações ghost não puras (gerado) — ")
                .append(machineName)
                .append(" */\n\n");

        for (String v : abstractVarNames) {
            String cType = GhostParamTypeResolver.ghostCTypeFromLogicType(varTypes.get(v));
            sb.append("//@ ghost ").append(cType).append(" ghost_").append(v).append(";\n");
        }
        if (!abstractVarNames.isEmpty()) sb.append("\n");

        List<String> allGhostEnsureLines = new ArrayList<>();
        for (GhostOp go : ghostOps) {
            allGhostEnsureLines.addAll(go.ghostEnsures());
        }

        // % (lambda) e SIGMA/PI/MIN/MAX usados nos ensures ghost acima (ex.: ANY_Sub que soma/mapeia
        // sobre um domínio): ghost_operations.ci é um front-end isolado (não vê os lambda_funcNN/
        // sigma_funcNN de -acsl-import), por isso cada um precisa da SUA PRÓPRIA cópia local — ver
        // LambdaFunctionRegistry/SigmaFunctionRegistry, populados como efeito colateral da tradução
        // das ensures acima (ctx.withLambdaRegistry/withSigmaRegistry no início desta função). Sem
        // isto, % cai no fallback inline "\lambda ...", que o front-end isolado não aceita
        // (confirmado empiricamente: "unexpected token '\lambda'"). Calculados ANTES de
        // DummyGhostAxiomaticBuilder.format abaixo (não só emitidos depois): o texto de cada um tem
        // de entrar em allGhostEnsureLines para que os símbolos da lib que usa (belongs,
        // function_apply, is_finite, …) sejam detectados e ganhem declaração dummy_ no preâmbulo,
        // tal como qualquer outra linha de ensures ghost. Ordem alinhada à do .acsl raiz
        // (AcslGenerator emite lambda_functions antes de generalized_quantifier_functions).
        LambdaFunctionRegistry lambdaRegistry = ctx.lambdaRegistry();
        String lambdaBlock = lambdaRegistry != null && !lambdaRegistry.isEmpty()
                ? GhostNamespacePrefixer.prefixLocalAxiomaticBlockForGhost(
                        GhostNamespacePrefixer.renameGhostAxiomaticBlock(lambdaRegistry.formatAxiomaticBlock(), "lambda_functions"),
                        ctx, concreteConstants, abstractSet)
                : null;
        if (lambdaBlock != null) allGhostEnsureLines.add(lambdaBlock);

        SigmaFunctionRegistry sigmaRegistry = ctx.sigmaRegistry();
        String sigmaBlock = sigmaRegistry != null && !sigmaRegistry.isEmpty()
                ? GhostNamespacePrefixer.prefixLocalAxiomaticBlockForGhost(
                        GhostNamespacePrefixer.renameGhostAxiomaticBlock(
                                sigmaRegistry.formatAxiomaticBlock(), "generalized_quantifier_functions"),
                        ctx, concreteConstants, abstractSet)
                : null;
        if (sigmaBlock != null) allGhostEnsureLines.add(sigmaBlock);

        UnionInterFunctionRegistry unionInterRegistry = ctx.unionInterRegistry();
        String unionInterBlock = unionInterRegistry != null && !unionInterRegistry.isEmpty()
                ? GhostNamespacePrefixer.prefixLocalAxiomaticBlockForGhost(
                        GhostNamespacePrefixer.renameGhostAxiomaticBlock(
                                unionInterRegistry.formatAxiomaticBlock(), "generalized_union_inter_functions"),
                        ctx, concreteConstants, abstractSet)
                : null;
        if (unionInterBlock != null) allGhostEnsureLines.add(unionInterBlock);

        int maxSetComp =
                Math.max(
                        ctx.comprehensions().maxComprehensionIndex(),
                        maxSetComprehensionIndexInGhostText(allGhostEnsureLines));
        String axiomaticBlock =
                new DummyGhostAxiomaticBuilder(AcslLibSymbolDependencyMap.instance())
                        .format(
                                allGhostEnsureLines,
                                abstractVarNames,
                                varTypes,
                                maxSetComp,
                                abstractMachineEl,
                                ctx,
                                bxmlDirectory,
                                abstractConstDecls,
                                mergedMachineElements,
                                seesGraph,
                                importsGraph);
        if (!axiomaticBlock.isBlank()) sb.append(axiomaticBlock);

        // Como o bloco "axiomatic dummy_ghost" acima: cada um precisa do wrapper /*@ ... */ — .ci é
        // um front-end externo que só aceita ACSL dentro de comentários de anotação, ao contrário do
        // .acsl raiz (importado directamente, sem wrapper).
        for (String block : new String[] {lambdaBlock, sigmaBlock, unionInterBlock}) {
            if (block == null) continue;
            sb.append("/*@\n");
            sb.append(block);
            if (!block.endsWith("\n")) sb.append("\n");
            sb.append("*/\n\n");
        }

        for (GhostOp go : ghostOps) {
            sb.append(go.format());
        }

        Files.createDirectories(cDir);
        // ACRESCENTA: write() é chamado uma vez por máquina que precisa de abstração ghost no
        // mesmo projeto (ver B2ACSLPipeline, que apaga o ficheiro uma única vez ANTES do loop
        // sobre as máquinas). Sobrescrever aqui perderia o conteúdo de máquinas já processadas.
        // O bloco "axiomatic dummy_ghost { ... }" (boilerplate genérico repetido por chamada) é
        // fundido num só logo depois, em mergeDuplicateDummyGhostBlocks.
        Files.writeString(
                target,
                sb.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    /** Casa um bloco {@code /*@ axiomatic dummy_ghost { ... } *&#47;} inteiro (sem chavetas aninhadas dentro). */
    private static final Pattern DUMMY_GHOST_BLOCK =
            Pattern.compile("/\\*@\\s*axiomatic\\s+dummy_ghost\\s*\\{(.*?)\\}\\s*\\*/", Pattern.DOTALL);

    /**
     * Funde todos os blocos {@code axiomatic dummy_ghost { ... }} de {@code ghostCiPath} num só.
     *
     * <p>{@link #write} agora ACRESCENTA ao ficheiro (uma chamada por máquina que precisa de
     * abstração ghost no mesmo projeto), e cada chamada inclui o seu próprio bloco {@code
     * axiomatic dummy_ghost { ... }} — mesmo nome e, em boa parte, o MESMO boilerplate genérico
     * ({@code type DSet<A>;}, {@code type DTuple<A,B>;}, {@code predicate dummy_equals<A>(...);},
     * …) em toda chamada, só as linhas específicas da máquina (ex.: {@code logic DSet<integer>
     * dummy_purchases;}) é que mudam. Sem fundir, o Frama-C rejeitaria os tipos/símbolos genéricos
     * repetidos como já declarados. A fusão une os PARÁGRAFOS (blocos separados por linha em
     * branco — cada {@code type ...;}/{@code predicate ...;}/{@code logic ...;}/{@code axiom
     * ...;} inteiro, mesmo multi-linha) de todos os blocos num único {@code axiomatic dummy_ghost}
     * na posição do primeiro (deduplicados por texto, ordem de primeira ocorrência preservada),
     * removendo os restantes.
     *
     * <p>Deduplicar por PARÁGRAFO (não por linha crua, como antes): um axioma multi-linha de
     * conjunto enumerado com 2+ valores (ex.: {@code dummy_belongs(a,S) && dummy_belongs(b,S) &&
     * \forall ...}) repete a linha solta {@code "&&"} entre cada conjunto — deduplicar por LINHA
     * tratava essa {@code "&&"} repetida como "boilerplate já visto" e apagava-a, corrompendo
     * silenciosamente a fórmula (sem erro de parse na maior parte dos casos, mas aqui gerava
     * {@code "unexpected token '\forall'"} por faltar o conector antes dele). Só disparava com 2+
     * blocos a fundir ({@code spans.size() > 1}, i.e. 2+ máquinas com abstração ghost no mesmo
     * projeto) — daí nunca ter aparecido antes desta sessão.
     */
    public static void mergeDuplicateDummyGhostBlocks(Path ghostCiPath) throws IOException {
        if (!Files.isRegularFile(ghostCiPath)) {
            return;
        }
        String content = Files.readString(ghostCiPath, StandardCharsets.UTF_8);
        Matcher m = DUMMY_GHOST_BLOCK.matcher(content);
        List<int[]> spans = new ArrayList<>();
        Set<String> uniqueParagraphs = new LinkedHashSet<>();
        while (m.find()) {
            spans.add(new int[] {m.start(), m.end()});
            for (String paragraph : m.group(1).split("\n\\s*\n")) {
                String trimmed = paragraph.strip();
                if (!trimmed.isEmpty()) {
                    uniqueParagraphs.add(trimmed);
                }
            }
        }
        if (spans.size() <= 1) {
            return;
        }
        StringBuilder merged = new StringBuilder();
        merged.append("/*@\n    axiomatic dummy_ghost {\n\n");
        for (String paragraph : uniqueParagraphs) {
            merged.append("        ").append(paragraph).append("\n\n");
        }
        merged.append("    }\n*/\n");

        StringBuilder result = new StringBuilder();
        int cursor = 0;
        boolean firstReplaced = false;
        for (int[] span : spans) {
            result.append(content, cursor, span[0]);
            if (!firstReplaced) {
                result.append(merged);
                firstReplaced = true;
            }
            cursor = span[1];
        }
        result.append(content.substring(cursor));
        Files.writeString(ghostCiPath, result.toString(), StandardCharsets.UTF_8);
    }

    public static boolean machineHasAnySubOperations(Element machineEl) {
        Element opsEl = BxmlDomUtils.firstChildElement(machineEl, "Operations");
        if (opsEl == null) return false;
        NodeList ch = opsEl.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element op = (Element) n;
            if (!"Operation".equals(op.getLocalName())) continue;
            if (GhostContractPredicates.operationBodyHasAnySub(op)) return true;
        }
        return false;
    }

    private static List<GhostOp> buildGhostOperations(
            Element abstractMachineEl,
            Set<String> abstractSet,
            BxmlTranslateContext ctx,
            Map<String, String> varTypes,
            Set<String> concreteConstants,
            List<BxmlSetsTranslator.EnumeratedSetInfo> enumeratedSetsForGhost,
            Map<String, List<String>> abstractConstParams) {
        List<GhostOp> ops = new ArrayList<>();
        List<String> initEnsures = collectGhostEnsuresFromInit(abstractMachineEl, abstractSet, ctx);
        Set<String> initAssigned = new LinkedHashSet<>();
        collectAssignedAbstractVarsInInit(abstractMachineEl, abstractSet, initAssigned);
        if (!initAssigned.isEmpty()) {
            List<String> prefixedInitEnsures = new ArrayList<>();
            for (String e : initEnsures) {
                String ge = GhostNamespacePrefixer.prefixAcslLibFunctionsForGhost(e);
                ge = GhostNamespacePrefixer.prefixEnumValuesForGhost(ge, ctx.enumValueRenames());
                ge = GhostNamespacePrefixer.prefixEnumeratedSetsForGhost(ge, enumeratedSetsForGhost);
                ge = GhostNamespacePrefixer.prefixGlobalLogicSetsForGhost(ge);
                ge = GhostNamespacePrefixer.prefixSetComprehensionsForGhost(ge);
                ge = ghostDummyConcreteRefs(ge, concreteConstants);
                ge = stripBTypingCommentsForGhost(ge);
                ge = normalizeBooleanLiteralsForGhost(ge);
                prefixedInitEnsures.add(ge);
            }
            String initSlug = abstractMachineEl.getAttribute("name").toLowerCase() + "__initialisation";
            ops.add(new GhostOp(initSlug, List.of(), initAssigned, prefixedInitEnsures));
        }

        Element operationsEl = BxmlDomUtils.firstChildElement(abstractMachineEl, "Operations");
        if (operationsEl != null) {
            NodeList opNodes = operationsEl.getChildNodes();
            for (int i = 0; i < opNodes.getLength(); i++) {
                Node n = opNodes.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element op = (Element) n;
                if (!"Operation".equals(op.getLocalName())) continue;
                String opName = op.getAttribute("name");
                if (opName == null || opName.isBlank()) continue;

                Element body = BxmlDomUtils.firstChildElement(op, "Body");
                if (body == null) continue;
                Set<String> assigned = new LinkedHashSet<>();
                collectAssignedAbstractVarsInBody(body, abstractSet, assigned);

                Element anySub = BxmlInitialisationTranslator.findTopLevelAnySub(body);
                if (anySub != null) {
                    String existsForm = BxmlInitialisationTranslator.translateAnySubAsExists(anySub, ctx);
                    if (existsForm == null || existsForm.isBlank()) continue;
                    List<Param> params =
                            GhostParamTypeResolver.appendOutputParametersAsPointers(GhostParamTypeResolver.listInputParameters(op), op, false);
                    existsForm =
                            GhostNamespacePrefixer.rewriteAnySubEnsureForGhost(
                                    existsForm, abstractSet, concreteConstants, op, anySub, ctx,
                                    abstractConstParams);
                    existsForm = rewriteBoolOutputPredicateTernary(existsForm);
                    existsForm = GhostParamTypeResolver.castScalarIntGhostParamsInEnsure(existsForm, params);
                    ops.add(
                            new GhostOp(
                                    GhostParamTypeResolver.sanitizeGhostFunctionName(opName),
                                    params,
                                    assigned,
                                    List.of(existsForm)));
                    continue;
                }

                if (assigned.isEmpty()) continue;

                List<Param> params =
                        GhostParamTypeResolver.appendOutputParametersAsPointers(GhostParamTypeResolver.listInputParameters(op), op, false);
                List<String> ensures = new ArrayList<>();
                BxmlInitialisationTranslator.appendEnsuresFromBody(body, ensures, ctx);
                List<String> ghostEnsures = new ArrayList<>();
                for (String e : ensures) {
                    String ge =
                            isPostStatePredicateEnsure(e)
                                    ? toGhostEnsurePostState(e, abstractSet)
                                    : toGhostEnsure(e, abstractSet);
                    ge = GhostDomainRestrictionRewriter.rewriteGhostEnsureForListDomainRestriction(
                            ge, op, varTypes, params, ctx, concreteConstants);
                    ge = GhostNamespacePrefixer.prefixAcslLibFunctionsForGhost(ge);
                    ge = GhostNamespacePrefixer.prefixEnumValuesForGhost(ge, ctx.enumValueRenames());
                    ge = GhostNamespacePrefixer.prefixEnumeratedSetsForGhost(ge, enumeratedSetsForGhost);
                    ge = GhostNamespacePrefixer.prefixGlobalLogicSetsForGhost(ge);
                    ge = GhostNamespacePrefixer.prefixSetComprehensionsForGhost(ge);
                    ge = ghostDummyConcreteRefs(ge, concreteConstants);
                    ge = stripBTypingCommentsForGhost(ge);
                    ge = normalizeBooleanLiteralsForGhost(ge);
                    ge = GhostNamespacePrefixer.dereferenceScalarOutputParams(ge, op);
                    ge = rewriteBoolOutputPredicateTernary(ge);
                    ge = rewriteIntegerTernaryPredicateConditionForGhost(ge);
                    ge = GhostParamTypeResolver.castScalarIntGhostParamsInEnsure(ge, params);
                    ghostEnsures.add(ge);
                }
                if (ghostEnsures.isEmpty()) continue;

                ops.add(
                        new GhostOp(
                                GhostParamTypeResolver.sanitizeGhostFunctionName(opName),
                                params,
                                assigned,
                                ghostEnsures));
            }
        }
        return ops;
    }

    private static final Pattern SET_COMPREHENSION_INDEX =
            Pattern.compile("dummy_set_comprehension_(\\d+)");

    /** Para contar índices em ensures completos (subexpressão). */
    private static final Pattern SET_COMPREHENSION_INDEX_IN_TEXT =
            Pattern.compile("(?:\\w+__)?set_comprehension_(\\d+)");

    private static int maxSetComprehensionIndexInGhostText(Iterable<String> lines) {
        int m = 0;
        if (lines == null) {
            return 0;
        }
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            Matcher mm = SET_COMPREHENSION_INDEX.matcher(line);
            while (mm.find()) {
                try {
                    m = Math.max(m, Integer.parseInt(mm.group(1)));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
            mm = SET_COMPREHENSION_INDEX_IN_TEXT.matcher(line);
            while (mm.find()) {
                try {
                    m = Math.max(m, Integer.parseInt(mm.group(1)));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        return m;
    }

    /** Nomes em {@code Concrete_Constants} (ordem de declaração no BXML). */
    private static Set<String> concreteConstantNames(Element machineEl) {
        Set<String> out = new LinkedHashSet<>();
        Element block = BxmlDomUtils.firstChildElement(machineEl, "Concrete_Constants");
        if (block == null) {
            return out;
        }
        NodeList ch = block.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            if (!"Id".equals(e.getLocalName())) continue;
            String name = e.getAttribute("value");
            if (name != null && !name.isBlank()) {
                out.add(name.trim());
            }
        }
        return out;
    }

    /** Substitui referências a constantes concretas por {@code dummy_<nome>} numa expressão ACSL já escrita. */
    static String ghostDummyConcreteRefs(String acslExpr, Set<String> concreteConstantNames) {
        if (acslExpr == null || concreteConstantNames == null || concreteConstantNames.isEmpty()) {
            return acslExpr;
        }
        String out = acslExpr;
        List<String> names = new ArrayList<>(concreteConstantNames);
        names.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String c : names) {
            Pattern pat = Pattern.compile("\\b" + Pattern.quote(c) + "\\b");
            Matcher m = pat.matcher(out);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement("dummy_" + c));
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        return out;
    }

    /** Igualdade {@code <var> == <rhs>} (com cast opcional) com variável abstrata no pós-estado. */
    private static final Pattern GHOST_ENSURE_SIMPLE_ABS_VAR_EQ =
            Pattern.compile(
                    "^(?:\\(integer\\)|\\(int\\))?\\s*([A-Za-z_]\\w*)\\s*==\\s*(.+)$", Pattern.DOTALL);

    /** {@code belongs(v, S)} vindo de {@code Becomes_In} / {@code v : S}. */
    private static final Pattern GHOST_ENSURE_BELONGS =
            Pattern.compile("^belongs\\s*\\(\\s*([^,]+)\\s*,\\s*([^)]+)\\s*\\)$");

    /**
     * {@code Becomes_Such_That} e predicados compostos no pós-estado (sem {@code \\old} nas variáveis
     * abstratas).
     */
    private static boolean isPostStatePredicateEnsure(String ensure) {
        if (ensure == null || ensure.isBlank()) {
            return false;
        }
        if (ensure.startsWith("belongs(")) {
            return false;
        }
        return ensure.contains("==>") || ensure.contains("&&");
    }

    private static String toGhostEnsurePostState(String translatedEnsure, Set<String> abstractVars) {
        String s = stripBTypingCommentsForGhost(translatedEnsure.trim());
        Matcher belongsM = GHOST_ENSURE_BELONGS.matcher(s);
        if (belongsM.matches()) {
            return toGhostEnsure(s, abstractVars);
        }
        return GhostNamespacePrefixer.prefixAbstractVarsForGhost(s, abstractVars);
    }

    /** Anotações de tipo B ({@code x /&#42; : BOOL &#42;/}) quebram comentários ACSL em {@code .ci}. */
    private static String stripBTypingCommentsForGhost(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.replaceAll("\\s*/\\*\\s*:[^*]*\\*/", "");
    }

    /**
     * {@code *param == (pred ? \true : \false)} → {@code (*param != 0) <==> pred}.
     *
     * <p>Em ACSL, predicados ({@code dummy_equals}, {@code dummy_belongs}, etc.) não podem ser usados
     * como condição de ternário (contexto de termo). Converte para bicondicional.
     *
     * <p>Visibilidade de pacote: também usada por {@link BxmlOperationsTranslator} para o mesmo
     * padrão fora do universo ghost (ex.: {@code equals(...)} da B2ACSLLib) quando {@code
     * bodyEnsuresOnly} passa a ser mantido no contrato real da função.
     */
    static String rewriteBoolOutputPredicateTernary(String ensure) {
        if (ensure == null || ensure.isEmpty() || !ensure.contains("\\true")) return ensure;
        return ensure.replaceAll(
                "(\\*\\w+)\\s*==\\s*\\((.+?)\\s*\\?\\s*\\\\true\\s*:\\s*\\\\false\\)",
                "(($1 != 0) <==> $2)");
    }

    /**
     * {@code \forall T z; X == (P ? A : B)} (X/A/B termos INTEIROS quaisquer, {@code P} um predicado
     * da lib como {@code dummy_belongs}) → {@code \forall T z; (P ==> X == A) && (!(P) ==> X == B)}.
     *
     * <p>Irmã de {@link #rewriteBoolOutputPredicateTernary}, mesma causa raiz (front-end isolado de
     * {@code .ci} rejeita predicado em posição de termo — "symbol dummy_X is a predicate, not a
     * function" — condição de ternário É posição de termo) mas forma diferente: aquela é para
     * ensures {@code boolean} (produz bicondicional); esta é para {@code X}/{@code A}/{@code B}
     * inteiros (ex.: vindos de {@link BxmlExpressionToAcsl#formatOverwriteWithLambdaAssignment}, B
     * {@code v := v <+ %z.(...)} ) — não há bicondicional que sirva, precisa da forma com duas
     * implicações. Só reescreve quando o ternário ocupa TODO o lado direito de um {@code \forall}
     * simples (não tenta cobrir aninhamento arbitrário); nas demais formas não mexe.
     *
     * <p>Visibilidade de pacote: também usada por {@link BxmlOperationsTranslator} para o mesmo
     * padrão fora do universo ghost — o contrato REAL de {@code v := v <+ %z.(...)} passa pelo
     * mesmo {@link BxmlExpressionToAcsl#formatOverwriteWithLambdaAssignment} e sofre exatamente o
     * mesmo erro do kernel (ex.: {@code RulerOfTheSeas__NextTurn}, {@code player_coins := player_coins
     * <+ %pp.(pp:players | player_coins(pp)+1)} — {@code players} é variável de máquina, não conjunto
     * literal, então a guarda {@code pp:players} traduz para {@code belongs(pp, players)}, um
     * predicado da lib, não uma função).
     */
    static String rewriteIntegerTernaryPredicateConditionForGhost(String ensure) {
        if (ensure == null || !ensure.startsWith("\\forall ") || !ensure.contains(" ? ")) {
            return ensure;
        }
        int semi = ensure.indexOf(';');
        if (semi < 0) return ensure;
        String forallPrefix = ensure.substring(0, semi + 1);
        String rest = ensure.substring(semi + 1).trim();
        int eqIdx = rest.indexOf(" == (");
        if (eqIdx < 0) return ensure;
        String funcApplyPart = rest.substring(0, eqIdx).trim();
        int openParen = eqIdx + " == (".length() - 1;
        int closeParen = findMatchingClose(rest, openParen);
        if (closeParen < 0 || closeParen != rest.length() - 1) {
            return ensure;
        }
        String inner = rest.substring(openParen + 1, closeParen);
        int qMark = findTopLevelChar(inner, '?', 0);
        if (qMark < 0) return ensure;
        int colon = findTopLevelChar(inner, ':', qMark + 1);
        if (colon < 0) return ensure;
        String pred = inner.substring(0, qMark).trim();
        String a = stripOuterParens(inner.substring(qMark + 1, colon).trim());
        String b = stripOuterParens(inner.substring(colon + 1).trim());
        return forallPrefix + " (" + pred + " ==> " + funcApplyPart + " == " + a + ")"
                + " && (!(" + pred + ") ==> " + funcApplyPart + " == " + b + ")";
    }

    /** Como {@link #findTopLevelComma}, mas procura {@code target} em vez de vírgula. */
    private static int findTopLevelChar(String s, char target, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                if (depth == 0) return -1;
                depth--;
            } else if (c == target && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** {@code TRUE}/{@code FALSE} B restantes → {@code \\true}/{@code \\false} no universo lógico ghost. */
    private static String normalizeBooleanLiteralsForGhost(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.replaceAll("\\bTRUE\\b", Matcher.quoteReplacement("\\true"))
                .replaceAll("\\bFALSE\\b", Matcher.quoteReplacement("\\false"));
    }

    /** Traduz {@code equals(ss, …)} para o universo {@code dummy_*} com {@code \\old} no RHS. */
    private static String toGhostEnsure(String translatedEnsure, Set<String> abstractVars) {
        String s = translatedEnsure.trim();

        // Conditional ensures: "(cond) ==> (inner_assigns)"
        // cond: all vars are pre-state (\old); inner: assignment ensures (dummy_lhs == \old(rhs))
        int implIdx = topLevelImplicationIndex(s);
        if (implIdx >= 0) {
            String condPart = s.substring(0, implIdx).trim();
            String innerPart = stripOuterParens(s.substring(implIdx + "==>".length()).trim());
            String condGhost = rewriteAbstractIdsWithOld(condPart, abstractVars);
            String innerGhost = toGhostEnsure(innerPart, abstractVars);
            return condGhost + " ==> (" + innerGhost + ")";
        }

        if (!s.startsWith("equals(")) {
            // "\forall T z; function_apply(v, z) == rhs" vem de BxmlExpressionToAcsl's
            // formatOverwriteWithLambdaAssignment (B "v := v <+ %z.(...)", sobreposição relacional
            // por lambda) — a MESMA razão do caso "function_apply(v, idx) == rhs" logo abaixo: "v"
            // aqui já denota o pós-estado (o \old(...) relevante já foi aplicado ao "rhs" pelo passo
            // partilhado em BxmlInitialisationTranslator, ANTES desta função ver o texto), então
            // cair no fallback genérico rewriteAbstractIdsWithOld também envolveria "v" errado
            // (\old(dummy_v) em vez de dummy_v) — e nem SEQUER bateria o formato "\forall" que esse
            // fallback assume (foi desenhado para o "cond" de uma implicação simples).
            if (s.startsWith("\\forall ")) {
                int semi = s.indexOf(';');
                if (semi > 0) {
                    String forallPrefix = s.substring(0, semi + 1);
                    String rest = s.substring(semi + 1).trim();
                    if (rest.startsWith("function_apply(")) {
                        int fnOpen = rest.indexOf('(');
                        int fnClose = findMatchingClose(rest, fnOpen);
                        if (fnClose > fnOpen) {
                            String argsPart = rest.substring(fnOpen + 1, fnClose);
                            int argComma = findTopLevelComma(argsPart, 0);
                            String tail = rest.substring(fnClose + 1).trim();
                            if (argComma >= 0 && tail.startsWith("==")) {
                                String v = argsPart.substring(0, argComma).trim();
                                String boundVarRef = argsPart.substring(argComma + 1).trim();
                                String rhs = tail.substring(2).trim();
                                if (abstractVars.contains(v)) {
                                    String vGhost = "dummy_" + v;
                                    String rhsGhost = GhostNamespacePrefixer.prefixAbstractVarsForGhost(rhs, abstractVars);
                                    return forallPrefix + " function_apply(" + vGhost + ", "
                                            + boundVarRef + ") == " + rhsGhost;
                                }
                            }
                        }
                    }
                }
            }
            // "function_apply(v, idx) == rhs" comes from B's relational-override assignment sugar
            // (f(x) := y, e.g. Price_i.setprice's "price(gg) := pp") via BxmlInitialisationTranslator's
            // generic body-to-ensures translation. Since ACSL "ensures" already denotes post-state,
            // "v" here means the NEW value — falling through to the generic rewriteAbstractIdsWithOld
            // below would wrongly wrap it as \old(dummy_v), asserting the postcondition about the
            // PRE-call function instead of the one just written.
            if (s.startsWith("function_apply(")) {
                int fnOpen = s.indexOf('(');
                int fnClose = findMatchingClose(s, fnOpen);
                if (fnClose > fnOpen) {
                    String argsPart = s.substring(fnOpen + 1, fnClose);
                    int argComma = findTopLevelComma(argsPart, 0);
                    String rest = s.substring(fnClose + 1).trim();
                    if (argComma >= 0 && rest.startsWith("==")) {
                        String v = argsPart.substring(0, argComma).trim();
                        String idx = argsPart.substring(argComma + 1).trim();
                        String rhs = rest.substring(2).trim();
                        if (abstractVars.contains(v)) {
                            String idxGhost = GhostNamespacePrefixer.prefixAbstractVarsForGhost(idx, abstractVars);
                            String rhsGhost = GhostNamespacePrefixer.prefixAbstractVarsForGhost(rhs, abstractVars);
                            return "function_apply(dummy_" + v + ", " + idxGhost + ") == " + rhsGhost;
                        }
                    }
                }
            }
            Matcher belongsM = GHOST_ENSURE_BELONGS.matcher(s);
            if (belongsM.matches()) {
                String v = stripIntegerCast(belongsM.group(1).trim());
                String setExpr = belongsM.group(2).trim();
                if (abstractVars.contains(v)) {
                    return "dummy_belongs(dummy_"
                            + v
                            + ", "
                            + GhostNamespacePrefixer.prefixGlobalLogicSetsForGhost(setExpr)
                            + ")";
                }
            }
            Matcher simpleEq = GHOST_ENSURE_SIMPLE_ABS_VAR_EQ.matcher(s);
            if (simpleEq.matches()) {
                String lhs = simpleEq.group(1);
                String rhs = simpleEq.group(2).trim();
                if (abstractVars.contains(lhs)) {
                    return "dummy_" + lhs + " == " + GhostNamespacePrefixer.prefixAbstractVarsForGhost(rhs, abstractVars);
                }
            }
            return rewriteAbstractIdsWithOld(s, abstractVars);
        }
        int open = s.indexOf('(');
        int comma = findTopLevelComma(s, open + 1);
        if (comma < 0) return rewriteAbstractIdsWithOld(s, abstractVars);
        String lhs = s.substring(open + 1, comma).trim();
        String rhs = s.substring(comma + 1, s.length() - 1).trim();
        String lhsGhost = lhs;
        for (String v : abstractVars) {
            if (lhs.equals(v)) {
                lhsGhost = "dummy_" + v;
                break;
            }
        }
        String rhsGhost = rewriteAbstractIdsWithOld(rhs, abstractVars);
        if (lhsGhost.startsWith("dummy_")
                && ("\\Nil".equals(rhsGhost) || "empty_seq".equals(rhsGhost))) {
            return lhsGhost + " == \\Nil";
        }
        return "equals(" + lhsGhost + ", " + rhsGhost + ")";
    }

    static int findTopLevelComma(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                if (depth == 0) return -1;
                depth--;
            } else if (c == ',' && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static String stripIntegerCast(String expr) {
        if (expr == null) {
            return "";
        }
        String t = expr.trim();
        if (t.startsWith("(integer)")) {
            return t.substring("(integer)".length()).trim();
        }
        if (t.startsWith("(int)")) {
            return t.substring("(int)".length()).trim();
        }
        return t;
    }

    private static String rewriteAbstractIdsWithOld(String expr, Set<String> abstractVars) {
        List<String> names = new ArrayList<>(abstractVars);
        names.sort((a, b) -> Integer.compare(b.length(), a.length()));
        String out = expr;
        for (String v : names) {
            // Pass 1: vars already inside \old(v) → \old(dummy_v); avoids double-wrapping.
            // \old( is a fixed-width 5-char lookbehind, valid in Java.
            Pattern alreadyWrapped = Pattern.compile(
                    "(?<=\\\\old\\()\\b" + Pattern.quote(v) + "\\b");
            Matcher m1 = alreadyWrapped.matcher(out);
            StringBuffer sb1 = new StringBuffer();
            while (m1.find()) {
                m1.appendReplacement(sb1, Matcher.quoteReplacement("dummy_" + v));
            }
            m1.appendTail(sb1);
            out = sb1.toString();
            // Pass 2: bare vars (not already dummy_-prefixed) → \old(dummy_v).
            Pattern bare = Pattern.compile("(?<!dummy_)\\b" + Pattern.quote(v) + "\\b");
            Matcher m2 = bare.matcher(out);
            StringBuffer sb2 = new StringBuffer();
            while (m2.find()) {
                m2.appendReplacement(sb2, Matcher.quoteReplacement("\\old(dummy_" + v + ")"));
            }
            m2.appendTail(sb2);
            out = sb2.toString();
        }
        return out;
    }

    /**
     * Encontra o índice do {@code ==>} de nível superior (fora de parênteses) em {@code s}.
     * Retorna {@code -1} se não houver.
     */
    private static int topLevelImplicationIndex(String s) {
        int depth = 0;
        for (int i = 0; i < s.length() - 2; i++) {
            char c = s.charAt(i);
            if (c == '(') { depth++; }
            else if (c == ')') { depth--; }
            else if (depth == 0 && c == '=' && i + 2 < s.length()
                    && s.charAt(i + 1) == '=' && s.charAt(i + 2) == '>') {
                return i;
            }
        }
        return -1;
    }

    /** Remove uma camada de parênteses externas, se a string inteira estiver envolvida por {@code (...)}. */
    private static String stripOuterParens(String s) {
        if (s == null || s.length() < 2) return s;
        if (s.charAt(0) != '(' || s.charAt(s.length() - 1) != ')') return s;
        int depth = 0;
        for (int i = 0; i < s.length() - 1; i++) {
            if (s.charAt(i) == '(') depth++;
            else if (s.charAt(i) == ')') { depth--; if (depth == 0) return s; }
        }
        return s.substring(1, s.length() - 1).trim();
    }

    private static List<String> collectGhostEnsuresFromInit(
            Element machineEl, Set<String> abstractSet, BxmlTranslateContext ctx) {
        List<String> out = new ArrayList<>();
        Element init = BxmlDomUtils.firstChildElement(machineEl, "Initialisation");
        if (init == null) return out;
        Element sub = BxmlDomUtils.firstSubChild(init);
        collectGhostEnsuresFromSubstitution(sub, abstractSet, ctx, out);
        return out;
    }

    private static void collectGhostEnsuresFromSubstitution(
            Element sub, Set<String> abstractSet, BxmlTranslateContext ctx, List<String> out) {
        if (sub == null) return;
        String ln = sub.getLocalName();
        switch (ln) {
            case "Assignement_Sub" -> {
                Element vars = BxmlDomUtils.firstChildElement(sub, "Variables");
                Element vals = BxmlDomUtils.firstChildElement(sub, "Values");
                if (vars == null || vals == null) return;
                List<Element> lhs = BxmlDomUtils.directExpChildren(vars);
                List<Element> rhs = BxmlDomUtils.directExpChildren(vals);
                int n = Math.min(lhs.size(), rhs.size());
                for (int i = 0; i < n; i++) {
                    String g =
                            ghostEnsureFromAssignment(lhs.get(i), rhs.get(i), abstractSet, ctx);
                    if (g != null && !g.isBlank()) out.add(g);
                }
            }
            case "Becomes_In" -> {
                Element vars = BxmlDomUtils.firstChildElement(sub, "Variables");
                Element value = BxmlDomUtils.firstChildElement(sub, "Value");
                if (vars == null || value == null) return;
                Element valExp = null;
                for (Element ve : BxmlDomUtils.directExpChildren(value)) {
                    valExp = ve;
                    break;
                }
                if (valExp == null) return;
                List<String> parts = new ArrayList<>();
                for (Element varExp : BxmlDomUtils.directExpChildren(vars)) {
                    if (!"Id".equals(varExp.getLocalName())) continue;
                    String v = varExp.getAttribute("value");
                    if (!abstractSet.contains(v)) continue;
                    String part = ghostMembershipEnsure(v, valExp, ctx);
                    if (part != null && !part.isBlank()) {
                        parts.add(part);
                    }
                }
                if (!parts.isEmpty()) {
                    out.add(String.join(" && ", parts));
                }
            }
            case "Nary_Sub" -> {
                String op = sub.getAttribute("op");
                if (";".equals(op) || "||".equals(op)) {
                    NodeList children = sub.getChildNodes();
                    for (int i = 0; i < children.getLength(); i++) {
                        Node n = children.item(i);
                        if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                        Element ch = (Element) n;
                        if ("Attr".equals(ch.getLocalName())) continue;
                        collectGhostEnsuresFromSubstitution(ch, abstractSet, ctx, out);
                    }
                } else {
                    collectGhostEnsuresFromSubstitution(BxmlDomUtils.firstSubChild(sub), abstractSet, ctx, out);
                }
            }
            case "Bloc_Sub" -> collectGhostEnsuresFromSubstitution(BxmlDomUtils.firstSubChild(sub), abstractSet, ctx, out);
            case "ANY_Sub" -> {
                Element thenEl = BxmlDomUtils.firstChildElement(sub, "Then");
                if (thenEl != null) {
                    collectGhostEnsuresFromSubstitution(BxmlDomUtils.firstSubChild(thenEl), abstractSet, ctx, out);
                }
            }
            default -> { }
        }
    }

    /**
     * {@code v :: S} no universo ghost — traduz o operador B {@code ::} (Becomes_In) para o
     * predicado da B2ACSLLib {@code becomes_element_of}, com as mesmas duas sobrecargas que
     * {@code BxmlInitialisationTranslator#becomesInMembershipEnsure} usa do lado NÃO-ghost
     * (função-arrow: {@code becomes_element_of(v, dom, rng)}; conjunto literal:
     * {@code becomes_element_of(v, S)}) — "-->" não existe como operador ACSL, então traduzir
     * literalmente (como faz o tradutor genérico de expressões) produz sintaxe inválida.
     * Devolve texto ainda por prefixar (dummy_/enumerado/global): o chamador
     * ({@link #collectGhostEnsuresFromInit}) já aplica esse pós-processamento a toda a linha.
     */
    private static String ghostMembershipEnsure(String v, Element setExp, BxmlTranslateContext ctx) {
        if (BxmlExpressionToAcsl.isFunctionArrowType(setExp)
                || BxmlExpressionToAcsl.isTotalSurjectionArrowType(setExp)) {
            Element[] domRng = BxmlExpressionToAcsl.twoDirectExpChildren(setExp);
            if (domRng[0] == null || domRng[1] == null) return null;
            String domainSet = BxmlExpressionToAcsl.intervalOrSetComprehensionRef(domRng[0], ctx);
            String rangeSet = BxmlExpressionToAcsl.translate(domRng[1], ctx);
            String base = "becomes_element_of(dummy_" + v + ", " + domainSet + ", " + rangeSet + ")";
            if (BxmlExpressionToAcsl.isTotalSurjectionArrowType(setExp)) {
                return base + " && is_surjective(dummy_" + v + ", " + rangeSet + ")";
            }
            return base;
        }
        String setExprText = BxmlExpressionToAcsl.translate(setExp, ctx);
        if (setExprText == null || setExprText.isBlank()) return null;
        return "becomes_element_of(dummy_" + v + ", " + setExprText + ")";
    }

    private static String ghostEnsureFromAssignment(
            Element lhsExp, Element rhsExp, Set<String> abstractSet, BxmlTranslateContext ctx) {
        if (!"Id".equals(lhsExp.getLocalName())) return null;
        String v = lhsExp.getAttribute("value");
        if (!abstractSet.contains(v)) return null;
        if ("EmptySet".equals(rhsExp.getLocalName())) {
            return "equals(dummy_" + v + ", empty(\\old(dummy_" + v + ")))";
        }
        if ("EmptySeq".equals(rhsExp.getLocalName()) && isListLikeVariable(v, ctx)) {
            return "dummy_" + v + " == \\Nil";
        }
        String rhs = BxmlExpressionToAcsl.translate(rhsExp, ctx);
        String rhsGhost = rewriteAbstractIdsWithOld(rhs, abstractSet);
        String logicType = ctx.variableLogicTypes().get(v);
        if (logicType != null
                && (logicType.equals("boolean")
                        || logicType.equals("integer")
                        || logicType.equals("int")
                        || logicType.equals("real"))) {
            return "dummy_" + v + " == " + rhsGhost;
        }
        return "equals(dummy_" + v + ", " + rhsGhost + ")";
    }

    private static boolean isListLikeVariable(String v, BxmlTranslateContext ctx) {
        String t = ctx.variableLogicTypes().get(v);
        return t != null && t.startsWith("\\list");
    }

    static void collectAssignedAbstractVarsInInit(
            Element machineEl, Set<String> abstractSet, Set<String> out) {
        Element init = BxmlDomUtils.firstChildElement(machineEl, "Initialisation");
        if (init == null) return;
        collectAssignedInSubstitution(BxmlDomUtils.firstSubChild(init), abstractSet, out);
    }

    static void collectAssignedAbstractVarsInBody(
            Element body, Set<String> abstractSet, Set<String> out) {
        Element sub = BxmlDomUtils.firstSubChild(body);
        collectAssignedInSubstitution(sub, abstractSet, out);
    }

    private static void collectAssignedInSubstitution(Element sub, Set<String> abstractSet, Set<String> out) {
        if (sub == null) return;
        String ln = sub.getLocalName();
        switch (ln) {
            case "Assignement_Sub", "Becomes_In", "Becomes_Such_That" -> {
                Element vars = BxmlDomUtils.firstChildElement(sub, "Variables");
                if (vars == null) return;
                for (Element id : BxmlDomUtils.directExpChildren(vars)) {
                    String overridden = functionOverrideTargetName(id);
                    if (overridden != null) {
                        if (abstractSet.contains(overridden)) out.add(overridden);
                        continue;
                    }
                    if (!"Id".equals(id.getLocalName())) continue;
                    String v = id.getAttribute("value");
                    if (abstractSet.contains(v)) out.add(v);
                }
            }
            case "Nary_Sub" -> {
                String op = sub.getAttribute("op");
                if (";".equals(op) || "||".equals(op)) {
                    // sequencial (;) e paralelo (||): percorrer todos os filhos
                    NodeList children = sub.getChildNodes();
                    for (int i = 0; i < children.getLength(); i++) {
                        Node n = children.item(i);
                        if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                        Element ch = (Element) n;
                        if ("Attr".equals(ch.getLocalName())) continue;
                        collectAssignedInSubstitution(ch, abstractSet, out);
                    }
                } else {
                    collectAssignedInSubstitution(BxmlDomUtils.firstSubChild(sub), abstractSet, out);
                }
            }
            case "Bloc_Sub" -> collectAssignedInSubstitution(BxmlDomUtils.firstSubChild(sub), abstractSet, out);
            case "If_Sub" -> {
                // Coleta assigns do ramo THEN (e ELSE, se existir)
                Element thenEl = BxmlDomUtils.firstChildElement(sub, "Then");
                if (thenEl != null) {
                    collectAssignedInSubstitution(BxmlDomUtils.firstSubChild(thenEl), abstractSet, out);
                }
                Element elseEl = BxmlDomUtils.firstChildElement(sub, "Else");
                if (elseEl != null) {
                    collectAssignedInSubstitution(BxmlDomUtils.firstSubChild(elseEl), abstractSet, out);
                }
            }
            case "Select" -> collectAssignedInSelect(sub, abstractSet, out);
            case "ANY_Sub" -> {
                Element thenEl = BxmlDomUtils.firstChildElement(sub, "Then");
                if (thenEl != null) {
                    collectAssignedInSubstitution(BxmlDomUtils.firstSubChild(thenEl), abstractSet, out);
                }
            }
            default -> { }
        }
    }

    private static void collectAssignedInSelect(
            Element select, Set<String> abstractSet, Set<String> out) {
        Element whenClauses = BxmlDomUtils.firstChildElement(select, "When_Clauses");
        if (whenClauses != null) {
            NodeList children = whenClauses.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node n = children.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element when = (Element) n;
                if (!"When".equals(when.getLocalName())) continue;
                Element thenEl = BxmlDomUtils.firstChildElement(when, "Then");
                if (thenEl != null) {
                    collectAssignedInSubstitution(BxmlDomUtils.firstSubChild(thenEl), abstractSet, out);
                }
            }
        }
        Element elseEl = BxmlDomUtils.firstChildElement(select, "Else");
        if (elseEl != null) {
            collectAssignedInSubstitution(BxmlDomUtils.firstSubChild(elseEl), abstractSet, out);
        }
    }

    /** Nomes em {@code Abstract_Variables} (ordem do BXML). */
    public static List<String> listAbstractVariableNames(Element machineEl) {
        List<String> out = new ArrayList<>();
        Element block = BxmlDomUtils.firstChildElement(machineEl, "Abstract_Variables");
        if (block == null) return out;
        NodeList ch = block.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            if ("Id".equals(e.getLocalName())) {
                String v = e.getAttribute("value");
                if (v != null && !v.isBlank()) out.add(v.trim());
            }
        }
        return out;
    }

    static int findMatchingClose(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /**
     * Converte texto de {@code ghost_operations.ci} para especificações ghost no {@code merged_code.c}:
     * restaura nomes ACSL de conjuntos ({@code dummy_M__S} → {@code S}) antes de remover o prefixo
     * {@code dummy_} das variáveis/funções.
     */
    public static String stripDummyPrefixForMergedGhostSpecs(String ghostText) {
        if (ghostText == null || ghostText.isEmpty()) {
            return ghostText;
        }
        String out = ghostText;
        Matcher setDecl =
                Pattern.compile("logic\\s+DSet<[^>]+>\\s+(dummy_[A-Za-z0-9_]+)\\s*;").matcher(ghostText);
        List<String> dummySetNames = new ArrayList<>();
        while (setDecl.find()) {
            dummySetNames.add(setDecl.group(1));
        }
        dummySetNames.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String dummySet : dummySetNames) {
            if (!dummySet.startsWith("dummy_")) {
                continue;
            }
            String rest = dummySet.substring("dummy_".length());
            int sep = rest.lastIndexOf("__");
            if (sep < 0 || sep + 2 >= rest.length()) {
                continue;
            }
            String stripped = rest.substring(sep + 2);
            // set comprehension globals keep the machine__ prefix in ACSL (e.g. iter_services__set_comprehension_1)
            out = out.replace(dummySet, stripped.contains("set_comprehension") ? rest : stripped);
        }
        out = out.replace("dummy_", "");
        // DSet<A>/DTuple<A,B> → Set<A>/Tuple<A,B> no contexto do merged_code.c
        out = out.replaceAll("\\bDSet<", "Set<");
        out = out.replaceAll("\\bDTuple<", "Tuple<");
        return out;
    }

    /**
     * Normaliza comparações booleanas em especificações ghost fundidas (0/1, alinhado a {@code _Bool}).
     */
    public static String normalizeIntegerBoolComparisonsInMergedGhostSpecs(String ghostText) {
        if (ghostText == null || ghostText.isEmpty()) {
            return ghostText;
        }
        return ghostText
                .replaceAll("==\\s*\\\\false\\b", "== 0")
                .replaceAll("==\\s*\\\\true\\b", "== 1")
                .replaceAll("!=\\s*\\\\false\\b", "!= 0")
                .replaceAll("!=\\s*\\\\true\\b", "!= 1");
    }

    static Element firstPredChildElement(Element parent) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            return e;
        }
        return null;
    }

    /**
     * B's relational-override assignment sugar {@code f(x) := y} (desugars to
     * {@code f := f <+ {x |-> y}}) puts the target inside a {@code Binary_Exp op='('} (function
     * application) as the sole {@code Variables} child, instead of a direct {@code Id} — e.g.
     * {@code Price_i.setprice}'s {@code price(gg) := pp}. Without recognizing this shape,
     * {@code price} was never detected as assigned, so no ghost update / concrete {@code assigns}
     * was ever generated for the operation (fell back to {@code assigns \nothing;}). Returns the
     * base function/variable name, or {@code null} if {@code e} isn't this shape.
     */
    private static String functionOverrideTargetName(Element e) {
        if (e == null || !"Binary_Exp".equals(e.getLocalName()) || !"(".equals(e.getAttribute("op"))) {
            return null;
        }
        List<Element> children = BxmlDomUtils.directExpChildren(e);
        if (children.isEmpty()) return null;
        Element first = children.get(0);
        if ("Id".equals(first.getLocalName())) {
            return first.getAttribute("value");
        }
        return functionOverrideTargetName(first);
    }

    /** Visibilidade de pacote: reaproveitado por {@link BxmlOperationsTranslator} (mesmo fim). */
    record Param(String type, String name) {}

    private record GhostOp(
            String cName, List<Param> params, Set<String> assignsAbstract, List<String> ghostEnsures) {

        /** Formato aceite pelo pré-processamento Frama-C em {@code .ci}: primeira cláusula {@code /@ assigns}. */
        String format() {
            StringBuilder sb = new StringBuilder();
            sb.append("/*@ ghost\n");
            sb.append("  /@ assigns ");
            if (assignsAbstract.isEmpty()) {
                sb.append("\\nothing");
            } else {
                List<String> ghosts = new ArrayList<>();
                for (String v : assignsAbstract) {
                    ghosts.add("ghost_" + v);
                }
                sb.append(String.join(", ", ghosts));
            }
            sb.append(";\n");
            for (String e : ghostEnsures) {
                sb.append("  @ ensures ").append(e).append(";\n");
            }
            sb.append("    @/\n");
            sb.append("  void ").append(cName).append("(").append(formatParams()).append(");\n");
            sb.append("*/\n\n");
            return sb.toString();
        }

        private String formatParams() {
            if (params.isEmpty()) return "void";
            List<String> parts = new ArrayList<>();
            for (Param p : params) {
                parts.add(GhostParamTypeResolver.formatCParameterDecl(p.type(), p.name()));
            }
            return String.join(", ", parts);
        }
    }
}
