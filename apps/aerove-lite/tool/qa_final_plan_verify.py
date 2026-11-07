"""Verify private synthetic input bytes. Never rewrite paths or claim host/Android acceptance."""
import hashlib,json
from pathlib import Path

def require(condition):
    if not condition:
        raise ValueError('Synthetic final-plan packet validation failed')

def verify(directory, expected):
    root=Path(directory);manifest=json.loads((root/'capture-manifest.json').read_text())
    require(root.is_dir() and not root.is_symlink())
    require(manifest['schema']==1 and manifest['captureCompleted'] is True and manifest['accepted'] is False)
    require(manifest['original312Input'] is False and manifest['hostReplayReady'] is False)
    identity=manifest['identity']
    require(identity['hostOwnerSha256']==expected['qaFinalPlanOwnerSha256'] and identity['syntheticQA'] is True)
    require(identity['selector']=='freshDefaultFinalPlanCapture' and identity['original312Input'] is False)
    for key in ('qaToken','source','tree','apkSha256','workflow','runAttempt','meowOverlaySha256','meowFileManifestSha256'):
        require(str(identity[key])==str(expected[key]))
    require(identity['signerSha256']==expected['qaFinalPlanSignerSha256'])
    require(str(identity['executorRun'])==str(expected['qaExecutorRun']) and str(identity['executorAttempt'])==str(expected['qaExecutorAttempt']))
    paths=set()
    for row in manifest['files']:
        name=row['path'];p=root/name
        require(isinstance(name,str) and name not in paths and name and not Path(name).is_absolute() and all(part not in ('','.','..') for part in name.split('/')) and '\\' not in name and ':' not in name)
        require(p.resolve().is_relative_to(root.resolve()))
        require(p.is_file() and not p.is_symlink() and p.stat().st_size==row['bytes'])
        require(hashlib.sha256(p.read_bytes()).hexdigest()==row['sha256']);paths.add(name)
    require(not any(p.is_symlink() for p in root.rglob('*')))
    actual={p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file()}
    require(actual==paths|{'capture-manifest.json'})
    plan=json.loads((root/'final-plan.json').read_text())
    require((root/'engine.yaml').read_bytes()==plan['engineYaml'].encode())
    require((root/'host-policy.yaml').read_bytes()==plan['hostPolicyYaml'].encode())
    require((root/'inbounds.yaml').read_bytes()==plan['inbounds']['configYaml'].encode())
    require((root/'applied-engine.yaml').read_bytes()==(root/'engine.yaml').read_bytes())
    dirs={p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_dir() and p.relative_to(root).as_posix().startswith('closure/')}
    require(dirs==set(manifest['capturedDirectories']))
    require(hashlib.sha256((root/'closure/read-root/relocation-receipt.json').read_bytes()).hexdigest()==manifest['relocationReceiptSha256'])
    require(manifest['missing']==[] and manifest['transformsApplied']==[])
    return dict(manifestSha256=hashlib.sha256((root/'capture-manifest.json').read_bytes()).hexdigest(),
        files=len(paths),original312Input=False,inputCaptureOnly=True,hostReplayReady=False)
