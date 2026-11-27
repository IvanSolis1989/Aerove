import json
from pathlib import Path
import tempfile
import unittest

from diagnostic_native_origin import BASE, PINS, verify_origin, workflow_identity
from packaging_probe import failed_task_evidence


class ProvenanceTest(unittest.TestCase):
    def test_enforces_exact_native_identity_and_archive_binding(self):
        with tempfile.TemporaryDirectory() as path:
            root = Path(path)
            (root / "core").mkdir()
            receipt = root / "core/provenance.json"
            origin = root / "original-identity.json"
            verified = {"baseline": BASE, "releaseAccepted": False,
                        "artifacts": {k: {"id": i, "archiveSha256": s} for k, (i, _, s) in PINS.items()}}
            origin.write_text(json.dumps(verified))
            receipt.write_text(json.dumps(workflow_identity()))
            verify_origin(root)
            receipt.write_text(json.dumps(dict(workflow_identity(), workflowRun="1")))
            with self.assertRaises(ValueError):
                verify_origin(root)
            receipt.write_text(json.dumps(workflow_identity()))
            verified["artifacts"]["core"]["archiveSha256"] = "0" * 64
            origin.write_text(json.dumps(verified))
            with self.assertRaises(ValueError):
                verify_origin(root)

    def test_failure_task_has_no_unrecognized_name(self):
        raw = (b"> Task :app:compileReleaseKotlin FAILED\n"
               b"Execution failed for task ':app:verifyNativeCore'.\n")
        self.assertEqual(failed_task_evidence(raw), {"role": "app", "name": ":app:verifyNativeCore"})
        self.assertEqual(failed_task_evidence(b"Execution failed for task ':app:SECRET'.\n"),
                         {"role": "app", "name": None})


if __name__ == "__main__":
    unittest.main()
