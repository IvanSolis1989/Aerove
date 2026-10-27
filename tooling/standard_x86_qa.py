"""One internal x86 ABI build of a formal Standard source and a first-screen probe, never promotion."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import struct
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET
import zipfile

REPOSITORY = 'IvanSolis1989/Aerove'
WORKFLOW_PATH = '.github/workflows/aerove-standard-x86-qa.yaml'
HARNESS_BRANCH = 'codex/aerove-lite-meow-app'
HARNESS_REF = 'refs/heads/' + HARNESS_BRANCH
HOST_OBSERVER_BRANCH = 'codex/aerove-standard-host-crash-31'
HOST_OBSERVER_REF = 'refs/heads/' + HOST_OBSERVER_BRANCH
RUN_TITLE = 'AEROVE Standard internal x86 QA for formal run '
REGISTRATION_TITLE = 'AEROVE Standard internal x86 QA registration push '
READBACK_BUILD_TOOLS = '35.0.0'
FORMAL_WORKFLOW = '.github/workflows/xclash-android.yaml'
FORMAL_WORKFLOW_NAME = 'Aerove Android'
FORMAL_APK = 'Aerove-android-arm64.apk'
PACKAGE = 'com.follow.clash.dev'
SIGNER = '0fc82c3b7b089b6244d9ed167b352ff76185417d07689853caca286fbdeb6e60'
APK_NAME = 'Aerove-standard-internal-qa-x86_64.apk'
ARTIFACT_PREFIX = 'Aerove-standard-internal-x86-qa-'
COMPILE_STEP = 'Build only the new x86 ABI once'
BUILD_STEPS = ('Resolve the dispatched formal ARM build run', 'Read actual prior QA build state before compiling',
               'Select the original artifacts main promotion would reuse',
               'Validate the formal BUILD_INFO with the release validator',
               'Read the formal ARM identity, signer and pinned toolchain',
               'Prepare pinned toolchain and dependencies before compilation', COMPILE_STEP,
               'Strict new-ABI APK readback and separate QA identity')
PROBE_WORKFLOW_PATH = '.github/workflows/aerove-standard-x86-qa-probe.yaml'
PROBE_TITLE = 'AEROVE Standard internal x86 QA probe of artifact {artifact} from run {run} for formal run {formal}'
PACKAGE_FILES = (APK_NAME, 'QA_BUILD_INFO.json', 'QA_SHA256SUMS')
FORMAL_ARTIFACTS = dict(artifactId='Aerove-android-arm64', generatedArtifactId='xclash-generated')
# Lite's supported mode on the same emulator build; swiftshader_indirect is deprecated since emulator 36.4.9.
GPU_BACKEND = 'swangle'
EMULATOR_FLAGS = ('-port', '5554', '-avd', 'test', '-no-window', '-gpu', GPU_BACKEND, '-no-snapshot', '-noaudio',
                  '-no-boot-anim')
FALLBACK_FLAGS = ('-port', '5554', '-avd', 'test', '-no-window', '-gpu', GPU_BACKEND, '-no-snapshot', '-wipe-data',
                  '-noaudio', '-no-boot-anim')
ATTEMPTS = dict(primary=dict(flags=EMULATOR_FLAGS, appRenderer='impeller'),
                fallback=dict(flags=FALLBACK_FLAGS, appRenderer='skia'))
CAPTURED = dict(impeller='INITIAL_QA_SCREEN_CAPTURED_REVIEW_REQUIRED',
                skia='NON_PRODUCTION_SKIA_SCREEN_CAPTURED_REVIEW_REQUIRED')
RENDERER_UNCONFIRMED = 'RENDERER_UNCONFIRMED_SCREEN_CAPTURED_REVIEW_REQUIRED'
SCREENSHOTS = dict(impeller='initial-screen.png', skia='initial-screen.NON-PRODUCTION-skia.png',
                   unconfirmed='initial-screen.RENDERER-UNCONFIRMED.png')
SKIA_OPT_OUT = ('--ez', 'enable-impeller', 'false')
APP_RENDERERS = (('Using the Impeller rendering backend (OpenGLES)', 'impeller-opengles'),
                 ('Using the Impeller rendering backend (Vulkan', 'impeller-vulkan'),
                 ('Impeller opt-out deprecated', 'skia'))
PRODUCTION_RENDERERS = ('impeller-opengles', 'impeller-vulkan')
OWNER_DEADLINE_SECONDS = 780
HOST_EXIT_GRACE_SECONDS = 30
OWNED_STOP_SECONDS = 30
# Owner adb state, the grace, the host-side logcat stop and two host diagnostics come out of the runtime deadline,
# so the owner still ends within the baseline 780 s plus its two owned stops.
ATTRIBUTION_SECONDS = 5 + HOST_EXIT_GRACE_SECONDS + OWNED_STOP_SECONDS + 2 * 10
# Only after the host exited, when the emulator itself needs no owned stop.
LIVE_DRAIN_SECONDS = 5
TERMINATION_SIGNALS = (15, 9)
CLEANUP_COMMANDS = ('device-state-after-failure', 'pre-cleanup-state', 'final-logcat', 'crash-logcat', 'final-activity',
                    'final-window', 'stop-task-app')
PROC = Path('/proc')
TOOLCHAIN = dict(flutter='3.47.6', go='1.27.1', rust='1.99.0', ndk='28.2.13676358', java='17')
FORMAL_PINS = (
    "  FLUTTER_VERSION: '3.47.6'",
    "          go-version: '1.27.1'",
    "          toolchain: '1.99.0'",
    '          ndk-version: r28c',
    "          java-version: '17'",
    '          flutter build apk --no-pub --release --target-platform android-arm64 \\',
    '            --build-name "$VERSION_NAME" --build-number "$VERSION_NUMBER" \\',
    '            --dart-define=APP_ENV="$APP_ENV"',
)
HARNESS_PINS = (
    "          flutter-version: '3.47.6'",
    "          go-version: '1.27.1'",
    "          toolchain: '1.99.0'",
    '          ndk-version: r28c',
    "          java-version: '17'",
    '          flutter build apk --no-pub --release --target-platform android-x64 \\',
    '            --build-name "$QA_VERSION_NAME" --build-number "$QA_VERSION_CODE" \\',
    '            --dart-define=APP_ENV="$QA_APP_ENV" 2>&1 | tee "$root/build.log"',
    "          timeout --kill-after=10s 300s sdkmanager --install 'build-tools;35.0.0' --channel=0",
    '          build_tools="$ANDROID_HOME/build-tools/35.0.0"',
)
OWN_LIBRARIES = ('libapp.so', 'libclash.so', 'libflutter.so', 'librust_api.so')
NATIVE_ABIS = {'arm64-v8a': (2, 183), 'armeabi-v7a': (1, 40), 'x86': (1, 3), 'x86_64': (2, 62)}
HISTORICAL_ARM = (
    dict(source='c133772fc83b7984bdacb11e5dd41e2d65098771', buildRun='40082294388', versionCode='2026102240',
         apkSha256='64c42740e9164b29cca0289ae229a1dbe4cf44899c475f867e0f62ad033cfca8'),
    dict(source='d8557dc664d0614339b7a8a676697367568f698e', buildRun='40143599605', versionCode='2026102243',
         apkSha256='392221a7808b6e036215304797da7cf025933a2f2a5078fcd190b3e729f6cb5e'),
)
SHA1 = re.compile(r'[0-9a-f]{40}')
SHA256 = re.compile(r'[0-9a-f]{64}')
RUN_ID = re.compile(r'[1-9][0-9]{0,19}')
VERSION_NAME = re.compile(r'(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?')
VERSION_CODE = re.compile(r'[1-9][0-9]{0,9}')
SUBMODULE_STATUS = re.compile(r'([ +U-])([0-9a-f]{40}) (\S+)(?: \(.*\))?')
FORMAL_FIELDS = dict(schema=int, role=str, repository=str, workflowPath=str, runId=int, runNumber=int,
                     runAttempt=int, event=str, headBranch=str, workflow=str, source=str, sourceTree=str,
                     standardSubtree=str, artifactId=int, generatedArtifactId=int, buildInfoSha256=str,
                     apkSha256=str, apkBytes=int, version=str, versionName=str, versionCode=str, appEnv=str,
                     package=str, abi=str, signerSha256=str)


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def load(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def save_atomic(path, value):
    temporary = path.with_name('.' + path.name + '.tmp')
    try:
        save(temporary, value)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def utc_now():
    now = time.time()
    return time.strftime('%Y-%m-%dT%H:%M:%S', time.gmtime(now)) + f'.{int(now * 1000) % 1000:03d}Z'


def target_key(source):
    return source + '-android-x64'


def artifact_name(source):
    return ARTIFACT_PREFIX + source


def run_url(run):
    return f'https://github.com/{REPOSITORY}/actions/runs/{run}'


def positive(value):
    return type(value) is int and value > 0


def output(values):
    with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8') as stream:
        stream.write(''.join(f'{key}={value}\n' for key, value in values.items()))


def validate_formal(formal):
    if not isinstance(formal, dict) or set(formal) != set(FORMAL_FIELDS):
        raise ValueError('Formal ARM build identity has missing or unexpected fields')
    if any(type(formal[key]) is not kind for key, kind in FORMAL_FIELDS.items()):
        raise ValueError('Formal ARM build identity has unexpected field types')
    name, code = formal['versionName'], formal['versionCode']
    if (formal['schema'] != 1 or formal['role'] != 'immutableFormalArmBuild' or formal['repository'] != REPOSITORY
            or formal['workflowPath'] != FORMAL_WORKFLOW or formal['event'] not in ('workflow_dispatch', 'push')
            or not all(positive(formal[key]) for key in ('runId', 'runNumber', 'runAttempt', 'artifactId',
                                                          'generatedArtifactId', 'apkBytes'))
            or not formal['headBranch'] or formal['workflow'] != run_url(formal['runId'])
            or not all(SHA1.fullmatch(formal[key]) for key in ('source', 'sourceTree', 'standardSubtree'))
            or not all(SHA256.fullmatch(formal[key]) for key in ('buildInfoSha256', 'apkSha256', 'signerSha256'))
            or not VERSION_NAME.fullmatch(name) or not VERSION_CODE.fullmatch(code)
            or formal['version'] != f'{name}+{code}' or formal['appEnv'] != ('pre' if '-' in name else 'stable')
            or formal['package'] != PACKAGE or formal['abi'] != 'arm64-v8a' or formal['signerSha256'] != SIGNER):
        raise ValueError('Formal ARM build identity is inconsistent')
    if any(formal['apkSha256'] == old['apkSha256'] or str(formal['runId']) == old['buildRun']
           or formal['source'] == old['source'] for old in HISTORICAL_ARM):
        raise ValueError('Formal ARM build reuses a historical ARM identity')
    return formal


def base_identity(formal, run, attempt, harness, run_number, ref):
    return dict(schema=2, kind='standard-internal-x86-qa', diagnosticOnly=True, accepted=False,
                normalAccepted=False, mainPromotionAllowed=False, source=formal['source'],
                sourceTree=formal['sourceTree'], standardSubtree=formal['standardSubtree'],
                targetKey=target_key(formal['source']),
                dataRoles=dict(source='newQaApplication', formalArmBuild='immutableFormalArmBuild',
                               versionCode='formalBuildInfoOnly'), formalArmBuild=formal,
                buildRun=str(run), buildAttempt=int(attempt), buildRunNumber=int(run_number), harnessSource=harness,
                harnessRef=ref, buildWorkflow=run_url(run), targetPlatform='android-x64', appEnv=formal['appEnv'])


def validate_identity(info):
    formal = validate_formal(info.get('formalArmBuild'))
    expected = dict(schema=2, kind='standard-internal-x86-qa', diagnosticOnly=True, accepted=False,
                    normalAccepted=False, mainPromotionAllowed=False, source=formal['source'],
                    sourceTree=formal['sourceTree'], standardSubtree=formal['standardSubtree'],
                    targetKey=target_key(formal['source']),
                    dataRoles=dict(source='newQaApplication', formalArmBuild='immutableFormalArmBuild',
                                   versionCode='formalBuildInfoOnly'),
                    targetPlatform='android-x64', appEnv=formal['appEnv'], readbackBuildTools=READBACK_BUILD_TOOLS)
    if any(info.get(k) != v or type(info.get(k)) is not type(v) for k, v in expected.items()):
        raise ValueError('QA/source/formal ARM build identity mismatch')
    run = info.get('buildRun', '')
    if (type(run) is not str or not RUN_ID.fullmatch(run) or run == str(formal['runId'])
            or run in {old['buildRun'] for old in HISTORICAL_ARM}
            or info.get('buildWorkflow') != run_url(run)
            or type(info.get('harnessSource')) is not str or not SHA1.fullmatch(info['harnessSource'])
            or info.get('harnessRef') != HARNESS_REF
            or info.get('buildAttempt') != 1 or type(info.get('buildAttempt')) is not int
            or type(info.get('buildRunNumber')) is not int or info['buildRunNumber'] < 1):
        raise ValueError('New QA build must retain its own first-attempt run and harness identity')
    actual = dict(abi='x86_64', package=PACKAGE, versionName=formal['versionName'],
                  versionCode=formal['versionCode'], signerSha256=SIGNER)
    if info.get('actual') != actual or info.get('apk', {}).get('file') != APK_NAME:
        raise ValueError('Actual QA APK identity differs from the formal BUILD_INFO target')
    digest = info.get('apk', {}).get('sha256', '')
    if (type(digest) is not str or not SHA256.fullmatch(digest) or digest == formal['apkSha256']
            or digest in {old['apkSha256'] for old in HISTORICAL_ARM}):
        raise ValueError('QA APK cannot be a formal or historical ARM artifact')
    return info


def validate_elf(name, abi, data):
    elf_class, machine = NATIVE_ABIS[abi]
    header = 64 if elf_class == 2 else 52
    if (len(data) < header or data[:7] != b'\x7fELF' + bytes((elf_class, 1, 1))
            or struct.unpack_from('<H', data, 18)[0] != machine or struct.unpack_from('<I', data, 20)[0] != 1
            or struct.unpack_from('<H', data, header - 12)[0] != header):
        raise ValueError(f'Native library is not a little-endian ELF for {abi}: {name}')


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
                  stdoutSaved=False, stderrSaved=False, diagnosticWriteErrors=[], startedAt=utc_now(),
                  startedMonotonic=time.monotonic())
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
        record.update(endedAt=utc_now(), endedMonotonic=time.monotonic())
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


def api_reader(directory, area='actions/'):
    prefix = f'repos/{REPOSITORY}/{area}'
    def read(label, endpoint, field=None):
        data = json.loads(inspection_command(['gh', 'api', '--paginate', '--slurp', prefix + endpoint], directory, label))
        if field is None:
            if len(data) != 1:
                raise ValueError('Unexpected paginated single-object API result')
            return data[0]
        items = [item for page in data for item in page[field]]
        if not data or any(type(page.get('total_count')) is not int or page['total_count'] != len(items) for page in data):
            raise ValueError('Paginated API list is incomplete or inconsistent: ' + endpoint)
        return items
    return read


def signer_and_badging(apk, tools, directory):
    directory.mkdir(parents=True, exist_ok=True)
    def inspect(label, args):
        return inspection_command([str(tools / label), *args, str(apk)], directory, label, suffix='.txt').decode(errors='replace')
    signature = inspect('apksigner', ['verify', '--verbose', '--print-certs'])
    if re.findall(r'^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-f]+)$', signature, re.M) != [SIGNER]:
        raise ValueError('Signing certificate differs from the persistent original test certificate')
    return inspect('aapt', ['dump', 'badging'])


def pinned_build_tools(path):
    if path.name != READBACK_BUILD_TOOLS:
        raise ValueError('APK readback must use the pinned build-tools ' + READBACK_BUILD_TOOLS)
    return path


def badging_matches(badging, version_name, version_code):
    return bool(re.search(r"^package: name='" + re.escape(PACKAGE) + r"' versionCode='" + re.escape(version_code)
                          + r"' versionName='" + re.escape(version_name) + r"'", badging, re.M))


def native_code(badging):
    lines = re.findall(r'^((?:alt-)?native-code):(.*)$', badging, re.M)
    if len(lines) != 1 or lines[0][0] != 'native-code' or not re.fullmatch(r"(?: '[^']+')+", lines[0][1]):
        raise ValueError('aapt badging must report exactly one native-code line')
    return re.findall(r"'([^']+)'", lines[0][1])


def native_libraries(apk, abi, badging):
    libraries = []
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or archive.testzip() is not None:
            raise ValueError('APK duplicate entries or CRC failure')
        for name in names:
            if not name.startswith('lib/'):
                continue
            parts = name.split('/')
            if len(parts) != 3 or parts[1] not in NATIVE_ABIS or not re.fullmatch(r'lib[\w.+-]+\.so', parts[2]):
                raise ValueError('Unexpected native library entry: ' + name)
            data = archive.read(name)
            validate_elf(name, parts[1], data)
            libraries.append(dict(abi=parts[1], path=name, bytes=len(data), sha256=hashlib.sha256(data).hexdigest()))
    present = {}
    for row in libraries:
        present.setdefault(row['abi'], set()).add(row['path'].rpartition('/')[2])
    own = sorted(row['path'] for row in libraries if row['path'].rpartition('/')[2] in OWN_LIBRARIES)
    if own != [f'lib/{abi}/{name}' for name in OWN_LIBRARIES]:
        raise ValueError(f'Flutter/AOT/Go/Rust libraries must exist once and only for {abi}; no hooks may be disabled')
    if any(not names <= present[abi] for other, names in present.items() if other != abi):
        raise ValueError(f'A third-party native library is packaged for another ABI but not for {abi}')
    if native_code(badging) != sorted(present):
        raise ValueError('aapt native-code differs from the APK native library directories')
    return libraries


def inspect_apk(apk, tools, directory, formal):
    badging = signer_and_badging(apk, tools, directory)
    if not badging_matches(badging, formal['versionName'], formal['versionCode']):
        raise ValueError('QA package/version badging mismatch')
    libraries = native_libraries(apk, 'x86_64', badging)
    return dict(abi='x86_64', package=PACKAGE, versionName=formal['versionName'],
                versionCode=formal['versionCode'], signerSha256=SIGNER), libraries


def git_text(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True)


def git_value(root, *args):
    return git_text(root, *args).strip()


def check_submodules(root):
    gitlinks = {}
    for entry in git_text(root, 'ls-tree', '-r', '-z', 'HEAD').split('\0'):
        meta, _, path = entry.partition('\t')
        if meta.startswith('160000 '):
            gitlinks[path] = meta.split()[2]
    reported = {}
    for line in git_text(root, 'submodule', 'status', '--recursive').splitlines():
        found = SUBMODULE_STATUS.fullmatch(line)
        if not found or found[1] != ' ' or found[3] in reported:
            raise ValueError('Application submodule is uninitialised, drifted or conflicted: ' + line)
        reported[found[3]] = found[2]
    if any(reported.get(path) != commit for path, commit in gitlinks.items()):
        raise ValueError('Application submodules do not match the formal source gitlinks: ' + json.dumps(gitlinks))


def check_source(root, formal):
    for args, expected, label in [
            (['rev-parse', 'HEAD'], formal['source'], 'source'),
            (['rev-parse', 'HEAD^{tree}'], formal['sourceTree'], 'tree'),
            (['rev-parse', 'HEAD:apps/aerove'], formal['standardSubtree'], 'Standard subtree'),
            (['diff', '--name-only', 'HEAD'], '', 'tracked content'),
            (['status', '--porcelain', '--untracked-files=all', '--ignore-submodules=none'], '', 'untracked or unstaged entries')]:
        actual = git_value(root, *args)
        if actual != expected:
            raise ValueError(f'Application checkout differs from the formal source ({label}): {actual}')
    check_submodules(root)


def validate_formal_run(run, run_id):
    if (run.get('id') != run_id or run.get('path') != FORMAL_WORKFLOW or run.get('name') != FORMAL_WORKFLOW_NAME
            or run.get('event') not in ('workflow_dispatch', 'push') or run.get('status') != 'completed'
            or run.get('conclusion') != 'success' or not positive(run.get('run_number'))
            or not positive(run.get('run_attempt')) or type(run.get('head_sha')) is not str
            or not SHA1.fullmatch(run['head_sha']) or type(run.get('head_branch')) is not str or not run['head_branch']
            or (run.get('repository') or {}).get('full_name') != REPOSITORY
            or (run.get('head_repository') or {}).get('full_name') != REPOSITORY):
        raise ValueError('Formal run is not a completed successful Aerove Android build of this repository')
    return dict(runId=run_id, runNumber=run['run_number'], runAttempt=run['run_attempt'], event=run['event'],
                headBranch=run['head_branch'], source=run['head_sha'])


def requested_run(text):
    if not isinstance(text, str) or not RUN_ID.fullmatch(text):
        raise ValueError('formal_run_id must be a positive decimal workflow run id')
    if text == os.environ.get('GITHUB_RUN_ID'):
        raise ValueError('The QA run cannot be its own formal build')
    return int(text)


def formal_run(args):
    run_id = requested_run(args.run_id)
    if os.environ.get('GITHUB_REPOSITORY') != REPOSITORY:
        raise ValueError('QA runs only in the original repository')
    directory = args.directory / 'formal'
    directory.mkdir(parents=True, exist_ok=False)
    identity = validate_formal_run(api_reader(directory)('formal-run', f'runs/{run_id}'), run_id)
    save(directory / 'FORMAL_RUN.json', identity)
    output(dict(source=identity['source'], run_number=identity['runNumber']))
    return 0


def run_target(title, current_formal, source, resolve):
    if not title.startswith(RUN_TITLE) or not RUN_ID.fullmatch(title[len(RUN_TITLE):]):
        return True
    formal = int(title[len(RUN_TITLE):])
    return formal == current_formal or resolve(formal) in (None, source)


def registration_run(run):
    head = run.get('head_sha')
    return (run.get('event') == 'push' and type(head) is str and SHA1.fullmatch(head) is not None
            and run.get('display_title') == REGISTRATION_TITLE + head)


def build_guard_decision(current, runs, histories, registrations, artifacts, source):
    blockers = []
    registered = []
    if artifacts:
        blockers.append(dict(reason='QA_ARTIFACT_EXISTS_REUSE_ONLY', artifacts=artifacts))
    for run in runs:
        if run['id'] in registrations:
            jobs = registrations[run['id']]
            if any(job.get('conclusion') != 'skipped' for job in jobs):
                blockers.append(dict(run=run['id'], reason='REGISTRATION_RUN_EXECUTED_A_JOB_REVIEW_REQUIRED'))
            registered.append(dict(run=run['id'], title=run['display_title'], event=run['event'],
                                   status=run.get('status'), conclusion=run.get('conclusion'), jobs=len(jobs)))
            continue
        if run['id'] not in histories:
            continue
        if run['status'] != 'completed':
            blockers.append(dict(run=run['id'], reason='PRIOR_RUN_ACTIVE_REVIEW_REQUIRED'))
        build_jobs = [job for job in histories[run['id']] if job['name'] == 'build_internal_qa']
        if not build_jobs:
            blockers.append(dict(run=run['id'], reason='BUILD_HISTORY_MISSING_REVIEW_REQUIRED'))
        for job in build_jobs:
            compile_steps = [step for step in job.get('steps', []) if step['name'] == COMPILE_STEP]
            if not compile_steps and job.get('conclusion') != 'skipped':
                blockers.append(dict(run=run['id'], reason='COMPILE_HISTORY_MISSING_REVIEW_REQUIRED'))
            for step in compile_steps:
                if step.get('conclusion') != 'skipped' and step.get('status') != 'queued':
                    blockers.append(dict(run=run['id'], reason='COMPILE_STEP_ENTERED_REVIEW_OR_REUSE_ONLY',
                                         status=step.get('status'), conclusion=step.get('conclusion')))
    return dict(diagnosticOnly=True, accepted=False, allowed=not blockers, blockers=blockers, registrationRuns=registered,
                sameTargetRuns=sorted(histories), currentRunExcluded=current['id'], applicationSource=source,
                targetKey=target_key(source), targetPlatform='android-x64')


def guard(args):
    if os.environ.get('GITHUB_RUN_ATTEMPT') != '1':
        raise ValueError('Do not rerun the build job; retry a proven precompile failure only through a new dispatch')
    if os.environ.get('GITHUB_REF') != HARNESS_REF:
        raise ValueError('The internal QA build runs only from the locked harness branch')
    run_id = requested_run(args.run_id)
    source = args.source
    if type(source) is not str or not SHA1.fullmatch(source):
        raise ValueError('Guard requires the resolved formal source')
    directory = args.directory / 'once-guard'
    directory.mkdir(parents=True, exist_ok=False)
    read = api_reader(directory)
    current = read('current-run', 'runs/' + os.environ['GITHUB_RUN_ID'])
    if (current.get('path') != WORKFLOW_PATH or current.get('event') != 'workflow_dispatch'
            or current.get('head_branch') != HARNESS_BRANCH
            or current.get('display_title') != RUN_TITLE + str(run_id) or current.get('run_attempt') != 1):
        raise ValueError('Current QA run identity does not match its dispatched formal run')
    runs = read('workflow-runs', f'workflows/{current["workflow_id"]}/runs?per_page=100', 'workflow_runs')
    artifacts = read('qa-artifacts', 'artifacts?name=' + artifact_name(source) + '&per_page=100', 'artifacts')
    sources = {run_id: source}
    def resolve(formal):
        if formal not in sources:
            try:
                sources[formal] = read(f'formal-{formal}', f'runs/{formal}').get('head_sha')
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ValueError):
                sources[formal] = None
        return sources[formal]
    def jobs_of(run):
        return read(f'jobs-{run["id"]}', f'runs/{run["id"]}/jobs?filter=all&per_page=100', 'jobs')
    histories = {}
    registrations = {}
    for run in runs:
        if run['id'] == current['id'] or run['run_number'] > current['run_number']:
            continue
        if registration_run(run):
            registrations[run['id']] = jobs_of(run)
        elif run_target(run.get('display_title') or '', run_id, source, resolve):
            histories[run['id']] = jobs_of(run)
    result = build_guard_decision(current, runs, histories, registrations, artifacts, source)
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


def check_pins(text, pins, label):
    lines = text.splitlines()
    missing = [pin for pin in pins if pin not in lines]
    if missing or sum(line.lstrip().startswith('flutter build apk ') for line in lines) != 1:
        raise ValueError(f'{label} toolchain or build command differs from the pinned QA contract: {missing}')


def read_reuse(path):
    values = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        key, separator, value = line.partition('=')
        if not separator or key in values:
            raise ValueError('Invalid artifact selection output')
        values[key] = value
    if set(values) != {'run_id', 'run_number', 'artifact_id', 'generated_artifact_id'} or not all(
            RUN_ID.fullmatch(value) for value in values.values()):
        raise ValueError('The promotion finder did not select one complete original build')
    return {key: int(value) for key, value in values.items()}


def formal_artifacts(args):
    run = load(args.directory / 'formal/FORMAL_RUN.json')
    selected = read_reuse(args.directory / 'formal/reuse.env')
    if selected['run_id'] != run['runId'] or selected['run_number'] != run['runNumber']:
        raise ValueError('Main promotion would reuse a different original build than the dispatched formal run')
    output(selected)
    return 0


def formal(args):
    tools = pinned_build_tools(args.build_tools)
    run = load(args.directory / 'formal/FORMAL_RUN.json')
    selected = read_reuse(args.directory / 'formal/reuse.env')
    if selected['run_id'] != run['runId'] or selected['run_number'] != run['runNumber']:
        raise ValueError('Main promotion would reuse a different original build than the dispatched formal run')
    root = args.source_root
    tree = git_value(root, 'rev-parse', 'HEAD^{tree}')
    subtree = git_value(root, 'rev-parse', 'HEAD:apps/aerove')
    check_source(root, dict(source=run['source'], sourceTree=tree, standardSubtree=subtree))
    generated = args.directory / 'formal/generated'
    if ((generated / 'xclash-generated.patch').stat().st_size != 0
            or (generated / 'xclash-verified-tree.txt').read_text(encoding='ascii').strip() != tree):
        raise ValueError('Formal build did not verify this exact committed tree')
    if git_value(root, 'rev-parse', 'HEAD:.github/actions/flutter') != git_value(args.harness, 'rev-parse', 'HEAD:.github/actions/flutter'):
        raise ValueError('Harness Flutter installer differs from the formal source installer')
    check_pins((root / FORMAL_WORKFLOW).read_text(encoding='utf-8'), FORMAL_PINS, 'Formal')
    check_pins((args.harness / WORKFLOW_PATH).read_text(encoding='utf-8'), HARNESS_PINS, 'Harness')
    apk_directory = args.directory / 'formal/apk'
    info = load(apk_directory / 'BUILD_INFO.json')
    name, separator, code = info.get('version', '').rpartition('+')
    sys.path.insert(0, str(root / 'apps/aerove/tool'))
    import aerove_version
    plan = aerove_version.resolve_product_version(
        (root / 'apps/aerove/pubspec.yaml').read_text(encoding='utf-8'), run['runNumber'])
    if (not separator or info.get('source') != run['source'] or info.get('workflow') != run_url(run['runId'])
            or info.get('applicationId') != PACKAGE or info.get('appEnv') != plan['app_env']
            or name != plan['name'] or code != str(plan['number'])):
        raise ValueError('Formal BUILD_INFO does not derive from this formal run number and source')
    apk = apk_directory / FORMAL_APK
    badging = signer_and_badging(apk, tools, args.directory / 'formal/readback')
    if not badging_matches(badging, name, code):
        raise ValueError('Formal ARM APK badging differs from its BUILD_INFO')
    native_libraries(apk, 'arm64-v8a', badging)
    identity = validate_formal(dict(
        schema=1, role='immutableFormalArmBuild', repository=REPOSITORY, workflowPath=FORMAL_WORKFLOW,
        runId=run['runId'], runNumber=run['runNumber'], runAttempt=run['runAttempt'], event=run['event'],
        headBranch=run['headBranch'], workflow=run_url(run['runId']), source=run['source'], sourceTree=tree,
        standardSubtree=subtree, artifactId=selected['artifact_id'],
        generatedArtifactId=selected['generated_artifact_id'], buildInfoSha256=sha(apk_directory / 'BUILD_INFO.json'),
        apkSha256=sha(apk), apkBytes=apk.stat().st_size, version=info['version'], versionName=name, versionCode=code,
        appEnv=info['appEnv'], package=PACKAGE, abi='arm64-v8a', signerSha256=SIGNER))
    save(args.directory / 'FORMAL_BUILD.json', identity)
    save(args.directory / 'QA_BUILD_PENDING.json', dict(
        diagnosticOnly=True, accepted=False, mainPromotionAllowed=False, source=identity['source'],
        buildRun=os.environ['GITHUB_RUN_ID'], buildRunNumber=os.environ['GITHUB_RUN_NUMBER'],
        versionName=name, versionCode=code, formalRun=identity['runId'], formalRunNumber=identity['runNumber'],
        targetPlatform='android-x64', actualReadback=False))
    output(dict(version_name=name, version_code=code, app_env=identity['appEnv']))
    return 0


def source_check(args):
    check_source(args.source_root, validate_formal(load(args.directory / 'FORMAL_BUILD.json')))
    return 0


def verify(args):
    tools = pinned_build_tools(args.build_tools)
    apk = args.directory / APK_NAME
    if apk.is_symlink() or not apk.is_file():
        raise ValueError('Missing plain QA APK')
    readback = (args.evidence if args.evidence else args.directory) / 'apk-readback'
    if args.create:
        formal_identity = validate_formal(load(args.directory / 'FORMAL_BUILD.json'))
        check_source(args.source_root, formal_identity)
        actual, libraries = inspect_apk(apk, tools, readback, formal_identity)
        info = base_identity(formal_identity, os.environ['GITHUB_RUN_ID'], os.environ['GITHUB_RUN_ATTEMPT'],
                             os.environ['GITHUB_SHA'], os.environ['GITHUB_RUN_NUMBER'], os.environ['GITHUB_REF'])
        info.update(actual=actual, nativeLibraries=libraries,
                    apk=dict(file=APK_NAME, sha256=sha(apk), bytes=apk.stat().st_size),
                    toolchain=TOOLCHAIN, readbackBuildTools=tools.name,
                    applicationSourceCheck=dict(source=formal_identity['source'], tree=formal_identity['sourceTree'],
                                                standardSubtree=formal_identity['standardSubtree'],
                                                trackedContentUnchanged=True, checkoutClean=True,
                                                submodulesAtGitlinks=True),
                    toolchainEvidence={p.name: sha(p) for p in (args.directory / 'toolchain').iterdir() if p.is_file()},
                    buildLogSha256=sha(args.directory / 'build.log'),
                    command='flutter build apk --no-pub --release --target-platform android-x64 '
                            f'--build-name {formal_identity["versionName"]} --build-number {formal_identity["versionCode"]} '
                            f'--dart-define=APP_ENV={formal_identity["appEnv"]}')
        validate_identity(info)
        save(args.directory / 'QA_BUILD_INFO.json', info)
        names = [APK_NAME, 'QA_BUILD_INFO.json']
        (args.directory / 'QA_SHA256SUMS').write_text(''.join(f'{sha(args.directory / n)}  {n}\n' for n in names))
    else:
        info = validate_identity(load(args.directory / 'QA_BUILD_INFO.json'))
        actual, libraries = inspect_apk(apk, tools, readback, info['formalArmBuild'])
        if info['apk'] != dict(file=APK_NAME, sha256=sha(apk), bytes=apk.stat().st_size) or info['actual'] != actual or info['nativeLibraries'] != libraries:
            raise ValueError('Saved QA receipt differs from actual APK readback')
        expected = ''.join(f'{sha(args.directory / n)}  {n}\n' for n in [APK_NAME, 'QA_BUILD_INFO.json'])
        if (args.directory / 'QA_SHA256SUMS').read_text() != expected:
            raise ValueError('QA checksum sidecar mismatch')
    return info


def requested_id(text, label):
    if not isinstance(text, str) or not RUN_ID.fullmatch(text):
        raise ValueError(label + ' must be a positive decimal id')
    return int(text)


def compile_entries(jobs):
    return {(job.get('runner_id'), step.get('started_at'), step.get('completed_at')) for job in jobs
            for step in job.get('steps', []) if step.get('name') == COMPILE_STEP
            and step.get('conclusion') != 'skipped' and step.get('status') != 'queued'}


def package_run(read, formal_id, qa_run, artifact_id):
    formal = validate_formal_run(read('formal-run', f'runs/{formal_id}'), formal_id)
    build = read('qa-run', f'runs/{qa_run}')
    if (build.get('id') != qa_run or build.get('path') != WORKFLOW_PATH or build.get('event') != 'workflow_dispatch'
            or build.get('head_branch') != HARNESS_BRANCH or build.get('display_title') != RUN_TITLE + str(formal_id)
            or build.get('status') != 'completed' or not positive(build.get('run_number'))
            or (build.get('repository') or {}).get('full_name') != REPOSITORY
            or (build.get('head_repository') or {}).get('full_name') != REPOSITORY
            or type(build.get('head_sha')) is not str or not SHA1.fullmatch(build['head_sha'])):
        raise ValueError('QA package run is not a completed internal x86 QA build of the dispatched formal run')
    jobs = read('qa-run-jobs', f'runs/{qa_run}/jobs?filter=all&per_page=100', 'jobs')
    builds = [job for job in jobs if job.get('name') == 'build_internal_qa']
    first = [job for job in builds if job.get('run_attempt') == 1]
    if (len(first) != 1 or first[0].get('conclusion') != 'success' or first[0].get('run_id') != qa_run
            or first[0].get('head_sha') != build['head_sha']
            or any([step.get('conclusion') for step in first[0].get('steps', []) if step.get('name') == name] != ['success']
                   for name in BUILD_STEPS)
            or len(compile_entries(first)) != 1 or compile_entries(builds) != compile_entries(first)):
        raise ValueError('QA package must come from one first-attempt build that compiled and read back exactly once')
    named = read('qa-artifacts', 'artifacts?name=' + artifact_name(formal['source']) + '&per_page=100', 'artifacts')
    meta = read('qa-artifact', f'artifacts/{artifact_id}')
    origin = meta.get('workflow_run') or {}
    digest = meta.get('digest')
    if ([item.get('id') for item in named] != [artifact_id] or meta.get('id') != artifact_id
            or meta.get('name') != artifact_name(formal['source']) or meta.get('expired') is not False
            or origin.get('id') != qa_run or origin.get('head_sha') != build['head_sha']
            or origin.get('head_branch') != HARNESS_BRANCH or not positive(meta.get('size_in_bytes'))
            or type(digest) is not str or not re.fullmatch('sha256:[0-9a-f]{64}', digest)):
        raise ValueError('QA artifact is not the single unexpired package of this target and QA run')
    return formal, build, first[0], meta


def bind_package(info, formal, build, read, git):
    bound = info['formalArmBuild']
    if (info['buildRun'] != str(build['id']) or info['buildAttempt'] != 1
            or info['buildRunNumber'] != build['run_number'] or info['harnessSource'] != build['head_sha']
            or any(bound[key] != formal[key] for key in ('runId', 'runNumber', 'runAttempt', 'event', 'headBranch', 'source'))):
        raise ValueError('Reused QA_BUILD_INFO does not bind to the verified QA run and formal run')
    for key, name in FORMAL_ARTIFACTS.items():
        meta = read('formal-' + name, f'artifacts/{bound[key]}')
        origin = meta.get('workflow_run') or {}
        if (meta.get('id') != bound[key] or meta.get('name') != name or origin.get('id') != formal['runId']
                or origin.get('head_sha') != formal['source']):
            raise ValueError('Reused QA_BUILD_INFO names a formal artifact outside the formal run')
    commit = git('source-commit', 'commits/' + formal['source'])
    if commit.get('sha') != formal['source'] or (commit.get('tree') or {}).get('sha') != bound['sourceTree']:
        raise ValueError('Reused QA_BUILD_INFO source tree differs from the formal commit')
    def child(tree, name):
        rows = [row.get('sha') for row in tree.get('tree', []) if row.get('path') == name and row.get('type') == 'tree']
        return rows[0] if len(rows) == 1 and tree.get('truncated') is False else None
    apps = child(git('source-tree', 'trees/' + bound['sourceTree']), 'apps')
    if apps is None or child(git('apps-tree', 'trees/' + apps), 'aerove') != bound['standardSubtree']:
        raise ValueError('Reused QA_BUILD_INFO Standard subtree differs from the formal commit')


def download_artifact_zip(artifact_id, destination, attempts):
    for attempt in range(1, 4):
        destination.unlink(missing_ok=True)
        row = dict(attempt=attempt, startedAt=utc_now(), result=None)
        attempts.append(row)
        try:
            with destination.open('xb') as stream:
                completed = subprocess.run(['gh', 'api', f'repos/{REPOSITORY}/actions/artifacts/{artifact_id}/zip'],
                                           stdout=stream, stderr=subprocess.PIPE, timeout=180)
            row.update(result=completed.returncode, bytes=destination.stat().st_size,
                       stderr=re.sub(r'https?://\S+', '<url redacted>', completed.stderr[:4096].decode(errors='replace')))
        except subprocess.TimeoutExpired:
            row['timeout'] = True
        row['endedAt'] = utc_now()
        if row['result'] == 0:
            return
    destination.unlink(missing_ok=True)
    raise ValueError('QA artifact download failed in every bounded attempt')


def extract_package(archive, directory):
    with zipfile.ZipFile(archive) as source:
        rows = source.infolist()
        names = [row.filename for row in rows]
        if (sorted(names) != sorted(PACKAGE_FILES) or source.testzip() is not None
                or any(row.is_dir() or (row.external_attr >> 16) & 0o170000 == 0o120000 for row in rows)):
            raise ValueError('Reused QA artifact must hold exactly the APK, QA_BUILD_INFO.json and QA_SHA256SUMS')
        directory.mkdir(parents=True, exist_ok=False)
        for name in names:
            (directory / name).write_bytes(source.read(name))


def reuse_package(args):
    if os.environ.get('GITHUB_RUN_ATTEMPT') != '1':
        raise ValueError('Do not rerun a package probe; dispatch a new probe instead')
    controller = HOST_OBSERVER_BRANCH if host_observer_enabled(args) else HARNESS_BRANCH
    if os.environ.get('GITHUB_REF') != 'refs/heads/' + controller:
        raise ValueError('The QA package probe runs only from the locked harness branch')
    formal_id = requested_run(args.run_id)
    qa_run = requested_id(args.qa_run_id, 'qa_run_id')
    artifact_id = requested_id(args.qa_artifact_id, 'qa_artifact_id')
    if controller == HOST_OBSERVER_BRANCH and (formal_id, qa_run, artifact_id) != (40531629533, 40536314613, 12747833792):
        raise ValueError('Host observer is fixed to formal58/QA5 and its original artifact')
    if str(qa_run) == os.environ.get('GITHUB_RUN_ID') or qa_run == formal_id:
        raise ValueError('The QA package must come from an earlier internal QA run, not the probe or formal run')
    args.evidence.mkdir(parents=True, exist_ok=False)
    read = api_reader(args.evidence)
    current = read('current-run', 'runs/' + os.environ['GITHUB_RUN_ID'])
    if (current.get('path') != PROBE_WORKFLOW_PATH or current.get('event') != 'workflow_dispatch'
            or current.get('head_branch') != controller or current.get('run_attempt') != 1
            or current.get('display_title') != PROBE_TITLE.format(artifact=artifact_id, run=qa_run, formal=formal_id)):
        raise ValueError('Current probe run identity does not match its dispatched QA package')
    formal, build, job, meta = package_run(read, formal_id, qa_run, artifact_id)
    archive = args.directory.with_name(args.directory.name + '.zip')
    downloads = []
    try:
        download_artifact_zip(artifact_id, archive, downloads)
    finally:
        save(args.evidence / 'QA_PACKAGE_DOWNLOAD.json', downloads)
    if 'sha256:' + sha(archive) != meta['digest']:
        raise ValueError('Downloaded QA artifact differs from its API digest')
    extract_package(archive, args.directory)
    info = verify(args)
    bind_package(info, formal, build, read, api_reader(args.evidence, 'git/'))
    save(args.evidence / 'QA_PACKAGE_REUSE.json', dict(
        schema=1, kind='standard-internal-x86-qa-package-reuse', diagnosticOnly=True, accepted=False, compiled=False,
        packageMode='reuse', packageFromEarlierRun=True, source=info['source'], sourceTree=info['sourceTree'],
        standardSubtree=info['standardSubtree'], targetKey=info['targetKey'], formalRun=formal_id,
        formalRunNumber=formal['runNumber'], packageRun=str(qa_run), packageRunNumber=build['run_number'],
        packageRunAttempt=1, packageRunLatestAttempt=build.get('run_attempt'), packageBuildJob=job.get('id'),
        packageHarnessSource=build['head_sha'],
        packageArtifact=dict(id=artifact_id, name=meta['name'], digest=meta['digest'], sizeInBytes=meta['size_in_bytes'],
                             createdAt=meta.get('created_at'), expiresAt=meta.get('expires_at')),
        apk=info['apk'], versionName=info['actual']['versionName'], versionCode=info['actual']['versionCode'],
        formalBytesReverified=False, probeRun=os.environ['GITHUB_RUN_ID'], probeRunAttempt=1,
        probeHarnessSource=os.environ['GITHUB_SHA'], probeHarnessRef=os.environ['GITHUB_REF']))
    return 0


def package_origin(qa, receipt_path):
    run = os.environ['GITHUB_RUN_ID']
    artifact = os.environ.get('QA_ARTIFACT_ID', '')
    if not RUN_ID.fullmatch(artifact):
        raise ValueError('QA_ARTIFACT_ID must name the probed QA package')
    if receipt_path is None:
        if qa['buildRun'] != run:
            raise ValueError('A same-run first-screen probe must use the QA package built by this run')
        return dict(packageMode='same-run', packageFromEarlierRun=False, packageRun=run, qaArtifactId=artifact)
    reuse = load(receipt_path)
    if (reuse.get('kind') != 'standard-internal-x86-qa-package-reuse' or reuse.get('compiled') is not False
            or reuse.get('accepted') is not False or reuse.get('packageMode') != 'reuse'
            or reuse.get('packageFromEarlierRun') is not True or reuse.get('packageRun') != qa['buildRun']
            or qa['buildRun'] == run or reuse.get('probeRun') != run
            or reuse.get('formalRun') != qa['formalArmBuild']['runId'] or reuse.get('apk') != qa['apk']
            or reuse.get('source') != qa['source'] or reuse.get('packageHarnessSource') != qa['harnessSource']
            or str((reuse.get('packageArtifact') or {}).get('id')) != artifact):
        raise ValueError('Reused QA package receipt does not match this probe run and QA_BUILD_INFO')
    return dict(packageMode='reuse', packageFromEarlierRun=True, packageRun=qa['buildRun'],
                packageHarnessSource=qa['harnessSource'], packageArtifact=reuse['packageArtifact'],
                qaArtifactId=artifact, packageReceiptSha256=sha(receipt_path))


def host_renderer(output):
    def field(name):
        return [value.strip() for value in re.findall(r'\| ' + name + r' (.+)$', output, re.M)]
    modes = re.findall(r'emuglConfig_init: vulkan_mode_selected:(\S+) gles_mode_selected:(\S+)', output)
    adapter, version = field('Graphics Adapter(?! Vendor)'), field('Graphics API Version')
    if (len(modes) != 1 or modes[0][1] != GPU_BACKEND or len(adapter) != 1 or len(version) != 1
            or 'SWIFTSHADER' not in adapter[0].upper() or not all('ANGLE' in text.upper() for text in adapter + version)):
        raise ValueError(f'Requested {GPU_BACKEND} renderer was not confirmed in actual emulator startup output')
    return dict(requestedGpu=GPU_BACKEND, vulkanModeSelected=modes[0][0], glesModeSelected=modes[0][1],
                adapterVendor=field('Graphics Adapter Vendor'), adapter=adapter[0], apiVersion=version[0],
                lines=[line for line in output.splitlines() if re.search(
                    r'emuglConfig_init|Graphics backend|Graphics Adapter|Graphics API Version|Selecting Vulkan device|ICD set', line)])


def app_renderer(log, pid):
    lines = [line for line in log.splitlines() if pid and re.match(r'\S+\s+\S+\s+' + re.escape(pid) + r'\s', line)
             and ('rendering backend' in line or 'Impeller' in line)]
    found = sorted({label for text, label in APP_RENDERERS if any(text in line for line in lines)})
    return dict(backend=found[0] if len(found) == 1 else 'conflicting' if found else 'not-reported', lines=lines[:20])


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


def live_app_failure(log):
    """QA app crash lines: native signals, tombstone headers, Java and Flutter fatals, and deaths, with line numbers."""
    name = re.escape(PACKAGE) + r'(?::[\w.]+)?'
    pids = set(re.findall(r'Start proc ([0-9]+):' + name + '/', log))
    lines = log.splitlines()
    found = []
    for number, line in enumerate(lines, 1):
        native = re.search(r'Fatal signal [0-9]+ .*\bpid ([0-9]+) \(([^)]*)\)', line)
        writer = re.match(r'\S+\s+\S+\s+([0-9]+)\s', line)
        # Android names the main thread after the last 15 characters of a long dotted process name.
        if ((native and (native.group(1) in pids or native.group(2) in (PACKAGE, PACKAGE[-15:])))
                or re.search('>>> ' + name + ' <<<', line)
                or re.search('Process ' + name + r' \(pid [0-9]+\) has died', line)
                or ('FATAL EXCEPTION' in line and re.search('Process: ' + name + ', PID: [0-9]+',
                                                            '\n'.join(lines[number - 1:number + 7])))
                or (writer and writer.group(1) in pids and '[FATAL:flutter/' in line)):
            found.append((number, line))
    return found


def run_probe(args):
    args.evidence.mkdir(parents=True, exist_ok=False)
    renderer = args.app_renderer
    path = args.evidence / 'FIRST-SCREEN.json'
    receipt = dict(diagnosticOnly=True, accepted=False, status='STARTED', commands=[], apkModified=False,
                   vpnRequested=False, demoInjected=False, twelveThemeCaptures=False,
                   appRendererRequested=renderer, productionRenderer=False)
    adb = ['adb', '-s', 'emulator-5554']
    installed = False
    cancelled = []
    def interrupt(signum, frame):
        cancelled.append(signum)
        raise SystemExit(128 + signum)
    previous = {s: signal.signal(s, interrupt) for s in (signal.SIGTERM, signal.SIGINT)}
    def command(label, values, timeout=25, check=True):
        return inspection_command(values, args.evidence, label, timeout, check, receipt)
    def checkpoint():
        try:
            save_atomic(path, dict(receipt, status='IN_PROGRESS', observedStatus=receipt['status'], provisional=True))
        except Exception as error:
            receipt.setdefault('checkpointErrors', []).append(repr(error))
    def device_state(label):
        try:
            return command(label, adb + ['get-state'], timeout=5, check=False).decode(errors='replace').strip()
        except Exception as error:
            return 'unreachable: ' + type(error).__name__
    def diagnostic(label, values):
        try:
            return command(label, adb + values, check=False).decode(errors='replace')
        except Exception as error:
            receipt.setdefault('diagnosticErrors', []).append(dict(phase=label, error=repr(error)))
            return ''
    def device_lost(during, state):
        receipt.setdefault('deviceLost', dict(during=during, state=state, observedAt=utc_now(),
                                              observedMonotonic=time.monotonic()))
        remember_failure(receipt, 'device-lost', RuntimeError(f'adb lost emulator-5554 {during}: {state or "no device"}'))
    def app_failed(source, pid, failures):
        receipt.setdefault('appFailure', dict(source=source, firstActivityPid=pid, lines=failures[:20],
                                              recordedAt=utc_now(), recordedMonotonic=time.monotonic()))
    try:
        try:
            checkpoint()
            receipt['qaBuild'] = verify(args)
            receipt['package'] = package_origin(receipt['qaBuild'], args.package_receipt)
            version_code = receipt['qaBuild']['actual']['versionCode']
            props = {p: command('prop-' + p, adb + ['shell', 'getprop', p]).decode().strip()
                     for p in ['sys.boot_completed', 'ro.build.version.sdk', 'ro.product.cpu.abilist64',
                               'ro.build.fingerprint', 'ro.dalvik.vm.native.bridge']}
            receipt['properties'] = props
            if props['sys.boot_completed'] != '1' or props['ro.build.version.sdk'] != '33' or 'x86_64' not in props['ro.product.cpu.abilist64'].split(','):
                raise ValueError('Expected booted API33 x86_64 emulator')
            if command('existing-package', adb + ['shell', 'pm', 'path', PACKAGE], check=False).strip():
                raise ValueError('Refusing to replace existing app/data')
            diagnostic('guest-properties', ['shell', 'getprop'])
            diagnostic('guest-features', ['shell', 'pm', 'list', 'features'])
            receipt['guestRenderer'] = re.findall(r'^GLES: (.+)$', diagnostic(
                'guest-surfaceflinger', ['shell', 'dumpsys', 'SurfaceFlinger']), re.M)
            command('prelaunch-logcat', adb + ['logcat', '-d', '-v', 'threadtime'])
            command('clear-fresh-log', adb + ['logcat', '-c'])
            command('install-x86-qa', adb + ['install', '--abi', 'x86_64', args.directory / APK_NAME], timeout=90)
            installed = True
            checkpoint()
            package = command('installed-package', adb + ['shell', 'dumpsys', 'package', PACKAGE]).decode()
            if 'primaryCpuAbi=x86_64' not in package or f'versionCode={version_code}' not in package:
                raise ValueError('Installed QA ABI/version mismatch')
            opt_out = list(SKIA_OPT_OUT) if renderer == 'skia' else []
            launch = command('launch', adb + ['shell', 'am', 'start', '-W', *opt_out, '-n', PACKAGE + '/com.follow.clash.MainActivity'], timeout=35).decode()
            if 'Status: ok' not in launch:
                raise RuntimeError('QA MainActivity launch request failed')
            time.sleep(12)
            log = command('first-logcat', adb + ['logcat', '-d', '-v', 'threadtime']).decode(errors='replace')
            pid = first_activity_pid(log)
            receipt['firstActivityPid'] = pid
            receipt['appRenderer'] = app_renderer(log, pid)
            receipt['productionRenderer'] = receipt['appRenderer']['backend'] in PRODUCTION_RENDERERS
            checkpoint()
            if pid is None:
                raise RuntimeError('First MainActivity process could not be identified from original logcat')
            if renderer == 'skia' and receipt['appRenderer']['backend'] != 'skia':
                raise RuntimeError('Requested Skia opt-out did not take effect in the first MainActivity process')
            failures = app_failure_lines(log, pid)
            receipt['firstFailureLines'] = failures
            if failures:
                app_failed('first-logcat', pid, failures)
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
            shot = renderer if renderer == 'skia' or receipt['productionRenderer'] else 'unconfirmed'
            (args.evidence / SCREENSHOTS[shot]).write_bytes(screen)
            receipt['screenshot'] = dict(file=SCREENSHOTS[shot], appRenderer=receipt['appRenderer']['backend'],
                                         productionRenderer=receipt['productionRenderer'])
            validate_initial_app_ui(ui, command('activity-after-screen', adb + ['shell', 'dumpsys', 'activity', 'activities']).decode())
            if command('pid-after-screen', adb + ['shell', 'pidof', PACKAGE]).decode().strip() != pid:
                raise RuntimeError('First MainActivity PID changed during capture')
            receipt['status'] = CAPTURED[renderer] if shot == renderer else RENDERER_UNCONFIRMED
        except BaseException as error:
            try:
                if isinstance(error, (subprocess.CalledProcessError, subprocess.TimeoutExpired)) and not cancelled:
                    failed = receipt['commands'][-1]['label'] if receipt['commands'] else None
                    state = device_state('device-state-after-failure')
                    if state != 'device':
                        device_lost(f'during {failed}', state)
            finally:
                remember_failure(receipt, 'probe', error)
        finally:
            receipt['cleanupErrors'] = []
            stopped = None
            try:
                checkpoint()
            except SystemExit as stop:
                stopped = stop
            state = None
            try:
                state = None if cancelled else device_state('pre-cleanup-state')
            except SystemExit:
                pass
            reachable = state == 'device' and not cancelled
            receipt['deviceCleanupSkipped'] = not reachable
            if not reachable and not cancelled:
                device_lost('before final evidence', state)
            for label, cmd in [('final-logcat', ['logcat', '-d', '-v', 'threadtime']),
                               ('crash-logcat', ['logcat', '-b', 'crash', '-d', '-v', 'threadtime']),
                               ('final-activity', ['shell', 'dumpsys', 'activity', 'activities']),
                               ('final-window', ['shell', 'dumpsys', 'window', 'windows'])]:
                if not reachable or cancelled:
                    break
                try:
                    data = command(label, adb + cmd)
                    if label == 'final-logcat' and receipt.get('firstActivityPid'):
                        failures = app_failure_lines(data.decode(errors='replace'), receipt['firstActivityPid'])
                        if failures:
                            app_failed('final-logcat', receipt['firstActivityPid'], failures)
                            remember_failure(receipt, 'final-app-crash', RuntimeError(failures[0]))
                        receipt['firstFailureLines'] = failures
                except BaseException as error:
                    receipt['cleanupErrors'].append(dict(phase=label, error=repr(error)))
                    remember_failure(receipt, label, error)
            if reachable and not cancelled and (installed or any(
                    c['label'] == 'install-x86-qa' and c.get('result') == 0 for c in receipt['commands'])):
                try:
                    command('stop-task-app', adb + ['shell', 'am', 'force-stop', PACKAGE])
                except BaseException as error:
                    receipt['cleanupErrors'].append(dict(phase='force-stop', error=repr(error)))
                    remember_failure(receipt, 'force-stop', error)
            if cancelled and 'primaryFailure' not in receipt:
                remember_failure(receipt, 'cancellation', SystemExit(128 + cancelled[0]))
            receipt['cancelSignals'] = cancelled
            try:
                save_atomic(path, receipt)
            except BaseException as error:
                remember_failure(receipt, 'receipt-save', error)
                receipt['receiptSaved'] = False
                fallback_receipt(receipt)
            if stopped is not None:
                raise stopped
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)
    return receipt.get('result', 0 if receipt['status'] == CAPTURED[renderer] else 1)


class EmulatorHostExit(RuntimeError):
    def __init__(self, code):
        number = -code if code < 0 else None
        super().__init__('Owned emulator host process exited before owned cleanup: '
                         + (f'signal {number}' if number else f'code {code}'))
        self.code = 128 + number if number else (code or 1)
        self.emulatorReturnCode = code


class AppFailure(RuntimeError):
    def __init__(self, record):
        super().__init__(f'First MainActivity process failed ({record.get("source")}): '
                         + str((record.get('lines') or ['no failure line'])[0]))
        self.code = 1


class SlowHostDeath(RuntimeError):
    def __init__(self, record):
        super().__init__(f'Owned emulator host outlived the {record["graceSeconds"]} s device-loss grace and exited with '
                         f'signal {record["signal"]} only after the owner terminated it')
        self.code = record['result']


def name_app_failure(receipt, record_failure, app):
    named = 'primaryFailure' in receipt
    record_failure(receipt, 'first-screen-app-failure', AppFailure(app))
    item = receipt['secondaryFailures'][-1] if named else receipt['primaryFailure']
    item.update(source=app.get('source'), lines=list(app.get('lines') or [])[:20])


class ExitWatch:
    """The owned emulator, stamping the first poll that finds the host process gone."""

    def __init__(self, process, receipt):
        self.process = process
        self.receipt = receipt

    def __getattr__(self, name):
        return getattr(self.process, name)

    def poll(self):
        code = self.process.poll()
        if code is not None and 'emulatorExitObservedAt' not in self.receipt:
            self.receipt.update(emulatorExitObservedAt=utc_now(), emulatorExitObservedMonotonic=time.monotonic())
        return code


def start_live_logcat(directory, env):
    path = directory / 'live-logcat.txt'
    with path.open('xb') as out:
        child = subprocess.Popen(['adb', '-s', 'emulator-5554', 'logcat', '-v', 'threadtime'], stdin=subprocess.DEVNULL,
                                 stdout=out, stderr=subprocess.STDOUT, start_new_session=True, env=env)
    return child, dict(pid=child.pid, file=path.name, startedAt=utc_now())


def finish_live_logcat(child, row, directory, stop_owned):
    row.update(cleanup=stop_owned(child), endedAt=utc_now())
    path = directory / row['file']
    text = path.read_text(errors='replace')
    pid = first_activity_pid(text)
    crashes = live_app_failure(text)[:20]
    row.update(bytes=path.stat().st_size, sha256=sha(path), lastLine=text.rstrip().rpartition('\n')[2][:400],
               firstActivityPid=pid, appRenderer=app_renderer(text, pid), appFailure=dict(
                   source='live-logcat', file=row['file'], firstActivityPid=pid, lines=[line for _, line in crashes],
                   lineNumbers=[number for number, _ in crashes], analysedAt=utc_now(),
                   analysedMonotonic=time.monotonic()) if crashes else None)


def drain_live_logcat(child, seconds):
    """Give adb time to deliver what the guest wrote before its host died; the stream ends by itself then."""
    started = time.monotonic()
    try:
        child.wait(timeout=seconds)
    except subprocess.TimeoutExpired:
        pass
    return dict(seconds=round(time.monotonic() - started, 3), endedBySelf=child.poll() is not None)


def owner_device_state(directory):
    try:
        output = inspection_command(['adb', '-s', 'emulator-5554', 'get-state'], directory, 'owner-device-state',
                                    timeout=5, check=False)
        return output.decode(errors='replace').strip()
    except Exception as error:
        return 'unreachable: ' + type(error).__name__


def probe_outcome(directory):
    try:
        value = load(directory / 'FIRST-SCREEN.json')
    except (OSError, ValueError):
        return {}
    return value if isinstance(value, dict) else {}


def probe_progress(directory):
    """How far the first-screen child got, so a fallback can tell a pre-launch host death from a launch-time one."""
    outcome = probe_outcome(directory)
    commands = outcome.get('commands')
    commands = [row for row in commands if isinstance(row, dict)] if isinstance(commands, list) else []
    stage = [row.get('label') for row in commands if row.get('label') not in CLEANUP_COMMANDS]
    launch = [row for row in commands if row.get('label') == 'launch']
    return dict(readable=bool(outcome), status=outcome.get('status'), observedStatus=outcome.get('observedStatus'),
                provisional=outcome.get('provisional') is True, lastStageCommand=stage[-1] if stage else None,
                launchStarted=bool(launch), launch={key: launch[0].get(key) for key in
                                                    ('result', 'timeout', 'errorType', 'startedAt', 'endedAt')} if launch else None,
                deviceLost=outcome.get('deviceLost'), primaryFailure=outcome.get('primaryFailure'),
                screenshot=outcome.get('screenshot'))


def signal_after_termination(receipt):
    """A crash signal after the owner's own SIGTERM; after a device-loss grace it is the slow host death."""
    cleanup, loss = receipt.get('cleanup') or {}, receipt.get('deviceLoss') or {}
    code = cleanup.get('returnCode')
    if not cleanup.get('terminationInitiated') or type(code) is not int or code >= 0 or -code in TERMINATION_SIGNALS:
        return None
    return dict(signal=-code, emulatorReturnCode=code, result=128 - code, terminationInitiated=True,
                slowHostDeath=loss.get('emulatorExited') is False, graceSeconds=loss.get('graceSeconds'),
                deviceLossWaitedSeconds=loss.get('waitedSeconds'))


