"""Fixed disclosure from an isolated Flutter AOT task; never publish logs or output."""
import argparse
import hashlib
import json
from pathlib import Path
import re

ALLOWED = {
    "flutter_native_assets": rb"Target (?:[a-zA-Z0-9_-]*native_assets[a-zA-Z0-9_-]*|install_code_assets) failed:|Failed to build native assets",
    "rust_hook": rb"error(?:\[[A-Z0-9]+\])?: could not compile\b|BINDGEN[^\r\n]{0,60}error|fatal error: ['\"]stdbool\.h['\"] file not found",
    "dart_kernel": rb"Target (?:kernel_snapshot(?:_program)?|app_kernel)[a-zA-Z0-9_-]* failed:|Kernel snapshot generation failed",
    "dart_aot": rb"Target android_aot[a-zA-Z0-9_-]* failed:|Dart AOT compiler exited|gen_snapshot(?: exited| failed)",
    "process_signal": rb"Process ['\"][^\r\n]{0,250}['\"] finished with non-zero exit value (?:137|143)\b",
    "disk_full": rb"No space left on device",
    "gradle_daemon_exit": rb"Gradle build daemon disappeared unexpectedly",
}
PHASES = ([b"flutter_task_started"], [b"flutter_task_started", b"flutter_task_completed"])


# Never emit matched text, raw coordinates, file paths, hostnames or hashes of
# diagnostics. Only literal labels below may leave the runner.
FATAL_CATEGORIES = {
    "kotlin_asset_integrity": rb"AEROVE pinned Kotlin asset integrity failure",
    "kotlin_asset_missing": rb"kotlin-compiler-embeddable-2\.3\.21\.(?:jar|pom)[^\r\n]{0,120}(?:No such file|not found)",
    "exclusive_repository_conflict": rb"exclusive content[^\r\n]{0,240}(?:repository|repositories)|(?:repository|repositories)[^\r\n]{0,240}exclusive content",
    "flutter_sdk_missing": rb"Set flutter\.sdk in android/local\.properties or FLUTTER_ROOT|Flutter SDK not found",
    "missing_flutter_project": rb"Missing expected Flutter app project",
    "missing_flutter_task": rb"Task ['\"](?:[a-zA-Z0-9_.:-]+:)?compileFlutterBuildRelease['\"][^\r\n]{0,140}(?:not found|does not exist)|Cannot locate tasks that match ['\"][^'\"\r\n]{0,100}compileFlutterBuildRelease",
    "repository_rate_limit": rb"status code 429\b|HTTP(?: Error)? 429\b|429 Too Many Requests",
    "repository_access_denied": rb"status code (?:401|403)\b|HTTP(?: Error)? (?:401|403)\b",
    "dependency_not_found": rb"status code 404\b|HTTP(?: Error)? 404\b|Could not find [a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+:[^\s]+",
    "repository_tls": rb"PKIX path building failed|SSLHandshakeException|certificate (?:has expired|verify failed)",
    "network_timeout": rb"Read timed out|Connection timed out|Connect timed out|UnknownHostException",
    "dependency_verification": rb"Dependency verification failed|checksum verification failed",
    "java_incompatible": rb"Unsupported class file major version|requires Java [0-9]+|requires JVM runtime version|No matching Java installation",
    "android_sdk_missing": rb"SDK location not found|Failed to find target with hash string|Failed to find Build Tools revision",
    "android_ndk_missing": rb"NDK[^\r\n]{0,160}(?:not found|did not have a source\.properties|was not found|does not match)",
    "kotlin_script_compilation": rb"Script compilation error|Unresolved reference|Kotlin compilation error",
    "init_script_compilation": rb"Could not compile initialization script",
    "settings_evaluation": rb"(?:problem|error) evaluat(?:ing|ion of) settings|Error resolving plugin",
    "plugin_resolution": rb"Plugin \[[^\r\n]{0,300}\] was not found|Could not resolve plugin artifact",
    "plugin_application": rb"Failed to apply plugin|An exception occurred applying plugin request",
    "dependency_resolution": rb"Could not (?:resolve all|download|GET)|Could not get resource",
    "project_configuration": rb"A problem occurred configur(?:ing|ation of) (?:root )?project|A problem occurred evaluating project",
    "gradle_daemon_exit": rb"Gradle build daemon disappeared unexpectedly",
    "disk_full": rb"No space left on device",
}
LOCATION = {
    "pinned_kotlin_init": rb"\baerove-pinned-kotlin\.gradle",
    "flutter_probe_init": rb"\bflutter_only\.init\.gradle",
    "settings_script": rb"\bsettings\.gradle(?:\.kts)?",
    "build_script": rb"\bbuild\.gradle(?:\.kts)?",
}


