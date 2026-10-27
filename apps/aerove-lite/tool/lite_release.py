"""Fail-closed exact-run acceptance artifact selection and Lite promotion."""

import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import sys
import zipfile
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "tooling"))
import product_source

import instrumentation_shards

SHARD_COUNT = instrumentation_shards.SHARD_COUNT
TOOL_DIR = Path(__file__).resolve().parent
APP_DIR = TOOL_DIR.parent
REPO = "IvanSolis1989/Aerove"
WORKFLOW = "aerove-lite.yaml"
ARTIFACT_PREFIXES = {
    "arm": "AEROVE-Lite-preview-arm64-attempt-",
    "x86": "AEROVE-Lite-runtime-contract-attempt-",
    "matrix": "AEROVE-Lite-dashboard-matrix-attempt-",
    "helper": "AEROVE-Lite-client-helper-arm64-v8a-attempt-",
}


class PromotionError(ValueError):
    pass


def _json(path):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise PromotionError(f"Duplicate JSON key: {key}")
            result[key] = value
        return result
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"), object_pairs_hook=pairs)
    except (OSError, json.JSONDecodeError) as error:
        raise PromotionError(f"Invalid JSON: {path}") from error


def _sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def _gh_json(route):
    result = subprocess.run(["gh", "api", route], check=True, capture_output=True, text=True)
    return json.loads(result.stdout)


def _gh_binary(route, destination, headers=()):
    """Stream an octet-stream gh api response directly to a controlled file."""
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    command = ["gh", "api", route]
    for header in headers:
        command.extend(("-H", header))
    try:
        with destination.open("wb") as output:
            subprocess.run(command, stdout=output, check=True)
    except (OSError, subprocess.CalledProcessError):
        destination.unlink(missing_ok=True)
        raise


def _successful_steps(job, required):
    if job.get("status") != "completed" or job.get("conclusion") != "success":
        return False
    steps = {step.get("name"): step.get("conclusion") for step in job.get("steps", [])}
    return all(steps.get(name) == "success" for name in required)


def _candidate_jobs(run, attempt, source=None):
    route = f"repos/{REPO}/actions/runs/{run}/attempts/{attempt}/jobs?per_page=100"
    jobs = _gh_items(route, "jobs") if source is not None else _gh_json(route).get("jobs", [])
    expected = {
        "resolve": ("Stable lock and host production regression",),
        "verify": ("Kotlin data, lifecycle and UI compilation checks",),
        "accepted": ("All exact-source acceptance gates passed",
                     f"Verify the {SHARD_COUNT} runtime-discovered shard sets form one complete APK test selection"),
        "acceptance (matrix)": ("Real API 33 matrix verification",),
    }
    for shard_index in range(SHARD_COUNT):
        expected[f"acceptance (remaining shard {shard_index}/{SHARD_COUNT})"] = ("Real API 33 remaining verification",)
    acceptance_names = [job.get("name") for job in jobs if str(job.get("name", "")).startswith("acceptance (")]
    expected_acceptance = [name for name in expected if name.startswith("acceptance (")]
    if sorted(acceptance_names) != sorted(expected_acceptance):
        raise PromotionError(f"Acceptance run requires exactly the matrix and {SHARD_COUNT} unique remaining shards 0..{SHARD_COUNT - 1}/{SHARD_COUNT}")
    for abi in ("arm64-v8a", "x86_64"):
        prefix = f"build ({abi}) / "
        expected[prefix + "rust"] = ("Build the pinned Rust Android bridge",)
        expected[prefix + "helper"] = ("Build the isolated client helper for this ABI",)
        expected[prefix + "package"] = (
            "Prepare the independent Lite release signing identity",
            "Signed preview ARM64 APK" if abi == "arm64-v8a" else "Emulator application and instrumentation APK",
            "Check the signed APK and record its exact provenance",
        )
    found = {}
    for key, steps in expected.items():
        matches = [job for job in jobs if job.get("name") == key and _successful_steps(job, steps)]
        if len(matches) != 1 or matches[0].get("run_id") != run or matches[0].get("run_attempt") != attempt or (source is not None and matches[0].get("head_sha") != source):
            raise PromotionError(f"Acceptance run has no unique successful {key} job")
        found[key] = matches[0]
    return found


def _gh_items(route, key):
    result, ids, page, count = [], set(), 1, None
    while True:
        payload = _gh_json(route + f"&page={page}")
        total, rows = payload.get("total_count"), payload.get(key)
        if type(total) is not int or total < 0 or not isinstance(rows, list):
            raise PromotionError("Invalid paginated GitHub API response")
        if count is None:
            count = total
        if count != total or (not rows and len(result) < count):
            raise PromotionError("Inconsistent GitHub API pagination")
        for row in rows:
            identity = row.get("id")
            if type(identity) is not int or identity < 1 or identity in ids:
                raise PromotionError("Invalid or duplicate GitHub API identity")
            ids.add(identity)
            result.append(row)
        if len(result) > count:
            raise PromotionError("GitHub API pagination exceeds total_count")
        if len(result) == count:
            return result
        page += 1


def _run_identity(run, run_id, source=None, release=False):
    if (type(run_id) is not int or run_id < 1 or run.get("id") != run_id
            or type(run.get("run_attempt")) is not int or run["run_attempt"] < 1
            or type(run.get("run_number")) is not int or run["run_number"] < 1
            or not re.fullmatch(r"[0-9a-f]{40}", str(run.get("head_sha", "")))
            or (source is not None and run.get("head_sha") != source)
            or run.get("path") != f".github/workflows/{WORKFLOW}"
            or any(not isinstance(run.get(key), dict) or run[key].get("full_name", "").casefold() != REPO.casefold()
                   for key in ("repository", "head_repository"))):
        raise PromotionError("Original/release run repository/workflow/source/identity mismatch")
    if release:
        if run.get("event") != "workflow_dispatch" or run.get("head_branch") != "main":
            raise PromotionError("Promotion must be an explicit main workflow dispatch")
    elif (run.get("event") not in {"push", "workflow_dispatch"}
            or run.get("head_branch") not in {"codex/aerove-independent-architecture", "codex/aerove-lite-meow-app"}
            or run.get("status") != "completed" or run.get("conclusion") != "success"):
        raise PromotionError("Explicit original build is not a fully accepted product run")


