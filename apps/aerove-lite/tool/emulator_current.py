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
from emulator_bootstrap import wait_ready
from emulator_qa79 import (GPU_BACKEND, RENDERER_LABEL, emulator_environment, renderer_evidence,
                          crash_inventory, preserve_owned_crash, monitor, stop_owned,
                          finish_emulator, sha)

APK_ROOT = Path('apps/aerove-lite/app/build/outputs/apk')


def verify_current_runtime(directory):
    if os.environ.get('AEROVE_ORIGINAL84_PROOF') or os.environ.get('AEROVE_ORIGINAL_RUNTIME_PROOF'):
        from emulator_original84 import verify_original_runtime
        return verify_original_runtime(directory)
    expected = artifacts.identity()
    value = artifacts.verify(directory, expected, runtime=True)
    from verify_apk import verify_bundled_script_apk
    bundled_script = verify_bundled_script_apk(Path(directory) / 'apks/app-release.apk')
    apks = {}
    for name, location in [('app-release.apk', 'release'),
                           ('app-release-androidTest.apk', 'androidTest/release')]:
        original = Path(directory) / 'apks' / name
        installed = APK_ROOT / location / name
        if installed.is_symlink() or sha(installed) != sha(original):
            raise ValueError('Installed APK differs from verified current runtime artifact')
        apks[name] = sha(installed)
    return dict(identity=expected, apks=apks, runtimeManifestSha256=sha(Path(directory) / 'source-manifest.json'),
                apkInspectorOutcome=value['apkInspectorOutcome'], bundledMeowScript=bundled_script)


def validate_renderer_proof(path):
    if os.environ.get('AEROVE_ORIGINAL84_PROOF') or os.environ.get('AEROVE_ORIGINAL_RUNTIME_PROOF'):
        from emulator_original84 import validate_original_renderer
        return validate_original_renderer(path)
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

def original_sample_phase_selector(phase, sample, runtime):
    if phase != 'original-sample':
        if sample is not None:
            raise ValueError('A sample is valid only for the original-sample phase')
        return None
    from emulator_original84 import SAMPLE_TARGETS, original_sample_selector
    if runtime.get('runtimeTarget') not in SAMPLE_TARGETS:
        raise ValueError('Single-sample phase requires a fixed original runtime')
    return original_sample_selector(sample, runtime['runtimeTarget'])


def guard_flutter_phase(phase, sample=None, runtime=None):
    if phase not in ('flutter-ui', 'flutter-platform', 'flutter-full'):
        return
    if (sample is not None or any(os.environ.get(name) for name in (
            'AEROVE_ORIGINAL84_PROOF', 'AEROVE_ORIGINAL_RUNTIME_PROOF',
            'AEROVE_ORIGINAL_RUNTIME_TARGET', 'AEROVE_ORIGINAL_SAMPLE',
            'AEROVE_ORIGINAL_SAMPLE_SELECTOR')) or os.environ.get('AEROVE_BETA14_PROFILE') == '1'
            or runtime and any(runtime.get(name) for name in (
                'runtimeTarget', 'diagnosticOriginal84', 'originalAppIdentity'))):
        raise ValueError('flutter-ui requires current production runtime, without original/profile/sample opt-ins')


