"""Strict current Flutter/platform receipts; historical Compose shard proof is a separate protocol."""
import argparse
import hashlib
import json
from pathlib import Path
import re
from uuid import UUID

from instrumentation_shards import parse_log, load_version_contract
from flutter_android_qa import validate_capture, renderer, Producer

CONTRACT = Path(__file__).with_name("android_acceptance_contract.json")
CORE = "727ddcd06f592e2f12c5222c4de6cce328df808c"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def sealed_file(root, manifest, relative):
    root = Path(root).resolve()
    name = Path(relative)
    require(not name.is_absolute() and ".." not in name.parts and "\\" not in relative, "Unsafe sealed path")
    path = root / name
    require(path.resolve() == path.absolute() and path.is_file(), "Missing or linked sealed file")
    rows = [row for row in manifest["files"] if row["path"] == relative]
    require(len(rows) == 1 and rows[0]["bytes"] == path.stat().st_size and rows[0]["sha256"] == sha(path), "Runtime file changed")
    return path


def platform_binding(runtime, info):
    manifest = load(Path(runtime) / "source-manifest.json")
    apk = sealed_file(runtime, manifest, "apks/platform-qa-release.apk")
    receipt = load(sealed_file(runtime, manifest, "platform-runner-build.json"))
    require(receipt.get("schema") == 1 and receipt.get("source") == info["source"] and
            receipt.get("tree") == info["tree"] and receipt.get("productionApkSha256") == info["apk_sha256"] and
            receipt.get("testApkSha256") == sha(apk) and receipt.get("signerSha256") == info["signer_sha256"] and
            receipt.get("targetPackage") == "dev.aerove.lite" and
            receipt.get("testPackage") == "dev.aerove.lite.platform.test" and
            receipt.get("runner") == "dev.aerove.lite.platformqa.AcceptanceCheckpointRunner" and
            receipt.get("productionBuildCount") == 1 and receipt.get("androidExecuted") is False and
            receipt.get("uiTestSourceSha256") == load(CONTRACT)["uiSourceSha256"],
            "Platform test APK is not tied to the one original target build/signer/source")
    require(receipt.get("runtimeDeviceAttestorSourceSha256") == load(CONTRACT)["runtimeDeviceAttestorSource"]["sha256"],
            "Platform build is not tied to the current runtime device observer")
    abi=receipt.get("r8Abi",{})
    require(abi.get("mode")=="typed-pre-r8-app-keeps-and-official-test-r8-applymapping" and
            abi.get("appR8Enabled") is True and abi.get("testR8Enabled") is True,
            "Standalone target ABI keep/remap chain is missing")
    for relative,key in (("app-abi-rules.pro","appRulesSha256"),("app-abi-report.json","appAbiReportSha256"),
                         ("test-mapping.txt","testR8MappingSha256"),("test-configuration.txt","testR8ConfigurationSha256")):
        require(sha(sealed_file(runtime,manifest,relative))==abi.get(key),"R8 ABI input/output seal changed")
    from seal_platform_runner import seal_abi, native_migration_binding
    actual_abi=seal_abi(
        sealed_file(runtime,manifest,"lite-platform-one-build-graph.txt").read_text(),
        sealed_file(runtime,manifest,"mapping/release/mapping.txt").read_text(),
        sealed_file(runtime,manifest,"platform-all-references.stdout.txt").read_text(),
        sealed_file(runtime,manifest,"app-abi-rules.pro"),sealed_file(runtime,manifest,"app-abi-report.json"),
        sealed_file(runtime,manifest,"test-mapping.txt"),sealed_file(runtime,manifest,"test-configuration.txt"))
    require(actual_abi==abi,"Raw target/test R8 ABI proof differs from producer receipt")
    fixture_root = Path(runtime) / "native-migration-fixture"
    for path in fixture_root.rglob("*"):
        if path.is_file():
            sealed_file(runtime, manifest, path.relative_to(runtime).as_posix())
    fixture = native_migration_binding(info, apk,
        sealed_file(runtime, manifest, "platform-defined-classes.stdout.txt").read_text(), fixture_root)
    require(receipt.get("nativeMigrationFixture") == fixture, "Runner migration fixture source or assets changed")
    from profile_cancel_verify import prerequisite
    cancel_root=Path(runtime)/"profile-cancel-prerequisite"
    for path in cancel_root.rglob("*"):
        if path.is_file():sealed_file(runtime,manifest,path.relative_to(runtime).as_posix())
    require(receipt.get("profileCancelPrerequisite")==prerequisite(cancel_root),"Build cancellation prerequisite changed")
    reuse = receipt.get("sharedUiReuse", {})
    expected_reuse = load(Path(__file__).with_name("android_shared_ui_reuse.json"))
    require(reuse.get("scope") == "exact-four-canonical-files-only" and reuse.get("files") == expected_reuse["files"] and
            reuse.get("standardSource") == expected_reuse["standardSource"] and reuse.get("standardRun") == expected_reuse["standardRun"] and
            reuse.get("standardApkSha256") == expected_reuse["standardApkSha256"] and reuse.get("liteAndroidAccepted") is False and
            reuse.get("contractSha256") == sha(Path(__file__).with_name("android_shared_ui_reuse.json")), "Shared reuse scope/identity changed")
    graph = sealed_file(runtime, manifest, "dependencies/platform-releaseRuntimeClasspath.stdout.txt")
    status = sealed_file(runtime, manifest, "dependencies/platform-releaseRuntimeClasspath.exit.txt")
    require(status.read_text().strip() == "0", "Platform test dependency resolution failed")
    for coordinate, allowed in load_version_contract().items():
        versions = {target or requested for requested, target in re.findall(re.escape(coordinate) + r":([^\s]+)(?: -> ([^\s]+))?", graph.read_text())}
        require(len(versions) == 1 and versions.issubset(allowed), "Unexpected platform runner dependency: " + coordinate)
    return apk


