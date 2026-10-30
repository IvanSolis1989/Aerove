# 共享 Flutter 客户端迁移实施计划

[文档](../README.md) / [开发维护](README.md)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. 用户已授权本轮迁移及按责任域并行推进，不重复询问路线或执行方式。

**Goal:** 标准版与 Lite 使用同一份 Flutter 客户端，分别连接 Mihomo 和 meow-rs，完成各自真实 Android 验收与独立发行。

**Architecture:** 采用共享 Dart 客户端包与两个薄 Flutter 产品入口。共享层拥有界面、状态和客户端业务；产品入口提供品牌、资源、内核与平台适配。Lite 复用既有 Meow JNI 和 `LiteVpnService` / `VpnCoordinator`，不同时引入标准版的 VPN 生命周期所有者。

**Tech Stack:** 仓库固定 Flutter / Dart、Android Kotlin、meow-rs v0.26.1（`727ddcd06f592e2f12c5222c4de6cce328df808c`）、既有 JNI 与 Flutter platform channel；不为此次迁移引入新的 Rust 核心桥。

**Spec:** [共同契约](../shared/UI_CONTRACT.md)、[外观参数](../shared/appearance.md)、[验收规则](../shared/ACCEPTANCE.md)、[Lite 核心迁移记录](../aerove-lite/core/meow-migration.md)。

## 当前边界

2026-10-29 用户决定取代旧的 Compose UI 复刻路线。当前 Lite beta.25 仍是旧界面原包：完整 CI #163 失败，手机验收失败，未发行；不能把本计划当作 Flutter Lite 已完成的证明。原包、失败日志、旧实现及尚未提交的修复均须保留。

新共享包计划位于 `packages/aerove_client/`，Dart 包名保持 `fl_clash`，减少无意义的内部导入改名；`apps/aerove/` 和 `apps/aerove-lite/` 保留独立产品构建入口。该目录已建立并接入标准版薄入口，移动前已保存逐文件来源、哈希和可恢复检查点；没有使用符号链接或复制两份 UI。路径消费者与 Lite 平台接线分别验证，不将抽取完成当作迁移验收通过。

## 全局约束

- Lite 最低 Android 13 / API 33，独立包名、签名、单调安装编号及更新入口；标准版保留官方 Mihomo 和现有桌面支持。
- Lite 包中不得出现 Mihomo 库或误用其专版脚本；两版分别固定各自 Smart-Config-Kit 脚本正文、更新链接和来源。
- 共用真实生产界面、交互、动效、无障碍与采样门禁；不得按产品复制页面、另做简化连接页或隐性降级。
- 每产品只有一个 VPN / 核心生命周期权威。停止、取消、权限拒绝及恢复必须最终收敛，不增加重复轮询。
- 不 reset、clean、强推、宽泛暂存或覆盖已有工作；测试、构建和批量脚本一律 Idle / nice 19。
- 每个真实应用目标完整构建一次，推广原字节；标准版 beta.14 已发行原包不重编。发行前仍须新原包手机确认和明确 main / 发行授权。

## 重点风险

1. 空配置与已有代理组分别产生三项、四项导航，选中图标及读屏状态必须正确。
2. 错误产品资源或错误核心被接入时，应在启动前明确失败，不能静默回退到另一个核心。
3. 权限框、启动中取消、快速启停和清理未确认时，不能留下第二实例或覆盖新意图。
4. Flutter 不在前台、引擎未启动或进程重建后，Android 服务仍须正确恢复；UI 不成为后台运行的前提。
5. 首次冷启动、声明选择复制、材质切换、Dock 按压和拖动必须检查真实生产页面，静态仪表盘 fixture 不足以验收。

## 任务 1：共享客户端与产品入口

**文件责任：** 新建 `packages/aerove_client/pubspec.yaml` 和 `lib/`；从 `apps/aerove/lib/` 移入共享实现；产品 `lib/main.dart` 只保留组合入口。同步 `tooling/workspace.py`、目录治理、测试和 CI 的实际源码路径。

