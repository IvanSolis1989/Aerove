[文档](../README.md) → 开发维护

# 开发维护

面向两版维护者，从工作目录、工程规则和来源进入开发。这里集中提供导航，详细规则只在各自权威文件维护。

## 开始修改前

1. 阅读[仓库与文档归档规则](REPOSITORY_LAYOUT.md)，确定文件归属及执行目录。
2. 核对[项目上下文](../../.agents/project.md)与[工程约束](../../.agents/rules.md)。
3. 从[构建与验证命令](../../.agents/commands.md)选择与变更对应的检查；应用验收边界见[共同契约](../shared/ACCEPTANCE.md)。

标准版命令在 `apps/aerove/` 执行，Lite 命令在 `apps/aerove-lite/` 执行。仓库布局与维护工具从根目录进入。

## 架构、版本与来源

- [独立维护架构](INDEPENDENCE.md)：产品所有权、双产品边界与迁移顺序。
- [标准版与 Lite 独立版本规范](VERSIONING.md)：版本选择、安装身份和原产物推广。
- [上游来源与更新策略](upstreams.md)：固定源码、采用方式与唯一鸣谢清单。

## 协作与自动化

- [文档编写与视觉规范](DOCUMENTATION_STYLE.md)：统一模板、排版、来源保护与推送前后视觉验收。
- [Issue 表单与 AI 助手](issue-assistant.md)：反馈入口、模型权限和验证方式。
- [官方频道自动通知](TELEGRAM.md)：通知触发、附件、回执与恢复。
- [仓库工具](../../tooling/README.md)：布局检查、兼容更新流、提交与网站工具。

开发规范集中维护在 `.agents/`，此处使用链接，避免同一构建或验收规则出现多份副本。

## 相关下一步

[标准版工程](../../apps/aerove/README.md) · [Lite 工程](../../apps/aerove-lite/README.md)
