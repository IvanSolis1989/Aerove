"""Own one cross-UID socket invocation on the already verified CI emulator and shard deadline."""
import argparse
import json
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import sys
import threading
import time

from build_process_owner_fixture import PACKAGE, build
import instrumentation_shards as shards


def validate_host(source, discovery, proof, serial, environment):
    if (environment.get("CI") != "true" or environment.get("GITHUB_ACTIONS") != "true"
            or not re.fullmatch(r"emulator-[0-9]+", serial) or environment.get("ANDROID_SERIAL") != serial
            or source.get("deviceKind") != "CI-emulator" or source.get("isolatedBackgroundPolicy") is not True
            or source.get("phase") != "remaining" or source.get("deviceSerial") != serial
            or proof.get("deviceSerial") != serial or proof.get("apks") != discovery["apkSha256"]
            or discovery["identity"] != {key: str(source[key]) for key in shards.IDENTITY}
            or shards.split_plan(discovery)["owner"] != [shards.OWNER_DESCRIPTION]):
        raise ValueError("Only the recorded CI emulator and exact discovered owner shard may run the fixture")


def validate_command(command, serial):
    if command[:6] != ["adb", "-s", serial, "shell", "am", "instrument"]:
        raise ValueError("Scoped instrumentation must use the recorded emulator")
    options = {}
    for index, value in enumerate(command):
        if value == "-e":
            key = command[index + 1]
            if key in options:
                raise ValueError("Duplicate instrumentation option")
            options[key] = command[index + 2]
    if (options.get("class") != shards.OWNER_SELECTOR or options.get("timeout_msec") != "120000"
            or any(key in options for key in ("numShards", "shardIndex", "notClass", "log", "processOwnerFixture", "ownerFixturePackage", "ownerTcpPort", "ownerUdpPort"))
            or command[-1] != "dev.aerove.lite.test/androidx.test.runner.AndroidJUnitRunner"):
        raise ValueError("Fixture must execute exactly the original method with the unchanged per-test timeout")


class EchoScope:
    def __init__(self, token_receipts):
        self.sockets = []
        self.threads = []
        self.stopped = threading.Event()
        self.receipts = token_receipts

    def start(self):
        tcp = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sockets += [tcp, udp]
        for sock in (tcp, udp):
            sock.bind(("127.0.0.1", 0))
            sock.settimeout(0.2)
        tcp.listen(1)

        def tcp_echo():
            while not self.stopped.is_set():
                try:
                    connection, peer = tcp.accept()
                    self.sockets.append(connection)
                    connection.settimeout(0.2)
                    with connection:
                        received = bytearray()
                        while not self.stopped.is_set():
                            try:
                                data = connection.recv(128)
                                if not data:
                                    break
                                connection.sendall(data)
                                received.extend(data)
                                if len(received) == 36:
                                    self.receipts.append(dict(protocol="tcp", peer=list(peer), payload=received.decode("ascii")))
                            except socket.timeout:
                                continue
                except socket.timeout:
                    continue
                except OSError:
                    break

        def udp_echo():
            while not self.stopped.is_set():
                try:
                    data, peer = udp.recvfrom(128)
                    udp.sendto(data, peer)
                    self.receipts.append(dict(protocol="udp", peer=list(peer), payload=data.decode("ascii")))
                except socket.timeout:
                    continue
                except OSError:
                    break

        for target in (tcp_echo, udp_echo):
            thread = threading.Thread(target=target, daemon=True)
            self.threads.append(thread)
            thread.start()
        return tcp.getsockname()[1], udp.getsockname()[1]

    def close(self):
        self.stopped.set()
        for sock in self.sockets:
            sock.close()
        for thread in self.threads:
            thread.join(timeout=1)
        if any(thread.is_alive() for thread in self.threads):
            raise ValueError("Owned echo worker did not terminate")


def validate_log(path):
    records = shards.parse_log(path)
    if (len(records) != 1 or records[0]["description"] != shards.OWNER_DESCRIPTION or records[0]["status"] != "passed"
            or re.findall(r"OK \((\d+) tests?\)", path.read_text()) != ["1"]):
        raise ValueError("Cross-UID original method did not pass exactly once")


