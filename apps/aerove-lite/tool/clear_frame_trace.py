"""Opt-in, bounded Android system trace around the unchanged matrix instrumentation.

No app/tool installation, root, renderer changes, frame warm-up, or acceptance override.
The caller retains emulator ownership; only this collector's recorded Perfetto PID is stopped.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
import uuid

PACKAGE = 'dev.aerove.lite'
TRACE_FILE_CAP_BYTES = 128 * 1024 * 1024
TRACE_WRITE_PERIOD_MS = 1000
TRACE_CONFIG_SCHEMA = ('https://github.com/google/perfetto/blob/'
                       '7e8d6801dbf73936a916dbcd8ed06a758c8d989e/'
                       'protos/perfetto/config/trace_config.proto')


class CollectorCancelled(BaseException):
    pass


def diagnostic_error(capture, stage, error):
    capture.receipt['errors'].append(dict(stage=stage, error=str(error)))
    try:
        print(f'Diagnostic {stage} failed: {error}', file=sys.stderr)
    except OSError:
        pass


def save_best_effort(capture):
    try:
        capture.save()
    except OSError as error:
        diagnostic_error(capture, 'receipt', error)


def stop_command_best_effort(child, capture):
    for action in ('terminate', 'kill'):
        try:
            if child.poll() is not None:
                capture.receipt['instrumentationCleanup'] = 'exited'
                return
            getattr(child, action)()
            child.wait(timeout=3)
            capture.receipt['instrumentationCleanup'] = 'exited'
            return
        except Exception as error:
            diagnostic_error(capture, 'instrumentation-' + action, error)
    capture.receipt['instrumentationCleanup'] = 'UNKNOWN'



def available_data_sources(query):
    section = re.search(r'^DATA SOURCES REGISTERED:\r?\n(.*?)(?=^TRACING SESSIONS:|\Z)',
                        query, re.M | re.S)
    if section is None:
        return ()
    approved = ('linux.ftrace', 'linux.process_stats',
                'android.surfaceflinger.frametimeline', 'gpu.renderstages')
    return tuple(name for name in approved if re.search(
        r'^\s*' + re.escape(name) + r'\s+\S+\s+\([0-9]+\)(?:\s+.*)?$',
        section[1], re.M))


def config_text(query, categories, duration=120):
    selected = [name for name in ('gfx', 'view', 'wm', 'sched', 'freq', 'binder_driver')
                if re.search(r'^\s*' + re.escape(name) + r'\s+-', categories, re.M)]
    events = '\n'.join('      ftrace_events: "' + event + '"' for event in (
        'sched/sched_switch', 'sched/sched_waking', 'sched/sched_process_exit'))
    atrace = '\n'.join('      atrace_categories: "' + name + '"' for name in selected)
    text = f'''buffers {{ size_kb: 32768 fill_policy: RING_BUFFER }}
duration_ms: {duration * 1000}
write_into_file: true
file_write_period_ms: {TRACE_WRITE_PERIOD_MS}
max_file_size_bytes: {TRACE_FILE_CAP_BYTES}
data_sources {{ config {{ name: "linux.ftrace" ftrace_config {{
{events}
{atrace}
      atrace_apps: "{PACKAGE}"
}} }} }}
data_sources {{ config {{ name: "linux.process_stats" process_stats_config {{ scan_all_processes_on_start: true }} }} }}
'''
    for source in ('android.surfaceflinger.frametimeline', 'gpu.renderstages'):
        if source in available_data_sources(query):
            text += f'data_sources {{ config {{ name: "{source}" }} }}\n'
    return text


class TraceCapture:
    def __init__(self, serial, directory, duration=120, adb='adb'):
        if not re.fullmatch(r'emulator-[0-9]+', serial):
            raise ValueError('An explicitly owned emulator serial is required')
        if not 15 <= duration <= 120:
            raise ValueError('Trace duration must be 15..120 seconds')
        self.adb = [adb, '-s', serial]
        self.directory = Path(directory)
        self.storage_ready = False
        self.duration = duration
        self.token = 'aerove-clear-' + uuid.uuid4().hex
        self.remote = '/data/misc/perfetto-traces/' + self.token
        self.pid = None
        self.started = None
        self.receipt = dict(schema=1, diagnosticOnly=True, accepted=False,
                            deviceSerial=serial, durationSeconds=duration,
                            bufferKiB=32768, fillPolicy='RING_BUFFER', commands=[],
                            errors=[], traceStarted=False, tracePulled=False,
                            bufferPolicyCanOverwriteUnread=True, bufferOverwritesObserved=None,
                            gpuTimingNotGuaranteed=True, coldCompilationNotGuaranteed=True,
                            performanceEvidence=False, targetPackage=PACKAGE, deviceApi=None,
                            targetPidsBefore=None, targetPidsAfter=None,
                            remoteTrace=self.remote + '.pftrace', traceCoverageValidated=False,
                            streamingToFile=True, fileWritePeriodMillis=TRACE_WRITE_PERIOD_MS,
                            traceFileCapBytes=TRACE_FILE_CAP_BYTES, fileCapMayStopBeforeDuration=True,
                            earlyFileCapStopObserved=None, traceEndReason='UNKNOWN_PENDING_SERVICE_EVENTS',
                            configSchemaReference=TRACE_CONFIG_SCHEMA)
        try:
            self.directory.mkdir(parents=True, exist_ok=False)
            self.storage_ready = True
        except FileExistsError:
            raise
        except OSError as error:
            diagnostic_error(self, 'directory', error)

    def save(self):
        if not self.storage_ready:
            return
        (self.directory / 'TRACE-RECEIPT.json').write_text(
            json.dumps(self.receipt, indent=2) + '\n', encoding='utf-8')

    def run(self, args, name, timeout=8, accepted_return_codes=(0,), input_bytes=None):
        began = time.monotonic_ns()
        item = dict(command=self.adb + args, timeoutSeconds=timeout,
                    hostMonotonicStartNanos=began, acceptedReturnCodes=list(accepted_return_codes))
        if input_bytes is not None:
            item.update(stdinBytes=len(input_bytes), stdinSha256=hashlib.sha256(input_bytes).hexdigest())
        try:
            result = subprocess.run(self.adb + args, input=input_bytes, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, timeout=timeout)
            data = result.stdout
            item['returnCode'] = result.returncode
            if result.returncode not in accepted_return_codes and name in (
                    'perfetto-start.txt', 'perfetto-query.txt', 'pull-trace.txt'):
                self.receipt['errors'].append(dict(stage=name, returnCode=result.returncode,
                                                   error='Diagnostic command returned non-accepted status'))
        except (subprocess.TimeoutExpired, OSError) as error:
            data = str(error).encode()
            item['error'] = str(error)
            self.receipt['errors'].append(dict(stage=name, error=str(error)))
            result = None
        item['hostMonotonicEndNanos'] = time.monotonic_ns()
        try:
            (self.directory / name).write_bytes(data)
        except OSError as error:
            diagnostic_error(self, name, error)
        self.receipt['commands'].append(item)
        save_best_effort(self)
        return data.decode(errors='replace') if result is not None and result.returncode in accepted_return_codes else None

    def start(self):
        if not self.storage_ready:
            return
        api = self.run(['shell', 'getprop', 'ro.build.version.sdk'], 'device-api.txt')
        self.receipt['deviceApi'] = int(api.strip()) if api and api.strip().isdigit() else None
        pids = self.run(['shell', 'pidof', PACKAGE], 'target-pids-before.txt', timeout=3)
        self.receipt['targetPidsBefore'] = [int(p) for p in pids.split()] if pids and all(p.isdigit() for p in pids.split()) else None
        help_text = self.run(['shell', 'perfetto', '--help'], 'perfetto-help.txt', accepted_return_codes=(0, 1))
        if not help_text or '--background-wait' not in help_text:
            self.receipt['errors'].append(dict(stage='start', error='Existing perfetto lacks confirmed --background-wait support'))
            save_best_effort(self)
            return
        query = self.run(['shell', 'perfetto', '--query'], 'perfetto-query.txt') or ''
        available = available_data_sources(query)
        self.receipt['dataSourceCapability'] = dict(
            source='perfetto-query.txt', format='DATA SOURCES REGISTERED table; exact approved name/producer/id',
            approvedAvailable=list(available), queryRawDecoded=False)
        if not {'linux.ftrace', 'linux.process_stats'}.issubset(available):
            diagnostic_error(self, 'capabilities', 'Required trace data sources not confirmed by the query table')
            save_best_effort(self)
            return
        categories = self.run(['shell', 'atrace', '--list_categories'], 'atrace-categories.txt') or ''

        config = config_text(query, categories, self.duration)
        path = self.directory / 'config.pbtxt'
        path.write_bytes(config.encode('utf-8'))
        self.receipt['configSha256'] = hashlib.sha256(config.encode()).hexdigest()
        self.receipt['configSourceFile'] = str(path)
        self.run(['shell', 'cat', '/proc/uptime'], 'device-uptime-before.txt')
        self.started = time.monotonic()
        self.receipt['traceTriggerHostMonotonicNanos'] = time.monotonic_ns()
        output = self.run(['shell', 'perfetto', '--background-wait', '--txt', '-c',
                           '-', '-o', self.remote + '.pftrace'],
                          'perfetto-start.txt', timeout=35, input_bytes=config.encode('utf-8'))
        candidates = re.findall(r'^\s*([0-9]+)\s*$', output or '', re.M)
        if len(candidates) != 1 or int(candidates[0]) <= 0:
            self.receipt['errors'].append(dict(stage='start', error='No unique owned PID; no arbitrary kill; session remains duration-bounded'))
            save_best_effort(self)
            return
        self.pid = int(candidates[0])
        self.receipt.update(traceStarted=True, perfettoPid=self.pid, remoteTrace=self.remote + '.pftrace')
        save_best_effort(self)

    def finish(self):
        if not self.storage_ready:
            return
        pids = self.run(['shell', 'pidof', PACKAGE], 'target-pids-after.txt', timeout=3)
        self.receipt['targetPidsAfter'] = [int(p) for p in pids.split()] if pids and all(p.isdigit() for p in pids.split()) else None
        if self.pid is not None:
            command = self.run(['shell', 'cat', f'/proc/{self.pid}/cmdline'], 'perfetto-owned-cmdline.txt', timeout=3)
            if command and 'perfetto' in command and self.remote + '.pftrace' in command:
                self.run(['shell', 'kill', '-INT', str(self.pid)], 'perfetto-owned-stop.txt', timeout=3)
                # Signal flush is bounded; never wait for app hardware commit here.
                for attempt in range(5):
                    alive = self.run(['shell', 'test', '-e', f'/proc/{self.pid}'], f'perfetto-exit-{attempt:02d}.txt', timeout=1)
                    if alive is None:
                        break
                    time.sleep(0.1)
                else:
                    self.receipt['errors'].append(dict(stage='flush', error='Owned perfetto has not exited; pulled trace may be partial'))
            elif command:
                self.receipt['errors'].append(dict(stage='stop', error='PID identity differs; refusing to signal'))
        trace = self.directory / 'clear-window.pftrace'
        if self.started is not None:
            pulled = self.run(['pull', self.remote + '.pftrace', str(trace)], 'pull-trace.txt', timeout=15)
            if pulled is not None and trace.is_file() and trace.stat().st_size:
                self.receipt.update(tracePulled=True, traceBytes=trace.stat().st_size,
                                    traceSha256=hashlib.sha256(trace.read_bytes()).hexdigest(),
                                    fileBytesAtOrAboveConfiguredCap=trace.stat().st_size >= TRACE_FILE_CAP_BYTES)
        self.run(['shell', 'cat', '/proc/uptime'], 'device-uptime-after.txt', timeout=3)
        self.run(['shell', 'dumpsys', 'gfxinfo', PACKAGE, 'framestats'], 'gfxinfo-framestats.txt', timeout=3)
        self.run(['shell', 'dumpsys', 'SurfaceFlinger', '--list'], 'surfaceflinger-layers.txt', timeout=3)
        self.run(['shell', 'dumpsys', 'SurfaceFlinger', '--latency'], 'surfaceflinger-latency.txt', timeout=3)
        save_best_effort(self)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--duration', type=int, default=120)
    parser.add_argument('--command-timeout', type=int, default=660)
    parser.add_argument('--require-trace', action='store_true',
                        default=os.environ.get('AEROVE_REQUIRE_FRAME_TRACE') == '1')
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    expected = ['-s', args.serial, 'shell', 'am', 'instrument']
    if not command or Path(command[0]).name not in ('adb', 'adb.exe') or command[1:6] != expected:
        parser.error('Wrap only the original adb -s SERIAL shell am instrument command')
    if not 1 <= args.command_timeout <= 660:
        parser.error('command-timeout must be 1..660 seconds; preserve original instrumentation timeout')
    capture = TraceCapture(args.serial, args.output, args.duration, command[0])
    child = None
    result = 1
    trace_blocked = False
    capture.receipt['traceRequired'] = args.require_trace
    cancelled = []
    previous_handlers = {s: signal.getsignal(s) for s in (signal.SIGINT, signal.SIGTERM)}

    def interrupt(signum, frame):
        cancelled.append(signum)
        if len(cancelled) == 1:
            raise CollectorCancelled(f'Collector interrupted by signal {signum}')

    def check_cancelled():
        if cancelled:
            raise CollectorCancelled(f'Collector cancelled by signal {cancelled[0]}')

    signal.signal(signal.SIGINT, interrupt)
    signal.signal(signal.SIGTERM, interrupt)
    try:
        try:
            capture.start()
        except Exception as error:
            diagnostic_error(capture, 'start', error)
        check_cancelled()
        if args.require_trace and not capture.receipt['traceStarted']:
            trace_blocked = True
            result = 78
            capture.receipt.update(instrumentationSkipped=True, skipReason='required-trace-not-started',
                                   requestedInstrumentationCommand=command)
            diagnostic_error(capture, 'required-trace', 'Trace start not confirmed; original matrix was not launched')
        else:
            capture.receipt['instrumentationCommand'] = command
            capture.receipt['instrumentationStartedHostMonotonicNanos'] = time.monotonic_ns()
            save_best_effort(capture)
            check_cancelled()
            child = subprocess.Popen(command)
            result = child.wait(timeout=args.command_timeout)
    except CollectorCancelled as error:
        result = 128 + cancelled[0]
        diagnostic_error(capture, 'instrumentation-cancelled', error)
    except subprocess.TimeoutExpired as error:
        result = 124
        diagnostic_error(capture, 'instrumentation-timeout', error)
    finally:
        try:
            if child is not None:
                try:
                    stop_command_best_effort(child, capture)
                except CollectorCancelled:
                    result = 128 + cancelled[0]
                    stop_command_best_effort(child, capture)
            try:
                if not trace_blocked or capture.started is not None:
                    capture.finish()
                else:
                    capture.receipt['collectorPostDiagnosticsSkipped'] = True
            except CollectorCancelled:
                result = 128 + cancelled[0]
            except Exception as error:
                diagnostic_error(capture, 'finish', error)
            capture.receipt.update(instrumentationReturnCode=None if trace_blocked else result,
                                   wrapperReturnCode=result, interruptSignals=cancelled,
                                   instrumentationFinishedHostMonotonicNanos=None if trace_blocked else time.monotonic_ns())
            try:
                save_best_effort(capture)
            except CollectorCancelled:
                result = 128 + cancelled[0]
                capture.receipt.update(instrumentationReturnCode=None if trace_blocked else result,
                                       wrapperReturnCode=result, interruptSignals=cancelled)
                save_best_effort(capture)
        finally:
            for signum, handler in previous_handlers.items():
                signal.signal(signum, handler)
        try:
            print('Diagnostic trace receipt: ' + str(capture.directory / 'TRACE-RECEIPT.json'), file=sys.stderr)
        except OSError:
            pass
    return result if result >= 0 else 128 - result



if __name__ == '__main__':
    raise SystemExit(main())
