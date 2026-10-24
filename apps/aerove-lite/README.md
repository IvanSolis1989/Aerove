[Documentation](../../docs/README.md) → [Development](../../docs/development/README.md)

# AEROVE Lite

Build-root reference for the native Android product. Lite has not had its first public release; the pinned Meow migration still requires Rust runtime, Android and APK acceptance.

**On this page** · [Development](#development) · [Ownership and behavior](#ownership-and-behavior) · [Licenses](#licenses)

Independent Android application: Kotlin/Jetpack Compose, Backdrop and a pinned Rust/meow-rs core (migration in progress). The Flutter/Go Aerove
application remains a separate product. This directory is the actual Lite application; `../../experiments/aerove-lite-renderer`
is an earlier, frozen experiment and is not a dependency.

Android 13 (API 33) is the minimum. The production ABI is ARM64. The `x86_64` build exists for isolated emulator tests.
Development APKs use `dev.aerove.lite.dev`; the production identity is reserved as `dev.aerove.lite`. They do not replace
Aerove, share its storage/signature, or publish to its stable update feed. The white-background black-ribbon icon has
separate source assets.

## Development

The selected replacement is Meow v0.26.1 at `727ddcd06f592e2f12c5222c4de6cce328df808c`.
The application build and JNI entry now use `meow-native/`; the old `native/` directory remains a preserved
clash-rs checkpoint. Build integration does not establish complete runtime or release acceptance.
See the [migration record](../../docs/aerove-lite/core/meow-migration.md) for current gates.

Use JDK 17, Gradle 9.6.0, Android SDK/Build Tools 37, NDK 28.2.13676358 and Rust 1.99.0. Source and dependency pins are in
`meow-native/source.json`, `meow-native/Cargo.toml`, `meow-native/Cargo.lock` and the Gradle files. The upstream patches and mobile
embedding module retain the original engine license and provenance. Do not edit the generated lock to imitate a
successful dependency resolution.

Run the following commands from `apps/aerove-lite/` at the repository root.

On Linux, after installing the target with `rustup target add aarch64-linux-android`:

```sh
python3 meow-native/build.py --target aarch64-linux-android \
  --ndk "$ANDROID_SDK_ROOT/ndk/28.2.13676358"
```

This command produces the ARM64 library and its original build receipt under `meow-native/build/`; it does not
assemble an APK. The committed lock and build policy are verified and builds use `--locked`. APK packaging also
requires the isolated client helper, the original ABI receipts, signing and source identity. Follow the authoritative
[ABI build workflow](../../.github/workflows/aerove-lite-build.yaml) for those steps; an absent core is rejected.

`../../.github/workflows/aerove-lite.yaml` checks Kotlin separately, verifies the committed Cargo lock, builds the native bridge,
then tests an isolated API 33 emulator. The emulator test requires a private controlled HTTP fixture and VPN permission.
It checks forwarding from a different UID, actual core traffic counters, stop and restart. These tests do not authorize
starting a proxy on the developer's host and do not establish real-device power or memory performance.

The independent workflow produces development artifacts only, with the APK, source/tree/workflow identity, signing
fingerprint and SHA256 sums. It does not create a public Release. Emulator ABI artifacts are not production downloads.

## Ownership and behavior

- `LiteContract.kt` defines UI state and actions; the controller connects them to real repositories and core streams.
- `data/` owns private atomic profile/settings storage, subscription updates, compatibility validation and the authenticated
  loopback client. Invalid updates preserve the previous revision. Unsupported fields report their exact path.
- `vpn/VpnCoordinator.kt` owns latest start/stop intent. Sessions terminate before replacements open; unconfirmed
  termination blocks a second instance. The service also handles notification actions, revoke, boot and physical-network
  changes. Native code duplicates the TUN descriptor and owns only its duplicate.
- `meow-native/` owns the JNI registry, per-instance cancellation/join, protected sockets and bootstrap DNS, API/TUN readiness
  and runtime logging. Desktop restart/re-exec and global shutdown are unavailable to the Android UI.
- `ui/` owns three material tiers, shapes, native screens and lifecycle-bound visuals. Solid has no optical filter;
  frosted surfaces consistently blur; clear surfaces refract and disperse through Backdrop with a small contour adapter.

The agreed first release includes VPN, local/subscription profiles and updates, proxy groups and latency tests,
rule/global/direct modes, traffic/connections, logs, DNS, application routing, background operation, three materials and
Aurora. Expose only implemented, usable capabilities. Unsupported optional engine features belong in development records,
not disabled controls or roadmap pages in the application. Do not silently rewrite an incompatible subscription.

The native build, actual device behavior, protocol interoperability and rendering must pass before calling Lite complete.
The project's development status and remaining evidence are tracked outside the APK; source code alone is not acceptance.

## Licenses

The repository's GPL license remains applicable. The selected Meow sources retain MIT and their original copyright.
The preserved clash-rs sources retain Apache-2.0 and their NOTICE, including
the enabled TUIC feature's GPL3 condition. Backdrop and Shapes retain Apache-2.0. Copies of the principal notices are in
the APK's `assets/licenses/` and the native source's `licenses/`. The full corresponding source and exact dependency pins
must accompany any distributed production build.

## Related next steps

[Product documentation](../../docs/aerove-lite/README.md) · [Acceptance contract](../../docs/shared/ACCEPTANCE.md)
