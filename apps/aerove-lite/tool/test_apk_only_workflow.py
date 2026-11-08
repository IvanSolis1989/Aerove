"""APK-only admission and job graph safety; no producer or Android process is launched."""
import json
import os
from pathlib import Path
import unittest
import tempfile
from unittest.mock import patch
import yaml

ROOT=Path(__file__).resolve().parents[3]
WORKFLOW=ROOT/'.github/workflows/aerove-lite.yaml'
ALLOWED={'promotion_request','resolve','verify','flutter_host_preflight','build_arm','build_x86','apk_only_candidate'}
BASE=dict(APK_ONLY='true',GITHUB_EVENT_NAME='workflow_dispatch',GITHUB_REPOSITORY='IvanSolis1989/Aerove',GITHUB_REF='refs/heads/dot/cloud-j316-checkpoint-20261108',DIAGNOSTIC_TARGET='none')
class ApkOnlyWorkflowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow=yaml.safe_load(WORKFLOW.read_text())
        cls.jobs=cls.workflow['jobs']
        script=cls.jobs['promotion_request']['steps'][0]['run']
        cls.guard=compile(script.split("python3 - <<'PY'\n",1)[1].rsplit('\nPY',1)[0],str(WORKFLOW),'exec')
    def admit(self, environment):
        with patch.dict(os.environ,environment,clear=True):exec(self.guard,{})
    def test_exact_isolated_dispatch_and_legacy_default(self):
        self.admit(BASE)
        self.admit(dict(BASE,APK_ONLY='false',GITHUB_REF='refs/heads/main'))
        for key,value in [('GITHUB_EVENT_NAME','push'),('GITHUB_REPOSITORY','other/repository'),('GITHUB_REF','refs/heads/main'),('GITHUB_REF','refs/heads/other')]:
            with self.subTest(key=key,value=value),self.assertRaises(SystemExit):self.admit(dict(BASE,**{key:value}))
    def test_every_conflicting_mode_is_rejected(self):
        flags=('PUBLISH_RELEASE','BUILD_APK','PREPARE_SOURCES','VERIFICATION_RECHECK','MEOW_HOST_ONLY','QA84_ONLY','QA79_ONLY','CLIENT_PREFLIGHT','NATIVE_PREFLIGHT','NATIVE_TEST_SERIAL','NATIVE_UNDERLAY_TESTS','NATIVE_TEST_STACKS')
        for flag in flags:
            with self.subTest(flag=flag),self.assertRaises(SystemExit):self.admit(dict(BASE,**{flag:'true'}))
        for key,value in [('ACCEPTED_BUILD_RUN','123'),('ORIGINAL_BUILD_RUN','123'),('NATIVE_TEST_FILTER','one_test')]:
            with self.subTest(key=key),self.assertRaises(SystemExit):self.admit(dict(BASE,**{key:value}))
        trigger=self.workflow.get('on',self.workflow.get(True))
        for diagnostic in trigger['workflow_dispatch']['inputs']['diagnostic_target']['options']:
            if diagnostic=='none':continue
            with self.subTest(diagnostic=diagnostic),self.assertRaises(SystemExit):self.admit(dict(BASE,DIAGNOSTIC_TARGET=diagnostic))
    def test_all_non_producer_jobs_are_explicitly_disabled(self):
        for name,job in self.jobs.items():
            if name not in ALLOWED:
                with self.subTest(job=name):self.assertTrue(job['if'].startswith('${{ !inputs.apk_only && ('),job['if'])
        for name in ('resolve','verify'):
            self.assertEqual(self.jobs[name]['needs'],'promotion_request')
            self.assertIn("needs.promotion_request.result == 'success'",self.jobs[name]['if'])
        self.assertEqual(self.jobs['flutter_host_preflight']['needs'],'promotion_request')
        self.assertIn("needs.promotion_request.result == 'success'",self.jobs['flutter_host_preflight']['if'])
        for name,abi in [('build_arm','arm64-v8a'),('build_x86','x86_64')]:
            job=self.jobs[name];self.assertEqual(job['uses'],'./.github/workflows/aerove-lite-build.yaml')
            self.assertEqual(job['needs'],['flutter_host_preflight','verify']);self.assertEqual(job['with']['abi'],abi)
            self.assertEqual(job['secrets'],'inherit')
    def test_candidate_requires_both_abis_and_all_front_gates_without_acceptance(self):
        job=self.jobs['apk_only_candidate']
        self.assertEqual(set(job['needs']),ALLOWED-{'apk_only_candidate'})
        self.assertNotIn('always()',job['if'])
        body=job['steps'][0]['run'];self.assertIn("all(row['result'] == 'success'",body)
        for marker in ['androidExecuted=False','accepted=False','fullAcceptance=False','releaseAccepted=False','promotionAccepted=False']:
            self.assertIn(marker,body)
        self.assertIn("abis=['arm64-v8a', 'x86_64']",body)
        self.assertNotIn('contents: write',yaml.safe_dump(job))
    def test_allowed_jobs_do_not_launch_device_or_publish(self):
        for name in ALLOWED:
            text=json.dumps(self.jobs[name])
            for forbidden in ('emulator_current.py','sudo udevadm','/dev/kvm','avdmanager','lite_release.py publish'):
                with self.subTest(job=name,forbidden=forbidden):self.assertNotIn(forbidden,text)
        inputs=self.workflow.get('on',self.workflow.get(True))['workflow_dispatch']['inputs']
        self.assertEqual(inputs['apk_only']['type'],'boolean');self.assertIs(inputs['apk_only']['default'],False)

    def test_candidate_receipt_is_not_written_after_any_failed_or_missing_gate(self):
        body=self.jobs['apk_only_candidate']['steps'][0]['run']
        code=compile(body.split("python3 - <<'PYTHON'\n",1)[1].rsplit('\nPYTHON',1)[0],str(WORKFLOW),'exec')
        complete={name:{'result':'success'} for name in ALLOWED-{'apk_only_candidate'}}
        with tempfile.TemporaryDirectory() as directory:
            environment=dict(RUNNER_TEMP=directory,GITHUB_SHA='a'*40,GITHUB_RUN_ID='123',GITHUB_RUN_ATTEMPT='1')
            for name in complete:
                for state in ('failure','skipped','cancelled','missing'):
                    rows={key:dict(value) for key,value in complete.items()}
                    if state=='missing':del rows[name]
                    else:rows[name]['result']=state
                    with self.subTest(job=name,state=state),patch.dict(os.environ,dict(environment,RESULTS=json.dumps(rows)),clear=True):
                        with self.assertRaises(AssertionError):exec(code,{})
                    self.assertFalse((Path(directory)/'APK_ONLY_CANDIDATE.json').exists())
            with patch.dict(os.environ,dict(environment,RESULTS=json.dumps(complete)),clear=True):exec(code,{})
            receipt=json.loads((Path(directory)/'APK_ONLY_CANDIDATE.json').read_text())
            self.assertEqual(receipt['abis'],['arm64-v8a','x86_64'])
            self.assertFalse(receipt['accepted']);self.assertFalse(receipt['androidExecuted'])

    def test_reused_abi_pipeline_keeps_signing_and_has_no_device_or_publication_steps(self):
        reused=yaml.safe_load((ROOT/'.github/workflows/aerove-lite-build.yaml').read_text())
        self.assertEqual(set(reused['jobs']),{'rust','helper','package'})
        text=json.dumps(reused)
        for required in ('verify_apk.py','apksigner','source-manifest','secrets.AEROVE_LITE_KEYSTORE'):
            self.assertIn(required,text)
        for job in reused['jobs'].values():
            self.assertNotIn('uses',job)
            for step in job.get('steps',[]):
                commands=step.get('run','')
                for forbidden in ('emulator_current.py','emulator_qa79.py','avdmanager','/dev/kvm','udevadm','lite_release.py publish','gh release','adb shell'):
                    self.assertNotIn(forbidden,commands)