def validate_receipt(receipt, echo, source):
    if (receipt.get("fixturePackage") != PACKAGE or receipt.get("fixtureUid") == receipt.get("appUid")
            or any(type(receipt.get(key)) is not int or receipt[key] <= 0 for key in ("fixtureUid", "appUid", "pid"))
            or receipt.get("lookupPassed") is not True or receipt.get("cleanupPassed") is not True
            or receipt.get("sourceRevision") != source["sourceRevision"] or not receipt.get("tunInterface")
            or not isinstance(receipt.get("sessionGeneration"), int) or receipt["sessionGeneration"] <= 0
            or not re.fullmatch(r"[0-9a-f-]{36}", receipt.get("token", ""))):
        raise ValueError("Actual VPN/identity/lookup/cleanup receipt is incomplete")
    for protocol in ("tcp", "udp"):
        row = receipt[protocol]
        if row["resolvedUid"] != receipt["fixtureUid"] or row["package"] != PACKAGE:
            raise ValueError("Resolved fixture identity differs")
        if not any(item["protocol"] == protocol and item["payload"] == receipt["token"] for item in echo):
            raise ValueError("Actual host nonce round-trip is missing: " + protocol)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("source", "discovery", "proof", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--deadline", type=float, required=True)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if sys.platform != "linux":
        raise ValueError("Fixture installation/execution requires the existing Linux CI emulator owner")
    os.nice(19)
    from emulator_current import validate_renderer_proof
    source = json.loads(args.source.read_text())
    discovery = json.loads(args.discovery.read_text())
    proof = validate_renderer_proof(args.proof)
    validate_host(source, discovery, proof, args.serial, os.environ)
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    validate_command(command, args.serial)
    expected = Path(os.environ["RUNNER_TEMP"]) / "lite-diagnostics/remaining/process-owner-device"
    if (args.output.resolve() != expected.resolve() or args.source.resolve().parent != expected.parent.resolve()
            or args.discovery.resolve().parent != expected.parent.resolve()):
        raise ValueError("Fixture output/source/discovery must belong to the existing remaining owner")
    if not 0 < args.deadline - time.time() <= 1200:
        raise TimeoutError("Original shard deadline expired or was extended")
    shards.verify_ordinary(discovery, args.discovery.parent / "instrumentation.log")
    args.output.mkdir(parents=True, exist_ok=False)
    record = dict(identity=discovery["identity"], apkSha256=discovery["apkSha256"], serial=args.serial,
                  sourceProofSha256=shards.sha(args.proof), hostDeadlineEpoch=args.deadline,
                  commands=[], echo=[], guestCancellationConfirmed=False, installed=False)
    cleaning = False
    installed = False
    echo = EchoScope(record["echo"])

    def run(argv, deadline=args.deadline):
        remaining = deadline - time.time()
        if remaining <= 0 and not cleaning:
            raise TimeoutError("Original shard deadline expired")
        result = subprocess.run(argv, capture_output=True, text=True, timeout=10 if cleaning else min(60, max(0.01, remaining)))
        record["commands"].append(dict(argv=argv, exit=result.returncode, stdout=result.stdout, stderr=result.stderr))
        result.check_returncode()
        return result.stdout.replace("\r", "")

    def adb(*items):
        return run(["adb", "-s", args.serial, *items])

    def check_apks():
        paths = {"app-release.apk": Path("apps/aerove-lite/app/build/outputs/apk/release/app-release.apk"),
                 "app-release-androidTest.apk": Path("apps/aerove-lite/app/build/outputs/apk/androidTest/release/app-release-androidTest.apk")}
        if {name: shards.sha(path) for name, path in paths.items()} != discovery["apkSha256"]:
            raise ValueError("Scoped invocation APK bytes changed")

    def interrupted(signum, frame):
        raise InterruptedError("Scoped fixture interrupted: " + str(signum))
    for signum in (signal.SIGINT, signal.SIGTERM):
        signal.signal(signum, interrupted)
    try:
        check_apks()
        if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1" or adb("shell", "am", "get-current-user").strip() != "0":
            raise ValueError("Recorded emulator is not User 0")
        if adb("shell", "pm", "list", "packages", PACKAGE).strip():
            raise ValueError("Never overwrite a pre-existing fixture package")
        apk, build_record = build(args.output / "build", args.deadline, run)
        record["fixtureBuild"] = build_record
        installed = True
        if "Success" not in adb("install", "-t", str(apk)).splitlines():
            raise ValueError("Fixture package installation did not report Success")
        record["installed"] = True
        record["packageBeforeRun"] = adb("shell", "dumpsys", "package", PACKAGE)
        package_path = adb("shell", "pm", "path", PACKAGE).strip()
        if not re.fullmatch(r"package:/data/app/[^\s]+/base\.apk", package_path):
            raise ValueError("Installed fixture APK path is not a single package base")
        installed_hash = adb("shell", "sha256sum", package_path.removeprefix("package:")).split()[0]
        if installed_hash != build_record["apkSha256"]:
            raise ValueError("Installed fixture bytes differ from the owned build")
        record["installedFixtureApkSha256"] = installed_hash
        tcp, udp = echo.start()
        invocation = command[:-1] + ["-e", "processOwnerFixture", "true", "-e", "ownerFixturePackage", PACKAGE,
                                   "-e", "ownerTcpPort", str(tcp), "-e", "ownerUdpPort", str(udp), command[-1]]
        with (args.output / "instrumentation.log").open("w") as stream:
            process = subprocess.Popen(invocation, stdout=stream, stderr=subprocess.STDOUT)
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
            raise ValueError("Scoped fixture instrumentation host failed")
        validate_log(args.output / "instrumentation.log")
        adb("pull", "/sdcard/Android/data/dev.aerove.lite/files/captures/process-owner/receipt.json", str(args.output / "receipt.json"))
        validate_receipt(json.loads((args.output / "receipt.json").read_text()), record["echo"], source)
        check_apks()
    except BaseException as error:
        record["failure"] = repr(error)
        raise
    finally:
        cleaning = True
        failures = []
        for action in (echo.close,):
            try:
                action()
            except BaseException as error:
                failures.append(repr(error))
        if installed:
            for action in (lambda: adb("shell", "am", "force-stop", "dev.aerove.lite"),
                           lambda: adb("shell", "am", "force-stop", PACKAGE), lambda: adb("uninstall", PACKAGE),
                           lambda: record.update(packageAfterCleanup=adb("shell", "pm", "list", "packages", PACKAGE))):
                try:
                    action()
                except BaseException as error:
                    failures.append(repr(error))
            if record.get("packageAfterCleanup", "missing").strip():
                failures.append("Fixture package removal was not confirmed")
        record["cleanup"] = dict(passed=not failures, failures=failures)
        (args.output / "execution.json").write_text(json.dumps(record, indent=2) + "\n")
        if failures:
            raise ValueError("Cross-UID fixture cleanup failed: " + "; ".join(failures))


if __name__ == "__main__":
    main()