def platform_log(log, contract=None):
    contract = contract or load(CONTRACT)
    records = parse_log(log)
    actual = sorted(row["className"] + "#" + row["testName"] for row in records)
    require(actual == sorted(contract["platformSelectors"]) and len(actual) == len(set(actual)) == 31,
            "Platform execution must be exactly the fixed 31 cases, independent of discovery")
    require(all(row["status"] == "passed" for row in records), "Skipped/assumption platform case is not acceptance")
    require(re.findall(r"OK \((\d+) tests?\)", Path(log).read_text()) == ["31"], "Exact JUnit 31 pass terminal required")
    return dict(logSha256=sha(log), selectors=actual, allPassed=True)


def legacy_log(path, selector):
    records = parse_log(path)
    require(len(records) == 1 and records[0]["className"] + "#" + records[0]["testName"] == selector and
            records[0]["status"] == "passed" and re.findall(r"OK \((\d+) tests?\)", Path(path).read_text()) == ["1"],
            "Each legacy invocation must pass exactly its selected real Android method")


def legacy_capture(directory, method, info, token, fixture, harness_sha, build_info_sha):
    directory = Path(directory)
    receipt = load(directory / "receipt.json")
    require(receipt.get("schema") == 1 and receipt.get("producer") == "dev.aerove.lite.platformqa.NativeLegacyMigrationFixtureTest"
            and receipt.get("method") == method and receipt.get("qaToken") == token and receipt.get("status") == "passed"
            and receipt.get("synthetic") is True and receipt.get("visualAccepted") is False
            and receipt.get("userDataAcceptance") is False and receipt.get("cooperativeCancellationTested") is False
            and receipt.get("sourceUnchanged") is True and receipt.get("targetVerified") is True,
            "Legacy fixture execution did not prove its exact synthetic migration scenario")
    parameters = receipt.get("sourceParameters", {})
    expected = dict(rootSourceSha=info["source"], productionSourceSha=info["source"], harnessSourceSha=info["source"],
        fixtureSourceSha=fixture["producerSource"], fixtureProducerRun=fixture["producerRun"],
        productionBuildInfoSha256=build_info_sha, requiresExternalSourceBuildInfoBinding=True)
    require(parameters == expected and receipt.get("fixtureManifestSha256") == fixture["manifestSha256"],
            "Legacy receipt differs from the sealed producer and original Build Info")
    require(receipt.get("processName") == "dev.aerove.lite", "Legacy test did not run in the actual target process")
    for field in ("installedAppBefore", "installedAppAfter", "installedHarness"):
        identity = receipt.get(field, {})
        app = field != "installedHarness"
        require(identity.get("package") == ("dev.aerove.lite" if app else "dev.aerove.lite.platform.test") and
                identity.get("apkSha256") == (info["apk_sha256"] if app else harness_sha) and
                identity.get("signerSha256") == info["signer_sha256"] and
                isinstance(identity.get("pid"), int) and identity["pid"] > 0 and
                identity.get("processUid") == receipt["installedAppBefore"]["uid"] and
                re.fullmatch(r"/data/app/[^\s]+/base\.apk", identity.get("packageCodePath", "")),
                "Actual legacy App/runner identity differs from the sealed installation")
        if app:
            require(identity.get("uid") == identity["processUid"] and identity.get("versionCode") == info["version_code"] and
                    identity.get("versionName") == info["version"], "Legacy App version or execution UID changed")
    native, dart = load(directory / "native-state.json"), load(directory / "dart-journal.json")
    source_path, target_path = directory / "android-source-manifest.json", directory / "target-receipt.json"
    digest, target_digest = sha(source_path), sha(target_path)
    require(native.get("schema") == dart.get("schema") == 1 and native.get("phase") == "shared" and
            dart.get("phase") == "complete" and native.get("sourceDigest") == dart.get("sourceDigest") == digest and
            native.get("receiptSha256") == dart.get("receiptSha256") == target_digest and
            receipt.get("androidSourceDigest") == digest and receipt.get("receiptSha256") == target_digest and
            (directory / "committed-receipt.json").read_bytes() == target_path.read_bytes(),
            "Actual native/Dart migration journals and committed target receipt do not converge")
    proof = load(target_path)
    require(proof.get("schema") == 1 and proof.get("sourceDigest") == digest, "Committed migration target has a different source")
    if method == "receiptPathFailureRecoversThroughProductionLauncherWithoutChangingSource":
        failure = receipt.get("receiptPathFailure", {})
        before_native, before_dart = load(directory / "failure-native-state.json"), load(directory / "failure-dart-journal.json")
        require(failure.get("nativePhase") == before_native.get("phase") == "pending" and
                failure.get("dartPhase") == before_dart.get("phase") == "preferencesCommitted" and
                all(failure.get(key) is True for key in ("startupFailureVisible", "committedReceiptAbsent", "sourceUnchanged")) and
                "receiptSha256" not in before_native and "receiptSha256" not in before_dart and
                before_native.get("sourceDigest") == before_dart.get("sourceDigest") == digest and
                before_dart.get("ids") == dart.get("ids") and bool(dart.get("ids")) and
                receipt.get("sameJournalSourceAndIdsRecovered") is True and
                receipt.get("recoveryAction") == "remove-owned-link-then-finish-and-relaunch-Launcher",
                "Legacy persistence failure was not observed and recovered with the same source and IDs")
    return receipt


