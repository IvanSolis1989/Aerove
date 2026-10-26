#!/usr/bin/env python3
"""Prepare and inspect a graph-only Lite125 test harness preflight."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import zipfile

APP_SOURCE = "d52726dc24756847b2b268d307a611cc37f994a3"
APP_TREE = "2dbd4a0c5463a24a826ffde19eace43ed229a1fc"
APP_RUN = 40335340043
ARTIFACT_ID = 12656749144
ARTIFACT_NAME = "AEROVE-Lite-runtime-contract-attempt-1"
ARTIFACT_BYTES = 35353437
ARTIFACT_SHA = "8ac16086a91edcfa75cd037bba658f09e088c4dc105885af66af7442a9ddce6d"
APP_APK_SHA = "ad5b09bcd20312cafd64bed39d4c508c1a463063686aa0ed0236c51d1b9fa701"
OLD_TEST_APK_SHA = "1e80bccab828e7e827f91b2d6e8d7ef5076e8a3875d736fbe4ea3e512a68f63b"
SIGNER_SHA = "09f9e021ddac36ead015d27e073b673e79fdf4e02605c402a505781963b1a4d8"
APP_MAPPING_SHA = "71f3d035f5870d0c4017d2dbd689b62681b5db802dd2008841ab75291e582426"
TEST_CONFIG_SHA = "2b92dd62cdc77dc47a1a5613e573da69d9eab8226d82e5606bd5bdf7d3ca63c0"
TEST_DEPS_SHA = "d970d97199235c2557e51684451a89970abd07a09619598fb0b6c83f3297d4db"
MANIFEST_SHA = "02916fdd4190823236f9bac4a2e7792f6785612c17f4f6e642b0351d3477f9ad"
BUILD_INFO_SHA = "923c0951b4e82471254f3549dd489f8bb6ea5b843117e4104458df66c2521be0"
SHA256SUMS_SHA = "3345e564d4559c80f5eaa62e49a7a9d2f859f11e38f9acf213a455c382a089d4"
TEST_PATH = "apps/aerove-lite/app/src/androidTest/java/dev/aerove/lite/ui/LiteDashboardMatrixTest.kt"
TEST_SOURCE_SHA = "1c4ccbee27feeaf665b81ffdaab4222d5cfc73f9289104933c07901973d209c1"
TEST_DIFF_SHA = "2907c171f0f1d2abff669ff4310d14ae0f8dd833e14bc6f50878ff69672bc6dc"

class GuardError(ValueError):
    pass

def require(ok: bool, message: str) -> None:
    if not ok:
        raise GuardError(message)

def verify_signer(signature: str, label: str) -> None:
    # Match the same legacy and SDK37 certificate formats as verify_apk.py.
    lines = signature.splitlines()
    digests = re.findall(r"^(?:Signer #[0-9]+|V2 Signer:) certificate SHA-256 digest: ([0-9a-f]{64})$",
                         signature, re.MULTILINE)
    require(digests == [SIGNER_SHA]
            and sum("Signer" in line and "certificate SHA-256 digest:" in line for line in lines) == 1
            and lines.count("Verifies") == 1
            and [line for line in lines if line.startswith("Verified using v2 scheme ")]
                == ["Verified using v2 scheme (APK Signature Scheme v2): true"]
            and [line for line in lines if line.startswith("Number of signers:")]
                == ["Number of signers: 1"],
            f"original {label} signer readback mismatch")

def sha_bytes(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()

def sha_file(path: Path) -> str:
    return sha_bytes(path.read_bytes())

def checked_run(argv: list[str], *, timeout: int = 60, capture: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(argv, check=True, timeout=timeout, text=True, capture_output=capture)

def verify_archive_file(archive: Path, extract: Path) -> dict:
    require(archive.is_file() and archive.stat().st_size == ARTIFACT_BYTES and sha_file(archive) == ARTIFACT_SHA,
            "original run125 archive size/hash mismatch")
    extract.mkdir(parents=True, exist_ok=False)
    with zipfile.ZipFile(archive) as zf:
        infos = zf.infolist()
        names = [info.filename for info in infos]
        require(bool(names) and len(names) == len(set(names)), "runtime ZIP empty or contains duplicate paths")
        folded = [name.casefold() for name in names]
        require(len(folded) == len(set(folded)), "runtime ZIP contains case-colliding paths")
        root = extract.resolve(strict=True)
        for info in infos:
            path = PurePosixPath(info.filename)
            mode = info.external_attr >> 16
            require(not path.is_absolute() and ".." not in path.parts and "\\" not in info.filename
                    and not any(":" in part for part in path.parts), "unsafe runtime ZIP path")
            require(not stat.S_ISLNK(mode) and (mode == 0 or stat.S_ISREG(mode) or stat.S_ISDIR(mode)),
                    f"unsupported runtime ZIP member mode: {info.filename} ({mode:o})")
            destination = extract.joinpath(*path.parts)
            require(destination.resolve().is_relative_to(root), f"runtime ZIP path escapes output: {info.filename}")
            if info.is_dir():
                destination.mkdir(parents=True, exist_ok=True)
            else:
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(zf.read(info))
        require(zf.testzip() is None, "runtime ZIP CRC failure")
    manifest_path = extract / "source-manifest.json"
    require(sha_file(manifest_path) == MANIFEST_SHA, "original source manifest hash mismatch")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    require(manifest.get("sourceRevision") == APP_SOURCE and manifest.get("sourceTree") == APP_TREE
            and manifest.get("workflowRun") == str(APP_RUN) and manifest.get("runAttempt") == 1
            and manifest.get("abi") == "x86_64" and manifest.get("apkInspectorOutcome") == "success",
            "source manifest app identity mismatch")
    files = manifest.get("files", [])
    by_path = {row.get("path"): row for row in files}
    require(len(by_path) == len(files), "source manifest has duplicate file names")
    actual_files = {name for name in names if not name.endswith("/")}
    require(actual_files == set(by_path) | {"source-manifest.json", "SHA256SUMS"}, "runtime ZIP members differ from source manifest closure and checksum receipt")
    for name, row in by_path.items():
        path = PurePosixPath(name)
        require(not path.is_absolute() and ".." not in path.parts and "\\" not in name
                and not any(":" in part for part in path.parts), "manifest path is unsafe")
        file_path = extract.joinpath(*path.parts)
        require(file_path.resolve().is_relative_to(root), f"manifest path escapes output: {name}")
        require(file_path.is_file() and file_path.stat().st_size == row.get("bytes")
                and sha_file(file_path) == row.get("sha256"), f"manifest entry differs: {name}")
    sums_path = extract / "SHA256SUMS"
    require(sha_file(sums_path) == SHA256SUMS_SHA, "original SHA256SUMS hash mismatch")
    sums = {}
    for line in sums_path.read_text(encoding="utf-8").splitlines():
        fields = line.split(None, 1)
        require(len(fields) == 2 and re.fullmatch(r"[0-9a-f]{64}", fields[0]) is not None,
                "malformed original SHA256SUMS row")
        name = fields[1].lstrip("* ")
        require(name not in sums, "duplicate original SHA256SUMS path")
        sums[name] = fields[0]
    require(set(sums) == set(by_path), "original SHA256SUMS file set differs from source manifest")
    for name, digest in sums.items():
        require(sha_file(extract.joinpath(*PurePosixPath(name).parts)) == digest,
                f"original SHA256SUMS entry differs: {name}")
    app_apk = extract / "apks/app-release.apk"
    old_test_apk = extract / "apks/app-release-androidTest.apk"
    require(sha_file(app_apk) == APP_APK_SHA, "original beta16 App APK hash mismatch")
    require(sha_file(old_test_apk) == OLD_TEST_APK_SHA, "original run125 test APK changed")
    build_info_path = extract / "BUILD_INFO.json"
    require(sha_file(build_info_path) == BUILD_INFO_SHA, "original BUILD_INFO hash mismatch")
    build_info = json.loads(build_info_path.read_text(encoding="utf-8"))
    require(build_info.get("source") == APP_SOURCE and build_info.get("tree") == APP_TREE
            and build_info.get("run_number") == 125 and build_info.get("run_attempt") == 1
            and build_info.get("version") == "0.1.0-beta.16" and build_info.get("version_code") == 16
            and build_info.get("apk_sha256") == APP_APK_SHA and build_info.get("signer_sha256") == SIGNER_SHA,
            "original BUILD_INFO identity mismatch")
    require(sha_file(extract / "mapping/release/mapping.txt") == APP_MAPPING_SHA,
            "original App R8 mapping hash mismatch")
    config = extract / "mapping/releaseAndroidTest/configuration.txt"
    require(sha_file(config) == TEST_CONFIG_SHA and '-applymapping "/home/runner/work/Aerove/Aerove/apps/aerove-lite/app/build/outputs/mapping/release/mapping.txt"' in config.read_text(encoding="utf-8"),
            "original AndroidTest mapping configuration differs")
    deps = extract / "dependencies/releaseAndroidTestRuntimeClasspath.stdout.txt"
    require(sha_file(deps) == TEST_DEPS_SHA, "original AndroidTest dependency closure changed")
    dep_text = deps.read_text(encoding="utf-8")
    require("androidx.test:runner:1.6.2" in dep_text and "junit:junit:4.13.2" in dep_text,
            "pinned AndroidTest runner/JUnit versions missing")
    return {"artifactId": ARTIFACT_ID, "archiveBytes": ARTIFACT_BYTES, "archiveSha256": ARTIFACT_SHA,
            "sourceRevision": APP_SOURCE, "sourceTree": APP_TREE, "workflowRun": APP_RUN,
            "appApkSha256": APP_APK_SHA, "originalTestApkSha256": OLD_TEST_APK_SHA,
            "buildInfoSha256": BUILD_INFO_SHA, "sourceManifestSha256": MANIFEST_SHA,
            "signerSha256": SIGNER_SHA, "appMappingSha256": APP_MAPPING_SHA,
            "androidTestMappingConfigSha256": TEST_CONFIG_SHA,
            "androidTestRuntimeClasspathSha256": TEST_DEPS_SHA,
            "allManifestFilesMatched": len(files), "sha256SumsFileSha256": SHA256SUMS_SHA,
            "allChecksumRowsMatched": len(sums), "safePathsAndModes": True,
            "archiveCrcVerified": True, "promotionAccepted": False}

def verify_artifact(out: Path) -> dict:
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    require(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo) is not None, "GITHUB_REPOSITORY missing or malformed")
    meta = json.loads(checked_run(["gh", "api", f"repos/{repo}/actions/artifacts/{ARTIFACT_ID}"]).stdout)
    run = meta.get("workflow_run", {})
    require(meta.get("id") == ARTIFACT_ID and meta.get("name") == ARTIFACT_NAME and meta.get("expired") is False,
            "original run125 artifact metadata mismatch/expired")
    require(meta.get("size_in_bytes") == ARTIFACT_BYTES and meta.get("digest") == "sha256:" + ARTIFACT_SHA,
            "original run125 artifact size/digest mismatch")
    require(run.get("id") == APP_RUN and run.get("head_sha") == APP_SOURCE,
            "original run125 artifact source/run mismatch")
    archive = out / "x86-original.zip"
    archive.parent.mkdir(parents=True, exist_ok=True)
    with archive.open("wb") as output:
        subprocess.run(["gh", "api", f"repos/{repo}/actions/artifacts/{ARTIFACT_ID}/zip"],
                       stdout=output, check=True, timeout=90)
    identity = verify_archive_file(archive, out / "runtime")
    extract = out / "runtime"
    app_apk = extract / "apks/app-release.apk"
    old_test_apk = extract / "apks/app-release-androidTest.apk"
    sdk = Path(os.environ["ANDROID_SDK_ROOT"]) / "build-tools/37.0.0"
    app_signature = checked_run([str(sdk / "apksigner"), "verify", "--verbose", "--print-certs", str(app_apk)]).stdout
    test_signature = checked_run([str(sdk / "apksigner"), "verify", "--verbose", "--print-certs", str(old_test_apk)]).stdout
    verify_signer(app_signature, "App")
    verify_signer(test_signature, "test")
    badging = checked_run([str(sdk / "aapt"), "dump", "badging", str(app_apk)]).stdout
    require("package: name='dev.aerove.lite' versionCode='16' versionName='0.1.0-beta.16'" in badging,
            "original App package/version readback mismatch")
    identity["signatureVerifiedAtRuntime"] = True
    identity["packageAndVersionReadback"] = "dev.aerove.lite/0.1.0-beta.16/code16"
    identity["noApkBuilt"] = True
    return identity

def verify_offline_archive(archive: Path, out: Path) -> dict:
    out.mkdir(parents=True, exist_ok=False)
    result = verify_archive_file(archive, out / "runtime")
    result.update({"offlineLocalArchive": True, "networkUsed": False,
                   "signatureCheckedByThisOfflineInvocation": False,
                   "promotionAccepted": False})
    (out / "OFFLINE-ARCHIVE-IDENTITY.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result

def prepare(app_root: Path, harness_root: Path, expected_harness: str, output: Path) -> dict:
    app_root = app_root.resolve(strict=True); harness_root = harness_root.resolve(strict=True)
    app_commit = checked_run(["git", "-C", str(app_root), "rev-parse", "HEAD"]).stdout.strip()
    require(app_commit == APP_SOURCE, "target workspace is not fixed d527 App source")
    harness_commit = checked_run(["git", "-C", str(harness_root), "rev-parse", "HEAD"]).stdout.strip()
    require(re.fullmatch(r"[0-9a-f]{40}", expected_harness) is not None and harness_commit == expected_harness,
            "harness checkout differs from caller workflow commit")
    candidate = harness_root.joinpath(*TEST_PATH.split("/"))
    require(candidate.is_file() and sha_file(candidate) == TEST_SOURCE_SHA, "test source differs from frozen canonical bytes")
    working = app_root.joinpath(*TEST_PATH.split("/"))
    working.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(candidate, working)
    diff = checked_run(["git", "-C", str(app_root), "-c", "core.abbrev=40", "diff", "--binary", APP_SOURCE, "--", TEST_PATH]).stdout.encode("utf-8")
    require(sha_bytes(diff) == TEST_DIFF_SHA, "test-only diff differs from the frozen d527 patch")
    changed = checked_run(["git", "-C", str(app_root), "diff", "--name-only", APP_SOURCE]).stdout.splitlines()
    require(changed == [TEST_PATH], f"overlay changed more than the approved androidTest file: {changed}")
    output.mkdir(parents=True, exist_ok=True)
    result = {"targetApp": {"sourceRevision": APP_SOURCE, "sourceTree": APP_TREE, "workflowRun": APP_RUN,
                "artifactId": ARTIFACT_ID, "apkSha256": APP_APK_SHA, "signerSha256": SIGNER_SHA,
                "mappingSha256": APP_MAPPING_SHA, "originalTestApkSha256": OLD_TEST_APK_SHA},
              "testHarness": {"sourceRevision": harness_commit, "testSourcePath": TEST_PATH,
                "testSourceSha256": TEST_SOURCE_SHA, "testOnlyDiffSha256": TEST_DIFF_SHA,
                "changedPaths": [TEST_PATH], "APKBuilt": False}, "promotionAccepted": False}
    (output / "INPUT-IDENTITY.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result

def verify_graph(path: Path, output: Path, graph_outcome: str) -> dict:
    parse_error = None
    try:
        raw = path.read_text(encoding="utf-8", errors="replace")
    except OSError as error:
        raw = ""
        parse_error = f"Gradle task graph log unavailable: {error}"
    raw = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", raw)
    tasks = []
    statuses = r"(?:SKIPPED|UP-TO-DATE|FROM-CACHE|NO-SOURCE)"
    for line in raw.splitlines():
        match = re.fullmatch(rf"> Task\s+(:[^\s]+)(?:\s+{statuses})?", line.strip())
        if match is None:
            match = re.fullmatch(rf"(:[^\s]+)\s+{statuses}", line.strip())
        if match:
            tasks.append(match.group(1))
    if not tasks and parse_error is None:
        parse_error = "Gradle task graph log contains no recognized task lines"
    forbidden_names = {":app:assembleRelease", ":app:packageRelease", ":app:bundleRelease", ":app:minifyReleaseWithR8"}
    forbidden = [task for task in tasks if task in forbidden_names or any(token in task.lower() for token in ("native", "ndk", "cmake", "rust", "cargo", "meow", "helper"))]
    test_assemble = ":app:assembleReleaseAndroidTest" in tasks
    parsed = bool(tasks)
    failures = []
    if graph_outcome != "success":
        failures.append(f"Gradle dry-run command outcome was {graph_outcome!r}, not success")
    if parse_error:
        failures.append(parse_error)
    if parsed and not test_assemble:
        failures.append("dry-run graph omitted :app:assembleReleaseAndroidTest")
    if forbidden:
        failures.append("dry-run graph contains forbidden App/native/helper tasks")
    passed = not failures
    result = {"mode": "dry-run-only", "graphParsed": parsed, "parseError": parse_error,
              "gradleCommandOutcome": graph_outcome, "gradleCommandSucceeded": graph_outcome == "success",
              "failureReasons": failures,
              "taskCount": len(tasks), "tasks": tasks, "testApkAssembleInGraph": test_assemble,
              "forbiddenAppOrNativeTasks": forbidden, "graphHasNoForbiddenTasks": passed,
              "actualCompileRun": False, "appApkBuildRun": False,
              "humanR8MappingReviewRequired": True, "testCompileAuthorizedByThisGraph": False,
              "diagnosticOnly": True, "accepted": False, "promotionAccepted": False}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    require(passed, "; ".join(failures) or "Gradle task graph rejected")
    return result

def main() -> int:
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    p = commands.add_parser("runtime"); p.add_argument("--out", type=Path, required=True)
    p = commands.add_parser("offline-archive"); p.add_argument("--archive", type=Path, required=True); p.add_argument("--out", type=Path, required=True)
    p = commands.add_parser("prepare"); p.add_argument("--app-root", type=Path, required=True); p.add_argument("--harness-root", type=Path, required=True); p.add_argument("--harness-commit", required=True); p.add_argument("--out", type=Path, required=True)
    p = commands.add_parser("graph"); p.add_argument("--log", type=Path, required=True); p.add_argument("--out", type=Path, required=True); p.add_argument("--graph-outcome", required=True)
    args = parser.parse_args()
    try:
        if args.command == "runtime": result = verify_artifact(args.out)
        elif args.command == "offline-archive": result = verify_offline_archive(args.archive.resolve(strict=True), args.out)
        elif args.command == "prepare": result = prepare(args.app_root, args.harness_root, args.harness_commit, args.out)
        else: result = verify_graph(args.log, args.out, args.graph_outcome)
    except (OSError, zipfile.BadZipFile, json.JSONDecodeError, subprocess.SubprocessError, GuardError) as error:
        parser.error(str(error))
    print(json.dumps(result, indent=2))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
