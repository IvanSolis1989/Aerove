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


if __name__ == '__main__':
    unittest.main()
