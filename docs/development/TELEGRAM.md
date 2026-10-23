# 官方频道自动通知

官方频道为 [Olympus](https://t.me/IvanSolis89)，关联讨论群为
[Olympus_Habitue](https://t.me/Olympus_Habitue)。发布机器人为 `@Olympus_TL_bot`。

仓库 Actions Secret `TELEGRAM_BOT_TOKEN` 保存机器人 Token；Token 不进入源码、日志和文档。
机器人需要频道管理员的“发布消息”权限，无须群管理权限。频道新帖由 Telegram 自动转发到关联群。

`.github/workflows/telegram-release.yml` 在公开 Release 发布时发送版本、正式/预览标记、更新说明和下载链接。
现有 `Aerove Android` 工作流由 `GITHUB_TOKEN` 发布 Release，因此还通过成功的 `workflow_run` 检查：
只接受本仓库 main 的 push/手动运行，且只发送该运行开始后发布、标签源码与构建源码一致的 Release。
草稿、失败运行、外部 fork 和没有已上传 APK 的 Release 均不发送。
Lite 后续公开 Release 复用同一通知入口，通过标签中的 `lite` 识别独立产品。

所有执行读取默认分支的通知实现，不执行 Release 标签或构建产物中的代码。
通知只发链接，不下载、重编或重新上传应用。完整更新说明以 GitHub Release 为准，频道正文控制在 Telegram 消息长度以内。

## 手动验证与恢复

Actions → **Telegram release notification** → Run workflow，选择 main：

- `preview`：填写准确 Release tag，只显示拟发送文字，不向 Telegram 发消息。
- `test`：发送一条静音的“机器人部署测试”，不需要 tag。
- `send`：填写准确 Release tag，发送已发布版本；重复发送自动跳过。
- `recover`：仅在查看频道确认上次没有发送成功后使用。此模式允许重试不确定的发送，有重复消息风险。

回执保存在 Release 标签提交的 GitHub commit status，context 为 `telegram/olympus/release-<Release ID>`，
成功回执带频道消息链接。工作流按仓库串行执行，双重触发或重跑会复用成功回执。
发送前写入 pending；网络失败或发送后回执保存失败时保留 pending，禁止盲目自动重试。
确认消息已经存在但回执未保存时，可将该 context 写为 success 并附上原消息链接；无需重发。

频道改名后公开链接需要同步更新，numeric chat ID 保持固定。机器人权限或 Secret 缺失会明确失败，
通知失败不改变原应用的构建和发行结果。GitHub Step Summary 显示真实消息链接或跳过原因。

Smart-Config-Kit 使用同一机器人，但仅 main 根 `CHANGELOG.md` 修改时通知；其部署规则在该仓库独立维护。

依据：[Telegram sendMessage](https://core.telegram.org/bots/api#sendmessage)、
[频道关联讨论群](https://core.telegram.org/api/discussion)、
[GitHub workflow_run](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_run)、
[GitHub commit statuses](https://docs.github.com/en/rest/commits/statuses)。
