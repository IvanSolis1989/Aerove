[文档](../../README.md) / [技术研究](../README.md)

# AEROVE Lite 原生玻璃库选型

阅读边界：本页保留固定来源、当时判断与未闭环记录，不能代替生产规格或实际验收。现行 Lite 为原生 Android + meow-rs、最低 API 33；共同界面和客户端功能以[双向契约](../../shared/UI_CONTRACT.md)为准，仅固定 Rust 核心源码证明不支持的能力可暂缓。

2026-10-22：按用户指示，优先复用现成原生实现。Android13+/API33为已确认的首个完整效果验证基线；本文件是源码选型记录，不是已编译或真机验收报告。

## 首个候选：Backdrop

项目：[Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)，公开版本[2.0.1](https://github.com/Kyant0/AndroidLiquidGlass/releases/tag/2.0.1)，固定源码`65ab177e90e5c1d8c62e70cf7755841982da65f6`，Apache-2.0。Maven坐标`io.github.kyant0:backdrop:2.0.1`及Android AAR已读回并核对发布校验和；制品要求`minCompileSdk=37`，接入需有效Kotlin/Compose Compiler2.4.10、Compose1.12.0和Shapes1.2.1。编译SDK37不改变API33运行验证基线。

已读固定版本源码：

- [Lens.kt](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/effects/Lens.kt)：有真实背景折射及色散着色器分支，使用RuntimeShaderEffect；并非仅透明填充或模糊。
- [LayerBackdrop.kt](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/backdrop/src/commonMain/kotlin/com/kyant/backdrop/backdrops/LayerBackdrop.kt)：通过GraphicsLayer录制与重放背景，处理源与目标坐标；外层变换的回退路径仍有待验证约束。
- [LiquidToggle.kt](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidToggle.kt)与[LiquidBottomTabs.kt](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt)：提供组合背景、拖动、镜片与按压的示例，需适配Aerove规则。

首个样板优先接入该库的捕获与光学能力，再实现Aerove的主题、三档材质和业务控件；不从零另写同类引擎，也不整套复制示例App。

## 必须保留的适配边界

1. 库示例不等于Aerove风格。示例开关的折射随按压进度变化，未按下时可绘白色实心底。清透档的轨道与滑块需要在普通、按下、开启和关闭状态分别验证，不能重现原有不统一。
2. 三档由一个材质解析器决定：纯色不采样折射；磨砂不保留折射、色散或玻璃高光；清透启用真正的背景位移。顶栏、底导航、开关、卡片和弹层遵守同一规则。
3. Lens默认只支持特定圆角矩形Shape，任意轮廓会报不支持。连续圆角的实际几何及光学边缘、自定义水滴和旗面动画需单独验证，不能仅凭传入一个Shape就宣称完整迁移。
4. 组合背景与多层采样可复用库能力；跨弹层、缩放/滚动、键盘、冻结和背景变化的正确性仍须测量。文字与图标取色、对比度、普通主题原色和极光语义由产品层拥有。
5. 保留依赖许可证与来源；优先包依赖加薄适配，确需修库时保留最小补丁及回归依据，不复制整套未固定分支。

## 比较候选

| 项目 | 已核对事实 | 本阶段用途 |
| --- | --- | --- |
| [Backdrop](https://github.com/Kyant0/AndroidLiquidGlass) | 公开2.0.1，折射/色散源码与控件示例存在 | 首个接入样板候选 |
| [Haze](https://github.com/chrisbanes/haze/releases/tag/2.0.1) | 已核验公开2.0.1及Maven实物，具有真实折射/色散；Glass API仍标实验性质，形状接口只接收RoundedCornerShape | 与Backdrop比较捕获、叠层和自定义形状；不能沿用“Haze只有模糊”的旧结论 |
| [Cloudy](https://github.com/belinwu/cloudy) | 已核对0.7.1公开Maven发行与真实折射；后景路径存在按节点快照成本，未实测 | 保留作对照，不优先引入多节点快照链 |
| [BuildItCode/LiquidGlass](https://github.com/BuildItCode/LiquidGlass) | 公开v0.8.2及GPU背景采样源码；目标AAR与完整许可证证据仍待核 | 保留层级捕获参考，暂不进入生产依赖 |
| [BlurView](https://github.com/Dimezis/BlurView) | 提供底层View背景模糊及层级更新 | 可参考磨砂，未据此确认完整液态折射 |

详细证据见[Backdrop审计](backdrop.md)、[Haze审计](haze.md)与[其他候选审计](native-alternatives.md)。项目宣称的性能和截图不直接作为AEROVE Lite的结果。

## 实际验证状态

本机已取得Android SDK36和Build Tools36，但JDK与Gradle完整下载遇到TLS传输错误，SDK37也尚未配置，未构建或运行原生样板。原自研样板已暂停，不把未完成源码当成可用库或可发布App。下一步以固定发行制品做最小依赖接入，核验实际Kotlin/Compose依赖图，再按[验收契约](../../shared/ACCEPTANCE.md)检查动态背景位移、色散、叠层、开关、导航、生命周期与同画质性能。

Lite正式界面只呈现实际支持的业务能力；缺项仅记开发文档。内部绘制fixture不进入正式App的业务导航或设置。

---

相关入口：[研究入口](../README.md) · [共享契约](../../shared/UI_CONTRACT.md)
