#!/usr/bin/env bash

set -uo pipefail
IFS=$'\n\t'

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
readonly GRADLEW="${REPO_ROOT}/gradlew"
readonly MAX_NUMERIC_SETTING=1000000
readonly MAX_MODEL_ID=9223372036854775807

usage() {
    cat <<'USAGE'
Usage:
  tools/rebuild_v3_models_parallel.sh [options] --model-id ID [--model-id ID ...]

Required environment (inherited by Gradle; never copied into argv):
  MOLCLASS_JDBC_URL
  MOLCLASS_DB_USER
  MOLCLASS_DB_PASSWORD

Options:
  --model-id ID                  Positive, unique v3 model definition ID. Repeatable.
  --lanes N                      Concurrent Gradle processes (default: 4).
  --threads-per-lane N           Weka threads passed to each process (default: 8).
  --heap-per-lane SIZE           JavaExec heap in whole GiB, such as 24g (default: 24g).
  --max-aggregate-heap SIZE      Aggregate heap ceiling (default: 96g).
  --max-aggregate-threads N      Aggregate Weka thread ceiling (default: 32).
  --log-dir PATH                 New directory for per-model logs and summary.
  --termination-grace-seconds N  Grace before KILL after TERM/INT (default: 30).
  --validate-only                Validate the complete launch plan; start no process.
  -h, --help                     Show this help.

Environment overrides:
  MOLCLASS_PARALLEL_LANES
  MOLCLASS_PARALLEL_THREADS_PER_LANE
  MOLCLASS_PARALLEL_HEAP_PER_LANE
  MOLCLASS_PARALLEL_MAX_AGGREGATE_HEAP
  MOLCLASS_PARALLEL_MAX_AGGREGATE_THREADS
  MOLCLASS_PARALLEL_LOG_DIR
  MOLCLASS_PARALLEL_TERMINATION_GRACE_SECONDS

This launcher never approves or publishes a model. Safe multi-lane operation requires
the model rebuilder to enforce an independent database lease per model definition.
USAGE
}

die() {
    printf 'error: %s\n' "$*" >&2
    exit 2
}

require_value() {
    local option=$1
    local remaining=$2
    (( remaining >= 2 )) || die "${option} requires a value"
}

