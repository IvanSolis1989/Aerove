import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import threading
import urllib.request
import urllib.error
from http.server import ThreadingHTTPServer
import fixture_server
from unittest.mock import patch
import os
import emulator_current
from android_acceptance import Acceptance
from argparse import Namespace
from uuid import uuid4

import android_acceptance_verify as v
import seal_platform_runner as seal


def log(selectors, skipped=False):
    lines = []
    for index, selector in enumerate(selectors, 1):
        cls, method = selector.split("#")
        for code in (1, -3 if skipped and index == 1 else 0):
            lines += ["INSTRUMENTATION_STATUS: id=AndroidJUnitRunner", "INSTRUMENTATION_STATUS: class=" + cls,
                      "INSTRUMENTATION_STATUS: test=" + method, "INSTRUMENTATION_STATUS: numtests=" + str(len(selectors)),
                      "INSTRUMENTATION_STATUS: current=" + str(index), "INSTRUMENTATION_STATUS_CODE: " + str(code)]
    return "\n".join(lines + ["INSTRUMENTATION_RESULT: stream=", "OK (" + str(len(selectors)) + " tests)", "INSTRUMENTATION_CODE: -1"]) + "\n"


class FixedSelectionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.contract = v.load(v.CONTRACT)

    def test_legacy_fresh_install_never_removes_an_unowned_package(self):
        platform = self.root / "platform.apk"
        platform.write_bytes(b"host-only runner fixture")
        producer = Acceptance(Namespace(adb=Path("adb"), serial="emulator-5554", output=self.root, phase="flutter-platform"))
        with patch.object(producer, "adb_command") as command, self.assertRaisesRegex(ValueError, "owned installation"):
            producer.fresh_legacy_install(platform, dict(apk_sha256="a"*64), 2)
        command.assert_not_called()
        self.assertEqual(producer.installed, [])

    def test_legacy_interrupted_reinstall_tracks_only_the_new_owned_attempt(self):
        from android_acceptance import PACKAGE, TEST_PACKAGE
        app, platform = self.root / "app.apk", self.root / "platform.apk"
        app.write_bytes(b"host-only app fixture")
        platform.write_bytes(b"host-only runner fixture")
        producer = Acceptance(Namespace(adb=Path("adb"), serial="emulator-5554", output=self.root,
            phase="flutter-platform", apk=app))
        producer.installed = [PACKAGE, TEST_PACKAGE]
        def adb(_label, *arguments, **_options):
            if arguments[0] == "install" and arguments[-1] == str(app):
                raise InterruptedError("installation interrupted after the owned attempt was recorded")
            return b""
        with patch.object(producer, "installed_hash"), patch.object(producer, "adb_command", side_effect=adb) as command:
            with self.assertRaises(InterruptedError):
                producer.fresh_legacy_install(platform, dict(apk_sha256=v.sha(app)), 2)
        self.assertEqual(producer.installed, [PACKAGE])
        self.assertEqual([call.args[2] for call in command.call_args_list if call.args[1] == "uninstall"],
                         [TEST_PACKAGE, PACKAGE])

    def test_legacy_invocation_rejects_skips_and_a_different_single_method(self):
        selector = self.contract["legacySelectors"][0]
        path = self.root / "legacy.out"
        path.write_text(log([selector]))
        v.legacy_log(path, selector)
        with self.assertRaisesRegex(ValueError, "selected real Android method"):
            v.legacy_log(path, self.contract["legacySelectors"][1])
        path.write_text(log([selector], skipped=True))
        with self.assertRaisesRegex(ValueError, "selected real Android method"):
            v.legacy_log(path, selector)

    def test_legacy_runner_assets_require_the_exact_same_source_and_payload(self):
        import zipfile
        from ci_artifacts import seal as seal_transport
        from lite_release import PromotionError
        fixture = self.root / "fixture"
        retained = fixture / "retained-export"
        payload = retained / "payload/profiles/index.json"
        payload.parent.mkdir(parents=True)
        payload.write_bytes(b"host-only original source bytes")
        manifest = retained / "manifest.json"
        manifest.write_text(json.dumps(dict(files=[dict(path="profiles/index.json", bytes=payload.stat().st_size, sha256=v.sha(payload))])))
        (fixture / "fixture.json").write_text(json.dumps(dict(manifest="/runner/native-migration/retained-export/manifest.json", sha256=v.sha(manifest))))
        identity = dict(sourceRevision="1"*40, sourceTree="2"*40, workflowRun="123", runNumber=9, runAttempt=1)
        seal_transport(fixture, "shared-fixture", identity)
        info = dict(source="1"*40, tree="2"*40, workflow="https://github.com/IvanSolis1989/Aerove/actions/runs/123", run_number=9, run_attempt=1)
        defined = "C d 1 1 200 dev.aerove.lite.platformqa.NativeLegacyMigrationFixtureTest\n"
        test = self.root / "host-only-assets.zip"
        def archive(changed=False):
            with zipfile.ZipFile(test, "w") as output:
                output.write(manifest, "assets/native-migration-fixture/manifest.json")
                output.writestr("assets/native-migration-fixture/payload/profiles/index.json",
                                b"changed source bytes" if changed else payload.read_bytes())
        archive()
        actual = seal.native_migration_binding(info, test, defined, fixture)
        self.assertEqual(actual["producerSource"], "1"*40)
        self.assertEqual(actual["manifestSha256"], v.sha(manifest))
        with self.assertRaisesRegex(PromotionError, "source/run/attempt identity mismatch"):
            seal.native_migration_binding(dict(info, source="3"*40), test, defined, fixture)
        archive(changed=True)
        with self.assertRaisesRegex(ValueError, "fixture asset changed"):
            seal.native_migration_binding(info, test, defined, fixture)

    def test_fixed257_skia_request_is_scoped_and_log_absence_is_not_confirmation(self):
        from lite236_pixel_diagnostic import Diagnostic
        args = Namespace(sdk=Path("sdk"), output=self.root, source="a"*40, run="123", attempt="1",
                         disable_queue_submit_with_commands=False, original_run_number=236,
                         app_renderer="skia")
        with self.assertRaisesRegex(ValueError, "fixed original257"):
            Diagnostic(args)
        args.original_run_number = 257
        producer = Diagnostic(args)
        producer.receipt["appPid"] = 3198
        producer.receipt["surfaceCapture"] = dict(appPid=4144)
        log = self.root / "scene-logcat.out"
        shell = "11-05 07:03:37.915  3198  3272 I flutter : Using the Impeller rendering backend (OpenGLES)."
        instrumentation = "11-05 07:04:13.815  4144  4423 I flutter : Using the Impeller rendering backend (OpenGLES)."
        unrelated = "11-05 07:04:14.000  9999  9999 I flutter : Using the Impeller rendering backend (OpenGLES)."
        log.write_text("\n".join((shell, instrumentation, unrelated)))
        producer.record_app_renderer()
        observation = producer.receipt["appRenderer"]
        self.assertEqual(observation["requested"], "skia")
        self.assertEqual(observation["engineStartupLines"], [shell, instrumentation])
        self.assertTrue(observation["observedImpeller"])
        self.assertEqual(observation["backendConfirmation"], "backend-unconfirmed")
        log.write_text("11-05 07:04:16.582  4144  4144 I flutter : connectivityChanged")
        producer.record_app_renderer()
        self.assertFalse(observation["observedImpeller"])
        self.assertEqual(observation["backendConfirmation"], "backend-unconfirmed")
        self.assertFalse(producer.receipt["accepted"])
        args.original_run_number, args.app_renderer = 236, "default"
        self.assertNotIn("appRenderer", Diagnostic(args).receipt)

    def test_surface_cancellation_pulls_existing_evidence_before_owned_uninstall(self):
        from unittest.mock import Mock
        from lite236_pixel_diagnostic import Diagnostic
        args = Namespace(sdk=Path("sdk"), output=self.root, source="a"*40, run="123", attempt="1",
                         disable_queue_submit_with_commands=False)
        producer = Diagnostic(args)
        producer.emulator = Mock()
        producer.emulator.poll.return_value = None
        calls = []
        def execute():
            producer.install_attempted = producer.surface_install_attempted = True
            producer.surface_evidence_remote = "/sdcard/Android/data/dev.aerove.lite/files/surface-pixel-diagnostic/owned"
            producer.receipt["interruptSignals"].append(15)
            raise InterruptedError("cancel before the ordinary evidence pull could start")
        def adb(label, *_arguments, **_options):
            calls.append(label)
            producer.receipt["commands"].append(dict(label=label))
            return b"", dict(exit=0)
        with patch.object(producer, "execute", side_effect=execute), patch.object(producer, "adb_command", side_effect=adb), \
                patch("lite236_pixel_diagnostic.finish_emulator", return_value=1):
            self.assertEqual(producer.run(), 1)
        self.assertEqual(calls.count("surface-evidence-pull"), 1)
        self.assertLess(calls.index("surface-evidence-pull"), calls.index("cleanup-uninstall-surface-runner"))
        self.assertLess(calls.index("surface-evidence-pull"), calls.index("cleanup-uninstall-owned-app"))
        self.assertEqual(producer.receipt["status"], "cancelled")
        self.assertFalse(producer.receipt["collectionComplete"])

    def test_real_parser_accepts_exact_28_terminal_passes(self):
        p = self.root / "platform.log"; p.write_text(log(self.contract["platformSelectors"]))
        self.assertEqual(v.platform_log(p)["selectors"], self.contract["platformSelectors"])

    def test_two_discovered_ui_methods_cannot_satisfy_platform(self):
        p = self.root / "platform.log"; p.write_text(log(self.contract["uiSelectors"][:2]))
        with self.assertRaisesRegex(ValueError, "exactly the fixed 28"):
            v.platform_log(p)

    def test_skipped_platform_method_and_missing_terminal_are_rejected(self):
        p = self.root / "platform.log"; p.write_text(log(self.contract["platformSelectors"], skipped=True))
        with self.assertRaisesRegex(ValueError, "Skipped"):
            v.platform_log(p)
        p.write_text(log(self.contract["platformSelectors"]).replace("INSTRUMENTATION_CODE: -1", ""))
        with self.assertRaises(ValueError):
            v.platform_log(p)

    def test_exact_platform_set_cannot_be_replaced_by_same_count(self):
        selectors = self.contract["platformSelectors"][:]
        selectors[0] = "dev.aerove.lite.ui.LegacyComposeTest#pretend"
        p = self.root / "platform.log"; p.write_text(log(selectors))
        with self.assertRaises(ValueError):
            v.platform_log(p)

    def fixture(self):
        token = "qa-test"; stage = "vpn-active"; nonce = str(uuid4())
        info = dict(source="1" * 40, tree="2" * 40, apk_sha256="3" * 64,
                    meow_overlay_sha256="4" * 64, meow_file_manifest_sha256="5" * 64)
        common = dict(schema=1, qaToken=token, stage=stage, nonce=nonce, source=info["source"], tree=info["tree"], apkSha256=info["apk_sha256"], pid=412)
        request = dict(common, expectedVpn=True, networkHandles=["123456789012"], expectedMixedPort=18081,
                       expectedBypassPrivateRoute=False, fixtureRouteTarget="10.0.2.2",
                       networkSettingsUiEvidence=["network-mixed-port-readback.png", "network-private-bypass-readback.png"])
        (self.root / "checkpoints").mkdir(); (self.root / "native-acks").mkdir(); (self.root / "native-state").mkdir()
        rp = self.root / "checkpoints" / (stage + ".json"); rp.write_text(json.dumps(request))
        native = dict(common, requestSha256=v.sha(rp), status="attested", producer="AcceptanceCheckpointRunner", readOnly=True,
                      selectionComplete=True, rawSelectionMatchesSharedSql=True, coreCommit=v.CORE,
                      overlaySha256=info["meow_overlay_sha256"], adaptedFileManifestSha256=info["meow_file_manifest_sha256"],
                      wireVerified=False, actualMixedPort=18081, actualBypassPrivateRoute=False, networkReadbackSha256="d"*64, coreEpoch="11", selectionWitnessId="1", intentEpoch=3, compiledSha256="a" * 64,
                      capabilitiesSha256="b" * 64, sourceDefinitionSha256="c" * 64, expectedVpn=True,
                      networkHandles=request["networkHandles"], phase="Running", listening=True, suspended=False,
                      serviceOwner=9, sessionGeneration=3, runtimeServiceOwner=9, runtimeSessionGeneration=3, queryGraphParked=False)
        native["networkReadbackJson"] = json.dumps(dict(coreEpoch="11", network={"mixed-port":18081}, activePorts=[18081]))
        native["networkReadbackSha256"] = hashlib.sha256(native["networkReadbackJson"].encode()).hexdigest()
        ep = self.root / "native-state" / (nonce + ".json"); ep.write_text(json.dumps(native))
        ack = dict(common, requestSha256=v.sha(rp), status="attested", evidencePath="native-state/" + nonce + ".json", evidenceSha256=v.sha(ep))
        ap = self.root / "native-acks" / (stage + ".json"); ap.write_text(json.dumps(ack))
        return info, token, stage, rp, ep, ap

    def test_actual_schema_requires_owner_source_and_raw_selection(self):
        info, token, stage, _, ep, ap = self.fixture()
        self.assertEqual(v.checkpoint(self.root, stage, info, token)["serviceOwner"], 9)
        native = v.load(ep); native["serviceOwner"] = 0; ep.write_text(json.dumps(native))
        ack = v.load(ap); ack["evidenceSha256"] = v.sha(ep); ap.write_text(json.dumps(ack))
        with self.assertRaisesRegex(ValueError, "owner/session"):
            v.checkpoint(self.root, stage, info, token)

    def test_old_ack_modified_request_wrong_overlay_and_wrong_pid_are_rejected(self):
        info, token, stage, rp, ep, ap = self.fixture()
        request = v.load(rp); request["nonce"] = str(uuid4()); rp.write_text(json.dumps(request))
        with self.assertRaises(ValueError):
            v.checkpoint(self.root, stage, info, token)
        # Matching hashes cannot repair a different build's Native overlay identity.
        request["nonce"] = v.load(ap)["nonce"]; rp.write_text(json.dumps(request))
        native = v.load(ep); native["overlaySha256"] = "f" * 64; ep.write_text(json.dumps(native))
        ack = v.load(ap); ack["requestSha256"] = v.sha(rp); ack["evidenceSha256"] = v.sha(ep); ap.write_text(json.dumps(ack))
        with self.assertRaises(ValueError):
            v.checkpoint(self.root, stage, info, token)
        ack["pid"] = 999; ap.write_text(json.dumps(ack))
        with self.assertRaises(ValueError):
            v.checkpoint(self.root, stage, info, token)

    def test_stopped_vpn_without_closed_user_listener_is_not_accepted(self):
        info,token,stage,rp,ep,ap=self.fixture()
        request=v.load(rp);request.update(expectedVpn=False,networkHandles=[]);rp.write_text(json.dumps(request))
        native=v.load(ep);native.update(expectedVpn=False,networkHandles=[],phase="Stopped",listening=False,
                                      queryGraphParked=True,requestSha256=v.sha(rp),userListenerClosed=False,closedMixedPort=18081)
        ep.write_text(json.dumps(native))
        ack=v.load(ap);ack.update(requestSha256=v.sha(rp),evidenceSha256=v.sha(ep));ap.write_text(json.dumps(ack))
        with self.assertRaisesRegex(ValueError,"Stopped checkpoint"):
            v.checkpoint(self.root,stage,info,token)
        native["userListenerClosed"]=True;ep.write_text(json.dumps(native));ack["evidenceSha256"]=v.sha(ep);ap.write_text(json.dumps(ack))
        self.assertTrue(v.checkpoint(self.root,stage,info,token)["userListenerClosed"])

    def test_target_proxy_and_vpn_label_do_not_satisfy_separate_uid(self):
        with self.assertRaisesRegex(ValueError, "Independent UID"):
            v.separate_uid(dict(expectedVpn=True, phase="Running", networkHandles=["1"], tunSeparateUidAccepted=False))

    def test_framework_mapping_and_preview_never_satisfy_full_aggregate(self):
        (self.root / "android-acceptance.json").write_text(json.dumps(dict(phase="flutter-ui", executionPassed=True)))
        with self.assertRaisesRegex(ValueError, "Exactly platform and Flutter"):
            v.aggregate(self.root, "1" * 40, "123", "1")

    def test_execution_only_command_retains_pending_gates_and_strict_default_rejects_them(self):
        # Isolate CLI policy from the raw-evidence parser, exercised by the other tests.
        import sys
        pending = dict(schema=1, exactAndroidExecutionPassed=True, fullAcceptance=False,
                       visualApproved=False, releaseAccepted=False,
                       unresolvedCoverage=["Lite real optical displacement", "user twelve-frame visual approval"])
        output = self.root / "AGGREGATE.json"
        arguments = ["verify", "--directory", str(self.root), "--source", "a" * 40,
                     "--run", "123", "--attempt", "2", "--output", str(output)]
        for execution_only in (False, True):
            with patch.object(sys, "argv", arguments + (["--execution-only"] if execution_only else [])), \
                    patch.object(v, "aggregate", return_value=copy.deepcopy(pending)) as aggregate:
                if execution_only:
                    v.main()
                else:
                    with self.assertRaisesRegex(ValueError, "Full acceptance pending"):
                        v.main()
                aggregate.assert_called_once_with(self.root, "a" * 40, "123", "2", require_full=False)
                self.assertEqual(v.load(output), pending)
        pending["exactAndroidExecutionPassed"] = False
        with patch.object(sys, "argv", arguments + ["--execution-only"]), \
                patch.object(v, "aggregate", return_value=pending), self.assertRaisesRegex(ValueError, "Exact Android execution"):
            v.main()

    def test_sealed_paths_cannot_escape_or_change_bytes(self):
        p = self.root / "one"; p.write_text("one")
        manifest = dict(files=[dict(path="one", bytes=3, sha256=v.sha(p))])
        self.assertEqual(v.sealed_file(self.root, manifest, "one"), p)
        p.write_text("two")
        with self.assertRaises(ValueError):
            v.sealed_file(self.root, manifest, "one")
        with self.assertRaises(ValueError):
            v.sealed_file(self.root, manifest, "../one")

    def test_official_defined_only_dex_output_excludes_references(self):
        text = "C d 3 4 100 dev.aerove.lite.platformqa.AcceptanceCheckpointRunner\nC r 0 1 20 dev.aerove.lite.LiteApplication\n"
        self.assertEqual(seal.classes(text), {"dev.aerove.lite.platformqa.AcceptanceCheckpointRunner"})

    def test_first_cancellation_during_cleanup_does_not_abort_owned_joins(self):
        producer=Acceptance(Namespace(adb=Path("adb"),serial="emulator-5554",output=self.root,phase="flutter-full"))
        producer.cleaning=True
        producer.interrupt(15,None)
        self.assertTrue(producer.cancelled)
        self.assertTrue(producer.receipt["cleanupCancellationObserved"])
        producer.interrupt(2,None)
        self.assertEqual(producer.receipt["interruptSignals"],[15])

    def test_original201_v2_certificate_preflight_keeps_platform_install_boundary(self):
        from types import SimpleNamespace
        signature = (Path(__file__).parent / "fixtures/original201/lite-flutter-runner-signature.txt").read_text()
        digest = "09f9e021ddac36ead015d27e073b673e79fdf4e02605c402a505781963b1a4d8"
        app, test, platform = (self.root / name for name in ("app.apk", "driver.apk", "platform.apk"))
        for path in (app, test, platform): path.write_bytes(b"host-only preflight fixture; not an installable APK")
        info_path = self.root / "BUILD_INFO.json"
        info_path.write_text(json.dumps(dict(signer_sha256=digest)))
        (self.root / "platform-runner-build.json").write_text(json.dumps(dict(sharedUiReuse={})))
        proof_path = self.root / "owner.json"; proof_path.write_text("{}")
        proof = dict(deviceSerial="emulator-5554", runtimeDirectory=str(self.root), runtimeManifestSha256="a"*64)
        args = Namespace(adb=Path("adb"), serial="emulator-5554", output=self.root, phase="flutter-platform",
                         apk=app, test_apk=test, platform_apk=platform, build_info=info_path, apksigner=Path("apksigner"))
        owner = SimpleNamespace(validate_renderer_proof=lambda _: proof)
        for signed, reaches_install in ((signature, True), (signature.replace(digest, "b"*64), False),
                                        (signature + "\nSigner #1 certificate SHA-256 digest: " + digest, False)):
            producer = Acceptance(args)
            def adb(label, *_args, **_kwargs):
                if label == "install-dev.aerove.lite":
                    raise InterruptedError("host-only test stops before any installation")
                return b""
            with self.subTest(reaches_install=reaches_install, signature=signed), \
                    patch.dict("sys.modules", {"emulator_current": owner}), \
                    patch.dict("os.environ", AEROVE_CURRENT_RENDERER_PROOF=str(proof_path), ANDROID_SERIAL="emulator-5554"), \
                    patch("android_acceptance.validate_binding", return_value={}), \
                    patch("android_acceptance.platform_binding", return_value=platform), \
                    patch.object(producer, "adb_command", side_effect=adb) as commands, \
                    patch.object(producer, "command", return_value=signed.encode()):
                self.assertEqual(producer.run(), 1)
            self.assertEqual(any(call.args[0].startswith("install-") for call in commands.call_args_list), reaches_install)
            self.assertFalse(v.load(self.root / "android-acceptance.json")["fullAcceptance"])

    def test_normal_platform_ui_uses_shared_instrument_entry_and_keeps_exact_junit_gate(self):
        producer = Acceptance(Namespace(adb=Path("adb"), serial="emulator-5554", output=self.root, phase="flutter-full"))
        info = dict(apk_sha256="a"*64, source="1"*40, tree="2"*40, workflow="original-run",
                    run_attempt=1, meow_overlay_sha256="b"*64, meow_file_manifest_sha256="c"*64)
        method = "profileImportApplyVpnNetworkAndBackgroundResume"
        with patch.object(producer, "normal_instrument", return_value=b"OK (1 test)\n") as invoke, \
                patch.object(producer, "pull", return_value=self.root), \
                patch("android_acceptance.parse_log", return_value=[]), \
                patch("android_acceptance.ui_capture") as capture:
            with self.assertRaisesRegex(ValueError, "precisely its one selected method"):
                producer.ui(method, info, dict(profileUrl="controlled-original-fixture"))
            original = invoke.call_args.args
            self.assertEqual(original[0], method)
            self.assertEqual(original[1:4], ("shell", "am", "instrument"))
            self.assertEqual(original[-1], "dev.aerove.lite.platform.test/dev.aerove.lite.platformqa.AcceptanceCheckpointRunner")
            for value in (info["apk_sha256"], info["source"], info["tree"], "controlled-original-fixture"):
                self.assertIn(value, original)
            capture.assert_not_called()

    def test_running_restart_boundary_remains_direct_child_even_when_trace_opted_in(self):
        from unittest.mock import Mock
        producer = Acceptance(Namespace(adb=Path("adb"), serial="emulator-5554", output=self.root, phase="flutter-full"))
        info = dict(apk_sha256="a"*64, source="1"*40, tree="2"*40, workflow="original-run",
                    run_attempt=1, meow_overlay_sha256="b"*64, meow_file_manifest_sha256="c"*64)
        child = Mock(returncode=1)
        child.poll.return_value = 1
        with patch.dict("os.environ", AEROVE_CLEAR_FRAME_TRACE="1"), \
                patch.object(producer, "normal_instrument") as normal, \
                patch("android_acceptance.subprocess.Popen", return_value=child) as spawn:
            with self.assertRaisesRegex(ValueError, "Preparer ended before"):
                producer.running_boundary(info, {})
            normal.assert_not_called()
            self.assertEqual(spawn.call_args.args[0][:6], producer.adb + ["shell", "am", "instrument"])
            self.assertIn("hostRestartBoundary", spawn.call_args.args[0])
            self.assertFalse(any("clear_frame_trace.py" in str(arg) for arg in spawn.call_args.args[0]))
        self.assertIsNone(producer.child)

    def test_representative_platform_runner_executes_only_two_ui_methods_without_fixture_or_acceptance(self):
        from types import SimpleNamespace
        import android_acceptance as acceptance
        platform = self.root / "platform.apk"; platform.write_bytes(b"host-only sealed-platform fixture")
        app = self.root / "app.apk"; app.write_bytes(b"host-only app fixture")
        driver = self.root / "independent-driver.apk"; driver.write_bytes(b"archive evidence, never installed")
        info = dict(apk_sha256=v.sha(app), signer_sha256="a"*64)
        info_path = self.root / "BUILD_INFO.json"; info_path.write_text(json.dumps(info))
        (self.root / "platform-runner-build.json").write_text(json.dumps(dict(sharedUiReuse={})))
        proof_path = self.root / "owner.json"; proof_path.write_text("{}")
        proof = dict(deviceSerial="emulator-5554", runtimeDirectory=str(self.root), runtimeManifestSha256="b"*64)
        args = Namespace(adb=Path("adb"), serial="emulator-5554", output=self.root, phase="flutter-ui",
                         apk=app, test_apk=driver, platform_apk=platform, build_info=info_path, apksigner=Path("apksigner"))
        producer = Acceptance(args)
        calls = []
        def ui(method, _info):
            calls.append(method)
            pid = 101 if len(calls) == 1 else 202
            return dict(method=method, pid=pid, renderer=dict(backend="impeller")), dict(pid=pid), []
        owner = SimpleNamespace(validate_renderer_proof=lambda _: proof)
        with patch.dict("sys.modules", {"emulator_current": owner}), \
                patch.dict(os.environ, AEROVE_CURRENT_RENDERER_PROOF=str(proof_path), ANDROID_SERIAL="emulator-5554"), \
                patch("android_acceptance.validate_binding", return_value={}) as archive_binding, \
                patch("android_acceptance.platform_binding", return_value=platform) as mapped_binding, \
                patch("android_acceptance.renderer", return_value=dict(backend="impeller")), \
                patch.object(producer, "command", return_value=("Signer #1 certificate SHA-256 digest: " + "a"*64).encode()), \
                patch.object(producer, "adb_command", return_value=b"") as adb, \
                patch.object(producer, "installed_hash") as installed, \
                patch.object(producer, "check_process_log"), patch.object(producer, "ui", side_effect=ui), \
                patch.object(producer, "fixture_start") as fixture, patch.object(producer, "running_boundary") as boundary:
            self.assertEqual(producer.run(), 0)
            archive_binding.assert_called_once_with(app, driver, info, proof)
            mapped_binding.assert_called_once_with(str(self.root), info)
            fixture.assert_not_called(); boundary.assert_not_called()
        self.assertEqual(calls, ["consentNavigationTheme12", "persistedConsentAfterProcessRestart"])
        installs = [c.args for c in adb.call_args_list if c.args[0].startswith("install-")]
        self.assertEqual([row[-1] for row in installs], [str(app), str(platform)])
        self.assertTrue(any(c.args[0] == "consent-process-stop" for c in adb.call_args_list))
        self.assertEqual([c.args[-1] for c in installed.call_args_list], [v.sha(app), v.sha(platform), info["apk_sha256"]])
        receipt = v.load(self.root / "android-acceptance.json")
        self.assertEqual(receipt["producer"], "android_acceptance.py")
        self.assertEqual(receipt["phase"], "flutter-ui")
        self.assertTrue(receipt["executionPassed"]); self.assertTrue(receipt["representativeOnly"])
        for key in ("fullAcceptance", "nativeMeowAccepted", "tunSeparateUidAccepted", "canonicalSharedEvidenceVerified",
                    "visualApproved", "performanceAccepted", "releaseAccepted", "promotionAccepted", "aggregateAcceptance"):
            self.assertIs(receipt[key], False)
        with self.assertRaisesRegex(ValueError, "Exactly platform and Flutter"):
            v.aggregate(self.root, "source", "run", 1)

    def test_representative_consent_restart_still_rejects_same_process(self):
        producer = Acceptance(Namespace(adb=Path("adb"), serial="emulator-5554", output=self.root, phase="flutter-ui"))
        row = dict(pid=123, renderer={})
        with patch.object(producer, "ui", return_value=(row, dict(pid=123), [])), \
                patch.object(producer, "adb_command", return_value=b""), \
                patch("android_acceptance.renderer", return_value={}), patch.object(producer, "check_process_log"):
            with self.assertRaisesRegex(ValueError, "reused original process"):
                producer.consent_restart({})

    def test_failed_ui_instrument_preserves_evidence_under_cancellation_and_original_exception(self):
        from subprocess import TimeoutExpired
        for cancelled, original in ((False, RuntimeError("instrument exited 1")),
                                    (False, TimeoutExpired(["adb", "instrument"], 600)),
                                    (True, InterruptedError("instrument cancelled"))):
            producer = Acceptance(Namespace(adb=Path("adb"), serial="emulator-5554", output=self.root, phase="flutter-ui"))
            producer.cancelled = cancelled
            info = dict(apk_sha256="a"*64, source="1"*40, tree="2"*40, workflow="original-run",
                        run_attempt=1, meow_overlay_sha256="b"*64, meow_file_manifest_sha256="c"*64)
            method = "consentNavigationTheme12"
            with self.subTest(cancelled=cancelled), \
                    patch.object(producer, "normal_instrument", side_effect=original) as invoke, \
                    patch.object(producer, "adb_command", side_effect=OSError("failed capture pull")) as pull, \
                    patch.object(producer, "pull") as successful_pull:
                with self.assertRaises(type(original)) as caught:
                    producer.ui(method, info)
                self.assertIs(caught.exception, original)
                successful_pull.assert_not_called()
                pull.assert_called_once()
                token = invoke.call_args.args[invoke.call_args.args.index("qaToken") + 1]
                self.assertEqual(pull.call_args.args[1:], ("pull", producer.remote(token), str(self.root / method)))
                self.assertEqual(pull.call_args.kwargs, dict(timeout=90, check=False, cancelling=True))
                self.assertIn("failed capture pull", producer.receipt["diagnosticErrors"][0])

    def test_failed_owner_preflight_never_contacts_a_device(self):
        producer=Acceptance(Namespace(adb=Path("adb"),serial="physical-device",output=self.root,phase="flutter-platform"))
        with patch.dict("sys.modules",{"emulator_current":None}),patch.object(producer,"adb_command") as adb:
            self.assertEqual(producer.run(),1)
            adb.assert_not_called()
        receipt=v.load(self.root/"android-acceptance.json")
        self.assertFalse(receipt["executionPassed"])
        self.assertFalse(receipt["fullAcceptance"])
        self.assertEqual(receipt["status"],"failed")

    def test_four_file_reuse_and_passed_ui_cannot_approve_optics_or_visuals(self):
        ui=dict(unresolvedCoverage=[], sharedUiReuse=dict(scope="exact-four-canonical-files-only"),
                executionPassed=True, nativeOwnerAttestationsVerified=True, matrixImages=12,
                tunSeparateUidAccepted=False, visualApproved=False, opticalDisplacementVerified=False)
        gaps=v.completion_gaps(ui, [])
        self.assertIn("canonical shared Flutter evidence binding", gaps)
        self.assertIn("Lite real optical displacement", gaps)
        self.assertIn("user twelve-frame visual approval", gaps)
        self.assertIn("independent-UID TUN", gaps)

    def test_shared_reuse_checks_exact_four_blob_bytes_and_rejects_a_changed_file(self):
        contract=v.load(Path(seal.__file__).with_name("android_shared_ui_reuse.json"))
        source=Path(os.environ.get("AEROVE_REPOSITORY_FOR_TEST", str(Path(__file__).resolve().parents[3])))
        for relative, expected in contract["files"].items():
            path=self.root/relative;path.parent.mkdir(parents=True,exist_ok=True)
            path.write_bytes((source/relative).read_bytes().replace(b"\r\n",b"\n"))
        reused=seal.shared_reuse(self.root)
        self.assertEqual(len(reused["files"]),4)
        self.assertFalse(reused["liteAndroidAccepted"])
        path=self.root/next(iter(contract["files"]))
        path.write_bytes(path.read_bytes()+b"changed")
        with self.assertRaisesRegex(ValueError,"canonical shared file changed"):
            seal.shared_reuse(self.root)

    def test_same_target_sealer_rejects_wrong_signer_duplicate_app_graph_and_production_classes(self):
        app=self.root/"app.apk";app.write_bytes(b"unit parser fixture, not an APK")
        test=self.root/"test.apk";test.write_bytes(b"unit parser fixture, not an APK")
        driver=Path(os.environ.get("AEROVE_UI_CANDIDATE_FOR_TEST", str(Path(__file__).resolve().parents[1]/"app/src/androidTest/java/dev/aerove/lite/FlutterProductionUiTest.kt")))
        info=dict(source="1"*40,tree="2"*40,apk_sha256=v.sha(app),signer_sha256="a"*64)
        signature="Signer #1 certificate SHA-256 digest: "+info["signer_sha256"]+"\n"
        modern_signature = ("Verifies\nVerified using v2 scheme (APK Signature Scheme v2): true\n"
                            "Number of signers: 1\nV2 Signer: certificate SHA-256 digest: " + info["signer_sha256"] + "\n")
        manifest='<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.aerove.lite.platform.test"><instrumentation android:name="dev.aerove.lite.platformqa.AcceptanceCheckpointRunner" android:targetPackage="dev.aerove.lite"/><application android:testOnly="true"/></manifest>'
        # Shape from the fixed core:1.6.1 AAR manifest: LAUNCHER category, deliberately no action.
        helpers = "".join('<activity android:name="androidx.test.core.app.InstrumentationActivityInvoker$' + name +
                          '" android:exported="true"><intent-filter android:priority="-100">'
                          '<category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity>'
                          for name in ("BootstrapActivity", "EmptyActivity", "EmptyFloatingActivity"))
        helper_manifest = manifest.replace('<application android:testOnly="true"/>',
                                           '<application android:testOnly="true">' + helpers + '</application>')
        graph=":app:assembleRelease SKIPPED\n:platform-qa:assembleRelease SKIPPED\n"
        mapping="dev.aerove.lite.LiteApplication -> a.a:\n"
        defined="C d 2 2 200 dev.aerove.lite.platformqa.AcceptanceCheckpointRunner\nC d 2 2 200 dev.aerove.lite.FlutterProductionUiTest\n"
        for signed, xml in ((signature, manifest), (modern_signature, manifest), (modern_signature, helper_manifest)):
            result=seal.seal(info,app,test,signed,xml,graph,mapping,defined,driver)
            self.assertEqual(result["productionBuildCount"],1)
            self.assertFalse(result["copiedProductionClasses"])
            self.assertFalse(result["androidExecuted"])
        for changed in (
            dict(signature=signature.replace("a"*64,"b"*64)),
            dict(signature=modern_signature.replace("a"*64,"b"*64)),
            dict(signature=modern_signature+signature),
            dict(signature=modern_signature.replace("Number of signers: 1", "Number of signers: 2")),
            dict(signature=modern_signature+"Unknown Signer: certificate SHA-256 digest: "+"a"*64+"\n"),
            dict(signature="Unknown Signer: certificate SHA-256 digest: "+"a"*64+"\n"),
            dict(signature=""),
            dict(manifest=helper_manifest.replace('<category android:name="android.intent.category.LAUNCHER"/>',
                '<action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/>', 1)),
            dict(manifest=helper_manifest.replace('</application>',
                '<activity-alias android:name="Alias" android:targetActivity="androidx.test.core.app.InstrumentationActivityInvoker$EmptyActivity"/></application>')),
            dict(graph=graph+":app:assembleRelease SKIPPED\n"),
            dict(defined=defined+"C d 2 2 200 a.a\n"),
            dict(manifest=manifest.replace("<application android:testOnly=\"true\"/>","<application android:testOnly=\"true\"><activity android:name=\"SecondApp\"/></application>")),
        ):
            values=dict(signature=signature,manifest=manifest,graph=graph,mapping=mapping,defined=defined)
            values.update(changed)
            with self.assertRaises(ValueError):
                seal.seal(info,app,test,values["signature"],values["manifest"],values["graph"],values["mapping"],values["defined"],driver)


