#!/bin/bash
#
# Integration test suite for nf-stage plugin.
# Run from the validation/ directory.
#

set -uo pipefail

NXF=${NXF:-nextflow}
PASS=0
FAIL=0
LAST_OUTPUT=$(mktemp)

# Each test gets a unique prefix for its work dir, archive dir, and session dir,
# preventing state leakage between tests while staying in the validation directory.
TEST_ID=""

nxf_run() {
    $NXF run -work-dir "work-${TEST_ID}" "$@" > "$LAST_OUTPUT" 2>&1 || true
}

cleanup() {
    rm -rf ".nextflow" "work-${TEST_ID}" ".nf-stage-archive-${TEST_ID}" \
           "cached-stages-${TEST_ID}.tsv" ".nf-stage-test-${TEST_ID}.config" \
           ".nf-stage-noplugin-${TEST_ID}.config" 2>/dev/null || true
}

assert_completed() {
    local expected=$1
    local actual
    actual=$(grep -o 'completed=[0-9]*' "$LAST_OUTPUT" | tail -1 | cut -d= -f2)
    if [[ "$actual" != "$expected" ]]; then
        echo "  ASSERT FAILED: expected completed=${expected}, got completed=${actual}"
        return 1
    fi
}

assert_cached_stages() {
    local expected=$1
    local tsv="cached-stages-${TEST_ID}.tsv"
    if [[ ! -f "$tsv" ]]; then
        echo "  ASSERT FAILED: ${tsv} not found"
        return 1
    fi
    local actual
    actual=$(tail -n +2 "$tsv" | wc -l | tr -d ' ')
    if [[ "$actual" != "$expected" ]]; then
        echo "  ASSERT FAILED: expected ${expected} cached stages, got ${actual}"
        return 1
    fi
}

assert_file_exists() {
    if [[ ! -f "$1" ]]; then
        echo "  ASSERT FAILED: file not found: $1"
        return 1
    fi
}

run_test() {
    local name=$1
    local fn=$2
    # unique ID per test: test name + timestamp
    TEST_ID="${name//[^a-zA-Z0-9]/-}-$$"
    echo ""
    echo "=== TEST: ${name} ==="
    if eval "$fn"; then
        echo "  PASS"
        ((PASS++))
    else
        echo "  FAIL"
        ((FAIL++))
    fi
    cleanup
}

# Generate per-test nextflow.config that overrides workDir, archiveRoot, cachedStagesFile
test_config() {
    cat > ".nf-stage-test-${TEST_ID}.config" << EOF
plugins { id 'nf-stage@0.1.0' }
stage {
    archiveRoot = '.nf-stage-archive-${TEST_ID}'
    cachedStagesFile = 'cached-stages-${TEST_ID}.tsv'
}
workDir = 'work-${TEST_ID}'
params {
    reference = 'GRCh38'
    dbsnp = 'dbsnp154'
    param_b = 'v1'
    param_c = 'v1'
    expected_total = 100
    summary_version = 'v1'
}
EOF
    echo ".nf-stage-test-${TEST_ID}.config"
}

noplugin_config() {
    cat > ".nf-stage-noplugin-${TEST_ID}.config" << EOF
workDir = 'work-${TEST_ID}'
params {
    reference = 'GRCh38'
    dbsnp = 'dbsnp154'
    param_b = 'v1'
    param_c = 'v1'
    expected_total = 100
    summary_version = 'v1'
}
EOF
    echo ".nf-stage-noplugin-${TEST_ID}.config"
}

between_runs() {
    # keep archive, drop session/work for next run
    rm -rf ".nextflow" "work-${TEST_ID}" 2>/dev/null || true
}

# ------------------------------------------------------------------
# Test 1: Basic archive and restore
# ------------------------------------------------------------------
test_basic() {
    local cfg; cfg=$(test_config)
    $NXF run test-basic.nf -c "$cfg" -work-dir "work-${TEST_ID}" > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 4

    between_runs
    $NXF run test-basic.nf -c "$cfg" -work-dir "work-${TEST_ID}" > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 0
    assert_cached_stages 2
    rm -f "$cfg"
}

