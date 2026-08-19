package com.example.bxml;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Element;

/**
 * Contexto compartilhado na tradução BXML → ACSL (tipos + índice de conjuntos em compreensão +
 * tipos {@code logic} das variáveis para igualdade conjunto vs escalar).
 */
public record BxmlTranslateContext(
        BxmlTypeRegistry types,
        BxmlComprehensionRegistry comprehensions,
        Map<String, String> variableLogicTypes,
        LambdaFunctionRegistry lambdaRegistry,
        Map<String, String> enumValueRenames,
        Map<String, String> enumeratedSetRenames,
        Set<String> enumeratedSetNames,
        Set<String> multiArgLambdaConstantNames,
        String machineName,
        Set<String> crossMachineVariableNames,
        Map<String, String> crossMachineVariableLogicTypes,
        Set<String> declaredSetNames,
        SigmaFunctionRegistry sigmaRegistry,
        UnionInterFunctionRegistry unionInterRegistry,
        Set<String> deferredSetTypedParameterNames) {

    /** Sem registo de lambdas/sigma/union-inter nem renomeação de enumerados. */
    public BxmlTranslateContext(
            BxmlTypeRegistry types,
            BxmlComprehensionRegistry comprehensions,
            Map<String, String> variableLogicTypes) {
        this(types, comprehensions, variableLogicTypes, null, Map.of(), Map.of(), Set.of(), Set.of(), "",
                Set.of(), Map.of(), Set.of(), null, null, Set.of());
    }

    public BxmlTranslateContext(BxmlTypeRegistry types, BxmlComprehensionRegistry comprehensions) {
        this(types, comprehensions, Map.of(), null, Map.of(), Map.of(), Set.of(), Set.of(), "", Set.of(),
                Map.of(), Set.of(), null, null, Set.of());
    }

    /** Retorna uma cópia deste contexto com o registo de lambdas substituído. */
    public BxmlTranslateContext withLambdaRegistry(LambdaFunctionRegistry registry) {
        return new BxmlTranslateContext(
                types,
                comprehensions,
                variableLogicTypes,
                registry,
                enumValueRenames,
                enumeratedSetRenames,
                enumeratedSetNames,
                multiArgLambdaConstantNames,
                machineName,
                crossMachineVariableNames,
                crossMachineVariableLogicTypes,
                declaredSetNames,
                sigmaRegistry,
                unionInterRegistry,
                deferredSetTypedParameterNames);
    }

    /** Retorna uma cópia deste contexto com o registo de funções SIGMA/PI/MIN/MAX substituído. */
    public BxmlTranslateContext withSigmaRegistry(SigmaFunctionRegistry registry) {
        return new BxmlTranslateContext(
                types,
                comprehensions,
                variableLogicTypes,
                lambdaRegistry,
                enumValueRenames,
                enumeratedSetRenames,
                enumeratedSetNames,
                multiArgLambdaConstantNames,
                machineName,
                crossMachineVariableNames,
                crossMachineVariableLogicTypes,
                declaredSetNames,
                registry,
                unionInterRegistry,
                deferredSetTypedParameterNames);
    }

    /** Retorna uma cópia deste contexto com o registo de funções UNION/INTER substituído. */
    public BxmlTranslateContext withUnionInterRegistry(UnionInterFunctionRegistry registry) {
        return new BxmlTranslateContext(
                types,
                comprehensions,
                variableLogicTypes,
                lambdaRegistry,
                enumValueRenames,
                enumeratedSetRenames,
                enumeratedSetNames,
                multiArgLambdaConstantNames,
                machineName,
                crossMachineVariableNames,
                crossMachineVariableLogicTypes,
                declaredSetNames,
                sigmaRegistry,
                registry,
                deferredSetTypedParameterNames);
    }

    /** Retorna uma cópia deste contexto com o mapa de renomeação de valores enumerados substituído. */
    public BxmlTranslateContext withEnumRenames(Map<String, String> renames) {
        return new BxmlTranslateContext(
                types,
                comprehensions,
                variableLogicTypes,
                lambdaRegistry,
                renames == null ? Map.of() : renames,
                enumeratedSetRenames,
                enumeratedSetNames,
                multiArgLambdaConstantNames,
                machineName,
                crossMachineVariableNames,
                crossMachineVariableLogicTypes,
                declaredSetNames,
                sigmaRegistry,
                unionInterRegistry,
                deferredSetTypedParameterNames);
    }

    /** Retorna uma cópia com renomeação de conjuntos enumerados (ex. {@code ALARM_STATUS} → {@code ctx__ALARM_STATUS}). */
    public BxmlTranslateContext withEnumeratedSetRenames(Map<String, String> renames) {
        return new BxmlTranslateContext(
                types,
                comprehensions,
                variableLogicTypes,
                lambdaRegistry,
                enumValueRenames,
                renames == null ? Map.of() : renames,
                enumeratedSetNames,
                multiArgLambdaConstantNames,
                machineName,
                crossMachineVariableNames,
                crossMachineVariableLogicTypes,
                declaredSetNames,
                sigmaRegistry,
                unionInterRegistry,
                deferredSetTypedParameterNames);
    }

    /** Retorna uma cópia deste contexto com os nomes de conjuntos enumerados substituídos. */
    public BxmlTranslateContext withEnumeratedSetNames(Set<String> setNames) {
        return new BxmlTranslateContext(
                types,
                comprehensions,
                variableLogicTypes,
                lambdaRegistry,
                enumValueRenames,
                enumeratedSetRenames,
                setNames == null ? Set.of() : setNames,
                multiArgLambdaConstantNames,
                machineName,
                crossMachineVariableNames,
                crossMachineVariableLogicTypes,
                declaredSetNames,
                sigmaRegistry,
                unionInterRegistry,
                deferredSetTypedParameterNames);
    }

    /**
     * Retorna uma cópia deste contexto com os nomes de variáveis de máquinas SEES/IMPORTS
     * (transitivo) substituídos — usado por {@link com.example.bxml.BxmlExpressionToAcsl} para não
     * tratar como "variável livre" (parâmetro extra) da função lógica gerada para {@code %} um
     * identificador que já é uma variável de estado de outra máquina visível (ex. {@code array} de
     * {@code VArray}, importada por {@code Fifo_i_2}) — {@link #variableLogicTypes()} só cobre as
     * variáveis da PRÓPRIA máquina sendo traduzida.
     */
    public BxmlTranslateContext withCrossMachineVariableNames(Set<String> names) {
        return new BxmlTranslateContext(
                types,
                comprehensions,
                variableLogicTypes,
                lambdaRegistry,
                enumValueRenames,
                enumeratedSetRenames,
                enumeratedSetNames,
                multiArgLambdaConstantNames,
                machineName,
                names == null ? Set.of() : names,
                crossMachineVariableLogicTypes,
                declaredSetNames,
                sigmaRegistry,
                unionInterRegistry,
                deferredSetTypedParameterNames);
    }

    /**
     * Retorna uma cópia deste contexto com o mapa de tipos {@code logic} das variáveis de máquinas
     * SEES/IMPORTS substituído — ao contrário de {@link #crossMachineVariableNames()} (só nomes,
     * usado para EXCLUIR da deteção de variável livre), este mapa serve para RESOLVER o tipo de uma
     * variável de outra máquina que precisa entrar como parâmetro TIPADO de uma função lógica gerada
     * (ex. {@code array: Function_int_int} como parâmetro de um {@code lambda_funcNN} recursivo).
     */
    public BxmlTranslateContext withCrossMachineVariableLogicTypes(Map<String, String> types0) {
        return new BxmlTranslateContext(
                types,
                comprehensions,
                variableLogicTypes,
                lambdaRegistry,
                enumValueRenames,
                enumeratedSetRenames,
                enumeratedSetNames,
                multiArgLambdaConstantNames,
                machineName,
                crossMachineVariableNames,
                types0 == null ? Map.of() : types0,
                declaredSetNames,
                sigmaRegistry,
                unionInterRegistry,
                deferredSetTypedParameterNames);
    }

    /**
     * Retorna uma cópia deste contexto com os nomes de conjuntos declarados (deferred + enumerados)
     * substituídos — ver {@link BxmlSetsTranslator#declaredSetNames(Element)}. Normalmente já
     * populado por {@link #forMachine}/{@link #forMachineWithSharedComprehensions} a partir da
     * PRÓPRIA máquina; este wither serve para alargar a cobertura a máquinas SEES/IMPORTS, se algum
     * dia for preciso (não usado atualmente).
     */
    public BxmlTranslateContext withDeclaredSetNames(Set<String> setNames) {
        return new BxmlTranslateContext(
                types,
                comprehensions,
                variableLogicTypes,
                lambdaRegistry,
                enumValueRenames,
                enumeratedSetRenames,
                enumeratedSetNames,
                multiArgLambdaConstantNames,
                machineName,
                crossMachineVariableNames,
                crossMachineVariableLogicTypes,
                setNames == null ? Set.of() : setNames,
                sigmaRegistry,
                unionInterRegistry,
                deferredSetTypedParameterNames);
    }

    /**
     * Retorna uma cópia deste contexto com os nomes de PARÂMETROS DE OPERAÇÃO tipados por um
     * conjunto diferido/enumerado NA MÁQUINA ABSTRATA (ex. {@code "pp"} para
     * {@code AddPlayer(pp) = PRE pp:PLAYER & ... END}) substituídos — ver {@link
     * BxmlMachineVariables#deferredSetTypedOperationParameterNames}.
     *
     * <p>Necessário porque o {@code typref} de um parâmetro de operação, DENTRO do corpo/loop da
     * IMPLEMENTAÇÃO, pode legitimamente ter sido refinado pelo próprio AtelierB para {@code
     * INTEGER} (ex. usado em indexação de array) mesmo sendo {@code PLAYER} na assinatura
     * ABSTRATA — mas a assinatura C REAL gerada continua a usar o tipo enum (ex. {@code
     * RulerOfTheSeas__PLAYER pp}), então o cast {@code (integer)} continua a ser necessário sempre
     * que o parâmetro entra num slot ACSL genérico (ex. {@code couple<A,B>}). Sem este sinal
     * adicional, {@code BxmlExpressionToAcsl#translate}'s caso {@code "Id"} (que decide o cast só
     * pelo {@code typref} do nó) não tem como saber disto — dá "invalid cast from
     * Tuple&lt;EnumType,...&gt; to Tuple&lt;integer,...&gt;" (confirmado em
     * {@code AddPlayer}/{@code player_islands_i}).
     */
    public BxmlTranslateContext withDeferredSetTypedParameterNames(Set<String> names) {
        return new BxmlTranslateContext(
                types,
                comprehensions,
                variableLogicTypes,
                lambdaRegistry,
                enumValueRenames,
                enumeratedSetRenames,
                enumeratedSetNames,
                multiArgLambdaConstantNames,
                machineName,
                crossMachineVariableNames,
                crossMachineVariableLogicTypes,
                declaredSetNames,
                sigmaRegistry,
                unionInterRegistry,
                names == null ? Set.of() : names);
    }

    /**
     * Retorna uma cópia deste contexto com {@code extra} mesclado em {@link #variableLogicTypes()}
     * como fallback (entradas já presentes têm prioridade) — usado para que o invariante de uma
     * camada da cadeia de refinamento (ex.: {@code _i}) veja o tipo de uma variável declarada só
     * numa camada intermédia (ex.: {@code ss_r} em {@code _r}), que {@link #variableLogicTypes()}
     * só cobre para a PRÓPRIA máquina (e a abstrata raiz) sem isto — sem o tipo autoritativo,
     * {@code isListValued} cai no fallback por {@code typref} cru, que não distingue sequência de
     * relação genérica (ambas {@code POW(INTEGER*T)} em B), gerando {@code sequence_ran}/{@code
     * relation_ran} trocados.
     */
    public BxmlTranslateContext withAdditionalVariableLogicTypes(Map<String, String> extra) {
        if (extra == null || extra.isEmpty()) return this;
        Map<String, String> merged = new LinkedHashMap<>(extra);
        merged.putAll(variableLogicTypes);
        return new BxmlTranslateContext(
                types,
                comprehensions,
                merged,
                lambdaRegistry,
                enumValueRenames,
                enumeratedSetRenames,
                enumeratedSetNames,
                multiArgLambdaConstantNames,
                machineName,
                crossMachineVariableNames,
                crossMachineVariableLogicTypes,
                declaredSetNames,
                sigmaRegistry,
                unionInterRegistry,
                deferredSetTypedParameterNames);
    }

    private static String machineNameFrom(Element machineEl) {
        if (machineEl == null) {
            return "";
        }
        String name = machineEl.getAttribute("name");
        return name == null ? "" : name.trim();
    }

    public static BxmlTranslateContext forMachine(Element machineEl) {
        return forMachine(machineEl, Map.of());
    }

    /**
     * @param gluingSubstitutions ex.: {@code ran(numbers_s) → numbers} extraído dos invariantes (todas as máquinas)
     */
    public static BxmlTranslateContext forMachine(Element machineEl, Map<String, String> gluingSubstitutions) {
        BxmlTypeRegistry types = BxmlTypeRegistry.fromMachine(machineEl);
        BxmlComprehensionRegistry reg = BxmlComprehensionRegistry.fromMachine(machineEl);
        Map<String, String> gl = gluingSubstitutions == null ? Map.of() : gluingSubstitutions;
        reg.setGluingSubstitutions(gl);
        reg.assignDedupIndices();
        String machineName = machineNameFrom(machineEl);
        Set<String> setNames = BxmlSetsTranslator.declaredSetNames(machineEl);
        BxmlTranslateContext tmp =
                new BxmlTranslateContext(
                        types, reg, Map.of(), null, Map.of(), Map.of(), Set.of(), Set.of(), machineName,
                        Set.of(), Map.of(), setNames, null, null, Set.of());
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        merged.putAll(BxmlMachineVariables.inferConcreteConstantsLogicTypes(machineEl, types));
        merged.putAll(BxmlMachineVariables.inferAbstractConstantsLogicTypesFromProperties(machineEl, types));
        merged.putAll(BxmlMachineVariables.inferVariableLogicTypes(machineEl, tmp));
        Set<String> lambdaConstantNames =
                BxmlConstantsAndProperties.collectLambdaDefsFromProperties(machineEl).keySet();
        return new BxmlTranslateContext(
                types, reg, merged, null, Map.of(), Map.of(), Set.of(), lambdaConstantNames, machineName,
                Set.of(), Map.of(), setNames, null, null, Set.of());
    }

    /**
     * Como {@link #forMachine(Element, Map)}, mas usa um registo de compreensões já construído (ex.
     * {@link BxmlComprehensionRegistry#fromMachineChain(java.util.List, Map)}) e indexado — não chama
     * {@link BxmlComprehensionRegistry#assignDedupIndices()} de novo.
     */
    public static BxmlTranslateContext forMachineWithSharedComprehensions(
            Element machineEl,
            BxmlComprehensionRegistry sharedComprehensions,
            Map<String, String> gluingSubstitutions) {
        return forMachineWithSharedComprehensions(
                machineEl, sharedComprehensions, gluingSubstitutions, null);
    }

    /**
     * Como {@link #forMachineWithSharedComprehensions(Element, BxmlComprehensionRegistry, Map)}, mas
     * para refinamentos/implementações fundidas: funde os tipos {@code logic} das variáveis inferidos
     * na máquina abstrata {@code abstractMachineElForSharedVariableTypes} (ex. {@code myseq} como
     * {@code \\list}) antes dos tipos da máquina concreta, para invariantes que referenciam estado
     * abstrato.
     */
    public static BxmlTranslateContext forMachineWithSharedComprehensions(
            Element machineEl,
            BxmlComprehensionRegistry sharedComprehensions,
            Map<String, String> gluingSubstitutions,
            Element abstractMachineElForSharedVariableTypes) {
        BxmlTypeRegistry types = BxmlTypeRegistry.fromMachine(machineEl);
        Map<String, String> gl = gluingSubstitutions == null ? Map.of() : gluingSubstitutions;
        sharedComprehensions.setGluingSubstitutions(gl);
        String machineName = machineNameFrom(machineEl);
        java.util.LinkedHashSet<String> setNames =
                new java.util.LinkedHashSet<>(BxmlSetsTranslator.declaredSetNames(machineEl));
        if (abstractMachineElForSharedVariableTypes != null) {
            setNames.addAll(BxmlSetsTranslator.declaredSetNames(abstractMachineElForSharedVariableTypes));
        }
        BxmlTranslateContext tmp =
                new BxmlTranslateContext(
                        types, sharedComprehensions, Map.of(), null, Map.of(), Map.of(), Set.of(), Set.of(),
                        machineName, Set.of(), Map.of(), setNames, null, null, Set.of());
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        merged.putAll(BxmlMachineVariables.inferConcreteConstantsLogicTypes(machineEl, types));
        merged.putAll(BxmlMachineVariables.inferAbstractConstantsLogicTypesFromProperties(machineEl, types));
        Set<String> lambdaConstantNames =
                new java.util.LinkedHashSet<>(
                        BxmlConstantsAndProperties.collectLambdaDefsFromProperties(machineEl).keySet());
        if (abstractMachineElForSharedVariableTypes != null) {
            BxmlTypeRegistry absTypes =
                    BxmlTypeRegistry.fromMachine(abstractMachineElForSharedVariableTypes);
            String absMachineName = machineNameFrom(abstractMachineElForSharedVariableTypes);
            BxmlTranslateContext tmpAbs =
                    new BxmlTranslateContext(
                            absTypes,
                            sharedComprehensions,
                            Map.of(),
                            null,
                            Map.of(),
                            Map.of(),
                            Set.of(),
                            Set.of(),
                            absMachineName,
                            Set.of(),
                            Map.of(),
                            setNames,
                            null,
                            null,
                            Set.of());
            merged.putAll(
                    BxmlMachineVariables.inferVariableLogicTypes(
                            abstractMachineElForSharedVariableTypes, tmpAbs));
            lambdaConstantNames.addAll(
                    BxmlConstantsAndProperties.collectLambdaDefsFromProperties(
                            abstractMachineElForSharedVariableTypes).keySet());
        }
        merged.putAll(BxmlMachineVariables.inferVariableLogicTypes(machineEl, tmp));
        return new BxmlTranslateContext(
                types, sharedComprehensions, merged, null, Map.of(), Map.of(), Set.of(),
                lambdaConstantNames, machineName, Set.of(), Map.of(), setNames, null, null, Set.of());
    }
}
