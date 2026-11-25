"""One original-style emulator launch, with no APK and no raw public output."""
from pathlib import Path
import hashlib
import json
import os
import re
import signal
import subprocess
import sys
import time

BOOT = ["getprop", "sys.boot_completed"]
ANGLE = ["getprop", "debug.angle.feature_overrides_disabled"]
KEYGUARD = ["dumpsys", "window", "policy"]

def status(report, early_exit, allocated):
    commands = report.get("commands", [])
    def proved(arguments, expected=None):
        return any(row.get("arguments") == arguments and row.get("returnCode") == 0
                   and (expected is None or row.get("stdout", "").strip() == expected)
                   for row in commands)
    if not allocated:
        milestone = "preflight"
    elif report.get("ready") is True and proved(KEYGUARD):
        milestone = "keyguard-ready"
    elif proved(BOOT, "1") and report.get("guestHostQueryResetCompat", {}).get("propertyReadbackVerified") is True and proved(ANGLE, "supportsHostQueryReset"):
        milestone = "android-boot-complete"
    elif report.get("guestHostQueryResetCompat", {}).get("propertyReadbackVerified") is True and proved(ANGLE, "supportsHostQueryReset"):
        milestone = "angle-readback"
    elif proved(BOOT):
        milestone = "adb-shell"
    else:
        milestone = "process-allocated"
    return milestone, bool(early_exit)

def failure_bucket(report, exited, code, ready, interrupted):
    if interrupted:
        return "interrupted"
    if exited:
        return "host-child-signal" if type(code) is int and code < 0 else "host-child-exit"
    if ready:
        return "none"
    error = report.get("errorType")
    if error == "TimeoutError":
        return "guest-readiness-deadline"
    if error == "ValueError":
        return "guest-readiness-readback"
    if error in ("FileNotFoundError", "PermissionError", "OSError"):
        return "host-io"
    return "unclassified"


def main(root, private, avd_name='test'):
    if avd_name not in ('test', 'test_repeat'):
        raise ValueError('Unexpected cold boot target')
    sys.path.insert(0, str(root / "apps/aerove-lite/tool"))
    from emulator_bootstrap import wait_ready
    from emulator_qa79 import emulator_environment, stop_owned
    started, child, result = time.monotonic(), None, 1
    report, exited, code, adb_hash = {}, False, None, None
    signals = []
    def cancel(sig, _):
        signals.append(sig)
    def check_cancelled(_):
        if signals:
            raise InterruptedError("Diagnostic interrupted")
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, cancel)
    try:
        sdk = Path(os.environ["ANDROID_HOME"])
        expected_adb = sdk / "platform-tools/adb"
        from shutil import which
        adb = which("adb")
        if adb is None or Path(adb).resolve() != expected_adb.resolve():
            raise ValueError("Unexpected adb executable")
        with expected_adb.open('rb') as stream:
            adb_hash = hashlib.file_digest(stream, 'sha256').hexdigest()
        targets = subprocess.run([adb, "devices"], check=True, capture_output=True, text=True, timeout=15).stdout
        if targets.strip() != "List of devices attached":
            raise ValueError("Non-empty or invalid ADB inventory")
        executable = sdk / "emulator/emulator"
        command = [str(executable), "-port", "5554", "-avd", avd_name, "-no-window", "-gpu", "swangle",
                   "-no-snapshot", "-noaudio", "-no-boot-anim", "-feature", "GuestAngle,Vulkan"]
        with (private / "stdout").open("wb") as out, (private / "stderr").open("wb") as err:
            check_cancelled(None)
            child = subprocess.Popen(command, env=emulator_environment(os.environ),
                                     stdin=subprocess.DEVNULL, stdout=out, stderr=err, start_new_session=True)
            receipt = dict(ownerPid=os.getpid(), interruptSignals=signals)
            wait_ready(child, private, receipt, check_cancelled, capture_guest_logcat=True,
                       disable_guest_host_query_reset=True)
            check_cancelled(None)
            result = int(child.poll() is not None)
    finally:
        elapsed = int(time.monotonic() - started)
        try:
            f = private / "BOOTSTRAP.json"
            if f.is_file():
                report = json.loads(f.read_text())
        finally:
            if child is not None:
                outcome = stop_owned(child)
                exited = outcome['exitObservedBeforeTermination']
                code = outcome['returnCode'] if exited else None
                result = 1 if exited or signals else result
                if child.poll() is not None:
                    (private / "owned-cleanup-ok").touch(exist_ok=False)
        milestone, unexpected = status(report, exited, child is not None)
        band = next((f"under {n}s" for n in (15, 45, 90, 180, 360, 605) if elapsed < n), "605s+")
        image = os.environ.get("ImageVersion", "")
        if not re.fullmatch(r"[0-9]{8}\.[0-9]{1,4}\.[0-9]{1,4}(?:\.[0-9]{1,4})?", image):
            image = "not available"
        guest = os.environ.get("AEROVE_BOOT_GUEST_ID", "")
        if not re.fullmatch(r"r[0-9]{1,3}", guest):
            guest = "not available"
        print("### AEROVE original-style boot control (no APK)\n")
        print(f"- Last proven milestone: `{milestone}`")
        print(f"- Child already exited before cleanup: `{str(unexpected).lower()}`")
        if exited and type(code) is int and -64 <= code <= 255:
            print(f"- Pre-cleanup exit status: `{code}`")
        print(f"- Boot outcome class: `{failure_bucket(report, exited, code, milestone == 'keyguard-ready', bool(signals))}`")
        print(f"- Boot observer elapsed: `{band}`")
        if adb_hash:
            print(f"- ADB executable SHA-256: `{adb_hash}`")
        print(f"- Hosted image version: `{image}`")
        print(f"- API 33 Google APIs x86_64 installed image revision: `{guest}`")
    return result

if __name__ == "__main__":
    raise SystemExit(main(Path(sys.argv[1]), Path(sys.argv[2]), *sys.argv[3:]))
