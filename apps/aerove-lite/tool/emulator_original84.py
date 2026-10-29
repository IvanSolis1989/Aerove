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

LITE125_ORIGINAL_SAMPLES = dict(
    identity=dict(sourceRevision='d52726dc24756847b2b268d307a611cc37f994a3',
                  sourceTree='2dbd4a0c5463a24a826ffde19eace43ed229a1fc',
                  workflowRun='40335340043', runNumber=125, runAttempt=1),
    artifact=12656749144,
    zipSha256='8ac16086a91edcfa75cd037bba658f09e088c4dc105885af66af7442a9ddce6d',
    zipBytes=35353437,
    manifestSha256='02916fdd4190823236f9bac4a2e7792f6785612c17f4f6e642b0351d3477f9ad',
    apks={'app-release.apk': 'ad5b09bcd20312cafd64bed39d4c508c1a463063686aa0ed0236c51d1b9fa701',
          'app-release-androidTest.apk': '1e80bccab828e7e827f91b2d6e8d7ef5076e8a3875d736fbe4ea3e512a68f63b'},
    product=dict(applicationId='dev.aerove.lite', version='0.1.0-beta.16', versionCode='16',
                 signerSha256='09f9e021ddac36ead015d27e073b673e79fdf4e02605c402a505781963b1a4d8'))

LITE125_SAMPLES = {
    'glyph': dict(
        description='actualArrowPaintIncludingAntialiasFitsTheSharedEnvelope(dev.aerove.lite.ui.GlyphPaintedBoundsUiTest)',
        className='dev.aerove.lite.ui.GlyphPaintedBoundsUiTest',
        testName='actualArrowPaintIncludingAntialiasFitsTheSharedEnvelope',
        selector='dev.aerove.lite.ui.GlyphPaintedBoundsUiTest#actualArrowPaintIncludingAntialiasFitsTheSharedEnvelope'),
    'solid': dict(
        description='threeMaterialsAndIndependentSwitchPlanesUseRealWindowPixels[Solid_ordinary_light_on_enabled](dev.aerove.lite.ui.LiteMaterialRenderingTest)',
        className='dev.aerove.lite.ui.LiteMaterialRenderingTest',
        testName='threeMaterialsAndIndependentSwitchPlanesUseRealWindowPixels[Solid_ordinary_light_on_enabled]',
        selector='dev.aerove.lite.ui.LiteMaterialRenderingTest#threeMaterialsAndIndependentSwitchPlanesUseRealWindowPixels[Solid_ordinary_light_on_enabled]'),
    'clear': dict(
        description='threeMaterialsAndIndependentSwitchPlanesUseRealWindowPixels[Clear_ordinary_dark_off_enabled](dev.aerove.lite.ui.LiteMaterialRenderingTest)',
        className='dev.aerove.lite.ui.LiteMaterialRenderingTest',
        testName='threeMaterialsAndIndependentSwitchPlanesUseRealWindowPixels[Clear_ordinary_dark_off_enabled]',
        selector='dev.aerove.lite.ui.LiteMaterialRenderingTest#threeMaterialsAndIndependentSwitchPlanesUseRealWindowPixels[Clear_ordinary_dark_off_enabled]'),
}

LITE125_ORIGINAL_SAMPLES = dict(
    identity=dict(sourceRevision='d52726dc24756847b2b268d307a611cc37f994a3',
                  sourceTree='2dbd4a0c5463a24a826ffde19eace43ed229a1fc',
                  workflowRun='40335340043', runNumber=125, runAttempt=1),
    artifact=12656749144,
    zipSha256='8ac16086a91edcfa75cd037bba658f09e088c4dc105885af66af7442a9ddce6d',
    zipBytes=35353437,
    manifestSha256='02916fdd4190823236f9bac4a2e7792f6785612c17f4f6e642b0351d3477f9ad',
    apks={'app-release.apk': 'ad5b09bcd20312cafd64bed39d4c508c1a463063686aa0ed0236c51d1b9fa701',
          'app-release-androidTest.apk': '1e80bccab828e7e827f91b2d6e8d7ef5076e8a3875d736fbe4ea3e512a68f63b'},
    product=dict(applicationId='dev.aerove.lite', version='0.1.0-beta.16', versionCode='16',
                 signerSha256='09f9e021ddac36ead015d27e073b673e79fdf4e02605c402a505781963b1a4d8'),
    samples=LITE125_SAMPLES)


