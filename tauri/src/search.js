const maxFolders = 200;
const maxResults = 100;
const searchKey = text => text.normalize('NFC').toLowerCase();

// Walk only the native app-private tree. The adapter validates every folder path.
export async function searchFiles(query, listFolder) {
  const needle = searchKey(query.trim());
  if (!needle) return { entries: [], limited: false };

  const folders = [''];
  const entries = [];
  let limited = false;
  for (const folder of folders) {
    for (const item of await listFolder(folder)) {
      if (searchKey(item.name).includes(needle)) {
        if (entries.length === maxResults) return { entries, limited: true };
        entries.push(item);
      }
      if (item.directory) {
        if (folders.length < maxFolders) folders.push(item.path);
        else limited = true;
      }
    }
  }
  return { entries, limited };
}
