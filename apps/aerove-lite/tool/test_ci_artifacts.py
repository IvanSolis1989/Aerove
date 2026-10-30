import importlib.util
import copy
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


class HostLockResolutionPlanTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = dict(REQUEST_EVENT='workflow_dispatch', MEOW_HOST_ONLY='true', DIAGNOSTIC_TARGET='none', NATIVE_TEST_FILTER='')
        for key in ('QA84_ONLY', 'QA79_ONLY', 'CLIENT_PREFLIGHT', 'PUBLISH_RELEASE', 'NATIVE_PREFLIGHT',
                    'NATIVE_TEST_SERIAL', 'NATIVE_UNDERLAY_TESTS', 'NATIVE_TEST_STACKS'):
            self.env[key] = 'false'
        for name, data in [('Cargo.toml', b'fixture manifest'), ('Cargo.lock', b'approved fixture seed'),
                           ('core-tests.lock', b'approved fixture Core lock'), ('fixed.patch', b'fixture overlay patch')]:
            (self.root / name).write_bytes(data)
        source = dict(repository='https://github.com/meow-rs/meow-rs', commit=ci.MEOW_SOURCE, tag='v0.26.1',
                      archiveSha256='4273e027c5db3f7c052b255730f29165908772bf417ec89d9ab54478f81dcd9a')
        (self.root / 'source.json').write_text(json.dumps(source))
        overlay = dict(schema=1, patch='fixed.patch', patchSha256=ci.digest(self.root / 'fixed.patch'),
                       rawFileManifestSha256='1' * 64, adaptedFileManifestSha256=ci.hashlib.sha256(b'{}').hexdigest(), files={}, changes={})
        (self.root / 'overlay.json').write_text(json.dumps(overlay))
        ci.sys.path.insert(0, str(ci.MEOW_ROOT))
        try:
            from build_policy import RESOURCE_HEALTH_TARGETS
        finally:
            ci.sys.path.pop(0)
        self.policy = dict(schema=1, resolveLock=True, manifestSha256=ci.digest(self.root / 'Cargo.toml'),
                           expectedLockBeforeSha256=ci.digest(self.root / 'Cargo.lock'), targets=list(RESOURCE_HEALTH_TARGETS),
                           overlay='overlay.json', overlaySha256=ci.digest(self.root / 'overlay.json'),
                           featureBoundary='capabilities-no-default', coreTests='policy-runtime-resource-health-v1',
                           coreTestLockSha256=ci.digest(self.root / 'core-tests.lock'))
        self.write_policy()
        pins = {key: self.policy[key] for key in ci.HOST_LOCK_RESOLUTION_INPUTS if key != 'sourceSpecSha256'}
        pins['sourceSpecSha256'] = ci.digest(self.root / 'source.json')
        self.addCleanup(mock.patch.stopall)
        mock.patch.object(ci, 'HOST_LOCK_RESOLUTION_INPUTS', pins).start()

    def write_policy(self):
        (self.root / 'build-policy.json').write_text(json.dumps(self.policy))

    def test_pending_plan_does_not_accept_lock_or_run_cargo(self):
        with mock.patch.object(ci.subprocess, 'run') as run:
            plan = ci.host_lock_resolution_plan('host', self.root, self.env)
        run.assert_not_called()
        self.assertIs(plan['acceptedLock'], False)
        self.assertEqual('pending', plan['lockAcceptance'])
        self.assertIs(plan['resolve'], True)
        self.assertIs(plan['android'], False)
        self.assertEqual((42, 26, 75), (plan['nativeTargetCount'], plan['coreCommandCount'], plan['expectedCoreHits']))
        self.assertEqual('--resolve-lock', plan['command'][-1])

    def test_history_resolution_requires_the_frozen_manifest_and_seed(self):
        self.policy['coreTests'] = 'policy-runtime-provider-reader-v1'
        self.write_policy()
        pins = {key: self.policy[key] for key in ci.HOST_LOCK_RESOLUTION_HISTORY_INPUTS if key != 'sourceSpecSha256'}
        pins['sourceSpecSha256'] = ci.digest(self.root / 'source.json')
        with mock.patch.object(ci, 'HOST_LOCK_RESOLUTION_HISTORY_INPUTS', pins):
            plan = ci.host_lock_resolution_plan('host', self.root, self.env)
            self.assertEqual((42, 32, 179), (plan['nativeTargetCount'], plan['coreCommandCount'], plan['expectedCoreHits']))
            self.assertIs(plan['acceptedLock'], False)
            for name, key in [('Cargo.toml', 'manifestSha256'), ('Cargo.lock', 'expectedLockBeforeSha256')]:
                path = self.root / name
                original = path.read_bytes()
                previous = self.policy[key]
                path.write_bytes(b'changed but internally consistent')
                self.policy[key] = ci.digest(path)
                self.write_policy()
                try:
                    with self.subTest(file=name), self.assertRaises(ci.PromotionError):
                        ci.host_lock_resolution_plan('host', self.root, self.env)
                finally:
                    path.write_bytes(original)
                    self.policy[key] = previous
                    self.write_policy()

    def test_phase_event_mode_and_all_conflicts_reject(self):
        for phase in (None, '', 'android', 'unknown'):
            with self.subTest(phase=phase), self.assertRaises(ci.PromotionError):
                ci.host_lock_resolution_plan(phase, self.root, self.env)
        for key in self.env:
            bad = dict(self.env)
            bad[key] = 'false' if key == 'MEOW_HOST_ONLY' else 'push' if key == 'REQUEST_EVENT' else 'both' if key == 'DIAGNOSTIC_TARGET' else 'filter' if key == 'NATIVE_TEST_FILTER' else 'true'
            with self.subTest(key=key), self.assertRaises(ci.PromotionError):
                ci.host_lock_resolution_plan('host', self.root, bad)

    def test_mutated_source_seed_manifest_core_overlay_and_suite_reject(self):
        for name in ('Cargo.lock', 'Cargo.toml', 'core-tests.lock', 'source.json', 'fixed.patch'):
            path = self.root / name; data = path.read_bytes(); path.write_bytes(b'changed')
            try:
                with self.subTest(file=name), self.assertRaises((ValueError, ci.PromotionError)):
                    ci.host_lock_resolution_plan('host', self.root, self.env)
            finally:
                path.write_bytes(data)
        for key, value in [('resolveLock', False), ('targets', self.policy['targets'][:-1]), ('coreTests', 'policy-runtime-v1'),
                           ('manifestSha256', '0' * 64), ('expectedLockBeforeSha256', '0' * 64)]:
            previous = self.policy[key]; self.policy[key] = value; self.write_policy()
            try:
                with self.subTest(key=key), self.assertRaises((ValueError, ci.PromotionError)):
                    ci.host_lock_resolution_plan('host', self.root, self.env)
            finally:
                self.policy[key] = previous; self.write_policy()

    def test_self_consistent_new_manifest_or_seed_is_still_unapproved(self):
        for name, key in [('Cargo.lock', 'expectedLockBeforeSha256'), ('Cargo.toml', 'manifestSha256')]:
            path = self.root / name; data = path.read_bytes(); previous = self.policy[key]
            path.write_bytes(b'unapproved'); self.policy[key] = ci.digest(path); self.write_policy()
            try:
                with self.subTest(file=name), self.assertRaises(ci.PromotionError):
                    ci.host_lock_resolution_plan('host', self.root, self.env)
            finally:
                path.write_bytes(data); self.policy[key] = previous; self.write_policy()

    def test_default_admission_and_host_stay_strict_and_other_actions_cannot_opt_in(self):
        for action in ('admission', 'host'):
            with mock.patch.object(ci.sys, 'argv', ['ci_artifacts.py', action]), mock.patch.object(ci, 'native_identity', side_effect=ValueError('pending is not accepted')) as strict:
                with self.assertRaisesRegex(ValueError, 'pending is not accepted'): ci.main()
                strict.assert_called_once_with()
        for args in (['native', '--host-lock-resolution', '--phase', 'host'], ['host', '--host-lock-resolution', '--phase', 'host'],
                     ['admission', '--host-lock-resolution', '--phase', 'android'], ['admission', '--phase', 'host'], ['admission', '--host-lock-resolution'], ['admission', '--host-lock-resolution', '--phase', 'host', '--target', 'x86_64-linux-android']):
            with mock.patch.object(ci.sys, 'argv', ['ci_artifacts.py', *args]), self.subTest(args=args), self.assertRaises(SystemExit) as caught:
                ci.main()
            self.assertEqual(2, caught.exception.code)

    def test_dirty_native_source_preserves_original_git_error_and_prints_no_plan(self):
        error = ci.subprocess.CalledProcessError(1, ['git', 'diff'])
        with mock.patch.object(ci.sys, 'argv', ['ci_artifacts.py', 'admission', '--host-lock-resolution', '--phase', 'host']), mock.patch.object(ci, 'host_lock_resolution_plan', return_value={}), mock.patch.object(ci.subprocess, 'run', side_effect=error) as run, mock.patch('builtins.print') as output:
            with self.assertRaises(ci.subprocess.CalledProcessError) as caught: ci.main()
            self.assertIs(error, caught.exception)
            self.assertEqual('git', run.call_args.args[0][0])
            output.assert_not_called()


    def _provider_reader_core_receipt(self):
        import verify_apk as verifier
        from core_tests import entries, entry_command
        self.policy['coreTests'] = 'policy-runtime-provider-reader-v1'
        gates = (verifier.CORE_MRS_READER_FORMAT_COMMAND, verifier.CORE_PROVIDER_READER_FORMAT_COMMAND)
        files = {name.removeprefix('.upstream/meow-rs/'): ci.hashlib.sha256(name.encode()).hexdigest()
                 for command in gates for name in command[4:]}
        files['Cargo.lock'] = 'a' * 64
        overlay = json.loads((self.root / 'overlay.json').read_text())
        overlay.update(files=files, adaptedFileManifestSha256=ci.hashlib.sha256(
            json.dumps(files, sort_keys=True, separators=(',', ':')).encode()).hexdigest())
        (self.root / 'overlay.json').write_text(json.dumps(overlay))
        self.policy['overlaySha256'] = ci.digest(self.root / 'overlay.json')
        self.write_policy()
        pins = {key: self.policy[key] for key in ci.HOST_LOCK_RESOLUTION_HISTORY_INPUTS if key != 'sourceSpecSha256'}
        pins['sourceSpecSha256'] = ci.digest(self.root / 'source.json')
        mock.patch.object(ci, 'HOST_LOCK_RESOLUTION_HISTORY_INPUTS', pins).start()
        cases = entries(self.policy['coreTests'])
        packages = tuple(sorted(set(verifier.CORE_PROVIDER_GENERAL_PACKAGES) | {'meow-rules'}))
        features = {package: sorted({feature for case in cases if case['package'] == package
                                    for feature in case['requiredFeatures']}) for package in packages}
        commands = [dict(case, command=entry_command(case, features[case['package']]), exit=0,
                         passed=case['expectedPass'], stdoutSha256='c' * 64, stderrSha256='d' * 64) for case in cases]
        self.assertEqual((32, 179), (len(commands), sum(entry['passed'] for entry in commands)))
        state = dict(mode=self.policy['coreTests'], accepted=True, upstreamLockRestored=True,
                     upstreamLockBeforeSha256='a' * 64, seedSha256=self.policy['coreTestLockSha256'],
                     resolvedLockSha256=self.policy['coreTestLockSha256'],
                     bridgeMetadataCommand=['cargo', 'metadata', '--locked', '--format-version', '1'], bridgeMetadataExit=0,
                     metadataCommand=['cargo', 'metadata', '--manifest-path', '.upstream/meow-rs/Cargo.toml', '--format-version', '1'],
                     metadataExit=0, workspaceMembers=list(packages), features=features, commands=commands,
                     compileCheck=dict(command=verifier.CORE_OBSERVABILITY_COMPILE_COMMAND, exit=0,
                                       stdoutSha256='e' * 64, stderrSha256='f' * 64))
        for key, command in zip(('formatCheck', 'providerFormatCheck'), gates):
            state[key] = dict(command=command, exit=0, stdoutSha256='e' * 64, stderrSha256='f' * 64,
                              files={name: files[name.removeprefix('.upstream/meow-rs/')] for name in command[4:]})
        return state

    def test_pending_provider_metadata_is_exact_and_does_not_relax_formal_acceptance(self):
        self._assert_pending_producer_readback(provider_reader=True)

    def test_fake_producer_plan_save_and_pending_readback_never_becomes_accepted(self):
        self._assert_pending_producer_readback()

    def _assert_pending_producer_readback(self, provider_reader=False):
        # Host fixtures only: no Cargo command is executed.
        provider_core = self._provider_reader_core_receipt() if provider_reader else None
        plan = ci.host_lock_resolution_plan('host', self.root, self.env)
        workflow = dict(sourceRevision='a' * 40, sourceTree='b' * 40, workflowRun='97', runAttempt=1, runNumber=97)
        plan['workflowIdentity'] = workflow
        build = self.root / 'build'; build.mkdir()
        plan_path = build / 'host-lock-resolution-plan.json'; plan_path.write_text(json.dumps(plan))
        plan_bytes = plan_path.read_bytes()
        (build / 'lock-before-resolution.lock').write_bytes((self.root / 'Cargo.lock').read_bytes())
        (self.root / 'Cargo.lock').write_bytes(b'fake producer resolved lock; not Cargo output')
        policy_bytes = (self.root / 'build-policy.json').read_bytes()
        ci.sys.path.insert(0, str(ci.MEOW_ROOT))
        try:
            from build_policy import feature_boundary_command
            from core_tests import entries
        finally:
            ci.sys.path.pop(0)
        command = ['cargo', 'test', '--locked', '--no-fail-fast']
        for target in self.policy['targets']:
            command += ['--lib'] if target == 'lib' else ['--test', target]
        command += ['--', '--test-threads=1', '--nocapture']
        overlay = json.loads((self.root / 'overlay.json').read_text())
        core_commands = [dict(exit=0, expectedPass=entry['expectedPass'], passed=entry['expectedPass'])
                         for entry in entries(self.policy['coreTests'])]
        resolved = ci.digest(self.root / 'Cargo.lock')
        receipt = dict(schema=1, acceptedLock=True, resolveLockRequested=True, bootstrapRequested=False,
                       androidTarget=None, commandExit=0, command=command, representativeTargets=self.policy['targets'],
                       workflowSourceSha=workflow['sourceRevision'], workflowRun=workflow['workflowRun'], workflowAttempt='1',
                       buildPolicySha256=ci.hashlib.sha256(policy_bytes).hexdigest(),
                       bridgeManifestSha256=self.policy['manifestSha256'], declaredSourceSpecSha256=ci.HOST_LOCK_RESOLUTION_INPUTS['sourceSpecSha256'],
                       lockSha256Before=self.policy['expectedLockBeforeSha256'], metadataInputLockSha256=self.policy['expectedLockBeforeSha256'],
                       metadataCommand=['cargo', 'metadata', '--format-version', '1'], metadataExit=0,
                       resolvedLockSha256=resolved, lockSha256After=resolved, lockUnchangedDuringLockedCommand=True,
                       featureBoundaryCommand=feature_boundary_command(self.policy), featureBoundaryExit=0,
                       source=dict(commit=ci.MEOW_SOURCE, tag='v0.26.1', archiveSha256='4273e027c5db3f7c052b255730f29165908772bf417ec89d9ab54478f81dcd9a',
                                   overlaySha256=self.policy['overlaySha256'], patchSha256=overlay['patchSha256'], fileManifestSha256=overlay['adaptedFileManifestSha256']),
                       coreTests=provider_core or dict(mode=self.policy['coreTests'], accepted=True, upstreamLockRestored=True,
                                      seedSha256=self.policy['coreTestLockSha256'], resolvedLockSha256=self.policy['coreTestLockSha256'],
                                      commands=core_commands, compileCheck=dict(exit=0)))
        if provider_reader:
            receipt.update(addedNativeTests=ci.RESOURCE_HEALTH_CASES, toolchain='rustc 1.98.1 (fixture)')
            receipt['source']['archiveHashBootstrapped'] = False
        receipt_path = build / 'host-receipt.json'; receipt_path.write_text(json.dumps(receipt))
        receipt_bytes = receipt_path.read_bytes()
        with mock.patch.object(ci, 'identity', return_value=workflow):
            try:
                output = ci.host_lock_resolution_result(plan_path, 'host', self.root, self.env)
            except ValueError as error:
                self.fail(f'Valid pending producer receipt was rejected: {error}')
            self.assertIs(output['archiveOnly'], True)
            self.assertIs(output['acceptedLock'], False)
            self.assertIs(output['producerAcceptedLock'], True)
            self.assertEqual('pending', output['lockAcceptance'])
            self.assertEqual(plan_bytes, plan_path.read_bytes())
            self.assertEqual(receipt_bytes, receipt_path.read_bytes())
            self.assertEqual(policy_bytes, (self.root / 'build-policy.json').read_bytes())
            for key, value in [('workflowRun', 'wrong'), ('commandExit', 1), ('resolveLockRequested', False),
                               ('resolvedLockSha256', '0' * 64), ('lockSha256Before', '0' * 64), ('acceptedLock', False)]:
                receipt_path.write_text(json.dumps(dict(receipt, **{key: value})))
                with self.subTest(key=key), self.assertRaises(ci.PromotionError):
                    ci.host_lock_resolution_result(plan_path, 'host', self.root, self.env)
            if provider_reader:
                import verify_apk as verifier
                with self.assertRaisesRegex(ValueError, 'complete approved Core unit proof'):
                    verifier.validate_core_test_receipt(provider_core, self.policy, self.root)
                # The formal entry stays locked even when handed a pending policy.
                verifier.validate_core_test_receipt(dict(provider_core,
                    metadataCommand=provider_core['metadataCommand'] + ['--locked']), self.policy, self.root)
                invalid = []
                for command in (provider_core['metadataCommand'] + ['--locked'], provider_core['metadataCommand'][:-1],
                                provider_core['metadataCommand'] + ['--offline']):
                    bad = copy.deepcopy(receipt); bad['coreTests']['metadataCommand'] = command; invalid.append(bad)
                for field, value in [('commands', provider_core['commands'][:-1]), ('compileCheck', {}),
                                     ('resolvedLockSha256', '0' * 64)]:
                    bad = copy.deepcopy(receipt); bad['coreTests'][field] = value; invalid.append(bad)
                bad = copy.deepcopy(receipt); bad['coreTests']['commands'][0]['command'].remove('--locked'); invalid.append(bad)
                bad = copy.deepcopy(receipt); bad['source']['commit'] = '0' * 40; invalid.append(bad)
                for index, bad in enumerate(invalid):
                    receipt_path.write_text(json.dumps(bad))
                    with self.subTest(pending_mutation=index), self.assertRaises((ValueError, ci.PromotionError)):
                        ci.host_lock_resolution_result(plan_path, 'host', self.root, self.env)
                receipt_path.write_bytes(receipt_bytes)
                with self.assertRaises(ci.PromotionError):
                    ci.host_lock_resolution_result(plan_path, 'host', self.root, dict(self.env, MEOW_HOST_ONLY='false'))
                self.policy['resolveLock'] = False; self.write_policy()
                try:
                    with self.assertRaises((ValueError, ci.PromotionError)):
                        ci.host_lock_resolution_result(plan_path, 'host', self.root, self.env)
                finally:
                    self.policy['resolveLock'] = True; self.write_policy()
            receipt_path.write_bytes(receipt_bytes)
            (build / 'lock-before-resolution.lock').write_bytes(b'wrong seed')
            with self.assertRaises(ci.PromotionError):
                ci.host_lock_resolution_result(plan_path, 'host', self.root, self.env)
        with self.assertRaisesRegex(ValueError, 'accepted committed lock'):
            ci.native_identity(self.root)

    def test_pending_readback_cli_never_calls_accepted_guard_and_excludes_only_resolved_lock(self):
        argv = ['ci_artifacts.py', 'host-resolution-result', '--host-lock-resolution', '--phase', 'host', '--receipt', 'plan.json']
        with mock.patch.object(ci.sys, 'argv', argv), mock.patch.object(ci, 'host_lock_resolution_result', return_value=dict(archiveOnly=True, acceptedLock=False)), mock.patch.object(ci, 'native_identity', side_effect=AssertionError('accepted guard must not run')), mock.patch.object(ci.subprocess, 'run') as run, mock.patch('builtins.print') as output:
            ci.main()
        self.assertEqual(['git', 'diff', '--exit-code', 'HEAD', '--', 'apps/aerove-lite/meow-native', ':(exclude)apps/aerove-lite/meow-native/Cargo.lock'], run.call_args.args[0])
        self.assertIs(json.loads(output.call_args.args[0])['acceptedLock'], False)

if __name__=='__main__':unittest.main()
