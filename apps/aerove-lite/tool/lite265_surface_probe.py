"""One fixed original265 API33 Surface/Window/compositor observation; never app acceptance."""
import argparse
import json
import os
from pathlib import Path
import re
import signal
import subprocess
from uuid import uuid4

import ci_artifacts
from flutter_android_qa import Producer, PACKAGE, validate_binding, renderer, sha, save, idle
from instrumentation_shards import parse_log
from lite265_recheck import enabled, IDENTITY, APKS
from verify_apk import signing_certificate

HARNESS_PACKAGE = 'dev.aerove.lite.test'
SIGNER = '09f9e021ddac36ead015d27e073b673e79fdf4e02605c402a505781963b1a4d8'
SELECTOR = 'dev.aerove.lite.SurfacePixelDiagnosticTest#testOriginal265SurfaceWindowAndStartupMetadata'
ORIGINAL_SOURCE = Path('.ci-original-lite265-source')


def require(value, message):
    if not value:
        raise ValueError(message)


def original_inputs(directory, runtime):
    require(enabled() and os.environ.get('AEROVE_LITE265_SURFACE_PROBE') == '1', 'Fixed265 probe opt-in required')
    source = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ORIGINAL_SOURCE, text=True).strip()
    tree = subprocess.check_output(['git', 'rev-parse', 'HEAD^{tree}'], cwd=ORIGINAL_SOURCE, text=True).strip()
    dirty = subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=no'], cwd=ORIGINAL_SOURCE, text=True)
    require(source == IDENTITY['sourceRevision'] and tree == IDENTITY['sourceTree'] and not dirty,
            'Fixed original265 source checkpoint differs')
    directory = Path(directory)
    result = dict(apk=Path('apps/aerove-lite/app/build/outputs/apk/release/app-release.apk').resolve(),
                  test_apk=Path('apps/aerove-lite/app/build/outputs/apk/androidTest/release/app-release-androidTest.apk').resolve(),
                  build_info=(directory / 'BUILD_INFO.json').resolve())
    info = json.loads(result['build_info'].read_text())
    require(validate_binding(result['apk'], result['test_apk'], info, dict(runtime, owner='emulator_current.py')) == IDENTITY,
            'Diagnostic original265 source/APK binding differs')
    return result


def harness_binding(path, info):
    require(info.get('sourceRevision') == os.environ['GITHUB_SHA'] and
            info.get('workflowRun') == os.environ['GITHUB_RUN_ID'] and
            info.get('runAttempt') == int(os.environ['GITHUB_RUN_ATTEMPT']) and
            info.get('sourceTree') == ci_artifacts.identity()['sourceTree'] and
            info.get('apkSha256') == sha(path) and info.get('signerSha256') == SIGNER and
            info.get('appCompiled') is False and info.get('harnessCompiled') is True and
            info.get('diagnosticOnly') is True and
            info.get('sourceSha256') == sha(Path(__file__).resolve().parents[1] /
                'android-qa/src/main/java/dev/aerove/lite/SurfacePixelDiagnosticTest.java'),
            'Surface harness source/build/signature binding differs')