def accepted_build(root, source, build_run, current_run):
    if build_run == current_run:
        raise PromotionError("Promotion cannot use its own run as an accepted original build")
    run = _gh_json(f"repos/{REPO}/actions/runs/{build_run}")
    _run_identity(run, build_run)
    projection = product_source.equivalent(root=root, product="aerove-lite",
        build_source=run["head_sha"], release_source=source)
    _candidate_jobs(build_run, run["run_attempt"], run["head_sha"])
    artifacts = _gh_items(f"repos/{REPO}/actions/runs/{build_run}/artifacts?per_page=100", "artifacts")
    selected = {}
    for key, prefix in ARTIFACT_PREFIXES.items():
        rows = [item for item in artifacts if item.get("name") == prefix + str(run["run_attempt"])]
        if len(rows) != 1:
            raise PromotionError("Original artifact is missing or duplicated")
        artifact = rows[0]
        if (artifact.get("expired") is not False
                or artifact.get("workflow_run", {}).get("id") != build_run
                or artifact.get("workflow_run", {}).get("head_sha") != run["head_sha"]
                or type(artifact.get("size_in_bytes")) is not int or artifact["size_in_bytes"] < 1
                or not re.fullmatch(r"sha256:[0-9a-f]{64}", str(artifact.get("digest", "")))):
            raise PromotionError("Original artifact provenance/size/digest mismatch")
        selected[key] = {field: artifact[field] for field in ("id", "name", "digest", "size_in_bytes")}
    return dict(run_id=build_run, run_number=run["run_number"], run_attempt=run["run_attempt"],
        build_source=run["head_sha"], build_tree=projection["build_tree"], artifacts=selected, projection=projection,
        artifact_ids={key: item["id"] for key, item in selected.items()})


def promotion_receipt(root, source, release_run, release_attempt, build_run, validation, directories):
    selected = accepted_build(root, source, build_run, release_run)
    run = _gh_json(f"repos/{REPO}/actions/runs/{release_run}")
    _run_identity(run, release_run, source, release=True)
    if run["run_attempt"] != release_attempt:
        raise PromotionError("Release attempt differs from the current API run")
    expected = dict(source=selected["build_source"], tree=selected["build_tree"], run_id=build_run,
        run_number=selected["run_number"], run_attempt=selected["run_attempt"])
    if any(validation.get(key) != value for key, value in expected.items()):
        raise PromotionError("Validated original payload identity differs from selected API build")
    files = {}
    with tempfile.TemporaryDirectory() as temporary:
        for key, artifact in selected["artifacts"].items():
            archive = Path(temporary) / (key + ".zip")
            _gh_binary(f"repos/{REPO}/actions/artifacts/{artifact['id']}/zip", archive)
            files[key] = product_source.bind_archive(archive, directories[key], artifact)
    return dict(schema=1, role="originalProductArtifactPromotion", product="aerove-lite", repository=REPO,
        build_source=selected["build_source"], build_tree=selected["build_tree"],
        build_run=build_run, build_attempt=selected["run_attempt"], build_number=selected["run_number"],
        release_source=source, release_tree=selected["projection"]["release_tree"],
        release_run=release_run, release_attempt=release_attempt, compiled=False,
        artifacts=selected["artifacts"], projection=selected["projection"], files=files)


def select_acceptance(source, current_run, outputs, root=None, build_run=None):
    if not re.fullmatch(r"[0-9a-f]{40}", source):
        raise PromotionError("Full main source SHA is required")
    if build_run is not None:
        result = accepted_build(root, source, build_run, current_run)
        with Path(outputs).open("a", encoding="utf-8") as output:
            for key in ("run_id", "run_number", "run_attempt", "build_source", "build_tree"):
                output.write(f"{key}={result[key]}\n")
            output.write("artifact_ids=" + json.dumps(result["artifact_ids"], separators=(",", ":")) + "\n")
        return result
    data = _gh_json(f"repos/{REPO}/actions/workflows/{WORKFLOW}/runs?head_sha={source}&per_page=100")
    candidates = []
    for run in data.get("workflow_runs", []):
        if (run.get("id") == current_run or run.get("head_sha") != source
                or run.get("head_branch") not in ("codex/aerove-independent-architecture", "codex/aerove-lite-meow-app")
                or run.get("path", "").split("@", 1)[0] != f".github/workflows/{WORKFLOW}"
                or run.get("status") != "completed" or run.get("conclusion") != "success"
                or run.get("event") not in {"push", "workflow_dispatch"}):
            continue
        try:
            _candidate_jobs(run["id"], run["run_attempt"])
            artifacts = _gh_json(f"repos/{REPO}/actions/runs/{run['id']}/artifacts?per_page=100").get("artifacts", [])
            wanted = {key: prefix + str(run["run_attempt"]) for key, prefix in ARTIFACT_PREFIXES.items()}
            picked = {}
            for key, name in wanted.items():
                matches = [item for item in artifacts if item.get("name") == name and not item.get("expired")
                           and item.get("workflow_run", {}).get("id") == run["id"]
                           and item.get("workflow_run", {}).get("head_sha") == source]
                if len(matches) != 1:
                    raise PromotionError(f"Expected exactly one unexpired {name} artifact from this run")
                picked[key] = matches[0]
            candidates.append((run, picked))
        except (PromotionError, KeyError):
            continue
    if not candidates:
        raise PromotionError("No exact-source, full-acceptance artifact run is available; refusing to rebuild on main")
    run, artifacts = max(candidates, key=lambda entry: (entry[0].get("run_number", 0), entry[0]["id"]))
    result = {"run_id": run["id"], "run_number": run["run_number"], "run_attempt": run["run_attempt"],
              "head_sha": source, "artifact_ids": {key: value["id"] for key, value in artifacts.items()},
              "artifact_names": {key: value["name"] for key, value in artifacts.items()}}
    Path(outputs).write_text("".join(f"{key}={value}\n" for key, value in result.items()
                                    if key != "artifact_ids" and key != "artifact_names")
                             + "artifact_ids=" + json.dumps(result["artifact_ids"], separators=(",", ":")) + "\n"
                             + "artifact_names=" + json.dumps(result["artifact_names"], separators=(",", ":")) + "\n",
                             encoding="utf-8")
    print(json.dumps(result, indent=2))
    return result


