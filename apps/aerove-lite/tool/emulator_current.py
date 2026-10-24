"""Own one emulator for already-verified, same-run current runtime artifacts."""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import tempfile

import ci_artifacts as artifacts
from emulator_qa79 import (GPU_BACKEND, RENDERER_LABEL, emulator_environment, renderer_evidence,
                          crash_inventory, preserve_owned_crash, monitor, stop_owned,
                          finish_emulator, sha)

APK_ROOT = Path('apps/aerove-lite/app/build/outputs/apk')


def verify_current_runtime(directory):
    expected = artifacts.identity()
    value = artifacts.verify(directory, expected, runtime=True)
    apks = {}
    for name, location in [('app-release.apk', 'release'),
                           ('app-release-androidTest.apk', 'androidTest/release')]:
        original = Path(directory) / 'apks' / name
        installed = APK_ROOT / location / name
        if installed.is_symlink() or sha(installed) != sha(original):
            raise ValueError('Installed APK differs from verified current runtime artifact')
        apks[name] = sha(installed)
    return dict(identity=expected, apks=apks, runtimeManifestSha256=sha(Path(directory) / 'source-manifest.json'),
                apkInspectorOutcome=value['apkInspectorOutcome'])


def validate_renderer_proof(path):
    proof = json.loads(Path(path).read_text())
    if (proof.get('schema') != 1 or proof.get('owner') != 'emulator_current.py'
            or proof.get('identity') != artifacts.identity()
            or proof.get('deviceSerial') != os.environ.get('ANDROID_SERIAL')
            or proof.get('gpuBackend') != GPU_BACKEND
            or proof.get('rendererBackend') != RENDERER_LABEL):
        raise ValueError('Invalid current-run renderer proof')
    renderer_evidence('\n'.join(proof['rendererEvidence']))
    actual = verify_current_runtime(proof['runtimeDirectory'])
    if any(proof.get(key) != actual[key] for key in actual):
        raise ValueError('Current runtime proof identity or APK hashes changed')
    return proof



def write_owner_receipt(path, receipt):
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=path.parent,
                                         prefix='.EMULATOR-OWNER-', suffix='.tmp',
                                         delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(receipt, stream, indent=2)
            stream.write('\n')
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def check_cancelled(receipt):
    if receipt.get('interruptSignals'):
        raise InterruptedError('Owner cancellation observed during runtime')


