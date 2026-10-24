"""Infer only the app ABI referenced by pre-R8 AndroidTest classfiles using R8.

The Gradle consumer supplies real scoped class artifacts and compile/boot libraries.
Typed missing-reference contexts keep project/app ABI errors fatal; third-party runtime compatibility is recorded separately.
"""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import tempfile
import sys
import zipfile


def collect_classes(paths, excluded=frozenset(), *, role="program", origins_out=None):
    """Normalize class directories/jars and reject ambiguous class definitions."""
    classes, origins = {}, {}
    for path in sorted({Path(item).resolve() for item in paths}):
        if path.is_dir():
            entries = ((file.relative_to(path).as_posix(), file.read_bytes())
                       for file in sorted(path.rglob("*.class"))
                       if file.relative_to(path).as_posix() not in excluded)
            archive = None
        else:
            archive = zipfile.ZipFile(path)
            entries = ((entry.filename, archive.read(entry)) for entry in sorted(archive.infolist(), key=lambda item: item.filename)
                       if entry.filename.endswith(".class") and entry.filename not in excluded)
        try:
            for name, image in entries:
                if name in excluded or name == "module-info.class" or name.startswith("META-INF/"):
                    continue
                if not image.startswith(b"\xca\xfe\xba\xbe"):
                    raise ValueError(f"Not a classfile: {path}!{name}")
                previous = classes.get(name)
                if previous is not None and previous != image:
                    raise ValueError(f"Conflicting {role} class: {name}; first={origins[name]} "
                                     f"sha256={hashlib.sha256(previous).hexdigest()}; "
                                     f"conflict={path}!{name} sha256={hashlib.sha256(image).hexdigest()}")
                classes[name] = image
                origins.setdefault(name, f"{path}!{name}")
        finally:
            if archive is not None:
                archive.close()
    if origins_out is not None:
        origins_out.update(origins)
    return classes


def collect_libraries(paths, excluded=frozenset()):
    """Match AGP's ordered library provider; program definitions stay strict."""
    classes, origins, shadows = {}, {}, []
    # Keep Gradle classpath order. Sorting paths changes which platform ABI wins.
    for path in dict.fromkeys(Path(item).resolve() for item in paths):
        for name, image in collect_classes([path], excluded, role="library input").items():
            if name in classes:
                shadows.append(dict(className=name, selected=origins[name],
                    selectedSha256=hashlib.sha256(classes[name]).hexdigest(),
                    shadowed=f"{path}!{name}", shadowedSha256=hashlib.sha256(image).hexdigest(),
                    identical=classes[name] == image))
            else:
                classes[name] = image
                origins[name] = f"{path}!{name}"
    return classes, shadows


