# English-first documentation policy and coverage

[Documentation](../README.md) / [Development](README.md)

## Policy

Public entry points default to English. Chinese is an explicit language link or a closed `details` section; do not infer Chinese from browser or device locale. A saved explicit application language remains respected. Required privacy information and consent dialogs must remain visible and understandable; this presentation policy must not hide them.

Keep the actual features, regional rules, DNS endpoints, license notices, source obligations, source/build identities and acceptance restrictions intact. Remove nationality-targeted promotional claims, not technical configuration or historical evidence. Do not claim zero data collection, guaranteed anonymity, guaranteed availability, immunity from law, or legal advice. GPL-3.0 rights, including permitted commercial use, are not narrowed by a product notice.

## Completed in this isolated change

- Root README and its existing English URL are English; Chinese has a separate explicit link.
- Seven document landing pages provide English and explicitly expandable Chinese.
- The network-baseline document provides a faithful English record, identifies its obsolete pins and IPv6 values as historical, and retains the Chinese source. It does not silently change DNS/routing behavior.
- README synchronization writes English to the root and English mirror, and Chinese only to its selected page.
- Future Standard/Lite release renderers retain source/build acceptance bindings and generate English metadata. Existing Chinese change entries are explicitly expandable originals, **not claimed to be full English translations**. English-authored entries render directly.
- Standard/Lite application fallback is English when no supported language is selected. Explicit Chinese and other supported selections remain intact. Older “system/default” and unset preferences both serialize as `null`; those indistinguishable cases now fall back to English.
- The shared language selector is available before first-use consent as well as in Tools. Choosing a language does not accept or hide the terms. English and Chinese are the first two explicit options.
- About adds a brief localized responsible-use/no-service/no-warranty notice without changing the existing legal acceptance state or version.

This branch does not modify the already-running #350 source, released APKs, tags or signatures. Repository Description/About and existing GitHub Release bodies are external metadata: drafts and verification are separate from committing these files. Do not report them changed until read-back confirms it.

## Remaining work, not represented as translated

The following technical specifications, audits, changelogs and historical records retain original-language content. This is a phased migration, **not completion of all documentation**. Their constraints remain authoritative; a shorter English navigation summary does not replace them. Preserve upstream license/NOTICE files and immutable historical evidence. Recorded screenshots retain their original pixels and provenance; they have not been re-rendered as English UI or presented as current device evidence. Internal `.agents/` guidance, product subdirectory documentation and third-party/vendor documentation require a separate inventory; they have not been bulk-rewritten.

- `docs/CHANGELOG_Aerove.md`
- `docs/aerove/CHANGELOG.md`
- `docs/aerove/background.md`
- `docs/aerove/defaults.md`
- `docs/aerove-lite/CHANGELOG.md`
- `docs/aerove-lite/core/clash-rs-audit.md`
- `docs/aerove-lite/core/meow-migration.md`
- `docs/archive/flclash/CHANGELOG.md`
- `docs/archive/repository/README.before-layout.md`
- `docs/archive/repository/README.en.before-layout.md`
- `docs/development/DOCUMENTATION_STYLE.md`
- `docs/development/INDEPENDENCE.md`
- `docs/development/REPOSITORY_LAYOUT.md`
- `docs/development/TELEGRAM.md`
- `docs/development/VERSIONING.md`
- `docs/development/issue-assistant.md`
- `docs/development/shared-flutter-migration.md`
- `docs/development/upstreams.md`
- `docs/research/android-core-decision.md`
- `docs/research/independence-audit.md`
- `docs/research/rendering/backdrop.md`
- `docs/research/rendering/haze.md`
- `docs/research/rendering/library-selection.md`
- `docs/research/rendering/native-alternatives.md`
- `docs/research/rendering/native-rendering-audit.md`
- `docs/shared/ACCEPTANCE.md`
- `docs/shared/UI_CONTRACT.md`
- `docs/shared/appearance.md`

## Legal consistency follow-up

The existing formal application disclaimer contains a restriction on commercial use that may conflict with GPL-granted rights. It has not been silently amended, hidden, or presented as newly accepted. Review its legal text and acceptance migration separately before changing that contract.
