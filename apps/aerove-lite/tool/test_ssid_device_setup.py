"""Synthetic shell-boundary state tests; no Android or SSID behavior is claimed."""
import tempfile
from pathlib import Path
import unittest
from ssid_device_setup import DeviceScope, PERMISSIONS, PACKAGE, validate_host, validate_command, validate_isolated_log, prepare_after_ordinary
import instrumentation_shards as shards
import test_instrumentation_shards as shard_tests


class FakeShell:
    def __init__(self):
        self.permissions = {name: False for name in PERMISSIONS}
        self.location = self.wifi = False
        self.commands = []
        self.fail_permission = None

    def __call__(self, *args):
        self.commands.append(args)
        if args == ("dumpsys", "package", PACKAGE):
            return "requested permissions:\n" + "\n".join(PERMISSIONS) + "\n User 0: installed=true\n  runtime permissions:\n" + "\n".join(
                f"    {name}: granted={str(value).lower()}, flags=[]" for name, value in self.permissions.items())
        if args[:3] == ("cmd", "location", "is-location-enabled"):
            return str(self.location).lower()
        if args[:3] == ("cmd", "location", "set-location-enabled"):
            self.location = args[3] == "true"
        elif args == ("cmd", "wifi", "status"):
            return "Wifi is enabled\nWifi is connected to synthetic-fixture\n" if self.wifi else "Wifi is disabled\n"
        elif args[:3] == ("cmd", "wifi", "set-wifi-enabled"):
            self.wifi = args[3] == "enabled"
        elif args[:2] in (("pm", "grant"), ("pm", "revoke")):
            if args[1] == "grant" and args[-1] == self.fail_permission:
                raise RuntimeError("grant failed")
            self.permissions[args[-1]] = args[1] == "grant"
        elif args != ("am", "force-stop", PACKAGE):
            raise AssertionError(args)
        return ""


