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
serial="${ANDROID_SERIAL:-emulator-5554}"
if [[ ! "$serial" =~ ^emulator-([0-9]+)$ ]]; then
    echo 'Acceptance requires an explicit isolated emulator serial.' >&2
    exit 2
fi
console_port=$((10#${BASH_REMATCH[1]}))
if (( console_port < 1024 || console_port >= 65535 )); then exit 2; fi
adb_device=(adb -s "$serial")
diagnostic_started_at=$(date -u '+%Y-%m-%d %H:%M:%S UTC')
app_apk=apps/aerove-lite/app/build/outputs/apk/release/app-release.apk
test_apk=apps/aerove-lite/app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
mkdir -p "$diagnostics"
test -f "$app_apk"
test -f "$test_apk"
sha256sum "$app_apk" "$test_apk" > "$diagnostics/APK_SHA256SUMS"
python3 - "$diagnostics/SOURCE.json" "$phase" "$test_timeout_msec" "$serial" <<'PY'
import hashlib, json, os, subprocess, sys
sys.path.insert(0, "apps/aerove-lite/tool")
from pathlib import Path
proof_path = os.environ.get("AEROVE_QA79_PROOF")
proof = json.loads(Path(proof_path).read_text()) if proof_path else None
current_path = os.environ.get("AEROVE_CURRENT_RENDERER_PROOF")
if proof_path and current_path:
    raise SystemExit("Mixed original-app and current-run renderer proofs")
current = None
if current_path:
    from emulator_current import validate_renderer_proof
    current = validate_renderer_proof(current_path)
if proof:
    if proof.get("gpuBackend") != "swangle" or proof.get("rendererBackend") != "SwANGLE (ANGLE + SwiftShader)":
        raise SystemExit("Invalid QA renderer proof")
    if (proof.get("sourceRevision") != "8d4bc572aa6b1984bd084e0d72d48b7c6b9f7e8e" or proof.get("sourceTree") != "a8822ba6523696f378659becf8f99121396d93f1" or proof.get("workflowRun") != "40063528837" or proof.get("harnessRevision") != os.environ["GITHUB_SHA"] or proof.get("promotionAccepted") is not False):
        raise SystemExit("Invalid dedicated QA79 original-app proof")
    for name, location in [("app-release.apk", "release"), ("app-release-androidTest.apk", "androidTest/release")]:
        with Path("apps/aerove-lite/app/build/outputs/apk", location, name).open("rb") as stream:
            if hashlib.file_digest(stream, "sha256").hexdigest() != proof["apks"][name]:
                raise SystemExit("QA APK differs from original runtime")
Path(sys.argv[1]).write_text(json.dumps({"sourceRevision": proof["sourceRevision"] if proof else os.environ["GITHUB_SHA"],
    "sourceTree": proof["sourceTree"] if proof else subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], text=True).strip(),
    "appBuildRun": proof["workflowRun"] if proof else os.environ["GITHUB_RUN_ID"],
    "harnessRevision": os.environ["GITHUB_SHA"], "harnessRun": os.environ["GITHUB_RUN_ID"],
    "workflowRun": os.environ.get("GITHUB_RUN_ID"), "runNumber": int(os.environ["GITHUB_RUN_NUMBER"]),
    "runAttempt": int(os.environ["GITHUB_RUN_ATTEMPT"]), "phase": sys.argv[2],
    "testTimeoutMillis": int(sys.argv[3]),
    "deviceKind": "CI-emulator", "rendererBackend": proof["rendererBackend"] if proof else current["rendererBackend"] if current else "SwiftShader-indirect",
    "prebuiltApks": True, "performanceEvidence": False, "deviceSerial": sys.argv[4],
    "isolatedBackgroundPolicy": True}, indent=2), encoding="utf-8")
PY
app_source=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["sourceRevision"])' "$diagnostics/SOURCE.json")
renderer_backend_remote=$(python3 -c 'import json,shlex,sys; print(shlex.quote(json.load(open(sys.argv[1]))["rendererBackend"]))' "$diagnostics/SOURCE.json")
python3 apps/aerove-lite/tool/fixture_server.py > "$diagnostics/fixture.log" 2>&1 &
fixture_pid=$!

