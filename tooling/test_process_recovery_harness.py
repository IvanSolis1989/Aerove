"""Bounded controller regressions; no Flutter compilation or application launch."""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import test_rule_sql_process as rule
import test_profile_publication_process as profile

ROOT = Path(__file__).resolve().parents[1]


class HarnessTests(unittest.TestCase):
    def mock_pidfds(self):
        self.enterContext(patch.object(rule.os, 'pidfd_open', side_effect=lambda pid: pid + 1000))
        return self.enterContext(patch.object(rule.os, 'close'))

    def test_partial_marker_never_becomes_visible(self):
        # IOOverrides pauses the production helper after writing a partial sibling.
        subprocess.run([os.environ.get('DART', 'dart'),
                        str(ROOT / 'tooling/process_marker_test.dart')],
                       check=True, timeout=30)
        for name, marker in [('rule_sql', 'worker-ready.json'),
                             ('profile_publication', 'ready.json')]:
            source = (ROOT / f'apps/aerove/test/helpers/{name}_process_worker.dart').read_text()
            self.assertIn(f"File('${{home.path}}/{marker}').writeAsStringAtomically(", source)

    def test_both_controllers_retire_launcher_after_partial_marker_error(self):
        for harness, marker in [(rule, 'worker-ready.json'), (profile, 'ready.json')]:
            with self.subTest(harness=harness.__name__), tempfile.TemporaryDirectory() as temp:
                home = Path(temp)
                child = Mock(pid=100)
                child.poll.return_value = None
                def launch(*args, **kwargs):
                    (home / marker).touch()
                    return child
                with patch.object(harness.subprocess, 'Popen', side_effect=launch), \
                        patch.object(harness, 'proc', return_value=(1, 'birth')), \
                        patch.object(harness, 'retire_owned_launcher', return_value={'errors': []}) as retire:
                    with self.assertRaises(json.JSONDecodeError):
                        if harness is rule:
                            case = {'workers': []}
                            harness.worker('flutter', ROOT, home, 'publish', 'prepared',
                                           evidence=Mock(), case_record=case)
                            self.fail('partial JSON accepted')
                        else:
                            harness.worker(SimpleNamespace(flutter='flutter', app=ROOT),
                                           home, 'publish', 'prepared')
                    retire.assert_called_once_with(child, 'birth')

    def test_both_controllers_retire_launcher_after_completion_timeout(self):
        for harness in (rule, profile):
            with self.subTest(harness=harness.__name__), tempfile.TemporaryDirectory() as temp:
                home = Path(temp)
                child = Mock(pid=100)
                child.wait.side_effect = subprocess.TimeoutExpired('fixture', 180)
                with patch.object(harness.subprocess, 'Popen', return_value=child), \
                        patch.object(harness, 'proc', return_value=(1, 'birth')), \
                        patch.object(harness, 'retire_owned_launcher', return_value={'errors': []}) as retire:
                    with self.assertRaises(subprocess.TimeoutExpired):
                        if harness is rule:
                            harness.worker('flutter', ROOT, home, 'recover',
                                           evidence=Mock(), case_record={'workers': []})
                        else:
                            harness.worker(SimpleNamespace(flutter='flutter', app=ROOT), home, 'recover')
                    retire.assert_called_once_with(child, 'birth')

    def test_profile_archive_failure_preserves_primary_exception(self):
        with tempfile.TemporaryDirectory() as temp:
            home = Path(temp)
            child = Mock(pid=100)
            def launch(*args, **kwargs):
                (home / 'ready.json').touch()
                return child
            with patch.object(profile.subprocess, 'Popen', side_effect=launch), \
                    patch.object(profile, 'proc', return_value=(1, 'birth')), \
                    patch.object(profile, 'retire_owned_launcher', return_value={'errors': []}), \
                    patch.object(Path, 'write_text', side_effect=OSError('disk full')), \
                    patch('builtins.print') as printed:
                with self.assertRaises(json.JSONDecodeError):
                    profile.worker(SimpleNamespace(flutter='flutter', app=ROOT), home, 'publish', 'prepared')
                self.assertIn('Additional failure evidence error:', printed.call_args.args)

    def test_reaped_launcher_keeps_cleanup_unconfirmed(self):
        child = Mock(pid=100)
        child.poll.return_value = 0
        with patch.object(rule, 'proc', side_effect=FileNotFoundError()), \
                patch.object(rule.signal, 'pidfd_send_signal') as kill:
            result = rule.retire_owned_launcher(child, 'birth')
        kill.assert_not_called()
        self.assertFalse(result['descendant_snapshot_complete'])
        self.assertFalse(result['recorded_processes_retired'])
        self.assertTrue(result['errors'])

    def test_cleanup_signals_only_birth_and_lineage_qualified_descendants(self):
        self.mock_pidfds()
        child = Mock(pid=100, returncode=-9)
        births = {100: (1, 'launcher'), 101: (100, 'owned'), 102: (1, 'unrelated')}
        with patch.object(rule.Path, 'iterdir', return_value=[Path('/proc/101'), Path('/proc/102')]), \
                patch.object(rule, 'proc', side_effect=lambda pid: births[pid]), \
                patch.object(rule.signal, 'pidfd_send_signal') as kill, \
                patch.object(rule, 'os_absence_proof', return_value={'absent': True}):
            result = rule.retire_owned_launcher(child, 'launcher')
        kill.assert_called_once_with(1101, signal.SIGKILL)
        child.wait.assert_called_once_with(timeout=10)
        self.assertEqual(result['errors'], [])

    def test_cleanup_orders_nested_children_before_their_parents(self):
        self.mock_pidfds()
        child = Mock(pid=100, returncode=0)
        births = {100: (1, 'launcher'), 101: (100, 'parent'), 102: (101, 'leaf')}
        def killed(handle, _signal):
            pid = handle - 1000
            # Once a parent is killed, its children are no longer descendants.
            for target, (parent, birth) in list(births.items()):
                if parent == pid:
                    births[target] = (1, birth)
        with patch.object(rule.Path, 'iterdir', return_value=[Path('/proc/101'), Path('/proc/102')]), \
                patch.object(rule, 'proc', side_effect=lambda pid: births[pid]), \
                patch.object(rule.signal, 'pidfd_send_signal', side_effect=killed) as kill, \
                patch.object(rule, 'os_absence_proof', return_value={'absent': True}):
            result = rule.retire_owned_launcher(child, 'launcher')
        self.assertEqual([call.args[0] for call in kill.call_args_list], [1102, 1101])
        self.assertEqual(result['errors'], [])

    def test_real_owned_child_is_reaped_after_failure_cleanup(self):
        # The synthetic launcher waits for its child, just as Flutter reaps its VM.
        script = ("import subprocess,sys; "
                  "p=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)']); "
                  "print(p.pid,flush=True); p.wait()")
        child = subprocess.Popen([sys.executable, '-c', script], stdout=subprocess.PIPE, text=True)
        target = None
        try:
            birth = rule.proc(child.pid)[1]
            target = int(child.stdout.readline())
            result = rule.retire_owned_launcher(child, birth)
            self.assertEqual(result['errors'], [])
            self.assertIsNotNone(child.poll())
            self.assertFalse(Path(f'/proc/{target}').exists())
        finally:
            # On a regression, clean up only this test's still-owned subprocesses.
            if child.poll() is None:
                rule.retire_owned_launcher(child, birth)
            child.stdout.close()

    def test_cleanup_never_signals_reused_launcher(self):
        child = Mock(pid=100)
        with patch.object(rule, 'proc', return_value=(1, 'reused')), \
                patch.object(rule.signal, 'pidfd_send_signal') as kill:
            result = rule.retire_owned_launcher(child, 'original')
        kill.assert_not_called()
        child.terminate.assert_not_called()
        self.assertTrue(result['errors'])

    def test_cleanup_rechecks_descendant_birth_before_signal(self):
        self.mock_pidfds()
        child = Mock(pid=100, returncode=0)
        calls = 0
        def process(pid):
            nonlocal calls
            if pid == 100:
                return (1, 'launcher')
            calls += 1
            return (100, 'old' if calls <= 3 else 'reused')
        with patch.object(rule.Path, 'iterdir', return_value=[Path('/proc/101')]), \
                patch.object(rule, 'proc', side_effect=process), \
                patch.object(rule.signal, 'pidfd_send_signal') as kill, \
                patch.object(rule, 'os_absence_proof', side_effect=AssertionError('still present')):
            result = rule.retire_owned_launcher(child, 'launcher')
        kill.assert_not_called()
        self.assertTrue(result['errors'])

    def test_pid_reuse_after_qualification_signals_original_handle(self):
        closed = self.mock_pidfds()
        births = {100: (1, 'launcher'), 101: (100, 'original')}
        def qualified(*_):
            births[101] = (1, 'replacement')
            return True
        with patch.object(rule, 'proc', side_effect=lambda pid: births[pid]), \
                patch.object(rule, 'descendant', side_effect=qualified), \
                patch.object(rule.signal, 'pidfd_send_signal') as signal_fd, \
                patch.object(rule.os, 'kill') as numeric_signal:
            rule.kill_owned_process(101, 'original', 100, 'launcher')
        signal_fd.assert_called_once_with(1101, signal.SIGKILL)
        numeric_signal.assert_not_called()
        closed.assert_called_once_with(1101)
        self.assertEqual(births[101][1], 'replacement')

    def test_pidfd_closes_without_signal_when_identity_changed(self):
        closed = self.mock_pidfds()
        with patch.object(rule, 'proc', side_effect=lambda pid: (1, 'launcher' if pid == 100 else 'reused')), \
                patch.object(rule.signal, 'pidfd_send_signal') as signal_fd:
            with self.assertRaises(AssertionError):
                rule.kill_owned_process(101, 'original', 100, 'launcher')
        signal_fd.assert_not_called()
        closed.assert_called_once_with(1101)

    def test_unavailable_pidfd_fails_closed(self):
        with patch.object(rule.os, 'pidfd_open', side_effect=OSError('unsupported')), \
                patch.object(rule.os, 'kill') as numeric_signal, \
                patch.object(rule.signal, 'pidfd_send_signal') as signal_fd:
            with self.assertRaises(OSError):
                rule.kill_owned_process(101, 'original', 100, 'launcher')
        numeric_signal.assert_not_called()
        signal_fd.assert_not_called()

    def test_unresponsive_launcher_has_bounded_escalation(self):
        child = Mock(pid=100, returncode=-9)
        child.wait.side_effect = [subprocess.TimeoutExpired('fixture', 10),
                                  subprocess.TimeoutExpired('fixture', 5), -9]
        with patch.object(rule.Path, 'iterdir', return_value=[]), \
                patch.object(rule, 'proc', return_value=(1, 'launcher')):
            result = rule.retire_owned_launcher(child, 'launcher')
        child.terminate.assert_called_once()
        child.kill.assert_called_once()
        self.assertEqual([call.kwargs['timeout'] for call in child.wait.call_args_list], [10, 5, 5])
        self.assertEqual(result['errors'], [])

    def test_ci_runs_regressions_before_real_rule_gate(self):
        workflow = (ROOT / '.github/workflows/aerove-lite.yaml').read_text()
        step = workflow.split('    - name: Execute owned Linux process termination and rule journal recovery\n')[1].split('    - name:')[0]
        self.assertIn("steps.host_collection_ready.outcome == 'success'", step)
        self.assertIn('set -euo pipefail', step)
        self.assertLess(step.index('python3 tooling/test_process_recovery_harness.py'),
                        step.index('python3 tooling/test_rule_sql_process.py'))
        self.assertIn('timeout-minutes: 20', step)


if __name__ == '__main__':
    unittest.main()
