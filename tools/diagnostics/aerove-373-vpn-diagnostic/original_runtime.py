"""Pinned original #373 runtime. No signing, producing, or publishing APKs."""
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import zipfile

from ci_artifacts import verify
from consent_gate import need
from flutter_android_qa import sha
from lite_release import _gh_binary, _gh_json
from verify_apk import verify_bundled_script_apk

REPO = "IvanSolis1989/Aerove"
IDENTITY = dict(sourceRevision="d1b2db996af6142fbb299059c4de1b50df7dac38",
                sourceTree="04dd07698f78b6b3fcf21bf5325f2f68a1483be5",
                workflowRun="45115924455", runAttempt=1, runNumber=373)
ARTIFACT = 14793448475
BYTES = 59914616
DIGEST = "b08edc6fdd6b23717485290cfe6221103be50c237c7944b451c2e27559f9def9"
NAME = "AEROVE-Lite-runtime-contract-attempt-1"
FILES = (("app-release.apk", "release"),
         ("app-release-androidTest.apk", "androidTest/release"))


def unpack(archive, directory):
    need(archive.is_file() and not archive.is_symlink() and archive.stat().st_size == BYTES
         and sha(archive) == DIGEST, "Pinned original runtime download differs")
    need(not directory.exists(), "Refusing to overlay existing runtime directory")
    with zipfile.ZipFile(archive) as source:
        entries = source.infolist()
        need(0 < len(entries) <= 600 and len({x.filename for x in entries}) == len(entries)
             and sum(x.file_size for x in entries) <= 260 * 1024 * 1024, "Unexpected runtime ZIP inventory size")
        for row in entries:
            name = row.filename.rstrip("/")
            path = PurePosixPath(name)
            kind = stat.S_IFMT(row.external_attr >> 16)
            need(bool(name) and not path.is_absolute() and str(path) == name and "\\" not in name
                 and all(part != ".." for part in path.parts) and not row.flag_bits & 1
                 and kind in (0, stat.S_IFREG if not row.is_dir() else stat.S_IFDIR),
                 "Unsafe original runtime entry")
        directory.mkdir()
        source.extractall(directory)
    return verify(directory, IDENTITY, runtime=True)


def fetch(directory, phase):
    phase("artifact-metadata")
    need(os.environ["GITHUB_REPOSITORY"] == REPO and os.environ["GITHUB_EVENT_NAME"] == "push",
         "Only the isolated push workflow can fetch the original runtime")
    rows = _gh_json(f"repos/{REPO}/actions/runs/{IDENTITY['workflowRun']}/artifacts?per_page=100")["artifacts"]
    candidates = [r for r in rows if r.get("name") == NAME]
    need(len(candidates) == 1, "Original runtime metadata missing/ambiguous")
    row = candidates[0]
    origin = row.get("workflow_run") or {}
    need(row.get("id") == ARTIFACT and row.get("expired") is False
         and row.get("size_in_bytes") == BYTES and row.get("digest") == "sha256:" + DIGEST
         and origin.get("id") == int(IDENTITY["workflowRun"])
         and origin.get("head_sha") == IDENTITY["sourceRevision"], "Original runtime metadata differs")
    archive = directory.with_suffix(".zip")
    need(not archive.exists() and not directory.exists(), "Original artifact staging must be new")
    phase("artifact-download")
    _gh_binary(f"repos/{REPO}/actions/artifacts/{ARTIFACT}/zip", archive)
    phase("artifact-verification")
    unpack(archive, directory)


def stage(directory, apk_root):
    need(not apk_root.exists(), "Refusing to overlay an existing build")
    for name, subdir in FILES:
        dest = apk_root / subdir / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(directory / "apks" / name, dest)


def pinned_runtime(directory, apk_root):
    directory = Path(directory)
    archive = directory.with_suffix(".zip")
    need(archive.is_file() and not archive.is_symlink() and archive.stat().st_size == BYTES
         and sha(archive) == DIGEST, "Original runtime ZIP proof missing")
    manifest = verify(directory, IDENTITY, runtime=True)
    apks = {}
    for name, subdir in FILES:
        path = apk_root / subdir / name
        need(not path.is_symlink() and sha(path) == sha(directory / "apks" / name),
             "Local APK differs from the sealed original")
        apks[name] = sha(path)
    script = verify_bundled_script_apk(directory / "apks/app-release.apk")
    return dict(identity=dict(IDENTITY), apks=apks, runtimeManifestSha256=sha(directory / "source-manifest.json"),
                apkInspectorOutcome=manifest["apkInspectorOutcome"], bundledMeowScript=script)
