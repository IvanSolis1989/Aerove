"""Guard Lite141 prebuilt diagnostics against source mixing and acceptance promotion."""
import json
import fnmatch
import os
from pathlib import Path
import re
import tempfile
import unittest
from unittest import mock

import emulator_original84 as original
import instrumentation_shards as shards
import lite141_shards_diagnostic as diagnostic
import prepare_lite141_smart_fixture as smart_adapter


class Lite141DiagnosticTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.harness = dict(sourceRevision="a" * 40, sourceTree="b" * 40,
                            workflowRun="500", runNumber=200, runAttempt=2)

    def test_target_is_fixed_original_run_and_not_a_current_build(self):
        spec = original.runtime_spec(diagnostic.TARGET)
        self.assertEqual("d139a59b13275c9c2b207d0dc936fd37d93249b3", spec["identity"]["sourceRevision"])
        self.assertEqual(141, spec["identity"]["runNumber"])
        self.assertEqual(1, spec["identity"]["runAttempt"])
        self.assertEqual(12708203167, spec["artifact"])
        self.assertEqual(423, diagnostic.EXPECTED_DISCOVERY_COUNT)
        self.assertEqual(423, sum(diagnostic.EXPECTED_SHARD_COUNTS))
        self.assertEqual("c3c23172377c5f6e26dae701fec6fbf586c846290941a79fdd1c426bf2efa3d7",
                         diagnostic.EXPECTED_DISCOVERY_FINGERPRINT)

    def _proof_fixture(self):
        root = self.root / "artifact"
        (root / "remaining").mkdir(parents=True)
        spec = original.runtime_spec(diagnostic.TARGET)
        dependency = root / "remaining/runtime-androidTest-dependencies.txt"
        dependency.write_text("androidx.test:runner:1.6.2 -> 1.7.0\njunit:junit:4.13.2\n")
        import hashlib
        dep_sha = hashlib.sha256(dependency.read_bytes()).hexdigest()
        manifest = {"schema": 1, **spec["identity"], "files": [
            {"path": "dependencies/releaseAndroidTestRuntimeClasspath.stdout.txt", "sha256": dep_sha}]}
        manifest_bytes = json.dumps(manifest).encode()
        manifest_sha = hashlib.sha256(manifest_bytes).hexdigest()
        (root / "remaining/runtime-source-manifest.json").write_bytes(manifest_bytes)
        proof = dict(originalAppIdentity=spec["identity"], harnessIdentity=self.harness,
            artifactId=spec["artifact"], artifactZipSha256=spec["zipSha256"],
            runtimeManifestSha256=manifest_sha, apks=spec["apks"], **diagnostic.DIAGNOSTIC_FLAGS)
        proof_bytes = json.dumps(proof).encode()
        (root / "remaining/ORIGINAL-RUNTIME-APP-PROOF.json").write_bytes(proof_bytes)
        (root / "remaining/APK_SHA256SUMS").write_text("\n".join(
            f"{value}  {name}" for name, value in spec["apks"].items()) + "\n")
        source = dict(originalAppIdentity=spec["identity"], sourceRevision=spec["identity"]["sourceRevision"],
            sourceTree=spec["identity"]["sourceTree"], workflowRun=spec["identity"]["workflowRun"],
            runNumber=spec["identity"]["runNumber"], runAttempt=spec["identity"]["runAttempt"],
            harnessIdentity=self.harness,
            runtimeManifestSha256=manifest_sha, originalRuntimeProofSha256=hashlib.sha256(proof_bytes).hexdigest(),
            runtimeArtifactId=spec["artifact"], runtimeArtifactZipSha256=spec["zipSha256"],
            apks=spec["apks"], androidTestDependencyReportSha256=dep_sha, **diagnostic.DIAGNOSTIC_FLAGS)
        fixture_spec = dict(spec, manifestSha256=manifest_sha)
        return root, source, fixture_spec

    def test_original_proof_requires_both_exact_identities_and_false_promotion(self):
        root, source, fixture_spec = self._proof_fixture()
        with mock.patch.object(diagnostic, "APP", fixture_spec):
            self.assertEqual(fixture_spec["manifestSha256"],
                             diagnostic._verify_original_proof(root, source, self.harness)[1])
            for field, bad in (("sourceRevision", self.harness["sourceRevision"]),
                               ("runAttempt", 2), ("accepted", True), ("aggregateAcceptance", True)):
                altered = dict(source)
                altered[field] = bad
                with self.subTest(field=field), self.assertRaises(ValueError):
                    diagnostic._verify_original_proof(root, altered, self.harness)

    def test_actual_run141_string_discovery_identity_matches_without_retyping_app_metadata(self):
        actual = dict(sourceRevision="d139a59b13275c9c2b207d0dc936fd37d93249b3",
            sourceTree="84e394165682384167a55f1bed04f1c0bd47b648",
            workflowRun="40445440882", runAttempt="1", runNumber="141")
        self.assertEqual("int", type(diagnostic.APP["identity"]["runNumber"]).__name__)
        self.assertEqual("int", type(diagnostic.APP["identity"]["runAttempt"]).__name__)
        self.assertTrue(diagnostic.discovery_identity_matches(actual))
        self.assertFalse(diagnostic.discovery_identity_matches(dict(actual, runNumber="142")))
        mixed_types = dict(actual, runNumber=141, runAttempt=1)
        self.assertFalse(diagnostic.discovery_identity_matches(mixed_types))
        self.assertEqual("141", actual["runNumber"])
        self.assertEqual("1", actual["runAttempt"])

    def test_missing_or_misassigned_shard_cannot_be_aggregated(self):
        descriptions = [f"case{i}(dev.aerove.lite.SampleTest)" for i in range(40)]
        discovery = dict(numShards=8, shardIndex=0, discoveredDescriptions=sorted(descriptions),
                         discoveryFingerprint=shards.fingerprint(sorted(descriptions)))
        plan = shards.split_plan(discovery)
        self.assertEqual(sorted(value for value in descriptions if shards.shard_for(value) == 0), plan["ordinary"])
        with mock.patch.object(diagnostic, "current_harness_identity", return_value=self.harness), \
                self.assertRaises(ValueError):
            diagnostic.verify_shards(self.root, self.root / "aggregate.json")

    def test_workflow_routes_diagnostic_target_away_from_product_acceptance(self):
        workflow = (Path(__file__).parents[3] / ".github/workflows/aerove-lite.yaml").read_text()
        self.assertIn("inputs.diagnostic_target == 'lite141-prebuilt-shards'", workflow)
        self.assertIn("diagnostic_lite141_shards", workflow)
        self.assertIn("aggregateAcceptance=False", Path(__file__).with_name("lite141_shards_diagnostic.py").read_text())
        self.assertIn("inputs.diagnostic_target == 'none'", workflow)
        self.assertNotIn("needs.diagnostic_lite141_aggregate", workflow.split("  accepted:", 1)[-1])

    def test_smart_fixture_adapter_pins_the_original_app_owner_before_materializing(self):
        args = type("Args", (), dict(app_repository=self.root, smart_repository=self.root,
            geo_cache=self.root / "geo", output=self.root / "fixture", receipt=self.root / "receipt.json",
            download=True))()
        app = original.runtime_spec(diagnostic.TARGET)
        git_values = iter((app["identity"]["sourceRevision"], app["identity"]["sourceTree"],
                           "d7ba10c398eaf3789aff605ad8a6e418cbf67707"))
        provider_rows = []
        files = {"script.js": b"pinned fixture script"}
        import hashlib
        for index in range(153):
            name = f"provider-{index}.yaml"
            payload = f"provider {index}".encode()
            files[name] = payload
            provider_rows.append(dict(sourcePath=f"provider/{name}", file=name,
                sha256=hashlib.sha256(payload).hexdigest(), bytes=len(payload)))
        files["manifest.json"] = json.dumps({"providers": provider_rows}).encode()
        with mock.patch.object(smart_adapter, "git", side_effect=lambda *_: next(git_values)), \
                mock.patch.object(smart_adapter.subprocess, "check_output", return_value=b"pinned ASN"), \
                mock.patch.object(smart_adapter.fixture, "verified_bytes", return_value=b"pinned ASN"), \
                mock.patch.object(smart_adapter.fixture, "acquire_geo", side_effect=lambda spec, *_: (self.root / spec["file"], dict(spec))), \
                mock.patch.object(smart_adapter.fixture, "prepare"), \
                mock.patch.object(smart_adapter.fixture, "load_verified_fixture", return_value=files), \
                mock.patch.object(smart_adapter.fixture, "verify_receipt", return_value=dict(
                    smartCommit="dba12215fe8f8d139a94fbd2d5b981282fbcf9c7",
                    smartTree="d7ba10c398eaf3789aff605ad8a6e418cbf67707",
                    fixtureManifestSha256="234442db6cd3be995e4d5b323f50a360ff2c846b37e9cbd1e742b3f7833ab108",
                    providers=[{}] * 153)) as verify:
            result = smart_adapter.prepare(args)
        self.assertEqual(app["identity"]["sourceRevision"], result["appSourceRevision"])
        self.assertEqual(app["identity"]["sourceTree"], result["appSourceTree"])
        self.assertEqual("dba12215fe8f8d139a94fbd2d5b981282fbcf9c7", result["smartCommit"])
        self.assertEqual("d7ba10c398eaf3789aff605ad8a6e418cbf67707", result["smartTree"])
        self.assertEqual(153, len(result["providers"]))
        verify.assert_called_once_with(args.receipt, app["identity"]["sourceRevision"], app["identity"]["sourceTree"])

    def test_only_lite141_original_uses_the_frozen_dba5_fixture_owner(self):
        shell = Path(__file__).with_name("emulator_acceptance.sh").read_text()
        self.assertIn('"$smart_fixture_target" == lite141-prebuilt-shards', shell)
        self.assertIn('smart_fixture_owner=dba5', shell)
        self.assertIn('smart_fixture_verifier=prepare_smart_config_meow_dba5_ci_fixture.py', shell)
        self.assertEqual('prepare_smart_config_meow_dba5_ci_fixture', smart_adapter.fixture.__name__)
        self.assertIn('smart_fixture_verifier=prepare_smart_config_meow_ci_fixture.py', shell)
        self.assertIn('smart_fixture_owner=current', shell)
        self.assertIn('smart_fixture_owner=legacy', shell)
        self.assertIn('--smart-config-fixture-owner "$smart_fixture_owner"', shell)
        self.assertEqual("dba12215fe8f8d139a94fbd2d5b981282fbcf9c7", smart_adapter.SMART_COMMIT)
        self.assertEqual("234442db6cd3be995e4d5b323f50a360ff2c846b37e9cbd1e742b3f7833ab108",
                         smart_adapter.SMART_MANIFEST_SHA256)

    def test_frozen_dba5_adapter_rejects_the_current_397_producer(self):
        import prepare_smart_config_meow_ci_fixture as current
        args = type("Args", (), dict(app_repository=self.root, smart_repository=self.root))()
        self.assertNotEqual(smart_adapter.SMART_COMMIT, current.COMMIT)
        with mock.patch.object(smart_adapter, "fixture", current), self.assertRaisesRegex(ValueError, "fixed dba5 contract"):
            smart_adapter.prepare(args)

    def test_actual_frozen_smart_receipt_verifier_accepts_dba5_153_and_rejects_legacy_149(self):
        fixture = smart_adapter.fixture
        source = "d139a59b13275c9c2b207d0dc936fd37d93249b3"
        tree = "84e394165682384167a55f1bed04f1c0bd47b648"
        receipt = dict(schema=1, appSourceRevision=source, appSourceTree=tree,
            smartCommit="dba12215fe8f8d139a94fbd2d5b981282fbcf9c7",
            smartTree="d7ba10c398eaf3789aff605ad8a6e418cbf67707",
            fixtureManifestSha256="234442db6cd3be995e4d5b323f50a360ff2c846b37e9cbd1e742b3f7833ab108",
            fixtureFiles=158, fixtureDataBytes=27364030,
            script=dict(sha256=fixture.SCRIPT_SHA),
            providers=[{} for _ in range(153)],
            geo=[dict(spec, acquisition="verified-cache") for spec in fixture.PINNED_GEO.values()],
            asn=dict(fixture.ASN, sourcePath=fixture.ASN_PATH, sourceRevision=source))
        path = self.root / "current-dba5-receipt.json"
        path.write_text(json.dumps(receipt))
        verified = fixture.verify_receipt(path, source, tree)
        self.assertEqual(153, len(verified["providers"]))
        receipt["smartCommit"] = "89abb221a157acc98d6b52c85583e6b5a5b2742b"
        receipt["smartTree"] = "854ec41132fc0bbc47330acfb7acd6911c16673c"
        receipt["fixtureManifestSha256"] = "cdf3e8c05f59555b2ef8f711aafa212d963160779e9a1eb1592fd038269fc70b"
        receipt["fixtureFiles"] = 154
        receipt["providers"] = [{} for _ in range(149)]
        path.write_text(json.dumps(receipt))
        with self.assertRaisesRegex(ValueError, "Fixture receipt differs"):
            fixture.verify_receipt(path, source, tree)

    def test_eight_uploaded_artifact_names_match_the_current_attempt_download_pattern(self):
        qa = (Path(__file__).parents[3] / ".github/workflows/aerove-lite-qa84.yaml").read_text()
        root_workflow = (Path(__file__).parents[3] / ".github/workflows/aerove-lite.yaml").read_text()
        upload_line = re.search(r"^        name: (.+)$", qa, re.M).group(1)
        template = "format('AEROVE-Lite-original141-shard-{0}-of-8-attempt-{1}', inputs.shard_index, github.run_attempt)"
        self.assertIn(template, upload_line)
        self.assertNotIn("inputs.runtime_target == 'lite141-prebuilt-shards' && ''", upload_line)
        self.assertIn("format('AEROVE-Lite-beta14-profile-matrix-{0}', github.run_attempt)", upload_line)
        self.assertIn("format('AEROVE-Lite-diagnostic94-matrix-{0}', github.run_attempt)", upload_line)
        self.assertIn("format('AEROVE-Lite-QA84-matrix-{0}', github.run_attempt)", upload_line)
        attempt = "2"
        names = [f"AEROVE-Lite-original141-shard-{index}-of-8-attempt-{attempt}" for index in range(8)]
        pattern = f"AEROVE-Lite-original141-shard-*-of-8-attempt-{attempt}"
        self.assertEqual(8, len(set(names)))
        self.assertTrue(all(fnmatch.fnmatchcase(name, pattern) for name in names))
        self.assertIn(f"pattern: {pattern.replace(attempt, '${{ github.run_attempt }}')}", root_workflow)


if __name__ == "__main__":
    unittest.main()
