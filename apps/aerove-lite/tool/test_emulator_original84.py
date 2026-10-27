"""Identity and extraction fixtures only; no Android or production APK execution."""
import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
import subprocess
import sys
from unittest import mock
import zipfile

import emulator_original84 as original
import emulator_current as owner
REAL_FILE_VALIDATOR = original.validate_original_files


class Original84Tests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.runtime = self.root / 'runtime'
        self.runtime.mkdir()
        self.apkroot = self.root / 'installed'
        self.harness = dict(sourceRevision='f' * 40, sourceTree='e' * 40,
                            workflowRun='900', runNumber=91, runAttempt=2)
        hashes = {}
        for name, location in [('app-release.apk', 'release'), ('app-release-androidTest.apk', 'androidTest/release')]:
            data = ('fixture-' + name).encode()
            path = self.apkroot / location / name
            path.parent.mkdir(parents=True)
            path.write_bytes(data)
            hashes[name] = hashlib.sha256(data).hexdigest()
        self.addCleanup(mock.patch.stopall)
        mock.patch.object(original, 'APK_ROOT', self.apkroot).start()
        mock.patch.object(original, 'APKS', hashes).start()
        mock.patch.object(original.artifacts, 'identity', return_value=self.harness).start()
        mock.patch.object(original, 'validate_original_files', return_value={}).start()
        app = dict(application_id=original.PRODUCT['applicationId'], version=original.PRODUCT['version'],
                   version_code=10, min_sdk=33, signer_sha256=original.PRODUCT['signerSha256'])
        test = dict(application_id=original.PRODUCT['applicationId'] + '.test', target_package=original.PRODUCT['applicationId'],
                    min_sdk=33, signer_sha256=original.PRODUCT['signerSha256'], version_code=None, version=None,
                    version_attributes_declared=False)
        self.proof = dict(schema=1, diagnosticOriginal84=True, originalAppIdentity=original.ORIGINAL,
                          harnessIdentity=self.harness, promotionAccepted=False,
                          artifactId=original.ARTIFACT_ID, artifactZipSha256=original.ZIP_SHA256,
                          runtimeManifestSha256=original.MANIFEST_SHA256, apks=hashes,
                          packages={'app-release.apk': app, 'app-release-androidTest.apk': test},
                          runtimeDirectory=str(self.runtime))
        self.path = self.root / 'proof.json'
        self.write()
        mock.patch.dict(os.environ, AEROVE_ORIGINAL84_PROOF=str(self.path), ANDROID_SERIAL='emulator-5554',
                        GITHUB_SHA='f' * 40, GITHUB_RUN_ID='900', GITHUB_RUN_NUMBER='91', GITHUB_RUN_ATTEMPT='2').start()

    def write(self):
        self.path.write_text(json.dumps(self.proof))

    def renderer(self):
        value = original.verify_original_runtime(self.runtime)
        return dict(value, schema=1, owner='emulator_current.py', deviceSerial='emulator-5554',
                    gpuBackend='swangle', rendererBackend='SwANGLE (ANGLE + SwiftShader)',
                    rendererEvidence=['Graphics Adapter ANGLE (Google SwiftShader)'],
                    runtimeDirectory=str(self.runtime))

    def test_original_identity_and_current_harness_remain_distinct(self):
        result = owner.verify_current_runtime(self.runtime)
        self.assertEqual(original.ORIGINAL, result['identity'])
        self.assertEqual(self.harness, result['harnessIdentity'])
        self.assertIs(result['promotionAccepted'], False)
        self.assertEqual('f' * 40, os.environ['GITHUB_SHA'])
        self.assertEqual('900', os.environ['GITHUB_RUN_ID'])

    def test_lite125_fixed_original_runtime_and_three_precise_test_selectors(self):
        spec = original.runtime_spec('lite125-original-samples')
        self.assertEqual('d52726dc24756847b2b268d307a611cc37f994a3', spec['identity']['sourceRevision'])
        self.assertEqual('2dbd4a0c5463a24a826ffde19eace43ed229a1fc', spec['identity']['sourceTree'])
        self.assertEqual(40335340043, int(spec['identity']['workflowRun']))
        self.assertEqual(12656749144, spec['artifact'])
        self.assertEqual('8ac16086a91edcfa75cd037bba658f09e088c4dc105885af66af7442a9ddce6d', spec['zipSha256'])
        self.assertEqual('ad5b09bcd20312cafd64bed39d4c508c1a463063686aa0ed0236c51d1b9fa701',
                         spec['apks']['app-release.apk'])
        self.assertEqual('1e80bccab828e7e827f91b2d6e8d7ef5076e8a3875d736fbe4ea3e512a68f63b',
                         spec['apks']['app-release-androidTest.apk'])
        self.assertEqual('dev.aerove.lite', spec['product']['applicationId'])
        self.assertEqual('0.1.0-beta.16', spec['product']['version'])
        self.assertEqual(16, int(spec['product']['versionCode']))
        self.assertEqual('dev.aerove.lite.ui.GlyphPaintedBoundsUiTest#actualArrowPaintIncludingAntialiasFitsTheSharedEnvelope',
                         spec['samples']['glyph']['selector'])
        self.assertEqual('dev.aerove.lite.ui.LiteMaterialRenderingTest#threeMaterialsAndIndependentSwitchPlanesUseRealWindowPixels[Solid_ordinary_light_on_enabled]',
                         spec['samples']['solid']['selector'])
        self.assertEqual('dev.aerove.lite.ui.LiteMaterialRenderingTest#threeMaterialsAndIndependentSwitchPlanesUseRealWindowPixels[Clear_ordinary_dark_off_enabled]',
                         spec['samples']['clear']['selector'])
        for sample in ('glyph', 'solid', 'clear'):
            row = spec['samples'][sample]
            self.assertTrue(row['description'].startswith(row['testName']))
            self.assertEqual(row['className'] + '#' + row['testName'], row['selector'])
        with self.assertRaises(ValueError):
            original.original_sample_selector('matrix')

    def test_lite125_sample_proof_is_diagnostic_and_cannot_be_accepted(self):
        spec = original.runtime_spec('lite125-original-samples')
        proof = dict(self.proof)
        proof.pop('diagnosticOriginal84')
        proof.update(diagnosticOriginalRuntime=True, runtimeTarget='lite125-original-samples',
                     originalAppIdentity=spec['identity'], artifactId=spec['artifact'],
                     artifactZipSha256=spec['zipSha256'], runtimeManifestSha256=spec['manifestSha256'],
                     apks=spec['apks'], diagnosticOnly=True, accepted=False)
        proof['packages']['app-release.apk'].update(
            version=spec['product']['version'], version_code=int(spec['product']['versionCode']))
        path = self.root / 'lite125-proof.json'
        path.write_text(json.dumps(proof))
        with mock.patch.dict(os.environ, AEROVE_ORIGINAL_RUNTIME_PROOF=str(path)):
            os.environ.pop('AEROVE_ORIGINAL84_PROOF', None)
            with mock.patch.object(original, 'sha', side_effect=lambda value: spec['apks'][Path(value).name]):
                result = original.verify_original_runtime(self.runtime)
                self.assertEqual(spec['identity'], result['identity'])
            proof['accepted'] = True
            path.write_text(json.dumps(proof))
            with self.assertRaisesRegex(ValueError, 'identity differs'):
                original.verify_original_runtime(self.runtime)

    def test_wrong_original_harness_artifact_and_signer_fail(self):
        for field, value in [('originalAppIdentity', self.harness), ('harnessIdentity', original.ORIGINAL),
                             ('artifactId', 12548174624), ('promotionAccepted', True), ('artifactZipSha256', '0' * 64)]:
            saved = self.proof[field]
            self.proof[field] = value
            self.write()
            with self.subTest(field=field), self.assertRaises(ValueError):
                original.verify_original_runtime(self.runtime)
            self.proof[field] = saved
        self.proof['packages']['app-release-androidTest.apk']['signer_sha256'] = '0' * 64
        self.write()
        with self.assertRaises(ValueError):
            original.verify_original_runtime(self.runtime)

    def test_changed_installed_apk_is_rejected(self):
        (self.apkroot / 'release/app-release.apk').write_bytes(b'changed')
        with self.assertRaises(ValueError):
            original.verify_original_runtime(self.runtime)

    def test_changed_original_manifest_bytes_fail_before_any_other_validation(self):
        (self.runtime / 'source-manifest.json').write_bytes(b'changed fixture manifest')
        with self.assertRaisesRegex(ValueError, 'manifest bytes changed'):
            REAL_FILE_VALIDATOR(self.runtime)

    def test_current_renderer_proof_must_confirm_real_backend_and_both_identities(self):
        proof = self.renderer()
        path = self.root / 'renderer.json'
        path.write_text(json.dumps(proof))
        self.assertEqual(original.ORIGINAL, owner.validate_renderer_proof(path)['identity'])
        proof['rendererEvidence'] = ['Graphics Adapter Google SwiftShader']
        path.write_text(json.dumps(proof))
        with self.assertRaises(ValueError):
            owner.validate_renderer_proof(path)

    def test_shell_snapshot_preserves_both_identities_and_cannot_be_promoted(self):
        path = self.root / 'renderer.json'
        path.write_text(json.dumps(self.renderer()))
        shell = Path(__file__).with_name('emulator_acceptance.sh').read_text()
        program = shell.split("<<'PY'\n", 1)[1].split('\nPY\n', 1)[0]
        output = self.root / 'SOURCE.json'
        with mock.patch.dict(os.environ, AEROVE_CURRENT_RENDERER_PROOF=str(path)), \
                mock.patch.object(owner.sys, 'argv', ['snapshot', str(output), 'matrix', '600000', 'emulator-5554', '', '', 'dev.aerove.lite.ui.LiteDashboardMatrixTest']):
            os.environ.pop('AEROVE_QA79_PROOF', None)
            exec(compile(program, 'original84-source-snapshot', 'exec'), {})
        source = json.loads(output.read_text())
        self.assertEqual(original.ORIGINAL['sourceRevision'], source['sourceRevision'])
        self.assertEqual(original.ORIGINAL['sourceTree'], source['sourceTree'])
        self.assertEqual(original.ORIGINAL['workflowRun'], source['appBuildRun'])
        self.assertEqual(self.harness['sourceRevision'], source['harnessRevision'])
        self.assertEqual('900', source['workflowRun'])
        self.assertEqual(2, source['runAttempt'])
        self.assertIs(source['promotionAccepted'], False)
        self.assertIsNone(source['testShard'])
        for identity in (original.ORIGINAL, self.harness):
            with self.assertRaises(original.release.PromotionError):
                original.release._verify_matrix(self.root, self.runtime, identity['sourceRevision'],
                    identity['sourceTree'], identity['workflowRun'], identity['runAttempt'], identity['runNumber'])

    def test_lite141_shell_snapshot_keeps_original_app_identity_and_current_harness_separate(self):
        from emulator_original84 import LITE141_PREBUILT_SHARDS
        spec = LITE141_PREBUILT_SHARDS
        dependency_rel = 'dependencies/releaseAndroidTestRuntimeClasspath.stdout.txt'
        dependency = self.runtime / dependency_rel
        dependency.parent.mkdir(parents=True, exist_ok=True)
        dependency.write_text('androidx.test:runner:1.6.2 -> 1.7.0\njunit:junit:4.13.2\n')
        dep_sha = hashlib.sha256(dependency.read_bytes()).hexdigest()
        manifest = dict(schema=1, **spec['identity'], files=[dict(path=dependency_rel, sha256=dep_sha)])
        manifest_bytes = json.dumps(manifest).encode()
        manifest_sha = hashlib.sha256(manifest_bytes).hexdigest()
        (self.runtime / 'source-manifest.json').write_bytes(manifest_bytes)
        current_harness = dict(sourceRevision='a' * 40, sourceTree='b' * 40,
            workflowRun='500', runNumber=200, runAttempt=2)
        original_proof = dict(originalAppIdentity=spec['identity'], harnessIdentity=current_harness,
            diagnosticOriginalRuntime=True, runtimeTarget='lite141-prebuilt-shards', diagnosticOnly=True,
            accepted=False, promotionAccepted=False, aggregateAcceptance=False,
            artifactId=spec['artifact'], artifactZipSha256=spec['zipSha256'],
            runtimeManifestSha256=spec['manifestSha256'], apks=spec['apks'])
        proof_path = self.root / 'original-proof.json'
        proof_path.write_text(json.dumps(original_proof))
        renderer_path = self.root / 'renderer.json'
        renderer_path.write_text('{}')
        renderer = dict(originalAppIdentity=spec['identity'], harnessIdentity=current_harness,
            diagnosticOriginalRuntime=True, runtimeTarget='lite141-prebuilt-shards', runtimeManifestSha256=manifest_sha,
            apks=spec['apks'], rendererBackend='SwANGLE (ANGLE + SwiftShader)',
            runtimeDirectory=str(self.runtime))
        shell = Path(__file__).with_name('emulator_acceptance.sh').read_text()
        program = shell.split("<<'PY'\n", 1)[1].split('\nPY\n', 1)[0]
        output = self.root / 'SOURCE.json'
        env = dict(AEROVE_CURRENT_RENDERER_PROOF=str(renderer_path),
            AEROVE_ORIGINAL_RUNTIME_PROOF=str(proof_path), RUNNER_TEMP=str(self.root),
            GITHUB_SHA=current_harness['sourceRevision'], GITHUB_RUN_ID=current_harness['workflowRun'],
            GITHUB_RUN_NUMBER=str(current_harness['runNumber']), GITHUB_RUN_ATTEMPT=str(current_harness['runAttempt']))
        with mock.patch.dict(os.environ, env, clear=False), \
                mock.patch.object(owner, 'validate_renderer_proof', return_value=renderer), \
                mock.patch('subprocess.check_output', return_value=current_harness['sourceTree'] + '\n'), \
                mock.patch.object(owner.sys, 'argv', ['snapshot', str(output), 'remaining', '120000',
                    'emulator-5554', '8', '1', 'dev.aerove.lite.ui.LiteDashboardMatrixTest']):
            exec(compile(program, 'lite141-source-snapshot', 'exec'), {})
        source = json.loads(output.read_text())
        self.assertEqual(spec['identity'], source['originalAppIdentity'])
        self.assertEqual(spec['identity']['sourceRevision'], source['sourceRevision'])
        self.assertEqual(spec['identity']['workflowRun'], source['workflowRun'])
        self.assertEqual(current_harness, source['harnessIdentity'])
        self.assertIs(source['diagnosticOnly'], True)
        self.assertIs(source['accepted'], False)
        self.assertIs(source['promotionAccepted'], False)
        self.assertIs(source['aggregateAcceptance'], False)
        self.assertEqual(manifest_sha, source['runtimeManifestSha256'])

    def _lite125_api_rows(self, spec, **run_changes):
        identity = spec['identity']
        artifact = dict(id=spec['artifact'], name='AEROVE-Lite-runtime-contract-attempt-1', expired=False,
                        digest='sha256:' + spec['zipSha256'], size_in_bytes=spec['zipBytes'],
                        workflow_run=dict(id=int(identity['workflowRun']), head_sha=identity['sourceRevision']))
        run = dict(id=int(identity['workflowRun']), head_sha=identity['sourceRevision'],
                   run_number=int(identity['runNumber']), run_attempt=int(identity['runAttempt']), status='completed')
        run.update(run_changes)
        return artifact, run

    def test_lite125_prepare_accepts_fixed_run125_to_safe_download_boundary(self):
        spec = original.runtime_spec('lite125-original-samples')
        artifact, run = self._lite125_api_rows(spec)
        calls = []
        class DownloadBoundary(Exception):
            pass
        def api(route):
            calls.append(route)
            return artifact if route.endswith('/artifacts/' + str(spec['artifact'])) else run
        def stop_at_download(route, destination):
            calls.append(route)
            raise DownloadBoundary()
        with mock.patch.object(original.release, '_gh_json', side_effect=api), \
                mock.patch.object(original.release, '_gh_binary', side_effect=stop_at_download):
            with self.assertRaises(DownloadBoundary):
                original.prepare(self.root / 'lite125-runtime', self.root / 'lite125-evidence',
                                 target='lite125-original-samples')
        self.assertEqual(3, len(calls))
        self.assertIn('/runs/40335340043', calls[1])
        self.assertIn('/artifacts/12656749144/zip', calls[2])
        self.assertTrue((self.root / 'lite125-evidence/lite125-original-artifact-api.json').is_file())

    def test_lite125_prepare_rejects_wrong_run_identity_before_download(self):
        spec = original.runtime_spec('lite125-original-samples')
        for changes in ({'run_number': 107}, {'run_attempt': 2}, {'head_sha': '0' * 40},
                        {'id': 40335340044}, {'status': 'in_progress'}):
            artifact, run = self._lite125_api_rows(spec, **changes)
            routes = []
            def api(route):
                routes.append(route)
                return artifact if '/artifacts/' in route else run
            with self.subTest(changes=changes), \
                    mock.patch.object(original.release, '_gh_json', side_effect=api), \
                    mock.patch.object(original.release, '_gh_binary') as download:
                with self.assertRaisesRegex(ValueError, 'lite125-original-samples original run API identity'):
                    original.prepare(self.root / 'lite125-runtime', self.root / 'lite125-evidence',
                                     target='lite125-original-samples')
                download.assert_not_called()
                self.assertEqual(2, len(routes))

    def test_lite141_prebuilt_prepare_checks_fixed_artifact_run_and_producer_before_download(self):
        spec = original.runtime_spec('lite141-prebuilt-shards')
        identity = spec['identity']
        artifact = dict(id=spec['artifact'], name=spec['artifactName'], expired=False,
            digest='sha256:' + spec['zipSha256'], size_in_bytes=spec['zipBytes'],
            workflow_run=dict(id=int(identity['workflowRun']), head_sha=identity['sourceRevision']))
        run = dict(id=int(identity['workflowRun']), head_sha=identity['sourceRevision'],
            run_number=identity['runNumber'], run_attempt=identity['runAttempt'], status='completed')
        job = dict(id=spec['producerJob'], run_id=int(identity['workflowRun']), status='completed', conclusion='success')
        calls = []
        class DownloadBoundary(Exception):
            pass
        def api(route):
            calls.append(route)
            if '/artifacts/' in route: return artifact
            if '/runs/' in route: return run
            return job
        def stop(route, destination):
            calls.append(route)
            raise DownloadBoundary()
        with mock.patch.object(original.release, '_gh_json', side_effect=api), \
                mock.patch.object(original.release, '_gh_binary', side_effect=stop):
            with self.assertRaises(DownloadBoundary):
                original.prepare(self.root / 'lite141-runtime', self.root / 'lite141-evidence',
                                 target='lite141-prebuilt-shards')
        self.assertEqual(4, len(calls))
        self.assertIn('/runs/40445440882', calls[1])
        self.assertIn('/jobs/121996423095', calls[2])
        self.assertIn('/artifacts/12708203167/zip', calls[3])
        for changes in ({'run_number': 140}, {'run_attempt': 2}, {'head_sha': '0' * 40},
                        {'id': 40445440883}, {'status': 'in_progress'}):
            bad_run = dict(run, **changes)
            routes = []
            def changed_api(route):
                routes.append(route)
                if '/artifacts/' in route: return artifact
                if '/runs/' in route: return bad_run
                return job
            with self.subTest(changes=changes), \
                    mock.patch.object(original.release, '_gh_json', side_effect=changed_api), \
                    mock.patch.object(original.release, '_gh_binary') as download, \
                    self.assertRaisesRegex(ValueError, 'lite141-prebuilt-shards original run API identity'):
                original.prepare(self.root / 'lite141-runtime', self.root / 'lite141-evidence',
                                 target='lite141-prebuilt-shards')
            download.assert_not_called()
            self.assertEqual(2, len(routes))
        bad_job = dict(job, conclusion='failure')
        routes = []
        with mock.patch.object(original.release, '_gh_json', side_effect=lambda route: (routes.append(route), artifact if '/artifacts/' in route else run if '/runs/' in route else bad_job)[1]), \
                mock.patch.object(original.release, '_gh_binary') as download, \
                self.assertRaisesRegex(ValueError, 'producer job identity or result'):
            original.prepare(self.root / 'lite141-runtime', self.root / 'lite141-evidence',
                             target='lite141-prebuilt-shards')
        download.assert_not_called()

    def test_lite141_target_rejects_other_phase_and_invalid_shard_index(self):
        for argv in (['tool', '--target', 'lite141-prebuilt-shards', '--directory', 'x'],
                     ['tool', '--target', 'lite141-prebuilt-shards', '--phase', 'matrix', '--shard-index', '1', '--directory', 'x'],
                     ['tool', '--target', 'lite141-prebuilt-shards', '--phase', 'remaining', '--shard-index', '8', '--directory', 'x']):
            with self.subTest(argv=argv), mock.patch.object(sys, 'argv', argv), self.assertRaises(SystemExit):
                original.main()

    def test_beta14_prepare_keeps_fixed_run107_and_rejects_other_identity(self):
        beta_spec = dict(identity=dict(sourceRevision='e56b8a6299e5bde9e1d7e2199987a1690b2132ed',
                            sourceTree='f84296bf554a90edc5ecbed23fbdfeb078b628a2',
                            workflowRun='40142862169', runNumber=107, runAttempt=1),
                         artifact=12579159019, zipBytes=34663270,
                         zipSha256='ae6e7535f3040ab627348a3bf0a244840a91cd96dea3d2ca9dfccb39b546830d')
        artifact, run = self._lite125_api_rows(beta_spec)
        class DownloadBoundary(Exception):
            pass
        import types
        runtime = types.SimpleNamespace(SPEC=beta_spec, api_json=lambda route: artifact if '/artifacts/' in route else run,
                                        download=lambda route, destination: (_ for _ in ()).throw(DownloadBoundary()))
        with mock.patch.dict(sys.modules, beta14_runtime=runtime):
            with self.assertRaises(DownloadBoundary):
                original.prepare(self.root / 'beta14-runtime', self.root / 'beta14-evidence', target='beta14')
        self.assertTrue((self.root / 'beta14-evidence/original107-run-api.json').is_file())
        for changes in ({'run_number': 125}, {'run_attempt': 2}, {'head_sha': '0' * 40},
                        {'id': 40142862170}, {'status': 'in_progress'}):
            artifact, run = self._lite125_api_rows(beta_spec, **changes)
            runtime = types.SimpleNamespace(SPEC=beta_spec,
                api_json=lambda route, a=artifact, r=run: a if '/artifacts/' in route else r,
                download=mock.Mock())
            with mock.patch.dict(sys.modules, beta14_runtime=runtime):
                with self.subTest(changes=changes), self.assertRaisesRegex(ValueError, 'beta14 original run API identity'):
                    original.prepare(self.root / 'beta14-runtime', self.root / 'beta14-evidence', target='beta14')
            runtime.download.assert_not_called()

    def test_bad_api_identity_fails_before_any_download(self):
        for row in ({}, {'id': original.ARTIFACT_ID, 'name': 'wrong'}):
            with mock.patch.object(original.release, '_gh_json', return_value=row), \
                    mock.patch.object(original.release, '_gh_binary') as download:
                with self.assertRaises(ValueError):
                    original.prepare(self.root / 'new-runtime', self.root / 'evidence')
                download.assert_not_called()

    def test_archive_digest_and_path_validation_are_real(self):
        archive = self.root / 'bad.zip'
        with zipfile.ZipFile(archive, 'w') as source:
            source.writestr('../escape', b'unsafe fixture')
        with self.assertRaises(ValueError):
            original.checked_archive(archive, self.root / 'extracted')
        with mock.patch.object(original, 'ZIP_SHA256', original.sha(archive)):
            with self.assertRaises(ValueError):
                original.checked_archive(archive, self.root / 'extracted')
        self.assertFalse((self.root / 'escape').exists())

    def test_package_inspection_checks_original_app_and_instrumentation(self):
        signer = original.PRODUCT['signerSha256']
        fixtures = Path(__file__).with_name('fixtures') / 'original84'
        signature = (fixtures / 'app-release-androidTest.apk.signature.txt').read_text()
        badging = (fixtures / 'app-release-androidTest.apk.badging.txt').read_text()
        target = (fixtures / 'app-release-androidTest.apk.xmltree.txt').read_text()
        app = dict(application_id=original.PRODUCT['applicationId'], version=original.PRODUCT['version'],
                   version_code=10, min_sdk=33, signer_sha256=signer)
        def inspect(text=badging, cert=signature, xml=target):
            def output(command, **kwargs):
                value = xml if 'xmltree' in command else cert if 'verify' in command else text
                return subprocess.CompletedProcess(command, 0, value, '')
            with mock.patch.dict(os.environ, ANDROID_HOME=str(self.root)), \
                    mock.patch.object(original.subprocess, 'run', side_effect=output), \
                    mock.patch.object(original, 'verify_identity', return_value=app) as verify:
                value = original.inspect_packages(self.runtime, self.root / 'inspection')
                verify.assert_called_once_with(text, cert, original.PRODUCT, 'preview')
                return value
        result = inspect()
        self.assertEqual('dev.aerove.lite', result['app-release-androidTest.apk']['target_package'])
        self.assertIsNone(result['app-release-androidTest.apk']['version_code'])
        self.assertIsNone(result['app-release-androidTest.apk']['version'])
        self.assertIs(result['app-release-androidTest.apk']['version_attributes_declared'], False)
        modern = signature.replace('Signer #1 certificate', 'V2 Signer: certificate')
        self.assertEqual(signer, inspect(cert=modern)['app-release-androidTest.apk']['signer_sha256'])
        for text, cert, xml in [(badging.replace('.test', '.other'), signature, target),
                                (badging, signature, target.replace('.lite', '.other')),
                                (badging, signature, target.replace('AndroidJUnitRunner', 'OtherRunner')),
                                (badging, signature, target + '\n      A: android:targetPackage(0x01010021)="dev.aerove.lite"\n'),
                                (badging, signature, target + '\n    A: android:versionCode(0x0101021b)=(type 0x10)0x1\n'),
                                (badging, signature.replace(signer, '0' * 64), target),
                                (badging, signature.replace('true', 'false'), target),
                                (badging, modern + 'Number of signers: 2\n', target),
                                (badging, modern + 'Verified using v2 scheme (APK Signature Scheme v2): false\n', target),
                                (badging, modern + 'Signer #1 certificate SHA-256 digest: ' + signer + '\n', target),
                                (badging.replace("versionCode=''", "versionCode='1'"), signature, target),
                                (badging.replace("'33'", "'32'"), signature, target)]:
            with self.subTest(text=text, cert=cert, xml=xml), self.assertRaises(ValueError):
                inspect(text, cert, xml)
            self.assertEqual(text, (self.root / 'inspection/app-release-androidTest.apk.badging.txt').read_text())
            self.assertEqual(0, json.loads((self.root / 'inspection/app-release-androidTest.apk.badging.command.json').read_text())['returncode'])

    def test_actual_sdk_badging_rejects_old_parser_and_is_saved_before_rejection(self):
        fixtures = Path(__file__).with_name('fixtures') / 'original84'
        badging = (fixtures / 'app-release-androidTest.apk.badging.txt').read_text()
        self.assertIsNone(original.re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'", badging, original.re.M))
        reports = self.root / 'failed-reader'
        reports.mkdir()
        completed = subprocess.CompletedProcess(['actual-reader-fixture'], 7, badging, 'original command failure')
        with mock.patch.object(original.subprocess, 'run', return_value=completed), self.assertRaises(subprocess.CalledProcessError) as caught:
            original.inspection_command(['reader'], reports, 'test.apk', 'badging')
        self.assertEqual(7, caught.exception.returncode)
        self.assertEqual(badging, (reports / 'test.apk.badging.txt').read_text())
        self.assertEqual('original command failure', (reports / 'test.apk.badging.stderr.txt').read_text())
        self.assertEqual(7, json.loads((reports / 'test.apk.badging.command.json').read_text())['returncode'])

    def test_reader_timeout_retains_original_error_and_partial_outputs(self):
        reports = self.root / 'timeout-reader'
        reports.mkdir()
        error = subprocess.TimeoutExpired(['reader'], 60, output=b'partial output', stderr=b'partial error')
        with mock.patch.object(original.subprocess, 'run', side_effect=error), self.assertRaises(subprocess.TimeoutExpired) as caught:
            original.inspection_command(['reader'], reports, 'test.apk', 'signature')
        self.assertIs(caught.exception, error)
        self.assertEqual('partial output', (reports / 'test.apk.signature.txt').read_text())
        self.assertTrue(json.loads((reports / 'test.apk.signature.command.json').read_text())['timeout'])


if __name__ == '__main__':
    unittest.main()
