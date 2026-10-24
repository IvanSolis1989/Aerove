[文档](../README.md) / [技术研究](README.md)

# Android 内核选型：源码、社区反馈与公开基准

阅读边界：本页保留固定来源、当时判断与未闭环记录，不能代替生产规格或实际验收。现行 Lite 为原生 Android + meow-rs、最低 API 33；共同界面和客户端功能以[双向契约](../shared/UI_CONTRACT.md)为准，仅固定 Rust 核心源码证明不支持的能力可暂缓。

本页目录：[当时建议（历史记录）](#当时建议历史记录) · [直接找到的社区与开发者证据](#直接找到的社区与开发者证据) · [现有仓库可以直接复用什么](#现有仓库可以直接复用什么) · [其他选择](#其他选择) · [后续决定与验收](#后续决定与验收)

历史研究日期：2026-10-22。2026-10-23 用户明确将 Lite 从 clash-rs 切换到 meow-rs；这是当前执行决定，见[迁移记录](../aerove-lite/core/meow-migration.md)。下文保留原研究与当时建议，不覆盖新决定；Lite 尚未发行。

## 当时建议（历史记录）

Lite 首个发行版优先采用 Android 原生 Compose 界面与现有 Mihomo Go 集成。保留已做的 Rust 工作作为可恢复的研究候选，不以已经投入的开发时间决定最终路线。此建议来自兼容性、现有可复用边界及公开性能证据的综合判断，不等于已经证明 Mihomo 在所有 Android 场景能效第一。

用户明确要求 Lite 与完整版界面完全一致，功能可按内核能力取舍。使用相同内核更容易满足完整配置、DNS、代理组、规则和 Android 应用归属语义，也能单独判断替换 Flutter 的实际收益。原生界面不要求同时更换 Go。

此前研究主要覆盖 clash-rs 协议、Android 嵌入和生命周期，未取得同设备功耗对照。不能用“本机不能测”停止外部研究；开发者实测、公开脚本和原始用户反馈同样是证据，但必须保留版本、平台、功能和测量口径。

## 直接找到的社区与开发者证据

| 来源 | 实际信息与终态 | 可以支持的判断 / 限制 |
| --- | --- | --- |
| [clash-rs #1504](https://github.com/ibigbug/clash-rs/pull/1504) | 提交者报告：128MB IPQ5018/OpenWrt，透明代理与 fake-IP，修复后运行 30 多天，RSS 约 17MB；维护者说明拆分修订后通过多个 PR 合入。 | Rust 路线具备低进程内存和长期运行的积极实物反馈。不是同机 Mihomo 对照，也不是 Android，不能换算成手机省电百分比。 |
| [clash-rs #1491](https://github.com/ibigbug/clash-rs/pull/1491) | 作者在 Linux/WSL2、i7-12650H 测同一 Rust 内核的两种 TUN 栈：64 路时 smoltcp/system 为 4.13/4.09Gbps，RSS 137/19MB；单路吞吐 3.39/4.82Gbps。该 PR 当前 open、没有 merged_at。 | TUN 实现及缓冲策略可以比语言选择更影响结果。部分状态转移到内核，进程 RSS 降低不代表整机总内存等比例下降；未合入方案不能当作当前稳定版现成功能。 |
| [clash-rs #1326](https://github.com/ibigbug/clash-rs/issues/1326) | 用户曾报告相同节点下网页和 Hysteria2/VLESS 速度落后；最终于 5 月 11 日确认 0.10.3 已修复。 | 必须同时阅读问题和结案。旧故障不证明当前 0.10.10 持续落后；也没有证明当前在 Android 超越 Go。 |
| [Nikki-rs 作者在 V2EX 的说明](https://v2ex.com/t/1233915) | 维护者描述修复多种协议问题、裁剪部分协议，并说明 TUN 和 DNS 兼容边界。 | 真实部署者认为低占用有价值，同时实际承担了适配成本。其定制分支、裁剪功能和路由器环境不能直接替代 ibigbug 稳定版的 Android 评估。 |
| [clash-rs #1512](https://github.com/ibigbug/clash-rs/pull/1512) | 内存压力、DNS 缓存回收和分配器调整于 9 月 19 日合入；PR 同时有 Linux CI 吞吐结果与历史基线警告。 | 当前项目确实持续优化。不同机器/时点的 CI 历史基线不能直接算作 Go/Rust 优劣；没有 Mihomo 同场对照。 |
| [meow-rs 作者公开基准](https://github.com/meow-rs/meow-rs#benchmarks) | Apple M4 Pro/macOS/回环，作者列出对比 Mihomo 1.19.31：空闲 RSS 31.3→9.7MB，大传输 14.40→14.91Gbps，小传输两组约落后 1%/2%。作者保留 p99 波动和测试范围说明。 | 另一种 Rust 实现显示内存潜力，但并非 clash-rs，也并非所有吞吐项目领先，更不是 Android 电量测试。README 的固定快照不能自动代表任意 release 二进制。 |
| [meow-rs #460](https://github.com/meow-rs/meow-rs/issues/460) | Android 反馈指出逐次 DNS TCP 重连与 protect(fd) 调用导致解析拥堵；当前 issue 已关闭。 | 即便使用 Rust，Android DNS/JNI/连接复用仍能形成瓶颈。已关闭问题不能直接声称仍存在，需要对应版本和修复确认。 |
| [Mihomo #2764](https://github.com/MetaCubeX/mihomo/issues/2764) | 用户报告跨平台高 CPU，后续自行定位 DNS/sniffer 循环，并报告通过配置避开得到解决。 | Go 也有真实高负载反馈；不能把配置循环归结成 Go 语言普遍耗电。 |

公开 Android 原生 Rust 客户端 [AChimera](https://github.com/MFSGA/AChimera) 已存在，但项目自述仍有运行时重启和配置兼容限制；[clash-rs Android GUI 讨论](https://github.com/ibigbug/clash-rs/issues/982) 也提供了 Android 工程入口。这些证明并非从零探索，不能据此声称有成熟的能耗胜出结果。

本次没有找到方法、设备和原始数据足够完整的 clash-rs 对 Mihomo Android 电量对照。此结论只描述已检索到的材料，不声称全网绝不存在。排除采集站、只写 Rust 高效的宣传、自动审查机器人赞语与下载包大小替代 RSS 的比较。检索覆盖 GitHub Issues/PR/评论、开发者基准、V2EX 一手部署说明及 Android 客户端来源。

## 现有仓库可以直接复用什么

本次读取工作分支 HEAD `11c1ff06`；Mihomo 子模块实际 SHA `8380d0e7462f061a964323e9bf3129a236fcc5d5`，没有将官方新 release 的修复冒充已进入本地。

- `android/core/.../Core.kt` 是独立 Kotlin→gomobile 入口，没有 Flutter 导入；已有 TUN fd、protect、UID/package 解析、事件、初始化、网络复位和流量接口。
- `core/mobile/mobile.go` 已封装 Java 异常到 Go 错误的回调边界，且设置 GC 参数并关闭无人读取的堆分配采样。
- `android/core/build.gradle.kts` 构建独立绑定与 JNI 库。Compose 可以复用其源码边界，不必装载 Flutter；现有 Gradle 版本目录和生命周期仍需适配，不能称零成本替换。
- `lib/enum/enum.dart` 与现有 Mihomo 子模块已包含 `mips`。共享对话提到的 MIPS 是 Mihomo IP Stack，不是 CPU 架构，也不是我们必须换一个内核才有的新能力。

[官方 TUN 文档](https://wiki.metacubex.one/config/inbound/tun/) 推荐正常工作时使用 mips，并列出 system/gvisor/mixed。文档的推荐不是 Android 所有机型功耗排名。应按相同协议、MTU、规则、DNS 和客户端调度比较。

官方 [v1.19.33](https://github.com/MetaCubeX/mihomo/releases/tag/v1.19.33) 已公开，包含 DoH 请求堆积、代理链检查复杂度和 TLS 等修复。是否升级应单独比较当前 fork 补丁与公开 tag、跑回归，不直接用通用二进制替掉带宿主回调的库。

## 其他选择

| 候选 | 本次工程判断 |
| --- | --- |
| 现有 Mihomo | 首版最有利于配置和功能一致性；已有独立 Android 绑定，不需 Flutter。作为实际基线。 |
| clash-rs | 值得保留研究；低内存反馈积极，但 Android 接口、规则/配置语义及 TUN 路径需要自有维护，没有足够证据支持立即认定能效胜者。 |
| sing-box | [官方 Android 客户端](https://sing-box.sagernet.org/clients/android/) 路径成熟，仍为 Go；配置体系并非 Mihomo YAML 原样替换，需要转换并验证全部语义，不适合为首版临时扩大迁移。 |
| meow-rs | 公开对比数据较具体，值得第二阶段审计；其结果不能移植到 clash-rs，也尚不足支持替代当前 Android 核心。 |

## 后续决定与验收

研究曾建议先以 Compose+同一 Mihomo 做 Lite，用户当时选择 clash-rs。2026-10-23 新决定已改为 Compose+meow-rs；不切 Go、不打包双核，旧源码和实测证据作为可恢复检查点保留。旧核心能力例外及测试结果不能自动成为新核心的验收依据。上述历史研究不再决定当前路线，界面与功能契约保持有效。

真正比较分两组：完整版 Flutter+Mihomo 对 Lite Compose+Mihomo，隔离界面收益；同一 Compose 前端下 Mihomo 对 Rust 候选，隔离核心收益。两组都固定协议与完整规则/DNS数据、同画质三档材质与极光、相同事件频率和后台状态。测整进程 PSS/RSS、系统及进程 CPU/唤醒、固定吞吐下的能量/GB、空闲耗电、延迟、帧和可靠性；不以低配裁剪、关动效或核心 RSS 转移到内核冒充收益。

证据目录 `D:/CodexTemp/aerove-independent-20261022`：共享对话提取文本、`community-clashrs-{1326,1491,1504,1512}.json`、`community-{460,746,2764}.json`。这些是公开来源快照，不是 AEROVE 真机测试结果。

---

相关入口：[研究入口](README.md) · [共享契约](../shared/UI_CONTRACT.md)
