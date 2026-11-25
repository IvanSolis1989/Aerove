"""Run one fixed #373 VPN diagnostic under the original verified emulator owner."""
import argparse
from json import loads as load_json
import re
import os
from pathlib import Path
import signal
import subprocess
from types import SimpleNamespace
from uuid import UUID, uuid4

from android_acceptance import Acceptance, PACKAGE, TEST_PACKAGE
from android_acceptance_verify import STAGES, load, platform_binding
from emulator_current import validate_renderer_proof
from own_emulator import bind_runtime
from flutter_android_qa import idle, renderer, save, sha, validate_binding, validate_capture
from instrumentation_shards import parse_log
from verify_apk import signing_certificate

from consent_gate import THEME, RESTART, bound_receipt, failed_theme, need, persisted_restart

SOURCE = "d1b2db996af6142fbb299059c4de1b50df7dac38"
TREE = "04dd07698f78b6b3fcf21bf5325f2f68a1483be5"
APP = "aef322ed57f9a64bb3cca2c135a10840e24859fcf239b8c0b2ce337a887862f7"
PLATFORM = "52274d7cfa2a8f0fa19576270a0a0ee380eedc268c464da015143b0dda31fd59"
TEST = "0aeb9cbc748cece05c8136e15b4d7acb15df834dab95bb320d08506f287502fc"
SIGNER = "09f9e021ddac36ead015d27e073b673e79fdf4e02605c402a505781963b1a4d8"
VPN = "profileImportApplyVpnNetworkAndBackgroundResume"


def exact_target(args, proof):
    need(args.serial == proof["deviceSerial"] == os.environ.get("ANDROID_SERIAL") == "emulator-5554",
         "Must use the original proof's dedicated emulator")
    need(proof.get("owner") == "emulator_current.py" and proof.get("phase") == "flutter-full",
         "Original guest renderer owner proof required")
    info = load(args.build_info)
    need(info.get("source") == SOURCE and info.get("tree") == TREE
         and str(info.get("workflow")).rsplit("/", 1)[-1] == "45115924455" and str(info.get("run_attempt")) == "1"
         and info.get("apk_sha256") == APP and info.get("signer_sha256") == SIGNER,
         "Immutable original #373 identity mismatch")
    runtime = Path(proof["runtimeDirectory"])
    need(args.build_info.resolve() == (runtime / "BUILD_INFO.json").resolve(),
         "Build info is not from sealed runtime")
    validate_binding(args.apk, args.test_apk, info, proof)
    platform = platform_binding(runtime, info)
    need(platform.resolve() == args.platform_apk.resolve() and (sha(args.apk), sha(platform), sha(args.test_apk))
         == (APP, PLATFORM, TEST), "Original #373 APK or mapped runner changed")
    return info


def guest_target(owner):
    inventory = owner.command("guest-inventory", [owner.args.adb, "devices"], timeout=15).decode().splitlines()
    need(inventory in (["List of devices attached", "emulator-5554\tdevice"],
                       ["List of devices attached", "emulator-5554\tdevice", ""]), "Unexpected ADB inventory")
    for package in (PACKAGE, TEST_PACKAGE):
        present = owner.adb_command("preexisting-" + package, "shell", "pm", "path", package, check=False)
        need(b"package:" not in present, "Refusing to replace an existing package")
    for label, package, path, expected in (
            ("app", PACKAGE, owner.args.apk, APP),
            ("platform", TEST_PACKAGE, owner.args.platform_apk, PLATFORM)):
        verified = owner.command(label + "-signature",
                                 [owner.args.apksigner, "verify", "--verbose", "--print-certs", path]).decode()
        need(signing_certificate(verified) == SIGNER, "Original APK signer mismatch")
    for package, path, expected in ((PACKAGE, owner.args.apk, APP), (TEST_PACKAGE, owner.args.platform_apk, PLATFORM)):
        owner.installed.append(package)  # Include a partially completed install in owned cleanup.
        owner.adb_command("install-" + package, "install", *(["-t"] if package == TEST_PACKAGE else []),
                          "--no-streaming", str(path), timeout=120)
        owner.installed_hash("initial-" + package, package, expected)
    owner.adb_command("locale-en", "shell", "cmd", "locale", "set-app-locales", PACKAGE, "--user", "0", "--locales", "en-US")


