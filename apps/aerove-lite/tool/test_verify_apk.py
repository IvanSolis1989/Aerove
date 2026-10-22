import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import warnings
import zipfile

spec = importlib.util.spec_from_file_location("verify_apk", Path(__file__).with_name("verify_apk.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ApkInspectionTests(unittest.TestCase):
    def product(self):
        return {"applicationId": "dev.aerove.lite", "version": "0.1.0-beta.1", "versionCode": "1",
            "channel": "preview", "signerSha256": "a" * 64}

    def badging(self, package="dev.aerove.lite", version="0.1.0-beta.1", sdk=33):
        return f"package: name='{package}' versionCode='1' versionName='{version}'\nsdkVersion:'{sdk}'\n"

    def test_preview_rejects_other_signing_certificate(self):
        with self.assertRaisesRegex(ValueError, "signing certificate"):
            module.verify_identity(self.badging(), "Signer #1 certificate SHA-256 digest: " + "b" * 64,
                self.product(), "preview")

    def test_preview_rejects_debug_package_and_old_version(self):
        signature = "Signer #1 certificate SHA-256 digest: " + "a" * 64
        for package, version in (("dev.aerove.lite.dev", "0.1.0-beta.1+debug"), ("dev.aerove.lite", "0.1.0-dev.1")):
            with self.subTest(package=package, version=version), self.assertRaisesRegex(ValueError, "identity or version"):
                module.verify_identity(self.badging(package, version), signature, self.product(), "preview")

    def test_preview_identity_checks_minimum_sdk_and_unique_signer(self):
        signature = "Signer #1 certificate SHA-256 digest: " + "a" * 64
        self.assertEqual(module.verify_identity(self.badging(), signature, self.product(), "preview")["version"], "0.1.0-beta.1")
        with self.assertRaisesRegex(ValueError, "minimum SDK"):
            module.verify_identity(self.badging(sdk=32), signature, self.product(), "preview")
        with self.assertRaisesRegex(ValueError, "signing certificate"):
            module.verify_identity(self.badging(), signature + "\n" + signature, self.product(), "preview")

    def test_duplicate_version_identity_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "version.properties"
            path.write_text("applicationId=dev.aerove.lite\napplicationId=other\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "duplicate"):
                module.product_version(path)

    def make_apk(self, root, abi="arm64-v8a", machine=183, duplicate=False, include_core=True):
        path = Path(root) / "candidate.apk"
        header = bytearray(64)
        header[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<H", header, 18, machine)
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"manifest fixture")
            archive.writestr("classes.dex", b"dex fixture")
            if include_core:
                archive.writestr(f"lib/{abi}/libaerove_core.so", header)
            if duplicate:
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    archive.writestr("AndroidManifest.xml", b"second manifest")
        return path

    def test_rejects_wrong_elf_even_under_arm64_filename(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaisesRegex(ValueError, "ELF"):
                module.verify(self.make_apk(root, machine=62), "arm64-v8a")

    def test_rejects_missing_real_core(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaisesRegex(ValueError, "core is missing"):
                module.verify(self.make_apk(root, include_core=False), "arm64-v8a")

    def test_rejects_ambiguous_zip(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                module.verify(self.make_apk(root, duplicate=True), "arm64-v8a")

    def test_rejects_other_abi(self):
        with tempfile.TemporaryDirectory() as root:
            apk = self.make_apk(root)
            with zipfile.ZipFile(apk, "a") as archive:
                archive.writestr("lib/x86_64/libextra.so", b"foreign ABI")
            with self.assertRaisesRegex(ValueError, "Unexpected ABI"):
                module.verify(apk, "arm64-v8a")


if __name__ == "__main__":
    unittest.main()
