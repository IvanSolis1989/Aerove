"""Fixed official Flutter hello_world build/observation; never AEROVE acceptance."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time
from uuid import uuid4
import zipfile

import ci_artifacts
from build_process_owner_fixture import installed_tools
from flutter_android_qa import Producer, idle, renderer, save, sha
from instrumentation_shards import parse_log
from verify_apk import signing_certificate

PACKAGE = 'dev.aerove.diagnostic.flutter_onscreen_control'
RUNNER = PACKAGE + '.test'
ACTIVITY = PACKAGE + '.MainActivity'
SELECTOR = 'dev.aerove.diagnostic.FlutterHelloWorldControlTest#testFirstScreen'
OPT_IN = 'AEROVE_FLUTTER_HELLO_CONTROL'
BRANCH = 'codex/flutter-onscreen-control-20261107'
FRAMEWORK = '5fc346839b5d0eef006ed8404392afb4dfae428d'
ENGINE = '692136cb6582dbfc5af3fb33c2515a069f2f66d0'
MAIN_SHA = '2a09b5a215eed2e6cab96338a6b892f674c8ffbde86710bbea49fc5fa06a1a6f'
ENGINE_SHA = 'e7233d989b5454bb1ac5ca0359522749c068fa7821e9cdc0c1b056fd43561265'
ENGINE_BYTES = 13051424
SOURCE = Path(__file__).with_name('hello_control')
OVERRIDES = ('VK_ICD_FILENAMES', 'VK_DRIVER_FILES', 'VK_ADD_DRIVER_FILES', 'MESA_LOADER_DRIVER_OVERRIDE')
CONFLICTS = ('AEROVE_LITE290_SURFACE_PROBE', 'AEROVE_LITE290_LAVAPIPE', 'AEROVE_LITE290_BOOT_CRASH',
    'AEROVE_LITE265_RECHECK', 'AEROVE_LITE265_SURFACE_PROBE', 'AEROVE_ORIGINAL84_PROOF',
    'AEROVE_ORIGINAL_RUNTIME_PROOF', 'AEROVE_QA79_PROOF', 'AEROVE_ORIGINAL_RUNTIME_TARGET',
    'AEROVE_ORIGINAL_SAMPLE', 'AEROVE_ORIGINAL_SAMPLE_SELECTOR', 'AEROVE_BETA14_PROFILE',
    'AEROVE_GFXSTREAM_SOURCE_RECEIPT', 'AEROVE_EMULATOR_SIGILL_TRACE')


def require(value, message):
    if not value:
        raise ValueError(message)


def enabled():
    if OPT_IN not in os.environ:
        return False
    require(os.environ[OPT_IN] == '1' and os.environ.get('GITHUB_REPOSITORY') == 'IvanSolis1989/Aerove'
        and os.environ.get('GITHUB_ACTOR') == 'IvanSolis1989'
        and os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch'
        and os.environ.get('GITHUB_REF') == 'refs/heads/' + BRANCH
        and os.environ.get('AEROVE_GUEST_ANGLE') == '1', 'Fixed control branch/actor/event/graphics required')
    require(not any(os.environ.get(key) for key in CONFLICTS + OVERRIDES), 'Mixed product/runtime/graphics opt-ins')
    return True


def ci_only():
    require(enabled() and sys.platform == 'linux' and os.environ.get('CI') == 'true'
        and os.environ.get('GITHUB_ACTIONS') == 'true', 'Control execution only in the owned Linux CI')


def engine_identity(apk):
    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
        require(len(names) == len(set(names)), 'Duplicate control APK entries')
        require({p.split('/')[1] for p in names if p.startswith('lib/')} == {'x86_64'}, 'Control ABI differs')
        data = z.read('lib/x86_64/libflutter.so')
        require(len(data) == ENGINE_BYTES and hashlib.sha256(data).hexdigest() == ENGINE_SHA,
                'Control-invalid: Flutter engine bytes differ from original38; never replace the library')
        require(z.read('lib/x86_64/libapp.so').startswith(b'\x7fELF'), 'Missing release AOT ELF')
        require(not any(Path(name).name in ('libmihomo.so', 'libclash.so', 'libaerove_meow_core.so') for name in names),
                'Product core must not enter official control')
    return dict(bytes=len(data), sha256=ENGINE_SHA, framework=FRAMEWORK, engineRevision=ENGINE)


def check_badging(text, package, launcher=None):
    require(re.findall(r"^package: name='([^']+)'", text, re.M) == [package]
        and re.findall(r"^sdkVersion:'([^']+)'", text, re.M) == ['33']
        and re.findall(r"^targetSdkVersion:'([^']+)'", text, re.M) == ['37'], 'Compiled control package/SDK differs')
    require('application-debuggable' not in text, 'Control must be release/AOT, not debug')
    if launcher:
        require(re.findall(r"^launchable-activity: name='([^']+)'", text, re.M) == [launcher], 'Control launcher differs')
    else:
        require('launchable-activity:' not in text, 'Runner must not have a launcher')


class OwnedBuildCommands:
    """Own each build session and one private Gradle home; never signal global daemons."""
    def __init__(self, logs, work, budget=1200):
        self.logs, self.work = Path(logs), Path(work)
        self.commands, self.cleanup_errors, self.signals = [], [], []
        self.deadline = time.monotonic() + budget
        self.current = None
        self.current_row = None
        self.cleaning = False
        self.closed = False
        self.gradle_used = False
        self.gradle_project = None
        self.gradle_home = self.work / 'gradle-user-home'
        self.gradle_home.mkdir(exist_ok=False)
        self.environment = dict(os.environ, GRADLE_USER_HOME=str(self.gradle_home),
            GRADLE_OPTS=os.environ.get('GRADLE_OPTS', '') + ' -Dorg.gradle.daemon=false')
        self.previous = {sig: signal.signal(sig, self.interrupt) for sig in (signal.SIGINT, signal.SIGTERM)}

    def interrupt(self, sig, _frame):
        self.signals.append(sig)
        # Record only: never raise between Popen returning and ownership assignment.
        # The bounded command wait notices cancellation within250ms.

    @staticmethod
    def _stat(pid):
        text = (Path('/proc') / str(pid) / 'stat').read_text()
        fields = text[text.rfind(')') + 2:].split()
        return dict(state=fields[0], ppid=int(fields[1]), pgid=int(fields[2]), starttime=int(fields[19]))

    def _members(self, pgid):
        rows = []
        for path in Path('/proc').iterdir():
            if not path.name.isdecimal(): continue
            try:
                if path.stat().st_uid != os.getuid(): continue
                row = self._stat(int(path.name))
                if row['pgid'] == pgid and row['state'] != 'Z': rows.append(dict(pid=int(path.name), **row))
            except (OSError, ValueError): continue
        return rows

    def _send_group(self, pgid, sig):
        try: os.killpg(pgid, sig)
        except ProcessLookupError: pass

    def _stop(self, child, row):
        previous = self.cleaning
        self.cleaning = True
        try: self._stop_group(child, row)
        finally: self.cleaning = previous

    def _stop_group(self, child, row):
        pgid = row['processGroup']
        row['terminationRequested'] = True
        self._send_group(pgid, signal.SIGTERM)
        try: child.wait(timeout=5)
        except subprocess.TimeoutExpired:
            row['killRequested'] = True
            self._send_group(pgid, signal.SIGKILL)
            child.wait(timeout=5)
        row.update(exit=child.returncode, directChildJoined=child.poll() is not None)
        until = time.monotonic() + 2
        while self._members(pgid) and time.monotonic() < until: time.sleep(.05)
        survivors = self._members(pgid)
        if survivors:
            row['groupKillRequested'] = True
            self._send_group(pgid, signal.SIGKILL)
            until = time.monotonic() + 2
            while self._members(pgid) and time.monotonic() < until: time.sleep(.05)
        row['liveGroupMembersAfterCleanup'] = self._members(pgid)
        require(not row['liveGroupMembersAfterCleanup'], 'Owned build process group remains live')

    def command(self, label, argv, cwd=None, timeout=120):
        require(self.current is None, 'Prior owned command has not joined')
        if self.signals and not self.cleaning: raise InterruptedError('Owned control build interrupted')
        remaining = 30 if self.cleaning else self.deadline - time.monotonic()
        require(remaining > 0, 'Control build deadline expired')
        row = dict(label=label, command=[str(v) for v in argv], startedNs=time.monotonic_ns(),
                   timeoutSeconds=min(timeout, remaining), terminationRequested=False, killRequested=False)
        self.commands.append(row)
        if label == 'flutter-build-once': self.gradle_used = True
        with (self.logs / (label + '.out')).open('wb') as out, (self.logs / (label + '.err')).open('wb') as err:
            child = subprocess.Popen(row['command'], cwd=cwd, env=self.environment, stdin=subprocess.DEVNULL,
                stdout=out, stderr=err, start_new_session=True)
            self.current = child
            self.current_row = row
            row.update(pid=child.pid, processGroup=child.pid)
            try:
                try: row['targetStarttime'] = self._stat(child.pid)['starttime']
                except FileNotFoundError:
                    if child.poll() is None: raise
                    row['targetStarttime'] = None
                end = time.monotonic() + row['timeoutSeconds']
                while True:
                    if self.signals and not self.cleaning: raise InterruptedError('Owned control build interrupted')
                    left = end - time.monotonic()
                    if left <= 0: raise subprocess.TimeoutExpired(row['command'], row['timeoutSeconds'])
                    try:
                        code = child.wait(timeout=min(.25, left)); break
                    except subprocess.TimeoutExpired: continue
                row.update(exit=code, directChildJoined=True)
                require(code == 0, 'Control build command failed: ' + label)
                # A successful wrapper must not leave live work in its owned session.
                drain_end = time.monotonic() + 2
                while self._members(child.pid) and time.monotonic() < drain_end:
                    if self.signals and not self.cleaning: raise InterruptedError('Owned control build interrupted')
                    time.sleep(.05)
                if self._members(child.pid):
                    row['orphanedGroupAfterCommand'] = True
                    raise RuntimeError('Build command returned while its owned process group remained live')
                row['groupNaturallyDrained'] = True
            except BaseException as failure:
                row['failureClass'] = type(failure).__name__
                try:
                    if child.poll() is None or self._members(child.pid): self._stop(child, row)
                except BaseException as cleanup:
                    row['cleanupFailureClass'] = type(cleanup).__name__
                    self.cleanup_errors.append(type(cleanup).__name__)
                raise
            finally:
                row.update(exit=child.poll(), directChildJoined=child.poll() is not None, finishedNs=time.monotonic_ns())
                if child.poll() is not None and not self._members(child.pid):
                    self.current = self.current_row = None
        return (self.logs / (label + '.out')).read_text(errors='replace')

    def _private_daemons(self):
        rows = []
        expected = ('GRADLE_USER_HOME=' + str(self.gradle_home)).encode()
        for path in Path('/proc').iterdir():
            if not path.name.isdecimal(): continue
            try:
                if path.stat().st_uid != os.getuid(): continue
                if expected not in (path / 'environ').read_bytes().split(b'\0'): continue
                if b'org.gradle.launcher.daemon.bootstrap.GradleDaemon' not in (path / 'cmdline').read_bytes(): continue
                row = self._stat(int(path.name))
                if row['state'] != 'Z': rows.append(dict(pid=int(path.name), **row))
            except (OSError, ValueError): continue
        return rows

    def _end_private_daemons(self):
        """Failure cleanup only: bind exact private-home daemon PID/starttime via pidfd."""
        records = []
        for row in self._private_daemons():
            try: fd = os.pidfd_open(row['pid'])
            except ProcessLookupError: continue
            try:
                actual = self._stat(row['pid'])
                require(actual['starttime'] == row['starttime'] and
                    any(p['pid'] == row['pid'] and p['starttime'] == row['starttime'] for p in self._private_daemons()),
                    'Private daemon identity changed before cleanup')
                signal.pidfd_send_signal(fd, signal.SIGTERM)
                record = dict(row, terminationRequested=True, killRequested=False)
                end = time.monotonic() + 3
                while any(p['pid'] == row['pid'] for p in self._private_daemons()) and time.monotonic() < end: time.sleep(.05)
                if any(p['pid'] == row['pid'] for p in self._private_daemons()):
                    record['killRequested'] = True
                    signal.pidfd_send_signal(fd, signal.SIGKILL)
                    end = time.monotonic() + 3
                    while any(p['pid'] == row['pid'] for p in self._private_daemons()) and time.monotonic() < end: time.sleep(.05)
                record['exitObserved'] = not any(p['pid'] == row['pid'] for p in self._private_daemons())
                record['directChildJoined'] = False  # A detached daemon is reaped by its actual parent.
                records.append(record)
                require(record['exitObserved'], 'Private daemon remains alive after bounded cleanup')
            except ProcessLookupError:
                records.append(dict(row, exitObserved=True, directChildJoined=False))
            finally:
                os.close(fd)
        return records

    def close(self):
        if self.closed: return
        self.cleaning = True
        try:
            if self.current is not None:
                self._stop(self.current, self.current_row)
                self.current = self.current_row = None
            if self.gradle_used and ((self.gradle_home / 'daemon').is_dir() or self._private_daemons()):
                require(self.gradle_project is not None, 'Missing private Gradle build root')
                wrapper = self.gradle_project / 'gradlew'
                require(wrapper.is_file(), 'Private Gradle wrapper missing during cleanup')
                self.command('private-gradle-stop', [wrapper, '--stop', '--gradle-user-home', self.gradle_home],
                             cwd=self.gradle_project, timeout=25)
                until = time.monotonic() + 5
                while self._private_daemons() and time.monotonic() < until: time.sleep(.05)
                require(not self._private_daemons(), 'Private Gradle daemon did not exit normally; build remains failed')
            if self.signals: raise InterruptedError('Owned control build cancelled after cleanup')
        except BaseException as failure:
            self.cleanup_errors.append(type(failure).__name__)
            try:
                self.forced_daemon_cleanup = self._end_private_daemons()
            except BaseException as cleanup:
                self.cleanup_errors.append(type(cleanup).__name__)
            raise
        finally:
            try:
                save(self.logs / 'build-owner.json', dict(commands=self.commands, signals=self.signals,
                    gradleUserHome=str(self.gradle_home), gradlePersistentDaemon=False,
                    privateDaemonsRemaining=self._private_daemons(), cleanupErrors=self.cleanup_errors,
                    forcedDaemonCleanup=getattr(self, 'forced_daemon_cleanup', []),
                    closed=True, productAppCompiled=False))
            finally:
                for sig, handler in self.previous.items(): signal.signal(sig, handler)
                self.closed = True
                self.cleaning = False


def prepare(output):
    ci_only()
    require(not signed301_requested(), 'Signed301 mode cannot build any App or runner')
    output = Path(output).resolve()
    temp = Path(os.environ['RUNNER_TEMP']).resolve(strict=True)
    require(output.is_relative_to(temp) and output != temp and not output.exists(), 'New RUNNER_TEMP output required')
    output.mkdir(parents=True)
    work = temp / ('hello-control-work-' + os.environ['GITHUB_RUN_ID'] + '-' + os.environ['GITHUB_RUN_ATTEMPT'])
    require(not work.exists(), 'Never repeat or overwrite a control build')
    work.mkdir()
    logs = output / 'build-evidence'; logs.mkdir()
    owner = OwnedBuildCommands(logs, work)
    commands, command = owner.commands, owner.command
    try:
        sdk = Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ['ANDROID_HOME'])
        android, tools = installed_tools(sdk)
        runner_jars(sdk, android)  # Check the actual37.1 system API jars before generating or building the App.
        require((sdk / 'ndk/28.2.13676358/source.properties').is_file(), 'Official Flutter template NDK28.2.13676358 must already be installed')
        flutter = shutil.which('flutter'); require(flutter, 'Existing pinned Flutter tool missing')
        flutter_root = Path(flutter).resolve().parents[1]
        version = json.loads((flutter_root / 'bin/cache/flutter.version.json').read_text())
        require(version.get('frameworkVersion') == '3.47.6' and version.get('frameworkRevision') == FRAMEWORK
            and version.get('engineRevision') == ENGINE, 'Pinned official Flutter identity differs')
        upstream = flutter_root / 'examples/hello_world/lib/main.dart'
        require(sha(upstream) == MAIN_SHA, 'Official hello_world source bytes differ')
        license_file = flutter_root / 'LICENSE'; require(license_file.is_file(), 'Official Flutter license missing')
        require('javac 17' in command('javac-version', [tools['javac'], '-version']) or
                'javac 17' in (logs / 'javac-version.err').read_text(), 'Existing Java17 required')
        aligned_runner = compile_runner(output, work, sdk, android, tools, owner)
        project = work / 'project'
        command('flutter-create', [flutter, 'create', '--no-pub', '--platforms', 'android', '--org', 'dev.aerove.diagnostic',
            '--project-name', 'flutter_onscreen_control', project], timeout=180)
        owner.gradle_project = project / 'android'
        (project / 'lib/main.dart').write_bytes(upstream.read_bytes())
        (project / 'pubspec.yaml').write_text('name: flutter_onscreen_control\npublish_to: none\nversion: 1.0.0+1\nenvironment:\n  sdk: ">=3.13.0 <4.0.0"\ndependencies:\n  flutter:\n    sdk: flutter\n')
        gradle = project / 'android/app/build.gradle.kts'
        text = gradle.read_text()
        for old, new in (('compileSdk = flutter.compileSdkVersion', 'compileSdk { version = release(37) { minorApiLevel = 1 } }'), ('minSdk = flutter.minSdkVersion', 'minSdk = 33'), ('targetSdk = flutter.targetSdkVersion', 'targetSdk = 37')):
            require(text.count(old) == 1, 'Official template contract differs: ' + old); text = text.replace(old, new)
        require('signingConfig = signingConfigs.getByName("debug")' in text, 'Official release debug signing template differs')
        gradle.write_text(text)
        settings = project / 'android/settings.gradle.kts'
        settings_text = settings.read_text()
        old_agp = 'id("com.android.application") version "9.1.0" apply false'
        require(settings_text.count(old_agp) == 1, 'Pinned Flutter template AGP version differs')
        settings_text = settings_text.replace(old_agp, 'id("com.android.application") version "9.4.1" apply false')
        old_kotlin = 'id("org.jetbrains.kotlin.android") version "2.4.0" apply false'
        require(settings_text.count(old_kotlin) == 1, 'Pinned Flutter template Kotlin plugin differs')
        settings.write_text(settings_text.replace(old_kotlin, 'id("org.jetbrains.kotlin.android") version "2.4.10" apply false'))
        wrapper = project / 'android/gradle/wrapper/gradle-wrapper.properties'
        wrapper_text = wrapper.read_text()
        old_distribution = r'distributionUrl=https\://services.gradle.org/distributions/gradle-9.3.1-all.zip'
        require(wrapper_text.count(old_distribution) == 1, 'Pinned Flutter template Gradle distribution differs')
        wrapper.write_text(wrapper_text.replace(old_distribution, r'distributionUrl=https\://services.gradle.org/distributions/gradle-9.8.0-all.zip'))
        properties = project / 'android/gradle.properties'
        require(properties.read_text().count('android.newDsl=false') == 1 and
            properties.read_text().count('android.builtInKotlin=false') == 1, 'Pinned template classic Android/Kotlin flags differ')
        properties.write_text(properties.read_text() + '\norg.gradle.daemon=false\n')
        command('flutter-pub-get', [flutter, 'pub', 'get'], cwd=project, timeout=180)
        sources = output / 'sources'; sources.mkdir()
        shutil.copyfile(license_file, sources / 'FLUTTER-LICENSE')
        shutil.copyfile(SOURCE / 'FlutterHelloWorldControlTest.java', sources / 'FlutterHelloWorldControlTest.java')
        shutil.copyfile(SOURCE / 'AndroidManifest.xml', sources / 'RunnerManifest.xml')
        for file in project.rglob('*'):
            if file.is_file() and not any(part in ('build', '.dart_tool', '.gradle', '.git') for part in file.relative_to(project).parts):
                relative = file.relative_to(project)
                dest = sources / 'generated-project' / relative; dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(file, dest)
        command('flutter-build-once', [flutter, 'build', 'apk', '--release', '--target-platform', 'android-x64'], cwd=project, timeout=600)
        apk = output / 'control.apk'; shutil.copyfile(project / 'build/app/outputs/flutter-apk/app-release.apk', apk)
        library = engine_identity(apk)
        app_badging = command('control-badging', [tools['aapt'], 'dump', 'badging', apk]); check_badging(app_badging, PACKAGE, ACTIVITY)
        app_manifest = command('control-manifest', [tools['aapt'], 'dump', 'xmltree', apk, 'AndroidManifest.xml'])
        require('EnableImpeller' not in app_manifest, 'Control changed stock renderer metadata')
        require(re.search(r'android:hardwareAccelerated[^=]*=\(type 0x12\)0xffffffff', app_manifest), 'Compiled control hardware acceleration missing')
        # Preserve the single real App build before any runner compilation.
        app_signer = signing_certificate(command('control-app-signature', [tools['apksigner'], 'verify', '--verbose', '--print-certs', apk]))
        source_files = {q.relative_to(sources).as_posix(): dict(bytes=q.stat().st_size, sha256=sha(q))
                        for q in sources.rglob('*') if q.is_file()}
        app_checkpoint = dict(schema=1, kind='official-flutter-control-app-build', status='app-built-and-verified',
            diagnosticOnly=True, accepted=False, productAppCompiled=False, controlCompiled=True, runnerCompiled=False,
            identity=ci_artifacts.identity(), package=PACKAGE, activity=ACTIVITY, apkFile='control.apk',
            apkSha256=sha(apk), apkBytes=apk.stat().st_size, signerSha256=app_signer,
            officialSource=dict(framework=FRAMEWORK, engine=ENGINE, mainSha256=MAIN_SHA,
                url='https://github.com/flutter/flutter/blob/' + FRAMEWORK + '/examples/hello_world/lib/main.dart'),
            adapterModuleSha256=sha(Path(__file__)), sourceFiles=source_files, flutterLibrary=library,
            actualBuildFlags=['build', 'apk', '--release', '--target-platform', 'android-x64'],
            android=dict(compileSdk=37, compileSdkMinor=1, minSdk=33, targetSdk=37, releaseDebugSigner=True, stockRenderer=True),
            buildToolchain=dict(agp='9.4.1', gradle='9.8.0', kotlin='2.4.10', ndk='28.2.13676358'),
            buildCommand=next(row for row in commands if row['label'] == 'flutter-build-once'),
            adaptation='Official Android scaffold and original main; isolated Flutter-only pubspec; explicit SDK37.1/min33/target37 and original290 AGP9.4.1/Gradle9.8.0/Kotlin2.4.10',
            reuse='Retain this App checkpoint if the independent runner fails; rebuild only the runner')
        save(output / 'CONTROL-APP-BUILD.json', app_checkpoint)
        signer = sign_runner(output, aligned_runner, tools, owner,
            Path.home() / '.android/debug.keystore', app_signer)
        owner.close()
        save(logs / 'commands.json', commands)
        seal_control(output, app_checkpoint, signer, False)
    except BaseException as failure:
        try: owner.close()
        except BaseException as cleanup: owner.cleanup_errors.append(type(cleanup).__name__)
        save(logs / 'commands.json', commands)
        checkpoint = output / 'CONTROL-APP-BUILD.json'
        save(output / 'PREPARE-FAILED.json', dict(status='control-invalid', failureClass=type(failure).__name__, accepted=False,
            controlAppCheckpointReady=checkpoint.is_file(), controlAppCheckpointSha256=sha(checkpoint) if checkpoint.is_file() else None,
            reuseAppWithoutRebuild=checkpoint.is_file()))
        raise


def runner_jars(sdk, android):
    optional = sdk / 'platforms/android-37.1/optional'
    jars = [optional / (name + '.jar') for name in ('android.test.runner', 'android.test.base', 'android.test.mock')]
    require(android == sdk / 'platforms/android-37.1/android.jar' and android.is_file() and all(j.is_file() for j in jars),
            'Installed37.1 platform and actual system optional jars required; no37.0 fallback')
    names = set()
    for jar in jars:
        with zipfile.ZipFile(jar) as archive: names.update(archive.namelist())
    require({'android/test/InstrumentationTestCase.class', 'android/test/InstrumentationTestRunner.class',
             'junit/framework/TestCase.class'}.issubset(names), 'System JUnit3 optional class closure missing')
    require((SOURCE / 'FlutterHelloWorldControlTest.java').is_file() and (SOURCE / 'AndroidManifest.xml').is_file(),
            'Fixed runner source/manifest missing')
    return jars


def compile_runner(output, work, sdk, android, tools, owner):
    """Compile/link/align this runner once, before the single control App build."""
    command = owner.command
    jars = runner_jars(sdk, android)
    classes = work / 'classes'; classes.mkdir()
    command('runner-javac', [tools['javac'], '-source', '17', '-target', '17', '-classpath', os.pathsep.join(map(str, [android, *jars])),
        '-d', classes, SOURCE / 'FlutterHelloWorldControlTest.java'])
    dex = work / 'dex'; dex.mkdir()
    argv = [tools['d8'], '--min-api', '33', '--lib', android, '--output', dex]
    for jar in jars: argv += ['--classpath', jar]
    command('runner-d8', [*argv, *sorted(classes.rglob('*.class'))])
    unsigned, aligned = work / 'runner-unsigned.apk', work / 'runner-aligned.apk'
    command('runner-aapt2', [tools['aapt2'], 'link', '-I', android, '--manifest', SOURCE / 'AndroidManifest.xml', '-o', unsigned])
    with zipfile.ZipFile(unsigned, 'a') as z: z.write(dex / 'classes.dex', 'classes.dex')
    command('runner-align', [tools['zipalign'], '-f', '4', unsigned, aligned])
    check_badging(command('runner-badging', [tools['aapt'], 'dump', 'badging', aligned]), RUNNER)
    manifest = command('runner-manifest', [tools['aapt'], 'dump', 'xmltree', aligned, 'AndroidManifest.xml'])
    require('android.test.InstrumentationTestRunner' in manifest and len(re.findall(r'E: instrumentation(?: |$)', manifest, re.M)) == 1
            and re.search(r'android:targetPackage[^=]*="' + re.escape(PACKAGE) + r'"', manifest) and
            re.search(r'android:testOnly[^=]*=\(type 0x12\)0xffffffff', manifest), 'Runner instrumentation boundary differs')
    defined = command('runner-dex', [tools['dexdump'], '-f', dex / 'classes.dex'])
    check_runner_dex(defined)
    return aligned


def check_runner_dex(defined):
    blocks = re.split(r'(?m)^Class #[0-9]+\s+-\s*$', defined)[1:]
    root = 'Ldev/aerove/diagnostic/FlutterHelloWorldControlTest'
    seen = set()
    for block in blocks:
        names = re.findall(r"Class descriptor\s*:\s*'([^']+)'", block)
        require(len(names) == 1 and names[0] not in seen, 'Duplicate or missing runner class definition')
        name = names[0]; seen.add(name)
        if name == root + ';' or re.fullmatch(re.escape(root) + r'\$[A-Za-z0-9_$]+;', name):
            continue
        # Official D8 generates this global annotation for desugared debug lambdas.
        require(name == 'Lcom/android/tools/r8/annotations/LambdaMethod;'
            and re.search(r'Access flags\s*:\s*0x3601 \(PUBLIC INTERFACE ABSTRACT SYNTHETIC ANNOTATION\)', block)
            and re.search(r"Superclass\s*:\s*'Ljava/lang/Object;'", block)
            and re.findall(r"#\d+\s*:\s*'(L[^']+;)'", block) == ['Ljava/lang/annotation/Annotation;']
            and re.search(r'Static fields\s*-\s*Instance fields\s*-\s*Direct methods\s*-\s*Virtual methods', block)
            and re.findall(r"name\s*:\s*'([^']+)'", block) == ['holder', 'method', 'proto']
            and re.findall(r"type\s*:\s*'([^']+)'", block) == ['()Ljava/lang/String;'] * 3
            and len(re.findall(r'access\s*:\s*0x0401 \(PUBLIC ABSTRACT\)', block)) == 3
            and re.findall(r'code\s*:\s*(\([^\n]+\)|-)', block) == ['(none)'] * 3
            and re.search(r'source_file_idx\s*:\s*\d+ \(D8\$\$SyntheticClass\)', block),
            'Runner duplicated framework/runtime classes or unexpected compiler synthetic')
    require(root + ';' in seen, 'Missing actual runner test class')


def verify_runner_key(output, tools, owner, key, expected_signer):
    command = owner.command
    require(key.is_file() and not key.resolve().is_relative_to(output.resolve()), 'Original signing key missing or inside upload output')
    command('runner-key-certificate', [tools['keytool'], '-exportcert', '-keystore', key,
        '-alias', 'androiddebugkey', '-storepass', 'android'])
    require(sha(owner.logs / 'runner-key-certificate.out') == expected_signer,
        'Control-invalid: original signer key unavailable or certificate differs; never rebuild or resign App')


def sign_runner(output, aligned, tools, owner, key, expected_signer, key_checked=False):
    command = owner.command
    if not key_checked: verify_runner_key(output, tools, owner, key, expected_signer)
    runner = output / 'runner.apk'
    require(key.is_file(), 'Original Flutter debug keystore is required; no key creation or App rebuild')
    command('runner-sign', [tools['apksigner'], 'sign', '--ks', key, '--ks-key-alias', 'androiddebugkey',
        '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', runner, aligned])
    signer = expected_signer
    require(signing_certificate(command('runner-signature', [tools['apksigner'], 'verify', '--verbose', '--print-certs', runner])) == signer,
            'Control and independent runner signing identities differ')
    return signer


def build_runner(output, work, sdk, android, tools, owner, key, expected_signer):
    # Recovery checks the original key first, then reuses exactly the same compile/sign helpers.
    verify_runner_key(output, tools, owner, key, expected_signer)
    aligned = compile_runner(output, work, sdk, android, tools, owner)
    return sign_runner(output, aligned, tools, owner, key, expected_signer, key_checked=True)


def seal_control(output, checkpoint, signer, app_reused):
    identity = ci_artifacts.identity()
    info = dict(schema=1, status='prepared', kind='official-flutter-hello-control', diagnosticOnly=True, accepted=False,
        productAppCompiled=False, controlCompiled=not app_reused, appReused=app_reused, runnerCompiled=True,
        identity=identity, appProducerIdentity=checkpoint['identity'], runnerIdentity=identity,
        appCheckpointSha256=sha(output / 'CONTROL-APP-BUILD.json'),
        packages=dict(control=PACKAGE, runner=RUNNER, activity=ACTIVITY), signerSha256=signer,
        officialSource=checkpoint['officialSource'], flutterLibrary=checkpoint['flutterLibrary'],
        moduleSha256=sha(Path(__file__)), runnerSourceSha256=sha(SOURCE / 'FlutterHelloWorldControlTest.java'),
        runnerManifestSha256=sha(SOURCE / 'AndroidManifest.xml'),
        adaptation='One frozen official App producer and separately identified current runner; no App resign/rebuild on recovery')
    current = output / 'runner-executor-sources'; current.mkdir()
    for name in ('FlutterHelloWorldControlTest.java', 'AndroidManifest.xml'):
        shutil.copyfile(SOURCE / name, current / name)
    info['files'] = {q.relative_to(output).as_posix(): dict(bytes=q.stat().st_size, sha256=sha(q)) for q in output.rglob('*') if q.is_file()}
    save(output / 'BUILD.json', info)


def verify_app_checkpoint(directory, expected_digest):
    root = Path(directory).resolve(strict=True)
    checkpoint = root / 'CONTROL-APP-BUILD.json'
    require(re.fullmatch('[0-9a-f]{64}', expected_digest or '') is not None and sha(checkpoint) == expected_digest,
            'Original App checkpoint hash is required and must match the preserved artifact')
    value = json.loads(checkpoint.read_text())
    require(value.get('schema') == 1 and value.get('kind') == 'official-flutter-control-app-build'
        and value.get('status') == 'app-built-and-verified' and value.get('accepted') is False
        and value.get('productAppCompiled') is False and value.get('controlCompiled') is True
        and value.get('package') == PACKAGE and value.get('activity') == ACTIVITY and value.get('apkFile') == 'control.apk',
        'Original App checkpoint contract differs')
    identity = value.get('identity', {})
    require(all(re.fullmatch('[0-9a-f]{40}', str(identity.get(k, ''))) for k in ('sourceRevision', 'sourceTree'))
        and re.fullmatch('[1-9][0-9]*', str(identity.get('workflowRun', ''))) is not None
        and type(identity.get('runAttempt')) is int and identity['runAttempt'] > 0
        and type(identity.get('runNumber')) is int and identity['runNumber'] > 0, 'Missing original App producer identity')
    source = value.get('officialSource', {})
    require(source.get('framework') == FRAMEWORK and source.get('engine') == ENGINE and source.get('mainSha256') == MAIN_SHA,
            'Original App official Git source differs')
    require(value.get('android') == dict(compileSdk=37, compileSdkMinor=1, minSdk=33, targetSdk=37, releaseDebugSigner=True, stockRenderer=True)
        and value.get('buildToolchain') == dict(agp='9.4.1', gradle='9.8.0', kotlin='2.4.10', ndk='28.2.13676358')
        and value.get('actualBuildFlags') == ['build', 'apk', '--release', '--target-platform', 'android-x64'],
        'Original release/control build flags differ')
    apk = root / 'control.apk'
    require(not apk.is_symlink() and apk.is_file() and apk.stat().st_size == value['apkBytes'] and sha(apk) == value['apkSha256'],
            'Frozen App bytes changed')
    require(engine_identity(apk) == value['flutterLibrary'], 'Frozen App engine changed')
    sources = root / 'sources'
    require(isinstance(value.get('sourceFiles'), dict) and value['sourceFiles'], 'Missing frozen App source inventory')
    for name, row in value['sourceFiles'].items():
        path = sources / name
        require(not Path(name).is_absolute() and '..' not in Path(name).parts and '\\' not in name and ':' not in name
            and path.resolve(strict=True).is_relative_to(sources.resolve(strict=True)) and not path.is_symlink()
            and path.is_file() and path.stat().st_size == row['bytes'] and sha(path) == row['sha256'], 'Frozen producer source changed')
    require(sha(sources / 'generated-project/lib/main.dart') == MAIN_SHA, 'Frozen official main differs')
    require(re.fullmatch('[0-9a-f]{64}', value.get('signerSha256', '')) is not None, 'Missing original signing identity')
    return value


def runner_only(original, output, expected_checkpoint, signing_key=None):
    """Recover only the independent runner, with an unchanged, fully identified original App."""
    ci_only()
    require(not signed301_requested(), 'Signed301 mode cannot compile or resign a runner')
    original = Path(original).resolve(strict=True)
    checkpoint = verify_app_checkpoint(original, expected_checkpoint)
    output = Path(output).resolve()
    temp = Path(os.environ['RUNNER_TEMP']).resolve(strict=True)
    require(output.is_relative_to(temp) and output != temp and not output.exists()
        and not output.is_relative_to(original), 'New separate RUNNER_TEMP recovery output required')
    key = Path(signing_key) if signing_key is not None else Path.home() / '.android/debug.keystore'
    require(key.is_file(), 'Control-invalid: original debug signing key required; no App rebuild or key generation allowed')
    output.mkdir(parents=True)
    logs = output / 'build-evidence'; logs.mkdir()
    work = temp / ('hello-control-runner-work-' + os.environ['GITHUB_RUN_ID'] + '-' + os.environ['GITHUB_RUN_ATTEMPT'])
    work.mkdir(exist_ok=False)
    owner = OwnedBuildCommands(logs, work, budget=180)
    try:
        sdk = Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ['ANDROID_HOME'])
        android, tools = installed_tools(sdk)
        version = owner.command('javac-version', [tools['javac'], '-version'])
        require('javac 17' in version or 'javac 17' in (logs / 'javac-version.err').read_text(), 'Existing Java17 required')
        # Copy the preserved producer checkpoint byte-for-byte, not with a new execution identity.
        shutil.copyfile(original / 'control.apk', output / 'control.apk')
        shutil.copyfile(original / 'CONTROL-APP-BUILD.json', output / 'CONTROL-APP-BUILD.json')
        for name in checkpoint['sourceFiles']:
            target = output / 'sources' / name; target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(original / 'sources' / name, target)
        cert = signing_certificate(owner.command('reused-control-signature', [tools['apksigner'], 'verify', '--verbose', '--print-certs', output / 'control.apk']))
        require(cert == checkpoint['signerSha256'], 'Original App actual certificate differs from checkpoint')
        signer = build_runner(output, work, sdk, android, tools, owner, key, cert)
        owner.close()
        save(logs / 'commands.json', owner.commands)
        require(sha(output / 'control.apk') == checkpoint['apkSha256']
            and sha(output / 'CONTROL-APP-BUILD.json') == expected_checkpoint, 'Original App producer artifact changed during recovery')
        seal_control(output, checkpoint, signer, True)
    except BaseException as failure:
        try: owner.close()
        except BaseException as cleanup: owner.cleanup_errors.append(type(cleanup).__name__)
        save(logs / 'commands.json', owner.commands)
        save(output / 'RUNNER-RECOVERY-FAILED.json', dict(status='control-invalid', accepted=False,
            failureClass=type(failure).__name__, appProducerIdentity=checkpoint['identity'],
            executorIdentity=ci_artifacts.identity(), appCheckpointSha256=expected_checkpoint,
            appRebuilt=False, appResigned=False, originalKeyRequired=True))
        raise


SIGNED301_OPT_IN = 'AEROVE_FLUTTER_HELLO_SIGNED301'
PRODUCER301 = dict(sourceRevision='179df017429574f02fbe2fd985ff43b7d28553e3',
    sourceTree='1cdbec58893acbe7ddd0574365be07feb9793c52', workflowRun='42287798957', runAttempt=1, runNumber=301)
ARTIFACT301 = dict(id=13538781823, name='Flutter-hello-world-control-attempt-1', size_in_bytes=7088269,
    digest='sha256:72bc45b1455366330e00079acd1b662170186458dd2c5758024a0dfe73620960')
CP301_SHA = '6936781492d5facbe718a5bb481810df22f50e5c9ae3b3aea1b31f3774c0d930'
APP301_SHA = 'fa58ff5b9b35e37f6fbf599c6ed2ee81e1f7d399c123e7a1ea343d4bf11d77ef'
RUNNER301_SHA = '85c45d7dbe5e70488ab3ec81440c9847a1d37115b88e397d60aa5b7fce549f65'
CERT301_SHA = '2ef6e74aea287af187c7440a2e287daa330e5f02f9faa7efcaaea0e1e0e108d9'
JAVA301_SHA = 'e4059656ca9faacde6feb4abd1fb820845bc2d6929ee1748196b968c85bfcaff'
MANIFEST301_SHA = '14631f6eff5209b739cf709e5b87f679e28f9f7fb4996c2c1728dc6d4f5c0412'
UNAVAILABLE301 = {'generated-project/.metadata': {'bytes': 968, 'sha256': '7e9c8e03dc7262a6715cea8e8499c633904bb6b13bd7c2f0593ab4c947a95e85'}, 'generated-project/.gitignore': {'bytes': 746, 'sha256': '441eaa1de5b3db9fae056e7503567e9f8dd94173e1389c60f76956abf729015a'}, 'generated-project/.idea/modules.xml': {'bytes': 452, 'sha256': 'b78d7b5a36fc08ea04a202ed9fd5ef550e21935f1efc7f2c4be1c743ed0b236f'}, 'generated-project/.idea/workspace.xml': {'bytes': 1517, 'sha256': '1f4bdf93fa0c89b32a2f788bcbd55363a23cd0db9b4c05a905247ac270c7061b'}, 'generated-project/android/.gitignore': {'bytes': 253, 'sha256': 'b027c9de1011765c015e5819bf857c0d75bfcd0886fb01721c091159012e50f1'}, 'generated-project/.idea/libraries/KotlinJavaRuntime.xml': {'bytes': 599, 'sha256': '82dff6dd065164518cd13ff2b677527601ef22bd9fd7cc4e607c38a2412e6cf9'}, 'generated-project/.idea/libraries/Dart_SDK.xml': {'bytes': 1392, 'sha256': 'c2efcca403c3cf14849da01b9197ce985981021469772e46f1b7637305b57e7e'}, 'generated-project/.idea/runConfigurations/main_dart.xml': {'bytes': 271, 'sha256': '0b2227c29b468c49f41b8b3138433e0de33d83dbe13b4002c069800e1d9b9322'}}


def signed301_requested():
    if SIGNED301_OPT_IN not in os.environ: return False
    require(os.environ[SIGNED301_OPT_IN] == '1' and enabled(), 'Signed301 adoption requires the fixed control request')
    return True


def fixed301_runner_certificate(report, apk, producer):
    """API33 V3 verification only for the already-built immutable301 runner; never a product parser."""
    require(producer == PRODUCER301 and Path(apk).stat().st_size == 41363 and sha(apk) == RUNNER301_SHA,
            'V3 reader is restricted to the exact signed301 runner/producer')
    lines = report.splitlines()
    expected = ('Verifies', 'Verified using v1 scheme (JAR signing): false',
        'Verified using v2 scheme (APK Signature Scheme v2): false',
        'Verified using v3 scheme (APK Signature Scheme v3): true',
        'Verified using v3.1 scheme (APK Signature Scheme v3.1): false',
        'Verified using v3.2 scheme (APK Signature Scheme v3.2): false',
        'Verified using v4 scheme (APK Signature Scheme v4): false', 'Number of signers: 1')
    require(all(lines.count(line) == 1 for line in expected), 'Unexpected fixed301 verification scheme/count report')
    require(len([line for line in lines if line.startswith('Verified using ')]) == 6
        and len([line for line in lines if line.startswith('Number of signers:')]) == 1, 'Ambiguous fixed301 verification markers')
    digests = [line for line in lines if 'Signer' in line and 'certificate SHA-256 digest:' in line]
    require(digests == ['V3.0 Signer: certificate SHA-256 digest: ' + CERT301_SHA], 'Fixed301 unique V3.0 certificate differs')
    return CERT301_SHA


def source_verification301(root, checkpoint):
    """Only original301's exact eight absent metadata records are unavailable; never reconstruct them."""
    sources=Path(root)/'sources'; declared=checkpoint.get('sourceFiles')
    require(isinstance(declared,dict) and len(declared)==42 and set(UNAVAILABLE301).issubset(declared), 'Original301 declared source set differs')
    verified=[];unavailable=[]
    for name,row in sorted(declared.items()):
        path=sources/name
        require(not Path(name).is_absolute() and '..' not in Path(name).parts and ':' not in name and '\\' not in name,
                'Unsafe original301 source path')
        if name in UNAVAILABLE301:
            require(row==UNAVAILABLE301[name] and not path.exists() and not path.is_symlink(), 'Original301 unavailable metadata record changed or was fabricated')
            unavailable.append(dict(path=name,**row,status='unavailable-in-original301-artifact'))
            continue
        require(path.is_file() and not path.is_symlink() and path.resolve(strict=True).is_relative_to(sources.resolve(strict=True))
            and path.stat().st_size==row['bytes'] and sha(path)==row['sha256'], 'Required original301 source missing or changed')
        verified.append(dict(path=name,**row))
    required={'FLUTTER-LICENSE','FlutterHelloWorldControlTest.java','RunnerManifest.xml','generated-project/lib/main.dart',
        'generated-project/pubspec.yaml','generated-project/android/settings.gradle.kts',
        'generated-project/android/app/build.gradle.kts','generated-project/android/app/src/main/AndroidManifest.xml',
        'generated-project/android/gradle/wrapper/gradle-wrapper.properties'}
    require(required.issubset({row['path'] for row in verified}) and
        any(row['path'].endswith('/MainActivity.kt') for row in verified) and len(verified)==34 and len(unavailable)==8,
        'Original301 mandatory source or verification counts differ')
    return dict(declared=42,verified=34,unavailable=8,verifiedFiles=verified,unavailableFiles=unavailable,
        sourceArchiveComplete=False,scope='fixed301-onscreen-control-diagnostic-only',
        flutterProjectMetadata='generated-project/.metadata was not retained; build-input status is not inferred')


