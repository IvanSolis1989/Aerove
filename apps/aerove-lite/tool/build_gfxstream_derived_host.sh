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
for tool in git cmake ninja clang-18 clang++-18 pkg-config readelf nm sha256sum python3 timeout xvfb-run ldd; do
  command -v "$tool" >/dev/null || { echo "Missing build dependency: $tool" >&2; exit 2; }
done
SOURCE=0884d2d224bfe2e46cbb13b71d68d7c66e2f7dc9
GTEST=f8d7d77c06936315286eb55f8de22cd23c188571
BASE_SHA=67fa485ebe97369429e283fbefb6db9046024f7264102de60c4bfc70d2628f7e
BASE_BUILD_ID=49b9fc8e45122f00b3970a89a72eb26101bcffe4
PRODUCTION=host/gl/glestranslator/gles_v2/gles_v30_imp.cpp
TEST_SOURCE=host/tests/DefaultFramebufferBlit_unittest.cpp
X11_TEST_SOURCE=host/tests/TextureDraw_unittest.cpp
PATCH="$SCRIPT_DIR/fixtures/gfxstream-zero-attachments.patch"
TEST_PATCH="$SCRIPT_DIR/fixtures/gfxstream-zero-attachments-tests.patch"
X11_TEST_PATCH="$SCRIPT_DIR/fixtures/gfxstream-x11-gtest.patch"
check_sha() { printf '%s  %s\n' "$2" "$1" | sha256sum --check --status; }
check_sha "$BASE_LIB" "$BASE_SHA"
[[ $(readelf -n "$BASE_LIB" | sed -n 's/.*Build ID: //p') == "$BASE_BUILD_ID" ]]
check_sha "$PATCH" a4d633095cb249ea9d7cada1127aab0692c84c5f9950e5926ebabe6a13ee9180
check_sha "$TEST_PATCH" 4e33b60eaf701019e67514aeedbf976e0b7591bf76518ca918aaf1d2884ac38e
check_sha "$X11_TEST_PATCH" 0e061d4f2bea1de57416600a7552e0f487c44e6606f2b68054d3078d7ffd0a43
mkdir -- "$OUTPUT"
mkdir -- "$OUTPUT/logs" "$OUTPUT/artifact"
STAGE=identity
STATUS=failed
BUILDER_SCRIPT=$(realpath -e -- "$0")
export OUTPUT BASE_LIB BASE_SHA BASE_BUILD_ID SOURCE GTEST PATCH TEST_PATCH X11_TEST_PATCH BUILDER_SCRIPT
finish() {
  local code=$?
  trap - EXIT
  set +e
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
library = root / 'artifact/libgfxstream_backend.so'
receipt = dict(schema=1, kind='source-derived-sdk-host-module', status=os.environ['STATUS'],
    stage=os.environ['STAGE'], exitCode=int(sys.argv[1]), googleOriginal=False,
    baseLibrary=dict(sha256=os.environ['BASE_SHA'], buildId=os.environ['BASE_BUILD_ID']),
    source=dict(repository='https://github.com/google/gfxstream', revision=os.environ['SOURCE'],
        tree=(root / 'source-identity.txt').read_text().splitlines()[1] if (root / 'source-identity.txt').is_file() else None),
    builderSha256=digest(os.environ['BUILDER_SCRIPT']),
    googleTestRevision=os.environ['GTEST'],
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
readelf -h -n -d -Ws -C "$BASE_LIB" > "$OUTPUT/base-elf.txt"
nm -DC --defined-only "$BASE_LIB" > "$OUTPUT/base-exports.txt"
clang++-18 --version > "$OUTPUT/compiler-version.txt"
sha256sum "$(realpath -e "$(command -v clang-18)")" "$(realpath -e "$(command -v clang++-18)")" > "$OUTPUT/compiler-files.sha256"
dpkg-query -W clang-18 libc++-18-dev libc++abi-18-dev cmake ninja-build xvfb xauth libgl1 libglx-mesa0 libgl1-mesa-dri libvulkan1 > "$OUTPUT/packages.txt"
CXX_FLAGS='-stdlib=libc++ -D_LIBCPP_ABI_VERSION=1 -D_LIBCPP_HARDENING_MODE=_LIBCPP_HARDENING_MODE_EXTENSIVE'
clang++-18 $CXX_FLAGS -dM -E -x c++ -include __config /dev/null > "$OUTPUT/libcxx-macros.txt"
[[ $(grep -c '^#define _LIBCPP_ABI_VERSION 1$' "$OUTPUT/libcxx-macros.txt") == 1 ]]
df -B1 "$OUTPUT" > "$OUTPUT/disk-before.txt"
free -b > "$OUTPUT/memory-before.txt"
run source-init 30s git init "$OUTPUT/source"
run source-remote 30s git -C "$OUTPUT/source" remote add origin https://github.com/google/gfxstream.git
run source-fetch 300s git -C "$OUTPUT/source" fetch --depth 1 origin "$SOURCE"
run source-checkout 30s git -c core.autocrlf=false -C "$OUTPUT/source" checkout --detach FETCH_HEAD
[[ $(git -C "$OUTPUT/source" rev-parse HEAD) == "$SOURCE" ]]
git -C "$OUTPUT/source" rev-parse HEAD HEAD^{tree} > "$OUTPUT/source-identity.txt"
check_sha "$OUTPUT/source/$PRODUCTION" 7a0b481b2633a2f433c4961412451f66b36f30c43fa013d009ef4d95104a942a
check_sha "$OUTPUT/source/$TEST_SOURCE" 6e5485142d1e3f0a287d74994e1404364b94b2ffebfc75fa130ae534bb13e44f
check_sha "$OUTPUT/source/$X11_TEST_SOURCE" 0fba089104b04943a2f01f4ef0f6e07fe282468e39eb84e7e26c02e0377b64a1
run gtest-init 30s git init "$OUTPUT/googletest"
run gtest-remote 30s git -C "$OUTPUT/googletest" remote add origin https://github.com/google/googletest.git
run gtest-fetch 300s git -C "$OUTPUT/googletest" fetch --depth 1 origin "$GTEST"
run gtest-checkout 30s git -c core.autocrlf=false -C "$OUTPUT/googletest" checkout --detach FETCH_HEAD
[[ $(git -C "$OUTPUT/googletest" rev-parse HEAD) == "$GTEST" ]]
git -C "$OUTPUT/googletest" rev-parse HEAD HEAD^{tree} > "$OUTPUT/gtest-identity.txt"
run gtest-configure 120s cmake -S "$OUTPUT/googletest" -B "$OUTPUT/gtest-out" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$OUTPUT/gtest-install" \
  -DCMAKE_C_COMPILER=clang-18 -DCMAKE_CXX_COMPILER=clang++-18 -DCMAKE_CXX_FLAGS="$CXX_FLAGS"
run gtest-build 600s cmake --build "$OUTPUT/gtest-out" --parallel 2
run gtest-install 60s cmake --install "$OUTPUT/gtest-out"
export PKG_CONFIG_PATH="$OUTPUT/gtest-install/lib/pkgconfig:$OUTPUT/gtest-install/lib64/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
pkg-config --modversion gtest gtest_main gmock gmock_main > "$OUTPUT/gtest-packages.txt"
run test-patch-check 30s git -C "$OUTPUT/source" apply --check "$TEST_PATCH"
run test-patch 30s git -C "$OUTPUT/source" apply "$TEST_PATCH"
check_sha "$OUTPUT/source/$TEST_SOURCE" bea09f474edc39a3ade868656e6dc60345d9bd56e65f90b0c8edd6bdeddfdb96
run x11-test-patch-check 30s git -C "$OUTPUT/source" apply --check "$X11_TEST_PATCH"
run x11-test-patch 30s git -C "$OUTPUT/source" apply "$X11_TEST_PATCH"
check_sha "$OUTPUT/source/$X11_TEST_SOURCE" c9bd13619a7d35fd89901e8f70e9dfe696ab749ed14d9331545b9e38034936ce
run configure 180s cmake -S "$OUTPUT/source" -B "$OUTPUT/build" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_COMPILER=clang-18 -DCMAKE_CXX_COMPILER=clang++-18 \
  -DCMAKE_CXX_FLAGS="$CXX_FLAGS" -DCMAKE_SHARED_LINKER_FLAGS=-stdlib=libc++ \
  -DCMAKE_EXPORT_COMPILE_COMMANDS=ON -DDEPENDENCY_RESOLUTION=SYSTEM \
  -DBUILD_STANDALONE=ON -DBUILD_SHARED_LIBS=OFF -DCONFIG_AEMU=OFF -DENABLE_VKCEREAL_TESTS=ON
# First prove the fixed source's zero-count defect under hardening, using the existing GLES3 fixture.
run red-build 2400s cmake --build "$OUTPUT/build" --target OpenglRender_unittests --parallel 2
STAGE=zero-attachment-red
set +e
timeout --signal=TERM --kill-after=10s 120s env ANDROID_EMU_TEST_WITH_HOST_GPU=1 LIBGL_ALWAYS_SOFTWARE=1 \
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
run production-patch-check 30s git -C "$OUTPUT/source" apply --check "$PATCH"
run production-patch 30s git -C "$OUTPUT/source" apply "$PATCH"
check_sha "$OUTPUT/source/$PRODUCTION" e7864041afccb6236d70c62861e149048754e9e6c261ebd6630d31998045f008
run green-build 2400s cmake --build "$OUTPUT/build" --target gfxstream_backend OpenglRender_unittests --parallel 2
run gles-regression 180s env ANDROID_EMU_TEST_WITH_HOST_GPU=1 LIBGL_ALWAYS_SOFTWARE=1 \
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
cp "$OUTPUT/build/libgfxstream_backend.so" "$OUTPUT/artifact/libgfxstream_backend.so"
readelf -h -n -d -Ws -C "$OUTPUT/artifact/libgfxstream_backend.so" > "$OUTPUT/derived-elf.txt"
nm -DC --defined-only "$OUTPUT/artifact/libgfxstream_backend.so" > "$OUTPUT/derived-exports.txt"
grep -Eq '[[:space:]]initLibrary$' "$OUTPUT/derived-exports.txt"
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
git -C "$OUTPUT/source" diff --binary > "$OUTPUT/applied-source.patch"
sha256sum "$OUTPUT/source/$PRODUCTION" "$OUTPUT/source/$TEST_SOURCE" "$OUTPUT/source/$X11_TEST_SOURCE" > "$OUTPUT/source-after.sha256"
cp "$OUTPUT/build/CMakeCache.txt" "$OUTPUT/build/compile_commands.json" "$OUTPUT/"
cp "$PATCH" "$TEST_PATCH" "$X11_TEST_PATCH" "$OUTPUT/artifact/"
# Retain all source/third-party licensing with the derived module, without shipping the SDK.
find "$OUTPUT/source" "$OUTPUT/googletest" -type f \( -iname 'license*' -o -iname 'copying*' -o -iname 'notice*' \) -not -path '*/.git/*' -print0 \
  | tar --null -T - -czf "$OUTPUT/artifact/upstream-notices.tar.gz"
check_sha "$BASE_LIB" "$BASE_SHA"
STAGE=host-module-built-and-tested
STATUS=host-module-built-and-tested
# This is a module/test result only. Root must wire the derived path into the original owned emulator,
# verify its actually mapped library, then run representative UI with unchanged App/API/GPU/quality.
