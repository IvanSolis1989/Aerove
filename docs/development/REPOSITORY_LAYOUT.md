[文档](../README.md) → [开发维护](README.md)

# 仓库与文档归档规则

查找代码、规范或临时证据的归属时从本页开始；新增、移动与归档文件也按本页执行。本文是目录与文档生命周期的权威规则。

**本页导航** · [文件归属](#文件归属) · [文档生命周期](#文档生命周期) · [工作目录与变更规则](#工作目录与变更规则)

仓库按产品划分工程，按用途划分文档。根目录提供导航、许可证、协作与 CI 入口，产品自身保留完整的构建根目录。

```text
apps/
  aerove/             Flutter + 官方 Mihomo，包含所有平台、插件、测试和产品构建工具
  aerove-lite/        原生 Android + meow-rs（迁移中），包含 Android 工程、Rust 桥接和产品测试
docs/
  shared/             两版共同遵守的 UI、功能、性能及验收契约
  aerove/             标准版说明、默认值、网络行为、发行记录
  aerove-lite/        Lite 说明与固定 Rust 内核能力依据
  development/        架构治理、构建维护、上游来源和本归档规则
  research/           技术调研与审计结论，不替代生产规格或验证结果
  archive/            已被替代的历史资料，按原项目或主题保留来源
  assets/             被正式文档引用的静态图片与附件
  i18n/               仓库入口的其他语言版本
tooling/              仓库级检查、提交钩子、网站工具与停用工作流归档
website/              项目网站源码
experiments/          可丢弃的原型，不能作为已发布功能
.github/              当前生效的 CI、Issue 和发行模板
.agents/              开发约束与技能；.codex、.claude 保留工具自动发现入口
```

## 文件归属

- 仅一个产品使用的实现、资源、配置、测试、依赖锁和构建工具进入对应 `apps/` 工程。两版独立版本、包名、签名、数据与发行入口继续有效。
- 两版共享的是规格与测试向量。不得为了目录对称复制业务代码、引入另一版运行时，或让 Lite 在运行时读取 Flutter 文件。
- 固定的第三方源码放所属产品 `third_party/`，记录提交、原始与适配后哈希、最小改动及许可证。当前 Lite 的 Java 色彩算法闭包独立存放，布局检查校验其完整清单；原注释保留，业务代码不得借此目录规避质量规则。
- 一份规范只有一个权威文件；其他位置使用链接。`docs/shared/UI_CONTRACT.md` 是双向同步要求的入口，产品文档不能另写较弱例外。
- 源码生成物留在工具规定的位置。构建缓存、临时验证脚本、原始 CI 日志、候选截图放本机任务目录或 CI artifact，不能直接堆入 `docs/`。
- 正式文档中的图放 `docs/assets/`；应用运行资源留在产品工程。不能把测试生成的十二图当成手绘设计稿或真机性能结论。
- 历史资料移入 `docs/archive/` 时保留来源与适用版本，并在索引注明已被哪份文件替代。仍在阻断发行的当前审计不得通过归档掩盖。
- 停用的上游工作流位于 `tooling/archive/workflows/`，不放在 GitHub 自动发现目录。原有代码许可证和来源说明继续保留。
- 当前 Flutter 安装 Action 的上游复用副本位于 `.github/actions/flutter/`：安装脚本保持原样，仅把嵌套缓存 Action 固定到完整 SHA，满足仓库安全策略；来源和适配后哈希由布局检查核对。

唯一保留的旧文档地址是 `docs/CHANGELOG_Aerove.md`：已发行客户端会读取这个 raw URL。它由 `tooling/sync_update_feed.py` 从现行发行记录生成，禁止直接编辑；布局检查核对内容同步。新客户端读取新路径。此兼容入口有明确消费者，不是第二份权威文档。

## 文档生命周期

| 资料状态 | 存放位置 | 维护方式 |
|---|---|---|
| 两版共同的有效规格 | `docs/shared/` | 在原文件更新，由两版实现共同核对；产品文档只链接 |
| 当前产品行为与发行记录 | `docs/aerove/`、`docs/aerove-lite/` | 按产品维护；发行版本、来源与实际验收结果相对应 |
| 构建、目录和上游维护规则 | `docs/development/`；工程约束在 `.agents/` | 各自保留一个权威入口，避免重复抄写命令 |
| 尚需决策、实施或验证的研究 | `docs/research/` | 写明适用源码、结论和未闭环项；不能当成生产完成证明 |
| 已被替代、仅供追溯的资料 | `docs/archive/<来源或主题>/` | 保留来源，索引指向现行替代文件，不继续追加当前开发记录 |
| 本次运行日志、交接、候选截图 | 仓库外任务目录或 CI artifact | 在交付说明中给证据入口，提炼稳定结论后才更新正式文档 |

文档名称描述长期主题，不使用每轮日期或 `final`、`final2`、`latest` 后缀制造并行版本。日期属于正文或发行记录。
新增资料先查索引及同主题文件；只有独立的维护责任和读者用途才新建文件。主题变化时移动原文件并修复引用，
不同时留下“新目录版”和“旧目录版”。删除或归档前检查源码、已发行客户端及 CI 消费者。

## 工作目录与变更规则

标准版命令在 `apps/aerove/` 执行；Lite 命令在 `apps/aerove-lite/` 执行。GitHub Actions 的 checkout、artifact、cache 路径相对仓库根目录；`run` 的工作目录由各产品工作流显式指定。

GitHub 的 Workspace layout 工作流会持续检查入口、文档分类、本地链接及旧客户端兼容更新流，防止后续重新散落。

根目录可统一调用：

```sh
python tooling/workspace.py check
python tooling/workspace.py run aerove -- flutter analyze --no-fatal-infos
python tooling/workspace.py run aerove-lite -- gradle :app:testReleaseUnitTest
```

新增文档先在现有分类查找权威文件，优先更新。新增类别或根目录入口时同步修改文档索引、`tooling/workspace.py` 的布局检查及相关 CI 路径。禁止保留无维护价值的旧路径副本或通过符号链接伪装迁移完成。

本轮移动前已保存未提交成果和逐文件哈希；目录调整不代表未执行的 Go、Kotlin、Rust、设备或 APK 检查已经通过。

## 相关下一步

[开发维护入口](README.md) · [构建与验证命令](../../.agents/commands.md)
