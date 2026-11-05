"""Fixtures exercise contracts, never Android execution."""
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile
from unittest.mock import patch
import emulator_current as current

SCRIPT_ASSETS = Path(__file__).resolve().parents[1] / "app/src/main/assets/scripts"


class DerivedHostEvidenceTests(unittest.TestCase):
    def test_only_the_tested_builder_library_and_owned_mapping_are_reported_loaded(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            library = root / 'emulator/lib64/libgfxstream_backend.so'
            library.parent.mkdir(parents=True)
            library.write_bytes(b'derived-host-fixture-not-an-ELF')
            build = dict(status='host-module-built-and-tested', googleOriginal=False, hostRegressionPassed=True,
                         builderSha256=current.sha(Path(current.__file__).with_name('build_gfxstream_derived_host.sh')),
                         derivedLibrarySha256=current.sha(library))
            receipt = root / 'build.json'
            maps_path = Path('/proc') / '123' / 'maps'
            real_read = Path.read_text
            mapping = '1000-2000 r-xp 0000 00:00 1 ' + str(library.resolve()) + '\n'
            def read(path, *args, **kwargs):
                return mapping if path == maps_path else real_read(path, *args, **kwargs)
            with patch.dict(os.environ, ANDROID_HOME=str(root)), patch.object(Path, 'read_text', read):
                receipt.write_text(json.dumps(build))
                result = current.derived_host_evidence(receipt, 123, root)
                self.assertTrue(result['loadedInOwnedEmulator'])
                self.assertEqual(result['loadedLibrarySha256'], current.sha(library))
                self.assertEqual(result['emulatorPid'], 123)
                for change in ({'googleOriginal': True}, {'status': 'failed'}, {'hostRegressionPassed': False},
                               {'builderSha256': '0' * 64}, {'derivedLibrarySha256': '0' * 64}):
                    receipt.write_text(json.dumps({**build, **change}))
                    with self.subTest(change=change), self.assertRaises(ValueError):
                        current.derived_host_evidence(receipt, 123, root)
                receipt.write_text(json.dumps(build))
                mapping = '1000-2000 r-xp 0000 00:00 1 /other/libgfxstream_backend.so\n'
                with self.assertRaises(ValueError):
                    current.derived_host_evidence(receipt, 123, root)
                self.assertEqual((root / 'derived-gfxstream-emulator-maps.txt').read_text(), mapping)


class GuestAngleEvidenceTests(unittest.TestCase):
    startup = 'Selecting Vulkan device: SwiftShader Device (Subzero), Version: 1.3.0'
    guest = 'GLES: Google Inc. (Google), ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device (Subzero) (0x0000C0DE)), Google-5.0.0), OpenGL ES 3.1.0 (ANGLE 2.1.0 git hash: unknown hash)'

    def outputs(self, *, api='33\n', boot='angle\n', hardware='angle\n', overrides='supportsHostQueryReset\n', guest=None, library_exit=0):
        return [subprocess.CompletedProcess([], 0, api, ''),
                subprocess.CompletedProcess([], 0, boot, ''),
                subprocess.CompletedProcess([], 0, hardware, ''),
                subprocess.CompletedProcess([], 0, overrides, ''),
                subprocess.CompletedProcess([], library_exit, 'original image libraries\n', 'missing' if library_exit else ''),
                subprocess.CompletedProcess([], 0, self.guest if guest is None else guest, '')]

    def test_guest_and_host_actual_routes_are_both_required(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            with patch.object(current.subprocess, 'run', side_effect=self.outputs()):
                value = current.guest_angle_evidence(directory, {}, self.startup)
            self.assertEqual(value['route'], 'guest-angle-vulkan')
            self.assertEqual(value['apiLevel'], 33)
            self.assertEqual(value['angleFeatureOverridesDisabled'], 'supportsHostQueryReset')
            self.assertEqual(value['guestRenderer'], [self.guest])
            self.assertEqual(value['hostVulkanDevice'], [self.startup])
            self.assertEqual((directory / 'guest-angle-boot-egl.out').read_text(), 'angle\n')
            for outputs, startup in ((self.outputs(api='37\n'), self.startup),
                                     (self.outputs(boot='emulation\n'), self.startup),
                                     (self.outputs(guest=self.guest.replace('3.1.0', '3.0.0')), self.startup),
                                     (self.outputs(hardware='emulation\n'), self.startup),
                                     (self.outputs(overrides='\n'), self.startup),
                                     (self.outputs(overrides='someOtherFeature\n'), self.startup),
                                     (self.outputs(guest='GLES: ANGLE OpenGL ES'), self.startup),
                                     (self.outputs(), 'Graphics Adapter ANGLE SwiftShader')):
                with self.subTest(startup=startup, outputs=outputs), \
                        patch.object(current.subprocess, 'run', side_effect=outputs), \
                        self.assertRaises(ValueError):
                    current.guest_angle_evidence(directory, {}, startup)

    def test_missing_original_image_library_retains_failure_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            with patch.object(current.subprocess, 'run', side_effect=self.outputs(library_exit=1)), \
                    self.assertRaises(subprocess.CalledProcessError):
                current.guest_angle_evidence(directory, {}, self.startup)
            self.assertEqual((directory / 'guest-angle-libraries.err').read_text(), 'missing')

    def test_surfaceflinger_service_budget_outlives_its_default_and_stays_inside_adb(self):
        responses = iter(self.outputs())
        def device_read(command, **kwargs):
            response = next(responses)
            if command[-1] == 'SurfaceFlinger':
                if '-t' not in command:
                    return subprocess.CompletedProcess(command, 0,
                        "*** SERVICE 'SurfaceFlinger' DUMP TIMEOUT (10000ms) EXPIRED ***\n", '')
                service_seconds = int(command[command.index('-t') + 1])
                self.assertGreater(service_seconds, 10)
                self.assertGreater(kwargs['timeout'], service_seconds)
            else:
                self.assertEqual(kwargs['timeout'], 15)
            return response
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(current.subprocess, 'run', side_effect=device_read):
            value = current.guest_angle_evidence(Path(temporary), {}, self.startup)
        self.assertEqual(value['guestRenderer'], [self.guest])

    def test_partial_surfaceflinger_timeout_never_qualifies_the_renderer(self):
        timed_out = self.guest + "\n*** SERVICE 'SurfaceFlinger' DUMP TIMEOUT (10000ms) EXPIRED ***\n"
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            with patch.object(current.subprocess, 'run', side_effect=self.outputs(guest=timed_out)), \
                    self.assertRaises(ValueError):
                current.guest_angle_evidence(directory, {}, self.startup)
            self.assertEqual((directory / 'guest-angle-surfaceflinger.out').read_text(), timed_out)

    def test_owner_cancellation_does_not_start_more_adb_reads(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(current.subprocess, 'run') as adb, \
                self.assertRaises(InterruptedError):
            current.guest_angle_evidence(Path(temporary), {'interruptSignals': [15]}, self.startup)
        adb.assert_not_called()


class CurrentOwnerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.runtime = self.root / 'runtime'
        self.apks = self.root / 'installed'
        self.expected = dict(sourceRevision='new-source', sourceTree='new-tree', workflowRun='new-run', runAttempt=2, runNumber=90)
        rows = []
        for name, loc in [('app-release.apk', 'release'), ('app-release-androidTest.apk', 'androidTest/release')]:
            data = ('fixture-' + name).encode()
            if name == 'app-release.apk':
                archive = io.BytesIO()
                assets = SCRIPT_ASSETS
                with zipfile.ZipFile(archive, 'w') as bundle:
                    for asset in assets.iterdir():
                        if asset.is_file():
                            bundle.writestr('assets/scripts/' + asset.name, asset.read_bytes())
                data = archive.getvalue()
            original = self.runtime / 'apks' / name
            installed = self.apks / loc / name
            for path in [original, installed]:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
            rows.append(dict(path='apks/' + name, bytes=len(data), sha256=current.sha(original)))
        info = self.runtime / 'BUILD_INFO.json'
        info.write_text(json.dumps({'flutter_packaging': {'client_kind': 'legacy-host-contract-fixture'}}))
        rows.append(dict(path='BUILD_INFO.json', bytes=info.stat().st_size, sha256=current.sha(info)))
        value = dict(self.expected, abi='x86_64', prebuiltApks=True, apkInspectorOutcome='success', files=rows)
        (self.runtime / 'source-manifest.json').write_text(json.dumps(value))
        (self.runtime / 'SHA256SUMS').write_text('fixture')
        self.addCleanup(patch.stopall)
        patch.object(current, 'APK_ROOT', self.apks).start()
        patch.object(current.artifacts, 'identity', return_value=self.expected).start()
        patch.dict(os.environ, ANDROID_SERIAL='emulator-5554').start()

    def proof(self):
        return dict(current.verify_current_runtime(self.runtime), schema=1, owner='emulator_current.py',
                    runtimeDirectory=str(self.runtime), deviceSerial='emulator-5554',
                    gpuBackend=current.GPU_BACKEND, rendererBackend=current.RENDERER_LABEL,
                    rendererEvidence=['Graphics Adapter ANGLE (Google SwiftShader)'])

    def validate(self, proof):
        path = self.root / 'proof.json'
        path.write_text(json.dumps(proof))
        return current.validate_renderer_proof(path)

    def test_current_artifact_and_confirmed_renderer_preserve_current_identity(self):
        self.assertEqual(self.expected, self.validate(self.proof())['identity'])

    def test_stale_source_and_attempt_are_rejected(self):
        for key in ['sourceRevision', 'runAttempt']:
            proof = self.proof()
            proof['identity'] = dict(proof['identity'], **{key: 'old'})
            with self.assertRaises(ValueError):
                self.validate(proof)

    def test_changed_installed_apk_is_rejected(self):
        proof = self.proof()
        (self.apks / 'release/app-release.apk').write_bytes(b'changed')
        with self.assertRaises(ValueError):
            self.validate(proof)

    def test_wrong_manifest_source_is_rejected(self):
        proof = self.proof()
        path = self.runtime / 'source-manifest.json'
        value = json.loads(path.read_text())
        value['sourceRevision'] = 'old79'
        path.write_text(json.dumps(value))
        with self.assertRaises(current.artifacts.PromotionError):
            self.validate(proof)

    def test_renderer_label_without_actual_swangle_startup_is_rejected(self):
        proof = self.proof()
        proof['rendererEvidence'] = ['Graphics Adapter Google SwiftShader']
        with self.assertRaises(ValueError):
            self.validate(proof)

    def test_old_qa_owner_and_wrong_serial_are_rejected(self):
        for key, value in [('owner', 'emulator_qa79.py'), ('deviceSerial', 'emulator-5556')]:
            proof = dict(self.proof(), **{key: value})
            with self.assertRaises(ValueError):
                self.validate(proof)

    def test_acceptance_source_snapshot_uses_current_run_with_only_renderer_proof(self):
        proof_path = self.root / 'proof.json'
        proof_path.write_text(json.dumps(self.proof()))
        shell = Path(__file__).with_name('emulator_acceptance.sh').read_text()
        program = shell.split("<<'PY'\n", 1)[1].split('\nPY\n', 1)[0]
        output = self.root / 'SOURCE.json'
        environment = dict(AEROVE_CURRENT_RENDERER_PROOF=str(proof_path),
                           GITHUB_SHA='new-source', GITHUB_RUN_ID='new-run',
                           GITHUB_RUN_NUMBER='90', GITHUB_RUN_ATTEMPT='2')
        with patch.dict(os.environ, environment):
            os.environ.pop('AEROVE_QA79_PROOF', None)
            with patch.object(current.sys, 'argv', ['snapshot', str(output), 'matrix', '600000', 'emulator-5554', '', '',
                    'dev.aerove.lite.ui.LiteDashboardMatrixTest']), \
                    patch.object(current.subprocess, 'check_output', return_value='new-tree\n'):
                exec(compile(program, 'acceptance-source-snapshot', 'exec'), {})
        result = json.loads(output.read_text())
        self.assertEqual('new-source', result['sourceRevision'])
        self.assertEqual('new-tree', result['sourceTree'])
        self.assertEqual('new-run', result['appBuildRun'])
        self.assertEqual(2, result['runAttempt'])
        self.assertEqual(current.RENDERER_LABEL, result['rendererBackend'])

    def test_legacy_shell_refuses_current_flutter_target_before_selection(self):
        self.flutter_runtime()
        proof = self.root / 'flutter-proof.json'
        proof.write_text(json.dumps(self.proof()))
        shell = Path(__file__).with_name('emulator_acceptance.sh').read_text()
        program = shell.split("<<'PY'\n", 1)[1].split('\nPY\n', 1)[0]
        with patch.dict(os.environ, AEROVE_CURRENT_RENDERER_PROOF=str(proof)):
            os.environ.pop('AEROVE_QA79_PROOF', None)
            with self.assertRaisesRegex(SystemExit, 'Legacy Compose'):
                exec(compile(program, 'reject-current-Flutter-legacy-selection', 'exec'), {})

    def flutter_runtime(self):
        from verify_apk import FLUTTER_ASSET_PREFIX, FLUTTER_RELEASE_ASSETS
        self.expected.update(sourceRevision='a' * 40, sourceTree='b' * 40, workflowRun='95')
        app = self.runtime / 'apks/app-release.apk'
        with zipfile.ZipFile(app, 'a') as archive:
            for name in ('libapp.so', 'libflutter.so', 'libaerove_meow_core.so'):
                archive.writestr('lib/x86_64/' + name, b'\x7fELFhost-contract-fixture')
        (self.apks / 'release/app-release.apk').write_bytes(app.read_bytes())
        info = dict(product='AEROVE Lite', application_id='dev.aerove.lite',
                    launcher_activity='dev.aerove.lite.FlutterMainActivity', abi='x86_64',
                    apk_sha256=current.sha(app), source=self.expected['sourceRevision'], tree=self.expected['sourceTree'],
                    workflow='https://github.com/IvanSolis1989/Aerove/actions/runs/95', run_number=90, run_attempt=2,
                    flutter_packaging=dict(schema=1, client_kind='flutter-aot', abi='x86_64',
                        libraries={f'lib/x86_64/{name}': {} for name in ('libapp.so', 'libflutter.so')},
                        assets={FLUTTER_ASSET_PREFIX + name: {} for name in FLUTTER_RELEASE_ASSETS}))
        self.seal_flutter_info(info)
        return info

    def seal_flutter_info(self, info):
        info_path = self.runtime / 'BUILD_INFO.json'
        info_path.write_text(json.dumps(info))
        rows = [dict(path=path.relative_to(self.runtime).as_posix(), bytes=path.stat().st_size,
                     sha256=current.sha(path)) for path in (self.runtime / 'apks').iterdir()]
        rows.append(dict(path='BUILD_INFO.json', bytes=info_path.stat().st_size, sha256=current.sha(info_path)))
        (self.runtime / 'source-manifest.json').write_text(json.dumps(dict(self.expected,
            abi='x86_64', prebuiltApks=True, apkInspectorOutcome='success', files=rows)))

    def test_flutter_phase_uses_exact_verified_transport_inputs_and_command(self):
        self.flutter_runtime()
        with patch('android_acceptance_verify.platform_binding', return_value=self.root / 'sealed-platform.apk') as binding:
            inputs = current.flutter_ui_inputs(self.runtime, current.verify_current_runtime(self.runtime))
        binding.assert_called_once()
        self.assertEqual(inputs['platform_apk'], (self.root / 'sealed-platform.apk').resolve())
        with patch.dict(os.environ, ANDROID_HOME='/sdk-home', ANDROID_SDK_ROOT='/sdk-pinned'):
            command = current.phase_acceptance_command('flutter-ui', self.root / 'flutter-ui', inputs)
        self.assertEqual(command, [current.sys.executable, 'apps/aerove-lite/tool/android_acceptance.py',
            '--apk', str((self.apks / 'release/app-release.apk').resolve()),
            '--test-apk', str((self.apks / 'androidTest/release/app-release-androidTest.apk').resolve()),
            '--build-info', str((self.runtime / 'BUILD_INFO.json').resolve()),
            '--adb', str(Path('/sdk-pinned/platform-tools/adb')),
            '--apksigner', str(Path('/sdk-pinned/build-tools/37.0.0/apksigner')),
            '--serial', 'emulator-5554', '--output', str(self.root / 'flutter-ui/production-ui'),
            '--platform-apk', str((self.root / 'sealed-platform.apk').resolve()), '--phase', 'flutter-ui'])
        for phase in ('matrix', 'remaining', 'original-sample'):
            self.assertEqual(['bash', 'apps/aerove-lite/tool/emulator_acceptance.sh', phase],
                             current.phase_acceptance_command(phase, self.root))

    def test_flutter_phase_rejects_unsealed_legacy_or_cross_run_build_info(self):
        info = self.flutter_runtime()
        (self.runtime / 'BUILD_INFO.json').write_text('{}')
        with self.assertRaisesRegex(ValueError, 'sealed runtime BUILD_INFO'):
            current.flutter_ui_inputs(self.runtime, dict(identity=self.expected))
        for key, value in (('launcher_activity', 'dev.aerove.lite.MainActivity'),
                           ('workflow', 'https://github.com/IvanSolis1989/Aerove/actions/runs/other'),
                           ('flutter_packaging', {})):
            with self.subTest(key=key):
                self.seal_flutter_info(dict(info, **{key: value}))
                with self.assertRaises(ValueError):
                    current.flutter_ui_inputs(self.runtime, current.verify_current_runtime(self.runtime))
        self.seal_flutter_info(info)
        manifest = json.loads((self.runtime / 'source-manifest.json').read_text())
        manifest['files'] = [row for row in manifest['files'] if row['path'] != 'BUILD_INFO.json']
        (self.runtime / 'source-manifest.json').write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, 'sealed runtime BUILD_INFO'):
            current.flutter_ui_inputs(self.runtime, dict(identity=self.expected))


    def guest_proof(self):
        self.flutter_runtime()
        fixture = GuestAngleEvidenceTests()
        with patch.object(current.subprocess, 'run', side_effect=fixture.outputs()):
            route = current.guest_angle_evidence(self.root, {}, fixture.startup)
        return dict(self.proof(), phase='flutter-full', rendererBackend=current.GUEST_RENDERER_LABEL,
                    rendererEvidence=[fixture.guest, fixture.startup], guestGraphicsRoute=route,
                    guestGraphicsEvidence=current.guest_angle_file_hashes(self.root))

    def test_guest_proof_replays_device_route_without_inventing_host_gles(self):
        proof = self.guest_proof()
        with patch.dict(os.environ, AEROVE_GUEST_ANGLE='1'), \
                patch.object(current, 'renderer_evidence', side_effect=AssertionError('Guest must not claim host GLES')):
            self.assertEqual(self.validate(proof)['identity'], self.expected)
        with patch.dict(os.environ, AEROVE_GUEST_ANGLE='0'), self.assertRaises(ValueError):
            self.validate(proof)
        with patch.dict(os.environ, AEROVE_GUEST_ANGLE='1'):
            for change in ({'owner': 'emulator_qa79.py'}, {'deviceSerial': 'emulator-5556'},
                           {'identity': dict(self.expected, runAttempt=1)}, {'phase': 'matrix'},
                           {'rendererBackend': current.RENDERER_LABEL}):
                with self.subTest(change=change), self.assertRaises(ValueError):
                    self.validate(dict(proof, **change))

    def test_guest_raw_hashes_and_actual_api_compat_and_renderers_remain_required(self):
        proof = self.guest_proof()
        cases = (
            ('api-level.out', '37\n', 'actual API33'),
            ('hardware-egl.out', 'emulation\n', 'booted EGL'),
            ('angle-overrides-disabled.out', 'someOtherFeature\n', 'compatibility override'),
            ('surfaceflinger.out', GuestAngleEvidenceTests.guest.replace('3.1.0', '3.0.0'), 'not both observed'),
            ('host-vulkan.out', 'Selecting Vulkan device: Mesa\n', 'not both observed'),
            ('libraries.exit.txt', '1\n', 'did not succeed'),
        )
        with patch.dict(os.environ, AEROVE_GUEST_ANGLE='1'):
            for suffix, value, error in cases:
                path = self.root / ('guest-angle-' + suffix)
                original = path.read_text()
                path.write_text(value)
                try:
                    with self.subTest(suffix=suffix), self.assertRaisesRegex(ValueError, 'raw evidence changed'):
                        self.validate(proof)
                    changed = dict(proof, guestGraphicsEvidence=dict(proof['guestGraphicsEvidence'],
                        **{path.name: current.sha(path)}))
                    with self.subTest(resealed=suffix), self.assertRaisesRegex(ValueError, error):
                        self.validate(changed)
                finally:
                    path.write_text(original)
            changed = dict(proof, guestGraphicsRoute=dict(proof['guestGraphicsRoute'], apiLevel=37))
            with self.assertRaisesRegex(ValueError, 'differs from its original device evidence'):
                self.validate(changed)

    def test_guest_current_phases_reach_original_platform_binding_but_diagnostics_stay_bounded(self):
        self.flutter_runtime()
        for phase in ('flutter-ui', 'flutter-platform', 'flutter-full'):
            with self.subTest(phase=phase), patch.dict(os.environ, {'AEROVE_GUEST_ANGLE': '1'}, clear=True), \
                    patch.object(current.sys, 'platform', 'linux'), \
                    patch.object(current.sys, 'argv', ['owner', phase, '--runtime-directory', str(self.runtime)]), \
                    patch.object(current, 'flutter_ui_inputs', side_effect=RuntimeError('original platform binding boundary')) as inputs, \
                    patch.object(current.subprocess, 'Popen') as spawn:
                with self.assertRaisesRegex(RuntimeError, 'original platform binding boundary'):
                    current.main()
                inputs.assert_called_once()
                spawn.assert_not_called()
        for flag in ('AEROVE_EMULATOR_SIGILL_TRACE', 'AEROVE_GFXSTREAM_SOURCE_RECEIPT'):
            with self.subTest(flag=flag), patch.dict(os.environ, {'AEROVE_GUEST_ANGLE': '1', flag: '1'}, clear=True), \
                    patch.object(current.sys, 'platform', 'linux'), \
                    patch.object(current.sys, 'argv', ['owner', 'flutter-full', '--runtime-directory', str(self.runtime)]), \
                    patch.object(current.subprocess, 'Popen') as spawn:
                with self.assertRaises(ValueError):
                    current.main()
                spawn.assert_not_called()


    def test_guest_original208_still_binds_original_and_harness_without_legacy_host_gles(self):
        import emulator_original84 as original
        proof = self.guest_proof()
        for target, pin in (('lite208-flutter-ui', original.LITE208_FLUTTER_UI),
                            ('lite201-flutter-ui', original.LITE201_FLUTTER_UI)):
            runtime = dict(current.verify_current_runtime(self.runtime), identity=pin['identity'],
                runtimeTarget=target, diagnosticOriginalRuntime=True, originalAppIdentity=pin['identity'],
                harnessIdentity=self.expected, diagnosticOnly=True, representativeOnly=True,
                accepted=False, aggregateAcceptance=False, promotionAccepted=False)
            neutral = self.root / 'original-input.json'
            neutral.write_text(json.dumps(runtime))
            bound = dict(proof, **runtime, phase='flutter-ui')
            with patch.dict(os.environ, AEROVE_GUEST_ANGLE='1', AEROVE_ORIGINAL_RUNTIME_PROOF=str(neutral)), \
                    patch.object(current, 'verify_current_runtime', return_value=runtime) as verify, \
                    patch.object(original, 'validate_original_renderer', side_effect=AssertionError('Legacy host route is not Guest')):
                if target == 'lite208-flutter-ui':
                    value = self.validate(bound)
                    self.assertEqual(value['originalAppIdentity'], pin['identity'])
                    self.assertEqual(value['harnessIdentity'], self.expected)
                else:
                    with self.assertRaisesRegex(ValueError, 'another historical runtime'):
                        self.validate(bound)
                verify.assert_called_once_with(str(self.runtime))


class Original84BoundaryTests(unittest.TestCase):
    def test_optin_delegates_runtime_and_renderer_to_strict_original_adapter(self):
        import sys
        from types import SimpleNamespace
        from unittest.mock import Mock
        original = dict(sourceRevision='original-source')
        adapter = SimpleNamespace(verify_original_runtime=Mock(return_value=original),
                                  validate_original_renderer=Mock(return_value=original))
        with patch.dict(sys.modules, emulator_original84=adapter), \
                patch.dict(os.environ, AEROVE_ORIGINAL84_PROOF='fixture-proof'):
            self.assertEqual(original, current.verify_current_runtime('runtime'))
            self.assertEqual(original, current.validate_renderer_proof('renderer'))
        adapter.verify_original_runtime.assert_called_once_with('runtime')
        adapter.validate_original_renderer.assert_called_once_with('renderer')

    def test_source_snapshot_keeps_original_app_and_new_harness_separate(self):
        import sys
        original = dict(sourceRevision='original-source', sourceTree='original-tree',
                        workflowRun='original-run', runAttempt=1, runNumber=84)
        verified = dict(diagnosticOriginal84=True, originalAppIdentity=original,
                        rendererBackend=current.RENDERER_LABEL)
        shell = Path(__file__).with_name('emulator_acceptance.sh').read_text()
        program = shell.split("<<'PY'\n", 1)[1].split('\nPY\n', 1)[0]
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / 'SOURCE.json'
            env = dict(AEROVE_ORIGINAL84_PROOF='fixture-proof',
                       AEROVE_CURRENT_RENDERER_PROOF='fixture-renderer',
                       GITHUB_SHA='harness-source', GITHUB_RUN_ID='harness-run',
                       GITHUB_RUN_NUMBER='100', GITHUB_RUN_ATTEMPT='2')
            with patch.dict(os.environ, env), \
                    patch.object(current, 'validate_renderer_proof', return_value=verified), \
                    patch.object(sys, 'argv', ['snapshot', str(output), 'matrix', '600000', 'emulator-5554', '', '', 'dev.aerove.lite.ui.LiteDashboardMatrixTest']), \
                    patch.object(current.subprocess, 'check_output', side_effect=AssertionError('must not replace original tree')):
                os.environ.pop('AEROVE_QA79_PROOF', None)
                exec(compile(program, 'original84-source-snapshot', 'exec'), {})
            result = json.loads(output.read_text())
            self.assertEqual('original-source', result['sourceRevision'])
            self.assertEqual('original-tree', result['sourceTree'])
            self.assertEqual('original-run', result['appBuildRun'])
            self.assertEqual(original, result['originalAppIdentity'])
            self.assertEqual('harness-source', result['harnessRevision'])
            self.assertEqual('harness-run', result['workflowRun'])
            self.assertEqual(100, result['runNumber'])
            self.assertEqual(2, result['runAttempt'])
            self.assertIs(False, result['promotionAccepted'])
            self.assertIsNone(result['testShard'])

    def test_original_optin_without_validated_current_renderer_fails_closed(self):
        import sys
        shell = Path(__file__).with_name('emulator_acceptance.sh').read_text()
        program = shell.split("<<'PY'\n", 1)[1].split('\nPY\n', 1)[0]
        with patch.dict(os.environ, AEROVE_ORIGINAL84_PROOF='fixture-proof'):
            os.environ.pop('AEROVE_QA79_PROOF', None)
            os.environ.pop('AEROVE_CURRENT_RENDERER_PROOF', None)
            with self.assertRaises(SystemExit):
                exec(compile(program, 'original84-invalid-proof', 'exec'), {})



class CurrentCancellationTests(unittest.TestCase):
    """Real harmless children at main's cancellation seam; never Android."""
    def exercise_cancellation(self, cleanup_error=False, mode='runtime', phase='matrix'):
        import signal
        import subprocess
        import sys
        import time
        import emulator_qa79 as owners
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            # Lifecycle fixtures are explicitly non-Android; real current Flutter legacy phases are rejected.
            (root / 'BUILD_INFO.json').write_text(json.dumps({'flutter_packaging': {'client_kind': 'lifecycle-fixture-not-Android'}}))
            children = {}
            spawn_commands = []
            waits = {}
            original_popen = subprocess.Popen
            unrelated = original_popen([sys.executable, '-c', 'import time; time.sleep(20)'],
                                       start_new_session=os.name == 'posix',
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            previous = {sig: signal.getsignal(sig) for sig in (signal.SIGTERM, signal.SIGINT)}
            emitted_second = False
            injected_late = False
            previous_trace = sys.gettrace()
            delayed_exit = root / 'acceptance-delayed-exit'
            windows = os.name == 'nt'

            def spawn(command, **kwargs):
                nonlocal emitted_second
                spawn_commands.append(command)
                name = 'emulator' if '-gpu' in command else 'acceptance'
                if name == 'emulator':
                    code = "import time; print('Graphics Adapter ANGLE (Google SwiftShader)',flush=True); time.sleep(20)"
                    child = original_popen([sys.executable, '-c', code], **kwargs)
                else:
                    # Windows terminate() cannot run a POSIX TERM trap. Use a real
                    # pipe-controlled delayed child there; parent signals remain real.
                    if windows:
                        code = "import sys,time; from pathlib import Path; sys.stdin.readline(); time.sleep(.2); Path(sys.argv[1]).write_text('joined delay'); sys.exit(0)"
                        child = original_popen([sys.executable, '-c', code, str(delayed_exit)], stdin=subprocess.PIPE,
                                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                               start_new_session=True)
                        def terminate():
                            child.stdin.write(b'stop\n')
                            child.stdin.flush()
                        child.terminate = terminate
                    else:
                        code = "import signal,time,sys; from pathlib import Path\n" \
                               "def stop(sig,frame):\n time.sleep(.2)\n Path(sys.argv[1]).write_text('joined delay')\n sys.exit(0)\n" \
                               "signal.signal(signal.SIGTERM,stop)\nprint('ready',flush=True)\nwhile True: time.sleep(.02)"
                        ready = (root / 'acceptance-ready').open('wb')
                        child = original_popen([sys.executable, '-c', code, str(delayed_exit)],
                                               stdout=ready, stderr=subprocess.DEVNULL, start_new_session=True)
                        ready.close()
                        deadline = time.monotonic() + 3
                        while 'ready' not in (root / 'acceptance-ready').read_text():
                            if time.monotonic() > deadline:
                                self.fail('real acceptance fixture did not initialize')
                            time.sleep(.01)
                children[name] = child
                waits[name] = 0
                real_wait = child.wait
                def wait(*args, **options):
                    nonlocal emitted_second
                    waits[name] += 1
                    if name == 'acceptance' and mode == 'runtime' and not emitted_second:
                        emitted_second = True
                        signal.raise_signal(signal.SIGINT)
                    return real_wait(*args, **options)
                child.wait = wait
                return child

            def adb(command, **kwargs):
                if 'getprop' in command:
                    log = root / 'lite-diagnostics' / phase / 'emulator.stdout.log'
                    deadline = time.monotonic() + 3
                    while 'SwiftShader' not in log.read_text():
                        if time.monotonic() > deadline:
                            self.fail('real emulator fixture did not initialize')
                        time.sleep(.01)
                    return subprocess.CompletedProcess(command, 0, '1')
                if 'policy' in command:
                    return subprocess.CompletedProcess(command, 0, 'KeyguardServiceDelegate\n  showing=false\n  inputRestricted=false\n  systemIsReady=true\n  bootCompleted=true\n  screenState=SCREEN_STATE_ON\n  interactiveState=INTERACTIVE_STATE_AWAKE\n', '')
                return subprocess.CompletedProcess(command, 0, '', '')

            def stop_acceptance(child):
                owners.stop_owned(child)
                if cleanup_error:
                    raise RuntimeError('controlled acceptance cleanup error after real join')

            def cancel(*args):
                if mode != 'runtime':
                    return 0
                signal.raise_signal(signal.SIGTERM)
                current.check_cancelled(args[2])
                self.fail('cooperative cancellation must leave main runtime phase')

            original_writer = getattr(current, 'write_owner_receipt', None)
            original_text_writer = Path.write_text
            def final_write(*args, **kwargs):
                nonlocal injected_late
                if not injected_late:
                    injected_late = True
                    signal.raise_signal(signal.SIGTERM)
                return original_writer(*args, **kwargs)

            def old_final_write(path, *args, **kwargs):
                nonlocal injected_late
                if path.name == 'EMULATOR-OWNER.json' and not injected_late:
                    injected_late = True
                    signal.raise_signal(signal.SIGTERM)
                return original_text_writer(path, *args, **kwargs)

            def trace(frame, event, argument):
                nonlocal injected_late
                if frame.f_code is current.main.__code__ and not injected_late:
                    if mode == 'return' and event == 'return' and argument == 0:
                        injected_late = True
                        signal.raise_signal(signal.SIGTERM)
                    elif mode == 'cleanup_entry' and event == 'line':
                        import linecache
                        line = linecache.getline(frame.f_code.co_filename, frame.f_lineno)
                        if "phase = 'cleanup'" in line or 'cleanup_started = True' in line:
                            injected_late = True
                            signal.raise_signal(signal.SIGTERM)
                return trace

            try:
                environment = dict(RUNNER_TEMP=str(root), ANDROID_HOME=str(root),
                                   GITHUB_SHA='new-source', GITHUB_RUN_ID='new-run')
                with patch.dict(os.environ, environment), \
                        patch.object(current.sys, 'platform', 'linux'), \
                        patch.object(current.sys, 'argv', ['owner', phase, '--runtime-directory', str(root)]), \
                        patch.object(current, 'verify_current_runtime', return_value=dict(identity={'source': 'fixture'})), \
                        patch.object(current, 'flutter_ui_inputs', return_value=dict(apk='owned-app.apk', test_apk='owned-test.apk', platform_apk='owned-platform.apk', build_info='owned-BUILD_INFO.json')), \
                        patch.object(current, 'crash_inventory', return_value={}), \
                        patch.object(current, 'preserve_owned_crash', return_value={'status': 'fixture; not Android'}), \
                        patch.object(current.subprocess, 'Popen', side_effect=spawn), \
                        patch.object(current.subprocess, 'run', side_effect=adb), \
                        patch.object(current, 'monitor', side_effect=cancel), \
                        patch.object(current, 'stop_owned', side_effect=stop_acceptance):
                    os.environ.pop('AEROVE_QA79_PROOF', None)
                    os.environ.pop('AEROVE_CURRENT_RENDERER_PROOF', None)
                    with patch.object(current, 'write_owner_receipt', side_effect=final_write) if mode == 'write_entry' and original_writer else patch.object(Path, 'write_text', old_final_write) if mode == 'write_entry' else patch.dict({}, {}):
                        if mode in ('return', 'cleanup_entry'):
                            sys.settrace(trace)
                        try:
                            exit_code = current.main()
                        except InterruptedError:
                            exit_code = 1
                        finally:
                            sys.settrace(previous_trace)
                    if mode == 'normal':
                        self.assertEqual(0, exit_code)
                    else:
                        self.assertNotEqual(0, exit_code)
                receipt = json.loads((root / 'lite-diagnostics' / phase / 'EMULATOR-OWNER.json').read_text())
                expected_signals = [signal.SIGTERM, signal.SIGINT] if mode == 'runtime' else [] if mode == 'normal' else [signal.SIGTERM]
                self.assertEqual(expected_signals, receipt['interruptSignals'])
                self.assertEqual(0 if mode == 'normal' else 1, receipt['result'])
                self.assertEqual(0, receipt['acceptanceReturnCode'])
                self.assertTrue(receipt['cleanupRequested'])
                self.assertFalse(receipt['unexpectedEmulatorExit'])
                if cleanup_error:
                    self.assertEqual('acceptance', receipt['cleanupErrors'][0]['owner'])
                    self.assertIn('controlled acceptance cleanup error', receipt['cleanupErrors'][0]['error'])
                else:
                    self.assertEqual([], receipt['cleanupErrors'])
                self.assertTrue(delayed_exit.is_file())
                self.assertEqual({'emulator', 'acceptance'}, set(children))
                self.assertEqual(1, sum('-gpu' in command for command in spawn_commands))
                self.assertEqual(2, len(spawn_commands))
                if phase == 'flutter-ui':
                    self.assertIn('apps/aerove-lite/tool/android_acceptance.py', spawn_commands[1])
                    self.assertIn('owned-platform.apk', spawn_commands[1])
                    self.assertEqual(spawn_commands[1][-2:], ['--phase', 'flutter-ui'])
                    self.assertNotIn('emulator_acceptance.sh', ' '.join(spawn_commands[1]))
                    self.assertIs(receipt['representativeOnly'], True)
                    self.assertIs(receipt['functionalAcceptance'], False)
                for name, child in children.items():
                    self.assertIsNotNone(child.poll(), name)
                    self.assertGreater(waits[name], 0, name)
                self.assertIsNone(unrelated.poll())
            finally:
                sys.settrace(previous_trace)
                for sig, handler in previous.items():
                    signal.signal(sig, handler)
                for child in children.values():
                    owners.stop_owned(child)
                    if child.stdin:
                        child.stdin.close()
                owners.stop_owned(unrelated)


    def test_second_signal_during_delayed_acceptance_cleanup_still_joins_both_owned_children(self):
        self.exercise_cancellation()

    def test_acceptance_cleanup_error_still_joins_emulator_and_writes_failed_receipt(self):
        self.exercise_cancellation(cleanup_error=True)


    def test_first_signal_at_final_atomic_write_entry_cannot_return_success(self):
        self.exercise_cancellation(mode='write_entry')

    def test_first_signal_at_main_return_event_cannot_return_success(self):
        self.exercise_cancellation(mode='return')

    def test_first_signal_at_cleanup_entry_does_not_interrupt_child_cleanup(self):
        self.exercise_cancellation(mode='cleanup_entry')

    def test_normal_main_still_joins_children_and_atomically_records_success(self):
        self.exercise_cancellation(mode='normal')

    def test_flutter_phase_cancellation_joins_same_two_children_without_orphans(self):
        self.exercise_cancellation(phase='flutter-ui')


class FlutterPhaseBoundaryTests(unittest.TestCase):
    def test_only_fixed_original_representative_proof_admits_flutter_ui(self):
        import emulator_original84 as original
        harness = dict(sourceRevision='f'*40, sourceTree='e'*40, workflowRun='900', runNumber=202, runAttempt=2)
        for target,pin in (('lite201-flutter-ui',original.LITE201_FLUTTER_UI), ('lite208-flutter-ui',original.LITE208_FLUTTER_UI)):
            proof = dict(runtimeTarget=target, diagnosticOriginalRuntime=True,
                originalAppIdentity=pin['identity'], harnessIdentity=harness,
                diagnosticOnly=True, representativeOnly=True, accepted=False, aggregateAcceptance=False, promotionAccepted=False)
            with tempfile.TemporaryDirectory() as temp:
                path = Path(temp) / 'proof.json'
                path.write_text(json.dumps(proof))
                with patch.dict(os.environ, {'AEROVE_ORIGINAL_RUNTIME_PROOF':str(path)}, clear=True), \
                        patch.object(current.artifacts, 'identity', return_value=harness):
                    current.guard_flutter_phase('flutter-ui', runtime=proof)
                    if target == 'lite208-flutter-ui':
                        current.guard_guest_angle_phase('flutter-ui', proof)
                    else:
                        with self.assertRaisesRegex(ValueError, 'another historical runtime'):
                            current.guard_guest_angle_phase('flutter-ui', proof)
                    for phase in ('flutter-platform','flutter-full'):
                        with self.subTest(phase=phase), self.assertRaises(ValueError):
                            current.guard_flutter_phase(phase, runtime=proof)
                        with self.assertRaises(ValueError):
                            current.guard_guest_angle_phase(phase, proof)
                    for key,value in (('runtimeTarget','lite158-frame-sample'), ('accepted',True),
                                      ('representativeOnly',1), ('harnessIdentity',dict(harness,runAttempt=1))):
                        changed = dict(proof, **{key:value})
                        path.write_text(json.dumps(changed))
                        with self.subTest(changed=key), self.assertRaises(ValueError):
                            current.guard_flutter_phase('flutter-ui', runtime=changed)
                    path.write_text(json.dumps(proof))
                    with patch.dict(os.environ, AEROVE_ORIGINAL_SAMPLE='solid'), self.assertRaises(ValueError):
                        current.guard_flutter_phase('flutter-ui', runtime=proof)

    def test_fixed_original_flutter_cannot_enter_old_matrix_owner(self):
        for target in ('lite201-flutter-ui','lite208-flutter-ui'):
            with patch.object(current.sys, 'platform', 'linux'), \
                    patch.object(current.sys, 'argv', ['owner','matrix','--runtime-directory','runtime']), \
                    patch.object(current, 'verify_current_runtime', return_value={'runtimeTarget':target}), \
                    patch.object(current.subprocess, 'Popen') as spawn:
                with self.assertRaisesRegex(ValueError, 'only the representative'):
                    current.main()
                spawn.assert_not_called()

    def test_original_optins_are_rejected_before_runtime_lookup_or_spawn(self):
        flags = ('AEROVE_ORIGINAL84_PROOF', 'AEROVE_ORIGINAL_RUNTIME_PROOF',
                 'AEROVE_ORIGINAL_RUNTIME_TARGET', 'AEROVE_ORIGINAL_SAMPLE',
                 'AEROVE_ORIGINAL_SAMPLE_SELECTOR', 'AEROVE_BETA14_PROFILE')
        for flag in flags:
            with self.subTest(flag=flag), patch.dict(os.environ, {flag: '1'}), \
                    patch.object(current.sys, 'platform', 'linux'), \
                    patch.object(current.sys, 'argv', ['owner', 'flutter-ui', '--runtime-directory', 'runtime']), \
                    patch.object(current, 'verify_current_runtime') as verify, \
                    patch.object(current.subprocess, 'Popen') as spawn:
                with self.assertRaisesRegex(ValueError, 'without original/profile/sample'):
                    current.main()
                verify.assert_not_called()
                spawn.assert_not_called()

    def test_flutter_sample_and_historical_runtime_are_rejected(self):
        with self.assertRaises(ValueError):
            current.guard_flutter_phase('flutter-ui', sample='solid')
        for runtime in ({'runtimeTarget': 'beta14'}, {'diagnosticOriginal84': True}, {'originalAppIdentity': {'source': 'old'}}):
            with self.subTest(runtime=runtime), self.assertRaises(ValueError):
                current.guard_flutter_phase('flutter-ui', runtime=runtime)
        with patch.object(current.sys, 'argv', ['owner', 'flutter-ui', '--runtime-directory', 'runtime', '--sample', 'solid']), \
                patch.object(current.subprocess, 'Popen') as spawn:
            with self.assertRaises(SystemExit):
                current.main()
            spawn.assert_not_called()


class OriginalSamplePhaseTests(unittest.TestCase):
    def test_lite158_phase_uses_only_its_fixed_parameterized_sample(self):
        runtime = {'runtimeTarget': 'lite158-frame-sample'}
        self.assertEqual('dev.aerove.lite.ui.LiteMaterialRenderingTest#threeMaterialsAndIndependentSwitchPlanesUseRealWindowPixels[Solid_ordinary_light_on_disabled]',
                         current.original_sample_phase_selector('original-sample', 'solid', runtime))
        for sample in ('glyph', 'clear', None):
            with self.subTest(sample=sample), self.assertRaises(ValueError):
                current.original_sample_phase_selector('original-sample', sample, runtime)

    def test_requires_one_fixed_lite125_sample_and_preserves_selector(self):
        runtime = {'runtimeTarget': 'lite125-original-samples'}
        self.assertEqual('dev.aerove.lite.ui.GlyphPaintedBoundsUiTest#actualArrowPaintIncludingAntialiasFitsTheSharedEnvelope',
                         current.original_sample_phase_selector('original-sample', 'glyph', runtime))
        self.assertIsNone(current.original_sample_phase_selector('matrix', None, runtime))
        with self.assertRaises(ValueError):
            current.original_sample_phase_selector('original-sample', None, runtime)
        with self.assertRaises(ValueError):
            current.original_sample_phase_selector('original-sample', 'glyph', {'runtimeTarget': 'lite94'})
        with self.assertRaises(ValueError):
            current.original_sample_phase_selector('matrix', 'glyph', runtime)

class SigillCaptureBoundaryTests(unittest.TestCase):
    def test_forensic_optin_cannot_enter_a_normal_current_or_full_owner(self):
        import sys
        for phase in ('matrix', 'flutter-full', 'flutter-ui'):
            with self.subTest(phase=phase), \
                    patch.dict(os.environ, AEROVE_EMULATOR_SIGILL_TRACE='1'), \
                    patch.object(current.sys, 'platform', 'linux'), \
                    patch.object(sys, 'argv', ['owner', phase, '--runtime-directory', 'fixture']), \
                    patch.object(current, 'verify_current_runtime', return_value={'identity': {}}), \
                    patch.object(current.subprocess, 'Popen') as spawn:
                with self.assertRaisesRegex(ValueError, 'restricted to the fixed original208'):
                    current.main()
                spawn.assert_not_called()


@unittest.skipUnless(os.name == 'posix' and os.environ.get('AEROVE_TEST_SIGILL_GDB') == '1',
                     'Explicit Linux CI GDB/ptrace fixture; never Android execution')
class SigillCaptureHostTests(unittest.TestCase):
    """Real GDB plus harmless owned host children, required before the opt-in SDK run."""
    def setUp(self):
        import shutil
        import sys
        import subprocess
        self.assertEqual('linux', sys.platform)
        self.assertIsNotNone(shutil.which('gdb'), 'Requested fixture requires CI GDB, never a skip')
        if os.environ.get('RUNNER_TEMP'):
            self.root = Path(os.environ['RUNNER_TEMP']) / 'lite-diagnostics' / 'sigill-host-tests' / self._testMethodName
            self.root.mkdir(parents=True, exist_ok=False)
        else:
            self.temp = tempfile.TemporaryDirectory()
            self.addCleanup(self.temp.cleanup)
            self.root = Path(self.temp.name)
        self.receipt = {'interruptSignals': [], 'unexpectedEmulatorExit': False,
                        'cleanupRequested': False}
        self.child = subprocess.Popen([sys.executable, '-u', '-c',
                                       'import sys\nfor line in sys.stdin:\n'
                                       ' if line.strip() == \"exit\": break\n'
                                       ' print(\"alive\", flush=True)\n'],
                                      stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                      stderr=subprocess.DEVNULL, start_new_session=True)
        # LIFO: debugger must detach before the existing owner stops its target.
        self.addCleanup(self.child.stdin.close)
        self.addCleanup(self.child.stdout.close)
        def stop_target_and_record_exit():
            before_stop = self.child.poll()
            try:
                current.stop_owned(self.child)
            finally:
                (self.root / 'target-exit.json').write_text(json.dumps(dict(
                    beforeOwnedStop=before_stop, afterOwnedStop=self.child.poll())) + '\n')
        self.addCleanup(stop_target_and_record_exit)
        self.receipt.update(targetProcessGroup=os.getpgid(self.child.pid), ownerProcessGroup=os.getpgrp())
        version = subprocess.run(['sudo', '-V'], capture_output=True, text=True, timeout=5)
        (self.root / 'sudo-version.out').write_text(version.stdout)
        (self.root / 'sudo-version.err').write_text(version.stderr)
        (self.root / 'sudo-version.exit').write_text(str(version.returncode) + '\n')
        self.assertEqual(0, version.returncode, version.stderr)
        self.capture = current.SigillCapture(self.child, self.root, self.receipt)
        self.addCleanup(current.write_owner_receipt, self.root / 'HOST-OWNER.json', self.receipt)
        self.addCleanup(self.capture.close)
        self.addCleanup(lambda: self.receipt.update(targetReturnCodeBeforeOwnedCleanup=self.child.poll()))

    def assert_target_executes(self):
        import selectors
        self.child.stdin.write(b'probe\n')
        self.child.stdin.flush()
        with selectors.DefaultSelector() as ready:
            ready.register(self.child.stdout, selectors.EVENT_READ)
            self.assertTrue(ready.select(timeout=5), 'Detached owned child did not execute its pipe acknowledgment')
            self.assertEqual(b'alive\n', self.child.stdout.readline())

    def logs(self):
        return '\n'.join(path.read_text(errors='replace') for path in self.root.glob('sigill-gdb.*.log'))

    def test_normal_child_exit_keeps_original_status_and_is_not_owner_acceptance(self):
        self.capture.start()
        self.child.stdin.write(b'exit\n')
        self.child.stdin.flush()
        self.assertEqual(0, self.child.wait(timeout=15), self.logs())
        self.capture.close()
        self.assertEqual(0, self.capture.child.returncode, self.logs())
        self.assertEqual(1, current.finish_emulator(self.child, self.receipt, 0))
        self.assertTrue(self.receipt['unexpectedEmulatorExit'])
        self.assertFalse(self.receipt['cleanupRequested'])
        self.assertFalse(self.capture.state['accepted'])

    def test_reaped_normal_target_allows_pending_gdb_finalization_before_owner_interrupt(self):
        import time
        release = self.root / 'allow-gdb-normal-exit'
        original = current.sigill_gdb_script
        marker = 'AEROVE_GDB_NORMAL_EXIT_PENDING'
        def delayed_finalization(*args):
            barrier = (
                'finally:\n'
                '    if not gdb.selected_inferior().pid:\n'
                f'        print({marker!r}, flush=True)\n'
                '        deadline = time.monotonic() + 10\n'
                f'        while not pathlib.Path({str(release)!r}).exists():\n'
                '            if time.monotonic() >= deadline:\n'
                '                raise RuntimeError("Test normal-exit barrier was not released")\n'
                '            time.sleep(.01)\n'
                '    if gdb.selected_inferior().pid:'
            )
            return original(*args).replace('finally:\n    if gdb.selected_inferior().pid:', barrier)
        with patch.object(current, 'sigill_gdb_script', side_effect=delayed_finalization):
            self.capture.start()
        self.child.stdin.write(b'exit\n')
        self.child.stdin.flush()
        self.assertEqual(0, self.child.wait(timeout=15), self.logs())
        deadline = time.monotonic() + 5
        while marker not in self.logs():
            self.assertIsNone(self.capture.child.poll(), self.logs())
            self.assertLess(time.monotonic(), deadline, self.logs())
            time.sleep(.01)
        self.assertIsNone(self.capture.child.poll(), self.logs())
        actual_wait = self.capture.child.wait
        def release_then_join(*args, **kwargs):
            release.touch()
            return actual_wait(*args, **kwargs)
        with patch.object(self.capture.child, 'wait', side_effect=release_then_join):
            self.capture.close()
        self.assertEqual(0, self.capture.child.returncode, self.logs())
        self.assertEqual([], self.capture.state['commands'])
        self.assertFalse(Path(self.capture.control['path']).exists())
        self.assertEqual(1, current.finish_emulator(self.child, self.receipt, 0))
        self.assertTrue(self.receipt['unexpectedEmulatorExit'])
        self.assertFalse(self.receipt['cleanupRequested'])
        self.assertFalse(self.capture.state['accepted'])
    def test_original_sigill_reaches_parent_even_if_one_diagnostic_read_fails(self):
        import signal
        original = current.sigill_gdb_script
        with patch.object(current, 'sigill_gdb_script', side_effect=lambda *args:
                          original(*args).replace('"info symbol $pc"', '"aerove-intentionally-unknown-read"')):
            self.capture.start()
        self.child.send_signal(signal.SIGILL)
        self.assertEqual(-signal.SIGILL, self.child.wait(timeout=15), self.logs())
        self.capture.close()
        self.assertEqual(0, self.capture.child.returncode, self.logs())
        self.assertIn('AEROVE_OWNED_SIGILL_CAPTURE pid=' + str(self.child.pid), self.logs())
        self.assertIn('AEROVE_GDB_READ_ERROR', self.logs())
        self.assertFalse(self.receipt['sigillForensics']['performanceEvidence'])
        self.assertFalse(self.receipt['sigillForensics']['accepted'])
        self.assertEqual(self.child.pid, json.loads(self.capture.fault.read_text())['targetPid'])

    def test_owner_cancellation_detaches_then_original_cleanup_joins_only_owned_target(self):
        import signal
        import subprocess
        import sys
        unrelated = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'],
                                     stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                     stderr=subprocess.DEVNULL, start_new_session=True)
        self.addCleanup(current.stop_owned, unrelated)
        self.capture.start()
        self.receipt['interruptSignals'].append(signal.SIGTERM)
        with self.assertRaises(InterruptedError):
            current.check_cancelled(self.receipt)
        self.capture.close()  # Cleanup deliberately ignores cancellation while detaching.
        self.assertIsNotNone(self.capture.child.poll(), self.logs())
        self.assert_target_executes()
        self.assertIsNone(self.child.poll())
        from beta14_render_profile import proc_stat
        self.assertNotIn(proc_stat((Path('/proc') / str(self.child.pid) / 'stat').read_text())['state'], ('t', 'T'))
        self.assertEqual(1, current.finish_emulator(self.child, self.receipt, 1))
        self.assertIsNotNone(self.child.poll())
        self.assertIsNone(unrelated.poll())
        self.assertTrue(self.receipt['cleanupRequested'])
        self.assertFalse(self.receipt['unexpectedEmulatorExit'])
        self.assertEqual([signal.SIGTERM], self.receipt['interruptSignals'])
        self.assertEqual(1, len(self.capture.state['commands']))
        for row in self.capture.state['commands']:
            if row['signal'] == 'INT':
                self.assertEqual([str(self.capture.state['gdbPid']), str(self.capture.state['gdbStarttime'])], row['command'][-2:])
                self.assertEqual(dict(signal=2, targetPid=self.capture.state['gdbPid'],
                                      targetStarttime=self.capture.state['gdbStarttime']), json.loads(row['stdout']))
            else:
                self.assertEqual('KILL', row['signal'])
                self.assertEqual('-' + str(self.capture.child.pid), row['command'][-1])
        owner_signals = [json.loads(line.removeprefix('AEROVE_GDB_OWNER_SIGNAL '))
                         for line in self.logs().splitlines() if line.startswith('AEROVE_GDB_OWNER_SIGNAL ')]
        self.assertEqual([dict(signo=2, sender=self.capture.state['gdbPid'], gdbPid=self.capture.state['gdbPid'])], owner_signals)

    def test_wrong_debugger_starttime_rejects_interrupt_without_signalling_another_identity(self):
        import subprocess
        self.capture.start()
        command = current.sigill_interrupt_command(self.capture.state['gdbPid'], self.capture.state['gdbStarttime'] + 1)
        result = subprocess.run(command, capture_output=True, text=True, timeout=5)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Owned debugger identity changed', result.stderr)
        self.assertEqual('', result.stdout)
        self.assertIsNone(self.capture.child.poll(), self.logs())
        self.assert_target_executes()
        self.assertNotIn('AEROVE_GDB_OWNER_SIGNAL ', self.logs())
        self.capture.close()
        self.assert_target_executes()

    def test_external_sigint_is_not_cleared_as_owner_control(self):
        import signal
        self.capture.start()
        self.assert_target_executes()
        # A valid request must not authenticate another process's signal.
        request = dict(token=self.capture.control['token'], targetPid=self.child.pid,
                       gdbPid=self.capture.state['gdbPid'])
        Path(self.capture.control['path']).write_text(json.dumps(request) + '\n')
        self.child.send_signal(signal.SIGINT)
        self.assertEqual(-signal.SIGINT, self.child.wait(timeout=15), self.logs())
        self.capture.close()
        self.assertNotIn('AEROVE_GDB_OWNER_CONTROL_CLEARED', self.logs())
        observed = [json.loads(line.removeprefix('AEROVE_GDB_OWNER_SIGNAL '))
                    for line in self.logs().splitlines()
                    if line.startswith('AEROVE_GDB_OWNER_SIGNAL ')]
        self.assertEqual(1, len(observed), self.logs())
        self.assertEqual(dict(signo=signal.SIGINT, sender=os.getpid(),
                              gdbPid=self.capture.state['gdbPid']), observed[0])
        self.assertNotEqual(observed[0]['sender'], observed[0]['gdbPid'])
        self.assertFalse(self.capture.state['accepted'])

    def test_failed_attach_is_terminal_and_does_not_leave_a_stopped_target(self):
        # A real failed sudo child exercises ready/exit handling without ptracing anything.
        with patch('shutil.which', return_value='/usr/bin/false'):
            with self.assertRaisesRegex(RuntimeError, 'GDB exited while emulator remained live'):
                self.capture.start()
        self.capture.close()
        self.assertIsNotNone(self.capture.child.poll())
        self.assertIsNone(self.child.poll())
        self.assertFalse(self.capture.ready.exists())
        self.assertEqual('failed', self.capture.state['status'])
        self.assertIsNotNone(current.stop_owned(self.child)['returnCode'])
    def test_sidecar_death_after_last_check_is_terminal_at_owner_close(self):
        import subprocess
        self.capture.start()
        self.capture.check()  # The last monitor check saw the actual debugger alive.
        result = subprocess.run(['sudo', '-n', '--', 'kill', '-KILL', '--',
                                 '-' + str(self.capture.child.pid)],
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotEqual(0, self.capture.child.wait(timeout=15), self.logs())
        self.assertIsNone(self.child.poll())
        with self.assertRaisesRegex(RuntimeError, 'GDB exited before owner cleanup'):
            self.capture.close()
        self.assertTrue(self.capture.state['cleanupClosed'])
        self.assertTrue(self.capture.state['unexpectedExitBeforeCleanup'])
        self.assertEqual('failed', self.capture.state['status'])
        self.capture.close()  # Already-cleaned retry must not replace the first error.
        self.assert_target_executes()
        from beta14_render_profile import proc_stat
        self.assertNotIn(proc_stat((Path('/proc') / str(self.child.pid) / 'stat').read_text())['state'], ('t', 'T'))
        # main records the close exception in cleanupErrors and retains failure through
        # this same original emulator finalizer, even when acceptance had returned zero.
        self.assertEqual(1, current.finish_emulator(self.child, self.receipt, 1))
        self.assertIsNotNone(self.child.poll())
        self.assertFalse(self.capture.state['accepted'])
        self.assertFalse(self.capture.state['performanceEvidence'])