def download_artifact(artifact_id, destination):
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    archive = destination.parent / (destination.name + ".zip")
    _gh_binary(f"repos/{REPO}/actions/artifacts/{artifact_id}/zip", archive)
    with zipfile.ZipFile(archive) as source:
        names = source.namelist()
        if not names or len(names) != len(set(names)):
            raise PromotionError("Artifact ZIP is empty or has duplicate entries")
        for name in names:
            path = PurePosixPath(name)
            if path.is_absolute() or ".." in path.parts or "\\" in name or not path.parts:
                raise PromotionError("Artifact contains an unsafe path")
            mode = source.getinfo(name).external_attr >> 16
            if stat.S_ISLNK(mode) or (mode and not (stat.S_ISREG(mode) or stat.S_ISDIR(mode))):
                raise PromotionError("Artifact contains a non-regular filesystem entry")
        source.extractall(destination)
    archive.unlink()


def _only_files(directory, expected):
    root = Path(directory)
    if root.is_symlink() or not root.is_dir():
        raise PromotionError(f"Missing artifact directory: {root}")
    actual = {p.name for p in root.iterdir()}
    if actual != set(expected) or any(p.is_symlink() or not p.is_file() for p in root.iterdir()):
        raise PromotionError(f"Unexpected artifact contents in {root}: {sorted(actual)}")


def _checksums(directory, expected):
    root = Path(directory)
    lines = (root / "SHA256SUMS").read_text(encoding="utf-8").splitlines()
    found = {}
    for line in lines:
        match = re.fullmatch(r"([0-9a-f]{64}) [ *]([^\\\r\n]+)", line)
        path = PurePosixPath(match[2]) if match else None
        if (match is None or not match[2] or ".." in path.parts or path.is_absolute()
                or match[2] in found):
            raise PromotionError("Invalid or duplicate checksum entry")
        found[match[2]] = match[1]
    if set(found) != set(expected):
        raise PromotionError("SHA256SUMS has missing or unexpected files")
    for name, digest in found.items():
        if _sha(root / name) != digest:
            raise PromotionError(f"Checksum mismatch: {name}")


def _validate_manifest(runtime, source, tree, run, attempt, number):
    root = Path(runtime)
    info = _json(root / "source-manifest.json")
    if (info.get("sourceRevision") != source or info.get("sourceTree") != tree
            or str(info.get("workflowRun")) != str(run) or str(info.get("runAttempt")) != str(attempt)
            or type(info.get("runAttempt")) is not int or type(info.get("runNumber")) is not int
            or info.get("runNumber") != number or info.get("abi") != "x86_64"
            or info.get("prebuiltApks") is not True or info.get("apkInspectorOutcome") != "success"):
        raise PromotionError("x86 runtime contract provenance does not match selected acceptance run")
    entries = info.get("files")
    if not isinstance(entries, list) or not entries:
        raise PromotionError("x86 runtime contract has no source file inventory")
    seen = set()
    for entry in entries:
        if not isinstance(entry, dict):
            raise PromotionError("Invalid x86 runtime file inventory entry")
        name = entry.get("path")
        path = PurePosixPath(name or "")
        if not name or path.is_absolute() or ".." in path.parts or name in seen:
            raise PromotionError("Invalid or duplicate x86 runtime file inventory path")
        seen.add(name)
        candidate = root.joinpath(*path.parts)
        if candidate.is_symlink() or not candidate.is_file() or candidate.stat().st_size != entry.get("bytes") or _sha(candidate) != entry.get("sha256"):
            raise PromotionError(f"x86 runtime inventory mismatch: {name}")
    actual = {p.relative_to(root).as_posix() for p in root.rglob("*") if p.is_file()
              and p.name not in {"SHA256SUMS", "source-manifest.json"}}
    if actual != seen:
        raise PromotionError("x86 runtime contract has unmanifested or missing files")
    _checksums(root, seen)
    if (root / "dependencies/releaseRuntimeClasspath.exit.txt").read_text().strip() != "0" or (root / "dependencies/releaseAndroidTestRuntimeClasspath.exit.txt").read_text().strip() != "0":
        raise PromotionError("x86 runtime dependency resolution did not complete")


