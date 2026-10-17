<div align="center">

<img src="assets/images/icon.png" alt="XClash" width="88">

# XClash

[XClash](https://github.com/IvanSolis1989/XClash) is a [GPL-3.0](LICENSE) fork of
[FlClash](https://github.com/chen08209/FlClash) by chen08209. Development currently focuses on Android,
with a soft aurora and glass visual direction. Upstream authorship and license notices are preserved.
The existing Android package ID is retained for compatibility in this phase.

Open **Tools → Theme → Random gradient wallpaper** to choose 1–3 colors and request a new pattern.
The palette and seed are saved with the configuration. Disable it to restore ordinary surfaces;
high contrast and dark pure-black mode keep opaque surfaces.
[Android build records](https://github.com/IvanSolis1989/XClash/actions/workflows/xclash-android.yaml)
· [Upstream sync guide](docs/upstream-sync.md)

**English** · [简体中文](README_zh_CN.md)

[![Release](https://img.shields.io/github/v/release/IvanSolis1989/XClash?style=flat-square&label=release)](https://github.com/IvanSolis1989/XClash/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/IvanSolis1989/XClash/total?style=flat-square&logo=github)](https://github.com/IvanSolis1989/XClash/releases)
[![License](https://img.shields.io/github/license/IvanSolis1989/XClash?style=flat-square)](LICENSE)
[![Upstream Telegram](https://img.shields.io/badge/Upstream_Telegram-channel-26A5E4?style=flat-square&logo=telegram&logoColor=white)](https://t.me/FlClash)

[Repository](https://github.com/IvanSolis1989/XClash) · [Download](#download) · [Changelog](CHANGELOG.md) · [Build from source](#build-from-source)

</div>

Upstream screenshots and architecture documentation are retained below; downloads refer to this fork.

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="snapshots/preview-dark.png">
    <img alt="FlClash dashboard on a MacBook and a phone" src="snapshots/preview.png" width="92%">
  </picture>
</p>

## Features

- **Android first** in this fork; upstream also supports Windows, macOS and Linux.
- **mihomo (Clash.Meta) core** with rule routing, proxy groups, latency tests, system proxy and TUN mode.
- **Profiles** from a subscription link or a file, with a built-in editor, override scripts, and custom rules,
  proxies and proxy groups.
- **Live views** of connections, requests, DNS queries and logs.
- **Material You design with Apple-style interaction**: dynamic color, light and dark themes, continuous corners,
  sheets that snap between heights, and layouts that adapt from phones to desktops.
- **Backup and restore** through WebDAV or a local file.
- **Platform touches**: a Quick Settings tile, per-app proxy and Android TV support on Android; a tray menu and
  global hotkeys on the desktop.
- **Open source and ad-free**, licensed under GPL-3.0.

## Download

Get this fork from [XClash Releases](https://github.com/IvanSolis1989/XClash/releases/latest), or download the [Android ARM64 APK](https://github.com/IvanSolis1989/XClash/releases/latest/download/XClash-android-arm64.apk) directly.

Only Android `arm64-v8a` APKs are published at present; other architectures and desktop platforms are not released.
These Android previews use a persistent test signing key; physical-device acceptance is pending.
Actions pages provide build records only.

<p>
  <a href="https://github.com/IvanSolis1989/XClash/releases/latest"><img alt="Get it on GitHub" src="snapshots/get-it-on-github.svg" height="56"></a>
</p>

## Usage

**Import a profile from a link.** Opening a link in this form imports the subscription into FlClash. The `clashmeta://`
and `flclash://` schemes work the same way.

```text
clash://install-config?url=<URL-encoded subscription link>
```

**Automate on Android.** Tasker, MacroDroid and similar apps can start, stop or toggle the proxy by starting an
activity with one of these actions:

```text
com.follow.clash.action.START
com.follow.clash.action.STOP
com.follow.clash.action.TOGGLE
```

From a computer, the same works over adb:

```bash
adb shell am start -a com.follow.clash.action.TOGGLE
```

## Build from source

You need [Flutter](https://docs.flutter.dev/get-started/install) 3.47 (release builds use 3.47.6),
[Go](https://go.dev/dl/) 1.27 and [Rust](https://rustup.rs/) installed through rustup. Each platform builds on its own
host, except Android, which builds anywhere.

```bash
git clone --recursive https://github.com/IvanSolis1989/XClash.git
cd XClash
flutter pub get
dart setup.dart android   # or windows, macos, linux
```

Packages land in `dist/`. The Go core and the Rust libraries are compiled as part of the Flutter build.

| Platform | Also needed |
| --- | --- |
| Android | Android SDK with the NDK. Add `--arch arm64` to build a single ABI. |
| Windows | GCC (MinGW-w64) for the core and [Inno Setup](https://jrsoftware.org/isinfo.php) 6 for the installer. |
| macOS | Xcode and Node.js. The script installs `appdmg` through npm. |
| Linux | Debian or Ubuntu. The script installs the build packages with apt and downloads `appimagetool`. |

Run `dart setup.dart --help` for the remaining options, such as `--targets` to build only some package formats.

## Support

Star this fork to support it; report bugs and feature requests in [XClash Issues](https://github.com/IvanSolis1989/XClash/issues).

Upstream community: [FlClash Telegram channel](https://t.me/FlClash).

<a href="https://star-history.com/#IvanSolis1989/XClash&Date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=IvanSolis1989/XClash&type=Date&theme=dark">
    <img alt="Star history" src="https://api.star-history.com/svg?repos=IvanSolis1989/XClash&type=Date" width="640">
  </picture>
</a>

## License

FlClash is released under the [GPL-3.0 license](LICENSE).
