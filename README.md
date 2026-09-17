# xml-reader (Java 21 + Maven)

Projeto de exemplo estruturado com Maven para:

- Leitura de XML com **Jackson Dataformat XML**
- Geração de **Uber-JAR** (Shade) para uso com `jpackage`
- Build de executável nativo com **GraalVM Native Image**
- Geração de instalador com **jpackage** (Linux/macOS/Windows)

## Clonar o repositório

O plugin depende da biblioteca ACSL em `translate/src/main/resources/lib`, registrada como submódulo Git apontando para [ACSL2BMethodLib](https://github.com/fagnerdias/ACSL2BMethodLib).

Clone o repositório principal já trazendo o submódulo:

```bash
git clone --recurse-submodules git@github.com:fagnerdias/B2ACSLPlugin.git
cd B2ACSLPlugin
```

Se o repositório já foi clonado sem `--recurse-submodules`, inicialize o submódulo depois:

```bash
git submodule update --init --recursive
```

## Atualizar o projeto

Para atualizar o repositório principal e o commit fixado do submódulo:

```bash
git pull
git submodule update --init --recursive
```

Para buscar a versão mais recente da biblioteca no remoto do submódulo:

```bash
git submodule update --remote translate/src/main/resources/lib
```

Revise as alterações em `translate/src/main/resources/lib` antes de fixar um novo commit do submódulo no repositório principal.

### Biblioteca ACSL no classpath

O submódulo preenche `translate/src/main/resources/lib` com o repositório completo da ACSL2BMethodLib. O plugin carrega as funções ACSL diretamente de `translate/src/main/resources/lib/B2ACSLLib` no classpath (módulo Maven `translate`).

O mapa de dependências entre símbolos da biblioteca fica em `translate/src/main/resources/b2acsl/symbol_dependency_map.json` e pode ser regenerado com:

```bash
python3 scripts/generate_acsl_symbol_dependency_map.py
```

## Pré-requisitos

- **JDK 21+** (inclui `jpackage`)
- **Maven 3.9+**
- (Opcional) **GraalVM 21+** com `native-image` instalado, para `make build-native`

No Ubuntu/Debian, para instalar o Maven:

```bash
sudo apt update && sudo apt install -y maven
```

### GraalVM Native Image (opcional)

Se você pretende gerar o executável nativo:

- Instale uma distribuição GraalVM compatível com Java 21
- Aponte `JAVA_HOME` para a GraalVM
- Garanta que o `native-image` esteja instalado

## Estrutura do projeto

O projeto é um reactor Maven multi-módulo (`pom.xml` na raiz, `packaging=pom`), dividido em 4 módulos com dependência acíclica entre si (`core`/`translate` não dependem de nada no projeto; `frama-c` depende dos dois; `cli` depende dos três):

- `core/`: diálogos Swing (`com.example.ui.*`), estimador de `-ulevel` (`com.example.analysis`), configuração tipada `B2AcslConfig`
- `translate/`: tradução BXML → ACSL (`com.example.bxml.*`, `AcslGenerator`), resolução da biblioteca (`AcslLibIncludes`, `AcslLibSymbolDependencyMap`); inclui o submódulo Git `translate/src/main/resources/lib/` (`B2ACSLLib/`), `translate/src/main/resources/b2acsl/symbol_dependency_map.json` e os testes JUnit (`translate/src/test/`)
- `frama-c/`: invocação do Frama-C (`FramaCRunner`) e pós-processamento de `merged_code.c`
- `cli/`: ponto de entrada (`Main.java`) e orquestração (`B2ACSLPipeline`) — artifactId mantido como `xml-reader` (não `b2acsl-cli`) e build redirecionado para `target/` na raiz, para preservar os caminhos que `scripts/run_examples.sh`, o `Makefile` e o `plugin.etool` do Atelier B já esperavam antes do split
- `pom.xml`: pai do reactor — propriedades compartilhadas, `<modules>`, versões de plugin
- `Makefile`: automações `build-jar`, `build-native`, `build-installer`, `clean` (usa `mvn -pl cli help:evaluate` para descobrir `artifactId`/`version` do módulo final)

## Como executar

### 1) Gerar e rodar o Uber-JAR

```bash
make build-jar
java -jar target/xml-reader-0.1.0-all.jar
```

> Observação: o nome do JAR segue o padrão `target/<artifactId>-<version>-all.jar`.

### 2) Gerar executável nativo (GraalVM)

```bash
make build-native
```

Saídas comuns:

- Linux/macOS: `target/xml-reader`
- Windows: `target/xml-reader.exe`

### 3) Gerar instalador com jpackage

```bash
make build-installer
```

O `Makefile` escolhe o tipo com base no sistema:

- Linux: `.deb` (requer ferramentas do sistema como `dpkg-deb`)
- macOS: `.dmg`
- Windows: `.exe` (pode requerer toolchain adicional dependendo do tipo escolhido)

Os arquivos ficam em `target/installer/`.

## Notas sobre compatibilidade (jpackage + módulos)

O alvo `build-installer` usa `--add-modules java.base,java.xml,java.logging` para garantir que os módulos essenciais estejam disponíveis no runtime empacotado.

Se você adicionar bibliotecas/recursos que dependam de outros módulos (ex.: `java.sql`, `java.desktop`), inclua-os também em `--add-modules` no `Makefile`.

## Comandos úteis

```bash
make clean
```

