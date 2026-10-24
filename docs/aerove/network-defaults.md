# Aerove 国内网络默认基线

[文档](../README.md) / [标准版](README.md)

默认方案采用 [Smart-Config-Kit 的 FlClash 专用脚本](https://github.com/IvanSolis1989/Smart-Config-Kit/tree/main/FlClash)，
DNS 的业务解析器、分域策略、回退链和私有节点适配由该脚本维护。
按用户最新要求，Aerove 仅将自举解析器收紧为下列四个 DoH-over-IP 地址，不保留明文兜底，
不再使用 FlClash 原有的默认 DNS 列表：

```text
https://223.5.5.5/dns-query
https://223.6.6.6/dns-query
https://8.8.8.8/dns-query
https://1.1.1.1/dns-query
```

## 本页目录

- [来源与更新](#section-1)
- [DNS 所有权](#section-2)
- [Android 默认设置](#section-3)
- [验证边界](#section-4)

<a name="section-1"></a>

## 来源与更新

随 APK 内置的版本为 `v6.0.20-flclash.21`，来源提交
[`6847cd225d70bc9eb6fdd9311d50ff53bc91981c`](https://github.com/IvanSolis1989/Smart-Config-Kit/commit/6847cd225d70bc9eb6fdd9311d50ff53bc91981c)，
JavaScript Git blob 为 `b92cdae0b826e43e6fc0237ec739d3d5bec134c2`。
内置正文可离线使用；在 **进阶配置 → 脚本** 中显示以下远程地址，支持编辑链接、复制或打开来源，
并使用现有的同步操作获取新版：

```text
https://raw.githubusercontent.com/IvanSolis1989/Smart-Config-Kit/main/FlClash/FlClash%28mihomo%29.js
```

Aerove 代码的上游同步与这份脚本的更新是两条独立流程。当前脚本通过脚本库的更新操作刷新，
不把订阅自动更新误称为脚本自动更新，也不自行假设源仓库要求某个更新周期。

<a name="section-2"></a>

## DNS 所有权

按[源仓库 Android 建议](https://github.com/IvanSolis1989/Smart-Config-Kit/blob/6847cd225d70bc9eb6fdd9311d50ff53bc91981c/FlClash/README.md#L113-L152)，
新安装关闭“追加系统 DNS”。应用层只覆写 `default-nameserver` 为上述四个加密地址；
这项是用户明确要求的客户端策略，其余 DNS 覆写键不选中。
脚本执行后，除这一项外，最终送入核心的 `dns` 必须与脚本输出逐字段相同，
包括未知字段、列表顺序和动态节点策略。恢复默认也使用同一套用户 DNS，不回到原版列表。

| 项目 | 采用的脚本行为 |
| --- | --- |
| 国内业务和直连解析 | AliDNS、DNSPod DoH |
| 海外域名策略与 fallback | Cloudflare、Google DoH |
| `respect-rules`、`direct-nameserver-follow-policy` | `true` |
| DNS IPv6、`prefer-h3`、`use-system-hosts` | `false` |
| 私有节点 | 保留 `adaptive` 产生的活动节点精确 `proxy-server-nameserver-policy` |
| Fake-IP | 保留脚本过滤列表及对来源模式的处理，不重新手写列表 |
| 来源 `listen`、`enhanced-mode` | 保留脚本“缺省时补齐”的语义 |
| 自举解析器 | 仅四项 DoH-over-IP，移除旧明文 `223.5.5.5` 兜底 |

业务 DNS 仍按作者方案使用国内/海外 DoH，不能为了检测网站的国家标签而替换为全海外 DNS。
DNS 国家标签也不等于实际流量出口或泄漏结论。字段含义参考
[Mihomo DNS 文档](https://wiki.metacubex.one/config/dns/)。

<a name="section-3"></a>

## Android 默认设置

以下是新安装的基线；升级、备份恢复及已有用户设置不强制重写。

| 设置 | 默认 | 依据 |
| --- | --- | --- |
| VPN、规则模式 | 开启、规则 | 源仓库 Android 建议 |
| VPN 系统代理 | 关闭 | 避免额外的 HTTP 代理声明干扰 VPN 分流 |
| VPN IPv6、核心 IPv6 | 均关闭 | 两个独立开关，均与作者方案一致 |
| DNS 劫持 | 开启 | 接管进入 TUN 的明文 DNS |
| 应用访问控制、允许应用绕过 VPN | 均关闭 | 采用源仓库“全部应用交给 Mihomo 分流”的基线 |
| 应用层 DNS 覆写键 | 仅 `default-nameserver` | 强制采用用户要求的加密自举，其余脚本 DNS 保持原样 |
| 追加系统 DNS | 关闭 | 不向作者 nameserver 追加 `system://` |
| TCP 并发、统一延迟 | 开启 | 与源脚本一致 |
| keep-alive idle / interval | 30 / 15 秒 | 与源脚本一致，避免 App 后处理改回其他值 |
| TUN 栈、MTU、拥塞控制、GeoX | 保留客户端默认 | 由 App 管理，不从其他客户端示例移植 |

当前 `mips` 是 [Mihomo 文档](https://wiki.metacubex.one/config/inbound/tun/)支持的默认栈。
没有本机网络测量依据时，不以“国内优化”为由随意改成其他栈或 MTU。
客户端没有旧指南中的独立“路由模式”字段，实际使用其配置路由和排除表，不能照抄旧界面字段。

`allowBypass=false` 撤销普通应用主动绑定底层网络绕过 VPN 的许可，见
[Android 官方接口](https://developer.android.com/reference/android/net/VpnService.Builder#allowBypass())；
它不影响核心的 `protect` 回调，也不是 Android 的系统级 kill switch。
私网路由绕过保持原客户端的局域网兼容行为；被主动排除的应用、路由以及应用自己的加密 DNS
仍需结合设备路径检查，不能以 DNS 字典一致代替整机网络验收。

<a name="section-4"></a>

## 验证边界

脚本来源校验、基础与私有节点用例，以及最终配置 DNS 的深比较用于防止客户端意外改写作者方案。
Android 真机仍需核验 VPN 授权、重启后的实际配置、应用是否被排除、连接出口与 DNS 请求路径。
静态或合成测试通过不构成“所有设备绝对零泄漏”的证明。

---

下一步：[标准版说明](README.md) · [文档导航](../README.md)
