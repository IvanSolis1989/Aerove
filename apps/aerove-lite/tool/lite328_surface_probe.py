"""Fixed run328 original APK, unchanged graphics, independent pixel diagnosis only."""
import argparse
import json
import os
from pathlib import Path
import re
import sys

import lite236_pixel_diagnostic as base
from lite236_pixel_diagnostic import require, sha

APP = dict(sourceRevision='2288e88304783ccc7113f7c1eecc5b7cd43a53b5',
           sourceTree='11a94ba01a840edfdb2b046753b537b5db546669',
           workflowRun='42482381897', runAttempt=1, runNumber=328)
ARCHIVE = dict(id=13619525870, name='AEROVE-Lite-runtime-contract-attempt-1',
               size_in_bytes=59798979,
               digest='sha256:f15dc870085cc509390a89fea5628b009f0c34bf25c397d62989657f08930f52')
APK_SHA = 'ba6a826c487ba2f243aeed306f5ab5bdd3c1545fff2476a847b297c4404aacfa'
INFO_SHA = 'ac51cf3ee5c8c75c8027f02edd77145e3f543868af5b406cc8f738fd62fb2a19'
MANIFEST_SHA = 'd4489c7248d44b2306cf065b82fe7080d7af8b31c9a6b5e2e4baf97acf1a0782'
SELECTOR = 'dev.aerove.lite.SurfacePixelDiagnosticTest#testOriginal328SurfaceWindowAndCompositor'


def guard_execution():
    require(os.environ.get('GITHUB_REPOSITORY') == 'IvanSolis1989/Aerove'
            and os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch'
            and os.environ.get('GITHUB_REF') == 'refs/heads/dot/cloud-j316-checkpoint-20261108',
            'Original328 requires explicit isolated diagnostic workflow')
    require(not any(value for key, value in os.environ.items()
                    if key.startswith(('AEROVE_ORIGINAL', 'AEROVE_LITE236', 'AEROVE_LITE257',
                                       'AEROVE_LITE265', 'AEROVE_LITE290', 'AEROVE_GFXSTREAM',
                                       'AEROVE_EMULATOR_SIGILL', 'AEROVE_QA79', 'AEROVE_CURRENT_RENDERER'))),
            'Original328 cannot mix historical runtime or graphics opt-ins')


def validate_api(api):
    require(all(api.get(k) == v for k, v in ARCHIVE.items()) and api.get('expired') is False
            and api.get('url') == 'https://api.github.com/repos/IvanSolis1989/Aerove/actions/artifacts/' + str(ARCHIVE['id'])
            and api.get('workflow_run', {}).get('id') == int(APP['workflowRun'])
            and api['workflow_run'].get('head_sha') == APP['sourceRevision']
            and api['workflow_run'].get('repository_id') == 1429390079
            and api['workflow_run'].get('head_repository_id') == 1429390079,
            'Fixed original328 artifact API identity differs')


def original(args):
    api = json.loads(args.artifact_api.read_text(encoding='utf-8-sig'))
    validate_api(api)
    sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
    from tooling.product_source import bind_archive
    from ci_artifacts import verify
    payload = bind_archive(args.archive, args.runtime_directory, api)
    verify(args.runtime_directory, APP, runtime=True)
    require(payload['BUILD_INFO.json']['sha256'] == INFO_SHA
            and payload['apks/app-release.apk']['sha256'] == APK_SHA
            and sha(args.runtime_directory / 'source-manifest.json') == MANIFEST_SHA,
            'Original328 APK, BUILD_INFO or seal bytes differ')
    info = json.loads((args.runtime_directory / 'BUILD_INFO.json').read_text())
    expected = dict(product='AEROVE Lite', application_id=base.PACKAGE,
                    version='0.2.0-beta.7', version_code=39, min_sdk=33,
                    signer_sha256=base.SIGNER, source=APP['sourceRevision'], tree=APP['sourceTree'],
                    abi='x86_64', workflow='https://github.com/IvanSolis1989/Aerove/actions/runs/' + APP['workflowRun'],
                    run_number=328, run_attempt=1, apk_sha256=APK_SHA,
                    launcher_activity=base.PACKAGE + '.FlutterMainActivity')
    require(all(info.get(k) == v for k, v in expected.items()), 'Original328 package metadata differs')
    return dict(artifactId=ARCHIVE['id'], artifactApiSha256=sha(args.artifact_api),
                actualZipSha256=ARCHIVE['digest'][7:], originalBuildInfoSha256=INFO_SHA,
                originalApkSha256=APK_SHA, originalSealSha256=MANIFEST_SHA)