def check301_files(root):
    root = Path(root)
    require(sha(root / 'CONTROL-APP-BUILD.json') == CP301_SHA, 'Original301 checkpoint bytes differ')
    checkpoint = json.loads((root / 'CONTROL-APP-BUILD.json').read_text())
    require(checkpoint.get('schema')==1 and checkpoint.get('kind')=='official-flutter-control-app-build'
        and checkpoint.get('status')=='app-built-and-verified' and checkpoint.get('accepted') is False
        and checkpoint.get('productAppCompiled') is False and checkpoint.get('controlCompiled') is True
        and checkpoint.get('package')==PACKAGE and checkpoint.get('activity')==ACTIVITY and checkpoint.get('apkFile')=='control.apk'
        and checkpoint.get('officialSource',{}).get('framework')==FRAMEWORK and checkpoint.get('officialSource',{}).get('engine')==ENGINE
        and checkpoint.get('officialSource',{}).get('mainSha256')==MAIN_SHA, 'Original301 exact checkpoint contract differs')
    source_verification301(root,checkpoint)
    require(engine_identity(root/'control.apk')==checkpoint['flutterLibrary'], 'Original301 engine differs')
    require(checkpoint['identity'] == PRODUCER301 and checkpoint['signerSha256'] == CERT301_SHA
        and (root / 'control.apk').stat().st_size == 15477599 and sha(root / 'control.apk') == APP301_SHA
        and (root / 'runner.apk').stat().st_size == 41363 and sha(root / 'runner.apk') == RUNNER301_SHA,
        'Original301 App/runner/producer binding differs')
    require(sha(root / 'sources/FlutterHelloWorldControlTest.java') == JAVA301_SHA
        and sha(root / 'sources/RunnerManifest.xml') == MANIFEST301_SHA
        and sha(SOURCE / 'FlutterHelloWorldControlTest.java') == JAVA301_SHA
        and sha(SOURCE / 'AndroidManifest.xml') == MANIFEST301_SHA, 'Original301 exact runner source/manifest differs')
    with zipfile.ZipFile(root / 'runner.apk') as z:
        require(len(z.namelist()) == len(set(z.namelist())) and z.read('classes.dex').startswith(b'dex\n')
            and not any(n.startswith(('lib/', 'assets/')) for n in z.namelist()), 'Original301 runner payload boundary differs')
    return checkpoint


