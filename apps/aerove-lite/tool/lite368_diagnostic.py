"""Fixed #368 original APK diagnosis with a separately identified cloud harness."""
import argparse
import json
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import shutil
import stat
import subprocess
import sys
import zipfile

import ci_artifacts as artifacts
import lite_release as release
from flutter_android_qa import PACKAGE, save, sha, validate_binding

IDENTITY = dict(sourceRevision='022b1d30f60c4f380938ea99a3c1c21d96c7321d',
                sourceTree='cc7f33ad8522899245e69cd988e5f9bb5482d2af',
                workflowRun='42912128007', runAttempt=1, runNumber=368)
ARCHIVE = dict(id=13811372268, name='AEROVE-Lite-runtime-contract-attempt-1',
               size_in_bytes=59906408,
               digest='sha256:ab263461c0bc38e292d730add9b73308ec97272876946bde4436e2fd24e24d6d')
MANIFEST = '9f9c1aed7d919a52da945465a2a7a31cd7fc3850b8db7d8fc2afef0eafa8c2ba'
INFO = '43e04b5ce4a25cfdb9227dd866cc67abd2f3b69c99ea27eeef7baf768d825e2c'
APKS = {
    'app-release.apk': 'ede700b63bf127a397d0b91c724c555b6f4a6cb44ee5b84ce18809c387ae3cf4',
    'app-release-androidTest.apk': 'b46234a84d60aa11861f5a7363b15f3347f60ce4876a9ab78e70f9536c9c4aff',
    'platform-qa-release.apk': '83527327e3345e2958ef278b11bc225bead18f9a7506ffe4b73257ea8bfd5d18',
}
SIGNER = '09f9e021ddac36ead015d27e073b673e79fdf4e02605c402a505781963b1a4d8'
OPT_IN = 'AEROVE_LITE368_DIAGNOSTIC'
BRANCH = 'test/368-same-apk-diagnostics'
WORKFLOW = '.github/workflows/aerove-lite368-diagnostic.yaml'
PHASES = ('flutter-ui', 'flutter-platform', 'flutter-full')
FALSE_FIELDS = ('functionalAcceptance', 'accepted', 'aggregateAcceptance', 'promotionAccepted', 'fullAcceptance',
                'visualApproved', 'performanceAccepted', 'releaseAccepted')
APK_ROOT = Path('apps/aerove-lite/app/build/outputs/apk')


def require(value, message):
    if not value:
        raise ValueError(message)


def enabled():
    value = os.environ.get(OPT_IN)
    if value is None:
        return False
    require(value == '1' and os.environ.get('GITHUB_REPOSITORY') == release.REPO and
            os.environ.get('GITHUB_EVENT_NAME') in ('push', 'workflow_dispatch') and
            os.environ.get('GITHUB_REF') == 'refs/heads/' + BRANCH and
            os.environ.get('GITHUB_WORKFLOW_REF') == release.REPO + '/' + WORKFLOW + '@refs/heads/' + BRANCH and
            os.environ.get('AEROVE_GUEST_ANGLE') == '1', 'Fixed368 requires its isolated cloud workflow/branch/graphics')
    forbidden = ('AEROVE_ORIGINAL', 'AEROVE_LITE236', 'AEROVE_LITE257', 'AEROVE_LITE265',
                 'AEROVE_LITE290', 'AEROVE_LITE328', 'AEROVE_LITE333', 'AEROVE_LITE343',
                 'AEROVE_GFXSTREAM', 'AEROVE_EMULATOR_SIGILL', 'AEROVE_QA79', 'AEROVE_BETA14')
    require(not any(value for key, value in os.environ.items() if key.startswith(forbidden)),
            'Fixed368 cannot mix historical or modified graphics inputs')
    require(not any(os.environ.get(key) for key in ('VK_ICD_FILENAMES', 'VK_DRIVER_FILES',
            'VK_ADD_DRIVER_FILES', 'MESA_LOADER_DRIVER_OVERRIDE', 'AEROVE_QA_FINAL_PLAN_CAPTURE')) and
            not any(value for key, value in os.environ.items()
                    if key.startswith('AEROVE_CURRENT_RENDERER') and key != 'AEROVE_CURRENT_RENDERER_PROOF'),
            'Fixed368 cannot mix renderer or scenario overrides')
    return True


