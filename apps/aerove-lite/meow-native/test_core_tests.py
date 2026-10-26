"""Direct tests of fixed Core commands and lock restoration, without pretending to execute Rust."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import build_policy
import core_tests

# The files rustfmt reaches from the two provider-reader format gates, with the overlay's real module declarations.
FORMAT_SOURCES={'crates/meow-rules/src/mrs_parser.rs':'mod tests {\n}\n','crates/meow-rules/src/rule_set.rs':'mod tests {\n}\n',
    'crates/meow-config/src/rule_provider.rs':'#[path = "rule_pages.rs"]\nmod rule_pages;\npub use rule_pages::RulePage;\n#[path = "rule_provider_reader.rs"]\nmod reader;\n',
    'crates/meow-config/src/rule_pages.rs':'pub struct RulePage;\n',
    'crates/meow-config/src/rule_provider_reader.rs':'#[cfg(test)]\n#[path = "rule_provider_reader_tests.rs"]\nmod tests;\n',
    'crates/meow-config/src/rule_provider_reader_tests.rs':'#[test]\nfn fixture() {}\n'}

def write_format_sources(upstream,sources=FORMAT_SOURCES):
    for name,text in sources.items():
        path=Path(upstream)/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,newline='\n')

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
            provider=contract==core_tests.MODE_PROVIDER_READER
            if provider:policy['targets']=list(build_policy.RESOURCE_HEALTH_TARGETS)
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
            if provider:
                write_format_sources(up)
                if mode=='format-undeclared':
                    write_format_sources(up,{'crates/meow-config/src/rule_provider.rs':FORMAT_SOURCES['crates/meow-config/src/rule_provider.rs']+'#[path = "rule_extra.rs"]\nmod extra;\n',
                        'crates/meow-config/src/rule_extra.rs':'fn extra() {}\n'})
            metadata=dict(packages=packages,resolve=dict(nodes=nodes),workspace_members=[p['id'] for p in packages])
            receipt=dict(addedNativeTests={'lib':['fixture_native_proof']}) if provider else {};commands=[]
            def fake(args,**kwargs):
                commands.append(args)
                if args[1]=='metadata':
                    if '--manifest-path' in args:(up/'Cargo.lock').write_bytes(resolved)
                    return subprocess.CompletedProcess(args,0,json.dumps(metadata).encode(),b'')
                if args[0]=='rustfmt':
                    self.assertIn(args,[core_tests.MRS_FORMAT_COMMAND,core_tests.PROVIDER_READER_FORMAT_COMMAND] if provider else [core_tests.MRS_FORMAT_COMMAND])
                    if mode=='format-mutate':(up/'Cargo.lock').write_bytes(b'format changed lock')
                    failed=mode=='format-fail' or (mode=='format-fail-provider' and args==core_tests.PROVIDER_READER_FORMAT_COMMAND)
                    return subprocess.CompletedProcess(args,1 if failed else 0,b'fixture format output',b'')
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
            formats=[args for args in commands if args[0]=='rustfmt']
            if provider:
                # Both gates run before the first Core test command, in Core03-then-provider order.
                self.assertEqual([core_tests.MRS_FORMAT_COMMAND,core_tests.PROVIDER_READER_FORMAT_COMMAND][:len(formats)],formats)
                self.assertEqual(commands[2:2+len(formats)],formats)
            if mode in ['success','locked']:
                self.assertIsNone(error);self.assertTrue(receipt['coreTests']['accepted'])
                self.assertEqual(len(receipt['coreTests']['commands']),len(core_tests.entries(contract)))
                self.assertEqual(sum(row['passed'] for row in receipt['coreTests']['commands']),sum(row['expectedPass'] for row in core_tests.entries(contract)))
                self.assertEqual((root/'build/core-tests/core-tests.lock').read_bytes(),resolved)
                if provider:
                    self.assertEqual(2,len(formats))
                    for key,expected in [('formatCheck',core_tests.MRS_FORMAT_COMMAND),('providerFormatCheck',core_tests.PROVIDER_READER_FORMAT_COMMAND)]:
                        self.assertEqual((expected,0),(receipt['coreTests'][key]['command'],receipt['coreTests'][key]['exit']))
                        self.assertEqual({name:core_tests.digest((root/name).read_bytes()) for name in expected[4:]},receipt['coreTests'][key]['files'])
                        self.assertEqual(expected[4:],list(receipt['coreTests'][key]['files']))
                    for stem in ['mrs-reader-format','provider-reader-format']:
                        self.assertEqual(b'fixture format output',(root/'build/core-tests'/(stem+'.stdout.log')).read_bytes())
            else:
                self.assertIsNotNone(error);self.assertFalse(receipt['coreTests']['accepted'])
                if provider and mode=='format-mutate':
                    # A rustfmt gate that touches the lock is rejected at once, before any Core test command.
                    self.assertIsInstance(error,ValueError);self.assertEqual(0,len(receipt['coreTests']['commands']))
                elif provider and mode=='format-undeclared':
                    # A module rustfmt would check but the gate does not list stops before its rustfmt and every test.
                    self.assertIsInstance(error,ValueError);self.assertIn('does not declare every file rustfmt checks',str(error))
                    self.assertEqual([core_tests.MRS_FORMAT_COMMAND],formats);self.assertEqual(3,len(commands))
                    self.assertEqual(0,len(receipt['coreTests']['commands']));self.assertIsNone(receipt['coreTests']['providerFormatCheck']['exit'])
                elif provider and mode in ['format-fail','format-fail-provider']:
                    # Deferred format failure: every authored command and the compile gate still ran and were recorded.
                    self.assertIsInstance(error,subprocess.CalledProcessError)
                    self.assertEqual(len(core_tests.entries(contract)),len(receipt['coreTests']['commands']))
                    self.assertTrue(all(row['passed']==row['expectedPass'] for row in receipt['coreTests']['commands']))
                    self.assertEqual(0,receipt['coreTests']['compileCheck']['exit']);self.assertEqual(2,len(formats))
                    self.assertEqual((1 if mode=='format-fail' else 0,1),(receipt['coreTests']['formatCheck']['exit'],receipt['coreTests']['providerFormatCheck']['exit']))
                    self.assertEqual(error.cmd,core_tests.MRS_FORMAT_COMMAND if mode=='format-fail' else core_tests.PROVIDER_READER_FORMAT_COMMAND)
                else:
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
        self.assertEqual((29,121),(len(specs),sum(e['expectedPass'] for e in specs)))
        self.assertEqual([('mrs_parser::tests::',30,False),('rule_set::tests::',15,False),('rule_set::plain_domain_validation_regression::invalid_plain_pattern_is_not_an_accepted_rule',1,True)],
            [(e['filter'],e['expectedPass'],e['exact']) for e in specs[26:]])
        self.assertEqual('--exact',core_tests.entry_command(specs[28],[])[-1])
        self.assertEqual({'meow-rules'},set(core_tests.packages_for_mode(core_tests.MODE_MRS_READER))-set(core_tests.packages_for_mode(core_tests.MODE_RESOURCE_HEALTH)))
        for mode in ['locked','empty','compile-fail','compile-mutate','format-fail','format-mutate','raise']:
            with self.subTest(mode=mode):self.run_fixture(mode,core_tests.MODE_MRS_READER)

    def test_provider_reader_keeps_all_mrs_commands_adds56_and_both_format_gates(self):
        specs=core_tests.entries(core_tests.MODE_PROVIDER_READER)
        self.assertEqual(core_tests.CASES_MRS_READER,specs[:29])
        self.assertEqual((31,177),(len(specs),sum(e['expectedPass'] for e in specs)))
        self.assertEqual([('meow-config','rule_provider::reader::tests::reader15_',15,'lib',[],False),('meow-config','rule_provider::tests::',41,'lib',[],False)],
            [(e['package'],e['filter'],e['expectedPass'],e['target'],e['requiredFeatures'],e['exact']) for e in specs[29:]])
        self.assertEqual(core_tests.packages_for_mode(core_tests.MODE_MRS_READER),core_tests.packages_for_mode(core_tests.MODE_PROVIDER_READER))
        self.assertEqual(['rustfmt','--check','--edition','2021','.upstream/meow-rs/crates/meow-rules/src/mrs_parser.rs','.upstream/meow-rs/crates/meow-rules/src/rule_set.rs'],core_tests.MRS_FORMAT_COMMAND)
        self.assertEqual(['rustfmt','--check','--edition','2021']+['.upstream/meow-rs/crates/meow-config/src/'+name for name in ['rule_provider.rs','rule_pages.rs','rule_provider_reader.rs','rule_provider_reader_tests.rs']],core_tests.PROVIDER_READER_FORMAT_COMMAND)
        self.assertEqual((core_tests.MODE_RESOURCE_HEALTH,core_tests.MODE_MRS_READER,core_tests.MODE_PROVIDER_READER),core_tests.FULL_NATIVE_SUITE_MODES)
        for mode in ['success','locked','empty','nonzero','mutate','compile-fail','compile-mutate','format-fail','format-fail-provider','format-mutate','format-undeclared','raise']:
            with self.subTest(mode=mode):self.run_fixture(mode,core_tests.MODE_PROVIDER_READER)

    def test_format_gate_files_are_exactly_what_rustfmt_follows_or_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);up=root/'.upstream/meow-rs';write_format_sources(up)
            for command in [core_tests.MRS_FORMAT_COMMAND,core_tests.PROVIDER_READER_FORMAT_COMMAND]:
                self.assertEqual({name:core_tests.digest((root/name).read_bytes()) for name in command[4:]},core_tests.format_files(root,command))
            pages='.upstream/meow-rs/crates/meow-config/src/rule_pages.rs'
            for bad in [[name for name in core_tests.PROVIDER_READER_FORMAT_COMMAND if name!=pages],core_tests.PROVIDER_READER_FORMAT_COMMAND+[pages],
                    core_tests.PROVIDER_READER_FORMAT_COMMAND[:5]]:
                with self.subTest(declared=bad[4:]),self.assertRaisesRegex(ValueError,'does not declare every file rustfmt checks'):core_tests.format_files(root,bad)
            provider='crates/meow-config/src/rule_provider.rs';reader='crates/meow-config/src/rule_provider_reader.rs'
            for name,extra in [(provider,'mod plain;\n'),(provider,'#[cfg(test)]\nmod hidden;\n'),(provider,'#[cfg(test)] #[path = "rule_pages.rs"] mod inline;\n'),
                    (provider,'#[path = "missing.rs"]\nmod missing;\n'),(provider,'#[path = "../escape.rs"]\nmod escape;\n'),
                    (provider,'#[path = "rule_pages.rs"]\n#[path = "rule_provider_reader.rs"]\nmod twice;\n'),
                    (reader,'mod nested {\n    #[path = "rule_pages.rs"]\n    mod again;\n}\n'),(reader,'#[cfg_attr(test, path = "rule_pages.rs")]\nmod conditional;\n')]:
                with self.subTest(file=name,extra=extra),tempfile.TemporaryDirectory() as other:
                    copy=Path(other);write_format_sources(copy/'.upstream/meow-rs',dict(FORMAT_SOURCES,**{name:FORMAT_SOURCES[name]+extra}))
                    (copy/'.upstream/meow-rs/crates/meow-config/escape.rs').write_text('')
                    with self.assertRaisesRegex(ValueError,'cannot resolve an out-of-line module'):core_tests.format_files(copy,core_tests.PROVIDER_READER_FORMAT_COMMAND)

class BuildContractTests(unittest.TestCase):
    """build.py is owned by the native domain; these pin what this admission needs from it (build-py-requirements.json)."""
    def drive(self,mode):
        import build
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'Cargo.toml').write_text('[package]\nname="aerove-meow-core"\n[lib]\nname="aerove_meow_core"\n[dependencies]\n')
            (root/'Cargo.lock').write_bytes(b'accepted lock\n');(root/'source.json').write_text('{}')
            upstream=root/'source';upstream.mkdir();(upstream/'AEROVE_SOURCE_PROVENANCE.json').write_text('{}')
            full=mode in core_tests.FULL_NATIVE_SUITE_MODES
            targets=list(build_policy.RESOURCE_HEALTH_TARGETS) if full else ['lib','proxy_runtime']
            policy=dict(resolveLock=False,expectedLockBeforeSha256=core_tests.digest(b'accepted lock\n'),targets=targets,
                policySha256='fixture policy',coreTests=mode,coreTestLockSha256='a'*64)
            output=''.join('test fixture::'+name+' ... ok\n' for cases in build.RESOURCE_HEALTH_CASES.values() for name in cases)
            calls=dict(jni=[],suite=[],core=[])
            def run(command,environment,capture=False):
                if command[0]=='rustc':return 'rustc 1.98.1 fixture'
                calls['suite'].append((command,capture))
                return output if capture else None
            def core(core_root,core_policy,environment,receipt):calls['core'].append(json.loads(json.dumps(receipt)))
            with patch.object(build,'ROOT',root),patch.object(build,'load_policy',return_value=policy),patch.object(build,'prepare',return_value=upstream),\
                    patch.object(build,'probe_libclang',return_value={'library':{'path':'unit-test-fixture-only'}}),patch.object(build,'run',side_effect=run),\
                    patch.object(build,'execute_core_tests',side_effect=core),patch.object(build,'verify_resource_jni_source',side_effect=calls['jni'].append),\
                    patch('builtins.print'),patch.object(sys,'argv',['build.py']):
                build.main()
            return build,root,full,calls,json.loads((root/'build/host-receipt.json').read_text())

    def test_each_full_suite_mode_runs_the_jni_source_gate_native_proof_and_all42_targets_before_core_tests(self):
        self.assertEqual((core_tests.MODE_RESOURCE_HEALTH,core_tests.MODE_MRS_READER,core_tests.MODE_PROVIDER_READER),core_tests.FULL_NATIVE_SUITE_MODES)
        for mode in core_tests.FULL_NATIVE_SUITE_MODES+(core_tests.MODE_PROVIDER_GENERAL,):
            with self.subTest(mode=mode):
                build,root,full,calls,receipt=self.drive(mode)
                self.assertEqual(full,build_policy.full_native_suite(dict(coreTests=mode)))
                self.assertEqual([root] if full else [],calls['jni'],'build.py must run verify_resource_jni_source exactly for full-suite modes')
                suite,capture=calls['suite'][0]
                self.assertEqual(full,capture,'build.py must capture the full Native suite output for the added-test proof')
                self.assertEqual(sorted(build_policy.RESOURCE_HEALTH_TARGETS[1:]) if full else ['proxy_runtime'],sorted(suite[i+1] for i,arg in enumerate(suite) if arg=='--test'))
                proof=build.RESOURCE_HEALTH_CASES if full else None
                self.assertEqual(proof,receipt.get('addedNativeTests'),'build.py must record addedNativeTests for full-suite modes')
                self.assertEqual(1,len(calls['core']));self.assertEqual(proof,calls['core'][0].get('addedNativeTests'))
                self.assertEqual((0,True),(receipt['commandExit'],receipt['acceptedLock']))

    def test_full_suite_predicate_and_policy_loader_require_every_native_target_per_mode(self):
        self.assertFalse(build_policy.full_native_suite(None))
        for mode in [core_tests.MODE,core_tests.MODE_V2,core_tests.MODE_TRAFFIC,core_tests.MODE_OBSERVABILITY,core_tests.MODE_PROVIDER_GENERAL,'unknown']:
            self.assertFalse(build_policy.full_native_suite(dict(coreTests=mode)),mode)
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'Cargo.toml').write_bytes(b'manifest');(root/'Cargo.lock').write_bytes(b'lock')
            (root/'patches').mkdir();(root/'patches/p.patch').write_bytes(b'patch');(root/'source-metadata').mkdir()
            overlay=dict(schema=1,patch='patches/p.patch',patchSha256=core_tests.digest(b'patch'),rawFileManifestSha256='c'*64,
                adaptedFileManifestSha256=core_tests.digest(b'{}'),changes={},files={})
            metadata=(json.dumps(overlay)+'\n').encode();(root/'source-metadata/m.json').write_bytes(metadata)
            base=dict(schema=1,manifestSha256=core_tests.digest(b'manifest'),resolveLock=False,expectedLockBeforeSha256=core_tests.digest(b'lock'),
                targets=list(build_policy.RESOURCE_HEALTH_TARGETS),overlay='source-metadata/m.json',overlaySha256=core_tests.digest(metadata),coreTestLockSha256='a'*64)
            for mode in core_tests.FULL_NATIVE_SUITE_MODES:
                (root/'build-policy.json').write_text(json.dumps(dict(base,coreTests=mode)))
                self.assertTrue(build_policy.full_native_suite(build_policy.load_policy(root)))
                for omitted in build_policy.RESOURCE_HEALTH_TARGETS[1:]:
                    (root/'build-policy.json').write_text(json.dumps(dict(base,coreTests=mode,targets=[t for t in base['targets'] if t!=omitted])))
                    with self.subTest(mode=mode,omitted=omitted),self.assertRaisesRegex(ValueError,'every approved Native target'):build_policy.load_policy(root)

    def test_provider_reader_policy_requires_all42_native_targets(self):
        targets=list(build_policy.RESOURCE_HEALTH_TARGETS);self.assertEqual(42,len(targets))
        policy=dict(resolveLock=False,coreTests=core_tests.MODE_PROVIDER_READER,coreTestLockSha256='a'*64)
        self.assertTrue(core_tests.validate_policy(dict(policy,targets=targets)))
        for bad in [None,targets[:-1],targets+['arbitrary'],list(reversed(targets)),tuple(targets)]+[[t for t in targets if t!=omitted] for omitted in targets[1:]]:
            with self.subTest(targets=bad),self.assertRaisesRegex(ValueError,'every approved Native target'):
                core_tests.validate_policy(dict(policy) if bad is None else dict(policy,targets=bad))
        with self.assertRaisesRegex(ValueError,'accepted Core test lock'):core_tests.validate_policy(dict(policy,coreTestLockSha256=None,targets=targets))

    def test_provider_reader_rejects_missing_native_suite_proof_before_any_command(self):
        for proof in [None,{},[],'proof']:
            with self.subTest(proof=proof),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);up=root/'.upstream/meow-rs';up.mkdir(parents=True);(up/'Cargo.lock').write_bytes(b'actual-upstream-lock')
                receipt={} if proof is None else dict(addedNativeTests=proof);commands=[]
                policy=dict(resolveLock=False,coreTests=core_tests.MODE_PROVIDER_READER,coreTestLockSha256='a'*64,targets=list(build_policy.RESOURCE_HEALTH_TARGETS))
                with patch.object(core_tests.subprocess,'run',lambda args,**kwargs:commands.append(args)):
                    with self.assertRaisesRegex(ValueError,'full Native suite proof'):core_tests.execute(root,policy,{},receipt)
                self.assertEqual([],commands);self.assertFalse(receipt['coreTests']['accepted'])
                self.assertTrue(receipt['coreTests']['upstreamLockRestored']);self.assertEqual(b'actual-upstream-lock',(up/'Cargo.lock').read_bytes())
                self.assertEqual(json.loads((root/'build/core-tests/receipt.json').read_bytes()),receipt['coreTests'])

if __name__=='__main__':unittest.main()
