"""Stage opt-in physical QA source files; never edit the stock tree or build/install."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import tempfile

UI = Path("apps/aerove-lite/app/src/androidTest/java/dev/aerove/lite/FlutterProductionUiTest.kt")
RUNNER = Path("apps/aerove-lite/android-platform-qa/src/main/java/dev/aerove/lite/platformqa")
EXACT_UI = "2fc86477eba49a1bbfa1c4d91558ff995e263110ef26ea785defcbcd7f839585"
ANCHOR = "    private fun functionalReceipt(fixture: String) {"
DRIVER = Path(__file__).parent / "driver"
FILES = ("PhysicalSocketDriver.kt", "PhysicalVpnScenario.kt", "ProductionPhysicalVpnBridge.kt", "PhysicalRunEvidence.kt")


def stage(source, out):
    base = (source / UI).read_bytes()
    if hashlib.sha256(base).hexdigest() != EXACT_UI:
        raise ValueError("Exact #373 Flutter UI driver is required; no fuzzy patching")
    if out.exists() or out.is_symlink() or not out.parent.is_dir():
        raise ValueError("Overlay destination must be a new directory under an existing parent")
    body = base.decode("utf-8")
    if body.count(ANCHOR) != 1:
        raise ValueError("Physical UI anchor differs")
    method = (DRIVER / "physical_ui_method.kt.in").read_text()
    if method.count("fun physicalOnlyOrdinaryAppTcp()") != 1:
        raise ValueError("Physical selector missing or repeated")
    scratch = Path(tempfile.mkdtemp(prefix=".physical-overlay-", dir=out.parent))
    try:
        target = scratch / UI
        target.parent.mkdir(parents=True)
        target.write_text(body.replace(ANCHOR, method.rstrip() + "\n\n" + ANCHOR, 1))
        path = scratch / RUNNER
        path.mkdir(parents=True)
        for name in FILES:
            shutil.copyfile(DRIVER / name, path / name)
        os.rename(scratch, out)
    finally:
        if scratch.exists():
            shutil.rmtree(scratch)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    try:
        stage(args.source, args.out)
    except (OSError, ValueError) as exc:
        parser.exit(2, f"Could not stage physical-only overlay: {exc}\n")


if __name__ == "__main__":
    main()