def await_host_exit(emulator, receipt, reasons):
    started = time.monotonic()
    receipt['deviceLoss'] = dict(reasons=reasons, observedAt=utc_now(), graceSeconds=HOST_EXIT_GRACE_SECONDS)
    while (emulator.poll() is None and not receipt['interruptSignals']
           and time.monotonic() - started < HOST_EXIT_GRACE_SECONDS):
        try:
            emulator.wait(timeout=0.25)
        except subprocess.TimeoutExpired:
            pass
    receipt['deviceLoss'].update(waitedSeconds=round(time.monotonic() - started, 3),
                                 emulatorExited=emulator.poll() is not None)


def host_exit(emulator, receipt, observed_by):
    code = None if emulator is None else emulator.poll()
    if code is None:
        return None
    error = EmulatorHostExit(code)
    receipt.setdefault('emulatorHostExit', dict(
        result=error.code, emulatorReturnCode=code, signal=-code if code < 0 else None, cleanExit=code == 0,
        observedBy=observed_by, observedAt=receipt.get('emulatorExitObservedAt'),
        observedMonotonic=receipt.get('emulatorExitObservedMonotonic'),
        firstScreenStageStarted=receipt['firstScreenStageStarted']))
    return error


def classify_failure(error, emulator, receipt, evidence, record_failure, stream):
    """Name an app failure, a host exit and a device loss in that order, whichever order adb and the host died in."""
    outcome = probe_outcome(evidence / 'first-screen') if receipt['firstScreenStageStarted'] else {}
    observed = 'monitor' if receipt.get('unexpectedEmulatorExit') else 'owner-poll'
    reasons = []
    if emulator is not None and emulator.poll() is None and not receipt['interruptSignals']:
        state = owner_device_state(evidence)
        if state != 'device':
            reasons.append('owner adb get-state: ' + (state or 'no device'))
        if isinstance(outcome.get('deviceLost'), dict):
            reasons.append('first-screen probe: ' + str(outcome['deviceLost'].get('during')))
        if reasons:
            await_host_exit(emulator, receipt, reasons)
            observed = 'grace-wait'
    host = host_exit(emulator, receipt, observed)
    try:
        live, problem = stream(), None
    except Exception as failure:
        live, problem = None, failure
    app = outcome.get('appFailure') if isinstance(outcome.get('appFailure'), dict) else None
    stamp = (receipt.get('emulatorHostExit') or {}).get('observedMonotonic')
    moment = (app or {}).get('recordedMonotonic')
    app_first = app is not None and (host is None or (isinstance(moment, (int, float)) and isinstance(stamp, (int, float))
                                                      and moment <= stamp))
    if live is not None and not app_first:
        # The guest wrote every line of the host-side stream, so none of them can postdate the host's death.
        app, app_first = live, True
    if app is not None:
        receipt['appFailure'] = dict(app, beforeHostExit=app_first if host is not None else None)
    if app_first:
        name_app_failure(receipt, record_failure, app)
    if host is not None:
        record_failure(receipt, 'emulator-host-exit', host)
    elif reasons:
        record_failure(receipt, 'device-lost', RuntimeError(
            f'adb lost emulator-5554 while its host process kept running {HOST_EXIT_GRACE_SECONDS} s: ' + '; '.join(reasons)))
    if app is not None and not app_first:
        name_app_failure(receipt, record_failure, app)
    record_failure(receipt, 'qa-runtime', error)
    if problem is not None:
        record_failure(receipt, 'live-logcat-cleanup', problem)


