"""Directed host-only profile boundary tests. No device, build or profiler is launched."""
import copy
import json
import os
from pathlib import Path
import signal
import re
import textwrap
import subprocess
import tempfile
import threading
import unittest
from unittest import mock
import beta14_render_profile as p
import beta14_runtime as runtime
import emulator_original84 as original

class FakeCapture:
    instance=None
    save_sequence=None
    preflight_error=None
    close_error=None
    def __init__(self,*args):
        FakeCapture.instance=self;self.receipt={'errors':[]};self.stop=threading.Event();self.worker=None;self.saved=0
    def preflight(self):
        if self.preflight_error:raise self.preflight_error
    def save(self):
        self.saved+=1
        return self.save_sequence[self.saved-1] if self.save_sequence and self.saved<=len(self.save_sequence) else True
    def sample(self):pass
    def close(self):
        if self.worker:self.worker.join(timeout=1)
        if self.close_error:raise self.close_error
    def error(self,where,error):self.receipt['errors'].append((where,type(error).__name__))

class WrapperTests(unittest.TestCase):
    def setUp(self):
        FakeCapture.save_sequence=None;FakeCapture.preflight_error=None;FakeCapture.close_error=None
        self.args=type('Args',(),dict(owner='unused',serial='emulator-5554',output='unused'))
    def invoke(self,child=None):
        with mock.patch.object(p.signal,'SIGKILL',9,create=True),mock.patch.object(p.sys,'platform','linux'),mock.patch.object(p,'checked_owner',return_value={}),mock.patch.object(p,'Capture',FakeCapture),mock.patch.object(p.subprocess,'Popen',return_value=child or mock.Mock()) as launch:
            result=p.run(self.args,['original-command'])
        return result,launch
    def test_original_nonzero_and_cleanup_error_remain_original(self):
        child=mock.Mock();child.wait.return_value=7;child.poll.return_value=7
        FakeCapture.close_error=OSError('disk full')
        result,_=self.invoke(child);self.assertEqual(7,result);self.assertEqual(7,FakeCapture.instance.receipt['originalCommandReturnCode'])
    def test_original_success_remains_success_until_separate_qualification(self):
        child=mock.Mock();child.wait.return_value=0;child.poll.return_value=0
        result,_=self.invoke(child);self.assertEqual(0,result)
    def test_preflight_missing_skips_original_command(self):
        FakeCapture.preflight_error=ValueError('No existing usable collector')
        result,launch=self.invoke();self.assertEqual(78,result);launch.assert_not_called();self.assertTrue(FakeCapture.instance.receipt['commandSkipped'])
    def test_precommand_receipt_failure_skips_command(self):
        FakeCapture.save_sequence=[False]
        result,launch=self.invoke();self.assertEqual(78,result);launch.assert_not_called()
    def test_final_receipt_failure_does_not_replace_original_exit(self):
        FakeCapture.save_sequence=[True,False]
        child=mock.Mock();child.wait.return_value=7;child.poll.return_value=7
        result,_=self.invoke(child);self.assertEqual(7,result)
    def test_timeout_value_locked_before_cleanup_unknown(self):
        child=mock.Mock();child.wait.side_effect=subprocess.TimeoutExpired('original',660);child.poll.return_value=None
        with mock.patch.object(p,'stop_owned',side_effect=OSError('cleanup unavailable')):
            result,_=self.invoke(child)
        self.assertEqual(124,result)
    def test_sigterm_during_preflight_does_not_launch_command(self):
        def interrupted(_):signal.getsignal(signal.SIGTERM)(signal.SIGTERM,None)
        with mock.patch.object(FakeCapture,'preflight',interrupted):result,launch=self.invoke()
        self.assertEqual(143,result);launch.assert_not_called();self.assertEqual([15],FakeCapture.instance.receipt['interruptSignals'])
    def test_oserror_handler_cannot_swallow_cancellation_class(self):
        self.assertFalse(issubclass(p.Cancelled,OSError));self.assertFalse(issubclass(p.Cancelled,Exception))