def _verify_current_renderer(directory, runtime_root, source_data, source, tree, run, attempt, number):
    from emulator_qa79 import renderer_evidence
    expected = dict(sourceRevision=source, sourceTree=tree, workflowRun=str(run),
                    runAttempt=int(attempt), runNumber=int(number))
    proof = _json(directory / "CURRENT-RENDERER-PROOF.json")
    owner = _json(directory / "EMULATOR-OWNER.json")
    runtime = Path(runtime_root)
    apks = {name: _sha(runtime / "apks" / name)
            for name in ("app-release.apk", "app-release-androidTest.apk")}
    actual = dict(identity=expected, apks=apks,
                  runtimeManifestSha256=_sha(runtime / "source-manifest.json"),
                  apkInspectorOutcome="success")
    stdout = (directory / "emulator.stdout.log").read_text(errors="replace")
    try:
        evidence = renderer_evidence(stdout)
    except ValueError as error:
        raise PromotionError("Current renderer startup was not confirmed") from error
    command_tail = ["-port", "5554", "-avd", "test", "-no-window", "-gpu", "swangle",
                    "-no-snapshot", "-noaudio", "-no-boot-anim"]
    command = owner.get("command", [])
    cleanup = owner.get("cleanup", {})
    code = owner.get("emulatorReturnCode")
    if (any(proof.get(key) != value for key, value in actual.items())
            or proof.get("schema") != 1 or type(proof.get("schema")) is not int
            or proof.get("owner") != "emulator_current.py"
            or proof.get("deviceSerial") != "emulator-5554"
            or proof.get("gpuBackend") != "swangle"
            or proof.get("rendererBackend") != "SwANGLE (ANGLE + SwiftShader)"
            or proof.get("rendererEvidence") != evidence
            or source_data.get("deviceSerial") != "emulator-5554"
            or source_data.get("harnessRevision") != source
            or str(source_data.get("harnessRun")) != str(run)
            or str(source_data.get("appBuildRun")) != str(run)
            or owner.get("app") != expected or owner.get("runtime") != actual
            or owner.get("harnessRevision") != source or str(owner.get("harnessRun")) != str(run)
            or owner.get("rendererEvidence") != evidence
            or owner.get("result") != 0 or type(owner.get("result")) is not int
            or owner.get("acceptanceReturnCode") != 0 or type(owner.get("acceptanceReturnCode")) is not int
            or owner.get("unexpectedEmulatorExit") is not False
            or owner.get("cleanupRequested") is not True
            or set(cleanup) != {"terminationInitiated", "exitObservedBeforeTermination", "initialReturnCode", "returnCode"}
            or cleanup.get("terminationInitiated") is not True
            or cleanup.get("exitObservedBeforeTermination") is not False
            or cleanup.get("initialReturnCode") is not None
            or type(code) is not int or cleanup.get("returnCode") != code
            or owner.get("emulatorSignal") != (-code if code < 0 else None)
            or type(owner.get("ownerPid")) is not int or owner["ownerPid"] <= 0
            or type(owner.get("emulatorPid")) is not int or owner["emulatorPid"] <= 0
            or not isinstance(command, list) or len(command) != len(command_tail) + 1
            or Path(command[0]).name != "emulator" or command[1:] != command_tail):
        raise PromotionError("Current SwANGLE owner proof does not match the accepted runtime")


def _verify_matrix(root, runtime_root, source, tree, run, attempt, number):
    directory = Path(root)
    source_data = _json(directory / "SOURCE.json")
    if (source_data.get("sourceRevision") != source or source_data.get("sourceTree") != tree
            or str(source_data.get("workflowRun")) != str(run) or str(source_data.get("runAttempt")) != str(attempt)
            or str(source_data.get("runNumber")) != str(number) or source_data.get("phase") != "matrix"
            or source_data.get("deviceKind") != "CI-emulator" or source_data.get("rendererBackend") not in {"SwiftShader-indirect", "SwANGLE (ANGLE + SwiftShader)"}
            or source_data.get("prebuiltApks") is not True or source_data.get("performanceEvidence") is not False):
        raise PromotionError("Dashboard matrix provenance does not match the accepted run")
    sums = (directory / "APK_SHA256SUMS").read_text(encoding="utf-8").splitlines()
    if len(sums) != 2:
        raise PromotionError("Dashboard matrix must identify exactly two APK hashes")
    runtime = _json(Path(runtime_root) / "source-manifest.json")
    runtime_files = {entry["path"]: entry for entry in runtime["files"]}
    apk_names = set()
    for line in sums:
        match = re.fullmatch(r"([0-9a-f]{64})  ([^\r\n]+)", line)
        path = PurePosixPath(match[2]) if match else None
        if (match is None or path.is_absolute() or ".." in path.parts
                or path.name not in {"app-release.apk", "app-release-androidTest.apk"}):
            raise PromotionError("Invalid matrix APK checksum record")
        name = "apks/" + path.name
        apk_names.add(path.name)
        entry = runtime_files.get(name)
        if entry is None or entry["sha256"] != match[1]:
            raise PromotionError("Dashboard matrix APK does not match the x86 runtime contract")
    if apk_names != {"app-release.apk", "app-release-androidTest.apk"}:
        raise PromotionError("Dashboard matrix checksums do not identify both runtime APKs")
    if source_data["rendererBackend"] == "SwANGLE (ANGLE + SwiftShader)":
        _verify_current_renderer(directory, runtime_root, source_data, source, tree, run, attempt, number)
    elif (directory / "CURRENT-RENDERER-PROOF.json").exists():
        raise PromotionError("Current renderer proof cannot use a legacy renderer label")
    log = (directory / "instrumentation.log").read_text(encoding="utf-8", errors="replace")
    if ("dev.aerove.lite.ui.LiteDashboardMatrixTest" not in log or "OK (1 test)" not in log
            or "INSTRUMENTATION_CODE: -1" not in log or re.search(r"INSTRUMENTATION_(?:FAILED|ABORTED)|FAILURES!!!", log)):
        raise PromotionError("Dashboard matrix instrumentation did not prove exactly one successful real matrix test")
    gallery_spec = importlib.util.spec_from_file_location("lite_render_gallery", TOOL_DIR / "render_gallery.py")
    gallery = importlib.util.module_from_spec(gallery_spec)
    gallery_spec.loader.exec_module(gallery)
    captures = directory / "captures"
    manifests = list(captures.rglob("dashboard-matrix/manifest.json"))
    if len(manifests) != 1:
        raise PromotionError("Expected one strict dashboard matrix manifest")
    matrix_data = _json(manifests[0])
    cases = matrix_data.get("cases")
    if not isinstance(cases, list) or len(cases) != 12:
        raise PromotionError("Dashboard matrix must contain twelve capture records")
    for case in cases:
        if not isinstance(case, dict):
            raise PromotionError("Invalid dashboard matrix capture record")
        for field in ("metadata", "windowImage", "displayImage"):
            name = case.get(field, "")
            if (not isinstance(name, str) or not name or name in {".", ".."}
                    or "/" in name or "\\" in name or "\0" in name):
                raise PromotionError("Dashboard matrix capture path is unsafe")
        _json(manifests[0].parent / case["metadata"])
    if gallery.gallery(captures, source, directory / "readback-gallery.html") != 12:
        raise PromotionError("Expected twelve validated dashboard captures")


