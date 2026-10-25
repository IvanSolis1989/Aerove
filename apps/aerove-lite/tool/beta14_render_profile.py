"""Exclusive beta14 diagnostic command wrapper; no install/build/renderer changes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import signal
import subprocess
import sys
import threading
import time
import uuid
from beta14_runtime import SPEC
import ci_artifacts as artifacts

LIMIT=48*1024*1024
START='/sdcard/Android/data/dev.aerove.lite/files/captures/dashboard-matrix/02-standard-theme-light.json'
END='/sdcard/Android/data/dev.aerove.lite/files/captures/dashboard-matrix/03-ultra-theme-light-frame-commit-3.json'
class Cancelled(BaseException): pass

def proc_stat(raw):
    a=raw[raw.rfind(')')+2:].split()
    return dict(state=a[0],ppid=int(a[1]),utime=int(a[11]),stime=int(a[12]),starttime=int(a[19]))

def stop_owned(child,errors):
    if child is None:return
    for sig in (signal.SIGINT,signal.SIGTERM,signal.SIGKILL):
        try:
            if child.poll() is not None:return
        except OSError as error:
            errors.append(dict(phase='owned-child-poll',errorType=type(error).__name__));return
        try:child.send_signal(sig);child.wait(timeout=2)
        except subprocess.TimeoutExpired:continue
        except ProcessLookupError:return
        except OSError as error:errors.append(dict(phase='owned-child-stop',errorType=type(error).__name__))
    try:
        if child.poll() is None:errors.append(dict(phase='owned-child-stop',outcome='UNKNOWN_AFTER_BOUNDED_WAIT'))
    except OSError as error:errors.append(dict(phase='owned-child-poll',errorType=type(error).__name__))

class Capture:
    def __init__(self,output,serial,owner):
        self.output=Path(output);self.output.mkdir(parents=True,exist_ok=False)
        self.serial=serial;self.owner=owner;self.stop=threading.Event();self.lock=threading.RLock()
        self.worker=None;self.host=None;self.guest=None;self.guest_identity=None;self.host_log=None;self.guest_log=None;self.cleanup_deadline=None;self.sample_deadline=None
        self.adb=shutil.which('adb');self.perf=shutil.which('perf');self.prlimit=shutil.which('prlimit');self.perf_command=[self.prlimit,'--fsize=8388608:8388608','--',self.perf] if self.perf and self.prlimit else [];self.cleanup=False
        self.remote='/data/local/tmp/aerove-beta14-profile-'+uuid.uuid4().hex
        self.receipt=dict(schema=1,diagnosticOnly=True,accepted=False,performanceEvidence=False,productionCause='UNKNOWN',
          originalAppIdentity=SPEC['identity'],harnessIdentity=owner['runtime']['harnessIdentity'],apkHashes=SPEC['apks'],
          byteBudget=LIMIT,armLoopSeconds=90,sampleLoopSeconds=12,commandMaxSeconds=3,markerPollSeconds=.5,
          nativeSampling='UNKNOWN_NOT_PROBED',commands=[],errors=[],interruptSignals=[],qemuPid=owner['emulatorPid'])
        self.qemu=Path('/proc')/str(owner['emulatorPid']);self.qemu_identity=proc_stat((self.qemu/'stat').read_text())
        if self.qemu_identity['starttime'] != owner['profileQemuStarttime']:raise ValueError('Owned QEMU starttime differs from live owner')
        self.check_owner()
    def begin_cleanup(self):
        self.cleanup=True
        with self.lock:
            if self.cleanup_deadline is None:self.cleanup_deadline=time.monotonic()+18
    def remaining(self,maximum):
        deadline=self.cleanup_deadline if self.cleanup_deadline is not None else getattr(self,'sample_deadline',None)
        return maximum if deadline is None else max(0,min(maximum,deadline-time.monotonic()))
    def check_owner(self):
        actual=proc_stat((self.qemu/'stat').read_text())
        if (actual['starttime']!=self.qemu_identity['starttime'] or actual['ppid']!=self.owner['ownerPid']
          or Path(os.readlink(self.qemu/'exe')).name!='qemu-system-x86_64-headless'):
            raise ValueError('Owned QEMU PID/starttime/PPID/exe changed')
    def error(self,phase,error):
        with self.lock:self.receipt['errors'].append(dict(phase=phase,errorType=type(error).__name__,message=str(error)[:500]))
    def used(self):return sum(p.stat().st_size for p in self.output.iterdir() if p.is_file())
    def put(self,name,raw,cap):
        try:
            with self.lock:
                if len(raw)>cap or self.used()+len(raw)>LIMIT:raise ValueError('Diagnostic byte cap reached')
                (self.output/name).write_bytes(raw)
            return True
        except (OSError,ValueError) as error:self.error('save-'+name,error);return False
    def save(self):
        try:raw=(json.dumps(self.receipt,indent=2)+'\n').encode()
        except (TypeError,ValueError,RuntimeError) as error:self.error('receipt-encode',error);return False
        return self.put('PROFILE-RECEIPT.json',raw,1024*1024)
    def command(self,args,timeout=3,record=True,metadata_only=False):
        if self.stop.is_set() and not self.cleanup:raise Cancelled()
        row=dict(command=args,hostMonoStart=time.monotonic_ns(),returnCode=None)
        timeout=self.remaining(timeout)
        if metadata_only:
            row.pop('command')
            phase='cleanup' if self.cleanup_deadline is not None else 'sample' if self.sample_deadline is not None else 'arm' if self.receipt.get('armHostMonoNanos') is not None else 'preflight'
            deadline=self.cleanup_deadline if phase=='cleanup' else self.sample_deadline if phase=='sample' else self.receipt['armHostMonoNanos']/1e9+90 if phase=='arm' else None
            row.update(logicalCommand='firstClearMarkerQuery',phase=phase,timeoutSeconds=timeout,
              remainingBudgetSeconds=None if deadline is None else max(0,deadline-row['hostMonoStart']/1e9),timedOut=False)
        def outputs(stdout,stderr):
            if metadata_only:
                token=stdout.decode(errors='replace').strip() if stdout is not None else None
                preview=None if stderr is None else stderr[:2048].decode(errors='replace').encode()[:2048].decode(errors='ignore')
                row.update(stdoutBytes=None if stdout is None else len(stdout),stdoutSha256=None if stdout is None else hashlib.sha256(stdout).hexdigest(),
                  markerToken=token if token in ('WAIT','START','END') else None,
                  stderr=preview,stderrBytes=None if stderr is None else len(stderr),stderrTruncated=stderr is not None and len(stderr)>2048)
            else:row.update(stdout=stdout[:32768].decode(errors='replace'),stderr=stderr[:32768].decode(errors='replace'),truncated=len(stdout)>32768 or len(stderr)>32768)
        if timeout<=0:
            row['collectionDeadlineExpired']=True
            if metadata_only:
                outputs(None,None);row['hostMonoEnd']=time.monotonic_ns()
                row['elapsedSeconds']=(row['hostMonoEnd']-row['hostMonoStart'])/1e9
            return row
        try:
            done=subprocess.run(args,capture_output=True,timeout=timeout)
            row['returnCode']=done.returncode;outputs(done.stdout,done.stderr)
        except (OSError,subprocess.TimeoutExpired) as error:
            row.update(errorType=type(error).__name__)
            if metadata_only:
                row['timedOut']=isinstance(error,subprocess.TimeoutExpired)
                row['osErrorNumber']=error.errno if isinstance(error,OSError) else None
                outputs(error.output if isinstance(error,subprocess.TimeoutExpired) else None,error.stderr if isinstance(error,subprocess.TimeoutExpired) else None)
        row['hostMonoEnd']=time.monotonic_ns()
        if metadata_only:row['elapsedSeconds']=(row['hostMonoEnd']-row['hostMonoStart'])/1e9
        if record:
            with self.lock:
                if len(self.receipt['commands'])<48:self.receipt['commands'].append(row)
        return row
    def shell(self,cmd,root=False,record=True,metadata_only=False):
        command=['su','0','sh','-c',shlex.quote(cmd)] if root else [cmd]
        return self.command([self.adb,'-s',self.serial,'shell',*command],record=record,metadata_only=metadata_only)
    def preflight(self):
        if not self.adb:raise ValueError('Existing adb missing; no install allowed')
        self.receipt['guestId']=self.shell('id')
        self.receipt['guestRootProbe']=self.shell('su 0 id')
        self.root=bool(re.search(r'\buid=0\b',self.receipt['guestId'].get('stdout','')))
        self.su=bool(re.search(r'\buid=0\b',self.receipt['guestRootProbe'].get('stdout','')))
        package=self.shell('dumpsys package dev.aerove.lite')
        self.receipt['packageFlagsProbe']=package
        flags=package.get('stdout','') if package.get('returnCode')==0 and not package.get('truncated') else ''
        self.profileable=bool(re.search(r'\b(?:PROFILEABLE_BY_SHELL|DEBUGGABLE)\b|isProfileableByShell=true',flags))
        simple=self.shell('command -v simpleperf');self.receipt['simpleperfPathProbe']=simple
        if simple.get('returnCode')==0:self.receipt['simpleperfVersion']=self.shell('simpleperf --version')
        help_=self.shell('simpleperf record --help') if simple.get('returnCode')==0 else {}
        self.receipt['simpleperfRecordHelp']=help_
        text=help_.get('stdout','')+help_.get('stderr','')
        self.native=bool(simple.get('returnCode')==0 and '--size-limit' in text and '--call-graph' in text and (self.root or self.su or (self.profileable and '--app' in text)))
        self.receipt.update(rootShellActive=self.root,suExistingAvailable=self.su,profileableByShellObserved=self.profileable,nativeSampling='AVAILABLE_PENDING_ACTUAL_CAPTURE' if self.native else 'UNAVAILABLE_HOST_ONLY_UNKNOWN_NATIVE')
        try:
            rows=list((self.qemu/'task').iterdir());proc_stat((rows[0]/'stat').read_text());self.proc=True
        except (OSError,ValueError,IndexError):self.proc=False
        self.host_perf=False
        if self.perf_command:
            self.receipt['perfVersion']=self.command([self.perf,'version'])
            args=[*self.perf_command,'record','--clockid','mono','--no-inherit','-e','cpu-clock','-F','99','-p',str(self.owner['emulatorPid']),'--call-graph','fp','-o',str(self.output/'perf-probe.data'),'--','sleep','0.1']
            try:
                r=subprocess.run(args,capture_output=True,timeout=3)
                self.host_perf=r.returncode==0 and (self.output/'perf-probe.data').is_file()
                self.receipt['perfRecordProbe']=dict(command=args,returnCode=r.returncode,stdout=r.stdout[:8192].decode(errors='replace'),stderr=r.stderr[:8192].decode(errors='replace'))
            except (OSError,subprocess.TimeoutExpired) as error:self.error('perf-record-probe',error)
        self.receipt['hostPerfCommandPrefix']=self.perf_command
        self.receipt['existingPrlimit']=self.prlimit
        self.receipt['hostPerfSingleFileCapBytes']=8388608
        self.receipt.update(hostPerfAvailable=self.host_perf,hostProcAvailable=self.proc)
        sdk=Path(os.environ.get('ANDROID_HOME','/nonexistent'))
        for name,relative in (('emulator-source.properties','emulator/source.properties'),('image-source.properties','system-images/android-33/google_apis/x86_64/source.properties')):
            try:self.put(name,(sdk/relative).read_bytes(),65536)
            except OSError as error:self.error(name,error)
        self.receipt['guestVersionProps']=self.shell('getprop ro.vendor.build.fingerprint; getprop ro.boot.qemu.gltransport.name')
        if not self.proc and not self.host_perf:raise ValueError('No existing usable host collector; matrix must not start')
        for name in ('maps','status'):
            try:self.put('qemu-'+name+'.txt',(self.qemu/name).read_bytes()[:512*1024],512*1024)
            except OSError as error:self.error('qemu-'+name,error)
        self.receipt['clockCaveat']='host monotonic and guest uptime probe bounds/0.01s quantization must be joined to same-run Perfetto; no direct clock equivalence'
        if self.marker()!='WAIT':raise ValueError('Fresh matrix markers required; never reuse earlier case files')
    def marker(self):
        r=self.shell(f'if [ -f {END} ]; then echo END; elif [ -f {START} ]; then echo START; else echo WAIT; fi',record=False,metadata_only=True)
        if r.get('returnCode')!=0 or r.get('markerToken') not in ('WAIT','START','END'):
            # Preserve the failed query, never raw stdout or an expanded/private command.
            # Storage failures cannot replace the original marker error or command result.
            try:
                with self.lock:
                    rows=self.receipt.setdefault('markerQueryFailures',[])
                    self.receipt['markerQueryFailureCount']=self.receipt.get('markerQueryFailureCount',0)+1
                    if len(rows)<8:rows.append(r)
                    else:self.receipt['markerFailureRowsDropped']=self.receipt.get('markerFailureRowsDropped',0)+1
                # No IO here: stop profilers before the existing sample/run receipt saves.
            except Exception:pass
            raise ValueError('Fresh first-Clear marker query unavailable')
        return r['markerToken']
    def guest_info(self):
        r=self.shell('pidof dev.aerove.lite');pids=r.get('stdout','').split()
        if len(pids)!=1 or not pids[0].isdigit():raise ValueError('Unique actual App PID not available')
        self.app_pid=int(pids[0]);r=self.shell(f'cat /proc/{self.app_pid}/stat',root=self.su and not self.root);self.app_identity=proc_stat(r['stdout'])
        tasks=self.shell(f'for t in /proc/{self.app_pid}/task/*; do printf "%s " "${{t##*/}}"; cat "$t/comm"; done',root=self.su and not self.root)
        threads=[]
        for line in tasks.get('stdout','').splitlines():
            a=line.split(' ',1)
            if len(a)==2 and a[0].isdigit():threads.append(dict(tid=int(a[0]),comm=a[1]))
        self.receipt['guestMapsAtTrigger']=self.shell(f'cat /proc/{self.app_pid}/maps',root=self.su and not self.root)
        self.receipt.update(appPid=self.app_pid,appStarttime=self.app_identity['starttime'],guestThreads=threads,
          mainTid=self.app_pid,renderTids=[x['tid'] for x in threads if x['comm']=='RenderThread'])
    def start_guest(self):
        if not self.native:return
        if self.remaining(12)<1:self.receipt['nativeSampling']='UNKNOWN_NO_WINDOW_BUDGET';return
        native_duration=max(1,int(self.remaining(12)))
        args=['simpleperf','record','-e','cpu-clock','-f','99','--call-graph','fp','--duration',str(native_duration),'--size-limit','8388608','-o',self.remote+'.data']
        args+=['-p',str(self.app_pid)] if self.root or self.su else ['--app','dev.aerove.lite']
        inner=' '.join(shlex.quote(v) for v in args)+f' & p=$!; echo "$p" > {self.remote}.pid; wait "$p"'
        remote=['su','0','sh','-c',shlex.quote(inner)] if self.su and not self.root else [inner]
        if self.stop.is_set():raise Cancelled()
        self.receipt['nativeToolStderrCaptured']=False
        self.guest=subprocess.Popen([self.adb,'-s',self.serial,'shell',*remote],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        # No system scan: read only our freshly-created PID token and its exact cmdline/starttime.
        for _ in range(4):
            r=self.shell('cat '+self.remote+'.pid',root=self.su and not self.root)
            if r.get('returnCode')==0 and r.get('stdout','').strip().isdigit():
                pid=int(r['stdout'].strip());stat_=self.shell(f'cat /proc/{pid}/stat',root=self.su and not self.root);cmd=self.shell(f'cat /proc/{pid}/cmdline',root=self.su and not self.root)
                if stat_.get('returnCode')==0 and self.remote+'.data' in cmd.get('stdout','') and 'simpleperf' in cmd.get('stdout',''):
                    self.guest_identity=(pid,proc_stat(stat_['stdout'])['starttime']);break
            if self.stop.wait(.15):break
        self.receipt['nativeCommand']=args;self.receipt['nativeOwnedPid']=self.guest_identity
        if self.guest_identity is None:self.error('native-pid',ValueError('remote ownership not confirmed'))
    def stop_guest(self):
        if self.guest_identity:
            pid,start=self.guest_identity
            for sig in ('INT','TERM','KILL'):
                row=self.shell(f'cat /proc/{pid}/stat',root=self.su and not self.root,record=False)
                if row.get('returnCode')!=0:break
                cmd=self.shell(f'cat /proc/{pid}/cmdline',root=self.su and not self.root,record=False)
                if proc_stat(row['stdout'])['starttime']!=start or self.remote+'.data' not in cmd.get('stdout',''):
                    self.error('native-stop',ValueError('PID identity changed; no signal'));break
                self.shell(f'kill -{sig} {pid}',root=self.su and not self.root,record=False)
                if self.guest is not None:
                    try:self.guest.wait(timeout=2);break
                    except subprocess.TimeoutExpired:pass
        stop_owned(self.guest,self.receipt['errors'])
    def export_guest(self):
        if self.guest is None:return
        if self.remaining(1)<=0:
            self.receipt['nativeSampling']='UNKNOWN_CLEANUP_DEADLINE';self.receipt['ownedRemoteFilesMayRemain']=True;return
        if self.guest_identity is None:self.receipt['nativeOwnershipConfirmed']=False
        else:self.receipt['nativeOwnershipConfirmed']=True
        size=self.shell('wc -c < '+self.remote+'.data',root=self.su and not self.root)
        raw=size.get('stdout','').strip()
        if size.get('returnCode')==0 and raw.isdigit() and 0<int(raw)<=8*1024*1024 and self.used()+int(raw)<LIMIT-1024*1024:
            cmd=['su','0','cat',self.remote+'.data'] if self.su and not self.root else ['cat',self.remote+'.data']
            report=self.shell('simpleperf report -i '+self.remote+'.data',root=self.su and not self.root)
            self.receipt['guestProfileReadable']=report.get('returnCode')==0
            self.receipt['guestProfileReport']=report
            left=self.remaining(8)
            if left<=0:self.receipt['nativeSampling']='UNKNOWN_CLEANUP_DEADLINE';return
            r=subprocess.run([self.adb,'-s',self.serial,'exec-out',*cmd],capture_output=True,timeout=left)
            okay=r.returncode==0 and len(r.stdout)==int(raw) and r.stdout[:8]==b'PERFILE2'
            if okay:okay=self.put('guest-simpleperf.data',r.stdout,8*1024*1024)
            self.receipt['nativeSampling']='CAPTURED_UNANALYZED' if okay else 'UNKNOWN_EXPORT_FAILED'
        else:self.receipt['nativeSampling']='UNKNOWN_MISSING_OR_OVER_CAP'
        self.receipt['guestSimpleperfReturnCode']=self.guest.poll()
        # Only our UUID files, never an existing App/third-party path or recursive delete.
        self.shell('rm -f '+self.remote+'.data '+self.remote+'.pid',root=self.su and not self.root)
    def sample(self):
        try:
            armed=time.monotonic();self.receipt['armHostMonoNanos']=time.monotonic_ns()
            while not self.stop.is_set():
                self.check_owner();mark=self.marker()
                if mark!='WAIT':break
                if time.monotonic()-armed>=90:raise ValueError('First Clear marker arm deadline')
                self.stop.wait(.5)
            if self.stop.is_set():return
            if mark=='END':raise ValueError('First Clear already closed; no new sampling')
            self.receipt['sampleStartHostMonoNanos']=time.monotonic_ns()
            self.sample_deadline=time.monotonic()+12
            if self.host_perf and not self.stop.is_set():
                self.host_log=(self.output/'host-perf.log').open('xb')
                self.host=subprocess.Popen([*self.perf_command,'record','--clockid','mono','--no-inherit','-e','cpu-clock','-F','99','-p',str(self.owner['emulatorPid']),'--call-graph','fp','-o',str(self.output/'host-perf.data')],stdout=self.host_log,stderr=subprocess.STDOUT)
            written=0;previous=0
            def snapshot(out):
                nonlocal written
                self.check_owner();rows=[]
                for task in sorted((self.qemu/'task').iterdir()):
                    try:
                        row=dict(tid=int(task.name),comm=(task/'comm').read_text().strip(),**proc_stat((task/'stat').read_text()))
                        try:row['wchan']=(task/'wchan').read_text().strip()
                        except OSError:row['wchan']=None
                        rows.append(row)
                    except (FileNotFoundError,ProcessLookupError):continue
                raw=(json.dumps(dict(hostMonoNanos=time.monotonic_ns(),threads=rows),separators=(',',':'))+'\n').encode()
                if written+len(raw)>6*1024*1024 or self.used()+len(raw)>LIMIT-1024*1024:
                    self.receipt['sampleStop']='BYTE_CAP';return False
                out.write(raw);written+=len(raw);return True
            with (self.output/'host-threads.jsonl').open('xb') as out:
                snapshot(out) # Host counters bracket the guest metadata/profiler setup too.
                self.guest_info();self.receipt['guestStartClockProbe']=self.shell('cat /proc/uptime')
                self.start_guest()
                while not self.stop.is_set() and self.remaining(1)>0:
                    if not snapshot(out):break
                    if time.monotonic()-previous>=.5 and self.remaining(.5)>=.2:
                        if self.marker()=='END':self.receipt['sampleStop']='FIRST_CLEAR_OBSERVATION_CLOSED';break
                        previous=time.monotonic()
                    self.stop.wait(min(.2,self.remaining(.2)))
                snapshot(out)
            self.receipt['sampleEndHostMonoNanos']=time.monotonic_ns();self.receipt['hostClockTicks']=os.sysconf('SC_CLK_TCK')
        except Cancelled:pass
        except Exception as error:self.error('sample',error)
        finally:
            self.receipt.setdefault('sampleStop','CANCELLED' if self.stop.is_set() else 'LOOP_BUDGET_OR_NO_WINDOW')
            if self.guest is None and self.receipt['nativeSampling']=='AVAILABLE_PENDING_ACTUAL_CAPTURE':self.receipt['nativeSampling']='UNKNOWN_NOT_STARTED'
            self.begin_cleanup()
            # End host sampling before any remote cleanup/export can consume its budget.
            stop_owned(self.host,self.receipt['errors'])
            self.receipt['hostPerfStopHostMonoNanos']=time.monotonic_ns()
            if self.host is not None:self.receipt['hostPerfReturnCode']=self.host.poll()
            try:self.stop_guest()
            except Exception as error:self.error('native-stop',error)
            self.receipt['nativeStopHostMonoNanos']=time.monotonic_ns()
            for stream in (self.host_log,self.guest_log):
                if stream is not None:stream.close()
            try:self.export_guest()
            except Exception as error:self.error('native-export',error)
            try:
                frame=self.shell('cat '+END)
                if frame.get('returnCode')==0 and not frame.get('truncated'):
                    raw=frame.get('stdout','').encode();obj=json.loads(raw);self.put('first-clear-frame-observation.json',raw,32768)
                    self.receipt['javaMainStackPresent']=any(x.get('uiThreadStack') for x in obj.get('events',[]))
                else:self.receipt['javaMainStackPresent']=None
                self.receipt['guestEndClockProbe']=self.shell('cat /proc/uptime')
                if hasattr(self,'app_pid'):
                    row=self.shell(f'cat /proc/{self.app_pid}/stat',root=self.su and not self.root);self.receipt['sameAppPidAtEnd']=row.get('returnCode')==0 and proc_stat(row['stdout'])['starttime']==self.app_identity['starttime']
            except Exception as error:self.error('guest-final-metadata',error)
            self.save()
    def close(self):
        self.stop.set();self.begin_cleanup()
        if self.worker is not None:self.worker.join(timeout=30)
        if self.worker is not None and self.worker.is_alive():
            self.error('join',ValueError('UNKNOWN_AFTER_30S_JOIN'));stop_owned(self.host,self.receipt['errors'])
            try:self.stop_guest()
            except Exception as error:self.error('join-native-stop',error)
        self.save()

def checked_owner(path,serial):
    value=json.loads(Path(path).read_text());runtime=value['runtime']
    if (value['app']!=SPEC['identity'] or runtime['identity']!=SPEC['identity'] or runtime['apks']!=SPEC['apks']
      or runtime.get('runtimeTarget')!='beta14' or runtime.get('apkInspectorOutcome')!='success'
      or runtime.get('promotionAccepted') is not False or runtime.get('originalAppIdentity')!=SPEC['identity']):
        raise ValueError('Exclusive immutable beta14 inspected owner required')
    expected=artifacts.identity()
    if (runtime.get('harnessIdentity')!=expected or value.get('harnessRevision')!=expected['sourceRevision']
      or value.get('harnessRun')!=expected['workflowRun'] or type(value.get('profileQemuStarttime')) is not int):
        raise ValueError('Profile owner current harness/starttime proof differs')
    port=value['command'][value['command'].index('-port')+1]
    if serial!='emulator-'+port:raise ValueError('Serial differs from owned QEMU')
    return value

def qualify(output):
    path=Path(output);receipt=json.loads((path/'PROFILE-RECEIPT.json').read_text())
    value=dict(schema=1,accepted=False,performanceEvidence=False,productionCause='UNKNOWN',
      outcome='CAPTURED_ANALYSIS_PENDING' if receipt.get('sampleStartHostMonoNanos') else 'NO_VALID_FOCUSED_CAPTURE',
      originalCommandReturnCode=receipt.get('originalCommandReturnCode'),nativeSampling=receipt.get('nativeSampling'),
      nativeRawCaptured=receipt.get('nativeSampling')=='CAPTURED_UNANALYZED',
      nativeUsability='UNKNOWN_PENDING_PROFILE_AND_WINDOW_ANALYSIS',
      guestReadabilityObserved=receipt.get('guestProfileReadable'),qualificationExit=78)
    try:(path/'PROFILE-QUALIFICATION.json').write_text(json.dumps(value,indent=2)+'\n')
    except OSError:pass
    return 78 # Collection is not a CPU/GPU cause or App-performance acceptance.

def run(args,command):
    if sys.platform!='linux':raise ValueError('Existing CI Linux only; no local SDK setup')
    owner=checked_owner(args.owner,args.serial);capture=Capture(args.output,args.serial,owner)
    child=None;result=78;command_result=None;result_owner='preflight';signals=[];cleaning=False
    previous={s:signal.getsignal(s) for s in (signal.SIGINT,signal.SIGTERM)}
    def record_error(where,error):
        try:capture.error(where,error)
        except Exception:pass # Diagnostic storage/error reporting cannot replace the primary result.
    def interrupt(signum,frame):
        nonlocal result,result_owner
        signals.append(signum);capture.stop.set()
        if not cleaning:raise Cancelled()
        if result==0:
            result=128+signum;result_owner='cleanup-cancellation'
            capture.receipt.update(wrapperReturnCode=result,primaryResultOwner=result_owner)
    for s in previous:signal.signal(s,interrupt)
    try:
        capture.preflight()
        if not capture.save():raise ValueError('Preflight receipt cannot be saved; matrix skipped')
        if signals:raise Cancelled()
        capture.worker=threading.Thread(target=capture.sample,daemon=True);capture.worker.start()
        if signals:raise Cancelled()
        capture.receipt['originalCommand']=command;capture.receipt['originalCommandStartHostMonoNanos']=time.monotonic_ns()
        capture.receipt['outerCommandGuardianSeconds']=780
        capture.receipt['originalInstrumentationHostDeadlineSeconds']=660
        child=subprocess.Popen(command,start_new_session=True)
        try:
            command_result=child.wait(timeout=780)
            result=command_result;result_owner='original-command'
        except subprocess.TimeoutExpired:
            command_result=124;result=124;result_owner='outer-guardian'
            record_error('original-command',TimeoutError('780s outer guardian: existing660s trace command plus bounded cleanup'))
    except Cancelled:
        # A completed nonzero wait/guardian owns the first failure, even at a signal boundary.
        if command_result is not None and command_result!=0:
            result=command_result;result_owner='outer-guardian' if result_owner=='outer-guardian' else 'original-command'
        else:result=128+signals[0] if signals else 130;result_owner='cancellation'
    except Exception as error:record_error('preflight-or-command',error)
    finally:
        cleaning=True
        try:
            try:stop_owned(child,capture.receipt['errors'])
            except Exception as error:record_error('original-command-cleanup',error)
            try:capture.close()
            except Exception as error:record_error('profile-close',error)
            # Cancellation after successful completion is nonzero, but never rewrites a primary failure.
            if signals and result==0:result=128+signals[0];result_owner='cleanup-cancellation'
            observed=None
            if child is not None:
                try:observed=child.poll()
                except OSError as error:record_error('original-child-status',error)
            capture.receipt.update(originalCommandReturnCode=command_result,
              childObservedReturnCode=observed,wrapperReturnCode=result if result>=0 else 128-result,
              primaryResultOwner=result_owner,commandSkipped=child is None,interruptSignals=signals,
              originalCommandEndHostMonoNanos=time.monotonic_ns() if child else None)
            saved_signals=len(signals)
            try:capture.save()
            except Exception as error:record_error('final-save',error)
            try:qualify(args.output)
            except Exception as error:record_error('qualification-save',error)
            if len(signals)!=saved_signals:
                try:capture.save()
                except Exception as error:record_error('late-cancellation-save',error)
        finally:
            for s,h in previous.items():signal.signal(s,h)
    return result if result>=0 else 128-result

def main():
    p=argparse.ArgumentParser();sub=p.add_subparsers(dest='action',required=True)
    r=sub.add_parser('run');r.add_argument('--serial',required=True);r.add_argument('--owner',required=True);r.add_argument('--output',required=True);r.add_argument('command',nargs=argparse.REMAINDER)
    q=sub.add_parser('qualify');q.add_argument('--output',required=True)
    args=p.parse_args()
    if args.action=='qualify':return qualify(args.output)
    cmd=args.command[1:] if args.command[:1]==['--'] else args.command
    if not cmd:raise ValueError('Original trace/instrumentation command required')
    return run(args,cmd)
if __name__=='__main__':raise SystemExit(main())
