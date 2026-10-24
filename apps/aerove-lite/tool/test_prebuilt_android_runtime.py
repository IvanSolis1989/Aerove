"""Approved prebuilt boundary fixtures; no APK build, download or Android execution."""
import copy
import errno
import io
import traceback
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import emulator_original84 as original
import emulator_current as current


class PrebuiltRuntimeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.runtime = self.root / 'runtime'
        self.runtime.mkdir()
        self.installed = self.root / 'installed'
        self.harness = dict(sourceRevision='f' * 40, sourceTree='e' * 40,
                            workflowRun='91000', runNumber=113, runAttempt=2)
        self.spec = copy.deepcopy(original.runtime_spec('lite94'))
        for name, location in [('app-release.apk', 'release'), ('app-release-androidTest.apk', 'androidTest/release')]:
            path = self.installed / location / name
            path.parent.mkdir(parents=True)
            path.write_bytes(('synthetic-' + name).encode())
            self.spec['apks'][name] = original.sha(path)
        product = self.spec['product']
        self.proof = dict(schema=1, diagnosticOriginalRuntime=True, runtimeTarget='lite94',
                          originalAppIdentity=self.spec['identity'], harnessIdentity=self.harness,
                          promotionAccepted=False, artifactId=self.spec['artifact'], artifactZipSha256=self.spec['zipSha256'],
                          runtimeManifestSha256=self.spec['manifestSha256'], apks=self.spec['apks'],
                          runtimeDirectory=str(self.runtime), packages={
                              'app-release.apk': dict(application_id=product['applicationId'], version=product['version'], version_code=12,
                                                      signer_sha256=product['signerSha256'], min_sdk=33),
                              'app-release-androidTest.apk': dict(application_id=product['applicationId'] + '.test', version=None, version_code=None,
                                  signer_sha256=product['signerSha256'], min_sdk=33, target_package=product['applicationId'],
                                  version_attributes_declared=False)})
        self.proof_path = self.root / 'proof.json'
        self.write()
        self.addCleanup(patch.stopall)
        env = dict(os.environ, AEROVE_ORIGINAL_RUNTIME_PROOF=str(self.proof_path), ANDROID_SERIAL='emulator-5554',
                   GITHUB_SHA=self.harness['sourceRevision'], GITHUB_RUN_ID=self.harness['workflowRun'],
                   GITHUB_RUN_NUMBER=str(self.harness['runNumber']), GITHUB_RUN_ATTEMPT=str(self.harness['runAttempt']))
        for key in ('AEROVE_ORIGINAL84_PROOF', 'AEROVE_QA79_PROOF', 'AEROVE_CURRENT_RENDERER_PROOF'):
            env.pop(key, None)
        patch.dict(os.environ, env, clear=True).start()
        patch.object(original, 'runtime_spec', return_value=self.spec).start()
        patch.object(original, 'APK_ROOT', self.installed).start()
        patch.object(original.artifacts, 'identity', return_value=self.harness).start()
        patch.object(original, 'validate_original_files', return_value={}).start()

    def write(self):
        self.proof_path.write_text(json.dumps(self.proof))

    def renderer(self):
        return dict(original.verify_original_runtime(self.runtime), schema=1, owner='emulator_current.py',
                    deviceSerial='emulator-5554', gpuBackend=current.GPU_BACKEND, rendererBackend=current.RENDERER_LABEL,
                    rendererEvidence=['Graphics Adapter ANGLE (Google SwiftShader)'], runtimeDirectory=str(self.runtime))

    def test_beta12_app_and_none_test_versions_stay_distinct_from_new_harness(self):
        value = current.verify_current_runtime(self.runtime)
        self.assertEqual(94, value['identity']['runNumber'])
        self.assertEqual(113, value['harnessIdentity']['runNumber'])
        self.assertEqual('0.1.0-beta.12', self.proof['packages']['app-release.apk']['version'])
        self.assertIsNone(self.proof['packages']['app-release-androidTest.apk']['version'])
        self.assertIs(value['promotionAccepted'], False)
        self.assertEqual(self.harness['sourceRevision'], os.environ['GITHUB_SHA'])

    def test_identity_artifact_hash_target_and_mixed_proof_tampering_fail(self):
        saved = copy.deepcopy(self.proof)
        fields = [('originalAppIdentity', self.harness), ('harnessIdentity', self.spec['identity']),
                  ('artifactId', 1), ('artifactZipSha256', '0' * 64), ('runtimeManifestSha256', '0' * 64),
                  ('apks', {}), ('promotionAccepted', True), ('runtimeTarget', 'unknown'),
                  ('diagnosticOriginalRuntime', False), ('diagnosticOriginal84', True),
                  ('schema', True), ('promotionAccepted', 0), ('diagnosticOriginalRuntime', 1),
                  ('originalAppIdentity', dict(self.spec['identity'], runAttempt=1.0))]
        for key, value in fields:
            self.proof = dict(saved, **{key: value}); self.write()
            with self.subTest(field=key), self.assertRaises(ValueError):
                current.verify_current_runtime(self.runtime)
        self.proof = saved; self.write()
        for name in ('AEROVE_ORIGINAL84_PROOF', 'AEROVE_QA79_PROOF'):
            with patch.dict(os.environ, {name: 'other-proof'}), self.assertRaises(ValueError):
                current.verify_current_runtime(self.runtime)

    def test_package_signer_target_and_none_versions_are_strict(self):
        saved = copy.deepcopy(self.proof)
        for package, key, value in [('app-release.apk', 'version', '0.1.0-beta.13'),
                                    ('app-release.apk', 'version_code', 13),
                                    ('app-release-androidTest.apk', 'version', '0.1.0-beta.12'),
                                    ('app-release-androidTest.apk', 'version_code', 12),
                                    ('app-release-androidTest.apk', 'version_attributes_declared', True),
                                    ('app-release-androidTest.apk', 'signer_sha256', '0' * 64),
                                    ('app-release-androidTest.apk', 'target_package', 'other')]:
            self.proof = copy.deepcopy(saved); self.proof['packages'][package][key] = value; self.write()
            with self.subTest(key=key, package=package), self.assertRaises(ValueError):
                current.verify_current_runtime(self.runtime)
        self.proof = copy.deepcopy(saved)
        del self.proof['packages']['app-release-androidTest.apk']['version']
        self.write()
        with self.assertRaises(ValueError):
            current.verify_current_runtime(self.runtime)

    def test_changed_or_linked_installed_apk_and_wrong_directory_fail(self):
        with self.assertRaises(ValueError):
            current.verify_current_runtime(self.root / 'wrong')
        path = self.installed / 'release/app-release.apk'
        with patch.object(Path, 'is_symlink', return_value=True), self.assertRaises(ValueError):
            current.verify_current_runtime(self.runtime)
        path.write_bytes(b'changed')
        with self.assertRaises(ValueError):
            current.verify_current_runtime(self.runtime)

    def test_renderer_binds_current_owned_backend_and_both_identities(self):
        value = self.renderer(); path = self.root / 'renderer.json'; path.write_text(json.dumps(value))
        self.assertEqual(self.spec['identity'], current.validate_renderer_proof(path)['identity'])
        for key, wrong in [('harnessIdentity', self.spec['identity']), ('identity', self.harness),
                           ('deviceSerial', 'emulator-5556'), ('rendererEvidence', ['Graphics Adapter Google SwiftShader'])]:
            path.write_text(json.dumps(dict(value, **{key: wrong})))
            with self.subTest(key=key), self.assertRaises(ValueError):
                current.validate_renderer_proof(path)

    def test_source_snapshot_keeps_case_app_source_and_harness_separate(self):
        renderer = self.root / 'renderer.json'; renderer.write_text(json.dumps(self.renderer()))
        shell = Path(__file__).with_name('emulator_acceptance.sh').read_text()
        program = shell.split("<<'PY'\n", 1)[1].split('\nPY\n', 1)[0]
        output = self.root / 'SOURCE.json'
        with patch.dict(os.environ, AEROVE_CURRENT_RENDERER_PROOF=str(renderer)), patch.object(current.sys, 'argv',
                ['snapshot', str(output), 'matrix', '600000', 'emulator-5554', '', '', 'dev.aerove.lite.ui.LiteDashboardMatrixTest']):
            exec(compile(program, 'neutral-original-source-snapshot', 'exec'), {})
        source = json.loads(output.read_text())
        self.assertEqual(self.spec['identity']['sourceRevision'], source['sourceRevision'])
        self.assertEqual(source['sourceRevision'], source['sourceAppRevision'])
        self.assertEqual(self.spec['identity']['workflowRun'], source['appBuildRun'])
        self.assertEqual(self.harness['sourceRevision'], source['harnessRevision'])
        self.assertEqual(self.harness, source['harnessIdentity'])
        self.assertEqual(self.harness['workflowRun'], source['workflowRun'])
        self.assertIs(source['promotionAccepted'], False)
        self.assertNotIn('diagnosticOriginal84', source)
        with patch.object(original.release, '_gh_json', return_value={'jobs': [{'name': 'Immutable Lite run94 matrix diagnosis', 'status': 'completed', 'conclusion': 'success'}]}), self.assertRaises(original.release.PromotionError):
            original.release._candidate_jobs(900, 1)

    def test_reader_cancellation_is_not_converted_into_diagnostic_success(self):
        interrupted = InterruptedError('cancelled during original proof read')
        with patch.object(original.release, '_json', side_effect=interrupted), self.assertRaises(InterruptedError) as error:
            current.verify_current_runtime(self.runtime)
        self.assertIs(interrupted, error.exception)


