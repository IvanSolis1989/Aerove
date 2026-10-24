[Documentation](../../docs/README.md) → [Development](../../docs/development/README.md)

# AEROVE

Start here to locate the Standard edition source and prepare its pinned core. Product usage and release records live in the linked product documentation.

Flutter + official Mihomo product. This directory is its build root; platform projects, assets, local plugins, tests and
product tools remain together so their internal relative paths stay valid.

- [Product documentation](../../docs/aerove/README.md)
- [Build commands](../../.agents/commands.md)
- [Development rules](AGENTS.md)
- [Official Mihomo pin](core/mihomo-upstream.json) and [replayable adaptations](core/patches/)
- [Shared UI and performance contract](../../docs/shared/UI_CONTRACT.md)

## Prepare the build root

Run from `apps/aerove/` at the repository root:

```sh
flutter pub get
python tool/mihomo_source.py prepare
flutter analyze --no-fatal-infos
```

## Verification boundary

Native builds need the pinned Go/Rust/Android toolchains. Host development must use safe mode; source preparation and
static analysis do not establish a working VPN, Android build or release.

## Related next steps

[Independent version rules](../../docs/development/VERSIONING.md) · [Workspace layout](../../docs/development/REPOSITORY_LAYOUT.md)
