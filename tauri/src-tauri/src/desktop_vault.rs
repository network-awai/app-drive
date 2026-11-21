//! macOS storage adapter for the existing Drive WNFS IPC. WNFS crypto and
//! network consent remain in the shared worker and broker.
use crate::desktop::{Local, MAX_ENTRIES, RESERVE};
use aes_gcm::{
    aead::{Aead, KeyInit, Payload},
    Aes256Gcm, Nonce,
};
use base64::{engine::general_purpose::STANDARD, Engine};
use security_framework::os::macos::keychain::SecKeychain;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, HashSet},
    ffi::CString,
    fs::{self, File, OpenOptions},
    io::{Read, Seek, SeekFrom, Write},
    os::{
        fd::AsRawFd,
        unix::{
            ffi::OsStrExt,
            fs::{DirBuilderExt, OpenOptionsExt},
        },
    },
    path::{Path, PathBuf},
};
use zeroize::Zeroizing;

const LIMIT: u64 = 32 * 1024 * 1024;
const CHUNK: usize = 65_531;
const CONTROL_LIMIT: u64 = 262_144;
const AAD: &[u8] = b"awai.drive.macos.wnfs.v1";
const KEY_ACCOUNT: &str = "wnfs-device-v1";
const KEYCHAIN_ITEM_NOT_FOUND: i32 = -25300;
type Result<T> = std::result::Result<T, String>;

