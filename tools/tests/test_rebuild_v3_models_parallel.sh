#!/usr/bin/env bash

set -euo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
readonly LAUNCHER="${REPO_ROOT}/tools/rebuild_v3_models_parallel.sh"
readonly SECRET_SENTINEL='parallel-launcher-secret-must-not-be-printed'

work_dir=$(mktemp -d)
trap 'rm -rf -- "$work_dir"' EXIT

export MOLCLASS_JDBC_URL='jdbc:mysql://example.invalid:3306/'
export MOLCLASS_DB_USER='validation-only-user'
export MOLCLASS_DB_PASSWORD=$SECRET_SENTINEL

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    exit 1
}

assert_contains() {
    local output=$1
    local expected=$2
    [[ $output == *"$expected"* ]] || fail "expected output to contain: ${expected}"
}

expect_failure() {
    local expected=$1
    shift
    local output
    if output=$("$LAUNCHER" "$@" 2>&1); then
        fail "command unexpectedly succeeded: $*"
    fi
    assert_contains "$output" "$expected"
}

validation_log_dir="${work_dir}/must-not-exist"
output=$("$LAUNCHER" \
    --validate-only \
    --log-dir "$validation_log_dir" \
    --model-id 101 \
    --model-id 102 \
    --model-id 103 \
    --model-id 104 2>&1) || fail "valid default launch plan was rejected"
assert_contains "$output" 'Validated 4 model IDs; lanes=4; threads/lane=8; heap/lane=24g; aggregate=32 threads, 96g heap.'
assert_contains "$output" 'Validation-only mode: no Gradle process was started.'
[[ $output != *"$SECRET_SENTINEL"* ]] || fail 'password appeared in validation output'
[[ ! -e $validation_log_dir ]] || fail 'validation-only mode created the log directory'

expect_failure "duplicate model ID '101'" \
    --validate-only --model-id 101 --model-id 101
expect_failure 'aggregate heap 120g (5 lanes x 24g) exceeds limit 96g' \
    --validate-only --lanes 5 --model-id 101
expect_failure 'aggregate threads 36 (4 lanes x 9) exceeds limit 32' \
    --validate-only --threads-per-lane 9 --model-id 101
expect_failure "invalid model ID '0'" \
    --validate-only --model-id 0
expect_failure 'at least one explicit --model-id ID is required' \
    --validate-only

output=$(env -u MOLCLASS_DB_PASSWORD "$LAUNCHER" --validate-only --model-id 101 2>&1) &&
    fail 'missing password was accepted'
assert_contains "$output" 'required environment variable MOLCLASS_DB_PASSWORD is not set'

output=$("$LAUNCHER" \
    --validate-only \
    --lanes 2 \
    --threads-per-lane 12 \
    --heap-per-lane 40g \
    --max-aggregate-threads 24 \
    --max-aggregate-heap 80g \
    --model-id 9223372036854775807 2>&1) || fail 'valid custom limits were rejected'
assert_contains "$output" 'aggregate=24 threads, 80g heap.'

printf 'PASS: parallel model launcher argument and resource-limit contract\n'
