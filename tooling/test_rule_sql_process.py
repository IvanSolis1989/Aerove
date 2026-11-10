"""Linux process-kill/reopen regression with retained synthetic evidence.
Only signals the original nonce-qualified descendant VM. Process death is not
power loss or an fsync guarantee. Every case keeps evidence, including success.
"""
import argparse
from contextlib import closing, contextmanager
from datetime import datetime, timezone
import hashlib
import errno
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import sqlite3
import subprocess
import sys
import tempfile
import time
import traceback

from owned_process import signal_qualified_process

RAW = 'providers/rules/11111111111111111111111111111111'
CASES = (
    ('admission-restore', (('admission-restore', '', 'before'),), 'restored', 'restored.example\n', 3, 'empty'),
    ('prepared-identity', (('prepared-identity', '', 'before'),), 'before', 'before.example\n', 1, 'third-identity'),
    ('parallel', (('parallel', '', 'before'),), 'after', 'after.example\n', 2, 'empty'),
    ('prepared', (('publish', 'prepared', 'before'), ('recover', '', 'before')), 'before', 'before.example\n', 1, 'empty'),
    ('first-rename', (('publish', 'file-renamed:0', 'before'), ('recover', '', 'before')), 'before', 'before.example\n', 1, 'empty'),
    ('second-rename', (('publish', 'file-renamed:1', 'before'), ('recover', '', 'before')), 'before', 'before.example\n', 1, 'empty'),
    ('committed', (('publish', 'sql-ended', 'before'), ('recover', '', 'after')), 'after', 'after.example\n', 2, 'empty'),
    ('terminal-cleanup', (('publish', 'terminal-cleanup', 'before'), ('recover', '', 'after')), 'after', 'after.example\n', 2, 'empty'),
    ('recovery-kill', (('publish', 'file-renamed:0', 'before'), ('recover', 'recovered:0', 'before'), ('recover', '', 'before')), 'before', 'before.example\n', 1, 'empty'),
    ('third-file', (('publish', 'file-renamed:0', 'before'), ('third', '', 'before')), 'before', 'third.example\n', 1, 'prepared'),
)


def stamp():
    return {'utc': datetime.now(timezone.utc).isoformat(), 'monotonic_ns': time.monotonic_ns()}


def error_record(error):
    return {'type': type(error).__name__, 'message': str(error), 'at': stamp(),
            'traceback': ''.join(traceback.format_exception(type(error), error, error.__traceback__)),
            'copy_proof': getattr(error, 'copy_proof', None)}


def digest(file, limit=None):
    result = hashlib.sha256()
    remaining = limit
    with file.open('rb') as stream:
        while remaining is None or remaining > 0:
            block = stream.read(1024 * 1024 if remaining is None else min(1024 * 1024, remaining))
            if not block:
                break
            result.update(block)
            if remaining is not None:
                remaining -= len(block)
    return result.hexdigest()


def safe_relative(root, file):
    root = root.resolve(strict=True)
    if file.is_symlink():
        raise AssertionError('symbolic evidence input/destination')
    relative = file.absolute().relative_to(root)
    if not relative.parts or any(part in ('', '.', '..') for part in relative.parts):
        raise AssertionError('invalid evidence relative path')
    cursor = root
    for part in relative.parts:
        cursor = cursor / part
        if cursor.is_symlink():
            raise AssertionError('symbolic evidence ancestor')
    if file.exists() and file.resolve(strict=True) != file.absolute():
        raise AssertionError('indirect evidence path')
    return relative


