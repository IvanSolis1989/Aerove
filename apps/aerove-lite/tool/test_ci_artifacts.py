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
class HostHelperTests(unittest.TestCase):
    def source(self, root):
        source = root/'apps/aerove-lite/regexp2';source.mkdir(parents=True)
        production = Path(__file__).resolve().parents[1]/'regexp2/build.py'
        (source/'build.py').write_bytes(production.read_bytes())
        (source/'go.mod').write_text('module fixture\ngo 1.27.1\n')
        (source/'go.sum').write_text('fixture checksums\n')
        ci.shutil.copytree(production.parent/'agebridge/testdata',source/'agebridge/testdata')
        return source

    def test_real_host_target_uses_production_patched_staging_and_records_saved_bytes(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);self.source(root);stage=root/'staging';output=root/'bin';receipt=root/'reports/host.json'
            calls=[]
            def run(command, **kwargs):
                calls.append((command,kwargs))
                if '--test-only' in command:
                    self.assertEqual(ci.HOST_ASSESSMENT_HASHES,{p.name:ci.digest(p) for p in (stage.parent/'regexp2-assessment').iterdir()})
                    stage.mkdir();(stage/'go.mod').write_text('replace github.com/dlclark/regexp2 => ./upstream\n')
                else:
                    self.assertEqual(stage,kwargs['cwd'])
                    self.assertIn('replace github.com/dlclark/regexp2', (stage/'go.mod').read_text())
                    Path(command[command.index('-o')+1]).write_bytes(b'compiled host executable fixture')
                self.assertEqual('0',kwargs['env']['CGO_ENABLED'])
                self.assertEqual('local',kwargs['env']['GOTOOLCHAIN'])
                for key in ('GOOS','GOARCH','GOFLAGS','CC','CXX'):self.assertNotIn(key,kwargs['env'])
            with mock.patch.object(ci.Path,'cwd',return_value=root),mock.patch.object(ci.subprocess,'check_output',return_value='go version go1.27.1 linux/amd64'),mock.patch.object(ci.subprocess,'run',side_effect=run),mock.patch.object(ci,'identity',return_value=TransportTests.expected),mock.patch.dict(ci.os.environ,dict(GOOS='android',GOARCH='arm64',CC='android-clang',GOFLAGS='-mod=mod')):
                value=ci.host_helper('/go/bin/go',stage,output,receipt)
            self.assertEqual(value,json.loads(receipt.read_text()))
            self.assertEqual(ci.digest(Path(value['outputs']['hostverify']['path'])),value['outputs']['hostverify']['sha256'])
            self.assertEqual(len(b'compiled host executable fixture'),value['outputs']['hostverify']['bytes'])
            self.assertEqual('a'*40,value['sourceRevision'])
            self.assertEqual(3,len(calls))
            self.assertIn('--test-only',calls[0][0]);self.assertNotIn('--ndk',calls[0][0])
            self.assertEqual('./cmd/hostverify',calls[1][0][-1])
            self.assertIn('-mod=readonly',calls[1][0])
            self.assertTrue({'build.py','go.mod','go.sum','agebridge/testdata/provenance.json'}.issubset(value['inputFiles']))
            self.assertEqual({'hostverify','ageverify'},set(value['outputs']))
            self.assertEqual('./cmd/ageverify',calls[2][0][-1])
            self.assertEqual(6,len(value['ageFixtures']['files']))
            self.assertEqual(ci.HOST_ASSESSMENT_HASHES,value['assessmentCorpus']['files'])

    def test_host_helper_rejects_wrong_go_existing_output_and_source_change(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);source=self.source(root);stage=root/'staging';output=root/'bin';receipt=root/'receipt.json'
            with mock.patch.object(ci.Path,'cwd',return_value=root),mock.patch.object(ci.subprocess,'check_output',return_value='go version go1.26.0 linux/amd64'),mock.patch.object(ci.subprocess,'run') as run:
                with self.assertRaisesRegex(ci.PromotionError,'Go 1.27.1'):ci.host_helper('go',stage,output,receipt)
                run.assert_not_called();self.assertFalse(receipt.exists())
            output.mkdir()
            with self.assertRaisesRegex(ci.PromotionError,'must be new'):ci.host_helper('go',stage,output,receipt)
            output.rmdir()
            def changed(command,**kwargs):
                if '--test-only' in command:stage.mkdir()
                else:
                    Path(command[command.index('-o')+1]).write_bytes(b'compiled fixture')
                    (source/'go.mod').write_text('changed during build')
            with mock.patch.object(ci.Path,'cwd',return_value=root),mock.patch.object(ci.subprocess,'check_output',return_value='go version go1.27.1 linux/amd64'),mock.patch.object(ci.subprocess,'run',side_effect=changed):
                with self.assertRaisesRegex(ci.PromotionError,'inputs changed'):ci.host_helper('go',stage,output,receipt)
            self.assertFalse(receipt.exists())

    def test_failed_production_preparation_never_builds_or_exports_unpatched_helper(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);self.source(root);output=root/'bin';receipt=root/'receipt.json'
            with mock.patch.object(ci.Path,'cwd',return_value=root),mock.patch.object(ci.subprocess,'check_output',return_value='go version go1.27.1 linux/amd64'),mock.patch.object(ci.subprocess,'run',side_effect=ci.subprocess.CalledProcessError(1,['production-checksum-gate'])) as run:
                with self.assertRaises(ci.subprocess.CalledProcessError):ci.host_helper('go',root/'staging',output,receipt)
                self.assertEqual(1,run.call_count)
            self.assertFalse(output.exists());self.assertFalse(receipt.exists())

            (root/'apps/aerove-lite/regexp2/agebridge/testdata/profile.yaml').write_text('corrupt fixture')
            with mock.patch.object(ci.Path,'cwd',return_value=root),mock.patch.object(ci.subprocess,'run') as run:
                with self.assertRaisesRegex(ci.PromotionError,'fixture identity mismatch'):
                    ci.host_helper('go',root/'second'/'staging',root/'second'/'bin',receipt)
                run.assert_not_called()

    def test_modified_assessment_corpus_is_rejected_before_production_tests(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);self.source(root);bad=root/'bad-corpus';ci.shutil.copytree(ci.HOST_ASSESSMENT_ROOT,bad)
            (bad/'cases.json').write_text('[]')
            with mock.patch.object(ci.Path,'cwd',return_value=root),mock.patch.object(ci,'HOST_ASSESSMENT_ROOT',bad),mock.patch.object(ci.subprocess,'check_output',return_value='go version go1.27.1 linux/amd64'),mock.patch.object(ci.subprocess,'run') as run:
                with self.assertRaisesRegex(ci.PromotionError,'assessment corpus identity mismatch'):
                    ci.host_helper('go',root/'staging',root/'bin',root/'receipt.json')
                run.assert_not_called()
            self.assertFalse((root/'regexp2-assessment').exists())
            existing=root/'existing';(existing/'regexp2-assessment').mkdir(parents=True)
            with self.assertRaisesRegex(ci.PromotionError,'must be new'):
                ci.host_helper('go',existing/'staging',existing/'bin',root/'receipt.json')
            with mock.patch.object(ci.Path,'cwd',return_value=root):
                with self.assertRaisesRegex(ci.PromotionError,'overlap'):
                    ci.host_helper('go',root/'staging',root/'bin',root/'bin'/'receipt.json')
                with self.assertRaisesRegex(ci.PromotionError,'overlap'):
                    ci.host_helper('go',root/'apps/aerove-lite/regexp2/staging',root/'bin',root/'receipt.json')

