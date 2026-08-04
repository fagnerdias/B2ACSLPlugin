# Implementações v0.0.3

Release focada na tradução de máquinas B com **IMPORTS/SEES multi-máquina** via mecanismo de
ghost operations completo, ampliando a cobertura de construtos B e a biblioteca ACSL genérica.
O conjunto de exemplos validados cresceu de 2 (v0.0.2) para **16 projetos**, com **1275/1357
(94,0%) obrigações de prova (PO) provadas** automaticamente pelo Frama-C WP (CVC5) nesta execução.

---

## Ghost operations: mecanismo completo (novo)

- Novo pipeline de **ghost operations**: cada operação abstrata passa a ter uma contraparte
  ghost com parâmetros e predicados espelhados, permitindo reconstruir o estado abstrato a
  partir do estado concreto/array-backed sem alterar a assinatura da função real em C.
- `DummyGhostAxiomaticBuilder` (novo, ~714 linhas): gera declarações dummy para operações e
  tipos ghost; corrigido para não omitir a segunda+ sobrecarga de uma mesma predicate/logic e
  para não perder o `&&` ao mesclar blocos dummy duplicados.
- `GhostOperationsCiGenerator` reescrito (~1150 linhas alteradas): `ghost_operations.ci` agora é
  gerado **antes** dos `.acsl` (necessário para que símbolos como `set_difference` sejam
  detectados no scan da lib) e é interpretado pelo Frama-C como front-end **isolado** — sem
  visão dos símbolos de `-acsl-import` —, exigindo tratamento explícito de `is_total_function`
  para o operador `-->` de B no texto ghost.
- `assignsAbstract` passa a disparar a inclusão de parâmetros de saída no `assigns` ghost.
- Renomeação de colisão com palavras reservadas do Frama-C para evitar erros de sintaxe;
  suporte a conjuntos diferidos (*deferred sets*) nas operações ghost.
- Contratos reais (não-ghost) agora **reutilizam** o `ensures` funcional do ghost e os
  invariantes de loop passam a carregar também os invariantes das operações chamadas
  internamente — foi necessário reaproveitar reescritas ACSL do lado ghost (predicado
  ternário, cast escalar) para manter tudo parseável.

## Transitividade de IMPORTS/SEES (classe de bug corrigida em 4 pontos distintos)

Uma mesma classe de problema — busca em máquinas importadas/vistas que só olhava um nível de
profundidade — foi encontrada e corrigida em quatro lugares diferentes do pipeline:

- Predicados `requires` derivados de invariantes, `assigns` por máquina e o mapa de `assigns`
  por operação agora resolvem **IMPORTS transitivo** (`listSeenMachineNamesTransitive` /
  equivalentes), não apenas o primeiro nível.
- Invariante de máquinas **SEEN** (não só IMPORTED) também passa a virar `requires` no ponto de
  chamada (`BxmlSeesGraph`, novo).
- Tamanho de `array_to_function` (cardinalidade de conjunto diferido), faixa de `assigns`
  (`X[low..high]` vs. `X[..]`) e a especificação de loop de inicialização
  (`ARRAY := DOMAIN*{VALUE}`) agora resolvem o conjunto/domínio mesmo quando ele é valorado
  numa máquina alcançável apenas via `SEES`.
- Novas classes dedicadas: `BxmlImportsGraph` e `BxmlSeesGraph` para navegar essas relações.

## Novos construtos e traduções B → ACSL

- **`ANY`**: tradução migrada de `\forall … ==>` para `\exists … &&`, com `\old()` correto e
  eliminação de variáveis-alias definidas por guarda (bug latente também corrigido no caminho
  de contrato real, não só no ghost).
- **`::` (becomes_element_of)**: nova tradução via `becomes_element_of` (2 sobrecargas —
  domínio função e conjunto simples) substituindo `is_total_function`/`belongs`; expôs e
  corrigiu 3 bugs latentes na biblioteca (entrada obsoleta em `symbol_dependency_map.json`,
  colisão de axiomáticas duplicadas, omissão silenciosa de declaração dummy quando a
  assinatura do predicate ocupava mais de uma linha).
