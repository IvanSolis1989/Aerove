# Haze 2.0.1：AEROVE Lite 原生库复用选型核验

核验日期：2026-10-22。按最新指令收敛为 GitHub 库选型，不作全仓生产审计，不等待网络补齐。只使用已取得的官方发布 API、Maven Central 元数据/AAR/source JAR、固定 commit 源码和文档；未构建、未运行库/测试/benchmark，未改项目、未启动代理、未执行下载的二进制。其他候选由各自责任人核验，本报告不代替其结论。

## 结论

**Haze 2.0.1 已正式公开发布，并且具有实际背景折射/色散实现；不能写成“未发布”或“仅模糊库”。与此同时，已发布模块里的 Glass 公共 API 仍明确要求 `@ExperimentalHazeApi`。** 稳定 release 与实验性 API 可以同时成立。

Haze 可复用 Compose 背景采集、模糊及 Glass 光学，不需要从零实现这些基础能力。但 2.0.1 的 Glass 光学边界只接收 `RoundedCornerShape`，不能直接满足 AEROVE 超椭圆或水滴的真实边缘折射。普通裁切不能替代该几何能力。当前保留为备选，与主线程首个候选 Backdrop 的选型方向不冲突；本报告未核验 Backdrop，不作未经证实的性能或功能排名。

## 1. 发布与 Maven 的实际状态

| 项目 | 已取得的证据 |
| --- | --- |
| GitHub release | `2.0.1`，ID `399025170`，`draft=false`，`prerelease=false` |
| 公开时间 | `published_at=2026-09-29T10:10:37Z`；`created_at=2026-09-29T09:24:13Z`；`updated_at=2026-09-29T10:16:10Z` |
| tag | `refs/tags/2.0.1` 直接指向 commit（非待剥离的 annotated tag） |
| 完整 SHA | `347c67a1f190b71f439f3b9ca7ffb326d0044629`，提交信息 `Prepare for release 2.0.1` |
| Maven metadata | `dev.chrisbanes.haze:haze-glass` 的 `latest`、`release` 均为 `2.0.1`；`lastUpdated=20260929100838` |
| Android 实物 | `haze-glass-android-2.0.1.aar` 实际 HTTP 200 下载，441,537 bytes，SHA-256 `a2b11bf595fd5374c6784d70876893bbc0b80daefd43ba68bf22580bbacf6cbc` |
| Android 源码 | `haze-glass-android-2.0.1-sources.jar` 实际下载，85,015 bytes，SHA-256 `efc4e04d7f00c54b487f89ffc44cdb91d6ac667492fd175283c28b3203912874` |
| 校验边界 | AAR 和 source JAR 的实际 SHA-256 与 Gradle `.module` 声明一致，并核对 Maven `.sha1`；未作签名/可复现构建验证，不把 checksum 等同独立真实性证明 |