def check301_api(run, artifact):
    require(run.get('id') == 42287798957 and run.get('run_attempt') == 1 and run.get('run_number') == 301
        and run.get('head_sha') == PRODUCER301['sourceRevision'] and run.get('head_branch') == BRANCH
        and run.get('path') == '.github/workflows/aerove-lite.yaml' and run.get('status') == 'completed'
        and run.get('conclusion') == 'failure' and run.get('repository', {}).get('full_name') == 'IvanSolis1989/Aerove',
        'Original301 failed run/source identity differs')
    require(all(artifact.get(k) == v for k,v in ARTIFACT301.items()) and artifact.get('expired') is False
        and artifact.get('workflow_run') == dict(id=42287798957, repository_id=1429390079,
            head_repository_id=1429390079, head_branch=BRANCH, head_sha=PRODUCER301['sourceRevision']),
        'Original301 artifact API identity differs')


def adopt_signed301(output):
    ci_only(); require(signed301_requested(), 'Explicit signed301 adoption required')
    temp = Path(os.environ['RUNNER_TEMP']).resolve(strict=True); output = Path(output).resolve()
    require(output.is_relative_to(temp) and output != temp and not output.exists(), 'New controlled adoption directory required')
    output.mkdir(parents=True)
    logs = output / 'adoption-evidence'; logs.mkdir()
    work = temp / ('signed301-adopt-work-' + os.environ['GITHUB_RUN_ID'] + '-' + os.environ['GITHUB_RUN_ATTEMPT'])
    work.mkdir(exist_ok=False)
    owner = OwnedBuildCommands(logs, work, budget=360)
    try:
        # Reuse repository read/download helpers inside the bounded owned command session.
        code = 'import json,sys;from lite_release import _gh_json;json.dump(_gh_json(sys.argv[1]),sys.stdout)'
        base = 'repos/IvanSolis1989/Aerove/actions/'
        run = json.loads(owner.command('original301-run-api', [sys.executable, '-c', code, base+'runs/42287798957'], cwd=Path(__file__).parent, timeout=30))
        row = json.loads(owner.command('original301-artifact-api', [sys.executable, '-c', code, base+'artifacts/13538781823'], cwd=Path(__file__).parent, timeout=30))
        check301_api(run, row)
        save(logs / 'ORIGINAL-RUN.json', run); save(logs / 'ORIGINAL-ARTIFACT.json', row)
        archive = output / 'ORIGINAL301.zip'
        code = 'import sys;from pathlib import Path;from lite_release import _gh_binary;_gh_binary(sys.argv[1],Path(sys.argv[2]))'
        owner.command('original301-download', [sys.executable, '-c', code, base+'artifacts/13538781823/zip', archive], cwd=Path(__file__).parent, timeout=180)
        require(archive.stat().st_size == ARTIFACT301['size_in_bytes'] and 'sha256:'+sha(archive) == ARTIFACT301['digest'], 'Original301 ZIP size/hash differs')
        raw = output / 'original301'; raw.mkdir()
        with zipfile.ZipFile(archive) as z:
            entries=z.infolist()
            require(entries and len({e.filename for e in entries}) == len(entries) and sum(e.file_size for e in entries) <= 128*1024*1024,
                    'Unsafe original301 ZIP inventory')
            import stat
            for item in entries:
                parts=item.filename.rstrip('/').split('/'); mode=stat.S_IFMT(item.external_attr>>16)
                require(item.filename and not item.filename.startswith('/') and '\\' not in item.filename
                    and all(p not in ('','.','..') and ':' not in p for p in parts)
                    and mode in (0,stat.S_IFREG,stat.S_IFDIR), 'Unsafe original301 ZIP path/type')
            require(z.testzip() is None, 'Original301 ZIP CRC failed')
            z.extractall(raw)
        import lite_release as release
        release.product_source.bind_archive(archive, raw, row)
        original = raw / 'flutter-control-runtime'
        checkpoint = check301_files(original)
        for name in ('control.apk','runner.apk','CONTROL-APP-BUILD.json'): shutil.copyfile(original/name,output/name)
        for name in checkpoint['sourceFiles']:
            if name in UNAVAILABLE301: continue
            dest=output/'sources'/name;dest.parent.mkdir(parents=True,exist_ok=True)
            shutil.copyfile(original/'sources'/name,dest)
        sdk=Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ['ANDROID_HOME'])
        tools={name:sdk/'build-tools/37.0.0'/name for name in ('aapt','apksigner')}
        require(all(t.is_file() for t in tools.values()), 'Existing verification-only SDK37 tools missing')
        app_report=owner.command('adopt-app-signature',[tools['apksigner'],'verify','--verbose','--print-certs',output/'control.apk'])
        require(signing_certificate(app_report)==CERT301_SHA,'Original301 App strict V2 certificate differs')
        runner_report=owner.command('adopt-runner-signature',[tools['apksigner'],'verify','--verbose','--print-certs',
            '--min-sdk-version','33','--max-sdk-version','33',output/'runner.apk'])
        fixed301_runner_certificate(runner_report,output/'runner.apk',PRODUCER301)
        for name,package,activity in (('control.apk',PACKAGE,ACTIVITY),('runner.apk',RUNNER,None)):
            check_badging(owner.command('adopt-'+name+'-badging',[tools['aapt'],'dump','badging',output/name]),package,activity)
        manifest=owner.command('adopt-runner-manifest',[tools['aapt'],'dump','xmltree',output/'runner.apk','AndroidManifest.xml'])
        require('android.test.InstrumentationTestRunner' in manifest and re.search(r'android:targetPackage[^=]*="'+re.escape(PACKAGE)+'"',manifest)
            and re.search(r'android:testOnly[^=]*=\(type 0x12\)0xffffffff',manifest),'Original301 runner manifest differs')
        owner.close()
        identity=ci_artifacts.identity()
        info=dict(schema=1,status='prepared',kind='official-flutter-hello-control',diagnosticOnly=True,accepted=False,
            visualApproved=False,performanceAccepted=False,releaseAccepted=False,sourceArchiveComplete=False,
            sourceVerification=source_verification301(output,checkpoint),
            productAppCompiled=False,controlCompiled=False,appReused=True,runnerCompiled=False,runnerReused=True,signed301Reuse=True,
            identity=identity,executorIdentity=identity,appProducerIdentity=PRODUCER301,runnerIdentity=PRODUCER301,
            appCheckpointSha256=CP301_SHA,originalArtifact=ARTIFACT301,packages=dict(control=PACKAGE,runner=RUNNER,activity=ACTIVITY),
            signerSha256=CERT301_SHA,officialSource=checkpoint['officialSource'],flutterLibrary=checkpoint['flutterLibrary'],
            moduleSha256=sha(Path(__file__)),runnerSourceSha256=JAVA301_SHA,runnerManifestSha256=MANIFEST301_SHA,
            actualVerificationTargetApi=33,runnerSignatureScheme='fixed-original301-v3.0',appResigned=False,runnerResigned=False)
        info['files']={q.relative_to(output).as_posix():dict(bytes=q.stat().st_size,sha256=sha(q)) for q in output.rglob('*') if q.is_file()}
        save(output/'BUILD.json',info)
    except BaseException as failure:
        cleanup_error=None
        try:owner.close()
        except BaseException as cleanup:cleanup_error=type(cleanup).__name__
        save(output/'ADOPT-FAILED.json',dict(status='control-invalid',accepted=False,failureClass=type(failure).__name__,
            appRebuilt=False,runnerRebuilt=False,appResigned=False,runnerResigned=False,cleanupErrorClass=cleanup_error))
        raise


