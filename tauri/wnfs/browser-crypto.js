const BN = require('bn.js');
const MillerRabin = require('miller-rabin');
const randomBytes = size => globalThis.crypto.getRandomValues(new Uint8Array(size));
const tester = MillerRabin.create({ generate: randomBytes });
const smallPrimes = [3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47];

function checkPrimeSync(bytes, { checks }) {
  const candidate = new BN(bytes);
  if (candidate.cmpn(2) === 0) return true;
  if (candidate.cmpn(2) < 0 || candidate.isEven()) return false;
  for (const prime of smallPrimes) {
    if (candidate.cmpn(prime) === 0) return true;
    if (candidate.modn(prime) === 0) return false;
  }
  return tester.test(candidate, checks);
}

function generatePrimeSync(bits, { bigint }) {
  if (bits !== 256 || bigint !== false) throw Error('unsupported-prime-profile');
  for (let attempt = 0; attempt < 65536; attempt++) {
    const candidate = randomBytes(32);
    candidate[0] |= 128;
    candidate[31] |= 1;
    if (checkPrimeSync(candidate, { checks: 64 })) return candidate;
  }
  throw Error('prime-search-bound');
}

module.exports = { randomBytes, checkPrimeSync, generatePrimeSync };
