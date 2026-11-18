import assert from 'node:assert/strict';
import test from 'node:test';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { runInThisContext } from 'node:vm';
import { CID } from 'multiformats/cid';

globalThis.window = globalThis;
const wnfsEnabled = process.env.AWAI_DRIVE_WNFS_SYNC === '1';
runInThisContext(await readFile(new URL('../generated/core.js', import.meta.url), 'utf8'));
const legacy = globalThis.AwaiDriveEncrypted;
if (wnfsEnabled) runInThisContext(await readFile(new URL('../generated/wnfs-core.js', import.meta.url), 'utf8'));

const sha = bytes => createHash('sha256').update(bytes).digest('hex');

test('WNFS copy snapshot carries more than 8 MiB across bounded CAR packs and retains v1 readback', { skip: !wnfsEnabled }, async () => {
  const wnfs = globalThis.AwaiDriveWnfs;
  const unlock = crypto.getRandomValues(new Uint8Array(32));
  const identity = await legacy.newRecord(unlock);
  const old = [{ path: 'existing.txt', bytes: new Uint8Array([1, 2, 3]) }];
  const previous = await legacy.sealSnapshot(identity, unlock, old);
  const image = new Uint8Array(9 * 1024 * 1024 + 17).fill(25);
  const video = new Uint8Array(10 * 1024 * 1024 + 29).fill(91);
  const rows = [
    ...old,
    { path: 'photos', directory: true },
    { path: 'photos/test.jpg', bytes: image },
    { path: 'test.mp4', bytes: video },
    { path: 'empty', directory: true }
  ];

  const { capsule, packs, basis } = await wnfs.seal(identity, unlock, rows);
  assert.equal(CID.parse(basis).multihash.code, 0x12);
  assert.ok(packs.length > 1);
  assert.ok(packs.every(bytes => bytes.length <= 12 * 1024 * 1024));
  const snapshot = await wnfs.openSnapshot(identity, unlock, capsule.root, capsule.bytes, packs);
  assert.equal(snapshot.basis, basis);
  assert.deepEqual(wnfs.project(snapshot)(basis, 'JPG'), [['photos/test.jpg', image.length]]);
  const restored = snapshot.rows;
  const byPath = new Map(restored.map(row => [row.path, row]));
  assert.equal(byPath.size, rows.length);
  for (const row of rows) {
    const restoredRow = byPath.get(row.path);
    if (row.directory) {
      assert.equal(restoredRow.directory, true);
    } else {
      const digest = createHash('sha256');
      for (const chunk of restoredRow.chunks) digest.update(chunk);
      assert.equal(digest.digest('hex'), sha(row.bytes));
    }
  }

  const wrong = unlock.slice();
  wrong[0] ^= 1;
  await assert.rejects(wnfs.open(identity, wrong, capsule.root, capsule.bytes, packs));
  await assert.rejects(wnfs.open(identity, unlock, capsule.root, capsule.bytes, packs.slice(1)));
  const torn = packs[0].slice();
  torn[torn.length - 1] ^= 1;
  await assert.rejects(wnfs.open(identity, unlock, capsule.root, capsule.bytes, [torn, ...packs.slice(1)]));
  const prior = await legacy.openSnapshot(identity, unlock, previous.root, previous.bytes);
  assert.deepEqual(prior[0].bytes, old[0].bytes);
  const { rows: catalog, basis: catalogBasis } = await wnfs.catalog(identity, unlock, capsule.root, capsule.bytes, packs);
  assert.equal(catalogBasis, basis);
  assert.deepEqual(catalog.map(row => row.path).sort(), rows.map(row => row.path).sort());
  assert.ok(catalog.every(row => !Object.hasOwn(row, 'chunks')));
  assert.equal(catalog.find(row => row.path === 'empty').directory, true);
  await assert.rejects(wnfs.catalog(identity, wrong, capsule.root, capsule.bytes, packs));
  await assert.rejects(wnfs.catalog(identity, unlock, capsule.root, capsule.bytes, [torn, ...packs.slice(1)]));
  assert.deepEqual(await legacy.snapshotCatalog(identity, unlock, previous.root, previous.bytes),
    [{ path: 'existing.txt', bytes: 3 }]);
  unlock.fill(0);
});

