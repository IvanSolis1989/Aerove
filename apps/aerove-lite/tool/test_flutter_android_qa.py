"""Host contract regressions only; no fixture here is Android acceptance evidence."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import zipfile

import flutter_android_qa as qa


class ContractTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.apk = self.root / 'app.apk'
        self.test_apk = self.root / 'test.apk'
        self.test_apk.write_bytes(b'contract fixture, never an installable APK')
        with zipfile.ZipFile(self.apk, 'w') as archive:
            for library in ('libapp.so', 'libflutter.so', 'libaerove_meow_core.so'):
                archive.writestr('lib/x86_64/' + library, b'\x7fELFcontract-fixture')
        self.info = dict(product='AEROVE Lite', application_id=qa.PACKAGE, launcher_activity=qa.LAUNCHER,
                         abi='x86_64', apk_sha256=qa.sha(self.apk), source='a' * 40, tree='b' * 40,
                         workflow='https://github.com/IvanSolis1989/Aerove/actions/runs/93',
                         run_number=93, run_attempt=1, flutter_packaging=dict(client_kind='flutter-aot'))
        self.proof = dict(owner='emulator_current.py', identity=dict(sourceRevision='a' * 40, sourceTree='b' * 40,
                          workflowRun='93', runNumber=93, runAttempt=1),
                          apks={'app-release.apk': qa.sha(self.apk), 'app-release-androidTest.apk': qa.sha(self.test_apk)})

    def test_seal_and_owner_require_identical_original_bytes(self):
        qa.validate_binding(self.apk, self.test_apk, self.info, self.proof)
        self.test_apk.write_bytes(b'changed test APK')
        with self.assertRaisesRegex(ValueError, 'both exact'):
            qa.validate_binding(self.apk, self.test_apk, self.info, self.proof)

    def test_old_launcher_and_cross_run_are_rejected(self):
        self.info['launcher_activity'] = qa.PACKAGE + '.MainActivity'
        with self.assertRaises(ValueError):
            qa.validate_binding(self.apk, self.test_apk, self.info, self.proof)
        self.info['launcher_activity'] = qa.LAUNCHER
        self.proof['identity']['workflowRun'] = 'other'
        with self.assertRaisesRegex(ValueError, 'source/run/attempt'):
            qa.validate_binding(self.apk, self.test_apk, self.info, self.proof)

    def test_compose_native_apk_cannot_pass_flutter_entry(self):
        with zipfile.ZipFile(self.apk, 'w') as archive:
            archive.writestr('lib/x86_64/libaerove_meow_core.so', b'\x7fELF')
        self.info['apk_sha256'] = self.proof['apks']['app-release.apk'] = qa.sha(self.apk)
        with self.assertRaisesRegex(ValueError, 'libapp.so'):
            qa.validate_binding(self.apk, self.test_apk, self.info, self.proof)

    def test_renderer_is_pid_scoped_and_no_skia_fallback(self):
        log = '10-31 12:00:00.000 123 125 I flutter: Using the Impeller rendering backend (Vulkan).'
        self.assertEqual(qa.renderer(log, 123)['backend'], 'impeller')
        with self.assertRaises(ValueError):
            qa.renderer(log, 124)
        with self.assertRaises(ValueError):
            qa.renderer(log + '\n10-31 12:00:00.000 123 125 I flutter: Skia rendering backend', 123)

    def test_crash_oracle_detects_dart_and_java_failures(self):
        for log in ('10-31 12:00:00.000 123 125 E flutter: Unhandled Exception',
                    '10-31 12:00:00.000 123 125 E AndroidRuntime: FATAL EXCEPTION: main\n'
                    '10-31 12:00:00.000 123 125 E AndroidRuntime: Process: dev.aerove.lite, PID: 123'):
            with self.assertRaises(RuntimeError):
                qa.Producer.check_process_log(log, 123)

    def test_preflight_failure_never_contacts_unowned_device(self):
        from argparse import Namespace
        args = Namespace(adb=Path('adb'), serial='physical-device', output=self.root)
        producer = qa.Producer(args)
        with patch.dict('sys.modules', {'emulator_current': None}), patch.object(producer, 'adb_command') as adb:
            self.assertEqual(producer.run(), 1)
            adb.assert_not_called()
        self.assertEqual(json.loads((self.root / 'qa-receipt.json').read_text())['status'], 'failed')

    def test_cancelled_install_only_cleans_its_owned_attempt(self):
        info_path = self.root / 'BUILD_INFO.json'
        self.info['signer_sha256'] = 'c' * 64
        qa.save(info_path, self.info)
        proof_path = self.root / 'owner.json'
        qa.save(proof_path, self.proof)
        self.proof['deviceSerial'] = 'emulator-5554'
        args = SimpleNamespace(adb=Path('adb'), serial='emulator-5554', output=self.root,
                               apk=self.apk, test_apk=self.test_apk, build_info=info_path,
                               apksigner=Path('apksigner'))
        producer = qa.Producer(args)
        def adb(label, *_args, **_kwargs):
            if label == 'install-' + qa.PACKAGE:
                producer.cancelled = True
                raise InterruptedError('cancelled during install')
            return b''
        owner = SimpleNamespace(validate_renderer_proof=lambda _: self.proof)
        with patch.dict('sys.modules', {'emulator_current': owner}), \
                patch.dict('os.environ', AEROVE_CURRENT_RENDERER_PROOF=str(proof_path), ANDROID_SERIAL='emulator-5554'), \
                patch.object(producer, 'adb_command', side_effect=adb) as commands, \
                patch.object(producer, 'command', return_value=('Signer #1 certificate SHA-256 digest: ' + 'c' * 64).encode()):
            self.assertEqual(producer.run(), 1)
        labels = [call.args[0] for call in commands.call_args_list]
        self.assertIn('cleanup-uninstall-' + qa.PACKAGE, labels)
        self.assertNotIn('cleanup-stop-' + qa.TEST_PACKAGE, labels)
        self.assertEqual(json.loads((self.root / 'qa-receipt.json').read_text())['status'], 'cancelled')

    def test_raw_capture_tamper_and_matrix_completeness(self):
        # Intentionally synthetic bytes test the parser's rejection paths, never device rendering.
        image = qa.PNG + b'\0\0\0\rIHDR' + (200).to_bytes(4, 'big') * 2 + b'fixture-padding'
        (self.root / 'frame.png').write_bytes(image)
        (self.root / 'hierarchy.json').write_text(json.dumps([dict(package=qa.PACKAGE, visible=True, boundsPx=[0, 0, 200, 200])]))
        (self.root / 'window.txt').write_text('synthetic parser rejection fixture')
        frame = dict(file='frame.png', sha256=qa.sha(self.root / 'frame.png'), bytes=len(image),
                     hierarchy='hierarchy.json', hierarchySha256=qa.sha(self.root / 'hierarchy.json'),
                     windowInsetsDump='window.txt', windowInsetsDumpSha256=qa.sha(self.root / 'window.txt'),
                     density=1, windows=[dict(boundsPx=[0, 0, 200, 200])])
        receipt = dict(schema=1, producer=qa.CLASS, scenarioPassed=True, status='captured', qaToken='token',
                       launcher=qa.PACKAGE + '/' + qa.LAUNCHER, apkSha256=self.info['apk_sha256'],
                       source=self.info['source'], tree=self.info['tree'], workflow=self.info['workflow'],
                       runAttempt='1', frames=[frame])
        qa.save(self.root / 'receipt.json', receipt)
        with self.assertRaisesRegex(ValueError, 'Incomplete'):
            qa.validate_capture(self.root, self.info, 'token')
        (self.root / 'frame.png').write_bytes(image + b'changed')
        with self.assertRaisesRegex(ValueError, 'changed'):
            qa.validate_capture(self.root, self.info, 'token', matrix=False)

    def test_original201_v2_signer_reaches_install_but_wrong_or_ambiguous_certificate_does_not(self):
        signature = (Path(__file__).parent / 'fixtures/original201/lite-flutter-runner-signature.txt').read_text()
        digest = '09f9e021ddac36ead015d27e073b673e79fdf4e02605c402a505781963b1a4d8'
        self.info['signer_sha256'] = digest
        info_path = self.root / 'BUILD_INFO.json'
        qa.save(info_path, self.info)
        proof_path = self.root / 'owner.json'
        qa.save(proof_path, self.proof)
        self.proof['deviceSerial'] = 'emulator-5554'
        args = SimpleNamespace(adb=Path('adb'), serial='emulator-5554', output=self.root,
                               apk=self.apk, test_apk=self.test_apk, build_info=info_path,
                               apksigner=Path('apksigner'))
        owner = SimpleNamespace(validate_renderer_proof=lambda _: self.proof)
        for signed, reaches_install in ((signature, True), (signature.replace(digest, 'b' * 64), False),
                                        (signature + '\nSigner #1 certificate SHA-256 digest: ' + digest, False),
                                        (signature.replace('V2 Signer: certificate SHA-256', 'Unknown Signer: certificate SHA-256'), False)):
            producer = qa.Producer(args)
            def adb(label, *_args, **_kwargs):
                if label == 'install-' + qa.PACKAGE:
                    raise InterruptedError('host-only test stops before any installation')
                return b''
            with self.subTest(reaches_install=reaches_install, signature=signed), \
                    patch.dict('sys.modules', {'emulator_current': owner}), \
                    patch.dict('os.environ', AEROVE_CURRENT_RENDERER_PROOF=str(proof_path), ANDROID_SERIAL='emulator-5554'), \
                    patch.object(producer, 'adb_command', side_effect=adb) as commands, \
                    patch.object(producer, 'command', return_value=signed.encode()):
                self.assertEqual(producer.run(), 1)
            self.assertEqual(any(call.args[0].startswith('install-') for call in commands.call_args_list), reaches_install)
            self.assertFalse(json.loads((self.root / 'qa-receipt.json').read_text())['releaseAccepted'])

    def test_api33_randomized_installed_base_still_requires_original_bytes_and_no_splits(self):
        path = '/data/app/~~7Hs06DjwO1r3r0HEmxkGWg==/dev.aerove.lite-aS-QNBQIXJ9HHqomOtz1Yg==/base.apk'
        original = self.apk.read_bytes()
        expected = hashlib.sha256(original).hexdigest()
        producer = qa.Producer(SimpleNamespace(adb=Path('adb'), serial='emulator-5554', output=self.root))
        with patch.object(producer, 'adb_command', side_effect=[('package:' + path + '\n').encode(), (expected + '  ' + path + '\n').encode()]) as commands:
            self.assertEqual(producer.installed_hash('app', qa.PACKAGE, expected), dict(path=path, sha256=expected))
        self.assertEqual(commands.call_args_list[1].args, ('app-installed-apk', 'shell', 'sha256sum', path))
        with patch.object(producer, 'adb_command', side_effect=[('package:' + path + '\n').encode(), (hashlib.sha256(original + b'changed').hexdigest() + '  ' + path + '\n').encode()]):
            with self.assertRaisesRegex(ValueError, 'bytes differ'):
                producer.installed_hash('app', qa.PACKAGE, expected)
        self.assertEqual(commands.call_args_list[1].kwargs, dict(timeout=60))
        for output in ('', expected, expected + '  /data/app/another/base.apk\n',
                       expected + '  ' + path + '\n' + expected + '  ' + path + '\n'):
            with self.subTest(output=output), patch.object(producer, 'adb_command', side_effect=[
                    ('package:' + path + '\n').encode(), output.encode()]):
                with self.assertRaisesRegex(ValueError, 'digest bound to its exact base path'):
                    producer.installed_hash('app', qa.PACKAGE, expected)
        unavailable = RuntimeError('sha256sum exited 127')
        with patch.object(producer, 'adb_command', side_effect=[('package:' + path + '\n').encode(), unavailable]) as calls:
            with self.assertRaises(RuntimeError) as caught:
                producer.installed_hash('app', qa.PACKAGE, expected)
            self.assertIs(caught.exception, unavailable)
            self.assertEqual(calls.call_count, 2)  # No unchecked hash/cat fallback.
        for bad in ('package:' + path + '\npackage:' + path.replace('base.apk', 'split_config.en.apk'),
                    'package:/sdcard/base.apk', 'package:' + path + ';id'):
            with self.subTest(path=bad), patch.object(producer, 'adb_command', return_value=bad.encode()) as commands:
                with self.assertRaisesRegex(ValueError, 'one installed base APK'):
                    producer.installed_hash('app', qa.PACKAGE, expected)
                self.assertEqual(commands.call_count, 1)

    def test_normal_instrument_default_and_opt_in_preserve_original_command_and_stdout(self):
        args = SimpleNamespace(adb=Path('adb'), serial='emulator-5554', output=self.root)
        producer = qa.Producer(args)
        original = ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', qa.CLASS + '#method', qa.RUNNER]
        junit = b'INSTRUMENTATION_CODE: -1\nOK (1 test)\n'
        for flag in ('0', 'true'):
            with self.subTest(flag=flag), patch.dict('os.environ', AEROVE_CLEAR_FRAME_TRACE=flag), \
                    patch.object(producer, 'adb_command', return_value=junit) as adb, \
                    patch.object(producer, 'command') as command:
                self.assertEqual(producer.normal_instrument('method', *original), junit)
                adb.assert_called_once_with('method', *original, timeout=600)
                command.assert_not_called()
        with patch.dict('os.environ', AEROVE_CLEAR_FRAME_TRACE='1', AEROVE_REQUIRE_FRAME_TRACE='1'), \
                patch.object(producer, 'command', return_value=junit) as command, \
                patch.object(producer, 'adb_command') as adb:
            self.assertEqual(producer.normal_instrument('method', *original), junit)
            adb.assert_not_called()
            label, wrapped = command.call_args.args
            self.assertEqual(label, 'method')
            self.assertEqual(Path(wrapped[1]).name, 'clear_frame_trace.py')
            self.assertEqual(wrapped[wrapped.index('--') + 1:], producer.adb + original)
            self.assertEqual(wrapped[wrapped.index('--command-timeout') + 1], '600')
            self.assertEqual(wrapped[wrapped.index('--serial') + 1], 'emulator-5554')
            self.assertEqual(Path(wrapped[wrapped.index('--output') + 1]).parent, self.root)
            self.assertIn('--require-trace', wrapped)
            self.assertEqual(command.call_args.kwargs, dict(timeout=780, termination_timeout=60))
        with patch.dict('os.environ', AEROVE_CLEAR_FRAME_TRACE='1', AEROVE_REQUIRE_FRAME_TRACE='0'), \
                patch.object(producer, 'command', side_effect=RuntimeError('collector exited 78')) as command:
            with self.assertRaisesRegex(RuntimeError, 'exited 78'):
                producer.normal_instrument('method', *original)
            self.assertNotIn('--require-trace', command.call_args.args[1])

    def test_flutter_instrument_keeps_junit_rejection_and_pull_after_collector_failure(self):
        producer = qa.Producer(SimpleNamespace(adb=Path('adb'), serial='emulator-5554', output=self.root))
        for outcome in (b'FAILURES!!!\n', RuntimeError('collector exited 78')):
            options = dict(side_effect=outcome) if isinstance(outcome, Exception) else dict(return_value=outcome)
            with self.subTest(outcome=str(outcome)), patch.object(producer, 'normal_instrument', **options) as invoke, \
                    patch.object(producer, 'adb_command', return_value=b'') as adb:
                with self.assertRaises(RuntimeError):
                    producer.instrument('consentNavigationTheme12', 'token', self.info)
                self.assertEqual(invoke.call_args.args[-1], qa.RUNNER)
                self.assertIn(self.info['apk_sha256'], invoke.call_args.args)
                self.assertIn(self.info['source'], invoke.call_args.args)
                self.assertEqual(adb.call_args.args[0], 'consentNavigationTheme12-pull')
                self.assertTrue(adb.call_args.kwargs['cancelling'])

    def test_collector_cancellation_gets_bounded_cleanup_time_without_changing_default(self):
        from unittest.mock import Mock, call
        producer = qa.Producer(SimpleNamespace(adb=Path('adb'), serial='emulator-5554', output=self.root))
        child = SimpleNamespace(wait=Mock(side_effect=[InterruptedError('cancelled'), 0]),
                                terminate=Mock(), kill=Mock())
        with patch.object(qa.subprocess, 'Popen', return_value=child):
            with self.assertRaises(InterruptedError):
                producer.command('collector', ['python', 'collector'], timeout=780, termination_timeout=60)
        self.assertEqual(child.wait.call_args_list, [call(timeout=780), call(timeout=60)])
        child.terminate.assert_called_once()
        child.kill.assert_not_called()
        self.assertIsNone(producer.child)

    def test_only_instrumentation_install_allows_test_only_apk(self):
        info_path = self.root / 'BUILD_INFO.json'
        self.info['signer_sha256'] = 'c' * 64
        qa.save(info_path, self.info)
        proof_path = self.root / 'owner.json'
        qa.save(proof_path, self.proof)
        self.proof['deviceSerial'] = 'emulator-5554'
        args = SimpleNamespace(adb=Path('adb'), serial='emulator-5554', output=self.root,
                               apk=self.apk, test_apk=self.test_apk, build_info=info_path,
                               apksigner=Path('apksigner'))
        producer = qa.Producer(args)
        def adb(label, *_args, **_kwargs):
            if label == 'install-' + qa.TEST_PACKAGE:
                raise InterruptedError('host-only test stops after recording both install commands')
            return b''
        owner = SimpleNamespace(validate_renderer_proof=lambda _: self.proof)
        with patch.dict('sys.modules', {'emulator_current': owner}), \
                patch.dict('os.environ', AEROVE_CURRENT_RENDERER_PROOF=str(proof_path), ANDROID_SERIAL='emulator-5554'), \
                patch.object(producer, 'adb_command', side_effect=adb) as commands, \
                patch.object(producer, 'command', return_value=('Signer #1 certificate SHA-256 digest: ' + 'c' * 64).encode()):
            self.assertEqual(producer.run(), 1)
        installs = [call for call in commands.call_args_list if call.args[0].startswith('install-')]
        self.assertEqual([call.args for call in installs], [
            ('install-' + qa.PACKAGE, 'install', '--no-streaming', str(self.apk)),
            ('install-' + qa.TEST_PACKAGE, 'install', '-t', '--no-streaming', str(self.test_apk)),
        ])
        self.assertEqual([call.kwargs for call in installs], [{'timeout': 120}, {'timeout': 120}])


    def test_guest_angle_install_stream_joins_only_owned_child_and_preserves_primary_failure(self):
        from unittest.mock import Mock, call
        import subprocess
        for primary, cleanup_fails in ((InterruptedError('cancelled install'), False),
                                       (subprocess.TimeoutExpired(['adb', 'install'], 120), True),
                                       (None, True)):
            with self.subTest(primary=type(primary).__name__):
                producer = qa.Producer(SimpleNamespace(adb=Path('adb'), serial='emulator-5554',
                                                       output=self.root, apk=self.apk))
                producer.owner_verified = True
                capture = SimpleNamespace(pid=4321, poll=Mock(return_value=None),
                                          terminate=Mock(), kill=Mock(), wait=Mock(return_value=-15))
                cleanup_error = OSError('owned capture join failed')
                if cleanup_fails:
                    capture.wait.side_effect = [subprocess.TimeoutExpired(['adb', 'logcat'], 5), cleanup_error]
                install_wait = Mock(return_value=0) if primary is None else Mock(side_effect=[primary, 0])
                install = SimpleNamespace(wait=install_wait, terminate=Mock(), kill=Mock())
                with patch.dict('os.environ', AEROVE_GUEST_ANGLE='1'), \
                        patch.object(qa.subprocess, 'Popen', side_effect=[capture, install]) as popen:
                    with self.assertRaises(type(primary or cleanup_error)) as caught:
                        producer.command('install-' + qa.PACKAGE,
                                         producer.adb + ['install', '--no-streaming', str(self.apk)], timeout=120)
                self.assertIs(caught.exception, primary or cleanup_error)
                self.assertEqual(popen.call_args_list[0].args[0],
                                 producer.adb + ['logcat', '-b', 'all', '-v', 'threadtime'])
                self.assertEqual(install.wait.call_args_list,
                                 [call(timeout=120)] + ([call(timeout=5)] if primary is not None else []))
                if primary is not None:
                    install.terminate.assert_called_once_with()
                else:
                    install.terminate.assert_not_called()
                capture.terminate.assert_called_once_with()
                self.assertEqual(capture.wait.call_args_list, [call(timeout=5)] * (2 if cleanup_fails else 1))
                if cleanup_fails:
                    capture.kill.assert_called_once_with()
                else:
                    capture.kill.assert_not_called()
                self.assertIsNone(producer.child)
                record = producer.receipt['guestAngleInstallLogcat']
                self.assertEqual(record['pid'], 4321)
                if cleanup_fails:
                    self.assertEqual(record['status'], 'cleanup-failed')
                    self.assertIn('owned capture join failed', record['error'])
                else:
                    self.assertEqual((record['exit'], record['status']), (-15, 'joined'))
                self.assertFalse(record['acceptanceEvidence'])
                self.assertIs(producer.receipt['commands'][0]['guestAngleLogcat'], record)
                self.assertEqual(bool(producer.receipt.get('cleanupErrors')), cleanup_fails)
                for call_row in popen.call_args_list:
                    self.assertTrue(call_row.kwargs['stdout'].closed)
                    self.assertTrue(call_row.kwargs['stderr'].closed)

    def test_guest_angle_stream_is_opt_in_exact_app_install_and_never_restarted(self):
        from unittest.mock import Mock
        args = SimpleNamespace(adb=Path('adb'), serial='emulator-5554', output=self.root, apk=self.apk)
        for flag, label, tail in (('0', 'install-' + qa.PACKAGE, ['install', '--no-streaming', str(self.apk)]),
                                  ('true', 'install-' + qa.PACKAGE, ['install', '--no-streaming', str(self.apk)]),
                                  ('1', 'install-' + qa.TEST_PACKAGE, ['install', '-t', '--no-streaming', str(self.test_apk)])):
            producer = qa.Producer(args)
            producer.owner_verified = True
            child = SimpleNamespace(wait=Mock(return_value=0))
            with self.subTest(flag=flag, label=label), patch.dict('os.environ', AEROVE_GUEST_ANGLE=flag), \
                    patch.object(qa.subprocess, 'Popen', return_value=child) as popen:
                self.assertEqual(producer.command(label, producer.adb + tail, timeout=120), b'')
            popen.assert_called_once()
            self.assertEqual(popen.call_args.args[0], producer.adb + tail)
            child.wait.assert_called_once_with(timeout=120)
            self.assertNotIn('guestAngleInstallLogcat', producer.receipt)
        producer = qa.Producer(args)
        producer.owner_verified = True
        capture = SimpleNamespace(pid=1234, poll=Mock(return_value=None), terminate=Mock(),
                                  kill=Mock(), wait=Mock(return_value=-15))
        install = SimpleNamespace(wait=Mock(return_value=0))
        with patch.dict('os.environ', AEROVE_GUEST_ANGLE='1'), \
                patch.object(qa.subprocess, 'Popen', side_effect=[capture, install, install]) as popen:
            for _ in range(2):
                producer.command('install-' + qa.PACKAGE,
                                 producer.adb + ['install', '--no-streaming', str(self.apk)], timeout=120)
        self.assertEqual(popen.call_count, 3)
        capture.terminate.assert_called_once_with()
        self.assertEqual(producer.receipt['guestAngleInstallLogcat']['status'], 'joined')

if __name__ == '__main__':
    unittest.main()
