"""Observe the untouched run290 APK with a separate mapped Surface test runner.

Reuses the existing artifact reader, emulator owner and Flutter QA IO. The older
265 probe pins a different graphics route, so it cannot admit this original APK.
"""
import argparse
import json
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import shutil
import signal
import stat
import subprocess
import time
from uuid import uuid4
import zipfile

import ci_artifacts as artifacts
import lite_release as release
from flutter_android_qa import Producer, PACKAGE, idle, renderer, save, sha, validate_binding
from instrumentation_shards import parse_log
from lite265_surface_probe import harness_binding
from verify_apk import signing_certificate

IDENTITY = dict(sourceRevision='dbf758c8db04516780513453fa65609ee2e8e80f',
                sourceTree='8af4cc19bbeceb46810f68166aced2b42c792ac2',
                workflowRun='42236353882', runAttempt=1, runNumber=290)
ARCHIVE = dict(id=13516645962, name='AEROVE-Lite-runtime-contract-attempt-1',
               size_in_bytes=59641508,
               digest='sha256:febafd629495244273d4fcc989b0cf883fa1909791473efb36a5b4530231efb5')
APKS = {'app-release.apk': 'e8a0a712317cf59f8aad5034ad2c560f0f01f520f668cb241146731a9b831108',
        'app-release-androidTest.apk': '2d45c0e261e296f1a0b92f846486da5323306537072d771773096348659081c3'}
MANIFEST = '1f63949cddca48bd1603c3f261a21a351e5e727cb7e84aaa18362c0d28fd55c2'
MAPPING = dict(sha256='7365901cbb1d15ea75521b172ab43be7f99993929994c98bd9468bc2b65f1a8a',
               pgMapId='2ec72d72bdeb76128550b7e7eb062450ca6ff4f15d6a5a1b78a8b62670e20cfa')
SIGNER = '09f9e021ddac36ead015d27e073b673e79fdf4e02605c402a505781963b1a4d8'
HARNESS = PACKAGE + '.test'
SELECTOR = PACKAGE + '.SurfacePixelDiagnosticTest#testOriginal290MappedRendererAndSurface'
OPT_IN = 'AEROVE_LITE290_SURFACE_PROBE'
BRANCH = 'codex/lite290-same-apk-probe'
LAVAPIPE_OPT_IN = 'AEROVE_LITE290_LAVAPIPE'


def require(value, message):
    if not value:
        raise ValueError(message)


def enabled():
    if OPT_IN not in os.environ:
        return False
    require(os.environ[OPT_IN] == '1' and
            os.environ.get('GITHUB_REPOSITORY') == release.REPO and
            os.environ.get('GITHUB_ACTOR') == 'IvanSolis1989' and
            os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch' and
            os.environ.get('GITHUB_REF') == 'refs/heads/' + BRANCH and
            os.environ.get('AEROVE_GUEST_ANGLE') == '1', 'Fixed290 diagnostic branch/event/graphics required')
    require(not any(os.environ.get(key) for key in (
        'AEROVE_LITE265_RECHECK', 'AEROVE_LITE265_SURFACE_PROBE', 'AEROVE_ORIGINAL84_PROOF',
        'AEROVE_ORIGINAL_RUNTIME_PROOF', 'AEROVE_QA79_PROOF', 'AEROVE_ORIGINAL_RUNTIME_TARGET',
        'AEROVE_ORIGINAL_SAMPLE', 'AEROVE_ORIGINAL_SAMPLE_SELECTOR', 'AEROVE_BETA14_PROFILE',
        'AEROVE_GFXSTREAM_SOURCE_RECEIPT', 'AEROVE_EMULATOR_SIGILL_TRACE')),
        'Fixed290 diagnosis cannot mix historical runtime or renderer opt-ins')
    return True


def lavapipe_requested():
    value = os.environ.get(LAVAPIPE_OPT_IN)
    if value is None:
        return False
    require(value == '1' and enabled(), 'Lavapipe comparison requires the fixed original290 diagnostic')
    require(not any(os.environ.get(key) for key in ('VK_ICD_FILENAMES', 'VK_DRIVER_FILES',
            'VK_ADD_DRIVER_FILES', 'MESA_LOADER_DRIVER_OVERRIDE')), 'Hidden graphics overrides cannot join this comparison')
    return True


