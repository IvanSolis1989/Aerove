"""Prefetch the pinned SQLite native assets without weakening its hook checksum."""
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
ASSET_URL = 'https://api.github.com/repos/simolus3/sqlite3.dart/releases/assets/600688886'
URL = f'https://github.com/simolus3/sqlite3.dart/releases/download/sqlite3-{VERSION}/{FILENAME}?download=1'

ASSETS = {
    'linux-x64': (FILENAME, SHA256, 600688886),
    'android-x64': ('libsqlite3.x64.android.so',
                    '949965f0eba976f707ae364cdcb42c342b5f0626081f8d7f0378fb7b52848772', 600688868),
    'android-arm64': ('libsqlite3.arm64.android.so',
                      '0c2d3bfc8c87abceb21ed72a4bb49964121c5fe1a8ef3848d83ba907d01b6161', 600688808),
}


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def package_root(project, target="linux-x64"):
    filename, sha256, _ = ASSETS[target]
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
    if f"'{filename}': '{sha256}'" not in hashes:
        raise ValueError('Pinned checksum disagrees with the resolved SQLite package')
    return root


def prefetch(project, download=subprocess.run, target="linux-x64"):
    filename, sha256, asset_id = ASSETS[target]
    asset_url = f'https://api.github.com/repos/simolus3/sqlite3.dart/releases/assets/{asset_id}'
    release_url = f'https://github.com/simolus3/sqlite3.dart/releases/download/sqlite3-{VERSION}/{filename}?download=1'
    project = project.resolve()
    package_root(project, target)
    # sqlite3 3.7.0 validates this content-addressed cache again before use.
    cache = project / '.dart_tool/hooks_runner/shared/sqlite3/build' / f'download-{sha256[:8]}'
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / 'libsqlite3.so'
    if target.is_file() and digest(target) == sha256:
        return target
    with tempfile.TemporaryDirectory(prefix='prefetch-', dir=cache) as staging:
        candidate = Path(staging) / filename
        for index, url in enumerate((asset_url, release_url)):
            candidate.unlink(missing_ok=True)
            try:
                download(['curl', '--fail', '--location', '--silent', '--show-error',
                          '--proto', '=https', '--proto-redir', '=https',
                          '--header', 'Accept: application/octet-stream',
                          '--connect-timeout', '15', '--max-time', '45',
                          '--retry', '3', '--retry-delay', '2', '--retry-max-time', '120',
                          '--output', str(candidate), url], check=True, timeout=180)
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
                if index == 0:
                    print('Official SQLite asset API unavailable; trying the release URL', flush=True)
                    continue
                raise
            if digest(candidate) != sha256:
                raise ValueError('SQLite asset checksum mismatch; refusing cache publication')
            break
        candidate.replace(target)
    return target


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('project', type=Path, nargs='+')
    parser.add_argument('--target', choices=tuple(ASSETS), action='append')
    args = parser.parse_args()
    for project in args.project:
        for platform in args.target or ['linux-x64']:
            target = prefetch(project, target=platform)
            print(f'Verified SQLite {VERSION} {platform}: {target} sha256={digest(target)}')


if __name__ == '__main__':
    main()
