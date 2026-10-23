"""Independent Meow bridge preflight: real lock, host representative chain, then Android link."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tomllib
from prepare import prepare
from build_policy import load_policy, digest
from verify_source import ROOT, sha, verify

TARGETS={'aarch64-linux-android':('arm64-v8a',183),'x86_64-linux-android':('x86_64',62)}

def run(command, environment, capture=False):
    return subprocess.run(command,cwd=ROOT,env=environment,check=True,text=True,stdout=subprocess.PIPE if capture else None).stdout

def android_environment(ndk,target,environment):
    ndk=Path(ndk).resolve();abi,_=TARGETS[target]
    if not re.search(r'Pkg.Revision\s*=\s*28\.2\.13676358\s*$',(ndk/'source.properties').read_text(),re.MULTILINE):
        raise ValueError('Exact NDK 28.2.13676358 required')
    compiler=ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin'
    for name in (target+'33-clang',target+'33-clang++','llvm-ar','llvm-readelf'):
        if not (compiler/name).is_file():raise ValueError('Required Android API33 tool missing')
    toolchain=ROOT/'build'/('cmake-'+target+'.cmake');toolchain.parent.mkdir(exist_ok=True)
    toolchain.write_text(f'set(ANDROID_ABI {abi})\nset(ANDROID_PLATFORM android-33)\ninclude("{(ndk/"build/cmake/android.toolchain.cmake").as_posix()}")\n')
    key=target.replace('-','_');result=dict(environment)
    result.update({f'CARGO_TARGET_{key.upper()}_LINKER':str(compiler/(target+'33-clang')),
        f'CARGO_TARGET_{key.upper()}_RUSTFLAGS':'-C link-arg=-Wl,-z,max-page-size=16384',
        f'CC_{key}':str(compiler/(target+'33-clang')),f'CXX_{key}':str(compiler/(target+'33-clang++')),
        f'AR_{key}':str(compiler/'llvm-ar'),f'CMAKE_TOOLCHAIN_FILE_{target}':str(toolchain),
        'ANDROID_NDK_HOME':str(ndk),'ANDROID_NDK_ROOT':str(ndk),'BORING_BSSL_RUST_CPPLIB':'c++_static','CMAKE_GENERATOR':'Ninja'})
    return result,compiler/'llvm-readelf'

def verify_elf(path,machine,readelf):
    header=Path(path).read_bytes()[:20]
    if len(header)!=20 or header[:6]!=b'\x7fELF\x02\x01' or int.from_bytes(header[16:18],'little')!=3 or int.from_bytes(header[18:20],'little')!=machine:
        raise ValueError('Meow bridge ELF ABI mismatch')
    output=subprocess.check_output([str(readelf),'-lW',str(path)],text=True)
    loads=[line.split() for line in output.splitlines() if line.strip().startswith('LOAD ')]
    if not loads or any(int(row[-1],16)<16384 for row in loads):raise ValueError('Meow bridge lacks 16KiB PT_LOAD alignment')
    dynamic=subprocess.check_output([str(readelf),'-dW',str(path)],text=True)
    if 'libc++_shared.so' in dynamic:raise ValueError('Bridge requires an unpackaged shared C++ runtime')
    return output+'\n'+dynamic

def resolve_graph(environment, build, receipt):
    lock=ROOT/'Cargo.lock'
    previous=lock.read_bytes();receipt['metadataInputLockSha256']=digest(previous)
    if receipt.get('resolveLockRequested') and receipt['metadataInputLockSha256']!=receipt['lockSha256Before']:
        raise ValueError('Previous lock changed before explicit resolution')
    (build/'lock-before-resolution.lock').write_bytes(previous)
    receipt['metadataCommand']=['cargo','metadata','--format-version','1']
    result=subprocess.run(receipt['metadataCommand'],cwd=ROOT,env=environment,capture_output=True)
    (build/'metadata.stdout.json').write_bytes(result.stdout)
    (build/'metadata.stderr.log').write_bytes(result.stderr)
    receipt['metadataExit']=result.returncode
    if result.returncode:raise subprocess.CalledProcessError(result.returncode,receipt['metadataCommand'])
    if sha(ROOT/'Cargo.toml')!=receipt['bridgeManifestSha256']:raise ValueError('Manifest changed during explicit graph resolution')


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--archive',type=Path)
    modes=parser.add_mutually_exclusive_group();modes.add_argument('--initialize-lock',action='store_true');modes.add_argument('--resolve-lock',action='store_true')
    parser.add_argument('--target',choices=TARGETS);parser.add_argument('--ndk',type=Path);args=parser.parse_args()
    policy=load_policy(ROOT)
    if args.resolve_lock and (policy is None or not policy['resolveLock']):raise ValueError('Lock resolution requires an explicit approved build policy')
    if args.target and (args.initialize_lock or args.resolve_lock or policy is not None and policy['resolveLock'] or not args.ndk or sys.platform!='linux'):
        raise ValueError('Android link requires committed lock/policy and Linux NDK; resolution is host-only')
    upstream=prepare(args.archive,allow_archive_bootstrap=args.initialize_lock);manifest=tomllib.loads((ROOT/'Cargo.toml').read_text())
    if manifest['package']['name']!='aerove-meow-core' or manifest['lib']['name']!='aerove_meow_core':raise ValueError('Unexpected bridge crate identity')
    for name,dependency in manifest['dependencies'].items():
        if name.startswith('meow-') and (ROOT/dependency['path']).resolve()!=upstream/'crates'/name:raise ValueError('Meow dependency bypasses fixed source')
    environment=dict(os.environ,CARGO_TARGET_DIR=str(ROOT/'target'),RUSTUP_TOOLCHAIN='1.98.1')
    version=run(['rustc','--version','--verbose'],environment,True)
    if not version.startswith('rustc 1.98.1 '):raise ValueError('Pinned Rust 1.98.1 required')
    lock=ROOT/'Cargo.lock';targets=policy['targets'] if policy else ['lib','proxy_runtime']
    receipt=dict(schema=1,source=json.loads((upstream/'AEROVE_SOURCE_PROVENANCE.json').read_text()),declaredSourceSpecSha256=sha(ROOT/'source.json'),bridgeManifestSha256=sha(ROOT/'Cargo.toml'),toolchain=version,
        workflowSourceSha=os.environ.get('GITHUB_SHA'),workflowRun=os.environ.get('GITHUB_RUN_ID'),workflowAttempt=os.environ.get('GITHUB_RUN_ATTEMPT'),
        bootstrapRequested=args.initialize_lock,resolveLockRequested=args.resolve_lock,buildPolicySha256=policy['policySha256'] if policy else None,
        lockPresentBefore=lock.is_file(),lockSha256Before=sha(lock) if lock.is_file() else None,
        representativeTargets=targets,androidTarget=args.target,commandExit=None,acceptedLock=False)
    command=['cargo','test','--locked']
    for target in targets:command+=['--lib'] if target=='lib' else ['--test',target]
    command+=['--','--test-threads=1','--nocapture']
    build=ROOT/'build';build.mkdir(exist_ok=True);resolved=None
    try:
        if args.initialize_lock:
            if lock.exists():raise ValueError('Bootstrap is only permitted when the bridge lock is absent')
            shutil.copyfile(upstream/'Cargo.lock',lock)
            resolve_graph(environment,build,receipt)
        elif args.resolve_lock:
            if not lock.is_file() or receipt['lockSha256Before']!=policy['expectedLockBeforeSha256']:raise ValueError('Previous lock identity mismatch')
            resolve_graph(environment,build,receipt)
        elif not lock.is_file():raise ValueError('Bridge Cargo.lock missing; explicit CI bootstrap is required')
        resolved=sha(lock);receipt['resolvedLockSha256']=resolved
        if args.target:
            environment,readelf=android_environment(args.ndk,args.target,environment)
            receipt['ndkSourceProperties']=(args.ndk/'source.properties').read_text()
            command=['cargo','build','--locked','--release','--target',args.target]
        run(command,environment)
        if sha(lock)!=resolved:raise ValueError('Locked build mutated Cargo.lock')
        if args.target:
            abi,machine=TARGETS[args.target];library=ROOT/'target'/args.target/'release/libaerove_meow_core.so'
            output=verify_elf(library,machine,readelf);(build/(abi+'-readelf.txt')).write_text(output)
            destination=build/abi;destination.mkdir(exist_ok=True);shutil.copyfile(library,destination/library.name)
            receipt['output']=dict(abi=abi,file=str(destination/library.name),sha256=sha(library),bytes=library.stat().st_size,minPageAlignment=16384)
        receipt['commandExit']=0;receipt['acceptedLock']=True
    except BaseException as error:
        receipt['commandExit']=getattr(error,'returncode',1);receipt['failureType']=type(error).__name__;raise
    finally:
        receipt['command']=command;receipt['lockSha256After']=sha(lock) if lock.exists() else None
        receipt['lockUnchangedDuringLockedCommand']=resolved is not None and receipt['lockSha256After']==resolved
        (build/('host-receipt.json' if not args.target else TARGETS[args.target][0]+'-receipt.json')).write_text(json.dumps(receipt,indent=2)+'\n')
if __name__=='__main__':main()
