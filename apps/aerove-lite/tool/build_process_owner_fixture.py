"""Build the separate-UID test dependency using only already installed CI SDK tools."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import zipfile

PACKAGE = "dev.aerove.fixture.socketowner"
SOURCE = Path(__file__).with_name("process_owner_fixture")
SDK_PLATFORM = "android-37.1"
BUILD_TOOLS_VERSION = "37.0.0"
SDK_TOOL_NAMES = ("aapt2", "aapt", "d8", "dexdump", "zipalign", "apksigner")


def installed_tools(sdk):
    android = sdk / "platforms" / SDK_PLATFORM / "android.jar"
    if not android.is_file():
        raise ValueError("Required installed SDK platform is missing: " + SDK_PLATFORM + "; no version fallback")
    tools = {name: sdk / "build-tools" / BUILD_TOOLS_VERSION / name for name in SDK_TOOL_NAMES}
    if not all(path.is_file() for path in tools.values()):
        raise ValueError("Required installed SDK build-tools are incomplete: " + BUILD_TOOLS_VERSION + "; no version fallback")
    for name in ("javac", "keytool"):
        found = shutil.which(name)
        if found is None:
            raise ValueError("Missing existing CI JDK tool: " + name)
        tools[name] = Path(found)
    return android, tools


def build(output, deadline, run):
    if sys.platform != "linux" or os.environ.get("CI") != "true" or os.environ.get("GITHUB_ACTIONS") != "true":
        raise ValueError("Fixture compilation is restricted to the existing Linux CI owner")
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ["ANDROID_HOME"])
    android, tools = installed_tools(sdk)
    output.mkdir(parents=True, exist_ok=False)
    classes = output / "classes"
    classes.mkdir()
    source = SOURCE / "src/dev/aerove/fixture/socketowner/SocketOwnerService.java"
    manifest = SOURCE / "AndroidManifest.xml"
    run([str(tools["javac"]), "-source", "8", "-target", "8", "-bootclasspath", str(android), "-d", str(classes), str(source)], deadline)
    bytecode = sorted(str(path) for path in classes.rglob("*.class"))
    run([str(tools["d8"]), "--min-api", "33", "--lib", str(android), "--output", str(output), *bytecode], deadline)
    unsigned = output / "unsigned.apk"
    run([str(tools["aapt2"]), "link", "-I", str(android), "--manifest", str(manifest), "-o", str(unsigned)], deadline)
    with zipfile.ZipFile(unsigned, "a") as archive:
        archive.write(output / "classes.dex", "classes.dex")
    aligned = output / "aligned.apk"
    run([str(tools["zipalign"]), "-f", "4", str(unsigned), str(aligned)], deadline)
    key = output / "fixture-only.jks"
    run([str(tools["keytool"]), "-genkeypair", "-keystore", str(key), "-storepass", "android", "-keypass", "android",
         "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=AEROVE CI socket fixture"], deadline)
    apk = output / "socket-owner-fixture.apk"
    run([str(tools["apksigner"]), "sign", "--ks", str(key), "--ks-pass", "pass:android", "--key-pass", "pass:android",
         "--out", str(apk), str(aligned)], deadline)
    signature = run([str(tools["apksigner"]), "verify", "--verbose", "--print-certs", str(apk)], deadline)
    (output / "signature.txt").write_text(signature)
    badging = run([str(tools["aapt"]), "dump", "badging", str(apk)], deadline)
    manifest_dump = run([str(tools["aapt"]), "dump", "xmltree", str(apk), "AndroidManifest.xml"], deadline)
    dex = run([str(tools["dexdump"]), "-f", str(output / "classes.dex")], deadline)
    for name, value in (("badging.txt", badging), ("manifest.txt", manifest_dump), ("dex.txt", dex)):
        (output / name).write_text(value)
    verify_compiled(apk, badging, manifest_dump, dex)
    record = dict(package=PACKAGE, apkSha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
                  files={str(path.relative_to(SOURCE)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(SOURCE.rglob("*")) if path.is_file()},
                  androidJar=str(android), tools={key: str(value) for key, value in tools.items()},
                  role="test-only-separate-UID-dependency", applicationArtifact=False)
    (output / "BUILD.json").write_text(json.dumps(record, indent=2) + "\n")
    return apk, record


def verify_compiled(apk, badging, manifest, dex):
    if (re.findall(r"^package: name='([^']+)'", badging, re.M) != [PACKAGE]
            or re.findall(r"^sdkVersion:'([^']+)'", badging, re.M) != ["33"]
            or re.findall(r"^targetSdkVersion:'([^']+)'", badging, re.M) != ["33"]
            or "sharedUserId" in manifest or "E: instrumentation" in manifest or "E: activity" in manifest
            or re.findall(r"^uses-permission: name='([^']+)'", badging, re.M) != ["android.permission.INTERNET"]
            or len(re.findall(r"E: service ", manifest)) != 1
            or not re.search(r'android:name\([^\n]+="\.SocketOwnerService"', manifest)
            or not re.search(r'android:exported\([^\n]+=\(type 0x12\)0xffffffff', manifest)
            or not re.search(r'android:testOnly\([^\n]+=\(type 0x12\)0xffffffff', manifest)
            or "Class descriptor  : 'Ldev/aerove/fixture/socketowner/SocketOwnerService;'" not in dex):
        raise ValueError("Compiled fixture manifest/package/UID boundary/DEX differs from the test dependency")
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if (len(names) != len(set(names)) or "AndroidManifest.xml" not in names or "classes.dex" not in names
                or any(name.startswith(("lib/", "assets/")) for name in names)
                or not archive.read("classes.dex").startswith(b"dex\n")):
            raise ValueError("Fixture APK payload is incomplete or contains product assets")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--deadline-seconds", type=int, choices=range(1, 121), default=120)
    args = parser.parse_args()
    if sys.platform != "linux" or os.environ.get("CI") != "true" or os.environ.get("GITHUB_ACTIONS") != "true":
        raise ValueError("Build-only fixture preflight requires the existing Linux CI owner")
    if args.output.exists():
        raise ValueError("Never overwrite an existing fixture preflight output")
    os.nice(19)
    commands = []
    def run(argv, deadline):
        if time.time() >= deadline:
            raise TimeoutError("Fixture preflight deadline expired")
        result = subprocess.run(argv, capture_output=True, text=True, timeout=max(0.01, deadline - time.time()))
        commands.append(dict(argv=argv, exit=result.returncode, stdout=result.stdout, stderr=result.stderr))
        result.check_returncode()
        return result.stdout
    try:
        apk, receipt = build(args.output, time.time() + args.deadline_seconds, run)
        print(json.dumps(dict(apk=str(apk), **receipt), indent=2))
    finally:
        if args.output.is_dir():
            (args.output / "commands.json").write_text(json.dumps(commands, indent=2) + "\n")


if __name__ == "__main__":
    main()
