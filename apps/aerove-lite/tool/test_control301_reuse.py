"""Fixed301 adoption parser/identity tests. No SDK commands, signing or device execution."""
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import flutter_hello_control as control

REPORT='''Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): false
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v3.2 scheme (APK Signature Scheme v3.2): false
Verified using v4 scheme (APK Signature Scheme v4): false
Number of signers: 1
V3.0 Signer: certificate SHA-256 digest: 2ef6e74aea287af187c7440a2e287daa330e5f02f9faa7efcaaea0e1e0e108d9
'''
class Signed301Boundaries(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.apk=Path(self.tmp.name)/'runner.apk';self.apk.write_bytes(b'fixture-not-executed'.ljust(41363,b'0'))
    def environment(self):
        return dict(AEROVE_FLUTTER_HELLO_CONTROL='1',AEROVE_FLUTTER_HELLO_SIGNED301='1',AEROVE_GUEST_ANGLE='1',
            GITHUB_REPOSITORY='IvanSolis1989/Aerove',GITHUB_ACTOR='IvanSolis1989',GITHUB_EVENT_NAME='workflow_dispatch',
            GITHUB_REF='refs/heads/codex/flutter-onscreen-control-20261107')
    def test_private_v3_reader_preserves_actual_format_without_forging_v2(self):
        with patch.object(control,'sha',return_value=control.RUNNER301_SHA):
            self.assertEqual(control.CERT301_SHA,control.fixed301_runner_certificate(REPORT,self.apk,control.PRODUCER301))
        with self.assertRaises(ValueError):control.signing_certificate(REPORT)
    def test_parser_rejects_changed_hash_producer_size_or_multiple_signers(self):
        with patch.object(control,'sha',return_value='a'*64),self.assertRaises(ValueError):
            control.fixed301_runner_certificate(REPORT,self.apk,control.PRODUCER301)
        with patch.object(control,'sha',return_value=control.RUNNER301_SHA):
            with self.assertRaises(ValueError):control.fixed301_runner_certificate(REPORT,self.apk,{**control.PRODUCER301,'runAttempt':2})
            for bad in (REPORT.replace('Number of signers: 1','Number of signers: 2'),REPORT+REPORT,
                REPORT.replace(control.CERT301_SHA,'0'*64),REPORT.replace('V3.0 Signer:','V2 Signer:'),
                REPORT.replace('v2): false','v2): true'),REPORT.replace('v3): true','v3): false')):
                with self.subTest(report=bad),self.assertRaises(ValueError):control.fixed301_runner_certificate(bad,self.apk,control.PRODUCER301)
        self.apk.write_bytes(b'short')
        with patch.object(control,'sha',return_value=control.RUNNER301_SHA),self.assertRaises(ValueError):
            control.fixed301_runner_certificate(REPORT,self.apk,control.PRODUCER301)
    def test_fixed_adoption_mode_cannot_enter_prepare_or_runner_rebuild(self):
        with patch.dict(os.environ,self.environment(),clear=True),patch.object(control,'ci_only'), \
                patch.object(control,'installed_tools') as tools:
            self.assertTrue(control.signed301_requested())
            with self.assertRaisesRegex(ValueError,'cannot build'):control.prepare(Path(self.tmp.name)/'build')
            with self.assertRaisesRegex(ValueError,'cannot compile'):control.runner_only(self.tmp.name,Path(self.tmp.name)/'restore','a'*64)
            tools.assert_not_called()
    def test_same_mode_still_rejects_other_branch_and_old_runtime(self):
        with patch.dict(os.environ,{**self.environment(),'GITHUB_REF':'refs/heads/main'},clear=True),self.assertRaises(ValueError):control.signed301_requested()
        with patch.dict(os.environ,{**self.environment(),'AEROVE_LITE290_SURFACE_PROBE':'1'},clear=True),self.assertRaises(ValueError):control.signed301_requested()
    def test_api_requires_exact_failed301_and_original_artifact(self):
        run=dict(id=42287798957,run_attempt=1,run_number=301,head_sha=control.PRODUCER301['sourceRevision'],head_branch=control.BRANCH,
            path='.github/workflows/aerove-lite.yaml',status='completed',conclusion='failure',repository={'full_name':'IvanSolis1989/Aerove'})
        row={**control.ARTIFACT301,'expired':False,'workflow_run':dict(id=42287798957,repository_id=1429390079,head_repository_id=1429390079,
            head_branch=control.BRANCH,head_sha=control.PRODUCER301['sourceRevision'])}
        control.check301_api(run,row)
        for other in ({**row,'expired':True},{**row,'id':1},{**row,'digest':'sha256:'+'0'*64}):
            with self.assertRaises(ValueError):control.check301_api(run,other)
        with self.assertRaises(ValueError):control.check301_api({**run,'conclusion':'success'},row)

if __name__=='__main__':unittest.main()

PRESENT301_PATHS = ['RunnerManifest.xml', 'FLUTTER-LICENSE', 'FlutterHelloWorldControlTest.java', 'generated-project/pubspec.yaml', 'generated-project/flutter_onscreen_control.iml', 'generated-project/analysis_options.yaml', 'generated-project/README.md', 'generated-project/pubspec.lock', 'generated-project/lib/main.dart', 'generated-project/test/widget_test.dart', 'generated-project/android/build.gradle.kts', 'generated-project/android/gradle.properties', 'generated-project/android/local.properties', 'generated-project/android/gradlew', 'generated-project/android/gradlew.bat', 'generated-project/android/settings.gradle.kts', 'generated-project/android/flutter_onscreen_control_android.iml', 'generated-project/android/app/build.gradle.kts', 'generated-project/android/app/src/debug/AndroidManifest.xml', 'generated-project/android/app/src/main/AndroidManifest.xml', 'generated-project/android/app/src/profile/AndroidManifest.xml', 'generated-project/android/app/src/main/java/io/flutter/plugins/GeneratedPluginRegistrant.java', 'generated-project/android/app/src/main/res/mipmap-xhdpi/ic_launcher.png', 'generated-project/android/app/src/main/res/mipmap-xxhdpi/ic_launcher.png', 'generated-project/android/app/src/main/res/values/styles.xml', 'generated-project/android/app/src/main/res/drawable/launch_background.xml', 'generated-project/android/app/src/main/res/values-night/styles.xml', 'generated-project/android/app/src/main/res/mipmap-hdpi/ic_launcher.png', 'generated-project/android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png', 'generated-project/android/app/src/main/res/drawable-v21/launch_background.xml', 'generated-project/android/app/src/main/res/mipmap-mdpi/ic_launcher.png', 'generated-project/android/app/src/main/kotlin/dev/aerove/diagnostic/flutter_onscreen_control/MainActivity.kt', 'generated-project/android/gradle/wrapper/gradle-wrapper.jar', 'generated-project/android/gradle/wrapper/gradle-wrapper.properties']

class PartialSource301Boundaries(unittest.TestCase):
    def fixture(self, root):
        sources=root/'sources';sources.mkdir()
        declared={name:dict(row) for name,row in control.UNAVAILABLE301.items()}
        for name in PRESENT301_PATHS:
            file=sources/name;file.parent.mkdir(parents=True,exist_ok=True)
            file.write_bytes(('source-fixture:'+name).encode())
            declared[name]=dict(bytes=file.stat().st_size,sha256=control.sha(file))
        return {'sourceFiles':declared}
    def test_declared42_verified34_unavailable8_never_claims_complete(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);cp=self.fixture(root);value=control.source_verification301(root,cp)
            self.assertEqual((42,34,8),(value['declared'],value['verified'],value['unavailable']))
            self.assertFalse(value['sourceArchiveComplete'])
            self.assertEqual(set(control.UNAVAILABLE301),{n['path'] for n in value['unavailableFiles']})
    def test_unknown_missing_source_is_not_covered_by_metadata_exception(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);cp=self.fixture(root)
            old=PRESENT301_PATHS[0];cp['sourceFiles']['unexpected-missing-source.txt']=cp['sourceFiles'].pop(old)
            with self.assertRaises(ValueError):control.source_verification301(root,cp)
    def test_existing_source_same_size_hash_change_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);cp=self.fixture(root);file=root/'sources'/PRESENT301_PATHS[0]
            data=file.read_bytes();file.write_bytes(bytes([data[0]^1])+data[1:])
            with self.assertRaises(ValueError):control.source_verification301(root,cp)
    def test_unavailable_metadata_record_change_or_fabrication_is_rejected(self):
        for field,value in (('bytes',0),('sha256','0'*64)):
            with tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp);cp=self.fixture(root);name=next(iter(control.UNAVAILABLE301))
                cp['sourceFiles'][name][field]=value
                with self.assertRaises(ValueError):control.source_verification301(root,cp)
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);cp=self.fixture(root);name=next(iter(control.UNAVAILABLE301))
            file=root/'sources'/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_bytes(b'fabricated')
            with self.assertRaises(ValueError):control.source_verification301(root,cp)