def input_evidence(manifest):
    """Retain exact ordered inputs even when normalization or tracing fails."""
    result = {}
    for role in ("sources", "sourceDependencies", "targets", "libraries", "r8Classpath"):
        result[role] = []
        for item in manifest.get(role, []):
            path = Path(item).resolve()
            entry = dict(path=str(path))
            if path.is_file():
                entry["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
            elif path.is_dir():
                files = {p.relative_to(path).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                         for p in sorted(path.rglob("*.class"))}
                entry.update(classes=len(files), classManifestSha256=hashlib.sha256(
                    json.dumps(files, sort_keys=True, separators=(",", ":")).encode()).hexdigest())
            else:
                entry["missing"] = True
            result[role].append(entry)
    return result


def write_archive(path, classes):
    """Stable archives avoid treating input traversal order as an ABI change."""
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as stream:
        for name, image in sorted(classes.items()):
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            stream.writestr(entry, image)


def _generate(manifest, output, report, diagnostics):
    project_origins, source_origins, target_origins = {}, {}, {}
    sources = collect_classes(manifest["sources"], role="test source", origins_out=project_origins)
    projects = set(sources)
    targets = collect_classes(manifest["targets"], role="app target", origins_out=target_origins)
    if not sources or not targets:
        raise ValueError("Both pre-R8 AndroidTest and app classfiles are required")
    overlap = set(sources) & set(targets)
    if overlap:
        raise ValueError("Test/app project class overlap: " + ", ".join(sorted(overlap)))
    # Shared runtime dependencies live in the target APK; test-only runtime code
    # is also a source, since its calls can cross into that same target APK.
    sources = collect_classes(manifest["sources"] + manifest.get("sourceDependencies", []),
                              excluded=set(targets), role="test source/dependency", origins_out=source_origins)
    # The test compile classpath includes the app's compile jar. Its definitions
    # must remain targets, never library classes that would evade generated keeps.
    libraries, shadows = collect_libraries(manifest["libraries"], excluded=set(sources) | set(targets))
    diagnostics["libraryShadows"] = shadows
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
        diagnostics["inputClasses"] = inputs
        # Only R8's allowobfuscation is enabled. It preserves members and call
        # shape while the existing app -> test applymapping flow handles names.
        generated = work / "rules.pro"
        helper_source = Path(manifest.get("traceHelper", Path(__file__).with_name("AndroidTestAppAbiTrace.java"))).resolve(strict=True)
        helper_classes = work / "helper"
        helper_classes.mkdir()
        compiler = manifest.get("javaCompiler") or [str(Path(manifest["java"]).with_name("javac" + Path(manifest["java"]).suffix))]
        compile_command = compiler + ["-encoding", "UTF-8", "-source", "17", "-target", "17", "-cp",
            os.pathsep.join(map(str, classpath)), "-d", str(helper_classes), str(helper_source)]
        diagnostics["helperSourceSha256"] = hashlib.sha256(helper_source.read_bytes()).hexdigest()
        diagnostics["helperCompileCommand"] = compile_command
        diagnostics["command"] = compile_command
        compiled = subprocess.run(compile_command, check=True, timeout=120, capture_output=True, text=True)
        diagnostics["helperCompileStdout"] = compiled.stdout
        diagnostics["helperCompileStderr"] = compiled.stderr
        boundary_files = []
        namespaces = manifest.get("productNamespaces", ["dev.aerove.lite"])
        if (not isinstance(namespaces, list) or not namespaces or any(not isinstance(n, str)
                or re.fullmatch(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*", n) is None for n in namespaces)):
            raise ValueError("Explicit product package namespaces are required")
        values = [projects, set(sources) - projects, set(targets)]
        for role, names in zip(("project", "dependency", "target"), values):
            path = work / (role + "-descriptors.txt")
            path.write_text("".join("L" + name[:-6] + ";\n" for name in sorted(names)), encoding="utf-8")
            boundary_files.append(path)
        namespace_file = work / "product-prefixes.txt"
        namespace_file.write_text("".join("L" + n.replace(".", "/") + "/\n" for n in namespaces), encoding="utf-8")
        typed_report = work / "typed-references.json"
        command = [manifest["java"], "-Xmx768m", "-cp", os.pathsep.join(map(str, [helper_classes] + classpath)),
                   "AndroidTestAppAbiTrace", str(typed_report)] + list(map(str, boundary_files)) + [str(namespace_file),
                   "--keep-rules", "--allowobfuscation", "--source", str(work / "source.jar"),
                   "--target", str(work / "target.jar"), "--lib", str(work / "library.jar"), "--output", str(generated)]
        diagnostics["command"] = command
        try:
            completed = subprocess.run(command, check=True, timeout=300, capture_output=True, text=True)
            diagnostics.update(stdout=completed.stdout, stderr=completed.stderr)
        finally:
            if typed_report.exists():
                diagnostics["referenceDiagnostics"] = json.loads(typed_report.read_text(encoding="utf-8"))
                for item in diagnostics["referenceDiagnostics"]["missingDefinitions"]:
                    owner = item["holder"][1:-1] + ".class"
                    if owner in target_origins:
                        item["targetInputOrigin"] = target_origins[owner]
                        item["targetClassSha256"] = hashlib.sha256(targets[owner]).hexdigest()
                    for context in item["contexts"]:
                        name = context["holder"][1:-1] + ".class"
                        origins = project_origins if context["role"] == "PROJECT" else source_origins
                        if name in origins and name in sources:
                            context["inputOrigin"] = origins[name]
                            context["classSha256"] = hashlib.sha256(sources[name]).hexdigest()
                diagnostics["referenceDiagnostics"]["productNamespaces"] = namespaces
                diagnostics["referenceDiagnostics"]["boundaryClassCounts"] = dict(
                    project=len(projects), testDependency=len(set(sources) - projects), target=len(targets))
        typed = diagnostics.get("referenceDiagnostics", {})
        if typed.get("traceCompleted") is not True or typed.get("fatalCount") != 0:
            raise ValueError("Typed TraceReferences did not accept the protected app boundary")
        rules = generated.read_bytes()
        metadata = dict(schema=3, generator="R8 TraceReferences", allowObfuscation=True,
                        orderedInputs=diagnostics["orderedInputs"], libraryShadows=shadows,
                        referenceDiagnostics=typed, helperSourceSha256=diagnostics["helperSourceSha256"],
                        inputClasses=inputs, rulesSha256=hashlib.sha256(rules).hexdigest(),
                        r8ClasspathSha256=[hashlib.sha256(path.read_bytes()).hexdigest() for path in classpath])
        staged_report = work / "report.json"
        staged_report.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
        # Publish only after typed project/app boundary checks exit successfully.
        try:
            os.replace(staged_report, report)
            os.replace(generated, output)
        except OSError:
            output.unlink(missing_ok=True)
            report.unlink(missing_ok=True)
            raise


def generate(manifest, output, report):
    output, report = Path(output), Path(report)
    output.parent.mkdir(parents=True, exist_ok=True)
    report.parent.mkdir(parents=True, exist_ok=True)
    failure = report.with_name(report.name + ".failure.json")
    # Failed reruns cannot expose a stale successful rules/provenance pair.
    for path in (output, report, failure):
        path.unlink(missing_ok=True)
    diagnostics = dict(schema=1, status="failed", manifest=manifest)
    try:
        diagnostics["orderedInputs"] = input_evidence(manifest)
        _generate(manifest, output, report, diagnostics)
    except Exception as error:
        output.unlink(missing_ok=True)
        report.unlink(missing_ok=True)
        diagnostics.update(errorType=type(error).__name__, error=str(error),
                           stdout=getattr(error, "stdout", None) or diagnostics.get("stdout", ""),
                           stderr=getattr(error, "stderr", None) or diagnostics.get("stderr", ""))
        for key in ("stdout", "stderr"):
            if isinstance(diagnostics[key], bytes):
                diagnostics[key] = diagnostics[key].decode("utf-8", errors="replace")
        failure.write_text(json.dumps(diagnostics, indent=2) + "\n", encoding="utf-8")
        print(f"AndroidTest ABI generation failed; exact inputs and tool diagnostics: {failure}", file=sys.stderr)
        print("Ordered ABI inputs: " + json.dumps(diagnostics.get("orderedInputs", manifest), sort_keys=True), file=sys.stderr)
        if diagnostics["stderr"]:
            print(diagnostics["stderr"], file=sys.stderr)
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
