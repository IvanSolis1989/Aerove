"""Install one official fixed API33 Play r01 archive on an isolated CI SDK."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import sys
import tempfile
import zipfile

URL = 'https://dl.google.com/android/repository/sys-img/google_apis_playstore/x86_64-33_r01.zip'
ARCHIVE_BYTES = 1479525531
ARCHIVE_SHA1 = '0db4201201a4b8ea1339fb5bd720d25455849100'
ENTRY_COUNT = 29
EXPANDED_BYTES = 3087611035
PACKAGE = 'system-images;android-33-ext4;google_apis_playstore;x86_64'
RELATIVE = Path('system-images/android-33-ext4/google_apis_playstore/x86_64')
FINGERPRINT = 'google/sdk_gphone64_x86_64/emu64xa:13/TSE4.230104.001/9453911:user/dev-keys'
# Exact runtime value observed on the unmodified official archive in CI #104.
RUNTIME_FINGERPRINT = 'google/sdk_gphone64_x86_64/emu64xa:13/TSE4.230104.001/9453911:user/release-keys'
METADATA_SHA256 = {
    'source.properties': 'f0501669c60a1a9dcb1dc84e59a433d5f8d3fc013a92359f1d912516c5a72f3e',
    'build.prop': 'a8751474ab21d7cdc0fd7aae43883d41e5a21e36d316274e1d6c5918e49195df',
}


def metadata_identity(directory):
    hashes = {}
    for name, expected in METADATA_SHA256.items():
        path = directory / name
        if path.is_symlink() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError('Official fixed image metadata differs: ' + name)
        hashes[name] = expected
    return hashes


def validate_installed_image(directory, sidecar):
    expected = Path(os.environ['ANDROID_HOME']).resolve() / RELATIVE
    if directory.is_symlink() or directory.resolve() != expected:
        raise ValueError('Std reference must use the exact installed Play33 r01 package path')
    value = json.loads(sidecar.read_text())
    required = dict(schema=1, status='VERIFIED AND INSTALLED', officialUrl=URL,
                    archiveBytes=ARCHIVE_BYTES, archiveSha1=ARCHIVE_SHA1, package=PACKAGE,
                    tag='google_apis_playstore', abi='x86_64', sourceLicense='android-sdk-preview-license',
                    apiLevel=33, extensionLevel=4, isBaseSdk=False, revision=1,
                    fingerprint=FINGERPRINT, runtimeFingerprint=RUNTIME_FINGERPRINT, harnessSource=os.environ['GITHUB_SHA'])
    if any(value.get(key) != item for key, item in required.items()):
        raise ValueError('Fixed image identity sidecar does not match this harness/package')
    actual_sha256 = value.get('archiveSha256', '')
    if len(actual_sha256) != 64 or any(char not in '0123456789abcdef' for char in actual_sha256):
        raise ValueError('Missing actual downloaded archive SHA256')
    if value.get('crcVerifiedDuringExtraction') is not True:
        raise ValueError('Image ZIP CRC extraction was not completed')
    if Path(value.get('imageDirectory', '')).resolve() != expected:
        raise ValueError('Image sidecar directory mismatch')
    metadata_identity(directory)
    return value


def write_sidecar(path, value):
    try:
        path.write_text(json.dumps(value, indent=2) + '\n')
        return True
    except Exception as error:
        value['sidecarWriteError'] = repr(error)
        value['status'] = 'FAILED'
        try:
            print('IMAGE_RECEIPT_FALLBACK_JSON=' + json.dumps(value), file=sys.stderr, flush=True)
        except OSError:
            pass
        return False


def install(archive, sdk, sidecar):
    if sys.platform != 'linux' or os.environ.get('GITHUB_ACTIONS') != 'true':
        raise ValueError('This fixed-image installer only runs on an isolated GitHub Linux runner')
    sdk = sdk.resolve()
    if sdk != Path(os.environ['ANDROID_HOME']).resolve():
        raise ValueError('SDK argument differs from the owned job SDK')
    target = sdk / RELATIVE
    if sidecar.exists() or target.exists() or target.is_symlink():
        raise ValueError('Refusing to overwrite an image or existing identity sidecar')
    receipt = dict(schema=1, status='STARTED', officialUrl=URL, package=PACKAGE,
                   tag='google_apis_playstore', abi='x86_64',
                   apiLevel=33, extensionLevel=4, isBaseSdk=False, revision=1,
                   fingerprint=FINGERPRINT, runtimeFingerprint=RUNTIME_FINGERPRINT, imageDirectory=str(target),
                   harnessSource=os.environ['GITHUB_SHA'], harnessRun=os.environ['GITHUB_RUN_ID'],
                   publishedChecksumAlgorithm='SHA-1', publishedChecksum=ARCHIVE_SHA1,
                   sourceCatalog='https://dl.google.com/android/repository/sys-img/google_apis_playstore/sys-img2-3.xml')
    failure = None
    try:
        license_file = sdk / 'licenses/android-sdk-preview-license'
        if not license_file.is_file() or license_file.stat().st_size == 0:
            raise ValueError('Existing SDK setup did not record android-sdk-preview-license')
        receipt.update(sourceLicense='android-sdk-preview-license',
                       sdkLicenseFileSha256=hashlib.sha256(license_file.read_bytes()).hexdigest())
        if archive.is_symlink() or archive.stat().st_size != ARCHIVE_BYTES:
            raise ValueError('Official fixed archive byte size mismatch')
        sha1, sha256 = hashlib.sha1(), hashlib.sha256()
        with archive.open('rb') as stream:
            while data := stream.read(1024 * 1024):
                sha1.update(data)
                sha256.update(data)
        receipt.update(archiveBytes=archive.stat().st_size, archiveSha1=sha1.hexdigest(),
                       archiveSha256=sha256.hexdigest())
        if receipt['archiveSha1'] != ARCHIVE_SHA1:
            raise ValueError('Official fixed archive SHA1 mismatch')
        parent = target.parent
        if parent.resolve() != sdk / RELATIVE.parent:
            raise ValueError('Unexpected symlink in SDK image parent')
        parent.mkdir(parents=True, exist_ok=True)
        stage = Path(tempfile.mkdtemp(prefix='.std-play33-r01-', dir=parent))
        receipt['stagingDirectory'] = str(stage)
        with zipfile.ZipFile(archive) as source:
            entries = source.infolist()
            names = [entry.filename for entry in entries]
            if len(entries) != ENTRY_COUNT or len(names) != len(set(names)):
                raise ValueError('Fixed image entry count or duplicate-name mismatch')
            if sum(entry.file_size for entry in entries) != EXPANDED_BYTES:
                raise ValueError('Fixed image uncompressed byte count mismatch')
            for entry in entries:
                path = PurePosixPath(entry.filename)
                mode = entry.external_attr >> 16
                kind = stat.S_IFMT(mode)
                if (path.is_absolute() or '..' in path.parts or '\\' in entry.filename
                        or not path.parts or path.parts[0] != 'x86_64'
                        or kind not in (0, stat.S_IFREG, stat.S_IFDIR)):
                    raise ValueError('Unsafe official archive entry: ' + entry.filename)
                destination = stage.joinpath(*path.parts)
                if not destination.resolve().is_relative_to(stage.resolve()):
                    raise ValueError('Archive extraction path escaped owned staging directory')
            receipt['entries'] = []
            for entry in entries:
                destination = stage.joinpath(*PurePosixPath(entry.filename).parts)
                if entry.is_dir():
                    destination.mkdir(parents=True, exist_ok=True)
                else:
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    with source.open(entry) as incoming, destination.open('xb') as outgoing:
                        shutil.copyfileobj(incoming, outgoing, 1024 * 1024)
                    if destination.stat().st_size != entry.file_size:
                        raise ValueError('Extracted image entry size mismatch')
                    destination.chmod((entry.external_attr >> 16) & 0o777 or 0o644)
                receipt['entries'].append(dict(path=entry.filename, bytes=entry.file_size,
                                               crc32=f'{entry.CRC:08x}'))
        receipt['metadataSha256'] = metadata_identity(stage / 'x86_64')
        (stage / 'x86_64').rename(target)
        receipt.update(status='VERIFIED AND INSTALLED', crcVerifiedDuringExtraction=True)
    except BaseException as error:
        failure = error
        receipt.update(status='FAILED', errorType=type(error).__name__, error=str(error))
    saved = write_sidecar(sidecar, receipt)
    if failure is not None:
        raise failure
    if not saved:
        raise RuntimeError('Fixed image sidecar could not be saved')
    return receipt


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--archive', required=True, type=Path)
    parser.add_argument('--sdk', required=True, type=Path)
    parser.add_argument('--receipt', required=True, type=Path)
    args = parser.parse_args()
    install(args.archive, args.sdk, args.receipt)


if __name__ == '__main__':
    main()
