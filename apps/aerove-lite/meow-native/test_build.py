"""Source/recipe tests only. These never invoke Rust, NDK, Gradle or network."""
import importlib.util
import io
import os
import json
import re
import textwrap
from pathlib import Path
import tarfile
import tempfile
import subprocess
import shlex
import types
import sys
import unittest
from unittest import mock
import prepare
import build
import build_policy
import clang_probe
import jni_abi
from verify_source import sha, verify, source_spec

class SourceTests(unittest.TestCase):
    def fixture(self,root):
        (root/'Cargo.lock').write_text('real fixture lock')
        (root/'LICENSE').write_text('MIT fixture')
        (root/'src').mkdir();(root/'src/lib.rs').write_bytes(b'source fixture\n')
        return dict(commit='a'*40,tag='v1',repository='fixture',files={p.relative_to(root).as_posix():sha(p) for p in root.rglob('*') if p.is_file()},
                    upstreamLockSha256=sha(root/'Cargo.lock'),licenseSha256=sha(root/'LICENSE'))

    def test_manifest_rejects_changed_missing_or_extra_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);spec=self.fixture(root);self.assertEqual(3,verify(root,spec)['files'])
            (root/'extra').write_text('not pinned')
            with self.assertRaises(ValueError):verify(root,spec)
            (root/'extra').unlink()
            (root/'src/lib.rs').write_bytes(b'source fixture\r\n')
            with self.assertRaises(ValueError):verify(root,spec)
            (root/'src/lib.rs').write_bytes(b'source fixture\n');self.assertEqual(3,verify(root,spec)['files'])
            (root/'src/lib.rs').write_text('changed')
            with self.assertRaises(ValueError):verify(root,spec)
            (root/'src/lib.rs').unlink()
            with self.assertRaises(ValueError):verify(root,spec)

    def test_archive_safe_extraction_and_duplicate_traversal_symlink_rejection(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            for index,names in enumerate([['pin/source.rs'],['pin/../escape'],['pin/a','pin/a']]):
                archive=root/f'{index}.tar.gz'
                with tarfile.open(archive,'w:gz') as target:
                    for name in names:
                        item=tarfile.TarInfo(name);item.size=1;target.addfile(item,io.BytesIO(b'x'))
                destination=root/f'out{index}';destination.mkdir()
                if index==0:
                    prepare.extract(archive,destination);self.assertEqual(b'x',(destination/'source.rs').read_bytes())
                else:
                    with self.assertRaises(ValueError):prepare.extract(archive,destination)
            archive=root/'link.tar.gz'
            with tarfile.open(archive,'w:gz') as target:
                item=tarfile.TarInfo('pin/link');item.type=tarfile.SYMTYPE;item.linkname='../outside';target.addfile(item)
            with self.assertRaises(ValueError):prepare.extract(archive,root/'links')

    def test_windows_drive_colon_absolute_and_resolved_escape_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            for index,name in enumerate(['pin/C:/outside.txt','C:/outside.txt','pin/C:outside.txt','pin/a:stream','/pin/outside.txt','pin/\\outside.txt']):
                with self.subTest(name=name):
                    archive=root/f'unsafe{index}.tar.gz'
                    with tarfile.open(archive,'w:gz') as target:
                        item=tarfile.TarInfo(name);item.size=1;target.addfile(item,io.BytesIO(b'x'))
                    destination=root/f'out{index}';destination.mkdir()
                    with self.assertRaises(ValueError):prepare.extract(archive,destination)
                    self.assertEqual([],list(destination.iterdir()))
            # Exercise containment independently of textual archive checks.
            archive=root/'resolved.tar.gz'
            with tarfile.open(archive,'w:gz') as target:
                item=tarfile.TarInfo('pin/file');item.size=1;target.addfile(item,io.BytesIO(b'x'))
            destination=root/'resolved';destination.mkdir()
            original_resolve=Path.resolve
            def resolved(path,*args,**kwargs):
                return root/'outside' if path==destination/'file' else original_resolve(path,*args,**kwargs)
            with mock.patch.object(Path,'resolve',resolved),self.assertRaisesRegex(ValueError,'escapes preparation'):
                prepare.extract(archive,destination)

    def test_cache_symlink_is_rejected_before_preparation_or_cleanup(self):
        with tempfile.TemporaryDirectory() as directory,mock.patch.object(prepare,'ROOT',Path(directory)),mock.patch.object(prepare,'source_spec',return_value={'archiveSha256':'a'*64}):
            original_is_symlink=Path.is_symlink
            def cache_is_link(path):
                return path==Path(directory)/'.upstream' or original_is_symlink(path)
            with mock.patch.object(Path,'is_symlink',cache_is_link),mock.patch.object(prepare.shutil,'rmtree') as remove:
                with self.assertRaisesRegex(ValueError,'Unsafe upstream cache'):prepare.prepare()
                remove.assert_not_called()

    def test_pending_archive_hash_cannot_enter_normal_locked_build(self):
        with tempfile.TemporaryDirectory() as directory,mock.patch.object(prepare,'ROOT',Path(directory)),mock.patch.object(prepare,'source_spec',return_value={'archiveSha256':None}):
            with self.assertRaisesRegex(ValueError,'explicit first CI bootstrap'):prepare.prepare()

class WorkflowPlanTests(unittest.TestCase):
    def test_actual_workflow_plan_admits_only_requested_fixed_or_bootstrap_proof(self):
        plan=build_policy.decide_plan;pin='a'*64
        self.assertEqual(dict(bootstrap=False,resolve=False,android=True,proof='host-android'),plan(True,pin,'push','',False))
        self.assertEqual('host-only',plan(True,pin,'workflow_dispatch','host',False)['proof'])
        self.assertEqual('host-android',plan(True,pin,'workflow_dispatch','android',False)['proof'])
        self.assertEqual('bootstrap-host-only',plan(False,None,'push','',False)['proof'])
        self.assertEqual('bootstrap-host-only',plan(False,None,'workflow_dispatch','host',True)['proof'])
        self.assertEqual('resolve-host-only',plan(True,pin,'push','',False,True)['proof'])
        with self.assertRaises(ValueError):plan(True,pin,'workflow_dispatch','android',False,True)
        for args in [(False,pin,'push','',False,True),(True,None,'push','',False,True),(True,pin,'push','',True,True)]:
            with self.subTest(resolve=args),self.assertRaises(ValueError):plan(*args)
        for args in [(False,None,'workflow_dispatch','android',True),(False,None,'workflow_dispatch','host',False),
                     (True,pin,'workflow_dispatch','host',True),(True,None,'push','',False),
                     (False,pin,'push','',False),(True,'not-a-hash','push','',False),
                     (True,pin,'pull_request','',False),(True,pin,'workflow_dispatch','injected',False)]:
            with self.subTest(args=args),self.assertRaises(ValueError):plan(*args)

class OverlayAndResolutionTests(unittest.TestCase):
    def overlay(self,root):
        (root/'Cargo.toml').write_bytes(b'approved manifest\n');(root/'Cargo.lock').write_bytes(b'accepted lock\n')
        raw=root/'raw';raw.mkdir();spec=SourceTests().fixture(raw)
        patch=b'--- a/src/lib.rs\n+++ b/src/lib.rs\n@@ -1 +1 @@\n-source fixture\n+adapted source\n--- /dev/null\n+++ b/src/new.rs\n@@ -0,0 +1,1 @@\n+new source\n'
        changes={'src/lib.rs':dict(beforeSha256=spec['files']['src/lib.rs'],afterSha256=build_policy.digest(b'adapted source\n')),'src/new.rs':dict(beforeSha256=None,afterSha256=build_policy.digest(b'new source\n'))}
        files=dict(spec['files']);files.update({k:v['afterSha256'] for k,v in changes.items()})
        (root/'patch').write_bytes(patch)
        metadata=dict(schema=1,patch='patch',patchSha256=sha(root/'patch'),rawFileManifestSha256=build_policy.digest(json.dumps(spec['files'],sort_keys=True,separators=(',',':')).encode()),adaptedFileManifestSha256=build_policy.digest(json.dumps(files,sort_keys=True,separators=(',',':')).encode()),changes=changes,files=files)
        (root/'overlay.json').write_text(json.dumps(metadata))
        policy=dict(schema=1,manifestSha256=sha(root/'Cargo.toml'),resolveLock=True,expectedLockBeforeSha256=sha(root/'Cargo.lock'),targets=['lib','proxy_runtime','api_lifecycle'],overlay='overlay.json',overlaySha256=sha(root/'overlay.json'))
        (root/'build-policy.json').write_text(json.dumps(policy))
        return raw,spec,patch,changes,metadata,policy

    def test_exact_patch_adds_absent_file_and_rejects_context_crlf_and_recreation(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);raw,spec,patch,changes,metadata,policy=self.overlay(root)
            (raw/'src/lib.rs').write_bytes(b'source fixture\r\n')
            with self.assertRaises(ValueError):build_policy.apply_patch(raw,patch,changes)
            self.assertFalse((raw/'src/new.rs').exists())
            (raw/'src/lib.rs').write_bytes(b'source fixture\n')
            build_policy.apply_patch(raw,patch,changes)
            self.assertEqual(b'adapted source\n',(raw/'src/lib.rs').read_bytes());self.assertEqual(b'new source\n',(raw/'src/new.rs').read_bytes())
            with self.assertRaises(ValueError):build_policy.apply_patch(raw,patch,changes)
            with self.assertRaises(ValueError):build_policy.apply_patch(raw,b'--- /dev/null\n+++ b/C:/outside\n@@ -0,0 +1,1 @@\n+x\n',{'C:/outside':dict(beforeSha256=None,afterSha256=build_policy.digest(b'x\n'))})

    def test_exact_hunk_counts_and_declared_file_set_reject_before_writes(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);raw,spec,patch,changes,metadata,policy=self.overlay(root)
            for bad in [patch.replace(b'@@ -0,0 +1,1 @@',b'@@ -0,0 +1,2 @@'),patch.replace(b'@@ -1 +1 @@',b'@@ -1 +2 @@')]:
                with self.assertRaises(ValueError):build_policy.apply_patch(raw,bad,changes)
                self.assertEqual(b'source fixture\n',(raw/'src/lib.rs').read_bytes());self.assertFalse((raw/'src/new.rs').exists())

    def test_policy_binds_manifest_previous_lock_patch_and_full_file_map(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);raw,spec,patch,changes,metadata,policy=self.overlay(root)
            self.assertEqual(['lib','proxy_runtime','api_lifecycle'],build_policy.load_policy(root)['targets'])
            for path in ['Cargo.toml','Cargo.lock','overlay.json','patch']:
                original=(root/path).read_bytes();(root/path).write_bytes(original+b'changed')
                with self.subTest(path=path),self.assertRaises((ValueError,json.JSONDecodeError)):build_policy.load_policy(root)
                (root/path).write_bytes(original)

    def test_archive_preserves_selected_overlay_after_resolution_without_weakening_build_seed(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);self.overlay(root)
            metadata=json.loads((root/'overlay.json').read_text());metadata['patch']='patches/chosen.patch'
            (root/'patches').mkdir();(root/'patches/chosen.patch').write_bytes((root/'patch').read_bytes())
            (root/'source-metadata').mkdir();body=(json.dumps(metadata)+'\n').encode();(root/'source-metadata/chosen.json').write_bytes(body)
            policy=json.loads((root/'build-policy.json').read_text());policy.update(overlay='source-metadata/chosen.json',overlaySha256=build_policy.digest(body))
            (root/'build-policy.json').write_bytes(json.dumps(policy).encode())
            (root/'Cargo.lock').write_bytes(b'actual resolved lock, not the original seed')
            with self.assertRaisesRegex(ValueError,'previous lock'):build_policy.load_policy(root)
            destination=root/'archive';destination.mkdir()
            self.assertEqual(['source-metadata/chosen.json','patches/chosen.patch'],build_policy.copy_policy_evidence(root,destination))
            self.assertEqual(body,(destination/'source-metadata/chosen.json').read_bytes())
            self.assertEqual((root/'patch').read_bytes(),(destination/'patches/chosen.patch').read_bytes())
            self.assertFalse((destination/'overlay.json').exists())
            evidence=json.loads((destination/'source-policy-evidence.json').read_text())
            self.assertTrue(evidence['archiveOnly'])
            self.assertEqual(policy['expectedLockBeforeSha256'],evidence['expectedBeforeLockSha256'])
            self.assertEqual(sha(root/'Cargo.lock'),evidence['actualAfterLockSha256'])
            self.assertNotIn('acceptedLock',evidence)
            (root/'patches/chosen.patch').write_bytes(b'corrupted source patch')
            with self.assertRaisesRegex(ValueError,'patch identity'):build_policy.copy_policy_evidence(root,destination)

    def test_locked_policy_requires_the_exact_accepted_lock_without_implicit_resolution(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);self.overlay(root)
            policy=json.loads((root/'build-policy.json').read_text());policy['resolveLock']=False
            (root/'build-policy.json').write_bytes(json.dumps(policy).encode())
            self.assertFalse(build_policy.load_policy(root)['resolveLock'])
            (root/'Cargo.lock').write_bytes(b'another otherwise valid candidate lock')
            with self.assertRaisesRegex(ValueError,'approved accepted lock'):build_policy.load_policy(root)
            destination=root/'archive';destination.mkdir()
            build_policy.copy_policy_evidence(root,destination)
            self.assertTrue(json.loads((destination/'source-policy-evidence.json').read_text())['archiveOnly'])

    def test_two_stage_prepare_verifies_raw_and_adapted_cache_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);raw,spec,patch,changes,metadata,policy=self.overlay(root)
            archive=root/'fixture.tar.gz'
            with tarfile.open(archive,'w:gz') as target:
                for file in raw.rglob('*'):
                    if file.is_file():target.add(file,arcname='pin/'+file.relative_to(raw).as_posix())
            spec['archiveSha256']=sha(archive)
            with mock.patch.object(prepare,'ROOT',root),mock.patch.object(prepare,'source_spec',return_value=spec):
                prepared=prepare.prepare(archive)
                self.assertEqual(metadata['adaptedFileManifestSha256'],verify(prepared,dict(spec,files=metadata['files']))['fileManifestSha256'])
                self.assertEqual(b'source fixture\n',(root/'.upstream/meow-rs-raw/src/lib.rs').read_bytes())
                (prepared/'src/new.rs').write_bytes(b'cache corruption')
                prepared=prepare.prepare(archive);self.assertEqual(b'new source\n',(prepared/'src/new.rs').read_bytes())
                self.assertEqual(metadata['patchSha256'],json.loads((prepared/'AEROVE_SOURCE_PROVENANCE.json').read_text())['patchSha256'])

    def test_unapproved_resolution_and_android_resolution_stop_before_tools(self):
        for argv,policy in [(['build.py','--resolve-lock'],None),(['build.py','--target','x86_64-linux-android','--ndk','unused'],{'resolveLock':True})]:
            with self.subTest(argv=argv),mock.patch.object(build,'load_policy',return_value=policy),mock.patch.object(sys,'argv',argv),mock.patch.object(build,'prepare') as source,mock.patch.object(build,'run') as command:
                with self.assertRaises(ValueError):build.main()
                source.assert_not_called();command.assert_not_called()

    def test_explicit_resolution_preserves_previous_lock_and_marks_failed_metadata_unaccepted(self):
        for success in [False,True]:
            with self.subTest(success=success),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);(root/'Cargo.toml').write_text('[package]\nname="aerove-meow-core"\n[lib]\nname="aerove_meow_core"\n[dependencies]\n')
                (root/'Cargo.lock').write_bytes(b'accepted lock\n');previous=sha(root/'Cargo.lock');upstream=root/'source';upstream.mkdir();(upstream/'AEROVE_SOURCE_PROVENANCE.json').write_text('{}');(root/'source.json').write_text('{}')
                policy=dict(resolveLock=True,expectedLockBeforeSha256=previous,targets=['lib','proxy_runtime','api_lifecycle'],policySha256='pinned policy')
                def metadata(*args,**kwargs):
                    (root/'Cargo.lock').write_bytes(b'actually resolved fixture lock\n')
                    result=subprocess.CompletedProcess(args[0],0 if success else 42,b'{}',b'resolver diagnostic')
                    Path(args[3]).write_bytes(result.stdout);Path(args[4]).write_bytes(result.stderr);return result
                def run(command,*args,**kwargs):
                    if command[0]=='rustc':return 'rustc 1.98.1 fixture'
                    self.assertIn('--locked',command);self.assertIn('api_lifecycle',command)
                with mock.patch.object(build,'probe_libclang',return_value={'library':{'path':'unit-test-fixture-only'}}),mock.patch('builtins.print'),mock.patch.object(build,'ROOT',root),mock.patch.object(build,'load_policy',return_value=policy),mock.patch.object(build,'prepare',return_value=upstream),mock.patch.object(build,'run',side_effect=run),mock.patch.object(build,'run_logged',side_effect=metadata),mock.patch.object(sys,'argv',['build.py','--resolve-lock']):
                    if success:build.main()
                    else:
                        with self.assertRaises(subprocess.CalledProcessError):build.main()
                receipt=json.loads((root/'build/host-receipt.json').read_text())
                self.assertEqual(b'accepted lock\n',(root/'build/lock-before-resolution.lock').read_bytes());self.assertEqual(previous,receipt['lockSha256Before'])
                self.assertEqual(success,receipt['acceptedLock']);self.assertEqual(0 if success else 42,receipt['metadataExit'])
                self.assertEqual(b'resolver diagnostic',(root/'build/metadata.stderr.log').read_bytes())


    def test_feature_boundary_policy_is_optional_and_rejects_other_commands(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);self.overlay(root)
            policy=json.loads((root/'build-policy.json').read_text())
            self.assertIsNone(build_policy.feature_boundary_command(build_policy.load_policy(root)))
            policy['featureBoundary']='capabilities-no-default'
            (root/'build-policy.json').write_bytes(json.dumps(policy).encode())
            self.assertEqual(['cargo','test','--locked','--no-default-features','--lib','capabilities::tests::','--','--test-threads=1','--nocapture'],build_policy.feature_boundary_command(build_policy.load_policy(root)))
            for invalid in [None,True,[],{'command':'shell'},'arbitrary-test']:
                policy['featureBoundary']=invalid;(root/'build-policy.json').write_bytes(json.dumps(policy).encode())
                with self.subTest(value=invalid),self.assertRaises(ValueError):build_policy.load_policy(root)

    def test_feature_boundary_failure_or_lock_mutation_cannot_accept_a_resolved_lock(self):
        for outcome in ['success','failure','mutated-lock','primary-failure','core-failure']:
            with self.subTest(outcome=outcome),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);(root/'Cargo.toml').write_text('[package]\nname="aerove-meow-core"\n[lib]\nname="aerove_meow_core"\n[dependencies]\n')
                (root/'Cargo.lock').write_bytes(b'accepted lock\n');previous=sha(root/'Cargo.lock')
                upstream=root/'source';upstream.mkdir();(upstream/'AEROVE_SOURCE_PROVENANCE.json').write_text('{}');(root/'source.json').write_text('{}')
                policy=dict(resolveLock=True,expectedLockBeforeSha256=previous,targets=['lib','proxy_runtime'],policySha256='fixture policy',featureBoundary='capabilities-no-default')
                executed=[]
                def metadata(*args,**kwargs):
                    (root/'Cargo.lock').write_bytes(b'resolved lock\n')
                    result=subprocess.CompletedProcess(args[0],0,b'{}',b'')
                    Path(args[3]).write_bytes(result.stdout);Path(args[4]).write_bytes(result.stderr);return result
                def run(command,*args,**kwargs):
                    if command[0]=='rustc':return 'rustc 1.98.1 fixture'
                    executed.append(command)
                    if '--no-default-features' not in command and outcome=='primary-failure':raise subprocess.CalledProcessError(101,command)
                    if '--no-default-features' in command:
                        if outcome=='failure':raise subprocess.CalledProcessError(19,command)
                        if outcome=='mutated-lock':(root/'Cargo.lock').write_bytes(b'unexpected mutation\n')
                with mock.patch.object(build,'execute_core_tests',side_effect=subprocess.CalledProcessError(23,['fixed-core-unit']) if outcome=='core-failure' else None),mock.patch.object(build,'probe_libclang',return_value={'library':{'path':'unit-test-fixture-only'}}),mock.patch('builtins.print'),mock.patch.object(build,'ROOT',root),mock.patch.object(build,'load_policy',return_value=policy),mock.patch.object(build,'prepare',return_value=upstream),mock.patch.object(build,'run',side_effect=run),mock.patch.object(build,'run_logged',side_effect=metadata),mock.patch.object(sys,'argv',['build.py','--resolve-lock']):
                    if outcome=='success':build.main()
                    else:
                        with self.assertRaises((subprocess.CalledProcessError,ValueError)):build.main()
                receipt=json.loads((root/'build/host-receipt.json').read_text())
                self.assertIn('--no-fail-fast',executed[0])
                if outcome=='primary-failure':
                    self.assertEqual(1,len(executed));self.assertFalse(receipt['acceptedLock']);self.assertEqual(101,receipt['commandExit'])
                    self.assertNotIn('featureBoundaryCommand',receipt);self.assertEqual(executed[0],receipt['command']);continue
                self.assertEqual(2,len(executed));self.assertNotIn('--no-default-features',executed[0]);self.assertNotIn('--no-fail-fast',executed[1])
                self.assertEqual(build_policy.feature_boundary_command(policy),executed[1]);self.assertEqual(executed[0],receipt['command'])
                self.assertEqual(executed[1],receipt['featureBoundaryCommand'])
                self.assertEqual(19 if outcome=='failure' else 0,receipt['featureBoundaryExit'])
                self.assertEqual(outcome=='success',receipt['acceptedLock'])
                if outcome=='core-failure':self.assertEqual(23,receipt['commandExit'])
                self.assertEqual(outcome!='mutated-lock',receipt['lockUnchangedDuringLockedCommand'])
                self.assertEqual(b'accepted lock\n',(root/'build/lock-before-resolution.lock').read_bytes())

