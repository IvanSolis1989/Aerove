"""Aggregate Lite141 original-APK shard diagnostics without product acceptance."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile

import instrumentation_shards as shards
import emulator_original84 as original

TARGET = "lite141-prebuilt-shards"
APP = original.runtime_spec(TARGET)
EXPECTED_DISCOVERY_COUNT = 423
EXPECTED_DISCOVERY_FINGERPRINT = "c3c23172377c5f6e26dae701fec6fbf586c846290941a79fdd1c426bf2efa3d7"
EXPECTED_SHARD_COUNTS = (40, 54, 59, 61, 48, 65, 44, 52)
DIAGNOSTIC_FLAGS = {
    "diagnosticOriginalRuntime": True,
    "runtimeTarget": TARGET,
    "diagnosticOnly": True,
    "accepted": False,
    "promotionAccepted": False,
    "aggregateAcceptance": False,
}


def require(value, message):
    if not value:
        raise ValueError(message)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def discovery_identity_matches(actual):
    expected = {key: str(value) for key, value in APP["identity"].items()}
    return isinstance(actual, dict) and actual == expected


def read_json(path):
    path = Path(path)
    require(not path.is_symlink() and path.is_file(), f"Required receipt missing or symlinked: {path.name}")
    raw = path.read_bytes()
    return json.loads(raw), hashlib.sha256(raw).hexdigest()


def current_harness_identity():
    import ci_artifacts
    identity = ci_artifacts.identity()
    require(identity.get("sourceRevision") == os.environ.get("GITHUB_SHA"), "Harness SHA differs from workflow source")
    return identity


def _verify_original_proof(root, source, harness):
    proof, proof_sha = read_json(root / "remaining/ORIGINAL-RUNTIME-APP-PROOF.json")
    manifest, manifest_sha = read_json(root / "remaining/runtime-source-manifest.json")
    dependency = root / "remaining/runtime-androidTest-dependencies.txt"
    require(not dependency.is_symlink() and dependency.is_file(), "Original dependency proof is missing or symlinked")
    dependency_sha = digest(dependency)
    expected = APP["identity"]
    require(source.get("originalAppIdentity") == expected and source.get("sourceRevision") == expected["sourceRevision"]
            and source.get("sourceTree") == expected["sourceTree"] and str(source.get("workflowRun")) == expected["workflowRun"]
            and source.get("runNumber") == expected["runNumber"] and source.get("runAttempt") == expected["runAttempt"],
            "Shard SOURCE no longer identifies the exact original App run141")
    require(source.get("harnessIdentity") == harness, "Shard belongs to another harness source/run/attempt")
    require(all(source.get(key) == value for key, value in DIAGNOSTIC_FLAGS.items() if key != "runtimeTarget")
            and source.get("runtimeTarget") == TARGET and source.get("diagnosticOriginalRuntime") is True,
            "Shard SOURCE is not diagnostic-only")
    require(proof.get("originalAppIdentity") == expected and proof.get("harnessIdentity") == harness
            and proof.get("artifactId") == APP["artifact"]
            and proof.get("artifactZipSha256") == APP["zipSha256"]
            and proof.get("runtimeManifestSha256") == APP["manifestSha256"]
            and proof.get("apks") == APP["apks"]
            and all(proof.get(key) == value for key, value in DIAGNOSTIC_FLAGS.items()),
            "Original runtime proof differs from the fixed run141 package or diagnostic contract")
    require(manifest_sha == APP["manifestSha256"] and source.get("runtimeManifestSha256") == manifest_sha
            and proof_sha == source.get("originalRuntimeProofSha256")
            and source.get("runtimeArtifactId") == APP["artifact"]
            and source.get("runtimeArtifactZipSha256") == APP["zipSha256"]
            and source.get("apks") == APP["apks"]
            and source.get("androidTestDependencyReportSha256") == dependency_sha,
            "Copied runtime proof, source manifest, artifact or dependency report changed")
    files = {entry.get("path"): entry.get("sha256") for entry in manifest.get("files", [])}
    dep_path = "dependencies/releaseAndroidTestRuntimeClasspath.stdout.txt"
    require(files.get(dep_path) == dependency_sha, "AndroidTest dependency report is not source-manifest bound")
    text = dependency.read_text(encoding="utf-8", errors="strict")
    require(re.search(r"androidx\.test:runner:1\.6\.2\s+->\s+1\.7\.0", text)
            and re.search(r"junit:junit:4\.13\.2(?:\s|$)", text),
            "Original runner/JUnit resolution differs from the verified run141 runtime")
    sums = root / "remaining/APK_SHA256SUMS"
    require(not sums.is_symlink() and sums.is_file(), "APK checksum receipt missing")
    parsed = {}
    for line in sums.read_text(encoding="utf-8", errors="strict").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})\s+\*?(.+)", line)
        require(match is not None, "Malformed APK checksum receipt")
        name = Path(match.group(2)).name
        require(name not in parsed, "Duplicate APK checksum entry")
        parsed[name] = match.group(1)
    require(parsed == APP["apks"], "Installed original APK checksums differ from run141 artifact")
    return proof_sha, manifest_sha, dependency_sha


def verify_shards(directory, output):
    harness = current_harness_identity()
    attempt = str(harness["runAttempt"])
    root = Path(directory)
    require(root.is_dir() and not root.is_symlink(), "Shard artifact root missing or symlinked")
    stage = None
    artifact_names = []
    receipts = []
    common = None
    try:
        with tempfile.TemporaryDirectory(prefix="lite141-diagnostic-aggregate-") as temp:
            stage = Path(temp)
            for index in range(8):
                name = f"AEROVE-Lite-original141-shard-{index}-of-8-attempt-{attempt}"
                artifact_names.append(name)
                artifact_root = root / name
                require(artifact_root.is_dir() and not artifact_root.is_symlink(), f"Missing current-attempt shard {index}")
                source, source_sha = read_json(artifact_root / "remaining/SOURCE.json")
                proof_sha, manifest_sha, dependency_sha = _verify_original_proof(artifact_root, source, harness)
                renderer, renderer_sha = read_json(artifact_root / "remaining/CURRENT-RENDERER-PROOF.json")
                require(renderer.get("owner") == "emulator_current.py"
                        and renderer.get("identity") == APP["identity"]
                        and renderer.get("originalAppIdentity") == APP["identity"]
                        and renderer.get("harnessIdentity") == harness
                        and renderer.get("runtimeTarget") == TARGET
                        and renderer.get("runtimeManifestSha256") == APP["manifestSha256"]
                        and renderer.get("apks") == APP["apks"],
                        "Renderer receipt is not bound to the original App and current harness")
                discovery, discovery_sha = read_json(artifact_root / "remaining/TEST-DISCOVERY.json")
                selection, _ = read_json(artifact_root / "remaining/TEST-SELECTION.json")
                require(discovery_identity_matches(discovery.get("identity"))
                        and discovery.get("apkSha256") == APP["apks"]
                        and discovery.get("runner") == "androidx.test:runner:1.7.0"
                        and discovery.get("junit") == "junit:junit:4.13.2"
                        and discovery.get("runtimeManifestSha256") == manifest_sha
                        and discovery.get("dependencyReportSha256") == dependency_sha
                        and discovery.get("discoveryCount") == EXPECTED_DISCOVERY_COUNT
                        and discovery.get("discoveryFingerprint") == EXPECTED_DISCOVERY_FINGERPRINT
                        and discovery.get("phase") == "remaining" and discovery.get("numShards") == 8
                        and discovery.get("shardIndex") == index
                        and discovery.get("discoveryMode") == "logOnly-no-test-method-execution"
                        and discovery.get("passedTests") == [],
                        f"Shard {index} discovery does not match fixed run141 APK/runner/partition")
                require(selection.get("discovery") == discovery
                        and selection.get("allExpectedAccounted") is True
                        and selection.get("allExpectedPassed") is True
                        and selection.get("aggregateAcceptance") is False,
                        f"Shard {index} did not produce exact successful terminal accounting")
                plan = shards.split_plan(discovery)
                expected = EXPECTED_SHARD_COUNTS[index]
                require(len(plan["ordinary"]) + len(plan["isolated"]) == expected
                        and len(discovery.get("discoveredDescriptions", [])) == EXPECTED_DISCOVERY_COUNT,
                        f"Shard {index} exact expected description count changed")
                require((index == 1 and plan["isolated"] == [shards.SSID_DESCRIPTION])
                        or (index != 1 and plan["isolated"] == []),
                        "SSID method is not isolated in its actual discovered shard")
                selection_sha = digest(artifact_root / "remaining/TEST-SELECTION.json")
                shared = (source.get("runtimeManifestSha256"), source.get("apks"),
                          discovery.get("discoveryFingerprint"), discovery.get("discoveredDescriptions"),
                          source.get("harnessIdentity"))
                require(common is None or shared == common, "Shards mix App, APK, runner discovery or harness identity")
                common = shared
                expected_receipt = stage / f"AEROVE-Lite-shard-selection-{index}-of-8-attempt-{APP['identity']['runAttempt']}"
                expected_receipt.mkdir()
                (expected_receipt / "TEST-SELECTION.json").write_bytes((artifact_root / "remaining/TEST-SELECTION.json").read_bytes())
                receipts.append(dict(shardIndex=index, artifact=name, sourceSha256=source_sha,
                    rendererProofSha256=renderer_sha, discoverySha256=discovery_sha,
                    selectionSha256=selection_sha, originalRuntimeProofSha256=proof_sha,
                    runtimeManifestSha256=manifest_sha, dependencyReportSha256=dependency_sha))
            require(sorted(path.name for path in root.iterdir() if path.is_dir()) == sorted(artifact_names),
                    "Unexpected or duplicate shard artifact directories")
            raw_aggregate = shards.aggregate(stage, APP["identity"]["sourceRevision"],
                APP["identity"]["workflowRun"], str(APP["identity"]["runAttempt"]))
        result = dict(schema=1, kind="Lite141 prebuilt original APK shard diagnostic",
            originalAppIdentity=APP["identity"], harnessIdentity=harness,
            runtimeArtifactId=APP["artifact"], runtimeArtifactZipSha256=APP["zipSha256"],
            runtimeManifestSha256=APP["manifestSha256"], apks=APP["apks"], shards=receipts,
            allExpectedAccounted=raw_aggregate["allDiscoveredAccounted"],
            allExpectedPassed=raw_aggregate["allDiscoveredPassed"],
            aggregateAcceptance=False, accepted=False, promotionAccepted=False, diagnosticOnly=True,
            performanceEvidence=False, aggregate=raw_aggregate)
        Path(output).write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        require(result["allExpectedAccounted"] and result["allExpectedPassed"],
                "One or more original APK shard tests failed, were ignored or unaccounted")
        return result
    finally:
        if stage is not None:
            # TemporaryDirectory owns only this process's isolated staging directory.
            pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    aggregate = sub.add_parser("aggregate")
    aggregate.add_argument("--directory", type=Path, required=True)
    aggregate.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        verify_shards(args.directory, args.output)
    except Exception as error:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        failed = dict(schema=1, kind="Lite141 prebuilt original APK shard diagnostic",
            diagnosticOnly=True, accepted=False, promotionAccepted=False, aggregateAcceptance=False,
            allExpectedAccounted=False, allExpectedPassed=False, failureType=type(error).__name__,
            failure=str(error))
        args.output.write_text(json.dumps(failed, indent=2) + "\n", encoding="utf-8")
        raise


if __name__ == "__main__":
    main()
