#!/usr/bin/env bash
set -euo pipefail

phase="${1:-all}"
matrix_class=dev.aerove.lite.ui.LiteDashboardMatrixTest
selection=()
test_timeout_msec=120000
case "$phase" in
    matrix) selection=(-e class "$matrix_class"); test_timeout_msec=600000 ;;
    remaining) selection=(-e notClass "$matrix_class") ;;
    original-sample)
        [[ "${2:-}" == "${AEROVE_ORIGINAL_SAMPLE:?One original sample required}" ]] || exit 2
        [[ "$AEROVE_ORIGINAL_SAMPLE" =~ ^(glyph|solid|clear)$ ]] || exit 2
        ;;
    all) ;;
    *) echo "Unknown instrumentation phase: $phase" >&2; exit 2 ;;
esac

shard_count="${AEROVE_REMAINING_SHARD_COUNT:-}"
shard_index="${AEROVE_REMAINING_SHARD_INDEX:-}"
remaining_timeout=()
if [[ "$phase" == original-sample ]]; then remaining_timeout=(timeout --signal=TERM --kill-after=20s 240s); fi
if [[ -v AEROVE_REMAINING_SHARD_COUNT || -v AEROVE_REMAINING_SHARD_INDEX ]]; then
    if [[ "$phase" != remaining || ! "$shard_count" =~ ^(8|16)$ || ! "$shard_index" =~ ^([0-9]|1[0-5])$ ]] ||
            (( 10#$shard_index >= shard_count )); then
        echo 'Sharding requires remaining, count=8 or 16 and one index in 0..count-1; omit both variables for an unsharded run.' >&2
        exit 2
    fi
    selection+=(-e numShards "$shard_count" -e shardIndex "$shard_index")
    remaining_timeout=(timeout --signal=TERM --kill-after=20s 1200s)
fi

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

write_result_index() {
    [[ "$phase" == remaining && -n "$shard_count" ]] || return 0
    timeout 15s python3 apps/aerove-lite/tool/instrumentation_results.py \
        --source "$diagnostics/SOURCE.json" --discovery "$diagnostics/TEST-DISCOVERY.json" \
        --discovery-log "$diagnostics/discovery.log" --sums "$diagnostics/APK_SHA256SUMS" \
        --ordinary-log "$diagnostics/instrumentation.log" \
        --isolated-log "$diagnostics/ssid-device/instrumentation.log" --host-exit-code "$1" \
        --output "$diagnostics/TEST-RESULT-INDEX.json" --markdown "$diagnostics/TEST-RESULT-INDEX.md" \
        > "$diagnostics/result-index.log" 2>&1 || true
    cat "$diagnostics/result-index.log" || true
}
trap 'early_result=$?; trap - EXIT; write_result_index "$early_result"; exit "$early_result"' EXIT
test -f "$app_apk"
test -f "$test_apk"
sha256sum "$app_apk" "$test_apk" > "$diagnostics/APK_SHA256SUMS"
python3 - "$diagnostics/SOURCE.json" "$phase" "$test_timeout_msec" "$serial" "$shard_count" "$shard_index" "$matrix_class" <<'PY'
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
original = current["originalAppIdentity"] if current and (current.get("diagnosticOriginal84") is True or current.get("diagnosticOriginalRuntime") is True) else None
if (os.environ.get("AEROVE_ORIGINAL84_PROOF") or os.environ.get("AEROVE_ORIGINAL_RUNTIME_PROOF")) and original is None:
    raise SystemExit("Original84 diagnostic requires its verified renderer proof")
if proof:
    if proof.get("gpuBackend") != "swangle" or proof.get("rendererBackend") != "SwANGLE (ANGLE + SwiftShader)":
        raise SystemExit("Invalid QA renderer proof")
    if (proof.get("sourceRevision") != "8d4bc572aa6b1984bd084e0d72d48b7c6b9f7e8e" or proof.get("sourceTree") != "a8822ba6523696f378659becf8f99121396d93f1" or proof.get("workflowRun") != "40063528837" or proof.get("harnessRevision") != os.environ["GITHUB_SHA"] or proof.get("promotionAccepted") is not False):
        raise SystemExit("Invalid dedicated QA79 original-app proof")
    for name, location in [("app-release.apk", "release"), ("app-release-androidTest.apk", "androidTest/release")]:
        with Path("apps/aerove-lite/app/build/outputs/apk", location, name).open("rb") as stream:
            if hashlib.file_digest(stream, "sha256").hexdigest() != proof["apks"][name]:
                raise SystemExit("QA APK differs from original runtime")
source = {"sourceRevision": proof["sourceRevision"] if proof else original["sourceRevision"] if original else os.environ["GITHUB_SHA"],
    "sourceTree": proof["sourceTree"] if proof else original["sourceTree"] if original else subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], text=True).strip(),
    "appBuildRun": proof["workflowRun"] if proof else original["workflowRun"] if original else os.environ["GITHUB_RUN_ID"],
    "harnessRevision": os.environ["GITHUB_SHA"], "harnessRun": os.environ["GITHUB_RUN_ID"],
    "workflowRun": os.environ.get("GITHUB_RUN_ID"), "runNumber": int(os.environ["GITHUB_RUN_NUMBER"]),
    "runAttempt": int(os.environ["GITHUB_RUN_ATTEMPT"]), "phase": sys.argv[2],
    "testTimeoutMillis": int(sys.argv[3]),
    "deviceKind": "CI-emulator", "rendererBackend": proof["rendererBackend"] if proof else current["rendererBackend"] if current else "SwiftShader-indirect",
    "prebuiltApks": True, "performanceEvidence": False, "deviceSerial": sys.argv[4],
    "isolatedBackgroundPolicy": True,
    "testShard": {"numShards": int(sys.argv[5]), "shardIndex": int(sys.argv[6]),
                  "runner": "androidx.test.runner.AndroidJUnitRunner", "excludedClass": sys.argv[7],
                  "hostInstrumentationDeadlineSeconds": 1200, "discoveryDeadlineSeconds": 120} if sys.argv[5] else None}