owned_logcat_pid=""
qemu_pids=()
cgroup_paths=()

remember_cgroups() {
    local pid="$1" hierarchy controllers relative mount_id parent_id device mount_root mountpoint options rest suffix directory
    while IFS=: read -r hierarchy controllers relative; do
        while read -r mount_id parent_id device mount_root mountpoint options rest; do
            if [[ -z "$controllers" ]]; then
                [[ " $rest " == *" - cgroup2 "* ]] || continue
            else
                [[ ",$controllers," == *,memory,* && " $rest " == *" - cgroup "* && ",${rest##* }," == *,memory,* ]] || continue
            fi
            if [[ "$mount_root" == / ]]; then suffix="$relative"
            elif [[ "$relative" == "$mount_root" || "$relative" == "$mount_root/"* ]]; then suffix="${relative#"$mount_root"}"
            else continue; fi
            directory=$(realpath -m -- "$mountpoint/${suffix#/}") || continue
            if [[ "$directory" == "$mountpoint" || "$directory" == "$mountpoint/"* ]]; then
                cgroup_paths+=("$directory")
            fi
        done < /proc/self/mountinfo
    done < "/proc/$pid/cgroup"
}

while read -r pid; do
    [[ "$pid" =~ ^[0-9]+$ && -r "/proc/$pid/cmdline" ]] || continue
    command_line=$(tr '\0' ' ' < "/proc/$pid/cmdline") || continue
    if [[ " $command_line " == *" -port $console_port "* ]]; then
        qemu_pids+=("$pid")
        remember_cgroups "$pid" || true
    fi
