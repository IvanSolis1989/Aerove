"""Own a fresh CI emulator for the immutable Std #40 reference, without builds."""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

from emulator_bootstrap import wait_ready
from emulator_current import CancellationView, check_cancelled, write_owner_receipt
from emulator_qa79 import (GPU_BACKEND, RENDERER_LABEL, emulator_environment,
                          renderer_evidence, crash_inventory, preserve_owned_crash,
                          monitor, stop_owned, finish_emulator, sha)

SERIAL = 'emulator-5554'
ORIGINAL_SOURCE = 'c133772fc83b7984bdacb11e5dd41e2d65098771'
ORIGINAL_RUN = 40082294388
PROBE_PINS = {
    'native-bridge-readonly-check.sh': '3c547d96f9d301baca9cd64305203df442bb60fb7a64db9bf68790b4eca60ca4',
    'readonly-check.py': 'ea660cd5dfe900af6328338d852e5f2117c2e13e0c40433bf69e24af272f49ba',
    'probe-original-standard.py': '38cd3be9d3042b5b1f4ea3782619e3f6d94e90c1d9ec06ec24ff7e022405f4f4',
}


class ChildExit(RuntimeError):
    def __init__(self, phase, code):
        super().__init__(f'{phase} child exited {code}')
        self.code = code if code > 0 else 128 - code


class DeadlineView(CancellationView):
    def __init__(self, child, receipt, deadline):
        super().__init__(child, receipt)
        self.deadline = deadline

    def check_deadline(self):
        check_cancelled(self.receipt)
        if time.monotonic() >= self.deadline:
            raise TimeoutError('Owned Std reference stage deadline reached')

    def poll(self):
        self.check_deadline()
        return super().poll()

    def wait(self, *args, **kwargs):
        self.check_deadline()
        return super().wait(*args, **kwargs)


def first_cause(error):
    seen = set()
    while error.__context__ is not None and id(error) not in seen:
        seen.add(id(error))
        error = error.__context__
    return error


def record_failure(receipt, phase, error):
    original = first_cause(error)
    code = getattr(original, 'code', 1)
    if isinstance(original, KeyboardInterrupt):
        code = 130
    if isinstance(original, InterruptedError) and receipt.get('interruptSignals'):
        code = 128 + receipt['interruptSignals'][0]
    if not isinstance(code, int) or code == 0:
        code = 1
    item = dict(phase=phase, errorType=type(original).__name__, error=str(original),
                observedError=repr(error), result=code)
    if 'primaryFailure' not in receipt:
        receipt['primaryFailure'] = item
        receipt['primaryResult'] = code
    else:
        receipt.setdefault('secondaryFailures', []).append(item)


def persist(directory, receipt):
    try:
        write_owner_receipt(directory / 'EMULATOR-OWNER.json', receipt)
        return True
    except BaseException as error:
        record_failure(receipt, 'owner-receipt', error)
        receipt['result'] = receipt['primaryResult']
        receipt['status'] = 'FAILED'
        try:
            print('OWNER_RECEIPT_FALLBACK_JSON=' + json.dumps(receipt), file=sys.stderr, flush=True)
        except OSError:
            pass
        return False


def run_stage(name, command, limit, emulator, receipt, owned, directory, environment, deadline):
    check_cancelled(receipt)
    if time.monotonic() >= deadline:
        raise TimeoutError('Owned Std reference overall deadline reached')
    with (directory / f'{name}.stdout.log').open('wb') as out, (directory / f'{name}.stderr.log').open('wb') as err:
        child = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=out, stderr=err,
                                 start_new_session=True, env=environment)
        row = dict(phase=name, pid=child.pid, command=command)
        owned.append((child, row))
        receipt['children'].append(row)
        result = monitor(emulator, DeadlineView(child, receipt, min(deadline, time.monotonic() + limit)), receipt)
        row['returnCode'] = child.returncode
        check_cancelled(receipt)
        if result:
            raise ChildExit(name, result)


