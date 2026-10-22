# Android 原生液态玻璃复用选型证据：LiquidGlass / Cloudy

日期：2026-10-22。范围收敛为 GitHub 现成库复用选型的来源与关键实现证据，不是候选库全仓生产审计。未修改 Aerove、AEROVE Lite 或两个冻结的自研样板文件，未运行 Gradle/模拟器/真机，未安装全局工具或启动代理。本报告不审 Kyant0、Haze 或 shayann07。

总体选型以主线程 `NATIVE_LIBRARY_SELECTION.md` 为准，首个候选是 Backdrop。主线程已核对 Haze 2 官方 `2.0.1` 公开发行，且该源码 README 将 Glass 标为 `ExperimentalHazeApi`；此处作为协作输入记录，不冒称本 agent 独立核验，也不将 Haze 2 写成未发布或仅 blur。以下建议仅比较本 agent 负责的两库，不覆盖总体候选顺序。

**建议：在这两个候选中，先以 Cloudy `0.7.1` 建立库验证工程。** 已证明其真实 Maven Central Android 包包含本次审查的 liquid-glass API，源码与固定 Git SHA 一致，Apache-2.0 来源清晰。它可以复用捕获、模糊、折射/色散和 shader 扩展机制，但不应直接认定适合生产：API 33+ backdrop 路径仍使用逐节点的栅格快照，需重点测多面板、滚动和清透画质。

**BuildItCode/LiquidGlass `v0.8.2` 是有公开正式 Release 的技术对照候选。** API 33+ 活 GPU 图层链路更贴近避免快照读回的目标；但本次未核实可解析的对应 Maven/JitPack 产物，许可证也只有 README 的 MIT 标识而缺完整文件。它目前适合作为隔离的源码验证对照，不应直接作为已核验发行依赖并入生产。不是因为缺 GitHub Release，也不是声称其全部版本都未发行。

两库默认光学几何都不是超椭圆；复用库仍需要小范围几何扩展和产品材质/交互适配。两者都没有本次 Android 实测性能数据，不作省电、低内存或已达到 Aerove 视觉一致性的承诺。

## 1. 固定来源与发行核验

