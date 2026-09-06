#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="${DEVICE_PROBE_ANDROID_SDK:-/home/sp2ctr2/.cache/latch-toolchain/android-sdk}"
ADB="${DEVICE_PROBE_ADB:-$SDK_DIR/platform-tools/adb}"
SERIAL="${DEVICE_PROBE_SERIAL:-emulator-5554}"
APK="$SCRIPT_DIR/build/luna-device-probe.apk"
PACKAGE="com.luna.deviceprobe"
ACTIVITY="$PACKAGE/.DeviceProbeActivity"

if [[ ! -x "$ADB" ]]; then
    echo "adb not found: $ADB" >&2
    exit 2
fi
if [[ ! -f "$APK" ]]; then
    echo "APK missing; run ./build.sh first: $APK" >&2
    exit 2
fi

usage() {
    cat <<'EOF'
Usage:
  ./run.sh install
  ./run.sh launch [am-start-arguments...]
  ./run.sh logcat
  ./run.sh stop

Examples (the fixture is already running on the host):
  ./run.sh install
  ./run.sh launch --es probe http --ei fixture-size 65536
  ./run.sh launch --es probe tcp4 --es tcp-family 4 --ei fixture-size 65536
  ./run.sh launch --es probe udp4 --es udp-family 4 --ei fixture-size 4096
  ./run.sh launch --es probe http --es http-url 'http://[fec0::2]:18080/bytes?size={size}' --ei fixture-size 65536
  ./run.sh launch --es probe tcp6 --es tcp-host fec0::2 --es tcp-family 6 --ei fixture-size 65536
  ./run.sh launch --es probe udp6 --es udp-host fec0::2 --es udp-family 6 --ei fixture-size 4096
  ./run.sh launch --es run-id dns-wire-nxdomain --es probe dns-wire --es dns-wire-name ads.example --ei expected-rcode 3
  ./run.sh launch --es run-id dns-wire-servfail --es probe dns-wire --es dns-wire-name doh-fail.example --ei expected-rcode 2
  ./run.sh launch --es run-id tcp-fin --es probe tcp4 --es tcp-family 4 --ei fixture-size 4096 --ez tcp-shutdown-output true
  ./run.sh launch --es run-id tcp-pressure --es probe tcp4 --es tcp-family 4 --ei fixture-size 1048576 --ez tcp-slow-read true --ez tcp-shutdown-output true --ei slow-read-delay-ms 2 --ei slow-read-chunk-bytes 1024 --ei timeout-ms 10000
  ./run.sh launch --es probe dns --es dns-host example.com --ez allow-public true
  ./run.sh launch --es probe http --es http-url https://example.com/ --ez allow-public true
  ./run.sh logcat

No command in this script changes VPN configuration. Public probes require the explicit
--ez allow-public true flag. Network arguments are passed to an exported activity.
EOF
}

case "${1:-}" in
    install)
        exec "$ADB" -s "$SERIAL" install -r "$APK"
        ;;
    launch)
        shift
        if [[ "$#" -eq 0 ]]; then
            echo "launch requires am-start arguments; see ./run.sh usage" >&2
            exit 2
        fi
        exec "$ADB" -s "$SERIAL" shell am start -S -W -n "$ACTIVITY" "$@"
        ;;
    logcat)
        exec "$ADB" -s "$SERIAL" logcat -v threadtime -s "LunaDeviceProbe:I" "*:S"
        ;;
    stop)
        exec "$ADB" -s "$SERIAL" shell am force-stop "$PACKAGE"
        ;;
    usage|help|"")
        usage
        ;;
    *)
        echo "unknown command: $1" >&2
        usage >&2
        exit 2
        ;;
esac
