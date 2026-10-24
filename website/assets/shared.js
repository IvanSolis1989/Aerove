// One content and release contract is shared by the static page generator.
globalThis.AeroveSite = (() => {
  'use strict';
  const STRINGS = {
    en: {
      pageTitle: 'AEROVE — See your connection. Make it yours.', description: 'AEROVE is an independently maintained proxy client. Download the Standard edition, explore the documentation, and follow the development of AEROVE Lite.',
      skip: 'Skip to content', navigation: 'Page sections', download: 'Download', start: 'Get started', docs: 'Docs', language: '中文', languageLabel: '切换到中文', themeLabel: 'Toggle dark theme',
      intro: 'An independent, open-source proxy client', heroTitle: 'See your connection.\nMake it yours.', heroDescription: 'Connection status, traffic, outbound modes and profiles — together in a dashboard you can arrange around your day.', getStandard: 'Get AEROVE Standard', exploreDocs: 'Explore the docs', heroNote: 'Android · Windows · macOS · Linux projects', overview: 'About AEROVE',
      feature1: 'A clear view', feature1Text: 'Read your connection and traffic at a glance, with layered cards and an editable dashboard.', feature2: 'Your network, your rules', feature2Text: 'The Standard edition uses official Mihomo, with profiles, proxy groups and rule routing.', feature3: 'Independently maintained', feature3Text: 'Two distinct products, with separate packages, data, versions and release channels.',
      chooseVersion: 'Choose your next connection.', downloadLead: 'Start with the stable Standard edition. Read the release notes before installing a preview.', stable: 'Stable', preview: 'Public preview', stableText: 'The recommended place to start.', previewText: 'Try upcoming changes after reading the release notes.', apk: 'Download Android ARM64 APK', releaseNotes: 'Release notes & checksums', unavailable: 'Browse available releases', downloadNote: 'Desktop projects are maintained; available installers depend on each release. Preview and stable belong to the same Standard product: back up your data before changing versions.', allReleases: 'Browse all releases', notReleased: 'Not yet released', liteDescription: 'Native Android + meow-rs, for Android 13 / API 33 and later. Shared interfaces and client features are being developed and validated against the same contract. There is no public Lite installer yet.', liteProgress: 'Follow Lite development',
      startLabel: 'Three steps', startTitle: 'From install to connected.', startLead: 'Bring your own configuration or subscription. AEROVE does not provide nodes or subscription services.', step1: 'Install the app', step1Text: 'Choose a package from the Standard release page, read its notes, then install it.', step2: 'Add your configuration', step2Text: 'Open Profiles and import a configuration file or your own subscription link.', step3: 'Choose an outbound and connect', step3Text: 'Select a profile, node and outbound mode. On Android, allow the VPN request when connecting for the first time.',
      readMore: 'Keep exploring', docsTitle: 'Find your way around.', docsIndex: 'All documentation', doc1: 'Using Standard', doc1Text: 'Defaults, networking and background behaviour.', doc2: 'What changed', doc2Text: 'Version history and compatibility notes.', doc3: 'Build & contribute', doc3Text: 'Workspace structure, development and shared contracts.', communityTitle: 'Stay in the conversation.', communityText: 'For a useful bug report, include the product, version, system and steps to reproduce. Remove private data from logs and configurations.', channel: 'Telegram channel', group: 'Discussion group', issues: 'Report an issue', footerText: 'Independent products. Open development.', attribution: 'Built on the historical FlClash codebase. Thanks to its authors and contributors.', footerLinks: 'Project links', acknowledgements: 'Sources & acknowledgements',
    },
    zh: {
      pageTitle: 'AEROVE — 看清连接，自在掌控。', description: 'AEROVE 是独立维护的开源代理客户端。下载标准版、浏览使用文档，了解原生 Android 产品 AEROVE Lite 的开发进展。',
      skip: '跳到正文', navigation: '页面导航', download: '下载', start: '开始使用', docs: '文档', language: 'EN', languageLabel: 'Switch to English', themeLabel: '切换深色主题',
      intro: '独立维护的开源代理客户端', heroTitle: '看清连接，\n自在掌控。', heroDescription: '将连接状态、流量、出站模式与配置集中在仪表盘中，用分层卡片和可编辑布局，安排自己的连接方式。', getStandard: '获取 AEROVE 标准版', exploreDocs: '浏览文档', heroNote: '保留 Android · Windows · macOS · Linux 工程', overview: '认识 AEROVE',
      feature1: '状态一目了然', feature1Text: '连接与流量集中呈现，通过分层卡片和可编辑仪表盘，找到自己的使用节奏。', feature2: '按自己的规则连接', feature2Text: '标准版采用官方 Mihomo，支持配置导入、代理组与规则分流。', feature3: '独立维护，持续演进', feature3Text: '两款独立产品，分别管理安装包、数据、版本与更新入口。',
      chooseVersion: '从适合你的版本开始。', downloadLead: '日常使用优先选择标准版稳定发行。安装公开预览版前，请先阅读版本说明。', stable: '稳定版', preview: '公开预览版', stableText: '初次使用，推荐从这里开始。', previewText: '了解版本变化后，体验新的改进。', apk: '下载 Android ARM64 APK', releaseNotes: '版本说明与校验文件', unavailable: '查看可用发行', downloadNote: '桌面工程继续维护，可用安装包以各次发行资产为准。预览版与稳定版属于同一标准版产品；切换版本前，请备份数据。', allReleases: '浏览全部发行', notReleased: '尚未首发', liteDescription: '原生 Android + meow-rs，面向 Android 13 / API 33 及以上设备。共享界面与客户端功能按同一契约开发验证，使用独立安装包、数据和更新入口。当前没有公开 Lite 安装包。', liteProgress: '了解 Lite 开发进展',
      startLabel: '三个步骤', startTitle: '从安装到连接。', startLead: '请准备自己的配置或订阅。AEROVE 不提供节点或订阅服务。', step1: '安装应用', step1Text: '从标准版发行页面选择安装包，阅读对应版本说明后安装。', step2: '添加配置', step2Text: '打开配置页面，导入配置文件或自己准备的订阅链接。', step3: '选择出口并连接', step3Text: '选择配置、节点与出站模式，启动连接。Android 首次连接时，按系统提示授权 VPN。',
      readMore: '按需深入', docsTitle: '找到下一步。', docsIndex: '完整文档导航', doc1: '标准版使用说明', doc1Text: '了解默认设置、网络策略与后台行为。', doc2: '跟进版本变化', doc2Text: '查看更新记录与兼容性说明。', doc3: '参与开发与改进', doc3Text: '了解仓库结构、构建方式与双产品共同契约。', communityTitle: '一起交流，持续改进。', communityText: '反馈问题时，请附产品名称、版本、系统与复现步骤；日志和配置请先脱敏。', channel: 'Telegram 频道', group: 'Telegram 交流群', issues: '提交问题或建议', footerText: '独立产品，开放开发。', attribution: '历史代码源自 FlClash，感谢原作者与贡献者。', footerLinks: '项目链接', acknowledgements: '来源与鸣谢',
    },
  };
  const escapeHtml = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  // Tags and asset names identify the product; unrelated and Lite releases never populate Standard downloads.
  function standardReleases(raw) {
    return raw.filter(r => !r.draft && /^v\d+\.\d+\.\d+(?:-[^+]+)?(?:\+\d+)?$/.test(r.tag_name || '') &&
      r.assets?.some(a => a.name === 'Aerove-android-arm64.apk')).sort((a,b) => String(b.published_at || '').localeCompare(String(a.published_at || '')));
  }
  function releaseCard(release, channel, t, repo) {
    const preview = channel === 'preview';
    const label = t[channel];
    const releaseUrl = release?.html_url || `${repo}/releases`;
    const asset = release?.assets.find(a => a.name === 'Aerove-android-arm64.apk');
    const safeUrl = url => typeof url === 'string' && url.startsWith(`${repo}/releases/`) ? escapeHtml(url) : `${repo}/releases`;
    return `<article class="release-card ${channel}"><span class="badge">${escapeHtml(label)}</span><h3>AEROVE ${t === STRINGS.zh ? '标准版' : 'Standard'}</h3><p class="version">${release ? escapeHtml(release.tag_name.replace(/^v/, '')) : '—'}</p><p>${escapeHtml(preview ? t.previewText : t.stableText)}</p><a class="button ${preview ? 'secondary' : 'primary'}" href="${safeUrl(asset?.browser_download_url || releaseUrl)}">${escapeHtml(asset ? t.apk : t.unavailable)} <span aria-hidden="true">↗</span></a><a class="release-link" href="${safeUrl(releaseUrl)}">${escapeHtml(t.releaseNotes)}</a></article>`;
  }
  return { STRINGS, escapeHtml, standardReleases, releaseCard };
})();
