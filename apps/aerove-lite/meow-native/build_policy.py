"""Explicit source overlays and lock-resolution requests; no implicit unlocks."""
import hashlib
import json
from pathlib import Path, PurePosixPath, PureWindowsPath
import re

HASH = re.compile('[0-9a-f]{64}')

def digest(data):
    return hashlib.sha256(data).hexdigest()

def owned_file(root, name):
    path=PurePosixPath(name)
    if not name or path.is_absolute() or PureWindowsPath(name).drive or ':' in name or '\\' in name or '..' in path.parts:
        raise ValueError('Unsafe policy or patch path')
    target=Path(root)/path
    if not target.resolve().is_relative_to(Path(root).resolve()) or target.is_symlink():
        raise ValueError('Policy or patch path escapes owner')
    return target

def load_policy(root):
    file=Path(root)/'build-policy.json'
    if not file.exists():return None
    raw=file.read_bytes();policy=json.loads(raw)
    if set(policy)!={'schema','manifestSha256','resolveLock','expectedLockBeforeSha256','targets','overlay','overlaySha256'} or type(policy['schema']) is not int or policy['schema']!=1:
        raise ValueError('Unknown build policy schema or fields')
    if type(policy['resolveLock']) is not bool or any(not isinstance(policy[k],str) or not HASH.fullmatch(policy[k]) for k in ['manifestSha256','expectedLockBeforeSha256','overlaySha256']):
        raise ValueError('Invalid explicit build policy identity')
    targets=policy['targets']
    if not isinstance(targets,list) or not targets or targets[0]!='lib' or len(set(targets))!=len(targets) or any(not isinstance(t,str) or not re.fullmatch('[a-z][a-z0-9_]*',t) for t in targets):
        raise ValueError('Invalid representative test targets')
    if digest((Path(root)/'Cargo.toml').read_bytes())!=policy['manifestSha256']:
        raise ValueError('Cargo manifest does not match approved build policy')
    if policy['resolveLock'] and digest((Path(root)/'Cargo.lock').read_bytes())!=policy['expectedLockBeforeSha256']:
        raise ValueError('Explicit lock resolution requires the approved previous lock')
    metadata=owned_file(root,policy['overlay']).read_bytes()
    if digest(metadata)!=policy['overlaySha256']:raise ValueError('Overlay metadata identity mismatch')
    overlay=json.loads(metadata)
    if set(overlay)!={'schema','patch','patchSha256','rawFileManifestSha256','adaptedFileManifestSha256','changes','files'} or type(overlay['schema']) is not int or overlay['schema']!=1:
        raise ValueError('Unknown overlay schema or fields')
    if any(not isinstance(overlay[k],str) or not HASH.fullmatch(overlay[k]) for k in ['patchSha256','rawFileManifestSha256','adaptedFileManifestSha256']):
        raise ValueError('Invalid overlay identity')
    if digest(owned_file(root,overlay['patch']).read_bytes())!=overlay['patchSha256']:
        raise ValueError('Overlay patch identity mismatch')
    if not isinstance(overlay['files'],dict) or not isinstance(overlay['changes'],dict):raise ValueError('Invalid overlay file tables')
    for name,value in overlay['files'].items():
        owned_file(root,name)
        if not isinstance(value,str) or not HASH.fullmatch(value):raise ValueError('Invalid adapted source hash')
    for name,value in overlay['changes'].items():
        owned_file(root,name)
        if set(value)!={'beforeSha256','afterSha256'} or value['afterSha256']!=overlay['files'].get(name):raise ValueError('Invalid overlay change table')
        if value['beforeSha256'] is not None and (not isinstance(value['beforeSha256'],str) or not HASH.fullmatch(value['beforeSha256'])):raise ValueError('Invalid previous source hash')
    actual=digest(json.dumps(overlay['files'],sort_keys=True,separators=(',',':')).encode())
    if actual!=overlay['adaptedFileManifestSha256']:raise ValueError('Adapted file manifest identity mismatch')
    return dict(policy,policySha256=digest(raw),overlayMetadata=overlay)