def first_gradle_failure(data):
    # Stop at the next canonical section. Gradle can report multiple failures;
    # keep only the first What went wrong block and its preceding Where section.
    opener = re.search(rb"(?m)^\* What went wrong:[ \t]*\r?$", data)
    if opener is None:
        for label in ("gradle_daemon_exit", "disk_full"):
            if re.search(FATAL_CATEGORIES[label], data, re.I):
                return {"site": None, "categories": [label]}
        return {"site": None, "categories": []}
    start = opener.end()
    recent = data[max(0, opener.start()-4096):opener.start()]
    where = re.search(rb"(?m)^\* Where:[ \t]*\r?\n([^\r\n]{1,1600})", recent)
    location = where.group(1) if where else b""
    window = data[start:start+16384]
    next_section = re.search(rb"(?m)^\* (?:Try|Exception is|Get more help|Where|What went wrong):", window)
    fatal = window[:next_section.start()] if next_section else window
    site = next((name for name, rule in LOCATION.items() if re.search(rule, location)), None)
    categories = [name for name, rule in FATAL_CATEGORIES.items() if re.search(rule, fatal, re.I)]
    return {"site": site, "categories": categories or ["unclassified_gradle_failure"]}


def output_receipt(project):
    digest = set()
    for candidate in (project / "build/app/intermediates/flutter").glob("release/**/app.so"):
        if candidate.is_symlink() or not candidate.is_file():
            continue
        with candidate.open("rb") as stream:
            header = stream.read(20)
            if header[:6] != b"\x7fELF\x02\x01" or header[18:20] != b"\xb7\x00":
                continue
            stream.seek(0)
            digest.add(hashlib.file_digest(stream, "sha256").hexdigest())
    return next(iter(digest)) if len(digest) == 1 else None


def collect(log, phases, project, code, seconds):
    with log.open("rb") as stream:
        size = stream.seek(0, 2)
        limit = 3 * 1024 * 1024
        offset = max(0, size-limit)
        stream.seek(offset)
        data = stream.read(limit)
    rows = phases.read_bytes().splitlines() if phases.stat().st_size <= 100 else [b"invalid"]
    phase = ("not_reached" if not rows else
             "completed" if rows == PHASES[1] else "started" if rows == PHASES[0] else "invalid")
    app = output_receipt(project)
    return dict(exitCode=code, elapsedSeconds=seconds, flutterTask=phase,
                tags=[key for key, pattern in ALLOWED.items() if re.search(pattern, data)],
                firstGradleFailure=first_gradle_failure(data) if code and phase == "not_reached" else None,
                logTailCapped=bool(offset), arm64AotSha256=app, pairedApkBuilt=False,
                releaseAccepted=False)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("log", type=Path)
    p.add_argument("phases", type=Path)
    p.add_argument("project", type=Path)
    p.add_argument("code", type=int)
    p.add_argument("seconds", type=int)
    a = p.parse_args()
    if not 0 <= a.code <= 255 or not 0 <= a.seconds <= 1200:
        p.error("invalid bounded diagnostics status")
    r = collect(a.log, a.phases, a.project, a.code, a.seconds)
    print(json.dumps(r, sort_keys=True))
    return 11 if a.code == 0 and (r["flutterTask"] != "completed" or not r["arm64AotSha256"]) else 0


if __name__ == "__main__":
    raise SystemExit(main())
