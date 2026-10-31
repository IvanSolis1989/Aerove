import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, statSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { deflateSync } from 'node:zlib';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { selectReleases, replaceBlock, validatePreview, syncReadmes, CASE_IDS } from './readme_sync.mjs';

const repo = 'https://github.com/IvanSolis1989/Aerove';
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
function release(tag, prerelease = false, date = '2026-10-22T00:00:00Z') {
  return { tag_name: tag, prerelease, draft: false, published_at: date,
    html_url: `${repo}/releases/tag/${encodeURIComponent(tag)}`,
    assets: [{ name: 'Aerove-android-arm64.apk', browser_download_url: `${repo}/releases/download/${encodeURIComponent(tag)}/Aerove-android-arm64.apk` }] };
}
const stable = () => release('v0.9.1+2026102203');
const preview = () => release('v0.11.0-beta.17+2026102294', true);
const block = name => `<!-- aerove:${name}:start -->\nold ${name}\n<!-- aerove:${name}:end -->`;
const document = () => `untouched before\n${block('releases')}\n${block('showcase')}\nuntouched after\n`;
function sandbox(t) {
  const root = mkdtempSync(join(tmpdir(), 'aerove-readme-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  mkdirSync(join(root, 'docs/i18n'), { recursive: true });
  writeFileSync(join(root, 'README.md'), document());
  writeFileSync(join(root, 'docs/i18n/README.en.md'), document());
  return root;
}
// Tiny, solid-colour PNG fixtures only exercise the importer; they are not screenshot evidence.
function png() {
  const crc = data => { let c = 0xffffffff; for (const b of data) { c ^= b; for (let i = 0; i < 8; i++) c = (c >>> 1) ^ (c & 1 ? 0xedb88320 : 0); } return (c ^ 0xffffffff) >>> 0; };
  const chunk = (type, data) => { const body = Buffer.concat([Buffer.from(type), data]); const out = Buffer.alloc(body.length + 8); out.writeUInt32BE(data.length); body.copy(out, 4); out.writeUInt32BE(crc(body), out.length - 4); return out; };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(860); ihdr.writeUInt32BE(1864, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from('89504e470d0a1a0a', 'hex'), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(Buffer.alloc((860 * 4 + 1) * 1864))), chunk('IEND', Buffer.alloc(0))]);
}
function fixture(root) {
  const dir = join(root, 'input'); mkdirSync(dir);
  const bytes = png();
  const manifest = { schema: 1, product: 'standard', method: 'flutter-widget-test', source: { commit: '4'.repeat(40), tree: 'd'.repeat(40), ref: 'fixture', workflowRun: null, producerDirty: true, harness: { file: 'test.dart', sha256: 'a'.repeat(64) } }, viewport: { width: 430, height: 932, devicePixelRatio: 2 }, fixture: 'offline', androidDevice: false, pureBlack: false, complete: true,
    cases: CASE_IDS.map(id => { const [material, colorMode, brightness] = id.split('-'); return { id, material, colorMode, brightness, file: `${id}.png`, width: 860, height: 1864, sha256: hash(bytes) }; }) };
  for (const c of manifest.cases) writeFileSync(join(dir, c.file), bytes);
  const save = () => writeFileSync(join(dir, 'render-info.json'), JSON.stringify(manifest, null, 2)); save();
  return { dir, manifest, bytes, save };
}

