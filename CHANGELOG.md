# Implementações v0.0.4

Release consolidando a tradução B → ACSL desde a v0.0.3 (52 commits) e uma refatoração de
arquitetura (split em módulos Maven). O conjunto de exemplos validados cresceu de **16 (v0.0.3)
para 31 projetos**, com **3583/4098 (87,4%) obrigações de prova (PO) provadas** automaticamente
pelo Frama-C WP (CVC5) nesta execução — sem nenhuma regressão introduzida pela refatoração de
arquitetura (verificado comparando a suíte completa antes/depois em 4 rodadas distintas).

---

## O que foi traduzido

### Sequências B (`seq`, `seq1`, `iseq`, `iseq1`, `perm`, …)

Cobertura completa da família de operadores de sequência do B, validada pelo projeto dedicado
`cv_seq` (18/18):

- `seq`/`seq1`/`iseq`/`iseq1`/`perm` decompostos em predicados já existentes (`seq` + `iSeq` +
  `sequence_ran` + `equals`) em vez de ficarem omitidos.
- `conc`, `rev`, `front`, `tail`, `first`, `last`, `length`, `is_seq_of`.
- `function_to_list`/`list_to_function`, `restrict_front`/`restrict_tail`.
- Correção de um bug latente de classe geral em `\list<A>` (sobrecarga de `belongs`) e de uma
  classe de axiomas de nil ambíguo (`front`/`tail`/`iSeq`/`conc`/`rev`).
- `>>` (concatenação) no lexer; tradução de sequências construídas por lambda como construtor
  recursivo (`\list` + lemas-ponte `_length`/`_nth`) — necessário para o invariante de buffer
  circular do `RobustFifo`, item que ficara aberto desde a v0.0.3.

### Funções e relações (`cv_fun`, operadores de relação)

- Tradução de `+->>`, `>+>`, `>->`, `>->>` (função parcial/total sobrejetiva/injetiva/bijetiva),
  validada pelo projeto `cv_fun` (18/18).
- `closure`, `closure1`, `iterate` sobre relações.
- `succ`/`pred` como identificador solto (`(nn|->nn+1):succ` → `belongs(couple(nn,nn+1), succ)`).
- `<+` (overwrite de relação) via nova função `overwrite`/`relation_overwrite` na biblioteca.
- `f(x) := y` (sobrescrita de relação/função) traduzido para
  `equals(f, overwrite(f, singleton(couple(x,y))))` em vez da equivalência fraca
  `function_apply(f,x)==y`.
- `::` (`becomes_element_of`): nova tradução com 2 sobrecargas (domínio-função e conjunto
  simples), substituindo `is_total_function`/`belongs`.
- `Relation<A,B>`/`Function<A,B>` genéricos em todo lugar onde antes se instanciava um literal
  achatado (`Relation_X`/`Function_X`) — corrige uma regressão que quebrava 6/17 projetos quando
  o literal não estava previamente declarado.
- `**` (potência): geração automática de `b_pow.acsl` com contrato dedicado sempre que detectado,
  evitando depender de um helper de runtime sem especificação.

### Conjuntos e quantificadores generalizados

- `cv_sets` (21/21): operações de não-inclusão, `inter(...)` e `union(...)` explícitos.
- Novos registros dedicados `SigmaFunctionRegistry` (`SIGMA`/`PI`/`MIN`/`MAX`) e
  `UnionInterFunctionRegistry` (`UNION`/`INTER`) — cada quantificador generalizado do B vira uma
  função lógica nomeada, axiomatizada por indução (domínio-intervalo) ou estruturalmente sobre
  `empty`/`set_union` (domínio-conjunto), com lemas-ponte para indução de loop no WP.
- Registro global de nomes de conjuntos declarados (`declaredSetNames`) para não capturar o
  próprio nome do conjunto como variável livre espúria em lambdas (`%cc.(cc:SETNAME|...)`).

### Lambda B (`%`)

Redesenho em três vias, em vez de uma tradução única: construtor de sequência (recursivo, para
domínios `lo..hi`, com lemas-ponte `_length`/`_nth`), mapa (predicado/função, caminho antigo), e
eliminação `\let` para o padrão `#v.(v=E & P)`.

### `ANY` e cláusulas não-escalares

- Tradução migrada de `\forall … ==> …` para `\exists … && …`, com `\old()` correto.
- `AnySubMarkerSpec` (novo): predicados-marcador nulários para contornar a mini-DSL restrita do
  `-acsl-import` quando a cláusula quantificada não é escalar — permitindo suporte inicial a
  `ANY` sobre tipos compostos, tanto no caminho ghost quanto no caminho de contrato real.

### `DEFINITIONS`, operações locais e variáveis cross-machine

- `BDefinitionsTranslator` (novo): traduz a cláusula B `DEFINITIONS` para um bloco `axiomatic`
  ACSL e reescreve os pontos de uso para os nomes simbólicos.
- Suporte a `LOCAL_OPERATIONS` na tradução de operações.
- Tipos lógicos de variável resolvidos de forma transitiva entre máquinas (`SEES`/`IMPORTS`),
  fechando mais uma instância da classe de bug "só olha um nível de profundidade" já corrigida
  parcialmente na v0.0.3 (agora também para a especificação de loop de `INITIALISATION` quando o
  domínio é valorado numa máquina só-`SEES`).
- Variáveis de mesmo nome entre abstrato e implementação continuam colapsando numa única
  variável array-backed (sem gêmeo ghost) — comportamento herdado da v0.0.3, agora coberto por
  mais exemplos.

---

## Arquitetura (refatoração, sem mudança de tradução)

Trabalho de reorganização estrutural, verificado a cada etapa contra a suíte completa de
exemplos sem nenhuma regressão:

