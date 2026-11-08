from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import kotlin_compiler_prefetch as subject


class PrefetchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)

    def test_download_targets_only_official_api_and_checks_sha(self):
        calls = []
        def download(args, **kwargs):
            calls.append(args[-1])
            self.assertIn('--fail', args)
            self.assertIn('--retry-max-time', args)
            Path(args[args.index('--output') + 1]).write_bytes(b'bad')
        with self.assertRaises(ValueError):
            subject.prefetch(self.home, download)
        self.assertEqual(calls, [subject.URLS[0]])
        self.assertEqual(list(self.home.rglob(subject.FILENAME)), [])

    def test_transport_fallback_is_bounded_and_discards_partial(self):
        calls = []
        def download(args, **kwargs):
            calls.append(args[-1])
            candidate = Path(args[args.index('--output') + 1])
            if len(calls) == 1:
                candidate.write_bytes(b'partial')
                raise subprocess.CalledProcessError(22, 'curl')
            self.assertFalse(candidate.exists())
            candidate.write_bytes(b'good')
        with patch.object(subject, 'verified', side_effect=[False, True]):
            target = subject.prefetch(self.home, download)
        self.assertEqual(calls, list(subject.URLS))
        self.assertEqual(target.read_bytes(), b'good')
        self.assertIn(subject.SHA1, str(target))
        self.assertEqual(list(self.home.rglob('prefetch-*')), [])

    def test_both_sources_fail_closed(self):
        def download(*args, **kwargs):
            raise subprocess.TimeoutExpired('curl', 280)
        with self.assertRaises(subprocess.TimeoutExpired):
            subject.prefetch(self.home, download)
        self.assertEqual(list(self.home.rglob(subject.FILENAME)), [])

    def test_existing_valid_cache_is_not_downloaded(self):
        with patch.object(subject, 'verified', return_value=True):
            subject.prefetch(self.home, lambda *a, **k: self.fail('network'))

    def test_repository_rejects_unverified_jar(self):
        jar = self.home / 'bad.jar'
        jar.write_bytes(b'bad')
        with self.assertRaises(ValueError):
            subject.configure_repository(self.home, jar)
        self.assertFalse((self.home / 'init.d').exists())

    def test_repository_rejects_wrong_pom_before_installing_init(self):
        jar = self.home / 'verified.jar'
        jar.write_bytes(b'fixture')
        def download(args, **kwargs):
            Path(args[args.index('--output') + 1]).write_bytes(b'wrong pom')
        with patch.object(subject, 'verified', return_value=True):
            with self.assertRaises(ValueError):
                subject.configure_repository(self.home, jar, download)
        self.assertFalse((self.home / 'init.d').exists())
        self.assertEqual(list(self.home.rglob('prefetch-*')), [])

    def test_repository_limits_override_to_one_exact_coordinate(self):
        jar = self.home / 'verified.jar'
        jar.write_bytes(b'fixture')
        def download(args, **kwargs):
            self.assertIn('--fail', args)
            Path(args[args.index('--output') + 1]).write_bytes(b'official pom')
        with patch.object(subject, 'verified', return_value=True), patch.object(subject, 'digest', return_value=subject.POM_SHA256):
            init = subject.configure_repository(self.home, jar, download)
        text = init.read_text()
        self.assertIn("includeVersion('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.3.21')", text)
        self.assertIn('RepositoriesMode.PREFER_PROJECT', text)
        self.assertIn('mavenPom(); ignoreGradleMetadataRedirection()', text)
        self.assertIn(subject.SHA256, text)
        self.assertIn(subject.POM_SHA256, text)
        self.assertNotIn('includeGroup', text)

    def test_real_bytes_do_not_match_expected_hashes(self):
        path = self.home / 'fake.jar'
        path.write_bytes(b'fake')
        self.assertFalse(subject.verified(path))


if __name__ == '__main__':
    unittest.main()
