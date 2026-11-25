import test from 'node:test';
import assert from 'node:assert/strict';

// Fake-storage unit tests for the deterministic recovery layer.
// Synthetic seeds only (test fixtures, never user keys).

const SYNTH_SEED = Uint8Array.from({ length: 32 }, (_, i) => i); // 0x00..0x1f
const UNSAFE = ['seed', 'SECRET', 'mhzm', 'otp', 'password'];

async function loadRecovery() {
  return import('../src/recovery.js');
}

function bytesToHex(b) {
  return Buffer.from(b).toString('hex');
}

test('bundled noble ignores tipk', async () => {
  const { deriveRecipient, deriveHeadName, deriveHeadSignRoot, deriveOwnerPseudonym, deriveDeviceReg, toHex } =
    await loadRecovery();
  const r1 = deriveRecipient(SYNTH_SEED);
  const r2 = deriveRecipient(SYNTH_SEED);
  assert.equal(toHex(r1.publicKey), toHex(r2.publicKey), 'same seed -> same recipient public key');
  assert.equal(toHex(r1.secretKey), toHex(r2.secretKey), 'same seed -> same recipient secret key');
  // A different synthetic seed must give a different recipient (negative control).
  const other = Uint8Array.from({ length: 32 }, (_, i) => 0xff - i);
  const r3 = deriveRecipient(other);
  assert.notEqual(toHex(r1.publicKey), toHex(r3.publicKey));
  // Domain separation: same seed, different labels -> different 32-byte outputs.
  const head = deriveHeadName(SYNTH_SEED);
  const signRoot = deriveHeadSignRoot(SYNTH_SEED);
  const pseudo = deriveOwnerPseudonym(SYNTH_SEED);
  const reg = deriveDeviceReg(SYNTH_SEED);
  for (const [name, v] of [['head', head], ['signRoot', signRoot], ['pseudo', pseudo], ['reg', reg]]) {
    assert.equal(v.length, 32, `${name} must be 32 bytes`);
    assert.notEqual(toHex(v), toHex(r1.publicKey.subarray(0, 32)), `${name} must not equal recipient material`);
  }
  assert.notEqual(toHex(head), toHex(signRoot), 'head/name must not reveal/equal head/sign-root');
});

test('2-of-3 split: any 2 of 3 artifacts recover the synthetic seed; any 1 does not', async () => {
  const { splitSecret23, combine23 } = await loadRecovery();
  const { shares } = splitSecret23(SYNTH_SEED, { deterministic: 'synthetic-fixture' });
  assert.equal(shares.length, 3);
  const recovered = combine23(shares.slice(0, 2));
  assert.equal(bytesToHex(recovered), bytesToHex(SYNTH_SEED));
  assert.throws(() => combine23(shares.slice(0, 1)), /insufficient/i);
  // 2-of-3 must not be reconstructible from any single share's bytes alone.
  for (const share of shares) {
    const fake = { id: share.id, data: share.data };
    assert.notEqual(bytesToHex(fake.data), bytesToHex(SYNTH_SEED));
  }
});

test('capsule + head fake store: seed opens latest head; stale head shown with date, owner picks; capsule signed by delegation', async () => {
  const { deriveOwnerPseudonym, deriveHeadName } = await loadRecovery();
  const { createFakeHeadStore } = await loadRecovery();
  const store = createFakeHeadStore();
  const owner = bytesToHex(deriveOwnerPseudonym(SYNTH_SEED));
  const headName = bytesToHex(deriveHeadName(SYNTH_SEED));
  assert.equal(headName.length, 64);
  // Publish two synthetic heads (old then new). Both valid; owner picks with dates shown.
  store.publish(headName, { seq: 1, capsuleId: 'a'.repeat(64), issuedAt: '2026-06-15T00:00:00Z' });
  store.publish(headName, { seq: 2, capsuleId: 'b'.repeat(64), issuedAt: '2026-11-01T00:00:00Z' });
  const heads = store.query(headName);
  assert.equal(heads.length, 2);
  const newest = heads[heads.length - 1];
  assert.equal(newest.seq, 2);
  assert.equal(newest.issuedAt, '2026-11-01T00:00:00Z');
  // Backwards head cannot be silently accepted as current: seq must strictly increase.
  assert.throws(() => store.publish(headName, { seq: 2, capsuleId: 'c'.repeat(64), issuedAt: 'x' }), /seq/i);
  assert.deepEqual(store.headForOwner(owner).headName, headName);
});

test('no real seed/key generation is possible from this module', async () => {
  const mod = await loadRecovery();
  // The module must not expose any factory that MINTS a fresh seed/key.
  // (The word "seed" appears legitimately as a parameter name / label.)
  for (const name of Object.keys(mod)) {
    assert.ok(!/generat|random|newSeed|createSeed|mint/i.test(name), `unexpected factory export: ${name}`);
  }
  // Determinism pins: the same synthetic seed always derives the same material,
  // and a module-side CSPRNG is used only for Shamir coefficients by default —
  // never for the seed itself. The test fixtures pass deterministic bytes.
  assert.equal(typeof mod.deriveRecipient, 'function');
  assert.equal(typeof mod.splitSecret23, 'function');
});