def harness_identity():
    identity = artifacts.identity()
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    require(identity['sourceRevision'] == head and identity != IDENTITY and
            identity['workflowRun'] != IDENTITY['workflowRun'], 'Original app and new harness identities must remain separate')
    return identity


def validate_owner(proof, phase):
    require(enabled() and phase in PHASES, 'Fixed368 permits only explicit Flutter diagnostic phases')
    require(proof.get('identity') == IDENTITY and proof.get('originalAppIdentity') == IDENTITY and
            proof.get('harnessIdentity') == harness_identity() and proof.get('original368Probe') is True and
            proof.get('diagnosticOnly') is True and all(proof.get(key) is False for key in FALSE_FIELDS),
            'Fixed368 owner lost original app/new harness separation or claimed acceptance')


def check_api(run, jobs, row):
    require(run.get('id') == int(IDENTITY['workflowRun']) and run.get('run_attempt') == 1 and
            run.get('run_number') == 368 and run.get('head_sha') == IDENTITY['sourceRevision'] and
            run.get('head_branch') == 'fix/362-global-integration' and
            run.get('path') == '.github/workflows/aerove-lite.yaml' and
            run.get('repository', {}).get('full_name') == release.REPO and
            run.get('status') == 'completed' and run.get('conclusion') == 'failure', 'Original368 run changed')
    rows = jobs.get('jobs', [])
    require(jobs.get('total_count') == len(rows) == len({job['id'] for job in rows}),
            'Original368 producer inventory is incomplete')
    package = next((job for job in rows if job['id'] == 130188880885), {})
    require(package.get('name') == 'build (x86_64) / package' and package.get('run_id') == run['id'] and
            package.get('run_attempt') == 1 and package.get('head_sha') == IDENTITY['sourceRevision'] and
            package.get('status') == 'completed' and package.get('conclusion') == 'success',
            'Original368 x86 package producer did not succeed')
    require(all(row.get(key) == value for key, value in ARCHIVE.items()) and row.get('expired') is False and
            row.get('url') == 'https://api.github.com/repos/' + release.REPO + '/actions/artifacts/' + str(ARCHIVE['id']) and
            row.get('workflow_run') == dict(id=run['id'], repository_id=1429390079,
                head_repository_id=1429390079, head_branch='fix/362-global-integration',
                head_sha=IDENTITY['sourceRevision']), 'Original368 archive API differs')


def extract_archive(archive, directory):
    require(not directory.exists() and not directory.is_symlink(), 'Never replace original368 runtime inputs')
    require(not archive.is_symlink() and archive.stat().st_size == ARCHIVE['size_in_bytes'] and
            'sha256:' + sha(archive) == ARCHIVE['digest'], 'Original368 archive size/hash changed')
    with zipfile.ZipFile(archive) as package:
        entries = package.infolist()
        require(entries and len({entry.filename for entry in entries}) == len(entries) and
                sum(entry.file_size for entry in entries) <= 512 * 1024 * 1024, 'Unsafe ZIP inventory/expansion')
        for entry in entries:
            posix, windows = PurePosixPath(entry.filename), PureWindowsPath(entry.filename)
            require(not posix.is_absolute() and not windows.drive and not windows.is_absolute() and
                    '\\' not in entry.filename and all(part not in ('', '.', '..') and ':' not in part
                    for part in entry.filename.rstrip('/').split('/')) and
                    stat.S_IFMT(entry.external_attr >> 16) in (0, stat.S_IFREG, stat.S_IFDIR), 'Unsafe ZIP member')
        require(package.testzip() is None, 'Original368 archive CRC failed')
        package.extractall(directory)


