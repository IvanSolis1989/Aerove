import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from tool import mihomo_source as source


SCRIPT = Path(source.__file__).resolve()


class MihomoSourceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='aerove-mihomo-test-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repo = self.root / 'core' / 'Clash.Meta'
        self.repo.mkdir(parents=True)
        (self.root / 'tool').mkdir()
        (self.root / 'tool' / 'mihomo_source.py').write_bytes(SCRIPT.read_bytes())
        self.git('init', '--quiet')
        self.git('config', 'user.name', 'Source fixture')
        self.git('config', 'user.email', 'fixture@example.invalid')
        self.git('config', 'core.autocrlf', 'false')
        self.git('config', 'core.symlinks', 'true')
        self.git('remote', 'add', 'origin', source.OFFICIAL_URL)
        (self.repo / 'main.go').write_bytes(b'package core\n// official\n')
        (self.repo / 'data.bin').write_bytes(bytes(range(256)))
        (self.repo / 'obsolete.txt').write_bytes(b'official old file\n')
        self.git('add', '.')
        self.git('commit', '--quiet', '-m', 'Official fixture')
        self.base = self.git('rev-parse', 'HEAD').decode().strip()
        self.base_tree = self.git('rev-parse', 'HEAD^{tree}').decode().strip()
        self.patch_path = self.root / 'core' / 'patches' / 'fixture.patch'
        self.patch_path.parent.mkdir()
        self.manifest_path = self.root / source.MANIFEST
        self.make_patch(b'package core\n// Aerove hooks\n', b'\0adaptation\xff\0')
        self.output = self.root / 'core' / '.mihomo'

    def git(self, *arguments, data=None, index=None):
        env = os.environ.copy()
        if index is not None:
            env['GIT_INDEX_FILE'] = str(index)
        return subprocess.run(['git', '-C', str(self.repo), *arguments], input=data,
                              capture_output=True, check=True, env=env).stdout

    def write_manifest(self):
        self.manifest_path.write_text(json.dumps(self.manifest) + '\n', encoding='utf-8')

    def make_patch(self, text, binary):
        (self.repo / 'main.go').write_bytes(text)
        (self.repo / 'data.bin').write_bytes(binary)
        (self.repo / 'obsolete.txt').unlink(missing_ok=True)
        self.git('add', '.')
        self.git('commit', '--quiet', '-m', 'Preserved adaptation fixture')
        adapted_tree = self.git('rev-parse', 'HEAD^{tree}').decode().strip()
        patch_bytes = self.git('diff', '--binary', '--full-index', '--no-renames', self.base, 'HEAD')
        self.git('switch', '--detach', self.base)
        self.patch_path.write_bytes(patch_bytes)
        self.manifest = {
            'schema': 1,
            'upstream': {'url': source.OFFICIAL_URL, 'version': 'v1.19.33',
                         'commit': self.base, 'tree': self.base_tree},
            'patches': [{'path': 'core/patches/fixture.patch', 'sha256': hashlib.sha256(patch_bytes).hexdigest()}],
            'result_tree': adapted_tree,
        }
        self.write_manifest()

    def test_real_binary_replay_reuse_and_readonly_verification(self):
        index = Path(self.git('rev-parse', '--absolute-git-dir').decode().strip()) / 'index'
        original_index = index.read_bytes()
        result = source.prepare(self.root)
        self.assertFalse(result['reused'])
        self.assertEqual(result['result_tree'], self.manifest['result_tree'])
        self.assertEqual((self.output / 'data.bin').read_bytes(), b'\0adaptation\xff\0')
        self.assertFalse((self.output / 'obsolete.txt').exists())
        metadata = self.output / source.METADATA
        original = metadata.read_bytes(), metadata.stat().st_mtime_ns
        self.assertTrue(source.prepare(self.root)['reused'])
        self.assertEqual(source.verify(self.root)['content_sha256'], result['content_sha256'])
        self.assertEqual((metadata.read_bytes(), metadata.stat().st_mtime_ns), original)
        self.assertEqual(index.read_bytes(), original_index)
        self.assertEqual(self.git('status', '--porcelain'), b'')
        self.assertEqual(self.git('rev-parse', 'HEAD').decode().strip(), self.base)

    def test_changed_patch_hash_is_rejected_without_touching_old_output(self):
        source.prepare(self.root)
        old = (self.output / source.METADATA).read_bytes()
        self.patch_path.write_bytes(self.patch_path.read_bytes() + b'changed\n')
        with self.assertRaisesRegex(source.SourceError, 'Patch SHA256 mismatch'):
            source.prepare(self.root)
        self.assertEqual((self.output / source.METADATA).read_bytes(), old)

    def test_updated_patch_and_result_pin_replace_complete_cache(self):
        first = source.prepare(self.root)
        self.make_patch(b'package core\n// changed hooks\n', b'\0second\xff')
        second = source.prepare(self.root)
        self.assertFalse(second['reused'])
        self.assertNotEqual(first['input_fingerprint'], second['input_fingerprint'])
        self.assertEqual((self.output / 'data.bin').read_bytes(), b'\0second\xff')
        self.assertEqual(source.verify(self.root)['result_tree'], self.manifest['result_tree'])
        previous = self.output.with_name('.mihomo.previous')
        self.assertEqual((previous / 'data.bin').read_bytes(), b'\0adaptation\xff\0')

    def test_wrong_official_commit_url_and_dirty_checkout_are_rejected(self):
        source.prepare(self.root)
        self.manifest['upstream']['commit'] = '0' * 40
        self.write_manifest()
        with self.assertRaisesRegex(source.SourceError, 'HEAD differs'):
            source.prepare(self.root)
        self.manifest['upstream']['commit'] = self.base
        self.manifest['upstream']['url'] = 'https://github.com/example/fork.git'
        self.write_manifest()
        with self.assertRaisesRegex(source.SourceError, 'official HTTPS'):
            source.prepare(self.root)
        self.manifest['upstream']['url'] = source.OFFICIAL_URL
        self.write_manifest()
        self.git('remote', 'set-url', 'origin', 'https://github.com/example/fork.git')
        with self.assertRaisesRegex(source.SourceError, 'origin'):
            source.prepare(self.root)
        self.git('remote', 'set-url', 'origin', source.OFFICIAL_URL)
        (self.repo / 'main.go').write_text('dirty\n', encoding='utf-8')
        with self.assertRaisesRegex(source.SourceError, 'pristine'):
            source.prepare(self.root)

    def test_tampered_cache_is_not_trusted_or_repaired_by_verify(self):
        source.prepare(self.root)
        target = self.output / 'data.bin'
        target.write_bytes(b'\0tampered!!!\0')
        with self.assertRaisesRegex(source.SourceError, 'integrity'):
            source.verify(self.root)
        self.assertEqual(target.read_bytes(), b'\0tampered!!!\0')
        self.assertFalse(source.prepare(self.root)['reused'])
        self.assertEqual(target.read_bytes(), b'\0adaptation\xff\0')

    def test_invalid_replay_preserves_previous_result(self):
        source.prepare(self.root)
        old = (self.output / source.METADATA).read_bytes()
        broken = b'diff --git a/main.go b/main.go\n--- a/main.go\n+++ b/main.go\n@@ -1 +1 @@\n-missing context\n+replacement\n'
        self.patch_path.write_bytes(broken)
        self.manifest['patches'][0]['sha256'] = hashlib.sha256(broken).hexdigest()
        self.write_manifest()
        with self.assertRaisesRegex(source.SourceError, 'git apply failed'):
            source.prepare(self.root)
        self.assertEqual((self.output / source.METADATA).read_bytes(), old)
        self.assertEqual((self.output / 'data.bin').read_bytes(), b'\0adaptation\xff\0')

    def test_wrong_expected_tree_preserves_previous_result(self):
        source.prepare(self.root)
        old = (self.output / source.METADATA).read_bytes()
        self.manifest['result_tree'] = self.base_tree
        self.write_manifest()
        with self.assertRaisesRegex(source.SourceError, 'Replayed tree mismatch'):
            source.prepare(self.root)
        self.assertEqual((self.output / source.METADATA).read_bytes(), old)

    def test_publication_failure_restores_old_directory(self):
        source.prepare(self.root)
        old = (self.output / source.METADATA).read_bytes()
        self.make_patch(b'package core\n// changed hooks\n', b'\0second\xff')
        replace = os.replace

        def fail_new_publication(origin, target):
            if Path(origin).name == 'source' and Path(target) == self.output:
                raise OSError('injected rename failure')
            return replace(origin, target)

        with patch.object(source.os, 'replace', side_effect=fail_new_publication):
            with self.assertRaisesRegex(OSError, 'injected rename failure'):
                source.prepare(self.root)
        self.assertEqual((self.output / source.METADATA).read_bytes(), old)
        self.assertEqual((self.output / 'data.bin').read_bytes(), b'\0adaptation\xff\0')

    def test_manifest_traversal_and_patched_symlink_escape_are_rejected(self):
        self.manifest['patches'][0]['path'] = 'core/patches/../fixture.patch'
        self.write_manifest()
        with self.assertRaisesRegex(source.SourceError, 'Unsafe source path'):
            source.prepare(self.root)
        index = self.root / 'fixture-index'
        self.git('read-tree', self.base, index=index)
        blob = self.git('hash-object', '-w', '--stdin', data=b'../../outside').decode().strip()
        self.git('update-index', '--add', '--cacheinfo', f'120000,{blob},escape', index=index)
        tree = self.git('write-tree', index=index).decode().strip()
        patch_bytes = self.git('diff', '--binary', '--full-index', self.base, tree)
        self.patch_path.write_bytes(patch_bytes)
        self.manifest['patches'][0] = {'path': 'core/patches/fixture.patch', 'sha256': hashlib.sha256(patch_bytes).hexdigest()}
        self.manifest['result_tree'] = tree
        self.write_manifest()
        with self.assertRaisesRegex(source.SourceError, 'Symlink escapes'):
            source.prepare(self.root)
        self.assertFalse(self.output.exists())
        self.assertFalse((self.root / 'outside').exists())

    def test_concurrent_prepare_serializes_complete_publication(self):
        arguments = [sys.executable, str(SCRIPT), 'prepare', '--root', str(self.root)]
        workers = [subprocess.Popen(arguments, stdout=subprocess.PIPE, stderr=subprocess.PIPE) for _ in range(2)]
        results = []
        for worker in workers:
            stdout, stderr = worker.communicate(timeout=30)
            self.assertEqual(worker.returncode, 0, stderr.decode())
            results.append(json.loads(stdout))
        self.assertEqual(sorted(result['reused'] for result in results), [False, True])
        self.assertEqual(results[0]['content_sha256'], results[1]['content_sha256'])
        self.assertEqual(source.verify(self.root)['result_tree'], self.manifest['result_tree'])

    def test_link_followed_by_dotdot_is_not_lexically_collapsed_past_escape(self):
        with self.assertRaisesRegex(source.SourceError, 'traverses another link'):
            source.validate_links(
                {'directory/link': '120000', 'escape': '120000', 'sibling/file': '100644'},
                {'directory/link': '../sibling', 'escape': 'directory/link/../../outside'},
            )

    def test_confined_file_directory_and_root_links_keep_their_targets(self):
        self.assertEqual(
            source.validate_links(
                {'directory/file': '100644', 'file-link': '120000', 'dir-link': '120000', 'directory/root-link': '120000'},
                {'file-link': 'directory/file', 'dir-link': 'directory', 'directory/root-link': '..'},
            ),
            {'file-link': 'directory/file', 'dir-link': 'directory', 'directory/root-link': ''},
        )

    def test_corrupt_metadata_inventory_is_rejected_readonly_and_rebuilt(self):
        source.prepare(self.root)
        metadata_path = self.output / source.METADATA
        metadata = json.loads(metadata_path.read_text())
        metadata['file_modes'] = []
        metadata_path.write_text(json.dumps(metadata), encoding='utf-8')
        with self.assertRaisesRegex(source.SourceError, 'mode inventory'):
            source.verify(self.root)
        self.assertFalse(source.prepare(self.root)['reused'])
        self.assertEqual(source.verify(self.root)['result_tree'], self.manifest['result_tree'])


if __name__ == '__main__':
    unittest.main()
