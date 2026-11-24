"""Render only reviewed, scalar boot-test evidence; never echo private JSON errors."""
import argparse
import hashlib
import json
from pathlib import Path
import re

SEQUENCE = [(1, "A"), (1, "B"), (2, "B"), (2, "A"), (3, "A"), (3, "B"), (4, "B"), (4, "A")]
PINS = {"A": "37.2.13.0 (16740729)", "B": "37.3.10.0 (16970787)"}
STAGES = {"bootstrap", "graphics", "stability"}
HEXDIGEST = re.compile("[0-9a-f]{64}")


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def sanitize(receipt, *, completed_process, receipt_digest):
    try:
        rows = receipt["results"]
        planned = tuple(tuple(pair) for pair in receipt["pairs"])
        expected = (("A", "B"), ("B", "A"), ("A", "B"), ("B", "A"))
        if (receipt["diagnosticOnly"] is not True or receipt["acceptance"] is not False
                or receipt["seed"] != "AEROVE-373-host-pin-a" or planned != expected
                or type(rows) is not list or len(rows) > len(SEQUENCE)):
            raise ValueError()
        for index, row in enumerate(rows):
            if (type(row["round"]) is not int or (row["round"], row["arm"]) != SEQUENCE[index]
                    or row["status"] not in {"failed", "boot-and-route-observed"}):
                raise ValueError()
            if row["status"] == "failed" and row.get("failureStage") not in STAGES:
                raise ValueError()
            if row["status"] == "boot-and-route-observed" and (row.get("failureStage") is not None
                    or not row.get("graphicsRoute") or not row.get("graphicsFilesSha256")
                    or not isinstance(row.get("cleanup"), dict)
                    or type(row["cleanup"].get("returnCode")) is not int):
                raise ValueError()
        complete = completed_process and len(rows) == len(SEQUENCE) and receipt.get("experimentComplete") is True
        if not HEXDIGEST.fullmatch(receipt_digest):
            raise ValueError()
    except (KeyError, ValueError, TypeError):
        return None
    result = ["### AEROVE emulator boot-only A/B", "", "No APK or app acceptance. Raw runner files were not uploaded.", "",
              f"| Arm | Emulator |", "| --- | --- |", f"| A | {PINS['A']} |", f"| B | {PINS['B']} |",
              "", "| Round | Arm | Result |", "| --- | --- | --- |"]
    for row in rows:
        state = "boot + graphics + stability observed" if row["status"] != "failed" else "failed: " + row["failureStage"]
        result.append(f"| {row['round']} | {row['arm']} | {state} |")
    result.append("")
    result.append("Experiment completed." if complete else "Experiment incomplete. No comparison conclusion.")
    if complete:
        counts = {arm: sum(r["arm"] == arm and r["status"] == "boot-and-route-observed" for r in rows) for arm in PINS}
        result.append(f"Boot observations: A {counts['A']}/4; B {counts['B']}/4.")
    result.append(f"Ephemeral private receipt SHA-256: `{receipt_digest}`.")
    return "\n".join(result) + "\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--completed-process", action="store_true")
    args = parser.parse_args()
    report = None
    try:
        if args.receipt.is_file() and not args.receipt.is_symlink() and args.receipt.stat().st_size <= 2_000_000:
            report = sanitize(json.loads(args.receipt.read_text()), completed_process=args.completed_process,
                              receipt_digest=digest(args.receipt))
    except (OSError, UnicodeError, ValueError):
        pass
    print(report or "### AEROVE emulator boot-only A/B\n\nNo validated boot observation. Raw runner files were not uploaded.",
          end="" if report else "\n")


if __name__ == "__main__":
    main()
