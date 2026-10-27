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



class OriginalApkRecheckTest(unittest.TestCase):
    def setUp(self):
        self.module = importlib.import_module('tool.aerove_artifact')
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.env = {**os.environ, 'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': os.devnull,
                    'GIT_AUTHOR_NAME': 'Offline Test', 'GIT_COMMITTER_NAME': 'Offline Test',
                    'GIT_AUTHOR_EMAIL': 'test@example.invalid', 'GIT_COMMITTER_EMAIL': 'test@example.invalid'}
        self.git('init', '-q')
        self.git('config', 'core.autocrlf', 'false')
        self.workflow = self.root / self.module.WORKFLOW
        self.workflow.parent.mkdir(parents=True)
        self.original_workflow = 'name: fixture\non:\n  workflow_dispatch:\n    inputs:\n' + 'jobs:\n'
        for name in ('reuse', 'prep', 'static', 'native', 'render', 'regressions', 'verify', 'apk', 'release'):
            self.original_workflow += f'  {name}:\n'
            if name == 'prep':
                self.original_workflow += "    if: ${{ needs.reuse.outputs.run_id == '' }}\n"
            if name in ('render', 'regressions'):
                self.original_workflow += '    strategy:\n      fail-fast: true\n'
            if name == 'apk':
                self.original_workflow += "    if: ${{ github.event_name == 'push' || inputs.build_apk }}\n"
            self.original_workflow += '    steps:\n      - run: preserved-' + name + '\n'
        self.workflow.write_text(self.original_workflow, encoding='utf-8')
        self.test = self.root / self.module.RECHECK_TEST
        self.test.parent.mkdir(parents=True)
        self.test.write_text('original test\n', encoding='utf-8')
        for path in self.module.RECHECK_WIRING - {self.module.WORKFLOW}:
            target = self.root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text('original wiring\n', encoding='utf-8')
        doc = self.root / 'docs/development/recheck.md'
        doc.parent.mkdir(parents=True)
        doc.write_text('Original declaration\n', encoding='utf-8')
        self.commit()
        self.original = self.git('rev-parse', 'HEAD')
        self.before_test = self.git('rev-parse', f'HEAD:{self.module.RECHECK_TEST}')
        addition = ("      original_build_run:\n"
                    "        description: Immutable original APK run to recheck (40488925368 only)\n"
                    "        type: string\n        default: ''\n")
        updated = self.original_workflow.replace('jobs:\n', addition + 'jobs:\n')
        updated = updated.replace('fail-fast: true', 'fail-fast: false')
        updated = updated.replace("${{ needs.reuse.outputs.run_id == '' }}", "${{ inputs.verification_recheck || needs.reuse.outputs.run_id == '' }}")
        updated = updated.replace("${{ github.event_name == 'push' || inputs.build_apk }}", "${{ !inputs.verification_recheck && (github.event_name == 'push' || inputs.build_apk) }}")
        self.workflow.write_text(updated, encoding='utf-8')
        self.test.write_text('reviewed test fix\n', encoding='utf-8')
        self.commit()
        self.harness = self.git('rev-parse', 'HEAD')
        after = self.git('rev-parse', f'HEAD:{self.module.RECHECK_TEST}')
        self.addCleanup(patch.stopall)
        patch.object(self.module, 'RECHECK_SOURCE', self.original).start()
        patch.object(self.module, 'RECHECK_WORKFLOW_BLOB', self.git('rev-parse', f'HEAD:{self.module.WORKFLOW}')).start()
        patch.object(self.module, 'RECHECK_ARTIFACTS', {
            'Aerove-android-arm64': (10, 100, 'sha256:' + 'a' * 64),
            'xclash-generated': (11, 100, 'sha256:' + 'a' * 64),
        }).start()
        patch.object(self.module, 'RECHECK_TEST_BLOBS', (self.before_test, after)).start()

    def git(self, *arguments):
        result = subprocess.run(['git', *arguments], cwd=self.root, env=self.env,
                                capture_output=True, text=True, check=True)
        return result.stdout.strip()

    def commit(self):
        self.git('add', '--all')
        self.git('-c', 'commit.gpgsign=false', 'commit', '-qm', 'offline fixture')

    def inputs(self):
        return self.module.recheck_inputs(self.root, self.git('rev-parse', 'HEAD'))

    def origin(self, **changes):
        return dict(id=self.module.RECHECK_RUN, run_number=55, run_attempt=1, head_sha=self.original,
                    path=self.module.WORKFLOW, event='workflow_dispatch', status='completed', conclusion='failure',
                    repository=dict(full_name=REPOSITORY), head_repository=dict(full_name=REPOSITORY), **changes)

    def artifacts(self):
        return [dict(id=index, name=name, expired=False, size_in_bytes=100, digest='sha256:' + 'a' * 64,
                     workflow_run=dict(id=self.module.RECHECK_RUN, head_sha=self.original))
                for index, name in enumerate(('Aerove-android-arm64', 'xclash-generated'), 10)]

    def jobs(self, run):
        from tool import ci_verify
        names = ['reuse', 'prep', 'static', 'native', 'verify', 'recheck']
        names += [f'{group} ({row["suite"]} {row["shard"]})'
                  for group, rows in ci_verify.matrices().items() for row in rows]
        return [dict(id=index, run_id=run['id'], run_attempt=run['run_attempt'], head_sha=run['head_sha'],
                     name=name, status='completed', conclusion='success')
                for index, name in enumerate(names, 1)] + [
                    dict(id=80, run_id=run['id'], head_sha=run['head_sha'], name='apk',
                         status='completed', conclusion='skipped')]

    def test_reviewed_fix_and_existing_document_declaration_record_exact_blob_map(self):
        rows = self.inputs()
        self.assertEqual({row['path'] for row in rows}, {self.module.RECHECK_TEST, self.module.WORKFLOW})
        (self.root / 'docs/development/recheck.md').write_text('Recheck facts\n', encoding='utf-8')
        self.commit()
        self.assertIn('docs/development/recheck.md', {row['path'] for row in self.inputs()})

    def test_actual_production_dependencies_assets_and_unreviewed_tests_are_rejected(self):
        for path in ('apps/aerove/lib/main.dart', 'apps/aerove/pubspec.lock',
                     'apps/aerove/assets/logo.png', 'apps/aerove/tool/setup.dart',
                     'apps/aerove/test/other_test.dart', 'apps/aerove-lite/build.gradle.kts'):
            with self.subTest(path=path):
                target = self.root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text('changed\n', encoding='utf-8')
                self.commit()
                with self.assertRaises(self.module.ArtifactError):
                    self.inputs()
                self.git('reset', '--hard', self.harness)

    def test_changed_test_bytes_removed_files_and_file_modes_are_rejected(self):
        self.test.write_text('different fix\n', encoding='utf-8')
        self.commit()
        with self.assertRaisesRegex(self.module.ArtifactError, 'reviewed'):
            self.inputs()
        self.git('reset', '--hard', self.harness)
        self.test.unlink()
        self.commit()
        with self.assertRaisesRegex(self.module.ArtifactError, 'regular'):
            self.inputs()
        self.git('reset', '--hard', self.harness)
        self.git('update-index', '--chmod=+x', self.module.RECHECK_TEST)
        self.git('-c', 'commit.gpgsign=false', 'commit', '-qm', 'changed mode')
        with self.assertRaisesRegex(self.module.ArtifactError, 'regular'):
            self.inputs()

    def test_workflow_cannot_change_build_commands_toolchain_or_required_verification(self):
        accepted = self.workflow.read_text(encoding='utf-8')
        for before, after in [('preserved-apk', 'changed-build'), ('preserved-native', 'skip-native'),
                              ('preserved-render', 'skip-render'), ('name: fixture', 'name: changed')]:
            with self.subTest(change=before):
                self.workflow.write_text(accepted.replace(before, after), encoding='utf-8')
                self.commit()
                with self.assertRaises(self.module.ArtifactError):
                    self.inputs()
                self.git('reset', '--hard', self.harness)

    def test_original_failed_run_is_retained_but_only_its_successful_apk_is_selected(self):
        apk = dict(id=1, run_id=self.module.RECHECK_RUN, head_sha=self.original, run_attempt=1,
                   name='apk', status='completed', conclusion='success')
        with patch.object(self.module, '_api_object', return_value=self.origin()), \
                patch.object(self.module, '_api_items', side_effect=[[apk], self.artifacts()]):
            result = self.module.original_recheck_build(REPOSITORY, self.module.RECHECK_RUN)
        self.assertEqual(result['build_conclusion'], 'failure')
        self.assertEqual(result['build_source'], self.original)
        self.assertEqual(result['artifact_id'], 10)
        for changes in (dict(conclusion='failure'), dict(conclusion='cancelled'), dict(status='in_progress'),
                        dict(run_attempt=2), dict(head_sha='f' * 40)):
            with self.subTest(changes=changes), patch.object(self.module, '_api_object', return_value=self.origin()), \
                    patch.object(self.module, '_api_items', return_value=[{**apk, **changes}]):
                with self.assertRaises(self.module.ArtifactError):
                    self.module.original_recheck_build(REPOSITORY, self.module.RECHECK_RUN)
        with patch.object(self.module, '_api_object') as api:
            with self.assertRaises(self.module.ArtifactError):
                self.module.original_recheck_build(REPOSITORY, self.module.RECHECK_RUN + 1)
            api.assert_not_called()

    def test_original_incomplete_rerun_expired_and_forged_artifacts_fail_closed(self):
        apk = dict(id=1, run_id=self.module.RECHECK_RUN, head_sha=self.original, name='apk',
                   status='completed', conclusion='success')
        for changes in (dict(status='in_progress'), dict(run_attempt=2), dict(id=9), dict(conclusion='cancelled')):
            with self.subTest(changes=changes), patch.object(self.module, '_api_object', return_value={**self.origin(), **changes}):
                with self.assertRaises(self.module.ArtifactError):
                    self.module.original_recheck_build(REPOSITORY, self.module.RECHECK_RUN)
        for changes in (dict(expired=True), dict(id=999), dict(size_in_bytes=99), dict(digest='sha256:' + 'b' * 64),
                        dict(workflow_run=dict(id=9, head_sha=self.original)),
                        dict(workflow_run=dict(id=self.module.RECHECK_RUN, head_sha='f' * 40))):
            artifacts = self.artifacts()
            artifacts[0].update(changes)
            with self.subTest(changes=changes), patch.object(self.module, '_api_object', return_value=self.origin()), \
                    patch.object(self.module, '_api_items', side_effect=[[apk], artifacts]):
                with self.assertRaises(self.module.ArtifactError):
                    self.module.original_recheck_build(REPOSITORY, self.module.RECHECK_RUN)

    def test_every_required_combination_and_no_apk_build_is_mandatory(self):
        run = dict(id=777, run_attempt=1, head_sha=self.harness)
        jobs = self.jobs(run)
        with patch.object(self.module, '_api_items', return_value=jobs):
            self.module._recheck_jobs(REPOSITORY, run, final=True)
        for index in range(len(jobs)):
            for conclusion in ('failure', 'cancelled', 'skipped'):
                if jobs[index]['name'] == 'apk' and conclusion == 'skipped':
                    continue
                changed = copy.deepcopy(jobs)
                changed[index]['conclusion'] = conclusion
                with self.subTest(job=jobs[index]['name'], conclusion=conclusion), \
                        patch.object(self.module, '_api_items', return_value=changed):
                    with self.assertRaises(self.module.ArtifactError):
                        self.module._recheck_jobs(REPOSITORY, run, final=True)
        for altered in (jobs[:-1], jobs + [jobs[0]], [{**job, 'head_sha': self.original} for job in jobs],
                        [{**job, 'run_attempt': 2} for job in jobs],
                        [{**job, 'conclusion': 'success'} if job['name'] == 'apk' else job for job in jobs]):
            with patch.object(self.module, '_api_items', return_value=altered):
                with self.assertRaises(self.module.ArtifactError):
                    self.module._recheck_jobs(REPOSITORY, run, final=True)

    def test_finder_selects_original_identity_and_rejects_forged_receipt(self):
        run = {**self.origin(), 'id': 777, 'head_sha': self.harness, 'conclusion': 'success'}
        selected = dict(run_id=self.module.RECHECK_RUN, run_number=55, build_source=self.original,
                        build_tree=self.git('rev-parse', f'{self.original}^{{tree}}'), build_conclusion='failure',
                        build_attempt=1, artifact_id=10, generated_artifact_id=11,
                        test_harness_source=self.harness, changed=self.inputs())
        receipt = dict(schema=1, role='originalApkVerificationRecheck', accepted=True, compiled=False,
                       repository=REPOSITORY, originalBuild=selected,
                       testHarness=dict(source=self.harness, tree=self.git('rev-parse', 'HEAD^{tree}'), run_id=777, run_attempt=1),
                       files={name: 'a' * 64 for name in (self.module.APK_NAME, 'BUILD_INFO.json', 'SHA256SUMS')},
                       signer_sha256=self.module.RECHECK_SIGNER)
        def find(value):
            with patch.object(self.module, '_api_items', side_effect=[[run], [dict(name=self.module.RECHECK_ARTIFACT)]]), \
                    patch.object(self.module, '_recheck_jobs'), \
                    patch.object(self.module, '_download_receipt', return_value=value), \
                    patch.object(self.module, 'recheck_plan', return_value=selected):
                return self.module.find_rechecked_artifacts(root=self.root, repository=REPOSITORY,
                                                             source=self.git('rev-parse', 'HEAD'), exclude_run=999)
        self.assertEqual(find(receipt)['run_id'], self.module.RECHECK_RUN)
        (self.root / 'docs/development/recheck.md').write_text('Main facts only\n', encoding='utf-8')
        self.commit()
        self.assertEqual(find(receipt)['run_id'], self.module.RECHECK_RUN)
        self.git('update-index', '--chmod=+x', 'docs/development/recheck.md')
        self.git('-c', 'commit.gpgsign=false', 'commit', '-qm', 'invalid main doc mode')
        with self.assertRaisesRegex(self.module.ArtifactError, 'regular'):
            find(receipt)
        self.git('reset', '--hard', self.harness)
        for key, value in [('accepted', False), ('compiled', True), ('signer_sha256', 'f' * 64),
                           ('originalBuild', {**selected, 'build_source': self.harness}),
                           ('testHarness', {**receipt['testHarness'], 'source': self.original}),
                           ('files', {self.module.APK_NAME: 'a' * 64})]:
            with self.subTest(key=key), self.assertRaises(self.module.ArtifactError):
                find({**receipt, key: value})

    def test_reviewed_source_without_receipt_cannot_fall_back_to_rebuilding(self):
        with patch.object(self.module, '_api_items', return_value=[]):
            with self.assertRaisesRegex(self.module.ArtifactError, 'rebuilding is forbidden'):
                self.module.find_rechecked_artifacts(root=self.root, repository=REPOSITORY,
                                                      source=self.harness, exclude_run=999)