class ClangProbeTests(unittest.TestCase):
    def library_file(self,root):
        path=root/'libclang-20.so.1';header=bytearray(20);header[:6]=b'\x7fELF\x02\x01';header[16:18]=(3).to_bytes(2,'little');header[18:20]=(62).to_bytes(2,'little');path.write_bytes(header);return path

    def test_actual_loader_branch_checks_elf_c_api_version_and_disposes_string(self):
        with tempfile.TemporaryDirectory() as directory:
            path=self.library_file(Path(directory));library=types.SimpleNamespace(**{name:mock.Mock() for name in clang_probe.REQUIRED_EXPORTS})
            library.clang_getClangVersion.return_value=clang_probe.CXString(None,0);library.clang_getCString.return_value=b'clang version 20.1.8 unit-test-only'
            with mock.patch.object(clang_probe.ctypes,'CDLL',return_value=library) as loader:
                result=clang_probe.load_library(path);self.assertEqual(str(path.resolve()),result['path']);self.assertEqual(sha(path),result['sha256']);loader.assert_called_once_with(str(path));library.clang_disposeString.assert_called_once()
            path.write_bytes(b'not an ELF library')
            with mock.patch.object(clang_probe.ctypes,'CDLL') as loader,self.assertRaises(ValueError):clang_probe.load_library(path)
            loader.assert_not_called()

    def test_probe_writes_negative_receipt_and_never_accepts_an_unloadable_library(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);path=self.library_file(root);output=root/'probe.json'
            with mock.patch.object(clang_probe.sys,'platform','linux'),mock.patch.object(clang_probe.platform,'machine',return_value='x86_64'),mock.patch.object(clang_probe,'candidates',return_value=[path]),mock.patch.object(clang_probe,'load_library',side_effect=OSError('unit-test load failure')):
                with self.assertRaisesRegex(ValueError,'No existing host libclang'):clang_probe.probe(output,{})
            result=json.loads(output.read_text());self.assertEqual('failure',result['status']);self.assertNotIn('library',result);self.assertEqual('OSError',result['attempts'][0]['errorType'])

    def test_explicit_library_path_does_not_fall_back_to_clang_executable_or_cpp(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);path=self.library_file(root);(root/'libclang-cpp.so.20').write_bytes(b'not the C API')
            self.assertEqual([path.resolve()],clang_probe.candidates({'LIBCLANG_PATH':str(root)}))
            self.assertEqual([],clang_probe.candidates({'LIBCLANG_PATH':str(root/'clang')}))

