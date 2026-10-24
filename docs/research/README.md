[文档](../README.md) / 技术研究

# 技术研究与审计

阅读边界：本页保留固定来源、当时判断与未闭环记录，不能代替生产规格或实际验收。现行 Lite 为原生 Android + meow-rs、最低 API 33；共同界面和客户端功能以[双向契约](../shared/UI_CONTRACT.md)为准，仅固定 Rust 核心源码证明不支持的能力可暂缓。

研究结论是选型证据；生产行为以共享规格、固定源码和实际验证为准。以下资料仍用于追踪当前风险，不表示已经实施或通过验收。

- [Android 核心选择](android-core-decision.md)
- [独立维护耦合审计](independence-audit.md)
- [原生渲染能力](rendering/native-rendering-audit.md)
- [原生库选型](rendering/library-selection.md)
- [Backdrop 审计](rendering/backdrop.md)
- [Haze 审计](rendering/haze.md)
- [其他渲染方案](rendering/native-alternatives.md)

原始查询响应、临时对比脚本和完整运行日志存任务证据目录，不复制为正式文档。

---

相关入口：[研究入口](README.md) · [共享契约](../shared/UI_CONTRACT.md)
