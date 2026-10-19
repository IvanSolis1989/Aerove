<!-- xclash-owned-readme -->
<!-- xclash-repository: IvanSolis1989/Aerove -->

# Aerove

[简体中文](README.md) · **English**

Aerove is an independent fork of [FlClash](https://github.com/chen08209/FlClash). Thanks to the upstream authors and contributors for the foundation. It retains FlClash's proxy capabilities and improves personalization, long-list browsing, initial setup, and Android updates.

## What Aerove adds to FlClash

The baseline is the FlClash `v0.9.1` revision recorded by this fork. Material You, glass levels, continuous corners, proxy groups, and connection monitoring already come from upstream; the following describes Aerove's extensions and optimizations. See the [Aerove changelog (Chinese)](docs/CHANGELOG_Aerove.md) for version-specific fixes, pending changes, and release scope.

### A flowing wallpaper you can personalize

- Generate a gradient wallpaper from 1–3 colors and tap **New wallpaper** to change its composition. The palette and composition persist. Automatic rotation becomes due at 05:00 local time each day and applies silently in the background without interrupting browsing.
- Aurora wallpaper and Ultra Glass are the defaults. Switching to an ordinary theme color restores the regular palette; glass strength can be adjusted separately. Brightness can follow the system or be selected manually, while high-contrast and pure-black dark modes keep opaque surfaces.
- Glass extends across dashboards, proxy groups, cards, buttons, selectors, fields, navigation, menus, popups, the editor, and forms. Text, icons, and interaction feedback coordinate with the background while status messages retain distinct colors.
- Fonts come from those available on your device; Aerove does not bundle MiSans. Devices without full glass support use a simpler frosted appearance.

### Easier continuous browsing

- Scrolling down compacts the floating bottom dock, keeping icons the same size while labels fade to leave more room for content. Scrolling up, returning to the top, or changing pages restores it.
- With many proxy groups collapsed, only nearby titles are prepared. Entries appear when expanded, with expand/collapse animations retained, reducing waits in long lists. Scrollbars stay out of the way and show a position hint while dragging.
- Group icons avoid repeating emoji from group names, and the active exit can show a country or region flag. The dashboard centers on the active outbound; connection, request, and DNS records use consistent text hierarchy and themed presentation.
- When valid quota data is available, a translucent fill and percentage show subscription usage. Warning colors change near or above the limit; the fill is hidden without valid quota data.

### Dynamic effects with less repeated work

- Glass panels share background processing for a consistent view of the same wallpaper. Covered areas pause unnecessary effects instead of repeatedly drawing what cannot be seen.
- Wallpaper updates adapt to interaction and app visibility, pausing in the background. Returning to the app retains the current image before motion resumes.
- Only a few likely icons load in advance, and groups share node information. Rebuildable graphics caches can be reclaimed and restored when the app returns to the foreground. These changes reduce repeated work without promising a particular memory reduction, frame rate, or battery saving.

### A bundled script for easier setup

- An integrity-checked Smart-Config-Kit script is available offline. New ordinary subscriptions, local files, and linked profiles use it by default. Existing and restored profiles, selected scripts, and custom profiles keep their choices; deleting the script does not reinstall it.
- The Scripts page shows its source and supports viewing, editing, copying or opening the source URL, and manual updates. Trying again during an update does not start another one. Script and subscription updates are managed separately.
- Initial Android setup provides separate VPN, system proxy, IPv6, and DNS defaults to reduce manual setup. DNS bootstrap uses only the four encrypted addresses below; the script maintains business resolvers, routing, and fallback. Custom DNS is preserved; unchanged upstream defaults without explicit override selections are migrated.

### Aerove identity and Android updates

- A silver flowing-ribbon mark identifies Aerove across the app, notifications, and desktop tray. Desktop window titles and startup entries use Aerove's name, with additional Spanish, French, and Korean text.
- When the Android About page finds an update, confirm to download the device-appropriate APK through the system download manager; failed downloads can be retried. Packages currently target ARM64 devices running Android 7.0 or newer; desktop features vary by operating system.
- Official upstream stable and `-pre.N` tags are validated before synchronization while preserving Aerove's changes. Releases include source information and checksums to help identify your download. See the download section and changelog for versions and signing details.

Before first use, read and accept the in-app disclaimer: the software is for non-commercial and lawful use, does not provide nodes, subscriptions, or network access, and third-party content remains the user's and its provider's responsibility. The software is provided as is, without a guarantee of error-free operation or fitness for a particular purpose. Refer to the full in-app disclaimer.

[![Latest release](https://img.shields.io/github/v/release/IvanSolis1989/Aerove?style=flat-square)](https://github.com/IvanSolis1989/Aerove/releases/latest)

## Interface preview

Light and dark views with the same palette, rendered from the actual application in an offline state. [Render details](docs/screenshots/render-info.json)

| Light | Dark |
| --- | --- |
| <img src="docs/screenshots/readme-light.png" alt="Aerove light Liquid Glass interface" width="300"> | <img src="docs/screenshots/readme-dark.png" alt="Aerove dark Liquid Glass interface" width="300"> |

## Download and installation

**[Download Android APK](https://github.com/IvanSolis1989/Aerove/releases/latest/download/Aerove-android-arm64.apk)** · [All releases](https://github.com/IvanSolis1989/Aerove/releases)

Requires **Android 7.0 or newer and ARM64 (arm64-v8a)**. Allow installation from your download source and install the APK.
Back up your configuration before upgrading. If an early test build has a conflicting signing key, back up, uninstall it, then install the new APK.
Release assets include `SHA256SUMS` and `BUILD_INFO.json` for checking the file and its source revision.
APKs use Aerove's fixed test signing key and application ID `com.follow.clash.dev`; they differ from official FlClash-signed and Google Play packages.

## Subscriptions and scripts

1. Add a subscription URL on the configuration page, save it and update the profile.
2. Select the profile, check that the bundled **Smart-Config-Kit** script is associated, then start the connection.
3. Open **Advanced configuration → Scripts** to view its content and source URL, edit, copy or open the URL, or manually update the script.

New ordinary subscriptions and local profiles use the bundled script by default. Existing and restored profiles retain their choices.
Deleting the script prevents automatic reinstallation. Script source:

```text
https://raw.githubusercontent.com/IvanSolis1989/Smart-Config-Kit/main/FlClash/FlClash%28mihomo%29.js
```

## Theme settings

Fresh installations start with the animated gradient wallpaper and Ultra Liquid Glass enabled. Light and dark modes follow the system; you can switch to the ordinary UI at any time.

Open **Tools → Theme → Theme colors** and choose one mode:

- **Solid theme color:** use the usual palette and adjust Liquid Glass independently.
- **Random gradient wallpaper:** select **1–3 colors**, then use **New wallpaper** to change the composition. Liquid Glass switches to Ultra; only None is disabled. Standard and Ultra remain selectable.

Switching back restores the ordinary palette and makes None selectable again. A manually selected glass quality is saved.
The gradient palette and current composition persist across restarts.
**Automatic rotation policy:** rotation becomes due at 05:00 in your phone's local time each day and is applied only while the app is truly in the background. If you are using the app then, it waits until you leave it. If the app was not running at 05:00, it catches up on the first move to the background after that day's first launch. Launching, resuming, and brief inactivity during screenshots do not change the wallpaper on screen. The app is not woken just to rotate it. **New wallpaper** still applies immediately and counts for the current daily cycle. See the [changelog (Chinese)](docs/CHANGELOG_Aerove.md) for version history.
Brightness defaults to **system**, with explicit light and dark options at the top. High-contrast and dark pure-black modes use opaque surfaces.

## Default DNS

The bundled script provides routing and the selected DNS policy. System DNS is not appended. Bootstrap uses only these encrypted endpoints:

```yaml
default-nameserver:
  - https://223.5.5.5/dns-query
  - https://223.6.6.6/dns-query
  - https://8.8.8.8/dns-query
  - https://1.1.1.1/dns-query
```

Business resolvers, domain policies and fallback come from the script. Reset returns to these defaults.
VPN HTTP proxy, VPN/core IPv6 and application bypass default to off; DNS hijacking defaults to on.
On initial Android setup, VPN starts enabled and app access control disabled. Rule mode, unified delay, and TCP concurrent are enabled; keep-alive idle/interval are 30/15 seconds. You still start the connection yourself.
See [network defaults](docs/network-defaults.md) for the values.

## Deploy SubStore on Cloudflare

This new deployment uses [sub-store-cloudflare](https://github.com/realchendahuang/sub-store-cloudflare), which hosts its frontend and backend in your Cloudflare account.
Prepare Cloudflare and GitHub or GitLab accounts, and your own subscription URL.

### 1. Generate two tokens

Generate and save two different random values of at least 32 bytes each in a password manager, one for administration and one for downloads.
Alternatively, run this command with Node.js installed:

```sh
node -e "const{randomBytes:r}=require('node:crypto');console.log(r(32).toString('base64url'));console.log(r(32).toString('base64url'))"
```

### 2. Deploy

Open **[Deploy to Cloudflare](https://deploy.workers.cloudflare.com/?url=https://github.com/realchendahuang/sub-store-cloudflare)**, sign in and import the repository:

| Field | Value |
| --- | --- |
| Worker name | Your choice, such as `sub-store-cloudflare` |
| D1 database | Create a new database |
| `SUB_STORE_ADMIN_TOKEN` | First random value, saved as a Secret |
| `SUB_STORE_PUBLIC_DOWNLOAD_TOKEN` | Second random value, saved as a Secret |
| Build command | `pnpm run build` |
| Deploy command | `pnpm run deploy` |

Wait for a successful build and deployment, then save the Worker URL.

### 3. Open the management page and add a source

Append `/?token=<ADMIN_TOKEN>` to the Worker URL:

```text
https://sub-store-cloudflare.<YOUR_SUBDOMAIN>.workers.dev/?token=<ADMIN_TOKEN>
```

Create a Source using your subscription URL. Open the default `Daily` Collection and check that it includes the intended sources.

### 4. Import into Aerove

Copy the **Mihomo / Clash Meta** download link from the Collection card and add it on Aerove's configuration page.
Download links use the download token; the admin token is only for management. Update the profile and check that nodes appear.
Keep Aerove's bundled Smart-Config-Kit script and default DNS settings; the routing script runs inside Aerove.
Keep both tokens and full subscription URLs private.

For deployment help, see the project's [quick start](https://github.com/realchendahuang/sub-store-cloudflare/blob/main/docs/quick-start.md).

## Updates

This repository checks official upstream release tags hourly and validates changes before synchronization; scheduled runs may be delayed. Aerove display versions append `-X` to the upstream version. See the [Aerove changelog (Chinese)](docs/CHANGELOG_Aerove.md) for full release numbers and fixes, and [Releases](https://github.com/IvanSolis1989/Aerove/releases/latest) for the available download. Maintenance and build rules are documented in the [upstream sync guide (Chinese)](docs/upstream-sync.md).

## Report a problem

Open an [Issue](https://github.com/IvanSolis1989/Aerove/issues) with your app version, Android version and reproduction steps.
For theme problems, include brightness mode, palette and a screenshot. Remove subscription URLs and node credentials from logs.

[GPL-3.0](LICENSE)
