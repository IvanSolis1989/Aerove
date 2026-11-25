from pathlib import Path
import os
import subprocess
import sys
import tempfile
import unittest
from probe import status, failure_bucket, wait_for_empty_adb, host_signature, PORT_BY_AVD, BOOT, ANGLE, KEYGUARD

def row(args, code, value=""):
    return dict(arguments=args, returnCode=code, stdout=value, stderr="SECRET_DO_NOT_PUBLISH")

class Milestones(unittest.TestCase):
    def test_preflight(self):
        self.assertEqual(status({}, False, False), ("preflight", False))

    def test_early_exit(self):
        self.assertEqual(status({"commands":[row(BOOT, 1)]}, True, True), ("process-allocated", True))

    def test_shell_even_when_boot_not_completed(self):
        self.assertEqual(status({"commands":[row(BOOT, 0)]}, False, True), ("adb-shell", False))

    def test_readback_requires_verified_and_exact(self):
        report = {"commands":[row(BOOT, 0, "1"), row(ANGLE, 0, "supportsHostQueryReset")]}
        self.assertEqual(status(report, False, True), ("adb-shell", False))
        report["guestHostQueryResetCompat"] = {"propertyReadbackVerified": True}
        self.assertEqual(status(report, False, True), ("android-boot-complete", False))
        report["commands"][0]["stdout"] = "0"
        self.assertEqual(status(report, False, True), ("angle-readback", False))

    def test_keyguard_needs_helper_success_not_only_dumpsys(self):
        report = {"commands":[row(KEYGUARD, 0)]}
        self.assertEqual(status(report, True, True), ("process-allocated", True))
        report["ready"] = True
        self.assertEqual(status(report, True, True), ("keyguard-ready", True))

class FailureBuckets(unittest.TestCase):
    def test_exit_cannot_be_misreported_as_readiness(self):
        self.assertEqual(failure_bucket({"errorType": "TimeoutError"}, True, -11, True, False),
                         "host-child-signal")

    def test_deadline_and_readback_are_distinct(self):
        self.assertEqual(failure_bucket({"errorType": "TimeoutError"}, False, None, False, False),
                         "guest-readiness-deadline")
        self.assertEqual(failure_bucket({"errorType": "ValueError"}, False, None, False, False),
                         "guest-readiness-readback")

    def test_untrusted_class_and_interrupt(self):
        self.assertEqual(failure_bucket({"errorType": "DO_NOT_PUBLISH"}, False, None, False, False),
                         "unclassified")
        self.assertEqual(failure_bucket({}, True, -11, True, True), "interrupted")

class InventoryDrain(unittest.TestCase):
    def check(self, states, expected, sleeps, previous="emulator-5554"):
        rows = iter(states)
        pauses = []
        def read():
            return "List of devices attached\n" + next(rows)
        self.assertEqual(wait_for_empty_adb(None, previous, read, pauses.append), expected)
        self.assertEqual(pauses, [1]*sleeps)

    def test_owned_offline_can_drain(self):
        self.check(["emulator-5554\toffline\n", ""], "none", 1)

    def test_connected_or_unknown_target_fails_immediately(self):
        for row in ("emulator-5554\tdevice", "other\toffline", "emulator-5554\toffline\nother\tdevice"):
            with self.subTest(row=row):
                self.check([row], "adb-unexpected-target", 0)
        self.check(["emulator-5554\toffline"], "adb-unexpected-target", 0, None)

    def test_offline_wait_is_bounded(self):
        self.check(["emulator-5554\toffline"]*7, "adb-previous-offline-not-drained", 6)

class OwnerIntegration(unittest.TestCase):
    def run_probe(self, early, name='test'):
        with tempfile.TemporaryDirectory() as tmp:
            private = Path(tmp) / "private"
            private.mkdir()
            sdk = Path(tmp) / "sdk"
            adb, emulator = sdk / "platform-tools/adb", sdk / "emulator/emulator"
            for script in (adb, emulator):
                script.parent.mkdir(parents=True)
            adb.write_text("""#!/bin/sh
if [ "$1" = -s ] && [ "$2" != "$EXPECTED_SERIAL" ]; then exit 55; fi
if [ "$1" = devices ]; then echo 'List of devices attached'; exit; fi
case "$*" in
  *'getprop sys.boot_completed') echo 1;;
  *'getprop debug.angle.feature_overrides_disabled') echo supportsHostQueryReset;;
  *'dumpsys window policy')
    echo 'KeyguardServiceDelegate
showing=false
inputRestricted=false
systemIsReady=true
bootCompleted=true
screenState=SCREEN_STATE_ON
interactiveState=INTERACTIVE_STATE_AWAKE';;
  *'logcat'*) echo PRIVATE_SENTINEL;;
esac
""")
            emulator.write_text('#!/bin/sh\n[ "$1" = -port ] && [ "$2" = "$EXPECTED_PORT" ] && [ "$3" = -avd ] && [ "$4" = "$EXPECTED_NAME" ] || exit 56\n' + ("echo PRIVATE_SENTINEL >&2; exit 27\n" if early else "exec sleep 20\n"))
            for script in (adb, emulator):
                script.chmod(0o755)
            probe = Path(__file__).resolve().with_name("probe.py")
            repo = probe.parents[3]
            port = str(PORT_BY_AVD[name])
            env = dict(os.environ, ANDROID_HOME=str(sdk), EXPECTED_SERIAL="emulator-"+port,
                       EXPECTED_PORT=port, EXPECTED_NAME=name, PATH=f"{adb.parent}:{os.environ['PATH']}")
            result = subprocess.run([sys.executable, "-I", str(probe), str(repo), str(private), name],
                                  timeout=8, capture_output=True, text=True, env=env)
            self.assertTrue((private / "owned-cleanup-ok").is_file(), result.stderr)
            return result

    def test_helper_success(self):
        result = self.run_probe(False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("keyguard-ready", result.stdout)
        self.assertIn("cleanup: `false`", result.stdout)
        self.assertNotIn("PRIVATE_SENTINEL", result.stdout)

    def test_second_arm_uses_independent_exact_ports(self):
        result = self.run_probe(False, "test_repeat")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("keyguard-ready", result.stdout)

    def test_host_signature_is_allowlisted(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "stderr"
            path.write_text("private-marker\nERROR: Port 5556 is already in use")
            self.assertEqual(host_signature(path), "socket-conflict")
            path.write_text("private-marker\nUnknown AVD name\nerror while loading shared libraries: libExample")
            self.assertEqual(host_signature(path), "multiple-signatures")
            path.write_text("private-marker")
            self.assertEqual(host_signature(path), "no-known-signature")

    def test_helper_early_child_exit(self):
        result = self.run_probe(True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("cleanup: `true`", result.stdout)
        self.assertIn("exit status: `27`", result.stdout)
        self.assertIn("outcome class: `host-child-exit`", result.stdout)
        self.assertNotIn("PRIVATE_SENTINEL", result.stdout)

if __name__ == "__main__":
    unittest.main()
