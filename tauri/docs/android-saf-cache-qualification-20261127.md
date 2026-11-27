# AWAI-4123 Android SAF cache qualification — 2026-11-27 (remediated 2026-11-28)

Source under test: `awai-4123-android-saf` branch head (see PR #36 for the exact
revision). Parent: AWAI-4118; scope fixed by the AWAI-4123 owner comment of
2026-11-26 and `AWAI-SPEC-REG-SAF-B1-v0.1` §2 (`SAF-OWN-01`–`04`). Design
context: [drive-host-ipc.md](drive-host-ipc.md) §7,
[portable-usb-custody.md](portable-usb-custody.md),
[scalable-sync-design.md](scalable-sync-design.md) §4.8–4.9.

## Scope delivered

- `cachePick` launches `ACTION_OPEN_DOCUMENT_TREE`; on result the plugin
  requires read+write grant flags, calls
  `takePersistableUriPermission(READ|WRITE|PERSISTABLE)`, adopts or verifies
  `<picked>/cache/` ownership (`SAF-OWN-02`), and stores the tree URI, `appId`,
  and UUIDv4 `instanceId` in a `noBackupFilesDir` AtomicFile record
  (`awai-drive-saf.v1.json`, schema `awai.drive.saf.v1`, ≤4 KiB). Deny/cancel
  or a non-content result rejects `cache-cancelled` before any state is
  written; a missing persistable grant rejects `persist-unsupported`; a
  non-empty foreign directory rejects `foreign-directory-refused` with zero
  writes/deletes and releases the rejected grant while leaving any prior
  selection intact.
- **`SAF-OWN-01` (dedicated subdirectory confinement & non-mutating `locate()`):**
  `DriveSafDocuments` confines all cache I/O strictly to `<picked>/cache/` by
  document id. `DriveSaf.Tree.locate()` locates an existing `<picked>/cache/`
  directory without creating it (`cache-absent` if missing,
  `foreign-directory-refused` if `<picked>/cache` is a non-directory); only
  `DriveSaf.adopt(...)` (`cachePick` / `DriveSafSession.remember`) invokes
  `ensure()` to create `<picked>/cache/`. Non-adopt operations (`cacheStatus`,
  `cachePut`, `cacheGet`, `cacheVerify`, `cacheClear`, `cacheRelease`) never
  auto-create `cache/` when absent, and `DriveSafDocuments` filters out
  directory documents on `read()`/`delete()` (`fileId` vs `hasChild`) so
  duplicate display names cannot address a directory.
- **`SAF-OWN-02` (ownership marker + `instanceId` dual binding):**
  `DriveSafRecord` persists `{"schema":"awai.drive.saf.v1","tree":"<uri>","appId":"network.awai.drive","instanceId":"<uuidv4>"}`
  in `noBackupFilesDir/awai-drive-saf.v1.json` and encodes/validates
  `<picked>/cache/.awai-cache-owner.v1.json` as
  `{"schema":"awai.saf-cache-owner.v1","appId":"network.awai.drive","cacheVersion":1,"instanceId":"<uuidv4>"}`.
  - Empty `cache/` adoption (`DriveSaf.adopt`) generates a UUIDv4 `instanceId`,
    writes `.awai-cache-owner.v1.json`, reads it back and verifies all four
    fields before persisting locally and allowing cache operations.
  - Non-empty `cache/` accepts ONLY when a single regular
    `.awai-cache-owner.v1.json` exists, parses strictly, and matches `schema`,
    `appId`, `cacheVersion` (integer `1`), and the local persisted `instanceId`.
    Missing marker, malformed JSON, wrong `schema`/`appId`/`cacheVersion`,
    symlink/directory marker, mismatched `instanceId`, or legacy/missing local
    `instanceId` all fail closed with `foreign-directory-refused` and execute
    zero writes and zero deletes across `cachePut`, `cacheGet`, `cacheVerify`,
    `cacheClear`, and `cacheRelease`.
- **`SAF-OWN-03` (double-gated deletion & read-only projection filtering):**
  - `DriveSaf.clearDetailed` / `cacheClear` runs ONLY after `SAF-OWN-02`
    ownership verification succeeds, deleting ONLY regular files (non-directory,
    non-symlink) matching 64-char lowercase hex digest or `.pending-<uuid>.part`.
    `.awai-cache-owner.v1.json` is never deleted by `cacheClear` (only by
    explicit `cacheRelease` after ownership verification), and foreign files,
    subdirectories, and symlinks are preserved and reported via
    `preservedForeign` / `ownerPreserved`.
  - `DriveSaf.verify` / `cacheVerify` runs ONLY after `SAF-OWN-02` ownership
    verification succeeds and deletes ONLY confirmed-corrupt blobs (SHA-256
    digest mismatch or size > `MAX_BLOB_BYTES`).
  - `DriveDocuments` (read-only `DocumentsProvider`) and `DriveFiles` exclude
    `.awai-cache-owner.v1.json` and `.pending-*` staging files from child
    listings and reject direct document resolution/open.
