"""Isolated software-CPU boot feasibility probe; never application acceptance."""
import json
import os
from pathlib import Path
import signal
import subprocess
import time


def command(sdk):
    return [str(sdk / 'emulator/emulator'), '-avd', 'tcg_probe', '-port', '5554',
            '-accel', 'off', '-cores', '1', '-memory', '2048', '-no-window',
            '-gpu', 'swiftshader', '-no-snapshot', '-noaudio', '-no-boot-anim']


def app_probe(adb, output, receipt, runtime):
    from lite368_diagnostic import verified_runtime, verify_signatures, IDENTITY
    proof = verified_runtime(Path(runtime), installed=False)
    verify_signatures(Path(runtime), Path(os.environ['ANDROID_HOME']) / 'build-tools/37.0.0/apksigner')
    receipt.update(originalAppIdentity=IDENTITY, harnessIdentity=proof['harnessIdentity'],
                   appCompiled=False, functionalAcceptance=False)

    def invoke(name, args, timeout=120):
        print('Diagnostic step: ' + name, flush=True)
        step_started = time.monotonic()
        with (output / name).open('wb') as target:
            try:
                result = subprocess.run(adb + args, stdout=target, stderr=subprocess.PIPE,
                                        timeout=timeout, check=False)
            except subprocess.TimeoutExpired as error:
                receipt[name] = dict(timedOut=True, timeoutSeconds=timeout,
                                     elapsedSeconds=round(time.monotonic() - step_started, 2),
                                     stderr=(error.stderr or b'').decode(errors='replace')[-2000:])
                raise
        receipt[name] = dict(returncode=result.returncode,
                             elapsedSeconds=round(time.monotonic() - step_started, 2),
                             stderr=result.stderr.decode(errors='replace')[-2000:])
        if result.returncode:
            raise RuntimeError('App diagnostic command failed: ' + name)

    try:
        invoke('app-install.txt', ['install', '--no-streaming',
                str(Path(runtime) / 'apks/app-release.apk')], 900)
        invoke('installed-package.txt', ['shell', 'pm', 'path', 'dev.aerove.lite'])
        if 'package:' not in (output / 'installed-package.txt').read_text():
            raise RuntimeError('APK install returned without an installed package')
        receipt['appInstalled'] = True
        for stage in ('cold-launch', 'background-return', 'force-stop-relaunch'):
            if stage == 'background-return':
                invoke('home.txt', ['shell', 'input', 'keyevent', 'KEYCODE_HOME'])
                time.sleep(10)
            elif stage == 'force-stop-relaunch':
                invoke('force-stop.txt', ['shell', 'am', 'force-stop', 'dev.aerove.lite'])
            invoke(stage + '-start.txt', ['shell', 'am', 'start', '-W', '-n',
                    'dev.aerove.lite/dev.aerove.lite.FlutterMainActivity'], 180)
            time.sleep(60)
            invoke(stage + '-screen.png', ['exec-out', 'screencap', '-p'])
            invoke(stage + '-activities.txt', ['shell', 'dumpsys', 'activity', 'activities'])
        receipt['appDiagnosis'] = 'launch_lifecycle_evidence_collected_only'
    finally:
        invoke('app-logcat.txt', ['logcat', '-d', '-t', '6000'])


def main():
    sdk = Path(os.environ['ANDROID_HOME'])
    output = Path(os.environ['RUNNER_TEMP']) / 'software-emulator-probe'
    output.mkdir(parents=True, exist_ok=False)
    receipt = dict(diagnosticOnly=True, accepted=False, functionalAcceptance=False,
                   releaseAccepted=False, appInstalled=False, acceleration='off',
                   harnessRevision=os.environ['GITHUB_SHA'],
                   harnessRun=os.environ['GITHUB_RUN_ID'], booted=False,
                   command=command(sdk), bootDeadlineSeconds=1200)
    process = None
    started = time.monotonic()
    adb = [str(sdk / 'platform-tools/adb'), '-s', 'emulator-5554']
    try:
        with (output / 'emulator.log').open('wb') as log:
            process = subprocess.Popen(command(sdk), stdout=log, stderr=subprocess.STDOUT,
                                       stdin=subprocess.DEVNULL, start_new_session=True)
            receipt['ownedPid'] = process.pid
            while time.monotonic() - started < receipt['bootDeadlineSeconds']:
                if process.poll() is not None:
                    raise RuntimeError('Emulator exited before Android boot completed')
                try:
                    probe = subprocess.run(adb + ['shell', 'getprop', 'sys.boot_completed'],
                                           capture_output=True, timeout=15, check=False)
                    if probe.returncode == 0 and probe.stdout.strip() == b'1':
                        receipt['booted'] = True
                        break
                except subprocess.TimeoutExpired:
                    pass
                time.sleep(10)
            if not receipt['booted']:
                raise TimeoutError('Software CPU emulation did not boot within 1200 seconds')
            for name, args in [('properties.txt', ['shell', 'getprop']),
                               ('logcat.txt', ['logcat', '-d', '-t', '3000']),
                               ('android-screen.png', ['exec-out', 'screencap', '-p'])]:
                with (output / name).open('wb') as target:
                    result = subprocess.run(adb + args, stdout=target, stderr=subprocess.PIPE,
                                            timeout=60, check=False)
                receipt[name] = dict(returncode=result.returncode,
                                     stderr=result.stderr.decode(errors='replace')[-2000:])
                if result.returncode:
                    raise RuntimeError('Boot evidence collection failed: ' + name)
            receipt['result'] = 'android_boot_observed_only'
            runtime = os.environ.get('AEROVE_SOFTWARE_APK_RUNTIME')
            if runtime:
                ready = subprocess.run(adb + ['shell', 'pm', 'path', 'android'],
                                       capture_output=True, timeout=180, check=False)
                receipt['packageManagerReady'] = ready.returncode == 0 and b'package:' in ready.stdout
                if not receipt['packageManagerReady'] or process.poll() is not None:
                    raise RuntimeError('Emulator or package manager not ready for application diagnosis')
                app_probe(adb, output, receipt, runtime)
                receipt['result'] = 'app_launch_diagnostic_only'
            if process.poll() is not None:
                raise RuntimeError('Emulator exited before diagnostic cleanup')
    except BaseException as error:
        receipt['result'] = 'failed'
        receipt['error'] = repr(error)
        raise
    finally:
        if process is not None:
            receipt['returncodeBeforeCleanup'] = process.poll()
            receipt['cleanupRequested'] = process.poll() is None
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=10)
            receipt['emulatorReturncode'] = process.returncode
        receipt['elapsedSeconds'] = round(time.monotonic() - started, 2)
        if process is not None and process.returncode not in (0, -signal.SIGTERM):
            receipt['result'] = 'failed_emulator_exit'
        (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
        if receipt['result'] == 'failed_emulator_exit':
            raise RuntimeError('Abnormal emulator exit: ' + str(process.returncode))


if __name__ == '__main__':
    main()