def fallback_reason(primary, package):
    expected = dict(owner='standard_x86_qa.py', attempt='primary', harnessRun=os.environ['GITHUB_RUN_ID'],
                    harnessRunAttempt=os.environ['GITHUB_RUN_ATTEMPT'], gpuBackend=GPU_BACKEND,
                    appRendererRequested='impeller', status='FAILED', accepted=False, interruptSignals=[],
                    firstScreenStageStarted=True, **package)
    if any(primary.get(key) != value for key, value in expected.items()):
        return 'Primary receipt does not describe a finished production-renderer attempt of this run and package'
    late = primary.get('emulatorSignalAfterTermination')
    if isinstance(late, dict) and late.get('slowHostDeath') is True:
        return (f'Slow host death: the primary host outlived the device-loss grace and exited with signal {late.get("signal")} '
                'only after the owner terminated it, which is not a host exit before owned cleanup')
    host, renderer = primary.get('emulatorHostExit'), primary.get('rendererEvidence')
    if (primary.get('unexpectedEmulatorExit') is not True or not isinstance(host, dict) or not positive(host.get('result'))
            or not isinstance(renderer, dict) or renderer.get('glesModeSelected') != GPU_BACKEND):
        return 'Primary attempt did not end with the owned emulator host gone after its first-screen stage started'
    return None


