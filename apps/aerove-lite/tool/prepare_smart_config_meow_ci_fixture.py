"""Acquire immutable CI Geo inputs and materialize the fixed Smart Git fixture.

Network acquisition is available only with --download under GitHub Actions.
Local verification replays existing bytes with --offline-geodata; it never fetches.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request

from prepare_smart_config_meow_current_fixture import COMMIT, SCRIPT, SCRIPT_SHA, prepare

MANIFEST_SHA = "e956168cfab6da7befaf2aa4d442b18a1dd8f7165398b5a9b613caacdd7bc2f4"
PINNED_GEO = {
    "mmdb": dict(file="Country.mmdb", repository="Loyalsoldier/geoip",
        commit="7ae41d7f3d4e7acc37acd1b02e554b7bb85f3a8d",
        gitBlobSha1="eb39fa51e8d4fb167f429a75cd1492f061962134", bytes=7649827,
        sha256="ccec4d6fa69de3a79960a460c377ac9a23c1a90e5f9052dae3488921e56201df"),
    "geosite": dict(file="geosite.dat", repository="MetaCubeX/meta-rules-dat",
        commit="bfa68b978ec9c0494203d7c0d64e186640f7257b",
        gitBlobSha1="453b6468c34adee2f428c569162c05471ce1d504", bytes=4311865,
        sha256="610e2fa2b57b1232ba2220eeea45eb6e0bbf3675e19ac6ee2d29e8eb3e294b10"),
}
for spec in PINNED_GEO.values():
    spec["url"] = f"https://raw.githubusercontent.com/{spec['repository']}/{spec['commit']}/{spec['file']}"
ASN = dict(file="ASN.mmdb", bytes=10789175,
           gitBlobSha1="c654595f1ba22d31396dbd8f7ce731ae0b6a298c",
           sha256="c0507cfb091f42ac525f4eb8a0051a7897f5fac72e852799e01096583566033b")
ASN_PATH = "apps/aerove/assets/data/ASN.mmdb"
SMART_TREE = "5ae653737c826b64452536fec4df35c02795a9a4"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def blob_sha(data):
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()


def verified_bytes(path, spec):
    path = Path(path)
    if path.is_symlink() or path.stat().st_size != spec["bytes"]:
        raise ValueError("Fixture size/type mismatch: " + str(path))
    data = path.read_bytes()
    if sha(data) != spec["sha256"] or blob_sha(data) != spec["gitBlobSha1"]:
        raise ValueError("Fixture hash mismatch: " + str(path))
    return data


class FixedRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        destination = urllib.parse.urlsplit(new_url)
        if destination.scheme != "https" or destination.netloc != "raw.githubusercontent.com":
            raise ValueError("Unexpected immutable fixture redirect")
        return super().redirect_request(request, fp, code, message, headers, new_url)


def _open_fixed(request):
    return urllib.request.build_opener(FixedRedirect()).open(request, timeout=15)


def acquire_geo(spec, cache, download=False):
    destination = Path(cache) / spec["file"]
    if destination.exists():
        verified_bytes(destination, spec)
        return destination, dict(spec, acquisition="verified-cache")
    if not download or os.environ.get("GITHUB_ACTIONS") != "true":
        raise ValueError("Geo acquisition requires an existing verified file or explicit CI --download")
    Path(cache).mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        deadline = time.monotonic() + 60
        request = urllib.request.Request(spec["url"], headers={"Accept-Encoding": "identity", "User-Agent": "Aerove-CI-fixture"})
        with _open_fixed(request) as response, tempfile.NamedTemporaryFile(dir=cache, suffix=".part", delete=False) as output:
            temporary = Path(output.name)
            declared = response.headers.get("Content-Length")
            if response.status != 200 or (declared is not None and int(declared) != spec["bytes"]):
                raise ValueError("Immutable Geo response status/size mismatch")
            size = 0
            while True:
                if time.monotonic() > deadline:
                    raise ValueError("Geo fixture acquisition deadline exceeded")
                data = response.read(65536)
                if not data:
                    break
                size += len(data)
                if size > spec["bytes"]:
                    raise ValueError("Geo fixture byte bound exceeded")
                output.write(data)
        verified_bytes(temporary, spec)
        os.replace(temporary, destination)
        return destination, dict(spec, acquisition="fixed-commit-ci-fetch")
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def load_verified_fixture(root):
    """Return an immutable in-memory allowlist; no filesystem route translation."""
    root = Path(root)
    if root.is_symlink():
        raise ValueError("Fixture root cannot be a symbolic link")
    root = root.resolve(strict=True)
    paths = list(root.rglob("*"))
    if any(path.is_symlink() for path in paths):
        raise ValueError("Symbolic links are forbidden in the controlled fixture")
    manifest_path = root / "manifest.json"
    if manifest_path.stat().st_size > 512 * 1024:
        raise ValueError("Fixture manifest byte bound exceeded")
    manifest_bytes = manifest_path.read_bytes()
    if sha(manifest_bytes) != MANIFEST_SHA:
        raise ValueError("Pinned Smart fixture manifest mismatch")
    manifest = json.loads(manifest_bytes)
    if manifest["smartCommit"] != COMMIT or len(manifest["providers"]) != 151:
        raise ValueError("Pinned Smart fixture source/count mismatch")
    entries = [manifest["script"]] + manifest["providers"] + manifest["geo"]
    files = {"manifest.json": manifest_bytes}
    for entry in entries:
        relative = PurePosixPath(entry["file"])
        if relative.is_absolute() or ".." in relative.parts or entry["file"] in files:
            raise ValueError("Unsafe or duplicate fixture path")
        path = root / relative
        if path.is_symlink() or not path.resolve().is_relative_to(root):
            raise ValueError("Fixture path escaped the controlled directory")
        if path.stat().st_size != entry["bytes"]:
            raise ValueError("Fixture saved-file size mismatch: " + str(relative))
        data = path.read_bytes()
        if len(data) != entry["bytes"] or sha(data) != entry["sha256"]:
            raise ValueError("Fixture saved-file readback mismatch: " + str(relative))
        files[entry["file"]] = data
    actual = {path.relative_to(root).as_posix() for path in paths if path.is_file()}
    if len(files) != 156 or actual != set(files):
        raise ValueError("Unexpected or missing files in controlled Smart fixture")
    return files


def verify_receipt(path, source, tree):
    path = Path(path)
    if path.is_symlink() or path.stat().st_size > 512 * 1024:
        raise ValueError("Invalid fixture source receipt")
    receipt = json.loads(path.read_text(encoding="utf-8"))
    expected = dict(schema=1, appSourceRevision=source, appSourceTree=tree,
                    smartCommit=COMMIT, smartTree=SMART_TREE,
                    fixtureManifestSha256=MANIFEST_SHA, fixtureFiles=156, fixtureDataBytes=27298874)
    if any(receipt.get(key) != value for key, value in expected.items()):
        raise ValueError("Fixture receipt differs from actual app/source identity")
    if len(receipt.get("providers", [])) != 151 or receipt["script"]["sha256"] != SCRIPT_SHA:
        raise ValueError("Fixture receipt script/provider mismatch")
    if len(receipt.get("geo", [])) != 2:
        raise ValueError("Fixture receipt Geo count mismatch")
    for actual, fixed in zip(receipt["geo"], PINNED_GEO.values()):
        if any(actual.get(key) != value for key, value in fixed.items()):
            raise ValueError("Fixture receipt Geo origin mismatch")
    if any(receipt["asn"].get(key) != value for key, value in dict(ASN, sourcePath=ASN_PATH, sourceRevision=source).items()):
        raise ValueError("Fixture receipt ASN source mismatch")
    return receipt


def materialize(args):
    app = args.app_repository.resolve(strict=True)
    app_source = subprocess.check_output(["git", "-C", str(app), "rev-parse", "HEAD"], text=True).strip()
    if os.environ.get("GITHUB_ACTIONS") == "true" and app_source != os.environ.get("GITHUB_SHA"):
        raise ValueError("Fixture receipt source differs from CI checkout")
    source_asn = subprocess.check_output(["git", "-C", str(app), "show", app_source + ":" + ASN_PATH])
    asn = app / ASN_PATH
    if source_asn != verified_bytes(asn, ASN):
        raise ValueError("ASN checkout differs from its tracked source blob")
    geos, acquisitions = {}, []
    for key, spec in PINNED_GEO.items():
        if args.offline_geodata:
            path = args.offline_geodata / spec["file"]
            verified_bytes(path, spec)
            receipt = dict(spec, acquisition="offline-existing-byte-replay")
        else:
            path, receipt = acquire_geo(spec, args.geo_cache, args.download)
        geos[key] = path
        acquisitions.append(receipt)
    output = args.output
    if output.exists() and any(output.iterdir()):
        raise ValueError("Fixture output must be a new or empty directory")
    # Fixed Git bytes and bounded Node execution determine the current shard projection.
    prepare(output, args.smart_repository, None, dict(geos, **{"asn-mmdb": asn}))
    files = load_verified_fixture(output)
    manifest = json.loads(files["manifest.json"])
    providers = [dict(sourcePath=entry["sourcePath"], gitBlobSha1=blob_sha(files[entry["file"]]),
                      sha256=entry["sha256"], bytes=entry["bytes"]) for entry in manifest["providers"]]
    receipt = dict(schema=1, appSourceRevision=app_source,
        appSourceTree=subprocess.check_output(["git", "-C", str(app), "rev-parse", app_source + "^{tree}"], text=True).strip(),
        smartCommit=COMMIT, smartTree=subprocess.check_output(["git", "-C", str(args.smart_repository), "rev-parse", COMMIT + "^{tree}"], text=True).strip(),
        script=dict(sourcePath=SCRIPT, sha256=SCRIPT_SHA, gitBlobSha1=blob_sha(files["script.js"])),
        geo=acquisitions, asn=dict(ASN, sourcePath=ASN_PATH, sourceRevision=app_source),
        fixtureManifestSha256=MANIFEST_SHA, fixtureFiles=len(files),
        fixtureDataBytes=sum(len(data) for name, data in files.items() if name != "manifest.json"),
        providers=providers, androidExecuted=False, originalNamedHttpDownloads=False)
    args.receipt.parent.mkdir(parents=True, exist_ok=True)
    args.receipt.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    verify_receipt(args.receipt, app_source, receipt["appSourceTree"])
    return receipt


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest="mode", required=True)
    build = modes.add_parser("prepare")
    build.add_argument("--smart-repository", type=Path, required=True)
    build.add_argument("--app-repository", type=Path, required=True)
    build.add_argument("--output", type=Path, required=True)
    build.add_argument("--receipt", type=Path, required=True)
    inputs = build.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--offline-geodata", type=Path)
    inputs.add_argument("--geo-cache", type=Path)
    build.add_argument("--download", action="store_true")
    check = modes.add_parser("verify")
    check.add_argument("--receipt", type=Path, required=True)
    check.add_argument("--expected-source", required=True)
    check.add_argument("--expected-tree", required=True)
    arguments = parser.parse_args()
    if arguments.mode == "verify":
        verify_receipt(arguments.receipt, arguments.expected_source, arguments.expected_tree)
        print("Fixture source receipt matches actual app identity")
        raise SystemExit(0)
    if arguments.download and arguments.offline_geodata:
        parser.error("--download is a CI cache acquisition option")
    result = materialize(arguments)
    print(json.dumps({key: result[key] for key in ["smartCommit", "fixtureFiles", "fixtureManifestSha256", "fixtureDataBytes"]}, indent=2))
