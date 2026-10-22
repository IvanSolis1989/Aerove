#!/usr/bin/env python3
"""Fetch or verify the immutable production GeoSite regression fixture."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import urllib.request

GEOSITE_URL = "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/download/202406182210/geosite.dat"
GEOSITE_SHA256 = "fbc2fbe784f2a6ce96166bf5185b374180b68e9a791f32d2a47393a6585247f7"
GEOSITE_BYTES = 6111771


def verify_geosite(path: Path) -> Path:
    path = path.resolve(strict=True)
    if not path.is_file() or path.stat().st_size != GEOSITE_BYTES:
        raise ValueError("production GeoSite fixture size mismatch")
    hasher = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(65536), b""):
            hasher.update(block)
    if hasher.hexdigest() != GEOSITE_SHA256:
        raise ValueError("production GeoSite fixture SHA256 mismatch")
    return path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--download", action="store_true", help="Download the fixed release asset if missing")
    args = parser.parse_args()
    if not args.output.exists() and args.download:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        request = urllib.request.Request(GEOSITE_URL, headers={"User-Agent": "AEROVE-Lite-reference-test"})
        with urllib.request.urlopen(request, timeout=60) as source:
            data = source.read(GEOSITE_BYTES + 1)
        if len(data) != GEOSITE_BYTES or hashlib.sha256(data).hexdigest() != GEOSITE_SHA256:
            raise ValueError("downloaded production GeoSite fixture does not match the fixed size/hash")
        args.output.write_bytes(data)
    path = verify_geosite(args.output)
    print(json.dumps({"path": str(path), "bytes": GEOSITE_BYTES, "sha256": GEOSITE_SHA256, "source": GEOSITE_URL}))


if __name__ == "__main__":
    main()
