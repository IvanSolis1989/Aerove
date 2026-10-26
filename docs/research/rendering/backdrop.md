[文档](../../README.md) / [技术研究](../README.md)

# AEROVE Lite 原生液态玻璃选库审计

阅读边界：本页保留固定来源、当时判断与未闭环记录，不能代替生产规格或实际验收。现行 Lite 为原生 Android + meow-rs、最低 API 33；共同界面和客户端功能以[双向契约](../../shared/UI_CONTRACT.md)为准，仅固定 Rust 核心源码证明不支持的能力可暂缓。

本页目录：[发行、许可证与工具链](#发行许可证与工具链) · [已由源码证实的复用能力与边界](#已由源码证实的复用能力与边界) · [不能直接照搬的三处关键差异](#不能直接照搬的三处关键差异) · [叠层风险与接入验收缺口](#叠层风险与接入验收缺口) · [首帧 padding 重绘与 Lite 适配](#首帧-padding-重绘与-lite-适配) · [其他候选的结论边界](#其他候选的结论边界) · [冻结状态与证据](#冻结状态与证据)

2026-10-22；范围仅选库与接入风险。**建议优先复用 Backdrop 2.0.1 做 Android 13+/API33+ 的 Compose 视觉样板，停止继续开发独立捕获框架。** 它已有真实图层采样、折射和色散，可替代先前自研方案的基础捕获与光学管线；三档材质、超椭圆光学边界和复杂叠层仍需应用适配，不能直接把 catalog 示例当成 AEROVE 成品。

## 发行、许可证与工具链

- 实际仓库是 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)，库名 Backdrop；本轮访问 `Kyant0/Backdrop` 返回 404，未将其视为另一候选。已核验公开、非 draft、非 prerelease 的 [2.0.1 Release](https://github.com/Kyant0/AndroidLiquidGlass/releases/tag/2.0.1)，发布时间 `2026-08-26T04:00:52Z`，固定 SHA `65ab177e90e5c1d8c62e70cf7755841982da65f6`。
- Backdrop 为 [Apache-2.0](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/LICENSE)。依赖 Shapes 1.2.1 也有公开 [Release](https://github.com/Kyant0/Shapes/releases/tag/1.2.1)，固定 SHA `ad83e4ad9ca6219cb0091fdc75d9b349865bf12b`，同为 [Apache-2.0](https://github.com/Kyant0/Shapes/blob/ad83e4ad9ca6219cb0091fdc75d9b349865bf12b/LICENSE)。后续分发应保留许可证与必要声明；本轮未完成全部传递依赖许可证审计。
- 建议依赖 `io.github.kyant0:backdrop:2.0.1`，由 Android variant 解析 `backdrop-android:2.0.1`。发布的 [Gradle metadata](https://repo.maven.apache.org/maven2/io/github/kyant0/backdrop-android/2.0.1/backdrop-android-2.0.1.module) 要求 Kotlin stdlib `2.4.10`、Compose Multiplatform foundation/ui/ui-graphics `1.12.0`、Shapes `1.2.1`，与 [源码版本表 L1–8](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/gradle/libs.versions.toml#L1-L8) 一致。
- **实读公开 Android AAR：`minCompileSdk=37`，并非仅从源码 compileSdk 推断。** 只在内存读取 Maven Central AAR，先核对发布 metadata 的长度 `131025` 字节及 SHA-256/SHA-512，再读 `META-INF/com/android/build/gradle/aar-metadata.properties`，未保存或执行二进制。SHA-256 为 `009744ad39a09c886bb40261c2c3dbbb0094ec0ef680800af7d5600d37db7597`；原始 [AAR](https://repo.maven.apache.org/maven2/io/github/kyant0/backdrop-android/2.0.1/backdrop-android-2.0.1.aar)，本地证据见下方研究目录 `maven-2.0.1/aar-inspection.json` 与 `backdrop-android-2.0.1-aar-metadata.properties`。其余字段为 `minCompileMinorSdk=0`、`minCompileSdkExtension=0`、`minAndroidGradlePluginVersion=1.0.0`；最后一个字段不代表古老 AGP 能构建现代 Compose。
- 现有样板 compileSdk36 因此尚不能原样消费该 AAR；需要主线程解决 SDK37 工具链。**compileSdk37 不要求设备最低 API37，也不自动要求 targetSdk37。** 库源码 minSdk21、buildTools37 是 [自身构建设置 L11–19](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/build.gradle.kts#L11-L19)；AAR metadata 没有独立声明消费者 buildTools37。完整依赖图的最终要求待真实解析，不因此默认降库或改变 Lite 的 API33 验证范围。
- 接入起点建议 **有效 Kotlin 编译器/KGP 2.4.10 + `org.jetbrains.kotlin.plugin.compose` 2.4.10 + Compose 1.12.0 + Shapes 1.2.1**，避免混入旧 Compose BOM 强压版本。Compose Compiler 插件应与实际 Kotlin 匹配，见 [Android 官方设置](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)。本轮读取 [AGP9.4.1 POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.4.1/gradle-9.4.1.pom) 的运行时 KGP 依赖仍是 `2.2.10`；不能因已启用内置 Kotlin 就假定实际是 2.4.10。主线程可按 [官方提高内置 KGP 版本方式](https://developer.android.com/build/releases/agp-9-0-0-release-notes#upgrade-to-a-higher-kgp-version) 配置并核验有效版本，保留内置 Kotlin，勿重复应用 `org.jetbrains.kotlin.android`。这是一组建议接入版本，尚未构建证明。

## 已由源码证实的复用能力与边界

| 项目 | 固定 2.0.1 源码事实 | AEROVE 剩余适配 |
|---|---|---|
| 真正背景采样 | `LayerBackdropModifier.draw` 录制指定内容为 GraphicsLayer；`LayerBackdrop.drawBackdrop` 做坐标映射后 `drawLayer`；消费节点再次录制背景层并加 RenderEffect。[录制 L55–57](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/backdrops/LayerBackdropModifier.kt#L55-L57)、[映射 L46–66](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/backdrops/LayerBackdrop.kt#L46-L66)、[消费 L264–302](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/DrawBackdropModifier.kt#L264-L302)。 | 可取代我们尚未完成的背景录制框架；需要明确录制哪些下层内容，不能理解为自动截取所有窗口、SurfaceView 或系统桌面。 |
| 真正折射/色散 | Android actual 调用 `RenderEffect.createRuntimeShaderEffect`，将图层内容绑定到 shader 输入；shader 根据距离/梯度位移后 `content.eval`，色散分支采用七次不同坐标采样合成各通道。[Android actual L24–32](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/androidMain/kotlin/com/kyant/backdrop/internal/RenderEffect.kt#L24-L32)、[AGSL L67–158](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/internal/Shaders.kt#L67-L158)。 | 不是只画高光的模拟玻璃；但它是图像空间光学近似，未证明与完整版像素或物理模型相同。强度、色散、alpha、负坐标和屏边取样需 fixture 验收。 |
| 层级与清晰前景 | `drawBackdrop` 先背景、surface，再 `drawContent()`、front；`exportedBackdrop` 录制背景/surface/front，**不包含 child 的 drawContent**；`CombinedBackdrop` 按传入顺序合成。[顺序及导出 L316–334](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/DrawBackdropModifier.kt#L316-L334)、[合成 L49–55](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/backdrops/CombinedBackdrop.kt#L49-L55)。 | 能组织卡片→轨道→thumb→浮层；应用必须建立无环采样关系，明确是否包含下层文字/旗面/高光，避免自身进入自己的捕获源。导出不是整个组件的完整截图。 |
| API 分段 | Android `isRenderEffectSupported` 为 API31+，`isRuntimeShaderSupported` 为 API33+；`lens` 不支持时直接返回。[门禁 L6–10](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/androidMain/kotlin/com/kyant/backdrop/Platform.kt#L6-L10)、[lens L16–23](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/effects/Lens.kt#L16-L23)。 | API33+ 有所需真实 lens 路径；31–32 只有库现成的模糊路径，24–30 连 RenderEffect blur 都会被跳过。它们不具备同等 clear，后续须显式决定额外 GPU 后端/支持范围，不能静默用纯色替代。当前按用户决定只做 API33+ 验证。 |
| 缓存与释放 | shader 按 key 缓存；shape outline 按尺寸/方向/密度缓存；节点 attach 创建 layer、detach 释放并清除坐标/shader cache。[shader L10–19](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/RuntimeShaderCache.kt#L10-L19)、[outline L29–41](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/internal/ShapeProvider.kt#L29-L41)、[释放 L370–386](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/DrawBackdropModifier.kt#L370-L386)。已下载库核心未检出逐帧 `toImageBitmap/readPixels` 路径。 | 可复用资源所有权，但每个效果层仍有离屏绘制成本，export 还会额外录制。后台暂停 Aurora/动画、不可见时停帧仍归应用负责；不能由源码推定更省电或低内存。 |

## 不能直接照搬的三处关键差异

1. **三档必须统一解析。** `drawBackdrop` 默认附带 Highlight/Shadow，见 [L41–42、80–86](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/DrawBackdropModifier.kt#L80-L86)。需要应用自己的 material resolver：solid 为实色；standard 所有表面（含顶栏/底导航）只用磨砂和必要填色，显式禁 lens、rim/highlight；clear 在常态/按压态均为清透采样，switch 轨道与 thumb、出站三按钮及状态卡框各自遵循同档规则。普通主题保留文字/图标原色；Aurora 才启用图文透色，solid 也只弱取色。库不提供这些业务语义或 Aurora ink 门禁。
2. **Toggle 示例恰好违反 clear 常态要求。** 固定版本的实际行号为 [LiquidToggle L158–165](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidToggle.kt#L158-L165)：lens 强度乘 `pressProgress`；[L195–197](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidToggle.kt#L195-L197) 的白色 surface alpha 为 `1-progress`，未按时完全实心；轨道 [L121–129](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidToggle.kt#L121-L129) 是颜色绘制。必须重接 material resolver，而不是复制 demo 后只换配色。导航 demo 也显式使用 lens，不可直接拿作 standard。
3. **连续轮廓不等于连续轮廓的光学。** [Lens L62–120](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/effects/Lens.kt#L62-L120) 只接受 RoundedRectangularShape/CornerBasedShape，任意 Path 会抛 UnsupportedOperationException；实际 [SDF L33–46](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/internal/Shaders.kt#L33-L46) 是圆角矩形。Shapes 能画连续曲率 Path（公开 sources 的 RoundedRectangleOutline.kt:40–41），但该轮廓仍被 lens 简化成四个半径；不能宣称与 Flutter superellipse 的折射法线一致。RoundedRectangularShape 还是 sealed interface，不能假设应用可随意实现它。最小适配方向是保留库捕获/缓存，通过公开 [runtimeShaderEffect L19–33](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/effects/RenderEffect.kt#L19-L33) 接入匹配超椭圆/水滴形变的 SDF/梯度与 Path，或对库做小而可维护的扩展；这是待验证的适配成本，本轮不实施。Shapes 的连续轮廓也需先与完整版几何对齐，不能改用普通 RoundedRect 通过验收。

## 叠层风险与接入验收缺口

- 固定源码 [LayerBackdrop L53–62](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/backdrops/LayerBackdrop.kt#L53-L62) 首选 `localPositionOf`，异常 fallback 仅减 `positionInWindow`，仍有外层 transform 定位 TODO。跨 Window 的两个 window-local 坐标不能据此认定已统一；缩放、非中心 transformOrigin、旋转和菜单需实测。公开 [Popup 定位 issue #91](https://github.com/Kyant0/AndroidLiquidGlass/issues/91) 仍开放，原报告是 2.0.0-alpha03；[循环渲染 issue #100](https://github.com/Kyant0/AndroidLiquidGlass/issues/100) 也开放。它们是必须覆盖的集成风险，本轮没有复现证明 2.0.1 必然失败。
- 公开稳定 Release 可固定依赖，却不构成我们的生产验收。优先验证同一 Window 的显式背景→下层 surface→上层镜片采样图；真实跨 Window Popup 单列验证，不能把同窗口浮层通过当成 Popup 通过。旗面完整居中、代理组接缝、导航重叠及 superellipse 首帧/动画均不由此库自动保证。
- 最小复用样板保留动态棋盘/编号文字、连续角卡片、独立轨道/thumb、可拖浮层；测三档×普通/Aurora×深浅色，以及 clear 常态/按压态。定格同一背景帧比较镜片内外编号位移与色散，确保前景不被自身折射、standard 无清透边、solid 不漏玻璃、Aurora 换色会影响 ink 而普通主题不受影响；拖至负采样/屏边、缩放与菜单重叠检查坐标和 alpha。再以同视觉/负载的 Macrobenchmark、Perfetto、PSS 和停帧/后台耗电证据判断成本，不承诺包体、内存、CPU 或耗电收益。本轮未进行这些运行验收。

## 首帧 padding 重绘与 Lite 适配

2026-10-26；适用于 Lite 柔光磨砂（`liteFrostBackdrop`）与无 haze 的流光清透（`liteGlassBackdrop`），两者都经 `LitePane` 进入固定 2.0.1 的 `drawPlainBackdrop`。简约纯色传空 effects；页面顶栏 `LiteHeaderBackdrop`（`LitePageChrome.kt`）的 effects 只有 `blur(12.dp.toPx())`，Clamp 模糊在 renderEffect 为空时不改 padding（[Blur L16–20](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/effects/Blur.kt#L16-L20)），padding 始终为 0；haze 清透走自有 `drawWithCache` 路径。三者都不涉及本节机制。

**源码与字节码已证实的机制**

- `DrawBackdropNode` 把 `padding` 存为快照状态（[L262](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/DrawBackdropModifier.kt#L262)）。attach 时 `observeEffects` 以未指定尺寸、density 1 运行 effects（[L370–375](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/DrawBackdropModifier.kt#L370-L375)、[默认值 L25–29](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/BackdropEffectScope.kt#L25-L29)、[apply 先清零 L59–63](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/BackdropEffectScope.kt#L59-L63)）。
- 首次 draw 因尺寸和密度变化在绘制中再次运行 effects 并写入 `padding`（[L316–319](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/DrawBackdropModifier.kt#L316-L319)、[L362–368](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/DrawBackdropModifier.kt#L362-L368)），随后录制背景层时在同一绘制中读取它（[L264–304](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/DrawBackdropModifier.kt#L264-L304)）。值一旦在这次绘制中改变，帧后的快照通知就会使读取它的绘制作废，背景层再完整录制一次并重算 RenderEffect。重组更新元素时（[L174–187](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/DrawBackdropModifier.kt#L174-L187)、[L354–360](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/DrawBackdropModifier.kt#L354-L360)）也会在绘制外重跑 effects。
- 实际依赖的字节码与上述源码顺序相同：Maven Central `backdrop-android-2.0.1.aar`（SHA256 `009744ad39a09c886bb40261c2c3dbbb0094ec0ef680800af7d5600d37db7597`，见上文发行一节）内的 classes.jar，SHA256 `8236d5feddae1a85070b60d967aa8700cd191a49619a3b3ab0db969db54b873e`。
- Compose 时序：Lite 依赖的 ui-android 1.12.0 AAR（SHA256 `3d2d83c96546c380cbc582329ece0da74a4f9267d52c6dead69c2272bfce0313`，与 Google Maven 发布的 `.sha256` 一致）内的 classes.jar（SHA256 `702782133e1d8eaaae4b6f95c6b96abe6d5639132261f3d36b7b49b5523b5624`）中，`AndroidComposeView.dispatchDraw` 先 `measureAndLayout`，再于绘制前调用 `Snapshot.notifyObjectsInitialized()`。runtime-android 1.12.0 AAR（SHA256 `4002a4a17696cd182f080fe3663490e4f327f285c569ed844f1c435b8b772ba7`，同样与发布的 `.sha256` 一致）内的 classes.jar（SHA256 `97db61153f3a0be95868d5b23ac78e1d3901d0b810f0fdcc908ff30475f16bb0`）中，全局快照的该方法即 `advanceGlobalSnapshot`。在同一 runtime 的 JVM 重放中，绘制前写入的位置状态不会使本次绘制作废，绘制中改变的 padding 会使它作废一次。
- 原 Lite effects 只在尺寸大于 0 时写入 padding，attach 时为 0。按上述机制，每个磨砂和清透节点的首帧都会多一次完整重算。

**验证状态**

#113 trace 记录新外观首帧之后出现了没有 recompose 和 layout 的 B 帧；现有材料没有证明它就是本节描述的 padding 重算。所述替代解释与已核对字节码和重放不符，但缺少 CI atrace 对因果关系的确认。本页只记录证据边界，不据此预测后续帧数或耗时。

**Lite 现行处理**

- `LiteOptics.kt` 中，磨砂和清透的 effects 分别由非组合函数 `frostBackdropEffects`、`glassBackdropEffects` 生成，两者都经共用的 `plainBackdropEffects`。每次 effects（未测量、空尺寸、已测量）都先报告同一个 padding，只在已测量且非空时运行视口裁剪、模糊和清透的玻璃着色器。两个组合函数在 effects 上只做一件事：以 `LocalDensity.current` 调用生成函数，把结果交给 `drawPlainBackdrop`。
- 同一个 style 同时生成 padding 与模糊半径，调用方不能分别提供两套 style；共用绘制路径在实际 blur 前要求 radius≤padding，拒绝不一致密度或未来失配。padding 用组合期密度计算（`frostBackdropPadding`、`glassBackdropPadding`），公式与 98d0c12e 相同。模糊半径为 `backdropBlurRadius`，即 98d0c12e 的 `style.blur * density`；padding 不小于它，而 Kyant `blur()` 只在半径大于 padding 时才提高 padding（Blur L16–20）。组合期密度等于绘制密度，依据是 Lite 所有 `LocalDensity` 覆盖都保留 `density.density`；这一点为源码核对，不是运行结果。
- 空尺寸面板也报告该值，Kyant 因此为它录制一个 (2×padding)² 的背景层（不带 RenderEffect），原先是 0×0 层。内容平移与 `topLeft` 相互抵消，节点自身的裁剪层和 `LitePane` 的 `clip(shape)` 仍按 0×0 轮廓裁剪。“不可见”是按源码推导的结论，没有测量过；十二宫格稳态里没有空面板，CI 像素比对覆盖不到这条路径。
- 着色器、模糊、视口裁剪、tile、玻璃参数、5 秒帧提交门槛和断言均不变，不降低画质。清透的几何解析现在排在视口裁剪和模糊之后；它只是纯计算，不改变效果链的顺序。
- 升级 Kyant 时，须重新核对上述时序和下述测试的重放顺序。测试 Proxy 依赖 2.0.1 的 sealed 接口 class 文件没有 PermittedSubclasses 属性；若升级改变该条件，测试会显式失败。

**JVM 测试已证明的范围**

`LiteBackdropPaddingTest` 基于 Compose runtime 1.12.0，协程 debug 开、关两种模式都运行。

- Kyant 的 `BackdropEffectScope` 实现类是 internal，测试用 `java.lang.reflect.Proxy` 实现这个接口，并按 Kyant 2.0.1 的顺序重放：attach（density 1、未指定尺寸）、重组触发的 effects 重跑、首帧、尺寸变化、重新 attach。每帧开头调用 `notifyObjectsInitialized`。
- 被执行的是生产的 `frostBackdropEffects` 和 `glassBackdropEffects`（视口由测试提供并返回空，清透不带按压光晕和边缘高光），覆盖 1、2.625、3.5 倍密度和四种样式，断言三点：重绘 0 次；存储的 padding 等于组合期 padding；视口裁剪只在已测量且非空时调用，tile 带同一 padding。
- 对照组有三种：只在已测量时报告、空尺寸不报告、按效果作用域密度计算 padding。三种各重绘 1 次，证明重放能发现这三类问题。
- 内置样式在 0.75–4 倍密度下，padding 都覆盖 `backdropBlurRadius`。
- 源码结构断言：两处组合函数都以 `LocalDensity.current` 调用上述生成函数；`LiteOptics.kt` 里只有 `plainBackdropEffects` 写 padding、调用 `blur`，半径取自 `backdropBlurRadius`。
- 本轮变异覆盖沿用 22 个原变异，并新增复核者的 X1/X2（分别修改磨砂或清透生成函数的 style）及删除实际半径保护；结果以外部 lite-stack 检查报告为准。旧 v3 的 X1/X2 曾存活，不把旧的 22 种写法结论扩张为全部可能回归。
  - 由执行测试杀死：
    - 只在已测量时报告；
    - 空尺寸不报告；
    - 从不报告；
    - 磨砂或清透在 effects 内按作用域密度计算 padding；
    - 清透在未测量和空尺寸时清零 padding；
    - 视口 tile 不带 padding；
    - 空尺寸也运行已测量分支；
    - 压低磨砂或清透的 padding 公式；
    - 放大 `backdropBlurRadius`；
    - 生产生成函数单独改变 style（X1/X2），使实际 padding 偏离原契约；
    - 删除生产路径在模糊前执行的半径保护。
  - 只由源码结构断言杀死：
    - 把 98d0c12e 的磨砂、清透或两者的 effects 原样贴回组合函数；
    - 组合函数改传 `Density(1f)`；
    - 磨砂不再跟踪视口的调用点形式（tracking 适配器内部不由 JVM 验证）；
    - 共用的 `blur` 调用或 `sigma` 放大 4 倍；
    - 磨砂或清透另加放大的 `blur`；
    - 清透着色块自行写 padding。

**JVM 无法证明、仍待 CI 或设备的部分**

- 两个组合函数本身（读取 `LocalDensity`、把 effects 交给 Kyant）在 JVM 上无法运行，只受上面的源码结构断言约束。这是静态检查，不是运行结果。
- JVM 单元测试中 `Build.VERSION.SDK_INT` 为 0（android.jar 存根的类初始化只给它赋 0），Kyant 的 `blur`、`effect`、`runtimeShaderEffect` 在测试中都直接返回。因此以下行为都没有在 JVM 上执行：模糊半径超过 padding 时 Kyant 在绘制中提高 padding、RenderEffect 链、着色器参数。实际半径保护由 JVM 执行，Kyant 自己的 blur 与 RenderEffect 仍不在 JVM 执行；ViewportClamp.tracking 适配器及清透几何解析由设备检查。
- **未完成验收**

本节所列 JVM 测试证据不能替代 Lite host119 之后的完整 Android 十二宫格、CI `testReleaseUnitTest`、atrace、实际帧数与耗时读回。现有材料没有提供这些项目的最新实际结果；本页不推断原因已修复，也不预测帧时或是否满足 5 秒门槛。

**两版对照**

- 受影响的只有 Lite 依赖库的时序。入口是 `apps/aerove-lite/app/src/main/java/dev/aerove/lite/ui/LiteMaterial.kt` 的 `LitePane` 和 `LiteOptics.kt`；共同界面、交互、功能和材质参数都不变。
- 标准版的玻璃由 `apps/aerove/lib/ui/liquid_glass.dart` 的 `_RenderLiquidGlass.paint` 在绘制时按当前尺寸构造，空尺寸直接清除。它不使用 Kyant Backdrop，也没有会把绘制中写入转成下一帧的快照通知。
- `LiquidGlassPane` 的 `onReady` 只在共享着色器程序加载完成时（`_GlassProgram.request`）重绘一次，是一次性的就绪重绘，不是绘制中写入。
- 因此标准版没有对应路径，无需改动；本项不涉及内核例外。

**开发前检索**

- GitHub issue 检索 `DrawBackdropNode padding`，无结果。在 Kyant0/AndroidLiquidGlass 中检索 `padding`、`redraw OR recompose OR invalidate`，未见相关 issue 或修复。
- 默认分支 `kmp` 的头提交即 2.0.1 固定的 `65ab177e`；`DrawBackdropModifier.kt` 最后一次改动是 2.0.1 之前的 `7c37be46`。
- 代码检索 `drawPlainBackdrop`，找到 kushagrasinghx/BitChord 和 suqi8/OShin 的内置副本。它们仍以快照状态保存 padding 并在绘制中更新，同样没有修正。
- 结论：没有可复用的实现，在 Lite 调用点适配；不改库、不复制源码。

## 其他候选的结论边界

主线程已形成 NATIVE_LIBRARY_SELECTION.md，并确认 Haze2 有公开正式 2.0.1，所查源码 README 将 Glass 标为 ExperimentalHazeApi。本线程未独立核验 Haze2 的固定源码、Glass 实现或制品依赖，不作同级比较；不能将 Haze2 写成未发布或一概仅支持 blur。后续是否作为对照由真实接库验证阶段决定，本报告的首选仅基于已取得的 Backdrop 证据。

## 冻结状态与证据

- 自研只写过 `prototypes/aerove-lite-renderer/app/src/main/java/dev/aerove/lite/renderlab/Superellipse.kt`、同目录 `GlassPane.kt`，以及 `app/src/main/assets/renderlab/glass.agsl`，现已冻结且保留。检查时尚无 RenderLabActivity，未形成可运行样板；本轮未补写入口、未改 Gradle/manifest/品牌。
- 冻结文件 SHA-256 依次为 `2EFE5788F687BA6A90379B7A8B7E28018B5893CCBB609B9708EBA645C1B5A9B7`、`80134F30312C170ADBB3FDF8380400057230DD1C13D745748A768985BFD5B5AC`、`5A39C1726E20EB7EEE4BFF919A465F737E6F2743C1B4E9ED628B9F8219EE4DB9`。没有构建/启动 App、Flutter、代理或依赖代码执行，没有 stage/commit/push。
- 研究材料已从误放的仓库 scratch 目录移到 **`D:/CodexTemp/aerove-independent-20261022/native-library-research`**。2.0.1 的 44 个选定源码/文本文件与 GitHub tree blob SHA 完全一致，附 provenance.json / integrity.json；对照 1.0.6 只读了 7 个文件，不能视为更低版本接入验收。AAR 在核对发布校验和后只读内部文本，源 jar 也先校验再提取文本；未下载 demo APK、未执行第三方二进制。
- 主线程已报告当前 SDK36/buildTools36 可用，JDK/Gradle 下载 TLS 阻断，SDK37 工具链待解决；本线程未重试工具安装。当前交付是**源码及公开制品证据支持的复用选型**，构建、有效 Kotlin/Compose 依赖图、设备像素与性能仍未验证。AEROVE 完整版继续生产；AEROVE Lite 独立产品与 ID/数据/渠道，最终可用功能和视觉仍以完整版为基线。缺失核心能力记开发文档，不在 App 暴露功能入口、灰置占位或待补齐提示；不支持的配置导入仍明确校验。

---

相关入口：[研究入口](../README.md) · [共享契约](../../shared/UI_CONTRACT.md)
