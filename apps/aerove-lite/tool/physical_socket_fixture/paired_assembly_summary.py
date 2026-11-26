"""Emit allowlisted facts, never the raw log of an ephemeral paired build."""
import argparse
import json
from pathlib import Path
import re

TASK = re.compile(rb"^> Task (:[:A-Za-z0-9-]+)(?: (?:FAILED|UP-TO-DATE|FROM-CACHE|NO-SOURCE|SKIPPED))?\r?\n?$")
KNOWN = {
    ":app:compileFlutterBuildRelease", ":app:compileReleaseKotlin", ":platform-qa:compileReleaseKotlin",
    ":app:generateReleasePlatformTestAppKeepRules", ":app:minifyReleaseWithR8",
    ":platform-qa:minifyReleaseWithR8", ":app:packageRelease", ":platform-qa:packageRelease",
    ":app:assembleRelease", ":platform-qa:assembleRelease",
}
DAEMON = {
    b"Gradle build daemon disappeared unexpectedly (it may have been killed or may have crashed)",
    b"> Gradle build daemon disappeared unexpectedly (it may have been killed or may have crashed)",
}


def summarize(log, code, elapsed):
    last = None
    daemon = False
    if log.exists() and not log.is_symlink():
        with log.open("rb") as stream:
            while line := stream.readline(4096):
                # Drop the entirety of a long private line, not just its first piece.
                if len(line) == 4096 and not line.endswith(b"\n"):
                    while tail := stream.readline(4096):
                        if tail.endswith(b"\n"):
                            break
                    continue
                task = TASK.fullmatch(line)
                if task and (label := task[1].decode("ascii")) in KNOWN:
                    last = label
                elif line.strip() in DAEMON:
                    daemon = True
    status = "success" if code == 0 else "timeout" if code == 124 else "failed"
    return dict(phase="paired-assembly", status=status, elapsedSeconds=elapsed, exitCode=code,
                lastReportedKnownGradleTask=last, daemonDisappearanceReported=daemon,
                deviceValidated=False, releaseAccepted=False)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("log", type=Path)
    p.add_argument("code", type=int)
    p.add_argument("elapsed", type=int)
    a = p.parse_args()
    if not (0 <= a.code <= 255 and 0 <= a.elapsed <= 3600):
        p.error("Invalid elapsed time or exit status")
    print("### AEROVE paired assembly outcome (not acceptance)\n\n```json")
    print(json.dumps(summarize(a.log, a.code, a.elapsed), sort_keys=True))
    print("```")


if __name__ == "__main__":
    main()