def validate_artifacts(arm, helper, runtime, matrix, source, tree, run, attempt, number, helper_source,
                       arm_badging, arm_signature, x86_badging, x86_signature):
    arm = Path(arm); helper = Path(helper); runtime = Path(runtime); matrix = Path(matrix)
    _only_files(arm, {"AEROVE-Lite-android-arm64.apk", "BUILD_INFO.json", "SHA256SUMS"})
    _checksums(arm, {"AEROVE-Lite-android-arm64.apk", "BUILD_INFO.json"})
    build = _json(arm / "BUILD_INFO.json")
    if (build.get("product") != "AEROVE Lite" or build.get("channel") != "preview"
            or build.get("application_id") != "dev.aerove.lite" or build.get("min_sdk") != 33
            or build.get("abi") != "arm64-v8a" or build.get("source") != source or build.get("tree") != tree
            or build.get("workflow") != f"https://github.com/{REPO}/actions/runs/{run}"
            or type(build.get("run_number")) is not int or type(build.get("run_attempt")) is not int
            or build.get("run_number") != number or build.get("run_attempt") != attempt
            or build.get("apk_sha256") != _sha(arm / "AEROVE-Lite-android-arm64.apk")):
        raise PromotionError("ARM64 Build Info does not match the exact accepted source/run/attempt")
    tag = f"lite-v{build['version']}+{build['version_code']}"
    _validate_manifest(runtime, source, tree, run, attempt, number)
    _verify_matrix(matrix, runtime, source, tree, run, attempt, number)
    helper_receipts = list(helper.rglob("build-receipt.json"))
    if len(helper_receipts) != 1:
        raise PromotionError("Expected exactly one ARM64 helper build receipt")
    verifier_spec = importlib.util.spec_from_file_location("lite_verify_apk", TOOL_DIR / "verify_apk.py")
    verifier = importlib.util.module_from_spec(verifier_spec); verifier_spec.loader.exec_module(verifier)
    core_identity = verifier.native_identity()
    if any(build.get(key) != value for key, value in core_identity.items()):
        raise PromotionError("ARM64 Build Info does not identify the fixed Meow source/lock/overlay")
    workflow_identity = dict(workflowSourceSha=source, workflowRun=str(run), workflowAttempt=str(attempt))
    arm_result = verifier.verify(arm / "AEROVE-Lite-android-arm64.apk", "arm64-v8a", helper_source, helper_receipts[0], build.get("native_build_receipt", {}), workflow_identity)
    product = verifier.product_version(APP_DIR / "version.properties")
    arm_identity = verifier.verify_identity(Path(arm_badging).read_text(encoding="utf-8"),
        Path(arm_signature).read_text(encoding="utf-8"), product, "preview")
    x86_identity = verifier.verify_identity(Path(x86_badging).read_text(encoding="utf-8"),
        Path(x86_signature).read_text(encoding="utf-8"), product, "preview")
    stored_x86_identity = verifier.verify_identity((runtime / "lite-badging.txt").read_text(encoding="utf-8"),
        (runtime / "lite-signature.txt").read_text(encoding="utf-8"), product, "preview")
    if (arm_result.get("core_sha256") != build.get("core_sha256")
            or arm_result.get("client_helpers") != build.get("client_helpers")
            or arm_result.get("client_helper_source_sha256") != build.get("client_helper_source_sha256")
            or arm_result.get("client_helper_license_assets") != build.get("client_helper_license_assets")):
        raise PromotionError("ARM64 packaged native/helper readback differs from Build Info")
    x86apk = runtime / "apks/app-release.apk"
    x86_result = verifier.verify(x86apk, "x86_64", helper_source, runtime / "client-helper-build-receipt.json",
        _json(runtime / "BUILD_INFO.json").get("native_build_receipt", {}), workflow_identity)
    x86_info = _json(runtime / "BUILD_INFO.json")
    if (x86_info.get("product") != "AEROVE Lite" or x86_info.get("channel") != "preview"
            or x86_info.get("application_id") != "dev.aerove.lite" or x86_info.get("min_sdk") != 33
            or x86_info.get("version") != build.get("version") or x86_info.get("version_code") != build.get("version_code")
            or x86_info.get("signer_sha256") != build.get("signer_sha256")
            or any(x86_info.get(key) != value for key, value in core_identity.items())
            or x86_info.get("source") != source or x86_info.get("tree") != tree
            or type(x86_info.get("run_number")) is not int or type(x86_info.get("run_attempt")) is not int
            or x86_info.get("run_number") != number
            or x86_info.get("run_attempt") != attempt or x86_info.get("abi") != "x86_64"
            or x86_info.get("apk_sha256") != _sha(x86apk) or x86_result.get("core_sha256") != x86_info.get("core_sha256")
            or x86_result.get("client_helpers") != x86_info.get("client_helpers")
            or x86_result.get("client_helper_source_sha256") != x86_info.get("client_helper_source_sha256")
            or x86_result.get("client_helper_license_assets") != x86_info.get("client_helper_license_assets")
            or arm_identity != x86_identity or x86_identity != stored_x86_identity
            or arm_identity.get("version") != build.get("version")
            or arm_identity.get("version_code") != build.get("version_code")
            or arm_identity.get("signer_sha256") != build.get("signer_sha256")):
        raise PromotionError("x86 APK native/helper provenance does not match the runtime contract")
    return {"tag": tag, "apk_sha256": _sha(arm / "AEROVE-Lite-android-arm64.apk"), "build_info": build,
            "run_id": run, "run_number": number, "run_attempt": attempt, "source": source, "tree": tree,
            "x86_apk_sha256": _sha(x86apk), "matrix_captures": 12}


