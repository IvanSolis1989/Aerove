"""Host-only temporary prerequisites for the discovered real-Wi-Fi method; not a permission UI test."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import re

PACKAGE = "dev.aerove.lite"
PERMISSIONS = tuple("android.permission." + name for name in (
    "ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION", "ACCESS_BACKGROUND_LOCATION"))


def permission_state(text):
    sections = re.split(r"(?m)^\s*User (\d+):", text)
    if len(sections) < 3 or sections[1] != "0":
        raise ValueError("The isolated acceptance requires the installed User 0 permission state")
    section = sections[2]
    if any(permission not in text for permission in PERMISSIONS):
        raise ValueError("Installed app does not declare all required location permissions")
    result = {}
    for permission in PERMISSIONS:
        rows = re.findall(re.escape(permission) + r": granted=(true|false), flags=\[([^\]]*)\]", section)
        if len(rows) > 1:
            raise ValueError("Ambiguous runtime permission state")
        result[permission] = {"granted": bool(rows and rows[0][0] == "true"),
                              "flags": sorted(filter(None, rows[0][1].strip().split("|"))) if rows else []}
    return result


class DeviceScope:
    def __init__(self, shell, output, sleep=time.sleep):
        self.shell, self.output, self.sleep = shell, output, sleep
        self.before = None
        self.changes = []

    def snapshot(self):
        permissions = permission_state(self.shell("dumpsys", "package", PACKAGE))
        location = self.shell("cmd", "location", "is-location-enabled", "--user", "0").strip()
        wifi = self.shell("cmd", "wifi", "status")
        if location not in ("true", "false") or not re.search(r"^Wifi is (enabled|disabled)$", wifi, re.M):
            raise ValueError("Unrecognized real location/Wi-Fi state")
        return {"permissions": permissions, "locationEnabled": location == "true",
                "wifiEnabled": "Wifi is enabled" in wifi.splitlines(), "wifiStatus": wifi}

    def prepare(self):
        self.before = self.snapshot()
        (self.output / "before.json").write_text(json.dumps(self.before, indent=2) + "\n")
        for permission in PERMISSIONS:
            if not self.before["permissions"][permission]["granted"]:
                self.changes.append(permission)
                self.shell("pm", "grant", "--user", "0", PACKAGE, permission)
        self.shell("cmd", "location", "set-location-enabled", "true", "--user", "0")
        if not self.before["wifiEnabled"]:
            self.shell("cmd", "wifi", "set-wifi-enabled", "enabled")
        for attempt in range(10):
            prepared = self.snapshot()
            if re.search(r"^Wifi is connected to (.+)$", prepared["wifiStatus"], re.M):
                break
            if attempt < 9:
                self.sleep(1)
        (self.output / "prepared.json").write_text(json.dumps(prepared, indent=2) + "\n")
        connected = re.findall(r"^Wifi is connected to (.+)$", prepared["wifiStatus"], re.M)
        if (not all(value["granted"] for value in prepared["permissions"].values())
                or not prepared["locationEnabled"] or not prepared["wifiEnabled"]
                or not connected or any(value.strip('" ') in ("<unknown ssid>", "0x", "") for value in connected)):
            raise ValueError("Real location/Wi-Fi prerequisites are not ready; SSID is never invented")

    def restore(self):
        if self.before is None:
            return
        failures = []
        commands = [("am", "force-stop", PACKAGE)]
        commands += [("pm", "revoke", "--user", "0", PACKAGE, permission) for permission in reversed(self.changes)]
        commands += [("cmd", "location", "set-location-enabled", str(self.before["locationEnabled"]).lower(), "--user", "0")]
        if not self.before["wifiEnabled"]:
            commands += [("cmd", "wifi", "set-wifi-enabled", "disabled")]
        for command in commands:
            try:
                self.shell(*command)
            except Exception as error:
                failures.append(repr(error))
        try:
            after = self.snapshot()
            (self.output / "restored.json").write_text(json.dumps(after, indent=2) + "\n")
            if any(after[key] != self.before[key] for key in ("permissions", "locationEnabled", "wifiEnabled")):
                failures.append("Real restored permissions/flags or device switches differ from baseline")
        except Exception as error:
            failures.append(repr(error))
        (self.output / "cleanup.json").write_text(json.dumps({"failures": failures, "restored": not failures}, indent=2) + "\n")
        if failures:
            raise ValueError("SSID prerequisite cleanup failed: " + "; ".join(failures))


def prepare_after_ordinary(scope, discovery, log):
    from instrumentation_shards import verify_ordinary
    verify_ordinary(discovery, log)
    scope.prepare()


def validate_host(source, discovery, proof, serial, environment):
    from instrumentation_shards import IDENTITY, SSID_DESCRIPTION, split_plan
    if (environment.get("CI") != "true" or environment.get("GITHUB_ACTIONS") != "true"
            or not re.fullmatch(r"emulator-[0-9]+", serial) or environment.get("ANDROID_SERIAL") != serial
            or source.get("deviceKind") != "CI-emulator" or source.get("isolatedBackgroundPolicy") is not True
            or source.get("phase") != "remaining" or source.get("deviceSerial") != serial
            or proof.get("deviceSerial") != serial):
        raise ValueError("Only the recorded CI-owned emulator may be prepared")
    if (discovery["identity"] != {key: str(source[key]) for key in IDENTITY}
            or proof.get("apks") != discovery["apkSha256"]
            or split_plan(discovery)["isolated"] != [SSID_DESCRIPTION]):
        raise ValueError("Scoped invocation differs from the immutable APK/source/discovery")


def validate_command(command, serial):
    from instrumentation_shards import SSID_SELECTOR
    if command[:6] != ["adb", "-s", serial, "shell", "am", "instrument"]:
        raise ValueError("Scoped instrumentation must use the recorded serial")
    options = {}
    for index, value in enumerate(command):
        if value == "-e":
            key = command[index + 1]
            if key in options:
                raise ValueError("Duplicate instrumentation option")
            options[key] = command[index + 2]
    if (options.get("class") != SSID_SELECTOR or any(key in options for key in ("numShards", "shardIndex", "notClass", "log"))
            or command[-1] != "dev.aerove.lite.test/androidx.test.runner.AndroidJUnitRunner"):
        raise ValueError("Scoped invocation must execute exactly the original discovered method")


def validate_isolated_log(path):
    from instrumentation_shards import parse_log, SSID_DESCRIPTION
    records = parse_log(path)
    text = path.read_text()
    if (len(records) != 1 or records[0]["description"] != SSID_DESCRIPTION or records[0]["status"] != "passed"
            or re.findall(r"OK \((\d+) tests?\)", text) != ["1"]):
        raise ValueError("Original method did not report one complete passing terminal and summary")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("source", "discovery", "proof", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--deadline", type=float, required=True)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if sys.platform != "linux":
        raise ValueError("Device preparation requires the existing Linux CI emulator owner")
    from emulator_current import validate_renderer_proof
    from instrumentation_shards import parse_log, SSID_DESCRIPTION
    source = json.loads(args.source.read_text())
    discovery = json.loads(args.discovery.read_text())
    proof = validate_renderer_proof(args.proof)
    validate_host(source, discovery, proof, args.serial, os.environ)
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    validate_command(command, args.serial)
    expected_output = Path(os.environ["RUNNER_TEMP"]) / "lite-diagnostics/remaining/ssid-device"
    if (args.output.resolve() != expected_output.resolve() or args.source.resolve().parent != expected_output.resolve().parent
            or args.discovery.resolve().parent != expected_output.resolve().parent):
        raise ValueError("Scoped output/source/discovery must belong to the existing remaining owner")
    if not 0 < args.deadline - time.time() <= 1200:
        raise TimeoutError("Original shard host deadline expired or was extended")
    args.output.mkdir(parents=True, exist_ok=False)
    record = {"identity": discovery["identity"], "apkSha256": discovery["apkSha256"], "serial": args.serial,
              "sourceProofSha256": hashlib.sha256(args.proof.read_bytes()).hexdigest(), "commands": [],
              "hostDeadlineEpoch": args.deadline, "guestCancellationConfirmed": False}
    cleaning_up = False
    def shell(*items):
        if not cleaning_up and time.time() >= args.deadline:
            raise TimeoutError("Original shard host deadline expired during preparation")
        result = subprocess.run(["adb", "-s", args.serial, "shell", *items], capture_output=True, text=True, timeout=10)
        record["commands"].append({"argv": list(items), "exit": result.returncode, "stdout": result.stdout, "stderr": result.stderr})
        result.check_returncode()
        return result.stdout.replace("\r", "")
    if shell("getprop", "ro.kernel.qemu").strip() != "1" or shell("am", "get-current-user").strip() != "0":
        raise ValueError("Recorded device is not the User 0 virtual emulator")
    apk_paths = {"app-release.apk": Path("apps/aerove-lite/app/build/outputs/apk/release/app-release.apk"),
                 "app-release-androidTest.apk": Path("apps/aerove-lite/app/build/outputs/apk/androidTest/release/app-release-androidTest.apk")}
    def check_apks():
        if {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in apk_paths.items()} != discovery["apkSha256"]:
            raise ValueError("Scoped invocation APK bytes changed")
    check_apks()
    if args.deadline <= time.time():
        raise TimeoutError("Original shard host deadline already expired")
    scope = DeviceScope(shell, args.output)
    def interrupted(signum, frame):
        raise InterruptedError("Scoped host interrupted by signal " + str(signum))
    for signum in (signal.SIGINT, signal.SIGTERM):
        signal.signal(signum, interrupted)
    try:
        prepare_after_ordinary(scope, discovery, args.discovery.parent / "instrumentation.log")
        with (args.output / "instrumentation.log").open("w") as stream:
            if args.deadline <= time.time():
                raise TimeoutError("Original shard deadline expired before the method could start")
            process = subprocess.Popen(command, stdout=stream, stderr=subprocess.STDOUT)
            try:
                result = process.wait(timeout=max(0.01, args.deadline - time.time()))
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
        record["hostExitCode"] = result
        if result != 0:
            raise ValueError("Scoped instrumentation host failed")
        validate_isolated_log(args.output / "instrumentation.log")
        check_apks()
    except BaseException as error:
        record["failure"] = repr(error)
        raise
    finally:
        cleaning_up = True
        try:
            scope.restore()
        except BaseException as error:
            record["cleanupFailure"] = repr(error)
            raise
        finally:
            (args.output / "execution.json").write_text(json.dumps(record, indent=2) + "\n")


if __name__ == "__main__":
    main()
