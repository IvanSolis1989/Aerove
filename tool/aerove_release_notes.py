import argparse
import json
import re
import subprocess
from pathlib import Path


CHANGELOG_PATH = 'docs/CHANGELOG_Aerove.md'
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
    if not re.fullmatch(r'v[^\s/]+-X\+\d+', tag):
        raise ValueError('Expected an exact Aerove release tag including its build number')
    current = sections(markdown)
    exact = [body for title, body in current.items()
             if re.fullmatch(r'v?' + re.escape(version) + r'(?:[ \t]+—.*)?', title)]
    if len(exact) > 1:
        raise ValueError(f'Multiple changelog sections match {tag}')
    if exact:
        notes = exact[0]
    else:
        pending = [current[key] for key in ('待发布', 'Unreleased') if key in current]
        if len(pending) != 1:
            raise ValueError(f'No exact section for {tag} or a single 待发布 section in {CHANGELOG_PATH}')
        notes = pending[0]
        previous = sections(previous_markdown)
        if notes in (previous.get('待发布'), previous.get('Unreleased')):
            raise ValueError('Pending notes are unchanged from the previous release source')
    if not re.search(r'^[ \t]*-[ \t]+\S', notes, re.MULTILINE):
        raise ValueError('Release notes must contain actual change entries')
    if '<!--' in notes:
        raise ValueError('Release notes must not contain embedded control comments')
    return notes


def render_release(markdown, tag, build_info, previous_markdown=''):
    version = tag.removeprefix('v')
    if build_info.get('version') != version:
        raise ValueError('The release tag does not match BUILD_INFO.json')
    source = build_info.get('source', '')
    if not re.fullmatch(r'[0-9a-f]{40}', source):
        raise ValueError('BUILD_INFO.json must identify the full source commit')
    notes = notes_for_release(markdown, tag, previous_markdown)
    return (
        f'# AEROVE {tag}\n\n'
        f'{BEGIN}\n<!-- aerove:changelog:tag {tag} -->\n{notes}\n{END}\n\n'
        f'版本：`{version}`\n\n源码：`{source}`\n\n'
        '适用于 Android ARM64，使用固定测试签名，包名为 `com.follow.clash.dev`。\n\n'
        '下载后可用 `SHA256SUMS` 核对文件完整性；构建与上游来源见 `BUILD_INFO.json`。\n'
    )


def git(*arguments):
    return subprocess.check_output(['git', *arguments], text=True, encoding='utf-8').strip()


def previous_source_changelog(previous_tag):
    if not previous_tag:
        return ''
    result = subprocess.run(
        ['git', 'show', f'{previous_tag}:{CHANGELOG_PATH}'],
        capture_output=True, text=True, encoding='utf-8',
    )
    return result.stdout if result.returncode == 0 else ''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--tag', required=True)
    parser.add_argument('--source', required=True)
    parser.add_argument('--previous-tag', default='')
    parser.add_argument('--build-info', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    info = json.loads(args.build_info.read_text(encoding='utf-8'))
    source = git('rev-parse', f'{args.source}^{{commit}}')
    if info.get('source') != source:
        raise ValueError('The requested source does not match BUILD_INFO.json')
    markdown = git('show', f'{source}:{CHANGELOG_PATH}')
    previous = previous_source_changelog(args.previous_tag)
    args.output.write_text(render_release(markdown, args.tag, info, previous), encoding='utf-8')


if __name__ == '__main__':
    main()
