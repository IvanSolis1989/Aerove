"""Fixed original APK and separate harness boundaries; no simulated Android acceptance."""
import copy
import json
import os
from pathlib import Path
import tempfile
import subprocess
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import emulator_current as current
import lite290_surface_probe as probe


class Original290ProbeTests(unittest.TestCase):
    def environment(self):
        return dict(AEROVE_LITE290_SURFACE_PROBE='1', GITHUB_REPOSITORY='IvanSolis1989/Aerove',
                    GITHUB_ACTOR='IvanSolis1989', GITHUB_EVENT_NAME='workflow_dispatch',
                    GITHUB_REF='refs/heads/codex/lite290-same-apk-probe', AEROVE_GUEST_ANGLE='1')

    def original_api(self):
        run = dict(id=42236353882, run_attempt=1, run_number=290,
                   head_sha='dbf758c8db04516780513453fa65609ee2e8e80f',
                   head_branch='codex/lite38-subscription-compat', path='.github/workflows/aerove-lite.yaml',
                   status='completed', conclusion='failure', repository=dict(full_name='IvanSolis1989/Aerove'))
        jobs = dict(total_count=1, jobs=[dict(id=127967391561, name='build (x86_64) / package',
                    run_id=42236353882, run_attempt=1, head_sha=run['head_sha'],
                    status='completed', conclusion='success')])
        row = dict(id=13516645962, name='AEROVE-Lite-runtime-contract-attempt-1', size_in_bytes=59641508,
                   digest='sha256:febafd629495244273d4fcc989b0cf883fa1909791473efb36a5b4530231efb5',
                   expired=False, workflow_run=dict(id=42236353882, repository_id=1429390079,
                   head_repository_id=1429390079, head_branch=run['head_branch'], head_sha=run['head_sha']))
        return run, jobs, row

    def test_failed_original_run_is_not_confused_with_failed_package_or_new_producer(self):
        probe.check_api(*self.original_api())
        for kind in ('run', 'attempt', 'source', 'package', 'inventory', 'duplicate', 'zip', 'repository'):
            run, jobs, row = copy.deepcopy(self.original_api())
            if kind == 'run':
                run['id'] = 42066278567
            elif kind == 'attempt':
                run['run_attempt'] = 2
            elif kind == 'source':
                run['head_sha'] = '0' * 40
            elif kind == 'package':
                jobs['jobs'][0]['conclusion'] = 'failure'
            elif kind == 'inventory':
                jobs['total_count'] = 36
            elif kind == 'duplicate':
                jobs['jobs'].append(jobs['jobs'][0])
                jobs['total_count'] = 2
            elif kind == 'zip':
                row['digest'] = 'sha256:' + '0' * 64
            else:
                row['workflow_run']['repository_id'] = 1
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                probe.check_api(run, jobs, row)

    def test_request_cannot_enable_old_apk_or_change_graphics(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertFalse(probe.enabled())
        env = self.environment()
        with patch.dict(os.environ, env, clear=True):
            self.assertTrue(probe.enabled())
        for change in ({'GITHUB_REF': 'refs/heads/main'}, {'GITHUB_ACTOR': 'untrusted'},
                       {'GITHUB_EVENT_NAME': 'push'}, {'AEROVE_GUEST_ANGLE': '0'},
                       {'AEROVE_LITE265_RECHECK': '1'}, {'AEROVE_GFXSTREAM_SOURCE_RECEIPT': 'other'},
                       {'AEROVE_LITE290_SURFACE_PROBE': 'false'}):
            with self.subTest(change=change), patch.dict(os.environ, {**env, **change}, clear=True), self.assertRaises(ValueError):
                probe.enabled()

    def test_only_fixed290_optin_changes_host_vulkan_and_rejects_hidden_overrides(self):
        with patch.dict(os.environ, self.environment(), clear=True):
            self.assertFalse(probe.lavapipe_requested())
            self.assertEqual('swangle', current.graphics_configuration()['gpuBackend'])
            self.assertEqual('swiftshader', current.graphics_configuration()['vulkanBackend'])
        env = {**self.environment(), probe.LAVAPIPE_OPT_IN: '1'}
        with patch.dict(os.environ, env, clear=True):
            self.assertEqual(dict(gpuBackend='lavapipe', vulkanBackend='lavapipe',
                guestRenderer='Guest ANGLE (Vulkan + Lavapipe)'), current.graphics_configuration())
        for change in ({probe.LAVAPIPE_OPT_IN: ''}, {probe.LAVAPIPE_OPT_IN: '0'},
                       {probe.OPT_IN: ''}, {'GITHUB_REF': 'refs/heads/main'},
                       {'AEROVE_LITE265_RECHECK': '1'}, {'VK_ICD_FILENAMES': '/outside.json'},
                       {'MESA_LOADER_DRIVER_OVERRIDE': 'other'}):
            with self.subTest(change=change), patch.dict(os.environ, {**env, **change}, clear=True), self.assertRaises(ValueError):
                current.graphics_configuration()

    def test_driver_inspection_binds_bundled_icd_and_rejects_external_or_missing_dependencies(self):
        with tempfile.TemporaryDirectory() as temporary:
            sdk = Path(temporary)
            directory = sdk / 'emulator/lib64/vulkan'
            directory.mkdir(parents=True)
            library = directory / 'libvulkan_lvp.so'
            library.write_bytes(b'fixture-only-not-an-ELF')
            icd = directory / 'lvp-fixture.json'
            icd.write_text(json.dumps({'ICD': {'library_path': library.name}}))
            with patch.dict(os.environ, {'LD_LIBRARY_PATH': ''}), \
                    patch.object(probe.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, 'libc => /lib/libc.so', '')) as ldd:
                result = probe.inspect_lavapipe_driver(sdk)
                self.assertTrue(result['passed'])
                self.assertEqual(probe.sha(library), result['candidates'][0]['sha256'])
                self.assertNotIn('', ldd.call_args.kwargs['env']['LD_LIBRARY_PATH'].split(os.pathsep))
                self.assertTrue(result['candidates'][0]['dependencyInspectionOnly'])
            with patch.object(probe.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, 'libmissing => not found', '')):
                self.assertFalse(probe.inspect_lavapipe_driver(sdk)['passed'])
            outside = sdk / 'outside-lvp.so'
            outside.write_bytes(b'external')
            icd.write_text(json.dumps({'ICD': {'library_path': str(outside)}}))
            with patch.object(probe.subprocess, 'run') as ldd:
                self.assertFalse(probe.inspect_lavapipe_driver(sdk)['passed'])
                ldd.assert_not_called()

    def test_only_single_ui_probe_uses_original_and_separate_harness_identity(self):
        identity = dict(sourceRevision='f' * 40, sourceTree='e' * 40, workflowRun='999', runAttempt=1, runNumber=291)
        runtime = dict(identity=probe.IDENTITY, original290Probe=True, harnessIdentity=identity,
                       diagnosticOnly=True, accepted=False)
        with patch.dict(os.environ, self.environment(), clear=True), patch.object(current.artifacts, 'identity', return_value=identity):
            current.guard_flutter_phase('flutter-ui', runtime=runtime)
            for phase in ('flutter-platform', 'flutter-full', 'matrix'):
                with self.subTest(phase=phase), self.assertRaises(ValueError):
                    current.guard_flutter_phase(phase, runtime=runtime)
            for field, value in (('identity', identity), ('harnessIdentity', probe.IDENTITY), ('accepted', True)):
                with self.subTest(field=field), self.assertRaises(ValueError):
                    current.guard_flutter_phase('flutter-ui', runtime={**runtime, field: value})

    def test_owner_command_never_selects_full_acceptance_or_rebuilds_app(self):
        env = dict(self.environment(), ANDROID_HOME='/sdk', AEROVE_LITE290_SURFACE_HARNESS='/runner.apk',
                   AEROVE_LITE290_SURFACE_HARNESS_INFO='/runner.json')
        inputs = dict(apk=Path('/original.apk'), test_apk=Path('/original-test.apk'), build_info=Path('/BUILD_INFO.json'))
        with patch.dict(os.environ, env, clear=True):
            command = current.phase_acceptance_command('flutter-ui', '/evidence', inputs)
            self.assertEqual(Path(command[1]).name, 'lite290_surface_probe.py')
            self.assertEqual(command[command.index('--apk') + 1], str(inputs['apk']))
            self.assertEqual(command[command.index('--harness') + 1], '/runner.apk')
            with self.assertRaises(ValueError):
                current.phase_acceptance_command('flutter-full', '/evidence', inputs)

    def test_stop_failure_does_not_skip_uninstall_or_replace_original_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = probe.Probe(SimpleNamespace(output=Path(directory), adb=Path('adb'), serial='emulator-5554'))
            runner.installed = [probe.PACKAGE]
            calls = []

            def cleanup(label, *arguments, **options):
                calls.append(label)
                if label.startswith('cleanup-stop-'):
                    raise RuntimeError('Owned force-stop timed out')
                return b'Success'

            with patch.dict(os.environ, {'AEROVE_CURRENT_RENDERER_PROOF': '/proof'}, clear=True), \
                    patch.object(probe, 'enabled', return_value=True), \
                    patch.object(probe.artifacts, 'identity', return_value={}), \
                    patch.object(current, 'validate_renderer_proof', side_effect=ValueError('Original proof failure')), \
                    patch.object(runner, 'adb_command', side_effect=cleanup):
                with self.assertRaisesRegex(ValueError, 'Original proof failure'):
                    runner.run()
            self.assertEqual(calls, ['cleanup-stop-dev.aerove.lite', 'cleanup-uninstall-dev.aerove.lite'])
            self.assertEqual(runner.receipt['result'], 1)


if __name__ == '__main__':
    unittest.main()
