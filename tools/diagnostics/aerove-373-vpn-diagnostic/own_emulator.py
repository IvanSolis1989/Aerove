"""Use the original emulator lifecycle with an isolated, fixed diagnostic command."""
import argparse
import os
from pathlib import Path
import subprocess
import sys

import emulator_current as owner
from consent_gate import need
from original_runtime import fetch, pinned_runtime, stage


def bind_runtime():
    # Process-local adapter: formal source, commands, and verifier entry points remain unchanged.
    owner.verify_current_runtime = lambda directory: pinned_runtime(directory, owner.APK_ROOT)


def command(phase, directory, flutter_inputs=None):
    need(phase == "flutter-full" and flutter_inputs is not None, "Only the fixed #373 diagnostic may run")
    sdk = Path(os.environ["ANDROID_HOME"])
    return [sys.executable, str(Path(__file__).with_name("run_vpn_diagnostic.py").resolve()),
            "--apk", str(flutter_inputs["apk"]), "--test-apk", str(flutter_inputs["test_apk"]),
            "--platform-apk", str(flutter_inputs["platform_apk"]), "--build-info", str(flutter_inputs["build_info"]),
            "--adb", str(sdk / "platform-tools/adb"),
            "--apksigner", str(sdk / "build-tools/37.0.0/apksigner"), "--serial", "emulator-5554",
            "--output", str(Path(directory) / "vpn-diagnostic")]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runtime", type=Path)
    args = parser.parse_args()
    need(os.environ.get("AEROVE_GUEST_ANGLE") == "1" and os.environ.get("ANDROID_SERIAL") == "emulator-5554"
         and not any(k.startswith("AEROVE_") and k != "AEROVE_GUEST_ANGLE" for k in os.environ),
         "Runner environment contains alternate AEROVE execution controls")
    args.runtime = args.runtime.resolve()
    adb = Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb"
    with adb.open("rb"):
        pass
    inventory = subprocess.check_output([adb, "devices"], text=True, timeout=15)
    need(inventory.strip() == "List of devices attached", "Refusing any preexisting ADB target")
    fetch(args.runtime)
    os.environ.pop("GH_TOKEN", None)  # Do not pass the artifact credential to the emulator or fixture.
    stage(args.runtime, owner.APK_ROOT)
    bind_runtime()
    owner.phase_acceptance_command = command
    sys.argv = [__file__, "flutter-full", "--runtime-directory", str(args.runtime)]
    return owner.main()


if __name__ == "__main__":
    raise SystemExit(main())
