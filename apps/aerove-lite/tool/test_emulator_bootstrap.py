"""Bootstrap protocol fixtures: no Android claims."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch
import emulator_bootstrap as bootstrap

READY = """KeyguardServiceDelegate
  showing=false
  inputRestricted=false
  systemIsReady=true
  bootCompleted=true
  screenState=SCREEN_STATE_ON
  interactiveState=INTERACTIVE_STATE_AWAKE
Looper state:
"""


class BootstrapTests(unittest.TestCase):
    def exercise(self, responses, clock=None, cancel=None):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.commands = []
        emulator = Mock(pid=123)
        emulator.poll.return_value = None
        receipt = dict(ownerPid=456)
        def run(arguments, **options):
            self.commands.append((arguments, options))
            result = next(responses)
            if isinstance(result, BaseException):
                raise result
            return subprocess.CompletedProcess(arguments, *result)
        with patch.object(bootstrap.subprocess, 'run', side_effect=run), \
                patch.object(bootstrap.time, 'sleep'), \
                patch.object(bootstrap.time, 'monotonic', side_effect=clock) if clock else patch.dict({}, {}):
            bootstrap.wait_ready(emulator, self.directory, receipt, cancel or (lambda receipt: None))
        return json.loads((self.directory / 'BOOTSTRAP.json').read_text())

    def report(self):
        return json.loads((self.directory / 'BOOTSTRAP.json').read_text())

    def test_direct_window_dismiss_requires_actual_awake_unlocked_readback(self):
        result = self.exercise(iter([(0, '1', ''), (0, '', ''),
                                     (0, READY.replace('showing=false', 'showing=true'), ''),
                                     (0, READY, '')]))
        self.assertTrue(result['ready'])
        self.assertEqual(['getprop', 'wm', 'dumpsys', 'dumpsys'],
                         [command[0][4] for command in self.commands])
        self.assertFalse(any('input' in command[0] for command in self.commands))
        self.assertTrue(all(command[1]['timeout'] <= 10 for command in self.commands))

    def test_boot_transient_timeout_retains_evidence_and_can_become_ready(self):
        result = self.exercise(iter([subprocess.TimeoutExpired(['adb'], 10),
                                     (0, '1', ''), (0, '', ''), (0, READY, '')]))
        self.assertTrue(result['ready'])
        self.assertTrue(result['commands'][0]['timedOut'])

    def test_window_timeout_is_not_swallowed_or_retried(self):
        with self.assertRaises(subprocess.TimeoutExpired):
            self.exercise(iter([(0, '1', ''), subprocess.TimeoutExpired(['wm'], 10),
                                (0, READY, ''), (0, 'Input Dispatcher State: fixture', ''),
                                (0, 'fixture guest log', '')]))
        self.assertFalse(self.report()['ready'])
        self.assertEqual('TimeoutExpired', self.report()['errorType'])
        self.assertEqual(2, len(self.report()['commands']))
        self.assertEqual(3, len(self.report()['failureDiagnostics']))
        self.assertEqual('fixture guest log', self.report()['failureDiagnostics'][-1]['stdout'])
        self.assertTrue(all(options['timeout'] <= 5 for _, options in self.commands[2:]))

    def test_dismiss_failure_cannot_start_acceptance(self):
        with self.assertRaises(RuntimeError):
            self.exercise(iter([(0, '1', ''), (1, '', 'permission denied'),
                                (0, READY, ''), (0, 'fixture input', ''), (0, 'fixture log', '')]))
        self.assertFalse(self.report()['ready'])
        self.assertEqual('permission denied', self.report()['commands'][-1]['stderr'])

    def test_missing_or_contradictory_keyguard_fields_fail_closed(self):
        self.assertFalse(bootstrap.keyguard_ready(''))
        for field in ('showing=false', 'inputRestricted=false', 'systemIsReady=true',
                      'bootCompleted=true', 'screenState=SCREEN_STATE_ON',
                      'interactiveState=INTERACTIVE_STATE_AWAKE'):
            self.assertFalse(bootstrap.keyguard_ready(READY.replace(field, '')))
        self.assertTrue(bootstrap.keyguard_ready(READY + 'showing=true\n'))  # Unrelated dump section.
        self.assertFalse(bootstrap.keyguard_ready(READY.replace('Looper state:', '  showing=true\nLooper state:')))
        self.assertFalse(bootstrap.keyguard_ready(READY + READY))

    def test_original_600s_budget_not_reset_after_boot(self):
        ticks = iter([0, 0, 0, 1, 1, 1, 2, 2, 2, 601, 601, 601])
        with self.assertRaises(TimeoutError):
            self.exercise(iter([(0, '1', ''), (0, '', ''), (0, '', '')]),
                          clock=lambda: next(ticks, 601))
        self.assertFalse(self.report()['ready'])
        self.assertEqual(600, self.report()['budgetSeconds'])

    def test_cancellation_remains_failure_with_receipt(self):
        def cancelled(receipt):
            raise InterruptedError('controlled cancellation')
        with self.assertRaises(InterruptedError):
            self.exercise(iter([]), cancel=cancelled)
        self.assertEqual('InterruptedError', self.report()['errorType'])
        self.assertEqual([], self.report()['commands'])


if __name__ == '__main__':
    unittest.main()