def verify_legacy_invocations(root, phase, info, contract):
    root = Path(root)
    from ci_artifacts import verify
    fixture_root = root / "native-migration-fixture"
    transfer = verify(fixture_root, dict(sourceRevision=info["source"], sourceTree=info["tree"],
        workflowRun=info["workflow"].rsplit("/", 1)[-1], runNumber=info["run_number"], runAttempt=info["run_attempt"]))
    require(transfer.get("abi") == "shared-fixture", "Legacy source was not the verified shared fixture")
    envelope = phase.get("legacyMigration", {})
    fixture = envelope.get("fixture", {})
    require(fixture.get("producerSource") == info["source"] and fixture.get("producerTree") == info["tree"] and
            fixture.get("producerRun") == info["workflow"].rsplit("/", 1)[-1] and fixture.get("producerAttempt") == info["run_attempt"] and
            fixture.get("provenanceSha256") == sha(fixture_root / "TRANSFER.json") and
            fixture.get("manifestSha256") == sha(fixture_root / "retained-export/manifest.json"), "Legacy fixture binding changed")
    rows = envelope.get("invocations", [])
    require([row.get("selector") for row in rows] == contract["legacySelectors"] and len(rows) == 2 and
            len({row.get("qaToken") for row in rows}) == 2, "Both independent legacy Android scenarios must run")
    expected_records = load(fixture_root / "retained-export/manifest.json")["files"]
    for generation, row in enumerate(rows, 2):
        method = row["selector"].split("#", 1)[1]
        require(row.get("directory") == "legacy/" + method and row.get("logPath") == "legacy-" + method + ".out" and
                row.get("installation") == dict(generation=generation, previousOwnedInstallationRemoved=True,
                    absenceConfirmed=True, productionApkSha256=info["apk_sha256"], testApkSha256=phase["platformApkSha256"]),
                "Legacy scenarios did not use independent owned installations of the same original APKs")
        legacy_log(root / row["logPath"], row["selector"])
        directory = root / row["directory"]
        require(row.get("receiptSha256") == sha(directory / "receipt.json"), "Legacy invocation receipt changed")
        captured = legacy_capture(directory, method, info, row["qaToken"], fixture, phase["platformApkSha256"], sha(root / "BUILD_INFO.json"))
        records = lambda values: {item["path"]: (item["bytes"], item["sha256"]) for item in values}
        require(records(captured["seededRecords"]) == records(expected_records) ==
                records(load(directory / "android-source-manifest.json")["files"]), "Legacy Android source or retained history changed")


