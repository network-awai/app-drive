import assert from 'node:assert/strict';
import test from 'node:test';

globalThis.window = globalThis;
await import('../generated/core.js');
const owner = globalThis.AwaiDriveEncrypted;
const wnfs = globalThis.AwaiDriveWNFS;

test('an encrypted WNFS snapshot reopens with the same basis and indexed results', async () => {
  const secret = crypto.getRandomValues(new Uint8Array(32));
  try {
    const record = await owner.newRecord(secret);
    const large = new Uint8Array(262121).fill(77);
    const entries = [
      { path: '資料/readme.txt', bytes: Uint8Array.of(1, 2, 3) },
      { path: 'data/payload.bin', bytes: large },
      { path: 'blank.txt', bytes: new Uint8Array() }
    ];
    entries.directories = ['empty'];
    const sealed = await wnfs.sealSnapshot(record, secret, entries);
    assert.ok(sealed.basis.startsWith('bafy'));
    const restored = await wnfs.openSnapshot(record, secret, sealed.root, sealed.bytes);
    assert.equal(restored.basis, sealed.basis);
    assert.deepEqual(Array.from(restored.directories), entries.directories);
    assert.deepEqual(restored.find(row => row.path === 'data/payload.bin').bytes, large);
    const query = wnfs.project(restored, restored.basis);
    assert.deepEqual(query(sealed.basis, 'txt'), [['blank.txt', 0], ['資料/readme.txt', 3]]);
    assert.throws(() => query('different-basis', 'txt'));
    const modified = sealed.bytes.slice();
    modified[modified.length - 1] ^= 1;
    await assert.rejects(wnfs.openSnapshot(record, secret, sealed.root, modified));
    await assert.rejects(wnfs.openSnapshot(record, new Uint8Array(32), sealed.root, sealed.bytes));
    const legacy = await owner.sealSnapshot(record, secret, [{ path: 'old.txt', bytes: Uint8Array.of(7) }]);
    const legacyFiles = await wnfs.openSnapshot(record, secret, legacy.root, legacy.bytes);
    assert.equal(legacyFiles[0].path, 'old.txt');
  } finally {
    secret.fill(0);
  }
});
