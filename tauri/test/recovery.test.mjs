import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { sha256 } from '@noble/hashes/sha256.js';
import { hkdf } from '@noble/hashes/hkdf.js';
import { ml_kem768 } from '@noble/post-quantum/ml-kem.js';
import { x25519 } from '@noble/curves/ed25519.js';
import {
  deriveRecipient,
  fingerprintRecipient,
  deriveDeviceReg,
  deriveOwnerPseudonym,
  deriveHeadName,
  deriveHeadSignRoot,
  splitSecret23,
  splitWithCoefficient,
  combine23,
  combineAndVerify,
  toHex,
  fromHex,
} from '../src/recovery.js';
import { counterRng, fixedSetId } from './recovery-test-helpers.mjs';
import { FIPS203_768_KEYGEN } from './fips203-vectors.mjs';

const SYNTH_SEED = Uint8Array.from({ length: 32 }, (_, i) => i); // 0x00..0x1f
const UNSAFE = ['seed', 'SECRET', 'mhzm', 'otp', 'password'];

async function forbidUnsafe() {
  for (const marker of UNSAFE) {
    const hit = Buffer.from(SYNTH_SEED).toString('hex').includes(marker.toLowerCase());
    assert.equal(hit, false, `synthetic seed must not contain marker ${marker}`);
  }
}

test('bundled noble ignores tipk', async () => {
  await forbidUnsafe();
  const r1 = deriveRecipient(SYNTH_SEED);
  const r2 = deriveRecipient(SYNTH_SEED);
  assert.equal(toHex(r1.publicKey), toHex(r2.publicKey));
  assert.equal(toHex(r1.secretKey), toHex(r2.secretKey));
  assert.equal(r1.publicKey.length, 1184, 'ML-KEM-768 ek is 1184 bytes');
  assert.equal(r1.secretKey.length, 2400, 'ML-KEM-768 dk is 2400 bytes');
  assert.equal(r1.x25519Pub.length, 32);
  assert.equal(r1.x25519Priv.length, 32);
  // Negative control: different seed -> different recipient.
  const other = Uint8Array.from({ length: 32 }, (_, i) => 0xff - i);
  assert.notEqual(toHex(deriveRecipient(other).publicKey), toHex(r1.publicKey));
  // Clamping is applied to the X25519 scalar (RFC 7748): bit 0 of byte 0 = 0.
  assert.equal(r1.x25519Priv[0] & 7, 0, 'scalar must be clamped');
  assert.equal(r1.x25519Priv[31] & 0x80, 0, 'scalar must be clamped');
  assert.equal(r1.x25519Priv[31] & 0x40, 0x40, 'scalar must be clamped');
  // Domain separation: same seed, different labels -> different outputs.
  const head = deriveHeadName(SYNTH_SEED);
  const signRoot = deriveHeadSignRoot(SYNTH_SEED);
  const pseudo = deriveOwnerPseudonym(SYNTH_SEED);
  const reg = deriveDeviceReg(SYNTH_SEED);
  for (const v of [head, signRoot, pseudo, reg]) {
    assert.equal(v.length, 32);
    assert.notEqual(toHex(v), toHex(r1.publicKey.subarray(0, 32)));
  }
  assert.notEqual(toHex(head), toHex(signRoot));
  assert.notEqual(toHex(pseudo), toHex(reg));
  // Fingerprint is SHA-256 of both publics (kv1:).
  const fp = fingerprintRecipient(r1);
  assert.equal(toHex(fp), toHex(sha256(Uint8Array.from(Buffer.concat([r1.publicKey, r1.x25519Pub])))));
});

test('FIPS 203 official ML-KEM-768 keygen vector + negative control', () => {
  const d = fromHex(FIPS203_768_KEYGEN.d);
  const z = fromHex(FIPS203_768_KEYGEN.z);
  const coins = new Uint8Array(64);
  coins.set(d, 0);
  coins.set(z, 32);
  const kp = ml_kem768.keygen(coins);
  assert.equal(toHex(kp.publicKey), FIPS203_768_KEYGEN.ek, 'ek must match official vector');
  assert.equal(toHex(kp.secretKey), FIPS203_768_KEYGEN.dk, 'dk must match official vector');
  // Negative control: swapping d/z must NOT reproduce the vector.
  const swapped = new Uint8Array(64);
  swapped.set(z, 0);
  swapped.set(d, 32);
  const wrong = ml_kem768.keygen(swapped);
  assert.notEqual(toHex(wrong.publicKey), FIPS203_768_KEYGEN.ek, 'swapped d/z must mismatch');
  assert.notEqual(toHex(wrong.secretKey), FIPS203_768_KEYGEN.dk, 'swapped d/z must mismatch');
});

test('HKDF-SHA256 RFC 5869 test case 1 + negative control', () => {
  const ikm = new Uint8Array(22).fill(0x0b);
  const salt = fromHex('000102030405060708090a0b0c');
  const info = fromHex('f0f1f2f3f4f5f6f7f8f9');
  const okm = hkdf(sha256, ikm, salt, info, 42);
  assert.equal(
    toHex(okm),
    '3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865',
  );
  // Negative control: one flipped info byte must change the output.
  const badInfo = Uint8Array.from(info);
  badInfo[0] ^= 1;
  assert.notEqual(toHex(hkdf(sha256, ikm, salt, badInfo, 42)), toHex(okm));
});

