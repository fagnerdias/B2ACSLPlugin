package com.example.bxml;

import java.util.List;

import com.example.bxml.CartesianProductLoopSpecDetector.CartesianProduct2DLoopSpec;
import com.example.bxml.CartesianProductLoopSpecDetector.CartesianProductLoopSpec;
import com.example.bxml.CartesianProductLoopSpecDetector.LoopInitSpec;

    /**
     * Texto de contrato no estilo pedido (função + contract + ensures + assigns).
     */
    public record InitialisationAcsl(
            String functionName,
            List<String> ensures,
            List<String> assignsTargets,
            boolean includeGhostBehaviorAssert,
            /**
             * Sufixos de variável abstrata (ex. {@code ss}) para cláusulas {@code ensures dummy_ghost_<v>;}
             * em inicialização não pura face ao modelo ghost.
             */
            List<String> dummyGhostEnsureVarNames,
            /**
             * Especificações de loop gerados por {@code ARRAY := DOMAIN * {VALUE}} (1D) ou
             * {@code ARRAY := DOMAIN1 * DOMAIN2 * {VALUE}} (2D, matriz característica — ver {@link
             * CartesianProduct2DLoopSpec}); uma entrada por atribuição desse padrão na
             * inicialização — cada uma emite UM ({@code CartesianProductLoopSpec}) ou DOIS
             * ({@code CartesianProduct2DLoopSpec}, externo depois interno) blocos {@code at loop N:}
             * com {@code loop invariant}, {@code loop assigns} e {@code loop variant}.
             */
            List<LoopInitSpec> loopSpecs,
            /**
             * Loops {@code WHILE} explícitos (com {@code INVARIANT}/{@code VARIANT} escritos pelo
             * usuário em B) encontrados na inicialização — ao contrário de {@code loopSpecs}, que só
             * cobre o açúcar {@code ARRAY := DOMAIN * {VALUE}}. Populado só quando {@code loopSpecs}
             * está vazio (os dois padrões nunca coincidem no mesmo nó — ver
             * {@link BxmlLoopTranslator#translateLoopsFromSubstitution}).
             */
            List<BxmlLoopTranslator.LoopContract> explicitLoops,
            /**
             * {@code true} para máquinas que não importam outras máquinas: emite um contrato mínimo
             * com {@code assigns \nothing;} mesmo que não haja outros conteúdos.
             */
            boolean emitMinimalContract) {

        public InitialisationAcsl {
            dummyGhostEnsureVarNames =
                    dummyGhostEnsureVarNames == null ? List.of() : List.copyOf(dummyGhostEnsureVarNames);
            loopSpecs = loopSpecs == null ? List.of() : List.copyOf(loopSpecs);
            explicitLoops = explicitLoops == null ? List.of() : List.copyOf(explicitLoops);
        }

        public String toContractText() {
            boolean hasContent = !ensures.isEmpty()
                    || !dummyGhostEnsureVarNames.isEmpty()
                    || !assignsTargets.isEmpty()
                    || !loopSpecs.isEmpty()
                    || !explicitLoops.isEmpty()
                    || includeGhostBehaviorAssert;
            if (!hasContent && !emitMinimalContract) return "";
            StringBuilder sb = new StringBuilder();
            sb.append("function ").append(functionName).append(":\n");
            sb.append("contract:\n");
            for (String e : ensures) {
                sb.append("    ensures  ").append(e).append(";\n");
            }
            for (String v : dummyGhostEnsureVarNames) {
                sb.append("    ensures  dummy_ghost_").append(v).append(";\n");
            }
            if (assignsTargets.isEmpty()) {
                sb.append("    assigns \\nothing;\n");
            } else {
                for (String a : assignsTargets) {
                    sb.append("    assigns ").append(a).append(";\n");
                }
            }
            int loopNumber = 1;
            for (LoopInitSpec spec : loopSpecs) {
                loopNumber = appendLoopInitSpec(sb, spec, loopNumber);
            }
            for (BxmlLoopTranslator.LoopContract loop : explicitLoops) {
                sb.append("    at loop ").append(loop.index()).append(":\n");
                for (String conjunct : loop.invariant()) {
                    if (conjunct != null && !conjunct.isBlank()) {
                        sb.append("        loop invariant (").append(conjunct).append(");\n");
                    }
                }
                if (!loop.assigns().isEmpty()) {
                    sb.append("        loop assigns ").append(String.join(", ", loop.assigns())).append(";\n");
                }
                if (loop.variant() != null && !loop.variant().isBlank()) {
                    sb.append("        loop variant (").append(loop.variant()).append(");\n");
                }
            }
            if (includeGhostBehaviorAssert) {
                String machinePart = functionName.toLowerCase().replace("__initialisation", "");
                sb.append("    at return: assert ghost__").append(machinePart).append("__initialisation;\n");
            }
            return sb.toString();
        }

        /**
         * Anexa os blocos {@code at loop N:} de UMA especificação — um para {@link
         * CartesianProductLoopSpec} (1D), dois (externo depois interno, mesma ordem de leitura do
         * corpo C aninhado — ver {@link CartesianProduct2DLoopSpec}) para o caso 2D — e devolve o
         * PRÓXIMO número de loop livre, para a chamada seguinte continuar a contagem
         * corretamente (o {@code -acsl-import} do Frama-C pareia {@code at loop N} com o Nº loop
         * encontrado, em ordem, no corpo C — não por nome/posição na lista de specs).
         */
        private static int appendLoopInitSpec(StringBuilder sb, LoopInitSpec spec, int loopNumber) {
            if (spec instanceof CartesianProductLoopSpec ls) {
                String lo = ls.loExpr();
                String hi = ls.hiExpr();
                String arr = ls.cArrayName();
                String val = ls.valueExpr();
                String v = ls.counterVar();
                sb.append("    at loop ").append(loopNumber).append(":\n");
                sb.append("        loop invariant ").append(lo).append(" <= ").append(v)
                  .append(" <= ").append(hi).append(" + 1;\n");
                sb.append("        loop invariant \\forall integer k; ").append(lo)
                  .append(" <= k < ").append(v).append(" ==> ").append(arr)
                  .append("[k] == ").append(val).append(";\n");
                sb.append("        loop assigns ").append(v).append(", ")
                  .append(arr).append("[").append(lo).append(" .. ").append(hi).append("];\n");
                sb.append("        loop variant ").append(hi).append(" + 1 - ").append(v).append(";\n");
                return loopNumber + 1;
            }
            if (spec instanceof CartesianProduct2DLoopSpec ls) {
                String rowLo = ls.outerLoExpr();
                String rowHi = ls.outerHiExpr();
                String colLo = ls.innerLoExpr();
                String colHi = ls.innerHiExpr();
                String arr = ls.cArrayName();
                String val = ls.valueExpr();
                String i = ls.outerCounterVar();
                String j = ls.innerCounterVar();
                // Linhas 0..i-1 já totalmente escritas (cada iteração do laço externo só avança
                // depois do laço interno completar a linha inteira).
                String rowsDoneInvariant =
                        "\\forall integer k, l; " + rowLo + " <= k < " + i + " && " + colLo
                                + " <= l <= " + colHi + " ==> " + arr + "[k][l] == " + val;
                sb.append("    at loop ").append(loopNumber).append(":\n");
                sb.append("        loop invariant ").append(rowLo).append(" <= ").append(i)
                  .append(" <= ").append(rowHi).append(" + 1;\n");
                sb.append("        loop invariant ").append(rowsDoneInvariant).append(";\n");
                sb.append("        loop assigns ").append(i).append(", ").append(arr)
                  .append("[").append(rowLo).append(" .. ").append(rowHi).append("][")
                  .append(colLo).append(" .. ").append(colHi).append("];\n");
                sb.append("        loop variant ").append(rowHi).append(" + 1 - ").append(i).append(";\n");
                loopNumber++;
                sb.append("    at loop ").append(loopNumber).append(":\n");
                sb.append("        loop invariant ").append(colLo).append(" <= ").append(j)
                  .append(" <= ").append(colHi).append(" + 1;\n");
                // Repete o progresso das linhas completas (o WP não herda automaticamente o
                // invariante do laço EXTERNO ao verificar o laço INTERNO — cada "at loop N:" é
                // independente) mais o progresso PARCIAL da linha corrente.
                sb.append("        loop invariant ").append(rowsDoneInvariant).append(";\n");
                sb.append("        loop invariant \\forall integer l; ").append(colLo)
                  .append(" <= l < ").append(j).append(" ==> ").append(arr).append("[").append(i)
                  .append("][l] == ").append(val).append(";\n");
                sb.append("        loop assigns ").append(j).append(", ").append(arr)
                  .append("[").append(i).append("][").append(colLo).append(" .. ").append(colHi)
                  .append("];\n");
                sb.append("        loop variant ").append(colHi).append(" + 1 - ").append(j).append(";\n");
                return loopNumber + 1;
            }
            return loopNumber;
        }
    }
