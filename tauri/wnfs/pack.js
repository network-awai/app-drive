import { CarBufferReader } from '@ipld/car/buffer-reader';
import * as CarBufferWriter from '@ipld/car/buffer-writer';

export const write = (root, blocks) => {
  let size = CarBufferWriter.headerLength({ roots: [root] });
  for (const { cid, bytes } of blocks) size += CarBufferWriter.blockLength({ cid, bytes });
  if (size > 8 * 1024 * 1024) throw Error('sync-size-limit');
  const writer = CarBufferWriter.createWriter(new ArrayBuffer(size), { roots: [root] });
  for (const block of blocks) writer.write(block);
  return writer.close();
};
export const read = bytes => {
  if (bytes.length > 8 * 1024 * 1024) throw Error('sync-size-limit');
  return CarBufferReader.fromBytes(bytes);
};