def reuse_avd(primary, record):
    """Clear the AVD locks the dead primary emulator left, refusing while its recorded process still runs."""
    pid, command, home = primary.get('emulatorPid'), primary.get('command'), os.environ.get('ANDROID_AVD_HOME')
    avd = Path(home or '.') / 'test.avd'
    record.update(directory=str(avd), primaryEmulatorPid=pid, staleLocks=[])
    if (not home or not positive(pid) or not isinstance(command, list) or not command
            or not all(isinstance(part, str) for part in command) or avd.is_symlink() or not avd.is_dir()):
        raise ValueError('the primary emulator PID, its command or the owned AVD directory is not recorded')
    try:
        running = [part.decode(errors='replace') for part in (PROC / str(pid) / 'cmdline').read_bytes().split(b'\0')[:-1]]
    except FileNotFoundError:
        running = []
    # qemu may replace the emulator launcher under the same PID, so match the SDK emulator directory or the AVD.
    record['primaryEmulatorRunning'] = bool(running) and (
        running == command or Path(running[0]).is_relative_to(Path(command[0]).parent)
        or any(running[index:index + 2] == ['-avd', 'test'] for index in range(len(running))))
    record['primaryEmulatorCmdline'] = running[:16]
    if record['primaryEmulatorRunning']:
        raise ValueError(f'the primary emulator {pid} is still running')
    record['configSha256'] = sha(avd / 'config.ini') if (avd / 'config.ini').is_file() else None
    locks = sorted(avd.rglob('*.lock'))
    if any(path.is_dir() and not path.is_symlink() for path in locks):
        raise ValueError('an AVD lock is a directory, not a stale emulator lock file')
    for path in locks:
        row = dict(path=path.relative_to(avd).as_posix(), kind='symlink' if path.is_symlink() else 'file')
        if row['kind'] == 'file' and path.stat().st_size <= 256:
            row['content'] = path.read_bytes().decode(errors='replace').strip()
        record['staleLocks'].append(row)
        path.unlink()
        row['removed'] = True


