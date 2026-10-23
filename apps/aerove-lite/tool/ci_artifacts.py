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
from verify_apk import native_identity, validate_native_receipt, CORE_LIBRARY, MEOW_ROOT, CORE_JNI_SYMBOLS, exported_functions, valid_elf64_shared_object


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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['seal', 'fetch', 'runtime', 'native', 'admission', 'host', 'host-helper', 'android-helper'])
    parser.add_argument('--directory'); parser.add_argument('--destination'); parser.add_argument('--name'); parser.add_argument('--abi')
    parser.add_argument('--target'); parser.add_argument('--ndk')
    parser.add_argument('--go'); parser.add_argument('--receipt')
    args = parser.parse_args()
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