def flutter_ui_inputs(directory, runtime):
    """Require the inspected BUILD_INFO in the same verified transport before starting an emulator."""
    guard_flutter_phase('flutter-ui', runtime=runtime)
    directory = Path(directory)
    info_path = directory / 'BUILD_INFO.json'
    manifest = json.loads((directory / 'source-manifest.json').read_text())
    entries = [row for row in manifest['files'] if row['path'] == 'BUILD_INFO.json']
    if (info_path.is_symlink() or not info_path.is_file() or len(entries) != 1
            or entries[0]['bytes'] != info_path.stat().st_size or entries[0]['sha256'] != sha(info_path)):
        raise ValueError('flutter-ui requires the sealed runtime BUILD_INFO.json')
    info = json.loads(info_path.read_text())
    from verify_apk import FLUTTER_ASSET_PREFIX, FLUTTER_RELEASE_ASSETS
    packaging = info.get('flutter_packaging', {})
    if (packaging.get('schema') != 1 or packaging.get('abi') != 'x86_64'
            or set(packaging.get('libraries', {})) != {'lib/x86_64/libapp.so', 'lib/x86_64/libflutter.so'}
            or set(packaging.get('assets', {})) != {FLUTTER_ASSET_PREFIX + name for name in FLUTTER_RELEASE_ASSETS}):
        raise ValueError('flutter-ui requires inspected Flutter release packaging')
    inputs = dict(apk=(APK_ROOT / 'release/app-release.apk').resolve(),
                  test_apk=(APK_ROOT / 'androidTest/release/app-release-androidTest.apk').resolve(),
                  build_info=info_path.resolve())
    from flutter_android_qa import validate_binding
    validate_binding(inputs['apk'], inputs['test_apk'], info, dict(runtime, owner='emulator_current.py'))
    return inputs