def signed301_runtime(root, info):
    require(signed301_requested(), 'Signed301 runtime only under fixed adoption opt-in')
    identity=ci_artifacts.identity()
    require(info.get('identity')==identity and info.get('executorIdentity')==identity and info.get('moduleSha256')==sha(Path(__file__))
        and info.get('sourceArchiveComplete') is False and info.get('visualApproved') is False
        and info.get('performanceAccepted') is False and info.get('releaseAccepted') is False
        and info.get('kind')=='official-flutter-hello-control' and info.get('status')=='prepared' and info.get('accepted') is False
        and info.get('controlCompiled') is False and info.get('runnerCompiled') is False and info.get('runnerReused') is True
        and info.get('appReused') is True and info.get('productAppCompiled') is False and info.get('appProducerIdentity')==PRODUCER301
        and info.get('runnerIdentity')==PRODUCER301 and info.get('originalArtifact')==ARTIFACT301
        and info.get('appResigned') is False and info.get('runnerResigned') is False
        and info.get('actualVerificationTargetApi')==33 and info.get('runnerSignatureScheme')=='fixed-original301-v3.0'
        and info.get('packages')==dict(control=PACKAGE,runner=RUNNER,activity=ACTIVITY), 'Fixed301 producer/executor seal differs')
    files=info['files']
    require(isinstance(files,dict) and files and {q.relative_to(root).as_posix() for q in root.rglob('*') if q.is_file()}==set(files)|{'BUILD.json'},'Adopted301 inventory differs')
    for name,row in files.items():
        path=root/name
        require(not Path(name).is_absolute() and '..' not in Path(name).parts and ':' not in name and '\\' not in name
            and path.is_file() and not path.is_symlink() and path.stat().st_size==row['bytes'] and sha(path)==row['sha256'],'Adopted301 payload changed')
    checkpoint=check301_files(root)
    source_verification=source_verification301(root,checkpoint)
    require(info.get('sourceVerification')==source_verification, 'Fixed301 partial-source verification record differs')
    require(info.get('appCheckpointSha256')==CP301_SHA and info.get('officialSource')==checkpoint['officialSource']
        and info.get('flutterLibrary')==checkpoint['flutterLibrary'] and info.get('signerSha256')==CERT301_SHA
        and info.get('runnerSourceSha256')==JAVA301_SHA and info.get('runnerManifestSha256')==MANIFEST301_SHA,'Fixed301 identity fields changed')
    require(signing_certificate((root/'adoption-evidence/adopt-app-signature.out').read_text())==CERT301_SHA,'Adopted App V2 report changed')
    fixed301_runner_certificate((root/'adoption-evidence/adopt-runner-signature.out').read_text(),root/'runner.apk',PRODUCER301)
    return dict(identity=identity,apks={'control.apk':APP301_SHA,'runner.apk':RUNNER301_SHA},controlRuntime=True,controlDiagnostic=True,
        appProducerIdentity=PRODUCER301,runnerIdentity=PRODUCER301,executorIdentity=identity,controlCompiled=False,appReused=True,
        runnerCompiled=False,runnerReused=True,signed301Reuse=True,controlBuildSha256=sha(root/'BUILD.json'),officialSource=checkpoint['officialSource'],
        diagnosticOnly=True,accepted=False,visualApproved=False,performanceAccepted=False,releaseAccepted=False,
        sourceArchiveComplete=False,sourceVerification=source_verification,
        productAppCompiled=False,requestedGraphicsRoute='guest-angle-vulkan-lavapipe')


