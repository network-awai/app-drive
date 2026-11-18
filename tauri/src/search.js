const maxFolders = 200;
const maxResults = 100;
const searchKey = text => text.normalize('NFC').toLowerCase();
const extensions = {
  image: new Set('avif bmp gif heic heif ico jpeg jpg png svg tif tiff webp'.split(' ')),
  video: new Set('3gp avi m4v mkv mov mp4 mpeg mpg webm'.split(' ')),
  audio: new Set('aac aif aiff flac m4a mp3 oga ogg opus wav'.split(' ')),
  document: new Set('csv doc docx md odp ods odt pdf ppt pptx rtf txt xls xlsx'.split(' ')),
};

export function fileKind(item) {
  if (item.directory) return 'folder';
  const name = searchKey(item.name);
  const dot = name.lastIndexOf('.');
  if (dot < 1) return null;
  const suffix = name.slice(dot + 1);
  return Object.keys(extensions).find(kind => extensions[kind].has(suffix)) ?? null;
}

function matches(needle, kind, item) {
  const name = searchKey(item.name);
  if (!name.includes(needle)) return false;
  if (kind === 'all') return true;
  return fileKind(item) === kind;
}

// Walk only the native app-private tree. The adapter validates every folder path.
export async function searchFiles(query, listFolder, kind = 'all') {
  const needle = searchKey(query.trim());
  if (!needle && kind === 'all') return { entries: [], limited: false };

  const folders = [''];
  const entries = [];
  let limited = false;
  for (const folder of folders) {
    for (const item of await listFolder(folder)) {
      if (matches(needle, kind, item)) {
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

export function searchCatalog(query, kind, catalog) {
  const needle = searchKey(query.trim());
  const entries = catalog.map(item => ({ ...item, name: item.path.split('/').at(-1) }))
    .filter(item => matches(needle, kind, item))
    .sort((a, b) => a.path.localeCompare(b.path) ||
      (a.sourceDevice ?? '').localeCompare(b.sourceDevice ?? ''));
  return { entries: entries.slice(0, maxResults), limited: entries.length > maxResults };
}
