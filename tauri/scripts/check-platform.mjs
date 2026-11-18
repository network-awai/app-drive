import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';

const vendor = new URL('../vendor/platform/', import.meta.url);
const pins = JSON.parse(await readFile(new URL('PINS.json', vendor), 'utf8'));
const expected = {
  ...pins.awaiOs.files,
  ...pins.yataverse.files,
  'kotoba-client-runtime.js': pins.runtime,
};

for (const [file, pin] of Object.entries(expected)) {
  const bytes = await readFile(new URL(file, vendor));
  if (createHash('sha256').update(bytes).digest('hex') !== pin.sha256) {
    throw new Error(`Platform dependency digest mismatch: ${file}`);
  }
}

const accountVerifier = await readFile(new URL('../src/wnfs-account.js', import.meta.url));
if (createHash('sha256').update(accountVerifier).digest('hex') !== pins.wnfsAccount.sha256) {
  throw Error('Awai OS account verifier pin drift');
}