if original:
    source.update(originalAppIdentity=original, promotionAccepted=False)
    if current.get("diagnosticOriginal84") is True:
        source["diagnosticOriginal84"] = True
    else:
        source.update(diagnosticOriginalRuntime=True, runtimeTarget=current["runtimeTarget"],
                      sourceAppRevision=original["sourceRevision"],
                      harnessIdentity=current["harnessIdentity"])
        if current["runtimeTarget"] == "lite141-prebuilt-shards":
            if sys.argv[2] != "remaining":
                raise SystemExit("The Lite141 prebuilt diagnostic only permits the original remaining shards")
            harness_identity = dict(sourceRevision=os.environ["GITHUB_SHA"],
                sourceTree=subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], text=True).strip(),
                workflowRun=os.environ["GITHUB_RUN_ID"], runNumber=int(os.environ["GITHUB_RUN_NUMBER"]),
                runAttempt=int(os.environ["GITHUB_RUN_ATTEMPT"]))
            proof_file = Path(os.environ["AEROVE_ORIGINAL_RUNTIME_PROOF"])
            original_proof_bytes = proof_file.read_bytes()
            original_proof = json.loads(original_proof_bytes)
            expected_flags = dict(diagnosticOriginalRuntime=True, runtimeTarget="lite141-prebuilt-shards",
                diagnosticOnly=True, accepted=False, promotionAccepted=False, aggregateAcceptance=False)
            if (original_proof.get("originalAppIdentity") != original
                    or original_proof.get("harnessIdentity") != harness_identity
                    or any(original_proof.get(key) != value for key, value in expected_flags.items())):
                raise SystemExit("Lite141 original runtime proof differs from this harness or diagnostic-only contract")
            runtime_directory = Path(current["runtimeDirectory"])
            manifest_path = runtime_directory / "source-manifest.json"
            if manifest_path.is_symlink():
                raise SystemExit("Refusing a symlinked original runtime source manifest")
            manifest_bytes = manifest_path.read_bytes()
            manifest_sha = hashlib.sha256(manifest_bytes).hexdigest()
            if manifest_sha != current["runtimeManifestSha256"]:
                raise SystemExit("Original runtime manifest changed after renderer proof")
            dependency_relative = "dependencies/releaseAndroidTestRuntimeClasspath.stdout.txt"
            dependency_path = runtime_directory / dependency_relative
            if dependency_path.is_symlink():
                raise SystemExit("Refusing a symlinked original AndroidTest dependency report")
            dependency_bytes = dependency_path.read_bytes()
            manifest = json.loads(manifest_bytes)
            dependency_entries = [entry for entry in manifest["files"] if entry["path"] == dependency_relative]
            dependency_sha = hashlib.sha256(dependency_bytes).hexdigest()
            if len(dependency_entries) != 1 or dependency_entries[0]["sha256"] != dependency_sha:
                raise SystemExit("Original AndroidTest dependency report differs from runtime manifest")
            evidence_directory = Path(sys.argv[1]).parent
            evidence_directory.joinpath("ORIGINAL-RUNTIME-APP-PROOF.json").write_bytes(original_proof_bytes)
            evidence_directory.joinpath("runtime-source-manifest.json").write_bytes(manifest_bytes)
            evidence_directory.joinpath("runtime-androidTest-dependencies.txt").write_bytes(dependency_bytes)
            source.update(workflowRun=original["workflowRun"], runNumber=original["runNumber"],
                runAttempt=original["runAttempt"], harnessIdentity=harness_identity,
                originalRuntimeProofSha256=hashlib.sha256(original_proof_bytes).hexdigest(),
                runtimeArtifactId=original_proof["artifactId"],
                runtimeArtifactZipSha256=original_proof["artifactZipSha256"],
                runtimeManifestSha256=manifest_sha, apks=original_proof["apks"],
                androidTestDependencyReportSha256=dependency_sha,
                diagnosticOnly=True, accepted=False, aggregateAcceptance=False)
