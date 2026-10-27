"""Synthetic protocol tests; no test APK, Android device or performance evidence."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

import instrumentation_shards as shards


class ShardSelectionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.descriptions = [f"case{index}[Solid](a.b.Test)" for index in range(40)]
        self.identity = dict(sourceRevision="a" * 40, sourceTree="b" * 40, workflowRun="11", runAttempt="2", runNumber="3")

    def log(self, descriptions, statuses=None):
        output = ""
        for index, description in enumerate(descriptions, 1):
            test, class_name = description.rsplit("(", 1)
            class_name = class_name[:-1]
            for code in (1, (statuses or {}).get(description, 0)):
                for key, value in dict(id="AndroidJUnitRunner", numtests=len(descriptions), current=index,
                                       **{"class": class_name, "test": test}).items():
                    output += f"INSTRUMENTATION_STATUS: {key}={value}\n"
                output += f"INSTRUMENTATION_STATUS_CODE: {code}\n"
        return output + "INSTRUMENTATION_CODE: -1\n"

    def write_log(self, descriptions, statuses=None):
        path = self.root / "instrumentation.log"
        path.write_text(self.log(descriptions, statuses))
        return path

    def discovery(self, index):
        return dict(numShards=shards.SHARD_COUNT, shardIndex=index, identity=self.identity, runner="androidx.test:runner:1.6.2",
                    junit="junit:junit:4.13.2", algorithm=f"abs(Description.hashCode()) % {shards.SHARD_COUNT}", apkSha256={"app": "c" * 64},
                    runtimeManifestSha256="d" * 64, dependencyReportSha256="e" * 64,
                    discoveryFingerprint=shards.fingerprint(sorted(self.descriptions)), discoveredDescriptions=sorted(self.descriptions),
                    discoveryCount=len(self.descriptions), excludedClass=shards.MATRIX_CLASS)

    def expected(self, index):
        return sorted(description for description in self.descriptions if shards.shard_for(description) == index)

    def artifact_receipts(self):
        for index in range(shards.SHARD_COUNT):
            receipt = shards.verify(self.discovery(index), self.write_log(self.expected(index)))
            path = self.root / f"AEROVE-Lite-shard-selection-{index}-of-{shards.SHARD_COUNT}-attempt-2" / "TEST-SELECTION.json"
            path.parent.mkdir()
            path.write_text(json.dumps(receipt))

    def test_java_utf16_hash_and_min_value_reject(self):
        self.assertEqual(2112, shards.java_hash("Aa"))
        self.assertEqual(shards.java_hash("Aa"), shards.java_hash("BB"))
        self.assertEqual(1772899, shards.java_hash("\U0001f600"))
        self.assertEqual(-(2**31), shards.java_hash("polygenelubricants"))
        with self.assertRaisesRegex(ValueError, "MIN_VALUE"):
            shards.shard_for("polygenelubricants")

    def test_log_only_needs_complete_statuses_but_no_ok_summary(self):
        records = shards.parse_log(self.write_log(self.descriptions))
        self.assertEqual(len(self.descriptions), len(records))
        self.assertEqual(set(self.descriptions), {record["description"] for record in records})

    def test_discovery_binds_resolved_runner_graph_apks_source_and_never_calls_it_passed(self):
        runtime = self.root / "runtime"
        (runtime / "dependencies").mkdir(parents=True)
        graph = runtime / "dependencies/releaseAndroidTestRuntimeClasspath.stdout.txt"
        graph.write_text("+--- androidx.test:runner:1.6.2\n+--- androidx.test:runner:1.5.0 -> 1.6.2 (*)\n+--- junit:junit:4.13.2\n")
        (runtime / "dependencies/releaseAndroidTestRuntimeClasspath.exit.txt").write_text("0\n")
        manifest_path = runtime / "source-manifest.json"
        manifest = dict(self.identity, files=[dict(path="dependencies/releaseAndroidTestRuntimeClasspath.stdout.txt", sha256=shards.sha(graph))])
        manifest_path.write_text(json.dumps(manifest))
        proof = self.root / "proof.json"
        apks = {"app-release.apk": "c" * 64, "app-release-androidTest.apk": "d" * 64}
        proof.write_text(json.dumps(dict(runtimeDirectory=str(runtime), apks=apks)))
        source = self.root / "SOURCE.json"
        source.write_text(json.dumps(dict(self.identity, phase="remaining", testShard=dict(numShards=shards.SHARD_COUNT, shardIndex=0,
            runner="androidx.test.runner.AndroidJUnitRunner", excludedClass=shards.MATRIX_CLASS))))
        sums = self.root / "APK_SHA256SUMS"
        sums.write_text("".join(f"{value}  apks/{name}\n" for name, value in apks.items()))
        log = self.write_log(self.descriptions)
        receipt = shards.discover(log, proof, source, sums)
        self.assertEqual("androidx.test:runner:1.6.2", receipt["runner"])
        self.assertEqual([], receipt["passedTests"])
        self.assertEqual(len(self.descriptions), receipt["discoveryCount"])
        actual_run141_graph = "+--- androidx.test:runner:1.6.2 -> 1.7.0\n+--- junit:junit:4.13.2\n"
        graph.write_text(actual_run141_graph)
        with self.assertRaisesRegex(ValueError, "source-bound"):
            shards.discover(log, proof, source, sums)
        manifest["files"][0]["sha256"] = shards.sha(graph)
        manifest_path.write_text(json.dumps(manifest))
        receipt = shards.discover(log, proof, source, sums)
        self.assertEqual("androidx.test:runner:1.7.0", receipt["runner"])
        self.assertIn("androidx.test:runner:1.6.2 -> 1.7.0", graph.read_text())
        self.assertEqual("logOnly-no-test-method-execution", receipt["discoveryMode"])
        self.assertEqual([], receipt["passedTests"])
        self.assertEqual(sorted(self.descriptions), receipt["discoveredDescriptions"])
        supported_graph = graph.read_text()
        for invalid in (supported_graph.replace("1.7.0", "1.8.0"),
                        supported_graph + "+--- androidx.test:runner:1.6.2\n",
                        supported_graph.replace("4.13.2", "4.13.1"),
                        supported_graph + "+--- junit:junit:4.13.1\n",
                        supported_graph.replace("+--- junit:junit:4.13.2\n", "")):
            graph.write_text(invalid)
            manifest["files"][0]["sha256"] = shards.sha(graph)
            manifest_path.write_text(json.dumps(manifest))
            with self.subTest(graph=invalid), self.assertRaisesRegex(ValueError, "Unsupported resolved"):
                shards.discover(log, proof, source, sums)
        graph.write_text(supported_graph)
        manifest["files"][0]["sha256"] = shards.sha(graph)
        manifest_path.write_text(json.dumps(manifest))
        original_source = source.read_text()
        changed_source = json.loads(original_source)
        changed_source["sourceRevision"] = "f" * 40
        source.write_text(json.dumps(changed_source))
        with self.assertRaisesRegex(ValueError, "source differs"):
            shards.discover(log, proof, source, sums)
        source.write_text(original_source)
        sums.write_text(sums.read_text().replace("c" * 64, "f" * 64))
        with self.assertRaisesRegex(ValueError, "APKs differ"):
            shards.discover(log, proof, source, sums)

    def test_version_contract_is_the_single_strict_source_for_resolved_support(self):
        contract = shards.load_version_contract()
        self.assertEqual({"androidx.test:runner", "junit:junit"}, set(contract))
        invalid_contracts = [
            {"schemaVersion": 1, "coordinates": {"androidx.test:runner": {"supportedResolvedVersions": ["1.7.0"]}}},
            {"schemaVersion": 1, "coordinates": {"androidx.test:runner": {"supportedResolvedVersions": ["1.7.0", "1.7.0"]},
                "junit:junit": {"supportedResolvedVersions": ["4.13.2"]}}},
            {"schemaVersion": 2, "coordinates": {"androidx.test:runner": {"supportedResolvedVersions": ["1.7.0"]},
                "junit:junit": {"supportedResolvedVersions": ["4.13.2"]}}},
        ]
        contract_path = self.root / "bad-instrumentation-version-contract.json"
        for invalid in invalid_contracts:
            contract_path.write_text(json.dumps(invalid))
            with self.subTest(contract=invalid), self.assertRaises(ValueError):
                shards.load_version_contract(contract_path)
        contract_path.unlink(missing_ok=True)

    def test_both_package_abis_run_resolution_gate_before_apk_tasks(self):
        repository = Path(__file__).resolve().parents[3]
        workflow = (repository / ".github/workflows/aerove-lite-build.yaml").read_text(encoding="utf-8")
        commands = [line.strip() for line in workflow.splitlines()
                    if "--init-script tool/compose_ui_graphs.gradle" in line
                    and ":app:reportComposeUiResolution" in line]
        self.assertEqual(2, len(commands))
        arm = next(command for command in commands if "-PliteAbi=arm64-v8a" in command)
        x86 = next(command for command in commands if "-PliteAbi=x86_64" in command)
        for command in (arm, x86):
            self.assertLess(command.index(":app:reportComposeUiResolution"), command.index(":app:assembleRelease"))
        self.assertNotIn(":app:assembleReleaseAndroidTest", arm)
        self.assertLess(x86.index(":app:assembleRelease"), x86.index(":app:assembleReleaseAndroidTest"))
        graph_task = (repository / "apps/aerove-lite/tool/compose_ui_graphs.gradle").read_text(encoding="utf-8")
        self.assertIn("releaseAndroidTestRuntimeClasspath", graph_task)
        self.assertIn("app.rootProject.file('tool/instrumentation-version-contract.json')", graph_task)
        self.assertIn("mustRunAfter(reportTask)", graph_task)
        compile_ordering = graph_task.split("app.tasks.matching { it.name in ['compileReleaseKotlin'", 1)[1].split("\n    }", 1)[0]
        self.assertIn("mustRunAfter(reportTask)", compile_ordering)
        for compile_task in ("compileReleaseKotlin", "compileReleaseJavaWithJavac",
                             "compileReleaseAndroidTestKotlin", "compileReleaseAndroidTestJavaWithJavac"):
            with self.subTest(compile_task=compile_task):
                self.assertIn(compile_task, graph_task)

    def test_parse_rejects_partial_duplicate_failure_unknown_and_excluded_class(self):
        good = self.log(self.descriptions)
        invalid = [good.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"),
                   good.replace(f"numtests={len(self.descriptions)}", f"numtests={len(self.descriptions) + 1}"),
                   good.replace("current=2\n", "current=1\n"),
                   good.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: -2", 1),
                   good.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: 9", 1),
                   self.log([self.descriptions[0]] * 2),
                   self.log([f"matrix({shards.MATRIX_CLASS})"]),
                   good.replace("INSTRUMENTATION_STATUS: class=a.b.Test\n", "", 1),
                   good.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: 1", 1)]
        for text in invalid:
            path = self.root / "bad.log"
            path.write_text(text)
            with self.subTest(text=text[:80]), self.assertRaises(ValueError):
                shards.parse_log(path)

    def test_exact_shard_rejects_missing_extra_and_other_shard(self):
        for index in range(shards.SHARD_COUNT):
            expected = self.expected(index)
            receipt = shards.verify(self.discovery(index), self.write_log(expected))
            self.assertTrue(receipt["allExpectedPassed"])
            for invalid in (expected[:-1], self.descriptions, self.expected((index + 1) % shards.SHARD_COUNT)):
                with self.subTest(index=index), self.assertRaises(ValueError):
                    shards.verify(self.discovery(index), self.write_log(invalid))

    def test_ignored_and_assumption_are_accounted_separately(self):
        expected = self.expected(0)
        statuses = {expected[0]: -3, expected[1]: -4}
        receipt = shards.verify(self.discovery(0), self.write_log(expected, statuses))
        self.assertFalse(receipt["allExpectedPassed"])
        self.assertEqual([expected[0]], receipt["terminalStatuses"]["ignored"])
        self.assertEqual([expected[1]], receipt["terminalStatuses"]["assumptionFailed"])
        self.assertNotIn(expected[0], receipt["terminalStatuses"]["passed"])
        self.assertNotIn(expected[1], receipt["terminalStatuses"]["passed"])

    def test_aggregate_requires_exact_eight_artifacts_and_same_discovery_apk_source(self):
        self.artifact_receipts()
        result = shards.aggregate(self.root, "a" * 40, "11", "2")
        self.assertEqual(len(self.descriptions), result["discoveredCount"])
        self.assertTrue(result["allDiscoveredPassed"])
        path = self.root / "AEROVE-Lite-shard-selection-2-of-8-attempt-2/TEST-SELECTION.json"
        original = json.loads(path.read_text())
        for key, invalid in (("shardIndex", 1), ("numShards", 3), ("discoveryFingerprint", "x"),
                             ("apkSha256", {"app": "f" * 64}), ("identity", dict(self.identity, runAttempt="1")),
                             ("runner", "androidx.test:runner:1.7.0")):
            changed = copy.deepcopy(original)
            changed["discovery"][key] = invalid
            path.write_text(json.dumps(changed))
            with self.subTest(key=key), self.assertRaises(ValueError):
                shards.aggregate(self.root, "a" * 40, "11", "2")
        path.write_text(json.dumps(original))
        for expected_source, expected_run, expected_attempt in (("b" * 40, "11", "2"), ("a" * 40, "12", "2"), ("a" * 40, "11", "1")):
            with self.assertRaises(ValueError):
                shards.aggregate(self.root, expected_source, expected_run, expected_attempt)
        path.unlink()
        with self.assertRaises(ValueError):
            shards.aggregate(self.root, "a" * 40, "11", "2")

    def test_aggregate_rejects_legacy_four_shard_receipts(self):
        for index in range(4):
            old = self.root / f"AEROVE-Lite-shard-selection-{index}-of-4-attempt-2"
            old.mkdir()
            (old / "TEST-SELECTION.json").write_text("{}")
        with self.assertRaisesRegex(ValueError, "8 unique shard artifacts"):
            shards.aggregate(self.root, "a" * 40, "11", "2")

    def test_aggregate_rejects_a_ninth_selection_receipt(self):
        self.artifact_receipts()
        extra = self.root / "AEROVE-Lite-shard-selection-8-of-8-attempt-2"
        extra.mkdir()
        (extra / "TEST-SELECTION.json").write_text("{}")
        with self.assertRaisesRegex(ValueError, "8 unique shard artifacts"):
            shards.aggregate(self.root, "a" * 40, "11", "2")

    def test_aggregate_rejects_skipped_promoted_to_passed(self):
        self.artifact_receipts()
        path = self.root / "AEROVE-Lite-shard-selection-0-of-8-attempt-2/TEST-SELECTION.json"
        expected = self.expected(0)
        receipt = shards.verify(self.discovery(0), self.write_log(expected, {expected[0]: -3}))
        path.write_text(json.dumps(receipt))
        self.assertFalse(shards.aggregate(self.root, "a" * 40, "11", "2")["allDiscoveredPassed"])
        receipt["allExpectedPassed"] = True
        path.write_text(json.dumps(receipt))
        with self.assertRaises(ValueError):
            shards.aggregate(self.root, "a" * 40, "11", "2")

    def test_split_invocations_keep_original_discovery_and_exact_shard_union(self):
        self.descriptions += [shards.SSID_DESCRIPTION]
        index = shards.shard_for(shards.SSID_DESCRIPTION)
        discovery = self.discovery(index)
        expected = self.expected(index)
        ordinary = [value for value in expected if value != shards.SSID_DESCRIPTION]
        normal = self.root / "ordinary.log"
        isolated = self.root / "ssid.log"
        normal.write_text(self.log(ordinary))
        isolated.write_text(self.log([shards.SSID_DESCRIPTION]))
        receipt = shards.verify(discovery, normal, isolated)
        self.assertEqual(expected, receipt["actualDescriptions"])
        self.assertEqual(len(self.descriptions), receipt["discovery"]["discoveryCount"])
        self.assertEqual(discovery["discoveryFingerprint"], receipt["discovery"]["discoveryFingerprint"])
        with self.assertRaisesRegex(ValueError, "terminal/start set|separate scoped"):
            shards.verify(discovery, normal)
        for values in ([shards.SSID_DESCRIPTION] * 2, ordinary[:1], [], [shards.SSID_DESCRIPTION, ordinary[0]]):
            isolated.write_text(self.log(values))
            with self.subTest(values=values), self.assertRaises(ValueError):
                shards.verify(discovery, normal, isolated)
        isolated.write_text(self.log([shards.SSID_DESCRIPTION]))
        normal.write_text(self.log(expected))
        with self.assertRaises(ValueError):
            shards.verify(discovery, normal, isolated)

    def test_split_aggregate_binds_both_invocations_to_same_source_and_apks(self):
        self.descriptions += [shards.SSID_DESCRIPTION]
        for index in range(shards.SHARD_COUNT):
            expected = self.expected(index)
            isolated = self.root / f"ssid-{index}.log"
            ordinary = self.root / f"ordinary-{index}.log"
            selected = [value for value in expected if value == shards.SSID_DESCRIPTION]
            ordinary.write_text(self.log([value for value in expected if value not in selected]))
            isolated.write_text(self.log(selected))
            receipt = shards.verify(self.discovery(index), ordinary, isolated if selected else None)
            path = self.root / f"AEROVE-Lite-shard-selection-{index}-of-{shards.SHARD_COUNT}-attempt-2/TEST-SELECTION.json"
            path.parent.mkdir()
            path.write_text(json.dumps(receipt))
        result = shards.aggregate(self.root, "a" * 40, "11", "2")
        self.assertEqual(len(self.descriptions), result["discoveredCount"])
        self.assertEqual(len(self.descriptions), result["passedCount"])
        path = self.root / "AEROVE-Lite-shard-selection-1-of-8-attempt-2/TEST-SELECTION.json"
        original = json.loads(path.read_text())
        for field, value in (("apkSha256", {"app": "x" * 64}), ("identity", {}), ("descriptions", [])):
            changed = copy.deepcopy(original)
            changed["instrumentationLogs"][1][field] = value
            path.write_text(json.dumps(changed))
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "Mixed invocation"):
                shards.aggregate(self.root, "a" * 40, "11", "2")


if __name__ == "__main__":
    unittest.main()