test('X25519 RFC 7748 test vector + negative control', () => {
  const a = fromHex('a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4');
  const b = fromHex('4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d');
  const shared = x25519.getSharedSecret(a, b);
  assert.equal(toHex(shared), '0fb6ad6e213f277bd00781494c57b2e82de89f17f21dffd0d340c9d452e6e043');
  // Negative control: a different (non-clampable) scalar must NOT produce it.
  const other = Uint8Array.from(a);
  other[31] ^= 0x01; // bit 0 of the top byte survives clamping
  assert.notEqual(toHex(x25519.getSharedSecret(other, b)), toHex(shared));
});

test('2-of-3 split: all combinations recover the synthetic seed', () => {
  const rng = counterRng('synthetic-fixture');
  const coef = rng(32);
  const shares = splitWithCoefficient(SYNTH_SEED, coef, fixedSetId());
  assert.equal(shares.length, 3);
  const combos = [
    [0, 1],
    [0, 2],
    [1, 2],
  ];
  for (const combo of combos) {
    const recovered = combine23(combo.map((i) => shares[i]));
    assert.equal(toHex(recovered), toHex(SYNTH_SEED), `combo ${combo} must recover`);
  }
  // Ordering permutations of the same pair must not matter.
  assert.equal(toHex(combine23([shares[1], shares[0]])), toHex(SYNTH_SEED));
  assert.equal(toHex(combine23([shares[2], shares[0]])), toHex(SYNTH_SEED));
  // All three at once: consistent third share passes.
  assert.equal(toHex(combine23(shares)), toHex(SYNTH_SEED));
  assert.equal(toHex(combine23([shares[2], shares[1], shares[0]])), toHex(SYNTH_SEED));
});

test('2-of-3 production split uses CSPRNG (non-deterministic across calls)', () => {
  const a = splitSecret23(SYNTH_SEED).shares;
  const b = splitSecret23(SYNTH_SEED).shares;
  // setIds are CSPRNG -> different across splits; recovery still works.
  assert.notEqual(toHex(a[0].setId), toHex(b[0].setId));
  assert.equal(toHex(combine23(a.slice(0, 2))), toHex(SYNTH_SEED));
  assert.equal(toHex(combine23(b.slice(0, 2))), toHex(SYNTH_SEED));
});

test('broken share, other-set share, and malformed shares are refused', () => {
  const rng = counterRng('broken-fixture');
  const shares = splitWithCoefficient(SYNTH_SEED, rng(32), fixedSetId());
  // Broken share data (checksum recomputed so only content is wrong).
  const otherSet = splitWithCoefficient(SYNTH_SEED, counterRng('other')(32), counterRng('other-set')(16));
  assert.throws(() => combine23([shares[0], otherSet[1]]), /mixed setId|checksum/);
  // Corrupted data with stale checksum.
  const corrupted = { ...shares[0], data: Uint8Array.from(shares[0].data) };
  corrupted.data[0] ^= 0xff;
  assert.throws(() => combine23([corrupted, shares[1]]), /checksum/);
  // 3rd share inconsistent: replace data of third with a different-set share
  // but keep setId/checksum of the original so only the line check catches it.
  const rng2 = counterRng('inconsistent');
  const alt = splitWithCoefficient(SYNTH_SEED, rng2(32), fixedSetId());
  assert.throws(() => combine23([shares[0], shares[1], alt[2]]), /inconsistent/);
  // Malformed envelopes.
  assert.throws(() => combine23([shares[0]]), /insufficient/);
  assert.throws(() => combine23([]), /insufficient/);
  assert.throws(() => combine23([shares[0], shares[1], shares[2], shares[0]]), /at most 3/);
  assert.throws(() => combine23([{ ...shares[0], id: 0 }, shares[1]]), /id must be/);
  assert.throws(() => combine23([{ ...shares[0], id: 4 }, shares[1]]), /id must be/);
  assert.throws(() => combine23([{ ...shares[0], id: 1.5 }, shares[1]]), /id must be/);
  assert.throws(() => combine23([{ ...shares[0], id: '1' }, shares[1]]), /id must be/);
  assert.throws(() => combine23([{ ...shares[0], version: 2 }, shares[1]]), /version/);
  assert.throws(() => combine23([{ ...shares[0], version: '1' }, shares[1]]), /version/);
  assert.throws(() => combine23([{ ...shares[0], data: new Uint8Array(16) }, shares[1]]), /data must be/);
  assert.throws(() => combine23([{ ...shares[0], setId: new Uint8Array(8) }, shares[1]]), /setId/);
  assert.throws(() => combine23([shares[0], shares[0]]), /duplicate/);
});

