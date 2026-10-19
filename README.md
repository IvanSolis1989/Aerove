<!-- xclash-owned-readme -->
<!-- xclash-repository: IvanSolis1989/Aerove -->

# Aerove

**简体中文** · [English](README_en.md)

Aerove 是基于 [FlClash](https://github.com/chen08209/FlClash) 的独立分支，感谢上游作者与贡献者提供基础。FlClash 已提供 Material You 外观、玻璃档位、代理组和连接记录；Aerove 在此基础上扩展壁纸与界面表现，并调整 Android 默认设置和发布方式。不同设备上的效果可能不同。

## Aerove 的界面与使用变化

### 主题和文字

- 渐变壁纸可选 1–3 种颜色，点“换一张”可改变构图；配色与当前壁纸会保存。每月 1 日应用活跃时自动换图；当时未打开，则在下次进入应用时换图。
- 首次安装默认使用极光壁纸和极致玻璃。切回普通主题色可恢复原有配色；玻璃档位可单独调整。高对比度和纯黑深色模式使用不透明表面。
- 浅色/深色可跟随系统或手动选择；字体使用设备已有字体，Aerove 不附带 MiSans。
- 仪表盘、代理组、卡片、按钮、选择控件、输入框、导航栏、菜单、弹窗、编辑器和表单页的背景、文字、图标、状态和点击反馈使用协调的颜色。设备不支持完整玻璃效果时，会显示较简单的磨砂效果。
- 多块玻璃面板显示同一背景时，背景效果更连贯，并减少重复处理。
- `关于`页面标题上下完整显示，不再被裁切。
- 内存卡片中的 App 条目和圆点也会随壁纸配色变化，Core 仍使用独立颜色区分。

### 弹层、滚动和动态背景

- 底部、吸附、侧边和表单弹层顶部不再叠加额外的渐变模糊遮罩；标题、拖动条、单独的玻璃按钮和弹层主体玻璃仍保留。页面不整体压暗，点击弹层外仍可关闭，底层控件不会收到点击。
- 主页面顶部仍显示渐变玻璃；完全被遮住的区域会暂停下层实时光效，但透过玻璃仍可看到柔和模糊的文字、图标和界面，半透明过渡部分保持可见。玻璃边缘过渡更平滑，滚动时减少突兀色块。
- 背景光影流动更明显。截图时若应用进入短暂的非活动状态，当前画面会保留，避免截图瞬间闪黑。切到后台时壁纸动画暂停；回到前台后先显示原画面，再继续动画。
- 壁纸背景闲置时每秒更新 30 次，滑动或操作时每秒更新 15 次；这只调整背景更新，不限制页面滚动刷新。
- 向下滚动时底部导航栏变窄，图标大小不变、文字淡出；向上滚动、回到顶部或切换页面时恢复完整导航栏。
- 代理列表滚动条在停止滚动后隐藏；拖动时显示位置提示。

### 代理、记录和订阅用量

- 代理组边缘与吸顶标题贴合，滚动时减少边缘锯齿和突然出现的色块。组标题里的 emoji 不会与组图标重复显示，当前出口可显示国家/地区旗帜。
- 大量分组保持折叠时，只准备屏幕附近的组标题，减少长列表滑动时的等待；组内节点仍在展开后显示，展开和收起动画保留。相同节点信息由多个分组共用，图标只提前加载少量可能出现的内容，屏幕外项目不再等待加载；系统回收内存后，回到前台会自动恢复玻璃效果。
- 连接详情中的端点、规则、代理链和流量采用一致的标题、数值、单位和辅助文字层级。仪表盘围绕当前出口组织信息；连接、请求和 DNS 记录沿用上游数据，Aerove 调整其颜色和呈现。
- 有有效配额时，订阅用量显示浅蓝色半透明液面、已用比例和内凹边缘；接近或超过额度时显示不同提示色。没有有效配额时不显示液面。

### 脚本和 Android 默认设置

- 安装包内置 Smart-Config-Kit `v6.0.20-flclash.21`，无需联网即可查看，且安装包内置脚本会经过完整性校验。脚本页显示来源，可查看、编辑、复制内容或打开来源链接，也可手动更新；更新进行中再次操作不会重复发起。来源：[Smart-Config-Kit](https://github.com/IvanSolis1989/Smart-Config-Kit)。
- 新导入的普通订阅、本地文件和链接配置会自动关联脚本；已有及备份恢复的配置、已选脚本和自定义配置保留原选择。删除脚本后不会自动重新安装。
- 首次 Android 安装不追加系统 DNS；VPN 功能开关默认开启，系统代理、应用绕过、VPN/核心 IPv6 和应用访问控制默认关闭，DNS 劫持开启。核心默认规则模式，统一延迟和 TCP 并发开启；连接保持默认空闲 30 秒、间隔 15 秒。启动代理连接仍需用户操作。
- DNS 引导使用下方四个加密地址；业务 DNS、域名分流和 fallback 由脚本提供。“恢复默认”会还原这套 DNS。旧配置仅在仍使用上游默认 DNS、且没有明确覆盖选择时迁移；已自定义 DNS 会保留。

### 品牌、平台和发布

- Android 使用 Aerove 普通、圆形、电视和单色图标，通知显示 Aerove 名称；系统不会强制将应用改成深色。桌面窗口标题、托盘和开机启动入口也使用 Aerove 标识。
- 补充西班牙语、法语和韩语。Android 关于页检查到更新后，确认即可下载适配设备的 APK，并交由系统下载管理；下载失败可重试。安装包支持 Android 7.0 及以上的 ARM64 设备；桌面功能因操作系统而异。
- Aerove 跟随官方 FlClash 可达的稳定版或 `-pre.N` 版本标签；上游同步检查没有新版本时不会产生同步提交；Aerove 代码更新仍可触发构建。上游变更先验证再合并，发布前确认 APK 对应的代码仍适用于当前 `main`。版本号在上游版本后加 `-X`，当前稳定版为 `0.9.1-X`；安装包附 `BUILD_INFO.json` 和 `SHA256SUMS`。
- APK 使用 Aerove 固定测试签名，应用 ID 为 `com.follow.clash.dev`，不是 FlClash 官方签名或 Google Play 安装包。升级前建议备份；早期测试包若签名冲突，需卸载旧包再安装。

首次使用前请阅读并确认应用内免责声明：软件仅供非商业且合法用途，不提供节点、订阅或网络接入服务；订阅、配置和第三方脚本由用户与其提供者负责；软件按现状提供，不保证无错误或适用于特定用途。请以应用内免责声明全文为准。

[![下载最新版](https://img.shields.io/github/v/release/IvanSolis1989/Aerove?style=flat-square)](https://github.com/IvanSolis1989/Aerove/releases/latest)

## 界面预览

同一配色下的浅色与深色界面，来自应用页面的实际离线渲染。[渲染来源](docs/screenshots/render-info.json)

| 浅色 | 深色 |
| --- | --- |
| <img src="docs/screenshots/readme-light.png" alt="Aerove 浅色液态玻璃界面" width="300"> | <img src="docs/screenshots/readme-dark.png" alt="Aerove 深色液态玻璃界面" width="300"> |

## 下载与安装

**[直接下载 Android APK](https://github.com/IvanSolis1989/Aerove/releases/latest/download/Aerove-android-arm64.apk)** · [全部版本](https://github.com/IvanSolis1989/Aerove/releases)

支持 **Android 7.0 及以上、ARM64（arm64-v8a）**。下载 APK 后允许安装该来源的应用，按系统提示安装。
更新前可在应用内备份配置。早期测试包签名不同，若提示签名冲突，请备份后卸载旧包再安装。
发布页提供 `SHA256SUMS` 和 `BUILD_INFO.json`，可核对下载文件及对应源码。

## 添加订阅与使用脚本

1. 在配置页面添加订阅链接，保存并更新配置。
2. 选中配置，确认已关联内置的 **Smart-Config-Kit** 脚本，然后启动连接。
3. 在 **进阶配置 → 脚本** 查看脚本正文和来源链接。这里可以修改、复制或打开链接，也可以手动更新脚本。

新建的普通订阅及本地配置默认关联内置脚本；已有配置与备份恢复的数据保留原来的选择。
删除脚本后不会自动补回。[查看脚本来源](https://raw.githubusercontent.com/IvanSolis1989/Smart-Config-Kit/main/FlClash/FlClash%28mihomo%29.js)。

## 主题设置

首次安装默认开启随机渐变底图与极致液态玻璃，深浅色跟随系统。用户可随时切回普通模式。

进入 **工具 → 主题 → 主题色彩**，二选一：

- **普通主题色**：使用原有主题配色，可自由调整下方液态玻璃选项。
- **随机渐变底图**：选择 **1–3 种颜色**，点击 **换一张** 改变构图；下方液态玻璃自动切到“极致”，只禁用“无”，标准和极致仍可切换。

切回普通主题色会恢复之前的主题配色，液态玻璃的“无”选项重新可选。手动选择的玻璃档位会保存。
渐变配色与当前构图会保存，重启后保留；每月 1 日应用活跃时自动换图，当时未打开则在下次进入应用时换图。
深浅色默认**跟随系统**，也可在页面顶部指定浅色或深色。高对比度、深色纯黑模式使用不透明表面。

## DNS 默认设置

内置脚本提供分流与防泄漏 DNS 配置。客户端不追加系统 DNS，启动自举仅使用以下四个加密地址：

```yaml
default-nameserver:
  - https://223.5.5.5/dns-query
  - https://223.6.6.6/dns-query
  - https://8.8.8.8/dns-query
  - https://1.1.1.1/dns-query
```

业务 DNS、分域策略及 fallback 由脚本配置。恢复默认也使用这套方案。
VPN 系统代理、VPN/核心 IPv6、应用绕过默认关闭，DNS 劫持默认开启。
具体取值见 [网络默认配置](docs/network-defaults.md)。

## 在 Cloudflare 部署 SubStore

以下使用 [sub-store-cloudflare](https://github.com/realchendahuang/sub-store-cloudflare) 新建部署，前端与后端一起部署到自己的 Cloudflare 账号。
准备 Cloudflare、GitHub 或 GitLab 账号，以及自己的订阅链接。

### 1. 准备两个随机 Token

用密码管理器生成两个不同、各至少 32 字节的随机值并保存，分别作为管理 Token 和下载 Token。
安装了 Node.js 的电脑也可运行：

```sh
node -e "const{randomBytes:r}=require('node:crypto');console.log(r(32).toString('base64url'));console.log(r(32).toString('base64url'))"
```

### 2. 部署到 Cloudflare

点击 **[部署到 Cloudflare](https://deploy.workers.cloudflare.com/?url=https://github.com/realchendahuang/sub-store-cloudflare)**，登录并按提示导入仓库：

| 项目 | 填写内容 |
| --- | --- |
| Worker 名称 | 自定，例如 `sub-store-cloudflare` |
| D1 数据库 | 新建数据库 |
| `SUB_STORE_ADMIN_TOKEN` | 第一个随机值，作为 Secret 保存 |
| `SUB_STORE_PUBLIC_DOWNLOAD_TOKEN` | 第二个随机值，作为 Secret 保存 |
| 构建命令 | `pnpm run build` |
| 部署命令 | `pnpm run deploy` |

等待构建和部署成功，保存 Cloudflare 显示的 Worker 地址。

### 3. 打开管理页面并添加订阅

在 Worker 地址后加上 `/?token=<管理Token>` 打开管理页面，例如：

```text
https://sub-store-cloudflare.<你的子域>.workers.dev/?token=<管理Token>
```

添加订阅源（Source），填入自己的订阅链接并保存；再进入默认的 `Daily` 组合订阅（Collection），确认已包含需要的订阅源。

### 4. 导入 Aerove

在组合订阅卡片复制 **Mihomo / Clash Meta** 格式的下载链接，在 Aerove 配置页面添加该链接。
下载链接使用下载 Token；管理 Token 只用于管理页面。确认 Aerove 能更新并显示节点，即完成接入。
导入后保留 Aerove 内置的 Smart-Config-Kit 脚本与 DNS 默认设置，分流脚本在 Aerove 内执行。
两个 Token 和完整订阅链接都应私下保存。

部署问题可对照该项目的 [快速开始](https://github.com/realchendahuang/sub-store-cloudflare/blob/main/docs/quick-start.md)。

## 问题反馈

请在 [Issues](https://github.com/IvanSolis1989/Aerove/issues) 提供应用版本、Android 版本和复现步骤。
主题问题请附上深浅色模式、配色和截图；提交日志前移除订阅链接及节点凭据。

## 上游同步与版本发布

本仓库**每小时检查一次官方上游**，只从上游分支可达的合法稳定版或 `-pre.N` 版本标签选择来源。**没有新上游版本时，上游同步检查不会产生同步提交；Aerove 代码更新仍可触发构建。**新来源通过验证后，工作流以普通 merge 同步，再由 Android 工作流验证并发布；发布前会核对构建源码仍适用于当前 `main`，避免旧代码包覆盖较新的版本。APK 版本为上游版本加 `-X`：当前稳定上游 `0.9.1` 显示为 `0.9.1-X`，预发布编号也会保留，例如 `0.9.1-pre.4-X`。发布包使用本仓库固定的 Android 测试签名，并附 `BUILD_INFO.json` 与 `SHA256SUMS`。GitHub 定时任务可能延迟；可在 [Releases](https://github.com/IvanSolis1989/Aerove/releases/latest) 查看当前下载。仅 README 等文档变化不会触发 APK 构建，也不会取消正在运行的构建。

[GPL-3.0](LICENSE)
