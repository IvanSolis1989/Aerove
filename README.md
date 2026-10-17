<!-- xclash-owned-readme -->
<!-- xclash-repository: IvanSolis1989/XClash -->

# XClash

**简体中文** · [English](README_en.md)

[![Release](https://img.shields.io/github/v/release/IvanSolis1989/XClash?style=flat-square)](https://github.com/IvanSolis1989/XClash/releases/latest)
[![License](https://img.shields.io/github/license/IvanSolis1989/XClash?style=flat-square)](LICENSE)

XClash 是基于 [FlClash](https://github.com/chen08209/FlClash) 开发、优先面向 Android 的代理客户端，
以淡雅极光底图与轻玻璃表面为视觉方向。本分支目前重点开发 Android 界面，保留上游 Flutter 应用结构和
mihomo 集成；外观调整不替换代理内核、路由引擎或服务生命周期。

## 下载与安装

请从本仓库的 [Releases](https://github.com/IvanSolis1989/XClash/releases/latest) 下载最新的
**Android ARM64 预览版**，也可使用固定的
[APK 下载链接](https://github.com/IvanSolis1989/XClash/releases/latest/download/XClash-android-arm64.apk)。
发布资产同时包含 `SHA256SUMS` 和 `BUILD_INFO.json`，用于核对文件校验和、源码版本与构建来源。

目前只发布 `arm64-v8a`，面向 **Android 7.0 及以上**；没有 XClash 桌面端、F-Droid 或 Homebrew 安装包。
APK 由本分支 CI 构建；[Actions](https://github.com/IvanSolis1989/XClash/actions/workflows/xclash-android.yaml)
用于查看构建日志与排查产物，安装请使用 Releases。

当前预览版使用固定的**测试签名**，包 ID 为 `com.follow.clash.dev`，尚未完成真机验收。
早期仅在 Actions 提供的 APK 使用临时签名，升级到 Release 时，Android 可能要求先卸载旧版；
请先备份配置。本地构建也可能使用不同签名，不能保证直接覆盖 CI 发布的版本。

## 外观与操作

进入 **工具 → 主题 → 随机渐变底图**，启用底图后选择 **1–3 种颜色**，点击**换一张**生成新构图。
配色与随机种子随配置保存，重启后保持同一图案。这是静态、由种子确定的随机底图，不持续播放壁纸动画；
界面表面采用克制的半透明效果，兼顾文字与控件的可读性。

关闭开关可恢复普通主题表面。高对比度模式、深色纯黑模式会使用不透明表面，停用极光与玻璃效果。
现有导航、仪表盘布局和代理操作控件继续保留。

<p align="center">
  <img src="snapshots/xclash-theme.png" alt="XClash 主题设置：淡蓝与浅紫底图" width="300">
</p>

上图由实际 Flutter widget 测试按 Android 模式渲染，**不是真机截图**，不能作为真机渲染性能或 VPN 连通性证明。

## 从源码构建 Android

请对齐 [Android CI 工作流](.github/workflows/xclash-android.yaml)：**Flutter 3.47.6**、**Go 1.27.1**、
**JDK 17**、Android SDK 与 **NDK r28c（`28.2.13676358`）**，以及通过 rustup 管理的 **Rust 1.99.0**。
Rust 版本锁定在 [rust-toolchain.toml](plugins/rust_api/rust/rust-toolchain.toml)，Android SDK 版本见
[libs.versions.toml](android/gradle/libs.versions.toml)（compile/target SDK 37）。
确保 Flutter、Go、Java 和 cargo 可从 `PATH` 调用，并为 Flutter 配置 Android SDK。

```sh
git clone --recursive https://github.com/IvanSolis1989/XClash.git
cd XClash
flutter pub get
flutter build apk --release --target-platform android-arm64
```

APK 输出到 `build/app/outputs/flutter-apk/app-release.apk`。Flutter 原生构建 hook 与 Gradle 会编译所需的
Rust 库和 Android Go 内核；打包时保持原生构建 hook 启用。未配置正式签名容器时，本仓库使用 debug 签名，
并附加 `.dev` 包 ID 后缀。CI 从仓库 secret 恢复固定测试签名，其私有签名容器不随源码分发。

Dart/UI 验证命令与原生 hook 的工具链要求见 [.agents/commands.md](.agents/commands.md)。提交代码前运行
`flutter pub get`、`flutter analyze --no-fatal-infos` 及相关 `flutter test` 测试。
Agent 在开发主机上启动应用时，必须使用 `--dart-define=SAFE_MODE=true`。

## 构建、发布与上游同步

- **Android CI：** `main` 推送或手动运行会验证生成文件、静态分析、UI/配置回归及 Android 单元测试，并构建
  ARM64 APK。满足条件的 `main` 构建成功后，由独立 Release 任务发布 APK 和来源文件；检查失败不发布新包。
  源码同步与 APK 发布分别运行，后续 APK 构建失败不会撤销已合并的源码更新。
- **上游同步：** 定时工作流在每小时 UTC 第 17 分钟检查 `chen08209/FlClash/main`；GitHub 调度可能延迟。
  使用普通 merge，在候选版本验证通过后才推进本分支 `main`。遇到冲突、验证失败或并发更新时保留原有 `main`。
- **分支文档：** 这两份 README 由 XClash 独立维护。同步时从合并前的 fork 版本保留 README，不导入上游正文。
  `tool/sync_fork_readme.py` 仅校正仓库派生链接与徽章；没有 XClash 自有文档标记的输入会被拒绝。

触发方式、工作流权限限制、签名、失败证据和手动恢复见[上游同步说明](docs/upstream-sync.md)。
自动合并不能保证未来的上游变更都符合本分支视觉设计，聚焦测试之外仍需人工界面验收。

## 项目结构

| 路径 | 职责 |
| --- | --- |
| `lib/manager/theme_manager.dart`、`lib/widgets/aurora.dart` | 主题集成与种子底图渲染 |
| `lib/views/`、`lib/widgets/` | 页面、主题控件及 Flutter 通用组件 |
| `lib/core/`、`android/`、`core/` | 内核集成、Android 服务生命周期与 Go 包装层 |
| `plugins/` | 本地 Flutter/原生依赖，包含 Rust 集成 |
| `test/`、`android/tests/` | Flutter 与 Android 回归测试 |
| `.github/workflows/`、`tool/` | 验证、打包、发布与维护自动化 |

## 反馈与贡献

XClash 的问题与功能建议请提交到[本仓库 Issues](https://github.com/IvanSolis1989/XClash/issues)。
请提供应用版本、Android 版本、复现步骤及是否启用极光底图；日志中应移除订阅凭据等隐私信息。
修改应保留上游内核/服务的职责边界，并验证受影响行为。开发前阅读 [AGENTS.md](AGENTS.md) 及其链接的项目规则。

## 许可与上游归属

XClash 按 [GPL-3.0](LICENSE) 分发，基于 chen08209 及贡献者维护的
[FlClash](https://github.com/chen08209/FlClash)，沿用 [mihomo](https://github.com/MetaCubeX/mihomo) 生态。
保留上游版权与许可声明，各依赖继续适用自身许可证。本分支的界面与发布自动化由本仓库维护，
XClash 安装包不是 FlClash 官方发行版。