def proceed_from_theme(owner, info, summary):
    token = "qa-" + uuid4().hex
    # Keep the genuine failed instrumentation and its log; never treat a nonzero test status as pass.
    summary["pendingCheckpoint"] = "theme-instrumentation"
    owner.adb_command("first-theme", *owner.base_args(THEME, token, info), timeout=600, check=False)
    command = owner.receipt["commands"][-1]
    need(command["label"] == "first-theme" and command["exit"] == 0 and not command.get("interruptedOrTimedOut"),
         "Theme transport was incomplete")
    summary["pendingCheckpoint"] = "theme-receipt-pull"
    directory = owner.pull(THEME, token)
    log_path = owner.args.output / "first-theme.out"
    summary["pendingCheckpoint"] = "theme-receipt-binding"
    row, digest = bound_receipt(directory, token, info)
    if row.get("scenarioPassed") is True:
        summary["pendingCheckpoint"] = "theme-pass-evidence"
        records = parse_log(log_path)
        need(len(records) == 1 and records[0]["className"] == row["producer"] and
             records[0]["testName"] == THEME and records[0]["status"] == "passed" and
             re.findall(r"^OK \((\d+) tests?\)$", log_path.read_text(), re.M) == ["1"],
             "No exact passed theme selector")
        need(type(row.get("finishedElapsedRealtimeMs")) is int
             and validate_capture(directory, info, token) == row, "No signed theme capture receipt")
        prior = dict(pid=row["pid"], token=token, receiptSha256=digest,
                     finished=row["finishedElapsedRealtimeMs"], themeStillFailed=False)
    else:
        summary["pendingCheckpoint"] = "theme-failure-evidence"
        prior = failed_theme(directory, log_path, token, info)
    summary["pendingCheckpoint"] = "theme-renderer-and-crash-check"
    log = owner.adb_command("first-renderer", "logcat", "-d", "-v", "threadtime", timeout=45).decode(errors="replace")
    renderer(log, prior["pid"])
    owner.check_process_log(log, prior["pid"])
    summary.update(stage="consent-ready-for-restart", themeFailed=prior["themeStillFailed"])
    summary["pendingCheckpoint"] = "original-process-exit"
    owner.adb_command("stop-first-process", "shell", "am", "force-stop", PACKAGE)
    before = owner.adb_command("first-process-gone", "shell", "pidof", PACKAGE, check=False)
    need(not before.strip() and owner.receipt["commands"][-1]["exit"] == 1, "Force-stop did not clear the original process")
    summary["pendingCheckpoint"] = "restart-test"
    restart, validated, _ = owner.ui(RESTART, info)
    summary["pendingCheckpoint"] = "restart-evidence"
    persisted_restart(owner.args.output / RESTART, restart["qaToken"], info, prior, validated)
    summary.update(stage="persisted-consent-proved", consentPersisted=True)


def verify_wire(output, row, identifier):
    requests = row.get("networkRequests", [])
    need([r.get("stage") for r in requests] == ["vpn-active", "background-active", "resumed-active"],
         "Incomplete three-stage fixture traffic")
    tokens = [r.get("requestToken", "") for r in requests]
    need(len(set(tokens + [identifier])) == 4 and all(str(UUID(t)) == t for t in tokens)
         and all(re.fullmatch("[0-9a-f]{64}", r.get("responseSha256", "")) for r in requests),
         "Fixture requests reused a token or lack response hashes")
    logfile = (output / "fixture.err").read_text()
    need(all(len(re.findall(r'"GET /' + t + r' HTTP/1\.[01]" 200 -$', logfile, re.M)) == 1 for t in tokens),
         "No matching socket requests at the owned fixture")
    events = [load_json(line) for line in (output / "profile-events.jsonl").read_text().splitlines()]
    need(any(e.get("token") == identifier and e.get("event") == "complete" for e in events),
         "No real completed profile fetch in the owned fixture")


