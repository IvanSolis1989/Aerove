"""Linux cancellation and ownership regressions without Flutter compilation."""
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

import owned_process
import run_owned_step as subject

ROOT = Path(__file__).resolve().parents[1]


class OwnedStepTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='owned-step-')
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.receipt = self.home / 'receipt.json'
        self.script = self.home / 'step with spaces.sh'

    def launch(self, text, cwd=None):
        self.script.write_text(text)
        command = [sys.executable, str(ROOT / 'tooling/run_owned_step.py'),
                   '--evidence', str(self.receipt), str(self.script)]
        return subprocess.Popen(command, cwd=cwd or self.home,
                                stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)

    def wait_ready(self, predicate):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(.01)
        self.fail('synthetic process did not become ready')

    def finish(self, child):
        _, error = child.communicate(timeout=5)
        self.assertEqual(error, '')
        return json.loads(self.receipt.read_text())

    def test_preserves_cwd_quotes_bash_errexit_and_pipefail(self):
        for script, expected in [("printf '%s' \"$PWD\" > cwd; exit 7", 7),
                                 ('false | true\necho wrong > continued', 1),
                                 ('false\necho wrong > continued', 1), ('true', 0)]:
            with self.subTest(script=script):
                child = self.launch(script)
                receipt = self.finish(child)
                self.assertEqual(child.returncode, expected)
                self.assertEqual(receipt['shellExit'], expected)
                self.assertTrue(receipt['cleanup']['confirmed'])
                self.assertFalse((self.home / 'continued').exists())
        self.assertEqual((self.home / 'cwd').read_text(), str(self.home))

    def test_cancellation_reaps_nested_children_and_tee_but_not_unrelated(self):
        for signum in (signal.SIGINT, signal.SIGTERM):
            with self.subTest(signal=signum):
                marker = self.home / f'ready-{signum}'
                nested = self.home / 'nested.py'
                nested.write_text(
                    'import json,os,subprocess,sys\n'
                    'from pathlib import Path\n'
                    'child=subprocess.Popen([sys.executable,"-c","import time; time.sleep(60)"])\n'
                    f'Path({str(marker)!r}).write_text(json.dumps([os.getpid(),child.pid]))\n'
                    'print("ready",flush=True)\nchild.wait()\n')
                unrelated = subprocess.Popen(['sleep', '60'])
                child = self.launch(f'{shlex.quote(sys.executable)} {shlex.quote(str(nested))} | tee pipe.log\n')
                try:
                    self.wait_ready(lambda: marker.exists() and self.receipt.exists()
                                    and json.loads(self.receipt.read_text())['status'] == 'RUNNING')
                    initial = json.loads(self.receipt.read_text())
                    self.wait_ready(lambda: len(subject.session_members(initial['session'])) >= 4)
                    owned = subject.session_members(initial['session'])
                    started = time.monotonic()
                    child.send_signal(signum)
                    child.send_signal(signum)
                    receipt = self.finish(child)
                    self.assertLess(time.monotonic() - started, 3)
                    self.assertEqual(child.returncode, 128 + signum)
                    self.assertEqual(receipt['cancelSignal'], signum)
                    self.assertTrue(receipt['cleanup']['confirmed'], receipt)
                    self.assertTrue(all(not Path(f'/proc/{item["pid"]}').exists() for item in owned))
                    self.assertIsNone(unrelated.poll())
                finally:
                    if child.poll() is None:
                        child.terminate()
                        child.wait(timeout=5)
                    unrelated.terminate()
                    unrelated.wait(timeout=5)
                    if child.stderr is not None:
                        child.stderr.close()

    def test_second_signal_during_cleanup_preserves_first_cancellation(self):
        handlers = {}
        def install(sig, handler):
            if callable(handler):
                handlers[sig] = handler
            return signal.SIG_DFL
        def observe(_child):
            handlers[signal.SIGINT](signal.SIGINT, None)
            return None
        def cleanup(_child, _birth):
            handlers[signal.SIGTERM](signal.SIGTERM, None)
            return {'confirmed': True, 'errors': []}
        libc = Mock()
        libc.prctl.return_value = 0
        with patch.object(subject.signal, 'signal', side_effect=install), \
                patch.object(subject, 'signal_qualified_process'), \
                patch.object(subject.ctypes, 'CDLL', return_value=libc), \
                patch.object(subject.subprocess, 'Popen', return_value=Mock(pid=100)), \
                patch.object(subject, 'process_state', return_value={'birth': 'owned'}), \
                patch.object(subject, 'observe_exit', side_effect=observe), \
                patch.object(subject, 'cleanup_session', side_effect=cleanup):
            code = subject.run(self.script, self.receipt)
        self.assertEqual(code, 130)
        receipt = json.loads(self.receipt.read_text())
        self.assertEqual(receipt['cancelSignal'], signal.SIGINT)
        self.assertTrue(receipt['cleanup']['confirmed'])

    def test_normal_exit_with_background_leak_fails_and_reaps(self):
        child = self.launch('sleep 60 &\necho $! > background-pid\nexit 0\n')
        receipt = self.finish(child)
        self.assertEqual(child.returncode, 1)
        self.assertEqual(receipt['shellExit'], 0)
        self.assertTrue(receipt['normalExitLeaks'])
        self.assertTrue(receipt['cleanup']['confirmed'])
        self.assertFalse(Path('/proc/' + (self.home / 'background-pid').read_text().strip()).exists())

    def test_failed_exit_with_background_leak_cleans_and_stays_failed(self):
        child = self.launch('sleep 60 &\necho $! > background-pid\nexit 9\n')
        receipt = self.finish(child)
        self.assertEqual(child.returncode, 9)
        self.assertEqual(receipt['shellExit'], 9)
        self.assertTrue(receipt['cleanup']['confirmed'])
        self.assertFalse(Path('/proc/' + (self.home / 'background-pid').read_text().strip()).exists())

    def test_session_scan_errors_are_not_hidden(self):
        with patch.object(subject.Path, 'iterdir', side_effect=PermissionError('proc denied')):
            with self.assertRaises(PermissionError):
                subject.session_members(100)

    def test_signal_failure_is_retained_as_unconfirmed(self):
        child = Mock(pid=100)
        leader = dict(pid=100, birth='leader', session=100, state='Z', parent=1)
        member = dict(pid=101, birth='child', session=100, state='S', parent=100)
        with patch.object(subject, 'process_state', return_value=leader), \
                patch.object(subject, 'session_members', return_value=[leader, member]), \
                patch.object(subject, 'signal_qualified_process', side_effect=PermissionError('signal denied')), \
                patch.object(subject, 'reap_adopted'), patch.object(subject, 'CLEANUP_SECONDS', 0):
            result = subject.cleanup_session(child, 'leader')
        self.assertFalse(result['confirmed'])
        self.assertTrue(any('signal denied' in error for error in result['errors']))
        child.wait.assert_not_called()

    def test_changed_session_is_never_signaled(self):
        child = Mock(pid=100)
        leader = dict(pid=100, birth='leader', session=100, state='Z', parent=1)
        member = dict(pid=101, birth='child', session=100, state='S', parent=100)
        changed = dict(member, session=999)
        with patch.object(subject, 'process_state', side_effect=lambda pid: leader if pid == 100 else changed), \
                patch.object(subject, 'session_members', return_value=[leader, member]), \
                patch.object(subject, 'reap_adopted'), patch.object(subject, 'CLEANUP_SECONDS', 0), \
                patch.object(owned_process.os, 'pidfd_open', return_value=99), \
                patch.object(owned_process.os, 'close') as close, \
                patch.object(owned_process.signal, 'pidfd_send_signal') as send:
            result = subject.cleanup_session(child, 'leader')
        self.assertFalse(result['confirmed'])
        self.assertTrue(any('identity/ownership changed' in error for error in result['errors']))
        send.assert_not_called()
        close.assert_called_once_with(99)

    def test_pidfd_rechecks_identity_and_closes_on_reuse_or_signal_error(self):
        for qualify, failure in [(False, None), (True, PermissionError('denied'))]:
            with self.subTest(qualified=qualify), \
                    patch.object(owned_process.os, 'pidfd_open', return_value=99), \
                    patch.object(owned_process.os, 'close') as close, \
                    patch.object(owned_process.signal, 'pidfd_send_signal', side_effect=failure) as send:
                with self.assertRaises(AssertionError if not qualify else PermissionError):
                    owned_process.signal_qualified_process(123, signal.SIGKILL, lambda: qualify)
                close.assert_called_once_with(99)
                if not qualify:
                    send.assert_not_called()

    def test_pid_reuse_after_check_never_uses_numeric_signal(self):
        identities = {123: 'old'}
        def qualify():
            identities[123] = 'new'
            return True
        with patch.object(owned_process.os, 'pidfd_open', return_value=99), \
                patch.object(owned_process.os, 'close') as close, \
                patch.object(owned_process.signal, 'pidfd_send_signal') as send, \
                patch.object(owned_process.os, 'kill') as numeric:
            owned_process.signal_qualified_process(123, signal.SIGKILL, qualify)
        send.assert_called_once_with(99, signal.SIGKILL)
        close.assert_called_once_with(99)
        numeric.assert_not_called()
        self.assertEqual(identities[123], 'new')

    def test_workflow_shell_has_no_unsupported_expression_context(self):
        # actions/runner workflow-v1.0.json: run-step.shell is non-empty-string,
        # not string-steps-context. A locally interpolated test cannot validate it.
        text = (ROOT / '.github/workflows/aerove-lite.yaml').read_text()
        shells = [line.strip()[len('shell: '):] for line in text.splitlines()
                  if line.strip().startswith('shell: ')]
        self.assertTrue(shells)
        self.assertTrue(all('${{' not in shell for shell in shells))

    def test_missing_evidence_destination_fails_before_script_launch(self):
        self.script.write_text('echo wrong > ' + shlex.quote(str(self.home / 'executed')))
        env = {key: value for key, value in os.environ.items() if key != 'RUNNER_TEMP'}
        result = subprocess.run([sys.executable, '../../tooling/run_owned_step.py', str(self.script)],
                                cwd=ROOT / 'apps/aerove', env=env, capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 2)
        self.assertIn('--evidence or RUNNER_TEMP is required', result.stderr)
        self.assertFalse((self.home / 'executed').exists())

    def test_exact_workflow_shell_from_product_cwd_and_budget_contracts(self):
        text = (ROOT / '.github/workflows/aerove-lite.yaml').read_text()
        step = text.split('      id: subscription_tests\n', 1)[1].split('    - name:', 1)[0]
        self.assertIn('timeout-minutes: 30', step)
        self.assertIn('working-directory: apps/aerove', step)
        self.assertIn('python3 tooling/test_owned_step.py', text)
        shell = next(line.strip().removeprefix('shell: ') for line in step.splitlines() if line.strip().startswith('shell: '))
        self.assertTrue(shell.startswith('nice -n 19 python3 '))
        self.assertNotIn('${{', shell)
        self.assertNotIn('$RUNNER_TEMP', shell)
        command = shlex.split(shell)
        self.assertEqual(command, ['nice', '-n', '19', 'python3',
                                   '../../tooling/run_owned_step.py', '{0}'])
        command = [str(self.script) if arg == '{0}' else arg for arg in command]
        self.script.write_text('pwd > ' + shlex.quote(str(self.home / 'production-cwd')))
        subprocess.run(command, cwd=ROOT / 'apps/aerove',
                       env=dict(os.environ, RUNNER_TEMP=str(self.home)), check=True, timeout=5)
        self.assertEqual((self.home / 'production-cwd').read_text().strip(), str(ROOT / 'apps/aerove'))
        receipt = json.loads((self.home / 'lite-flutter-host/subscription-behavior/process-owner.json').read_text())
        self.assertEqual(receipt['status'], 'PASSED')
        host_job = text.split('  flutter_host_preflight:\n', 1)[1].split('    steps:', 1)[0]
        self.assertIn('timeout-minutes: 50', host_job)
        self.assertIn('env "${suite_env[@]}" flutter test --no-pub --concurrency 1 --machine "${suite_args[@]}" "test/$suite" 2>&1 | tee "$evidence/$label.jsonl"', step)
        self.assertIn('len(results) == 63', step)


if __name__ == '__main__':
    unittest.main()
