"""Real harmless child processes test ownership; not Android/renderer evidence."""
import os
from pathlib import Path
import subprocess
import sys
import unittest
import tempfile
from unittest import mock

import emulator_qa79 as qa


def child(code):
    return subprocess.Popen([sys.executable, '-c', code], start_new_session=os.name == 'posix', stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


class OwnerTests(unittest.TestCase):
    def test_renderer_metadata_requires_actual_angle_and_swiftshader_startup_evidence(self):
        with self.assertRaisesRegex(ValueError, 'not confirmed'):
            qa.renderer_evidence('Graphics Adapter Android Emulator OpenGL ES Translator (Google SwiftShader)')
        output = 'Graphics Adapter ANGLE (Google SwiftShader)\nGraphics API Version OpenGL ES 3.1'
        self.assertEqual(output.splitlines(), qa.renderer_evidence(output))

    def test_emulator_environment_removes_authentication_only_from_child_copy(self):
        original = dict(GH_TOKEN='private', GITHUB_TOKEN='private', ACTIONS_RUNTIME_TOKEN='private',
                        ACTIONS_ID_TOKEN_REQUEST_TOKEN='private', SOME_SECRET='private',
                        GITHUB_SHA='harness', GITHUB_RUN_ID='run', PATH='bin', ANDROID_HOME='sdk')
        safe = qa.emulator_environment(original)
        self.assertEqual(dict(GITHUB_SHA='harness', GITHUB_RUN_ID='run', PATH='bin', ANDROID_HOME='sdk'), safe)
        self.assertIn('GH_TOKEN', original)

    def test_crash_export_rejects_wrong_pid_before_reading_database(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            (directory / 'emulator.stdout.log').write_text('Storing crashdata in: /tmp/android-runner/emu-crash-37.2.12.db, detection is enabled for process: 17')
            with mock.patch.object(qa, 'crash_database', return_value=Path('/tmp/android-runner/emu-crash-37.2.12.db')), mock.patch.object(qa, 'crash_inventory') as inventory:
                self.assertIn('not-copied', qa.preserve_owned_crash(directory, 18, {})['status'])
                inventory.assert_not_called()

    def test_crash_export_copies_only_new_owned_scoped_files_with_hash(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp) / 'evidence'
            directory.mkdir()
            root = Path(temp) / 'db'
            (root / 'pending').mkdir(parents=True)
            (root / 'pending/new.dmp').write_bytes(b'owned crash dump')
            (root / 'pending/old.dmp').write_bytes(b'prior dump')
            (directory / 'emulator.stdout.log').write_text(f'Storing crashdata in: {root}, detection is enabled for process: 18')
            with mock.patch.object(qa, 'crash_database', return_value=root), mock.patch.object(qa.os, 'getuid', create=True, return_value=root.stat().st_uid):
                before = qa.crash_inventory()
                before.pop('pending/new.dmp')
                result = qa.preserve_owned_crash(directory, 18, before)
            self.assertEqual(['pending/new.dmp'], [item['path'] for item in result['files']])
            self.assertEqual(qa.sha(root / 'pending/new.dmp'), result['files'][0]['sha256'])
            self.assertFalse((directory / 'owned-crashpad/pending/old.dmp').exists())

    def test_live_owned_child_records_real_termination_and_preserves_success(self):
        emulator = child('import time;time.sleep(20)')
        receipt = dict(unexpectedEmulatorExit=False)
        try:
            self.assertEqual(0, qa.finish_emulator(emulator, receipt, 0))
            self.assertTrue(receipt['cleanupRequested'])
            self.assertFalse(receipt['unexpectedEmulatorExit'])
            self.assertFalse(receipt['cleanup']['exitObservedBeforeTermination'])
            self.assertIsNotNone(receipt['emulatorReturnCode'])
        finally:
            qa.stop_owned(emulator)

    def test_natural_exit_between_cleanup_polls_is_unexpected_and_fails(self):
        emulator = mock.Mock(returncode=7)
        emulator.poll.side_effect = [None, 7]
        receipt = dict(cleanupRequested=False, unexpectedEmulatorExit=False)
        self.assertEqual(1, qa.finish_emulator(emulator, receipt, 0))
        self.assertEqual(2, emulator.poll.call_count)
        emulator.terminate.assert_not_called()
        emulator.wait.assert_not_called()
        self.assertFalse(receipt['cleanupRequested'])
        self.assertTrue(receipt['unexpectedEmulatorExit'])
        self.assertTrue(receipt['cleanup']['exitObservedBeforeTermination'])
        self.assertEqual(7, receipt['emulatorReturnCode'])
        self.assertIsNone(receipt['emulatorSignal'])

    def test_disappeared_before_termination_syscall_does_not_claim_cleanup(self):
        emulator = mock.Mock(returncode=9)
        emulator.poll.side_effect = [None, None]
        emulator.terminate.side_effect = ProcessLookupError
        receipt = {}
        with mock.patch.object(qa.os, 'name', 'nt'):
            self.assertEqual(1, qa.finish_emulator(emulator, receipt, 0))
        emulator.terminate.assert_called_once()
        emulator.wait.assert_called_once_with(timeout=30)
        self.assertFalse(receipt['cleanupRequested'])
        self.assertTrue(receipt['unexpectedEmulatorExit'])
        self.assertEqual(9, receipt['emulatorReturnCode'])

    def test_unexpected_child_exit_is_not_a_success_and_joins_owned_test_child(self):
        emulator = child('import time;time.sleep(.05);raise SystemExit(7)')
        acceptance = child('import time;time.sleep(20)')
        unrelated = child('import time;time.sleep(20)')
        try:
            receipt = {}
            self.assertEqual(1, qa.monitor(emulator, acceptance, receipt))
            self.assertEqual(7, receipt['emulatorReturnCode'])
            self.assertTrue(receipt['unexpectedEmulatorExit'])
            self.assertIsNotNone(acceptance.returncode)
            self.assertIsNone(unrelated.poll())
        finally:
            for process in [emulator, acceptance, unrelated]:
                qa.stop_owned(process)

    def test_original_acceptance_failure_is_propagated_while_emulator_is_live(self):
        emulator = child('import time;time.sleep(20)')
        acceptance = child('raise SystemExit(23)')
        try:
            self.assertEqual(23, qa.monitor(emulator, acceptance, {}))
            self.assertIsNone(emulator.poll())
        finally:
            qa.stop_owned(emulator)

    def test_passing_acceptance_does_not_stop_emulator_before_explicit_cleanup(self):
        emulator = child('import time;time.sleep(20)')
        acceptance = child('pass')
        try:
            self.assertEqual(0, qa.monitor(emulator, acceptance, {}))
            self.assertIsNone(emulator.poll())
        finally:
            qa.stop_owned(emulator)

    def test_wrong_original_artifact_api_digest_fails_before_download(self):
        with mock.patch.object(qa.release, '_gh_json', return_value={'digest': 'sha256:wrong'}), mock.patch.object(qa.release, '_gh_binary') as download:
            with self.assertRaisesRegex(ValueError, 'API identity'):
                qa.download_original('runtime', Path('not-created'))
            download.assert_not_called()


if __name__ == '__main__':
    unittest.main()