validate_small_positive_integer() {
    local value=$1
    [[ $value =~ ^[1-9][0-9]*$ ]] || return 1
    ((${#value} <= 7)) || return 1
    (( 10#${value} <= MAX_NUMERIC_SETTING ))
}

validate_model_id() {
    local value=$1
    [[ $value =~ ^[1-9][0-9]*$ ]] || return 1
    ((${#value} < ${#MAX_MODEL_ID})) && return 0
    ((${#value} == ${#MAX_MODEL_ID})) || return 1
    [[ $value < $MAX_MODEL_ID || $value == "$MAX_MODEL_ID" ]]
}

heap_gib() {
    local value=$1
    [[ $value =~ ^([1-9][0-9]*)[gG]$ ]] || return 1
    local gib=${BASH_REMATCH[1]}
    validate_small_positive_integer "$gib" || return 1
    printf '%s\n' "$gib"
}

lanes=${MOLCLASS_PARALLEL_LANES:-4}
threads_per_lane=${MOLCLASS_PARALLEL_THREADS_PER_LANE:-8}
heap_per_lane=${MOLCLASS_PARALLEL_HEAP_PER_LANE:-24g}
max_aggregate_heap=${MOLCLASS_PARALLEL_MAX_AGGREGATE_HEAP:-96g}
max_aggregate_threads=${MOLCLASS_PARALLEL_MAX_AGGREGATE_THREADS:-32}
termination_grace_seconds=${MOLCLASS_PARALLEL_TERMINATION_GRACE_SECONDS:-30}
default_log_dir="${REPO_ROOT}/logs/v3-model-rebuild-$(date -u +%Y%m%dT%H%M%SZ)-$$"
log_dir=${MOLCLASS_PARALLEL_LOG_DIR:-$default_log_dir}
validate_only=false
declare -a model_ids=()

while (($# > 0)); do
    case $1 in
        --model-id)
            require_value "$1" "$#"
            model_ids+=("$2")
            shift 2
            ;;
        --model-id=*)
            model_ids+=("${1#*=}")
            shift
            ;;
        --lanes)
            require_value "$1" "$#"
            lanes=$2
            shift 2
            ;;
        --lanes=*)
            lanes=${1#*=}
            shift
            ;;
        --threads-per-lane)
            require_value "$1" "$#"
            threads_per_lane=$2
            shift 2
            ;;
        --threads-per-lane=*)
            threads_per_lane=${1#*=}
            shift
            ;;
        --heap-per-lane)
            require_value "$1" "$#"
            heap_per_lane=$2
            shift 2
            ;;
        --heap-per-lane=*)
            heap_per_lane=${1#*=}
            shift
            ;;
        --max-aggregate-heap)
            require_value "$1" "$#"
            max_aggregate_heap=$2
            shift 2
            ;;
        --max-aggregate-heap=*)
            max_aggregate_heap=${1#*=}
            shift
            ;;
        --max-aggregate-threads)
            require_value "$1" "$#"
            max_aggregate_threads=$2
            shift 2
            ;;
        --max-aggregate-threads=*)
            max_aggregate_threads=${1#*=}
            shift
            ;;
        --log-dir)
            require_value "$1" "$#"
            log_dir=$2
            shift 2
            ;;
        --log-dir=*)
            log_dir=${1#*=}
            shift
            ;;
        --termination-grace-seconds)
            require_value "$1" "$#"
            termination_grace_seconds=$2
            shift 2
            ;;
        --termination-grace-seconds=*)
            termination_grace_seconds=${1#*=}
            shift
            ;;
        --validate-only)
            validate_only=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        --)
            die "positional model IDs are not accepted; repeat --model-id ID"
            ;;
        -*|*)
            die "unknown argument '$1'; model IDs must use --model-id ID"
            ;;
    esac
done

((${#model_ids[@]} > 0)) || die "at least one explicit --model-id ID is required"
validate_small_positive_integer "$lanes" || die "--lanes must be an integer from 1 to ${MAX_NUMERIC_SETTING}"
validate_small_positive_integer "$threads_per_lane" || die "--threads-per-lane must be an integer from 1 to ${MAX_NUMERIC_SETTING}"
validate_small_positive_integer "$max_aggregate_threads" || die "--max-aggregate-threads must be an integer from 1 to ${MAX_NUMERIC_SETTING}"
validate_small_positive_integer "$termination_grace_seconds" || die "--termination-grace-seconds must be an integer from 1 to ${MAX_NUMERIC_SETTING}"

if ! heap_per_lane_gib=$(heap_gib "$heap_per_lane"); then
    die "--heap-per-lane must be a positive whole-GiB value such as 24g"
fi
if ! max_aggregate_heap_gib=$(heap_gib "$max_aggregate_heap"); then
    die "--max-aggregate-heap must be a positive whole-GiB value such as 96g"
fi

aggregate_heap_gib=$((lanes * heap_per_lane_gib))
aggregate_threads=$((lanes * threads_per_lane))
(( aggregate_heap_gib <= max_aggregate_heap_gib )) ||
    die "aggregate heap ${aggregate_heap_gib}g (${lanes} lanes x ${heap_per_lane_gib}g) exceeds limit ${max_aggregate_heap_gib}g"
(( aggregate_threads <= max_aggregate_threads )) ||
    die "aggregate threads ${aggregate_threads} (${lanes} lanes x ${threads_per_lane}) exceeds limit ${max_aggregate_threads}"

declare -A seen_model_ids=()
for model_id in "${model_ids[@]}"; do
    validate_model_id "$model_id" || die "invalid model ID '${model_id}'; expected a positive signed BIGINT"
    [[ ! -v "seen_model_ids[$model_id]" ]] || die "duplicate model ID '${model_id}'"
    seen_model_ids[$model_id]=1
done

for required_environment in MOLCLASS_JDBC_URL MOLCLASS_DB_USER MOLCLASS_DB_PASSWORD; do
    [[ -n ${!required_environment:-} ]] || die "required environment variable ${required_environment} is not set"
done

[[ -x $GRADLEW ]] || die "Gradle wrapper is not executable at ${GRADLEW}"
command -v setsid >/dev/null 2>&1 || die "setsid is required for per-model process-group cleanup"
if (( BASH_VERSINFO[0] < 5 || (BASH_VERSINFO[0] == 5 && BASH_VERSINFO[1] < 1) )); then
    die "Bash 5.1 or newer is required"
fi

if [[ $log_dir != /* ]]; then
    log_dir="${REPO_ROOT}/${log_dir}"
fi
[[ -n $log_dir ]] || die "--log-dir must not be empty"

printf 'Validated %d model IDs; lanes=%d; threads/lane=%d; heap/lane=%dg; aggregate=%d threads, %dg heap.\n' \
    "${#model_ids[@]}" "$lanes" "$threads_per_lane" "$heap_per_lane_gib" "$aggregate_threads" "$aggregate_heap_gib"
printf 'Model IDs:'
for model_id in "${model_ids[@]}"; do
    printf ' %s' "$model_id"
done
printf '\nLog directory: %s\n' "$log_dir"

if [[ $validate_only == true ]]; then
    printf 'Validation-only mode: no Gradle process was started.\n'
    exit 0
fi

[[ ! -e $log_dir ]] || die "log directory already exists: ${log_dir}"
mkdir -p -- "$log_dir" || die "cannot create log directory: ${log_dir}"

declare -a active_pids=()
declare -A pid_to_model=()
declare -A result_status=()
declare -A result_exit=()
declare -A result_log=()
next_model_index=0

remove_active_pid() {
    local completed_pid=$1
    local pid
    local -a remaining=()
    for pid in "${active_pids[@]}"; do
        [[ $pid == "$completed_pid" ]] || remaining+=("$pid")
    done
    active_pids=("${remaining[@]}")
}

start_model() {
    local model_id=$1
    local model_log="${log_dir}/model-${model_id}.log"
    local pid

    {
        printf 'MolClass v3 model rebuild\n'
        printf 'model_id=%s\n' "$model_id"
        printf 'threads=%s\n' "$threads_per_lane"
        printf 'max_heap=%sg\n' "$heap_per_lane_gib"
        printf 'started_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    } >"$model_log"

    (
        cd -- "$REPO_ROOT" || exit 125
        export MOLCLASS_MODEL_MAX_HEAP="${heap_per_lane_gib}g"
        export MOLCLASS_MODEL_THREADS="$threads_per_lane"
        exec setsid "$GRADLEW" --no-daemon rebuildV3Models \
            "-PmodelArgs=--model-id ${model_id} --threads ${threads_per_lane}"
    ) >>"$model_log" 2>&1 &
    pid=$!

    active_pids+=("$pid")
    pid_to_model[$pid]=$model_id
    result_log[$model_id]=$model_log
    printf 'Started model %s as process group %s; log=%s\n' "$model_id" "$pid" "$model_log"
}

record_completion() {
    local pid=$1
    local exit_code=$2
    local model_id=${pid_to_model[$pid]}

    if (( exit_code == 0 )); then
        result_status[$model_id]=SUCCEEDED
    else
        result_status[$model_id]=FAILED
    fi
    result_exit[$model_id]=$exit_code
    remove_active_pid "$pid"
    unset 'pid_to_model[$pid]'
    printf 'Finished model %s with exit code %s.\n' "$model_id" "$exit_code"
}

write_summary() {
    local model_id
    local status
    local exit_code
    local model_log
    local summary_file="${log_dir}/summary.tsv"

    {
        printf 'MODEL_ID\tSTATUS\tEXIT_CODE\tLOG\n'
        for model_id in "${model_ids[@]}"; do
            status=${result_status[$model_id]:-NOT_STARTED}
            exit_code=${result_exit[$model_id]:--}
            model_log=${result_log[$model_id]:--}
            printf '%s\t%s\t%s\t%s\n' "$model_id" "$status" "$exit_code" "$model_log"
        done
    } >"$summary_file"

    printf '\nRebuild summary:\n'
    cat -- "$summary_file"
}

terminate_children() {
    local signal_name=$1
    local launcher_exit=$2
    local pid
    local model_id
    local child_exit
    local deadline=$((SECONDS + termination_grace_seconds))

    trap - TERM INT
    printf '\nReceived %s; terminating %d active model process group(s).\n' "$signal_name" "${#active_pids[@]}" >&2
    for pid in "${active_pids[@]}"; do
        kill -TERM -- "-${pid}" 2>/dev/null || true
    done

    while ((${#active_pids[@]} > 0 && SECONDS < deadline)); do
        local -a still_running=()
        for pid in "${active_pids[@]}"; do
            if kill -0 -- "-${pid}" 2>/dev/null; then
                still_running+=("$pid")
            fi
        done
        ((${#still_running[@]} == 0)) && break
        sleep 0.2
    done

    for pid in "${active_pids[@]}"; do
        if kill -0 -- "-${pid}" 2>/dev/null; then
            kill -KILL -- "-${pid}" 2>/dev/null || true
        fi
    done

    for pid in "${active_pids[@]}"; do
        model_id=${pid_to_model[$pid]}
        wait "$pid"
        child_exit=$?
        result_status[$model_id]=INTERRUPTED
        result_exit[$model_id]=$child_exit
    done
    active_pids=()
    write_summary
    exit "$launcher_exit"
}

trap 'terminate_children TERM 143' TERM
trap 'terminate_children INT 130' INT

while (( next_model_index < ${#model_ids[@]} || ${#active_pids[@]} > 0 )); do
    while (( next_model_index < ${#model_ids[@]} && ${#active_pids[@]} < lanes )); do
        start_model "${model_ids[$next_model_index]}"
        ((next_model_index += 1))
    done

    if ((${#active_pids[@]} > 0)); then
        completed_pid=
        wait -n -p completed_pid "${active_pids[@]}"
        completed_exit=$?
        [[ -n $completed_pid ]] || die "wait returned without a completed child PID"
        record_completion "$completed_pid" "$completed_exit"
    fi
done

write_summary

for model_id in "${model_ids[@]}"; do
    if [[ ${result_status[$model_id]} != SUCCEEDED ]]; then
        exit 1
    fi
done
exit 0
