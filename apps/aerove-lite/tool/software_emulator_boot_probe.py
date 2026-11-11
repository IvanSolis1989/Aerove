"""Isolated software-CPU boot feasibility probe; never application acceptance."""
import json
import os
from pathlib import Path
import signal
import shutil
import subprocess
import sys
import time


def host_capacity():
    memory = dict(line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines())
    available = int(memory['MemAvailable'].split()[0]) // 1024
    if available < 7000:
        raise RuntimeError('At least 7000 MiB host available memory required for isolated 4096 MiB guest')
    return dict(hostAvailableMiB=available, hostCpuCount=os.cpu_count(), guestMemoryMiB=4096,
                guestCores=1, image='system-images;android-33;default;x86_64')


def command(sdk):
    return [str(sdk / 'emulator/emulator'), '-avd', 'tcg_probe', '-port', '5554',
            '-accel', 'off', '-cores', '1', '-memory', '4096', '-no-window',
            '-gpu', 'swangle', '-feature', '-Vulkan',
            '-no-snapshot', '-noaudio', '-no-boot-anim']




def debug_command(sdk):
    debugger = shutil.which('gdb')
    if not debugger:
        raise RuntimeError('GDB required for owned-emulator native crash diagnosis')
    return [debugger, '--batch', '--return-child-result', '-nx',
            '-ex', 'set pagination off', '-ex', 'set confirm off',
            '-ex', 'set disable-randomization off',
            '-ex', 'handle SIGPIPE nostop noprint pass',
            '-ex', 'handle SIGILL stop print nopass',
            '-ex', 'handle SIGSEGV stop print nopass',
            '-ex', 'run', '-ex', 'thread apply all bt 20',
            '-ex', 'info registers', '-ex', 'x/16i $pc-16',
            '-ex', 'info sharedlibrary', '-ex', 'quit', '--args'] + command(sdk)

def collect_native_crash_evidence(output, receipt):
    """Preserve only this disposable runner's emulator crash database, bounded."""
    evidence = dict(files=[], errors=[], totalBytes=0, limitBytes=50 * 1024 * 1024)
    receipt['nativeCrashEvidence'] = evidence
    try:
        (output / 'host-cpuinfo.txt').write_text(Path('/proc/cpuinfo').read_text())
        for database in sorted(Path('/tmp/android-runner').glob('emu-crash-*.db')):
            candidates = sorted(database.rglob('*')) if database.is_dir() else [database]
            for source in candidates:
                if source.is_symlink() or not source.is_file():
                    continue
                size = source.stat().st_size
                if evidence['totalBytes'] + size > evidence['limitBytes']:
                    evidence['errors'].append('Crash evidence exceeds bounded size budget')
                    continue
                relative = source.relative_to(Path('/tmp/android-runner'))
                target = output / 'native-crash' / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
                evidence['totalBytes'] += size
                evidence['files'].append(dict(path=str(relative), bytes=size))
    except Exception as error:
        evidence['errors'].append(repr(error))

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
        invoke('guest-memory.txt', ['shell', 'cat', '/proc/meminfo'])
        invoke('guest-google-packages.txt', ['shell', 'pm', 'list', 'packages', 'com.google.android.gms'])
        if 'package:' in (output / 'guest-google-packages.txt').read_text():
            raise RuntimeError('AOSP probe unexpectedly contains Google Play services')
        invoke('app-install.txt', ['install', '--no-streaming',
                str(Path(runtime) / 'apks/app-release.apk')], 900)
        invoke('installed-package.txt', ['shell', 'pm', 'path', 'dev.aerove.lite'])
        if 'package:' not in (output / 'installed-package.txt').read_text():
            raise RuntimeError('APK install returned without an installed package')
        receipt['appInstalled'] = True
        for stage in ('cold-launch', 'background-return', 'force-stop-relaunch'):
            invoke(stage + '-wake.txt', ['shell', 'input', 'keyevent', 'KEYCODE_WAKEUP'])
            invoke(stage + '-dismiss-keyguard.txt', ['shell', 'wm', 'dismiss-keyguard'])
            if stage == 'background-return':
                invoke('home.txt', ['shell', 'input', 'keyevent', 'KEYCODE_HOME'])
                time.sleep(10)
            elif stage == 'force-stop-relaunch':
                invoke('force-stop.txt', ['shell', 'am', 'force-stop', 'dev.aerove.lite'])
            invoke(stage + '-start.txt', ['shell', 'am', 'start', '-W', '-n',
                    'dev.aerove.lite/dev.aerove.lite.FlutterMainActivity'], 180)
            time.sleep(60)
            for attempt in range(1, 4):
                prefix = stage + '-ready-' + str(attempt)
                invoke(prefix + '-wake.txt', ['shell', 'input', 'keyevent', 'KEYCODE_WAKEUP'])
                invoke(prefix + '-keyguard.txt', ['shell', 'wm', 'dismiss-keyguard'])
                time.sleep(15)
                invoke(prefix + '-power.txt', ['shell', 'dumpsys', 'power'])
                invoke(prefix + '-activities.txt', ['shell', 'dumpsys', 'activity', 'activities'])
                activities = (output / (prefix + '-activities.txt')).read_text()
                if ('isSleeping=true' not in activities and 'isKeyguardShowing=true' not in activities
                        and 'isSleeping=false' in activities and 'isKeyguardShowing=false' in activities):
                    receipt[stage + '-screenReadiness'] = dict(awake=True, unlocked=True, attempts=attempt)
                    break
            else:
                invoke(stage + '-locked-screen.png', ['exec-out', 'screencap', '-p'])
                raise RuntimeError('Screen not awake and unlocked after three readiness checks: ' + stage)
            invoke(stage + '-screen.png', ['exec-out', 'screencap', '-p'])
        receipt['appDiagnosis'] = 'launch_lifecycle_evidence_collected_only'
        from software_functional_probe import run as functional_probe
        functional_probe(adb, output, receipt)
    finally:
        primary_failure = sys.exc_info()[0] is not None
        try:
            invoke('app-logcat.txt', ['logcat', '-d', '-t', '6000'], 30)
        except BaseException as error:
            receipt['logcatCollectionError'] = repr(error)
            if not primary_failure:
                raise


