"""Capture fixed original Lite236/257 pixels without approving UI.

Linux/KVM diagnostic only. Builds nothing, clicks nothing, and never approves UI.
The caller supplies the downloaded original ZIP, API row and unpacked runtime.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import sys
import time
from uuid import uuid4
import xml.etree.ElementTree as ET

from emulator_bootstrap import wait_ready
from emulator_current import guest_angle_evidence, write_owner_receipt
from emulator_qa79 import GPU_BACKEND, emulator_environment, finish_emulator
from verify_apk import verify_identity, signing_certificate

APP = dict(sourceRevision="2a38627ca1c224f6ebf71c3e42052912699decdb",
           sourceTree="ec62b4fdf2f113960237e1853da2faf9664f3e05",
           workflowRun="41779045595", runNumber=236, runAttempt=1)
PACKAGE = "dev.aerove.lite"
LAUNCHER = PACKAGE + "/" + PACKAGE + ".FlutterMainActivity"
SERIAL = "emulator-5554"
APK_SHA = "e0e4ddb3cf4868377a903c7c031ee6dcc5715a1e303d303a012bcb8a366f26f1"
INFO_SHA = "23e423ec40910771cb90c8433646bf357497ad350b2f9fd90162400116716b18"
SIGNER = "09f9e021ddac36ead015d27e073b673e79fdf4e02605c402a505781963b1a4d8"
ZIP_SHA = "3fe0d859db00532552156e2a4107b71cac247a40e1a3cb8c4cdf91d0563a567e"
PNG = b"\x89PNG\r\n\x1a\n"
APP_257 = dict(sourceRevision="baea420b1ec6e42ecdd3f9e2610c3dc2346b326c",
               sourceTree="6b15d9ae6b20d85f324bff38855523f5a26d0375",
               workflowRun="41958443583", runNumber=257, runAttempt=1)
APK_SHA_257 = "c58aed2e2cddc15b5e8a1953bac88bef3b60feddbbd1621e822a230bc28e9a64"
INFO_SHA_257 = "4c47bf0fd834f703ea1d83b4f17ff24ae3c374cd88eb09f9be3cc48e3df2b992"
ZIP_SHA_257 = "c4a0310698635fcce0e3c7d4f698bceec51b113639b4f7b23fb7488eac3b6836"


def original257(args):
    api = json.loads(args.artifact_api.read_text(encoding="utf-8-sig"))
    require(api.get("id") == 13385808111 and api.get("name") == "AEROVE-Lite-runtime-contract-attempt-1"
            and api.get("size_in_bytes") == 59572666 and api.get("digest") == "sha256:" + ZIP_SHA_257
            and api.get("created_at") == "2026-11-05T03:34:32Z" and api.get("expired") is False
            and api.get("url") == "https://api.github.com/repos/IvanSolis1989/Aerove/actions/artifacts/13385808111"
            and api.get("workflow_run", {}).get("id") == int(APP_257["workflowRun"])
            and api["workflow_run"].get("head_sha") == APP_257["sourceRevision"]
            and api["workflow_run"].get("repository_id") == 1429390079
            and api["workflow_run"].get("head_repository_id") == 1429390079,
            "Requires the fixed official original257 artifact API identity")
    repository = Path(__file__).resolve().parents[3]
    sys.path.insert(0, str(repository))
    from tooling.product_source import bind_archive
    from ci_artifacts import verify
    payload = bind_archive(args.archive, args.runtime_directory, api)
    verify(args.runtime_directory, APP_257, runtime=True)
    require(payload["BUILD_INFO.json"]["sha256"] == INFO_SHA_257
            and payload["apks/app-release.apk"]["sha256"] == APK_SHA_257, "Original257 App/BUILD_INFO bytes differ")
    info = json.loads((args.runtime_directory / "BUILD_INFO.json").read_text())
    expected = dict(product="AEROVE Lite", channel="preview", application_id=PACKAGE, version="0.2.0-beta.1", version_code=34,
        min_sdk=33, signer_sha256=SIGNER, source=APP_257["sourceRevision"], tree=APP_257["sourceTree"], abi="x86_64",
        workflow="https://github.com/IvanSolis1989/Aerove/actions/runs/" + APP_257["workflowRun"], run_number=257,
        run_attempt=1, apk_sha256=APK_SHA_257, meow_source="727ddcd06f592e2f12c5222c4de6cce328df808c",
        launcher_activity=PACKAGE + ".FlutterMainActivity")
    require(all(info.get(k) == v for k, v in expected.items()), "Fixed original257 package metadata differs")
    return dict(artifactId=13385808111, artifactApiSha256=sha(args.artifact_api), actualZipSha256=ZIP_SHA_257,
                originalBuildInfoSha256=INFO_SHA_257, originalApkSha256=APK_SHA_257,
                originalSealSha256=sha(args.runtime_directory / "source-manifest.json"))


def require(value, message):
    if not value:
        raise ValueError(message)


def sha(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def original(args):
    number = getattr(args, "original_run_number", 236)
    require(number in (236, 257), "Only fixed original236/257 diagnostic inputs are supported")
    if number == 257:
        return original257(args)
    api = json.loads(args.artifact_api.read_text(encoding="utf-8-sig"))
    require(api.get("id") == 13300156190 and api.get("name") == "AEROVE-Lite-runtime-contract-attempt-1"
            and api.get("size_in_bytes") == 59036169 and api.get("digest") == "sha256:" + ZIP_SHA
            and api.get("expired") is False
            and api.get("url") == "https://api.github.com/repos/IvanSolis1989/Aerove/actions/artifacts/13300156190"
            and api.get("workflow_run", {}).get("id") == int(APP["workflowRun"])
            and api["workflow_run"].get("head_sha") == APP["sourceRevision"]
            and api["workflow_run"].get("repository_id") == 1429390079
            and api["workflow_run"].get("head_repository_id") == 1429390079,
            "Requires the fixed official original236 artifact API identity")
    # Existing reader hashes the actual ZIP, checks CRC while reading every entry,
    # rejects unsafe/linked entries, and compares the complete extracted inventory.
    repository = Path(__file__).resolve().parents[3]
    sys.path.insert(0, str(repository))
    from tooling.product_source import bind_archive
    from ci_artifacts import verify
    payload = bind_archive(args.archive, args.runtime_directory, api)
    verify(args.runtime_directory, APP, runtime=True)
    require(payload["BUILD_INFO.json"]["sha256"] == INFO_SHA
            and payload["apks/app-release.apk"]["sha256"] == APK_SHA, "Original App/BUILD_INFO bytes differ")
    info = json.loads((args.runtime_directory / "BUILD_INFO.json").read_text())
    expected = dict(product="AEROVE Lite", application_id=PACKAGE, version="0.1.0-beta.28", version_code=28,
        min_sdk=33, signer_sha256=SIGNER, source=APP["sourceRevision"], tree=APP["sourceTree"], abi="x86_64",
        workflow="https://github.com/IvanSolis1989/Aerove/actions/runs/" + APP["workflowRun"], run_number=236,
        run_attempt=1, apk_sha256=APK_SHA, meow_source="727ddcd06f592e2f12c5222c4de6cce328df808c",
        launcher_activity=PACKAGE + ".FlutterMainActivity")
    require(all(info.get(k) == v for k, v in expected.items()), "Fixed original236 package metadata differs")
    return dict(artifactId=13300156190, artifactApiSha256=sha(args.artifact_api), actualZipSha256=ZIP_SHA,
                originalBuildInfoSha256=INFO_SHA, originalApkSha256=APK_SHA,
                originalSealSha256=sha(args.runtime_directory / "source-manifest.json"))


class Diagnostic:
    def __init__(self, args):
        self.args = args
        self.original257 = getattr(args, "original_run_number", 236) == 257
        self.app = APP_257 if self.original257 else APP
        self.apk_sha = APK_SHA_257 if self.original257 else APK_SHA
        self.info_sha = INFO_SHA_257 if self.original257 else INFO_SHA
        self.scene_logcat = None
        self.adb = args.sdk / "platform-tools/adb"
        self.emulator = None
        self.child = None
        self.install_attempted = False
        self.surface_install_attempted = False
        self.surface_evidence_remote = None
        self.cleaning = False
        self.finalizing = False
        self.handlers = {}
        self.receipt = dict(schema=1, producer="lite236_pixel_diagnostic.py", status="starting",
            diagnosticOnly=True, accepted=False, visualApproved=False, performanceAccepted=False,
            fullAcceptance=False, releaseAccepted=False, collectionComplete=False, clicksPerformed=False,
            appOrHarnessCompiled=False, originalAppIdentity=self.app,
            executorIdentity=dict(sourceRevision=args.source, workflowRun=args.run, runAttempt=int(args.attempt)),
            disableQueueSubmitWithCommands=args.disable_queue_submit_with_commands,
            executorScriptSha256=sha(__file__), ownerPid=os.getpid(), deviceSerial=SERIAL,
            interruptSignals=[], commands=[], frames=[], cleanupErrors=[], unexpectedEmulatorExit=False)

    def surface_capture(self):
        args = self.args
        harness = json.loads(args.surface_test_info.read_text())
        require(harness.get("schema") == 1 and harness.get("diagnosticOnly") is True and
                harness.get("sourceRevision") == args.source and harness.get("workflowRun") == args.run and
                harness.get("runAttempt") == int(args.attempt) and harness.get("apkSha256") == sha(args.surface_test_apk) and
                harness.get("signerSha256") == SIGNER and harness.get("appCompiled") is False,
                "Surface diagnostic runner must be the separately built same-executor test APK")
        signature, _ = self.command("surface-runner-signature", [args.sdk / "build-tools/37.0.0/apksigner",
            "verify", "--verbose", "--print-certs", args.surface_test_apk], timeout=120)
        require(signing_certificate(signature.decode()) == SIGNER, "Surface runner differs from the original Lite signer")
        self.receipt.update(appOrHarnessCompiled=True, appCompiled=False, harnessCompiled=True,
                            surfaceHarness=harness, surfaceHarnessInfoSha256=sha(args.surface_test_info))
        self.surface_install_attempted = True
        self.adb_command("install-surface-runner", "install", "-t", "--no-streaming", args.surface_test_apk, timeout=120)
        token = "surface-" + uuid4().hex
        remote = "/sdcard/Android/data/" + PACKAGE + "/files/surface-pixel-diagnostic/" + token
        self.surface_evidence_remote = remote
        values = dict(qaToken=token, originalAppApkSha256=self.apk_sha, originalAppSignerSha256=SIGNER,
            originalAppVersionCode="34" if self.original257 else "28", harnessApkSha256=harness["apkSha256"], harnessSignerSha256=SIGNER,
            harnessSourceSha=args.source, harnessBuildRun=args.run, harnessBuildAttempt=args.attempt,
            executorSourceSha=args.source, executionRun=args.run, executionAttempt=args.attempt)
        selector = "dev.aerove.lite.SurfacePixelDiagnosticTest#testCaptureLateSurfaceAndCompositor"
        command = ["shell", "am", "instrument", "-w", "-r", "-e", "class", selector]
        for key, value in values.items():
            command += ["-e", key, str(value)]
        directory = args.output / "surface-capture"
        pulled = False
        try:
            log, _ = self.adb_command("surface-instrument", *command,
                "dev.aerove.lite.test/android.test.InstrumentationTestRunner", timeout=170)
        except BaseException:
            if self.original257:
                self.failure_scene257()
            raise
        finally:
            try:
                _, result = self.adb_command("surface-evidence-pull", "pull", remote, directory, timeout=45, check=False)
                pulled = result["exit"] == 0
            except BaseException as error:
                self.receipt.setdefault("diagnosticErrors", []).append(repr(error))
        from instrumentation_shards import parse_log
        records = parse_log(args.output / "surface-instrument.out", expected_reporter="InstrumentationTestRunner")
        require(len(records) == 1 and records[0]["className"] + "#" + records[0]["testName"] == selector and
                records[0]["status"] == "passed" and re.findall(rb"OK \((\d+) tests?\)", log) == [b"1"],
                "The one actual Surface capture test did not pass")
        require(pulled, "Surface capture evidence could not be read back")
        capture = json.loads((directory / "receipt.json").read_text())
        require(capture.get("status") == "captured" and capture.get("captureCompleted") is True and
                capture.get("diagnosticOnly") is True and capture.get("accepted") is False and
                capture.get("visualApproved") is False and capture.get("atomicSameFrame") is False and
                capture.get("resourceCleanup") == "complete", "Surface capture or resource cleanup did not complete")
        for key, filename in (("surfaceCopy", "surface.png"), ("compositorCopy", "compositor.png")):
            require(capture[key]["image"]["sha256"] == sha(directory / filename), "Captured original pixels changed")
        self.receipt["surfaceCapture"] = dict(receiptSha256=sha(directory / "receipt.json"),
            directory="surface-capture", captureCompleted=True, visualApproved=False, atomicSameFrame=False)

    def check(self, _receipt=None):
        if self.receipt["interruptSignals"] and not self.cleaning:
            raise InterruptedError("Pixel diagnostic cancelled")
        if self.emulator is not None and self.emulator.poll() is not None:
            raise RuntimeError("Owned emulator exited before capture completed")

    def interrupt(self, signum, _frame):
        self.receipt["interruptSignals"].append(signum)
        if self.finalizing:
            self.receipt.update(status="cancelled", result=1, collectionComplete=False)
            write_owner_receipt(self.args.output / "PIXEL-DIAGNOSTIC.json", self.receipt)
            raise InterruptedError("Pixel diagnostic interrupted during finalization")
        if not self.cleaning:
            raise InterruptedError("Pixel diagnostic interrupted")

    def command(self, label, command, *, timeout=30, check=True):
        self.check()
        row = dict(label=label, command=[str(c) for c in command], monotonicStart=time.monotonic(), timeoutSeconds=timeout)
        self.receipt["commands"].append(row)
        out, err = self.args.output / (label + ".out"), self.args.output / (label + ".err")
        with out.open("wb") as stdout, err.open("wb") as stderr:
            child = subprocess.Popen(row["command"], stdin=subprocess.DEVNULL, stdout=stdout, stderr=stderr,
                                     start_new_session=True)
            self.child = child
            try:
                row["exit"] = child.wait(timeout=timeout)
            except BaseException:
                if child.poll() is None:
                    child.terminate()
                    try:
                        child.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        child.kill(); child.wait(timeout=5)
                row.update(exit=child.returncode, interruptedOrTimedOut=True)
                raise
            finally:
                self.child = None
                row["monotonicEnd"] = time.monotonic()
        row.update(stdoutSha256=sha(out), stderrSha256=sha(err))
        self.check()
        require(not check or row["exit"] == 0, f"{label} failed ({row['exit']}); see {err}")
        return out.read_bytes(), row

    def adb_command(self, label, *arguments, **options):
        return self.command(label, [self.adb, "-s", SERIAL, *arguments], **options)

    def pid(self, label):
        raw, _ = self.adb_command(label, "shell", "pidof", PACKAGE)
        require(re.fullmatch(rb"[1-9][0-9]*\s*", raw) is not None, "Original App must have one actual main PID")
        return int(raw.strip())

    def installed_hash(self, label):
        raw, _ = self.adb_command(label + "-path", "shell", "pm", "path", PACKAGE)
        path = re.fullmatch(r"package:(/data/app/[A-Za-z0-9_./+=~-]+/base\.apk)\s*", raw.decode())
        require(path is not None, "Original App must have one installed base APK, without splits")
        digest, _ = self.adb_command(label + "-hash", "shell", "sha256sum", path[1], timeout=60)
        match = re.fullmatch(r"([0-9a-f]{64})[ \t]+" + re.escape(path[1]) + r"\s*", digest.decode())
        require(match is not None and match[1] == self.apk_sha, "Installed fixed original APK bytes differ")
        return dict(path=path[1], sha256=match[1])

    def frame(self, label, *, target=None):
        # These PID reads and the screenshot use no accessibility connection.
        before = self.pid(label + "-pid-before")
        require(before == self.receipt["appPid"], "App process changed during the unchanged consent scene")
        raw, row = self.adb_command(label, "exec-out", "screencap", "-p", timeout=30)
        require(raw.startswith(PNG) and len(raw) >= 45 and raw[12:16] == b"IHDR"
                and raw.endswith(b"\x00\x00\x00\x00IEND\xaeB`\x82"), "screencap did not return a complete original PNG")
        width, height = int.from_bytes(raw[16:20], "big"), int.from_bytes(raw[20:24], "big")
        require(width > 100 and height > 100, "Unexpected original screencap dimensions")
        if self.args.disable_queue_submit_with_commands:
            require((width, height) == (1080, 2400), "Queue-submit control changed the original render dimensions")
        path = self.args.output / (label + ".png")
        # Write the exact adb exec-out bytes; no image conversion, crop or annotation.
        path.write_bytes(raw)
        after = self.pid(label + "-pid-after")
        require(after == before, "App PID changed across the screenshot")
        window, _ = self.adb_command(label + "-window", "shell", "dumpsys", "window", "windows")
        self.receipt["frames"].append(dict(file=path.name, sha256=sha(path), bytes=len(raw), width=width, height=height,
            appPid=before, targetOffsetSeconds=target, captureMonotonicStart=row["monotonicStart"],
            captureMonotonicEnd=row["monotonicEnd"],
            actualOffsetSeconds=row["monotonicStart"] - self.receipt["launchMonotonicEnd"],
            scheduleLatenessSeconds=None if target is None else max(0, row["monotonicStart"] - self.receipt["launchMonotonicEnd"] - target),
            accessibilityDumpStarted=self.receipt.get("accessibilityDumpStarted") is True,
            windowFile=label + "-window.out", windowSha256=hashlib.sha256(window).hexdigest()))

    def scene_diagnostics(self, label):
        self.adb_command(label + "-app-logcat", "logcat", "-b", "all", "-d", "-v", "threadtime", "--pid=" + str(self.receipt["appPid"]))
        self.adb_command(label + "-window", "shell", "dumpsys", "window", "windows")
        self.adb_command(label + "-surfaceflinger", "shell", "dumpsys", "SurfaceFlinger")
        self.adb_command(label + "-system-logcat", "logcat", "-b", "all", "-d", "-v", "threadtime", timeout=45)

    def start_scene_logcat(self):
        with (self.args.output / "scene-logcat.out").open("wb") as out, (self.args.output / "scene-logcat.err").open("wb") as err:
            self.scene_logcat = subprocess.Popen([self.adb, "-s", SERIAL, "logcat", "-b", "all", "-v", "threadtime"],
                stdin=subprocess.DEVNULL, stdout=out, stderr=err, start_new_session=True)
        self.receipt["sceneLogcat"] = dict(ownerPid=self.scene_logcat.pid, monotonicStart=time.monotonic(),
            coverage="started before installation; actual logs/end status must be inspected")

    def failure_scene257(self, stage="surface"):
        require(stage in ("baseline", "surface"), "Only the two fixed257 failure boundaries are supported")
        scenes = self.receipt.setdefault("failureScenes257", {})
        if stage in scenes:
            return
        scene = scenes[stage] = dict(monotonicStart=time.monotonic())
        prefix = "failure257-" + stage
        probes = [(prefix + "-system-logcat", ["logcat", "-b", "all", "-d", "-v", "threadtime"], 10),
                  (prefix + "-surfaceflinger", ["shell", "dumpsys", "SurfaceFlinger"], 3)]
        for name, package in (("app", PACKAGE), ("sf", "surfaceflinger")):
            try:
                raw, _ = self.adb_command(prefix + "-" + name + "-pid", "shell", "pidof", package, timeout=3, check=False)
                require(re.fullmatch(rb"[1-9][0-9]*\s*", raw) is not None, "No unique current " + name + " PID")
                probes.append((prefix + "-" + name + "-backtrace", ["shell", "debuggerd", "-b", str(int(raw.strip()))], 10))
            except BaseException as error:
                self.receipt.setdefault("diagnosticErrors", []).append(repr(error))
        for label, command, budget in probes:
            try:
                self.adb_command(label, *command, timeout=budget, check=False)
            except BaseException as error:
                self.receipt.setdefault("diagnosticErrors", []).append(repr(error))
        try:
            self.command(prefix + "-host-threads", ["ps", "-L", "-p", str(self.emulator.pid),
                "-o", "pid,tid,stat,wchan:40,comm"], timeout=5, check=False)
            process = Path("/proc") / str(self.emulator.pid)
            directory = self.args.output / (prefix + "-host-proc")
            directory.mkdir()
            for path in [process / "stat", process / "status"] + [
                    task / name for task in sorted((process / "task").iterdir(), key=lambda p: int(p.name))[:128]
                    for name in ("stat", "wchan", "stack")]:
                destination = directory / "-".join(path.relative_to(process).parts)
                try:
                    destination.write_bytes(path.read_bytes())
                except OSError as error:
                    destination.with_suffix(".error").write_text(repr(error))
            scene["hostEvidence"] = dict(emulatorPid=self.emulator.pid, maxThreads=128,
                nativeBacktrace=False, observation="owned host process/task state; stack permission failures retained")
        except BaseException as error:
            self.receipt.setdefault("diagnosticErrors", []).append(repr(error))

    def execute(self):
        args = self.args
        self.receipt["originalArtifact"] = original(args)
        require(sys.platform == "linux", "Use the existing isolated Linux/KVM runner; no other device is supported")
        require(not os.environ.get("ANDROID_EMU_VK_DISABLE_FEATURE_DEPS_CHECK"), "Never bypass emulator feature dependencies")
        for key, supplied in (("GITHUB_SHA", args.source), ("GITHUB_RUN_ID", args.run), ("GITHUB_RUN_ATTEMPT", args.attempt)):
            require(not os.environ.get(key) or os.environ[key] == supplied, "Executor argument differs from actual " + key)
        require(args.run != self.app["workflowRun"], "A new diagnostic execution must not impersonate its original App run")
        for tool in (self.adb, args.sdk / "emulator/emulator", args.sdk / "build-tools/37.0.0/aapt", args.sdk / "build-tools/37.0.0/apksigner"):
            require(tool.is_file(), "Missing required installed SDK tool: " + str(tool))
        # Existing bootstrap/Guest ANGLE helpers call adb by name. Bind that name to
        # the same supplied SDK, without altering emulator/rendering flags.
        os.environ["PATH"] = str(self.adb.parent) + os.pathsep + os.environ.get("PATH", "")
        devices, _ = self.command("preexisting-adb-devices", [self.adb, "devices"])
        require(not any(line.strip() for line in devices.decode().splitlines()[1:]),
                "Requires an isolated runner with no existing adb devices; never touches another device")
        for port in (5554, 5555):
            with socket.socket() as probe:
                probe.bind(("127.0.0.1", port))
        apk = args.runtime_directory / "apks/app-release.apk"
        badging, _ = self.command("original-apk-badging", [args.sdk / "build-tools/37.0.0/aapt", "dump", "badging", apk])
        signature, _ = self.command("original-apk-signature", [args.sdk / "build-tools/37.0.0/apksigner", "verify", "--verbose", "--print-certs", apk], timeout=120)
        self.receipt["packageIdentity"] = verify_identity(badging.decode(), signature.decode(),
            dict(applicationId=PACKAGE, version="0.2.0-beta.1" if self.original257 else "0.1.0-beta.28",
                 versionCode="34" if self.original257 else "28", signerSha256=SIGNER), "preview")
        features = "GuestAngle,Vulkan"
        if args.disable_queue_submit_with_commands:
            features += ",-VulkanQueueSubmitWithCommands"
        command = [str(args.sdk / "emulator/emulator"), "-port", "5554", "-avd", args.avd,
            "-no-window", "-gpu", "lavapipe" if self.original257 else GPU_BACKEND, "-no-snapshot", "-noaudio", "-no-boot-anim", "-feature", features]
        self.receipt.update(emulatorCommand=command, avd=args.avd)
        with (args.output / "emulator.stdout.log").open("wb") as out, (args.output / "emulator.stderr.log").open("wb") as err:
            self.emulator = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=out, stderr=err,
                start_new_session=True, env=emulator_environment(os.environ))
        self.receipt["emulatorPid"] = self.emulator.pid
        wait_ready(self.emulator, args.output, self.receipt, self.check, capture_guest_logcat=True,
                   disable_guest_host_query_reset=True)
        name, _ = self.adb_command("owned-avd-name", "emu", "avd", "name")
        require(name.decode().strip().splitlines() == [args.avd, "OK"], "Booted serial does not identify the requested owned AVD")
        abi, _ = self.adb_command("owned-device-abi", "shell", "getprop", "ro.product.cpu.abi")
        require(abi.strip() == b"x86_64", "Requires the existing x86_64 image")
        self.receipt["guestGraphicsRoute"] = guest_angle_evidence(args.output, self.receipt,
            (args.output / "emulator.stdout.log").read_text(errors="replace"),
            vulkan_backend="lavapipe" if self.original257 else "swiftshader")
        if args.disable_queue_submit_with_commands:
            host = (args.output / "emulator.stdout.log").read_text(errors="replace")
            require(re.search(r"Feature 'VulkanQueueSubmitWithCommands' \([0-9]+\) is overridden to 'disabled'", host),
                    "SDK did not prove the requested queue-submit override disabled")
            require(not re.search(r"Feature 'VulkanQueueSubmitWithCommands' \([0-9]+\) is overridden to 'enabled'", host),
                    "SDK re-enabled the queue-submit feature")
            self.receipt["queueSubmitOverrideDisabled"] = True
        users, _ = self.adb_command("owned-device-users", "shell", "pm", "list", "users")
        require(re.findall(rb"UserInfo\{([0-9]+):", users) == [b"0"], "Requires a fresh single-user dedicated AVD")
        packages, _ = self.adb_command("preexisting-target-packages", "shell", "pm", "list", "packages", "-u", "--user", "0", PACKAGE)
        require(not packages.strip(), "Refuses an AVD with an existing Lite package; never clears/replaces user data")
        baseline_failure = None
        if self.original257:
            self.start_scene_logcat()
            try:
                raw, _ = self.adb_command("system-before-install", "exec-out", "screencap", "-p", timeout=30)
                require(raw.startswith(PNG) and len(raw) >= 45 and raw[12:16] == b"IHDR"
                        and raw.endswith(b"\x00\x00\x00\x00IEND\xaeB`\x82"), "System baseline did not return a complete PNG")
                baseline = args.output / "system-before-install.png"
                baseline.write_bytes(raw)
                self.receipt["systemBaseline"] = dict(file=baseline.name, sha256=sha(baseline), bytes=len(raw),
                    accessibilityAccess=False, beforeAppInstall=True)
            except (ValueError, subprocess.TimeoutExpired) as error:
                baseline_failure = error
                self.receipt["baselineFailure"] = dict(stage="system-before-install", errorType=type(error).__name__,
                    error=str(error), remainingShellBaselinesSkipped=True, surfaceEvidenceStillRequired=True)
                self.failure_scene257("baseline")
        self.install_attempted = True  # Own a partially completed install as well.
        self.adb_command("install-original-app", "install", "--no-streaming", str(apk), timeout=120)
        self.receipt["installedApp"] = self.installed_hash("installed-original")
        self.adb_command("set-locale-en-US", "shell", "cmd", "locale", "set-app-locales", PACKAGE, "--user", "0", "--locales", "en-US")
        locale, _ = self.adb_command("read-locale-en-US", "shell", "cmd", "locale", "get-app-locales", PACKAGE, "--user", "0")
        require("[en-US]" in locale.decode(), "Actual App locale readback is not en-US")
        launch, launch_row = self.adb_command("launcher-start-W", "shell", "am", "start", "-W", "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.LAUNCHER", "-n", LAUNCHER, timeout=60)
        require(re.search(rb"(?m)^Status: ok\s*$", launch) is not None and b"Error:" not in launch,
                "Official launcher did not return successful am start -W")
        self.receipt["launchMonotonicEnd"] = launch_row["monotonicEnd"]
        self.receipt["appPid"] = self.pid("launched-original-pid")
        self.receipt.update(status="capturing-before-accessibility", accessibilityDumpStarted=False)
        for offset, label in ((0, "before-immediate"), (1, "before-1s"), (5, "before-5s"), (15, "before-15s")):
            if baseline_failure is not None:
                break
            deadline = self.receipt["launchMonotonicEnd"] + offset
            while time.monotonic() < deadline:
                self.check()
                time.sleep(max(0, min(0.1, deadline - time.monotonic())))
            if self.original257:
                try:
                    self.frame(label, target=offset)
                except (ValueError, subprocess.TimeoutExpired) as error:
                    baseline_failure = error
                    self.receipt["baselineFailure"] = dict(stage=label, errorType=type(error).__name__, error=str(error),
                        remainingShellBaselinesSkipped=True, surfaceEvidenceStillRequired=True)
                    self.failure_scene257("baseline")
                    break
            else:
                self.frame(label, target=offset)
        if not self.original257:
            self.scene_diagnostics("before-accessibility")
        if args.disable_queue_submit_with_commands:
            own_log = (args.output / "before-accessibility-app-logcat.out").read_text(errors="replace")
            capabilities = [line for line in own_log.splitlines() if "HostConnection" in line and "HostComposition ext " in line]
            require(capabilities and all(re.search(r"\bANDROID_EMU_vulkan\b", line) for line in capabilities),
                    "App did not advertise the original Vulkan transport")
            require(not any(re.search(r"\bANDROID_EMU_vulkan_queue_submit_with_commands\b", line) for line in capabilities),
                    "App still advertises the queue-submit extension")
            require("Using the Impeller rendering backend (OpenGLES)" in own_log,
                    "App did not retain the original Impeller OpenGLES backend")
            self.receipt["queueSubmitControl"] = dict(overrideDisabled=True, extensionAdvertised=False,
                hostCompositionRows=len(capabilities), impellerBackend="OpenGLES")
        if self.original257:
            self.receipt["surfaceHostOrdering"] = "shell baselines, Java PixelCopy, Java compositor, then host hierarchy"
            try:
                self.surface_capture()
            except (ValueError, subprocess.TimeoutExpired) as error:
                if baseline_failure is not None:
                    self.receipt["surfaceSecondaryFailure"] = dict(errorType=type(error).__name__, error=str(error))
                    raise baseline_failure from error
                raise
            if baseline_failure is not None:
                raise baseline_failure
        available, availability = self.adb_command("uiautomator-availability", "shell", "command", "-v", "uiautomator", check=False)
        errors = (args.output / "uiautomator-availability.err").read_bytes()
        require(availability["exit"] == 0 or availability["exit"] in (1, 127) and not available.strip() and not errors.strip(),
                "uiautomator availability command failed, rather than reporting absence")
        if availability["exit"] == 0:
            require(re.fullmatch(rb"/system/bin/uiautomator\s*", available) is not None, "Unexpected uiautomator command path")
            self.receipt.update(status="capturing-after-accessibility", accessibilityDumpStarted=True,
                                accessibilityDumpMonotonicStart=time.monotonic())
            remote = "/data/local/tmp/lite" + str(self.app["runNumber"]) + "-pixel-" + uuid4().hex + ".xml"
            self.receipt["ownedHierarchyPath"] = remote
            self.adb_command("uiautomator-single-dump", "shell", "uiautomator", "dump", remote, timeout=45)
            hierarchy, _ = self.adb_command("uiautomator-hierarchy", "exec-out", "cat", remote)
            hierarchy_root = ET.fromstring(hierarchy)
            require(hierarchy_root.tag == "hierarchy", "uiautomator did not return an actual hierarchy XML")
            self.receipt["hierarchyContainsTargetPackage"] = any(
                node.get("package") == PACKAGE for node in hierarchy_root.iter("node"))
            if self.original257:
                self.receipt["hostHierarchyAfterInstrumentation"] = True
                self.receipt["hostHierarchyIsSameSceneProof"] = False
                self.adb_command("post-surface-app-pid", "shell", "pidof", PACKAGE, timeout=3, check=False)
                self.adb_command("after-accessibility-system-logcat", "logcat", "-b", "all", "-d", "-v", "threadtime", timeout=10)
            else:
                self.frame("after-uiautomator")
                self.scene_diagnostics("after-accessibility")
            self.adb_command("remove-owned-hierarchy", "shell", "rm", remote)
            self.receipt["ownedHierarchyRemoved"] = True
        else:
            self.receipt["hierarchyUnavailable"] = True
        if args.surface_test_apk and not self.original257:
            self.surface_capture()
        self.receipt["installedAppAfter"] = self.installed_hash("final-original")
        require(sha(apk) == self.apk_sha and sha(args.runtime_directory / "BUILD_INFO.json") == self.info_sha,
                "Original App/BUILD_INFO changed during capture")
        self.receipt.update(status="collected-diagnostic-only", collectionComplete=True)

    def run(self):
        code = 1
        for name in (signal.SIGINT, signal.SIGTERM):
            self.handlers[name] = signal.signal(name, self.interrupt)
        try:
            self.execute()
            code = 0
        except BaseException as error:
            self.receipt.update(status="cancelled" if self.receipt["interruptSignals"] else "failed",
                                errorType=type(error).__name__, error=str(error), collectionComplete=False)
        finally:
            self.cleaning = True
            package_cleaned = not self.install_attempted
            surface_package_cleaned = not self.surface_install_attempted
            if self.emulator is not None and self.emulator.poll() is None:
                if code and self.original257:
                    self.failure_scene257()
                if self.surface_evidence_remote and not any(row["label"] == "surface-evidence-pull" for row in self.receipt["commands"]):
                    try:
                        _, pulled = self.adb_command("surface-evidence-pull", "pull", self.surface_evidence_remote,
                            self.args.output / "surface-capture", timeout=45, check=False)
                        self.receipt["surfaceEvidencePullExit"] = pulled["exit"]
                    except BaseException as error:
                        self.receipt.setdefault("diagnosticErrors", []).append(repr(error))
                if code and self.receipt.get("appPid"):
                    for label, command in (("failure-app-logcat", ["logcat", "-b", "all", "-d", "-v", "threadtime", "--pid=" + str(self.receipt["appPid"])]),
                                           ("failure-window", ["shell", "dumpsys", "window", "windows"])):
                        try:
                            self.adb_command(label, *command, timeout=15)
                        except BaseException as error:
                            self.receipt.setdefault("diagnosticErrors", []).append(repr(error))
                if self.surface_install_attempted:
                    for label, command in (("cleanup-stop-surface-runner", ["shell", "am", "force-stop", "dev.aerove.lite.test"]),
                                           ("cleanup-uninstall-surface-runner", ["uninstall", "dev.aerove.lite.test"])):
                        try:
                            self.adb_command(label, *command, timeout=45)
                            if label == "cleanup-uninstall-surface-runner":
                                surface_package_cleaned = True
                        except BaseException as error:
                            self.receipt["cleanupErrors"].append(repr(error))
                if self.install_attempted:
                    for label, command in (("cleanup-stop-owned-app", ["shell", "am", "force-stop", PACKAGE]),
                                           ("cleanup-uninstall-owned-app", ["uninstall", PACKAGE])):
                        try:
                            self.adb_command(label, *command, timeout=45)
                            if label == "cleanup-uninstall-owned-app":
                                package_cleaned = True
                        except BaseException as error:
                            self.receipt["cleanupErrors"].append(repr(error))
                if self.receipt.get("ownedHierarchyPath") and not self.receipt.get("ownedHierarchyRemoved"):
                    try:
                        self.adb_command("cleanup-owned-hierarchy", "shell", "rm", "-f", self.receipt["ownedHierarchyPath"])
                    except BaseException as error:
                        self.receipt["cleanupErrors"].append(repr(error))
            if self.scene_logcat is not None:
                try:
                    self.receipt["sceneLogcat"]["runningAtCleanup"] = self.scene_logcat.poll() is None
                    if self.scene_logcat.poll() is None:
                        self.scene_logcat.terminate()
                        try:
                            self.scene_logcat.wait(timeout=5)
                        except subprocess.TimeoutExpired:
                            self.scene_logcat.kill()
                            self.scene_logcat.wait(timeout=5)
                    self.receipt["sceneLogcat"].update(monotonicEnd=time.monotonic(), exit=self.scene_logcat.returncode,
                        ownerReaped=True)
                except BaseException as error:
                    self.receipt["cleanupErrors"].append(repr(error))
            if self.emulator is not None:
                try:
                    code = finish_emulator(self.emulator, self.receipt, code)
                except BaseException as error:
                    code = 1; self.receipt["cleanupErrors"].append(repr(error))
            if not package_cleaned:
                self.receipt["cleanupErrors"].append("Owned install could not be confirmed removed from the dedicated AVD")
            if not surface_package_cleaned:
                self.receipt["cleanupErrors"].append("Owned Surface runner install could not be confirmed removed")
            if self.receipt["interruptSignals"] or self.receipt["cleanupErrors"]:
                code = 1
            self.receipt.update(result=code, packageCleanupConfirmed=package_cleaned,
                                surfaceRunnerCleanupConfirmed=surface_package_cleaned,
                                cleanupStatus="failed" if self.receipt["cleanupErrors"] else "complete")
            if code and self.receipt["status"] == "collected-diagnostic-only":
                self.receipt["status"] = "cleanup-failed"
            self.receipt["files"] = [dict(path=p.relative_to(self.args.output).as_posix(), bytes=p.stat().st_size, sha256=sha(p))
                for p in sorted(self.args.output.rglob("*")) if p.is_file() and p.name != "PIXEL-DIAGNOSTIC.json"]
            self.finalizing = True
            if self.receipt["interruptSignals"]:
                code = 1
                self.receipt.update(status="cancelled", result=1, collectionComplete=False)
            write_owner_receipt(self.args.output / "PIXEL-DIAGNOSTIC.json", self.receipt)
            for name, handler in self.handlers.items():
                signal.signal(name, handler)
        return code


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("runtime-directory", "archive", "artifact-api", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    for name in ("source", "run", "attempt"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or "/missing-sdk"))
    parser.add_argument("--avd", default="test")
    parser.add_argument("--disable-queue-submit-with-commands", action="store_true")
    parser.add_argument("--original-run-number", type=int, choices=(236, 257), default=236)
    parser.add_argument("--surface-test-apk", type=Path)
    parser.add_argument("--surface-test-info", type=Path)
    args = parser.parse_args()
    require(args.original_run_number != 257 or args.surface_test_apk and args.surface_test_info
            and not args.disable_queue_submit_with_commands, "Fixed257 supports only the unchanged Surface diagnostic route")
    require(bool(args.surface_test_apk) == bool(args.surface_test_info), "Surface runner APK and actual build metadata must be supplied together")
    require(not args.surface_test_apk or not args.disable_queue_submit_with_commands,
            "Surface readback uses the unchanged original graphics recipe; the queue-submit control has ended")
    require(re.fullmatch(r"[0-9a-f]{40}", args.source) and args.run.isdecimal() and int(args.run) > 0
            and args.attempt.isdecimal() and int(args.attempt) > 0 and re.fullmatch(r"[A-Za-z0-9_.-]+", args.avd),
            "Requires truthful executor source/run/attempt and a named dedicated AVD")
    args.output.mkdir(parents=True, exist_ok=False)
    os.nice(19) if sys.platform == "linux" else None
    return Diagnostic(args).run()


if __name__ == "__main__":
    raise SystemExit(main())
