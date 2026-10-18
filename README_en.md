<!-- xclash-owned-readme -->
<!-- xclash-repository: IvanSolis1989/Aerove -->

# Aerove

[简体中文](README.md) · **English**

[![Latest release](https://img.shields.io/github/v/release/IvanSolis1989/Aerove?style=flat-square)](https://github.com/IvanSolis1989/Aerove/releases/latest)

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

Open **Tools → Theme → Theme colors** and choose one mode:

- **Solid theme color:** use the usual palette and adjust Liquid Glass independently.
- **Random gradient wallpaper:** select **1–3 colors**, then use **New wallpaper** to change the composition. Liquid Glass switches to Ultra; only None is disabled. Standard and Ultra remain selectable.

Switching back restores the ordinary palette and makes None selectable again. A manually selected glass quality is saved.
The gradient palette and composition also persist across restarts.
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

This repository **checks upstream once per hour**, selecting the highest version tag reachable from any branch, including prereleases.
**With no new version, it exits without committing, rebuilding or publishing an APK.** A new version is built and published only after validation,
using the upstream version plus `-X`, such as `0.9.1-pre.4-X`.
GitHub schedules may be delayed; [Releases](https://github.com/IvanSolis1989/Aerove/releases/latest) shows the available download.
README and other documentation-only changes do not start APK builds or cancel active builds.

## Report a problem

Open an [Issue](https://github.com/IvanSolis1989/Aerove/issues) with your app version, Android version and reproduction steps.
For theme problems, include brightness mode, palette and a screenshot. Remove subscription URLs and node credentials from logs.

[GPL-3.0](LICENSE)
