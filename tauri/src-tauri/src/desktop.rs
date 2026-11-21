//! Bounded macOS app-private file adapter (AWAI-3256).
//!
//! Mirrors the Android `DriveFiles` rules: names are validated and NFC-normalised, paths stay
//! inside one app-private root, symlinks are refused, a folder holds at most 1000 entries, and a
//! saved file is published with an atomic no-clobber link. This is a transport adapter only; it
//! is not an AwaiFS codec, CID or crypto implementation and has no network authority.
use sha2::{Digest, Sha256};
use std::fs;
use std::io::{Read, Write};
use std::os::unix::fs::{MetadataExt, OpenOptionsExt};
use std::path::{Component, Path, PathBuf};
use unicode_normalization::UnicodeNormalization;

pub const MAX_ENTRIES: usize = 1000;
pub const MAX_TEXT: usize = 65536;
const MAX_IMPORT: u64 = 1024 * 1024 * 1024;
pub const RESERVE: u64 = 64 * 1024 * 1024;

pub struct Local {
    root: PathBuf,
}

fn io(e: std::io::Error) -> String {
    match e.kind() {
        std::io::ErrorKind::AlreadyExists => "file-exists".into(),
        std::io::ErrorKind::NotFound => "invalid-path".into(),
        _ => "drive-operation-failed".into(),
    }
}

pub fn name(n: &str) -> Result<String, String> {
    if n.trim().is_empty()
        || n.chars().count() > 160
        || n == "."
        || n == ".."
        || n.starts_with(".pending-")
        || n.chars().any(|c| c == '/' || c == '\\' || (c as u32) < 0x20 || c as u32 == 0x7f)
    {
        return Err("invalid-name".into());
    }
    Ok(n.nfc().collect())
}

fn is_symlink(p: &Path) -> bool {
    fs::symlink_metadata(p).map(|m| m.file_type().is_symlink()).unwrap_or(false)
}

fn sync_dir(p: &Path) -> Result<(), String> {
    fs::File::open(p).and_then(|f| f.sync_all()).map_err(io)
}

#[cfg(unix)]
pub(crate) fn available(p: &Path) -> Option<u64> {
    use std::ffi::CString;
    use std::os::unix::ffi::OsStrExt;
    let c = CString::new(p.as_os_str().as_bytes()).ok()?;
    let mut s: libc::statvfs = unsafe { std::mem::zeroed() };
    if unsafe { libc::statvfs(c.as_ptr(), &mut s) } != 0 {
        return None;
    }
    Some(s.f_bavail as u64 * s.f_frsize as u64)
}

fn modified(meta: &fs::Metadata) -> u64 {
    meta.modified().ok().and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_millis().min(u64::MAX as u128) as u64).unwrap_or(0)
}

pub(crate) fn rename_exclusive(from: &Path, to: &Path) -> Result<(), String> {
    use std::ffi::CString;
    use std::os::unix::ffi::OsStrExt;
    let from = CString::new(from.as_os_str().as_bytes()).map_err(|_| "invalid-path")?;
    let to = CString::new(to.as_os_str().as_bytes()).map_err(|_| "invalid-path")?;
    // RENAME_EXCL enforces no-overwrite in the same atomic filesystem operation.
    if unsafe { libc::renamex_np(from.as_ptr(), to.as_ptr(), libc::RENAME_EXCL) } == 0 {
        Ok(())
    } else {
        Err(io(std::io::Error::last_os_error()))
    }
}

fn relocate(from: &Path, to: &Path) -> Result<(), String> {
    rename_exclusive(from, to)?;
    if let Some(dir) = from.parent() { sync_dir(dir)?; }
    if to.parent() != from.parent() {
        if let Some(dir) = to.parent() { sync_dir(dir)?; }
    }
    Ok(())
}

impl Local {
    pub fn new(base: PathBuf) -> Result<Self, String> {
        fs::create_dir_all(&base).map_err(io)?;
        let root = base.canonicalize().map_err(io)?;
        Ok(Local { root })
    }

    /// Resolve a relative, slash-separated path inside the root, refusing anything else.
    pub fn resolve(&self, rel: &str) -> Result<PathBuf, String> {
        if rel.len() > 2048 {
            return Err("invalid-path".into());
        }
        if rel.is_empty() {
            return Ok(self.root.clone());
        }
        let parts: Vec<&str> = rel.split('/').collect();
        if parts.len() > 16 {
            return Err("invalid-path".into());
        }
        let mut f = self.root.clone();
        for p in parts {
            f.push(name(p).map_err(|_| "invalid-path".to_string())?);
            if is_symlink(&f) {
                return Err("invalid-path".into());
            }
        }
        if f.components().any(|c| matches!(c, Component::ParentDir)) || !f.starts_with(&self.root) || f == self.root {
            return Err("invalid-path".into());
        }
        Ok(f)
    }