def runtime(directory):
    require(enabled(), 'Fixed control runtime opt-in required')
    root = Path(directory)
    require(root.is_dir() and not root.is_symlink() and not any(p.is_symlink() for p in root.rglob('*')), 'Missing or symlinked fixed control directory')
    info = json.loads((root / 'BUILD.json').read_text())
    if info.get('signed301Reuse') is True: return signed301_runtime(root, info)
    require(not signed301_requested(), 'Signed301 opt-in cannot admit a newly built or different runtime')
    require(info.get('schema') == 1 and info.get('status') == 'prepared' and info.get('kind') == 'official-flutter-hello-control'
        and info.get('identity') == ci_artifacts.identity() and info.get('accepted') is False
        and info.get('productAppCompiled') is False and info.get('runnerCompiled') is True
        and (info.get('controlCompiled') is True and info.get('appReused') is False
             or info.get('controlCompiled') is False and info.get('appReused') is True)
        and info.get('moduleSha256') == sha(Path(__file__)) and info.get('runnerSourceSha256') == sha(SOURCE / 'FlutterHelloWorldControlTest.java')
        and info.get('runnerManifestSha256') == sha(SOURCE / 'AndroidManifest.xml'), 'Control build/source identity differs')
    require(info.get('packages') == dict(control=PACKAGE, runner=RUNNER, activity=ACTIVITY)
        and info.get('officialSource', {}).get('mainSha256') == MAIN_SHA
        and info.get('officialSource', {}).get('framework') == FRAMEWORK and info.get('officialSource', {}).get('engine') == ENGINE
        and sha(root / 'sources/generated-project/lib/main.dart') == MAIN_SHA, 'Control source/package binding differs')
    files = info['files']
    require(isinstance(files, dict) and files and {p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file()} == set(files) | {'BUILD.json'},
            'Control runtime inventory differs')
    for name, row in files.items():
        path = root / name
        require(not Path(name).is_absolute() and '..' not in Path(name).parts and '\\' not in name
            and not path.is_symlink() and path.is_file() and path.stat().st_size == row['bytes'] and sha(path) == row['sha256'], 'Control seal changed')
    checkpoint = verify_app_checkpoint(root, info['appCheckpointSha256'])
    require(checkpoint['identity'] == info.get('appProducerIdentity') and info.get('runnerIdentity') == info['identity'], 'App producer or runner executor changed')
    if not info['appReused']: require(checkpoint['identity'] == info['identity'], 'New App producer identity differs')
    require(engine_identity(root / 'control.apk') == info['flutterLibrary'], 'Control engine proof changed')
    return dict(identity=info['identity'], apks={n: sha(root / n) for n in ('control.apk', 'runner.apk')},
        controlRuntime=True, controlDiagnostic=True, appProducerIdentity=info['appProducerIdentity'], runnerIdentity=info['runnerIdentity'], controlCompiled=info['controlCompiled'], appReused=info['appReused'], controlBuildSha256=sha(root / 'BUILD.json'), officialSource=info['officialSource'],
        diagnosticOnly=True, accepted=False, productAppCompiled=False, requestedGraphicsRoute='guest-angle-vulkan-lavapipe')


