"""Seed Gradle's checksum-addressed store with the exact official Kotlin assets."""
import argparse
import hashlib
import os
import shutil
from pathlib import Path
import subprocess
import tempfile

VERSION = '2.3.21'
MODULE = 'kotlin-compiler-embeddable'
FILENAME = f'{MODULE}-{VERSION}.jar'
SHA256 = 'd3e70fb011675e77ef00f1a68918d3cd91eed26058977d1e3ea2a37a293233af'
POM_SHA256 = '9523c8feadcb17f42d04ffa97fe2a9c08e5db18f090f6db903dca5c36c7ae5c4'
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


def prefetch_pom(home, download=subprocess.run):
    filename = f'{MODULE}-{VERSION}.pom'
    store = home.resolve() / 'caches/modules-2/files-2.1/org.jetbrains.kotlin' / MODULE / VERSION
    store.mkdir(parents=True, exist_ok=True)
    # setup-gradle restores this store; never trust its directory name alone.
    for cached in sorted(store.glob(f'*/{filename}')):
        if cached.is_file() and digest(cached, 'sha256') == POM_SHA256:
            return cached
    with tempfile.TemporaryDirectory(prefix='prefetch-', dir=store) as staging:
        candidate = Path(staging) / filename
        # Both Central hostnames serve the same repository, not independent mirrors.
        # Use a cacheable URL and curl's exponential backoff / Retry-After handling.
        download(['curl', '--fail', '--location', '--silent', '--show-error',
                  '--proto', '=https', '--proto-redir', '=https',
                  '--connect-timeout', '15', '--max-time', '45',
                  '--retry', '6', '--retry-max-time', '240',
                  '--output', str(candidate),
                  f'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/{MODULE}/{VERSION}/{filename}'],
                 check=True, timeout=300)
        if not candidate.is_file() or digest(candidate, 'sha256') != POM_SHA256:
            raise ValueError('Kotlin POM checksum mismatch; refusing cache publication')
        target = store / digest(candidate, 'sha1') / filename
        target.parent.mkdir(parents=True, exist_ok=True)
        candidate.replace(target)
        return target


def configure_repository(home, jar, download=subprocess.run):
    if not verified(jar):
        raise ValueError('Unverified Kotlin JAR cannot be installed in the local repository')
    home = home.resolve()
    repository = home / 'aerove-pinned-maven'
    module = repository / 'org/jetbrains/kotlin' / MODULE / VERSION
    module.mkdir(parents=True, exist_ok=True)
    pom = module / f'{MODULE}-{VERSION}.pom'
    target = module / FILENAME
    with tempfile.TemporaryDirectory(prefix='prefetch-', dir=module) as staging:
        staging = Path(staging)
        if not pom.is_file() or digest(pom, 'sha256') != POM_SHA256:
            candidate = staging / pom.name
            shutil.copyfile(prefetch_pom(home, download), candidate)
            if digest(candidate, 'sha256') != POM_SHA256:
                raise ValueError('Kotlin POM checksum mismatch; refusing repository publication')
            candidate.replace(pom)
        if not verified(target):
            candidate = staging / FILENAME
            shutil.copyfile(jar, candidate)
            if not verified(candidate):
                raise ValueError('Kotlin JAR changed while installing the local repository')
            candidate.replace(target)
    init = home / 'init.d/aerove-pinned-kotlin.gradle'
    init.parent.mkdir(parents=True, exist_ok=True)
    content = f"""import java.security.MessageDigest

def repository = new File(gradle.gradleUserHomeDir, 'aerove-pinned-maven')
def moduleDir = new File(repository, 'org/jetbrains/kotlin/{MODULE}/{VERSION}')
def verify = {{ File file, String expected ->
    def hash = MessageDigest.getInstance('SHA-256')
    file.withInputStream {{ stream ->
        byte[] buffer = new byte[65536]
        int count
        while ((count = stream.read(buffer)) != -1) {{ hash.update(buffer, 0, count) }}
    }}
    if (hash.digest().encodeHex().toString() != expected) {{
        throw new GradleException('AEROVE pinned Kotlin asset integrity failure: ' + file.name)
    }}
}}
verify(new File(moduleDir, '{FILENAME}'), '{SHA256}')
verify(new File(moduleDir, '{MODULE}-{VERSION}.pom'), '{POM_SHA256}')
def configureRepository = {{ repositories ->
    repositories.exclusiveContent {{
        forRepository {{
            repositories.maven {{
                name = 'aeroveVerifiedKotlinCompiler'
                url = repository.toURI()
                metadataSources {{ mavenPom(); ignoreGradleMetadataRedirection() }}
            }}
        }}
        filter {{ includeVersion('org.jetbrains.kotlin', '{MODULE}', '{VERSION}') }}
    }}
}}
gradle.settingsEvaluated {{ settings ->
    def management = settings.dependencyResolutionManagement
    configureRepository(management.repositories)
    if (management.repositoriesMode.get() == org.gradle.api.initialization.resolve.RepositoriesMode.PREFER_PROJECT) {{
        gradle.beforeProject {{ project -> configureRepository(project.repositories) }}
    }}
}}
"""
    with tempfile.NamedTemporaryFile(mode='w', dir=init.parent, delete=False) as stream:
        temporary = Path(stream.name)
        stream.write(content)
    temporary.replace(init)
    return init


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gradle-user-home', type=Path,
                        default=Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')))
    args = parser.parse_args()
    target = prefetch(args.gradle_user_home)
    init = configure_repository(args.gradle_user_home, target)
    print(f'Exact-coordinate verified Maven repository configured: {init}')
    print(f'Verified Kotlin {VERSION}: {target} sha256={digest(target, "sha256")}')


if __name__ == '__main__':
    main()