def fallback_decision(path, package):
    try:
        primary = load(path)
    except (OSError, ValueError) as error:
        primary, reason = {}, 'Primary receipt is unreadable: ' + repr(error)
    else:
        primary = primary if isinstance(primary, dict) else {}
        reason = fallback_reason(primary, package)
    def part(key):
        return primary.get(key) if isinstance(primary.get(key), dict) else {}
    live = part('liveLogcat').get('appFailure')
    decision = dict(diagnosticOnly=True, accepted=False, warranted=False, reason=reason,
                    primaryFailure=primary.get('primaryFailure'), primarySecondaryFailures=primary.get('secondaryFailures'),
                    primaryHostExit=primary.get('emulatorHostExit'), primaryCleanup=primary.get('cleanup'),
                    primarySignalAfterTermination=primary.get('emulatorSignalAfterTermination'),
                    primaryDeviceLoss=primary.get('deviceLoss'), primaryFirstScreen=primary.get('firstScreenProgress'),
                    launchStarted=part('firstScreenProgress').get('launchStarted'),
                    primaryAppFailure=primary.get('appFailure'), primaryLiveLogcatAppFailure=live,
                    mixedWithAppFailure=bool(primary.get('appFailure') or live),
                    primaryReceiptSha256=sha(path) if path.is_file() else None, avd={})
    if reason is None:
        try:
            reuse_avd(primary, decision['avd'])
        except (OSError, ValueError) as error:
            decision['reason'] = 'The owned AVD cannot be reused: ' + str(error)
        else:
            decision['warranted'] = True
    return decision


