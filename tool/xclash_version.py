#!/usr/bin/env python3
"""Resolve and validate XClash's upstream-derived Android version."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


UPSTREAM_REPOSITORY = "chen08209/FlClash"
UPSTREAM_BRANCH_NAMESPACE = "refs/remotes/upstream/"
UPSTREAM_TAG_NAMESPACE = "refs/upstream-tags/"
SCHEMA_VERSION = 1
FORK_VERSION_CODE_BASE = 2026101700
ANDROID_VERSION_CODE_LIMIT = 2_100_000_000
SHA1_RE = re.compile(r"^[0-9a-f]{40}$")
TAG_RE = re.compile(
    r"^v(?P<version>(?P<major>0|[1-9][0-9]*)\.(?P<minor>0|[1-9][0-9]*)\."
    r"(?P<patch>0|[1-9][0-9]*)(?:-(?P<prerelease>[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?)$"
)
PUBSPEC_VERSION_RE = re.compile(
    r"(?m)^version:\s*['\"]?([^\s'\"#]+)['\"]?\s*(?:#.*)?$"
)
METADATA_KEYS = {
    "schema_version",
    "repository",
    "source_commit",
    "tag",
    "tag_commit",
    "source_branches",
    "version",
    "pubspec_version",
    "pubspec_source_commit",
}


class VersionError(ValueError):
    """Raised when upstream version evidence is absent or inconsistent."""


@dataclass(frozen=True)
class ReleaseTag:
    name: str
    version: str
    precedence: tuple[Any, ...]


def parse_release_tag(tag: str) -> ReleaseTag | None:
    match = TAG_RE.fullmatch(tag)
    if match is None:
        return None
    prerelease = match.group("prerelease")
    prerelease_key: tuple[tuple[int, Any], ...] = ()
    if prerelease is not None:
        identifiers = prerelease.split(".")
        if any(
            identifier.isdecimal() and len(identifier) > 1 and identifier.startswith("0")
            for identifier in identifiers
        ):
            return None
        prerelease_key = tuple(
            (0, int(identifier)) if identifier.isdecimal() else (1, identifier)
            for identifier in identifiers
        )
    return ReleaseTag(
        name=tag,
        version=match.group("version"),
        precedence=(
            int(match.group("major")),
            int(match.group("minor")),
            int(match.group("patch")),
            1 if prerelease is None else 0,
            prerelease_key,
        ),
    )


def select_upstream_tag(refs: Iterable[str]) -> str:
    candidates: list[tuple[ReleaseTag, str]] = []
    for ref in refs:
        if not ref.startswith(UPSTREAM_TAG_NAMESPACE):
            continue
        tag = ref.removeprefix(UPSTREAM_TAG_NAMESPACE)
        parsed = parse_release_tag(tag)
        if parsed is not None:
            candidates.append((parsed, ref))
    if not candidates:
        raise VersionError(
            f"No reachable legal release tag under {UPSTREAM_TAG_NAMESPACE}"
        )
    return max(candidates, key=lambda candidate: candidate[0].precedence)[1]


def _git_ref_commit(repo: Path, ref: str) -> str:
    result = subprocess.run(
        ["git", "rev-parse", "--verify", f"{ref}^{{commit}}"],
        cwd=repo,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise VersionError(f"Could not resolve ref to a commit: {ref}")
    return _require_sha1("peeled ref commit", result.stdout.strip())


def select_reachable_release(repo: Path) -> dict[str, Any]:
    branch_refs = subprocess.run(
        ["git", "for-each-ref", "--format=%(refname)", UPSTREAM_BRANCH_NAMESPACE],
        cwd=repo,
        capture_output=True,
        text=True,
        check=False,
    )
    tag_refs = subprocess.run(
        ["git", "for-each-ref", "--format=%(refname)", UPSTREAM_TAG_NAMESPACE],
        cwd=repo,
        capture_output=True,
        text=True,
        check=False,
    )
    if branch_refs.returncode != 0 or tag_refs.returncode != 0:
        raise VersionError("Could not list fetched upstream branches and tags")
    branches = {
        ref: _git_ref_commit(repo, ref)
        for ref in branch_refs.stdout.splitlines()
        if ref.startswith(UPSTREAM_BRANCH_NAMESPACE) and not ref.endswith("/HEAD")
    }
    candidates: list[tuple[ReleaseTag, dict[str, Any]]] = []
    for tag_ref in tag_refs.stdout.splitlines():
        if not tag_ref.startswith(UPSTREAM_TAG_NAMESPACE):
            continue
        tag = tag_ref.removeprefix(UPSTREAM_TAG_NAMESPACE)
        parsed = parse_release_tag(tag)
        if parsed is None:
            continue
        tag_commit = resolve_tag_commit(repo, tag_ref)
        reachable_branches = sorted(
            ref.removeprefix(UPSTREAM_BRANCH_NAMESPACE)
            for ref, branch_commit in branches.items()
            if git_is_ancestor(repo, tag_commit, branch_commit)
        )
        if reachable_branches:
            candidates.append(
                (
                    parsed,
                    {
                        "tag_ref": tag_ref,
                        "tag": tag,
                        "tag_commit": tag_commit,
                        "source_commit": tag_commit,
                        "version": parsed.version,
                        "source_branches": reachable_branches,
                    },
                )
            )
    if not candidates:
        raise VersionError(
            "No legal upstream release tag is reachable from a fetched upstream branch"
        )
    return max(candidates, key=lambda candidate: candidate[0].precedence)[1]


def parse_pubspec_version(contents: str) -> str:
    match = PUBSPEC_VERSION_RE.search(contents)
    if match is None:
        raise VersionError("Could not read a single-line version from pubspec.yaml")
    value = match.group(1)
    if "+" not in value:
        raise VersionError("pubspec version must include its upstream build number")
    name, build = value.rsplit("+", maxsplit=1)
    if not name or not build.isdecimal():
        raise VersionError(f"Invalid pubspec version: {value}")
    return value


def _require_sha1(name: str, value: Any) -> str:
    if not isinstance(value, str) or SHA1_RE.fullmatch(value) is None:
        raise VersionError(f"{name} must be a 40-character lowercase commit SHA")
    return value


def validate_metadata_shape(metadata: Any) -> dict[str, Any]:
    if not isinstance(metadata, dict) or set(metadata) != METADATA_KEYS:
        raise VersionError("Upstream version metadata has missing or unexpected fields")
    if metadata["schema_version"] != SCHEMA_VERSION:
        raise VersionError("Unsupported upstream version metadata schema")
    if metadata["repository"] != UPSTREAM_REPOSITORY:
        raise VersionError("Upstream version metadata points at an untrusted source")
    for field in ("source_commit", "tag_commit", "pubspec_source_commit"):
        _require_sha1(field, metadata[field])
    if metadata["source_commit"] != metadata["pubspec_source_commit"]:
        raise VersionError("pubspec source commit does not match the upstream source commit")
    if metadata["source_commit"] != metadata["tag_commit"]:
        raise VersionError("Upstream source commit must be the selected tag's peeled commit")
    branches = metadata["source_branches"]
    if (
        not isinstance(branches, list)
        or not branches
        or any(not isinstance(branch, str) or not branch for branch in branches)
        or branches != sorted(set(branches))
    ):
        raise VersionError("Metadata must record sorted upstream branches containing its tag")
    tag = metadata["tag"]
    parsed = parse_release_tag(tag) if isinstance(tag, str) else None
    if parsed is None or metadata["version"] != parsed.version:
        raise VersionError("Metadata version does not match its legal upstream tag")
    if not isinstance(metadata["pubspec_version"], str):
        raise VersionError("Metadata pubspec_version must be a string")
    parse_pubspec_version(f"version: {metadata['pubspec_version']}\n")
    return metadata


def make_metadata(
    *,
    source_commit: str,
    tag_ref: str,
    tag_commit: str,
    source_branches: Iterable[str],
    pubspec_version: str,
) -> dict[str, Any]:
    if not tag_ref.startswith(UPSTREAM_TAG_NAMESPACE):
        raise VersionError("Selected tag must come from the upstream tag namespace")
    tag = tag_ref.removeprefix(UPSTREAM_TAG_NAMESPACE)
    parsed = parse_release_tag(tag)
    if parsed is None:
        raise VersionError(f"Not a legal upstream release tag: {tag}")
    metadata = {
        "schema_version": SCHEMA_VERSION,
        "repository": UPSTREAM_REPOSITORY,
        "source_commit": _require_sha1("source_commit", source_commit),
        "tag": tag,
        "tag_commit": _require_sha1("tag_commit", tag_commit),
        "source_branches": sorted(set(source_branches)),
        "version": parsed.version,
        "pubspec_version": parse_pubspec_version(f"version: {pubspec_version}\n"),
        "pubspec_source_commit": source_commit,
    }
    return validate_metadata_shape(metadata)


def sync_action(
    current: dict[str, Any] | None,
    target: dict[str, Any],
    *,
    source_in_base: bool,
) -> str:
    validate_metadata_shape(target)
    if current is not None:
        try:
            validate_metadata_shape(current)
        except VersionError:
            current = None
    identity_fields = METADATA_KEYS - {"source_branches"}
    same_release = current is not None and all(
        current[field] == target[field] for field in identity_fields
    )
    if source_in_base and same_release:
        return "no_change"
    if source_in_base:
        return "metadata_only"
    return "merge"


def git_is_ancestor(repo: Path, ancestor: str, descendant: str) -> bool:
    result = subprocess.run(
        ["git", "merge-base", "--is-ancestor", ancestor, descendant],
        cwd=repo,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode == 0:
        return True
    if result.returncode == 1:
        return False
    detail = result.stderr.strip() or f"git exited {result.returncode}"
    raise VersionError(f"Could not verify commit ancestry: {detail}")


def resolve_tag_commit(repo: Path, tag_ref: str) -> str:
    if not tag_ref.startswith(UPSTREAM_TAG_NAMESPACE):
        raise VersionError("Selected tag ref is outside the upstream tag namespace")
    result = subprocess.run(
        ["git", "rev-parse", "--verify", f"{tag_ref}^{{commit}}"],
        cwd=repo,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise VersionError(f"Could not peel upstream tag to a commit: {tag_ref}")
    return _require_sha1("peeled tag commit", result.stdout.strip())


def _git_show(repo: Path, revision_path: str) -> str:
    result = subprocess.run(
        ["git", "show", revision_path],
        cwd=repo,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise VersionError(f"Could not read upstream source {revision_path}")
    return result.stdout


def build_release_plan(
    *,
    metadata: dict[str, Any],
    current_pubspec: str,
    repo: Path,
    head: str,
    run_number: int,
) -> dict[str, Any]:
    validate_metadata_shape(metadata)
    selected = select_reachable_release(repo)
    if (
        selected["tag"] != metadata["tag"]
        or selected["tag_commit"] != metadata["tag_commit"]
        or selected["source_commit"] != metadata["source_commit"]
    ):
        raise VersionError(
            "Recorded upstream tag/source does not match the highest official reachable release tag"
        )
    _require_sha1("head", head)
    if not git_is_ancestor(repo, metadata["source_commit"], head):
        raise VersionError("Recorded upstream source commit is not contained in HEAD")
    if not git_is_ancestor(repo, metadata["tag_commit"], metadata["source_commit"]):
        raise VersionError("Recorded release tag is not an ancestor of the upstream source")
    source_pubspec_version = parse_pubspec_version(
        _git_show(repo, f"{metadata['pubspec_source_commit']}:pubspec.yaml")
    )
    if source_pubspec_version != metadata["pubspec_version"]:
        raise VersionError("Recorded upstream pubspec version does not match its source commit")
    current_version = parse_pubspec_version(current_pubspec)
    if current_version != metadata["pubspec_version"]:
        raise VersionError("Current pubspec version does not match its recorded upstream source")
    if not isinstance(run_number, int) or isinstance(run_number, bool) or run_number < 1:
        raise VersionError("GitHub run number must be a positive integer")
    version_code = FORK_VERSION_CODE_BASE + run_number
    if version_code > ANDROID_VERSION_CODE_LIMIT:
        raise VersionError("Fork Android version code exceeds the platform limit")
    display_name = f"{metadata['version']}-X"
    return {
        "name": display_name,
        "number": version_code,
        "tag": f"v{display_name}+{version_code}",
        "upstream_repository": metadata["repository"],
        "upstream_source_commit": metadata["source_commit"],
        "upstream_tag": metadata["tag"],
        "upstream_tag_commit": metadata["tag_commit"],
        "upstream_source_branches": metadata["source_branches"],
        "upstream_pubspec_version": metadata["pubspec_version"],
        "upstream_pubspec_source_commit": metadata["pubspec_source_commit"],
    }


def _read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise VersionError(f"Could not read JSON file {path}: {error}") from error


def _write_metadata(path: Path, metadata: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    select_parser = commands.add_parser(
        "select-tag", help="Select the highest release tag reachable from upstream branches"
    )
    select_parser.add_argument("--repo", type=Path, default=Path.cwd())

    metadata_parser = commands.add_parser("make-metadata")
    metadata_parser.add_argument("--source-commit", required=True)
    metadata_parser.add_argument("--tag-ref", required=True)
    metadata_parser.add_argument("--tag-commit", required=True)
    metadata_parser.add_argument("--source-branch", action="append", required=True)
    metadata_parser.add_argument("--pubspec-version", required=True)
    metadata_parser.add_argument("--output", type=Path, required=True)

    decision_parser = commands.add_parser("sync-decision")
    decision_parser.add_argument("--current", type=Path)
    decision_parser.add_argument("--target", type=Path, required=True)
    decision_parser.add_argument("--upstream-in-base", choices=("true", "false"), required=True)

    release_parser = commands.add_parser("release-plan")
    release_parser.add_argument("--metadata", type=Path, required=True)
    release_parser.add_argument("--pubspec", type=Path, required=True)
    release_parser.add_argument("--repo", type=Path, default=Path.cwd())
    release_parser.add_argument("--head", required=True)
    release_parser.add_argument("--run-number", type=int, required=True)

    args = parser.parse_args(argv)
    try:
        if args.command == "select-tag":
            print(json.dumps(select_reachable_release(args.repo.resolve()), sort_keys=True))
        elif args.command == "make-metadata":
            metadata = make_metadata(
                source_commit=args.source_commit,
                tag_ref=args.tag_ref,
                tag_commit=args.tag_commit,
                source_branches=args.source_branch,
                pubspec_version=args.pubspec_version,
            )
            if not git_is_ancestor(Path.cwd(), metadata["tag_commit"], metadata["source_commit"]):
                raise VersionError("Selected release tag is not an ancestor of its source commit")
            _write_metadata(args.output, metadata)
        elif args.command == "sync-decision":
            current = _read_json(args.current) if args.current and args.current.exists() else None
            target = validate_metadata_shape(_read_json(args.target))
            print(
                sync_action(
                    current,
                    target,
                    source_in_base=args.upstream_in_base == "true",
                )
            )
        else:
            plan = build_release_plan(
                metadata=_read_json(args.metadata),
                current_pubspec=args.pubspec.read_text(encoding="utf-8"),
                repo=args.repo.resolve(),
                head=args.head,
                run_number=args.run_number,
            )
            print(json.dumps(plan, sort_keys=True))
    except (OSError, VersionError) as error:
        print(f"xclash_version: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