- Extração de interfaces (`ExternalVerifierRunner` sobre `FramaCRunner`, `AcslLibraryResolver`
  sobre `AcslLibIncludes`), eliminação de estado estático mutável (`TupleCodomainTypeRegistry`,
  `cachedProps`), `B2AcslConfig` tipado substituindo leituras dispersas de `System.getProperty`,
  e uma suíte JUnit inicial (14 testes) para os tradutores `bxml.*` sem estado.
- Lista de estágios nomeada para o pós-processamento de `merged_code.c`; quebra dos
  acoplamentos circulares `B2ACSLPipeline`↔`FramaCRunner` e
  `BxmlMachineVariables`↔`ConcreteAssignTargetResolver`.
- **Split em 4 módulos Maven** (`core`/`translate`/`frama-c`/`cli`), desenhado pela dependência
  real de classes — o módulo `translate` (tradução BXML→ACSL + biblioteca `B2ACSLLib`) é
  standalone, sem depender de nenhum outro módulo do projeto.
- `targetAcslDir` deixou de ser uma property da JVM escrita em runtime, virando parâmetro `Path`
  explícito.

---

## Exemplos utilizados para verificar a tradução (31 projetos)

Resultados de obrigações de prova (PO) obtidos executando o pipeline completo
(`B2ACSLPipeline` → Frama-C `-acsl-import` → `-wp -wp-prover CVC5 -wp-rte -wp-smoke-tests`)
contra o estado atual do código, pasta `examples/`:

| Projeto | PO provadas / total | % |
|---|---|---|
| AddRunner | 41 / 41 | 100,0% |
| airlock | 123 / 123 | 100,0% |
| Biblioteca | 172 / 218 | 78,9% |
| BirthdayRegister | 60 / 64 | 93,8% |
| Customer_estr | 138 / 147 | 93,9% |
| cv_arith | 20 / 20 | 100,0% |
| cv_base | 11 / 11 | 100,0% |
| cv_closure | 18 / 18 | 100,0% |
| cv_fun | 18 / 18 | 100,0% |
| cv_rec¹ | não conclui | — |
| cv_rel | 18 / 22 | 81,8% |
| cv_seq | 18 / 18 | 100,0% |
| cv_sets | 21 / 21 | 100,0% |
| cv_struct | 41 / 41 | 100,0% |
| DataFields | 3 / 3 | 100,0% |
| DataValidation | 147 / 167 | 88,0% |
| filling_array | 143 / 158 | 90,5% |
| finding_the_max_array | 156 / 177 | 88,1% |
| fuel_level | 239 / 239 | 100,0% |
| integer_arithmetic_calculator | 123 / 125 | 98,4% |
| mult | 78 / 78 | 100,0% |
| OddEvenCounter | 59 / 63 | 93,7% |
| railroad_switch | 47 / 47 | 100,0% |
| Register | 65 / 75 | 86,7% |
| RobustFifo | 331 / 334 | 99,1% |
| Room | 25 / 26 | 96,2% |
| RulesOfTheSeas | 1319 / 1679 | 78,6% |
| Seats | 88 / 102 | 86,3% |
| simple_loop | 38 / 38 | 100,0% |
| TestLocalOperation | 23 / 25 | 92,0% |
| xor_integrity² | 74 / 74 (exit≠0) | — |
| **Total (29 projetos concluídos)** | **3583 / 4098** | **87,4%** |

¹ `cv_rec` trava na etapa de parsing do Frama-C (`-acsl-import -print`) antes de qualquer goal de
WP ser agendado — pré-existente, reproduzido de forma idêntica num worktree isolado da branch
`main` pristina (sem nenhuma das mudanças desta release).

² `xor_integrity` prova **todas as 74 obrigações** que consegue agendar, mas termina com código de
saída ≠ 0: a verificação por-operação tenta rodar `-wp-fct` também para operações de máquinas
`SEES`-only (`xor_ops`, `xor_spec`) que não têm função C compilada correspondente, e o Frama-C
recusa esses 3 alvos com "no function". Também pré-existente — o mesmo comportamento aparece já
no commit anterior à v0.0.3 (`xor_integrity` era 174/174 antes de um merge posterior introduzir
esse efeito colateral do modo por-operação).

**Verificação cruzada independente** — 6 projetos também têm uma referência `correct/` (contrato
ACSL escrito à mão, verificada com `frama-c -wp` direto, fora do pipeline `-acsl-import`):
BirthdayRegister (73/73), Customer_estr (119/126), filling_array (131/134),
finding_the_max_array (189/189), OddEvenCounter (40/42), RobustFifo (323/324).

Das PO não provadas nos 29 projetos concluídos, a mesma classificação da v0.0.3 continua válida:
a maioria é timeout do CVC5 (candidata a prover alternativo/timeout maior) ou smoke-test de
código morto (`Doomed`) já revisado — não há nenhum contraexemplo real (`Invalid`) em nenhum
exemplo.

---

## Próximos passos sugeridos

- Resolver os 3 alvos `SEES`-only de `xor_integrity` na verificação por-operação (não incluir
  operações sem função C própria na lista de `-wp-fct`).
- Investigar o parsing travado de `cv_rec`.
- Investigar os goals com timeout de maior duração (`RulesOfTheSeas`, `Biblioteca`, `Seats`,
  `Register`) com timeout maior ou lemmas auxiliares.
- Seção `ASSERTIONS` de B (obrigações de prova explícitas na máquina) segue sem suporte —
  afeta `cv_arith`/`cv_sets`/`cv_rel`/`cv_seq`/`xor_integrity`; adiado por decisão consciente.
- Considerar publicar o módulo `translate` como artefato standalone (Maven Central ou repositório
  interno), já que ele não depende de nenhum outro módulo do projeto.

---

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