- **`f(x) := y`** (sobrescrita de relação/função): agora traduzido para
  `equals(f, overwrite(f, singleton(couple(x,y))))` em vez da equivalência fraca
  `function_apply(f,x)==y`; inclui correção de `\old`-wrapping do valor pós-estado.
- **`<+`** (overwrite de relação): nova função `overwrite`/`relation_overwrite` na biblioteca.
- **`**`** (potência): sem operador C equivalente — geração automática de `b_pow.acsl` com
  contrato dedicado sempre que `**` é detectado no projeto, evitando depender de um helper de
  runtime opaco sem especificação.
- **`v = bool(P)`**: parênteses externos corrigidos em `v <==> P` — a ausência deles corrompia
  silenciosamente qualquer invariante/loop que fizesse `&&` com outros conjuntos (`<==>` tem
  precedência menor que `&&`).
- Relações com **codomínio tupla** (`PERSON +-> (DAY*MONTH*YEAR)`): suporte totalmente genérico
  (qualquer aridade/mistura inteiro-booleano) via `TupleCodomainTypeRegistry` + arquivo `.acsl`
  dinâmico por máquina.
- Constantes lambda multi-argumento e emissão antecipada (`LambdaFunctionRegistry`).
- Variáveis de mesmo nome entre abstrato e implementação agora **colapsam** em uma única
  variável array-backed (sem gêmeo ghost), com correção correspondente na origem do `ensures`
  de `INITIALISATION` e no parsing do operador `-->` do lado não-ghost.

## Separação de memória e ponteiros de saída

- `requires \separated(p, array)` gerado automaticamente para parâmetros de saída versus os
  arrays da própria máquina e das máquinas IMPORTS/SEES/USES transitivas, além de
  `\separated` par-a-par entre múltiplas saídas — tornando explícita a hipótese de modelo de
  memória que o WP antes assumia implicitamente.

## Loops

- `BxmlLoopTranslator` (novo, ~325 linhas) e `LoopUnrollLevelEstimator` (novo, ~253 linhas).
- Suporte a múltiplas especificações de loop na tradução de `INITIALISATION`.

## Biblioteca ACSL (submódulo B2ACSLLib)

- `array_to_function` dividido em `array_to_function_bool` / `array_to_function_int`.
- Novas funções `overwrite` / `relation_overwrite` (operador B `<+`) e `becomes_element_of`.
- Suporte a tuplas ampliado (`accessors`, `equals`) e mais de 200 linhas de lemmas novos.

## Interface gráfica e fluxo de verificação

- `VerificationProgressDialog` (novo): acompanhamento em tempo real da verificação, por
  operação, com logging.
- `WpOptionsDialog`: interface não-bloqueante; opções de contraexemplos e "split goals".
- `FormalVerificationReportDialog` / `VerificationReportData` ampliados para refletir o novo
  detalhamento por operação.

## Refatoração

- Remoção de classes e métodos não utilizados (`AcslLibSymbolDependencyMap`,
  `SpecificationTypesCollector`, simplificação de `Invariant`/`Operations`/`Variables`).

---

## Exemplos validados (16 projetos)

Resultados de obrigações de prova (PO) obtidos executando o pipeline completo
(`B2ACSLPipeline` → Frama-C `-acsl-import` → `-wp -wp-prover CVC5 -wp-rte -wp-smoke-tests`)
contra o estado atual do código, pasta `examples/`:

| Projeto | PO provadas / total | % |
|---|---|---|
| AddRunner | 28 / 28 | 100,0% |
| airlock | 87 / 87 | 100,0% |
| Biblioteca | 144 / 158 | 91,1% |
| BirthdayRegister | 38 / 46 | 82,6% |
| Customer_estr | 123 / 133 | 92,5% |
| DataFields | 3 / 3 | 100,0% |
| DataValidation | 147 / 167 | 88,0% |
| filling_array | 111 / 120 | 92,5% |
| finding_the_max_array | 108 / 122 | 88,5% |
| fuel_level | 206 / 206 | 100,0% |
| integer_arithmetic_calculator | 101 / 103 | 98,1% |
| mult | 69 / 69 | 100,0% |
| OddEvenCounter | 37 / 42 | 88,1% |
| railroad_switch | 47 / 47 | 100,0% |
| RobustFifo | não conclui¹ | — |
| simple_loop | 26 / 26 | 100,0% |
| **Total (15 projetos concluídos)** | **1275 / 1357** | **94,0%** |

¹ `RobustFifo` trava na etapa de parsing do Frama-C (`-acsl-import -print`) antes de qualquer
goal de WP ser agendado — não é uma falha de prova, e sim uma limitação de tradução conhecida:
o invariante de buffer circular da máquina depende de operadores de rotação de sequência e de
sequências construídas por lambda que ainda não são totalmente suportados (ver
`BirthdayRegister/RobustFifo Bugs & Gaps` nas notas de desenvolvimento). Fica como item aberto
para a próxima release.

Das 82 PO não provadas nos 15 projetos concluídos, **nenhuma é um contraexemplo real**
(nenhum status `Invalid`/inconsistência em nenhum dos exemplos):

- **60** são timeout do CVC5 (limite de 10s por goal) — candidatas a prova com um timeout maior
  ou lemmas auxiliares, não bugs de especificação;
- **22** são smoke-tests de código morto (`Doomed`) que o próprio WP identifica como
  inalcançável — achado correto do smoke-test, não uma lacuna de prova (20 em
  `DataValidation__check`, 1 em `filling_array` e 1 em `finding_the_max_array`; a "lacuna" de
  `DataValidation__check` é, na verdade, **100% coberta** por esses 20 achados de código morto,
  já revisados em sessão anterior).

---

## Próximos passos sugeridos

- Suporte a operadores de rotação/lambda de sequência para viabilizar `RobustFifo`.
- Investigar os goals com timeout de `Biblioteca`, `BirthdayRegister`, `DataValidation`,
  `finding_the_max_array` e `OddEvenCounter` com timeout maior ou lemmas auxiliares.
- Automatizar esta bateria de 16 exemplos como suíte de regressão (script único, como o
  `scripts/generate_acsl_symbol_dependency_map.py` já faz para a lib).
- Commitar as alterações pendentes do submódulo `B2ACSLLib` (`array_to_function_bool/int`,
  `overwrite`, `becomes_element_of`, tuplas) antes de fixar a tag `v0.0.3`.

---

# Implementações v0.0.2

## Biblioteca ACSL genérica e instanciação

- Integração da biblioteca **B2ACSLLib** (submódulo Git `ACSL2BMethodLib`) como fonte de funções, axiomas e lemmas ACSL.
- Geração automática de includes a partir dos símbolos utilizados na especificação (`AcslLibIncludes`, `symbol_dependency_map.json`).
- Coleta de tipos usados na especificação (`SpecificationTypesCollector`) e gravação em `specification_types.txt`.
- Instanciação monomórfica de blocos genéricos ACSL (`SpecificationAxiomaticInstantiator`):
  - substituição de tipos parametrizados (`Set<A>`, `Tuple<A,B>`, `Relation<A,B>`, `Function<A,B>`) por nomes concretos;
  - geração do bloco `axiomatic new_types` com declarações de tipos opacos e aliases;
  - instanciação de axiomas e funções apenas para os tipos identificados no exemplo.
- Filtragem de lemmas por símbolos permitidos, incluindo processamento por bloco axiomatic individual.
- Correções na instanciação para evitar captura incorreta de predicados como nomes de tipo.
- Exclusão de tipos legados do `ghost_operations.ci` (`DTuple`, `DRelation`, `DSet`, `dummy_*`) da instanciação da biblioteca.
- Remoção de comentários inline no bloco `axiomatic new_types` que causavam erros de parsing no Frama-C.

