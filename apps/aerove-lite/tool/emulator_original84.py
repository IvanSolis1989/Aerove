"""Approved immutable original-runtime diagnosis; default run84, never builds or promotes APKs."""
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

ORIGINAL94 = dict(
    identity=dict(sourceRevision='c8b0fdb6ce33ba93fea831659096c85938ca3994',
                  sourceTree='11d6031b795a437d5bf326c089f9e4bbc295b7aa',
                  workflowRun='40117349063', runNumber=94, runAttempt=1),
    artifact=12570625761,
    zipSha256='679de7269a6e0479660e26aeb296abac5fe4d6e53ee718369854bff34a1afab5',
    manifestSha256='0ae0b2d2b2a391c5252512abda3e6b294bcd5af0618c67ad6f2168914888c950',
    apks={'app-release.apk': '0dac57d5cbfb559a0ac3638286198b39fdc6ddaacd36b6886c329736948a8f2f',
          'app-release-androidTest.apk': '95550e44611c1df97aa7b502fae6ac271afe6622c73c17a5258638d771d7c2c5'},
    product=dict(applicationId='dev.aerove.lite', version='0.1.0-beta.12', versionCode='12',
                 signerSha256='09f9e021ddac36ead015d27e073b673e79fdf4e02605c402a505781963b1a4d8'))


def runtime_spec(target='original84'):
    if target == 'original84':
        return dict(identity=ORIGINAL, artifact=ARTIFACT_ID, zipSha256=ZIP_SHA256,
                    manifestSha256=MANIFEST_SHA256, apks=APKS, product=PRODUCT)
    if target == 'lite94':
        return ORIGINAL94
    raise ValueError('Unknown approved original runtime target')


def proof_context():
    legacy = os.environ.get('AEROVE_ORIGINAL84_PROOF')
    neutral = os.environ.get('AEROVE_ORIGINAL_RUNTIME_PROOF')
    if (legacy and neutral) or ((legacy or neutral) and os.environ.get('AEROVE_QA79_PROOF')):
        raise ValueError('Mixed original runtime proofs')
    if legacy:
        return legacy, 'original84'
    if neutral:
        if release._json(neutral).get('runtimeTarget') != 'lite94':
            raise ValueError('Neutral proof requires approved lite94 target')
        return neutral, 'lite94'
    raise ValueError('Original runtime identity is diagnostic opt-in only')


def diagnostic_fields(target):
    if target == 'original84':
        return dict(diagnosticOriginal84=True)
    runtime_spec(target)
    return dict(diagnosticOriginalRuntime=True, runtimeTarget=target)



def checked_archive(archive, destination, target='original84'):
    spec = runtime_spec(target)
    if sha(archive) != spec['zipSha256']:
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


def validate_original_files(directory, target='original84'):
    spec = runtime_spec(target)
    directory = Path(directory)
    if directory.is_symlink():
        raise ValueError('Original runtime directory must not be a symlink')
    if sha(directory / 'source-manifest.json') != spec['manifestSha256']:
        raise ValueError('Original84 runtime manifest bytes changed')
    artifacts.verify(directory, spec['identity'], runtime=True)
    release._validate_manifest(directory, spec['identity']['sourceRevision'], spec['identity']['sourceTree'],
                               spec['identity']['workflowRun'], spec['identity']['runAttempt'], spec['identity']['runNumber'])
    for name, digest in spec['apks'].items():
        if sha(directory / 'apks' / name) != digest:
            raise ValueError('Original84 APK bytes changed')
    info = release._json(directory / 'BUILD_INFO.json')
    expected = dict(product='AEROVE Lite', source=spec['identity']['sourceRevision'], tree=spec['identity']['sourceTree'],
                    workflow='https://github.com/' + release.REPO + '/actions/runs/' + spec['identity']['workflowRun'],
                    run_number=spec['identity']['runNumber'], run_attempt=spec['identity']['runAttempt'], application_id=spec['product']['applicationId'],
                    version=spec['product']['version'], version_code=int(spec['product']['versionCode']), signer_sha256=spec['product']['signerSha256'],
                    channel='preview', abi='x86_64', apk_sha256=spec['apks']['app-release.apk'])
    if any(info.get(key) != value for key, value in expected.items()):
        raise ValueError('Original84 BUILD_INFO identity mismatch')
    return info


