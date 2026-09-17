package com.example;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Dados que variam por etapa da cadeia de pós-processamento de {@code merged_code.c} (ver {@link
 * MergedCodePostProcessingStage} e a lista ordenada em {@code FramaCRunner}): o próprio ficheiro
 * fundido, o {@code ghost_operations.ci} de onde algumas etapas leem texto ghost bruto, os tipos
 * usados na especificação (para monomorphização/renomeação de blocos {@code axiomatic}
 * genéricos) e os símbolos de biblioteca ACSL cujos lemas podem ser anexados ao fim do ficheiro.
 * Nem toda etapa usa todos os campos — cada uma lê só os que precisa.
 */
record MergedCodeContext(
        Path mergedC,
        Path ghostCi,
        List<String> specificationUsedTypes,
        Set<String> allowedLibSymbolsForLemmas) {}
