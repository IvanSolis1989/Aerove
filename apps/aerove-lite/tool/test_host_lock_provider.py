import copy
import json
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest import mock
import test_ci_artifacts
import verify_apk as verifier
ci = test_ci_artifacts.ci


class ProviderHostLockResolutionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        source = ci.MEOW_ROOT
        self.policy = json.loads((source / 'build-policy.json').read_text())
        overlay = json.loads((source / self.policy['overlay']).read_text())
        for name in ('Cargo.toml', 'Cargo.lock', 'source.json', 'core-tests.lock', 'build-policy.json', self.policy['overlay'], overlay['patch']):
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source / name, target)
        self.env = dict(REQUEST_EVENT='workflow_dispatch', MEOW_HOST_ONLY='true', DIAGNOSTIC_TARGET='none', NATIVE_TEST_FILTER='')
        for key in ('QA84_ONLY', 'QA79_ONLY', 'CLIENT_PREFLIGHT', 'PUBLISH_RELEASE', 'NATIVE_PREFLIGHT', 'NATIVE_TEST_SERIAL', 'NATIVE_UNDERLAY_TESTS', 'NATIVE_TEST_STACKS'):
            self.env[key] = 'false'

    def write_policy(self):
        (self.root / 'build-policy.json').write_bytes((json.dumps(self.policy, indent=2) + '\n').encode())

    def test_current_real_pins_plan_full_profiles_and_never_run_cargo(self):
        with mock.patch.object(ci.subprocess, 'run') as run:
            plan = ci.host_lock_resolution_plan('host', self.root, self.env)
        run.assert_not_called()
        self.assertEqual((42, 31, 177), (plan['nativeTargetCount'], plan['coreCommandCount'], plan['expectedCoreHits']))
        self.assertEqual(ci.HOST_LOCK_RESOLUTION_PROVIDER_INPUTS['manifestSha256'], plan['manifestSha256'])
        self.assertIs(plan['acceptedLock'], False)
        self.assertIs(plan['android'], False)
        self.assertEqual(['python3', 'apps/aerove-lite/meow-native/build.py', '--resolve-lock'], plan['command'])
        with self.assertRaises(ValueError):
            ci.native_identity(self.root)

    def test_profile_downgrade_every_conflict_and_mixed_pin_tuple_reject(self):
        for key in self.env:
            bad = dict(self.env)
            bad[key] = 'false' if key == 'MEOW_HOST_ONLY' else 'push' if key == 'REQUEST_EVENT' else 'both' if key == 'DIAGNOSTIC_TARGET' else 'filter' if key == 'NATIVE_TEST_FILTER' else 'true'
            with self.subTest(key=key), self.assertRaises(ci.PromotionError):
                ci.host_lock_resolution_plan('host', self.root, bad)
        for mode in ('policy-runtime-resource-health-v1', 'policy-runtime-mrs-reader-v1', 'policy-runtime-v1'):
            previous = self.policy['coreTests']
            self.policy['coreTests'] = mode
            self.write_policy()
            try:
                with self.subTest(mode=mode), self.assertRaises((ValueError, ci.PromotionError)):
                    ci.host_lock_resolution_plan('host', self.root, self.env)
            finally:
                self.policy['coreTests'] = previous
                self.write_policy()
        for name, key in [('Cargo.lock', 'expectedLockBeforeSha256'), ('Cargo.toml', 'manifestSha256'), ('core-tests.lock', 'coreTestLockSha256'), (self.policy['overlay'], 'overlaySha256')]:
            path = self.root / name
            previous = path.read_bytes()
            original = self.policy[key]
            path.write_bytes(previous + b'\n')
            self.policy[key] = ci.digest(path)
            self.write_policy()
            try:
                with self.subTest(input=name), self.assertRaises(ci.PromotionError):
                    ci.host_lock_resolution_plan('host', self.root, self.env)
            finally:
                path.write_bytes(previous)
                self.policy[key] = original
                self.write_policy()
        for name in ('source.json', 'core-tests.lock'):
            path = self.root / name
            previous = path.read_bytes()
            path.write_bytes(previous + b'\n')
            try:
                with self.subTest(input=name), self.assertRaises((ValueError, ci.PromotionError)):
                    ci.host_lock_resolution_plan('host', self.root, self.env)
            finally:
                path.write_bytes(previous)

    def core_fixture(self):
        ci.sys.path.insert(0, str(ci.MEOW_ROOT))
        try:
            from core_tests import entries, packages_for_mode
        finally:
            ci.sys.path.pop(0)
        cases = entries(self.policy['coreTests'])
        packages = packages_for_mode(self.policy['coreTests'])
        features = {package: sorted({feature for entry in cases if entry['package'] == package for feature in entry['requiredFeatures']}) for package in packages}
        commands = []
        for entry in cases:
            package = entry['package']
            command = ['cargo', 'test', '--manifest-path', '.upstream/meow-rs/Cargo.toml', '--locked', '--package', package]
            command += ['--lib'] if entry['target'] == 'lib' else ['--test', entry['target']]
            command += ['--no-default-features']
            if features[package]:
                command += ['--features', ','.join(package + '/' + feature for feature in features[package])]
            if entry['filter'] is not None:
                command += [entry['filter']]
            command += ['--', '--test-threads=1', '--nocapture']
            if entry['exact']:
                command += ['--exact']
            commands.append(dict(entry, command=command, exit=0, passed=entry['expectedPass'], stdoutSha256='1'*64, stderrSha256='2'*64))
        overlay = json.loads((self.root / self.policy['overlay']).read_text())
        state = dict(mode=self.policy['coreTests'], accepted=True, upstreamLockRestored=True,
                     upstreamLockBeforeSha256=overlay['files']['Cargo.lock'], seedSha256=self.policy['coreTestLockSha256'], resolvedLockSha256=self.policy['coreTestLockSha256'],
                     workspaceMembers=sorted(packages), features=features,
                     bridgeMetadataCommand=['cargo', 'metadata', '--locked', '--format-version', '1'], bridgeMetadataExit=0,
                     metadataCommand=['cargo', 'metadata', '--manifest-path', '.upstream/meow-rs/Cargo.toml', '--format-version', '1', '--locked'], metadataExit=0,
                     commands=commands, compileCheck=dict(command=verifier.CORE_OBSERVABILITY_COMPILE_COMMAND, exit=0, stdoutSha256='3'*64, stderrSha256='4'*64))
        for key, command in [('formatCheck', verifier.CORE_MRS_READER_FORMAT_COMMAND), ('providerFormatCheck', verifier.CORE_PROVIDER_READER_FORMAT_COMMAND)]:
            state[key] = dict(command=command, exit=0, stdoutSha256='5'*64, stderrSha256='6'*64,
                              files={name: overlay['files'][name.removeprefix('.upstream/meow-rs/')] for name in command[4:]})
        return state

    def test_profile_readback_requires_all_native_core_format_and_hash_proofs(self):
        plan = ci.host_lock_resolution_plan('host', self.root, self.env)
        workflow = dict(sourceRevision='a'*40, sourceTree='b'*40, workflowRun='pending-unit-fixture', runAttempt=1, runNumber=1)
        plan['workflowIdentity'] = workflow
        build = self.root / 'build'
        build.mkdir()
        plan_path = build / 'host-lock-resolution-plan.json'
        plan_path.write_text(json.dumps(plan))
        original = (self.root / 'Cargo.lock').read_bytes()
        (build / 'lock-before-resolution.lock').write_bytes(original)
        (self.root / 'Cargo.lock').write_bytes(b'unit-test fake producer output, never a delivery Cargo.lock')
        resolved = ci.digest(self.root / 'Cargo.lock')
        command = ['cargo', 'test', '--locked', '--no-fail-fast']
        for target in self.policy['targets']:
            command += ['--lib'] if target == 'lib' else ['--test', target]
        command += ['--', '--test-threads=1', '--nocapture']
        spec = json.loads((self.root / 'source.json').read_text())
        overlay = json.loads((self.root / self.policy['overlay']).read_text())
        receipt = dict(schema=1, acceptedLock=True, resolveLockRequested=True, bootstrapRequested=False, androidTarget=None,
                       commandExit=0, command=command, representativeTargets=self.policy['targets'],
                       workflowSourceSha=workflow['sourceRevision'], workflowRun=workflow['workflowRun'], workflowAttempt='1',
                       buildPolicySha256=ci.digest(self.root / 'build-policy.json'), bridgeManifestSha256=self.policy['manifestSha256'],
                       declaredSourceSpecSha256=ci.HOST_LOCK_RESOLUTION_PROVIDER_INPUTS['sourceSpecSha256'],
                       lockSha256Before=self.policy['expectedLockBeforeSha256'], metadataInputLockSha256=self.policy['expectedLockBeforeSha256'],
                       metadataCommand=['cargo', 'metadata', '--format-version', '1'], metadataExit=0,
                       resolvedLockSha256=resolved, lockSha256After=resolved, lockUnchangedDuringLockedCommand=True,
                       featureBoundaryCommand=['cargo','test','--locked','--no-default-features','--lib','capabilities::tests::','--','--test-threads=1','--nocapture'], featureBoundaryExit=0,
                       toolchain='rustc 1.98.1 unit-test fixture', source=dict(commit=ci.MEOW_SOURCE, tag='v0.26.1', archiveHashBootstrapped=False,
                           archiveSha256=spec['archiveSha256'], overlaySha256=self.policy['overlaySha256'], patchSha256=overlay['patchSha256'], fileManifestSha256=overlay['adaptedFileManifestSha256']),
                       coreTests=self.core_fixture(), addedNativeTests=copy.deepcopy(ci.RESOURCE_HEALTH_CASES))
        path = build / 'host-receipt.json'
        def save(value):
            path.write_text(json.dumps(value))
        save(receipt)
        with mock.patch.object(ci, 'identity', return_value=workflow):
            result = ci.host_lock_resolution_result(plan_path, 'host', self.root, self.env)
            self.assertIs(result['archiveOnly'], True)
            self.assertIs(result['acceptedLock'], False)
            self.assertEqual(31, result['coreCommandCount'])
            variants = []
            for key, value in [('commandExit', 1), ('acceptedLock', False), ('metadataExit', 1), ('featureBoundaryExit', 1), ('toolchain', 'rustc other')]:
                changed = copy.deepcopy(receipt)
                changed[key] = value
                variants.append((key, changed))
            for key, value in [('mode', 'policy-runtime-mrs-reader-v1'), ('commands', receipt['coreTests']['commands'][:-1]), ('formatCheck', None), ('providerFormatCheck', None)]:
                changed = copy.deepcopy(receipt)
                changed['coreTests'][key] = value
                variants.append((key, changed))
            changed = copy.deepcopy(receipt)
            changed['coreTests']['commands'][-1]['expectedPass'] -= 1
            changed['coreTests']['commands'][-1]['passed'] -= 1
            variants.append(('lower-reader-count', changed))
            changed = copy.deepcopy(receipt)
            changed['coreTests']['providerFormatCheck']['files'].pop(next(iter(changed['coreTests']['providerFormatCheck']['files'])))
            variants.append(('missing-profile-file', changed))
            changed = copy.deepcopy(receipt)
            changed['addedNativeTests']['lib'].pop()
            variants.append(('missing-native-32-proof', changed))
            changed = copy.deepcopy(receipt)
            changed['source']['archiveHashBootstrapped'] = True
            variants.append(('source-bootstrap', changed))
            for name, value in variants:
                save(value)
                with self.subTest(mutation=name), self.assertRaises((ci.PromotionError, ValueError)):
                    ci.host_lock_resolution_result(plan_path, 'host', self.root, self.env)
            save(receipt)
            self.assertEqual(31, ci.host_lock_resolution_result(plan_path, 'host', self.root, self.env)['coreCommandCount'])


if __name__ == '__main__':
    unittest.main()
