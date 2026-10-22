# 上游自动同步

`Sync upstream` 工作流每小时 UTC 第 17 分钟检查
[`chen08209/FlClash`](https://github.com/chen08209/FlClash) 的上游发布标签，
合并到 `IvanSolis1989/Aerove/main`。也可在仓库 **Actions → Sync upstream → Run workflow**
选择 `main` 立即运行。工作流只在此 fork 的 `main` 上运行，同一时间只处理一次同步。

每次检查都会从官方上游 remote 获取全部分支到 `refs/remotes/upstream/*`，并把 `v*` 标签放入独立的
`refs/upstream-tags/` 命名空间；不会从本 fork 的发布标签选择版本。只考虑至少有一个上游分支可达的合法稳定版或
`-pre.N` 预发布标签，按 SemVer 顺序选择最高版本，并把 annotated tag 剥离为其目标 commit。孤立 tag 不作为版本；
没有可达合法标签或 tag 版本无法解析时，本轮明确失败，不猜测版本。工作流合并选中的标签 commit，
不会跟随未打标签的分支开发提交，也不依赖固定分支名。

同步使用普通 merge，不 reset、rebase、force push，也不以 `ours`/`theirs` 整体覆盖文件。
`README.md` 为默认中文首页，`README_en.md` 为英文说明，均由 Aerove 独立维护，
完整介绍本仓库的功能、下载、构建和发布。上游的 `README_zh_CN.md` 不再导入。
同步时精确保留 fork 基线中的这两个文件，不采用上游 README；只有这两份文档发生冲突时，
可以自动保留本仓库版本，任何其他文件冲突仍停止。上游署名和 GPL 许可信息保留在本仓库文档中。
只有当选中的 tag、其 peeled commit 或 pubspec 来源发生变化时才继续；branch membership 的改变与未打标签的
分支提交不会单独触发同步。真实代码更新使用普通双父 merge；若选中 tag 的代码已在 fork 历史中而只有发布 tag
或来源记录变化，则仅提交新的 `tool/upstream-version.json`。同一 tag 与 source commit 已记录时直接早退，
即使某个分支推进了未打标签的提交。新增 tag 本身会刷新 metadata 并触发后续 APK 构建。metadata 记录上游仓库、
source/tag peeled commit、发现时包含该 tag commit 的上游分支、tag 版本、上游 pubspec 完整版本及其来源 commit。

合并提交前运行 `tool/sync_fork_readme.py --repository "$GITHUB_REPOSITORY"`，
只校正自有文档中由仓库名派生的链接，不从上游生成 README。
文档与源码随同同一个合并或 metadata 更新提交进入验证；Android 工作流再次以 `--check` 检查链接，漂移时停止。
同一最高 tag 已记录时直接结束。发现新 tag commit 时，以当前 fork main 为第一父提交建立双父合并提交，
在只有读权限的任务中运行 Flutter 3.47.6 的 `pub get`、`analyze --no-fatal-infos`、
配置模型/Provider、更新请求、主题、导航、启动按钮、Aurora 设置/壁纸/应用组件、本地化及 lint 测试。
测试期间仅临时关闭 Go setup hook，保留 Rust hook 运行编辑器相关测试，退出时恢复，打包前检查工作区无已跟踪修改；
被推送的是关闭 hook 之前已经生成并验证的合并提交。

验证通过后，独立写权限任务只读取 Git bundle，检查提交父链、工作流文件及远端 main，
通过普通 fast-forward push 更新 main。验证期间有人更新 main，或推送时发生竞争，
本轮失败并保留 main，下一轮基于最新 main 重做合并和验证。
写权限任务不执行合并后的仓库脚本或 Flutter 代码。

推送后确认远端 SHA，只有合并相对 fork 基线改变了 APK 输入时，才通过 `workflow_dispatch`
触发 `Aerove Android`，参数为
`ref=main`、`prepare_sources=false`、`build_apk=true`、`publish_release=true`。
该工作流执行 Android 验证和原生构建，成功后自动发布 APK、`SHA256SUMS` 和 `BUILD_INFO.json`
到本仓库 [Releases](https://github.com/IvanSolis1989/Aerove/releases/latest)。
默认分支的普通 push 也会运行同一构建发布流程；Actions artifacts 继续保留用于排查。
根目录 `README*.md`、`AGENTS.md`、`CLAUDE.md`、`CHANGELOG.md`，`docs/`、`snapshots/` 全部文件，
以及 `.agents/` 中的 Markdown 文档属于非 APK 输入；仅这些文件变化时，普通 push 不触发构建，
同步仍合并并执行既有验证，但不 dispatch Android。其他文件一律按实际输入处理，包含
`lib/`、`assets/`、`android/`、依赖、脚本和工作流。后续提交不会自动取消正在运行的构建；
手动 dispatch 仍可明确发起新构建。
源码同步与 Android 构建是两次独立运行：后续构建失败不会撤销已成功同步的源码，
失败结论保留在 Android Actions；dispatch 失败则同步运行的发布步骤显示失败，源码可能已推送，
应先核对记录的远端 SHA，再手动运行 Android 工作流。期间若 main 再次更新，Android 运行可能构建新的 main，
以它实际 checkout 的 SHA 和日志为准。

## Android 发布与签名

`Aerove Android` 在分支上先完整验证，再构建 APK 和执行 Android 单元测试。相同提交快进进入
`main` 后，工作流先查找本仓库同一 Android 工作流中该精确 SHA 的成功运行；只有完整 verify 与 APK
任务都成功、两个产物均未过期时才复用，并按不可变 artifact ID 下载。找到后跳过重复测试和编译，
由独立 `contents: write` 任务校验并发布原 APK；没有合格产物才在当前运行完整验证和构建。
查询失败明确停止，不把查询错误当成没有产物；下载或校验失败也停止，不静默替换构建。
正式通道公开为 latest，预览通道标为 prerelease 且不替换稳定版 latest；通道取自原构建来源信息。

发布前要求生成补丁为空、verified tree 等于发布提交的 tree，核对原始运行编号、源码、上游来源、
版本通道、双文件哈希、APK ZIP/ARM64 主库及签名和包信息。复用不会改写 APK、`BUILD_INFO.json`
或 `SHA256SUMS`，版本号和 `workflow` 链接始终保留第一次实际构建的信息；发布运行另外记录原构建链接。
任何源码变更都会使精确 SHA 匹配失效，必须重新验证构建。发布任务检出实际构建 SHA，
在上传前和公开前各获取一次最新 main：构建 SHA 必须仍是 main 的祖先，且其后变化只能是
上述非 APK 文档。README 等文档更新不会阻止已完成的包发布；任何代码、资源、依赖或打包变化
仍阻止旧包替换 latest，若已上传则保留草稿。`BUILD_INFO.json` 和 tag 始终指向实际构建 SHA，
不会标成后续文档提交。重跑不会覆盖已经公开的同名版本资产。`prepare_sources=true`
只用于准备生成文件，永不直接发布；补丁为空且成功的准备运行可在同一提交进入 main 后作为已验证产物复用。
仅验证时可关闭 `build_apk`，只构建时可关闭 `publish_release`。手动关闭 `build_apk` 时不强制构建，
若 main 也没有可复用产物则不会发布。

APK 显示版本取 `tool/upstream-version.json` 中上游 tag 去掉 `v` 后追加 `-X`，例如
`v0.9.1-pre.4` 显示为 `0.9.1-pre.4-X`；不从 `pubspec.yaml` 的基础版本推导，因此保留上游预发布编号。
同步 CI 会验证 metadata 的 schema、上游仓库、完整 commit SHA、tag commit 与 source commit 一致、记录的
pubspec 来源版本与当前 `pubspec.yaml`，并确认 source commit 已包含在构建 `HEAD`。过期或不匹配 metadata 会使 APK
构建失败。正式版构建核对最高可达的官方正式标签，后续预发布标签不阻止当前正式基线的修复版发布；
预览版构建仍核对包含预发布的最高官方标签。自动同步的标签选择不变，仍跟随两者中的最高版本。
两个通道都保留 tag/source、源码祖先与 pubspec 来源验证，新正式标签仍会阻止过期正式基线发布。
`pubspec.yaml` 保留上游内容；内部 Android versionCode 固定为 `2026101700 + GITHUB_RUN_NUMBER`，
不读取或累加上游 build number。Release tag 为 `v<显示版本>+<内部versionCode>`，且 APK 使用同一名称和编号。
构建编号只用于 CI 打包，不回写 pubspec；`BUILD_INFO.json` 保存上游 source/tag/pubspec 来源，便于核对。
README 的 `/releases/latest/download/Aerove-android-arm64.apk` 始终指向最近成功发布的 ARM64 APK。

仓库 secret `XCLASH_ANDROID_DEBUG_KEYSTORE` 保存固定 Android 测试签名容器的 base64，
CI 还原到私有 runner 的默认 debug keystore；缺失时构建失败，不生成临时签名替代。
别名为 `androiddebugkey`、容器口令为 `android`，包 ID 保持 `com.follow.clash.dev`。
不要轮换此 key，否则已安装版本不能直接覆盖升级。先前仅在 Actions 提供的 APK 使用
临时签名，更换到首次 Release 可能需要先备份配置再卸载旧测试版。
这些发布仍标为 Android preview，尚未完成真机验收；没有产出其他平台安装包。

## 首次启用与平台限制

- 工作流必须已提交到默认分支 `main`，fork 的 Actions 必须启用；当前工作流仅申请
  `contents: write` 和用于触发 Android 工作流的 `actions: write`，仓库规则或组织策略仍可能拒绝
  机器人直接推送或触发工作流，此时运行会明确失败。
- GitHub 定时任务可能延迟或被丢弃，并非小时级时效保证。公开仓库连续 60 天无活动后，
  定时任务会被平台禁用；维护者应在 Actions 重新启用。无需新密钥即可运行一般源码同步。
- `GITHUB_TOKEN` 没有可申请的 `workflows: write` 权限。合并若修改 `.github/workflows/`，
  本工作流主动失败，不能自动发布该更新；维护者需检查变化后，使用具备相应 workflow
  权限的个人凭据或 GitHub App 手动合并推送。不要通过丢弃上游工作流更改来绕过限制。
- 默认 token 推送不会触发另一个 `push` 工作流，必要 UI 检查已放在同步工作流中。
  源码同步后显式 dispatch Android 构建，检查其独立运行结果。这里不证明桌面原生构建、
  全部 Rust/Go 或插件测试通过；其他平台验证可显式启用上游 `build` 工作流。
  上游升级 Flutter 或改变测试路径时，需先调整此工作流。
- Git merge 保留 fork 的既有 UI 提交，但不能判断无文本冲突的上游改动是否仍符合视觉意图；
  聚焦测试也不代替人工界面验收。不承诺自动解决未来所有冲突。

## 失败排查与恢复

失败运行显示红色结论。先看失败步骤及该次运行的 artifacts：
`upstream-sync-validation` 包含基线/上游/候选 SHA、merge 日志、冲突文件列表与补丁、
依赖/分析/测试日志（完成到哪一步就保留到哪一步）及最终 Git 状态；
`upstream-sync-publish` 记录推送阶段日志和远端 main。证据保留 30 天。
候选 bundle 保留 7 天，仅供检查，不应绕过验证直接推送。

冲突时在干净的独立本地工作树获取最新 `origin/main`、上游所有分支和 `v*` 标签，按同一规则选出最高可达版本标签，
执行 `git merge --no-ff <peeled-tag-commit>`，逐文件协调 UI 与上游逻辑，完成相同检查，再提交和普通推送。
使用有 workflow 权限的凭据处理工作流变更。若放弃本地冲突，使用 `git merge --abort`；
自动任务中失败的候选不会更新远端。依赖、分析或测试失败同样先修复真实失败条件，
然后重新运行；不要删断言、跳测试或强制推送。工作流不自动创建 issue 或发送消息。

平台行为依据：
[定时事件与默认分支](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule)、
[token 触发限制](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow)、
[GITHUB_TOKEN 权限](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#permissions)、
[修改 workflow 所需额外权限](https://docs.github.com/en/rest/repos/contents#create-or-update-file-contents)。
