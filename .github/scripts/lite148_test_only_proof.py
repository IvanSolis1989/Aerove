"""Frozen Lite148 graph/keep proof. Never produce or replace an APK."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import signal
import subprocess
import sys
import time
import zipfile

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
KEEP_TASK = ':app:generateReleaseAndroidTestAppKeepRules'
APK_TASK = ':app:assembleReleaseAndroidTest'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=True, indent=2) + '\n', encoding='utf-8')


def git(root, *args):
    return subprocess.run(['git', '-C', str(root), *args], check=True, capture_output=True, timeout=30).stdout


def graph_receipt(path, mode, target, code):
    value = json.loads(path.read_text(encoding='utf-8'))
    require(value.get('mode') == mode and value.get('target') == target
            and value.get('requested') == [target] and value.get('exclusions') == []
            and value.get('graphPopulatedBeforeTaskActions') is True
            and isinstance(value.get('tasks'), list) and value['tasks'], 'Missing or invalid task graph receipt')
    if mode == 'admit':
        require(code == 0 and value.get('safe') is True and value.get('rejected') == [], 'Keep graph was not admitted')
    else:
        require(code != 0 and value.get('safe') is False, 'Observation must stop before task actions')
    return value


def source_contract(app, corrected, contract):
    original, candidate = contract['appSource'], contract['testSource']
    require(git(app, 'rev-parse', 'HEAD').decode().strip() == original, 'App checkout is not frozen Lite148')
    require(git(corrected, 'rev-parse', 'HEAD').decode().strip() == candidate, 'Test checkout is not frozen correction')
    require(not git(app, 'status', '--porcelain', '--untracked-files=normal'), 'App checkout must be pristine')
    changed = [p.decode() for p in git(app, 'diff', '--no-renames', '--name-only', '-z', original, candidate).split(b'\0') if p]
    require(sorted(changed) == sorted(contract['testFiles']), 'Source pair changes more than the two frozen Test files')
    for path, expected in contract['testFiles'].items():
        for revision, key in ((original, 'oldSha256'), (candidate, 'newSha256')):
            raw = git(app, 'show', revision + ':' + path)
            require(sha(raw) == expected[key], 'Frozen Test bytes differ')
    entries = git(app, 'ls-tree', '-r', '-z', original)
    protected = b'\0'.join(row for row in entries.split(b'\0') if row and
        row.split(b'\t', 1)[1].decode() not in contract['testFiles'])
    return dict(originalTree=git(app, 'rev-parse', original + '^{tree}').decode().strip(),
        testTree=git(app, 'rev-parse', candidate + '^{tree}').decode().strip(),
        changedPaths=changed, unchangedProductionGitClosureSha256=sha(protected))


def gh(route, binary=False):
    args = ['gh', 'api', '--method', 'GET', route]
    if binary:
        args += ['-H', 'Accept: application/octet-stream']
    result = subprocess.run(args, capture_output=True, timeout=120)
    require(result.returncode == 0, 'GitHub evidence read failed; raw stderr/URLs are not persisted')
    return result.stdout if binary else json.loads(result.stdout)


def baseline(out, abi, contract):
    fixed = contract['diagnostics'][abi]
    prefix = 'repos/' + contract['repository'] + '/actions/'
    run = gh(prefix + 'runs/' + str(contract['appRun']) + '/attempts/1')
    require(run.get('id') == contract['appRun'] and run.get('head_sha') == contract['appSource']
            and run.get('run_attempt') == 1 and run.get('status') == 'completed'
            and run.get('path') == '.github/workflows/aerove-lite.yaml'
            and (run.get('repository') or {}).get('full_name') == contract['repository']
            and (run.get('head_repository') or {}).get('full_name') == contract['repository'], 'Original run identity mismatch')
    jobs = []
    page = 1
    while True:
        response = gh(prefix + f"runs/{contract['appRun']}/attempts/1/jobs?per_page=100&page={page}")
        rows = response.get('jobs')
        require(isinstance(rows, list), 'Original jobs response invalid')
        jobs.extend(rows)
        if len(jobs) >= response.get('total_count', 0):
            require(len(jobs) == response['total_count'], 'Original job pagination changed')
            break
        page += 1
        require(page <= 5 and rows, 'Original job pagination exceeds bound')
    selected = [j for j in jobs if j.get('name') == f'build ({abi}) / package']
    require(len(selected) == 1 and selected[0].get('conclusion') == 'success'
            and selected[0].get('head_sha') == contract['appSource'] and selected[0].get('run_id') == contract['appRun']
            and selected[0].get('run_attempt') == 1, 'Original ABI package did not succeed in its original attempt')
    meta = gh(prefix + 'artifacts/' + str(fixed['id']))
    origin = meta.get('workflow_run') or {}
    require(meta.get('id') == fixed['id'] and meta.get('name') == fixed['name'] and meta.get('expired') is False
            and meta.get('digest') == 'sha256:' + fixed['zipSha256'] and meta.get('size_in_bytes') == fixed['zipBytes']
            and origin.get('id') == contract['appRun'] and origin.get('head_sha') == contract['appSource'], 'Original diagnostics identity mismatch')
    raw = gh(prefix + 'artifacts/' + str(fixed['id']) + '/zip', binary=True)
    require(len(raw) == fixed['zipBytes'] and sha(raw) == fixed['zipSha256'], 'Original diagnostics ZIP changed')
    archive = out / 'baseline.zip'
    archive.write_bytes(raw)
    suffixes = {name: 'androidTestAppKeepRules/release/' + name for name in ('rules.pro', 'inputs.json')}
    with zipfile.ZipFile(archive) as z:
        names = z.namelist()
        require(len(names) == len(set(names)) and len(names) <= 12 and z.testzip() is None, 'Diagnostics ZIP invalid')
        for entry in z.infolist():
            p = PurePosixPath(entry.filename)
            require(not p.is_absolute() and '..' not in p.parts and '\\' not in entry.filename
                    and entry.file_size <= 4_000_000 and (entry.external_attr >> 16) & 0o170000 != 0o120000, 'Unsafe diagnostics member')
        for name, suffix in suffixes.items():
            matches = [n for n in names if n.endswith('/' + suffix)]
            require(len(matches) == 1, 'Original keep baseline missing/ambiguous')
            data = z.read(matches[0])
            if 'entries' in fixed:
                expected = fixed['entries'][name]
                require(len(data) == expected['bytes'] and sha(data) == expected['sha256'], 'Indexed baseline entry changed')
            (out / name).write_bytes(data)
    trace = json.loads((out / 'inputs.json').read_text())
    require(trace.get('rulesSha256') == sha((out / 'rules.pro').read_bytes()), 'Original keep report/rules pair is inconsistent')
    archive.unlink()
    save(out / 'ORIGINAL-BASELINE.json', dict(diagnosticOnly=True, accepted=False, originalRunConclusion=run.get('conclusion'),
        originalSource=contract['appSource'], originalRun=contract['appRun'], originalAttempt=1,
        artifact=fixed, packageJobId=selected[0]['id'], originalAppBytesRead=False,
        entries={n: dict(bytes=(out / n).stat().st_size, sha256=sha((out / n).read_bytes())) for n in suffixes}))


def bounded_gradle(args, cwd, environment, log, seconds):
    with log.open('wb') as stream:
        child = subprocess.Popen(args, cwd=cwd, env=environment, stdout=stream,
            stderr=subprocess.STDOUT, start_new_session=True)
        started = time.monotonic()
        try:
            while child.poll() is None:
                require(time.monotonic() - started <= seconds, 'Gradle exceeded its diagnostic deadline')
                require(log.stat().st_size <= 16 * 1024 * 1024, 'Gradle output exceeded its diagnostic bound')
                time.sleep(0.25)
            return child.returncode
        finally:
            if child.poll() is None:
                os.killpg(child.pid, signal.SIGTERM)
                try:
                    child.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    os.killpg(child.pid, signal.SIGKILL)
                    child.wait(timeout=10)


def gradle(app, out, label, target, mode, abi, contract, dry):
    receipt = out / (label + '-graph.json')
    environment = {k:v for k,v in os.environ.items() if not any(s in k.upper() for s in ('TOKEN', 'PASSWORD', 'SECRET', 'KEYSTORE'))}
    environment.update(LITE148_GRAPH_MODE=mode, LITE148_GRAPH_TARGET=target, LITE148_GRAPH_RECEIPT=str(receipt))
    args = ['gradle', '--no-daemon', '--no-configuration-cache', '--no-build-cache', '--console=plain',
        '--init-script', str(HERE / 'lite148_test_only_guard.gradle'), target,
        '-PliteAbi=' + abi, '-PsourceSha=' + contract['appSource']]
    if dry:
        args += ['--dry-run']
    else:
        args += ['--rerun-tasks']
    log = out / (label + '-gradle.log')
    code = bounded_gradle(args, app / 'apps/aerove-lite', environment, log, 180 if dry else 780)
    value = graph_receipt(receipt, mode, target, code)
    require(not list((app / 'apps/aerove-lite/app/build/outputs').rglob('*.apk')), 'An APK was unexpectedly produced')
    return value


def identity_entries(report, role, excluded=()):
    values = report['orderedInputs'][role]
    require(isinstance(values, list) and values, 'Missing actual classpath evidence: ' + role)
    result = []
    for entry in values:
        require(not entry.get('missing'), 'Missing classpath entry')
        if entry.get('path') in excluded:
            continue
        item = {k:v for k,v in entry.items() if k != 'path'}
        require('sha256' in item or 'classManifestSha256' in item, 'No actual input digest')
        result.append(item)
    return result


def compare_reports(old, new, *, original_reproduction=False):
    for report in (old, new):
        require(report.get('schema') == 3 and report.get('generator') == 'R8 TraceReferences'
                and report.get('allowObfuscation') is True and report.get('referenceDiagnostics', {}).get('traceCompleted') is True
                and report.get('referenceDiagnostics', {}).get('fatalCount') == 0, 'Typed original App boundary was not accepted')
    keys = ('helperSourceSha256', 'r8ClasspathSha256')
    require(all(old.get(k) == new.get(k) for k in keys), 'Trace helper/R8 toolchain changed')
    for role in ('targets', 'libraries', 'r8Classpath'):
        require(identity_entries(old, role) == identity_entries(new, role), 'Actual production input closure changed: ' + role)
    for role in ('target', 'library'):
        require(old['inputClasses'][role] == new['inputClasses'][role], 'Normalized production classes changed: ' + role)
    old_sources = {x['path'] for x in old['orderedInputs']['sources']}
    new_sources = {x['path'] for x in new['orderedInputs']['sources']}
    require(identity_entries(old, 'sourceDependencies', old_sources) == identity_entries(new, 'sourceDependencies', new_sources),
            'Actual Test dependency closure changed beyond project Test classes')
    if original_reproduction:
        require(old['inputClasses']['source'] == new['inputClasses']['source'], 'Original Test classes were not reproduced')
    require(old['rulesSha256'] == new['rulesSha256'], 'Test correction changes original App keep rules')


def proof(app, corrected, out, abi, contract, phase):
    require(os.environ.get('GITHUB_REPOSITORY') == contract['repository']
            and os.environ.get('GITHUB_REF') == contract['harnessRef']
            and os.environ.get('GITHUB_RUN_ATTEMPT') == '1', 'Only fixed first-attempt isolated diagnostic workflow is allowed')
    out.mkdir(parents=True, exist_ok=False)
    receipt = dict(schema=1, diagnosticOnly=True, accepted=False, promotionAccepted=False,
        appApkBuilt=False, testApkBuilt=False, installed=False, originalAppBytesRead=False,
        originalApp=dict(source=contract['appSource'], run=contract['appRun'], attempt=1, abi=abi,
            signerSha256=contract['signerSha256'], signerActuallyReadBack=False),
        newTest=dict(source=contract['testSource'], files=contract['testFiles'], apkBuilt=False),
        harness=dict(source=os.environ['GITHUB_SHA'], run=os.environ['GITHUB_RUN_ID'], attempt=1),
        phase=phase, keepProofPassed=False, completed=False)
    try:
        receipt['sourceClosure'] = source_contract(app, corrected, contract)
        receipt['keepGraph'] = gradle(app, out, 'keep-dry', KEEP_TASK, 'admit', abi, contract, True)
        receipt['testPackageObservation'] = gradle(app, out, 'test-package-observe', APK_TASK, 'observe', abi, contract, True)
        if phase == 'keep-proof':
            base = out / 'baseline'
            base.mkdir()
            baseline(base, abi, contract)
            gradle(app, out, 'original-keep', KEEP_TASK, 'admit', abi, contract, False)
            location = app / 'apps/aerove-lite/app/build/intermediates/androidTestAppKeepRules/release'
            original = json.loads((location / 'inputs.json').read_text())
            original_rules = (location / 'rules.pro').read_bytes()
            base_rules = (base / 'rules.pro').read_bytes()
            require(original.get('rulesSha256') == sha(original_rules), 'Reproduced keep pair is inconsistent')
            require(original_rules == base_rules, 'Original keep rules do not reproduce the saved App input')
            compare_reports(json.loads((base / 'inputs.json').read_text()), original, original_reproduction=True)
            save(out / 'ORIGINAL-KEEP-REPORT.json', original)
            for path in contract['testFiles']:
                raw = git(corrected, 'show', contract['testSource'] + ':' + path)
                (app / path).write_bytes(raw)
            require(sorted(p.decode() for p in git(app, 'diff', '--name-only', '-z').split(b'\0') if p)
                    == sorted(contract['testFiles']), 'Overlay changed a non-Test input')
            gradle(app, out, 'corrected-keep', KEEP_TASK, 'admit', abi, contract, False)
            corrected_report = json.loads((location / 'inputs.json').read_text())
            corrected_rules = (location / 'rules.pro').read_bytes()
            require(corrected_report.get('rulesSha256') == sha(corrected_rules), 'Corrected keep pair is inconsistent')
            require(corrected_rules == base_rules, 'New Test changes original App keep bytes')
            compare_reports(original, corrected_report)
            save(out / 'CORRECTED-KEEP-REPORT.json', corrected_report)
            receipt.update(keepProofPassed=True, testClassesActuallyCompiled=True,
                identicalKeep=dict(bytes=len(base_rules), sha256=sha(base_rules)),
                actualProductionTargetLibraryR8ClosureUnchanged=True)
        receipt['completed'] = True
        receipt['nextStageBlocked'] = ['No signed Test-only packaging producer implemented',
            'Original runtime mapping is indexed; actual producer extraction, original App readback and exact AGP mapping wiring still required',
            'Both ABI keep proofs must succeed before any new Test APK stage',
            'No Android method or render acceptance was executed']
        return 0
    except BaseException as error:
        receipt['errorType'] = type(error).__name__
        receipt['error'] = str(error)[:1200] if isinstance(error, ValueError) else 'Bounded diagnostic failed; inspect safe task logs'
        return 1
    finally:
        save(out / 'PROOF.json', receipt)
        print(json.dumps(dict(completed=receipt['completed'], keepProofPassed=receipt['keepProofPassed'],
            diagnosticOnly=True, accepted=False, abi=abi)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app-root', type=Path, required=True)
    parser.add_argument('--test-root', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--abi', choices=('arm64-v8a', 'x86_64'), required=True)
    parser.add_argument('--phase', choices=('graph', 'keep-proof'), required=True)
    args = parser.parse_args()
    require(sys.platform == 'linux' and os.environ.get('GITHUB_ACTIONS') == 'true',
            'Actual graph/keep proof requires the isolated Linux CI job')
    def cancelled(signum, frame):
        raise InterruptedError('Diagnostic cancellation')
    signal.signal(signal.SIGTERM, cancelled)
    contract = json.loads((HERE / 'lite148_test_only_contract.json').read_text())
    return proof(args.app_root.resolve(), args.test_root.resolve(), args.output.resolve(), args.abi, contract, args.phase)


if __name__ == '__main__':
    raise SystemExit(main())
