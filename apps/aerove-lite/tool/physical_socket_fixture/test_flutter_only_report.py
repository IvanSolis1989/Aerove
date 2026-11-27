"""Regression tests for the diagnostic public-output boundary."""
import json
from pathlib import Path
import tempfile
import unittest

from flutter_only_report import collect, first_gradle_failure


class FailureReportTest(unittest.TestCase):
    def test_first_failure_uses_only_allowlisted_labels(self):
        raw = (b"setup: status code 404\nFAILURE: Build completed with 2 failures.\n"
               b"* Where:\nSettings file '/private/TOKEN_NEVER_PRINT/settings.gradle.kts' line: 13\n"
               b"* What went wrong:\nError resolving plugin [id: 'PRIVATE_ID']\n"
               b"> Could not GET 'https://internal.invalid/?key=SECRET'. Received status code 429\n"
               b"* Try:\n status code 401\n* What went wrong:\nNo space left on device\n")
        result = first_gradle_failure(raw)
        self.assertEqual(result, {"site": "settings_script",
                                  "categories": ["repository_rate_limit", "settings_evaluation", "dependency_resolution"]})
        encoded = json.dumps(result)
        for secret in ("TOKEN_NEVER_PRINT", "PRIVATE_ID", "internal.invalid", "SECRET"):
            self.assertNotIn(secret, encoded)

    def test_unknown_fails_closed(self):
        raw = (b"* Where:\r\nInitialization script '/REDACT/odd.gradle' line: 9\r\n"
               b"* What went wrong:\r\nsensitive unknown error\r\n* Try:\r\nstatus code 403\r\n")
        self.assertEqual(first_gradle_failure(raw),
                         {"site": None, "categories": ["unclassified_gradle_failure"]})

    def test_early_daemon_exit_without_fatal_header(self):
        self.assertEqual(first_gradle_failure(b"Gradle build daemon disappeared unexpectedly"),
                         {"site": None, "categories": ["gradle_daemon_exit"]})

    def test_receipt_only_classifies_before_flutter_and_retains_gate(self):
        with tempfile.TemporaryDirectory() as root:
            project = Path(root)
            log = project / "log"
            phases = project / "phases"
            log.write_bytes(b"* What went wrong:\nMissing expected Flutter app project\n")
            phases.write_bytes(b"")
            result = collect(log, phases, project, 1, 28)
            self.assertEqual(result["firstGradleFailure"]["categories"], ["missing_flutter_project"])
            self.assertIsNone(result["arm64AotSha256"])
            self.assertFalse(result["releaseAccepted"])
            phases.write_bytes(b"flutter_task_started\n")
            self.assertIsNone(collect(log, phases, project, 1, 28)["firstGradleFailure"])


if __name__ == "__main__":
    unittest.main()
