<!-- xclash-owned-readme -->
<!-- xclash-repository: IvanSolis1989/Aerove -->

# Aerove

**简体中文** · [English](README_en.md)

Aerove 是基于 [FlClash](https://github.com/chen08209/FlClash) 的分支版本，保留上游代理客户端基础，并围绕液态玻璃界面、渐变壁纸、仪表盘与 Android 默认配置作了扩展。下表概括用户可见的主要差异；具体功能会因平台、主题模式与系统能力而异。
感谢 chen08209/FlClash 及其贡献者提供项目基础。Aerove 与上游 FlClash 是独立发布的分支。

## 与 FlClash 上游的主要差异

上游已有 Material You 配色体系、液态玻璃档位、连续圆角、代理组/记录界面和连接监控。Aerove 保留这些基础，在覆盖范围、壁纸、交互呈现、显示默认值和发布流程上继续扩展；以下列出分支增加或调整的主要用户可见内容。

| 模块 | Aerove 增加或调整的内容 |
| --- | --- |
| 主题与壁纸 | 极光渐变壁纸、可选 1–3 色与构图、月度轮换；首次 Android 配置默认极光背景与极致玻璃。 |
| 玻璃界面与动效 | 更多控件使用玻璃前景和背景适配；共享背景捕获/模糊缓存、覆盖区域冻结、标题与表单页的玻璃合成；动态壁纸按前后台和交互状态调整更新节奏。 |
| 导航与信息层级 | 底部导航栏随滚动收缩，滚动条操作后渐隐；仪表盘、代理出口、记录表格及订阅用量采用新的主题布局。 |
| 配置和 Android 默认值 | 内置并校验 Smart-Config-Kit；改进 DNS 默认值与旧默认迁移；新 Android 配置使用单独的 VPN、系统代理、IPv6 和 DNS 默认项。 |
| Aerove 品牌与维护 | 自有应用图标、翻译和桌面集成；单独的上游版本钉定、同步验证与 ARM64 APK 发布。 |

### 主题与页面

- 新增有种子的极光渐变壁纸，可从 1–3 种颜色生成构图并手动换图；配色和当前构图可保存，应用活跃时按月检查轮换。首次安装使用该壁纸与极致玻璃；普通主题色与玻璃档位可以分别设置。高对比度和纯黑深色模式保持不透明表面。
- 重做主题颜色选择器，支持系统/手动明暗模式和字体选择；字体选择依赖设备实际可用字体，Aerove 不捆绑 MiSans。MiSans 字体复现文件仅用于 CI 字体回归测试，不作为应用运行资源或随 APK 打包。
- 将玻璃前景、文字、图标和交互反馈扩展到仪表盘、代理组、卡片、按钮、选择器、输入框、导航栏、菜单、弹窗、编辑器和表单页。玻璃档位本身及 Material You、连续圆角等基础来自上游，Aerove 改的是覆盖与组合。
- 代理分组玻璃边界与吸顶标题按实际可见形状裁切；标题中的 emoji 与分组图标不重复显示。当前出口信息用国家/地区旗帜 emoji 呈现。
- 连接详情和表格中的标题、数值、单位与辅助文字使用统一层级和取色；这项调整不通过缩小文字或重排内容处理窄屏裁字。

### 弹层与表单页

- Bottom、Snap、Side 与 Form 弹层默认使用透明的整页遮罩，不再把背景统一压暗；遮罩仍拦截底层点击，并保留点击弹层外关闭。
- 使用玻璃标题时，内容还未滚到标题下方，顶栏不铺背景；内容滚入后，顶层显示一次渐变模糊玻璃并让下层文字透出。该变化按滚动位置切换标题背景，玻璃捕获继续复用已有帧缓存。

### 渲染与动效

- 同一页面内的玻璃窗格共享壁纸来源和背景捕获；静态背景的模糊结果可跨窗格复用。截图/纹理采用有边界的图集，长边最多 2048 像素，并按局部区域缩放捕获。
- 被不透明标题、弹层或其他表面覆盖的玻璃区域可冻结已有画面；需要保持透色的标题下层则绘制普通背景。覆盖边缘按设备像素比处理，玻璃区域的采样分辨率可低于屏幕，以控制捕获范围和纹理尺寸。
- 页面不活跃时暂停动态壁纸更新；重新可见时保留已有画面，避免先空白再恢复。壁纸动画在空闲时按 30 Hz 更新，滚动/交互时按 15 Hz 更新，活动结束 350 ms 后回到空闲节奏。
- 这些是渲染路径和更新节奏的实现说明，不代表经过真机测量的省电比例、稳定帧率或掉帧根因修复。实际效果受设备、分辨率和图形后端影响；不支持完整效果的环境使用简化磨砂/着色表现。

### 导航、代理与仪表盘

- 底部悬浮 Dock 在向下滚动时缩小外框，向上滚动或回到顶部时展开；图标尺寸保持不变，文字标签淡出。页面切换会重新展开。
- 连续折叠的代理组使用固定高度的 sliver 列表，标题只在可视区和缓存范围内按需创建，折叠组中的代理节点不创建/绘制；展开组继续使用原有 sliver 行布局，折叠/展开动画仍由组 sliver 处理。这样滚动时不必遍历所有折叠组并创建其节点骨架。
- 代理列表不再常驻显示滚动条；滚动结束后渐隐，拖动长列表时显示位置/倍率提示。其他滚动区域也按主题呈现滚动条。
- 保留上游已有的代理组卡片、标签、连接/请求/DNS 表格和连接监控；Aerove 调整其玻璃背景、图标/文字呈现、出口信息以及仪表盘围绕当前出口的布局，而非新增代理协议或核心监控数据。
- 订阅用量卡片新增液面与弯曲液面边缘，液面向内留边并裁切在卡片轮廓内；仅在订阅提供有效总量/用量时显示。接近额度和超额使用不同警示色；无有效配额时不显示液面。
- 连接记录在列表和详情中统一端点、规则、代理链和流量文字的层级；DNS/请求记录使用当前主题下的表格与空状态。

### 脚本、DNS 与首次配置

- 内置 Smart-Config-Kit `v6.0.20-flclash.21`，来源为 [Smart-Config-Kit](https://github.com/IvanSolis1989/Smart-Config-Kit)，固定到提交 `6847cd225d70bc9eb6fdd9311d50ff53bc91981c`，并校验 SHA-256 `d962c99703ca0c4fb0649541b81a7d24d94f2737ad3d83937a85a42e07f4ccc5`。安装包内离线提供脚本；脚本页显示来源，可查看、编辑、复制/打开来源并手动更新，避免重复更新。
- 新导入的普通订阅、本地文件和链接配置默认关联该脚本；已有配置、已有脚本选择及自定义配置不被覆盖。删除脚本后不会自动重装。
- 新 Android 安装默认不追加系统 DNS，VPN 选项开启、系统代理/绕过/IPv6 关闭，DNS 劫持开启；核心默认规则模式、IPv6 关闭，连接并发选项开启，keep-alive 空闲/间隔为 30/15 秒。启动代理仍需用户操作。
- DNS 默认引导服务器为 README 下方列出的四个 DoH 地址，业务 DNS、分域与 fallback 由脚本配置。恢复 DNS 默认值也使用这组设置；旧配置仅在仍匹配原版默认 DNS 且未显式选择相关覆盖项时迁移，不覆盖用户已经自定义的值。

### 品牌、平台与发布

- Android 使用 Aerove 自有 adaptive、round、TV 与单色图标；关闭系统对应用的强制深色转换，通知标题/频道名称使用 Aerove 名称。桌面窗口标题、托盘和启动集成也按分支标识调整。
- 补充西班牙语、法语与韩语翻译；Android 发布仅提供 ARM64 APK，最低 Android 7.0。桌面功能按各操作系统能力呈现。
- 上游版本由 `tool/upstream-version.json` 钉定；同步工作流只从官方 FlClash 可达的稳定版或 `-pre.N` 标签选择来源，以普通 merge 合并并先做验证。APK 使用上游版本号加 `-X`（例如 `0.9.1-X`），随包提供 `BUILD_INFO.json` 和 `SHA256SUMS`。
- 发布使用本仓库固定的 Android 测试签名，包 ID 为 `com.follow.clash.dev`；这不是 FlClash 官方签名或 Google Play 发行版。更新前建议备份配置；早期测试签名冲突时需卸载旧包后安装。

本项目与 FlClash 上游独立发布，感谢上游作者与贡献者。首次使用前需阅读并确认应用内免责声明：软件仅供非商业且合法用途，不提供节点、订阅或接入服务；第三方内容由用户与其提供者负责；软件按现状提供，不保证无错误或适用于特定用途。请以应用内免责声明全文为准。

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
渐变配色与当前构图会保存，重启后保留；应用活跃时会按月自动换一张。
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

本仓库**每小时检查一次官方上游**，只从上游分支可达的合法稳定版或 `-pre.N` 版本标签选择来源。**没有新版本时，不提交、不构建也不发布 APK。**新来源通过验证后，工作流以普通 merge 同步，再由 Android 工作流验证并发布；发布前会核对构建源码仍适用于当前 `main`，避免旧代码包覆盖较新的版本。APK 版本为上游版本加 `-X`：当前稳定上游 `0.9.1` 显示为 `0.9.1-X`，预发布编号也会保留，例如 `0.9.1-pre.4-X`。发布包使用本仓库固定的 Android 测试签名，并附 `BUILD_INFO.json` 与 `SHA256SUMS`。GitHub 定时任务可能延迟；可在 [Releases](https://github.com/IvanSolis1989/Aerove/releases/latest) 查看当前下载。仅 README 等文档变化不会触发 APK 构建，也不会取消正在运行的构建。

[GPL-3.0](LICENSE)
