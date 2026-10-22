import copy
import hashlib
import importlib
import io
import json
import os
import struct
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

from tool import xclash_version as version


REPOSITORY = 'IvanSolis1989/Aerove'
SOURCE = 'a' * 40
TREE = 'b' * 40
RUN_ID = 123
RUN_NUMBER = 42
SCRIPT = Path(__file__).with_name('aerove_artifact.py')
LIBRARIES = ('libflutter.so', 'libapp.so', 'libclash.so', 'librust_api.so')


def elf(machine=183, elf_class=2):
    data = bytearray(64)
    data[:7] = b'\x7fELF' + bytes((elf_class, 1, 1))
    struct.pack_into('<HHI', data, 16, 3, machine, 1)
    struct.pack_into('<H', data, 52, 64)
    return bytes(data)


class ArtifactFilesTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.dist = self.root / 'dist'
        self.generated = self.root / 'gen'
        self.dist.mkdir()
        self.generated.mkdir()
        self.metadata = version.make_metadata(
            source_commit='c' * 40,
            tag_ref='refs/upstream-tags/v0.9.1',
            tag_commit='c' * 40,
            source_branches=['main'],
            pubspec_version='0.9.1+2026101702',
        )
        self.metadata_path = self.root / 'upstream-version.json'
        self.info = {
            'source': SOURCE,
            'version': f'0.9.1-X+{version.FORK_VERSION_CODE_BASE + RUN_NUMBER}',
            'appEnv': 'stable',
            'workflow': f'https://github.com/{REPOSITORY}/actions/runs/{RUN_ID}',
            'signing': 'persistent Android test key',
            'applicationId': 'com.follow.clash.dev',
            'upstream': {
                'repository': self.metadata['repository'],
                'sourceCommit': self.metadata['source_commit'],
                'tag': self.metadata['tag'],
                'tagCommit': self.metadata['tag_commit'],
                'sourceBranches': self.metadata['source_branches'],
                'pubspecVersion': self.metadata['pubspec_version'],
                'pubspecSourceCommit': self.metadata['pubspec_source_commit'],
            },
        }
        (self.generated / 'xclash-generated.patch').write_bytes(b'')
        (self.generated / 'xclash-verified-tree.txt').write_bytes((TREE + '\n').encode('ascii'))
        self.entries = {f'lib/arm64-v8a/{name}': elf() for name in LIBRARIES}
        self.entries['lib/x86/libthirdparty.so'] = elf(machine=3, elf_class=1)
        self.entries['AndroidManifest.xml'] = b'fixture manifest'
        self.write_apk()
        self.save_info()

    @property
    def module(self):
        return importlib.import_module('tool.aerove_artifact')

    def write_apk(self):
        with zipfile.ZipFile(self.dist / 'Aerove-android-arm64.apk', 'w') as apk:
            for name, data in self.entries.items():
                apk.writestr(name, data)
        self.checksums()

    def checksums(self):
        names = ('Aerove-android-arm64.apk', 'BUILD_INFO.json')
        if all((self.dist / name).exists() for name in names):
            lines = [
                f'{hashlib.sha256((self.dist / name).read_bytes()).hexdigest()}  {name}\n'
                for name in names
            ]
            (self.dist / 'SHA256SUMS').write_text(''.join(lines))

    def save_info(self):
        self.metadata_path.write_text(json.dumps(self.metadata))
        (self.dist / 'BUILD_INFO.json').write_text(json.dumps(self.info))
        self.checksums()

    def arguments(self):
        return [
            'validate', '--directory', str(self.dist),
            '--generated-directory', str(self.generated), '--source', SOURCE,
            '--tree', TREE, '--repository', REPOSITORY, '--run-id', str(RUN_ID),
            '--run-number', str(RUN_NUMBER), '--metadata', str(self.metadata_path),
        ]

    def validate(self, **changes):
        arguments = {
            'directory': self.dist, 'generated_directory': self.generated,
            'source': SOURCE, 'tree': TREE, 'repository': REPOSITORY,
            'run_id': RUN_ID, 'run_number': RUN_NUMBER,
            'metadata': self.metadata_path,
        }
        arguments.update(changes)
        return self.module.validate_artifacts(**arguments)

    def test_validate_cli_accepts_the_original_version_without_changing_files(self):
        before = {
            path: path.read_bytes()
            for directory in (self.dist, self.generated)
            for path in directory.iterdir()
        }
        completed = subprocess.run(
            [sys.executable, '-B', str(SCRIPT), *self.arguments()],
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn(self.info['version'], completed.stdout)
        self.assertEqual(before, {path: path.read_bytes() for path in before})

    def test_validate_accepts_preview_metadata_and_its_original_build_number(self):
        self.metadata.update(tag='v0.9.2-pre.1', version='0.9.2-pre.1')
        self.info['version'] = f'0.9.2-pre.1-X+{version.FORK_VERSION_CODE_BASE + RUN_NUMBER}'
        self.info['appEnv'] = 'pre'
        self.info['upstream']['tag'] = self.metadata['tag']
        self.save_info()
        self.assertEqual(self.validate(), self.info['version'])

    def test_independent_product_version_keeps_historical_upstream_evidence(self):
        pubspec = self.root / 'pubspec.yaml'
        for name, channel in [('1.3.0', 'stable'), ('1.4.0-beta.2', 'pre')]:
            with self.subTest(name=name):
                pubspec.write_text(f'version: {name}+1\n')
                self.info['version'] = f'{name}+{self.module.product.VERSION_CODE_BASE + RUN_NUMBER}'
                self.info['appEnv'] = channel
                self.save_info()
                before = {path: path.read_bytes() for path in self.dist.iterdir()}
                self.assertEqual(self.validate(pubspec=pubspec), self.info['version'])
                completed = subprocess.run(
                    [sys.executable, '-B', str(SCRIPT), *self.arguments(),
                     '--pubspec', str(pubspec)], capture_output=True, text=True,
                )
                self.assertEqual(completed.returncode, 0, completed.stderr)
                self.assertEqual(before, {path: path.read_bytes() for path in before})
                with self.assertRaises(self.module.ArtifactError):
                    self.validate()
                self.info['appEnv'] = 'pre' if channel == 'stable' else 'stable'
                self.save_info()
                with self.assertRaises(self.module.ArtifactError):
                    self.validate(pubspec=pubspec)

    def test_invalid_product_version_cannot_be_promoted(self):
        pubspec = self.root / 'pubspec.yaml'
        pubspec.write_text('version: not-a-version\n')
        with self.assertRaises(self.module.ArtifactError):
            self.validate(pubspec=pubspec)

    def test_monorepo_cli_uses_the_original_artifacts_from_root_and_product_cwds(self):
        product = self.root / 'apps/aerove'
        (product / 'tool').mkdir(parents=True)
        self.dist = self.dist.rename(product / 'dist')
        self.metadata_path = self.metadata_path.rename(product / 'tool/upstream-version.json')
        pubspec = product / 'pubspec.yaml'
        pubspec.write_text('version: 1.3.0\n', encoding='utf-8')
        self.info['version'] = f'1.3.0+{self.module.product.VERSION_CODE_BASE + RUN_NUMBER}'
        self.save_info()
        before = {path: path.read_bytes() for path in self.root.rglob('*') if path.is_file()}
        for cwd, prefix in ((self.root, 'apps/aerove/'), (product, '')):
            with self.subTest(cwd=cwd):
                arguments = self.arguments()
                arguments[arguments.index('--directory') + 1] = prefix + 'dist'
                arguments[arguments.index('--metadata') + 1] = prefix + 'tool/upstream-version.json'
                completed = subprocess.run(
                    [sys.executable, '-B', str(SCRIPT.resolve()), *arguments,
                     '--pubspec', prefix + 'pubspec.yaml'],
                    cwd=cwd, capture_output=True, text=True, encoding='utf-8',
                )
                self.assertEqual(completed.returncode, 0, completed.stderr)
                self.assertIn(self.info['version'], completed.stdout)
        self.assertEqual(before, {path: path.read_bytes() for path in before})

    def test_checksums_reject_changed_bytes_and_ambiguous_or_escaping_entries(self):
        checksum = self.dist / 'SHA256SUMS'
        original = checksum.read_text()
        malformed = [
            original.replace('Aerove-android-arm64.apk', '../Aerove-android-arm64.apk'),
            original.replace('BUILD_INFO.json', '/tmp/BUILD_INFO.json'),
            original + original.splitlines(keepends=True)[0],
            original.splitlines(keepends=True)[0],
            original + '0' * 64 + '  extra.txt\n',
            '0' * 64 + original[64:],
        ]
        for contents in malformed:
            with self.subTest(contents=contents):
                checksum.write_text(contents)
                with self.assertRaises(self.module.ArtifactError):
                    self.validate()
        checksum.write_text(original)
        with (self.dist / 'Aerove-android-arm64.apk').open('ab') as handle:
            handle.write(b'tampered')
        with self.assertRaisesRegex(self.module.ArtifactError, 'checksum'):
            self.validate()

    def test_generated_files_must_be_empty_patch_and_the_exact_tree(self):
        patch_path = self.generated / 'xclash-generated.patch'
        patch_path.write_bytes(b'\n')
        with self.assertRaisesRegex(self.module.ArtifactError, 'patch'):
            self.validate()
        patch_path.write_bytes(b'')
        tree_path = self.generated / 'xclash-verified-tree.txt'
        for value in ['d' * 40, TREE + '\nextra\n', ' ' + TREE]:
            with self.subTest(value=value):
                tree_path.write_text(value)
                with self.assertRaisesRegex(self.module.ArtifactError, 'tree'):
                    self.validate()

    def test_no_missing_or_extra_artifact_files(self):
        extra = self.dist / 'extra'
        extra.write_bytes(b'extra')
        with self.assertRaises(self.module.ArtifactError):
            self.validate()
        extra.unlink()
        target = self.dist / 'BUILD_INFO.json'
        target.unlink()
        with self.assertRaises(self.module.ArtifactError):
            self.validate()

    def test_symlinked_artifact_files_are_rejected(self):
        target = self.dist / 'BUILD_INFO.json'
        data = target.read_bytes()
        target.unlink()
        outside = self.root / 'outside.json'
        outside.write_bytes(data)
        try:
            target.symlink_to(outside)
        except OSError:
            self.skipTest('Creating symlinks is unavailable on this host')
        with self.assertRaises(self.module.ArtifactError):
            self.validate()

    def test_build_info_rejects_wrong_source_run_version_channel_package_and_signing(self):
        changes = {
            'source': 'd' * 40,
            'workflow': f'https://github.com/other/repo/actions/runs/{RUN_ID}',
            'version': '0.9.1-X+1',
            'appEnv': 'pre',
            'applicationId': 'other.package',
            'signing': 'temporary key',
        }
        for key, value in changes.items():
            original = self.info[key]
            with self.subTest(field=key):
                self.info[key] = value
                self.save_info()
                with self.assertRaises(self.module.ArtifactError):
                    self.validate()
            self.info[key] = original
        self.save_info()
        for changes in [
            {'run_id': RUN_ID + 1}, {'run_number': RUN_NUMBER + 1},
            {'source': 'd' * 40}, {'repository': 'other/repo'},
        ]:
            with self.subTest(arguments=changes):
                with self.assertRaises(self.module.ArtifactError):
                    self.validate(**changes)

    def test_every_upstream_provenance_field_is_checked(self):
        for key in self.info['upstream']:
            original = self.info['upstream'][key]
            with self.subTest(field=key):
                self.info['upstream'][key] = ['forged'] if isinstance(original, list) else 'forged'
                self.save_info()
                with self.assertRaisesRegex(self.module.ArtifactError, 'upstream'):
                    self.validate()
            self.info['upstream'][key] = original

    def test_malformed_duplicate_json_keys_and_untrusted_metadata_fail(self):
        info_path = self.dist / 'BUILD_INFO.json'
        for contents in ['{', '{"source": "a", "source": "b"}', '[]']:
            with self.subTest(contents=contents):
                info_path.write_text(contents)
                self.checksums()
                with self.assertRaises(self.module.ArtifactError):
                    self.validate()
        self.save_info()
        self.metadata['repository'] = 'attacker/repo'
        self.save_info()
        with self.assertRaises(self.module.ArtifactError):
            self.validate()

    def test_corrupt_zip_crc_and_duplicate_entries_are_rejected(self):
        apk_path = self.dist / 'Aerove-android-arm64.apk'
        apk_path.write_bytes(b'not a ZIP')
        self.checksums()
        with self.assertRaises(self.module.ArtifactError):
            self.validate()
        self.write_apk()
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(apk_path, 'a') as apk:
                apk.writestr('AndroidManifest.xml', b'duplicate')
        self.checksums()
        with self.assertRaisesRegex(self.module.ArtifactError, 'duplicate'):
            self.validate()
        self.write_apk()
        data = apk_path.read_bytes()
        apk_path.write_bytes(data.replace(b'fixture manifest', b'corrupt manifest'))
        self.checksums()
        with self.assertRaisesRegex(self.module.ArtifactError, 'CRC'):
            self.validate()

    def test_own_libraries_must_all_be_arm64_elf64_but_third_party_abis_are_allowed(self):
        self.assertEqual(self.validate(), self.info['version'])
        original = self.entries.copy()
        for name, data in [
            ('lib/x86/libapp.so', elf(machine=3)),
            ('lib/arm64-v8a/libapp.so', elf(machine=62)),
            ('lib/arm64-v8a/libapp.so', elf(elf_class=1)),
            ('lib/arm64-v8a/libapp.so', b'not ELF'),
            ('../lib/arm64-v8a/libapp.so', elf()),
        ]:
            with self.subTest(entry=name, data=data):
                self.entries = {**original, name: data}
                self.write_apk()
                with self.assertRaises(self.module.ArtifactError):
                    self.validate()
        self.entries = original.copy()
        del self.entries['lib/arm64-v8a/librust_api.so']
        self.write_apk()
        with self.assertRaises(self.module.ArtifactError):
            self.validate()

    def test_invalid_cli_identifiers_fail_before_validation(self):
        for changes in [
            {'source': 'main'}, {'tree': 'HEAD^{tree}'}, {'run_id': 0},
            {'run_number': -1}, {'run_number': True},
            {'repository': '../repo'}, {'repository': REPOSITORY + '\nforged=1'},
        ]:
            with self.subTest(arguments=changes):
                with self.assertRaises(self.module.ArtifactError):
                    self.validate(**changes)


class ArtifactFindTest(unittest.TestCase):
    def setUp(self):
        self.module = importlib.import_module('tool.aerove_artifact')
        self.calls = []

    @staticmethod
    def workflow_run(run_id=RUN_ID, number=RUN_NUMBER, **changes):
        return {
            'id': run_id, 'run_number': number, 'run_attempt': 2,
            'head_sha': SOURCE, 'status': 'completed', 'conclusion': 'success',
            'path': '.github/workflows/xclash-android.yaml', 'event': 'workflow_dispatch',
            'repository': {'full_name': REPOSITORY},
            'head_repository': {'full_name': REPOSITORY}, **changes,
        }

    @staticmethod
    def job(name, job_id=1, **changes):
        return {
            'id': job_id, 'run_id': RUN_ID, 'run_attempt': 2, 'head_sha': SOURCE,
            'name': name, 'status': 'completed', 'conclusion': 'success', **changes,
        }

    @staticmethod
    def artifacts():
        return [
            {'id': 10, 'name': 'Aerove-android-arm64', 'expired': False},
            {'id': 11, 'name': 'xclash-generated', 'expired': False},
        ]

    def api(self, pages):
        def request(command, **kwargs):
            self.assertEqual(command[:2], ['gh', 'api'])
            endpoint = next(value for value in command if value.startswith('repos/'))
            self.calls.append(endpoint)
            for needle, value in pages.items():
                if needle in endpoint:
                    if isinstance(value, Exception):
                        raise value
                    if isinstance(value, subprocess.CompletedProcess):
                        return value
                    return subprocess.CompletedProcess(command, 0, json.dumps(value), '')
            self.fail(f'Unexpected gh API endpoint: {endpoint}')
        return patch.object(self.module.subprocess, 'run', side_effect=request)

    def pages(self, *, runs=None, jobs=None, artifacts=None):
        def page(key, items):
            return {'total_count': len(items), key: items}
        return {
            '/workflows/xclash-android.yaml/runs?': page('workflow_runs', [self.workflow_run()] if runs is None else runs),
            '/attempts/2/jobs?': page('jobs', [self.job('verify'), self.job('apk', 2)] if jobs is None else jobs),
            '/artifacts?': page('artifacts', self.artifacts() if artifacts is None else artifacts),
        }

    def find(self, **changes):
        arguments = {'repository': REPOSITORY, 'source': SOURCE, 'exclude_run': 999}
        arguments.update(changes)
        return self.module.find_artifacts(**arguments)

    def test_find_returns_original_run_and_both_artifact_ids(self):
        with self.api(self.pages()):
            self.assertEqual(self.find(), {
                'run_id': RUN_ID, 'run_number': RUN_NUMBER,
                'artifact_id': 10, 'generated_artifact_id': 11,
            })
        self.assertTrue(any('/attempts/2/jobs?' in value for value in self.calls))

    def test_all_three_apis_paginate_and_the_latest_eligible_run_is_selected(self):
        older = self.workflow_run()
        newer = self.workflow_run(run_id=124, number=43)
        excluded = [self.workflow_run(run_id=200 + index, number=50 + index, event='pull_request')
                    for index in range(99)]
        runs = f'/runs?head_sha={SOURCE}&status=success&per_page=100&page='
        jobs = '/runs/124/attempts/2/jobs?per_page=100&page='
        artifacts = '/runs/124/artifacts?per_page=100&page='
        pages = {
            runs + '1': {'total_count': 101, 'workflow_runs': [older, *excluded]},
            runs + '2': {'total_count': 101, 'workflow_runs': [newer]},
            jobs + '1': {'total_count': 2, 'jobs': [self.job('verify', run_id=124)]},
            jobs + '2': {'total_count': 2, 'jobs': [self.job('apk', 2, run_id=124)]},
            artifacts + '1': {'total_count': 2, 'artifacts': self.artifacts()[:1]},
            artifacts + '2': {'total_count': 2, 'artifacts': self.artifacts()[1:]},
        }
        with self.api(pages):
            result = self.find()
        self.assertEqual(result['run_id'], 124)
        self.assertEqual(result['run_number'], 43)
        self.assertEqual(len(self.calls), 6)

    def test_newer_incomplete_build_falls_back_to_the_latest_qualified_run(self):
        newer = self.workflow_run(run_id=124, number=43, event='push')
        pages = self.pages(runs=[self.workflow_run(), newer])
        pages = {
            '/runs/124/attempts/2/jobs?': {
                'total_count': 2,
                'jobs': [self.job('verify', run_id=124),
                         self.job('apk', 2, run_id=124, conclusion='failure')],
            }, **pages,
        }
        with self.api(pages):
            self.assertEqual(self.find()['run_id'], RUN_ID)

    def test_invalid_find_parameters_do_not_call_the_api(self):
        for changes in [{'source': 'HEAD'}, {'exclude_run': 0},
                        {'exclude_run': True}, {'repository': 'https://github.com/owner/repo'}]:
            with self.subTest(arguments=changes), patch.object(self.module.subprocess, 'run') as api:
                with self.assertRaises(self.module.ArtifactError):
                    self.find(**changes)
                api.assert_not_called()

    def test_other_source_repository_event_workflow_and_failed_runs_are_not_reusable(self):
        for changes in [
            {'head_sha': 'd' * 40}, {'conclusion': 'failure'}, {'status': 'in_progress'},
            {'event': 'pull_request'}, {'event': 'pull_request_target'},
            {'event': 'workflow_run'}, {'path': '.github/workflows/other.yaml'},
            {'repository': {'full_name': 'other/repo'}},
            {'head_repository': {'full_name': 'fork/repo'}}, {'id': 999},
        ]:
            with self.subTest(changes=changes), self.api(self.pages(runs=[self.workflow_run(**changes)])):
                self.assertTrue(all(value == '' for value in self.find().values()))

    def test_missing_failed_or_previous_attempt_jobs_are_not_reusable(self):
        for jobs in [
            [], [self.job('verify')], [self.job('apk')],
            [self.job('verify'), self.job('apk', 2, conclusion='failure')],
            [self.job('verify', run_attempt=1), self.job('apk', 2)],
            [self.job('verify', head_sha='d' * 40), self.job('apk', 2)],
            [self.job('verify', run_id=999), self.job('apk', 2)],
            [self.job('verify'), self.job('apk', 2), self.job('apk', 3)],
        ]:
            with self.subTest(jobs=jobs), self.api(self.pages(jobs=jobs)):
                self.assertTrue(all(value == '' for value in self.find().values()))

    def test_missing_expired_or_duplicate_artifacts_are_not_reusable(self):
        for artifacts in [
            [], self.artifacts()[:1],
            [{**item, 'expired': True} for item in self.artifacts()],
            self.artifacts() + [{'id': 12, 'name': 'Aerove-android-arm64', 'expired': False}],
        ]:
            with self.subTest(artifacts=artifacts), self.api(self.pages(artifacts=artifacts)):
                self.assertTrue(all(value == '' for value in self.find().values()))

    def test_api_and_format_errors_fail_closed_instead_of_falling_back(self):
        for value in [
            {}, {'total_count': 1, 'workflow_runs': []},
            {'total_count': '1', 'workflow_runs': [self.workflow_run()]},
            {'total_count': 1, 'workflow_runs': [{**self.workflow_run(), 'id': True}]},
            {'total_count': 2, 'workflow_runs': [self.workflow_run(), self.workflow_run()]},
            subprocess.CompletedProcess([], 1, '', 'API failure'),
            subprocess.CompletedProcess([], 0, 'not JSON', ''),
            subprocess.CompletedProcess([], 0, '{"total_count":0,"total_count":0,"workflow_runs":[]}', ''),
            OSError('gh is absent'),
        ]:
            with self.subTest(value=value), self.api({'/runs?': value}):
                with self.assertRaises(self.module.ArtifactError):
                    self.find()

    def test_missing_conclusions_and_malformed_artifact_provenance_fail_closed(self):
        run = copy.deepcopy(self.workflow_run())
        del run['conclusion']
        job = self.job('verify')
        del job['conclusion']
        artifacts = self.artifacts()
        artifacts[0]['workflow_run'] = {'id': RUN_ID + 1, 'head_sha': 'not a SHA'}
        for pages in [self.pages(runs=[run]), self.pages(jobs=[job]),
                      self.pages(artifacts=artifacts)]:
            with self.subTest(pages=pages), self.api(pages):
                with self.assertRaises(self.module.ArtifactError):
                    self.find()
        for key, value in [
            ('/attempts/2/jobs?', {'jobs': []}),
            ('/artifacts?', {'total_count': 1, 'artifacts': [{'id': 10, 'name': 'Aerove-android-arm64', 'expired': 'false'}]}),
        ]:
            pages = self.pages()
            pages[key] = value
            with self.subTest(endpoint=key), self.api(pages):
                with self.assertRaises(self.module.ArtifactError):
                    self.find()

    def test_find_cli_writes_outputs_and_empty_fallback_without_api_error_masking(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / 'outputs'
            arguments = [
                'find', '--repository', REPOSITORY, '--source', SOURCE,
                '--exclude-run', '999',
            ]
            with self.api(self.pages()), patch.dict(os.environ, {'GITHUB_OUTPUT': str(output)}):
                with redirect_stdout(io.StringIO()):
                    self.assertEqual(self.module.main(arguments), 0)
            self.assertIn(f'run_number={RUN_NUMBER}\n', output.read_text())
            with self.api(self.pages(runs=[])), redirect_stdout(io.StringIO()):
                self.assertEqual(self.module.main([*arguments, '--output', str(output)]), 0)
            self.assertTrue(output.read_text().endswith(
                'run_id=\nrun_number=\nartifact_id=\ngenerated_artifact_id=\n'
            ))
            before = output.read_bytes()
            with self.api({'/runs?': {}}), redirect_stderr(io.StringIO()):
                self.assertEqual(self.module.main([*arguments, '--output', str(output)]), 1)
            self.assertEqual(output.read_bytes(), before)


if __name__ == '__main__':
    unittest.main()
