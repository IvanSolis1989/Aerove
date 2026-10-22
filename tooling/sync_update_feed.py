"""Keep the raw update URL used by released Aerove clients working after relocation."""
import argparse
from pathlib import Path
import posixpath
import re

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'docs/aerove/CHANGELOG.md'
FEED = ROOT / 'docs/CHANGELOG_Aerove.md'


def contents():
    text = SOURCE.read_text(encoding='utf-8')

    def link(match):
        value = match[2]
        if ':' in value or value.startswith(('#', '/')):
            return match[0]
        target, marker, anchor = value.partition('#')
        relative = posixpath.relpath(posixpath.normpath('aerove/' + target), '.')
        return match[1] + relative + (marker + anchor if marker else '') + match[3]

    text = re.sub(r'(!?\[[^\]\n]*\]\()([^\s)]+)([^)]*\))', link, text)
    return '<!-- Generated compatibility feed. Edit docs/aerove/CHANGELOG.md and run tooling/sync_update_feed.py. -->\n' + text


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    expected = contents()
    if args.check:
        if not FEED.exists() or FEED.read_text(encoding='utf-8') != expected:
            parser.exit(1, 'Compatibility update feed is stale; run python tooling/sync_update_feed.py\n')
    else:
        FEED.write_text(expected, encoding='utf-8', newline='\n')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
