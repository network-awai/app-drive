// Shamir 2-of-3 core (GF(2^8), poly 0x11b) — INTERNAL module.
//
// Not part of the production API surface: the app bundle never imports this
// file or recovery.js (verified by grepping dist/*.js for the export names
// below — they are absent). splitSecret23/combineAndVerify (recovery.js) are
// the only production entry points; tests import splitWithCoefficient and
// combine23 directly from here for known-coefficient construction and
// combination checks. A caller that can supply the coefficient can recover
// the seed from a single share — exactly why splitWithCoefficient stays here.
//
// Shares are versioned envelopes:
// { version: 1, setId: 16 B, id: 1..3, data: 32 B, checksum: 32 B } with
// checksum = SHA-256(0x01 || setId || id || data). That checksum is UNKEYED:
// it detects accidental corruption only. Anyone can recompute it, so it is
// not forgery protection — forgery resistance comes from the fingerprint
// check in combineAndVerify, against a fingerprint the owner holds on
// paper (see recovery.js).

import { sha256 } from '@noble/hashes/sha256.js';

const SHARE_VERSION = 1;
const SHARE_ID_MIN = 1;
const SHARE_ID_MAX = 3;
const SHARE_DATA_LEN = 32;
const SET_ID_LEN = 16;

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

function wipeSecret(bytes) {
  if (!(bytes instanceof Uint8Array)) throw new Error('wipeSecret: expected Uint8Array');
  bytes.fill(0);
  return bytes;
}

// --- GF(2^8), constant-time branch-free ---

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

// Pure split with caller-supplied coefficients — test construction only.
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

export { splitWithCoefficient, combine23 };
