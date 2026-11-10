import argparse
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

import lite368_diagnostic as m
import emulator_current as owner

ENV = dict(AEROVE_LITE368_DIAGNOSTIC='1', AEROVE_GUEST_ANGLE='1',
    GITHUB_REPOSITORY='IvanSolis1989/Aerove', GITHUB_EVENT_NAME='push',
    GITHUB_REF='refs/heads/' + m.BRANCH,
    GITHUB_WORKFLOW_REF='IvanSolis1989/Aerove/' + m.WORKFLOW + '@refs/heads/' + m.BRANCH,
    GITHUB_SHA='1' * 40, GITHUB_RUN_ID='999', GITHUB_RUN_ATTEMPT='1', GITHUB_RUN_NUMBER='1',
    ANDROID_HOME='/sdk')
HARNESS = dict(sourceRevision='1' * 40, sourceTree='2' * 40, workflowRun='999', runAttempt=1, runNumber=1)


def metadata():
    run = dict(id=42912128007, run_attempt=1, run_number=368,
        head_sha=m.IDENTITY['sourceRevision'], head_branch='fix/362-global-integration',
        path='.github/workflows/aerove-lite.yaml', repository=dict(full_name='IvanSolis1989/Aerove'),
        status='completed', conclusion='failure')
    jobs = dict(total_count=1, jobs=[dict(id=130188880885, name='build (x86_64) / package',
        run_id=run['id'], run_attempt=1, head_sha=m.IDENTITY['sourceRevision'],
        status='completed', conclusion='success')])
    row = dict(m.ARCHIVE, expired=False,
        url='https://api.github.com/repos/IvanSolis1989/Aerove/actions/artifacts/13811372268',
        workflow_run=dict(id=run['id'], repository_id=1429390079, head_repository_id=1429390079,
            head_branch='fix/362-global-integration', head_sha=m.IDENTITY['sourceRevision']))
    return run, jobs, row


