"""Seed Gradle's checksum-addressed store with the exact official Kotlin JAR."""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile

VERSION = '2.3.21'
MODULE = 'kotlin-compiler-embeddable'
FILENAME = f'{MODULE}-{VERSION}.jar'
SHA256 = 'd3e70fb011675e77ef00f1a68918d3cd91eed26058977d1e3ea2a37a293233af'
SHA1 = 'c4b47f8e33cdbeedd8c1415ab9f9ade870388162'
URLS = (
    'https://api.github.com/repos/JetBrains/kotlin/releases/assets/598615460',
    f'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/{MODULE}/{VERSION}/{FILENAME}?download=1',
)


def digest(path, algorithm):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def verified(path):
    return path.is_file() and digest(path, 'sha256') == SHA256 and digest(path, 'sha1') == SHA1


def prefetch(home, download=subprocess.run):
    store = home.resolve() / 'caches/modules-2/files-2.1/org.jetbrains.kotlin' / MODULE / VERSION / SHA1
    store.mkdir(parents=True, exist_ok=True)
    target = store / FILENAME
    if verified(target):
        return target
    with tempfile.TemporaryDirectory(prefix='prefetch-', dir=store) as staging:
        candidate = Path(staging) / FILENAME
        for index, url in enumerate(URLS):
            candidate.unlink(missing_ok=True)
            try:
                download(['curl', '--fail', '--location', '--silent', '--show-error',
                          '--proto', '=https', '--proto-redir', '=https',
                          '--header', 'Accept: application/octet-stream',
                          '--connect-timeout', '15', '--max-time', '90',
                          '--retry', '2', '--retry-delay', '2', '--retry-max-time', '180',
                          '--output', str(candidate), url], check=True, timeout=280)
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
                if index == 0:
                    print('Official Kotlin asset API unavailable; trying Maven Central', flush=True)
                    continue
                raise
            if not verified(candidate):
                raise ValueError('Kotlin compiler checksum mismatch; refusing cache publication')
            break
        candidate.replace(target)
    return target


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gradle-user-home', type=Path,
                        default=Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')))
    args = parser.parse_args()
    target = prefetch(args.gradle_user_home)
    print(f'Verified Kotlin {VERSION}: {target} sha256={digest(target, "sha256")}')


if __name__ == '__main__':
    main()