fn io(error: std::io::Error) -> String {
    match error.kind() {
        std::io::ErrorKind::AlreadyExists => "file-exists",
        std::io::ErrorKind::NotFound => "invalid-path",
        _ => "storage-unavailable",
    }
    .into()
}
fn sync_dir(path: &Path) -> Result<()> {
    File::open(path)
        .and_then(|file| file.sync_all())
        .map_err(io)
}
fn random<const N: usize>() -> Result<[u8; N]> {
    let mut bytes = [0; N];
    getrandom::fill(&mut bytes).map_err(|_| "storage-unavailable")?;
    Ok(bytes)
}
fn token() -> Result<String> {
    Ok(random::<16>()?.iter().map(|b| format!("{b:02x}")).collect())
}
fn text<'a>(request: &'a Value, name: &str) -> Result<&'a str> {
    request
        .get(name)
        .and_then(Value::as_str)
        .ok_or_else(|| "invalid-request".into())
}
fn digest_id(id: &str) -> bool {
    id.len() == 64
        && id
            .bytes()
            .all(|b| b.is_ascii_digit() || matches!(b, b'a'..=b'f'))
}
const RECEIPT_LIMIT: usize = 4096;
/// Shape check of the root receipt the WebView verified against the build-pinned key set
/// (receipt/verify.mjs): it must name this commit's capsule pack and its key epoch.
fn root_receipt(raw: &Value, capsule: &str) -> Result<Option<Value>> {
    if raw.is_null() {
        return Ok(None);
    }
    let bad = || String::from("invalid-receipt");
    let object = raw.as_object().filter(|o| o.len() == 5).ok_or_else(bad)?;
    let cid = object
        .get("receiptCid")
        .and_then(Value::as_str)
        .ok_or_else(bad)?;
    let key_id = object
        .get("keyId")
        .and_then(Value::as_str)
        .ok_or_else(bad)?;
    let epoch = object
        .get("keyEpoch")
        .and_then(Value::as_u64)
        .filter(|e| (1..=u32::MAX as u64).contains(e))
        .ok_or_else(bad)?;
    let envelope = object.get("receipt").ok_or_else(bad)?;
    let fields = &envelope["receipt"];
    let subject = format!("0x{capsule}");
    if object.get("pack").and_then(Value::as_str) != Some(capsule)
        || !(cid.len() == 59
            && cid.starts_with("bafyrei")
            && cid.bytes().all(|b| matches!(b, b'a'..=b'z' | b'2'..=b'7')))
        || !(key_id.len() == 66 && key_id.starts_with("0x") && digest_id(&key_id[2..]))
        || envelope["v"].as_u64() != Some(1)
        || fields["subject"].as_str() != Some(&subject)
        || fields["contentDigest"].as_str() != Some(&subject)
        || fields["keyId"].as_str() != Some(key_id)
        || fields["keyEpoch"].as_u64() != Some(epoch)
        || serde_json::to_vec(raw).map_err(|_| bad())?.len() > RECEIPT_LIMIT
    {
        return Err(bad());
    }
    Ok(Some(raw.clone()))
}
fn private_dir(path: &Path) -> Result<()> {
    match fs::DirBuilder::new().mode(0o700).create(path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
            fs::symlink_metadata(path)
                .map_err(io)?
                .is_dir()
                .then_some(())
                .ok_or("invalid-path".into())
        }
        Err(error) => Err(io(error)),
    }
}
fn read_file(path: &Path) -> Result<File> {
    let file = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK)
        .open(path)
        .map_err(io)?;
    if !file.metadata().map_err(io)?.is_file() {
        return Err("invalid-path".into());
    }
    Ok(file)
}
fn rename_exclusive(from: &Path, to: &Path) -> Result<()> {
    let from = CString::new(from.as_os_str().as_bytes()).map_err(|_| "invalid-path")?;
    let to = CString::new(to.as_os_str().as_bytes()).map_err(|_| "invalid-path")?;
    // RENAME_EXCL enforces no-overwrite in the same atomic filesystem operation.
    if unsafe { libc::renamex_np(from.as_ptr(), to.as_ptr(), libc::RENAME_EXCL) } == 0 {
        Ok(())
    } else {
        Err(io(std::io::Error::last_os_error()))
    }
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
struct Entry {
    path: String,
    directory: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    size: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    sha256: Option<String>,
}
type Inventory = BTreeMap<String, Entry>;

fn entries(local: &Local, raw: &Value) -> Result<Inventory> {
    let rows: Vec<Entry> = serde_json::from_value(raw.clone()).map_err(|_| "invalid-inventory")?;
    if rows.len() > MAX_ENTRIES {
        return Err("entry-limit".into());
    }
    let mut total = 0;
    let mut result = Inventory::new();
    for row in rows {
        local.resolve(&row.path)?;
        if row.path.is_empty() || result.contains_key(&row.path) {
            return Err("invalid-inventory".into());
        }
        match (row.directory, row.size, row.sha256.as_deref()) {
            (true, None, None) => {}
            (false, Some(n), Some(hash)) if n <= LIMIT && digest_id(hash) => total += n,
            _ => return Err("invalid-inventory".into()),
        }
        result.insert(row.path.clone(), row);
    }
    if total > LIMIT {
        return Err("sync-size-limit".into());
    }
    for path in result.keys() {
        if let Some((parent, _)) = path.rsplit_once('/') {
            if !result.get(parent).is_some_and(|entry| entry.directory) {
                return Err("invalid-inventory".into());
            }
        }
    }
    Ok(result)
}

fn scan(local: &Local, path: &str, entries: &mut Inventory, remaining: &mut u64) -> Result<()> {
    for row in local.list(path)? {
        if entries.len() >= MAX_ENTRIES {
            return Err("entry-limit".into());
        }
        let path = text(&row, "path")?.to_owned();
        let directory = row["directory"].as_bool().ok_or("invalid-path")?;
        let mut entry = Entry {
            path: path.clone(),
            directory,
            size: None,
            sha256: None,
        };
        if !directory {
            let mut file = read_file(&local.file(&path)?)?;
            let mut hash = Sha256::new();
            let size =
                std::io::copy(&mut (&mut file).take(*remaining + 1), &mut hash).map_err(io)?;
            *remaining = remaining.checked_sub(size).ok_or("sync-size-limit")?;
            if file.metadata().map_err(io)?.len() != size {
                return Err("source-size-changed".into());
            }
            entry.size = Some(size);
            entry.sha256 = Some(format!("{:x}", hash.finalize()));
        }
        entries.insert(path.clone(), entry);
        if directory {
            scan(local, &path, entries, remaining)?;
        }
    }
    Ok(())
}
fn inventory(local: &Local) -> Result<Inventory> {
    let mut rows = Inventory::new();
    let mut remaining = LIMIT;
    scan(local, "", &mut rows, &mut remaining)?;
    Ok(rows)
}

#[derive(Default, Deserialize, Serialize)]
struct Control {
    #[serde(default)]
    account_link: Option<Value>,
    record: Option<String>,
    wnfs: Option<Value>,
    /// Verified storage receipt of the current WNFS root only (owner decision 2026-11-22).
    /// Replaced on every commit; no history is kept. Restore does not need it.
    #[serde(default)]
    root_receipt: Option<Value>,
    recipients: Recipients,
}
#[derive(Default, Deserialize, Serialize)]
struct Recipients {
    revision: u64,
    items: BTreeMap<String, Value>,
}

fn control_cipher(key: &[u8]) -> Result<Aes256Gcm> {
    Aes256Gcm::new_from_slice(key.get(32..).ok_or("recovery-required")?)
        .map_err(|_| "recovery-required".into())
}
fn open_control(key: &[u8], bytes: &[u8]) -> Result<Control> {
    if bytes.len() < 28 || bytes.len() as u64 > CONTROL_LIMIT {
        return Err("recovery-required".into());
    }
    let plain = Zeroizing::new(
        control_cipher(key)?
            .decrypt(
                Nonce::from_slice(&bytes[..12]),
                Payload {
                    msg: &bytes[12..],
                    aad: AAD,
                },
            )
            .map_err(|_| "recovery-required")?,
    );
    serde_json::from_slice(&plain).map_err(|_| "recovery-required".into())
}
fn seal_control(key: &[u8], control: &Control) -> Result<Vec<u8>> {
    let plain = Zeroizing::new(serde_json::to_vec(control).map_err(|_| "state-limit")?);
    if plain.len() as u64 > CONTROL_LIMIT - 28 {
        return Err("state-limit".into());
    }
    let nonce = random::<12>()?;
    let bytes = control_cipher(key)?
        .encrypt(
            Nonce::from_slice(&nonce),
            Payload {
                msg: &plain,
                aad: AAD,
            },
        )
        .map_err(|_| "storage-unavailable")?;
    Ok([nonce.as_slice(), bytes.as_slice()].concat())
}

struct Restore {
    operation: String,
    folder: String,
    expected: Inventory,
}

pub struct Vault {
    root: PathBuf,
    service: String,
    restore: Option<Restore>,
    _lock: File,
}
impl Vault {
    pub fn new(root: PathBuf, bundle: &str) -> Result<Self> {
        private_dir(&root)?;
        let lock = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW)
            .open(root.join("lock"))
            .map_err(io)?;
        // The lock lives for the app process, including interrupted restore staging.
        if unsafe { libc::flock(lock.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } != 0 {
            return Err("vault-in-use".into());
        }
        Ok(Self {
            root,
            service: format!("{bundle}.vault"),
            restore: None,
            _lock: lock,
        })
    }

    fn key(&self, initialize: bool) -> Result<Option<Zeroizing<Vec<u8>>>> {
        let chain = SecKeychain::default().map_err(|_| "keychain-unavailable")?;
        match chain.find_generic_password(&self.service, KEY_ACCOUNT) {
            Ok((password, _)) if password.as_ref().len() == 64 => {
                Ok(Some(Zeroizing::new(password.as_ref().to_vec())))
            }
            Err(error) if error.code() == KEYCHAIN_ITEM_NOT_FOUND => {
                match fs::symlink_metadata(self.root.join("state.bin")) {
                    Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                    _ => return Err("recovery-required".into()),
                }
                if !initialize {
                    return Ok(None);
                }
                let key = Zeroizing::new(random::<64>()?.to_vec());
                chain
                    .add_generic_password(&self.service, KEY_ACCOUNT, &key)
                    .map_err(|_| "keychain-unavailable")?;
                Ok(Some(key))
            }
            _ => Err("keychain-unavailable".into()),
        }
    }

    fn load(&self, key: &[u8]) -> Result<Control> {
        let mut bytes = Vec::new();
        match read_file(&self.root.join("state.bin")) {
            Ok(file) => file
                .take(CONTROL_LIMIT + 1)
                .read_to_end(&mut bytes)
                .map_err(io)?,
            Err(error) if error == "invalid-path" => {
                if matches!(fs::symlink_metadata(self.root.join("state.bin")), Err(e) if e.kind() == std::io::ErrorKind::NotFound)
                {
                    return Ok(Control::default());
                }
                return Err("recovery-required".into());
            }
            Err(error) => return Err(error),
        };
        open_control(key, &bytes)
    }

    fn save(&self, key: &[u8], state: &Control) -> Result<()> {
        let bytes = seal_control(key, state)?;
        let pending = self.root.join(format!(".pending-{}", token()?));
        let result = (|| {
            let mut out = OpenOptions::new()
                .write(true)
                .create_new(true)
                .mode(0o600)
                .open(&pending)
                .map_err(io)?;
            out.write_all(&bytes)
                .and_then(|_| out.sync_all())
                .map_err(io)?;
            fs::rename(&pending, self.root.join("state.bin")).map_err(io)?;
            sync_dir(&self.root)
        })();
        let _ = fs::remove_file(&pending);
        result
    }

    fn stage(&self) -> PathBuf {
        self.root.join("restore-stage")
    }

    fn clear_stage(&self) -> Result<()> {
        let stage = self.stage();
        match fs::symlink_metadata(&stage) {
            Ok(meta) if meta.is_dir() => fs::remove_dir_all(stage).map_err(io),
            Ok(_) => Err("invalid-path".into()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(error) => Err(io(error)),
        }
    }

    fn restoring(&self, request: &Value) -> Result<&Restore> {
        let operation = text(request, "operation")?;
        self.restore
            .as_ref()
            .filter(|restore| restore.operation == operation)
            .ok_or_else(|| "restore-operation-mismatch".into())
    }

    fn begin(&mut self, local: &Local, state: &Control, request: &Value) -> Result<Value> {
        if self.restore.is_some() {
            return Err("restore-in-progress".into());
        }
        let share = request
            .get("share")
            .map(|_| text(request, "share"))
            .transpose()?;
        let (prefix, id, raw) = if let Some(share) = share {
            ("Shared", share, &request["entries"])
        } else {
            let head = state.wnfs.as_ref().ok_or("recovery-required")?;
            ("Recovered", text(head, "capsule")?, &head["entries"])
        };
        if !digest_id(id) {
            return Err("invalid-root".into());
        }
        let folder = format!("{prefix} {id}");
        let expected = entries(local, raw)?;
        self.clear_stage()?;
        private_dir(&self.stage())?;
        let operation = token()?;
        self.restore = Some(Restore {
            operation: operation.clone(),
            folder,
            expected,
        });
        Ok(json!({"operation":operation}))
    }

    fn write(&self, request: &Value) -> Result<Value> {
        let restore = self.restoring(request)?;
        let path = text(request, "path")?;
        let offset = request["offset"].as_u64().ok_or("invalid-request")?;
        let data = Zeroizing::new(
            STANDARD
                .decode(text(request, "bytes")?)
                .map_err(|_| "invalid-request")?,
        );
        let size = restore
            .expected
            .get(path)
            .and_then(|row| row.size)
            .ok_or("invalid-path")?;
        if data.len() > CHUNK
            || offset
                .checked_add(data.len() as u64)
                .filter(|n| *n <= size)
                .is_none()
        {
            return Err("restore-mismatch".into());
        }
        let directory = self.stage();
        if crate::desktop::available(&directory)
            .filter(|n| *n >= RESERVE + data.len() as u64)
            .is_none()
        {
            return Err("storage-full".into());
        }
        let stage = Local::new(directory)?;
        let target = stage.resolve(path)?;
        fs::create_dir_all(target.parent().ok_or("invalid-path")?).map_err(io)?;
        let mut file = OpenOptions::new()
            .write(true)
            .create(true)
            .append(true)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK)
            .open(target)
            .map_err(io)?;
        let metadata = file.metadata().map_err(io)?;
        if !metadata.is_file() || metadata.len() != offset {
            return Err("restore-mismatch".into());
        }
        file.write_all(&data).map_err(io)?;
        Ok(json!({}))
    }

    fn publish(&mut self, local: &Local, request: &Value) -> Result<Value> {
        let restore = self.restoring(request)?;
        let name = restore.folder.clone();
        let dest = local.resolve(&name)?;
        let stage = self.stage();
        if dest.exists() {
            if inventory(&Local::new(dest.clone())?)? != restore.expected {
                return Err("restore-mismatch".into());
            }
            self.clear_stage()?;
        } else {
            if local.list("")?.len() >= MAX_ENTRIES {
                return Err("entry-limit".into());
            }
            let staged = Local::new(stage.clone())?;
            for entry in restore.expected.values() {
                let path = staged.resolve(&entry.path)?;
                if entry.directory {
                    fs::create_dir_all(&path).map_err(io)?;
                } else {
                    read_file(&path)?.sync_all().map_err(io)?;
                }
            }
            if inventory(&staged)? != restore.expected {
                return Err("restore-mismatch".into());
            }
            for entry in restore.expected.values().rev().filter(|row| row.directory) {
                sync_dir(&staged.resolve(&entry.path)?)?;
            }
            sync_dir(&stage)?;
            rename_exclusive(&stage, &dest)?;
        }
        sync_dir(dest.parent().ok_or("invalid-path")?)?;
        sync_dir(&self.root)?;
        self.restore = None;
        Ok(json!({"folder":name}))
    }

    pub fn call(&mut self, local: &Local, op: &str, request: &Value) -> Result<Value> {
        match op {
            "accountPrepare" | "accountPublic" => {
                // A stored link cannot authorize a replacement device key.
                let linked = match self.key(false)? {
                    Some(key) => self.load(&key)?.account_link.is_some(),
                    None => false,
                };
                let key = crate::desktop_account::device_key(
                    &self.service,
                    op == "accountPrepare" && !linked,
                )?;
                return match key {
                    Some(key) => crate::desktop_account::public_value(&key),
                    None if linked => Err("account-device-key-missing".into()),
                    None => Ok(json!({})),
                };
            }
            "vaultWnfsInventory" => {
                return Ok(json!({"entries":inventory(local)?.into_values().collect::<Vec<_>>()}))
            }
            "vaultRead" => {
                let mut file = read_file(&local.file(text(request, "path")?)?)?;
                let offset = request["offset"].as_u64().ok_or("invalid-request")?;
                let len = request["length"]
                    .as_u64()
                    .filter(|n| *n <= CHUNK as u64)
                    .ok_or("invalid-request")?;
                if offset.checked_add(len).filter(|n| *n <= LIMIT).is_none() {
                    return Err("invalid-path".into());
                }
                let mut bytes = Zeroizing::new(vec![0; len as usize]);
                file.seek(SeekFrom::Start(offset))
                    .and_then(|_| file.read_exact(&mut bytes))
                    .map_err(io)?;
                return Ok(json!({"bytes":STANDARD.encode(&bytes)}));
            }
            "vaultWnfsRestoreWrite" => return self.write(request),
            "vaultWnfsRestorePublish" => return self.publish(local, request),
            "vaultWnfsRestoreAbort" => {
                self.restoring(request)?;
                self.clear_stage()?;
                self.restore = None;
                return Ok(json!({}));
            }
            "vaultStatus"
            | "accountRead"
            | "accountSave"
            | "accountBindWnfs"
            | "vaultSession"
            | "vaultRecord"
            | "vaultWnfsRecipients"
            | "vaultWnfsRecipientSet"
            | "vaultWnfsCommit"
            | "vaultWnfsRestoreBegin" => {}
            _ => return Err("unsupported-operation".into()),
        }

        let initialize = op == "accountSave"
            || (op == "vaultSession" && request["initialize"].as_bool() == Some(true));
        let Some(key) = self.key(initialize)? else {
            return match op {
                "accountRead" => Ok(json!({})),
                "vaultSession" | "vaultStatus" => {
                    Ok(json!({"initialized":false,"synced":false,"origin":"","evicted":false}))
                }
                _ => Err("recovery-required".into()),
            };
        };
        let mut state = self.load(&key)?;
        match op {
            "accountRead" => Ok(state.account_link.unwrap_or_else(|| json!({}))),
            "accountSave" => {
                let device = crate::desktop_account::device_key(&self.service, false)?
                    .ok_or("account-device-key-missing")?;
                let link = &request["link"];
                crate::desktop_account::validate_link(&device, link)?;
                if state
                    .account_link
                    .as_ref()
                    .is_some_and(|prior| prior["accountDid"] != link["accountDid"])
                {
                    return Err("account-mismatch".into());
                }
                state.account_link = Some(link.clone());
                self.save(&key, &state)?;
                Ok(json!({}))
            }
            "accountBindWnfs" => {
                let device = crate::desktop_account::device_key(&self.service, false)?
                    .ok_or("account-device-key-missing")?;
                let link = state.account_link.as_ref().ok_or("passkey-link-required")?;
                if state.record.is_none() {
                    return Err("recovery-required".into());
                }
                crate::desktop_account::binding(&device, link, request)
            }
            "vaultSession" => Ok(
                json!({"initialized":true,"secret":STANDARD.encode(&key[..32]),
                "record":state.record,"wnfs":state.wnfs}),
            ),
            "vaultStatus" => {
                let synced = state.wnfs.as_ref().is_some_and(|head| {
                    entries(local, &head["entries"])
                        .ok()
                        .zip(inventory(local).ok())
                        .is_some_and(|(a, b)| a == b)
                });
                let capsule = state
                    .wnfs
                    .as_ref()
                    .and_then(|head| head["capsule"].as_str());
                let receipt = state
                    .root_receipt
                    .as_ref()
                    .filter(|r| capsule.is_some() && r["pack"].as_str() == capsule)
                    .map(|r| json!({"pack":r["pack"],"receiptCid":r["receiptCid"],"keyEpoch":r["keyEpoch"]}));
                Ok(
                    json!({"initialized":true,"synced":synced,"origin":state.wnfs
                    .as_ref().and_then(|head|head["origin"].as_str()).unwrap_or(""),
                    "wnfs":state.wnfs.is_some(),"evicted":false,"receipt":receipt}),
                )
            }
            "vaultRecord" => {
                let record = text(request, "record")?;
                if state.record.is_some() || record.is_empty() || record.len() > 16_000 {
                    return Err("recovery-required".into());
                }
                state.record = Some(record.to_owned());
                self.save(&key, &state)?;
                Ok(json!({}))
            }
            "vaultWnfsRecipients" => Ok(json!(state.recipients)),
            "vaultWnfsRecipientSet" => {
                if state.record.is_none() {
                    return Err("recovery-required".into());
                }
                let id = text(request, "id")?;
                let field = |s: &str, n| {
                    s.len() == n
                        && s.bytes()
                            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
                };
                if !id.strip_prefix("kv1:").is_some_and(|s| field(s, 43)) {
                    return Err("invalid-recipient".into());
                }
                if let Some(recipient) = request.get("recipient") {
                    if recipient.as_object().map(|o| o.len()) != Some(3)
                        || recipient["id"].as_str() != Some(id)
                        || !field(text(recipient, "pub")?, 43)
                        || !field(text(recipient, "pq")?, 1579)
                    {
                        return Err("invalid-recipient".into());
                    }
                    if !state.recipients.items.contains_key(id) && state.recipients.items.len() >= 8
                    {
                        return Err("recipient-limit".into());
                    }
                    state
                        .recipients
                        .items
                        .insert(id.to_owned(), recipient.clone());
                } else {
                    state.recipients.items.remove(id);
                }
                state.recipients.revision = state
                    .recipients
                    .revision
                    .checked_add(1)
                    .ok_or("state-limit")?;
                self.save(&key, &state)?;
                Ok(json!({"revision":state.recipients.revision}))
            }
            "vaultWnfsCommit" => {
                if state.record.is_none() {
                    return Err("recovery-required".into());
                }
                if request["recipientRevision"].as_u64() != Some(state.recipients.revision) {
                    return Err("recipient-grants-changed".into());
                }
                if entries(local, &request["entries"])? != inventory(local)? {
                    return Err("unsynced-data".into());
                }
                let root = text(request, "root")?;
                let capsule = text(request, "capsule")?;
                let origin = text(request, "origin")?;
                let url = tauri::Url::parse(origin).map_err(|_| "invalid-origin")?;
                if url.scheme() != "https" || url.origin().ascii_serialization() != origin {
                    return Err("invalid-origin".into());
                }
                let packs = request["packs"]
                    .as_array()
                    .filter(|p| (1..=8).contains(&p.len()))
                    .ok_or("invalid-root")?;
                let mut seen = HashSet::from([capsule]);
                if !digest_id(root)
                    || !digest_id(capsule)
                    || !packs.iter().all(|p| {
                        p.as_str()
                            .is_some_and(|id| digest_id(id) && seen.insert(id))
                    })
                {
                    return Err("invalid-root".into());
                }
                state.root_receipt = root_receipt(&request["receipt"], capsule)?;
                state.wnfs = Some(
                    json!({"schema":"awai.drive.wnfs.v1","root":root,"capsule":capsule,
                    "packs":packs,"origin":origin,"entries":request["entries"],"basis":request["basis"]}),
                );
                self.save(&key, &state)?;
                Ok(json!({}))
            }
            "vaultWnfsRestoreBegin" if request["legacy"].as_bool() != Some(true) => {
                self.begin(local, &state, request)
            }
            _ => Err("unsupported-operation".into()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Scratch(PathBuf);
    impl Scratch {
        fn new() -> Self {
            let root = std::env::temp_dir().join(format!("awai-vault-test-{}", token().unwrap()));
            private_dir(&root).unwrap();
            Self(root)
        }
    }
    impl Drop for Scratch {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    fn receipt_for(capsule: &str) -> Value {
        let key = format!("0x{}", "ad".repeat(32));
        json!({"pack":capsule,"receiptCid":format!("bafyrei{}", "a".repeat(52)),"keyId":key,"keyEpoch":1,
            "receipt":{"v":1,"receipt":{"subject":format!("0x{capsule}"),"contentDigest":format!("0x{capsule}"),
            "keyId":key,"keyEpoch":1},"sig":{"alg":"Ed25519","key":key,"sig":"x"}}})
    }

    #[test]
    fn root_receipt_must_name_the_committed_capsule_and_its_epoch() {
        let capsule = "ab".repeat(32);
        let good = receipt_for(&capsule);
        assert_eq!(root_receipt(&good, &capsule).unwrap(), Some(good.clone()));
        assert_eq!(root_receipt(&Value::Null, &capsule).unwrap(), None);
        assert!(root_receipt(&good, &"cd".repeat(32)).is_err());
        let mut epoch = good.clone();
        epoch["keyEpoch"] = json!(2);
        assert!(root_receipt(&epoch, &capsule).is_err());
        let mut cid = good.clone();
        cid["receiptCid"] = json!("bafyreiAAAA");
        assert!(root_receipt(&cid, &capsule).is_err());
        let mut extra = good.clone();
        extra["history"] = json!([]);
        assert!(root_receipt(&extra, &capsule).is_err());
        let mut large = good;
        large["receipt"]["sig"]["sig"] = json!("x".repeat(RECEIPT_LIMIT));
        assert!(root_receipt(&large, &capsule).is_err());
    }

    #[test]
    fn control_records_without_a_receipt_still_open_and_keep_one_receipt() {
        let old = serde_json::to_vec(&json!({"record":"r","wnfs":null,
            "recipients":{"revision":0,"items":{}}}))
        .unwrap();
        let mut state: Control = serde_json::from_slice(&old).unwrap();
        assert!(state.root_receipt.is_none());
        state.root_receipt = Some(receipt_for(&"ab".repeat(32)));
        let sealed = seal_control(&[3; 64], &state).unwrap();
        let reopened = open_control(&[3; 64], &sealed).unwrap();
        assert_eq!(reopened.root_receipt, state.root_receipt);
    }

    #[test]
    fn sealed_control_requires_the_original_key() {
        let state = Control {
            record: Some("test fixture".into()),
            ..Control::default()
        };
        let mut sealed = seal_control(&[1; 64], &state).unwrap();
        assert_eq!(
            open_control(&[1; 64], &sealed).unwrap().record,
            state.record
        );
        assert!(open_control(&[2; 64], &sealed).is_err());
        sealed[12] ^= 1;
        assert!(open_control(&[1; 64], &sealed).is_err());
    }

    #[test]
    #[ignore = "requires an unlocked macOS login Keychain"]
    fn keychain_unlock_survives_vault_restart() {
        struct KeychainEntry(String);
        impl Drop for KeychainEntry {
            fn drop(&mut self) {
                if let Ok(chain) = SecKeychain::default() {
                    if let Ok((_, item)) = chain.find_generic_password(&self.0, KEY_ACCOUNT) {
                        item.delete();
                    }
                }
            }
        }

        let scratch = Scratch::new();
        let bundle = format!("network.awai.drive.keychain-test.{}", token().unwrap());
        let _entry = KeychainEntry(format!("{bundle}.vault"));
        let local = Local::new(scratch.0.join("files")).unwrap();
        let path = scratch.0.join("vault");
        let mut vault = Vault::new(path.clone(), &bundle).unwrap();
        let first = vault
            .call(&local, "vaultSession", &json!({"initialize":true}))
            .unwrap();
        vault
            .call(
                &local,
                "vaultRecord",
                &json!({"record":"test owner envelope"}),
            )
            .unwrap();
        drop(vault);
        let mut vault = Vault::new(path, &bundle).unwrap();
        let session = vault.call(&local, "vaultSession", &json!({})).unwrap();
        assert!(session["secret"] == first["secret"]);
        assert_eq!(session["record"], "test owner envelope");

        // The Keychain-sealed control record keeps only the current root's receipt.
        let commit = |capsule: &str, receipt: Value| {
            json!({"root":"0".repeat(64),"capsule":capsule,"packs":["1".repeat(64)],
                "origin":"https://yataverse.com","entries":[],"recipientRevision":0,"basis":"b","receipt":receipt})
        };
        let first_root = "ab".repeat(32);
        vault
            .call(
                &local,
                "vaultWnfsCommit",
                &commit(&first_root, receipt_for(&first_root)),
            )
            .unwrap();
        drop(vault);
        let mut vault = Vault::new(scratch.0.join("vault"), &bundle).unwrap();
        let status = vault.call(&local, "vaultStatus", &json!({})).unwrap();
        assert_eq!(status["receipt"]["pack"], first_root.as_str());
        assert_eq!(status["receipt"]["keyEpoch"], 1);
        let next_root = "cd".repeat(32);
        assert!(vault
            .call(
                &local,
                "vaultWnfsCommit",
                &commit(&next_root, receipt_for(&first_root))
            )
            .is_err());
        vault
            .call(&local, "vaultWnfsCommit", &commit(&next_root, Value::Null))
            .unwrap();
        let status = vault.call(&local, "vaultStatus", &json!({})).unwrap();
        assert!(status["receipt"].is_null());
    }

    #[test]
    fn interrupted_restore_restarts_and_publishes_only_expected_bytes() {
        let scratch = Scratch::new();
        let local = Local::new(scratch.0.join("files")).unwrap();
        local.create_text("", "original.txt", "preserved").unwrap();
        let vault_path = scratch.0.join("vault");
        let share = "b".repeat(64);
        let begin = json!({"share":share,"entries":[
            {"path":"photos","directory":true},
            {"path":"photos/snapshot.txt","directory":false,"size":5,
             "sha256":format!("{:x}",Sha256::digest(b"hello"))}
        ]});
        let mut vault = Vault::new(vault_path.clone(), "test.awai.drive").unwrap();
        assert!(
            matches!(Vault::new(vault_path.clone(), "test.awai.drive"), Err(e) if e=="vault-in-use")
        );
        let operation =
            vault.begin(&local, &Control::default(), &begin).unwrap()["operation"].clone();
        let write = |op: &Value, offset, bytes: &[u8]| {
            json!({"operation":op,"path":"photos/snapshot.txt",
            "offset":offset,"bytes":STANDARD.encode(bytes)})
        };
        vault.write(&write(&operation, 0, b"he")).unwrap();
        assert_eq!(local.list("").unwrap().len(), 1);
        drop(vault);

        let mut vault = Vault::new(vault_path, "test.awai.drive").unwrap();
        let fresh = vault.begin(&local, &Control::default(), &begin).unwrap()["operation"].clone();
        assert_eq!(
            vault.write(&write(&operation, 2, b"llo")).unwrap_err(),
            "restore-operation-mismatch"
        );
        vault.write(&write(&fresh, 0, b"wrong")).unwrap();
        assert_eq!(
            vault
                .publish(&local, &json!({"operation":fresh}))
                .unwrap_err(),
            "restore-mismatch"
        );
        assert_eq!(local.list("").unwrap().len(), 1);
        vault
            .call(&local, "vaultWnfsRestoreAbort", &json!({"operation":fresh}))
            .unwrap();

        let fresh = vault.begin(&local, &Control::default(), &begin).unwrap()["operation"].clone();
        vault.write(&write(&fresh, 0, b"hello")).unwrap();
        let published = vault.publish(&local, &json!({"operation":fresh})).unwrap();
        let folder = published["folder"].as_str().unwrap();
        assert_eq!(
            fs::read(
                local
                    .file(&format!("{folder}/photos/snapshot.txt"))
                    .unwrap()
            )
            .unwrap(),
            b"hello"
        );
        assert_eq!(
            fs::read(local.file("original.txt").unwrap()).unwrap(),
            b"preserved"
        );
    }
}
