// The Closure resolver cannot resolve package export maps or node: modules.
// Verify the pinned upstream bytes, then map import specifiers only.
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = new URL('../', import.meta.url);
const pins = JSON.parse(await readFile(new URL('vendor/PINS.json', root)));
const aliases = new Map([
  ['node:crypto', 'crypto'],
  ['node:fs', 'unsupported'],
  ['node:path', 'unsupported'],
  ['multiformats/cid', 'cid'],
  ['multiformats/hashes/digest', 'digest'],
  ['@ipld/dag-cbor', 'codec']
]);
const checkout = process.env.AWAI_DRIVE_WNFS_SOURCE;
if (!checkout) throw new Error('AWAI_DRIVE_WNFS_SOURCE must name the authorized network-awai/awai-fs checkout');
for (const [path, sha256] of Object.entries(pins.wnfs.files)) {
  const bytes = execFileSync('git', ['-C', checkout, 'show', pins.wnfs.revision + ':src/' + path]);
  if (createHash('sha256').update(bytes).digest('hex') !== sha256) {
    throw new Error('WNFS upstream pin drift: ' + path);
  }
  const source = bytes.toString().replace(/"([^"]+)"/g, (whole, name) =>
    aliases.has(name) ? '"@awai/drive-wnfs-platform/' + aliases.get(name) + '"' : whole);
  const file = fileURLToPath(new URL('.build/wnfs/' + path, root));
  await mkdir(dirname(file), { recursive: true });
  await writeFile(file, source);
}

for (const [path, expected] of Object.entries(pins.query.files)) {
  const source = await readFile(new URL('query/' + path, root));
  if (createHash('sha256').update(source).digest('hex') !== expected) {
    throw new Error('Kotoba Datalog pin drift: ' + path);
  }
  const file = fileURLToPath(new URL('.build/wnfs/' + path.replace(/\.cljk$/, '.cljc'), root));
  await mkdir(dirname(file), { recursive: true });
  await writeFile(file, source);
}