# ------------------------------------------------------------------
# Test 2: Partial rerun - change last stage param
# ------------------------------------------------------------------
test_partial_rerun_last() {
    local cfg; cfg=$(test_config)
    $NXF run test-partial-rerun.nf -c "$cfg" -work-dir "work-${TEST_ID}" > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 6

    between_runs
    $NXF run test-partial-rerun.nf -c "$cfg" -work-dir "work-${TEST_ID}" --dbsnp dbsnp155 > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 2
    assert_cached_stages 2
    rm -f "$cfg"
}

# ------------------------------------------------------------------
# Test 3: Partial rerun - change middle stage param
# ------------------------------------------------------------------
test_partial_rerun_middle() {
    local cfg; cfg=$(test_config)
    $NXF run test-partial-rerun.nf -c "$cfg" -work-dir "work-${TEST_ID}" > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 6

    between_runs
    $NXF run test-partial-rerun.nf -c "$cfg" -work-dir "work-${TEST_ID}" --reference hg19 > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 4
    assert_cached_stages 1
    rm -f "$cfg"
}

# ------------------------------------------------------------------
# Test 4: Multi-emit channels
# ------------------------------------------------------------------
test_multi_emit() {
    local cfg; cfg=$(test_config)
    $NXF run test-multi-emit.nf -c "$cfg" -work-dir "work-${TEST_ID}" > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 4

    between_runs
    $NXF run test-multi-emit.nf -c "$cfg" -work-dir "work-${TEST_ID}" > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 0
    assert_cached_stages 1
    rm -f "$cfg"
}

# ------------------------------------------------------------------
# Test 5: Value channel emit
# ------------------------------------------------------------------
test_value_channel() {
    local cfg; cfg=$(test_config)
    $NXF run test-value-channel.nf -c "$cfg" -work-dir "work-${TEST_ID}" > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 2

    between_runs
    $NXF run test-value-channel.nf -c "$cfg" -work-dir "work-${TEST_ID}" > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 0
    rm -f "$cfg"
}

# ------------------------------------------------------------------
# Test 6: Single file emit (not tuple)
# ------------------------------------------------------------------
test_single_file() {
    local cfg; cfg=$(test_config)
    $NXF run test-single-file.nf -c "$cfg" -work-dir "work-${TEST_ID}" > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 2

    between_runs
    $NXF run test-single-file.nf -c "$cfg" -work-dir "work-${TEST_ID}" > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 0
    rm -f "$cfg"
}

# ------------------------------------------------------------------
# Test 7: Same filename across samples
# ------------------------------------------------------------------
test_same_filename() {
    local cfg; cfg=$(test_config)
    $NXF run test-same-filename.nf -c "$cfg" -work-dir "work-${TEST_ID}" > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 2

    between_runs
    $NXF run test-same-filename.nf -c "$cfg" -work-dir "work-${TEST_ID}" > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 0

    local f1 f2
    f1=$(find ".nf-stage-archive-${TEST_ID}" -path "*/0/report.txt" 2>/dev/null)
    f2=$(find ".nf-stage-archive-${TEST_ID}" -path "*/1/report.txt" 2>/dev/null)
    assert_file_exists "$f1"
    assert_file_exists "$f2"
    if diff -q "$f1" "$f2" > /dev/null 2>&1; then
        echo "  ASSERT FAILED: same-name files should have different content"
        rm -f "$cfg"
        return 1
    fi
    rm -f "$cfg"
}

# ------------------------------------------------------------------
# Test 8: Three-level chain - change last param
# ------------------------------------------------------------------
test_chain_last() {
    local cfg; cfg=$(test_config)
    $NXF run test-chain.nf -c "$cfg" -work-dir "work-${TEST_ID}" > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 6

    between_runs
    $NXF run test-chain.nf -c "$cfg" -work-dir "work-${TEST_ID}" --param_c v2 > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 2
    assert_cached_stages 2
    rm -f "$cfg"
}

# ------------------------------------------------------------------
# Test 9: Three-level chain - change middle param
# ------------------------------------------------------------------
test_chain_middle() {
    local cfg; cfg=$(test_config)
    $NXF run test-chain.nf -c "$cfg" -work-dir "work-${TEST_ID}" > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 6

    between_runs
    $NXF run test-chain.nf -c "$cfg" -work-dir "work-${TEST_ID}" --param_b v2 > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 4
    assert_cached_stages 1
    rm -f "$cfg"
}

