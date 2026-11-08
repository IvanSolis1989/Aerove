import copy
import hashlib
import json
import os
from pathlib import Path
import unittest
from unittest.mock import patch
import lite343_anr as m
import emulator_current as owner

ENV = dict(AEROVE_LITE343_ANR='1', GITHUB_REPOSITORY='IvanSolis1989/Aerove', GITHUB_EVENT_NAME='workflow_dispatch', GITHUB_REF='refs/heads/dot/cloud-j316-checkpoint-20261108', GITHUB_SHA='1'*40, GITHUB_RUN_ID='987', GITHUB_RUN_ATTEMPT='1', GITHUB_RUN_NUMBER='999')
class Fixed343Tests(unittest.TestCase):
    def api(self):
        return dict(m.ARCHIVE, expired=False, url='https://api.github.com/repos/IvanSolis1989/Aerove/actions/artifacts/13633269139', workflow_run=dict(id=42517946969, repository_id=1429390079, head_repository_id=1429390079, head_branch='dot/cloud-j316-checkpoint-20261108', head_sha=m.IDENTITY['sourceRevision']))
    def test_api_is_exact_and_rejects_other_archive_or_source(self):
        row=self.api();m.validate_api(row)
        for key,value in [('id',1),('size_in_bytes',1),('digest','sha256:'+'0'*64),('expired',True)]:
            with self.subTest(key=key),self.assertRaises(ValueError):m.validate_api(dict(row,**{key:value}))
        row['workflow_run']['head_sha']='0'*40
        with self.assertRaises(ValueError):m.validate_api(row)
    def test_optin_and_owner_phase_keep_harness_separate(self):
        with patch.dict(os.environ,ENV,clear=True),patch.object(m.artifacts,'identity',return_value={'executor':'new'}):
            self.assertTrue(m.enabled())
            runtime=dict(identity=m.IDENTITY,original343Anr=True,harnessIdentity={'executor':'new'},diagnosticOnly=True,accepted=False)
            for phase in ('flutter-ui','flutter-full'):owner.guard_flutter_phase(phase,runtime=runtime)
            for phase in ('flutter-platform','matrix'):
                with self.assertRaises(ValueError):owner.guard_flutter_phase(phase,runtime=runtime)
            for key,value in [('identity',{}),('harnessIdentity',m.IDENTITY),('accepted',True),('diagnosticOnly',False)]:
                with self.assertRaises(ValueError):owner.guard_flutter_phase('flutter-ui',runtime=dict(runtime,**{key:value}))
            for key in ('AEROVE_LITE236_PIXELS','AEROVE_LITE257_SURFACE','AEROVE_LITE328_SURFACE','AEROVE_LITE333_GRAPHICS','AEROVE_GFXSTREAM_SOURCE_RECEIPT','AEROVE_CURRENT_RENDERER_OVERRIDE'):
                with patch.dict(os.environ,{key:'bad'}):
                    with self.assertRaises(ValueError):m.enabled()
    def test_no_optin_preserves_existing_paths(self):
        with patch.dict(os.environ,{},clear=True):self.assertFalse(m.enabled())
    def test_workflow_never_rebuilds_or_promotes_original(self):
        import yaml
        data=yaml.safe_load((Path(__file__).resolve().parents[3]/'.github/workflows/aerove-lite.yaml').read_text())
        job=data['jobs']['original343_anr'];text=json.dumps(job)
        self.assertEqual({v['phase'] for v in job['strategy']['matrix']['include']},{'flutter-ui','flutter-full'})
        self.assertIn('lite343_anr.py fetch',text)
        self.assertNotIn('gradle',text.lower());self.assertNotIn('flutter build',text)
        self.assertNotIn('aggregate.py',text);self.assertNotIn('publish_release',text)
        self.assertEqual(job['env']['AEROVE_LITE343_ANR'],'1')
        for forbidden in ('sudo', 'udevadm', '/etc/udev', 'chmod', 'setfacl'):
            self.assertNotIn(forbidden, text)
        for required in ('test -c /dev/kvm', 'test -r /dev/kvm', 'test -w /dev/kvm', 'kvm-access.txt'):
            self.assertIn(required, text)

    def test_signature_mismatch_is_rejected_for_every_apk(self):
        for name in m.APKS:
            def signature(command, **kwargs):
                return b"wrong" if Path(command[-1]).name == name else b"correct"
            with self.subTest(name=name), patch.object(m.subprocess,'check_output',side_effect=signature), patch('verify_apk.signing_certificate',side_effect=lambda text: '0'*64 if text=='wrong' else '09f9e021ddac36ead015d27e073b673e79fdf4e02605c402a505781963b1a4d8'):
                with self.assertRaises(ValueError):m.verify_signatures(Path('/synthetic'),'apksigner')