class DeltaSignalTests(unittest.TestCase):
    def invoke(self,exit_code,phase='close',failure=None,signum=signal.SIGTERM):
        fixture=WrapperTests();fixture.setUp()
        child=mock.Mock();child.poll.return_value=-15 if exit_code==124 else exit_code
        child.wait.side_effect=subprocess.TimeoutExpired('original',780) if exit_code==124 else None
        child.wait.return_value=exit_code
        handlers={sig:signal.getsignal(sig) for sig in (signal.SIGINT,signal.SIGTERM)}
        original_close=FakeCapture.close;original_save=FakeCapture.save
        def cancel():signal.getsignal(signum)(signum,None)
        def close(c):
            original_close(c)
            if phase=='close':cancel()
            if failure=='cleanup':raise OSError('cleanup failure after signal')
        def save(c):
            if c.saved==1 and phase=='save':cancel()
            value=original_save(c)
            if c.saved>1 and failure=='storage':raise OSError('ENOSPC final receipt')
            return value
        def qualify(_):
            if phase=='qualify':cancel()
            if failure=='qualification':raise OSError('qualification ENOSPC')
            return 78
        try:
            with mock.patch.object(FakeCapture,'close',close),mock.patch.object(FakeCapture,'save',save),mock.patch.object(p,'qualify',qualify):
                result,launch=fixture.invoke(child)
            return result,FakeCapture.instance.receipt,child
        finally:
            self.assertEqual(handlers,{sig:signal.getsignal(sig) for sig in handlers})
    def test_late_cleanup_sigterm_preserves_primary7(self):
        result,r,child=self.invoke(7);self.assertEqual((7,7,7),(result,r['originalCommandReturnCode'],r['childObservedReturnCode']));self.assertEqual('original-command',r['primaryResultOwner']);self.assertEqual([15],r['interruptSignals'])
    def test_late_cleanup_sigterm_preserves_guardian124_and_actual_child(self):
        result,r,child=self.invoke(124);self.assertEqual((124,124,-15),(result,r['originalCommandReturnCode'],r['childObservedReturnCode']));self.assertEqual('outer-guardian',r['primaryResultOwner'])
    def test_late_cleanup_sigterm_changes_success_only_wrapper(self):
        result,r,child=self.invoke(0);self.assertEqual((143,0,0),(result,r['originalCommandReturnCode'],r['childObservedReturnCode']));self.assertEqual('cleanup-cancellation',r['primaryResultOwner'])
    def test_late_signal_combined_with_cleanup_storage_and_qualification_errors(self):
        for code in (7,124,0):
            for failure in ('cleanup','storage','qualification'):
                with self.subTest(code=code,failure=failure):
                    result,r,_=self.invoke(code,failure=failure);self.assertEqual(code or 143,result);self.assertEqual(code,r['originalCommandReturnCode']);self.assertTrue(r['errors'])
    def test_signal_during_final_save_or_qualification_does_not_rewrite_command(self):
        for phase in ('save','qualify'):
            for code in (7,124,0):
                with self.subTest(code=code,phase=phase):
                    result,r,_=self.invoke(code,phase);self.assertEqual(code or 143,result);self.assertEqual(code,r['originalCommandReturnCode']);self.assertEqual(result,r['wrapperReturnCode'])
    def test_sigint_late_success_and_command_running_cancellation_restore_handlers(self):
        result,r,_=self.invoke(0,signum=signal.SIGINT);self.assertEqual(130,result);self.assertEqual(0,r['originalCommandReturnCode'])
        fixture=WrapperTests();fixture.setUp();child=mock.Mock();child.poll.return_value=-15
        child.wait.side_effect=lambda **_:signal.getsignal(signal.SIGTERM)(signal.SIGTERM,None)
        handlers={sig:signal.getsignal(sig) for sig in (signal.SIGINT,signal.SIGTERM)}
        with mock.patch.object(p,'qualify',return_value=78):result,_=fixture.invoke(child)
        self.assertEqual(143,result);r=FakeCapture.instance.receipt;self.assertIsNone(r['originalCommandReturnCode']);self.assertEqual(-15,r['childObservedReturnCode']);self.assertEqual('cancellation',r['primaryResultOwner']);self.assertEqual(handlers,{sig:signal.getsignal(sig) for sig in handlers})
    def test_prelaunch_sigterm_remains143_without_original_result(self):
        fixture=WrapperTests();fixture.setUp();handlers={sig:signal.getsignal(sig) for sig in (signal.SIGINT,signal.SIGTERM)}
        def cancel(_):signal.getsignal(signal.SIGTERM)(signal.SIGTERM,None)
        with mock.patch.object(FakeCapture,'preflight',cancel),mock.patch.object(p,'qualify',return_value=78):result,launch=fixture.invoke()
        self.assertEqual(143,result);launch.assert_not_called();self.assertIsNone(FakeCapture.instance.receipt['originalCommandReturnCode']);self.assertEqual(handlers,{sig:signal.getsignal(sig) for sig in handlers})

