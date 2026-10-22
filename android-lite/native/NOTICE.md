AEROVE Lite native bridge

The engine is clash-rs v0.10.10, commit
36eec77463d1243ede287bf6b7197e38237948d5, from
https://github.com/ibigbug/clash-rs . Upstream authors and copyrights are retained.
The original LICENSE and NOTICE.md are reproduced in licenses/ and retained
in the fetched source. This build enables TUIC; upstream's NOTICE specifies GPL3
for that feature. The Aerove repository's GPL license and historical notices remain.

AEROVE modifies socket protection, protected bootstrap DNS, per-instance mobile
embedding, runtime log routing, controller lifecycle restrictions, strict provider
validation, API readiness, TUN readiness and task joining. Changes to original
files are in upstream.patch; the added module is upstream/mobile.rs. prepare.py
records both hashes with the immutable source archive hash in AEROVE_PROVENANCE.json.

Cargo.lock is initially the unmodified upstream lock as a dependency seed. The
first CI integration must resolve the bridge through cargo metadata, retain that
actual lock, and pass cargo metadata/test/build --locked before a distributable
build is accepted. An inherited lock alone is not bridge build evidence.
