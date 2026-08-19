#!/usr/bin/env bash
# Executa o pipeline B2ACSL (bxml -> .acsl/.c -> Frama-C acsl-import + WP) para cada exemplo em
# examples/*. Para exemplos com uma pasta "correct" (referência já verificada manualmente), roda
# também "frama-c -wp" diretamente sobre o(s) arquivo(s) dessa pasta, como segunda checagem
# independente do pipeline.
#
# Uso:
#   scripts/run_examples.sh [-b|--background] [nome_do_exemplo ...]
#
# Sem argumentos, roda todos os exemplos em examples/. Com argumentos, roda só os exemplos
# nomeados (ex.: scripts/run_examples.sh Customer_estr RobustFifo).
#
# -b, --background   roda em background (nohup + disown): imprime o PID e o ficheiro de log e
#                     retorna imediatamente, sem bloquear o terminal — útil para exemplos
#                     demorados (ex. RulesOfTheSeas, ~10min) ou para rodar a suite toda sem
#                     esperar. Equivalente a definir BACKGROUND=1. Acompanhar com:
#                     tail -f <ficheiro de log impresso>
#                     Também liga -Djava.awt.headless=true na JVM (false por padrão em
#                     foreground) — só em background não há display/terminal interativo por
#                     perto para a JVM sequer tentar depender de um.
#
# Variáveis de ambiente:
#   WP_TIMEOUT   timeout por goal do -wp-timeout / b2acsl.wp.timeout (default: 10, igual ao
#                default do próprio plugin)
#   WP_PROVER    prova usado no -wp-prover (default: CVC5)
#   BACKGROUND   "1" equivale a passar -b/--background

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# -b/--background: remove a flag dos argumentos e re-executa este script destacado (nohup +
# disown), devolvendo o controlo do terminal de imediato. _RUN_EXAMPLES_BG_CHILD evita re-destacar
# infinitamente quando o processo filho reexecuta este mesmo script.
BACKGROUND="${BACKGROUND:-0}"
args=()
for arg in "$@"; do
    case "$arg" in
        -b|--background) BACKGROUND=1 ;;
        *) args+=("$arg") ;;
    esac
done
set -- "${args[@]}"

if [ "$BACKGROUND" = "1" ] && [ -z "${_RUN_EXAMPLES_BG_CHILD:-}" ]; then
    BG_LOG="scripts/run-logs/background_$(date +%Y%m%d_%H%M%S).log"
    mkdir -p "$(dirname "$BG_LOG")"
    _RUN_EXAMPLES_BG_CHILD=1 nohup "$0" "$@" > "$BG_LOG" 2>&1 &
    disown
    echo "[run_examples] Rodando em background (PID: $!)"
    echo "[run_examples] Log: $BG_LOG"
    echo "[run_examples] Acompanhar com: tail -f $BG_LOG"
    exit 0
fi

JAR="target/xml-reader-0.1.0-all.jar"
EXAMPLES_DIR="examples"
TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
LOG_DIR="scripts/run-logs/${TIMESTAMP}"
SUMMARY_FILE="${LOG_DIR}/summary.tsv"

WP_TIMEOUT="${WP_TIMEOUT:-50}"
WP_PROVER="${WP_PROVER:-CVC5}"

# headless: false por padrão (execução em foreground, terminal interativo); true só quando
# efetivamente destacado em background (via -b/--background) — é aí que não há display/terminal
# interativo por perto e a JVM não deve sequer tentar depender de um.
JAVA_HEADLESS="false"
if [ -n "${_RUN_EXAMPLES_BG_CHILD:-}" ]; then
    JAVA_HEADLESS="true"
fi

mkdir -p "$LOG_DIR"

if [ ! -f "$JAR" ]; then
    echo "[run_examples] $JAR não encontrado; compilando com Maven (mvn -DskipTests package)..."
    mvn -q -DskipTests package
fi

printf 'example\tstage\ttarget\texit_code\tduration_s\tproved\ttotal\tstatus\n' > "$SUMMARY_FILE"

