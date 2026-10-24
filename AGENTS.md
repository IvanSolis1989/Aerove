# AEROVE workspace instructions

[文档导航](docs/README.md) · [开发维护](docs/development/README.md)

仓库开发入口：产品规则、开发导航与交付边界。

Read this entry point, then the applicable product instructions. The Standard edition and Lite are independent build roots:

- [AEROVE 标准版](apps/aerove/AGENTS.md): Flutter and official Mihomo; run its commands in `apps/aerove/`.
- [AEROVE Lite](apps/aerove-lite/AGENTS.md): native Android and meow-rs (migration in progress); run its commands in `apps/aerove-lite/`.
- [Workspace and document policy](docs/development/REPOSITORY_LAYOUT.md): ownership, paths and archival rules.
- [Project context](.agents/project.md), [commands](.agents/commands.md) and [engineering rules](.agents/rules.md).

## 双版本开发硬契约（每次开发必读）

- 产品名称为 **AEROVE 标准版**（原完整版）和 **AEROVE Lite**。现阶段并行开发、独立安装和发行；Lite 采用原生
  Android + meow-rs、最低 Android 13/API 33；标准版保留 Flutter + 官方 Mihomo 与现有桌面支持。
- 两版 Android 共同界面、交互和客户端功能必须完全一致。任一版的新增功能、修复或微调均须双向核对并同步；
  另一版已正确实现时复用其证据，不制造无意义改动。不能把单端退化同步成新标准。
- 只有固定 Rust 核心源码证明确实不支持的能力才可暂缓；必须记录核心 SHA、源码路径、行为证据和具体字段。
  “尚未移植”、缺少 JNI/API/包装、框架差异、工期及首版目标均不是例外，而是交付阻断。
- 内核例外只进开发记录，APP 不出现占位、灰置或“待实现”入口。不兼容配置必须指出字段和原因，禁止静默忽略。
- 保留仪表盘状态信息、大控制按钮、分层卡片及布局编辑；导航、表单、菜单、弹层、状态、字体、间距、图标、
  连续圆角、主题配色、动画和无障碍均属于契约，不能用通用连接页或平台默认控件代替。
- 简约纯色、柔光磨砂、流光清透 × 普通主题/灵动极光 × 浅色/深色均须一致；磨砂没有折射，清透必须有真实
  背景位移。不能以半透明加模糊冒充液态玻璃，不能靠降低画质满足性能目标。
- 启动品牌底色覆盖整个窗口及系统栏：标准版黑底亮飘带，Lite 白底黑飘带；不随系统深浅色改变，不露图标方块。
- 同功能的刷新、采集、缓存、请求合并、退避、动画门禁和前后台暂停策略一致。禁止额外轮询、重复请求、隐形页面
  持续重绘；重任务离开 UI 线程。原生/Rust 更省电是待测目标，不能当成已验证结论。
- 共同变更须留下两版入口与数据流、状态/交互对照、相关验证及例外依据。仅有组件、包装、静态映射或截图不算闭环。
- Lite 首发须提交真实 Android 渲染的十二宫格（三档材质 × 两种底图 × 深浅色），由用户确认细节；还须完成真实
  功能、取消/恢复、核心与 APK 验收。演示数据、模拟器截图和桌面 JVM 测试不冒充生产或真机能耗结果。
- 两版各自按产品变更选择主/次/修订版本，禁止复制 FlClash、Mihomo、meow-rs 或历史 clash-rs 的版本号。上游版本仅记录来源；
  Android 安装编号保持各产品单调递增。具体规则见[独立版本规范](docs/development/VERSIONING.md)。
- 真实应用变更完整构建一次，main 推广同一已验证 APK；不得改写原构建身份、重用其他源码的包，或因文档和流程改动
  重编已验收应用。预览版不覆盖标准版稳定 latest，Lite 使用独立更新入口。

详细规格只有一份：[双向实现与功能契约](docs/shared/UI_CONTRACT.md)、[外观参数](docs/shared/appearance.md)、
[验收边界](docs/shared/ACCEPTANCE.md)。调研、产品文档和子目录规则不得放宽本契约；未完成项如实记录，不能以改文档代替实现。

