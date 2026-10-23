"""Download/extract only the fixed upstream archive, then verify every source byte."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import shutil
import tarfile
import tempfile
import urllib.request
from verify_source import ROOT, sha, source_spec, verify
from build_policy import load_policy, apply_patch, owned_file, digest

MAX_ARCHIVE = 64*1024*1024
MAX_EXPANDED = 128*1024*1024

def extract(archive, destination):
    stage=Path(destination).resolve()
    total=0; seen=set(); prefix=None
    with tarfile.open(archive, 'r:gz') as source:
        for member in source:
            name=PurePosixPath(member.name)
            if name.is_absolute() or PureWindowsPath(member.name).drive or ':' in member.name or '..' in name.parts or '\\' in member.name or not name.parts:
                raise ValueError('Unsafe upstream archive path')
            prefix = name.parts[0] if prefix is None else prefix
            if name.parts[0]!=prefix or not (member.isdir() or member.isfile()):
                raise ValueError('Upstream archive has multiple roots or non-regular files')
            relative=PurePosixPath(*name.parts[1:])
            if str(relative)=='.':
                if not member.isdir(): raise ValueError('Invalid archive root')
                continue
            if str(relative) in seen: raise ValueError('Duplicate upstream archive entry')
            seen.add(str(relative)); target=stage/relative
            if not target.resolve().is_relative_to(stage):raise ValueError('Upstream archive target escapes preparation directory')
            if member.isdir():target.mkdir(parents=True,exist_ok=True);continue
            total+=member.size
            if total>MAX_EXPANDED or member.size>MAX_EXPANDED:raise ValueError('Upstream archive expands beyond its bound')
            target.parent.mkdir(parents=True,exist_ok=True)
            with source.extractfile(member) as data,target.open('wb') as output:shutil.copyfileobj(data,output)
            target.chmod(0o755 if member.mode & 0o111 else 0o644)


def prepare_raw(archive=None, allow_archive_bootstrap=False, separate=False):
    spec=source_spec();owned_root=ROOT.resolve();cache=owned_root/'.upstream'
    if cache.is_symlink() or cache.resolve()!=owned_root/'.upstream':raise ValueError('Unsafe upstream cache directory')
    cache.mkdir(exist_ok=True)
    if not spec['archiveSha256'] and not allow_archive_bootstrap:raise ValueError('Archive hash requires an explicit first CI bootstrap')
    target=cache/('meow-rs-raw' if separate else 'meow-rs')
    if target.is_symlink() or target.parent.resolve()!=cache.resolve() or target.resolve()!=cache/target.name:raise ValueError('Unsafe prepared source directory')
    if target.exists():
        try:
            result=verify(target,spec)
            marker=json.loads((target/'AEROVE_SOURCE_PROVENANCE.json').read_text())
            if (spec['archiveSha256'] is None or marker['archiveSha256']==spec['archiveSha256']) and all(marker.get(k)==v for k,v in result.items()):return target
        except (ValueError,KeyError,OSError):pass
    blob=Path(archive) if archive else cache/(spec['commit']+'.tar.gz')
    if not blob.exists():
        temporary=cache/('download-'+str(os.getpid())+'.tmp')
        try:
            request=urllib.request.Request(spec['archiveUrl'],headers={'User-Agent':'AEROVE-Lite-Meow-Source'})
            with urllib.request.urlopen(request,timeout=60) as response,temporary.open('wb') as output:
                count=0
                while chunk:=response.read(1024*1024):
                    count+=len(chunk)
                    if count>MAX_ARCHIVE:raise ValueError('Upstream archive exceeds its bound')
                    output.write(chunk)
            temporary.replace(blob)
        finally:temporary.unlink(missing_ok=True)
    if blob.stat().st_size>MAX_ARCHIVE or (spec['archiveSha256'] is not None and sha(blob)!=spec['archiveSha256']):
        raise ValueError('Pinned upstream archive SHA256 mismatch')
    try:
        with tempfile.TemporaryDirectory(prefix='prepare-',dir=cache) as stage:
            candidate=Path(stage)/'source';candidate.mkdir();extract(blob,candidate)
            result=verify(candidate,spec);result['archiveSha256']=sha(blob);result['archiveHashBootstrapped']=spec['archiveSha256'] is None
            (candidate/'AEROVE_SOURCE_PROVENANCE.json').write_text(json.dumps(result,indent=2)+'\n')
            if target.exists():
                if cache.is_symlink() or cache.resolve()!=owned_root/'.upstream' or target.is_symlink() or target.resolve()!=cache/('meow-rs-raw' if separate else 'meow-rs'):
                    raise ValueError('Unsafe prepared source cleanup directory')
                shutil.rmtree(target)
            candidate.replace(target)
    except BaseException:
        # A failed owned download must not poison the next cache restore; caller archives are never deleted.
        if archive is None:blob.unlink(missing_ok=True)
        raise
    return target

def prepare(archive=None, allow_archive_bootstrap=False):
    policy=load_policy(ROOT)
    raw=prepare_raw(archive,allow_archive_bootstrap,separate=policy is not None)
    if policy is None:return raw
    spec=source_spec();metadata=policy['overlayMetadata'];raw_result=verify(raw,spec)
    if raw_result['fileManifestSha256']!=metadata['rawFileManifestSha256']:
        raise ValueError('Overlay raw source manifest mismatch')
    expected=dict(spec['files'])
    for name,change in metadata['changes'].items():
        if expected.get(name)!=change['beforeSha256'] or (change['beforeSha256'] is None and name in expected):
            raise ValueError('Overlay changes do not match fixed raw source')
        expected[name]=change['afterSha256']
    if expected!=metadata['files']:raise ValueError('Overlay changes alter undeclared source bytes')
    adapted_spec=dict(spec,files=expected)
    identity=dict(rawFileManifestSha256=raw_result['fileManifestSha256'],overlaySha256=policy['overlaySha256'],patchSha256=metadata['patchSha256'])
    cache=ROOT.resolve()/'.upstream';target=cache/'meow-rs'
    if cache.is_symlink() or cache.resolve()!=ROOT.resolve()/'.upstream' or target.is_symlink() or target.resolve()!=cache/'meow-rs':
        raise ValueError('Unsafe adapted source directory')
    if target.exists():
        try:
            result=verify(target,adapted_spec);marker=json.loads((target/'AEROVE_SOURCE_PROVENANCE.json').read_text())
            if all(marker.get(k)==v for k,v in dict(result,**identity,archiveSha256=spec['archiveSha256']).items()):return target
        except (ValueError,KeyError,OSError):pass
    with tempfile.TemporaryDirectory(prefix='adapt-',dir=cache) as temporary:
        candidate=Path(temporary)/'source';shutil.copytree(raw,candidate)
        apply_patch(candidate,owned_file(ROOT,metadata['patch']).read_bytes(),metadata['changes'])
        result=verify(candidate,adapted_spec)
        if result['fileManifestSha256']!=metadata['adaptedFileManifestSha256']:raise ValueError('Adapted source manifest mismatch')
        result.update(identity,archiveSha256=json.loads((raw/'AEROVE_SOURCE_PROVENANCE.json').read_text())['archiveSha256'],archiveHashBootstrapped=False)
        (candidate/'AEROVE_SOURCE_PROVENANCE.json').write_text(json.dumps(result,indent=2)+'\n')
        if target.exists():
            if cache.is_symlink() or cache.resolve()!=ROOT.resolve()/'.upstream' or target.is_symlink() or target.resolve()!=cache/'meow-rs':
                raise ValueError('Unsafe adapted source cleanup directory')
            shutil.rmtree(target)
        candidate.replace(target)
    return target

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--archive',type=Path);args=parser.parse_args();print(prepare(args.archive))