- **`SAF-OWN-04` (`RESET-04` alignment):** Ciphertext-only `cacheClear` touches
  no keys and only deletes re-fetchable encrypted blobs, so it remains exempt
  from OS re-auth per `AWAI-4150` §12(e) while never omitting `SAF-OWN-02`
  ownership verification.
- **Transient read `IOException` vs confirmed corruption separation:**
  `DriveSaf.intact` distinguishes confirmed corruption (oversize >
  `MAX_BLOB_BYTES` or SHA-256 mismatch) from transient read `IOException`s
  (e.g. `storage-unavailable`, mid-stream I/O error). On transient read failure:
  - `cachePut` propagates the typed I/O error (`storage-unavailable`) without
    calling `delete` — existing entries are preserved.
  - `cacheVerify` and `inspect(verify=true)` preserve the existing entry with
    zero delete calls, keep `corrupt=0`/`removed=0`, and report the entry in
    `unreadable` / `unreadableNames` with `clean=false`.
  - `cacheGet` propagates the typed I/O error without deleting the entry.
- Ops `cacheStatus/cachePut/cacheGet/cacheVerify/cacheClear/cacheRelease`
  ride the existing `drive_call` envelope (128 KiB cap; blob payloads are
  base64 inside it) with typed codes (including `foreign-directory-refused` and
  `storage-unavailable`) forwarded through `DrivePlugin.kt` and `lib.rs`.
  `src/saf-cache.js` is the minimal JS binding (`window.AwaiDriveSaf`) and
  exports `SAF_CACHE_ERRORS`; no UI was added.

## Observed results

Toolchains used in local verification (2026-11-28):
- JDK: `javac 21.0.10` / `OpenJDK 21.0.10` (`/opt/homebrew/Cellar/openjdk@21/21.0.10/bin`)
- Node: `v26.3.0`
- Android SDK stub jar (compile-check only, no Gradle/APK build): `android-37.0/android.jar`

| Check | Exit code | Result and coverage |
| --- | ---: | --- |
| `DriveSafRecordTest` (JVM) | `0` | **127 pass / 0 fail / 0 skip** (`test/DriveSafRecordTest.java`): record (`tree` + `appId` + UUIDv4 `instanceId`) and owner-marker (`.awai-cache-owner.v1.json`) encode→decode roundtrips, UUIDv4 validation, legacy `{schema,tree}` decoding with `hasOwnership()==false`, strict `invalid-saf-record` / `foreign-directory-refused` rejection of malformed/duplicate-key/wrong-schema/wrong-appId/wrong-cacheVersion/oversize/non-`content://` inputs |
| `DriveSafTest` (JVM) | `0` | **503 pass / 0 fail / 0 skip** (`test/DriveSafTest.java`): `SAF-OWN-01` `<picked>/cache/` confinement (zero files at `<picked>` root, `locate()` vs `ensure()` non-mutating `cache-absent` when `cache/` is absent or externally removed, `foreign-directory-refused` with zero mutations when `<picked>/cache` is a regular file), `SAF-OWN-02` empty `cache/` adoption + read-back verification, non-empty foreign `cache/` refusal (64-hex + `.pending-*.part` without marker, malformed marker, wrong `schema`/`appId`/`cacheVersion`, mismatched `instanceId`, legacy/missing local `instanceId`, dir/symlink marker) with zero creates/deletes, `SAF-OWN-03` double-gated `clearDetailed` preserving `.awai-cache-owner.v1.json`, foreign files, subdirs, and symlinks, explicit `release` teardown, transient `IOException` (`storage-unavailable` + mid-stream) preservation on BOTH `put` and `verify` (plus `inspect` and `get`) with zero deletes, mixed `verify` deleting only confirmed-corrupt blobs, confirmed corrupt/truncated/oversize detection + `rebuilt` replacement, staging cleanup on mid-write failure, `permission-lost`/`cache-absent` typed errors leaving sibling internal store byte-identical, entry/byte ceilings, `isUserDocument` hiding marker & staging |
| `DriveFilesTest` (JVM) | `0` | **30 pass / 0 fail / 0 skip** (`test/DriveFilesTest.java`): existing local store invariants plus `.awai-cache-owner.v1.json` rejected by `name()`/`resolve()` and hidden from `list()` (backing read-only `DriveDocuments`) |
| Android Java compile-check (`javac`) | `0` | `DriveSafRecord.java`, `DriveSaf.java`, `DriveSafDocuments.java`, `DriveSafSession.java`, `DriveFiles.java`, `DriveDocuments.java` compile clean against `android-37.0/android.jar` (0 errors) |
| `test/saf-cache.test.mjs` (Node) | `0` | **3 pass / 0 fail / 0 skip**: base64 roundtrip, op→payload mapping (including `unreadable`/`preservedForeign`/`ownerPreserved`), typed-error propagation including `foreign-directory-refused` and `storage-unavailable` |
| Full `test/*.test.mjs` (Node) | `0` | **44 pass / 0 fail / 4 pre-existing skips** |

