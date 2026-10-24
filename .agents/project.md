# Project Context

[Documentation](../docs/README.md) · [Development](../docs/development/README.md) · [Agent references](skills.md)

Product context, independent ownership and dependency constraints.

**On this page**

[Version Notes](#version-notes) · [Forked Dependencies](#forked-dependencies) · [Dependency Ceilings](#dependency-ceilings) · [Build Dependencies](#build-dependencies)

Paths and commands for the Standard edition are relative to `apps/aerove/`; run its commands there.
Repository governance and workflows remain at the repository root. See [workspace layout](../docs/development/REPOSITORY_LAYOUT.md).

AEROVE 标准版 (Standard edition, previously Full) is an independently maintained proxy client with a historical FlClash codebase. The current implementation uses
Flutter and a pinned Mihomo core on Android, Windows, macOS, and Linux. Material You from `material_ui` and the existing
custom visual system remain the production baseline. AEROVE Lite is the separate Android-native/Rust product in
`apps/aerove-lite/`, requiring Android 13 or later. Both products share this repository, with separate versions and release
entries. Share specifications and test vectors, not platform runtimes; keep package identity, data and release feeds
separate. All shared client features must be implemented and verified before release. Source-proven core capability
exceptions belong in development records, not placeholder or disabled controls;
unsupported imported configuration still requires an explicit compatibility error. Its icon uses a white background
and black ribbon derived from the Standard edition, without replacing the Standard edition's icon.

Both products must have identical shared UI, interaction and client functionality, including all future refinements in
either direction. Only a capability proven unsupported by the pinned Rust core may be deferred. Incomplete client code,
missing wrappers or a first-release deadline are not product exceptions. Follow the
[binding shared contract](../docs/shared/UI_CONTRACT.md).

The long-term Android objective is for the native product to replace the current Android application after measured
energy efficiency, functionality, visual quality and reliability justify migration. Keep both products separate during
development; preserve the existing stable app and desktop support. The selected Rust core is meow-rs; its [migration](../docs/aerove-lite/core/meow-migration.md) is in progress, not proof of
the most efficient core. Compare cores on the same native frontend and workload before making that claim.

Product versions belong to Aerove, not to the latest FlClash tag. Upstream work is a reference and requires a scoped
change, source attribution and product validation; never automatically merge it or resume a paused integration without
current explicit user authorization. Both products use [independent version rules](../docs/development/VERSIONING.md).
Preserve existing licenses, signing identity and user data. See
`docs/development/INDEPENDENCE.md` and `docs/development/upstreams.md` for ownership and migration boundaries.

## Version Notes

- Flutter stays on the 3.47.x line. Release CI pins 3.47.6 and is the source
  of truth for release builds; `.fvmrc` and local SDKs may sit on another 3.47
  patch. The editor in `plugins/code_forge` came from `code_forge` 10.14.0, which
  needs Dart 3.13.2, so the CI pin cannot go below Flutter 3.47.2.
- Before moving the pin, walk `flutter-upgrade.md`: it lists the workarounds tied to
  the current SDK, such as Impeller disabled on the Intel macOS release build.
- The Dart SDK lower bound in `pubspec.yaml` sets the package language
  version. `flutter analyze` excludes `lib/**/generated/**`, so a raise is
  proven by `flutter test`, which parses the generated code, not by analyze.

## Forked Dependencies

One historical dependency in `pubspec.yaml` remains pinned to a chen08209 fork by commit SHA. It is an external
dependency of this independently maintained repository. Preserve its source attribution and validate a scoped pin
change against the recorded upstream blocker.

Each entry records what the fork changes and what has to be true before it can go
back to the published package, so a future upgrade does not have to rediscover it.
Re-verify a fork by diffing its pub cache checkout against the published version
of the same number:

```bash
diff -ru ~/.pub-cache/hosted/pub.dev/<name>-<version> ~/.pub-cache/git/<name>-<sha>
```

The `window_manager` fork (`chen08209/window_manager`, tag `v0.5.1-flclash.3`)
was replaced by the in-repo `plugins/window` package; the behaviors it carried
(work-area maximize with a hidden title bar on Windows, geometry restore on
re-map on Linux, corner preference, terminate and activate events) live there
now, see the window plugin section of `architecture.md`.

`launch_at_startup` — `chen08209/launch_at_startup`, version 0.5.1.

- Migrates `win32_registry` from `^2.0.0` to `^3.0.3`, which is a breaking rename
  across the whole Windows implementation (`Registry.openPath` → `CURRENT_USER.open`,
  `createValue` → `setValue`, `getStringValue` → `getString`).
- This one is not optional while it lasts: FlClash depends on `win32_registry: ^3.0.3`
  directly, and upstream's `^2.0.0` constraint cannot co-resolve with it.
- Drop the fork when upstream publishes a release that accepts `win32_registry` 3.x.

The `yaml_writer` fork (`chen08209/yaml_writer`) was replaced by
`lib/common/yaml_writer.dart`. Its key quoting let keys such as `!a`, `'a` or
`0x1F` reach the Core's yaml.v3 as a parse error or a non-string; a merge key
`<<` stays plain on purpose, because `package:yaml` keeps it as an ordinary key
and the Core applies the merge.

The `code_forge` fork (`chen08209/code_forge`) is frozen: its Dart code now lives
in the in-repo `plugins/code_forge` package and its Rust code in the `editor/`
module of `plugins/rust_api`, both trimmed to what FlClash uses.
`plugins/code_forge/README.md` records what was kept and what was removed;
upstream's MIT notice stays in `plugins/code_forge/LICENSE`.

## Dependency Ceilings

`flutter pub outdated` lists only upstream-held entries, so treat them as
resolved-until-the-ceiling-moves rather than as debt:

- `intl` is intentionally unbounded (`any`) and `material_color_utilities` is
  resolved by the Flutter SDK; neither is a bound this repository sets.

The `riverpod` set, `build_runner`, `drift_dev` and `intl_utils` resolve to their
latest releases. Two are held back:

- `freezed` 4.0.2 needs `analyzer` 14, while `intl_utils` 2.8.16 still requires
  `analyzer` ^13. It moves once `intl_utils` accepts analyzer 14.
- `test` 1.32.0 needs `test_api` 0.7.14, and `flutter_test` on Flutter 3.47 pins
  0.7.12. It moves with the SDK.

When an upgrade fails version solving, record the blocker here with
what has to change upstream before the pin can move, so the next
`flutter pub outdated` run does not rediscover it.

`_initDynamicColor` in `lib/bootstrap.dart` reads the `dynamic_color` method
channel directly and decodes the palette list into a `TonalPalette`, instead of
going through `DynamicColorPlugin.getCorePalette()` and its deprecated
`CorePalette` return type. Everything downstream sees `DynamicColorSeeds`, so a
non-deprecated plugin accessor changes only that one function.

## Build Dependencies

Linux:

```bash
sudo apt-get install libayatana-appindicator3-dev
```

Windows:

- GCC and Inno Setup.

macOS:

```bash
npm install -g appdmg
```
