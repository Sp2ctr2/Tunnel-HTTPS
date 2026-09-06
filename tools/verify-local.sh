#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TOOLCHAIN_ROOT="${TUNNEL_HTTPS_TOOLCHAIN_ROOT:-/home/sp2ctr2/.cache/latch-toolchain}"
FALLBACK_JAVA_HOME="$TOOLCHAIN_ROOT/jdk17"
FALLBACK_ANDROID_HOME="$TOOLCHAIN_ROOT/android-sdk"
MIN_AVAILABLE_KB=$((3 * 1024 * 1024))
RERUN_ARGS=()

usage() {
    cat <<'EOF'
Usage: ./tools/verify-local.sh [--rerun-tasks]

Runs the local JVM tests, lint, and debug/release assembly with one Gradle worker.
Gradle tasks are incremental by default; pass --rerun-tasks only when explicitly needed.
EOF
}

for arg in "$@"; do
    case "$arg" in
        --rerun-tasks)
            RERUN_ARGS+=("$arg")
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "unknown option" >&2
            usage >&2
            exit 2
            ;;
    esac
done

resolve_java_home() {
    local supplied="${JAVA_HOME:-}"
    if [[ -n "$supplied" && -x "$supplied/bin/java" ]]; then
        printf '%s\n' "$supplied"
    elif [[ -x "$FALLBACK_JAVA_HOME/bin/java" ]]; then
        printf '%s\n' "$FALLBACK_JAVA_HOME"
    else
        return 1
    fi
}

resolve_android_home() {
    local supplied="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [[ -n "$supplied" && -x "$supplied/platform-tools/adb" && -d "$supplied/platforms" ]]; then
        printf '%s\n' "$supplied"
    elif [[ -x "$FALLBACK_ANDROID_HOME/platform-tools/adb" && -d "$FALLBACK_ANDROID_HOME/platforms" ]]; then
        printf '%s\n' "$FALLBACK_ANDROID_HOME"
    else
        return 1
    fi
}

read_mem_available_kb() {
    awk '$1 == "MemAvailable:" { print $2; exit }' /proc/meminfo 2>/dev/null || true
}

MEM_AVAILABLE_KB="$(read_mem_available_kb)"
if [[ ! "$MEM_AVAILABLE_KB" =~ ^[0-9]+$ || "$MEM_AVAILABLE_KB" -lt "$MIN_AVAILABLE_KB" ]]; then
    echo "insufficient MemAvailable; need at least 3 GiB" >&2
    exit 3
fi

if ! JAVA_HOME="$(resolve_java_home)"; then
    echo "JDK 17 is unavailable" >&2
    exit 2
fi
if ! ANDROID_HOME="$(resolve_android_home)"; then
    echo "Android SDK is unavailable" >&2
    exit 2
fi

export JAVA_HOME ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:${PATH:-}"

if [[ ! -x "$PROJECT_DIR/gradlew" ]]; then
    echo "Gradle wrapper is unavailable" >&2
    exit 2
fi
if [[ ! -f "$ANDROID_HOME/platforms/android-36/android.jar" || ! -d "$ANDROID_HOME/build-tools" ]]; then
    echo "Android SDK compile tools for this project are unavailable" >&2
    exit 2
fi

cd "$PROJECT_DIR"
exec ./gradlew \
    --no-daemon \
    --max-workers=1 \
    --console=plain \
    "${RERUN_ARGS[@]}" \
    testDebugUnitTest \
    lintDebug \
    assembleDebug \
    assembleRelease
