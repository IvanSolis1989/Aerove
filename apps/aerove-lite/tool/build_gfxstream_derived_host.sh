#!/usr/bin/env bash
# Build one source-derived host module; never modify or execute the base SDK/App.
# Fixed Apache-2.0 gfxstream and BSD-3-Clause GoogleTest retain their upstream notices.
set -euo pipefail
umask 022
ulimit -c 0
if [[ $# != 2 || $(uname -s) != Linux ]]; then
  echo 'Usage (Linux): bash build_gfxstream_derived_host.sh BASE_LIBGFXSTREAM_SO NEW_OUTPUT_DIRECTORY' >&2
  exit 2
fi
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
BASE_LIB=$(realpath -e -- "$1")
OUTPUT=$(realpath -m -- "$2")
[[ -f "$BASE_LIB" && ! -e "$OUTPUT" ]] || { echo 'Require an existing base library and a new output directory' >&2; exit 2; }
BASE_DIR=$(dirname -- "$BASE_LIB")
case "$OUTPUT/" in "$BASE_DIR/"*) echo 'Output must be outside the original SDK library directory' >&2; exit 2 ;; esac
for tool in git readelf nm sha256sum python3 timeout xvfb-run ldd cmp; do
  command -v "$tool" >/dev/null || { echo "Missing build dependency: $tool" >&2; exit 2; }
done
SOURCE=49ee51f297118cd2e98c5f4867df556e8966562a
GTEST=013ef6db94e3677b24b8e4c9fbdf1708ecea516b
SOURCE_DIR="$OUTPUT/aosp/hardware/google/gfxstream"
AOSP_DIR="$OUTPUT/aosp"
SOURCE_PINS="$SCRIPT_DIR/fixtures/aemu-integration-sources.json"
BASE_SHA=67fa485ebe97369429e283fbefb6db9046024f7264102de60c4bfc70d2628f7e
BASE_BUILD_ID=49b9fc8e45122f00b3970a89a72eb26101bcffe4
PRODUCTION=host/gl/glestranslator/gles_v2/gles_v30_imp.cpp
TEST_SOURCE=host/tests/DefaultFramebufferBlit_unittest.cpp
X11_TEST_SOURCE=host/tests/TextureDraw_unittest.cpp
PATCH="$SCRIPT_DIR/fixtures/gfxstream-zero-attachments.patch"
TEST_PATCH="$SCRIPT_DIR/fixtures/gfxstream-zero-attachments-tests.patch"
X11_TEST_PATCH="$SCRIPT_DIR/fixtures/gfxstream-x11-gtest.patch"
PARSER_INCLUDE="$SCRIPT_DIR/fixtures/angle-shader-translator"
PARSER_HEADER="$PARSER_INCLUDE/ShaderTranslator.h"
PARSER_SOURCE="$PARSER_INCLUDE/SOURCE.json"
PARSER_LIB="$BASE_DIR/libshadertranslator.so"
PARSER_GIT_BLOB=3b3174adf5d912a37780b69876f0de449bdc2be4
PARSER_SHA=a8832f3f2d8f900b77d2e894b659c71ef5093afd514543bba3b398baa450804e
PARSER_REFERENCE_BLOB=bf6f43c6b36c4c4b2651ac9e74db4b58035af270
PARSER_OBJCOPY_REVISION=6599d0b82da42c78c5f30e73ba71aa3db907e5aa
PARSER_OBJCOPY_BLOB=e97fdf72793217f6114f85fbdeafd30c71e65463
check_sha() { printf '%s  %s\n' "$2" "$1" | sha256sum --check --status; }
check_sha "$BASE_LIB" "$BASE_SHA"
[[ $(readelf -n "$BASE_LIB" | sed -n 's/.*Build ID: //p') == "$BASE_BUILD_ID" ]]
check_sha "$PATCH" a4d633095cb249ea9d7cada1127aab0692c84c5f9950e5926ebabe6a13ee9180
check_sha "$TEST_PATCH" 4e33b60eaf701019e67514aeedbf976e0b7591bf76518ca918aaf1d2884ac38e
check_sha "$X11_TEST_PATCH" 0e061d4f2bea1de57416600a7552e0f487c44e6606f2b68054d3078d7ffd0a43
check_sha "$PARSER_HEADER" 979138ea0b60d23ee604b065743ebd9a2a32fbe76bee4e68081e3c73975b673a
check_sha "$PARSER_INCLUDE/LICENSE" 54aff7276217df9f6b5181613999d208c9e40d2b1d51bf55217837e6871a4a63
check_sha "$PARSER_SOURCE" 305f8d997b9e94f311824b9601f151d51048e1af697b2964fcb81f6af0601346
check_sha "$SOURCE_PINS" c04f658bc64a9c2ea1076848f836a46a212cd5748850cc5a00b04f9390f1d695
mkdir -- "$OUTPUT"
mkdir -- "$OUTPUT/logs" "$OUTPUT/artifact"
STAGE=identity
STATUS=failed
BUILDER_SCRIPT=$(realpath -e -- "$0")
export AOSP_DIR SOURCE_DIR SOURCE_PINS
export OUTPUT BASE_LIB BASE_SHA BASE_BUILD_ID SOURCE GTEST PATCH TEST_PATCH X11_TEST_PATCH BUILDER_SCRIPT PARSER_HEADER PARSER_SOURCE PARSER_LIB PARSER_GIT_BLOB PARSER_SHA PARSER_REFERENCE_BLOB PARSER_OBJCOPY_REVISION PARSER_OBJCOPY_BLOB
finish() {
  local code=$?
  trap - EXIT
  set +e
  # Preserve available configure diagnostics even when the first integrated configure fails.
  if [[ -d "$AOSP_DIR/.repo" && ! -f "$OUTPUT/resolved-manifest.xml" ]]; then
    (cd "$AOSP_DIR" && timeout --kill-after=5s 30s python3 "$OUTPUT/repo-tool/repo" manifest -r -o "$OUTPUT/partial-resolved-manifest.xml") > "$OUTPUT/logs/partial-source-manifest.out" 2> "$OUTPUT/logs/partial-source-manifest.err"
  fi
  if [[ -d "$OUTPUT/build" ]]; then
    for build_file in CMakeCache.txt compile_commands.json; do
      if [[ -f "$OUTPUT/build/$build_file" ]]; then cp -- "$OUTPUT/build/$build_file" "$OUTPUT/"; fi
    done
    mkdir -p "$OUTPUT/logs/cmake"
    if [[ -d "$OUTPUT/build/CMakeFiles" ]]; then
      find "$OUTPUT/build/CMakeFiles" -maxdepth 1 -type f \( -name '*.log' -o -name '*Log.yaml' \) -exec cp -- {} "$OUTPUT/logs/cmake/" \;
    fi
  fi
  # Retain reference ELF and debuglink evidence even when acquisition or pairing fails.
  for parser_item in unstripped.so libshadertranslator.so build/debug_info/libshadertranslator.so.debug; do
    parser_label=${parser_item##*/}
    if [[ -f "$OUTPUT/reference/$parser_item" ]]; then
      timeout --kill-after=5s 30s readelf -h -n -S -l -d -Ws -C "$OUTPUT/reference/$parser_item" > "$OUTPUT/parser-reference-$parser_label.elf.txt" 2> "$OUTPUT/parser-reference-$parser_label.elf.err"
      timeout --kill-after=5s 30s readelf -p .gnu_debuglink -x .gnu_debuglink "$OUTPUT/reference/$parser_item" > "$OUTPUT/parser-reference-$parser_label.debuglink.txt" 2> "$OUTPUT/parser-reference-$parser_label.debuglink.err"
    fi
  done
  export STAGE STATUS
  # Small factual receipt only; absence of a completed stage is not Android acceptance.
  python3 - "$code" <<'PY'
import hashlib, json, os, pathlib, sys
root = pathlib.Path(os.environ['OUTPUT'])
def digest(path):
    path = pathlib.Path(path)
    if not path.is_file(): return None
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()
# Input, output, debug sidecar and tool identities survive a failed transform/cmp.
def reference_identity(relative):
    path = root / 'reference' / relative
    if not path.is_file(): return None
    blob = hashlib.sha1(f'blob {path.stat().st_size}\0'.encode())
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            blob.update(block)
    return dict(path=relative, bytes=path.stat().st_size, sha256=digest(path), gitBlob=blob.hexdigest())
(root / 'parser-reference-readback.json').write_text(json.dumps(dict(
    stage=os.environ['STAGE'], exitCode=int(sys.argv[1]),
    reference=reference_identity('unstripped.so'),
    result=reference_identity('libshadertranslator.so'),
    debug=reference_identity('build/debug_info/libshadertranslator.so.debug'),
    tool=reference_identity('llvm-objcopy'),
    requiredToolRevision=os.environ['PARSER_OBJCOPY_REVISION'],
    requiredToolGitBlob=os.environ['PARSER_OBJCOPY_BLOB']), indent=2) + '\n')
library = root / 'artifact/libgfxstream_backend.so'
receipt = dict(schema=1, kind='source-derived-sdk-host-module', status=os.environ['STATUS'],
    stage=os.environ['STAGE'], exitCode=int(sys.argv[1]), googleOriginal=False,
    baseLibrary=dict(sha256=os.environ['BASE_SHA'], buildId=os.environ['BASE_BUILD_ID']),
    source=dict(repository='https://android.googlesource.com/platform/hardware/google/gfxstream', revision=os.environ['SOURCE'],
        tree=(root / 'source-identity.txt').read_text().splitlines()[1] if (root / 'source-identity.txt').is_file() else None),
    builderSha256=digest(os.environ['BUILDER_SCRIPT']),
    integrationSources=json.loads(pathlib.Path(os.environ['SOURCE_PINS']).read_text()),
    resolvedManifestSha256=digest(root / 'resolved-manifest.xml'),
    googleTestRevision=os.environ['GTEST'],
    angleShaderParser=dict(enabled=True, source=json.loads(pathlib.Path(os.environ['PARSER_SOURCE']).read_text()),
        headerSha256=digest(os.environ['PARSER_HEADER']), librarySha256=digest(os.environ['PARSER_LIB']),
        requiredLibraryGitBlob=os.environ['PARSER_GIT_BLOB'],
        referenceGitBlob=os.environ['PARSER_REFERENCE_BLOB'],
        stripWitness=json.loads((root / 'parser-strip-witness.json').read_text()) if (root / 'parser-strip-witness.json').is_file() else None,
        actualLibraryGitBlob=(root / 'parser-git-blob.txt').read_text().strip() if (root / 'parser-git-blob.txt').is_file() else None),
    patches={pathlib.Path(os.environ[k]).name:digest(os.environ[k]) for k in ('PATCH','TEST_PATCH','X11_TEST_PATCH')},
    derivedLibrarySha256=digest(library),
    derivedLibraryBuildId=(root / 'artifact/build-id.txt').read_text().strip() if (root / 'artifact/build-id.txt').is_file() else None,
    hostRegressionPassed=os.environ['STATUS'] == 'host-module-built-and-tested',
    emulatorExecuted=False, sdkExecuted=False, appExecuted=False,
    loaderRuntimeAccepted=False, androidAccepted=False, performanceAccepted=False, releaseAccepted=False)
(root / 'build-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
PY
  local receipt_code=$?
  if (( code != 0 )); then exit "$code"; fi
  exit "$receipt_code"
}
trap finish EXIT
run() {
  local label=$1 limit=$2; shift 2
  STAGE=$label
  printf '%q ' "$@" > "$OUTPUT/logs/$label.command"
  printf '\n' >> "$OUTPUT/logs/$label.command"
  timeout --signal=TERM --kill-after=10s "$limit" "$@" > "$OUTPUT/logs/$label.out" 2> "$OUTPUT/logs/$label.err"
}
STAGE=parser-preflight
[[ -f "$PARSER_LIB" ]] || { echo 'Pinned SDK shader translator library is missing' >&2; exit 1; }
git hash-object --no-filters "$PARSER_LIB" > "$OUTPUT/parser-git-blob.txt"
[[ $(cat "$OUTPUT/parser-git-blob.txt") == "$PARSER_GIT_BLOB" ]] || { echo 'SDK shader translator differs from the fixed original SDK library' >&2; exit 1; }
check_sha "$PARSER_LIB" "$PARSER_SHA"
sha256sum "$PARSER_LIB" > "$OUTPUT/original-sdk-parser.sha256"
readelf -h -n -S -l -d -Ws -C "$PARSER_LIB" > "$OUTPUT/original-sdk-parser-elf.txt"
nm -D --defined-only "$PARSER_LIB" > "$OUTPUT/original-sdk-parser-exports.txt"
for symbol in STInitialize STFinalize STGenerateResources STCompileAndResolve STFreeShaderResolveState STCopyVariable STCopyInterfaceBlock STDestroyVariable STDestroyInterfaceBlock; do
  grep -Eq "[[:space:]][TW][[:space:]]$symbol$" "$OUTPUT/original-sdk-parser-exports.txt"
done
# One fixed official debug-split recipe; never alter or execute the SDK parser.
# All downloaded binaries and the debug sidecar remain outside uploaded artifact paths.
readelf -p .gnu_debuglink -x .gnu_debuglink "$PARSER_LIB" > "$OUTPUT/original-sdk-parser-debuglink.txt" 2> "$OUTPUT/original-sdk-parser-debuglink.err"
STAGE=parser-strip-reference
mkdir -- "$OUTPUT/reference"
run parser-reference-fetch 120s python3 - "$OUTPUT/reference/unstripped.so" <<'PY'
import base64, pathlib, sys, urllib.request
url = 'https://android.googlesource.com/platform/prebuilts/android-emulator-build/common/+/1ebd9720117e7e3c495a1e1c43e069f1727d60d5/ANGLE/linux-x86_64/lib/libshadertranslator.so?format=TEXT'
with urllib.request.urlopen(url, timeout=60) as response:
    encoded = response.read()
pathlib.Path(sys.argv[1]).write_bytes(base64.b64decode(b"".join(encoded.split()), validate=True))
PY
[[ $(git hash-object --no-filters "$OUTPUT/reference/unstripped.so") == "$PARSER_REFERENCE_BLOB" ]] || { echo 'Official parser reference blob differs' >&2; exit 1; }
run parser-objcopy-fetch 120s python3 - "$OUTPUT/reference/llvm-objcopy" <<'PY'
import base64, pathlib, sys, urllib.request
url = 'https://android.googlesource.com/platform/prebuilts/clang/host/linux-x86/+/6599d0b82da42c78c5f30e73ba71aa3db907e5aa/clang-r596125/bin/llvm-objcopy?format=TEXT'
with urllib.request.urlopen(url, timeout=60) as response:
    encoded = response.read()
pathlib.Path(sys.argv[1]).write_bytes(base64.b64decode(b"".join(encoded.split()), validate=True))
PY
STAGE=parser-objcopy-identity
OBJCOPY_TOOL="$OUTPUT/reference/llvm-objcopy"
[[ $(git hash-object --no-filters "$OBJCOPY_TOOL") == "$PARSER_OBJCOPY_BLOB" ]] || { echo 'Official LLVM objcopy blob differs' >&2; exit 1; }
printf '%s\n' "$OBJCOPY_TOOL" > "$OUTPUT/parser-objcopy-tool.path"
printf '%s\n' "$PARSER_OBJCOPY_REVISION" > "$OUTPUT/parser-objcopy-tool.revision"
git hash-object --no-filters "$OBJCOPY_TOOL" > "$OUTPUT/parser-objcopy-tool.git-blob"
sha256sum "$OBJCOPY_TOOL" > "$OUTPUT/parser-objcopy-tool.sha256"
# Static ELF readback includes the interpreter and NEEDED entries before execution.
run parser-objcopy-elf 30s readelf -h -l -d -W "$OBJCOPY_TOOL"
chmod 755 -- "$OBJCOPY_TOOL"
# Missing runtime dependencies fail here with their original loader stderr; no install/fallback.
run parser-objcopy-version 30s "$OBJCOPY_TOOL" --version
cp -- "$OUTPUT/reference/unstripped.so" "$OUTPUT/reference/libshadertranslator.so"
# Preserve the official wrapper's basename, debug filename, order and arguments exactly.
pushd "$OUTPUT/reference" > /dev/null
mkdir -p build/debug_info
run parser-debug-keep 30s "$OBJCOPY_TOOL" --only-keep-debug libshadertranslator.so build/debug_info/libshadertranslator.so.debug
run parser-debug-strip 30s "$OBJCOPY_TOOL" --strip-unneeded libshadertranslator.so
run parser-debug-link 30s "$OBJCOPY_TOOL" --add-gnu-debuglink=build/debug_info/libshadertranslator.so.debug libshadertranslator.so
popd > /dev/null
STAGE=parser-strip-equality
run parser-strip-cmp 30s cmp -- "$OUTPUT/reference/libshadertranslator.so" "$PARSER_LIB"
check_sha "$OUTPUT/reference/libshadertranslator.so" "$PARSER_SHA"
[[ $(git hash-object --no-filters "$OUTPUT/reference/libshadertranslator.so") == "$PARSER_GIT_BLOB" ]]
python3 - "$OUTPUT" <<'PY'
import hashlib, json, os, pathlib, sys
root = pathlib.Path(sys.argv[1])
reference = root / 'reference/unstripped.so'
stripped = root / 'reference/libshadertranslator.so'
original = pathlib.Path(os.environ['PARSER_LIB'])

def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()
# Written only after cmp and both fixed identity checks have succeeded.
witness = dict(kind='exact-byte-equality-after-fixed-official-llvm-debug-split',
    googlePackagingToolIdentified=False,
    reference=dict(revision='1ebd9720117e7e3c495a1e1c43e069f1727d60d5',
        gitBlob=os.environ['PARSER_REFERENCE_BLOB'], bytes=reference.stat().st_size, sha256=digest(reference)),
    tool=dict(repository='https://android.googlesource.com/platform/prebuilts/clang/host/linux-x86',
        revision=os.environ['PARSER_OBJCOPY_REVISION'], path='clang-r596125/bin/llvm-objcopy',
        gitBlob=os.environ['PARSER_OBJCOPY_BLOB'], sha256=digest(root / 'reference/llvm-objcopy'),
        version=(root / 'logs/parser-objcopy-version.out').read_text(),
        recipeRevision='cf0f40a0aad1aa3fca68f2f89372525c8d28ca68',
        arguments=[['--only-keep-debug', 'libshadertranslator.so', 'build/debug_info/libshadertranslator.so.debug'],
            ['--strip-unneeded', 'libshadertranslator.so'],
            ['--add-gnu-debuglink=build/debug_info/libshadertranslator.so.debug', 'libshadertranslator.so']]),
    result=dict(bytes=stripped.stat().st_size, sha256=digest(stripped), gitBlob=os.environ['PARSER_GIT_BLOB']),
    originalSdk=dict(bytes=original.stat().st_size, sha256=digest(original), gitBlob=os.environ['PARSER_GIT_BLOB']))
(root / 'parser-strip-witness.json').write_text(json.dumps(witness, indent=2) + '\n')
PY
run parser-loader 30s env LD_LIBRARY_PATH="$BASE_DIR" ldd -r "$PARSER_LIB"
if grep -Eq 'undefined symbol:|not found' "$OUTPUT/logs/parser-loader.out" "$OUTPUT/logs/parser-loader.err"; then
  echo 'Original SDK shader translator has unresolved runtime dependencies' >&2
  exit 1
else
  grep_exit=$?
  if (( grep_exit != 1 )); then
    echo 'Could not read shader translator loader evidence' >&2
    exit "$grep_exit"
  fi
fi
readelf -h -n -d -Ws -C "$BASE_LIB" > "$OUTPUT/base-elf.txt"
nm -DC --defined-only "$BASE_LIB" > "$OUTPUT/base-exports.txt"
# Use the official repo source client and public AEMU checkout, all inside this new output.
df -B1 "$OUTPUT" > "$OUTPUT/disk-before.txt"
free -b > "$OUTPUT/memory-before.txt"
cp -- "$SOURCE_PINS" "$OUTPUT/source-pins.json"
# git-repo explicitly supports this location for its user config; keep it in this output.
export REPO_CONFIG_DIR="$OUTPUT/repo-user-config"
mkdir -- "$REPO_CONFIG_DIR"
run repo-tool-init 30s git init "$OUTPUT/repo-tool"
run repo-tool-remote 30s git -C "$OUTPUT/repo-tool" remote add origin https://gerrit.googlesource.com/git-repo
run repo-tool-fetch 120s git -C "$OUTPUT/repo-tool" fetch --depth 1 origin refs/tags/v2.59
run repo-tool-checkout 30s git -c core.autocrlf=false -C "$OUTPUT/repo-tool" checkout --detach FETCH_HEAD
[[ $(git -C "$OUTPUT/repo-tool" rev-parse HEAD) == 1afe96a7e997ce7748f066b206a85ac648f7a87c ]]
git -C "$OUTPUT/repo-tool" rev-parse HEAD HEAD^{tree} > "$OUTPUT/repo-tool-identity.txt"
mkdir -- "$AOSP_DIR"
pushd "$AOSP_DIR" > /dev/null
REPO=(python3 "$OUTPUT/repo-tool/repo")
run manifest-init 300s "${REPO[@]}" init --no-clone-bundle --depth=1 \
  --repo-url=https://gerrit.googlesource.com/git-repo --repo-rev=v2.59 \
  -u https://android.googlesource.com/platform/manifest \
  -b 2692acc620f6563b21995540656674faeb536cdc -g default,platform-linux
[[ $(git -C .repo/repo rev-parse HEAD) == 1afe96a7e997ce7748f066b206a85ac648f7a87c ]]
[[ $(git -C .repo/manifests rev-parse HEAD) == 2692acc620f6563b21995540656674faeb536cdc ]]
python3 - "$SOURCE_PINS" "$AOSP_DIR" <<'PY'
import json, pathlib, sys, xml.etree.ElementTree as ET
pins = json.loads(pathlib.Path(sys.argv[1]).read_text())
root = ET.Element('manifest')
for project in pins['projects']:
    ET.SubElement(root, 'extend-project', name=project['name'], path=project['path'], revision=project['revision'])
destination = pathlib.Path(sys.argv[2]) / '.repo/local_manifests/aerove-fixed-integration.xml'
destination.parent.mkdir(parents=True, exist_ok=True)
ET.ElementTree(root).write(destination, encoding='utf-8', xml_declaration=True)
PY
# No custom multi-repo downloader, toolchain fallback or prebuilt rebuild matrix.
run source-sync 1800s "${REPO[@]}" sync -c -j2 --fail-fast --no-clone-bundle
run source-manifest 120s "${REPO[@]}" manifest -r -o "$OUTPUT/resolved-manifest.xml"
popd > /dev/null
run source-pins-check 30s python3 - "$SOURCE_PINS" "$AOSP_DIR" <<'PY'
import json, pathlib, subprocess, sys
pins = json.loads(pathlib.Path(sys.argv[1]).read_text())
for project in pins['projects']:
    actual = subprocess.check_output(['git', '-C', str(pathlib.Path(sys.argv[2]) / project['path']), 'rev-parse', 'HEAD'], text=True).strip()
    print(project['path'], actual, flush=True)
    if actual != project['revision']:
        raise ValueError('Public integration source differs: ' + project['path'])
PY
git -C "$SOURCE_DIR" rev-parse HEAD HEAD^{tree} > "$OUTPUT/source-identity.txt"
git -C "$AOSP_DIR/external/googletest" rev-parse HEAD HEAD^{tree} > "$OUTPUT/gtest-identity.txt"
check_sha "$SOURCE_DIR/$PRODUCTION" f80e1b6083072d86602cc4c8aed4e3972180ea244d3939b611012dc89e6ed6ab
check_sha "$SOURCE_DIR/$TEST_SOURCE" 6e5485142d1e3f0a287d74994e1404364b94b2ffebfc75fa130ae534bb13e44f
check_sha "$SOURCE_DIR/$X11_TEST_SOURCE" 0fba089104b04943a2f01f4ef0f6e07fe282468e39eb84e7e26c02e0377b64a1
# Bind the exact header identity to the common source used by QEMU's ANGLE prebuilt configuration.
check_sha "$AOSP_DIR/prebuilts/android-emulator-build/common/ANGLE/linux-x86_64/include/ShaderTranslator.h" 979138ea0b60d23ee604b065743ebd9a2a32fbe76bee4e68081e3c73975b673a
run test-patch-check 30s git -C "$SOURCE_DIR" apply --check "$TEST_PATCH"
run test-patch 30s git -C "$SOURCE_DIR" apply "$TEST_PATCH"
check_sha "$SOURCE_DIR/$TEST_SOURCE" bea09f474edc39a3ade868656e6dc60345d9bd56e65f90b0c8edd6bdeddfdb96
run x11-test-patch-check 30s git -C "$SOURCE_DIR" apply --check "$X11_TEST_PATCH"
run x11-test-patch 30s git -C "$SOURCE_DIR" apply "$X11_TEST_PATCH"
check_sha "$SOURCE_DIR/$X11_TEST_SOURCE" c9bd13619a7d35fd89901e8f70e9dfe696ab749ed14d9331545b9e38034936ce
CMAKE_TOOL="$AOSP_DIR/prebuilts/cmake/linux-x86/bin/cmake"
NINJA_TOOL="$AOSP_DIR/prebuilts/ninja/linux-x86/ninja"
CLANG_TOOL="$AOSP_DIR/prebuilts/clang/host/linux-x86/clang-r596125/bin/clang++"
run compiler-version 30s "$CLANG_TOOL" --version
sha256sum "$CLANG_TOOL" > "$OUTPUT/compiler-files.sha256"
# Keep the pre-existing ABI1/EXTENSIVE regression contract. Fixed r596125's __config defines EXTENSIVE.
# QEMU defines FAST in directory definitions; the actual generated command is preprocessed below,
# so a changed option order cannot silently weaken this regression.
CXX_FLAGS="-stdlib=libc++ -D_LIBCPP_ABI_VERSION=1 -D_LIBCPP_HARDENING_MODE=_LIBCPP_HARDENING_MODE_EXTENSIVE -I\"$PARSER_INCLUDE\""
pushd "$AOSP_DIR/external/qemu" > /dev/null
run configure 600s bash android/rebuild.sh --task Configure --target linux_x86_64 \
  --config release --crash none --ccache none --out "$OUTPUT/build" \
  --cmake_option CMAKE_EXPORT_COMPILE_COMMANDS=ON \
  --cmake_option "CMAKE_CXX_FLAGS=$CXX_FLAGS" --cmake_option USE_ANGLE_SHADER_PARSER=ON
popd > /dev/null
run build-targets 30s "$NINJA_TOOL" -C "$OUTPUT/build" -t targets all
run compiler-contract 60s python3 - "$OUTPUT/build/compile_commands.json" "$OUTPUT/libcxx-macros.txt" "$OUTPUT/compiler-contract-command.json" <<'PY'
import json, pathlib, shlex, subprocess, sys
commands = json.loads(pathlib.Path(sys.argv[1]).read_text())
entry = next(item for item in commands if item['file'].endswith('/host/gl/glestranslator/gles_v2/gles_v2_imp.cpp'))
args = entry.get('arguments') or shlex.split(entry['command'])
if '-DCONFIG_AEMU' not in args and '-DCONFIG_AEMU=1' not in args:
    raise ValueError('GLES production unit was not configured for AEMU')
options = []
skip = False
for arg in args[1:]:
    if skip:
        skip = False
        continue
    if arg in ('-o', '-MF', '-MT', '-MQ'):
        skip = True
    elif arg not in ('-c', '-MD', '-MMD', entry['file']):
        options.append(arg)
command = [args[0], *options, '-dM', '-E', '-x', 'c++', '-include', '__config', '/dev/null']
pathlib.Path(sys.argv[3]).write_text(json.dumps(dict(directory=entry['directory'], command=command), indent=2) + '\n')
result = subprocess.run(command, cwd=entry['directory'], text=True, stdout=subprocess.PIPE, timeout=45)
pathlib.Path(sys.argv[2]).write_text(result.stdout)
result.check_returncode()
if '#define _LIBCPP_ABI_VERSION 1\n' not in result.stdout or '#define _LIBCPP_HARDENING_MODE _LIBCPP_HARDENING_MODE_EXTENSIVE\n' not in result.stdout:
    raise ValueError('Actual integrated production command does not retain ABI1/EXTENSIVE')
PY
# Compile the real official integrated shared target before attempting its existing test target.
run integrated-target-build 2400s "$CMAKE_TOOL" --build "$OUTPUT/build" --target gfxstream_backend --parallel 2
# First prove the fixed source's zero-count defect under hardening, using the existing GLES3 fixture.
run red-build 2400s "$CMAKE_TOOL" --build "$OUTPUT/build" --target OpenglRender_unittests --parallel 2
STAGE=zero-attachment-red
set +e
timeout --signal=TERM --kill-after=10s 120s env LD_LIBRARY_PATH="$BASE_DIR:$OUTPUT/build/lib64" ANDROID_EMU_TEST_WITH_HOST_GPU=1 LIBGL_ALWAYS_SOFTWARE=1 \
  xvfb-run -a "$OUTPUT/build/OpenglRender_unittests" \
  --gtest_filter=InvalidateFramebufferTest.ZeroAttachmentsPreserveColor \
  > "$OUTPUT/logs/zero-attachment-red.out" 2> "$OUTPUT/logs/zero-attachment-red.err"
red_exit=$?
set -e
printf '%s\n' "$red_exit" > "$OUTPUT/logs/zero-attachment-red.exit"
# A failed driver/context initialization, timeout or arbitrary exit is not the expected RED.
[[ "$red_exit" == 132 || "$red_exit" == 134 ]]
# The wrapped test's stderr marker appears in stdout; retain both original streams.
grep -Fxq gfxstream-zero-attachment-ready "$OUTPUT/logs/zero-attachment-red.out" "$OUTPUT/logs/zero-attachment-red.err"
run production-patch-check 30s git -C "$SOURCE_DIR" apply --check "$PATCH"
run production-patch 30s git -C "$SOURCE_DIR" apply "$PATCH"
check_sha "$SOURCE_DIR/$PRODUCTION" 0f2031bc4bdc13dd97d1be54c8307c99937ef9114e24c0dc04688c55b7cf8bf2
run green-build 2400s "$CMAKE_TOOL" --build "$OUTPUT/build" --target gfxstream_backend OpenglRender_unittests --parallel 2
run gles-regression 180s env LD_LIBRARY_PATH="$BASE_DIR:$OUTPUT/build/lib64" ANDROID_EMU_TEST_WITH_HOST_GPU=1 LIBGL_ALWAYS_SOFTWARE=1 \
  xvfb-run -a "$OUTPUT/build/OpenglRender_unittests" \
  '--gtest_filter=InvalidateFramebufferTest.*:CombinedFramebufferBlitTest/CombinedFramebufferBlit.*' \
  --gtest_output="xml:$OUTPUT/gles-regression.xml"
python3 - "$OUTPUT/gles-regression.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
cases = root.findall('.//testcase')
expected = {'ZeroAttachmentsPreserveColor','InvalidArgumentsRemainErrors','NonEmptyAttachmentsRemainValid'}
actual = {case.attrib['name'] for case in cases if case.attrib.get('classname') == 'InvalidateFramebufferTest'}
if actual != expected or len(cases) < 6 or any(case.find('skipped') is not None or case.find('failure') is not None or case.attrib.get('status') != 'run' for case in cases):
    raise ValueError('Required real GLES regression cases were missing, skipped or failed')
PY
STAGE=elf-and-loader
cp "$OUTPUT/build/lib64/libgfxstream_backend.so" "$OUTPUT/artifact/libgfxstream_backend.so"
readelf -h -n -d -Ws -C "$OUTPUT/artifact/libgfxstream_backend.so" > "$OUTPUT/derived-elf.txt"
nm -DC --defined-only "$OUTPUT/artifact/libgfxstream_backend.so" > "$OUTPUT/derived-exports.txt"
# These are the startup ABI entries actually present in the original SDK; cf0-only set_service_ops is excluded.
for symbol in initLibrary android_setOpenglesRenderer android_stopOpenglesRenderer \
  stream_renderer_init stream_renderer_teardown stream_renderer_resource_create stream_renderer_resource_unref \
  stream_renderer_context_destroy stream_renderer_submit_cmd stream_renderer_transfer_read_iov stream_renderer_transfer_write_iov \
  stream_renderer_get_cap_set stream_renderer_fill_caps stream_renderer_resource_attach_iov stream_renderer_resource_detach_iov \
  stream_renderer_ctx_attach_resource stream_renderer_ctx_detach_resource stream_renderer_resource_get_info stream_renderer_flush \
  stream_renderer_create_blob stream_renderer_export_blob stream_renderer_resource_map stream_renderer_resource_unmap \
  stream_renderer_context_create stream_renderer_create_fence stream_renderer_resource_map_info stream_renderer_vulkan_info; do
  grep -Eq "[[:space:]]$symbol$" "$OUTPUT/base-exports.txt"
  grep -Eq "[[:space:]]$symbol$" "$OUTPUT/derived-exports.txt"
done
grep -q 'std::__1::' "$OUTPUT/derived-elf.txt"
if grep -Eq 'std::__2::|GLIBCXX_' "$OUTPUT/derived-elf.txt"; then
  echo 'Derived library has an incompatible C++ namespace or libstdc++ dependency' >&2
  exit 1
else
  grep_exit=$?
  if (( grep_exit != 1 )); then
    echo 'Could not read derived ELF ABI evidence' >&2
    exit "$grep_exit"
  fi
fi
run loader-relocations 30s env LD_LIBRARY_PATH="$BASE_DIR" ldd -r "$OUTPUT/artifact/libgfxstream_backend.so"
if grep -Eq 'undefined symbol:|not found' "$OUTPUT/logs/loader-relocations.out" "$OUTPUT/logs/loader-relocations.err"; then
  echo 'Derived library loader reported an unresolved dependency or symbol' >&2
  exit 1
else
  grep_exit=$?
  if (( grep_exit != 1 )); then
    echo 'Could not read derived loader evidence' >&2
    exit "$grep_exit"
  fi
fi
derived_build_id=$(readelf -n "$OUTPUT/artifact/libgfxstream_backend.so" | sed -n 's/.*Build ID: //p')
[[ "$derived_build_id" =~ ^[0-9a-f]{40}$ ]] || { echo 'Require one derived SHA-1 ELF build-id' >&2; exit 1; }
printf '%s\n' "$derived_build_id" > "$OUTPUT/artifact/build-id.txt"
sha256sum "$OUTPUT/artifact/libgfxstream_backend.so" > "$OUTPUT/artifact/SHA256SUMS"
git -C "$SOURCE_DIR" diff --binary > "$OUTPUT/applied-source.patch"
sha256sum "$SOURCE_DIR/$PRODUCTION" "$SOURCE_DIR/$TEST_SOURCE" "$SOURCE_DIR/$X11_TEST_SOURCE" > "$OUTPUT/source-after.sha256"
cp "$OUTPUT/build/CMakeCache.txt" "$OUTPUT/build/compile_commands.json" "$OUTPUT/"
cp "$PATCH" "$TEST_PATCH" "$X11_TEST_PATCH" "$OUTPUT/artifact/"
cp -a -- "$PARSER_INCLUDE" "$OUTPUT/artifact/angle-shader-translator"
# Retain all source/third-party licensing with the derived module, without shipping the SDK.
find "$AOSP_DIR" -type d \( -name .git -o -name .repo \) -prune -o -type f \( -iname 'license*' -o -iname 'copying*' -o -iname 'notice*' \) -print0 \
  | tar --null -T - -czf "$OUTPUT/artifact/upstream-notices.tar.gz"
check_sha "$BASE_LIB" "$BASE_SHA"
check_sha "$PARSER_LIB" "$PARSER_SHA"
[[ $(git hash-object --no-filters "$PARSER_LIB") == "$PARSER_GIT_BLOB" ]]
STAGE=host-module-built-and-tested
STATUS=host-module-built-and-tested
# This is a module/test result only. Root must wire the derived path into the original owned emulator,
# verify its actually mapped library, then run representative UI with unchanged App/API/GPU/quality.