def checkpoint(directory, stage, info, token):
    directory = Path(directory)
    request_path = directory / "checkpoints" / (stage + ".json")
    ack_path = directory / "native-acks" / (stage + ".json")
    request, ack = load(request_path), load(ack_path)
    identity = dict(schema=1, qaToken=token, stage=stage, source=info["source"], tree=info["tree"], apkSha256=info["apk_sha256"])
    require(all(request.get(k) == v and ack.get(k) == v for k,v in identity.items()), "Checkpoint identity mismatch")
    nonce = request.get("nonce")
    require(isinstance(nonce, str) and str(UUID(nonce)) == nonce and ack.get("nonce") == nonce,
            "Checkpoint nonce mismatch")
    require(type(request.get("pid")) is int and request["pid"] > 0 and ack.get("pid") == request["pid"] and
            ack.get("requestSha256") == sha(request_path) and ack.get("status") == "attested", "Stale/failed checkpoint ACK")
    require(ack.get("evidencePath") == "native-state/" + nonce + ".json", "Unsafe native evidence path")
    path = directory / ack["evidencePath"]
    require(path.resolve() == path.absolute() and sha(path) == ack.get("evidenceSha256"), "Native evidence bytes changed")
    native = load(path)
    require(all(native.get(k) == v for k,v in identity.items()) and native.get("pid") == request["pid"] and
            native.get("nonce") == nonce and native.get("requestSha256") == sha(request_path) and
            native.get("status") == "attested" and native.get("producer") == "AcceptanceCheckpointRunner" and
            native.get("readOnly") is True and native.get("selectionComplete") is True and
            native.get("rawSelectionMatchesSharedSql") is True and native.get("coreCommit") == CORE and
            native.get("overlaySha256") == info["meow_overlay_sha256"] and
            native.get("adaptedFileManifestSha256") == info["meow_file_manifest_sha256"] and native.get("wireVerified") is False,
            "Native owner/source/selection proof incomplete")
    for key in ("coreEpoch", "selectionWitnessId"):
        require(re.fullmatch(r"[1-9][0-9]*", str(native.get(key, ""))) is not None, "Invalid actual Native " + key)
    require(type(native.get("intentEpoch")) is int and native["intentEpoch"] >= 0 and
            all(re.fullmatch(r"[0-9a-f]{64}", str(native.get(k, ""))) for k in
                ("compiledSha256", "capabilitiesSha256", "sourceDefinitionSha256")), "Missing live source fingerprint")
    require(type(native.get("runtimeServiceOwner")) is int and native["runtimeServiceOwner"] > 0 and
            type(native.get("runtimeSessionGeneration")) is int and native["runtimeSessionGeneration"] > 0,
            "No real current running/parked graph owner identity")
    expected = request.get("expectedVpn")
    require(type(expected) is bool and native.get("expectedVpn") is expected and
            native.get("networkHandles") == sorted(request.get("networkHandles", [])), "OS/UI network evidence mismatch")
    if expected:
        require(type(request.get("expectedMixedPort")) is int and request["expectedMixedPort"] in range(1024,49152) and
                native.get("actualMixedPort") == request["expectedMixedPort"] and request.get("expectedBypassPrivateRoute") is False and
                native.get("actualBypassPrivateRoute") is False and request.get("fixtureRouteTarget") == "10.0.2.2" and
                re.fullmatch(r"[0-9a-f]{64}", str(native.get("networkReadbackSha256", ""))) and
                request.get("networkSettingsUiEvidence") == ["network-mixed-port-readback.png", "network-private-bypass-readback.png"],
                "UI expectations do not replace actual Native port/builder network readback")
        raw_network = native.get("networkReadbackJson", "")
        require(isinstance(raw_network, str) and hashlib.sha256(raw_network.encode()).hexdigest() == native["networkReadbackSha256"], "Actual private network readback changed")
        network = json.loads(raw_network)
        require(network.get("coreEpoch") == native["coreEpoch"] and network.get("network", {}).get("mixed-port") == native["actualMixedPort"] and
                native["actualMixedPort"] in network.get("activePorts", []), "Native configured port is not an actual active listener")
        require(native.get("phase") == "Running" and native.get("listening") is True and native.get("suspended") is False and
                type(native.get("serviceOwner")) is int and native["serviceOwner"] > 0 and
                type(native.get("sessionGeneration")) is int and native["sessionGeneration"] > 0 and
                native["serviceOwner"] == native["runtimeServiceOwner"] and native["sessionGeneration"] == native["runtimeSessionGeneration"] and
                len(native["networkHandles"]) == 1 and native.get("queryGraphParked") is False,
                "A VPN label without current running owner/session is insufficient")
    else:
        require(native.get("phase") == "Stopped" and not native["networkHandles"] and native.get("queryGraphParked") is True and
                native.get("userListenerClosed") is True and native.get("closedMixedPort") == request.get("expectedMixedPort"),
                "Stopped checkpoint still has user business/VPN")
    return native


