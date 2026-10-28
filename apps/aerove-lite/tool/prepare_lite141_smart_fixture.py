"""Prepare the pinned Smart fixture with the immutable Lite141 App as its owner."""
import argparse
import json
from pathlib import Path
import subprocess

import emulator_original84 as original
import prepare_smart_config_meow_dba5_ci_fixture as fixture

SMART_COMMIT = "dba12215fe8f8d139a94fbd2d5b981282fbcf9c7"
SMART_TREE = "d7ba10c398eaf3789aff605ad8a6e418cbf67707"
SMART_MANIFEST_SHA256 = "234442db6cd3be995e4d5b323f50a360ff2c846b37e9cbd1e742b3f7833ab108"
SMART_PROVIDER_COUNT = 153


def git(path, *args):
    return subprocess.check_output(["git", "-C", str(path), *args], text=True).strip()


def prepare(args):
    spec = original.runtime_spec("lite141-prebuilt-shards")
    app = args.app_repository.resolve(strict=True)
    smart = args.smart_repository.resolve(strict=True)
    source = spec["identity"]["sourceRevision"]
    tree = spec["identity"]["sourceTree"]
    if (fixture.COMMIT != SMART_COMMIT or fixture.SMART_TREE != SMART_TREE
            or fixture.MANIFEST_SHA != SMART_MANIFEST_SHA256):
        raise ValueError("Current Smart fixture module differs from the fixed dba5 contract")
    if git(app, "rev-parse", "HEAD") != source or git(app, "rev-parse", "HEAD^{tree}") != tree:
        raise ValueError("Smart fixture owner checkout is not the fixed Lite141 App source/tree")
    if git(smart, "rev-parse", SMART_COMMIT + "^{tree}") != SMART_TREE:
        raise ValueError("Smart fixture checkout differs from the pinned dba5 source tree")
    source_asn = subprocess.check_output(["git", "-C", str(app), "show", source + ":" + fixture.ASN_PATH])
    asn = app / fixture.ASN_PATH
    if source_asn != fixture.verified_bytes(asn, fixture.ASN):
        raise ValueError("Original App ASN file differs from its fixed tracked source blob")

    geo = {}
    acquisitions = []
    for key, spec_geo in fixture.PINNED_GEO.items():
        path, record = fixture.acquire_geo(spec_geo, args.geo_cache, args.download)
        geo[key] = path
        acquisitions.append(record)
    if args.output.exists() and any(args.output.iterdir()):
        raise ValueError("Fixture output must be a new or empty directory")
    fixture.prepare(args.output, smart, None, dict(geo, **{"asn-mmdb": asn}))
    files = fixture.load_verified_fixture(args.output)
    manifest = json.loads(files["manifest.json"])
    providers = [dict(sourcePath=entry["sourcePath"], gitBlobSha1=fixture.blob_sha(files[entry["file"]]),
                      sha256=entry["sha256"], bytes=entry["bytes"])
                 for entry in manifest["providers"]]
    receipt = dict(schema=1, appSourceRevision=source, appSourceTree=tree,
        smartCommit=SMART_COMMIT, smartTree=SMART_TREE,
        script=dict(sourcePath=fixture.SCRIPT, sha256=fixture.SCRIPT_SHA,
                    gitBlobSha1=fixture.blob_sha(files["script.js"])),
        geo=acquisitions,
        asn=dict(fixture.ASN, sourcePath=fixture.ASN_PATH, sourceRevision=source),
        fixtureManifestSha256=fixture.MANIFEST_SHA, fixtureFiles=len(files),
        fixtureDataBytes=sum(len(data) for name, data in files.items() if name != "manifest.json"),
        providers=providers, androidExecuted=False, originalNamedHttpDownloads=False)
    args.receipt.parent.mkdir(parents=True, exist_ok=True)
    args.receipt.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    verified = fixture.verify_receipt(args.receipt, source, tree)
    if (verified.get("smartCommit") != SMART_COMMIT or verified.get("smartTree") != SMART_TREE
            or verified.get("fixtureManifestSha256") != SMART_MANIFEST_SHA256
            or len(verified.get("providers", [])) != SMART_PROVIDER_COUNT):
        raise ValueError("Current dba5 fixture receipt lost its pinned 153-provider identity")
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app-repository", type=Path, required=True)
    parser.add_argument("--smart-repository", type=Path, required=True)
    parser.add_argument("--geo-cache", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--download", action="store_true")
    args = parser.parse_args()
    prepare(args)


if __name__ == "__main__":
    main()
