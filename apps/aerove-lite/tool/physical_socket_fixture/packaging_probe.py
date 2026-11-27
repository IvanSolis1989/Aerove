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
