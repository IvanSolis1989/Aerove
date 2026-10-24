"""Infer only the app ABI referenced by pre-R8 AndroidTest classfiles using R8.

The Gradle consumer supplies real scoped class artifacts and compile/boot libraries.
No source-name inference, hand-maintained ABI list or missing-reference suppression.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


def collect_classes(paths, excluded=frozenset()):
    """Normalize class directories/jars and reject ambiguous class definitions."""
    classes = {}
    for path in sorted({Path(item).resolve() for item in paths}):
        if path.is_dir():
            entries = ((file.relative_to(path).as_posix(), file.read_bytes())
                       for file in sorted(path.rglob("*.class"))
                       if file.relative_to(path).as_posix() not in excluded)
            archive = None
        else:
            archive = zipfile.ZipFile(path)
            entries = ((name, archive.read(name)) for name in sorted(archive.namelist())
                       if name.endswith(".class") and name not in excluded)
        try:
            for name, image in entries:
                if name in excluded or name == "module-info.class" or name.startswith("META-INF/"):
                    continue
                if not image.startswith(b"\xca\xfe\xba\xbe"):
                    raise ValueError(f"Not a classfile: {path}!{name}")
                previous = classes.get(name)
                if previous is not None and previous != image:
                    raise ValueError(f"Conflicting class: {name} in {path}")
                classes[name] = image
        finally:
            if archive is not None:
                archive.close()
    return classes


def write_archive(path, classes):
    """Stable archives avoid treating input traversal order as an ABI change."""
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as stream:
        for name, image in sorted(classes.items()):
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            stream.writestr(entry, image)


def generate(manifest, output, report):
    output, report = Path(output), Path(report)
    output.parent.mkdir(parents=True, exist_ok=True)
    report.parent.mkdir(parents=True, exist_ok=True)
    # A failed rerun must not expose a previous successful rules/provenance pair.
    output.unlink(missing_ok=True)
    report.unlink(missing_ok=True)
    sources = collect_classes(manifest["sources"])
    targets = collect_classes(manifest["targets"])
    if not sources or not targets:
        raise ValueError("Both pre-R8 AndroidTest and app classfiles are required")
    overlap = set(sources) & set(targets)
    if overlap:
        raise ValueError("Test/app project class overlap: " + ", ".join(sorted(overlap)))
    # Shared runtime dependencies live in the target APK; test-only runtime code
    # is also a source, since its calls can cross into that same target APK.
    sources = collect_classes(manifest["sources"] + manifest.get("sourceDependencies", []),
                              excluded=set(targets))
    # The test compile classpath includes the app's compile jar. Its definitions
    # must remain targets, never library classes that would evade generated keeps.
    libraries = collect_classes(manifest["libraries"], excluded=set(sources) | set(targets))
    if not libraries:
        raise ValueError("The complete Android boot/compile library classpath is required")
    classpath = [Path(item).resolve(strict=True) for item in manifest["r8Classpath"]]
    if not classpath:
        raise ValueError("Use the R8 classpath already loaded by AGP")
    with tempfile.TemporaryDirectory(prefix="android-test-abi-", dir=output.parent) as temporary:
        work = Path(temporary)
        inputs = {}
        for role, classes in [("source", sources), ("target", targets), ("library", libraries)]:
            path = work / f"{role}.jar"
            write_archive(path, classes)
            inputs[role] = dict(classes=len(classes), sha256=hashlib.sha256(path.read_bytes()).hexdigest())
        # Only R8's allowobfuscation is enabled. It preserves members and call
        # shape while the existing app -> test applymapping flow handles names.
        generated = work / "rules.pro"
        command = [manifest["java"], "-Xmx768m", "-cp", os.pathsep.join(map(str, classpath)),
                   "com.android.tools.r8.tracereferences.TraceReferences", "--keep-rules", "--allowobfuscation",
                   "--source", str(work / "source.jar"), "--target", str(work / "target.jar"),
                   "--lib", str(work / "library.jar"), "--output", str(generated)]
        subprocess.run(command, check=True, timeout=300)
        rules = generated.read_bytes()
        metadata = dict(schema=1, generator="R8 TraceReferences", allowObfuscation=True,
                        inputClasses=inputs, rulesSha256=hashlib.sha256(rules).hexdigest(),
                        r8ClasspathSha256=[hashlib.sha256(path.read_bytes()).hexdigest() for path in classpath])
        staged_report = work / "report.json"
        staged_report.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
        # Publish only after strict TraceReferences exits successfully.
        try:
            os.replace(staged_report, report)
            os.replace(generated, output)
        except OSError:
            output.unlink(missing_ok=True)
            report.unlink(missing_ok=True)
            raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()
    generate(json.loads(args.manifest.read_text(encoding="utf-8")), args.output, args.report)


if __name__ == "__main__":
    main()
