#!/usr/bin/env bash
set -euo pipefail

if [ "${AEGIS_BUILD_SLOT_GRANTED:-0}" != "1" ]; then
    printf '%s\n' 'Aegis runner is gated. Set AEGIS_BUILD_SLOT_GRANTED=1 after the parent grants the sole Gradle build slot.' >&2
    exit 2
fi

script_dir=$(cd "$(dirname "$0")" && pwd)
project_dir=$(cd "$script_dir/.." && pwd)
run_id=${AEGIS_RUN_ID:-aegis-$(date -u +%Y%m%dT%H%M%SZ)}
jdk_home=${JAVA_HOME:-/home/sp2ctr2/.cache/latch-toolchain/jdk17}
android_home=${ANDROID_HOME:-/home/sp2ctr2/.cache/latch-toolchain/android-sdk}
export JAVA_HOME="$jdk_home"
export ANDROID_HOME="$android_home"
export ANDROID_SDK_ROOT="$android_home"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

case "$run_id" in
    *[!A-Za-z0-9._-]*)
        printf '%s\n' 'AEGIS_RUN_ID contains unsupported path characters.' >&2
        exit 2
        ;;
esac

artifact_dir=${AEGIS_ARTIFACT_DIR:-$project_dir/benchmark-results/aegis/$run_id}
mkdir -p "$artifact_dir"

hash_files() {
    sha256sum "$@" | sha256sum | cut -d ' ' -f1
}

source_hash=$(hash_files \
    "$project_dir/app/src/test/java/com/tunnelvpn/app/AegisLocalEvaluationTest.kt" \
    "$script_dir/aegis-evaluate.sh")
model_hash=$(hash_files \
    "$project_dir/app/src/main/java/com/tunnelvpn/app/TurboAiEngine.kt" \
    "$project_dir/app/src/main/java/com/tunnelvpn/app/TurboAiModels.kt" \
    "$project_dir/app/src/main/java/com/tunnelvpn/app/TurboModelStore.kt")

export AEGIS_ARTIFACT_DIR="$artifact_dir"
export AEGIS_RUN_ID="$run_id"
export AEGIS_SOURCE_HASH="$source_hash"
export AEGIS_MODEL_HASH="$model_hash"

printf '%s\n' \
    "run_id=$run_id" \
    "artifact_dir=$artifact_dir" \
    "source_hash=$source_hash" \
    "model_hash=$model_hash" \
    'measurement_domain=host-local-jvm-synthetic' \
    'outcome_label=synthetic-known-safe-outcome' \
    'real_network_claims=false' \
    'device_or_emulator=false' \
    'raw_browsing_data=false' \
    > "$artifact_dir/runner-provenance.txt"

gradle_command=(
    "$project_dir/gradlew"
    --offline
    --no-daemon
    --max-workers=1
    --console=plain
    "-Daegis.artifactDir=$artifact_dir"
    "-Daegis.runId=$run_id"
    "-Daegis.sourceHash=$source_hash"
    "-Daegis.modelHash=$model_hash"
    :app:testDebugUnitTest
    lintDebug
    assembleDebug
    assembleRelease
    bundleRelease
)

printf '%q ' "${gradle_command[@]}" > "$artifact_dir/runner-command.txt"
printf '\n' >> "$artifact_dir/runner-command.txt"

cd "$project_dir"
gradle_status=125

write_test_counts() {
    test_results_dir="$project_dir/app/build/test-results"
    json_count=0
    xml_count=0
    if [ -d "$test_results_dir" ]; then
        json_count=$(find "$test_results_dir" -type f -name '*.json' -print | wc -l | tr -d ' ')
        xml_count=$(find "$test_results_dir" -type f -name '*.xml' -print | wc -l | tr -d ' ')
    fi
    printf '{"artifactKind":"test-result-counts","exitCode":%s,"jsonFileCount":%s,"xmlFileCount":%s,"rawConsoleLog":"raw-console.log"}\n' \
        "$gradle_status" "$json_count" "$xml_count" > "$artifact_dir/test-result-counts.json"
}

trap write_test_counts EXIT
set +e
"${gradle_command[@]}" 2>&1 | tee "$artifact_dir/raw-console.log"
gradle_status=${PIPESTATUS[0]}
set -e
exit "$gradle_status"
