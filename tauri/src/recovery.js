// Deterministic recovery-holder layer (synthetic-testable, fake storage only).
//
// Implements the unified derivation table from the design docs:
//   seed (32B) --HKDF-SHA256(awai-drive/v1/<label>)--> labeled sub-keys
//   recovery-recipient: KEM coins (64B) + X25519 scalar (32B)
//   device-registration / owner-pseudonym / head/name / head/sign-root (32B each)
//
// Deterministic keygen: ml_kem768.keygen(coins) is FIPS 203 deterministic mode
// (coins = d||z, 64B). X25519 scalar is RFC 7748 clamped before pub derivation.
// No real seed/key generation: callers pass a synthetic test seed; production
// supplies the seed from the recovery ceremony. Shares use Shamir 2-of-3 over
// GF(256); split randomness defaults to crypto.getRandomValues (CSPRNG) and
// tests inject a deterministic counter for reproducibility.

import { hkdf } from '@noble/hashes/hkdf.js';
import { sha256 } from '@noble/hashes/sha256.js';
import { hmac } from '@noble/hashes/hmac.js';
import { ml_kem768 } from '@noble/post-quantum/ml-kem.js';
import { x25519 } from '@noble/curves/ed25519.js';

const LABEL_PREFIX = 'awai-drive/v1/';

function utf8(s) {
  return new TextEncoder().encode(s);
}

function toHex(bytes) {
  return Buffer.from(bytes).toString('hex');
}

function derive(seed, label, length) {
  if (!(seed instanceof Uint8Array) || seed.length !== 32) throw new Error('seed must be 32 bytes');
  return hkdf(sha256, seed, new Uint8Array(0), utf8(LABEL_PREFIX + label), length);
}

function deriveRecipient(seed) {
  // ML-KEM coins: 64B deterministic keygen seed.
  const coins = derive(seed, 'recovery-recipient', 64);
  const kp = ml_kem768.keygen(coins);
  // X25519 scalar: derive 32B then RFC 7748 clamp before pub derivation.
  const scalar = derive(seed, 'recovery-recipient/x25519-scalar', 32);
  const priv = Uint8Array.from(scalar);
  priv[0] &= 248;
  priv[31] &= 127;
  priv[31] |= 64;
  const pub = x25519.getPublicKey(priv);
  return { publicKey: kp.publicKey, secretKey: kp.secretKey, x25519Priv: priv, x25519Pub: pub, coins };
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

// --- Shamir 2-of-3 over GF(2^8), poly 0x11b ---
const FIELD_POLY = 0x11b;

function gfMul(a, b) {
  let r = 0;
  let x = a;
  let y = b;
  while (y > 0) {
    if (y & 1) r ^= x;
    y >>= 1;
    x = (x & 0x80) ? ((x << 1) ^ FIELD_POLY) & 0xff : (x << 1) & 0xff;
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

// Production split randomness must be CSPRNG. Tests may inject a deterministic
// stream derived from `options.deterministic` (string) for reproducibility.
function splitSecret23(secret, options = {}) {
  if (!(secret instanceof Uint8Array) || secret.length !== 32) throw new Error('secret must be 32 bytes');
  let rand;
  if (options.rng) {
    rand = options.rng;
  } else if (options.deterministic) {
    const tag = utf8(String(options.deterministic || 'fixture'));
    let counter = 0;
    rand = (n) => {
      const out = new Uint8Array(n);
      let off = 0;
      while (off < n) {
        const block = hmac(sha256, tag, new Uint8Array([counter++]));
        out.set(block.subarray(0, Math.min(block.length, n - off)), off);
        off += block.length;
      }
      return out;
    };
  } else {
    rand = defaultRng;
  }
  const coef = rand(32); // per-byte coefficient a_i; y = s + a*x
  const xs = [1, 2, 3];
  const shares = xs.map((x) => {
    const data = new Uint8Array(32);
    for (let i = 0; i < 32; i++) data[i] = secret[i] ^ gfMul(coef[i], x);
    return { id: x, data };
  });
  return { shares };
}

function combine23(shares) {
  if (!Array.isArray(shares) || shares.length < 2) throw new Error('insufficient shares: need 2');
  const [a, b] = shares;
  if (a.id === b.id) throw new Error('duplicate share x');
  // Interpolate at x=0: s = y1 * x2/(x2-x1) + y2 * x1/(x1-x2) in GF(256).
  const x1 = a.id;
  const x2 = b.id;
  const l1 = gfDiv(x2, x2 ^ x1); // x2/(x2 - x1), subtraction = XOR in GF(2^8)
  const l2 = gfDiv(x1, x1 ^ x2);
  const out = new Uint8Array(32);
  for (let i = 0; i < 32; i++) out[i] = gfMul(a.data[i], l1) ^ gfMul(b.data[i], l2);
  return out;
}

// --- Fake ordering store (fake storage only; no DO, no prod writes) ---
function createFakeHeadStore() {
  const byName = new Map();
  let lastHeadName = null;
  return {
    publish(headName, entry) {
      const cur = byName.get(headName) ?? { seq: 0, entries: [] };
      if (!(typeof entry.seq === 'number') || entry.seq <= cur.seq) {
        throw new Error('rollback: headSeq must strictly increase');
      }
      byName.set(headName, { seq: entry.seq, entries: [...cur.entries, { ...entry }] });
      lastHeadName = headName;
      return entry.seq;
    },
    query(headName) {
      return [...(byName.get(headName)?.entries ?? [])].sort((a, b) => a.seq - b.seq);
    },
    headForOwner(_owner) {
      if (!lastHeadName) throw new Error('no heads published');
      return { headName: lastHeadName, heads: [...(byName.get(lastHeadName)?.entries ?? [])] };
    },
  };
}

export {
  deriveRecipient,
  deriveDeviceReg,
  deriveOwnerPseudonym,
  deriveHeadName,
  deriveHeadSignRoot,
  splitSecret23,
  combine23,
  createFakeHeadStore,
  toHex,
};