class DeviceScopeTests(unittest.TestCase):
    def test_real_scope_control_flow_reads_back_and_restores_partial_prepare_failure(self):
        with tempfile.TemporaryDirectory() as root:
            fake = FakeShell()
            fake.fail_permission = PERMISSIONS[-1]
            scope = DeviceScope(fake, Path(root))
            try:
                with self.assertRaisesRegex(RuntimeError, "grant failed"):
                    scope.prepare()
            finally:
                scope.restore()
            self.assertFalse(any(fake.permissions.values()))
            self.assertFalse(fake.location or fake.wifi)
            mutations = [command for command in fake.commands if command[:1] == ("pm",)]
            self.assertEqual(list(PERMISSIONS), [command[-1] for command in mutations[:3]])
            self.assertEqual(list(reversed(PERMISSIONS)), [command[-1] for command in mutations[3:]])
            self.assertTrue((Path(root) / "restored.json").is_file())

    def test_success_prepares_only_original_method_and_restores_before_next_invocation(self):
        with tempfile.TemporaryDirectory() as root:
            fake = FakeShell()
            scope = DeviceScope(fake, Path(root))
            try:
                scope.prepare()
                self.assertTrue(all(fake.permissions.values()))
                self.assertTrue(fake.location and fake.wifi)
            finally:
                scope.restore()
            self.assertFalse(any(fake.permissions.values()))
            self.assertFalse(fake.location or fake.wifi)
            stop = fake.commands.index(("am", "force-stop", PACKAGE))
            self.assertLess(stop, next(index for index, command in enumerate(fake.commands) if command[:2] == ("pm", "revoke")))

    def test_cleanup_failure_is_not_suppressed_and_other_restoration_still_runs(self):
        class RejectRevoke(FakeShell):
            def __call__(self, *args):
                if args[:2] == ("pm", "revoke") and args[-1] == PERMISSIONS[1]:
                    raise RuntimeError("revoke failed")
                return super().__call__(*args)
        with tempfile.TemporaryDirectory() as root:
            fake = RejectRevoke()
            scope = DeviceScope(fake, Path(root))
            scope.prepare()
            with self.assertRaisesRegex(ValueError, "cleanup failed"):
                scope.restore()
            self.assertFalse(fake.location or fake.wifi)
            self.assertFalse(fake.permissions[PERMISSIONS[0]])
            self.assertFalse(fake.permissions[PERMISSIONS[2]])
            self.assertTrue(fake.permissions[PERMISSIONS[1]])

    def test_recorded_emulator_source_and_exact_method_guards(self):
        index = shards.shard_for(shards.SSID_DESCRIPTION)
        ordinary = next(f"case{i}(a.b.Test)" for i in range(100) if shards.shard_for(f"case{i}(a.b.Test)") == index)
        descriptions = sorted([ordinary, shards.SSID_DESCRIPTION])
        identity = dict(sourceRevision="a" * 40, sourceTree="b" * 40, workflowRun="11", runAttempt="2", runNumber="3")
        discovery = dict(numShards=4, shardIndex=index, discoveredDescriptions=descriptions,
                         discoveryFingerprint=shards.fingerprint(descriptions), identity=identity, apkSha256={"app": "c" * 64})
        source = dict(identity, deviceKind="CI-emulator", deviceSerial="emulator-5554", isolatedBackgroundPolicy=True, phase="remaining")
        proof = dict(deviceSerial="emulator-5554", apks=discovery["apkSha256"])
        env = dict(CI="true", GITHUB_ACTIONS="true", ANDROID_SERIAL="emulator-5554")
        validate_host(source, discovery, proof, "emulator-5554", env)
        for changed in ({}, dict(env, CI="false"), dict(env, ANDROID_SERIAL="user-device")):
            with self.assertRaises(ValueError):
                validate_host(source, discovery, proof, "emulator-5554", changed)
        with self.assertRaises(ValueError):
            validate_host(source, discovery, dict(proof, apks={}), "emulator-5554", env)
        command = ["adb", "-s", "emulator-5554", "shell", "am", "instrument", "-w", "-r", "-e", "class", shards.SSID_SELECTOR,
                   "dev.aerove.lite.test/androidx.test.runner.AndroidJUnitRunner"]
        validate_command(command, "emulator-5554")
        with self.assertRaises(ValueError):
            validate_command(command[:-1] + ["-e", "shardIndex", "1", command[-1]], "emulator-5554")

    def test_ordinary_exact_pass_gate_prevents_all_device_preparation_on_protocol_failures(self):
        index = shards.shard_for(shards.SSID_DESCRIPTION)
        ordinary = [f"case{i}(a.b.Test)" for i in range(100) if shards.shard_for(f"case{i}(a.b.Test)") == index][:2]
        descriptions = sorted(ordinary + [shards.SSID_DESCRIPTION])
        discovery = dict(numShards=4, shardIndex=index, discoveredDescriptions=descriptions,
                         discoveryFingerprint=shards.fingerprint(descriptions))
        formatter = shard_tests.ShardSelectionTests()
        good = formatter.log(ordinary) + "OK (2 tests)\n"
        bad_logs = {
            "missing": formatter.log(ordinary[:1]) + "OK (1 test)\n",
            "assumption": formatter.log(ordinary, {ordinary[0]: -4}) + "OK (2 tests)\n",
            "ignored": formatter.log(ordinary, {ordinary[0]: -3}) + "OK (2 tests)\n",
            "failure": good.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: -2", 1),
            "incomplete": good.replace("INSTRUMENTATION_CODE: -1", ""),
            "summary": good.replace("OK (2 tests)", "OK (1 test)"),
        }
        with tempfile.TemporaryDirectory() as root:
            log = Path(root) / "ordinary.log"
            for label, text in bad_logs.items():
                with self.subTest(label=label):
                    fake = FakeShell()
                    scope = DeviceScope(fake, Path(root))
                    log.write_text(text)
                    with self.assertRaises(ValueError):
                        prepare_after_ordinary(scope, discovery, log)
                    self.assertEqual([], fake.commands)
                    self.assertIsNone(scope.before)
            log.write_text(good)
            fake = FakeShell()
            scope = DeviceScope(fake, Path(root))
            try:
                prepare_after_ordinary(scope, discovery, log)
                self.assertTrue(all(fake.permissions.values()))
            finally:
                scope.restore()

    def test_original_method_needs_passing_terminal_and_one_test_summary(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "instrumentation.log"
            formatter = shard_tests.ShardSelectionTests()
            good = formatter.log([shards.SSID_DESCRIPTION]) + "OK (1 test)\n"
            path.write_text(good)
            validate_isolated_log(path)
            for bad in (good.replace("OK (1 test)", ""), good.replace("OK (1 test)", "OK (2 tests)"),
                        formatter.log([shards.SSID_DESCRIPTION], {shards.SSID_DESCRIPTION: -4}) + "OK (1 test)\n",
                        good.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0")):
                path.write_text(bad)
                with self.assertRaises(ValueError):
                    validate_isolated_log(path)


if __name__ == "__main__":
    unittest.main()