class StorageOwnershipTests(unittest.TestCase):
    def partial(self,root):
        c=p.Capture.__new__(p.Capture);c.output=Path(root);c.lock=threading.RLock();c.receipt={'errors':[],'originalCommandReturnCode':7};return c
    def test_enospc_put_is_best_effort_and_preserves_primary(self):
        with tempfile.TemporaryDirectory() as d:
            c=self.partial(d)
            with mock.patch.object(Path,'write_bytes',side_effect=OSError('ENOSPC')):self.assertFalse(c.save())
            self.assertEqual(7,c.receipt['originalCommandReturnCode']);self.assertTrue(c.receipt['errors'])
    def test_total_budget_failure_does_not_write(self):
        with tempfile.TemporaryDirectory() as d:
            c=self.partial(d)
            with mock.patch.object(c,'used',return_value=p.LIMIT-2):self.assertFalse(c.put('large',b'123',8))
            self.assertFalse((Path(d)/'large').exists())
    def test_owned_child_two_timeout_stages_are_bounded_unknown(self):
        child=mock.Mock();child.poll.return_value=None;child.wait.side_effect=subprocess.TimeoutExpired('child',2);errors=[]
        with mock.patch.object(p.signal,'SIGKILL',9,create=True):p.stop_owned(child,errors)
        self.assertEqual(3,child.send_signal.call_count);self.assertEqual(3,child.wait.call_count);self.assertEqual('UNKNOWN_AFTER_BOUNDED_WAIT',errors[-1]['outcome'])
    def test_reused_qemu_pid_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            c=self.partial(d);c.qemu=Path(d);c.qemu_identity={'starttime':1};c.owner={'ownerPid':9}
            values=['S']+['0']*21;values[1]='9';values[19]='2';(Path(d)/'stat').write_text('100 (qemu) '+' '.join(values))
            with self.assertRaises(ValueError):c.check_owner()
    def test_native_pid_reuse_is_never_signalled(self):
        c=p.Capture.__new__(p.Capture);c.guest_identity=(99,1);c.remote='/data/local/tmp/own';c.su=False;c.root=False;c.guest=None;c.receipt={'errors':[]};c.lock=threading.RLock()
        fields=['S']+['0']*21;fields[19]='2';calls=[]
        def shell(cmd,**kw):
            calls.append(cmd);return {'returnCode':0,'stdout':'99 (simpleperf) '+' '.join(fields) if cmd.endswith('/stat') else 'simpleperf /data/local/tmp/own.data'}
        c.shell=shell;c.stop_guest();self.assertFalse(any(cmd.startswith('kill') for cmd in calls))
    def test_native_unavailable_does_not_launch(self):
        c=p.Capture.__new__(p.Capture);c.native=False
        with mock.patch.object(p.subprocess,'Popen') as launch:c.start_guest();launch.assert_not_called()
    def test_qualification_is_unknown_red_without_overwriting_original(self):
        with tempfile.TemporaryDirectory() as d:
            raw={'originalCommandReturnCode':7,'nativeSampling':'UNAVAILABLE_HOST_ONLY_UNKNOWN_NATIVE','sampleStartHostMonoNanos':123}
            path=Path(d)/'PROFILE-RECEIPT.json';path.write_text(json.dumps(raw));before=path.read_bytes()
            self.assertEqual(78,p.qualify(d));self.assertEqual(before,path.read_bytes());q=json.loads((Path(d)/'PROFILE-QUALIFICATION.json').read_text());self.assertFalse(q['accepted']);self.assertEqual('UNKNOWN',q['productionCause'])


