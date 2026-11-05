"""Fixed Lite265 prebuilt execution; producer bytes and harness identity stay separate."""
import argparse
import json
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import shutil
import stat
import zipfile

import ci_artifacts as artifacts
import lite_release as release

IDENTITY = dict(sourceRevision='4190ff45f8eedc6734fa666e2658b71abf147479',
                sourceTree='8ca6fb6bc19867e9302923ba9bea8d289972a8b6',
                workflowRun='42066278567', runAttempt=1, runNumber=265)
ARCHIVE = dict(id=13436773990, name='AEROVE-Lite-runtime-contract-attempt-1',
               size_in_bytes=59451889,
               digest='sha256:49e50eb6317d8993bd6137956c88c9167ea8012653040fb182d8825a8ef79a9c')
MANIFEST = '7d868f81e8ec430a6be1a74bd79a164adea270c0aa1b94f6e4816f0f5bf24f0f'
APKS = {'app-release.apk': '07b9482e3ba7227c4746b11ea55b6eac7c64fe51a1355276686b791fbb14a1cb',
        'app-release-androidTest.apk': '8abed1e675d6c0e10b1c530b38f0bf26689ed80f65f759390b6d1d614da03ec7'}
PRODUCERS = {127401476340: 'build (arm64-v8a) / package',
             127401036780: 'build (x86_64) / package',
             127396763516: 'build (arm64-v8a) / rust',
             127396763933: 'build (arm64-v8a) / helper',
             127396763976: 'build (x86_64) / rust',
             127396763696: 'build (x86_64) / helper'}
OPT_IN = 'AEROVE_LITE265_RECHECK'
BACKEND = 'Guest ANGLE (Vulkan + Lavapipe)'


def require(value, message):
    if not value:
        raise ValueError(message)