**接口：** 共享入口接收不可变 `ProductSpec`；至少包含产品 ID、展示名称、期望核心 ID、最低 Android API、产品资源清单与更新入口。界面内部不散布 `isLite` 分支。标准版无参数构建和既有 APK 路径继续有效，不盲增 flavor。

- [x] 保存最终共享输入和原有未提交改动的逐文件哈希；在不覆盖原工作区的检查点中实施结构移动。
- [x] 提取一份客户端源码，保持 `package:fl_clash` 内部导入和生成器来源；生成文件仍由原生成器维护。
- [x] 把包内共同图片、字体和 shader 的访问集中到资源入口；产品专用脚本、Geo、CA、品牌及更新信息由薄入口提供，不能把整个标准版资产目录传递给 Lite。
- [x] 标准版继续独立依赖 `plugins/setup`，其既有目录假设不因共享 UI 被破坏；客户端脚本与编辑器所用 `rust_api` 辅助库按实际能力复用。
- [ ] 对两产品入口、资源清单和错核心拒绝增加最小测试；运行受影响分析及现有 Flutter 测试。路径变更后运行 `python tooling/workspace.py check`。

## 任务 2：Lite Flutter 平台宿主

**文件责任：** Lite Flutter pubspec / 入口、Android 宿主构建文件与新的 `FlutterMainActivity`；既有 `meow-native/` 及原生来源检查保留。

**接口：** Flutter 宿主只向平台命令门面发请求。Lite 继续以 `LiteVpnService` / `VpnCoordinator` 及捕获的 owner / generation 管理会话；不得同时启动标准版 `ServiceState` / `VpnService`。

- [ ] 接入仓库固定 Flutter 工具链和宿主插件，不引入标准版 Gradle `:core`、gomobile、`libclash.so` 依赖边。
- [ ] 保留 `version.properties` 作为 Lite 版本事实源，并验证 Flutter 展示版本、Android manifest 和产物版本一致。
- [ ] 保留 Meow ELF / JNI / ABI 收据、签名和包身份校验；先验证依赖图和真实 native 来源，再构建应用。
- [ ] 固定白底黑飘带启动品牌；共享 Flutter 正文出现前系统栏与窗口也必须一致。

## 任务 3：内核命令、配置与事件适配

**文件责任：** 共享客户端现有 `core/interface.dart` / `method.dart` / `event.dart` 接缝与 Lite 新的 `MeowFlutterCoreAdapter`、`MeowFlutterCommandFacade`；不引入整个 Compose `LiteController` 作为 Flutter 的第二状态树。

**接口：** 适配器对外保持 `CoreHandlerInterface` 的参数、结果、错误、取消和终态语义。对内复用 `MeowNativeCore.startOwned`、ready / exit / stop、listener / host policy，以及既有 `CoreApiClient` HTTP / WS 能力。

- [ ] 按实际 `CoreMethod` 和 `CoreEventType` 建立穷尽映射；测试必须核对真实 DTO 及错误语义，禁止空结果、成功占位或未映射事件被默默丢弃。
- [ ] 共享 Dart 客户端作为用户配置、订阅、设置和编辑的唯一持久化权威；现有 Kotlin 编译、资源验证和来源流水线作为无第二套持久化的核心服务复用。
- [ ] 复用 Lite 专版脚本和配置编译器。共享 UI 中的 Mihomo 字段不能直接传入 Meow 后静默忽略，必须保留字段级兼容性错误。
- [ ] 逐项接通代理选择、规则、provider / Geo、探测、统计、连接、请求 / DNS 历史、日志和外部控制器。只有固定核心源码证明不支持的能力可按原契约记录例外。
- [ ] 首条真实链路必须经过共享 Flutter 导入与专版脚本、平台命令、owned start、TUN / protect / UID、Meow 实际 TCP / UDP / DNS 转发、真实 HTTP / WS 统计、stop / join；只得到版本号或状态回执不算闭环。

