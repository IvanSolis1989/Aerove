<!-- xclash-owned-readme -->
<!-- xclash-repository: IvanSolis1989/Aerove -->
<p align="center">
  <img src="apps/aerove/assets/images/icon.png" width="112" height="112" alt="AEROVE 飘带图标">
</p>

<h1 align="center">AEROVE</h1>
<p align="center"><strong>看清连接，自在掌控。</strong></p>
<p align="center">独立维护的代理客户端 · Android 与桌面 · 两款独立产品</p>
<p align="center">
  <a href="#download">下载与版本</a> ·
  <a href="#start">开始使用</a> ·
  <a href="#appearance">界面预览</a> ·
  <a href="docs/README.md">文档</a> ·
  <a href="docs/i18n/README.en.md">English</a>
</p>

AEROVE 将连接状态、流量、出站模式和配置集中在仪表盘中，提供分层卡片与可编辑布局。
标准版采用 Flutter 与官方 Mihomo，保留 Android、Windows、macOS、Linux 工程；
Lite 采用原生 Android 与 meow-rs，面向 Android 13 及以上设备，正在开发验证中。

## <a name="download"></a>选择适合你的版本

### AEROVE 标准版

**现在开始使用，优先选择稳定版。** Android 提供 ARM64 APK；桌面平台的可用安装包以各次发行资产为准。

<a href="https://github.com/IvanSolis1989/Aerove/releases/latest/download/Aerove-android-arm64.apk"><img src="docs/assets/readme/download-stable.svg" width="248" height="72" alt="下载稳定版 APK，Android ARM64"></a>
<a href="https://github.com/IvanSolis1989/Aerove/releases"><img src="docs/assets/readme/browse-releases.svg" width="248" height="72" alt="浏览全部发行、版本说明与安装包"></a>

[稳定版 APK](https://github.com/IvanSolis1989/Aerove/releases/latest/download/Aerove-android-arm64.apk) · [全部发行](https://github.com/IvanSolis1989/Aerove/releases)

- **稳定版**：[0.9.1](https://github.com/IvanSolis1989/Aerove/releases/tag/v0.9.1%2B2026102203)，构建号 2026102203。
- **公开预览版**：[0.10.0-beta.6](https://github.com/IvanSolis1989/Aerove/releases/tag/v0.10.0-beta.6%2B2026102232)，构建号 2026102232。希望体验新变化时，请先阅读该版本说明。

预览版与稳定版属于同一标准版产品，安装前请确认版本与数据备份；稳定版入口持续指向稳定发行。
版本变化与兼容说明见[更新记录](docs/aerove/CHANGELOG.md)。

### AEROVE Lite

**尚未首发。** 原生 Android + meow-rs，最低 Android 13 / API 33。Lite 与标准版使用独立安装包、数据和更新入口；
共享界面与客户端功能按同一契约开发，完成验证后独立发行。

关注 [Lite 产品说明](docs/aerove-lite/README.md)与[开发进展](docs/aerove-lite/core/meow-migration.md)。当前没有公开 Lite 安装包。

## <a name="start"></a>从安装到连接

1. **安装应用**：从上方标准版发行页面选择安装包，阅读版本说明后安装。
2. **添加配置**：打开配置页面，导入自己准备的配置或订阅。AEROVE 不提供节点或订阅服务。
3. **选择出口并连接**：选择配置、节点与出站模式，启动连接；Android 首次连接时按系统提示授权 VPN。

连接后可在仪表盘查看状态与流量。默认网络策略和后台行为见[标准版说明](docs/aerove/README.md)。

## <a name="appearance"></a>让连接状态一目了然

**仪表盘与布局**　查看连接状态、网络速度、累计流量和当前配置，按自己的习惯编辑卡片布局。

**主题与材质**　简约纯色、柔光磨砂、流光清透，配合普通主题、灵动极光与浅深色模式。
两款产品以[共同外观规格](docs/shared/appearance.md)为目标，Lite 的完整呈现仍在验证中。

<p align="center">
  <img src="docs/assets/screenshots/readme-light.png" width="300" alt="AEROVE 标准版浅色仪表盘，离线未连接状态">
  <img src="docs/assets/screenshots/readme-dark.png" width="300" alt="AEROVE 标准版深色仪表盘，离线未连接状态">
</p>

<sub>展示图来自标准版真实 Flutter 组件树的离线渲染，呈现空配置、未连接状态；
不是 Android 真机截图，也不代表当前预览版或 Lite 的完整界面。来源与渲染信息见
<a href="docs/assets/screenshots/render-info.json">图片记录</a>。</sub>

## 按需深入

- **日常使用**：[标准版说明](docs/aerove/README.md) · [默认设置](docs/aerove/defaults.md) · [网络行为](docs/aerove/network-defaults.md) · [后台行为](docs/aerove/background.md)
- **跟进变化**：[标准版更新记录](docs/aerove/CHANGELOG.md) · [Lite 进展](docs/aerove-lite/README.md) · [独立版本规则](docs/development/VERSIONING.md)
- **参与开发**：[开发指南](docs/development/README.md) · [构建命令](.agents/commands.md) · [共享界面与功能契约](docs/shared/UI_CONTRACT.md)

完整目录见[文档导航](docs/README.md)。源码分别位于 [apps/aerove](apps/aerove/) 与 [apps/aerove-lite](apps/aerove-lite/)。

## 交流与参与

[Telegram 频道](https://t.me/IvanSolis89)　／　[Telegram 交流群](https://t.me/Olympus_Habitue)　／　[提交问题或建议](https://github.com/IvanSolis1989/Aerove/issues)

反馈问题时，请附产品名称、版本、系统和复现步骤；日志与配置请先脱敏。
提交改进前请阅读[贡献约束](AGENTS.md)与[开发指南](docs/development/README.md)。

## 来源与许可

感谢 [FlClash](https://github.com/chen08209/FlClash) 原作者与贡献者提供的历史代码基础。
AEROVE 现独立维护产品、版本与发行；标准版采用官方 Mihomo，Lite 选用 meow-rs。

完整来源、固定版本、采用方式和版权信息统一维护在[开源来源与鸣谢](docs/development/upstreams.md#开源来源与鸣谢)。
本仓库遵循 [GPL-3.0](LICENSE)，各依赖保留原有许可证与署名。
