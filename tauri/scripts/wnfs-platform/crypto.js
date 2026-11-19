// Browser provider for the pinned CLJC WNFS accumulator and nonce APIs.
import { pow } from '@noble/curves/abstract/modular';
import { bytesToNumberBE } from '@noble/curves/abstract/utils';
import { sha256 } from '@noble/hashes/sha256';

export function createHash(algorithm) {
  if (algorithm !== 'sha256') throw new Error('unsupported-wnfs-hash');
  return sha256.create();
}

export function randomBytes(length) {
  return globalThis.crypto.getRandomValues(new Uint8Array(length));
}

const smallPrimes = [3n, 5n, 7n, 11n, 13n, 17n, 19n, 23n, 29n, 31n, 37n, 41n, 43n, 47n];

export function checkPrimeSync(bytes, { checks }) {
  if (bytes.length !== 32 || checks !== 64) throw new Error('invalid-wnfs-prime-profile');
  const n = bytesToNumberBE(bytes);
  if (n === 2n) return true;
  if (n < 3n || n % 2n === 0n) return false;
  for (const p of smallPrimes) {
    if (n % p === 0n) return n === p;
  }

  // FIPS 186-5 B.3.1: decompose n - 1, then sample each witness without modulo bias.
  const end = n - 1n;
  let odd = end;
  let shifts = 0;
  while (odd % 2n === 0n) {
    odd /= 2n;
    shifts++;
  }
  const width = end.toString(2).length;
  for (let i = 0; i < checks; i++) {
    let base;
    do {
      const sample = randomBytes(Math.ceil(width / 8));
      sample[0] &= (1 << ((width - 1) % 8 + 1)) - 1;
      base = bytesToNumberBE(sample);
    } while (base < 2n || base >= end);
    let value = pow(base, odd, n);
    if (value === 1n || value === end) continue;
    let accepted = false;
    for (let round = 1; round < shifts; round++) {
      value = (value * value) % n;
      if (value === end) {
        accepted = true;
        break;
      }
      if (value === 1n) return false;
    }
    if (!accepted) return false;
  }
  return true;
}

export function generatePrimeSync(bits, { bigint }) {
  if (bits !== 256 || bigint) throw new Error('invalid-wnfs-prime-profile');
  for (let attempt = 0; attempt < 65536; attempt++) {
    const bytes = randomBytes(32);
    bytes[0] |= 0x80;
    bytes[31] |= 1;
    if (checkPrimeSync(bytes, { checks: 64 })) return bytes;
  }
  throw new Error('wnfs-prime-search-bound');
}
