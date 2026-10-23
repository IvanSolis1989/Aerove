#!/usr/bin/env python3
"""Opt-in Linux host-test stack capture; never changes a test's outcome."""
import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time


STOP = False
LIFETIME = 110
CAPTURE_DELAY = 10


def stop_monitor(_signal, _frame):
    global STOP
    STOP = True


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target-dir", required=True, type=Path)
    parser.add_argument("--evidence-dir", required=True, type=Path)
    parser.add_argument("--filter", required=True)
    args = parser.parse_args(argv)
    if not args.target_dir.is_absolute() or not args.evidence_dir.is_absolute():
        parser.error("target-dir and evidence-dir must be absolute")
    if not args.filter or "\x00" in args.filter:
        parser.error("filter must be nonempty and contain no NUL")
    return args


def identity(pid, deps, test_filter, proc=Path("/proc")):
    """Read both process and executable identities; reject unrelated binaries."""
    try:
        directory = proc / str(pid)
        stat = (directory / "stat").read_text()
        # comm can contain spaces and parentheses; fields after its final ')' start at 3.
        fields = stat[stat.rindex(")") + 2:].split()
        starttime = fields[19]
        executable = (directory / "exe").resolve(strict=True)
        command = (directory / "cmdline").read_bytes().split(b"\0")
        command = [arg.decode(errors="replace") for arg in command if arg]
        if executable.parent != deps or not executable.name.startswith("aerove_core-"):
            return None
        if test_filter not in command:
            return None
        file_stat = (directory / "exe").stat()
        if (directory / "stat").read_text().rsplit(")", 1)[1].split()[19] != starttime:
            return None
        return {"pid": int(pid), "starttime": starttime, "exe": str(executable),
                "exeDevice": file_stat.st_dev, "exeInode": file_stat.st_ino,
                "exeSize": file_stat.st_size, "exeMtimeNs": file_stat.st_mtime_ns,
                "cmdline": command, "procStat": stat}
    except (OSError, ValueError, IndexError):
        return None


def same_process(first, current):
    return current is not None and all(first[key] == current[key] for key in
        ("pid", "starttime", "exe", "exeDevice", "exeInode", "exeSize", "exeMtimeNs", "cmdline"))


def run_tool(command, stem, evidence, deadline, limit=20):
    """Bound debugger/tool lifetime separately from the monitored test."""
    result = {"command": command, "status": "completed"}
    with (evidence / (stem + ".stdout")).open("wb") as stdout, \
            (evidence / (stem + ".stderr")).open("wb") as stderr:
        process = subprocess.Popen(command, stdout=stdout, stderr=stderr,
                                   start_new_session=sys.platform == "linux")
        expires = min(deadline, time.monotonic() + limit)
        while process.poll() is None and not STOP and time.monotonic() < expires:
            time.sleep(0.1)
        if process.poll() is None:
            result["status"] = "stopped" if STOP else "timeout"
            # The private process group contains only this monitor's tools,
            # including sudo's debugger child; never signal the test process.
            if sys.platform == "linux":
                os.killpg(process.pid, signal.SIGTERM)
            else:
                process.terminate()
            try:
                process.wait(timeout=min(1, max(0, deadline - time.monotonic())))
            except subprocess.TimeoutExpired:
                if sys.platform == "linux":
                    os.killpg(process.pid, signal.SIGKILL)
                else:
                    process.kill()
                process.wait(timeout=1)
        result["exitCode"] = process.returncode
    return result


def capture(args, report, deadline):
    deps = (args.target_dir / "debug" / "deps").resolve()
    report["deps"] = str(deps)
    first = None
    while not STOP and time.monotonic() < deadline - CAPTURE_DELAY - 2:
        candidates = [candidate for entry in Path("/proc").iterdir()
                      if entry.name.isdigit()
                      and (candidate := identity(entry.name, deps, args.filter))]
        if len(candidates) > 1:
            report.update(status="ambiguous", candidates=candidates)
            return
        if candidates:
            first = candidates[0]
            break
        time.sleep(0.25)
    if first is None:
        report["status"] = "stopped" if STOP else "not_found"
        return
    report["discovered"] = first
    wait_until = time.monotonic() + CAPTURE_DELAY
    while not STOP and time.monotonic() < wait_until:
        if not same_process(first, identity(first["pid"], deps, args.filter)):
            report["status"] = "ended"
            return
        time.sleep(0.25)
    if STOP:
        report["status"] = "stopped"
        return
    current = identity(first["pid"], deps, args.filter)
    if not same_process(first, current):
        report["status"] = "ended"
        return
    report["beforeCapture"] = current
    ps = shutil.which("ps")
    if ps:
        report["ps"] = run_tool([ps, "-p", str(first["pid"]), "-o",
            "pid,ppid,lstart,stat,etime,args"], "ps", args.evidence_dir, deadline, 2)
    gdb = shutil.which("gdb")
    report["gdbPath"] = gdb
    if not gdb:
        report.update(status="unavailable", reason="gdb is not installed")
        return
    command = [gdb, "-nx", "-nh", "--batch", "-p", str(first["pid"]),
        "-ex", "set pagination off", "-ex", "set confirm off",
        "-ex", "thread apply all bt", "-ex", "detach", "-ex", "quit"]
    report["attempts"] = []
    for attempt in range(2):
        if STOP or not same_process(first, identity(first["pid"], deps, args.filter)):
            report["status"] = "stopped" if STOP else "ended"
            return
        stem = "gdb" if attempt == 0 else "gdb-sudo"
        result = run_tool(command, stem, args.evidence_dir, deadline)
        report["attempts"].append(result)
        stderr = (args.evidence_dir / (stem + ".stderr")).read_text(errors="replace")
        stdout = (args.evidence_dir / (stem + ".stdout")).read_text(errors="replace")
        denied = "ptrace: Operation not permitted" in stderr or "ptrace: Permission denied" in stderr
        if attempt == 0 and denied and result["status"] == "completed" and not STOP:
            sudo = shutil.which("sudo")
            if sudo:
                report["sudoPath"] = sudo
                command = [sudo, "-n", "--"] + command
                continue  # Retry attachment only after explicit ptrace denial.
        report["status"] = result["status"] if result["status"] != "completed" else (
            "unavailable" if result["exitCode"] != 0 or denied or "#0 " not in stdout else "captured")
        report["afterCapture"] = identity(first["pid"], deps, args.filter)
        return


def main(argv=None):
    args = arguments(argv)
    started = time.monotonic()
    args.evidence_dir.mkdir(parents=True, exist_ok=True)
    report = {"platform": sys.platform, "filter": args.filter,
              "targetDir": str(args.target_dir), "monitorPid": os.getpid(),
              "lifetimeLimitSeconds": LIFETIME, "captureDelaySeconds": CAPTURE_DELAY}
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, stop_monitor)
    try:
        if sys.platform != "linux":
            report.update(status="unavailable", reason="Linux /proc is required")
        else:
            capture(args, report, started + LIFETIME - 2)
    except (OSError, subprocess.SubprocessError) as error:
        report.update(status="unavailable", reason=f"{type(error).__name__}: {error}")
    report["elapsedSeconds"] = round(time.monotonic() - started, 3)
    (args.evidence_dir / "capture.json").write_text(json.dumps(report, indent=2) + "\n")
    return 0  # Diagnostic availability never stands in for Cargo's exit status.


if __name__ == "__main__":
    raise SystemExit(main())