def atomic_json(file, value):
    pending = file.with_name(file.name + '.pending')
    with pending.open('w', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
        stream.flush()
    pending.replace(file)


def copy_proof(source_root, source, target_root, relative, stable_required=True):
    source_relative = safe_relative(source_root, source)
    target = target_root / relative
    safe_relative(target_root, target)
    target.parent.mkdir(parents=True, exist_ok=True)
    before_stat = source.stat()
    before_hash = digest(source, before_stat.st_size)
    with source.open('rb') as incoming, target.open('xb') as outgoing:
        remaining = before_stat.st_size
        while remaining > 0:
            block = incoming.read(min(1024 * 1024, remaining))
            if not block:
                break
            outgoing.write(block)
            remaining -= len(block)
        outgoing.flush()
    copied_hash = digest(target)
    after_stat = source.stat()
    after_hash = digest(source, after_stat.st_size)
    final_stat = source.stat()
    fields = ('st_dev', 'st_ino', 'st_size', 'st_mtime_ns')
    stable = before_hash == copied_hash == after_hash and all(
        getattr(before_stat, key) == getattr(after_stat, key) == getattr(final_stat, key) for key in fields)
    proof = {'source_relative': source_relative.as_posix(), 'artifact_relative': relative.as_posix(),
             'source_bytes_before': before_stat.st_size, 'source_bytes_after': after_stat.st_size,
             'artifact_bytes': target.stat().st_size, 'source_sha256_before': before_hash,
             'source_sha256_after': after_hash, 'artifact_sha256': copied_hash, 'stable': stable,
             'copy_bound_bytes': before_stat.st_size, 'hash_scope': 'recorded source size; full-file equality only when stable'}
    if stable_required and not stable:
        error = AssertionError('evidence source changed during copy: ' + str(source))
        error.copy_proof = proof
        raise error
    return proof


class Evidence:
    def __init__(self, path, args):
        requested = path.absolute()
        if requested.exists() or requested.is_symlink():
            raise ValueError('--evidence must name a new task directory')
        requested.parent.mkdir(parents=True, exist_ok=True)
        if requested.parent.resolve() != requested.parent:
            raise ValueError('indirect --evidence parent is not accepted')
        requested.mkdir()
        self.path = requested.resolve(strict=True)
        self.temp_root = None
        self.data = {'schema': 1, 'status': 'NOT_STARTED', 'started': stamp(),
                     'evidence_root': str(self.path), 'invocation': vars(args).copy(),
                     'process_death_is_not_power_loss': True, 'platform': sys.platform, 'controller_pid': os.getpid(),
                     'cases': [{'id': f'{i:02d}-{row[0]}', 'status': 'NOT_RUN', 'workers': [],
                                'planned_actions': [{'mode': a[0], 'phase': a[1], 'expected': a[2]} for a in row[1]],
                                'expected_final': {'provider_label': row[2], 'raw_sha256': hashlib.sha256(row[3].encode()).hexdigest(),
                                                   'mrs_sha256': hashlib.sha256(bytes([77, 82, 83, 0, row[4]])).hexdigest(), 'token_rule': row[5]}}
                               for i, row in enumerate(CASES, 1)]}
        self.data['invocation'] = {key: str(value) for key, value in self.data['invocation'].items()}
        self.data['sources'] = {}
        for name, file in [('harness', Path(__file__).absolute()),
                           ('worker', args.app / 'test/helpers/rule_sql_process_worker.dart'),
                           ('fixture', args.app / 'test/helpers/rule_sql_fixture.dart')]:
            if file.is_file():
                self.data['sources'][name] = {'path': str(file.absolute()), 'sha256': digest(file)}
        self.data['ci'] = {key: os.environ[key] for key in ('GITHUB_SHA', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT') if key in os.environ}
        self.save()

    def save(self):
        atomic_json(self.path / 'summary.json', self.data)

    def directory(self, record):
        result = self.path / record['id']
        safe_relative(self.path, result)
        result.mkdir(exist_ok=True)
        return result

    def event(self, record, name, **fields):
        record.setdefault('events', []).append({'event': name, **stamp(), **fields})
        self.save()

    def worker_artifacts(self, case, run, home, log, stable_required):
        target = self.directory(case) / f"worker-{run['index']:02d}"
        target.mkdir()
        run['artifact_directory'] = target.relative_to(self.path).as_posix()
        errors = []
        for name, source in [('log', log), ('ready', home / 'worker-ready.json'), ('launcher', home / 'launcher.json')]:
            try:
                if source.exists():
                    proof = copy_proof(home, source, target, Path(source.name), stable_required)
                    run.setdefault('artifacts', {})[name] = proof
                    if name == 'log':
                        run['log_empty'] = proof['artifact_bytes'] == 0
                    if name == 'launcher':
                        saved = json.loads((target / source.name).read_text())
                        run['launcher_record_matches_current'] = saved.get('nonce') == run['nonce'] and saved.get('pid') == run['launcher_pid']
                else:
                    run.setdefault('artifacts', {})[name] = {'present': False}
            except BaseException as error:
                errors.append(error_record(error))
        run['archive_errors'] = errors
        atomic_json(target / 'worker.json', run)
        self.save()
        if errors and stable_required:
            raise AssertionError('worker evidence archive did not complete: ' + json.dumps(errors))


def proc(pid):
    text = Path(f'/proc/{pid}/stat').read_text()
    fields = text[text.rfind(')') + 2:].split()
    return int(fields[1]), fields[19]


def descendant_depth(pid, parent):
    for depth in range(100):
        if pid == parent:
            return depth
        if pid <= 1:
            return None
        pid, _ = proc(pid)
    raise AssertionError('invalid process lineage')


def descendant(pid, parent):
    return descendant_depth(pid, parent) is not None


def os_absence_proof(target):
    # The original wait loop is retained. Evidence additionally distinguishes
    # real ENOENT from access/namespace errors hidden by Path.exists().
    try:
        os.lstat(f'/proc/{target}')
    except FileNotFoundError as error:
        if error.errno != errno.ENOENT:
            raise
        return {'method': 'lstat /proc/PID returned ENOENT', 'errno': error.errno, 'observed': stamp()}
    raise AssertionError('OS PID entry still exists at evidence qualification')


def kill_owned_process(target, birth, launcher_pid, launcher_birth):
    """Bind SIGKILL to a Linux process handle, not a reusable numeric PID."""
    signal_qualified_process(target, signal.SIGKILL, lambda: (
        proc(launcher_pid)[1] == launcher_birth and proc(target)[1] == birth
        and descendant(target, launcher_pid)))


def retire_owned_launcher(child, launcher_birth):
    """Retire only recorded descendants of the original launcher on failure.

    Descendants are killed before their launcher so it can reap its children.
    Never signal a reused PID or a process whose ownership is no longer provable.
    """
    result = {'targets': [], 'errors': [], 'descendant_snapshot_complete': False}
    owned = []
    try:
        if proc(child.pid)[1] != launcher_birth:
            raise AssertionError('launcher PID identity changed before cleanup')
        for entry in Path('/proc').iterdir():
            if not entry.name.isdigit() or int(entry.name) == child.pid:
                continue
            target = int(entry.name)
            try:
                _, birth = proc(target)
                depth = descendant_depth(target, child.pid)
                if depth is not None and proc(target)[1] == birth:
                    owned.append((depth, target, birth))
            except FileNotFoundError:
                continue
        result['descendant_snapshot_complete'] = True
        # Keep intermediate parents alive until their children are signaled.
        for _, target, birth in sorted(owned, reverse=True):
            record = {'pid': target, 'birth': birth}
            result['targets'].append(record)
            try:
                kill_owned_process(target, birth, child.pid, launcher_birth)
                record['sigkill_sent'] = True
            except FileNotFoundError:
                record['already_absent'] = True
            except Exception as error:
                result['errors'].append(error_record(error))
        try:
            child.wait(timeout=10)
        except subprocess.TimeoutExpired:
            if proc(child.pid)[1] != launcher_birth:
                raise AssertionError('launcher PID reused before termination')
            child.terminate()
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                if proc(child.pid)[1] != launcher_birth:
                    raise AssertionError('launcher PID reused before kill')
                child.kill()
                child.wait(timeout=5)
        result['launcher_exit'] = child.returncode
    except FileNotFoundError:
        # A naturally exited launcher may already have been reaped by poll().
        result['launcher_exit'] = child.poll()
        if not result['descendant_snapshot_complete'] or result['launcher_exit'] is None:
            result['errors'].append(error_record(AssertionError(
                'launcher absent before complete descendant qualification; cleanup unconfirmed')))
    except Exception as error:
        result['errors'].append(error_record(error))
    for record in result['targets']:
        try:
            record['retirement'] = os_absence_proof(record['pid'])
        except Exception as error:
            result['errors'].append(error_record(error))
    result['recorded_processes_retired'] = (result['descendant_snapshot_complete']
        and result.get('launcher_exit') is not None and not result['errors'])
    return result


def worker(flutter, app, home, mode, phase='', expected='before', *, evidence, case_record):
    marker = home / 'worker-ready.json'
    marker.unlink(missing_ok=True)
    nonce = secrets.token_hex(24)
    env = dict(os.environ, AEROVE_RULE_HOME=str(home), AEROVE_RULE_NONCE=nonce,
               AEROVE_RULE_MODE=mode, AEROVE_RULE_PHASE=phase, AEROVE_RULE_EXPECT=expected)
    log = home / ('worker-' + mode + '-' + phase.replace(':', '-') + '.log')
    command = [flutter, 'test', 'test/helpers/rule_sql_process_worker.dart', '--reporter', 'expanded']
    run = {'index': len(case_record['workers']) + 1, 'status': 'RUNNING', 'command': command,
           'cwd': str(app), 'mode': mode, 'phase': phase, 'expected': expected, 'nonce': nonce,
           'started': stamp(), 'launcher_pid': None, 'launcher_exit': None,
           'target_pid': None, 'target_birth': None, 'descendant_qualified': None,
           'target_exit_code': None, 'target_exit_note': 'not a direct waitable child; OS absence is recorded separately',
           'ready_note': 'normal path has no expected ready phase; any copied marker is unqualified' if not phase else 'not observed yet'}
    case_record['workers'].append(run)
    evidence.save()
    child = None
    primary = None
    launcher_birth = None
    try:
        with log.open('wb') as output:
            child = subprocess.Popen(command, cwd=app, env=env, stdout=output, stderr=subprocess.STDOUT)
            run['launcher_pid'] = child.pid
            launcher_birth = proc(child.pid)[1]
            run['launcher_birth'] = launcher_birth
            (home / 'launcher.json').write_text(json.dumps({'pid': child.pid, 'nonce': nonce}))
            evidence.event(run, 'launcher-started', pid=child.pid)
            if not phase:
                run['launcher_exit'] = child.wait(timeout=180)
                evidence.event(run, 'launcher-exited', returncode=run['launcher_exit'])
                if run['launcher_exit'] != 0:
                    raise AssertionError(log.read_text(errors='replace'))
                run['status'] = 'WORKER_EXITED_ZERO'
                return
            deadline = time.monotonic() + 120
            while not marker.exists():
                if child.poll() is not None or time.monotonic() >= deadline:
                    raise AssertionError(f'worker did not reach {phase}: {log}')
                time.sleep(0.05)
            value = json.loads(marker.read_text())
            run['ready'] = value
            evidence.event(run, 'ready-marker-observed')
            assert value['nonce'] == nonce and value['phase'] == phase
            target = value['pid']
            assert isinstance(target, int) and target > 1
            _, birth = proc(target)
            run['target_pid'], run['target_birth'] = target, birth
            try:
                assert descendant(target, child.pid), 'refusing to signal non-task PID'
            except AssertionError:
                run['descendant_qualified'] = False
                raise
            run['descendant_qualified'] = True
            evidence.event(run, 'descendant-qualification', target=target, birth=birth, qualified=True)
            assert proc(target)[1] == birth, 'PID reuse before kill'
            run['signal_attempt'] = stamp()
            kill_owned_process(target, birth, child.pid, launcher_birth)
            evidence.event(run, 'sigkill-sent', pid=target, signal=int(signal.SIGKILL))
            run['launcher_exit'] = child.wait(timeout=30)
            evidence.event(run, 'launcher-exited', returncode=run['launcher_exit'])
            run['os_absence_wait_started'] = stamp()
            deadline = time.monotonic() + 30
            while Path(f'/proc/{target}').exists():
                assert proc(target)[1] == birth, 'PID reused; cannot prove this numeric PID remains retired'
                if time.monotonic() >= deadline:
                    raise AssertionError('OS retirement not confirmed')
                time.sleep(0.05)
            run['os_absence_proof'] = os_absence_proof(target)
            run['os_absence_confirmed'] = run['os_absence_proof']['observed']
            run['ready_note'] = 'nonce/phase/PID/birth and descendant qualification observed'
            run['status'] = 'SIGKILL_SENT_AND_OS_ABSENCE_CONFIRMED'
            evidence.event(run, 'os-pid-absent', pid=target, birth=birth)
    except BaseException as error:
        primary = error
        run['status'] = 'FAILED_OR_UNCONFIRMED'
        run['error'] = error_record(error)
        raise
    finally:
        if primary is not None and child is not None and launcher_birth is not None:
            run['failure_cleanup'] = retire_owned_launcher(child, launcher_birth)
        run['ended'] = stamp()
        if child is not None:
            run['launcher_exit'] = child.poll()
        try:
            evidence.worker_artifacts(case_record, run, home, log, stable_required=primary is None)
        except BaseException as archive_error:
            run['evidence_error'] = error_record(archive_error)
            if primary is None:
                raise
            print('Additional evidence error; preserving primary and live input:', archive_error, home, flush=True)


def verify_workers(spec, record):
    observed = [(run['mode'], run['phase'], run['expected']) for run in record['workers']]
    if observed != list(spec[1]):
        raise AssertionError('case worker actions were not all observed')
    for run in record['workers']:
        terminal = 'SIGKILL_SENT_AND_OS_ABSENCE_CONFIRMED' if run['phase'] else 'WORKER_EXITED_ZERO'
        if run['status'] != terminal:
            raise AssertionError('worker terminal was not confirmed')
        if run['phase'] and ('os_absence_confirmed' not in run or run['descendant_qualified'] is not True):
            raise AssertionError('worker OS qualification evidence is incomplete')
        if not run['phase'] and run['launcher_exit'] != 0:
            raise AssertionError('worker launcher did not succeed')
        if run.get('archive_errors') or not run.get('artifacts', {}).get('log', {}).get('stable'):
            raise AssertionError('worker source log was not stably archived')


def verify_fixture(home, spec, record):
    """Independent literal fixture expectations after all original worker actions."""
    record['readback_started'] = stamp()
    checks = record['readback_checks'] = []

    def equal(name, actual, expected):
        matches = actual == expected
        checks.append({'name': name, 'actual': actual, 'expected': expected, 'matched': matches})
        if not matches:
            raise AssertionError('independent fixture readback failed: ' + name)

    file = home / 'database.sqlite'
    safe_relative(home, file)
    with closing(sqlite3.connect(file.as_uri() + '?mode=ro', uri=True)) as db:
        db.execute('PRAGMA query_only=ON')
        db.execute('BEGIN')
        equal('user_version', db.execute('PRAGMA user_version').fetchone()[0], 23)
        identity = db.execute('SELECT slot,incarnation FROM rule_sql_identity').fetchall()
        record['identity'] = identity
        equal('identity_count', len(identity), 1)
        equal('identity_slot', identity[0][0], 1)
        equal('identity_uuid', bool(re.fullmatch(r'[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}', identity[0][1])), True)
        equal('provider_rows', db.execute('SELECT id,label FROM clash_providers ORDER BY id').fetchall(), [(71, spec[2])])
        db.row_factory = sqlite3.Row
        tokens = [dict(row) for row in db.execute('SELECT * FROM rule_source_publications ORDER BY transaction_id')]
        record['tokens'] = tokens
        equal('token_count', len(tokens), 0 if spec[5] == 'empty' else 1)
        if tokens:
            equal('token_decision', tokens[0]['decision'], 'PREPARED')
            equal('token_provider', tokens[0]['provider_id'], 71)
            equal('token_schema', tokens[0]['schema_version'], 1)
            equal('token_plan_sha256', bool(re.fullmatch(r'[0-9a-f]{64}', tokens[0]['plan_sha256'])), True)
            equal('token_identity_matches', tokens[0]['incarnation'] == identity[0][1], spec[5] != 'third-identity')
    for name, relative, expected in [('raw', RAW, spec[3].encode()), ('mrs', RAW + '.mrs', bytes([77, 82, 83, 0, spec[4]]))]:
        file = home / relative
        safe_relative(home, file)
        actual = file.read_bytes()
        equal(name, {'bytes': len(actual), 'sha256': hashlib.sha256(actual).hexdigest()},
              {'bytes': len(expected), 'sha256': hashlib.sha256(expected).hexdigest()})


def archive_fixture(evidence, record, home):
    target = evidence.directory(record) / 'fixture-final'
    target.mkdir()
    record['fixture_artifact_directory'] = target.relative_to(evidence.path).as_posix()
    files = sorted(home.rglob('*'))
    record['fixture_artifacts'] = []
    for source in files:
        if source.is_symlink():
            raise AssertionError('symbolic synthetic fixture entry')
        if not source.is_file():
            continue
        relative = safe_relative(home, source)
        if (relative.as_posix().startswith('database.sqlite') or relative.as_posix() in (RAW, RAW + '.mrs', 'candidate.raw', 'candidate.mrs')
                or relative.parts[0] == 'rule-sql-publications'):
            record['fixture_artifacts'].append(copy_proof(home, source, target, relative))


def checked_delete(home, temp_root, evidence_root):
    absolute = home.absolute()
    if (absolute.is_symlink() or absolute.resolve(strict=True) != absolute
            or absolute.parent != temp_root or temp_root.resolve(strict=True) != temp_root
            or absolute == evidence_root or absolute in evidence_root.parents
            or evidence_root == temp_root or temp_root in evidence_root.parents):
        raise AssertionError('refusing recursive delete outside the owned temporary root')
    shutil.rmtree(absolute)


@contextmanager
def case(evidence, record, spec):
    home = Path(tempfile.mkdtemp(prefix=record['id'] + '-', dir=evidence.temp_root)).resolve(strict=True)
    record.update(status='RUNNING', home=str(home), started=stamp())
    evidence.save()
    try:
        yield home
        verify_workers(spec, record)
        verify_fixture(home, spec, record)
        archive_fixture(evidence, record, home)
        record['status'] = 'VERIFIED_AND_ARCHIVED'
        atomic_json(evidence.directory(record) / 'case.json', record)
        evidence.save()
    except BaseException as error:
        record.update(status='FAILED_INPUT_PRESERVED', error=error_record(error), temporary_home_deleted=False,
                      temporary_home_present=home.exists(), cleanup_attempted=False)
        evidence.data['status'] = 'FAILED'
        try:
            atomic_json(evidence.directory(record) / 'case.json', record)
        except BaseException as evidence_error:
            print('Additional case evidence write error; preserving primary:', evidence_error, flush=True)
        try:
            evidence.save()
        except BaseException as evidence_error:
            print('Additional evidence write error; preserving primary:', evidence_error, flush=True)
        print('Preserved failure input and launcher PID:', home, evidence.path / record['id'], flush=True)
        raise
    # Cleanup happens only after original workers and independent readback/copies
    # succeeded. A cleanup failure retains the verified archive, not a false
    # claim that an already partially removed temporary tree is still intact.
    try:
        checked_delete(home, evidence.temp_root, evidence.path)
        record['temporary_home_deleted'] = True
        record['status'] = 'PASSED'
        record['ended'] = stamp()
        atomic_json(evidence.directory(record) / 'case.json', record)
        evidence.save()
    except BaseException as error:
        record.update(status='CLEANUP_FAILED_ARCHIVE_RETAINED', cleanup_error=error_record(error),
                      temporary_home_deleted=not home.exists())
        try:
            evidence.save()
        except BaseException as evidence_error:
            print('Additional evidence write error:', evidence_error, flush=True)
        raise


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--flutter', required=True)
    p.add_argument('--app', required=True, type=Path)
    p.add_argument('--evidence', required=True, type=Path, help='new directory outside temporary/live fixture homes')
    args = p.parse_args()
    args.app = args.app.absolute().resolve(strict=True)
    evidence = Evidence(args.evidence, args)
    if sys.platform != 'linux' or not Path('/proc/self/stat').exists():
        evidence.data.update(status='UNSUPPORTED_NO_LINUX_OS_QUALIFICATION', ended=stamp())
        evidence.save()
        raise SystemExit('Linux /proc required; Windows/Android process-kill routing remains unverified')
    evidence.temp_root = Path(tempfile.mkdtemp(prefix='aerove-rule-process-')).resolve(strict=True)
    if evidence.temp_root == evidence.path or evidence.temp_root in evidence.path.parents:
        raise AssertionError('evidence must not be beneath the deletable temporary root')
    evidence.data.update(status='RUNNING', temporary_root=str(evidence.temp_root))
    evidence.save()
    try:
        for record, spec in zip(evidence.data['cases'], CASES):
            with case(evidence, record, spec) as home:
                for index, (mode, phase, expected) in enumerate(spec[1]):
                    if spec[0] == 'third-file' and index == 1:
                        raw = home / RAW
                        raw.write_text('third.example\n')
                    worker(args.flutter, args.app, home, mode, phase, expected,
                           evidence=evidence, case_record=record)
                if spec[0] == 'third-file':
                    assert raw.read_text() == 'third.example\n'
        # All homes have separately passed the bounded deletion check.
        if evidence.temp_root.resolve(strict=True) != evidence.temp_root:
            raise AssertionError('temporary root identity changed')
        evidence.temp_root.rmdir()
        evidence.data.update(status='PASSED', ended=stamp())
        evidence.save()
        print('Verified 10 cases; retained evidence:', evidence.path, flush=True)
    except BaseException as error:
        evidence.data.update(status='FAILED', error=error_record(error), ended=stamp())
        try:
            evidence.save()
        except BaseException as evidence_error:
            print('Additional evidence write error; preserving primary:', evidence_error, flush=True)
        raise


if __name__ == '__main__':
    main()