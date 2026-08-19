package com.example.bxml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Declara variáveis de máquina B em blocos {@code axiomatic …_variables}, com tipos inferidos
 * preferencialmente a partir do invariante (ex.: {@code numbers <: NAT} → {@code Set<integer>}) e
 * recurso ao {@code typref} de {@code Abstract_Variables} / {@code Concrete_Variables}.
 * Em refinamento fundido: {@code logic T v = return_valid_v(abs);} quando há ligação no invariante
 * ({@link BxmlConnectionAcsl#linkingAbstractVariableName}).
 * Constantes concretas têm bloco próprio ({@link #inferConcreteConstantsLogicTypes} + contexto).
 *
 * <p>Sequências B ({@code iseq}, {@code POW(T*T)}) traduzem-se para {@code \list<elemento>} em ACSL.
 */
public final class BxmlMachineVariables {

    private BxmlMachineVariables() {}

    /**
     * Ordem: declaração em {@code Abstract_Variables} / {@code Concrete_Variables}; tipos do
     * invariante sobrepõem-se ao {@code typref} quando há informação compatível.
     */
    public static Map<String, String> inferVariableLogicTypes(
            Element machineEl, BxmlTranslateContext ctx) {
        BxmlTypeRegistry types = ctx.types();
        List<Element> varIds = listDeclaredVariableIds(machineEl);
        Map<String, String> fromInvariant = inferTypesFromInvariants(machineEl, types);

        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (Element idEl : varIds) {
            String name = idEl.getAttribute("value");
            if (name == null || name.isBlank()) continue;
            String trAttr = idEl.getAttribute("typref");
            int typref = trAttr.isBlank() ? -1 : Integer.parseInt(trAttr.trim());

            String t = fromInvariant.get(name);
            if (t == null) {
                t = types.acslVariableLogicTypeFromTypref(typref);
            }
            out.put(name, t);
        }
        return out;
    }

    /**
     * Tipos {@code logic} só para {@code Concrete_Constants} (para fundir no
     * {@link BxmlTranslateContext#variableLogicTypes()} sem duplicar no bloco {@code _variables}).
     */
    public static Map<String, String> inferConcreteConstantsLogicTypes(
            Element machineEl, BxmlTypeRegistry types) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        Element block = BxmlDomUtils.firstChildElement(machineEl, "Concrete_Constants");
        if (block == null) return out;
        NodeList ch = block.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            if (!"Id".equals(e.getLocalName())) continue;
            String name = e.getAttribute("value");
            if (name == null || name.isBlank()) continue;
            String trAttr = e.getAttribute("typref");
            int typref = trAttr.isBlank() ? -1 : Integer.parseInt(trAttr.trim());
            out.put(name, types.acslVariableLogicTypeFromTypref(typref));
        }
        return out;
    }

    /**
     * Tipos {@code logic} só para {@code Abstract_Constants} tipadas em {@code PROPERTIES} por um
     * predicado de sequência ({@code C : seq(T)}, {@code iseq(T)}, etc.) — sem isto, {@link
     * BxmlConstantsAndProperties#formatAbstractConstantsBlock} cai direto no {@code typref} cru, que
     * não distingue {@code \list<T>} de {@code Relation<integer,T>} (ambos codificam-se como
     * {@code POW(INTEGER*T)} no tipo B bruto — mesma ambiguidade documentada em {@link
     * #inferTypesFromInvariants} para variáveis, mas nunca coberta para constantes abstratas até
     * ao exemplo cv_seq, cujo {@code SQ : seq(0..9)} declarava {@code Relation<integer,integer>
     * SQ;} em vez de {@code \list<integer> SQ;}, quebrando toda chamada a {@code front}/{@code
     * last}/{@code is_seq_of} sobre {@code SQ} (assinaturas da lib só aceitam {@code \list<A>}).
     */
    public static Map<String, String> inferAbstractConstantsLogicTypesFromProperties(
            Element machineEl, BxmlTypeRegistry types) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        Element block = BxmlDomUtils.firstChildElement(machineEl, "Abstract_Constants");
        if (block == null) return out;
        Map<String, String> fromProperties = inferTypesFromProperties(machineEl, types);
        if (fromProperties.isEmpty()) return out;
        NodeList ch = block.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName()) || !"Id".equals(e.getLocalName())) continue;
            String name = e.getAttribute("value");
            if (name == null || name.isBlank()) continue;
            String t = fromProperties.get(name);
            if (t != null) out.put(name, t);
        }
        return out;
    }

    /** Nomes de variáveis declaradas ({@code Abstract_Variables} / {@code Concrete_Variables}). */
    public static Set<String> declaredVariableNames(Element machineEl) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Element idEl : listDeclaredVariableIds(machineEl)) {
            String name = idEl.getAttribute("value");
            if (name != null && !name.isBlank()) {
                names.add(name.trim());
            }
        }
        return names;
    }

    /**
     * Nomes de variáveis declaradas por qualquer máquina em {@code machineNames} (tipicamente o
     * fecho transitivo SEES/IMPORTS de {@link BxmlSetsTranslator#listSeenMachineNamesTransitive}/
     * {@link BxmlSetsTranslator#listImportedMachineNamesTransitive}) — usado por {@link
     * BxmlExpressionToAcsl#translate} para não tratar como "variável livre" de uma lambda {@code %}
     * um identificador que já é estado de OUTRA máquina visível (ex. {@code array} de {@code
     * VArray}, importada por {@code Fifo_i_2}): {@code ctx.variableLogicTypes()} só cobre a
     * PRÓPRIA máquina sendo traduzida.
     */
    public static Set<String> transitiveCrossMachineVariableNames(
            Collection<String> machineNames, Path bxmlDirectory) {
        if (machineNames == null || machineNames.isEmpty() || bxmlDirectory == null) {
            return Set.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String name : machineNames) {
            Element impl = BxmlSetsTranslator.findImplementationMachineElement(name, bxmlDirectory);
            if (impl != null) {
                out.addAll(declaredVariableNames(impl));
            }
        }
        return out;
    }

    /**
     * Como {@link #transitiveCrossMachineVariableNames}, mas devolvendo também o tipo {@code logic}
     * de cada variável (ex. {@code array} → {@code Function_int_int}) — usado quando uma variável de
     * OUTRA máquina precisa entrar como PARÂMETRO explícito (tipado) de uma função lógica gerada
     * (ex. {@code lambda_funcNN} recursivo sobre sequência), não apenas ser excluída da deteção de
     * variável livre.
     */
    public static Map<String, String> transitiveCrossMachineVariableLogicTypes(
            Collection<String> machineNames, Path bxmlDirectory) {
        if (machineNames == null || machineNames.isEmpty() || bxmlDirectory == null) {
            return Map.of();
        }
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (String name : machineNames) {
            // Camada ABSTRATA primeiro: a tipagem "v : seq(T)"/"v : iseq(T)" de uma variável (ex.
            // "contents" em Fifo.bxml) vive no invariante ABSTRATO — o invariante de colagem da
            // implementação usa a variável sem a retipar, por isso inferVariableLogicTypes só na
            // implementação nunca a encontra (fica sem entrada, cai no fallback por typref cru em
            // isListValued/isRelationOrFunctionValued, que não distingue \list de Relation).
            Element abs = BxmlSetsTranslator.findAbstractMachineElement(name, bxmlDirectory);
            if (abs != null) {
                BxmlTypeRegistry absTypes = BxmlTypeRegistry.fromMachine(abs);
                BxmlTranslateContext absTmp =
                        new BxmlTranslateContext(
                                absTypes, BxmlComprehensionRegistry.emptyForFingerprinting());
                for (Map.Entry<String, String> e : inferVariableLogicTypes(abs, absTmp).entrySet()) {
                    out.putIfAbsent(e.getKey(), e.getValue());
                }
            }
            Element impl = BxmlSetsTranslator.findImplementationMachineElement(name, bxmlDirectory);
            if (impl == null) continue;
            BxmlTypeRegistry types = BxmlTypeRegistry.fromMachine(impl);
            BxmlTranslateContext tmp =
                    new BxmlTranslateContext(types, BxmlComprehensionRegistry.emptyForFingerprinting());
            for (Map.Entry<String, String> e : inferVariableLogicTypes(impl, tmp).entrySet()) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /**
     * Verdadeiro quando alguma implementação fundida não declara variáveis próprias e reutiliza o
     * estado da abstrata (liga {@code logic v = Raiz__v;} no bloco {@code Raiz_variables}).
     *
     * <p>Implementações com {@code IMPORTS} ficam excluídas: nesse caso o estado concreto vem de
     * outra máquina via linking invariant, o que requer ghost abstraction em vez de C globals
     * da abstrata (ex.: {@code Mult_i} importa {@code Body} → {@code nn} usa {@code reads ghost_nn},
     * e o invariante {@code loopnn == nn} faz a ligação formal).
     */
    public static boolean anyImplementationUsesAbstractVariablesOnly(
            Element abstractMachineEl, List<Element> mergedMachineElements) {
        if (abstractMachineEl == null || mergedMachineElements == null || mergedMachineElements.isEmpty()) {
            return false;
        }
        Set<String> ownNames = declaredVariableNames(abstractMachineEl);
        if (ownNames.isEmpty()) {
            return false;
        }
        // Ligação ESTRUTURAL sólida (variável representada como array/função total em memória —
        // o invariante da própria abstrata a tipa com "-->", via concreteVariableFunctionArrowElement,
        // o mesmo mecanismo de implementationRhsTotalFunctionFromArray/array_to_function_int).
        boolean hasArrayBackedVariable = false;
        for (String v : ownNames) {
            if (concreteVariableFunctionArrowElement(abstractMachineEl, v) != null) {
                hasArrayBackedVariable = true;
                break;
            }
        }
        for (Element mel : mergedMachineElements) {
            if (!isImplementationMachine(mel)) {
                continue;
            }
            if (!declaredVariableNames(mel).isEmpty()) {
                continue;
            }
            // Sem Concrete_Variables próprias: a implementação manipula a variável abstrata
            // diretamente OU herda o estado de uma máquina importada via invariante de colagem
            // (ex. "loopnn == nn" em Mult_i, ligando o "nn" de Mult ao "loopnn" de Body). Sem
            // IMPORTS não há ambiguidade nenhuma (não existe outra máquina para se colar) — qualifica
            // sempre. Com IMPORTS, só qualifica se houver ligação array-backed sólida OU nenhum
            // invariante próprio: uma variável cuja única "ligação" é invariante de colagem com
            // OUTRA máquina não tem representação concreta própria nenhuma — defini-la diretamente
            // como a variável da outra máquina tornaria essa invariante de colagem uma tautologia
            // (nn == loopnn == Body__loopnn, nunca prova nada sobre a sincronização real); precisa
            // continuar ghost, com a colagem verificada como obrigação de prova de verdade. Mas uma
            // invariante de colagem só pode existir DENTRO do <Invariant> da própria implementação
            // (é isso que a expressaria) — sem NENHUM invariante próprio (ex.: cv_struct_i, cujo
            // IMPORTS cv_base existe só para PROMOTES bset, sem relação nenhuma com sv), não há
            // ambiguidade nenhuma a proteger, mesmo com IMPORTS presente por outra razão. Sem este
            // caso, "sv" ficava sem ligação C nenhuma ("logic integer sv;" sem "= cv_struct__sv"),
            // deixando WP sem forma de observar as suas atribuições ("assigns \\nothing" inválido).
            if (hasArrayBackedVariable
                    || BxmlSetsTranslator.listImportedMachineNames(mel).isEmpty()
                    || !hasNonTrivialInvariant(mel)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Verdadeiro se {@code mel} tem um {@code <Invariant>} com predicado real (não ausente).
     * Uma invariante de colagem entre a variável concreta e uma máquina importada só pode
     * viver dentro do PRÓPRIO {@code <Invariant>} de {@code mel} — sem nenhum, não há como
     * existir ambiguidade nenhuma a proteger em {@link #anyImplementationUsesAbstractVariablesOnly}.
     */
    private static boolean hasNonTrivialInvariant(Element mel) {
        Element inv = BxmlDomUtils.firstChildElement(mel, "Invariant");
        return inv != null && BxmlDomUtils.firstPredChild(inv) != null;
    }

    /**
     * Verdadeiro quando a máquina abstrata NÃO TEM nenhuma implementação/refinamento fundida (B
     * clássico: {@code CONCRETE_VARIABLES} declaradas diretamente na própria abstrata, sem
     * separação em {@code MACHINE}/{@code IMPLEMENTATION} — ex.: {@code cv_rel}, cujo {@code bdp/}
     * só tem {@code cv_rel.bxml}, sem {@code cv_rel_i.bxml}/{@code cv_rel_r.bxml}) e pelo menos uma
     * das suas próprias variáveis é array-backed (tipada como {@code v : lo..hi --> T} no seu
     * próprio {@code <Invariant>}, via {@link #concreteVariableFunctionArrowElement}) — nesse caso a
     * máquina deve ligar-se A SI PRÓPRIA ({@code logic v = array_to_function_int((int*)(Raiz__v),
     * tamanho);}) em vez de ficar como variável lógica ghost sem RHS, já que não existe nenhuma
     * máquina de implementação separada para prover essa ligação (o que dava "unbound logic
     * variable" no {@code -wp}, mesmo com um {@code .c} escrito à mão fornecendo o array real).
     */
    public static boolean abstractMachineIsSelfContainedArrayBacked(
            Element abstractMachineEl, List<Element> mergedMachineElements) {
        if (abstractMachineEl == null) {
            return false;
        }
        if (mergedMachineElements != null && !mergedMachineElements.isEmpty()) {
            return false;
        }
        for (String v : declaredVariableNames(abstractMachineEl)) {
            if (concreteVariableFunctionArrowElement(abstractMachineEl, v) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Variáveis abstratas cuja implementação declara {@code Concrete_Variables} com o MESMO NOME
     * (namespaces B são por máquina — reusar o nome é o padrão normal quando a implementação é um
     * refinamento direto da variável abstrata, ex. {@code limit} tanto em {@code Customer} quanto
     * em {@code Customer_i}), e cuja própria implementação a tipa como array/função total
     * ({@code v : lo..hi --> T} no {@code <Invariant>} da implementação, via
     * {@link #concreteVariableFunctionArrowElement}).
     *
     * <p>Diferente de {@link #anyImplementationUsesAbstractVariablesOnly} (implementação SEM
     * {@code Concrete_Variables} próprias — reusa o estado abstrato diretamente) — este caso é o
     * oposto: a implementação TEM a sua própria variável concreta, mas com o nome idêntico ao da
     * abstrata (não um nome distinto como {@code price}/{@code price_i}, onde o autor B escolheu
     * deliberadamente duas identidades ligadas por uma invariante de colagem explícita — nesse caso
     * mantém-se ghost + array-backed como duas entidades). Quando o nome é o mesmo, não há
     * invariante de colagem separada no B fonte (a igualdade é implícita ao partilhar o nome) — só
     * faz sentido UMA variável ACSL: a definição direta a partir do array, sem camada ghost redundante
     * (evita o padrão duplo "logic ... v reads dummy_ghost_v;" + "logic ... v_i = array_to_function…"
     * nunca ligados por um {@code equals(v_i, v)} explícito, que nem sequer era gerado).
     */
    public static Set<String> collapsedIntoImplementationVariableNames(
            Element abstractMachineEl, List<Element> mergedMachineElements) {
        Set<String> out = new LinkedHashSet<>();
        if (abstractMachineEl == null || mergedMachineElements == null || mergedMachineElements.isEmpty()) {
            return out;
        }
        Set<String> ownNames = declaredVariableNames(abstractMachineEl);
        if (ownNames.isEmpty()) {
            return out;
        }
        for (Element mel : mergedMachineElements) {
            if (!isImplementationMachine(mel)) continue;
            for (String v : declaredVariableNames(mel)) {
                if (!ownNames.contains(v)) continue;
                if (concreteVariableFunctionArrowElement(mel, v) != null) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    /**
     * Verdadeiro quando alguma máquina fundida do tipo {@code implementation} declara exactamente
     * o mesmo conjunto de variáveis que a abstrata raiz (evita duplicar {@code logic v} no ACSL).
     */
    public static boolean implementationMirrorsAbstractVariables(
            Element abstractMachineEl, List<Element> mergedMachineElements) {
        if (abstractMachineEl == null || mergedMachineElements == null || mergedMachineElements.isEmpty()) {
            return false;
        }
        Set<String> abstractNames = declaredVariableNames(abstractMachineEl);
        if (abstractNames.isEmpty()) {
            return false;
        }
        for (Element mel : mergedMachineElements) {
            if (!isImplementationMachine(mel)) {
                continue;
            }
            if (abstractNames.equals(declaredVariableNames(mel))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Implementação com o mesmo conjunto de variáveis que a abstrata: o estado C é o da especificação
     * ({@code logic v = Raiz__v;}) e não se usa camada ghost paralela.
     *
     * <p>Terceira forma (além das duas que exigem uma implementação SEPARADA em {@code
     * mergedMachineElements}): a máquina abstrata pode não ter implementação NENHUMA e ligar o seu
     * próprio array C sozinha ({@link #abstractMachineIsSelfContainedArrayBacked}, ex.: cv_rel).
     * Sem esta terceira opção, {@code needsGhostAbstraction} concluía (incorretamente) que uma
     * máquina assim self-contained PRECISAVA de camada ghost — mas nenhuma camada ghost é
     * gerada para esse caso (nunca foi preciso até cv_rel), então nenhum {@code assigns} concreto
     * chegava a ser calculado (os resolvers de {@code ConcreteAssignTargetResolver} ficam presos
     * atrás de {@code !useGhostAbstraction}), e as operações que escrevem o array (ex.
     * {@code cv_rel__upd}) acabavam com {@code assigns \nothing} — contrato UNSOUND.
     */
    public static boolean usesDirectImplementationVariables(
            Element abstractMachineEl, List<Element> mergedMachineElements) {
        return implementationMirrorsAbstractVariables(abstractMachineEl, mergedMachineElements)
                || anyImplementationUsesAbstractVariablesOnly(
                        abstractMachineEl, mergedMachineElements)
                || abstractMachineIsSelfContainedArrayBacked(abstractMachineEl, mergedMachineElements);
    }

    /** Ghost só quando há refinamento/implementação com estado distinto do da abstrata. */
    public static boolean needsGhostAbstraction(
            Element abstractMachineEl, List<Element> mergedMachineElements) {
        if (abstractMachineEl == null || declaredVariableNames(abstractMachineEl).isEmpty()) {
            return false;
        }
        return !usesDirectImplementationVariables(abstractMachineEl, mergedMachineElements);
    }

    /** {@code Abstract_Variables} e {@code Concrete_Variables} (filhos diretos de {@code Machine}). */
    public static List<Element> listDeclaredVariableIds(Element machineEl) {
        List<Element> out = new ArrayList<>();
        String[] sections = {"Abstract_Variables", "Concrete_Variables"};
        for (String sec : sections) {
            Element block = BxmlDomUtils.firstChildElement(machineEl, sec);
            if (block == null) continue;
            NodeList ch = block.getChildNodes();
            for (int i = 0; i < ch.getLength(); i++) {
                Node n = ch.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element e = (Element) n;
                if ("Attr".equals(e.getLocalName())) continue;
                if ("Id".equals(e.getLocalName())) out.add(e);
            }
        }
        return out;
    }

    /**
     * Nomes para cláusulas {@code assigns} na inicialização: variáveis de implementação
     * ({@code Concrete_Variables}) de cada máquina fundida com {@code type="implementation"},
     * no formato {@code NomeMaquinaAbstrata__nomeVar} (contrato C alinhado à raiz abstrata).
     */
    static boolean isImplementationMachine(Element machineEl) {
        String t = machineEl.getAttribute("type");
        return t != null && "implementation".equalsIgnoreCase(t.trim());
    }

    static boolean isAbstractionMachine(Element machineEl) {
        String t = machineEl.getAttribute("type");
        return t != null && "abstraction".equalsIgnoreCase(t.trim());
    }

    static boolean isRefinementMachine(Element machineEl) {
        String t = machineEl.getAttribute("type");
        return t != null && "refinement".equalsIgnoreCase(t.trim());
    }

    /**
     * Sobe a árvore DOM a partir de {@code node} até encontrar o elemento-máquina que o contém
     * (o primeiro ancestral com {@code type="implementation"|"abstraction"|"refinement"}) — usado
     * por {@code BxmlLoopTranslator} para obter a máquina de IMPLEMENTAÇÃO real a partir de um nó
     * qualquer do corpo de uma operação/loop, já que só a implementação tem loops B e só ela
     * declara as suas próprias {@code Concrete_Variables} (ex. {@code player_islands_i}, nunca
     * visível em {@link #declaredVariableNames} da máquina abstrata).
     */
    static Element enclosingMachineElement(Node node) {
        Node cur = node;
        while (cur != null) {
            if (cur.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) cur;
                if (isImplementationMachine(el) || isAbstractionMachine(el) || isRefinementMachine(el)) {
                    return el;
                }
            }
            cur = cur.getParentNode();
        }
        return null;
    }

    /**
     * Percorre todos os {@code <Invariant>} e extrai restrições de tipo:
     * <ul>
     *   <li>{@code v <: T} com {@code T} nome de tipo base → {@code Set<…>}</li>
     *   <li>{@code v : T} com {@code T} primitivo → elemento escalar</li>
     *   <li>{@code v : iseq(T)} → {@code \list<elemento(T)>} (sequências em ACSL)</li>
     * </ul>
     */
    static Map<String, String> inferTypesFromInvariants(Element machineEl, BxmlTypeRegistry types) {
        LinkedHashMap<String, String> acc = new LinkedHashMap<>();
        NodeList children = machineEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (!"Invariant".equals(e.getLocalName())) continue;
            Element pred = BxmlDomUtils.firstPredChild(e);
            if (pred != null) walkPredForVariableTypes(pred, types, acc);
        }
        return acc;
    }

    /**
     * Como {@link #inferTypesFromInvariants}, mas para {@code <Properties>} — usado pelas
     * {@code Abstract_Constants} tipadas por sequência (ex. {@code SQ : seq(0..9)}). Reaproveita o
     * mesmo caminhador recursivo de predicados: o único {@code Nary_Pred op="&"} sob
     * {@code <Properties>} (todos os conjuntos {@code &}-separados da cláusula B ficam achatados
     * num só nó) é percorrido filho a filho, tal como um invariante multi-conjunto.
     */
    static Map<String, String> inferTypesFromProperties(Element machineEl, BxmlTypeRegistry types) {
        LinkedHashMap<String, String> acc = new LinkedHashMap<>();
        Element propsEl = BxmlDomUtils.firstChildElement(machineEl, "Properties");
        if (propsEl == null) return acc;
        Element pred = BxmlDomUtils.firstPredChild(propsEl);
        if (pred != null) walkPredForVariableTypes(pred, types, acc);
        return acc;
    }

    private static void walkPredForVariableTypes(
            Element p, BxmlTypeRegistry types, Map<String, String> acc) {
        String ln = p.getLocalName();
        switch (ln) {
            case "Exp_Comparison" -> handleExpComparisonForTypes(p, types, acc);
            case "Nary_Pred", "Unary_Pred", "Binary_Pred" -> {
                NodeList nl = p.getChildNodes();
                for (int i = 0; i < nl.getLength(); i++) {
                    Node n = nl.item(i);
                    if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                    Element ch = (Element) n;
                    if ("Attr".equals(ch.getLocalName())) continue;
                    walkPredForVariableTypes(ch, types, acc);
                }
            }
            default -> {
            }
        }
    }

    private static void handleExpComparisonForTypes(
            Element cmp, BxmlTypeRegistry types, Map<String, String> acc) {
        String op = normalizeComparisonOp(cmp.getAttribute("op"));
        Element[] pair = BxmlExpressionToAcsl.twoDirectExpChildren(cmp);
        if (pair[0] == null || pair[1] == null) return;

        if ("<:".equals(op)) {
            if ("Id".equals(pair[0].getLocalName())) {
                String v = pair[0].getAttribute("value");
                if ("Id".equals(pair[1].getLocalName())) {
                    String rhs = pair[1].getAttribute("value");
                    if (isNamedBaseType(rhs)) {
                        acc.put(v, "Set<" + types.acslElementTypeName(rhs) + ">");
                    }
                }
            }
            return;
        }
        if (":".equals(op)) {
            if ("Id".equals(pair[0].getLocalName())) {
                String v = pair[0].getAttribute("value");
                if ("Id".equals(pair[1].getLocalName())) {
                    String rhs = pair[1].getAttribute("value");
                    if (isNamedBaseType(rhs)) {
                        acc.put(v, types.acslElementTypeName(rhs));
                    }
                    return;
                }
                if (isSequenceTypingOp(pair[1])) {
                    acc.put(v, acslListTypeForSequenceUnary(pair[1], types));
                }
            }
        }
    }

    private static boolean isSequenceTypingOp(Element exp) {
        if (!"Unary_Exp".equals(exp.getLocalName())) return false;
        String op = exp.getAttribute("op");
        return "seq".equals(op) || "iseq".equals(op) || "seq1".equals(op) || "iseq1".equals(op)
                || "perm".equals(op);
    }

    /**
     * Tipo elemento ACSL de {@code seq(T)}/{@code iseq(T)}/{@code seq1(T)}/{@code iseq1(T)}/
     * {@code perm(T)} no B → {@code \list<…>}. Recursivo: {@code T} pode ele próprio ser
     * {@code seq(U)} (ex. {@code CC : seq(seq(0..9))}, sequência de sequências), produzindo
     * {@code \list<\list<integer>>} em vez de colapsar erradamente para {@code \list<integer>}.
     */
    private static String acslListTypeForSequenceUnary(Element unarySeq, BxmlTypeRegistry types) {
        Element arg = BxmlDomUtils.firstNonAttrElementChild(unarySeq);
        String inner = acslSequenceElementType(arg, types);
        // Espaço antes do "> " final quando o tipo interno também termina em ">" (ex.
        // \list<\list<integer>>, sequência de sequências, CC : seq(seq(0..9))) — o lexer do
        // Frama-C tokeniza ">>" como um único operador de shift, não dois fechos de genérico
        // consecutivos (mesmo problema clássico do C++ pré-C++11; ver
        // BxmlTypeRegistry#spaceOutAdjacentClosingAngleBrackets para o caso análogo em Set<Tuple<>>).
        return "\\list<" + inner + (inner.endsWith(">") ? " " : "") + ">";
    }

    /** Tipo elemento ACSL do domínio {@code T} de {@code seq(T)} — "integer" por omissão (intervalos, NAT…). */
    private static String acslSequenceElementType(Element arg, BxmlTypeRegistry types) {
        if (arg == null) return "integer";
        if (isSequenceTypingOp(arg)) {
            return "\\list<" + acslSequenceElementType(BxmlDomUtils.firstNonAttrElementChild(arg), types) + ">";
        }
        if ("Id".equals(arg.getLocalName())) {
            return types.acslElementTypeName(arg.getAttribute("value"));
        }
        return "integer";
    }

    private static String normalizeComparisonOp(String op) {
        if (op == null) return "";
        String o = op.trim();
        if ("&lt;:".equals(o)) return "<:";
        return o;
    }

    private static boolean isNamedBaseType(String name) {
        if (name == null || name.isBlank()) return false;
        return switch (name) {
            case "NAT", "INTEGER", "INT", "BOOL" -> true;
            default -> false;
        };
    }

    /**
     * Nomes de PARÂMETROS DE OPERAÇÃO tipados por um conjunto diferido/enumerado na PRECONDITION
     * de UMA operação (ex. {@code "pp"} para {@code AddPlayer(pp) = PRE pp:PLAYER & ... END}) —
     * ver {@link BxmlTranslateContext#withDeferredSetTypedParameterNames}.
     *
     * <p>Necessário porque, DENTRO do corpo/loop da IMPLEMENTAÇÃO (ex. usado em indexação de
     * array), o {@code typref} do MESMO parâmetro pode legitimamente ter sido refinado pelo
     * próprio AtelierB para {@code INTEGER} (confirmado: {@code pp} tem {@code typref} resolvendo
     * a {@code INTEGER} em {@code RulerOfTheSeas_i.bxml}, mas a {@code PLAYER} em
     * {@code RulerOfTheSeas.bxml}, mesmo referenciando o MESMO parâmetro da MESMA operação) — mas a
     * assinatura C REAL gerada continua a usar o tipo enum (ex. {@code RulerOfTheSeas__PLAYER pp}
     * em {@code RulerOfTheSeas_i.c}), então o cast {@code (integer)} continua a ser necessário
     * sempre que o parâmetro entra num slot ACSL genérico (ex. {@code couple<A,B>}) — sem isto,
     * "invalid cast from Tuple&lt;EnumType,...&gt; to Tuple&lt;integer,...&gt;".
     *
     * <p><b>Deliberadamente por OPERAÇÃO, não por máquina inteira.</b> Uma primeira versão
     * varria TODAS as operações da máquina para um único {@code Set<String>} partilhado — quebrou
     * o Biblioteca: {@code addBook}/{@code addCopy}/{@code removeCopy} têm um parâmetro escalar
     * {@code cc:COPY}, mas {@code copiesQuery}'s {@code cc} é um parâmetro de SAÍDA (array/ponteiro
     * booleano), sem relação nenhuma com {@code COPY} na sua própria precondition — o nome coincide
     * entre operações DIFERENTES da mesma máquina, cada uma com seu próprio escopo B. Com um set
     * global, o {@code cc} escalar de {@code addBook} "vazava" para {@code copiesQuery} e forçava
     * um cast/deref (\{@code (integer)*cc}) sobre o array de saída, produzindo ACSL inválido
     * ({@code lambda_func01} chamado como função quando é predicado). {@code precondition} aqui
     * deve ser a Precondition da OPERAÇÃO ABSTRATA sendo traduzida (a implementação tipicamente
     * OMITE a sua própria Precondition, herdando-a inteiramente da abstrata) — o chamador
     * ({@code BxmlOperationsTranslator}) já a tem em mãos por operação, dentro do próprio laço.
     */
    public static Set<String> deferredSetTypedOperationParameterNames(
            Element precondition, Set<String> declaredSetNames) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (precondition == null || declaredSetNames == null || declaredSetNames.isEmpty()) {
            return out;
        }
        collectDeferredSetTypedParamNames(BxmlDomUtils.firstPredChild(precondition), declaredSetNames, out);
        return out;
    }

    private static void collectDeferredSetTypedParamNames(
            Element pred, Set<String> enumeratedSetNames, Set<String> out) {
        if (pred == null) return;
        String ln = pred.getLocalName();
        if ("Exp_Comparison".equals(ln)) {
            String op = BxmlDomUtils.normalizeColonLikeOp(pred.getAttribute("op"));
            if (":".equals(op)) {
                Element[] pair = BxmlExpressionToAcsl.twoDirectExpChildren(pred);
                if (pair[0] != null
                        && "Id".equals(pair[0].getLocalName())
                        && pair[1] != null
                        && "Id".equals(pair[1].getLocalName())
                        && enumeratedSetNames.contains(pair[1].getAttribute("value").trim())) {
                    out.add(pair[0].getAttribute("value").trim());
                }
            }
            return;
        }
        if ("Nary_Pred".equals(ln)) {
            NodeList nl = pred.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element ch = (Element) n;
                if ("Attr".equals(ch.getLocalName())) continue;
                collectDeferredSetTypedParamNames(ch, enumeratedSetNames, out);
            }
            return;
        }
        if ("Unary_Pred".equals(ln)) {
            collectDeferredSetTypedParamNames(BxmlDomUtils.firstPredChild(pred), enumeratedSetNames, out);
            return;
        }
        if ("Binary_Pred".equals(ln)) {
            Element[] pair = BxmlDomUtils.twoDirectPredChildren(pred);
            collectDeferredSetTypedParamNames(pair[0], enumeratedSetNames, out);
            collectDeferredSetTypedParamNames(pair[1], enumeratedSetNames, out);
        }
    }

    /**
     * B: {@code v : Dom --> Cod} num invariante → tipo {@code logic} como função total ({@code
     * Function<A,B>}, instanciação genérica ACSL já resolvida via {@code type Function<A,B> =
     * Relation<A,B>;} em {@code types.acsl}) em vez de {@code Relation_*_*}.
     */
    static String relationLogicTypeToFunctionLogicType(String relationLogicType) {
        if (relationLogicType == null || relationLogicType.isBlank()) {
            return "Function<integer,integer>";
        }
        String t = relationLogicType.trim();
        // Instanciação genérica (ex.: Relation<integer,integer>) → Function<A,B> via o mesmo par de
        // parâmetros: resolve para QUALQUER par A,B via type Function<A,B> = Relation<A,B>; em
        // types.acsl, sem depender de aliases achatados estáticos que não existem nessa forma.
        if (t.startsWith("Relation<")) {
            return "Function<" + t.substring("Relation<".length());
        }
        // Nome achatado legado (ex.: Relation_Tuple_integer_integer_boolean, codomínio tupla N>=2):
        // declarado explicitamente em par com o correspondente Function_* pelo TupleCodomainTypeRegistry
        // (ver BxmlTypeRegistry#registerCartesianRelationType) — mantém a mesma substituição de prefixo.
        if (t.startsWith("Relation_")) {
            return "Function_" + t.substring("Relation_".length());
        }
        return t;
    }

    static boolean concreteVariableInvIsTotalArrowFunction(Element implMachineEl, String varName) {
        return concreteVariableFunctionArrowElement(implMachineEl, varName) != null;
    }

    /** {@code Binary_Exp} {@code -->} que tipa {@code varName}, ou null. */
    static Element concreteVariableFunctionArrowElement(Element implMachineEl, String varName) {
        if (implMachineEl == null || varName == null || varName.isBlank()) {
            return null;
        }
        Element inv = BxmlDomUtils.firstChildElement(implMachineEl, "Invariant");
        if (inv == null) {
            return null;
        }
        Element pred = BxmlDomUtils.firstPredChild(inv);
        return findFunctionArrowExpForVariableMembership(pred, varName);
    }

    private static Element findFunctionArrowExpForVariableMembership(Element pred, String varName) {
        if (pred == null) {
            return null;
        }
        String ln = pred.getLocalName();
        if ("Exp_Comparison".equals(ln)) {
            String op = BxmlDomUtils.normalizeColonLikeOp(pred.getAttribute("op"));
            if (":".equals(op)) {
                Element[] pair = BxmlExpressionToAcsl.twoDirectExpChildren(pred);
                if (pair[0] != null
                        && "Id".equals(pair[0].getLocalName())
                        && varName.equals(pair[0].getAttribute("value").trim())
                        && pair[1] != null
                        && BxmlExpressionToAcsl.isFunctionArrowType(pair[1])) {
                    return pair[1];
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
                Element found = findFunctionArrowExpForVariableMembership(ch, varName);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if ("Unary_Pred".equals(ln)) {
            return findFunctionArrowExpForVariableMembership(BxmlDomUtils.firstPredChild(pred), varName);
        }
        if ("Binary_Pred".equals(ln)) {
            Element[] pair = BxmlDomUtils.twoDirectPredChildren(pred);
            if (pair[0] != null) {
                Element f = findFunctionArrowExpForVariableMembership(pair[0], varName);
                if (f != null) {
                    return f;
                }
            }
            if (pair[1] != null) {
                return findFunctionArrowExpForVariableMembership(pair[1], varName);
            }
        }
        return null;
    }

    /**
     * Lado direito da ligação à implementação C: array {@code Raiz__v} como função (índices 0..n-1).
     * Usa {@code array_to_function_bool} para domínios booleanos e {@code array_to_function_int}
     * para inteiros. O tamanho é derivado do domínio da seta {@code -->} ou da secção VALUES.
     */
    static String implementationRhsTotalFunctionFromArray(
            String rootAbstractName,
            String varName,
            Element implMachineEl,
            BxmlTranslateContext ctx,
            Path bxmlDirectory) {
        Element arrow = concreteVariableFunctionArrowElement(implMachineEl, varName);
        boolean boolCodomain = arrow != null && arrowCodomainIsBool(arrow);
        String cast = boolCodomain ? "(_Bool*)(" : "(int*)(";
        String q = rootAbstractName + "__" + varName;
        Element domain = arrow != null ? BxmlExpressionToAcsl.twoDirectExpChildren(arrow)[0] : null;
        if (isCompoundProductExp(domain)) {
            // Domínio composto (ex. player_islands_i : PLAYER*ISLAND --> BOOL, matriz
            // característica 2D) — cada lado tem a SUA PRÓPRIA cardinalidade, resolvida
            // separadamente (ver #domainElementCardinalityAcsl); array_to_function_bool/int só
            // sabe construir uma Function_integer_X de domínio ESCALAR a partir de um array 1D, por
            // isso usa-se aqui o par array2d_to_relation_bool/int (domínio Tuple<integer,integer>,
            // array C genuinamente 2D, layout row-major — ver
            // B2ACSLLib/function_functions/array2d_to_relation_*.acsl).
            Element[] domainParts = BxmlExpressionToAcsl.twoDirectExpChildren(domain);
            String rows = domainElementCardinalityAcsl(domainParts[0], ctx, implMachineEl, bxmlDirectory);
            String cols = domainElementCardinalityAcsl(domainParts[1], ctx, implMachineEl, bxmlDirectory);
            if (rows == null || rows.isBlank()) rows = "1";
            if (cols == null || cols.isBlank()) cols = "1";
            String funcName2d = boolCodomain ? "array2d_to_relation_bool" : "array2d_to_relation_int";
            return funcName2d + "(" + cast + q + "), " + rows + ", " + cols + ")";
        }
        String len = arrow != null ? arrayDomainCardinalityAcsl(arrow, ctx, implMachineEl, bxmlDirectory) : null;
        if (len == null || len.isBlank()) {
            len = "1";
        }
        String funcName = boolCodomain ? "array_to_function_bool" : "array_to_function_int";
        return funcName + "(" + cast + q + "), " + len + ")";
    }

    /**
     * Produto B {@code A*B} (ex. domínio composto de um {@code -->}) em {@code Binary_Exp}. Na
     * árvore de EXPRESSÃO (invariante, ao contrário da tabela {@code TypeInfos} usada por {@link
     * BxmlTypeRegistry}) o BXML grava o produto cartesiano de conjuntos como {@code *s}, não
     * {@code *} nu (que aqui significaria multiplicação aritmética) — ver
     * {@link BxmlExpressionToAcsl#isCartesianProduct}.
     */
    static boolean isCompoundProductExp(Element e) {
        return e != null
                && "Binary_Exp".equals(e.getLocalName())
                && BxmlExpressionToAcsl.isCartesianProduct(e.getAttribute("op"));
    }

    /**
     * Cardinalidade do intervalo/domínio de {@code -->}; consulta a secção VALUES para conjuntos
     * nomeados — primeiro na própria máquina, depois (se {@code bxmlDirectory} não for nulo) nas
     * máquinas vistas (SEES), pois um deferred set (ex. {@code GOODS}) é tipicamente valorado na
     * implementação de OUTRA máquina (ex. {@code Goods_i}), não na máquina que o usa.
     */
    private static String arrayDomainCardinalityAcsl(Element arrowEl, BxmlTranslateContext ctx,
            Element implMachineEl, Path bxmlDirectory) {
        if (arrowEl == null || ctx == null) {
            return null;
        }
        Element[] domRng = BxmlExpressionToAcsl.twoDirectExpChildren(arrowEl);
        if (domRng[0] == null) {
            return null;
        }
        return domainElementCardinalityAcsl(domRng[0], ctx, implMachineEl, bxmlDirectory);
    }

    /**
     * Cardinalidade de UM lado de um domínio (intervalo, ou nome de conjunto via VALUES) —
     * extraído de {@link #arrayDomainCardinalityAcsl} para ser reutilizável tanto no domínio
     * inteiro de um {@code -->} escalar quanto em CADA lado, separadamente, de um domínio composto
     * {@code A*B --> C} (ver {@link #implementationRhsTotalFunctionFromArray}).
     */
    private static String domainElementCardinalityAcsl(Element domain, BxmlTranslateContext ctx,
            Element implMachineEl, Path bxmlDirectory) {
        if (domain == null || ctx == null) {
            return null;
        }
        if (BxmlExpressionToAcsl.isIntervalBinaryExp(domain)) {
            Element[] lr = BxmlExpressionToAcsl.twoDirectExpChildren(domain);
            if (lr[0] != null && lr[1] != null) {
                String low = BxmlExpressionToAcsl.translate(lr[0], ctx).trim();
                String high = BxmlExpressionToAcsl.translate(lr[1], ctx).trim();
                return "(" + high + " - (" + low + ") + 1)";
            }
        }
        if ("Id".equals(domain.getLocalName()) && implMachineEl != null) {
            String setName = domain.getAttribute("value");
            String card = lookupSetCardinalityFromValues(setName, implMachineEl, ctx, bxmlDirectory);
            if (card != null) return card;
        }
        return null;
    }

    /** Verdadeiro se o codomínio da seta {@code -->} for {@code BOOL}. */
    private static boolean arrowCodomainIsBool(Element arrowEl) {
        if (arrowEl == null) return false;
        Element[] domRng = BxmlExpressionToAcsl.twoDirectExpChildren(arrowEl);
        if (domRng[1] == null) return false;
        Element codomain = domRng[1];
        return "Id".equals(codomain.getLocalName()) && "BOOL".equals(codomain.getAttribute("value"));
    }

    /** Procura {@code <Valuation ident='setName'>} só na {@code <Values>} da própria {@code machineEl}. */
    private static String lookupSetCardinalityFromOwnValues(String setName, Element machineEl,
            BxmlTranslateContext ctx) {
        if (setName == null || setName.isBlank() || machineEl == null) return null;
        NodeList children = machineEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            if (!"Values".equals(el.getLocalName())) continue;
            NodeList vals = el.getChildNodes();
            for (int j = 0; j < vals.getLength(); j++) {
                Node vn = vals.item(j);
                if (vn.getNodeType() != Node.ELEMENT_NODE) continue;
                Element val = (Element) vn;
                if (!"Valuation".equals(val.getLocalName())) continue;
                if (!setName.equals(val.getAttribute("ident"))) continue;
                Element exp = BxmlDomUtils.firstNonAttrElementChild(val);
                if (exp != null && BxmlExpressionToAcsl.isIntervalBinaryExp(exp)) {
                    Element[] lr = BxmlExpressionToAcsl.twoDirectExpChildren(exp);
                    if (lr[0] != null && lr[1] != null) {
                        String low = BxmlExpressionToAcsl.translate(lr[0], ctx).trim();
                        String high = BxmlExpressionToAcsl.translate(lr[1], ctx).trim();
                        return "(" + high + " - (" + low + ") + 1)";
                    }
                }
                break;
            }
        }
        return null;
    }

    /**
     * Como {@link #lookupSetCardinalityFromOwnValues(String, Element, BxmlTranslateContext)}, mas
     * quando não encontra na própria {@code machineEl} e {@code bxmlDirectory} não é nulo, procura
     * também nas máquinas vistas (SEES) de {@code machineEl} — um deferred set (ex. {@code GOODS},
     * visto por {@code Price}/{@code Price_i}) é tipicamente valorado na implementação de OUTRA
     * máquina (ex. {@code Goods_i}), nunca na máquina que só o usa. Sem isto, a cardinalidade fica
     * por resolver e o chamador cai no fallback errado de tamanho {@code 1} — ver
     * {@link #implementationRhsTotalFunctionFromArray}.
     */
    static String lookupSetCardinalityFromValues(String setName, Element machineEl,
            BxmlTranslateContext ctx, Path bxmlDirectory) {
        String local = lookupSetCardinalityFromOwnValues(setName, machineEl, ctx);
        if (local != null || bxmlDirectory == null || machineEl == null) {
            return local;
        }
        for (String seenName : BxmlSetsTranslator.listReferencedMachineNames(machineEl)) {
            Element seenEl = BxmlSetsTranslator.findImplementationMachineElement(seenName, bxmlDirectory);
            if (seenEl == null) continue;
            String found = lookupSetCardinalityFromOwnValues(setName, seenEl, ctx);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Quando a implementação não tem variáveis próprias mas importa outra máquina que fornece o
     * estado concreto via linking invariant (ex.: {@code loopnn == nn}), devolve o mapa
     * {variável-abstrata → nome-C-da-variável-importada} (ex.: {@code "nn" → "Body__loopnn"}).
     * Usado para substituir a fórmula padrão {@code Raiz__v} no bloco axiomatic de variáveis.
     */
    public static Map<String, String> buildAbstractVarRhsOverrides(
            Element abstractMachineEl,
            List<Element> mergedMachineElements,
            List<String> importedMachineNames,
            Path bxmlDirectory) {
        if (!anyImplementationUsesAbstractVariablesOnly(abstractMachineEl, mergedMachineElements)
                || importedMachineNames == null
                || importedMachineNames.isEmpty()
                || bxmlDirectory == null) {
            return Map.of();
        }
        Set<String> abstractVarNames = declaredVariableNames(abstractMachineEl);
        if (abstractVarNames.isEmpty()) return Map.of();

        Element implEl = null;
        for (Element mel : mergedMachineElements) {
            if (isImplementationMachine(mel) && declaredVariableNames(mel).isEmpty()) {
                implEl = mel;
                break;
            }
        }
        if (implEl == null) return Map.of();

        // importedVar → importedMachineName (abstract vars of the imported machine)
        Map<String, String> importedVarToMachine = new LinkedHashMap<>();
        for (String importedName : importedMachineNames) {
            Path absPath = bxmlDirectory.resolve(importedName + ".bxml");
            if (!Files.exists(absPath)) continue;
            try {
                Element importedAbstractEl = BxmlSetsTranslator.parseMachineElement(absPath);
                for (String v : declaredVariableNames(importedAbstractEl)) {
                    importedVarToMachine.put(v, importedName);
                }
            } catch (Exception ignored) {}
        }
        if (importedVarToMachine.isEmpty()) return Map.of();

        Element invEl = BxmlDomUtils.firstChildElement(implEl, "Invariant");
        if (invEl == null) return Map.of();

        Map<String, String> result = new LinkedHashMap<>();
        collectLinkingVarPairs(invEl, abstractVarNames, importedVarToMachine, result);
        return result;
    }

    private static void collectLinkingVarPairs(
            Element node,
            Set<String> abstractVarNames,
            Map<String, String> importedVarToMachine,
            Map<String, String> result) {
        String localName = node.getLocalName();
        if ("Exp_Comparison".equals(localName) && "=".equals(node.getAttribute("op"))) {
            List<Element> ch = nonAttrChildren(node);
            if (ch.size() == 2) {
                String lhsId = singleIdValue(ch.get(0));
                String rhsId = singleIdValue(ch.get(1));
                if (lhsId != null && rhsId != null) {
                    if (abstractVarNames.contains(rhsId) && importedVarToMachine.containsKey(lhsId)) {
                        result.put(rhsId, importedVarToMachine.get(lhsId) + "__" + lhsId);
                    } else if (abstractVarNames.contains(lhsId) && importedVarToMachine.containsKey(rhsId)) {
                        result.put(lhsId, importedVarToMachine.get(rhsId) + "__" + rhsId);
                    }
                }
            }
            return;
        }
        for (Element child : nonAttrChildren(node)) {
            collectLinkingVarPairs(child, abstractVarNames, importedVarToMachine, result);
        }
    }

    private static List<Element> nonAttrChildren(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                Element e = (Element) n;
                if (!"Attr".equals(e.getLocalName())) result.add(e);
            }
        }
        return result;
    }

    private static String singleIdValue(Element el) {
        return "Id".equals(el.getLocalName()) ? el.getAttribute("value") : null;
    }

    /**
     * Targets {@code assigns machineName__varName} for ALL concrete variables of each imported
     * machine's implementation BXML ({@code name_i.bxml}).  Added to INITIALISATION assigns because
     * Atelier B always calls imported-machine INITIALISATION in the generated C code.
     */
    public static List<String> listImportedMachineConcreteAssigns(
            List<String> importedMachineNames, Path bxmlDirectory) {
        if (importedMachineNames == null || importedMachineNames.isEmpty() || bxmlDirectory == null) {
            return List.of();
        }
        // LinkedHashSet: loadConcreteAssignsForImportedMachine agora expande IMPORTS
        // transitivamente, então uma máquina importada transitivamente por duas vias diferentes
        // (ex.: "array" e, separadamente, "iter_services" já vindo do fecho transitivo de quem
        // chama) não deve gerar "assigns X;" duplicado.
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String name : importedMachineNames) {
            result.addAll(CrossMachineBxmlLoader.loadConcreteAssignsForImportedMachine(name, bxmlDirectory));
        }
        return List.copyOf(result);
    }

    /**
     * Converte alvos de {@code assigns} array-backed ({@code "Nome[low .. high]"}) para a forma
     * usada por {@code \separated} ({@code "Nome + (low .. high)"}); descarta alvos sem colchetes
     * (variáveis escalares/{@code ghost_*}, sem sentido de endereço-de-vetor para {@code
     * \separated}).
     */
    public static List<String> toSeparatedPointerRanges(List<String> assignTargets) {
        if (assignTargets == null || assignTargets.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String t : assignTargets) {
            if (t == null) continue;
            int open = t.indexOf('[');
            int close = t.lastIndexOf(']');
            if (open < 0 || close < open) continue;
            String name = t.substring(0, open).trim();
            if (name.isEmpty()) continue;
            // Array 2D+ (ex. "Nome[..][..]", matriz característica genuína — ver
            // implementationAssignTargetWithRange): "Nome + (range)" só faz sentido para UM par
            // de colchetes — extrair "entre o primeiro '[' e o último ']'" para 2+ pares dava
            // "..][.." (colchetes internos sobreviviam dentro do "range", "[Syntax error] ]").
            // Uma primeira tentativa usava o nome NU (\separated(p, Nome)) assumindo que isso
            // denotasse o objeto inteiro — Frama-C rejeita: "there is no implicit conversion
            // between a C array and a pointer" (\separated exige algo pointer-like, ao contrário
            // de "assigns Nome[..]", que é sintaxe de localização, não uma chamada de predicado).
            // "&Nome[0][0] + (0 .. rows*cols-1)" resolveria, mas exigiria threading das dimensões
            // concretas até aqui (perdidas: o alvo já chega como "[..][..]" genérico, sem
            // rows/cols) — sem isso, omite-se a cláusula para este alvo em vez de emitir ACSL
            // inválido; o modelo de memória "typed" do WP continua a assumir a separação
            // implicitamente (ver javadoc de listArraySeparationTargets), só perde a documentação
            // explícita que esta função dá aos casos 1D.
            int firstClose = t.indexOf(']');
            if (firstClose >= 0 && firstClose != close) {
                continue;
            }
            String range = t.substring(open + 1, close).trim();
            if (range.isEmpty()) continue;
            out.add(name + " + (" + range + ")");
        }
        return List.copyOf(out);
    }

    /**
     * Alvos array ({@code "Nome + (low .. high)"}) para {@code requires \separated(saída, alvo);}
     * de parâmetros de saída (ponteiros C): variáveis array-backed da PRÓPRIA máquina mais as de
     * todas as máquinas transitivamente {@code IMPORTS}/{@code SEES}/{@code USES} ({@code
     * relatedMachineNames} — já resolvido pelo chamador, tipicamente a união de {@link
     * com.example.bxml.BxmlSetsTranslator#listImportedMachineNamesTransitive} e {@link
     * com.example.bxml.BxmlSetsTranslator#listSeenMachineNamesTransitive}). Um parâmetro de saída
     * pode, em C, apontar para qualquer região de memória global do programa — sem separação
     * explícita destas, o modelo de memória "typed" do WP assume-a implicitamente (ver aviso
     * "Memory model hypotheses" do Frama-C); tornar isto explícito no contrato documenta a hipótese
     * e permite ao chamador (e ao próprio WP) verificá-la.
     */
    public static List<String> listArraySeparationTargets(
            String machineName,
            List<Element> mergedMachineElements,
            BxmlTranslateContext ctx,
            Collection<String> relatedMachineNames,
            Path bxmlDirectory) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.addAll(
                toSeparatedPointerRanges(
                        ConcreteAssignTargetResolver.listImplementationAssignTargets(
                                machineName, mergedMachineElements, ctx, bxmlDirectory)));
        if (relatedMachineNames != null && !relatedMachineNames.isEmpty() && bxmlDirectory != null) {
            out.addAll(
                    toSeparatedPointerRanges(
                            listImportedMachineConcreteAssigns(
                                    new ArrayList<>(relatedMachineNames), bxmlDirectory)));
        }
        return List.copyOf(out);
    }

    /**
     * Map from each imported-machine operation local name to the concrete assign targets of that
     * machine.  Used to union called-operation assigns into the caller's {@code assigns} clause.
     */
    public static Map<String, List<String>> buildImportedOperationAssignsMap(
            List<String> importedMachineNames, Path bxmlDirectory) {
        return buildImportedOperationAssignsMap(importedMachineNames, List.of(), bxmlDirectory);
    }

    /**
     * Como {@link #buildImportedOperationAssignsMap(List, Path)}, mas também propaga
     * {@code __fc_random_counter} para operações de máquinas <em>vistas</em> ({@code SEES}) que
     * usam {@code Becomes_In}.  Máquinas vistas não contribuem com assigns concretos (as suas
     * variáveis não pertencem ao chamador), apenas com {@code __fc_random_counter}.
     */
    public static Map<String, List<String>> buildImportedOperationAssignsMap(
            List<String> importedMachineNames, List<String> seenMachineNames, Path bxmlDirectory) {
        if (bxmlDirectory == null) return Map.of();
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();

        if (importedMachineNames != null) {
            for (String machineName : importedMachineNames) {
                List<String> machineAssigns = CrossMachineBxmlLoader.loadConcreteAssignsForImportedMachine(machineName, bxmlDirectory);
                Set<String> opsWithDirectRand = CrossMachineBxmlLoader.loadOperationNamesWithBecomesIn(machineName, bxmlDirectory);
                Set<String> opsWithTransitiveRand = CrossMachineBxmlLoader.loadOperationNamesCallingRandOps(machineName, bxmlDirectory);
                if (machineAssigns.isEmpty() && opsWithDirectRand.isEmpty() && opsWithTransitiveRand.isEmpty()) continue;
                for (String opName : CrossMachineBxmlLoader.loadOperationNamesForMachine(machineName, bxmlDirectory)) {
                    List<String> opAssigns = new ArrayList<>(machineAssigns);
                    if (opsWithDirectRand.contains(opName) || opsWithTransitiveRand.contains(opName)) {
                        opAssigns.add("__fc_random_counter");
                    }
                    if (!opAssigns.isEmpty()) {
                        result.computeIfAbsent(opName, k -> new ArrayList<>()).addAll(opAssigns);
                    }
                }
            }
        }

        // Máquinas vistas: só __fc_random_counter (sem assigns concretos)
        if (seenMachineNames != null) {
            for (String machineName : seenMachineNames) {
                Set<String> opsWithDirectRand = CrossMachineBxmlLoader.loadOperationNamesWithBecomesIn(machineName, bxmlDirectory);
                Set<String> opsWithTransitiveRand = CrossMachineBxmlLoader.loadOperationNamesCallingRandOps(machineName, bxmlDirectory);
                if (opsWithDirectRand.isEmpty() && opsWithTransitiveRand.isEmpty()) continue;
                for (String opName : CrossMachineBxmlLoader.loadOperationNamesForMachine(machineName, bxmlDirectory)) {
                    if (opsWithDirectRand.contains(opName) || opsWithTransitiveRand.contains(opName)) {
                        result.computeIfAbsent(opName, k -> new ArrayList<>()).add("__fc_random_counter");
                    }
                }
            }
        }

        return result;
    }

}
