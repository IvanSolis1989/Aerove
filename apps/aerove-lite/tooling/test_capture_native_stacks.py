import importlib.util
from pathlib import Path
from types import SimpleNamespace
import tempfile
import time
import unittest
from unittest import mock


spec = importlib.util.spec_from_file_location(
    "capture_native_stacks", Path(__file__).with_name("capture_native_stacks.py"))
capture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capture)

ATTACH_STDOUT = (
    "Could not attach to process.  If your uid matches the uid of the target\n"
    "process, check the setting of /proc/sys/kernel/yama/ptrace_scope, or try\n"
    "again as the root user.  For more details, see /etc/sysctl.d/10-ptrace.conf\n"
)
ATTACH_STDERR = "ptrace: Inappropriate ioctl for device.\nThe program is not being run.\n"
STACK = "Thread 1 (Thread 0x123):\n#0  syscall ()\n"


class CaptureRetryTests(unittest.TestCase):
    def run_capture(self, outputs, identities=None):
        owner = dict(pid=3348, starttime="18066", exe="aerove_core-test",
                     exeDevice=2049, exeInode=9193323, exeSize=950354160,
                     exeMtimeNs=1792752651126488135,
                     cmdline=["aerove_core-test", "observation_tests::foreground"])
        commands = []
        report = {}
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory)

            def run_tool(command, stem, output, deadline, limit=20):
                commands.append((command, deadline, limit))
                stdout, stderr, status, code = outputs[len(commands) - 1]
                (output / (stem + ".stdout")).write_text(stdout)
                (output / (stem + ".stderr")).write_text(stderr)
                return dict(command=command, status=status, exitCode=code)

            deadline = time.monotonic() + 30
            args = SimpleNamespace(target_dir=evidence, evidence_dir=evidence,
                                   filter="observation_tests::foreground")
            with mock.patch.object(capture, "STOP", False), \
                    mock.patch.object(capture, "CAPTURE_DELAY", 0), \
                    mock.patch.object(capture.Path, "iterdir", return_value=[SimpleNamespace(name="3348")]), \
                    mock.patch.object(capture, "identity", side_effect=identities, return_value=owner), \
                    mock.patch.object(capture.shutil, "which", side_effect=lambda name: None if name == "ps" else "/usr/bin/" + name), \
                    mock.patch.object(capture, "run_tool", side_effect=run_tool):
                capture.capture(args, report, deadline)
        return report, commands, deadline

    def test_actual_exit_zero_ptrace_output_retries_once_with_existing_bounds(self):
        report, commands, deadline = self.run_capture([
            (ATTACH_STDOUT, ATTACH_STDERR, "completed", 0),
            (STACK, "", "completed", 0),
        ])
        self.assertEqual(report["status"], "captured")
        self.assertEqual(len(commands), 2)
        self.assertEqual(commands[1][0], ["/usr/bin/sudo", "-n", "--"] + commands[0][0])
        self.assertEqual(commands[0][0][commands[0][0].index("-p") + 1], "3348")
        self.assertEqual([(row[1], row[2]) for row in commands], [(deadline, 20)] * 2)

    def test_existing_capture_is_not_retried(self):
        report, commands, _ = self.run_capture([(STACK, "", "completed", 0)])
        self.assertEqual(report["status"], "captured")
        self.assertEqual(len(commands), 1)

    def test_existing_permission_denials_keep_the_single_sudo_retry(self):
        for error in ["ptrace: Operation not permitted", "ptrace: Permission denied"]:
            with self.subTest(error=error):
                report, commands, _ = self.run_capture([
                    ("", error, "completed", 1), (STACK, "", "completed", 0)])
                self.assertEqual(report["status"], "captured")
                self.assertEqual(len(commands), 2)

    def test_unrelated_or_incomplete_failures_do_not_request_sudo(self):
        for stdout, stderr in [("", "No such file or directory"),
                               ("", ATTACH_STDERR),
                               (ATTACH_STDOUT, "remote connection failed"),
                               ("Could not attach to process.\n", ATTACH_STDERR)]:
            with self.subTest(stdout=stdout, stderr=stderr):
                report, commands, _ = self.run_capture([(stdout, stderr, "completed", 0)])
                self.assertEqual(report["status"], "unavailable")
                self.assertEqual(len(commands), 1)

    def test_timeout_does_not_request_sudo(self):
        report, commands, _ = self.run_capture([(ATTACH_STDOUT, ATTACH_STDERR, "timeout", -15)])
        self.assertEqual(report["status"], "timeout")
        self.assertEqual(len(commands), 1)

    def test_identity_change_prevents_sudo_attachment(self):
        calls = 0

        def identity(*args):
            nonlocal calls
            calls += 1
            if calls == 4:
                return None
            return dict(pid=3348, starttime="18066", exe="aerove_core-test",
                        exeDevice=2049, exeInode=9193323, exeSize=950354160,
                        exeMtimeNs=1792752651126488135,
                        cmdline=["aerove_core-test", "observation_tests::foreground"])

        report, commands, _ = self.run_capture([(ATTACH_STDOUT, ATTACH_STDERR, "completed", 0)], identity)
        self.assertEqual(report["status"], "ended")
        self.assertEqual(len(commands), 1)

    def test_failed_sudo_is_not_retried_or_reported_as_captured(self):
        report, commands, _ = self.run_capture([
            (ATTACH_STDOUT, ATTACH_STDERR, "completed", 0),
            (ATTACH_STDOUT, ATTACH_STDERR, "completed", 0),
        ])
        self.assertEqual(report["status"], "unavailable")
        self.assertEqual(len(commands), 2)


if __name__ == "__main__":
    unittest.main()