def inspect_lavapipe_driver(sdk):
    """Read the verified SDK ICD and its bundled library; install or select nothing."""
    root = (Path(sdk) / 'emulator').resolve(strict=True)
    directory = root / 'lib64/vulkan'
    result = dict(sdkEmulator=str(root), candidates=[], passed=False)
    for path in sorted(directory.glob('*.json')):
        data = json.loads(path.read_text())
        declared = data.get('ICD', {}).get('library_path')
        if not isinstance(declared, str) or not any(word in (path.name + declared).lower() for word in ('lavapipe', 'lvp')):
            continue
        row = dict(icd=str(path), icdSha256=sha(path), declaredLibrary=declared, ready=False)
        result['candidates'].append(row)
        try:
            library = (path.parent / declared).resolve(strict=True)
            require(library.is_relative_to(root) and library.is_file(), 'Comparison library must belong to the verified SDK')
            search = [str(root / 'lib64'), str(directory)]
            search.extend(part for part in os.environ.get('LD_LIBRARY_PATH', '').split(os.pathsep) if part)
            environment = dict(os.environ, LD_LIBRARY_PATH=os.pathsep.join(search))
            dependencies = subprocess.run(['ldd', str(library)], capture_output=True, text=True,
                                           timeout=20, env=environment)
            row.update(library=str(library), bytes=library.stat().st_size, sha256=sha(library),
                dependencyExit=dependencies.returncode, dependencies=dependencies.stdout,
                dependencyError=dependencies.stderr, inspectionLibrarySearch=search,
                dependencyInspectionOnly=True)
            row['ready'] = dependencies.returncode == 0 and 'not found' not in dependencies.stdout
        except (OSError, ValueError, subprocess.TimeoutExpired) as failure:
            row['failureClass'] = type(failure).__name__
    result['passed'] = bool(result['candidates']) and all(row['ready'] for row in result['candidates'])
    return result


def check_api(run, jobs, row):
    require(run.get('id') == int(IDENTITY['workflowRun']) and run.get('run_attempt') == 1 and
            run.get('run_number') == 290 and run.get('head_sha') == IDENTITY['sourceRevision'] and
            run.get('head_branch') == 'codex/lite38-subscription-compat' and
            run.get('path') == '.github/workflows/aerove-lite.yaml' and
            run.get('status') == 'completed' and run.get('conclusion') == 'failure' and
            run.get('repository', {}).get('full_name') == release.REPO, 'Original290 run changed')
    rows = jobs.get('jobs', [])
    require(jobs.get('total_count') == len(rows) == len({job['id'] for job in rows}),
            'Original290 producer inventory is incomplete')
    package = next((job for job in rows if job['id'] == 127967391561), {})
    require(package.get('name') == 'build (x86_64) / package' and
            package.get('run_id') == run['id'] and package.get('run_attempt') == 1 and
            package.get('head_sha') == IDENTITY['sourceRevision'] and
            package.get('status') == 'completed' and package.get('conclusion') == 'success',
            'Original290 x86 package producer did not succeed')
    require(all(row.get(key) == value for key, value in ARCHIVE.items()) and row.get('expired') is False and
            row.get('workflow_run') == dict(id=run['id'], repository_id=1429390079,
                head_repository_id=1429390079, head_branch='codex/lite38-subscription-compat',
                head_sha=IDENTITY['sourceRevision']), 'Original290 artifact identity changed')


