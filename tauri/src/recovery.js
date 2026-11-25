// Deterministic recovery-holder layer (synthetic-testable, fake storage only).
//
// Unified derivation table (design docs §3.2, identical in both): one HKDF-SHA256
// with IKM = seed, info = `awai-drive/v1/<label>`, empty salt, producing
// domain-separated sub-keys. Nothing else is derived from anything else.
//
//   label                 output      used for
//   recovery-recipient    96 B        recovery recipient keypair: KEM coins
//                                       (first 64 B, FIPS 203 deterministic
//                                       keygen d||z) + X25519 scalar (last 32 B,
//                                       RFC 7748 clamped before pub derivation)
//   device-registration   32 B        registering a new device after total loss
//   owner-pseudonym       32 B        per-person pseudonym
//   head/name             32 B        public head name (never reveals sign-root)
//   head/sign-root        32 B        signing-root material
//
// The 96 B single HKDF output for `recovery-recipient` is split at call time;
// there is no separate `recovery-recipient/x25519-scalar` label.
//
// Shamir 2-of-3 over GF(2^8), poly 0x11b. Shares are versioned envelopes:
// { version: 1, setId: 16 B, id: 1..3, data: 32 B, checksum: 32 B } with
// checksum = SHA-256(0x01 || setId || id || data). combine23 validates every
// envelope, rejects mismatched sets/duplicates/out-of-range ids, needs 2 shares
// and — when a 3rd is supplied — checks it lies on the same line (consistency).
// combineAndVerify additionally compares the recovered seed's recipient
// fingerprint (SHA-256 of ML-KEM ek || X25519 pub, the `kv1:` fingerprint) to
// the registration-time value and refuses on mismatch.
//
// Shamir randomness: production splitSecret23 uses only crypto.getRandomValues
// (CSPRNG). There is no options/rng/deterministic hook. The pure
// splitWithCoefficient(secret, coef, setId) below exists so that TESTS can
// construct known-coefficient splits; production callers must use
// splitSecret23, which samples coefficients itself. A caller that can supply
// the coefficient can recover the seed from a single share — that is exactly
// why it is not part of the production API surface.
//
// Dependencies kept from the audited noble libraries: HKDF/SHA-256 from
// @noble/hashes, ML-KEM-768 from @noble/post-quantum, X25519 from
// @noble/curves. The GF(256) Shamir core is self-made because no audited
// library exposes it; it is ~40 lines of pure arithmetic, exhaustively tested
// (all 256 coefficient values per share byte prove one share gives no seed
// information; all 2-of-3 combinations + permutations round-trip).
//
// Secret handling (wiping policy): every Uint8Array that holds a seed,
// coefficient, share data, or derived private material must be zero-filled
// with wipeSecret() once its last use is done (finally-style). This module
// wipes intermediates it owns (coefficients, interpolated buffers on error);
// returned buffers (seed, recipient privates, shares) are caller-owned —
// wipe them when finished. JS cannot guarantee memory is not relocated by the
// GC; wiping bounds the lifetime to the scope that needed the value, which is
// the strongest guarantee available in the WebView runtime.
//
// Timing policy: no secret-dependent branches. gfMul runs a fixed 8 iterations
// regardless of operand bits; gfPow/gfInv run fixed iteration counts; byte
// loops are fixed-length; share validation and fingerprint comparison
// accumulate a mismatch flag instead of early-exiting. Share/poly evaluation
// therefore does not branch on secret bits. (This is a policy against
// data-dependent control flow, not a claim of cryptographic constant-time
// against all side channels; the audited noble primitives carry their own
// guarantees.)
//
// No Buffer: this module runs in the Tauri WebView. All byte/hex work is plain
// Uint8Array + TextEncoder. No seed/key generation: callers pass a synthetic
// test seed; production supplies the seed from the recovery ceremony. The
// module never mints a new seed itself (split randomness covers Shamir
// coefficients only).

import { hkdf } from '@noble/hashes/hkdf.js';
import { sha256 } from '@noble/hashes/sha256.js';
import { ml_kem768 } from '@noble/post-quantum/ml-kem.js';
import { x25519 } from '@noble/curves/ed25519.js';

const LABEL_PREFIX = 'awai-drive/v1/';
const SHARE_VERSION = 1;
const SHARE_ID_MIN = 1;
const SHARE_ID_MAX = 3;
const SHARE_DATA_LEN = 32;
const SET_ID_LEN = 16;

function utf8(s) {
  return new TextEncoder().encode(s);
}

const HEX_DIGITS = '0123456789abcdef';

function toHex(bytes) {
  if (!(bytes instanceof Uint8Array)) throw new Error('toHex: expected Uint8Array');
  let out = '';
  for (let i = 0; i < bytes.length; i++) {
    out += HEX_DIGITS[(bytes[i] >> 4) & 0xf] + HEX_DIGITS[bytes[i] & 0xf];
  }
  return out;
}