def host_observer_enabled(args):
    if not getattr(args, 'host_crash_observer', False):
        return False
    if (sys.platform != 'linux' or os.environ.get('GITHUB_ACTIONS') != 'true'
            or os.environ.get('GITHUB_REPOSITORY') != REPOSITORY
            or os.environ.get('GITHUB_REF') != HOST_OBSERVER_REF
            or os.environ.get('GITHUB_EVENT_NAME') != 'workflow_dispatch'
            or os.environ.get('GITHUB_RUN_ATTEMPT') != '1'
            or args.phase not in ('reuse', 'capture')
            or getattr(args, 'fallback_after', None) is not None):
        raise ValueError('Host crash observation requires the isolated first-attempt Impeller probe controller')
    return True


def observer_process(pid):
    text = (PROC / str(pid) / 'stat').read_text()
    fields = text[text.rindex(')') + 2:].split()
    return dict(pid=pid, parent=int(fields[1]), startTicks=int(fields[19]))


def observer_target(pid, sdk):
    identity = observer_process(pid)
    executable = (PROC / str(pid) / 'exe').resolve(strict=True)
    if not executable.is_relative_to(sdk.resolve() / 'emulator'):
        raise ValueError('Owned emulator executable is outside the recorded SDK emulator directory')
    return dict(identity, executable=str(executable))


def observer_debugger(pid, environment, elevated):
    if type(pid) is not int or pid <= 0:
        raise ValueError('Debugger PID must be a positive integer')
    before = observer_process(pid)
    path = PROC / str(pid) / 'exe'
    try:
        executable = os.readlink(path)
    except PermissionError:
        if not elevated:
            raise
        result = subprocess.run(['sudo', '-n', '--', '/usr/bin/readlink', '-e', str(path)],
                                env=environment, capture_output=True, timeout=2, check=True)
        if len(result.stdout) > 4096:
            raise ValueError('Debugger executable readback exceeds limit')
        executable = result.stdout.decode().strip()
    executable = str(Path(executable).resolve(strict=True))
    if observer_process(pid) != before:
        raise RuntimeError('Debugger process identity changed during executable readback')
    return dict(before, executable=executable)


def observer_environment(directory):
    return dict(PATH='/usr/bin:/bin', HOME=str(directory), TMPDIR=str(directory),
                LANG='C.UTF-8', LC_ALL='C.UTF-8', DEBUGINFOD_URLS='')


def observer_signal_action(name, stopping, captured):
    if name == 'SIGILL':
        return 'capture' if captured < 4 else 'continue'
    return 'detach' if stopping else 'continue'


HOST_GDB_SCRIPT = r'''
import gdb, os, json, time, hashlib, select, stat
config = json.loads(CONFIG_JSON)
last_signal = None
captured = 0
failure_context = {}
def marker(kind, **values):
    print('AEROVE_HOST_OBSERVER ' + json.dumps(dict(kind=kind, **values)), flush=True)
def stopped(event):
    global last_signal
    last_signal = getattr(event, 'stop_signal', None)
def stat_identity(pid):
    text = open('/proc/%d/stat' % pid).read()
    fields = text[text.rindex(')') + 2:].split()
    return dict(pid=pid, parent=int(fields[1]), startTicks=int(fields[19]))
def target_identity():
    return dict(stat_identity(config['target']['pid']),
                executable=os.readlink('/proc/%d/exe' % config['target']['pid']))
def detail(command):
    try: print(gdb.execute(command, to_string=True), flush=True)
    except gdb.error as error: print('unavailable: ' + str(error), flush=True)
gdb.events.stop.connect(stopped)
attached = False
try:
    marker('started', debugger=dict(stat_identity(os.getpid()), executable=os.readlink('/proc/self/exe')))
    for command in ['set pagination off', 'set confirm off', 'set auto-load off',
                    'set debuginfod enabled off', 'set print frame-arguments none',
                    'set print elements 32', 'handle SIGILL stop print pass']:
        gdb.execute(command, to_string=True)
    if os.isatty(0) or not stat.S_ISFIFO(os.fstat(0).st_mode):
        raise RuntimeError('Startup permission requires the owned stdin pipe, not a terminal')
    permission = b''
    while len(permission) < 3:
        remaining = config['startupDeadline'] - time.monotonic()
        if remaining <= 0 or not select.select([0], [], [], remaining)[0]: break
        part = os.read(0, 3 - len(permission))
        if not part: break
        permission += part
    if permission != b'GO\n' or time.monotonic() > config['startupDeadline']:
        raise RuntimeError('Startup was not admitted before its deadline or stdin EOF')
    marker('admitted')
    actual_target = target_identity()
    if actual_target != config['target']:
        failure_context = dict(phase='before-attach',
            expected={key: (config['target'][key][:4096] if key == 'executable' else config['target'][key])
                      for key in ('pid', 'parent', 'startTicks', 'executable')},
            actual={key: (actual_target[key][:4096] if key == 'executable' else actual_target[key])
                    for key in ('pid', 'parent', 'startTicks', 'executable')})
        raise RuntimeError('Owned target identity changed before attach')
    gdb.execute('attach %d' % config['target']['pid'], to_string=True)
    attached = True
    if target_identity() != config['target']:
        raise RuntimeError('Owned target identity changed at attach')
    marker('ready', debugger=dict(stat_identity(os.getpid()), executable=os.readlink('/proc/self/exe')),
           target=target_identity())
    while gdb.selected_inferior().pid:
        last_signal = None
        gdb.execute('continue', to_string=True)
        if not gdb.selected_inferior().pid:
            break
        action = signal_action(last_signal, os.path.exists(config['stop']), captured)
        if action == 'detach':
            break
        if last_signal == 'SIGILL':
            marker('signal', signal='SIGILL', captured=captured, signalPassedOnContinue=True)
        if action == 'capture':
            captured += 1
            marker('capture-start', event=captured)
            fault_thread = gdb.selected_thread()
            try:
                started = time.monotonic()
                for command in ['p $_siginfo', 'info registers', 'x/32bx $pc', 'x/16i $pc',
                                'info sharedlibrary', 'info proc mappings']:
                    if time.monotonic() - started >= 5: break
                    detail(command)
                threads = gdb.selected_inferior().threads()
                print('threads=%d; at most 64 threads, 32 frames, no arguments/locals' % len(threads), flush=True)
                for thread in threads[:64]:
                    if time.monotonic() - started >= 5: break
                    thread.switch()
                    frame = gdb.newest_frame()
                    for index in range(32):
                        if frame is None or time.monotonic() - started >= 5: break
                        print('thread=%d frame=%d pc=%#x function=%s' %
                              (thread.num, index, frame.pc(), frame.name()), flush=True)
                        frame = frame.older()
                print('ELF objects/build IDs: ' + json.dumps([
                    dict(path=obj.filename, buildId=getattr(obj, 'build_id', None)) for obj in gdb.objfiles()]), flush=True)
            except BaseException as error:
                marker('capture-error', errorType=type(error).__name__)
            finally:
                fault_thread.switch()
            marker('capture-end', event=captured, seconds=time.monotonic() - started)
except BaseException as error:
    if isinstance(error, KeyboardInterrupt) and os.path.exists(config['stop']):
        marker('stop-interrupt')
    else:
        marker('error', errorType=type(error).__name__, error=str(error), **failure_context)
finally:
    if attached and gdb.selected_inferior().pid:
        try:
            gdb.execute('detach', to_string=True)
            marker('detached')
        except BaseException as error: marker('detach-error', errorType=type(error).__name__)
    marker('finished')
'''


