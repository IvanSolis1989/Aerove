"""Inspect Lite APK identity, ABI and verified signing output before promotion."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import zipfile


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def product_version(path):
    product = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        key, separator, value = line.partition("=")
        if not separator or key in product:
            raise ValueError("Invalid or duplicate Lite version property")
        product[key] = value
    if set(product) != {"applicationId", "version", "versionCode", "channel", "signerSha256"}:
        raise ValueError("Incomplete Lite version identity")
    if product["applicationId"] != "dev.aerove.lite" or product["channel"] != "preview":
        raise ValueError("Unexpected Lite release channel or application identity")
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+-beta\.[1-9][0-9]*", product["version"]):
        raise ValueError("Invalid Lite preview version")
    if not product["versionCode"].isdigit() or not 0 < int(product["versionCode"]) < 2_100_000_000:
        raise ValueError("Invalid Android version code")
    if not re.fullmatch(r"[0-9a-f]{64}", product["signerSha256"]):
        raise ValueError("Invalid Lite signing identity")
    return product


def verify_identity(badging, signature, product, channel):
    identity = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.MULTILINE)
    expected_id = product["applicationId"] + (".dev" if channel == "development" else "")
    expected_version = product["version"] + ("+debug" if channel == "development" else "")
    if identity is None or tuple(identity.groups()) != (expected_id, product["versionCode"], expected_version):
        raise ValueError("Unexpected package identity or version")
    if not re.search(r"^sdkVersion:'33'$", badging, re.MULTILINE):
        raise ValueError("Unexpected minimum SDK")
    signer = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-f]{64})$", signature, re.MULTILINE)
    if len(signer) != 1 or (channel != "development" and signer[0] != product["signerSha256"]):
        raise ValueError("Unexpected or ambiguous verified signing certificate")
    return {"application_id": expected_id, "version": expected_version,
        "version_code": int(product["versionCode"]), "min_sdk": 33, "signer_sha256": signer[0]}


def verify(apk, abi):
    machine = {"arm64-v8a": 183, "x86_64": 62}[abi]
    with zipfile.ZipFile(apk) as archive:
        if archive.testzip() is not None:
            raise ValueError("APK ZIP CRC failure")
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate APK entries")
        library = f"lib/{abi}/libaerove_core.so"
        if library not in names:
            raise ValueError("The actual Rust core is missing")
        libraries = [name for name in names if name.startswith("lib/") and name.endswith(".so")]
        for name in libraries:
            if name.split("/")[1] != abi:
                raise ValueError(f"Unexpected ABI: {name}")
            header = archive.read(name)[:20]
            if len(header) < 20 or header[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", header, 18)[0] != machine:
                raise ValueError(f"Wrong ELF architecture: {name}")
        if "AndroidManifest.xml" not in names or not any(name.endswith(".dex") for name in names):
            raise ValueError("Not a complete Android application")
        return {"abi": abi, "jni_libraries": libraries, "core_sha256": hashlib.sha256(archive.read(library)).hexdigest()}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--abi", required=True, choices=("arm64-v8a", "x86_64"))
    parser.add_argument("--channel", choices=("development", "preview"), default="development")
    parser.add_argument("--version-file", type=Path, default=Path(__file__).resolve().parents[1] / "version.properties")
    parser.add_argument("--source", required=True)
    parser.add_argument("--tree", required=True)
    parser.add_argument("--workflow", required=True)
    parser.add_argument("--badging", required=True, type=Path)
    parser.add_argument("--signature", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if not all(re.fullmatch(r"[0-9a-f]{40}", value) for value in (args.source, args.tree)):
        parser.error("Full source and tree SHA values are required")
    if not re.fullmatch(r"https://github\.com/IvanSolis1989/Aerove/actions/runs/[0-9]+", args.workflow):
        parser.error("The actual repository workflow URL is required")
    result = verify(args.apk, args.abi)
    badging = args.badging.read_text(encoding="utf-8")
    try:
        identity = verify_identity(badging, args.signature.read_text(encoding="utf-8"), product_version(args.version_file), args.channel)
    except ValueError as error:
        parser.error(str(error))
    args.output.mkdir(parents=True, exist_ok=True)
    apk = args.output / ("AEROVE-Lite-dev-arm64.apk" if args.channel == "development" else "AEROVE-Lite-android-arm64.apk")
    shutil.copyfile(args.apk, apk)
    info = args.output / "BUILD_INFO.json"
    info.write_text(json.dumps({
        "product": "AEROVE Lite",
        "channel": args.channel,
        **identity,
        "source": args.source,
        "tree": args.tree,
        "workflow": args.workflow,
        "clash_rs_source": "36eec77463d1243ede287bf6b7197e38237948d5",
        "apk_sha256": sha256(apk),
        **result,
    }, indent=2) + "\n", encoding="utf-8")
    (args.output / "SHA256SUMS").write_text("".join(f"{sha256(path)}  {path.name}\n" for path in (apk, info)), encoding="utf-8")
    print(json.dumps({"apk": str(apk), **result}))


if __name__ == "__main__":
    main()
