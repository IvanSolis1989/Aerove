"""Immutable original343 ANR diagnosis; never builds or promotes an APK."""
import argparse
import json
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import shutil
import stat
import subprocess
import zipfile
import ci_artifacts as artifacts
import lite_release as release

IDENTITY = dict(sourceRevision='c46a25ec26ece80724bc26254a3f59bdc10185f8', sourceTree='6a9d43c4cc1950dc4a32d4fed66454f44307e322', workflowRun='42517946969', runAttempt=1, runNumber=343)
ARCHIVE = dict(id=13633269139, name='AEROVE-Lite-runtime-contract-attempt-1', size_in_bytes=59854732, digest='sha256:741a489c2c13b4e2f720e8bf242ecfc4c4a08d827c331ea3b21faeb60e7206a7')
MANIFEST = '422d31094d401280115cf9b3448acf741b05b9392f1f77d68ea3e616d0b3758a'
INFO = 'eeb13c23d48aec3f8e53b1b89212675780481f6bff29d2ea9554bb5f23b6ed2e'
APKS = {'app-release.apk':'d1b788bfd0cdfa06d28fdf4b6c058fcfd673fce91bb3451995935eb1c6784101', 'app-release-androidTest.apk':'2ea334ed78a933ab3167f2ef6df62aa1c2c0d782328c0a4dbccdc0a38362e63f', 'platform-qa-release.apk':'3914132d90108ba543132fb54c5fcc0484bc1fbccd6b1b205c7d087de82ca2a3'}
OPT_IN = 'AEROVE_LITE343_ANR'

def require(value, message):
    if not value:
        raise ValueError(message)

