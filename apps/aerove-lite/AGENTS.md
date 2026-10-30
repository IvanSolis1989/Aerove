# AEROVE Lite

[Documentation](../../docs/README.md) · [Development](../../docs/development/README.md) · [Agent references](../../.agents/skills.md)

Lite development entry point: shared Flutter client UI, native Android platform integration, fixed-core migration and acceptance boundaries.

Read [workspace rules](../../AGENTS.md), [UI contract](../../docs/shared/UI_CONTRACT.md) and
[acceptance rules](../../docs/shared/ACCEPTANCE.md) first. Lite uses the shared Flutter client UI with native Android platform integration and meow-rs, minimum API 33. The migration is in progress; follow the
[fixed-core migration record](../../docs/aerove-lite/core/meow-migration.md).
Read [independent version rules](../../docs/development/VERSIONING.md) before changing versions or release packaging.

- The shared Flutter client UI has one canonical source at `../../packages/aerove_client/` (Dart package `fl_clash`); do not continue reproducing it in Compose or invent a parallel Lite UI tree. The Lite Flutter host is still being connected. Keep `app/` for Android platform integration and androidTest, and `meow-native/` for the candidate Meow bridge. Preserve the former Compose UI and old `native/` checkpoint as recoverable history; the UI decision does not switch the production core or establish acceptance.
- The Standard edition reference is `../aerove/`. Reuse the shared Flutter client runtime package and assets; keep Mihomo and meow-rs cores, native platform lifecycles, and package/signing/version/update identities independent. Lite must not package or load Mihomo.
- Keep the former Compose UI frozen as a recoverable checkpoint. Maintain Kotlin platform/data, native engine and VPN lifecycle ownership explicitly. One writer per file.
- Do not use missing client implementations as core capability exceptions, lower material quality or add duplicate polling.
- Preserve the white full-window launch background and black ribbon, separate package/signature/version and release feed.
- For platform/core changes, compile affected Kotlin and Rust code and run relevant JVM tests. For shared client UI, validate the shared Flutter implementation on Lite Android and capture the twelve dashboard combinations from actual Android Flutter rendering before claiming visual parity. Static source checks cannot substitute for device execution.
- Use the repository's Lite workflow for the actual Rust libraries and release APK. Do not bypass native packaging,
  signing, source provenance, functional or pixel checks to obtain a first release.
