#!/usr/bin/env bash
set -euo pipefail

phase="${1:-all}"
matrix_class=dev.aerove.lite.ui.LiteDashboardMatrixTest
selection=()
test_timeout_msec=120000
case "$phase" in
    matrix) selection=(-e class "$matrix_class"); test_timeout_msec=600000 ;;
    remaining) selection=(-e notClass "$matrix_class") ;;
    all) ;;
    *) echo "Unknown instrumentation phase: $phase" >&2; exit 2 ;;
esac

diagnostics="$RUNNER_TEMP/lite-diagnostics/$phase"
app_apk=apps/aerove-lite/app/build/outputs/apk/release/app-release.apk
test_apk=apps/aerove-lite/app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
mkdir -p "$diagnostics"
test -f "$app_apk"
test -f "$test_apk"
sha256sum "$app_apk" "$test_apk" > "$diagnostics/APK_SHA256SUMS"
python3 - "$diagnostics/SOURCE.json" "$phase" "$test_timeout_msec" <<'PY'
import json, os, subprocess, sys
from pathlib import Path
Path(sys.argv[1]).write_text(json.dumps({"sourceRevision": os.environ["GITHUB_SHA"],
    "sourceTree": subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], text=True).strip(),
    "workflowRun": os.environ.get("GITHUB_RUN_ID"), "runNumber": int(os.environ["GITHUB_RUN_NUMBER"]),
    "runAttempt": int(os.environ["GITHUB_RUN_ATTEMPT"]), "phase": sys.argv[2],
    "testTimeoutMillis": int(sys.argv[3]),
    "deviceKind": "CI-emulator", "rendererBackend": "SwiftShader-indirect",
    "prebuiltApks": True, "performanceEvidence": False}, indent=2), encoding="utf-8")
PY
python3 apps/aerove-lite/tool/fixture_server.py > "$diagnostics/fixture.log" 2>&1 &
fixture_pid=$!

host_snapshot() {
    date -u '+%Y-%m-%dT%H:%M:%SZ'
    free -m
    df -h "$RUNNER_TEMP"
    pgrep -af '/emulator/qemu|qemu-system|/emulator/emulator' || true
    for events in /sys/fs/cgroup/memory.events /sys/fs/cgroup/memory/memory.failcnt; do
        if [[ -r "$events" ]]; then printf '%s\n' "$events"; cat "$events"; fi
    done
}
host_snapshot > "$diagnostics/host-before.txt" 2>&1 || true

finish() {
    result=$?
    trap - EXIT
    if [[ "$result" != 0 ]]; then
        host_snapshot > "$diagnostics/host-after-failure.txt" 2>&1 || true
        timeout 5s sudo -n dmesg --ctime --level=err,warn > "$diagnostics/host-kernel.txt" 2>&1 || true
    fi
    timeout 15s adb logcat -d > "$diagnostics/logcat.txt" 2>&1 || true
    pull_status=0
    timeout 60s adb pull /sdcard/Android/data/dev.aerove.lite/files/captures "$diagnostics/" || pull_status=$?
    if [[ "$phase" != remaining ]]; then
        if [[ "$result" == 0 && "$pull_status" != 0 ]]; then result=1; fi
        gallery_status=0
        python3 apps/aerove-lite/tool/render_gallery.py --captures "$diagnostics/captures" --source "$GITHUB_SHA" \
            --output "$diagnostics/aerove-lite-appearance-12.html" > "$diagnostics/gallery.log" 2>&1 || gallery_status=$?
        if [[ "$result" == 0 && "$gallery_status" != 0 ]]; then result=1; fi
    fi
    kill "$fixture_pid" 2>/dev/null || true
    wait "$fixture_pid" 2>/dev/null || true
    exit "$result"
}
trap finish EXIT

curl --fail --silent --show-error --retry 5 --retry-connrefused --retry-delay 1 --max-time 5 \
    http://127.0.0.1:18080/00000000-0000-4000-8000-000000000000 > /dev/null
adb install -r "$app_apk"
adb install -r -t "$test_apk"
adb shell appops set dev.aerove.lite ACTIVATE_VPN allow
adb shell pm grant dev.aerove.lite android.permission.POST_NOTIFICATIONS
adb shell pm list instrumentation | tr -d '\r' | tee "$diagnostics/instrumentation-runners.txt"
grep -Fx 'instrumentation:dev.aerove.lite.test/androidx.test.runner.AndroidJUnitRunner (target=dev.aerove.lite)' \
    "$diagnostics/instrumentation-runners.txt"
set +e
adb shell am instrument -w -r -e debug false -e timeout_msec "$test_timeout_msec" "${selection[@]}" \
    -e fixtureUrl http://10.0.2.2:18080 -e sourceRevision "$GITHUB_SHA" \
    -e deviceKind CI-emulator -e rendererBackend SwiftShader-indirect \
    dev.aerove.lite.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$diagnostics/instrumentation.log"
pipeline_status=("${PIPESTATUS[@]}")
set -e
if [[ "${pipeline_status[0]}" != 0 ]]; then exit "${pipeline_status[0]}"; fi
if [[ "${pipeline_status[1]}" != 0 ]]; then exit "${pipeline_status[1]}"; fi
python3 - "$diagnostics/instrumentation.log" "$phase" "$matrix_class" <<'PY'
import re, sys
from pathlib import Path
text = Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
codes = re.findall(r"^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$", text, re.MULTILINE)
failed = re.search(r"INSTRUMENTATION_(?:FAILED|ABORTED)|FAILURES!!!|^INSTRUMENTATION_STATUS_CODE:\s*-(?:1|2)\s*$", text, re.MULTILINE)
summary = re.findall(r"OK \((\d+) tests?\)", text)
classes = set(re.findall(r"^INSTRUMENTATION_STATUS: class=([^\r\n]+)", text, re.MULTILINE))
if codes != ["-1"] or failed or len(summary) != 1 or int(summary[0]) < 1:
    raise SystemExit("Instrumentation did not report a complete passing test run; see instrumentation.log")
if sys.argv[2] == "matrix" and (summary != ["1"] or classes != {sys.argv[3]}):
    raise SystemExit("The dashboard matrix phase must run exactly its single matrix test class")
if sys.argv[2] == "remaining" and sys.argv[3] in classes:
    raise SystemExit("The remaining phase unexpectedly executed the excluded dashboard matrix class")
PY
