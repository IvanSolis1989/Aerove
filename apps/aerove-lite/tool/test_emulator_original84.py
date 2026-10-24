"""Identity and extraction fixtures only; no Android or production APK execution."""
import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
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
                    min_sdk=33, signer_sha256=original.PRODUCT['signerSha256'])
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
                mock.patch.object(owner.sys, 'argv', ['snapshot', str(output), 'matrix', '600000', 'emulator-5554']):
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
        for identity in (original.ORIGINAL, self.harness):
            with self.assertRaises(original.release.PromotionError):
                original.release._verify_matrix(self.root, self.runtime, identity['sourceRevision'],
                    identity['sourceTree'], identity['workflowRun'], identity['runAttempt'], identity['runNumber'])

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
        signature = ('Verifies\nVerified using v2 scheme (APK Signature Scheme v2): true\n'
                     'Number of signers: 1\nSigner #1 certificate SHA-256 digest: ' + signer + '\n')
        badging = "package: name='dev.aerove.lite.test' versionCode='1' versionName=''\nsdkVersion:'33'\n"
        target = 'android:targetPackage(0x01010021)="dev.aerove.lite"'
        app = dict(application_id=original.PRODUCT['applicationId'], version=original.PRODUCT['version'],
                   version_code=10, min_sdk=33, signer_sha256=signer)
        def inspect(text=badging, cert=signature, xml=target):
            def output(command, **kwargs):
                if 'xmltree' in command:
                    return xml
                if 'verify' in command:
                    return cert
                return text
            with mock.patch.dict(os.environ, ANDROID_HOME=str(self.root)), \
                    mock.patch.object(original.subprocess, 'check_output', side_effect=output), \
                    mock.patch.object(original, 'verify_identity', return_value=app) as verify:
                value = original.inspect_packages(self.runtime, self.root / 'inspection')
                verify.assert_called_once_with(text, cert, original.PRODUCT, 'preview')
                return value
        result = inspect()
        self.assertEqual('dev.aerove.lite', result['app-release-androidTest.apk']['target_package'])
        modern = signature.replace('Signer #1 certificate', 'V2 Signer: certificate')
        self.assertEqual(signer, inspect(cert=modern)['app-release-androidTest.apk']['signer_sha256'])
        for text, cert, xml in [(badging.replace('.test', '.other'), signature, target),
                                (badging, signature, target.replace('.lite', '.other')),
                                (badging, signature.replace(signer, '0' * 64), target),
                                (badging, signature.replace('true', 'false'), target),
                                (badging, modern + 'Number of signers: 2\n', target),
                                (badging, modern + 'Verified using v2 scheme (APK Signature Scheme v2): false\n', target),
                                (badging, modern + 'Signer #1 certificate SHA-256 digest: ' + signer + '\n', target),
                                (badging.replace("'33'", "'32'"), signature, target)]:
            with self.subTest(text=text, cert=cert, xml=xml), self.assertRaises(ValueError):
                inspect(text, cert, xml)


if __name__ == '__main__':
    unittest.main()
