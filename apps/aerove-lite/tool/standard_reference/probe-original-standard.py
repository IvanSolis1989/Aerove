#!/usr/bin/env python3
"""Candidate CI probe for the unchanged Std #40 APK; never builds an app."""
import argparse
from install_play33_r01 import validate_installed_image
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import signal
import sys
import time
import xml.etree.ElementTree as ET

EXPECTED = {
    'Aerove-android-arm64.apk': '64c42740e9164b29cca0289ae229a1dbe4cf44899c475f867e0f62ad033cfca8',
    'BUILD_INFO.json': '4b5e2547d2639346c54f8bf3c024b9a0e5c93052cabffcb823289656b6075f80',
    'SHA256SUMS': '11dce7250b3fd94926613a50278bf1879baf951c427cfa7c267fe6e2350994c7',
}
SOURCE = 'c133772fc83b7984bdacb11e5dd41e2d65098771'
SIGNER = '0fc82c3b7b089b6244d9ed167b352ff76185417d07689853caca286fbdeb6e60'
PACKAGE = 'com.follow.clash.dev'
RUN = 'https://github.com/IvanSolis1989/Aerove/actions/runs/40082294388'



def emit_stderr(value):
    try:
        print(value, file=sys.stderr, flush=True)
    except OSError:
        pass


def evidence_write(receipt, path, data):
    try:
        path.write_bytes(data)
        return True
    except Exception as error:
        receipt.setdefault('evidenceErrors', []).append({'path': str(path), 'error': repr(error)})
        emit_stderr(f'EVIDENCE WRITE FAILED {path}: {error!r}')
        return False


def execute_command(receipt, evidence, label, argv, timeout=20, check=True, binary=False):
    record = {'label': label, 'argv': [str(x) for x in argv], 'limitSeconds': timeout}
    receipt['commands'].append(record)
    started = time.monotonic()
    try:
        result = subprocess.run(record['argv'], capture_output=True, timeout=timeout)
    except BaseException as error:
        record.update(exit='TIMEOUT' if isinstance(error, subprocess.TimeoutExpired) else 'EXCEPTION',
                      error=repr(error), seconds=round(time.monotonic() - started, 3))
        stderr = getattr(error, 'stderr', None) or b''
        stdout = getattr(error, 'stdout', None) or b''
        record['stderrPreview'] = stderr[:8192].decode(errors='replace')
        emit_stderr(json.dumps(record))
        evidence_write(receipt, evidence / (label + '.timeout-stdout.txt'), stdout)
        evidence_write(receipt, evidence / (label + '.stderr.txt'), stderr)
        raise
    record.update(exit=result.returncode, seconds=round(time.monotonic() - started, 3),
                  stderrPreview=result.stderr[:8192].decode(errors='replace'))
    if result.returncode:
        emit_stderr(json.dumps(record))
    evidence_write(receipt, evidence / (label + '.stderr.txt'), result.stderr)
    evidence_write(receipt, evidence / (label + ('.png' if binary else '.stdout.txt')), result.stdout)
    if check and result.returncode:
        raise RuntimeError(f'{label} exited {result.returncode}: {record["stderrPreview"]}')
    return result.stdout if binary else result.stdout.decode(errors='replace').strip()