class Probe(Producer):
    def run(self):
        require(enabled() and os.environ.get('AEROVE_LITE265_SURFACE_PROBE') == '1', 'Fixed265 probe opt-in required')
        self.receipt.update(producer='lite265_surface_probe.py', diagnosticOnly=True, accepted=False,
                            appCompiled=False, harnessCompiled=True, originalProducer=IDENTITY,
                            harnessIdentity=ci_artifacts.identity())
        result = 1
        remote = None
        for name in (signal.SIGINT, signal.SIGTERM):
            self.previous_signals[name] = signal.signal(name, self.interrupt)
        try:
            from emulator_current import validate_renderer_proof
            proof = validate_renderer_proof(Path(os.environ['AEROVE_CURRENT_RENDERER_PROOF']))
            info = json.loads(self.args.build_info.read_text())
            require(validate_binding(self.args.apk, self.args.test_apk, info, proof) == IDENTITY and
                    sha(self.args.apk) == APKS['app-release.apk'] and
                    proof['deviceSerial'] == self.args.serial == os.environ['ANDROID_SERIAL'],
                    'Fixed original265 app/owner binding differs')
            harness = json.loads(self.args.harness_info.read_text())
            harness_binding(self.args.harness, harness)
            self.receipt['harnessBuild'] = harness
            for package in (PACKAGE, HARNESS_PACKAGE):
                require(b'package:' not in self.adb_command('preexisting-' + package, 'shell', 'pm', 'path', package, check=False),
                        'Fresh owned emulator required; never replace an existing installation')
            self.owner_verified = True
            for package, path in ((PACKAGE, self.args.apk), (HARNESS_PACKAGE, self.args.harness)):
                signature = self.command('signature-' + package,
                    [self.args.apksigner, 'verify', '--verbose', '--print-certs', path]).decode()
                require(signing_certificate(signature) == SIGNER, 'App or independent harness signer differs')
                self.installed.append(package)
                self.adb_command('install-' + package, 'install', *(['-t'] if package == HARNESS_PACKAGE else []),
                                 '--no-streaming', str(path), timeout=120)
                self.installed_hash('installed-' + package, package, sha(path))
            token = 'surface-' + uuid4().hex
            remote = '/sdcard/Android/data/' + PACKAGE + '/files/surface-pixel-diagnostic/' + token
            values = dict(qaToken=token, originalAppApkSha256=APKS['app-release.apk'],
                          originalAppSignerSha256=SIGNER, originalAppVersionCode='36',
                          harnessApkSha256=sha(self.args.harness), harnessSignerSha256=SIGNER,
                          harnessSourceSha=os.environ['GITHUB_SHA'], harnessBuildRun=os.environ['GITHUB_RUN_ID'],
                          harnessBuildAttempt=os.environ['GITHUB_RUN_ATTEMPT'], executorSourceSha=os.environ['GITHUB_SHA'],
                          executionRun=os.environ['GITHUB_RUN_ID'], executionAttempt=os.environ['GITHUB_RUN_ATTEMPT'])
            command = ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', SELECTOR]
            for key, value in values.items():
                command += ['-e', key, value]
            command += [HARNESS_PACKAGE + '/android.test.InstrumentationTestRunner']
            self.adb_command('surface-instrument', *command, timeout=170)
            self.adb_command('surface-pull', 'pull', remote, str(self.args.output / 'capture'), timeout=45)
            capture = json.loads((self.args.output / 'capture/receipt.json').read_text())
            records = parse_log(self.args.output / 'surface-instrument.out', expected_reporter='InstrumentationTestRunner')
            require(len(records) == 1 and records[0]['className'] + '#' + records[0]['testName'] == SELECTOR and
                    records[0]['status'] == 'passed' and capture.get('status') == 'captured' and
                    capture.get('captureCompleted') is True and capture.get('resourceCleanup') == 'complete',
                    'Surface observation or cleanup incomplete')
            require(capture['originalApp']['apkSha256'] == APKS['app-release.apk'] and
                    capture.get('diagnosticOnly') is True and capture.get('accepted') is False,
                    'Surface observation identity changed')
            for field, name in (('surfaceCopy', 'surface.png'), ('windowCopy', 'window.png'), ('compositorCopy', 'compositor.png')):
                require(capture[field]['image']['sha256'] == sha(self.args.output / 'capture' / name), 'Captured PNG changed')
            log = self.adb_command('app-pid-renderer', 'logcat', '-d', '-v', 'threadtime', timeout=45).decode(errors='replace')
            self.receipt['appRenderer'] = renderer(log, capture['pid'])
            require(any('Using the Impeller rendering backend (OpenGLES)' in line
                        for line in self.receipt['appRenderer']['lines']), 'Original OpenGLES Impeller was not observed')
            self.check_process_log(log, capture['pid'])
            self.receipt.update(status='observed-pending-pixel-review', captureSha256=sha(self.args.output / 'capture/receipt.json'))
            result = 0
        except BaseException as failure:
            self.receipt.update(status='failed', failureClass=type(failure).__name__)
            raise
        finally:
            if self.owner_verified:
                if remote and not (self.args.output / 'capture/receipt.json').exists():
                    try:
                        self.adb_command('failure-capture-pull', 'pull', remote, str(self.args.output / 'capture'),
                                         timeout=45, check=False, cancelling=True)
                    except Exception as failure:
                        self.receipt['pullFailureClass'] = type(failure).__name__
                try:
                    self.adb_command('final-logcat', 'logcat', '-d', '-v', 'threadtime', timeout=45, check=False, cancelling=True)
                    self.adb_command('final-window', 'shell', 'dumpsys', '-t', '10', 'window', 'windows', timeout=15,
                                     check=False, cancelling=True)
                except Exception as failure:
                    self.receipt['diagnosticFailureClass'] = type(failure).__name__
            for package in reversed(self.installed):
                try:
                    self.adb_command('cleanup-stop-' + package, 'shell', 'am', 'force-stop', package, check=False, cancelling=True)
                    self.adb_command('cleanup-uninstall-' + package, 'uninstall', package, timeout=45, cancelling=True)
                except Exception as failure:
                    self.receipt.setdefault('cleanupErrors', []).append(type(failure).__name__)
            self.receipt['result'] = 1 if self.cancelled or self.receipt.get('cleanupErrors') else result
            self.receipt['files'] = [dict(path=p.relative_to(self.args.output).as_posix(), bytes=p.stat().st_size, sha256=sha(p))
                                     for p in self.args.output.rglob('*') if p.is_file() and p.name != 'PROBE.json']
            save(self.args.output / 'PROBE.json', self.receipt)
            for name, handler in self.previous_signals.items():
                signal.signal(name, handler)
        return self.receipt['result']


def main():
    parser = argparse.ArgumentParser()
    for name in ('apk', 'test-apk', 'build-info', 'adb', 'apksigner', 'harness', 'harness-info', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--serial', required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    idle()
    return Probe(args).run()


if __name__ == '__main__':
    raise SystemExit(main())