def run_owner(apk_directory, directory, tools_directory):
    if sys.platform != 'linux' or os.environ.get('GITHUB_ACTIONS') != 'true':
        raise ValueError('Std reference requires a fresh isolated GitHub Linux/KVM runner')
    directory.mkdir(parents=True, exist_ok=False)
    sdk = Path(os.environ['ANDROID_HOME'])
    emulator_command = [str(sdk / 'emulator/emulator'), '-port', '5554', '-avd', 'test',
                        '-no-window', '-gpu', GPU_BACKEND, '-no-snapshot', '-noaudio', '-no-boot-anim']
    receipt = dict(schema=1, owner='emulator_standard_reference.py', ownerPid=os.getpid(),
                   originalSource=ORIGINAL_SOURCE, originalRun=ORIGINAL_RUN, originalArtifact=12555818245,
                   harnessSource=os.environ['GITHUB_SHA'], harnessRun=os.environ['GITHUB_RUN_ID'],
                   deviceSerial=SERIAL, gpuBackend=GPU_BACKEND, rendererBackend=RENDERER_LABEL,
                   command=emulator_command, children=[], interruptSignals=[], cleanupErrors=[],
                   unexpectedEmulatorExit=False, cleanupRequested=False, primaryResult=0,
                   status='STARTED', apkRebuilt=False, vpnRequested=False, demoInjected=False,
                   twelveThemeCaptures=False)
    deadline = time.monotonic() + 780
    phase = 'runtime'
    emulator = None
    owned = []
    before = None

    def check_owner(value):
        check_cancelled(value)
        if time.monotonic() >= deadline:
            raise TimeoutError('Owned Std reference overall 780-second deadline reached')

    def interrupt(signum, frame):
        receipt['interruptSignals'].append(signum)
        if phase == 'finalized':
            record_failure(receipt, 'finalized-cancellation', SystemExit(128 + signum))
            receipt['result'] = receipt['primaryResult']
            receipt['status'] = 'FAILED'
            persist(directory, receipt)
            raise SystemExit(128 + signum)

    previous = {sig: signal.signal(sig, interrupt) for sig in (signal.SIGTERM, signal.SIGINT)}
    environment = emulator_environment(dict(os.environ, ANDROID_SERIAL=SERIAL))
    try:
        check_owner(receipt)
        receipt['probePins'] = {}
        for name, expected in PROBE_PINS.items():
            path = tools_directory / name
            if path.is_symlink() or sha(path) != expected:
                raise ValueError('Closed02 probe source mismatch: ' + name)
            receipt['probePins'][name] = expected
        before = crash_inventory()
        with (directory / 'emulator.stdout.log').open('wb') as out, (directory / 'emulator.stderr.log').open('wb') as err:
            emulator = subprocess.Popen(emulator_command, stdin=subprocess.DEVNULL, stdout=out, stderr=err,
                                        start_new_session=True, env=environment)
            receipt['emulatorPid'] = emulator.pid
            check_owner(receipt)
            wait_ready(emulator, directory, receipt, check_owner)
            check_owner(receipt)
            receipt['rendererEvidence'] = renderer_evidence((directory / 'emulator.stdout.log').read_text(errors='replace'))
            run_stage('readonly', ['bash', str(tools_directory / 'native-bridge-readonly-check.sh'),
                                   str(directory / 'properties')], 180, emulator, receipt, owned,
                      directory, environment, deadline)
            run_stage('probe', [sys.executable, str(tools_directory / 'probe-original-standard.py'),
                                '--apk-directory', str(apk_directory), '--evidence', str(directory / 'first-launch'),
                                '--serial', SERIAL, '--build-tools', str(sdk / 'build-tools/35.0.0')],
                      300, emulator, receipt, owned, directory, environment, deadline)
            check_owner(receipt)
            receipt['status'] = 'INITIAL REFERENCE CAPTURE COMPLETE; REVIEW REQUIRED; NOT TWELVE THEMES'
    except BaseException as error:
        record_failure(receipt, 'runtime', error)
    finally:
        phase = 'cleanup'
        for child, row in reversed(owned):
            try:
                row['cleanup'] = stop_owned(child)
            except BaseException as error:
                receipt['cleanupErrors'].append(dict(ownerPid=child.pid, error=repr(error)))
                record_failure(receipt, 'child-cleanup', error)
            finally:
                row['returnCode'] = child.returncode
        if emulator is not None:
            try:
                cleanup_result = finish_emulator(emulator, receipt, receipt['primaryResult'])
                if cleanup_result and not receipt['primaryResult']:
                    record_failure(receipt, 'emulator-unexpected-exit', RuntimeError('Emulator exited before owned cleanup'))
            except BaseException as error:
                receipt['cleanupErrors'].append(dict(ownerPid=emulator.pid, error=repr(error)))
                record_failure(receipt, 'emulator-cleanup', error)
            finally:
                receipt['emulatorReturnCode'] = emulator.returncode
                try:
                    receipt['crashpad'] = preserve_owned_crash(directory, emulator.pid, before or {})
                except BaseException as error:
                    receipt['crashpad'] = dict(status='collection failed', error=repr(error))
                    record_failure(receipt, 'crash-evidence', error)
        phase = 'finalized'
        if receipt['interruptSignals'] and not receipt['primaryResult']:
            record_failure(receipt, 'cancellation', SystemExit(128 + receipt['interruptSignals'][0]))
        if receipt['primaryResult']:
            receipt['status'] = 'FAILED'
        receipt['result'] = receipt['primaryResult']
        persist(directory, receipt)
        for sig, handler in previous.items():
            signal.signal(sig, handler)
    return receipt['result']


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk-directory', required=True, type=Path)
    parser.add_argument('--diagnostics', required=True, type=Path)
    parser.add_argument('--tools-directory', type=Path, default=Path(__file__).with_name('standard_reference'))
    args = parser.parse_args()
    return run_owner(args.apk_directory, args.diagnostics, args.tools_directory)


if __name__ == '__main__':
    raise SystemExit(main())
