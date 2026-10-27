"""Bind eight verified instrumentation shards to APK log-only discovery and terminal statuses."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re

MATRIX_CLASS = "dev.aerove.lite.ui.LiteDashboardMatrixTest"
SSID_CLASS = "dev.aerove.lite.backgroundpolicy.OnDemandRuntimeDeviceTest"
SSID_METHOD = "excludedRealWifiParksAndResumesProxyAndTunWithoutReplacingCore"
SSID_SELECTOR = SSID_CLASS + "#" + SSID_METHOD
SSID_DESCRIPTION = SSID_METHOD + "(" + SSID_CLASS + ")"
STATUSES = {0: "passed", -3: "ignored", -4: "assumptionFailed"}
IDENTITY = ("sourceRevision", "sourceTree", "workflowRun", "runAttempt", "runNumber")
SHARD_COUNT = 8


def require(condition, message):
    if not condition:
        raise ValueError(message)


VERSION_CONTRACT = Path(__file__).with_name("instrumentation-version-contract.json")


def load_version_contract(path=VERSION_CONTRACT):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    expected_coordinates = {"androidx.test:runner", "junit:junit"}
    require(type(data) is dict and set(data) == {"schemaVersion", "coordinates"}
            and type(data.get("schemaVersion")) is int and data["schemaVersion"] == 1,
            "Invalid instrumentation version contract schema")
    coordinates = data.get("coordinates")
    require(type(coordinates) is dict and set(coordinates) == expected_coordinates,
            "Invalid instrumentation version contract coordinates")
    supported = {}
    for coordinate, policy in coordinates.items():
        versions = policy.get("supportedResolvedVersions") if type(policy) is dict else None
        require(type(policy) is dict and set(policy) == {"supportedResolvedVersions"}
                and type(versions) is list and bool(versions)
                and all(type(version) is str and bool(version) for version in versions)
                and len(versions) == len(set(versions)),
                f"Invalid supported versions for {coordinate}")
        supported[coordinate] = frozenset(versions)
    return supported


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=True).encode()).hexdigest()


def java_hash(value):
    encoded = value.encode("utf-16-be", errors="surrogatepass")
    result = 0
    for index in range(0, len(encoded), 2):
        result = (31 * result + int.from_bytes(encoded[index:index + 2], "big")) & 0xffffffff
    return result if result < 2**31 else result - 2**32


def shard_for(description):
    value = java_hash(description)
    require(value != -(2**31), "Runner Math.abs MIN_VALUE description requires explicit review")
    return abs(value) % SHARD_COUNT


def parse_log(path):
    text = Path(path).read_text(encoding="utf-8", errors="strict")
    require(re.findall(r"^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$", text, re.M) == ["-1"], "Incomplete instrumentation terminal")
    require(not re.search(r"INSTRUMENTATION_(?:FAILED|ABORTED)|FAILURES!!!", text), "Instrumentation reported failure")
    fields, records, active = {}, [], None
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if separator:
                require(key not in fields, "Duplicate status field")
                fields[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.split(": ", 1)[1])
            require(code in (1, 0, -3, -4), f"Failed or unknown test status {code}")
            require(fields.get("id") == "AndroidJUnitRunner", "Unexpected instrumentation reporter")
            require(all(fields.get(key) for key in ("class", "test", "numtests", "current")), "Missing test identity/count")
            identity = f"{fields['test']}({fields['class']})"
            require(fields["class"] != MATRIX_CLASS, "Discovery/run includes excluded matrix class")
            record = dict(description=identity, className=fields["class"], testName=fields["test"],
                          current=int(fields["current"]), total=int(fields["numtests"]))
            if code == 1:
                require(active is None, "Overlapping test starts")
                active = record
            else:
                require(active == record, "Missing/mismatched test start or terminal")
                records.append(dict(record, status=STATUSES[code]))
                active = None
            fields = {}
    require(active is None and not fields and bool(records), "Unfinished/empty test discovery or execution")
    require([r["current"] for r in records] == list(range(1, len(records) + 1)), "Non-contiguous test status sequence")
    require({r["total"] for r in records} == {len(records)}, "Incomplete test count")
    require(len({r["description"] for r in records}) == len(records), "Duplicate test description")
    return records


def discover(log, proof_path, source_path, sums_path):
    source = json.loads(Path(source_path).read_text())
    shard = source.get("testShard") or {}
    require(shard.get("numShards") == SHARD_COUNT and type(shard.get("shardIndex")) is int and shard["shardIndex"] in range(SHARD_COUNT), "Invalid source shard")
    require(source.get("phase") == "remaining" and shard.get("excludedClass") == MATRIX_CLASS
            and shard.get("runner") == "androidx.test.runner.AndroidJUnitRunner", "Invalid source selection")
    proof = json.loads(Path(proof_path).read_text())
    runtime = Path(proof["runtimeDirectory"])
    manifest_path = runtime / "source-manifest.json"
    manifest = json.loads(manifest_path.read_text())
    identity = {key: str(source[key]) for key in IDENTITY}
    require(identity == {key: str(manifest[key]) for key in IDENTITY}, "Discovery source differs from verified runtime")
    report_relative = "dependencies/releaseAndroidTestRuntimeClasspath.stdout.txt"
    report = runtime / report_relative
    entries = [entry for entry in manifest["files"] if entry["path"] == report_relative]
    require(len(entries) == 1 and entries[0]["sha256"] == sha(report), "Dependency report is not source-bound")
    require((runtime / "dependencies/releaseAndroidTestRuntimeClasspath.exit.txt").read_text().strip() == "0", "Dependency graph did not resolve")
    graph = report.read_text()
    resolved_versions = {}
    for coordinate, supported in load_version_contract().items():
        resolved = {target or requested for requested, target in re.findall(re.escape(coordinate) + r":([^\s]+)(?: -> ([^\s]+))?", graph)}
        require(len(resolved) == 1 and resolved.issubset(supported), f"Unsupported resolved {coordinate}: {resolved}")
        resolved_versions[coordinate] = resolved.pop()
    records = parse_log(log)
    # Log-only reports synthetic success: these identities are discovered, never passed tests.
    descriptions = sorted(record["description"] for record in records)
    for description in descriptions:
        shard_for(description)
    sums = Path(sums_path).read_text()
    apk_hashes = {}
    for digest, path in re.findall(r"^([0-9a-f]{64})\s+(.+)$", sums, re.M):
        name = Path(path).name
        require(name not in apk_hashes, "Duplicate APK checksum")
        apk_hashes[name] = digest
    require(apk_hashes == proof.get("apks"), "Discovery APKs differ from renderer proof")
    return dict(schema=1, phase="remaining", numShards=SHARD_COUNT, shardIndex=shard["shardIndex"], identity=identity,
                runner=f"androidx.test:runner:{resolved_versions['androidx.test:runner']}",
                junit=f"junit:junit:{resolved_versions['junit:junit']}", algorithm=f"abs(Description.hashCode()) % {SHARD_COUNT}",
                apkSha256=apk_hashes, runtimeManifestSha256=sha(manifest_path), dependencyReportSha256=sha(report),
                discoveryLogSha256=sha(log), discoveryMode="logOnly-no-test-method-execution",
                discoveredDescriptions=descriptions, discoveryFingerprint=fingerprint(descriptions),
                discoveryCount=len(descriptions), excludedClass=MATRIX_CLASS, passedTests=[])


def split_plan(discovery):
    require(discovery.get("numShards") == SHARD_COUNT and type(discovery.get("shardIndex")) is int and discovery["shardIndex"] in range(SHARD_COUNT), "Invalid discovery shard")
    descriptions = discovery["discoveredDescriptions"]
    require(descriptions == sorted(set(descriptions)) and discovery["discoveryFingerprint"] == fingerprint(descriptions), "Invalid discovery fingerprint")
    expected = sorted(value for value in descriptions if shard_for(value) == discovery["shardIndex"])
    isolated = [value for value in expected if value == SSID_DESCRIPTION]
    ordinary = [value for value in expected if value != SSID_DESCRIPTION]
    require(bool(ordinary), "A shard requires an ordinary invocation")
    return dict(ordinary=ordinary, isolated=isolated, isolatedSelector=SSID_SELECTOR if isolated else None,
                discoveryFingerprint=discovery["discoveryFingerprint"], discoveryCount=len(descriptions))


def verify_ordinary(discovery, log):
    plan = split_plan(discovery)
    records = parse_log(log)
    require(sorted(record["description"] for record in records) == plan["ordinary"],
            "Ordinary invocation differs from its exact discovered selection")
    require(all(record["status"] == "passed" for record in records),
            "Ordinary invocation must pass every test before device preparation")
    require(re.findall(r"OK \((\d+) tests?\)", Path(log).read_text()) == [str(len(records))],
            "Ordinary invocation requires one exact passing summary")
    return records


def verify(discovery, log, isolated_log=None):
    plan = split_plan(discovery)
    records = parse_log(log)
    require(sorted(record["description"] for record in records) == plan["ordinary"] if isolated_log is not None else
            sorted(record["description"] for record in records) == sorted(plan["ordinary"] + plan["isolated"]),
            "Actual shard terminal/start set differs from APK discovery selection")
    logs = [dict(role="ordinary", sha256=sha(log), descriptions=sorted(record["description"] for record in records),
                 identity=discovery["identity"], apkSha256=discovery["apkSha256"])]
    if isolated_log is not None:
        require(plan["isolated"] == [SSID_DESCRIPTION], "Isolated invocation belongs to another shard")
        isolated = parse_log(isolated_log)
        require([record["description"] for record in isolated] == plan["isolated"], "Isolated terminal/start set must be exactly the discovered SSID method")
        records += isolated
        logs += [dict(role="ssid-scoped", sha256=sha(isolated_log), descriptions=plan["isolated"],
                      identity=discovery["identity"], apkSha256=discovery["apkSha256"])]
    else:
        require(not plan["isolated"], "The real-Wi-Fi method requires its separate scoped host invocation")
    expected = sorted(plan["ordinary"] + plan["isolated"])
    actual = sorted(record["description"] for record in records)
    require(actual == expected and len(actual) == len(set(actual)), "Split shard union is incomplete or duplicated")
    statuses = {status: sorted(record["description"] for record in records if record["status"] == status) for status in STATUSES.values()}
    return dict(schema=1, discovery=discovery, expectedDescriptions=expected, actualDescriptions=actual,
                terminalStatuses=statuses, instrumentationLogSha256=sha(log), instrumentationLogs=logs,
                allExpectedAccounted=True, allExpectedPassed=len(statuses["passed"]) == len(expected), aggregateAcceptance=False)


def aggregate(directory, source, run, attempt):
    root = Path(directory)
    expected_paths = [root / f"AEROVE-Lite-shard-selection-{i}-of-{SHARD_COUNT}-attempt-{attempt}" / "TEST-SELECTION.json" for i in range(SHARD_COUNT)]
    require(set(root.rglob("TEST-SELECTION.json")) == set(expected_paths), f"{SHARD_COUNT} unique shard artifacts are required")
    receipts = [json.loads(path.read_text()) for path in expected_paths]
    first = receipts[0]["discovery"]
    comparison = ("identity", "runner", "junit", "algorithm", "apkSha256", "runtimeManifestSha256",
                  "dependencyReportSha256", "discoveryFingerprint", "discoveredDescriptions", "discoveryCount", "excludedClass")
    combined, passed, ignored, assumptions = [], [], [], []
    for index, receipt in enumerate(receipts):
        discovery = receipt["discovery"]
        require(discovery["numShards"] == SHARD_COUNT and discovery["shardIndex"] == index, "Wrong shard artifact identity")
        require(all(discovery[key] == first[key] for key in comparison), "Mixed APK/source/discovery across shards")
        require(discovery["identity"]["sourceRevision"] == source and discovery["identity"]["workflowRun"] == str(run)
                and discovery["identity"]["runAttempt"] == str(attempt), "Shard belongs to another source/run/attempt")
        descriptions = discovery["discoveredDescriptions"]
        require(descriptions == sorted(set(descriptions)) and discovery["discoveryFingerprint"] == fingerprint(descriptions), "Invalid discovery set")
        expected = sorted(description for description in descriptions if shard_for(description) == index)
        require(receipt["allExpectedAccounted"] is True and receipt["expectedDescriptions"] == expected
                and receipt["actualDescriptions"] == expected, "Incomplete shard receipt")
        plan = split_plan(discovery)
        logs = receipt.get("instrumentationLogs", [])
        require([entry.get("role") for entry in logs] == (["ordinary", "ssid-scoped"] if plan["isolated"] else ["ordinary"]), "Wrong ordinary/scoped invocation accounting")
        for entry, selected in zip(logs, [plan["ordinary"], plan["isolated"]]):
            require(entry.get("identity") == discovery["identity"] and entry.get("apkSha256") == discovery["apkSha256"]
                    and entry.get("descriptions") == selected and re.fullmatch("[0-9a-f]{64}", entry.get("sha256", "")), "Mixed invocation source/APK/selection")
        statuses = receipt["terminalStatuses"]
        require(set(statuses) == set(STATUSES.values()), "Unknown terminal classification")
        require(Counter(item for items in statuses.values() for item in items) == Counter(expected), "Terminal accounting is incomplete or duplicated")
        require(receipt["allExpectedPassed"] == (len(statuses["passed"]) == len(expected)), "Skipped test represented as passed")
        combined.extend(receipt["actualDescriptions"])
        passed.extend(statuses["passed"])
        ignored.extend(statuses["ignored"])
        assumptions.extend(statuses["assumptionFailed"])
    require(Counter(combined) == Counter(first["discoveredDescriptions"]), "Shard union differs from complete APK discovery")
    return dict(schema=1, identity=first["identity"], apkSha256=first["apkSha256"], discoveryFingerprint=first["discoveryFingerprint"],
                discoveredCount=len(combined), passedCount=len(passed), ignoredTests=sorted(ignored), assumptionFailedTests=sorted(assumptions),
                allDiscoveredAccounted=True, allDiscoveredPassed=not ignored and not assumptions, shardIndices=list(range(SHARD_COUNT)),
                matrixGate="separately required by accepted.needs.acceptance", performanceEvidence=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    discovery = commands.add_parser("discover")
    for name in ("log", "proof", "source", "sums", "output"):
        discovery.add_argument("--" + name, type=Path, required=True)
    verification = commands.add_parser("verify")
    for name in ("discovery", "log", "output"):
        verification.add_argument("--" + name, type=Path, required=True)
    verification.add_argument("--isolated-log", type=Path)
    plan = commands.add_parser("plan")
    plan.add_argument("--discovery", type=Path, required=True)
    plan.add_argument("--output", type=Path, required=True)
    aggregation = commands.add_parser("aggregate")
    for name in ("directory", "output"):
        aggregation.add_argument("--" + name, type=Path, required=True)
    for name in ("source", "run", "attempt"):
        aggregation.add_argument("--" + name, required=True)
    args = parser.parse_args()
    if args.command == "discover":
        result = discover(args.log, args.proof, args.source, args.sums)
    elif args.command == "verify":
        result = verify(json.loads(args.discovery.read_text()), args.log, args.isolated_log)
    elif args.command == "plan":
        result = split_plan(json.loads(args.discovery.read_text()))
    else:
        result = aggregate(args.directory, args.source, args.run, args.attempt)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