class CurrentRouteAndFixtureTest(unittest.TestCase):
    def test_new_phases_use_current_platform_producer_and_require_sealed_runner(self):
        inputs = dict(apk=Path("app.apk"), test_apk=Path("preview.apk"), build_info=Path("info.json"), platform_apk=Path("platform.apk"))
        with patch.dict(os.environ, ANDROID_HOME="sdk"):
            for phase in ("flutter-ui", "flutter-platform", "flutter-full"):
                command = emulator_current.phase_acceptance_command(phase, Path("out"), inputs)
                self.assertIn("apps/aerove-lite/tool/android_acceptance.py", command)
                self.assertEqual(command[-2:], ["--phase", phase])
            with self.assertRaises(ValueError):
                emulator_current.phase_acceptance_command("flutter-full", Path("out"), None)

    def test_real_fixture_preserves_echo_smart_and_serves_profile_and_controlled_slow(self):
        handler = fixture_server.Handler
        old = handler.smart_files
        handler.smart_files = {"pinned.bin": b"pinned-smart-bytes"}
        self.addCleanup(setattr, handler, "smart_files", old)
        server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(thread.join, 3)
        self.addCleanup(server.shutdown)
        base = "http://127.0.0.1:" + str(server.server_port)
        token = str(uuid4())
        with urllib.request.urlopen(base + "/" + token) as response:
            self.assertEqual(response.read(), ("AEROVE_LITE_FIXTURE:" + token).encode())
        with urllib.request.urlopen(base + "/smart-config-meow/pinned.bin") as response:
            self.assertEqual(response.read(), b"pinned-smart-bytes")
        expected = b"mixed-port: 18081\nmode: direct\nrules: ['MATCH,DIRECT']\n"
        with urllib.request.urlopen(base + "/flutter-profile/" + token + ".yaml?mixedPort=18081") as response:
            self.assertEqual(response.read(), expected)
        with self.assertRaises(urllib.error.HTTPError) as error:
            urllib.request.urlopen(base + "/flutter-profile/" + token + ".yaml?mixedPort=18080")
        self.assertEqual(error.exception.code, 400)
        slow_token = str(uuid4())
        with urllib.request.urlopen(base + "/flutter-profile-slow/" + slow_token + ".yaml?mixedPort=18081", timeout=3) as response:
            first = response.read(1)
            self.assertEqual(first, expected[:1])
            request = urllib.request.Request(base + "/flutter-profile-release/" + slow_token, method="POST")
            with urllib.request.urlopen(request) as release:
                self.assertEqual(release.status, 204)
            self.assertEqual(first + response.read(), expected)


