"""Prepare and transport immutable native/helper inputs; never rewrite APK identity."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import sys
from lite_release import download_artifact, _gh_json, PromotionError
from verify_apk import RESOURCE_HEALTH_CASES, validate_core_test_receipt, native_identity, validate_native_receipt, CORE_LIBRARY, MEOW_ROOT, CORE_JNI_SYMBOLS, exported_functions, valid_elf64_shared_object, MEOW_SOURCE


HOST_ASSESSMENT_ROOT = Path(__file__).with_name('regexp2-assessment')
HOST_ASSESSMENT_HASHES = {
    'cases.json': '8bba42374ab04a1480715ee867e6cab38071e7bdc0d67e7adc6a366927ba0539',
    'regexp2-v1.12.0.json': '72b01f624f820a1a8dcb30c14f7d03cee4ec9d0a062a359e30474864afa79e49',
}

def identity():
    return dict(sourceRevision=os.environ['GITHUB_SHA'],
                sourceTree=subprocess.check_output(['git', 'rev-parse', 'HEAD^{tree}'], text=True).strip(),
                workflowRun=os.environ['GITHUB_RUN_ID'], runAttempt=int(os.environ['GITHUB_RUN_ATTEMPT']),
                runNumber=int(os.environ['GITHUB_RUN_NUMBER']))


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def assessment_identity(directory):
    directory = Path(directory)
    if directory.is_symlink() or not directory.is_dir():
        raise PromotionError('Pinned regexp2 assessment corpus identity mismatch')
    paths = list(directory.iterdir())
    value = {p.name: digest(p) for p in paths if p.is_file() and not p.is_symlink()}
    if value != HOST_ASSESSMENT_HASHES or any(not p.is_file() or p.is_symlink() for p in paths):
        raise PromotionError('Pinned regexp2 assessment corpus identity mismatch')
    return value


def seal(directory, abi, expected):
    root = Path(directory)
    files = []
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            raise PromotionError('Build transport contains a symlink')
        if path.is_file():
            files.append(dict(path=path.relative_to(root).as_posix(), bytes=path.stat().st_size, sha256=digest(path)))
    if not files or (root / 'TRANSFER.json').exists():
        raise PromotionError('Build transport must be new and nonempty')
    (root / 'TRANSFER.json').write_text(json.dumps(dict(expected, abi=abi, files=files), indent=2)+'\n')


def verify(directory, expected, runtime=False):
    root = Path(directory)
    manifest_name = 'source-manifest.json' if runtime else 'TRANSFER.json'
    value = json.loads((root / manifest_name).read_text())
    if any(value.get(key) != item for key, item in expected.items()):
        raise PromotionError('Build artifact source/run/attempt identity mismatch')
    if runtime and (value.get('abi') != 'x86_64' or value.get('prebuiltApks') is not True or value.get('apkInspectorOutcome') != 'success'):
        raise PromotionError('Runtime artifact was not an inspected prebuilt x86 APK')
    names = set()
    for item in value['files']:
        name = item['path']; relative = PurePosixPath(name)
        if name in names or relative.is_absolute() or '..' in relative.parts or '\\' in name or str(relative) != name:
            raise PromotionError('Invalid artifact manifest path')
        names.add(name)
        path = root / name
        if path.is_symlink() or not path.is_file() or path.stat().st_size != item['bytes'] or digest(path) != item['sha256']:
            raise PromotionError('Artifact payload changed')
    if runtime and not {'apks/app-release.apk', 'apks/app-release-androidTest.apk'}.issubset(names):
        raise PromotionError('Runtime receipt is missing an original APK')
    actual = {p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file()}
    extras = {manifest_name, 'SHA256SUMS'} if runtime else {manifest_name}
    if not names or actual != names | extras or any(p.is_symlink() for p in root.rglob('*')):
        raise PromotionError('Artifact has missing or unexpected files')
    return value


def fetch(name, directory, expected, runtime=False):
    if Path(directory).exists():
        raise PromotionError('Artifact destination already exists')
    run = expected['workflowRun']
    rows = _gh_json(f'repos/{os.environ["GITHUB_REPOSITORY"]}/actions/runs/{run}/artifacts?per_page=100')['artifacts']
    matches = [row for row in rows if row['name'] == name and not row['expired'] and
               row.get('workflow_run', {}).get('id') == int(run) and
               row.get('workflow_run', {}).get('head_sha') == expected['sourceRevision']]
    if len(matches) != 1:
        raise PromotionError('No unique same-run source artifact')
    download_artifact(matches[0]['id'], directory)
    return verify(directory, expected, runtime)


def native_fingerprint(root, target, ndk):
    files = subprocess.check_output(['git', 'ls-files', '-z', 'apps/aerove-lite/meow-native'], cwd=root).decode().split('\0')
    state = [(name, digest(root / name)) for name in sorted(files) if name and not name.endswith('.md')]
    state.extend([('wrapper', digest(Path(__file__))), ('target', target),
                  ('rust', subprocess.check_output(['rustc', '--version'], text=True).strip()),
                  ('ndk', digest(Path(ndk) / 'source.properties'))])
    return hashlib.sha256(json.dumps(state, sort_keys=True).encode()).hexdigest()


def native_build(target, abi, ndk):
    supported = {'arm64-v8a': ('aarch64-linux-android', 183), 'x86_64': ('x86_64-linux-android', 62)}
    if abi not in supported or supported[abi][0] != target:
        raise PromotionError('Native target and ABI mismatch')
    root = Path.cwd(); fingerprint = native_fingerprint(root, target, ndk)
    # Require real ELF/JNI exports and validate the current-run
    # receipt; cached .so files cannot carry an earlier source/run identity forward.
    native_identity()
    subprocess.run(['python3', 'apps/aerove-lite/meow-native/build.py', '--target', target, '--ndk', ndk], check=True)
    if native_fingerprint(root, target, ndk) != fingerprint:
        raise PromotionError('Native inputs changed during build')
    build = root / 'apps/aerove-lite/meow-native/build'
    image = (build / abi / CORE_LIBRARY).read_bytes()
    if not valid_elf64_shared_object(image, supported[abi][1]) or set(CORE_JNI_SYMBOLS) - exported_functions(image):
        raise PromotionError('Meow producer ELF alignment/ABI/JNI exports are incomplete')
    validate_native_receipt(build / (abi + '-receipt.json'), abi, image, workflow=dict(
        workflowSourceSha=os.environ['GITHUB_SHA'], workflowRun=os.environ['GITHUB_RUN_ID'],
        workflowAttempt=os.environ['GITHUB_RUN_ATTEMPT']))
    destination = root / 'apps/aerove-lite/app/src/main/jniLibs' / abi
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(build / abi / CORE_LIBRARY, destination / CORE_LIBRARY)


def stage_helper_assessment(staging, source):
    """Supply the identical authored corpus to production service tests in either ABI path."""
    staging, source = Path(staging).resolve(), Path(source).resolve()
    destination = staging.parent / 'regexp2-assessment'
    if staging.exists() or destination.exists():
        raise PromotionError('Helper staging and assessment corpus must be new')
    if (staging.is_relative_to(source) or source.is_relative_to(staging)
            or destination.is_relative_to(source) or source.is_relative_to(destination)
            or staging.is_relative_to(HOST_ASSESSMENT_ROOT.resolve())
            or destination == HOST_ASSESSMENT_ROOT.resolve()):
        raise PromotionError('Helper staging or assessment corpus overlaps source')
    inputs = assessment_identity(HOST_ASSESSMENT_ROOT)
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(HOST_ASSESSMENT_ROOT, destination)
    return destination, inputs


def android_helper(go, ndk, abi, staging, destination):
    """Run the unchanged Android producer after staging its required exact test inputs."""
    if abi not in {'arm64-v8a', 'x86_64'}:
        raise PromotionError('Unsupported Android helper ABI')
    source = Path.cwd() / 'apps/aerove-lite/regexp2'
    staging, destination = Path(staging).resolve(), Path(destination).resolve()
    if (destination.is_relative_to(source.resolve()) or source.resolve().is_relative_to(destination)
            or destination.is_relative_to(staging) or staging.is_relative_to(destination)
            or destination.is_relative_to(HOST_ASSESSMENT_ROOT.resolve())):
        raise PromotionError('Android helper paths overlap source or each other')
    for name in ('libaerove_filter_helper.so', 'libaerove_filter_jni.so'):
        if (destination / abi / name).exists():
            raise PromotionError('Android helper output must be new')
    spec = importlib.util.spec_from_file_location('lite_android_helper_build', source / 'build.py')
    builder = importlib.util.module_from_spec(spec); spec.loader.exec_module(builder)
    inputs = builder.input_identity(source)
    corpus, assessment_inputs = stage_helper_assessment(staging, source)
    environment = dict(os.environ, GOTOOLCHAIN='local', GOSUMDB='off')
    for key in ('GOOS', 'GOARCH', 'GOFLAGS', 'CC', 'CXX', 'CGO_ENABLED'):
        environment.pop(key, None)
    command = [sys.executable, str(source / 'build.py'), '--go', str(go), '--ndk', str(ndk),
        '--abi', abi, '--staging', str(staging), '--out', str(destination)]
    subprocess.run(command, env=environment, check=True)
    if builder.input_identity(source) != inputs:
        raise PromotionError('Android helper production source inputs changed during build')
    if (assessment_identity(HOST_ASSESSMENT_ROOT) != assessment_inputs
            or assessment_identity(corpus) != assessment_inputs):
        raise PromotionError('Android helper assessment corpus changed during build')
    receipt = staging / 'build-receipt.json'
    original = json.loads(receipt.read_text())
    if original.get('sourceSha256') != inputs['sourceSha256'] or original.get('inputFiles') != inputs['inputFiles']:
        raise PromotionError('Android helper producer receipt source identity mismatch')
    # Preserve the production receipt bytes; transport/package admission verifies outputs.
    return dict(identity(), productionReceiptSha256=digest(receipt), assessmentCorpus=dict(
        source=str(HOST_ASSESSMENT_ROOT), staged=str(corpus), files=assessment_inputs))


def host_helper(go, staging, destination, receipt_path):
    """Build the real JVM helper from the production checksum-verified patched staging."""
    root = Path.cwd(); source = root / 'apps/aerove-lite/regexp2'
    staging, destination, receipt_path = Path(staging).resolve(), Path(destination).resolve(), Path(receipt_path)
    assessment_destination = staging.parent / 'regexp2-assessment'
    if staging.exists() or destination.exists() or receipt_path.exists() or assessment_destination.exists():
        raise PromotionError('Host helper staging, output and receipt must be new')
    if (receipt_path.resolve().is_relative_to(source.resolve())
            or receipt_path.resolve().is_relative_to(HOST_ASSESSMENT_ROOT.resolve())
            or receipt_path.resolve().is_relative_to(staging) or receipt_path.resolve().is_relative_to(destination)
            or staging.is_relative_to(source.resolve()) or destination.is_relative_to(source.resolve())
            or staging.is_relative_to(destination) or destination.is_relative_to(staging)):
        raise PromotionError('Host helper paths overlap source or each other')
    spec = importlib.util.spec_from_file_location('lite_production_helper_build', source / 'build.py')
    builder = importlib.util.module_from_spec(spec); spec.loader.exec_module(builder)
    inputs = builder.input_identity(source)
    fixtures = source / 'agebridge/testdata'
    provenance = json.loads((fixtures / 'provenance.json').read_text())
    fixture_names = {'hybrid.age', 'hybrid.identity', 'profile.yaml', 'wrong.identity', 'x25519.age', 'x25519.identity'}
    fixture_hashes = {}
    for item in provenance['files']:
        name = item['name']; path = fixtures / name
        if (name not in fixture_names or name in fixture_hashes or path.is_symlink()
                or not path.is_file() or digest(path) != item['sha256']):
            raise PromotionError('Pinned age interoperability fixture identity mismatch')
        fixture_hashes[name] = item['sha256']
    if set(fixture_hashes) != fixture_names:
        raise PromotionError('Pinned age interoperability fixtures are incomplete')
    environment = dict(os.environ, GOTOOLCHAIN='local', GOSUMDB='off', CGO_ENABLED='0')
    for key in ('GOOS', 'GOARCH', 'GOFLAGS', 'CC', 'CXX'):
        environment.pop(key, None)
    version = subprocess.check_output([str(go), 'version'], env=environment, text=True).strip()
    if not version.startswith('go version go1.27.1 '):
        raise PromotionError('Pinned Go 1.27.1 is required for the host helper')
    assessment_destination, assessment_inputs = stage_helper_assessment(staging, source)
    subprocess.run([sys.executable, str(source / 'build.py'), '--go', str(go), '--test-only',
        '--staging', str(staging)], env=environment, check=True)
    # build.py validates module sums/source/patches and tests the same service used
    # by Android; compiling directly in the source checkout would bypass that patch.
    destination.mkdir(parents=True)
    commands, outputs = [], {}
    for target in ('hostverify', 'ageverify'):
        executable = destination / (target + ('.exe' if os.name == 'nt' else ''))
        command = [str(go), 'build', '-mod=readonly', '-trimpath', '-o', str(executable), './cmd/' + target]
        subprocess.run(command, cwd=staging, env=environment, check=True)
        if executable.is_symlink() or not executable.is_file() or executable.stat().st_size == 0:
            raise PromotionError('Host helper build did not produce a real executable: ' + target)
        commands.append(command)
        outputs[target] = dict(path=str(executable), bytes=executable.stat().st_size, sha256=digest(executable))
    if builder.input_identity(source) != inputs:
        raise PromotionError('Host helper production source inputs changed during build')
    if (assessment_identity(HOST_ASSESSMENT_ROOT) != assessment_inputs
            or assessment_identity(assessment_destination) != assessment_inputs):
        raise PromotionError('Host helper assessment corpus changed during build')
    value = dict(identity(), go=version, commands=commands, productionBuilderSha256=digest(source / 'build.py'),
        **inputs, outputs=outputs, assessmentCorpus=dict(source=str(HOST_ASSESSMENT_ROOT), staged=str(assessment_destination),
            files=assessment_inputs), ciWrapperSha256=digest(Path(__file__)),
        ageFixtures=dict(path=str(fixtures), files=fixture_hashes,
            provenanceSha256=digest(fixtures / 'provenance.json')))
    receipt_path.parent.mkdir(parents=True, exist_ok=True)
    receipt_path.write_text(json.dumps(value, indent=2)+'\n')
    return value


HOST_LOCK_RESOLUTION_INPUTS = {
    'manifestSha256': 'a1c719f09d0d4433e2de27e1958b936f6f0e70d84d84267003c13289cb682604',
    'expectedLockBeforeSha256': '925bc4bdbda8f7f039759f8c4d5d862ed3263c664d6d10653d389d0950bbafeb',
    'overlaySha256': 'cccc8f33ae1461778738f0beb80020375a3e82c00ed9e6ec523fec07b5a217e9',
    'coreTestLockSha256': '96b509814a2d833c39ac23c1cbee62a38e401f420ec1cf8f72a7738a71df6c58',
    'sourceSpecSha256': 'f8f76d22b27f779f5b5d06a07366cd2597404fc01aa302745230cf9ee8c1220c',
}


HOST_LOCK_RESOLUTION_PROVIDER_INPUTS = {
    'manifestSha256': '2092489392fd24e9770908344f171ddf65c97f7e273bfc814b743202746e8417',
    'expectedLockBeforeSha256': '8760f0367ca6596d3ac9ecf6436326b32f9ae7226adc753c58fce26ee916b0bd',
    'overlaySha256': 'ec903175c96374d1ed7ebc82e1476ccc787af6912ff892090635d731308c8e6d',
    'coreTestLockSha256': '96b509814a2d833c39ac23c1cbee62a38e401f420ec1cf8f72a7738a71df6c58',
    'sourceSpecSha256': 'f8f76d22b27f779f5b5d06a07366cd2597404fc01aa302745230cf9ee8c1220c',
}


HOST_LOCK_RESOLUTION_HISTORY_INPUTS = {
    'manifestSha256': 'b56e36493b3eae608be987f3f8a60f984500f5d0570c5709f1122e31fabb9802',
    'expectedLockBeforeSha256': '821af7e07190269a21f323dc59b86bb667da48de831641d775162cbbbd5c6fb0',
    'overlaySha256': 'afdcf45c8659dd9cc705c075ac7b81b38f6927665beb0f48390646d39c9443bf',
    'coreTestLockSha256': '96b509814a2d833c39ac23c1cbee62a38e401f420ec1cf8f72a7738a71df6c58',
    'sourceSpecSha256': 'f8f76d22b27f779f5b5d06a07366cd2597404fc01aa302745230cf9ee8c1220c',
}


def _host_resolution_inputs(phase, root, environment, *, check_seed):
    environment = os.environ if environment is None else environment
    conflicts = ('QA84_ONLY', 'QA79_ONLY', 'CLIENT_PREFLIGHT', 'PUBLISH_RELEASE', 'NATIVE_PREFLIGHT',
                 'NATIVE_TEST_SERIAL', 'NATIVE_UNDERLAY_TESTS', 'NATIVE_TEST_STACKS')
    if (phase != 'host' or environment.get('REQUEST_EVENT') != 'workflow_dispatch'
            or environment.get('MEOW_HOST_ONLY') != 'true'
            or any(environment.get(key) != 'false' for key in conflicts)
            or environment.get('NATIVE_TEST_FILTER') != '' or environment.get('DIAGNOSTIC_TARGET') != 'none'):
        raise PromotionError('Pending lock resolution requires explicit exclusive meow_host_only phase=host')
    # Load the same source-owned policy/command definitions used by the real producer.
    sys.path.insert(0, str(MEOW_ROOT))
    try:
        from build_policy import load_policy, owned_file, decide_plan, feature_boundary_command
        from core_tests import entries
    finally:
        sys.path.pop(0)
    root = Path(root)
    owned_file(root, 'Cargo.lock')
    owned_file(root, 'Cargo.toml')
    policy = load_policy(root, check_resolution_seed=check_seed)
    if policy is None or policy['resolveLock'] is not True:
        raise PromotionError('Pending host admission requires explicit resolveLock=true policy')
    if policy.get('coreTests') == 'policy-runtime-resource-health-v1':
        contract = (HOST_LOCK_RESOLUTION_INPUTS, 26, 75)
    elif policy.get('coreTests') == 'policy-runtime-provider-reader-v1':
        contract = ((HOST_LOCK_RESOLUTION_HISTORY_INPUTS, 32, 179)
                    if policy['manifestSha256'] == HOST_LOCK_RESOLUTION_HISTORY_INPUTS['manifestSha256']
                    else (HOST_LOCK_RESOLUTION_PROVIDER_INPUTS, 31, 177))
    else:
        raise PromotionError('Pending host resolution requires an approved complete Core suite')
    inputs, core_count, core_hits = contract
    for key, expected in inputs.items():
        if key != 'sourceSpecSha256' and policy.get(key) != expected:
            raise PromotionError('Unapproved host lock-resolution input: ' + key)
    spec_path = owned_file(root, 'source.json')
    spec = json.loads(spec_path.read_text())
    if (digest(spec_path) != inputs['sourceSpecSha256']
            or spec.get('commit') != MEOW_SOURCE or spec.get('tag') != 'v0.26.1'
            or spec.get('repository') != 'https://github.com/meow-rs/meow-rs'
            or spec.get('archiveSha256') != '4273e027c5db3f7c052b255730f29165908772bf417ec89d9ab54478f81dcd9a'):
        raise PromotionError('Pending host resolution requires the fixed Meow source')
    if digest(owned_file(root, 'core-tests.lock')) != policy['coreTestLockSha256']:
        raise PromotionError('Pending host resolution changed the accepted Core test lock')
    if len(policy['targets']) != 42 or feature_boundary_command(policy) is None:
        raise PromotionError('Pending host resolution requires the complete approved Native/Core suite')
    core = entries(policy['coreTests'])
    if len(core) != core_count or sum(entry['expectedPass'] for entry in core) != core_hits:
        raise PromotionError('Pending host resolution changed the authored Core command contract')
    plan = decide_plan(True, spec['archiveSha256'], 'workflow_dispatch', phase, False, resolve_requested=True)
    return policy, spec, plan, contract


def host_lock_resolution_plan(phase, root=MEOW_ROOT, environment=None):
    """Plan the approved pending host resolution; do not execute or accept a lock."""
    policy, spec, plan, contract = _host_resolution_inputs(phase, root, environment, check_seed=True)
    inputs, core_count, core_hits = contract
    return dict(schema=1, planOnly=True, phase=phase, lockAcceptance='pending', acceptedLock=False,
                **plan, policySha256=policy['policySha256'], manifestSha256=policy['manifestSha256'],
                seedLockSha256=policy['expectedLockBeforeSha256'], coreTestLockSha256=policy['coreTestLockSha256'],
                sourceSpecSha256=digest(Path(root) / 'source.json'), meowSource=spec['commit'], nativeTargets=policy['targets'],
                nativeTargetCount=42, coreCommandCount=core_count, expectedCoreHits=core_hits,
                command=['python3', 'apps/aerove-lite/meow-native/build.py', '--resolve-lock'])


def host_lock_resolution_result(plan_path, phase, root=MEOW_ROOT, environment=None):
    """Read back pending producer evidence for archive only; never use the APK acceptance gate."""
    environment = os.environ if environment is None else environment
    policy, spec, expected_plan, contract = _host_resolution_inputs(phase, root, environment, check_seed=False)
    inputs, core_count, core_hits = contract
    root = Path(root)
    plan_path = Path(plan_path)
    if plan_path.is_symlink() or not plan_path.is_file():
        raise PromotionError('Pending host plan must be an existing regular file')
    plan = json.loads(plan_path.read_text())
    current = identity()
    expected = dict(planOnly=True, phase='host', acceptedLock=False, lockAcceptance='pending',
                    policySha256=policy['policySha256'], manifestSha256=policy['manifestSha256'],
                    seedLockSha256=policy['expectedLockBeforeSha256'], workflowIdentity=current,
                    coreTestLockSha256=policy['coreTestLockSha256'], nativeTargets=policy['targets'],
                    sourceSpecSha256=inputs['sourceSpecSha256'], meowSource=MEOW_SOURCE,
                    nativeTargetCount=42, coreCommandCount=core_count, expectedCoreHits=core_hits,
                    command=['python3', 'apps/aerove-lite/meow-native/build.py', '--resolve-lock'])
    if (any(plan.get(key) != value for key, value in expected.items())
            or plan.get('acceptedLock') is not False or plan.get('planOnly') is not True
            or any(plan.get(key) != value for key, value in expected_plan.items())):
        raise PromotionError('Pending host plan identity changed before readback')
    from build_policy import owned_file, feature_boundary_command
    receipt_path = owned_file(root, 'build/host-receipt.json')
    receipt = json.loads(receipt_path.read_text())
    before = owned_file(root, 'build/lock-before-resolution.lock')
    resolved = digest(owned_file(root, 'Cargo.lock'))
    if digest(before) != policy['expectedLockBeforeSha256']:
        raise PromotionError('Pending host lost the original approved resolution seed')
    command = ['cargo', 'test', '--locked', '--no-fail-fast']
    for target in policy['targets']:
        command += ['--lib'] if target == 'lib' else ['--test', target]
    command += ['--', '--test-threads=1', '--nocapture']
    identity_fields = dict(workflowSourceSha=current['sourceRevision'], workflowRun=current['workflowRun'], workflowAttempt=current['runAttempt'])
    if (any(str(receipt.get(key)) != str(value) for key, value in identity_fields.items())
            or receipt.get('acceptedLock') is not True or receipt.get('resolveLockRequested') is not True or receipt.get('bootstrapRequested') is not False
            or receipt.get('androidTarget') is not None or type(receipt.get('commandExit')) is not int or receipt['commandExit'] != 0
            or receipt.get('command') != command or receipt.get('representativeTargets') != policy['targets']
            or receipt.get('buildPolicySha256') != policy['policySha256']
            or receipt.get('bridgeManifestSha256') != policy['manifestSha256']
            or receipt.get('declaredSourceSpecSha256') != inputs['sourceSpecSha256']
            or receipt.get('lockSha256Before') != policy['expectedLockBeforeSha256']
            or receipt.get('metadataInputLockSha256') != policy['expectedLockBeforeSha256']
            or receipt.get('metadataCommand') != ['cargo', 'metadata', '--format-version', '1']
            or type(receipt.get('metadataExit')) is not int or receipt['metadataExit'] != 0
            or receipt.get('resolvedLockSha256') != resolved or receipt.get('lockSha256After') != resolved
            or receipt.get('lockUnchangedDuringLockedCommand') is not True
            or receipt.get('featureBoundaryCommand') != feature_boundary_command(policy)
            or type(receipt.get('featureBoundaryExit')) is not int or receipt['featureBoundaryExit'] != 0):
        raise PromotionError('Pending host producer did not preserve the explicit full host execution')
    source = receipt.get('source', {})
    overlay = policy['overlayMetadata']
    if any(source.get(key) != value for key, value in dict(commit=MEOW_SOURCE, tag='v0.26.1', archiveSha256=spec['archiveSha256'],
            overlaySha256=policy['overlaySha256'], patchSha256=overlay['patchSha256'], fileManifestSha256=overlay['adaptedFileManifestSha256']).items()):
        raise PromotionError('Pending host producer source identity changed')
    core = receipt.get('coreTests', {})
    commands = core.get('commands', [])
    if (core.get('mode') != policy['coreTests'] or core.get('accepted') is not True or core.get('upstreamLockRestored') is not True
            or core.get('seedSha256') != policy['coreTestLockSha256'] or core.get('resolvedLockSha256') != policy['coreTestLockSha256']
            or not isinstance(commands, list) or len(commands) != core_count
            or any(type(entry.get('exit')) is not int or entry['exit'] != 0 or type(entry.get('passed')) is not int
                   or entry['passed'] != entry.get('expectedPass') for entry in commands)
            or sum(entry['passed'] for entry in commands) != core_hits
            or type(core.get('compileCheck', {}).get('exit')) is not int or core['compileCheck']['exit'] != 0):
        raise PromotionError('Pending host Core evidence is incomplete or its lock drifted')
    if policy['coreTests'] == 'policy-runtime-provider-reader-v1':
        if (receipt.get('addedNativeTests') != RESOURCE_HEALTH_CASES
                or not str(receipt.get('toolchain', '')).startswith('rustc 1.98.1 ')
                or source.get('archiveHashBootstrapped') is not False):
            raise PromotionError('Pending provider resolution lacks the fixed Native/toolchain proof')
        validate_core_test_receipt(core, policy, root)
    return dict(schema=1, archiveOnly=True, lockAcceptance='pending', acceptedLock=False, workflowIdentity=current,
                readbackScope='pending-producer-shape-and-locks-only', planSha256=digest(plan_path), producerReceiptSha256=digest(receipt_path), seedLockSha256=digest(before),
                resolvedLockSha256=resolved, nativeTargetCount=42, coreCommandCount=core_count,
                producerAcceptedLock=receipt.get('acceptedLock'), requiresCommittedLockAndPolicyAcceptance=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['seal', 'fetch', 'runtime', 'native', 'admission', 'host', 'host-helper', 'android-helper', 'host-resolution-result'])
    parser.add_argument('--directory'); parser.add_argument('--destination'); parser.add_argument('--name'); parser.add_argument('--abi')
    parser.add_argument('--target'); parser.add_argument('--ndk')
    parser.add_argument('--go'); parser.add_argument('--receipt')
    parser.add_argument('--host-lock-resolution', action='store_true')
    parser.add_argument('--phase')
    args = parser.parse_args()
    if args.action == 'host-resolution-result':
        if (not args.host_lock_resolution or args.phase != 'host' or not args.receipt
                or any((args.directory, args.destination, args.name, args.abi, args.target, args.ndk, args.go))):
            parser.error('Pending host readback requires --host-lock-resolution --phase host --receipt PLAN')
        result = host_lock_resolution_result(args.receipt, args.phase)
        subprocess.run(['git', 'diff', '--exit-code', 'HEAD', '--', 'apps/aerove-lite/meow-native',
                        ':(exclude)apps/aerove-lite/meow-native/Cargo.lock'], check=True)
        print(json.dumps(result))
        return
    if args.host_lock_resolution or args.phase is not None:
        if not args.host_lock_resolution or args.action != 'admission' or args.phase != 'host':
            parser.error('Lock-resolution planning is only admission --host-lock-resolution --phase host')
        if any((args.directory, args.destination, args.name, args.abi, args.target, args.ndk, args.go, args.receipt)):
            parser.error('Pending host planning does not accept build, ABI or transfer arguments')
        plan = host_lock_resolution_plan(args.phase)
        subprocess.run(['git', 'diff', '--exit-code', 'HEAD', '--', 'apps/aerove-lite/meow-native'], check=True)
        plan['workflowIdentity'] = identity()
        print(json.dumps(plan))
        return
    if args.action == 'android-helper':
        if not all((args.go, args.ndk, args.abi, args.directory, args.destination)):
            parser.error('Android helper requires --go, --ndk, --abi, --directory and --destination')
        print(json.dumps(android_helper(args.go, args.ndk, args.abi, args.directory, args.destination)))
        return
    if args.action == 'host-helper':
        if not all((args.go, args.directory, args.destination, args.receipt)):
            parser.error('Host helper requires --go, --directory, --destination and --receipt')
        print(json.dumps(host_helper(args.go, args.directory, args.destination, args.receipt)))
        return
    if args.action in {'admission', 'host'}:
        native_identity()
        subprocess.run(['git', 'diff', '--exit-code', 'HEAD', '--', 'apps/aerove-lite/meow-native'], check=True)
        if args.action == 'host':
            validate_native_receipt(MEOW_ROOT / 'build/host-receipt.json', workflow=dict(
                workflowSourceSha=os.environ['GITHUB_SHA'], workflowRun=os.environ['GITHUB_RUN_ID'],
                workflowAttempt=os.environ['GITHUB_RUN_ATTEMPT']))
        return
    if args.action == 'native':
        native_build(args.target, args.abi, args.ndk); return
    expected = identity()
    if args.action == 'seal':
        if args.abi not in {'arm64-v8a', 'x86_64'}: parser.error('ABI required')
        seal(args.directory, args.abi, expected)
    else:
        if args.action == 'fetch':
            if args.abi not in {'arm64-v8a', 'x86_64'}: parser.error('ABI required')
            expected['abi'] = args.abi
        value = fetch(args.name, args.destination, expected, args.action == 'runtime')
        if args.action == 'runtime':
            for original, target in [('app-release.apk', 'release'), ('app-release-androidTest.apk', 'androidTest/release')]:
                source = Path(args.destination) / 'apks' / original
                if f'apks/{original}' not in {item['path'] for item in value['files']}: raise PromotionError('Missing prebuilt APK')
                destination = Path('apps/aerove-lite/app/build/outputs/apk') / target / original
                destination.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(source, destination)

if __name__ == '__main__': main()
