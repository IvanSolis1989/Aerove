"""One owned emulator, one original app, one target instrumentation; no build producer."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import shutil
import subprocess
import sys
import time
from uuid import uuid4

from flutter_android_qa import Producer, PACKAGE, CLASS, LAUNCHER, idle, renderer, save, sha, validate_binding
from android_acceptance_verify import (CONTRACT, checkpoint, load, platform_binding, platform_log, require,
                                       separate_uid, ui_capture)
from instrumentation_shards import parse_log
from verify_apk import signing_certificate

TEST_PACKAGE = "dev.aerove.lite.platform.test"
RUNNER = TEST_PACKAGE + "/dev.aerove.lite.platformqa.AcceptanceCheckpointRunner"


class Acceptance(Producer):
    def __init__(self, args):
        super().__init__(args)
        self.receipt.update(producer="android_acceptance.py", phase=args.phase,
                            fullAcceptance=False, nativeMeowAccepted=False, tunSeparateUidAccepted=False,
                            canonicalSharedEvidenceVerified=False, opticalDisplacementVerified=False)
        self.fixture = None
        self.fixture_streams = []
        self.serial_number = 0
        self.native_captures = []
        self.cleaning = False

    def interrupt(self, signum, _frame):
        if self.cancelled:
            return
        self.cancelled = True
        self.receipt.setdefault("interruptSignals", []).append(signum)
        if not self.cleaning:
            raise InterruptedError("Acceptance cancelled before cleanup")
        self.receipt["cleanupCancellationObserved"] = True

    def unique(self, prefix):
        self.serial_number += 1
        return prefix + "-" + str(self.serial_number)

    def remote(self, token):
        return f"/sdcard/Android/data/{PACKAGE}/files/flutter-production-qa/{token}"

    def base_args(self, method, token, info, extra=None):
        values = dict(qaToken=token, apkSha256=info["apk_sha256"], source=info["source"], tree=info["tree"],
                      workflow=info["workflow"], runAttempt=str(info["run_attempt"]),
                      meowOverlaySha256=info["meow_overlay_sha256"],
                      meowFileManifestSha256=info["meow_file_manifest_sha256"])
        values.update(extra or {})
        result = ["shell", "am", "instrument", "-w", "-r", "-e", "class", CLASS + "#" + method]
        for key, value in values.items():
            result += ["-e", key, str(value)]
        return result + [RUNNER]

    def pull(self, method, token):
        destination = self.args.output / method
        self.adb_command(self.unique(method + "-pull"), "pull", self.remote(token), str(destination), timeout=90)
        return destination

    def ui(self, method, info, extra=None):
        token = "qa-" + uuid4().hex
        log = self.normal_instrument(method, *self.base_args(method, token, info, extra)).decode(errors="strict")
        directory = self.pull(method, token)
        records = parse_log(self.args.output / (method + ".out"))
        require(len(records) == 1 and records[0]["className"] + "#" + records[0]["testName"] == CLASS + "#" + method and
                records[0]["status"] == "passed" and re.findall(r"OK \((\d+) tests?\)", log) == ["1"],
                "Each UI invocation must finish precisely its one selected method")
        receipt, natives = ui_capture(directory, method, info, token, profile_proof=getattr(self,"profile_cancel_proof",None))
        self.native_captures.extend(natives)
        process_log = self.adb_command(method + "-renderer", "logcat", "-d", "-v", "threadtime", timeout=45).decode(errors="replace")
        actual_renderer = renderer(process_log, receipt["pid"])
        self.check_process_log(process_log, receipt["pid"])
        return dict(method=method, qaToken=token, directory=method, pid=receipt["pid"], renderer=actual_renderer,
                    receiptSha256=sha(directory / "receipt.json")), receipt, natives

    def running_boundary(self, info, extra):
        method = "prepareImportedRunningSessionForProcessRestart"
        token = "qa-" + uuid4().hex
        command = self.adb + self.base_args(method, token, info, dict(extra, hostRestartBoundary="true"))
        out = (self.args.output / (method + ".out")).open("wb")
        err = (self.args.output / (method + ".err")).open("wb")
        row = dict(label=method, command=command, prepareDeadlineSeconds=180, uiTerminationBarrierMs=45000, expectedTermination=True)
        self.receipt["commands"].append(row)
        child = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=out, stderr=err)
        self.child = child
        started = time.monotonic()
        try:
            prepared = None
            while time.monotonic() - started < 180:
                if self.cancelled:
                    raise InterruptedError("Cancelled during running restart boundary")
                require(child.poll() is None, "Preparer ended before the host-owned process boundary")
                raw = self.adb_command(self.unique("prepared-read"), "exec-out", "cat", self.remote(token) + "/receipt.json",
                                       timeout=3, check=False)
                try:
                    value = json.loads(raw)
                    if value.get("status") == "awaiting-host-process-termination":
                        prepared = value
                        break
                except (ValueError, UnicodeError):
                    pass
                time.sleep(0.2)  # Bounded host file handshake, no application scheduler/polling.
            require(prepared is not None, "No prepared running checkpoint before the bounded host deadline")
            directory = self.pull(method, token)
            receipt, natives = ui_capture(directory, method, info, token, prepared=True)
            self.native_captures.extend(natives)
            native = natives[-1]
            pid = str(receipt["pid"])
            require(pid.isdecimal() and int(pid) > 0, "Invalid prepared PID")
            live = self.adb_command("boundary-pidof", "shell", "pidof", PACKAGE).decode().strip().split()
            require(live == [pid], "Prepared process is not still the sole actual target PID")
            status = self.adb_command("boundary-process-status", "shell", "cat", "/proc/" + pid + "/status").decode()
            stat = self.adb_command("boundary-process-stat", "shell", "cat", "/proc/" + pid + "/stat").decode()
            require(re.search(r"^Uid:\s+" + str(native["targetUid"]) + r"\s", status, re.M), "Prepared PID UID differs from attested target")
            start_ticks = stat[stat.rfind(")") + 2:].split()[19]
            connectivity = self.adb_command("boundary-connectivity", "shell", "dumpsys", "connectivity").decode()
            net_id = int(native["networkHandles"][0]) >> 32
            blocks = re.split(r"(?=NetworkAgentInfo\{)", connectivity)
            require(any(re.search(r"network\{" + str(net_id) + r"\}", block) and
                        re.search(r"OwnerUid:\s*" + str(native["targetUid"]) + r"\b", block)
                        for block in blocks if block.startswith("NetworkAgentInfo{")),
                    "Host cannot confirm the currently owned attested OS VPN")
            uptime = float(self.adb_command("boundary-uptime", "shell", "cat", "/proc/uptime").decode().split()[0]) * 1000
            require(0 <= uptime - native["attestedElapsedRealtimeMs"] <= 10_000, "Running attestation is stale at process boundary")
            require(child.poll() is None, "Instrumentation already ended; force-stop would not prove running recovery")
            last_stat = self.adb_command("boundary-last-process-stat", "shell", "cat", "/proc/" + pid + "/stat").decode()
            require(last_stat[last_stat.rfind(")") + 2:].split()[19] == start_ticks, "Prepared PID identity changed before the owned kill")
            process_log = self.adb_command(method + "-renderer", "logcat", "-d", "-v", "threadtime", timeout=10).decode(errors="replace")
            actual_renderer = renderer(process_log, int(pid)); self.check_process_log(process_log, int(pid))
            require(child.poll() is None and self.adb_command("boundary-last-pidof", "shell", "pidof", PACKAGE).decode().strip().split() == [pid],
                    "Preparer is no longer alive immediately before the host boundary")
            prepared_sha = sha(directory / "receipt.json")
            latest_prepared = self.adb_command("boundary-last-prepared-receipt", "exec-out", "cat", self.remote(token) + "/receipt.json", timeout=3)
            require(hashlib.sha256(latest_prepared).hexdigest() == prepared_sha, "Prepared receipt changed before the host-owned kill")
            latest_uptime = float(self.adb_command("boundary-last-uptime", "shell", "cat", "/proc/uptime", timeout=3).decode().split()[0]) * 1000
            require(0 <= latest_uptime - receipt["preparedElapsedRealtimeMs"] <= 35_000,
                    "Host boundary did not occur within the still-blocked UI preparer lifetime")
            self.adb_command("boundary-force-stop-owned-target", "shell", "am", "force-stop", PACKAGE)
            require(not self.adb_command("boundary-confirm-pid-gone", "shell", "pidof", PACKAGE, check=False).strip(),
                    "Target PID remained after owned process termination")
            row["exit"] = child.wait(timeout=15)
            text = (self.args.output / (method + ".out")).read_text(errors="replace")
            require("OK (1 test)" not in text, "Expected termination producer cannot be recorded as JUnit pass")
            boundary = dict(schema=1, kind="expected-host-process-termination", source=info["source"], tree=info["tree"],
                            apkSha256=info["apk_sha256"], qaToken=token, pid=int(pid), targetUid=native["targetUid"],
                            startTicks=start_ticks, preparedReceiptSha256=prepared_sha,
                            nativeEvidenceSha256=sha(directory / "native-state" / (native["nonce"] + ".json")),
                            connectivitySha256=sha(self.args.output / "boundary-connectivity.out"),
                            processWasAlive=True, vpnWasOwned=True, hostForcedStop=True, pidGone=True,
                            expectedInterruption=True, junitPass=False)
            save(self.args.output / "PROCESS-BOUNDARY.json", boundary)
            return dict(method=method, qaToken=token, directory=method, pid=int(pid), renderer=actual_renderer, receiptSha256=prepared_sha), boundary
        finally:
            if child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    child.kill(); child.wait(timeout=5)
            row["exit"] = child.returncode
            row["elapsedSeconds"] = round(time.monotonic() - started, 3)
            self.child = None
            out.close(); err.close()

    def fixture_start(self):
        owner_token = str(uuid4())
        ready_file = self.args.output / "fixture-ready.json"
        fixture_source = Path(__file__).with_name("fixture_server.py")
        command = [sys.executable, str(fixture_source), "--ready-file", str(ready_file), "--owner-token", owner_token,
                   "--profile-events", str(self.args.output / "profile-events.jsonl")]
        if os.environ.get("SMART_CONFIG_MEOW_FIXTURE_DIR"):
            command += ["--smart-config-fixture", os.environ["SMART_CONFIG_MEOW_FIXTURE_DIR"]]
        self.fixture_streams = [(self.args.output / "fixture.out").open("wb"), (self.args.output / "fixture.err").open("wb")]
        self.fixture = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=self.fixture_streams[0], stderr=self.fixture_streams[1])
        self.receipt["fixtureOwner"] = dict(pid=self.fixture.pid, command=command)
        # A real response proves readiness; a PID heartbeat never does.
        import urllib.request
        deadline = time.monotonic() + 10
        token = str(uuid4())
        while True:
            require(self.fixture.poll() is None, "Owned fixture exited before readiness")
            try:
                if not ready_file.is_file():
                    raise OSError("Owned fixture has not actually bound its socket")
                ready = load(ready_file)
                require(ready == dict(schema=1, pid=self.fixture.pid, ownerToken=owner_token, boundAddress="0.0.0.0",
                                      boundPort=18080, sourceSha256=sha(fixture_source)), "Fixture readiness is not from the actual owned bound producer")
                with urllib.request.urlopen("http://127.0.0.1:18080/" + token, timeout=1) as response:
                    require(response.read() == ("AEROVE_LITE_FIXTURE:" + token).encode(), "Wrong controlled echo fixture")
                    require(self.fixture.poll() is None, "Owned fixture died during readiness")
                    self.receipt["fixtureOwner"]["readySha256"] = sha(ready_file)
                    break
            except OSError:
                require(time.monotonic() < deadline, "Fixture never became ready")
                time.sleep(0.1)

    def run(self):
        result = 1
        for name in (signal.SIGINT, signal.SIGTERM):
            self.previous_signals[name] = signal.signal(name, self.interrupt)
        try:
            from emulator_current import validate_renderer_proof
            proof_path = Path(os.environ["AEROVE_CURRENT_RENDERER_PROOF"])
            proof = validate_renderer_proof(proof_path)
            require(self.args.serial == proof["deviceSerial"] == os.environ.get("ANDROID_SERIAL") and
                    self.args.serial.startswith("emulator-"), "Dedicated existing emulator owner required")
            info = load(self.args.build_info)
            identity = validate_binding(self.args.apk, self.args.test_apk, info, proof)
            platform = platform_binding(proof["runtimeDirectory"], info)
            require(platform.resolve() == self.args.platform_apk.resolve(), "Platform APK argument differs from sealed runtime")
            save(self.args.output / "BUILD_INFO.json", info)
            platform_build = load(Path(proof["runtimeDirectory"]) / "platform-runner-build.json")
            self.receipt["sharedUiReuse"] = platform_build["sharedUiReuse"]
            self.receipt.update(identity=identity, apkSha256=sha(self.args.apk), platformApkSha256=sha(platform),
                                runtimeManifestSha256=proof["runtimeManifestSha256"], ownerProofSha256=sha(proof_path),
                                deviceSerial=self.args.serial)
            if self.args.phase == "flutter-full":
                from profile_cancel_verify import prerequisite
                source_prerequisite=Path(proof["runtimeDirectory"])/"profile-cancel-prerequisite"
                self.profile_cancel_proof=prerequisite(source_prerequisite)
                require(platform_build.get("profileCancelPrerequisite")==self.profile_cancel_proof,"Platform build does not bind the verified cancellation source")
                shutil.copytree(source_prerequisite,self.args.output/"profile-cancel-prerequisite")
            self.owner_verified = True
            for package in (PACKAGE, TEST_PACKAGE):
                require(b"package:" not in self.adb_command("preexisting-" + package, "shell", "pm", "path", package, check=False),
                        "Fresh dedicated emulator required; existing user package will not be replaced")
            for label, path in (("app", self.args.apk), ("platform", platform)):
                signature = self.command(label + "-signature", [self.args.apksigner, "verify", "--verbose", "--print-certs", path]).decode()
                require(signing_certificate(signature) == info["signer_sha256"],
                        "Original app and platform runner must share the sealed signer")
            for package, path in ((PACKAGE, self.args.apk), (TEST_PACKAGE, platform)):
                self.installed.append(package)
                self.adb_command("install-" + package, "install", *(["-t"] if package == TEST_PACKAGE else []),
                                 "--no-streaming", str(path), timeout=120)
                self.installed_hash(package, package, sha(path))
            self.adb_command("locale-en", "shell", "cmd", "locale", "set-app-locales", PACKAGE, "--user", "0", "--locales", "en-US")
            self.fixture_start()
            contract = load(CONTRACT)
            if self.args.phase == "flutter-platform":
                require(bool(os.environ.get("SMART_CONFIG_MEOW_FIXTURE_DIR")), "Pinned Smart fixture must be prepared before platform suite")
                self.adb_command("platform-tests", "shell", "am", "instrument", "-w", "-r", "-e", "class",
                                 ",".join(contract["platformSelectors"]), "-e", "isolatedBackgroundPolicy", "true",
                                 "-e", "smartConfigFixtureUrl", "http://10.0.2.2:18080/smart-config-meow/", RUNNER, timeout=900)
                self.receipt["platform"] = platform_log(self.args.output / "platform-tests.out")
            else:
                rows = []
                first, receipt, _ = self.ui("consentNavigationTheme12", info); rows.append(first)
                log = self.adb_command("first-logcat", "logcat", "-d", "-v", "threadtime").decode(errors="replace")
                self.receipt["appRenderer"] = renderer(log, receipt["pid"]); self.check_process_log(log, receipt["pid"])
                self.adb_command("consent-process-stop", "shell", "am", "force-stop", PACKAGE)
                second, receipt, _ = self.ui("persistedConsentAfterProcessRestart", info); rows.append(second)
                require(first["pid"] != second["pid"], "Consent recovery reused original process")
                profile = str(uuid4())
                extra = dict(fixtureUrl="http://10.0.2.2:18080", profileUrl=f"http://10.0.2.2:18080/flutter-profile/{profile}.yaml?mixedPort=18081",
                             profileLabel="FlutterAcceptance-" + profile[:8], mixedProxyPort="18081")
                _, _, natives = self._record_ui(rows, "profileImportApplyVpnNetworkAndBackgroundResume", info, extra)
                active = [n for n in natives if n.get("expectedVpn") is True]
                self.adb_command("revoke-vpn-authorization", "shell", "appops", "set", PACKAGE, "ACTIVATE_VPN", "default")
                self.adb_command("readback-vpn-authorization", "shell", "appops", "get", PACKAGE, "ACTIVATE_VPN")
                self._record_ui(rows, "cancelVpnAuthorizationThenRecover", info, extra)
                prepared, boundary = self.running_boundary(info, extra); rows.append(prepared)
                recovered, _, _ = self._record_ui(rows, "persistedImportedProfileAfterProcessRestart", info,
                    dict(extra, previousQaToken=prepared["qaToken"], previousBoundarySha256=boundary["preparedReceiptSha256"], restartExpectedState="stopped"))
                require(recovered["pid"] != prepared["pid"], "Functional process recovery reused killed PID")
                pair=str(uuid4())
                cancel_args=dict(profileCancelPrerequisiteGreen="true",
                    profileCancelImplementationManifestSha256=self.profile_cancel_proof["implementationManifestSha256"],
                    profileCancelGreenEvidenceSha256=self.profile_cancel_proof["greenEvidenceSha256"],
                    profileCancelServerSha256=self.profile_cancel_proof["serverSourceSha256"])
                for key in ("A","B"):
                    cancel_args["cancelProfile"+key+"Label"]="CancelAcceptance-"+pair[:8]+"-"+key
                    cancel_args["cancelProfile"+key+"Url"]="http://10.0.2.2:18080/profile-cancel/"+pair+"/"+key+".yaml"
                self._record_ui(rows,"profileUpdateCancelBatchAndRetry",info,cancel_args)
                self.receipt["profileUpdateCancelVerified"]=True
                self.adb_command("runtime-notification-permission", "shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
                self._record_ui(rows, "listenerRebindFailureRecoveryAndNotificationLifecycle", info,
                    dict(extra, rebindMixedPort="18082", occupiedMixedPort="18083"))
                self.ordinary_owner(rows, info, extra)
                final_log = self.adb_command("functional-final-process-log", "logcat", "-d", "-v", "threadtime", timeout=45).decode(errors="replace")
                for pid in {row["pid"] for row in rows}:
                    self.check_process_log(final_log, pid)
                separate = True
                for native in [n for n in self.native_captures if n.get("expectedVpn") is True]:
                    try:
                        separate_uid(native)
                    except ValueError:
                        separate = False
                self.receipt.update(uiInvocations=rows, normalUiSelectors=contract["normalUiSelectors"],
                    expectedTerminationSelector=contract["expectedTerminationSelector"], expectedTerminationVerified=True,
                    nativeOwnerAttestationsVerified=True, tunSeparateUidAccepted=separate, matrixImages=12,
                    unresolvedCoverage=["physical Wi-Fi exclusion park/resume",
                                        "shared client cancellation/recovery coverage mapping requires execution-bound canonical proof"])
            self.installed_hash("final-app", PACKAGE, info["apk_sha256"])
            self.receipt.update(status="executed-pending-complete-acceptance", executionPassed=True)
            result = 0
        except BaseException as error:
            self.receipt.update(status="cancelled" if self.cancelled else "failed", executionPassed=False, error=repr(error))
        finally:
            self.cleaning = True
            self.receipt["stage"] = "cleanup"
            if self.fixture is not None:
                try:
                    self.fixture.terminate()
                    try:
                        self.fixture.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        self.fixture.kill(); self.fixture.wait(timeout=5)
                    self.receipt["fixtureOwner"]["exit"] = self.fixture.returncode
                except BaseException as error:
                    self.receipt.setdefault("cleanupErrors", []).append(repr(error))
            for stream in self.fixture_streams:
                try:
                    stream.close()
                except BaseException as error:
                    self.receipt.setdefault("cleanupErrors", []).append(repr(error))
            if self.owner_verified:
                for label, command in (("final-logcat", ["logcat", "-d", "-v", "threadtime"]),
                                       ("final-connectivity", ["shell", "dumpsys", "connectivity"])):
                    try:
                        self.adb_command(label, *command, timeout=20, check=False, cancelling=True)
                    except BaseException as error:
                        self.receipt.setdefault("diagnosticErrors", []).append(repr(error))
            for package in reversed(self.installed):
                try:
                    self.adb_command("cleanup-stop-" + package, "shell", "am", "force-stop", package, check=False, cancelling=True)
                    self.adb_command("cleanup-uninstall-" + package, "uninstall", package, timeout=45, cancelling=True)
                except BaseException as error:
                    self.receipt.setdefault("cleanupErrors", []).append(repr(error))
            if self.cancelled:
                self.receipt.update(status="cancelled", executionPassed=False)
            self.receipt["cleanupStatus"] = "failed" if self.receipt.get("cleanupErrors") else "complete"
            files = [p for p in self.args.output.rglob("*") if p.is_file() and p.name != "android-acceptance.json"]
            self.receipt["files"] = [dict(path=p.relative_to(self.args.output).as_posix(), sha256=sha(p), bytes=p.stat().st_size) for p in sorted(files)]
            save(self.args.output / "android-acceptance.json", self.receipt)
            for name, handler in self.previous_signals.items():
                signal.signal(name, handler)
        return 1 if self.cancelled or self.receipt.get("cleanupErrors") else result

    def ordinary_owner(self, rows, info, extra):
        from build_process_owner_fixture import PACKAGE as OWNER_PACKAGE, build
        from process_owner_device_setup import EchoScope
        from runtime_device_verify import OWNER_METHOD, verify_host_owner
        deadline = time.time() + 120
        def build_command(command, limit):
            return self.command(self.unique("owner-fixture-build"), command,
                                timeout=max(0.01, limit - time.time())).decode()
        apk, build_receipt = build(self.args.output / "ordinary-owner-build", deadline, build_command)
        require(build_receipt["files"] == load(CONTRACT)["ownerFixtureSources"], "Ordinary fixture source is not the original current-source dependency")
        require(b"package:" not in self.adb_command("preexisting-ordinary-owner", "shell", "pm", "path", OWNER_PACKAGE, check=False),
                "Dedicated emulator required; never replace an existing independent fixture")
        self.installed.append(OWNER_PACKAGE)
        self.adb_command("install-ordinary-owner", "install", "-t", "--no-streaming", str(apk), timeout=120)
        installed = self.installed_hash("ordinary-owner", OWNER_PACKAGE, sha(apk))
        echoes = []
        echo = EchoScope(echoes)
        try:
            tcp_port, udp_port = echo.start()
            _, _, natives = self._record_ui(rows, OWNER_METHOD, info,
                dict(extra, ownerTcpPort=tcp_port, ownerUdpPort=udp_port))
        finally:
            echo.close()  # A returned receipt requires actual thread/socket join, never a caller flag.
        host = dict(source=info["source"], tree=info["tree"], workflow=info["workflow"], runAttempt=str(info["run_attempt"]),
                    installedPackage=OWNER_PACKAGE, apkSha256=sha(apk), installedApkSha256=installed["sha256"],
                    build=build_receipt, echo=list(echoes), echoJoined=True)
        save(self.args.output / "ordinary-owner-host.json", host)
        verify_host_owner(self.args.output, natives, info)


    def _record_ui(self, rows, method, info, extra):
        row, receipt, natives = self.ui(method, info, extra); rows.append(row)
        return row, receipt, natives


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", choices=("flutter-platform", "flutter-full"), required=True)
    for name in ("apk", "test-apk", "platform-apk", "build-info", "adb", "apksigner", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    idle()
    return Acceptance(args).run()


if __name__ == "__main__":
    raise SystemExit(main())
