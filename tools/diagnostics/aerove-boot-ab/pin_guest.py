"""Validate the current installed image against original first-party bytes before either arm."""
import argparse
import hashlib
from pathlib import Path, PurePosixPath
import stat
import sys
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from boot_ab import image_hash

IMAGE_SHA1 = "2b96f5bd5c79bfe1cc645e70b3e630b5755d9711"
IMAGE_BYTES = 1707857511


def require(value):
    if not value:
        raise ValueError("Invalid or mismatched original API 33 r17 image")


def digest(path, method="sha256"):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, method).hexdigest()


def verify_members(archive, image_dir):
    with zipfile.ZipFile(archive) as package:
        actual = set()
        for member in package.infolist():
            path = PurePosixPath(member.filename)
            mode = member.external_attr >> 16
            require(not path.is_absolute() and ".." not in path.parts and "\\" not in member.filename
                    and path.parts and path.parts[0] == "x86_64" and not stat.S_ISLNK(mode))
            if member.is_dir():
                continue
            name = PurePosixPath(*path.parts[1:]).as_posix()
            require(name not in {"", ".", "package.xml"} and name not in actual)
            actual.add(name)
            installed = image_dir / name
            require(installed.is_file() and installed.resolve() == installed.absolute()
                    and installed.stat().st_size == member.file_size)
            with package.open(member) as stream:
                require(hashlib.file_digest(stream, "sha256").hexdigest() == digest(installed))
        require({"source.properties", "system.img", "ramdisk.img"} <= actual)
        require({path.relative_to(image_dir).as_posix() for path in image_dir.rglob("*") if path.is_file()}
                in (actual, actual | {"package.xml"}))


def main():
    args = argparse.ArgumentParser()
    args.add_argument("archive", type=Path)
    args.add_argument("image", type=Path)
    a = args.parse_args()
    require(a.archive.is_file() and not a.archive.is_symlink() and a.archive.stat().st_size == IMAGE_BYTES
            and digest(a.archive, "sha1") == IMAGE_SHA1)
    image = a.image.absolute()
    require(image.resolve() == image)
    verify_members(a.archive, image)
    print(image_hash(image))


if __name__ == "__main__":
    main()
