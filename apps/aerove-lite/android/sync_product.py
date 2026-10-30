"""Keep the thin Flutter manifest aligned with Lite's version authority."""

import argparse
from pathlib import Path
import re


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    product = dict(
        line.split("=", 1)
        for line in (root / "version.properties").read_text().splitlines()
        if line and not line.startswith("#")
    )
    expected = f"version: {product['version']}+{product['versionCode']}"
    manifest = root / "pubspec.yaml"
    original = manifest.read_text(encoding="utf-8")
    updated, count = re.subn(r"(?m)^version: [^\r\n]+$", expected, original)
    if count != 1:
        raise SystemExit("Expected exactly one pubspec version field")
    if updated != original:
        if not args.write:
            raise SystemExit(f"Version drift: run with --write to set {expected}")
        manifest.write_text(updated, encoding="utf-8", newline="\n")
    print(expected)


if __name__ == "__main__":
    main()
