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
    def test_restricted_gdb_profile(self):
        from emulator_current import sigill_gdb_script
        script=sigill_gdb_script(123,Path('/ready'),Path('/fault'),restricted=True)
        for forbidden in ('p $_siginfo', 'rsp rbp', 'x/256bx', 'x/160bx', 'x/16bx', 'thread.switch()', 'generate-core', 'bt full', 'info locals', 'show environment'):
            self.assertNotIn(forbidden,script)
        for required in ('set auto-load off','set print frame-arguments none','set print entry-values no','handle SIGILL stop print pass','emit("bt 24")','fields[5].startswith("/")','"x" in fields[1]'):
            self.assertIn(required,script)
        self.assertIn('rsp rbp',sigill_gdb_script(123,Path('/ready'),Path('/fault')))
        compile(script.split('python\n',1)[1].rsplit('\nend',1)[0],'<gdb-python>','exec')
    def test_sigill_requires_host_default_and_rejects_environment(self):
        with tempfile.TemporaryDirectory() as temp:
            args=self.args(Path(temp));args.host_sigill=True
            with self.assertRaises(ValueError):p.Diagnostic(args)
            args.graphics_mode='host-default';d=p.Diagnostic(args)
            self.assertEqual('host-default-sigill',d.receipt['experiment'])
        env=dict(GITHUB_REPOSITORY='IvanSolis1989/Aerove',GITHUB_EVENT_NAME='workflow_dispatch',GITHUB_REF='refs/heads/dot/cloud-j316-checkpoint-20261108',AEROVE_EMULATOR_SIGILL_TRACE='1')
        with patch.dict(os.environ,env,clear=True),self.assertRaises(ValueError):p.guard_execution()
    def test_failed_attach_is_closed_and_not_success(self):
        from unittest.mock import Mock
        with tempfile.TemporaryDirectory() as temp:
            d=p.Diagnostic(self.args(Path(temp)));capture=Mock()
            def execute():
                d.host_capture=capture
                raise RuntimeError('attach failed')
            d.execute_graphics=execute
            with self.assertRaisesRegex(RuntimeError,'attach failed'):d.execute()
            capture.close.assert_called_once()
            self.assertFalse(d.receipt['accepted'])
    def test_forensics_attach_is_after_system_gate_before_app(self):
        source=Path(p.__file__).read_text()
        self.assertLess(source.index("require(usable, 'System layer failed"),source.index('self.host_capture.start()'))
        self.assertLess(source.index('self.host_capture.start()'),source.index("self.adb_command('install-original'"))
        workflow=(Path(__file__).resolve().parents[3]/'.github/workflows/aerove-lite.yaml').read_text()
        self.assertIn('lite333-graphics-host-sigill',workflow)
        self.assertIn('extra+=(--host-sigill)',workflow)
    def test_sigill_preflight_provisions_missing_official_gdb(self):
        source=(Path(__file__).resolve().parents[3]/'.github/workflows/aerove-lite.yaml').read_text()
        block=source.split('    - name: Verify restricted owned-host signal capture before SIGILL diagnosis',1)[1].split('    - name:',1)[0]
        for required in ("if: inputs.diagnostic_target == 'lite333-graphics-host-sigill'",'ulimit -c 0','if ! command -v gdb','install --yes --no-install-recommends gdb',"AEROVE_TEST_SIGILL_GDB: '1'",'test_emulator_current.SigillCaptureHostTests','timeout --signal=TERM --kill-after=10s 90s'):
            self.assertIn(required,block)
        self.assertLess(block.index('install --yes'),block.index('python3 -B -m unittest'))
        for forbidden in ('ptrace_scope','core_pattern','--allow-unauthenticated','|| true'):
            self.assertNotIn(forbidden,block)
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
        self.assertIn("inputs.diagnostic_target == 'lite333-graphics-host-sigill') && 'host-default' || 'baseline'", source)
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
    def test_home_waits_for_natural_setup_and_current_resolved_surface(self):
        with tempfile.TemporaryDirectory() as temp:
            d=p.Diagnostic(self.args(Path(temp)));clock=[0.0];seen=[]
            def command(label,*args,**kwargs):
                seen.append(args);index=d.receipt['sceneReadiness'][-1]['observations'][-1]['index']
                package='com.google.android.sdksetup' if index==0 else 'com.google.android.apps.nexuslauncher'
                if label.endswith('-setup'):return (b'0' if index==0 else b'1'),{'exit':0}
                if label.endswith('-resolve'):return (package+'/.Activity').encode(),{'exit':0}
                if index==0:return b'mCurrentFocus=null',{'exit':0}
                value='mCurrentFocus=Window{abc u0 '+package+'/'+package+'.Activity}\n  Window #1 Window{abc u0 '+package+'/'+package+'.Activity}:\n    mHasSurface=true isReadyForDisplay()=true isOnScreen=true isVisible=true Surface: shown=true\n'
                return value.encode(),{'exit':0}
            d.adb_command=command
            with patch.object(p.time,'monotonic',side_effect=lambda:clock[0]),patch.object(p.time,'sleep',side_effect=lambda n:clock.__setitem__(0,clock[0]+n)):
                self.assertEqual('com.google.android.apps.nexuslauncher',d.wait_scene_stable('home',['-a','android.intent.action.MAIN'],budget=3))
            self.assertEqual(3,len(d.receipt['sceneReadiness'][0]['observations']))
            self.assertTrue(all(args[:3] != ('shell','settings','put') for args in seen))
    def test_missing_setup_state_fails_at_bounded_deadline(self):
        with tempfile.TemporaryDirectory() as temp:
            d=p.Diagnostic(self.args(Path(temp)));clock=[0.0]
            def command(label,*args,**kwargs):
                return (b'null' if label.endswith('-setup') else b'com.google.android.sdksetup/.DefaultActivity' if label.endswith('-resolve') else b'mCurrentFocus=null'),{'exit':0}
            d.adb_command=command
            with patch.object(p.time,'monotonic',side_effect=lambda:clock[0]),patch.object(p.time,'sleep',side_effect=lambda n:clock.__setitem__(0,clock[0]+n)),self.assertRaises(ValueError):
                d.wait_scene_stable('home',[],budget=1)
            self.assertEqual(1,clock[0]);self.assertFalse(d.receipt['sceneReadiness'][0]['ready'])
    def test_setup_complete_without_matching_home_focus_still_times_out(self):
        with tempfile.TemporaryDirectory() as temp:
            d=p.Diagnostic(self.args(Path(temp)));clock=[0.0]
            def command(label,*args,**kwargs):
                if label.endswith('-setup'):return b'1',{'exit':0}
                if label.endswith('-resolve'):return b'com.google.android.apps.nexuslauncher/.Home',{'exit':0}
                return b'mCurrentFocus=Window{abc u0 other/.Home}\n  Window #1 Window{abc u0 other/.Home}:\n mHasSurface=true isReadyForDisplay()=true isOnScreen=true isVisible=true Surface: shown=true\n',{'exit':0}
            d.adb_command=command
            with patch.object(p.time,'monotonic',side_effect=lambda:clock[0]),patch.object(p.time,'sleep',side_effect=lambda n:clock.__setitem__(0,clock[0]+n)),self.assertRaises(ValueError):d.wait_scene_stable('home',[],budget=1)
            self.assertFalse(d.receipt['sceneReadiness'][0]['ready'])
    def test_readiness_command_error_is_not_readiness(self):
        with tempfile.TemporaryDirectory() as temp:
            d=p.Diagnostic(self.args(Path(temp)))
            def command(*args,**kwargs):raise OSError('adb unavailable')
            d.adb_command=command;row={}
            self.assertIsNone(d.attempt(row,'readiness',lambda:d.wait_scene_stable('home',[])))
            self.assertFalse(row['readiness']['collected']);self.assertFalse(d.receipt['sceneReadiness'][0]['ready'])
    def test_surface_readiness_is_bound_to_focused_window_id(self):
        window='mCurrentFocus=Window{abc u0 p/.A}\n  Window #1 Window{other u0 p/.A}:\n mHasSurface=true isReadyForDisplay()=true isOnScreen=true isVisible=true Surface: shown=true\n'
        self.assertFalse(p.focused_surface_ready(window))
        self.assertTrue(p.focused_surface_ready(window.replace('Window{other','Window{abc')))
    def test_self_target_and_no_mutation(self):
        import xml.etree.ElementTree as ET
        root=Path(__file__).resolve().parents[1]/'android-qa'
        manifest=ET.parse(root/'src/graphicsDiagnostic/AndroidManifest.xml').getroot()
        self.assertEqual('dev.aerove.lite.test',manifest.find('instrumentation').get('{http://schemas.android.com/apk/res/android}targetPackage'))
        java=(root/'src/main/java/dev/aerove/lite/GraphicsBaselineDiagnosticTest.java').read_text()
        self.assertIn('getUiAutomation().takeScreenshot()',java)
        for token in ('SharedPreferences','startActivity','adoptShellPermissionIdentity','Runtime.getRuntime'):self.assertNotIn(token,java)
if __name__=='__main__':unittest.main()
