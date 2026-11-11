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
    except BaseException as error:
        receipt['result'] = 'failed'
        receipt['error'] = repr(error)
        raise
    finally:
        if process is not None:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=10)
            receipt['emulatorReturncode'] = process.returncode
        receipt['elapsedSeconds'] = round(time.monotonic() - started, 2)
        (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')


if __name__ == '__main__':
    main()
