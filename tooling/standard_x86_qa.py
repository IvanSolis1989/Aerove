"""One internal x86 ABI build receipt and a first-screen probe, never promotion."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import struct
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile

SOURCE = 'd8557dc664d0614339b7a8a676697367568f698e'
TREE = '70d19dcdaba20519bda88b545bbcbf113ba9d019'
STANDARD_TREE = '5807e5924efb38f3db00da80f3ae078973a14094'
TARGET_KEY = SOURCE + '-android-x64'
PACKAGE = 'com.follow.clash.dev'
SIGNER = '0fc82c3b7b089b6244d9ed167b352ff76185417d07689853caca286fbdeb6e60'
VERSION_NAME = '0.11.0-beta.4'
VERSION_CODE = '2026102243'
FORMAL_PLAN_CODE = dict(source='d8557dc664d0614339b7a8a676697367568f698e', workflowRun='40143599605',
                        runNumber=43, versionCode='2026102243', dataRole='runtimeCodePlanOnly')
APK_NAME = 'Aerove-standard-internal-qa-d8557dc-x86_64.apk'
ARTIFACT_NAME = 'Aerove-standard-internal-x86-qa-d8557dc'
ORIGINAL = dict(source='c133772fc83b7984bdacb11e5dd41e2d65098771',
                sourceTree='fb4a8fb6f0f8d3126a5a637673b8f333d80a8888', buildRun='40082294388', artifactId=12555818245,
                abi='arm64-v8a', apkSha256='64c42740e9164b29cca0289ae229a1dbe4cf44899c475f867e0f62ad033cfca8',
                signerSha256='0fc82c3b7b089b6244d9ed167b352ff76185417d07689853caca286fbdeb6e60',
                package='com.follow.clash.dev', versionName='0.11.0-beta.3', versionCode='2026102240')


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def base_identity(run, attempt, harness, run_number):
    return dict(schema=1, kind='standard-internal-x86-qa', diagnosticOnly=True, accepted=False,
                normalAccepted=False, mainPromotionAllowed=False, source=SOURCE, sourceTree=TREE,
                standardSubtree=STANDARD_TREE, targetKey=TARGET_KEY,
                dataRoles=dict(source='newQaApplication', originalArmReference='immutableOldArmBaseline',
                               formalPlanCode='runtimeCodePlanOnly'), formalPlanCode=FORMAL_PLAN_CODE,
                buildRun=str(run), buildAttempt=int(attempt), buildRunNumber=int(run_number), harnessSource=harness,
                buildWorkflow=f'https://github.com/IvanSolis1989/Aerove/actions/runs/{run}',
                targetPlatform='android-x64', appEnv='pre', originalArmReference=ORIGINAL)


def validate_identity(info):
    expected = dict(schema=1, kind='standard-internal-x86-qa', diagnosticOnly=True, accepted=False,
                    normalAccepted=False, mainPromotionAllowed=False, source=SOURCE, sourceTree=TREE,
                    standardSubtree=STANDARD_TREE, targetKey=TARGET_KEY,
                    dataRoles=dict(source='newQaApplication', originalArmReference='immutableOldArmBaseline',
                                   formalPlanCode='runtimeCodePlanOnly'), formalPlanCode=FORMAL_PLAN_CODE,
                    targetPlatform='android-x64', appEnv='pre', originalArmReference=ORIGINAL)
    if any(info.get(k) != v or type(info.get(k)) is not type(v) for k, v in expected.items()):
        raise ValueError('QA/source/original ARM reference identity mismatch')
    run = info.get('buildRun', '')
    if (not re.fullmatch(r'[0-9]+', run) or run in {ORIGINAL['buildRun'], FORMAL_PLAN_CODE['workflowRun']}
            or info.get('buildWorkflow') != f'https://github.com/IvanSolis1989/Aerove/actions/runs/{run}'
            or not info.get('harnessSource') or info.get('buildAttempt') != 1
            or type(info.get('buildRunNumber')) is not int or info['buildRunNumber'] < 1):
        raise ValueError('New QA build must retain its own first-attempt run and harness identity')
    if FORMAL_PLAN_CODE['source'] != SOURCE or FORMAL_PLAN_CODE['versionCode'] != VERSION_CODE:
        raise ValueError('Formal runtime code plan does not belong to this exact QA application source')
    actual = dict(abi='x86_64', package=PACKAGE, versionName=VERSION_NAME,
                  versionCode=VERSION_CODE, signerSha256=SIGNER)
    if info.get('actual') != actual or info.get('apk', {}).get('file') != APK_NAME:
        raise ValueError('Actual QA APK identity differs from the pinned target')
    digest = info.get('apk', {}).get('sha256', '')
    if not re.fullmatch(r'[0-9a-f]{64}', digest) or digest == ORIGINAL['apkSha256']:
        raise ValueError('QA APK cannot be the original ARM artifact')


def validate_elf(name, data):
    if (not name.startswith('lib/x86_64/') or len(data) < 20 or data[:6] != b'\x7fELF\x02\x01'
            or struct.unpack_from('<H', data, 18)[0] != 62):
        raise ValueError('Expected only ELF64 little-endian x86_64 native libraries: ' + name)


def fallback_receipt(receipt):
    try:
        print('QA_RECEIPT_FALLBACK_JSON=' + json.dumps(receipt), file=sys.stderr, flush=True)
    except BaseException:
        pass


def remember_failure(receipt, phase, error):
    code = getattr(error, 'returncode', getattr(error, 'code', 1))
    if isinstance(error, KeyboardInterrupt):
        code = 130
    if not isinstance(code, int) or not code:
        code = 1
    if code < 0:
        code = 128 - code
    item = dict(phase=phase, type=type(error).__name__, error=repr(error), result=code)
    if 'primaryFailure' not in receipt:
        receipt['primaryFailure'] = item
    else:
        receipt.setdefault('secondaryFailures', []).append(item)
    receipt['status'] = 'FAILED'
    receipt['result'] = receipt['primaryFailure']['result']


def inspection_command(command, directory, label, timeout=60, check=True, receipt=None, suffix=''):
    record = dict(label=label, argv=[str(v) for v in command], result=None, timeout=False,
                  stdoutSaved=False, stderrSaved=False, diagnosticWriteErrors=[])
    if receipt is not None:
        receipt['commands'].append(record)
    stdout = stderr = b''
    primary = None
    try:
        completed = subprocess.run(record['argv'], capture_output=True, timeout=timeout)
        stdout, stderr = completed.stdout, completed.stderr
        record['result'] = completed.returncode
        if check:
            completed.check_returncode()
    except BaseException as error:
        primary = error
        record.update(error=repr(error), errorType=type(error).__name__, timeout=isinstance(error, subprocess.TimeoutExpired))
        stdout = getattr(error, 'stdout', None) or stdout
        stderr = getattr(error, 'stderr', None) or stderr
        raise
    finally:
        diagnostic_failure = None
        record['stdoutPreview'] = stdout[:8192].decode(errors='replace')
        record['stderrPreview'] = stderr[:8192].decode(errors='replace')
        for kind, data in [('stdout', stdout), ('stderr', stderr), ('command', None)]:
            path = directory / (label + ('.command.json' if kind == 'command' else '.' + kind + suffix))
            try:
                if kind == 'command':
                    save(path, record)
                else:
                    path.write_bytes(data)
                    record[kind + 'Saved'] = True
                    record[kind + 'Path'] = path.name
            except BaseException as error:
                if diagnostic_failure is None:
                    diagnostic_failure = (error, error.__traceback__)
                record['diagnosticWriteErrors'].append(dict(file=path.name, type=type(error).__name__, error=repr(error)))
        if diagnostic_failure is not None:
            fallback_receipt(record)
            if primary is None:
                error, traceback = diagnostic_failure
                raise error.with_traceback(traceback)
    return stdout


def inspect_apk(apk, tools, directory):
    directory.mkdir(parents=True, exist_ok=True)
    def inspect(label, args):
        return inspection_command([str(tools / label), *args, str(apk)], directory, label, suffix='.txt').decode(errors='replace')
    signature = inspect('apksigner', ['verify', '--verbose', '--print-certs'])
    if re.findall(r'^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-f]+)$', signature, re.M) != [SIGNER]:
        raise ValueError('QA signing certificate differs from the persistent original test certificate')
    badging = inspect('aapt', ['dump', 'badging'])
    if (not re.search(r"^package: name='" + re.escape(PACKAGE)
                      + r"' versionCode='" + VERSION_CODE + r"' versionName='" + re.escape(VERSION_NAME) + r"'", badging, re.M)
            or re.findall(r'^native-code:\s*(.+)$', badging, re.M) != ["'x86_64'"]):
        raise ValueError('QA package/version/native ABI badging mismatch')
    libraries = []
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or archive.testzip() is not None:
            raise ValueError('APK duplicate entries or CRC failure')
        for name in names:
            if name.startswith('lib/') and name.endswith('.so'):
                data = archive.read(name)
                validate_elf(name, data)
                libraries.append(dict(path=name, bytes=len(data), sha256=hashlib.sha256(data).hexdigest()))
    if not {'libapp.so', 'libflutter.so', 'libclash.so', 'librust_api.so'} <= {Path(x['path']).name for x in libraries}:
        raise ValueError('Missing Flutter/AOT/Go/Rust native library; no hooks may be disabled')
    return dict(abi='x86_64', package=PACKAGE, versionName=VERSION_NAME,
                versionCode=VERSION_CODE, signerSha256=SIGNER), libraries


def check_source(root):
    for args, expected in [(['rev-parse', 'HEAD'], SOURCE), (['rev-parse', 'HEAD^{tree}'], TREE),
                           (['rev-parse', 'HEAD:apps/aerove'], STANDARD_TREE),
                           (['diff', '--name-only', 'HEAD'], '')]:
        if subprocess.check_output(['git', '-C', str(root), *args], text=True).strip() != expected:
            raise ValueError('Fixed application source/tree or tracked content changed')


def build_guard_decision(current, runs, histories, artifacts):
    blockers = []
    if artifacts:
        blockers.append(dict(reason='QA_ARTIFACT_EXISTS_REUSE_ONLY', artifacts=artifacts))
    for run in runs:
        if run['id'] == current['id'] or run['run_number'] > current['run_number']:
            continue
        if run['status'] != 'completed':
            blockers.append(dict(run=run['id'], reason='PRIOR_RUN_ACTIVE_REVIEW_REQUIRED'))
        history = histories[run['id']]
        build_jobs = [job for job in history['jobs'] if job['name'] == 'build_internal_qa']
        if not build_jobs:
            blockers.append(dict(run=run['id'], reason='BUILD_HISTORY_MISSING_REVIEW_REQUIRED'))
        for job in build_jobs:
            compile_steps = [step for step in job.get('steps', []) if step['name'] == 'Build only the new x86 ABI once']
            if not compile_steps and job.get('conclusion') != 'skipped':
                blockers.append(dict(run=run['id'], reason='COMPILE_HISTORY_MISSING_REVIEW_REQUIRED'))
            for step in compile_steps:
                if step.get('conclusion') != 'skipped' and step.get('status') != 'queued':
                    blockers.append(dict(run=run['id'], reason='COMPILE_STEP_ENTERED_REVIEW_OR_REUSE_ONLY',
                                         status=step.get('status'), conclusion=step.get('conclusion')))
    return dict(diagnosticOnly=True, accepted=False, allowed=not blockers, blockers=blockers,
                currentRunExcluded=current['id'], applicationSource=SOURCE, targetKey=TARGET_KEY,
                targetPlatform='android-x64')


def guard(args):
    if os.environ.get('GITHUB_RUN_ATTEMPT') != '1':
        raise ValueError('Do not rerun the build job; retry a proven precompile failure only through a new scoped harness push')
    directory = args.directory / 'once-guard'
    directory.mkdir(parents=True, exist_ok=False)
    prefix = 'repos/IvanSolis1989/Aerove/actions/'
    def read(label, endpoint, field=None):
        data = json.loads(inspection_command(['gh', 'api', '--paginate', '--slurp', prefix + endpoint], directory, label))
        if field is None:
            if len(data) != 1:
                raise ValueError('Unexpected paginated single-object API result')
            return data[0]
        return [item for page in data for item in page[field]]
    current = read('current-run', 'runs/' + os.environ['GITHUB_RUN_ID'])
    runs = read('workflow-runs', f'workflows/{current["workflow_id"]}/runs?per_page=100', 'workflow_runs')
    artifacts = read('qa-artifacts', 'artifacts?name=' + ARTIFACT_NAME + '&per_page=100', 'artifacts')
    histories = {}
    for run in runs:
        if run['id'] != current['id'] and run['run_number'] <= current['run_number']:
            histories[run['id']] = dict(
                jobs=read(f'jobs-{run["id"]}', f'runs/{run["id"]}/jobs?filter=all&per_page=100', 'jobs'))
    result = build_guard_decision(current, runs, histories, artifacts)
    try:
        save(directory / 'ONCE-GUARD.json', result)
    except BaseException as error:
        result.update(receiptSaved=False, receiptError=repr(error))
        fallback_receipt(result)
        if result['allowed']:
            raise
    if not result['allowed']:
        raise ValueError('Prior target state blocks compilation: ' + json.dumps(result['blockers']))
    return 0


def verify(args):
    apk = args.directory / APK_NAME
    if apk.is_symlink() or not apk.is_file():
        raise ValueError('Missing plain QA APK')
    readback = (args.evidence if args.evidence else args.directory) / 'apk-readback'
    actual, libraries = inspect_apk(apk, args.build_tools, readback)
    if args.create:
        check_source(args.source_root)
        info = base_identity(os.environ['GITHUB_RUN_ID'], os.environ['GITHUB_RUN_ATTEMPT'], os.environ['GITHUB_SHA'],
                             os.environ['GITHUB_RUN_NUMBER'])
        info.update(actual=actual, nativeLibraries=libraries,
                    apk=dict(file=APK_NAME, sha256=sha(apk), bytes=apk.stat().st_size),
                    toolchain=dict(flutter='3.47.6', go='1.27.1', rust='1.99.0', ndk='28.2.13676358', java='17'),
                    applicationSourceCheck=dict(source=SOURCE, tree=TREE, standardSubtree=STANDARD_TREE,
                                                trackedContentUnchanged=True),
                    toolchainEvidence={p.name: sha(p) for p in (args.directory / 'toolchain').iterdir() if p.is_file()},
                    buildLogSha256=sha(args.directory / 'build.log'),
                    command='flutter build apk --no-pub --release --target-platform android-x64 '
                            f'--build-name {VERSION_NAME} --build-number {VERSION_CODE} --dart-define=APP_ENV=pre')
        validate_identity(info)
        save(args.directory / 'QA_BUILD_INFO.json', info)
        names = [APK_NAME, 'QA_BUILD_INFO.json']
        (args.directory / 'QA_SHA256SUMS').write_text(''.join(f'{sha(args.directory / n)}  {n}\n' for n in names))
    else:
        info = json.loads((args.directory / 'QA_BUILD_INFO.json').read_text())
        validate_identity(info)
        if info['apk'] != dict(file=APK_NAME, sha256=sha(apk), bytes=apk.stat().st_size) or info['actual'] != actual or info['nativeLibraries'] != libraries:
            raise ValueError('Saved QA receipt differs from actual APK readback')
        expected = ''.join(f'{sha(args.directory / n)}  {n}\n' for n in [APK_NAME, 'QA_BUILD_INFO.json'])
        if (args.directory / 'QA_SHA256SUMS').read_text() != expected:
            raise ValueError('QA checksum sidecar mismatch')
    return info


def validate_initial_app_ui(ui_tree, activity_state):
    root = ET.fromstring(ui_tree)
    if not any(node.get('package') == PACKAGE for node in root.iter('node')):
        raise RuntimeError('Captured UI tree does not belong to the QA app')
    resumed = re.compile(r'^\s*(?:topResumedActivity|mResumedActivity)\s*[=:].*?(?<!\S)'
                         + re.escape(PACKAGE + '/com.follow.clash.MainActivity')
                         + r'(?=[\s}])', re.MULTILINE)
    if not resumed.search(activity_state):
        raise RuntimeError('QA MainActivity is not the resumed activity at capture')


def first_activity_pid(log):
    found = re.search(r'Start proc ([0-9]+):' + re.escape(PACKAGE)
                      + r'/\S+ for (?:next-top-|top-)?activity\s', log)
    return found.group(1) if found else None


def app_failure_lines(log, pid):
    lines = log.splitlines()
    failures = []
    for index, line in enumerate(lines):
        if (('Fatal signal' in line and re.search(r'\bpid ' + re.escape(pid) + r'\b', line))
                or re.search(r'Process ' + re.escape(PACKAGE) + r' \(pid ' + re.escape(pid) + r'\) has died', line)
                or ('FATAL EXCEPTION' in line and f'Process: {PACKAGE}, PID: {pid}' in '\n'.join(lines[index:index + 8]))):
            failures.append(line)
    return failures


def run_probe(args):
    args.evidence.mkdir(parents=True, exist_ok=False)
    receipt = dict(diagnosticOnly=True, accepted=False, status='STARTED', commands=[], apkModified=False,
                   vpnRequested=False, demoInjected=False, twelveThemeCaptures=False)
    adb = ['adb', '-s', 'emulator-5554']
    installed = False
    cancelled = []
    def interrupt(signum, frame):
        cancelled.append(signum)
        raise SystemExit(128 + signum)
    previous = {s: signal.signal(s, interrupt) for s in (signal.SIGTERM, signal.SIGINT)}
    def command(label, values, timeout=25, check=True):
        return inspection_command(values, args.evidence, label, timeout, check, receipt)
    try:
        try:
            receipt['qaBuild'] = verify(args)
            props = {p: command('prop-' + p, adb + ['shell', 'getprop', p]).decode().strip()
                     for p in ['sys.boot_completed', 'ro.build.version.sdk', 'ro.product.cpu.abilist64',
                               'ro.build.fingerprint', 'ro.dalvik.vm.native.bridge']}
            receipt['properties'] = props
            if props['sys.boot_completed'] != '1' or props['ro.build.version.sdk'] != '33' or 'x86_64' not in props['ro.product.cpu.abilist64'].split(','):
                raise ValueError('Expected booted API33 x86_64 emulator')
            if command('existing-package', adb + ['shell', 'pm', 'path', PACKAGE], check=False).strip():
                raise ValueError('Refusing to replace existing app/data')
            command('prelaunch-logcat', adb + ['logcat', '-d', '-v', 'threadtime'])
            command('clear-fresh-log', adb + ['logcat', '-c'])
            command('install-x86-qa', adb + ['install', '--abi', 'x86_64', args.directory / APK_NAME], timeout=90)
            installed = True
            package = command('installed-package', adb + ['shell', 'dumpsys', 'package', PACKAGE]).decode()
            if 'primaryCpuAbi=x86_64' not in package or f'versionCode={VERSION_CODE}' not in package:
                raise ValueError('Installed QA ABI/version mismatch')
            launch = command('launch', adb + ['shell', 'am', 'start', '-W', '-n', PACKAGE + '/com.follow.clash.MainActivity'], timeout=35).decode()
            if 'Status: ok' not in launch:
                raise RuntimeError('QA MainActivity launch request failed')
            time.sleep(12)
            log = command('first-logcat', adb + ['logcat', '-d', '-v', 'threadtime']).decode(errors='replace')
            pid = first_activity_pid(log)
            receipt['firstActivityPid'] = pid
            if pid is None:
                raise RuntimeError('First MainActivity process could not be identified from original logcat')
            failures = app_failure_lines(log, pid)
            receipt['firstFailureLines'] = failures
            if failures:
                raise RuntimeError('First MainActivity process crashed or died: ' + failures[0])
            if not re.search(r'Displayed ' + re.escape(PACKAGE + '/com.follow.clash.MainActivity') + r':', log):
                raise RuntimeError('Window manager has not reported the first MainActivity frame displayed')
            if command('live-pid', adb + ['shell', 'pidof', PACKAGE]).decode().strip().split() != [pid]:
                raise RuntimeError('First MainActivity PID was replaced or is no longer uniquely alive')
            command('ui-dump', adb + ['shell', 'uiautomator', 'dump', '/sdcard/standard-x86-qa-ui.xml'])
            ui = command('ui-tree', adb + ['exec-out', 'cat', '/sdcard/standard-x86-qa-ui.xml']).decode()
            state = command('activity-state', adb + ['shell', 'dumpsys', 'activity', 'activities']).decode()
            validate_initial_app_ui(ui, state)
            screen = command('initial-screen', adb + ['exec-out', 'screencap', '-p'])
            if not screen.startswith(b'\x89PNG\r\n\x1a\n'):
                raise RuntimeError('Initial screenshot is not a PNG')
            (args.evidence / 'initial-screen.png').write_bytes(screen)
            validate_initial_app_ui(ui, command('activity-after-screen', adb + ['shell', 'dumpsys', 'activity', 'activities']).decode())
            if command('pid-after-screen', adb + ['shell', 'pidof', PACKAGE]).decode().strip() != pid:
                raise RuntimeError('First MainActivity PID changed during capture')
            receipt['status'] = 'INITIAL_QA_SCREEN_CAPTURED_REVIEW_REQUIRED'
        except BaseException as error:
            remember_failure(receipt, 'probe', error)
        finally:
            receipt['cleanupErrors'] = []
            for label, cmd in [('final-logcat', ['logcat', '-d', '-v', 'threadtime']),
                               ('crash-logcat', ['logcat', '-b', 'crash', '-d', '-v', 'threadtime']),
                               ('final-activity', ['shell', 'dumpsys', 'activity', 'activities']),
                               ('final-window', ['shell', 'dumpsys', 'window', 'windows'])]:
                try:
                    data = command(label, adb + cmd)
                    if label == 'final-logcat' and receipt.get('firstActivityPid'):
                        failures = app_failure_lines(data.decode(errors='replace'), receipt['firstActivityPid'])
                        if failures:
                            remember_failure(receipt, 'final-app-crash', RuntimeError(failures[0]))
                        receipt['firstFailureLines'] = failures
                except BaseException as error:
                    receipt['cleanupErrors'].append(dict(phase=label, error=repr(error)))
                    remember_failure(receipt, label, error)
            if installed or any(c['label'] == 'install-x86-qa' and c.get('result') == 0 for c in receipt['commands']):
                try:
                    command('stop-task-app', adb + ['shell', 'am', 'force-stop', PACKAGE])
                except BaseException as error:
                    receipt['cleanupErrors'].append(dict(phase='force-stop', error=repr(error)))
                    remember_failure(receipt, 'force-stop', error)
            if cancelled and 'primaryFailure' not in receipt:
                remember_failure(receipt, 'cancellation', SystemExit(128 + cancelled[0]))
            receipt['cancelSignals'] = cancelled
            try:
                save(args.evidence / 'FIRST-SCREEN.json', receipt)
            except BaseException as error:
                remember_failure(receipt, 'receipt-save', error)
                receipt['receiptSaved'] = False
                fallback_receipt(receipt)
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)
    return receipt.get('result', 0 if receipt['status'] == 'INITIAL_QA_SCREEN_CAPTURED_REVIEW_REQUIRED' else 1)


def capture(args):
    sys.path.insert(0, str(args.harness / 'apps/aerove-lite/tool'))
    from emulator_bootstrap import wait_ready
    from emulator_standard_reference import record_failure, persist, run_stage
    from emulator_qa79 import emulator_environment, renderer_evidence, crash_inventory, preserve_owned_crash, finish_emulator
    if sys.platform != 'linux' or os.environ.get('GITHUB_ACTIONS') != 'true':
        raise ValueError('Only a fresh isolated GitHub Linux/KVM runner is allowed')
    args.evidence.mkdir(parents=True, exist_ok=False)
    receipt = dict(owner='standard_x86_qa.py', ownerPid=os.getpid(), diagnosticOnly=True, accepted=False,
                   source=SOURCE, sourceTree=TREE, harnessSource=os.environ['GITHUB_SHA'],
                   harnessRun=os.environ['GITHUB_RUN_ID'], qaArtifactId=os.environ['QA_ARTIFACT_ID'],
                   primaryResult=0, interruptSignals=[], children=[],
                   cleanupErrors=[], gpuBackend='swangle', status='STARTED')
    def check(value):
        if value['interruptSignals']:
            raise InterruptedError('QA owner cancelled')
        if time.monotonic() >= deadline:
            raise TimeoutError('QA owned emulator deadline')
    phase = 'runtime'
    late_handling = False
    def interrupt(sig, frame):
        nonlocal late_handling
        receipt['interruptSignals'].append(sig)
        if phase == 'finalized' and not late_handling:
            late_handling = True
            record_failure(receipt, 'finalized-cancellation', SystemExit(128 + sig))
            receipt.update(result=receipt['primaryResult'], status='FAILED')
            try:
                persist(args.evidence, receipt)
            finally:
                raise SystemExit(receipt['primaryResult'])
    previous = {s: signal.signal(s, interrupt) for s in (signal.SIGTERM, signal.SIGINT)}
    emulator = None
    owned = []
    before = None
    deadline = time.monotonic() + 780
    env = emulator_environment(dict(os.environ, ANDROID_SERIAL='emulator-5554'))
    try:
        before = crash_inventory()
        command = [str(Path(os.environ['ANDROID_HOME']) / 'emulator/emulator'), '-port', '5554', '-avd', 'test',
                   '-no-window', '-gpu', 'swangle', '-no-snapshot', '-noaudio', '-no-boot-anim']
        receipt['command'] = command
        with (args.evidence / 'emulator.stdout.log').open('wb') as out, (args.evidence / 'emulator.stderr.log').open('wb') as err:
            emulator = subprocess.Popen(command, stdout=out, stderr=err, stdin=subprocess.DEVNULL,
                                        start_new_session=True, env=env)
            receipt['emulatorPid'] = emulator.pid
            wait_ready(emulator, args.evidence, receipt, check)
            receipt['rendererEvidence'] = renderer_evidence((args.evidence / 'emulator.stdout.log').read_text(errors='replace'))
            run_stage('first-screen', [sys.executable, __file__, 'probe', '--directory', str(args.directory),
                                       '--evidence', str(args.evidence / 'first-screen'), '--build-tools', str(args.build_tools)],
                      300, emulator, receipt, owned, args.evidence, env, deadline)
            check(receipt)
            receipt['status'] = 'INITIAL_QA_SCREEN_CAPTURED_REVIEW_REQUIRED'
    except BaseException as error:
        record_failure(receipt, 'qa-runtime', error)
    finally:
        from emulator_qa79 import stop_owned
        for child, row in reversed(owned):
            try:
                row['cleanup'] = stop_owned(child)
            except BaseException as error:
                record_failure(receipt, 'probe-cleanup', error)
        if emulator is not None:
            try:
                result = finish_emulator(emulator, receipt, receipt['primaryResult'])
                if result and not receipt['primaryResult']:
                    record_failure(receipt, 'emulator-exit', RuntimeError('Emulator exited before owned cleanup'))
            except BaseException as error:
                record_failure(receipt, 'emulator-cleanup', error)
            try:
                receipt['crashpad'] = preserve_owned_crash(args.evidence, emulator.pid, before or {})
            except BaseException as error:
                record_failure(receipt, 'crashpad', error)
        try:
            phase = 'finalized'
            if receipt['interruptSignals']:
                record_failure(receipt, 'cancellation', SystemExit(128 + receipt['interruptSignals'][0]))
            receipt['result'] = receipt['primaryResult']
            if receipt['result']:
                receipt['status'] = 'FAILED'
            persisted = persist(args.evidence, receipt)
        finally:
            for sig, handler in previous.items():
                signal.signal(sig, handler)
    return receipt['result'] or (0 if persisted else 1)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('phase', choices=['guard', 'verify', 'probe', 'capture'])
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--build-tools', type=Path)
    parser.add_argument('--create', action='store_true')
    parser.add_argument('--source-root', type=Path)
    parser.add_argument('--harness', type=Path)
    parser.add_argument('--evidence', type=Path)
    args = parser.parse_args()
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise ValueError('QA commands require an isolated GitHub Actions runner')
    if args.phase == 'guard':
        return guard(args)
    if args.build_tools is None:
        parser.error('--build-tools is required outside the read-only once guard')
    if args.phase == 'verify':
        verify(args)
        return 0
    return run_probe(args) if args.phase == 'probe' else capture(args)


if __name__ == '__main__':
    raise SystemExit(main())