# Soma todas as ocorrências "Proved goals: X / Y" de um log (o WP imprime um bloco por função).
sum_proved_goals() {
    grep -oiE 'proved goals[[:space:]]*:[[:space:]]*[0-9]+[[:space:]]*/[[:space:]]*[0-9]+' "$1" \
        | grep -oE '[0-9]+[[:space:]]*/[[:space:]]*[0-9]+' \
        | awk -F/ '{p+=$1; t+=$2} END{print p+0, t+0}'
}

record_result() {
    local example="$1" stage="$2" target="$3" exit_code="$4" duration="$5" logfile="$6"
    local proved total status
    read -r proved total <<< "$(sum_proved_goals "$logfile")"
    if [ "$exit_code" -ne 0 ]; then
        status="ERROR(exit=$exit_code)"
    elif [ "${total:-0}" -eq 0 ]; then
        status="NO_GOALS"
    elif [ "$proved" -eq "$total" ]; then
        status="ALL_PROVED"
    else
        status="PARTIAL"
    fi
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$example" "$stage" "$target" "$exit_code" "$duration" "${proved:-0}" "${total:-0}" "$status" \
        | tee -a "$SUMMARY_FILE"
}

# Pipeline completo do B2ACSL: java -jar <jar> <bdp> (headless: ver JAVA_HEADLESS acima).
run_pipeline() {
    local example_dir="$1" name="$2"
    local bdp="${example_dir}/bdp"
    if [ ! -d "$bdp" ]; then
        echo "[run_examples] $name: sem pasta bdp/, pulando pipeline"
        return
    fi

    local logfile="${LOG_DIR}/${name}/pipeline.log"
    mkdir -p "$(dirname "$logfile")"

    echo "[run_examples] === $name: pipeline B2ACSL ==="
    local start end exit_code
    start=$(date +%s)
    java -Djava.awt.headless="$JAVA_HEADLESS" \
         -Db2acsl.wp.prover="$WP_PROVER" \
         -Db2acsl.wp.timeout="$WP_TIMEOUT" \
         -jar "$JAR" "$ROOT_DIR/$bdp" > "$logfile" 2>&1
    exit_code=$?
    end=$(date +%s)

    record_result "$name" "pipeline" "$(basename "$bdp")" "$exit_code" "$((end - start))" "$logfile"
}

# Referência "correct/": arquivo já contém ACSL embutido (é um merged_code.c revisado à mão), então
# roda-se "frama-c -wp" direto nele, sem passar por -acsl-import.
run_correct_reference() {
    local example_dir="$1" name="$2"
    local correct_dir="${example_dir}/correct"
    [ -d "$correct_dir" ] || return

    local ref_file ref_name logfile start end exit_code
    for ref_file in "$correct_dir"/*; do
        [ -f "$ref_file" ] || continue
        ref_name="$(basename "$ref_file")"
        logfile="${LOG_DIR}/${name}/correct_${ref_name}.log"
        mkdir -p "$(dirname "$logfile")"

        echo "[run_examples] === $name: referência correct/$ref_name ==="
        start=$(date +%s)
        (
            cd "$correct_dir" && frama-c -wp "$ref_name" \
                -wp-prover "$WP_PROVER" -wp-rte -wp-timeout "$WP_TIMEOUT" -wp-status
        ) > "$logfile" 2>&1
        exit_code=$?
        end=$(date +%s)

        record_result "$name" "correct" "$ref_name" "$exit_code" "$((end - start))" "$logfile"
    done
}

if [ "$#" -gt 0 ]; then
    example_names=("$@")
else
    example_names=()
    for example_dir in "$EXAMPLES_DIR"/*/; do
        example_names+=("$(basename "${example_dir%/}")")
    done
fi

for name in "${example_names[@]}"; do
    example_dir="${EXAMPLES_DIR}/${name}"
    if [ ! -d "$example_dir" ]; then
        echo "[run_examples] Exemplo '$name' não encontrado em $EXAMPLES_DIR/, pulando"
        continue
    fi
    run_pipeline "$example_dir" "$name"
    run_correct_reference "$example_dir" "$name"
done

echo
echo "[run_examples] Logs completos em: $LOG_DIR"
echo "[run_examples] Resumo salvo em:   $SUMMARY_FILE"
echo
column -t -s $'\t' "$SUMMARY_FILE"
