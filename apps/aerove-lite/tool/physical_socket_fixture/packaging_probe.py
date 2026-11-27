"""Bounded evidence for a disposable same-graph ARM64 packaging attempt."""
import argparse
import json
from pathlib import Path
import re

from flutter_only_report import collect, first_gradle_failure
from keep_boundary import evidence
from paired_assembly_summary import summarize

GRAPH_TASK = re.compile(rb"^(:[a-zA-Z0-9_:-]+) SKIPPED\r?$", re.M)
REQUIRED = (
    b":app:compileFlutterBuildRelease",
    b":app:compileReleaseKotlin",
    b":platform-qa:compileReleaseKotlin",
    b":app:generateReleasePlatformTestAppKeepRules",
    b":app:minifyReleaseWithR8",
    b":platform-qa:minifyReleaseWithR8",
    b":app:packageRelease",
    b":platform-qa:packageRelease",
    b":app:assembleRelease",
    b":platform-qa:assembleRelease",
)
ORDERED_ABI = REQUIRED[1:6]

PLUGIN_IDS = tuple(name.encode("ascii") for name in (
    "aerove.lite-app-common", "com.android.application", "com.android.test",
    "dev.flutter.flutter-gradle-plugin", "dev.flutter.flutter-plugin-loader",
    "org.gradle.kotlin.kotlin-dsl", "org.jetbrains.compose",
    "org.jetbrains.kotlin.android", "org.jetbrains.kotlin.jvm",
    "org.jetbrains.kotlin.plugin.compose", "org.jetbrains.kotlin.plugin.serialization",
))
PLUGIN_FAILURE = re.compile(
    rb"Plugin \[id: '([^'\r\n]{1,240})'[^\r\n]{0,180}\] was not found", re.M)
BUILD_ROLE = {
    "lite_build_logic": rb"/apps/aerove-lite/build-logic/(?:settings|build)\.gradle(?:\.kts)?['\"]",
    "lite_android_app": rb"/apps/aerove-lite/android/app/build\.gradle(?:\.kts)?['\"]",
    "lite_android_root": rb"/apps/aerove-lite/android/(?:settings|build)\.gradle(?:\.kts)?['\"]",
    "lite_platform_qa": rb"/apps/aerove-lite/android-platform-qa/build\.gradle(?:\.kts)?['\"]",
    "flutter_gradle_tools": rb"/packages/flutter_tools/gradle/(?:settings|build)\.gradle(?:\.kts)?['\"]",
}
REPOSITORY_HOST = {
    "maven_central": rb"https://repo\.(?:maven\.apache|maven2)\.org/",
    "gradle_plugin_portal": rb"https://plugins\.gradle\.org/",
    "google_maven": rb"https://(?:dl\.google\.com/dl/android/maven2/|maven\.google\.com/)",
}
HTTP_CODE = re.compile(rb"(?:status code |HTTP(?: Error)? ?|HTTP/[0-9.]+ )(401|403|404|429|500|502|503|504)\b", re.I)


def plugin_evidence(data):
    # The failure line binds the public ID to the failed request. Transport
    # evidence from --info is separate and does not establish what caused it.
    header = re.search(rb"(?m)^\* What went wrong:[ \t]*\r?$", data)
    if not header:
        return None
    leading = data[max(0, header.start() - 4096):header.start()]
    where = re.findall(rb"(?m)^\* Where:[ \t]*\r?\n([^\r\n]{1,1600})", leading)
    location = where[-1] if where else b""
    fatal = data[header.end():header.end() + 16384]
    fatal = re.split(rb"(?m)^\* (?:Try|Exception is|Get more help|Where|What went wrong):", fatal, 1)[0]
    match = PLUGIN_FAILURE.search(fatal)
    if not match:
        return None
    plugin_id = match[1]
    role = next((role for role, rule in BUILD_ROLE.items() if re.search(rule, location)), None)
    statuses = set()
    for row in data.splitlines()[-3000:]:
        row = row[:4096]
        codes = HTTP_CODE.findall(row)
        if not codes:
            continue
        for host, rule in REPOSITORY_HOST.items():
            if re.search(rule, row):
                statuses.update((host, int(code)) for code in codes)
    return dict(pluginId=plugin_id.decode("ascii") if plugin_id in PLUGIN_IDS else "other",
                buildRole=role, observedRepositoryHttp=[dict(repository=host, status=code)
                                                        for host, code in sorted(statuses)])