class Control(Producer):
    def ordinary_launch(self, bound):
        """Compare the same installed, stateless control outside instrumentation."""
        self.adb_command('ordinary-stop-before', 'shell', 'am', 'force-stop', PACKAGE)
        self.adb_command('ordinary-launch', 'shell', 'am', 'start', '-W', '-n', PACKAGE + '/' + ACTIVITY, timeout=35)
        time.sleep(10)
        pid = self.adb_command('ordinary-pid', 'shell', 'pidof', PACKAGE).decode().strip()
        require(re.fullmatch('[1-9][0-9]*', pid), 'Ordinary control requires one live app process')
        image = self.adb_command('ordinary-display', 'exec-out', 'screencap', '-p', timeout=20)
        require(image[:8] == b'\x89PNG\r\n\x1a\n' and len(image) >= 33 and image[12:16] == b'IHDR'
            and int.from_bytes(image[16:20], 'big') == 1080 and int.from_bytes(image[20:24], 'big') == 2400,
            'Ordinary control original display PNG differs')
        output = self.args.output / 'ordinary-display.png'
        require(not output.exists(), 'Ordinary display output must be new')
        output.write_bytes(image)
        self.adb_command('ordinary-window', 'shell', 'dumpsys', '-t', '10', 'window', 'windows', timeout=15)
        self.adb_command('ordinary-surfaceflinger', 'shell', 'dumpsys', '-t', '10', 'SurfaceFlinger', timeout=15)
        log = self.adb_command('ordinary-renderer-log', 'logcat', '-d', '-v', 'threadtime', timeout=45).decode(errors='replace')
        evidence = renderer(log, pid)
        require(any('Using the Impeller rendering backend (OpenGLES)' in line for line in evidence['lines']),
            'Ordinary control stock Impeller GLES not observed')
        require(self.adb_command('ordinary-pid-after', 'shell', 'pidof', PACKAGE).decode().strip() == pid,
            'Ordinary control process changed during observation')
        installed = self.installed_hash('ordinary-after', PACKAGE, bound['apks']['control.apk'])
        self.receipt['ordinaryLaunch'] = dict(mode='am-start-without-instrumentation', pid=pid,
            display=dict(file=output.name, bytes=len(image), sha256=sha(output), width=1080, height=2400),
            appRenderer=evidence, installedAfter=installed, accepted=False, atomicSameFrame=False)

    def run(self):
        ci_only()
        bound = runtime(self.args.runtime_directory)
        info = json.loads((self.args.runtime_directory / 'BUILD.json').read_text())
        remote = None; result = 1
        self.receipt.update(producer='flutter_hello_control.py', diagnosticOnly=True, accepted=False,
            productAppCompiled=False, controlRuntime=bound, visualApproved=False, performanceAccepted=False, releaseAccepted=False)
        if info.get('signed301Reuse') is True:
            self.receipt.update(sourceArchiveComplete=False, sourceVerification=info['sourceVerification'])
        for sig in (signal.SIGINT, signal.SIGTERM): self.previous_signals[sig] = signal.signal(sig, self.interrupt)
        try:
            from emulator_current import validate_renderer_proof
            proof = validate_renderer_proof(Path(os.environ['AEROVE_CURRENT_RENDERER_PROOF']))
            require(proof.get('owner') == 'emulator_current.py' and proof.get('phase') == 'flutter-control'
                and all(proof.get(k) == v for k, v in bound.items()) and proof.get('gpuBackend') == 'lavapipe'
                and proof.get('deviceSerial') == self.args.serial == os.environ['ANDROID_SERIAL'] == 'emulator-5554', 'Control owner/route proof differs')
            for package in (PACKAGE, RUNNER):
                require(b'package:' not in self.adb_command('preexisting-' + package, 'shell', 'pm', 'path', package, check=False), 'Fresh owned emulator required')
            self.owner_verified = True
            for package, name in ((PACKAGE, 'control.apk'), (RUNNER, 'runner.apk')):
                path = self.args.runtime_directory / name
                verification = [self.args.apksigner, 'verify', '--verbose', '--print-certs']
                if info.get('signed301Reuse') is True and name == 'runner.apk':
                    verification += ['--min-sdk-version', '33', '--max-sdk-version', '33']
                report = self.command('signature-' + name, [*verification, path]).decode()
                cert = (fixed301_runner_certificate(report, path, info['runnerIdentity'])
                        if info.get('signed301Reuse') is True and name == 'runner.apk' else signing_certificate(report))
                require(cert == info['signerSha256'], 'Control signer changed')
                self.installed.append(package)
                self.adb_command('install-' + package, 'install', *(['-t'] if package == RUNNER else []), '--no-streaming', str(path), timeout=120)
                self.installed_hash('installed-' + package, package, bound['apks'][name])
            token = 'hello-' + uuid4().hex
            remote = '/sdcard/Android/data/' + PACKAGE + '/files/hello-control/' + token
            argv = ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', SELECTOR, '-e', 'qaToken', token,
                    '-e', 'appSha256', bound['apks']['control.apk'], '-e', 'runnerSha256', bound['apks']['runner.apk'],
                    '-e', 'signerSha256', info['signerSha256'], RUNNER + '/android.test.InstrumentationTestRunner']
            self.adb_command('control-instrument', *argv, timeout=170)
            self.adb_command('control-pull', 'pull', remote, str(self.args.output / 'capture'), timeout=45)
            capture = json.loads((self.args.output / 'capture/receipt.json').read_text())
            self.receipt.update(capture=capture, captureSha256=sha(self.args.output / 'capture/receipt.json'))
            records = parse_log(self.args.output / 'control-instrument.out', expected_reporter='InstrumentationTestRunner')
            require(capture['appIdentity']['sha256'] == bound['apks']['control.apk']
                and capture['runnerIdentity']['sha256'] == bound['apks']['runner.apk'], 'Captured installed identities differ')
            self.adb_command('instrumented-window', 'shell', 'dumpsys', '-t', '10', 'window', 'windows', timeout=15)
            self.adb_command('instrumented-surfaceflinger', 'shell', 'dumpsys', '-t', '10', 'SurfaceFlinger', timeout=15)
            # Retain both independent modes even when the earlier image-binding check fails.
            self.ordinary_launch(bound)
            for key in ('surface', 'window', 'display'):
                image = capture[key]['image'] if key != 'display' else capture[key]
                require(image['file'] == key + '.png' and sha(self.args.output / 'capture' / image['file']) == image['sha256']
                    and image['width'] == 1080 and image['height'] == 2400, 'Control original PNG binding differs')
            log = self.adb_command('app-renderer-log', 'logcat', '-d', '-v', 'threadtime', timeout=45).decode(errors='replace')
            evidence = renderer(log, capture['pid'])
            require(any('Using the Impeller rendering backend (OpenGLES)' in s for s in evidence['lines']), 'Stock Impeller GLES not observed')
            require(not any(re.match(r'\S+\s+\S+\s+' + str(capture['pid']) + r'\s', line) and
                any(term in line for term in ('FATAL EXCEPTION', 'Fatal signal', '[ERROR:flutter/runtime/', 'Unhandled Exception'))
                for line in log.splitlines()), 'Actual control process reported a fatal error')
            self.receipt['appRenderer'] = evidence
            self.receipt['installedAfter'] = self.installed_hash('control-after', PACKAGE, bound['apks']['control.apk'])
            self.adb_command('post-run-window', 'shell', 'dumpsys', '-t', '10', 'window', 'windows', timeout=15)
            self.adb_command('post-run-surfaceflinger', 'shell', 'dumpsys', '-t', '10', 'SurfaceFlinger', timeout=15)
            require(len(records) == 1 and records[0]['className'] + '#' + records[0]['testName'] == SELECTOR
                and records[0]['status'] == 'passed' and capture.get('qaToken') == token and capture.get('captureCompleted') is True
                and capture.get('resourceCleanup') == 'complete' and capture.get('accepted') is False, 'Actual control observation failed')
            self.receipt.update(status='observed-pending-pixel-review'); result = 0
        except BaseException as failure:
            self.receipt.update(status='cancelled' if self.cancelled else 'failed', failureClass=type(failure).__name__)
            raise
        finally:
            if self.owner_verified:
                if remote and not (self.args.output / 'capture/receipt.json').exists():
                    try: self.adb_command('failure-pull', 'pull', remote, str(self.args.output / 'capture'), timeout=45, check=False, cancelling=True)
                    except Exception as failure: self.receipt['pullFailureClass'] = type(failure).__name__
                try: self.adb_command('final-logcat', 'logcat', '-d', '-v', 'threadtime', timeout=45, check=False, cancelling=True)
                except Exception as failure: self.receipt['logFailureClass'] = type(failure).__name__
            for package in reversed(self.installed):
                for label, command, timeout in (('stop', ['shell', 'am', 'force-stop', package], 15), ('uninstall', ['uninstall', package], 45)):
                    try: self.adb_command('cleanup-' + label + '-' + package, *command, timeout=timeout, check=True, cancelling=True)
                    except Exception as failure: self.receipt.setdefault('cleanupErrors', []).append(type(failure).__name__)
            self.receipt['result'] = 1 if self.cancelled or self.receipt.get('cleanupErrors') else result
            save(self.args.output / 'CONTROL.json', self.receipt)
            for sig, handler in self.previous_signals.items(): signal.signal(sig, handler)
        return self.receipt['result']


