"""Immutable run107/code14 runtime pins; reuse current26 JNI and three bundled-asset readers."""
import hashlib
import json
import subprocess
from pathlib import Path
import zipfile
from verify_apk import (CORE_JNI_SYMBOLS, CORE_LIBRARY, exported_functions,
                        valid_elf64_shared_object, verify_bundled_script_apk)

SPEC = dict(identity=dict(sourceRevision='e56b8a6299e5bde9e1d7e2199987a1690b2132ed',
    sourceTree='f84296bf554a90edc5ecbed23fbdfeb078b628a2', workflowRun='40142862169', runNumber=107, runAttempt=1),
    artifact=12579159019, zipBytes=34663270,
    zipSha256='ae6e7535f3040ab627348a3bf0a244840a91cd96dea3d2ca9dfccb39b546830d',
    manifestSha256='9bb8bbb7f1c5dcee79048a2705aaa668124b8524f76f8526a8c9221c69087882',
    apks={'app-release.apk':'ed3c1f1f4647909e3814c8e61052fc187dfea198ba6ef1b53b3bd5969eb55339',
          'app-release-androidTest.apk':'b3d49f2f15e0e3bcc0c55d87643617065cb51958c78d4f2ed43ce6370015851b'},
    product=dict(applicationId='dev.aerove.lite',version='0.1.0-beta.14',versionCode='14',
      signerSha256='09f9e021ddac36ead015d27e073b673e79fdf4e02605c402a505781963b1a4d8'))
CORE_SHA='06f781fbc59113dffb9090101d1740caedf7357beb2e8cc689482b99c02ad16d'
SYMBOL_CONTRACT_SHA='402bb616b9a0c26b6b1978acadc329a5aaa7c900f7e72d5a06c9148c9c2af39a'
DEX107_PROOF_SHA='f5f3c599ffe3f65f3f1fca1d25264b424fc01ecaee7afae77ea1e3b200f7fa52'
ASSETS={
 'assets/scripts/smart-config-kit-meow.js':dict(bytes=112115,sha256='217a4d7ecfb56d99cbb79148159ee636b882af5520c4ad7b6c6c870405f938cb'),
 'assets/scripts/smart-config-kit-meow.source.json':dict(bytes=3010,sha256='b1fa17a7312b082343a2b4e77e798fcc8d4d6942c8d140abb68c028388e5f865'),
 'assets/scripts/smart-config-kit-meow.upstream-README.md':dict(bytes=5982,sha256='1d7049f2419ec0c5ad1dc51fd9289a9e7a71bdb0cbd55024e652532470bd6bc0')}

def verify_contract(directory):
    root=Path(directory);apk=root/'apks/app-release.apk'
    digest=lambda raw:hashlib.sha256(raw).hexdigest()
    if digest(apk.read_bytes())!=SPEC['apks']['app-release.apk']:
        raise ValueError('beta14 original APK hash changed')
    symbols=set(CORE_JNI_SYMBOLS)
    if len(symbols)!=26 or digest('\n'.join(sorted(symbols)).encode())!=SYMBOL_CONTRACT_SHA:
        raise ValueError('beta14 requires the fixed26 JNI source contract, never legacy19')
    with zipfile.ZipFile(apk) as archive:
        if archive.testzip() is not None or len(archive.namelist())!=len(set(archive.namelist())):
            raise ValueError('beta14 APK ZIP CRC/duplicate failure')
        core=archive.read('lib/x86_64/'+CORE_LIBRARY)
        actual={name for name in exported_functions(core) if name.startswith('Java_')}
        if digest(core)!=CORE_SHA or not valid_elf64_shared_object(core,62) or actual!=symbols:
            raise ValueError('beta14 actual ELF machine/alignment/hash/exact26 JNI mismatch')
    bundled=verify_bundled_script_apk(apk)
    info=json.loads((root/'BUILD_INFO.json').read_text())
    if bundled['assets']!=ASSETS or bundled!=info.get('bundled_meow_script') or info.get('core_sha256')!=CORE_SHA:
        raise ValueError('beta14 three actual asset/source/BUILD_INFO identities differ')
    return dict(schema=1,source=SPEC['identity'],coreSha256=CORE_SHA,elfMachine=62,jniExports=sorted(actual),
      bundledMeowScript=bundled,dexBridgeProof=dict(sourceRun=107,apkSha256=SPEC['apks']['app-release.apk'],
      originalProofSha256=DEX107_PROOF_SHA,qualification='reuse sealed run107 actual26 DEX descriptors and2 callbacks for identical APK bytes; not a new invocation test'),
      performanceEvidence=False,promotionAccepted=False)


def api_json(route):
    result=subprocess.run(['gh','api',route],check=True,capture_output=True,text=True,timeout=30)
    return json.loads(result.stdout)


def download(route,destination):
    # New owned path only; partial/error are retained on timeout, never silently retried.
    with Path(destination).open('xb') as out:
        subprocess.run(['gh','api',route],stdout=out,check=True,timeout=1200)
