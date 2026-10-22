# AEROVE Lite Render Lab

状态：自研尝试已暂停，源码骨架仅作开发记录，尚无Activity或可运行APK。用户要求优先复用现成原生库，后续按[玻璃库选型](../../docs/research/rendering/library-selection.md)接入固定发行版本。本目录不纳入当前治理改动的提交或任何发布。

Android 13+（API33）的独立原生视觉验证工程。它不依赖Flutter或完整版构建，不连接代理核心，不申请VPN、网络或存储权限；与正式AEROVE Lite的应用身份及发布通道分开。

用户已确认先在Android13+验证完整液态玻璃。工程只验证绘制和交互fixture，不是正式Lite客户端；界面中的测试控制不是尚不可用的代理业务功能入口。

## 构建

- JDK17。
- Gradle9.6.0；Android Gradle Plugin固定9.4.1，使用其内置Kotlin编译。
- Android SDK Platform36和Build Tools36.0.0。编译SDK36不改变API33最低运行版本。

以下是后续接库并补齐工程后的构建入口，目前骨架不能用于签收。届时在本目录创建不提交的`local.properties`，设置本机`sdk.dir`，或设置`ANDROID_HOME`。

```powershell
gradle --no-daemon :app:assembleDebug :app:lintDebug
```

输出为`app/build/outputs/apk/debug/app-debug.apk`。这是独立测试包`dev.aerove.lite.renderlab`，不覆盖完整版包，也不发布到Aerove稳定更新源。

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.aerove.lite.renderlab/.RenderLabActivity
adb shell screencap -p /sdcard/aerove-renderlab.png
adb pull /sdcard/aerove-renderlab.png
```

## 验证边界

以动态棋盘、条纹和编号文字验证背景坐标位移，检查清透档的折射/色散、开关轨道和滑块、叠层及拖动镜片。纯色不出现光学效果，磨砂不出现折射；前景文字保持清晰。前后台切换后不得保留无意义的逐帧绘制。

真实GPU结果必须在Android13+设备或模拟器上截图、逐帧检查；成功编译不证明RuntimeShader已成功编译或视觉效果达标。模拟器也不代替真机性能和能耗验收。完整对照矩阵见[验收契约](../../docs/shared/ACCEPTANCE.md)，已完成范围与缺口写入开发记录。

此阶段不据样板的假数据、包体或帧率宣称完整Lite更省内存、更省电或已达到Aerove全部视觉与功能。
