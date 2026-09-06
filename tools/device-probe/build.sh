#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
SDK_DIR="${DEVICE_PROBE_ANDROID_SDK:-/home/sp2ctr2/.cache/latch-toolchain/android-sdk}"
JDK_DIR="${DEVICE_PROBE_JDK_HOME:-/home/sp2ctr2/.cache/latch-toolchain/jdk17}"
API_LEVEL="${DEVICE_PROBE_API_LEVEL:-36}"
BUILD_TOOLS_VERSION="${DEVICE_PROBE_BUILD_TOOLS:-36.0.0}"
MIN_SDK="${DEVICE_PROBE_MIN_SDK:-28}"

ANDROID_JAR="$SDK_DIR/platforms/android-$API_LEVEL/android.jar"
BUILD_TOOLS="$SDK_DIR/build-tools/$BUILD_TOOLS_VERSION"
OUT_DIR="$SCRIPT_DIR/build"
CLASSES_DIR="$OUT_DIR/classes"
DEX_DIR="$OUT_DIR/dex"
RES_DIR="$OUT_DIR/res"
UNSIGNED_APK="$OUT_DIR/luna-device-probe-unsigned.apk"
ALIGNED_APK="$OUT_DIR/luna-device-probe-aligned.apk"
APK="$OUT_DIR/luna-device-probe.apk"
KEYSTORE="$SCRIPT_DIR/.android/debug.keystore"
LEGACY_KEYSTORE="$OUT_DIR/debug.keystore"

export JAVA_HOME="$JDK_DIR"
export PATH="$JDK_DIR/bin:$PATH"

for required in "$JDK_DIR/bin/javac" "$JDK_DIR/bin/java" "$JDK_DIR/bin/jar" "$JDK_DIR/bin/keytool" \
    "$ANDROID_JAR" "$BUILD_TOOLS/aapt2" "$BUILD_TOOLS/d8" "$BUILD_TOOLS/zipalign" \
    "$BUILD_TOOLS/apksigner"; do
    if [[ ! -e "$required" ]]; then
        echo "missing toolchain file: $required" >&2
        exit 2
    fi
done

rm -rf -- "$CLASSES_DIR" "$DEX_DIR" "$RES_DIR"
rm -f -- "$UNSIGNED_APK" "$ALIGNED_APK" "$APK"
mkdir -p "$CLASSES_DIR" "$DEX_DIR" "$RES_DIR"
mkdir -p "$(dirname "$KEYSTORE")"

if [[ ! -f "$KEYSTORE" && -f "$LEGACY_KEYSTORE" ]]; then
    cp -- "$LEGACY_KEYSTORE" "$KEYSTORE"
fi

mapfile -t SOURCES < <(find "$SCRIPT_DIR/src" -type f -name '*.java' -print | sort)
if [[ "${#SOURCES[@]}" -eq 0 ]]; then
    echo "no Java sources under $SCRIPT_DIR/src" >&2
    exit 2
fi

"$JDK_DIR/bin/javac" \
    --release 8 \
    -encoding UTF-8 \
    -classpath "$ANDROID_JAR" \
    -d "$CLASSES_DIR" \
    "${SOURCES[@]}"

"$BUILD_TOOLS/d8" \
    --lib "$ANDROID_JAR" \
    --min-api "$MIN_SDK" \
    --output "$DEX_DIR" \
    $(find "$CLASSES_DIR" -type f -name '*.class' -print | sort)

"$BUILD_TOOLS/aapt2" link \
    --manifest "$SCRIPT_DIR/AndroidManifest.xml" \
    -I "$ANDROID_JAR" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$API_LEVEL" \
    --version-code 1 \
    --version-name 1.0 \
    -o "$UNSIGNED_APK"

"$JDK_DIR/bin/jar" uf "$UNSIGNED_APK" -C "$DEX_DIR" classes.dex

if [[ ! -f "$KEYSTORE" ]]; then
    "$JDK_DIR/bin/keytool" -genkeypair \
        -keystore "$KEYSTORE" \
        -storepass android \
        -keypass android \
        -alias androiddebugkey \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" \
        -noprompt >/dev/null 2>&1
fi

"$BUILD_TOOLS/zipalign" -f -P 16 4 "$UNSIGNED_APK" "$ALIGNED_APK"
"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$APK" \
    "$ALIGNED_APK"
"$BUILD_TOOLS/apksigner" verify --verbose "$APK"
"$BUILD_TOOLS/zipalign" -c -P 16 -v 4 "$APK"

echo "built: $APK"
echo "package: com.luna.deviceprobe"
