#!/usr/bin/env bash
# Verify the exact signed beta in an isolated Android emulator. No physical-device claims.
set -euo pipefail
mkdir -p publication-results
: "${ANDROID_HOME:?Android SDK is required}"
: "${RUNNER_TEMP:?Runner temporary directory is required}"
APK="$RUNNER_TEMP/beta-public/TunnelHTTPS-0.9.0-beta.1.apk"
EMULATOR_PID=''
cleanup() {
  status=$?
  if [ "$status" -ne 0 ]; then
    echo 'Emulator/install verification failed. Recent infrastructure diagnostics:'
    tail -n 100 publication-results/emulator.log 2>/dev/null || true
    tail -n 30 publication-results/sdk-install.log 2>/dev/null || true
    adb devices -l || true
  fi
  if [ -n "$EMULATOR_PID" ]; then kill "$EMULATOR_PID" 2>/dev/null || true; fi
  exit "$status"
}
trap cleanup EXIT
sdkmanager 'build-tools;36.0.0' 'platform-tools' 'emulator' 'system-images;android-35;google_apis;x86_64' > publication-results/sdk-install.log 2>&1
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs "$APK" | tee publication-results/signature.txt
grep -q 'fbd8c2508d81843ff6f7cdaaba62e85c935fd8a0e1e8aac465a29b7b12a16e46' publication-results/signature.txt
"$ANDROID_HOME/build-tools/36.0.0/aapt" dump badging "$APK" > publication-results/package.txt
grep -q "package: name='com.tunnelvpn.app' versionCode='11' versionName='0.9.0-beta.1'" publication-results/package.txt
if grep -q 'application-debuggable' publication-results/package.txt; then exit 1; fi
export ANDROID_AVD_HOME="$RUNNER_TEMP/publication-avd"
mkdir -p "$ANDROID_AVD_HOME"
printf 'no\n' | avdmanager create avd --force --name publication --package 'system-images;android-35;google_apis;x86_64' --device pixel_2 > publication-results/avd-create.log 2>&1
ls -la "$ANDROID_AVD_HOME" > publication-results/avd-paths.txt
test -e /dev/kvm
sudo chmod a+rw /dev/kvm
"$ANDROID_HOME/emulator/emulator" -accel-check > publication-results/acceleration.txt 2>&1
export QT_QPA_PLATFORM=offscreen
"$ANDROID_HOME/emulator/emulator" -avd publication -port 5554 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048 -cores 2 -no-metrics > publication-results/emulator.log 2>&1 &
EMULATOR_PID=$!
adb start-server
ready=0
for i in $(seq 1 180); do
  if ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
    echo 'Emulator exited before Android finished booting.'
    exit 1
  fi
  booted=$(timeout 5 adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
  if [ "$booted" = '1' ]; then ready=1; break; fi
  sleep 2
done
test "$ready" = '1'
ADB=(adb -s emulator-5554)
"${ADB[@]}" shell input keyevent 82
"${ADB[@]}" install -r "$APK" | tee publication-results/install.txt
grep -q 'Success' publication-results/install.txt
ACTIVITY=$("${ADB[@]}" shell cmd package resolve-activity --brief com.tunnelvpn.app | tr -d '\r' | tail -n 1)
case "$ACTIVITY" in com.tunnelvpn.app/*) ;; *) echo 'Launcher activity was not resolved'; exit 1 ;; esac
for i in 1 2 3; do
  "${ADB[@]}" shell am force-stop com.tunnelvpn.app
  "${ADB[@]}" shell am start -W -n "$ACTIVITY" > "publication-results/launch-$i.txt"
  grep -q 'Status: ok' "publication-results/launch-$i.txt"
  sleep 5
  "${ADB[@]}" shell pidof com.tunnelvpn.app | grep -q '[0-9]'
done
"${ADB[@]}" exec-out screencap -p > publication-results/android15-initial-screen.png
printf 'PASS: exact signed beta installed; three cold launches remained running.\nAndroid 15/API 35 x86_64 emulator, not physical hardware.\nNo VPN traffic, carrier, handover or long-run certification is inferred.\n' > publication-results/installation-smoke.txt
