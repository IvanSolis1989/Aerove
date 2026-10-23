# AEROVE Lite

工程位于 [apps/aerove-lite](../../apps/aerove-lite/)，采用原生 Android 界面，内核按用户决定迁移至固定 meow-rs v0.26.1，最低 Android 13。当前处于开发和验证阶段，生产入口尚未切换。

- [双产品开发契约](../shared/UI_CONTRACT.md)
- [验收规则](../shared/ACCEPTANCE.md)
- [独立版本规则](../development/VERSIONING.md)
- [当前 Meow 迁移与验收](core/meow-migration.md)
- [历史 clash-rs 内核审计](core/clash-rs-audit.md)
- [Android 核心选型研究](../research/android-core-decision.md)

Lite 的版本、签名、包名、更新入口与标准版隔离。未完成的客户端功能属于发行阻断，不在应用内放置占位或“尚未移植”项目。十二组合截图必须来自实际渲染，并由用户确认细节后再发行。
