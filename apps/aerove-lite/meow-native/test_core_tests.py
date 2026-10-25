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

    def test_v2_tracks_migrated_helpers_and_real_integration_target(self):
        specs=core_tests.entries(core_tests.MODE_V2)
        self.assertEqual(len(specs),14)
        self.assertEqual(sum(row['expectedPass'] for row in specs),29)
        self.assertEqual(specs[0]['package'],'meow-transport')
        self.assertEqual((specs[0]['filter'],specs[0]['expectedPass']),('scoped_join::',11))
        compat=next(row for row in specs if row['target']=='transport_scope_compat')
        args=core_tests.entry_command(compat,['aerove-android-runtime'])
        self.assertEqual(args,['cargo','test','--manifest-path',core_tests.MANIFEST,'--locked','--package','meow-common','--test','transport_scope_compat','--no-default-features','--features','meow-common/aerove-android-runtime','--','--test-threads=1','--nocapture'])
        self.assertIsNone(compat['filter'])
        for spec in specs:
            if spec['requiredFeatures']:
                with self.subTest(package=spec['package'],filter=spec['filter']):
                    with self.assertRaisesRegex(ValueError,'authored Core test feature'):core_tests.entry_command(spec,[])

    def run_fixture(self,mode,contract=core_tests.MODE):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);up=root/'.upstream/meow-rs';up.mkdir(parents=True);(root/'build').mkdir()
            original=b'actual-upstream-lock';bridge=b'accepted-bridge-lock';resolved=b'actual-cargo-core-lock'
            (up/'Cargo.lock').write_bytes(original);(root/'Cargo.lock').write_bytes(bridge)
            policy=dict(resolveLock=True,coreTests=contract)
            if mode in ['locked','bad-seed']:
                (root/'core-tests.lock').write_bytes(resolved if mode=='locked' else b'wrong seed')
                policy.update(resolveLock=False,coreTestLockSha256=core_tests.digest(resolved))
            packages=[];nodes=[]
            for name in core_tests.packages_for_mode(contract):
                p=up/'crates'/name/'Cargo.toml';p.parent.mkdir(parents=True);p.write_text('')
                packages.append(dict(id=name,name=name,version='0.26.1',manifest_path=str(p)))
                features=sorted({feature for spec in core_tests.entries(contract) if spec['package']==name for feature in spec['requiredFeatures']})
                if name=='meow-common':features=sorted(set(features+['aerove-android-runtime']))
                nodes.append(dict(id=name,features=features,deps=[]))
            metadata=dict(packages=packages,resolve=dict(nodes=nodes),workspace_members=[p['id'] for p in packages])
            receipt={};commands=[]
            def fake(args,**kwargs):
                commands.append(args)
                if args[1]=='metadata':
                    if '--manifest-path' in args:(up/'Cargo.lock').write_bytes(resolved)
                    return subprocess.CompletedProcess(args,0,json.dumps(metadata).encode(),b'')
                if args[0]=='rustfmt':
                    self.assertEqual(args,core_tests.MRS_FORMAT_COMMAND)
                    if mode=='format-mutate':(up/'Cargo.lock').write_bytes(b'format changed lock')
                    return subprocess.CompletedProcess(args,1 if mode=='format-fail' else 0,b'fixture format output',b'')
                if args[1]=='check':
                    self.assertEqual(args,core_tests.COMPILE_COMMAND)
                    if mode=='compile-mutate':(up/'Cargo.lock').write_bytes(b'compile changed lock')
                    return subprocess.CompletedProcess(args,101 if mode=='compile-fail' else 0,b'fixture check output',b'')
                package=args[args.index('--package')+1]
                spec=next(row for row in core_tests.entries(contract) if row['package']==package and core_tests.entry_command(row,next(node['features'] for node in nodes if node['id']==package))==args)
                if mode=='nonzero':return subprocess.CompletedProcess(args,101,b'',b'actual command failure')
                if mode=='raise':raise KeyboardInterrupt()
                if mode=='mutate':(up/'Cargo.lock').write_bytes(b'mutated')
                count=0 if mode=='empty' else spec['expectedPass']
                return subprocess.CompletedProcess(args,0,f'test result: ok. {count} passed; 0 failed;\n'.encode(),b'')
            error=None
            with patch.object(core_tests.subprocess,'run',fake):
                try:core_tests.execute(root,policy,{},receipt)
                except BaseException as caught:error=caught
            self.assertEqual((up/'Cargo.lock').read_bytes(),original)
            self.assertEqual((root/'Cargo.lock').read_bytes(),bridge)
            self.assertTrue(receipt['coreTests']['upstreamLockRestored'])
            self.assertEqual(receipt['coreTests']['mode'],contract)
            self.assertEqual(json.loads((root/'build/core-tests/receipt.json').read_bytes()),receipt['coreTests'])
            self.assertEqual(commands[0],['cargo','metadata','--locked','--format-version','1'])
            if mode=='bad-seed':
                self.assertIsInstance(error,ValueError);self.assertFalse(receipt['coreTests']['accepted'])
                self.assertEqual(len(commands),1);return
            self.assertEqual(commands[1],['cargo','metadata','--manifest-path',core_tests.MANIFEST,'--format-version','1']+(['--locked'] if mode=='locked' else []))
            if mode in ['success','locked']:
                self.assertIsNone(error);self.assertTrue(receipt['coreTests']['accepted'])
                self.assertEqual(len(receipt['coreTests']['commands']),len(core_tests.entries(contract)))
                self.assertEqual(sum(row['passed'] for row in receipt['coreTests']['commands']),sum(row['expectedPass'] for row in core_tests.entries(contract)))
                self.assertEqual((root/'build/core-tests/core-tests.lock').read_bytes(),resolved)
            else:
                self.assertIsNotNone(error);self.assertFalse(receipt['coreTests']['accepted'])
                self.assertEqual(len(receipt['coreTests']['commands']),len(core_tests.entries(contract)) if mode in ['compile-fail','compile-mutate','format-fail','format-mutate'] else 1)

    def test_success_records_all_authored_counts_and_restores_original_lock(self):self.run_fixture('success')

    def test_accepted_core_seed_uses_locked_metadata_without_implicit_resolution(self):self.run_fixture('locked')

    def test_wrong_accepted_core_seed_is_rejected_before_core_metadata(self):self.run_fixture('bad-seed')

    def test_failure_zero_matches_mutation_and_interrupt_cannot_accept_or_leak_test_lock(self):
        for mode in ['nonzero','empty','mutate','raise']:
            with self.subTest(mode=mode):self.run_fixture(mode)

    def test_v2_success_and_zero_match_preserve_separate_lock_contract(self):
        for mode in ['success','locked','empty','raise']:
            with self.subTest(mode=mode):self.run_fixture(mode,core_tests.MODE_V2)

    def test_traffic_mode_reuses_exact_v2_commands_with_its_own_receipt_identity(self):
        self.assertNotEqual(core_tests.MODE_V2,core_tests.MODE_TRAFFIC)
        self.assertEqual(core_tests.entries(core_tests.MODE_V2),core_tests.entries(core_tests.MODE_TRAFFIC))
        self.assertEqual(14,len(core_tests.entries(core_tests.MODE_TRAFFIC)))
        self.assertEqual(29,sum(e['expectedPass'] for e in core_tests.entries(core_tests.MODE_TRAFFIC)))
        self.assertTrue(core_tests.validate_policy(dict(resolveLock=False,coreTests=core_tests.MODE_TRAFFIC,coreTestLockSha256='a'*64)))
        for mode in ['locked','empty','raise']:
            with self.subTest(mode=mode):self.run_fixture(mode,core_tests.MODE_TRAFFIC)

    def test_observability_runs_all18_exact47_cases_and_locked_compile_or_rejects(self):
        specs=core_tests.entries(core_tests.MODE_OBSERVABILITY)
        self.assertEqual(core_tests.CASES_V2,specs[:14])
        self.assertEqual(18,len(specs));self.assertEqual(47,sum(e['expectedPass'] for e in specs))
        for mode in ['locked','empty','compile-fail','compile-mutate','raise']:
            with self.subTest(mode=mode):self.run_fixture(mode,core_tests.MODE_OBSERVABILITY)

    def test_observability_package_projection_rejects_wrong_new_package_source_and_version(self):
        with tempfile.TemporaryDirectory() as directory:
            up=Path(directory);packages=[];nodes=[]
            for name in core_tests.packages_for_mode(core_tests.MODE_OBSERVABILITY):
                packages.append(dict(id=name,name=name,version='0.26.1',manifest_path=str(up/'crates'/name/'Cargo.toml')))
                nodes.append(dict(id=name,features=[]))
            metadata=dict(packages=packages,resolve=dict(nodes=nodes))
            self.assertEqual(8,len(core_tests.feature_matrix(metadata,up,core_tests.MODE_OBSERVABILITY)))
            for name in ['meow-api','meow-tunnel','meow-listener']:
                package=next(p for p in packages if p['name']==name);original=dict(package)
                for key,value in [('manifest_path',str(up/'outside/Cargo.toml')),('version','0.26.2')]:
                    package[key]=value
                    with self.subTest(package=name,key=key),self.assertRaises(ValueError):core_tests.feature_matrix(metadata,up,core_tests.MODE_OBSERVABILITY)
                    package.update(original)

    def test_provider_general_requires_source26_exact75_and_locked_compile_restore(self):
        specs=core_tests.entries(core_tests.MODE_PROVIDER_GENERAL)
        self.assertEqual(26,len(specs));self.assertEqual(75,sum(e['expectedPass'] for e in specs))
        self.assertEqual(12,specs[0]['expectedPass'])
        self.assertEqual(11,core_tests.entries(core_tests.MODE_OBSERVABILITY)[0]['expectedPass'])
        exact=next(e for e in specs if e.get('exact'))
        self.assertEqual('proxy_provider::tests::provider_strict_refresh_keeps_last_good_on_bad_dialer',exact['filter'])
        self.assertEqual('--exact',core_tests.entry_command(exact,[])[-1])
        for mode in ['locked','empty','compile-fail','compile-mutate','raise']:
            with self.subTest(mode=mode):self.run_fixture(mode,core_tests.MODE_PROVIDER_GENERAL)
        with tempfile.TemporaryDirectory() as directory:
            up=Path(directory);packages=[];nodes=[]
            for name in core_tests.packages_for_mode(core_tests.MODE_PROVIDER_GENERAL):
                packages.append(dict(id=name,name=name,version='0.26.1',manifest_path=str(up/'crates'/name/'Cargo.toml')))
                nodes.append(dict(id=name,features=[]))
            metadata=dict(packages=packages,resolve=dict(nodes=nodes))
            self.assertEqual(9,len(core_tests.feature_matrix(metadata,up,core_tests.MODE_PROVIDER_GENERAL)))
            config=next(p for p in packages if p['name']=='meow-config')
            config['manifest_path']=str(up/'wrong/Cargo.toml')
            with self.assertRaises(ValueError):core_tests.feature_matrix(metadata,up,core_tests.MODE_PROVIDER_GENERAL)

    def test_resource_health_preserves26_commands75_hits_and_compile_lock_gates(self):
        self.assertEqual(core_tests.entries(core_tests.MODE_PROVIDER_GENERAL),core_tests.entries(core_tests.MODE_RESOURCE_HEALTH))
        for mode in ['locked','empty','compile-fail','compile-mutate','raise']:
            with self.subTest(mode=mode):self.run_fixture(mode,core_tests.MODE_RESOURCE_HEALTH)

    def test_mrs_reader_keeps_all_previous_commands_and_adds46_with_format_lock_gates(self):
        specs=core_tests.entries(core_tests.MODE_MRS_READER)
        self.assertEqual(core_tests.CASES_PROVIDER_GENERAL,specs[:26])
        self.assertEqual((28,121),(len(specs),sum(e['expectedPass'] for e in specs)))
        self.assertEqual(['mrs_parser::tests::','rule_set::tests::'],[e['filter'] for e in specs[26:]])
        self.assertEqual({'meow-rules'},set(core_tests.packages_for_mode(core_tests.MODE_MRS_READER))-set(core_tests.packages_for_mode(core_tests.MODE_RESOURCE_HEALTH)))
        for mode in ['locked','empty','compile-fail','compile-mutate','format-fail','format-mutate','raise']:
            with self.subTest(mode=mode):self.run_fixture(mode,core_tests.MODE_MRS_READER)

if __name__=='__main__':unittest.main()