def separate_uid(native):
    tun = native.get("tun", {})
    require(native.get("expectedVpn") is True and native.get("tunSeparateUidAccepted") is True and tun.get("accepted") is True and
            tun.get("probeUid") == 2000 and type(tun.get("targetUid")) is int and tun["targetUid"] != 2000 and
            tun.get("bypassPrivateRoute") is False and tun.get("appFilterMode") == "All" and
            tun.get("networkHandle") in native["networkHandles"] and bool(tun.get("routes")) and
            tun.get("uploadAfter", 0) > tun.get("uploadBefore", 0) >= 0 and
            tun.get("downloadAfter", 0) > tun.get("downloadBefore", 0) >= 0 and
            re.fullmatch(r"[0-9a-f]{64}", str(tun.get("responseSha256", ""))), "Independent UID TUN forwarding has not been proved")


STAGES = {
    "profileImportApplyVpnNetworkAndBackgroundResume": ["imported-selected", "vpn-active", "background-active", "resumed-active", "vpn-stopped"],
    "cancelVpnAuthorizationThenRecover": ["vpn-permission-cancelled", "vpn-active", "vpn-stopped"],
    "prepareImportedRunningSessionForProcessRestart": ["vpn-active", "functional-running-for-host-restart"],
    "persistedImportedProfileAfterProcessRestart": ["restart-observed", "vpn-active", "vpn-stopped"],
}


