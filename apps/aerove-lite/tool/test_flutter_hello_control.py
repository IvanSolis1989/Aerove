"""Host-only boundary tests; no SDK, APK execution, device or acceptance claims."""
import json
import os
from pathlib import Path
import tempfile
import subprocess
import unittest
from unittest.mock import patch
import zipfile
import flutter_hello_control as control
import emulator_current as owner

class FixedControlBoundaries(unittest.TestCase):
    def test_owner_accepts_only_the_independent_control_phase_and_current_identity(self):
        identity = {'sourceRevision': 'current-control'}
        runtime = dict(controlDiagnostic=True, identity=identity, accepted=False)
        with patch.dict(os.environ, self.environment(), clear=True), patch.object(owner.artifacts, 'identity', return_value=identity):
            owner.guard_flutter_phase('flutter-control', runtime=runtime)
            self.assertEqual('lavapipe', owner.graphics_configuration()['gpuBackend'])
            for phase in ('flutter-ui', 'flutter-full', 'matrix'):
                with self.subTest(phase=phase), self.assertRaises(ValueError):
                    owner.guard_flutter_phase(phase, runtime=runtime)
            with self.assertRaises(ValueError):
                owner.guard_flutter_phase('flutter-control', runtime={**runtime, 'identity': {'other': 'producer'}})
        with patch.dict(os.environ, {}, clear=True), self.assertRaises(ValueError):
            owner.guard_flutter_phase('flutter-control')

    def test_owner_command_retains_control_source_and_never_selects_product_runner(self):
        with patch.dict(os.environ, {**self.environment(), 'ANDROID_HOME': '/sdk'}, clear=True):
            command = owner.phase_acceptance_command('flutter-control', '/observations', {'control_runtime': Path('/sealed-control')})
            self.assertEqual('flutter_hello_control.py', Path(command[1]).name)
            self.assertEqual('run', command[2])
            self.assertEqual(str(Path('/sealed-control')), command[command.index('--runtime-directory') + 1])
            with self.assertRaises(ValueError):
                owner.phase_acceptance_command('flutter-ui', '/observations', {'control_runtime': Path('/sealed-control')})
    def environment(self):
        return dict(AEROVE_FLUTTER_HELLO_CONTROL='1', GITHUB_REPOSITORY='IvanSolis1989/Aerove',
            GITHUB_ACTOR='IvanSolis1989', GITHUB_EVENT_NAME='workflow_dispatch',
            GITHUB_REF='refs/heads/codex/flutter-onscreen-control-20261107', AEROVE_GUEST_ANGLE='1')
    def test_fixed_request_and_unset_default(self):
        with patch.dict(os.environ, {}, clear=True): self.assertFalse(control.enabled())
        with patch.dict(os.environ, self.environment(), clear=True): self.assertTrue(control.enabled())
    def test_wrong_request_and_mixed_product_graphics_are_rejected(self):
        changes = ({control.OPT_IN:'0'}, {'GITHUB_ACTOR':'other'}, {'GITHUB_EVENT_NAME':'push'},
            {'GITHUB_REF':'refs/heads/main'}, {'AEROVE_GUEST_ANGLE':''},
            *({key:'1'} for key in control.CONFLICTS), *({key:'/external'} for key in control.OVERRIDES))
        for change in changes:
            with self.subTest(change=change), patch.dict(os.environ, {**self.environment(), **change}, clear=True):
                with self.assertRaises(ValueError): control.enabled()
    def test_local_or_nonci_build_is_rejected_before_tools_or_output(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, self.environment(), clear=True), \
                patch.object(control, 'installed_tools') as tools:
            output=Path(tmp)/'never-created'
            with self.assertRaises(ValueError): control.prepare(output)
            tools.assert_not_called(); self.assertFalse(output.exists())
    def test_wrong_engine_bytes_are_control_invalid(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk=Path(tmp)/'fixture.apk'
            with zipfile.ZipFile(apk,'w') as z:
                z.writestr('lib/x86_64/libflutter.so',b'not-the-original38-engine')
                z.writestr('lib/x86_64/libapp.so',b'\x7fELF-not-executed')
            with self.assertRaisesRegex(ValueError,'Control-invalid'): control.engine_identity(apk)
    def test_nonx86_payload_cannot_be_admitted(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk=Path(tmp)/'fixture.apk'
            with zipfile.ZipFile(apk,'w') as z: z.writestr('lib/arm64-v8a/libflutter.so',b'fixture')
            with self.assertRaisesRegex(ValueError,'ABI'): control.engine_identity(apk)
    def test_merged_package_sdk_and_launcher_contracts(self):
        normal="package: name='"+control.PACKAGE+"'\nsdkVersion:'33'\ntargetSdkVersion:'37'\nlaunchable-activity: name='"+control.ACTIVITY+"'\n"
        control.check_badging(normal,control.PACKAGE,control.ACTIVITY)
        for invalid in (normal.replace("'33'","'24'"),normal.replace(control.ACTIVITY,'wrong.Activity'),normal+'application-debuggable\n'):
            with self.assertRaises(ValueError): control.check_badging(invalid,control.PACKAGE,control.ACTIVITY)
    def test_runtime_rejects_other_execution_identity_before_engine_read(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ,self.environment(),clear=True), \
                patch.object(control.ci_artifacts,'identity',return_value={'run':'current'}), \
                patch.object(control,'engine_identity') as engine:
            root=Path(tmp)
            (root/'BUILD.json').write_text(json.dumps(dict(schema=1,status='prepared',kind='official-flutter-hello-control',identity={'run':'other'})))
            with self.assertRaisesRegex(ValueError,'identity'): control.runtime(root)
            engine.assert_not_called()

if __name__=='__main__': unittest.main()

class BuildOwnerAndRecoveryBoundaries(unittest.TestCase):
    def test_each_command_owns_a_new_session_private_home_and_joins_direct_child(self):
        from unittest.mock import Mock
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); logs=root/'logs'; work=root/'work'; logs.mkdir();work.mkdir()
            with patch.object(control.OwnedBuildCommands,'_stat',return_value={'starttime':123}), \
                 patch.object(control.OwnedBuildCommands,'_members',return_value=[]), \
                 patch.object(control.OwnedBuildCommands,'_private_daemons',return_value=[]):
                owner=control.OwnedBuildCommands(logs,work)
                child=Mock(pid=1234,returncode=0);child.wait.return_value=0;child.poll.return_value=0
                try:
                    with patch.object(control.subprocess,'Popen',return_value=child) as spawn:
                        owner.command('owned-host-only',['not-executed'])
                    self.assertTrue(spawn.call_args.kwargs['start_new_session'])
                    self.assertEqual(str(work/'gradle-user-home'),spawn.call_args.kwargs['env']['GRADLE_USER_HOME'])
                    self.assertIn('-Dorg.gradle.daemon=false',spawn.call_args.kwargs['env']['GRADLE_OPTS'])
                    self.assertTrue(owner.commands[0]['directChildJoined'])
                finally:owner.close()
    def test_record_only_signal_cannot_raise_between_spawn_and_ownership_assignment(self):
        import signal
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);logs=root/'logs';work=root/'work';logs.mkdir();work.mkdir()
            with patch.object(control.OwnedBuildCommands,'_private_daemons',return_value=[]):
                owner=control.OwnedBuildCommands(logs,work)
                owner.interrupt(signal.SIGTERM,None)
                self.assertEqual([signal.SIGTERM],owner.signals)
                with patch.object(control.subprocess,'Popen') as spawn:
                    with self.assertRaises(InterruptedError):owner.command('never-started',['not-executed'])
                    spawn.assert_not_called()
                with self.assertRaises(InterruptedError):owner.close()
                self.assertTrue(owner.closed)
    def test_missing_original_key_does_not_create_output_or_build_the_app(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); original=root/'original';original.mkdir()
            with patch.object(control,'ci_only'),patch.object(control,'verify_app_checkpoint',return_value={}), \
                 patch.dict(os.environ,{'RUNNER_TEMP':str(root)}),patch.object(control,'prepare') as app, \
                 patch.object(control,'installed_tools') as tools:
                with self.assertRaisesRegex(ValueError,'original debug signing key required'):
                    control.runner_only(original,root/'output','a'*64,root/'missing-original-key')
                app.assert_not_called();tools.assert_not_called();self.assertFalse((root/'output').exists())
    def test_recovery_seal_keeps_original_producer_and_bytes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/'CONTROL-APP-BUILD.json').write_bytes(b'original-checkpoint-bytes')
            original=(root/'CONTROL-APP-BUILD.json').read_bytes()
            (root/'control.apk').write_bytes(b'not-executed-fixture')
            old={'workflowRun':'producer-old'};current={'workflowRun':'runner-new'}
            cp={'identity':old,'officialSource':{},'flutterLibrary':{}}
            with patch.object(control.ci_artifacts,'identity',return_value=current):
                control.seal_control(root,cp,'a'*64,True)
            info=json.loads((root/'BUILD.json').read_text())
            self.assertEqual(old,info['appProducerIdentity']);self.assertEqual(current,info['runnerIdentity'])
            self.assertTrue(info['appReused']);self.assertFalse(info['controlCompiled'])
            self.assertEqual(original,(root/'CONTROL-APP-BUILD.json').read_bytes())