if sys.argv[2] == "original-sample":
    if original is None or current.get("runtimeTarget") != "lite125-original-samples":
        raise ValueError("Original sample requires the approved immutable run125 runtime")
    from emulator_original84 import runtime_spec
    from original_sample import context
    source.update(context(runtime_spec("lite125-original-samples"), current,
                          os.environ["AEROVE_ORIGINAL_SAMPLE"], os.environ["AEROVE_ORIGINAL_SAMPLE_SELECTOR"]))
Path(sys.argv[1]).write_text(json.dumps(source, indent=2), encoding="utf-8")
PY
if [[ "$phase" == original-sample ]]; then
    sample_selector=$(python3 -c 'import json,shlex,sys; print(shlex.quote(json.load(open(sys.argv[1]))["originalSample"]["selector"]))' "$diagnostics/SOURCE.json")
    selection=(-e class "$sample_selector")
fi
app_source=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["sourceRevision"])' "$diagnostics/SOURCE.json")
renderer_backend_remote=$(python3 -c 'import json,shlex,sys; print(shlex.quote(json.load(open(sys.argv[1]))["rendererBackend"]))' "$diagnostics/SOURCE.json")
fixture_args=()
smart_fixture_args=()
if [[ "$phase" != matrix && -n "${SMART_CONFIG_MEOW_FIXTURE_DIR:-}" ]]; then
    smart_fixture_tree=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["sourceTree"])' "$diagnostics/SOURCE.json")
    smart_fixture_target=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("runtimeTarget", ""))' "$diagnostics/SOURCE.json")
    smart_fixture_owner=current
    smart_fixture_verifier=prepare_smart_config_meow_ci_fixture.py
    if [[ "$smart_fixture_target" == lite141-prebuilt-shards ]]; then
        smart_fixture_owner=dba5
        smart_fixture_verifier=prepare_smart_config_meow_dba5_ci_fixture.py
    elif [[ -n "${AEROVE_ORIGINAL84_PROOF:-}${AEROVE_ORIGINAL_RUNTIME_PROOF:-}" ]]; then
        smart_fixture_owner=legacy
        smart_fixture_verifier=prepare_smart_config_meow_legacy_ci_fixture.py
    fi
    python3 "apps/aerove-lite/tool/$smart_fixture_verifier" verify \
        --receipt "${SMART_CONFIG_MEOW_FIXTURE_RECEIPT:?Smart fixture source receipt required}" \
        --expected-source "$app_source" --expected-tree "$smart_fixture_tree"
    fixture_args=(--smart-config-fixture "$SMART_CONFIG_MEOW_FIXTURE_DIR" --smart-config-fixture-owner "$smart_fixture_owner")
    smart_fixture_args=(-e smartConfigFixtureUrl http://10.0.2.2:18080/smart-config-meow)
fi
python3 apps/aerove-lite/tool/fixture_server.py "${fixture_args[@]}" > "$diagnostics/fixture.log" 2>&1 &
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
    if [[ "$phase" != remaining && "$phase" != original-sample ]]; then
        if [[ "$result" == 0 && "$pull_status" != 0 ]]; then result=1; fi
        gallery_status=0
        python3 apps/aerove-lite/tool/render_gallery.py --captures "$diagnostics/captures" --source "$app_source" \
            --output "$diagnostics/aerove-lite-appearance-12.html" > "$diagnostics/gallery.log" 2>&1 || gallery_status=$?
        if [[ "$result" == 0 && "$gallery_status" != 0 ]]; then result=1; fi
    fi
    write_result_index "$result"
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
if [[ -n "$shard_count" ]]; then
    set +e
    timeout --signal=TERM --kill-after=10s 120s "${adb_device[@]}" shell am instrument -w -r \
        -e debug false -e log true -e notClass "$matrix_class" \
        -e fixtureUrl http://10.0.2.2:18080 -e sourceRevision "$app_source" \
        "${smart_fixture_args[@]}" \
        -e isolatedBackgroundPolicy true -e deviceKind CI-emulator -e rendererBackend "$renderer_backend_remote" \
        dev.aerove.lite.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$diagnostics/discovery.log"
    discovery_status=("${PIPESTATUS[@]}")
    set -e
    printf '{"hostExitCode":%s,"teeExitCode":%s,"deadlineSeconds":120,"guestCancellationConfirmed":false}\n' \
        "${discovery_status[0]}" "${discovery_status[1]}" > "$diagnostics/DISCOVERY-HOST-EXIT.json" || true
    if [[ "${discovery_status[0]}" != 0 ]]; then exit "${discovery_status[0]}"; fi
    if [[ "${discovery_status[1]}" != 0 ]]; then exit "${discovery_status[1]}"; fi
    python3 apps/aerove-lite/tool/instrumentation_shards.py discover \
        --shard-count "$shard_count" --log "$diagnostics/discovery.log" --source "$diagnostics/SOURCE.json" \
        --proof "${AEROVE_CURRENT_RENDERER_PROOF:?Current runtime proof required for sharded acceptance}" \
        --sums "$diagnostics/APK_SHA256SUMS" --output "$diagnostics/TEST-DISCOVERY.json"
fi
if [[ "$phase" == original-sample ]]; then
    set +e
    timeout --signal=TERM --kill-after=10s 120s "${adb_device[@]}" shell am instrument -w -r \
        -e debug false -e log true "${selection[@]}" -e fixtureUrl http://10.0.2.2:18080 \
        -e sourceRevision "$app_source" -e isolatedBackgroundPolicy true -e deviceKind CI-emulator \
        -e rendererBackend "$renderer_backend_remote" dev.aerove.lite.test/androidx.test.runner.AndroidJUnitRunner \
        2>&1 | tee "$diagnostics/sample-discovery.log"
    discovery_status=("${PIPESTATUS[@]}")
    set -e
    if [[ "${discovery_status[0]}" != 0 || "${discovery_status[1]}" != 0 ]]; then exit 1; fi
    python3 apps/aerove-lite/tool/original_sample.py --source "$diagnostics/SOURCE.json" \
        --log "$diagnostics/sample-discovery.log" --discovery --output "$diagnostics/SAMPLE-SELECTION.json"
fi
ssid_selector=""
if [[ -n "$shard_count" ]]; then
    python3 apps/aerove-lite/tool/instrumentation_shards.py plan \
        --discovery "$diagnostics/TEST-DISCOVERY.json" --output "$diagnostics/EXECUTION-PLAN.json"
    ssid_selector=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["isolatedSelector"] or "")' "$diagnostics/EXECUTION-PLAN.json")
    if [[ -n "$ssid_selector" ]]; then
        selection=(-e notClass "$matrix_class,$ssid_selector" -e numShards "$shard_count" -e shardIndex "$shard_index")
    fi
