<!-- xclash-owned-readme -->
<!-- xclash-repository: IvanSolis1989/Aerove -->

# Aerove

[简体中文](README.md) · **English**

Aerove is a fork of [FlClash](https://github.com/chen08209/FlClash). It retains the upstream proxy-client foundation and adds a Liquid Glass interface, gradient wallpaper, dashboard styling, and Android defaults. The table below summarizes the main user-visible differences; exact behavior depends on platform, theme mode, and system capabilities.
Thanks to chen08209/FlClash and its contributors for the project foundation. Aerove is an independently released fork.

## Main differences from upstream FlClash

The upstream already provides Material You colors, Liquid Glass levels, continuous corners, proxy-group and record views, and connection monitoring. Aerove keeps that foundation and extends its coverage, wallpaper, interaction styling, defaults, and release workflow. The list below focuses on the fork's main user-visible additions and changes.

| Area | Aerove additions and changes |
| --- | --- |
| Theme and wallpaper | Seeded aurora gradient wallpaper, 1–3 color and composition controls, monthly rotation; fresh Android setup starts with aurora and Ultra Glass. |
| Glass UI and motion | Extends glass foreground/background treatment; shares wallpaper capture and blur caches, freezes covered areas, composites headers and sheets with their backgrounds, and adapts wallpaper updates to lifecycle and interaction. |
| Navigation and information | Floating dock compacts while scrolling; scrollbars fade after interaction. Dashboard, proxy exit, records, and subscription usage receive updated themed layouts. |
| Configuration and Android defaults | Bundles and verifies Smart-Config-Kit; updates DNS defaults and migrates old stock defaults; sets Android VPN, system proxy, IPv6, and DNS options independently for new installs. |
| Aerove identity and maintenance | Own app icon, translations, and desktop integration; pinned upstream source, sync verification, and ARM64 APK release flow. |

### Themes and pages

- Adds a seeded aurora gradient wallpaper that composes 1–3 selected colors. Users can regenerate the composition; the palette and current composition persist, and the app checks for monthly rotation while active. Fresh installs use this wallpaper and Ultra Glass; ordinary theme colors and glass quality remain separately adjustable. High-contrast and pure-black dark modes keep opaque surfaces.
- Redesigns the theme-color picker and provides system/manual brightness and font selection. Available fonts depend on the device; Aerove does not bundle MiSans.
- Extends glass foregrounds, text, icons, and interaction feedback across dashboards, proxy groups, cards, buttons, selectors, inputs, navigation, menus, popups, the editor, and form sheets. The glass levels, Material You foundation, and continuous corners already exist upstream; Aerove changes their coverage and composition.
- Clips proxy-group glass boundaries and pinned headers to their visible shapes; avoids showing an emoji twice beside a group icon. The active exit shows a country/region flag emoji.
- Uses consistent title, value, unit, and secondary-text hierarchy and colors in connection details and tables. This adjustment does not solve narrow-screen clipping by shrinking or reflowing text.

### Sheets and forms

- Bottom, snap, side, and form sheets default to a transparent page-wide barrier instead of dimming the background. The barrier still blocks taps to the page underneath and remains dismissible by tapping outside the sheet.
- With a glass header, no background is drawn until content scrolls underneath it. Once content passes the header, a single gradient glass blur appears and lets underlying text show through. The header background follows scroll position while glass capture reuses its existing frame cache.

### Rendering and motion

- Glass panes on a page share the wallpaper source and background capture; static-source blur results can be reused across panes. Captures use bounded atlases up to 2048 pixels on the long edge, with local regions scaled to fit.
- Glass areas covered by opaque headers, popups, or other surfaces can freeze their captured image; header underlays that should remain translucent use a plain background. Coverage edges account for device pixel ratio; sampling inside glass regions may use fewer pixels than the display resolution to bound capture size.
- Dynamic wallpaper updates pause while the page is inactive and preserve a visible frame when it returns. The wallpaper ticker runs at 30 Hz while idle, 15 Hz during scrolling or interaction, then returns to idle 350 ms after activity ends.
- These describe implementation and update cadence, not measured device power savings, guaranteed frame rates, or a confirmed root cause for dropped frames. Results depend on device, resolution, and graphics backend; environments without the full effect use simplified frosted/tinted rendering.

### Navigation, proxies, and dashboard

- The floating bottom dock narrows while scrolling down and expands when scrolling up, returning to the top, or changing pages. Icons keep their size while labels fade out.
- Proxy lists no longer keep a scrollbar visible at all times; it fades after scrolling and shows a position/rate hint while dragging long lists. Other scroll regions also use themed scrollbars.
- Aerove retains upstream proxy-group cards/tabs, connection/request/DNS tables, and connection monitoring. It changes their glass backgrounds, icon/text presentation, exit information, and the dashboard layout around the active outbound; it adds no proxy protocol or core monitoring data.
- Subscription cards add a liquid fill with an inset, curved meniscus clipped to the card outline. It appears only when the subscription supplies valid total and usage values; near-limit and over-limit use different warning colors. Without valid quota data, the fill is hidden.
- Connection records align endpoint, rule, proxy chain, and traffic text across list and detail views. DNS/request records use themed tables and empty states.

### Scripts, DNS, and first-run configuration

- Bundles Smart-Config-Kit `v6.0.20-flclash.21` from [Smart-Config-Kit](https://github.com/IvanSolis1989/Smart-Config-Kit), pinned to commit `6847cd225d70bc9eb6fdd9311d50ff53bc91981c` and checked against SHA-256 `d962c99703ca0c4fb0649541b81a7d24d94f2737ad3d83937a85a42e07f4ccc5`. The script is available offline in the app. Its page shows the source and supports viewing, editing, copying/opening the source, and manual updates without duplicate requests.
- Newly imported ordinary subscriptions, local files, and linked profiles use the bundled script by default. Existing profiles, selected scripts, and custom profiles are not overwritten. Deleting the script does not reinstall it automatically.
- New Android installs do not append system DNS; the VPN option is enabled, system proxy/bypass/IPv6 are disabled, and DNS hijacking is enabled. Core defaults include rule mode, IPv6 off, unified delay and TCP concurrent on, and keep-alive idle/interval values of 30/15 seconds. Users still start the proxy connection.
- The DNS bootstrap defaults to the four DoH addresses shown below; business resolvers, domain policies, and fallback come from the script. Resetting DNS restores these values. Existing DNS is migrated only while it still matches upstream's stock DNS and has no explicit override selection; customized values are not replaced.

### Identity, platforms, and releases

- Android uses Aerove adaptive, round, TV, and monochrome icons; disables system-enforced darkening of the app, and uses Aerove branding in service notification titles/channel names. Desktop window titles, tray menus, and startup integration are also adapted to the fork.
- Adds Spanish, French, and Korean translations. Android releases are ARM64 APKs for Android 7.0 and newer; desktop behavior depends on operating-system support.
- Upstream source is pinned in `tool/upstream-version.json`. The sync workflow selects a reachable official FlClash stable or `-pre.N` tag, merges it normally, and validates before publishing. APK versions append `-X` to the upstream tag (for example, `0.9.1-X`) and include `BUILD_INFO.json` and `SHA256SUMS`.
- Releases use this repository's fixed Android test signing key and package ID `com.follow.clash.dev`; they are not official FlClash-signed builds or Google Play releases. Back up configuration before upgrading; an early test build with a conflicting key may need to be uninstalled first.

Aerove is independently released from FlClash and thanks the upstream authors and contributors. Read and accept the in-app disclaimer before first use: the software is for non-commercial and lawful use, does not provide nodes, subscriptions, or network access, and third-party content remains the user's and its provider's responsibility. The software is provided as is, without a guarantee of error-free operation or fitness for a particular purpose. Refer to the full in-app disclaimer.

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
The gradient palette and current composition persist across restarts; while the app is active, the wallpaper changes automatically at a monthly check.
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

This repository **checks the official upstream hourly**, selecting only a valid stable or `-pre.N` tag reachable from an upstream branch.
**With no new version, it exits without committing, rebuilding or publishing an APK.** After validating a new source, the workflow merges it normally,
then verifies and publishes through the Android workflow. Before publication it checks that the build source still applies to the current `main`, preventing an older package from replacing a newer version. APK versions append `-X` to the upstream version: the current stable upstream `0.9.1` becomes `0.9.1-X`, while prerelease numbers remain, such as `0.9.1-pre.4-X`. Releases use this repository's fixed Android test signing key and include `BUILD_INFO.json` and `SHA256SUMS`.
GitHub schedules may be delayed; [Releases](https://github.com/IvanSolis1989/Aerove/releases/latest) shows the available download.
README and other documentation-only changes do not start APK builds or cancel active builds.

## Report a problem

Open an [Issue](https://github.com/IvanSolis1989/Aerove/issues) with your app version, Android version and reproduction steps.
For theme problems, include brightness mode, palette and a screenshot. Remove subscription URLs and node credentials from logs.

[GPL-3.0](LICENSE)