class BindgenGraphTests(unittest.TestCase):
    def test_target_only_feature_requires_actual_metadata_and_lock_dependency_edge(self):
        manifest={'dependencies':{'rquickjs':{'version':'=0.12.2','default-features':False}},'target':{'cfg(target_os = "android")':{'dependencies':{'rquickjs':{'version':'=0.12.2','default-features':False,'features':['bindgen']}}}}}
        metadata={'packages':[{'id':'sys','name':'rquickjs-sys','version':'0.12.2'},{'id':'bindgen','name':'bindgen','version':'0.72.1'}],'resolve':{'nodes':[{'id':'sys','features':['bindgen'],'deps':[{'name':'bindgen_rs','pkg':'bindgen'}]}]}}
        lock={'package':[{'name':'rquickjs-sys','version':'0.12.2','dependencies':['cc','bindgen']}]}
        self.assertTrue(build.verify_bindgen_graph(metadata,lock,manifest)['lockDependencyEdgeVerified'])
        lock['package'][0]['dependencies']=['cc']
        with self.assertRaisesRegex(ValueError,'lacks'):build.verify_bindgen_graph(metadata,lock,manifest)
        lock['package'][0]['dependencies']=['cc','bindgen'];metadata['resolve']['nodes'][0]['features']=[]
        with self.assertRaisesRegex(ValueError,'did not activate'):build.verify_bindgen_graph(metadata,lock,manifest)
        manifest['dependencies']['rquickjs']['features']=['bindgen']
        with self.assertRaisesRegex(ValueError,'target-only'):build.android_bindgen_requested(manifest)