def runtime(directory, installed=True):
    require(enabled(), 'Explicit original290 diagnostic required')
    directory = Path(directory)
    release.product_source.bind_archive(directory.with_suffix('.zip'), directory, ARCHIVE)
    manifest = artifacts.verify(directory, IDENTITY, runtime=True)
    require(sha(directory / 'source-manifest.json') == MANIFEST, 'Original290 seal changed')
    mapping = directory / 'mapping/release/mapping.txt'
    require(sha(mapping) == MAPPING['sha256'], 'Original290 R8 map changed')
    with mapping.open(encoding='utf-8') as stream:
        header = ''.join(next(stream) for _ in range(8))
    require(MAPPING['pgMapId'] in header, 'Original290 R8 map ID changed')
    info = release._json(directory / 'BUILD_INFO.json')
    expected = dict(source=IDENTITY['sourceRevision'], tree=IDENTITY['sourceTree'],
        product='AEROVE Lite', channel='preview', application_id=PACKAGE, abi='x86_64', min_sdk=33,
        version='0.2.0-beta.6', version_code=38, run_number=290, run_attempt=1,
        workflow='https://github.com/' + release.REPO + '/actions/runs/' + IDENTITY['workflowRun'],
        apk_sha256=APKS['app-release.apk'], signer_sha256=SIGNER)
    require(all(info.get(key) == value for key, value in expected.items()), 'Original290 BUILD_INFO changed')
    for name, digest in APKS.items():
        require(sha(directory / 'apks' / name) == digest, 'Original290 APK changed')
        if installed:
            location = 'release' if name == 'app-release.apk' else 'androidTest/release'
            require(sha(Path('apps/aerove-lite/app/build/outputs/apk') / location / name) == digest,
                    'Staged original290 APK changed')
    from verify_apk import verify_bundled_script_apk
    return dict(identity=IDENTITY, apks=APKS, runtimeManifestSha256=MANIFEST,
        apkInspectorOutcome=manifest['apkInspectorOutcome'],
        bundledMeowScript=verify_bundled_script_apk(directory / 'apks/app-release.apk'),
        original290Probe=True, originalAppIdentity=IDENTITY, harnessIdentity=artifacts.identity(),
        originalMapping=MAPPING, diagnosticOnly=True, accepted=False,
        requestedGraphicsRoute='guest-angle-vulkan-lavapipe' if lavapipe_requested() else 'guest-angle-vulkan-swiftshader')


def fetch(directory):
    require(enabled(), 'Explicit original290 diagnostic required')
    base = 'repos/' + release.REPO + '/actions/'
    run = release._gh_json(base + 'runs/' + IDENTITY['workflowRun'])
    jobs = release._gh_json(base + 'runs/' + IDENTITY['workflowRun'] + '/attempts/1/jobs?per_page=100')
    row = release._gh_json(base + 'artifacts/' + str(ARCHIVE['id']))
    check_api(run, jobs, row)
    directory = Path(directory)
    archive = directory.with_suffix('.zip')
    require(not directory.exists() and not archive.exists(), 'Never overwrite original290 input')
    release._gh_binary(base + 'artifacts/' + str(ARCHIVE['id']) + '/zip', archive)
    require(archive.stat().st_size == ARCHIVE['size_in_bytes'] and
            'sha256:' + sha(archive) == ARCHIVE['digest'], 'Original290 ZIP size/hash changed')
    with zipfile.ZipFile(archive) as package:
        entries = package.infolist()
        require(entries and len({entry.filename for entry in entries}) == len(entries) and
                sum(entry.file_size for entry in entries) <= 512 * 1024 * 1024, 'Unsafe ZIP inventory/expansion')
        for entry in entries:
            posix, windows = PurePosixPath(entry.filename), PureWindowsPath(entry.filename)
            require(not posix.is_absolute() and not windows.drive and not windows.is_absolute() and
                    '..' not in posix.parts and '\\' not in entry.filename and
                    all(part not in ('', '.', '..') and ':' not in part
                        for part in entry.filename.rstrip('/').split('/')) and
                    stat.S_IFMT(entry.external_attr >> 16) in (0, stat.S_IFREG, stat.S_IFDIR), 'Unsafe ZIP path')
        require(package.testzip() is None, 'Original290 ZIP CRC failed')
        package.extractall(directory)
    runtime(directory, installed=False)
    for name, location in (('app-release.apk', 'release'), ('app-release-androidTest.apk', 'androidTest/release')):
        target = Path('apps/aerove-lite/app/build/outputs/apk') / location / name
        require(not target.exists(), 'Never overwrite staged APK')
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(directory / 'apks' / name, target)
    save(directory.parent / 'original290-fetch.json', dict(originalProducer=IDENTITY, artifactApi=row,
        runApi=run, producerJobsApi=jobs, harnessIdentity=artifacts.identity(), appCompiled=False, accepted=False))