class AndroidHelperTests(unittest.TestCase):
    def test_android_abis_use_original_producer_receipt_and_exact_sibling_corpus(self):
        for abi in ('arm64-v8a','x86_64'):
            with self.subTest(abi=abi),tempfile.TemporaryDirectory() as temp:
                root=Path(temp);source=HostHelperTests.source(self,root);stage=root/'staging';output=root/'jniLibs';saved=[]
                spec=ci.importlib.util.spec_from_file_location('fixture_builder',source/'build.py')
                builder=ci.importlib.util.module_from_spec(spec);spec.loader.exec_module(builder)
                def run(command,**kwargs):
                    self.assertEqual(ci.HOST_ASSESSMENT_HASHES,ci.assessment_identity(stage.parent/'regexp2-assessment'))
                    self.assertEqual(str(source/'build.py'),command[1]);self.assertNotIn('--test-only',command)
                    self.assertEqual(abi,command[command.index('--abi')+1]);self.assertEqual(str(output),command[-1])
                    self.assertEqual('local',kwargs['env']['GOTOOLCHAIN'])
                    for key in ('GOOS','GOARCH','GOFLAGS','CC','CXX','CGO_ENABLED'):self.assertNotIn(key,kwargs['env'])
                    stage.mkdir();data=(json.dumps(dict(builder.input_identity(source),abis=[abi]),indent=4)+'\n').encode()
                    (stage/'build-receipt.json').write_bytes(data);saved.append(data)
                with mock.patch.object(ci.Path,'cwd',return_value=root),mock.patch.object(ci.subprocess,'run',side_effect=run) as called,mock.patch.object(ci,'identity',return_value=TransportTests.expected):
                    value=ci.android_helper('/go/bin/go','/ndk',abi,stage,output)
                self.assertEqual(1,called.call_count)
                self.assertEqual(saved[0],(stage/'build-receipt.json').read_bytes())
                self.assertEqual(ci.digest(stage/'build-receipt.json'),value['productionReceiptSha256'])
                self.assertEqual(ci.HOST_ASSESSMENT_HASHES,value['assessmentCorpus']['files'])

    def test_android_helper_rejects_failed_changed_existing_and_overlapping_inputs(self):
        for failure in ('process','source','corpus','receipt'):
            with self.subTest(failure=failure),tempfile.TemporaryDirectory() as temp:
                root=Path(temp);source=HostHelperTests.source(self,root);stage=root/'staging'
                spec=ci.importlib.util.spec_from_file_location('fixture_builder',source/'build.py')
                builder=ci.importlib.util.module_from_spec(spec);spec.loader.exec_module(builder)
                def run(command,**kwargs):
                    if failure=='process':raise ci.subprocess.CalledProcessError(1,command)
                    stage.mkdir();(stage/'build-receipt.json').write_text(json.dumps(builder.input_identity(source)))
                    if failure=='source':(source/'go.mod').write_text('changed')
                    if failure=='corpus':(stage.parent/'regexp2-assessment/cases.json').write_text('[]')
                    if failure=='receipt':(stage/'build-receipt.json').write_text('{}')
                with mock.patch.object(ci.Path,'cwd',return_value=root),mock.patch.object(ci.subprocess,'run',side_effect=run):
                    with self.assertRaises((ci.PromotionError,ci.subprocess.CalledProcessError)):
                        ci.android_helper('go','/ndk','arm64-v8a',stage,root/'out')
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);source=HostHelperTests.source(self,root)
            with mock.patch.object(ci.Path,'cwd',return_value=root),mock.patch.object(ci.subprocess,'run') as run:
                for stage,out in [(source/'staging',root/'out'),(root/'staging',source/'output'),(root/'staging',root/'staging'/'output')]:
                    with self.assertRaises(ci.PromotionError):ci.android_helper('go','/ndk','arm64-v8a',stage,out)
                (root/'regexp2-assessment').mkdir()
                with self.assertRaises(ci.PromotionError):ci.android_helper('go','/ndk','arm64-v8a',root/'staging',root/'out')
                run.assert_not_called()

if __name__=='__main__':unittest.main()