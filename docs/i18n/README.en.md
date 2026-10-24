<!-- xclash-owned-readme -->
<!-- xclash-repository: IvanSolis1989/Aerove -->
# AEROVE

[简体中文](../../README.md) · English

An independently maintained proxy client with two products in one repository.

| Product | Source | Runtime | Release |
|---|---|---|---|
| AEROVE Standard | [apps/aerove](../../apps/aerove/) | Flutter + Mihomo; Android, Windows, macOS and Linux | [Stable APK](https://github.com/IvanSolis1989/Aerove/releases/latest/download/Aerove-android-arm64.apk) |
| AEROVE Lite | [apps/aerove-lite](../../apps/aerove-lite/) | Native Android + meow-rs (in development); Android 13+ | In development and validation |

Shared UI, interactions and client features must remain identical in both directions. Only capabilities proven unsupported
by the pinned Rust engine may be deferred. Keep product versions, packages, signing identities and update feeds separate.

- [Standard preview releases](https://github.com/IvanSolis1989/Aerove/releases) (separate from stable latest)
- [Telegram channel](https://t.me/IvanSolis89) · [Community group](https://t.me/Olympus_Habitue)
- [Documentation](../README.md)
- [UI, function and performance contract](../shared/UI_CONTRACT.md)
- [AEROVE changelog](../aerove/CHANGELOG.md)
- [Workspace and document policy](../development/REPOSITORY_LAYOUT.md)
- [Development commands](../../.agents/commands.md)

The historical codebase comes from [FlClash](https://github.com/chen08209/FlClash); original attribution and licenses are
preserved. AEROVE now owns its architecture and release process. Mihomo and meow-rs are independently pinned upstreams. Lite has no public release yet.
This repository uses [GPL-3.0](../../LICENSE); dependencies retain their original notices.