| 对象 | 固定版本/commit | 实际发行和许可证据 |
|---|---|---|
| BuildItCode/LiquidGlass | `v0.8.2` / `3e30a2300ce471f8b44497df87b93beb24fc6f5e`；master 当前同 SHA，提交 2026-09-11 | [正式 Release](https://github.com/BuildItCode/LiquidGlass/releases/tag/v0.8.2)，发布时间 `2026-09-11T20:12:52Z`，draft=false、prerelease=false；无自定义二进制资产。GitHub license=null；[README:150–152][B-readme] 写 MIT，但固定树没有 LICENSE 文件。不能把 GitHub 未识别等同于作者没有任何许可声明；生产前仍须明确完整许可文本、版权和分发归属。 |
| belinwu/cloudy | tag `0.7.1` / `e3ffd8e957b2a29832c287a95665ecc540e9c7eb`；main 当前同 SHA，提交 2026-07-12 | fork 本身 Releases API 返回空列表；但 skydoves 官方 `0.7.1` tag 指向**同一个完整 SHA**。真实 Maven Central 坐标为 `com.github.skydoves:cloudy:0.7.1`，Android variant 为 `cloudy-android:0.7.1`；[POM](https://repo.maven.apache.org/maven2/com/github/skydoves/cloudy-android/0.7.1/cloudy-android-0.7.1.pom) 与固定树 [LICENSE][C-license] 均为 Apache-2.0，保留原版权/NOTICE 等适用来源。 |

Cloudy 发行核验并非照抄 README：实际下载 [Android sources.jar](https://repo.maven.apache.org/maven2/com/github/skydoves/cloudy-android/0.7.1/cloudy-android-0.7.1-sources.jar)，其中全部 **51 个 Android/common Kotlin 源文件**的 Git blob SHA 与上述固定提交逐一相同；实际 [AAR](https://repo.maven.apache.org/maven2/com/github/skydoves/cloudy-android/0.7.1/cloudy-android-0.7.1.aar) 的 classes.jar 含 `LiquidGlass_androidKt`、`LiquidGlassShaderSource`、`CloudyBackgroundModifierNode`、`SkyFrameDriver`。因此不是“fork 的玻璃源码没有发行，却误拿只有 blur 的上游包”。Central metadata 当前还列出 `1.0.0-alpha01`，本次没有将其当稳定基线。

- Cloudy sources.jar SHA256：`f93b09a62a2ad59c9414d672bf2ef2005b8ad31e7056c35cba1d34c7a48bcd4c`。
- Cloudy Android AAR SHA256：`6d9415be68c6bef0754222b62088e24325b88a6414dbad42a19257d28883d65b`，388770 bytes；这不是含全部传递依赖的 APK 大小。
- LiquidGlass 固定源码归档 SHA256：`aefec5ce78ee5faa37d33a4843152ad3b7bcafdb260afa47aef82d73602ee97e`。

LiquidGlass 发行解析仍有具体缺口：[README:23–55][B-install] 示例依赖 `com.github.BuildItCode:LiquidGlass:0.2.5`，catalog 中库版本却为 `0.8.0`，发布配置为 `com.github.BuildItCode:glass`，当前 Release 是 `v0.8.2`。[B-build][B-versions] JitPack 对 `v0.8.2` 的构建 API 返回 `status=none`、空 commit/modules；POM 请求超时，不能当作 404，更不能据此宣布所有历史发行不存在。现阶段没有证据证明目标 AAR 可重复解析、其 POM/内容对应固定 SHA。不要把任意移动分支或未经核对的旧坐标替代本次目标版本。

## 2. 背景采样、真实折射与叠层

| 方面 | LiquidGlass v0.8.2 | Cloudy 0.7.1 |
|---|---|---|
| 背景从哪里来 | `layeredBackdropSource` 将指定 Compose 子树录入 GraphicsLayer；capture 按源/目标 root 坐标裁取。API 33+ 使用源节点持有的 ping-pong layers + 稳定 publication layer。[B-source][B-hardware] | `Modifier.sky(sky)` 录制指定子树；`cloudy(sky=...)` 按坐标采样。单独 `liquidGlass` 作用于自身图层的 content，并不会自动读取任意窗口后景；需正确组合已捕获背景，并分离前景文字/控件层。[C-sky-node][C-liquid] |
| 真折射/色散 | AGSL `content.eval(sc)` 使用法线和光学参数移动采样坐标；红蓝通道在 rim 处以不同坐标重采样，保留中间绿色。不是透明填充伪装折射。[B-shader] | AGSL 计算 rounded-box SDF、法线、深度和 sampleXY；RGB 使用 xyR/xyG/xyB 分开采样，另有饱和度/对比度/tint/高光。[C-shader] |
| 模糊与光学顺序 | Hardware backend 对裁取图层使用 blur 与 RuntimeShader 的 RenderEffect chain；背景先画，capture node 最后 `drawContent()`，有条件保持前景清晰。[B-cache][B-target] | `cloudy(sky)` 的 RenderEffect 模糊与 `liquidGlass` 是独立路径。后者对所修饰图层做 shader，因此 modifier 顺序及内容层边界必须通过像素测试，不能把整个含文字组件随背景一起折射。[C-background][C-liquid] |
| 多层后景 | TriLevel/QuadLevel 明确按 lower-source 链组成背景→中景→前景→overlay，可让高层采样包含下层玻璃的结果；禁止采样自身 source。它是显式命名层拓扑，不是自动抓取全屏所有内容。[B-layers] | 每个 Sky 支持一个 recorder；同一 Sky 捕获期间，相关 cloudy overlay 整体不参与源绘制，以断开 RenderNode 循环。因此同 Sky 的 overlay 不天然成为下一 overlay 的已合成玻璃后景；复杂叠层需要多个方向明确的 Sky/层级验证。[C-sky][C-sky-node][C-background] |

这两种实现都不等于已证明能采集独立 SurfaceView/外部 surface；本次只追到 Compose/GraphicsLayer 内容链。网络硬件 bitmap、透明孔洞、键盘/弹窗/跨窗口内容仍应进入真实样板验收。

## 3. API、Compose 与生产依赖

LiquidGlass 的 minSdk 为 24；API 33+ 且硬件加速开启时选择 HardwareBackdropCapture，否则走软件路径。[B-build][B-routing] 固定 catalog 为 AGP `9.1.0`、Kotlin `2.2.10`、Compose BOM `2026.02.01`；源码构建还需 SDK 36.1、NDK `27.1.12297006`、CMake `3.22.1`，daemon toolchain 21。它不是无 native 构建链的纯 Kotlin 库。[B-build][B-versions] 这些是来源工程配置，不是声称消费最终 AAR 必须复制所有构建插件版本。

Cloudy 的 minSdk 为 23，但完整液态折射 shader 分支要求 API 33+；API 31+ backdrop 用平台 RenderEffect，低版本有 CPU/scrim 或 tint/edge 降级，不能称为相同光学效果。[C-config][C-liquid][C-background] 来源工程是 Kotlin `2.4.0`、AndroidX Compose `1.11.4`、Compose Multiplatform `1.11.1`、AGP `9.2.1`、compileSdk 37。[C-versions] 实际发布 POM 也声明这些 Kotlin/Compose runtime 依赖和 `cloudy-native:0.7.1`；AAR metadata 的 `minCompileSdk=33`。该值不自动保证所有传递依赖在旧工具链可解析，应在库验证工程锁定并验证最终依赖图。

API 33+ 仍可能携带 Cloudy 的 native fallback 依赖；要检查最终 APK 的 ABI 和库体积，不能以设备只跑 GPU 为由宣称没有 native 包负担。优先验证现成 Maven 库，确需源码适配时保留明确来源和最小 patch，避免复制整个第三方 app 或重新建立独立捕获引擎。

## 4. 缓存、读回、帧调度和生命周期

**LiquidGlass：API 33+ 主链没有强制 bitmap 读回。** Hardware backend 的 `createsFallbackBitmap=false`、`requiresContinuousCapture=false`，源/目标之间复用 GraphicsLayer；目标节点缓存 RuntimeShader、RenderEffect、uniform 参数、形状半径及记录结果，未变内容不重复构建效果。[B-hardware][B-cache] capture 默认分辨率比例为 0.4，Tri/Quad helper 为 0.6，比较性能必须固定相同采样清晰度，不能用隐含降采样冒充优化。[B-manager][B-layers]

API 24–32 的软件/硬件读回路径不同，存在 CPU bitmap/blur；`HardwarePictureReader` 会使用硬件 buffer 后复制到 ARGB_8888，不能把 API 33+ 优点推广到全部设备。软件 backend 可按 debounce 持续捕获；无区域或 `stopUpdates()` 会停。[B-reader][B-scheduling] manager 在 composition dispose 时清理，source/target detach/reset 解除 region/图层；部分释放推迟两个帧，另有 bitmap lease 防止提前 recycle。[B-manager][B-target][B-dispose] 倾斜边框的传感器有 RESUMED 注册及 PAUSE/STOP/DESTROY 注销。[B-sensor] 这些不替代宿主对“页面保留但不可见/APP 后台”的捕获停更策略；manager 的 stop/start API 仍需按页面生命周期接入并测量。

**Cloudy：GPU 模糊不意味着无快照。** 每个 `CloudyBackgroundModifierNode` 自有 `BackdropClearBlurMachine`；即使 API 33+、甚至 radius=0，绘制仍先调用 `requestIfStale`。机器按 contentVersion 在 Main coroutine 发起 `layer.toImageBitmap()`，缓存整张 Sky 快照，再 `drawImage` 采样到 blur layer。没有看到多个 overlay 共用一份快照的缓存。[C-background][C-snapshot] Android 实际实现可返回硬件 bitmap；不能因此把全部开销笼统说成 CPU memcpy，但栅格化、快照分配和同步也不能从成本模型删除。

量级示例仅为推算：若整张源为 1080×2400 RGBA8，一张像素面约 9.89 MiB；10 个独立快照约 98.9 MiB，还未含在途帧、模糊层及其他资源。这不是实测 PSS/实际 driver 分配，也不是断言每帧必定分配此数值。多 overlay 同源滚动必须实测，不能凭单个静态卡片样例认定 Lite 省资源。

Cloudy 有实质调度与回收设计：SkyFrameDriver 由 nested scroll/显式 invalidate 激活，使用时间窗口和 80ms settle tail，在无 overlay/recorder detach 时取消；不会无条件永久 frame loop。[C-driver] 非滚动动画/图片更新须主动 `sky.invalidate(durationMillis)` 等通知，不能保证所有动画被自动追踪。[C-sky] Shader 用 remember 缓存，光照在 draw 阶段读状态；node detach 清理快照、图层、blur job 和 bitmap；倾斜光传感器也有 START/STOP/Dispose 和 reduce-motion 处理。[C-liquid][C-background][C-gyro]

需定向验证的错误边界：快照 capture 异常时 `BackdropClearBlurMachine` catch 会再次 invalidate，cachedContentVersion 不推进，下一次 draw 可能继续尝试，代码未见退避。[C-snapshot] 这是源码可定位的重试风险，尚未复现为设备上的持续帧问题；样板应注入 capture 失败、源销毁和窗口后台场景，证明不会空闲重试或显示陈旧后景。

## 5. 超椭圆与产品适配

- LiquidGlass 接受 Compose Shape 并在外层 clip，但 `resolveGlassCornerRadii` 仅识别 `Outline.Rounded`，Generic 超椭圆返回全零角半径；shader 本身仍是 rounded-box SDF。[B-shape][B-shader] 传自定义超椭圆 Shape 只能改变裁剪，不能保证法线、折射 rim 和高光符合该轮廓。
- Cloudy backdrop clipping 接受 `Outline.Generic`，但 `liquidGlass` 参数只有 cornerRadius，默认 shader 的 `boxRoundedSDF` 仍为圆弧圆角。[C-background][C-liquid][C-shader] 同样不能以“支持 Shape”宣称已达到连续超椭圆的光学效果。
- Cloudy 提供公开 `LiquidGlassShaderSource`，还有 `Optic.raw` 和 `Modifier.mirage` 的自定义 shader 扩展；后者标为 Experimental。[C-extension] 可优先验证复用既有捕获/缓存并仅扩展几何函数的路线，不必从零重写整套渲染。它不是已经完成的超椭圆支持；扩展还需维护 uniform、clip、SDF/法线及抗锯齿的一致性。

现成库不能自动交付 Aerove 三档材质、普通/灵动极光、普通/选中图文透色、嵌套菜单/弹窗、连续圆角、旗面和触控/无障碍。库验证工程应保留同一产品视觉契约，只替换底层效果来源；不要让库 demo 的默认按钮、圆角或 tint 决定产品外观。

## 6. 后续接库验证事项（本阶段不执行）

建议首先锁定 Cloudy Maven 0.7.1，以两个固定来源的库版本进行同设备对照：

1. 一个真实变化的背景和独立清晰前景闭环：滚动图文/硬件图片 → 后景捕获 → 折射/色散/模糊 → 前景文字及交互 → 像素截图；区分“自身内容滤镜”和后景滤镜，禁止用半透明色替代失败光学。
2. 叠层、超椭圆和尺寸/坐标：玻璃压玻璃、弹窗/底栏/键盘、fractional translation、RTL、不同 density、透明孔洞和边缘源不足；验证同源循环保护没有吞掉应进入高层后景的内容。
3. 60/120Hz 下 1/10/40 面板的帧耗时、主线程 capture 耗时、GPU/bitmap 分配、PSS 和稳定空闲；固定分辨率、效果强度、采样比例与画质，不以低采样或少效果换取虚假优势。
4. 页面不可见但保留、切后台、关屏、窗口缩放/旋转、销毁中 capture、capture 异常、内存压力、sensor stop/reduce-motion；确认帧和资源收敛。
5. 锁定坐标/校验和/依赖和许可，生成最终 APK 并检查 native ABI/体积。LiquidGlass 先明确许可证完整来源及目标 AAR 对应关系，再考虑进入生产依赖。

源码中确实有相关 unit/device/screenshot 测试及 benchmark 文件：LiquidGlass 包含 NestedGlassRendering、硬件读取和 bitmap lifetime/cache 测试；Cloudy 包含 liquid glass、PixelCopy crash、Sky backdrop、GL roundtrip 等测试入口，且有 Android CI/publish workflow。[B-tests][C-tests] **本次没有执行这些测试或取得本机真机测量；测试文件和 README 的性能说明不等于通过证据。** LiquidGlass 固定快照未见 `.github/workflows`，不据此断言作者从未执行验证。

当前可交接的是固定来源、可用的已发行依赖证明及上述适配边界，不是生产视觉/性能验收。接库编译、实际画质、性能和生命周期行为均未验证，留给独立后续阶段。若未来选择的新增能力只存在于未发行 commit，必须单列为研究，不能用本次 0.7.1 的发行证明覆盖它。冻结样板保留原状，由主线程选型后改成库验证工程。本阶段停止新增抓取、网络重试和旁支，不等待发行解析未知项补齐。

## 7. 证据位置与源码行链接

原始 GitHub API、固定源码/逐 blob 校验、Maven POM/AAR/sources.jar 与比较结果在：`D:/CodexTemp/aerove-independent-20261022/scratch/native-alternative-research`。Cloudy tarball 两次遇 EOF/IncompleteRead 后改为 Git tree/blob API 与已发行 sources.jar；51 源文件逐 blob 校验成功，不把失败的归档当完整 checkout。没有执行下载代码。

下列 B 链接全部固定于 `3e30a2300ce471f8b44497df87b93beb24fc6f5e`，C 链接全部固定于 `e3ffd8e957b2a29832c287a95665ecc540e9c7eb`。

[B-readme]: https://github.com/BuildItCode/LiquidGlass/blob/3e30a2300ce471f8b44497df87b93beb24fc6f5e/README.md#L150-L152
[B-install]: https://github.com/BuildItCode/LiquidGlass/blob/3e30a2300ce471f8b44497df87b93beb24fc6f5e/README.md#L23-L55
[B-build]: https://github.com/BuildItCode/LiquidGlass/blob/3e30a2300ce471f8b44497df87b93beb24fc6f5e/glass/build.gradle.kts#L7-L72
[B-versions]: https://github.com/BuildItCode/LiquidGlass/blob/3e30a2300ce471f8b44497df87b93beb24fc6f5e/gradle/libs.versions.toml#L1-L18
[B-source]: https://github.com/BuildItCode/LiquidGlass/blob/3e30a2300ce471f8b44497df87b93beb24fc6f5e/glass/src/main/java/com/builditcode/glass/BackdropCapture.kt#L498-L615
[B-hardware]: https://github.com/BuildItCode/LiquidGlass/blob/3e30a2300ce471f8b44497df87b93beb24fc6f5e/glass/src/main/java/com/builditcode/glass/HardwareBackdropCapture.kt#L28-L126
[B-shader]: https://github.com/BuildItCode/LiquidGlass/blob/3e30a2300ce471f8b44497df87b93beb24fc6f5e/glass/src/main/java/com/builditcode/glass/Shaders.kt#L14-L99
[B-cache]: https://github.com/BuildItCode/LiquidGlass/blob/3e30a2300ce471f8b44497df87b93beb24fc6f5e/glass/src/main/java/com/builditcode/glass/BackdropCapture.kt#L942-L1095
[B-target]: https://github.com/BuildItCode/LiquidGlass/blob/3e30a2300ce471f8b44497df87b93beb24fc6f5e/glass/src/main/java/com/builditcode/glass/BackdropCapture.kt#L761-L840
[B-layers]: https://github.com/BuildItCode/LiquidGlass/blob/3e30a2300ce471f8b44497df87b93beb24fc6f5e/glass/src/main/java/com/builditcode/glass/TriLevelLayout.kt#L60-L112
[B-routing]: https://github.com/BuildItCode/LiquidGlass/blob/3e30a2300ce471f8b44497df87b93beb24fc6f5e/glass/src/main/java/com/builditcode/glass/BackdropCapture.kt#L1292-L1300
[B-manager]: https://github.com/BuildItCode/LiquidGlass/blob/3e30a2300ce471f8b44497df87b93beb24fc6f5e/glass/src/main/java/com/builditcode/glass/BackdropCapture.kt#L220-L328
[B-reader]: https://github.com/BuildItCode/LiquidGlass/blob/3e30a2300ce471f8b44497df87b93beb24fc6f5e/glass/src/main/java/com/builditcode/glass/HardwarePictureReader.kt#L87-L115
[B-scheduling]: https://github.com/BuildItCode/LiquidGlass/blob/3e30a2300ce471f8b44497df87b93beb24fc6f5e/glass/src/main/java/com/builditcode/glass/BackdropCapture.kt#L1771-L1808
[B-dispose]: https://github.com/BuildItCode/LiquidGlass/blob/3e30a2300ce471f8b44497df87b93beb24fc6f5e/glass/src/main/java/com/builditcode/glass/BackdropCapture.kt#L1439-L1492
[B-sensor]: https://github.com/BuildItCode/LiquidGlass/blob/3e30a2300ce471f8b44497df87b93beb24fc6f5e/glass/src/main/java/com/builditcode/glass/GlassBorder.kt#L194-L213
[B-shape]: https://github.com/BuildItCode/LiquidGlass/blob/3e30a2300ce471f8b44497df87b93beb24fc6f5e/glass/src/main/java/com/builditcode/glass/BackdropCapture.kt#L875-L890
[B-tests]: https://github.com/BuildItCode/LiquidGlass/tree/3e30a2300ce471f8b44497df87b93beb24fc6f5e/glass/src/test
[C-license]: https://github.com/belinwu/cloudy/blob/e3ffd8e957b2a29832c287a95665ecc540e9c7eb/LICENSE#L1-L16
[C-sky-node]: https://github.com/belinwu/cloudy/blob/e3ffd8e957b2a29832c287a95665ecc540e9c7eb/cloudy/src/androidMain/kotlin/com/skydoves/cloudy/CloudyBackground.android.kt#L148-L227
[C-liquid]: https://github.com/belinwu/cloudy/blob/e3ffd8e957b2a29832c287a95665ecc540e9c7eb/cloudy/src/androidMain/kotlin/com/skydoves/cloudy/LiquidGlass.android.kt#L137-L254
[C-shader]: https://github.com/belinwu/cloudy/blob/e3ffd8e957b2a29832c287a95665ecc540e9c7eb/cloudy/src/commonMain/kotlin/com/skydoves/cloudy/LiquidGlassShaderSource.kt#L38-L151
[C-sky]: https://github.com/belinwu/cloudy/blob/e3ffd8e957b2a29832c287a95665ecc540e9c7eb/cloudy/src/commonMain/kotlin/com/skydoves/cloudy/Sky.kt#L87-L180
[C-background]: https://github.com/belinwu/cloudy/blob/e3ffd8e957b2a29832c287a95665ecc540e9c7eb/cloudy/src/androidMain/kotlin/com/skydoves/cloudy/CloudyBackground.android.kt#L295-L596
[C-config]: https://github.com/belinwu/cloudy/blob/e3ffd8e957b2a29832c287a95665ecc540e9c7eb/buildSrc/src/main/kotlin/com/skydoves/cloudy/Configuration.kt#L3-L14
[C-versions]: https://github.com/belinwu/cloudy/blob/e3ffd8e957b2a29832c287a95665ecc540e9c7eb/gradle/libs.versions.toml#L1-L26
[C-snapshot]: https://github.com/belinwu/cloudy/blob/e3ffd8e957b2a29832c287a95665ecc540e9c7eb/cloudy/src/commonMain/kotlin/com/skydoves/cloudy/internal/BackdropClearBlurMachine.kt#L63-L147
[C-driver]: https://github.com/belinwu/cloudy/blob/e3ffd8e957b2a29832c287a95665ecc540e9c7eb/cloudy/src/commonMain/kotlin/com/skydoves/cloudy/SkyFrameDriver.kt#L44-L152
[C-gyro]: https://github.com/belinwu/cloudy/blob/e3ffd8e957b2a29832c287a95665ecc540e9c7eb/cloudy/src/androidMain/kotlin/com/skydoves/cloudy/GyroLight.android.kt#L95-L131
[C-extension]: https://github.com/belinwu/cloudy/blob/e3ffd8e957b2a29832c287a95665ecc540e9c7eb/cloudy/src/commonMain/kotlin/com/skydoves/cloudy/Optic.kt#L124-L142
[C-tests]: https://github.com/belinwu/cloudy/tree/e3ffd8e957b2a29832c287a95665ecc540e9c7eb/cloudy/src/androidDeviceTest
