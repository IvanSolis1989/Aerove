# Issue 表单与 AI 助手

AEROVE 的 Issue 入口按使用求助、故障、性能与耗电、功能建议、文档问题分为五类。
表单关注标准版与 Lite 的独立版本、系统、复现条件和可验证证据，不套用配置规则仓库的分类。
UI 反馈补充材质、底图和深浅色；性能反馈补充前后台、时长、流量与同条件对照。
无法取得日志时可以说明原因，不以必须上传完整配置或大型测试作为反馈门槛。

## 行为与权限

[工作流](../../.github/workflows/ai-issue-responder.yml)在新建 Issue 或添加
`question`、`bug`、`enhancement`、`documentation`、`help wanted` 标签时尝试首次分析，使用 `mimo-v2.6-flash`。
维护者、协作者可在评论开头写 `/ai-help`，后面附新增问题或日志，使用 `mimo-v2.6-pro`。
运行时再次核对发起人的仓库写入权限。普通用户补充评论后，由维护者按需触发深度分析。

首次回复只有一条，重复加标签不重复调用。追问按评论 ID 去重，一分钟内连续追问跳过；
普通评论、机器人消息、PR 对话、已关闭或锁定的 Issue 不触发回复。
维护者添加 `ai:skip` 标签可排除此 Issue。分析结束前再次检查状态和回执，避免关闭后继续发帖。
删除已生成评论会同时移除去重标记；需要重新分析时优先使用明确的 `/ai-help`。

助手只分析和提供可逆排查步骤，不修改文件、创建 PR、关闭 Issue、承诺排期或发布应用。
只授予 `contents: read` 和 `issues: write`；检出可信 main 提交，不检出 Issue 指定的分支。
模型没有工具和运行器环境访问权限。报告和评论通过事件 JSON 与 API 读取，不插入 shell 命令。
输出只作为评论数据提交，不执行模型代码；生成内容不等于维护者验证或真实设备结果。

## 配置

在仓库 Settings → Secrets and variables → Actions 配置：

| 配置 | 类型 | 用途 |
| --- | --- | --- |
| `MIMO_API_KEY` | Secret | MiMo API 密钥，必需 |
| `MIMO_BASE_URL` | Variable | 可选，默认 `https://api.xiaomimimo.com/v1` |

Token Plan 必须使用对应套餐的官方专用端点，例如 `https://token-plan-cn.xiaomimimo.com/v1`。
密钥类型和端点须一致；不会自动切换端点或模型绕过认证、过滤及套餐限制。
端点仅允许脚本中的官方 MiMo 地址，不支持将密钥转发到任意服务。
Secret 形式的 `MIMO_BASE_URL` 兼容已有配置；同名 Variable 优先。
无需 Telegram 凭据、个人 GitHub Token、Tavily 或常驻服务器。

公开 Issue、最近八条对话的有限片段及当前仓库的相关公开文件片段会发送给 MiMo。
代码按网络、配置、DNS、UI/性能主题选取两版相关入口，每个文件最多前 90 行，整体有界，来源链接带实际 SHA。
文档反馈可指定 `docs/` 下的 Markdown 路径；不允许向上遍历、外部文件或历史归档作为任意读取入口。
不下载附件、不执行用户提供的命令、不主动访问报告中的链接，也不声称已读懂截图。
发送前隐藏订阅/节点链接和常见凭据字段；回复也会脱敏。自动脱敏不能替代提交者检查原始公开内容。

服务返回内容过滤、拒答或未完成输出时，发布明确的未完成提示并将运行标为失败，
不把拒答当成技术结论。缺少密钥时只在 Actions 提示，不向 Issue 发送配置噪声。
连接故障、429 和 5xx 最多以相同请求重试一次；认证和过滤不重试。
发布评论发生不确定网络失败时不自动重发，应先检查 Issue。
日志只记录受控状态和评论 URL，不记录密钥、请求全文、模型推理或供应商原始响应。

## 验证与手动运行

Actions → **AEROVE Issue assistant** → Run workflow：

- `preview`：真实调用模型，结果只进入运行摘要，不发评论。Issue 编号留空使用明确标注的虚构安装问题验收。
- `send`：必须指定 Issue 编号，向该 Issue 发布一条分析；仅用于维护者明确需要重新分析的情况。
- `deep_analysis`：使用 Pro，默认关闭。预览也会消耗相应 API 用量。

离线检查：

```sh
node --test .github/scripts/ai-issue-*.test.mjs
python tooling/workspace.py check
```

PR 和 main 的相关文件变更自动执行离线测试及 YAML 解析，不调用付费模型。
只改表单和助手不会重新构建 APK，不改变产品版本或现有 Telegram 发行通知。

设计参考及固定来源见[来源与鸣谢](upstreams.md#开源来源与鸣谢)。接口依据：
[MiMo Chat Completions](https://mimo.mi.com/docs/zh-CN/api/chat/openai-api)、
[MiMo 错误码](https://mimo.mi.com/docs/zh-CN/api/guidance/error-codes)、
[GitHub Actions 安全指引](https://docs.github.com/en/actions/reference/security/secure-use)。
