"""Replay the pinned Tailscale adaptation into a fresh, private staging directory."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import tarfile

ROOT = Path(__file__).resolve().parent


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def replay_patch(directory: Path, patch: str) -> None:
    lines = patch.splitlines(keepends=True)
    position = 0
    while position < len(lines):
        if not lines[position].startswith("--- a/"):
            raise ValueError("invalid dependency patch header")
        path = PurePosixPath(lines[position][6:].strip())
        if path.is_absolute() or ".." in path.parts:
            raise ValueError("unsafe dependency patch path")
        position += 1
        if lines[position] != "+++ b/" + path.as_posix() + "\n":
            raise ValueError("dependency patch renames are not supported")
        position += 1
        target = directory.joinpath(*path.parts)
        original = target.read_text(encoding="utf-8").splitlines(keepends=True)
        result, consumed = [], 0
        while position < len(lines) and lines[position].startswith("@@ "):
            match = re.fullmatch(r"@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@\n", lines[position])
            if not match:
                raise ValueError("invalid dependency patch hunk")
            start = int(match[1]) - 1
            if start < consumed:
                raise ValueError("overlapping dependency patch hunks")
            result.extend(original[consumed:start])
            consumed, removed, added = start, 0, 0
            position += 1
            while position < len(lines) and lines[position][:1] in (" ", "+", "-"):
                line = lines[position]
                if line.startswith("--- a/"):
                    break
                if line[0] in " -":
                    if consumed >= len(original) or original[consumed] != line[1:]:
                        raise ValueError(f"dependency patch context mismatch: {path}:{consumed + 1}")
                    consumed += 1
                    removed += 1
                if line[0] in " +":
                    result.append(line[1:])
                    added += 1
                position += 1
            if removed != int(match[2] or 1) or added != int(match[4] or 1):
                raise ValueError("incorrect dependency patch hunk counts")
        result.extend(original[consumed:])
        target.write_text("".join(result), encoding="utf-8", newline="\n")


def prepare(destination: Path, archive_cache: Path) -> dict[str, Path]:
    manifest = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
    patch = (ROOT.parent / "tailscale-protect.patch").read_bytes()
    if digest(patch) != manifest["patch_sha256"]:
        raise ValueError("Tailscale protect patch checksum mismatch")
    archives = []
    for package in manifest["packages"]:
        archive = archive_cache / f"{package['name']}-{package['version']}.crate"
        blob = archive.read_bytes()
        if digest(blob) != package["sha256"]:
            raise ValueError(f"locked official crate checksum mismatch: {archive.name}")
        archives.append((package, blob))
    helper_files = []
    for entry in manifest["helper_files"]:
        blob = (ROOT / "underlay" / entry["path"]).read_bytes()
        if digest(blob) != entry["sha256"]:
            raise ValueError(f"underlay helper checksum mismatch: {entry['path']}")
        helper_files.append((entry["path"], blob))
    destination.mkdir(parents=True, exist_ok=False)
    paths = {}
    for package, blob in archives:
        prefix = f"{package['name']}-{package['version']}"
        with tarfile.open(fileobj=io.BytesIO(blob), mode="r:gz") as archive:
            for member in archive.getmembers():
                path = PurePosixPath(member.name)
                if path.is_absolute() or ".." in path.parts or not path.parts or path.parts[0] != prefix:
                    raise ValueError("unsafe official crate archive member")
                target = destination.joinpath(*path.parts)
                if member.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                elif member.isfile():
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(archive.extractfile(member).read())
                else:
                    raise ValueError("unsupported official crate archive member")
        paths[package["name"]] = destination / prefix
    for entry in manifest["files"]:
        if digest((destination / entry["path"]).read_bytes()) != entry["before_sha256"]:
            raise ValueError(f"original dependency source mismatch: {entry['path']}")
    replay_patch(destination, patch.decode("utf-8"))
    for entry in manifest["files"]:
        if digest((destination / entry["path"]).read_bytes()) != entry["after_sha256"]:
            raise ValueError(f"adapted dependency source mismatch: {entry['path']}")
    helper = destination / "aerove-ts-underlay"
    for path, blob in helper_files:
        target = helper / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(blob)
    paths["aerove-ts-underlay"] = helper
    (destination / "source-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return paths


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--destination", type=Path, required=True)
    parser.add_argument("--archive-cache", type=Path, required=True)
    args = parser.parse_args()
    paths = prepare(args.destination, args.archive_cache)
    print(json.dumps({name: str(path) for name, path in paths.items()}, indent=2))