# ------------------------------------------------------------------
# Test 10: Fan-in stage
# ------------------------------------------------------------------
test_fan_in() {
    local cfg; cfg=$(test_config)
    $NXF run test-fan-in.nf -c "$cfg" -work-dir "work-${TEST_ID}" > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 6

    between_runs
    $NXF run test-fan-in.nf -c "$cfg" -work-dir "work-${TEST_ID}" > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 0
    assert_cached_stages 3
    rm -f "$cfg"
}

# ------------------------------------------------------------------
# Test 11: No-plugin compatibility
# ------------------------------------------------------------------
test_no_plugin() {
    local cfg; cfg=$(noplugin_config)
    $NXF run test-basic.nf -c "$cfg" -work-dir "work-${TEST_ID}" > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 4
    rm -f "$cfg"
}

# ------------------------------------------------------------------
# Test 12: Untracked channel (Channel.of directly passed)
# ------------------------------------------------------------------
test_untracked_channel() {
    local cfg; cfg=$(test_config)
    $NXF run test-untracked-channel.nf -c "$cfg" -work-dir "work-${TEST_ID}" > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 2

    between_runs
    $NXF run test-untracked-channel.nf -c "$cfg" -work-dir "work-${TEST_ID}" > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 0
    assert_cached_stages 1
    rm -f "$cfg"
}

# ------------------------------------------------------------------
# Test 13: Untracked process output passed to named workflow
# ------------------------------------------------------------------
test_untracked_process() {
    local cfg; cfg=$(test_config)
    $NXF run test-untracked-process.nf -c "$cfg" -work-dir "work-${TEST_ID}" > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 3

    between_runs
    $NXF run test-untracked-process.nf -c "$cfg" -work-dir "work-${TEST_ID}" > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 1
    assert_cached_stages 1
    rm -f "$cfg"
}

# ------------------------------------------------------------------
# Test 14: Nested named workflows (workflow calls workflow)
# ------------------------------------------------------------------
test_nested_workflow() {
    local cfg; cfg=$(test_config)
    $NXF run test-nested-workflow.nf -c "$cfg" -work-dir "work-${TEST_ID}" > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 6

    between_runs
    $NXF run test-nested-workflow.nf -c "$cfg" -work-dir "work-${TEST_ID}" > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 0
    rm -f "$cfg"
}

# ------------------------------------------------------------------
# Test 15: Many samples (10 samples)
# ------------------------------------------------------------------
test_many_samples() {
    local cfg; cfg=$(test_config)
    $NXF run test-many-samples.nf -c "$cfg" -work-dir "work-${TEST_ID}" > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 20

    between_runs
    $NXF run test-many-samples.nf -c "$cfg" -work-dir "work-${TEST_ID}" > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 0
    assert_cached_stages 2
    rm -f "$cfg"
}

# ------------------------------------------------------------------
# Test 16: Many samples - partial rerun with param change
# ------------------------------------------------------------------
test_many_samples_partial_rerun() {
    local cfg; cfg=$(test_config)
    $NXF run test-many-samples.nf -c "$cfg" -work-dir "work-${TEST_ID}" > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 20

    between_runs
    $NXF run test-many-samples.nf -c "$cfg" -work-dir "work-${TEST_ID}" --summary_version v2 > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 10
    assert_cached_stages 1
    rm -f "$cfg"
}

# ------------------------------------------------------------------
# Run all tests
# ------------------------------------------------------------------
run_test "basic"               test_basic
run_test "partial-rerun-last"  test_partial_rerun_last
run_test "partial-rerun-mid"   test_partial_rerun_middle
run_test "multi-emit"          test_multi_emit
run_test "value-channel"       test_value_channel
run_test "single-file"         test_single_file
run_test "same-filename"       test_same_filename
run_test "chain-last"          test_chain_last
run_test "chain-middle"        test_chain_middle
run_test "fan-in"              test_fan_in
run_test "no-plugin"           test_no_plugin
run_test "untracked-channel"   test_untracked_channel
run_test "untracked-process"   test_untracked_process
run_test "nested-workflow"     test_nested_workflow
run_test "many-samples"        test_many_samples
run_test "many-samples-rerun"  test_many_samples_partial_rerun

echo ""
echo "================================"
echo "Results: ${PASS} passed, ${FAIL} failed"
echo "================================"

rm -f "$LAST_OUTPUT"
[[ $FAIL -eq 0 ]]
