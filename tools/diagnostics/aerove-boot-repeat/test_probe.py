from pathlib import Path
import os
import subprocess
import sys
import tempfile
import unittest
from probe import status, failure_bucket, BOOT, ANGLE, KEYGUARD

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

class OwnerIntegration(unittest.TestCase):
    def run_probe(self, early):
        with tempfile.TemporaryDirectory() as tmp:
            private = Path(tmp) / "private"
            private.mkdir()
            sdk = Path(tmp) / "sdk"
            adb, emulator = sdk / "platform-tools/adb", sdk / "emulator/emulator"
            for script in (adb, emulator):
                script.parent.mkdir(parents=True)
            adb.write_text("""#!/bin/sh
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
            emulator.write_text("#!/bin/sh\n" + ("echo PRIVATE_SENTINEL >&2; exit 27\n" if early else "exec sleep 20\n"))
            for script in (adb, emulator):
                script.chmod(0o755)
            probe = Path(__file__).resolve().with_name("probe.py")
            repo = probe.parents[3]
            env = dict(os.environ, ANDROID_HOME=str(sdk), PATH=f"{adb.parent}:{os.environ['PATH']}")
            result = subprocess.run([sys.executable, "-I", str(probe), str(repo), str(private)],
                                  timeout=8, capture_output=True, text=True, env=env)
            self.assertTrue((private / "owned-cleanup-ok").is_file(), result.stderr)
            return result

    def test_helper_success(self):
        result = self.run_probe(False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("keyguard-ready", result.stdout)
        self.assertIn("cleanup: `false`", result.stdout)
        self.assertNotIn("PRIVATE_SENTINEL", result.stdout)

    def test_helper_early_child_exit(self):
        result = self.run_probe(True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("cleanup: `true`", result.stdout)
        self.assertIn("exit status: `27`", result.stdout)
        self.assertIn("outcome class: `host-child-exit`", result.stdout)
        self.assertNotIn("PRIVATE_SENTINEL", result.stdout)

if __name__ == "__main__":
    unittest.main()
