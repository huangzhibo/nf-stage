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

cleanup() {
    rm -rf .nextflow .nextflow.log* work .nf-stage-archive cached-stages.tsv 2>/dev/null || true
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
    if [[ ! -f cached-stages.tsv ]]; then
        echo "  ASSERT FAILED: cached-stages.tsv not found"
        return 1
    fi
    local actual
    actual=$(tail -n +2 cached-stages.tsv | wc -l | tr -d ' ')
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
    echo ""
    echo "=== TEST: ${name} ==="
    if eval "$2"; then
        echo "  PASS"
        ((PASS++))
    else
        echo "  FAIL"
        ((FAIL++))
    fi
}

# ------------------------------------------------------------------
# Test 1: Basic archive and restore
# ------------------------------------------------------------------
test_basic() {
    cleanup
    $NXF run test-basic.nf > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 4

    rm -rf .nextflow .nextflow.log* work
    $NXF run test-basic.nf > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 0
    assert_cached_stages 2
}

# ------------------------------------------------------------------
# Test 2: Partial rerun - change last stage param
# ------------------------------------------------------------------
test_partial_rerun_last() {
    cleanup
    $NXF run test-partial-rerun.nf > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 6

    rm -rf .nextflow .nextflow.log* work
    $NXF run test-partial-rerun.nf --dbsnp dbsnp155 > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 2   # only CALL_VARIANTS reruns (2 samples)
    assert_cached_stages 2  # PREPARE + ALIGN cached
}

# ------------------------------------------------------------------
# Test 3: Partial rerun - change middle stage param
# ------------------------------------------------------------------
test_partial_rerun_middle() {
    cleanup
    $NXF run test-partial-rerun.nf > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 6

    rm -rf .nextflow .nextflow.log* work
    $NXF run test-partial-rerun.nf --reference hg19 > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 4   # ALIGN + CALL_VARIANTS rerun (2+2)
    assert_cached_stages 1  # only PREPARE cached
}

# ------------------------------------------------------------------
# Test 4: Multi-emit channels
# ------------------------------------------------------------------
test_multi_emit() {
    cleanup
    $NXF run test-multi-emit.nf > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 4

    rm -rf .nextflow .nextflow.log* work
    $NXF run test-multi-emit.nf > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 0
    assert_cached_stages 1  # QC_AND_TRIM
}

# ------------------------------------------------------------------
# Test 5: Value channel emit
# ------------------------------------------------------------------
test_value_channel() {
    cleanup
    $NXF run test-value-channel.nf > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 2

    rm -rf .nextflow .nextflow.log* work
    $NXF run test-value-channel.nf > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 0
}

# ------------------------------------------------------------------
# Test 6: Single file emit (not tuple)
# ------------------------------------------------------------------
test_single_file() {
    cleanup
    $NXF run test-single-file.nf > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 2

    rm -rf .nextflow .nextflow.log* work
    $NXF run test-single-file.nf > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 0
}

# ------------------------------------------------------------------
# Test 7: Same filename across samples
# ------------------------------------------------------------------
test_same_filename() {
    cleanup
    $NXF run test-same-filename.nf > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 2

    rm -rf .nextflow .nextflow.log* work
    $NXF run test-same-filename.nf > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 0

    # verify both report.txt files exist and are different
    local f1 f2
    f1=$(find .nf-stage-archive -path "*/0/report.txt")
    f2=$(find .nf-stage-archive -path "*/1/report.txt")
    assert_file_exists "$f1"
    assert_file_exists "$f2"
    if diff -q "$f1" "$f2" > /dev/null 2>&1; then
        echo "  ASSERT FAILED: same-name files should have different content"
        return 1
    fi
}

# ------------------------------------------------------------------
# Test 8: Three-level chain - change last param
# ------------------------------------------------------------------
test_chain_last() {
    cleanup
    $NXF run test-chain.nf > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 6

    rm -rf .nextflow .nextflow.log* work
    $NXF run test-chain.nf --param_c v2 > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 2   # only STAGE_C reruns
    assert_cached_stages 2  # STAGE_A + STAGE_B cached
}

# ------------------------------------------------------------------
# Test 9: Three-level chain - change middle param
# ------------------------------------------------------------------
test_chain_middle() {
    cleanup
    $NXF run test-chain.nf > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 6

    rm -rf .nextflow .nextflow.log* work
    $NXF run test-chain.nf --param_b v2 > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 4   # STAGE_B + STAGE_C rerun
    assert_cached_stages 1  # only STAGE_A cached
}

# ------------------------------------------------------------------
# Test 10: Fan-in stage
# ------------------------------------------------------------------
test_fan_in() {
    cleanup
    $NXF run test-fan-in.nf > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 6

    rm -rf .nextflow .nextflow.log* work
    $NXF run test-fan-in.nf > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 0
    assert_cached_stages 3  # PREPARE + QC + MERGE_STAGE
}

# ------------------------------------------------------------------
# Test 11: No-plugin compatibility
# ------------------------------------------------------------------
test_no_plugin() {
    cleanup
    $NXF run test-basic.nf -c nextflow-noplugin.config > "$LAST_OUTPUT" 2>&1 || true
    assert_completed 4
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

echo ""
echo "================================"
echo "Results: ${PASS} passed, ${FAIL} failed"
echo "================================"

cleanup
rm -f "$LAST_OUTPUT"
[[ $FAIL -eq 0 ]]
