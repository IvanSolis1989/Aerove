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

当前文档目标候选为 **0.1.0-beta.16 / code 16**；beta.15/code 15 未发行，已被当前候选取代。host123（source `bd83a3f5d9ad44be8ac3c87460279133af34ca17`）Native 42 个 targets 全部通过，Core 32 条命令/179 个命中、compileCheck 与 featureBoundary 均通过且锁恢复；整次 run 仅因两道 rustfmt 检查失败。Root 已机械回放真实 formatter 输出并将三文件修正整合到 pref9；host124原run/job仍因旧consumer表31/32不匹配失败；独立修复已在 `d1c0c47d` 整合，保留原31批准项并追加明确批准项。44项Python测试通过；原sealed receipt（SHA-256 `391719e99f0bd00632139990dc6d9d8779a984d834c8c938cecd28134000323c`）已在修复后consumer下只读重放并接受，receipt/source/run/attempt未修改，Core 32/179、format/compile/feature/source/lock证明与原receipt匹配。该原run回放不改写host124 job失败，也不是新source验收；新source完整App CI仍待运行。Android、十二宫格与 APK 均未验收。内置分流脚本原样采用 AEROVE Lite / meow 专版，来源和许可统一见[开源来源与鸣谢](../../development/upstreams.md#开源来源与鸣谢)。生产入口为 `LiteApplication` 的 asset loader，经 `OverrideRepository` 一次初始化进入原脚本库，再沿既有配置 Script 模式和 `ScriptLibrarySession` 更新/CAS 链路执行。

标准版对照为 `common/xclash_defaults.dart` 的一次初始化，Lite 没有新增独有菜单。首次及旧安装保留已有脚本、编辑与配置选择；用户删除后不自动回填。接近既有容量上限的合法旧库暂缓加入，仍可读取、编辑和删除；释放空间或原子恢复后重试，不扩大容量上限，也不把暂缓标成初始化成功。

当前内置 Smart-Config-Kit 固定 `c18c314d94206eb6724d235ca0d590c3e14e9121` / `v6.0.21-meow.7`，保留跟随 main 的更新地址；本次重新执行离线 Node `main(config)` 并核对同批官方投影，153 个规则资源及 Geo 等 158 个文件已保存读回，规则资源与 `.6` 输入逐字节相同。固定脚本、README、来源 manifest、完整性常量及 Android fixture 输入一同更新；这些证据不代表真实 Android QuickJS/JNI、Meow compiler 或新 APK 已通过。

专版接线已有最小 SDK36 闭包、16 项原 JVM 及 Node 执行证据；容量修正新增 3 项实际 JVM 回归。原联调样本与内置脚本同字节，仍须分开核对生产接线。完整应用编译、新 APK asset 读回、真实 Android QuickJS 到 Meow 流程及整体首发验收仍待完成，不能以这些局部检查代替。

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
离线校验在固定核心内部的取消粒度见[离线资源校验的取消与等待](#offline-cancel-join)，只是开发记录，不形成 APP 入口差异，也不是核心例外。
官方 MRS 规则集的展开字节、domain-set 输出与 IP 前缀输出另受固定核心上限约束，按核心例外记录，能力标识、源码证据、受影响字段与入口见[官方 MRS 解码上限](#mrs-decode-limits)。

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
- 标准版已选择性整合 FlClash v0.9.2 的路由命中复核：探测结果记录各主机实际出口，路线移动只递增 route-version，
  界面在稳定后请核心不拨号复核，出口未变的结果保留。Lite 仍按 picks 判断新鲜度，须在 `meow-native` 记录命中、
  区分移动与重置并提供私有 `checkRoutes`，再替换 `RouteSnapshot`、`ServiceProbe`、`OutboundProbe` 与
  `DiagnosticsCoordinator` 的 picks 判断，沿用 1.5 秒稳定、300 ms 移动稳定和同目标 5 秒节流。固定核心
  `crates/meow-tunnel/src/tunnel.rs` 已有 `pre_handle_metadata`、`pre_resolve`、`resolve_proxy`，不构成核心例外；
  完成前阻止下一次 Lite 发行。
- 同批整合中，全局文件规则集内容刷新而定义未变时，Lite 改用核心 `PUT /providers/rules/{name}` 原地重载，内联规则集或
  重载失败才整体重新应用；编辑全局规则后重新应用使用它的当前配置须以 JVM 测试核实，缺失即补齐。新建配置排序、拖动
  返回焦点和 Flutter 资源释放在 Lite 不适用：新导入配置本就追加到末尾，返回走系统 BackHandler，编辑器为 sora-editor。

<a name="strict-rule-set-read"></a>

### 规则集严格读取（Lite 有意适配）

这是 AEROVE 在固定 meow-rs 上的 Core03 overlay 行为（随 `fe31b452` 提交），由 Root 决定保留。它不是固定核心差异，
也不是核心例外：固定核心本身接受这类输入，Lite 选择拒绝。

- **固定上游原行为**：固定提交的 `crates/meow-rules/src/mrs_parser.rs`（原始 SHA256
  `1163c106fa992a54bf9def0c494d0043be40a46bb9e8255f70622ee51574331b`）对上游 zstd MRS 宽松处理：第 547 行
  `std::str::from_utf8` 失败的 domain-set 叶子直接跳过；第 488 行把 IP 区间直接交给 `push_range_prefixes`，
  不检查起止顺序，倒置区间经 `while start <= end`（第 768、793 行）不产出前缀。原字节工厂
  `build_rule_set_from_mrs_with_behavior` 保持这一兼容行为。
- **Lite 适配**：带 `MrsReadControl` 的 `UpstreamRuleSetReader` 读取路径对上述输入分别返回
  `MrsError::Utf8("domain_set_leaf", …)` 和 `MrsError::InvalidIpCidrRange { from, to }`。依据是共同契约
  “不兼容配置必须指出字段和原因，禁止静默忽略”。
- **Lite 入口与数据流**：资源校验、规则检查与导出、准备预检，以及规则 provider 的加载与刷新都进入同一读取器。
  file 预取、加载和刷新读取已打开的普通文件描述符，拒绝目录与 FIFO；HTTP 下载字节以借用方式读取；HTTP 磁盘缓存也经描述符读取。
  错误类型 `MrsParseError` 保留 `MrsError`，以 `{:#}` 输出时完整消息只出现一次。严格加载报
  `rule-provider '<名称>' failed to load (strict mode): mrs parse error: mrs: invalid UTF-8 in domain_set_leaf: …`
  或 `… mrs: invalid ip-cidr range: <from> > <to>`，Native 以 `providerConfigRejected` 的 reason 原样交给 App；
  非严格加载按既有 dropped 记录列出同一原因。刷新失败保留上一份已接受规则；HTTP 缓存不再能解析时忽略该缓存并在后台重新获取。
  描述符打开后文件被追加、截断或改写（读取量、长度或修改时间变化）时返回 `providerFileChangedDuringRead`，不安装部分内容。
  初始加载的 provider 租约仍在获取和解析之后开始，获取失败照旧注册空 provider 并重试。
- **标准版对照**：入口同为订阅或配置导入后的规则 provider 加载（Flutter → 官方 Mihomo `3f27ef39`）。
  `component/trie/domain_set_bin.go` 的 `ReadDomainSetBin` 原样读取 label 字节，不做 UTF-8 校验；
  `component/cidr/ipcidr_set_bin.go` 的 `ReadIpCidrSet` 用 `netipx.IPRangeFrom(from, to)` 构造区间，不校验顺序。
  同一规则集在标准版可以加载，非法叶子与倒置区间不匹配任何目标；标准版无需改动。
- **状态与交互**：两版 provider 列表、状态与错误展示面相同，APP 不出现占位或灰置入口。就畸形输入而言，差异只在核心是否接受畸形上游 MRS；
  Lite 指出具体字段与原因，不静默丢弃条目。官方 MRS 的规模另受[官方 MRS 解码上限](#mrs-decode-limits)约束，宿主准入边界见[规则资源准入边界](#rule-preview-budget)。
- **验证边界**：`rule_provider::reader::tests::reader15_` 共 15 项（FIFO 用例仅限 Unix），覆盖文件与 HTTP 字节的类型化错误和实际计数、
  倒置区间、读取期间变更、HTTP 缓存年龄与重新验证、geo 扫描、严格错误文本只出现一次、租约被拒时的获取失败分类；
  原 `rule_provider::tests::` 41 项不变。准入模式 `policy-runtime-provider-reader-v1` 保留原 31 条命令/177 个命中，并新增 1 条命令及 2 个命中，现为 32 条命令、179 个命中；
  host123 的真实 run 已执行该 Core recipe：32/179 均 exit 0、compileCheck=0、featureBoundary=0、锁恢复成功；42 个 Native targets 全部通过。整次 run 仅两道覆盖六源的 rustfmt 门失败，真实 formatter 输出已机械回放并进入 pref9，host124原job仍因旧consumer 31/32清单不匹配而失败；独立修复后consumer已只读接受原sealed receipt，新source完整App CI仍待运行。
  Android 存储上的描述符行为、取消时序、真实设备 RSS 与 APK 验收仍待完成。

<a name="rule-preview-budget"></a>

### 规则集预览、导出与规则资源准入边界

本节记录 `candidate-v3` 的规则读取改动与准入状态。宿主规则输入、只读预览、本地编辑/粘贴/保存均保持 64 MiB 预算。Ruleset YAML、普通配置 YAML、官方 MRS 宿主条目准入及固定核心字节解码上限分别见下列边界与当前实现状态。
- **保留的条目门槛：** 用户决定官方 MRS、文本及 `MRS!` 宿主入口最多 250,000 条，文本单条最多 2,048 字节。当前官方 MRS 三个宿主上下文均已接线同一 250,000 准入并由 host123 实际运行验证；411,634 和 345,000 条旧样本仅证明 Core reader 能读到该上游数据，随后 App 四入口精确拒绝。每条逻辑规则最多 128 个标签、总逻辑标签最多 500,000；普通配置 YAML 为独立 100,000 解析值限制，ruleset YAML 的 250,002 对应 250,000 条 payload 加容器值。
- **保留的解压边界：** 官方 MRS 压缩输入最多 64 MiB；固定核心解压输出最多 256 MiB（`MAX_DECOMPRESSED_BYTES`）；该 Core 上限独立于宿主 250,000 条准入，也不同于历史 `MRS!` 的 64 MiB 展开预算。展开 64–256 MiB 的读取仅在宿主压缩输入及其他准入门槛全部满足时才可接受。不得将解压上限误写成规则编辑/输入预算，也不得删除核心上限。设备内存/RSS 未验。
- **规格变更三：Kotlin 规则资源上限收敛到 Native 准入边界，Native 文本规则输入同步到 64 MiB 文本预算。** 常量集中在
  `apps/aerove-lite/app/src/main/java/dev/aerove/lite/data/RuleResourceText.kt`：`MAX_RULE_INPUT_BYTES`（第 27 行，64 MiB，等于 Native `MAX_INPUT_BYTES`）、
  `MAX_RULE_TEXT_BYTES`（第 29 行，64 MiB，等于 `MAX_DUMP_TEXT_BYTES`）、`MAX_RULE_PAYLOAD_ENTRIES`（第 31 行，250,000，等于 `MAX_ENTRIES`）、
  `MAX_RULE_ENTRY_BYTES`（第 33 行，2,048，等于本候选新增的 Native `MAX_ENTRY_BYTES`）。`meow-native/test_build.py` 的 `RuleResourceBoundTests`
  从两侧源码读出这些值并要求相等。下列路径相对 `apps/aerove-lite/app/src/main/java/dev/aerove/lite/`，“原”行号为 native-v4 树：
  1. `resources/ResourceManagementRuleCache.kt` 原 :268 的 `limit()`：资源页规则库与全局规则库共用的规则缓存，对官方 MRS 与 `MRS!` 限 32 MiB、
     对 text/YAML 限 8 MiB，规范化文本（`Materialized rule payload exceeds limit`）限 8 MiB。现在规则一律 64 MiB（:268，读取校验 :215–226），规范化文本 64 MiB（:130）。
  2. `ui/GlobalResourcePage.kt` 原 :74：全局资源库的文件选择器把整份文件读入内存，32 MiB，报 `Rule resource exceeds 32 MiB`。现在选择器只解析名称，
     规则导入在 `integration/GlobalResourceWorkspace.kt:23` 以 64 MiB 流式交给仓库，第 64 MiB+1 字节报 `Rule resource exceeds 64 MiB`
     （`profilebuilder/GlobalResourceLibrarySession.kt:36`）。图标集导入不是规则资源，保持 32 MiB（:40、:182），本候选把它的报错改为自己的
     `Icon set exceeds 32 MiB`：此前沿用规则文字是误标，`6654a60f` 起就是这样。
  3. `data/ResourceBundleManager.kt` 原 :795 `MAX_RULE_BYTES`（8 MiB）与原 :796 `MAX_MRS_BYTES`（32 MiB）：配置资源的下载、复制与共享库副本。
     现在两个常量删除，规则一律 `MAX_RULE_INPUT_BYTES`（:227）。
  4. `data/GlobalRulePayloadRepository.kt` 原 :149 的 `MAX_BINARY`（32 MiB）与 `MAX_TEXT`（8 MiB）：全局规则库导入的下载与复制（:99–100）、文本输入
     （:103，`Rule payload exceeds text limit`）和预览（:70–77，`Rule preview exceeds text limit`）。现在为 64/64 MiB（:151）。
  5. `resources/ResourceManagementRepository.kt` 资源页：原始文本预览（原 :57）与规则计数（原 :77、:80）各限 8 MiB。现在计数按 64 MiB 输入流式读取（:85–86），
     预览见规格变更四。
  6. Ruleset YAML 使用 `MAX_YAML_RULE_VALUES=250002`，即最多 250,000 条 payload 加根/容器解析值；这是已确认的条目门槛，不是放宽。普通配置 YAML 继续单独遵守 100,000 值限制，不能与 ruleset YAML 计值混为一谈。
  7. Native `apps/aerove-lite/meow-native/src/config_validation.rs` 原 :231：text/YAML 规则输入限 `MAX_TEXT_BYTES` 8 MiB（`rule-provider text input limit exceeded`）。
     现在为 `MAX_RULE_TEXT_BYTES`（:26，即 64 MiB 文本预算；检查在 :239）。经文件入口时，64 MiB+1 字节先由 `read_open_file_until`（:803）报
     `resource input limit exceeded`。`MAX_TEXT_BYTES` 8 MiB 现只用于配置与 proxy-provider 文本（:22）。

  候选 source 曾提出扩大 text/YAML、MRS 输入容量及 YAML 条目数；当前宿主规则输入仍为 64 MiB；官方 MRS/text 条目门槛为 250,000，ruleset YAML 解析值门槛为 250,002（对应 250,000 条 payload 与容器值），普通配置 YAML 独立保持 100,000 值。现有三个官方 MRS 宿主上下文均接线统一准入；host123 验证 411,634 / 345,000 条旧样本仅 Core 可读、App 四入口拒绝，另验证 100,000 条、300,000 个逻辑标签、67,700,000 字节文本先由 Core strict matcher 接受，再由 App 四入口按 64 MiB 拒绝。候选错误文案如下，只描述 source 候选：`Resource exceeds 67108864 bytes`（`data/ResourceDownloader.kt:59`、:90；配置资源外加
  `Resource download/copy failed: ` 前缀）、`Rule resource exceeds 64 MiB`、`Input exceeds 67108864 bytes`、`Rule payload exceeds text limit`、
  `Rule preview exceeds text limit`、`Materialized rule payload exceeds limit`、`Expanded configuration exceeds 250002 values`、`resource input limit exceeded`
  和 `rule-provider text input limit exceeded`。只读规则预览和本地规则编辑均可打开到 64 MiB 文本预算；官方 MRS/text 宿主条目准入 250,000、单条 2,048 字节、每条 128 标签及总逻辑标签 500,000 已有 host123 实际验证；ruleset YAML 250,002（含容器）与普通配置 YAML 100,000 值是独立限制。
- **规格变更四：边界在流式读取中执行（本候选）。** native-v5 把上述上限放宽到 64 MiB 后，仍先物化再检查：64 MiB 的短行文本或 YAML 在 `-Xmx256m` 下
  OutOfMemoryError，原本在 8 MiB 处得到的干净拒绝因此变成崩溃。现在各读取点如下，行号相对上述目录：
  1. **text 规则**（准备 `data/ResourceBundleManager.kt:360–368`、规则缓存 `resources/ResourceManagementRuleCache.kt:219`、仅配置恢复 `data/ResourceBundleManager.kt:725`）：
     `forEachRuleLine`（`RuleResourceText.kt:65`）按字符扫描，切行与修剪等同原 `lineSequence().map(String::trim)`，注释行与空行从不物化，每条交给调用方后
     不再保留。修剪后超过 2,048 个 UTF-8 字节的条目不再物化；读完整个文件、先报完校验问题之后，报 `<字段>[<序号>]: Rule entry exceeds 2048 UTF-8 bytes`，
     例如 `$.rule-providers.big[1]`。编码与大小错误仍先于它。这一条目上限以前只在 Native 预检时触发，报
     `Rule resource preflight failed: rule-provider payload count/line limit exceeded`，现在在 Kotlin 读取时提前报出。
     Kotlin 判定超限时 Native 也一定拒绝，例外是两项既有的切行与修剪差异：Kotlin 也按单独的 CR 切行，Native 只按 LF；Native 还修剪 U+0085。
     条目数不在 Kotlin 侧限制：条目不保留，25 万条仍由 Native 拒绝，文字不变。资源页计数（`resources/ResourceManagementRepository.kt:85`，
     `countRuleLines` 在 `RuleResourceText.kt:68`）只计数，不物化也不设条目上限，结果与原读取相同。
  2. **YAML 规则（ruleset YAML 250,002 个解析值上限；普通配置 YAML 独立为 100,000 值）**（准备 `data/ResourceBundleManager.kt:752–756`、规则缓存 :220、资源页计数 :86、仅配置恢复 :726）：`readRuleYaml`（`RuleResourceText.kt:137`）
     先做一遍只取 SnakeYAML 事件的计数（`admitRuleYaml`，:143），按 `ConfigYaml` 复制时的方式按文档顺序计值，第 250,003 个值处停止并报
     `<路径>: Expanded configuration exceeds 250002 values`（候选错误文案；此门槛对应 ruleset YAML 的 250,000 条 payload 与容器解析值），此时还没有节点图。之后再把整份读完，所以编码与大小错误仍先报。通过计数的输入才交给原 SnakeYAML
     加载器（`ConfigYaml.parseRule`，`data/CompatibilityValidator.kt:34`）：解析选项、构造器、结果与报错文字都和原读取相同。加载器读到的每个事件经同一计数，
     文件在两遍之间被替换也不会建出更多节点。节点的位置标记不再引用读取器的码点窗口（`BoundedYamlStream`，`RuleResourceText.kt:187`）。
     SnakeYAML 在一个记号结束前把它整段放进码点窗口与字符串缓冲，所以单个标量、单行注释或两个值之间的内容超过 1,048,576 个字符时报
     `$: YAML scalar, comment or gap exceeds 1048576 characters at line <行>`；每个注释的 `#` 重新开始计数，大段注释不受影响。这一上限是本候选新增的，经用户决定保留。
     超限输入的报错与原读取有三处不同：原读取在边界之后才遇到的语法、重复键、“Keys must be strings”或递归别名错误不再报出；`<<` 合并键按书写处计数，
     比合并后的计数多，所以接近边界的合并文档会更早被拒绝；超限发生在别名展开中时，路径是别名所在位置。比边界更早的未定义别名、超过 50 个集合别名、
     嵌套超过 50 层和第二个文档仍由原加载器报出原文字。
  3. **预览**：只读规则预览共用 `MAX_RULE_PREVIEW_BYTES`，按用户决定等于 64 MiB 文本预算；本地规则编辑采用同一 64 MiB 会话预算。`readEditorRuleText`（`RuleResourceText.kt:304`）一次读入文件字节并严格解码为一个字符串，字节随即释放；编辑器大小检查改用 `utf8Size`，
     不再整份 `toByteArray`。配置详情的只读预览经资源页同一读取，编辑冲突检测用的快照不再另读字节（`integration/ProfileDetailWorkspace.kt:284–292`）；
     规则可编辑时快照、打开、粘贴/大段插入、批量替换及保存沿同一会话的 64 MiB 预算；配置/代理资源编辑仍默认 4 MiB。超出规则预算时明确报 `Configuration exceeds 67108864 UTF-8 bytes`。全局规则库导出超过 64 MiB 时报
     `Rule preview exceeds 67108864 UTF-8 bytes`。
  4. **仅配置恢复的 geo 判定**（`data/ResourceBundleManager.kt:722–727`）：原先把整份规则文本当一个字符串交给 `GeoRuleScan`，而 GEOIP、GEOSITE、IP-ASN
     三个正则只在文首或 `,`、`(` 之后匹配，不在首行的规则因此漏判，恢复时不绑定对应的 mmdb 或 geosite。现在 text 逐条、YAML 按条目判定，与配置准备一致；
     含这类规则的恢复会多绑定对应数据库。
  5. **Native**：`decode_rule_payload`（`config_validation.rs:242–246`）对 `format: text` 不再先做一遍会被丢弃的 YAML 解析。`text_lines`（:609）在第 250,001 条
     或首个超过 2,048 字节的条目处停止，不再先收集全部条目，报原文字 `rule-provider payload count/line limit exceeded`；这类超限输入的这条错误现在先于分类、
     格式与 behavior 错误。`yaml_payload`（:593）先经 `yaml_value_bound`（:626）：用 serde_yaml 0.9.34 所驱动的同一锁定解析器 unsafe-libyaml 0.2.11 逐个读事件，
     按 Kotlin 的方式计值，在 serde_yaml 建立事件表或 `Value` 之前于第 250,003 个值处停止，报
     `rule-provider YAML exceeds 250002 values at <路径>`（`format: yaml` 时外加 `rule_set_invalid: $.payload.yaml: `）。其他 YAML 错误仍由 serde_yaml 报出原文字；
     未声明格式时 YAML 失败照旧回退为 text。只经 Native 读取的入口（全局规则库导入时的检查）因此会拒绝接近边界、另含两个以上非条目值的 YAML，此前这类文档可以通过。
     `Cargo.toml` 新增 `unsafe-libyaml = "=0.2.11"`；`Cargo.lock` 只在根包依赖表加入这个已锁定的包，未新增或升级任何包；`build-policy.json` 的清单与锁 hash 随之更新，
     `cargo test --locked` 与 CI 的 `git diff --exit-code Cargo.lock` 会核对这正是 cargo 的解析结果。
- **保持不变：官方 MRS 压缩输入 64 MiB。** 与 `6654a60f` 相同，Native 按已打开文件的长度拒绝超过 64 MiB（`MAX_INPUT_BYTES`）的官方 MRS，
  报原文字 `resource input limit exceeded`。读取期间文件增长到超过该值时，由 `MrsReadControl` 的压缩上限报
  `rule_set_invalid: $.payload.mrs: mrs: invalid length for compressed_payload: 67108864`，而 `6654a60f` 在这一情形报 `resource input limit exceeded`。
  边界值相同；这是有意保留的文字差异，只出现在读取期间文件被改写的竞态路径。
- **保持不变：预览与导出文本 64 MiB；`MRS!` 展开 64 MiB。**
- **2026-10-26 用户确认的 Lite 设备保护上限。** 以下上限标准版都没有，固定核心也没有依据，不是核心例外；用户明确要求保留防内存耗尽的上限（单个规则 64 MiB、25 万条等），超大文件提示字段、原因和上限值，编辑预览采用同一上限，并要求边读边检查。这是用户确认限制，不宣称与标准版一致。每项超出时指出字段与原因，
  Native 文字由 Kotlin 前缀为对应的 provider 字段。
  1. 64 MiB 输入与文本预算：Native `MAX_INPUT_BYTES`、`MAX_DUMP_TEXT_BYTES`、`MAX_RULE_TEXT_BYTES`（`config_validation.rs:20`、:24、:26），provider 下载
     `maxBytes` 1..64 MiB（`meow-native/src/resource_fetch.rs:10`、:186），以及 Kotlin 的 `MAX_RULE_INPUT_BYTES` 与 `MAX_RULE_TEXT_BYTES`。报错见本节当前接受的 64 MiB 宿主预算。
  2. 250,000 条：Native `MAX_ENTRIES`（:27），text、`MRS!` 与三个官方 MRS 宿主上下文均执行该准入。host123 实际边界用例通过；旧 411,634 / 345,000 记录是 Core reader 阳性后 App 四入口拒绝。`MAX_YAML_RULE_VALUES` :31 是 ruleset YAML 的 250,002 个解析值门槛，含最多 250,000 条 payload 和容器值；普通配置 YAML 的 100,000 值门槛独立保留。
  3. 单条 2,048 字节：Native `MAX_ENTRY_BYTES`（:29），Kotlin `MAX_RULE_ENTRY_BYTES`，报错见上。
  4. trie 500,000 节点或 128 层（`MAX_TRIE_NODES`、`MAX_TRIE_DEPTH`）：`rule-provider trie exceeds 500000 nodes`、`rule-provider entry <序号> exceeds 128 labels`；
     单条超宽报 `rule-provider entry <序号> exceeds 2048 bytes`，输入超限报 `resource input exceeds 67108864 bytes` 或 `rule-provider text input exceeds 67108864 bytes`，
     导出文本超限报 `rule-set text exceeds 67108864 bytes`。
  5. `MRS!` 展开 64 MiB（`MAX_EXPANDED_BYTES`，:32、:308）：由固定核心 `decompress_payload_bounded` 报 `rule_set_invalid: $.payload.mrs: …`。
  6. YAML 单个记号、单行注释或两个值之间 1,048,576 个字符（Kotlin `MAX_RULE_YAML_TOKEN_CHARS`，`RuleResourceText.kt:36`）：本候选新增，报错见规格变更四。
  7. 编辑器：只读规则预览及本地规则编辑统一为 64 MiB（`MAX_RULE_PREVIEW_BYTES`）。编辑会话经 `ProfileEditorSession.open`（`data/ProfileEditorSession.kt:46`）与保存（:79）的
     实例 `checkEditorSize`；`GlobalResourceLibraryScreen.ruleEditor` 与 `ProfileDetailWorkspace.open` 的本地规则均传同一预算，再由 `EditorScreen` 传给 `EditorDocumentController` 的插入、粘贴及 `EditorSearch.replaceAll`。普通配置仍默认 4 MiB，撤销历史的独立 16 MiB 保留预算不扩大。标准版的 `EditorPage`（`apps/aerove/lib/pages/editor.dart` 的 `openEditorFile`）没有此上限。
  同域另有以下 Lite 独有上限，不在本次决定的规则项内，交各自负责人收敛或请用户确认，在此之前同样不是已接受的差异：配置资源包合计 256 MiB、最多 256 项
  （`data/ResourceBundleManager.kt:267`、:403、:812–813，`Resource bundle limit exceeded`）；侧载与本地导入先按 64 MiB 复制；备份单项 64 MiB、归档 256 MiB、展开 512 MiB
  （`data/BackupRepository.kt:35–37`），单配置本地资源最多 128 项、合计 256 MiB（:132、:253、:318）；配置归档 zip 128 MiB、展开 256 MiB、单项 64 MiB
  （`data/ProfileArchive.kt:15–17`，导入含本地规则文件的配置时经 `data/ResourceBundleManager.kt:590` 使用）；图标集 32 MiB（见上）。代理 provider 的 4 MiB
  （`MAX_PROFILE_BYTES`）与资源页代理计数的 8 MiB 读取不属规则资源，未改。
- **Lite 入口与数据流**：宿主规则输入、预览、编辑/粘贴/保存保持 64 MiB 预算，并在三个官方 MRS 上下文执行 250,000 条准入、单条文本长度及标签上限；host123 实际验证边界和超限拒绝。固定核心的 256 MiB 解压上限是独立字节边界，不放宽宿主准入。资源页预览（`ResourcePageWorkspace.preview` → `ResourceManagementRepository.preview`）、配置详情的 provider 预览与编辑
  （`ProfileDetailWorkspace.open` → `ProfileRepository.resourceEditSnapshot`）、全局规则库预览（`GlobalResourceLibraryScreen.ruleEditor` →
  `GlobalRulePayloadRepository.preview`）和规则缓存规范化中的官方 MRS 都经 `NativeRulePayloadInspector.dump` → `JniResourceFetch.offlineRead` → JNI
  `dumpRuleSet(handle, …)` → `dump_rule_set_controlled`；text/YAML 原文经 `readEditorRuleText`。资源校验、规则检查和准备预检分别经 `validateResource`、
  `inspectRuleSet`、`preflightRuleResource` 进入同一 `rule_file`、`read_rule_payload` 与 `read_upstream_file`。Kotlin 侧的规则下载、复制、选择器导入、
  缓存校验、配置准备与恢复、资源页计数都使用 `RuleResourceText.kt` 的常量与读取函数，逐行校验经 `CompatibilityValidator.ruleLineCheck`（`data/CompatibilityValidator.kt:444`）。
- **标准版对照**：设置与代理页的 provider 预览（`apps/aerove/lib/views/config/providers.dart` 的 `_handlePreview`，:227–243；
  `apps/aerove/lib/views/proxies/providers.dart` 的 `_handlePreviewProvider`）→ `core.dumpRuleSet(path)` → `apps/aerove/core/provider.go` 的 `handleDumpRuleSet`：
  `os.ReadFile` 读取整文件后经 Mihomo `ConvertToMrs` 输出文本，不设条目或文本上限；text/YAML provider 由 `openEditorFile` 直接打开。规则 provider 加载经官方
  Mihomo 的 MRS 读取不设展开或条目上限，见[官方 MRS 解码上限](#mrs-decode-limits)；Lite 宿主在三个上下文执行用户决定的 250,000 条限制，host123 已运行相应边界用例；规则资源的下载与导入也不设大小上限。规则文件导入
  （同文件 `_handleImportFromFile`，:122–135）在加载状态之外 `file.readBytes()`，读取失败的异常没有交给页面错误展示。
- **状态与交互**：宿主输入/只读预览/编辑器预算保持 64 MiB；用户决定的 250,000 条准入、单条文本长度和标签门槛均已接线，host123 实际验证；两版预览都是只读编辑器页，失败时显示错误文字，没有占位或灰置入口；只读规则预览可打开到 64 MiB；本地规则在同一 64 MiB 会话预算内均可预览与编辑，超出预算报明上限。Lite 另在以下情况指出字段与原因后
  拒绝：上一条列出的各项上限，以及命中固定核心解码上限。全局资源库选定文件后在 `session.perform` 内读取：读取期间显示忙碌状态，超限或无法打开时错误显示在库页错误行；
  标准版的规则文件导入没有对应的读取中状态与错误展示。这是标准版的缺口，不能按“两版一致”把 Lite 改回无提示；标准版需由其负责人同步，本候选没有标准版代码改动。
- **内存与设备验收**：64 MiB 只读预览需要一份文件字节（读后释放）和一个字符串，非 Latin-1 文本按 UTF-16 存放最多约 128 MB，另加编辑器自身的文档结构，
  须以设备帧率与 RSS 验收后才能发行。JVM 证据（HotSpot 17，见下方验证）显示，在每个规则读取边界上下的最坏输入都能在 256 MiB 堆内完成或干净拒绝。Android ART 的字符串与
  `StringBuilder` 布局不同，只能以设备验证。仍有的峰值来源：边界内 YAML 规则的节点图与条目列表，ruleset YAML 最多 250,000 条 payload（250,002 个解析值）、普通配置 YAML 最多 100,000 值或文本/MRS 项的设备保护准入最多 250,000 条，均受各自 64 MiB 预算；标签上限为每条 128、总计 500,000。官方 MRS 导出另有原生字符串与 JNI 转出的
  Java 字符串各一份；Native 对边界内 YAML 仍由 serde_yaml 两次加载（固定核心 `parse_raw_yaml` 一次、条目提取一次），每次持有整份事件表与 `Value`；固定核心允许的
  256 MiB 展开。待设备验收：64 MiB text/YAML 与官方 MRS 的导入、刷新、启动、资源页计数与预览、规则库同步，配置详情打开 64 MiB provider，以及 256 MiB 展开校验的
  RSS 与 OOM，均在默认 heapgrowthlimit 下进行。
- **验证：** host123（run 40320087571，source `bd83a3f5d9ad44be8ac3c87460279133af34ca17`）实际运行 Native 全部 42 个 targets；Core 准入 32 条命令、179 个命中全部 exit 0，compileCheck=0、featureBoundary=0、锁恢复成功。官方 MRS 250,000 边界、实际计数与声明头检查、单条 2,048 字节、每条 128 标签、总标签 500,000 均经真实 Core/App 路径验证；411,634/345,000 旧样本为 Core reader 可读但 App 四入口精确拒绝。独立 100,000 条、每条约 676 字节、300,000 个标签、文本 67,700,000 字节夹具先通过 Core strict matcher，再由 App 四入口按 64 MiB 拒绝。
  `official_mrs_text_over_budget_is_an_error_but_binary_inspection_still_succeeds`、`official_mrs_above_64m_expanded_reaches_the_actual_strict_matcher`、
  `upstream_mrs_preflight_rejects_identity_corruption_and_256m_expansion_without_relaxing_other_caps`、
  `official_mrs_compressed_input_between_32m_and_64m_is_admitted_and_the_64m_input_bound_holds` 与 `text_rule_input_up_to_the_64m_text_budget_is_admitted_and_the_input_bound_holds`；
  单元测试 `text_rule_entries_stop_at_either_entry_bound_before_they_are_collected` 与 `yaml_event_pass_admits_the_value_bound_and_stops_one_value_past_it` 覆盖本候选的两侧边界、
  单元测试 `text_rule_entries_stop_at_either_entry_bound_before_they_are_collected` 与 `yaml_event_pass_admits_the_value_bound_and_stops_one_value_past_it` 覆盖本候选的两侧边界、别名展开和交给 serde_yaml 的错误。host123 上述 Core 与 Native 用例实际通过；全 run 失败仅为六个 Rust 源文件的两道 rustfmt 检查，Root 已将 formatter 原始输出机械回放至三文件 overlay，见 `pref9fb2580833bca00f0b68cfddcdf786404516112c`。
  `test_build.py` 另要求 `build.py` 与 `verify_apk.py` 两张表逐项相等（改名一项即被拒绝），并要求 Kotlin 与 Native 常量相等；host124原job仍因旧consumer 31/32清单不匹配而失败；独立修复后consumer已只读接受原sealed receipt，新source完整App CI仍待运行。Kotlin `RuleResourceBoundsTest` 经生产路径覆盖每个读取点的两侧：
  配置准备的 text、YAML 与条目上限，规则缓存，资源页计数与预览，全局规则库导入、预览与规范化，仅配置恢复，geo 判定与校验先于条目上限；流式读行与原整份读取在
  300 个随机样本上逐行一致，1,500 份随机 YAML 的结果和报错与原整份加载一致，令牌与间隔上限、编码优先级也各有两侧用例。`RuleResourceHeapTest` 在 `-Xmx256m`
  子进程中用生产函数读取最坏输入：1,677 万条短行、25 万条 64 MiB YAML（含 UTF-16 字符串）、超限 YAML、64 MiB 单行与注释、64 MiB 预览，64 MiB 行尾空白另在
  `-Xmx48m` 下完成；`ProfileDetailWorkspaceTest`
  原有测试覆盖配置详情在 4 MiB 两侧的打开；本轮增加本地规则 5 MiB 打开及会话 64 MiB 两侧的打开/保存、插入/替换预算回归。旧 native-v6 的读取点变异证据继续保留；host123 已实际运行 Native/Core 回归，host124原job仍因旧consumer 31/32清单不匹配而失败；独立修复后consumer已只读接受原sealed receipt，新source完整App CI仍待运行。

<a name="mrs-decode-limits"></a>

本轮原生读取补充：规则 dump、检查/计数/校验与准备预检共用 `read_rule_input`。text 在字节块解码时计行，YAML 用 unsafe-libyaml 0.2.11 的 `yaml_parser_set_input` 回调驱动同一文件流，并在事件到达时执行既有值预算；64 MiB 字节预算在每次读取时生效。有效输入只捕获这一遍的相同字节，再交既有严格解码与规则构造，不 rewind、不重新打开路径。拒绝后使用小块 drain 继续判定编码、大小和取消错误，并停止保留无用尾部；并非先读完整份再检查条目或 YAML 值。解析器、文件与同步扫描仍在现有 OfflineResourceOwner 的同一个实际 worker 中，返回前由原 owner join；未增加独立任务或 FD。host123 Native 全部 42 targets 通过、Core 32/179 全部成功，整体仅两道 rustfmt 检查失败；Root 已机械回放真实 formatter 输出并整合到 pref9，host124原job仍因旧consumer 31/32清单不匹配而失败；独立修复后consumer已只读接受原sealed receipt，新source完整App CI仍待运行。Android ART RSS 尚无验收结论，不能将源码或主机测试称为设备验收。

本轮复核增量：unsafe-libyaml 回调在构造 `&mut [u8]` 前先初始化上游 malloc 缓冲区。YAML 预算帧不再复制完整父路径，只保存索引与有界诊断组件，超限时才生成一次路径；超长键以精确源码字节偏移和原键字节数定位，诊断路径限制在约 4 KiB，输入和实际解析键不截断。reader 与直接字节入口均在事件处理前执行固定核心的相同轻量深度准入：未闭合 flow `[`/`{` 为 128 层（含引号内字符），行首空格最多 256；不增加一个会误拒核心合法 block 输入的 event-depth128 规则。边界 128/129、256/257、长键诊断和原回调初始化均已由 host123 Core 准入用例实际运行通过；内存放大结论是源码推导，尚无 RSS 实测。

### 官方 MRS 解码上限（固定核心字节边界）

- **能力标识**：`rule-set.mrs.upstream-decode-bounds`，即官方（Mihomo 格式、zstd 压缩）MRS 规则集的展开字节、domain-set 输出和 IP 前缀输出规模。
- **固定核心与源码证据**：meow-rs `727ddcd06f592e2f12c5222c4de6cce328df808c` 的
  [`crates/meow-rules/src/mrs_parser.rs`](https://github.com/meow-rs/meow-rs/blob/727ddcd06f592e2f12c5222c4de6cce328df808c/crates/meow-rules/src/mrs_parser.rs)，原始 SHA256 `1163c106fa992a54bf9def0c494d0043be40a46bb9e8255f70622ee51574331b`
  （即 `apps/aerove-lite/meow-native/source-metadata/full-runtime.json` 中该文件的 `beforeSha256`）：
  1. [第 131–141 行](https://github.com/meow-rs/meow-rs/blob/727ddcd06f592e2f12c5222c4de6cce328df808c/crates/meow-rules/src/mrs_parser.rs#L131-L141) `MAX_DECOMPRESSED_BYTES = 256 * 1024 * 1024`：`decompress_payload` 和流式 `FrameReader::fill`
     （[第 168–176 行](https://github.com/meow-rs/meow-rs/blob/727ddcd06f592e2f12c5222c4de6cce328df808c/crates/meow-rules/src/mrs_parser.rs#L168-L176)）超过即返回 `InvalidLength("decompressed_payload", 268435456)`。
  2. [第 587–598 行](https://github.com/meow-rs/meow-rs/blob/727ddcd06f592e2f12c5222c4de6cce328df808c/crates/meow-rules/src/mrs_parser.rs#L587-L598) `MAX_DOMAIN_SET_ENTRIES = 4 * 1024 * 1024`（4,194,304 条）、`MAX_DOMAIN_SET_BYTES = 256 * 1024 * 1024`、
     `MAX_DOMAIN_SET_DEPTH = 4 * 1024`：`traverse`（第 601–617 行）把它们交给 `traverse_bounded`；条目数或累计域名字节超限时第 668–672 行返回
     `domain_set_output`，深度超限时第 677–682 行返回 `domain_set_depth`。
  3. [第 469–499 行](https://github.com/meow-rs/meow-rs/blob/727ddcd06f592e2f12c5222c4de6cce328df808c/crates/meow-rules/src/mrs_parser.rs#L469-L499) `for_each_net`：IP 区间拆出的前缀累计超过 `MAX_DOMAIN_SET_ENTRIES` 时，第 494–496 行返回 `ipcidr_set_output`。

  第 131–135 行和第 587–591 行的注释说明，这些上限用于阻止恶意文件把有界输入放大到耗尽内存（上游 issue #513）。同文件自带测试
  `decompress_payload_rejects_output_past_the_bound`（第 1226 行）、`domain_set_traversal_is_iterative_and_bounded`（第 1246 行）、
  `domain_set_output_is_bounded`（第 1290 行）和 `domain_set_bounds_error_instead_of_amplifying`（第 1331 行）验证上述行为。
- **Lite overlay**：Core03（`apps/aerove-lite/meow-native/patches/full-runtime.patch`，该文件 after SHA256
  `c6e1c5d5e68e3d0fb1a4e635aa51bbc4e88ff6c19b7e25decaecb77e1ef97c54`）的 `MrsReadControl::new` 把调用方给出的压缩与展开上限都截到
  `MAX_DECOMPRESSED_BYTES`；受控读取 `DomainSetTraversal::traverse_controlled`（经 `traverse_bounded_controlled`）与 `UpstreamRuleSetReader::for_each_net`
  Native 不能通过参数放宽这些上限。Core 二进制表保持借用；两个采样索引合计最多 1,000,001 个 `usize`。这是解析器分配边界，不证明完整已打开描述符逐条流式处理，也不证明 RSS 或 Android 设备内存行为。
  Native 无法经参数放宽这些上限。
- **为什么保留而不调高**：overlay 已改写同一文件的受控读取（`MrsReadControl`、`traverse_bounded_controlled`），技术上可以改大这些常量。
  但它们是上游为阻止有界输入被放大到耗尽内存而设的安全边界（第 131–135、587–591 行注释，issue #513），改大等于在同一进程内移除这道防护。
  依 2026-10-25 Root 决定 (b)，这些固定核心源码可证的上限登记为核心例外并保留。这与[离线校验的取消粒度](#offline-cancel-join)不同：
  那里能在 overlay 实现的能力不能记作缺接口；这里保留的是固定核心的安全上限本身，不是缺少实现。
- **受影响字段与行为**：配置 `rule-providers.<名称>`（`format: mrs`，`behavior: domain` 或 `ipcidr`）以及全局规则库中的官方 MRS。超过任一上限时，
  资源校验、规则检查、导出与准备预检报 `rule_set_invalid: $.payload.mrs: mrs: invalid length for <字段>: <值>`，字段为 `decompressed_payload`、
  `domain_set_output`、`domain_set_depth` 或 `ipcidr_set_output`；运行时 provider 加载按[规则集严格读取](#strict-rule-set-read)的 `mrs parse error`
  文字拒绝，非严格加载列入既有 dropped 记录。不截断，也不静默丢条。
- **受影响入口**：导入、刷新与启动的资源校验和完整转换；资源页与配置详情预览；全局规则库导入与预览；规则 provider 的文件、HTTP 下载和 HTTP 缓存加载。
- **标准版对照**：官方 Mihomo `3f27ef39` 的 [`rules/provider/mrs_reader.go`](https://github.com/MetaCubeX/mihomo/blob/3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8/rules/provider/mrs_reader.go) 中 `rulesMrsParse` 以默认参数的
  `zstd.NewReader` 流式解压；[`component/trie/domain_set_bin.go`](https://github.com/MetaCubeX/mihomo/blob/3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8/component/trie/domain_set_bin.go) 的 `ReadDomainSetBin` 与
  [`component/cidr/ipcidr_set_bin.go`](https://github.com/MetaCubeX/mihomo/blob/3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8/component/cidr/ipcidr_set_bin.go) 的 `ReadIpCidrSet` 按文件头长度读入 succinct 结构，不逐条展开域名，
  也不设条目数、输出字节或深度上限。同一规则集在标准版可以加载；标准版无需改动。
- **状态与交互**：APP 不出现占位或灰置入口；超限规则集按上文指出字段与核心原因后拒绝。
- **验证**：Native 集成测试 `upstream_mrs_preflight_rejects_identity_corruption_and_256m_expansion_without_relaxing_other_caps` 覆盖展开 256 MiB+1 字节
  时检查、校验与准备预检都报 `decompressed_payload`。4,194,304 条、256 MiB 域名字节和深度上限以固定源码自带测试为证据，宿主未另造 4 Mi 条以上的夹具。
  host123 Core compileCheck=0、featureBoundary=0，Native 42 targets 通过；本段所列 Core 用例均由 32 命令/179 命中实际覆盖。整次 run 仅因两道 rustfmt 门失败，host124原job仍因旧consumer 31/32清单不匹配而失败；独立修复后consumer已只读接受原sealed receipt，新source完整App CI仍待运行。

<a name="offline-cancel-join"></a>

### 离线资源校验的取消与等待

- **Lite 入口与数据流**：导入、刷新与启动的完整转换走 `CompatibilityValidator` → `NativePreparedConfigValidator`，生产实现是
  `JniResourceFetch.preparedValidator` → `preparedResourceValidator`；配置、资源与规则检查、导出和准备预检走 `JniResourceFetch.offlineRead`。
  两者都先 `nativeCreate` 离线 owner，在 IO 线程执行 JNI 调用。协程取消时调用 `nativeCancel`，等实际工作线程退出后在 NonCancellable 下
  `nativeCloseJoin`，之后才删除校验缓存或释放文件租约。原生 `OfflineResourceOwner` 把转换作用域登记为子作用域：取消同时到达子作用域和读取令牌，
  `close_and_join` 先等工作线程、再逐个等子作用域，并发关闭由关闭闸门串行。
- **工作中的检查点**：geo 文件读取以及 gzip、zstd 展开每 64 KiB 检查一次取消。zstd 由宿主用与固定核心 `decompress_payload_bounded`
  相同的解码器、上限和错误文字分块展开；读取遇 `EINTR` 时重试。proxy-provider 转换把令牌转给固定转换器的作用域，返回或重抛前等待已开始的作业。
  传输 DNS 的 geo 校验使用资源句柄的令牌。
- **标准版对照**：导入、更新与启动经 Dart `await` 调用 Go 核心的 `validateConfig` 等方法（`apps/aerove/lib/core/controller.dart`，
  `apps/aerove/core/config.go` 的 `handleValidateConfig`）。Go 调用不能从 Dart 中途取消，临时文件在调用返回后才删除（`validateConfigWithData`）。
  两版都不会在读取结束前删除被读取的文件；Lite 额外让取消在步骤之间生效并等待退出，这是原生 owner 的生命周期要求，不改变共同界面，标准版无需改动。
- **状态与交互**：两版的导入、刷新、启动入口、进度和错误展示相同，取消后都不保存半成品配置或索引；Lite 没有新增按钮或状态。
- **验证**：Rust 库测试 `prepared_validation_forwards_the_owner_token_into_geodata_reading` 与
  `transport_load_forwards_the_fetch_token_into_dns_geodata_reading` 经公开入口 `OfflineResourceOwner::validate_prepared`、`ResourceFetch::load`，
  令牌被替换即失败；另有 `a_concurrent_close_cannot_report_joined_before_the_first_closer_joins_its_children`、zstd 分块取消、`EINTR` 重试，
  以及集成测试 `zstd_geodata_streaming_matches_the_fixed_core_bounded_decoder`。Kotlin `PreparedResourceValidatorTest` 与
  `MeowProviderPreparationTest` 直接执行生产 `preparedResourceValidator`。JNI 描述符由 `build.py` 调用 `meow-native/jni_abi.py`，
  逐项对照 Rust 导出、Kotlin 声明和 `verify_apk.py`。host123 compileCheck 与 Native targets 通过，Core 32/179 实际执行成功；整次 run 的两道失败均为 rustfmt。host124原job仍因旧consumer 31/32清单不匹配而失败；独立修复后consumer已只读接受原sealed receipt，新source完整App CI仍待运行；Android 取消时序仍待设备验收。
- **固定核心内的取消粒度（只进开发记录，不是核心例外）**：以下调用在固定 meow-rs `727ddcd` 源码中没有可中断点，Native 在调用前后检查取消并等待其完成。
  两版取消能力相同：标准版的 Go 调用同样不能中途中断，取消后两版都不保存半成品，因此这不构成 APP 能力差异。
  1. 转换器阻塞阶段：原始 [`crates/meow-config/src/lib.rs` 第 2718–2851 行](https://github.com/meow-rs/meow-rs/blob/727ddcd06f592e2f12c5222c4de6cce328df808c/crates/meow-config/src/lib.rs#L2718-L2851)
     的 `build_parser_context_with_geo_async`、`prefetch_rule_provider_payloads_async`、`rebuild_from_raw_impl_async` 和
     `load_rule_providers_async` 各把一个同步闭包交给 `spawn_blocking_with_current_dispatcher`，闭包没有取消参数；overlay 的
     `validate_offline_config_with_scope` 与 `validate_prepared_config_with_scope` 只在作业之间观察作用域取消。
  2. geosite 解析：原始 [`crates/meow-rules/src/geosite_dat.rs` 第 166–212 行](https://github.com/meow-rs/meow-rs/blob/727ddcd06f592e2f12c5222c4de6cce328df808c/crates/meow-rules/src/geosite_dat.rs#L166-L212)
     的 `from_dat_bytes` 一次同步遍历整个 protobuf，没有控制参数。overlay 的 `from_dat_bytes_checked` 经 `parse_dat_bytes` 复用同一遍历；
     在其条目循环加入取消检查可以在 Core overlay 中实现，属于 Core overlay（`patches/full-runtime.patch` 与 `source-metadata`，由 core-provider 候选单一写入）
     的后续项，不以“缺少接口”记作核心例外。实现之前与 `6654a60f` 相同：取消在解析前后生效，并等待解析结束。
- **不作为例外**：zstd geo 展开已由宿主分块实现，不再记录。MMDB 结构校验 `maxminddb::Reader::verify`（固定核心以 `offline_maxminddb`
  重导出，锁定 0.27.3）没有调用方钩子，但它属于依赖库，不是固定核心源码，按契约不列为例外；Native 在调用前检查并等待其完成，耗时受 64 MiB 输入
  与搜索树节点上限约束。是否在宿主分块重做该校验作为后续项，不能以降低校验强度换取更细的取消粒度。

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
