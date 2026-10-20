# Aerove 默认设置审查

本轮默认值以国内日常使用的兼容性、稳定性、安全和设备资源为取舍：保留截图指定的选择，补齐 Core、VPN、DNS 的 IPv6 开启链与 IPv6 fake-ip 路由；不更换 DNS 服务器、17 项系统代理排除域名或现有传输性能默认。

审查日期：2026-10-20。代码基线为 `0fae92f` 后本轮修改；mihomo 固定版本为 [`8380d0e7462f061a964323e9bf3129a236fcc5d5`](https://github.com/chen08209/Clash.Meta/tree/8380d0e7462f061a964323e9bf3129a236fcc5d5)。下表按唯一字段归类，重叠的 DNS、VPN 和常规设置不重复计数。“保留”表示维持当前选择，“修改”表示本轮补齐默认链；`null`、空列表和“未选择覆盖键”有不同含义。

## 默认值如何生效

- 新安装、重置及缺失字段使用默认值；已保存的明确选择优先。不会在升级时强制把所有用户改为 IPv6 开启。
- 普通订阅先读取源配置、执行其已关联脚本，再合并客户端选择的 DNS/TUN 覆盖。模型里显示一个值，不代表该键必然覆盖订阅。
- 自定义配置使用自身 `ProfileOverrides`；应用级 TUN 覆盖按现有合并规则参与。普通配置的 DNS 覆盖限于允许的键。
- Core 可热更新的字段走 `UpdateParams`；DNS、Hosts、Geo 加载器和出站参数等走重新装配配置。Android VPN 启动参数变化沿既有通知提示用户重启 VPN；继续使用现有重启提示流程。
- Android 首次预设只在空偏好、空文件目录和无既有数据的条件下使用；完成的初始化 journal 不重做。既有订阅和已删除的默认脚本不重新绑定。

实现入口：[模型](../lib/models/config.dart)、[Core/DNS/TUN 模型](../lib/models/clash_config.dart)、[配置装配](../lib/common/task.dart)、[配置来源](../lib/providers/state/profile.dart)、[Android VPN 参数](../lib/providers/state/system.dart)、[首次预设](../lib/common/xclash_defaults.dart)；路径说明见[设置变更规则](../.agents/setting-changes.md)。

## 常规、应用与界面偏好

`AppSettingProps` 的完整字段映射如下。与网络相关的代价写在对应行；纯界面偏好不以“国内加速”为由调整。

| 字段 / 页面 | 默认值 | 国内使用取舍 | 平台 / 生效边界 | 决定 |
|---|---|---|---|---|
| `AppSettingProps.locale` / 工具/语言 | `null` | 跟随系统覆盖国内中文系统，也保留海外设备语言；不硬设zh_CN。 | 全平台；空值跟随系统；ThemeManager.locale→MaterialApp | 保留 |
| `AppSettingProps.dashboardWidgets` / 仪表盘/编辑 | `["currentOutbound","networkSpeed","outboundMode","vpnButton","networkTakeover","intranetIp","serviceStatus","profiles","proxyGroups","connectionStats"]` | 展示偏好不改变路由；保留当前产品默认，不以国内网络为理由增减后台探测。 | 按设备布局展示；dashboardStateProvider→Dashboard | 保留 |
| `AppSettingProps.onlyStatisticsProxy` / 常规/连接 | `true` | 截图明确开启；统计代理口径，不构成只代理这些流量。 | 全平台；CommonAction.updateTraffic→Core.getTraffic/getTotalTraffic | 保留 |
| `AppSettingProps.showNotificationStopAction` / 常规/启动与后台 | `true` | 保留截图，便于随时停止VPN；不是后台自动运行开关。 | 仅Android显示；sharedState→Service notification | 保留 |
| `AppSettingProps.autoLaunch` / 常规/启动与后台 | `false` | 不替用户注册自启动，避免登录后意外接管网络。 | 仅桌面显示；safe mode禁用主机集成；WindowManager→AutoLaunch launcher | 保留 |
| `AppSettingProps.silentLaunch` / 常规/启动与后台 | `false` | 首次使用应可见并能完成授权和配置。 | 仅桌面显示；Bootstrap._initApp→window.show/hide | 保留 |
| `AppSettingProps.autoRun` / 常规/启动与后台 | `false` | 保留截图；避免没有有效订阅或首次授权时自动接管。 | 全平台；SetupAction.initStatus→setRunning | 保留 |
| `AppSettingProps.openLogs` / 常规/日志与诊断 | `false` | 截图明确关闭；减少高频事件传输和日志隐私暴露；按需诊断再开，不影响错误通告。 | 全平台；CoreManager._syncLogStream→Core.startLog/stopLog；导航隐藏日志入口 | 保留 |
| `AppSettingProps.closeConnections` / 常规/连接 | `true` | 截图明确开启，切换后已有连接尽快跟随；代价是下载/通话可能断连，不能称无损切换。 | 全平台；成功切换代理且changed后→Core.closeConnections；关闭时resetConnections | 保留 |
| `AppSettingProps.testUrl` / 常规/连接 | `https://www.gstatic.com/generate_204` | 截图优先；这是代理节点测试目标，不等于国内直连连通检测；不能因国内可达性未实测擅换。 | 全平台；delayTest使用；SetupParams.testUrl在强制apply/重启/其他apply时传Core | 保留 |
| `AppSettingProps.tabAnimation` / 外观/页面切换 | `slide` | 视觉偏好不决定网络质量；保留当前产品选择。 | 全平台；Home/ThemePreview呈现 | 保留 |
| `AppSettingProps.floatingNavigationBar` / 外观/导航栏 | `true` | 保留现有UI默认，性能单独由UI责任域验证。 | 布局依屏宽；Home呈现 | 保留 |
| `AppSettingProps.autoCheckUpdate` / 常规/请求与更新 | `true` | 截图优先，及时获得修复；不是自动安装，GitHub国内直连可达性不能保证。 | 全平台；Bootstrap启动→CommonAction.autoCheckUpdate→Request.latestRelease；用户确认后下载/打开发布页 | 保留 |
| `AppSettingProps.sidebarExpanded` / 桌面侧栏 | `true` | 不改变网络请求；保留。 | 仅有侧栏的布局；AppManager/布局 | 保留 |
| `AppSettingProps.acceptedDisclaimerVersion` / 首次使用协议 | `0` | 内部接受记录，不预先替用户同意。 | 全平台；Bootstrap协议门槛 | 保留 |
| `AppSettingProps.minimizeOnExit` / 常规/启动与后台 | `true` | 保留截图，避免误关中断代理；明确真正退出仍由退出入口控制。 | 全平台；桌面窗口和移动后台行为不同；SystemAction.handleExit→隐藏/退出协调 | 保留 |
| `AppSettingProps.hidden` / 常规/启动与后台 | `false` | 保留截图，用户可在最近任务发现并控制应用。 | 仅Android显示；AndroidManager→app.setExcludeFromRecents | 保留 |
| `AppSettingProps.developerMode` / 开发者选项 | `false` | 开发诊断能力不是国内网络必需项，不默认开启。 | 全平台；Tools/Developer高级功能可见性 | 保留 |
| `AppSettingProps.restoreStrategy` / 备份与恢复 | `compatible` | 默认兼容合并避免覆盖已有数据；不能为网络优化改为破坏性覆盖。 | 全平台；备份导入/数据库restore按策略 | 保留 |
| `AppSettingProps.showTrayTitle` / 托盘/速度统计 | `true` | 显示采样结果不改变路由，保留。 | 桌面托盘；macOS支持速度标题，其他平台能力不同；CommonAction.updateSpeedStatistics→AppTray | 保留 |
| `AppSettingProps.checkCertificate` / 常规/请求与更新 | `true` | 安全约束及截图明确开启；证书错误应修复时钟/信任/服务端，不以国内网络为由全局放行。 | 全平台；HttpOverrides.badCertificateCallback拒绝不可信证书；UpdateParams/SetupParams.skip-cert-verify=false | 保留 |
| `AppSettingProps.userAgents` / 常规/用户代理 | `["clash-verge/v2.4.2","ClashforWindows/0.19.23"]` | 两个条目仅供用户选择，并非当前默认UA；不盲目冒充第三方或提升版本。 | 全平台；备选列表；实际所选为PatchClashConfig.globalUa | 保留 |
| `AppSettingProps.filters` / 筛选/代理自定义成员 | `["Hong Kong","Taiwan","Japan","Singapore","United States","South Korea","United Kingdom","Remaining Traffic","Expiry Date","Traffic Reset","Website & Notices"]` | 只是可用筛选模板，不是路由规则或自动选择最快节点；地域和元信息兼容现有订阅。 | 全平台；本地分组筛选表达式 | 保留 |
| `AppSettingProps.hideIp` / 仪表盘/当前出口 | `false` | 显示偏好，截图未要求；保留，分享屏幕时可手动隐藏。 | 全平台；CurrentOutbound IP显示 | 保留 |
| `AppSettingProps.editorLineWrap` / 配置编辑器 | `false` | 配置原始结构更易查看，不影响运行语义。 | 全平台；EditorView呈现 | 保留 |
| `AppSettingProps.editorFontSize` / 配置编辑器 | `standard=16dp` | 可读性偏好；不构成网络优化。 | 全平台；EditorView字号 | 保留 |
| `AppSettingProps.serviceOrder` / 仪表盘/服务状态 | `[]` | 不根据国内网络臆造探测优先级。 | 全平台；ServiceStatus卡片排序；空列表使用内置顺序 | 保留 |
| `AppSettingProps.disabledServices` / 仪表盘/服务状态 | `[]` | 属于探测卡片控制；空列表保留内置服务；本轮不额外开启后台探测或编造国内排名。 | 全平台；ServiceStatus用户隐藏/禁用列表 | 保留 |
| `AppSettingProps.tableColumnWidths` / 表格列宽 | `{}` | 用户操作状态，不预填设备相关宽度。 | 桌面表格；布局读取/存储 | 保留 |

其中 `filters` 的地区及流量提示项为本地筛选模板，不是分流规则；具体正则见[模型中的 defaultFilters](../lib/models/config.dart)。`dashboardWidgets` 是卡片列表，不表示所有诊断任务持续后台执行。免责声明接受版本、列宽和服务排序属于使用状态，不预造用户操作。

TLS 校验保持开启：Dart 的不可信证书回调返回 `false` 时拒绝连接，Core 的证书宽松模式也保持关闭。证书错误应定位时钟、信任链或服务端，不能全局关闭校验来换取表面可用。[Dart 官方证书回调](https://api.flutter.dev/flutter/dart-io/HttpClient/badCertificateCallback.html)、[本地 HTTP 实现](../lib/common/http.dart)、[Core 证书实现](../core/certificate.go)。

## Core、入站、连接与日志

| 字段 / 页面 | 默认值 | 国内使用取舍 | 平台 / 生效边界 | 决定 |
|---|---|---|---|---|
| `PatchClashConfig.mode` / 出站模式 | `rule` | 国内直连与按需代理由规则决定，避免无依据全局绕远 | 热更新；完整配置也写入 | 保留 |
| `mixedPort` / 常规→端口 | `7890` | 保持截图及现有本机客户端兼容 | 混合 HTTP/SOCKS；Android VPN HTTP 代理使用它时变更需重启提示 | 保留 |
| `port / socksPort / redirPort / tproxyPort` | 均 `0` | 无具体用途不增加额外监听和端口占用 | 重新装配；`0` 为关闭 | 保留 |
| `allowLan` | `false` | 日常本机使用不对局域网开放代理 | 热更新 Core 监听 | 保留 |
| `externalController` | `close` | 应用自有 IPC 不需要额外公开 REST API | 热更新；需要外部面板时再开启 | 保留 |
| `secret` | 空字符串 | 关闭控制器时不预设共享口令 | 开启控制器且无密钥时生成32位随机密钥 | 保留 |
| `NetworkProps.authentication.enable / username / password` | `false / '' / ''` | 保持截图；本机使用不要求额外认证 | 开启且账号空时随机生成；启用认证会抑制 Android VPN HTTP 系统代理 | 保留 |
| `unifiedDelay` | `true` | 不同代理协议的延迟比较更一致 | 热更新；不是带宽加速承诺 | 保留 |
| `tcpConcurrent` | `true` | 多解析地址竞争首个成功连接，容忍部分地址质量不佳 | 热更新；增加短时握手与 socket 开销 | 保留 |
| `findProcessMode` | `always` | 保留截图，支持进程规则和连接归属 | 有查询开销；Android10以下不能保证取得 UID；应用枚举仅 always/off | 保留 |
| `ipv6` | `true` | 保留 IPv6 通路 | Core 顶层，与 DNS、Android VPN IPv6 独立 | 保留 |
| `hosts` | 应用表为空 | 无可验证映射不预造静态 IP | 合并脚本 Hosts 后应用同名项最后生效；脚本引导 Hosts 见 DNS 小节 | 保留 |
| `globalUa` | `null`（界面“默认”） | 使用应用 UA，避免盲目冒充其他客户端 | 应用下载使用 appName/vVersion + clash-verge + Platform/OS；Core 随配置装配；更新检查另用 browserUa | 保留 |
| `logLevel` | `error` | 降低日常事件量，故障再临时调高 | 热更新；与日志捕获开关独立 | 保留 |
| `LogsState.query / keywords / autoScrollToEnd` | `'' / [] / true` | 不预先隐藏诊断内容 | 日志页局部过滤/滚动，不代表默认后台捕获 | 保留 |
| `TrackerInfosState.query / keywords / autoScrollToEnd` | `'' / [] / true` | 保持连接页正常查看 | 页面局部状态，不改变路由 | 保留 |

`closeConnections=true` 在成功切换代理后关闭全部跟踪连接，包含 DIRECT；`onlyStatisticsProxy=true` 仅限制统计口径，两者没有筛选关联。长下载、通话等持续业务优先的用户可自行关闭自动断开。测速 URL `https://www.gstatic.com/generate_204` 保留作为代理健康目标，改成国内站点可能把已失去外网能力的代理误判健康。

含义依据：[mihomo 全局配置](https://wiki.metacubex.one/config/general/)、[固定版本连接统计](https://github.com/chen08209/Clash.Meta/tree/8380d0e7462f061a964323e9bf3129a236fcc5d5/tunnel/statistic)。具体客户端初值见[模型](../lib/models/clash_config.dart)，不将官方示例值当成本应用值。

## DNS：IPv6 补齐，服务器与顺序不变

### IPv6 与选中覆盖

| 字段 | 默认值 / 行为 | 理由与边界 | 决定 |
|---|---|---|---|
| `PatchClashConfig.dns.ipv6`、`ProfileOverrides.dns.ipv6` | 客户端预设 `true` | DNS AAAA 是独立开关；顶层 IPv6 开启不能代替它 | 修改 |
| `dns.fakeIpRange6 / fake-ip-range6` | `fdfe:dcba:9876::1/64` | 官方虚拟地址池，**不是新增 DNS 服务器**；固定 Core 从网段 base+4 分配，不占接口 ::1 与虚拟 DNS ::2 | 修改 |
| `dnsOverrideKeys / dns-override-keys` | 新默认选中 `ipv6`、`fake-ip-range6` | 客户端最终覆盖脚本，保证修改进入最终 YAML；普通配置允许这两个键 | 修改 |
| Android 首次 DNS 选择 | 上述两键 + `default-nameserver` | 保留原4项加密引导覆盖，其余 resolver/policy 由原配置/脚本提供 | 修改 |
| `Dns()` 裸模型 | 仍为上游语义：IPv6 false、range6空、DNS关闭等 | 不把上游重置/解析语义混同应用 `xclashDefaultDns` 预设；配置安全解码补齐缺失的客户端字段 | 保留裸模型；补缺省链 |
| 显式保存 `ipv6:false`、`fake-ip-range6:''`、`dns-override-keys:[]` | 原值优先 | 空键集合表示不覆盖；不得视作未保存并重新勾选 | 保留 |
| 旧 DNS map 缺少 keyset | 沿原 legacy 兼容分支解码，再按有效 IPv6 与显式 pool 值建立键集合 | 缺字段补默认；明确关闭不补隐含 pool key，明确空 pool 不换成默认；坏值仍走现有 salvage | 修改兼容链 |

固定 Core 在 fake-ip 模式下若没有 IPv6 pool，普通域名 AAAA 会空答。因此只把 DNS IPv6 布尔值设真不足以实现本次目标。内置脚本字节及 SHA 保持不变，它的旧 IPv6 值由**选中的客户端覆盖**在最后处理；没有选中键的旧配置不强制改变。

### 原有 resolver 地址

以下列表保持原地址和原顺序。多个 resolver 的实现可能并发查询，列表顺序不等于严格顺次故障转移。

| 列表名 | 值（从左到右） | 用途与决定 |
|---|---|---|
| `xclashBootstrapNameservers / default-nameserver` | `https://223.5.5.5/dns-query` → `https://223.6.6.6/dns-query` → `https://8.8.8.8/dns-query` → `https://1.1.1.1/dns-query` | 保留 IP 形式引导，减少解析 DoH 域名时的循环依赖 |
| 脚本自身 `default-nameserver` | 上述4项后接 `223.5.5.5` | 原有 UDP 引导项也不删除；Android首次选中引导覆盖后为上行4项，不能宣传所有既有配置绝无明文 |
| `xclashDomesticNameservers / nameserver / direct-nameserver` | `https://dns.alidns.com/dns-query` → `https://doh.pub/dns-query` | 保留国内解析与直连出口解析 |
| `xclashForeignNameservers / fallback` | `https://cloudflare-dns.com/dns-query` → `https://dns.google/dns-query` | 保留原境外解析 |
| `proxy-server-nameserver` | Cloudflare DoH → Google DoH → AliDNS DoH → DNSPod DoH（使用上行完整地址） | 独立节点解析，避免解析节点依赖节点自身 |
| `nameserver-policy` | `+.jsdelivr.net`、`+.github.com`、`+.githubusercontent.com`、`+.githubassets.com`、`+.fastly.net` → 境外2项；`geosite:cn` → 国内2项；`geosite:geolocation-!cn` → 境外2项 | 保留现有 CDN 与地域策略；不能假定 geosite 覆盖所有国内域名 |
| `proxy-server-nameserver-policy` | 客户端预设空表；脚本仅投射通过筛选的节点域名 hints | 保留节点级提示，不接受订阅任意扩大为全局 DNS 策略 |
| 脚本引导 `hosts` | `dns.alidns.com=[223.5.5.5,223.6.6.6]`；`doh.pub=[119.29.29.29]`；`dns.google=[8.8.8.8,8.8.4.4]`；`cloudflare-dns.com=[1.1.1.1,1.0.0.1]` | 保留4组；还可投射限定节点 hints，应用同名 Hosts 最终优先 |

完整原列表及过滤条目可检索于 [xclashDefaultDns](../lib/models/clash_config.dart) 和[内置脚本 overwriteGeneral](../assets/data/smart-config-kit-flclash.js)。本轮不改供应商、地址、顺序及脚本内容。

### DNS 其余字段

表中“客户端预设”指 `xclashDefaultDns`；没有被选中的键仍由订阅/脚本决定，并非一律强制覆盖。

| 字段（DNS 页面键名） | 客户端预设 / 代表性最终值 | 国内网络理由 / 平台边界 | 决定 |
|---|---|---|---|
| `enable` | 预设true；无工作 DNS 时注入必要 baseline | 内部 TUN DNS 需要解析器；裸模型false不等于最终关闭 | 保留 |
| `listen` | 预设空；脚本缺失时补 `0.0.0.0:1053`；安全模式空 | AAAA与监听地址无直接等价关系；不为IPv6另开公网监听 | 保留 |
| `listen-routing-mark` | `0` | 没有既定 Linux 标记需求 | 保留 |
| `prefer-h3` | `false` | 不增加 UDP/QUIC 可达性依赖；尤其不盲目与 respect-rules 组合 | 保留 |
| `use-hosts` | `true`；源明确false未被选择覆盖时可保留 | 保留静态映射能力；内部解析器另有 Hosts 查找，不能误当唯一引导机制 | 保留 |
| `use-system-hosts` | 客户端预设及脚本false | 避免本机系统 Hosts 意外进入可复现解析链 | 保留 |
| `respect-rules` | 客户端预设及脚本true | DNS连接服从路由；已有独立节点解析满足防循环前提 | 保留 |
| `ipv6-timeout` | `100ms` | 保留固定 Core 等待取舍，不无据改零或大幅加长 | 保留 |
| `cache-algorithm` | 客户端预设及脚本 `arc` | 不在缺乏命中率/RSS基准时替换算法 | 保留 |
| `cache-max-size` | `4096` | 不无据扩缓存占用 | 保留 |
| `enhanced-mode` | 客户端预设fake-ip；脚本保留来源模式，缺失才fake-ip | 保留明确 redir-host 兼容选择；不强制改已有模式 | 保留 |
| `fake-ip-range` | `198.18.0.1/16` | 保持现有IPv4虚拟网及路由关系 | 保留 |
| `fake-ip-filter-mode` | `blacklist` | 现有列表为真实IP豁免语义，切白名单/规则模式会改变解释 | 保留 |
| `fake-ip-filter` | 现有 LAN、NTP、STUN/TURN、远控、游戏、推送与 Docker 等兼容列表；脚本只追加清理后的源黑名单 | 不把全部国内域名一律排除fake-ip；原条目见链接源码 | 保留 |
| `fake-ip-ttl` | `1s` | 不延长旧映射缓存寿命 | 保留 |
| `direct-nameserver-follow-policy` | `true` | DIRECT重新解析仍尊重CDN/地域精确策略 | 保留 |
| `fallback-lazy-query` | `false`，脚本不强写 | 开启可减查询，但未命中policy且主解析失败时增加串行等待 | 保留 |
| `fallback-filter.geoip / geoip-code` | `true / CN` | 作用于未命中policy的后备筛选，不是全局分流开关 | 保留 |
| `fallback-filter.geosite` | `[gfw, geolocation-!cn]` | 固定Core仍支持；官方已弃用，未证明等价policy前不静默删除 | 保留，后续等价迁移 |
| `fallback-filter.ipcidr` | `240.0.0.0/4, 0.0.0.0/32, 127.0.0.0/8, 10.0.0.0/8, 192.168.0.0/16` | 保留已有污染过滤范围，不扩大到更多企业私网 | 保留 |
| `fallback-filter.domain` | 预设空；有效源列表保留 | 尊重明确的后备域名要求 | 保留 |
| `NetworkProps.appendSystemDns` | `false` | 不追加 `system://` 干扰既有列表，遵循截图 | 保留 |
| `DnsQueriesState.query / keywords / autoScrollToEnd` | `'' / [] / true` | DNS诊断页的本地筛选/滚动，不改变resolver | 保留 |

依据：[DNS配置](https://wiki.metacubex.one/config/dns/)、[解析流程](https://wiki.metacubex.one/config/dns/diagram/)、[固定 Core DNS默认与解析](https://github.com/chen08209/Clash.Meta/blob/8380d0e7462f061a964323e9bf3129a236fcc5d5/config/config.go)、[AAAA fake-ip中间件](https://github.com/chen08209/Clash.Meta/blob/8380d0e7462f061a964323e9bf3129a236fcc5d5/dns/middleware.go)、[池地址分配](https://github.com/chen08209/Clash.Meta/blob/8380d0e7462f061a964323e9bf3129a236fcc5d5/component/fakeip/pool.go)。

## VPN、TUN、路由与传输

| 字段 / 页面 | 默认值 | 国内使用取舍 | 平台 / 生效边界 | 决定 |
|---|---|---|---|---|
| `VpnProps.enable` / 网络→VPN | `true` | 覆盖不遵循HTTP代理的应用与UDP | Android需VPN授权；安全模式不接管 | 保留 |
| `VpnProps.systemProxy` | `true` | 兼容遵循HTTP代理的应用 | Android API29+；部分应用忽略；认证/安全模式抑制 | 保留 |
| `VpnProps.ipv6` | `true` | 与Core及DNS配合保留IPv6 | Android建立IPv6地址、DNS6与路由；不等于运营商/节点必有IPv6 | 保留 |
| `VpnProps.allowBypass` | `true` | 保留截图，兼容网络自检及特殊业务 | 允许应用显式绑定底层网络；不是封闭防泄漏模式 | 保留 |
| `VpnProps.dnsHijacking` | `false` | 保留截图，少干预硬编码DNS应用 | Android仅截获虚拟DNS `198.18.0.2` / `fdfe:dcba:9876::2`；不是关闭内部DNS，也不覆盖私人DNS/应用DoH | 保留 |
| `NetworkProps.systemProxy` | `true` | 保持桌面客户端兼容 | 桌面系统代理；Android另用VpnProps开关，共用排除域名 | 保留 |
| `NetworkProps.bypassPrivateRoute` | `true` | 保留NAS、打印机、路由器及局域网发现 | IPv4/IPv6自动私网排除；选中的保留虚拟池从自动排除项中挖孔 | 保留并补池路由 |
| `Tun.enable` | `false` | 桌面不未经授权自动开启TUN | Android使用VpnProps.enable；二者不混同 | 保留 |
| `Tun.device` | `appName` | 保留应用设备标识 | 桌面TUN；原生平台实际命名受系统约束 | 保留 |
| `Tun.stack` | `mips` | 固定Core默认，无证据支持统一换旧stack | Android/桌面均可消费；具体协议栈有兼容差异 | 保留 |
| `Tun.mtu / ProfileTun.mtu` | `9000` | 虚拟TUN MTU不是运营商物理MTU，不无据统一降1500/1280 | Android传Builder与Core，限制1280..65535；桌面选中覆盖才写入，否则保留来源/Core | 保留 |
| `Tun.congestionController / ProfileTun.congestionController` | `cubic` | BBR/BBR3并非所有RTT/丢包/带宽条件最优 | 作用于支持该选项的mipstack本地TCP leg，不是代理服务器TCP/QUIC算法 | 保留 |
| `Tun.routeAddress / routeExcludeAddress` | 均 `[]` | 不猜用户网络范围 | 用户明确include/exclude优先；空include按现有全集计算 | 保留 |
| `ProfileTun.routeAddress / routeExcludeAddress` | 均 `[]`、默认未选择覆盖 | 尊重订阅的明确路由范围 | 桌面按selected覆盖；Android读取Tun路线 | 保留 |
| `ProfileTun.strictRoute` | `false`、未选择 | 避免无据破坏虚拟机等网络兼容性 | 桌面selected有效；Android由Builder管路线，不能靠此开关修私人DNS | 保留 |
| `ProfileTun.dnsHijack` | `[any:53]` | 保持桌面TUN原DNS路径 | 桌面配置默认基础值，可selected替换；不同于Android dnsHijacking=false | 保留 |
| `ProfileTun.udpTimeout` | `300s` | 无同业务数据支持缩短或放大 | 仅相关用户态stack读取；按平台展示/selected | 保留 |
| `ProfileTun.disableIcmpForwarding / gso` | 均 `false` | 不无据扩大内核/网卡特性依赖 | 平台及stack支持范围不同；selected才覆盖 | 保留 |
| `ProfileTun.includeInterface / excludeInterface / includeUid / excludeUid / excludeSrcPort / excludeDstPort` | 均 `[]` | 没有用户设备/应用清单时不预排除流量 | 部分为Linux专属；selected才覆盖 | 保留 |
| `tunOverrideKeys` | `[]` | 模型展示值不强行覆盖源配置 | 自定义配置自身覆盖按既有优先级叠加 | 保留 |
| `interfaceNameMode / interfaceName` | `clear / ''` | 切换WiFi/蜂窝/网卡后避免绑定过期接口 | 桌面清除旧绑定并自动识别；Android通过protect避免回环，忽略该接口指定 | 保留 |
| `routingMark` | `0` | 无固定策略路由需求不造mark | 仅Linux相关路径有效 | 保留 |
| `keepAliveIdle / keepAliveInterval / disableKeepAlive` | 模型 `15s / 30s / false`；Android首次存值 `30s / 15s / false` | 不为无效果的手机优化改存值，也不无据提高唤醒频率 | Android mihomo出站socket TCP KeepAlive强制停；TUN本地mipstack leg仍有独立保活；桌面按配置消费 | 保留 |
| `AccessControlProps.enable / mode / acceptList / rejectList` | `false / rejectSelected / [] / []` | 不猜应用名单，避免新装漏代理 | Android应用访问控制；关闭时不安装每应用列表 | 保留 |
| `AccessControlProps.sort / isFilterSystemApp / isFilterNonInternetApp` | `none / true / true` | 方便列表操作，不改变真实访问控制名单 | 界面过滤排序，不触发VPN重启 | 保留 |
| `Config.excludeSSIDs` / 按需运行 | `[]` | 不猜家庭/公司WiFi，也不默认索取后台位置 | 非空才读取SSID并按规则暂停；Android/macOS受权限约束 | 保留 |
| `NetworkProps.autoSetSystemDns` | `true` | 保留既有macOS系统DNS集成 | 仅macOS相关路径；安全模式不修改主机 | 保留 |

### IPv6 虚拟池与私网旁路

`fdfe:dcba:9876::1/64` 位于ULA私网，原 `fc00::/7` 自动旁路会覆盖它。本轮从**自动**私网排除项中减去客户端明确选中的合法保留池，不把池强行并入最终用户路由：

- 普通配置读取应用选中的 `ipv6` 与 `fake-ip-range6`；自定义配置读取自身选中值。Core/VPN IPv6关闭或安全模式不保留这条开口。
- 用户显式的include/exclude继续优先；不覆盖用户把池排除或把include缩窄到公网的选择。其他ULA、链路本地、组播与IPv4旁路维持原行为。
- 这是客户端选中虚拟网的保留，不声称从任意脚本推导出了实际活跃池。来源模式为redir-host时池暂不用于fake回答，但选中池仍保留静态开口；未选中、显式关闭、空池不补默认开口。
- Android固定接口为 `fdfe:dcba:9876::1/126`、虚拟DNS为 `fdfe:dcba:9876::2`；池从base+4分配，不将池地址当成新resolver，不采用早期讨论的9877候选。

依据：[Android VPN](https://developer.android.com/develop/connectivity/vpn)、[Android Builder及allowBypass](https://developer.android.com/reference/android/net/VpnService.Builder)、[mihomo TUN](https://wiki.metacubex.one/config/inbound/tun/)、[固定sing-tun mips实现](https://github.com/MetaCubeX/sing-tun/blob/v0.4.27/stack_mipstack.go)、[固定Core出站保活](https://github.com/chen08209/Clash.Meta/blob/8380d0e7462f061a964323e9bf3129a236fcc5d5/component/keepalive/tcp_keepalive.go)。

### 17项系统代理排除域名

`NetworkProps.bypassDomain` 原值及顺序保留：

```text
*zhihu.com
*zhimg.com
*jd.com
100ime-iat-api.xfyun.cn
*360buyimg.com
localhost
*.local
127.*
10.*
172.16.*
172.17.*
172.18.*
172.19.*
172.2*
172.30.*
172.31.*
192.168.*
```

这是HTTP系统代理排除列表，不是强制DIRECT规则；绕开HTTP代理的流量仍可能进入TUN并由Core规则处理。通配匹配依操作系统实现，不扩大列表。

## 订阅、更新、资源与Geo

| 字段 / 页面 | 默认值 | 国内使用取舍 | 平台 / 生效边界 | 决定 |
|---|---|---|---|---|
| `Profile.autoUpdate` / 配置/订阅编辑 | `true` | 保持节点/到期信息新鲜；本地文件与custom不会触发远程更新，不能笼统说所有配置后台下载。 | 全平台；仅type=url有效；Bootstrap和Application定时扫描→ProfilesAction.autoUpdateProfiles | 保留 |
| `Profile.autoUpdateDuration` / 配置/订阅编辑 | `24h` | 降低后台电量/带宽，同时避免长期陈旧；有短时令牌要求按订阅服务说明改，不臆设所有机场1h。 | 全平台；启动检查及每20分钟到期扫描；并非每20分钟重复下载 | 保留 |
| `Profile.label/url/ageSecretKey` / 配置/订阅编辑 | `{"label":"","url":"","ageSecretKey":""}` | 没有通用默认订阅地址/解密密钥，不编造来源。 | 全平台；用户导入指定；label按订阅信息/来源名优化，age密钥供验证 | 保留 |
| `Profile.type` / 配置/添加 | `file（normal有URL则url；custom显式custom）` | 尊重来源类型，不把所有文件解释为订阅。 | 全平台；导入构造器决定 | 保留 |
| `Profile.extendType/scriptId` / 配置/扩展 | `{"model":"standard / null","newNormalProfile":"journal complete且默认脚本文件/DB存在→script / bundled scriptId"}` | 当前Smart-Config-Kit离线脚本是受哈希校验的产品预设；已有/custom/显式配置及被删除脚本不重新绑定，不恢复旧stash。 | 全平台；XClashDefaults.associateNewProfile→订阅装配→Apply | 保留 |
| `Profile.selectedMap/currentGroupName/unfoldSet` / 代理组/视图 | `{"selectedMap":{},"currentGroupName":null,"unfoldSet":[]}` | 不预选不可知节点；保留用户选择。 | 全平台；用户选择落库→setup selected-map；展开状态仅UI | 保留 |
| `Profile.overrides/matchTarget/order` / 配置/自定义/排序 | `{"overrides":"ProfileOverrides()","matchTarget":null,"order":null}` | DNS/NTP/sniffer/TUN按各自覆盖键生效；不默认叠加无依据覆写。 | 全平台；按配置类型装配→Apply；排序UI | 保留 |
| `Config.currentProfileId` / 配置/选择 | `null` | 没有订阅不自动编造当前配置。 | 全平台；首次导入时ProfilesAction.putProfile选择；后续fullSetup | 保留 |
| `Script.url / ClashProvider.url / IconSet.url` / 资源/添加 | `{"script":null,"provider":"","iconSet":""}` | 没有通用可信用户来源；捆绑Smart-Config-Kit是单独受校验例外，不默认添加更多源。 | 全平台；非空合法远程来源才下载；ResourcesAction手动或geoAutoUpdate调度→验证/落盘；脚本再经Apply | 保留 |
| `ClashProvider.behavior/format` / 资源/规则源 | `{"behavior":"classical","format":"yaml"}` | 兼容常见规则文件；用户导入其他格式按实际来源识别，不凭国内偏好统一强转。 | 全平台；资源解析与Core规则源配置 | 保留 |
| `Config.davProps` / 备份/WebDAV | `null` | 不预置云账号/凭据或自动上传；国内可达性不替代用户授权。 | 全平台；用户配置后显式备份恢复 | 保留 |
| `Config.hotKeyActions` / 工具/快捷键 | `{"newWindows":[],"newOther":["Ctrl+Alt+V","Ctrl+Alt+S","Ctrl+Alt+M","Ctrl+Alt+P","Ctrl+Alt+D"],"constructor":[]}` | 保留平台差异，不把其他平台快捷键强加Windows。 | 实际注册能力依桌面插件；Windows避免AltGr冲突；Config.realFromJson(null)→defaultHotKeyActions→热键注册 | 保留 |

`Profile.extendType/scriptId` 行的“journal complete”指当前设备已完成默认脚本安装且记录和文件仍在。新普通订阅/文件可关联捆绑 Smart-Config-Kit；已有配置、自定义配置、已显式关联或已删除的默认脚本不被重绑。源脚本、远程规则与节点协议的用户输入不是一个通用“国内最佳值”，不凭空预选节点或替换订阅地址。

| 字段 / 页面 | 默认值 | 国内使用取舍 / 边界 | 决定 |
|---|---|---|---|
| `geodataLoader` / 常规→Geo低内存 | `memconservative` | 偏向内存受限移动设备，保持截图；不冒充吞吐量最优 | 保留 |
| `geoAutoUpdate` / 资源菜单 | `false` | 同时控制Geo与远程脚本/图标/规则源自动刷新；不把执行脚本的后台更新当无代价优化 | 保留 |
| `geoUpdateInterval` | `24h` | 开启资源自动更新后使用；默认开关关，非每24小时必下载 | 保留 |
| `geoXUrl.MMDB` | `https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.metadb` | 保持官方发布来源 | 保留 |
| `geoXUrl.ASN` | `https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/GeoLite2-ASN.mmdb` | 同上 | 保留 |
| `geoXUrl.GEOIP` | `https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.dat` | 同上 | 保留 |
| `geoXUrl.GEOSITE` | `https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat` | 同上 | 保留 |

订阅自动更新独立于资源自动更新：URL订阅默认24小时，启动及应用每20分钟检查到期情况；本地文件/custom不远程下载。应用版本检查默认开，只提示，下载/安装沿用户确认入口。Core未运行或SSID暂停时应用请求可能直连，GitHub可达性并无无代理引导保证；不因此硬改未经来源验证的第三方镜像。

来源：[订阅模型](../lib/models/profile.dart)、[订阅更新](../lib/providers/actions/profiles.dart)、[资源更新](../lib/providers/actions/resources.dart)、[请求与版本检查](../lib/common/request.dart)、[应用调度](../lib/application.dart)、[mihomo全局Geo配置](https://wiki.metacubex.one/config/general/)。

## 嗅探、NTP与来源配置

普通订阅保留脚本的现有嗅探策略；自定义配置通过自身selected键配置，不把“模型默认”误写成所有来源的最终值。

| 字段 | 模型 / 脚本值 | 理由与边界 | 决定 |
|---|---|---|---|
| `Sniffer.enable` | 裸模型false；脚本true | 支持真实IP/QUIC流量域名归属；不保证识别ECH或解密业务 | 保留 |
| `parsePureIp / parse-pure-ip`、`forceDnsMapping / force-dns-mapping` | 模型及脚本true | 保持fake-ip-filter流量域名映射能力 | 保留 |
| `overrideDest / override-destination` | 模型及脚本true；脚本HTTP也true | 无已复现误嗅探不改访问目标覆盖语义 | 保留 |
| `sniff` / `SnifferConfig.ports / overrideDest` | 裸模型空表，子模型ports空、overrideDest=null；脚本HTTP=80、8080-8880，TLS=443、8443，QUIC=443、8443、4433 | 不任意扩大协议端口；具体来源配置仍优先 | 保留 |
| `forceDomain / force-domain`、`skipSrcAddress / skip-src-address` | 模型空列表 | 不无据增加强制嗅探或豁免 | 保留 |
| `skipDomain / skip-domain` | 模型空；脚本 `[+.push.apple.com]` | 保留推送兼容例外 | 保留 |
| `skipDstAddress / skip-dst-address` | 模型空；脚本现有Telegram IPv4/IPv6段 | 保留既有兼容列表，完整段见脚本 | 保留 |
| `snifferOverrideKeys` | `[]` | 未选择键不覆写来源；普通配置不开放整段全局嗅探覆盖 | 保留 |
| `Ntp.enable / server / port / interval / dialerProxy / writeToSystem` | `false / time.apple.com / 123 / 30s / '' / false` | 不默认改系统时间；Android系统写时钟受限，安全模式强制不写 | 保留 |
| `ntpOverrideKeys` | `[]` | 自定义配置按需选择，普通配置保留来源 | 保留 |
| `ProfileOverrides.proxyProviders` | `{}` | 不编造外部代理源 | 保留 |
| `profile.store-fake-ip / store-selected / tracing` | 脚本 `true / true / true`；最终装配 `store-selected=false` | fake映射和tracing保留；节点选择由客户端selectedMap存储，避免Core重复持久化 | 保留 |

依据：[mihomo嗅探配置](https://wiki.metacubex.one/config/sniff/)、[NTP配置](https://wiki.metacubex.one/config/ntp/)、[模型与覆盖选择](../lib/models/clash_config.dart)、[捆绑脚本](../assets/data/smart-config-kit-flclash.js)。

## 外观、代理样式与持久化状态

以下完整映射保持既有产品选择，不以“国内网络”推断GPU性能或擅自改样式。

界面项均保留，适用所有平台的对应布局，不改变网络路由。代理排序不会自动选择最快节点；隐藏超时节点可能妨碍排错。主题绘制性能需单独验证。

| 字段 | 默认值 | 平台 / 理由 | 决定 |
|---|---|---|---|
| `ProxiesStyleProps.type` | `list` | 代理样式菜单；本地呈现/排序 | 保留 |
| `ProxiesStyleProps.sortType` | `none` | 代理样式菜单；本地呈现/排序 | 保留 |
| `ProxiesStyleProps.layout` | `standard` | 代理样式菜单；本地呈现/排序 | 保留 |
| `ProxiesStyleProps.iconStyle` | `filled` | 代理样式菜单；本地呈现/排序 | 保留 |
| `ProxiesStyleProps.cardType` | `standard` | 代理样式菜单；本地呈现/排序 | 保留 |
| `ProxiesStyleProps.hideTimeoutProxies` | `false` | 代理样式菜单；本地呈现/排序 | 保留 |
| `ThemeProps.primaryColor` | `defaultThemeProps=0xFF007AFF；裸ThemeProps=null` | 外观；系统色/字体能力依平台 | 保留 |
| `ThemeProps.primaryColors` | `[0xFF007AFF,0xFFAF52DE,0xFFFF2D55,0xFFFF3B30,0xFFFF9500,0xFFFFCC00,0xFF34C759,0xFF8E8E93]` | 外观；系统色/字体能力依平台 | 保留 |
| `ThemeProps.themeMode` | `system` | 外观；系统色/字体能力依平台 | 保留 |
| `ThemeProps.colorStyle` | `vivid` | 外观；系统色/字体能力依平台 | 保留 |
| `ThemeProps.pureBlack` | `false` | 外观；系统色/字体能力依平台 | 保留 |
| `ThemeProps.sidebarBlur` | `true` | 外观；系统色/字体能力依平台 | 保留 |
| `ThemeProps.liquidGlass` | `ultra` | 外观；系统色/字体能力依平台 | 保留 |
| `ThemeProps.auroraEnabled` | `true` | 外观；系统色/字体能力依平台 | 保留 |
| `ThemeProps.auroraColors` | `["0xFF65BDD2","0xFFC589CA","0xFF75CCA8"]` | 外观；系统色/字体能力依平台 | 保留 |
| `ThemeProps.auroraSeed` | `271828` | 外观；系统色/字体能力依平台 | 保留 |
| `ThemeProps.auroraRotationDay` | `0` | 外观；系统色/字体能力依平台 | 保留 |
| `ThemeProps.textScale` | `{"enable":false,"scale":1}` | 外观；系统色/字体能力依平台 | 保留 |
| `ThemeProps.fontFamily` | `null` | 外观；系统色/字体能力依平台 | 保留 |

`WindowProps` 的宽高初值为0、位置/缩放为null，实际无保存几何时回退 `1280×880dp`；用户调整后读保存值。配置ID、更新时间、订阅用量与到期时间、脚本ID、排序号、窗口几何是使用状态，不预造身份、额度或用户偏好。新订阅的URL、密码、age密钥和WebDAV账号没有通用默认值。

## 本轮变更与验收状态

截图中的开关和值已统一到模型及Android首次预设；本轮深审进一步补齐DNS IPv6、官方IPv6 fake pool、selected最终覆盖、缺失字段/legacy兼容及自动私网排除中的池路由。维持 mips、MTU9000、cubic、ARC、prefer-h3=false、TLS校验、日志级别、自动更新周期及所有既有resolver。

当前证据是源代码、固定Core机制、静态检查与已编写回归；新增模型生成、格式化、分析及相关Flutter测试由CI统一执行，未将独立CIDR算术验证当作生产路径测试通过。真机VPN路由、DNS6、切网及目标运营商吞吐/时延/功耗仍待实测，因此不宣称“全国最优”或“无泄漏”。
