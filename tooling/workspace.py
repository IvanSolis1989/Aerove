"""Run product commands from their own roots and check the public workspace layout."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PRODUCTS = {'aerove': ROOT / 'apps/aerove', 'aerove-lite': ROOT / 'apps/aerove-lite'}
PUBLIC_DIRECTORIES = {'.agents', '.claude', '.codex', '.github', 'apps', 'docs', 'experiments', 'tooling', 'website'}
DOC_AREAS = {'shared', 'aerove', 'aerove-lite', 'development', 'research', 'archive', 'assets', 'i18n'}
ROOT_FILES = {'AGENTS.md', 'README.md', 'LICENSE', 'Makefile', '.gitignore', '.gitattributes',
              '.gitmodules', '.pre-commit-config.yaml', '.mcp.json'}


def check():
    files = subprocess.check_output(
        ['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT,
    ).decode('utf-8').split('\0')
    files = sorted({name for name in files if name and (ROOT / name).is_file()})
    errors = []
    for name in files:
        parts = Path(name).parts
        if len(parts) == 1 and name not in ROOT_FILES:
            errors.append(f'Unexpected public root file: {name}')
        elif len(parts) > 1 and parts[0] not in PUBLIC_DIRECTORIES:
            errors.append(f'Unexpected public root directory: {parts[0]}')
        if parts[0] == 'docs' and len(parts) > 1 and parts[1] not in {'README.md', 'CHANGELOG_Aerove.md'} and parts[1] not in DOC_AREAS:
            errors.append(f'Unclassified document: {name}')
    for name in ['apps/aerove/pubspec.yaml', 'apps/aerove-lite/settings.gradle.kts', 'docs/README.md',
                 'docs/shared/UI_CONTRACT.md', 'docs/aerove/CHANGELOG.md']:
        if not (ROOT / name).is_file():
            errors.append(f'Missing workspace entry: {name}')
    vendor = ROOT / 'apps/aerove-lite/third_party/material-color-utilities'
    sources = json.loads((vendor / 'SOURCES.json').read_text(encoding='utf-8'))
    listed = set()
    for source in sources['files']:
        path = (vendor / source['path']).resolve()
        if not path.is_relative_to(vendor.resolve()) or not path.is_file():
            errors.append(f'Invalid third-party source path: {source["path"]}')
            continue
        listed.add(path)
        if hashlib.sha256(path.read_bytes()).hexdigest() != source['sha256']:
            errors.append(f'Third-party source differs from its manifest: {source["path"]}')
        if '/' + sources['commit'] + '/' not in source['source']:
            errors.append(f'Third-party source URL is not commit pinned: {source["path"]}')
    if listed != {path.resolve() for path in (vendor / 'src').rglob('*.java')}:
        errors.append('Third-party source closure differs from its manifest')
    from sync_update_feed import FEED, contents
    if not FEED.exists() or FEED.read_text(encoding='utf-8') != contents():
        errors.append('Compatibility update feed is stale; run python tooling/sync_update_feed.py')
    for name in files:
        if not name.endswith('.md') or not (name.startswith(('docs/', '.agents/')) or name in ROOT_FILES):
            continue
        content = (ROOT / name).read_text(encoding='utf-8')
        for target in re.findall(r'!?\[[^\]\n]*\]\(([^\s)]+)(?:\s+[^)]*)?\)', content):
            if ':' in target or target.startswith(('#', '/')) or '<' in target:
                continue
            target = target.partition('#')[0]
            if target and not (ROOT / name).parent.joinpath(target).exists():
                errors.append(f'Broken local link: {name} -> {target}')
    if errors:
        print('\n'.join(sorted(set(errors))), file=sys.stderr)
        return 1
    print(json.dumps({'products': {name: path.relative_to(ROOT).as_posix() for name, path in PRODUCTS.items()},
                      'publicRootDirectories': sorted({Path(name).parts[0] for name in files if len(Path(name).parts) > 1}),
                      'checkedFiles': len(files), 'layout': 'valid'}, ensure_ascii=False))
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('check')
    run = commands.add_parser('run')
    run.add_argument('product', choices=PRODUCTS)
    run.add_argument('arguments', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.command == 'check':
        return check()
    arguments = args.arguments[1:] if args.arguments[:1] == ['--'] else args.arguments
    if not arguments:
        parser.error('run requires a command after the product name')
    executable = shutil.which(arguments[0])
    if executable is None:
        parser.exit(127, f'Tool not found: {arguments[0]}\n')
    return subprocess.call([executable, *arguments[1:]], cwd=PRODUCTS[args.product])


if __name__ == '__main__':
    raise SystemExit(main())