fi
instrumentation_prefix=()
if [[ "$phase" == matrix && "${AEROVE_CLEAR_FRAME_TRACE:-0}" == 1 ]]; then
    instrumentation_prefix=(python3 apps/aerove-lite/tool/clear_frame_trace.py
        --serial "$serial" --output "$diagnostics/clear-trace" --duration 120
        --command-timeout 660 --)
fi
if [[ "${AEROVE_BETA14_PROFILE:-0}" == 1 ]]; then
    [[ "$phase" == matrix && "${AEROVE_CLEAR_FRAME_TRACE:-0}" == 1 ]] || exit 2
    instrumentation_prefix=(python3 apps/aerove-lite/tool/beta14_render_profile.py run
        --serial "$serial" --owner "${AEROVE_BETA14_PROFILE_OWNER:?Verified beta14 owner required}"
        --output "$diagnostics/render-profile" -- "${instrumentation_prefix[@]}")
fi
instrumentation_deadline=""
if [[ -n "$shard_count" ]]; then instrumentation_deadline=$(( $(date +%s) + 1200 )); fi
set +e
"${remaining_timeout[@]}" "${instrumentation_prefix[@]}" "${adb_device[@]}" shell am instrument -w -r -e debug false -e timeout_msec "$test_timeout_msec" "${selection[@]}" \
    -e fixtureUrl http://10.0.2.2:18080 -e sourceRevision "$app_source" \
    "${smart_fixture_args[@]}" \
    -e isolatedBackgroundPolicy true -e deviceKind CI-emulator -e rendererBackend "$renderer_backend_remote" \
    dev.aerove.lite.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$diagnostics/instrumentation.log"
