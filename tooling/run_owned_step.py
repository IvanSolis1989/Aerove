"""Run one Linux CI Bash step with cancellation-safe private-session ownership."""
import argparse
import ctypes
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

from owned_process import signal_qualified_process

CLEANUP_SECONDS = 2.0


def process_state(pid):
    raw = Path(f'/proc/{pid}/stat').read_text()
    fields = raw[raw.rfind(')') + 2:].split()
    return {'pid': pid, 'state': fields[0], 'parent': int(fields[1]),
            'session': int(fields[3]), 'birth': fields[19]}


def session_members(session):
    members = []
    for entry in Path('/proc').iterdir():
        if entry.name.isdigit():
            try:
                item = process_state(int(entry.name))
                if item['session'] == session:
                    members.append(item)
            except (FileNotFoundError, ProcessLookupError):
                pass
    return members


def observe_exit(child):
    # WNOWAIT reserves the session leader's PID until every member is retired.
    info = os.waitid(os.P_PID, child.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
    if info is None:
        return None
    return info.si_status if info.si_code == os.CLD_EXITED else -info.si_status


def reap_adopted(leader):
    for item in session_members(leader):
        if item['pid'] != leader and item['parent'] == os.getpid():
            os.waitpid(item['pid'], os.WNOHANG)


def cleanup_session(child, birth):
    result = {'confirmed': False, 'signaled': [], 'errors': []}
    attempted = set()
    deadline = time.monotonic() + CLEANUP_SECONDS
    try:
        while True:
            anchor = process_state(child.pid)
            if anchor['birth'] != birth or anchor['session'] != child.pid:
                raise AssertionError('private session leader identity changed')
            members = session_members(child.pid)
            for item in sorted(members, key=lambda member: member['pid'], reverse=True):
                identity = (item['pid'], item['birth'])
                if item['state'] == 'Z' or identity in attempted:
                    continue
                attempted.add(identity)
                try:
                    def qualified():
                        current = process_state(item['pid'])
                        leader = process_state(child.pid)
                        return (current['birth'] == item['birth'] and current['session'] == child.pid
                                and leader['birth'] == birth and leader['session'] == child.pid)
                    signal_qualified_process(item['pid'], signal.SIGKILL, qualified)
                    result['signaled'].append({'pid': item['pid'], 'birth': item['birth']})
                except (FileNotFoundError, ProcessLookupError):
                    pass
                except Exception as error:
                    result['errors'].append(f'{type(error).__name__}: {error}')
            reap_adopted(child.pid)
            remaining = [item for item in session_members(child.pid) if item['pid'] != child.pid]
            if not remaining and observe_exit(child) is not None:
                child.wait(timeout=.1)
                result['confirmed'] = not result['errors']
                result['remaining'] = []
                return result
            if time.monotonic() >= deadline:
                result['remaining'] = remaining
                result['errors'].append('private session retirement deadline exceeded')
                return result
            time.sleep(.02)
    except Exception as error:
        result['errors'].append(f'{type(error).__name__}: {error}')
        return result


def save_receipt(path, receipt):
    pending = path.with_name(path.name + '.pending')
    pending.write_text(json.dumps(receipt, indent=2) + '\n')
    pending.replace(path)


def run(script, evidence):
    evidence.parent.mkdir(parents=True, exist_ok=True)
    receipt = {'schema': 1, 'status': 'STARTING', 'cancelSignal': None,
               'shellExit': None, 'normalExitLeaks': False, 'cleanup': None}
    child = None
    birth = None
    code = 1
    def interrupted(signum, _frame):
        # A second runner signal cannot interrupt the short cleanup window.
        if receipt['cancelSignal'] is None:
            receipt['cancelSignal'] = signum
    previous = {sig: signal.signal(sig, interrupted) for sig in (signal.SIGINT, signal.SIGTERM)}
    try:
        save_receipt(evidence, receipt)
        signal_qualified_process(os.getpid(), 0, lambda: True)
        libc = ctypes.CDLL(None, use_errno=True)
        if libc.prctl(36, 1, 0, 0, 0) != 0:
            raise OSError(ctypes.get_errno(), 'cannot become child subreaper')
        if receipt['cancelSignal'] is None:
            child = subprocess.Popen(['bash', '--noprofile', '--norc', '-eo', 'pipefail', str(script)],
                                     start_new_session=True)
            birth = process_state(child.pid)['birth']
            receipt.update(status='RUNNING', session=child.pid, launcherBirth=birth)
            save_receipt(evidence, receipt)
            while receipt['cancelSignal'] is None:
                code = observe_exit(child)
                if code is not None:
                    receipt['shellExit'] = code
                    receipt['normalExitLeaks'] = any(item['pid'] != child.pid and item['state'] != 'Z'
                                                    for item in session_members(child.pid))
                    break
                time.sleep(.05)
    except BaseException as error:
        receipt['error'] = f'{type(error).__name__}: {error}'
        code = 1
    finally:
        if child is not None:
            receipt['cleanup'] = cleanup_session(child, birth)
            if not receipt['cleanup']['confirmed']:
                code = 1
        if receipt['cancelSignal'] is not None:
            code = 128 + receipt['cancelSignal']
        elif receipt.get('error'):
            code = 1
        elif receipt['normalExitLeaks'] and code == 0:
            code = 1
        elif code is None:
            code = 1
        elif code < 0:
            code = 128 - code
        receipt.update(status='PASSED' if code == 0 else 'FAILED', exitCode=code)
        try:
            save_receipt(evidence, receipt)
        except Exception as error:
            print(f'Cannot retain step owner receipt: {error}', file=sys.stderr)
            code = code or 1
        for sig, handler in previous.items():
            signal.signal(sig, handler)
    return code


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', required=True, type=Path)
    parser.add_argument('script', type=Path)
    args = parser.parse_args()
    return run(args.script, args.evidence)


if __name__ == '__main__':
    raise SystemExit(main())