class ApprovedTargetTests(unittest.TestCase):
    def test_only_two_fixed_specs_and_default84(self):
        self.assertEqual(84, original.runtime_spec()['identity']['runNumber'])
        spec = original.runtime_spec('lite94')
        self.assertEqual(12570625761, spec['artifact'])
        self.assertEqual('12', spec['product']['versionCode'])
        for invalid in ('', '94', 'std40', 'current', 'latest', None):
            with self.subTest(target=invalid), self.assertRaises(ValueError):
                original.runtime_spec(invalid)

    def test_normal_current_rejects_cross_run_without_explicit_approved_proof(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            (directory / 'source-manifest.json').write_text(json.dumps(original.runtime_spec('lite94')['identity']))
            env = {key: value for key, value in os.environ.items() if key not in
                   ('AEROVE_ORIGINAL_RUNTIME_PROOF', 'AEROVE_ORIGINAL84_PROOF', 'AEROVE_QA79_PROOF')}
            with patch.dict(os.environ, env, clear=True), patch.object(current.artifacts, 'identity', return_value=dict(workflowRun='new-run')):
                with self.assertRaisesRegex(original.release.PromotionError, 'source/run/attempt'):
                    current.verify_current_runtime(directory)

    def test_unknown_target_and_bad_artifact_reject_before_download(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            with patch.object(original.release, '_gh_binary') as download, patch.object(original.release, '_gh_json', return_value={}):
                for target in ('unapproved', 'lite94'):
                    with self.subTest(target=target), self.assertRaises(ValueError):
                        original.prepare(directory / target, directory / 'evidence', target)
                download.assert_not_called()

    def test_archive_hash_crc_and_entry_boundaries_remain_strict(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp); archive = directory / 'bad.zip'
            for kind in ('unsafe', 'duplicate', 'crc'):
                with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_STORED) as z:
                    if kind == 'unsafe': z.writestr('../escape', b'payload')
                    elif kind == 'duplicate':
                        z.writestr('same', b'a'); z.writestr('same', b'b')
                    else: z.writestr('file', b'unique-payload')
                if kind == 'crc':
                    archive.write_bytes(archive.read_bytes().replace(b'unique-payload', b'broken-payload', 1))
                spec = copy.deepcopy(original.runtime_spec('lite94')); spec['zipSha256'] = original.sha(archive)
                with patch.object(original, 'runtime_spec', return_value=spec), self.assertRaises((ValueError, zipfile.BadZipFile)):
                    original.checked_archive(archive, directory / kind, 'lite94')
            with self.assertRaises(ValueError):
                original.checked_archive(archive, directory / 'wrong-hash', 'lite94')
            self.assertFalse((directory / 'escape').exists())


class ReaderEvidenceFailureTests(unittest.TestCase):
    def invoke(self, reports, failure_stage, primary=None, returncode=0, evidence_error=None, fallback_error=False):
        original_write = Path.write_text
        evidence_error = evidence_error or OSError(errno.ENOSPC, 'evidence disk full')
        suffix = {'stdout': '.txt', 'stderr': '.stderr.txt', 'command': '.command.json'}[failure_stage]
        def writer(path, value, *args, **kwargs):
            if path.name == 'reader.badging' + suffix:
                raise evidence_error
            return original_write(path, value, *args, **kwargs)
        def reader(*args, **kwargs):
            if primary is not None:
                raise primary
            return subprocess.CompletedProcess(['reader'], returncode, 'original output', 'original stderr')
        output = io.StringIO()
        with patch.object(original.subprocess, 'run', side_effect=reader), patch.object(Path, 'write_text', writer), \
                patch.object(original.sys, 'stderr', output):
            if fallback_error:
                with patch('builtins.print', side_effect=OSError(errno.ENOSPC, 'stderr disk full')):
                    original.inspection_command(['reader'], reports, 'reader', 'badging')
            else:
                original.inspection_command(['reader'], reports, 'reader', 'badging')

    def test_primary_timeout_and_interrupt_survive_all_three_write_failures(self):
        for stage in ('stdout', 'stderr', 'command'):
            for error in (subprocess.TimeoutExpired(['reader'], 60, output=b'partial output', stderr=b'partial error'),
                          KeyboardInterrupt('original cancel')):
                with tempfile.TemporaryDirectory() as temp, self.subTest(stage=stage, primary=type(error).__name__):
                    reports = Path(temp)
                    try:
                        self.invoke(reports, stage, primary=error, fallback_error=True)
                    except BaseException as caught:
                        self.assertIs(error, caught)
                        frames = traceback.extract_tb(caught.__traceback__)
                    else:
                        self.fail('Original reader error was swallowed')
                    self.assertIn('reader', [frame.name for frame in frames])
                    self.assertNotIn('writer', [frame.name for frame in frames])
                    if stage != 'command':
                        record = json.loads((reports / 'reader.badging.command.json').read_text())
                        self.assertEqual(type(error).__name__, record['errorType'])
                        self.assertEqual(isinstance(error, subprocess.TimeoutExpired), record['timeout'])
                        self.assertFalse(record[stage + 'Saved'])
                        self.assertNotIn(stage, record)
                        self.assertEqual('OSError', record['diagnosticWriteErrors'][0]['errorType'])

    def test_nonzero_command_is_primary_even_when_evidence_cannot_be_saved(self):
        for stage in ('stdout', 'stderr', 'command'):
            with tempfile.TemporaryDirectory() as temp, self.subTest(stage=stage):
                reports = Path(temp)
                with self.assertRaises(subprocess.CalledProcessError) as caught:
                    self.invoke(reports, stage, returncode=7)
                self.assertEqual(7, caught.exception.returncode)
                self.assertEqual('original output', caught.exception.stdout)
                if stage != 'command':
                    record = json.loads((reports / 'reader.badging.command.json').read_text())
                    self.assertEqual(7, record['returncode'])
                    self.assertEqual('CalledProcessError', record['errorType'])

    def test_successful_reader_with_failed_evidence_is_not_reported_complete(self):
        for stage in ('stdout', 'stderr', 'command'):
            error = OSError(errno.ENOSPC, 'original evidence write failure')
            with tempfile.TemporaryDirectory() as temp, self.subTest(stage=stage):
                reports = Path(temp)
                with self.assertRaises(OSError) as caught:
                    self.invoke(reports, stage, evidence_error=error)
                self.assertIs(error, caught.exception)
                if stage != 'command':
                    record = json.loads((reports / 'reader.badging.command.json').read_text())
                    self.assertEqual(0, record['returncode'])
                    self.assertFalse(record[stage + 'Saved'])
                    self.assertNotIn(stage, record)
                    self.assertEqual(1, len(record['diagnosticWriteErrors']))
                else:
                    self.assertFalse((reports / 'reader.badging.command.json').exists())

    def test_diagnostic_cancellation_keeps_first_cause(self):
        for primary in (None, subprocess.TimeoutExpired(['reader'], 60)):
            secondary = KeyboardInterrupt('cancel during evidence')
            with tempfile.TemporaryDirectory() as temp:
                expected = primary if primary is not None else secondary
                with self.assertRaises(type(expected)) as caught:
                    self.invoke(Path(temp), 'stdout', primary=primary, evidence_error=secondary)
                self.assertIs(expected, caught.exception)

    def test_successful_evidence_preserves_original_result_and_actual_saved_files(self):
        with tempfile.TemporaryDirectory() as temp:
            reports = Path(temp)
            result = subprocess.CompletedProcess(['reader'], 0, 'original output', 'original stderr')
            with patch.object(original.subprocess, 'run', return_value=result):
                self.assertEqual('original output', original.inspection_command(['reader'], reports, 'reader', 'badging'))
            record = json.loads((reports / 'reader.badging.command.json').read_text())
            self.assertEqual(0, record['returncode'])
            self.assertTrue(record['stdoutSaved'])
            self.assertTrue(record['stderrSaved'])
            self.assertEqual([], record['diagnosticWriteErrors'])
            self.assertEqual('original output', (reports / record['stdout']).read_text())
            self.assertEqual('original stderr', (reports / record['stderr']).read_text())


if __name__ == '__main__':
    unittest.main()
