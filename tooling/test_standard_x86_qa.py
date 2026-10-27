import io
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import standard_x86_qa as qa


class HostObserverTest(unittest.TestCase):
    def isolated(self):
        return patch.dict(os.environ, dict(GITHUB_ACTIONS='true', GITHUB_REPOSITORY=qa.REPOSITORY,
                          GITHUB_REF=qa.HOST_OBSERVER_REF, GITHUB_EVENT_NAME='workflow_dispatch',
                          GITHUB_RUN_ATTEMPT='1'), clear=True)

    def args(self, **changes):
        return SimpleNamespace(**dict(dict(host_crash_observer=True, phase='capture', fallback_after=None), **changes))

    def test_default_never_needs_diagnostic_environment_or_debugger(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(qa.shutil, 'which') as which:
            self.assertFalse(qa.host_observer_enabled(SimpleNamespace()))
            which.assert_not_called()

    def test_opt_in_is_confined_to_first_attempt_isolated_primary(self):
        with self.isolated(), patch.object(qa.sys, 'platform', 'linux'):
            self.assertTrue(qa.host_observer_enabled(self.args()))
            for key, value in [('GITHUB_REF', qa.HARNESS_REF), ('GITHUB_RUN_ATTEMPT', '2'),
                               ('GITHUB_REPOSITORY', 'someone/else'), ('GITHUB_EVENT_NAME', 'push')]:
                with self.subTest(key=key), patch.dict(os.environ, {key: value}):
                    with self.assertRaises(ValueError): qa.host_observer_enabled(self.args())
            for args in [self.args(phase='verify'), self.args(fallback_after=Path('primary'))]:
                with self.assertRaises(ValueError): qa.host_observer_enabled(args)
            with patch.object(qa.sys, 'platform', 'win32'):
                with self.assertRaises(ValueError): qa.host_observer_enabled(self.args())

    def test_isolated_reuse_keeps_producer_identity_and_rejects_other_package_before_api(self):
        with self.isolated(), patch.object(qa.sys, 'platform', 'linux'), patch.object(qa, 'api_reader') as api:
            args = self.args(phase='reuse', run_id='40531629533', qa_run_id='40536314613',
                             qa_artifact_id='12747833793')
            with self.assertRaisesRegex(ValueError, 'fixed to formal58/QA5'): qa.reuse_package(args)
            api.assert_not_called()
        self.assertEqual(qa.HARNESS_BRANCH, 'codex/aerove-lite-meow-app')
        self.assertEqual(qa.HARNESS_REF, 'refs/heads/codex/aerove-lite-meow-app')

    def test_missing_existing_gdb_writes_unavailable_and_never_spawns(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(qa.shutil, 'which', return_value=None), \
                patch.object(qa.subprocess, 'Popen') as spawn:
            directory = Path(folder) / 'observer'
            with self.assertRaisesRegex(RuntimeError, 'HOST_CRASH_CAPTURE_UNAVAILABLE'):
                qa.HostCrashObserver(directory, {})
            receipt = json.loads((directory / 'HOST-OBSERVER.json').read_text())
            self.assertFalse(receipt['accepted'])
            self.assertFalse(receipt['compiled'])
            self.assertEqual(receipt['status'], 'HOST_CRASH_CAPTURE_UNAVAILABLE')
            spawn.assert_not_called()

    def test_proc_start_identity_handles_parentheses_and_spaces_in_comm(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(qa, 'PROC', Path(folder)):
            process = Path(folder) / '42'; process.mkdir()
            fields = ['S', '17'] + ['0'] * 17 + ['12345']
            (process / 'stat').write_text('42 (emu (qemu) name) ' + ' '.join(fields))
            self.assertEqual(qa.observer_process(42), dict(pid=42, parent=17, startTicks=12345))

    def test_target_must_be_sdk_executable_and_direct_owner_child(self):
        process = Mock(); process.__truediv__ = Mock(return_value=process)
        process.resolve.return_value = Path('/outside/emulator')
        with patch.object(qa, 'PROC', process), patch.object(qa, 'observer_process', return_value={}), \
                self.assertRaisesRegex(ValueError, 'outside'):
            qa.observer_target(42, Path('/sdk'))
        observer = object.__new__(qa.HostCrashObserver); observer.record = {}
        with patch.object(qa, 'observer_target', return_value=dict(parent=-1)), \
                patch.object(qa.subprocess, 'Popen') as spawn:
            with self.assertRaisesRegex(ValueError, 'not the emulator child'):
                observer.start(SimpleNamespace(pid=42), Path('/sdk'))
            spawn.assert_not_called()

    def test_sigill_is_delivered_even_during_stop_and_after_capture_cap(self):
        for stopping in [False, True]:
            self.assertEqual(qa.observer_signal_action('SIGILL', stopping, 0), 'capture')
            self.assertEqual(qa.observer_signal_action('SIGILL', stopping, 4), 'continue')
        self.assertEqual(qa.observer_signal_action('SIGINT', True, 0), 'detach')
        self.assertEqual(qa.observer_signal_action('SIGSEGV', False, 0), 'continue')

    def run_gdb_script(self, attach_error=False, permission=b'GO\n', pipe=True, readable=True, expired=False):
        debugger = Mock()
        debugger.error = RuntimeError
        inferior = SimpleNamespace(pid=42, threads=lambda: [])
        debugger.selected_inferior.return_value = inferior
        debugger.objfiles.return_value = []
        state = dict(continues=0, passSignal=False)
        def execute(command, **unused):
            if command == 'handle SIGILL stop print pass': state['passSignal'] = True
            if command == 'attach 42' and attach_error: raise PermissionError('ptrace denied')
            if command == 'continue':
                state['continues'] += 1
                if state['continues'] == 1:
                    debugger.events.stop.connect.call_args.args[0](SimpleNamespace(stop_signal='SIGILL'))
                else:
                    if not state['passSignal']: raise AssertionError('original signal was suppressed')
                    inferior.pid = 0
            return ''
        debugger.execute.side_effect = execute
        fields = ['S', '17'] + ['0'] * 17 + ['12345']
        config = dict(target=dict(pid=42, parent=17, startTicks=12345, executable='/sdk/emulator/qemu'),
                      stop='/does-not-exist/STOP', startupDeadline=qa.time.monotonic() + (-1 if expired else 5))
        namespace = dict(CONFIG_JSON=json.dumps(config), signal_action=qa.observer_signal_action)
        output = io.StringIO()
        with patch.dict(sys.modules, {'gdb': debugger}), patch('builtins.open', side_effect=lambda *a: io.StringIO(
                '42 (qemu) ' + ' '.join(fields))), patch('os.readlink', return_value='/sdk/emulator/qemu'), \
                patch('sys.stdout', output), patch('os.isatty', return_value=False), \
                patch('os.fstat', return_value=SimpleNamespace(st_mode=0o010000 if pipe else 0o020000)), \
                patch('select.select', return_value=([0], [], []) if readable else ([], [], [])), \
                patch('os.read', side_effect=permission if isinstance(permission, list) else None, return_value=permission):
            exec(compile(qa.HOST_GDB_SCRIPT, '<generated-GDB-Python>', 'exec'), namespace)
        rows = [json.loads(line.split(' ', 1)[1]) for line in output.getvalue().splitlines()
                if line.startswith('AEROVE_HOST_OBSERVER ')]
        return debugger, state, rows

    def test_generated_gdb_program_records_and_continues_pending_sigill(self):
        debugger, state, rows = self.run_gdb_script(permission=[b'G', b'O\n'])
        self.assertEqual(state['continues'], 2)
        self.assertEqual([r['kind'] for r in rows].count('capture-start'), 1)
        self.assertTrue(any(r['kind'] == 'signal' and r['signalPassedOnContinue'] for r in rows))
        self.assertFalse(any(r['kind'] == 'error' for r in rows))
        debugger.selected_thread.return_value.switch.assert_called_once_with()
        self.assertNotIn('detach', [c.args[0] for c in debugger.execute.call_args_list])

    def test_generated_gdb_program_reports_real_attach_error_without_ready(self):
        debugger, state, rows = self.run_gdb_script(attach_error=True)
        self.assertEqual(state['continues'], 0)
        self.assertFalse(any(r['kind'] == 'ready' for r in rows))
        self.assertTrue(any(r['kind'] == 'error' and r['errorType'] == 'PermissionError' for r in rows))
        self.assertNotIn('detach', [c.args[0] for c in debugger.execute.call_args_list])

    def test_recycled_debugger_pid_is_not_signalled(self):
        observer = object.__new__(qa.HostCrashObserver)
        observer.process = Mock(); observer.debugger = dict(pid=42, parent=17, startTicks=100, executable='/gdb')
        observer.environment = {}; observer.prefix = []
        with patch.object(qa, 'observer_debugger', return_value=dict(pid=42, parent=17, startTicks=101, executable='/gdb')), \
                patch.object(qa.os, 'kill') as kill:
            with self.assertRaisesRegex(RuntimeError, 'identity'): observer.signal_debugger(signal.SIGINT)
            kill.assert_not_called()

    def observer_for_close(self, directory):
        observer = object.__new__(qa.HostCrashObserver)
        observer.directory = directory; observer.stop = directory / 'STOP-REQUESTED'
        observer.timer = None; observer.process = Mock(); observer.process.poll.return_value = None
        def waited(**unused):
            observer.process.poll.return_value = 0
            return 0
        observer.process.wait.side_effect = waited
        observer.reader = Mock(); observer.reader.is_alive.return_value = False
        observer.record = dict(events=[], attachActuallySucceeded=True, startupAdmitted=True)
        observer.signal_debugger = Mock()
        return observer

    def test_close_is_bounded_escalates_owned_debugger_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as folder:
            observer = self.observer_for_close(Path(folder))
            def waited(**unused):
                if observer.process.wait.call_count == 1: raise subprocess.TimeoutExpired('gdb', 5)
                observer.process.poll.return_value = 0
                return 0
            observer.process.wait.side_effect = waited
            with patch.object(signal, 'SIGKILL', 9, create=True):
                observer.close(); observer.close()
            self.assertEqual([call.args for call in observer.signal_debugger.call_args_list],
                             [(signal.SIGINT,), (signal.SIGTERM,)])
            self.assertEqual([call.kwargs['timeout'] for call in observer.process.wait.call_args_list], [5, 2])
            observer.reader.join.assert_called_once_with(timeout=1)
            self.assertTrue(observer.record['cleanupTimedOut'])

    def test_cleanup_signal_error_does_not_replace_original_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            observer = self.observer_for_close(Path(folder))
            observer.signal_debugger.side_effect = PermissionError('denied')
            with self.assertRaisesRegex(ValueError, 'original primary'):
                try: raise ValueError('original primary')
                finally: observer.close()
            self.assertEqual(observer.record['stopSignalError'], 'PermissionError')

    def test_output_cap_and_untrusted_ready_do_not_admit_attach(self):
        with tempfile.TemporaryDirectory() as folder:
            observer = object.__new__(qa.HostCrashObserver)
            observer.directory = Path(folder); observer.ready = qa.threading.Event()
            observer.debugger = None; observer.target = dict(pid=42)
            observer.record = dict(events=[], outputLimitBytes=1024)
            row = b'AEROVE_HOST_OBSERVER ' + json.dumps(dict(kind='ready', debugger={}, target=dict(pid=42))).encode()
            observer.process = Mock(stdout=io.BytesIO(b'x' * 4096 + b'\n' + row + b'\n'))
            observer.read_output()
            self.assertFalse(observer.record.get('attachActuallySucceeded'))
            self.assertTrue(observer.record['outputTruncated'])
            self.assertEqual((Path(folder) / 'gdb.txt').stat().st_size, 1024)

    def test_abrupt_debugger_or_missing_finished_is_incomplete(self):
        for code, events, reader_error, expected in [
                (-11, [dict(kind='ready')], None, 'DIAGNOSTIC_INCOMPLETE'),
                (0, [dict(kind='ready')], None, 'DIAGNOSTIC_INCOMPLETE'),
                (0, [dict(kind='finished')], 'OSError', 'DIAGNOSTIC_INCOMPLETE'),
                (0, [dict(kind='finished')], None, 'FINISHED')]:
            with self.subTest(code=code, reader_error=reader_error, expected=expected), tempfile.TemporaryDirectory() as folder:
                observer = self.observer_for_close(Path(folder))
                observer.process.poll.return_value = code; observer.record['events'] = events
                if reader_error: observer.record['readerError'] = reader_error
                with self.assertRaisesRegex(ValueError, 'original primary'):
                    try: raise ValueError('original primary')
                    finally: observer.close()
                receipt = json.loads((Path(folder) / 'HOST-OBSERVER.json').read_text())
                self.assertEqual(receipt['status'], expected)
                self.assertEqual(receipt['debuggerReturnCode'], code)

    def fake_proc(self, folder):
        root = Path(folder) / 'proc'; root.mkdir()
        for pid, parent, ticks in [(41, 17, 99), (42, 41, 100)]:
            directory = root / str(pid); directory.mkdir()
            fields = ['S', str(parent)] + ['0'] * 17 + [str(ticks)]
            (directory / 'stat').write_text(str(pid) + ' (gdb) ' + ' '.join(fields))
        tool = Path(folder) / 'gdb'; tool.write_text('existing tool fixture')
        drift = Path(folder) / 'other'; drift.write_text('exec drift fixture')
        return root, tool.resolve(), drift.resolve()

    def test_same_start_executable_drift_is_not_signalled(self):
        with tempfile.TemporaryDirectory() as folder:
            root, tool, drift = self.fake_proc(folder)
            observer = object.__new__(qa.HostCrashObserver)
            observer.process = Mock(); observer.prefix = []; observer.environment = {}
            observer.debugger = dict(pid=42, parent=41, startTicks=100, executable=str(tool))
            with patch.object(qa, 'PROC', root), patch.object(qa.os, 'readlink', return_value=str(drift)) as read, \
                    patch.object(qa.os, 'kill') as kill:
                with self.assertRaisesRegex(RuntimeError, 'identity'): observer.signal_debugger(signal.SIGTERM)
                kill.assert_not_called()
                read.assert_called_once_with(root / '42' / 'exe')

    def test_marker_executable_claim_must_match_actual_proc(self):
        with tempfile.TemporaryDirectory() as folder:
            root, tool, drift = self.fake_proc(folder)
            observer = object.__new__(qa.HostCrashObserver)
            observer.directory = Path(folder); observer.tool = tool
            observer.debugger = None; observer.prefix = []; observer.environment = {}
            observer.ready = qa.threading.Event()
            observer.record = dict(events=[], outputLimitBytes=1024,
                                   launcher=dict(pid=41, parent=17, startTicks=99))
            row = dict(kind='started', debugger=dict(pid=42, parent=41, startTicks=100, executable=str(tool)))
            observer.process = Mock(pid=41, stdout=io.BytesIO(
                b'AEROVE_HOST_OBSERVER ' + json.dumps(row).encode() + b'\n'))
            with patch.object(qa, 'PROC', root), patch.object(qa.os, 'readlink', return_value=str(drift)) as read:
                observer.read_output()
            self.assertIsNone(observer.debugger)
            self.assertTrue(observer.record['debuggerIdentityRejected'])
            read.assert_called_once_with(root / '42' / 'exe')

    def test_executable_read_permission_failure_does_not_signal(self):
        with tempfile.TemporaryDirectory() as folder:
            root, tool, drift = self.fake_proc(folder)
            observer = object.__new__(qa.HostCrashObserver)
            observer.process = Mock(); observer.prefix = []; observer.environment = {}
            observer.debugger = dict(pid=42, parent=41, startTicks=100, executable=str(tool))
            with patch.object(qa, 'PROC', root), patch.object(qa.os, 'readlink', side_effect=PermissionError), \
                    patch.object(qa.os, 'kill') as kill:
                with self.assertRaises(PermissionError): observer.signal_debugger(signal.SIGTERM)
                kill.assert_not_called()

    def test_reader_exception_is_retained_and_wakes_admission(self):
        with tempfile.TemporaryDirectory() as folder:
            observer = object.__new__(qa.HostCrashObserver)
            observer.record = dict(events=[]); observer.ready = qa.threading.Event()
            with patch.object(observer, 'read_output_stream', side_effect=OSError): observer.read_output()
            self.assertEqual(observer.record['readerError'], 'OSError')
            self.assertTrue(observer.ready.is_set())

    def test_minimal_environment_reaches_child_without_synthetic_url(self):
        with tempfile.TemporaryDirectory() as folder:
            untrusted = dict(QA_DOWNLOAD_URL='https://example.invalid/blob?sig=SYNTHETIC_NEVER_VALID_SAS',
                             GH_TOKEN='SYNTHETIC_TOKEN', LD_PRELOAD='SYNTHETIC_PRELOAD',
                             PYTHONPATH='SYNTHETIC_PYTHONPATH', PATH='SYNTHETIC_PATH')
            with patch.object(qa.HostCrashObserver, 'prepare') as prepare:
                observer = qa.HostCrashObserver(Path(folder) / 'observer', untrusted)
            environment = prepare.call_args.args[0]
            self.assertTrue(set(environment) == {'PATH', 'HOME', 'TMPDIR', 'LANG', 'LC_ALL', 'DEBUGINFOD_URLS'})
            child = ('import os,json\n'
                     'if os.name == "nt":\n'
                     ' import ctypes\n'
                     ' k=ctypes.windll.kernel32;k.GetCurrentProcess.restype=ctypes.c_void_p\n'
                     ' k.SetPriorityClass.argtypes=[ctypes.c_void_p,ctypes.c_ulong]\n'
                     ' assert k.SetPriorityClass(k.GetCurrentProcess(),64)\n'
                     'print(json.dumps(dict(os.environ)))\n')
            result = subprocess.run([sys.executable, '-c', child], env=environment, capture_output=True,
                                    creationflags=0x08000040 if os.name == 'nt' else 0, timeout=10, check=True)
            actual = json.loads(result.stdout)
            self.assertFalse(any('SYNTHETIC' in value for value in actual.values()))
            self.assertFalse('QA_DOWNLOAD_URL' in actual)
            observer.persist()
            self.assertFalse('SYNTHETIC' in (observer.directory / 'HOST-OBSERVER.json').read_text())

    def test_partial_start_readback_failure_recovers_and_closes_child(self):
        for stage in ['executable', 'process-stat', 'reader-start']:
            with self.subTest(stage=stage), tempfile.TemporaryDirectory() as folder:
                directory = Path(folder) / 'observer'
                with patch.object(qa.HostCrashObserver, 'prepare'):
                    observer = qa.HostCrashObserver(directory, {})
                tool = Path(folder) / 'gdb'; tool.write_text('existing debugger fixture')
                observer.tool = tool.resolve(); observer.prefix = []
                identity = dict(pid=77, parent=os.getpid(), startTicks=100)
                actual = dict(identity, executable=str(tool.resolve()))
                state = dict(code=None, stdinClosed=False)
                process = Mock(pid=77)
                process.poll.side_effect = lambda: state['code']
                process.stdin.close.side_effect = lambda: state.update(stdinClosed=True)
                def wait(**unused):
                    state['code'] = 0
                    return 0
                process.wait.side_effect = wait
                reader = Mock(ident=None); reader.is_alive.return_value = False
                reader.start.side_effect = RuntimeError('initial reader start failure')
                proc_reads = [PermissionError('initial process stat failure'), identity] if stage == 'process-stat' else None
                exe_reads = [PermissionError('initial executable failure'), actual] if stage == 'executable' else None
                with patch.object(qa, 'observer_target', return_value=dict(parent=os.getpid())), \
                        patch.object(qa, 'observer_process', side_effect=proc_reads, return_value=identity), \
                        patch.object(qa, 'observer_debugger', side_effect=exe_reads, return_value=actual), \
                        patch.object(qa.subprocess, 'Popen', return_value=process), \
                        patch.object(qa.threading, 'Thread', return_value=reader), \
                        patch.object(qa.os, 'kill') as kill, patch.object(qa.os, 'killpg', create=True) as killpg:
                    expected = RuntimeError if stage == 'reader-start' else PermissionError
                    with self.assertRaisesRegex(expected, 'initial'):
                        try: observer.start(SimpleNamespace(pid=1), Path(folder))
                        finally: observer.close()
                    observer.close()
                self.assertTrue(state['stdinClosed'])
                process.wait.assert_called_once_with(timeout=5)
                kill.assert_not_called(); killpg.assert_not_called()
                receipt = json.loads((directory / 'HOST-OBSERVER.json').read_text())
                self.assertTrue(receipt['closed'])
                self.assertTrue(receipt['childReaped'])
                self.assertFalse(receipt['startupAdmitted'])
                self.assertEqual(receipt['status'], 'HOST_CRASH_CAPTURE_UNAVAILABLE')
                self.assertIsNotNone(receipt['launcherExecutable'])

    def test_generated_gdb_stdin_eof_never_attaches(self):
        debugger, state, rows = self.run_gdb_script(permission=b'')
        self.assertEqual(state['continues'], 0)
        self.assertNotIn('attach 42', [call.args[0] for call in debugger.execute.call_args_list])
        self.assertFalse(any(row['kind'] == 'admitted' for row in rows))
        self.assertTrue(any(row['kind'] == 'finished' for row in rows))

    def test_generated_gdb_nonpipe_or_permission_timeout_never_attaches(self):
        for options in [dict(pipe=False), dict(readable=False), dict(expired=True)]:
            with self.subTest(options=options):
                debugger, state, rows = self.run_gdb_script(**options)
                self.assertEqual(state['continues'], 0)
                self.assertNotIn('attach 42', [call.args[0] for call in debugger.execute.call_args_list])
                self.assertFalse(any(row['kind'] == 'admitted' for row in rows))

    def exercise_start_permission(self, mode):
        with tempfile.TemporaryDirectory() as folder:
            with patch.object(qa.HostCrashObserver, 'prepare'):
                observer = qa.HostCrashObserver(Path(folder) / 'observer', {})
            tool = Path(folder) / 'gdb'; tool.write_text('existing debugger fixture')
            observer.tool = tool.resolve(); observer.prefix = []
            identity = dict(pid=77, parent=os.getpid(), startTicks=100)
            actual = dict(identity, executable=str(tool.resolve()))
            state = dict(code=None, reads=0)
            process = Mock(pid=77); process.poll.side_effect = lambda: state['code']
            process.stdin.closed = False
            process.stdin.close.side_effect = lambda: setattr(process.stdin, 'closed', True)
            reader = Mock(ident=123); reader.is_alive.return_value = True
            reader.join.side_effect = lambda **unused: setattr(reader.is_alive, 'return_value', False)
            def started():
                observer.debugger = dict(actual)
                observer.record['startedIdentityValidated'] = True
                observer.record['events'].append(dict(kind='started'))
                observer.started.set()
                if mode in ('reader-error-exit', 'reader-error-alive'): observer.record['readerError'] = 'OSError'
                if mode in ('reader-error-exit', 'reader-exited'): reader.is_alive.return_value = False
                if mode in ('reader-error-exit', 'ready-without-attach'): observer.ready.set()
                if mode == 'child-exited': state['code'] = -11
                if mode == 'reader-not-started': reader.ident = None
                if mode == 'unvalidated-marker': observer.record['startedIdentityValidated'] = False
                if mode == 'stop-requested': observer.stop.touch()
                if mode == 'stdin-closed': process.stdin.closed = True
                if mode == 'error-marker': observer.record['events'].append(dict(kind='error'))
                if mode == 'return-code-recorded': observer.record['debuggerReturnCode'] = -11
            reader.start.side_effect = started
            def debugger(*unused):
                state['reads'] += 1
                if state['reads'] == 2 and mode == 'reader-error-during-identity':
                    observer.record['readerError'] = 'OSError'; reader.is_alive.return_value = False
                    observer.ready.set()
                return dict(actual)
            def flushed():
                if mode == 'healthy':
                    observer.record.update(attachActuallySucceeded=True, startupAdmitted=True)
                observer.ready.set()
            process.stdin.flush.side_effect = flushed
            def waited(**unused):
                state['code'] = 0; reader.is_alive.return_value = False
                observer.record['events'].append(dict(kind='finished'))
                return 0
            process.wait.side_effect = waited
            with patch.object(qa, 'observer_target', return_value=dict(parent=os.getpid())), \
                    patch.object(qa, 'observer_process', return_value=identity), \
                    patch.object(qa, 'observer_debugger', side_effect=debugger), \
                    patch.object(qa.subprocess, 'Popen', return_value=process), \
                    patch.object(qa.threading, 'Thread', return_value=reader), patch.object(qa.os, 'kill'):
                try:
                    if mode == 'healthy': observer.start(SimpleNamespace(pid=1), Path(folder))
                    else:
                        with self.assertRaises(RuntimeError): observer.start(SimpleNamespace(pid=1), Path(folder))
                finally:
                    observer.close()
            if mode == 'healthy':
                process.stdin.write.assert_called_once_with(b'GO\n')
                process.stdin.flush.assert_called_once_with()
                self.assertTrue(observer.record['startupPermissionSent'])
            else:
                process.stdin.write.assert_not_called(); process.stdin.flush.assert_not_called()
                self.assertFalse(observer.record.get('startupPermissionSent'))
            self.assertTrue(process.stdin.closed)
            self.assertTrue(observer.record['childReaped'])

    def test_started_reader_failure_and_other_known_invalid_states_never_send_go(self):
        for mode in ['reader-error-exit', 'reader-error-alive', 'reader-exited', 'child-exited',
                     'reader-not-started', 'unvalidated-marker', 'ready-without-attach', 'stop-requested',
                     'stdin-closed', 'error-marker', 'return-code-recorded', 'reader-error-during-identity']:
            with self.subTest(mode=mode): self.exercise_start_permission(mode)

    def test_healthy_started_reader_sends_one_permission(self):
        self.exercise_start_permission('healthy')


if __name__ == '__main__':
    unittest.main()
