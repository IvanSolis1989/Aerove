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