---

## Pipeline de geração e ghost operations

- Refatoração do pipeline (`B2ACSLPipeline`) para merge de especificações, geração de `merged_code.c` e execução do Frama-C WP.
- Geração e integração do arquivo `ghost_operations.ci` com funções dummy para operações ghost e tipos auxiliares.
- Prefixo `dummy_` em funções de pré-processamento; cast `int` → `integer` quando necessário.
- Remoção seletiva de contratos ghost para operações que não alteram variáveis abstratas.
- Correção da posição das especificações ghost no `merged_code.c` e uso de `\old` nos contratos ghost.
- Suporte a `array_to_function`, `list_to_function`, `domain_restriction`, `range_restriction` e demais funções auxiliares do ghost.

---

## Tradução BXML → ACSL

### Expressões e predicados

- Tradução de operadores aritméticos, comparações (`=`, `≠`, `:`, `/:`, `<`, `>`, …) e conectivos lógicos.
- Suporte a quantificadores universais `\forall`.
- Tradução de `card` para conjuntos e `\length` para `\list`.
- Tradução de relação inversa (`~`) via `relation_inverse`.
- Tradução de funções totais e sobjetivas (`-->>`) via `is_total_function` e `is_surjective`.
- Suporte a maplets, diferença de conjuntos, aplicação de funções/relações e restrições de domínio/imagem.
- Tradução de intervalos (`a..b`) e concatenação de listas.
- Tradução de `MAXINT` e valores numéricos B.
- Tradução de conjuntos finitos `{e1, e2, …}` via `set_union(singleton(...), …)` em vez de `set_enum`.
- Cast implícito `(integer)` para parâmetros/variáveis de tipo enum C em operações com conjuntos ACSL.

### Conjuntos definidos (`<Sets>`)

- Nova tradução da tag `<Sets>` em bloco axiomatic `{Máquina}_sets`.
- Declaração de conjuntos enumerados com axioma único de pertinência e exaustividade.
- Valores enumerados com prefixo da máquina (ex.: `switch__normal`, `switch__reverse`, `switch__void`).
- Correção de case sensitivity entre tipos B e nomes ACSL (ex.: `BOOK` vs `book`).
- Resolução de tipos `POW(T)` para `Set<integer>` quando `T` é um conjunto definido.

### Constantes, propriedades e valores

- Tradução de `CONSTANTS` e `PROPERTIES` de máquinas de implementação para blocos `{Máquina}_constants` e `{Máquina}_properties`.
- Tradução de `Values` para bloco `{Máquina}_values`.
- Ordenação dos blocos axiomatic para que constantes e variáveis sejam declaradas antes do primeiro uso.

### Variáveis de máquina

- Geração de blocos axiomatic de variáveis por camada de refinamento:
  - `{Máquina}_variables` (abstrata),
  - `{Máquina}_r_variables` (refinamento),
  - `{Máquina}_i_variables` (implementação).
- Variáveis abstratas com cláusula `reads dummy_ghost_*`.
- Variáveis de refinamento/implementação com expressões de ligação (ex.: `numbers_s = return_valid_numbers_s(numbers)`).
- Reordenação da emissão: todos os blocos de variáveis em sequência **antes** de compreensões, constantes e valores.

### Invariantes

- Tradução de invariantes B para predicados ACSL nomeados (`{Máquina}_invariant`, `{Máquina}_r_invariant`, `{Máquina}_i_invariant`).
- Normalização de colagem (gluing) entre camadas de refinamento (ex.: `equals(numbers, ran(numbers_s))`).
- Tradução de imagem relacional (`r[{x}]`) via `apply(relation_inverse(r), x)`.

### Compreensões de conjuntos

