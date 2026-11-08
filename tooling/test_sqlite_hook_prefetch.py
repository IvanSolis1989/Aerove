import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import sqlite_hook_prefetch as subject


class PrefetchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.project = Path(self.temp.name)
        package = self.project / 'package'
        (package / 'lib/src/hook').mkdir(parents=True)
        (package / 'pubspec.yaml').write_text(f'version: {subject.VERSION}\n')
        (package / 'lib/src/hook/asset_hashes.dart').write_text(
            f"releaseTag = 'sqlite3-{subject.VERSION}';\n"
            f"'{subject.FILENAME}': '{subject.SHA256}'\n")
        (self.project / '.dart_tool').mkdir()
        (self.project / '.dart_tool/package_config.json').write_text(json.dumps({
            'packages': [{'name': 'sqlite3', 'rootUri': package.as_uri()}]}))

    def test_resolves_pinned_package(self):
        self.assertEqual(subject.package_root(self.project), self.project / 'package')

    def test_rejects_package_upgrade(self):
        (self.project / 'package/pubspec.yaml').write_text('version: 3.9.0\n')
        with self.assertRaises(ValueError):
            subject.package_root(self.project)

    def test_rejects_changed_hash(self):
        (self.project / 'package/lib/src/hook/asset_hashes.dart').write_text('bad')
        with self.assertRaises(ValueError):
            subject.package_root(self.project)

    def test_bad_download_never_published(self):
        def download(args, **kwargs):
            self.assertIn('--fail', args)
            self.assertIn('--retry-max-time', args)
            self.assertEqual(args[-1], subject.ASSET_URL)
            self.assertEqual(kwargs, {'check': True, 'timeout': 180})
            Path(args[args.index('--output') + 1]).write_bytes(b'504 Gateway Time-out')
        with self.assertRaises(ValueError):
            subject.prefetch(self.project, download)
        self.assertEqual(list(self.project.rglob('libsqlite3.so')), [])
        self.assertEqual(list(self.project.rglob('prefetch-*')), [])

    def test_http_error_fails_closed(self):
        def download(*args, **kwargs):
            raise subprocess.CalledProcessError(22, 'curl')
        with self.assertRaises(subprocess.CalledProcessError):
            subject.prefetch(self.project, download)
        self.assertEqual(list(self.project.rglob('libsqlite3.so')), [])

    def test_api_transport_failure_uses_official_release_fallback(self):
        calls = []
        def download(args, **kwargs):
            calls.append(args[-1])
            output = Path(args[args.index('--output') + 1])
            if len(calls) == 1:
                output.write_bytes(b'partial error response')
                raise subprocess.CalledProcessError(22, 'curl')
            self.assertFalse(output.exists())
            output.write_bytes(b'valid')
        with patch.object(subject, 'digest', return_value=subject.SHA256):
            target = subject.prefetch(self.project, download)
        self.assertEqual(calls, [subject.ASSET_URL, subject.URL])
        self.assertEqual(target.read_bytes(), b'valid')

    def test_hash_mismatch_does_not_retry_other_source(self):
        calls = []
        def download(args, **kwargs):
            calls.append(args[-1])
            Path(args[args.index('--output') + 1]).write_bytes(b'untrusted')
        with self.assertRaises(ValueError):
            subject.prefetch(self.project, download)
        self.assertEqual(calls, [subject.ASSET_URL])

    def test_verified_cache_skips_network(self):
        cache = self.project / '.dart_tool/hooks_runner/shared/sqlite3/build' / f'download-{subject.SHA256[:8]}'
        cache.mkdir(parents=True)
        target = cache / 'libsqlite3.so'
        target.write_bytes(b'fixture')
        with patch.object(subject, 'digest', return_value=subject.SHA256):
            self.assertEqual(subject.prefetch(self.project, lambda *a, **k: self.fail('network')), target)

    def test_success_atomically_replaces_corrupt_cache(self):
        cache = self.project / '.dart_tool/hooks_runner/shared/sqlite3/build' / f'download-{subject.SHA256[:8]}'
        cache.mkdir(parents=True)
        (cache / 'libsqlite3.so').write_bytes(b'corrupt')
        def download(args, **kwargs):
            Path(args[args.index('--output') + 1]).write_bytes(b'valid')
        with patch.object(subject, 'digest', side_effect=['wrong', subject.SHA256]):
            target = subject.prefetch(self.project, download)
        self.assertEqual(target.read_bytes(), b'valid')
        self.assertEqual(list(self.project.rglob('prefetch-*')), [])


if __name__ == '__main__':
    unittest.main()
