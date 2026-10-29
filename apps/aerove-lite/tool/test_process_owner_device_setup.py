"""Host boundary/protocol tests only; not Android UID or Tun acceptance."""
from pathlib import Path
import json
import socket
import zipfile
from build_process_owner_fixture import PACKAGE, verify_compiled
import unittest

import instrumentation_shards as shards
import instrumentation_results as results
from process_owner_device_setup import EchoScope, validate_command, validate_host, validate_log, validate_receipt
import test_instrumentation_shards as shard_tests
import test_instrumentation_results as result_tests


class OwnerScopeTests(unittest.TestCase):
    def discovery(self):
        helper = shard_tests.ShardSelectionTests()
        helper.setUp()
        self.addCleanup(helper.doCleanups)
        helper.descriptions += [shards.OWNER_DESCRIPTION]
        return helper, helper.discovery(shards.shard_for(shards.OWNER_DESCRIPTION, 16), 16)

    def test_only_verified_ci_owner_and_original_selector_are_admitted(self):
        helper, discovery = self.discovery()
        source = dict(discovery["identity"], phase="remaining", deviceSerial="emulator-5554", deviceKind="CI-emulator", isolatedBackgroundPolicy=True)
        proof = dict(apks=discovery["apkSha256"], deviceSerial="emulator-5554")
        env = dict(CI="true", GITHUB_ACTIONS="true", ANDROID_SERIAL="emulator-5554")
        validate_host(source, discovery, proof, "emulator-5554", env)
        for changed in ({}, dict(env, ANDROID_SERIAL="real-device"), dict(env, CI="false")):
            with self.assertRaises(ValueError):
                validate_host(source, discovery, proof, "emulator-5554", changed)
        command = ["adb", "-s", "emulator-5554", "shell", "am", "instrument", "-w", "-r", "-e", "class", shards.OWNER_SELECTOR,
                   "-e", "timeout_msec", "120000", "dev.aerove.lite.test/androidx.test.runner.AndroidJUnitRunner"]
        validate_command(command, "emulator-5554")
        for key, value in (("numShards", "16"), ("timeout_msec", "240000"), ("processOwnerFixture", "true")):
            with self.assertRaises(ValueError):
                validate_command(command[:-1] + ["-e", key, value, command[-1]], "emulator-5554")

    def test_owner_exact_once_discovery_union_and_assumptions_never_become_passed(self):
        helper, discovery = self.discovery()
        plan = shards.split_plan(discovery)
        self.assertEqual([shards.OWNER_DESCRIPTION], plan["owner"])
        self.assertNotIn(shards.OWNER_DESCRIPTION, plan["ordinary"])
        normal = helper.root / "normal.log"
        owner = helper.root / "owner.log"
        normal.write_text(helper.log(plan["ordinary"]))
        owner.write_text(helper.log(plan["owner"]) + "OK (1 test)\n")
        validate_log(owner)
        receipt = shards.verify(discovery, normal, owner_log=owner)
        self.assertTrue(receipt["allExpectedPassed"])
        self.assertEqual(["ordinary", "owner-scoped"], [row["role"] for row in receipt["instrumentationLogs"]])
        with self.assertRaises(ValueError):
            shards.verify(discovery, normal)
        for records in ([shards.OWNER_DESCRIPTION] * 2, plan["ordinary"], []):
            owner.write_text(helper.log(records) + "OK (1 test)\n")
            with self.assertRaises(ValueError):
                shards.verify(discovery, normal, owner_log=owner)
        owner.write_text(helper.log(plan["owner"], {shards.OWNER_DESCRIPTION: -4}) + "OK (1 test)\n")
        with self.assertRaises(ValueError):
            validate_log(owner)
        self.assertFalse(shards.verify(discovery, normal, owner_log=owner)["allExpectedPassed"])

    def test_real_host_echo_roundtrips_and_workers_close(self):
        receipts = []
        scope = EchoScope(receipts)
        try:
            tcp, udp = scope.start()
            token = b"12345678-1234-1234-1234-123456789abc"
            with socket.create_connection(("127.0.0.1", tcp), timeout=2) as connection:
                connection.sendall(token)
                self.assertEqual(token, connection.recv(128))
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as connection:
                connection.settimeout(2)
                connection.sendto(token, ("127.0.0.1", udp))
                self.assertEqual(token, connection.recv(128))
        finally:
            scope.close()
        self.assertEqual({"tcp", "udp"}, {row["protocol"] for row in receipts})
        self.assertFalse(any(thread.is_alive() for thread in scope.threads))

    def test_sixteen_shard_aggregate_keeps_every_owner_test_and_rejects_assumption(self):
        helper, _ = self.discovery()
        helper.descriptions = [f"case{index}(a.b.Test)" for index in range(320)] + [shards.OWNER_DESCRIPTION, shards.SSID_DESCRIPTION]
        owner_receipt = None
        for index in range(16):
            discovery = helper.discovery(index, 16)
            plan = shards.split_plan(discovery)
            ordinary = helper.root / f"ordinary-{index}.log"
            ssid = helper.root / f"ssid-{index}.log"
            owner = helper.root / f"owner-{index}.log"
            ordinary.write_text(helper.log(plan["ordinary"]))
            ssid.write_text(helper.log(plan["isolated"]))
            owner.write_text(helper.log(plan["owner"]))
            receipt = shards.verify(discovery, ordinary, ssid if plan["isolated"] else None, owner if plan["owner"] else None)
            path = helper.root / f"AEROVE-Lite-shard-selection-{index}-of-16-attempt-2/TEST-SELECTION.json"
            path.parent.mkdir()
            path.write_text(json.dumps(receipt))
            if plan["owner"]:
                owner_receipt = path, receipt
        aggregate = shards.aggregate(helper.root, "a" * 40, "11", "2", 16)
        self.assertTrue(aggregate["allDiscoveredPassed"])
        self.assertEqual(len(helper.descriptions), aggregate["passedCount"])
        path, receipt = owner_receipt
        receipt["terminalStatuses"]["passed"].remove(shards.OWNER_DESCRIPTION)
        receipt["terminalStatuses"]["assumptionFailed"].append(shards.OWNER_DESCRIPTION)
        receipt["allExpectedPassed"] = False
        path.write_text(json.dumps(receipt))
        with self.assertRaisesRegex(ValueError, "sixteen shards must pass"):
            shards.aggregate(helper.root, "a" * 40, "11", "2", 16)

    def test_receipt_requires_actual_echo_identity_running_tun_and_cleanup(self):
        token = "12345678-1234-1234-1234-123456789abc"
        receipt = dict(fixturePackage="dev.aerove.fixture.socketowner", fixtureUid=10123, appUid=10124,
                       pid=1234,
                       lookupPassed=True, cleanupPassed=True, sourceRevision="a" * 40, tunInterface="tun0", sessionGeneration=1,
                       token=token, tcp=dict(resolvedUid=10123, package="dev.aerove.fixture.socketowner"),
                       udp=dict(resolvedUid=10123, package="dev.aerove.fixture.socketowner"))
        echo = [dict(protocol=protocol, payload=token) for protocol in ("tcp", "udp")]
        validate_receipt(receipt, echo, dict(sourceRevision="a" * 40))
        for key, value in (("fixtureUid", 10124), ("cleanupPassed", False), ("lookupPassed", False), ("sessionGeneration", 0), ("tunInterface", "")):
            with self.assertRaises(ValueError):
                validate_receipt(dict(receipt, **{key: value}), echo, dict(sourceRevision="a" * 40))
        with self.assertRaises(ValueError):
            validate_receipt(receipt, echo[:1], dict(sourceRevision="a" * 40))

    def test_compiled_dependency_inspection_rejects_shared_uid_missing_service_or_dex(self):
        helper, _ = self.discovery()
        apk = helper.root / "fixture.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"compiled manifest")
            archive.writestr("classes.dex", b"dex\n039\0")
        badging = f"package: name='{PACKAGE}' versionCode='0'\nsdkVersion:'33'\ntargetSdkVersion:'33'\nuses-permission: name='android.permission.INTERNET'\n"
        manifest = 'E: service (line=5)\nA: android:name(0x01010003)=".SocketOwnerService"\nA: android:exported(0x01010010)=(type 0x12)0xffffffff\nA: android:testOnly(0x01010272)=(type 0x12)0xffffffff\n'
        dex = "Class descriptor  : 'Ldev/aerove/fixture/socketowner/SocketOwnerService;'"
        verify_compiled(apk, badging, manifest, dex)
        for changed in (manifest + "sharedUserId", manifest.replace("E: service ", "E: activity "), manifest.replace("0xffffffff", "0x0")):
            with self.assertRaises(ValueError):
                verify_compiled(apk, badging, changed, dex)
        with self.assertRaises(ValueError):
            verify_compiled(apk, badging.replace(PACKAGE, "dev.aerove.lite"), manifest, dex)
        with self.assertRaises(ValueError):
            verify_compiled(apk, badging, manifest, "")

    def test_diagnostic_index_reports_missing_owner_separately_from_the_same_shard_ssid(self):
        helper = result_tests.ResultIndexTests()
        helper.setUp()
        self.addCleanup(helper.doCleanups)
        index = shards.shard_for(shards.OWNER_DESCRIPTION)
        names = sorted([shards.OWNER_DESCRIPTION, shards.SSID_DESCRIPTION] +
                       [f"normal{i}(example.Tests)" for i in range(50) if shards.shard_for(f"normal{i}(example.Tests)") == index])
        helper.inputs(names)
        ordinary = [name for name in names if name not in (shards.OWNER_DESCRIPTION, shards.SSID_DESCRIPTION)]
        helper.ordinary.write_text(helper.log(ordinary))
        ssid = helper.root / "ssid.log"
        owner = helper.root / "owner.log"
        ssid.write_text(helper.log([shards.SSID_DESCRIPTION]))
        missing = results.build_index(helper.source, helper.discovery, helper.sums, helper.ordinary, ssid, helper.discovery_log, 1)
        self.assertEqual("unstarted", next(row["status"] for row in missing["tests"] if row["description"] == shards.OWNER_DESCRIPTION))
        self.assertFalse(missing["complete"])
        owner.write_text(helper.log([shards.OWNER_DESCRIPTION]))
        complete = results.build_index(helper.source, helper.discovery, helper.sums, helper.ordinary, ssid, helper.discovery_log, 0, owner)
        self.assertTrue(complete["complete"])
        self.assertEqual(len(names), complete["counts"]["passed"])
        self.assertFalse(complete["accepted"])
        self.assertEqual(["ordinary", "isolated-SSID", "isolated-owner"], [row["role"] for row in complete["inputs"]])


if __name__ == "__main__":
    unittest.main()