def main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest='phase', required=True)
    build = commands.add_parser('prepare'); build.add_argument('--output', type=Path, required=True)
    restore = commands.add_parser('runner-only')
    restore.add_argument('--runtime-directory', type=Path, required=True)
    restore.add_argument('--output', type=Path, required=True)
    restore.add_argument('--app-checkpoint-sha256', required=True)
    restore.add_argument('--signing-key', type=Path)
    adopt = commands.add_parser('adopt-signed301'); adopt.add_argument('--output', type=Path, required=True)
    run = commands.add_parser('run')
    for name in ('runtime-directory', 'adb', 'apksigner', 'output'): run.add_argument('--' + name, type=Path, required=True)
    run.add_argument('--serial', required=True)
    args = parser.parse_args(); idle()
    if args.phase == 'adopt-signed301': adopt_signed301(args.output); return 0
    if args.phase == 'prepare': prepare(args.output); return 0
    if args.phase == 'runner-only':
        runner_only(args.runtime_directory, args.output, args.app_checkpoint_sha256, args.signing_key); return 0
    ci_only(); args.output.mkdir(parents=True, exist_ok=False)
    require(not args.output.resolve().is_relative_to(args.runtime_directory.resolve()), 'Observation must not modify sealed runtime')
    return Control(args).run()


if __name__ == '__main__':
    raise SystemExit(main())