class OriginalApkReceiptTest(unittest.TestCase):
    def setUp(self):
        self.fixture = ArtifactFilesTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.module = self.fixture.module
        self.fixture.info['version'] = f'0.11.0-beta.7+{self.module.product.VERSION_CODE_BASE + RUN_NUMBER}'
        self.fixture.info['appEnv'] = 'pre'
        self.fixture.save_info()
        payloads = {path.name: (path.stat().st_size, hashlib.sha256(path.read_bytes()).hexdigest())
                    for directory in (self.fixture.dist, self.fixture.generated) for path in directory.iterdir()}
        pin = patch.object(self.module, 'RECHECK_PAYLOADS', payloads)
        pin.start()
        self.addCleanup(pin.stop)
        self.run = dict(id=777, run_number=56, run_attempt=1, head_sha='f' * 40,
                        path=self.module.WORKFLOW, event='workflow_dispatch', status='in_progress',
                        conclusion=None, head_branch='codex/test-harness', repository=dict(full_name=REPOSITORY),
                        head_repository=dict(full_name=REPOSITORY))
        self.selected = dict(run_id=RUN_ID, run_number=RUN_NUMBER, build_source=SOURCE, build_tree=TREE)
        self.certs = f'Signer #1 certificate SHA-256 digest: {self.module.RECHECK_SIGNER}\n'
        name, code = self.fixture.info['version'].rsplit('+', 1)
        self.badging = f"package: name='com.follow.clash.dev' versionCode='{code}' versionName='{name}' platformBuildVersionName='37'\n"

    def command(self, args, root=None):
        if args[0] == 'signer':
            return self.certs
        if args[0] == 'aapt':
            return self.badging
        if args[:2] == ['git', 'show']:
            return (json.dumps(self.fixture.metadata) if args[-1].endswith('upstream-version.json')
                    else 'version: 0.11.0-beta.7+1\n')
        self.fail(str(args))

    def receipt(self):
        jobs = OriginalApkRecheckTest.jobs(self, self.run)
        with patch.object(self.module, 'RECHECK_SOURCE', SOURCE), \
                patch.object(self.module, 'recheck_plan', return_value=self.selected), \
                patch.object(self.module, '_api_object', return_value=self.run), \
                patch.object(self.module, '_api_items', return_value=jobs), \
                patch.object(self.module, '_command', side_effect=self.command), \
                patch.object(self.module, '_git', return_value='e' * 40):
            return self.module.recheck_receipt(root=self.fixture.root, repository=REPOSITORY,
                source=self.run['head_sha'], build_run=RUN_ID, run_id=777, directory=self.fixture.dist,
                generated_directory=self.fixture.generated, apksigner=Path('signer'), aapt=Path('aapt'))

    def test_acceptance_uses_real_artifact_validator_and_preserves_every_original_byte(self):
        before = {path: path.read_bytes() for directory in (self.fixture.dist, self.fixture.generated)
                  for path in directory.iterdir()}
        receipt = self.receipt()
        self.assertIs(receipt['accepted'], True)
        self.assertIs(receipt['compiled'], False)
        self.assertEqual(receipt['originalBuild']['build_source'], SOURCE)
        self.assertEqual(receipt['testHarness']['source'], 'f' * 40)
        self.assertEqual(receipt['files'][self.module.APK_NAME], hashlib.sha256(before[self.fixture.dist / self.module.APK_NAME]).hexdigest())
        self.assertEqual(before, {path: path.read_bytes() for path in before})

    def test_acceptance_rejects_wrong_signer_actual_package_and_original_source(self):
        for value in ('Signer #1 certificate SHA-256 digest: ' + 'a' * 64 + '\n',
                      self.certs + self.certs):
            self.certs = value
            with self.assertRaises(self.module.ArtifactError):
                self.receipt()
        self.certs = f'Signer #1 certificate SHA-256 digest: {self.module.RECHECK_SIGNER}\n'
        self.badging = self.badging.replace('com.follow.clash.dev', 'dev.aerove.lite')
        with self.assertRaises(self.module.ArtifactError):
            self.receipt()
        self.fixture.info['source'] = 'f' * 40
        self.fixture.save_info()
        with self.assertRaisesRegex(self.module.ArtifactError, 'BUILD_INFO'):
            self.receipt()

    def test_any_original_payload_byte_change_is_rejected_before_acceptance(self):
        for directory in (self.fixture.dist, self.fixture.generated):
            for path in directory.iterdir():
                original = path.read_bytes()
                with self.subTest(payload=path.name):
                    path.write_bytes(original + b'changed')
                    with self.assertRaisesRegex(self.module.ArtifactError, 'payload bytes/hash'):
                        self.receipt()
                    path.write_bytes(original)

    def test_promotion_cli_rejects_downloaded_byte_swap_before_publication(self):
        receipt = self.receipt()
        selected = {**self.selected, 'recheck_run': 777, 'receipt': receipt}
        flags = ['--promotion-root', str(self.fixture.root), '--promotion-source', 'f' * 40, '--recheck-run', '777']
        pubspec = self.fixture.root / 'pubspec.yaml'
        pubspec.write_text('version: 0.11.0-beta.7+1\n')
        args = [*self.fixture.arguments(), '--pubspec', str(pubspec), *flags]
        with patch.object(self.module, 'find_rechecked_artifacts', return_value=selected), \
                patch.dict(os.environ, {'GITHUB_RUN_ID': '999'}), redirect_stdout(io.StringIO()):
            self.assertEqual(self.module.main(args), 0)
            (self.fixture.dist / self.module.APK_NAME).write_bytes(b'substituted package')
            with redirect_stderr(io.StringIO()):
                self.assertEqual(self.module.main(args), 1)


if __name__ == '__main__':
    unittest.main()
