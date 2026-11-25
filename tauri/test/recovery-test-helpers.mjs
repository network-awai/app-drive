// Test-only helpers for the recovery suite. Nothing here is production code:
// deterministic coefficient streams and hex codecs for fixtures live ONLY in
// this file, so production has no deterministic/randomness hook to misuse.

import { createHmac } from 'node:crypto';

const HEX_DIGITS = '0123456789abcdef';

export function toHex(bytes) {
  let out = '';
  for (let i = 0; i < bytes.length; i++) {
    out += HEX_DIGITS[(bytes[i] >> 4) & 0xf] + HEX_DIGITS[bytes[i] & 0xf];
  }
  return out;
}

export function fromHex(hex) {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) {
    out[i] = parseInt(hex.slice(2 * i, 2 * i + 2), 16);
  }
  return out;
}

// Deterministic counter-mode stream: same tag -> same bytes, forever.
export function counterRng(tag) {
  const key = Buffer.from(String(tag ?? 'fixture'), 'utf8');
  let counter = 0;
  return function rng(n) {
    const out = new Uint8Array(n);
    let off = 0;
    while (off < n) {
      const block = createHmac('sha256', key).update(Uint8Array.of(counter++)).digest();
      out.set(block.subarray(0, Math.min(block.length, n - off)), off);
      off += block.length;
    }
    return out;
  };
}

export function fixedSetId() {
  return Uint8Array.from({ length: 16 }, (_, i) => 0xa0 + i);
}
