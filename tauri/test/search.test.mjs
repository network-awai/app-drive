import assert from 'node:assert/strict';
import test from 'node:test';
import {searchFiles} from '../src/search.js';

const entry = (path, directory = false) => ({ path, directory, name: path.split('/').at(-1) });

test('searches file and folder names across the local tree', async () => {
  const tree = {
    '': [entry('旅行', true), entry('Foto.jpg')],
    '旅行': [entry('旅行/2026', true), entry('旅行/撮影リスト.txt')],
    '旅行/2026': [entry('旅行/2026/VIDEO.MP4'), entry('旅行/2026/Cafe\u0301.txt')],
  };
  const list = async folder => tree[folder];
  assert.deepEqual((await searchFiles(' video ', list)).entries, [entry('旅行/2026/VIDEO.MP4')]);
  assert.deepEqual((await searchFiles('カメラ', list)), { entries: [], limited: false });
  assert.deepEqual((await searchFiles('撮影', list)).entries, [entry('旅行/撮影リスト.txt')]);
  assert.deepEqual((await searchFiles('旅行', list)).entries, [entry('旅行', true)]);
  assert.deepEqual((await searchFiles('CAFÉ', list)).entries, [entry('旅行/2026/Cafe\u0301.txt')]);
  assert.deepEqual(await searchFiles(' ', () => assert.fail('Empty query opened the store')),
    { entries: [], limited: false });
});

test('reports partial results only once another match or folder exceeds the limits', async () => {
  const matches = Array.from({ length: 100 }, (_, index) => entry(`image-${index}`));
  assert.deepEqual(await searchFiles('image', async () => matches), { entries: matches, limited: false });
  assert.deepEqual(await searchFiles('image', async () => [...matches, entry('image-extra')]),
    { entries: matches, limited: true });

  const folders = Array.from({ length: 200 }, (_, index) => entry(`folder-${index}`, true));
  let reads = 0;
  const result = await searchFiles('never-matches', async folder => {
    reads++;
    return folder ? [] : folders;
  });
  assert.equal(reads, 200);
  assert.deepEqual(result, { entries: [], limited: true });
});