class CapabilityTests(unittest.TestCase):
    def capture(self,d,flags):
        c=p.Capture.__new__(p.Capture);c.output=Path(d)/'out';c.output.mkdir();c.lock=threading.RLock();c.stop=threading.Event();c.cleanup=False;c.cleanup_deadline=None;c.receipt={'errors':[]};c.perf_command=[];c.perf=None;c.prlimit=None;c.adb='fixture-adb';c.qemu=Path(d)/'qemu';(c.qemu/'task/1').mkdir(parents=True)
        a=['S']+['0']*21;(c.qemu/'task/1/stat').write_text('1 (qemu) '+' '.join(a));c.marker=lambda:'WAIT'
        def shell(cmd,**kw):
            if cmd=='id':return {'returnCode':0,'stdout':'uid=2000(shell)'}
            if cmd=='su 0 id':return {'returnCode':1,'stdout':''}
            if cmd.startswith('dumpsys'):return {'returnCode':0,'stdout':flags,'truncated':False}
            if cmd=='command -v simpleperf':return {'returnCode':0,'stdout':'/system/bin/simpleperf'}
            if cmd=='simpleperf record --help':return {'returnCode':1,'stderr':'--size-limit --call-graph --app'}
            return {'returnCode':0,'stdout':''}
        c.shell=shell;return c
    def test_release_not_profileable_is_host_only_no_native_claim(self):
        with tempfile.TemporaryDirectory() as d:
            c=self.capture(d,'flags=[ HAS_CODE ]');c.preflight();self.assertFalse(c.native);self.assertTrue(c.proc);self.assertEqual('UNAVAILABLE_HOST_ONLY_UNKNOWN_NATIVE',c.receipt['nativeSampling'])
    def test_observed_profileable_only_enables_pending_not_success(self):
        with tempfile.TemporaryDirectory() as d:
            c=self.capture(d,'privateFlags=[ PROFILEABLE_BY_SHELL ]');c.preflight();self.assertTrue(c.native);self.assertEqual('AVAILABLE_PENDING_ACTUAL_CAPTURE',c.receipt['nativeSampling'])
    def test_background_join_is_bounded_and_unknown_is_kept(self):
        with tempfile.TemporaryDirectory() as d:
            c=self.capture(d,'');c.host=None;c.guest=None;c.guest_identity=None;c.worker=mock.Mock();c.worker.is_alive.return_value=True;c.save=mock.Mock(return_value=True)
            c.close();c.worker.join.assert_called_once_with(timeout=30);self.assertTrue(any(x['phase']=='join' for x in c.receipt['errors']))

