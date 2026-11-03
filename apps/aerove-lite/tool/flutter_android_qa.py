"""Black-box Flutter release UI producer. Uses an existing owned emulator; never builds an app.

The emulator_current owner remains responsible for emulator lifetime and host renderer proof.
Only this explicit suite is selected; legacy Compose suites retain their separate scope.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
import zipfile

from verify_apk import signing_certificate

PACKAGE = 'dev.aerove.lite'
LAUNCHER = PACKAGE + '.FlutterMainActivity'
TEST_PACKAGE = PACKAGE + '.test'
RUNNER = TEST_PACKAGE + '/androidx.test.runner.AndroidJUnitRunner'
CLASS = PACKAGE + '.FlutterProductionUiTest'
PNG = b'\x89PNG\r\n\x1a\n'
MATRIX = {(m, b, l) for m in ('Plain solid', 'Soft frost', 'Clear shimmer')
          for b in ('Classic monochrome', 'Living Aurora') for l in ('Light', 'Dark')}


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def save(path, value):
    Path(path).write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def idle():
    if sys.platform == 'win32':
        import ctypes
        if not ctypes.windll.kernel32.SetPriorityClass(ctypes.windll.kernel32.GetCurrentProcess(), 0x40):
            raise OSError('Cannot set QA process to Idle')
    else:
        os.nice(19)


def validate_binding(apk, test_apk, info, proof):
    expected = dict(sourceRevision=info['source'], sourceTree=info['tree'],
                    workflowRun=info['workflow'].rsplit('/', 1)[-1],
                    runAttempt=info['run_attempt'], runNumber=info['run_number'])
    if (info.get('product') != 'AEROVE Lite' or info.get('application_id') != PACKAGE
            or info.get('launcher_activity') != LAUNCHER or info.get('abi') != 'x86_64'
            or info.get('apk_sha256') != sha(apk)
            or any(not re.fullmatch('[0-9a-f]{40}', str(info.get(k, ''))) for k in ('source', 'tree'))
            or info.get('flutter_packaging', {}).get('client_kind') != 'flutter-aot'):
        raise ValueError('Requires sealed inspected x86 Flutter production BUILD_INFO and exact APK bytes')
    if proof.get('owner') != 'emulator_current.py' or proof.get('identity') != expected:
        raise ValueError('Emulator owner and production source/run/attempt differ')
    if proof.get('apks') != {'app-release.apk': sha(apk), 'app-release-androidTest.apk': sha(test_apk)}:
        raise ValueError('Owner must seal both exact app and independently built test APK hashes')
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate APK paths')
        for name in ('libapp.so', 'libflutter.so', 'libaerove_meow_core.so'):
            path = f'lib/x86_64/{name}'
            if path not in names or not archive.read(path).startswith(b'\x7fELF'):
                raise ValueError('Production APK lacks Flutter/Meow ELF: ' + path)
        if any(Path(name).name in ('libclash.so', 'libmihomo.so', 'libaerove_core.so') for name in names):
            raise ValueError('Retired or Standard core packaged in Flutter Lite')
    return expected


def renderer(log, pid):
    # Same PID-scoped evidence rule as tooling/standard_x86_qa.py:app_renderer.
    lines = [line for line in log.splitlines() if re.match(r'\S+\s+\S+\s+' + re.escape(str(pid)) + r'\s', line)
             and ('Impeller' in line or 'rendering backend' in line)]
    if not any('Using the Impeller rendering backend' in line for line in lines):
        raise ValueError('Exact app PID did not report production Impeller; no Skia fallback acceptance')
    if any('Skia' in line for line in lines):
        raise ValueError('Conflicting app renderer evidence')
    return dict(backend='impeller', pid=str(pid), lines=lines)


def validate_capture(directory, info, token, matrix=True):
    directory = Path(directory)
    receipt = json.loads((directory / 'receipt.json').read_text(encoding='utf-8'))
    fields = dict(qaToken=token, apkSha256=info['apk_sha256'], source=info['source'], tree=info['tree'],
                  workflow=info['workflow'], runAttempt=str(info['run_attempt']))
    if (receipt.get('schema') != 1 or receipt.get('producer') != CLASS
            or receipt.get('scenarioPassed') is not True or receipt.get('status') != 'captured'
            or receipt.get('launcher') != PACKAGE + '/' + LAUNCHER
            or any(receipt.get(k) != v for k, v in fields.items())):
        raise ValueError('Android scenario failed or lost original APK/source binding')
    combinations = []
    for frame in receipt['frames']:
        for file_key, digest_key in (('file', 'sha256'), ('hierarchy', 'hierarchySha256'),
                                     ('windowInsetsDump', 'windowInsetsDumpSha256')):
            name = frame[file_key]
            if Path(name).name != name or '\\' in name or (directory / name).is_symlink():
                raise ValueError('Invalid capture evidence path')
            if sha(directory / name) != frame[digest_key]:
                raise ValueError('Raw capture/hierarchy changed after Android producer')
        image = (directory / frame['file']).read_bytes()
        if image[:8] != PNG or len(image) < 33 or image[12:16] != b'IHDR':
            raise ValueError('Screenshot is not original PNG output')
        width, height = int.from_bytes(image[16:20], 'big'), int.from_bytes(image[20:24], 'big')
        if min(width, height) < 100 or frame.get('bytes') != len(image) or frame.get('density', 0) <= 0:
            raise ValueError('Missing actual screenshot geometry/density')
        hierarchy = json.loads((directory / frame['hierarchy']).read_text(encoding='utf-8'))
        if not any(n.get('package') == PACKAGE and n.get('visible') and len(n.get('boundsPx', [])) == 4 for n in hierarchy):
            raise ValueError('Capture has no actual production accessibility bounds')
        if not frame.get('windows'):
            raise ValueError('No actual Android window bounds')
        configuration = frame.get('configuration') or {}
        if configuration.get('matrix') is True:
            combinations.append(tuple(configuration.get(k) for k in ('material', 'backdrop', 'brightness')))
            if configuration.get('settingsEvidence') not in {f['file'] for f in receipt['frames']}:
                raise ValueError('Matrix lacks real selected-theme evidence')
    if matrix and (len(combinations) != 12 or set(combinations) != MATRIX or receipt.get('statementCopied') is not True):
        raise ValueError('Incomplete consent/theme12 evidence; no case may be dropped')
    if not matrix and (combinations or receipt.get('stage') != 'restart-consent-observed'):
        raise ValueError('Restart evidence did not observe persisted production consent')
    return receipt


class Producer:
    def __init__(self, args):
        self.args = args
        self.adb = [str(args.adb), '-s', args.serial]
        self.receipt = dict(schema=1, producer='flutter_android_qa.py', status='running', stage='preflight',
                            commands=[], visualApproved=False, performanceAccepted=False, releaseAccepted=False)
        self.cancelled = False
        self.installed = []
        self.child = None
        self.previous_signals = {}
        self.owner_verified = False

    def interrupt(self, signum, _frame):
        if self.cancelled:
            return
        self.cancelled = True
        raise InterruptedError(f'Cancelled by signal {signum} at {self.receipt["stage"]}')

    def command(self, label, command, timeout=30, check=True, cancelling=False, termination_timeout=5):
        if self.cancelled and not cancelling:
            raise InterruptedError('QA cancellation observed')
        row = dict(label=label, command=[str(c) for c in command], timeoutSeconds=timeout)
        self.receipt['commands'].append(row)
        out, err = self.args.output / (label + '.out'), self.args.output / (label + '.err')
        start = time.monotonic()
        with out.open('wb') as stdout, err.open('wb') as stderr:
            child = subprocess.Popen(row['command'], stdin=subprocess.DEVNULL, stdout=stdout, stderr=stderr)
            self.child = child
            try:
                row['exit'] = child.wait(timeout=timeout)
            except BaseException:
                child.terminate()
                try:
                    child.wait(timeout=termination_timeout)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=5)
                row['interruptedOrTimedOut'] = True
                raise
            finally:
                row['elapsedSeconds'] = round(time.monotonic() - start, 3)
                self.child = None
        if check and row['exit'] != 0:
            raise RuntimeError(f'{label} exited {row["exit"]}; see {err}')
        return out.read_bytes()

    def adb_command(self, label, *command, **options):
        return self.command(label, self.adb + list(command), **options)

    def normal_instrument(self, label, *arguments):
        """Observe normal invocations only; the host-terminated boundary owns its direct child."""
        if os.environ.get('AEROVE_CLEAR_FRAME_TRACE') != '1':
            return self.adb_command(label, *arguments, timeout=600)
        trace_directory = self.args.output / (label + '-frame-trace-' + str(len(self.receipt['commands'])))
        command = [sys.executable, str(Path(__file__).with_name('clear_frame_trace.py')),
                   '--serial', self.args.serial, '--output', str(trace_directory),
                   '--command-timeout', '600']
        if os.environ.get('AEROVE_REQUIRE_FRAME_TRACE') == '1':
            command.append('--require-trace')
        command += ['--', *self.adb, *arguments]
        # Collector start/finish are bounded separately from the unchanged 600s instrument.
        return self.command(label, command, timeout=780, termination_timeout=60)

    def installed_hash(self, label, package, expected):
        output = self.adb_command(label + '-path', 'shell', 'pm', 'path', package).decode().strip()
        paths = output.splitlines()
        if len(paths) != 1 or not re.fullmatch(r'package:/data/app/[A-Za-z0-9_./+=~-]+/base.apk', paths[0]):
            raise ValueError('Requires one installed base APK without splits')
        target = paths[0][len('package:'):]
        output = self.adb_command(label + '-installed-apk', 'shell', 'sha256sum', target, timeout=60).decode().strip()
        digest = re.fullmatch(r'([0-9a-f]{64})[ \t]+' + re.escape(target), output)
        if digest is None:
            raise ValueError('Requires one installed APK digest bound to its exact base path')
        if digest[1] != expected:
            raise ValueError('Installed package bytes differ from sealed original: ' + package)
        return dict(path=target, sha256=expected)

    def instrument(self, method, token, info):
        remote = f'/sdcard/Android/data/{PACKAGE}/files/flutter-production-qa/{token}'
        args = ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', CLASS + '#' + method,
                '-e', 'qaToken', token, '-e', 'apkSha256', info['apk_sha256'], '-e', 'source', info['source'],
                '-e', 'tree', info['tree'], '-e', 'workflow', info['workflow'],
                '-e', 'runAttempt', str(info['run_attempt']), RUNNER]
        failure = None
        try:
            output = self.normal_instrument(method, *args).decode(errors='replace')
            if not re.search(r'OK \(1 test\)', output) or any(x in output for x in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'shortMsg=')):
                raise RuntimeError('Instrumentation did not finish exactly one successful test')
        except BaseException as error:
            failure = error
        finally:
            self.adb_command(method + '-pull', 'pull', remote, str(self.args.output / method),
                             timeout=90, check=False, cancelling=True)
        if failure:
            raise failure
        return validate_capture(self.args.output / method, info, token, matrix=method == 'consentNavigationTheme12')

    def run(self):
        result = 1
        for name in (signal.SIGINT, signal.SIGTERM):
            self.previous_signals[name] = signal.signal(name, self.interrupt)
        try:
            # Importing the adjacent existing owner reuses its identity, manifest and host-renderer validation.
            from emulator_current import validate_renderer_proof
            proof_path = Path(os.environ['AEROVE_CURRENT_RENDERER_PROOF'])
            proof = validate_renderer_proof(proof_path)
            if (self.args.serial != proof['deviceSerial'] or not self.args.serial.startswith('emulator-')
                    or self.args.serial != os.environ.get('ANDROID_SERIAL')):
                raise ValueError('Only the explicitly owned isolated emulator is allowed')
            info = json.loads(self.args.build_info.read_text(encoding='utf-8'))
            self.receipt.update(identity=validate_binding(self.args.apk, self.args.test_apk, info, proof),
                                buildInfoSha256=sha(self.args.build_info), apkSha256=sha(self.args.apk),
                                testApkSha256=sha(self.args.test_apk), ownerProofSha256=sha(proof_path),
                                ownerProof=proof, deviceSerial=self.args.serial)
            self.owner_verified = True
            self.adb_command('device-properties', 'shell', 'getprop')
            for package in (PACKAGE, TEST_PACKAGE):
                installed_path = self.adb_command('preexisting-' + package, 'shell', 'pm', 'path', package, check=False)
                if b'package:' in installed_path:
                    raise ValueError('Dedicated fresh emulator required; refuses to clear/replace existing user app')
            for label, path in (('app', self.args.apk), ('test', self.args.test_apk)):
                signature = self.command(label + '-signature', [self.args.apksigner, 'verify', '--verbose', '--print-certs', path]).decode()
                if signing_certificate(signature) != info['signer_sha256']:
                    raise ValueError('App and runner must share the sealed production signer')
            self.receipt['stage'] = 'install-exact-originals'
            for package, path in ((PACKAGE, self.args.apk), (TEST_PACKAGE, self.args.test_apk)):
                self.installed.append(package)  # Own the attempt even if adb is cancelled after pm installed it.
                test_only = ['-t'] if package == TEST_PACKAGE else []
                self.adb_command('install-' + package, 'install', *test_only, '--no-streaming', str(path), timeout=120)
            self.receipt['installedApp'] = self.installed_hash('app', PACKAGE, info['apk_sha256'])
            self.receipt['installedTest'] = self.installed_hash('test', TEST_PACKAGE, sha(self.args.test_apk))
            self.adb_command('package-before', 'shell', 'dumpsys', 'package', PACKAGE)
            launcher = self.adb_command('manifest-launcher', 'shell', 'cmd', 'package', 'resolve-activity', '--brief',
                                        '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER', PACKAGE).decode()
            if launcher.strip().splitlines()[-1] not in (PACKAGE + '/' + LAUNCHER, PACKAGE + '/.FlutterMainActivity'):
                raise ValueError('Installed manifest does not launch the real Flutter host')
            self.adb_command('locale-set', 'shell', 'cmd', 'locale', 'set-app-locales', PACKAGE, '--user', '0', '--locales', 'en-US')
            self.adb_command('locale-readback', 'shell', 'cmd', 'locale', 'get-app-locales', PACKAGE, '--user', '0')
            self.adb_command('display-size', 'shell', 'wm', 'size')
            self.adb_command('display-density', 'shell', 'wm', 'density')
            self.receipt['stage'] = 'consent-navigation-theme12'
            token = 'flutter-' + os.urandom(12).hex()
            first = self.instrument('consentNavigationTheme12', token, info)
            pid = str(first['pid'])
            self.receipt['firstPid'] = pid
            log = self.adb_command('first-logcat', 'logcat', '-d', '-v', 'threadtime', timeout=45).decode(errors='replace')
            self.receipt['appRenderer'] = renderer(log, pid)
            self.check_process_log(log, pid)
            self.adb_command('first-window-insets', 'shell', 'dumpsys', 'window', 'windows')
            self.adb_command('first-activity', 'shell', 'dumpsys', 'activity', 'activities')
            self.adb_command('flutter-threads', 'shell', 'ps', '-T', '-p', pid)
            self.receipt['stage'] = 'real-process-restart-consent'
            self.adb_command('restart-stop-owned-app', 'shell', 'am', 'force-stop', PACKAGE)
            restarted = self.instrument('persistedConsentAfterProcessRestart', token + '-restart', info)
            if str(restarted['pid']) == pid:
                raise ValueError('Restart check reused the first process')
            log = self.adb_command('restart-logcat', 'logcat', '-d', '-v', 'threadtime', timeout=45).decode(errors='replace')
            self.receipt['restartRenderer'] = renderer(log, restarted['pid'])
            self.check_process_log(log, restarted['pid'])
            self.receipt['installedAppAfter'] = self.installed_hash('app-after', PACKAGE, info['apk_sha256'])
            if sha(self.args.apk) != info['apk_sha256']:
                raise ValueError('Original APK changed during acceptance')
            self.receipt.update(status='captured-pending-visual-review', stage='complete', representativeScenarioPassed=True,
                                matrixImages=12, restartPid=str(restarted['pid']))
            result = 0
        except BaseException as error:
            self.receipt.update(status='cancelled' if self.cancelled else 'failed', error=repr(error))
        finally:
            # Preserve diagnostics before cleanup; no emulator kill, global adb restart, VPN or host network action.
            diagnostics = (
                ('final-logcat', ['logcat', '-d', '-v', 'threadtime']),
                ('final-window', ['shell', 'dumpsys', 'window', 'windows']),
                ('failure-hierarchy', ['shell', 'uiautomator', 'dump', '/sdcard/flutter-qa-failure.xml']),
                ('failure-hierarchy-read', ['exec-out', 'cat', '/sdcard/flutter-qa-failure.xml']),
                ('final-screen.png', ['exec-out', 'screencap', '-p']))
            for label, command in diagnostics if self.owner_verified else ():
                try:
                    self.adb_command(label, *command, timeout=20, check=False, cancelling=True)
                except BaseException as error:
                    self.receipt.setdefault('diagnosticErrors', []).append(repr(error))
            for package in reversed(self.installed):
                try:
                    self.adb_command('cleanup-stop-' + package, 'shell', 'am', 'force-stop', package,
                                     check=False, cancelling=True)
                    self.adb_command('cleanup-uninstall-' + package, 'uninstall', package, timeout=45, cancelling=True)
                except BaseException as error:
                    self.receipt.setdefault('cleanupErrors', []).append(repr(error))
            if self.receipt.get('cleanupErrors'):
                self.receipt['cleanupStatus'] = 'failed'
            else:
                self.receipt['cleanupStatus'] = 'complete' if self.installed else 'not-required'
            save(self.args.output / 'qa-receipt.json', self.receipt)
            for name, handler in self.previous_signals.items():
                signal.signal(name, handler)
        return 1 if self.receipt.get('cleanupErrors') else result

    @staticmethod
    def check_process_log(log, pid):
        lines = log.splitlines()
        for index, line in enumerate(lines):
            own = re.match(r'\S+\s+\S+\s+' + re.escape(str(pid)) + r'\s', line)
            if (own and any(term in line for term in ('FATAL EXCEPTION', 'Fatal signal', '[ERROR:flutter/runtime/', 'Unhandled Exception'))
                    or ('FATAL EXCEPTION' in line and f'Process: {PACKAGE}, PID: {pid}' in '\n'.join(lines[index:index + 8]))
                    or re.search(r'>>> ' + re.escape(PACKAGE) + r' <<<', line)):
                raise RuntimeError('Production process failure: ' + line)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('apk', 'test-apk', 'build-info', 'adb', 'apksigner', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--serial', required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    idle()
    return Producer(args).run()


if __name__ == '__main__':
    raise SystemExit(main())
