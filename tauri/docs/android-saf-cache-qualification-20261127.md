# AWAI-4123 Android SAF cache qualification — 2026-11-27 (remediated 2026-11-28, Round 2 P1 fixes)

Source under test: `com-junkawasaki/awai-4123-android-saf` branch head (see PR #36 for the exact
revision). Parent: AWAI-4118; scope fixed by the AWAI-4123 owner comment of
2026-11-26 and `AWAI-SPEC-REG-SAF-B1-v0.1` §2 (`SAF-OWN-01`–`04`). Design
context: [drive-host-ipc.md](drive-host-ipc.md) §7,
[portable-usb-custody.md](portable-usb-custody.md),
[scalable-sync-design.md](scalable-sync-design.md) §4.8–4.9.

## Scope delivered & Round 2 P1 remediations

- **Frontend bundling & clean-build entry-load verification (P1 #1):**
  - `scripts/build.mjs` copies `src/saf-cache.js` into `dist/saf-cache.js` and
    records its SHA-256 in `dist/release.json` (`files['saf-cache.js']`), so
    `dist/app.js`'s static `./saf-cache.js` import resolves in clean Tauri builds.
  - `test/saf-cache.test.mjs` snapshots/restores `src-tauri/tauri.conf.json`,
    deletes `dist/`, runs `node scripts/build.mjs`, verifies SHA-256 integrity
    for every entry in `dist/release.json`, walks every static relative ESM
    import reachable from `dist/app.js` (confirming presence in both `dist/`
    and `release.json.files`), imports `dist/saf-cache.js` directly, and loads
    `dist/app.js` via Node's real ESM loader in an isolated subprocess with
    minimal DOM/window stubs.
- **`cachePick` & persistable tree record:**
  `cachePick` launches `ACTION_OPEN_DOCUMENT_TREE`; on result the plugin
  requires read+write grant flags, calls
  `takePersistableUriPermission(READ|WRITE|PERSISTABLE)`, adopts or verifies
  `<picked>/cache/` ownership (`SAF-OWN-02`), and stores the tree URI, `appId`,
  and UUIDv4 `instanceId` in a `noBackupFilesDir` AtomicFile record
  (`awai-drive-saf.v1.json`, schema `awai.drive.saf.v1`, ≤4 KiB). Deny/cancel
  or a non-content result rejects `cache-cancelled` before any state is
  written; a missing persistable grant rejects `persist-unsupported`; a
  non-empty foreign directory rejects `foreign-directory-refused` with zero
  writes/deletes and releases the rejected grant while leaving any prior
  selection intact. On failed local record save, `DriveSafSession.remember`
  rolls back strictly via the newly created `DriveSaf.Item` marker handle.
- **`SAF-OWN-01` & Android SAF protocol boundary (P1 #2):**
  - `DriveSaf.Item` carries pinned `id` and `verifiedRegularFile` (`regularFile()`
    requires `verifiedRegularFile && !directory && !symlink`). `DriveSaf.Tree`
    binds `read(Item)`, `confirmRegularFile(Item)`, and `delete(Item)` strictly
    to inspected `Item` handles returned by `children()` / `create()` — display
    names are never re-searched on read or delete.
  - `DriveSafSafAdapter` (pure Java, zero `android.*` imports) implements
    `DriveSaf.Tree` over `DriveSafSafAdapter.Provider`:
    - **Pinned `cache` doc ID:** once bound in `locate()` or `ensure()`,
      `verifyPinnedCache()` re-queries `queryDoc(pinnedCacheId)` and verifies
      `pinnedCacheId` remains the unique valid `"cache"` directory under `root`
      (`mime == MIME_TYPE_DIR`, non-null `flags`, `(flags & FLAG_VIRTUAL_DOCUMENT) == 0`).
      If the pinned `cache` doc disappears, changes, moves, or gains a sibling
      `"cache"` entry, it fails closed (`cache-absent` /
      `foreign-directory-refused`) and **never** rebinds to another `cache` doc ID.
    - **Unambiguous root scan:** initial bind fails closed with
      `foreign-directory-refused` if root has >1 `"cache"` entry or any
      non-directory / virtual / null-flags `"cache"` entry.
    - **Strict regular-file metadata check:** requires non-null/non-empty `id`,
      `name`, and `mime`; rejects `MIME_TYPE_DIR`, any `vnd.android.document/*`,
      symlink MIMEs (`inode/symlink`, `application/x-symlink`, `inode/*`
      case-insensitive), null `flags`, and `FLAG_VIRTUAL_DOCUMENT`.
    - **Pinned child identity & pre-delete verification:** `read`,
      `confirmRegularFile`, and `delete` verify the pinned `cache` doc ID,
      match unique `d.id.equals(item.id)` under `pinnedCacheId`, and confirm
      `item.name.equals(d.name)` + regular-file metadata. `delete(Item)`
      additionally requires `item.regularFile()`,
      `(d.flags & FLAG_SUPPORTS_DELETE) != 0`, and
      `provider.confirmRegularFile(d.id, d.size) == true` before calling
      `provider.deleteDoc(d.id)` — otherwise fails closed with
      `foreign-directory-refused` and zero `deleteDoc` calls.
  - `DriveSafDocuments` is a thin Android `ContentResolver` / `DocumentsContract`
    bridge implementing `DriveSafSafAdapter.Provider` and delegating all
    `DriveSaf.Tree` methods to `DriveSafSafAdapter`. It queries 5 columns
    including `DocumentsContract.Document.COLUMN_FLAGS` and implements
    `confirmRegularFile(docId, expectedSize)` via
    `resolver.openFileDescriptor(doc(docId), "r")` + `android.system.Os.fstat`
    requiring `OsConstants.S_ISREG(st.st_mode)` and
    `(expectedSize < 0 || st.st_size == expectedSize)` (propagating
    `SecurityException` as `permission-lost` and returning `false` on any other
    error or non-regular FD).
- **`SAF-OWN-02` (ownership marker + `instanceId` dual binding):**
  `DriveSafRecord` persists `{"schema":"awai.drive.saf.v1","tree":"<uri>","appId":"network.awai.drive","instanceId":"<uuidv4>"}`
  in `noBackupFilesDir/awai-drive-saf.v1.json` and encodes/validates
  `<picked>/cache/.awai-cache-owner.v1.json` as
  `{"schema":"awai.saf-cache-owner.v1","appId":"network.awai.drive","cacheVersion":1,"instanceId":"<uuidv4>"}`.
  `verifyOwner` returns the verified pinned `Item owner` after confirming
  `owner.regularFile() && t.confirmRegularFile(owner)` and reading via `t.read(owner)`.
  Empty `cache/` adoption returns the newly created marker `Item` (or `null` when
  verifying an already-owned directory). Missing marker, malformed JSON, wrong
  `schema`/`appId`/`cacheVersion`, symlink/directory marker, mismatched
  `instanceId`, or legacy/missing local `instanceId` all fail closed with
  `foreign-directory-refused` and execute zero writes and zero deletes across
  all cache operations.
- **`SAF-OWN-03` (double-gated deletion & read-only projection filtering):**
  - `DriveSaf.clearDetailed` / `cacheClear` runs ONLY after `SAF-OWN-02`
    ownership verification succeeds, checks `t.confirmRegularFile(it)` for each
    candidate regular file matching 64-char lowercase hex digest or
    `.pending-<uuid>.part` (preserving any unconfirmed entry in
    `preservedForeign`), and deletes strictly by pinned `Item` handle.
    `.awai-cache-owner.v1.json` is never deleted by `cacheClear` (only by
    explicit `cacheRelease`, which reuses the verified pinned `Item owner` from
    the same scan and confirms `confirmRegularFile(owner)` before deleting).
  - `DriveDocuments` (read-only `DocumentsProvider`) and `DriveFiles` exclude
    `.awai-cache-owner.v1.json` and `.pending-*` staging files from child
    listings and reject direct document resolution/open.
- **`SAF-OWN-04` (`RESET-04` alignment):** Ciphertext-only `cacheClear` touches
  no keys and only deletes re-fetchable encrypted blobs, so it remains exempt
  from OS re-auth per `AWAI-4150` §12(e) while never omitting `SAF-OWN-02`
  ownership verification.
- **Measured SHA-256 mismatch vs oversize vs transient read `IOException` separation (P1 #3):**
  - `MAX_BLOB_BYTES` (65,531 bytes) bounded read limit remains intact.
  - `DriveSaf` separates `Oversize` (provider metadata `item.size > MAX_BLOB_BYTES`
    OR stream exceeding `MAX_BLOB_BYTES` before EOF — full SHA-256 not measured)
    from measured `DIGEST_MISMATCH` (entire blob read `<= MAX_BLOB_BYTES` AND
    `SHA-256(bytes) != item.name`) and transient read `IOException`s
    (`storage-unavailable`, mid-stream read errors).
  - **`cacheVerify` (`DriveSaf.verify`)**: deletes ONLY entries with measured
    `DIGEST_MISMATCH` (after `confirmRegularFile(it)`). Oversize entries are
    **never deleted** and are reported in `oversize` / `oversizeNames` with
    `verified = blobs - corrupt - unreadable - oversize` and `clean = false`.
    Transient read failures are **never deleted** and are reported in
    `unreadable` / `unreadableNames` with `clean = false`.
  - **`cacheGet` and `cachePut`**: on `Oversize`, throw `cache-corrupt`
    **without deleting** the entry; on transient read `IOException`, propagate
    without deleting; only measured `DIGEST_MISMATCH` triggers deletion/rebuild.
  - `DriveSafSession.verify()` exposes `oversize` and `oversizeNames` in its
    JSON response alongside `corrupt`/`corruptNames` and
    `unreadable`/`unreadableNames`.
- Ops `cacheStatus/cachePut/cacheGet/cacheVerify/cacheClear/cacheRelease`
  ride the existing `drive_call` envelope (128 KiB cap; blob payloads are
  base64 inside it) with typed codes forwarded through `DrivePlugin.kt` and
  `lib.rs`. `src/saf-cache.js` (bundled to `dist/saf-cache.js`) is the minimal
  JS binding (`window.AwaiDriveSaf`) and exports `SAF_CACHE_ERRORS`; no UI was
  added.

## Observed results

Toolchains used in local verification (2026-11-28):
- JDK: `javac 21.0.10` / `OpenJDK 21.0.10` (`/opt/homebrew/Cellar/openjdk@21/21.0.10/bin`)
- Node: `v26.3.0`
- Android SDK stub jar (compile-check only, no Gradle/APK build): `/Users/node/Library/Android/sdk/platforms/android-37.0/android.jar`

| Check | Exact command | Toolchain | Exit code | Pass / Fail / Skip & coverage |
| --- | --- | --- | ---: | --- |
| `DriveSafRecordTest` (JVM) | `"$JDK/java" -cp "$TMPDIR_JAVA" DriveSafRecordTest` | OpenJDK 21.0.10 | `0` | **127 pass / 0 fail / 0 skip** (`test/DriveSafRecordTest.java`): record (`tree` + `appId` + UUIDv4 `instanceId`) and owner-marker encode→decode roundtrips, legacy record fail-closed flag, strict rejection of malformed/duplicate-key/wrong-field inputs |
| `DriveSafTest` (JVM) | `"$JDK/java" -cp "$TMPDIR_JAVA" DriveSafTest "$TMPDIR_JAVA/SAF-TEST"` | OpenJDK 21.0.10 | `0` | **558 pass / 0 fail / 0 skip** (`test/DriveSafTest.java`): `SAF-OWN-01..04` pinned `Item` handles, `locate()` vs `ensure()`, ownership adoption/refusal with zero mutations, double-gated `clearDetailed` & `release`, oversize non-deletion (provider metadata header alone + stream exceeding `MAX_BLOB_BYTES` across `verify`/`inspect`/`get`/`put`), mixed `verify` deleting ONLY measured SHA-256 mismatch while preserving intact/oversize/unreadable/owner, transient read `IOException` preservation, rebuild, staging cleanup, typed errors, entry/byte ceilings |
| `DriveSafDocumentsTest` (JVM) | `"$JDK/java" -cp "$TMPDIR_JAVA" DriveSafDocumentsTest` | OpenJDK 21.0.10 | `0` | **145 pass / 0 fail / 0 skip** (`test/DriveSafDocumentsTest.java`): `DriveSafSafAdapter` + `DriveSaf` boundary tests — pinned `cache` doc ID swapped/moved between `verifyOwner` and `delete` (zero `deleteDoc`, never rebinds to `cache-2`), duplicate/non-dir/virtual/null-flags `"cache"` at root (zero mutations), child doc ID swapped between `children()` and `delete(item)` (zero `deleteDoc`), child renamed/moved/MIME-changed/virtual/lost-`FLAG_SUPPORTS_DELETE`/failed-`confirmRegularFile` (`S_ISREG == false` and size mismatch) between `children()` and `delete(item)` (zero `deleteDoc`), duplicate display names inside `cache/` (regular file deleted by doc ID, sibling dir untouched), symlink MIMEs (`inode/symlink`, `application/x-symlink`, `INODE/FIFO`) / `vnd.android.document/*` / null & empty MIME / null flags / `FLAG_VIRTUAL_DOCUMENT` / `confirmRegularFile == false` preserved as `preservedForeign` & `unexpected` (zero `deleteDoc`), oversize non-deletion through adapter, end-to-end happy path (`adopt` → `put` → `get` → `verify` → `clear` → `release`) |
| `DriveFilesTest` (JVM) | `"$JDK/java" -cp "$TMPDIR_JAVA" DriveFilesTest "$TMPDIR_JAVA/FILES-TEST"` | OpenJDK 21.0.10 | `0` | **30 pass / 0 fail / 0 skip** (`test/DriveFilesTest.java`): local store invariants + `.awai-cache-owner.v1.json` rejected by `name()`/`resolve()` and hidden from `list()` |
| Android Java compile-check (`javac`) | `"$JDK/javac" -cp "$ANDROID_JAR" -d "$TMPDIR_ANDROID" ...` (all 7 Android Java files) | `javac 21.0.10` + `android-37.0/android.jar` | `0` | **7 files compiled / 0 errors**: `DriveSafRecord.java`, `DriveSaf.java`, `DriveSafSafAdapter.java`, `DriveSafDocuments.java`, `DriveSafSession.java`, `DriveFiles.java`, `DriveDocuments.java` |
| `test/saf-cache.test.mjs` (Node + clean `dist/` build) | `node --test test/saf-cache.test.mjs` | Node `v26.3.0` | `0` | **4 pass / 0 fail / 0 skip**: base64 roundtrip, op→payload mapping (including `oversize`/`oversizeNames`/`unreadable`/`preservedForeign`/`ownerPreserved`), typed-error propagation, clean `dist/` build (`node scripts/build.mjs`) verifying `dist/saf-cache.js` + `dist/release.json` SHA-256s + static ESM import graph from `dist/app.js` + direct `dist/saf-cache.js` import + isolated Node ESM load of `dist/app.js` (restoring `src-tauri/tauri.conf.json`) |
| Full `test/*.test.mjs` (Node) | `node --test test/*.test.mjs` | Node `v26.3.0` | `0` | **45 pass / 0 fail / 4 pre-existing skips** |

Reproduce the JVM, Android compile-check, and Node checks from `tauri/`:

```sh
JDK=/opt/homebrew/Cellar/openjdk@21/21.0.10/bin
ANDROID_JAR=/Users/node/Library/Android/sdk/platforms/android-37.0/android.jar
TMPDIR_JAVA=$(mktemp -d)
TMPDIR_ANDROID=$(mktemp -d)
"$JDK/javac" -d "$TMPDIR_JAVA" \
  src-tauri/gen/android/app/src/main/java/network/awai/drive/DriveSafRecord.java \
  src-tauri/gen/android/app/src/main/java/network/awai/drive/DriveSaf.java \
  src-tauri/gen/android/app/src/main/java/network/awai/drive/DriveSafSafAdapter.java \
  src-tauri/gen/android/app/src/main/java/network/awai/drive/DriveFiles.java \
  test/DriveSafRecordTest.java test/DriveSafTest.java test/DriveSafDocumentsTest.java test/DriveFilesTest.java
"$JDK/java" -cp "$TMPDIR_JAVA" DriveSafRecordTest
"$JDK/java" -cp "$TMPDIR_JAVA" DriveSafTest "$TMPDIR_JAVA/SAF-TEST"
"$JDK/java" -cp "$TMPDIR_JAVA" DriveSafDocumentsTest
"$JDK/java" -cp "$TMPDIR_JAVA" DriveFilesTest "$TMPDIR_JAVA/FILES-TEST"
"$JDK/javac" -cp "$ANDROID_JAR" -d "$TMPDIR_ANDROID" \
  src-tauri/gen/android/app/src/main/java/network/awai/drive/DriveSafRecord.java \
  src-tauri/gen/android/app/src/main/java/network/awai/drive/DriveSaf.java \
  src-tauri/gen/android/app/src/main/java/network/awai/drive/DriveSafSafAdapter.java \
  src-tauri/gen/android/app/src/main/java/network/awai/drive/DriveSafDocuments.java \
  src-tauri/gen/android/app/src/main/java/network/awai/drive/DriveSafSession.java \
  src-tauri/gen/android/app/src/main/java/network/awai/drive/DriveFiles.java \
  src-tauri/gen/android/app/src/main/java/network/awai/drive/DriveDocuments.java
rm -rf "$TMPDIR_JAVA" "$TMPDIR_ANDROID"
node --test test/saf-cache.test.mjs
node --test test/*.test.mjs
```

## Explicitly unverified

- Real DocumentsUI tree picker flow, `takePersistableUriPermission`
  behaviour across providers, and grant revocation on a physical device —
  the Android bridge (`DriveSafDocuments`, `DriveSafSession`, `DrivePlugin.kt`)
  is compile-checked only (no Gradle/APK or `cargo build` was re-run in this
  remediation pass); the SAF protocol state machine (`DriveSafSafAdapter`) is
  JVM-verified via `DriveSafDocumentsTest`.
- `DriveSafDocuments` against real providers: `Os.fstat(ParcelFileDescriptor)`
  behaviour on virtual/pipe FDs returned by third-party providers, MIME/extension
  handling for `.awai-cache-owner.v1.json` (`application/json`), auto-rename
  edge cases beyond the display-name check, cursor quirks, provider-specific
  `COLUMN_SIZE` / `COLUMN_FLAGS` omissions (handled fail-closed in
  `DriveSafSafAdapter`, unexercised on hardware).
- Real USB/removable-media eject timing; `cache-absent` and
  `storage-unavailable` mappings are verified only through JVM doubles.
- Persistable-permission loss across reboot, "forget this device" flows,
  and SAF-on-adoptable-storage combinations (out of scope per owner
  decision).
- `DriveDocuments` (`DocumentsProvider`) read-only runtime queries on Android
  were not re-tested on device (backing `DriveFiles` filter and
  `DriveSaf.isUserDocument` are JVM-tested; `DriveDocuments.java` is `javac`
  compile-checked).
- No production endpoint, no AWAI-4120 IPC, no AWAI-4121 auth was exercised.

## Failure-semantics mapping

| Acceptance scenario | Behaviour |
| --- | --- |
| User deny/cancel | `cache-cancelled`, no record written, nothing touched |
| Read-only grant | `persist-unsupported`, nothing stored |
| Non-empty foreign `cache/` (missing/malformed/mismatched marker or legacy/missing local `instanceId`) | `foreign-directory-refused`, zero writes and zero deletes; `cachePick` keeps prior selection intact and releases the rejected grant; `cacheStatus` reports `state:"foreign-directory-refused"` |
| Duplicate/non-dir/virtual `"cache"` at root, swapped pinned `cache` doc ID, swapped/renamed/moved child doc ID, missing `FLAG_SUPPORTS_DELETE`, or failed `Os.fstat` `S_ISREG` / size check | fails closed (`cache-absent` / `foreign-directory-refused`) with zero `deleteDoc` calls; pinned `cache` doc ID never rebinds |
| App restart, grant & marker intact | record re-read, `getPersistedUriPermissions` and `.awai-cache-owner.v1.json` `instanceId` re-checked per op |
| Permission revoked / media ejected | `permission-lost` / status `unavailable`; cache treated as absent, internal data untouched |
| Transient read `IOException` (`storage-unavailable`, mid-stream read error) | `cachePut` and `cacheGet` propagate `storage-unavailable` without deleting existing entries; `cacheVerify` preserves entries and reports them in `unreadable`/`unreadableNames` with `corrupt:0, removed:0, clean:false` |
| Oversize blob (provider metadata `size > MAX_BLOB_BYTES` or stream `> MAX_BLOB_BYTES` before EOF; full SHA-256 not measured) | **never deleted**; `cacheVerify` / `inspect(verify=true)` reports in `oversize`/`oversizeNames` with `corrupt:0, removed:0, clean:false`; `cacheGet` and `cachePut` throw `cache-corrupt` without deleting |
| Measured SHA-256 mismatch (entire blob read `<= MAX_BLOB_BYTES` and `SHA-256(bytes) != name`, e.g. bitflip or truncation) | `cache-corrupt`; proven-bad regular file deleted by pinned `Item` handle on `cacheGet` and `cacheVerify` (only after `SAF-OWN-02` + `confirmRegularFile` pass), replaced on next `cachePut` (`rebuilt`) |
| Unexpected foreign files/subdirs/symlinks/virtual/unconfirmed entries in owned `cache/` | counted in `unexpected`, never served, never deleted by `cacheClear` (`preservedForeign` reported; `.awai-cache-owner.v1.json` preserved with `ownerPreserved:true`) |
| Network loss | no claim change: status never reports synced/offline-available; only digest-verified bytes are returned |

## Device QA (requires a physical device — pending)

Rebuild the Android debug APK (`tauri android build --apk --target aarch64 --debug`)
and install (`adb install -r`) before running on device. Each step lists the
manual action and the expected result; any deviation is a defect against this
slice, not a fixture gap.

1. **DocumentsUI tree picker & empty `cache/` adoption (`SAF-OWN-01`/`02`).**
   Trigger `cachePick` (via `window.AwaiDriveSaf.pick()`). Select an empty
   folder on internal storage (and on removable media if present); grant access.
   Expected: `cacheStatus` reports `picked:true, state:"ready", clean:true`; a
   `cache/` directory containing `.awai-cache-owner.v1.json` (`schema:
   "awai.saf-cache-owner.v1"`, `appId: "network.awai.drive"`, `cacheVersion: 1`,
   UUIDv4 `instanceId`) appears inside the picked tree and nothing else is
   created at the picked root; `noBackupFilesDir/awai-drive-saf.v1.json` stores
   the matching tree URI, `appId`, and `instanceId`.
2. **Foreign non-empty `cache/` refusal (`SAF-OWN-02`).** Pre-populate a folder
   with `cache/<64-hex>` and `cache/.pending-<uuid>.part` (no owner marker, or
   with a mismatched `instanceId`), then pick it via `cachePick`.
   Expected: rejects with `foreign-directory-refused`; zero files created or
   deleted in that folder; prior valid selection (if any) remains usable.
3. **Persistable grant & ownership persistence.** Force-stop and relaunch the
   app.
   Expected: `cacheStatus` still `ready` — grant and `instanceId` match; ops
   succeed without re-picking.
4. **Cancel/deny.** Trigger `cachePick`, press back or deny the prompt.
   Expected: `cache-cancelled` error; `cacheStatus` unchanged (previous
   record intact, or `absent` if none); nothing written to any tree.
5. **Real provider write path & `cacheClear` preservation (`SAF-OWN-03`/`04`).**
   `cachePut` a small blob, place an unrelated file `notes.txt` inside `cache/`
   via a file manager, then run `cacheClear` (no OS re-auth prompt expected).
   Expected: blob removed, `notes.txt` AND `.awai-cache-owner.v1.json`
   preserved (`ownerPreserved:true, preservedForeign:1`).
6. **Media eject.** With the tree on removable media, `cachePut`, then
   physically remove the media.
   Expected: ops return `cache-absent`/status `unavailable` (typed, never a
   crash or data-loss error); internal files unaffected; reinsert → verify
   marker & blobs or re-pick cleanly.
7. **Permission revocation.** Revoke the grant via system Settings →
   Apps → Drive → Permissions (or forget the USB device authorization).
   Expected: `permission-lost` typed error; `cacheStatus`
   `state:"permission-lost"`; internal store untouched; re-pick recovers.
8. **Reboot persistence loss.** Reboot the device; check whether the
   persistable grant survived (expected on most providers; if not,
   `permission-lost` and re-pick is the designed recovery — record which
   providers behave which way).
9. **Corrupt vs oversize blob on device.** Edit a cached blob (keeping size
   `<= 65531` bytes) on the media side then `cacheGet` / `cacheVerify`.
   Expected: `cache-corrupt`, proven-bad blob removed, `.awai-cache-owner.v1.json`
   preserved; `cachePut` rebuilds (`rebuilt`). Separately place a `> 65531`-byte
   64-hex file in `cache/` and run `cacheVerify`. Expected: reported in
   `oversize`/`oversizeNames` with `clean:false` and **preserved** (not deleted).
10. **Read-only provider exclusion (`SAF-OWN-03`).** Open Android Files app
    under "Awai Drive · この端末" (`DriveDocuments`).
    Expected: neither `.awai-cache-owner.v1.json` nor `.pending-*` files appear.

## Artifacts

| File | Bytes | SHA256 |
| --- | ---: | --- |
| app-universal-debug.apk (aarch64 target, debug — built at `7d4ba63`, pending rebuild) | 21027041 | 3669855e2ff280188c983b4f0354b38ee90671b06cbee5c582e3f66da8dc1b8f |

Development signer certificate SHA-256:
`1c73b625bebc3e897b27d74e32813c12c34628288f037eed59ec6120d6f65061`.
The APK stays in the worktree under
`src-tauri/gen/android/app/build/outputs/apk/universal/debug/` and is not
committed; it exceeds the Store 20 MiB ceiling like prior debug builds and
is not a release artifact. It was built at `7d4ba63`; the record-codec
follow-up (`4da78295`), `SAF-OWN-01`–`04` + transient-`IOException`
remediation, and these Round 2 P1 fixes compile clean via `javac` against
`android-37.0/android.jar` but have not been re-packaged into an APK — rebuild
together with the device QA pass.
