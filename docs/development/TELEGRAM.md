# 官方频道自动通知

官方频道为 [Olympus](https://t.me/IvanSolis89)，关联讨论群为
[Olympus_Habitue](https://t.me/Olympus_Habitue)。发布机器人为 `@Olympus_TL_bot`。

仓库 Actions Secrets `TELEGRAM_BOT_TOKEN`、`TELEGRAM_API_ID`、`TELEGRAM_API_HASH` 保存认证信息；
API ID/Hash 从 [Telegram API 开发页面](https://my.telegram.org/apps) 取得，认证信息不进入源码、日志和文档。
机器人需要频道管理员的“发布消息”权限，无须群管理权限。频道新帖由 Telegram 自动转发到关联群。

`.github/workflows/telegram-release.yml` 在公开 Release 发布时发送版本、正式/预览标记、更新说明和下载链接。
现有 `Aerove Android` 工作流由 `GITHUB_TOKEN` 发布 Release，因此还通过成功的 `workflow_run` 检查：
只接受本仓库 main 的 push/手动运行，且只发送该运行开始后发布、标签源码与构建源码一致的 Release。
草稿、失败运行、外部 fork 和没有已上传 APK 的 Release 均不发送。
Lite 后续公开 Release 复用同一通知入口，通过标签中的 `lite` 识别独立产品。

所有执行读取默认分支的通知实现，不执行 Release 标签或构建产物中的代码。
通知直接发送 Release 的全部产物文件，多个文件合并为一组，附正式/预览标记、摘要和完整说明链接。
下载后逐项核对 GitHub Release 的 SHA256 digest 与文件大小，再把原文件作为 document 上传；不重编或改写 APK。
附件说明只保留一次版本标题、最多四条实际变化和一个短文本“发布页”链接；
去掉源码 SHA、包名、重复版本及构建流程。HTML 链接避免裸 URL 占据多行。

文件通过 Telethon 的机器人账号 MTProto 上传，支持超过云端 Bot API 50 MB 限制的 APK。
每次运行只在内存中创建机器人 session，结束后登出；不登录个人用户账号，不持久化 session，不运行常驻服务。
Python 上传依赖固定版本与 PyPI 文件哈希，位于 `.github/scripts/telegram-requirements.txt`。
未配置 API ID/Hash 时，仅不超过 50 MB 的文件可使用云端 Bot API 直接上传；
需要下载回验时云端文件上限为 20 MB。超过限制会停止并明确提示补齐 API 凭据，不降级成链接。

## 手动验证与恢复

Actions → **Telegram release notification** → Run workflow，选择 main：

- `preview`：填写准确 Release tag，只显示拟发送文字，不向 Telegram 发消息。
- `test`：发送一条静音的“机器人部署测试”，不需要 tag。
- `send`：填写准确 Release tag，发送已发布版本的原始附件；重复发送自动跳过。
- `recover`：仅在查看频道确认上次没有发送成功后使用。此模式允许重试不确定的发送，有重复消息风险。
- `edit`：仅更新已有成功消息的简短说明，不重新上传或再次发布附件。
- `verify`：指定 `first_message_id`，只读取其后有限范围内的已有附件并下载核对 SHA256，补齐发送回执，不发新文件。

勾选 `verify_files` 会在上传后从 Telegram 下载全部附件，并逐项核对 SHA256，用于实际交付验收。
执行摘要保留每个文件的名称、大小、SHA256、消息 ID 和下载回验结果。

回执保存在 Release 标签提交的 GitHub commit status，context 为 `telegram/olympus/files-release-<Release ID>`，
成功回执带频道消息链接。工作流按仓库串行执行，双重触发或重跑会复用成功回执。
发送前写入 pending；网络失败或发送后回执保存失败时保留 pending，禁止盲目自动重试。
确认消息已经存在但回执未保存时，可将该 context 写为 success 并附上原消息链接；无需重发。

频道改名后公开链接需要同步更新，numeric chat ID 保持固定。机器人权限或 Secret 缺失会明确失败，
通知失败不改变原应用的构建和发行结果。GitHub Step Summary 显示真实消息链接或跳过原因。

Smart-Config-Kit 使用同一机器人，仅 main 根 `CHANGELOG.md` 修改时发送各客户端原始配置文件，
不压缩、不改文件名、不改文件内容；根日志引用的同日子目录日志用于提取实际变化，其部署规则独立维护。

依据：[Telegram sendMessage](https://core.telegram.org/bots/api#sendmessage)、
[频道关联讨论群](https://core.telegram.org/api/discussion)、
[GitHub workflow_run](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_run)、
[GitHub commit statuses](https://docs.github.com/en/rest/commits/statuses)。
上传依据：[机器人 MTProto 登录](https://core.telegram.org/method/auth.importBotAuthorization)、
[Telethon 机器人账号](https://docs.telethon.dev/en/stable/basic/signing-in.html#signing-in-as-a-bot)。