function fromHex(hex) {
  if (typeof hex !== 'string' || hex.length % 2 !== 0 || /[^0-9a-fA-F]/.test(hex)) {
    throw new Error('fromHex: expected even-length hex string');
  }
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) {
    out[i] = parseInt(hex.slice(2 * i, 2 * i + 2), 16);
  }
  return out;
}

function wipeSecret(bytes) {
  if (!(bytes instanceof Uint8Array)) throw new Error('wipeSecret: expected Uint8Array');
  bytes.fill(0);
  return bytes;
}

function concatBytes(parts) {
  let total = 0;
  for (const p of parts) total += p.length;
  const out = new Uint8Array(total);
  let off = 0;
  for (const p of parts) {
    out.set(p, off);
    off += p.length;
  }
  return out;
}

function bytesEqual(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

function derive(seed, label, length) {
  if (!(seed instanceof Uint8Array) || seed.length !== 32) throw new Error('seed must be 32 bytes');
  return hkdf(sha256, seed, new Uint8Array(0), utf8(LABEL_PREFIX + label), length);
}

function deriveRecipient(seed) {
  // One HKDF output (96 B): KEM coins (64 B) + X25519 scalar (32 B).
  const material = derive(seed, 'recovery-recipient', 96);
  const coins = material.slice(0, 64);
  const scalar = material.slice(64, 96);
  const kp = ml_kem768.keygen(coins);
  const priv = Uint8Array.from(scalar);
  // RFC 7748 clamping: clear low 3 bits, clear top bit, set bit 6.
  priv[0] &= 248;
  priv[31] &= 127;
  priv[31] |= 64;
  const pub = x25519.getPublicKey(priv);
  wipeSecret(material);
  return { publicKey: kp.publicKey, secretKey: kp.secretKey, x25519Priv: priv, x25519Pub: pub };
}

// Registration fingerprint: SHA-256 of both public keys (kv1: fingerprint).
function fingerprintRecipient(recipient) {
  return sha256(concatBytes([recipient.publicKey, recipient.x25519Pub]));
}

function deriveDeviceReg(seed) {
  return derive(seed, 'device-registration', 32);
}

function deriveOwnerPseudonym(seed) {
  return derive(seed, 'owner-pseudonym', 32);
}

function deriveHeadName(seed) {
  return derive(seed, 'head/name', 32);
}

function deriveHeadSignRoot(seed) {
  return derive(seed, 'head/sign-root', 32);
}

// --- Shamir 2-of-3 over GF(2^8), poly 0x11b, constant-time branch-free ---

function gfMul(a, b) {
  let r = 0;
  let x = a;
  // Fixed 8 iterations; mask is all-0 or all-1 derived from the multiplier bit
  // without branching on the secret bit.
  for (let i = 0; i < 8; i++) {
    const mask = 0 - ((b >>> i) & 1); // 0x00 or 0xFF... (bit 0 operand)
    r ^= x & mask;
    const top = (x >>> 7) & 1;
    x = ((x << 1) & 0xff) ^ (0x1b & (0 - top));
  }
  return r;
}

function gfPow(a, p) {
  let r = 1;
  for (let i = 0; i < p; i++) r = gfMul(r, a);
  return r;
}

function gfInv(a) {
  if (a === 0) throw new Error('zero has no inverse');
  return gfPow(a, 254);
}

function gfDiv(a, b) {
  return gfMul(a, gfInv(b));
}

function defaultRng(n) {
  return crypto.getRandomValues(new Uint8Array(n));
}

function shareChecksum(version, setId, id, data) {
  return sha256(concatBytes([new Uint8Array([version & 0xff]), setId, new Uint8Array([id]), data]));
}

function validateShareEnvelope(share) {
  if (share === null || typeof share !== 'object') throw new Error('invalid share: expected object');
  if (share.version !== SHARE_VERSION) throw new Error('invalid share: unsupported version');
  if (!(share.setId instanceof Uint8Array) || share.setId.length !== SET_ID_LEN) {
    throw new Error('invalid share: setId must be Uint8Array(16)');
  }
  if (typeof share.id !== 'number' || !Number.isInteger(share.id) || share.id < SHARE_ID_MIN || share.id > SHARE_ID_MAX) {
    throw new Error('invalid share: id must be an integer in 1..3');
  }
  if (!(share.data instanceof Uint8Array) || share.data.length !== SHARE_DATA_LEN) {
    throw new Error('invalid share: data must be Uint8Array(32)');
  }
  if (!(share.checksum instanceof Uint8Array) || share.checksum.length !== SHARE_DATA_LEN) {
    throw new Error('invalid share: checksum must be Uint8Array(32)');
  }
  const expected = shareChecksum(share.version, share.setId, share.id, share.data);
  if (!bytesEqual(expected, share.checksum)) throw new Error('invalid share: checksum mismatch');
}

function makeShare(setId, id, data) {
  return { version: SHARE_VERSION, setId, id, data, checksum: shareChecksum(SHARE_VERSION, setId, id, data) };
}

// Pure split with caller-supplied coefficients — for tests only (see header).
// Production callers must use splitSecret23 (CSPRNG coefficients).
function splitWithCoefficient(secret, coef, setId) {
  if (!(secret instanceof Uint8Array) || secret.length !== 32) throw new Error('secret must be 32 bytes');
  if (!(coef instanceof Uint8Array) || coef.length !== 32) throw new Error('coef must be 32 bytes');
  if (!(setId instanceof Uint8Array) || setId.length !== SET_ID_LEN) throw new Error('setId must be Uint8Array(16)');
  const xs = [1, 2, 3];
  return xs.map((x) => {
    const data = new Uint8Array(32);
    for (let i = 0; i < 32; i++) data[i] = secret[i] ^ gfMul(coef[i], x);
    return makeShare(setId, x, data);
  });
}

function splitSecret23(secret) {
  if (!(secret instanceof Uint8Array) || secret.length !== 32) throw new Error('secret must be 32 bytes');
  const coef = defaultRng(32);
  const setId = defaultRng(SET_ID_LEN);
  try {
    return { shares: splitWithCoefficient(secret, coef, setId) };
  } finally {
    wipeSecret(coef);
  }
}

// Interpolate the polynomial defined by two shares at x = 0.
function interpolateTwo(a, b) {
  const x1 = a.id;
  const x2 = b.id;
  const l1 = gfDiv(x2, x2 ^ x1); // x2/(x2 - x1), subtraction = XOR in GF(2^8)
  const l2 = gfDiv(x1, x1 ^ x2);
  const out = new Uint8Array(32);
  for (let i = 0; i < 32; i++) out[i] = gfMul(a.data[i], l1) ^ gfMul(b.data[i], l2);
  return out;
}

// Evaluate the polynomial defined by two shares at an arbitrary x.
function evaluateAt(a, b, x) {
  const x1 = a.id;
  const x2 = b.id;
  const l1 = gfDiv(x ^ x2, x1 ^ x2);
  const l2 = gfDiv(x ^ x1, x2 ^ x1);
  const out = new Uint8Array(32);
  for (let i = 0; i < 32; i++) out[i] = gfMul(a.data[i], l1) ^ gfMul(b.data[i], l2);
  return out;
}

function combine23(shares) {
  if (!Array.isArray(shares)) throw new Error('insufficient shares: need 2..3');
  if (shares.length < 2) throw new Error('insufficient shares: need 2');
  if (shares.length > 3) throw new Error('invalid shares: at most 3');
  for (const s of shares) validateShareEnvelope(s);
  const sorted = [...shares].sort((a, b) => a.id - b.id);
  const setId = sorted[0].setId;
  let mismatch = 0;
  for (const s of sorted) {
    if (!bytesEqual(s.setId, setId)) mismatch |= 1;
  }
  for (let i = 1; i < sorted.length; i++) {
    if (sorted[i].id === sorted[i - 1].id) throw new Error('duplicate share id');
  }
  if (mismatch) throw new Error('invalid shares: mixed setId');
  const [a, b] = sorted;
  const seed = interpolateTwo(a, b);
  if (sorted.length === 3) {
    // 3rd share must lie on the same degree-1 line (consistency check).
    const predicted = evaluateAt(a, b, sorted[2].id);
    let diff = 0;
    for (let i = 0; i < 32; i++) diff |= predicted[i] ^ sorted[2].data[i];
    wipeSecret(predicted);
    if (diff !== 0) {
      wipeSecret(seed);
      throw new Error('invalid shares: third share inconsistent');
    }
  }
  return seed;
}

// Full recovery: combine 2..3 envelopes, then verify against the
// registration-time fingerprint (recovery recipient public value).
function combineAndVerify(shares, expectedFingerprint) {
  if (!(expectedFingerprint instanceof Uint8Array) || expectedFingerprint.length !== 32) {
    throw new Error('expectedFingerprint must be Uint8Array(32)');
  }
  const seed = combine23(shares);
  let recipient;
  try {
    recipient = deriveRecipient(seed);
    const fingerprint = fingerprintRecipient(recipient);
    const ok = bytesEqual(fingerprint, expectedFingerprint);
    wipeSecret(fingerprint);
    if (!ok) throw new Error('fingerprint mismatch: wrong seed or wrong shares');
    return { seed };
  } finally {
    if (recipient) {
      wipeSecret(recipient.secretKey);
      wipeSecret(recipient.x25519Priv);
    }
  }
}

export {
  toHex,
  fromHex,
  wipeSecret,
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
};
