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
- [x] 3. `generation/` (JVM filha, BundledTools, RunDirectory) e `verification/` (ProcessSupervisor,
      VerificationService, ToolDetector)
  - [x] Harness headless (sem UI) sobre cópia do AddRunner em modo compare: 41/41 nos dois lados,
        13/15/13 por função = baseline do comparador; WP corre no merged_code.c publicado
  - [x] Caminhos de erro: rules jar inexistente → NOT_AVAILABLE; railmap → IMPORT_ERROR nos dois lados
- [x] 4. `ui/`: tema, componentes, passos do wizard, janela de progresso, relatórios, diálogo de erro
  - [x] Telas renderizadas fora do ecrã (harness em scratchpad) e comparadas com os mockups 1, 2, 2E,
        E, 3, 4, 5, 6, 5A
- [x] 5. Preferências, verificação de ferramentas no arranque, acabamento face aos mockups
  - [x] Next do passo 1 desativado enquanto a nova pasta não foi analisada (corrida encontrada no
        teste ponta a ponta); Run Verification bloqueado com diálogo se o Frama-C não foi encontrado
  - [x] gui/README.md (build, uso, ficheiros, comandos, pacotes) + entrada nos README da raiz
- [x] 6. Verificação ponta a ponta (UI real conduzida por código, harness em scratchpad)
  - [x] Compare em AddRunner: 41/41 nos dois lados (13/15/13, smoke 4/4) = baseline; relatório de
        comparação e aba de especificações com os contratos reais; volta ao passo 3
  - [x] Cancelar durante o WP: frama-c + why3server vivos antes, 0 processos depois
  - [x] Rules jar inexistente: diálogo de erro + "Continue with Plugin Only" → passo 3 em modo Plugin
  - [x] Só Plugin em count_array: relatório simples; CountArray__count 27/30 sem -wp-split (o
        comparador usa -wp-split: 34/38, confirmado com o mesmo merged_code.c)
- [x] 7. Ajustes pedidos depois da comparação com as telas antigas (2026-11-25)
  - [x] Barras de rolagem no padrão anterior (tema Ocean) e rolagem automática: lista de funções
        com rolagem que acompanha a função em execução, log que acompanha a última linha
  - [x] Status Running com o número de goals agendados pelo WP (`[wp] N goals scheduled`)
  - [x] Os seis status anteriores (Pending, Running…, Proved, Partial, Timeout, Failed) com a
        mesma classificação de `markCompletedFromSummary`, também no relatório
  - [x] Log do Frama-C embutido na janela de progresso durante a verificação (Hide Logs / Save Log)
  - [x] "How to fix" removido do diálogo de erro (e do texto copiado)
- [x] 8. Todas as janelas no mesmo tamanho (pedido de 2026-11-25)
  - [x] `Ui.standardSize`: 960 × 720 (limitado à tela), centralizado — wizard (3 passos), progresso,
        relatório simples, relatório de comparação, diálogo de erro, visualizador de log; nenhum
        `pack()` por passo
  - [x] Conteúdo adaptado: formulários do wizard numa coluna centralizada de 760 px; lista de
        funções, log de geração, log do WP e saída do erro ocupam a altura livre; páginas dos
        relatórios acompanham a largura (sem corte horizontal); cartões reduzem a fonte para caber
  - [x] Corrigido: `Ui.wrap` quebrava 30% mais largo (o HTML do Swing escala `px` por 1,3)
  - [x] Verificado em janelas reais: compare no AddRunner e falha do ATL, todas 960 × 720 na mesma
        posição
- [x] 9. Passo 1 como na pipeline anterior (pedido de 2026-11-25)
  - [x] Projeto recebido por argumento (pasta do projeto ou `bdp/`, como o Atelier B passa):
        `java -jar … <pasta>` / `make gui PROJECT=<pasta>`; sem botão Browse e sem preferência de pasta
  - [x] Passo 1 só informativo: pasta, nome (editável) e lista de funções sem checkbox — todas são
        verificadas ("N functions will be verified")
  - [x] Sem projeto ou projeto inválido: o wizard abre e o diálogo de erro diz como iniciar (aberto
        depois de a janela aparecer)
  - [x] Verificado: compare real no AddRunner passando `AddRunner/bdp`; 22 testes
- [x] 10. Ajustes pedidos em 2026-11-26
  - [x] Wizard 760 × 720 (sem faixas vazias nas laterais; mesma altura das outras janelas); diálogo de
        erro e visualizador de log com o tamanho e o lugar da janela de baixo
  - [x] Nome do projeto só leitura
  - [x] Provadores na ordem CVC5, Alt-Ergo, Z3
  - [x] Progresso: "X / N done" sem cor na palavra "done"; só a contagem colorida (cinza no início,
        azul parcial, verde/laranja no resultado final)
  - [x] Results: sem a coluna Difference (a diferença fica no tooltip de "Different"); coluna "Action"
  - [x] Specifications: só no Plugin em azul, só no ATL Rules em laranja
  - [x] Close dos relatórios encerra a aplicação (como a pipeline)
  - [x] Verificado no app real (compare AddRunner: janelas 760/960 × 720, JVM termina com 0 no Close)
- [x] 11. Timeouts em amarelo (pedido de 2026-11-26): número do cartão Timeouts, selo de resumo sem
      falhas ("0 failures · N timeouts"), proved/total da tabela de comparação — mesma cor do status
      Timeout; falhas continuam em vermelho
- [x] 12. Botões dos diálogos do Swing em inglês (pedido de 2026-11-26): confirmações Yes/No, OK e a
      janela de salvar (Save/Cancel) não seguem mais o idioma do sistema; `Theme.install` muda o
      locale padrão da JVM para inglês e mantém o formato de números do sistema ("16,54s")

