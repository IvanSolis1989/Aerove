"""Prefetch the pinned host SQLite asset without weakening its hook checksum."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
from urllib.parse import unquote, urljoin, urlparse

VERSION = '3.7.0'
FILENAME = 'libsqlite3.x64.linux.so'
SHA256 = '0f947ebe629e8d9d02d7f408bef36e056bfc63ea47e02e0d45a7bae454f04ace'
URL = f'https://github.com/simolus3/sqlite3.dart/releases/download/sqlite3-{VERSION}/{FILENAME}?download=1'


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def package_root(project):
    config = project / '.dart_tool/package_config.json'
    packages = json.loads(config.read_text())['packages']
    entry = next(item for item in packages if item['name'] == 'sqlite3')
    uri = urlparse(urljoin(config.as_uri(), entry['rootUri']))
    if uri.scheme != 'file' or uri.netloc:
        raise ValueError('SQLite package must resolve to a local file URI')
    root = Path(unquote(uri.path))
    pubspec = (root / 'pubspec.yaml').read_text()
    hashes = (root / 'lib/src/hook/asset_hashes.dart').read_text()
    if not re.search(rf'^version:\s*{re.escape(VERSION)}\s*$', pubspec, re.M):
        raise ValueError('Revalidate the SQLite hook cache contract before upgrading')
    if f"releaseTag = 'sqlite3-{VERSION}'" not in hashes:
        raise ValueError('Unexpected SQLite release tag')
    if f"'{FILENAME}': '{SHA256}'" not in hashes:
        raise ValueError('Pinned checksum disagrees with the resolved SQLite package')
    return root


def prefetch(project, download=subprocess.run):
    project = project.resolve()
    package_root(project)
    # sqlite3 3.7.0 validates this content-addressed cache again before use.
    cache = project / '.dart_tool/hooks_runner/shared/sqlite3/build' / f'download-{SHA256[:8]}'
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / 'libsqlite3.so'
    if target.is_file() and digest(target) == SHA256:
        return target
    with tempfile.TemporaryDirectory(prefix='prefetch-', dir=cache) as staging:
        candidate = Path(staging) / FILENAME
        download(['curl', '--fail', '--location', '--silent', '--show-error',
                  '--proto', '=https', '--proto-redir', '=https',
                  '--connect-timeout', '15', '--max-time', '45',
                  '--retry', '3', '--retry-delay', '2', '--retry-max-time', '120',
                  '--output', str(candidate), URL], check=True, timeout=180)
        if digest(candidate) != SHA256:
            raise ValueError('SQLite asset checksum mismatch; refusing cache publication')
        candidate.replace(target)
    return target


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('project', type=Path, nargs='+')
    args = parser.parse_args()
    for project in args.project:
        target = prefetch(project)
        print(f'Verified SQLite {VERSION}: {target} sha256={digest(target)}')


if __name__ == '__main__':
    main()
