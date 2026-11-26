"""Summarize private keep-rule logs without emitting raw data."""
import argparse
import json
import os
from pathlib import Path
import re
import stat

LIMIT = 2 * 1024 * 1024
DAEMON = re.compile(r"daemon-[1-9][0-9]{0,12}\.out\.log\Z")
PATTERNS = {
    "gradle_daemon_exit": r"^Gradle build daemon disappeared unexpectedly",
    "gradle_daemon_connection": r"^(?:Unable to start the daemon process|Could not connect to the Gradle daemon)",
    "java_heap_oom": r"^(?:(?:Caused by: )?java\.lang\.OutOfMemoryError: )?Java heap space$",
    "java_other_oom": r"^(?:Caused by: )?java\.lang\.OutOfMemoryError(?::(?! Java heap space$)|$)",
    "r8_diagnostic": r"^(?:(?:Caused by: )?com\.android\.tools\.r8\.(?:CompilationFailedException|ResourceException)\b|(?:ERROR: )?R8: |Error: (?:Missing class|Missing field|Missing method)|Missing class .+ \(referenced from:|TraceReferences.*(?:failed|error))",
    "kotlin_compilation": r"^(?:Kotlin compilation failed|Compilation error\. See log for more details)",
    "rust_ndk_compilation": r"^(?:error(?:\[[A-Z0-9]+\])?: could not compile\b|fatal error: ['\"]stdbool\.h['\"] file not found|error: linker .+ not found$)",
    "dependency_resolution": r"^(?:Could not (?:resolve|download) (?:all |any |artifact |files |dependencies |[a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+:)|Could not GET |Could not HEAD )",
}
PATTERNS = {key: re.compile(regex, re.M) for key, regex in PATTERNS.items()}
DECORATION = re.compile(r"(?m)^[ \t]*(?:\x1b\[[0-9;]*m|> [ \t]*)*")
DIR_FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW


def owned(fd):
    if os.fstat(fd).st_uid != os.getuid():
        os.close(fd)
        raise ValueError("unbound directory")
    return fd


def read_tail(directory_fd, name):
    fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK,
                 dir_fd=directory_fd)
    try:
        f = os.fstat(fd)
        if not stat.S_ISREG(f.st_mode) or f.st_uid != os.getuid() or f.st_nlink != 1:
            raise ValueError("unbound log")
        offset = max(0, f.st_size - LIMIT)
        os.lseek(fd, offset, os.SEEK_SET)
        with os.fdopen(fd, "rb", closefd=False) as stream:
            data = stream.read(LIMIT)
        if offset:
            data = data.partition(b"\n")[2]
        return ("seen" if data else "empty", data.decode("utf-8", "replace"), bool(offset))
    finally:
        os.close(fd)


def read_source(path, *, daemon=False):
    """The daemon path is a fresh single-command Gradle home, not the shared cache."""
    fds = []
    try:
        fds.append(owned(os.open(path if daemon else path.parent, DIR_FLAGS)))
        if daemon:
            for d in ("daemon", "9.6.0"):
                fds.append(owned(os.open(d, DIR_FLAGS, dir_fd=fds[-1])))
            entries = [n for n in os.listdir(fds[-1]) if DAEMON.fullmatch(n)]
            if len(entries) != 1:
                return ("ambiguous" if entries else "missing", "", False)
            name = entries[0]
        else:
            name = path.name
        return read_tail(fds[-1], name)
    except FileNotFoundError:
        return "missing", "", False
    except (OSError, ValueError):
        return "rejected", "", False
    finally:
        for fd in reversed(fds):
            os.close(fd)


def report(client, home, code, seconds):
    result = {}
    for source, inputs in (("client", read_source(client)),
                           ("ownedGradleDaemon", read_source(home, daemon=True))):
        status, data, capped = inputs
        normalized = DECORATION.sub("", data)
        result[source] = dict(log=status,
                              tags=[k for k, p in PATTERNS.items() if p.search(normalized)],
                              tailCapped=capped)
    return dict(task=":app:generateReleasePlatformTestAppKeepRules",
                exitCode=code, elapsedSeconds=seconds, diagnostics=result,
                rssCoverage="launcher_process_group_only", releaseAccepted=False)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("client", type=Path)
    parser.add_argument("home", type=Path)
    parser.add_argument("code", type=int)
    parser.add_argument("seconds", type=int)
    args = parser.parse_args()
    if not 0 <= args.code <= 255 or not 0 <= args.seconds <= 11000:
        parser.error("invalid bounded status")
    output = json.dumps(report(args.client, args.home, args.code, args.seconds), sort_keys=True)
    fence = chr(96) * 3
    print("### AEROVE keep-rule evidence (not acceptance)\n\n"+fence+"json\n"+output+"\n"+fence)
    print(output, file=__import__("sys").stderr)


if __name__ == "__main__":
    main()
