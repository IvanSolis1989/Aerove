"""Observe an isolated cloud Gradle keep-rule probe without claiming an OOM cause."""
import argparse
import json
import os
import re
import signal
import subprocess
import time
from pathlib import Path

TASK = ":app:generateReleasePlatformTestAppKeepRules"


def visible_cgroup_memory():
    """Read fixed cgroup-v2 files only if this namespace exposes the containing group."""
    try:
        rows = Path("/proc/self/cgroup").read_text().splitlines()
        row, = [row[3:] for row in rows if row.startswith("0::")]
        relative = Path(row.lstrip("/"))
        if relative.is_absolute() or ".." in relative.parts:
            return None
        root = Path("/sys/fs/cgroup") / relative
        contents = (root / "memory.events.local").read_text().splitlines()
        result = {"events": {k: int(v) for k, v in map(str.split, contents)}}
        for name in ("memory.peak", "memory.max"):
            try:
                result[name] = (root / name).read_text().strip()
            except OSError:
                pass
        return result
    except (OSError, ValueError):
        return None


def group_rss(pid):
    """Probe this launched POSIX process group; never read user commands or environment."""
    total = 0
    processes = 0
    for proc in Path("/proc").iterdir():
        if not proc.name.isdecimal():
            continue
        try:
            # stat comm may itself contain parentheses.
            cols = (proc / "stat").read_text().rsplit(")", 1)[1].split()
            if int(cols[2]) != pid:  # state, ppid, pgrp
                continue
            rss = int(cols[21])  # field 24, after removing pid and comm
            total += max(rss, 0) * os.sysconf("SC_PAGE_SIZE")
            processes += 1
        except (OSError, ValueError, IndexError):
            continue
    return total, processes


def forward(pid, sig):
    try:
        os.killpg(pid, sig)
    except ProcessLookupError:
        pass


def observe(command, timeout, logfile):
    before = visible_cgroup_memory()
    peak = samples = max_processes = 0
    timed_out = False
    start = time.monotonic()
    with logfile.open("xb") as log:
        proc = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            while proc.poll() is None:
                rss, count = group_rss(proc.pid)
                peak, max_processes = max(peak, rss), max(max_processes, count)
                samples += 1
                if time.monotonic() - start >= timeout:
                    timed_out = True
                    forward(proc.pid, signal.SIGTERM)
                    try:
                        proc.wait(timeout=4)
                    except subprocess.TimeoutExpired:
                        forward(proc.pid, signal.SIGKILL)
                    break
                time.sleep(0.2)
            code = proc.wait()
        finally:
            # Signal the owned launcher group only. A native Gradle daemon can
            # detach into its own session; this neither samples nor kills it.
            if proc.poll() is None:
                forward(proc.pid, signal.SIGKILL)
                proc.wait()
            else:
                forward(proc.pid, signal.SIGTERM)
    after = visible_cgroup_memory()
    return {
        "task": TASK, "exitCode": code, "timedOut": timed_out, "samples": samples,
        "observedGroupRssPeakBytes": peak, "maxProcessCount": max_processes,
        "cgroupBefore": before, "cgroupAfter": after, "oomCauseKnown": False,
        "releaseAccepted": False,
    }


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--timeout-sec", type=int, default=300)
    cli.add_argument("--log", type=Path, required=True)
    cli.add_argument("--receipt", type=Path, required=True)
    cli.add_argument("command", nargs=argparse.REMAINDER)
    a = cli.parse_args()
    command = a.command[1:] if a.command[:1] == ["--"] else a.command
    if not (1 <= a.timeout_sec <= 900 and command.count(TASK) == 1
            and not any(re.search(r"(^|:)(assemble|publish|install)", item) for item in command)
            and not a.log.exists() and not a.receipt.exists()):
        cli.error("Provide a fresh bounded keep-rule probe, not assembly, install or publication")
    os.umask(0o077)
    result = observe(command, a.timeout_sec, a.log)
    with a.receipt.open("x") as output:
        output.write(json.dumps(result, sort_keys=True) + "\n")
    print(json.dumps(result, sort_keys=True))
    code = result["exitCode"]
    return 124 if result["timedOut"] else code if code >= 0 else 128 - code


if __name__ == "__main__":
    raise SystemExit(main())
