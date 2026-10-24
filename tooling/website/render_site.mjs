import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { runInNewContext } from 'node:vm';

const [out, siteArg, repository = 'IvanSolis1989/Aerove'] = process.argv.slice(2);
if (!out || !siteArg || !/^[\w.-]+\/[\w.-]+$/.test(repository)) {
  console.error('usage: render_site.mjs <site dir> <site url> [owner/repository]');
  process.exit(64);
}
const siteUrl = new URL(siteArg.replace(/\/*$/, '/')).href;
const repo = `https://github.com/${repository}`;
const sandbox = {};
runInNewContext(readFileSync(join(out, 'assets/shared.js'), 'utf8'), sandbox);
const { STRINGS, escapeHtml, standardReleases, releaseCard } = sandbox.AeroveSite;
const metadata = join(out, 'releases.json');
const raw = existsSync(metadata) ? JSON.parse(readFileSync(metadata, 'utf8')) : [];
if (!Array.isArray(raw)) throw new Error('releases.json must be an array of GitHub release metadata');
const releases = standardReleases(raw);
const stable = releases.find(r => !r.prerelease);
const preview = releases.find(r => r.prerelease);
// Never infer a release from the development changelog or construct asset names from version strings.
const template = readFileSync(join(out, 'index.html'), 'utf8');
const pages = [{ lang: 'en', locale: 'en', ogLocale: 'en_US', path: '', up: '', otherUrl: 'zh/', otherLang: 'zh-CN' },
  { lang: 'zh', locale: 'zh-CN', ogLocale: 'zh_CN', path: 'zh/', up: '../', otherUrl: '../?lang=en', otherLang: 'en' }];
for (const page of pages) {
  const t = STRINGS[page.lang];
  const schema = { '@context': 'https://schema.org', '@type': 'SoftwareApplication', name: 'AEROVE Standard',
    description: t.description, url: siteUrl + page.path, inLanguage: page.locale,
    applicationCategory: 'UtilitiesApplication', operatingSystem: 'Android, Windows, macOS, Linux',
    image: siteUrl + 'icon.png', license: `${repo}/blob/main/LICENSE`, isAccessibleForFree: true,
    downloadUrl: stable?.html_url || `${repo}/releases`, ...(stable ? { softwareVersion: stable.tag_name.replace(/^v/, '') } : {}), sameAs: [repo] };
  const values = { ...t, ...page, repo, siteUrl, canonical: siteUrl + page.path,
    schema: JSON.stringify(schema).replace(/</g, '\\u003c'),
    releases: releaseCard(stable, 'stable', t, repo) + releaseCard(preview, 'preview', t, repo) };
  let html = template.replace(/{{(\w+)}}/g, (_, key) => {
    if (!(key in values)) throw new Error(`Unknown template key: ${key}`);
    return key === 'schema' || key === 'releases' ? values[key] : escapeHtml(values[key]);
  }).replace('<html lang="en">', `<html lang="${page.locale}">`);
  const dir = join(out, page.path);
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, 'index.html'), html);
}
writeFileSync(join(out, 'release.json'), JSON.stringify(stable ? { tag: stable.tag_name, publishedAt: stable.published_at, assets: stable.assets } : {}, null, 2));
writeFileSync(join(out, 'sitemap.xml'), '<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n' + pages.map(p => `  <url><loc>${escapeHtml(siteUrl + p.path)}</loc></url>`).join('\n') + '\n</urlset>\n');
console.log(JSON.stringify({ repository, pages: pages.length, stable: stable?.tag_name || null, preview: preview?.tag_name || null }));
