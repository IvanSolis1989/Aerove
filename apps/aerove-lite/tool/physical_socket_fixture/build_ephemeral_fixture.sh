#!/usr/bin/env bash
# Build the separate-UID dependency only. Never accepts a production key.
set -euo pipefail
umask 077
[[ $# == 3 && -n ${JAVA_HOME:-} ]] || {
  echo 'Usage: JAVA_HOME=... build_ephemeral_fixture.sh ANDROID_JAR BUILD_TOOLS EMPTY_OUTPUT_DIR' >&2
  exit 2
}
jar=$(realpath -e "$1")
tools=$(realpath -e "$2")
out=$(realpath -e "$3")
[[ -d "$out" && -z $(find "$out" -mindepth 1 -maxdepth 1 -print -quit) ]] || {
  echo 'An existing, empty output directory is required' >&2; exit 2
}
[[ $(stat -c '%a' "$out") == 700 ]] || {
  echo 'Output directory must be private (0700)' >&2; exit 2
}
fixture=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
self="$fixture/src/dev/aerove/fixture/socketowner/PhysicalSocketService.java"
export PATH="$JAVA_HOME/bin:$PATH"
temp=$(mktemp -d "$out/.build.XXXXXX")
trap 'rm -rf -- "$temp"' EXIT
mkdir "$temp/classes" "$temp/dex"
javac --release 8 -cp "$jar" -d "$temp/classes" "$self"
mapfile -d '' bytecode < <(find "$temp/classes" -type f -name '*.class' -print0 | sort -z)
(( ${#bytecode[@]} > 0 ))
"$tools/d8" --lib "$jar" --min-api 33 --output "$temp/dex" "${bytecode[@]}"
[[ -s "$temp/dex/classes.dex" && ! -e "$temp/dex/classes2.dex" ]]
"$tools/aapt2" link -I "$jar" --manifest "$fixture/AndroidManifest.xml" \
  --version-code 1 --version-name lab-1 -o "$temp/unsigned.apk"
zip -qj "$temp/unsigned.apk" "$temp/dex/classes.dex"
"$tools/zipalign" -p -f 4 "$temp/unsigned.apk" "$temp/aligned.apk"
export AEROVE_QA_EPHEMERAL_PASS="$(openssl rand -hex 24)"
keytool -genkeypair -alias fixture -keyalg RSA -keysize 2048 -validity 7 \
  -dname 'CN=AEROVE Ephemeral Fixture QA' -keystore "$temp/ephemeral.p12" \
  -storetype PKCS12 -storepass:env AEROVE_QA_EPHEMERAL_PASS >/dev/null 2>&1
"$tools/apksigner" sign --ks "$temp/ephemeral.p12" --ks-key-alias fixture \
  --ks-pass env:AEROVE_QA_EPHEMERAL_PASS --v1-signing-enabled false \
  --v3-signing-enabled true --v4-signing-enabled false \
  --out "$temp/fixture.apk" "$temp/aligned.apk"
rm -- "$temp/ephemeral.p12"
unset AEROVE_QA_EPHEMERAL_PASS
"$tools/zipalign" -c -p 4 "$temp/fixture.apk"
"$tools/apksigner" verify --verbose --print-certs "$temp/fixture.apk" > "$temp/signature"
"$tools/aapt2" dump badging "$temp/fixture.apk" > "$temp/badging"
"$tools/aapt2" dump xmltree --file AndroidManifest.xml "$temp/fixture.apk" > "$temp/manifest"
python3 - "$temp" "$fixture" <<'PY'
import hashlib, json, re, sys
from pathlib import Path

sys.path.insert(0, sys.argv[2])
from verify_signed_pair import signer

root = Path(sys.argv[1])
signature, badging, manifest = [(root / n).read_text() for n in ("signature", "badging", "manifest")]
cert = signer(signature)
if (
    "Verified using v3 scheme (APK Signature Scheme v3): true\n" not in signature
    or not re.search(r"^package: name='dev\.aerove\.fixture\.socketowner' versionCode='1' ", badging, re.M)
    or "launchable-activity:" in badging
    or not re.search(r"android:testOnly\([^)]*\)=true$", manifest, re.M)
    or not re.search(r'android:name\([^)]*\)="\.PhysicalSocketService"', manifest)
    or re.findall(r"^\s*E: (\S+)", manifest, re.M)
       != ["manifest", "uses-sdk", "uses-permission", "queries", "package", "application", "service"]
):
    raise SystemExit("Fixture package identity or APK signing check failed")
apk = root / "fixture.apk"
(root / "FIXTURE.json").write_text(json.dumps({
    "packageName": "dev.aerove.fixture.socketowner",
    "apkSha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
    "signerSha256": cert,
    "signingIdentity": "one-use-fixture-only",
    "releaseAccepted": False,
    "deviceValidated": False,
}, indent=2) + "\n")
PY
mv -Tn -- "$temp/fixture.apk" "$out/fixture.apk"
mv -Tn -- "$temp/FIXTURE.json" "$out/FIXTURE.json"
test ! -e "$temp/fixture.apk" && test ! -e "$temp/FIXTURE.json"