def _release_asset_bytes(release_id, asset, destination):
    _gh_binary(f"repos/{REPO}/releases/assets/{asset['id']}", destination,
               ("Accept: application/octet-stream",))


def _assert_release_not_latest(release_id):
    try:
        latest = _gh_json(f"repos/{REPO}/releases/latest")
    except (PromotionError, subprocess.CalledProcessError, json.JSONDecodeError) as error:
        raise PromotionError("Could not verify that the Lite preview is not the repository latest release") from error
    if str(latest.get("id")) == str(release_id):
        raise PromotionError("Lite preview unexpectedly became the repository latest release")


def _verify_release_asset_bytes(release, directory, expected, readback):
    readback.mkdir(parents=True, exist_ok=True)
    for asset in release["assets"]:
        target = readback / asset["name"]
        _release_asset_bytes(release["id"], asset, target)
        if (_sha(target) != expected[asset["name"]]["sha256"]
                or target.stat().st_size != expected[asset["name"]]["size"]):
            raise PromotionError(f"Release asset readback differs: {asset['name']}")


def _published_release_is_exact(release, tag, source, body, expected):
    return (release.get("draft") is False and release.get("prerelease") is True
            and release.get("tag_name") == tag and release.get("target_commitish") == source
            and release.get("name") == f"AEROVE Lite {tag}" and release.get("body") == body
            and len(release.get("assets", [])) == len(expected)
            and {asset.get("name") for asset in release.get("assets", [])} == set(expected)
            and all(asset.get("size") == expected[asset["name"]]["size"]
                    for asset in release.get("assets", []) if asset.get("name") in expected))


def _assert_exact_published_release(release, tag, source, body, expected):
    if (_tag_commit(tag) != source
            or not _published_release_is_exact(release, tag, source, body, expected)):
        raise PromotionError("Published Lite tag is immutable and does not exactly match this accepted artifact")


def _current_main_inputs(root, source):
    subprocess.run(["git", "fetch", "--no-tags", "--recurse-submodules=no", "origin", "main"],
        cwd=root, check=True, capture_output=True)
    main = subprocess.check_output(["git", "rev-parse", "FETCH_HEAD"], cwd=root, text=True).strip()
    subprocess.run(["git", "merge-base", "--is-ancestor", source, main],
        cwd=root, check=True, capture_output=True)
    return product_source.equivalent(root=root, product="aerove-lite",
        build_source=source, release_source=main)