def ui_capture(directory, method, info, token, prepared=False, profile_proof=None):
    directory = Path(directory)
    if method in ("consentNavigationTheme12", "persistedConsentAfterProcessRestart"):
        return validate_capture(directory, info, token, matrix=method == "consentNavigationTheme12"), []
    receipt = load(directory / "receipt.json")
    require(receipt.get("schema") == 1 and receipt.get("producer") == "dev.aerove.lite.FlutterProductionUiTest" and
            receipt.get("qaToken") == token and receipt.get("apkSha256") == info["apk_sha256"] and
            receipt.get("source") == info["source"] and receipt.get("tree") == info["tree"] and
            receipt.get("workflow") == info["workflow"] and receipt.get("runAttempt") == str(info["run_attempt"]),
            "Functional UI receipt lost exact target identity")
    require(receipt.get("nativeMeowAccepted") is False and receipt.get("fullAcceptance") is False,
            "UI producer must not self-certify Native/full acceptance")
    if prepared:
        require(method == "prepareImportedRunningSessionForProcessRestart" and receipt.get("scenarioPassed") is False and
                receipt.get("expectedTermination") is True and receipt.get("status") == "awaiting-host-process-termination",
                "Prepared running method is an expected interruption, never JUnit pass")
    else:
        require(receipt.get("scenarioPassed") is True and receipt.get("status") == "captured", "Functional scenario failed")
    frames = receipt.get("frames", [])
    require(bool(frames), "Functional scenario has no actual Android captures")
    frame_names = {frame["file"] for frame in frames}
    for frame in frames:
        for field, hash_field in (("file", "sha256"), ("hierarchy", "hierarchySha256"), ("windowInsetsDump", "windowInsetsDumpSha256")):
            name = frame[field]
            require(Path(name).name == name and "\\" not in name and (directory/name).resolve() == (directory/name).absolute() and
                    sha(directory/name) == frame[hash_field], "Functional capture bytes/path changed")
        image = (directory/frame["file"]).read_bytes()
        require(image[:8] == b"\x89PNG\r\n\x1a\n" and len(image) >= 33 and image[12:16] == b"IHDR" and
                frame.get("bytes") == len(image) and frame.get("density",0) > 0 and frame.get("windows"), "Invalid original functional capture")
    from runtime_device_verify import LIFECYCLE_METHOD, OWNER_METHOD, sequence as device_sequence
    if method in (LIFECYCLE_METHOD, OWNER_METHOD):
        natives = device_sequence(directory, method, info, token)
        require(all(native["pid"] == receipt["pid"] for native in natives), "Runtime device scene uses another target process")
        return receipt, natives
    if method == "profileUpdateCancelBatchAndRetry":
        from profile_cancel_verify import sequence
        require(profile_proof is not None and receipt.get("profileUpdateCancelAccepted") is False,"Missing sealed prerequisite or self-certified cancellation")
        natives=sequence(directory,info,token,profile_proof)
        require(all(n["pid"]==receipt["pid"] for n in natives),"Cancellation checkpoint process differs from actual UI")
        return receipt,natives
    expected = STAGES[method]
    request_files = sorted(x.stem for x in (directory / "checkpoints").glob("*.json"))
    require(request_files == sorted(expected), "Missing/extra functional checkpoint")
    for stage in expected:
        request = load(directory/"checkpoints"/(stage+".json"))
        require(set(request.get("networkSettingsUiEvidence", [])) <= frame_names, "Network UI readback capture is missing")
    natives = [checkpoint(directory, stage, info, token) for stage in expected]
    require(all(n["pid"] == receipt["pid"] for n in natives), "Checkpoint process differs from UI receipt")
    active = [n for n in natives if n["expectedVpn"]]
    if method == "profileImportApplyVpnNetworkAndBackgroundResume":
        require(len({(n["serviceOwner"], n["sessionGeneration"], n["coreEpoch"], tuple(n["networkHandles"])) for n in active}) == 1,
                "HOME/resume replaced the live owner/session/core/VPN")
    return receipt, natives


