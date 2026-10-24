#!/usr/bin/env python3
"""The original read-only preflight, with bounded commands and a failure receipt."""
import importlib.util
import argparse
from install_play33_r01 import validate_installed_image
import json
import os
from pathlib import Path
import sys

spec = importlib.util.spec_from_file_location('standard_probe', Path(__file__).with_name('probe-original-standard.py'))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('evidence', type=Path)
    parser.add_argument('--image-directory', required=True, type=Path)
    parser.add_argument('--image-receipt', required=True, type=Path)
    args = parser.parse_args()
    evidence = args.evidence
    receipt = {'status': 'STARTED', 'commands': [], 'readOnly': True}
    ready = False
    try:
        evidence.mkdir(parents=True, exist_ok=False)
        ready = True
        receipt['imageIdentity'] = validate_installed_image(args.image_directory, args.image_receipt)

        def command(label, argv):
            return probe.execute_command(receipt, evidence, label, argv, timeout=20)

        command('adb-devices', ['adb', 'devices', '-l'])
        if command('adb-state', ['adb', 'get-state']) != 'device':
            raise RuntimeError('ADB target is not in device state')
        receipt['properties'] = {}
        for prop in ['sys.boot_completed', 'ro.build.fingerprint', 'ro.build.version.sdk',
                     'ro.product.cpu.abi', 'ro.product.cpu.abilist', 'ro.product.cpu.abilist64',
                     'ro.system.product.cpu.abilist64', 'ro.dalvik.vm.native.bridge',
                     'ro.ndk_translation.version', 'ro.dalvik.vm.isa.arm64', 'ro.enable.native.bridge.exec']:
            receipt['properties'][prop] = command('prop-' + prop, ['adb', 'shell', 'getprop', prop])
        if receipt['properties']['ro.build.fingerprint'] != receipt['imageIdentity']['fingerprint']:
            raise ValueError('Booted image fingerprint differs from the fixed image sidecar')
        # Missing optional library paths are diagnostic data; retain the real exit.
        probe.execute_command(receipt, evidence, 'native-bridge-files',
                              ['adb', 'shell', 'ls -l /system/lib64/libndk_translation.so /system/lib64/libnb.so /system/lib64/arm64'],
                              timeout=20, check=False)
        sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
        if sdk:
            image = args.image_directory
            for name in ['source.properties', 'build.prop', 'package.xml']:
                if (image / name).is_file():
                    probe.evidence_write(receipt, evidence / name, (image / name).read_bytes())
            command('emulator-version', [Path(sdk) / 'emulator/emulator', '-no-window', '-version'])
        receipt['status'] = 'READ-ONLY CHECK COMPLETE; NO EXECUTION PROOF'
        if receipt.get('evidenceErrors'):
            receipt['status'] = 'FAILED_EVIDENCE'
    except BaseException as error:
        receipt['status'] = 'FAILED' if isinstance(error, Exception) else 'CANCELLED'
        receipt['error'] = repr(error)
        if not isinstance(error, Exception):
            raise
    finally:
        if ready:
            probe.persist_receipt(receipt, evidence)
        else:
            probe.emit_stderr('RECEIPT_FALLBACK_JSON=' + json.dumps(receipt))
    if receipt['status'] != 'READ-ONLY CHECK COMPLETE; NO EXECUTION PROOF':
        raise SystemExit(1)


if __name__ == '__main__':
    probe.install_cancel_handler()
    main()