def publish(directory, validated, notes, source, run, attempt, number, receipt=None, root=None, release_run=None, release_attempt=None):
    directory = Path(directory)
    validation = _json(validated)
    if receipt is None:
        raise PromotionError("Publishing requires a separately verified original build promotion receipt")
    promotion = _json(receipt)
    build_source = promotion["build_source"] if promotion is not None else source
    if promotion is not None:
        if root is None or release_run is None or release_attempt is None:
            raise PromotionError("Promotion receipt requires release root/run/attempt")
        selected = accepted_build(root, source, run, release_run)
        current = _gh_json(f"repos/{REPO}/actions/runs/{release_run}")
        _run_identity(current, release_run, source, release=True)
        if (promotion.get("schema") != 1 or promotion.get("role") != "originalProductArtifactPromotion"
                or promotion.get("repository") != REPO or promotion.get("product") != "aerove-lite"
                or promotion.get("compiled") is not False or promotion.get("projection") != selected["projection"]
                or promotion.get("artifacts") != selected["artifacts"]
                or promotion.get("build_run") != run or promotion.get("build_attempt") != attempt
                or promotion.get("build_number") != number or promotion.get("build_tree") != selected["build_tree"]
                or build_source != selected["build_source"] or promotion.get("release_source") != source
                or promotion.get("release_tree") != selected["projection"]["release_tree"]
                or promotion.get("release_run") != release_run or promotion.get("release_attempt") != release_attempt
                or current["run_attempt"] != release_attempt):
            raise PromotionError("Promotion receipt differs from original API build/release/projection")
    tag = validation.get("tag", "")
    if not re.fullmatch(r"lite-v[^+\s/]+\+[1-9][0-9]*", tag):
        raise PromotionError("Invalid Lite release tag")
    build = validation.get("build_info", {})
    if (validation.get("source") != build_source or validation.get("run_id") != run
            or validation.get("run_number") != number or validation.get("run_attempt") != attempt
            or build.get("source") != build_source or build.get("run_number") != number
            or build.get("run_attempt") != attempt or build.get("workflow") != f"https://github.com/{REPO}/actions/runs/{run}"):
        raise PromotionError("Validated artifact does not bind the selected source and attempt")
    apk = directory / "AEROVE-Lite-android-arm64.apk"
    info = directory / "BUILD_INFO.json"
    sums = directory / "SHA256SUMS"
    _only_files(directory, {apk.name, info.name, sums.name})
    _checksums(directory, {apk.name, info.name})
    if (_json(info) != build or validation.get("tree") != promotion.get("build_tree")
            or build.get("tree") != promotion.get("build_tree")):
        raise PromotionError("Original Build Info bytes/tree differ from the validated promotion identity")
    expected = {path.name: {"sha256": _sha(path), "size": path.stat().st_size}
                for path in (apk, info, sums)}
    if promotion is not None:
        actual = {name: dict(bytes=item["size"], sha256=item["sha256"]) for name, item in expected.items()}
        if promotion.get("files", {}).get("arm") != actual:
            raise PromotionError("Original three payload bytes differ from promotion receipt")
    if validation.get("apk_sha256") != expected[apk.name]["sha256"]:
        raise PromotionError("Validated APK hash differs from original artifact bytes")
    if tag != f"lite-v{build.get('version')}+{build.get('version_code')}":
        raise PromotionError("Promotion tag does not match original APK Build Info")
    body = Path(notes).read_text(encoding="utf-8")
    marker = (f"\n\nPromotion receipt\n\nAcceptance run: `{run}` (run number `{number}`, attempt `{attempt}`)\n"
              f"Build source: `{build_source}`\nRelease source: `{source}`\nAPK SHA-256: `{expected[apk.name]['sha256']}`\n")
    body += marker
    bound_notes = Path(os.environ.get("RUNNER_TEMP", ".")) / "lite-bound-release-notes.md"
    bound_notes.write_text(body, encoding="utf-8")
    _current_main_inputs(root, source)
    releases = _gh_json(f"repos/{REPO}/releases/tags/{tag}") if _has_release(tag) else None
    if releases is not None:
        if releases.get("draft") is False:
            _assert_exact_published_release(releases, tag, source, body, expected)
            _verify_release_asset_bytes(releases, directory, expected,
                                        Path(os.environ.get("RUNNER_TEMP", ".")) / "lite-release-idempotent-readback")
            _assert_release_not_latest(releases["id"])
            summary = Path(os.environ.get("GITHUB_STEP_SUMMARY", "lite-release-summary.md"))
            summary.write_text(f"Lite release `{tag}` already matches acceptance run `{run}` attempt `{attempt}`.\n\n"
                f"Source `{source}`; original ARM64 APK SHA-256 `{expected[apk.name]['sha256']}`.\n"
                "Existing published asset bytes were read back and verified; no assets or tag were modified.\n", encoding="utf-8")
            return
        if releases.get("draft") is not True:
            raise PromotionError("Existing Lite release has an invalid draft state")
        if (releases.get("target_commitish") != source or (releases.get("body") or "").strip() != body.strip()
                or releases.get("prerelease") is not True):
            raise PromotionError("Existing draft does not match the exact accepted Lite artifact")
        if _tag_commit(tag) != source:
            raise PromotionError("Existing draft tag does not point at the accepted source commit")
        release = releases
    else:
        if _has_tag(tag):
            raise PromotionError("Lite release tag already exists without a matching draft")
        release = subprocess.run([
            "gh", "api", "--method", "POST", f"repos/{REPO}/releases",
            "-f", f"tag_name={tag}", "-f", f"target_commitish={source}",
            "-F", "draft=true", "-F", "prerelease=true", "-f", f"name=AEROVE Lite {tag}",
            "-f", "make_latest=false", "-F", f"body=@{bound_notes}",
        ], check=True, capture_output=True, text=True)
        created = json.loads(release.stdout)
        release = _gh_json(f"repos/{REPO}/releases/{created['id']}")
        if _tag_commit(tag) != source:
            raise PromotionError("New Lite tag does not point at the accepted source commit")
        if (release.get("body") or "").strip() != body.strip():
            raise PromotionError("Could not bind draft release notes to acceptance run and APK hash")
    wanted = set(expected)
    existing = {}
    for asset in release.get("assets", []):
        if asset.get("name") in existing or asset.get("name") not in wanted:
            raise PromotionError("Draft contains duplicate or unexpected release assets")
        existing[asset["name"]] = asset
    for name, path in ((apk.name, apk), (info.name, info), (sums.name, sums)):
        if name not in existing:
            subprocess.run(["gh", "release", "upload", tag, str(path), "--repo", REPO], check=True)
    release = _gh_json(f"repos/{REPO}/releases/{release['id']}")
    if {asset["name"] for asset in release.get("assets", [])} != wanted:
        raise PromotionError("Draft release asset set is incomplete or contains extras")
    _verify_release_asset_bytes(release, directory, expected,
                                Path(os.environ.get("RUNNER_TEMP", ".")) / "lite-release-readback")
    if release.get("draft") is not True or release.get("prerelease") is not True:
        raise PromotionError("Release ceased to be a preview draft before readback")
    _current_main_inputs(root, source)
    subprocess.run(["gh", "api", "--method", "PATCH", f"repos/{REPO}/releases/{release['id']}",
                    "-F", "draft=false", "-F", "prerelease=true", "-f", "make_latest=false"], check=True)
    published = _gh_json(f"repos/{REPO}/releases/{release['id']}")
    if not _published_release_is_exact(published, tag, source, body, expected):
        raise PromotionError("Release publish readback did not retain preview status and exact tag")
    _assert_release_not_latest(published["id"])
    summary = Path(os.environ.get("GITHUB_STEP_SUMMARY", "lite-release-summary.md"))
    summary.write_text(f"Lite release `{tag}` published from acceptance run `{run}` attempt `{attempt}`.\n\n"
        f"Source `{source}`; original ARM64 APK SHA-256 `{expected[apk.name]['sha256']}`.\n"
        f"All three release assets were downloaded and hash-verified before publication.\n", encoding="utf-8")


def _has_release(tag):
    result = subprocess.run(["gh", "api", f"repos/{REPO}/releases/tags/{tag}"], capture_output=True, text=True)
    if result.returncode == 0:
        return True
    if "Not Found" in result.stderr or "HTTP 404" in result.stderr:
        return False
    raise PromotionError("Could not determine whether the Lite release tag already exists")


def _has_tag(tag):
    result = subprocess.run(["gh", "api", f"repos/{REPO}/git/ref/tags/{tag}"], capture_output=True, text=True)
    if result.returncode == 0:
        return True
    if "Not Found" in result.stderr or "HTTP 404" in result.stderr:
        return False
    raise PromotionError("Could not determine whether the Lite Git tag already exists")


def _tag_commit(tag):
    reference = _gh_json(f"repos/{REPO}/git/ref/tags/{tag}")
    obj = reference.get("object", {})
    for _ in range(4):
        if obj.get("type") == "commit":
            return obj.get("sha")
        if obj.get("type") != "tag" or not re.fullmatch(r"[0-9a-f]{40}", obj.get("sha", "")):
            break
        obj = _gh_json(f"repos/{REPO}/git/tags/{obj['sha']}").get("object", {})
    raise PromotionError("Lite tag does not resolve to a commit")


