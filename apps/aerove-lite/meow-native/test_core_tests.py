"""Direct tests of fixed Core commands and lock restoration, without pretending to execute Rust."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import core_tests

class CoreTestAdmissionTests(unittest.TestCase):
    def test_fixed_policy_and_exact_feature_owner_reject_unknown_inputs(self):
        for policy in [{'resolveLock':True,'coreTests':'shell'}, {'resolveLock':False,'coreTests':core_tests.MODE}, {'resolveLock':True,'coreTestLockSha256':'a'*64}]:
            with self.assertRaises(ValueError):core_tests.validate_policy(policy)
        self.assertFalse(core_tests.validate_policy({'resolveLock':False}))
        self.assertTrue(core_tests.validate_policy({'resolveLock':True,'coreTests':core_tests.MODE}))
        self.assertEqual(sum(core_tests.COUNTS.get(case,1) for case in core_tests.CASES),24)
        self.assertEqual(len(core_tests.CASES),22)

    def test_feature_projection_rejects_package_outside_prepared_source(self):
        with tempfile.TemporaryDirectory() as directory:
            upstream=Path(directory);packages=[];nodes=[]
            for name in core_tests.PACKAGES:
                packages.append(dict(id=name,name=name,version='0.26.1',manifest_path=str(upstream/'crates'/name/'Cargo.toml')))
                nodes.append(dict(id=name,features=['z','a']))
            metadata=dict(packages=packages,resolve=dict(nodes=nodes))
            self.assertTrue(all(features==['a','z'] for features in core_tests.feature_matrix(metadata,upstream).values()))
            packages[0]['manifest_path']=str(upstream/'outside/Cargo.toml')
            with self.assertRaisesRegex(ValueError,'prepared source'):core_tests.feature_matrix(metadata,upstream)

    def run_fixture(self,mode):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);up=root/'.upstream/meow-rs';up.mkdir(parents=True);(root/'build').mkdir()
            original=b'actual-upstream-lock';bridge=b'accepted-bridge-lock';resolved=b'actual-cargo-core-lock'
            (up/'Cargo.lock').write_bytes(original);(root/'Cargo.lock').write_bytes(bridge)
            policy=dict(resolveLock=True,coreTests=core_tests.MODE)
            if mode in ['locked','bad-seed']:
                (root/'core-tests.lock').write_bytes(resolved if mode=='locked' else b'wrong seed')
                policy.update(resolveLock=False,coreTestLockSha256=core_tests.digest(resolved))
            packages=[];nodes=[]
            for name in core_tests.PACKAGES:
                p=up/'crates'/name/'Cargo.toml';p.parent.mkdir(parents=True);p.write_text('')
                packages.append(dict(id=name,name=name,version='0.26.1',manifest_path=str(p)))
                nodes.append(dict(id=name,features=['aerove-android-runtime'] if name=='meow-common' else [],deps=[]))
            metadata=dict(packages=packages,resolve=dict(nodes=nodes),workspace_members=[p['id'] for p in packages])
            receipt={};commands=[]
            def fake(args,**kwargs):
                commands.append(args)
                if args[1]=='metadata':
                    if '--manifest-path' in args:(up/'Cargo.lock').write_bytes(resolved)
                    return subprocess.CompletedProcess(args,0,json.dumps(metadata).encode(),b'')
                name=args[args.index('--')-1];package=args[args.index('--package')+1]
                if mode=='nonzero':return subprocess.CompletedProcess(args,101,b'',b'actual command failure')
                if mode=='raise':raise KeyboardInterrupt()
                if mode=='mutate':(up/'Cargo.lock').write_bytes(b'mutated')
                count=0 if mode=='empty' else core_tests.COUNTS.get((package,name),1)
                return subprocess.CompletedProcess(args,0,f'test result: ok. {count} passed; 0 failed;\n'.encode(),b'')
            error=None
            with patch.object(core_tests.subprocess,'run',fake):
                try:core_tests.execute(root,policy,{},receipt)
                except BaseException as caught:error=caught
            self.assertEqual((up/'Cargo.lock').read_bytes(),original)
            self.assertEqual((root/'Cargo.lock').read_bytes(),bridge)
            self.assertTrue(receipt['coreTests']['upstreamLockRestored'])
            self.assertEqual(json.loads((root/'build/core-tests/receipt.json').read_bytes()),receipt['coreTests'])
            self.assertEqual(commands[0],['cargo','metadata','--locked','--format-version','1'])
            if mode=='bad-seed':
                self.assertIsInstance(error,ValueError);self.assertFalse(receipt['coreTests']['accepted'])
                self.assertEqual(len(commands),1);return
            self.assertEqual(commands[1],['cargo','metadata','--manifest-path',core_tests.MANIFEST,'--format-version','1']+(['--locked'] if mode=='locked' else []))
            if mode in ['success','locked']:
                self.assertIsNone(error);self.assertTrue(receipt['coreTests']['accepted'])
                self.assertEqual(len(receipt['coreTests']['commands']),22)
                self.assertEqual(sum(row['passed'] for row in receipt['coreTests']['commands']),24)
                self.assertEqual((root/'build/core-tests/core-tests.lock').read_bytes(),resolved)
            else:
                self.assertIsNotNone(error);self.assertFalse(receipt['coreTests']['accepted'])
                self.assertEqual(len(receipt['coreTests']['commands']),1)

    def test_success_records_all_authored_counts_and_restores_original_lock(self):self.run_fixture('success')

    def test_accepted_core_seed_uses_locked_metadata_without_implicit_resolution(self):self.run_fixture('locked')

    def test_wrong_accepted_core_seed_is_rejected_before_core_metadata(self):self.run_fixture('bad-seed')

    def test_failure_zero_matches_mutation_and_interrupt_cannot_accept_or_leak_test_lock(self):
        for mode in ['nonzero','empty','mutate','raise']:
            with self.subTest(mode=mode):self.run_fixture(mode)

if __name__=='__main__':unittest.main()
