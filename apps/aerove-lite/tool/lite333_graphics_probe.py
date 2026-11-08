"""Fixed run333 baseline or single-variable host-default graphics diagnosis; never acceptance."""
import argparse
import json
import os
from pathlib import Path
import re
import sys

import lite236_pixel_diagnostic as base
from lite236_pixel_diagnostic import require, sha

APP = dict(sourceRevision='70e9c233cae7e05a00062c66cc79592384ada7ed',
           sourceTree='49bd175149f7fdaa938a21bed094754a0af1cf30',
           workflowRun='42497494765', runAttempt=1, runNumber=333)
ARCHIVE = dict(id=13624668964, name='AEROVE-Lite-runtime-contract-attempt-1',
               size_in_bytes=59853302,
               digest='sha256:76c069a4d8617c6f37cd1a54756b23674694aab8a6962c8c2c6b3f619c11fa23')
APK_SHA = 'd76e7e0d62b0a8ecd178c834057519aeffe3829365464ac18687fd05f87c22e0'
INFO_SHA = '7ae5611aa50a2d906622717b0f7b1fa387b48a909db243570c5269f258b40ccb'
MANIFEST_SHA = 'dd89020faf348f11ff39133a21fd79c7e6028540216c0348bb66b9c2608c574a'
SELECTOR = 'dev.aerove.lite.GraphicsBaselineDiagnosticTest#testCaptureCurrentCompositor'


def guard_execution():
    require(os.environ.get('GITHUB_REPOSITORY') == 'IvanSolis1989/Aerove'
            and os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch'
            and os.environ.get('GITHUB_REF') == 'refs/heads/dot/cloud-j316-checkpoint-20261108',
            'Original333 requires explicit isolated diagnostic workflow')
    require(not any(value for key, value in os.environ.items()
                    if key.startswith(('AEROVE_ORIGINAL', 'AEROVE_LITE236', 'AEROVE_LITE257',
                                       'AEROVE_LITE265', 'AEROVE_LITE290', 'AEROVE_GFXSTREAM',
                                       'AEROVE_EMULATOR_SIGILL', 'AEROVE_QA79', 'AEROVE_CURRENT_RENDERER'))),
            'Original333 cannot mix historical runtime or graphics opt-ins')


def validate_api(api):
    require(all(api.get(k) == v for k, v in ARCHIVE.items()) and api.get('expired') is False
            and api.get('url') == 'https://api.github.com/repos/IvanSolis1989/Aerove/actions/artifacts/' + str(ARCHIVE['id'])
            and api.get('workflow_run', {}).get('id') == int(APP['workflowRun'])
            and api['workflow_run'].get('head_sha') == APP['sourceRevision']
            and api['workflow_run'].get('repository_id') == 1429390079
            and api['workflow_run'].get('head_repository_id') == 1429390079,
            'Fixed original333 artifact API identity differs')


def original(args):
    api = json.loads(args.artifact_api.read_text(encoding='utf-8-sig'))
    validate_api(api)
    sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
    from tooling.product_source import bind_archive
    from ci_artifacts import verify
    payload = bind_archive(args.archive, args.runtime_directory, api)
    verify(args.runtime_directory, APP, runtime=True)
    require(payload['BUILD_INFO.json']['sha256'] == INFO_SHA
            and payload['apks/app-release.apk']['sha256'] == APK_SHA
            and sha(args.runtime_directory / 'source-manifest.json') == MANIFEST_SHA,
            'Original333 APK, BUILD_INFO or seal bytes differ')
    info = json.loads((args.runtime_directory / 'BUILD_INFO.json').read_text())
    expected = dict(product='AEROVE Lite', application_id=base.PACKAGE,
                    version='0.2.0-beta.7', version_code=39, min_sdk=33,
                    signer_sha256=base.SIGNER, source=APP['sourceRevision'], tree=APP['sourceTree'],
                    abi='x86_64', workflow='https://github.com/IvanSolis1989/Aerove/actions/runs/' + APP['workflowRun'],
                    run_number=333, run_attempt=1, apk_sha256=APK_SHA,
                    launcher_activity=base.PACKAGE + '.FlutterMainActivity')
    require(all(info.get(k) == v for k, v in expected.items()), 'Original333 package metadata differs')
    return dict(artifactId=ARCHIVE['id'], artifactApiSha256=sha(args.artifact_api),
                actualZipSha256=ARCHIVE['digest'][7:], originalBuildInfoSha256=INFO_SHA,
                originalApkSha256=APK_SHA, originalSealSha256=MANIFEST_SHA)