def decide_plan(lock_present, archive_hash, event, phase, initialize, resolve_requested=False):
    pinned=isinstance(archive_hash,str) and HASH.fullmatch(archive_hash) is not None
    if event not in ('push','workflow_dispatch') or phase not in ('','host','android'):
        raise ValueError('Unsupported explicit preflight request')
    if resolve_requested:
        if not lock_present or not pinned or initialize or phase=='android':
            raise ValueError('Explicit graph resolution is host-only and requires committed previous lock/source')
        return dict(bootstrap=False,resolve=True,android=False,proof='resolve-host-only')
    if lock_present and pinned:
        if initialize:raise ValueError('Bootstrap cannot replace an existing committed lock')
        android=event=='push' or phase=='android'
        return dict(bootstrap=False,resolve=False,android=android,proof='host-android' if android else 'host-only')
    if lock_present or archive_hash is not None:
        raise ValueError('Commit both the resolved lock and exact archive hash before locked preflight')
    if phase=='android':raise ValueError('Android requires a committed lock and archive hash')
    if event!='push' and not initialize:raise ValueError('First manual host bootstrap requires initialize_lock')
    return dict(bootstrap=True,resolve=False,android=False,proof='bootstrap-host-only')

def apply_patch(directory, patch, changes):
    """Exact byte replay, including checked /dev/null additions; no fuzz or normalization."""
    lines=patch.splitlines(keepends=True);position=0;pending={}
    while position<len(lines):
        header=lines[position];new=header==b'--- /dev/null\n'
        if not new and not header.startswith(b'--- a/'):raise ValueError('Invalid patch header')
        position+=1
        if position>=len(lines) or not lines[position].startswith(b'+++ b/'):raise ValueError('Invalid patch destination')
        name=lines[position][6:].removesuffix(b'\n').decode('utf-8');target=owned_file(directory,name)
        if not new and header!=b'--- a/'+name.encode()+b'\n':raise ValueError('Patch rename is not permitted')
        if name in pending or name not in changes:raise ValueError('Duplicate or undeclared patch file')
        expected=changes[name]
        if new:
            if target.exists() or expected['beforeSha256'] is not None:raise ValueError('New patch file must be absent')
            original=[]
        else:
            content=target.read_bytes()
            if digest(content)!=expected['beforeSha256']:raise ValueError('Patch previous source identity mismatch')
            original=content.splitlines(keepends=True)
        position+=1;result=[];consumed=0;hunks=0
        while position<len(lines) and lines[position].startswith(b'@@ '):
            match=re.fullmatch(rb'@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@\n',lines[position])
            if not match:raise ValueError('Invalid exact patch hunk')
            oldcount=int(match[2] or b'1');newcount=int(match[4] or b'1')
            start=int(match[1])-(1 if oldcount else 0);newstart=int(match[3])-(1 if newcount else 0)
            if start<consumed or start>len(original):raise ValueError('Overlapping or out of range patch hunk')
            if new and (start!=0 or oldcount!=0 or hunks):raise ValueError('New file requires one empty-source hunk')
            result.extend(original[consumed:start]);consumed=start
            if len(result)!=newstart:raise ValueError('Patch destination line offset mismatch')
            position+=1;removed=added=0;hunks+=1
            while removed<oldcount or added<newcount:
                if position>=len(lines) or lines[position][:1] not in (b' ',b'+',b'-'):raise ValueError('Incomplete patch hunk')
                line=lines[position];position+=1
                if line[:1] in (b' ',b'-'):
                    if consumed>=len(original) or original[consumed]!=line[1:]:raise ValueError('Patch context mismatch')
                    consumed+=1;removed+=1
                if line[:1] in (b' ',b'+'):result.append(line[1:]);added+=1
                if removed>oldcount or added>newcount:raise ValueError('Incorrect patch hunk counts')
        if not hunks:raise ValueError('Patch file has no hunks')
        result.extend(original[consumed:]);data=b''.join(result)
        if digest(data)!=expected['afterSha256']:raise ValueError('Patch resulting source identity mismatch')
        pending[name]=(target,data)
    if set(pending)!=set(changes):raise ValueError('Patch does not cover exact approved changes')
    for target,data in pending.values():target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
