"""Supervisor contracts only; never claims actual GLES execution."""
import json
import tempfile
from pathlib import Path
from types import SimpleNamespace
from unittest import TestCase
from unittest.mock import Mock, patch
import gfxstream_host_regression as p

class HostRegressionTests(TestCase):
    def args(self, root):
        return SimpleNamespace(base_library=root/'base.so',output=root/'build',evidence=root/'evidence')
    def test_low_disk_prevents_execution_and_preserves_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            a=self.args(Path(temp))
            with patch.object(p,'free_bytes',return_value=0),patch.object(p.subprocess,'Popen') as launch:
                self.assertEqual(1,p.run(a));launch.assert_not_called()
            d=json.loads((a.evidence/'HOST-REGRESSION.json').read_text())
            self.assertFalse(d['accepted']);self.assertEqual('failed',d['status'])
    def test_no_full_source_environment_core_or_module_export(self):
        with tempfile.TemporaryDirectory() as temp:
            r=Path(temp);b=r/'build';e=r/'evidence';b.mkdir();e.mkdir()
            for name in ('source.cpp','core','env','secrets.json'): (b/name).write_text('not for export')
            (b/'artifact').mkdir();(b/'artifact/libgfxstream_backend.so').write_text('not shipped')
            (b/'build-receipt.json').write_text('{"status":"failed"}')
            (b/'logs').mkdir();(b/'logs/result.out').write_text('bounded log')
            (b/'logs/bad.err').symlink_to(b/'env')
            records=p.export_evidence(b,e)
            self.assertEqual({'build-receipt.json','logs/result.out'}, {x['path'] for x in records})
    def test_low_watermark_stops_real_timeout_descendants_not_unrelated_child(self):
        import subprocess, sys, time
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);a=self.args(root)
            # GNU timeout has its own process group; the shell is another owned child.
            child=subprocess.Popen(['bash','-c','timeout 60s sh -c "sleep 60" & wait'], start_new_session=True)
            other=subprocess.Popen(['sleep','60'], start_new_session=True)
            try:
                deadline=time.monotonic()+3
                while len(p.session_members(child.pid))<3 and time.monotonic()<deadline:time.sleep(.05)
                self.assertGreaterEqual(len(p.session_members(child.pid)),3)
                with patch.object(p,'free_bytes',side_effect=[p.START_FREE+1,0]),patch.object(p.subprocess,'Popen',return_value=child):
                    self.assertEqual(1,p.run(a))
                self.assertEqual([],p.session_members(child.pid))
                self.assertIsNone(other.poll())
                self.assertTrue(json.loads((a.evidence/'HOST-REGRESSION.json').read_text())['ownedRecipeTerminated'])
            finally:
                if p.session_members(child.pid):p.stop_owned_session(child)
                other.terminate();other.wait(timeout=5)
    def test_parent_directory_symlink_is_not_exported(self):
        with tempfile.TemporaryDirectory() as temp:
            r=Path(temp);b=r/'build';e=r/'evidence';outside=r/'outside'
            b.mkdir();e.mkdir();outside.mkdir();(outside/'secret.out').write_text('private')
            (b/'logs').symlink_to(outside, target_is_directory=True)
            self.assertEqual([],p.export_evidence(b,e))
    def test_zero_exit_without_recipe_proof_fails_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            a=self.args(Path(temp));child=Mock(pid=4321,returncode=0);child.poll.return_value=0
            with patch.object(p,'free_bytes',return_value=p.START_FREE+1),patch.object(p.subprocess,'Popen',return_value=child),patch.object(p,'session_members',return_value=[]):
                self.assertEqual(1,p.run(a))
    def test_workflow_is_independent_no_android_or_security_bypass(self):
        r=Path(__file__).resolve().parents[3];s=(r/'.github/workflows/aerove-lite.yaml').read_text()
        block=s.split('  diagnostic_gfxstream_zero_attachments_host:',1)[1].split('  diagnostic_gfxstream37_static:',1)[0]
        for required in ('gfxstream-zero-attachments-host','ubuntu-24.04','timeout-minutes: 215','persist-credentials: false','if: always()','xvfb-run -a xdpyinfo','cbc7c3cfb83220b8a0f9c513fdab557712e74d339ec19ddffa43f23350ae9bdd'):
            self.assertIn(required,block)
        for forbidden in ('adb ', 'sdkmanager', 'core_pattern','ptrace_scope','GH_TOKEN','--allow-unauthenticated','rm -rf','publish_release: true'):
            self.assertNotIn(forbidden,block)
        self.assertGreaterEqual(p.MAX_SECONDS,10800)
        recipe=(r/'apps/aerove-lite/tool/build_gfxstream_derived_host.sh').read_text()
        for required in ('_LIBCPP_HARDENING_MODE_EXTENSIVE','_LIBCPP_ABI_VERSION=1','gfxstream-zero-attachment-ready','InvalidateFramebufferTest.ZeroAttachmentsPreserveColor','InvalidateFramebufferTest.*:CombinedFramebufferBlitTest'):
            self.assertIn(required,recipe)