Reproduce the JVM and Node checks from `tauri/`:

```sh
JDK=/opt/homebrew/Cellar/openjdk@21/21.0.10/bin
"$JDK/javac" -d .agent-output/drive-java \
  src-tauri/gen/android/app/src/main/java/network/awai/drive/DriveSafRecord.java \
  src-tauri/gen/android/app/src/main/java/network/awai/drive/DriveSaf.java \
  src-tauri/gen/android/app/src/main/java/network/awai/drive/DriveFiles.java \
  test/DriveSafRecordTest.java test/DriveSafTest.java test/DriveFilesTest.java
"$JDK/java" -cp .agent-output/drive-java DriveSafRecordTest
"$JDK/java" -cp .agent-output/drive-java DriveSafTest .agent-output/SAF-TEST
"$JDK/java" -cp .agent-output/drive-java DriveFilesTest .agent-output/FILES-TEST
node --test test/saf-cache.test.mjs
node --test test/*.test.mjs
```

## Explicitly unverified

- Real DocumentsUI tree picker flow, `takePersistableUriPermission`
  behaviour across providers, and grant revocation on a physical device —
  the plugin path is compile-checked only (no Gradle/APK or `cargo build` was
  re-run in this remediation pass).
- `DriveSafDocuments` against real providers: document-id stability,
  MIME/extension handling for `.awai-cache-owner.v1.json` (`application/json`),
  auto-rename edge cases beyond the display-name check, cursor quirks,
  provider-specific size reporting (`COLUMN_SIZE` may be absent → -1,
  handled but unexercised).
- Real USB/removable-media eject timing; `cache-absent` and
  `storage-unavailable` mappings are verified only through the JVM fake tree.
- Persistable-permission loss across reboot, "forget this device" flows,
  and SAF-on-adoptable-storage combinations (out of scope per owner
  decision).
- `DriveDocuments` (`DocumentsProvider`) read-only runtime queries on Android
  were not re-tested on device (backing `DriveFiles` filter and `DriveSaf.isUserDocument`
  are JVM-tested; `DriveDocuments.java` is `javac` compile-checked).
- No production endpoint, no AWAI-4120 IPC, no AWAI-4121 auth was exercised.

## Failure-semantics mapping

| Acceptance scenario | Behaviour |
| --- | --- |
| User deny/cancel | `cache-cancelled`, no record written, nothing touched |
| Read-only grant | `persist-unsupported`, nothing stored |
| Non-empty foreign `cache/` (missing/malformed/mismatched marker or legacy/missing local `instanceId`) | `foreign-directory-refused`, zero writes and zero deletes; `cachePick` keeps prior selection intact and releases the rejected grant; `cacheStatus` reports `state:"foreign-directory-refused"` |
| App restart, grant & marker intact | record re-read, `getPersistedUriPermissions` and `.awai-cache-owner.v1.json` `instanceId` re-checked per op |
| Permission revoked / media ejected | `permission-lost` / status `unavailable`; cache treated as absent, internal data untouched |
| Transient read `IOException` (`storage-unavailable`, mid-stream read error) | `cachePut` and `cacheGet` propagate `storage-unavailable` without deleting existing entries; `cacheVerify` preserves entries and reports them in `unreadable`/`unreadableNames` with `corrupt:0, removed:0, clean:false` |
| Confirmed corrupt/truncated/oversize blob (SHA-256 mismatch or size > `MAX_BLOB_BYTES`) | `cache-corrupt`; proven-bad blob deleted on `cacheGet` and `cacheVerify` (only after `SAF-OWN-02` passes), replaced on next `cachePut` (`rebuilt`) |
| Unexpected foreign files/subdirs/symlinks in owned `cache/` | counted in `unexpected`, never served, never deleted by `cacheClear` (`preservedForeign` reported; `.awai-cache-owner.v1.json` preserved with `ownerPreserved:true`) |
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
9. **Corrupt blob on device.** Edit a cached blob on the media side
   (e.g. via a file manager) then `cacheGet` / `cacheVerify`.
   Expected: `cache-corrupt`, proven-bad blob removed, `.awai-cache-owner.v1.json`
   preserved; `cachePut` rebuilds (`rebuilt`).
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
follow-up (`4da78295`) and this `SAF-OWN-01`–`04` + transient-`IOException`
remediation compile clean via `javac` against `android-37.0/android.jar` but
have not been re-packaged into an APK — rebuild together with the device QA pass.