def enabled():
    value = os.environ.get(OPT_IN)
    if value is None:
        return False
    require(value == '1', 'Invalid Lite265 opt-in')
    require(os.environ.get('GITHUB_REPOSITORY') == release.REPO and
            os.environ.get('GITHUB_ACTOR') == 'IvanSolis1989' and
            os.environ.get('GITHUB_REF') == 'refs/heads/codex/lite265-original-recheck' and
            os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch', 'Fixed Lite265 recheck branch/event required')
    require(not any(os.environ.get(key) for key in (
        'AEROVE_ORIGINAL84_PROOF', 'AEROVE_ORIGINAL_RUNTIME_PROOF', 'AEROVE_QA79_PROOF',
        'AEROVE_ORIGINAL_RUNTIME_TARGET', 'AEROVE_ORIGINAL_SAMPLE', 'AEROVE_BETA14_PROFILE',
        'AEROVE_GFXSTREAM_SOURCE_RECEIPT', 'AEROVE_EMULATOR_SIGILL_TRACE')), 'Mixed historical runtime opt-ins')
    require(os.environ.get('AEROVE_GUEST_ANGLE') == '1', 'Lite265 requires Guest ANGLE')
    return True


def check_api(run, jobs, row):
    require(run.get('id') == int(IDENTITY['workflowRun']) and run.get('run_attempt') == 1 and
            run.get('head_sha') == IDENTITY['sourceRevision'] and
            run.get('head_branch') == 'codex/lite-shared-flutter-complete114' and
            run.get('path') == '.github/workflows/aerove-lite.yaml' and
            run.get('status') == 'completed' and run.get('conclusion') == 'failure' and
            run.get('repository', {}).get('full_name') == release.REPO, 'Original265 run identity changed')
    require(jobs.get('total_count') == len(jobs.get('jobs', [])), 'Incomplete original265 job inventory')
    by_id = {job['id']: job for job in jobs['jobs']}
    for key, name in PRODUCERS.items():
        job = by_id.get(key, {})
        require(job.get('name') == name and job.get('run_id') == run['id'] and
                job.get('run_attempt') == 1 and job.get('head_sha') == IDENTITY['sourceRevision'] and
                job.get('status') == 'completed' and job.get('conclusion') == 'success',
                'Original265 producer did not succeed: ' + name)
    require(all(row.get(key) == value for key, value in ARCHIVE.items()) and row.get('expired') is False,
            'Original265 runtime artifact identity changed')
    require(row.get('workflow_run') == dict(id=run['id'], repository_id=1429390079,
            head_repository_id=1429390079, head_branch='codex/lite-shared-flutter-complete114',
            head_sha=IDENTITY['sourceRevision']), 'Original265 artifact source/run changed')


def extract(archive, directory):
    require(not directory.exists(), 'Original265 destination must be new')
    require(archive.stat().st_size == ARCHIVE['size_in_bytes'] and
            'sha256:' + artifacts.digest(archive) == ARCHIVE['digest'], 'Original265 ZIP size/hash changed')
    with zipfile.ZipFile(archive) as package:
        entries = package.infolist()
        require(entries and len({item.filename for item in entries}) == len(entries), 'Duplicate/empty ZIP entries')
        require(sum(item.file_size for item in entries) <= 512 * 1024 * 1024, 'Original265 ZIP expansion limit')
        for item in entries:
            path, windows = PurePosixPath(item.filename), PureWindowsPath(item.filename)
            mode = stat.S_IFMT(item.external_attr >> 16)
            require(not path.is_absolute() and not windows.drive and not windows.is_absolute() and
                    '..' not in path.parts and '\\' not in item.filename and
                    all(part not in ('', '.') for part in item.filename.rstrip('/').split('/')) and
                    mode in (0, stat.S_IFREG, stat.S_IFDIR), 'Unsafe original265 ZIP entry')
        require(package.testzip() is None, 'Original265 ZIP CRC failed')
        package.extractall(directory)
    release.product_source.bind_archive(archive, directory, ARCHIVE)


def runtime(directory, installed=True):
    require(enabled(), 'Explicit Lite265 recheck required')
    directory = Path(directory)
    release.product_source.bind_archive(directory.with_suffix('.zip'), directory, ARCHIVE)
    manifest = artifacts.verify(directory, IDENTITY, runtime=True)
    require(artifacts.digest(directory / 'source-manifest.json') == MANIFEST, 'Original265 manifest changed')
    release._validate_manifest(directory, IDENTITY['sourceRevision'], IDENTITY['sourceTree'],
                              IDENTITY['workflowRun'], 1, 265)
    info = release._json(directory / 'BUILD_INFO.json')
    expected = dict(source=IDENTITY['sourceRevision'], tree=IDENTITY['sourceTree'],
                    product='AEROVE Lite', application_id='dev.aerove.lite', abi='x86_64', min_sdk=33,
                    version='0.2.0-beta.4', version_code=36, run_number=265, run_attempt=1,
                    workflow='https://github.com/' + release.REPO + '/actions/runs/' + IDENTITY['workflowRun'],
                    apk_sha256=APKS['app-release.apk'],
                    signer_sha256='09f9e021ddac36ead015d27e073b673e79fdf4e02605c402a505781963b1a4d8')
    require(all(info.get(key) == value for key, value in expected.items()), 'Original265 BUILD_INFO changed')
    for name, digest in APKS.items():
        require(artifacts.digest(directory / 'apks' / name) == digest, 'Original265 APK changed')
        if installed:
            location = 'release' if name == 'app-release.apk' else 'androidTest/release'
            require(artifacts.digest(Path('apps/aerove-lite/app/build/outputs/apk') / location / name) == digest,
                    'Staged APK differs from original265')
    from verify_apk import verify_bundled_script_apk
    return dict(identity=IDENTITY, apks=APKS, runtimeManifestSha256=MANIFEST,
                apkInspectorOutcome=manifest['apkInspectorOutcome'],
                bundledMeowScript=verify_bundled_script_apk(directory / 'apks/app-release.apk'),
                original265Recheck=True, harnessIdentity=artifacts.identity())


def fetch(directory):
    require(enabled(), 'Explicit Lite265 recheck required')
    route = 'repos/' + release.REPO + '/actions/'
    run = release._gh_json(route + 'runs/' + IDENTITY['workflowRun'])
    jobs = release._gh_json(route + 'runs/' + IDENTITY['workflowRun'] + '/attempts/1/jobs?per_page=100')
    row = release._gh_json(route + 'artifacts/' + str(ARCHIVE['id']))
    check_api(run, jobs, row)
    archive = directory.with_suffix('.zip')
    require(not archive.exists() and not directory.exists(), 'Never overwrite original265 input')
    release._gh_binary(route + 'artifacts/' + str(ARCHIVE['id']) + '/zip', archive)
    extract(archive, directory)
    runtime(directory, installed=False)
    for name, location in (('app-release.apk', 'release'), ('app-release-androidTest.apk', 'androidTest/release')):
        target = Path('apps/aerove-lite/app/build/outputs/apk') / location / name
        require(not target.exists(), 'Never overwrite staged APK')
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(directory / 'apks' / name, target)
    receipt = dict(originalProducer=IDENTITY, archive=ARCHIVE, producerJobs=PRODUCERS,
                   harnessIdentity=artifacts.identity(), appCompiled=False, releaseAccepted=False)
    (directory.parent / 'original265-fetch.json').write_text(json.dumps(receipt, indent=2) + '\n')


def phase_receipt(directory, phase, live=True):
    from emulator_current import (validate_renderer_proof, guest_angle_file_hashes,
                                  guest_angle_route, GUEST_ANGLE_READS)
    directory = Path(directory)
    proof_path = directory / 'CURRENT-RENDERER-PROOF.json'
    proof = validate_renderer_proof(proof_path) if live else release._json(proof_path)
    require(proof.get('identity') == IDENTITY and proof.get('apks') == APKS and
            proof.get('runtimeManifestSha256') == MANIFEST and proof.get('original265Recheck') is True and
            proof.get('harnessIdentity') == artifacts.identity() and proof.get('gpuBackend') == 'lavapipe' and
            proof.get('rendererBackend') == BACKEND and proof.get('phase') == phase,
            'Original265 raw renderer identity/backend changed')
    require(proof.get('guestGraphicsEvidence') == guest_angle_file_hashes(directory), 'Raw graphics evidence changed')
    outputs = {}
    for name, _ in GUEST_ANGLE_READS:
        require((directory / ('guest-angle-' + name + '.exit.txt')).read_text().strip() == '0', 'Graphics read failed')
        outputs[name] = (directory / ('guest-angle-' + name + '.out')).read_text()
    route = guest_angle_route(outputs, (directory / 'guest-angle-host-vulkan.out').read_text(), vulkan_backend='lavapipe')
    require(proof.get('guestGraphicsRoute') == route and
            proof.get('rendererEvidence') == route['guestRenderer'] + route['hostVulkanDevice'], 'Graphics route changed')
    owner = release._json(directory / 'EMULATOR-OWNER.json')
    require(owner.get('result') == 0 and not owner.get('cleanupErrors') and
            owner.get('runtime') == {key: proof[key] for key in owner['runtime']} and
            owner.get('harnessRevision') == artifacts.identity()['sourceRevision'] and
            owner.get('harnessRun') == artifacts.identity()['workflowRun'], 'Recheck owner did not complete in this harness')
    evidence = directory / 'production-ui'
    receipt = release._json(evidence / 'android-acceptance.json')
    require(receipt.get('phase') == phase and receipt.get('identity') == IDENTITY and
            receipt.get('ownerProofSha256') == artifacts.digest(proof_path) and
            receipt.get('executionPassed') is True and receipt.get('cleanupStatus') == 'complete',
            'Original265 phase execution/cleanup incomplete')
    actual = {path.relative_to(evidence).as_posix() for path in evidence.rglob('*')
              if path.is_file() and path != evidence / 'android-acceptance.json'}
    require(len({entry['path'] for entry in receipt['files']}) == len(receipt['files']) and
            actual == {entry['path'] for entry in receipt['files']}, 'Missing/extra phase raw evidence')
    for entry in receipt['files']:
        from android_acceptance_verify import sealed_file
        sealed_file(evidence, receipt, entry['path'])
    if phase == 'flutter-ui':
        require(receipt.get('matrixImages') == 12 and len(receipt.get('uiInvocations', [])) == 2 and
                receipt.get('appRenderer', {}).get('backend') == 'impeller', 'Capability precheck lacks actual matrix/Impeller/restart')
        from android_acceptance_verify import ui_capture
        from flutter_android_qa import renderer, Producer, CLASS
        from instrumentation_shards import parse_log
        info = release._json(evidence / 'BUILD_INFO.json')
        invocations = receipt['uiInvocations']
        require([row['method'] for row in invocations] ==
                ['consentNavigationTheme12', 'persistedConsentAfterProcessRestart'] and
                invocations[0]['pid'] != invocations[1]['pid'], 'Representative consent/restart selection changed')
        for row in invocations:
            method = row['method']
            capture, _ = ui_capture(evidence / method, method, info, row['qaToken'])
            records = parse_log(evidence / (method + '.out'))
            require(len(records) == 1 and records[0]['status'] == 'passed' and
                    records[0]['className'] + '#' + records[0]['testName'] == CLASS + '#' + method and
                    artifacts.digest(evidence / method / 'receipt.json') == row['receiptSha256'] and
                    capture['pid'] == row['pid'], 'Representative raw instrumentation differs')
            log = (evidence / (method + '-renderer.out')).read_text(errors='replace')
            require(renderer(log, row['pid']) == row['renderer'], 'Representative PID renderer differs')
            Producer.check_process_log(log, row['pid'])
    return dict(phase=phase, originalProducer=IDENTITY, harnessIdentity=artifacts.identity(),
                ownerSha256=artifacts.digest(directory / 'EMULATOR-OWNER.json'),
                receiptSha256=artifacts.digest(evidence / 'android-acceptance.json'),
                renderer=proof['guestGraphicsRoute'], executionPassed=True, releaseAccepted=False)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=('fetch', 'phase', 'aggregate'))
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--phase', choices=('flutter-ui', 'flutter-platform', 'flutter-full'))
    args = parser.parse_args()
    require(enabled(), 'Explicit Lite265 recheck required')
    if args.action == 'fetch':
        fetch(args.directory)
        return
    if args.action == 'phase':
        require(args.phase is not None, 'Phase required')
        result = phase_receipt(args.directory, args.phase)
        output = args.directory / 'RECHECK-PHASE.json'
    else:
        from android_acceptance_verify import aggregate
        phases = [phase_receipt(args.directory / 'execution' / phase, phase, live=False) for phase in
                  ('flutter-platform', 'flutter-full')]
        phases.append(phase_receipt(args.directory / 'precheck' / 'flutter-ui', 'flutter-ui', live=False))
        execution = aggregate(args.directory / 'execution', IDENTITY['sourceRevision'], IDENTITY['workflowRun'], 1, require_full=False)
        result = dict(schema=1, kind='original265-prebuilt-recheck', originalProducer=IDENTITY,
                      harnessIdentity=artifacts.identity(), originalArchive=ARCHIVE, phases=phases,
                      androidExecution=execution, exactAndroidExecutionPassed=True,
                      appCompiled=False, releaseAccepted=False,
                      unresolvedCoverage=execution['unresolvedCoverage'])
        output = args.directory / 'ORIGINAL265-RECHECK.json'
    output.write_text(json.dumps(result, indent=2) + '\n')


if __name__ == '__main__':
    main()
