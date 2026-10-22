#!/usr/bin/env python3
"""Find and validate an unchanged Aerove Android build for promotion."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import struct
import subprocess
import sys
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any
from urllib.parse import urlencode

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
    args = parser.parse_args(argv)
    try:
        if args.command == 'find':
            if args.output is None:
                raise ArtifactError('--output or GITHUB_OUTPUT is required')
            selected = find_artifacts(
                repository=args.repository, source=args.source, exclude_run=args.exclude_run,
            )
            with args.output.open('a', encoding='utf-8') as output:
                output.write(''.join(f'{key}={selected[key]}\n' for key in OUTPUTS))
            print(json.dumps(selected, sort_keys=True))
        else:
            result = validate_artifacts(
                directory=args.directory, generated_directory=args.generated_directory,
                source=args.source, tree=args.tree, repository=args.repository,
                run_id=args.run_id, run_number=args.run_number, metadata=args.metadata,
                pubspec=args.pubspec,
            )
            print(f'Validated {result} from original run {args.run_id}')
    except (ArtifactError, OSError, UnicodeError) as error:
        print(f'aerove_artifact: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