SAMPLE_TARGETS = ('lite125-original-samples', 'lite158-frame-sample')


def original_sample_selector(sample, target='lite125-original-samples'):
    if target not in SAMPLE_TARGETS:
        raise ValueError('Unknown approved sample runtime')
    spec = runtime_spec(target)['samples']
    if sample not in spec:
        raise ValueError('Unknown Lite125 original sample')
    return spec[sample]['selector']


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

LITE141_PREBUILT_SHARDS = dict(
    identity=dict(sourceRevision='d139a59b13275c9c2b207d0dc936fd37d93249b3',
                  sourceTree='84e394165682384167a55f1bed04f1c0bd47b648',
                  workflowRun='40445440882', runNumber=141, runAttempt=1),
    artifact=12708203167, artifactName='AEROVE-Lite-runtime-contract-attempt-1',
    zipBytes=35580014,
    zipSha256='757f26423d7671f450d6466b5c22386f393e644bd4a75a0c05215e63e9796e44',
    manifestSha256='a19eaee061da26a1580337aeb6a80d48eac7c7bc6dab021fcb2715295040fd26',
    apks={'app-release.apk': '6bef27a4f6e91c8ba69b85d2fac6191e763eda212780b692e1a3fa1502f916e5',
          'app-release-androidTest.apk': 'c721f4b7e05a4b51c631732b97e8edcee8cfcada912bf65f690ba16782f833c2'},
    product=dict(applicationId='dev.aerove.lite', version='0.1.0-beta.19', versionCode='19',
                 signerSha256='09f9e021ddac36ead015d27e073b673e79fdf4e02605c402a505781963b1a4d8'),
    producerJob=121996423095)

LITE158_FRAME_SAMPLE = dict(
    identity=dict(sourceRevision='860f4c2c9d9c1169b2bb49d0ff5e75e798bc4ccd',
                  sourceTree='188682c026070cc452569c2b083f0e3a5a85dcb3',
                  workflowRun='40825097086', runNumber=158, runAttempt=1),
    artifact=12875689873, artifactName='AEROVE-Lite-runtime-contract-attempt-1', zipBytes=35824612,
    zipSha256='1d141104700c72a2c228434d60cb9383b7ba735fb41de7b453c4e0f76fae8967',
    manifestSha256='0633008fa6a657c9646459e0e2cfd24aca89af9dbd11a9d655f18daa9b591172',
    apks={'app-release.apk': '44d6cc5fa9007022cb4f8a0279ff0da8daa79ea08bdd083319afe798b14042d4',
          'app-release-androidTest.apk': 'b6d8ad98c371e47483afdc2923f3c0327badb66101fc35ea1ec82fd5bb9c4f83'},
    product=dict(applicationId='dev.aerove.lite', version='0.1.0-beta.24', versionCode='24',
                 signerSha256='09f9e021ddac36ead015d27e073b673e79fdf4e02605c402a505781963b1a4d8'),
    producerJob=123288152478,
    samples={'solid': dict(
        description='threeMaterialsAndIndependentSwitchPlanesUseRealWindowPixels[Solid_ordinary_light_on_disabled](dev.aerove.lite.ui.LiteMaterialRenderingTest)',
        className='dev.aerove.lite.ui.LiteMaterialRenderingTest',
        testName='threeMaterialsAndIndependentSwitchPlanesUseRealWindowPixels[Solid_ordinary_light_on_disabled]',
        selector='dev.aerove.lite.ui.LiteMaterialRenderingTest#threeMaterialsAndIndependentSwitchPlanesUseRealWindowPixels[Solid_ordinary_light_on_disabled]')})


def runtime_spec(target='original84'):
    if target == 'original84':
        return dict(identity=ORIGINAL, artifact=ARTIFACT_ID, zipSha256=ZIP_SHA256,
                    manifestSha256=MANIFEST_SHA256, apks=APKS, product=PRODUCT)
    if target == 'lite125-original-samples':
        return LITE125_ORIGINAL_SAMPLES
    if target == 'lite141-prebuilt-shards':
        return LITE141_PREBUILT_SHARDS
    if target == 'lite158-frame-sample':
        return LITE158_FRAME_SAMPLE
    if target == 'lite94':
        return ORIGINAL94
    if target == 'beta14':
        from beta14_runtime import SPEC
        return SPEC
    raise ValueError('Unknown approved original runtime target')


