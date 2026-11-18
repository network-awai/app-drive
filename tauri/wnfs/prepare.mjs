import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';

const base = new URL('../', import.meta.url);
const lock = JSON.parse(await readFile(new URL('source-lock.json', import.meta.url)));
export const modules = new Map([
  ['node:crypto', 'awai-wnfs-ports/browser-crypto.js'],
  ['node:fs', 'awai-wnfs-ports/no-disk.js'],
  ['node:path', 'awai-wnfs-ports/no-disk.js'],
  ['awai-wnfs-car', 'awai-wnfs-ports/pack.js']
]);
for (const name of [
  'multiformats/cid', 'multiformats/hashes/digest', '@ipld/dag-cbor',
  '@noble/hashes/blake3', '@noble/hashes/sha256', '@noble/ciphers/aes',
  '@noble/ciphers/chacha', '@noble/curves/abstract/modular', '@noble/curves/abstract/utils'
]) {
  modules.set(name, import.meta.resolve(name).split('/node_modules/').at(-1));
}

for (const [path, expected] of Object.entries(lock.files)) {
  const source = await readFile(new URL(path, base));
  if (createHash('sha256').update(source).digest('hex') !== expected) throw Error('WNFS source pin drift');
  const target = new URL('.build/wnfs-cljs/awai/wnfs/' + path.split('/').at(-1), base);
  await mkdir(new URL('./', target), { recursive: true });
  let adapted = source.toString('utf8');
  for (const [name, resolved] of modules) adapted = adapted.replaceAll(JSON.stringify(name), JSON.stringify(resolved));
  await writeFile(target, adapted);
}

const query = JSON.parse(await readFile(new URL('query-lock.json', import.meta.url)));
for (const [path, expected] of Object.entries(query.files)) {
  const source = await readFile(new URL(path, base));
  if (createHash('sha256').update(source).digest('hex') !== expected) throw Error('Kotoba query source pin drift');
  const target = new URL('.build/wnfs-cljs/' + path.replace(/^wnfs\/query\//, '').replace(/\.cljk$/, '.cljc'), base);
  await mkdir(new URL('./', target), { recursive: true });
  await writeFile(target, source);
}
