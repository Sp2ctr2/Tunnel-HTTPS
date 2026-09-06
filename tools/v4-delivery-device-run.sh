#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
project_dir=$(cd "$script_dir/.." && pwd)
cd "$project_dir"
artifact_dir="$project_dir/benchmark-results/v4-delivery-device"
adb=/home/sp2ctr2/.cache/latch-toolchain/android-sdk/platform-tools/adb
mkdir -p "$artifact_dir"
debug_hash=$(sha256sum app/build/outputs/apk/debug/app-debug.apk | awk '{print $1}')
"$adb" -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
node tools/v4-device-configure.mjs com.tunnelvpn.app.turbo shadow 32768 turbo "$artifact_dir/shadow-setup"
node tools/webview_eval.mjs '({ai:JSON.parse(TunnelAndroid.getTurboAiStatus()),state:JSON.parse(TunnelAndroid.getCurrentStateSnapshot()),turbo:JSON.parse(TunnelAndroid.getTurboState())})' | tee "$artifact_dir/shadow-before-tls.json"
node tools/v4-device-probes.mjs tls "$artifact_dir/tls"
node tools/webview_eval.mjs '({ai:JSON.parse(TunnelAndroid.getTurboAiStatus()),state:JSON.parse(TunnelAndroid.getCurrentStateSnapshot()),turbo:JSON.parse(TunnelAndroid.getTurboState())})' | tee "$artifact_dir/shadow-after-tls.json"
node -e 'const s=require(process.argv[1]).result.value.ai;if(s.aegisMode!=="AI_SHADOW"||s.aegisObservations<1)throw Error("Neural readiness gate failed");' "$artifact_dir/shadow-after-tls.json"
for profile in shadow off normal; do
    if [[ "$profile" == off ]]; then
        node tools/v4-device-configure.mjs com.tunnelvpn.app.turbo off 32768 turbo "$artifact_dir/off-setup"
    elif [[ "$profile" == normal ]]; then
        node tools/v4-device-configure.mjs com.tunnelvpn.app.turbo shadow 32768 normal "$artifact_dir/normal-setup"
    fi
    python3 tools/benchmark-runner.py throughput --mode next --expected-production-sha256 "$debug_hash" --output-dir "$artifact_dir/$profile-b1" >/dev/null
    python3 tools/benchmark-runner.py concurrency --mode next --expected-production-sha256 "$debug_hash" --output-dir "$artifact_dir/$profile-b2" >/dev/null
    node tools/webview_eval.mjs '({ai:JSON.parse(TunnelAndroid.getTurboAiStatus()),state:JSON.parse(TunnelAndroid.getCurrentStateSnapshot()),turbo:JSON.parse(TunnelAndroid.getTurboState()),traffic:JSON.parse(TunnelAndroid.getTrafficStats())})' | tee "$artifact_dir/$profile-after-bulk.json"
done
node tools/v4-device-configure.mjs com.tunnelvpn.app.turbo shadow 32768 turbo "$artifact_dir/b5-setup"
node tools/device-lifecycle-test.mjs --cycles 5 --output "$artifact_dir/b5-lifecycle.json"
node tools/device-lifecycle-test.mjs --cycles 5 --burst --output "$artifact_dir/b5-burst.json"
