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
        manifest='<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.aerove.lite.platform.test"><instrumentation android:name="dev.aerove.lite.platformqa.AcceptanceCheckpointRunner" android:targetPackage="dev.aerove.lite"/><application android:testOnly="true"/></manifest>'
        graph=":app:assembleRelease SKIPPED\n:platform-qa:assembleRelease SKIPPED\n"
        mapping="dev.aerove.lite.LiteApplication -> a.a:\n"
        defined="C d 2 2 200 dev.aerove.lite.platformqa.AcceptanceCheckpointRunner\nC d 2 2 200 dev.aerove.lite.FlutterProductionUiTest\n"
        result=seal.seal(info,app,test,signature,manifest,graph,mapping,defined,driver)
        self.assertEqual(result["productionBuildCount"],1)
        self.assertFalse(result["copiedProductionClasses"])
        for changed in (
            dict(signature=signature.replace("a"*64,"b"*64)),
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
            for phase in ("flutter-platform", "flutter-full"):
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


if __name__ == "__main__":
    unittest.main()