## 任务 4：生命周期、存量数据与权限

**文件责任：** Lite 平台命令门面、现有 VPN / 网络所有者、共享客户端采样生命周期和必要的数据导入器。

- [ ] 复用已有 owned-start 取消与 superseded / unconfirmed 结果，不加入全局 stop 兜底或第二运行状态。
- [ ] 验证 Flutter 引擎不存在时的通知启动、后台恢复、网络切换与 Wi-Fi 停泊；按既有决定保留静默通知和自动恢复。
- [ ] 保护既有 Lite 安装的数据；迁移前识别实际格式，事务导入，不覆盖用户编辑、不自动重加已删脚本。禁止假定用户测试包一定为空。
- [ ] 验证拒绝权限、取消导入、取消脚本和快速启停的真实结束及恢复；前台三统计一秒一次、短暂失焦立即暂停，曲线历史保留并续接。

## 任务 5：共享 Dock 与真实 UI 验收

**文件责任：** 共享 `ui/navigation_dock.dart`、`pages/home.dart` 及现有直接测试；迁移时按哈希带入已审补丁，不重新手写。

- [x] 悬浮栏采用标准版固定栏同一 `AnimatedGlyph`：未选中描边、选中实心，保留透色。
- [x] 固定栏复用真实正文 `LiquidGlass` 来源，磨砂无折射、清透使用既有真实位移；保留安全区、高度与点击。
- [x] 源码复核修正材质切换时退出动画保留旧栏导致的短暂双栏；增加同一界面 0 / 100 / 300ms 转换断言。
- [x] 抽取前底栏补丁的客户端 CI #82 已通过，旧五项动画门禁回归有成功日志；不将其复用为抽取后或 Android 验收。本机 native hook 缺 `rustup`，不安装或绕过门禁碰绿。
- [ ] 在真实 Lite Flutter Android 上覆盖首次冷启动、声明正文 / 目标文本复制粘贴、主题页和工具页、Dock 按压 / 拖动 / 取消及三材质十二组合。
- [ ] 将旧 Compose 用例的业务意图逐项对应到共享 Flutter 及 Lite 平台证据；退役实现的旧失败保留，不改写为通过，不以改框架删除业务或光学门。

## 任务 6：候选与独立发行

**文件责任：** 两产品自己的 CI、产物验证、版本和发行入口。共享包不是第三个独立发行 App。

- [ ] 两版分别完成客户端与核心检查，保留输入未变的有效证据，仅补验受影响部分。
- [ ] 新的真实应用变更使用新的产品目标；完整 CI 后验证原 APK 的源码、版本、包名、签名、哈希及内核资产，禁止重建旧 beta.14 / beta.25。
- [ ] 给用户原构建 APK 完成手机验收。两版各自满足自己的契约即可发行，不等待另一版。
- [ ] 明确 main / 发行授权后推广同一原包，核对 Release、独立更新入口及 Telegram 实际结果；没有实际证据不写完成。

## 验证记录

目前已完成仓库构建边界、现有 Meow JNI / 平台复用与手机差异调查，以及上述 Dock 补丁的格式、限定静态分析和独立源码复核。共享包已抽取，生成器、测试扫描和 CI 的路径消费者已迁移；共享包限定分析和相关 Python 检查通过，抽取后的完整 Flutter/Android 验证尚未完成。Lite owned-start 命令已抽取为共享平台门面，28 项 JVM 测试通过；Flutter 宿主、完整协议适配、真实 Flutter Lite Android、手机验收和发行均未完成。

本计划不改变[独立版本与原包推广规则](VERSIONING.md)，也不代替[固定核心来源与能力依据](../aerove-lite/core/meow-migration.md)。
