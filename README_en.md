<!-- xclash-owned-readme -->
<!-- xclash-repository: IvanSolis1989/XClash -->

# XClash

[简体中文](README.md) · **English**

[![Release](https://img.shields.io/github/v/release/IvanSolis1989/XClash?style=flat-square)](https://github.com/IvanSolis1989/XClash/releases/latest)
[![License](https://img.shields.io/github/license/IvanSolis1989/XClash?style=flat-square)](LICENSE)

XClash is an Android-first proxy client derived from [FlClash](https://github.com/chen08209/FlClash),
with a quiet aurora background and subtle glass surfaces. This fork currently develops the Android interface;
it retains the upstream Flutter application structure and mihomo integration. Changing the appearance does not
replace the proxy core, routing engine or service lifecycle.

## Download and installation

Download the latest **Android ARM64 preview** from this repository's
[Releases](https://github.com/IvanSolis1989/XClash/releases/latest), or use the stable
[APK download link](https://github.com/IvanSolis1989/XClash/releases/latest/download/XClash-android-arm64.apk).
Release assets also include `SHA256SUMS` and `BUILD_INFO.json` for checksums, source revision and build provenance.

Only `arm64-v8a` is published, targeting **Android 7.0 or later**. There are no XClash desktop, F-Droid or Homebrew releases.
The APK is built by this fork's CI; [Actions](https://github.com/IvanSolis1989/XClash/actions/workflows/xclash-android.yaml)
provides build logs and diagnostic artifacts. Use Releases for installation.

The preview uses a persistent **test signing key** and application ID `com.follow.clash.dev`.
Physical-device acceptance is still pending. Earlier Actions-only APKs used temporary signing keys;
Android may require uninstalling those before installing a Release. Back up your configuration first.
Locally built APKs may also have a different signing key and cannot necessarily update the CI build in place.

## Appearance and controls

Open **Tools → Theme → Random gradient wallpaper**. Enable the background, choose **1–3 colors**,
then use **New wallpaper** to request a new composition. The palette and random seed are saved with your
configuration, so the same pattern returns after restarting. It is a static seeded background, with no
continuous wallpaper animation. The surfaces use restrained translucency to keep controls readable.

Disable the option to return to ordinary theme surfaces. High-contrast mode and dark pure-black mode use opaque
surfaces instead of the aurora/glass treatment. Existing navigation, dashboard layout and proxy controls are retained.

<p align="center">
  <img src="snapshots/xclash-theme.png" alt="XClash theme settings with a soft blue and lavender background" width="300">
</p>

This preview is rendered from the actual Flutter widget test in Android mode. It is not a physical-device screenshot
and does not demonstrate on-device rendering performance or VPN connectivity.

## Network defaults for mainland China

New installations include and select the [Smart-Config-Kit FlClash script](https://github.com/IvanSolis1989/Smart-Config-Kit/tree/main/FlClash)
for new ordinary subscriptions and local profiles. Open **Advanced configuration → Scripts** to view its bundled
content and source URL, edit or copy the URL, open the source, or manually sync a newer version.
Existing profiles and restored data keep their selections.

DNS uses the user's approved policy: four encrypted DoH-over-IP bootstrap endpoints, with the plaintext fallback
and original client resolver defaults removed. Business DNS, domain policies, fallback and private-node adaptation
come from the script. The client appends no system DNS and overrides only `default-nameserver` for encrypted
bootstrap; all other script DNS fields remain intact. Reset returns to the same user defaults.
VPN HTTP proxy, VPN/core IPv6 and application VPN bypass default to off; DNS hijacking defaults to on.

See the [network default baseline](docs/network-defaults.md) for source versions, update behavior and validation limits.

## Build Android from source

Match the [Android CI workflow](.github/workflows/xclash-android.yaml): **Flutter 3.47.6**, **Go 1.27.1**,
**JDK 17**, Android SDK and **NDK r28c (`28.2.13676358`)**, plus rustup with **Rust 1.99.0**.
The Rust pin is in [rust-toolchain.toml](plugins/rust_api/rust/rust-toolchain.toml);
Android SDK versions are in [libs.versions.toml](android/gradle/libs.versions.toml) (compile/target SDK 37).
Ensure Flutter, Go, Java and cargo are available on `PATH`, and configure the Android SDK for Flutter.

```sh
git clone --recursive https://github.com/IvanSolis1989/XClash.git
cd XClash
flutter pub get
flutter build apk --release --target-platform android-arm64
```

The APK is written to `build/app/outputs/flutter-apk/app-release.apk`. Flutter's native build hooks and Gradle
build the required Rust library and Android Go core; keep the native build hooks enabled when packaging.
Without a configured release keystore, this repository uses debug signing with the `.dev` application ID.
CI restores its persistent test key from a repository secret; that private signing container is not included in source.

For Dart/UI checks, use the commands and native-hook requirements in [.agents/commands.md](.agents/commands.md).
Before submitting code, run `flutter pub get`, `flutter analyze --no-fatal-infos`, and relevant `flutter test` suites.
An agent running the application on a development host must use `--dart-define=SAFE_MODE=true`.

## Builds, releases and upstream updates

- **Android CI:** a `main` push or manual run validates generated sources, analysis, UI/configuration regressions
  and Android unit tests, then builds ARM64. Successful eligible `main` builds publish the APK and provenance files
  through a separate Release job. Failed checks do not publish a new APK; source synchronization and APK publication
  are separate runs, so an APK build failure does not undo an already merged source update.
- **Upstream synchronization:** the scheduled workflow checks `chen08209/FlClash/main` hourly at minute 17 UTC;
  GitHub may delay scheduled runs. It uses an ordinary merge, validates the candidate and only then advances this
  fork's `main`. Conflicts, failed validation or a competing update leave the existing `main` in place.
- **Fork documentation:** these two READMEs are maintained for XClash. Synchronization preserves the fork's README
  files from its pre-merge revision; it does not import upstream README content. `tool/sync_fork_readme.py` only
  updates repository-derived links and badges, and rejects documents without the XClash ownership marker.

See the [upstream synchronization guide](docs/upstream-sync.md) for triggers, workflow permission limits,
signing, failure evidence and manual recovery. Automatic merging does not guarantee that future upstream changes
preserve this fork's visual design; focused tests still need human UI review.

## Project layout

| Path | Responsibility |
| --- | --- |
| `lib/manager/theme_manager.dart`, `lib/widgets/aurora.dart` | Theme integration and seeded background rendering |
| `lib/views/`, `lib/widgets/` | Screens, theme controls and reusable Flutter components |
| `lib/core/`, `android/`, `core/` | Core integration, Android service lifecycle and Go wrapper |
| `plugins/` | Local Flutter/native dependencies, including Rust integration |
| `test/`, `android/tests/` | Flutter and Android regressions |
| `.github/workflows/`, `tool/` | Verification, packaging, publication and maintenance automation |

## Feedback and contributions

Report XClash bugs and feature requests in [this repository's Issues](https://github.com/IvanSolis1989/XClash/issues).
Include the app version, Android version, reproduction steps and whether the aurora option is enabled;
remove subscription credentials and other private data from logs. Changes should preserve upstream core/service
ownership and cover affected behavior. Start with [AGENTS.md](AGENTS.md) and the linked project rules.

## License and upstream attribution

XClash is distributed under [GPL-3.0](LICENSE). It is a fork of
[FlClash](https://github.com/chen08209/FlClash) by chen08209 and its contributors, using the
[mihomo](https://github.com/MetaCubeX/mihomo) ecosystem. Upstream copyright and license notices are preserved;
bundled dependencies retain their respective licenses. XClash's interface work and release automation are maintained
in this repository and are not official FlClash releases.
