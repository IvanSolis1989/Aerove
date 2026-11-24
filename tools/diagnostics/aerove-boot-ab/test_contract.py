"""Host contract tests; no real Android guest, device data, or GitHub write."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

from boot_ab import schedule
from pin_guest import verify_members
from public_summary import SEQUENCE, sanitize


class PublicScopeTests(unittest.TestCase):
    def receipt(self):
        return dict(diagnosticOnly=True, acceptance=False, seed="AEROVE-373-host-pin-a",
                    pairs=schedule("AEROVE-373-host-pin-a", 4), experimentComplete=True,
                    results=[dict(round=i, arm=arm, status="boot-and-route-observed", graphicsRoute={"private": "private-token"},
                                  graphicsFilesSha256={"raw": "private-path"}, cleanup={"returnCode": 0},
                                  command=["private-cmd"], ownerPid=991827, ownedCrashpad="private-crashpad")
                             for i, arm in SEQUENCE], cpuInfoFirstEntry="private-cpu")

    def test_real_entrypoint_outputs_only_allowlisted_summary(self):
        raw = self.receipt()
        raw["results"][0].update(status="failed", failureStage="bootstrap", error="private-error")
        from json import dumps
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "experiment.json"
            path.write_text(dumps(raw))
            child = subprocess.run([sys.executable, "-I", str(Path(__file__).with_name("public_summary.py")),
                                    "--receipt", str(path), "--completed-process"], capture_output=True, text=True, check=True)
            self.assertEqual(child.stderr, "")
            self.assertIn("A 3/4; B 4/4", child.stdout)
            self.assertIn("failed: bootstrap", child.stdout)
            for text in ("private-token", "private-path", "private-crashpad", "private-error", "private-cpu", "991827"):
                self.assertNotIn(text, child.stdout)

    def test_bad_arm_raw_error_and_incomplete_or_canceled_fail_closed(self):
        raw = self.receipt()
        raw["results"][0]["arm"] = "A|private"
        self.assertIsNone(sanitize(raw, completed_process=True, receipt_digest="1"*64))
        raw = self.receipt()
        raw["results"][0].update(status="failed", failureStage="private-log")
        self.assertIsNone(sanitize(raw, completed_process=True, receipt_digest="1"*64))
        raw = self.receipt()
        raw["results"] = raw["results"][:2]
        report = sanitize(raw, completed_process=False, receipt_digest="1"*64)
        self.assertIn("Experiment incomplete", report)
        self.assertNotIn("Boot observations:", report)
        raw = self.receipt()
        self.assertIn("Experiment incomplete", sanitize(raw, completed_process=False, receipt_digest="1"*64))

    def test_image_members_require_matching_contents_and_no_symlink(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory)
            image = p / "installed"
            image.mkdir()
            archive = p / "archive.zip"
            with zipfile.ZipFile(archive, "w") as out:
                for file in ("source.properties", "system.img", "ramdisk.img"):
                    (image / file).write_bytes(file.encode())
                    out.writestr("x86_64/" + file, file)
            (image / "package.xml").write_bytes(b"local managed metadata")
            verify_members(archive, image)
            (image / "system.img").write_bytes(b"not actual")
            with self.assertRaises(ValueError):
                verify_members(archive, image)
            (image / "system.img").unlink()
            (p / "outside").write_bytes(b"system.img")
            (image / "system.img").symlink_to(p / "outside")
            with self.assertRaises(ValueError):
                verify_members(archive, image)
            (image / "system.img").unlink()
            (image / "system.img").write_bytes(b"system.img")
            with zipfile.ZipFile(archive, "a") as out:
                out.writestr("x86_64/../not-in-image", "")
            with self.assertRaises(ValueError):
                verify_members(archive, image)


if __name__ == "__main__":
    unittest.main()
