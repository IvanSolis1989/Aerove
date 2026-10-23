import { readFileSync, realpathSync, statSync } from 'node:fs';
import path from 'node:path';

const baseline = ['docs/aerove/README.md', 'docs/aerove-lite/README.md', 'docs/shared/UI_CONTRACT.md',
  'docs/development/VERSIONING.md', 'apps/aerove/pubspec.yaml', 'apps/aerove-lite/version.properties',
  'apps/aerove/core/mihomo-upstream.json'];
const topics = [
  [/安装|更新|最低|系统要求|minSdk|install|update|android\s*1[0-9]/i,
    ['apps/aerove/android/app/build.gradle.kts', 'apps/aerove/android/gradle/libs.versions.toml',
      'apps/aerove-lite/app/build.gradle.kts', 'docs/development/upstreams.md']],
  [/vpn|tun|连接|断网|启停|后台|休眠|通知|permission|connect|background/i,
    ['docs/aerove/network-defaults.md', 'docs/aerove/background.md', 'apps/aerove/lib/core/service.dart',
      'apps/aerove/android/app/src/main/kotlin/com/follow/clash/ServiceState.kt',
      'apps/aerove-lite/app/src/main/java/dev/aerove/lite/vpn/LiteVpnService.kt']],
  [/配置|订阅|导入|备份|profile|subscription|import|backup/i,
    ['apps/aerove/lib/providers/actions/profiles.dart',
      'apps/aerove-lite/app/src/main/java/dev/aerove/lite/data/ProfileRepository.kt']],
  [/dns|ipv6|解析|分流|路由|rule/i,
    ['docs/aerove/network-defaults.md', 'apps/aerove/lib/views/config/dns.dart', 'docs/aerove-lite/core/clash-rs-audit.md']],
  [/界面|主题|材质|磨砂|清透|极光|ui|theme|glass|滚动|动画|卡顿|耗电|性能|battery|performance/i,
    ['docs/shared/appearance.md', 'docs/shared/ACCEPTANCE.md', 'docs/aerove/background.md']],
];

export function redact(text) {
  return String(text || '')
    .replace(/\b(?:vmess|vless|trojan|ssr?|hysteria2?|tuic):\/\/[^\s<>`]+/gi, '[节点链接已隐藏]')
    .replace(/https?:\/\/[^\s<>`\])]+/gi, value => {
      try {
        const url = new URL(value);
        return ['github.com', 'raw.githubusercontent.com'].includes(url.hostname) && !url.search && !url.username
          ? value : '[外部链接已隐藏]';
      } catch { return '[链接已隐藏]'; }
    })
    .replace(/((?:["']?(?:password|passwd|token|secret|uuid|api[_-]?key|authorization|密码|订阅地址|订阅链接)["']?)\s*[:=]\s*)(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|(?:Bearer|Basic)\s+[a-zA-Z0-9._~+/-]+=*|[^\s,;}]+)/gi, '$1[已隐藏]')
    .replace(/\b(?:sk-[a-zA-Z0-9_-]{16,}|\d{8,12}:[a-zA-Z0-9_-]{30,})\b/g, '[凭据已隐藏]');
}

export function buildContext(root, report, sha) {
  const candidates = [...baseline];
  for (const [pattern, files] of topics) if (pattern.test(report)) candidates.push(...files);
  for (const match of report.matchAll(/\bdocs\/[a-zA-Z0-9_./-]+\.md\b/g)) {
    if (!match[0].split('/').includes('..') && !match[0].startsWith('docs/archive/')) candidates.push(match[0]);
  }
  const base = realpathSync(root);
  let budget = 50000;
  const evidence = [];
  for (const file of [...new Set(candidates)].slice(0, 18)) {
    let content;
    try {
      const target = realpathSync(path.join(base, file));
      const relative = path.relative(base, target);
      if (relative.startsWith('..') || path.isAbsolute(relative) || statSync(target).size > 400000) continue;
      content = readFileSync(target, 'utf8');
    } catch { continue; }
    const lines = content.split(/\r?\n/);
    const selected = lines.slice(0, 90).map((line, i) => `${i + 1}: ${line}`).join('\n').slice(0, 6000);
    if (selected.length > budget) break;
    budget -= selected.length;
    evidence.push(`${file} (节选，不能据此断言全文实现完整)\nhttps://github.com/IvanSolis1989/Aerove/blob/${sha}/${file.split('/').map(encodeURIComponent).join('/')}\n${selected}`);
  }
  return evidence.join('\n\n');
}