def persist_receipt(receipt, evidence):
    try:
        (evidence / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
        return True
    except Exception as error:
        receipt.setdefault('evidenceErrors', []).append({'path': str(evidence / 'receipt.json'),
                                                        'error': repr(error)})
        receipt.setdefault('mainStatus', receipt['status'])
        if receipt['status'] != 'CANCELLED':
            receipt['status'] = 'FAILED_EVIDENCE'
        emit_stderr('RECEIPT_FALLBACK_JSON=' + json.dumps(receipt))
        return False


def finalize_probe(receipt, evidence, adb, installed):
    installed = installed or any(item.get('label') == 'install-arm64-original' and item.get('exit') == 0
                                 for item in receipt['commands'])
    receipt['mainStatus'] = receipt['status']
    receipt['cleanup'] = {'required': installed, 'status': 'PENDING' if installed else 'NOT_REQUIRED'}
    cancellation = None
    actions = [('diagnostics', 'logcat', adb + ['logcat', '-d', '-v', 'threadtime'], 25)]
    if installed:
        actions.append(('cleanup', 'stop-task-installed-app', adb + ['shell', 'am', 'force-stop', PACKAGE], 20))
    for field, label, argv, limit in actions:
        try:
            execute_command(receipt, evidence, label, argv, timeout=limit)
            receipt[field] = {'status': 'SUCCESS', **({'required': True} if field == 'cleanup' else {})}
        except BaseException as error:
            receipt[field] = {'status': 'FAILED', 'error': repr(error),
                              **({'required': True} if field == 'cleanup' else {})}
            if not isinstance(error, Exception) and cancellation is None:
                cancellation = error
    if cancellation is not None:
        receipt['status'] = 'CANCELLED'
    elif (receipt.get('evidenceErrors') or receipt['diagnostics']['status'] != 'SUCCESS'
          or receipt['cleanup']['status'] == 'FAILED'):
        if receipt['status'] != 'CANCELLED':
            receipt['status'] = 'FAILED_FINALIZATION'
    persist_receipt(receipt, evidence)
    if cancellation is not None:
        raise cancellation


def validate_initial_app_ui(ui_tree, activity_state):
    root = ET.fromstring(ui_tree)
    if not any(node.get('package') == PACKAGE for node in root.iter('node')):
        raise RuntimeError('Captured UI tree does not belong to the original app')
    resumed = re.compile(r'^\s*(?:topResumedActivity|mResumedActivity)\s*[=:].*?(?<!\S)'
                         + re.escape(PACKAGE + '/com.follow.clash.MainActivity')
                         + r'(?=[\s}])', re.MULTILINE)
    if not resumed.search(activity_state):
        raise RuntimeError('Original MainActivity is not the resumed activity at capture')


def install_cancel_handler():
    def terminate(signum, frame):
        raise SystemExit(128 + signum)
    signal.signal(signal.SIGTERM, terminate)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk-directory', required=True, type=Path)
    parser.add_argument('--evidence', required=True, type=Path)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--build-tools', required=True, type=Path)
    parser.add_argument('--image-directory', required=True, type=Path)
    parser.add_argument('--image-receipt', required=True, type=Path)
    args = parser.parse_args()
    if os.environ.get('GITHUB_ACTIONS') != 'true' or not args.serial.startswith('emulator-'):
        parser.error('This candidate is restricted to an isolated GitHub Actions emulator')
    args.evidence.mkdir(parents=True, exist_ok=False)
    receipt = {'status': 'STARTED', 'originalSource': SOURCE, 'originalRun': RUN,
               'artifactId': 12555818245, 'artifactName': 'Aerove-android-arm64',
               'harnessSource': os.environ.get('GITHUB_SHA'),
               'harnessRun': os.environ.get('GITHUB_RUN_ID'), 'serial': args.serial,
               'renderKind': 'Android emulator; x86_64 host with ARM64 native translation',
               'commands': [], 'vpnStartedByProbe': False, 'demoInjected': False,
               'twelveThemeCaptures': False, 'apkModified': False, 'appBuilt': False}
    adb = ['adb', '-s', args.serial]
    installed = False

    def command(label, argv, timeout=20, check=True, binary=False):
        return execute_command(receipt, args.evidence, label, argv, timeout, check, binary)

    try:
        receipt['imageIdentity'] = validate_installed_image(args.image_directory, args.image_receipt)
        properties = {}
        for prop in ['sys.boot_completed', 'ro.build.fingerprint', 'ro.system.build.fingerprint', 'ro.build.version.sdk',
                     'ro.product.cpu.abilist', 'ro.product.cpu.abilist64',
                     'ro.dalvik.vm.native.bridge', 'ro.ndk_translation.version',
                     'ro.dalvik.vm.isa.arm64']:
            properties[prop] = command('prop-' + prop, adb + ['shell', 'getprop', prop])
        receipt['properties'] = properties
        if properties['ro.build.fingerprint'] != receipt['imageIdentity']['runtimeFingerprint']:
            raise ValueError('Booted runtime fingerprint differs from the fixed image sidecar')
        if properties['sys.boot_completed'] != '1' or properties['ro.build.version.sdk'] != '33':
            raise RuntimeError('Expected already booted API33 emulator')
        if 'arm64-v8a' not in properties['ro.product.cpu.abilist64'].split(','):
            raise RuntimeError('Effective 64-bit ABI list does not advertise ARM64')
        if properties['ro.dalvik.vm.native.bridge'] != 'libndk_translation.so':
            raise RuntimeError('Expected official libndk_translation bridge is not configured')
        files = command('bridge-files', adb + ['shell', 'find', '/system/lib64',
                        '/vendor/lib64', '-maxdepth', '2', '-name', '*ndk_translation*'], check=False)
        if 'libndk_translation.so' not in files:
            raise RuntimeError('Bridge binary not found in checked official library directories')
        sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
        image = args.image_directory
        for name in ['source.properties', 'build.prop']:
            (args.evidence / ('image-' + name)).write_bytes((image / name).read_bytes())
        command('emulator-version', [sdk / 'emulator/emulator', '-no-window', '-version'])
        receipt['assets'] = {}
        for name, expected in EXPECTED.items():
            path = args.apk_directory / name
            actual = hashlib.sha256(path.read_bytes()).hexdigest()
            receipt['assets'][name] = {'sha256': actual, 'bytes': path.stat().st_size}
            if actual != expected:
                raise RuntimeError(f'Original accepted asset mismatch: {name}')
        info = json.loads((args.apk_directory / 'BUILD_INFO.json').read_text())
        if (info.get('source') != SOURCE or info.get('version') != '0.11.0-beta.3+2026102240'
                or info.get('workflow') != RUN or info.get('applicationId') != PACKAGE):
            raise RuntimeError('Original BUILD_INFO source/version/workflow/package mismatch')
        receipt['originalBuildInfo'] = info
        apk = args.apk_directory / 'Aerove-android-arm64.apk'
        signature = command('apksigner', [args.build_tools / 'apksigner', 'verify',
                            '--verbose', '--print-certs', apk])
        if f'Signer #1 certificate SHA-256 digest: {SIGNER}' not in signature:
            raise RuntimeError('Original signing certificate mismatch')
        badging = command('aapt', [args.build_tools / 'aapt', 'dump', 'badging', apk])
        if not re.search(r"package: name='com.follow.clash.dev' versionCode='2026102240' versionName='0.11.0-beta.3'", badging):
            raise RuntimeError('Package version badging mismatch')
        existing = command('existing-package', adb + ['shell', 'pm', 'path', PACKAGE], check=False)
        if 'package:' in existing:
            raise RuntimeError('Refusing to replace existing dev installation/data')
        command('install-arm64-original', adb + ['install', '--abi', 'arm64-v8a', apk], timeout=90)
        installed = True
        package = command('installed-package', adb + ['shell', 'dumpsys', 'package', PACKAGE])
        if 'primaryCpuAbi=arm64-v8a' not in package:
            raise RuntimeError('Installed process ABI is not ARM64')
        launch = command('launch', adb + ['shell', 'am', 'start', '-W', '-n',
                         PACKAGE + '/com.follow.clash.MainActivity'], timeout=35)
        if 'Status: ok' not in launch:
            raise RuntimeError('Activity launch did not report success')
        time.sleep(12)
        pid = command('process', adb + ['shell', 'pidof', PACKAGE], check=False)
        receipt['pidAfter12Seconds'] = pid
        screen = command('initial-screen', adb + ['exec-out', 'screencap', '-p'], binary=True)
        if not screen.startswith(b'\x89PNG\r\n\x1a\n'):
            raise RuntimeError('Screenshot is not a PNG')
        command('initial-ui-dump', adb + ['shell', 'uiautomator', 'dump',
                                        '/sdcard/standard-probe-ui.xml'])
        ui_tree = command('initial-ui-tree', adb + ['exec-out', 'cat', '/sdcard/standard-probe-ui.xml'])
        activity_state = command('activity-state', adb + ['shell', 'dumpsys', 'activity', 'activities'])
        command('renderer-state', adb + ['shell', 'dumpsys', 'SurfaceFlinger'], check=False)
        if not pid:
            raise RuntimeError('Application no longer running after initial launch')
        validate_initial_app_ui(ui_tree, activity_state)
        receipt['status'] = 'CAPTURED INITIAL ANDROID SCREEN; HUMAN/LOG REVIEW REQUIRED'
    except BaseException as error:
        receipt['status'] = 'FAILED' if isinstance(error, Exception) else 'CANCELLED'
        receipt['error'] = repr(error)
        if not isinstance(error, Exception):
            raise
    finally:
        finalize_probe(receipt, args.evidence, adb, installed)
    if receipt['status'] != 'CAPTURED INITIAL ANDROID SCREEN; HUMAN/LOG REVIEW REQUIRED':
        raise SystemExit(1)



if __name__ == '__main__':
    install_cancel_handler()
    main()
