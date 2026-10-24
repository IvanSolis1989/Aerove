"""Checks for the app/test boundary generator; the real R8 fixture is opt-in locally."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from generate_android_test_keep_rules import collect_classes, collect_libraries, generate, write_archive


class AndroidTestKeepRulesTest(unittest.TestCase):
    def test_class_inputs_exclude_app_test_duplicates_from_library_scope(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = root / "classes"
            (directory / "app").mkdir(parents=True)
            (directory / "app/Api.class").write_bytes(b"\xca\xfe\xba\xbeapp")
            archive = root / "dependency.jar"
            with zipfile.ZipFile(archive, "w") as stream:
                stream.writestr("app/Api.class", b"\xca\xfe\xba\xbecompile-api")
                stream.writestr("dependency/Value.class", b"\xca\xfe\xba\xbelibrary")
                stream.writestr("META-INF/versions/21/dependency/Value.class", b"ignored")
            target = collect_classes([directory])
            library = collect_classes([archive], excluded=set(target))
            self.assertEqual(set(library), {"dependency/Value.class"})
            output = root / "normalized.jar"
            write_archive(output, library)
            with zipfile.ZipFile(output) as stream:
                self.assertEqual(stream.namelist(), ["dependency/Value.class"])

    def test_conflicting_dependency_definitions_fail_instead_of_picking_one(self):
        with tempfile.TemporaryDirectory() as temporary:
            archives = []
            for index in range(2):
                archive = Path(temporary) / f"{index}.jar"
                with zipfile.ZipFile(archive, "w") as stream:
                    stream.writestr("library/Value.class", b"\xca\xfe\xba\xbe" + bytes([index]))
                archives.append(archive)
            with self.assertRaisesRegex(ValueError, "Conflicting .*class"):
                collect_classes(archives)

    def test_trace_failure_never_leaves_rules_from_an_earlier_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for role in ["source", "target", "library"]:
                write_archive(root / f"{role}.jar", {f"{role}/Value.class": b"\xca\xfe\xba\xbe"})
            rules, report = root / "rules.pro", root / "report.json"
            rules.write_text("stale rules")
            report.write_text("stale success")
            manifest = dict(java="java", r8Classpath=[str(root / "r8.jar")],
                            sources=[str(root / "source.jar")], targets=[str(root / "target.jar")],
                            libraries=[str(root / "library.jar")])
            (root / "r8.jar").write_bytes(b"tool")
            with patch("generate_android_test_keep_rules.subprocess.run", side_effect=[subprocess.CompletedProcess([],0,"",""), subprocess.CalledProcessError(1, "trace")]):
                with self.assertRaises(subprocess.CalledProcessError):
                    generate(manifest, rules, report)
            self.assertFalse(rules.exists())
            self.assertFalse(report.exists())

    def test_library_lookup_preserves_order_and_reports_both_shadow_hashes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);boot=root/'z-android.jar';stub=root/'a-core-lambda-stubs.jar'
            chosen=b"\xca\xfe\xba\xbeboot";shadow=b"\xca\xfe\xba\xbewrong-stub"
            write_archive(boot,{'java/lang/invoke/CallSite.class':chosen})
            write_archive(stub,{'java/lang/invoke/CallSite.class':shadow})
            classes,events=collect_libraries([boot,stub,boot])
            self.assertEqual(chosen,classes['java/lang/invoke/CallSite.class'])
            self.assertEqual(1,len(events));self.assertIn(str(boot),events[0]['selected'])
            self.assertIn(str(stub),events[0]['shadowed']);self.assertFalse(events[0]['identical'])
            self.assertNotEqual(events[0]['selectedSha256'],events[0]['shadowedSha256'])
            reverse,_=collect_libraries([stub,boot]);self.assertEqual(shadow,reverse['java/lang/invoke/CallSite.class'])
            with self.assertRaisesRegex(ValueError,'first=.*a-core-lambda.*conflict=.*z-android.jar'):
                collect_classes([boot,stub])

    def test_source_target_conflicts_and_cross_role_overlap_remain_fatal(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)
            for name,image in [('one',b'one'),('two',b'two')]:
                write_archive(root/(name+'.jar'),{'same/Api.class':b"\xca\xfe\xba\xbe"+image})
            for role in ['test source','app target']:
                with self.subTest(role=role),self.assertRaisesRegex(ValueError,'Conflicting '+role+' class'):
                    collect_classes([root/'one.jar',root/'two.jar'],role=role)
            manifest=dict(java='unused',r8Classpath=[],sources=[str(root/'one.jar')],targets=[str(root/'two.jar')],libraries=[])
            with self.assertRaisesRegex(ValueError,'Test/app project class overlap'):
                generate(manifest,root/'rules.pro',root/'report.json')
            self.assertFalse((root/'rules.pro').exists());self.assertFalse((root/'report.json').exists())

    def test_one_library_archive_cannot_hide_conflicting_duplicate_entries(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive=Path(temporary)/'invalid.jar'
            with zipfile.ZipFile(archive,'w') as stream:
                stream.writestr('lib/Api.class',b"\xca\xfe\xba\xbefirst")
                stream.writestr('lib/Api.class',b"\xca\xfe\xba\xbesecond")
            with self.assertRaisesRegex(ValueError,'Conflicting library input class'):
                collect_libraries([archive])

    def test_failure_record_has_exact_ordered_inputs_and_actual_tool_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)
            for role in ['source','target','library']:
                write_archive(root/(role+'.jar'),{role+'/Api.class':b"\xca\xfe\xba\xbe"})
            (root/'r8.jar').write_bytes(b'actual-tool-fixture')
            manifest=dict(java='java',r8Classpath=[str(root/'r8.jar')],sources=[str(root/'source.jar')],
                          targets=[str(root/'target.jar')],libraries=[str(root/'library.jar')])
            rules,report=root/'rules.pro',root/'report.json'
            failure=subprocess.CalledProcessError(1,['actual-command'],output='actual stdout',stderr='actual missing member')
            with patch('generate_android_test_keep_rules.subprocess.run',side_effect=[subprocess.CompletedProcess([],0,'',''),failure]):
                with self.assertRaises(subprocess.CalledProcessError):generate(manifest,rules,report)
            data=json.loads((root/'report.json.failure.json').read_text())
            self.assertEqual('failed',data['status']);self.assertEqual('actual stdout',data['stdout'])
            self.assertEqual('actual missing member',data['stderr']);self.assertEqual(manifest,data['manifest'])
            self.assertEqual(str(root/'library.jar'),data['orderedInputs']['libraries'][0]['path'])
            self.assertRegex(data['orderedInputs']['libraries'][0]['sha256'],r'^[0-9a-f]{64}$')
            self.assertIn('command',data);self.assertIn('inputClasses',data)
            self.assertFalse(rules.exists());self.assertFalse(report.exists())


@unittest.skipUnless(all(os.environ.get("ABI_TEST_" + key) for key in ["JAVA", "ECJ", "R8", "ANDROID_JAR"]),
                     "Set ABI_TEST_JAVA/ECJ/R8/ANDROID_JAR for the real classfile/R8 fixture")
class RealR8BoundaryTest(unittest.TestCase):
    def test_independent_optimized_test_links_with_generated_allowobfuscation_rules(self):
        java, ecj, r8, library = [os.environ["ABI_TEST_" + key] for key in ["JAVA", "ECJ", "R8", "ANDROID_JAR"]]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sources = {
                "app/Api.java": """package app; public class Api {
                    public int value = 7;
                    public static class Companion { public boolean wasRequested(Object context) { return context != null; } }
                    public static class Marker {}
                    public static class State { public final int value;
                        public State(int value, int mask, Marker marker) { this.value = (mask & 1) != 0 ? 9 : value; }
                    }
                }""",
                "app/HostKt.java": "package app; public class HostKt { public static String getLocalValue() { return \"local\"; } }",
                "app/Entry.java": "package app; public class Entry { public static void main(String[] args) { System.out.println(1); } }",
                "testdep/Bridge.java": "package testdep; public class Bridge { public static String local() { return app.HostKt.getLocalValue(); } }",
                "test/TestEntry.java": """package test; public class TestEntry { public static void main(String[] args) {
                    if (new app.Api().value != 7 || !new app.Api.Companion().wasRequested(new Object()) ||
                        new app.Api.State(0, 1, null).value != 9 || !testdep.Bridge.local().equals("local")) throw new AssertionError();
                    System.out.println("BOUNDARY_OK");
                } }""",
            }
            for name, text in sources.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text)
            app, test, dependency = root / "app-classes", root / "test-classes", root / "test-dependency"
            subprocess.run([java, "-jar", ecj, "-17", "-d", str(app)] + [str(root / name) for name in sources if name.startswith("app/")], check=True, capture_output=True)
            subprocess.run([java, "-jar", ecj, "-17", "-cp", str(app), "-d", str(dependency), str(root / "testdep/Bridge.java")], check=True, capture_output=True)
            subprocess.run([java, "-jar", ecj, "-17", "-cp", os.pathsep.join([str(app), str(dependency)]), "-d", str(test), str(root / "test/TestEntry.java")], check=True, capture_output=True)
            app_jar, test_jar = root / "app.jar", root / "test.jar"
            write_archive(app_jar, collect_classes([app]))
            write_archive(test_jar, collect_classes([test]))
            dependency_jar, test_program = root / "test-dependency.jar", root / "test-program.jar"
            write_archive(dependency_jar, collect_classes([dependency]))
            write_archive(test_program, collect_classes([test, dependency]))
            rules, report = root / "generated.pro", root / "report.json"
            manifest = dict(java=java, javaCompiler=[java,"-jar",ecj], productNamespaces=["app"], r8Classpath=[r8], sources=[str(test_jar)], sourceDependencies=[str(dependency_jar), str(app_jar)],
                            targets=[str(app_jar)], libraries=[library] + ([os.environ["ABI_TEST_LAMBDA_STUBS"]] if os.environ.get("ABI_TEST_LAMBDA_STUBS") else []) + [str(app_jar)])
            generate(manifest, rules, report)
            generated = rules.read_text()
            if os.environ.get('ABI_TEST_LAMBDA_STUBS'):
                resolved=json.loads(report.read_text())
                callsite=next(event for event in resolved['libraryShadows'] if event['className']=='java/lang/invoke/CallSite.class')
                self.assertIn(str(Path(library).resolve()),callsite['selected'])
                self.assertIn('core-lambda-stubs.jar',callsite['shadowed'])
            self.assertIn("-keep,allowobfuscation", generated)
            for member in ["wasRequested", "getLocalValue", "<init>(int,int,app.Api$Marker)", "value"]:
                self.assertIn(member, generated.replace(" ", ""))
            self.assertNotIn("allowoptimization", generated)
            def shrink(input_jar, config, output, libraries, classpath=()):
                subprocess.run([java, "-cp", r8, "com.android.tools.r8.R8", "--release", "--classfile", "--pg-conf", str(config),
                                "--output", str(output)] + [argument for file in libraries for argument in ["--lib", str(file)]] +
                               [argument for file in classpath for argument in ["--classpath", str(file)]] + [str(input_jar)], check=True, capture_output=True)
            baseline = root / "baseline.pro"
            baseline.write_text("-keep class app.Entry { public static void main(java.lang.String[]); }\n")
            shrink(app_jar, baseline, root / "bad.jar", [library])
            broken = subprocess.run([java, "-cp", os.pathsep.join([str(test_program), str(root / "bad.jar")]), "test.TestEntry"], capture_output=True, text=True)
            self.assertNotEqual(broken.returncode, 0)
            self.assertRegex(broken.stderr, "NoClassDefFoundError|NoSuchMethodError|IncompatibleClassChangeError")
            app_config = root / "app.pro"
            mapping = root / "app.map"
            app_config.write_text(baseline.read_text() + generated + f"\n-printmapping {mapping.as_posix()}\n")
            shrink(app_jar, app_config, root / "good.jar", [library])
            renamed = re.findall(r"^(app\.[^ ]+) -> ([^:]+):", mapping.read_text(), re.MULTILINE)
            self.assertTrue(any(original != destination for original, destination in renamed))
            test_config = root / "test.pro"
            test_config.write_text("-keep class test.TestEntry { public static void main(java.lang.String[]); }\n" + f"-applymapping {mapping.as_posix()}\n")
            shrink(test_program, test_config, root / "optimized-test.jar", [library], [app_jar])
            passed = subprocess.run([java, "-cp", os.pathsep.join([str(root / "optimized-test.jar"), str(root / "good.jar")]), "test.TestEntry"], capture_output=True, text=True)
            if os.environ.get("ABI_TEST_EVIDENCE"):
                shutil.copytree(root, os.environ["ABI_TEST_EVIDENCE"], dirs_exist_ok=True)
            self.assertEqual(passed.returncode, 0, passed.stderr)
            self.assertEqual(passed.stdout.strip(), "BOUNDARY_OK")
            (root / "generated-success.pro").write_bytes(rules.read_bytes())
            (root / "generation-success.json").write_bytes(report.read_bytes())
            (root / "runtime-result.json").write_text(json.dumps(dict(unprotectedExit=broken.returncode,
                unprotectedError=broken.stderr, protectedExit=passed.returncode, protectedOutput=passed.stdout,
                classNamesRenamed=renamed), indent=2))
            # A real missing target member must stop generation; diagnostics stay strict.
            missing = dict(manifest, targets=[str(root / "bad.jar")], sourceDependencies=[str(dependency_jar)], libraries=[library])
            with self.assertRaises(subprocess.CalledProcessError):
                generate(missing, rules, report)
            self.assertFalse(rules.exists())
            if os.environ.get("ABI_TEST_EVIDENCE"):
                shutil.copytree(root, os.environ["ABI_TEST_EVIDENCE"], dirs_exist_ok=True)

    def test_real_r8_library_precedence_never_unions_a_shadowed_definition(self):
        java,ecj,r8,android=[os.environ['ABI_TEST_'+key] for key in ['JAVA','ECJ','R8','ANDROID_JAR']]
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)
            definitions={'good':('lib/Choice.java','package lib; public class Choice { public static int selected(){return 1;} }'),
                'bad':('lib/Choice.java','package lib; public class Choice { public static int shadowOnly(){return 2;} }'),
                'app':('app/Api.java','package app; public class Api { public static void called(){} }'),
                'test':('test/Caller.java','package test; public class Caller { public void call(){app.Api.called();lib.Choice.selected();} }')}
            for role,(name,text) in definitions.items():
                source=root/role/'src'/name;source.parent.mkdir(parents=True);source.write_text(text)
                command=[java,'-jar',ecj,'-17','-d',str(root/role/'classes')]
                if role=='test':command+=['-cp',os.pathsep.join(str(root/n/'classes') for n in ['good','app'])]
                subprocess.run(command+[str(source)],check=True,capture_output=True)
                write_archive(root/(role+'.jar'),collect_classes([root/role/'classes']))
            manifest=dict(java=java,javaCompiler=[java,'-jar',ecj],productNamespaces=['app'],r8Classpath=[r8],sources=[str(root/'test.jar')],targets=[str(root/'app.jar')],
                          libraries=[android,str(root/'good.jar'),str(root/'bad.jar')])
            rules,report=root/'rules.pro',root/'report.json'
            generate(manifest,rules,report)
            self.assertIn('called',rules.read_text())
            proof=json.loads(report.read_text());self.assertEqual(1,len(proof['libraryShadows']))
            (root/'success-report.json').write_bytes(report.read_bytes())
            # If two libraries were unioned, this reversed input would incorrectly pass.
            reversed_manifest=dict(manifest,libraries=[android,str(root/'bad.jar'),str(root/'good.jar')])
            with self.assertRaises(subprocess.CalledProcessError):generate(reversed_manifest,rules,report)
            failure=json.loads((root/'report.json.failure.json').read_text())
            self.assertIn('selected',failure['stderr']);self.assertFalse(rules.exists());self.assertFalse(report.exists())
            if os.environ.get('ABI_TEST_EVIDENCE'):
                shutil.copytree(root,Path(os.environ['ABI_TEST_EVIDENCE'])/'library-precedence',dirs_exist_ok=True)

    def test_typed_context_boundary_keeps_bridge_and_defers_only_third_party_compatibility(self):
        java,ecj,r8,android=[os.environ['ABI_TEST_'+key] for key in ['JAVA','ECJ','R8','ANDROID_JAR']]
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)
            def compile_classes(role,definitions,classpath=()):
                sources=[]
                for name,text in definitions.items():
                    p=root/role/'src'/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text);sources.append(str(p))
                destination=root/role/'classes'
                command=[java,'-jar',ecj,'-source','17','-target','17','-d',str(destination)]
                if classpath:command+=['-cp',os.pathsep.join(map(str,classpath))]
                subprocess.run(command+sources,check=True,capture_output=True)
                return collect_classes([destination]),destination
            full,target_dir=compile_classes('app',{'app/Api.java':'package app; public class Api { public static void viaBridge(){} }',
                'app/Removed.java':'package app; public class Removed { public static void call(){} }'})
            target=root/'app.jar';write_archive(target,{'app/Api.class':full['app/Api.class']})
            optional,optional_dir=compile_classes('optional',{
                'optional/External.java':'package optional; public class External { public static int value=1; public static void call(){} }',
                'thirdparty/RuntimeApi.java':'package thirdparty; public class RuntimeApi { public static void absentAtRuntime(){} }'})
            dependency,dependency_dir=compile_classes('deps',{
                'thirdparty/Bridge.java':'package thirdparty; public class Bridge { public static void call(){ app.Api.viaBridge(); } }',
                'thirdparty/Feature.java':'package thirdparty; public class Feature { public static int optional(){optional.External.call();thirdparty.RuntimeApi.absentAtRuntime();return optional.External.value;} }'},[target_dir,optional_dir])
            empty_api,_=compile_classes('actual-api',{'thirdparty/RuntimeApi.java':'package thirdparty; public class RuntimeApi {}'})
            dependency.update(empty_api);depjar=root/'dependency.jar';write_archive(depjar,dependency)
            projects,project_dir=compile_classes('project',{'zproject/TestEntry.java':'package zproject; public class TestEntry { public void call(){ thirdparty.Bridge.call(); } }'},[dependency_dir,target_dir])
            project=root/'project.jar';write_archive(project,projects)
            manifest=dict(java=java,javaCompiler=[java,'-jar',ecj],productNamespaces=['app'],r8Classpath=[r8],sources=[str(project)],
                          sourceDependencies=[str(depjar)],targets=[str(target)],libraries=[android])
            def run_case(name,changes=None,fatal=False):
                directory=root/'cases'/name;directory.mkdir(parents=True)
                rules,report=directory/'rules.pro',directory/'report.json'
                if fatal:
                    with self.assertRaises(subprocess.CalledProcessError):generate(dict(manifest,**(changes or {})),rules,report)
                    self.assertFalse(rules.exists());self.assertFalse(report.exists())
                    return json.loads((directory/'report.json.failure.json').read_text())['referenceDiagnostics']
                generate(dict(manifest,**(changes or {})),rules,report)
                proof=json.loads(report.read_text())['referenceDiagnostics']
                self.assertEqual('not-verified',proof['testRuntimeCompatibility']);return proof
            proof=run_case('optional-third-party')
            self.assertEqual(0,proof['fatalCount']);self.assertGreaterEqual(proof['deferredCount'],4)
            self.assertEqual({'class','field','method'},{e['kind'] for e in proof['missingDefinitions']})
            mismatch=next(e for e in proof['missingDefinitions'] if e['holder']=='Lthirdparty/RuntimeApi;')
            self.assertEqual('deferred-third-party-runtime',mismatch['classification'])
            self.assertTrue(all(c['role']=='THIRD_PARTY_TEST' for e in proof['missingDefinitions'] for c in e['contexts']))
            self.assertTrue(all('dependency.jar!' in c['inputOrigin'] and re.fullmatch('[0-9a-f]{64}',c['classSha256']) for e in proof['missingDefinitions'] for c in e['contexts']))
            self.assertIn('viaBridge',(root/'cases/optional-third-party/rules.pro').read_text())
            direct,_=compile_classes('direct-project',{'zproject/Direct.java':'package zproject; public class Direct { public void call(){ optional.External.call(); } }'},[optional_dir])
            directjar=root/'direct.jar';write_archive(directjar,direct)
            direct_proof=run_case('project-missing',dict(sources=[str(directjar)]),fatal=True)
            self.assertTrue(any('project-reference' in e['fatalReasons'] for e in direct_proof['missingDefinitions']))
            changed_target,_=compile_classes('removed-member',{'app/Api.java':'package app; public class Api {}'})
            badtarget=root/'bad-target.jar';write_archive(badtarget,changed_target)
            target_proof=run_case('dependency-to-missing-target-member',dict(targets=[str(badtarget)]),fatal=True)
            self.assertTrue(any('app-target-definition' in e['fatalReasons'] for e in target_proof['missingDefinitions']))
            removed,_=compile_classes('missing-product-dep',{'thirdparty/RemovedBridge.java':'package thirdparty; public class RemovedBridge { public void call(){app.Removed.call();} }'},[target_dir])
            removedjar=root/'removed-dep.jar';write_archive(removedjar,removed)
            product_proof=run_case('dependency-to-missing-product-class',dict(sourceDependencies=[str(removedjar)]),fatal=True)
            self.assertTrue(any('product-namespace' in e['fatalReasons'] for e in product_proof['missingDefinitions']))
            definitions={'adep/A%02d.java'%i:'package adep; public class A%02d { public void call(){optional.External.call();} }'%i for i in range(15)}
            many,_=compile_classes('many-dependency-contexts',definitions,[optional_dir]);manyjar=root/'many.jar';write_archive(manyjar,many)
            full_proof=run_case('project-context-after15-dependency-contexts',dict(sources=[str(directjar)],sourceDependencies=[str(manyjar)]),fatal=True)
            collection=next(e for e in full_proof['missingDefinitions'] if len(e['contexts'])>=16)
            self.assertEqual(16,len(collection['contexts']));self.assertEqual('THIRD_PARTY_TEST',collection['contexts'][0]['role'])
            self.assertEqual('PROJECT',collection['contexts'][-1]['role']);self.assertIn('project-reference',collection['fatalReasons'])
            self.assertIn('other contexts',collection['message'])
            (root/'typed-case-results.json').write_text(json.dumps(dict(optional=proof,project=direct_proof,target=target_proof,
                productNamespace=product_proof,fullContexts=full_proof),indent=2))
            if os.environ.get('ABI_TEST_EVIDENCE'):
                shutil.copytree(root,Path(os.environ['ABI_TEST_EVIDENCE'])/'typed-contexts',dirs_exist_ok=True)


if __name__ == "__main__":
    unittest.main()
