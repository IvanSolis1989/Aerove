# Shared product specifications

[Documentation](../README.md) / Shared specifications

This directory contains the authoritative shared specifications. Start with the product contract, implement the appearance parameters, and assess actual evidence against the acceptance requirements.

- [UI, functionality and performance contract](UI_CONTRACT.md): keep both products synchronized in both directions. Deferral requires evidence from the pinned Rust core that a capability is unsupported.
- [Appearance and three material modes](appearance.md): solid, soft frost and liquid clear, with classic color or aurora modes.
- [Delivery acceptance](ACCEPTANCE.md): boundaries for real functionality, rendering, artifacts and performance evidence.
- [Independent version policy](../development/VERSIONING.md): version each product by its own changes, not by copying upstream versions.

Shared specifications take precedence over research and historical documents. An incomplete client implementation cannot be relabeled as a core capability exception. Linked specifications retain their complete requirements while their English translations are pending.

<details>
<summary>简体中文</summary>

[文档](../README.md) / 共享规格

# 双产品共享规格

这里维护两版共同遵守的唯一规格。先确认共同契约，再按外观参数实现，最后用验收边界核对实际证据。

- [UI、功能与性能开发契约](UI_CONTRACT.md)：共同界面双向同步；仅有固定内核源码证明的能力缺失可暂缓。
- [外观和三档材质](appearance.md)：简约纯色、柔光磨砂、流光清透及灵动极光。
- [交付验收](ACCEPTANCE.md)：真实功能、渲染、产物与性能证据的边界。
- [两版独立版本规则](../development/VERSIONING.md)：按产品内容升级，不复制上游版本号。

共享规格优先于产品调研和历史资料；未完成客户端实现不能改列为内核能力例外。

---

相关入口：[共享规格入口](README.md) · [技术研究](../research/README.md)

</details>