class AndroidRecipeTests(unittest.TestCase):
    def test_exact_ndk_target_cmake_and_16k_linker_arguments(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);ndk=root/'ndk';ndk.mkdir();(ndk/'source.properties').write_text('Pkg.Revision = 28.2.13676358\n')
            compiler=ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin';compiler.mkdir(parents=True)
            (compiler.parent/'sysroot/usr/include/android').mkdir(parents=True);(compiler.parent/'sysroot/usr/include/android/api-level.h').touch()
            for target in build.TARGETS:
                (compiler.parent/'sysroot/usr/include'/target).mkdir()
                for name in (target+'33-clang',target+'33-clang++','llvm-ar','llvm-readelf'):(compiler/name).touch()
                with mock.patch.object(build,'ROOT',root):environment,readelf=build.android_environment(ndk,target,{})
                self.assertEqual('c++_static',environment['BORING_BSSL_RUST_CPPLIB'])
                self.assertIn('max-page-size=16384',environment['CARGO_TARGET_'+target.replace('-','_').upper()+'_RUSTFLAGS'])
                flags=shlex.split(environment['CARGO_TARGET_'+target.replace('-','_').upper()+'_RUSTFLAGS'])
                self.assertEqual(['-C','link-arg=-Wl,-Bstatic','-C','link-arg=-lc++','-C','link-arg=-Wl,-Bdynamic','-C','link-arg=-Wl,--no-undefined'],flags[2:])
                self.assertIn('android-33',Path(environment['CMAKE_TOOLCHAIN_FILE_'+target]).read_text())
                self.assertEqual(compiler/'llvm-readelf',readelf)
                self.assertEqual(['--sysroot='+str(compiler.parent/'sysroot'),'--target='+target+'33'],shlex.split(environment['BINDGEN_EXTRA_CLANG_ARGS_'+target]))
                self.assertEqual(environment['BINDGEN_EXTRA_CLANG_ARGS_'+target],environment['BINDGEN_EXTRA_CLANG_ARGS_'+target.replace('-','_')])
                self.assertEqual(str(compiler/(target+'33-clang')),environment['CLANG_PATH'])
                self.assertNotIn('BINDGEN_EXTRA_CLANG_ARGS',environment)
            (ndk/'source.properties').write_text('Pkg.Revision = 28.1.0')
            with self.assertRaises(ValueError):build.android_environment(ndk,'x86_64-linux-android',{})

    def test_elf_abi_and_actual_readelf_load_alignment_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'lib.so';header=bytearray(20);header[:6]=b'\x7fELF\x02\x01';header[16:18]=(3).to_bytes(2,'little');header[18:20]=(62).to_bytes(2,'little');path.write_bytes(header)
            with mock.patch.object(build.subprocess,'check_output',return_value='  LOAD 0 0 0 0 0 R E 0x4000\n'):
                build.verify_elf(path,62,'readelf')
                with self.assertRaises(ValueError):build.verify_elf(path,183,'readelf')
            with mock.patch.object(build.subprocess,'check_output',return_value='  LOAD 0 0 0 0 0 R E 0x1000\n'):
                with self.assertRaises(ValueError):build.verify_elf(path,62,'readelf')

    def test_required_script_jni_exports_are_read_from_dynamic_symbols(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'lib.so';header=bytearray(20);header[:6]=b'\x7fELF\x02\x01';header[16:18]=(3).to_bytes(2,'little');header[18:20]=(62).to_bytes(2,'little');path.write_bytes(header)
            symbols='\n'.join(' 1: 00000123 42 FUNC GLOBAL DEFAULT 1 '+name for name in build.SCRIPT_JNI_SYMBOLS)
            with mock.patch.object(build.subprocess,'check_output',side_effect=[' LOAD 0 0 0 0 0 R E 0x4000\n','NEEDED libc.so',symbols]):build.verify_elf(path,62,'readelf',build.SCRIPT_JNI_SYMBOLS)
            diagnostics=path.parent/'readelf.txt'
            with mock.patch.object(build.subprocess,'check_output',side_effect=[' LOAD 0 0 0 0 0 R E 0x4000\n','NEEDED libc.so',symbols.replace('DEFAULT 1','DEFAULT UND')]),self.assertRaisesRegex(ValueError,'JNI symbols'):build.verify_elf(path,62,'readelf',build.SCRIPT_JNI_SYMBOLS,diagnostics)
            self.assertIn('DEFAULT UND',diagnostics.read_text())

    def test_complete_owned_runtime_requires_all_fifteen_defined_runtime_and_four_script_jni_symbols(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'lib.so';header=bytearray(20);header[:6]=b'\x7fELF\x02\x01';header[16:18]=(3).to_bytes(2,'little');header[18:20]=(62).to_bytes(2,'little');path.write_bytes(header)
            required=build.SCRIPT_JNI_SYMBOLS+build.RUNTIME_JNI_SYMBOLS
            self.assertEqual(19,len(set(required)))
            self.assertIn('Java_dev_aerove_lite_core_MeowNativeCore_awaitExit',required)
            symbols='\n'.join(' 1: 00000123 42 FUNC GLOBAL DEFAULT 1 '+name for name in required)
            with mock.patch.object(build.subprocess,'check_output',side_effect=[' LOAD 0 0 0 0 0 R E 0x4000\n','NEEDED libc.so',symbols]):build.verify_elf(path,62,'readelf',required)
            for name in build.RUNTIME_JNI_SYMBOLS:
                missing=symbols.replace('DEFAULT 1 '+name,'DEFAULT UND '+name)
                with self.subTest(name=name),mock.patch.object(build.subprocess,'check_output',side_effect=[' LOAD 0 0 0 0 0 R E 0x4000\n','NEEDED libc.so',missing]),self.assertRaisesRegex(ValueError,name):build.verify_elf(path,62,'readelf',required)


    def test_unresolved_cpp_runtime_is_rejected_for_all_required_abi_families(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'lib.so';header=bytearray(20);header[:6]=b'\x7fELF\x02\x01';header[16:18]=(3).to_bytes(2,'little');header[18:20]=(62).to_bytes(2,'little');path.write_bytes(header)
            for name in ['__gxx_personality_v0','__cxa_begin_catch','__cxa_throw','_ZSt9terminatev','_ZTISt9exception','_Unwind_Resume']:
                symbols=' 1: 00000000 0 NOTYPE GLOBAL DEFAULT UND '+name
                with self.subTest(name=name),mock.patch.object(build.subprocess,'check_output',side_effect=[' LOAD 0 0 0 0 0 R E 0x4000\n','NEEDED libc.so',symbols]),self.assertRaisesRegex(ValueError,r'unresolved C\+\+ runtime'):
                    build.verify_elf(path,62,'readelf')

    def test_cpp_runtime_gate_keeps_bionic_and_optional_weak_imports_valid(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'lib.so';header=bytearray(20);header[:6]=b'\x7fELF\x02\x01';header[16:18]=(3).to_bytes(2,'little');header[18:20]=(62).to_bytes(2,'little');path.write_bytes(header)
            symbols='\n'.join([' 1: 00000000 0 FUNC GLOBAL DEFAULT UND __cxa_finalize@LIBC',' 2: 00000000 0 FUNC GLOBAL DEFAULT UND __cxa_atexit@LIBC',' 3: 00000000 0 FUNC GLOBAL DEFAULT UND __cxa_thread_atexit_impl@LIBC',' 4: 00000000 0 FUNC WEAK DEFAULT UND __gxx_personality_v0',' 5: 00000123 4 FUNC GLOBAL DEFAULT 1 __gxx_personality_v0'])
            with mock.patch.object(build.subprocess,'check_output',side_effect=[' LOAD 0 0 0 0 0 R E 0x4000\n','NEEDED libc.so',symbols]):
                build.verify_elf(path,62,'readelf')


class ResourceHealthContractTests(unittest.TestCase):
    def test_all_26_symbols_are_independently_required_in_defined_dynamic_exports(self):
        required=build.RUNTIME_JNI_SYMBOLS+build.SCRIPT_JNI_SYMBOLS+list(build.RESOURCE_JNI_SYMBOLS)
        self.assertEqual(26,len(set(required)))
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'lib.so';header=bytearray(20);header[:6]=b'\x7fELF\x02\x01';header[16:18]=(3).to_bytes(2,'little');header[18:20]=(62).to_bytes(2,'little');path.write_bytes(header)
            for omitted in [None]+required:
                symbols='\n'.join(' 1: 00000123 42 FUNC GLOBAL DEFAULT '+('UND' if name==omitted else '1')+' '+name for name in required)
                with self.subTest(omitted=omitted),mock.patch.object(build.subprocess,'check_output',side_effect=[' LOAD 0 0 0 0 0 R E 0x4000\n','NEEDED libc.so',symbols]):
                    if omitted is None:build.verify_elf(path,62,'readelf',required)
                    else:
                        with self.assertRaisesRegex(ValueError,omitted):build.verify_elf(path,62,'readelf',required)

    def test_resource_source_descriptors_reject_each_missing_or_changed_edge(self):
        source=(build.ROOT/'src/meow_jni.rs').read_text()
        actual=re.findall(r'pub extern \"system\" fn (Java_\w+)',source+(build.ROOT/'src/script_jni.rs').read_text())
        self.assertEqual(set(build.RUNTIME_JNI_SYMBOLS+build.SCRIPT_JNI_SYMBOLS+list(build.RESOURCE_JNI_SYMBOLS)),set(actual))
        self.assertEqual(26,len(actual))
        self.assertEqual(build.RESOURCE_JNI_DESCRIPTORS,build.verify_resource_jni_source(build.ROOT))
        for symbol in build.RESOURCE_JNI_SYMBOLS:
            with self.subTest(symbol=symbol),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);(root/'src').mkdir()
                (root/'src/meow_jni.rs').write_text(source.replace(symbol,symbol+'_changed'))
                with self.assertRaisesRegex(ValueError,'source export'):build.verify_resource_jni_source(root)
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'src').mkdir()
            (root/'src/meow_jni.rs').write_text(source.replace("plan:JString<'local>", "plan:i64"))
            with self.assertRaisesRegex(ValueError,'descriptor'):build.verify_resource_jni_source(root)
            (root/'src/meow_jni.rs').write_text(source.replace("_class:JClass<'local>","_class:JObject<'local>"))
            with self.assertRaisesRegex(ValueError,'receiver'):build.verify_resource_jni_source(root)

    def test_every_resource_edge_after_create_takes_the_owner_handle_first(self):
        for symbol,descriptor in build.RESOURCE_JNI_DESCRIPTORS.items():
            with self.subTest(symbol=symbol):
                self.assertEqual(not symbol.endswith('_nativeCreate'),descriptor.startswith('(J'))
        source=(build.ROOT/'src/meow_jni.rs').read_text()
        for name in ['nativeValidatePrepared','nativePreflightRuleResource']:
            symbol='Java_dev_aerove_lite_core_JniResourceFetch_'+name
            with self.subTest(symbol=symbol),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);(root/'src').mkdir()
                (root/'src/meow_jni.rs').write_text(source.replace(symbol+"<'local>(mut env:EnvUnowned<'local>,_class:JClass<'local>,handle:i64,",symbol+"<'local>(mut env:EnvUnowned<'local>,_class:JClass<'local>,"))
                with self.assertRaisesRegex(ValueError,'descriptor'):build.verify_resource_jni_source(root)

    def test_resource_health_producer_policy_cannot_drop_any_native_target(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);_,_,_,_,_,policy=OverlayAndResolutionTests().overlay(root)
            policy.update(coreTests='policy-runtime-resource-health-v1',coreTestLockSha256='a'*64,targets=list(build_policy.RESOURCE_HEALTH_TARGETS))
            (root/'build-policy.json').write_text(json.dumps(policy));build_policy.load_policy(root)
            for omitted in policy['targets']:
                broken=dict(policy,targets=[t for t in policy['targets'] if t!=omitted])
                (root/'build-policy.json').write_text(json.dumps(broken))
                with self.subTest(omitted=omitted),self.assertRaisesRegex(ValueError,'every approved Native target'):build_policy.load_policy(root)

    def test_exact_added_native_passes_and_negative_missing_duplicate_or_ignored(self):
        names=[n for cases in build.RESOURCE_HEALTH_CASES.values() for n in cases]
        self.assertEqual(32,len(names))
        output='\n'.join('test fixture::'+n+' ... ok' for n in names)
        self.assertEqual(build.RESOURCE_HEALTH_CASES,build.added_native_test_proof(output))
        for n in names:
            for bad in [output.replace('test fixture::'+n+' ... ok',''),output+'\ntest duplicate::'+n+' ... ok',output.replace(n+' ... ok',n+' ... ignored')]:
                with self.subTest(name=n),self.assertRaisesRegex(ValueError,'Native test pass'):build.added_native_test_proof(bad)

class ResourceHealthTableTests(unittest.TestCase):
    """build.py's receipt table and verify_apk.py's acceptance table must name the same cases in the same order."""
    def test_build_and_apk_verification_tables_are_identical(self):
        verify_apk=_load_verify_apk()
        self.assertEqual(build.RESOURCE_HEALTH_CASES,verify_apk.RESOURCE_HEALTH_CASES)
        self.assertEqual([(t,list(c)) for t,c in build.RESOURCE_HEALTH_CASES.items()],[(t,list(c)) for t,c in verify_apk.RESOURCE_HEALTH_CASES.items()])

    def test_one_sided_table_drift_is_caught(self):
        path=build.ROOT.parent/'tool/verify_apk.py';source=path.read_text()
        for target,cases in build.RESOURCE_HEALTH_CASES.items():
            drifted=source.replace(repr(cases[-1]),repr(cases[-1]+'_renamed'),1)
            self.assertNotEqual(source,drifted)
            module=types.ModuleType('verify_apk_drift');module.__file__=str(path)
            exec(compile(drifted,str(path),'exec'),module.__dict__)
            with self.subTest(target=target):self.assertNotEqual(build.RESOURCE_HEALTH_CASES,module.RESOURCE_HEALTH_CASES)

class RuleResourceBoundTests(unittest.TestCase):
    """Kotlin rule-resource bounds are the Native admission constants, not a separate host policy."""
    KOTLIN='app/src/main/java/dev/aerove/lite/data/RuleResourceText.kt'
    def test_kotlin_bounds_equal_native_admission_constants(self):
        rust=(build.ROOT/'src/config_validation.rs').read_text()
        kotlin=(build.ROOT.parent/self.KOTLIN).read_text()
        def product(expression):
            expression=expression.replace('_','').replace('L','')
            self.assertRegex(expression,r'^[0-9 *]+$')
            result=1
            for factor in expression.split('*'):result*=int(factor)
            return result
        def rust_bytes(name):
            value=re.search(r'pub const '+name+r': usize = ([^;]+);',rust).group(1)
            return rust_bytes(value) if re.fullmatch(r'[A-Z_]+',value) else product(value)
        def kotlin_value(name):
            return product(re.search(r'internal const val '+name+r' = ([^\n]+)',kotlin).group(1))
        self.assertEqual(64*1024*1024,rust_bytes('MAX_INPUT_BYTES'))
        self.assertEqual(rust_bytes('MAX_INPUT_BYTES'),kotlin_value('MAX_RULE_INPUT_BYTES'))
        self.assertEqual(rust_bytes('MAX_DUMP_TEXT_BYTES'),kotlin_value('MAX_RULE_TEXT_BYTES'))
        self.assertEqual(rust_bytes('MAX_DUMP_TEXT_BYTES'),rust_bytes('MAX_RULE_TEXT_BYTES'))
        self.assertEqual(rust_bytes('MAX_ENTRIES'),kotlin_value('MAX_RULE_PAYLOAD_ENTRIES'))
        self.assertEqual(2048,rust_bytes('MAX_ENTRY_BYTES'))
        self.assertEqual(rust_bytes('MAX_ENTRY_BYTES'),kotlin_value('MAX_RULE_ENTRY_BYTES'))
        self.assertIn('pub const MAX_YAML_RULE_VALUES: usize = MAX_ENTRIES + 2;',rust)
        self.assertIsNotNone(re.search(r'^internal const val MAX_RULE_YAML_VALUES = MAX_RULE_PAYLOAD_ENTRIES \+ 2$',kotlin,re.M))
        self.assertNotIn('> 2048',rust)
        self.assertIn('if bytes.len() > MAX_RULE_TEXT_BYTES { return Err(format!("rule-provider text input exceeds {MAX_RULE_TEXT_BYTES} bytes")); }',rust)

def _load_verify_apk():
    spec=importlib.util.spec_from_file_location('verify_apk_tables',build.ROOT.parent/'tool/verify_apk.py')
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    return module

class StrictJniAbiTests(unittest.TestCase):
    """All 26 bridge exports: Rust signature, Kotlin external, build.py tables and verify_apk.py agree without wildcards."""
    APPROVED={**build.RUNTIME_JNI_DESCRIPTORS,**build.SCRIPT_JNI_DESCRIPTORS,**build.RESOURCE_JNI_DESCRIPTORS}
    KOTLIN=['app/src/main/java/dev/aerove/lite/core/MeowNativeCore.kt','app/src/main/java/dev/aerove/lite/core/JniResourceFetch.kt',
        'app/src/main/java/dev/aerove/lite/overrides/NativeScriptEngine.kt','app/src/main/java/dev/aerove/lite/core/NativeCore.kt']
    def tree(self,directory,edit=None):
        """Copy only the gate inputs; edit=(relative path, old, new) applies one exact one-sided drift."""
        lite=Path(directory);root=lite/'meow-native'
        for relative in [*('meow-native/'+name for name in jni_abi.RUST_SOURCES),*self.KOTLIN,'tool/verify_apk.py']:
            target=lite/relative;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes((build.ROOT.parent/relative).read_bytes())
        if edit:
            relative,old,new=edit;path=lite/relative;text=path.read_text(encoding='utf-8')
            self.assertEqual(1,text.count(old),'mutation anchor: '+old);path.write_text(text.replace(old,new),encoding='utf-8')
        return root

    def test_repository_exports_match_rust_kotlin_build_tables_and_apk_gate(self):
        self.assertEqual(26,len(self.APPROVED));self.assertEqual(15,len(build.RUNTIME_JNI_DESCRIPTORS));self.assertEqual(4,len(build.SCRIPT_JNI_DESCRIPTORS))
        self.assertEqual([],jni_abi.check(build.ROOT,self.APPROVED))
        self.assertEqual(list(build.RUNTIME_JNI_DESCRIPTORS),build.RUNTIME_JNI_SYMBOLS)
        self.assertEqual(list(build.SCRIPT_JNI_DESCRIPTORS),build.SCRIPT_JNI_SYMBOLS)
        for name in ['validate','validateResource','inspectRuleSet','dumpRuleSet']:
            self.assertTrue(build.RUNTIME_JNI_DESCRIPTORS['Java_dev_aerove_lite_core_MeowNativeCore_'+name].startswith('(J'),name)

    def test_each_one_sided_drift_is_rejected_by_the_build_gate(self):
        core='app/src/main/java/dev/aerove/lite/core/MeowNativeCore.kt';jni='meow-native/src/meow_jni.rs';script='meow-native/src/script_jni.rs'
        release='pub extern "system" fn Java_dev_aerove_lite_overrides_NativeScriptEngine_nativeRelease<'
        drifts={
            'Rust validate loses its owner handle':(jni,"_class: JClass<'local>, handle: i64, source: JString<'local>) -> JString<'local> {","_class: JClass<'local>, source: JString<'local>) -> JString<'local> {"),
            'Kotlin dumpRuleSet loses its owner handle':(core,'external fun dumpRuleSet(handle: Long, path: String,','external fun dumpRuleSet(path: String,'),
            'Kotlin inspectRuleSet becomes an instance method':(core,'@JvmStatic external fun inspectRuleSet(','external fun inspectRuleSet('),
            'Kotlin parameter type without strict mapping':(core,'external fun awaitReady(handle: Long)','external fun awaitReady(handle: ByteArray)'),
            'Kotlin declaration carries an unchecked annotation':(core,'@JvmStatic external fun stop(','@JvmStatic @JvmName("halt") external fun stop('),
            'Kotlin internal external has a mangled JVM name':(core,'@JvmStatic external fun stop(','@JvmStatic internal external fun stop('),
            'Kotlin result drifts':(core,'external fun capabilities(): String','external fun capabilities(): Int'),
            'Rust startOwned object parameter is unmapped':(jni,"protector: JObject<'local>, owner: JObject<'local>","callback: JObject<'local>, owner: JObject<'local>"),
            'Rust result drifts':(jni,"_MeowNativeCore_awaitReady<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>, handle: i64) {","_MeowNativeCore_awaitReady<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>, handle: i64) -> i64 {"),
            'Rust callback signature drifts from the Kotlin interface':(jni,'jni_sig!("(I)Z")','jni_sig!("(J)Z")'),
            'Kotlin callback interface drifts from Rust':('app/src/main/java/dev/aerove/lite/core/NativeCore.kt','fun protect(fd: Int): Boolean','fun protect(fd: Long): Boolean'),
            'script export becomes static':(script,"nativeBegin<'local>(mut env: EnvUnowned<'local>, _this: JObject<'local>)","nativeBegin<'local>(mut env: EnvUnowned<'local>, _this: JClass<'local>)"),
            'Rust export without an approved Kotlin native':(script,'#[unsafe(no_mangle)]\n'+release,
                "#[unsafe(no_mangle)]\npub extern \"system\" fn Java_dev_aerove_lite_overrides_NativeScriptEngine_nativeExtra<'local>(mut env: EnvUnowned<'local>, _this: JObject<'local>) {}\n#[unsafe(no_mangle)]\n"+release),
            'Rust export bypasses the strict no_mangle form':(script,'#[unsafe(no_mangle)]\n'+release,'#[no_mangle]\n'+release),
            'verify_apk.py loses an export':('tool/verify_apk.py','"configurePolicies", "policyStatus"','"configurePolicies"'),
        }
        for label,edit in drifts.items():
            with self.subTest(label),tempfile.TemporaryDirectory() as directory:
                root=self.tree(directory,edit)
                self.assertTrue(jni_abi.check(root,self.APPROVED),label+' was accepted')
                with self.assertRaisesRegex(ValueError,'Strict JNI ABI mismatch'):build.verify_resource_jni_source(root)
        with tempfile.TemporaryDirectory() as directory:
            root=self.tree(directory);self.assertEqual(build.RESOURCE_JNI_DESCRIPTORS,build.verify_resource_jni_source(root))
            for omitted in self.APPROVED:
                with self.subTest(table_omits=omitted):self.assertTrue(jni_abi.check(root,{k:v for k,v in self.APPROVED.items() if k!=omitted}))

    def test_classfile_mode_compares_compiled_natives_and_callback_interfaces(self):
        def classfile(path,methods):
            pool=[];index={}
            def utf8(text):
                if text not in index:pool.append(b'\x01'+len(text.encode()).to_bytes(2,'big')+text.encode());index[text]=len(pool)
                return index[text]
            body=b''.join(access.to_bytes(2,'big')+utf8(name).to_bytes(2,'big')+utf8(descriptor).to_bytes(2,'big')+b'\x00\x00' for name,descriptor,access in methods)
            data=b'\xca\xfe\xba\xbe\x00\x00\x00\x34'+(len(pool)+1).to_bytes(2,'big')+b''.join(pool)+b'\x00\x21\x00\x00\x00\x00\x00\x00\x00\x00'+len(methods).to_bytes(2,'big')+body+b'\x00\x00'
            path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
        def compile_fixture(classes,approved,flip=None):
            for cls,(package,_,static) in jni_abi.CLASSES.items():
                prefix=jni_abi.symbol(cls,'')
                methods=[(name[len(prefix):],descriptor,0x0100|(0x0008 if static!=(name==flip) else 0)) for name,descriptor in approved.items() if name.startswith(prefix)]
                classfile(Path(classes)/package.replace('.','/')/(cls+'.class'),methods+[('readStatus','(J)Ljava/lang/Object;',0x0011)])
            classfile(Path(classes)/'dev/aerove/lite/core/SocketProtector.class',[('protect','(I)Z',0x0401)])
            classfile(Path(classes)/'dev/aerove/lite/core/ConnectionOwnerLookup.class',[('resolveConnectionOwner','(ILjava/lang/String;Ljava/lang/String;)Ljava/lang/String;',0x0401)])
        dump='Java_dev_aerove_lite_core_MeowNativeCore_dumpRuleSet'
        for label,approved,flip,ok in [('exact',self.APPROVED,None,True),('descriptor',dict(self.APPROVED,**{dump:'(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;'}),None,False),('static',self.APPROVED,dump,False)]:
            with self.subTest(label),tempfile.TemporaryDirectory() as classes:
                compile_fixture(classes,approved,flip)
                self.assertEqual(ok,not jni_abi.check(build.ROOT,self.APPROVED,classes_dir=classes),label)

class BuildStreamingTests(unittest.TestCase):
    def test_native_runner_tees_raw_output_and_retains_text_parser_contract(self):
        import io
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);command=[sys.executable,'-c','import sys;sys.stdout.buffer.write(b"native\\r\\n");sys.stderr.write("diagnostic")']
            with mock.patch.object(build,'ROOT',root),mock.patch.object(sys,'stdout',io.StringIO()),mock.patch.object(sys,'stderr',io.StringIO()):
                self.assertEqual('native\n',build.run(command,os.environ,capture=True))
            self.assertEqual(b'native\r\n',(root/'build/native-link.stdout.log').read_bytes())
            self.assertEqual(b'diagnostic',(root/'build/native-link.stderr.log').read_bytes())
            progress=json.loads((root/'build/native-link.stdout.log.progress.json').read_bytes())
            self.assertIsNone(progress['timeoutSeconds']);self.assertEqual(0,progress['exit'])

    def test_native_nonzero_is_not_a_successful_capture(self):
        import io
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);command=[sys.executable,'-c','import sys;print("before-error");sys.exit(9)']
            with mock.patch.object(build,'ROOT',root),mock.patch.object(sys,'stdout',io.StringIO()),mock.patch.object(sys,'stderr',io.StringIO()):
                with self.assertRaises(subprocess.CalledProcessError) as caught:build.run(command,os.environ,capture=True)
            self.assertEqual(9,caught.exception.returncode);self.assertIn('before-error',caught.exception.stdout)
            self.assertIn(b'before-error',(root/'build/native-link.stdout.log').read_bytes())

