"""Update repository-derived links in XClash-owned README files."""

import argparse
from pathlib import Path
import re
import sys


OWNERSHIP = '<!-- xclash-owned-readme -->'
REPOSITORY = r'<!-- xclash-repository: ([^\s]+) -->'


def repository_name(value):
    if not re.fullmatch(r'[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?/[A-Za-z0-9_.-]{1,100}', value):
        raise argparse.ArgumentTypeError('expected a GitHub owner/repository')
    if value.split('/')[1] in {'.', '..'} or value.lower() == 'chen08209/flclash':
        raise argparse.ArgumentTypeError('expected a fork repository')
    return value


def synchronize(text, repository):
    if text.count(OWNERSHIP) != 1:
        raise ValueError('refusing README without XClash ownership marker; preserve the fork README before syncing')
    recorded = re.findall(REPOSITORY, text)
    if len(recorded) != 1:
        raise ValueError('expected one xclash-repository marker')
    old = repository_name(recorded[0])
    if '[FlClash](https://github.com/chen08209/FlClash)' not in text or '[GPL-3.0](LICENSE)' not in text:
        raise ValueError('XClash README must retain upstream attribution and GPL-3.0 license')
    text = re.sub(REPOSITORY, f'<!-- xclash-repository: {repository} -->', text)
    text = re.sub(re.escape('https://github.com/' + old) + r'(?=[/).#?\s]|$)',
                  'https://github.com/' + repository, text)
    text = re.sub(r'https://github\.com/chen08209/FlClash/releases(?=[/)?#\s]|$)',
                  'https://github.com/' + repository + '/releases', text)
    text = re.sub(r'(https://img.shields.io/github/(?:v/release|downloads|license)/)[^/?#]+/[^/?#]+',
                  lambda match: match[1] + repository, text)
    text = re.sub(r'(?m)^cd ' + re.escape(old.split('/')[1]) + '$',
                  'cd ' + repository.split('/')[1], text)
    required = (
        f'https://github.com/{repository}/releases/latest/download/XClash-android-arm64.apk',
        f'git clone --recursive https://github.com/{repository}.git',
    )
    if any(link not in text for link in required):
        raise ValueError('XClash README must include the fork stable APK and clone links')
    return text


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', required=True, type=repository_name)
    parser.add_argument('--check', action='store_true', help='fail if repository links need updating')
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parent.parent)
    args = parser.parse_args()
    updates = []
    try:
        for filename in ('README.md', 'README_en.md'):
            path = args.root / filename
            before = path.read_text(encoding='utf-8')
            after = synchronize(before, args.repository)
            if before != after:
                updates.append((path, after))
    except (OSError, ValueError, argparse.ArgumentTypeError) as error:
        parser.exit(2, f'{error}\n')
    for path, after in updates:
        if not args.check:
            with path.open('w', encoding='utf-8', newline='\n') as output:
                output.write(after)
        print(f'{"Needs synchronization" if args.check else "Updated"}: {path.name}')
    return 1 if args.check and updates else 0


if __name__ == '__main__':
    sys.exit(main())
