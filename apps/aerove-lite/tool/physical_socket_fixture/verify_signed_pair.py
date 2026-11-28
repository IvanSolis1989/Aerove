"""Validate the signed physical lab app+runner pair; never install or publish."""
import argparse
import hashlib
import json
import re
import subprocess
import tempfile
import zipfile
from pathlib import Path

APP = "dev.aerove.lite"
RUNNER = "dev.aerove.lite.platform.test"
DRIVER = "dev.aerove.lite.platformqa.AcceptanceCheckpointRunner"
BASELINE_SIGNER = "09f9e021ddac36ead015d27e073b673e79fdf4e02605c402a505781963b1a4d8"
DEX_DRIVER = "Ldev/aerove/lite/FlutterProductionUiTest;"
SELECTOR = "physicalOnlyOrdinaryAppTcp"


def require(condition, error):
    if not condition:
        raise ValueError(error)


def digest(path):
    with path.open("rb") as src:
        return hashlib.file_digest(src, "sha256").hexdigest()


def tool(bin_dir, command, *args):
    try:
        return subprocess.run([str(bin_dir / command), *map(str, args)], check=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=40).stdout
    except subprocess.SubprocessError as ex:
        raise ValueError(f"{command} could not verify the supplied APK") from ex


def signer(text):
    count = re.findall(r"^Number of signers:\s*(\d+)\s*$", text, re.M)
    certificates = set(re.findall(r"^\s*(?:Signer #\d+|V[23](?:\.[01])? Signer):? certificate SHA-256 digest: ([a-f0-9]{64})\s*$", text, re.M))
    signed = re.search(r"^Verified using v(?:2|3) scheme [^\n]*: true\s*$", text, re.M)
    require(count == ["1"] and signed and len(certificates) == 1, "Expected exactly one strong APK signer")
    return certificates.pop()


def runner_manifest(text):
    require(len(re.findall(r"^\s*E: instrumentation\b", text, re.M)) == 1 and
            not re.search(r"^\s*E: activity-alias\b", text, re.M) and
            re.search(r'android:testOnly\([^\n]+?=\(type 0x12\)0xffffffff', text),
            "Runner is not the test-only instrumentation package")
    block = re.search(r"(?m)^(\s*)E: instrumentation[^\n]*\n((?:\1\s+A:[^\n]*\n)+)", text)
    require(block is not None, "No readable runner instrumentation")
    allowed = {"androidx.test.core.app.InstrumentationActivityInvoker$" + child for child in
               ("BootstrapActivity", "EmptyActivity", "EmptyFloatingActivity")}
    activity_names = []
    for entry in re.finditer(r"(?m)^( *)E: activity[^\n]*\n((?:\1 +[^\n]*\n)*)", text):
        content = entry[2]
        name = re.search(r'android:name\([^\n]+?="([^"]+)"', content)
        require(name and name[1] in allowed and not re.search(r"(?m)^ *E: action\b", content),
                "Runner includes a nonstandard or runnable activity")
        activity_names.append(name[1])
    require(len(set(activity_names)) == len(activity_names), "Duplicate test helper activity")
    fields = block[2]
    def names(key):
        return re.findall(r'(?m)^\s*A: android:' + re.escape(key) + r'\([^\n]+?="([^"]+)"', fields)
    require(names("name") == [DRIVER] and names("targetPackage") == [APP],
            "Runner targets a different Android package or runner class")


def contains_physical_method(dex_output):
    chunks = re.split(r"(?m)^Class #\d+\s+-[^\n]*\n", dex_output)
    for chunk in chunks:
        descriptor = re.search(r"Class descriptor\s*:\s*'([^']+)'", chunk)
        if descriptor and descriptor[1] == DEX_DRIVER:
            return re.search(r"name\s*:\s*'" + SELECTOR + r"'\s*\n[^\n]*type\s*:\s*'\(\)V'", chunk) is not None
    return False


def verify_runner_contents(bin_dir, runner):
    runner_manifest(tool(bin_dir, "aapt", "dump", "xmltree", runner, "AndroidManifest.xml"))
    with zipfile.ZipFile(runner) as archive, tempfile.TemporaryDirectory(prefix="aerove-signed-dex-") as temp:
        dex = [entry for entry in archive.infolist() if re.fullmatch(r"classes(?:[0-9]+)?\.dex", entry.filename)]
        require(0 < len(dex) <= 12 and len({x.filename for x in dex}) == len(dex), "Invalid runner DEX inventory")
        found = 0
        for entry in dex:
            require(0 < entry.file_size <= 64 * 1024 * 1024, "Oversized runner DEX")
            raw = archive.read(entry)
            if SELECTOR.encode() not in raw:
                continue
            path = Path(temp) / entry.filename
            path.write_bytes(raw)
            found += contains_physical_method(tool(bin_dir, "dexdump", path))
        require(found == 1, "Signed runner lacks the exact physical-only test method")


def verify(bin_dir, app, runner, app_digest, runner_digest):
    for name, path, pinned in ((APP, app, app_digest), (RUNNER, runner, runner_digest)):
        require(re.fullmatch(r"[a-f0-9]{64}", pinned) and digest(path) == pinned, "APK hash differs from the pinned build")
        certificate = signer(tool(bin_dir, "apksigner", "verify", "--verbose", "--print-certs", path))
        require(certificate == BASELINE_SIGNER, "APK certificate is not the original #373 production signer")
        badging = tool(bin_dir, "aapt", "dump", "badging", path)
        require(re.findall(r"^package: name='([^']+)'", badging, re.M) == [name], "APK application ID does not match its role")
    verify_runner_contents(bin_dir, runner)
    return {"kind": "physical-signed-pair-preflight", "signerSha256": BASELINE_SIGNER,
            "appSha256": app_digest, "runnerSha256": runner_digest, "releaseAccepted": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("build-tools", "app", "runner"):
        parser.add_argument("--" + name, type=Path, required=True)
    for name in ("app-sha256", "runner-sha256"):
        parser.add_argument("--" + name, required=True)
    a = parser.parse_args()
    try:
        print(json.dumps(verify(a.build_tools, a.app, a.runner, a.app_sha256, a.runner_sha256)))
    except (ValueError, OSError, zipfile.BadZipFile) as exc:
        parser.exit(2, f"Physical runner refused: {exc}\n")


if __name__ == "__main__":
    main()
