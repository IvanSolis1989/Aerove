"""Fixed run84 original-runtime Android diagnosis; never builds or promotes APKs."""
import argparse
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import zipfile

import ci_artifacts as artifacts
import lite_release as release
from emulator_qa79 import GPU_BACKEND, RENDERER_LABEL, renderer_evidence, sha
from verify_apk import verify_identity

ORIGINAL = dict(sourceRevision='5d2c2bb550ab54ae485837c1dec55e961e273dab',
                sourceTree='8a9334db3c6d332e8ce098c1608c1ce613499ea1',
                workflowRun='40088361338', runNumber=84, runAttempt=1)
ARTIFACT_ID = 12558172679
ZIP_SHA256 = 'df91550113ab3c74034ce9ccb622f21747dbba3617ce7aa50efe95a38a4e70ed'
MANIFEST_SHA256 = 'c094bdd43d15a9d1a9716fcd86e7a544d8286d16fb545915820c297a47271910'
APKS = {'app-release.apk': '2b2357a6df96a3035bc6299187decfdf61702da18133886196f0ff5841333c6a',
        'app-release-androidTest.apk': '725e18e231eca0cd0ee6a8839a3a4f0b415a026eb3ac107c8ace441621875162'}
PRODUCT = dict(applicationId='dev.aerove.lite', version='0.1.0-beta.10', versionCode='10',
               signerSha256='09f9e021ddac36ead015d27e073b673e79fdf4e02605c402a505781963b1a4d8')
APK_ROOT = Path('apps/aerove-lite/app/build/outputs/apk')


def checked_archive(archive, destination):
    if sha(archive) != ZIP_SHA256:
        raise ValueError('Original84 artifact ZIP digest changed')
    if destination.exists():
        raise ValueError('Original84 runtime destination must be new')
    with zipfile.ZipFile(archive) as source:
        if source.testzip() is not None:
            raise ValueError('Original84 ZIP CRC failure')
        names = source.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate original84 ZIP entries')
        for name in names:
            path = PurePosixPath(name)
            mode = source.getinfo(name).external_attr >> 16
            if path.is_absolute() or '..' in path.parts or '\\' in name or stat.S_ISLNK(mode):
                raise ValueError('Unsafe original84 ZIP entry')
        source.extractall(destination)


def validate_original_files(directory):
    directory = Path(directory)
    if sha(directory / 'source-manifest.json') != MANIFEST_SHA256:
        raise ValueError('Original84 runtime manifest bytes changed')
    artifacts.verify(directory, ORIGINAL, runtime=True)
    release._validate_manifest(directory, ORIGINAL['sourceRevision'], ORIGINAL['sourceTree'],
                               ORIGINAL['workflowRun'], 1, 84)
    for name, digest in APKS.items():
        if sha(directory / 'apks' / name) != digest:
            raise ValueError('Original84 APK bytes changed')
    info = release._json(directory / 'BUILD_INFO.json')
    expected = dict(product='AEROVE Lite', source=ORIGINAL['sourceRevision'], tree=ORIGINAL['sourceTree'],
                    workflow='https://github.com/' + release.REPO + '/actions/runs/' + ORIGINAL['workflowRun'],
                    run_number=84, run_attempt=1, application_id=PRODUCT['applicationId'],
                    version=PRODUCT['version'], version_code=10, signer_sha256=PRODUCT['signerSha256'],
                    channel='preview', abi='x86_64', apk_sha256=APKS['app-release.apk'])
    if any(info.get(key) != value for key, value in expected.items()):
        raise ValueError('Original84 BUILD_INFO identity mismatch')
    return info


def inspection_command(command, reports, name, kind):
    """Persist each actual reader result before any identity parsing can reject it."""
    record = dict(command=[str(value) for value in command], returncode=None, timeout=False)
    stdout = stderr = ''
    try:
        completed = subprocess.run(command, capture_output=True, text=True, timeout=60)
        stdout, stderr = completed.stdout, completed.stderr
        record['returncode'] = completed.returncode
    except (OSError, subprocess.TimeoutExpired) as error:
        record['error'] = repr(error)
        record['timeout'] = isinstance(error, subprocess.TimeoutExpired)
        stdout = getattr(error, 'stdout', None) or ''
        stderr = getattr(error, 'stderr', None) or ''
        raise
    finally:
        for label, value in [('stdout', stdout), ('stderr', stderr)]:
            if isinstance(value, bytes):
                value = value.decode('utf-8', errors='replace')
            path = reports / (name + '.' + kind + ('.txt' if label == 'stdout' else '.stderr.txt'))
            path.write_text(value)
            record[label] = path.name
        (reports / (name + '.' + kind + '.command.json')).write_text(json.dumps(record, indent=2) + '\n')
    completed.check_returncode()
    return stdout


