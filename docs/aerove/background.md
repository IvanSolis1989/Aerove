# AEROVE 后台工作审查

[文档](../README.md) / [标准版](README.md)

审查日期：2026-10-20。本轮目标是停止不可见界面的持续工作，保留代理转发及其必要系统服务。电池页面截图不能区分这些开销；本轮以源码与 CI 回归验收，未连接 Android 调试设备，不据此承诺电量、CPU 或省电比例。

## 确认的问题及处理

| 后台工作 | 原行为 | 本轮处理 |
|---|---|---|
| 连接、DNS 与逐连接流量遥测 | 界面隐藏后仍构造事件，经 Go、JNI、Kotlin、Dart 解码后更新历史 | 独立控制 UI 遥测，源头停止生成；取消流量观察 worker，过滤旧代际批次 |
| 日志 | 只跟随日志开关，隐藏后仍订阅 | 隐藏时真正退订，恢复前台再按设置开启 |
| 通知速度 | 熄屏已停，但亮屏切到其他应用后仍每秒查询、解码和格式化流量 | 通知不再测速：VPN 模式不挂常驻通知，仅代理模式保留一条静默、不再测速刷新的通知和停止操作 |
| 节点测速与分组刷新 | 已排队任务继续补发，delay 事件继续触发分组查询 | 取消 UI 测速队列及其 Core 请求上下文，停止 UI debounce；前台合并刷新 |
| IP 质量查询 | 已发查询仍按间隔启动后续来源 | 取消 HTTP 和后续波次，保留成功缓存，前台按需查询 |
| 自动订阅、图标、脚本与规则更新 | 每 20 分钟运行，后台也会下载、提交及 apply | 后台取消应用定时器；每项下载和提交边界检查资格，前台补查，保持单个在途循环 |
| 本地 IP 与应用标签 | 后台网络、安装包事件仍可能触发 UI 枚举 | 后台只标记失效，前台合并读取，丢弃跨后台代际的旧结果 |
| 更新状态清理 | 仅有本地下载时也启动 30 秒扫描 | 只在有 Core 更新需要超时保护时保留扫描 |
| 回前台自动更新 | 任何一次离开（含下拉通知栏）后返回都立即跑一轮订阅、图标、脚本与规则检查 | 按 20 分钟间隔到期才跑；只有被离开中断的那一轮在返回时补做 |
| 代理链循环提示 | 后台仍逐次弹出提示，帧停止时提示无法退场而在队列中累积 | 后台只记下最近一次，返回前台显示一次 |

主要入口：[Core 遥测](../../apps/aerove/core/ui_telemetry.go)、[请求观察](../../apps/aerove/core/request_traffic.go)、[事件管理](../../packages/aerove_client/lib/manager/core_manager.dart)、[应用调度](../../packages/aerove_client/lib/application.dart)、[IP 查询](../../packages/aerove_client/lib/providers/ip_quality.dart)、[代理操作](../../packages/aerove_client/lib/providers/actions/proxies.dart)、[通知](../../apps/aerove/android/service/src/main/java/com/follow/clash/service/modules/NotificationModule.kt)。

## 已有的后台渲染保护

根 `TickerMode` 已覆盖实际导航树，inactive 保留最后画面，hidden、paused、detached 隐藏页面并停止动画节拍。主题过渡自行停止截图与动画；图表没有独立动画计时器；玻璃 Host 随合成采样，没有独立刷新循环。本轮补齐 standalone Host 在 detached 时暂停和清缓存，不能将这个资源一致性缺口当作主要耗电原因。

来源：[极光与根生命周期](../../packages/aerove_client/lib/widgets/aurora.dart)、[主题过渡](../../packages/aerove_client/lib/ui/theme_cross_fade.dart)、[玻璃 Host](../../packages/aerove_client/lib/ui/liquid_glass_capture.dart)。

## 必须保留的工作与边界

- VPN 服务（由系统以前台优先级绑定）、仅代理模式的前台通知与停止按钮、socket protect、UID/进程规则查询、系统网络与 DNS 变化处理继续工作；不通过停止真实入站 listener 来休眠 UI。
- Loaded、Geo 更新终态、崩溃等控制事件保留，避免代理状态与界面失联；UI 遥测和日志指令按序号收敛，旧批次不在恢复后回灌。
- Core 自身的健康检查与代理转发继续运行；后台历史不逐条补录，返回前台读取实时连接及分组快照，不重复累计 Request。
- 每日 05:00 的后台换图沿用一次性到期任务，不增加持续轮询。
- 既有 HTTP 下载与资源编译没有公开取消入口，最多允许已发出的调用自然返回，随后拒绝续发和提交。已开始的成对文件提交或 Core 配置事务完整结束，避免损坏；已提交但待 apply 的规则在前台补 apply。
- 出口 IP 和服务探测的原生在途调用仍受原超时约束；隐藏后不再续发。macOS 现有托盘流量展示例外保持，不能把 Android 策略描述为所有平台零后台工作。

## 验收

新增回归检查源头事件关闭与控制事件保留、旧代际过滤、真实 HTTP 测速取消、日志退订、超过并发上限的队列取消、前后台切换竞态、下载提交边界及后台无动画/采样重建。Go、Flutter、Android 单元测试和打包结果以本次实际 CI 为准；当前文档不是已完成真机功耗测量的声明。

---

下一步：[标准版说明](README.md) · [文档导航](../README.md)
