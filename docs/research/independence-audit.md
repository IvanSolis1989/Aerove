> 历史审计：下文问题定位于固定基线128e3227，后续治理修改见本目录主架构文档；不能把旧行为描述当作当前实现。

# Aerove 独立维护耦合审计

审计基线：`128e3227db353c626d5de9750c2b09bb69dca5c8`，分支 `codex/aerove-independent-architecture`，目录 `C:/Users/dedew/.codex/worktrees/aerove-independent/FLClash`。旧 preview worktree 完全冻结。所有源码行号均对应上述基线；主线程并行修改后，工作区链接可能已变动，请用 `git show 128e3227:<path>` 对照。本报告只审计现有产品/开发治理和 Core 边界，不评估 clash-rs 源码或 Android 原生 UI，也不预设去留 Flutter。

## 1. 结论

1. 自主产品的主要阻断是“发布政策的上游绑定”，不是显示名称：当前 Android 显示版本、通道和发布 tag 全由 FlClash metadata 推导；Aerove 自己改 pubspec 会失败，新上游 stable 也能阻断原基线修复版。
2. 血缘证据必须保留；应移除的是“必须跟随最高 tag”和“当前产品 pubspec 必须等于历史上游 pubspec”的规则。原始源码 SHA、来源 commit/tag、历史 pubspec、许可、构建 run、签名/包名、hash 和 tree 均不是应删除的 fork 包袱。
3. 自动同步现在可直接推进 main 并触发发布。独立开发应改为非发布的候选/人工集成入口，保留其冲突、父提交与基线竞态校验；只改 CI 名称不会改变这条产品决策链。
4. `CoreInterface` 不是原始字节 transport：它是包含生命周期和 Mihomo 业务模型的 Core façade。`CoreHandlerInterface` 再将其转换为 RPC。它已隔离 Android/desktop 传输，但还不是 backend-neutral。
5. 产品治理、版本与来源解耦、开发验收矩阵现在即可先做；后端替换和前端技术选择不必成为这些工作的前置条件。

## 2. 版本、来源、打包、发布的完整调用链

### 2.1 上游自动驱动链

`sync-upstream.yaml` 小时调度 → fetch 官方 branches/tags → `xclash_version select-tag` 选可达最高合法 tag → 读取该 source 的 pubspec → make-metadata → sync-decision → 普通双父 merge 或 metadata-only commit → Flutter/Go/配置验证 → Git bundle → publish job 检查 BASE/CANDIDATE/父提交与远端 main 未变化 → push main → 如包含 APK 输入则 workflow_dispatch Android（build=true、publish=true）。不是“仅创建 PR”。