def aggregate(directory, source, run, attempt, require_full=True):
    root = Path(directory)
    paths = list(root.rglob("android-acceptance.json"))
    receipts = [load(p) for p in paths]
    require(len(receipts) == 2 and {r.get("phase") for r in receipts} == {"flutter-platform", "flutter-full"},
            "Exactly platform and Flutter functionality receipts are required")
    identities = []
    for receipt_path, r in zip(paths, receipts):
        entries = r.get("files", [])
        require(type(entries) is list and len({entry["path"] for entry in entries}) == len(entries), "Duplicate evidence manifest entry")
        actual = {p.relative_to(receipt_path.parent).as_posix() for p in receipt_path.parent.rglob("*")
                  if p.is_file() and p != receipt_path}
        require(actual == {entry["path"] for entry in entries}, "Missing/extra raw acceptance evidence")
        for entry in entries:
            sealed_file(receipt_path.parent, r, entry["path"])
        i = r.get("identity", {})
        require(i.get("sourceRevision") == source and str(i.get("workflowRun")) == str(run) and
                str(i.get("runAttempt")) == str(attempt) and r.get("executionPassed") is True and
                r.get("cleanupStatus") == "complete", "Acceptance source/run/attempt/execution/cleanup differs")
        identities.append((i, r.get("apkSha256"), r.get("platformApkSha256"), r.get("runtimeManifestSha256")))
    require(identities[0] == identities[1], "Platform and Flutter suites used different source/APKs/runtime")
    platform = next(r for r in receipts if r["phase"] == "flutter-platform")
    ui = next(r for r in receipts if r["phase"] == "flutter-full")
    contract = load(CONTRACT)
    platform_root = paths[receipts.index(platform)].parent
    ui_root = paths[receipts.index(ui)].parent
    require(platform_log(platform_root / "platform-tests.out") == platform.get("platform"), "Platform receipt differs from actual raw log")
    info = load(ui_root / "BUILD_INFO.json")
    require(info["source"] == source and info["apk_sha256"] == ui["apkSha256"], "Raw build identity differs")
    verify_legacy_invocations(platform_root, platform, info, contract)
    invocations = ui.get("uiInvocations", [])
    require(["dev.aerove.lite.FlutterProductionUiTest#" + row["method"] for row in invocations] == contract["uiSelectors"] and
            len({row["qaToken"] for row in invocations}) == len(contract["uiSelectors"]), "Each exact UI method requires its own fresh token")
    from profile_cancel_verify import prerequisite
    profile_proof=prerequisite(ui_root/"profile-cancel-prerequisite")
    require(ui.get("profileUpdateCancelVerified") is True,"Actual seventh Android cancellation method was not verified")
    all_natives = []
    for row in invocations:
        require(row["directory"] == row["method"], "Invalid invocation evidence directory")
        prepared = row["method"] == "prepareImportedRunningSessionForProcessRestart"
        capture, natives = ui_capture(ui_root / row["method"], row["method"], info, row["qaToken"], prepared, profile_proof)
        require(sha(ui_root / row["method"] / "receipt.json") == row["receiptSha256"] and capture["pid"] == row["pid"], "UI invocation receipt changed")
        if not prepared:
            records = parse_log(ui_root / (row["method"] + ".out"))
            require(len(records) == 1 and records[0]["className"] + "#" + records[0]["testName"] ==
                    "dev.aerove.lite.FlutterProductionUiTest#" + row["method"] and records[0]["status"] == "passed", "UI raw log selection differs")
        raw_process_log = (ui_root / (row["method"] + "-renderer.out")).read_text(errors="replace")
        require(row.get("renderer") == renderer(raw_process_log, row["pid"]), "UI invocation lacks its actual PID-scoped Impeller proof")
        Producer.check_process_log(raw_process_log, row["pid"])
        if row["method"] == "independentApplicationTcpUdpNativeAttribution":
            from runtime_device_verify import verify_host_owner
            verify_host_owner(ui_root, natives, info)
        all_natives.extend(natives)
    boundary = load(ui_root / "PROCESS-BOUNDARY.json")
    prepared = invocations[4]; recovered = invocations[5]
    require(boundary.get("kind") == "expected-host-process-termination" and boundary.get("source") == source and
            boundary.get("tree") == info["tree"] and boundary.get("apkSha256") == info["apk_sha256"] and
            boundary.get("qaToken") == prepared["qaToken"] and boundary.get("pid") == prepared["pid"] and
            boundary.get("preparedReceiptSha256") == prepared["receiptSha256"] and recovered["pid"] != prepared["pid"] and
            boundary.get("junitPass") is False and all(boundary.get(k) is True for k in
                ("processWasAlive", "vpnWasOwned", "hostForcedStop", "pidGone", "expectedInterruption")), "Expected running process boundary incomplete")
    require((ui_root / "boundary-pidof.out").read_text().strip().split() == [str(prepared["pid"])] and
            not (ui_root / "boundary-confirm-pid-gone.out").read_text().strip() and
            boundary["connectivitySha256"] == sha(ui_root / "boundary-connectivity.out"), "Host raw live/terminated process evidence missing")
    require("OK (1 test)" not in (ui_root / (prepared["method"] + ".out")).read_text(), "Preparer cannot be counted as a normal pass")
    require((ui_root / "boundary-last-pidof.out").read_text().strip().split() == [str(prepared["pid"])], "Prepared PID was not alive at the kill boundary")
    require(sha(ui_root / "boundary-last-prepared-receipt.out") == prepared["receiptSha256"], "Prepared boundary source changed before kill")
    prepared_capture = load(ui_root / prepared["method"] / "receipt.json")
    latest_uptime = float((ui_root / "boundary-last-uptime.out").read_text().split()[0]) * 1000
    require(0 <= latest_uptime - prepared_capture["preparedElapsedRealtimeMs"] <= 35_000, "UI preparer had already reached its expected-termination deadline")
    original_stat = (ui_root / "boundary-process-stat.out").read_text()
    last_stat = (ui_root / "boundary-last-process-stat.out").read_text()
    require(original_stat[original_stat.rfind(")")+2:].split()[19] == last_stat[last_stat.rfind(")")+2:].split()[19] == boundary["startTicks"], "Prepared PID was reused before the owned kill")
    final_process_log = (ui_root / "functional-final-process-log.out").read_text(errors="replace")
    for row in invocations:
        Producer.check_process_log(final_process_log, row["pid"])
    require(platform.get("platform", {}).get("selectors") == contract["platformSelectors"] and
            platform["platform"].get("allPassed") is True, "Fixed platform selection incomplete")
    require(sorted(ui.get("normalUiSelectors", [])) == sorted(contract["normalUiSelectors"]) and
            ui.get("expectedTerminationSelector") == contract["expectedTerminationSelector"] and
            ui.get("expectedTerminationVerified") is True and ui.get("nativeOwnerAttestationsVerified") is True and
            ui.get("matrixImages") == 12 and ui.get("appRenderer", {}).get("backend") == "impeller",
            "UI exact selection/termination/Native/renderer/matrix gate incomplete")
    gaps = completion_gaps(ui, all_natives)
    result = dict(schema=1, producer="android_acceptance_verify.py", identity=receipts[0]["identity"],
                  exactAndroidExecutionPassed=True, unresolvedCoverage=sorted(set(gaps)),
                  sharedUiReuse=ui.get("sharedUiReuse"), fullAcceptance=not gaps, releaseAccepted=False,
                  visualApproved=ui.get("visualApproved") is True)
    if require_full:
        require(not gaps, "Full Android acceptance remains blocked: " + ", ".join(result["unresolvedCoverage"]))
    return result


