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

from generate_android_test_keep_rules import collect_classes, generate, write_archive


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
            with self.assertRaisesRegex(ValueError, "Conflicting class"):
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
            with patch("generate_android_test_keep_rules.subprocess.run", side_effect=subprocess.CalledProcessError(1, "trace")):
                with self.assertRaises(subprocess.CalledProcessError):
                    generate(manifest, rules, report)
            self.assertFalse(rules.exists())
            self.assertFalse(report.exists())


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
            manifest = dict(java=java, r8Classpath=[r8], sources=[str(test_jar)], sourceDependencies=[str(dependency_jar), str(app_jar)],
                            targets=[str(app_jar)], libraries=[library, str(app_jar)])
            generate(manifest, rules, report)
            generated = rules.read_text()
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


if __name__ == "__main__":
    unittest.main()