def inspection_command(command, reports, name, kind):
    """Preserve the reader's first failure while saving diagnostics best effort."""
    record = dict(command=[str(value) for value in command], returncode=None, timeout=False,
                  stdoutSaved=False, stderrSaved=False, diagnosticWriteErrors=[])
    stdout = stderr = ''
    primary = None
    try:
        completed = subprocess.run(command, capture_output=True, text=True, timeout=60)
        stdout, stderr = completed.stdout, completed.stderr
        record['returncode'] = completed.returncode
        completed.check_returncode()
    except BaseException as error:
        primary = error
        record['error'] = repr(error)
        record['errorType'] = type(error).__name__
        record['timeout'] = isinstance(error, subprocess.TimeoutExpired)
        stdout = getattr(error, 'stdout', None) or stdout
        stderr = getattr(error, 'stderr', None) or stderr
        raise
    finally:
        diagnostic_failure = None
        for label in ('stdout', 'stderr', 'command'):
            suffix = '.command.json' if label == 'command' else ('.txt' if label == 'stdout' else '.stderr.txt')
            path = reports / (name + '.' + kind + suffix)
            try:
                value = json.dumps(record, indent=2) + '\n' if label == 'command' else stdout if label == 'stdout' else stderr
                if isinstance(value, bytes):
                    value = value.decode('utf-8', errors='replace')
                path.write_text(value)
                if label != 'command':
                    record[label] = path.name
                    record[label + 'Saved'] = True
            except BaseException as error:
                if diagnostic_failure is None:
                    diagnostic_failure = (error, error.__traceback__)
                record['diagnosticWriteErrors'].append(dict(file=path.name, errorType=type(error).__name__, error=repr(error)))
                try:
                    print(f'Diagnostic reader evidence write failed for {path.name}: {error!r}', file=sys.stderr)
                except BaseException:
                    pass
        if primary is None and diagnostic_failure is not None:
            error, traceback = diagnostic_failure
            raise error.with_traceback(traceback)
    return stdout


def inspect_packages(directory, reports, tools=None, target='original84'):
    spec = runtime_spec(target)
    tools = Path(tools) if tools is not None else Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
    reports.mkdir(parents=True, exist_ok=True)
    result = {}
    for name in spec['apks']:
        apk = Path(directory) / 'apks' / name
        badging = inspection_command([str(tools / ('aapt.exe' if os.name == 'nt' else 'aapt')),
                                     'dump', 'badging', str(apk)], reports, name, 'badging')
        signature = inspection_command([str(tools / ('apksigner.bat' if os.name == 'nt' else 'apksigner')),
                                       'verify', '--verbose', '--print-certs', str(apk)], reports, name, 'signature')
        product = dict(spec['product'])
        if name == 'app-release-androidTest.apk':
            identity = re.findall(r"^package: name='([^']+)' versionCode='([^']*)' versionName='([^']*)'[^\n]*$", badging, re.M)
            if identity != [(spec['product']['applicationId'] + '.test', '', '')]:
                raise ValueError('Original84 test APK package identity mismatch')
            xml = inspection_command([str(tools / ('aapt.exe' if os.name == 'nt' else 'aapt')),
                                      'dump', 'xmltree', str(apk), 'AndroidManifest.xml'], reports, name, 'xmltree')
            if (re.findall(r'android:targetPackage(?:\(0x[0-9a-f]+\))?="([^"]+)"', xml) != [spec['product']['applicationId']]
                    or len(re.findall(r'^\s+E: instrumentation \(line=', xml, re.M)) != 1
                    or not re.search(r'android:name(?:\(0x[0-9a-f]+\))?="androidx\.test\.runner\.AndroidJUnitRunner"', xml)
                    or re.search(r'android:version(?:Code|Name)(?:\(0x[0-9a-f]+\))?=', xml)):
                raise ValueError('Original84 instrumentation target mismatch')
            digests = re.findall(r'^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-f]{64})$', signature, re.M)
            digests += re.findall(r'^V2 Signer: certificate SHA-256 digest: ([0-9a-f]{64})$', signature, re.M)
            if (digests != [spec['product']['signerSha256']] or signature.splitlines().count('Verifies') != 1
                    or len([line for line in signature.splitlines() if 'Signer' in line and 'certificate SHA-256 digest:' in line]) != 1
                    or [line for line in signature.splitlines() if line.startswith('Verified using v2 scheme ')]
                       != ['Verified using v2 scheme (APK Signature Scheme v2): true']
                    or [line for line in signature.splitlines() if line.startswith('Number of signers:')]
                       != ['Number of signers: 1']
                    or not re.search(r"^sdkVersion:'33'$", badging, re.M)):
                raise ValueError('Original84 instrumentation signing or SDK identity mismatch')
            result[name] = dict(application_id=identity[0][0], version_code=None, version=None,
                                version_attributes_declared=False,
                                min_sdk=33, signer_sha256=digests[0], target_package=spec['product']['applicationId'])
        else:
            result[name] = verify_identity(badging, signature, product, 'preview')
    return result


