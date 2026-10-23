"""Source/recipe tests only. These never invoke Rust, NDK, Gradle or network."""
import io
import json
import re
import textwrap
from pathlib import Path
import tarfile
import tempfile
import subprocess
import sys
import unittest
from unittest import mock
import prepare
import build
import build_policy
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
                    return subprocess.CompletedProcess(args[0],0 if success else 42,b'{}',b'resolver diagnostic')
                def run(command,*args,**kwargs):
                    if command[0]=='rustc':return 'rustc 1.98.1 fixture'
                    self.assertIn('--locked',command);self.assertIn('api_lifecycle',command)
                with mock.patch.object(build,'ROOT',root),mock.patch.object(build,'load_policy',return_value=policy),mock.patch.object(build,'prepare',return_value=upstream),mock.patch.object(build,'run',side_effect=run),mock.patch.object(build.subprocess,'run',side_effect=metadata),mock.patch.object(sys,'argv',['build.py','--resolve-lock']):
                    if success:build.main()
                    else:
                        with self.assertRaises(subprocess.CalledProcessError):build.main()
                receipt=json.loads((root/'build/host-receipt.json').read_text())
                self.assertEqual(b'accepted lock\n',(root/'build/lock-before-resolution.lock').read_bytes());self.assertEqual(previous,receipt['lockSha256Before'])
                self.assertEqual(success,receipt['acceptedLock']);self.assertEqual(0 if success else 42,receipt['metadataExit'])
                self.assertEqual(b'resolver diagnostic',(root/'build/metadata.stderr.log').read_bytes())

class AndroidRecipeTests(unittest.TestCase):
    def test_exact_ndk_target_cmake_and_16k_linker_arguments(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);ndk=root/'ndk';ndk.mkdir();(ndk/'source.properties').write_text('Pkg.Revision = 28.2.13676358\n')
            compiler=ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin';compiler.mkdir(parents=True)
            for target in build.TARGETS:
                for name in (target+'33-clang',target+'33-clang++','llvm-ar','llvm-readelf'):(compiler/name).touch()
                with mock.patch.object(build,'ROOT',root):environment,readelf=build.android_environment(ndk,target,{})
                self.assertEqual('c++_static',environment['BORING_BSSL_RUST_CPPLIB'])
                self.assertIn('max-page-size=16384',environment['CARGO_TARGET_'+target.replace('-','_').upper()+'_RUSTFLAGS'])
                self.assertIn('android-33',Path(environment['CMAKE_TOOLCHAIN_FILE_'+target]).read_text())
                self.assertEqual(compiler/'llvm-readelf',readelf)
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

if __name__=='__main__':unittest.main()