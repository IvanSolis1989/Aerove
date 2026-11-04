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



class GuestLogcatTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.now = 0
        self.calls = []
        self.emulator = Mock(pid=123)
        self.emulator.poll.return_value = None
        self.receipt = dict(ownerPid=456, interruptSignals=[])

    def exercise(self, respond, cancel=None, *, debug_guest_vulkan=False):
        def run(arguments, **options):
            self.calls.append((arguments, options, self.now))
            return respond(arguments, options)
        def sleep(seconds):
            self.now += seconds
        def check(receipt):
            if receipt['interruptSignals']:
                raise InterruptedError('controlled guest capture cancellation')
        with patch.object(bootstrap.subprocess, 'run', side_effect=run), \
                patch.object(bootstrap.time, 'monotonic', side_effect=lambda: self.now), \
                patch.object(bootstrap.time, 'sleep', side_effect=sleep):
            bootstrap.wait_ready(self.emulator, self.directory, self.receipt, cancel or check,
                                 capture_guest_logcat=True, debug_guest_vulkan=debug_guest_vulkan)

    def report(self):
        return json.loads((self.directory / 'BOOTSTRAP.json').read_text())

    def result(self, arguments, stdout='', code=0, stderr=''):
        return subprocess.CompletedProcess(arguments, code, stdout, stderr)

    def test_initial_and_deadline_dumps_are_each_once_with_raw_failure_retained(self):
        def respond(arguments, options):
            if arguments[4] == 'logcat':
                return self.result(arguments, 'raw guest log\n', 7, 'guest read failed\n')
            if self.now >= 590:
                self.now = 600  # A blocking final probe must not pre-empt the tail snapshot.
            return self.result(arguments, '\n')
        with self.assertRaisesRegex(TimeoutError, '600-second'):
            self.exercise(respond)
        report = self.report()
        self.assertFalse(report['ready'])
        self.assertEqual('TimeoutError', report['errorType'])
        self.assertEqual(['initial', 'before-deadline'], [row['window'] for row in report['guestLogcat']])
        self.assertEqual(0, report['guestLogcat'][0]['elapsedStart'])
        self.assertGreaterEqual(report['guestLogcat'][1]['elapsedStart'], 590)
        for row in report['guestLogcat']:
            self.assertEqual(7, row['returnCode'])
            self.assertEqual('raw guest log\n', row['stdout'])
            self.assertEqual('guest read failed\n', row['stderr'])
        captures = [(args, options, at) for args, options, at in self.calls if args[4] == 'logcat']
        self.assertEqual(2, len(captures))
        for args, options, at in captures:
            self.assertEqual(['adb', '-s', 'emulator-5554', 'shell', 'logcat', '-b', 'main',
                              '-b', 'system', '-b', 'crash', '-d', '-v', 'threadtime'], args)
            self.assertLessEqual(options['timeout'], min(3, 600 - at))
        self.assertEqual([], report['failureDiagnostics'])
        self.assertEqual(600, report['elapsedSeconds'])

    def test_success_has_only_initial_capture_and_preserves_ready_checks(self):
        def respond(arguments, options):
            if arguments[4] == 'getprop':
                return self.result(arguments, '1\n')
            if arguments[4] == 'dumpsys':
                return self.result(arguments, READY)
            return self.result(arguments)
        self.exercise(respond)
        self.assertTrue(self.report()['ready'])
        self.assertEqual(['initial'], [row['window'] for row in self.report()['guestLogcat']])
        self.assertEqual(['getprop', 'logcat', 'wm', 'dumpsys'], [args[4] for args, _, _ in self.calls])

    def test_partial_timeout_is_diagnostic_and_cannot_extend_original_deadline(self):
        probes = 0
        def respond(arguments, options):
            nonlocal probes
            if arguments[4] == 'getprop':
                probes += 1
                if probes == 2:
                    self.now = 598
                return self.result(arguments, '\n')
            self.now += options['timeout']
            raise subprocess.TimeoutExpired(arguments, options['timeout'], output=b'partial\n', stderr=b'late\n')
        with self.assertRaisesRegex(TimeoutError, '600-second'):
            self.exercise(respond)
        report = self.report()
        self.assertEqual('TimeoutError', report['errorType'])
        self.assertEqual([3, 2], [options['timeout'] for args, options, _ in self.calls if args[4] == 'logcat'])
        self.assertEqual(600, report['elapsedSeconds'])
        self.assertTrue(all(row['timedOut'] for row in report['guestLogcat']))
        self.assertEqual(['partial\n', 'partial\n'], [row['stdout'] for row in report['guestLogcat']])
        self.assertEqual(['late\n', 'late\n'], [row['stderr'] for row in report['guestLogcat']])
        self.assertFalse(report['ready'])
        self.assertEqual([], report['failureDiagnostics'])

    def test_cancellation_during_capture_stops_before_more_boot_commands(self):
        def respond(arguments, options):
            if arguments[4] == 'logcat':
                self.receipt['interruptSignals'].append(15)
            return self.result(arguments, '\n')
        with self.assertRaisesRegex(InterruptedError, 'controlled guest capture'):
            self.exercise(respond)
        self.assertEqual(['getprop', 'logcat'], [args[4] for args, _, _ in self.calls])
        self.assertEqual('InterruptedError', self.report()['errorType'])
        self.assertFalse(self.report()['ready'])
        self.assertEqual([], self.report()['failureDiagnostics'])

    def test_emulator_exit_during_capture_stops_without_more_diagnostics(self):
        def respond(arguments, options):
            if arguments[4] == 'logcat':
                self.emulator.poll.return_value = -4
            return self.result(arguments, '\n')
        with self.assertRaisesRegex(RuntimeError, 'Owned emulator exited during bootstrap guest logcat'):
            self.exercise(respond)
        self.assertEqual(['getprop', 'logcat'], [args[4] for args, _, _ in self.calls])
        self.assertEqual('RuntimeError', self.report()['errorType'])
        self.assertEqual([], self.report()['failureDiagnostics'])

    def test_capture_oserror_does_not_replace_original_window_timeout(self):
        def respond(arguments, options):
            if arguments[4] == 'getprop':
                return self.result(arguments, '1\n')
            if arguments[4:6] == ['logcat', '-b']:
                raise OSError('controlled logcat spawn failure')
            if arguments[4] == 'wm':
                raise subprocess.TimeoutExpired(arguments, options['timeout'])
            return self.result(arguments)
        with self.assertRaises(subprocess.TimeoutExpired):
            self.exercise(respond)
        report = self.report()
        self.assertEqual('TimeoutExpired', report['errorType'])
        self.assertEqual('OSError', report['guestLogcat'][0]['errorType'])
        self.assertIn('controlled logcat spawn failure', report['guestLogcat'][0]['error'])
        self.assertFalse(report['ready'])
        self.assertEqual(3, len(report['failureDiagnostics']))

    def test_callback_property_setup_follows_first_successful_shell_probe_once(self):
        probes = 0
        def respond(arguments, options):
            nonlocal probes
            command = arguments[4:]
            if command == ['getprop', 'sys.boot_completed']:
                probes += 1
                return self.result(arguments, '1' if probes == 3 else '', code=1 if probes == 1 else 0)
            if command == ['getprop', 'ro.debuggable']:
                return self.result(arguments, '1\n')
            if command == ['getprop', 'debug.vulkan.enable_callback']:
                return self.result(arguments, 'true\n')
            if command == ['dumpsys', 'window', 'policy']:
                return self.result(arguments, READY)
            return self.result(arguments)
        self.exercise(respond, debug_guest_vulkan=True)
        self.assertTrue(self.report()['ready'])
        self.assertEqual([
            ['getprop', 'sys.boot_completed'], ['getprop', 'sys.boot_completed'],
            ['getprop', 'ro.debuggable'], ['setprop', 'debug.vulkan.enable_callback', 'true'],
            ['getprop', 'debug.vulkan.enable_callback'],
            ['logcat', '-b', 'main', '-b', 'system', '-b', 'crash', '-d', '-v', 'threadtime'],
            ['getprop', 'sys.boot_completed'], ['wm', 'dismiss-keyguard'], ['dumpsys', 'window', 'policy'],
        ], [args[4:] for args, _, _ in self.calls])
        self.assertEqual(['initial'], [row['window'] for row in self.report()['guestLogcat']])
        self.assertTrue(self.report()['guestVulkanDebug']['propertyReadbackVerified'])
        self.assertNotIn('callbackInstalled', self.report()['guestVulkanDebug'])
        self.assertTrue(all(options['timeout'] <= min(10, 600 - at) for _, options, at in self.calls))

    def test_callback_unsupported_or_failed_property_keeps_original_command_evidence(self):
        for debuggable, readback, set_code in [('0', 'true', 0), ('1', 'false', 0), ('1', 'true', 7)]:
            with self.subTest(debuggable=debuggable, readback=readback, set_code=set_code):
                self.calls.clear()
                def respond(arguments, options):
                    command = arguments[4:]
                    if command == ['getprop', 'sys.boot_completed']:
                        return self.result(arguments, '')
                    if command == ['getprop', 'ro.debuggable']:
                        return self.result(arguments, debuggable)
                    if command == ['setprop', 'debug.vulkan.enable_callback', 'true']:
                        return self.result(arguments, code=set_code, stderr='fixture setprop denied' if set_code else '')
                    if command == ['getprop', 'debug.vulkan.enable_callback']:
                        return self.result(arguments, readback)
                    return self.result(arguments)
                with self.assertRaises(RuntimeError if set_code else ValueError):
                    self.exercise(respond, debug_guest_vulkan=True)
                report = self.report()
                self.assertFalse(report['ready'])
                self.assertFalse(report['guestVulkanDebug']['propertyReadbackVerified'])
                self.assertNotIn('wm', [args[4] for args, _, _ in self.calls])
                self.assertLessEqual(sum(args[4] == 'setprop' for args, _, _ in self.calls), 1)
                if set_code:
                    self.assertEqual(7, report['commands'][-1]['returnCode'])
                    self.assertEqual('fixture setprop denied', report['commands'][-1]['stderr'])

    def test_callback_cancellation_after_setprop_sends_no_readback_or_cleanup_commands(self):
        def respond(arguments, options):
            if arguments[4] == 'setprop':
                self.receipt['interruptSignals'].append(15)
            return self.result(arguments, '1' if arguments[4:] == ['getprop', 'ro.debuggable'] else '')
        with self.assertRaises(InterruptedError):
            self.exercise(respond, debug_guest_vulkan=True)
        self.assertEqual([
            ['getprop', 'sys.boot_completed'], ['getprop', 'ro.debuggable'],
            ['setprop', 'debug.vulkan.enable_callback', 'true'],
        ], [args[4:] for args, _, _ in self.calls])
        self.assertEqual([], self.report()['failureDiagnostics'])
        self.assertFalse(self.report()['guestVulkanDebug']['propertyReadbackVerified'])

    def test_callback_setup_cannot_send_setprop_after_original_deadline(self):
        def respond(arguments, options):
            if arguments[4:] == ['getprop', 'ro.debuggable']:
                self.now = 600
                return self.result(arguments, '1')
            return self.result(arguments)
        with self.assertRaisesRegex(TimeoutError, '600-second'):
            self.exercise(respond, debug_guest_vulkan=True)
        self.assertEqual([['getprop', 'sys.boot_completed'], ['getprop', 'ro.debuggable']],
                         [args[4:] for args, _, _ in self.calls])
        self.assertEqual([], self.report()['failureDiagnostics'])
        self.assertEqual(600, self.report()['elapsedSeconds'])
        self.assertFalse(self.report()['ready'])


if __name__ == '__main__':
    unittest.main()

