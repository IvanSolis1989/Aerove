#!/usr/bin/env python3
"""Resolve Aerove versions independently while verifying committed source lineage."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from typing import Any

if __package__:
    from . import xclash_version as upstream
else:
    import xclash_version as upstream


VersionError = upstream.VersionError
VERSION_CODE_BASE = 2026102200
_VERSION_KEY = re.compile(r'^version[ \t]*:', re.MULTILINE)
_VERSION_LINE = re.compile(
    r'''^version[ \t]*:[ \t]*(?:'(?P<single>[^'\r\n]+)'|"(?P<double>[^"\r\n]+)"|'''
    r'''(?P<plain>[^# \t\r\n'"\x00]+))[ \t]*(?:\#[^\r\n]*)?\r?$''',
    re.MULTILINE,
)
_SEMVER = re.compile(
    r'(?P<name>(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)'
    r'(?:-(?P<pre>[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?)'
    r'(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?',
)


def resolve_product_version(pubspec_text: str, run_number: int) -> dict[str, Any]:
    """Return the product name/channel and the original build run's Android code."""
    if not isinstance(run_number, int) or isinstance(run_number, bool) or run_number < 1:
        raise VersionError('GitHub run number must be a positive integer')
    number = VERSION_CODE_BASE + run_number
    if number > upstream.ANDROID_VERSION_CODE_LIMIT:
        raise VersionError('Android version code exceeds the platform limit')
    if not isinstance(pubspec_text, str) or len(_VERSION_KEY.findall(pubspec_text)) != 1:
        raise VersionError('Expected exactly one top-level pubspec version')
    line = _VERSION_LINE.search(pubspec_text)
    if line is None:
        raise VersionError('Invalid single-line pubspec version')
    value = next(item for item in line.groups() if item is not None)
    match = _SEMVER.fullmatch(value)
    if match is None:
        raise VersionError('Product version must be strict SemVer with optional build metadata')
    prerelease = match['pre']
    if prerelease is not None and any(
        identifier.isdecimal() and len(identifier) > 1 and identifier.startswith('0')
        for identifier in prerelease.split('.')
    ):
        raise VersionError('Numeric prerelease identifiers must not have leading zeroes')
    name = match['name']
    return {
        'name': name,
        'number': number,
        'tag': f'v{name}+{number}',
        'app_env': 'pre' if prerelease is not None else 'stable',
    }


def _tree_path(repo: Path, path: Path) -> str:
    candidate = Path(os.path.abspath(repo / path))
    try:
        relative = candidate.relative_to(repo)
    except ValueError as error:
        raise VersionError('Release input paths must stay inside the repository') from error
    if relative == Path('.'):
        raise VersionError('Release inputs must identify files')
    return relative.as_posix()


def _metadata(contents: str) -> dict[str, Any]:
    def pairs(items: list[tuple[str, Any]]) -> dict[str, Any]:
        result = {}
        for key, value in items:
            if key in result:
                raise VersionError(f'duplicate metadata field: {key}')
            result[key] = value
        return result

    def constant(value: str) -> None:
        raise VersionError(f'Invalid JSON constant: {value}')

    try:
        evidence = json.loads(contents, object_pairs_hook=pairs, parse_constant=constant)
    except json.JSONDecodeError as error:
        raise VersionError(f'Invalid upstream metadata JSON: {error}') from error
    if not isinstance(evidence, dict) or type(evidence.get('schema_version')) is not int:
        raise VersionError('Invalid upstream metadata schema')
    return upstream.validate_metadata_shape(evidence)


def build_release_plan(
    *, repo: Path, head: str, run_number: int, metadata: Path, pubspec: Path,
) -> dict[str, Any]:
    """Read both inputs at head; the checkout and moving upstream refs are not authority."""
    input_root = repo.resolve()
    try:
        repo = Path(subprocess.check_output(
            ['git', 'rev-parse', '--show-toplevel'], cwd=input_root,
            text=True, encoding='utf-8', stderr=subprocess.PIPE,
        ).strip()).resolve()
    except subprocess.CalledProcessError as error:
        raise VersionError('The release input root must belong to a Git working tree') from error
    upstream._require_sha1('head', head)
    upstream._git_ref_commit(repo, head)
    metadata_path = _tree_path(repo, input_root / metadata)
    pubspec_path = _tree_path(repo, input_root / pubspec)
    evidence = _metadata(upstream._git_show(repo, f'{head}:{metadata_path}'))
    if not upstream.git_is_ancestor(repo, evidence['source_commit'], head):
        raise VersionError('Recorded upstream source commit is not contained in head')
    if not upstream.git_is_ancestor(repo, evidence['tag_commit'], evidence['source_commit']):
        raise VersionError('Recorded upstream tag is not an ancestor of its source')
    historical_pubspec = upstream.parse_pubspec_version(
        upstream._git_show(repo, f'{evidence["pubspec_source_commit"]}:pubspec.yaml'),
    )
    if historical_pubspec != evidence['pubspec_version']:
        raise VersionError('Recorded upstream pubspec version does not match its source commit')
    product = resolve_product_version(
        upstream._git_show(repo, f'{head}:{pubspec_path}'), run_number,
    )
    return {
        **product,
        'upstream_repository': evidence['repository'],
        'upstream_source_commit': evidence['source_commit'],
        'upstream_tag': evidence['tag'],
        'upstream_tag_commit': evidence['tag_commit'],
        'upstream_source_branches': evidence['source_branches'],
        'upstream_pubspec_version': evidence['pubspec_version'],
        'upstream_pubspec_source_commit': evidence['pubspec_source_commit'],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    release = commands.add_parser('release-plan')
    release.add_argument('--metadata', type=Path, required=True)
    release.add_argument('--pubspec', type=Path, required=True)
    release.add_argument('--repo', type=Path, default=Path.cwd())
    release.add_argument('--head', required=True)
    release.add_argument('--run-number', type=int, required=True)
    args = parser.parse_args(argv)
    try:
        plan = build_release_plan(
            repo=args.repo, head=args.head, run_number=args.run_number,
            metadata=args.metadata, pubspec=args.pubspec,
        )
    except (OSError, UnicodeError, VersionError) as error:
        print(f'aerove_version: {error}', file=sys.stderr)
        return 1
    print(json.dumps(plan, sort_keys=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
