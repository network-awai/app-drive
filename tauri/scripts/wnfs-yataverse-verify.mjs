// Operator-run synthetic qualification. In-app traffic uses the canonical
// operation broker; this Node process performs only the two fixed CLI methods.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { createDrivePackClient } from '../vendor/platform/drive-packs.mjs';
import { verifyDrivePackReceipt } from '../receipt/verify.mjs';

globalThis.window = globalThis;
await import('../generated/core.js');
const owner = globalThis.AwaiDriveEncrypted;
const wnfs = globalThis.AwaiDriveWNFS;
const origin = 'https://yataverse.com';
const keys = JSON.parse(await readFile(new URL('../receipt-trust.json', import.meta.url)))[origin];
const network = [];
const packs = createDrivePackClient({
  origin, capability: 'awai.drive.sync', sha256: owner.sha256,
  broker: { async invoke({ operation }, { input, init }) {
    const method = { upload: 'PUT', download: 'GET' }[operation];
    assert.equal(method, init.method);
    assert.match(input, /^https:\/\/yataverse\.com\/awai-drive\/v1\/packs\/[0-9a-f]{64}$/);
    const started = performance.now();
    const response = await fetch(input, {
      ...init, cache: 'no-store', credentials: 'omit', redirect: 'error', signal: AbortSignal.timeout(30000)
    });
    network.push({ operation, responseStatus: response.status, headersMs: performance.now() - started });
    return response;
  } }
});
const expectedQuery = [['blank.txt', 0], ['資料/readme.txt', 3]];
const fixture = () => [
  { path: '資料/readme.txt', bytes: Uint8Array.of(1, 2, 3) },
  { path: 'data/payload.bin', bytes: new Uint8Array(262121).fill(77) },
  { path: 'blank.txt', bytes: new Uint8Array() }
];

async function open(control, secret, ciphertext) {
  const started = performance.now();
  const rows = await wnfs.openSnapshot(control.record, secret, control.root, ciphertext);
  const opened = performance.now();
  assert.equal(rows.basis, control.basis);
  const q = wnfs.project(rows, control.basis);
  const indexed = performance.now();
  const results = q(control.basis, 'txt');
  const queried = performance.now();
  assert.deepEqual(results, expectedQuery);
  for (const expected of fixture()) {
    const actual = rows.find(row => row.path === expected.path);
    assert.ok(actual);
    assert.deepEqual(actual.bytes, expected.bytes);
    actual.bytes.fill(0);
  }
  return { basis: rows.basis, rows: rows.length, matches: results.length,
    openMs: opened - started, indexMs: indexed - opened, queryMs: queried - indexed };
}

async function cold() {
  // Only the sealed identity, trusted locator and synthetic unlock enter a
  // fresh process through stdin. There is no local CAR, file or graph cache.
  const input = JSON.parse(await readFile('/dev/stdin', 'utf8'));
  const secret = Uint8Array.from(input.secret);
  input.secret.fill(0);
  try {
    const started = performance.now();
    const ciphertext = await packs.download(input.pack);
    const downloadMs = performance.now() - started;
    console.log(JSON.stringify({ downloadMs, ...await open(input, secret, ciphertext), network }));
  } finally {
    secret.fill(0);
  }
}

async function restoreCold(control, secret) {
  return new Promise((resolve, reject) => {
    const process = spawn(globalThis.process.execPath,
      [fileURLToPath(import.meta.url), '--cold'], { stdio: ['pipe', 'pipe', 'inherit'] });
    let output = '';
    process.stdout.on('data', data => { output += data; });
    process.on('error', reject);
    process.on('exit', code => {
      if (code) return reject(Error('fresh process refused restore'));
      try { resolve(JSON.parse(output)); } catch (error) { reject(error); }
    });
    process.stdin.on('error', reject);
    process.stdin.end(JSON.stringify({ ...control, secret: Array.from(secret) }));
  });
}

async function run(count) {
  assert.ok(Number.isInteger(count) && count > 0 && count <= 10);
  for (let sample = 1; sample <= count; sample++) {
    network.length = 0;
    const secret = crypto.getRandomValues(new Uint8Array(32));
    try {
      const record = await owner.newRecord(secret);
      const started = performance.now();
      const sealed = await wnfs.sealSnapshot(record, secret, fixture());
      const sealMs = performance.now() - started;
      const control = { record, root: sealed.root, basis: sealed.basis };
      const first = await open(control, secret, sealed.bytes);
      const publishStarted = performance.now();
      const pack = await packs.publish(sealed.bytes, (receipt, expected) =>
        verifyDrivePackReceipt(receipt, keys, expected));
      const publishMs = performance.now() - publishStarted;
      control.pack = pack;
      const bytes = sealed.bytes.length;
      sealed.bytes.fill(0);
      const restored = await restoreCold(control, secret);
      assert.equal(restored.basis, first.basis);
      console.log(JSON.stringify({ sample, bytes, sealMs, publishMs, first, restored, network }));
    } finally {
      secret.fill(0);
    }
  }
}

if (process.argv[2] === '--cold') {
  await cold();
} else {
  assert.equal(process.argv[2], '--synthetic-live');
  await run(Number(process.argv[3] ?? 1));
}
