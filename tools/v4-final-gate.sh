#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
project_dir=$(cd "$script_dir/.." && pwd)
artifact_dir=${1:-"$project_dir/benchmark-results/v4-final-gate"}
if [[ "$artifact_dir" != /* ]]; then artifact_dir="$project_dir/$artifact_dir"; fi
available_kb=$(awk '$1 == "MemAvailable:" {print $2; exit}' /proc/meminfo)
if [[ ! "$available_kb" =~ ^[0-9]+$ || "$available_kb" -lt 3145728 ]]; then
    echo 'Need at least 3 GiB available memory for the sole Gradle worker.' >&2
    exit 3
fi
mkdir -p "$artifact_dir/b3" "$artifact_dir/b4"
export JAVA_HOME=/home/sp2ctr2/.cache/latch-toolchain/jdk17
export ANDROID_HOME=/home/sp2ctr2/.cache/latch-toolchain/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
export V4_ARTIFACT_DIR="$artifact_dir/b3"
export AEGIS_V4_ARTIFACT_DIR="$artifact_dir/b4"
cd "$project_dir"
set +e
./gradlew --offline --no-daemon --max-workers=1 --console=plain \
    testDebugUnitTest lintDebug assembleDebug assembleRelease bundleRelease \
    2>&1 | tee "$artifact_dir/raw-console.log"
gate_status=${PIPESTATUS[0]}
set -e
if [[ "$gate_status" -ne 0 ]]; then exit "$gate_status"; fi
sha256sum app/build/outputs/apk/debug/app-debug.apk \
    app/build/outputs/apk/release/app-release-unsigned.apk \
    app/build/outputs/bundle/release/app-release.aab \
    | tee "$artifact_dir/artifact-sha256.txt"
