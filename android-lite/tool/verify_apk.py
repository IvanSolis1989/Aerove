"""Inspect a development APK without presenting it as a production release."""

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
    identity = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.MULTILINE)
    if identity is None or identity[1] != "dev.aerove.lite.dev" or identity[3] != "0.1.0-dev.1+debug":
        parser.error("Unexpected package identity or development version")
    if not re.search(r"^sdkVersion:'33'$", badging, re.MULTILINE):
        parser.error("Unexpected minimum SDK")
    signer = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-f]{64})$", args.signature.read_text(encoding="utf-8"), re.MULTILINE)
    if len(signer) != 1:
        parser.error("One verified signing certificate is required")
    args.output.mkdir(parents=True, exist_ok=True)
    apk = args.output / "AEROVE-Lite-dev-arm64.apk"
    shutil.copyfile(args.apk, apk)
    info = args.output / "BUILD_INFO.json"
    info.write_text(json.dumps({
        "product": "AEROVE Lite",
        "channel": "development",
        "application_id": "dev.aerove.lite.dev",
        "version": identity[3],
        "version_code": int(identity[2]),
        "min_sdk": 33,
        "signer_sha256": signer[0],
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