import socket
import subprocess
import time
from uuid import uuid4


def graphics_command(sdk, avd, mode):
    require(mode in ('baseline', 'host-default'), 'Unknown independent graphics experiment')
    command = [str(sdk / 'emulator/emulator'), '-port', '5554', '-avd', avd,
               '-no-window', '-gpu', 'swangle', '-no-snapshot', '-noaudio', '-no-boot-anim']
    if mode == 'baseline':
        command += ['-feature', 'GuestAngle,Vulkan']
    return command


def current_focus_lines(output):
    # Android 13 WMS full dump includes DisplayContent.dump; `window windows` does not.
    return re.findall(r'(?m)^\s*mCurrentFocus=.*$', output)


class Diagnostic(base.Diagnostic):
    def __init__(self, args):
        super().__init__(args)
        self.app, self.apk_sha, self.info_sha = APP, APK_SHA, INFO_SHA
        self.session = uuid4().hex
        self.receipt.update(producer='lite333_graphics_probe.py', originalAppIdentity=APP,
            experiment=args.graphics_mode, promotionAccepted=False, appCompiled=False, harnessCompiled=True, appOrHarnessCompiled=True,
            executorScriptSha256=sha(__file__), baseScriptSha256=sha(base.__file__),
            acceptanceProofProduced=False, layers=[], systemNavigationPerformed=False)

    def app_version(self):
        return '0.2.0-beta.7', '39'

    def attempt(self, layer, name, action):
        try:
            value = action()
            layer[name] = dict(collected=True, value=value)
            return value
        except (ValueError, RuntimeError, subprocess.TimeoutExpired, OSError) as error:
            layer[name] = dict(collected=False, errorType=type(error).__name__, error=str(error))
            return None

    def capture(self, scene, expected_package=None):
        layer = dict(scene=scene, beforeAppInstall=not self.install_attempted, diagnosticOnly=True, accepted=False)
        self.receipt['layers'].append(layer)
        def observation(label, *command):
            raw, _ = self.adb_command(scene + '-' + label, *command, timeout=15)
            return raw.decode(errors='replace')
        for label, command in [('sf-pid-before', ('shell', 'pidof', 'surfaceflinger')),
                ('focus-before', ('shell', 'dumpsys', 'window')),
                ('sf-before', ('shell', 'dumpsys', 'SurfaceFlinger'))]:
            self.attempt(layer, label, lambda label=label, command=command: observation(label, *command))
        def raw_capture():
            raw, _ = self.adb_command(scene + '-raw', 'exec-out', 'screencap', '-p', timeout=30)
            require(len(raw) >= 45 and raw.startswith(base.PNG) and raw[12:16] == b'IHDR' and raw.endswith(b'\x00\x00\x00\x00IEND\xaeB`\x82'), 'Incomplete raw screenshot')
            require(int.from_bytes(raw[16:20], 'big') > 100 and int.from_bytes(raw[20:24], 'big') > 100, 'Raw image dimensions too small')
            path = self.args.output / (scene + '-raw.png'); path.write_bytes(raw)
            return dict(file=path.name, sha256=sha(path), bytes=len(raw))
        self.attempt(layer, 'rawScreenshot', raw_capture)
        def automation_capture():
            remote = '/sdcard/Android/data/dev.aerove.lite.test/files/graphics-' + self.session + '/' + scene
            directory = self.args.output / (scene + '-automation')
            try:
                raw, _ = self.adb_command(scene + '-instrument', 'shell', 'am', 'instrument', '-w', '-r',
                    '-e', 'class', SELECTOR, '-e', 'scene', scene, '-e', 'session', self.session,
                    'dev.aerove.lite.test/android.test.InstrumentationTestRunner', timeout=45)
                require(re.findall(rb'OK \((\d+) tests?\)', raw) == [b'1'] and b'FAILURES' not in raw,
                        'UiAutomation instrumentation did not complete its single capture')
            finally:
                self.adb_command(scene + '-automation-pull', 'pull', remote, directory, timeout=20, check=False)
            receipt = json.loads((directory / 'receipt.json').read_text())
            require(receipt.get('scene') == scene and receipt.get('session') == self.session
                    and receipt.get('captureCompleted') is True and receipt.get('accepted') is False
                    and receipt.get('sha256') == sha(directory / 'uiautomation.png'), 'UiAutomation receipt differs')
            require(receipt['pixels'] == receipt['width'] * receipt['height'] and receipt['pixels'] > 0,
                    'Invalid compositor dimensions')
            return receipt
        self.attempt(layer, 'uiAutomation', automation_capture)
        for label, command in [('sf-pid-after', ('shell', 'pidof', 'surfaceflinger')),
                ('focus-after', ('shell', 'dumpsys', 'window')),
                ('sf-after', ('shell', 'dumpsys', 'SurfaceFlinger')),
                ('logcat', ('logcat', '-b', 'all', '-d', '-v', 'threadtime'))]:
            self.attempt(layer, label, lambda label=label, command=command: observation(label, *command))
        good = all(row.get('collected') for row in layer.values() if isinstance(row, dict) and 'collected' in row)
        image = layer.get('uiAutomation', {}).get('value', {})
        good = good and image.get('rgbBlack', 1) < image.get('pixels', 1) * .99
        good = good and layer.get('sf-pid-before', {}).get('value') == layer.get('sf-pid-after', {}).get('value')
        if expected_package:
            layer['expectedFocusPackage'] = expected_package
            layer['observedCurrentFocus'] = {}
            for moment in ('before', 'after'):
                observed = current_focus_lines(layer.get('focus-' + moment, {}).get('value', ''))
                layer['observedCurrentFocus'][moment] = observed
                good = good and len(observed) == 1 and bool(re.search(r'\b' + re.escape(expected_package) + r'/', observed[0]))
        layer['diagnosticCaptureUsable'] = bool(good)
        return good

    def execute(self):
        args = self.args; guard_execution()
        self.receipt['originalArtifact'] = original(args)
        require(sys.platform == 'linux' and args.run != APP['workflowRun'], 'Independent Linux execution required')
        require(not os.environ.get('ANDROID_EMU_VK_DISABLE_FEATURE_DEPS_CHECK'), 'No feature-dependency bypass')
        require(not os.environ.get('ANGLE_FEATURE_OVERRIDES_DISABLED') and not os.environ.get('ANGLE_FEATURE_OVERRIDES_ENABLED'),
                'Boot overrides require a separately proven image and experiment')
        for key, value in [('GITHUB_SHA', args.source), ('GITHUB_RUN_ID', args.run), ('GITHUB_RUN_ATTEMPT', args.attempt)]:
            require(os.environ.get(key) == value, 'Executor identity differs: ' + key)
        os.environ['PATH'] = str(self.adb.parent) + os.pathsep + os.environ.get('PATH', '')
        devices, _ = self.command('preexisting-devices', [self.adb, 'devices'])
        require(not any(line.strip() for line in devices.decode().splitlines()[1:]), 'Existing device is not owned')
        for port in (5554, 5555):
            with socket.socket() as sock: sock.bind(('127.0.0.1', port))
        harness = json.loads(args.surface_test_info.read_text())
        source = Path(__file__).resolve().parents[1] / 'android-qa/src/main/java/dev/aerove/lite/GraphicsBaselineDiagnosticTest.java'
        require(harness.get('sourceSha256') == sha(source) and harness.get('apkSha256') == sha(args.surface_test_apk)
                and harness.get('sourceRevision') == args.source and str(harness.get('workflowRun')) == args.run
                and harness.get('runAttempt') == int(args.attempt) and harness.get('signerSha256') == base.SIGNER
                and harness.get('appCompiled') is False and harness.get('harnessCompiled') is True and harness.get('diagnosticOnly') is True,
                'Independent observer provenance differs')
        tools = args.sdk / 'build-tools/37.0.0'
        manifest, _ = self.command('observer-manifest', [tools / 'aapt', 'dump', 'xmltree', args.surface_test_apk, 'AndroidManifest.xml'])
        require(re.search(rb'android:targetPackage[^=]*="dev.aerove.lite.test"', manifest)
                and len(re.findall(rb'E: instrumentation(?: |$)', manifest)) == 1, 'Observer must target itself')
        for name, apk in [('original', args.runtime_directory / 'apks/app-release.apk'), ('observer', args.surface_test_apk)]:
            signature, _ = self.command(name + '-signature', [tools / 'apksigner', 'verify', '--verbose', '--print-certs', apk])
            require(base.signing_certificate(signature.decode()) == base.SIGNER, 'Unexpected signing identity')
        command = graphics_command(args.sdk, args.avd, args.graphics_mode)
        self.receipt['emulatorCommand'] = command
        with (args.output / 'emulator.stdout.log').open('wb') as out, (args.output / 'emulator.stderr.log').open('wb') as err:
            self.emulator = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=out, stderr=err,
                start_new_session=True, env=base.emulator_environment(os.environ))
        self.receipt['emulatorPid'] = self.emulator.pid
        base.wait_ready(self.emulator, args.output, self.receipt, self.check, capture_guest_logcat=True,
                        disable_guest_host_query_reset=True)
        name, _ = self.adb_command('owned-avd-name', 'emu', 'avd', 'name')
        require(name.decode().strip().splitlines() == [args.avd, 'OK'], 'Wrong AVD')
        for label, prop, expected in [('api', 'ro.build.version.sdk', b'33'), ('abi', 'ro.product.cpu.abi', b'x86_64'),
                ('image-fingerprint', 'ro.build.fingerprint', b'google/sdk_gphone64_x86_64/emu64x:13/TE1A.240213.009/12342917:userdebug/dev-keys')]:
            raw, _ = self.adb_command(label, 'shell', 'getprop', prop); require(raw.strip() == expected, 'Wrong image')
        for label, cmd in [('image-properties', ['shell', 'getprop']), ('init-ranchu', ['shell',
                'for f in /vendor/etc/init/hw/init.ranchu.rc /vendor/etc/init/init.ranchu.rc /init.ranchu.rc; do if [ -f "$f" ]; then echo "$f"; cat "$f"; fi; done'])]:
            self.adb_command(label, *cmd, check=False)
        users, _ = self.adb_command('owned-users', 'shell', 'pm', 'list', 'users')
        require(re.findall(rb'UserInfo\{([0-9]+):', users) == [b'0'], 'Fresh single-user AVD required')
        packages, _ = self.adb_command('before-install-packages', 'shell', 'pm', 'list', 'packages', '-u', '--user', '0', base.PACKAGE)
        require(not packages.strip(), 'App or observer already installed')
        self.surface_install_attempted = True
        self.adb_command('install-observer', 'install', '-t', '--no-streaming', args.surface_test_apk, timeout=120)
        self.start_scene_logcat()
        usable = True
        for scene, action in [('home', ['shell', 'am', 'start', '-W', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME']),
                              ('settings', ['shell', 'am', 'start', '-W', '-a', 'android.settings.SETTINGS'])]:
            resolve, _ = self.adb_command(scene + '-resolve', 'shell', 'cmd', 'package', 'resolve-activity', '--brief', *action[4:])
            components = re.findall(rb'(?m)^([a-zA-Z0-9_.]+)/(?:[a-zA-Z0-9_.$]+)\s*$', resolve)
            require(len(components) == 1, 'System scene must resolve one actual component')
            launch = dict(scene=scene)
            self.receipt.setdefault('sceneLaunches', []).append(launch)
            self.receipt['systemNavigationPerformed'] = True
            self.attempt(launch, 'launch', lambda: self.adb_command(scene + '-launch', *action, timeout=30)[0].decode())
            time.sleep(2)
            usable = self.capture(scene, components[0].decode()) and launch['launch']['collected'] and usable
        require(usable, 'System layer failed; original App deliberately not installed')
        self.install_attempted = True
        self.adb_command('install-original', 'install', '--no-streaming', args.runtime_directory / 'apks/app-release.apk', timeout=120)
        self.receipt['installedApp'] = self.installed_hash('original-installed')
        self.adb_command('original-launch', 'shell', 'am', 'start', '-W', '-n', base.LAUNCHER, timeout=45)
        time.sleep(3)
        usable = self.capture('app', base.PACKAGE)
        self.receipt['installedAppAfter'] = self.installed_hash('original-after')
        require(usable, 'Original App layer failed; raw evidence retained')
        self.receipt.update(status='collected-diagnostic-only', collectionComplete=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('runtime-directory', 'archive', 'artifact-api', 'output', 'surface-test-apk', 'surface-test-info'):
        parser.add_argument('--' + name, type=Path, required=True)
    for name in ('source', 'run', 'attempt'): parser.add_argument('--' + name, required=True)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--avd', default='test')
    parser.add_argument('--graphics-mode', choices=('baseline', 'host-default'), default='baseline')
    parser.add_argument('--original-run-number', type=int, choices=(333,), default=333)
    parser.set_defaults(app_renderer='default', disable_queue_submit_with_commands=False)
    args = parser.parse_args()
    require(re.fullmatch(r'[0-9a-f]{40}', args.source) and args.run.isdecimal() and int(args.run) > 0
            and args.attempt.isdecimal() and int(args.attempt) > 0 and re.fullmatch(r'[A-Za-z0-9_.-]+', args.avd),
            'Actual executor identity and safe owned AVD required')
    args.output.mkdir(parents=True, exist_ok=False)
    return Diagnostic(args).run()


if __name__ == '__main__':
    raise SystemExit(main())
