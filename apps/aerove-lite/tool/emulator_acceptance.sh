#!/usr/bin/env bash
set -euo pipefail

diagnostics="$RUNNER_TEMP/lite-diagnostics"
mkdir -p "$diagnostics"
python3 apps/aerove-lite/tool/fixture_server.py > "$RUNNER_TEMP/lite-fixture.log" 2>&1 &
fixture_pid=$!

finish() {
    result=$?
    trap - EXIT
    timeout 15s adb logcat -d > "$diagnostics/logcat.txt" 2>&1 || true
    timeout 60s adb pull /sdcard/Android/data/dev.aerove.lite/files/captures "$diagnostics/" || true
    python3 apps/aerove-lite/tool/render_gallery.py --captures "$diagnostics/captures" --source "$GITHUB_SHA" \
        --output "$diagnostics/aerove-lite-appearance-12.html" > "$diagnostics/gallery.log" 2>&1 || true
    kill "$fixture_pid" 2>/dev/null || true
    wait "$fixture_pid" 2>/dev/null || true
    exit "$result"
}
trap finish EXIT

curl --fail --silent --show-error --retry 5 --retry-connrefused --retry-delay 1 --max-time 5 \
    http://127.0.0.1:18080/00000000-0000-4000-8000-000000000000 > /dev/null
adb install -r apps/aerove-lite/app/build/outputs/apk/release/app-release.apk
adb shell appops set dev.aerove.lite ACTIVATE_VPN allow
adb shell pm grant dev.aerove.lite android.permission.POST_NOTIFICATIONS
gradle -p apps/aerove-lite --no-daemon :app:connectedReleaseAndroidTest \
    -PliteAbi=x86_64 -PsourceSha="$GITHUB_SHA" \
    -Pandroid.testInstrumentationRunnerArguments.fixtureUrl=http://10.0.2.2:18080 \
    -Pandroid.testInstrumentationRunnerArguments.sourceRevision="$GITHUB_SHA" \
    -Pandroid.testInstrumentationRunnerArguments.deviceKind=CI-emulator \
    -Pandroid.testInstrumentationRunnerArguments.rendererBackend=SwiftShader-indirect
adb pull /sdcard/Android/data/dev.aerove.lite/files/captures "$diagnostics/"
python3 apps/aerove-lite/tool/render_gallery.py --captures "$diagnostics/captures" --source "$GITHUB_SHA" \
    --output "$diagnostics/aerove-lite-appearance-12.html"
