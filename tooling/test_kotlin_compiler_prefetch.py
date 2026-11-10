import hashlib
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


class PomPrefetchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.content = b'<project>exact fixture metadata</project>'
        self.sha256 = hashlib.sha256(self.content).hexdigest()
        self.sha1 = hashlib.sha1(self.content).hexdigest()
        self.pin = patch.object(subject, 'POM_SHA256', self.sha256)
        self.pin.start()
        self.addCleanup(self.pin.stop)
        self.store = (self.home / 'caches/modules-2/files-2.1/org.jetbrains.kotlin'
                      / subject.MODULE / subject.VERSION)
        self.filename = f'{subject.MODULE}-{subject.VERSION}.pom'

    def cache(self, content, key='existing'):
        target = self.store / key / self.filename
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
        return target

    def download(self, args, **kwargs):
        self.assertEqual(args[-1], 'https://repo.maven.apache.org/maven2/'
                         f'org/jetbrains/kotlin/{subject.MODULE}/{subject.VERSION}/{self.filename}')
        self.assertEqual(args[args.index('--retry') + 1], '6')
        self.assertEqual(args[args.index('--retry-max-time') + 1], '240')
        self.assertEqual(args[args.index('--max-time') + 1], '45')
        self.assertNotIn('--retry-delay', args)
        self.assertEqual(kwargs, {'check': True, 'timeout': 300})
        self.assertIn('--fail', args)
        self.assertEqual(args[args.index('--proto-redir') + 1], '=https')
        Path(args[args.index('--output') + 1]).write_bytes(self.content)

    def test_download_seeds_checksum_store_and_next_run_needs_no_network(self):
        target = subject.prefetch_pom(self.home, self.download)
        self.assertEqual(target, self.store / self.sha1 / self.filename)
        self.assertEqual(target.read_bytes(), self.content)
        self.assertEqual(subject.prefetch_pom(
            self.home, lambda *a, **k: self.fail('network')), target)
        self.assertEqual(list(self.home.rglob('prefetch-*')), [])

    def test_verified_gradle_cache_configures_repository_offline(self):
        self.cache(self.content)
        jar = self.home / 'fixture.jar'
        jar.write_bytes(b'jar fixture')
        with patch.object(subject, 'verified', return_value=True):
            init = subject.configure_repository(
                self.home, jar, lambda *a, **k: self.fail('network'))
        self.assertTrue(init.is_file())
        pom = (self.home / 'aerove-pinned-maven/org/jetbrains/kotlin'
               / subject.MODULE / subject.VERSION / self.filename)
        self.assertEqual(pom.read_bytes(), self.content)
        with patch.object(subject, 'verified', return_value=True):
            subject.configure_repository(
                self.home, jar, lambda *a, **k: self.fail('network'))

    def test_wrong_cache_bytes_are_not_trusted_even_in_expected_sha1_directory(self):
        cached = self.cache(b'<html>rate limited</html>', self.sha1)
        result = subject.prefetch_pom(self.home, self.download)
        self.assertEqual(result, cached)
        self.assertEqual(result.read_bytes(), self.content)

    def test_other_versions_are_not_used(self):
        other = self.store.parent / '2.3.20/existing' / self.filename
        other.parent.mkdir(parents=True)
        other.write_bytes(self.content)
        target = subject.prefetch_pom(self.home, self.download)
        self.assertEqual(target, self.store / self.sha1 / self.filename)

    def test_rate_limit_and_timeout_fail_without_publication_or_alias_retry(self):
        for error in (subprocess.CalledProcessError(22, 'curl'),
                      subprocess.TimeoutExpired('curl', 300)):
            with self.subTest(error=type(error).__name__):
                calls = []
                def download(args, **kwargs):
                    calls.append(args[-1])
                    Path(args[args.index('--output') + 1]).write_bytes(b'partial')
                    raise error
                jar = self.home / 'fixture.jar'
                jar.write_bytes(b'fixture')
                with patch.object(subject, 'verified', return_value=True):
                    with self.assertRaises(type(error)):
                        subject.configure_repository(self.home, jar, download)
                self.assertEqual(len(calls), 1)
                self.assertFalse((self.home / 'init.d').exists())
                self.assertEqual(list(self.home.rglob(self.filename)), [])
                self.assertEqual(list(self.home.rglob('prefetch-*')), [])

    def test_successful_transport_with_bad_or_missing_bytes_fails_closed(self):
        for content in (b'<html>blocked</html>', b'{"error":"rate limited"}', b'', None):
            with self.subTest(content=content):
                def download(args, **kwargs):
                    if content is not None:
                        Path(args[args.index('--output') + 1]).write_bytes(content)
                with self.assertRaises(ValueError):
                    subject.prefetch_pom(self.home, download)
                self.assertEqual(list(self.home.rglob(self.filename)), [])
                self.assertEqual(list(self.home.rglob('prefetch-*')), [])

    def test_cache_changed_during_copy_is_rejected(self):
        cached = self.cache(self.content)
        jar = self.home / 'fixture.jar'
        jar.write_bytes(b'fixture')
        def changed_cache(*args, **kwargs):
            cached.write_bytes(b'changed after verification')
            return cached
        with patch.object(subject, 'verified', return_value=True), patch.object(
                subject, 'prefetch_pom', side_effect=changed_cache):
            with self.assertRaises(ValueError):
                subject.configure_repository(self.home, jar)
        self.assertFalse((self.home / 'init.d').exists())
        self.assertEqual(list((self.home / 'aerove-pinned-maven').rglob(self.filename)), [])


if __name__ == '__main__':
    unittest.main()