def runtime(directory, installed=True):
    require(enabled(), 'Explicit original368 diagnostic required')
    return verified_runtime(directory, installed)


def verified_runtime(directory, installed=True):
    """Validate frozen bytes without granting emulator or scenario execution."""
    directory = Path(directory)
    metadata = release._json(directory.with_suffix('.api.json'))
    check_api(metadata['run'], metadata['jobs'], metadata['artifact'])
    release.product_source.bind_archive(directory.with_suffix('.zip'), directory, ARCHIVE)
    manifest = artifacts.verify(directory, IDENTITY, runtime=True)
    require(len(manifest['files']) == 76 and sha(directory / 'source-manifest.json') == MANIFEST and
            sha(directory / 'BUILD_INFO.json') == INFO, 'Original368 seal or BUILD_INFO changed')
    info = release._json(directory / 'BUILD_INFO.json')
    expected = dict(source=IDENTITY['sourceRevision'], tree=IDENTITY['sourceTree'], product='AEROVE Lite',
        channel='preview', application_id=PACKAGE, abi='x86_64', min_sdk=33, version='0.2.0-beta.8',
        version_code=40, run_number=368, run_attempt=1, launcher_activity=PACKAGE + '.FlutterMainActivity',
        workflow='https://github.com/' + release.REPO + '/actions/runs/' + IDENTITY['workflowRun'],
        apk_sha256=APKS['app-release.apk'], signer_sha256=SIGNER)
    require(all(info.get(key) == value for key, value in expected.items()), 'Original368 package metadata differs')
    for name, digest in APKS.items():
        require(sha(directory / 'apks' / name) == digest, 'Original368 APK differs')
        if installed and name != 'platform-qa-release.apk':
            location = 'release' if name == 'app-release.apk' else 'androidTest/release'
            target = APK_ROOT / location / name
            require(not target.is_symlink() and sha(target) == digest, 'Staged original368 APK differs')
    from verify_apk import verify_bundled_script_apk
    return dict(identity=IDENTITY, originalAppIdentity=IDENTITY, harnessIdentity=harness_identity(),
        apks={key: value for key, value in APKS.items() if key != 'platform-qa-release.apk'},
        runtimeManifestSha256=MANIFEST, apkInspectorOutcome=manifest['apkInspectorOutcome'],
        bundledMeowScript=verify_bundled_script_apk(directory / 'apks/app-release.apk'),
        original368Probe=True, diagnosticOnly=True, appCompiled=False, **dict.fromkeys(FALSE_FIELDS, False))


def verify_signatures(directory, apksigner):
    from verify_apk import signing_certificate
    for name in APKS:
        output = subprocess.check_output([str(apksigner), 'verify', '--verbose', '--print-certs',
            str(Path(directory) / 'apks' / name)], stderr=subprocess.STDOUT, timeout=30).decode()
        require(signing_certificate(output) == SIGNER, 'Original368 APK signature differs')


def fetch(directory):
    require(enabled(), 'Explicit original368 diagnostic required')
    return verified_fetch(directory)


def verified_fetch(directory):
    """Fetch only the fixed public artifact; execution admission belongs to its caller."""
    require(not os.environ.get('AEROVE_CURRENT_RENDERER_PROOF'), 'Fresh original368 diagnostic owner required')
    base = 'repos/' + release.REPO + '/actions/'
    metadata = dict(run=release._gh_json(base + 'runs/' + IDENTITY['workflowRun']),
        jobs=release._gh_json(base + 'runs/' + IDENTITY['workflowRun'] + '/attempts/1/jobs?per_page=100'),
        artifact=release._gh_json(base + 'artifacts/' + str(ARCHIVE['id'])))
    check_api(metadata['run'], metadata['jobs'], metadata['artifact'])
    directory = Path(directory)
    archive = directory.with_suffix('.zip')
    require(not any(path.exists() or path.is_symlink() for path in
            (directory, archive, directory.with_suffix('.api.json'), directory.with_suffix('.fetch.json'))),
            'Never overwrite original368 inputs')
    release._gh_binary(base + 'artifacts/' + str(ARCHIVE['id']) + '/zip', archive)
    extract_archive(archive, directory)
    save(directory.with_suffix('.api.json'), metadata)
    proof = verified_runtime(directory, installed=False)
    verify_signatures(directory, Path(os.environ['ANDROID_HOME']) / 'build-tools/37.0.0/apksigner')
    for name, location in (('app-release.apk', 'release'), ('app-release-androidTest.apk', 'androidTest/release')):
        target = APK_ROOT / location / name
        require(not target.exists() and not target.is_symlink(), 'Never overwrite staged original368 APK')
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(directory / 'apks' / name, target)
    save(directory.with_suffix('.fetch.json'), proof)