class Diagnostic(base.Diagnostic):
    def __init__(self, args):
        require(getattr(args, 'app_renderer', 'default') == 'default'
                and not args.disable_queue_submit_with_commands
                and args.surface_test_apk and args.surface_test_info,
                'Original328 requires unchanged renderer and independent Surface runner')
        require(getattr(args, 'original_run_number', 328) == 328, 'Original328 only')
        super().__init__(args)
        self.app, self.apk_sha, self.info_sha = APP, APK_SHA, INFO_SHA
        self.receipt.update(producer='lite328_surface_probe.py', originalAppIdentity=APP,
                            executorScriptSha256=sha(__file__), baseScriptSha256=sha(base.__file__),
                            promotionAccepted=False, appCompiled=False)

    def original_artifact(self):
        guard_execution()
        return original(self.args)

    def resilient_surface(self):
        return True

    def app_version(self):
        return '0.2.0-beta.7', '39'

    def surface_selector(self):
        return SELECTOR

    def surface_capture(self):
        harness = json.loads(self.args.surface_test_info.read_text())
        source = Path(__file__).resolve().parents[1] / 'android-qa/src/main/java/dev/aerove/lite/SurfacePixelDiagnosticTest.java'
        require(harness.get('sourceSha256') == sha(source), 'Actual diagnostic Java source differs')
        super().surface_capture()
        directory = self.args.output / 'surface-capture'
        capture = json.loads((directory / 'receipt.json').read_text())
        require(capture['originalApp']['apkSha256'] == APK_SHA
                and capture['originalApp']['source'] == APP['sourceRevision']
                and capture['originalApp']['tree'] == APP['sourceTree'], 'Captured original328 identity differs')
        require(capture['windowCopy']['image']['sha256'] == sha(directory / 'window.png'),
                'Captured Window pixels changed')
        for field in ('surfaceCopy', 'windowCopy', 'compositorCopy'):
            image = capture[field]['image']
            stats = image['pixelStats']
            require(stats.get('observationOnly') is True and stats.get('pixels') == image['width'] * image['height']
                    and all(type(stats.get(key)) is int and 0 <= stats[key] <= stats['pixels']
                            for key in ('rgbBlack', 'transparent', 'sentinel')), 'Pixel observation is incomplete')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('runtime-directory', 'archive', 'artifact-api', 'output', 'surface-test-apk', 'surface-test-info'):
        parser.add_argument('--' + name, type=Path, required=True)
    for name in ('source', 'run', 'attempt'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--sdk', type=Path, default=Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or '/missing-sdk'))
    parser.add_argument('--avd', default='test')
    parser.add_argument('--original-run-number', type=int, choices=(328,), default=328)
    parser.set_defaults(app_renderer='default', disable_queue_submit_with_commands=False)
    args = parser.parse_args()
    require(re.fullmatch(r'[0-9a-f]{40}', args.source) and args.run.isdecimal() and int(args.run) > 0
            and args.attempt.isdecimal() and int(args.attempt) > 0
            and re.fullmatch(r'[A-Za-z0-9_.-]+', args.avd), 'Truthful executor identity and named owned AVD required')
    args.output.mkdir(parents=True, exist_ok=False)
    os.nice(19) if sys.platform == 'linux' else None
    return Diagnostic(args).run()


if __name__ == '__main__':
    raise SystemExit(main())