def completion_gaps(ui, all_natives):
    # Classification is a coverage ledger, never a substitute for executed evidence.
    gaps = list(ui.get("unresolvedCoverage", []))
    try:
        active = [native for native in all_natives if native.get("expectedVpn") is True]
        require(bool(active), "No actual active Native TUN evidence")
        for native in active:
            separate_uid(native)
        require(ui.get("tunSeparateUidAccepted") is True, "Producer did not accept its independent UID evidence")
    except ValueError:
        gaps.append("independent-UID TUN")
    if ui.get("canonicalSharedEvidenceVerified") is not True:
        gaps.append("canonical shared Flutter evidence binding")
    if ui.get("opticalDisplacementVerified") is not True:
        gaps.append("Lite real optical displacement")
    if ui.get("visualApproved") is not True:
        gaps.append("user twelve-frame visual approval")
    return sorted(set(gaps))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    for key in ("source", "run", "attempt"):
        parser.add_argument("--" + key, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--execution-only", action="store_true",
                        help="Verify actual Android execution and retain pending release gates without approving them")
    args = parser.parse_args()
    try:
        result = aggregate(args.directory, args.source, args.run, args.attempt, require_full=False)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n")
        require(result["exactAndroidExecutionPassed"] is True, "Exact Android execution did not pass")
        if not args.execution_only:
            require(result["fullAcceptance"], "Full acceptance pending: " + repr(result["unresolvedCoverage"]))
    except ValueError as error:
        if not args.output.exists():
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(dict(schema=1, fullAcceptance=False, error=str(error)), indent=2) + "\n")
        raise


if __name__ == "__main__":
    main()
