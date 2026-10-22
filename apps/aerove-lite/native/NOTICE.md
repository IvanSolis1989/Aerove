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
files are in upstream.patch; added modules are upstream/mobile.rs,
upstream/resources.rs, upstream/outbound.rs and upstream/telemetry.rs. Resource validation adds bounded
MRS/geo decoding and strict offline provider checks. The authenticated outbound
query uses the current core dispatcher, TLS certificate verification, bounded
responses and session cancellation. Its actual stream chain is returned through
a per-connection dispatcher callback; only related group picks are checked for
freshness, including automatic re-selection. The source order, six-second total
budget and three-source concurrency follow the Full product. Telemetry reuses the engine's existing 1 Hz
statistics tick and reports group revisions at selection, latency and provider
mutation points. Latency results share a five-second trailing debounce; effective
selection and provider changes remain immediate. The delay task is cancelled and
joined during mobile shutdown. upstream/outbound_tests.rs, upstream/outbound_route_tests.rs and upstream/telemetry_tests.rs are
compiled only by the bridge regression feature. prepare.py records each module hash with the
immutable source archive hash in AEROVE_PROVENANCE.json.

The bridge uses the committed Cargo.lock resolved by CI. Dependency changes must
retain a real cargo metadata lock and pass metadata/test/build --locked before a
distributable build is accepted. Source preparation alone is not build evidence.