来源：[GitHub release](https://github.com/chrisbanes/haze/releases/tag/2.0.1)、[release API](https://api.github.com/repos/chrisbanes/haze/releases/tags/2.0.1)、[tag API](https://api.github.com/repos/chrisbanes/haze/git/ref/tags/2.0.1)、[固定 commit](https://github.com/chrisbanes/haze/commit/347c67a1f190b71f439f3b9ca7ffb326d0044629)、[Maven metadata](https://repo.maven.apache.org/maven2/dev/chrisbanes/haze/haze-glass/maven-metadata.xml)、[Android module 元数据](https://repo.maven.apache.org/maven2/dev/chrisbanes/haze/haze-glass-android/2.0.1/haze-glass-android-2.0.1.module)。API 原始 JSON、Maven 文件保留在本报告末尾证据目录。

固定版 README:15 仍写 beta，但 README:90 明确描述 refraction、depth blur、tint、lighting、highlights，并标记 Glass API experimental。前者是落后文档，不能推翻 API/Maven 的公开发行事实；后者与 `HazeGlass.kt:51-53` 的真实注解吻合。

## 2. 已核实的功能与适配约束

| 事项 | 已确认事实与精确位置 | 选型意义/剩余边界 |
| --- | --- | --- |
| 输入与背景采集 | `HazeGlass.kt:53-61,84-96` 将明确的 `HazeInput` 交给 `hazeEffect`；已发布 `haze-android` source JAR 中 `HazeSourceNode.kt:318,326` 记录并绘制 `GraphicsLayer`。 | 可复用捕获体系；没有证明可无配置采集任意 Flutter/SurfaceView/跨窗口内容。 |
| 实际折射 | 已发布 Glass source JAR 的 `GlassShaders.kt:70-71` 从 `content.eval(clampSample(coord))` 采样；`:110-126` 计算 displacement，改用 `coord + displacement`，再进入 `sampleChroma`。 | 是对采样坐标施加位移的真实 shader 实现，不只是 blur 或装饰边框；未实机验收效果。 |
| 色散 | 同文件 `:22,123-126` 有 chromatic strength 和依赖 displacement 的 chroma offset、色彩采样调用。 | 有色散路径；Full/Simple 模式的具体成本和完整谱采样细节未继续核验。 |
| 内建风格 | `GlassStyle.kt:74-83` 的 Clear 定义折射、浅 blur、detail；`:85-109` Regular 也写 optics、specular、edgeShadow、ambient 和色彩响应。 | **Regular 不等于 AEROVE 的无清透光学 standard**。不能按同名或“Regular/性能 Balanced”直接映射产品三档。 |
| 任意 Shape | `GlassStyle.kt:289` 为 `shape(shape: RoundedCornerShape)`；`GlassDefaults.kt:72` 同类型；`CornerRadii.kt:35-55,152-163` 转成四角半径和 round-rect path。 | Glass 不接受一般 `Shape`/任意 Path/SDF。超椭圆、水滴的真实光学边界是明确缺口；外部 clip 只能改变可见轮廓，不能证明折射法线和 rim 随新轮廓变化。 |
| 叠层 | 固定版 `docs/core-concepts.md:19-29` 描述 source `zIndex`、`HazeSourceSelection.Behind` 和过滤；`:261-277` 给出多源层级示例。 | 具有明确层级/来源选择模型。循环采样防护、交叠 correctness 和弹窗跨窗口真实接入尚未逐条验证。 |
| 保留与“冻结” | 已发布 `HazeInput.kt:21-24` 的 Sources 默认 `Behind`、`KeepLastFrame`；固定版 core-concepts:67 描述临时来源缺失时保留上一帧。 | 这是来源不可用时的保留策略；**不能据此声称具备显式冻结动态背景的完整 API**。冻结/解冻行为未完成核验。 |
| Android API 33 | `GlassVisualEffect.android.kt:8-9` 委托 `isRuntimeShaderRenderEffectSupported()`；`GlassRenderEffectFactory.android.kt:63-69` 的 rim 使用 `RuntimeShader` 并标 `TIRAMISU`。 | 已确认 Android RuntimeShader 路径和 API 33 标注；公共 capability 函数完整 gate、所有低版本分支未继续追读，不把 minSdk23 等同全光学支持。 |
| window backdrop | 固定版 `docs/effects/glass.md:341-379`：实验性原生 window-backdrop 默认 flag=false，文档要求完整 Android **37.2** gate；采集同窗口之前绘制像素，不跨 dialog/popup/window。 | 这条新 window-backdrop 与 API33 的 source-backed RuntimeShader 路径不同。API33 不能直接等同原生 window-backdrop。本轮未核实全部 37.2 实现 gate。 |
| 失败回退 | `HazeGlass.kt:38-40`：无 source fallback 的不可用/失败 backdrop 绘制原内容；glass.md:368-370 提醒转换可能一帧；`:404-414` 描述 limited renderer 省略 refraction/blur/chromatic 等。 | 接库须明确 source fallback；不得默认旧系统仍有等价 ultra。回退首帧与保留状态的实测留待接库阶段。 |
| 主题/前景 | `HazeGlass.kt:23-35` 和 `GlassStyle.kt:29-31,45-60`：每节点响应、继承 style、系统明暗风格与 renderer 自动选择。 | 可复用样式分发，但本轮没有证据证明它自动解决 AEROVE 的前景 ink 对比/主题 primary 保色；由产品适配层统一控制。 |

上述源码均锁定完整 SHA；shader 与 HazeSourceNode 因对应 raw 文件下载不全，读取的是 **Maven 2.0.1 已发布 source JAR**，不是用 main 分支补齐。

关键固定链接：

- [HazeGlass API](https://github.com/chrisbanes/haze/blob/347c67a1f190b71f439f3b9ca7ffb326d0044629/haze-glass/src/commonMain/kotlin/dev/chrisbanes/haze/glass/HazeGlass.kt#L38)
- [GlassStyle / Shape API](https://github.com/chrisbanes/haze/blob/347c67a1f190b71f439f3b9ca7ffb326d0044629/haze-glass/src/commonMain/kotlin/dev/chrisbanes/haze/glass/GlassStyle.kt#L289)
- [四角几何转换](https://github.com/chrisbanes/haze/blob/347c67a1f190b71f439f3b9ca7ffb326d0044629/haze-glass/src/commonMain/kotlin/dev/chrisbanes/haze/glass/CornerRadii.kt#L35)
- [Glass shader 固定源码](https://github.com/chrisbanes/haze/blob/347c67a1f190b71f439f3b9ca7ffb326d0044629/haze-glass/src/commonMain/kotlin/dev/chrisbanes/haze/glass/GlassShaders.kt#L70)
- [Android RuntimeShader rim](https://github.com/chrisbanes/haze/blob/347c67a1f190b71f439f3b9ca7ffb326d0044629/haze-glass/src/androidMain/kotlin/dev/chrisbanes/haze/glass/GlassRenderEffectFactory.android.kt#L63)
- [固定版 Glass 文档](https://github.com/chrisbanes/haze/blob/347c67a1f190b71f439f3b9ca7ffb326d0044629/docs/effects/glass.md#L341)
- [实际发行 source JAR](https://repo.maven.apache.org/maven2/dev/chrisbanes/haze/haze-glass-android/2.0.1/haze-glass-android-2.0.1-sources.jar)

## 3. Compose、平台和许可证

实际 Android AAR manifest 为 `minSdkVersion=23`，AAR metadata 为 `minCompileSdk=37`。Maven Android POM 声明 Compose animation-core/foundation Android `1.12.1`、Kotlin stdlib `2.4.20`；固定 `gradle/libs.versions.toml:2,12,19,22` 的 AGP/Compose Multiplatform/Kotlin/Jetpack Compose 分别是 `9.4.1 / 1.12.1 / 2.4.20 / 1.12.1`。仓库构建用的 AGP 版本不是自动推导出的使用者最低 AGP 要求。未运行 Gradle 解析或兼容构建，不保证可直接放进当前 Flutter 宿主或较旧 Compose 工程。

这是 Compose 库，不是 Flutter 控件或传统 Android View 库。若选原生 Compose UI，可复用；若继续 Flutter，ComposeView/PlatformView、纹理/跨层背景共享和生命周期仍是单独集成工作，本轮不预设前端重写。

[POM](https://repo.maven.apache.org/maven2/dev/chrisbanes/haze/haze-glass-android/2.0.1/haze-glass-android-2.0.1.pom)、[固定依赖版本表](https://github.com/chrisbanes/haze/blob/347c67a1f190b71f439f3b9ca7ffb326d0044629/gradle/libs.versions.toml#L1)。

固定 `LICENSE:1-5` 是 Apache License 2.0，源码文件也有 Apache-2.0 SPDX，Maven POM 同样声明 Apache 2.0。可以作为开源复用候选；交付时保留适用版权、许可证/通知及修改声明，不能通过改名删除来源伪装原创。未完成所有传递依赖许可证审计。[固定 LICENSE](https://github.com/chrisbanes/haze/blob/347c67a1f190b71f439f3b9ca7ffb326d0044629/LICENSE)

## 4. AEROVE Lite 三档材质映射建议（选型层推论，未接库验证）

| 产品档位 | 最小复用路径 | 关键限制 |
| --- | --- | --- |
| 简约纯色 / none | 原生 Compose 填充与主题色；不安装 Haze 光学 modifier。 | 普通主题保留主题 primary/文本色；极光背景开关和材质档位分别控制。 |
| 柔光磨砂 / standard | 优先复用同系列 `haze-blur:2.0.1`，由统一产品适配层设填充、blur、shape。 | 不直接套 GlassStyle.regular；顶栏、Dock、菜单、控件统一无 refraction/specular rim/清透边。blur 路径尚未做真实接库验收。 |
| 流光清透 / ultra | `haze-glass:2.0.1` 的 source-backed 光学、清透参数、折射/色散。 | 需要 experimental opt-in；API能力回退须明确；超椭圆/水滴的完整光学不满足“直接复用”要求。 |

产品材质档位与库的 performanceMode 是不同维度，不能把 Fidelity/Balanced/Performance 当作 none/standard/ultra。普通主题与灵动极光应共用同一材质解析入口，防止局部控件自行恢复光学；上述是适配建议，不是已落地实现。

若超椭圆/水滴是首发硬要求，Haze 2.0.1 不应被标成开箱即用满足项；优先让首候选用真实 shape 光学样本验收。无需本轮改 Haze internals、自研 SDF 或扩大到其他仓库审计。

## 5. 性能、测试与未知项

已取得固定 `docs/benchmark-results.md:10-14,24-56`：作者报告 Pixel 8a、Android 17/full SDK37.2，区分静态/持续变化源、CPU frame duration 与 frame overrun；明确 CPU 时间不是 GPU shader duration、不同 run 顺序有超过 2ms 的变化。它是作者报告，不是本机/AEROVE 实测，本报告不引用 README 宣传数字承诺帧率。

固定 tree API（`truncated=false`）能确认 `GlassShadersTest`、`GlassRenderParamsTest`、`GlassRendererLifecycleTest`、`RuntimeShaderGlassDelegateAndroidHostTest`、`GlassRuntimeEffectAndroidCapabilityTest`、`GlassBackdropInstrumentationTest` 等测试源存在。**没有运行这些测试，也未核对全部断言或对应成功 CI，不能把“有测试文件”写成已通过。** [固定 benchmark 文档](https://github.com/chrisbanes/haze/blob/347c67a1f190b71f439f3b9ca7ffb326d0044629/docs/benchmark-results.md#L10)

未验证：API33完整能力门禁和低版本所有分支；显式冻结/解冻；任意 shape 光学扩展能力；多层/跨窗口与 Flutter 场景集成；GPU/内存/温升/中低端设备表现；实验 API 升级兼容性；依赖解析、传递许可证与可复现构建。以上都留待用户选库后的真实接入阶段，不阻止本轮作出“正式公开、有真实 Glass，但 Shape 是关键缺口”的结论。

## 6. 原始证据及收口

绝对证据目录：`D:/CodexTemp/aerove-independent-20261022/scratch/haze-library-research/`。

- `release.json`、`tagref.json`、`commit.json`、`tree.json`：官方 API 原始结果；匿名 API 遇限流后使用已配置 gh 只读获取，没有以限流判定未发布。
- `maven-glass-metadata.xml`、`maven-glass-module.json`、`maven-android-pom.xml`、Android `.module`、AAR、source JAR 和 `.sha1`：实际发行文件与校验依据。
- `pinned-source/`：固定 SHA 的已下载文本。部分 raw 下载中断；完整 tarball 读取失败，未获得完整归档。本报告明确区分已读文件和未验证项，已停止抓取/重试，不等待补齐。
- 没有改项目、版本 helper 或测试，没有 Flutter/Gradle/benchmark 执行，没有递归委派。选型结论供主线程 `NATIVE_LIBRARY_SELECTION.md` 整合；真实接库另作阶段。