def original_inputs(directory, proof):
    require(enabled() and proof.get('original290Probe') is True and proof.get('identity') == IDENTITY and
            proof.get('harnessIdentity') == artifacts.identity(), 'Original290 dual identity required')
    result = dict(apk=Path('apps/aerove-lite/app/build/outputs/apk/release/app-release.apk').resolve(),
        test_apk=Path('apps/aerove-lite/app/build/outputs/apk/androidTest/release/app-release-androidTest.apk').resolve(),
        build_info=(Path(directory) / 'BUILD_INFO.json').resolve())
    require(validate_binding(result['apk'], result['test_apk'], release._json(result['build_info']),
            dict(proof, owner='emulator_current.py')) == IDENTITY, 'Original290 APK inputs differ')
    return result


class Probe(Producer):
    def checkpoint(self, marker):
        """Start adjacent host observations together; join every owned subprocess."""
        self.receipt['hostCheckpoint'] = dict(marker=marker, atomicSameFrame=False, commands=[])
        reads = [('screen', ['exec-out', 'screencap', '-p']),
                 ('window', ['shell', 'dumpsys', '-t', '10', 'window', 'windows']),
                 ('surfaceflinger', ['shell', 'dumpsys', '-t', '10', 'SurfaceFlinger'])]
        children = []
        try:
            for name, arguments in reads:
                row = dict(name=name, command=self.adb + arguments, startMonotonicNs=time.monotonic_ns())
                self.receipt['hostCheckpoint']['commands'].append(row)
                out = (self.args.output / ('checkpoint-' + name + '.out')).open('wb')
                err = (self.args.output / ('checkpoint-' + name + '.err')).open('wb')
                try:
                    child = subprocess.Popen(row['command'], stdin=subprocess.DEVNULL, stdout=out, stderr=err)
                    children.append((child, row, out, err))
                except BaseException:
                    out.close()
                    err.close()
                    raise
            deadline = time.monotonic() + 15
            for child, row, _, _ in children:
                try:
                    row['exit'] = child.wait(timeout=max(.01, deadline - time.monotonic()))
                except subprocess.TimeoutExpired:
                    row['timedOut'] = True
                row['endMonotonicNs'] = time.monotonic_ns()
        finally:
            for child, row, out, err in children:
                try:
                    if child.poll() is None:
                        child.terminate()
                        try:
                            child.wait(timeout=3)
                        except subprocess.TimeoutExpired:
                            child.kill()
                            child.wait(timeout=3)
                    row['exit'] = child.returncode
                    row['joined'] = True
                except Exception as failure:
                    row.update(cleanupFailureClass=type(failure).__name__, joined=False)
                finally:
                    out.close()
                    err.close()

    def observe(self, command, remote, token):
        row = dict(label='surface-instrument', command=command, startMonotonicNs=time.monotonic_ns())
        self.receipt['commands'].append(row)
        child = None
        with (self.args.output / 'surface-instrument.out').open('wb') as out, \
                (self.args.output / 'surface-instrument.err').open('wb') as err:
            try:
                child = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=out, stderr=err)
                deadline = time.monotonic() + 170
                marker_deadline = time.monotonic() + 45
                attempt = 0
                while child.poll() is None:
                    require(not self.cancelled and time.monotonic() < deadline, 'Instrumentation cancelled/timed out')
                    if 'hostCheckpoint' not in self.receipt and time.monotonic() < marker_deadline:
                        attempt += 1
                        raw = self.adb_command('checkpoint-marker-' + str(attempt), 'exec-out', 'cat',
                                               remote + '/checkpoint.json', timeout=3, check=False)
                        try:
                            marker = json.loads(raw)
                        except (ValueError, UnicodeDecodeError):
                            marker = None  # A marker may not have been published yet.
                        if marker is not None:
                            require(marker.get('qaToken') == token and type(marker.get('pid')) is int and
                                    marker['pid'] > 0 and type(marker.get('elapsedRealtimeNanos')) is int and
                                    marker['elapsedRealtimeNanos'] > 0, 'Invalid checkpoint identity')
                            self.checkpoint(marker)
                    time.sleep(.25)
                row['exit'] = child.returncode
            finally:
                if child is not None and child.poll() is None:
                    child.terminate()
                    try:
                        child.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        child.kill()
                        child.wait(timeout=5)
                row.update(exit=child.returncode if child else None, endMonotonicNs=time.monotonic_ns(),
                           joined=child is not None and child.poll() is not None)
        return row

    def run(self):
        require(enabled(), 'Explicit original290 diagnostic required')
        self.receipt.update(producer='lite290_surface_probe.py', diagnosticOnly=True, accepted=False,
            appCompiled=False, harnessCompiled=True, originalProducer=IDENTITY, originalMapping=MAPPING,
            harnessIdentity=artifacts.identity())
        result, remote = 1, None
        for name in (signal.SIGINT, signal.SIGTERM):
            self.previous_signals[name] = signal.signal(name, self.interrupt)
        try:
            from emulator_current import validate_renderer_proof
            proof = validate_renderer_proof(Path(os.environ['AEROVE_CURRENT_RENDERER_PROOF']))
            require(validate_binding(self.args.apk, self.args.test_apk, release._json(self.args.build_info), proof) == IDENTITY
                    and proof['deviceSerial'] == self.args.serial == os.environ['ANDROID_SERIAL'], 'Original290 owner differs')
            harness = release._json(self.args.harness_info)
            harness_binding(self.args.harness, harness)
            self.receipt['harnessBuild'] = harness
            for package in (PACKAGE, HARNESS):
                require(b'package:' not in self.adb_command('preexisting-' + package, 'shell', 'pm', 'path', package, check=False),
                        'Fresh owned emulator required; never replace user data')
            self.owner_verified = True
            for package, path in ((PACKAGE, self.args.apk), (HARNESS, self.args.harness)):
                signature = self.command('signature-' + package,
                    [self.args.apksigner, 'verify', '--verbose', '--print-certs', path]).decode()
                require(signing_certificate(signature) == SIGNER, 'Original App or harness signer differs')
                self.installed.append(package)
                self.adb_command('install-' + package, 'install', *(['-t'] if package == HARNESS else []),
                                 '--no-streaming', str(path), timeout=120)
                self.installed_hash('installed-' + package, package, sha(path))
            token = 'surface-' + uuid4().hex
            remote = '/sdcard/Android/data/' + PACKAGE + '/files/surface-pixel-diagnostic/' + token
            values = dict(qaToken=token, originalAppApkSha256=APKS['app-release.apk'],
                originalAppSignerSha256=SIGNER, originalAppVersionCode='38',
                harnessApkSha256=sha(self.args.harness), harnessSignerSha256=SIGNER,
                harnessSourceSha=os.environ['GITHUB_SHA'], harnessBuildRun=os.environ['GITHUB_RUN_ID'],
                harnessBuildAttempt=os.environ['GITHUB_RUN_ATTEMPT'], executorSourceSha=os.environ['GITHUB_SHA'],
                executionRun=os.environ['GITHUB_RUN_ID'], executionAttempt=os.environ['GITHUB_RUN_ATTEMPT'])
            command = self.adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', SELECTOR]
            for key, value in values.items():
                command += ['-e', key, value]
            command += [HARNESS + '/android.test.InstrumentationTestRunner']
            invocation = self.observe(command, remote, token)
            self.adb_command('surface-pull', 'pull', remote, str(self.args.output / 'capture'), timeout=45)
            capture = release._json(self.args.output / 'capture/receipt.json')
            records = parse_log(self.args.output / 'surface-instrument.out', expected_reporter='InstrumentationTestRunner')
            self.receipt['instrumentationRecords'] = records
            self.receipt['captureSha256'] = sha(self.args.output / 'capture/receipt.json')
            require(capture.get('originalMapping') == MAPPING and
                    capture['originalApp']['apkSha256'] == APKS['app-release.apk'] and
                    capture.get('diagnosticOnly') is True and capture.get('accepted') is False,
                    'Captured original APK/mapping identity differs')
            log = self.adb_command('app-pid-renderer', 'logcat', '-d', '-v', 'threadtime', timeout=45).decode(errors='replace')
            self.receipt['appRenderer'] = renderer(log, capture['pid'])
            require(any('Using the Impeller rendering backend (OpenGLES)' in line
                        for line in self.receipt['appRenderer']['lines']), 'Original Impeller OpenGLES was not observed')
            self.receipt['installedAppAfter'] = self.installed_hash('app-after', PACKAGE, APKS['app-release.apk'])
            require(len(records) == 1 and records[0]['className'] + '#' + records[0]['testName'] == SELECTOR and
                    records[0]['status'] == 'passed' and capture.get('captureCompleted') is True and
                    capture.get('resourceCleanup') == 'complete' and 'hostCheckpoint' in self.receipt and
                    invocation.get('exit') == 0 and invocation.get('joined') is True,
                    'Surface diagnostic recorded a failure or incomplete observation; inspect raw evidence')
            require(self.receipt['hostCheckpoint']['marker']['pid'] == capture['pid'] and
                    all(row.get('exit') == 0 and row.get('joined') is True and not row.get('timedOut')
                        for row in self.receipt['hostCheckpoint']['commands']) and
                    all((self.args.output / ('checkpoint-' + name + '.out')).stat().st_size > 0
                        for name in ('window', 'surfaceflinger')) and
                    (self.args.output / 'checkpoint-screen.out').read_bytes().startswith(b'\x89PNG\r\n\x1a\n'),
                    'Adjacent host captures failed or do not bind the same App process')
            for field, name in (('surfaceCopy', 'surface.png'), ('windowCopy', 'window.png'), ('compositorCopy', 'compositor.png')):
                require(capture[field]['image']['sha256'] == sha(self.args.output / 'capture' / name), 'PNG changed')
            require(sha(self.args.apk) == APKS['app-release.apk'], 'Original App file changed during observation')
            self.receipt['status'], result = 'observed-pending-pixel-review', 0
        except BaseException as failure:
            self.receipt.update(status='cancelled' if self.cancelled else 'failed', failureClass=type(failure).__name__)
            raise
        finally:
            if self.owner_verified:
                if remote and not (self.args.output / 'capture/receipt.json').exists():
                    try:
                        self.adb_command('failure-capture-pull', 'pull', remote, str(self.args.output / 'capture'),
                                         timeout=45, check=False, cancelling=True)
                    except Exception as failure:
                        self.receipt['pullFailureClass'] = type(failure).__name__
                for name, arguments in (('final-logcat', ['logcat', '-d', '-v', 'threadtime']),
                                        ('final-window', ['shell', 'dumpsys', '-t', '10', 'window', 'windows'])):
                    try:
                        self.adb_command(name, *arguments, timeout=45, check=False, cancelling=True)
                    except Exception as failure:
                        self.receipt.setdefault('diagnosticErrors', []).append(type(failure).__name__)
            for package in reversed(self.installed):
                for label, arguments in (('cleanup-stop-', ['shell', 'am', 'force-stop', package]),
                                         ('cleanup-uninstall-', ['uninstall', package])):
                    try:
                        self.adb_command(label + package, *arguments, timeout=45, cancelling=True)
                    except Exception as failure:
                        self.receipt.setdefault('cleanupErrors', []).append(label + type(failure).__name__)
            self.receipt['result'] = 1 if self.cancelled or self.receipt.get('cleanupErrors') else result
            self.receipt['files'] = [dict(path=p.relative_to(self.args.output).as_posix(), bytes=p.stat().st_size, sha256=sha(p))
                                     for p in self.args.output.rglob('*') if p.is_file() and p.name != 'PROBE.json']
            save(self.args.output / 'PROBE.json', self.receipt)
            for name, handler in self.previous_signals.items():
                signal.signal(name, handler)
        return self.receipt['result']


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--fetch', type=Path)
    for name in ('apk', 'test-apk', 'build-info', 'adb', 'apksigner', 'harness', 'harness-info', 'output'):
        parser.add_argument('--' + name, type=Path)
    parser.add_argument('--serial')
    args = parser.parse_args()
    idle()
    if args.fetch:
        fetch(args.fetch)
        return 0
    require(all(getattr(args, name) for name in ('apk', 'test_apk', 'build_info', 'adb', 'apksigner',
                                               'harness', 'harness_info', 'output', 'serial')), 'Probe inputs missing')
    args.output.mkdir(parents=True, exist_ok=False)
    return Probe(args).run()


if __name__ == '__main__':
    raise SystemExit(main())