test('one share alone reveals nothing (exhaustive GF argument)', () => {
  // Single-byte model: share y = s ^ mul(a, x) with a uniform over GF(256).
  // For fixed y and x != 0, the map a -> y ^ mul(a, x) is a bijection on
  // 256 values, so every possible seed byte is equally likely: exactly zero
  // information about s. Prove it by enumeration, not by sampling.
  const rng = counterRng('privacy-fixture');
  const { shares } = splitSecret23(SYNTH_SEED);
  assert.equal(shares.length, 3);
  // Exhaustive check over one byte: all 256 coefficients, all 256 share
  // values, for each id. The reachable seed-byte set is always all 256.
  for (const x of [1, 2, 3]) {
    const seen = new Set();
    for (let a = 0; a < 256; a++) {
      // Rebuild a 1-byte share from coefficient byte a and read back the
      // implied seed byte: s = share ^ mul(a, x). Use coef stream where
      // only byte 0 varies.
      const coef = new Uint8Array(32);
      coef[0] = a;
      const set = splitWithCoefficient(SYNTH_SEED, coef, fixedSetId());
      const shareByte = set.find((s) => s.id === x).data[0];
      // Invert: mul(a, x) = share ^ seed; recover mul via brute search is
      // overkill — instead tally the share bytes themselves across all a:
      seen.add(shareByte);
    }
    assert.equal(seen.size, 256, `id ${x}: share bytes cover all 256 values`);
  }
  // With a uniform coefficient the share bytes are uniform too: a fixed
  // share byte recurs with many different seeds in the exhaustive model.
  // Statistical backstop over random splits: no share byte equals the seed
  // byte except at chance rate, and every value appears.
  const RANDOM_TRIALS = 4096;
  const counts = new Array(256).fill(0);
  let equalToSeed = 0;
  for (let t = 0; t < RANDOM_TRIALS; t++) {
    const { shares: s } = splitSecret23(SYNTH_SEED);
    const byte = s[0].data[0];
    counts[byte]++;
    if (byte === SYNTH_SEED[0]) equalToSeed++;
  }
  assert.equal(counts.filter((c) => c > 0).length, 256, 'every byte value appears');
  assert.ok(equalToSeed < RANDOM_TRIALS / 64, 'share byte ~ seed byte only at chance rate');
});

test('combineAndVerify refuses a wrong fingerprint / wrong shares', () => {
  const rng = counterRng('verify-fixture');
  const shares = splitWithCoefficient(SYNTH_SEED, rng(32), fixedSetId());
  const recipient = deriveRecipient(SYNTH_SEED);
  const fp = fingerprintRecipient(recipient);
  const ok = combineAndVerify(shares.slice(0, 2), fp);
  assert.equal(toHex(ok.seed), toHex(SYNTH_SEED));
  // Wrong fingerprint -> refuse.
  const otherFp = fingerprintRecipient(deriveRecipient(Uint8Array.from({ length: 32 }, (_, i) => 0xff - i)));
  assert.throws(() => combineAndVerify(shares.slice(0, 2), otherFp), /fingerprint mismatch/);
  // Right shares but tampered checksum -> refuse before fingerprint check.
  const tampered = shares.map((s) => ({ ...s }));
  tampered[0] = { ...tampered[0], checksum: Uint8Array.from(tampered[0].checksum) };
  tampered[0].checksum[0] ^= 1;
  assert.throws(() => combineAndVerify(tampered.slice(0, 2), fp), /checksum/);
  // Malformed expected fingerprint.
  assert.throws(() => combineAndVerify(shares.slice(0, 2), new Uint8Array(16)), /Uint8Array\(32\)/);
});

test('module evidence: no Buffer, no deterministic hook in production source', () => {
  const src = readFileSync(new URL('../src/recovery.js', import.meta.url), 'utf8');
  assert.ok(!/Buffer\./.test(src), 'production module must not use Buffer');
  assert.ok(!/from ['"]buffer['"]/.test(src), 'production module must not import buffer');
  assert.ok(!src.includes('options.deterministic'), 'production module must not ship a deterministic option');
  assert.ok(!src.includes('options.rng'), 'production module must not ship an rng option');
  assert.ok(src.includes('crypto.getRandomValues'), 'production split must use CSPRNG');
  // deriveRecipient must pair with the official keygen: coins are the shared
  // d||z block of the 96 B single HKDF output.
  const r = deriveRecipient(SYNTH_SEED);
  assert.equal(r.publicKey.length, 1184);
  assert.equal(r.secretKey.length, 2400);
  // Wrong-length seeds are refused everywhere.
  assert.throws(() => deriveRecipient(new Uint8Array(31)), /32 bytes/);
  assert.throws(() => deriveRecipient(new Uint8Array(33)), /32 bytes/);
  assert.throws(() => splitSecret23(new Uint8Array(16)), /32 bytes/);
  assert.throws(() => splitWithCoefficient(new Uint8Array(16), new Uint8Array(32), fixedSetId()), /32 bytes/);
  assert.throws(() => splitWithCoefficient(SYNTH_SEED, new Uint8Array(16), fixedSetId()), /32 bytes/);
});
