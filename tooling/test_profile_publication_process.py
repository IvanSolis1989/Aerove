"""Kill/reopen real Flutter profile publication. Not a power-loss/fsync test."""
import argparse
import json
import os
from pathlib import Path
import secrets
import signal
import subprocess
import time

from test_rule_sql_process import descendant, proc, os_absence_proof


def worker(args, home, mode, phase='', expected='before', original='keep'):
    marker = home / 'ready.json'
    marker.unlink(missing_ok=True)
    nonce = secrets.token_hex(24)
    env = dict(os.environ, AEROVE_PROFILE_HOME=str(home),
               AEROVE_PROFILE_MODE=mode, AEROVE_PROFILE_PHASE=phase,
               AEROVE_PROFILE_EXPECT=expected, AEROVE_PROFILE_INPUT=original,
               AEROVE_PROFILE_NONCE=nonce)
    log = home / f'{mode}-{phase.replace(":", "-") or "complete"}.log'
    command = [args.flutter, 'test', '--no-pub', '--concurrency=1',
               'test/helpers/profile_publication_process_worker.dart', '--reporter=expanded']
    child = None
    target = None
    birth = None
    record = {'mode': mode, 'phase': phase, 'expected': expected, 'log': str(log)}
    try:
        with log.open('w') as output:
            child = subprocess.Popen(command, cwd=args.app, env=env,
                                     stdout=output, stderr=subprocess.STDOUT)
            record['launcher_pid'] = child.pid
            if not phase:
                code = child.wait(timeout=240)
                if code != 0:
                    raise AssertionError(log.read_text())
                record['exit'] = code
                return record
            deadline = time.monotonic() + 240
            while time.monotonic() < deadline:
                if marker.exists():
                    ready = json.loads(marker.read_text())
                    if ready.get('nonce') != nonce or ready.get('phase') != phase:
                        raise AssertionError('unqualified worker marker')
                    target = ready['pid']
                    if not isinstance(target, int) or not descendant(target, child.pid):
                        raise AssertionError('worker is not this launch descendant')
                    _, birth = proc(target)
                    if proc(target)[1] != birth or not descendant(target, child.pid):
                        raise AssertionError('worker identity changed')
                    record['target_pid'] = target
                    record['target_birth'] = birth
                    os.kill(target, signal.SIGKILL)
                    child.wait(timeout=60)
                    for _ in range(100):
                        if not Path(f'/proc/{target}').exists():
                            break
                        time.sleep(.05)
                    record['retirement'] = os_absence_proof(target)
                    target = None
                    return record
                if child.poll() is not None:
                    raise AssertionError(log.read_text())
                time.sleep(.05)
            raise TimeoutError('worker did not reach requested publication phase')
    finally:
        if target is not None and birth is not None:
            try:
                if proc(target)[1] == birth and descendant(target, child.pid):
                    os.kill(target, signal.SIGKILL)
            except FileNotFoundError:
                pass
        if child is not None and child.poll() is None:
            child.terminate()
            child.wait(timeout=30)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--flutter', default='flutter')
    parser.add_argument('--app', type=Path, default=Path('apps/aerove'))
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    args.app = args.app.resolve()
    args.evidence = args.evidence.absolute()
    args.evidence.mkdir(parents=True, exist_ok=False)
    results = []
    cases = [
        ('prepared', 'prepared', 'before', 'keep'),
        ('first-rename', 'file-renamed:0', 'before', 'keep'),
        ('both-renames', 'file-renamed:1', 'before', 'keep'),
        ('committed', 'sql-ended', 'after', 'keep'),
        ('cleanup', 'terminal-cleanup', 'after', 'keep'),
        ('input-deleted', 'file-renamed:1', 'before', 'delete'),
        ('recovery-interrupted', 'file-renamed:1', 'before', 'keep'),
    ]
    try:
        for name, phase, expected, original in cases:
            home = args.evidence / name
            home.mkdir()
            case = {'case': name, 'workers': [], 'passed': False}
            results.append(case)
            case['workers'].append(worker(args, home, 'publish', phase, original=original))
            if name == 'recovery-interrupted':
                case['workers'].append(worker(args, home, 'recover', 'recovered:0'))
            case['workers'].append(worker(args, home, 'recover', expected=expected, original=original))
            case['passed'] = True
            print(f'PASS {name}', flush=True)
    finally:
        (args.evidence / 'summary.json').write_text(json.dumps({
            'process_death_only': True, 'cases': results,
            'passed': len(results) == len(cases) and all(c['passed'] for c in results),
        }, indent=2) + '\n')


if __name__ == '__main__':
    main()