@unittest.skipUnless(control.sys.platform=='linux' and os.environ.get('AEROVE_TEST_CONTROL_BUILD_OWNER')=='1',
                     'Explicit real Linux owned-host child fixture; never SDK')
class RealBuildOwnerHostTests(unittest.TestCase):
    def test_successful_wrapper_allows_short_lived_descendant_to_exit_naturally(self):
        import sys
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);logs=root/'logs';work=root/'work';logs.mkdir();work.mkdir()
            owner=control.OwnedBuildCommands(logs,work,budget=20)
            try:
                program='import subprocess,sys;subprocess.Popen([sys.executable,"-c","import time;time.sleep(.2)"]);print("done")'
                self.assertIn('done',owner.command('natural-drain',[sys.executable,'-c',program],timeout=5))
                row=owner.commands[0]
                self.assertTrue(row['groupNaturallyDrained'])
                self.assertFalse(row['terminationRequested'])
                self.assertEqual([],owner._members(row['processGroup']))
            finally:owner.close()

    def test_cleanup_error_does_not_replace_original_command_timeout(self):
        import sys
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);logs=root/'logs';work=root/'work';logs.mkdir();work.mkdir()
            owner=control.OwnedBuildCommands(logs,work,budget=20)
            try:
                with patch.object(owner,'_stop',side_effect=RuntimeError('injected cleanup failure')):
                    with self.assertRaises(subprocess.TimeoutExpired):
                        owner.command('primary-timeout',[sys.executable,'-c','import time;time.sleep(60)'],timeout=.1)
                row=owner.commands[0]
                self.assertEqual('TimeoutExpired',row['failureClass'])
                self.assertEqual('RuntimeError',row['cleanupFailureClass'])
            finally:owner.close()
            self.assertTrue(owner.commands[0]['directChildJoined'])

    def test_timeout_terminates_group_and_joins_its_actual_child(self):
        import sys
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);logs=root/'logs';work=root/'work';logs.mkdir();work.mkdir()
            owner=control.OwnedBuildCommands(logs,work,budget=20)
            try:
                program='import subprocess,sys,time;subprocess.Popen([sys.executable,"-c","import time;time.sleep(60)"]);time.sleep(60)'
                with self.assertRaises(subprocess.TimeoutExpired):owner.command('owned-timeout',[sys.executable,'-c',program],timeout=.5)
                row=owner.commands[0];self.assertTrue(row['terminationRequested']);self.assertTrue(row['directChildJoined'])
                self.assertEqual([],owner._members(row['processGroup']))
            finally:owner.close()
    def test_sigterm_cancellation_stops_only_owned_build_and_restores_handlers(self):
        import sys,signal
        original=signal.getsignal(signal.SIGTERM)
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);logs=root/'logs';work=root/'work';logs.mkdir();work.mkdir()
            owner=control.OwnedBuildCommands(logs,work,budget=20)
            program='import os,signal,time;os.kill(os.getppid(),signal.SIGTERM);time.sleep(60)'
            with self.assertRaises(InterruptedError):owner.command('owned-cancel',[sys.executable,'-c',program],timeout=5)
            row=owner.commands[0];self.assertTrue(row['directChildJoined']);self.assertEqual([],owner._members(row['processGroup']))
            with self.assertRaises(InterruptedError):owner.close()
            self.assertEqual(original,signal.getsignal(signal.SIGTERM))