class BuildPyRequirementTests(unittest.TestCase):
    """Native mirror of core-provider-v3 build-py-requirements.json; test_core_tests.BuildContractTests stays authoritative."""
    def drive(self,mode,compile_error=False):
        import core_tests
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'Cargo.toml').write_text('[package]\nname="aerove-meow-core"\n[lib]\nname="aerove_meow_core"\n[dependencies]\n')
            (root/'Cargo.lock').write_bytes(b'accepted lock\n');(root/'source.json').write_text('{}')
            upstream=root/'source';upstream.mkdir();(upstream/'AEROVE_SOURCE_PROVENANCE.json').write_text('{}')
            full=build_policy.full_native_suite(dict(coreTests=mode))
            policy=dict(resolveLock=False,expectedLockBeforeSha256=core_tests.digest(b'accepted lock\n'),policySha256='fixture policy',coreTests=mode,
                coreTestLockSha256='a'*64,targets=list(build_policy.RESOURCE_HEALTH_TARGETS) if full else ['lib','proxy_runtime'])
            (root/'tests').mkdir()
            for target in policy['targets'][1:]:(root/'tests'/f'{target}.rs').write_text('// compile fixture only\n')
            output=''.join('test fixture::'+name+' ... ok\n' for cases in build.RESOURCE_HEALTH_CASES.values() for name in cases)
            calls=dict(gate=[],suite=[],core=[])
            def run(command,environment,capture=False):
                if command[0]=='rustc':return 'rustc 1.98.1 fixture'
                calls['suite'].append(capture)
                if compile_error and '--keep-going' in command:raise subprocess.CalledProcessError(47,command)
                return output if capture else None
            with mock.patch.object(build,'ROOT',root),mock.patch.object(build,'load_policy',return_value=policy),mock.patch.object(build,'prepare',return_value=upstream),\
                    mock.patch.object(build,'probe_libclang',return_value={'library':{'path':'unit-test-fixture-only'}}),mock.patch.object(build,'run',side_effect=run),\
                    mock.patch.object(build,'execute_core_tests',side_effect=lambda r,p,e,receipt:calls['core'].append(dict(receipt))),\
                    mock.patch.object(build,'verify_resource_jni_source',side_effect=calls['gate'].append),mock.patch('builtins.print'),mock.patch.object(sys,'argv',['build.py']):
                if compile_error:
                    with self.assertRaises(subprocess.CalledProcessError):build.main()
                else:build.main()
            return full,root,calls,json.loads((root/'build/host-receipt.json').read_text())

    def test_full_suite_modes_run_the_strict_jni_gate_and_record_native_passes_before_core_tests(self):
        import core_tests
        self.assertIn(core_tests.MODE_PROVIDER_READER,core_tests.FULL_NATIVE_SUITE_MODES)
        for mode in core_tests.FULL_NATIVE_SUITE_MODES+(core_tests.MODE_PROVIDER_GENERAL,):
            with self.subTest(mode=mode):
                full,root,calls,receipt=self.drive(mode)
                self.assertEqual([root] if full else [],calls['gate'])
                self.assertEqual([False,True] if full else [False],calls['suite'])
                expected=build.RESOURCE_HEALTH_CASES if full else None
                self.assertEqual(expected,receipt.get('addedNativeTests'));self.assertEqual(expected,calls['core'][0].get('addedNativeTests'))

    def test_compile_aggregation_failure_cannot_run_tests_or_accept_a_lock(self):
        full,root,calls,receipt=self.drive('policy-runtime-provider-reader-v1',compile_error=True)
        self.assertTrue(full)
        self.assertEqual([False],calls['suite'])
        self.assertEqual([],calls['core'])
        self.assertEqual(47,receipt['compileAggregationExit'])
        self.assertEqual(47,receipt['commandExit'])
        self.assertIs(receipt['acceptedLock'],False)
        self.assertNotIn('addedNativeTests',receipt)

    def test_build_py_has_one_full_suite_predicate_and_no_literal_mode_tuple(self):
        source=(build.ROOT/'build.py').read_text()
        self.assertEqual(3,source.count('full_native_suite(policy)'))
        for mode in ['policy-runtime-resource-health-v1','policy-runtime-mrs-reader-v1','policy-runtime-provider-reader-v1']:self.assertNotIn(mode,source)

if __name__=='__main__':unittest.main()