def main():
    capacity = host_capacity()
    sdk = Path(os.environ['ANDROID_HOME'])
    output = Path(os.environ['RUNNER_TEMP']) / 'software-emulator-probe'
    output.mkdir(parents=True, exist_ok=False)
    receipt = dict(diagnosticOnly=True, accepted=False, functionalAcceptance=False,
                   visualAcceptance=False, graphicsExperiment='swangle-vulkan-disabled',
                   releaseAccepted=False, appInstalled=False, acceleration='off',
                   harnessRevision=os.environ['GITHUB_SHA'],
                   harnessRun=os.environ['GITHUB_RUN_ID'], booted=False,
                   command=command(sdk), bootDeadlineSeconds=1200)
    receipt['hostCapacity'] = capacity
    receipt['debugger'] = 'gdb-owned-child-only'
    receipt['processReturncodeMeaning'] = 'debugger wrapper, not raw emulator signal'
    launch_command = debug_command(sdk)
    receipt['launchCommand'] = launch_command
    process = None
    started = time.monotonic()
    adb = [str(sdk / 'platform-tools/adb'), '-s', 'emulator-5554']
    try:
        with (output / 'emulator.log').open('wb') as log:
            process = subprocess.Popen(launch_command, stdout=log, stderr=subprocess.STDOUT,
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
                receipt['result'] = 'app_targeted_diagnostic_only'
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
                    process.wait(timeout=35)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=10)
            receipt['emulatorReturncode'] = process.returncode
        collect_native_crash_evidence(output, receipt)
        receipt['elapsedSeconds'] = round(time.monotonic() - started, 2)
        if process is not None and process.returncode not in (0, -signal.SIGTERM):
            receipt['applicationResultBeforeCleanup'] = receipt['result']
            receipt['emulatorCleanupFault'] = bool(receipt.get('cleanupRequested'))
            receipt['result'] = 'failed_emulator_exit'
        (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
        if receipt['result'] == 'failed_emulator_exit':
            raise RuntimeError('Abnormal emulator exit: ' + str(process.returncode))


if __name__ == '__main__':
    main()