pipeline_status=("${PIPESTATUS[@]}")
set -e
if [[ "$phase" == original-sample ]]; then
    printf '{"hostExitCode":%s,"teeExitCode":%s,"deadlineSeconds":240,"timeoutSignal":"TERM","killAfterSeconds":20,"guestCancellationConfirmed":false}\n' \
        "${pipeline_status[0]}" "${pipeline_status[1]}" > "$diagnostics/INSTRUMENTATION-HOST-EXIT.json" || true
elif [[ -n "$shard_count" ]]; then
    printf '{"hostExitCode":%s,"teeExitCode":%s,"deadlineSeconds":1200,"guestCancellationConfirmed":false}\n' \
        "${pipeline_status[0]}" "${pipeline_status[1]}" > "$diagnostics/INSTRUMENTATION-HOST-EXIT.json" || true
fi
if [[ "${pipeline_status[0]}" != 0 ]]; then exit "${pipeline_status[0]}"; fi
if [[ "${pipeline_status[1]}" != 0 ]]; then exit "${pipeline_status[1]}"; fi
if [[ "$phase" == original-sample ]]; then
    python3 apps/aerove-lite/tool/original_sample.py --source "$diagnostics/SOURCE.json" \
        --log "$diagnostics/instrumentation.log" --output "$diagnostics/SAMPLE-RESULT.json"
fi
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
if [[ -n "$shard_count" ]]; then
    isolated_verification=()
    if [[ -n "$ssid_selector" ]]; then
        python3 apps/aerove-lite/tool/ssid_device_setup.py \
            --serial "$serial" --source "$diagnostics/SOURCE.json" --discovery "$diagnostics/TEST-DISCOVERY.json" \
            --proof "${AEROVE_CURRENT_RENDERER_PROOF:?Owned emulator proof required}" \
            --output "$diagnostics/ssid-device" --deadline "$instrumentation_deadline" -- \
            "${adb_device[@]}" shell am instrument -w -r -e debug false -e timeout_msec "$test_timeout_msec" \
            -e class "$ssid_selector" -e fixtureUrl http://10.0.2.2:18080 -e sourceRevision "$app_source" \
            "${smart_fixture_args[@]}" -e isolatedBackgroundPolicy true -e deviceKind CI-emulator \
            -e rendererBackend "$renderer_backend_remote" dev.aerove.lite.test/androidx.test.runner.AndroidJUnitRunner
        isolated_verification=(--isolated-log "$diagnostics/ssid-device/instrumentation.log")
    fi
    python3 apps/aerove-lite/tool/instrumentation_shards.py verify \
        --discovery "$diagnostics/TEST-DISCOVERY.json" --log "$diagnostics/instrumentation.log" \
        "${isolated_verification[@]}" --output "$diagnostics/TEST-SELECTION.json"
fi

if [[ "${AEROVE_BETA14_PROFILE:-0}" == 1 ]]; then
    python3 apps/aerove-lite/tool/beta14_render_profile.py qualify --output "$diagnostics/render-profile"
fi
