"""Direct tests of fixed Core commands and lock restoration, without pretending to execute Rust."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import io
import os
import time
import signal
import threading
from unittest.mock import patch
import build_policy
import core_tests

class StreamingCommandTests(unittest.TestCase):
    def test_real_success_and_nonzero_preserve_exact_streams_and_progress(self):
        for code in (0,7):
            with self.subTest(code=code),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);out=root/'out';err=root/'err';entry={};console=io.StringIO()
                args=[sys.executable,'-c',f'import sys;sys.stdout.buffer.write(b"ready\\r\\n\\x00");sys.stderr.buffer.write(b"error\\r\\n");sys.exit({code})']
                with patch.object(sys,'stdout',console),patch.object(sys,'stderr',io.StringIO()):
                    result=core_tests.run_logged(args,root,os.environ,out,err,entry=entry,timeout=3)
                self.assertEqual((code,b'ready\r\n\x00',b'error\r\n'),(result.returncode,result.stdout,result.stderr))
                self.assertEqual(result.stdout,out.read_bytes());self.assertEqual(result.stderr,err.read_bytes())
                self.assertIn('ready',console.getvalue());self.assertEqual(code,entry['exit'])
                self.assertNotEqual(os.getpid(),entry['pid']);self.assertTrue(entry['cleanupConfirmed']);self.assertIsNotNone(entry['endedAt'])
                self.assertEqual(entry,json.loads(Path(str(out)+'.progress.json').read_bytes()))

    def test_partial_stdout_and_stderr_are_visible_before_command_finishes(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);out=root/'out';err=root/'err';release=root/'release';results=[]
            script='import sys,time;from pathlib import Path;sys.stdout.write("ready");sys.stdout.flush();sys.stderr.write("early-error");sys.stderr.flush();p=Path(sys.argv[1]);\nwhile not p.exists():time.sleep(.01)\nprint("done")'
            def invoke():results.append(core_tests.run_logged([sys.executable,'-c',script,str(release)],root,os.environ,out,err,timeout=4))
            with patch.object(sys,'stdout',io.StringIO()),patch.object(sys,'stderr',io.StringIO()):
                task=threading.Thread(target=invoke);task.start()
                try:
                    deadline=time.monotonic()+3
                    while time.monotonic()<deadline and not (out.exists() and err.exists() and out.read_bytes()==b'ready' and err.read_bytes()==b'early-error'):time.sleep(.01)
                    self.assertEqual(b'ready',out.read_bytes());self.assertEqual(b'early-error',err.read_bytes());self.assertTrue(task.is_alive())
                finally:release.touch();task.join(5)
            self.assertFalse(task.is_alive());self.assertEqual(b'readydone'+os.linesep.encode(),results[0].stdout)

    def test_timeout_preserves_partial_streams_and_never_records_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);entry={};out=root/'out';err=root/'err'
            args=[sys.executable,'-u','-c','import time,sys;print("partial",flush=True);print("partial-error",file=sys.stderr,flush=True);time.sleep(30)']
            with patch.object(sys,'stdout',io.StringIO()),patch.object(sys,'stderr',io.StringIO()):
                with self.assertRaises(subprocess.TimeoutExpired):core_tests.run_logged(args,root,os.environ,out,err,entry=entry,timeout=1,grace=.1)
            self.assertIn(b'partial',out.read_bytes());self.assertIn(b'partial-error',err.read_bytes())
            self.assertEqual('timed-out',entry['status']);self.assertNotEqual(0,entry['exit']);self.assertTrue(entry['cleanupConfirmed'])

    def test_cancel_preserves_partial_output_and_restores_signal_handlers(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);entry={};real_wait=subprocess.Popen.wait;interrupted=False
            previous={sig:signal.getsignal(sig) for sig in (signal.SIGINT,signal.SIGTERM)}
            def wait(process,*args,**kwargs):
                nonlocal interrupted
                if not interrupted:
                    interrupted=True;deadline=time.monotonic()+3
                    while time.monotonic()<deadline and not (root/'out').read_bytes():time.sleep(.01)
                    raise KeyboardInterrupt('test cancellation after real output')
                return real_wait(process,*args,**kwargs)
            with patch.object(sys,'stdout',io.StringIO()),patch.object(sys,'stderr',io.StringIO()),patch.object(subprocess.Popen,'wait',wait):
                with self.assertRaises(KeyboardInterrupt):core_tests.run_logged([sys.executable,'-u','-c','import time;print("before-cancel",flush=True);time.sleep(30)'],root,os.environ,root/'out',root/'err',entry=entry,timeout=4,grace=.1)
            self.assertIn(b'before-cancel', (root/'out').read_bytes());self.assertEqual('cancelled',entry['status'])
            self.assertTrue(entry['cleanupConfirmed']);self.assertEqual(previous,{sig:signal.getsignal(sig) for sig in previous})

    def test_unconfirmed_or_zombie_group_never_becomes_success(self):
        for status in ('unknown','zombie'):
            with self.subTest(status=status),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);entry={}
                with patch.object(sys,'stdout',io.StringIO()),patch.object(sys,'stderr',io.StringIO()),\
                        patch.object(core_tests,'_group_outcome',return_value=dict(status=status,members=[])):
                    with self.assertRaisesRegex(RuntimeError,'CleanupUnconfirmed'):
                        core_tests.run_logged([sys.executable,'-c','print("output retained")'],root,os.environ,root/'out',root/'err',entry=entry,timeout=3)
                self.assertEqual(0,entry['exit']);self.assertEqual('failed',entry['status']);self.assertFalse(entry['cleanupConfirmed'])
                self.assertIn(b'output retained',(root/'out').read_bytes())

    def test_group_cleanup_still_kills_owned_group_after_leader_exit(self):
        from unittest.mock import Mock
        process=Mock(pid=90909,returncode=-15);process.wait.return_value=-15;state=dict(processGroup=90909,signals=[])
        with patch.object(core_tests.os,'name','posix'),patch.object(core_tests.os,'getpgrp',return_value=80808,create=True),\
                patch.object(core_tests.os,'killpg',create=True) as killpg,patch.object(core_tests,'_process_identity',return_value=None),\
                patch.object(core_tests,'_owned_alive',side_effect=[True,True,False,False]):
            self.assertTrue(core_tests._stop_owned(process,state,0,threading.Event()))
        self.assertEqual([(90909,signal.SIGTERM),(90909,getattr(signal,'SIGKILL',9))],[call.args for call in killpg.call_args_list])
        process.wait.assert_called_once_with(timeout=2)

    @unittest.skipUnless(os.name=='posix','real POSIX process-group integration requires a POSIX host')
    def test_timeout_kills_grandchild_even_after_its_leader_exits(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);pidfile=root/'grandchild';entry={}
            grandchild='import signal,time;signal.signal(signal.SIGTERM,signal.SIG_IGN);time.sleep(30)'
            script='import subprocess,sys,time;from pathlib import Path;p=subprocess.Popen([sys.executable,"-c",sys.argv[1]]);Path(sys.argv[2]).write_text(str(p.pid));print("spawned",flush=True);time.sleep(30)'
            with patch.object(sys,'stdout',io.StringIO()),patch.object(sys,'stderr',io.StringIO()):
                with self.assertRaises(subprocess.TimeoutExpired):core_tests.run_logged([sys.executable,'-u','-c',script,grandchild,str(pidfile)],root,os.environ,root/'out',root/'err',entry=entry,timeout=1,grace=.1)
            self.assertIn('SIGKILL',entry['signals'])
            self.assertIn(entry['groupOutcome']['status'],['absent','zombie'])
            self.assertEqual(entry['groupOutcome']['status']=='absent',entry['cleanupConfirmed'])
            if sys.platform.startswith('linux'):
                identity=core_tests._process_identity(int(pidfile.read_text()))
                self.assertTrue(identity is None or identity['state']=='Z','grandchild must no longer be running')

# The files rustfmt reaches from the two provider-reader format gates, with the overlay's real module declarations.
FORMAT_SOURCES={'crates/meow-rules/src/mrs_parser.rs':'mod tests {\n}\n','crates/meow-rules/src/rule_set.rs':'mod tests {\n}\n',
    'crates/meow-config/src/rule_provider.rs':'#[path = "rule_pages.rs"]\nmod rule_pages;\npub use rule_pages::RulePage;\n#[path = "rule_provider_reader.rs"]\nmod reader;\n',
    'crates/meow-config/src/rule_pages.rs':'pub struct RulePage;\n',
    'crates/meow-config/src/rule_provider_reader.rs':'#[cfg(test)]\n#[path = "rule_provider_reader_tests.rs"]\nmod tests;\n',
    'crates/meow-config/src/rule_provider_reader_tests.rs':'#[test]\nfn fixture() {}\n'}

def write_format_sources(upstream,sources=FORMAT_SOURCES):
    for name,text in sources.items():
        path=Path(upstream)/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,newline='\n')

class CoreTestAdmissionTests(unittest.TestCase):
    def test_fixed_policy_and_exact_feature_owner_reject_unknown_inputs(self):
        for policy in [{'resolveLock':True,'coreTests':'shell'}, {'resolveLock':False,'coreTests':core_tests.MODE}, {'resolveLock':True,'coreTestLockSha256':'a'*64}]:
            with self.assertRaises(ValueError):core_tests.validate_policy(policy)
        self.assertFalse(core_tests.validate_policy({'resolveLock':False}))
        self.assertTrue(core_tests.validate_policy({'resolveLock':True,'coreTests':core_tests.MODE}))
        self.assertEqual(sum(core_tests.COUNTS.get(case,1) for case in core_tests.CASES),24)
        self.assertEqual(len(core_tests.CASES),22)

    def test_feature_projection_rejects_package_outside_prepared_source(self):
        with tempfile.TemporaryDirectory() as directory:
            upstream=Path(directory);packages=[];nodes=[]
            for name in core_tests.PACKAGES:
                packages.append(dict(id=name,name=name,version='0.26.1',manifest_path=str(upstream/'crates'/name/'Cargo.toml')))
                nodes.append(dict(id=name,features=['z','a']))
            metadata=dict(packages=packages,resolve=dict(nodes=nodes))
            self.assertTrue(all(features==['a','z'] for features in core_tests.feature_matrix(metadata,upstream).values()))
            packages[0]['manifest_path']=str(upstream/'outside/Cargo.toml')
            with self.assertRaisesRegex(ValueError,'prepared source'):core_tests.feature_matrix(metadata,upstream)

    def test_v2_tracks_migrated_helpers_and_real_integration_target(self):
        specs=core_tests.entries(core_tests.MODE_V2)
        self.assertEqual(len(specs),14)
        self.assertEqual(sum(row['expectedPass'] for row in specs),29)
        self.assertEqual(specs[0]['package'],'meow-transport')
        self.assertEqual((specs[0]['filter'],specs[0]['expectedPass']),('scoped_join::',11))
        compat=next(row for row in specs if row['target']=='transport_scope_compat')
        args=core_tests.entry_command(compat,['aerove-android-runtime'])
        self.assertEqual(args,['cargo','test','--manifest-path',core_tests.MANIFEST,'--locked','--package','meow-common','--test','transport_scope_compat','--no-default-features','--features','meow-common/aerove-android-runtime','--','--test-threads=1','--nocapture'])
        self.assertIsNone(compat['filter'])
        for spec in specs:
            if spec['requiredFeatures']:
                with self.subTest(package=spec['package'],filter=spec['filter']):
                    with self.assertRaisesRegex(ValueError,'authored Core test feature'):core_tests.entry_command(spec,[])

    def run_fixture(self,mode,contract=core_tests.MODE,command_runner=None):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);up=root/'.upstream/meow-rs';up.mkdir(parents=True);(root/'build').mkdir()
            original=b'actual-upstream-lock';bridge=b'accepted-bridge-lock';resolved=b'actual-cargo-core-lock'
            (up/'Cargo.lock').write_bytes(original);(root/'Cargo.lock').write_bytes(bridge)
            policy=dict(resolveLock=True,coreTests=contract)
            provider=contract in (core_tests.MODE_PROVIDER_READER,core_tests.MODE_SELECTION_OWNER)
            if provider:policy['targets']=list(build_policy.RESOURCE_HEALTH_TARGETS)
            if mode in ['locked','bad-seed']:
                (root/'core-tests.lock').write_bytes(resolved if mode=='locked' else b'wrong seed')
                policy.update(resolveLock=False,coreTestLockSha256=core_tests.digest(resolved))
            packages=[];nodes=[]
            for name in core_tests.packages_for_mode(contract):
                p=up/'crates'/name/'Cargo.toml';p.parent.mkdir(parents=True);p.write_text('')
                packages.append(dict(id=name,name=name,version='0.26.1',manifest_path=str(p)))
                features=sorted({feature for spec in core_tests.entries(contract) if spec['package']==name for feature in spec['requiredFeatures']})
                if name=='meow-common':features=sorted(set(features+['aerove-android-runtime']))
                nodes.append(dict(id=name,features=features,deps=[]))
            if provider:
                write_format_sources(up)
                if mode=='format-undeclared':
                    write_format_sources(up,{'crates/meow-config/src/rule_provider.rs':FORMAT_SOURCES['crates/meow-config/src/rule_provider.rs']+'#[path = "rule_extra.rs"]\nmod extra;\n',
                        'crates/meow-config/src/rule_extra.rs':'fn extra() {}\n'})
            metadata=dict(packages=packages,resolve=dict(nodes=nodes),workspace_members=[p['id'] for p in packages])
            receipt=dict(addedNativeTests={'lib':['fixture_native_proof']}) if provider else {};commands=[]
            def fake(args,**kwargs):
                commands.append(args)
                if args[1]=='metadata':
                    if '--manifest-path' in args:(up/'Cargo.lock').write_bytes(resolved)
                    return subprocess.CompletedProcess(args,0,json.dumps(metadata).encode(),b'')
                if args[0]=='rustfmt':
                    self.assertIn(args,[core_tests.MRS_FORMAT_COMMAND,core_tests.PROVIDER_READER_FORMAT_COMMAND] if provider else [core_tests.MRS_FORMAT_COMMAND])
                    if mode=='format-mutate':(up/'Cargo.lock').write_bytes(b'format changed lock')
                    failed=mode=='format-fail' or (mode=='format-fail-provider' and args==core_tests.PROVIDER_READER_FORMAT_COMMAND)
                    return subprocess.CompletedProcess(args,1 if failed else 0,b'fixture format output',b'')
                if args[1]=='check':
                    self.assertEqual(args,core_tests.COMPILE_COMMAND)
                    if mode=='compile-mutate':(up/'Cargo.lock').write_bytes(b'compile changed lock')
                    return subprocess.CompletedProcess(args,101 if mode=='compile-fail' else 0,b'fixture check output',b'')
                package=args[args.index('--package')+1]
                spec=next(row for row in core_tests.entries(contract) if row['package']==package and core_tests.entry_command(row,next(node['features'] for node in nodes if node['id']==package))==args)
                if mode=='nonzero':return subprocess.CompletedProcess(args,101,b'',b'actual command failure')
                if mode=='raise':raise KeyboardInterrupt()
                if mode=='timeout':raise subprocess.TimeoutExpired(args,900,output=b'partial core output',stderr=b'partial core error')
                if mode=='mutate':(up/'Cargo.lock').write_bytes(b'mutated')
                host_admission=spec['filter']=='mrs_parser::host_admission_index_tests::'
                if host_admission and mode=='duplicate-host-admission':
                    return subprocess.CompletedProcess(args,0,b'test result: ok. 2 passed; 0 failed;\ntest result: ok. 2 passed; 0 failed;\n',b'')
                count=0 if mode=='empty' else spec['expectedPass']
                if host_admission and mode=='empty-host-admission':count=0
                if host_admission and mode=='extra-host-admission':count=3
                return subprocess.CompletedProcess(args,0,f'test result: ok. {count} passed; 0 failed;\n'.encode(),b'')
            error=None
            def logged(args,cwd,environment,out,err,*,entry=None,update=None,**kwargs):
                active=json.loads((root/'build/core-tests/receipt.json').read_bytes())
                self.assertEqual(args,active['activeCommand']['command'])
                if args[1]=='test':self.assertEqual(len(receipt['coreTests']['commands'])-1,active['activeIndex'])
                if command_runner is not None and args[1]=='test':
                    commands.append(args)
                    return command_runner(args,cwd,environment,out,err,entry,update)
                try:result=fake(args)
                except BaseException as error:
                    Path(out).write_bytes(getattr(error,'output',None) or b'');Path(err).write_bytes(getattr(error,'stderr',None) or b'');raise
                Path(out).write_bytes(result.stdout);Path(err).write_bytes(result.stderr)
                if entry is not None:entry['exit']=result.returncode
                if update is not None:update()
                return result
            with patch.object(core_tests,'run_logged',logged):
                try:core_tests.execute(root,policy,{},receipt)
                except BaseException as caught:error=caught
            self.assertEqual((up/'Cargo.lock').read_bytes(),original)
            self.assertEqual((root/'Cargo.lock').read_bytes(),bridge)
            self.assertTrue(receipt['coreTests']['upstreamLockRestored'])
            self.assertEqual(receipt['coreTests']['mode'],contract)
            self.assertEqual(json.loads((root/'build/core-tests/receipt.json').read_bytes()),receipt['coreTests'])
            self.assertEqual(commands[0],['cargo','metadata','--locked','--format-version','1'])
            if mode=='bad-seed':
                self.assertIsInstance(error,ValueError);self.assertFalse(receipt['coreTests']['accepted'])
                self.assertEqual(len(commands),1);return
            self.assertEqual(commands[1],['cargo','metadata','--manifest-path',core_tests.MANIFEST,'--format-version','1']+(['--locked'] if mode=='locked' else []))
            formats=[args for args in commands if args[0]=='rustfmt']
            if provider:
                # Both gates run before the first Core test command, in Core03-then-provider order.
                self.assertEqual([core_tests.MRS_FORMAT_COMMAND,core_tests.PROVIDER_READER_FORMAT_COMMAND][:len(formats)],formats)
                self.assertEqual(commands[2:2+len(formats)],formats)
            if mode in ['success','locked']:
                self.assertIsNone(error);self.assertTrue(receipt['coreTests']['accepted'])
                self.assertEqual(len(receipt['coreTests']['commands']),len(core_tests.entries(contract)))
                self.assertEqual(sum(row['passed'] for row in receipt['coreTests']['commands']),sum(row['expectedPass'] for row in core_tests.entries(contract)))
                self.assertEqual((root/'build/core-tests/core-tests.lock').read_bytes(),resolved)
                if provider:
                    self.assertEqual(2,len(formats))
                    for key,expected in [('formatCheck',core_tests.MRS_FORMAT_COMMAND),('providerFormatCheck',core_tests.PROVIDER_READER_FORMAT_COMMAND)]:
                        self.assertEqual((expected,0),(receipt['coreTests'][key]['command'],receipt['coreTests'][key]['exit']))
                        self.assertEqual({name:core_tests.digest((root/name).read_bytes()) for name in expected[4:]},receipt['coreTests'][key]['files'])
                        self.assertEqual(expected[4:],list(receipt['coreTests'][key]['files']))
                    for stem in ['mrs-reader-format','provider-reader-format']:
                        self.assertEqual(b'fixture format output',(root/'build/core-tests'/(stem+'.stdout.log')).read_bytes())
            else:
                self.assertIsNotNone(error);self.assertFalse(receipt['coreTests']['accepted'])
                if mode=='sink-failure':
                    import errno
                    self.assertIsInstance(error,OSError);self.assertEqual(errno.ENOSPC,error.errno)
                if mode=='timeout':
                    self.assertIsInstance(error,subprocess.TimeoutExpired)
                    self.assertEqual(900,error.timeout)
                    self.assertEqual(b'partial core output',next((root/'build/core-tests').glob('00-*.stdout.log')).read_bytes())
                    self.assertNotIn('passed',receipt['coreTests']['commands'][0])
                if provider and mode=='format-mutate':
                    # A rustfmt gate that touches the lock is rejected at once, before any Core test command.
                    self.assertIsInstance(error,ValueError);self.assertEqual(0,len(receipt['coreTests']['commands']))
                elif provider and mode=='format-undeclared':
                    # A module rustfmt would check but the gate does not list stops before its rustfmt and every test.
                    self.assertIsInstance(error,ValueError);self.assertIn('does not declare every file rustfmt checks',str(error))
                    self.assertEqual([core_tests.MRS_FORMAT_COMMAND],formats);self.assertEqual(3,len(commands))
                    self.assertEqual(0,len(receipt['coreTests']['commands']));self.assertIsNone(receipt['coreTests']['providerFormatCheck']['exit'])
                elif provider and mode in ['format-fail','format-fail-provider']:
                    # Deferred format failure: every authored command and the compile gate still ran and were recorded.
                    self.assertIsInstance(error,subprocess.CalledProcessError)
                    self.assertEqual(len(core_tests.entries(contract)),len(receipt['coreTests']['commands']))
                    self.assertTrue(all(row['passed']==row['expectedPass'] for row in receipt['coreTests']['commands']))
                    self.assertEqual(0,receipt['coreTests']['compileCheck']['exit']);self.assertEqual(2,len(formats))
                    self.assertEqual((1 if mode=='format-fail' else 0,1),(receipt['coreTests']['formatCheck']['exit'],receipt['coreTests']['providerFormatCheck']['exit']))
                    self.assertEqual(error.cmd,core_tests.MRS_FORMAT_COMMAND if mode=='format-fail' else core_tests.PROVIDER_READER_FORMAT_COMMAND)
                elif provider and mode in ['empty-host-admission','extra-host-admission','duplicate-host-admission']:
                    self.assertIsInstance(error,ValueError)
                    self.assertIn('exact authored test count',str(error))
                    self.assertEqual(32,len(receipt['coreTests']['commands']))
                    self.assertEqual(177,sum(row['passed'] for row in receipt['coreTests']['commands'][:31]))
                    final=receipt['coreTests']['commands'][-1]
                    self.assertEqual(('mrs_parser::host_admission_index_tests::',2),(final['filter'],final['expectedPass']))
                    self.assertNotIn('passed',final)
                else:
                    self.assertEqual(len(receipt['coreTests']['commands']),len(core_tests.entries(contract)) if mode in ['compile-fail','compile-mutate','format-fail','format-mutate'] else 1)

            return receipt

    def test_real_sink_write_failure_keeps_prefix_rejects_receipt_and_stops_only_owned_process(self):
        import errno
        original_open=Path.open;original_stop=core_tests._stop_owned;original_runner=core_tests.run_logged;evidence={};stopped=[]
        prefix=b'kept-prefix\n';observed=bytearray();prefix_seen=threading.Event()
        def actual_command(args,cwd,environment,out,err,entry,update):
            release=Path(cwd)/'release-after-prefix'
            class FailingSink:
                def __init__(self,stream):self.stream=stream
                def __enter__(self):self.stream.__enter__();return self
                def __exit__(self,*error):return self.stream.__exit__(*error)
                def flush(self):return self.stream.flush()
                def write(self,chunk):
                    if prefix_seen.is_set():raise OSError(errno.ENOSPC,'controlled sink failure after persisted prefix')
                    written=self.stream.write(chunk);self.stream.flush();observed.extend(chunk)
                    self_outer.assertTrue(prefix.startswith(observed))
                    if bytes(observed)==prefix:prefix_seen.set();release.touch()
                    return written
            def opened(path,mode='r',*pos,**kwargs):
                stream=original_open(path,mode,*pos,**kwargs)
                return FailingSink(stream) if Path(path)==Path(out) and mode=='wb' else stream
            def stop(process,state,grace,force):
                stopped.append(dict(pid=process.pid,group=state['processGroup'],aliveBeforeStop=process.poll() is None))
                self.assertEqual(entry['pid'],process.pid);self.assertNotEqual(os.getpid(),process.pid)
                self.assertEqual(process.pid if os.name=='posix' else None,state['processGroup'])
                return original_stop(process,state,grace,force)
            self_outer=self
            script='import sys,time;from pathlib import Path;sys.stdout.buffer.write(b"kept-prefix\\n");sys.stdout.buffer.flush();gate=Path(sys.argv[1]);\nwhile not gate.exists():time.sleep(.01)\nsys.stdout.buffer.write(b"rejected-chunk\\n");sys.stdout.buffer.flush();time.sleep(30)'
            command=[sys.executable,'-u','-c',script,str(release)]
            with patch.object(Path,'open',opened),patch.object(core_tests,'_stop_owned',stop),\
                    patch.object(sys,'stdout',io.StringIO()),patch.object(sys,'stderr',io.StringIO()):
                try:return original_runner(command,cwd,os.environ,out,err,entry=entry,update=update,timeout=4,grace=.1)
                finally:
                    evidence.update(entry=json.loads(json.dumps(entry)),stdoutHex=Path(out).read_bytes().hex(),
                        progress=json.loads(Path(str(out)+'.progress.json').read_bytes()))
        receipt=self.run_fixture('sink-failure',command_runner=actual_command)
        self.assertTrue(prefix_seen.is_set());self.assertEqual(prefix.hex(),evidence['stdoutHex'])
        self.assertEqual(1,len(stopped));self.assertTrue(stopped[0]['aliveBeforeStop'])
        self.assertFalse(receipt['coreTests']['accepted']);self.assertTrue(receipt['coreTests']['upstreamLockRestored'])
        entry=evidence['entry'];self.assertEqual('failed',entry['status']);self.assertEqual('OSError',entry['failureType'])
        self.assertTrue(entry['cleanupConfirmed']);self.assertIsNotNone(entry['exit']);self.assertIsNotNone(entry['endedAt'])
        self.assertEqual(len(prefix),entry['stdoutBytes']);self.assertEqual(entry,evidence['progress'])
        self.assertNotIn('passed',receipt['coreTests']['commands'][0]);self.assertEqual(entry,receipt['coreTests']['lastCommand'])
        self.assertIsNone(receipt['coreTests']['activeCommand'])
        print('io-failure-evidence '+json.dumps(dict(testId=self.id(),platform=sys.platform,stopped=stopped,entry=entry,stdoutHex=evidence['stdoutHex']),sort_keys=True))

    def test_success_records_all_authored_counts_and_restores_original_lock(self):self.run_fixture('success')

    def test_accepted_core_seed_uses_locked_metadata_without_implicit_resolution(self):self.run_fixture('locked')

    def test_wrong_accepted_core_seed_is_rejected_before_core_metadata(self):self.run_fixture('bad-seed')

    def test_failure_zero_matches_mutation_and_interrupt_cannot_accept_or_leak_test_lock(self):
        for mode in ['nonzero','empty','mutate','raise','timeout']:
            with self.subTest(mode=mode):self.run_fixture(mode)

    def test_v2_success_and_zero_match_preserve_separate_lock_contract(self):
        for mode in ['success','locked','empty','raise']:
            with self.subTest(mode=mode):self.run_fixture(mode,core_tests.MODE_V2)

    def test_traffic_mode_reuses_exact_v2_commands_with_its_own_receipt_identity(self):
        self.assertNotEqual(core_tests.MODE_V2,core_tests.MODE_TRAFFIC)
        self.assertEqual(core_tests.entries(core_tests.MODE_V2),core_tests.entries(core_tests.MODE_TRAFFIC))
        self.assertEqual(14,len(core_tests.entries(core_tests.MODE_TRAFFIC)))
        self.assertEqual(29,sum(e['expectedPass'] for e in core_tests.entries(core_tests.MODE_TRAFFIC)))
        self.assertTrue(core_tests.validate_policy(dict(resolveLock=False,coreTests=core_tests.MODE_TRAFFIC,coreTestLockSha256='a'*64)))
        for mode in ['locked','empty','raise']:
            with self.subTest(mode=mode):self.run_fixture(mode,core_tests.MODE_TRAFFIC)

    def test_observability_runs_all18_exact47_cases_and_locked_compile_or_rejects(self):
        specs=core_tests.entries(core_tests.MODE_OBSERVABILITY)
        self.assertEqual(core_tests.CASES_V2,specs[:14])
        self.assertEqual(18,len(specs));self.assertEqual(47,sum(e['expectedPass'] for e in specs))
        for mode in ['locked','empty','compile-fail','compile-mutate','raise']:
            with self.subTest(mode=mode):self.run_fixture(mode,core_tests.MODE_OBSERVABILITY)

    def test_observability_package_projection_rejects_wrong_new_package_source_and_version(self):
        with tempfile.TemporaryDirectory() as directory:
            up=Path(directory);packages=[];nodes=[]
            for name in core_tests.packages_for_mode(core_tests.MODE_OBSERVABILITY):
                packages.append(dict(id=name,name=name,version='0.26.1',manifest_path=str(up/'crates'/name/'Cargo.toml')))
                nodes.append(dict(id=name,features=[]))
            metadata=dict(packages=packages,resolve=dict(nodes=nodes))
            self.assertEqual(8,len(core_tests.feature_matrix(metadata,up,core_tests.MODE_OBSERVABILITY)))
            for name in ['meow-api','meow-tunnel','meow-listener']:
                package=next(p for p in packages if p['name']==name);original=dict(package)
                for key,value in [('manifest_path',str(up/'outside/Cargo.toml')),('version','0.26.2')]:
                    package[key]=value
                    with self.subTest(package=name,key=key),self.assertRaises(ValueError):core_tests.feature_matrix(metadata,up,core_tests.MODE_OBSERVABILITY)
                    package.update(original)

    def test_provider_general_requires_source26_exact75_and_locked_compile_restore(self):
        specs=core_tests.entries(core_tests.MODE_PROVIDER_GENERAL)
        self.assertEqual(26,len(specs));self.assertEqual(75,sum(e['expectedPass'] for e in specs))
        self.assertEqual(12,specs[0]['expectedPass'])
        self.assertEqual(11,core_tests.entries(core_tests.MODE_OBSERVABILITY)[0]['expectedPass'])
        exact=next(e for e in specs if e.get('exact'))
        self.assertEqual('proxy_provider::tests::provider_strict_refresh_keeps_last_good_on_bad_dialer',exact['filter'])
        self.assertEqual('--exact',core_tests.entry_command(exact,[])[-1])
        for mode in ['locked','empty','compile-fail','compile-mutate','raise']:
            with self.subTest(mode=mode):self.run_fixture(mode,core_tests.MODE_PROVIDER_GENERAL)
        with tempfile.TemporaryDirectory() as directory:
            up=Path(directory);packages=[];nodes=[]
            for name in core_tests.packages_for_mode(core_tests.MODE_PROVIDER_GENERAL):
                packages.append(dict(id=name,name=name,version='0.26.1',manifest_path=str(up/'crates'/name/'Cargo.toml')))
                nodes.append(dict(id=name,features=[]))
            metadata=dict(packages=packages,resolve=dict(nodes=nodes))
            self.assertEqual(9,len(core_tests.feature_matrix(metadata,up,core_tests.MODE_PROVIDER_GENERAL)))
            config=next(p for p in packages if p['name']=='meow-config')
            config['manifest_path']=str(up/'wrong/Cargo.toml')
            with self.assertRaises(ValueError):core_tests.feature_matrix(metadata,up,core_tests.MODE_PROVIDER_GENERAL)

    def test_resource_health_preserves26_commands75_hits_and_compile_lock_gates(self):
        self.assertEqual(core_tests.entries(core_tests.MODE_PROVIDER_GENERAL),core_tests.entries(core_tests.MODE_RESOURCE_HEALTH))
        for mode in ['locked','empty','compile-fail','compile-mutate','raise']:
            with self.subTest(mode=mode):self.run_fixture(mode,core_tests.MODE_RESOURCE_HEALTH)

    def test_mrs_reader_keeps_all_previous_commands_and_adds46_with_format_lock_gates(self):
        specs=core_tests.entries(core_tests.MODE_MRS_READER)
        self.assertEqual(core_tests.CASES_PROVIDER_GENERAL,specs[:26])
        self.assertEqual((29,121),(len(specs),sum(e['expectedPass'] for e in specs)))
        self.assertEqual([('mrs_parser::tests::',30,False),('rule_set::tests::',15,False),('rule_set::plain_domain_validation_regression::invalid_plain_pattern_is_not_an_accepted_rule',1,True)],
            [(e['filter'],e['expectedPass'],e['exact']) for e in specs[26:]])
        self.assertEqual('--exact',core_tests.entry_command(specs[28],[])[-1])
        self.assertEqual({'meow-rules'},set(core_tests.packages_for_mode(core_tests.MODE_MRS_READER))-set(core_tests.packages_for_mode(core_tests.MODE_RESOURCE_HEALTH)))
        for mode in ['locked','empty','compile-fail','compile-mutate','format-fail','format-mutate','raise']:
            with self.subTest(mode=mode):self.run_fixture(mode,core_tests.MODE_MRS_READER)

    def test_provider_reader_keeps_all_mrs_commands_adds56_and_both_format_gates(self):
        specs=core_tests.entries(core_tests.MODE_PROVIDER_READER)
        self.assertEqual(core_tests.CASES_MRS_READER,specs[:29])
        self.assertEqual((31,177),(len(specs[:31]),sum(e['expectedPass'] for e in specs[:31])))
        self.assertEqual((32,179),(len(specs),sum(e['expectedPass'] for e in specs)))
        self.assertEqual(dict(package='meow-rules',filter='mrs_parser::host_admission_index_tests::',expectedPass=2,target='lib',requiredFeatures=[],exact=False),specs[31])
        self.assertEqual(['cargo','test','--manifest-path',core_tests.MANIFEST,'--locked','--package','meow-rules','--lib','--no-default-features','mrs_parser::host_admission_index_tests::','--','--test-threads=1','--nocapture'],core_tests.entry_command(specs[31],[]))
        for historical in [core_tests.MODE_MRS_READER,core_tests.MODE_RESOURCE_HEALTH,core_tests.MODE_PROVIDER_GENERAL,core_tests.MODE_OBSERVABILITY,core_tests.MODE_V2,core_tests.MODE_TRAFFIC,core_tests.MODE]:
            self.assertNotIn(specs[31],core_tests.entries(historical))
        self.assertEqual([('meow-config','rule_provider::reader::tests::reader15_',15,'lib',[],False),('meow-config','rule_provider::tests::',41,'lib',[],False)],
            [(e['package'],e['filter'],e['expectedPass'],e['target'],e['requiredFeatures'],e['exact']) for e in specs[29:31]])
        self.assertEqual(core_tests.packages_for_mode(core_tests.MODE_MRS_READER),core_tests.packages_for_mode(core_tests.MODE_PROVIDER_READER))
        self.assertEqual(['rustfmt','--check','--edition','2021','.upstream/meow-rs/crates/meow-rules/src/mrs_parser.rs','.upstream/meow-rs/crates/meow-rules/src/rule_set.rs'],core_tests.MRS_FORMAT_COMMAND)
        self.assertEqual(['rustfmt','--check','--edition','2021']+['.upstream/meow-rs/crates/meow-config/src/'+name for name in ['rule_provider.rs','rule_pages.rs','rule_provider_reader.rs','rule_provider_reader_tests.rs']],core_tests.PROVIDER_READER_FORMAT_COMMAND)
        self.assertEqual((core_tests.MODE_RESOURCE_HEALTH,core_tests.MODE_MRS_READER,core_tests.MODE_PROVIDER_READER,core_tests.MODE_SELECTION_OWNER),core_tests.FULL_NATIVE_SUITE_MODES)
        for mode in ['success','locked','empty','nonzero','mutate','compile-fail','compile-mutate','format-fail','format-fail-provider','format-mutate','format-undeclared','raise','empty-host-admission','extra-host-admission','duplicate-host-admission']:
            with self.subTest(mode=mode):self.run_fixture(mode,core_tests.MODE_PROVIDER_READER)

    def test_selection_owner_extends_unchanged_reader_contract_and_reuses_strict_execution(self):
        old=core_tests.entries(core_tests.MODE_PROVIDER_READER)
        new=core_tests.entries(core_tests.MODE_SELECTION_OWNER)
        self.assertEqual((32,179),(len(old),sum(row['expectedPass'] for row in old)))
        self.assertEqual(old,new[:len(old)])
        self.assertEqual((35,182),(len(new),sum(row['expectedPass'] for row in new)))
        self.assertEqual([{'package': 'meow-proxy', 'filter': 'group::fallback::tests::startup_stale_raw_cache_selects_the_real_dead_first_for_nested_tcp_and_udp', 'expectedPass': 1, 'target': 'lib', 'requiredFeatures': [], 'exact': True}, {'package': 'meow-proxy', 'filter': 'group::fallback::tests::startup_owner_consumes_dead_middle_once_then_business_reads_and_dials_use_auto', 'expectedPass': 1, 'target': 'lib', 'requiredFeatures': [], 'exact': True}, {'package': 'meow-proxy', 'filter': 'group::fallback::tests::startup_compatibility_does_not_change_native_unpinned_auto_or_explicit_unfix', 'expectedPass': 1, 'target': 'lib', 'requiredFeatures': [], 'exact': True}],new[32:])
        self.assertEqual(3,len(new[len(old):]))
        for row in new[len(old):]:
            self.assertTrue(row['exact']);self.assertEqual(1,row['expectedPass'])
            self.assertEqual('meow-proxy',row['package']);self.assertEqual('lib',row['target'])
        for outcome in ['locked','empty','nonzero','mutate','compile-fail','compile-mutate','format-fail','format-fail-provider','format-mutate','format-undeclared','empty-host-admission','extra-host-admission','duplicate-host-admission']:
            with self.subTest(outcome=outcome):self.run_fixture(outcome,core_tests.MODE_SELECTION_OWNER)

    def test_format_gate_files_are_exactly_what_rustfmt_follows_or_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);up=root/'.upstream/meow-rs';write_format_sources(up)
            for command in [core_tests.MRS_FORMAT_COMMAND,core_tests.PROVIDER_READER_FORMAT_COMMAND]:
                self.assertEqual({name:core_tests.digest((root/name).read_bytes()) for name in command[4:]},core_tests.format_files(root,command))
            pages='.upstream/meow-rs/crates/meow-config/src/rule_pages.rs'
            for bad in [[name for name in core_tests.PROVIDER_READER_FORMAT_COMMAND if name!=pages],core_tests.PROVIDER_READER_FORMAT_COMMAND+[pages],
                    core_tests.PROVIDER_READER_FORMAT_COMMAND[:5]]:
                with self.subTest(declared=bad[4:]),self.assertRaisesRegex(ValueError,'does not declare every file rustfmt checks'):core_tests.format_files(root,bad)
            provider='crates/meow-config/src/rule_provider.rs';reader='crates/meow-config/src/rule_provider_reader.rs'
            for name,extra in [(provider,'mod plain;\n'),(provider,'#[cfg(test)]\nmod hidden;\n'),(provider,'#[cfg(test)] #[path = "rule_pages.rs"] mod inline;\n'),
                    (provider,'#[path = "missing.rs"]\nmod missing;\n'),(provider,'#[path = "../escape.rs"]\nmod escape;\n'),
                    (provider,'#[path = "rule_pages.rs"]\n#[path = "rule_provider_reader.rs"]\nmod twice;\n'),
                    (reader,'mod nested {\n    #[path = "rule_pages.rs"]\n    mod again;\n}\n'),(reader,'#[cfg_attr(test, path = "rule_pages.rs")]\nmod conditional;\n')]:
                with self.subTest(file=name,extra=extra),tempfile.TemporaryDirectory() as other:
                    copy=Path(other);write_format_sources(copy/'.upstream/meow-rs',dict(FORMAT_SOURCES,**{name:FORMAT_SOURCES[name]+extra}))
                    (copy/'.upstream/meow-rs/crates/meow-config/escape.rs').write_text('')
                    with self.assertRaisesRegex(ValueError,'cannot resolve an out-of-line module'):core_tests.format_files(copy,core_tests.PROVIDER_READER_FORMAT_COMMAND)

class BuildContractTests(unittest.TestCase):
    """build.py is owned by the native domain; these pin what this admission needs from it (build-py-requirements.json)."""
    def drive(self,mode):
        import build
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'Cargo.toml').write_text('[package]\nname="aerove-meow-core"\n[lib]\nname="aerove_meow_core"\n[dependencies]\n')
            (root/'Cargo.lock').write_bytes(b'accepted lock\n');(root/'source.json').write_text('{}')
            upstream=root/'source';upstream.mkdir();(upstream/'AEROVE_SOURCE_PROVENANCE.json').write_text('{}')
            full=mode in core_tests.FULL_NATIVE_SUITE_MODES
            targets=list(build_policy.RESOURCE_HEALTH_TARGETS) if full else ['lib','proxy_runtime']
            policy=dict(resolveLock=False,expectedLockBeforeSha256=core_tests.digest(b'accepted lock\n'),targets=targets,
                policySha256='fixture policy',coreTests=mode,coreTestLockSha256='a'*64)
            (root/'tests').mkdir()
            for target in targets[1:]:(root/'tests'/f'{target}.rs').write_text('// compile fixture only\n')
            output=''.join('test fixture::'+name+' ... ok\n' for cases in build.native_test_cases(mode).values() for name in cases)
            calls=dict(jni=[],suite=[],core=[])
            def run(command,environment,capture=False):
                if command[0]=='rustc':return 'rustc 1.98.1 fixture'
                calls['suite'].append((command,capture))
                return output if capture else None
            def core(core_root,core_policy,environment,receipt):calls['core'].append(json.loads(json.dumps(receipt)))
            with patch.object(build,'ROOT',root),patch.object(build,'load_policy',return_value=policy),patch.object(build,'prepare',return_value=upstream),\
                    patch.object(build,'probe_libclang',return_value={'library':{'path':'unit-test-fixture-only'}}),patch.object(build,'run',side_effect=run),\
                    patch.object(build,'execute_core_tests',side_effect=core),patch.object(build,'verify_resource_jni_source',side_effect=calls['jni'].append),\
                    patch('builtins.print'),patch.object(sys,'argv',['build.py']):
                build.main()
            return build,root,full,calls,json.loads((root/'build/host-receipt.json').read_text())

    def test_each_full_suite_mode_runs_the_jni_source_gate_native_proof_and_all42_targets_before_core_tests(self):
        self.assertEqual((core_tests.MODE_RESOURCE_HEALTH,core_tests.MODE_MRS_READER,core_tests.MODE_PROVIDER_READER,core_tests.MODE_SELECTION_OWNER),core_tests.FULL_NATIVE_SUITE_MODES)
        for mode in core_tests.FULL_NATIVE_SUITE_MODES+(core_tests.MODE_PROVIDER_GENERAL,):
            with self.subTest(mode=mode):
                build,root,full,calls,receipt=self.drive(mode)
                self.assertEqual(full,build_policy.full_native_suite(dict(coreTests=mode)))
                self.assertEqual([root] if full else [],calls['jni'],'build.py must run verify_resource_jni_source exactly for full-suite modes')
                if full:
                    self.assertEqual((['cargo','build','--locked','--tests','--keep-going'],False),calls['suite'][0])
                    self.assertEqual(0,receipt['compileAggregationExit'])
                suite,capture=calls['suite'][1 if full else 0]
                self.assertEqual(full,capture,'build.py must capture the full Native suite output for the added-test proof')
                self.assertEqual(sorted(build_policy.RESOURCE_HEALTH_TARGETS[1:]) if full else ['proxy_runtime'],sorted(suite[i+1] for i,arg in enumerate(suite) if arg=='--test'))
                proof=build.native_test_cases(mode) if full else None
                self.assertEqual(proof,receipt.get('addedNativeTests'),'build.py must record addedNativeTests for full-suite modes')
                self.assertEqual(1,len(calls['core']));self.assertEqual(proof,calls['core'][0].get('addedNativeTests'))
                self.assertEqual((0,True),(receipt['commandExit'],receipt['acceptedLock']))

    def test_full_suite_predicate_and_policy_loader_require_every_native_target_per_mode(self):
        self.assertFalse(build_policy.full_native_suite(None))
        for mode in [core_tests.MODE,core_tests.MODE_V2,core_tests.MODE_TRAFFIC,core_tests.MODE_OBSERVABILITY,core_tests.MODE_PROVIDER_GENERAL,'unknown']:
            self.assertFalse(build_policy.full_native_suite(dict(coreTests=mode)),mode)
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'Cargo.toml').write_bytes(b'manifest');(root/'Cargo.lock').write_bytes(b'lock')
            (root/'patches').mkdir();(root/'patches/p.patch').write_bytes(b'patch');(root/'source-metadata').mkdir()
            overlay=dict(schema=1,patch='patches/p.patch',patchSha256=core_tests.digest(b'patch'),rawFileManifestSha256='c'*64,
                adaptedFileManifestSha256=core_tests.digest(b'{}'),changes={},files={})
            metadata=(json.dumps(overlay)+'\n').encode();(root/'source-metadata/m.json').write_bytes(metadata)
            base=dict(schema=1,manifestSha256=core_tests.digest(b'manifest'),resolveLock=False,expectedLockBeforeSha256=core_tests.digest(b'lock'),
                targets=list(build_policy.RESOURCE_HEALTH_TARGETS),overlay='source-metadata/m.json',overlaySha256=core_tests.digest(metadata),coreTestLockSha256='a'*64)
            for mode in core_tests.FULL_NATIVE_SUITE_MODES:
                (root/'build-policy.json').write_text(json.dumps(dict(base,coreTests=mode)))
                if mode == core_tests.MODE_SELECTION_OWNER:
                    with patch.object(build_policy,'SELECTION_OWNER_OVERLAY_SHA256',core_tests.digest(metadata)):
                        self.assertTrue(build_policy.full_native_suite(build_policy.load_policy(root)))
                else:self.assertTrue(build_policy.full_native_suite(build_policy.load_policy(root)))
                for omitted in build_policy.RESOURCE_HEALTH_TARGETS[1:]:
                    (root/'build-policy.json').write_text(json.dumps(dict(base,coreTests=mode,targets=[t for t in base['targets'] if t!=omitted])))
                    with self.subTest(mode=mode,omitted=omitted),self.assertRaisesRegex(ValueError,'every approved Native target'):build_policy.load_policy(root)

    def test_provider_reader_policy_requires_all42_native_targets(self):
        targets=list(build_policy.RESOURCE_HEALTH_TARGETS);self.assertEqual(42,len(targets))
        policy=dict(resolveLock=False,coreTests=core_tests.MODE_PROVIDER_READER,coreTestLockSha256='a'*64)
        self.assertTrue(core_tests.validate_policy(dict(policy,targets=targets)))
        for bad in [None,targets[:-1],targets+['arbitrary'],list(reversed(targets)),tuple(targets)]+[[t for t in targets if t!=omitted] for omitted in targets[1:]]:
            with self.subTest(targets=bad),self.assertRaisesRegex(ValueError,'every approved Native target'):
                core_tests.validate_policy(dict(policy) if bad is None else dict(policy,targets=bad))
        with self.assertRaisesRegex(ValueError,'accepted Core test lock'):core_tests.validate_policy(dict(policy,coreTestLockSha256=None,targets=targets))

    def test_provider_reader_rejects_missing_native_suite_proof_before_any_command(self):
        for proof in [None,{},[],'proof']:
            with self.subTest(proof=proof),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);up=root/'.upstream/meow-rs';up.mkdir(parents=True);(up/'Cargo.lock').write_bytes(b'actual-upstream-lock')
                receipt={} if proof is None else dict(addedNativeTests=proof);commands=[]
                policy=dict(resolveLock=False,coreTests=core_tests.MODE_PROVIDER_READER,coreTestLockSha256='a'*64,targets=list(build_policy.RESOURCE_HEALTH_TARGETS))
                with patch.object(core_tests,'run_logged',lambda args,*a,**kwargs:commands.append(args)):
                    with self.assertRaisesRegex(ValueError,'full Native suite proof'):core_tests.execute(root,policy,{},receipt)
                self.assertEqual([],commands);self.assertFalse(receipt['coreTests']['accepted'])
                self.assertTrue(receipt['coreTests']['upstreamLockRestored']);self.assertEqual(b'actual-upstream-lock',(up/'Cargo.lock').read_bytes())
                self.assertEqual(json.loads((root/'build/core-tests/receipt.json').read_bytes()),receipt['coreTests'])

if __name__=='__main__':unittest.main()