class Fixed368Tests(unittest.TestCase):
    def proof(self):
        return dict(identity=m.IDENTITY, originalAppIdentity=m.IDENTITY, harnessIdentity=HARNESS,
            original368Probe=True, diagnosticOnly=True, **dict.fromkeys(m.FALSE_FIELDS, False))

    def test_exact_workflow_branch_event_graphics_required(self):
        with patch.dict(os.environ, ENV, clear=True):
            self.assertTrue(m.enabled())
            with patch.dict(os.environ, GITHUB_EVENT_NAME='workflow_dispatch'): self.assertTrue(m.enabled())
            for key, value in [('AEROVE_LITE368_DIAGNOSTIC', 'true'), ('GITHUB_REPOSITORY', 'x/y'),
                    ('GITHUB_EVENT_NAME', 'pull_request'), ('GITHUB_REF', 'refs/heads/main'),
                    ('GITHUB_WORKFLOW_REF', 'foreign'), ('AEROVE_GUEST_ANGLE', '0')]:
                with self.subTest(key=key), patch.dict(os.environ, {key: value}), self.assertRaises(ValueError): m.enabled()
            for key in ('AEROVE_ORIGINAL_RUNTIME_PROOF', 'AEROVE_LITE290_BOOT_CRASH', 'AEROVE_LITE343_ANR',
                        'AEROVE_GFXSTREAM_SOURCE_RECEIPT', 'VK_ICD_FILENAMES', 'VK_DRIVER_FILES',
                        'MESA_LOADER_DRIVER_OVERRIDE', 'AEROVE_CURRENT_RENDERER_OVERRIDE', 'AEROVE_QA_FINAL_PLAN_CAPTURE'):
                with self.subTest(key=key), patch.dict(os.environ, {key: 'x'}), self.assertRaises(ValueError): m.enabled()
        with patch.dict(os.environ, {}, clear=True): self.assertFalse(m.enabled())

    def test_owner_keeps_original_and_harness_separate(self):
        with patch.dict(os.environ, ENV, clear=True), patch.object(m, 'harness_identity', return_value=HARNESS):
            for phase in m.PHASES:
                m.validate_owner(self.proof(), phase)
                owner.guard_flutter_phase(phase, runtime=self.proof())
            for phase in ('matrix', 'remaining', 'original-sample', 'unknown'):
                with self.subTest(phase=phase), self.assertRaises(ValueError): m.validate_owner(self.proof(), phase)
            for key, value in [('identity', HARNESS), ('originalAppIdentity', HARNESS),
                    ('harnessIdentity', m.IDENTITY), ('diagnosticOnly', False), ('original368Probe', False)] + [
                    (key, True) for key in m.FALSE_FIELDS]:
                with self.subTest(key=key), self.assertRaises(ValueError):
                    m.validate_owner(dict(self.proof(), **{key: value}), 'flutter-ui')
            with self.assertRaises(ValueError): owner.guard_flutter_phase('flutter-ui', sample='sample', runtime=self.proof())

    def test_harness_matches_checkout_and_never_relabels_original_run(self):
        with patch.object(m.artifacts, 'identity', return_value=HARNESS), patch.object(m.subprocess, 'check_output', return_value='1' * 40):
            self.assertEqual(m.harness_identity(), HARNESS)
        for identity, head in [(HARNESS, 'f' * 40), (dict(HARNESS, workflowRun=m.IDENTITY['workflowRun']), '1' * 40),
                               (m.IDENTITY, m.IDENTITY['sourceRevision'])]:
            with self.subTest(identity=identity), patch.object(m.artifacts, 'identity', return_value=identity), \
                    patch.object(m.subprocess, 'check_output', return_value=head), self.assertRaises(ValueError): m.harness_identity()

    def test_api_rejects_wrong_run_attempt_source_producer_or_artifact(self):
        m.check_api(*metadata())
        for key, value in [('id', 1), ('run_attempt', 2), ('head_sha', '0' * 40), ('run_number', 369),
                           ('head_branch', 'main'), ('conclusion', 'success')]:
            run, jobs, row = metadata(); run[key] = value
            with self.subTest(run=key), self.assertRaises(ValueError): m.check_api(run, jobs, row)
        for key, value in [('id', 1), ('conclusion', 'failure'), ('run_attempt', 2), ('head_sha', '0' * 40)]:
            run, jobs, row = metadata(); jobs['jobs'][0][key] = value
            with self.subTest(job=key), self.assertRaises(ValueError): m.check_api(run, jobs, row)
        for key, value in [('id', 1), ('size_in_bytes', 1), ('digest', 'sha256:' + '0' * 64),
                           ('expired', True), ('url', 'https://foreign.invalid'), ('workflow_run', {})]:
            run, jobs, row = metadata(); row[key] = value
            with self.subTest(artifact=key), self.assertRaises(ValueError): m.check_api(run, jobs, row)
        run, jobs, row = metadata(); jobs['total_count'] += 1
        with self.assertRaises(ValueError): m.check_api(run, jobs, row)

    def test_archive_requires_exact_bytes_before_extracting(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive, target = Path(tmp) / 'input.zip', Path(tmp) / 'runtime'
            with zipfile.ZipFile(archive, 'w') as package: package.writestr('a', 'synthetic')
            with self.assertRaises(ValueError): m.extract_archive(archive, target)
            self.assertFalse(target.exists())
            spec = dict(size_in_bytes=archive.stat().st_size, digest='sha256:' + m.sha(archive))
            with patch.object(m, 'ARCHIVE', spec):
                m.extract_archive(archive, target)
                self.assertEqual((target / 'a').read_text(), 'synthetic')
                with self.assertRaises(ValueError): m.extract_archive(archive, target)

    def test_unsafe_archive_members_are_rejected_before_extracting(self):
        cases = ['../outside', '/absolute', 'C:/drive', 'a\\b', './hidden', 'a//b', 'a:b', 'symlink', 'duplicate']
        for name in cases:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as tmp:
                archive, target = Path(tmp) / 'input.zip', Path(tmp) / 'runtime'
                with warnings.catch_warnings(), zipfile.ZipFile(archive, 'w') as package:
                    warnings.simplefilter('ignore', UserWarning)
                    entry = zipfile.ZipInfo(name)
                    if name == 'symlink': entry.external_attr = (stat.S_IFLNK | 0o777) << 16
                    package.writestr(entry, 'payload')
                    if name == 'duplicate': package.writestr(entry, 'other')
                spec = dict(size_in_bytes=archive.stat().st_size, digest='sha256:' + m.sha(archive))
                with patch.object(m, 'ARCHIVE', spec), self.assertRaises(ValueError): m.extract_archive(archive, target)
                self.assertFalse(target.exists())

    def test_runtime_rejects_changed_seal_info_app_and_runners(self):
        run, jobs, artifact = metadata()
        api = dict(run=run, jobs=jobs, artifact=artifact)
        info = dict(source=m.IDENTITY['sourceRevision'], tree=m.IDENTITY['sourceTree'], product='AEROVE Lite',
            channel='preview', application_id=m.PACKAGE, abi='x86_64', min_sdk=33, version='0.2.0-beta.8',
            version_code=40, run_number=368, run_attempt=1, launcher_activity=m.PACKAGE + '.FlutterMainActivity',
            workflow='https://github.com/IvanSolis1989/Aerove/actions/runs/42912128007',
            apk_sha256=m.APKS['app-release.apk'], signer_sha256=m.SIGNER)
        hashes = dict(m.APKS, **{'source-manifest.json': m.MANIFEST, 'BUILD_INFO.json': m.INFO})
        for changed in (None, *hashes):
            def digest(path): return '0' * 64 if Path(path).name == changed else hashes[Path(path).name]
            with self.subTest(changed=changed), patch.object(m, 'enabled', return_value=True), \
                    patch.object(m.release, '_json', side_effect=lambda path: api if str(path).endswith('.api.json') else info), \
                    patch.object(m.release.product_source, 'bind_archive'), \
                    patch.object(m.artifacts, 'verify', return_value=dict(files=[{}] * 76, apkInspectorOutcome='success')), \
                    patch.object(m, 'sha', side_effect=digest), patch.object(m, 'harness_identity', return_value=HARNESS), \
                    patch('verify_apk.verify_bundled_script_apk', return_value={}):
                if changed is None:
                    proof = m.runtime(Path('/synthetic'), installed=False)
                    self.assertEqual(proof['identity'], m.IDENTITY)
                    self.assertEqual(proof['harnessIdentity'], HARNESS)
                else:
                    with self.assertRaises(ValueError): m.runtime(Path('/synthetic'), installed=False)

    def test_signature_mismatch_of_each_original_apk_is_rejected(self):
        for name in m.APKS:
            def signature(command, **kwargs): return b'wrong' if Path(command[-1]).name == name else b'correct'
            with self.subTest(name=name), patch.object(m.subprocess, 'check_output', side_effect=signature), \
                    patch('verify_apk.signing_certificate', side_effect=lambda text: '0' * 64 if text == 'wrong' else m.SIGNER), \
                    self.assertRaises(ValueError): m.verify_signatures('/synthetic', 'apksigner')

    def test_explicit_phase_command_never_calls_aggregate_or_build(self):
        inputs = dict(apk=Path('/app'), test_apk=Path('/test'), platform_apk=Path('/platform'), build_info=Path('/info'))
        with patch.dict(os.environ, ENV, clear=True):
            for phase in m.PHASES:
                command = m.command(argparse.Namespace(phase=phase), inputs, Path('/evidence'))
                self.assertIn('run', command); self.assertIn(phase, command)
                self.assertTrue(command[1].endswith('lite368_diagnostic.py'))
                self.assertNotIn('aggregate', ' '.join(command))
            with self.assertRaises(ValueError): m.command(argparse.Namespace(phase='matrix'), inputs, '/evidence')
            with self.assertRaises(ValueError): m.command(argparse.Namespace(phase='flutter-ui'), {}, '/evidence')

    def test_workflow_scoped_read_permission_no_product_build_or_security_changes(self):
        import yaml
        workflow = yaml.safe_load((Path(__file__).resolve().parents[3] / m.WORKFLOW).read_text())
        self.assertEqual(workflow['on']['push']['branches'], [m.BRANCH])
        self.assertNotIn('pull_request_target', workflow['on'])
        self.assertEqual(workflow['permissions'], dict(contents='read', actions='read'))
        job = workflow['jobs']['diagnose']; text = json.dumps(job)
        self.assertFalse(job['strategy']['fail-fast'])
        self.assertEqual(set(job['strategy']['matrix']['phase']), set(m.PHASES))
        self.assertEqual(job['env'][m.OPT_IN], '1'); self.assertEqual(job['env']['AEROVE_LITE368_BOOT_CRASH'], '1')
        for required in ('SigillCaptureHostTests', 'AEROVE_TEST_SIGILL_GDB', 'lite368_diagnostic.py fetch',
                         'test -c /dev/kvm', 'test -r /dev/kvm', 'test -w /dev/kvm',
                         'cbc7c3cfb83220b8a0f9c513fdab557712e74d339ec19ddffa43f23350ae9bdd'):
            self.assertIn(required, text)
        for forbidden in ('udevadm', '/etc/udev', 'chmod', 'setfacl', 'sysctl', 'gradle', 'flutter build',
                          'aggregate.py', 'publish_release', 'secrets.'):
            self.assertNotIn(forbidden, text)
        collector = next(step for step in job['steps'] if 'AEROVE_TEST_SIGILL_GDB' in step.get('env', {}))
        for forbidden in ('curl', 'wget', 'pip install', 'ptrace_scope'): self.assertNotIn(forbidden, collector['run'])


if __name__ == '__main__': unittest.main()