class CancellationView:
    def __init__(self, child, receipt):
        self.child = child
        self.receipt = receipt

    def __getattr__(self, name):
        return getattr(self.child, name)

    def poll(self):
        check_cancelled(self.receipt)
        return self.child.poll()

    def wait(self, *args, **kwargs):
        check_cancelled(self.receipt)
        try:
            return self.child.wait(*args, **kwargs)
        finally:
            check_cancelled(self.receipt)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('phase', choices=['matrix', 'remaining'])
    parser.add_argument('--runtime-directory', type=Path, required=True)
    args = parser.parse_args()
    if sys.platform != 'linux':
        raise ValueError('The real acceptance owner requires the existing Linux/KVM runner')
    if os.environ.get('AEROVE_QA79_PROOF') or os.environ.get('AEROVE_CURRENT_RENDERER_PROOF'):
        raise ValueError('Current owner requires fresh, unmixed renderer proof')
    current = verify_current_runtime(args.runtime_directory)
    directory = Path(os.environ['RUNNER_TEMP']) / 'lite-diagnostics' / args.phase
    directory.mkdir(parents=True, exist_ok=True)
    emulator_command = [str(Path(os.environ['ANDROID_HOME']) / 'emulator/emulator'), '-port', '5554', '-avd', 'test', '-no-window', '-gpu', GPU_BACKEND, '-no-snapshot', '-noaudio', '-no-boot-anim']
    receipt = dict(app=current['identity'], runtime=current, harnessRevision=os.environ['GITHUB_SHA'], harnessRun=os.environ['GITHUB_RUN_ID'], command=emulator_command, ownerPid=os.getpid(), unexpectedEmulatorExit=False, cleanupRequested=False)
    environment = dict(os.environ, ANDROID_SERIAL='emulator-5554')
    interrupt_signals = []
    receipt['interruptSignals'] = interrupt_signals
    phase = 'runtime'
    late_handling = False
    def interrupt(signum, frame):
        nonlocal late_handling
        interrupt_signals.append(signum)
        receipt['result'] = 1
        if phase == 'finalized' and not late_handling:
            late_handling = True
            try:
                write_owner_receipt(directory / 'EMULATOR-OWNER.json', receipt)
            finally:
                raise InterruptedError(f'Finalized owner interrupted by signal {signum}')
    signal.signal(signal.SIGTERM, interrupt)
    signal.signal(signal.SIGINT, interrupt)
    emulator = acceptance = None
    result = 1
    crash_before = crash_inventory()
    try:
        check_cancelled(receipt)
        with (directory / 'emulator.stdout.log').open('wb') as out, (directory / 'emulator.stderr.log').open('wb') as err:
            emulator = subprocess.Popen(emulator_command, stdin=subprocess.DEVNULL, stdout=out, stderr=err, start_new_session=True, env=emulator_environment(os.environ))
            receipt['emulatorPid'] = emulator.pid
            check_cancelled(receipt)
            deadline = time.monotonic() + 600
            while True:
                check_cancelled(receipt)
                if emulator.poll() is not None:
                    raise RuntimeError('Owned emulator exited during boot')
                try:
                    boot = subprocess.run(['adb', '-s', 'emulator-5554', 'shell', 'getprop', 'sys.boot_completed'], capture_output=True, text=True, timeout=10)
                except subprocess.TimeoutExpired:
                    boot = subprocess.CompletedProcess([], 1, '')
                if boot.returncode == 0 and boot.stdout.strip() == '1':
                    break
                if time.monotonic() >= deadline:
                    raise TimeoutError('Existing 600-second boot deadline reached')
                time.sleep(2)
            check_cancelled(receipt)
            receipt['rendererEvidence'] = renderer_evidence((directory / 'emulator.stdout.log').read_text(errors='replace'))
            proof = dict(current, schema=1, owner='emulator_current.py',
                         runtimeDirectory=str(args.runtime_directory.resolve()),
                         deviceSerial='emulator-5554', gpuBackend=GPU_BACKEND,
                         rendererBackend=RENDERER_LABEL, rendererEvidence=receipt['rendererEvidence'])
            proof_path = directory / 'CURRENT-RENDERER-PROOF.json'
            proof_path.write_text(json.dumps(proof, indent=2) + '\n')
            environment['AEROVE_CURRENT_RENDERER_PROOF'] = str(proof_path.resolve())
            subprocess.run(['adb', '-s', 'emulator-5554', 'shell', 'input', 'keyevent', '82'], check=True, timeout=10)
            check_cancelled(receipt)
            acceptance = subprocess.Popen(['bash', 'apps/aerove-lite/tool/emulator_acceptance.sh', args.phase], env=environment, start_new_session=True)
            check_cancelled(receipt)
            result = monitor(emulator, CancellationView(acceptance, receipt), receipt)
            check_cancelled(receipt)
    finally:
        phase = 'cleanup'
        cleanup_errors = []
        try:
            try:
                if acceptance is not None:
                    stop_owned(acceptance)
            except Exception as error:
                cleanup_errors.append(dict(owner='acceptance', error=str(error)))
            finally:
                if acceptance is not None:
                    receipt['acceptanceReturnCode'] = acceptance.returncode
                try:
                    if emulator is not None:
                        result = finish_emulator(emulator, receipt, result)
                except Exception as error:
                    cleanup_errors.append(dict(owner='emulator', error=str(error)))
                    receipt.update(emulatorReturnCode=emulator.returncode,
                                   emulatorSignal=-emulator.returncode if emulator.returncode is not None and emulator.returncode < 0 else None)
                finally:
                    if emulator is not None:
                        try:
                            receipt['crashpad'] = preserve_owned_crash(directory, emulator.pid, crash_before)
                        except Exception as error:
                            receipt['crashpad'] = dict(status='collection failed', error=str(error))
        finally:
            receipt.update(interruptSignals=interrupt_signals, cleanupErrors=cleanup_errors)
            phase = 'finalized'
            if interrupt_signals or cleanup_errors:
                result = 1
            receipt['result'] = result
            write_owner_receipt(directory / 'EMULATOR-OWNER.json', receipt)

    return result


if __name__ == '__main__':
    raise SystemExit(main())
