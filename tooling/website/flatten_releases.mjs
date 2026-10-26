import { readFileSync, writeFileSync } from 'node:fs';

const [input, output] = process.argv.slice(2);
if (!input || !output) {
  console.error('usage: flatten_releases.mjs <paginated JSON> <flat releases.json>');
  process.exit(64);
}
const MAX_PAGES = 100;
const MAX_RELEASES = 10_000;
const pages = JSON.parse(readFileSync(input, 'utf8'));
if (!Array.isArray(pages) || pages.length > MAX_PAGES) {
  throw new Error(`expected at most ${MAX_PAGES} paginated release arrays`);
}
const releases = [];
for (const [pageIndex, page] of pages.entries()) {
  if (!Array.isArray(page)) throw new Error(`release page ${pageIndex + 1} is not an array`);
  if (releases.length + page.length > MAX_RELEASES) {
    throw new Error(`release count exceeds ${MAX_RELEASES}`);
  }
  for (const [releaseIndex, release] of page.entries()) {
    if (!release || typeof release !== 'object' || Array.isArray(release) ||
        typeof release.tag_name !== 'string' || typeof release.draft !== 'boolean' ||
        typeof release.prerelease !== 'boolean' || !Array.isArray(release.assets)) {
      throw new Error(`invalid release object at page ${pageIndex + 1}, item ${releaseIndex + 1}`);
    }
    releases.push(release);
  }
}
writeFileSync(output, `${JSON.stringify(releases)}\n`, { flag: 'wx' });
