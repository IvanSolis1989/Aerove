"""Strict admission of the complete fixed Meow source tree; does not compile code."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent

def sha(path):
    with Path(path).open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()

def source_spec():
    return json.loads((ROOT / 'source.json').read_text())

def verify(directory, spec=None):
    spec = source_spec() if spec is None else spec
    root = Path(directory)
    actual = {}
    for path in root.rglob('*'):
        if path.is_symlink(): raise ValueError('Upstream source contains a symlink')
        if path.is_file() and path.relative_to(root).as_posix() != 'AEROVE_SOURCE_PROVENANCE.json':
            actual[path.relative_to(root).as_posix()] = sha(path)
    if actual != spec['files']:
        raise ValueError('Pinned Meow file manifest mismatch')
    if sha(root / 'Cargo.lock') != spec['upstreamLockSha256'] or sha(root / 'LICENSE') != spec['licenseSha256']:
        raise ValueError('Pinned Meow lock/license mismatch')
    return dict(commit=spec['commit'], tag=spec['tag'], repository=spec['repository'],
                fileManifestSha256=hashlib.sha256(json.dumps(actual,sort_keys=True,separators=(',',':')).encode()).hexdigest(),
                upstreamLockSha256=sha(root/'Cargo.lock'), licenseSha256=sha(root/'LICENSE'), files=len(actual))

def main():
    parser=argparse.ArgumentParser();parser.add_argument('directory',type=Path);args=parser.parse_args()
    print(json.dumps(verify(args.directory),indent=2))
if __name__=='__main__':main()