def proof_context():
    legacy = os.environ.get('AEROVE_ORIGINAL84_PROOF')
    neutral = os.environ.get('AEROVE_ORIGINAL_RUNTIME_PROOF')
    if (legacy and neutral) or ((legacy or neutral) and os.environ.get('AEROVE_QA79_PROOF')):
        raise ValueError('Mixed original runtime proofs')
    if legacy:
        return legacy, 'original84'
    if neutral:
        if release._json(neutral).get('runtimeTarget') not in ('lite94','beta14', *SAMPLE_TARGETS, 'lite141-prebuilt-shards'):
            raise ValueError('Neutral proof requires approved lite94 target')
        return neutral, release._json(neutral)['runtimeTarget']
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
            if target == 'beta14' and ('\\' in name or path.as_posix() != name.rstrip('/')):
                raise ValueError('Unsafe beta14 ZIP path')
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
    if target == 'beta14':
        from beta14_runtime import verify_contract
        verify_contract(directory)
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
    if target != 'original84' and (type(proof.get('schema')) is not int or proof.get('promotionAccepted') is not False
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
    if target == 'lite125-original-samples':
        expected.update(diagnosticOnly=True, accepted=False)
    if target in ('lite141-prebuilt-shards', 'lite158-frame-sample'):
        expected.update(diagnosticOnly=True, accepted=False, aggregateAcceptance=False)
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
    if target == 'beta14':
        from beta14_runtime import verify_contract
        if proof.get('nativeBundleProof') != verify_contract(directory):
            raise ValueError('beta14 exact26/3 proof missing or changed')
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
    reader = release._gh_json
    if target == 'beta14':
        from beta14_runtime import api_json
        reader = api_json
    row = reader(f"repos/{release.REPO}/actions/artifacts/{spec['artifact']}")
    if (row.get('id') != spec['artifact'] or row.get('name') != spec.get('artifactName', 'AEROVE-Lite-runtime-contract-attempt-1')
            or row.get('expired') or row.get('digest') != 'sha256:' + spec['zipSha256']
            or row.get('workflow_run', {}).get('id') != int(spec['identity']['workflowRun'])
            or row['workflow_run'].get('head_sha') != spec['identity']['sourceRevision']):
        raise ValueError('Original84 artifact API identity changed')
    if target in ('beta14', *SAMPLE_TARGETS, 'lite141-prebuilt-shards'):
        if row.get('size_in_bytes') != spec['zipBytes']:
            raise ValueError(f'{target} artifact API size differs')
        run=reader(f"repos/{release.REPO}/actions/runs/{spec['identity']['workflowRun']}")
        identity = spec['identity']
        if (run.get('id') != int(identity['workflowRun']) or run.get('head_sha') != identity['sourceRevision']
                or run.get('run_number') != int(identity['runNumber'])
                or run.get('run_attempt') != int(identity['runAttempt']) or run.get('status') != 'completed'):
            raise ValueError(f'{target} original run API identity differs')
    if target in ('lite141-prebuilt-shards', 'lite158-frame-sample'):
        job = reader(f"repos/{release.REPO}/actions/jobs/{spec['producerJob']}")
        if (job.get('id') != spec['producerJob'] or job.get('run_id') != int(spec['identity']['workflowRun'])
                or job.get('status') != 'completed' or job.get('conclusion') != 'success'):
            raise ValueError(f'{target} producer job identity or result differs')
    evidence.mkdir(parents=True, exist_ok=True)
    if target == 'beta14': (evidence / 'original107-run-api.json').write_text(json.dumps(run,indent=2)+'\n')
    artifact_record = ('original84-artifact-api.json' if target == 'original84' else
                       'original107-artifact-api.json' if target == 'beta14' else
                       'lite125-original-artifact-api.json' if target == 'lite125-original-samples' else
                       'lite141-original-artifact-api.json' if target == 'lite141-prebuilt-shards' else
                       'lite158-original-artifact-api.json' if target == 'lite158-frame-sample' else
                       'original94-artifact-api.json')
    (evidence / artifact_record).write_text(json.dumps(row, indent=2) + '\n')
    if target == 'lite141-prebuilt-shards':
        (evidence / 'lite141-original-run-api.json').write_text(json.dumps(run, indent=2) + '\n')
        (evidence / 'lite141-producer-job-api.json').write_text(json.dumps(job, indent=2) + '\n')
    if target == 'lite158-frame-sample':
        (evidence / 'lite158-original-run-api.json').write_text(json.dumps(run, indent=2) + '\n')
        (evidence / 'lite158-producer-job-api.json').write_text(json.dumps(job, indent=2) + '\n')
    if target == 'beta14':
        from beta14_runtime import download
        download(f"repos/{release.REPO}/actions/artifacts/{spec['artifact']}/zip",archive)
    else:
        release._gh_binary(f"repos/{release.REPO}/actions/artifacts/{spec['artifact']}/zip",archive)
    if target in ('beta14', 'lite158-frame-sample') and archive.stat().st_size != spec['zipBytes']:
        raise ValueError(f'{target} complete archive size differs')
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
    if target == 'beta14':
        from beta14_runtime import verify_contract
        proof['nativeBundleProof']=verify_contract(directory)
        (evidence / 'BETA14-NATIVE-BUNDLE-PROOF.json').write_text(json.dumps(proof['nativeBundleProof'],indent=2)+'\n')
    if target == 'lite125-original-samples':
        proof.update(diagnosticOnly=True, accepted=False)
    if target in ('lite141-prebuilt-shards', 'lite158-frame-sample'):
        proof.update(diagnosticOnly=True, accepted=False, aggregateAcceptance=False)
    path = evidence / ('ORIGINAL84-APP-PROOF.json' if target == 'original84' else 'ORIGINAL-RUNTIME-APP-PROOF.json')
    path.write_text(json.dumps(proof, indent=2) + '\n')
    return path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--target', choices=['original84', 'lite94','beta14', *SAMPLE_TARGETS, 'lite141-prebuilt-shards'], default='original84')
    parser.add_argument('--sample', choices=sorted(LITE125_SAMPLES))
    parser.add_argument('--phase', choices=['matrix','remaining'])
    parser.add_argument('--shard-index', type=int)
    args = parser.parse_args()
    if (args.target in SAMPLE_TARGETS) != (args.sample is not None):
        parser.error('--sample is required only for a fixed original-sample target')
    sample_selector = original_sample_selector(args.sample, args.target) if args.sample else None
    if args.target == 'lite141-prebuilt-shards':
        if args.sample is not None or args.phase != 'remaining' or args.shard_index not in range(8):
            parser.error('lite141-prebuilt-shards requires --phase remaining and one --shard-index 0..7')
    elif args.phase is not None or args.shard_index is not None:
        parser.error('--phase/--shard-index are valid only for lite141-prebuilt-shards')
    if os.environ.get('AEROVE_QA79_PROOF') or os.environ.get('AEROVE_CURRENT_RENDERER_PROOF') or os.environ.get('AEROVE_ORIGINAL84_PROOF') or os.environ.get('AEROVE_ORIGINAL_RUNTIME_PROOF'):
        raise ValueError('Original84 QA requires fresh unmixed proof')
    evidence = Path(os.environ['RUNNER_TEMP']) / 'lite-diagnostics/matrix'
    proof = prepare(args.directory, evidence, args.target)
    proof_variable = 'AEROVE_ORIGINAL84_PROOF' if args.target == 'original84' else 'AEROVE_ORIGINAL_RUNTIME_PROOF'
    environment = dict(os.environ, **{proof_variable: str(proof.resolve())})
    phase = 'original-sample' if args.sample else args.phase or 'matrix'
    command = [sys.executable, str(Path(__file__).with_name('emulator_current.py')), phase,
               '--runtime-directory', str(args.directory)]
    if args.target == 'lite141-prebuilt-shards':
        environment['AEROVE_REMAINING_SHARD_COUNT'] = '8'
        environment['AEROVE_REMAINING_SHARD_INDEX'] = str(args.shard_index)
    if args.sample:
        command.extend(['--sample', args.sample])
        environment['AEROVE_ORIGINAL_SAMPLE'] = args.sample
        environment['AEROVE_ORIGINAL_SAMPLE_SELECTOR'] = sample_selector
    return subprocess.run(command, env=environment).returncode


if __name__ == '__main__':
    raise SystemExit(main())
