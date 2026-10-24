# Aerove 独立维护与来源参考

文档 → 开发 → 开源来源与鸣谢

Aerove 独立管理产品需求、版本和发布。FlClash 是保留版权与历史记录的来源项目，也可以作为功能参考；其新标签、分支推进或版本号不会自动改写本产品，也不再阻止本产品发版。

## 本页导航

- [开源来源与鸣谢](#开源来源与鸣谢)：来源条目、许可证和登记范围。
- [多个来源，独立交付](#多个来源独立交付) 与 [选择性参考](#选择性参考)：历史来源与整合边界。
- [产品版本和血缘证据分离](#产品版本和血缘证据分离)：版本、构建及发版约束。

## 开源来源与鸣谢

本项目在功能开发前先检索可复用的 GitHub 实现；实际采用的依赖、复制适配代码、只读参照和仅评估未采用的候选分别记录。固定 commit 的来源以 commit 为准；只有 tag/版本和锁文件校验值时，明确记录现有证据，不推测完整 commit。该表覆盖已登记的实际来源，不是完整传递依赖许可证清单，也不代表所有历史调研均已穷尽。

以下按仓库归并重复来源，保留每项固定版本、用途、采用方式和许可证证据。清单包括实际采用和只读参照；候选计划不会表述为已执行实现。

### 来源条目

#### [chen08209/FlClash](https://github.com/chen08209/FlClash)

**固定版本 / revision：** 历史整合 `v0.9.2-pre.1` / `7220961f7c5e494df831ca196ab53ef08a17e926`；本轮只读参照 `8d4f9744948751f1273fe871e1d05276fac09b0e`

**用途与采用方式：** 标准版历史源码选择性整合；另只读核对 `lib/common/request.dart` 的 `latestRelease` / `checkForUpdate` 现有检查路径，未复制新算法或新增依赖

**许可证、署名与证据：** 保留历史版权与对应文件许可；`apps/aerove/core/mihomo-upstream.json` 中保留的双父与补丁来源记录；[固定源码 `lib/common/request.dart`](https://github.com/chen08209/FlClash/blob/8d4f9744948751f1273fe871e1d05276fac09b0e/lib/common/request.dart)、[GitHub Releases List API 文档](https://docs.github.com/en/rest/releases/releases#list-releases)

#### [MetaCubeX/mihomo](https://github.com/MetaCubeX/mihomo)

**固定版本 / revision：** `v1.19.33` / `3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8`

**用途与采用方式：** 标准版 Go 代理核心，来源代码及本项目适配

**许可证、署名与证据：** GPL-3.0；`apps/aerove/core/mihomo-upstream.json`、`apps/aerove/core/Clash.Meta/LICENSE`、该目录源码及其原始版权头

#### [MetaCubeX/http](https://github.com/MetaCubeX/http)

**固定版本 / revision：** `v0.1.8` / `3ffb994bb87614dd9f1fc40e036613b04b4a8ffd`

**用途与采用方式：** 标准版现有Go依赖；只读调查HTTP请求取消与拨号/连接清理链，未采用新的transport桥，不能据源码审查声称问题已修复

**许可证、署名与证据：** BSD-3-Clause；`apps/aerove/core/Clash.Meta/go.mod`、[固定源码](https://github.com/MetaCubeX/http/tree/3ffb994bb87614dd9f1fc40e036613b04b4a8ffd)、[LICENSE](https://github.com/MetaCubeX/http/blob/3ffb994bb87614dd9f1fc40e036613b04b4a8ffd/LICENSE)

#### [MetaCubeX/tls](https://github.com/MetaCubeX/tls)

**固定版本 / revision：** `v0.1.9`（由现有Go模块锁定）

**用途与采用方式：** 标准版现有Go依赖；只读调查TLS握手取消和底层连接关闭路径，未引入新的TLS桥或更改现有请求行为

**许可证、署名与证据：** BSD-3-Clause；`apps/aerove/core/Clash.Meta/go.mod`、[v0.1.9 LICENSE](https://github.com/MetaCubeX/tls/blob/v0.1.9/LICENSE)

#### [ibigbug/clash-rs](https://github.com/ibigbug/clash-rs)

**固定版本 / revision：** `v0.10.10` / `36eec77463d1243ede287bf6b7197e38237948d5`

**用途与采用方式：** Lite 历史 Rust 核心检查点复制适配（用户已选 Meow，原源码和许可保留）；socket保护、移动端嵌入、日志路由、生命周期、provider校验等适配

**许可证、署名与证据：** 上游固定源码含 Apache-2.0 LICENSE；启用 `tuic` 时按固定源码 NOTICE 为 GPL-3.0；Aerove 整仓 GPL 许可保持不变；`apps/aerove-lite/native/NOTICE.md`、`native/licenses/clash-rs-NOTICE.md`、`native/upstream.patch`、`native/dependency-protect/manifest.json`；保留上游版权与NOTICE

#### [Watfaq-legacy/clash-rs](https://github.com/Watfaq-legacy/clash-rs)

**固定版本 / revision：** 固定源码提交 `36eec77463d1243ede287bf6b7197e38237948d5`（与上行核心提交相同；独立 fork 仓库）

**用途与采用方式：** Native Direct/WS 源码只读参照；未采用为第二代理核心

**许可证、署名与证据：** 固定源码 LICENSE 为 Apache-2.0；[固定提交 LICENSE](https://github.com/Watfaq-legacy/clash-rs/blob/36eec77463d1243ede287bf6b7197e38237948d5/LICENSE)；GitHub 仓库身份及 fork parent 由仓库元数据核实

#### [material-components/material-components-android](https://github.com/material-components/material-components-android)

**固定版本 / revision：** `1.13.0` / `84c0e6d6d493fcf176d2099683245b78afbeb78d`

**用途与采用方式：** Lite Material Color Utilities：20个Java类复制，包名迁移并按来源记录移除源码级Error Prone注解

**许可证、署名与证据：** Apache-2.0；`apps/aerove-lite/third_party/material-color-utilities/LICENSE`、`SOURCES.json`

#### [dlclark/regexp2](https://github.com/dlclark/regexp2)

**固定版本 / revision：** 生产依赖 `v1.12.0`（`apps/aerove-lite/regexp2/dependencies.json`）；时钟退出检索另核固定提交 `936b3813c162928c10e57cb04c9e3e7620bb7daf`

**用途与采用方式：** Lite regexp2 Go辅助代码纳入/适配；后者仅作退出逻辑参考，未直接采用其代码。上游仍以 sleep 等待退出，本项目增加按代唤醒与 join 及保留原门槛的回归，不宣称为上游修复

**许可证、署名与证据：** MIT；`apps/aerove-lite/regexp2/licenses/github.com_dlclark_regexp2/LICENSE`；本项目改动与检索记录见 `regexp2/patches/fastclock.go.patch`、`clock-stop-source.json`

#### [androidx/androidx](https://github.com/androidx/androidx)

**固定版本 / revision：** `851a99e20723278101bfa110cc209021ba676748`

**用途与采用方式：** Lite资源库 Compose 状态订阅只读参考 `StateFlow.collectAsStateWithLifecycle` 默认 STARTED 和 `repeatOnLifecycle` 行为；仪表盘状态卡另参考 `AlignmentLine` 的父级继承与子项位置偏移，在同次测量中判断紧凑指标与真实前景是否碰撞。复用既有 Compose / lifecycle-runtime-compose 依赖，未复制实现、未新增依赖；实际 Android 布局仍须验收

同一固定提交另只读核对 `GraphicsLayer.kt`、`LayoutNodeDrawScope.kt` 与 `AndroidRenderEffect.android.kt` 的离屏记录、`DstIn` 隔离及 RenderEffect 输入桥接，用于 Lite 极光前景蒙版修正；未复制上游实现或增加依赖。

**许可证、署名与证据：** Apache-2.0；保留 Android Open Source Project 署名；[固定 `FlowExt.kt`](https://github.com/androidx/androidx/blob/851a99e20723278101bfa110cc209021ba676748/lifecycle/lifecycle-runtime-compose/src/commonMain/kotlin/androidx/lifecycle/compose/FlowExt.kt)、[固定 `AlignmentLine.kt`](https://github.com/androidx/androidx/blob/851a99e20723278101bfa110cc209021ba676748/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/layout/AlignmentLine.kt)；本项目入口为 `GlobalResourceLibraryScreen.kt` 和 `OverviewScreen.kt`

#### [google/skia](https://github.com/google/skia)

**固定版本 / revision：** `chrome/m106` / `4100191c5888bfcdf3f48d6ec04e4adef458f34c`

**用途与采用方式：** 只读研究 `SkRuntimeImageFilter.cpp` 和 `SkSpecialImage.cpp` 的 desired-output、输入子集及 shader 采样契约，用于定位 Lite 双高图集跨区读取字形 alpha 的风险。采用同坐标取色与独立字形蒙版；未复制 Skia 实现、未新增依赖。该参考 revision 不作为实际 Android 设备 Skia 版本或已逐层实测裁剪机制的证明。

**许可证、署名与证据：** BSD-3-Clause，保留原 Skia 版权；[固定 RuntimeImageFilter 源码](https://github.com/google/skia/blob/4100191c5888bfcdf3f48d6ec04e4adef458f34c/src/effects/imagefilters/SkRuntimeImageFilter.cpp)、[固定 SpecialImage 源码](https://github.com/google/skia/blob/4100191c5888bfcdf3f48d6ec04e4adef458f34c/src/core/SkSpecialImage.cpp)。修正后的像素结果由本项目实际 Android 验收确认。

#### [MetaCubeX/age](https://github.com/MetaCubeX/age)

**固定版本 / revision：** `v0.0.0-20260603010618-28d156b4ea78`（伪版本后缀仅证明短修订，不补写完整SHA）

**用途与采用方式：** regexp2客户端辅助包中的Age解密bridge；实际调用Go age实现

**许可证、署名与证据：** 以随包许可文本为准；`apps/aerove-lite/regexp2/dependencies.json`、`regexp2/licenses/github.com_metacubex_age/LICENSE`、本轮 `apps/aerove-lite/regexp2/agebridge/`、`regexp2/dependencies.json`

#### [MetaCubeX/hkdf](https://github.com/MetaCubeX/hkdf)

**固定版本 / revision：** `v0.1.0`（依赖清单版本）

**用途与采用方式：** Age加密辅助依赖，作为现成依赖随辅助包使用；未登记为复制其源码

**许可证、署名与证据：** 以 `regexp2/licenses/github.com_metacubex_hkdf/LICENSE` 为准；`regexp2/dependencies.json`

#### [MetaCubeX/hpke](https://github.com/MetaCubeX/hpke)

**固定版本 / revision：** `v0.1.0`（依赖清单版本）

**用途与采用方式：** Age加密辅助依赖，作为现成依赖随辅助包使用

**许可证、署名与证据：** 以 `regexp2/licenses/github.com_metacubex_hpke/LICENSE` 为准；`regexp2/dependencies.json`

#### [MetaCubeX/mlkem](https://github.com/MetaCubeX/mlkem)

**固定版本 / revision：** `v0.1.0`（依赖清单版本）

**用途与采用方式：** Age加密辅助依赖，作为现成依赖随辅助包使用

**许可证、署名与证据：** 以 `regexp2/licenses/github.com_metacubex_mlkem/LICENSE` 为准；`regexp2/dependencies.json`

#### [golang/crypto](https://github.com/golang/crypto)

**固定版本 / revision：** `v0.57.0`（依赖清单版本）

**用途与采用方式：** regexp2/Age辅助包所用Go密码学支持依赖

**许可证、署名与证据：** BSD-3-Clause；`regexp2/licenses/golang.org_x_crypto/LICENSE`、`PATENTS`、`dependencies.json`

#### [golang/sys](https://github.com/golang/sys)

**固定版本 / revision：** `v0.48.0`（依赖清单版本）

**用途与采用方式：** regexp2/Age辅助包所用Go系统接口依赖

**许可证、署名与证据：** BSD-3-Clause；`regexp2/licenses/golang.org_x_sys/LICENSE`、`PATENTS`、`dependencies.json`

#### [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)

**固定版本 / revision：** `io.github.kyant0:backdrop:2.0.1` / `65ab177e90e5c1d8c62e70cf7755841982da65f6`

**用途与采用方式：** Lite材质/背景呈现所用Backdrop实现，直接依赖

**许可证、署名与证据：** Apache-2.0；`apps/aerove-lite/app/build.gradle.kts`、`docs/research/rendering/backdrop.md`、`app/src/main/assets/optics/NOTICE.md`

#### [Kotlin/kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines)

**固定版本 / revision：** `1.10.2`，release/tag短commit `5f89004`

**用途与采用方式：** 读取 `CoroutineScope.kt`、`Job.kt`、`Builders.common.kt`，复用结构化并发取消语义，并确认 `withContext` 的prompt cancellation可能在IO提交后丢失返回值；导入/文件写入沿用项目既有 `onCommitted` 回执处理；未复制实现代码、未新增依赖

**许可证、署名与证据：** Apache-2.0；`apps/aerove-lite/app/build.gradle.kts`、[CoroutineScope.kt](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/CoroutineScope.kt)、[Job.kt](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/Job.kt)、[Builders.common.kt](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/Builders.common.kt)

#### [BigBadaboom/androidsvg](https://github.com/BigBadaboom/androidsvg)

**固定版本 / revision：** Maven `com.caverock:androidsvg-aar:1.4`；AAR SHA256 `02a5b08a2b35d2d58eb2eaca9d84ac00fb341da725fdbd653ea3ed130437e95a`，无源码commit记录

**用途与采用方式：** Lite代理组图标SVG渲染，直接依赖；AAR源包核验

**许可证、署名与证据：** Apache-2.0；`apps/aerove-lite/app/build.gradle.kts`

#### [stleary/JSON-java](https://github.com/stleary/JSON-java)

**固定版本 / revision：** `20250517`

**用途与采用方式：** Lite现有JSON依赖API只读参考：核对 `JSONObject` 键与类型访问语义，沿用项目现有SDK/解析路径；未复制代码或新增依赖

**许可证、署名与证据：** Public Domain；[固定版本 LICENSE](https://github.com/stleary/JSON-java/blob/20250517/LICENSE)、[JSONObject.java](https://github.com/stleary/JSON-java/blob/20250517/src/main/java/org/json/JSONObject.java)、`apps/aerove-lite/app/build.gradle.kts`

#### [square/okhttp](https://github.com/square/okhttp)

**固定版本 / revision：** `4.12.0`

**用途与采用方式：** Lite HTTP请求与MockWebServer测试现存依赖；本轮结构化并发变更没有参照Dispatcher实现，也未改客户端池/并发策略

**许可证、署名与证据：** Apache-2.0；`apps/aerove-lite/app/build.gradle.kts`。本轮尝试打开tagged Dispatcher源码时工具重定向到未确认owner页面，该页面未作依据且未取用代码

#### [snakeyaml/snakeyaml](https://github.com/snakeyaml/snakeyaml)

**固定版本 / revision：** `2.4`

**用途与采用方式：** Lite配置YAML解析，直接依赖

**许可证、署名与证据：** Apache-2.0；`apps/aerove-lite/app/build.gradle.kts`

#### [zxing/zxing](https://github.com/zxing/zxing)

**固定版本 / revision：** `3.5.3`

**用途与采用方式：** Lite二维码读写，直接依赖

**许可证、署名与证据：** Apache-2.0；`apps/aerove-lite/app/build.gradle.kts`

#### [Flutter](https://github.com/flutter/flutter)

**固定版本 / revision：** SDK `3.47.6` / framework `5fc346839b5d0eef006ed8404392afb4dfae428d`

**用途与采用方式：** 标准版Flutter框架；Lite optics几何与AGSL shader适配源自Flutter Impeller superellipse；字体测试fixture保留Roboto许可，生产不新增捆绑字体；字体策略只读参考SDK Android默认字体行为

**许可证、署名与证据：** BSD-3-Clause（Flutter）；字体按原 `roboto_license.txt`；`apps/aerove-lite/app/src/main/assets/optics/NOTICE.md`、`FLUTTER_LICENSE`、`apps/aerove/test/fixtures/fonts/SOURCE.json`

#### [fzyzcjy/flutter_rust_bridge](https://github.com/fzyzcjy/flutter_rust_bridge)

**固定版本 / revision：** `5463b190636ca9edf705be0b168471875e675ebf`

**用途与采用方式：** CI/Dart集成只读参考：核对 `RustLib.init()` 初始化方式；未复制代码、未新增依赖

**许可证、署名与证据：** MIT；[固定源码](https://github.com/fzyzcjy/flutter_rust_bridge/blob/5463b190636ca9edf705be0b168471875e675ebf/frb_example/flutter_via_integrate_native_assets/lib/main.dart)、[LICENSE](https://github.com/fzyzcjy/flutter_rust_bridge/blob/5463b190636ca9edf705be0b168471875e675ebf/LICENSE)；`.github/workflows/xclash-android.yaml`

#### [dart-lang/sdk](https://github.com/dart-lang/sdk)

**固定版本 / revision：** Dart `3.13.5` / `04bcd1036cdc799ac6564988f159ee454d42c822`

**用途与采用方式：** TLS、字体、Socket API及 `Isolate.kill` 生命周期只读核验；kill不保证挂起socket立即EOF/FD清理，因此未以隔离区退出替代逐请求取消

**许可证、署名与证据：** BSD-3-Clause；[Isolate.kill API](https://api.dart.dev/dart-isolate/Isolate/kill.html)、固定Dart SDK版本/source

#### [rust-lang/cargo](https://github.com/rust-lang/cargo)

**固定版本 / revision：** `956fdaeb068d791a39dec140f2efc9ba48c59729`

**用途与采用方式：** 只读研究Cargo官方source/选包语义，不复制Cargo实现，不构成应用运行时依赖

**许可证、署名与证据：** Apache-2.0 OR MIT；本轮来源检索记录

#### [spongebob888/shadowquic](https://github.com/spongebob888/shadowquic)

**固定版本 / revision：** `0.3.13`；源码crate在上游clash-rs仓库锁定版本；本次未另记完整commit

**用途与采用方式：** Lite核心的真实上游依赖及适配，另只读核对官方JLS/QUIC server、SQConnect API编写loopback peer；peer仍是测试验证

**许可证、署名与证据：** MIT；`apps/aerove-lite/native/Cargo.lock`、`native/upstream/shadowquic_peer_tests.rs`，核心锁源 `apps/aerove-lite/native/.upstream/shadowquic/Cargo.toml`

#### [spongebob888/rustls-jls](https://github.com/spongebob888/rustls-jls)

**固定版本 / revision：** `1.3.7`，crate SHA256 `e7b8b5e6fb5b6c65cc8b198609185aa0668d80df151ee4928cf978413bec0bed`；Cargo.toml.repository确认仓库，未声称此为上游Git commit

**用途与采用方式：** Lite核心JLS/TLS依赖与适配；只读检查loopback peer涉及的JLS认证/回退语义，未复制上游实现

**许可证、署名与证据：** Apache-2.0 OR ISC OR MIT；`apps/aerove-lite/native/Cargo.lock`及原crate许可证

#### [quinn-rs/quinn](https://github.com/quinn-rs/quinn)

**固定版本 / revision：** `quinn-proto-jls 0.3.8` SHA256 `cfff8bfe6190cc5e06e722680afbe1ec5cf3c6e8b62d026b1551bb68c63f0fb3`；`quinn-jls 0.3.8` SHA256 `06575428b476c2672be58db56c48d517c2b469b3083eeb9f51dc4f61de541be4`；Cargo.toml.repository字段确认仓库，不宣称精确Git commit

**用途与采用方式：** Lite核心QUIC依赖/适配；只读检查loopback peer所需的JLS认证与fallback API，未复制上游实现

**许可证、署名与证据：** MIT OR Apache-2.0；`apps/aerove-lite/native/Cargo.lock`及原crate许可证

#### [warp-tech/russh](https://github.com/warp-tech/russh)

**固定版本 / revision：** `0.61.1`，crate SHA256 `f67013f080c226e5a34db1c71f2567f44d95a6300005bb6cd4e2c8fe3c326d1b`

**用途与采用方式：** Lite核心的真实SSH依赖/适配；另只读读取SSH server API与官方loopback echo示例编写测试peer，未复制上游实现或示例

**许可证、署名与证据：** Apache-2.0；`apps/aerove-lite/native/Cargo.lock`、`native/upstream/ssh_peer_tests.rs`

#### [Watfaq/boring-noise](https://github.com/Watfaq/boring-noise)

**固定版本 / revision：** `9ddf1b54d0c9679e7bc50e9151f72d526190db1a`

**用途与采用方式：** Lite核心的WireGuard Noise实现依赖及适配；测试peer另读取 `Tunn` API，不复制上游实现

**许可证、署名与证据：** MIT；固定revision源码包原始LICENSE保留于受控依赖快照，`apps/aerove-lite/native/Cargo.lock`、`native/upstream/wireguard_peer_tests.rs`

#### [tailscale/tailscale-rs](https://github.com/tailscale/tailscale-rs)

**固定版本 / revision：** `0.6.1`；crate源码通过依赖保护快照固定，源码归档SHA见 `native/dependency-protect/manifest.json`

**用途与采用方式：** Lite Tailscale/DERP族Rust依赖来源；`tailscale`、`ts_control`、`ts_derp`等均来自同一仓库和固定版本。manifest列出原始crate、hash及本地补丁

**许可证、署名与证据：** BSD-3-Clause；`apps/aerove-lite/native/dependency-protect/manifest.json`、对应vendor包LICENSE

#### [DelSkayn/rquickjs](https://github.com/DelSkayn/rquickjs)

**固定版本 / revision：** `rquickjs 0.12.2`，Cargo.lock checksum `4e04e4eedfb060b503b5f0a2644abb890b0b3620d3fb674f9455f230014964e4`

**用途与采用方式：** 旧 Lite JS API 及 Meow 桥接的 QuickJS Runtime/Context/Function 直接依赖；Meow 保留原脚本模块与六项用例，成功 host 图已验证

**许可证、署名与证据：** MIT；`apps/aerove-lite/native/Cargo.toml`、`Cargo.lock`；新桥接 `apps/aerove-lite/meow-native/{Cargo.toml,Cargo.lock,src/script_engine.rs}`

#### [Itsusinn/tuic](https://github.com/Itsusinn/tuic)

**固定版本 / revision：** `v1.8.5`

**用途与采用方式：** Lite clash-rs审计中只读调查TUIC实现和协议行为；与clash-rs中实际启用的TUIC依赖区分，不复制该仓库代码

**许可证、署名与证据：** GPL-3.0；`docs/aerove-lite/core/clash-rs-audit.md`

#### [material-foundation/material-color-utilities](https://github.com/material-foundation/material-color-utilities)

**固定版本 / revision：** `0.13.0` / pub.lock SHA256 `9c337007e82b1889149c82ed242ed1cb24a66044e30979c44912381e9be4c48b`

**用途与采用方式：** Standard Flutter Material色彩依赖；保留pub锁定

**许可证、署名与证据：** Apache-2.0；`apps/aerove/pubspec.lock`

#### [dart-lang/http](https://github.com/dart-lang/http)

**固定版本 / revision：** 搜索结果commit `3b504cf09cb70ae4071d4c2549c903aad5cf4bc4`；Cronet `cronet_http 1.10.0`

**用途与采用方式：** 实际审查Cronet取消、代理、TLS与平台接口后排除为完整替换；只读检查源码和公开API，不构成采用或修复完成声明

**许可证、署名与证据：** BSD-3-Clause；[固定源码](https://github.com/dart-lang/http/tree/3b504cf09cb70ae4071d4c2549c903aad5cf4bc4)，SDK/API评估记录

#### [chen08209/Clash.Meta](https://github.com/chen08209/Clash.Meta)

**固定版本 / revision：** `8380d0e7462f061a964323e9bf3129a236fcc5d5`

**用途与采用方式：** FlClash历史来源体系中的适配源码参照；审计 mihomo DNS配置行为及保留的API适配来源，不作为当前核心主线

**许可证、署名与证据：** GPL-3.0；`docs/aerove-lite/core/clash-rs-audit.md`、`apps/aerove/core/mihomo-upstream.json`

#### [Kyant0/Shapes](https://github.com/Kyant0/Shapes)

**固定版本 / revision：** `1.2.1` / `ad83e4ad9ca6219cb0091fdc75d9b349865bf12b`

**用途与采用方式：** Backdrop配套形状库，Lite直接依赖；此前登记的 `Kyant0/AndroidShapes` 使用相同版本和固定revision，按同一来源别名归并

**许可证、署名与证据：** Apache-2.0；Gradle依赖、`docs/research/rendering/backdrop.md`

#### [tokio-rs/tokio](https://github.com/tokio-rs/tokio)

**固定版本 / revision：** `1.53.1`（Cargo.lock固定registry crate校验值；tag `tokio-1.53.1`）；Meow 桥接成功锁另含 `tokio 1.54.0` / `a0c168a474b95bfecfe908a65d4bdffc523144b649a74c09c286afb35d53e0bc`、`tokio-util 0.7.20` / `e464cf451ba96ebfc6f9b6542f17ee8b8956e33f1e40d9690624e59d7a7f8a4b`

**用途与采用方式：** 旧 Lite 异步运行时及广播重入只读源码参照；新 `meow-native` 直接采用 runtime、取消和任务治理 API，未复制 Tokio 实现

**许可证、署名与证据：** MIT；`apps/aerove-lite/native/Cargo.toml`、`Cargo.lock`、[固定版本 LICENSE](https://github.com/tokio-rs/tokio/blob/tokio-1.53.1/LICENSE)、[broadcast.rs](https://docs.rs/tokio/1.53.1/src/tokio/sync/broadcast.rs.html)；新图原始包信息按 crates.io 精确版本 metadata 与桥接 Cargo.lock 核实（未取得 Tokio 新版源码包）

#### [tokio-rs/axum](https://github.com/tokio-rs/axum)

**固定版本 / revision：** `axum-v0.8.9`；`axum 0.8.9` registry checksum `31b698c5f9a010f6573133b09e0de5408834d0c82f8d7475a89fc1867a71cd90`

**用途与采用方式：** 本轮只读核对 `ws.rs` 的 WebSocket upgrade/callback 顺序，以及 `routing/nest.md` 的末尾斜杠匹配语义，修正测试请求路径；未复制源码或新增依赖；Meow 桥接直接采用同版本路由/JSON/受控 HTTP API，旧参考信息保留

**许可证、署名与证据：** MIT；[`axum/Cargo.toml`](https://github.com/tokio-rs/axum/blob/axum-v0.8.9/axum/Cargo.toml) 声明许可，[固定版本 LICENSE](https://github.com/tokio-rs/axum/blob/axum-v0.8.9/axum/LICENSE)、[ws.rs](https://github.com/tokio-rs/axum/blob/axum-v0.8.9/axum/src/extract/ws.rs)、[nest.md](https://github.com/tokio-rs/axum/blob/axum-v0.8.9/axum/src/docs/routing/nest.md)

#### [rust-lang/rust](https://github.com/rust-lang/rust)

**固定版本 / revision：** `1.91.0`

**用途与采用方式：** 只读核验标准库 `RwLock::set` 在锁外析构旧值的行为；不复制标准库代码

**许可证、署名与证据：** MIT OR Apache-2.0；来源为固定版本 `library/std/src/sync/poison/rwlock.rs`，本轮native资源实现记录

#### [Smart-Config-Kit](https://github.com/IvanSolis1989/Smart-Config-Kit)

**固定版本 / revision：** `28b86e2e991cde8bc2fa71b874e807c622f2b096`；README 参考 `18a676345de86af38f511b8137add605e0a59588`；Lite Meow 联调基线 `89abb221a157acc98d6b52c85583e6b5a5b2742b`

**用途与采用方式：** 分类 Issue Forms、MiMo Flash 首轮与 Pro 追问、过滤/失败只读模式作为流程参考；该流程未复制规则/提示词或自动PR。另参考首页内容分层、下载导航与二级文档组织，按本产品事实重新编写，未复制 SVG、文案或 UI。Lite 兼容准入、provider / Geo 准备及脚本联调使用固定 Meow 配置与测试向量；分流脚本仍由上游生成维护，本项目不另维护生产路由基线。测试夹具、显式本地资源供给和实际 HTTP / VPN 路由验收分别记录，不能互相代替

**许可证、署名与证据：** 既有流程与 README 为只读参考；[固定 README](https://github.com/IvanSolis1989/Smart-Config-Kit/blob/18a676345de86af38f511b8137add605e0a59588/README.md)。联调固定树的 README 声明 MIT，未发现独立 LICENSE / COPYING / NOTICE 文件，不补造授权；[固定 Meow 脚本](https://github.com/IvanSolis1989/Smart-Config-Kit/blob/89abb221a157acc98d6b52c85583e6b5a5b2742b/Meow%E5%86%85%E6%A0%B8%E7%B3%BB/Aerove%20Lite/AeroveLite(meow).js) SHA256 `217a4d7ecfb56d99cbb79148159ee636b882af5520c4ad7b6c6c870405f938cb`。第三方规则及数据库保留各自来源和许可约束，不视为 SCK 原创或推定采用 MIT

#### [tun-rs/tun-rs](https://github.com/tun-rs/tun-rs)

**固定版本 / revision：** `d0f764c135a34ad92360462f89a40b59f373cc06`

**用途与采用方式：** Lite clash-rs核心能力审计中，只读核对Unix异步设备/已打开fd保护边界；未新增独立依赖

**许可证、署名与证据：** MIT；`docs/aerove-lite/core/clash-rs-audit.md`

#### [Arc-blroth/memory-stats](https://github.com/Arc-blroth/memory-stats)

**固定版本 / revision：** `431e74654c27ec38a6f7d19c186bec42c2a938b8`

**用途与采用方式：** Lite核心审计中只读核对内存统计实现；未新增依赖

**许可证、署名与证据：** MIT；`docs/aerove-lite/core/clash-rs-audit.md`

#### [meow-rs/meow-rs](https://github.com/meow-rs/meow-rs)

**固定版本 / revision：** `v0.26.1` / `727ddcd06f592e2f12c5222c4de6cce328df808c`；原始 Git 文件 map `a1948157edb77700f6f9152f3467907fb6a050c66cd153edfe98295bbf56e550`

**用途与采用方式：** Lite 选定 Rust 核心，迁移中；[Run17](https://github.com/IvanSolis1989/Aerove/actions/runs/39994659739) 已完成 56 项宿主测试及双 Android ABI 链接，实际覆盖同代运行、监听与资源所有权。尚无完整 Meow APK/ART/系统路由及十二图，不代表完整功能或首发。保留此前公开基准、DNS 与官方 Android FFI/Cargo 参考记录。

**许可证、署名与证据：** MIT；[固定 LICENSE](https://github.com/meow-rs/meow-rs/blob/727ddcd06f592e2f12c5222c4de6cce328df808c/LICENSE) 原始 SHA256 `b7a39018faacfd98597da373045fbacf2c3f134fd755e553894a6a9adad38db5`；[固定 Cargo.toml](https://github.com/meow-rs/meow-rs/blob/727ddcd06f592e2f12c5222c4de6cce328df808c/Cargo.toml)。

#### [meow-rs/meow-android](https://github.com/meow-rs/meow-android)

**固定版本 / revision：** `4740012bf0efdb8bae8fbeeefaf4937dfa309f26`；该参考自身 Meow 依赖仍为 `v0.26.0`

**用途与采用方式：** 已逐 Git blob 核查 14 份原文件；Lite Android JNI、socket protect、TUN FD、配置装配、网络切换、订阅及 NDK/CMake 实现参照；不照搬删监听/sniffer、公共 DNS fallback 或未等待退出的路径，不作为已验收实现

**许可证、署名与证据：** MIT；[固定提交 LICENSE](https://github.com/meow-rs/meow-android/blob/4740012bf0efdb8bae8fbeeefaf4937dfa309f26/LICENSE)，SHA256 `9efa36b633149025223074bc471560d9aec5111d31a874d133cb1e1eec38f59e`；实际采用桥接另保原始署名与许可

#### [sunO2/GStore](https://github.com/sunO2/GStore)

**固定版本 / revision：** `0fed7b121b45f26df1f8e57a266295a5d5e9b31a`；实际 Meow `0.21.2` 固定 `0e718f587442caaac00b221efc91c84907aeb0e0`

**用途与采用方式：** 第三方完整 Android 产品源码参考：VpnService、JNI protect、TUN duplicate FD、停止与订阅抓取；未复制其动态模块宿主、缺 service 时放行或限时退出规则。发布包来源与检查 main 不同，不冒称已验证设备行为

**许可证、署名与证据：** GPL-3.0；[固定 LICENSE](https://github.com/sunO2/GStore/blob/0fed7b121b45f26df1f8e57a266295a5d5e9b31a/LICENSE)，保留原许可与版权；本次只读参照

#### [libnyanpasu/clash-nyanpasu](https://github.com/libnyanpasu/clash-nyanpasu)

**固定版本 / revision：** `730ad00c18c067b4b0fe7f1e749387cf57d1a8dc`；实际 Meow release 配置 `v0.26.1`

**用途与采用方式：** 真实桌面产品的核心资产选择、API 接入和生命周期参照；不改变标准版官方 Mihomo 约定，也不把外置进程接口当 Android JNI

**许可证、署名与证据：** GPL-3.0；保留固定来源与原许可，本次只读参照

#### [libnyanpasu/nyanpasu-runtime](https://github.com/libnyanpasu/nyanpasu-runtime)

**固定版本 / revision：** 客户端实际子模块 `4eccc555490a5edcf5d8db63c487dc82b2c19bf4`；core-manager `1.0.1`

**用途与采用方式：** 已核进程停止确认、PID 归属、转发任务 abort 后 await、DNS 恢复及 Meow API DTO；桌面实现参考，未引入依赖或迁移 Android owner

**许可证、署名与证据：** GPL-3.0；保留原许可及署名，本次只读参照

#### [richerfu/Paws](https://github.com/richerfu/Paws)

**固定版本 / revision：** `81c4a259a6d409e06fd8c17663d8dd7157a1506f`；Meow `0.22.0` 加六份 vendor patch；原 crate provenance `4a11f74fb8bc0feb3365bee7ee02dcf97452afa4`

**用途与采用方式：** HarmonyOS 嵌入客户端源码参考：TunSession stop/join、generation、公开 meow-api 路由及物理 DNS/protect；未复制平台 N-API、产品状态或整体 vendor fork

**许可证、署名与证据：** MIT；vendor 原 Max Lv MIT 署名保留，本次只读参照

#### [gucoi/S](https://github.com/gucoi/S)

**固定版本 / revision：** `4b9220bb88bc3c874e6f345946503084a1fd39a8`；Meow 依赖固定 `7c43cbbedc5c665ebeda1e55a50afcfdb373ccf0`

**用途与采用方式：** 已检查 Tauri/macOS 协议适配源码和 ADR；透明 DNS/UDP/QUIC 与许可证据不足，未复制或采用，保留排除依据

**许可证、署名与证据：** 本次未核得适用根许可证；不据此宣称上游无许可证，不复制源码

#### [meow-rs/meow-ios](https://github.com/meow-rs/meow-ios)

**固定版本 / revision：** `a32df0ba845d706659cb531aa9250e769011f651`

**用途与采用方式：** 官方客户端检索参考：核查仓库结构、平台和许可；未移植 iOS 网络扩展或称其为 Android SDK

**许可证、署名与证据：** MIT，Max Lv；[固定 LICENSE](https://github.com/meow-rs/meow-ios/blob/a32df0ba845d706659cb531aa9250e769011f651/LICENSE)；未复制源码

#### [madeye/BaoLianDeng](https://github.com/madeye/BaoLianDeng)

**固定版本 / revision：** `10cffd10b15d485f2377607d055b01700b04c58b`

**用途与采用方式：** 检索候选的平台排除参考：Swift/macOS 产品，不能当第三方 Android 客户端；未复制源码

**许可证、署名与证据：** MIT，Max Lv；[固定 LICENSE](https://github.com/madeye/BaoLianDeng/blob/10cffd10b15d485f2377607d055b01700b04c58b/LICENSE)

#### [dalek-cryptography/subtle](https://github.com/dalek-cryptography/subtle)

**固定版本 / revision：** `subtle 2.6.1` / `13c2bddecc57b384dee18652358fb23172facb8a2c51ccc10d74c157bdea3292`

**用途与采用方式：** Meow-native API token 使用 constant-time equality；直接采用，已在成功 host 13 例图中验证。

**许可证、署名与证据：** BSD-3-Clause；成功桥接 Cargo.lock checksum 对应 固定 crates.io 源码包；crate `LICENSE` SHA-256 与成功 `meow-native/Cargo.lock` 核对；原始声明保留于 registry 源包。

#### [tokio-rs/tracing](https://github.com/tokio-rs/tracing)

**固定版本 / revision：** `tracing 0.1.44` / `63e71662fa4b2a2c3a26f570f037eb95bb1f85397f3cd8076caed2f026a6d100`；`tracing-subscriber 0.3.23` / `cb7f578e5945fb242538965c2d0b04418d38ec25c79d160cd279bf0731c8d319`

**用途与采用方式：** Meow-native API 请求日志层和 subscriber 配置；同仓库两个直接 crate 一行登记，已在成功 host 13 例图中验证。

**许可证、署名与证据：** MIT；各 固定 crates.io 源码包 Cargo.toml metadata 与 LICENSE hash 与成功 `meow-native/Cargo.lock` 核对；原始声明保留于 registry 源包。

#### [rust-lang/futures-rs](https://github.com/rust-lang/futures-rs)

**固定版本 / revision：** `futures 0.3.34` / `9a31d2a3fbaaeb2af2368bbdd904aa8e812d3c04a1ee10d3171f52d556e5d0a3`

**用途与采用方式：** 成功 [Run13](https://github.com/IvanSolis1989/Aerove/actions/runs/39985950525) 锁2510fedd中为普通直接依赖；Driver采用 FutureExt/SinkExt/StreamExt，原api_lifecycle测试用途保留。34项host通过，尚非该图Android验收。

**许可证、署名与证据：** MIT OR Apache-2.0；固定源码包 Cargo.toml metadata 与两份 LICENSE 文件 hash 与成功 `meow-native/Cargo.lock` 核对；原始声明保留于 registry 源包。

#### [snapview/tokio-tungstenite](https://github.com/snapview/tokio-tungstenite)

**固定版本 / revision：** `tokio-tungstenite 0.29.0` / `8f72a05e828585856dacd553fba484c242c46e391fb0e58917c942ee9202915c`

**用途与采用方式：** API lifecycle integration test 连接 WebSocket；dev-dependency，成功 host 13 例图使用。

**许可证、署名与证据：** MIT；固定源码包 Cargo.toml metadata 与 LICENSE hash 与成功 `meow-native/Cargo.lock` 核对；原始声明保留于 registry 源包。

#### [hyperium/hyper](https://github.com/hyperium/hyper)

**固定版本 / revision：** `hyper 1.12.0`；registry checksum `2c3e324da4c95177d6291d4c8730197c0d1822f8a9766814a4a44fa5ab797c9c`（Cargo.lock）

**用途与采用方式：** Meow-native 的本地 HTTP/1 server connection；直接依赖并复用既有 API，未复制其实现。

**许可证、署名与证据：** MIT；官方 crates.io 精确版本元数据仓库/许可字段，`apps/aerove-lite/meow-native/Cargo.lock` 的 registry checksum 对应原始源码包

#### [hyperium/hyper-util](https://github.com/hyperium/hyper-util)

**固定版本 / revision：** `hyper-util 0.1.21`；registry checksum `ddc03d96684f9226b8a787cdb71488417b53ab5ea8fdb1dac946cb9431cc8bff`（Cargo.lock）

**用途与采用方式：** Meow-native 将 Tokio listener/service 桥接到 Hyper；直接依赖并复用既有 API，未复制其实现。

**许可证、署名与证据：** MIT；官方 crates.io 精确版本元数据仓库/许可字段，`apps/aerove-lite/meow-native/Cargo.lock` 的 registry checksum 对应原始源码包

#### [jni-rs/jni-rs](https://github.com/jni-rs/jni-rs)

**固定版本 / revision：** `jni 0.22.4`；registry checksum `5efd9a482cf3a427f00d6b35f14332adc7902ce91efb778580e180ff90fa3498`

**用途与采用方式：** Meow 桥接直接依赖；[Run17](https://github.com/IvanSolis1989/Aerove/actions/runs/39994659739) 的 ARM64/x86_64 原 ELF 已定义 7 个 Runtime 与 4 个 Script JNI，56 项宿主测试通过。下一批 UA、能力与四类校验扩展仍待新图编译，不能借 11 符号证明完整 APP、ART 或新 18 符号。

**许可证、署名与证据：** MIT OR Apache-2.0；官方 crates.io 精确版本元数据及桥接 Cargo.lock 核实；该 crate 原包没有独立 LICENSE 文件的事实保留，不虚构文件哈希。

#### [Amanieu/parking_lot](https://github.com/Amanieu/parking_lot)

**固定版本 / revision：** `parking_lot 0.12.5`；registry checksum `93857453250e3077bd71ff98b6a65ea6621a19bb0f559a85248955ac12c45a1a`（Cargo.lock）

**用途与采用方式：** Meow-native runtime owner/session 的同步 Mutex；直接依赖并复用既有 API，未复制其实现。

**许可证、署名与证据：** MIT OR Apache-2.0；官方 crates.io 精确版本元数据仓库/许可字段，`apps/aerove-lite/meow-native/Cargo.lock` 的 registry checksum 对应原始源码包

#### [serde-rs/serde](https://github.com/serde-rs/serde)

**固定版本 / revision：** `serde 1.0.229`；registry checksum `4148590afebada386688f18773da617792bf2ef03ffc1e4cbd2b1d45b023e0ba`（Cargo.lock）

**用途与采用方式：** Meow-native runtime 响应结构使用 Serialize；直接依赖并复用既有 API，未复制其实现。

**许可证、署名与证据：** MIT OR Apache-2.0；官方 crates.io 精确版本元数据仓库/许可字段，`apps/aerove-lite/meow-native/Cargo.lock` 的 registry checksum 对应原始源码包

#### [serde-rs/json](https://github.com/serde-rs/json)

**固定版本 / revision：** `serde_json 1.0.154`；registry checksum `e7e9cc8b1b85264074fbcc02a88680c4096b1e47df8f739dceb03bf482f04bd6`（Cargo.lock）

**用途与采用方式：** 直接 JSON 依赖；代表链测试实际解码私有运行快照，响应由 Axum/Serde 序列化；未复制其实现。

**许可证、署名与证据：** MIT OR Apache-2.0；官方 crates.io 精确版本元数据仓库/许可字段，`apps/aerove-lite/meow-native/Cargo.lock` 的 registry checksum 对应原始源码包

#### [dtolnay/serde-yaml](https://github.com/dtolnay/serde-yaml)

**固定版本 / revision：** `serde_yaml 0.9.34+deprecated`；registry checksum `6a8b1a1a2ebf674015cc02edccce75287f1a0130d394307b36743c2f5d504b47`（Cargo.lock）

**用途与采用方式：** Meow-native runtime 解析 YAML 配置并检查字段；直接依赖并复用既有 API，版本原样记录其 +deprecated 后缀。

**许可证、署名与证据：** MIT OR Apache-2.0；官方 crates.io 精确版本元数据仓库/许可字段，`apps/aerove-lite/meow-native/Cargo.lock` 的 registry checksum 对应原始源码包

#### [MFSGA/AChimera](https://github.com/MFSGA/AChimera)

**固定版本 / revision：** 只读查阅公开项目；无产品依赖

**用途与采用方式：** Android Rust代理客户端形态与限制调查，未采用其实现

**许可证、署名与证据：** GPL-3.0；`docs/research/android-core-decision.md`

#### [chrisbanes/haze](https://github.com/chrisbanes/haze)

**固定版本 / revision：** `2.0.1` / `347c67a1f190b71f439f3b9ca7ffb326d0044629`

**用途与采用方式：** 液态玻璃库对比；检查Glass、形状和Shader API，未进入产品依赖或复制代码

**许可证、署名与证据：** Apache-2.0；`docs/research/rendering/haze.md`、`docs/research/rendering/library-selection.md`

#### [belinwu/cloudy](https://github.com/belinwu/cloudy)

**固定版本 / revision：** `0.7.1`；只读源码锚点 `e3ffd8e957b2a29832c287a95665ecc540e9c7eb`

**用途与采用方式：** 玻璃渲染与多层捕获路径比较，未进入产品依赖或复制代码

**许可证、署名与证据：** Apache-2.0；`docs/research/rendering/native-alternatives.md`、`library-selection.md`

#### [BuildItCode/LiquidGlass](https://github.com/BuildItCode/LiquidGlass)

**固定版本 / revision：** `v0.8.2`；只读源码锚点 `3e30a2300ce471f8b44497df87b93beb24fc6f5e`

**用途与采用方式：** Android玻璃捕获层级与GPU背景采样比较；未采用，完整AAR/许可证据在原评估中待核

**许可证、署名与证据：** 许可证尚未由该评估证实；`docs/research/rendering/library-selection.md`、`native-alternatives.md`

#### [Dimezis/BlurView](https://github.com/Dimezis/BlurView)

**固定版本 / revision：** 未固定release/commit

**用途与采用方式：** 仅作底层View模糊能力比较，未采用，也未据此复制代码

**许可证、署名与证据：** Apache-2.0（上游）；`docs/research/rendering/library-selection.md`

#### [actions/checkout](https://github.com/actions/checkout)

**固定版本 / revision：** `11d5960a326750d5838078e36cf38b85af677262`

**用途与采用方式：** 两版 CI 拉取工作区源码。作为 GitHub Action 按固定 SHA 引用。

**许可证、署名与证据：** MIT；[固定提交 LICENSE](https://github.com/actions/checkout/blob/11d5960a326750d5838078e36cf38b85af677262/LICENSE)

#### [actions/cache](https://github.com/actions/cache)

**固定版本 / revision：** `0057852bfaa89a56745cba8c7296529d2fc39830`、`caa296126883cff596d87d8935842f9db880ef25`

**用途与采用方式：** 标准版构建及字体 fixture 缓存；本地 Flutter action 的 SDK/pub 缓存。两固定提交均由本轮 CI action `uses` 引用。

**许可证、署名与证据：** MIT；[0057852 LICENSE](https://github.com/actions/cache/blob/0057852bfaa89a56745cba8c7296529d2fc39830/LICENSE)、[caa2961 LICENSE](https://github.com/actions/cache/blob/caa296126883cff596d87d8935842f9db880ef25/LICENSE)

#### [actions/download-artifact](https://github.com/actions/download-artifact)

**固定版本 / revision：** `d3f86a106a0bac45b974a628896c90dbdf5c8093`

**用途与采用方式：** 标准版跨 job 取回同一运行的已验收构建产物。按固定 SHA 引用。

**许可证、署名与证据：** MIT；[固定提交 LICENSE](https://github.com/actions/download-artifact/blob/d3f86a106a0bac45b974a628896c90dbdf5c8093/LICENSE)

#### [actions/setup-go](https://github.com/actions/setup-go)

**固定版本 / revision：** `40f1582b2485089dde7abd97c1529aa768e1baff`

**用途与采用方式：** 两版构建 Go 辅助程序时安装 Go。按固定 SHA 引用。

**许可证、署名与证据：** MIT；[固定提交 LICENSE](https://github.com/actions/setup-go/blob/40f1582b2485089dde7abd97c1529aa768e1baff/LICENSE)

#### [actions/setup-java](https://github.com/actions/setup-java)

**固定版本 / revision：** `cf277c60eb25467037889841efdb72551f06f6c3`

**用途与采用方式：** 两版 Android 构建与验证环境安装 Java。按固定 SHA 引用。

**许可证、署名与证据：** MIT；[固定提交 LICENSE](https://github.com/actions/setup-java/blob/cf277c60eb25467037889841efdb72551f06f6c3/LICENSE)

#### [actions/upload-artifact](https://github.com/actions/upload-artifact)

**固定版本 / revision：** `ea165f8d65b6e75b540449e92b4886f43607fa02`

**用途与采用方式：** 两版 CI 上传构建收据、APK 和验证证据。按固定 SHA 引用。

**许可证、署名与证据：** MIT；[固定提交 LICENSE](https://github.com/actions/upload-artifact/blob/ea165f8d65b6e75b540449e92b4886f43607fa02/LICENSE)

#### [android-actions/setup-android](https://github.com/android-actions/setup-android)

**固定版本 / revision：** `9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407`

**用途与采用方式：** Lite CI 安装 Android SDK 环境。按固定 SHA 引用。

**许可证、署名与证据：** MIT；[固定提交 LICENSE](https://github.com/android-actions/setup-android/blob/9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407/LICENSE)

#### [dtolnay/rust-toolchain](https://github.com/dtolnay/rust-toolchain)

**固定版本 / revision：** `b725eda525362e7cf34af9a7243cac1e3f01a7ec`

**用途与采用方式：** 两版 CI 配置固定 Rust 工具链。按固定 SHA 引用。

**许可证、署名与证据：** MIT；[固定提交 LICENSE](https://github.com/dtolnay/rust-toolchain/blob/b725eda525362e7cf34af9a7243cac1e3f01a7ec/LICENSE)

#### [gradle/actions](https://github.com/gradle/actions)

**固定版本 / revision：** `ed408507eac070d1f99cc633dbcf757c94c7933a`

**用途与采用方式：** Lite Android CI 配置 Gradle 与缓存。按固定 SHA 引用。

**许可证、署名与证据：** MIT；[固定提交 LICENSE](https://github.com/gradle/actions/blob/ed408507eac070d1f99cc633dbcf757c94c7933a/LICENSE)

#### [nttld/setup-ndk](https://github.com/nttld/setup-ndk)

**固定版本 / revision：** `ed92fe6cadad69be94a966a7ee3271275e62f779`

**用途与采用方式：** 标准版 Android 构建安装 NDK。按固定 SHA 引用。

**许可证、署名与证据：** MIT；[固定提交 LICENSE](https://github.com/nttld/setup-ndk/blob/ed92fe6cadad69be94a966a7ee3271275e62f779/LICENSE)

#### [reactivecircus/android-emulator-runner](https://github.com/ReactiveCircus/android-emulator-runner)

**固定版本 / revision：** `a421e43855164a8197daf9d8d40fe71c6996bb0d`

**用途与采用方式：** Lite CI 在 Android emulator 上运行 API 33 应用/仪器验收。按固定 SHA 引用。

**许可证、署名与证据：** Apache-2.0；[固定提交 LICENSE](https://github.com/ReactiveCircus/android-emulator-runner/blob/a421e43855164a8197daf9d8d40fe71c6996bb0d/LICENSE)

#### [subosito/flutter-action](https://github.com/subosito/flutter-action)

**固定版本 / revision：** `1a449444c387b1966244ae4d4f8c696479add0b2`

**用途与采用方式：** 标准版本地 Flutter setup action 由该固定上游源码适配而来；保留 setup.sh、适配 action.yaml 并 pin 其嵌套 cache。根据现有 SOURCES.json 及本轮对照记录来源。

**许可证、署名与证据：** MIT；[固定提交 LICENSE](https://github.com/subosito/flutter-action/blob/1a449444c387b1966244ae4d4f8c696479add0b2/LICENSE)，本地 `.github/actions/flutter/SOURCES.json` 记录来源 SHA256 `329009b59300a124af69c29c9ad19d38079207d9098c76fe0b51dc79a76a2e9a`

#### [Swatinem/rust-cache](https://github.com/Swatinem/rust-cache)

**固定版本 / revision：** `d1527c8f67ef9af357290475d4af4b0fab8f12e0`

**用途与采用方式：** Lite CI Rust 编译缓存。按固定 SHA 引用。

**许可证、署名与证据：** LGPL-3.0；[固定提交 LICENSE](https://github.com/Swatinem/rust-cache/blob/d1527c8f67ef9af357290475d4af4b0fab8f12e0/LICENSE)

#### [rust-lang/rust-bindgen](https://github.com/rust-lang/rust-bindgen)

**固定版本 / revision：** `bindgen 0.72.1` / Cargo.lock checksum `993776b509cfb49c750f11b8f07a46fa23e0a1386ffc01fb1e7d343efc387895`

**用途与采用方式：** QuickJS Android 绑定的官方 target-specific clang 参数与 shlex 行为只读参考；未复制实现，Android 实际生成仍须 CI 验证

**许可证、署名与证据：** BSD-3-Clause；已成功 host 图的 metadata/lock 核对版本、仓库与许可；[固定版本源码](https://github.com/rust-lang/rust-bindgen/blob/v0.72.1/bindgen/lib.rs)

#### [KyleMayes/clang-sys](https://github.com/KyleMayes/clang-sys)

**固定版本 / revision：** `clang-sys 1.9.1` / Cargo.lock checksum `157a8ba7b480713b56f4c09fd13fc3e0a22a5dfab8097ba61cbc5feef950788a`

**用途与采用方式：** 只读确认 `LIBCLANG_PATH` 可指向实际共享库文件；本项目探测代码不复制其实现，真实加载结果单独留证

**许可证、署名与证据：** Apache-2.0；已成功 host 图的 metadata/lock 核对版本、仓库与许可；[固定版本源码](https://github.com/KyleMayes/clang-sys/blob/v1.9.1/build/common.rs)

#### [madeye/lwip](https://github.com/madeye/lwip)

**固定版本 / revision：** Meow v0.26.1 vendoring 声明固定 `5f6f0dfe025c967157ff328d94efe488852d8755`，位于 Meow `727ddcd06f592e2f12c5222c4de6cce328df808c` 的 `crates/meow-lwip`

**用途与采用方式：** Lite 采用当前 Meow 内置单 owner LwipCore；对照官方 Android 所用 dab00c540cf0606fee14ddd99cfa6ec79d33681d 分支的三项锁、PCB 和 timer 修复，实际根因均已由现有 actor 覆盖；保留当前分支变化，不回贴旧 mutex；尚无本轮 TUN/APK 验收

**许可证、署名与证据：** Rust Cargo 声明 MIT/Apache-2.0；C `COPYING` 为 BSD-3-Clause，原始 Git 字节 SHA256 `ef4aac92e05e87cd1cdc140870ed52206ba03d4a7fe46c1e11d7ffa6c87d252b`；该 vendored 子树无单独 MIT/Apache 文本，不虚构文件哈希；保留原版权与 COPYING

#### [rust-lang/libc](https://github.com/rust-lang/libc)

**固定版本 / revision：** `libc 0.2.191` / `65cd87c9a6d60536580b316ad985726104730a5151c99845f2490428ef91240a`

**用途与采用方式：** 成功 [Run13](https://github.com/IvanSolis1989/Aerove/actions/runs/39985950525) 锁2510fedd确认 cfg(unix) 直接依赖，用于私有FD与物理网络socket；34项host证据，不沿用旧图间接依赖冒新图，也不宣称Android已验。

**许可证、署名与证据：** MIT OR Apache-2.0；旧 Run6 `Cargo.lock`、官方精确版 [crates.io metadata](https://crates.io/api/v1/crates/libc/0.2.191) 与官方源包 `Cargo.toml` / LICENSE 文件证据见 `proof.json`。

#### [dtolnay/async-trait](https://github.com/dtolnay/async-trait)

**固定版本 / revision：** `async-trait 0.1.92` / `82f6aeea286b8eb4dd3431a1be1b59d290ace00f5bfd8e2a159bc2a05e2c1667`

**用途与采用方式：** physical_dns.rs 使用macro的普通直接依赖；成功 [Run13](https://github.com/IvanSolis1989/Aerove/actions/runs/39985950525) 锁2510fedd/34项host核实，替代此前仅candidate及Run9间接锁证据。

**许可证、署名与证据：** Official crates.io metadata and exact `async-trait-0.1.92` source package Cargo.toml report `MIT OR Apache-2.0`; package/license hashes in sibling `proof.json`.

#### [hickory-dns/hickory-dns](https://github.com/hickory-dns/hickory-dns)

**固定版本 / revision：** `hickory-proto 0.26.3`；原包 registry checksum `12b92608f679a6fa515dd1d15c1ff89443026e391200a2c840c7afcba482893d`；`.cargo_vcs_info.json` 固定源码 `bd37cafbdd86112a6da32878f8c9bab22e14377e`

**用途与采用方式：** physical_dns 真实 DNS 报文的直接 dev 依赖，成功 [Run13](https://github.com/IvanSolis1989/Aerove/actions/runs/39985950525) 锁已核；另只读参考 Name 规范化比较及 HTTPS AliasMode/ServiceMode 的类型语义，核对 ECH 答案的名称、类别与别名绑定，复用既有 API，未复制其实现

**许可证、署名与证据：** MIT OR Apache-2.0；精确源码包 Cargo.toml/许可及锁校验保留；[name.rs](https://github.com/hickory-dns/hickory-dns/blob/bd37cafbdd86112a6da32878f8c9bab22e14377e/crates/proto/src/rr/domain/name.rs)、[svcb.rs](https://github.com/hickory-dns/hickory-dns/blob/bd37cafbdd86112a6da32878f8c9bab22e14377e/crates/proto/src/rr/rdata/svcb.rs)

#### [rust-lang/flate2-rs](https://github.com/rust-lang/flate2-rs)

**固定版本 / revision：** 候选精确依赖 `flate2 =1.1.10`；registry checksum `6e634e2e0ebac1ee034020da1ca582e17ffe4e0f5e985823721e168928136dcb`

**用途与采用方式：** Meow 离线资源校验候选采用有界 `MultiGzDecoder`，复用旧 Lite `native/upstream/resources.rs` 的多成员 gzip 解码经验；新候选 Cargo 明确 `default-features=false, features=[rust_backend]`。成功 [Run19](https://github.com/IvanSolis1989/Aerove/actions/runs/40002589911) 锁 `333699a8…` 已固定该依赖，配置与资源目标 16 项实际通过（含 gzip 与 FIFO 回归）；当前全图 103 主测试及 17 个 feature 边界测试通过，尚无 APK/ART 验收。

**许可证、署名与证据：** MIT OR Apache-2.0；官方 1.1.10 原包 SHA 与锁校验值一致；`LICENSE-MIT` SHA256 `025436edff4cfcdde17a5811fdea78892d8482efd1abdec5a17872d07a4f2112`，`LICENSE-APACHE` SHA256 `a60eea817514531668d7e00765731449fe14d059d3249e0bc93b36de45f759f2`；[MultiGzDecoder 官方文档](https://docs.rs/flate2/1.1.10/flate2/read/struct.MultiGzDecoder.html)。

#### [android/gradle-recipes](https://github.com/android/gradle-recipes)

**固定版本 / revision：** `agp-9.0` 分支固定 `449b73601ba59ae751ff76f9869f2b8461e2b9c0`

**用途与采用方式：** 修复客户端预检的资产 SourceSet Provider 配置错误时，只读参照官方 `addGeneratedSourceFolder` 的 README / CustomPlugin.kt，比较 Variant 生成目录方案，实际沿用固定输出目录及原 Sync / mergeAssets 依赖。另适配 `getScopedArtifacts` 示例的公开 `ScopedArtifact.CLASSES` / `Scope` / `toGet` 接线，在 R8 前读取真实 app、AndroidTest 和依赖 classfile，生成测试调用边的精确保留规则；不引入示例插件或私有任务名推断。

**许可证、署名与证据：** Apache-2.0；资产示例保留 AOSP 2022，scoped-artifact 适配保留 AOSP 2023 署名与许可链接于 `apps/aerove-lite/app/build.gradle.kts`；[固定资产示例](https://github.com/android/gradle-recipes/tree/449b73601ba59ae751ff76f9869f2b8461e2b9c0/addGeneratedSourceFolder)、[固定 classfile 示例](https://github.com/android/gradle-recipes/tree/449b73601ba59ae751ff76f9869f2b8461e2b9c0/getScopedArtifacts)、[AGP 9.4 API](https://developer.android.com/reference/tools/gradle-api/9.4/com/android/build/api/dsl/AndroidSourceDirectorySet)。适配另对照本项目 AGP 9.4.1 固定源码；源码核对不代替实际构建图验收。

#### [aosp-mirror/platform_frameworks_base](https://github.com/aosp-mirror/platform_frameworks_base)

**固定版本 / revision：** 固定 `1cdfff555f4a21f71ccc978290e2e212e2f8b168`；Run83 只读源码固定提交 `0d3ff311e6e80dee7fe88a2a2cfa272ce231c3c6`

**用途与采用方式：** SDK 37 客户端编译修复时只读参考 Android `ActivityManager.AppTask#getTaskInfo()`：任务不存在可返回 null，采用安全访问忽略失效任务；未复制实现、未新增产品依赖。；Run83 Android capture 诊断只读参照 `ViewTreeObserver.java`（blob `5a99ab2f5bdb2ef688b398ba904147b8b2cfb96c`，范围752–779）和 `ViewRootImpl.java`（blob `a13872eef6b80ccbadb09e785dbeb14b20e493b8`，范围4209–4236、4263–4280）；未复制实现或新增依赖；同固定提交另参照 `Instrumentation.java`（blob `8984c4292023bbe21633b0aa7739c67ff10040f2`）与 `Process.java`（blob `e06e7326a8603a58c8f49397f5fa0a681a10cab2`）的受控测试退出及本进程 PID 信号路径，未复制实现

同一固定提交还只读参照 `InputShellCommand.java`、`WindowManagerShellCommand.java`、`KeyguardServiceDelegate.java`、`PhoneWindowManager.java` 和 `cmds/wm/wm`，用于 #84 Android 取图启动修正：比较同步输入注入与 WindowManager 解锁，按真实 Keyguard 状态读回确认可交互；未复制 Java 实现或新增产品依赖。

同一 `0d3ff311e6e80dee7fe88a2a2cfa272ce231c3c6` 提交另只读核对 [`libs/hwui/jni/RenderEffect.cpp`](https://github.com/aosp-mirror/platform_frameworks_base/blob/0d3ff311e6e80dee7fe88a2a2cfa272ce231c3c6/libs/hwui/jni/RenderEffect.cpp) 的 RuntimeShader 单输入绑定，用于极光前景蒙版修正；未复制实现。

**许可证、署名与证据：** Apache-2.0，原 AOSP 源文件许可头；[固定 ActivityManager.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/1cdfff555f4a21f71ccc978290e2e212e2f8b168/core/java/android/app/ActivityManager.java)、[官方 API](https://developer.android.com/reference/android/app/ActivityManager.AppTask#getTaskInfo())。；run83 固定来源中的两份 AOSP 源文件许可为 Apache-2.0，证据见 `SOURCES.json`

#### [torvalds/linux](https://github.com/torvalds/linux)

**固定版本 / revision：** [`v6.8`](https://github.com/torvalds/linux/tree/v6.8) / `net/ipv4/tcp.c` blob `c82dc42f57c65df112f79080ff407cd98d11ce68`

**用途与采用方式：** Run31 取消夹具的只读诊断参考：核查 `tcp_close()` 对 unread TCP data 的处理路径（关闭并发送 active reset）；未复制 Linux 实现，不声称 CI kernel 为 v6.8。

**许可证、署名与证据：** 文件 SPDX `GPL-2.0-or-later`，见 [固定 `tcp.c`](https://github.com/torvalds/linux/blob/v6.8/net/ipv4/tcp.c)；GitHub 仓库 API 许可字段为 `NOASSERTION`；[Linux `LICENSES/preferred/GPL-2.0`](https://github.com/torvalds/linux/blob/v6.8/LICENSES/preferred/GPL-2.0)，blob `ff0812fd89cc41fc1b1ed6732a621057d30ed2ad`。

#### [flutter/packages](https://github.com/flutter/packages)

**固定版本 / revision：** `url_launcher 6.3.3` / `url_launcher-v6.3.3` / `1cbff9334a7dc8f286dbd3b00fe30fa04a7083e7`; `url_launcher_platform_interface 2.3.2` / `url_launcher_platform_interface-v2.3.2` / `ef349bec8ee9d5fa3f69d73aa78ab9bb84ae0aa7`

**用途与采用方式：** Standard URL launcher failure diagnosis only: read `launchUrl` result contract (`false` or `PlatformException`) and MethodChannel implementation (`null` becomes `false`, platform exception propagates); no source copied and no dependency added.

**许可证、署名与证据：** BSD-style text and Flutter Authors copyright in both fixed-tag package/root `LICENSE` files: [`url_launcher` LICENSE](https://github.com/flutter/packages/blob/url_launcher-v6.3.3/packages/url_launcher/url_launcher/LICENSE) blob `29b709dac6c7a8ce696d35c375f603afd808d205`; [`url_launcher_platform_interface` LICENSE](https://github.com/flutter/packages/blob/url_launcher_platform_interface-v2.3.2/packages/url_launcher/url_launcher_platform_interface/LICENSE) blob `c6823b81eb845db89cee59cbbc7ee0b0b63d86ec`. GitHub repo metadata response did not expose a repository-level license field; these fixed-file texts are the evidence.

#### [dart-lang/native](https://github.com/dart-lang/native)

**固定版本 / revision：** `0aa672fed0b26aff83440d939da14ee3bab13969`；native_toolchain_c `0.17.6`

**用途与采用方式：** Lite Android 静态 C++ 链接方式参考：`-Bstatic -lc++ -Bdynamic` 选用 NDK libc++.a 脚本，补齐 libc++abi；未引入 Dart 工具链或新运行依赖

**许可证、署名与证据：** BSD-3-Clause，Dart project authors；[固定实现](https://github.com/dart-lang/native/blob/0aa672fed0b26aff83440d939da14ee3bab13969/pkgs/native_toolchain_c/lib/src/cbuilder/run_cbuilder.dart#L350)、[LICENSE](https://github.com/dart-lang/native/blob/0aa672fed0b26aff83440d939da14ee3bab13969/LICENSE)

#### [Rosemoe/sora-editor](https://github.com/Rosemoe/sora-editor)

**固定版本 / revision：** `0.23.7` / `223e4febc8aa37f7d968ee0e8fcda95cec746342`；来源归档 SHA256 `0429f8853930a0ada2936bf6f8740cfaa22358009fd56f0f49111dff9709e7d8`

**用途与采用方式：** Lite 既有编辑器依赖；只读核验搜索发布事件与结果计数、文档布局完成时序，适配客户端单次延后发布与测试真实就绪；未复制或修改 Sora 源码，未新增依赖

**许可证、署名与证据：** LGPL-2.1-or-later，Rosemoe 及原贡献者；[固定 EditorSearcher](https://github.com/Rosemoe/sora-editor/blob/223e4febc8aa37f7d968ee0e8fcda95cec746342/editor/src/main/java/io/github/rosemoe/sora/widget/EditorSearcher.java)、[LICENSE](https://github.com/Rosemoe/sora-editor/blob/223e4febc8aa37f7d968ee0e8fcda95cec746342/LICENSE)；已有许可原文 SHA256 `7ffe1954587c77dfba1cf8eb9b2ea743671fa6e63f9e7a2f258119d42e14eefe`

#### [Android NDK 官方文档](https://android.googlesource.com/platform/ndk)

**固定版本 / revision：** r28 文档固定 `805836ef53231ef86dd5fcc5652a1c28d74f4bc4`

**用途与采用方式：** 只读核验本项目 NDK 28.2 的工具链、C++ runtime 和链接脚本行为；未复制工具链实现或新增运行依赖

**许可证、署名与证据：** 官方原文与署名保留；[固定 BuildSystemMaintainers.md](https://android.googlesource.com/platform/ndk/+/805836ef53231ef86dd5fcc5652a1c28d74f4bc4/docs/BuildSystemMaintainers.md)；实际适配来源与 BSD 许可见上列 dart-lang/native

#### [cloudflare/boring](https://github.com/cloudflare/boring)

**固定版本 / revision：** 检索候选 `v5.2.0` / `6fdd0a54e81a1eecc76e587c685439d3f2ffdd09`

**用途与采用方式：** Android C++ 链接问题的配置检索候选；检索 `BORING_BSSL_RUST_CPPLIB`，目标 main.rs 读取失败，未采用该仓库的新实现

**许可证、署名与证据：** 本轮未复制源码或新增依赖；仅保留候选检索和[固定版本来源](https://github.com/cloudflare/boring/tree/6fdd0a54e81a1eecc76e587c685439d3f2ffdd09)，不推测未核实的许可信息

#### [slackhq/keeper](https://github.com/slackhq/keeper)

**固定版本 / revision：** 只读评估时核定 HEAD `db4a0e7af12cb92e02e1efd613d94e6d5dee037d`

**用途与采用方式：** 参考项目说明评估 Android instrumentation 测试 ABI 推断方案；未读取或复制实现、未采用插件，其 AGP 9.4.1 兼容性未核定。本项目保留原同轮 DEX/R8 证据限定规则，并通过官方 classfile API 与 TraceReferences 生成补充规则，不采用该插件的私有 AGP 接缝

**许可证、署名与证据：** Apache-2.0，原作者署名保留；[固定项目](https://github.com/slackhq/keeper/tree/db4a0e7af12cb92e02e1efd613d94e6d5dee037d)、[LICENSE](https://github.com/slackhq/keeper/blob/db4a0e7af12cb92e02e1efd613d94e6d5dee037d/LICENSE)；本轮仅评估未采用

#### [R8 官方仓库](https://r8.googlesource.com/r8/)

**固定版本 / revision：** TraceReferences 命令源码 `d3a004e326742850d743833562cc2d6ca267d117`，parser blob `06cb8149b17ec95646b185a0ade00cac7dda240f`；生产公共 API 另核 R8 `9.4.24` 固定提交 `61dc59bf271ddb314b5e0953231bd22df0ffb17e`。本地最小用例使用现有 SDK36 R8 `8.10.9-dev`，生产使用本项目 AGP 实际已加载的 R8，不新增另一个版本依赖。

**用途与采用方式：** 核对官方 `--source`、`--target`、`--lib`、`--keep-rules`、`--allowobfuscation`，从预 R8 测试 classfile 推导 app 的精确保留规则并保留混淆。公共 `TraceReferencesCommand` 与类型化诊断 API 保留官方 keep/check consumer，以全部引用上下文区分应用调用边与第三方测试依赖内部引用：项目、目标成员、产品命名空间或未知上下文缺失仍失败；可延后的第三方问题完整记录为未验证，继续接受真实 test R8 / Android 检查。未复制工具实现、未用截断日志判断上下文；最小 classfile / R8 / JVM 用例不代替完整应用验收。

**许可证、署名与证据：** R8 project authors，源码所示 BSD-style license；[固定命令源码](https://r8.googlesource.com/r8/+/d3a004e326742850d743833562cc2d6ca267d117/src/main/java/com/android/tools/r8/tracereferences/TraceReferencesCommandParser.java)、[固定公共诊断 API](https://r8.googlesource.com/r8/+/61dc59bf271ddb314b5e0953231bd22df0ffb17e/src/main/java/com/android/tools/r8/DiagnosticsHandler.java)、[官方 LICENSE](https://r8.googlesource.com/r8/+/refs/heads/main/LICENSE)；本项目 `generate_android_test_keep_rules.py` 与 `AndroidTestAppAbiTrace.java`。

#### [google/gfxstream](https://github.com/google/gfxstream)

**固定版本 / revision：** `0884d2d224bfe2e46cbb13b71d68d7c66e2f7dc9`

**用途与采用方式：** SwANGLE 诊断只读参照：核对 `host/include/render-utils/renderer_enums.h` 中 SwiftShader indirect 与 ANGLE/SwANGLE renderer enum 的区分；未复制源码，不认定为当前打包的 37.2.12 源码身份。

**许可证、署名与证据：** Apache-2.0，固定源码文件头；[固定 renderer_enums.h](https://github.com/google/gfxstream/blob/0884d2d224bfe2e46cbb13b71d68d7c66e2f7dc9/host/include/render-utils/renderer_enums.h)。

#### [google/swiftshader](https://github.com/google/swiftshader)

**固定版本 / revision：** `cddb5c91b23ff70a1f607ea31610aa0d7f4b5ad5`

**用途与采用方式：** SwANGLE 诊断只读参照：核对 SwiftShader 官方 Vulkan driver 与 SwANGLE GLES 3.1 支持说明；未复制驱动源码。

**许可证、署名与证据：** Apache-2.0；固定提交 [README.md](https://github.com/google/swiftshader/blob/cddb5c91b23ff70a1f607ea31610aa0d7f4b5ad5/README.md)。

#### [chromium/crashpad](https://github.com/chromium/crashpad)

**固定版本 / revision：** `32fbcb2a47991daac5954319bcdf1cb804282db7`

**用途与采用方式：** SwANGLE QA81 崩溃诊断只读参照：核对 `client/crash_report_database_generic.cc` 的 crash database layout 与 minidump report capture；未新增依赖、未复制源码。

**许可证、署名与证据：** Apache-2.0，固定源码文件头；[固定 crash_report_database_generic.cc](https://github.com/chromium/crashpad/blob/32fbcb2a47991daac5954319bcdf1cb804282db7/client/crash_report_database_generic.cc)。

#### [platform/external/qemu](https://android.googlesource.com/platform/external/qemu)

**固定版本 / revision：** `ae9d18d2b6261179fbd57fffec720a04f7bfb053`（35.6.3 public lineage；不代表当前 37.2.12 packaged source）

**用途与采用方式：** SwANGLE 诊断只读参照：核对 `android/android-ui/modules/aemu-gl-init/src/android/opengl/emugl_config.cpp` 中 SwANGLE 到 ANGLE libraries 与 `ANGLE_DEFAULT_PLATFORM=swiftshader` 的映射；未复制源码。

**许可证、署名与证据：** GPL-2.0，固定源码文件头；[固定 emugl_config.cpp](https://android.googlesource.com/platform/external/qemu/+/ae9d18d2b6261179fbd57fffec720a04f7bfb053/android/android-ui/modules/aemu-gl-init/src/android/opengl/emugl_config.cpp)，blob `07c1a129a599927cc2aa0a7f674c0de835c4c5e5`。

#### [JetBrains/compose-multiplatform-core](https://github.com/JetBrains/compose-multiplatform-core)

**固定版本 / revision：** `v1.12.0` / `f29d2f99f3beafc60992c6a73dbbaa9f841c5d4c`

**用途与采用方式：** Run83 Compose Android capture 诊断只读参照 `ComposeUiTest.android.kt`（blob `9997a393337e420190ac6257a7e655cbca6da9a9`，范围712–739、843–860）和 `WindowCapture.android.kt`（blob `51521d8857d6927a3201e5aa97b80fbf04e79d4c`，范围41–54、118–168）；未复制源码或新增依赖

**许可证、署名与证据：** Apache-2.0；固定来源与许可证据见 run83 `SOURCES.json`

#### [android/android-test](https://github.com/android/android-test)

**固定版本 / revision：** `edea994e9ad118c4ac6869c7105dcd06d66da7df`

**用途与采用方式：** 另参照同固定提交的 `MonitoringInstrumentation.finish`（blob `c7814fee031f0c59f3294f61518d6762f23066ad`）确定有界测试诊断的退出路径；未复制实现或新增依赖。Run83 View capture 诊断只读参照 `core/java/androidx/test/core/view/ViewCapture.kt`（blob `7dba04134f52a02595d899dd3e6251a5feb6e852`，范围122–158）；未复制源码或新增依赖

**许可证、署名与证据：** Apache-2.0；固定来源与许可证据见 run83 `SOURCES.json`

#### [Loyalsoldier/geoip](https://github.com/Loyalsoldier/geoip)

**固定版本 / revision：** 数据 commit `7ae41d7f3d4e7acc37acd1b02e554b7bb85f3a8d`；`Country.mmdb` Git blob `eb39fa51e8d4fb167f429a75cd1492f061962134`，7,649,827 B，SHA256 `ccec4d6fa69de3a79960a460c377ac9a23c1a90e5f9052dae3488921e56201df`。

**用途与采用方式：** Lite Android 内部联调使用固定 GeoIP 夹具。Git tree / blob 身份与既有官方 Meow 检查使用的真实数据库字节一致；CI 按固定 commit 获取并核对大小、SHA256 和 Git blob，不跟随 release / latest。数据不加入本仓库、APK 或发行资产；实际获取来源单独记录在测试收据中。

**许可证、署名与证据：** 源码仓库许可元数据为 CC-BY-SA-4.0，核对版本 `084eb113e458b82374919dabefbc49cd2c2dd079`、LICENSE blob `cc3e2459dc5c46f1936b6e3b54137099063818e0`；该信息不替代第三方数据库各自的条款。保留原署名和数据来源约束，不视为 SCK 的 MIT 数据；[固定数据树](https://github.com/Loyalsoldier/geoip/tree/7ae41d7f3d4e7acc37acd1b02e554b7bb85f3a8d)、[源码 LICENSE](https://github.com/Loyalsoldier/geoip/blob/084eb113e458b82374919dabefbc49cd2c2dd079/LICENSE)。

#### [MetaCubeX/meta-rules-dat](https://github.com/MetaCubeX/meta-rules-dat)

**固定版本 / revision：** 数据 commit `bfa68b978ec9c0494203d7c0d64e186640f7257b`；`geosite.dat` Git blob `453b6468c34adee2f428c569162c05471ce1d504`，4,311,865 B，SHA256 `610e2fa2b57b1232ba2220eeea45eb6e0bbf3675e19ac6ee2d29e8eb3e294b10`。

**用途与采用方式：** Lite Android 内部联调使用固定 GeoSite 夹具。固定 Git tree / blob 与既有官方 Meow 检查的真实字节一致；CI 有界获取、核验后供给显式本地资源入口，不修改原分流规则，也不冒充原 HTTP provider 下载。数据不进入本仓库、APK 或新上传的 seed。

**许可证、署名与证据：** 源码仓库许可元数据为 GPL-3.0，核对版本 `4178770badecb1b349fbcd62c737e0d7a2079729`、LICENSE blob `f288702d2fa16d3cdf0035b15a9fcbc552cd88e7`；固定数据树未含 LICENSE 文件，第三方数据仍保留各自来源、版权和许可约束，不推定新的再分发授权；[固定数据树](https://github.com/MetaCubeX/meta-rules-dat/tree/bfa68b978ec9c0494203d7c0d64e186640f7257b)、[源码 LICENSE](https://github.com/MetaCubeX/meta-rules-dat/blob/4178770badecb1b349fbcd62c737e0d7a2079729/LICENSE)。

#### [android.googlesource.com/platform/tools/base](https://android.googlesource.com/platform/tools/base)

**固定版本 / revision：** Android Gradle Plugin `9.4.1` source artifact；Maven `gradle-9.4.1-sources.jar` SHA256 `08a69084ca47b2681671a611113ed919b986facb79dc24404fb7348d2c62a386`；同版本 `builder-9.4.1-sources.jar` SHA256 `384e5cfd3aa1292a85145c40f0b4b9f4af6f963c3c93d38ef8c19eff374b159a`（没有可核实的 Git commit，故不填写 commit）

**用途与采用方式：** 历史 QA83 testAPK-only task graph 只读调查 `ProguardConfigurableTask.kt`、`AndroidTestTaskManager.kt`、`R8Task.kt` 的 task/mapping 输入关系，该次 init 候选未执行或采用。后续正式接入公开 classfile API，并参考 `ClassFileProviderFactory.java` 与 `r8Tool.kt` 的有序 library provider 语义，修复重复库类误判：按 boot / compile classpath 顺序选首个定义，同时保留源码冲突和缺失接口的严格失败；未复制 provider 实现。真实 AGP 构建已在客户端 CI 执行，修正后的完整构建仍须验收

**许可证、署名与证据：** Apache-2.0；sources jar 原版权头及 `NOTICE`，有序 provider 源文件保留 AOSP 2017 署名；`NOTICE` 解压后 SHA256 `4842d6b86dc14afab28106d484946e1f927974896772c4644030be8758405ae0`。固定 `ClassFileProviderFactory.java` SHA256 `d400669af2f4773736dd1b85b01060d5d7051ba681d9611c535498b6d3b8b412`，第 49、64–65 行为顺序选首个 descriptor 的来源依据；[官方 Gradle source artifact](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.4.1/gradle-9.4.1-sources.jar)、[官方 Builder source artifact](https://dl.google.com/dl/android/maven2/com/android/tools/build/builder/9.4.1/builder-9.4.1-sources.jar)

### 来源登记边界

版本、commit、许可证分别按表中对应证据记录；缺失信息不从仓库名或最新上游状态推定。`Cargo.lock`/Maven/Pub锁定值标识依赖时，若未核实源码commit，版本列仅陈述已固定的发行版本与锁校验值。`russh`、`shadowquic`、`boring-noise`等peer测试读取上游API但未复制其实现；只读调查也与实际运行依赖分开。Cargo、Dart SDK和网络库的只读研究来源不代表被复制进产品。

本名单不展开所有Cargo/Pub/Maven传递依赖，也不扫描缓存、class或二进制。订阅数据、用户私密URL及query token不属于来源登记内容。若新功能开发实际打开并采用了额外GitHub实现，应在同一节增补仓库、已证revision、用途、采用方式、许可证与来源证据；只有搜索命中或未被采用的外部页面不应登记成已采用来源。
## 多个来源，独立交付

| 来源 | 职责 | 纳入规则 |
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