class RuntimeWorkflowTests(unittest.TestCase):
    def test_explicit_beta14_spec_is_not_legacy94(self):
        self.assertIs(runtime.SPEC,original.runtime_spec('beta14'));self.assertEqual(107,runtime.SPEC['identity']['runNumber']);self.assertEqual('14',runtime.SPEC['product']['versionCode']);self.assertNotEqual(original.runtime_spec('lite94')['apks'],runtime.SPEC['apks'])
    def owner(self):
        return dict(harnessRevision='b'*40,harnessRun='999',profileQemuStarttime=123,app=copy.deepcopy(runtime.SPEC['identity']),runtime=dict(identity=copy.deepcopy(runtime.SPEC['identity']),apks=copy.deepcopy(runtime.SPEC['apks']),runtimeTarget='beta14',apkInspectorOutcome='success',promotionAccepted=False,originalAppIdentity=copy.deepcopy(runtime.SPEC['identity']),harnessIdentity=dict(sourceRevision='b'*40,sourceTree='c'*40,workflowRun='999',runNumber=999,runAttempt=1)),command=['emulator','-port','5554'])
    def test_owner_rejects_old_app_mixed_apks_and_wrong_serial(self):
        expected=dict(sourceRevision='b'*40,sourceTree='c'*40,workflowRun='999',runNumber=999,runAttempt=1)
        with tempfile.TemporaryDirectory() as d,mock.patch.object(p.artifacts,'identity',return_value=expected):
            path=Path(d)/'owner.json';owner=self.owner();path.write_text(json.dumps(owner));p.checked_owner(path,'emulator-5554')
            with self.assertRaises(ValueError):p.checked_owner(path,'emulator-5556')
            owner['app']['runNumber']=94;path.write_text(json.dumps(owner))
            with self.assertRaises(ValueError):p.checked_owner(path,'emulator-5554')
    def test_original19_jni_reader_is_rejected_for_identical107_apk(self):
        if not os.environ.get('AEROVE_PROFILE_RUNTIME107'):self.skipTest('existing107 files not provided')
        with mock.patch.object(runtime,'CORE_JNI_SYMBOLS',runtime.CORE_JNI_SYMBOLS[:19]):
            with self.assertRaisesRegex(ValueError,'fixed26'):runtime.verify_contract(os.environ['AEROVE_PROFILE_RUNTIME107'])
    def test_beta14_archive_api_wrong_size_stops_before_download(self):
        row=dict(id=runtime.SPEC['artifact'],name='AEROVE-Lite-runtime-contract-attempt-1',expired=False,digest='sha256:'+runtime.SPEC['zipSha256'],workflow_run=dict(id=int(runtime.SPEC['identity']['workflowRun']),head_sha=runtime.SPEC['identity']['sourceRevision']),size_in_bytes=1)
        with tempfile.TemporaryDirectory() as d,mock.patch.object(runtime,'api_json',return_value=row),mock.patch.object(runtime,'download') as download:
            with self.assertRaisesRegex(ValueError,'API size'):original.prepare(Path(d)/'new',Path(d)/'evidence','beta14')
            download.assert_not_called()
    def test_dispatch_guard_accepts_profile_only_and_rejects_mixed_build(self):
        root=Path(__file__).resolve().parents[3];workflow=(root/'.github/workflows/aerove-lite.yaml').read_text()
        raw=workflow.split("python3 - <<'PY'\n",1)[1].split('\n        PY',1)[0];code=textwrap.dedent(raw)
        keys=('MEOW_HOST_ONLY','QA84_ONLY','QA79_ONLY','CLIENT_PREFLIGHT','PUBLISH_RELEASE','NATIVE_PREFLIGHT','NATIVE_TEST_SERIAL','NATIVE_UNDERLAY_TESTS','NATIVE_TEST_STACKS')
        env={k:'false' for k in keys};env.update(REQUEST_EVENT='workflow_dispatch',DIAGNOSTIC_TARGET='beta14-profile-only',NATIVE_TEST_FILTER='')
        with mock.patch.dict(os.environ,env):exec(compile(code,'workflow-guard','exec'),{})
        env['MEOW_HOST_ONLY']='true'
        with mock.patch.dict(os.environ,env):
            with self.assertRaises(SystemExit):exec(compile(code,'workflow-guard','exec'),{})
    def test_workflow_independent_mode_and_unchanged_deadline_hook(self):
        root=Path(__file__).resolve().parents[3]
        workflow=(root/'.github/workflows/aerove-lite.yaml').read_text()
        job=re.search(r'\n  diagnostic_beta14_profile:\n(.*?)(?=\n  qa84:)',workflow,re.S).group(1)
        self.assertIn('runtime_target: beta14',job);self.assertIn('uses: ./.github/workflows/aerove-lite-qa84.yaml',job);self.assertIn("== 'beta14-profile-only'",job)
        self.assertIn('options: [none, lite94-matrix, std40, both, beta14-profile-only]',workflow)
        shell=(root/'apps/aerove-lite/tool/emulator_acceptance.sh').read_text();self.assertIn('test_timeout_msec=600000',shell);self.assertIn('--command-timeout 660 --',shell)
        self.assertGreater(shell.index('beta14_render_profile.py qualify'),shell.index('Instrumentation did not report a complete passing test run'))
    @unittest.skipUnless(os.environ.get('AEROVE_PROFILE_RUNTIME107'),'explicit existing immutable run107 runtime required')
    def test_actual_existing107_inventory_native26_and_three_assets(self):
        root=Path(os.environ['AEROVE_PROFILE_RUNTIME107']);info=original.validate_original_files(root,'beta14');self.assertEqual(14,info['version_code']);proof=runtime.verify_contract(root);self.assertEqual(26,len(proof['jniExports']));self.assertEqual(3,len(proof['bundledMeowScript']['assets']))

if __name__=='__main__':unittest.main(verbosity=2)
