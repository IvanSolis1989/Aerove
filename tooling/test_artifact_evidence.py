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

    def index_request(self, **changes):
        request = self.request()
        del request['basenames']
        request.update(mode='index')
        request.update(changes)
        return reader.parse_request(json.dumps(request))

    def test_filter_schema_and_identity(self):
        self.index_request(pathContains='截图/[a]*?.json')
        for value in ('', None, 12, [], 'a' * 129, '界' * 100, 'a\n', 'a\u202e', 'a\ud800',
                      'https://invalid.example', 'secret=SYNTHETIC'):
            with self.subTest(value=repr(value)), self.assertRaises(ValueError):
                self.index_request(pathContains=value)
        for changes in (dict(mode='select'), dict(mode='INDEX'), dict(headSHA='bad'),
                        dict(expectedZIPsha='bad'), dict(version=2), dict(unknown=True)):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.index_request(pathContains='metrics', **changes)
        with self.assertRaises(ValueError):
            reader.parse_request(json.dumps(self.request(pathContains='metrics')))

    def test_literal_subset_empty_and_unfiltered(self):
        rows = [('截图/[a]*?.json', b'{}'), ('capture/aa.json', b'[]'), ('capture/METRICS.json', b'{}')]
        receipt, output = self.consume(rows, mode='index')
        complete = json.loads(output['INDEX.json'])
        self.assertEqual([r['path'] for r in complete], [r[0] for r in rows])
        self.assertIsNone(receipt['outputScope']['filter'])
        self.assertEqual(receipt['outputScope']['totalEntryCount'], 3)
        for literal, expected in (('[a]*?', complete[:1]), ('metrics', []), ('capture/', complete[1:])):
            receipt, output = self.consume(rows, mode='index', pathContains=literal)
            self.assertEqual(json.loads(output['INDEX.json']), expected)
            self.assertEqual(receipt['outputScope']['totalEntryCount'], 3)
            self.assertEqual(receipt['outputScope']['matchedEntryCount'], len(expected))
            self.assertEqual(receipt['outputScope']['entryCount'], len(expected))
            self.assertEqual(receipt['outputScope']['filter']['pathContains'], literal)
            self.assertTrue(receipt['wholeZipDigestVerified'])
            self.assertTrue(receipt['allEntryCrcVerified'])
        self.assertEqual(complete[0]['rawSha256'], hashlib.sha256(b'{}').hexdigest())

    def test_unmatched_corruption_still_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = self.package(root, [('metrics.json', b'{}'), ('unmatched.bin', b'ORIGINAL_PAYLOAD')])
            data = archive.read_bytes().replace(b'ORIGINAL_PAYLOAD', b'CORRUPTED_BYTES!')
            archive.write_bytes(data)
            request = self.index_request(pathContains='metrics', expectedZIPsha=hashlib.sha256(data).hexdigest(), expectedZIPbytes=len(data))
            out = root / 'out'
            out.mkdir()
            receipt = {}
            with self.assertRaises(zipfile.BadZipFile):
                reader.consume(archive, request, out, receipt, time.monotonic() + 5)
            self.assertFalse(receipt.get('allEntryCrcVerified', False))
            self.assertEqual(list(out.iterdir()), [])

    def test_output_limit_unchanged(self):
        rows = [('capture/' + str(i) + 'x' * 150 + '.json', b'{}') for i in range(600)]
        for changes in ({}, {'pathContains': 'capture/'}):
            with self.subTest(changes=changes), self.assertRaisesRegex(ValueError, 'Index output limit'):
                self.consume(rows, mode='index', **changes)
        receipt, outputs = self.consume(rows, mode='index', pathContains='capture/599x')
        self.assertEqual(receipt['outputScope']['matchedEntryCount'], 1)
        self.assertEqual(receipt['outputScope']['totalEntryCount'], 600)
        self.assertLessEqual(len(outputs['INDEX.json']), 131072)

    def logged(self, rows, **changes):
        import contextlib
        import io
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = self.package(root, rows)
            request = self.request(expectedZIPsha=hashlib.sha256(archive.read_bytes()).hexdigest(), expectedZIPbytes=archive.stat().st_size)
            request.update(changes)
            receipt = dict(original=request)
            out = root / 'out'
            out.mkdir()
            reader.consume(archive, request, out, receipt, time.monotonic() + 5)
            before = {p.name: p.read_bytes() for p in out.iterdir()}
            receipt['completed'] = True
            capture = io.StringIO()
            with contextlib.redirect_stdout(capture):
                reader.log_json_outputs(out, receipt)
            self.assertEqual(before, {p.name: p.read_bytes() for p in out.iterdir()})
            return receipt, capture.getvalue(), before

    def test_json_log_byte_exact_and_json_only(self):
        import base64
        import zlib
        raw = (json.dumps({"samples": ["x" * 14627]}, separators=(",", ":")) + "\n").encode()
        receipt, text, outputs = self.logged([('metrics.json', raw), ('notes.txt', b'plain')], basenames=['metrics.json', 'notes.txt'])
        lines = text.splitlines()
        self.assertEqual(len(lines), 2)
        status = json.loads(lines[0][len('AEROVE_JSON_FRAME_V1 '):-len(' AEROVE_JSON_FRAME_END')])
        frame = json.loads(lines[1][len('AEROVE_JSON_FRAME_V1 '):-len(' AEROVE_JSON_FRAME_END')])
        self.assertEqual(status['type'], 'status')
        encoded = base64.b64decode(frame['data'])
        decoded = zlib.decompress(encoded) if frame['compression'] == 'zlib' else encoded
        self.assertEqual(decoded, raw)
        self.assertEqual(frame['compression'], 'zlib')
        self.assertEqual(frame['sha256'], hashlib.sha256(raw).hexdigest())
        self.assertEqual(frame['bytes'], len(raw))
        self.assertEqual(frame['original']['headSHA'], 'a' * 40)
        self.assertTrue(frame['wholeZipDigestVerified'] and frame['allEntryCrcVerified'])
        self.assertEqual(status['logged'], 1)
        self.assertEqual(len(receipt['jsonLogTransport']['loggedFiles']), 1)
        self.assertNotIn('member-02', text)
        self.assertEqual(outputs['member-01.json'], raw)
    def test_json_log_limits_omit_without_truncation(self):
        import random
        receipt, text, _ = self.logged([('metrics.json', b'"' + b'x' * 16384 + b'"')])
        self.assertEqual(len(text.encode()), receipt['jsonLogTransport']['totalFramedBytes'])
        self.assertLessEqual(len(text.encode()), 16384)
        self.assertEqual(len(text.splitlines()), 1)
        status = json.loads(text.splitlines()[0][len('AEROVE_JSON_FRAME_V1 '):-len(' AEROVE_JSON_FRAME_END')])
        self.assertEqual(status['omittedReasons'], {'single-file byte limit': 1})
        rng = random.Random(4)
        noise = ''.join(rng.choice('abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789') for _ in range(15500))
        receipt, text, _ = self.logged([('metrics.json', json.dumps(noise).encode())])
        self.assertEqual(len(text.splitlines()), 1)
        status = json.loads(text.splitlines()[0][len('AEROVE_JSON_FRAME_V1 '):-len(' AEROVE_JSON_FRAME_END')])
        self.assertEqual(status['omittedReasons'], {'total framed byte limit': 1})
        self.assertLessEqual(len(text.encode()), 16384)
    def test_unicode_identity_and_actual_total_budget_boundary(self):
        import random
        request = self.request(artifactName='界' * 300)
        reader.parse_request(json.dumps(request))
        receipt, text, outputs = self.logged([('metrics.json', b'{}')], artifactName='界' * 300)
        self.assertEqual(outputs['member-01.json'], b'{}')
        self.assertLessEqual(len(text.encode('utf-8')), 16384)
        status = json.loads(text.splitlines()[0][len('AEROVE_JSON_FRAME_V1 '):-len(' AEROVE_JSON_FRAME_END')])
        self.assertEqual(status['original']['artifactName'], '界' * 300)
        self.assertEqual(receipt['jsonLogTransport']['totalFramedBytes'], len(text.encode('utf-8')))

        rng = random.Random(404)
        noise = ''.join(rng.choice('abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789') for _ in range(15000))
        receipt, text, _ = self.logged([('large.json', json.dumps(noise).encode()), ('tiny.json', b'{}')],
                                       basenames=['large.json', 'tiny.json'])
        wire_bytes = len(text.encode('utf-8'))
        self.assertGreater(wire_bytes, 16000)
        self.assertLessEqual(wire_bytes, 16384)
        self.assertEqual(receipt['jsonLogTransport']['totalFramedBytes'], wire_bytes)
        self.assertEqual(len(receipt['jsonLogTransport']['loggedFiles']), 1)
        self.assertEqual(receipt['jsonLogTransport']['omittedFiles'][0]['reason'], 'total framed byte limit')

    def test_json_log_sensitive_recheck_and_failed_integrity_emit_nothing(self):
        import contextlib
        import io
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            secret = b'{"secret":"SYNTHETIC"}'
            (out / 'INDEX.json').write_bytes(secret)
            receipt = dict(original=self.request(), completed=True, wholeZipDigestVerified=True,
                           allEntryCrcVerified=True, outputScope=dict(mode='index'))
            capture = io.StringIO()
            with contextlib.redirect_stdout(capture):
                reader.log_json_outputs(out, receipt)
            self.assertEqual(len(capture.getvalue().splitlines()), 1)
            status = json.loads(capture.getvalue().splitlines()[0][len('AEROVE_JSON_FRAME_V1 '):-len(' AEROVE_JSON_FRAME_END')])
            self.assertEqual(status['omittedReasons'], {'sensitive text policy': 1})
            self.assertNotIn('SYNTHETIC', capture.getvalue())
            (out / 'INDEX.json').write_bytes(b'{}')
            private_identity = dict(self.request(), artifactName='https://invalid.example/?secret=SYNTHETIC')
            unsafe = dict(original=private_identity, completed=True, wholeZipDigestVerified=True,
                          allEntryCrcVerified=True, outputScope=dict(mode='index'))
            capture = io.StringIO()
            with contextlib.redirect_stdout(capture):
                reader.log_json_outputs(out, unsafe)
            self.assertNotIn('SYNTHETIC', capture.getvalue())
            self.assertEqual(len(capture.getvalue().splitlines()), 1)
            for key in ('completed', 'wholeZipDigestVerified', 'allEntryCrcVerified'):
                bad = dict(receipt, **{key: False})
                with contextlib.redirect_stdout(capture), self.assertRaises(ValueError):
                    reader.log_json_outputs(out, bad)
            self.assertEqual(capture.getvalue().splitlines()[-1], capture.getvalue().splitlines()[0])
    def test_index_log_preserves_readonly_scope_and_bytes(self):
        import base64
        import zlib
        receipt, text, outputs = self.logged([('a.json', b'{}'), ('b.json', b'{}')], mode='index', pathContains='a.json')
        lines = text.splitlines()
        frame = json.loads(lines[1][len('AEROVE_JSON_FRAME_V1 '):-len(' AEROVE_JSON_FRAME_END')])
        encoded = base64.b64decode(frame['data'])
        raw = zlib.decompress(encoded) if frame['compression'] == 'zlib' else encoded
        self.assertEqual(raw, outputs['INDEX.json'])
        self.assertEqual(frame['indexScope'], receipt['outputScope'])
        self.assertEqual(frame['indexScope']['totalEntryCount'], 2)
        self.assertEqual(frame['indexScope']['matchedEntryCount'], 1)

    def test_index_128k_compresses_and_incompressible_index_is_omitted(self):
        import contextlib
        import io
        import base64
        import random
        import zlib
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            repeated = (json.dumps([{"path": f"entry/{index:05d}.json", "bytes": 7} for index in range(3000)]) + "\n").encode()
            self.assertGreater(len(repeated), 16384)
            self.assertLessEqual(len(repeated), 131072)
            (out / 'INDEX.json').write_bytes(repeated)
            scope = dict(mode='index', entryCount=3000, totalEntryCount=3000, matchedEntryCount=3000, filter=None)
            receipt = dict(original=self.request(), completed=True, wholeZipDigestVerified=True,
                           allEntryCrcVerified=True, outputScope=scope)
            capture = io.StringIO()
            with contextlib.redirect_stdout(capture):
                reader.log_json_outputs(out, receipt)
            lines = capture.getvalue().splitlines()
            self.assertEqual(len(capture.getvalue().encode()), receipt['jsonLogTransport']['totalFramedBytes'])
            self.assertLessEqual(len(capture.getvalue().encode()), 16384)
            frames = [json.loads(line[len('AEROVE_JSON_FRAME_V1 '):-len(' AEROVE_JSON_FRAME_END')]) for line in lines]
            payload = frames[1]
            self.assertEqual(payload['compression'], 'zlib')
            self.assertEqual(zlib.decompress(base64.b64decode(payload['data'])), repeated)
            rng = random.Random(13)
            noise = ''.join(rng.choice('abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789') for _ in range(120000))
            raw = json.dumps(noise).encode()
            self.assertLessEqual(len(raw), 131072)
            (out / 'INDEX.json').write_bytes(raw)
            capture = io.StringIO()
            with contextlib.redirect_stdout(capture):
                reader.log_json_outputs(out, receipt)
            self.assertEqual(len(capture.getvalue().splitlines()), 1)
            status = json.loads(capture.getvalue().splitlines()[0][len('AEROVE_JSON_FRAME_V1 '):-len(' AEROVE_JSON_FRAME_END')])
            self.assertEqual(status['omittedReasons'], {'total framed byte limit': 1})
            self.assertLessEqual(len(capture.getvalue().encode()), 16384)
    def test_main_integrity_failure_never_logs_and_png_is_skipped(self):
        import contextlib
        import io
        import os
        from unittest import mock
        with tempfile.TemporaryDirectory() as directory:
            capture = io.StringIO()
            env = dict(RUNNER_TEMP=directory, GITHUB_REPOSITORY=reader.REPO,
                       GITHUB_EVENT_NAME='workflow_dispatch', GITHUB_REF=reader.BRANCH,
                       EVIDENCE_REQUEST=json.dumps(self.request()))
            def fake_gh(route, destination, *args):
                Path(destination).write_text('{}')
            with mock.patch.dict(os.environ, env), mock.patch.object(reader, 'gh', side_effect=fake_gh), \
                    mock.patch.object(reader, 'validate_api'), \
                    mock.patch.object(reader, 'consume', side_effect=zipfile.BadZipFile('synthetic')), \
                    contextlib.redirect_stdout(capture):
                self.assertEqual(reader.main(), 1)
            self.assertEqual(capture.getvalue(), '')
            saved = json.loads((Path(directory) / 'artifact-evidence-out/RECEIPT.json').read_text())
            self.assertFalse(saved['completed'])
            self.assertNotIn('jsonLogTransport', saved)
            receipt = dict(original=self.request(), completed=True, wholeZipDigestVerified=True,
                           allEntryCrcVerified=True, outputScope=dict(mode='select', members=[dict(output='member-01.png')]))
            with contextlib.redirect_stdout(capture):
                reader.log_json_outputs(Path(directory), receipt)
            self.assertEqual(len(capture.getvalue().splitlines()), 1)
            status = json.loads(capture.getvalue().splitlines()[0][len('AEROVE_JSON_FRAME_V1 '):-len(' AEROVE_JSON_FRAME_END')])
            self.assertEqual(status['logged'], 0)
            self.assertEqual(receipt['jsonLogTransport']['loggedFiles'], [])


if __name__ == "__main__":
    unittest.main()
