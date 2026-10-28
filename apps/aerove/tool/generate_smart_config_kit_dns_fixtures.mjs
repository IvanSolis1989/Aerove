import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

export const fixedSource = Object.freeze({
  revision: '3971964b4089b38c6a1233537e1cf2e44bf2be4d',
  gitBlob: '9dc3bb723779f835f94d0267aac401c735a2ac71',
  scriptSha256: '8bbbe11ca809c1c49e56919e4aef4d4480683a7c9d843bd8875947d6c4cd9d9e',
});

export function syntheticInput(kind) {
  assert.ok(['baseline', 'private'].includes(kind));
  const config = {
    proxies: [
      { name: 'HK Node', type: 'ss', server: 'hk.node.fixture.test', port: 443, cipher: 'aes-128-gcm', password: 'synthetic' },
      { name: 'US Node', type: 'ss', server: 'us.node.fixture.test', port: 443, cipher: 'aes-128-gcm', password: 'synthetic' },
    ],
    'proxy-groups': [],
    rules: [],
  };
  if (kind === 'private') {
    config.dns = {
      nameserver: ['https://unrelated-global.fixture.test/dns-query'],
      fallback: ['https://unrelated-fallback.fixture.test/dns-query'],
      'proxy-server-nameserver': ['https://unrelated-node-global.fixture.test/dns-query'],
      'nameserver-policy': { '+.unrelated.fixture.test': ['https://unrelated-policy.fixture.test/dns-query'] },
      'fake-ip-filter-mode': 'whitelist',
      'fake-ip-filter': ['unrelated.fixture.test'],
      'proxy-server-nameserver-policy': {
        'hk.node.fixture.test': ['https://resolver.exact.fixture.test/dns-query'],
        'us.node.fixture.test': ['https://resolver.fallback.fixture.test/dns-query'],
        'unused.node.fixture.test': ['https://unused.fixture.test/dns-query'],
      },
    };
  }
  return config;
}

export function runDns(source, input) {
  const context = vm.createContext(Object.create(null), {
    codeGeneration: { strings: false, wasm: false },
    microtaskMode: 'afterEvaluate',
  });
  vm.runInContext(`globalThis.console = Object.freeze({ log() {}, warn() {}, error() {} });`, context, { timeout: 3000 });
  const unavailable = vm.runInContext(
    '[typeof require, typeof process, typeof fetch, typeof WebSocket, typeof XMLHttpRequest, typeof setTimeout]',
    context,
    { timeout: 3000 },
  );
  assert.ok(Array.from(unavailable).every(value => value === 'undefined'));
  new vm.Script(source, { filename: 'fixed-smart-config-kit.js' }).runInContext(context, { timeout: 3000 });
  const output = vm.runInContext(
    `JSON.stringify(main(${JSON.stringify(input)}).dns, null, 2)`,
    context,
    { timeout: 3000 },
  );
  assert.equal(typeof output, 'string');
  return output + '\n';
}

export function fixtureSource() {
  return {
    ...fixedSource,
    generation: 'Fixed synthetic Node vm main(config) execution; DNS extracted from the current source result and checked against the saved fixtures.',
    generator: 'tool/generate_smart_config_kit_dns_fixtures.mjs',
    syntheticOnly: true,
    networkRequests: 0,
    baseline: 'Two synthetic FQDN nodes; no source DNS.',
    private: 'Same nodes; exact node resolver hints, an unused node hint and unrelated global DNS. Only active-node proxy-server-nameserver-policy survives.',
    previousRevision: '70d4ddbe0b6d52af7acbd503ff1bf556a3bddacd',
    previousGitBlob: '65beff76d400f51d21686b04b09ccc40df6e620e',
    previousScriptSha256: '0f8c6d3c6385e2a22b6435a5ade37c6236f376531749bf446642ce481977f6ec',
  };
}

function main() {
  const args = process.argv.slice(2);
  const rootIndex = args.indexOf('--root');
  const root = rootIndex < 0 ? resolve(dirname(fileURLToPath(import.meta.url)), '..') : resolve(args[rootIndex + 1]);
  const write = args.includes('--write');
  assert.ok(args.every((value, i) => ['--root', '--check', '--write'].includes(value) || (rootIndex >= 0 && i === rootIndex + 1)));
  assert.ok(!(write && args.includes('--check')));
  const source = Buffer.from(
    readFileSync(join(root, 'assets/data/smart-config-kit-flclash.js'), 'utf8').replace(/\r\n/g, '\n'),
    'utf8',
  );
  assert.equal(createHash('sha256').update(source).digest('hex'), fixedSource.scriptSha256);
  assert.equal(createHash('sha1').update(`blob ${source.length}\0`).update(source).digest('hex'), fixedSource.gitBlob);
  const metadata = JSON.parse(readFileSync(join(root, 'assets/data/smart-config-kit-flclash.source.json')));
  assert.equal(metadata.revision, fixedSource.revision);
  assert.equal(metadata.gitBlob, fixedSource.gitBlob);
  assert.equal(metadata.sha256, fixedSource.scriptSha256);
  assert.equal(metadata.version, 'v6.0.21-flclash.24');
  const outputs = new Map();
  for (const kind of ['baseline', 'private']) {
    outputs.set(`dns-${kind}.json`, runDns(source.toString('utf8'), syntheticInput(kind)));
  }
  outputs.set('source.json', JSON.stringify(fixtureSource(), null, 2) + '\n');
  for (const [name, bytes] of outputs) {
    const path = join(root, 'test/fixtures/smart_config_kit', name);
    if (write) writeFileSync(path, bytes);
    else assert.equal(readFileSync(path, 'utf8').replace(/\r\n/g, '\n'), bytes, name);
  }
  console.log(JSON.stringify({ source: fixedSource, fixtures: 2, mode: write ? 'write' : 'check', networkRequests: 0, hostAPIsExposed: false }));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
