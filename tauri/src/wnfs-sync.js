const CHUNK_BYTES = 65531;
const PACK_BYTES = 12 * 1024 * 1024;
const PACK_ID = /^[a-f0-9]{64}$/;
const b64 = bytes => {
  let text = '';
  for (let start = 0; start < bytes.length; start += 16384) {
    text += String.fromCharCode(...bytes.subarray(start, start + 16384));
  }
  return btoa(text);
};
const unb64 = text => Uint8Array.from(atob(text), char => char.charCodeAt(0));

function computeInWorker(operation, session, input, buffers) {
  const worker = new Worker('./wnfs-worker.js');
  const secret = session.secret.slice();
  return new Promise((resolve, reject) => {
    worker.onmessage = ({ data }) => {
      if (data.error) reject(Error(data.error));
      else resolve(data.result);
    };
    worker.onerror = () => reject(Error('invalid-wnfs-snapshot'));
    worker.onmessageerror = () => reject(Error('invalid-wnfs-snapshot'));
    worker.postMessage({ operation, record: session.record, secret, ...input },
      [secret.buffer, ...buffers]);
  }).finally(() => {
    if (secret.byteLength) secret.fill(0);
    worker.terminate();
  });
}

export function createWnfsSync({ invoke, origin, packs, verifyReceipt, sha256, compute = computeInWorker }) {
  async function restoreCopy(rows, legacy = false) {
    try {
      await invoke('vaultWnfsRestoreBegin', { legacy });
      for (const { path, directory, chunks } of rows) {
        if (directory) continue;
        let offset = 0;
        for (const chunk of chunks) {
          for (let start = 0; start < chunk.length; start += CHUNK_BYTES) {
            const piece = chunk.subarray(start, start + CHUNK_BYTES);
            await invoke('vaultWnfsRestoreWrite', { path, offset, bytes: b64(piece) });
            offset += piece.length;
          }
        }
        if (offset === 0) await invoke('vaultWnfsRestoreWrite', { path, offset: 0, bytes: '' });
      }
      return await invoke('vaultWnfsRestorePublish', { legacy });
    } catch (error) {
      await invoke('vaultWnfsRestoreAbort').catch(() => {});
      throw error;
    } finally {
      for (const { chunks = [] } of rows) for (const chunk of chunks) chunk.fill(0);
    }
  }

  return {
    restoreCopy,
    async sync(session) {
      const { entries } = await invoke('vaultWnfsInventory');
      if (!entries.length) throw Error('empty-sync-snapshot');
      const rows = [];
      try {
        for (const entry of entries) {
          if (entry.directory) {
            rows.push({ path: entry.path, directory: true });
            continue;
          }
          const bytes = new Uint8Array(entry.size);
          rows.push({ path: entry.path, bytes });
          for (let offset = 0; offset < entry.size; offset += CHUNK_BYTES) {
            const length = Math.min(CHUNK_BYTES, entry.size - offset);
            const piece = unb64((await invoke('vaultRead', { path: entry.path, offset, length })).bytes);
            if (piece.length !== length) throw Error('source-size-changed');
            bytes.set(piece, offset);
            piece.fill(0);
          }
          if (await sha256(bytes) !== entry.sha256) throw Error('source-size-changed');
        }
        const sealed = await compute('seal', session, { rows },
          rows.flatMap(row => row.bytes ? [row.bytes.buffer] : []));
        const dataIds = [];
        for (const pack of sealed.packs) dataIds.push(await packs.publish(pack, verifyReceipt));
        const capsule = await packs.publish(sealed.capsule.bytes, verifyReceipt);
        await invoke('vaultWnfsCommit', { root: sealed.capsule.root, capsule, packs: dataIds, origin, entries });
        return { files: rows.filter(row => !row.directory).length };
      } finally {
        for (const row of rows) if (row.bytes?.byteLength) row.bytes.fill(0);
      }
    },

    async restore(session) {
      const head = session.wnfs;
      if (head?.schema !== 'awai.drive.wnfs.v1' || head.origin !== origin ||
          !PACK_ID.test(head.root) || !PACK_ID.test(head.capsule) || !Array.isArray(head.packs) ||
          head.packs.length < 1 || head.packs.length > 8 ||
          head.packs.some(id => !PACK_ID.test(id)) ||
          new Set([head.capsule, ...head.packs]).size !== head.packs.length + 1) {
        throw Error('recovery-required');
      }
      const capsule = await packs.download(head.capsule);
      if (capsule.length > 262144) throw Error('invalid-wnfs-snapshot');
      const data = [];
      let size = 0;
      for (const id of head.packs) {
        const pack = await packs.download(id);
        size += pack.length;
        if (size > 4 * PACK_BYTES) throw Error('invalid-wnfs-snapshot');
        data.push(pack);
      }
      const rows = await compute('open', session, { root: head.root, capsule, packs: data },
        [capsule.buffer, ...data.map(pack => pack.buffer)]);
      return restoreCopy(rows);
    }
  };
}
