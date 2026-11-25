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
//   device-registration   32 B        registration keypair material (§3.5):
//                                       seed -> Ed25519 secret key; the public
//                                       key is what a fresh device registers
//   owner-pseudonym       32 B        SHA-256("awai-drive/v1/owner" ||
//                                       registration public key) — the
//                                       per-person pseudonym; public key only
//   head/name             32 B        public head name (never reveals sign-root)
//   head/sign-root        32 B        signing-root material
//
// The 96 B single HKDF output for `recovery-recipient` is split at call time;
// there is no separate `recovery-recipient/x25519-scalar` label.
//
// Shamir 2-of-3 lives in ./internal/shamir.js (INTERNAL, not exported by this
// module, never bundled). Shares are versioned envelopes:
// { version: 1, setId: 16 B, id: 1..3, data: 32 B, checksum: 32 B } with
// checksum = SHA-256(0x01 || setId || id || data). That checksum is UNKEYED:
// it detects accidental corruption only — anyone can recompute it, so it is
// not forgery protection. Forgery resistance comes from combineAndVerify.
//
// Fingerprint provenance (decision, binding for callers): when every device
// is lost, the expectedFingerprint used by combineAndVerify must come from
// owner-held offline material written at registration time — the fingerprint
// is printed on the share/paper artifacts themselves. It must never be
// fetched from a server, bot, or any network source at restore time: an
// attacker-supplied fingerprint nullifies the check (they can supply their
// own key's fingerprint and the wrong seed passes). The restore device
// reads the fingerprint from the paper with the shares, compares, and
// refuses on mismatch before touching any pack id. This module only checks
// equality; it cannot tell where a caller obtained the value, so callers
// must source it from the owner-held artifact.
//
// Shamir randomness: production splitSecret23 uses only crypto.getRandomValues
// (CSPRNG). There is no options/rng/deterministic hook anywhere in this
// module. Deterministic test randomness lives in test/ only.
//
// Dependencies kept from the audited noble libraries: HKDF/SHA-256 from
// @noble/hashes, ML-KEM-768 from @noble/post-quantum, X25519 + Ed25519 from
// @noble/curves. The GF(256) Shamir core is self-made because no audited
// library exposes it; it is ~40 lines of pure arithmetic, exhaustively tested
// (all 256 coefficient values per share byte prove one share gives no seed
// information; all 2-of-3 combinations + permutations round-trip).
//
// Secret handling (wiping policy): every Uint8Array that holds a seed,
// coefficient, share data, or derived private material must be zero-filled
// with wipeSecret() once its last use is done (finally-style). This module
// wipes intermediates it owns (coefficients, HKDF material copies, seeds on
// every failure path); returned buffers (seed, recipient privates, shares)
// are caller-owned — wipe them when finished. JS cannot guarantee memory is
// not relocated by the GC; wiping bounds the lifetime to the scope that
// needed the value, which is the strongest guarantee available in the
// WebView runtime. Paper/share loss or theft re-registration is a design-doc
// matter and is deliberately not implemented here.
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
import { x25519, ed25519 } from '@noble/curves/ed25519.js';
import { splitWithCoefficient, combine23 } from './internal/shamir.js';

const LABEL_PREFIX = 'awai-drive/v1/';
const OWNER_PSEUDONYM_PREFIX = 'awai-drive/v1/owner';
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
  try {
    const kp = ml_kem768.keygen(coins);
    const priv = Uint8Array.from(scalar);
    // RFC 7748 clamping: clear low 3 bits, clear top bit, set bit 6.
    priv[0] &= 248;
    priv[31] &= 127;
    priv[31] |= 64;
    const pub = x25519.getPublicKey(priv);
    return { publicKey: kp.publicKey, secretKey: kp.secretKey, x25519Priv: priv, x25519Pub: pub };
  } finally {
    wipeSecret(coins);
    wipeSecret(scalar);
    wipeSecret(material);
  }
}

// Registration fingerprint: SHA-256 of both public keys (kv1: fingerprint).
function fingerprintRecipient(recipient) {
  return sha256(concatBytes([recipient.publicKey, recipient.x25519Pub]));
}

function deriveDeviceReg(seed) {
  return derive(seed, 'device-registration', 32);
}

// Device-registration public key: Ed25519 pub of the registration material.
// The 32 B derived value is the Ed25519 secret key; publish only the pub.
function deriveDeviceRegPublic(seed) {
  const material = deriveDeviceReg(seed);
  try {
    return ed25519.getPublicKey(material);
  } finally {
    wipeSecret(material);
  }
}

// Per-person pseudonym: SHA-256("awai-drive/v1/owner" || registration pub).
function deriveOwnerPseudonym(seed) {
  const pub = deriveDeviceRegPublic(seed);
  try {
    return sha256(concatBytes([utf8(OWNER_PSEUDONYM_PREFIX), pub]));
  } finally {
    wipeSecret(pub);
  }
}

function deriveHeadName(seed) {
  return derive(seed, 'head/name', 32);
}

function deriveHeadSignRoot(seed) {
  return derive(seed, 'head/sign-root', 32);
}

function defaultRng(n) {
  return crypto.getRandomValues(new Uint8Array(n));
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

// Full recovery: combine 2..3 envelopes, then verify against the
// registration-time fingerprint (recovery recipient public value, sourced
// from the owner-held paper per the provenance policy above).
// The assembled seed is wiped on every failure path and never returned
// on non-success; on success ownership transfers to the caller.
function combineAndVerify(shares, expectedFingerprint) {
  if (!(expectedFingerprint instanceof Uint8Array) || expectedFingerprint.length !== 32) {
    throw new Error('expectedFingerprint must be Uint8Array(32)');
  }
  const seed = combine23(shares);
  let recipient;
  let fingerprint;
  try {
    recipient = deriveRecipient(seed);
    fingerprint = fingerprintRecipient(recipient);
    if (!bytesEqual(fingerprint, expectedFingerprint)) {
      throw new Error('fingerprint mismatch: wrong seed or wrong shares');
    }
    return { seed };
  } catch (err) {
    wipeSecret(seed);
    throw err;
  } finally {
    if (recipient) {
      wipeSecret(recipient.secretKey);
      wipeSecret(recipient.x25519Priv);
    }
    if (fingerprint) wipeSecret(fingerprint);
  }
}

export {
  toHex,
  fromHex,
  wipeSecret,
  deriveRecipient,
  fingerprintRecipient,
  deriveDeviceReg,
  deriveDeviceRegPublic,
  deriveOwnerPseudonym,
  deriveHeadName,
  deriveHeadSignRoot,
  splitSecret23,
  combineAndVerify,
};
