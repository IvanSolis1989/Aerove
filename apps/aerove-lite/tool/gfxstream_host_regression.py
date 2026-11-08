"""Bounded host-only recipe supervisor. No Android execution or acceptance."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time

START_FREE = 24 * 1024**3
RESERVE_FREE = 4 * 1024**3
MAX_SECONDS = 12000


def free_bytes(path):
    return shutil.disk_usage(path).free


def session_members(session):
    members = []
    for path in Path('/proc').glob('[0-9]*/stat'):
        try:
            raw = path.read_text()
            fields = raw[raw.rfind(')') + 2:].split()
            if int(fields[3]) == session and fields[0] != 'Z':
                members.append((int(path.parent.name), int(fields[19])))
        except (OSError, ValueError, IndexError):
            continue
    return members


def signal_session(session, signum):
    # GNU timeout creates another process group, but remains in our private session.
    # pidfds + identity reread prevent PID-reuse signalling. No global pkill.
    for pid, start in session_members(session):
        try:
            fd = os.pidfd_open(pid)
            try:
                if (pid, start) in session_members(session):
                    signal.pidfd_send_signal(fd, signum)
            finally:
                os.close(fd)
        except ProcessLookupError:
            pass


def stop_owned_session(child):
    # The session was created exclusively for our Popen child; live members retain its ID.
    signal_session(child.pid, signal.SIGTERM)
    deadline = time.monotonic() + 20
    while session_members(child.pid) and time.monotonic() < deadline:
        time.sleep(.1)
    if session_members(child.pid):
        signal_session(child.pid, signal.SIGKILL)
    child.wait(timeout=10)
    deadline = time.monotonic() + 5
    while session_members(child.pid) and time.monotonic() < deadline:
        time.sleep(.1)
    if session_members(child.pid):
        raise RuntimeError('Owned host recipe session did not stop')


def export_evidence(build, evidence):
    # Explicit shallow allowlist. Never collect source repositories, SDK, environment,
    # debug memory, core files, or arbitrary recursively discovered files.
    names = ('build-receipt.json', 'parser-reference-readback.json', 'parser-identity.json', 'source-pins.json',
             'resolved-manifest.xml', 'partial-resolved-manifest.xml', 'source-identity.txt',
             'gtest-identity.txt', 'compiler-files.sha256', 'libcxx-macros.txt',
             'compiler-contract-command.json', 'gles-regression.xml', 'base-elf.txt',
             'derived-elf.txt', 'base-exports.txt', 'derived-exports.txt',
             'source-sync-phase.txt', 'disk-before.txt', 'memory-before.txt',
             'source-network-df.txt', 'source-network-du.txt', 'source-local-df.txt',
             'source-local-du.txt', 'parser-strip-witness.json', 'host-sparse-plan.json',
             'source-project-paths.txt', 'source-checkout-current.txt', 'source-project-du.txt')
    candidates = [build / name for name in names]
    candidates += list((build / 'logs').glob('*.out')) + list((build / 'logs').glob('*.err'))
    candidates += list((build / 'logs').glob('*.exit'))
    candidates += [build / 'artifact' / name for name in
                   ('build-id.txt', 'SHA256SUMS', 'gfxstream-zero-attachments.patch',
                    'gfxstream-zero-attachments-tests.patch', 'gfxstream-x11-gtest.patch')]
    records = []
    for source in candidates:
        if (source.is_symlink() or not source.is_file()
                or not source.resolve().is_relative_to(build.resolve())):
            continue
        limit = 16 * 1024**2
        if source.stat().st_size > limit:
            records.append(dict(path=str(source.relative_to(build)), omitted='size-limit'))
            continue
        relative = source.relative_to(build)
        target = evidence / 'build' / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        records.append(dict(path=str(relative), bytes=target.stat().st_size,
                            sha256=hashlib.sha256(target.read_bytes()).hexdigest()))
    return records


def run(args):
    args.evidence.mkdir(parents=True, exist_ok=True)
    receipt = dict(diagnosticOnly=True, accepted=False, officialSdk=False,
                   appExecuted=False, emulatorExecuted=False, releaseAccepted=False,
                   status='started', executor={key: os.environ.get(key) for key in
                   ('GITHUB_SHA', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT')},
                   minimumStartFreeBytes=START_FREE,
                   reserveFreeBytes=RESERVE_FREE, timeoutSeconds=MAX_SECONDS)
    child = None
    code = 1
    def interrupted(signum, frame):
        raise InterruptedError('host recipe interrupted by signal ' + str(signum))
    old = {sig: signal.signal(sig, interrupted) for sig in (signal.SIGINT, signal.SIGTERM)}
    try:
        if args.output.exists():
            raise ValueError('Build output must be new')
        available = free_bytes(args.output.parent)
        receipt['initialFreeBytes'] = available
        if available < START_FREE:
            raise RuntimeError('Insufficient free disk for fixed integrated host recipe')
        script = Path(__file__).with_name('build_gfxstream_derived_host.sh')
        receipt['builderSha256'] = hashlib.sha256(script.read_bytes()).hexdigest()
        shutil.copyfile(script, args.evidence / script.name)
        # Fixed public-source recipe requires no API tokens or ambient feature overrides.
        env = {k: v for k, v in os.environ.items() if k in
               ('PATH', 'HOME', 'TMPDIR', 'LANG', 'LC_ALL', 'SSL_CERT_FILE', 'SSL_CERT_DIR')}
        env['DEBUGINFOD_URLS'] = ''
        with (args.evidence / 'host-recipe.out').open('wb') as out, (args.evidence / 'host-recipe.err').open('wb') as err:
            child = subprocess.Popen(['bash', str(script), str(args.base_library), str(args.output)],
                stdout=out, stderr=err, stdin=subprocess.DEVNULL, env=env, start_new_session=True)
            start = time.monotonic()
            minimum = available
            while child.poll() is None:
                minimum = min(minimum, free_bytes(args.output.parent))
                receipt['minimumObservedFreeBytes'] = minimum
                if minimum < RESERVE_FREE:
                    raise RuntimeError('Host recipe disk reserve reached')
                if time.monotonic() - start > MAX_SECONDS:
                    raise TimeoutError('Host recipe time budget exceeded')
                time.sleep(2)
            code = child.returncode
            receipt['recipeExit'] = code
            if code == 0:
                built = json.loads((args.output / 'build-receipt.json').read_text())
                if (built.get('status') != 'host-module-built-and-tested' or built.get('exitCode') != 0
                        or built.get('hostRegressionPassed') is not True
                        or built.get('builderSha256') != receipt['builderSha256']):
                    raise ValueError('Recipe success lacks matching RED/GREEN completion receipt')
            receipt.update(status='host-regression-complete' if code == 0 else 'recipe-failed')
    except Exception as error:
        receipt.update(status='failed', errorType=type(error).__name__, error=str(error))
        code = 1
    finally:
        if child is not None and session_members(child.pid):
            try:
                stop_owned_session(child)
                receipt['ownedRecipeTerminated'] = True
            except Exception as error:
                code = 1
                receipt.update(status='failed', cleanupError=str(error))
        if args.output.is_dir():
            # Bound a real usage snapshot even when a checkout is interrupted mid-project.
            with (args.evidence / 'owned-disk-at-exit.txt').open('wb') as disk:
                try:
                    subprocess.run(['du', '-x', '-B1', '--max-depth=4', str(args.output)],
                                   stdout=disk, stderr=subprocess.DEVNULL, timeout=20, check=False)
                except subprocess.TimeoutExpired:
                    receipt['diskSnapshotIncomplete'] = True
        receipt['files'] = export_evidence(args.output, args.evidence)
        receipt['result'] = code
        (args.evidence / 'HOST-REGRESSION.json').write_text(json.dumps(receipt, indent=2) + '\n')
        for sig, handler in old.items():
            signal.signal(sig, handler)
    return 0 if code == 0 else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('base-library', 'output', 'evidence'):
        parser.add_argument('--' + name, type=Path, required=True)
    raise SystemExit(run(parser.parse_args()))
