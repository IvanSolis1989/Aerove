#!/usr/bin/env python3
"""Build one ABI; production packaging selects arm64-v8a separately."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys

from prepare import ROOT, prepare

TARGETS = {
    "aarch64-linux-android": ("arm64-v8a", "aarch64-linux-android", 183),
    "x86_64-linux-android": ("x86_64", "x86_64-linux-android", 62),
}


def check_elf(path: Path, machine: int) -> None:
    header = path.read_bytes()[:20]
    if len(header) != 20 or header[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", header, 18)[0] != machine:
        raise ValueError(f"unexpected native ELF ABI: {path}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=TARGETS, default="aarch64-linux-android")
    parser.add_argument("--abi", choices=("arm64-v8a", "x86_64"))
    parser.add_argument("--ndk", type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / "build/jniLibs")
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--initialize-lock", action="store_true", help="Resolve the first real bridge lock with cargo")
    parser.add_argument("--test", action="store_true", help="Run host unit tests after source preparation")
    args = parser.parse_args()
    abi, clang_target, machine = TARGETS[args.target]
    if args.abi is not None and args.abi != abi:
        parser.error("--abi must match --target")
    engine = prepare(args.archive)
    environment = dict(os.environ, RUSTC_BOOTSTRAP="1", CARGO_TARGET_DIR=str(ROOT / "target"))
    if args.initialize_lock:
        subprocess.run(["cargo", "metadata", "--format-version", "1"], cwd=ROOT,
            env=environment, check=True, stdout=subprocess.DEVNULL)
        subprocess.run(["cargo", "metadata", "--locked", "--format-version", "1"], cwd=ROOT,
            env=environment, check=True, stdout=subprocess.DEVNULL)
    if args.prepare_only:
        return
    if args.test:
        subprocess.run(["cargo", "test", "--locked", "--lib"], cwd=ROOT, env=environment, check=True)
        subprocess.run(["cargo", "test", "--locked", "--manifest-path", str(engine / "Cargo.toml"),
            "-p", "clash-lib", "--no-default-features", "--features", "aerove-mobile,ring,shadowsocks,tuic,zero_copy",
            "mobile::tests", "--", "--test-threads=1"], cwd=ROOT, env=environment, check=True)
        return
    if sys.platform != "linux":
        parser.error("the native Android build entry point requires Linux")
    ndk = args.ndk or Path(environment.get("ANDROID_NDK_HOME", environment.get("ANDROID_NDK_ROOT", "")))
    compiler_bin = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    linker = compiler_bin / f"{clang_target}33-clang"
    if not linker.is_file():
        parser.error(f"NDK API33 compiler missing: {linker}")
    key = args.target.replace("-", "_")
    environment.update({
        f"CARGO_TARGET_{key.upper()}_LINKER": str(linker.resolve()),
        f"CC_{key}": str(linker.resolve()),
        f"CXX_{key}": str((compiler_bin / f"{clang_target}33-clang++").resolve()),
        f"AR_{key}": str((compiler_bin / "llvm-ar").resolve()),
    })
    subprocess.run(["cargo", "build", "--locked", "--release", "--target", args.target],
        cwd=ROOT, env=environment, check=True)
    library = ROOT / "target" / args.target / "release/libaerove_core.so"
    check_elf(library, machine)
    destination = args.output.resolve() / abi
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copy2(library, destination / library.name)
    (ROOT / "build").mkdir(exist_ok=True)
    shutil.copy2(engine / "AEROVE_PROVENANCE.json", ROOT / "build/provenance.json")
    print(f"Built {args.target}: {destination / library.name}")


if __name__ == "__main__":
    main()
