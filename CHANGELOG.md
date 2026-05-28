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
