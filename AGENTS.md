# AEROVE workspace instructions

Read this entry point, then the applicable product instructions. The full product and Lite are independent build roots:

- [AEROVE](apps/aerove/AGENTS.md): Flutter and official Mihomo; run its commands in `apps/aerove/`.
- [AEROVE Lite](apps/aerove-lite/AGENTS.md): native Android and clash-rs; run its commands in `apps/aerove-lite/`.
- [Workspace and document policy](docs/development/REPOSITORY_LAYOUT.md): ownership, paths and archival rules.
- [Project context](.agents/project.md), [commands](.agents/commands.md) and [engineering rules](.agents/rules.md).

## Binding dual-product contract

All shared UI, interaction and client functionality must be identical in AEROVE and AEROVE Lite, including future changes
in either direction. Only a capability proven unsupported by the pinned Rust core may be deferred. Incomplete client code,
missing wrappers and release deadlines are not exceptions. Unsupported optional features stay in development records,
not placeholder or disabled app controls. Imported unsupported configurations must produce specific errors.

Refresh frequency, data collection, caching, subscription sharing, visibility and background suspension must follow the
same policy. Do not claim reduced functionality, extra polling or lower visual quality as an energy optimization.
The authoritative rules are [UI_CONTRACT](docs/shared/UI_CONTRACT.md) and [ACCEPTANCE](docs/shared/ACCEPTANCE.md).

## Safe implementation and delivery

- Preserve uncommitted work, collaborator ownership, licenses, signing identities and user data. Never reset, broadly
  stage unrelated files, force push or overwrite an active writer. Capture a stable checkpoint before moving owned files.
- Keep lifecycle ownership and latest-intent convergence in the existing platform owners. Host proxy launches must use
  safe mode; stop only processes started by this task, by recorded PID.
- Follow the applicable repo skill for UI, localization, core/platform and pre-commit quality work.
- Keep generated code generated. Report missing toolchains and actual failed checks; a heartbeat, static audit or source
  replay is not a successful APK, device render or performance test.
- Scope and authorization come from the user's current request. Do not block clear reversible work with repeated design
  approvals. Do not expand a change into unrelated cleanup.
- New documentation belongs in an existing `docs/` category; temporary reports and screenshots belong in external task
  evidence or CI artifacts. Keep one authoritative document and link to it.
- Run `python tooling/workspace.py check` after changing paths, document placement or repository entry points.
- Build each real application target once, promote the exact validated artifact, retain its original source/build identity
  and verify hashes, signing, package identity and release notes. Pure directory or policy changes do not justify silently
  rebuilding an already verified old application.
- No coding-agent Co-authored-by trailers. Repository comment-density and commit-message hooks remain binding.