## 提交说明语言

- 提交标题和正文优先使用中文，简明说明实际变更及原因。
- 保留 Conventional Commits 的类型和可选作用域等必要格式，例如 `fix(lite): 修复核心接口不一致`；代码符号、路径、SHA 和机器读取字段沿用原文。
- `Changelog:` 等发布工具字段继续遵守对应格式与语言要求；普通提交说明仍优先中文。

## 开发前检索与开源复用

- 任何 APP 功能代码开发前，先在 GitHub 搜索已有实现，优先查官方上游和成熟开源项目；核对实际源码、版本、平台和接口是否适用，再确定实现方案。
- 已有可直接使用或小范围适配的可靠实现时，优先复用，不重复手写同等能力。确无适用实现，或存在明确不兼容约束时，记录检索结果和理由后自行实现；常规复用不额外请求批准。
- 在开发记录中保留检索关键词、候选仓库和采用结论。同一功能已有有效检索及固定来源时复用其记录；检索和引用不能代替本项目的实际验证。
- 所有实际参照、复制、适配或采用的上游仓库都须登记到[开源来源与鸣谢](docs/development/upstreams.md#开源来源与鸣谢)，包括仅作实现参考的项目；记录仓库链接、已核实的版本或提交、用途及采用方式，保留必要许可证、版权和署名，不宣称上游实现为原创。
- 来源登记和鸣谢使用上述唯一清单，仓库与 APP 的鸣谢入口链接该清单；新增来源随实现同步补齐，不另建相互漂移的名单。

## Safe implementation and delivery

- Preserve uncommitted work, collaborator ownership, licenses, signing identities and user data. Never reset, broadly
  stage unrelated files, force push or overwrite an active writer. Capture a stable checkpoint before moving owned files.
- Keep lifecycle ownership and latest-intent convergence in the existing platform owners. Host proxy launches must use
  safe mode; stop only processes started by this task, by recorded PID.
- Follow the applicable repo skill for UI, localization, core/platform and pre-commit quality work.
- Keep generated code generated. Report missing toolchains and actual failed checks; a heartbeat, static audit or source
  replay is not a successful APK, device render or performance test.
- Scope and authorization come from the user's current request. Do not block clear reversible work with repeated design
  approvals. Do not expand a change into unrelated cleanup.
- New documentation belongs in an existing `docs/` category; temporary reports and screenshots belong in external task
  evidence or CI artifacts. Keep one authoritative document and link to it.
- Run `python tooling/workspace.py check` after changing paths, document placement or repository entry points.
- Build each real application target once, promote the exact validated artifact, retain its original source/build identity
  and verify hashes, signing, package identity and release notes. Pure directory or policy changes do not justify silently
  rebuilding an already verified old application.
- No coding-agent Co-authored-by trailers. Repository comment-density and commit-message hooks remain binding.

## 文档编写、排版与视觉验收契约

- 所有人写项目文档、文档入口及网站中英文内容必须遵守唯一的[文档编写与视觉规范](docs/development/DOCUMENTATION_STYLE.md)，统一信息层次、导航与可读排版；密集细节进入仓库二级页面，不另建 Wiki 副本。
- 美化必须保留命令、代码、权威条款、来源身份、机器消费者格式与历史版权；沿用唯一事实源和开源鸣谢清单，维护旧路径及锚点兼容。具体保护边界和页面模板见上述规范。
- 推送前必须对受影响代表模板完成桌面1280px、手机390px的浅深色四组合真实渲染检查；网站中英文均需覆盖，确认无整体溢出、裁切、断图或不可读内容，并核对链接与锚点。输入未变的有效证据复用，只补验受影响风险。
- 推送后核对真实 GitHub 和涉及的已部署站点渲染及相关 CI；本地预览不能冒充线上验收。未完成项如实记录，提交、推送与部署仍受本次授权约束。
- 纯文档变更不重编 App、不改已验收产物身份。发布、候选、研究、Android渲染和能耗实测必须分清，不能以文档或截图替代对应验收。
