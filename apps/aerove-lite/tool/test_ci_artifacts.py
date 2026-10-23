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

class NativeOutputCacheTests(unittest.TestCase):
    def test_exact_native_inputs_reuse_then_changed_or_corrupt_inputs_rebuild(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); source=root/'source.rs';source.write_text('first')
            built=[]
            def build(command,check):
                built.append(command)
                target=root/'apps/aerove-lite/app/src/main/jniLibs/x86_64';target.mkdir(parents=True,exist_ok=True)
                header=bytearray(20);header[:6]=b'\x7fELF\x02\x01';header[18:20]=(62).to_bytes(2,'little')
                (target/'libaerove_core.so').write_bytes(bytes(header)+source.read_bytes())
                provenance=root/'apps/aerove-lite/native/build/provenance.json';provenance.parent.mkdir(parents=True,exist_ok=True);provenance.write_text('{"fixture":"provenance"}')
            with mock.patch.object(ci.Path,'cwd',return_value=root),mock.patch.object(ci,'native_fingerprint',side_effect=lambda *_:ci.digest(source)),mock.patch.object(ci.subprocess,'run',side_effect=build):
                ci.native_build('x86_64-linux-android','x86_64','ndk')
                ci.native_build('x86_64-linux-android','x86_64','ndk')
                self.assertEqual(1,len(built))
                source.write_text('second');ci.native_build('x86_64-linux-android','x86_64','ndk')
                self.assertEqual(2,len(built))
                (root/'apps/aerove-lite/native/build/ci-cache-x86_64/libaerove_core.so').write_bytes(b'corrupt')
                ci.native_build('x86_64-linux-android','x86_64','ndk');self.assertEqual(3,len(built))
                with self.assertRaises(ci.PromotionError):ci.native_build('aarch64-linux-android','x86_64','ndk')

    def test_fingerprint_includes_native_content_toolchain_ndk_and_wrapper(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);source=root/'apps/aerove-lite/native/src/lib.rs';source.parent.mkdir(parents=True);source.write_text('one')
            ndk=root/'ndk';ndk.mkdir();(ndk/'source.properties').write_text('ndk28.2')
            def output(command,**kwargs):
                return b'apps/aerove-lite/native/src/lib.rs\0' if command[0]=='git' else 'rustc 1.99.0'
            with mock.patch.object(ci.subprocess,'check_output',side_effect=output):
                initial=ci.native_fingerprint(root,'x86_64-linux-android',ndk)
                self.assertEqual(initial,ci.native_fingerprint(root,'x86_64-linux-android',ndk))
                source.write_text('two');self.assertNotEqual(initial,ci.native_fingerprint(root,'x86_64-linux-android',ndk))
                source.write_text('one');(ndk/'source.properties').write_text('different ndk')
                self.assertNotEqual(initial,ci.native_fingerprint(root,'x86_64-linux-android',ndk))
                (ndk/'source.properties').write_text('ndk28.2')
                self.assertNotEqual(initial,ci.native_fingerprint(root,'aarch64-linux-android',ndk))
if __name__=='__main__':unittest.main()