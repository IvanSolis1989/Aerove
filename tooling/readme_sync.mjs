/**
 * Synchronize only the owned README marker blocks from published GitHub metadata.
 * Usage: node tooling/readme_sync.mjs --releases <flat releases.json>
 *   [--preview-dir <render-info.json and twelve PNGs directory>] [--root <repo>] [--check]
 * Exit: 0 = synchronized (or no drift); 1 = --check found drift; 2 = invalid input / I/O error.
 * Release-only calls leave showcase blocks and assets untouched. No network access is performed.
 * All inputs and target paths are validated before writing; unchanged bytes are never rewritten.
 * Reuse: website/assets/shared.js owns Standard release filtering and publication-time ordering.
 * Field references: https://docs.github.com/en/rest/releases/releases and https://nodejs.org/api/fs.html
 */
import { existsSync, lstatSync, mkdirSync, readFileSync, readdirSync, renameSync, unlinkSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { runInNewContext } from 'node:vm';
import { createHash, randomUUID } from 'node:crypto';

const defaultRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sandbox = {};
runInNewContext(readFileSync(join(defaultRoot, 'website/assets/shared.js'), 'utf8'), sandbox);
const { standardReleases } = sandbox.AeroveSite;
const REPO = 'https://github.com/IvanSolis1989/Aerove';
const APK = 'Aerove-android-arm64.apk';
const ASSETS = 'docs/assets/screenshots/showcase';
const SEMVER = /^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-((?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*))?\+([1-9]\d*)$/;
const SHA40 = /^[0-9a-f]{40}$/;
const SHA256 = /^[0-9a-f]{64}$/;
export const CASE_IDS = Object.freeze(['solid', 'frost', 'clear'].flatMap(material => ['classic', 'aurora'].flatMap(mode => ['light', 'dark'].map(brightness => `${material}-${mode}-${brightness}`))));
const fail = message => { throw new Error(message); };

function releaseUrl(value, expectedPath) {
  if (typeof value !== 'string') fail('Missing GitHub release URL');
  const url = new URL(value);
  // Compare decoded paths to accept GitHub's literal + and %2B without constructing replacement URLs.
  if (url.origin !== 'https://github.com' || url.username || url.password || url.search || url.hash ||
      /[\\\s<>"']/.test(value) || decodeURIComponent(url.pathname) !== expectedPath) fail(`Unsafe or mismatched release URL: ${value}`);
  return value;
}

export function selectReleases(raw) {
  if (!Array.isArray(raw) || raw.some(r => !r || typeof r !== 'object' || Array.isArray(r) || typeof r.tag_name !== 'string' || typeof r.draft !== 'boolean' || typeof r.prerelease !== 'boolean' || !Array.isArray(r.assets))) fail('Expected a flattened GitHub release array');
  const releases = standardReleases(raw);
  const stable = releases.find(r => !r.prerelease);
  if (!stable) fail('No published Standard stable release; refusing to replace existing README versions');
  const preview = releases.find(r => r.prerelease);
  // Do not silently fall back to an older release when a current Standard candidate is corrupt.
  // Historical -X releases used GitHub's stable channel; only the displayed releases need validation.
  for (const r of [stable, preview].filter(Boolean)) {
    const parsed = SEMVER.exec(r.tag_name);
    if (!parsed || Boolean(parsed[4]) !== r.prerelease || !Number.isFinite(Date.parse(r.published_at))) fail(`Invalid Standard release identity: ${r.tag_name}`);
    releaseUrl(r.html_url, `/IvanSolis1989/Aerove/releases/tag/${r.tag_name}`);
    const apks = r.assets.filter(a => a?.name === APK);
    if (apks.length !== 1) fail(`Expected exactly one ${APK}`);
    releaseUrl(apks[0].browser_download_url, `/IvanSolis1989/Aerove/releases/download/${r.tag_name}/${APK}`);
  }
  return { stable, preview };
}

export function replaceBlock(text, name, content) {
  const start = `<!-- aerove:${name}:start -->`;
  const end = `<!-- aerove:${name}:end -->`;
  if (text.split(start).length !== 2 || text.split(end).length !== 2 || text.indexOf(start) > text.indexOf(end)) fail(`Missing, repeated or reversed ${name} markers`);
  const nl = text.includes('\r\n') ? '\r\n' : '\n';
  return text.slice(0, text.indexOf(start) + start.length) + nl + content.replace(/\r?\n/g, nl) + nl + text.slice(text.indexOf(end));
}

function regularFile(path) {
  const stat = lstatSync(path);
  if (!stat.isFile() || stat.isSymbolicLink()) fail(`Expected a regular file: ${path}`);
  return readFileSync(path);
}

export function validatePreview(dir) {
  const manifestBytes = regularFile(join(dir, 'render-info.json'));
  const manifest = JSON.parse(manifestBytes.toString('utf8'));
  const m = manifest;
  if (m.schema !== 1 || m.product !== 'standard' || m.method !== 'flutter-widget-test' || m.fixture !== 'offline' || m.androidDevice !== false || m.pureBlack !== false || m.complete !== true || !SHA40.test(m.source?.commit) || !SHA40.test(m.source?.tree) || typeof m.source?.ref !== 'string' || !Object.hasOwn(m.source, 'workflowRun') || typeof m.source.producerDirty !== 'boolean' || !SHA256.test(m.source.harness?.sha256)) fail('Invalid or incomplete Standard offline render provenance');
  if (m.viewport?.width !== 430 || m.viewport?.height !== 932 || m.viewport?.devicePixelRatio !== 2) fail('Expected 430×932 viewport at DPR 2');
  if (!Array.isArray(m.cases) || m.cases.length !== 12 || new Set(m.cases.map(c => c.id)).size !== 12) fail('Expected twelve unique showcase cases');
  const expectedFiles = new Set(['render-info.json', ...CASE_IDS.map(id => `${id}.png`)]);
  if (readdirSync(dir).some(name => !expectedFiles.has(name))) fail('Unexpected files in showcase input directory');
  const images = CASE_IDS.map(id => {
    const c = m.cases.find(item => item.id === id);
    const [material, colorMode, brightness] = id.split('-');
    if (!c || c.file !== `${id}.png` || c.material !== material || c.colorMode !== colorMode || c.brightness !== brightness || c.width !== 860 || c.height !== 1864 || !SHA256.test(c.sha256)) fail(`Invalid case: ${id}`);
    const bytes = regularFile(join(dir, c.file));
    if (bytes.length < 33 || !bytes.subarray(0, 8).equals(Buffer.from('89504e470d0a1a0a', 'hex')) || bytes.readUInt32BE(8) !== 13 || bytes.toString('ascii', 12, 16) !== 'IHDR' || bytes.readUInt32BE(16) !== c.width || bytes.readUInt32BE(20) !== c.height || bytes[24] !== 8 || ![2, 6].includes(bytes[25]) || bytes[26] !== 0 || bytes[27] !== 0 || bytes[28] !== 0) fail(`Invalid PNG IHDR: ${id}`);
    if (createHash('sha256').update(bytes).digest('hex') !== c.sha256) fail(`PNG SHA-256 mismatch: ${id}`);
    return { file: c.file, bytes };
  });
  return { manifest, manifestBytes, images };
}

export function renderReleases(language, releases) {
  const zh = language === 'zh';
  return [['stable', zh ? '稳定版' : 'Stable'], ['preview', zh ? '公开预览版' : 'Public preview']].map(([channel, label]) => {
    const r = releases[channel];
    if (!r) return `- **${label}** · ${zh ? '暂无已发布的公开预览版。' : 'No published public preview.'}`;
    const build = SEMVER.exec(r.tag_name)[5];
    return `- **${label}** · [${r.tag_name.slice(1).split('+')[0]}](${r.html_url}) · ${zh ? '构建' : 'Build'} \`${build}\` · [Android ARM64 APK](${r.assets.find(a => a.name === APK).browser_download_url})`;
  }).join('\n');
}

export function renderGallery(language, manifest, prefix = '') {
  const zh = language === 'zh';
  const asset = `${prefix}${ASSETS}`;
  const labels = zh ? ['简约纯色', '柔光磨砂', '流光清透'] : ['Solid', 'Soft frost', 'Liquid clear'];
  const groups = ['solid', 'frost', 'clear'].map((material, i) => {
    const rows = ['classic', 'aurora'].map(mode => {
      const name = mode === 'classic' ? (zh ? '经典单色' : 'Classic monochrome') : (zh ? '灵动极光' : 'Aurora');
      const images = ['light', 'dark'].map(brightness => {
        const label = `${name} · ${brightness === 'light' ? (zh ? '浅色' : 'Light') : (zh ? '深色' : 'Dark')}`;
        const path = `${asset}/${material}-${mode}-${brightness}.png`;
        return `<a href="${path}"><img src="${path}" width="300" alt="${labels[i]} · ${label}"></a>`;
      }).join(' ');
      return `**${name}**\n\n${images}`;
    }).join('\n\n');
    return `### ${labels[i]}\n\n${rows}`;
  }).join('\n\n');
  const caption = zh ? '标准版开发界面的十二种组合，点击图片查看原图。来自实际 Flutter 组件的离线单屏渲染，呈现空配置、未连接状态；不作为 Android 真机或 Lite 验收。' : 'Twelve combinations of the Standard development interface. Click an image for full size. Actual Flutter widgets rendered offline with no profile or connection; not Android device or Lite acceptance.';
  return `${caption}\n\n${groups}\n\n${zh ? '来源' : 'Source'}: [\`${manifest.source.commit}\`](${REPO}/commit/${manifest.source.commit}) · [${zh ? '完整渲染记录' : 'Render provenance'}](${asset}/render-info.json)`;
}

function safeTarget(root, path) {
  const rel = relative(root, path);
  if (rel.startsWith(`..${sep}`) || rel === '..') fail('Target escapes repository');
  if (!lstatSync(root).isDirectory() || lstatSync(root).isSymbolicLink()) fail('Expected a regular repository directory');
  let cursor = root;
  for (const part of rel.split(sep)) {
    cursor = join(cursor, part);
    if (existsSync(cursor)) {
      const stat = lstatSync(cursor);
      if (stat.isSymbolicLink() || (cursor !== path && !stat.isDirectory()) || (cursor === path && !stat.isFile())) fail(`Invalid target path: ${cursor}`);
    }
  }
}

export function syncReadmes({ root = defaultRoot, releases, previewDir, check = false }) {
  root = resolve(root);
  const channels = selectReleases(releases);
  const preview = previewDir ? validatePreview(resolve(previewDir)) : null;
  const planned = [];
  for (const [file, language, prefix] of [['README.md', 'zh', ''], ['docs/i18n/README.en.md', 'en', '../../']]) {
    const target = join(root, file); safeTarget(root, target);
    let text = regularFile(target).toString('utf8');
    text = replaceBlock(text, 'releases', renderReleases(language, channels));
    if (preview) text = replaceBlock(text, 'showcase', renderGallery(language, preview.manifest, prefix));
    planned.push({ target, bytes: Buffer.from(text) });
  }
  if (preview) for (const image of [...preview.images, { file: 'render-info.json', bytes: preview.manifestBytes }]) planned.push({ target: join(root, ASSETS, image.file), bytes: image.bytes });
  // Complete preflight before creating directories or staging any output.
  for (const { target } of planned) safeTarget(root, target);
  const changed = planned.filter(p => !existsSync(p.target) || !regularFile(p.target).equals(p.bytes));
  if (!check) for (const { target, bytes } of changed) {
    mkdirSync(dirname(target), { recursive: true });
    const temporary = `${target}.${randomUUID()}.tmp`;
    try { writeFileSync(temporary, bytes, { flag: 'wx' }); renameSync(temporary, target); }
    finally { if (existsSync(temporary)) unlinkSync(temporary); }
  }
  return { changed: changed.length > 0, files: changed.map(p => relative(root, p.target).split(sep).join('/')), stable: channels.stable.tag_name, preview: channels.preview?.tag_name ?? null, showcase: preview?.manifest.source.commit ?? null };
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  try {
    const options = {}; const args = process.argv.slice(2);
    while (args.length) {
      const arg = args.shift();
      if (arg === '--check') { options.check = true; continue; }
      if (!['--root', '--releases', '--preview-dir'].includes(arg) || !args[0] || args[0].startsWith('--') || Object.hasOwn(options, arg)) fail(`Invalid or repeated argument: ${arg}`);
      options[arg] = args.shift();
    }
    if (!options['--releases']) fail('Usage: node tooling/readme_sync.mjs --releases <flat releases.json> [--preview-dir <dir>] [--root <repo>] [--check]');
    const result = syncReadmes({ root: options['--root'], releases: JSON.parse(readFileSync(options['--releases'], 'utf8')), previewDir: options['--preview-dir'], check: options.check });
    console.log(JSON.stringify(result));
    process.exitCode = options.check && result.changed ? 1 : 0;
  } catch (error) { console.error(`README sync failed: ${error.message}`); process.exitCode = 2; }
}