test('a distinct owner and recipient share a basis, and a revoked recipient cannot open a new forest', { skip: !wnfsEnabled }, async () => {
  const { AwaiDriveEncrypted: owner, AwaiDriveWnfs: wnfs } = globalThis;
  const alice = crypto.getRandomValues(new Uint8Array(32));
  const bob = crypto.getRandomValues(new Uint8Array(32));
  try {
    const a = await owner.newRecord(alice), b = await owner.newRecord(bob);
    const recipient = await wnfs.publicRecipient(b);
    assert.deepEqual(Object.keys(recipient).sort(), ['id', 'pq', 'pub']);
    const first = await wnfs.seal(a, alice, [{ path: 'first.txt', bytes: Uint8Array.of(1, 2) }], [recipient]);
    const { capsule } = first.grants[0];
    const opened = await wnfs.openSnapshot(b, bob, capsule.root, capsule.bytes, first.packs);
    assert.equal(opened.basis, first.basis);
    assert.deepEqual(wnfs.inventory(opened.rows), [{ path: 'first.txt', directory: false, size: 2, sha256: sha(Uint8Array.of(1, 2)) }]);
    const { createWnfsSync } = await import('../src/wnfs-sync.js');
    const origin = 'https://storage.example';
    const blocks = new Map([capsule.bytes, ...first.packs].map(bytes => [sha(bytes), bytes]));
    const subject = 'did:key:zExisting';
    const grant = { schema: 'awai.drive.wnfs.grant.v1', recipient: recipient.id, basis: first.basis,
      head: { schema: 'awai.drive.wnfs.v1', origin, root: capsule.root, capsule: sha(capsule.bytes),
        packs: first.packs.map(sha) } };
    let downloads = 0;
    const reader = createWnfsSync({
      origin,
      packs: { async download(id) { downloads++; return blocks.get(id).slice(); } },
      async compute(op, session, input) {
        if (op === 'public-recipient') return wnfs.publicRecipient(session.record);
        assert.equal(op, 'catalog');
        return wnfs.catalog(session.record, session.secret, input.root, input.capsule, input.packs);
      },
    });
    const sources = [{ subject, deviceId: 'sender-device', grant }];
    await assert.rejects(reader.catalogAccountRecipients({record: a, secret: alice}, {subject, sources}),
      /invalid-snapshot-grant/);
    assert.equal(downloads, 0);
    assert.deepEqual(await reader.catalogAccountRecipients({record: b, secret: bob}, {subject, sources}),
      [{path: 'first.txt', directory: false, sourceDevice: 'sender-device'}]);
    await assert.rejects(wnfs.open(a, alice, capsule.root, capsule.bytes, first.packs));
    const next = await wnfs.seal(a, alice, [{ path: 'next.txt', bytes: Uint8Array.of(3) }], []);
    assert.equal(next.grants.length, 0);
    assert.notEqual(next.basis, first.basis);
    await assert.rejects(wnfs.open(b, bob, next.capsule.root, next.capsule.bytes, next.packs));
    await assert.rejects(wnfs.open(b, bob, capsule.root, capsule.bytes, next.packs));
    assert.equal((await wnfs.openSnapshot(b, bob, capsule.root, capsule.bytes, first.packs)).basis, first.basis);
  } finally { alice.fill(0); bob.fill(0); }
});

test('Drive only commits WNFS after all readbacks, and restores into a staged copy', async () => {
  const { createWnfsSync } = await import('../src/wnfs-sync.js');
  const { createDrivePackClient } = await import('../vendor/platform/drive-packs.mjs');
  const origin = 'https://storage.example';
  const bytes = Uint8Array.of(7, 8, 9);
  const media = { path: 'media.mp4', size: bytes.length, sha256: sha(bytes), directory: false };
  const transport = new Map();
  const nativeOps = [];
  const requests = [];
  let denyReadback = true;
  let committed;
  const broker = { async invoke({ operation }, { input, init }) {
    const id = new URL(input).pathname.split('/').at(-1);
    requests.push(operation);
    if (operation === 'upload') {
      transport.set(id, init.body.slice());
      return Response.json({ sha256: id, bytes: init.body.length });
    }
    if (denyReadback) throw Error('operation-denied');
    return new Response(transport.get(id));
  } };
  const sync = createWnfsSync({
    origin,
    packs: createDrivePackClient({ origin, capability: 'awai.drive.sync', broker, sha256: sha }),
    verifyReceipt: (_receipt, { id }) => assert.ok(transport.has(id)),
    sha256: sha,
    async invoke(op, args) {
      nativeOps.push(op);
      if (op === 'vaultWnfsRecipients') return { revision: 0, items: {} };
      if (op === 'vaultWnfsInventory') return { entries: [media] };
      if (op === 'vaultRead') return { bytes: Buffer.from(bytes).toString('base64') };
      if (op === 'vaultWnfsCommit') committed = args;
      if (op === 'vaultWnfsRestoreBegin') return { operation: 'stage-test' };
      if (op === 'vaultWnfsRestoreWrite') {
        assert.equal(args.operation, 'stage-test');
        assert.equal(args.path, media.path);
        assert.equal(args.offset, 0);
        assert.deepEqual(Buffer.from(args.bytes, 'base64'), Buffer.from(bytes));
      }
      if (op === 'vaultWnfsRestorePublish') {
        assert.equal(args.operation, 'stage-test');
        return { folder: 'Recovered test' };
      }
    },
    async compute(operation) {
      if (operation === 'seal') return {
        packs: [Uint8Array.of(1), Uint8Array.of(2)],
        capsule: { root: 'a'.repeat(64), bytes: Uint8Array.of(3) }
      };
      return [{ path: media.path, chunks: [bytes.slice()] }];
    }
  });

  await assert.rejects(sync.sync({}), /operation-denied/);
  assert.equal(committed, undefined);
  denyReadback = false;
  requests.length = 0;
  await sync.sync({});
  assert.deepEqual(requests, ['upload', 'download', 'upload', 'download', 'upload', 'download']);
  assert.equal(committed.packs.length, 2);
  const head = { ...committed, schema: 'awai.drive.wnfs.v1' };
  const count = nativeOps.length;
  assert.deepEqual(await sync.restore({ wnfs: head }), { folder: 'Recovered test' });
  assert.deepEqual(nativeOps.slice(count), ['vaultWnfsRestoreBegin', 'vaultWnfsRestoreWrite', 'vaultWnfsRestorePublish']);
});

