"""Source/recipe tests only. These never invoke Rust, NDK, Gradle or network."""
import io
import json
import re
import textwrap
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest import mock
import prepare
import build
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
        workflow=Path(__file__).resolve().parents[3]/'.github/workflows/aerove-meow-preflight.yaml'
        text=workflow.read_text()
        code=textwrap.dedent(text.split('# MEOW_PLAN_BEGIN\n',1)[1].split('# MEOW_PLAN_END',1)[0])
        scope={'re':re};exec(code,scope);plan=scope['decide_plan'];pin='a'*64
        self.assertEqual(dict(bootstrap=False,android=True,proof='host-android'),plan(True,pin,'push','',False))
        self.assertEqual('host-only',plan(True,pin,'workflow_dispatch','host',False)['proof'])
        self.assertEqual('host-android',plan(True,pin,'workflow_dispatch','android',False)['proof'])
        self.assertEqual('bootstrap-host-only',plan(False,None,'push','',False)['proof'])
        self.assertEqual('bootstrap-host-only',plan(False,None,'workflow_dispatch','host',True)['proof'])
        for args in [(False,None,'workflow_dispatch','android',True),(False,None,'workflow_dispatch','host',False),
                     (True,pin,'workflow_dispatch','host',True),(True,None,'push','',False),
                     (False,pin,'push','',False),(True,'not-a-hash','push','',False),
                     (True,pin,'pull_request','',False),(True,pin,'workflow_dispatch','injected',False)]:
            with self.subTest(args=args),self.assertRaises(ValueError):plan(*args)

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