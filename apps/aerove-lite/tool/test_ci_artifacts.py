import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

spec=importlib.util.spec_from_file_location('ci_artifacts',Path(__file__).with_name('ci_artifacts.py'))
ci=importlib.util.module_from_spec(spec);spec.loader.exec_module(ci)

class TransportTests(unittest.TestCase):
    expected=dict(sourceRevision='a'*40,sourceTree='b'*40,workflowRun='123',runAttempt=2,runNumber=7)

    def test_actual_file_roundtrip_rejects_changed_content_identity_and_extras(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'lib.so').write_bytes(b'actual fixture ELF bytes')
            ci.seal(root,'x86_64',self.expected)
            ci.verify(root,dict(self.expected,abi='x86_64'))
            for key,value in [('runAttempt',1),('sourceRevision','c'*40),('abi','arm64-v8a')]:
                with self.subTest(key=key),self.assertRaises(ci.PromotionError):
                    ci.verify(root,dict(self.expected,abi='x86_64',**{key:value}) if key!='abi' else dict(self.expected,abi=value))
            (root/'extra').write_bytes(b'not manifested')
            with self.assertRaises(ci.PromotionError):ci.verify(root,self.expected)
            (root/'extra').unlink();(root/'lib.so').write_bytes(b'changed')
            with self.assertRaises(ci.PromotionError):ci.verify(root,self.expected)

    def test_fetch_rejects_other_attempt_artifact_and_checks_exact_metadata(self):
        row=dict(name='current-attempt',id=5,expired=False,workflow_run=dict(id=123,head_sha='a'*40))
        with tempfile.TemporaryDirectory() as temp,mock.patch.dict(ci.os.environ,{'GITHUB_REPOSITORY':'owner/repo'}):
            with mock.patch.object(ci,'_gh_json',return_value={'artifacts':[dict(row,workflow_run=dict(id=124,head_sha='a'*40))]}),mock.patch.object(ci,'download_artifact') as download:
                with self.assertRaises(ci.PromotionError):ci.fetch('current-attempt',Path(temp)/'out',self.expected)
                download.assert_not_called()
            with mock.patch.object(ci,'_gh_json',return_value={'artifacts':[row,row]}):
                with self.assertRaises(ci.PromotionError):ci.fetch('current-attempt',Path(temp)/'out',self.expected)

    def test_runtime_receipt_requires_inspector_and_both_original_apks(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'apks').mkdir()
            for name in ('app-release.apk','app-release-androidTest.apk'):(root/'apks'/name).write_bytes(name.encode())
            ci.seal(root,'x86_64',self.expected)
            value=json.loads((root/'TRANSFER.json').read_text());(root/'TRANSFER.json').unlink()
            value.update(prebuiltApks=True,apkInspectorOutcome='success')
            (root/'source-manifest.json').write_text(json.dumps(value));(root/'SHA256SUMS').write_text('original checksum receipt')
            ci.verify(root,self.expected,True)
            value['apkInspectorOutcome']='failure';(root/'source-manifest.json').write_text(json.dumps(value))
            with self.assertRaises(ci.PromotionError):ci.verify(root,self.expected,True)

class NativeBuildTests(unittest.TestCase):
    def test_native_build_stages_only_after_current_locked_receipt_validates(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);build=root/'apps/aerove-lite/meow-native/build'
            (build/'x86_64').mkdir(parents=True);(build/'x86_64'/ci.CORE_LIBRARY).write_bytes(b'actual current ELF')
            (build/'x86_64-receipt.json').write_text('{}')
            with mock.patch.object(ci.Path,'cwd',return_value=root),mock.patch.object(ci,'native_fingerprint',return_value='fingerprint'),mock.patch.object(ci,'native_identity'),mock.patch.object(ci,'valid_elf64_shared_object',return_value=True),mock.patch.object(ci,'exported_functions',return_value=set(ci.CORE_JNI_SYMBOLS)),mock.patch.object(ci.subprocess,'run') as run,mock.patch.object(ci,'validate_native_receipt') as validate,mock.patch.dict(ci.os.environ,dict(GITHUB_SHA='a'*40,GITHUB_RUN_ID='123',GITHUB_RUN_ATTEMPT='2')):
                ci.native_build('x86_64-linux-android','x86_64','ndk')
                self.assertEqual((root/'apps/aerove-lite/app/src/main/jniLibs/x86_64'/ci.CORE_LIBRARY).read_bytes(),b'actual current ELF')
                self.assertIn('apps/aerove-lite/meow-native/build.py',run.call_args.args[0])
                self.assertNotIn('--resolve-lock',run.call_args.args[0]);validate.assert_called_once()
                with self.assertRaises(ci.PromotionError):ci.native_build('aarch64-linux-android','x86_64','ndk')
                destination=root/'apps/aerove-lite/app/src/main/jniLibs/x86_64'/ci.CORE_LIBRARY
                destination.write_bytes(b'previous verified output')
                with mock.patch.object(ci,'exported_functions',return_value=set()),self.assertRaisesRegex(ci.PromotionError,'JNI exports'):
                    ci.native_build('x86_64-linux-android','x86_64','ndk')
                self.assertEqual(destination.read_bytes(),b'previous verified output')

    def test_fingerprint_includes_native_content_toolchain_ndk_and_wrapper(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);source=root/'apps/aerove-lite/meow-native/src/lib.rs';source.parent.mkdir(parents=True);source.write_text('one')
            ndk=root/'ndk';ndk.mkdir();(ndk/'source.properties').write_text('ndk28.2')
            def output(command,**kwargs):
                return b'apps/aerove-lite/meow-native/src/lib.rs\0' if command[0]=='git' else 'rustc 1.98.1'
            with mock.patch.object(ci.subprocess,'check_output',side_effect=output):
                initial=ci.native_fingerprint(root,'x86_64-linux-android',ndk)
                self.assertEqual(initial,ci.native_fingerprint(root,'x86_64-linux-android',ndk))
                source.write_text('two');self.assertNotEqual(initial,ci.native_fingerprint(root,'x86_64-linux-android',ndk))
                source.write_text('one');(ndk/'source.properties').write_text('different ndk')
                self.assertNotEqual(initial,ci.native_fingerprint(root,'x86_64-linux-android',ndk))
                (ndk/'source.properties').write_text('ndk28.2')
                self.assertNotEqual(initial,ci.native_fingerprint(root,'aarch64-linux-android',ndk))
if __name__=='__main__':unittest.main()