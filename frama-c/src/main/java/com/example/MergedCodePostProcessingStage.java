package com.example;

import java.io.IOException;

/**
 * Uma etapa da cadeia de pós-processamento de {@code merged_code.c}, executada em sequência fixa
 * depois do {@code -acsl-import} do Frama-C e antes do {@code -wp} (ver a lista ordenada e
 * nomeada {@code FramaCRunner#MERGED_CODE_POST_PROCESSING_STAGES}). Extraída como abstração
 * funcional para que essa lista de 16 chamadas estáticas sequenciais fique explícita e iterável
 * em vez de 16 statements soltos — reorganização pura, sem mudança de comportamento: a mesma
 * sequência de métodos, na mesma ordem, com os mesmos argumentos.
 */
interface MergedCodePostProcessingStage {

    void apply(MergedCodeContext ctx) throws IOException;
}
