"""Check the same-target diagnostic pair without copying, installing, or releasing it."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

from verify_signed_pair import APP, BASELINE_SIGNER, RUNNER, digest, require, signer, tool, verify_runner_contents

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from seal_platform_runner import seal_abi


def collect(app_dir, graph_path, tools, analyzer):
    app_build = app_dir / "build/app"
    test_build = app_dir / "build/flutter-host/platform-qa"
    app = app_build / "outputs/apk/release/app-release.apk"
    test = test_build / "outputs/apk/release/platform-qa-release.apk"
    certificates = []
    for apk, name in ((app, APP), (test, RUNNER)):
        require(apk.is_file() and not apk.is_symlink(), "Missing assembled APK")
        badging = tool(tools, "aapt", "dump", "badging", apk)
        require(re.findall(r"^package: name='([^']+)'", badging, re.M) == [name], "Wrong output package")
        certificates.append(signer(tool(tools, "apksigner", "verify", "--verbose", "--print-certs", apk)))
    require(certificates[0] == certificates[1] != BASELINE_SIGNER, "Expected a sole same-pair lab signer")
    verify_runner_contents(tools, test)
    reference_dump = subprocess.run(
        [str(analyzer), "dex", "packages", str(test)], check=True, capture_output=True, text=True, timeout=100,
    ).stdout
    app_rules = app_build / "intermediates/androidTestAppKeepRules/release"
    abi = seal_abi(
        graph_path.read_text(), (app_build / "outputs/mapping/release/mapping.txt").read_text(), reference_dump,
        app_rules / "rules.pro", app_rules / "inputs.json",
        test_build / "outputs/mapping/release/mapping.txt", test_build / "outputs/mapping/release/configuration.txt",
    )
    return dict(appSha256=digest(app), runnerSha256=digest(test), targetMappingSha256=abi["targetMappingSha256"],
                keepRulesSha256=abi["appRulesSha256"], signerSha256=certificates[0],
                deviceValidated=False, releaseAccepted=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("app-dir", "graph", "tools", "analyzer"):
        parser.add_argument("--" + key, required=True, type=Path)
    a = parser.parse_args()
    try:
        result = collect(a.app_dir, a.graph, a.tools, a.analyzer)
    except (OSError, ValueError, KeyError, subprocess.SubprocessError):
        parser.exit(2, "Could not verify a same-target ephemeral pair. No APK has been published.\n")
    print("### AEROVE isolated build evidence (not acceptance)\n\n```json")
    print(json.dumps(result, sort_keys=True, indent=2))
    print("```")


if __name__ == "__main__":
    main()
