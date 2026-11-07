"""Linux real process-kill/reopen regression; requires existing Flutter SDK.
Only kills nonce-registered descendant VM and awaits true OS retirement.
This tests process death, not power loss or fsync guarantees.
"""
import argparse
from contextlib import contextmanager
import shutil
import json
import os
from pathlib import Path
import secrets
import signal
import subprocess
import tempfile
import time


def proc(pid):
    text = Path(f'/proc/{pid}/stat').read_text()
    fields = text[text.rfind(')') + 2:].split()
    return int(fields[1]), fields[19]


def descendant(pid, parent):
    for _ in range(100):
        if pid == parent:
            return True
        if pid <= 1:
            return False
        pid, _ = proc(pid)
    raise AssertionError('invalid process lineage')


def worker(flutter, app, home, mode, phase='', expected='before'):
    marker = home / 'worker-ready.json'
    marker.unlink(missing_ok=True)
    nonce = secrets.token_hex(24)
    env = dict(os.environ, AEROVE_RULE_HOME=str(home), AEROVE_RULE_NONCE=nonce,
               AEROVE_RULE_MODE=mode, AEROVE_RULE_PHASE=phase, AEROVE_RULE_EXPECT=expected)
    log = home / ('worker-' + mode + '-' + phase.replace(':', '-') + '.log')
    with log.open('wb') as output:
        child = subprocess.Popen([flutter, 'test', 'test/helpers/rule_sql_process_worker.dart',
                                  '--reporter', 'expanded'], cwd=app, env=env,
                                 stdout=output, stderr=subprocess.STDOUT)
        (home / 'launcher.json').write_text(json.dumps({'pid':child.pid,'nonce':nonce}))
        if not phase:
            if child.wait(timeout=180) != 0:
                raise AssertionError(log.read_text(errors='replace'))
            return
        deadline = time.monotonic() + 120
        while not marker.exists():
            if child.poll() is not None or time.monotonic() >= deadline:
                raise AssertionError(f'worker did not reach {phase}: {log}')
            time.sleep(0.05)
        value = json.loads(marker.read_text())
        assert value['nonce'] == nonce and value['phase'] == phase
        target = value['pid']
        assert isinstance(target, int) and target > 1
        _, birth = proc(target)
        assert descendant(target, child.pid), 'refusing to signal non-task PID'
        assert proc(target)[1] == birth, 'PID reuse before kill'
        os.kill(target, signal.SIGKILL)
        child.wait(timeout=30)
        deadline = time.monotonic() + 30
        while Path(f'/proc/{target}').exists():
            assert proc(target)[1] == birth, 'PID reused; cannot prove this numeric PID remains retired'
            if time.monotonic() >= deadline:
                raise AssertionError('OS retirement not confirmed')
            time.sleep(0.05)


@contextmanager
def case(prefix):
    home = Path(tempfile.mkdtemp(prefix=prefix))
    try:
        yield home
    except BaseException:
        # A timeout/unknown retirement must never recursively remove live input.
        print('Preserved failure evidence and launcher PID:', home, flush=True)
        raise
    else:
        shutil.rmtree(home)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--flutter', required=True)
    p.add_argument('--app', required=True, type=Path)
    args = p.parse_args()
    if not Path('/proc/self/stat').exists():
        raise SystemExit('Linux /proc required; Windows/Android process-kill routing remains unverified')
    for mode in ['admission-restore', 'prepared-identity']:
        with case(prefix='rule-identity-') as name:
            worker(args.flutter, args.app, Path(name), mode)
    with case(prefix='rule-parallel-') as name:
        worker(args.flutter, args.app, Path(name), 'parallel')
    for phase in ['prepared', 'file-renamed:0', 'file-renamed:1', 'sql-ended', 'terminal-cleanup']:
        with case(prefix='rule-kill-') as name:
            home = Path(name)
            worker(args.flutter, args.app, home, 'publish', phase)
            worker(args.flutter, args.app, home, 'recover', expected='after' if phase in ['sql-ended', 'terminal-cleanup'] else 'before')
    with case(prefix='rule-recovery-kill-') as name:
        home = Path(name)
        worker(args.flutter, args.app, home, 'publish', 'file-renamed:0')
        worker(args.flutter, args.app, home, 'recover', 'recovered:0')
        worker(args.flutter, args.app, home, 'recover')
    with case(prefix='rule-third-') as name:
        home = Path(name)
        worker(args.flutter, args.app, home, 'publish', 'file-renamed:0')
        raw = home / 'providers/rules/11111111111111111111111111111111'
        raw.write_text('third.example\n')
        worker(args.flutter, args.app, home, 'third')
        assert raw.read_text() == 'third.example\n'


if __name__ == '__main__':
    main()