def phase_acceptance_command(phase, directory, flutter_inputs=None):
    if phase not in ('flutter-ui', 'flutter-platform', 'flutter-full'):
        return ['bash', 'apps/aerove-lite/tool/emulator_acceptance.sh', phase]
    if flutter_inputs is None:
        raise ValueError('flutter-ui requires validated original APK and BUILD_INFO inputs')
    sdk = Path(os.environ.get('ANDROID_SDK_ROOT', os.environ['ANDROID_HOME']))
    command = [sys.executable, 'apps/aerove-lite/tool/' + ('flutter_android_qa.py' if phase == 'flutter-ui' else 'android_acceptance.py'),
            '--apk', str(flutter_inputs['apk']), '--test-apk', str(flutter_inputs['test_apk']),
            '--build-info', str(flutter_inputs['build_info']),
            '--adb', str(sdk / 'platform-tools/adb'),
            '--apksigner', str(sdk / 'build-tools/37.0.0/apksigner'),
            '--serial', 'emulator-5554', '--output', str(Path(directory) / 'production-ui')]
    if phase != 'flutter-ui':
        if 'platform_apk' not in flutter_inputs:
            raise ValueError('Current platform/full suite requires a sealed same-target platform runner')
        command += ['--platform-apk', str(flutter_inputs['platform_apk']), '--phase', phase]
    return command


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('phase', choices=['matrix', 'remaining', 'original-sample', 'flutter-ui', 'flutter-platform', 'flutter-full'])
    parser.add_argument('--runtime-directory', type=Path, required=True)
    parser.add_argument('--sample')
    args = parser.parse_args()
    if (args.phase == 'original-sample') != (args.sample is not None):
        parser.error('--sample is required only for original-sample')
    if sys.platform != 'linux':
        raise ValueError('The real acceptance owner requires the existing Linux/KVM runner')
    if os.environ.get('AEROVE_QA79_PROOF') or os.environ.get('AEROVE_CURRENT_RENDERER_PROOF'):
        raise ValueError('Current owner requires fresh, unmixed renderer proof')
    guard_flutter_phase(args.phase, args.sample)
    current = verify_current_runtime(args.runtime_directory)
    sample_selector = original_sample_phase_selector(args.phase, args.sample, current)
    flutter_inputs = flutter_ui_inputs(args.runtime_directory, current) if args.phase in ('flutter-ui', 'flutter-platform', 'flutter-full') else None
    if args.phase in ('matrix', 'remaining') and not current.get('runtimeTarget'):
        info = json.loads((args.runtime_directory / 'BUILD_INFO.json').read_text())
        if info.get('flutter_packaging', {}).get('client_kind') == 'flutter-aot':
            raise ValueError('Current Flutter target cannot execute legacy Compose matrix/remaining; use flutter-platform/flutter-full')
    if args.phase in ('flutter-platform', 'flutter-full'):
        from android_acceptance_verify import platform_binding
        info = json.loads(flutter_inputs['build_info'].read_text())
        flutter_inputs['platform_apk'] = platform_binding(args.runtime_directory, info).resolve()
    directory = Path(os.environ['RUNNER_TEMP']) / 'lite-diagnostics' / args.phase
    directory.mkdir(parents=True, exist_ok=True)
    emulator_command = [str(Path(os.environ['ANDROID_HOME']) / 'emulator/emulator'), '-port', '5554', '-avd', 'test', '-no-window', '-gpu', GPU_BACKEND, '-no-snapshot', '-noaudio', '-no-boot-anim']
    receipt = dict(app=current['identity'], runtime=current, harnessRevision=os.environ['GITHUB_SHA'], harnessRun=os.environ['GITHUB_RUN_ID'], command=emulator_command, ownerPid=os.getpid(), unexpectedEmulatorExit=False, cleanupRequested=False)
    if args.phase == 'flutter-ui':
        receipt.update(phase='flutter-ui', representativeOnly=True, functionalAcceptance=False,
                       visualApproved=False, performanceAccepted=False, releaseAccepted=False)
    if sample_selector:
        receipt.update(sample=args.sample, testSelector=sample_selector, diagnosticOnly=True, accepted=False, promotionAccepted=False)
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
            wait_ready(emulator, directory, receipt, check_cancelled)
            check_cancelled(receipt)
            receipt['rendererEvidence'] = renderer_evidence((directory / 'emulator.stdout.log').read_text(errors='replace'))
            proof = dict(current, schema=1, owner='emulator_current.py',
                         runtimeDirectory=str(args.runtime_directory.resolve()),
                         deviceSerial='emulator-5554', gpuBackend=GPU_BACKEND,
                         rendererBackend=RENDERER_LABEL, rendererEvidence=receipt['rendererEvidence'])
            proof_path = directory / 'CURRENT-RENDERER-PROOF.json'
            proof_path.write_text(json.dumps(proof, indent=2) + '\n')
            environment['AEROVE_CURRENT_RENDERER_PROOF'] = str(proof_path.resolve())
            if environment.get('AEROVE_BETA14_PROFILE') == '1':
                if current.get('runtimeTarget') != 'beta14':
                    raise ValueError('Profile-only requires verified immutable beta14 runtime')
                from beta14_render_profile import proc_stat
                receipt['profileQemuStarttime'] = proc_stat((Path('/proc') / str(emulator.pid) / 'stat').read_text())['starttime']
                profile_owner = directory / 'PROFILE-OWNER.json'
                write_owner_receipt(profile_owner, receipt)
                environment['AEROVE_BETA14_PROFILE_OWNER'] = str(profile_owner.resolve())
            check_cancelled(receipt)
            acceptance_command = phase_acceptance_command(args.phase, directory, flutter_inputs)
            if args.phase == 'flutter-ui':
                receipt['acceptanceCommand'] = acceptance_command
            if args.sample:
                environment['AEROVE_ORIGINAL_RUNTIME_TARGET'] = current['runtimeTarget']
                environment['AEROVE_ORIGINAL_SAMPLE'] = args.sample
                environment['AEROVE_ORIGINAL_SAMPLE_SELECTOR'] = sample_selector
                acceptance_command.append(args.sample)
            acceptance = subprocess.Popen(acceptance_command, env=environment, start_new_session=True)
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
            if environment.get('AEROVE_BETA14_PROFILE') == '1':
                try:
                    write_owner_receipt(directory / 'EMULATOR-OWNER.json', receipt)
                except OSError as error:
                    # New diagnostics cannot replace a prior acceptance/cancellation result.
                    try: print('Profile owner receipt save failed: '+repr(error),file=sys.stderr)
                    except OSError: pass
                    if result == 0: result = 1
            else:
                write_owner_receipt(directory / 'EMULATOR-OWNER.json', receipt)

    return result


if __name__ == '__main__':
    raise SystemExit(main())
