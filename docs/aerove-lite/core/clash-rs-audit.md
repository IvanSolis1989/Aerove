# clash-rs 兼容性与 Android 接入审计：Aerove / AEROVE Lite 双产品路线

[文档](../../README.md) / [Lite](../README.md)

> 历史审计：下文保留 2026-10-22 的固定来源、当时判断与证据，不是当前 Lite 路线。Lite 已选择 meow-rs；请先阅读[当前迁移记录](meow-migration.md)与[共同契约](../../shared/UI_CONTRACT.md)。历史中的“完整版”及功能对齐表述不替代现行标准版与 Lite 硬契约。

审计日期：2026-10-22。范围为固定版本源码、公开 Release/CI 状态与当前 Aerove 桥接接口；没有修改 Aerove，也没有触碰冻结的 preview worktree。

**结论：完整版 Aerove 保留 Flutter + Go/Mihomo；AEROVE Lite 独立采用 Android 原生界面 + Rust/clash-rs，界面效果和功能近似一致是持续目标。APP 只展示实际支持且可用的功能，不放占位、灰置开关或待补齐入口。clash-rs 值得进入技术原型，当前源码不足以证明 Android 产品已经可发布或功能对等。** AnyTLS、VLESS/REALITY、Hysteria2、TUIC、SS、VMess、MRS、GEOSITE 均有实现；Mieru、sniffer、Android UID/应用归属、部分 DNS/provider 语义及 Android 嵌入、protect、生命周期接口仍有已确认缺口。下文逐项区分已实现、待适配和待实测，不能因使用 Rust 就承诺所有能力已具备。

本报告按用户最新产品决定收口：暂缺能力只列开发记录，不在 APP 暴露不可用入口，也不能宣称支持。导入不支持字段仍须指出具体路径、原因并报错或提供兼容提示，不能静默忽略或未经确认删除语义。双产品独立交付，不将 AEROVE Lite 作为原核心替换；不在 Lite 偷偷回退 Go 或打包双核掩盖缺口。源码语言和产品名称都不能证明省电、省内存或更快。

## 本页目录