FAILED_TASK = re.compile(rb"(?m)^(?:Execution failed for task '([^'\r\n]{1,240})'\.|> Task ([^\s]{1,240}) FAILED)\r?$")
FAILURE_TASKS = frozenset(REQUIRED) | frozenset(
    b":" + module + b":" + task
    for module in (b"app", b"platform-qa")
    for task in (
        b"verifyNativeCore", b"verifyClientHelper", b"prepareCandidateIdentity", b"preparePlatformSources",
        b"prepareNativeMigrationFixtureAssets", b"mergeReleaseAssets", b"mergeReleaseNativeLibs",
        b"mergeReleaseResources", b"processReleaseResources", b"processReleaseMainManifest",
        b"compileReleaseJavaWithJavac", b"bundleReleaseClassesToCompileJar",
        b"checkReleaseAarMetadata", b"checkReleaseDuplicateClasses", b"validateSigningRelease",
        b"stripReleaseDebugSymbols", b"optimizeReleaseResources", b"shrinkReleaseRes",
    )
)
DOMAIN_ERRORS = {
    "candidate_checkout_mismatch": rb"Candidate source does not match checkout",
    "native_workflow_mismatch": rb"Meow receipt source/run/attempt mismatch",
    "native_source_mismatch": rb"Meow receipt source/lock/overlay identity mismatch",
    "native_binary_mismatch": rb"Meow receipt output/NDK/ABI mismatch|Meow ELF ABI/16KiB alignment invalid",
    "native_missing_jni_exports": rb"Missing Meow JNI exports",
    "kotlin_compiler": rb"(?m)^e: (?:file:|Compilation error)",
    "java_compiler": rb"(?m)^(?:[^\r\n]{1,450}\.java:\d+: )?error: (?:cannot find symbol|package [a-zA-Z.]+ does not exist)",
    "android_resources": rb"Android resource linking failed|AAPT2 error",
    "r8_missing_class": rb"Missing classes detected while running R8",
    "java_heap": rb"java\.lang\.OutOfMemoryError|Java heap space",
}


def failed_task_evidence(data):
    rows = FAILED_TASK.findall(data)
    if not rows:
        return None
    # Prefer Gradle's explicit exception line to a task console row.
    task = next((a for a, _ in rows if a), rows[0][1])
    role = next((s.decode("ascii") for s in (b"app", b"platform-qa") if task.startswith(b":" + s + b":")), "other")
    return dict(role=role, name=task.decode("ascii") if task in FAILURE_TASKS else None)


def inspect(phase, log, code, seconds, project, flutter_phases, keep_phases):
    with log.open("rb") as stream:
        size = stream.seek(0, 2)
        offset = max(0, size - 3 * 1024 * 1024)
        stream.seek(offset)
        tail = stream.read(3 * 1024 * 1024)
    result = dict(phase=phase, exitCode=code, elapsedSeconds=seconds,
                  firstGradleFailure=first_gradle_failure(tail) if code else None,
                  logTailCapped=bool(offset), pairVerified=False,
                  deviceValidated=False, releaseAccepted=False)
    if code:
        result["pluginEvidence"] = plugin_evidence(tail)
        result["failedTask"] = failed_task_evidence(tail)
        result["domainErrors"] = [key for key, pattern in DOMAIN_ERRORS.items() if re.search(pattern, tail)]
    if phase == "plan":
        tasks = GRAPH_TASK.findall(tail)
        unique = not offset and all(tasks.count(task) == 1 for task in REQUIRED)
        order = [tasks.index(task) for task in ORDERED_ABI] if unique else []
        valid = unique and order == sorted(order)
        result["requiredGraphTasksUnique"] = unique
        result["requiredAbiTaskOrder"] = valid
        return result, valid
    aot = collect(log, flutter_phases, project, code, seconds)
    keep = evidence(keep_phases)
    result.update(flutterTask=aot["flutterTask"], arm64AotSha256=aot["arm64AotSha256"],
                  flutterFailureTags=aot["tags"], keepRuleEvidence=keep["phaseEvidence"],
                  keepRulePhases=keep["phases"],
                  lastKnownGradleTask=summarize(log, code, seconds)["lastReportedKnownGradleTask"])
    valid = (result["flutterTask"] == "completed" and result["arm64AotSha256"] is not None
             and keep["phaseEvidence"] == "seen" and "gradle_python_ok" in keep["phases"])
    return result, valid


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("phase", choices=("plan", "assembly"))
    p.add_argument("log", type=Path)
    p.add_argument("code", type=int)
    p.add_argument("elapsed", type=int)
    p.add_argument("app", type=Path)
    p.add_argument("flutter_phases", type=Path)
    p.add_argument("keep_phases", type=Path)
    a = p.parse_args()
    if not (0 <= a.code <= 255 and 0 <= a.elapsed <= 1800):
        p.error("Invalid status bound")
    result, valid = inspect(a.phase, a.log, a.code, a.elapsed, a.app, a.flutter_phases, a.keep_phases)
    print(json.dumps(result, sort_keys=True))
    return 0 if a.code or valid else 11


if __name__ == "__main__":
    raise SystemExit(main())
