"""Same-run transport of immutable build inputs; never builds or rewrites APK identity."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
from lite_release import download_artifact, _gh_json, PromotionError
from verify_apk import native_identity, validate_native_receipt, CORE_LIBRARY, MEOW_ROOT, CORE_JNI_SYMBOLS, exported_functions, valid_elf64_shared_object


def identity():
    return dict(sourceRevision=os.environ['GITHUB_SHA'],
                sourceTree=subprocess.check_output(['git', 'rev-parse', 'HEAD^{tree}'], text=True).strip(),
                workflowRun=os.environ['GITHUB_RUN_ID'], runAttempt=int(os.environ['GITHUB_RUN_ATTEMPT']),
                runNumber=int(os.environ['GITHUB_RUN_NUMBER']))


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['seal', 'fetch', 'runtime', 'native', 'admission', 'host'])
    parser.add_argument('--directory'); parser.add_argument('--destination'); parser.add_argument('--name'); parser.add_argument('--abi')
    parser.add_argument('--target'); parser.add_argument('--ndk')
    args = parser.parse_args()
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