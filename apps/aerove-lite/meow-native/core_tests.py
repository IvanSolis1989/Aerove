"""Fixed dependency-unit admission for the policy runtime graph, with a separate real Cargo lock."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import os
import sys
import signal
import threading
import time
from datetime import datetime, timezone

CORE_COMMAND_TIMEOUT=15*60
COMMAND_TERM_GRACE=10

class CommandCancelled(KeyboardInterrupt):
    def __init__(self,signum):
        super().__init__('Host command cancelled by signal '+str(signum));self.returncode=128+signum

def write_progress(path,value):
    path=Path(path);temporary=path.with_name(path.name+'.tmp')
    temporary.write_bytes((json.dumps(value,indent=2)+'\n').encode());temporary.replace(path)

def _owned_alive(process,state):
    if os.name!='posix':return process.poll() is None
    try:os.killpg(state['processGroup'],0);return True
    except ProcessLookupError:return False

def _process_identity(pid):
    if not sys.platform.startswith('linux'):return None
    try:
        fields=Path('/proc',str(pid),'stat').read_text().rsplit(')',1)[1].split()
        return dict(pid=pid,state=fields[0],group=int(fields[2]),session=int(fields[3]),startTicks=int(fields[19]))
    except FileNotFoundError:return None


def _group_outcome(process,state):
    if os.name!='posix':return dict(status='direct-child-exited' if process.poll() is not None else 'running',members=[])
    if not _owned_alive(process,state):return dict(status='absent',members=[])
    if not sys.platform.startswith('linux'):return dict(status='unknown',members=[])
    try:
        members=[]
        for item in Path('/proc').iterdir():
            if item.name.isdecimal():
                member=_process_identity(int(item.name))
                if member is not None and member['group']==state['processGroup']:members.append(member)
        if not members:return dict(status='absent' if not _owned_alive(process,state) else 'unknown',members=[])
        return dict(status='zombie' if all(m['state']=='Z' for m in members) else 'running',members=members)
    except OSError:return dict(status='unknown',members=[])


def _stop_owned(process,state,grace,force):
    def send(sig):
        try:
            if os.name=='posix':
                group=state['processGroup']
                if group!=process.pid or group==os.getpgrp():raise RuntimeError('hostCommandGroupOwnershipMismatch')
                identity=_process_identity(process.pid)
                if identity is not None and (identity['group']!=group or identity['session']!=group or state.get('leaderIdentity') not in (None,identity['startTicks'])):
                    raise RuntimeError('hostCommandGroupIdentityChanged')
                os.killpg(group,sig)
            elif sig==signal.SIGTERM:process.terminate()
            else:process.kill()
            state['signals'].append('SIGTERM' if sig==signal.SIGTERM else 'SIGKILL')
        except ProcessLookupError:pass
    if _owned_alive(process,state):
        send(signal.SIGTERM);deadline=time.monotonic()+grace
        while time.monotonic()<deadline and not force.is_set():
            process.poll()
            if not _owned_alive(process,state):break
            time.sleep(.02)
        # The leader may already have exited while an inherited-group grandchild remains.
        if _owned_alive(process,state):send(getattr(signal,'SIGKILL',9))
    try:process.wait(timeout=2)
    except subprocess.TimeoutExpired:return False
    deadline=time.monotonic()+2
    while _owned_alive(process,state) and time.monotonic()<deadline:time.sleep(.02)
    return not _owned_alive(process,state)

def run_logged(args,cwd,environment,stdout_path,stderr_path,*,entry=None,update=None,timeout=CORE_COMMAND_TIMEOUT,grace=COMMAND_TERM_GRACE):
    """Keep raw streams and the owned process-group outcome even when a command is interrupted."""
    cwd=Path(cwd);stdout_path=Path(stdout_path);stderr_path=Path(stderr_path)
    stdout_path.parent.mkdir(parents=True,exist_ok=True);stderr_path.parent.mkdir(parents=True,exist_ok=True)
    state=entry if entry is not None else {};progress=Path(str(stdout_path)+'.progress.json')
    state.update(command=list(args),cwd=str(cwd.resolve()),stdoutPath=str(stdout_path.resolve()),stderrPath=str(stderr_path.resolve()),
        startedAt=datetime.now(timezone.utc).isoformat(),endedAt=None,status='starting',pid=None,processGroup=None,
        timeoutSeconds=timeout,exit=None,signals=[],cleanupConfirmed=False,stdoutBytes=0,stderrBytes=0)
    def persist():
        write_progress(progress,state)
        if update is not None:update()
    process=None;failure=None;readers=[];reader_errors=[];stopping=threading.Event();force=threading.Event();pending=[];handlers={}
    def interrupted(signum,frame):
        if stopping.is_set():force.set()
        elif process is None or state['pid'] is None:pending.append(signum)
        else:raise CommandCancelled(signum)
    def pump(pipe,destination,console,key):
        try:
            while chunk:=pipe.read(65536):
                destination.write(chunk);destination.flush();state[key]+=len(chunk)
                binary=getattr(console,'buffer',None)
                if binary is not None:binary.write(chunk);binary.flush()
                else:console.write(chunk.decode('utf-8',errors='replace'));console.flush()
        except BaseException as error:reader_errors.append(error)
        finally:pipe.close()
    with stdout_path.open('wb') as output,stderr_path.open('wb') as errors:
        try:
            persist()
            if threading.current_thread() is threading.main_thread():
                for sig in (signal.SIGINT,signal.SIGTERM):handlers[sig]=signal.signal(sig,interrupted)
            options=dict(start_new_session=True) if os.name=='posix' else dict(creationflags=subprocess.IDLE_PRIORITY_CLASS|subprocess.CREATE_NO_WINDOW)
            if os.name=='posix':os.setpriority(os.PRIO_PROCESS,0,19)
            if pending:raise CommandCancelled(pending[0])
            process=subprocess.Popen(args,cwd=cwd,env=environment,stdout=subprocess.PIPE,stderr=subprocess.PIPE,bufsize=0,**options)
            state.update(pid=process.pid,processGroup=process.pid if os.name=='posix' else None,status='running')
            identity=_process_identity(process.pid);state['leaderIdentity']=identity['startTicks'] if identity is not None else None
            persist()
            for pipe,destination,console,key in [(process.stdout,output,sys.stdout,'stdoutBytes'),(process.stderr,errors,sys.stderr,'stderrBytes')]:
                reader=threading.Thread(target=pump,args=(pipe,destination,console,key),daemon=True);reader.start();readers.append(reader)
            if pending:raise CommandCancelled(pending[0])
            deadline=None if timeout is None else time.monotonic()+timeout
            while True:
                if reader_errors:raise reader_errors[0]
                remaining=None if deadline is None else deadline-time.monotonic()
                if remaining is not None and remaining<=0:raise subprocess.TimeoutExpired(args,timeout)
                try:process.wait(timeout=.1 if remaining is None else min(.1,remaining));break
                except subprocess.TimeoutExpired:pass
            state['status']='completed' if process.returncode==0 else 'failed'
        except BaseException as error:
            failure=error;state['status']='timed-out' if isinstance(error,subprocess.TimeoutExpired) else 'cancelled' if isinstance(error,KeyboardInterrupt) else 'failed'
            state['failureType']=type(error).__name__
        finally:
            stopping.set()
            try:
                try:state['cleanupConfirmed']=process is None or _stop_owned(process,state,grace,force)
                except BaseException as error:
                    state['cleanupError']=type(error).__name__+': '+str(error)
                    if failure is None:failure=error;state['status']='failed'
                if process is not None:
                    try:state['groupOutcome']=_group_outcome(process,state)
                    except OSError as error:state['groupOutcome']=dict(status='unknown',error=str(error))
                    if state['groupOutcome']['status'] in ('running','zombie','unknown'):state['cleanupConfirmed']=False
                for reader in readers:reader.join(2)
                state['cleanupConfirmed'] &= not any(reader.is_alive() for reader in readers)
                if reader_errors and failure is None:failure=reader_errors[0];state['status']='failed'
                if not state['cleanupConfirmed'] and failure is None:failure=RuntimeError('hostCommandCleanupUnconfirmed');state['status']='failed'
                if failure is not None:state['failureType']=type(failure).__name__
                state.update(exit=process.returncode if process is not None else None,endedAt=datetime.now(timezone.utc).isoformat())
                output.flush();errors.flush();persist()
            finally:
                for sig,handler in handlers.items():signal.signal(sig,handler)
    stdout=stdout_path.read_bytes();stderr=stderr_path.read_bytes()
    if failure is not None:
        if isinstance(failure,subprocess.TimeoutExpired):failure.output=stdout;failure.stderr=stderr
        raise failure
    return subprocess.CompletedProcess(args,state['exit'],stdout,stderr)

MODE='policy-runtime-v1'
MODE_V2='policy-runtime-v2'
MODE_TRAFFIC='policy-runtime-traffic-v1'
MODE_OBSERVABILITY='policy-runtime-observability-v1'
MODE_PROVIDER_GENERAL='policy-runtime-provider-general-v1'
MODE_RESOURCE_HEALTH='policy-runtime-resource-health-v1'
MODE_MRS_READER='policy-runtime-mrs-reader-v1'
MODE_PROVIDER_READER='policy-runtime-provider-reader-v1'
MODE_SELECTION_OWNER='policy-runtime-selection-owner-v1'
CASES=[
    ('meow-common',name) for name in [
        'cli_without_scope_keeps_output_and_real_join_error','observer_does_not_consume_protocol_output_or_panic',
        'protocol_can_consume_error_before_native_observer','abort_handle_targets_actual_work_and_real_socket_reaches_eof',
        'dropped_protocol_handle_remains_owned_until_actual_join','cancelled_join_waiter_retains_actual_handle_for_retry',
        'closed_admission_drops_future_without_polling_user_work','nested_task_captures_same_scope_without_an_observer_task',
        'ready_then_session_cancel_has_actual_completed_ack','captured_closed_session_never_adopts_another_current_owner',
        'send_only_user_future_keeps_scoped_join_send_sync']
]+[
    ('meow-proxy','actual_tasked_duplex_preserves_stream_and_proxy_conversion'),
    ('meow-proxy','actual_vmess_read_write_supervisor_are_joined_before_transport_eof_ack'),
    ('meow-proxy','closed_session_rejects_vmess_before_any_relay_task_is_spawned'),
    ('meow-transport','actual_stream_preserves_send_sync_transport_contract'),
    ('meow-transport','closed_origin_upgrade_has_repeatable_error_and_zero_http_bytes'),
    ('meow-anytls','actual_pool_reaper_drop_aborts_and_native_observer_joins_resource_release'),
    ('meow-anytls','pooled_close_cancelled_after_admission_can_resume_and_last_arc_drops_both_transports'),
    ('meow-anytls','concurrent_close_waiter_observes_completion_and_takes_over_cancelled_first_attempt'),
    ('meow-dns','resolver_captures_generation_scope_when_constructed'),
    ('meow-dns','upstream_exchange_stays_with_captured_generation'),
    ('meow-dns','retiring_generation_cancels_and_joins_pending_exchange'),
]
# The transport contract leaf is intentionally shared by three modules; its exact expected count is3.
COUNTS={('meow-transport','actual_stream_preserves_send_sync_transport_contract'):3}
PACKAGES=sorted({package for package,_ in CASES})
MANIFEST='.upstream/meow-rs/Cargo.toml'
# V1 remains the historical22-command contract. V2 follows the authored task-leaf migration table.
CASES_V2=[
    dict(package='meow-transport',filter='scoped_join::',expectedPass=11,target='lib',requiredFeatures=['ws','grpc','h2','xhttp']),
    dict(package='meow-transport',filter='actual_stream_preserves_send_sync_transport_contract',expectedPass=3,target='lib',requiredFeatures=['ws','grpc','h2','xhttp']),
    dict(package='meow-transport',filter='closed_origin_upgrade_has_repeatable_error_and_zero_http_bytes',expectedPass=1,target='lib',requiredFeatures=['ws','grpc','h2','xhttp']),
    dict(package='meow-proxy',filter='vmess::conn::scoped_owner_tests::',expectedPass=2,target='lib',requiredFeatures=['vmess']),
    dict(package='meow-proxy',filter='actual_tasked_duplex_preserves_stream_and_proxy_conversion',expectedPass=1,target='lib',requiredFeatures=['vmess']),
    dict(package='meow-anytls',filter='actual_pool_reaper_drop_aborts_and_native_observer_joins_resource_release',expectedPass=1,target='lib',requiredFeatures=[]),
    dict(package='meow-common',filter=None,expectedPass=3,target='transport_scope_compat',requiredFeatures=['aerove-android-runtime']),
]+[dict(package=package,filter=name,expectedPass=1,target='lib',requiredFeatures=[]) for package,name in CASES if name in {
    'pooled_close_cancelled_after_admission_can_resume_and_last_arc_drops_both_transports',
    'concurrent_close_waiter_observes_completion_and_takes_over_cancelled_first_attempt',
    'resolver_captures_generation_scope_when_constructed','upstream_exchange_stays_with_captured_generation',
    'retiring_generation_cancels_and_joins_pending_exchange'}]+[
    dict(package='meow-dns',filter='resolver::tests::ech_cycle_guard_survives_dns_owned_spawn',expectedPass=1,target='lib',requiredFeatures=['encrypted']),
    dict(package='meow-dns',filter='resolver::owned_generation_tests::ech_cycle_guard_survives_run_owned_query_spawn',expectedPass=1,target='lib',requiredFeatures=['encrypted']),
]

CASES_OBSERVABILITY=CASES_V2+[
    dict(package=p,filter='observations_',expectedPass=n,target='lib',requiredFeatures=required)
    for p,n,required in [('meow-common',5,['aerove-android-runtime']),
        ('meow-dns',6,['aerove-android-runtime','encrypted']),('meow-tunnel',6,[]),('meow-api',1,[])]
]
# New diagnostic test is also covered by the retained broad scoped_join:: filter.
CASES_PROVIDER_GENERAL=[dict(entry,expectedPass=12 if index==0 else entry['expectedPass'],exact=False)
    for index,entry in enumerate(CASES_OBSERVABILITY)]+[{'package': 'meow-config', 'filter': 'proxy_provider::tests::companion_', 'expectedPass': 2, 'target': 'lib', 'requiredFeatures': [], 'exact': False}, {'package': 'meow-config', 'filter': 'proxy_provider::tests::provider_strict_refresh_keeps_last_good_on_bad_dialer', 'expectedPass': 1, 'target': 'lib', 'requiredFeatures': [], 'exact': True}, {'package': 'meow-common', 'filter': 'diagnostic_', 'expectedPass': 2, 'target': 'lib', 'requiredFeatures': [], 'exact': False}, {'package': 'meow-config', 'filter': 'diagnostic_', 'expectedPass': 1, 'target': 'lib', 'requiredFeatures': [], 'exact': False}, {'package': 'meow-proxy', 'filter': 'diagnostic_', 'expectedPass': 10, 'target': 'lib', 'requiredFeatures': [], 'exact': False}, {'package': 'meow-transport', 'filter': 'diagnostic_', 'expectedPass': 3, 'target': 'lib', 'requiredFeatures': [], 'exact': False}, {'package': 'meow-config', 'filter': 'managed_dependency_', 'expectedPass': 3, 'target': 'lib', 'requiredFeatures': [], 'exact': False}, {'package': 'meow-config', 'filter': 'proxy_provider::tests::projected_', 'expectedPass': 5, 'target': 'lib', 'requiredFeatures': [], 'exact': False}]
CASES_MRS_READER=CASES_PROVIDER_GENERAL+[
    dict(package='meow-rules',filter='mrs_parser::tests::',expectedPass=30,target='lib',requiredFeatures=[],exact=False),
    dict(package='meow-rules',filter='rule_set::tests::',expectedPass=15,target='lib',requiredFeatures=[],exact=False),
    # The overlay's rule_set regression lives outside rule_set::tests, so it is admitted by its exact libtest path.
    dict(package='meow-rules',filter='rule_set::plain_domain_validation_regression::invalid_plain_pattern_is_not_an_accepted_rule',expectedPass=1,target='lib',requiredFeatures=[],exact=True),
]
# Provider reader keeps every MRS command and adds the real reader15 module and the unchanged 41 provider tests.
CASES_PROVIDER_READER=CASES_MRS_READER+[
    dict(package='meow-config',filter='rule_provider::reader::tests::reader15_',expectedPass=15,target='lib',requiredFeatures=[],exact=False),
    dict(package='meow-config',filter='rule_provider::tests::',expectedPass=41,target='lib',requiredFeatures=[],exact=False),
    dict(package='meow-rules',filter='mrs_parser::host_admission_index_tests::',expectedPass=2,target='lib',requiredFeatures=[],exact=False),
]
MRS_FORMAT_COMMAND=['rustfmt','--check','--edition','2021',
    '.upstream/meow-rs/crates/meow-rules/src/mrs_parser.rs',
    '.upstream/meow-rs/crates/meow-rules/src/rule_set.rs']
# Selection-owner retains the complete historical reader proof and adds three exact startup cases.
CASES_SELECTION_OWNER = CASES_PROVIDER_READER + [{'package': 'meow-proxy', 'filter': 'group::fallback::tests::startup_stale_raw_cache_selects_the_real_dead_first_for_nested_tcp_and_udp', 'expectedPass': 1, 'target': 'lib', 'requiredFeatures': [], 'exact': True}, {'package': 'meow-proxy', 'filter': 'group::fallback::tests::startup_owner_consumes_dead_middle_once_then_business_reads_and_dials_use_auto', 'expectedPass': 1, 'target': 'lib', 'requiredFeatures': [], 'exact': True}, {'package': 'meow-proxy', 'filter': 'group::fallback::tests::startup_compatibility_does_not_change_native_unpinned_auto_or_explicit_unfix', 'expectedPass': 1, 'target': 'lib', 'requiredFeatures': [], 'exact': True}]

# rustfmt also checks every out-of-line module a listed file declares, so each #[path] child is listed too.
PROVIDER_READER_FORMAT_COMMAND=['rustfmt','--check','--edition','2021',
    '.upstream/meow-rs/crates/meow-config/src/rule_provider.rs',
    '.upstream/meow-rs/crates/meow-config/src/rule_pages.rs',
    '.upstream/meow-rs/crates/meow-config/src/rule_provider_reader.rs',
    '.upstream/meow-rs/crates/meow-config/src/rule_provider_reader_tests.rs']
COMPILE_COMMAND=['cargo','check','--manifest-path',MANIFEST,'--locked','--package','meow-api',
    '--package','meow-listener','--all-features']
# Modes whose build must run all 42 Native targets, the resource JNI source gate and the added Native test proof.
FULL_NATIVE_SUITE_MODES=(MODE_RESOURCE_HEALTH,MODE_MRS_READER,MODE_PROVIDER_READER,MODE_SELECTION_OWNER)

def packages_for_mode(mode):
    if mode in (MODE_MRS_READER,MODE_PROVIDER_READER,MODE_SELECTION_OWNER):return sorted(set(packages_for_mode(MODE_RESOURCE_HEALTH))|{'meow-rules'})
    if mode in (MODE_PROVIDER_GENERAL,MODE_RESOURCE_HEALTH):return sorted(set(PACKAGES)|{'meow-tunnel','meow-api','meow-listener','meow-config'})
    if mode==MODE_OBSERVABILITY:return sorted(set(PACKAGES)|{'meow-tunnel','meow-api','meow-listener'})
    if mode in (MODE,MODE_V2,MODE_TRAFFIC):return PACKAGES
    raise ValueError('Unknown Core unit test admission')

def digest(data):return hashlib.sha256(data).hexdigest()

MOD_ITEM=re.compile(r'\bmod\s+[A-Za-z_][A-Za-z0-9_]*\s*;')
PATH_MOD=re.compile(r'^((?:#\[[^\n]*\]\n)+)(?:pub(?:\([a-z]+\))? )?mod [A-Za-z_][A-Za-z0-9_]*;$',re.MULTILINE)

def format_files(root,command):
    """Hash every file a rustfmt command checks, which must be exactly the files it lists.

    rustfmt follows out-of-line modules without evaluating cfg. Only top-level #[path] children are resolved
    (relative to the declaring file, as rustc does); any other out-of-line module fails closed."""
    declared=list(command[4:]);found={};pending=list(declared)
    while pending:
        name=pending.pop(0)
        if name in found:continue
        data=(Path(root)/name).read_bytes();found[name]=digest(data);text=data.decode('utf-8')
        children=PATH_MOD.findall(text)
        if len(children)!=len(MOD_ITEM.findall(text)):raise ValueError('Format gate cannot resolve an out-of-line module in '+name)
        for attributes in children:
            paths=re.findall(r'^#\[path = "([A-Za-z0-9_.-]+)"\]$',attributes,re.MULTILINE)
            child=(PurePosixPath(name).parent/paths[0]).as_posix() if len(paths)==1 and paths[0] not in ('.','..') else None
            if child is None or not (Path(root)/child).is_file():raise ValueError('Format gate cannot resolve an out-of-line module in '+name)
            pending.append(child)
    if len(set(declared))!=len(declared) or set(found)!=set(declared):
        raise ValueError('Format gate does not declare every file rustfmt checks: '+','.join(sorted(set(found)^set(declared))))
    return {name:found[name] for name in declared}

def validate_policy(policy):
    if 'coreTests' not in policy:
        if 'coreTestLockSha256' in policy:raise ValueError('Core test lock without approved tests')
        return False
    if policy['coreTests'] not in (MODE,MODE_V2,MODE_TRAFFIC,MODE_OBSERVABILITY,MODE_PROVIDER_GENERAL,MODE_RESOURCE_HEALTH,MODE_MRS_READER,MODE_PROVIDER_READER,MODE_SELECTION_OWNER):raise ValueError('Unknown Core unit test admission')
    expected=policy.get('coreTestLockSha256')
    if expected is not None and (not isinstance(expected,str) or re.fullmatch('[0-9a-f]{64}',expected) is None):
        raise ValueError('Invalid Core unit lock identity')
    if not policy['resolveLock'] and expected is None:raise ValueError('Locked Core tests require an accepted Core test lock')
    if policy['coreTests'] in (MODE_PROVIDER_READER,MODE_SELECTION_OWNER):
        # Same 42-target gate as build_policy applies to the earlier full-suite modes, owned here for this mode.
        from build_policy import RESOURCE_HEALTH_TARGETS
        if not isinstance(policy.get('targets'),list) or tuple(policy['targets'])!=RESOURCE_HEALTH_TARGETS:
            raise ValueError('Provider reader admission requires every approved Native target')
    return True

def feature_matrix(metadata,upstream,mode=MODE):
    """Project only features from the real bridge graph onto the exact prepared member paths."""
    packages={p['id']:p for p in metadata['packages']};nodes={n['id']:n for n in metadata['resolve']['nodes']};result={}
    for name in packages_for_mode(mode):
        found=[p for p in packages.values() if p['name']==name and p['version']=='0.26.1']
        if len(found)!=1 or Path(found[0]['manifest_path']).resolve()!=(upstream/'crates'/name/'Cargo.toml').resolve():
            raise ValueError('Core unit package bypasses the prepared source')
        features=nodes[found[0]['id']]['features']
        if any(re.fullmatch('[A-Za-z0-9_-]+',f) is None for f in features):raise ValueError('Unexpected resolved Core feature name')
        result[name]=sorted(set(features))
    return result

def command(package,name,features):
    result=['cargo','test','--manifest-path',MANIFEST,'--locked','--package',package,'--lib','--no-default-features']
    if features:result+=['--features',','.join(package+'/'+f for f in features)]
    return result+[name,'--','--test-threads=1','--nocapture']

def entries(mode):
    if mode==MODE:return [dict(package=p,filter=f,expectedPass=COUNTS.get((p,f),1),target='lib',requiredFeatures=[]) for p,f in CASES]
    if mode in (MODE_V2,MODE_TRAFFIC):return CASES_V2
    if mode==MODE_MRS_READER:return CASES_MRS_READER
    if mode==MODE_SELECTION_OWNER:return CASES_SELECTION_OWNER
    if mode==MODE_PROVIDER_READER:return CASES_PROVIDER_READER
    if mode==MODE_OBSERVABILITY:return CASES_OBSERVABILITY
    if mode in (MODE_PROVIDER_GENERAL,MODE_RESOURCE_HEALTH):return CASES_PROVIDER_GENERAL
    raise ValueError('Unknown Core unit test admission')

def entry_command(entry,features):
    if not set(entry['requiredFeatures'])<=set(features):raise ValueError('Actual bridge graph lacks the authored Core test feature')
    if entry['target']=='lib':return command(entry['package'],entry['filter'],features)+(['--exact'] if entry.get('exact') else [])
    if entry['package']!='meow-common' or entry['target']!='transport_scope_compat' or entry['filter'] is not None:
        raise ValueError('Unknown fixed Core integration target')
    result=['cargo','test','--manifest-path',MANIFEST,'--locked','--package',entry['package'],'--test','transport_scope_compat','--no-default-features']
    if features:result+=['--features',','.join(entry['package']+'/'+f for f in features)]
    return result+['--','--test-threads=1','--nocapture']

def execute(root,policy,environment,receipt):
    """Resolve a separately recorded test graph, then restore the source lock even after interruption."""
    if not validate_policy(policy):return
    root=Path(root);upstream=root/'.upstream/meow-rs';directory=root/'build/core-tests';directory.mkdir(parents=True,exist_ok=True)
    state=dict(mode=policy['coreTests'],accepted=False,commands=[],upstreamLockRestored=False)
    receipt['coreTests']=state
    source_lock=upstream/'Cargo.lock';original=source_lock.read_bytes()
    (directory/'upstream-before.lock').write_bytes(original);state['upstreamLockBeforeSha256']=digest(original)
    def persist():write_progress(directory/'receipt.json',state)
    def logged(args,stdout_path,stderr_path,entry=None,index=None):
        entry=entry if entry is not None else dict(command=list(args),exit=None)
        if index is not None:entry['index']=index
        state.update(activeIndex=index,activeCommand=entry)
        persist()
        try:return run_logged(args,root,environment,stdout_path,stderr_path,entry=entry,update=persist)
        finally:
            state.update(lastCommand=entry,activeIndex=None,activeCommand=None)
            persist()
    def format_check(key,command,stem,locked):
        args=list(command);entry=dict(command=args,exit=None);state[key]=entry
        entry['files']=format_files(root,args)
        result=logged(args,directory/(stem+'.stdout.log'),directory/(stem+'.stderr.log'),entry)
        entry.update(exit=result.returncode,stdoutSha256=digest(result.stdout),stderrSha256=digest(result.stderr))
        if source_lock.read_bytes()!=locked:raise ValueError('Core format check changed its lock')
        return result.returncode
    try:
        if policy['coreTests'] in (MODE_PROVIDER_READER,MODE_SELECTION_OWNER) and (not isinstance(receipt.get('addedNativeTests'),dict) or not receipt['addedNativeTests']):
            # build.py must have run its full-suite Native proof (and resource JNI source gate) for this mode first.
            raise ValueError('Provider reader admission requires the full Native suite proof before Core tests')
        bridge_command=['cargo','metadata','--locked','--format-version','1']
        bridge=logged(bridge_command,directory/'bridge-metadata.stdout.json',directory/'bridge-metadata.stderr.log')
        state['bridgeMetadataCommand']=bridge_command;state['bridgeMetadataExit']=bridge.returncode
        if bridge.returncode:raise subprocess.CalledProcessError(bridge.returncode,bridge_command)
        matrix=feature_matrix(json.loads(bridge.stdout),upstream,policy['coreTests'])
        expected=policy.get('coreTestLockSha256');seed=root/'core-tests.lock' if expected is not None else root/'Cargo.lock'
        seed_bytes=seed.read_bytes()
        if expected is not None and digest(seed_bytes)!=expected:raise ValueError('Core test seed identity mismatch')
        state['seedSha256']=digest(seed_bytes)
        source_lock.write_bytes(seed_bytes)
        args=['cargo','metadata','--manifest-path',MANIFEST,'--format-version','1']
        if not policy['resolveLock']:args+=['--locked']
        state['metadataCommand']=args
        result=logged(args,directory/'metadata.stdout.json',directory/'metadata.stderr.log')
        state['metadataExit']=result.returncode
        if result.returncode:raise subprocess.CalledProcessError(result.returncode,args)
        metadata=json.loads(result.stdout);members={p['name'] for p in metadata['packages'] if p['id'] in metadata['workspace_members']}
        if not set(packages_for_mode(policy['coreTests']))<=members:raise ValueError('Core unit packages are not members of the actual workspace')
        state['workspaceMembers']=sorted(members)
        state['features']=matrix
        locked=source_lock.read_bytes();state['resolvedLockSha256']=digest(locked);(directory/'core-tests.lock').write_bytes(locked)
        format_failures=[]
        if policy['coreTests'] in (MODE_PROVIDER_READER,MODE_SELECTION_OWNER):
            # Both rustfmt gates run first and are recorded; a failure is raised only after every test and compile
            # command, so one host run uploads the exact diffs together with the authored test results.
            for key,command_args,stem in [('formatCheck',MRS_FORMAT_COMMAND,'mrs-reader-format'),
                    ('providerFormatCheck',PROVIDER_READER_FORMAT_COMMAND,'provider-reader-format')]:
                exit_code=format_check(key,command_args,stem,locked)
                if exit_code:format_failures.append((exit_code,command_args))
        for index,spec in enumerate(entries(policy['coreTests'])):
            package=spec['package'];name=spec['filter'];args=entry_command(spec,matrix[package]);entry=dict(package=package,filter=name,command=args,exit=None,expectedPass=spec['expectedPass']);state['commands'].append(entry)
            if policy['coreTests'] in (MODE_V2,MODE_TRAFFIC,MODE_OBSERVABILITY,MODE_PROVIDER_GENERAL,MODE_RESOURCE_HEALTH,MODE_MRS_READER,MODE_PROVIDER_READER,MODE_SELECTION_OWNER):entry['target']=spec['target']
            if policy['coreTests'] in (MODE_PROVIDER_GENERAL,MODE_RESOURCE_HEALTH,MODE_MRS_READER,MODE_PROVIDER_READER,MODE_SELECTION_OWNER):entry['exact']=spec['exact']
            label=re.sub('[^A-Za-z0-9_.-]','_',name if name is not None else spec['target'])
            stem=f'{index:02d}-{package}-{label}'
            result=logged(args,directory/(stem+'.stdout.log'),directory/(stem+'.stderr.log'),entry,index)
            entry.update(exit=result.returncode,stdoutSha256=digest(result.stdout),stderrSha256=digest(result.stderr))
            if result.returncode:raise subprocess.CalledProcessError(result.returncode,args)
            count=re.findall(rb'test result: ok\. (\d+) passed; 0 failed;',result.stdout)
            if count!=[str(entry['expectedPass']).encode()]:raise ValueError('Core unit filter did not execute its exact authored test count')
            entry['passed']=entry['expectedPass']
            if source_lock.read_bytes()!=locked:raise ValueError('Locked Core unit command changed its lock')
        if policy['coreTests'] in (MODE_OBSERVABILITY,MODE_PROVIDER_GENERAL,MODE_RESOURCE_HEALTH,MODE_MRS_READER,MODE_PROVIDER_READER,MODE_SELECTION_OWNER):
            args=list(COMPILE_COMMAND)
            entry=dict(command=args,exit=None);state['compileCheck']=entry
            result=logged(args,directory/'observability-compile.stdout.log',directory/'observability-compile.stderr.log',entry)
            entry.update(exit=result.returncode,stdoutSha256=digest(result.stdout),stderrSha256=digest(result.stderr))
            if result.returncode:raise subprocess.CalledProcessError(result.returncode,args)
            if source_lock.read_bytes()!=locked:raise ValueError('Locked Core compile command changed its lock')
        if policy['coreTests']==MODE_MRS_READER:
            args=list(MRS_FORMAT_COMMAND);entry=dict(command=args,exit=None);state['formatCheck']=entry
            result=logged(args,directory/'mrs-reader-format.stdout.log',directory/'mrs-reader-format.stderr.log',entry)
            entry.update(exit=result.returncode,stdoutSha256=digest(result.stdout),stderrSha256=digest(result.stderr))
            if result.returncode:raise subprocess.CalledProcessError(result.returncode,args)
            if source_lock.read_bytes()!=locked:raise ValueError('Core format check changed its lock')
        if format_failures:raise subprocess.CalledProcessError(format_failures[0][0],list(format_failures[0][1]))
        state['accepted']=True
    finally:
        source_lock.write_bytes(original)
        state['upstreamLockRestored']=source_lock.read_bytes()==original
        if not state['upstreamLockRestored']:state['accepted']=False
        persist()
        if not state['upstreamLockRestored']:raise ValueError('Core source lock restoration readback failed')
