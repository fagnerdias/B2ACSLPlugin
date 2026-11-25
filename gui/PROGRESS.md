# GUI wizard (Frama-C WP · Plugin / ATL Rules / compare) — progresso

Plano: `~/.claude/plans/pasted-content-id-e4f8-role-sparkling-crystal.md`. Branch: `gui_wizard_wp`.
Retomada após pausa (limite de uso): ler este checklist + `git log` + `git status`/`git diff` e
continuar do primeiro item não marcado.

- [x] 1. Pontos de extensão no código existente
  - [x] `FramaCRunner.buildMergedCode` (+ `MergeOutcome`), `buildWpCommand` e
        `resolveOperationFunctionNamesForWp` públicos
  - [x] `VerificationReportData.FunctionSummary` com `smokePassed`/`smokeTotal` inteiros
  - [x] `PreVerificationErrorDialog.extractHeadline` público
  - [x] `ProcessRunner.run(..., lineSink)` + `RulesSpecGenerator(..., lineSink)`
  - [x] Regressão: testes existentes OK; comparador AddRunner 41/41 nos dois lados, `merged_code.c`
        idêntico byte a byte ao run 20261124_114005
- [x] 2. Módulo `gui`: pom, Makefile, `model/`, `parsing/`, `comparison/` + testes unitários
  - [x] 19 testes (parsing + comparison) verdes; deteção de funções = lista `function X:` dos .acsl
        em 53/56 exemplos (exceções esperadas: `b_pow`; railmap/safetyflasher sem definição C das
        máquinas BASIC)
- [ ] 3. `generation/` (JVM filha, BundledTools, RunDirectory) e `verification/` (ProcessSupervisor,
      VerificationService, ToolDetector)
- [ ] 4. `ui/`: tema, componentes, passos do wizard, janela de progresso, relatórios, diálogo de erro
- [ ] 5. Preferências, verificação de ferramentas no arranque, acabamento face aos mockups
- [ ] 6. Verificação ponta a ponta (AddRunner nos 3 modos, caminhos de erro, cancelamento)