    pub fn path(&self, f: &Path) -> String {
        f.strip_prefix(&self.root).map(|p| p.to_string_lossy().into_owned()).unwrap_or_default()
    }

    fn directory(&self, p: &str) -> Result<PathBuf, String> {
        let f = self.resolve(p)?;
        if !f.is_dir() {
            return Err("invalid-path".into());
        }
        Ok(f)
    }

    pub fn list(&self, p: &str) -> Result<Vec<serde_json::Value>, String> {
        let dir = self.directory(p)?;
        let mut rows = Vec::new();
        for entry in fs::read_dir(&dir).map_err(io)? {
            let entry = entry.map_err(io)?;
            let file_name = entry.file_name().to_string_lossy().into_owned();
            if file_name.starts_with(".pending-") {
                continue;
            }
            let meta = fs::symlink_metadata(entry.path()).map_err(io)?;
            if meta.file_type().is_symlink() {
                return Err("invalid-path".into());
            }
            rows.push((meta.is_dir(), file_name, entry.path(), meta.len(), modified(&meta)));
            if rows.len() > MAX_ENTRIES {
                return Err("entry-limit".into());
            }
        }
        rows.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.cmp(&b.1)));
        Ok(rows
            .into_iter()
            .map(|(directory, name, path, bytes, modified)| {
                serde_json::json!({"name": name, "path": self.path(&path), "directory": directory,
                    "bytes": if directory { 0 } else { bytes }, "modified": modified, "local": true, "remoteVerified": false})
            })
            .collect())
    }

    fn child(&self, parent: &str, n: &str) -> Result<(PathBuf, PathBuf), String> {
        let dir = self.directory(parent)?;
        let rel = if parent.is_empty() { name(n)? } else { format!("{}/{}", parent, name(n)?) };
        Ok((dir, self.resolve(&rel)?))
    }

    pub fn mkdir(&self, parent: &str, n: &str) -> Result<(), String> {
        if self.list(parent)?.len() >= MAX_ENTRIES {
            return Err("entry-limit".into());
        }
        let (dir, dest) = self.child(parent, n)?;
        fs::create_dir(&dest).map_err(io)?;
        sync_dir(&dir)
    }

    /// Save a small text note. Written to a pending file, synced, then published with a hard
    /// link, which fails instead of replacing an existing entry.
    pub fn create_text(&self, parent: &str, n: &str, text: &str) -> Result<String, String> {
        let bytes = text.as_bytes();
        if bytes.len() > MAX_TEXT {
            return Err("file-too-large".into());
        }
        if self.list(parent)?.len() >= MAX_ENTRIES {
            return Err("entry-limit".into());
        }
        let (dir, dest) = self.child(parent, n)?;
        if fs::symlink_metadata(&dest).is_ok() {
            return Err("file-exists".into());
        }
        if available(&dir).map_or(false, |free| free < RESERVE + bytes.len() as u64) {
            return Err("storage-full".into());
        }
        let stamp = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0);
        let tmp = dir.join(format!(".pending-{}-{}.part", std::process::id(), stamp));
        let result = (|| {
            let mut f = fs::OpenOptions::new().write(true).create_new(true).open(&tmp).map_err(io)?;
            f.write_all(bytes).map_err(io)?;
            f.sync_all().map_err(io)?;
            fs::hard_link(&tmp, &dest).map_err(io)?;
            sync_dir(&dir)
        })();
        let _ = fs::remove_file(&tmp);
        result?;
        Ok(format!("{:x}", Sha256::digest(bytes)))
    }

    pub fn move_entry(&self, path: &str, destination: &str, n: &str) -> Result<String, String> {
        if path.is_empty() { return Err("invalid-path".into()); }
        let source = self.resolve(path)?;
        if !source.exists() { return Err("invalid-path".into()); }
        let (dir, target) = self.child(destination, n)?;
        if dir.starts_with(&source) { return Err("invalid-path".into()); }
        if source != target {
            if source.parent() != Some(dir.as_path()) && self.list(destination)?.len() >= MAX_ENTRIES {
                return Err("entry-limit".into());
            }
            relocate(&source, &target)?;
        }
        Ok(self.path(&target))
    }

    fn trash_root(&self) -> Result<PathBuf, String> {
        let root = self.root.parent().ok_or("invalid-path")?.join("drive-trash-v1");
        match fs::create_dir(&root) {
            Ok(()) => sync_dir(root.parent().ok_or("invalid-path")?)?,
            Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => {},
            Err(e) => return Err(io(e)),
        }
        if !root.is_dir() || is_symlink(&root) { return Err("invalid-path".into()); }
        Ok(root)
    }

    pub fn trash(&self, path: &str) -> Result<String, String> {
        if path.is_empty() { return Err("invalid-path".into()); }
        let from = self.resolve(path)?;
        let root = self.trash_root()?;
        if fs::read_dir(&root).map_err(io)?.take(MAX_ENTRIES).count() >= MAX_ENTRIES {
            return Err("entry-limit".into());
        }
        let id = format!("{:x}-{:x}", std::process::id(), std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH).map_err(|_| "storage-unavailable")?.as_nanos());
        let slot = root.join(&id);
        fs::create_dir(&slot).map_err(io)?;
        let result = (|| {
            let mut record = fs::OpenOptions::new().write(true).create_new(true).open(slot.join("original")).map_err(io)?;
            record.write_all(self.path(&from).as_bytes()).map_err(io)?;
            record.sync_all().map_err(io)?;
            sync_dir(&slot)?;
            sync_dir(&root)?;
            relocate(&from, &slot.join("item"))
        })();
        if result.is_err() && !slot.join("item").exists() {
            let _ = fs::remove_file(slot.join("original"));
            let _ = fs::remove_dir(&slot);
        }
        result?;
        Ok(id)
    }

    fn trash_slot(&self, id: &str) -> Result<PathBuf, String> {
        if id.is_empty() || id.len() > 64 || !id.bytes().all(|c| c.is_ascii_hexdigit() || c == b'-') {
            return Err("invalid-path".into());
        }
        let slot = self.trash_root()?.join(id);
        if !slot.is_dir() || [&slot, &slot.join("item"), &slot.join("original")].iter().any(|p| is_symlink(p)) {
            return Err("invalid-path".into());
        }
        Ok(slot)
    }

    fn original(slot: &Path) -> Result<String, String> {
        let mut path = String::new();
        fs::File::open(slot.join("original")).map_err(io)?.take(8193).read_to_string(&mut path).map_err(io)?;
        if path.is_empty() || path.len() > 8192 { return Err("invalid-path".into()); }
        Ok(path)
    }

    pub fn trash_list(&self) -> Result<Vec<serde_json::Value>, String> {
        let mut rows = Vec::new();
        for slot in fs::read_dir(self.trash_root()?).map_err(io)? {
            let slot = slot.map_err(io)?;
            let id = slot.file_name().into_string().map_err(|_| "invalid-path")?;
            let slot = self.trash_slot(&id)?;
            let item = slot.join("item");
            if !item.exists() { continue; }
            let path = Self::original(&slot)?;
            let meta = fs::symlink_metadata(item).map_err(io)?;
            rows.push(serde_json::json!({"name": path.rsplit('/').next(), "path": id, "directory": meta.is_dir(),
                "modified": modified(&meta), "bytes": if meta.is_dir() { 0 } else { meta.len() }}));
            if rows.len() > MAX_ENTRIES { return Err("entry-limit".into()); }
        }
        Ok(rows)
    }

    pub fn recover(&self, id: &str) -> Result<(), String> {
        let slot = self.trash_slot(id)?;
        let path = Self::original(&slot)?;
        let (parent, name) = path.rsplit_once('/').unwrap_or(("", &path));
        if self.list(parent)?.len() >= MAX_ENTRIES { return Err("entry-limit".into()); }
        let (_, to) = self.child(parent, name)?;
        relocate(&slot.join("item"), &to)?;
        let _ = fs::remove_file(slot.join("original"));
        let _ = fs::remove_dir(&slot);
        Ok(())
    }

    pub fn preview_text(&self, path: &str) -> Result<String, String> {
        let f = self.file(path)?;
        let mut bytes = Vec::new();
        fs::File::open(f).map_err(io)?.take(65_536).read_to_end(&mut bytes).map_err(io)?;
        Ok(String::from_utf8_lossy(&bytes).into_owned())
    }

    /// Copy a file explicitly selected by the native picker into Drive storage.
    /// Never write into the selected volume or take a source path from the WebView.
    pub fn import(&self, parent: &str, source: &Path) -> Result<serde_json::Value, String> {
        let name = source.file_name().and_then(|n| n.to_str()).ok_or("invalid-name")?;
        if self.list(parent)?.len() >= MAX_ENTRIES {
            return Err("entry-limit".into());
        }
        let (dir, dest) = self.child(parent, name)?;
        if fs::symlink_metadata(&dest).is_ok() {
            return Err("file-exists".into());
        }
        let selected = fs::symlink_metadata(source).map_err(io)?;
        if !selected.is_file() {
            return Err("invalid-path".into());
        }
        let mut input = fs::OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK)
            .open(source)
            .map_err(io)?;
        let before = input.metadata().map_err(io)?;
        if !before.is_file() || (selected.dev(), selected.ino()) != (before.dev(), before.ino()) {
            return Err("invalid-path".into());
        }
        if before.len() > MAX_IMPORT {
            return Err("file-too-large".into());
        }
        if available(&dir).is_some_and(|free| free < RESERVE + before.len()) {
            return Err("storage-full".into());
        }
        let stamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .map_err(|_| "drive-operation-failed")?;
        let tmp = dir.join(format!(".pending-{}-{}.part", std::process::id(), stamp));
        let mut output = fs::OpenOptions::new()
            .read(true).write(true).create_new(true).mode(0o600)
            .open(&tmp).map_err(io)?;

        let result = (|| {
            let mut buf = vec![0; 256 * 1024];
            let mut total = 0u64;
            let mut source_hash = Sha256::new();
            loop {
                let n = input.read(&mut buf).map_err(io)?;
                if n == 0 { break; }
                total += n as u64;
                if total > before.len() { return Err("source-size-changed".into()); }
                output.write_all(&buf[..n]).map_err(io)?;
                source_hash.update(&buf[..n]);
            }
            let after = input.metadata().map_err(io)?;
            if total != before.len() || after.len() != before.len() ||
                (before.mtime(), before.mtime_nsec(), before.ctime(), before.ctime_nsec()) !=
                (after.mtime(), after.mtime_nsec(), after.ctime(), after.ctime_nsec()) {
                return Err("source-size-changed".into());
            }
            output.sync_all().map_err(io)?;
            drop(output);

            let expected = source_hash.finalize();
            let mut saved = fs::File::open(&tmp).map_err(io)?;
            let mut copy_hash = Sha256::new();
            loop {
                let n = saved.read(&mut buf).map_err(io)?;
                if n == 0 { break; }
                copy_hash.update(&buf[..n]);
            }
            if copy_hash.finalize() != expected {
                return Err("restore-mismatch".into());
            }
            fs::hard_link(&tmp, &dest).map_err(io)?;
            sync_dir(&dir)?;
            Ok(serde_json::json!({"path": self.path(&dest), "sha256": format!("{:x}", expected), "bytes": total}))
        })();
        let _ = fs::remove_file(&tmp);
        result
    }

    pub fn file(&self, rel: &str) -> Result<PathBuf, String> {
        let f = self.resolve(rel)?;
        if !f.is_file() {
            return Err("invalid-path".into());
        }
        Ok(f)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn temp() -> Local {
        let d = std::env::temp_dir().join(format!("awai-drive-test-{}-{}", std::process::id(),
            std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
        Local::new(d.join("drive-local-v1")).unwrap()
    }

    #[test]
    fn imports_a_local_file_once_without_overwrite() {
        let l = temp();
        let outside = l.root.parent().unwrap().join("import.bin");
        fs::write(&outside, &[0, 1, 2, 255]).unwrap();
        l.import("", &outside).unwrap();
        assert_eq!(fs::read(l.file("import.bin").unwrap()).unwrap(), [0, 1, 2, 255]);
        assert_eq!(l.import("", &outside).unwrap_err(), "file-exists");
        assert_eq!(fs::read(&outside).unwrap(), [0, 1, 2, 255]);
    }

    #[test]
    fn organizes_without_overwrite_and_recovers_from_trash() {
        let l = temp();
        l.mkdir("", "folder").unwrap();
        l.create_text("", "file.txt", "first").unwrap();
        l.create_text("folder", "file.txt", "second").unwrap();
        assert_eq!(l.move_entry("file.txt", "folder", "file.txt").unwrap_err(), "file-exists");
        assert_eq!(l.preview_text("file.txt").unwrap(), "first");
        assert_eq!(l.preview_text("folder/file.txt").unwrap(), "second");
        assert!(l.move_entry("folder", "folder", "nested").is_err());
        assert_eq!(l.move_entry("file.txt", "folder", "moved.txt").unwrap(), "folder/moved.txt");
        let id = l.trash("folder/moved.txt").unwrap();
        assert_eq!(l.trash_list().unwrap()[0]["path"], id);
        assert_eq!(l.list("folder").unwrap().len(), 1);
        l.create_text("folder", "moved.txt", "conflict").unwrap();
        assert_eq!(l.recover(&id).unwrap_err(), "file-exists");
        assert_eq!(l.preview_text("folder/moved.txt").unwrap(), "conflict");
        l.move_entry("folder/moved.txt", "folder", "different.txt").unwrap();
        l.recover(&id).unwrap();
        assert_eq!(l.preview_text("folder/moved.txt").unwrap(), "first");
        assert!(l.trash_list().unwrap().is_empty());
    }

    #[test]
    fn refuses_bad_names_and_paths() {
        let l = temp();
        for n in ["", " ", ".", "..", "a/b", "a\\b", ".pending-x", "a\u{0}b"] {
            assert_eq!(name(n), Err("invalid-name".to_string()), "{n:?}");
        }
        assert!(name(&"x".repeat(161)).is_err());
        for p in ["../etc", "a/../../b", "/abs", "a//b"] {
            assert_eq!(l.resolve(p), Err("invalid-path".to_string()), "{p}");
        }
        assert!(l.resolve(&vec!["a"; 17].join("/")).is_err());
    }

    #[test]
    fn saves_lists_and_reads_back() {
        let l = temp();
        l.mkdir("", "メモ").unwrap();
        let h = l.create_text("メモ", "note.txt", "hello").unwrap();
        assert_eq!(h, "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        let rows = l.list("メモ").unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0]["path"], "メモ/note.txt");
        assert_eq!(fs::read_to_string(l.file("メモ/note.txt").unwrap()).unwrap(), "hello");
        assert_eq!(l.create_text("メモ", "note.txt", "again"), Err("file-exists".to_string()));
        assert_eq!(fs::read_to_string(l.file("メモ/note.txt").unwrap()).unwrap(), "hello");
        assert_eq!(l.mkdir("", "メモ"), Err("file-exists".to_string()));
        assert!(l.list("").unwrap()[0]["directory"].as_bool().unwrap());
    }

    #[test]
    fn picked_file_copies_verified_bytes_without_replacing_either_side() {
        let local = temp();
        let source = temp();
        local.mkdir("", "Imported").unwrap();
        let selected = source.root.join("sample.bin");
        let bytes = vec![0x6d; 12 * 1024 * 1024];
        fs::write(&selected, &bytes).unwrap();
        let receipt = local.import("Imported", &selected).unwrap();
        assert_eq!(receipt["bytes"], bytes.len());
        assert_eq!(receipt["sha256"], format!("{:x}", Sha256::digest(&bytes)));
        let dest = local.file("Imported/sample.bin").unwrap();
        assert_eq!(fs::read(&dest).unwrap(), bytes);
        assert_eq!(fs::read(&selected).unwrap(), bytes);
        fs::write(&selected, b"changed original").unwrap();
        assert_eq!(local.import("Imported", &selected), Err("file-exists".to_string()));
        assert_eq!(fs::read(&dest).unwrap(), bytes);
        let alias = source.root.join("link.bin");
        std::os::unix::fs::symlink(&selected, &alias).unwrap();
        assert!(local.import("Imported", &alias).is_err());
        assert_eq!(local.list("Imported").unwrap().len(), 1);
        fs::remove_dir_all(local.root).unwrap();
        fs::remove_dir_all(source.root).unwrap();
    }

    #[test]
    fn refuses_symlinks_large_text_and_files_as_folders() {
        let l = temp();
        std::os::unix::fs::symlink("/etc", l.root.join("link")).unwrap();
        assert_eq!(l.resolve("link"), Err("invalid-path".to_string()));
        assert_eq!(l.list(""), Err("invalid-path".to_string()));
        fs::remove_file(l.root.join("link")).unwrap();
        assert_eq!(l.create_text("", "big", &"x".repeat(MAX_TEXT + 1)), Err("file-too-large".to_string()));
        l.create_text("", "f", "x").unwrap();
        assert_eq!(l.list("f"), Err("invalid-path".to_string()));
        assert_eq!(l.file(""), Err("invalid-path".to_string()));
    }

    #[test]
    fn normalizes_names_to_nfc() {
        let l = temp();
        l.create_text("", "e\u{301}.txt", "x").unwrap();
        assert_eq!(l.list("").unwrap()[0]["name"], "\u{e9}.txt");
    }
}