def verify_original_runtime(directory):
    proof_path, target = proof_context()
    spec = runtime_spec(target)
    proof = release._json(proof_path)
    if target == 'lite94' and (type(proof.get('schema')) is not int or proof.get('promotionAccepted') is not False
            or proof.get('diagnosticOriginalRuntime') is not True
            or any(not isinstance(proof.get(key), dict) or any(type(proof[key].get(field)) is not int
                   for field in ('runNumber', 'runAttempt')) for key in ('originalAppIdentity', 'harnessIdentity'))):
        raise ValueError('Neutral original proof requires exact boolean and integer identity types')
    forbidden = ('diagnosticOriginalRuntime', 'runtimeTarget') if target == 'original84' else ('diagnosticOriginal84',)
    if any(key in proof for key in forbidden):
        raise ValueError('Mixed legacy and neutral original runtime identity')
    expected = dict(schema=1, **diagnostic_fields(target), originalAppIdentity=spec['identity'],
                    harnessIdentity=artifacts.identity(), promotionAccepted=False,
                    artifactId=spec['artifact'], artifactZipSha256=spec['zipSha256'],
                    runtimeManifestSha256=spec['manifestSha256'], apks=spec['apks'])
    if any(proof.get(key) != value for key, value in expected.items()):
        raise ValueError('Original84 proof identity differs from fixed app or current harness')
    if Path(proof['runtimeDirectory']).resolve() != Path(directory).resolve():
        raise ValueError('Original84 proof runtime directory mismatch')
    validate_original_files(directory, target)
    for name, location in [('app-release.apk', 'release'), ('app-release-androidTest.apk', 'androidTest/release')]:
        installed = APK_ROOT / location / name
        if installed.is_symlink() or sha(installed) != spec['apks'][name]:
            raise ValueError('Installed original84 APK differs from original runtime')
    app = proof.get('packages', {}).get('app-release.apk', {})
    test = proof.get('packages', {}).get('app-release-androidTest.apk', {})
    if (app.get('signer_sha256') != spec['product']['signerSha256'] or app.get('application_id') != spec['product']['applicationId']
            or app.get('version') != spec['product']['version'] or app.get('version_code') != int(spec['product']['versionCode']) or app.get('min_sdk') != 33
            or test.get('signer_sha256') != spec['product']['signerSha256'] or test.get('application_id') != spec['product']['applicationId'] + '.test'
            or test.get('min_sdk') != 33 or test.get('target_package') != spec['product']['applicationId']
            or 'version_code' not in test or 'version' not in test
            or test.get('version_code') is not None or test.get('version') is not None
            or test.get('version_attributes_declared') is not False):
        raise ValueError('Original84 verified package signer proof is missing')
    return dict(identity=spec['identity'], apks=spec['apks'], runtimeManifestSha256=spec['manifestSha256'],
                apkInspectorOutcome='success', **diagnostic_fields(target), originalAppIdentity=spec['identity'],
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


def prepare(directory, evidence, target='original84'):
    spec = runtime_spec(target)
    directory = Path(directory)
    archive = directory.with_suffix('.zip')
    if directory.exists() or archive.exists():
        raise ValueError('Never overwrite or redownload original84 inputs')
    row = release._gh_json(f"repos/{release.REPO}/actions/artifacts/{spec['artifact']}")
    if (row.get('id') != spec['artifact'] or row.get('name') != 'AEROVE-Lite-runtime-contract-attempt-1'
            or row.get('expired') or row.get('digest') != 'sha256:' + spec['zipSha256']
            or row.get('workflow_run', {}).get('id') != int(spec['identity']['workflowRun'])
            or row['workflow_run'].get('head_sha') != spec['identity']['sourceRevision']):
        raise ValueError('Original84 artifact API identity changed')
    evidence.mkdir(parents=True, exist_ok=True)
    (evidence / ('original84-artifact-api.json' if target == 'original84' else 'original94-artifact-api.json')).write_text(json.dumps(row, indent=2) + '\n')
    release._gh_binary(f"repos/{release.REPO}/actions/artifacts/{spec['artifact']}/zip", archive)
    checked_archive(archive, directory, target)
    validate_original_files(directory, target)
    packages = inspect_packages(directory, evidence / (target + '-package-readback') if target != 'original84' else evidence / 'original84-package-readback', target=target)
    for name, location in [('app-release.apk', 'release'), ('app-release-androidTest.apk', 'androidTest/release')]:
        destination = APK_ROOT / location / name
        if destination.exists():
            raise ValueError('Never overwrite an installed APK')
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(directory / 'apks' / name, destination)
    proof = dict(schema=1, **diagnostic_fields(target), originalAppIdentity=spec['identity'],
                 harnessIdentity=artifacts.identity(), promotionAccepted=False,
                 artifactId=spec['artifact'], artifactZipSha256=spec['zipSha256'],
                 runtimeManifestSha256=spec['manifestSha256'], apks=spec['apks'], packages=packages,
                 runtimeDirectory=str(directory.resolve()))
    path = evidence / ('ORIGINAL84-APP-PROOF.json' if target == 'original84' else 'ORIGINAL-RUNTIME-APP-PROOF.json')
    path.write_text(json.dumps(proof, indent=2) + '\n')
    return path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--target', choices=['original84', 'lite94'], default='original84')
    args = parser.parse_args()
    if os.environ.get('AEROVE_QA79_PROOF') or os.environ.get('AEROVE_CURRENT_RENDERER_PROOF') or os.environ.get('AEROVE_ORIGINAL84_PROOF') or os.environ.get('AEROVE_ORIGINAL_RUNTIME_PROOF'):
        raise ValueError('Original84 QA requires fresh unmixed proof')
    evidence = Path(os.environ['RUNNER_TEMP']) / 'lite-diagnostics/matrix'
    proof = prepare(args.directory, evidence, args.target)
    proof_variable = 'AEROVE_ORIGINAL84_PROOF' if args.target == 'original84' else 'AEROVE_ORIGINAL_RUNTIME_PROOF'
    environment = dict(os.environ, **{proof_variable: str(proof.resolve())})
    return subprocess.run([sys.executable, str(Path(__file__).with_name('emulator_current.py')),
                           'matrix', '--runtime-directory', str(args.directory)], env=environment).returncode


if __name__ == '__main__':
    raise SystemExit(main())
