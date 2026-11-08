"""Adapter contract tests, not evidence of Android pixel success."""
import copy
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from unittest.mock import Mock

import lite328_surface_probe as probe


class Fixed328Tests(unittest.TestCase):
    def api(self):
        return dict(probe.ARCHIVE, expired=False,
                    url='https://api.github.com/repos/IvanSolis1989/Aerove/actions/artifacts/13619525870',
                    workflow_run=dict(id=42482381897, head_sha=probe.APP['sourceRevision'],
                                      repository_id=1429390079, head_repository_id=1429390079))

    def test_only_exact_api_identity(self):
        probe.validate_api(self.api())
        for key, value in [('id', 1), ('size_in_bytes', 1), ('digest', 'sha256:' + '0' * 64),
                           ('expired', True), ('name', 'different'), ('url', 'https://example.test')]:
            row = self.api(); row[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                probe.validate_api(row)
        for key in ('id', 'head_sha', 'repository_id', 'head_repository_id'):
            row = self.api(); row['workflow_run'][key] = 'wrong'
            with self.subTest(key=key), self.assertRaises(ValueError):
                probe.validate_api(row)

    def test_execution_scope_and_historical_opt_ins_rejected(self):
        env = dict(GITHUB_REPOSITORY='IvanSolis1989/Aerove', GITHUB_EVENT_NAME='workflow_dispatch',
                   GITHUB_REF='refs/heads/dot/cloud-j316-checkpoint-20261108')
        with patch.dict(os.environ, env, clear=True):
            probe.guard_execution()
        for key, value in [('GITHUB_EVENT_NAME', 'push'), ('GITHUB_REF', 'refs/heads/main'),
                           ('GITHUB_REPOSITORY', 'other/repo'), ('AEROVE_LITE290_LAVAPIPE', '1')]:
            with patch.dict(os.environ, dict(env, **{key: value}), clear=True), self.assertRaises(ValueError):
                probe.guard_execution()

    def test_adapter_defaults_cannot_accept_or_change_renderer(self):
        with tempfile.TemporaryDirectory() as directory:
            args = SimpleNamespace(original_run_number=328, app_renderer='default',
                                   disable_queue_submit_with_commands=False, surface_test_apk=Path('runner.apk'),
                                   surface_test_info=Path('runner.json'), sdk=Path(directory),
                                   source='a' * 40, run='999', attempt='1')
            value = probe.Diagnostic(args)
            self.assertEqual(probe.APP, value.app)
            self.assertEqual(('0.2.0-beta.7', '39'), value.app_version())
            self.assertEqual(probe.SELECTOR, value.surface_selector())
            for key in ('accepted', 'visualApproved', 'performanceAccepted', 'fullAcceptance',
                        'releaseAccepted', 'promotionAccepted', 'appCompiled'):
                self.assertIs(value.receipt[key], False)
            for key, wrong in [('app_renderer', 'skia'), ('disable_queue_submit_with_commands', True),
                               ('original_run_number', 290), ('surface_test_apk', None)]:
                changed = copy.copy(args); setattr(changed, key, wrong)
                with self.subTest(key=key), self.assertRaises(ValueError):
                    probe.Diagnostic(changed)

    def test_java_selector_uses_fixed_identity_and_generic_surface_path(self):
        source = (Path(__file__).resolve().parents[1] /
                  'android-qa/src/main/java/dev/aerove/lite/SurfacePixelDiagnosticTest.java').read_text()
        method = source.split('public void testOriginal328SurfaceWindowAndCompositor()', 1)[1].split('\n    }', 1)[0]
        self.assertIn('original328Probe = true', method)
        self.assertIn('testCaptureLateSurfaceAndCompositor()', method)
        self.assertNotIn('captureOriginal290', method)
        self.assertIn('if (original265Probe || original328Probe)', source)
        self.assertIn(probe.APK_SHA, source)
        self.assertIn(probe.APP['sourceRevision'], source)
        self.assertIn('Unexpected original290 identity argument', source)
        self.assertIn('Unexpected original265 identity argument', source)

    def test_baseline_failure_still_captures_surface_then_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = SimpleNamespace(original_run_number=328, app_renderer='default',
                                   disable_queue_submit_with_commands=False, surface_test_apk=root / 'runner.apk',
                                   surface_test_info=root / 'runner.json', sdk=root, output=root,
                                   runtime_directory=root, avd='test', source='a' * 40, run='999', attempt='1')
            value = probe.Diagnostic(args)
            calls = []
            def command(label, *unused, **options):
                if label == 'system-before-install':
                    raise ValueError('baseline PNG failed')
                output = {'owned-avd-name': b'test\nOK\n', 'owned-device-abi': b'x86_64',
                          'owned-device-users': b'UserInfo{0:Owner}',
                          'read-locale-en-US': b'[en-US]', 'launcher-start-W': b'Status: ok\n'}.get(label, b'')
                return output, dict(monotonicEnd=0)
            value.command = command
            value.adb_command = command
            value.original_artifact = lambda: {}
            value.start_scene_logcat = lambda: None
            value.failure_scene257 = lambda stage: calls.append('failure-log')
            value.installed_hash = lambda label: {}
            value.pid = lambda label: 123
            value.surface_capture = lambda: calls.append('three-layer-capture')
            with patch.dict(os.environ, {}, clear=True), patch.object(Path, 'is_file', return_value=True), \
                    patch.object(probe.base.socket, 'socket'), patch.object(probe.base.subprocess, 'Popen', return_value=Mock(pid=7)), \
                    patch.object(probe.base, 'verify_identity'), patch.object(probe.base, 'wait_ready'), \
                    patch.object(probe.base, 'guest_angle_evidence', return_value={}):
                with self.assertRaisesRegex(ValueError, 'baseline PNG failed'):
                    value.execute()
            self.assertEqual(['failure-log', 'three-layer-capture'], calls)
            self.assertEqual('system-before-install', value.receipt['baselineFailure']['stage'])
            self.assertFalse(value.receipt['accepted'])
            self.assertFalse(value.receipt['collectionComplete'])

    def test_historical_resilience_defaults_unchanged(self):
        value = object.__new__(probe.base.Diagnostic)
        value.original257 = False
        self.assertFalse(value.resilient_surface())
        self.assertEqual(('0.1.0-beta.28', '28'), value.app_version())
        value.original257 = True
        self.assertTrue(value.resilient_surface())
        self.assertEqual(('0.2.0-beta.1', '34'), value.app_version())


if __name__ == '__main__':
    unittest.main()
