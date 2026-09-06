#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
artifact_dir="${1:-$project_dir/benchmark-results/aegis-local2/$(date -u +%Y%m%dT%H%M%SZ)}"
if [[ "$artifact_dir" != /* ]]; then artifact_dir="$project_dir/$artifact_dir"; fi
mkdir -p "$artifact_dir"
cd "$project_dir"
export AEGIS_V4_ARTIFACT_DIR="$artifact_dir"

gradle_properties=("-Daegis.v4.artifactDir=$artifact_dir")
if [[ -n "${AEGIS_V4_D_ADAPTER_CLASS:-}" ]]; then
    gradle_properties+=("-Daegis.v4.d.adapterClass=${AEGIS_V4_D_ADAPTER_CLASS}")
fi
if [[ -n "${AEGIS_V4_D_MODE:-}" ]]; then
    gradle_properties+=("-Daegis.v4.d.mode=${AEGIS_V4_D_MODE}")
fi

./gradlew "${gradle_properties[@]}" :app:testDebugUnitTest --tests com.tunnelvpn.app.AegisLocal2EvaluationTest --no-daemon --max-workers=1
python3 tools/v4-aegis-local2-summarize.py "$artifact_dir"
