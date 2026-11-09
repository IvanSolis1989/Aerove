import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / 'tooling'))
import product_source

if __package__:
    from .xclash_version import parse_release_tag
else:
    from xclash_version import parse_release_tag


CHANGELOG_PATH = 'docs/aerove/CHANGELOG.md'
LEGACY_CHANGELOG_PATH = 'docs/CHANGELOG_Aerove.md'
BEGIN = '<!-- flclash:changelog:begin -->'
END = '<!-- flclash:changelog:end -->'


def sections(markdown):
    headings = list(re.finditer(r'^##[ \t]+(.+)$', markdown, re.MULTILINE))
    result = {}
    for index, heading in enumerate(headings):
        end = headings[index + 1].start() if index + 1 < len(headings) else len(markdown)
        title = heading.group(1).strip()
        if title in result:
            raise ValueError(f'Duplicate changelog heading: {title}')
        result[title] = markdown[heading.end():end].strip()
    return result


def notes_for_release(markdown, tag, previous_markdown=''):
    version = tag.removeprefix('v')
    identity = re.fullmatch(r'(v[^+\s/]+)\+([1-9]\d*)', tag)
    if identity is None or parse_release_tag(identity[1]) is None:
        raise ValueError('Expected an exact Aerove release tag including its build number')
    current = sections(markdown)
    exact = [body for title, body in current.items()
             if re.fullmatch(r'v?' + re.escape(version) + r'(?:[ \t]+—.*)?', title)]
    if len(exact) > 1:
        raise ValueError(f'Multiple changelog sections match {tag}')
    if exact:
        notes = exact[0]
    else:
        base = identity[1].removeprefix('v')
        candidates = [(title, body) for title, body in current.items()
                      if re.fullmatch(r'v?' + re.escape(base) + r'(?:[ \t]+—.*)?', title)]
        if candidates:
            if len(candidates) != 1:
                raise ValueError(f'Multiple changelog sections match the product version for {tag}')
            title, notes = candidates[0]
            if not re.fullmatch(r'v?' + re.escape(base)
                                + r'[ \t]+—[ \t]+开发候选(?:，[^\r\n]+)?', title):
                raise ValueError(f'Base-version section for {tag} must be an explicit development candidate')
            notes = development_candidate_notes(notes)
        else:
            notes = pending_notes(current, previous_markdown, tag)
    if not re.search(r'^[ \t]*-[ \t]+\S', notes, re.MULTILINE):
        raise ValueError('Release notes must contain actual change entries')
    if '<!--' in notes:
        raise ValueError('Release notes must not contain embedded control comments')
    return notes


def development_candidate_notes(body):
    if '<!--' in body:
        raise ValueError('Release notes must not contain embedded control comments')
    if not re.match(r'-[ \t]+\S', body):
        status, separator, body = body.partition('\n\n')
        if (not separator or '\n' in status or status.startswith(('#', '>', '-', '*', '`'))
                or ('本候选未接受、未发行。' not in status
                    and status != '本候选尚未构建、验收或发行，安装编号由下一次实际构建生成。')):
            raise ValueError('Development candidate notes must start with entries or one explicit status paragraph')
        body = body.lstrip('\n')
    if not re.match(r'-[ \t]+\S', body):
        raise ValueError('Development candidate notes must contain change entries after the status paragraph')
    if any(line and not re.match(r'(?:-[ \t]+\S|[ \t]+\S)', line)
           for line in body.splitlines()):
        raise ValueError('Development candidate notes allow only change entries and indented continuations')
    return body


def pending_notes(current, previous_markdown, tag):
    pending = [current[key] for key in ('待发布', 'Unreleased') if key in current]
    if len(pending) != 1:
        raise ValueError(f'No exact section for {tag} or a single 待发布 section in {CHANGELOG_PATH}')
    notes = pending[0]
    previous = sections(previous_markdown)
    if notes in (previous.get('待发布'), previous.get('Unreleased')):
        raise ValueError('Pending notes are unchanged from the previous release source')
    return notes


