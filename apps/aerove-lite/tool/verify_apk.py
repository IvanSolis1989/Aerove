"""Inspect Lite APK identity, ABI and verified signing output before promotion."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import zipfile

HELPER_LIBRARIES = ("libaerove_filter_helper.so", "libaerove_filter_jni.so")
HELPER_LICENSE_PREFIX = "assets/licenses/client-helper/"
ANDROID_MAX_PAGE_SIZE = 16 * 1024
CLASH_RS_SOURCE = "36eec77463d1243ede287bf6b7197e38237948d5"


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def artifact_name(abi, channel):
    if abi == "x86_64":
        return "AEROVE-Lite-test-x86_64.apk"
    return "AEROVE-Lite-dev-arm64.apk" if channel == "development" else "AEROVE-Lite-android-arm64.apk"


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
    if not re.fullmatch(
        r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
        r"-(?:alpha|beta|rc)\.[1-9][0-9]*", product["version"],
    ):
        raise ValueError("Invalid Lite preview version")
    if not re.fullmatch(r"[1-9][0-9]*", product["versionCode"]) or int(product["versionCode"]) > 2_100_000_000:
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
    lines = signature.splitlines()
    legacy_signers = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-f]{64})$", signature, re.MULTILINE)
    modern_signers = re.findall(r"^V2 Signer: certificate SHA-256 digest: ([0-9a-f]{64})$", signature, re.MULTILINE)
    signer_digest_lines = [line for line in lines if "Signer" in line and "certificate SHA-256 digest:" in line]
    modern_markers = any(
        line == "Verifies" or line.startswith("Verified using ") or line.startswith("Number of signers:")
        or line.startswith("V2 Signer:")
        for line in lines
    )
    if modern_markers:
        verifies = [line for line in lines if line == "Verifies"]
        v2_status = [line for line in lines if line.startswith("Verified using v2 scheme ")]
        signer_counts = [line for line in lines if line.startswith("Number of signers:")]
        if (len(verifies) != 1 or len(v2_status) != 1
                or v2_status[0] != "Verified using v2 scheme (APK Signature Scheme v2): true"
                or len(signer_counts) != 1 or signer_counts[0] != "Number of signers: 1"):
            raise ValueError("Unexpected or ambiguous verified signing certificate")
    signer = legacy_signers + modern_signers
    if len(signer_digest_lines) != len(signer) or len(signer) != 1:
        raise ValueError("Unexpected or ambiguous verified signing certificate")
    if len(signer) != 1 or (channel != "development" and signer[0] != product["signerSha256"]):
        raise ValueError("Unexpected or ambiguous verified signing certificate")
    return {"application_id": expected_id, "version": expected_version,
        "version_code": int(product["versionCode"]), "min_sdk": 33, "signer_sha256": signer[0]}


def verify(apk, abi, helper_source_root, helper_receipt):
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
        helpers = [f"lib/{abi}/{name}" for name in HELPER_LIBRARIES]
        missing_helpers = [name for name in helpers if name not in names]
        if missing_helpers:
            raise ValueError("Required client helper library is missing: " + ", ".join(missing_helpers))
        helper_provenance = validate_helper_provenance(helper_source_root, helper_receipt, abi)
        libraries = [name for name in names if name.startswith("lib/") and name.endswith(".so")]
        helper_readback = {}
        for name in libraries:
            if name.split("/")[1] != abi:
                raise ValueError(f"Unexpected ABI: {name}")
            image = archive.read(name)
            if name in helpers:
                valid = valid_elf64_shared_object(image, machine)
            else:
                header = image[:20]
                valid = (len(header) >= 20 and header[:6] == b"\x7fELF\x02\x01"
                    and struct.unpack_from("<H", header, 18)[0] == machine)
            if not valid:
                raise ValueError(f"Wrong ELF architecture: {name}")
            if name in helpers:
                helper_readback[name.rsplit("/", 1)[-1]] = {
                    "sha256": hashlib.sha256(image).hexdigest(),
                    "size_bytes": len(image),
                }
        licenses = sorted(name for name in names if name.startswith(HELPER_LICENSE_PREFIX) and not name.endswith("/"))
        if not licenses:
            raise ValueError("Client helper license assets are missing")
        dependency_manifests = [name for name in licenses if name == HELPER_LICENSE_PREFIX + "dependencies.json"]
        if len(dependency_manifests) != 1:
            raise ValueError("Client helper dependency manifest is missing or ambiguous")
        validate_helper_license_manifest(archive, archive.read(dependency_manifests[0]), licenses, helper_source_root)
        for helper_name, details in helper_readback.items():
            receipt = helper_provenance["outputs"][helper_name]
            if details["sha256"] != receipt["sha256"] or details["size_bytes"] != receipt["bytes"]:
                raise ValueError(f"Client helper build receipt does not match APK: {helper_name}")
        if "AndroidManifest.xml" not in names or not any(name.endswith(".dex") for name in names):
            raise ValueError("Not a complete Android application")
        return {"abi": abi, "jni_libraries": libraries, "core_sha256": hashlib.sha256(archive.read(library)).hexdigest(),
            "client_helpers": helper_readback, "client_helper_license_assets": licenses,
            "client_helper_source_sha256": helper_provenance["source_sha256"]}


def build_info(identity, result, source, tree, workflow, run_number, run_attempt, apk_path, channel="preview"):
    if type(run_number) is not int or run_number < 1 or type(run_attempt) is not int or run_attempt < 1:
        raise ValueError("Positive workflow run number and attempt are required")
    return {
        "product": "AEROVE Lite", "channel": channel, **identity,
        "source": source, "tree": tree, "workflow": workflow,
        "run_number": run_number, "run_attempt": run_attempt,
        "clash_rs_source": CLASH_RS_SOURCE,
        "apk_sha256": sha256(apk_path), **result,
    }


def valid_elf64_shared_object(image, machine):
    """Require a complete ELF64 DSO with 16 KiB-compatible PT_LOAD segments."""
    if len(image) < 64 or image[:7] != b"\x7fELF\x02\x01\x01":
        return False
    try:
        e_type, e_machine, e_version = struct.unpack_from("<HHI", image, 16)
        e_phoff = struct.unpack_from("<Q", image, 32)[0]
        e_ehsize, e_phentsize, e_phnum = struct.unpack_from("<HHH", image, 52)
    except struct.error:
        return False
    if (e_type != 3 or e_machine != machine or e_version != 1 or e_ehsize != 64
            or e_phentsize < 56 or e_phnum < 1 or e_phoff < 64
            or e_phoff + e_phentsize * e_phnum > len(image)):
        return False
    loads = 0
    for index in range(e_phnum):
        offset = e_phoff + index * e_phentsize
        p_type, _p_flags = struct.unpack_from("<II", image, offset)
        if p_type != 1:  # PT_LOAD
            continue
        p_offset, p_vaddr, _p_paddr, p_filesz, p_memsz, p_align = struct.unpack_from("<QQQQQQ", image, offset + 8)
        if (p_filesz > p_memsz or p_offset + p_filesz > len(image) or p_align < ANDROID_MAX_PAGE_SIZE
                or p_align & (p_align - 1) or p_offset % ANDROID_MAX_PAGE_SIZE != p_vaddr % ANDROID_MAX_PAGE_SIZE
                or p_offset % p_align != p_vaddr % p_align):
            return False
        loads += 1
    return loads > 0


def validate_helper_license_manifest(archive, raw, license_assets, helper_source_root):
    helper_source_root = Path(helper_source_root)
    try:
        expected_manifest = (helper_source_root / "dependencies.json").read_bytes()
        manifest = json.loads(raw.decode("utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("Invalid client helper dependency manifest") from error
    if raw != expected_manifest:
        raise ValueError("Client helper dependency manifest differs from pinned source")
    dependencies = manifest if isinstance(manifest, list) else None
    if not isinstance(dependencies, list) or not dependencies:
        raise ValueError("Client helper dependency manifest is incomplete")
    expected = set()
    for dependency in dependencies:
        if not isinstance(dependency, dict) or not isinstance(dependency.get("Path"), str):
            raise ValueError("Invalid client helper dependency entry")
        dependency_licenses = dependency.get("licenses")
        if not isinstance(dependency_licenses, list) or not dependency_licenses:
            raise ValueError("Client helper dependency license list is incomplete")
        for relative in dependency_licenses:
            if not isinstance(relative, str) or not relative.startswith("licenses/"):
                raise ValueError("Client helper dependency license path is invalid")
            source_path = (helper_source_root / relative).resolve()
            if not source_path.is_relative_to(helper_source_root.resolve()) or not source_path.is_file() or source_path.stat().st_size == 0:
                raise ValueError("Client helper dependency license is missing or empty")
            expected.add(HELPER_LICENSE_PREFIX + relative.removeprefix("licenses/"))
    actual = set(license_assets) - {HELPER_LICENSE_PREFIX + "dependencies.json"}
    source_licenses = {p.relative_to(helper_source_root / "licenses").as_posix() for p in (helper_source_root / "licenses").rglob("*") if p.is_file()}
    actual_relative = {name.removeprefix(HELPER_LICENSE_PREFIX) for name in actual}
    if actual_relative != source_licenses or not expected.issubset(actual):
        raise ValueError("Client helper license assets are incomplete")
    for asset in actual:
        mapped = asset.removeprefix(HELPER_LICENSE_PREFIX)
        if archive.read(asset) != (helper_source_root / "licenses" / mapped).read_bytes():
            raise ValueError(f"Client helper license asset differs from pinned source: {asset}")


def validate_helper_provenance(source_root, receipt_path, abi):
    source_root = Path(source_root).resolve()
    if not source_root.is_dir():
        raise ValueError("Client helper source root is missing")
    try:
        receipt = json.loads(Path(receipt_path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("Invalid client helper build receipt") from error
    input_files = receipt.get("inputFiles")
    if not isinstance(input_files, dict) or not input_files:
        raise ValueError("Client helper build receipt has no source inputs")
    actual_inputs = {}
    for path in source_root.rglob("*"):
        if not path.is_file() or "__pycache__" in path.parts or path.suffix.lower() == ".pyc":
            continue
        relative = path.relative_to(source_root).as_posix()
        actual_inputs[relative] = sha256(path)
    if input_files != actual_inputs:
        raise ValueError("Client helper source input hashes do not match the build receipt")
    source_sha256 = hashlib.sha256(json.dumps(input_files, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    if receipt.get("sourceSha256") != source_sha256:
        raise ValueError("Client helper source aggregate hash is invalid")
    outputs = receipt.get("outputs")
    if not isinstance(outputs, list):
        raise ValueError("Client helper build receipt has no outputs")
    matching = {}
    for output in outputs:
        if not isinstance(output, dict) or output.get("abi") != abi:
            continue
        filename = str(output.get("path", "")).replace("\\", "/").rsplit("/", 1)[-1]
        if filename not in HELPER_LIBRARIES or filename in matching:
            raise ValueError("Client helper build receipt output is ambiguous")
        if not re.fullmatch(r"[0-9a-f]{64}", str(output.get("sha256", ""))) or type(output.get("bytes")) is not int or output["bytes"] <= 0:
            raise ValueError("Client helper build receipt output is invalid")
        matching[filename] = {"sha256": output["sha256"], "bytes": output["bytes"]}
    if set(matching) != set(HELPER_LIBRARIES):
        raise ValueError("Client helper build receipt is missing selected ABI outputs")
    return {"source_sha256": source_sha256, "outputs": matching}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--abi", required=True, choices=("arm64-v8a", "x86_64"))
    parser.add_argument("--channel", choices=("development", "preview"), default="development")
    parser.add_argument("--version-file", type=Path, default=Path(__file__).resolve().parents[1] / "version.properties")
    parser.add_argument("--source", required=True)
    parser.add_argument("--tree", required=True)
    parser.add_argument("--workflow", required=True)
    parser.add_argument("--run-number", required=True, type=int)
    parser.add_argument("--run-attempt", required=True, type=int)
    parser.add_argument("--badging", required=True, type=Path)
    parser.add_argument("--signature", required=True, type=Path)
    parser.add_argument("--helper-source-root", required=True, type=Path)
    parser.add_argument("--helper-receipt", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if not all(re.fullmatch(r"[0-9a-f]{40}", value) for value in (args.source, args.tree)):
        parser.error("Full source and tree SHA values are required")
    if not re.fullmatch(r"https://github\.com/IvanSolis1989/Aerove/actions/runs/[0-9]+", args.workflow):
        parser.error("The actual repository workflow URL is required")
    if args.run_number < 1 or args.run_attempt < 1:
        parser.error("Positive workflow run number and attempt are required")
    try:
        result = verify(args.apk, args.abi, args.helper_source_root, args.helper_receipt)
    except ValueError as error:
        parser.error(str(error))
    badging = args.badging.read_text(encoding="utf-8")
    try:
        identity = verify_identity(badging, args.signature.read_text(encoding="utf-8"), product_version(args.version_file), args.channel)
    except ValueError as error:
        parser.error(str(error))
    args.output.mkdir(parents=True, exist_ok=True)
    apk = args.output / artifact_name(args.abi, args.channel)
    shutil.copyfile(args.apk, apk)
    info = args.output / "BUILD_INFO.json"
    info.write_text(json.dumps(build_info(identity, result, args.source, args.tree, args.workflow,
        args.run_number, args.run_attempt, apk, args.channel), indent=2) + "\n", encoding="utf-8")
    (args.output / "SHA256SUMS").write_text("".join(f"{sha256(path)}  {path.name}\n" for path in (apk, info)), encoding="utf-8")
    print(json.dumps({"apk": str(apk), **result}))


if __name__ == "__main__":
    main()
