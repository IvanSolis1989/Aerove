import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("protected_dependencies", Path(__file__).with_name("prepare.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ReplayTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bundle = self.root / "bundle"
        self.bundle.mkdir()
        self.cache = self.root / "cache"
        self.cache.mkdir()
        self.old_root = MODULE.ROOT
        MODULE.ROOT = self.bundle
        self.addCleanup(setattr, MODULE, "ROOT", self.old_root)
        blob = io.BytesIO()
        with tarfile.open(fileobj=blob, mode="w:gz") as archive:
            info = tarfile.TarInfo("sample-0.6.1/src/lib.rs")
            info.size = len(b"before\n")
            archive.addfile(info, io.BytesIO(b"before\n"))
        self.archive = self.cache / "sample-0.6.1.crate"
        self.archive.write_bytes(blob.getvalue())
        self.patch = self.root / "tailscale-protect.patch"
        self.patch.write_text("--- a/sample-0.6.1/src/lib.rs\n+++ b/sample-0.6.1/src/lib.rs\n@@ -1 +1 @@\n-before\n+after\n", encoding="utf-8", newline="\n")
        self.helper = self.bundle / "underlay/src/lib.rs"
        self.helper.parent.mkdir(parents=True)
        self.helper.write_bytes(b"helper\n")
        self.manifest = {
            "patch_sha256": MODULE.digest(self.patch.read_bytes()),
            "packages": [{"name": "sample", "version": "0.6.1", "sha256": MODULE.digest(self.archive.read_bytes())}],
            "files": [{"path": "sample-0.6.1/src/lib.rs", "before_sha256": MODULE.digest(b"before\n"), "after_sha256": MODULE.digest(b"after\n")}],
            "helper_files": [{"path": "src/lib.rs", "sha256": MODULE.digest(b"helper\n")}],
        }
        self.save_manifest()

    def save_manifest(self):
        (self.bundle / "manifest.json").write_text(json.dumps(self.manifest), encoding="utf-8")

    def test_exact_replay_and_existing_destination_refusal(self):
        destination = self.root / "output"
        paths = MODULE.prepare(destination, self.cache)
        self.assertEqual((paths["sample"] / "src/lib.rs").read_bytes(), b"after\n")
        self.assertEqual((paths["aerove-ts-underlay"] / "src/lib.rs").read_bytes(), b"helper\n")
        with self.assertRaises(FileExistsError):
            MODULE.prepare(destination, self.cache)

    def test_archive_patch_and_helper_tampering_fail_before_destination_creation(self):
        for file in (self.archive, self.patch, self.helper):
            original = file.read_bytes()
            file.write_bytes(original + b"tampered")
            destination = self.root / "blocked"
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                MODULE.prepare(destination, self.cache)
            self.assertFalse(destination.exists())
            file.write_bytes(original)

    def test_source_and_result_hash_mismatch_are_rejected(self):
        for field in ("before_sha256", "after_sha256"):
            original = self.manifest["files"][0][field]
            self.manifest["files"][0][field] = "0" * 64
            self.save_manifest()
            with self.assertRaisesRegex(ValueError, "source mismatch"):
                MODULE.prepare(self.root / field, self.cache)
            self.manifest["files"][0][field] = original

    def test_context_mismatch_and_path_traversal_fail(self):
        target = self.root / "source/sample-0.6.1/src/lib.rs"
        target.parent.mkdir(parents=True)
        target.write_bytes(b"wrong\n")
        with self.assertRaisesRegex(ValueError, "context mismatch"):
            MODULE.replay_patch(self.root / "source", self.patch.read_text())
        with self.assertRaisesRegex(ValueError, "unsafe"):
            MODULE.replay_patch(self.root / "source", "--- a/../escape\n+++ b/../escape\n")


if __name__ == "__main__":
    unittest.main()