def enabled():
    value = os.environ.get(OPT_IN)
    if value is None:
        return False
    require(value == '1' and os.environ.get('GITHUB_REPOSITORY') == release.REPO and os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch' and os.environ.get('GITHUB_REF') == 'refs/heads/dot/cloud-j316-checkpoint-20261108', 'Fixed343 requires isolated explicit workflow')
    require(not any(v for k,v in os.environ.items() if k.startswith(('AEROVE_ORIGINAL','AEROVE_LITE236','AEROVE_LITE257','AEROVE_LITE265','AEROVE_LITE290','AEROVE_LITE328','AEROVE_LITE333','AEROVE_GFXSTREAM','AEROVE_EMULATOR_SIGILL','AEROVE_QA79'))), 'Fixed343 cannot mix historical or modified graphics inputs')
    require(not any(v for k,v in os.environ.items() if k.startswith('AEROVE_CURRENT_RENDERER') and k != 'AEROVE_CURRENT_RENDERER_PROOF'), 'Fixed343 cannot mix renderer overrides')
    return True

def validate_api(row):
    require(all(row.get(k) == v for k,v in ARCHIVE.items()) and row.get('expired') is False, 'Fixed343 archive API differs')
    require(row.get('workflow_run') == dict(id=42517946969, repository_id=1429390079, head_repository_id=1429390079, head_branch='dot/cloud-j316-checkpoint-20261108', head_sha=IDENTITY['sourceRevision']), 'Fixed343 archive producer differs')
    require(row.get('url') == 'https://api.github.com/repos/' + release.REPO + '/actions/artifacts/' + str(ARCHIVE['id']), 'Fixed343 archive URL differs')

def runtime(directory, installed=True):
    require(enabled(), 'Fixed343 opt-in required')
    directory = Path(directory)
    api = json.loads(directory.with_suffix('.api.json').read_text())
    validate_api(api)
    release.product_source.bind_archive(directory.with_suffix('.zip'), directory, api)
    manifest = artifacts.verify(directory, IDENTITY, runtime=True)
    require(artifacts.digest(directory/'source-manifest.json') == MANIFEST and artifacts.digest(directory/'BUILD_INFO.json') == INFO, 'Fixed343 seal or BUILD_INFO changed')
    info = json.loads((directory/'BUILD_INFO.json').read_text())
    expected = dict(source=IDENTITY['sourceRevision'], tree=IDENTITY['sourceTree'], product='AEROVE Lite', application_id='dev.aerove.lite', abi='x86_64', min_sdk=33, version='0.2.0-beta.7', version_code=39, run_number=343, run_attempt=1, workflow='https://github.com/'+release.REPO+'/actions/runs/42517946969', apk_sha256=APKS['app-release.apk'], signer_sha256='09f9e021ddac36ead015d27e073b673e79fdf4e02605c402a505781963b1a4d8', launcher_activity='dev.aerove.lite.FlutterMainActivity')
    require(all(info.get(k)==v for k,v in expected.items()), 'Fixed343 package metadata differs')
    for name,digest in APKS.items():
        require(artifacts.digest(directory/'apks'/name)==digest, 'Fixed343 APK differs')
        if installed and name != 'platform-qa-release.apk':
            location='release' if name=='app-release.apk' else 'androidTest/release'
            require(artifacts.digest(Path('apps/aerove-lite/app/build/outputs/apk')/location/name)==digest, 'Fixed343 staged APK differs')
    from verify_apk import verify_bundled_script_apk
    return dict(identity=IDENTITY, apks={k:v for k,v in APKS.items() if k!='platform-qa-release.apk'}, runtimeManifestSha256=MANIFEST, apkInspectorOutcome=manifest['apkInspectorOutcome'], bundledMeowScript=verify_bundled_script_apk(directory/'apks/app-release.apk'), original343Anr=True, originalAppIdentity=IDENTITY, harnessIdentity=artifacts.identity(), diagnosticOnly=True, accepted=False, aggregateAcceptance=False, promotionAccepted=False)

def verify_signatures(directory, apksigner):
    from verify_apk import signing_certificate
    expected = '09f9e021ddac36ead015d27e073b673e79fdf4e02605c402a505781963b1a4d8'
    for name in APKS:
        output = subprocess.check_output([str(apksigner), 'verify', '--verbose', '--print-certs',
            str(Path(directory)/'apks'/name)], stderr=subprocess.STDOUT, timeout=30).decode()
        require(signing_certificate(output) == expected, 'Fixed343 APK signature differs')

def fetch(directory):
    require(enabled(), 'Fixed343 opt-in required')
    require(not os.environ.get('AEROVE_CURRENT_RENDERER_PROOF'), 'Fixed343 fetch cannot reuse an existing renderer owner')
    route='repos/'+release.REPO+'/actions/artifacts/'+str(ARCHIVE['id'])
    row=release._gh_json(route); validate_api(row)
    require(not directory.exists() and not directory.with_suffix('.zip').exists(), 'Never overwrite original343 input')
    archive=directory.with_suffix('.zip'); release._gh_binary(route+'/zip', archive)
    require(archive.stat().st_size==ARCHIVE['size_in_bytes'] and 'sha256:'+artifacts.digest(archive)==ARCHIVE['digest'], 'Fixed343 ZIP differs')
    with zipfile.ZipFile(archive) as package:
        entries=package.infolist()
        require(entries and len({e.filename for e in entries})==len(entries) and sum(e.file_size for e in entries)<=512*1024*1024, 'Unsafe ZIP inventory')
        for entry in entries:
            p,w=PurePosixPath(entry.filename),PureWindowsPath(entry.filename)
            require(not p.is_absolute() and not w.drive and '..' not in p.parts and '\\' not in entry.filename and stat.S_IFMT(entry.external_attr>>16) in (0,stat.S_IFREG,stat.S_IFDIR), 'Unsafe ZIP path')
        require(package.testzip() is None, 'ZIP CRC failed'); package.extractall(directory)
    directory.with_suffix('.api.json').write_text(json.dumps(row,indent=2)+'\n')
    proof=runtime(directory,installed=False)
    verify_signatures(directory, Path(os.environ["ANDROID_HOME"])/"build-tools/37.0.0/apksigner")
    for name,location in [('app-release.apk','release'),('app-release-androidTest.apk','androidTest/release')]:
        target=Path('apps/aerove-lite/app/build/outputs/apk')/location/name
        require(not target.exists(), 'Never overwrite staged original APK')
        target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(directory/'apks'/name,target)
    directory.with_suffix('.fetch.json').write_text(json.dumps(proof,indent=2)+'\n')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('action',choices=['fetch']);parser.add_argument('--directory',required=True,type=Path)
    args=parser.parse_args();fetch(args.directory)
