"""Only allow fixed result fields out of the isolated CI diagnostic."""
import json
from pathlib import Path
import sys

PREFLIGHT = frozenset(("host-kvm", "guest-image", "avd", "emulator", "apksigner", "owner-import",
                       "owner-environment", "adb-inventory", "artifact-metadata",
                       "artifact-download", "artifact-verification", "artifact-staging", "emulator-owner"))
STAGES = frozenset(("original-binding", "original-install", "consent-theme", "consent-ready-for-restart",
                    "persisted-consent-proved", "vpn-sequence", "vpn-sequence-proved"))

CHECKPOINTS = frozenset(["theme-instrumentation","theme-receipt-pull","theme-receipt-binding","theme-pass-evidence","theme-failure-evidence","theme-renderer-and-crash-check","original-process-exit","restart-test","restart-evidence","owned-fixture-start","vpn-instrumentation","vpn-checkpoints","vpn-wire-evidence","final-apk-binding"])
FAILURES = frozenset(("TimeoutExpired", "TimeoutError", "ValueError", "CalledProcessError",
                      "RuntimeError", "FileNotFoundError", "InterruptedError", "OSError"))


def read(path):
    if not path.is_file():
        return {}
    return json.loads(path.read_text())


def summarize(root, code):
    phase_file = root / "phase"
    phase = phase_file.read_text().strip() if phase_file.is_file() else ""
    path = root / "lite-diagnostics/flutter-full"
    owner = read(path / "EMULATOR-OWNER.json")
    bootstrap = read(path / "BOOTSTRAP.json")
    result = read(path / "vpn-diagnostic/vpn-diagnostic-summary.json")
    stage = result.get("stage")
    safe_stage = stage if stage in STAGES else "not observed"
    fully_owned = (owner.get("cleanupRequested") is True and owner.get("unexpectedEmulatorExit") is False
                   and owner.get("cleanupErrors") == [] and owner.get("interruptSignals") == [])
    print("### AEROVE #373 original signed runtime, VPN diagnostic only\n")
    print("- Release and visual acceptance: not granted")
    print("- Preflight step: " + (phase if phase in PREFLIGHT else "unclassified"))
    print("- Owned emulator PID recorded: " + str(type(owner.get("emulatorPid")) is int and owner["emulatorPid"] > 1).lower())
    print("- Android boot ready: " + str(bootstrap.get("ready") is True).lower())
    print("- Furthest verified diagnostic stage: " + safe_stage)
    checkpoint = result.get("pendingCheckpoint")
    failure = result.get("failureClass")
    if checkpoint in CHECKPOINTS:
        print("- Diagnostic checkpoint not completed: " + checkpoint)
    if failure is not None:
        print("- Diagnostic failure category: " + (failure if failure in FAILURES else "unclassified"))
    if type(result.get("themeFailed")) is bool:
        print("- Theme test failed: " + str(result["themeFailed"]).lower())
    print("- New process did not request consent again: " + str(result.get("consentPersisted") is True).lower())
    print("- Owned VPN import, start, foreground/background traffic, resume and stop proved: "
          + str(result.get("vpnSequenceProved") is True and result.get("cleanupComplete") is True
                and fully_owned and code == 0 and result.get("cancelled") is False).lower())
    print("- Emulator owner cleanup: " + ("verified" if fully_owned else "not verified"))
    print("- No new package, signing, release or published raw diagnostics")


if __name__ == "__main__":
    summarize(Path(sys.argv[1]), int(sys.argv[2]))
