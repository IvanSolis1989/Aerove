#!/usr/bin/env python3
"""Find and validate an unchanged Aerove Android build for promotion."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
import re
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any
from urllib.parse import urlencode

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "tooling"))
import product_source

if __package__:
    from . import aerove_version as product
    from . import xclash_version as version
else:
    import aerove_version as product
    import xclash_version as version


WORKFLOW = '.github/workflows/xclash-android.yaml'
APK_NAME = 'Aerove-android-arm64.apk'
OUTPUTS = ('run_id', 'run_number', 'artifact_id', 'generated_artifact_id')
LIBRARIES = {'libflutter.so', 'libapp.so', 'libclash.so', 'librust_api.so'}


class ArtifactError(ValueError):
    """Artifact evidence is unavailable, malformed or inconsistent."""


def _positive(value: Any, label: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value < 1:
        raise ArtifactError(f'{label} must be a positive integer')
    return value


def _text(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise ArtifactError(f'{label} must be a nonempty string')
    return value


def _sha(value: Any, label: str) -> str:
    if not isinstance(value, str) or version.SHA1_RE.fullmatch(value) is None:
        raise ArtifactError(f'{label} must be a full lowercase SHA-1')
    return value


def _repository(value: str) -> str:
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*', value):
        raise ArtifactError('repository must be an owner/repository name')
    return value


def _json(contents: str) -> Any:
    def pairs(items: list[tuple[str, Any]]) -> dict[str, Any]:
        result = {}
        for key, value in items:
            if key in result:
                raise ArtifactError(f'duplicate JSON field: {key}')
            result[key] = value
        return result

    def constant(value: str) -> None:
        raise ArtifactError(f'invalid JSON constant: {value}')

    try:
        return json.loads(contents, object_pairs_hook=pairs, parse_constant=constant)
    except (json.JSONDecodeError, UnicodeError) as error:
        raise ArtifactError(f'invalid JSON: {error}') from error


def _api_items(endpoint: str, key: str, **query: str) -> list[dict[str, Any]]:
    items = []
    identities = set()
    total = None
    page = 1
    while True:
        url = endpoint + '?' + urlencode({**query, 'per_page': 100, 'page': page})
        try:
            response = subprocess.run(
                ['gh', 'api', '--method', 'GET', url,
                 '-H', 'Accept: application/vnd.github+json',
                 '-H', 'X-GitHub-Api-Version: 2022-11-28'],
                capture_output=True, text=True, check=False,
            )
        except OSError as error:
            raise ArtifactError(f'Could not run gh api: {error}') from error
        if response.returncode != 0:
            raise ArtifactError(f'gh api failed for {endpoint}: {response.stderr.strip()}')
        payload = _json(response.stdout)
        if not isinstance(payload, dict):
            raise ArtifactError(f'{endpoint}: expected a JSON object')
        count = payload.get('total_count')
        entries = payload.get(key)
        if (not isinstance(count, int) or isinstance(count, bool) or count < 0
                or not isinstance(entries, list)):
            raise ArtifactError(f'{endpoint}: invalid total_count/{key}')
        if total is None:
            total = count
        if count != total or (not entries and len(items) < total):
            raise ArtifactError(f'{endpoint}: inconsistent pagination')
        for item in entries:
            if not isinstance(item, dict):
                raise ArtifactError(f'{endpoint}: invalid list entry')
            identity = _positive(item.get('id'), f'{endpoint} id')
            if identity in identities:
                raise ArtifactError(f'{endpoint}: duplicate id across pages')
            identities.add(identity)
            items.append(item)
        if len(items) > total:
            raise ArtifactError(f'{endpoint}: pagination exceeds total_count')
        if len(items) == total:
            return items
        page += 1


def _same_repository(run: dict[str, Any], field: str, repository: str) -> bool:
    if field not in run:
        raise ArtifactError(f'workflow run is missing {field}')
    value = run[field]
    if value is None:
        return False
    if not isinstance(value, dict):
        raise ArtifactError(f'workflow run has invalid {field}')
    return _text(value.get('full_name'), field).casefold() == repository.casefold()


def _successful_jobs(repository: str, run: dict[str, Any], source: str) -> bool:
    attempt = run['run_attempt']
    jobs = _api_items(
        f'repos/{repository}/actions/runs/{run["id"]}/attempts/{attempt}/jobs', 'jobs',
    )
    required = {'verify': [], 'apk': []}
    for job in jobs:
        name = _text(job.get('name'), 'job name')
        run_id = _positive(job.get('run_id'), 'job run_id')
        head_sha = _sha(job.get('head_sha'), 'job head_sha')
        status = _text(job.get('status'), 'job status')
        if 'conclusion' not in job:
            raise ArtifactError('job is missing conclusion')
        conclusion = job['conclusion']
        if conclusion is not None:
            _text(conclusion, 'job conclusion')
        job_attempt = job.get('run_attempt', attempt)
        _positive(job_attempt, 'job run_attempt')
        if name in required:
            required[name].append(
                run_id == run['id'] and head_sha == source and job_attempt == attempt
                and status == 'completed' and conclusion == 'success'
            )
    return all(results == [True] for results in required.values())


def find_artifacts(*, repository: str, source: str, exclude_run: int) -> dict[str, Any]:
    _repository(repository)
    _sha(source, 'source')
    _positive(exclude_run, 'exclude_run')
    runs = _api_items(
        f'repos/{repository}/actions/workflows/xclash-android.yaml/runs',
        'workflow_runs', head_sha=source, status='success',
    )
    candidates = []
    for run in runs:
        _positive(run.get('run_number'), 'run_number')
        _positive(run.get('run_attempt'), 'run_attempt')
        _sha(run.get('head_sha'), 'run head_sha')
        for field in ('path', 'event', 'status'):
            _text(run.get(field), f'run {field}')
        if 'conclusion' not in run:
            raise ArtifactError('workflow run is missing conclusion')
        conclusion = run['conclusion']
        if conclusion is not None:
            _text(conclusion, 'run conclusion')
        same_repo = _same_repository(run, 'repository', repository)
        same_head_repo = _same_repository(run, 'head_repository', repository)
        if (run['id'] != exclude_run and run['head_sha'] == source
                and run['path'] == WORKFLOW and same_repo and same_head_repo
                and run['event'] in ('workflow_dispatch', 'push')
                and run['status'] == 'completed' and conclusion == 'success'):
            candidates.append(run)
    for run in sorted(candidates, key=lambda item: (item['run_number'], item['id']), reverse=True):
        if not _successful_jobs(repository, run, source):
            continue
        artifacts = _api_items(f'repos/{repository}/actions/runs/{run["id"]}/artifacts', 'artifacts')
        named = {'Aerove-android-arm64': [], 'xclash-generated': []}
        for artifact in artifacts:
            name = _text(artifact.get('name'), 'artifact name')
            if not isinstance(artifact.get('expired'), bool):
                raise ArtifactError('artifact expired must be a boolean')
            provenance = artifact.get('workflow_run')
            same_run = True
            if provenance is not None:
                if not isinstance(provenance, dict):
                    raise ArtifactError('invalid artifact workflow_run')
                artifact_run = _positive(provenance.get('id'), 'artifact workflow_run id')
                artifact_source = _sha(provenance.get('head_sha'), 'artifact head_sha')
                same_run = artifact_run == run['id'] and artifact_source == source
            if name in named:
                named[name].append((artifact, same_run))
        if any(len(matches) != 1 or matches[0][0]['expired'] or not matches[0][1]
               for matches in named.values()):
            continue
        return {
            'run_id': run['id'], 'run_number': run['run_number'],
            'artifact_id': named['Aerove-android-arm64'][0][0]['id'],
            'generated_artifact_id': named['xclash-generated'][0][0]['id'],
        }
    return dict.fromkeys(OUTPUTS, '')


def _files(directory: Path, expected: set[str]) -> None:
    if directory.is_symlink() or not directory.is_dir():
        raise ArtifactError(f'Not an artifact directory: {directory}')
    entries = list(directory.iterdir())
    if {entry.name for entry in entries} != expected:
        raise ArtifactError(f'Unexpected or missing artifact files in {directory}')
    if any(entry.is_symlink() or not entry.is_file() for entry in entries):
        raise ArtifactError(f'Artifact files must be regular files: {directory}')


def _checksums(directory: Path) -> None:
    checksums = (directory / 'SHA256SUMS').read_text(encoding='utf-8').splitlines()
    expected = {APK_NAME, 'BUILD_INFO.json'}
    seen = set()
    if len(checksums) != 2:
        raise ArtifactError('SHA256SUMS must contain exactly two entries')
    for line in checksums:
        match = re.fullmatch(r'([0-9a-f]{64}) [ *]([^\r\n]+)', line)
        if match is None or match[2] not in expected or match[2] in seen:
            raise ArtifactError('Invalid, duplicate or escaping checksum entry')
        seen.add(match[2])
        digest = hashlib.sha256()
        with (directory / match[2]).open('rb') as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b''):
                digest.update(chunk)
        if digest.hexdigest() != match[1]:
            raise ArtifactError(f'checksum mismatch: {match[2]}')


def _apk(path: Path) -> None:
    try:
        with zipfile.ZipFile(path) as apk:
            entries = apk.infolist()
            names = [entry.filename for entry in entries]
            if len(names) != len(set(names)):
                raise ArtifactError('APK has duplicate ZIP entries')
            for name in names:
                parts = PurePosixPath(name).parts
                if ('\\' in name or '\0' in name or not parts
                        or PurePosixPath(name).is_absolute() or '..' in parts):
                    raise ArtifactError(f'Invalid APK entry path: {name}')
            corrupt = apk.testzip()
            if corrupt is not None:
                raise ArtifactError(f'APK CRC mismatch: {corrupt}')
            found = set()
            for entry in entries:
                name = PurePosixPath(entry.filename).name
                if name not in LIBRARIES:
                    continue
                if entry.filename != f'lib/arm64-v8a/{name}':
                    raise ArtifactError(f'Own APK library is not ARM64: {entry.filename}')
                with apk.open(entry) as handle:
                    header = handle.read(64)
                if (len(header) != 64 or header[:7] != b'\x7fELF\x02\x01\x01'
                        or struct.unpack_from('<H', header, 18)[0] != 183
                        or struct.unpack_from('<I', header, 20)[0] != 1
                        or struct.unpack_from('<H', header, 52)[0] != 64):
                    raise ArtifactError(f'Own APK library is not an ARM64 ELF64: {name}')
                found.add(name)
            if found != LIBRARIES:
                raise ArtifactError('APK is missing required ARM64 libraries')
    except (zipfile.BadZipFile, RuntimeError, NotImplementedError, EOFError) as error:
        raise ArtifactError(f'Invalid APK ZIP/CRC: {error}') from error


def validate_artifacts(
    *, directory: Path, generated_directory: Path, source: str, tree: str,
    repository: str, run_id: int, run_number: int, metadata: Path,
    pubspec: Path | None = None,
) -> str:
    _sha(source, 'source')
    _sha(tree, 'tree')
    _repository(repository)
    _positive(run_id, 'run_id')
    _positive(run_number, 'run_number')
    number = version.FORK_VERSION_CODE_BASE + run_number
    if number > version.ANDROID_VERSION_CODE_LIMIT:
        raise ArtifactError('Android version code exceeds the platform limit')
    _files(directory, {APK_NAME, 'BUILD_INFO.json', 'SHA256SUMS'})
    _files(generated_directory, {'xclash-generated.patch', 'xclash-verified-tree.txt'})
    if (generated_directory / 'xclash-generated.patch').stat().st_size != 0:
        raise ArtifactError('Generated patch must be empty to reuse the build')
    verified_tree = (generated_directory / 'xclash-verified-tree.txt').read_bytes()
    if verified_tree not in (tree.encode('ascii'), (tree + '\n').encode('ascii')):
        raise ArtifactError('Generated verified tree does not match the source tree')
    _checksums(directory)
    evidence = _json(metadata.read_text(encoding='utf-8'))
    try:
        version.validate_metadata_shape(evidence)
    except version.VersionError as error:
        raise ArtifactError(f'Invalid upstream metadata: {error}') from error
    expected_upstream = {
        'repository': evidence['repository'], 'sourceCommit': evidence['source_commit'],
        'tag': evidence['tag'], 'tagCommit': evidence['tag_commit'],
        'sourceBranches': evidence['source_branches'],
        'pubspecVersion': evidence['pubspec_version'],
        'pubspecSourceCommit': evidence['pubspec_source_commit'],
    }
    info = _json((directory / 'BUILD_INFO.json').read_text(encoding='utf-8'))
    expected = {
        'source': source, 'version': f'{evidence["version"]}-X+{number}',
        'appEnv': 'pre' if '-' in evidence['version'] else 'stable',
        'workflow': f'https://github.com/{repository}/actions/runs/{run_id}',
        'signing': 'persistent Android test key', 'applicationId': 'com.follow.clash.dev',
        'upstream': expected_upstream,
    }
    if pubspec is not None:
        try:
            identity = product.resolve_product_version(
                pubspec.read_text(encoding='utf-8'), run_number,
            )
        except product.VersionError as error:
            raise ArtifactError(f'Invalid product version: {error}') from error
        expected['version'] = f'{identity["name"]}+{identity["number"]}'
        expected['appEnv'] = identity['app_env']
    if not isinstance(info, dict) or info != expected:
        raise ArtifactError('BUILD_INFO source/run/version/channel/package/signing/upstream mismatch')
    _apk(directory / APK_NAME)
    return expected['version']



RECHECK_SOURCE = '2dd3c67864236087b9a681e7772ad08eaf59a295'
RECHECK_RUN = 40488925368
RECHECK_TEST = 'apps/aerove/test/views/dashboard/traffic_test.dart'
RECHECK_TEST_BLOBS = ('2d9fe51959f80315d21a1ca300ad60fd1bd71eca',
                     'fd1a3471654b81e884b487f7d9c2cfaddabee394')
RECHECK_WIRING = {WORKFLOW, 'apps/aerove/tool/aerove_artifact.py',
                 'apps/aerove/tool/test_aerove_artifact.py',
                 'apps/aerove/tool/test_aerove_workflow.py'}
RECHECK_WORKFLOW_BLOB = '8d7fb8ca4854dc29a2d99c07cf0051065608ce53'
RECHECK_ARTIFACTS = {
    'Aerove-android-arm64': (12728776871, 64740563,
                            'sha256:b94af041ffddc75397a176b7766d6a11e320826029446e92fbc5963bf3cb8f21'),
    'xclash-generated': (12726327557, 341,
                        'sha256:6a01730f638c07a1d5e7624db330e4316c5133b399f59a8c2cc21ecf85f7cb26'),
}
RECHECK_PAYLOADS = {
    APK_NAME: (66524964, '8fff93954945b16885626674b77f76d9452759935bddcfa281db460fd4ad8db3'),
    'BUILD_INFO.json': (650, '0894b4b12063e7de64cf7160e0c5040126585e8913c0c07e7a52075c37411d21'),
    'SHA256SUMS': (173, '9cbad27d7ccbbeac4b109b50dc942d7c0ad7f39fe3fe431bb88ba32809b859f0'),
    'xclash-generated.patch': (0, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'),
    'xclash-verified-tree.txt': (41, 'cf787ce5264df880b761365929c4932731d9ed63d7a97302b75c694af8acec7e'),
}
RECHECK_ARTIFACT = 'aerove-original-apk-recheck'
RECHECK_SIGNER = '0fc82c3b7b089b6244d9ed167b352ff76185417d07689853caca286fbdeb6e60'
RECHECK_RUN_74 = 40797508485
RECHECK_DESCRIPTION = 'Immutable original APK run to recheck (reviewed Std55 40488925368 or Std74 40797508485 only)'
RECHECK_POLICY_74 = dict(
    run_id=40797508485, run_number=74,
    source='806558b3c397ebd6b3d4ea4d558a442828c6d285',
    tree='581fa6f42a75e37d0dce94fc97b27a49863c215f',
    test='apps/aerove/test/views/theme_test.dart',
    test_blobs=('dc9256478d86328b350e2b9fb2ea870ac2e1b8bd', 'd7e2dca79caaf546573bf58507588082ef65468e'),
    workflow_blob='af037f37630b64001823087d56bde3fd884861c2',
    artifacts={
        'Aerove-android-arm64': (12864444196, 64763537,
            'sha256:c339960c765a36d56f51fbb426477cb9c930c23dcf633a2f1e95790c83facf61'),
        'xclash-generated': (12864861108, 341,
            'sha256:5b19075eb7ae01594accb36728b06fb920a9c7b558ef8499fe4516ef33806bc4'),
    },
    payloads={
        APK_NAME: (66547908, '514a3bd5190768ee98dfd6df6c7f297ef507b120ba4d172bc2ed7ba0e5fcb864'),
        'BUILD_INFO.json': (651, '77a0aabb3407ed280b11999e5f2a81fd40d261d5f97f96708b85e3cdb3ef0fbb'),
        'SHA256SUMS': (173, '851b49cf7c46f74c328ad1efee678f0a35a91027ded269e037ad4a4f97d49824'),
        'xclash-generated.patch': (0, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'),
        'xclash-verified-tree.txt': (41, 'afed2d61cc7ab9fc26f5ab37846f84e7c21304348311c7192ade005da16e5864'),
    },
    created_at={'Aerove-android-arm64': '2026-10-29T01:11:30Z', 'xclash-generated': '2026-10-29T01:01:49Z'},
    producers={
        'apk': dict(id=123198625740, started_at='2026-10-29T01:01:59Z', completed_at='2026-10-29T01:11:36Z'),
        'prep': dict(id=123197534652, started_at='2026-10-29T00:58:53Z', completed_at='2026-10-29T01:01:56Z'),
    },
    signer=RECHECK_SIGNER, version='0.11.0-beta.14+2026102274', promotion_request=True,
)


def _recheck_policy(build_run: int, repository: str = 'IvanSolis1989/Aerove') -> dict[str, Any]:
    _positive(build_run, 'reviewed original build run')
    if repository != 'IvanSolis1989/Aerove':
        raise ArtifactError('Only reviewed original APK runs in the official repository are approved')
    if build_run == RECHECK_RUN:
        return dict(run_id=RECHECK_RUN, source=RECHECK_SOURCE, test=RECHECK_TEST,
                    test_blobs=RECHECK_TEST_BLOBS, workflow_blob=RECHECK_WORKFLOW_BLOB,
                    artifacts=RECHECK_ARTIFACTS, payloads=RECHECK_PAYLOADS, signer=RECHECK_SIGNER,
                    run_number=55, tree=None, producers=None, version=None, promotion_request=False)
    if build_run == RECHECK_RUN_74:
        return RECHECK_POLICY_74
    raise ArtifactError('Only explicitly reviewed original APK runs are approved')


def _command(arguments: list[str], root: Path | None = None) -> str:
    result = subprocess.run(arguments, cwd=root, capture_output=True, text=True, check=False)
    if result.returncode:
        raise ArtifactError(f'{arguments[0]} failed: {result.stderr.strip()}')
    return result.stdout


def _git(root: Path, *arguments: str) -> str:
    return _command(['git', *arguments], root).strip()


def _api_object(endpoint: str, *, allow_not_found=False) -> dict[str, Any] | None:
    command = ['gh', 'api', '--method', 'GET', endpoint,
               '-H', 'Accept: application/vnd.github+json']
    if allow_not_found:
        response = subprocess.run(command, capture_output=True, text=True, check=False)
        result = _json(response.stdout)
        if response.returncode:
            if isinstance(result, dict) and str(result.get('status')) == '404':
                return None
            raise ArtifactError(f'gh api failed for {endpoint}: {response.stderr.strip()}')
    else:
        result = _json(_command(command))
    if not isinstance(result, dict):
        raise ArtifactError('Expected an API object')
    return result


def _run_identity(repository: str, run: dict[str, Any], source: str) -> None:
    for field in ('id', 'run_number', 'run_attempt'):
        _positive(run.get(field), field)
    if (run.get('head_sha') != source or run.get('path') != WORKFLOW
            or run.get('event') not in ('push', 'workflow_dispatch')
            or not _same_repository(run, 'repository', repository)
            or not _same_repository(run, 'head_repository', repository)):
        raise ArtifactError('Run repository/workflow/event/source mismatch')


def _recheck_jobs(repository: str, run: dict[str, Any], *, final: bool, build_run: int = RECHECK_RUN) -> None:
    policy = _recheck_policy(build_run, repository)
    if __package__:
        from . import ci_verify
    else:
        import ci_verify
    required = {'prep', 'static', 'native', 'verify', 'reuse'}
    if policy['promotion_request']:
        required.add('promotion_request')
    for group, rows in ci_verify.matrices().items():
        required.update(f'{group} ({row["suite"]} {row["shard"]})' for row in rows)
    if final:
        required.add('recheck')
    jobs = _api_items(f'repos/{repository}/actions/runs/{run["id"]}/attempts/{run["run_attempt"]}/jobs', 'jobs')
    names = [job.get('name') for job in jobs]
    for name in required | {'apk'}:
        matches = [job for job in jobs if job.get('name') == name]
        wanted = 'skipped' if name == 'apk' else 'success'
        if (len(matches) != 1 or matches[0].get('run_id') != run['id']
                or matches[0].get('head_sha') != run['head_sha']
                or matches[0].get('run_attempt', run['run_attempt']) != run['run_attempt']
                or matches[0].get('status') != 'completed' or matches[0].get('conclusion') != wanted):
            raise ArtifactError(f'Recheck requires exactly one completed {name}: {wanted}')
    permitted = required | {'recheck', 'apk', 'release'}
    if any(name not in permitted for name in names) or len(names) != len(set(names)):
        raise ArtifactError('Recheck has unexpected or duplicate jobs')
    release = [job for job in jobs if job.get('name') == 'release']
    if release and any(job.get('conclusion') != 'skipped' for job in release):
        raise ArtifactError('Recheck must not publish')


def _workflow_inputs_unchanged(before: str, after: str, *, build_run: int = RECHECK_RUN) -> None:
    policy = _recheck_policy(build_run)
    old_description = 'Immutable original APK run to recheck (40488925368 only)'
    if policy['promotion_request']:
        if (before.count(old_description) != 1 or after.count(RECHECK_DESCRIPTION) != 1
                or before.replace(old_description, RECHECK_DESCRIPTION) != after):
            raise ArtifactError('Recheck changed protected workflow beyond the reviewed input description')
        return
    def job(text: str, name: str) -> str:
        match = re.search(rf'(?ms)^  {name}:\n.*?(?=^  [a-z][a-z_-]*:|\Z)', text)
        if match is None:
            raise ArtifactError(f'Missing protected workflow job: {name}')
        return match[0]
    addition = ('      original_build_run:\n'
                '        description: Immutable original APK run to recheck (40488925368 only)\n'
                '        type: string\n        default: \'\'\n')
    before_header, after_header = before.split('jobs:\n', 1)[0], after.split('jobs:\n', 1)[0]
    if after_header.replace(addition, '') != before_header or after_header.count(addition) != 1:
        raise ArtifactError('Recheck changed workflow toolchain/defaults/permissions/inputs')
    for name in ('prep', 'static', 'native', 'render', 'regressions', 'verify', 'apk'):
        old, new = job(before, name), job(after, name)
        if name == 'prep':
            new = new.replace('inputs.verification_recheck || ', '')
        if name in ('render', 'regressions'):
            new = new.replace('fail-fast: false', 'fail-fast: true')
        if name == 'apk':
            new = new.replace("!inputs.verification_recheck && (github.event_name == 'push' || inputs.build_apk)",
                              "github.event_name == 'push' || inputs.build_apk")
        if old != new:
            raise ArtifactError(f'Recheck changed protected production/verification job: {name}')


def recheck_inputs(root: Path, source: str, build_run: int = RECHECK_RUN) -> list[dict[str, str]]:
    policy = _recheck_policy(build_run)
    _sha(source, 'test_harness_source')
    original = policy['source']
    _git(root, 'merge-base', '--is-ancestor', original, source)
    paths = _command(['git', 'diff', '--no-renames', '--name-only', '-z', original, source], root).split('\0')
    result = []
    for path in filter(None, paths):
        documentation = path.startswith('docs/') and path.endswith('.md')
        if path not in RECHECK_WIRING | {policy['test']} and not documentation:
            raise ArtifactError(f'Recheck changed an unapproved APK/build input: {path}')
        entries = [_git(root, 'ls-tree', revision, '--', path) for revision in (original, source)]
        if any(not re.fullmatch(r'100644 blob [0-9a-f]{40}\t' + re.escape(path), item) for item in entries):
            raise ArtifactError(f'Recheck requires existing regular files: {path}')
        before, after = [entry.split()[2] for entry in entries]
        if path == policy['test'] and (before, after) != policy['test_blobs']:
            raise ArtifactError('Recheck test is not the reviewed original-run test fix')
        result.append(dict(path=path, before=before, after=after))
    if policy['test'] not in {row['path'] for row in result}:
        raise ArtifactError('Recheck must contain the exact reviewed original-run test fix')
    if _git(root, 'rev-parse', f'{source}:{WORKFLOW}') != policy['workflow_blob']:
        raise ArtifactError('Recheck workflow is not the exact reviewed verification wiring')
    _workflow_inputs_unchanged(
        _command(['git', 'show', f'{original}:{WORKFLOW}'], root),
        _command(['git', 'show', f'{source}:{WORKFLOW}'], root),
        build_run=build_run,
    )
    return result


def original_recheck_build(repository: str, build_run: int) -> dict[str, Any]:
    _repository(repository)
    policy = _recheck_policy(build_run, repository)
    original = policy['source']
    run = _api_object(f'repos/{repository}/actions/runs/{build_run}')
    _run_identity(repository, run, original)
    if (run['id'] != build_run or run['run_number'] != policy['run_number'] or run['run_attempt'] != 1
            or run.get('status') != 'completed' or run.get('conclusion') not in ('failure', 'success')):
        raise ArtifactError('Original run must retain its completed first attempt')
    jobs = _api_items(f'repos/{repository}/actions/runs/{build_run}/attempts/1/jobs', 'jobs')
    apk = [job for job in jobs if job.get('name') == 'apk']
    if (len(apk) != 1 or apk[0].get('status') != 'completed' or apk[0].get('conclusion') != 'success'
            or apk[0].get('head_sha') != original or apk[0].get('run_id') != build_run
            or apk[0].get('run_attempt', 1) != 1):
        raise ArtifactError('Original APK job has not succeeded in its original attempt')
    artifacts = _api_items(f'repos/{repository}/actions/runs/{build_run}/artifacts', 'artifacts')
    selected = dict(run_id=build_run, run_number=run['run_number'], build_source=original,
                    build_conclusion=run['conclusion'], build_attempt=1)
    for name, key in [('Aerove-android-arm64', 'artifact_id'), ('xclash-generated', 'generated_artifact_id')]:
        matches = [item for item in artifacts if item.get('name') == name]
        if len(matches) != 1 or matches[0].get('expired') is not False:
            raise ArtifactError(f'Original artifact is missing, duplicate or expired: {name}')
        item = matches[0]
        _positive(item.get('id'), 'artifact id')
        provenance = item.get('workflow_run')
        if not isinstance(provenance, dict) or provenance.get('id') != build_run or provenance.get('head_sha') != original:
            raise ArtifactError('Original artifact source/run provenance mismatch')
        if (item['id'], item.get('size_in_bytes'), item.get('digest')) != policy['artifacts'][name]:
            raise ArtifactError('Original artifact ID/size/digest differs from the fixed original-run evidence')
        if policy['producers'] is not None:
            producer_name = 'apk' if name == 'Aerove-android-arm64' else 'prep'
            matches = [job for job in jobs if job.get('name') == producer_name]
            pin = policy['producers'][producer_name]
            if (len(matches) != 1 or any(matches[0].get(field) != value for field, value in pin.items())
                    or matches[0].get('run_id') != build_run or matches[0].get('head_sha') != original
                    or matches[0].get('run_attempt', 1) != 1 or matches[0].get('status') != 'completed'
                    or matches[0].get('conclusion') != 'success'
                    or item.get('created_at') != policy['created_at'][name]):
                raise ArtifactError('Original artifact producer identity/time differs from the fixed original-run evidence')
            try:
                stamps = [datetime.fromisoformat(value.replace('Z', '+00:00'))
                          for value in (pin['started_at'], item['created_at'], pin['completed_at'])]
            except (KeyError, TypeError, AttributeError, ValueError) as error:
                raise ArtifactError('Original artifact has no producer time binding') from error
            if (any(stamp.tzinfo is None or stamp.utcoffset() != timezone.utc.utcoffset(stamp) for stamp in stamps)
                    or not stamps[0] <= stamps[1] <= stamps[2]):
                raise ArtifactError('Original artifact was not created by its fixed original-attempt producer')
        selected[key] = item['id']
    return selected


def recheck_plan(*, root: Path, repository: str, source: str, build_run: int) -> dict[str, Any]:
    policy = _recheck_policy(build_run, repository)
    changed = recheck_inputs(root, source, build_run)
    selected = original_recheck_build(repository, build_run)
    tree = _git(root, 'rev-parse', f'{policy["source"]}^{{tree}}')
    if policy['tree'] is not None and tree != policy['tree']:
        raise ArtifactError('Original full source tree differs from fixed original-run evidence')
    selected.update(build_tree=tree,
                    test_harness_source=source, changed=changed)
    return selected


def _digest(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def _original_payloads(directory: Path, generated: Path, build_run: int = RECHECK_RUN) -> None:
    policy = _recheck_policy(build_run)
    for name, expected in policy['payloads'].items():
        path = (generated if name.startswith('xclash-') else directory) / name
        if path.is_symlink() or not path.is_file() or (path.stat().st_size, _digest(path)) != expected:
            raise ArtifactError(f'Original Std{policy["run_number"]} payload bytes/hash mismatch: {name}')


def _verify_original_signer(output: str, expected: str) -> None:
    records, formats, invalid = [], [], 0
    pattern = re.compile(
        r'Signer (?P<index>#[1-9][0-9]*|\(minSdkVersion=(?P<minimum>[1-9][0-9]*)'
        r'(?P<dev> \(dev release=true\))?, maxSdkVersion=(?P<maximum>[1-9][0-9]*)\))'
        r' certificate SHA-256 digest:[ \t]*(?P<digest>[0-9a-fA-F]{64})'
    )
    for line in output.splitlines():
        folded = line.casefold()
        if 'certificate' not in folded or 'sha-256' not in folded:
            continue
        match = pattern.fullmatch(line.strip())
        if match is None:
            invalid += 1
            continue
        if match['minimum'] is not None:
            if (len(match['minimum']) > 10 or len(match['maximum']) > 10
                    or not 1 <= int(match['minimum']) <= int(match['maximum']) <= 2147483647):
                invalid += 1
                continue
            formats.append('sdk-range-dev' if match['dev'] else 'sdk-range')
        else:
            formats.append('numbered')
        records.append(match['digest'].lower())
    if invalid or records != [expected]:
        raise ArtifactError('Original APK signer mismatch: '
            f'parsed_records={len(records)}, invalid_records={invalid}, '
            f'formats={",".join(formats[:4]) or "none"}, '
            f'certificate_sha256={",".join(records[:4]) or "none"}, expected_match={records == [expected]}')


def recheck_receipt(*, root: Path, repository: str, source: str, build_run: int,
                    run_id: int, directory: Path, generated_directory: Path,
                    apksigner: Path, aapt: Path) -> dict[str, Any]:
    policy = _recheck_policy(build_run, repository)
    selected = recheck_plan(root=root, repository=repository, source=source, build_run=build_run)
    run = _api_object(f'repos/{repository}/actions/runs/{run_id}')
    _run_identity(repository, run, source)
    if (run.get('event') != 'workflow_dispatch' or run['id'] == build_run
            or run.get('head_branch') == 'main'):
        raise ArtifactError('Recheck must be an independent workflow dispatch')
    _recheck_jobs(repository, run, final=False, build_run=build_run)
    _original_payloads(directory, generated_directory, build_run)
    with tempfile.TemporaryDirectory() as temporary:
        fixed = Path(temporary)
        for name in ('pubspec.yaml', 'tool/upstream-version.json'):
            path = fixed / Path(name).name
            path.write_text(_command(['git', 'show', f'{policy["source"]}:apps/aerove/{name}'], root), encoding='utf-8')
        validate_artifacts(directory=directory, generated_directory=generated_directory,
                           source=policy['source'], tree=selected['build_tree'], repository=repository,
                           run_id=build_run, run_number=selected['run_number'],
                           metadata=fixed / 'upstream-version.json', pubspec=fixed / 'pubspec.yaml')
    apk = directory / APK_NAME
    certs = _command([str(apksigner), 'verify', '--print-certs', str(apk)])
    _verify_original_signer(certs, policy['signer'])
    info = _json((directory / 'BUILD_INFO.json').read_text(encoding='utf-8'))
    name, code = info['version'].rsplit('+', 1)
    if policy['version'] is not None and info['version'] != policy['version']:
        raise ArtifactError('Original APK version differs from the fixed original-run evidence')
    badging = _command([str(aapt), 'dump', 'badging', str(apk)])
    if not badging.startswith(f"package: name='com.follow.clash.dev' versionCode='{code}' versionName='{name}' "):
        raise ArtifactError('Actual original APK package/version mismatch')
    hashes = {name: _digest(directory / name) for name in (APK_NAME, 'BUILD_INFO.json', 'SHA256SUMS')}
    return dict(schema=1, role='originalApkVerificationRecheck', accepted=True, compiled=False,
                repository=repository, originalBuild=selected,
                testHarness=dict(source=source, tree=_git(root, 'rev-parse', f'{source}^{{tree}}'),
                                 run_id=run_id, run_attempt=run['run_attempt']),
                files=hashes, signer_sha256=policy['signer'])


def _download_receipt(repository: str, run: dict[str, Any]) -> dict[str, Any]:
    artifacts = _api_items(f'repos/{repository}/actions/runs/{run["id"]}/artifacts', 'artifacts')
    matches = [item for item in artifacts if item.get('name') == RECHECK_ARTIFACT]
    if (len(matches) != 1 or matches[0].get('expired') is not False
            or matches[0].get('workflow_run', {}).get('id') != run['id']
            or matches[0].get('workflow_run', {}).get('head_sha') != run['head_sha']):
        raise ArtifactError('Recheck receipt artifact is unavailable or has wrong provenance')
    with tempfile.TemporaryDirectory() as temporary:
        _command(['gh', 'run', 'download', str(run['id']), '--repo', repository,
                  '--name', RECHECK_ARTIFACT, '--dir', temporary])
        _files(Path(temporary), {'recheck.json'})
        return _json((Path(temporary) / 'recheck.json').read_text(encoding='utf-8'))


def recheck_main_inputs(root: Path, harness: str, source: str) -> None:
    _sha(harness, 'test_harness_source')
    _sha(source, 'latest_main_source')
    _git(root, 'merge-base', '--is-ancestor', harness, source)
    paths = _command(['git', 'diff', '--no-renames', '--name-only', '-z', harness, source], root).split('\0')
    for path in filter(None, paths):
        if not (path.startswith('docs/') and path.endswith('.md')):
            raise ArtifactError(f'Main changed an input outside existing regular Markdown documentation: {path}')
        entries = [_git(root, 'ls-tree', revision, '--', path) for revision in (harness, source)]
        if any(not re.fullmatch(r'100644 blob [0-9a-f]{40}\t' + re.escape(path), entry) for entry in entries):
            raise ArtifactError('Main documentation drift must preserve existing regular files')


def find_rechecked_artifacts(*, root: Path, repository: str, source: str, exclude_run: int) -> dict[str, Any]:
    _repository(repository)
    _sha(source, 'source')
    _positive(exclude_run, 'exclude_run')
    runs = _api_items(f'repos/{repository}/actions/workflows/xclash-android.yaml/runs',
                      'workflow_runs', status='success', event='workflow_dispatch')
    for run in sorted(runs, key=lambda item: item['id'], reverse=True):
        harness = _sha(run.get('head_sha'), 'recheck source')
        if run['id'] == exclude_run:
            continue
        _run_identity(repository, run, harness)
        if run.get('status') != 'completed' or run.get('conclusion') != 'success':
            continue
        if harness != source:
            relation = subprocess.run(['git', 'merge-base', '--is-ancestor', harness, source], cwd=root, capture_output=True)
            if relation.returncode:
                continue
            delta = _command(['git', 'diff', '--no-renames', '--name-only', '-z', harness, source], root).split('\0')
            if any(path and not (path.startswith('docs/') and path.endswith('.md')) for path in delta):
                continue
            recheck_main_inputs(root, harness, source)
        artifacts = _api_items(f'repos/{repository}/actions/runs/{run["id"]}/artifacts', 'artifacts')
        if not any(item.get('name') == RECHECK_ARTIFACT for item in artifacts):
            continue
        receipt = _download_receipt(repository, run)
        if not isinstance(receipt.get('originalBuild'), dict):
            raise ArtifactError('Recheck receipt has no original build identity')
        build_run = receipt['originalBuild'].get('run_id')
        policy = _recheck_policy(build_run, repository)
        _recheck_jobs(repository, run, final=True, build_run=build_run)
        expected = recheck_plan(root=root, repository=repository, source=harness, build_run=build_run)
        hashes = {name: digest for name, (_, digest) in policy['payloads'].items()
                  if name in (APK_NAME, 'BUILD_INFO.json', 'SHA256SUMS')}
        if (receipt.get('schema') != 1 or receipt.get('role') != 'originalApkVerificationRecheck'
                or receipt.get('accepted') is not True or receipt.get('compiled') is not False
                or receipt.get('repository') != repository or receipt.get('originalBuild') != expected
                or receipt.get('testHarness') != dict(source=harness, tree=_git(root, 'rev-parse', f'{harness}^{{tree}}'),
                                                     run_id=run['id'], run_attempt=run['run_attempt'])
                or receipt.get('signer_sha256') != policy['signer'] or receipt.get('files') != hashes):
            raise ArtifactError('Recheck receipt identity/input map mismatch')
        return {**expected, 'recheck_run': run['id'], 'receipt': receipt}
    for build_run in (RECHECK_RUN, RECHECK_RUN_74):
        try:
            recheck_inputs(root, source, build_run)
        except ArtifactError:
            policy = _recheck_policy(build_run, repository)
            try:
                paths = _command(['git', 'diff', '--no-renames', '--name-only', '-z', policy['source'], source], root).split('\0')
            except ArtifactError:
                continue
            if any(path and path not in RECHECK_WIRING | {policy['test']}
                   and not (path.startswith('docs/') and path.endswith('.md')) for path in paths):
                continue
        raise ArtifactError('The reviewed test-only source has no accepted original-APK recheck; rebuilding is forbidden')
    return {}


def accepted_build(*, root: Path, repository: str, source: str, build_run: int,
                   release_run: int) -> dict[str, Any]:
    _repository(repository)
    _sha(source, 'release_source')
    _positive(build_run, 'build_run')
    _positive(release_run, 'release_run')
    if build_run == release_run:
        raise ArtifactError('Promotion requires an independently accepted original run')
    if build_run in (RECHECK_RUN, RECHECK_RUN_74):
        _recheck_policy(build_run, repository)
        return accepted_rechecked_build(root=root, repository=repository, source=source,
            build_run=build_run, release_run=release_run)
    run = _api_object(f'repos/{repository}/actions/runs/{build_run}')
    original = _sha(run.get('head_sha'), 'build_source')
    _run_identity(repository, run, original)
    if run['id'] != build_run or run.get('status') != 'completed' or run.get('conclusion') != 'success':
        raise ArtifactError('Explicit original run is not fully accepted')
    projection = product_source.equivalent(root=root, product='aerove',
        build_source=original, release_source=source)
    if __package__:
        from . import ci_verify
    else:
        import ci_verify
    required = {'reuse', 'prep', 'static', 'native', 'verify', 'apk'}
    for group, rows in ci_verify.matrices().items():
        required.update(f'{group} ({row["suite"]} {row["shard"]})' for row in rows)
    jobs = _api_items(f'repos/{repository}/actions/runs/{build_run}/attempts/{run["run_attempt"]}/jobs', 'jobs')
    producers = {}
    for name in required:
        matches = [job for job in jobs if job.get('name') == name]
        if (len(matches) != 1 or matches[0].get('run_id') != build_run
                or matches[0].get('head_sha') != original
                or matches[0].get('run_attempt', run['run_attempt']) != run['run_attempt']
                or matches[0].get('status') != 'completed' or matches[0].get('conclusion') != 'success'):
            raise ArtifactError(f'Original run requires one successful exact-attempt {name} job')
        producers[name] = matches[0]
    rows = _api_items(f'repos/{repository}/actions/runs/{build_run}/artifacts', 'artifacts')
    artifacts = {}
    for name, key in [('Aerove-android-arm64', 'artifact_id'), ('xclash-generated', 'generated_artifact_id')]:
        matches = [item for item in rows if item.get('name') == name]
        if len(matches) != 1:
            raise ArtifactError(f'Original artifact missing/duplicate: {name}')
        item = matches[0]
        if (item.get('expired') is not False or item.get('workflow_run', {}).get('id') != build_run
                or item.get('workflow_run', {}).get('head_sha') != original
                or type(item.get('size_in_bytes')) is not int or item['size_in_bytes'] < 1
                or not re.fullmatch(r'sha256:[0-9a-f]{64}', str(item.get('digest', '')))):
            raise ArtifactError('Original artifact provenance/size/digest mismatch')
        _positive(item.get('id'), 'artifact_id')
        job = producers['apk' if name == 'Aerove-android-arm64' else 'prep']
        _positive(job.get('id'), 'producer job id')
        try:
            stamps = [datetime.fromisoformat(value.replace('Z', '+00:00'))
                      for value in (job['started_at'], item['created_at'], job['completed_at'])]
        except (KeyError, TypeError, AttributeError, ValueError) as error:
            raise ArtifactError('Original artifact has no exact-attempt producer time binding') from error
        if (any(stamp.tzinfo is None or stamp.utcoffset() != timezone.utc.utcoffset(stamp) for stamp in stamps)
                or not stamps[0] <= stamps[1] <= stamps[2]):
            raise ArtifactError('Original artifact was not created by the successful selected-attempt producer')
        artifacts[key] = {field: item[field] for field in ('id', 'name', 'size_in_bytes', 'digest', 'created_at')}
        artifacts[key].update(producer_job_id=job['id'], producer_attempt=run['run_attempt'])
    return dict(run_id=build_run, run_number=run['run_number'], build_attempt=run['run_attempt'],
        build_source=original, build_tree=projection['build_tree'], projection=projection, artifacts=artifacts,
        **{key: item['id'] for key, item in artifacts.items()})


def accepted_rechecked_build(*, root, repository, source, build_run, release_run):
    policy = _recheck_policy(build_run, repository)
    original = original_recheck_build(repository, build_run)
    runs = _api_items(f'repos/{repository}/actions/workflows/xclash-android.yaml/runs',
        'workflow_runs', status='success', event='workflow_dispatch')
    for run in sorted(runs, key=lambda item: item['id'], reverse=True):
        harness = _sha(run.get('head_sha'), 'recheck source')
        if run['id'] == release_run or run.get('status') != 'completed' or run.get('conclusion') != 'success':
            continue
        _run_identity(repository, run, harness)
        artifacts = _api_items(f'repos/{repository}/actions/runs/{run["id"]}/artifacts', 'artifacts')
        if not any(item.get('name') == RECHECK_ARTIFACT for item in artifacts):
            continue
        try:
            projection = product_source.equivalent(root=root, product='aerove',
                build_source=harness, release_source=source)
        except product_source.ProjectionError:
            continue
        _recheck_jobs(repository, run, final=True, build_run=build_run)
        receipt = _download_receipt(repository, run)
        expected = recheck_plan(root=root, repository=repository, source=harness, build_run=build_run)
        hashes = {name: digest for name, (_, digest) in policy['payloads'].items()
                  if name in (APK_NAME, 'BUILD_INFO.json', 'SHA256SUMS')}
        identity = dict(source=harness, tree=_git(root, 'rev-parse', f'{harness}^{{tree}}'),
                        run_id=run['id'], run_attempt=run['run_attempt'])
        if (receipt.get('schema') != 1 or receipt.get('role') != 'originalApkVerificationRecheck'
                or receipt.get('accepted') is not True or receipt.get('compiled') is not False
                or receipt.get('repository') != repository or receipt.get('originalBuild') != expected
                or receipt.get('testHarness') != identity or receipt.get('signer_sha256') != policy['signer']
                or receipt.get('files') != hashes):
            raise ArtifactError('Explicit original promotion requires its unchanged accepted hard recheck receipt')
        pinned = {}
        for name, key in [('Aerove-android-arm64', 'artifact_id'), ('xclash-generated', 'generated_artifact_id')]:
            artifact_id, size, digest = policy['artifacts'][name]
            pinned[key] = dict(id=artifact_id, name=name, size_in_bytes=size, digest=digest)
        return dict(**original, build_tree=expected['build_tree'], projection=projection, artifacts=pinned,
                    recheck_run=run['id'], test_harness_source=harness, acceptance=identity)
    raise ArtifactError('Explicit original has no accepted hard recheck for unchanged current product inputs')


def promotion_receipt(*, root: Path, repository: str, source: str, build_run: int,
                      release_run: int, release_attempt: int, directory: Path,
                      generated_directory: Path) -> dict[str, Any]:
    selected = accepted_build(root=root, repository=repository, source=source,
        build_run=build_run, release_run=release_run)
    current = _api_object(f'repos/{repository}/actions/runs/{release_run}')
    _run_identity(repository, current, source)
    if (current['id'] != release_run or current['run_attempt'] != release_attempt
            or current.get('event') != 'workflow_dispatch' or current.get('head_branch') != 'main'):
        raise ArtifactError('Promotion must bind the current explicit main release run/attempt')
    with tempfile.TemporaryDirectory() as temporary:
        fixed = Path(temporary)
        for name in ('pubspec.yaml', 'tool/upstream-version.json'):
            (fixed / Path(name).name).write_bytes(_git_bytes(root, 'show', f'{selected["build_source"]}:apps/aerove/{name}'))
        validate_artifacts(directory=directory, generated_directory=generated_directory,
            source=selected['build_source'], tree=selected['build_tree'], repository=repository,
            run_id=build_run, run_number=selected['run_number'],
            metadata=fixed / 'upstream-version.json', pubspec=fixed / 'pubspec.yaml')
        files = {}
        for key, payload in [('artifact_id', directory), ('generated_artifact_id', generated_directory)]:
            artifact = selected['artifacts'][key]
            archive = fixed / (key + '.zip')
            with archive.open('wb') as stream:
                subprocess.run(['gh', 'api', f'repos/{repository}/actions/artifacts/{artifact["id"]}/zip'],
                    check=True, stdout=stream)
            files[key] = product_source.bind_archive(archive, payload, artifact)
    return dict(schema=1, role='originalProductArtifactPromotion', product='aerove', repository=repository,
        build_source=selected['build_source'], build_tree=selected['build_tree'],
        build_run=build_run, build_attempt=selected['build_attempt'], build_number=selected['run_number'],
        release_source=source, release_tree=selected['projection']['release_tree'],
        release_run=release_run, release_attempt=release_attempt, compiled=False,
        projection=selected['projection'], artifacts=selected['artifacts'], files=files,
        acceptance=selected.get('acceptance'))


def _git_bytes(root, *arguments):
    return subprocess.run(['git', *arguments], cwd=root, check=True, capture_output=True).stdout


def check_promotion_receipt(*, root, repository, source, build_run, release_run,
                            release_attempt, directory, generated_directory, receipt):
    selected = accepted_build(root=root, repository=repository, source=source,
        build_run=build_run, release_run=release_run)
    current = _api_object(f'repos/{repository}/actions/runs/{release_run}')
    _run_identity(repository, current, source)
    if (current['id'] != release_run or current['run_attempt'] != release_attempt
            or current.get('event') != 'workflow_dispatch' or current.get('head_branch') != 'main'):
        raise ArtifactError('Promotion receipt current release run/attempt mismatch')
    value = _json(receipt.read_text(encoding='utf-8'))
    expected = dict(schema=1, role='originalProductArtifactPromotion', product='aerove', repository=repository,
        build_source=selected['build_source'], build_tree=selected['build_tree'],
        build_run=build_run, build_attempt=selected['build_attempt'], build_number=selected['run_number'],
        release_source=source, release_tree=selected['projection']['release_tree'],
        release_run=release_run, release_attempt=release_attempt, compiled=False,
        projection=selected['projection'], artifacts=selected['artifacts'], acceptance=selected.get('acceptance'))
    if any(value.get(key) != item for key, item in expected.items()):
        raise ArtifactError('Promotion receipt build/release/API/input identity mismatch')
    files = {}
    for key, target, names in (
            ('artifact_id', directory, (APK_NAME, 'BUILD_INFO.json', 'SHA256SUMS')),
            ('generated_artifact_id', generated_directory, ('xclash-generated.patch', 'xclash-verified-tree.txt'))):
        _files(target, set(names))
        files[key] = {name: dict(bytes=(target / name).stat().st_size, sha256=_digest(target / name)) for name in names}
    if value.get('files') != files:
        raise ArtifactError('Original payload bytes changed after the promotion receipt was saved')


def _tag_source(repository, tag, draft):
    response = subprocess.run(['gh', 'api', '--method', 'GET', f'repos/{repository}/git/ref/tags/{tag}'],
        check=False, capture_output=True, text=True)
    value = _json(response.stdout)
    if response.returncode:
        if draft and isinstance(value, dict) and str(value.get('status')) == '404':
            return None
        raise ArtifactError('Could not read the exact release tag identity')
    reference = value.get('object', {})
    for _ in range(10):
        sha = _sha(reference.get('sha'), 'release tag SHA')
        if reference.get('type') == 'commit':
            return sha
        if reference.get('type') != 'tag':
            break
        reference = _api_object(f'repos/{repository}/git/tags/{sha}').get('object', {})
    raise ArtifactError('Release tag does not resolve to one commit')


def _release_for_tag(repository, source, tag):
    release = _api_object(f'repos/{repository}/releases/tags/{tag}', allow_not_found=True)
    if release is not None:
        return release
    pages = _json(_command(['gh', 'api', '--paginate', '--slurp',
                           f'repos/{repository}/releases?per_page=100',
                           '-H', 'Accept: application/vnd.github+json']))
    if not isinstance(pages, list) or not pages or any(not isinstance(page, list) for page in pages):
        raise ArtifactError('Invalid paginated release list')
    matches = []
    identities = set()
    for page in pages:
        for item in page:
            if not isinstance(item, dict):
                raise ArtifactError('Invalid release list entry')
            identity = _positive(item.get('id'), 'release ID')
            if identity in identities:
                raise ArtifactError('Duplicate release ID across pages')
            identities.add(identity)
            if item.get('tag_name') == tag:
                matches.append(item)
    if len(matches) != 1:
        raise ArtifactError('Expected one exact draft release for the requested tag')
    discovered = matches[0]
    endpoint = f'repos/{repository}/releases/{discovered["id"]}'
    if (discovered.get('draft') is not True or discovered.get('target_commitish') != source
            or discovered.get('url') != f'https://api.github.com/{endpoint}'):
        raise ArtifactError('Discovered draft repository/tag/target identity mismatch')
    release = _api_object(endpoint)
    fields = ('id', 'url', 'tag_name', 'target_commitish', 'draft')
    if any(release.get(field) != discovered.get(field) for field in fields):
        raise ArtifactError('Draft release identity changed during discovery')
    return release


def verify_release_assets(*, repository, source, tag, directory, allow_missing=False):
    _repository(repository)
    _sha(source, 'release_source')
    _files(directory, {APK_NAME, 'BUILD_INFO.json', 'SHA256SUMS'})
    release = _release_for_tag(repository, source, tag)
    draft = release.get('draft')
    if (type(draft) is not bool or release.get('tag_name') != tag
            or release.get('target_commitish') != source):
        raise ArtifactError('Existing release tag/target does not match the separate release source')
    tag_source = _tag_source(repository, tag, draft)
    if tag_source != source and not (draft and tag_source is None):
        raise ArtifactError('Existing release tag resolves to another commit')
    info = _json((directory / 'BUILD_INFO.json').read_text(encoding='utf-8'))
    if not draft and release.get('prerelease') != (info.get('appEnv') == 'pre'):
        raise ArtifactError('Published release has the wrong product channel')
    rows = release.get('assets')
    if not isinstance(rows, list):
        raise ArtifactError('Invalid existing release asset list')
    names = [item.get('name') for item in rows]
    expected = {APK_NAME, 'BUILD_INFO.json', 'SHA256SUMS'}
    if len(names) != len(set(names)) or set(names) - expected:
        raise ArtifactError('Existing release has duplicate or unexpected assets')
    missing = expected - set(names)
    if missing and not (allow_missing and draft):
        raise ArtifactError('Published/uploaded release does not contain all three original assets')
    with tempfile.TemporaryDirectory() as temporary:
        for item in rows:
            identity = _positive(item.get('id'), 'release asset ID')
            original = directory / item['name']
            if item.get('size') != original.stat().st_size:
                raise ArtifactError('Existing release asset size differs from the original build')
            target = Path(temporary) / item['name']
            with target.open('wb') as stream:
                subprocess.run(['gh', 'api', f'repos/{repository}/releases/assets/{identity}',
                    '-H', 'Accept: application/octet-stream'], check=True, stdout=stream)
            if target.stat().st_size != original.stat().st_size or _digest(target) != _digest(original):
                raise ArtifactError('Existing release asset bytes differ from the original build')
    return sorted(missing)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    find = commands.add_parser('find')
    find.add_argument('--repository', required=True)
    find.add_argument('--source', required=True)
    find.add_argument('--exclude-run', type=int, required=True)
    find.add_argument('--output', type=Path, default=os.environ.get('GITHUB_OUTPUT'))
    validate = commands.add_parser('validate')
    validate.add_argument('--directory', type=Path, required=True)
    validate.add_argument('--generated-directory', type=Path, required=True)
    validate.add_argument('--source', required=True)
    validate.add_argument('--tree', required=True)
    validate.add_argument('--repository', required=True)
    validate.add_argument('--run-id', type=int, required=True)
    validate.add_argument('--run-number', type=int, required=True)
    validate.add_argument('--metadata', type=Path, required=True)
    validate.add_argument('--pubspec', type=Path)
    validate.add_argument('--promotion-root', type=Path)
    validate.add_argument('--promotion-source')
    validate.add_argument('--recheck-run', type=int)
    for name in ('recheck-plan', 'recheck-receipt', 'find-rechecked'):
        command = commands.add_parser(name)
        command.add_argument('--root', type=Path, required=True)
        command.add_argument('--repository', required=True)
        command.add_argument('--source', required=True)
        command.add_argument('--output', type=Path, required=True)
        if name == 'find-rechecked':
            command.add_argument('--exclude-run', type=int, required=True)
        else:
            command.add_argument('--build-run', type=int, required=True)
        if name == 'recheck-receipt':
            command.add_argument('--run-id', type=int, required=True)
            command.add_argument('--directory', type=Path, required=True)
            command.add_argument('--generated-directory', type=Path, required=True)
            command.add_argument('--apksigner', type=Path, required=True)
            command.add_argument('--aapt', type=Path, required=True)
    for name in ('select-build', 'promotion-receipt'):
        command = commands.add_parser(name)
        command.add_argument('--root', type=Path, required=True)
        command.add_argument('--repository', required=True)
        command.add_argument('--source', required=True)
        command.add_argument('--build-run', type=int, required=True)
        command.add_argument('--release-run', type=int, required=True)
        command.add_argument('--output', type=Path, required=True)
        if name == 'promotion-receipt':
            command.add_argument('--release-attempt', type=int, required=True)
            command.add_argument('--directory', type=Path, required=True)
            command.add_argument('--generated-directory', type=Path, required=True)
    source_check = commands.add_parser('source-check')
    source_check.add_argument('--root', type=Path, required=True)
    source_check.add_argument('--build-source', required=True)
    source_check.add_argument('--source', required=True)
    check = commands.add_parser('check-promotion')
    for name in ('root', 'directory', 'generated-directory', 'receipt'):
        check.add_argument('--' + name, type=Path, required=True)
    for name in ('repository', 'source'):
        check.add_argument('--' + name, required=True)
    for name in ('build-run', 'release-run', 'release-attempt'):
        check.add_argument('--' + name, type=int, required=True)
    assets = commands.add_parser('release-assets')
    for name in ('repository', 'source', 'tag'):
        assets.add_argument('--' + name, required=True)
    assets.add_argument('--directory', type=Path, required=True)
    assets.add_argument('--output', type=Path, required=True)
    assets.add_argument('--allow-missing', action='store_true')
    drift = commands.add_parser('recheck-main')
    drift.add_argument('--root', type=Path, required=True)
    drift.add_argument('--harness', required=True)
    drift.add_argument('--source', required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == 'release-assets':
            missing = verify_release_assets(repository=args.repository, source=args.source,
                tag=args.tag, directory=args.directory, allow_missing=args.allow_missing)
            args.output.write_text(''.join(name + '\n' for name in missing), encoding='utf-8')
        elif args.command == 'check-promotion':
            check_promotion_receipt(root=args.root, repository=args.repository, source=args.source,
                build_run=args.build_run, release_run=args.release_run, release_attempt=args.release_attempt,
                directory=args.directory, generated_directory=args.generated_directory, receipt=args.receipt)
        elif args.command == 'source-check':
            print(json.dumps(product_source.equivalent(root=args.root, product='aerove',
                build_source=args.build_source, release_source=args.source), sort_keys=True))
        elif args.command in ('select-build', 'promotion-receipt'):
            parameters = dict(root=args.root, repository=args.repository, source=args.source,
                build_run=args.build_run, release_run=args.release_run)
            if args.command == 'promotion-receipt':
                result = promotion_receipt(**parameters, release_attempt=args.release_attempt,
                    directory=args.directory, generated_directory=args.generated_directory)
                args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
            else:
                result = accepted_build(**parameters)
                with args.output.open('a', encoding='utf-8') as output:
                    output.write(''.join(f'{key}={result.get(key, "")}\n' for key in
                        (*OUTPUTS, 'build_source', 'build_tree', 'build_attempt', 'recheck_run', 'test_harness_source')))
            print(json.dumps(result, sort_keys=True))
        elif args.command == 'recheck-main':
            recheck_main_inputs(args.root, args.harness, args.source)
            print(f'Validated latest main {args.source} against recheck harness {args.harness}')
        elif args.command in ('recheck-plan', 'recheck-receipt', 'find-rechecked'):
            parameters = dict(root=args.root, repository=args.repository, source=args.source)
            if args.command == 'recheck-receipt':
                result = recheck_receipt(**parameters, build_run=args.build_run, run_id=args.run_id,
                                         directory=args.directory, generated_directory=args.generated_directory,
                                         apksigner=args.apksigner, aapt=args.aapt)
                args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
            else:
                result = (recheck_plan(**parameters, build_run=args.build_run) if args.command == 'recheck-plan'
                          else find_rechecked_artifacts(**parameters, exclude_run=args.exclude_run))
                with args.output.open('a', encoding='utf-8') as output:
                    keys = (*OUTPUTS, 'build_source', 'build_tree', 'test_harness_source', 'recheck_run')
                    output.write(''.join(f'{key}={result.get(key, "")}\n' for key in keys))
                if result.get('receipt') is not None:
                    args.output.with_suffix('.receipt.json').write_text(json.dumps(result['receipt']), encoding='utf-8')
            print(json.dumps(result, sort_keys=True))
        elif args.command == 'find':
            if args.output is None:
                raise ArtifactError('--output or GITHUB_OUTPUT is required')
            selected = find_artifacts(
                repository=args.repository, source=args.source, exclude_run=args.exclude_run,
            )
            with args.output.open('a', encoding='utf-8') as output:
                output.write(''.join(f'{key}={selected[key]}\n' for key in OUTPUTS))
            print(json.dumps(selected, sort_keys=True))
        else:
            if any(value is not None for value in (args.promotion_root, args.promotion_source, args.recheck_run)):
                if any(value is None for value in (args.promotion_root, args.promotion_source, args.recheck_run)):
                    raise ArtifactError('Promotion requires root, source and recheck run together')
                selected = find_rechecked_artifacts(root=args.promotion_root, repository=args.repository,
                                                     source=args.promotion_source, exclude_run=int(os.environ['GITHUB_RUN_ID']))
                if (selected.get('recheck_run') != args.recheck_run or selected.get('run_id') != args.run_id
                        or selected.get('run_number') != args.run_number or selected.get('build_source') != args.source
                        or selected.get('build_tree') != args.tree):
                    raise ArtifactError('Promotion does not match the accepted original build receipt')
                _original_payloads(args.directory, args.generated_directory, args.run_id)
                hashes = {name: _digest(args.directory / name) for name in (APK_NAME, 'BUILD_INFO.json', 'SHA256SUMS')}
                if selected['receipt']['files'] != hashes:
                    raise ArtifactError('Downloaded original APK files differ from accepted recheck receipt')
            result = validate_artifacts(
                directory=args.directory, generated_directory=args.generated_directory,
                source=args.source, tree=args.tree, repository=args.repository,
                run_id=args.run_id, run_number=args.run_number, metadata=args.metadata,
                pubspec=args.pubspec,
            )
            print(f'Validated {result} from original run {args.run_id}')
    except (ArtifactError, product_source.ProjectionError, OSError, UnicodeError, subprocess.CalledProcessError) as error:
        print(f'aerove_artifact: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
