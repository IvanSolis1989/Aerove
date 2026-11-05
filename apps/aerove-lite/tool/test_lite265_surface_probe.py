import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import emulator_current
import lite265_recheck
import lite265_surface_probe as probe


class SurfaceProbeBoundaryTests(unittest.TestCase):
    def test_new_harness_requires_its_actual_bytes_and_current_executor_without_app_compilation(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / 'harness.apk'
            apk.write_bytes(b'independently-built-test-fixture')
            source = Path(probe.__file__).resolve().parents[1] / 'android-qa/src/main/java/dev/aerove/lite/SurfacePixelDiagnosticTest.java'
            env = dict(GITHUB_SHA='new-harness', GITHUB_RUN_ID='new-run', GITHUB_RUN_ATTEMPT='2')
            info = dict(sourceRevision='new-harness', sourceTree='new-tree', workflowRun='new-run', runAttempt=2,
                        apkSha256=probe.sha(apk), signerSha256=probe.SIGNER, appCompiled=False, harnessCompiled=True,
                        diagnosticOnly=True, sourceSha256=probe.sha(source))
            with patch.dict(os.environ, env, clear=True), patch.object(probe.ci_artifacts, 'identity', return_value=dict(sourceTree='new-tree')):
                probe.harness_binding(apk, info)
                for changed in (dict(appCompiled=True), dict(sourceRevision='old'), dict(signerSha256='wrong'),
                                dict(sourceSha256='wrong'), dict(harnessCompiled=False), dict(diagnosticOnly=False)):
                    with self.subTest(changed=changed), self.assertRaises(ValueError):
                        probe.harness_binding(apk, dict(info, **changed))
                apk.write_bytes(b'changed')
                with self.assertRaises(ValueError):
                    probe.harness_binding(apk, info)

    def test_fixed_probe_routes_only_representative_phase_and_keeps_original_input_paths(self):
        inputs = dict(apk=Path('original-app.apk'), test_apk=Path('original-driver.apk'), build_info=Path('original-BUILD_INFO.json'))
        env = dict(AEROVE_LITE265_SURFACE_PROBE='1', ANDROID_HOME='sdk',
                   AEROVE_LITE265_SURFACE_HARNESS='new-harness.apk', AEROVE_LITE265_SURFACE_HARNESS_INFO='HARNESS.json')
        with patch.dict(os.environ, env, clear=True), patch.object(lite265_recheck, 'enabled', return_value=True):
            command = emulator_current.phase_acceptance_command('flutter-ui', Path('evidence'), inputs)
            self.assertIn(str(inputs['apk']), command)
            self.assertIn(str(inputs['build_info']), command)
            self.assertIn('new-harness.apk', command)
            self.assertTrue(command[1].endswith('lite265_surface_probe.py'))
            for phase in ('flutter-full', 'flutter-platform', 'matrix'):
                with self.subTest(phase=phase), self.assertRaises(ValueError):
                    emulator_current.phase_acceptance_command(phase, Path('evidence'), inputs)

    def test_changed_original_source_checkpoint_is_rejected_before_app_input_read(self):
        for values in (['changed', lite265_recheck.IDENTITY['sourceTree'], ''],
                       [lite265_recheck.IDENTITY['sourceRevision'], 'changed', ''],
                       [lite265_recheck.IDENTITY['sourceRevision'], lite265_recheck.IDENTITY['sourceTree'], ' M owned-source']):
            with self.subTest(values=values), patch.dict(os.environ, {'AEROVE_LITE265_SURFACE_PROBE': '1'}, clear=True), \
                    patch.object(probe, 'enabled', return_value=True), \
                    patch.object(probe.subprocess, 'check_output', side_effect=values), \
                    self.assertRaisesRegex(ValueError, 'source checkpoint differs'):
                probe.original_inputs(Path('not-read'), {})


if __name__ == '__main__':
    unittest.main()