done < <(pgrep -f '/emulator/qemu/|qemu-system-' || true)
{
    if [[ -n "${AEROVE_QA79_PROOF:-}" ]]; then
        printf 'owner=emulator_qa79.py; actual parent returncode/signal and complete stdio in EMULATOR-OWNER.json and emulator.*.log\n'
    elif [[ -n "${AEROVE_CURRENT_RENDERER_PROOF:-}" ]]; then
        printf 'owner=emulator_current.py; actual parent returncode/signal and complete stdio in EMULATOR-OWNER.json and emulator.*.log\n'
    else
        printf 'owner=reactivecircus/android-emulator-runner@a421e43855164a8197daf9d8d40fe71c6996bb0d\n'
    fi
    printf 'acceptance_shell_pid=%s\nserial=%s\n' "$$" "$serial"
    printf 'exit_status=unavailable_non_child; no wait or emulator kill is attempted here\n'
    printf 'live proc fd descriptors are recorded without consuming them; this observer never waits for QEMU\n'
    if (( ${#qemu_pids[@]} == 0 )); then printf 'qemu_discovery=no_matching_process\n'; fi
} > "$diagnostics/qemu-ownership.txt"

host_snapshot() {
    local pid name directory
    date -u '+%Y-%m-%dT%H:%M:%SZ'
    free -m
    df -h "$RUNNER_TEMP"
    pgrep -af '/emulator/qemu|qemu-system|/emulator/emulator' || true
    printf '\nRecorded action-owned QEMU identities (read only):\n'
    for pid in "${qemu_pids[@]}"; do
        ps -o pid,ppid,lstart,stat,rss,args -p "$pid" || true
        for name in stat status cgroup oom_score oom_score_adj; do
            printf '/proc/%s/%s\n' "$pid" "$name"
            cat "/proc/$pid/$name" 2>&1 || true
        done
        for name in 1 2; do printf 'stdio_fd_%s=' "$name"; readlink "/proc/$pid/fd/$name" 2>&1 || true; done
    done
    printf '\nADB/console listening sockets:\n'
    timeout 5s ss -ltnp "sport = :5037 or sport = :$console_port or sport = :$((console_port + 1))" 2>&1 || true
    if (( ${#cgroup_paths[@]} == 0 )); then printf 'memory_cgroup=unresolved_or_unavailable\n'; fi
    for directory in "${cgroup_paths[@]}"; do
        for name in memory.current memory.max memory.peak memory.events memory.events.local memory.pressure memory.usage_in_bytes memory.limit_in_bytes memory.max_usage_in_bytes memory.failcnt memory.oom_control; do
            printf '%s/%s\n' "$directory" "$name"
            if [[ -r "$directory/$name" ]]; then cat "$directory/$name"; else printf 'unavailable_or_permission_denied\n'; fi
        done
    done
}
host_snapshot > "$diagnostics/host-before.txt" 2>&1 || true

stop_owned_child() {
    local pid="$1"
    if [[ -n "$pid" ]]; then
        if jobs -pr | grep -Fxq -- "$pid"; then kill "$pid" 2>/dev/null || true; fi
        wait "$pid" 2>/dev/null || true
    fi
}

finish() {
    result=$?
    trap - EXIT INT TERM
    set +e
    if [[ "$result" != 0 ]]; then
        host_snapshot > "$diagnostics/host-after-failure.txt" 2>&1 || true
        timeout 5s sudo -n dmesg --ctime > "$diagnostics/host-kernel.txt" 2>&1
        printf '\ncollector_exit=%s\n' "$?" >> "$diagnostics/host-kernel.txt"
        timeout 5s sudo -n journalctl -k --since "$diagnostic_started_at" --no-pager > "$diagnostics/host-kernel-window.txt" 2>&1
        printf '\ncollector_exit=%s\n' "$?" >> "$diagnostics/host-kernel-window.txt"
        timeout 5s sudo -n journalctl -u systemd-oomd.service --since "$diagnostic_started_at" --no-pager > "$diagnostics/host-oomd-window.txt" 2>&1
        printf '\ncollector_exit=%s\n' "$?" >> "$diagnostics/host-oomd-window.txt"
    fi
    stop_owned_child "$owned_logcat_pid"
    timeout 15s "${adb_device[@]}" logcat -d > "$diagnostics/logcat.txt" 2>&1 || true
    pull_status=0
    timeout 60s "${adb_device[@]}" pull /sdcard/Android/data/dev.aerove.lite/files/captures "$diagnostics/" || pull_status=$?
    if [[ "$phase" != remaining ]]; then
        if [[ "$result" == 0 && "$pull_status" != 0 ]]; then result=1; fi
        gallery_status=0
        python3 apps/aerove-lite/tool/render_gallery.py --captures "$diagnostics/captures" --source "$app_source" \
            --output "$diagnostics/aerove-lite-appearance-12.html" > "$diagnostics/gallery.log" 2>&1 || gallery_status=$?
        if [[ "$result" == 0 && "$gallery_status" != 0 ]]; then result=1; fi
    fi
    stop_owned_child "$fixture_pid"
    exit "$result"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
"${adb_device[@]}" logcat -v threadtime > "$diagnostics/logcat-live.txt" 2>&1 &
owned_logcat_pid=$!

curl --fail --silent --show-error --retry 5 --retry-connrefused --retry-delay 1 --max-time 5 \
    http://127.0.0.1:18080/00000000-0000-4000-8000-000000000000 > /dev/null
"${adb_device[@]}" install -r "$app_apk"
"${adb_device[@]}" install -r -t "$test_apk"
"${adb_device[@]}" shell appops set dev.aerove.lite ACTIVATE_VPN allow
"${adb_device[@]}" shell pm grant dev.aerove.lite android.permission.POST_NOTIFICATIONS
"${adb_device[@]}" shell pm list instrumentation | tr -d '\r' | tee "$diagnostics/instrumentation-runners.txt"
grep -Fx 'instrumentation:dev.aerove.lite.test/androidx.test.runner.AndroidJUnitRunner (target=dev.aerove.lite)' \
    "$diagnostics/instrumentation-runners.txt"
set +e
"${adb_device[@]}" shell am instrument -w -r -e debug false -e timeout_msec "$test_timeout_msec" "${selection[@]}" \
    -e fixtureUrl http://10.0.2.2:18080 -e sourceRevision "$app_source" \
    -e isolatedBackgroundPolicy true -e deviceKind CI-emulator -e rendererBackend "$renderer_backend_remote" \
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
