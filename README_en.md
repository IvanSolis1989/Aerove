<!-- xclash-owned-readme -->
<!-- xclash-repository: IvanSolis1989/Aerove -->

# Aerove

[简体中文](README.md) · **English**

Aerove is an independent fork of [FlClash](https://github.com/chen08209/FlClash). Thanks to the upstream authors and contributors for the foundation. FlClash already includes Material You styling, glass levels, proxy groups, and connection records; Aerove extends the wallpaper and interface and changes some Android defaults and release behavior. Appearance may vary by device.

## What changes in Aerove

### Themes and text

- Choose 1–3 colors for the gradient wallpaper and tap **New wallpaper** to change its composition. Your palette and current wallpaper are saved. The wallpaper changes on the first day of each month while the app is open; if it is closed then, it changes the next time you open the app.
- Fresh installs start with the aurora wallpaper and Ultra Glass. Switching to an ordinary theme color restores the regular palette; glass quality can be changed separately. High-contrast and pure-black dark modes use opaque surfaces.
- Light/dark mode can follow the system or be selected manually. Font choices come from fonts installed on the device; Aerove does not include MiSans.
- Dashboards, proxy groups, cards, buttons, selectors, fields, navigation bars, menus, popups, the editor, and forms use coordinated background, text, icon, status, and touch-feedback colors. Devices without full glass support show a simpler frosted appearance.
- When several glass panels show the same background, the effect looks consistent across them and avoids repeating the same background work for each panel.

### Sheets, scrolling, and wallpaper motion

- Bottom, snap, side, and form sheets open without dimming the page. Tapping outside still closes a sheet, and the open sheet prevents taps from reaching controls below it.
- A glass header stays clear until content scrolls underneath; then one gradient glass layer appears and the text below remains visible. Covered areas pause their live light effects but still show a soft, blurred view of the text, icons, and interface. The glass boundary blends more smoothly and reduces sudden color patches while scrolling.
- If taking a screenshot briefly makes the app inactive, the current image stays on screen to avoid a black flash during the screenshot. When the app goes to the background, wallpaper motion pauses; returning to the foreground first shows the saved image, then resumes the animation.
- The wallpaper background updates 30 times per second while idle and 15 times per second during scrolling or interaction; this changes background updates only and does not limit page scrolling.
- Scrolling down narrows the bottom dock; icons stay the same size while labels fade out. Scrolling up, returning to the top, or changing pages restores the full dock.
- The proxy-list scrollbar hides when scrolling stops and shows a position hint while dragging.

### Proxies, records, and subscription usage

- Proxy-group edges align with pinned headers, reducing jagged edges and abrupt color patches while scrolling. Group-name emoji do not repeat beside the group icon; the active exit can show a country/region flag.
- When many groups stay collapsed, only titles near the screen are prepared, reducing waits while scrolling long lists. Group entries appear when expanded, and expand/collapse animations remain.
- Connection details use consistent title, value, unit, and secondary-text levels for endpoints, rules, proxy chains, and traffic. The dashboard centers on the active outbound. DNS, request, and connection records retain their upstream data and appear with Aerove's colors and styling.
- When valid quota data is available, subscription usage shows a translucent light-blue fill, percentage, and inset curved edge. Warning colors change near or above the limit. The fill is hidden when quota data is unavailable.

### Scripts and Android defaults

- Smart-Config-Kit `v6.0.20-flclash.21` is included in the app, passes an integrity check, and can be viewed offline. The Scripts page shows its source and lets you view, edit, copy, or open it, and update it manually; trying again while an update is in progress will not start another one. Source: [Smart-Config-Kit](https://github.com/IvanSolis1989/Smart-Config-Kit).
- Newly imported ordinary subscriptions, local files, and linked profiles use the script by default. Existing and restored profiles, existing script choices, and custom profiles keep their selections. Deleting the script does not reinstall it.
- A fresh Android install does not append system DNS. The VPN option starts enabled; system proxy, app bypass, VPN/core IPv6, and app access control start disabled; DNS hijacking starts enabled. Core defaults use rule mode with unified delay and TCP concurrent enabled. Keep-alive defaults are 30 seconds idle and 15 seconds interval. You still start the proxy connection yourself.
- DNS bootstrap uses the four encrypted addresses listed below; business DNS, domain routing, and fallback come from the bundled script. **Reset** restores these defaults. Existing DNS is migrated only if it still matches the upstream default and has no explicit override; custom DNS is preserved.

### Aerove identity, platforms, and releases

- Android uses Aerove regular, round, TV, and monochrome icons. Notifications show the Aerove name, and the system does not force the app into dark mode. Desktop window titles, tray menus, and startup entry points also use Aerove branding.
- Updates and extends the Spanish, French, and Korean translations. Android packages support ARM64 devices running Android 7.0 or newer; desktop features vary by operating system.
- Aerove follows reachable official FlClash stable or `-pre.N` version tags. When the upstream sync check finds no new upstream version, it makes no sync commit; updates to Aerove code can still trigger a build. Upstream changes are validated and merged normally; before release, the build is checked against the current `main`. APK versions append `-X` to the upstream version; the current stable version is `0.9.1-X`. Releases include `BUILD_INFO.json` and `SHA256SUMS`.
- APKs use Aerove's fixed test signing key and application ID `com.follow.clash.dev`; they are not official FlClash-signed or Google Play packages. Back up your configuration before upgrading. An early test build with a signing conflict may need to be uninstalled first.

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
The gradient palette and current composition persist across restarts. The wallpaper changes on the first day of each month while the app is open; if it is closed then, it changes the next time you open the app.
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
**When no new upstream version is available, the upstream sync check makes no sync commit; updates to Aerove code can still trigger a build.** After validating a new source, the workflow merges it normally,
then verifies and publishes through the Android workflow. Before publication it checks that the build source still applies to the current `main`, preventing an older package from replacing a newer version. APK versions append `-X` to the upstream version: the current stable upstream `0.9.1` becomes `0.9.1-X`, while prerelease numbers remain, such as `0.9.1-pre.4-X`. Releases use this repository's fixed Android test signing key and include `BUILD_INFO.json` and `SHA256SUMS`.
GitHub schedules may be delayed; [Releases](https://github.com/IvanSolis1989/Aerove/releases/latest) shows the available download.
README and other documentation-only changes do not start APK builds or cancel active builds.

## Report a problem

Open an [Issue](https://github.com/IvanSolis1989/Aerove/issues) with your app version, Android version and reproduction steps.
For theme problems, include brightness mode, palette and a screenshot. Remove subscription URLs and node credentials from logs.

[GPL-3.0](LICENSE)
