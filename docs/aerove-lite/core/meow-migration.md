# Lite 内核迁移：meow-rs

[文档](../../README.md) / [Lite](../README.md)

2026-10-23 用户决定：Lite 从 clash-rs 切换到 meow-rs，继续原生 Android / Compose、最低 API 33。
标准版继续 Flutter + 官方 Mihomo。这里记录实施与验收，不改变[共同契约](../../shared/UI_CONTRACT.md)。

## 本页目录

- [固定来源](#section-1)
- [当前状态](#section-2)
- [固定核心差异](#section-3)
- [保留的运行边界](#section-4)
- [接口和配置适配](#section-5)
- [实施和验收顺序](#section-6)

<a name="section-1"></a>

## 固定来源

- 核心：`meow-rs/meow-rs` 的 `v0.26.1`，提交 `727ddcd06f592e2f12c5222c4de6cce328df808c`，MIT。
- Android 接入参考：`meow-rs/meow-android` 的 `4740012bf0efdb8bae8fbeeefaf4937dfa309f26`。
  该参考自己的 Cargo 仍固定 `v0.26.0` / `3e123030915233c5ffa599d72ee60908e65281eb`，不能冒充本产品的 `.1` 来源。
- `.0` 到 `.1` 只改变 CHANGELOG、根 Cargo manifest/lock 和 meow-anytls manifest，Rust 源码没有变化；
  依赖升级仍须真实编译、链接与运行，不能据此跳过验证。
- 来源、用途、采用方式与许可统一登记在[开源来源与鸣谢](../../development/upstreams.md#开源来源与鸣谢)。
  分享讨论用于理解背景；未成功读取的内容不作为事实或实施指令。

<a name="section-2"></a>

## 当前状态

切换前的功能与并行 CI 已保存在 `932d460f18fcb0df7fff83d399417e4de69e09a4`，树
`6457a817530396d61d534bf360c40cfa7ad370ea`。保留旧 `native/`、锁文件、适配补丁、测试场景及真实失败证据；
停止继续扩展旧核心。旧检查点不是完成版，旧测试通过也不能代表 Meow 已通过。

初始迁移检查点位于 `codex/aerove-meow-preflight` 分支；当前应用整合位于 `codex/aerove-lite-meow-app`，一般策略组检查点位于 `codex/aerove-meow-provider-general`。main 的文档更新不等于运行时或发行验收完成。

新候选位于 `apps/aerove-lite/meow-native/`。第一条代表链先验证唯一运行实例、私有认证 API、真实预绑定
mixed 端口、经 Meow DIRECT 到回环 HTTP 服务、实际流量快照、取消、等待退出和端口重新绑定。
当前 Kotlin 生产适配与 Gradle 原生构建入口已接入 Meow，`MeowRuntimeConfigComposer` 形成候选配置和宿主计划；旧 `native/` 继续保留。已接入构建不代表共同功能、真实路由和发行验收完成。
实际 APK 和各项证据必须绑定原源码及构建身份，不能以新增目录、配置适配或单模块测试代替完整验收。

已取得的真实证据分属不同源码图，不能相互替代：

- 初始 Proxy 图的 5 项 Rust 用例，以及 ARM64、x86_64 真实链接和 16 KiB ELF 检查已通过。
- 加入 API / WebSocket / QuickJS 后，13 项 host 用例在真实 CI 通过；双 ABI 曾因缺少 QuickJS Android
  预生成 bindings 失败。已采用同版本官方 `bindgen`，真实加载 libclang 18，核对新锁与依赖边。
- [锁定双 ABI 验证](https://github.com/IvanSolis1989/Aerove/actions/runs/39974811882)在源码
  `230f41a5d9985bb8a993ae5e1cff13733171a91a` 成功：原 13 项 host 用例、API33/NDK28.2 真正生成绑定、
  ARM64 / x86_64 链接、16 KiB ELF、四个已定义脚本 JNI 导出和动态依赖门禁全部通过；原产物收据已核。
  这些只证明该源码图，不包含后续 FD / 控制变更，不替代完整运行 JNI、TUN、配置保真、共同功能、APK 与设备验收。

<a name="section-3"></a>

## 固定核心差异

当前固定 SHA 的封闭代理 factory、导出模块与 Cargo feature 表均无 `tuic`、`ssh`、`wireguard`、
`tailscale`、`shadowquic` 出站实现。依据为同一提交的
[proxy_parser.rs](https://github.com/meow-rs/meow-rs/blob/727ddcd06f592e2f12c5222c4de6cce328df808c/crates/meow-config/src/proxy_parser.rs)、
[代理模块](https://github.com/meow-rs/meow-rs/blob/727ddcd06f592e2f12c5222c4de6cce328df808c/crates/meow-proxy/src/lib.rs) 和
[feature 声明](https://github.com/meow-rs/meow-rs/blob/727ddcd06f592e2f12c5222c4de6cce328df808c/crates/meow-config/Cargo.toml)。
这五项按核心例外记录；导入含对应 `$.proxies[i].type` 的配置须指出具体节点、字段及原因，禁止静默删除或转换。

`trusttunnel` 已有实现，只是未纳入官方 `full`，须显式启用并验证。DNS redir-host、nameserver-policy
列表及 UID / IN-USER 规则也已有实现，不得沿用旧核心的 blanket 拒绝。其他传输或选项差异仍须逐字段核对，
尚未接入的 JNI、宿主策略、API、provider 或客户端功能继续阻止发行，不作为核心例外。

<a name="section-4"></a>

## 保留的运行边界

`LiteVpnService`、`VpnCoordinator`、`AndroidTunnelBackend` 继续唯一持有用户意图、授权、Builder、物理网络和前台生命周期。
新桥接只替换其后面的核心适配，不创建第二个 Service、运行实例或额外轮询。

- 启动先完成配置和真实监听绑定；任务 spawn 不等于就绪。初始 parked 保持核心/私有控制，零用户监听、零 Builder/FD。
- FD 从 typed 输入进入桥接，原 FD 仍归宿主，原生仅持 duplicate。dup 不隔离共享的文件状态标志。
- protect/归属回调必须在任何需要保护的拨号前安装，失败即拒绝启动。旧代任务必须真正取消并 join 后才能清理/替换全局回调。
- stop/park/加载取消与等待本身的取消，都须保留可恢复的退出等待；未确认退出时阻止后继启动。
- 保留实际 MTU、IPv6、物理 DNS、应用归属、网络变化和已确认的设置应用/重启语义；不得引入公共 DNS fallback。
- 官方 Android 的监听/sniffer 删除、强制 fake-IP、spawn 即成功、abort 不等待均不直接移植。

<a name="section-5"></a>

## 接口和配置适配

现有 Kotlin 产品契约和生命周期数据流继续作为消费者基线。复用 Meow 的 parser、Tunnel、代理/规则和公开协议处理器；
`/aerove/*`、一致 revision、provider 成功事件、规则分页、网络 CAS、真实诊断以及 Android 生命周期属于宿主适配责任。
缺少现成 JNI/路由不是核心不支持的证据。

- Meow `/memory` 是持续 NDJSON 且首帧为零，不能直接接到现有一次性读取；须提供真实进程内存快照，不填模拟零值。
- 原 `gateway/device-id/boolean dns-hijack` 不能原样传递。Meow 使用地址字段和列表形式；Android Builder 持有路由，
  引擎不得另建系统 TUN 或接管 OS 路由。FD 不写入假设备 YAML。
- 候选 composer 复用现有控制认证、资源 generation/路径包含、设置合成链；用户监听及宿主策略须由桥接实际消费和读回。
  保存计划不等于功能已实现，不兼容字段按具体路径报错，不能 strip 后假称支持。
- 日志/连接/流量共享事件订阅，沿用可见性、请求合并、缓存、退避、取消与前后台暂停策略。
- provider 的初始、手动、定时、文件和未变化成功都须在真正提交后产生一致事件；失败不前进 revision。
- 用户控制入口不得暴露私有 AEROVE 路由。规则/配置检查仍须离线、有界、受资源归属约束。
- QuickJS 脚本能力及独立 Go regexp2/age 辅助程序保留，不能随旧代理核心替换而丢失。

<a name="section-6"></a>

## 实施和验收顺序

1. 固定来源、许可和可重放准备；独立 CI 真实生成桥接锁，保存实际锁与 source/tree/run/attempt，再以 `--locked` 运行。
2. 首条 Proxy 代表链真实编译及 I/O；覆盖端口冲突、配置错误、取消等待后重试 stop、迟到结果和重新绑定。
3. 补齐 API/事件与宿主策略，移植原核心相关测试的场景和断言；不删除旧场景以取得通过。
4. 真实 Android 两 ABI 链接、JNI/QuickJS/辅助库闭包、16 KiB ELF、TUN/protect/MTU/IPv6/park/resume/取消接线。
5. 再切唯一生产适配及并行发行流程，完成共同功能、性能策略、签名/包名/版本/来源/hash、真实 Android 十二图和用户确认。
6. 全部门槛通过后首发；main 推广同一已验收 APK，保留原源码和构建身份，不重新编译旧包。

独立工作流先验证第一代表链；它不替代 Lite 的完整发行门禁。首轮最小协议 feature 集也不等于产品最终协议支持集合。
旧 clash-rs 的能力例外须依据固定 Meow 源码重新核验。上述五项只证明固定核心能力边界，不代表配置准入或 APP 已完成；Lite 尚无首版发行或十二图。
公开核心基准不证明 AEROVE Android 更省电，实际测量仍按[验收边界](../../shared/ACCEPTANCE.md)。

---

下一步：[Lite 说明](../README.md) · [共同契约](../../shared/UI_CONTRACT.md)
