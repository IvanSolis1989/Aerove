import copy
import hashlib
import json
from pathlib import Path
import tempfile
import time
import unittest
import zipfile
import binascii
import struct
import zlib

import artifact_evidence as reader


class ArtifactEvidenceTest(unittest.TestCase):
    def test_png_metadata_rejected_without_output_and_plain_png_unchanged(self):
        def chunk(kind, data):
            return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", binascii.crc32(kind + data) & 0xffffffff)

        head = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0))
        tail = chunk(b"IDAT", zlib.compress(b"\0\0\0\0\xff")) + chunk(b"IEND", b"")
        image = head + tail
        receipt, output = self.consume([("capture.png", image)], basenames=["capture.png"])
        self.assertTrue(receipt["allEntryCrcVerified"])
        self.assertEqual(output, {"member-01.png": image})
        metadata = b"https://invalid.example/?sig=SYNTHETIC_NEVER_VALID"
        self.assertTrue(reader.sensitive_text(metadata.decode()))
        for kind, data in ((b"tEXt", b"Comment\0" + metadata), (b"iTXt", metadata), (b"zTXt", zlib.compress(metadata)),
                           (b"iCCP", zlib.compress(metadata)), (b"vpAg", metadata)):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                archive = self.package(root, [("capture.png", head + chunk(kind, data) + tail)])
                request = self.request(expectedZIPsha=hashlib.sha256(archive.read_bytes()).hexdigest(),
                                       expectedZIPbytes=archive.stat().st_size, basenames=["capture.png"])
                out = root / "out"
                out.mkdir()
                with self.assertRaisesRegex(ValueError, "Unsupported evidence PNG") as caught:
                    reader.consume(archive, request, out, {}, time.monotonic() + 5)
                self.assertNotIn("SYNTHETIC", str(caught.exception))
                self.assertEqual(list(out.iterdir()), [])

    def test_directory_crc_actually_read_before_all_entry_claim(self):
        receipt, output = self.consume([("empty/", b""), ("metrics.json", b"{}")])
        self.assertTrue(receipt["allEntryCrcVerified"])
        self.assertEqual(output, {"member-01.json": b"{}"})
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = self.package(root, [("empty/", b""), ("metrics.json", b"{}")])
            data = bytearray(archive.read_bytes())
            local, central = data.index(b"PK\x03\x04"), data.index(b"PK\x01\x02")
            struct.pack_into("<I", data, local + 14, 1)
            struct.pack_into("<I", data, central + 16, 1)
            archive.write_bytes(data)
            with zipfile.ZipFile(archive) as package, self.assertRaises(zipfile.BadZipFile):
                package.open("empty/").read()
            request = self.request(expectedZIPsha=hashlib.sha256(data).hexdigest(), expectedZIPbytes=len(data))
            out = root / "out"
            out.mkdir()
            receipt = {}
            with self.assertRaises(zipfile.BadZipFile):
                reader.consume(archive, request, out, receipt, time.monotonic() + 5)
            self.assertFalse(receipt.get("allEntryCrcVerified", False))
            self.assertEqual(list(out.iterdir()), [])

    def request(self, **changes):
        value = dict(runId=148, attempt=1, headSHA="a" * 40, artifactId=20, artifactName="original-attempt-1",
                     expectedZIPsha="b" * 64, expectedZIPbytes=100, mode="select", basenames=["metrics.json"])
        value.update(changes)
        return value

    def package(self, root, rows):
        archive = root / "original.zip"
        with zipfile.ZipFile(archive, "w") as stream:
            for name, data in rows:
                if isinstance(name, str):
                    entry = zipfile.ZipInfo()
                    entry.filename = name
                    name = entry
                stream.writestr(name, data)
        return archive

    def consume(self, rows, **changes):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = self.package(root, rows)
            request = self.request(expectedZIPsha=hashlib.sha256(archive.read_bytes()).hexdigest(), expectedZIPbytes=archive.stat().st_size)
            request.update(changes)
            out = root / "out"
            out.mkdir()
            receipt = {}
            reader.consume(archive, request, out, receipt, time.monotonic() + 5)
            return receipt, {p.name: p.read_bytes() for p in out.iterdir()}

    def test_exact_json_readback_and_raw_hash(self):
        data = b'{"elapsed":12}\n'
        receipt, outputs = self.consume([("capture/metrics.json", data)])
        self.assertTrue(receipt["allEntryCrcVerified"])
        self.assertEqual(outputs, {"member-01.json": data})
        self.assertEqual(receipt["outputScope"]["members"][0]["rawSha256"], hashlib.sha256(data).hexdigest())

    def test_api_source_attempt_and_digest_substitution_rejected(self):
        request = self.request()
        run = dict(id=148, head_sha="a" * 40, run_attempt=1, status="completed", repository=dict(full_name=reader.REPO),
                   head_repository=dict(full_name=reader.REPO), run_started_at="2026-10-27T00:00:00Z", updated_at="2026-10-27T00:02:00Z")
        artifact = dict(id=20, name=request["artifactName"], size_in_bytes=100, expired=False, digest="sha256:" + "b" * 64,
                        workflow_run=dict(id=148, head_sha="a" * 40), created_at="2026-10-27T00:01:00Z")
        reader.validate_api(request, run, artifact)
        for field, value in (("digest", "sha256:" + "c" * 64), ("created_at", "2026-10-26T00:00:00Z")):
            changed = copy.deepcopy(artifact)
            changed[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                reader.validate_api(request, run, changed)
        for field, value in (("head_sha", "c" * 40), ("run_attempt", 2)):
            changed = dict(run, **{field: value})
            with self.subTest(field=field), self.assertRaises(ValueError):
                reader.validate_api(request, changed, artifact)

    def test_zip_digest_replacement_rejected(self):
        with self.assertRaisesRegex(ValueError, "ZIP digest"):
            self.consume([("metrics.json", b"{}")], expectedZIPsha="0" * 64)

    def test_traversal_absolute_duplicates_and_symlink_rejected(self):
        for name in ("../metrics.json", "/metrics.json", "a/../metrics.json", "a\\metrics.json"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.consume([(name, b"{}")])
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            self.consume([("A/metrics.json", b"{}"), ("a/metrics.json", b"{}")])
        entry = zipfile.ZipInfo("metrics.json")
        entry.create_system = 3
        entry.external_attr = 0o120777 << 16
        with self.assertRaisesRegex(ValueError, "unsupported"):
            self.consume([(entry, b"target")])

    def test_sensitive_selection_emits_no_payload(self):
        for value in ('{"Authorization":"Bearer SYNTHETIC"}', '{"sig":"SYNTHETIC"}', '{"url":"https://invalid.example/?sig=NEVER_VALID"}'):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                archive = self.package(root, [("first.txt", b"clean"), ("metrics.json", value.encode())])
                out = root / "out"
                out.mkdir()
                request = self.request(expectedZIPsha=hashlib.sha256(archive.read_bytes()).hexdigest(), expectedZIPbytes=archive.stat().st_size,
                                       basenames=["first.txt", "metrics.json"])
                with self.assertRaisesRegex(ValueError, "suspected credential") as caught:
                    reader.consume(archive, request, out, {}, time.monotonic() + 5)
                self.assertNotIn("SYNTHETIC", str(caught.exception))
                self.assertEqual(list(out.iterdir()), [])

    def test_output_limit_and_ambiguous_basename_rejected(self):
        with self.assertRaisesRegex(ValueError, "output limit"):
            self.consume([("metrics.json", b" " * (reader.MAX_OUTPUT - 262143))])
        with self.assertRaisesRegex(ValueError, "ambiguous"):
            self.consume([("a/metrics.json", b"{}"), ("b/metrics.json", b"{}")])

    def test_nonselected_corruption_is_not_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = self.package(root, [("metrics.json", b"{}"), ("unselected.bin", b"ORIGINAL_PAYLOAD")])
            data = archive.read_bytes().replace(b"ORIGINAL_PAYLOAD", b"CORRUPTED_BYTES!")
            archive.write_bytes(data)
            request = self.request(expectedZIPsha=hashlib.sha256(data).hexdigest(), expectedZIPbytes=len(data))
            with self.assertRaises(zipfile.BadZipFile):
                reader.consume(archive, request, root, {}, time.monotonic() + 5)
            self.assertFalse((root / "member-01.json").exists())

    def test_schema_limits_and_index(self):
        reader.parse_request(json.dumps(self.request()))
        for changes in (dict(expectedZIPbytes=reader.MAX_ZIP + 1), dict(basenames=["x.txt"] * 9), dict(mode="index")):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                reader.parse_request(json.dumps(self.request(**changes)))
        receipt, output = self.consume([("metrics.json", b"{}")], mode="index")
        self.assertTrue(receipt["allEntryCrcVerified"])
        self.assertEqual(set(output), {"INDEX.json"})


if __name__ == "__main__":
    unittest.main()
