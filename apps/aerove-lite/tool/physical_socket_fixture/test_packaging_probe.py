"""Check that the packaging probe is fail-closed and cannot echo raw logs."""
import json
from pathlib import Path
import tempfile
import unittest

from packaging_probe import inspect, plugin_evidence, REQUIRED


class PackagingProbeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.log = self.home / "log"
        self.flutter = self.home / "flutter"
        self.keep = self.home / "keep"
        self.flutter.write_bytes(b"")
        self.keep.write_bytes(b"")

    def check_phase(self, phase, code=0):
        return inspect(phase, self.log, code, 1, self.home, self.flutter, self.keep)

    def test_exact_graph_tasks_required_once(self):
        lines = [task + b" SKIPPED\n" for task in REQUIRED]
        self.log.write_bytes(b"".join(lines))
        self.assertTrue(self.check_phase("plan")[1])
        self.log.write_bytes(b"".join(lines + lines[-1:]))
        self.assertFalse(self.check_phase("plan")[1])
        self.log.write_bytes(b"".join([lines[0], lines[2], lines[1], *lines[3:]]))
        self.assertFalse(self.check_phase("plan")[1])
        self.log.write_bytes(b"".join(lines[:-1]))
        self.assertFalse(self.check_phase("plan")[1])

    def test_failed_assembly_does_not_disclose_private_lines(self):
        self.log.write_bytes(b"> Task :app:minifyReleaseWithR8 FAILED\n"
                             b"* What went wrong:\nCould not GET https://private.invalid/SECRET\n"
                             b"* Try:\nTOKEN\n")
        self.keep.write_bytes(b"unknown private SECRET\n")
        result, valid = self.check_phase("assembly", 1)
        self.assertFalse(valid)
        self.assertEqual(result["lastKnownGradleTask"], ":app:minifyReleaseWithR8")
        self.assertEqual(result["firstGradleFailure"]["categories"], ["dependency_resolution"])
        self.assertEqual(result["keepRulePhases"], [])
        for secret in ("private.invalid", "SECRET", "TOKEN"):
            self.assertNotIn(secret, json.dumps(result))

    def test_plugin_evidence_exposes_only_pinned_labels(self):
        raw = (
            b"Failed to GET resource. HTTP 429 https://plugins.gradle.org/m2/SECRET\n"
            b"Received status code 403 from https://private.invalid/SECRET\n"
            b"* Where:\nBuild file '/PRIVATE_ROOT/apps/aerove-lite/build-logic/build.gradle.kts' line: 1\n"
            b"* What went wrong:\nPlugin [id: 'org.gradle.kotlin.kotlin-dsl', version: 'PRIVATE_VERSION']"
            b" was not found in any of the following sources:\n* Try:\n"
        )
        result = plugin_evidence(raw)
        self.assertEqual(result, {"pluginId": "org.gradle.kotlin.kotlin-dsl", "buildRole": "lite_build_logic",
                                  "observedRepositoryHttp": [{"repository": "gradle_plugin_portal", "status": 429}]})
        for secret in ("PRIVATE_ROOT", "PRIVATE_VERSION", "SECRET", "private.invalid"):
            self.assertNotIn(secret, json.dumps(result))
        self.assertEqual(plugin_evidence(raw.replace(b"org.gradle.kotlin.kotlin-dsl", b"secret.id"))["pluginId"], "other")

    def test_missing_assembly_evidence_does_not_pass(self):
        self.log.write_bytes(b"BUILD SUCCESSFUL\n")
        result, valid = self.check_phase("assembly")
        self.assertFalse(valid)
        self.assertFalse(result["pairVerified"])
        self.assertFalse(result["releaseAccepted"])


if __name__ == "__main__":
    unittest.main()
