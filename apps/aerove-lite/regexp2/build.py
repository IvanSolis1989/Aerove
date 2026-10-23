"""Pinned regexp2 staging/build. Requires existing Go 1.27.1 and NDK 28.2; no environment installation."""
import argparse, difflib, hashlib, json, os, pathlib, shutil, subprocess, tempfile
ROOT = pathlib.Path(__file__).resolve().parent
PIN = 'github.com/dlclark/regexp2@v1.12.0'
SUM = 'h1:0j4c5qQmnC6XOWNjP3PIXURXN2gWx76rd3KvgdPkCz8='
TARGETS = (('arm64-v8a','arm64','aarch64-linux-android'), ('x86_64','amd64','x86_64-linux-android'))

def input_identity(root=ROOT):
    files = {}
    for path in sorted(root.rglob('*')):
        relative = path.relative_to(root)
        if path.is_file() and '__pycache__' not in relative.parts and path.suffix.lower() != '.pyc':
            files[relative.as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    canonical = json.dumps(files, sort_keys=True, separators=(',', ':')).encode()
    return dict(inputFiles=files, sourceSha256=hashlib.sha256(canonical).hexdigest())

def run(args, cwd, env, capture=False):
    return subprocess.run([str(x) for x in args], cwd=cwd, env=env, check=True,
                          text=True, stdout=subprocess.PIPE if capture else None).stdout

def apply_exact_patch(text, patch):
    lines = text.splitlines(keepends=True)
    output, cursor = [], 0
    patch_lines = patch.splitlines(keepends=True)
    i = 2
    while i < len(patch_lines):
        header = patch_lines[i]
        if not header.startswith('@@ '): raise RuntimeError('Unexpected patch header')
        start = int(header.split()[1].split(',')[0][1:]) - 1
        output.extend(lines[cursor:start]); cursor = start; i += 1
        while i < len(patch_lines) and not patch_lines[i].startswith('@@ '):
            line = patch_lines[i]; kind, value = line[0], line[1:]
            if kind in (' ', '-'):
                if cursor >= len(lines) or lines[cursor] != value: raise RuntimeError('Patch context mismatch')
                cursor += 1
            if kind in (' ', '+'): output.append(value)
            if kind not in (' ', '+', '-'): raise RuntimeError('Unexpected patch line')
            i += 1
    output.extend(lines[cursor:]); return ''.join(output)

def selected_targets(abi):
    return [target for target in TARGETS if abi is None or target[0] == abi]

def parse_args(argv=None):
    parser=argparse.ArgumentParser()
    parser.add_argument('--go', required=True);parser.add_argument('--ndk')
    parser.add_argument('--staging', required=True);parser.add_argument('--out')
    parser.add_argument('--test-only', action='store_true')
    parser.add_argument('--abi', choices=[target[0] for target in TARGETS], help='Build only this ABI; default: both supported ABIs')
    return parser.parse_args(argv)

def main():
    a=parse_args();identity=input_identity();env=os.environ.copy();env['GOTOOLCHAIN']='local';env['GOSUMDB']='off';env.pop('GOOS',None);env.pop('GOARCH',None)
    version=run([a.go,'version'],ROOT,env,True).strip()
    if not version.startswith('go version go1.27.1 '):raise RuntimeError('Go 1.27.1 is required: '+version)
    module=json.loads(run([a.go,'mod','download','-json',PIN],ROOT,env,True))
    if module.get('Sum') != SUM:raise RuntimeError('Upstream module checksum mismatch')
    run([a.go,'mod','verify'],ROOT,env)
    for dependency in json.loads((ROOT/'dependencies.json').read_text()):
        actual=json.loads(run([a.go,'mod','download','-json',dependency['Path']+'@'+dependency['Version']],ROOT,env,True))
        for key in ('Sum','GoModSum'):
            if actual.get(key)!=dependency.get(key):raise RuntimeError('Dependency checksum mismatch: '+dependency['Path'])
    source=pathlib.Path(module['Dir']); expected=json.loads((ROOT/'upstream-sha256.json').read_text())
    actual={p.relative_to(source).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in source.rglob('*') if p.is_file()}
    if actual != expected:raise RuntimeError('Pinned upstream source manifest mismatch')
    staging=pathlib.Path(a.staging).resolve()
    # Never recursively overwrite an existing path or caller's source checkout.
    staging.mkdir(parents=True,exist_ok=False)
    upstream=staging/'upstream';shutil.copytree(source,upstream)
    # copytree preserves the module cache's read-only directory mode.
    # Only our newly created staging root needs owner write for the added test.
    upstream.chmod(upstream.stat().st_mode | 0o200)
    patched=json.loads((ROOT/'patches/adapted-sha256.json').read_text())
    for name in ('regexp.go','runner.go','fastclock.go'):
        target=upstream/name;original=target.read_text()
        patch=(ROOT/'patches'/(name+'.patch')).read_text()
        adapted=apply_exact_patch(original,patch)
        if ''.join(difflib.unified_diff(original.splitlines(True),adapted.splitlines(True),fromfile='a/'+name,tofile='b/'+name))!=patch:raise RuntimeError('Non-exact patch')
        target.chmod(0o600);target.write_text(adapted,encoding='utf-8',newline='\n')
        if hashlib.sha256(target.read_bytes()).hexdigest()!=patched[name]:raise RuntimeError('Adapted source checksum mismatch')
    clock=json.loads((ROOT/'patches/clock-stop-source.json').read_text())
    if clock['upstreamSha256']!=expected['fastclock.go'] or clock['adaptedSha256']!=patched['fastclock.go']:raise RuntimeError('Clock source identity mismatch')
    regression=ROOT/'patches/fastclock_stop_test.go'
    if hashlib.sha256(regression.read_bytes()).hexdigest()!=clock['regressionTestSha256']:raise RuntimeError('Clock regression identity mismatch')
    shutil.copyfile(regression,upstream/'aerove_fastclock_stop_test.go')
    for name in ('go.mod','go.sum'):shutil.copyfile(ROOT/name,staging/name)
    for name in ('service','cmd','agebridge'):shutil.copytree(ROOT/name,staging/name)
    with (staging/'go.mod').open('a') as f:f.write('\nreplace github.com/dlclark/regexp2 => ./upstream\n')
    env['CGO_ENABLED']='0'
    run([a.go,'test','-count=1','./service','./agebridge'],staging,env)
    run([a.go,'test','-count=1','-short','./...'],upstream,env)
    if a.test_only:return
    if not a.ndk or not a.out:raise RuntimeError('--ndk and --out required for Android build')
    ndk=pathlib.Path(a.ndk).resolve();props=(ndk/'source.properties').read_text()
    if '28.2.' not in props:raise RuntimeError('NDK 28.2 is required')
    host='windows-x86_64' if os.name=='nt' else 'linux-x86_64'
    llvm=ndk/'toolchains/llvm/prebuilt'/host/'bin';output=pathlib.Path(a.out).resolve()
    receipts=[]
    targets=selected_targets(a.abi)
    for abi,arch,triple in targets:
        dest=output/abi;dest.mkdir(parents=True,exist_ok=True)
        cc=llvm/(triple+'33-clang'+('.cmd' if os.name=='nt' else ''))
        env['CGO_ENABLED']='1';env['GOOS']='android';env['GOARCH']=arch;env['CC']=str(cc)
        run([a.go,'build','-trimpath','-buildmode=c-shared','-ldflags=-s -w -extldflags=-Wl,-z,max-page-size=16384','-o',dest/'libaerove_filter_helper.so','./cmd/cshared'],staging,env)
        # Invoke clang directly: no extra CMake installation/build system required.
        run([cc,'-shared','-fPIC','-O2','-Werror','-Wl,-z,max-page-size=16384',ROOT/'jni/filter_bridge.c','-ldl','-o',dest/'libaerove_filter_jni.so'],ROOT,env)
        for lib in (dest/'libaerove_filter_helper.so', dest/'libaerove_filter_jni.so'):
            receipts.append(dict(abi=abi,path=str(lib),sha256=hashlib.sha256(lib.read_bytes()).hexdigest(),bytes=lib.stat().st_size))
    if input_identity() != identity:raise RuntimeError('Helper source inputs changed during build')
    (staging/'build-receipt.json').write_text(json.dumps(dict(go=version,ndk=props,abis=[target[0] for target in targets],outputs=receipts,**identity),indent=2)+'\n')
if __name__=='__main__':main()