test('account catalog requires bound sources and reports only verified snapshot metadata', async () => {
  const { createWnfsSync } = await import('../src/wnfs-sync.js');
  const origin = 'https://storage.example';
  const subject = 'did:pkh:eip155:8453:0x1';
  const basis = 'b' + 'a'.repeat(58);
  const otherBasis = 'b' + 'b'.repeat(58);
  const ids = n => n.repeat(64);
  const grants = [
    { subject, deviceId: 'desktop', basis, owner: { record: 'fixture-key-a', secret: new Uint8Array(32),
      wnfs: { schema: 'awai.drive.wnfs.v1', origin, root: ids('a'), capsule: ids('b'), packs: [ids('c')] } } },
    { subject, deviceId: 'phone', basis: otherBasis, owner: { record: 'fixture-key-b', secret: new Uint8Array(32),
      wnfs: { schema: 'awai.drive.wnfs.v1', origin, root: ids('d'), capsule: ids('e'), packs: [ids('f')] } } },
  ];
  const requests = [];
  let denied;
  const wnfs = createWnfsSync({
    origin,
    invoke: () => assert.fail('Catalog cannot mutate either vault'),
    packs: { async download(id) {
      requests.push(id);
      if (id === denied) throw Error('operation-denied');
      return Uint8Array.of(1);
    } },
    async compute(operation, owner) {
      assert.equal(operation, 'catalog');
      const a = owner.record === 'fixture-key-a';
      return { basis: a ? basis : otherBasis, rows: [{ path: a ? 'trip/sky.jpg' : 'trip/ball.mp4' }] };
    }
  });
  assert.deepEqual(await wnfs.catalogAuthorizedAccount({ subject, sources: grants }), [
    { path: 'trip/sky.jpg', directory: false, sourceDevice: 'desktop' },
    { path: 'trip/ball.mp4', directory: false, sourceDevice: 'phone' },
  ]);
  assert.deepEqual(requests, [ids('b'), ids('c'), ids('e'), ids('f')]);
  requests.length = 0;
  await assert.rejects(wnfs.catalogAuthorizedAccount({ subject, sources: [grants[0], { ...grants[1], subject: 'did:key:other' }] }),
    /invalid-account-snapshot-grant/);
  await assert.rejects(wnfs.catalogAuthorizedAccount({ subject, sources: [grants[0], grants[0]] }),
    /invalid-account-snapshot-grant/);
  await assert.rejects(wnfs.catalogAuthorizedAccount({ subject, sources: [grants[0], {
    ...grants[1], owner: { ...grants[1].owner, wnfs: { ...grants[1].owner.wnfs, origin: 'https://other.example' } }
  }] }), /recovery-required/);
  assert.deepEqual(requests, []);
  await assert.rejects(wnfs.catalogAuthorizedAccount({ subject, sources: [{ ...grants[0], basis: otherBasis }, grants[1]] }),
    /snapshot-basis-mismatch/);
  assert.deepEqual(requests, [ids('b'), ids('c')]);
  requests.length = 0;
  denied = ids('e');
  await assert.rejects(wnfs.catalogAuthorizedAccount({ subject, sources: grants }), /operation-denied/);
  assert.deepEqual(requests, [ids('b'), ids('c'), ids('e')]);
});