- [1. 固定基线与证据边界](#section-1)
- [2. 协议兼容性](#section-2)
- [3. Mihomo 配置与规则：共享格式不等于共享语义](#section-3)
- [4. REST/controller 与 Aerove 自定义协议对照](#section-4)
- [5. Android / FFI / TUN 深审](#section-5)
- [6. 双产品路线、开发差距与发布条件](#section-6)
- [7. 许可与来源](#section-7)
- [8. 测试与性能证据缺口](#section-8)
- [9. 源码索引](#section-9)

<a name="section-1"></a>

## 1. 固定基线与证据边界

| 对象 | 本次固定值 | 核验方式 |
|---|---|---|
| clash-rs 正式基线 | `v0.10.10` → `36eec77463d1243ede287bf6b7197e38237948d5` | GitHub tag ref、commit、Release API 相互核对 |
| Release | 2026-10-05 03:51:30 UTC，`draft=false`、`prerelease=false` | [正式 Release](https://github.com/ibigbug/clash-rs/releases/tag/v0.10.10)；[原始 API](https://api.github.com/repos/ibigbug/clash-rs/releases/tags/v0.10.10) |
| master 快照 | `02b7ee897e80d7a41320b6ce339d09cc7e26ba23`，提交时间 2026-10-21 05:48:58 UTC | 读取 master 后立即以完整 SHA 下载和比较；没有使用移动 `latest` 作为基线 |
| Aerove 主仓库 | `D:/GitHub/FLClash`，`a3ffc6935ee7f34f37f988394c09dcf5bd4affe2` | 只读当前 HEAD；Mihomo gitlink 为 `8380d0e7462f061a964323e9bf3129a236fcc5d5` |
| 来源目录 | `D:/CodexTemp/aerove-independent-20261022/clash-rs` | 顶层为 stable；`_audit/master` 为固定 master 快照 |

Git HTTPS clone 分别遇到 schannel 和单命令 OpenSSL 握手失败，因此改用 `gh api .../tarball/<full SHA>` 下载不可变提交快照。它们是源码快照，不伪装成有完整 Git 历史的 checkout。Python `tarfile` 以 `data` filter 解包，未执行项目脚本。元数据、原始 API 返回和压缩包位于 `_audit/`。

- stable 压缩包 SHA256：`89269275c27bfd70cae2a57e80700d0f393d94a01342e87703a610f0f232a376`
- master 压缩包 SHA256：`8b2fde5c6d35977a9a48ae7270b96ae2f5ed7bb323c05a8974ca50f6c2fab3aa`
- stable 453 个文件逐字节比较，master 仅改动四个文件，无新增文件；所有本报告分析的 Rust 实现文件、FFI、Android target 配置均相同。

master 比 stable 超前三个提交：[固定 compare](https://github.com/ibigbug/clash-rs/compare/36eec77463d1243ede287bf6b7197e38237948d5...02b7ee897e80d7a41320b6ce339d09cc7e26ba23)。`6dfe4e1` 将 `serde_yaml = "0.9"` 替换为包名 `yaml_serde` 的 `0.10`，但保留代码中的 `serde_yaml` 别名；另外两个提交更新 dashboard npm 依赖。变化集中于 `Cargo.lock`、`clash-lib/Cargo.toml`、dashboard 的两个 package 文件。它们没有补齐下面的 Android 或协议阻断，也不能当作已发布稳定版；YAML 解析器变更仍需配置回归。[S-master]

以下“有实现”只代表源码存在并能追到转换/运行路径，不表示本机已经编译、与真实服务端互通，或 Android 真机已经验收。缺少功能时区分：明确报错、默认忽略、解析后运行时不采用。

<a name="section-2"></a>

## 2. 协议兼容性

| 协议 | v0.10.10 的实际实现 | Lite 功能对齐的限制 |
|---|---|---|
| AnyTLS | 枚举、配置转换器、TLS 客户端与数据通道均存在。[S-protocol][S-anytls] | `fingerprint`、`client-fingerprint` 以及 idle-session 参数被解析，但转换器明确警告未应用。不能把 Mihomo 上的指纹/连接池调优效果承诺给用户。 |
| Mieru | `OutboundProxyProtocol` 完整枚举没有该类型；业务 Rust 源检索也无 Mieru 实现。[S-protocol] | **使用 Mieru 的节点不能直接迁移。** 暂缺协议列开发记录，APP 不放不可用入口，也不宣称支持；导入须明确报错。未来若实现，须走 Rust/原生路线验证，不能静默回退 Go。 |
| VLESS + REALITY | `reality-opts` 公钥/short-id 被解码并交给 RealityClient；有 REALITY 握手和 Vision stream 实现。[S-vless][S-reality][S-vision] | `client-fingerprint` 会警告 uTLS 未实现、被忽略。REALITY 存在不等于浏览器指纹行为与 Mihomo 相同；特定服务端和网络上的可用性须实测。 |
| Hysteria2 | 有配置类型、转换器、QUIC outbound、Salamander 和 UDP hopping 模块。[S-hy2] | 应逐字段核对密码、TLS、带宽/拥塞、UDP 和跳端口参数；代码存在不是实际 Android 互通证据。 |
| TUIC | 有配置/转换器/QUIC 实现；`tuic` 是编译 feature，配置使用 UUID/password，并提供 UDP relay、拥塞等参数。[S-tuic][S-features] | 必须启用相应 feature，并核对所需服务端版本与字段。TUIC 还改变项目声明的分发许可条件，见第 7 节。 |
| Shadowsocks | `ss` 由 feature 控制；依赖启用 AEAD-2022、stream 和额外 cipher；转换器处理插件。[S-protocol][S-features] | 构建裁剪不能遗漏 `shadowsocks`；插件/加密套件须逐项验收，转换器对不支持插件明确报错。 |
| VMess | 枚举、转换器、加密数据通道及多种 transport 均存在。[S-protocol][S-vmess] | 不应以名称相同认定所有 transport/header/UDP 参数等价；现成 Docker 测试是基础，不能替代 Aerove 使用的配置样本。 |

master 的这些实现与 stable 字节相同，以上差异没有在 master 消失。

<a name="section-3"></a>

## 3. Mihomo 配置与规则：共享格式不等于共享语义

### 3.1 默认静默忽略是迁移风险

`config/def.rs` 中的普通配置解析允许未知字段；`check_unknown_fields` 才通过 `serde_ignored` 拒绝未知路径。现有 C FFI 调用普通解析，不会自动启用 strict。**“YAML 成功加载”不能作为语义兼容证明。** CLI 的 `--compatibility` 主要设置工作目录及缺省 MMDB/GEOSITE 文件位置，不是通用 Mihomo 配置翻译器，FFI 也没有走这段 CLI 处理。[S-config][S-strict][S-compat][S-ffi]

AEROVE Lite 应对导入、脚本输出、覆写后的最终配置以及后续 provider 更新做能力预检，诊断具体路径和未实现语义。不能原样应用的输入要明确报错或提供兼容提示；不得静默忽略字段、自动删除语义后宣称成功。APP 只展示已验证可用功能，缺失能力留在开发记录；预检拒绝不等于实现了相应能力。以下是已经从源码确认的具体项。

| 方面 | stable 现状 | 对迁移的影响 |
|---|---|---|
| 常用入口 | `port`、`socks-port`、`mixed-port`、`mode`、`allow-lan`、controller、代理/规则/provider 等有 schema。[S-config] | 可做有边界的格式映射，不需要全盘重造订阅格式。 |
| Mihomo 特有顶层功能 | 该 schema 无 `sniffer`、`geox-url`、`geo-auto-update`、`unified-delay`、`tcp-concurrent`、`find-process-mode` 等对应顶层字段。[S-config] | 普通解析可能忽略；Aerove 原 `UpdateParams` 中确实向 Mihomo 发送多项参数。[A-protocol] 不能把 UI 操作默认为仍有效。 |
| Sniffer | 固定树 `clash-lib`、`clash-dns`、`clash-netstack` 的 Rust 源无 sniffer 实现/配置处理；dispatcher 的 fake-IP 反查不等于 TLS/HTTP/QUIC 嗅探。[S-config][S-fakeip] | 导入依赖此能力的配置须具体报错/提示；未实现时不展示开关、不宣称支持，开发记录保留与完整版的差异。 |
| MRS | `RuleSetFormat::Mrs` 和 MRSv1 zstd 解码器存在，支持 domain/ipcidr；classical 不在 MRS 格式支持范围。[S-mrs][S-ruleformat] | **不是缺失 MRS。** 仍须用当前 Mihomo 生成的完整二进制 fixture 验证域名 trie、IPv4/IPv6 范围和坏文件；已有该文件单测主要覆盖 range-to-CIDR 边界，不能充当全量格式互操作证明。 |
| GEOSITE/GeoIP | 有 GEOSITE rule、geodata 数据读取及可选 MMDB；`geosite` 未配置则跳过加载。[S-rules][S-geo] | Aerove 的下载位置/文件名/更新来源需映射到 `geosite`、`mmdb` 等字段；不能原样保留 Mihomo 的 geox 配置就认为已经加载。 |
| 规则种类 | 常见域名、IP-CIDR、端口、NETWORK、RULE-SET、AND/OR/NOT 存在；完整 parser 无 `UID` 分支，对未知类型报 unsupported。[S-rules] | Android UID 规则是明确缺口。不能把进程规则全删掉继续运行。 |
| Android 进程规则 | PROCESS-NAME/PATH 有枚举，但 matcher 仅对 Linux/macOS/Windows 调用 sock2proc；Android 进入 unsupported 分支并返回 false。[S-process] | 即使配置解析成功，Android 应用名规则仍不工作。当前 Aerove 则有 `resolveUid` + `resolvePackage` 回调，需补齐会话归属信息和 matcher。[A-mobile][A-tun] |
| TUN 配置 | 支持 `device-id`，`device`/`device-url` 为别名，可指定 `fd://N`；路由、gateway、MTU 有自己的字段。[S-tunconfig] | 不等于支持 Mihomo 的 `stack: system/mixed/gvisor` 选择与全部 auto-route 行为；Android 路由仍由 VpnService 配置。 |

### 3.2 DNS / fake-IP 的具体差异

- `nameserver: [system]` 会明确记录不支持并跳过；它不是 Aerove 当前经 Android 网络观察器喂入的“当前物理网络 DNS”。该条件不能通过静默替换成公共 DNS 来掩盖。[S-dns-system][A-dns]
- `redir-host` 可被解析为模式，但构造 resolver 时警告未支持、不产生对应实现。`fake-ip` 则有真实实现和可选持久 store。[S-fakeip]
- `nameserver-policy` 类型是 `HashMap<String, String>`；当前 Mihomo 是有序 map 的 `any` 值，允许更丰富的值表达。Mihomo 的列表值不能直接塞入这个单字符串 schema；优先匹配/重叠域名策略也需 golden tests。[S-dns][A-mihomo-dns]
- `fake-ip-filter` 逐条放入 StringTrie，没有 Mihomo 的 rule-set/GEOSITE filter 解析路径；不能把 `geosite:...` 或规则模式的过滤条目当成已实现。[S-fakeip]
- `/cache/dns/flush` 只调用 resolver `clear_cache()`，后者清空反查与响应缓存。这不能替代“物理网络切换时更新系统 DNS、关闭旧 DNS/QUIC 连接、使旧路由探测失效”的整套机制。[S-cache][S-dns-cache][A-dns][A-protocol]

### 3.3 providers 与策略组

有 HTTP/file 代理 provider、HTTP/file/inline 规则 provider、健康检查、订阅刷新 API。策略组支持 relay、url-test、fallback、load-balance、select 和 smart；load-balance 包括 consistent-hashing、round-robin、sticky-session。[S-providers][S-groups][S-api-provider]

但 HTTP proxy provider schema 只有 name/url/interval/path/health-check，不能认定 Mihomo 的 `override`、`filter`、`exclude-filter`、header、provider 下载指定出口等扩展仍生效。组配置也不是字段全集复制：例如原 UI 的固定偏好、自动组选择持久化、include/filter 行为必须逐个映射。Smart 这个类型名的存在只证明有实现，不能证明策略与当前 Mihomo fork 的算法等价。[S-providers][S-groups]

### 3.4 配置构建与脚本属于产品能力

Aerove 的 `setupState` 区分自定义配置、标准扩展和脚本扩展；实际应用链先取得脚本与配置 JSON，调用 `handleEvaluate`，再把 `scriptOutput`、覆写、附加规则和 provider 等送入 `makeRealProfileTask`。[A-profile-state][A-profile-build][A-script-call] 现有脚本执行并非 Mihomo 独占能力：它调用独立 `rust_api` 的 QuickJS 实现，支持 `main(config)`、Promise job queue、10 秒中断和 256 MiB 内存上限。[A-script-engine]

Lite 可以评估复用这段 Rust 逻辑并新增原生桥接；本次没有验证它的 Android 原生构建或新 JNI 接口。若提供脚本功能，必须验证脚本输入/返回值、错误、超时、覆写顺序、自定义配置、订阅刷新及最终出站/规则语义。clash-rs 解析 YAML 或其三函数 C FFI 均不等价于 Aerove 的完整配置构建链。实现和回归未完成前，APP 不展示脚本占位入口；导入依赖脚本或尚不支持的配置语义时明确报错/提示，并在开发记录追踪差距。

<a name="section-4"></a>

## 4. REST/controller 与 Aerove 自定义协议对照

clash-rs 的 Axum controller 有 TCP 和 IPC 入口，但这是 HTTP/WS controller。Aerove Android `Mobile.invokeMethod` 接收的是自定义方法 JSON，经 JNI/gomobile 返回原始字节，Dart 还处理响应错误与 spilled-file 等语义；它不是“同一组 HTTP URL 换个库名”。[S-api][S-ffi][A-mobile][A-dart]

| 产品能力 | clash-rs 可用入口/源码 | 仍需适配的行为 |
|---|---|---|
| 查询/更新配置 | `GET/PATCH /configs`；`PUT /configs` 接收 payload/path，通过 reload channel 等待完成。[S-api-config] | PATCH 字段有限；没有 Aerove 的 `SetupBase`、应用记录/digest、分步 profile 构建/验证等协议。保留错误、完成确认和恢复状态。 |
| 代理与选择 | `GET /proxies`、`PUT /proxies/{name}`、`GET .../delay`；组 API。[S-api-proxy] | 需对齐超时单位、结果模型、选择持久化和取消/过期结果处理。不能直接实现现有 `getLatestDelays` 的完整语义。 |
| providers | `/providers/proxies`、`/providers/rules`，代理更新及 healthcheck。[S-api-provider][S-api] | 与当前 provider 状态、侧载、更新中事件和刷新失败 UI 对齐。 |
| 流量/日志/内存 | `/traffic`、`/logs`、`/memory`；`/ws/{traffic,logs,memory,connections,flows}`。[S-api][S-ws] | 没有 Aerove 的统一 loaded/geoUpdate/routeChanged/DNS/requestTraffic 事件流。需明确定序、背压、订阅停止和后台 telemetry 暂停。 |
| 连接列表与关闭 | `GET/DELETE /connections`、`DELETE /connections/{id}`；可 WS 周期快照。[S-connections] | 快照不是当前请求增量事件；短连接、过期快照与关闭确认不能靠“看起来像 Mihomo JSON”解决。Android Session 也没有现有 UID/package 元数据。 |
| 探测 | proxy delay 可用。[S-api-proxy] | Aerove `probe`、`outboundIp`、`serviceCheck`、`watchRoute` 带有自身结果和路由状态约定；需要引擎内探测或提供等价适配，不能把请求搬到 Flutter HTTP 就认为走相同出口。[A-protocol] |
| 网络改变/缓存 | cache flush API 有限。[S-cache] | C FFI 无 UpdateDns/ResetNetwork 对应入口；物理网络 DNS 和 socket 重建仍是缺口。 |
| 重启 | `POST /restart` 会尝试重新执行当前进程（Unix exec；Windows spawn/exit）。[S-restart] | **不能作为 Android 嵌入库的 engine restart。** 不能把宿主进程重启入口交给生命周期适配器；应在 ServiceState 的同一 owner 内完成受控停止和重建。 |

重载会创建新 RuntimeComponents，然后 stop 旧 TUN/DNS/inbound/statistics，再启动新组件。它不是就地更改所有设置，也没有 Aerove“相同配置只接管、不重推以免自动组重选”的 applied-record 恢复接口。尤其在 `fd://N` 下，旧组件释放 fd 与新组件接管 fd 的时序需重新设计/验证；不能在整个 YAML reload 时反复使用同一个已转移所有权的整数。[S-reload][S-stop][A-protocol] 已进一步核对 lockfile 固定的 `tun-rs 2.8.11`：`AsyncDevice::from_fd` 取得 fd 所有权并在 drop 时关闭，`borrow_raw` 才不取得所有权。[D-tun-fd] 这是静态源码确认的所有权约束；尚未把 reload 后复用 fd 的失败复现为 Android 真机结果。

内存口径也不同：clash-rs `/memory` 的 `inuse` 来自 `memory_stats().physical_mem`。锁定依赖 `memory-stats 1.2.0` 将 Android 的该值定义为当前进程 RSS、Windows 为 Working Set。[S-memory][S-memory-impl][D-memory] 嵌入原生 Android 进程后也包含宿主的内存，不能直接与 Go heap 或其他进程边界的 Core 指标比较。完整版和 Lite 应测各自完整产品的 PSS/RSS，并分开报告实际存在的运行时开销。

<a name="section-5"></a>

## 5. Android / FFI / TUN 深审

### 5.1 有 fd 接入底座，但还不是可替换的 Android Core

正面证据：TunRunner 解析 `fd://N`，Unix 使用 `tun_rs::AsyncDevice::from_fd`；Android/iOS 不允许走自动创建 TUN 的分支，必须由宿主提供 fd。因而通过 VpnService 接入是可实现的方向，而不是需要从零写整个 IP 栈。[S-tun]

当前公开 C ABI 仅有 `clash_start(config, log, cwd, multithread)`、`clash_shutdown()`、`clash_free_string()`。`clash_start` 内调用 `start_scaffold` 并 block_on runtime 主循环，要到停止/错误才返回；不是在 UI 线程调用后立即得到 handle/ready 的非阻塞启动接口。ABI 没有 JNI 方法、回调、独立实例 handle、TUN start/stop、系统 DNS 注入、网络切换或 ready/failed 事件。[S-ffi][S-life]

stock `clash-ffi/Cargo.toml` 显式关闭 clash-lib default features，仅开 SS/TUIC/SSH/zero_copy，未开 `tun`，也未在该依赖声明中选择 crypto provider。工作区 feature 合并可能改变实际构建结果，因此准确结论是：**独立 Android .so 构建必须显式选择 TUN、crypto 和协议 feature 并检查最终依赖图，不能假定现成 FFI 默认具备 VPN 能力。** 不能据此宣称 stock FFI 在所有构建方式下一定编译失败。[S-ffi-cargo][S-features][S-crypto]

### 5.2 protect 与应用归属是两个独立缺口

当前 Aerove 从 `VpnService.Builder.establish().detachFd()` 取得 TUN fd，传入 `Core.startTun(fd, protect, resolveUid, resolvePackage, ...)`；Go socket hook 在创建上游 socket 时调用保护函数，Android Q+ 应用识别也依赖宿主 UID 解析。[A-vpn][A-tun][A-mobile]

clash-rs 的 TCP socket 创建路径在 Android 跳过 interface binding，Android 平台的 bind helper 本身也直接返回 Ok；没有对应宿主 protect 回调。源码注释所说“host handles routing”是宿主必须完成的条件，不能视作已经调用 VpnService.protect。[S-sockets][S-android-bind]

Android 官方说明 protect 用于让隧道的出站 socket 绕开 VPN，避免回环：[VpnService.protect](https://developer.android.com/reference/android/net/VpnService#protect(int))。实际适配必须覆盖 TCP、UDP/QUIC、DNS，以及协议依赖自行创建的 socket，而不是只补一处 TCP connect。

替代做法是让整个宿主 UID 绕过 VPN，但会改变应用自身的请求、DNS和允许/拒绝列表语义，不能悄悄替代当前精确到 socket 的保护策略。是否采用必须作为清楚的产品决定并验证；本次未修改任何路由或访问策略。

即使网络不回环，应用归属仍缺失：Session 无 Android UID/package 字段，进程 matcher 在 Android 返回 false。需增加宿主查询、会话携带及规则求值支持，才能保留当前 UID/应用规则和连接归属 UI。[S-process][S-session][A-tun]

新增 JNI 桥接还需要明确 JavaVM/thread attach、全局引用释放、异常转译和回调失效时的返回策略，避免销毁后回调或跨线程复用 JNIEnv。这里是设计验收要求，不是对候选实现中不存在的 JNI 代码提出漏洞指控。[Android JNI 官方说明](https://developer.android.com/ndk/guides/jni-tips)

### 5.3 生命周期与并行 runtime

- `start_scaffold` 把 token 放到进程级 Vec；`shutdown()` drain 并取消全部 token，返回 true 仅表示发出取消信号，未等待线程/TUN/端口退出。[S-life]
- Rust 库另有 `start_scaffold_instance` 返回 JoinHandle + CancellationToken，这是可利用的独立生命周期底座；但它没有暴露到 C FFI，也没有 ready/error 完成确认。[S-life]
- 即使使用 instance 接口，默认出站接口、TUN SO_MARK、AnyTLS buffer 配置、crypto provider 等仍存在进程级共享状态；不能把“多建一个 Tokio runtime”当成配置和资源完全隔离。[S-global][S-any-global][S-crypto]
- 上游 throughput workflow 还明确记载 all-features telemetry 初始化在并发启动时曾出现死锁，因此专门避免这种 binary 构建方式。这是上游记录的限制，不是本机已经复现的新缺陷。[S-perf-ci]
- Aerove 已由 ServiceStateMachine/ServiceController 仲裁最新 start/stop 意图，并区分配置、Core listener 和系统 VPN 的存续。Lite 独立实现时仍须保留同等的单一 owner 和 latest-intent 语义；引擎只报告真实状态，不得把 callback 当成用户 start 意图，也不能只把 C shutdown 的 bool 当成完成。[A-owner][A-mobile]

因此，Lite 原型应先保证单个活动 clash-rs VPN runtime 的正确启动、停止和重建；重建前确认旧 runtime 停止、回调解除及 fd 归属。Lite 不包含 Go 回退。若配置验证或其他用途确需并行 runtime，先移除/隔离这些 global 状态，再证明关闭一个不影响另一个；全进程 shutdown 不可冒充实例级停止。

### 5.4 实际 Android 发布与 CI 证据

**有 Android 交叉编译证据，但没有发布级 Android VPN 验收证据。**

1. `.github/target.toml:199–207` 只有 `x86_64-linux-android` target，明确 `skip-upload=true`、`skip-test=true`。[S-android-ci]
2. 该 target 使用 CLI 的 `android` feature；其定义未包含 `tun`。所以它不是对上面 fd/TUN 路径的 Android 编译覆盖，也不是 JNI/AAR/APK 构建。[S-bin-features]
3. 固定 master 的 CI run `39551696456` 中，Android job `119062234209` 确实 completed/success：[实际 job](https://github.com/ibigbug/clash-rs/actions/runs/39551696456/job/119062234209)。整个 workflow 的 failure 来自 Docker 发布 job，不能误报为 Android 编译失败。
4. 这个成功不能挪作 stable 的验证：按 stable full SHA 查询 Actions runs 当前返回空数组。它不证明从未跑过 CI，只说明本次未取得该 SHA 的 run 证据。原始结果保存在 `_audit/stable_ci_runs.json`；master job 元数据保存在 `_audit/master_ci_jobs.json`。
5. Release API 列出 25 个资产（含 LICENSE），未见 Android APK/AAR/.so；发布脚本的移动端 framework 路线面向 Apple。主 CI 构建的是 `clash-rs` CLI；coverage 还明确 `--exclude clash-ffi`。[S-ci][S-coverage][S-apple]

仍缺少 aarch64-linux-android + TUN + 目标 crypto/protocol features 的构建、Android shared library 打包、真实 VpnService.protect、权限/revoke/Always-on/后台恢复、Wi-Fi/蜂窝切换、arm64 真机和 APK 发布产物证据。不能把 Linux aarch64 二进制视作 Android arm64 产物。

<a name="section-6"></a>

## 6. 双产品路线、开发差距与发布条件

本节按用户最新决定作为主线程产品治理和样板架构的源码输入。完整版 Aerove 持续使用 Flutter + Go/Mihomo；产品名固定为 **AEROVE Lite**，采用独立 Android 原生界面 + Rust/clash-rs。近似的界面效果和功能一致性是持续目标；每个版本只展示、声明已支持且实际可用的功能。暂缺能力只作开发记录，不在 APP 放占位、灰置开关或待补齐入口。

| 分类 | 当前源码证据/缺口 | 对产品与验收的要求 |
|---|---|---|
| Android 数据面发布阻断 | 现有 CI 不覆盖 ARM64 TUN；stock FFI feature 不保证 VPN；socket 无宿主 protect | 固定依赖的 arm64 .so/APK；全 TCP/UDP/QUIC/DNS socket 保护；fd 接管/停止/重载；真机无回环和错误出口 |
| 运行控制发布阻断 | C ABI 缺 handle、ready、停止完成、网络切换；全局 shutdown 取消全部实例 | 原生单一生命周期 owner；latest-intent；实例级启动/取消/join；network/DNS 变更和有界事件；后台与 revoke 回归 |
| 功能差距开发记录 | Mieru、sniffer、UID/package/进程规则及部分 DNS/provider 语义未实现或未等价 | APP 不暴露不可用能力，不宣称支持；导入具体报错/兼容提示；后续实现通过真实回归后才开放 |
| 产品能力开发记录 | 配置/脚本/覆写链、自定义探测、route epoch、连接归属、后台 telemetry 不由 clash-rs API 自动提供 | 拟提供的功能先实现、再验收；可复用既有 Rust/QuickJS，但需验证完整流水线。必要的后台安全与资源管理不能因不展示入口而跳过 |
| 已展示界面与交互验收 | 引擎审计没有验证原生界面、可访问性、动效、状态和用户操作 | 主线程维护视觉契约；每个展示入口真实可用，结果与声明一致；没有占位、灰置或无效开关 |
| 分发发布阻断 | TUIC feature 影响许可声明；新 Android 产物和依赖来源未形成发布证据 | 固定 feature/lockfile/NDK/CI run、SBOM、许可和来源清单；保留 Aerove/FlClash 的 GPL 与版权来源 |

建议分四个阶段推进，并按每阶段真实范围记录完成情况：

1. **记录基线和版本能力。** 从 Aerove Android 的配置/脚本、协议、规则、探测、连接、系统 VPN/后台行为提取对照 fixtures，由主线程关联视觉契约。维护 AEROVE Lite 每版实际支持清单和开发差距；不将近似一致的长期目标写成当前已达成。
2. **构建独立原生/Rust 接入。** 原生层持有唯一服务意图，Rust shim 提供实例 handle、非 UI 阻塞启动、ready/error、cancel+join、apply/reload、TUN fd、protect、网络/DNS和必要事件接口。protect 等平台前提缺失时阻止启动 VPN，不能靠隐藏设置规避。AEROVE Lite 不打包 Go 内核，不偷偷回退完整版引擎。
3. **按可用能力开放功能。** APP 只展示通过验收的能力；缺失功能留在开发记录。对导入、脚本输出、覆写和 provider 更新做具体路径预检，报错或说明不兼容原因；不能静默忽略字段或自动删语义。兼容转换如需改变效果，必须清楚说明并验证，不能冒充原配置等价支持。
4. **以已声明范围验收发布，持续缩小差距。** 必需的 Android 数据面、生命周期、CI/真机、许可与来源验证通过后，再按实际可用能力发布。展示的每个功能和导入兼容声明均须有测试证据；Mieru/sniffer/UID/DNS/脚本等未完成项不得宣称支持，也不在 APP 提供待补齐入口。后续沿 Rust/原生路线逐项实现，性能宣称以公平实测为依据。

REST/WS 可用于原型控制面实验，不能补齐 fd/protect/UID。原生界面不必复刻 Dart/gomobile 的传输封装；已提供功能须保持清楚、可信的产品行为。上述阶段没有执行代理启动、网络更改、构建或源码改动。

<a name="section-7"></a>

## 7. 许可与来源

- clash-rs 固定 stable 根 `LICENSE` 是 Apache License 2.0；**同一版本 `NOTICE.md:1–4` 明确声明启用 `tuic` feature 时按 GPL3 分发，否则 Apache 2.0**。CLI 默认 standard 和 stock FFI 均包含 TUIC，不能只摘根许可证判断所有构建产物。[S-license][S-notice][S-bin-features][S-ffi-cargo]
- 被引用的 `Itsusinn/tuic` 固定 tag `v1.8.5` 的 LICENSE API 返回 `GPL-3.0`：[许可证原文件](https://github.com/Itsusinn/tuic/blob/v1.8.5/LICENSE)。本次未扩展为全依赖法律审查；锁定版本/feature 后仍需生成依赖许可证清单，并保留适用 NOTICE/版权和修改来源。
- Aerove 当前仓库保留 GPL-3.0 LICENSE，README 也链接 GPL-3.0。[A-license] 更换代理内核不构成删除现有 Aerove/FlClash GPL 和版权来源的依据。独立重构应记录新增独立模块与沿用/修改模块的来源边界，而不是重新贴一个统一宽松标签。
- 以上是源码和项目声明核对，不对整个重构的衍生关系、专利或具体分发方案作未经验证的法律结论。

<a name="section-8"></a>

## 8. 测试与性能证据缺口

**本次实际完成：** 固定 tag/Release/SHA 核对；stable/master 两个源码快照；453 文件内容比较；配置、协议、FFI、TUN、socket、controller、生命周期与 CI 源码审查；公开 Actions Android job 状态读取；与 Aerove 主仓库固定 HEAD 的接口对照。没有运行 Cargo/Go/Flutter，没有安装工具或执行主机代理/路由命令。

已有证据值得利用：协议 Docker 测试、API tests、MRS 数值边界 tests、Linux TUN/吞吐 benchmark 脚本；coverage 在三种桌面 OS 上运行。它们的范围与 Android 全功能差距需要明确：FFI 被排除；TUN 部分本地测试显式 ignore；Docker/throughput 测试受 cfg/env gate 约束，部分服务端镜像使用移动 latest，复现实验前须钉住版本或 digest。[S-test-tun][S-test-proto][S-coverage][S-perf-ci]

仓库内 `bench/baseline-tun-benchmark.json` 记录的是 WSL2/Linux、Ryzen 9950X3D、10 秒样本：直接基线约 91.92 Gbit/s，TUN 约 6.485 Gbit/s。它没有同设备同参数的 Mihomo 样本，也不是 Android 电池/后台实验，不能推出 Lite 相对完整版的性能提升。[S-baseline] 此数值仅说明既有可复用的测量产物和环境口径，不能作为用户手机收益预测。

在 AEROVE Lite 对外发布前，必需平台能力及所有实际展示/声明支持的功能都要通过真实验收；以下缺失功能用例保留为开发记录，不能把未执行或未实现写成通过：

| 验收层 | 必需用例与证据 |
|---|---|
| 配置语义 | 实际会使用的脱敏订阅/脚本/覆写样本；未知字段 fail-closed；system DNS、redir-host、fakeip filter/policy、MRS 二进制、GEOSITE、provider 过滤与组选择 golden tests。 |
| 协议与路由 | 对实际声明支持的协议使用固定服务端版本验证 TCP/UDP、IPv4/IPv6、证书/指纹、重连、错误认证和切网；Mieru/sniffer/UID 等缺口留作开发记录，未通过前不展示、不宣称支持；相关配置导入明确报错/提示。 |
| Android 数据面 | aarch64 + TUN + 明确 feature 构建；宿主 fd 生命周期；TCP/UDP/QUIC/DNS socket protect；UID/package；拒绝 fd、撤销权限、无网及连接失败不能留下空跑 VPN。 |
| 生命周期 | 快速 start/stop/restart 交错、启动中取消、engine 重建、Always-on、系统 revoke、后台/前台、Wi-Fi/蜂窝转换；退出确认后无旧线程/fd/callback；若支持双实例，关闭一个不能影响另一个。 |
| 控制、脚本与界面 | 实际开放 API 的成功/错误/超时；若提供脚本，验证返回/Promise/异常/限时、覆写顺序；验证适用的 provider、快照/增量、route epoch、队列及后台订阅；界面仅有真实可用入口，无占位/灰置/待补齐入口。 |
| 性能 | 同一 arm64 手机、同构建模式和协议/服务器，分别记录冷启动、配置加载、规则命中、TCP/UDP吞吐、延迟分位数、PSS/RSS/native heap、Flutter/Java 开销、CPU、wakeups、后台空闲和切网耗电；控制配置/网络/温度，重复测量并报告分布。无此证据不声称 Rust 更省资源。 |
| 交付来源 | 可重复的固定 commit/lockfile/NDK 构建、最终 .so/APK 核验、CI run 和许可/feature 清单；不能依赖未发布 master 或移动 latest 下载。 |

<a name="section-9"></a>

## 9. 源码索引

以下索引由本次固定快照生成；每项提供文件/行号、完整 commit、GitHub 行链接和 raw 文件链接。`S-*` 均指 stable；master 差异单列，`A-*` 指当前 Aerove 主仓库，不是冻结的 preview；`D-*` 指 stable lockfile 锁定依赖。

- **S-master** — `clash-lib/Cargo.toml:153–161`；commit `02b7ee897e80d7a41320b6ce339d09cc7e26ba23`；[源码行](https://github.com/ibigbug/clash-rs/blob/02b7ee897e80d7a41320b6ce339d09cc7e26ba23/clash-lib/Cargo.toml#L153-L161) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/02b7ee897e80d7a41320b6ce339d09cc7e26ba23/clash-lib/Cargo.toml)
- **S-protocol** — `clash-lib/src/config/internal/proxy.rs:47–87`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/config/internal/proxy.rs#L47-L87) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/config/internal/proxy.rs)
- **S-anytls** — `clash-lib/src/proxy/converters/anytls.rs:23–76`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/converters/anytls.rs#L23-L76) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/converters/anytls.rs)
- **S-vless** — `clash-lib/src/proxy/converters/vless.rs:23–69`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/converters/vless.rs#L23-L69) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/converters/vless.rs)
- **S-reality** — `clash-lib/src/proxy/transport/reality.rs:18–70`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/transport/reality.rs#L18-L70) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/transport/reality.rs)
- **S-vision** — `clash-lib/src/proxy/vless/mod.rs:68–103`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/vless/mod.rs#L68-L103) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/vless/mod.rs)
- **S-hy2** — `clash-lib/src/proxy/converters/hysteria2.rs:1–115`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/converters/hysteria2.rs#L1-L115) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/converters/hysteria2.rs)
- **S-tuic** — `clash-lib/src/config/internal/proxy.rs:394–427`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/config/internal/proxy.rs#L394-L427) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/config/internal/proxy.rs)
- **S-features** — `clash-lib/Cargo.toml:7–205`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/Cargo.toml#L7-L205) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/Cargo.toml)
- **S-vmess** — `clash-lib/src/proxy/converters/vmess.rs:1–144`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/converters/vmess.rs#L1-L144) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/converters/vmess.rs)
- **S-config** — `clash-lib/src/config/def.rs:368–498`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/config/def.rs#L368-L498) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/config/def.rs)
- **S-strict** — `clash-lib/src/config/def.rs:532–562`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/config/def.rs#L532-L562) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/config/def.rs)
- **S-compat** — `clash-bin/src/main.rs:284–323`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-bin/src/main.rs#L284-L323) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-bin/src/main.rs)
- **S-ffi** — `clash-ffi/src/lib.rs:1–65`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-ffi/src/lib.rs#L1-L65) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-ffi/src/lib.rs)
- **S-mrs** — `clash-lib/src/app/remote_content_manager/providers/rule_provider/mrs.rs:17–108`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/remote_content_manager/providers/rule_provider/mrs.rs#L17-L108) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/remote_content_manager/providers/rule_provider/mrs.rs)
- **S-ruleformat** — `clash-lib/src/app/remote_content_manager/providers/rule_provider/provider.rs:36–66`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/remote_content_manager/providers/rule_provider/provider.rs#L36-L66) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/remote_content_manager/providers/rule_provider/provider.rs)
- **S-rules** — `clash-lib/src/config/internal/rule.rs:293–402`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/config/internal/rule.rs#L293-L402) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/config/internal/rule.rs)
- **S-geo** — `clash-lib/src/lib.rs:580–615`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/lib.rs#L580-L615) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/lib.rs)
- **S-process** — `clash-lib/src/app/router/rules/process.rs:17–60`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/router/rules/process.rs#L17-L60) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/router/rules/process.rs)
- **S-tunconfig** — `clash-lib/src/config/def.rs:49–89`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/config/def.rs#L49-L89) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/config/def.rs)
- **S-dns-system** — `clash-lib/src/app/dns/config.rs:73–83`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/dns/config.rs#L73-L83) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/dns/config.rs)
- **S-dns** — `clash-lib/src/config/def.rs:664–706`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/config/def.rs#L664-L706) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/config/def.rs)
- **S-fakeip** — `clash-lib/src/app/dns/resolver/enhanced.rs:273–298`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/dns/resolver/enhanced.rs#L273-L298) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/dns/resolver/enhanced.rs)
- **S-cache** — `clash-lib/src/app/api/handlers/cache.rs:18–45`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/api/handlers/cache.rs#L18-L45) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/api/handlers/cache.rs)
- **S-dns-cache** — `clash-lib/src/app/dns/resolver/enhanced.rs:736–754`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/dns/resolver/enhanced.rs#L736-L754) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/dns/resolver/enhanced.rs)
- **S-providers** — `clash-lib/src/config/internal/proxy.rs:730–771`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/config/internal/proxy.rs#L730-L771) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/config/internal/proxy.rs)
- **S-groups** — `clash-lib/src/config/internal/proxy.rs:561–725`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/config/internal/proxy.rs#L561-L725) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/config/internal/proxy.rs)
- **S-api-provider** — `clash-lib/src/app/api/handlers/provider.rs:46–80`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/api/handlers/provider.rs#L46-L80) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/api/handlers/provider.rs)
- **S-api** — `clash-lib/src/app/api/runner.rs:132–174`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/api/runner.rs#L132-L174) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/api/runner.rs)
- **S-api-config** — `clash-lib/src/app/api/handlers/config.rs:149–246`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/api/handlers/config.rs#L149-L246) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/api/handlers/config.rs)
- **S-api-proxy** — `clash-lib/src/app/api/handlers/proxy.rs:50–69`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/api/handlers/proxy.rs#L50-L69) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/api/handlers/proxy.rs)
- **S-ws** — `clash-lib/src/app/api/websocket.rs:24–31`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/api/websocket.rs#L24-L31) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/api/websocket.rs)
- **S-connections** — `clash-lib/src/app/api/handlers/connection.rs:39–114`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/api/handlers/connection.rs#L39-L114) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/api/handlers/connection.rs)
- **S-restart** — `clash-lib/src/app/api/handlers/restart.rs:7–49`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/api/handlers/restart.rs#L7-L49) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/api/handlers/restart.rs)
- **S-reload** — `clash-lib/src/lib.rs:332–385`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/lib.rs#L332-L385) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/lib.rs)
- **S-stop** — `clash-lib/src/lib.rs:414–442`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/lib.rs#L414-L442) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/lib.rs)
- **S-memory** — `clash-lib/src/app/api/handlers/memory.rs:14–27`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/api/handlers/memory.rs#L14-L27) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/api/handlers/memory.rs)
- **S-memory-impl** — `clash-lib/src/app/dispatcher/statistics_manager.rs:569–571`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/dispatcher/statistics_manager.rs#L569-L571) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/dispatcher/statistics_manager.rs)
- **S-tun** — `clash-lib/src/proxy/tun/runner.rs:70–300`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/tun/runner.rs#L70-L300) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/tun/runner.rs)
- **S-life** — `clash-lib/src/lib.rs:146–252`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/lib.rs#L146-L252) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/lib.rs)
- **S-ffi-cargo** — `clash-ffi/Cargo.toml:1–12`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-ffi/Cargo.toml#L1-L12) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-ffi/Cargo.toml)
- **S-crypto** — `clash-lib/src/lib.rs:255–267`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/lib.rs#L255-L267) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/lib.rs)
- **S-sockets** — `clash-lib/src/proxy/utils/socket_helpers.rs:43–90`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/utils/socket_helpers.rs#L43-L90) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/utils/socket_helpers.rs)
- **S-android-bind** — `clash-lib/src/proxy/utils/platform/unix.rs:5–24`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/utils/platform/unix.rs#L5-L24) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/utils/platform/unix.rs)
- **S-session** — `clash-lib/src/session.rs:463–488`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/session.rs#L463-L488) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/session.rs)
- **S-global** — `clash-lib/src/app/net/mod.rs:13–31`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/net/mod.rs#L13-L31) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/net/mod.rs)
- **S-any-global** — `clash-lib/src/proxy/anytls/mod.rs:60–73`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/anytls/mod.rs#L60-L73) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/anytls/mod.rs)
- **S-perf-ci** — `.github/workflows/proxy-throughput.yml:47–116`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/.github/workflows/proxy-throughput.yml#L47-L116) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/.github/workflows/proxy-throughput.yml)
- **S-android-ci** — `.github/target.toml:199–207`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/.github/target.toml#L199-L207) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/.github/target.toml)
- **S-bin-features** — `clash-bin/Cargo.toml:9–28`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-bin/Cargo.toml#L9-L28) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-bin/Cargo.toml)
- **S-ci** — `.github/workflows/ci.yml:27–40`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/.github/workflows/ci.yml#L27-L40) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/.github/workflows/ci.yml)
- **S-coverage** — `.github/workflows/coverage.yml:38–51`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/.github/workflows/coverage.yml#L38-L51) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/.github/workflows/coverage.yml)
- **S-apple** — `scripts/build_apple.sh:1–18`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/scripts/build_apple.sh#L1-L18) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/scripts/build_apple.sh)
- **S-license** — `LICENSE:1–13`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/LICENSE#L1-L13) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/LICENSE)
- **S-notice** — `NOTICE.md:1–4`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/NOTICE.md#L1-L4) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/NOTICE.md)
- **S-test-tun** — `clash-lib/src/proxy/tun/mod.rs:9–37`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/tun/mod.rs#L9-L37) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/tun/mod.rs)
- **S-test-proto** — `clash-lib/src/proxy/vmess/mod.rs:236–275`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/vmess/mod.rs#L236-L275) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/vmess/mod.rs)
- **S-baseline** — `bench/baseline-tun-benchmark.json:1–44`；commit `36eec77463d1243ede287bf6b7197e38237948d5`；[源码行](https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/bench/baseline-tun-benchmark.json#L1-L44) · [raw](https://raw.githubusercontent.com/ibigbug/clash-rs/36eec77463d1243ede287bf6b7197e38237948d5/bench/baseline-tun-benchmark.json)
- **A-protocol** — `core/protocol.go:19–159`；commit `a3ffc6935ee7f34f37f988394c09dcf5bd4affe2`；[源码行](https://github.com/IvanSolis1989/Aerove/blob/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/core/protocol.go#L19-L159) · [raw](https://raw.githubusercontent.com/IvanSolis1989/Aerove/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/core/protocol.go)
- **A-mobile** — `core/mobile/mobile.go:1–116`；commit `a3ffc6935ee7f34f37f988394c09dcf5bd4affe2`；[源码行](https://github.com/IvanSolis1989/Aerove/blob/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/core/mobile/mobile.go#L1-L116) · [raw](https://raw.githubusercontent.com/IvanSolis1989/Aerove/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/core/mobile/mobile.go)
- **A-tun** — `core/lib.go:25–146`；commit `a3ffc6935ee7f34f37f988394c09dcf5bd4affe2`；[源码行](https://github.com/IvanSolis1989/Aerove/blob/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/core/lib.go#L25-L146) · [raw](https://raw.githubusercontent.com/IvanSolis1989/Aerove/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/core/lib.go)
- **A-dart** — `lib/core/method.dart:60–134`；commit `a3ffc6935ee7f34f37f988394c09dcf5bd4affe2`；[源码行](https://github.com/IvanSolis1989/Aerove/blob/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/lib/core/method.dart#L60-L134) · [raw](https://raw.githubusercontent.com/IvanSolis1989/Aerove/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/lib/core/method.dart)
- **A-dns** — `core/lib.go:357–401`；commit `a3ffc6935ee7f34f37f988394c09dcf5bd4affe2`；[源码行](https://github.com/IvanSolis1989/Aerove/blob/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/core/lib.go#L357-L401) · [raw](https://raw.githubusercontent.com/IvanSolis1989/Aerove/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/core/lib.go)
- **A-mihomo-dns** — `config/config.go:232–260`；commit `8380d0e7462f061a964323e9bf3129a236fcc5d5`；[源码行](https://github.com/chen08209/Clash.Meta/blob/8380d0e7462f061a964323e9bf3129a236fcc5d5/config/config.go#L232-L260) · [raw](https://raw.githubusercontent.com/chen08209/Clash.Meta/8380d0e7462f061a964323e9bf3129a236fcc5d5/config/config.go)
- **A-vpn** — `android/service/src/main/java/com/follow/clash/service/VpnService.kt:126–175`；commit `a3ffc6935ee7f34f37f988394c09dcf5bd4affe2`；[源码行](https://github.com/IvanSolis1989/Aerove/blob/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/android/service/src/main/java/com/follow/clash/service/VpnService.kt#L126-L175) · [raw](https://raw.githubusercontent.com/IvanSolis1989/Aerove/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/android/service/src/main/java/com/follow/clash/service/VpnService.kt)
- **A-owner** — `android/app/src/main/kotlin/com/follow/clash/ServiceStateMachine.kt:40–149`；commit `a3ffc6935ee7f34f37f988394c09dcf5bd4affe2`；[源码行](https://github.com/IvanSolis1989/Aerove/blob/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/android/app/src/main/kotlin/com/follow/clash/ServiceStateMachine.kt#L40-L149) · [raw](https://raw.githubusercontent.com/IvanSolis1989/Aerove/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/android/app/src/main/kotlin/com/follow/clash/ServiceStateMachine.kt)
- **A-license** — `LICENSE:1–10`；commit `a3ffc6935ee7f34f37f988394c09dcf5bd4affe2`；[源码行](https://github.com/IvanSolis1989/Aerove/blob/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/LICENSE#L1-L10) · [raw](https://raw.githubusercontent.com/IvanSolis1989/Aerove/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/LICENSE)

- **D-tun-fd** — `src/async_device/unix/mod.rs:52–66`；tun-rs commit `d0f764c135a34ad92360462f89a40b59f373cc06`；[源码行](https://github.com/tun-rs/tun-rs/blob/d0f764c135a34ad92360462f89a40b59f373cc06/src/async_device/unix/mod.rs#L52-L66) · [raw](https://raw.githubusercontent.com/tun-rs/tun-rs/d0f764c135a34ad92360462f89a40b59f373cc06/src/async_device/unix/mod.rs)
- **D-memory** — `src/lib.rs:81–109`；memory-stats commit `431e74654c27ec38a6f7d19c186bec42c2a938b8`；[源码行](https://github.com/Arc-blroth/memory-stats/blob/431e74654c27ec38a6f7d19c186bec42c2a938b8/src/lib.rs#L81-L109) · [raw](https://raw.githubusercontent.com/Arc-blroth/memory-stats/431e74654c27ec38a6f7d19c186bec42c2a938b8/src/lib.rs)
- **A-profile-state** — `lib/providers/state/profile.dart:47–114`；Aerove commit `a3ffc6935ee7f34f37f988394c09dcf5bd4affe2`；[源码行](https://github.com/IvanSolis1989/Aerove/blob/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/lib/providers/state/profile.dart#L47-L114) · [raw](https://raw.githubusercontent.com/IvanSolis1989/Aerove/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/lib/providers/state/profile.dart)
- **A-profile-build** — `lib/providers/actions/setup.dart:698–748`；Aerove commit `a3ffc6935ee7f34f37f988394c09dcf5bd4affe2`；[源码行](https://github.com/IvanSolis1989/Aerove/blob/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/lib/providers/actions/setup.dart#L698-L748) · [raw](https://raw.githubusercontent.com/IvanSolis1989/Aerove/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/lib/providers/actions/setup.dart)
- **A-script-call** — `lib/common/javascript.dart:5–30`；Aerove commit `a3ffc6935ee7f34f37f988394c09dcf5bd4affe2`；[源码行](https://github.com/IvanSolis1989/Aerove/blob/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/lib/common/javascript.dart#L5-L30) · [raw](https://raw.githubusercontent.com/IvanSolis1989/Aerove/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/lib/common/javascript.dart)
- **A-script-engine** — `plugins/rust_api/rust/src/script/mod.rs:3–63`；Aerove commit `a3ffc6935ee7f34f37f988394c09dcf5bd4affe2`；[源码行](https://github.com/IvanSolis1989/Aerove/blob/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/plugins/rust_api/rust/src/script/mod.rs#L3-L63) · [raw](https://raw.githubusercontent.com/IvanSolis1989/Aerove/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/plugins/rust_api/rust/src/script/mod.rs)

锁定依赖来源额外核验：下载 crates.io `tun-rs 2.8.11` 和 `memory-stats 1.2.0` 源码包，以 SHA256 对照 stable `Cargo.lock`，再由包内 `.cargo_vcs_info.json` 取得上述完整 commit；未执行依赖代码。前者 SHA256 为 `19e3459c60bb1cd4a5969fced4299be335b3e3169d8cb3466ffe23da56eeeeb4`，后者为 `c73f5c649995a115e1a0220b35e4df0a1294500477f97a91d0660fb5abeb574a`。

[D-tun-fd]: https://github.com/tun-rs/tun-rs/blob/d0f764c135a34ad92360462f89a40b59f373cc06/src/async_device/unix/mod.rs#L52-L66
[D-memory]: https://github.com/Arc-blroth/memory-stats/blob/431e74654c27ec38a6f7d19c186bec42c2a938b8/src/lib.rs#L81-L109
[A-profile-state]: https://github.com/IvanSolis1989/Aerove/blob/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/lib/providers/state/profile.dart#L47-L114
[A-profile-build]: https://github.com/IvanSolis1989/Aerove/blob/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/lib/providers/actions/setup.dart#L698-L748
[A-script-call]: https://github.com/IvanSolis1989/Aerove/blob/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/lib/common/javascript.dart#L5-L30
[A-script-engine]: https://github.com/IvanSolis1989/Aerove/blob/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/plugins/rust_api/rust/src/script/mod.rs#L3-L63
[S-master]: https://github.com/ibigbug/clash-rs/blob/02b7ee897e80d7a41320b6ce339d09cc7e26ba23/clash-lib/Cargo.toml#L153-L161
[S-protocol]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/config/internal/proxy.rs#L47-L87
[S-anytls]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/converters/anytls.rs#L23-L76
[S-vless]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/converters/vless.rs#L23-L69
[S-reality]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/transport/reality.rs#L18-L70
[S-vision]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/vless/mod.rs#L68-L103
[S-hy2]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/converters/hysteria2.rs#L1-L115
[S-tuic]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/config/internal/proxy.rs#L394-L427
[S-features]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/Cargo.toml#L7-L205
[S-vmess]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/converters/vmess.rs#L1-L144
[S-config]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/config/def.rs#L368-L498
[S-strict]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/config/def.rs#L532-L562
[S-compat]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-bin/src/main.rs#L284-L323
[S-ffi]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-ffi/src/lib.rs#L1-L65
[S-mrs]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/remote_content_manager/providers/rule_provider/mrs.rs#L17-L108
[S-ruleformat]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/remote_content_manager/providers/rule_provider/provider.rs#L36-L66
[S-rules]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/config/internal/rule.rs#L293-L402
[S-geo]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/lib.rs#L580-L615
[S-process]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/router/rules/process.rs#L17-L60
[S-tunconfig]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/config/def.rs#L49-L89
[S-dns-system]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/dns/config.rs#L73-L83
[S-dns]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/config/def.rs#L664-L706
[S-fakeip]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/dns/resolver/enhanced.rs#L273-L298
[S-cache]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/api/handlers/cache.rs#L18-L45
[S-dns-cache]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/dns/resolver/enhanced.rs#L736-L754
[S-providers]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/config/internal/proxy.rs#L730-L771
[S-groups]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/config/internal/proxy.rs#L561-L725
[S-api-provider]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/api/handlers/provider.rs#L46-L80
[S-api]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/api/runner.rs#L132-L174
[S-api-config]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/api/handlers/config.rs#L149-L246
[S-api-proxy]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/api/handlers/proxy.rs#L50-L69
[S-ws]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/api/websocket.rs#L24-L31
[S-connections]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/api/handlers/connection.rs#L39-L114
[S-restart]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/api/handlers/restart.rs#L7-L49
[S-reload]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/lib.rs#L332-L385
[S-stop]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/lib.rs#L414-L442
[S-memory]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/api/handlers/memory.rs#L14-L27
[S-memory-impl]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/dispatcher/statistics_manager.rs#L569-L571
[S-tun]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/tun/runner.rs#L70-L300
[S-life]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/lib.rs#L146-L252
[S-ffi-cargo]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-ffi/Cargo.toml#L1-L12
[S-crypto]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/lib.rs#L255-L267
[S-sockets]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/utils/socket_helpers.rs#L43-L90
[S-android-bind]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/utils/platform/unix.rs#L5-L24
[S-session]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/session.rs#L463-L488
[S-global]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/app/net/mod.rs#L13-L31
[S-any-global]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/anytls/mod.rs#L60-L73
[S-perf-ci]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/.github/workflows/proxy-throughput.yml#L47-L116
[S-android-ci]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/.github/target.toml#L199-L207
[S-bin-features]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-bin/Cargo.toml#L9-L28
[S-ci]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/.github/workflows/ci.yml#L27-L40
[S-coverage]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/.github/workflows/coverage.yml#L38-L51
[S-apple]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/scripts/build_apple.sh#L1-L18
[S-license]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/LICENSE#L1-L13
[S-notice]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/NOTICE.md#L1-L4
[S-test-tun]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/tun/mod.rs#L9-L37
[S-test-proto]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/clash-lib/src/proxy/vmess/mod.rs#L236-L275
[S-baseline]: https://github.com/ibigbug/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/bench/baseline-tun-benchmark.json#L1-L44
[A-protocol]: https://github.com/IvanSolis1989/Aerove/blob/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/core/protocol.go#L19-L159
[A-mobile]: https://github.com/IvanSolis1989/Aerove/blob/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/core/mobile/mobile.go#L1-L116
[A-tun]: https://github.com/IvanSolis1989/Aerove/blob/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/core/lib.go#L25-L146
[A-dart]: https://github.com/IvanSolis1989/Aerove/blob/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/lib/core/method.dart#L60-L134
[A-dns]: https://github.com/IvanSolis1989/Aerove/blob/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/core/lib.go#L357-L401
[A-mihomo-dns]: https://github.com/chen08209/Clash.Meta/blob/8380d0e7462f061a964323e9bf3129a236fcc5d5/config/config.go#L232-L260
[A-vpn]: https://github.com/IvanSolis1989/Aerove/blob/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/android/service/src/main/java/com/follow/clash/service/VpnService.kt#L126-L175
[A-owner]: https://github.com/IvanSolis1989/Aerove/blob/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/android/app/src/main/kotlin/com/follow/clash/ServiceStateMachine.kt#L40-L149
[A-license]: https://github.com/IvanSolis1989/Aerove/blob/a3ffc6935ee7f34f37f988394c09dcf5bd4affe2/LICENSE#L1-L10

---

下一步：[Lite 说明](../README.md) · [共同契约](../../shared/UI_CONTRACT.md)
