"""No device: cover the actual capture entry's argument construction and module compilation."""
import ast
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from android_acceptance import Acceptance, PACKAGE, TEST_PACKAGE, CLASS

def producer_workflow():
    # Same production-derived URL projection as Kotlin, not a fabricated numeric workflow or real CI receipt.
    fixture = Path(__file__).resolve().parents[1] / 'app/src/test/resources/qa-final-plan-build-info-workflow.json'
    return json.loads(fixture.read_text(encoding='utf-8'))['workflow']


class StopBeforeDevice(Exception):
    pass


class Entry(Acceptance):
    def __init__(self, output):
        self.args = SimpleNamespace(phase='flutter-ui', serial='emulator-5554', output=output)
        self.owner_verified = True
        self.installed = [PACKAGE, TEST_PACKAGE]
        self.receipt = {'ownerProofSha256': '9' * 64}
        self.calls = []

    def adb_command(self, label, *arguments, **options):
        self.calls.append((label, arguments, options))
        if label == 'final-plan-instrument':
            raise StopBeforeDevice('test-only boundary; no command executes')
        return b''


class FinalPlanCaptureEntryTest(unittest.TestCase):
    def test_actual_entry_passes_unique_core_identities_to_instrumentation(self):
        info = dict(source='a' * 40, tree='b' * 40, apk_sha256='c' * 64,
                    meow_overlay_sha256='d' * 64, meow_file_manifest_sha256='e' * 64,
                    workflow=producer_workflow(), run_attempt=1, signer_sha256='f' * 64)
        environment = dict(CI='true', GITHUB_EVENT_NAME='workflow_dispatch',
                           GITHUB_REPOSITORY='IvanSolis1989/Aerove', GITHUB_ACTOR='IvanSolis1989',
                           AEROVE_QA_FINAL_PLAN_SOURCE=info['source'], GITHUB_SHA=info['source'],
                           AEROVE_QA_FINAL_PLAN_REF='refs/heads/unit', GITHUB_REF='refs/heads/unit',
                           GITHUB_RUN_ID='456', GITHUB_RUN_ATTEMPT='2')
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, environment):
            owner = Entry(Path(temporary))
            with self.assertRaises(StopBeforeDevice):
                owner.final_plan_capture(info)
            self.assertEqual(['final-plan-instrument', 'final-plan-pull'], [call[0] for call in owner.calls])
            command = owner.calls[0][1]
            pairs = [(command[i + 1], command[i + 2]) for i, value in enumerate(command) if value == '-e']
            values = dict(pairs)
            self.assertEqual(len(pairs), len(values))
            self.assertEqual(CLASS + '#freshDefaultFinalPlanCapture', values['class'])
            self.assertEqual(info['meow_overlay_sha256'], values['meowOverlaySha256'])
            self.assertEqual(info['meow_file_manifest_sha256'], values['meowFileManifestSha256'])
            self.assertEqual(info['source'], values['source'])
            self.assertEqual(info['apk_sha256'], values['apkSha256'])
            self.assertEqual(info['signer_sha256'], values['qaFinalPlanSignerSha256'])
            self.assertEqual('https://github.com/IvanSolis1989/Aerove/actions/runs/123', values['workflow'])
            self.assertEqual('456', values['qaExecutorRun'])

    def test_original_base_args_retains_each_core_identity_once(self):
        with tempfile.TemporaryDirectory() as temporary:
            info = dict(source='a' * 40, tree='b' * 40, apk_sha256='c' * 64,
                        meow_overlay_sha256='d' * 64, meow_file_manifest_sha256='e' * 64,
                        workflow=producer_workflow(), run_attempt=1)
            args = Entry(Path(temporary)).base_args('consentNavigationTheme12', 'unit', info)
            self.assertEqual(1, args.count('meowOverlaySha256'))
            self.assertEqual(1, args.count('meowFileManifestSha256'))

    def test_semantic_compile_rejects_duplicate_keywords(self):
        source = Path(__file__).with_name('android_acceptance.py')
        compile(ast.parse(source.read_text(encoding='utf-8'), filename=str(source)), str(source), 'exec')


if __name__ == '__main__':
    unittest.main()
