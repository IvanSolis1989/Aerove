"""Real harmless child processes test ownership; not Android/renderer evidence."""
import os
from pathlib import Path
import subprocess
import sys
import unittest
from unittest import mock

import emulator_qa79 as qa


def child(code):
    return subprocess.Popen([sys.executable, '-c', code], start_new_session=os.name == 'posix', stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


class OwnerTests(unittest.TestCase):
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