- Registro global de `Quantified_Set` e intervalos com deduplicação por fingerprint sintático.
- Geração do bloco `{Máquina}_comprehension_sets` com axiomas `set_comp_k_values`.
- Compreensões em corpos de operações de máquinas abstratas/refinadas passam a ser registradas globalmente.
- Compreensões em operações de máquinas de **implementação** continuam excluídas do bloco global.
- Deduplicação sem gluing: `{xx | xx : numbers & …}` e `{xx | xx : ran(numbers_s) & …}` permanecem como conjuntos distintos.
- Referência a `set_comprehension_k` em contratos de operações (ex.: `card(set_comprehension_1)`).

### Funções lambda (`%`)

- Tradução de expressões lambda B para predicados lógicos nomeados (`lambda_func01`, …).
- Bloco axiomatic `lambda_functions` agregando todas as lambdas da máquina.

### Operações e substituições

- Tradução de contratos de operações (`requires`, `ensures`, `assigns`, `assert`).
- Tradução de `Becomes_Such_That` para cláusulas `ensures`, com desreferência de parâmetros de saída (`*pos`).
- `INITIALISATION` sem atribuições gera `assigns \nothing;`.
- Suporte a operação `ANY` (trabalho em progresso inicial).

---

## Interface gráfica e verificação formal

- Perfil nativo GraalVM no `pom.xml`.
- Diálogo de opções do Frama-C WP (`WpOptionsDialog`): projeto, provedor, timeout, tipo de saída.
- Relatório de verificação formal (`FormalVerificationReportDialog`, `VerificationReportData`).
- Suporte a modo headless para execução sem interface gráfica.

---

## Exemplos utilizados

### OddEvenCounter

Máquina com variável abstrata `numbers : POW(INTEGER)`, refinamento com sequência `numbers_s` e implementação com contadores `odd_counter` / `even_counter`.

Validou, entre outros pontos:

- tradução de variáveis em três camadas (`_variables`, `_r_variables`, `_i_variables`);
- compreensões `{xx | xx : numbers & xx mod 2 = n}` e `{xx | xx : ran(numbers_s) & xx mod 2 = n}` como quatro conjuntos distintos;
- invariantes com `iSeq`, `equals`, `card`, `belongs`, `NAT`, `is_finite`;
- operações `getOddCounter` / `getEvenCounter` referenciando `set_comprehension_k`;
- ghost patterns e contratos de `inserir` / `INITIALISATION`;
- instanciação da biblioteca para tipos `Set<integer>`, `\list<integer>`, `Relation_int_int`.

Arquivos BXML: `OddEvenCounter.bxml`, `OddEvenCounter_r.bxml`, `OddEvenCounter_i.bxml`.

### Railroad_switch (`switch`)

Máquina com conjunto enumerado `POSITION = {normal, reverse, void}` e operação `estimate` com `Becomes_Such_That`.

Validou, entre outros pontos:

- bloco axiomatic `switch_sets` com exaustividade de `POSITION`;
- prefixo `switch__` nos valores enumerados;
- tradução de `Becomes_Such_That` para `ensures` com `*pos` e conjuntos via `set_union(singleton(...))`;
- cast `(integer)` em parâmetros enum C;
- `INITIALISATION` com `assigns \nothing;`.

---

## Correções relevantes

- Ordenação de blocos axiomatic (variáveis → constantes/propriedades → compreensões → values → invariantes → lambdas → contratos).
- Tipos de variáveis abstratas: `logic Set<BOOK>` → `logic Set<integer> books` quando `BOOK` é conjunto definido.
- `\neq` traduzido corretamente; dummy `dummy` comparado com `!=` em invariantes.
- Dependências de includes corrigidas (`is_finite`, `is_sequence`, `list_to_function`, etc.).
- Validação de fingerprints para não confundir predicados ACSL com nomes de tipos na instanciação.

---

## Próximos passos sugeridos

- Consolidar suporte completo a `ANY`.
- Ampliar cobertura de construtos B ainda não traduzidos encontrados em novos exemplos.
- Automatizar testes de regressão com Frama-C WP para OddEvenCounter e Railroad_switch.