class HostCrashObserver:
    def __init__(self, directory, environment):
        self.directory, self.environment = directory, observer_environment(directory)
        self.process = self.debugger = self.timer = self.reader = None
        self.ready = threading.Event()
        self.started = threading.Event()
        self.stop = directory / 'STOP-REQUESTED'
        self.record = dict(diagnosticOnly=True, accepted=False, compiled=False, timingPerturbed=True,
                           status='HOST_CRASH_CAPTURE_UNAVAILABLE', events=[], outputLimitBytes=2 * 1024 * 1024,
                           launcher=None, launcherExecutable=None, startupAdmitted=False, readerStarted=False,
                           startedIdentityValidated=False)
        self.directory.mkdir()
        try:
            self.prepare(self.environment)
        except Exception as error:
            self.record['preflightError'] = type(error).__name__
            self.persist()
            raise RuntimeError('HOST_CRASH_CAPTURE_UNAVAILABLE: existing debugger preflight failed') from error

    def prepare(self, environment):
        tool = shutil.which('gdb', path=self.environment['PATH'])
        if tool is None:
            self.persist()
            raise RuntimeError('HOST_CRASH_CAPTURE_UNAVAILABLE: existing GDB not found; no installation allowed')
        tool = Path(tool).resolve(strict=True)
        self.prefix = []
        self.record['debuggerTool'] = dict(path=str(tool), sha256=sha(tool))
        # Elevated attach is confined to this isolated runner and the recorded owned PID, never global ptrace policy.
        if (tool.stat().st_uid == 0 and not tool.stat().st_mode & 0o022
                and shutil.which('sudo') is not None):
            result = subprocess.run(['sudo', '-n', '--', str(tool), '-nx', '-nh', '--batch', '--version'],
                                    env=environment, capture_output=True, timeout=5)
            if result.returncode == 0:
                self.prefix = ['sudo', '-n', '--']
        result = subprocess.run([*self.prefix, str(tool), '-nx', '-nh', '--batch',
                                 '-ex', 'set debuginfod enabled off', '-ex', 'python import gdb',
                                 '-ex', 'show version'],
                                env=environment, capture_output=True, timeout=5)
        if result.returncode:
            self.persist()
            raise RuntimeError('HOST_CRASH_CAPTURE_UNAVAILABLE: existing GDB cannot execute')
        self.tool = tool
        self.record.update(debuggerVersion=result.stdout[:4096].decode(errors='replace'),
                           sudoNonInteractive=bool(self.prefix))
        import resource
        self.record['coreLimits'] = list(resource.getrlimit(resource.RLIMIT_CORE))
        for name, file in [('corePattern', '/proc/sys/kernel/core_pattern'),
                           ('ptraceScope', '/proc/sys/kernel/yama/ptrace_scope')]:
            try: self.record[name] = Path(file).read_text()[:4096]
            except OSError as error: self.record[name] = dict(unavailable=type(error).__name__)
        self.persist()

    def persist(self):
        save(self.directory / 'HOST-OBSERVER.json', dict(self.record, events=list(self.record['events'])))

    def register_launcher(self):
        if self.process.poll() is not None: return None
        actual = observer_debugger(self.process.pid, self.environment, bool(self.prefix))
        identity = {key: actual[key] for key in ('pid', 'parent', 'startTicks')}
        if (actual['parent'] != os.getpid() or actual['executable'] not in self.launcher_executables
                or self.record.get('launcher') not in (None, identity)):
            raise RuntimeError('Observer launcher is not the live owned child with its expected executable')
        self.record.update(launcher=identity, launcherExecutable=actual['executable'])
        return actual

    def signal_debugger(self, sig):
        deadline = time.monotonic() + 2
        if self.process is None:
            return
        if self.debugger is None:
            if self.process.poll() is not None: return
            actual = (self.register_launcher() if self.record.get('launcherExecutable') is None
                      else observer_debugger(self.process.pid, self.environment, bool(self.prefix)))
            if actual is None: return
            if ({key: actual[key] for key in ('pid', 'parent', 'startTicks')} != self.record['launcher']
                    or actual['executable'] != self.record['launcherExecutable']
                    or os.getpgid(self.process.pid) != self.process.pid):
                raise RuntimeError('Observer launcher identity changed')
            if self.prefix:
                remaining = deadline - time.monotonic()
                if remaining <= 0: raise TimeoutError('Debugger identity read exhausted signal budget')
                subprocess.run(['sudo', '-n', '--', '/bin/kill', '-' + signal.Signals(sig).name,
                                '--', '-' + str(self.process.pid)], check=True, capture_output=True,
                               timeout=remaining, env=self.environment)
            else:
                os.killpg(self.process.pid, sig)
            return
        actual = observer_debugger(self.debugger['pid'], self.environment, bool(self.prefix))
        if any(actual[key] != self.debugger[key] for key in ('pid', 'startTicks', 'executable')):
            raise RuntimeError('Observer debugger identity is unavailable or changed')
        if self.prefix:
            remaining = deadline - time.monotonic()
            if remaining <= 0: raise TimeoutError('Debugger identity read exhausted signal budget')
            subprocess.run(['sudo', '-n', '--', '/bin/kill', '-' + signal.Signals(sig).name,
                            str(self.debugger['pid'])], check=True, capture_output=True, timeout=remaining,
                           env=self.environment)
        else:
            os.kill(self.debugger['pid'], sig)

    def read_output(self):
        try:
            self.read_output_stream()
        except BaseException as error:
            self.record['readerError'] = type(error).__name__
        finally:
            self.ready.set()
            if getattr(self, 'started', None) is not None: self.started.set()

    def read_output_stream(self):
        written = 0
        with (self.directory / 'gdb.txt').open('xb') as output:
            while True:
                line = self.process.stdout.readline(65536)
                if not line: break
                remaining = max(0, self.record['outputLimitBytes'] - written)
                output.write(line[:remaining]); written += min(len(line), remaining)
                if len(line) > remaining: self.record['outputTruncated'] = True
                if not line.startswith(b'AEROVE_HOST_OBSERVER '): continue
                try: row = json.loads(line[len(b'AEROVE_HOST_OBSERVER '):])
                except ValueError: continue
                row['observedAt'] = utc_now()
                if len(self.record['events']) < 64: self.record['events'].append(row)
                else: self.record['eventsTruncated'] = True
                if row['kind'] == 'started':
                    debugger = row['debugger']
                    actual = observer_debugger(debugger['pid'], self.environment, bool(self.prefix))
                    ancestor = observer_process(debugger['pid'])
                    for _ in range(8):
                        if ancestor['pid'] == self.process.pid: break
                        ancestor = observer_process(ancestor['parent'])
                    if (ancestor != self.record['launcher'] or debugger['executable'] != str(self.tool)
                            or actual != debugger):
                        self.record['debuggerIdentityRejected'] = True
                    else:
                        self.debugger = debugger
                        self.record['startedIdentityValidated'] = True
                    self.started.set()
                if row['kind'] == 'admitted': self.record['startupAdmitted'] = True
                if row['kind'] == 'ready':
                    actual = observer_debugger(row['debugger']['pid'], self.environment, bool(self.prefix))
                    self.record['attachActuallySucceeded'] = (self.debugger == row['debugger'] == actual
                                                               and row['target'] == self.target)
                    self.ready.set()
                if row['kind'] == 'capture-start':
                    self.timer = threading.Timer(5, self.capture_timeout)
                    self.timer.daemon = True; self.timer.start()
                if row['kind'] == 'capture-end' and self.timer is not None:
                    self.timer.cancel()
        self.record['outputBytes'] = written
        self.ready.set()

    def capture_timeout(self):
        self.record['captureTimedOut'] = True
        try: self.signal_debugger(signal.SIGINT)
        except Exception as error: self.record['interruptError'] = type(error).__name__

    def grant_startup_permission(self, deadline):
        def prerequisites():
            failures = ('readerError', 'debuggerIdentityRejected', 'cleanupError', 'cleanupTimedOut',
                        'startupIdentityError', 'startupPipeCloseError', 'stopRequestError', 'pollError',
                        'stopSignalError', 'interruptError', 'captureTimedOut', 'outputTruncated', 'eventsTruncated')
            return (not any(self.record.get(key) for key in failures)
                    and len(self.record['events']) == 1 and self.record['events'][0]['kind'] == 'started'
                    and not self.record.get('closed') and not self.record.get('childReaped')
                    and self.record.get('status') == 'HOST_CRASH_CAPTURE_UNAVAILABLE'
                    and self.record.get('debuggerReturnCode') is None
                    and self.record.get('readerStarted') is True
                    and self.record.get('startedIdentityValidated') is True and self.started.is_set()
                    and self.reader is not None and self.reader.ident is not None and self.reader.is_alive()
                    and self.process is not None and self.process.poll() is None
                    and self.process.stdin is not None and self.process.stdin.closed is False
                    and self.debugger is not None and not self.ready.is_set()
                    and not self.record.get('startupPermissionSent') and not self.record.get('startupAdmitted')
                    and not self.record.get('attachActuallySucceeded') and not self.stop.exists()
                    and time.monotonic() < deadline)
        if not prerequisites():
            raise RuntimeError('HOST_CRASH_CAPTURE_UNAVAILABLE: startup permission prerequisites are invalid')
        actual = observer_debugger(self.debugger['pid'], self.environment, bool(self.prefix))
        if actual != self.debugger or actual['executable'] != str(self.tool) or not prerequisites():
            raise RuntimeError('HOST_CRASH_CAPTURE_UNAVAILABLE: startup identity or reader changed before permission')
        self.process.stdin.write(b'GO\n'); self.process.stdin.flush()
        self.record['startupPermissionSent'] = True

    def start(self, emulator, sdk):
        startup_deadline = time.monotonic() + 5
        self.target = observer_target(emulator.pid, sdk)
        if self.target['parent'] != os.getpid():
            raise ValueError('Observer target is not the emulator child of this owner')
        self.record['target'] = self.target
        self.launcher_executables = {str(self.tool)}
        if self.prefix:
            self.launcher_executables.add(str(Path(shutil.which('sudo', path=self.environment['PATH'])).resolve(strict=True)))
        stop = self.directory / 'STOP-REQUESTED'
        self.stop = stop
        import inspect
        code = ('import time\n' + inspect.getsource(observer_signal_action).replace(
            'observer_signal_action', 'signal_action') + '\nCONFIG_JSON = ' + repr(json.dumps(dict(
                target=self.target, stop=str(stop), startupDeadline=startup_deadline))) + '\n' + HOST_GDB_SCRIPT)
        script = self.directory / 'observer.gdb'
        script.write_text('python\n' + code + '\nend\n', encoding='utf-8')
        self.process = subprocess.Popen([*self.prefix, str(self.tool), '-nx', '-nh', '--quiet', '--batch',
                                         '-x', str(script)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, start_new_session=True, env=self.environment)
        self.record['launcher'] = observer_process(self.process.pid)
        self.register_launcher()
        self.reader = threading.Thread(target=self.read_output, daemon=True); self.reader.start()
        self.record['readerStarted'] = True
        if not self.started.wait(max(0, startup_deadline - time.monotonic())) or self.debugger is None:
            raise RuntimeError('HOST_CRASH_CAPTURE_UNAVAILABLE: debugger startup identity was not admitted')
        self.grant_startup_permission(startup_deadline)
        if not self.ready.wait(max(0, startup_deadline - time.monotonic())) or not self.record.get('attachActuallySucceeded'):
            raise RuntimeError('HOST_CRASH_CAPTURE_UNAVAILABLE: owned-PID attach failed or timed out')
        actual = observer_debugger(self.debugger['pid'], self.environment, bool(self.prefix))
        if self.debugger != actual or actual['executable'] != str(self.tool):
            raise RuntimeError('HOST_CRASH_CAPTURE_UNAVAILABLE: debugger executable/start identity mismatch')
        self.record.update(status='ATTACHED', debugger=self.debugger)
        self.persist()

    def close(self):
        if self.record.get('closed'): return
        try:
            if self.timer is not None: self.timer.cancel()
            if self.process is not None:
                try: self.stop.touch(exist_ok=True)
                except OSError as error: self.record['stopRequestError'] = type(error).__name__
                try: self.process.stdin.close()
                except (OSError, ValueError) as error: self.record['startupPipeCloseError'] = type(error).__name__
                if self.process.poll() is None:
                    if not self.record.get('startupAdmitted'):
                        try: self.register_launcher()
                        except Exception as error: self.record['startupIdentityError'] = type(error).__name__
                    else:
                        try: self.signal_debugger(signal.SIGINT)
                        except Exception as error: self.record['stopSignalError'] = type(error).__name__
                    try: self.process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        self.record['cleanupTimedOut'] = True
                        for sig in (signal.SIGTERM, signal.SIGKILL):
                            try:
                                self.signal_debugger(sig); self.process.wait(timeout=2)
                                break
                            except Exception as error: self.record['cleanupError'] = type(error).__name__
        except BaseException as error:
            self.record['cleanupError'] = type(error).__name__
        finally:
            reader = getattr(self, 'reader', None)
            reader_alive = False
            try:
                if reader is not None and reader.ident is not None: reader.join(timeout=1)
                reader_alive = reader is not None and reader.is_alive()
                self.record['outputReaderJoined'] = reader is not None and reader.ident is not None and not reader_alive
            except BaseException as error:
                self.record['readerError'] = type(error).__name__
                self.record['outputReaderJoined'] = False
                reader_alive = True
            try: code = None if self.process is None else self.process.poll()
            except BaseException as error:
                self.record['pollError'] = type(error).__name__
                code = None
            self.record.update(debuggerReturnCode=code, childReaped=self.process is None or code is not None)
            incomplete = (code != 0 or not self.record['outputReaderJoined']
                          or not self.record['childReaped']
                          or not any(row['kind'] == 'finished' for row in self.record['events'])
                          or any(row['kind'] in ('error', 'detach-error', 'capture-error')
                                 for row in self.record['events'])
                          or any(self.record.get(key) for key in ('cleanupTimedOut', 'cleanupError', 'captureTimedOut',
                                 'stopSignalError', 'interruptError', 'outputTruncated', 'readerError',
                                 'startupIdentityError', 'startupPipeCloseError')))
            self.record['status'] = ('DIAGNOSTIC_INCOMPLETE' if incomplete else 'FINISHED') if self.record.get(
                'attachActuallySucceeded') else 'HOST_CRASH_CAPTURE_UNAVAILABLE'
            self.record['closed'] = self.record['childReaped'] and not reader_alive
            self.persist()


def host_diagnostics(directory):
    directory.mkdir(parents=True, exist_ok=True)
    rows = []
    for label, command in (('host-kernel', ['sudo', '-n', 'dmesg', '--ctime']), ('host-cpuinfo', ['cat', '/proc/cpuinfo'])):
        try:
            inspection_command(command, directory, label, timeout=10, check=False, suffix='.txt')
            rows.append(dict(label=label, collected=True))
        except Exception as error:
            rows.append(dict(label=label, collected=False, error=repr(error)))
    return rows


def capture(args):
    sys.path.insert(0, str(args.harness / 'apps/aerove-lite/tool'))
    from emulator_bootstrap import wait_ready
    from emulator_current import write_owner_receipt
    from emulator_standard_reference import record_failure, persist, run_stage
    from emulator_qa79 import emulator_environment, crash_inventory, preserve_owned_crash, finish_emulator, stop_owned
    if sys.platform != 'linux' or os.environ.get('GITHUB_ACTIONS') != 'true':
        raise ValueError('Only a fresh isolated GitHub Linux/KVM runner is allowed')
    qa = validate_identity(load(args.directory / 'QA_BUILD_INFO.json'))
    package = package_origin(qa, args.package_receipt)
    observe_host = host_observer_enabled(args)
    if observe_host and (args.package_receipt is None or package.get('packageRun') != '40536314613'
                         or package.get('qaArtifactId') != '12747833792'
                         or qa['formalArmBuild']['runId'] != 40531629533
                         or qa['apk']['sha256'] != 'b3185647274c76254e023ded2224785d7e1cd0c50600a0f83c464fbabb0d541e'):
        raise ValueError('Host observer may only reuse the immutable QA5 package of formal58')
    attempt = 'primary' if args.fallback_after is None else 'fallback'
    setup = ATTEMPTS[attempt]
    args.evidence.mkdir(parents=True, exist_ok=False)
    decision = None
    if attempt == 'fallback':
        decision = fallback_decision(args.fallback_after, package)
        save(args.evidence / 'FALLBACK.json', decision)
        if not decision['warranted']:
            return 0
    receipt = dict(owner='standard_x86_qa.py', ownerPid=os.getpid(), diagnosticOnly=True, accepted=False,
                   source=qa['source'], sourceTree=qa['sourceTree'], formalRun=qa['formalArmBuild']['runId'],
                   harnessSource=os.environ['GITHUB_SHA'], harnessRun=os.environ['GITHUB_RUN_ID'],
                   harnessRunAttempt=os.environ['GITHUB_RUN_ATTEMPT'], **package, attempt=attempt,
                   fallbackAfter=decision, primaryResult=0, interruptSignals=[], children=[], cleanupErrors=[],
                   gpuBackend=GPU_BACKEND, appRendererRequested=setup['appRenderer'],
                   productionRendererRequested=setup['appRenderer'] == 'impeller', firstScreenStageStarted=False,
                   startedAt=utc_now(), status='STARTED')
    def checkpoint():
        try:
            write_owner_receipt(args.evidence / 'EMULATOR-OWNER.json', dict(
                receipt, status='IN_PROGRESS', observedStatus=receipt['status'], provisional=True, checkpointAt=utc_now()))
        except Exception as error:
            receipt.setdefault('checkpointErrors', []).append(repr(error))
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
        if phase != 'finalized':
            checkpoint()
        elif not late_handling:
            late_handling = True
            record_failure(receipt, 'finalized-cancellation', SystemExit(128 + sig))
            receipt.update(result=receipt['primaryResult'], status='FAILED')
            try:
                persist(args.evidence, receipt)
            finally:
                raise SystemExit(receipt['primaryResult'])
    previous = {s: signal.signal(s, interrupt) for s in (signal.SIGTERM, signal.SIGINT)}
    emulator = live = observer = None
    owned = []
    before = None
    observer_stop_budget = 16 if observe_host else 0
    deadline = time.monotonic() + OWNER_DEADLINE_SECONDS - ATTRIBUTION_SECONDS - observer_stop_budget
    receipt['ownerBudget'] = dict(runtimeSeconds=OWNER_DEADLINE_SECONDS - ATTRIBUTION_SECONDS - observer_stop_budget,
                                  attributionSeconds=ATTRIBUTION_SECONDS + observer_stop_budget, ownedStopSeconds=OWNED_STOP_SECONDS,
                                  worstCaseSeconds=OWNER_DEADLINE_SECONDS + 2 * OWNED_STOP_SECONDS)
    env = emulator_environment(dict(os.environ, ANDROID_SERIAL='emulator-5554'))
    def stream():
        """Stop the host-side logcat once and return the QA app crash it holds."""
        row = receipt.get('liveLogcat') or {}
        if live is not None and 'cleanup' not in row and 'cleanupError' not in row:
            try:
                if emulator is not None and emulator.poll() is not None:
                    row['drain'] = drain_live_logcat(live, LIVE_DRAIN_SECONDS)
                finish_live_logcat(live, row, args.evidence, stop_owned)
            except BaseException as error:
                row['cleanupError'] = repr(error)
                raise
        return row.get('appFailure')
    checkpoint()
    try:
        if observe_host:
            observer = HostCrashObserver(args.evidence / 'host-observer', env)
            receipt['hostCrashObserver'] = observer.record
        before = crash_inventory()
        command = [str(Path(os.environ['ANDROID_HOME']) / 'emulator/emulator'), *setup['flags']]
        receipt['command'] = command
        with (args.evidence / 'emulator.stdout.log').open('wb') as out, (args.evidence / 'emulator.stderr.log').open('wb') as err:
            emulator = ExitWatch(subprocess.Popen(command, stdout=out, stderr=err, stdin=subprocess.DEVNULL,
                                                  start_new_session=True, env=env), receipt)
            receipt['emulatorPid'] = emulator.pid
            if observer is not None:
                observer.start(emulator, Path(os.environ['ANDROID_HOME']))
            checkpoint()
            wait_ready(emulator, args.evidence, receipt, check)
            receipt['readyAt'] = utc_now()
            receipt['rendererEvidence'] = host_renderer((args.evidence / 'emulator.stdout.log').read_text(errors='replace'))
            live, receipt['liveLogcat'] = start_live_logcat(args.evidence, env)
            probe = [sys.executable, __file__, 'probe', '--directory', str(args.directory),
                     '--evidence', str(args.evidence / 'first-screen'), '--build-tools', str(args.build_tools),
                     '--app-renderer', setup['appRenderer']]
            if args.package_receipt is not None:
                probe += ['--package-receipt', str(args.package_receipt)]
            receipt['firstScreenStageStarted'] = True
            checkpoint()
            run_stage('first-screen', probe, 300, emulator, receipt, owned, args.evidence, env, deadline)
            check(receipt)
            receipt['status'] = CAPTURED[setup['appRenderer']]
    except BaseException as error:
        checkpoint()
        try:
            classify_failure(error, emulator, receipt, args.evidence, record_failure, stream)
        except Exception as problem:
            record_failure(receipt, 'qa-runtime', error)
            record_failure(receipt, 'failure-classification', problem)
    finally:
        checkpoint()
        for child, row in reversed(owned):
            try:
                row['cleanup'] = stop_owned(child)
            except BaseException as error:
                record_failure(receipt, 'probe-cleanup', error)
        try:
            app = stream()
        except BaseException as error:
            app = None
            record_failure(receipt, 'live-logcat-cleanup', error)
        if app is not None and 'appFailure' not in receipt:
            receipt['appFailure'] = dict(app, beforeHostExit=None)
            name_app_failure(receipt, record_failure, app)
        if receipt['firstScreenStageStarted']:
            try:
                receipt['firstScreenProgress'] = probe_progress(args.evidence / 'first-screen')
            except Exception as error:
                record_failure(receipt, 'first-screen-progress', error)
        if emulator is not None:
            if observer is not None:
                try:
                    observer.close()
                    if (observer.record['status'] != 'FINISHED' or not observer.record.get('outputReaderJoined')
                            or any(row['kind'] in ('error', 'detach-error', 'capture-error')
                                   for row in observer.record['events'])):
                        raise RuntimeError('Host debugger diagnostic/cleanup failed; see HOST-OBSERVER.json')
                except BaseException as error:
                    record_failure(receipt, 'host-observer', error)
            try:
                finish_emulator(emulator, receipt, receipt['primaryResult'])
                if not receipt['cleanup']['terminationInitiated'] and 'emulatorHostExit' not in receipt:
                    record_failure(receipt, 'emulator-host-exit', host_exit(emulator, receipt, 'owned-cleanup'))
                late = signal_after_termination(receipt)
                if late is not None:
                    receipt['emulatorSignalAfterTermination'] = late
                    if late['slowHostDeath']:
                        record_failure(receipt, 'slow-host-death', SlowHostDeath(late))
            except BaseException as error:
                record_failure(receipt, 'emulator-cleanup', error)
            if 'emulatorHostExit' in receipt and (receipt.get('appFailure') or {}).get('source') == 'live-logcat':
                receipt['appFailure']['beforeHostExit'] = True
            if ('emulatorHostExit' in receipt or receipt.get('unexpectedEmulatorExit')
                    or 'emulatorSignalAfterTermination' in receipt):
                receipt['hostDiagnostics'] = host_diagnostics(args.evidence / 'host')
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
    parser.add_argument('phase', choices=['formal-run', 'guard', 'formal-artifacts', 'formal', 'source', 'verify',
                                          'reuse', 'probe', 'capture'])
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--run-id')
    parser.add_argument('--source')
    parser.add_argument('--build-tools', type=Path)
    parser.add_argument('--create', action='store_true')
    parser.add_argument('--source-root', type=Path)
    parser.add_argument('--harness', type=Path)
    parser.add_argument('--evidence', type=Path)
    parser.add_argument('--qa-run-id')
    parser.add_argument('--qa-artifact-id')
    parser.add_argument('--package-receipt', type=Path)
    parser.add_argument('--fallback-after', type=Path)
    parser.add_argument('--app-renderer', choices=['impeller', 'skia'], default='impeller')
    parser.add_argument('--host-crash-observer', action='store_true')
    args = parser.parse_args()
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise ValueError('QA commands require an isolated GitHub Actions runner')
    host_observer_enabled(args)
    if args.phase == 'formal-run':
        return formal_run(args)
    if args.phase == 'guard':
        return guard(args)
    if args.phase == 'formal-artifacts':
        return formal_artifacts(args)
    if args.phase == 'source':
        if args.source_root is None:
            parser.error('source requires --source-root')
        return source_check(args)
    if args.build_tools is None:
        parser.error('--build-tools is required for APK readback phases')
    if args.phase == 'formal':
        if args.source_root is None or args.harness is None:
            parser.error('formal requires --source-root and --harness')
        return formal(args)
    if args.phase == 'verify':
        if args.create and args.source_root is None:
            parser.error('verify --create requires --source-root')
        verify(args)
        return 0
    if args.phase == 'reuse':
        if args.evidence is None:
            parser.error('reuse requires --evidence')
        return reuse_package(args)
    return run_probe(args) if args.phase == 'probe' else capture(args)


if __name__ == '__main__':
    raise SystemExit(main())
