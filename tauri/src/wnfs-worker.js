self.window = self;
importScripts('./wnfs-core.js');

self.onmessage = async ({ data: { operation, record, secret, rows, root, capsule, packs } }) => {
  try {
    if (operation === 'seal') {
      const result = await self.AwaiDriveWnfs.seal(record, secret, rows);
      self.postMessage({ result }, [result.capsule.bytes.buffer, ...result.packs.map(bytes => bytes.buffer)]);
    } else if (operation === 'open') {
      const result = await self.AwaiDriveWnfs.open(record, secret, root, capsule, packs);
      self.postMessage({ result }, result.flatMap(row => (row.chunks || []).map(bytes => bytes.buffer)));
    } else if (operation === 'catalog') {
      self.postMessage({ result: await self.AwaiDriveWnfs.catalog(record, secret, root, capsule, packs) });
    } else {
      throw Error('invalid-wnfs-worker-operation');
    }
  } catch (error) {
    self.postMessage({ error: String(error?.message || 'invalid-wnfs-snapshot').slice(0, 80) });
  } finally {
    secret.fill(0);
    for (const row of rows || []) row.bytes?.fill(0);
  }
};