证据：[.github/workflows/sync-upstream.yaml:3](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/.github/workflows/sync-upstream.yaml#L3)、[.github/workflows/sync-upstream.yaml:95](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/.github/workflows/sync-upstream.yaml#L95)、[.github/workflows/sync-upstream.yaml:259](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/.github/workflows/sync-upstream.yaml#L259)、[.github/workflows/sync-upstream.yaml:317](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/.github/workflows/sync-upstream.yaml#L317)。README 冲突是唯一自动保留 fork 内容的例外；其他冲突停止。这个 fail-closed 机制值得保留，但候选通过不应继续等于自动接受上游成为产品路线。

### 2.2 Android 新构建链

`xclash-android verify` 先检查 README 自有链接 → `apk` 下载验证生成物并确认 staged tree → 再 fetch 官方 refs → `xclash_version release-plan(metadata,current pubspec,head,run)` → 输出 name/number/tag/app_env/upstream_* → `flutter build apk --build-name --build-number --dart-define APP_ENV` → Gradle 读取 flutter.versionName/versionCode → aapt/apksigner → BUILD_INFO.json + SHA256SUMS → 固定 artifact ID。

证据：[.github/workflows/xclash-android.yaml:82](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/.github/workflows/xclash-android.yaml#L82)、[.github/workflows/xclash-android.yaml:421](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/.github/workflows/xclash-android.yaml#L421)、[.github/workflows/xclash-android.yaml:473](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/.github/workflows/xclash-android.yaml#L473)、[android/app/build.gradle.kts:35](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/android/app/build.gradle.kts#L35)。

当前精确绑定：

| 位置 | 当前契约/后果 | 独立维护处理 |
| --- | --- | --- |
| [tool/upstream-version.json:2](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/tool/upstream-version.json#L2) | FlClash v0.9.1，source/tag commit `8d4f974...`，历史 pubspec `0.9.1+2026101702` | 保留为来源记录，不当产品版本权威 |
| [tool/xclash_version.py:190](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/tool/xclash_version.py#L190) | schema/repository/SHA/tag/source/pubspec identity 校验 | 保留并绑定提交内证据 |
| [tool/xclash_version.py:325](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/tool/xclash_version.py#L325) | release-plan 必须匹配当前 fetch 到的最高可达官方 tag；stable metadata 只在 stable 范围取最高，preview metadata 在全部通道取最高 | 从产品发布门禁移除；可保留在显式上游候选发现工具 |
| [tool/xclash_version.py:335](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/tool/xclash_version.py#L335) | 历史 source pubspec 必须符合 metadata；当前 pubspec 也被强制相等 | 前半保留，后半移除 |
| [tool/xclash_version.py:349](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/tool/xclash_version.py#L349) | name=`upstream version-X`，APP_ENV 由上游 prerelease 推断，code=`2026101700+run_number` | name/channel 改为 Aerove pubspec；code 保留兼容 |
| [tool/aerove_artifact.py:290](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/tool/aerove_artifact.py#L290) | artifact validator 再次按上游 metadata 重算版本和通道，严格比对 BUILD_INFO | 需同时迁移到产品版本 resolver，否则只换构建步骤仍不能推广新包 |
| [tool/aerove_release_notes.py:25](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/tool/aerove_release_notes.py#L25) | release notes 仅接受带 `-X+数字` 的 tag | 放开为受校验的自主产品 tag，保留与 BUILD_INFO/source 一致、变更记录真实且不复用旧待发布内容的约束 |
| [lib/common/package.dart:15](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/lib/common/package.dart#L15) | 升级比较器兼容 `-X`，按 SemVer core/prerelease 后再比较 build | 保留旧 -X 读取兼容；不能无迁移把产品版本重置低于已安装 0.9.1 系列 |

只读内存反例已执行（没有 fetch、没有项目写入）：原公式 run 149 产生 `v0.9.1-X+2026101849`；将当前 pubspec 换为 `1.0.0+2026102201` 被旧 release-plan 拒绝；模拟更高 stable 被最高-tag门禁拒绝；`v1.0.0+2026101849` 被 notes tag 正则拒绝。模拟更高 tag 是政策反例，不是对网络上当前 tag 的观测。

### 2.3 同 APK 推广链与必须保留的门禁

`reuse` → `aerove_artifact.find_artifacts`：同 repository/head_repository、精确 source SHA、同 workflow、push/manual 事件、成功 run、当前 attempt 的 verify/apk 成功、两个唯一未过期 artifact → 返回原 run_id/run_number 和两个 immutable IDs → release 下载这些 ID → checkout 原 source → validate 零字节生成 patch + 完全相同 tree + 全文件集合 + 双文件 SHA256 + BUILD_INFO 原 run/source/版本/通道/来源 + ZIP CRC/重复项/ARM64 主库 → apksigner/aapt → 两次检查当前 main 仍包含构建 SHA 且之后仅文档变化 → 草稿上传 →公开，已公开同 tag 资产不覆盖。

证据：[tool/aerove_artifact.py:159](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/tool/aerove_artifact.py#L159)、[tool/aerove_artifact.py:281](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/tool/aerove_artifact.py#L281)、[.github/workflows/xclash-android.yaml:538](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/.github/workflows/xclash-android.yaml#L538)、[.github/workflows/xclash-android.yaml:578](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/.github/workflows/xclash-android.yaml#L578)。这套可信链应保留，不能为“独立”改成按文件名随意下载或放宽 source/tree/hash。推广继续使用原 run 的 code 和 BUILD_INFO，不按推广 run 重新编号，不重新编译，不改写已有历史包。

当前 `BUILD_INFO.upstream` 是单一 FlClash 结构。最小过渡可继续保留其所有字段，再另加产品 schema/构建信息；以后扩展为有版本的 components/materials 列表，分别记录 FlClash 历史基线、Mihomo 子模块及本地 patch。旧 schema/旧 validator 保留供历史包验收。

### 2.4 Android 与客户端读值

当前 `pubspec.yaml:4` 是 `0.9.1+2026101702`，release CLI 参数覆盖为上游 `0.9.1-X` 与 CI code。因此 pubspec 不等于已安装显示版本。Gradle 自身没有再算上游版本。`Bootstrap` 通过 PackageInfo 获取实际安装值；`Request.checkForUpdate` 拼合 version+build，向 Aerove repository 的 latest Release 比较。证据：[android/app/build.gradle.kts:35](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/android/app/build.gradle.kts#L35)、[lib/bootstrap.dart:106](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/lib/bootstrap.dart#L106)、[lib/common/request.dart:90](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/lib/common/request.dart#L90)、[lib/common/request.dart:142](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/lib/common/request.dart#L142)。

产品版本、build code、更新通道和安装身份应分别定义：保留现有 code 公式以延续 1849 升级单调性；不自动加 -X；显式 SemVer prerelease 映射 pre，其余 stable。不能把换包名/签名作为这一阶段的附带操作：当前固定签名与 `com.follow.clash.dev` 的升级连续性必须保持，安装身份改变另做迁移决策。现客户端只读 latest stable，不等于已有独立 preview 更新订阅器。

## 3. 其他开发治理耦合

- [tool/sync_fork_readme.py:21](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/tool/sync_fork_readme.py#L21) 的 README helper 是链接归属/许可保护，不负责拉取上游 README 内容；保留稳定 APK 链接检查和 GPL 链接约束，去掉“必须是 fork”的产品命名前提，文档保留真实历史基础与署名。README 中 hourly sync、显示版本追加 -X 的承诺也必须同步更新，而非删除来源说明。
- [.github/workflows/build.yaml:325](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/.github/workflows/build.yaml#L325) 的继承 build.yaml 在所有分支提供多语言/平台检查，但正式多平台 build job 限定 `chen08209/FlClash`，因此其 Telegram/Homebrew 发布链在 Aerove 并非第二条可用发布链。不可只替换仓库名就激活那些上游外部发布动作。应提取可复用检查，再由 Aerove 自己声明支持的平台和发布入口。
- [setup.dart:181](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/setup.dart#L181) 每次 setup 会激活固定 tag 的 `chen08209/flutter_distributor`；[core/go.mod:5](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/core/go.mod#L5) 和 [.gitmodules:1](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/.gitmodules#L1) 将 Mihomo 解析到作者分支子模块。这是依赖治理/可重现构建耦合，先做依赖清单、固定 SHA/patch 原因和升级验收，不必马上复制所有库或更换引擎。
- setup 的 native hook 禁用保护、Core manifest/hash、Helper 绑定相同 Core bytes 是正确的包装约束，不能因为产品自立删除。证据：[setup.dart:57](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/setup.dart#L57)、[services/helper/build.rs:2](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/services/helper/build.rs#L2)、[test/tool/packaging_config_test.dart:64](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/test/tool/packaging_config_test.dart#L64)。当前桌面 setup 没有 Android 专用版本 resolver 覆盖，后续要统一消费产品版本计划，避免本地/桌面/Android 各自算版本。
- 现有 `tool/release.sh`/`bump_version.sh` 仍提供另一套从 pubspec、日期 build、普通 vX.Y.Z/pre tag 出发的流程；其 help/外部发布描述基于继承 CI。应选择统一的 Aerove release-plan 为权威，将旧入口显式转接或标明历史用途，不能同时保留多个当前产品编号分配器。

## 4. CoreInterface 的层次与实际调用链

`SetupAction/ProxiesAction/各 feature provider` → 注入的 `coreHandlerProvider/CoreController` → `CoreInterface` 的强业务类型方法 → `CoreHandlerInterface` 将 DTO toJson 与 CoreMethod 编码 → Android `CoreLib` → service MethodChannel → Kotlin ServicePlugin/Core → gomobile Mobile → Go handler；desktop `CoreService` → `CoreRpcClient`（request id、超时、response unwrap）→ IpcCoreTransport（pipe/socket）→ Go server → `core/method.go` handler table → Mihomo。

证据：[lib/providers/actions/setup.dart:37](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/lib/providers/actions/setup.dart#L37)、[lib/core/controller.dart:18](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/lib/core/controller.dart#L18)、[lib/core/interface.dart:10](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/lib/core/interface.dart#L10)、[lib/core/interface.dart:123](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/lib/core/interface.dart#L123)、[lib/core/lib.dart:145](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/lib/core/lib.dart#L145)、[lib/core/service.dart:36](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/lib/core/service.dart#L36)、[lib/core/desktop/rpc_client.dart:78](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/lib/core/desktop/rpc_client.dart#L78)、[core/method.go:181](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/core/method.go#L181)。

所以“已有 interface，因此可直接任意替换 backend”不成立；但也不能把它简单叫成 transport：传输已在下一层。可以先在现业务 façade 上方定义 Aerove 的能力/领域结果，把当前 Core façade 作为 Mihomo adapter，按真实用例逐步迁移，保留生命周期 owner。

| 业务接口 | 确认的具体引擎耦合 | 证据 |
| --- | --- | --- |
| buildProfile/getProfileBase/validateConfig/setupConfig | SetupBase 是路径+YAML overlay，ProfileBase 包含 proxy-groups/match-rules；Go 使用 config.DefaultRawConfig、ParseRawConfig、executor.ParseWithBytes | [lib/models/core.dart:50](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/lib/models/core.dart#L50)；[core/profile_base.go:101](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/core/profile_base.go#L101)；[core/config.go:123](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/core/config.go#L123) |
| updateConfig | mixed-port、allow-lan、find-process-mode、external-controller、Tun、geox-url 等直接是 Mihomo 配置键；Go 修改 tunnel/dialer/resolver 全局状态 | [lib/models/core.dart:74](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/lib/models/core.dart#L74)；[core/config.go:239](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/core/config.go#L239) |
| getProxies/changeProxy | adapter.Proxy、Selector/URLTest/Fallback/Relay/LoadBalance、MarshalJSON、Set/ForceSet/Now 的组语义 | [core/proxy.go:85](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/core/proxy.go#L85)；[core/proxy.go:140](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/core/proxy.go#L140) |
| asyncTestDelay/getLatestDelays | 直接调用 proxy.URLTest，uint16 delay/timeout/失败与历史值语义、UI取消策略与引擎测试历史耦合 | [core/delay.go:75](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/core/delay.go#L75)；lib/core/interface.dart:602 |
| get/update/sideLoadExternalProvider | ProxySetProvider/RuleSetProvider、vehicle、SideUpdate、subscriptionInfo 类型断言 | [core/provider.go:30](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/core/provider.go#L30)；lib/models/core.dart:346 |
| dumpRuleSet/compileRuleSet/updateGeoData | Mihomo MRS 格式与 RuleBehavior、geo updater/matcher 重建，不是通用 provider API | [core/rule_set.go:117](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/core/rule_set.go#L117)；core/geo.go:47 |
| getConnections/getTraffic/reset/close | statistic.DefaultManager 的 Snapshot/Tracker/统计代理流量参数直接进入应用层 | [core/connection.go:8](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/core/connection.go#L8)；lib/models/common.dart:119 |
| decodeShareLinks/validateProxies | convert.ConvertsV2Ray 与 adapter.ParseProxy 直接解释协议 map | [core/share_link.go:25](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/core/share_link.go#L25)；core/proxy.go:193 |
| watchRoute/probe/outboundIp/serviceCheck | picks/coreEpoch/链路 stamp 及路由新鲜度协议，RoutedProbe 判定何时接收结果 | core/route.go；[lib/providers/route_state.dart:63](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/lib/providers/route_state.dart#L63) |

先收敛能力如配置应用、出站选择、观测、资源更新，不给“所有后端都支持所有 Mihomo 特性”的空接口。格式转换、MRS/geodata、external-controller 等明确作为 adapter 能力/不支持结果；不能在 UI 换个类名继续隐式透传任意 map。后端中立化也不应把 backend lifecycle 再复制到 UI/provider。

## 5. 不可破坏的平台所有权

1. Desktop 进程状态由 DesktopCoreLifecycle 最新意图收敛：revision、session/PID/connection generation、CoreProcessLease 与未确认退出 lease。停止未确认不能重启第二个实例；close 终态且幂等。证据：[lib/core/desktop/lifecycle.dart:126](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/lib/core/desktop/lifecycle.dart#L126)、[lib/core/desktop/lifecycle.dart:196](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/lib/core/desktop/lifecycle.dart#L196)、[lib/core/desktop/lifecycle.dart:522](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/lib/core/desktop/lifecycle.dart#L522)。
2. Android `ServicePlugin.start/stop` 只提交命令后立即答复；ServiceState/ServiceStateMachine 负责 RunRequest 令牌、permission/setup 与 transition 两把锁，RunState 从 runtime+最新意图派生。不能让 Flutter 回调再决定“真正运行”。证据：[android/app/src/main/kotlin/com/follow/clash/plugins/ServicePlugin.kt:144](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/android/app/src/main/kotlin/com/follow/clash/plugins/ServicePlugin.kt#L144)、[android/app/src/main/kotlin/com/follow/clash/ServiceStateMachine.kt:46](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/android/app/src/main/kotlin/com/follow/clash/ServiceStateMachine.kt#L46)、[android/app/src/main/kotlin/com/follow/clash/ServiceStateMachine.kt:324](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/android/app/src/main/kotlin/com/follow/clash/ServiceStateMachine.kt#L324)。
3. ServiceController 仍是 ManagedServiceBinding/runtime 的唯一 owner；Quick Settings、Always-on、系统 revoke 进入同一意图仲裁，不把 service 创建/销毁等同于用户启停。证据：[android/app/src/main/kotlin/com/follow/clash/ServiceController.kt:77](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/android/app/src/main/kotlin/com/follow/clash/ServiceController.kt#L77)、android/service/src/main/java/com/follow/clash/service/VpnService.kt:113、:120。
4. 应用退出集中于 SystemExitCoordinator 与 SystemAction，清理失败仍执行之后步骤，不能 widget dispose/crash manager 各自杀 Core。证据：[lib/providers/actions/system_exit.dart:5](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/lib/providers/actions/system_exit.dart#L5)、lib/providers/actions/system.dart:106。
5. transport/RPC 保留 request 关联、断连 pending 失败、结构化错误/事件批处理；Helper 保留 executable hash、session 与调用身份检查；更换后端也不能退化为无所有权的 shell 启停。
6. 安全模式在各宿主资源 owner 生效，不能通过覆盖用户 TUN/DNS/系统代理设置模拟；本次没有启动任何主机代理或修改系统状态。
7. 当前配置应用 digest 纳入实际 buildNumber，见 [lib/providers/actions/setup.dart:554](https://github.com/IvanSolis1989/Aerove/blob/128e3227db353c626d5de9750c2b09bb69dca5c8/lib/providers/actions/setup.dart#L554)；因此换版本权威需验证配置缓存、adopt/listener/reapply，而不是只检查 APK 文件名。升级 cache key 时不能抹掉现有最新意图仲裁。

## 6. 最小连贯改造顺序（当前授权可先做）

**A. 版本与来源解耦，一次闭合构建/验收/更新链。** 新增 Aerove product resolver：严格解析自己 pubspec 的 SemVer，使用自身 prerelease 决定通道，保留 `2026101700+run`；CLI 从给定 source commit 读取 product pubspec 与 provenance metadata，来源继续验历史 source/tag commit 祖先与历史 pubspec。由主线程接 Android workflow、artifact validator、notes/tag、客户端升级兼容与文档；旧 xclash_version 和历史包 validator 不改写。不能只改 build-name 而漏 validator/notes。

**B. 停止自动决定产品内容。** sync-upstream 变为手动/只读发现+候选分支或 PR，去掉直接 main push 和 release dispatch；保留同源审查、冲突证据、基线竞态。上游升级作为有范围、有回归的普通开发事项，产品修复版不等待最高 tag。

**C. 一个开发/发布政策来源。** 明确自有版本与构建计划、支持平台、检查矩阵、changelog 归属和依赖清单；重复 workflow 用可复用 jobs 整合；保留需要的 Go/Rust/Kotlin/Flutter 检查，前端选择未定时不假定已删除 Flutter。README 链接检查变成产品发布资产检查并保留归因/许可。

**D. 逐用例建立可替换后端边界。** 先定义 Aerove 的领域能力和结果、适配器契约与 fake conformance，再迁移现 CoreController 调用；保持当前 Mihomo 实现可用。单独做后端/前端方案评估，不以 rename、删版权、改 import 或大空抽象宣称独立完成。

本次职责升级仅实施 A 中 `tool/aerove_version.py` 与 `tool/test_aerove_version.py` 两个新文件；其他项目集成由主线程负责。本报告不宣称尚未接入的整个发布链已独立。

## 7. 必须同步的测试清单

| 测试位置 | 保留/更新内容 |
| --- | --- |
| tool/test_aerove_version.py（本次新增） | temp Git 真入口、指定 head 取输入、独立 SemVer/build/channel、legacy code 单调性、历史 provenance 严格、上游新 tag 不影响自己版本、CLI 参数错误；禁止联网 |
| tool/test_xclash_version.py:211/326/391 | 作为历史 fork /显式来源发现规则保留；不再作为当前产品“必须最高 tag”的发布政策 |
| tool/test_aerove_artifact.py:122/136/203/229/364 | 新 schema 用产品 resolver，保留原 run/source/tree/patch0/hash/ABI 与拒绝路径；旧包样本继续能验收 |
| tool/test_aerove_release_notes.py:17/36/47/62 | 支持自主 tag，保留精确 BUILD_INFO/source/已提交 changelog/真实内容和防旧待发布复用 |
| test/common/request_test.dart:141、test/common/helpers_test.dart:110 | 新/旧 -X SemVer 比较、不会以较低产品 SemVer漏升级、稳定/预览渠道的真实客户端入口；不编造已支持 preview 更新订阅 |
| test/common/changelog_test.dart、test/widgets/update_dialog_test.dart | 自有 tag 与历史 release notes marker 兼容、正确资产与更新展示 |
| README 同步 helper + workflow 检查 | 新增纯函数 fixture（自有 marker、仓库/下载链接/许可），验证移除自动 main 发布、只读候选及失败停机，避免全靠 YAML 字符串断言 |
| test/setup_test.dart、test/tool/packaging_config_test.dart:64 | native hooks 必须开启、包身份/显示名、Helper 与 Core bytes 一致、不同平台用同一产品计划 |
| test/core/protocol_contract_test.dart:197/383/426/445/460 | 保留真实 JSON/Go 协议契约；新领域接口另外做能力/不支持结果测试，不删旧断言伪装解耦 |
| test/core/desktop/、test/core/service_test.dart、android/tests/app/ServiceStateMachineTest.kt | 进程 lease、session/PID、latest intent、终态关闭、权限/系统回调和 native 仲裁 |
| test/providers/action_test.dart、system_action_test.dart、setup_action_test.dart、routed_probe_test.dart、test/common/setup_digest_test.dart | restart/exit/setup/reapply/route freshness 继续正确；产品版本改变只使正确的 cache key 失效 |

验证建议：先运行纯 Python version/artifact/notes tests；接线后 actionlint 与受影响打包/升级 tests；再执行既定 UI/Go/Rust/Kotlin/Android 验收。完整回归不可被“版本 helper green”替代；本次没有运行 Flutter 或任何代理。

## 8. 验证界限与交付

本报告使用稳定基线源码、适用 core-platform 技能与只读内存反例，不使用旧 preview diff，不查询网络上的实时 tag/发布/部署状态。源码存在的 job 不等于 GitHub 当前启用或某次 run 成功；没有外推这些外部状态。审计阶段没有修改项目文件。新增版本模块的实际实现/API/测试结果将在本报告末尾追加。

## 9. 独立版本模块实施与交接（审计后授权实施）

实际项目写集仅两个新文件：`tool/aerove_version.py`、`tool/test_aerove_version.py`。未修改旧 `xclash_version`、pubspec、workflow、artifact validator、README、平台或前后端源码；主线程正在独立整合其他责任文件，不能将其 diff 归为本模块改动。没有 stage/commit/Flutter/全局安装/联网/启动代理。

### API 与权威输入

- `resolve_product_version(pubspec_text, run_number) -> dict` 返回且仅返回 `name/number/tag/app_env`。接受严格 `MAJOR.MINOR.PATCH[-prerelease][+build.metadata]`；**build metadata 可省略**，空 `+`、非法字符、前导零数字 prerelease、重复 version 键等拒绝。引用/注释/CRLF 兼容。
- SemVer 有 prerelease 就是 `pre`，无 prerelease 为 `stable`；不接受 CLI 通道覆盖，不使用环境变量或分支名。任何合法 prerelease 标识均归入 pre，并非只接受 `pre.N`。
- `name` 不自动附加 -X，也不包含 pubspec build metadata；`number=2026101700+原 run_number`；`tag=v<name>+<number>`。布尔/非整数/非正 run 与超 Android 上限拒绝。run 149 为 2026101849，run 150 为 2026101850；产品 build metadata 不参与编号。
- `build_release_plan(*, repo, head, run_number, metadata, pubspec)` 中两个 Path 参数按 repo 解析，并从 **指定完整 SHA 的 Git blob** 读取。不是读取当前 checkout 字节；working tree 变更或 checkout 更新均不改变已指定 head 的计划。路径不得逃出 repo。
- CLI `release-plan --metadata ... --pubspec ... --repo ... --head <full-sha> --run-number ...` 保留原调用形式。输出上述四字段与七个原 `upstream_*` 字段。错误仅 stderr、无计划 stdout、非零状态。
- 上游 schema/repository/commit/tag identity、source 属于给定 head 的历史、tag commit 祖先、metadata 与历史 source 的完整 pubspec 相符仍严格校验；当前产品 pubspec 可以不同；不调用 select/fetch/最高 tag。批准并提交在 head 中的 metadata 是冻结的历史证据，不声称离线查询了远端当前 tag 指针。
- artifact validator 调用纯 resolver 时应给它与 source checkout 对应的 pubspec 文本和**原构建** run_number；是否采用新产品 schema 或 legacy 模式由主线程接线，不由 pure resolver 猜测。

### 实际验证

最终运行：`python -B -m coverage run --branch --source=tool.aerove_version --data-file=D:/CodexTemp/aerove-independent-20261022/version.coverage -m unittest tool.test_aerove_version -v`；随后 coverage report。

结果：**21 tests，18.772s，OK；模块分支统计覆盖率 95%（96 statements、30 branches，3 missing statements）**。真实 temp Git/CLI 覆盖不同产品版本、稳定/预览无 build、带 metadata、追加更高 upstream preview/stable、删除移动 upstream refs 后仍离线发布、被篡改来源/历史 pubspec/不属于 head 的 source 拒绝、dirty/较新 checkout 不影响 head 计划、布尔/溢出 run 和非法参数、输入文件不被写回。另通过 Python AST/空白检查。

无 build 新用例先真实失败（纯 resolver 两项 error、temp Git CLI 两项 fail），修正后完整 21 项通过；原始日志保留此前初次缺模块失败及各轮输出，未覆盖失败证据。日志：`D:/CodexTemp/aerove-independent-20261022/version-tests.log`。

另在真实审计 worktree 只读执行固定 `128e3227...`、run 149 的 CLI，得到 `name=0.9.1, number=2026101849, tag=v0.9.1+2026101849, app_env=stable`，七项 upstream 输出仍是原 `8d4f974.../v0.9.1/0.9.1+2026101702`，与工作区并行修改无关。

模块与测试已稳定，释放给主线程整合。尚未由本责任域验证整合后的 workflow/validator/notes/客户端/UI/Android 实包；21 项通过不能代替主线程全链验收。旧 preview 保持冻结。
