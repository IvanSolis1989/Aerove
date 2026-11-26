"""Prepare independently pinned run #373 inputs. No signing, installation or network."""
import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import tempfile
import zipfile
from pathlib import Path, PurePosixPath

BASE = {
    "sourceRevision": "d1b2db996af6142fbb299059c4de1b50df7dac38",
    "sourceTree": "04dd07698f78b6b3fcf21bf5325f2f68a1483be5",
    "workflowRun": "45115924455", "runAttempt": 1, "runNumber": 373,
}
# Exact GitHub Actions artifact bytes, not caller-supplied pins.
PINS = {
    "core": (14793710109, 54729824, "c98329a3450dbd0ff3c3f0ba4ea493a5feae4deed6ed5ea0b657a4ab81ae3ed8"),
    "helper": (14793311404, 4073113, "55f5ce5827e22f6ab961c8cfdaef90dd6643f5a56199c587396553f2088c337b"),
    "migration": (14792767188, 10298, "1d01a42b454b4680739225f9364ddbee999042361e063aac02904134cef7a5bb"),
    "original": (14794000243, 59320948, "1b9e4c98cebc954821754ec1eb3b5f425404f03d9115cd8ac0e5243f2da4fd63"),
}
APP_PATH = "apks/app-release.apk"
MAPPING_PATH = "mapping/release/mapping.txt"
BASELINE_FILES = {
    APP_PATH: (117354602, "14c61f6b043964c578498299c4c37d7a228166408371983a120577a6eab1e5f3"),
    MAPPING_PATH: (107429219, "da1cbd9a0a89cfb5704ce1f1e6883217db401c70fa422d8a9d3ad64bcb866089"),
}


def check(ok, error):
    if not ok:
        raise ValueError(error)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verify_manifest(archive, inventory, name, abi):
    row = inventory.get(name)
    check(row is not None and 0 < row.file_size <= 512 * 1024, "Missing transfer manifest")
    value = json.loads(archive.read(name))
    expected = dict(BASE, abi=abi)
    if name == "source-manifest.json":
        expected.update(prebuiltApks=True, apkInspectorOutcome="success")
    check(all(value.get(k) == v for k, v in expected.items()), "Original source/run/ABI identity differs")
    records = {}
    for item in value["files"]:
        item_path, size, sha = item["path"], item["bytes"], item["sha256"]
        check(item_path in inventory and item_path not in records and item_path != name
              and type(size) is int and 0 <= size <= 150 * 1024 * 1024
              and inventory[item_path].file_size == size
              and isinstance(sha, str) and re.fullmatch(r"[0-9a-f]{64}", sha), "Invalid original manifest payload")
        records[item_path] = (size, sha)
    check(bool(records), "Original manifest has no payloads")
    return records


def stage(source, output):
    os.umask(0o077)
    check(output.is_dir() and not output.is_symlink() and output.stat().st_mode & 0o777 == 0o700
          and not any(output.iterdir()), "Output must already be empty and private")
    receipt = {"baseline": BASE, "artifacts": {}, "releaseAccepted": False}
    with tempfile.TemporaryDirectory(dir=output.parent, prefix=".original373-") as td:
        temp = Path(td)
        for kind, (artifact, size, sha) in PINS.items():
            path = source / f"{artifact}.zip"
            st = path.lstat()
            check(stat.S_ISREG(st.st_mode) and st.st_size == size and digest(path) == sha,
                  f"Original {kind} archive does not match its pinned bytes")
            with zipfile.ZipFile(path) as archive:
                inventory = {}
                all_names = set()
                for item in archive.infolist():
                    n = item.filename
                    rel = PurePosixPath(n)
                    mode = item.external_attr >> 16
                    check(bool(rel.parts) and not rel.is_absolute() and ".." not in rel.parts and "\\" not in n
                          and str(rel) == n.rstrip("/") and n not in all_names
                          and (not stat.S_IFMT(mode) or stat.S_ISDIR(mode) or stat.S_ISREG(mode)),
                          "Unexpected archive entry")
                    all_names.add(n)
                    if not item.is_dir():
                        inventory[n] = item
                manifest = "source-manifest.json" if kind == "original" else "TRANSFER.json"
                rows = verify_manifest(archive, inventory, manifest, "shared-fixture" if kind == "migration" else "arm64-v8a")
                if kind == "original":
                    check({name: rows.get(name) for name in BASELINE_FILES} == BASELINE_FILES,
                          "Original app or mapping identity differs")
                    # Do not expose the stock runner to the compile or signing stages.
                    rows = BASELINE_FILES
                else:
                    check(set(inventory) == set(rows) | {manifest}, "Unexpected original build input data")
                for name, (want_size, want_sha) in rows.items():
                    target = temp / kind / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with archive.open(inventory[name]) as src, target.open("xb") as dst:
                        shutil.copyfileobj(src, dst, 1024 * 1024)
                    check(target.stat().st_size == want_size and digest(target) == want_sha,
                          "Original extracted payload did not match its receipt")
                receipt["artifacts"][kind] = {"id": artifact, "archiveSha256": sha}
        (temp / "original-identity.json").write_text(json.dumps(receipt, sort_keys=True) + "\n")
        for path in temp.iterdir():
            path.rename(output / path.name)
    return receipt


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("archives", type=Path)
    cli.add_argument("empty_private_out", type=Path)
    a = cli.parse_args()
    try:
        print(json.dumps(stage(a.archives, a.empty_private_out), sort_keys=True))
    except (ValueError, OSError, KeyError, TypeError, zipfile.BadZipFile) as exc:
        cli.exit(2, f"Original inputs refused: {exc}\n")


if __name__ == "__main__":
    main()