test('selects published Standard channels by publication time, excluding draft and Lite', () => {
  const old = release('v99.0.0+1', false, '2025-01-01T00:00:00Z');
  const draft = { ...release('v5.0.0+5'), draft: true };
  const lite = release('lite-v1.0.0+6');
  const historic = release('v0.9.1-X+2026101782', false, '2026-10-17T00:00:00Z');
  const result = selectReleases([old, historic, preview(), draft, lite, stable()]);
  assert.equal(result.stable.tag_name, stable().tag_name);
  assert.equal(result.preview.tag_name, preview().tag_name);
});
test('rejects unavailable stable, API errors, malformed SemVer and mismatched channel', () => {
  for (const data of [[], [preview()], { message: 'API rate limit' }, [release('v01.2.3+4')], [release('v1.2.3-beta.01+4', true)], [release('v1.2.3+0')], [release('v1.2.3-beta.1+2')]]) assert.throws(() => selectReleases(data));
});
test('strict repository, exact tag and exact APK URL checks reject forged metadata', () => {
  for (const url of ['https://evil.test/x', `${repo}.evil.test/releases/tag/x`, `${repo}/releases/tag/v0.9.1+2026102203?x=1`, `${repo}/releases/tag/../x`]) {
    assert.throws(() => selectReleases([{ ...stable(), html_url: url }]));
  }
  const r = stable(); r.assets[0].browser_download_url = `${repo}/releases/download/v0.0.1/Aerove-android-arm64.apk`;
  assert.throws(() => selectReleases([r]));
});
test('markers preserve outside bytes and reject missing, repeated and reversed pairs', () => {
  assert.equal(replaceBlock(`prefix\r\n${block('releases')}\r\nsuffix`, 'releases', 'new'), `prefix\r\n<!-- aerove:releases:start -->\r\nnew\r\n<!-- aerove:releases:end -->\r\nsuffix`);
  for (const text of ['', `${block('releases')}${block('releases')}`, '<!-- aerove:releases:end --><!-- aerove:releases:start -->']) assert.throws(() => replaceBlock(text, 'releases', 'new'));
});
test('release-only update preserves showcase, actual API URLs and both languages; check never writes', t => {
  const root = sandbox(t); const before = readFileSync(join(root, 'README.md'));
  assert.equal(syncReadmes({ root, releases: [stable(), preview()], check: true }).changed, true);
  assert.deepEqual(readFileSync(join(root, 'README.md')), before);
  syncReadmes({ root, releases: [stable(), preview()] });
  const text = readFileSync(join(root, 'README.md'), 'utf8');
  assert.ok(text.includes(`[0.9.1](${stable().html_url})`)); assert.ok(text.includes('构建 `2026102203`'));
  assert.ok(text.includes(stable().assets[0].browser_download_url));
  assert.ok(text.includes(block('showcase'))); assert.ok(text.startsWith('untouched before\n'));
  assert.match(readFileSync(join(root, 'docs/i18n/README.en.md'), 'utf8'), /Public preview/);
  const time = statSync(join(root, 'README.md')).mtimeMs;
  assert.equal(syncReadmes({ root, releases: [stable(), preview()] }).changed, false);
  assert.equal(statSync(join(root, 'README.md')).mtimeMs, time);
});
test('imports twelve original PNGs and provenance bytes, is idempotent, and emits explicit offline boundary', t => {
  const root = sandbox(t); const f = fixture(root);
  syncReadmes({ root, releases: [stable(), preview()], previewDir: f.dir });
  const dest = join(root, 'docs/assets/screenshots/showcase');
  assert.deepEqual(readFileSync(join(dest, CASE_IDS[0] + '.png')), f.bytes);
  assert.deepEqual(readFileSync(join(dest, 'render-info.json')), readFileSync(join(f.dir, 'render-info.json')));
  assert.match(readFileSync(join(root, 'README.md'), 'utf8'), /离线/);
  assert.match(readFileSync(join(root, 'docs/i18n/README.en.md'), 'utf8'), /not Android device or Lite acceptance/);
  const mtime = statSync(join(dest, CASE_IDS[0] + '.png')).mtimeMs;
  assert.equal(syncReadmes({ root, releases: [stable(), preview()], previewDir: f.dir }).changed, false);
  assert.equal(statSync(join(dest, CASE_IDS[0] + '.png')).mtimeMs, mtime);
});
test('all invalid gallery inputs fail before any destination or README write', t => {
  const mutations = [f => f.manifest.cases.pop(), f => f.manifest.cases[1] = f.manifest.cases[0], f => f.manifest.cases[0].file = '../escape.png', f => f.manifest.cases[0].sha256 = '0'.repeat(64), f => f.manifest.complete = false, f => f.manifest.source.commit = 'HEAD', f => f.manifest.androidDevice = true, f => f.manifest.cases[0].width = 1, f => writeFileSync(join(f.dir, 'extra.png'), f.bytes), f => { const b = Buffer.from(f.bytes); b[12] = 0; writeFileSync(join(f.dir, f.manifest.cases[0].file), b); f.manifest.cases[0].sha256 = hash(b); }];
  for (const mutate of mutations) {
    const root = sandbox(t); const f = fixture(root); mutate(f); f.save();
    const before = readFileSync(join(root, 'README.md'));
    assert.throws(() => syncReadmes({ root, releases: [stable()], previewDir: f.dir }));
    assert.deepEqual(readFileSync(join(root, 'README.md')), before);
    assert.throws(() => statSync(join(root, 'docs/assets/screenshots/showcase')));
  }
});
test('valid gallery plus broken second README marker leaves everything untouched', t => {
  const root = sandbox(t); const f = fixture(root); writeFileSync(join(root, 'docs/i18n/README.en.md'), 'missing markers');
  assert.throws(() => syncReadmes({ root, releases: [stable()], previewDir: f.dir }));
  assert.equal(readFileSync(join(root, 'README.md'), 'utf8'), document());
  assert.throws(() => statSync(join(root, 'docs/assets/screenshots/showcase')));
});
test('target directory obstruction is caught before changing either README', t => {
  const root = sandbox(t); const f = fixture(root);
  mkdirSync(join(root, 'docs/assets'));
  writeFileSync(join(root, 'docs/assets/screenshots'), 'not a directory');
  assert.throws(() => syncReadmes({ root, releases: [stable()], previewDir: f.dir }));
  assert.equal(readFileSync(join(root, 'README.md'), 'utf8'), document());
});
test('CLI distinguishes check drift, success and failure without writing on check or invalid data', t => {
  const root = sandbox(t); const metadata = join(root, 'releases.json');
  writeFileSync(metadata, JSON.stringify([stable(), preview()]));
  const cli = fileURLToPath(new URL('./readme_sync.mjs', import.meta.url));
  const run = (...extra) => spawnSync(process.execPath, [cli, '--root', root, '--releases', metadata, ...extra], { encoding: 'utf8' });
  assert.equal(run('--check').status, 1);
  assert.equal(readFileSync(join(root, 'README.md'), 'utf8'), document());
  assert.equal(run().status, 0); assert.equal(run('--check').status, 0);
  const before = readFileSync(join(root, 'README.md'));
  writeFileSync(metadata, JSON.stringify({ message: 'API failure' }));
  assert.equal(run().status, 2);
  assert.deepEqual(readFileSync(join(root, 'README.md')), before);
});
