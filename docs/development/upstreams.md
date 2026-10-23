# Aerove 独立维护与来源参考

Aerove 独立管理产品需求、版本和发布。FlClash 是保留版权与历史记录的来源项目，也可以作为功能参考；其新标签、分支推进或版本号不会自动改写本产品，也不再阻止本产品发版。

## 开源来源与鸣谢

本项目在功能开发前先检索可复用的 GitHub 实现；实际采用的依赖、复制适配代码、只读参照和仅评估未采用的候选分别记录。固定 commit 的来源以 commit 为准；只有 tag/版本和锁文件校验值时，明确记录现有证据，不推测完整 commit。该表覆盖已登记的实际来源，不是完整传递依赖许可证清单，也不代表所有历史调研均已穷尽。

| 上游仓库 | 固定版本 / revision | 用途与采用方式 | 许可证与署名/文件证据 |
| --- | --- | --- | --- |
| [chen08209/FlClash](https://github.com/chen08209/FlClash) | 历史整合 `v0.9.2-pre.1` / `7220961f7c5e494df831ca196ab53ef08a17e926`；本轮只读参照 `8d4f9744948751f1273fe871e1d05276fac09b0e` | 标准版历史源码选择性整合；另只读核对 `lib/common/request.dart` 的 `latestRelease` / `checkForUpdate` 现有检查路径，未复制新算法或新增依赖 | 保留历史版权与对应文件许可；`apps/aerove/core/mihomo-upstream.json` 中保留的双父与补丁来源记录；[固定源码 `lib/common/request.dart`](https://github.com/chen08209/FlClash/blob/8d4f9744948751f1273fe871e1d05276fac09b0e/lib/common/request.dart)、[GitHub Releases List API 文档](https://docs.github.com/en/rest/releases/releases#list-releases) |
| [MetaCubeX/mihomo](https://github.com/MetaCubeX/mihomo) | `v1.19.33` / `3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8` | 标准版 Go 代理核心，来源代码及本项目适配 | GPL-3.0；`apps/aerove/core/mihomo-upstream.json`、`apps/aerove/core/Clash.Meta/LICENSE`、该目录源码及其原始版权头 |
| [MetaCubeX/http](https://github.com/MetaCubeX/http) | `v0.1.8` / `3ffb994bb87614dd9f1fc40e036613b04b4a8ffd` | 标准版现有Go依赖；只读调查HTTP请求取消与拨号/连接清理链，未采用新的transport桥，不能据源码审查声称问题已修复 | BSD-3-Clause；`apps/aerove/core/Clash.Meta/go.mod`、[固定源码](https://github.com/MetaCubeX/http/tree/3ffb994bb87614dd9f1fc40e036613b04b4a8ffd)、[LICENSE](https://github.com/MetaCubeX/http/blob/3ffb994bb87614dd9f1fc40e036613b04b4a8ffd/LICENSE) |
| [MetaCubeX/tls](https://github.com/MetaCubeX/tls) | `v0.1.9`（由现有Go模块锁定） | 标准版现有Go依赖；只读调查TLS握手取消和底层连接关闭路径，未引入新的TLS桥或更改现有请求行为 | BSD-3-Clause；`apps/aerove/core/Clash.Meta/go.mod`、[v0.1.9 LICENSE](https://github.com/MetaCubeX/tls/blob/v0.1.9/LICENSE) |
| [ibigbug/clash-rs](https://github.com/ibigbug/clash-rs) | `v0.10.10` / `36eec77463d1243ede287bf6b7197e38237948d5` | Lite Rust 代理核心复制适配；socket保护、移动端嵌入、日志路由、生命周期、provider校验等适配 | 上游固定源码含 Apache-2.0 LICENSE；启用 `tuic` 时按固定源码 NOTICE 为 GPL-3.0；Aerove 整仓 GPL 许可保持不变；`apps/aerove-lite/native/NOTICE.md`、`native/licenses/clash-rs-NOTICE.md`、`native/upstream.patch`、`native/dependency-protect/manifest.json`；保留上游版权与NOTICE |
| [Watfaq-legacy/clash-rs](https://github.com/Watfaq-legacy/clash-rs) | 固定源码提交 `36eec77463d1243ede287bf6b7197e38237948d5`（与上行核心提交相同；独立 fork 仓库） | Native Direct/WS 源码只读参照；未采用为第二代理核心 | 固定源码 LICENSE 为 Apache-2.0；[固定提交 LICENSE](https://github.com/Watfaq-legacy/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/LICENSE)；GitHub 仓库身份及 fork parent 由仓库元数据核实 |
| [material-components/material-components-android](https://github.com/material-components/material-components-android) | `1.13.0` / `84c0e6d6d493fcf176d2099683245b78afbeb78d` | Lite Material Color Utilities：20个Java类复制，包名迁移并按来源记录移除源码级Error Prone注解 | Apache-2.0；`apps/aerove-lite/third_party/material-color-utilities/LICENSE`、`SOURCES.json` |
| [dlclark/regexp2](https://github.com/dlclark/regexp2) | `v1.12.0`（`apps/aerove-lite/regexp2/dependencies.json` 固定模块版本） | Lite regexp2 Go辅助代码纳入/适配 | MIT；`apps/aerove-lite/regexp2/licenses/github.com_dlclark_regexp2/LICENSE` |
| [MetaCubeX/age](https://github.com/MetaCubeX/age) | `v0.0.0-20260603010618-28d156b4ea78`（伪版本后缀仅证明短修订，不补写完整SHA） | regexp2客户端辅助包中的Age解密bridge；实际调用Go age实现 | 以随包许可文本为准；`apps/aerove-lite/regexp2/dependencies.json`、`regexp2/licenses/github.com_metacubex_age/LICENSE`、本轮 `apps/aerove-lite/regexp2/agebridge/`、`regexp2/dependencies.json` |
| [MetaCubeX/hkdf](https://github.com/MetaCubeX/hkdf) | `v0.1.0`（依赖清单版本） | Age加密辅助依赖，作为现成依赖随辅助包使用；未登记为复制其源码 | 以 `regexp2/licenses/github.com_metacubex_hkdf/LICENSE` 为准；`regexp2/dependencies.json` |
| [MetaCubeX/hpke](https://github.com/MetaCubeX/hpke) | `v0.1.0`（依赖清单版本） | Age加密辅助依赖，作为现成依赖随辅助包使用 | 以 `regexp2/licenses/github.com_metacubex_hpke/LICENSE` 为准；`regexp2/dependencies.json` |
| [MetaCubeX/mlkem](https://github.com/MetaCubeX/mlkem) | `v0.1.0`（依赖清单版本） | Age加密辅助依赖，作为现成依赖随辅助包使用 | 以 `regexp2/licenses/github.com_metacubex_mlkem/LICENSE` 为准；`regexp2/dependencies.json` |
| [golang/crypto](https://github.com/golang/crypto) | `v0.57.0`（依赖清单版本） | regexp2/Age辅助包所用Go密码学支持依赖 | BSD-3-Clause；`regexp2/licenses/golang.org_x_crypto/LICENSE`、`PATENTS`、`dependencies.json` |
| [golang/sys](https://github.com/golang/sys) | `v0.48.0`（依赖清单版本） | regexp2/Age辅助包所用Go系统接口依赖 | BSD-3-Clause；`regexp2/licenses/golang.org_x_sys/LICENSE`、`PATENTS`、`dependencies.json` |
| [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) | `io.github.kyant0:backdrop:2.0.1` / `65ab177e90e5c1d8c62e70cf7755841982da65f6` | Lite材质/背景呈现所用Backdrop实现，直接依赖 | Apache-2.0；`apps/aerove-lite/app/build.gradle.kts`、`docs/research/rendering/backdrop.md`、`app/src/main/assets/optics/NOTICE.md` |
| [Kotlin/kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | `1.10.2`，release/tag短commit `5f89004` | 读取 `CoroutineScope.kt`、`Job.kt`、`Builders.common.kt`，复用结构化并发取消语义，并确认 `withContext` 的prompt cancellation可能在IO提交后丢失返回值；导入/文件写入沿用项目既有 `onCommitted` 回执处理；未复制实现代码、未新增依赖 | Apache-2.0；`apps/aerove-lite/app/build.gradle.kts`、[CoroutineScope.kt](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/CoroutineScope.kt)、[Job.kt](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/Job.kt)、[Builders.common.kt](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/Builders.common.kt) |
| [BigBadaboom/androidsvg](https://github.com/BigBadaboom/androidsvg) | Maven `com.caverock:androidsvg-aar:1.4`；AAR SHA256 `02a5b08a2b35d2d58eb2eaca9d84ac00fb341da725fdbd653ea3ed130437e95a`，无源码commit记录 | Lite代理组图标SVG渲染，直接依赖；AAR源包核验 | Apache-2.0；`apps/aerove-lite/app/build.gradle.kts` |
| [stleary/JSON-java](https://github.com/stleary/JSON-java) | `20250517` | Lite现有JSON依赖API只读参考：核对 `JSONObject` 键与类型访问语义，沿用项目现有SDK/解析路径；未复制代码或新增依赖 | Public Domain；[固定版本 LICENSE](https://github.com/stleary/JSON-java/blob/20250517/LICENSE)、[JSONObject.java](https://github.com/stleary/JSON-java/blob/20250517/src/main/java/org/json/JSONObject.java)、`apps/aerove-lite/app/build.gradle.kts` |
| [square/okhttp](https://github.com/square/okhttp) | `4.12.0` | Lite HTTP请求与MockWebServer测试现存依赖；本轮结构化并发变更没有参照Dispatcher实现，也未改客户端池/并发策略 | Apache-2.0；`apps/aerove-lite/app/build.gradle.kts`。本轮尝试打开tagged Dispatcher源码时工具重定向到未确认owner页面，该页面未作依据且未取用代码 |
| [snakeyaml/snakeyaml](https://github.com/snakeyaml/snakeyaml) | `2.4` | Lite配置YAML解析，直接依赖 | Apache-2.0；`apps/aerove-lite/app/build.gradle.kts` |
| [zxing/zxing](https://github.com/zxing/zxing) | `3.5.3` | Lite二维码读写，直接依赖 | Apache-2.0；`apps/aerove-lite/app/build.gradle.kts` |
| [Flutter](https://github.com/flutter/flutter) | SDK `3.47.6` / framework `5fc346839b5d0eef006ed8404392afb4dfae428d` | 标准版Flutter框架；Lite optics几何与AGSL shader适配源自Flutter Impeller superellipse；字体测试fixture保留Roboto许可，生产不新增捆绑字体；字体策略只读参考SDK Android默认字体行为 | BSD-3-Clause（Flutter）；字体按原 `roboto_license.txt`；`apps/aerove-lite/app/src/main/assets/optics/NOTICE.md`、`FLUTTER_LICENSE`、`apps/aerove/test/fixtures/fonts/SOURCE.json` |
| [fzyzcjy/flutter_rust_bridge](https://github.com/fzyzcjy/flutter_rust_bridge) | `5463b190636ca9edf705be0b168471875e675ebf` | CI/Dart集成只读参考：核对 `RustLib.init()` 初始化方式；未复制代码、未新增依赖 | MIT；[固定源码](https://github.com/fzyzcjy/flutter_rust_bridge/blob/5463b190636ca9edf705be0b168471875e675ebf/frb_example/flutter_via_integrate_native_assets/lib/main.dart)、[LICENSE](https://github.com/fzyzcjy/flutter_rust_bridge/blob/5463b190636ca9edf705be0b168471875e675ebf/LICENSE)；`.github/workflows/xclash-android.yaml` |
| [dart-lang/sdk](https://github.com/dart-lang/sdk) | Dart `3.13.5` / `04bcd1036cdc799ac6564988f159ee454d42c822` | TLS、字体、Socket API及 `Isolate.kill` 生命周期只读核验；kill不保证挂起socket立即EOF/FD清理，因此未以隔离区退出替代逐请求取消 | BSD-3-Clause；[Isolate.kill API](https://api.dart.dev/dart-isolate/Isolate/kill.html)、固定Dart SDK版本/source |
| [rust-lang/cargo](https://github.com/rust-lang/cargo) | `956fdaeb068d791a39dec140f2efc9ba48c59729` | 只读研究Cargo官方source/选包语义，不复制Cargo实现，不构成应用运行时依赖 | Apache-2.0 OR MIT；本轮来源检索记录 |
| [spongebob888/shadowquic](https://github.com/spongebob888/shadowquic) | `0.3.13`；源码crate在上游clash-rs仓库锁定版本；本次未另记完整commit | Lite核心的真实上游依赖及适配，另只读核对官方JLS/QUIC server、SQConnect API编写loopback peer；peer仍是测试验证 | MIT；`apps/aerove-lite/native/Cargo.lock`、`native/upstream/shadowquic_peer_tests.rs`，核心锁源 `apps/aerove-lite/native/.upstream/shadowquic/Cargo.toml` |
| [spongebob888/rustls-jls](https://github.com/spongebob888/rustls-jls) | `1.3.7`，crate SHA256 `e7b8b5e6fb5b6c65cc8b198609185aa0668d80df151ee4928cf978413bec0bed`；Cargo.toml.repository确认仓库，未声称此为上游Git commit | Lite核心JLS/TLS依赖与适配；只读检查loopback peer涉及的JLS认证/回退语义，未复制上游实现 | Apache-2.0 OR ISC OR MIT；`apps/aerove-lite/native/Cargo.lock`及原crate许可证 |
| [quinn-rs/quinn](https://github.com/quinn-rs/quinn) | `quinn-proto-jls 0.3.8` SHA256 `cfff8bfe6190cc5e06e722680afbe1ec5cf3c6e8b62d026b1551bb68c63f0fb3`；`quinn-jls 0.3.8` SHA256 `06575428b476c2672be58db56c48d517c2b469b3083eeb9f51dc4f61de541be4`；Cargo.toml.repository字段确认仓库，不宣称精确Git commit | Lite核心QUIC依赖/适配；只读检查loopback peer所需的JLS认证与fallback API，未复制上游实现 | MIT OR Apache-2.0；`apps/aerove-lite/native/Cargo.lock`及原crate许可证 |
| [warp-tech/russh](https://github.com/warp-tech/russh) | `0.61.1`，crate SHA256 `f67013f080c226e5a34db1c71f2567f44d95a6300005bb6cd4e2c8fe3c326d1b` | Lite核心的真实SSH依赖/适配；另只读读取SSH server API与官方loopback echo示例编写测试peer，未复制上游实现或示例 | Apache-2.0；`apps/aerove-lite/native/Cargo.lock`、`native/upstream/ssh_peer_tests.rs` |
| [Watfaq/boring-noise](https://github.com/Watfaq/boring-noise) | `9ddf1b54d0c9679e7bc50e9151f72d526190db1a` | Lite核心的WireGuard Noise实现依赖及适配；测试peer另读取 `Tunn` API，不复制上游实现 | MIT；固定revision源码包原始LICENSE保留于受控依赖快照，`apps/aerove-lite/native/Cargo.lock`、`native/upstream/wireguard_peer_tests.rs` |
| [tailscale/tailscale-rs](https://github.com/tailscale/tailscale-rs) | `0.6.1`；crate源码通过依赖保护快照固定，源码归档SHA见 `native/dependency-protect/manifest.json` | Lite Tailscale/DERP族Rust依赖来源；`tailscale`、`ts_control`、`ts_derp`等均来自同一仓库和固定版本。manifest列出原始crate、hash及本地补丁 | BSD-3-Clause；`apps/aerove-lite/native/dependency-protect/manifest.json`、对应vendor包LICENSE |
| [DelSkayn/rquickjs](https://github.com/DelSkayn/rquickjs) | `rquickjs 0.12.2`，Cargo.lock checksum `4e04e4eedfb060b503b5f0a2644abb890b0b3620d3fb674f9455f230014964e4` | 旧 Lite JS API 及 Meow 桥接的 QuickJS Runtime/Context/Function 直接依赖；Meow 保留原脚本模块与六项用例，成功 host 图已验证 | MIT；`apps/aerove-lite/native/Cargo.toml`、`Cargo.lock`；新桥接 `apps/aerove-lite/meow-native/{Cargo.toml,Cargo.lock,src/script_engine.rs}` |
| [Itsusinn/tuic](https://github.com/Itsusinn/tuic) | `v1.8.5` | Lite clash-rs审计中只读调查TUIC实现和协议行为；与clash-rs中实际启用的TUIC依赖区分，不复制该仓库代码 | GPL-3.0；`docs/aerove-lite/core/clash-rs-audit.md` |
| [material-foundation/material-color-utilities](https://github.com/material-foundation/material-color-utilities) | `0.13.0` / pub.lock SHA256 `9c337007e82b1889149c82ed242ed1cb24a66044e30979c44912381e9be4c48b` | Standard Flutter Material色彩依赖；保留pub锁定 | Apache-2.0；`apps/aerove/pubspec.lock` |
| [dart-lang/http](https://github.com/dart-lang/http) | 搜索结果commit `3b504cf09cb70ae4071d4c2549c903aad5cf4bc4`；Cronet `cronet_http 1.10.0` | 实际审查Cronet取消、代理、TLS与平台接口后排除为完整替换；只读检查源码和公开API，不构成采用或修复完成声明 | BSD-3-Clause；[固定源码](https://github.com/dart-lang/http/tree/3b504cf09cb70ae4071d4c2549c903aad5cf4bc4)，SDK/API评估记录 |
| [chen08209/Clash.Meta](https://github.com/chen08209/Clash.Meta) | `8380d0e7462f061a964323e9bf3129a236fcc5d5` | FlClash历史来源体系中的适配源码参照；审计 mihomo DNS配置行为及保留的API适配来源，不作为当前核心主线 | GPL-3.0；`docs/aerove-lite/core/clash-rs-audit.md`、`apps/aerove/core/mihomo-upstream.json` |
| [Kyant0/Shapes](https://github.com/Kyant0/Shapes) | `1.2.1` / `ad83e4ad9ca6219cb0091fdc75d9b349865bf12b` | Backdrop配套形状库，Lite直接依赖；此前登记的 `Kyant0/AndroidShapes` 使用相同版本和固定revision，按同一来源别名归并 | Apache-2.0；Gradle依赖、`docs/research/rendering/backdrop.md` |
| [tokio-rs/tokio](https://github.com/tokio-rs/tokio) | `1.53.1`（Cargo.lock固定registry crate校验值；tag `tokio-1.53.1`）；Meow 桥接成功锁另含 `tokio 1.54.0` / `a0c168a474b95bfecfe908a65d4bdffc523144b649a74c09c286afb35d53e0bc`、`tokio-util 0.7.20` / `e464cf451ba96ebfc6f9b6542f17ee8b8956e33f1e40d9690624e59d7a7f8a4b` | 旧 Lite 异步运行时及广播重入只读源码参照；新 `meow-native` 直接采用 runtime、取消和任务治理 API，未复制 Tokio 实现 | MIT；`apps/aerove-lite/native/Cargo.toml`、`Cargo.lock`、[固定版本 LICENSE](https://github.com/tokio-rs/tokio/blob/tokio-1.53.1/LICENSE)、[broadcast.rs](https://docs.rs/tokio/1.53.1/src/tokio/sync/broadcast.rs.html)；新图原始包信息按 crates.io 精确版本 metadata 与桥接 Cargo.lock 核实（未取得 Tokio 新版源码包） |
| [tokio-rs/axum](https://github.com/tokio-rs/axum) | `axum-v0.8.9`；`axum 0.8.9` registry checksum `31b698c5f9a010f6573133b09e0de5408834d0c82f8d7475a89fc1867a71cd90` | 本轮只读核对 `ws.rs` 的 WebSocket upgrade/callback 顺序，以及 `routing/nest.md` 的末尾斜杠匹配语义，修正测试请求路径；未复制源码或新增依赖；Meow 桥接直接采用同版本路由/JSON/受控 HTTP API，旧参考信息保留 | MIT；[`axum/Cargo.toml`](https://github.com/tokio-rs/axum/blob/axum-v0.8.9/axum/Cargo.toml) 声明许可，[固定版本 LICENSE](https://github.com/tokio-rs/axum/blob/axum-v0.8.9/axum/LICENSE)、[ws.rs](https://github.com/tokio-rs/axum/blob/axum-v0.8.9/axum/src/extract/ws.rs)、[nest.md](https://github.com/tokio-rs/axum/blob/axum-v0.8.9/axum/src/docs/routing/nest.md) |
| [rust-lang/rust](https://github.com/rust-lang/rust) | `1.91.0` | 只读核验标准库 `RwLock::set` 在锁外析构旧值的行为；不复制标准库代码 | MIT OR Apache-2.0；来源为固定版本 `library/std/src/sync/poison/rwlock.rs`，本轮native资源实现记录 |
| [Smart-Config-Kit](https://github.com/IvanSolis1989/Smart-Config-Kit) | `28b86e2e991cde8bc2fa71b874e807c622f2b096` | 分类 Issue Forms、MiMo Flash 首轮与 Pro 追问、过滤/失败只读模式作为流程参考；本产品重新实现，没有复制规则/提示词或自动PR | 未复制或分发其代码；上游 `.github/ISSUE_TEMPLATE`、固定revision，本仓库只读流程对照 |
| [tun-rs/tun-rs](https://github.com/tun-rs/tun-rs) | `d0f764c135a34ad92360462f89a40b59f373cc06` | Lite clash-rs核心能力审计中，只读核对Unix异步设备/已打开fd保护边界；未新增独立依赖 | MIT；`docs/aerove-lite/core/clash-rs-audit.md` |
| [Arc-blroth/memory-stats](https://github.com/Arc-blroth/memory-stats) | `431e74654c27ec38a6f7d19c186bec42c2a938b8` | Lite核心审计中只读核对内存统计实现；未新增依赖 | MIT；`docs/aerove-lite/core/clash-rs-audit.md` |
| [meow-rs/meow-rs](https://github.com/meow-rs/meow-rs) | `v0.26.1` / `727ddcd06f592e2f12c5222c4de6cce328df808c` | Lite Rust代理内核迁移来源，固定源码用于实施；不表示已完成集成、功能对齐、APK或设备验收。保留此前只读评估公开基准及 Android DNS 行为的历史记录，并参考官方 Android FFI/Cargo 实现。 | MIT；[固定提交 LICENSE](https://github.com/meow-rs/meow-rs/blob/727ddcd06f592e2f12c5222c4de6cce328df808c/LICENSE)、[固定 Cargo.toml](https://github.com/meow-rs/meow-rs/blob/727ddcd06f592e2f12c5222c4de6cce328df808c/Cargo.toml)；官方 Android FFI 来源和 SHA256 见外部迁移报告。 |
| [meow-rs/meow-android](https://github.com/meow-rs/meow-android) | `4740012bf0efdb8bae8fbeeefaf4937dfa309f26`；该参考自身 Meow 依赖仍为 `v0.26.0` | Lite Meow 接入的 Android JNI、socket protect、TUN FD、配置装配及 NDK/CMake 只读参照；不照搬删监听/sniffer、公共 DNS fallback 或未等待退出的路径，不作为已验收实现 | MIT；[固定提交 LICENSE](https://github.com/meow-rs/meow-android/blob/4740012bf0efdb8bae8fbeeefaf4937dfa309f26/LICENSE)，SHA256 `9efa36b633149025223074bc471560d9aec5111d31a874d133cb1e1eec38f59e`；实际采用桥接另保原始署名与许可 |
| [dalek-cryptography/subtle](https://github.com/dalek-cryptography/subtle) | `subtle 2.6.1` / `13c2bddecc57b384dee18652358fb23172facb8a2c51ccc10d74c157bdea3292` | Meow-native API token 使用 constant-time equality；直接采用，已在成功 host 13 例图中验证。 | BSD-3-Clause；成功桥接 Cargo.lock checksum 对应 固定 crates.io 源码包；crate `LICENSE` SHA-256 与成功 `meow-native/Cargo.lock` 核对；原始声明保留于 registry 源包。 |
| [tokio-rs/tracing](https://github.com/tokio-rs/tracing) | `tracing 0.1.44` / `63e71662fa4b2a2c3a26f570f037eb95bb1f85397f3cd8076caed2f026a6d100`；`tracing-subscriber 0.3.23` / `cb7f578e5945fb242538965c2d0b04418d38ec25c79d160cd279bf0731c8d319` | Meow-native API 请求日志层和 subscriber 配置；同仓库两个直接 crate 一行登记，已在成功 host 13 例图中验证。 | MIT；各 固定 crates.io 源码包 Cargo.toml metadata 与 LICENSE hash 与成功 `meow-native/Cargo.lock` 核对；原始声明保留于 registry 源包。 |
| [rust-lang/futures-rs](https://github.com/rust-lang/futures-rs) | `futures 0.3.34` / `9a31d2a3fbaaeb2af2368bbdd904aa8e812d3c04a1ee10d3171f52d556e5d0a3` | `api_lifecycle` integration test 使用 `futures::StreamExt`；dev-dependency，成功 host 13 例图使用。 | MIT OR Apache-2.0；固定源码包 Cargo.toml metadata 与两份 LICENSE 文件 hash 与成功 `meow-native/Cargo.lock` 核对；原始声明保留于 registry 源包。 |
| [snapview/tokio-tungstenite](https://github.com/snapview/tokio-tungstenite) | `tokio-tungstenite 0.29.0` / `8f72a05e828585856dacd553fba484c242c46e391fb0e58917c942ee9202915c` | API lifecycle integration test 连接 WebSocket；dev-dependency，成功 host 13 例图使用。 | MIT；固定源码包 Cargo.toml metadata 与 LICENSE hash 与成功 `meow-native/Cargo.lock` 核对；原始声明保留于 registry 源包。 |
| [hyperium/hyper](https://github.com/hyperium/hyper) | `hyper 1.12.0`；registry checksum `2c3e324da4c95177d6291d4c8730197c0d1822f8a9766814a4a44fa5ab797c9c`（Cargo.lock） | Meow-native 的本地 HTTP/1 server connection；直接依赖并复用既有 API，未复制其实现。 | MIT；官方 crates.io 精确版本元数据仓库/许可字段，`apps/aerove-lite/meow-native/Cargo.lock` 的 registry checksum 对应原始源码包 |
| [hyperium/hyper-util](https://github.com/hyperium/hyper-util) | `hyper-util 0.1.21`；registry checksum `ddc03d96684f9226b8a787cdb71488417b53ab5ea8fdb1dac946cb9431cc8bff`（Cargo.lock） | Meow-native 将 Tokio listener/service 桥接到 Hyper；直接依赖并复用既有 API，未复制其实现。 | MIT；官方 crates.io 精确版本元数据仓库/许可字段，`apps/aerove-lite/meow-native/Cargo.lock` 的 registry checksum 对应原始源码包 |
| [jni-rs/jni-rs](https://github.com/jni-rs/jni-rs) | `jni 0.22.4`；registry checksum `5efd9a482cf3a427f00d6b35f14332adc7902ce91efb778580e180ff90fa3498`（Cargo.lock） | Cargo.toml 中声明的直接 JNI 依赖；该五例图的 lib.rs/runtime.rs 未发现 JNI API 调用，暂不声称实际桥接已使用。 | MIT OR Apache-2.0；官方 crates.io 精确版本元数据仓库/许可字段，`apps/aerove-lite/meow-native/Cargo.lock` 的 registry checksum 对应原始源码包 |
| [Amanieu/parking_lot](https://github.com/Amanieu/parking_lot) | `parking_lot 0.12.5`；registry checksum `93857453250e3077bd71ff98b6a65ea6621a19bb0f559a85248955ac12c45a1a`（Cargo.lock） | Meow-native runtime owner/session 的同步 Mutex；直接依赖并复用既有 API，未复制其实现。 | MIT OR Apache-2.0；官方 crates.io 精确版本元数据仓库/许可字段，`apps/aerove-lite/meow-native/Cargo.lock` 的 registry checksum 对应原始源码包 |
| [serde-rs/serde](https://github.com/serde-rs/serde) | `serde 1.0.229`；registry checksum `4148590afebada386688f18773da617792bf2ef03ffc1e4cbd2b1d45b023e0ba`（Cargo.lock） | Meow-native runtime 响应结构使用 Serialize；直接依赖并复用既有 API，未复制其实现。 | MIT OR Apache-2.0；官方 crates.io 精确版本元数据仓库/许可字段，`apps/aerove-lite/meow-native/Cargo.lock` 的 registry checksum 对应原始源码包 |
| [serde-rs/json](https://github.com/serde-rs/json) | `serde_json 1.0.154`；registry checksum `e7e9cc8b1b85264074fbcc02a88680c4096b1e47df8f739dceb03bf482f04bd6`（Cargo.lock） | 直接 JSON 依赖；代表链测试实际解码私有运行快照，响应由 Axum/Serde 序列化；未复制其实现。 | MIT OR Apache-2.0；官方 crates.io 精确版本元数据仓库/许可字段，`apps/aerove-lite/meow-native/Cargo.lock` 的 registry checksum 对应原始源码包 |
| [dtolnay/serde-yaml](https://github.com/dtolnay/serde-yaml) | `serde_yaml 0.9.34+deprecated`；registry checksum `6a8b1a1a2ebf674015cc02edccce75287f1a0130d394307b36743c2f5d504b47`（Cargo.lock） | Meow-native runtime 解析 YAML 配置并检查字段；直接依赖并复用既有 API，版本原样记录其 +deprecated 后缀。 | MIT OR Apache-2.0；官方 crates.io 精确版本元数据仓库/许可字段，`apps/aerove-lite/meow-native/Cargo.lock` 的 registry checksum 对应原始源码包 |
| [MFSGA/AChimera](https://github.com/MFSGA/AChimera) | 只读查阅公开项目；无产品依赖 | Android Rust代理客户端形态与限制调查，未采用其实现 | GPL-3.0；`docs/research/android-core-decision.md` |
| [chrisbanes/haze](https://github.com/chrisbanes/haze) | `2.0.1` / `347c67a1f190b71f439f3b9ca7ffb326d0044629` | 液态玻璃库对比；检查Glass、形状和Shader API，未进入产品依赖或复制代码 | Apache-2.0；`docs/research/rendering/haze.md`、`docs/research/rendering/library-selection.md` |
| [belinwu/cloudy](https://github.com/belinwu/cloudy) | `0.7.1`；只读源码锚点 `e3ffd8e957b2a29832c287a95665ecc540e9c7eb` | 玻璃渲染与多层捕获路径比较，未进入产品依赖或复制代码 | Apache-2.0；`docs/research/rendering/native-alternatives.md`、`library-selection.md` |
| [BuildItCode/LiquidGlass](https://github.com/BuildItCode/LiquidGlass) | `v0.8.2`；只读源码锚点 `3e30a2300ce471f8b44497df87b93beb24fc6f5e` | Android玻璃捕获层级与GPU背景采样比较；未采用，完整AAR/许可证据在原评估中待核 | 许可证尚未由该评估证实；`docs/research/rendering/library-selection.md`、`native-alternatives.md` |
| [Dimezis/BlurView](https://github.com/Dimezis/BlurView) | 未固定release/commit | 仅作底层View模糊能力比较，未采用，也未据此复制代码 | Apache-2.0（上游）；`docs/research/rendering/library-selection.md` |
| [actions/checkout](https://github.com/actions/checkout) | `11d5960a326750d5838078e36cf38b85af677262` | 两版 CI 拉取工作区源码。作为 GitHub Action 按固定 SHA 引用。 | MIT；[固定提交 LICENSE](https://github.com/actions/checkout/blob/11d5960a326750d5838078e36cf38b85af677262/LICENSE) |
| [actions/cache](https://github.com/actions/cache) | `0057852bfaa89a56745cba8c7296529d2fc39830`、`caa296126883cff596d87d8935842f9db880ef25` | 标准版构建及字体 fixture 缓存；本地 Flutter action 的 SDK/pub 缓存。两固定提交均由本轮 CI action `uses` 引用。 | MIT；[0057852 LICENSE](https://github.com/actions/cache/blob/0057852bfaa89a56745cba8c7296529d2fc39830/LICENSE)、[caa2961 LICENSE](https://github.com/actions/cache/blob/caa296126883cff596d87d8935842f9db880ef25/LICENSE) |
| [actions/download-artifact](https://github.com/actions/download-artifact) | `d3f86a106a0bac45b974a628896c90dbdf5c8093` | 标准版跨 job 取回同一运行的已验收构建产物。按固定 SHA 引用。 | MIT；[固定提交 LICENSE](https://github.com/actions/download-artifact/blob/d3f86a106a0bac45b974a628896c90dbdf5c8093/LICENSE) |
| [actions/setup-go](https://github.com/actions/setup-go) | `40f1582b2485089dde7abd97c1529aa768e1baff` | 两版构建 Go 辅助程序时安装 Go。按固定 SHA 引用。 | MIT；[固定提交 LICENSE](https://github.com/actions/setup-go/blob/40f1582b2485089dde7abd97c1529aa768e1baff/LICENSE) |
| [actions/setup-java](https://github.com/actions/setup-java) | `cf277c60eb25467037889841efdb72551f06f6c3` | 两版 Android 构建与验证环境安装 Java。按固定 SHA 引用。 | MIT；[固定提交 LICENSE](https://github.com/actions/setup-java/blob/cf277c60eb25467037889841efdb72551f06f6c3/LICENSE) |
| [actions/upload-artifact](https://github.com/actions/upload-artifact) | `ea165f8d65b6e75b540449e92b4886f43607fa02` | 两版 CI 上传构建收据、APK 和验证证据。按固定 SHA 引用。 | MIT；[固定提交 LICENSE](https://github.com/actions/upload-artifact/blob/ea165f8d65b6e75b540449e92b4886f43607fa02/LICENSE) |
| [android-actions/setup-android](https://github.com/android-actions/setup-android) | `9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407` | Lite CI 安装 Android SDK 环境。按固定 SHA 引用。 | MIT；[固定提交 LICENSE](https://github.com/android-actions/setup-android/blob/9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407/LICENSE) |
| [dtolnay/rust-toolchain](https://github.com/dtolnay/rust-toolchain) | `b725eda525362e7cf34af9a7243cac1e3f01a7ec` | 两版 CI 配置固定 Rust 工具链。按固定 SHA 引用。 | MIT；[固定提交 LICENSE](https://github.com/dtolnay/rust-toolchain/blob/b725eda525362e7cf34af9a7243cac1e3f01a7ec/LICENSE) |
| [gradle/actions](https://github.com/gradle/actions) | `ed408507eac070d1f99cc633dbcf757c94c7933a` | Lite Android CI 配置 Gradle 与缓存。按固定 SHA 引用。 | MIT；[固定提交 LICENSE](https://github.com/gradle/actions/blob/ed408507eac070d1f99cc633dbcf757c94c7933a/LICENSE) |
| [nttld/setup-ndk](https://github.com/nttld/setup-ndk) | `ed92fe6cadad69be94a966a7ee3271275e62f779` | 标准版 Android 构建安装 NDK。按固定 SHA 引用。 | MIT；[固定提交 LICENSE](https://github.com/nttld/setup-ndk/blob/ed92fe6cadad69be94a966a7ee3271275e62f779/LICENSE) |
| [reactivecircus/android-emulator-runner](https://github.com/ReactiveCircus/android-emulator-runner) | `a421e43855164a8197daf9d8d40fe71c6996bb0d` | Lite CI 在 Android emulator 上运行 API 33 应用/仪器验收。按固定 SHA 引用。 | Apache-2.0；[固定提交 LICENSE](https://github.com/ReactiveCircus/android-emulator-runner/blob/a421e43855164a8197daf9d8d40fe71c6996bb0d/LICENSE) |
| [subosito/flutter-action](https://github.com/subosito/flutter-action) | `1a449444c387b1966244ae4d4f8c696479add0b2` | 标准版本地 Flutter setup action 由该固定上游源码适配而来；保留 setup.sh、适配 action.yaml 并 pin 其嵌套 cache。根据现有 SOURCES.json 及本轮对照记录来源。 | MIT；[固定提交 LICENSE](https://github.com/subosito/flutter-action/blob/1a449444c387b1966244ae4d4f8c696479add0b2/LICENSE)，本地 `.github/actions/flutter/SOURCES.json` 记录来源 SHA256 `329009b59300a124af69c29c9ad19d38079207d9098c76fe0b51dc79a76a2e9a` |
| [Swatinem/rust-cache](https://github.com/Swatinem/rust-cache) | `d1527c8f67ef9af357290475d4af4b0fab8f12e0` | Lite CI Rust 编译缓存。按固定 SHA 引用。 | LGPL-3.0；[固定提交 LICENSE](https://github.com/Swatinem/rust-cache/blob/d1527c8f67ef9af357290475d4af4b0fab8f12e0/LICENSE) |

| [rust-lang/rust-bindgen](https://github.com/rust-lang/rust-bindgen) | `bindgen 0.72.1` / Cargo.lock checksum `993776b509cfb49c750f11b8f07a46fa23e0a1386ffc01fb1e7d343efc387895` | QuickJS Android 绑定的官方 target-specific clang 参数与 shlex 行为只读参考；未复制实现，Android 实际生成仍须 CI 验证 | BSD-3-Clause；已成功 host 图的 metadata/lock 核对版本、仓库与许可；[固定版本源码](https://github.com/rust-lang/rust-bindgen/blob/v0.72.1/bindgen/lib.rs) |
| [KyleMayes/clang-sys](https://github.com/KyleMayes/clang-sys) | `clang-sys 1.9.1` / Cargo.lock checksum `157a8ba7b480713b56f4c09fd13fc3e0a22a5dfab8097ba61cbc5feef950788a` | 只读确认 `LIBCLANG_PATH` 可指向实际共享库文件；本项目探测代码不复制其实现，真实加载结果单独留证 | Apache-2.0；已成功 host 图的 metadata/lock 核对版本、仓库与许可；[固定版本源码](https://github.com/KyleMayes/clang-sys/blob/v1.9.1/build/common.rs) |
| [madeye/lwip](https://github.com/madeye/lwip) | Meow v0.26.1 vendoring 声明固定 `5f6f0dfe025c967157ff328d94efe488852d8755`，位于 Meow `727ddcd06f592e2f12c5222c4de6cce328df808c` 的 `crates/meow-lwip` | Lite 候选 Android netstack 采用当前 Meow 内置单 owner 实现；不新增官方 Android 旧参考中的落后 Git 依赖；尚无 TUN/APK 验收 | Rust Cargo 声明 MIT/Apache-2.0；C `COPYING` 为 BSD-3-Clause，原始 Git 字节 SHA256 `ef4aac92e05e87cd1cdc140870ed52206ba03d4a7fe46c1e11d7ffa6c87d252b`；该 vendored 子树无单独 MIT/Apache 文本，不虚构文件哈希；保留原版权与 COPYING |

### 来源登记边界

版本、commit、许可证分别按表中对应证据记录；缺失信息不从仓库名或最新上游状态推定。`Cargo.lock`/Maven/Pub锁定值标识依赖时，若未核实源码commit，版本列仅陈述已固定的发行版本与锁校验值。`russh`、`shadowquic`、`boring-noise`等peer测试读取上游API但未复制其实现；只读调查也与实际运行依赖分开。Cargo、Dart SDK和网络库的只读研究来源不代表被复制进产品。

本名单不展开所有Cargo/Pub/Maven传递依赖，也不扫描缓存、class或二进制。订阅数据、用户私密URL及query token不属于来源登记内容。若新功能开发实际打开并采用了额外GitHub实现，应在同一节增补仓库、已证revision、用途、采用方式、许可证与来源证据；只有搜索命中或未被采用的外部页面不应登记成已采用来源。
## 多个来源，独立交付

| 来源 | 职责 | 纳入规则 |
| --- | --- | --- |
| FlClash | 历史来源及可选择的客户端实现参考 | 按 Aerove 实际问题选择相关变更，保留来源和许可证，不自动整套合并 |
| MetaCubeX/mihomo | 标准版代理核心 | 直接核验官方已公开发布的稳定版本，固定源码提交，在本仓库维护可重放适配补丁，不等待 FlClash |
| meow-rs/meow-rs | Lite 选定代理核心（迁移中） | 固定公开版本、宿主接入与配置/协议/平台契约；真实验收前不冒充已发行 APK |
| ibigbug/clash-rs | Lite 历史核心检查点 | 保留源码、许可、测试和实测证据，不继续扩展或复用成 Meow 的验收结论 |
| Android 图形库与 Flutter | 两版呈现基础 | 固定依赖并保持共同视觉、交互、功能和刷新策略；框架差异不构成删减理由 |

产品版本、签名、更新地址、验收和发版由 Aerove 决定。上游发布是评估依赖更新的输入，不是跳过兼容性验证或自动发版的授权。借鉴代码仍须保留原始版权与许可证；独立维护不意味着重写历史或宣称全部代码原创。

GitHub 的 fork 网络关系单独处理，不是上述开发治理的前提。当前保留 `IvanSolis1989/Aerove` 地址、发布资产和升级入口。根据 [GitHub 官方脱离 fork 文档](https://docs.github.com/en/pull-requests/how-tos/work-with-forks/detaching-a-fork)，脱离会丢失 Star、议题、PR、Wiki 等仓库记录，且不可恢复关联；Git 提交历史会保留。不能把移除标记描述成无损操作，也不能直接删除重建仓库。真正执行前应列出保存、恢复和无法恢复的记录，以及发行地址、Secrets、工作流和客户端更新入口的影响，取得针对该具体结果的授权。

## 选择性参考

`.github/workflows/sync-upstream.yaml` 现在是 **Review upstream reference**，只接受手动触发，不再定时合并、推送 main 或触发 Android 构建。输入明确的公开 Release 标签后，它以只读权限核对发布状态、可下载资产和 peeled commit，只输出参考摘要。

只有标签而没有公开发行证据不等于已发布。GitHub Release 页面之外的官方发行渠道也须核对实际安装包与构建源码，不能把只有源码的标签归档当作发行。
暂停的整合不能因定时任务或仅有新标签自动恢复；须有用户当前明确授权与相应来源核验。

2026-10-22 用户已明确授权参考 FlClash `v0.9.2-pre.1`：官方频道实际分发安装包并指向
[成功构建 39748575294](https://github.com/chen08209/FlClash/actions/runs/39748575294)，对应固定源码 `7220961f7c5e494df831ca196ab53ef08a17e926`。
本次独立分支基于该真实来源审查；旧 `43d6bd78` 冻结工作不能冒充新目标。此专项授权不恢复自动合并，也不要求 Aerove 采用上游版本号。

确需借鉴代码时，先说明解决的 Aerove 问题与来源提交，选择最小相关改动，保留许可证与署名，进入独立功能分支审查和验证。整套合并上游不是默认更新方式。库与代理核心的更新同样要固定来源，经过本产品功能、配置和平台契约验证。

## 产品版本和血缘证据分离

- `pubspec.yaml` 的语义版本属于 Aerove。`tool/aerove_version.py release-plan` 生成显示版本、通道、Android versionCode 和 Release tag，不给新版本自动添加 `-X`。
- 标准版 Android versionCode 为 `2026102200 + 原实际构建 GITHUB_RUN_NUMBER`。该基数在仓库重建时用于跨过历史 `2026101849`；当前已发布稳定编号为 `2026102203`，不能再把 `2026102201` 当作可发布的新编号。后续构建必须高于该产品已发布的最高编号，具体见[独立版本规范](VERSIONING.md)。旧 `-X` 资产继续按旧编号基数校验，不改写历史包，也不改变包名或签名来绕过升级检查。
- `tool/upstream-version.json` 保留已纳入历史的 FlClash 来源，不是产品更新开关。发布仍验证其 source/tag 的祖先关系、原始来源中的 pubspec 与记录一致，避免伪造血缘；不要求当前产品版本等于历史来源，也不要求网络上最高标签已经整合。
- `tool/xclash_version.py` 保留旧版本与来源记录的解析能力，历史 `0.9.1-X+...` 资产不重写，也不会因新的产品版本规则而失效。

两产品的主/次/修订升级、预发布阶段及当前候选按[独立版本规范](VERSIONING.md)维护。
含预发布标识的产品版本进入 preview 通道，否则进入 stable；版本选择对应实际变更，不由上游标签触发。

## 构建一次，推广原产物

分支执行完整 verify、APK 构建和 Android 单元测试。精确同一提交快进进入 main 后，发布工作流查找同仓库、同工作流、当前 attempt 的完整成功构建，按唯一且未过期的 artifact ID 下载；命中后跳过重复编译，没有合格产物才构建。查询错误或下载/校验失败明确停止。

推广继续核对空生成补丁、精确 verified tree、真实源提交、原工作流与编号、产品版本与历史来源、两份文件哈希、ZIP CRC、ARM64 主库、包信息和签名。第三方 JNI 可以含其他 ABI，Flutter/app/Go/Rust 主库只允许 ARM64。校验原旧包时不传 `--pubspec`；独立产品包必须传发布源中的 `--pubspec`，以产品版本验证，不把历史上游版本当作产品版本。

`BUILD_INFO.json`、APK 和 SHA256SUMS 不因推广而改变；显示版本和 workflow 保留第一次实际构建的信息，发布运行另外记录。stable 可更新 latest，preview 标记为 prerelease 且不替换稳定 latest。

固定测试签名和 `com.follow.clash.dev` 保持已有升级边界。密钥继续来自现有仓库 secret，不能删除重建或换成临时签名。正式包默认最低 Android 7.0；原生视觉研究的 API 门槛不会悄悄提高生产 minSdk。

## 发版和文档

更新说明从实际构建源码的 `docs/aerove/CHANGELOG.md` 精确版本段或本轮新待发布段生成，既支持旧 `-X` 格式，也支持独立产品 SemVer 与构建号；旧说明、空说明和不匹配来源阻止发布。

发布完成后，正式下载 APK、BUILD_INFO 和 SHA256SUMS，核对与被推广原产物逐字节相同，以及 source/version/run/signature/CRC/ARM64/Release 状态和正文。验收后才归档 CHANGELOG，记录日本时区日期、源提交、原构建与发布链接。纯文档归档不触发重复发版。

仅修改发布流程和工具、且应用输入未变时，复用已有应用验证并补做对应工具及工作流检查，不为流程上线重复编译同一应用。不能由此跳过真实应用变更的验证，也不能把工具检查通过描述为新 APK 已验收。

完整架构方向、前端渲染和后端替换的验收边界见[独立架构](INDEPENDENCE.md)。