def render_release(markdown, tag, build_info, previous_markdown=''):
    version = tag.removeprefix('v')
    if build_info.get('version') != version:
        raise ValueError('The release tag does not match BUILD_INFO.json')
    source = build_info.get('source', '')
    if not re.fullmatch(r'[0-9a-f]{40}', source):
        raise ValueError('BUILD_INFO.json must identify the full source commit')
    notes = notes_for_release(markdown, tag, previous_markdown)
    if re.search(r'[\u4e00-\u9fff]', notes):
        notes = ('The original change entries are available in Chinese below; '
                 'they have not been represented as an English translation.\n\n'
                 '<details>\n<summary>简体中文 — original change entries</summary>\n\n'
                 + notes + '\n\n</details>')
    return (
        f'# AEROVE {tag}\n\n'
        f'{BEGIN}\n<!-- aerove:changelog:tag {tag} -->\n{notes}\n{END}\n\n'
        f'Version: `{version}`\n\nSource: `{source}`\n\n'
        'Android ARM64. Refer to `BUILD_INFO.json` for the actual package and signing identity.\n\n'
        'Verify downloads with `SHA256SUMS`; build and upstream provenance are recorded in `BUILD_INFO.json`.\n\n'
        'Use only with authorization and in accordance with applicable law. AEROVE does not provide proxy endpoints or subscriptions. '
        'The GPL-3.0 warranty disclaimer applies; this notice does not restrict license rights.\n'
    )


def git(*arguments, repo=None):
    return subprocess.check_output(['git', *arguments], cwd=repo, text=True, encoding='utf-8').strip()


def source_changelog(source, repo=None, required=True):
    for path in (CHANGELOG_PATH, LEGACY_CHANGELOG_PATH):
        result = subprocess.run(
            ['git', 'show', f'{source}:{path}'], cwd=repo,
            capture_output=True, text=True, encoding='utf-8',
        )
        if result.returncode == 0:
            return result.stdout
    if required:
        raise ValueError(f'No committed Aerove changelog at {source}')
    return ''


def previous_source_changelog(previous_tag, repo=None):
    if not previous_tag:
        return ''
    return source_changelog(previous_tag, repo=repo, required=False)


def release_changelog(build_source, changelog_source=None, repo=None, *, build_run=None, release_run=None):
    if (build_run is None) != (release_run is None):
        raise ValueError('Accepted original notes require both build and release run IDs')
    if build_run is not None and not changelog_source:
        raise ValueError('Accepted original notes require an explicit release source')
    if not changelog_source:
        return source_changelog(build_source, repo=repo)
    source = git('rev-parse', f'{changelog_source}^{{commit}}', repo=repo)
    git('merge-base', '--is-ancestor', build_source, source, repo=repo)
    if build_run is None:
        product_source.equivalent(root=repo, product='aerove',
                                 build_source=build_source, release_source=source)
    else:
        if __package__:
            from . import aerove_artifact
        else:
            import aerove_artifact
        root = Path(git('rev-parse', '--show-toplevel', repo=repo))
        selected = aerove_artifact.accepted_build(root=root, repository='IvanSolis1989/Aerove',
            source=source, build_run=build_run, release_run=release_run)
        if selected['build_source'] != build_source:
            raise ValueError('Accepted original notes source differs from the selected original APK')
    return source_changelog(source, repo=repo)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--tag', required=True)
    parser.add_argument('--source', required=True)
    parser.add_argument('--changelog-source')
    parser.add_argument('--build-run', type=int)
    parser.add_argument('--release-run', type=int)
    parser.add_argument('--previous-tag', default='')
    parser.add_argument('--repo', type=Path, default=Path.cwd())
    parser.add_argument('--build-info', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    info = json.loads(args.build_info.read_text(encoding='utf-8'))
    source = git('rev-parse', f'{args.source}^{{commit}}', repo=args.repo)
    if info.get('source') != source:
        raise ValueError('The requested source does not match BUILD_INFO.json')
    markdown = release_changelog(source, args.changelog_source, repo=args.repo,
                                 build_run=args.build_run, release_run=args.release_run)
    previous = previous_source_changelog(args.previous_tag, repo=args.repo)
    args.output.write_text(render_release(markdown, args.tag, info, previous), encoding='utf-8')


if __name__ == '__main__':
    main()
