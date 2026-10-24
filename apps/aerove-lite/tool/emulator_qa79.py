"""One-off, non-publishing Android QA of immutable run79 APKs; never builds an app."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import signal
import stat
import subprocess
import sys
import time
import zipfile

import lite_release as release
from verify_apk import verify_identity

APP = dict(sourceRevision='8d4bc572aa6b1984bd084e0d72d48b7c6b9f7e8e', sourceTree='a8822ba6523696f378659becf8f99121396d93f1', workflowRun='40063528837', runNumber=79, runAttempt=1)
SIGNER = '09f9e021ddac36ead015d27e073b673e79fdf4e02605c402a505781963b1a4d8'
PINS = {
    'runtime': (12548174624, '06529e7bd88088f3001e47b5a24ebf788e9cbc7cd0d918d96052bfd8fe359a8c', '929b464153b4902bb182f78d0a385376d81ca558c6943d1ed59ad0aa4671dd83', 'e75ed0da2994f737b7c4cc729686638e87dc4d95851dca61e7740ddc4216f395'),
    'arm': (12548960803, '90d17a36d316e315d963ee4e0bd8cb4ab9df06c873ce55938c58441d187f1681', '1b8dc13cc03eebc57849b2b861ba8ed878c5413bcdcdb8dc592c6a258dfbd258', 'a4928eb9cae92a01b683e73efaa957f49e800600a9e48e827b04e4fd27618049'),
}


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def download_original(kind, root):
    aid, zip_sha, info_sha, apk_sha = PINS[kind]
    metadata = release._gh_json(f'repos/{release.REPO}/actions/artifacts/{aid}')
    if metadata.get('digest') != 'sha256:' + zip_sha or metadata.get('expired') or metadata.get('workflow_run', {}).get('id') != int(APP['workflowRun']) or metadata['workflow_run'].get('head_sha') != APP['sourceRevision']:
        raise ValueError('Original artifact API identity changed')
    root.mkdir(exist_ok=False)
    archive = root.with_suffix('.zip')
    release._gh_binary(f'repos/{release.REPO}/actions/artifacts/{aid}/zip', archive)
    if sha(archive) != zip_sha:
        raise ValueError('Original ZIP digest mismatch')
    with zipfile.ZipFile(archive) as source:
        if source.testzip() is not None:
            raise ValueError('Original ZIP CRC mismatch')
        names = source.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate ZIP entries')
        for name in names:
            path = PurePosixPath(name)
            mode = source.getinfo(name).external_attr >> 16
            if path.is_absolute() or '..' in path.parts or '\\' in name or stat.S_ISLNK(mode):
                raise ValueError('Unsafe ZIP entry')
        source.extractall(root)
    if sha(root / 'BUILD_INFO.json') != info_sha:
        raise ValueError('Original BUILD_INFO changed')
    info = json.loads((root / 'BUILD_INFO.json').read_text())
    expected = dict(source=APP['sourceRevision'], tree=APP['sourceTree'], workflow='https://github.com/IvanSolis1989/Aerove/actions/runs/' + APP['workflowRun'], run_number=79, run_attempt=1, version='0.1.0-beta.9', version_code=9, application_id='dev.aerove.lite', signer_sha256=SIGNER, channel='preview', abi='x86_64' if kind == 'runtime' else 'arm64-v8a', apk_sha256=apk_sha)
    if any(info.get(key) != value for key, value in expected.items()):
        raise ValueError('Original package build identity mismatch')
    apk = root / ('apks/app-release.apk' if kind == 'runtime' else 'AEROVE-Lite-android-arm64.apk')
    if sha(apk) != apk_sha:
        raise ValueError('Original APK hash mismatch')
    if kind == 'runtime':
        release._validate_manifest(root, APP['sourceRevision'], APP['sourceTree'], APP['workflowRun'], 1, 79)
    else:
        release._only_files(root, ['AEROVE-Lite-android-arm64.apk', 'BUILD_INFO.json', 'SHA256SUMS'])
        release._checksums(root, ['AEROVE-Lite-android-arm64.apk', 'BUILD_INFO.json'])
    tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
    badging = subprocess.check_output([str(tools / 'aapt'), 'dump', 'badging', str(apk)], text=True)
    signature = subprocess.check_output([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
    identity = verify_identity(badging, signature, dict(applicationId='dev.aerove.lite', version='0.1.0-beta.9', versionCode='9', signerSha256=SIGNER), 'preview')
    (root / 'qa-badging.txt').write_text(badging)
    (root / 'qa-signature.txt').write_text(signature)
    return apk, dict(artifact=aid, zipSha256=zip_sha, buildInfoSha256=info_sha, apkSha256=apk_sha, package=identity)


def stop_owned(child):
    initial_code = child.poll()
    termination_initiated = False
    if initial_code is None:
        try:
            if os.name == 'posix':
                os.killpg(child.pid, signal.SIGTERM)
            else:
                child.terminate()
            termination_initiated = True
        except ProcessLookupError:
            pass
        try:
            child.wait(timeout=30)
        except subprocess.TimeoutExpired:
            try:
                if os.name == 'posix':
                    os.killpg(child.pid, signal.SIGKILL)
                else:
                    child.kill()
            except ProcessLookupError:
                pass
            child.wait()
    return dict(terminationInitiated=termination_initiated,
                exitObservedBeforeTermination=not termination_initiated and child.returncode is not None,
                initialReturnCode=initial_code, returnCode=child.returncode)


def finish_emulator(emulator, receipt, result):
    code = emulator.poll()
    if code is None:
        outcome = stop_owned(emulator)
    else:
        outcome = dict(terminationInitiated=False, exitObservedBeforeTermination=True,
                       initialReturnCode=code, returnCode=code)
    receipt['cleanup'] = outcome
    receipt['cleanupRequested'] = outcome['terminationInitiated']
    if not outcome['terminationInitiated']:
        receipt['unexpectedEmulatorExit'] = True
        result = 1
    code = outcome['returnCode']
    receipt.update(emulatorReturnCode=code, emulatorSignal=-code if code < 0 else None)
    return result


def monitor(emulator, acceptance, receipt):
    while acceptance.poll() is None:
        code = emulator.poll()
        if code is not None:
            receipt.update(unexpectedEmulatorExit=True, emulatorReturnCode=code, emulatorSignal=-code if code < 0 else None)
            stop_owned(acceptance)
            return 1
        try:
            acceptance.wait(timeout=0.25)
        except subprocess.TimeoutExpired:
            pass
    if emulator.poll() is not None:
        code = emulator.returncode
        receipt.update(unexpectedEmulatorExit=True, emulatorReturnCode=code, emulatorSignal=-code if code < 0 else None)
        return 1
    return acceptance.returncode


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('phase', choices=['matrix', 'remaining'])
    args = parser.parse_args()
    if sys.platform != 'linux':
        raise ValueError('The real acceptance owner requires the existing Linux/KVM runner')
    directory = Path(os.environ['RUNNER_TEMP']) / 'lite-diagnostics' / args.phase
    directory.mkdir(parents=True, exist_ok=True)
    inputs = Path(os.environ['RUNNER_TEMP']) / 'qa79-original-inputs'
    inputs.mkdir(exist_ok=False)
    apk, runtime = download_original('runtime', inputs / 'runtime')
    proof = dict(APP, apks={'app-release.apk': runtime['apkSha256'], 'app-release-androidTest.apk': '2f600dcdd3809328c3bd11cb37e706b3f6b0d6bc80c530d5aab3131971bf70ea'}, runtime=runtime, harnessRevision=os.environ['GITHUB_SHA'], harnessRun=os.environ['GITHUB_RUN_ID'], harnessFileSha256=sha(__file__), promotionAccepted=False)
    if args.phase == 'matrix':
        _, proof['arm'] = download_original('arm', inputs / 'arm')
    target = Path('apps/aerove-lite/app/build/outputs/apk')
    for source, destination in [(apk, target / 'release/app-release.apk'), (inputs / 'runtime/apks/app-release-androidTest.apk', target / 'androidTest/release/app-release-androidTest.apk')]:
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists():
            raise ValueError('Never overwrite a pre-existing APK')
        shutil.copyfile(source, destination)
    proof_path = directory / 'ORIGINAL-APP-PROOF.json'
    proof_path.write_text(json.dumps(proof, indent=2) + '\n')
    emulator_command = [str(Path(os.environ['ANDROID_HOME']) / 'emulator/emulator'), '-port', '5554', '-avd', 'test', '-no-window', '-gpu', 'swiftshader_indirect', '-no-snapshot', '-noaudio', '-no-boot-anim']
    receipt = dict(app=APP, harnessRevision=os.environ['GITHUB_SHA'], harnessRun=os.environ['GITHUB_RUN_ID'], command=emulator_command, ownerPid=os.getpid(), unexpectedEmulatorExit=False, cleanupRequested=False)
    environment = dict(os.environ, ANDROID_SERIAL='emulator-5554', AEROVE_QA79_PROOF=str(proof_path))
    def interrupt(signum, frame):
        raise InterruptedError(f'Owner interrupted by signal {signum}')
    signal.signal(signal.SIGTERM, interrupt)
    signal.signal(signal.SIGINT, interrupt)
    emulator = acceptance = None
    result = 1
    try:
        with (directory / 'emulator.stdout.log').open('wb') as out, (directory / 'emulator.stderr.log').open('wb') as err:
            emulator = subprocess.Popen(emulator_command, stdin=subprocess.DEVNULL, stdout=out, stderr=err, start_new_session=True)
            receipt['emulatorPid'] = emulator.pid
            deadline = time.monotonic() + 600
            while True:
                if emulator.poll() is not None:
                    raise RuntimeError('Owned emulator exited during boot')
                try:
                    boot = subprocess.run(['adb', '-s', 'emulator-5554', 'shell', 'getprop', 'sys.boot_completed'], capture_output=True, text=True, timeout=10)
                except subprocess.TimeoutExpired:
                    boot = subprocess.CompletedProcess([], 1, '')
                if boot.returncode == 0 and boot.stdout.strip() == '1':
                    break
                if time.monotonic() >= deadline:
                    raise TimeoutError('Original 600-second boot deadline reached')
                time.sleep(2)
            subprocess.run(['adb', '-s', 'emulator-5554', 'shell', 'input', 'keyevent', '82'], check=True, timeout=10)
            acceptance = subprocess.Popen(['bash', 'apps/aerove-lite/tool/emulator_acceptance.sh', args.phase], env=environment, start_new_session=True)
            result = monitor(emulator, acceptance, receipt)
    finally:
        if acceptance is not None:
            stop_owned(acceptance)
            receipt['acceptanceReturnCode'] = acceptance.returncode
        if emulator is not None:
            result = finish_emulator(emulator, receipt, result)
        receipt['result'] = result
        (directory / 'EMULATOR-OWNER.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return result


if __name__ == '__main__':
    raise SystemExit(main())
