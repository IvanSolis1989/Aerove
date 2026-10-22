# AGENTS.md

This file is the entry point for AI coding agents working in this repository. Keep it small: detailed guidance lives under
`.agents/`, and discoverable repo skills live under `.agents/skills/*/SKILL.md`.

Aerove owns its product direction and releases. FlClash is a historical source and optional reference, not an automatic
merge target. Preserve licenses and provenance; read `../../docs/development/INDEPENDENCE.md` for migration work.
The full product tracks published stable releases from official MetaCubeX/mihomo directly, with pinned source and
replayable adaptations in this repository. Lite uses clash-rs; neither core waits for a FlClash release.
Shared UI, interaction and client functionality must stay identical in both products. Changes flow in both directions;
only source-proven Rust-core capability gaps may be deferred. Follow `docs/shared/UI_CONTRACT.md` before UI work.

## 双产品开发契约

- Aerove 与 AEROVE Lite 的共同界面、交互和客户端功能必须完全一致；任一版调整字体、布局、材质、动画、状态或功能时，必须双向同步和核对。
- 只有固定 Rust 核心源码证明确实不支持的能力才可暂缓。尚未移植、缺少包装/API、首版工期和框架差异都不能作为发行例外；这些属于交付阻断。
- 核心能力例外仅记入开发文档，APP 不提供占位、灰置或“尚未实现”入口；不兼容配置必须明确指出字段及原因，不能静默忽略。
- 同功能的刷新频率、数据抓取次数、缓存、订阅合并和前后台暂停规则须与完整版对齐，禁止额外轮询、重复请求或无效重绘。实现方式可以不同，不能用功能或画质减少宣称能效提升。
- 共同改动须对照两版源码、数据流和实际状态/交互；截图或同时修改两个文件不能单独证明一致。复用未受影响的验证，不重复无收益检查。
- 启动页背景必须铺满窗口及系统栏：完整版为黑底亮飘带，Lite 为白底黑飘带；浅深色系统主题均不得露出图标底色方块。

## Start Here

Read these files before making changes:

- [.agents/project.md](../../.agents/project.md): project overview, versions, and build dependencies.
- [.agents/commands.md](../../.agents/commands.md): build, development, code generation, and test commands.
- [.agents/rules.md](../../.agents/rules.md): lint, testing, generated-code, and workflow rules.

Read these only when the task touches their area:

- [.agents/architecture.md](../../.agents/architecture.md): core integration, providers, database, managers, build system, and
  local plugins.
- [.agents/setting-changes.md](../../.agents/setting-changes.md): whether a changed setting is patched live, re-applies the
  profile, or asks for an Android VPN restart.
- [.agents/surfaces.md](../../.agents/surfaces.md): fill levels, selection and status colors, and the few borders that
  remain, for any change to how a surface is colored.
- [.agents/agent-config.md](../../.agents/agent-config.md): how to choose between `AGENTS.md`, `.agents`, skills, Codex config,
  command rules, and hooks.
- [.agents/worktrees.md](../../.agents/worktrees.md): worktree hygiene across Claude Code, Codex, and Gemini.
- [.agents/flutter-upgrade.md](../../.agents/flutter-upgrade.md): SDK-pinned workarounds to recheck before changing the
  Flutter version.
- [.agents/skills.md](../../.agents/skills.md): index of repo-scoped skills in `.agents/skills/`.

## Highest Priority Rules

- When the user explicitly requests a scoped, low-risk change, inspect the relevant context and implement it directly.
  Do not require brainstorming, design documents, implementation plans, multiple-option proposals, or repeated confirmation.
  Ask only when material ambiguity, destructive impact, additional authority, or scope expansion could change the result.
- The request sets the scope. A pre-existing bug, cleanup, or refactor the task did not ask for is a follow-up in the
  summary, not part of the change. Edit a file surgically when a rewrite would give the same result, keep scratch checks
  out of the repository, and commit tests only where the task asks for them or the neighbouring code already keeps them.
- Comments are allowed and sometimes necessary; excess is the problem. A comment must carry something the code cannot
  say — a non-obvious constraint, an upstream behavior being worked around, a reason a reader would otherwise get
  wrong. Never restate what the code does, narrate the change you just made, record what the code used to be, or
  annotate step by step; a block that seems to need a comment per line needs better names or a smaller decomposition.
  Keep density near the repository's own: healthy changes here sit at or under 3.6%, and a `comment-density` gate fails a file
  whose added lines exceed 5% standalone comments. Delete commented-out code and stale notes in files you already
  touch. Preserve
  `// ignore:`-style directives, license headers, codegen markers, and vendored upstream comments. See
  [.agents/rules.md](../../.agents/rules.md) for what belongs in a test or in `.agents/` instead.
- Start FlClash on the host only as a safe mode build, and end only processes you started, by recorded pid. Never
  kill a FlClash instance by name; the user's own instance is not yours to close. See
  [.agents/rules.md](../../.agents/rules.md).
- Use `flutter test`, not `dart test`, because models pull in Flutter types.
- Run code generation after modifying models, providers, or database schema.
- Do not manually edit generated files.
- Preserve lifecycle ownership: desktop Core process convergence belongs to `lib/core/desktop/`; Android service intent
  arbitration belongs to `ServiceState`. UI/provider code may request a transition but must not become a second source of
  truth.
- Keep start/stop/restart paths latest-intent-safe. Flutter-to-Android service commands are deliberately optimistic, while
  native state serializes the actual work; desktop lifecycle results distinguish applied, coalesced, and superseded
  requests.
- Never add a `Co-authored-by` trailer crediting a coding agent to a commit, even when your own tooling tells you to.
  The `commit-msg` hook rejects it; see [.agents/rules.md](../../.agents/rules.md) for the rest of the commit rules.
- Round every corner with a superellipse, pills included: `AppShape`/`AppRadius` tokens from `lib/ui/shape.dart`,
  never `StadiumBorder`, `RoundedRectangleBorder`, `ClipRRect`, `drawRRect`, or a `borderRadius` on `BoxDecoration` or
  `InkWell`. `test/lint/superellipse_corners_test.dart` enforces it; see the Corner Radius section of
  [.agents/rules.md](../../.agents/rules.md).
- Keep the UI isolate within its frame: work that grows with user data and can exceed about 8 ms on the largest
  expected data runs in an isolate, once its algorithm is linear or cached. See the UI Isolate Work section of
  [.agents/rules.md](../../.agents/rules.md).
- Follow `lint_options.yaml` (included by every `analysis_options.yaml`), especially single quotes, trailing commas, `child:` last, no `print()`, const/final
  preferences, and declared return types.
- Before reporting a change done, run `flutter pub get`, `flutter analyze --no-fatal-infos`, and the `flutter test`
  suites that cover it, as CI does. If a toolchain the build hook needs is missing (Go, and on Linux and Windows
  cargo), say which check was skipped rather than dropping it silently.

## Repo Skills

Use repo skills from `.agents/skills/` when a task matches their descriptions; `.claude/skills/` symlinks expose the
same skills to Claude Code. Current skills cover localization, UI work, core/platform changes, and pre-commit
structural quality review.