class RuntimeDeviceReplayTests(unittest.TestCase):
    """Parser policy only. Synthetic rows never claim an App/Android acceptance pass."""
    def test_contract_preserves_original_seven_and_adds_the_two_real_device_scenes(self):
        contract = v.load(v.CONTRACT)
        prefix = "dev.aerove.lite.FlutterProductionUiTest#"
        original = ["consentNavigationTheme12", "persistedConsentAfterProcessRestart",
                    "profileImportApplyVpnNetworkAndBackgroundResume", "cancelVpnAuthorizationThenRecover",
                    "prepareImportedRunningSessionForProcessRestart", "persistedImportedProfileAfterProcessRestart",
                    "profileUpdateCancelBatchAndRetry"]
        self.assertEqual(contract["uiSelectors"][:7], [prefix + method for method in original])
        self.assertEqual(contract["uiSelectors"][7:], [prefix + "listenerRebindFailureRecoveryAndNotificationLifecycle",
            prefix + "independentApplicationTcpUdpNativeAttribution"])
        self.assertEqual(contract["platformCount"], 28)
        self.assertFalse(contract["fullAcceptance"])
        self.assertFalse(contract["visualApproved"])

    def lifecycle(self):
        import runtime_device_verify as device
        import uuid
        result = []
        for stage in device.LIFECYCLE:
            stopped = stage in ("listener-bind-failed", "listener-stopped", "proxy-notification-stopped", "proxy-notification-still-stopped")
            proxy = stage.startswith("proxy-") and not stopped
            port = 18081 if stage == "listener-baseline" else 18082
            generation = 1 if stage in ("listener-baseline", "listener-rebound") else 2 if stage == "listener-retry" else 3
            epoch = "2" if stage == "listener-rebound" else str(generation)
            row = dict(stage=stage, phase="Stopped" if stopped else "Running", serviceOwner=generation,
                       sessionGeneration=generation, coreEpoch=epoch, requestedPort=port,
                       vpnHandles=[] if stopped or proxy else ["123"], listening=not stopped, suspended=False,
                       appUid=10001, pid=999, notifications=[])
            row["networkRequestId"] = 2 if stage == "listener-rebound" else 1
            row["profileName"] = "ControlledProfile"
            row["networkReadbackJson"] = json.dumps(dict(coreEpoch=epoch, network={"mixed-port": port}, activePorts=[port]))
            token = str(uuid.uuid4())
            row["proxyEcho"] = dict(port=port, token=token, response="HTTP/1.0 200 OK\r\n\r\nAEROVE_LITE_FIXTURE:" + token)
            if stage == "listener-rebound" or stopped:
                row.update(releasedPort=18081 if stage == "listener-rebound" else 18082, releasedPortBindSucceeded=True)
            if stage == "listener-bind-failed":
                # Parser-policy fixture, never a claim that the current Android collector emits it.
                row.update(occupiedPort=18083, occupiedPortRejected=True,
                    error="Live update failed: Core API aerove returned HTTP 503",
                    networkApplyFailure=dict(operation="aerove/network", method="POST", statusCode=503,
                        requestId=str(uuid.uuid4()), networkRequestId=3, mixedPort=18083, expectedCoreEpoch="2",
                        serviceOwner=1, sessionGeneration=1, responseBody='{"error":"userListenerBindFailed"}'))
            if proxy:
                row["notifications"] = [dict(channel="aerove-lite-vpn", title="ControlledProfile", flags=10, channelImportance=2,
                    actions=[] if stage == "proxy-stop-hidden" else ["Stop"])]
            result.append(row)
        return result

    def test_hot_rebind_keeps_the_same_runtime_and_releases_the_old_port(self):
        import runtime_device_verify as device
        records = self.lifecycle()
        self.assertEqual(device.validate_rows(device.LIFECYCLE_METHOD, records), records)
        for key, value in (("serviceOwner", 9), ("sessionGeneration", 9), ("coreEpoch", "9"), ("vpnHandles", ["other"]), ("pid", 9)):
            changed = copy.deepcopy(records); changed[1][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): device.validate_rows(device.LIFECYCLE_METHOD, changed)
        changed = copy.deepcopy(records); changed[1]["releasedPortBindSucceeded"] = False
        with self.assertRaises(ValueError): device.validate_rows(device.LIFECYCLE_METHOD, changed)

    def test_hot_rebind_requires_actual_target_readback_new_request_and_canonical_epoch_advance(self):
        import runtime_device_verify as device
        # Native apply receipts require a new u64 epoch, while owner/session/TUN/PID stay unchanged.
        records = self.lifecycle()
        self.assertEqual((records[0]["coreEpoch"], records[1]["coreEpoch"]), ("1", "2"))
        self.assertEqual(device.validate_rows(device.LIFECYCLE_METHOD, records), records)
        for epoch in ("1", "0", "02", "18446744073709551616", 2):
            records = self.lifecycle()
            records[1]["coreEpoch"] = epoch
            readback = json.loads(records[1]["networkReadbackJson"])
            readback["coreEpoch"] = epoch
            records[1]["networkReadbackJson"] = json.dumps(readback)
            with self.subTest(epoch=epoch), self.assertRaises(ValueError):
                device.validate_rows(device.LIFECYCLE_METHOD, records)
        records = self.lifecycle(); records[1]["networkRequestId"] = records[0]["networkRequestId"]
        with self.assertRaises(ValueError): device.validate_rows(device.LIFECYCLE_METHOD, records)
        records = self.lifecycle()
        readback = json.loads(records[1]["networkReadbackJson"]); readback["network"]["mixed-port"] = 18081
        records[1]["networkReadbackJson"] = json.dumps(readback)
        with self.assertRaises(ValueError): device.validate_rows(device.LIFECYCLE_METHOD, records)

    def test_active_listener_readback_does_not_replace_a_real_http_response(self):
        import runtime_device_verify as device
        records = self.lifecycle(); records[1]["proxyEcho"]["response"] = "HTTP/1.0 200 OK\r\n\r\nwrong body"
        with self.assertRaises(ValueError): device.validate_rows(device.LIFECYCLE_METHOD, records)

    def test_notification_updates_cannot_restart_or_repost_after_stop(self):
        import runtime_device_verify as device
        records = self.lifecycle(); records[6]["serviceOwner"] += 1
        with self.assertRaises(ValueError): device.validate_rows(device.LIFECYCLE_METHOD, records)
        records = self.lifecycle(); records[-1]["notifications"] = [dict(channel="aerove-lite-vpn", actions=["Stop"])]
        with self.assertRaises(ValueError): device.validate_rows(device.LIFECYCLE_METHOD, records)

    def test_any_extra_application_notification_channel_is_rejected(self):
        import runtime_device_verify as device
        for index in (0, 4, 5, 9):
            records = self.lifecycle()
            records[index]["notifications"].append(dict(channel="unrelated-app-channel", actions=[]))
            with self.subTest(stage=records[index]["stage"]), self.assertRaises(ValueError):
                device.validate_rows(device.LIFECYCLE_METHOD, records)

    def test_busy_port_requires_original_native_code_request_port_and_session(self):
        import runtime_device_verify as device
        records = self.lifecycle()
        del records[2]["networkApplyFailure"]
        with self.assertRaisesRegex(ValueError, "original network apply diagnostic"):
            device.validate_rows(device.LIFECYCLE_METHOD, records)
        invalid = (("operation", "delay"), ("method", "GET"), ("statusCode", 409),
                   ("requestId", "not-a-request"), ("networkRequestId", 1),
                   ("mixedPort", 18082), ("expectedCoreEpoch", "stale"),
                   ("serviceOwner", 90), ("sessionGeneration", 90),
                   ("responseBody", '{"error":"networkApplyCancelled"}'))
        for key, value in invalid:
            records = self.lifecycle()
            records[2]["networkApplyFailure"][key] = value
            with self.subTest(field=key), self.assertRaises(ValueError):
                device.validate_rows(device.LIFECYCLE_METHOD, records)
        records = self.lifecycle()
        records[2]["error"] = "Live update failed: unrelated configuration error"
        with self.assertRaises(ValueError):
            device.validate_rows(device.LIFECYCLE_METHOD, records)

    def test_occupied_failure_needs_actual_collision_release_and_a_new_retry_session(self):
        import runtime_device_verify as device
        for field, value in (("occupiedPortRejected", False), ("releasedPortBindSucceeded", False), ("error", "")):
            records = self.lifecycle(); records[2][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError): device.validate_rows(device.LIFECYCLE_METHOD, records)
        records = self.lifecycle(); records[3].update(serviceOwner=1, sessionGeneration=1, coreEpoch="1")
        readback = json.loads(records[3]["networkReadbackJson"]); readback["coreEpoch"] = "1"
        records[3]["networkReadbackJson"] = json.dumps(readback)
        with self.assertRaises(ValueError): device.validate_rows(device.LIFECYCLE_METHOD, records)

    def owner(self):
        from uuid import uuid4
        package, uid = "dev.aerove.fixture.socketowner", 10099
        row = dict(appUid=10001, pid=999)
        owner = dict(fixtureUid=uid, fixturePackage=package, fixturePid=1000, retained=True, fixtureClosed=True,
                     fixtureTestOnly=True, fixtureApkSha256="a" * 64, token=str(uuid4()))
        connections = []
        for protocol, source_port, target_port in (("tcp", 40001, 19001), ("udp", 40002, 19002)):
            owner[protocol] = dict(source=f"[172.19.0.1]:{source_port}", target=f"[10.0.2.2]:{target_port}")
            connections.append(dict(metadata=dict(network=protocol, type="Tun", sourceIP="172.19.0.1", sourcePort=source_port,
                destinationIP="10.0.2.2", destinationPort=target_port, uid=uid, process=package)))
        owner["connectionsJson"] = json.dumps(dict(connections=connections)); row["owner"] = owner
        return row

    def test_owner_requires_native_tcp_and_udp_metadata_for_the_actual_ordinary_uid(self):
        import runtime_device_verify as device
        row = self.owner(); self.assertEqual(device.validate_owner(row), row["owner"])
        for uid in (row["appUid"], 2000):
            changed = copy.deepcopy(row); changed["owner"]["fixtureUid"] = uid
            with self.subTest(uid=uid), self.assertRaises(ValueError): device.validate_owner(changed)
        for index in (0, 1):
            changed = copy.deepcopy(row); raw = json.loads(changed["owner"]["connectionsJson"])
            raw["connections"][index]["metadata"]["uid"] = 10098
            changed["owner"]["connectionsJson"] = json.dumps(raw)
            with self.subTest(protocol=index), self.assertRaises(ValueError): device.validate_owner(changed)
        changed = copy.deepcopy(row); raw = json.loads(changed["owner"]["connectionsJson"])
        raw["connections"][0]["metadata"]["type"] = "Mixed"
        changed["owner"]["connectionsJson"] = json.dumps(raw)
        with self.assertRaises(ValueError): device.validate_owner(changed)

    def test_owner_rejects_missing_udp_wrong_tuple_or_unclosed_helper(self):
        import runtime_device_verify as device
        row = self.owner(); raw = json.loads(row["owner"]["connectionsJson"])
        raw["connections"] = raw["connections"][:1]; row["owner"]["connectionsJson"] = json.dumps(raw)
        with self.assertRaises(ValueError): device.validate_owner(row)
        row = self.owner(); row["owner"]["tcp"]["source"] = "[172.19.0.1]:44444"
        with self.assertRaises(ValueError): device.validate_owner(row)
        row = self.owner(); row["owner"]["fixtureClosed"] = False
        with self.assertRaises(ValueError): device.validate_owner(row)



if __name__ == "__main__":
    unittest.main()
