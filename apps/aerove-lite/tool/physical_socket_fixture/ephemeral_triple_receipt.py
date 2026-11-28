"""Verify disposable fixture + same-graph app and runner without installing them."""
import argparse
import json
from pathlib import Path
import re
import subprocess

from ephemeral_pair_receipt import collect
from verify_signed_pair import BASELINE_SIGNER, digest, require, signer, tool

PACKAGE = "dev.aerove.fixture.socketowner"
HEX = re.compile(r"[0-9a-f]{64}")
PAIR_DIGESTS = ("appSha256", "runnerSha256", "targetMappingSha256", "keepRulesSha256", "signerSha256")


def fixture_contents(tools, directory):
    apk = directory / "fixture.apk"
    receipt_path = directory / "FIXTURE.json"
    require(apk.is_file() and not apk.is_symlink() and 0 < apk.stat().st_size <= 4 * 1024 * 1024,
            "Invalid disposable fixture APK")
    require(receipt_path.is_file() and not receipt_path.is_symlink() and 0 < receipt_path.stat().st_size <= 4096,
            "Invalid disposable fixture receipt")
    row = json.loads(receipt_path.read_text())
    require(isinstance(row, dict) and row.get("packageName") == PACKAGE
            and row.get("signingIdentity") == "one-use-fixture-only"
            and row.get("deviceValidated") is False and row.get("releaseAccepted") is False,
            "Unexpected fixture receipt")
    require(digest(apk) == row.get("apkSha256"), "Fixture APK bytes differ from receipt")
    cert = signer(tool(tools, "apksigner", "verify", "--verbose", "--print-certs", apk))
    require(cert != BASELINE_SIGNER and cert == row.get("signerSha256"), "Unexpected fixture signer")
    badging = tool(tools, "aapt2", "dump", "badging", apk)
    manifest = tool(tools, "aapt2", "dump", "xmltree", "--file", "AndroidManifest.xml", apk)
    require(re.findall(r"^package: name='([^']+)'", badging, re.M) == [PACKAGE]
            and "launchable-activity:" not in badging
            and re.search(r"android:testOnly\([^)]*\)=true$", manifest, re.M)
            and re.search(r'android:name\([^)]*\)="\.PhysicalSocketService"', manifest)
            and re.findall(r"^\s*E: (\S+)", manifest, re.M)
            == ["manifest", "uses-sdk", "uses-permission", "queries", "package", "application", "service"],
            "Unexpected fixture manifest or package")
    return {"fixtureApkSha256": row["apkSha256"], "fixtureSignerSha256": cert}


def bind(pair, fixture):
    require(all(isinstance(pair.get(k), str) and HEX.fullmatch(pair[k]) for k in PAIR_DIGESTS)
            and pair.get("deviceValidated") is False and pair.get("releaseAccepted") is False,
            "Unexpected disposable pair receipt")
    require(fixture["fixtureSignerSha256"] not in (pair["signerSha256"], BASELINE_SIGNER)
            and pair["signerSha256"] != BASELINE_SIGNER, "Fixture and pair must use independent lab signers")
    require(len({pair["appSha256"], pair["runnerSha256"], fixture["fixtureApkSha256"]}) == 3,
            "The three APK roles must be distinct")
    return dict(pair, **fixture, packagesVerified=3)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for key in ("app-dir", "graph", "tools", "analyzer", "fixture-dir"):
        p.add_argument("--" + key, required=True, type=Path)
    a = p.parse_args()
    try:
        fixture = fixture_contents(a.tools, a.fixture_dir)
        pair = collect(a.app_dir, a.graph, a.tools, a.analyzer)
        result = bind(pair, fixture)
    except (OSError, ValueError, KeyError, subprocess.SubprocessError):
        p.exit(2, "Three-package offline verification failed. No APK installed or published.\n")
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