class RecoveryKeyAndCheckpointTests(unittest.TestCase):
    def test_changed_checkpoint_is_rejected_before_engine_or_runner(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/'CONTROL-APP-BUILD.json').write_text('{}')
            with patch.object(control,'engine_identity') as engine:
                with self.assertRaisesRegex(ValueError,'checkpoint hash'):
                    control.verify_app_checkpoint(root,'a'*64)
                engine.assert_not_called()
    def test_wrong_key_certificate_stops_before_javac_or_resigning_app(self):
        from unittest.mock import Mock
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);output=root/'output';output.mkdir();key=root/'original-debug.keystore';key.write_bytes(b'not-executed-key')
            logs=root/'logs';logs.mkdir();(logs/'runner-key-certificate.out').write_bytes(b'wrong-public-certificate')
            owner=Mock(logs=logs)
            with self.assertRaisesRegex(ValueError,'certificate differs'):
                control.build_runner(output,root,root,root/'android.jar',{'keytool':'not-executed'},owner,key,'a'*64)
            self.assertEqual(1,owner.command.call_count)
            self.assertEqual('runner-key-certificate',owner.command.call_args.args[0])
    def test_gradle_stop_is_explicitly_scoped_to_private_home(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);logs=root/'logs';work=root/'work';logs.mkdir();work.mkdir()
            with patch.object(control.OwnedBuildCommands,'_private_daemons',return_value=[]):
                owner=control.OwnedBuildCommands(logs,work)
                project=root/'android';project.mkdir();(project/'gradlew').write_text('not executed')
                owner.gradle_project=project;owner.gradle_used=True;(owner.gradle_home/'daemon').mkdir()
                with patch.object(owner,'command',return_value='') as command:
                    owner.close()
                argv=command.call_args.args[1]
                self.assertEqual([project/'gradlew','--stop','--gradle-user-home',owner.gradle_home],argv)
                self.assertEqual(project,command.call_args.kwargs['cwd'])