def inspect_packages(directory, reports, tools=None):
    tools = Path(tools) if tools is not None else Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
    reports.mkdir(parents=True, exist_ok=True)
    result = {}
    for name in APKS:
        apk = Path(directory) / 'apks' / name
        badging = inspection_command([str(tools / ('aapt.exe' if os.name == 'nt' else 'aapt')),
                                     'dump', 'badging', str(apk)], reports, name, 'badging')
        signature = inspection_command([str(tools / ('apksigner.bat' if os.name == 'nt' else 'apksigner')),
                                       'verify', '--verbose', '--print-certs', str(apk)], reports, name, 'signature')
        product = dict(PRODUCT)
        if name == 'app-release-androidTest.apk':
            identity = re.findall(r"^package: name='([^']+)' versionCode='([^']*)' versionName='([^']*)'[^\n]*$", badging, re.M)
            if identity != [(PRODUCT['applicationId'] + '.test', '', '')]:
                raise ValueError('Original84 test APK package identity mismatch')
            xml = inspection_command([str(tools / ('aapt.exe' if os.name == 'nt' else 'aapt')),
                                      'dump', 'xmltree', str(apk), 'AndroidManifest.xml'], reports, name, 'xmltree')
            if (re.findall(r'android:targetPackage(?:\(0x[0-9a-f]+\))?="([^"]+)"', xml) != [PRODUCT['applicationId']]
                    or len(re.findall(r'^\s+E: instrumentation \(line=', xml, re.M)) != 1
                    or not re.search(r'android:name(?:\(0x[0-9a-f]+\))?="androidx\.test\.runner\.AndroidJUnitRunner"', xml)
                    or re.search(r'android:version(?:Code|Name)(?:\(0x[0-9a-f]+\))?=', xml)):
                raise ValueError('Original84 instrumentation target mismatch')
            digests = re.findall(r'^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-f]{64})$', signature, re.M)
            digests += re.findall(r'^V2 Signer: certificate SHA-256 digest: ([0-9a-f]{64})$', signature, re.M)
            if (digests != [PRODUCT['signerSha256']] or signature.splitlines().count('Verifies') != 1
                    or len([line for line in signature.splitlines() if 'Signer' in line and 'certificate SHA-256 digest:' in line]) != 1
                    or [line for line in signature.splitlines() if line.startswith('Verified using v2 scheme ')]
                       != ['Verified using v2 scheme (APK Signature Scheme v2): true']
                    or [line for line in signature.splitlines() if line.startswith('Number of signers:')]
                       != ['Number of signers: 1']
                    or not re.search(r"^sdkVersion:'33'$", badging, re.M)):
                raise ValueError('Original84 instrumentation signing or SDK identity mismatch')
            result[name] = dict(application_id=identity[0][0], version_code=None, version=None,
                                version_attributes_declared=False,
                                min_sdk=33, signer_sha256=digests[0], target_package=PRODUCT['applicationId'])
        else:
            result[name] = verify_identity(badging, signature, product, 'preview')
    return result


def verify_original_runtime(directory):
    proof_path = os.environ.get('AEROVE_ORIGINAL84_PROOF')
    if not proof_path:
        raise ValueError('Original84 identity is diagnostic opt-in only')
    proof = release._json(proof_path)
    expected = dict(schema=1, diagnosticOriginal84=True, originalAppIdentity=ORIGINAL,
                    harnessIdentity=artifacts.identity(), promotionAccepted=False,
                    artifactId=ARTIFACT_ID, artifactZipSha256=ZIP_SHA256,
                    runtimeManifestSha256=MANIFEST_SHA256, apks=APKS)
    if any(proof.get(key) != value for key, value in expected.items()):
        raise ValueError('Original84 proof identity differs from fixed app or current harness')
    if Path(proof['runtimeDirectory']).resolve() != Path(directory).resolve():
        raise ValueError('Original84 proof runtime directory mismatch')
    validate_original_files(directory)
    for name, location in [('app-release.apk', 'release'), ('app-release-androidTest.apk', 'androidTest/release')]:
        installed = APK_ROOT / location / name
        if installed.is_symlink() or sha(installed) != APKS[name]:
            raise ValueError('Installed original84 APK differs from original runtime')
    app = proof.get('packages', {}).get('app-release.apk', {})
    test = proof.get('packages', {}).get('app-release-androidTest.apk', {})
    if (app.get('signer_sha256') != PRODUCT['signerSha256'] or app.get('application_id') != PRODUCT['applicationId']
            or app.get('version') != PRODUCT['version'] or app.get('version_code') != 10 or app.get('min_sdk') != 33
            or test.get('signer_sha256') != PRODUCT['signerSha256'] or test.get('application_id') != PRODUCT['applicationId'] + '.test'
            or test.get('min_sdk') != 33 or test.get('target_package') != PRODUCT['applicationId']
            or test.get('version_code') is not None or test.get('version') is not None
            or test.get('version_attributes_declared') is not False):
        raise ValueError('Original84 verified package signer proof is missing')
    return dict(identity=ORIGINAL, apks=APKS, runtimeManifestSha256=MANIFEST_SHA256,
                apkInspectorOutcome='success', diagnosticOriginal84=True, originalAppIdentity=ORIGINAL,
                harnessIdentity=proof['harnessIdentity'], promotionAccepted=False)


