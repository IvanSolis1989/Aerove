# AEROVE Lite

Read [workspace rules](../../AGENTS.md), [UI contract](../../docs/shared/UI_CONTRACT.md) and
[acceptance rules](../../docs/shared/ACCEPTANCE.md) first. This is the native Android + clash-rs product, minimum API 33.
Read [independent version rules](../../docs/development/VERSIONING.md) before changing versions or release packaging.

- UI and androidTest live under `app/`; the fixed Rust engine, replayable adaptation and JNI bridge live under `native/`.
- The Standard edition reference is `../aerove/`. Share specs and test vectors, never production runtime dependencies.
- Keep Kotlin UI/data, native engine and VPN lifecycle ownership explicit. One writer per file.
- Do not use missing client implementations as core capability exceptions, lower material quality or add duplicate polling.
- Preserve the white full-window launch background and black ribbon, separate package/signature/version and release feed.
- Compile the affected Kotlin and Rust code, run relevant JVM tests, then actual Android/AGSL tests and capture twelve
  dashboard combinations before claiming visual parity. Static source checks cannot substitute for device execution.
- Use the repository's Lite workflow for the actual Rust libraries and release APK. Do not bypass native packaging,
  signing, source provenance, functional or pixel checks to obtain a first release.