def cleanup(owner):
    ok = True
    owner.cleaning = True
    if owner.fixture is not None:
        try:
            owner.fixture.terminate()
            try:
                owner.fixture.wait(timeout=5)
            except subprocess.TimeoutExpired:
                owner.fixture.kill()
                owner.fixture.wait(timeout=5)
        except Exception:
            ok = False
    for stream in owner.fixture_streams:
        try:
            stream.close()
        except OSError:
            ok = False
    for package in reversed(owner.installed):
        try:
            owner.adb_command("cleanup-stop-" + package, "shell", "am", "force-stop", package,
                              timeout=20, cancelling=True)
            owner.adb_command("cleanup-uninstall-" + package, "uninstall", package,
                              timeout=45, cancelling=True)
        except Exception:
            ok = False
    return ok


def run(args):
    owner = Acceptance(SimpleNamespace(**vars(args), phase="flutter-full"))
    summary = dict(schema=1, diagnosticOnly=True, accepted=False, fullAcceptance=False,
                   promotionAccepted=False, stage="original-binding", themeFailed=None,
                   consentPersisted=False, vpnSequenceProved=False, cleanupComplete=False)
    bind_runtime()
    previous = {name: signal.signal(name, owner.interrupt) for name in (signal.SIGINT, signal.SIGTERM)}
    try:
        proof = validate_renderer_proof(Path(os.environ["AEROVE_CURRENT_RENDERER_PROOF"]))
        info = exact_target(args, proof)
        summary["stage"] = "original-install"
        guest_target(owner)
        summary["stage"] = "consent-theme"
        proceed_from_theme(owner, info, summary)
        summary["pendingCheckpoint"] = "owned-fixture-start"
        owner.fixture_start()
        identifier = str(uuid4())
        summary["stage"] = "vpn-sequence"
        summary["pendingCheckpoint"] = "vpn-instrumentation"
        row, receipt, checkpoints = owner.ui(VPN, info, dict(fixtureUrl="http://10.0.2.2:18080",
            profileUrl="http://10.0.2.2:18080/flutter-profile/" + identifier + ".yaml?mixedPort=18081",
            profileLabel="FlutterAcceptance-" + identifier[:8], mixedProxyPort="18081"))
        summary["pendingCheckpoint"] = "vpn-checkpoints"
        need(receipt.get("stage") == "functional-stopped" and
             [item.get("stage") for item in checkpoints] == STAGES[VPN], "Functional lifecycle was not observed")
        summary["pendingCheckpoint"] = "vpn-wire-evidence"
        verify_wire(args.output, receipt, identifier)
        summary["pendingCheckpoint"] = "final-apk-binding"
        owner.installed_hash("final-app", PACKAGE, APP)
        owner.installed_hash("final-runner", TEST_PACKAGE, PLATFORM)
        summary.update(stage="vpn-sequence-proved", vpnSequenceProved=True)
        summary.pop("pendingCheckpoint", None)
    except Exception as error:
        summary["failureClass"] = type(error).__name__
        (args.output / "private-error.txt").write_text(repr(error), encoding="utf-8")
    finally:
        summary["cleanupComplete"] = cleanup(owner)
        summary["cancelled"] = owner.cancelled
        for name, old in previous.items():
            signal.signal(name, old)
        save(args.output / "vpn-diagnostic-summary.json", summary)
    return 0 if summary["vpnSequenceProved"] and summary["cleanupComplete"] and not owner.cancelled else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("apk", "test-apk", "platform-apk", "build-info", "adb", "apksigner", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    idle()
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