def original_app_inputs(directory, proof):
    """Read original APK inputs; a new runner must receive its own separate admission."""
    require(proof.get('identity') == IDENTITY and proof.get('originalAppIdentity') == IDENTITY and
            proof.get('harnessIdentity') == harness_identity(), 'Original368 dual identity required')
    directory = Path(directory)
    inputs = dict(apk=(APK_ROOT / 'release/app-release.apk').resolve(),
        test_apk=(APK_ROOT / 'androidTest/release/app-release-androidTest.apk').resolve(),
        build_info=(directory / 'BUILD_INFO.json').resolve())
    info = release._json(inputs['build_info'])
    require(validate_binding(inputs['apk'], inputs['test_apk'], info, dict(proof, owner='emulator_current.py')) == IDENTITY,
            'Original368 app and original runner binding differs')
    return inputs


def original_inputs(directory, proof):
    validate_owner(proof, 'flutter-ui')
    inputs = original_app_inputs(directory, proof)
    info = release._json(inputs['build_info'])
    from android_acceptance_verify import platform_binding
    inputs['platform_apk'] = platform_binding(directory, info).resolve()
    require(sha(inputs['platform_apk']) == APKS['platform-qa-release.apk'], 'Original368 platform runner changed')
    return inputs


def command(args, inputs, output):
    require(enabled() and args.phase in PHASES and inputs is not None and 'platform_apk' in inputs,
            'Fixed368 requires original inputs and an explicit diagnostic phase')
    sdk = Path(os.environ['ANDROID_HOME'])
    result = [sys.executable, str(Path(__file__).resolve()), 'run', '--phase', args.phase,
              '--serial', 'emulator-5554', '--output', str(output),
              '--adb', str(sdk / 'platform-tools/adb'), '--apksigner', str(sdk / 'build-tools/37.0.0/apksigner')]
    for key in ('apk', 'test_apk', 'platform_apk', 'build_info'):
        result += ['--' + key.replace('_', '-'), str(inputs[key])]
    return result


def run(args):
    from android_acceptance import Acceptance
    from emulator_current import validate_renderer_proof
    from flutter_android_qa import idle
    proof = validate_renderer_proof(Path(os.environ['AEROVE_CURRENT_RENDERER_PROOF']))
    validate_owner(proof, args.phase)
    args.output.mkdir(parents=True, exist_ok=False)
    idle()
    producer = Acceptance(args)
    producer.receipt.update(originalAppIdentity=IDENTITY, harnessIdentity=harness_identity(),
        diagnosticOnly=True, original368Probe=True, appCompiled=False, **dict.fromkeys(FALSE_FIELDS, False))
    return producer.run()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest='action', required=True)
    fetch_parser = actions.add_parser('fetch')
    fetch_parser.add_argument('--directory', type=Path, required=True)
    run_parser = actions.add_parser('run')
    run_parser.add_argument('--phase', choices=PHASES, required=True)
    run_parser.add_argument('--serial', required=True)
    for name in ('apk', 'test-apk', 'platform-apk', 'build-info', 'adb', 'apksigner', 'output'):
        run_parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    return fetch(args.directory) if args.action == 'fetch' else run(args)


if __name__ == '__main__':
    raise SystemExit(main())