def validate_original_renderer(path):
    proof = release._json(path)
    if (proof.get('schema') != 1 or proof.get('owner') != 'emulator_current.py'
            or proof.get('deviceSerial') != os.environ.get('ANDROID_SERIAL')
            or proof.get('gpuBackend') != GPU_BACKEND or proof.get('rendererBackend') != RENDERER_LABEL):
        raise ValueError('Invalid original84 owned renderer proof')
    renderer_evidence('\n'.join(proof['rendererEvidence']))
    actual = verify_original_runtime(proof['runtimeDirectory'])
    if any(proof.get(key) != value for key, value in actual.items()):
        raise ValueError('Original84 renderer proof lost app/harness identity')
    return proof


def prepare(directory, evidence):
    directory = Path(directory)
    archive = directory.with_suffix('.zip')
    if directory.exists() or archive.exists():
        raise ValueError('Never overwrite or redownload original84 inputs')
    row = release._gh_json(f'repos/{release.REPO}/actions/artifacts/{ARTIFACT_ID}')
    if (row.get('id') != ARTIFACT_ID or row.get('name') != 'AEROVE-Lite-runtime-contract-attempt-1'
            or row.get('expired') or row.get('digest') != 'sha256:' + ZIP_SHA256
            or row.get('workflow_run', {}).get('id') != int(ORIGINAL['workflowRun'])
            or row['workflow_run'].get('head_sha') != ORIGINAL['sourceRevision']):
        raise ValueError('Original84 artifact API identity changed')
    evidence.mkdir(parents=True, exist_ok=True)
    (evidence / 'original84-artifact-api.json').write_text(json.dumps(row, indent=2) + '\n')
    release._gh_binary(f'repos/{release.REPO}/actions/artifacts/{ARTIFACT_ID}/zip', archive)
    checked_archive(archive, directory)
    validate_original_files(directory)
    packages = inspect_packages(directory, evidence / 'original84-package-readback')
    for name, location in [('app-release.apk', 'release'), ('app-release-androidTest.apk', 'androidTest/release')]:
        destination = APK_ROOT / location / name
        if destination.exists():
            raise ValueError('Never overwrite an installed APK')
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(directory / 'apks' / name, destination)
    proof = dict(schema=1, diagnosticOriginal84=True, originalAppIdentity=ORIGINAL,
                 harnessIdentity=artifacts.identity(), promotionAccepted=False,
                 artifactId=ARTIFACT_ID, artifactZipSha256=ZIP_SHA256,
                 runtimeManifestSha256=MANIFEST_SHA256, apks=APKS, packages=packages,
                 runtimeDirectory=str(directory.resolve()))
    path = evidence / 'ORIGINAL84-APP-PROOF.json'
    path.write_text(json.dumps(proof, indent=2) + '\n')
    return path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', type=Path, required=True)
    args = parser.parse_args()
    if os.environ.get('AEROVE_QA79_PROOF') or os.environ.get('AEROVE_CURRENT_RENDERER_PROOF') or os.environ.get('AEROVE_ORIGINAL84_PROOF'):
        raise ValueError('Original84 QA requires fresh unmixed proof')
    evidence = Path(os.environ['RUNNER_TEMP']) / 'lite-diagnostics/matrix'
    proof = prepare(args.directory, evidence)
    environment = dict(os.environ, AEROVE_ORIGINAL84_PROOF=str(proof.resolve()))
    return subprocess.run([sys.executable, str(Path(__file__).with_name('emulator_current.py')),
                           'matrix', '--runtime-directory', str(args.directory)], env=environment).returncode


if __name__ == '__main__':
    raise SystemExit(main())
