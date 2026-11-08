"""Contract tests, not device acceptance."""
import json
import os
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch
import lite333_graphics_probe as p

class Graphics333Tests(unittest.TestCase):
    def args(self, root):
        return SimpleNamespace(sdk=root,output=root,source='a'*40,run='99',attempt='1',original_run_number=333,
            app_renderer='default',disable_queue_submit_with_commands=False,graphics_mode='baseline')
    def test_fixed_artifact(self):
        api=dict(p.ARCHIVE,expired=False,url='https://api.github.com/repos/IvanSolis1989/Aerove/actions/artifacts/13624668964',
            workflow_run=dict(id=42497494765,head_sha=p.APP['sourceRevision'],repository_id=1429390079,head_repository_id=1429390079))
        p.validate_api(api)
        for key in ('id','digest','name','size_in_bytes','url','expired'):
            with self.subTest(key=key),self.assertRaises(ValueError):p.validate_api(dict(api,**{key:'wrong'}))
    def test_single_variable(self):
        self.assertEqual(p.graphics_command(Path('/sdk'),'test','baseline'),p.graphics_command(Path('/sdk'),'test','host-default')+['-feature','GuestAngle,Vulkan'])
        with self.assertRaises(ValueError):p.graphics_command(Path('/sdk'),'test','invalid')
    def test_explicit_scope(self):
        env=dict(GITHUB_REPOSITORY='IvanSolis1989/Aerove',GITHUB_EVENT_NAME='workflow_dispatch',GITHUB_REF='refs/heads/dot/cloud-j316-checkpoint-20261108')
        with patch.dict(os.environ,env,clear=True):p.guard_execution()
        for key,value in [('GITHUB_REF','refs/heads/main'),('GITHUB_EVENT_NAME','push'),('AEROVE_LITE290_LAVAPIPE','1')]:
            with patch.dict(os.environ,dict(env,**{key:value}),clear=True),self.assertRaises(ValueError):p.guard_execution()
    def test_independent_failures(self):
        with tempfile.TemporaryDirectory() as temp:
            d=p.Diagnostic(self.args(Path(temp)));calls=[]
            def command(label,*args,**kwargs):
                calls.append(label)
                if label in ('home-raw','home-instrument'):raise ValueError('failed')
                return b'123\n',{'exit':0}
            d.adb_command=command
            self.assertFalse(d.capture('home'))
            for label in ('home-instrument','home-automation-pull','home-sf-after'):self.assertIn(label,calls)
            for key in ('accepted','visualApproved','performanceAccepted','fullAcceptance','releaseAccepted','promotionAccepted','appCompiled','acceptanceProofProduced'):self.assertIs(d.receipt[key],False)
    def test_black_frame_fails(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);d=p.Diagnostic(self.args(root));dest=root/'home-automation';dest.mkdir();(dest/'uiautomation.png').write_bytes(b'pixels')
            (dest/'receipt.json').write_text(json.dumps(dict(scene='home',session=d.session,captureCompleted=True,accepted=False,sha256=p.sha(dest/'uiautomation.png'),pixels=100,width=10,height=10,rgbBlack=100)))
            png=p.base.PNG+b'IHDR'+b'\x00\x00\x00\x00IEND\xaeB`\x82'
            def command(label,*args,**kwargs):return (png if label.endswith('-raw') else b'OK (1 test)' if label.endswith('-instrument') else b'123\n'),{'exit':0}
            d.adb_command=command
            self.assertFalse(d.capture('home'));self.assertTrue(d.receipt['layers'][0]['uiAutomation']['collected'])
    def test_workflow_binds_original_and_self_target_driver(self):
        source=(Path(__file__).resolve().parents[3]/'.github/workflows/aerove-lite.yaml').read_text()
        self.assertIn('lite333-graphics', source)
        self.assertIn('333: 13624668964', source)
        self.assertIn('from lite333_graphics_probe import original', source)
        self.assertIn('driver=graphics-baseline-diagnostic', source)
        self.assertIn('GraphicsBaselineDiagnosticTest', source)
        self.assertIn('extra+=(--graphics-mode "$GRAPHICS_MODE")', source)
        self.assertIn("inputs.diagnostic_target == 'lite333-graphics-host-default' && 'host-default' || 'baseline'", source)
        self.assertIn('dev.aerove.lite.test' ,source)
    def test_resolve_arguments_keep_action_flag(self):
        source=Path(p.__file__).read_text()
        self.assertIn('*action[4:]',source)
        self.assertIn("require(usable, 'System layer failed; original App deliberately not installed')",source)
        self.assertLess(source.index("require(usable, 'System layer failed"),source.index("self.adb_command('install-original'"))
    def test_android13_display_focus_not_window_presence(self):
        # Synthetic format contracts, not new device evidence.
        windows = "WINDOW MANAGER WINDOWS (dumpsys window windows)\n  Window #9 Window{123 u0 com.android.settings/.Settings}:\n  mTopFocusedDisplayId=0\n"
        self.assertEqual([],p.current_focus_lines(windows))
        display = "WINDOW MANAGER DISPLAY CONTENTS\n  Display: mDisplayId=0 rootTasks=2\n  mCurrentFocus=Window{123 u0 com.android.settings/.Settings}\n  mFocusedApp=ActivityRecord{other}\n"
        self.assertEqual(['  mCurrentFocus=Window{123 u0 com.android.settings/.Settings}'],p.current_focus_lines(display))
        self.assertEqual([],p.current_focus_lines('other=mCurrentFocus=Window{123}'))
    def test_focus_command_full_dump_and_init_single_shell(self):
        with tempfile.TemporaryDirectory() as temp:
            d=p.Diagnostic(self.args(Path(temp)));calls=[]
            def command(label,*args,**kwargs):
                calls.append((label,args))
                if label in ('home-raw','home-instrument'):raise ValueError('deliberate capture failure')
                return b'123\n',{'exit':0}
            d.adb_command=command;d.capture('home','com.android.settings')
            for label in ('home-focus-before','home-focus-after'):
                self.assertIn((label,('shell','dumpsys','window')),calls)
        source=Path(p.__file__).read_text()
        self.assertNotIn("['shell', 'sh', '-c'",source)
    def test_both_focus_observations_are_required(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);d=p.Diagnostic(self.args(root));dest=root/'home-automation';dest.mkdir();(dest/'uiautomation.png').write_bytes(b'pixels')
            (dest/'receipt.json').write_text(json.dumps(dict(scene='home',session=d.session,captureCompleted=True,accepted=False,sha256=p.sha(dest/'uiautomation.png'),pixels=100,width=10,height=10,rgbBlack=0)))
            png=p.base.PNG+b'\0'*4+b'IHDR'+(1080).to_bytes(4,'big')+(2400).to_bytes(4,'big')+b'\0'*12+b'\x00\x00\x00\x00IEND\xaeB`\x82'
            for after in ('com.android.settings','other.package',None):
                def command(label,*args,**kwargs):
                    if label.endswith('-raw'):return png,{'exit':0}
                    if label.endswith('-instrument'):return b'OK (1 test)',{'exit':0}
                    if '-focus-' in label:
                        package='com.android.settings' if label.endswith('before') else after
                        return (('mCurrentFocus=Window{123 u0 '+package+'/.Activity}').encode() if package else b'mCurrentFocus=null'),{'exit':0}
                    return b'514\n',{'exit':0}
                d.adb_command=command
                self.assertEqual(after=='com.android.settings',d.capture('home','com.android.settings'))
    def test_host_default_preserves_postboot_compatibility_and_no_early_navigation_claim(self):
        source=Path(p.__file__).read_text()
        self.assertIn('disable_guest_host_query_reset=True',source)
        self.assertNotIn("disable_guest_host_query_reset=args.graphics_mode",source)
        with tempfile.TemporaryDirectory() as temp:
            d=p.Diagnostic(self.args(Path(temp)))
            self.assertIs(d.receipt['systemNavigationPerformed'],False)
    def test_self_target_and_no_mutation(self):
        import xml.etree.ElementTree as ET
        root=Path(__file__).resolve().parents[1]/'android-qa'
        manifest=ET.parse(root/'src/graphicsDiagnostic/AndroidManifest.xml').getroot()
        self.assertEqual('dev.aerove.lite.test',manifest.find('instrumentation').get('{http://schemas.android.com/apk/res/android}targetPackage'))
        java=(root/'src/main/java/dev/aerove/lite/GraphicsBaselineDiagnosticTest.java').read_text()
        self.assertIn('getUiAutomation().takeScreenshot()',java)
        for token in ('SharedPreferences','startActivity','adoptShellPermissionIdentity','Runtime.getRuntime'):self.assertNotIn(token,java)
if __name__=='__main__':unittest.main()