def previous_changelog(output, target_tag):
    target = re.fullmatch(r"lite-v(.+)\+([1-9][0-9]*)", target_tag or "")
    if target is None:
        raise PromotionError("Target Lite release tag is required to select the true previous release")
    target_code = int(target[2])
    releases = _gh_json(f"repos/{REPO}/releases?per_page=100")
    if len(releases) == 100:
        raise PromotionError("Lite release history exceeds one page; refusing to guess the previous release")
    candidates = []
    for release in releases:
        if release.get("draft"):
            continue
        match = re.fullmatch(r"lite-v(.+)\+([1-9][0-9]*)", release.get("tag_name", ""))
        if match and int(match[2]) < target_code:
            candidates.append((int(match[2]), release))
    if not candidates:
        Path(output).write_text("", encoding="utf-8")
        return
    highest = max(code for code, _ in candidates)
    selected = [release for code, release in candidates if code == highest]
    if len(selected) != 1:
        raise PromotionError("Previous Lite release code is ambiguous")
    release = selected[0]
    assets = [asset for asset in release.get("assets", []) if asset.get("name") == "BUILD_INFO.json"]
    if len(assets) != 1:
        raise PromotionError("Previous Lite release has no unique BUILD_INFO.json")
    temp = Path(output).with_suffix(".previous-build-info.json")
    _release_asset_bytes(release["id"], assets[0], temp)
    info = _json(temp)
    if (info.get("version_code") != highest or info.get("version") != release["tag_name"].split("lite-v", 1)[1].rsplit("+", 1)[0]
            or not re.fullmatch(r"[0-9a-f]{40}", info.get("source", ""))):
        raise PromotionError("Previous Lite Build Info does not match its release tag")
    route = f"repos/{REPO}/contents/docs/aerove-lite/CHANGELOG.md?ref={info['source']}"
    content = _gh_json(route)
    if content.get("encoding") != "base64" or not isinstance(content.get("content"), str):
        raise PromotionError("Previous Lite source changelog is unavailable")
    try:
        markdown = base64.b64decode("".join(content["content"].split()), validate=True).decode("utf-8")
    except (ValueError, UnicodeDecodeError) as error:
        raise PromotionError("Previous Lite source changelog is invalid") from error
    Path(output).write_text(markdown, encoding="utf-8")
    temp.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    select = sub.add_parser("select")
    select.add_argument("--source", required=True); select.add_argument("--current-run", required=True, type=int)
    select.add_argument("--root", type=Path)
    select.add_argument("--build-run", type=int)
    select.add_argument("--outputs", type=Path, default=Path(os.environ.get("GITHUB_OUTPUT", "promotion-outputs.txt")))
    extract = sub.add_parser("extract")
    extract.add_argument("--artifact-id", required=True, type=int); extract.add_argument("--destination", required=True, type=Path)
    validate = sub.add_parser("validate")
    for name in ("arm", "helper", "runtime", "matrix", "helper-source"):
        validate.add_argument("--" + name, required=True, type=Path)
    for name in ("arm-badging", "arm-signature", "x86-badging", "x86-signature"):
        validate.add_argument("--" + name, required=True, type=Path)
    for name in ("source", "tree"):
        validate.add_argument("--" + name, required=True)
    for name in ("run", "attempt", "number"):
        validate.add_argument("--" + name, required=True, type=int)
    validate.add_argument("--root", type=Path)
    validate.add_argument("--release-source")
    validate.add_argument("--release-run", type=int)
    validate.add_argument("--release-attempt", type=int)
    validate.add_argument("--receipt", type=Path)
    validate.add_argument("--output", type=Path, required=True)
    publish_parser = sub.add_parser("publish")
    for name in ("directory", "validated", "notes"):
        publish_parser.add_argument("--" + name, required=True, type=Path)
    for name in ("source",):
        publish_parser.add_argument("--" + name, required=True)
    for name in ("run", "attempt"):
        publish_parser.add_argument("--" + name, required=True, type=int)
    publish_parser.add_argument("--number", required=True, type=int)
    publish_parser.add_argument("--root", type=Path, required=True)
    publish_parser.add_argument("--receipt", type=Path, required=True)
    publish_parser.add_argument("--release-run", type=int, required=True)
    publish_parser.add_argument("--release-attempt", type=int, required=True)
    previous = sub.add_parser("previous-changelog")
    previous.add_argument("--output", required=True, type=Path)
    previous.add_argument("--target-tag", required=True)
    args = parser.parse_args()
    try:
        if args.command == "select":
            if args.root is None or args.build_run is None:
                raise PromotionError("Publishing requires an explicit accepted --build-run and --root; never rebuild")
            select_acceptance(args.source, args.current_run, args.outputs, args.root, args.build_run)
        elif args.command == "extract":
            download_artifact(args.artifact_id, args.destination)
        elif args.command == "validate":
            result = validate_artifacts(args.arm, args.helper, args.runtime, args.matrix, args.source,
                args.tree, args.run, args.attempt, args.number, args.helper_source,
                args.arm_badging, args.arm_signature, args.x86_badging, args.x86_signature)
            promotion_args = (args.root, args.release_source, args.release_run, args.release_attempt, args.receipt)
            if any(value is not None for value in promotion_args):
                if any(value is None for value in promotion_args):
                    raise PromotionError("Release root/source/run/attempt/receipt must be supplied together")
                receipt = promotion_receipt(args.root, args.release_source, args.release_run, args.release_attempt,
                    args.run, result, dict(arm=args.arm, helper=args.helper, x86=args.runtime, matrix=args.matrix))
                args.receipt.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
            args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
            print(json.dumps(result, indent=2))
        elif args.command == "previous-changelog":
            previous_changelog(args.output, args.target_tag)
        else:
            publish(args.directory, args.validated, args.notes, args.source, args.run, args.attempt, args.number,
                args.receipt, args.root, args.release_run, args.release_attempt)
    except (PromotionError, ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        print(f"Lite promotion refused: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
