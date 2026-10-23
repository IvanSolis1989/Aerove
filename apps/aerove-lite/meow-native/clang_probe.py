"""Read back a real CI host libclang before Cargo; never installs a toolchain."""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import sys

class CXString(ctypes.Structure):
    _fields_=[('data',ctypes.c_void_p),('private_flags',ctypes.c_uint)]

REQUIRED_EXPORTS=['clang_getClangVersion','clang_getCString','clang_disposeString','clang_createIndex','clang_parseTranslationUnit2','clang_disposeTranslationUnit']

def candidates(environment):
    explicit=environment.get('LIBCLANG_PATH')
    if explicit:
        path=Path(explicit)
        directories=[path] if path.is_dir() else []
        files=[path] if path.is_file() else []
    else:
        directories=[];files=[]
        for value in [environment.get('LD_LIBRARY_PATH',''),environment.get('LIBRARY_PATH','')]:
            directories.extend(Path(p) for p in value.split(os.pathsep) if p)
        for pattern in ['/usr/lib/llvm-*/lib','/usr/lib/x86_64-linux-gnu','/usr/lib64','/usr/local/lib','/usr/local/llvm*/lib','/usr/local/llvm*/lib64']:
            directories.extend(Path('/').glob(pattern.lstrip('/')))
    for directory in directories:
        for pattern in ['libclang.so*','libclang-[0-9]*.so*']:files.extend(directory.glob(pattern))
    unique={p.resolve() for p in files if p.is_file() and re.fullmatch(r'libclang(?:-[0-9.]+)?\.so(?:\.[0-9.]+)?',p.name)}
    def version_key(path):
        match=re.search(r'(?:llvm-|libclang-|libclang\.so\.)([0-9]+)',str(path))
        return (int(match[1]) if match else 0,str(path))
    return sorted(unique,key=version_key,reverse=True)

def load_library(path):
    with path.open('rb') as file:header=file.read(20)
    if len(header)!=20 or header[:6]!=b'\x7fELF\x02\x01' or int.from_bytes(header[16:18],'little')!=3 or int.from_bytes(header[18:20],'little')!=62:
        raise ValueError('libclang must be a Linux x86_64 ELF64 shared library')
    library=ctypes.CDLL(str(path))
    for name in REQUIRED_EXPORTS:
        if not hasattr(library,name):raise ValueError('libclang C API export missing: '+name)
    library.clang_getClangVersion.argtypes=[];library.clang_getClangVersion.restype=CXString
    library.clang_getCString.argtypes=[CXString];library.clang_getCString.restype=ctypes.c_char_p
    library.clang_disposeString.argtypes=[CXString];library.clang_disposeString.restype=None
    value=library.clang_getClangVersion()
    try:
        raw=library.clang_getCString(value)
        if raw is None:raise ValueError('libclang returned no version')
        version=raw.decode('utf-8')
    finally:library.clang_disposeString(value)
    match=re.search(r'(\d+)\.\d+',version)
    if not match or int(match[1])<9:raise ValueError('bindgen requires a readable libclang version >=9')
    with path.open('rb') as file:checksum=hashlib.file_digest(file,'sha256').hexdigest()
    return dict(path=str(path.resolve()),version=version,sha256=checksum,bytes=path.stat().st_size,ctypesLoaded=True,ELFMachine=62,exports=REQUIRED_EXPORTS)

def probe(output,environment=None):
    environment=dict(os.environ if environment is None else environment)
    receipt=dict(schema=1,status='failure',workflowSourceSha=environment.get('GITHUB_SHA'),workflowRun=environment.get('GITHUB_RUN_ID'),workflowAttempt=environment.get('GITHUB_RUN_ATTEMPT'),python=sys.version,ctypesModule=ctypes.__file__,attempts=[])
    output=Path(output);output.parent.mkdir(parents=True,exist_ok=True)
    try:
        if sys.platform!='linux' or platform.machine()!='x86_64':raise ValueError('CI libclang gate requires the declared Linux x86_64 host')
        for path in candidates(environment):
            try:
                receipt['library']=load_library(path);receipt['status']='success'
                return receipt
            except (OSError,ValueError,AttributeError) as error:
                receipt['attempts'].append(dict(path=str(path),errorType=type(error).__name__,error=str(error)))
        raise ValueError('No existing host libclang passed the actual ELF/load/version gate; no tools installed')
    except BaseException as error:
        receipt['failureType']=type(error).__name__;receipt['failure']=str(error);raise
    finally:output.write_text(json.dumps(receipt,indent=2)+'\n')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    print(json.dumps(probe(args.output),indent=2))
