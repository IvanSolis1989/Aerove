# AEROVE Lite

[Documentation](../README.md) / Lite

**Status: no first public release yet.** This page links product constraints, the current core migration and historical sources. Development UI and test candidates are not evidence of a completed release.

The build root is [apps/aerove-lite](../../apps/aerove-lite/). Lite uses the shared Flutter client UI, native Android platform integration and the pinned meow-rs core. Android 13 / API 33 is the minimum. The former Compose renderer is historical; Makepad is a future option to evaluate through measurements, not the current shipping UI.

## Specifications and delivery

- [Two-product development contract](../shared/UI_CONTRACT.md)
- [Acceptance requirements](../shared/ACCEPTANCE.md)
- [Independent version policy](../development/VERSIONING.md)

## Current and historical references

- [Current Meow migration and acceptance](core/meow-migration.md)
- [Historical clash-rs audit](core/clash-rs-audit.md)
- [Android core selection research](../research/android-core-decision.md)

## Release boundary

Lite has its own version, signature, package, data and update feed, independent of Standard. Missing client functionality remains a release blocker, not an in-app placeholder or a claim of unsupported core capability. Required screenshots must come from actual rendering; first-release visual details and the remaining acceptance gates still require verification.

<details>
<summary>简体中文</summary>

# AEROVE Lite

[文档](../README.md) / Lite

**状态：尚未首发。** 本页用于查找产品约束、当前内核迁移与历史来源；验证中的界面不作为已完成发行展示。

工程位于 [apps/aerove-lite](../../apps/aerove-lite/)，采用共享 Flutter 界面与原生 Android 平台接入，内核按用户决定迁移至固定 meow-rs v0.26.1，最低 Android 13。当前处于开发和验证阶段，Meow 已接入应用构建，首个公开版本尚未发行。

## 规格与交付

- [双产品开发契约](../shared/UI_CONTRACT.md)
- [验收规则](../shared/ACCEPTANCE.md)
- [独立版本规则](../development/VERSIONING.md)

## 当前与历史参考

- [当前 Meow 迁移与验收](core/meow-migration.md)
- [历史 clash-rs 内核审计](core/clash-rs-audit.md)
- [Android 核心选型研究](../research/android-core-decision.md)

## 发行边界

Lite 的版本、签名、包名、更新入口与标准版隔离。未完成的客户端功能属于发行阻断，不在应用内放置占位或“尚未移植”项目。十二组合截图必须来自实际渲染，并由用户确认细节后再发行。

</details>
