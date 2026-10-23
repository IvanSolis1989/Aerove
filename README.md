<!-- xclash-owned-readme -->
<!-- xclash-repository: IvanSolis1989/Aerove -->
# AEROVE

独立维护的代理客户端。两个产品并行开发，共享界面与功能契约，分别构建和发行。

简体中文 · [English](docs/i18n/README.en.md)

| 产品 | 工程 | 技术路线 | 当前发行 |
|---|---|---|---|
| **AEROVE 标准版** | [apps/aerove](apps/aerove/) | Flutter + Mihomo；Android、Windows、macOS、Linux | [稳定版下载](https://github.com/IvanSolis1989/Aerove/releases/latest/download/Aerove-android-arm64.apk) |
| **AEROVE Lite** | [apps/aerove-lite](apps/aerove-lite/) | 原生 Android + clash-rs；Android 13+ | 开发与验证中 |

两版共享简约纯色、柔光磨砂、流光清透，以及普通主题和灵动极光；共同界面与客户端功能双向同步。
Lite 仅可暂缓固定 Rust 内核确实不支持的能力，未完成移植不构成发行例外。

## 导航

- [产品文档与开发指南](docs/README.md)
- [UI、功能与性能契约](docs/shared/UI_CONTRACT.md)
- [标准版与 Lite 独立版本规则](docs/development/VERSIONING.md)
- [AEROVE 更新记录](docs/aerove/CHANGELOG.md)
- [仓库目录与归档规则](docs/development/REPOSITORY_LAYOUT.md)
- [开发命令](.agents/commands.md) · [贡献约束](AGENTS.md) · [开源来源与鸣谢](docs/development/upstreams.md)

根目录只保留项目入口。应用源码在 `apps/`，当前文档在 `docs/`，仓库工具在 `tooling/`，原型在 `experiments/`。
原安装包的构建、来源和签名信息随发行资产保留；仓库独立重建不改变已验证安装包。

## 来源与许可

AEROVE 的历史代码来自 [FlClash](https://github.com/chen08209/FlClash)，感谢原作者及贡献者；现独立维护，
FlClash 作为参考上游。标准版核心直接跟踪 [Mihomo](https://github.com/MetaCubeX/mihomo) 的固定正式源码，
Lite 使用固定的 [clash-rs](https://github.com/ibigbug/clash-rs) 源码与可审计适配。

本仓库遵循 [GPL-3.0](LICENSE)。各依赖原有许可证、版权声明和来源记录保留在所属工程中。
