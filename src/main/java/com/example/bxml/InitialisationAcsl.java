package com.example.bxml;

import java.util.List;

import com.example.bxml.CartesianProductLoopSpecDetector.CartesianProductLoopSpec;

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
             * Especificações de loop gerados por {@code ARRAY := DOMAIN * {VALUE}}; uma entrada por
             * atribuição desse padrão na inicialização — cada uma emite {@code at loop N:} com
             * {@code loop invariant}, {@code loop assigns} e {@code loop variant}.
             */
            List<CartesianProductLoopSpec> loopSpecs,
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
            for (int idx = 0; idx < loopSpecs.size(); idx++) {
                CartesianProductLoopSpec ls = loopSpecs.get(idx);
                String lo  = ls.loExpr();
                String hi  = ls.hiExpr();
                String arr = ls.cArrayName();
                String val = ls.valueExpr();
                String v   = ls.counterVar();
                sb.append("    at loop ").append(idx + 1).append(":\n");
                sb.append("        loop invariant ").append(lo).append(" <= ").append(v)
                  .append(" <= ").append(hi).append(" + 1;\n");
                sb.append("        loop invariant \\forall integer k; ").append(lo)
                  .append(" <= k < ").append(v).append(" ==> ").append(arr)
                  .append("[k] == ").append(val).append(";\n");
                sb.append("        loop assigns ").append(v).append(", ")
                  .append(arr).append("[").append(lo).append(" .. ").append(hi).append("];\n");
                sb.append("        loop variant ").append(hi).append(" + 1 - ").append(v).append(";\n");
            }
            for (BxmlLoopTranslator.LoopContract loop : explicitLoops) {
                sb.append("    at loop ").append(loop.index()).append(":\n");
                if (loop.invariant() != null && !loop.invariant().isBlank()) {
                    sb.append("        loop invariant (").append(loop.invariant()).append(");\n");
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
    }
