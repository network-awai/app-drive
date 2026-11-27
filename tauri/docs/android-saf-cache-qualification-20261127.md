# AWAI-4123 Android SAF cache qualification — 2026-11-27

Source under test: `awai-4123-android-saf` branch head (see PR for the exact
revision). Parent: AWAI-4118; scope fixed by the AWAI-4123 owner comment of
2026-11-26. Design context: [drive-host-ipc.md](drive-host-ipc.md) §7,
[portable-usb-custody.md](portable-usb-custody.md),
[scalable-sync-design.md](scalable-sync-design.md) §4.8–4.9.

## Scope delivered

- `cachePick` launches `ACTION_OPEN_DOCUMENT_TREE`; on result the plugin
  requires read+write grant flags, calls
  `takePersistableUriPermission(READ|WRITE|PERSISTABLE)` and stores only the
  tree URI in a `noBackupFilesDir` AtomicFile record
  (`awai-drive-saf.v1.json`, schema `awai.drive.saf.v1`, ≤4 KiB). Deny/cancel
  or a non-content result rejects `cache-cancelled` before any state is
  written; a missing persistable grant rejects `persist-unsupported`.
- `DriveSaf` (pure Java, no `android.*`) holds the cache policy:
  content-addressed blobs named by lowercase SHA-256 hex under the picked
  tree's `cache/` subtree only, `.pending-<uuid>.part` staging, entry/byte
  ceilings (4096 entries / 256 MiB / 65531-byte blobs), SHA-256 verified on
  write and on every readback, and a rebuild decision that replaces a
  proven-corrupt blob instead of serving it.
- `DriveSafDocuments` is the thin `DocumentsContract` adapter confined to
  `<picked>/cache/` by document id; it refuses provider auto-renames so
  name == digest always holds, and maps `SecurityException` to
  `permission-lost` and null cursors/streams to `cache-absent` /
  `storage-unavailable`.
- `DriveSafSession` revalidates the persisted permission on every op:
  absent record → `cache-absent`, revoked grant → `permission-lost`,
  unreachable tree → status `unavailable`. `cacheVerify` digests every blob
  and deletes only proven-corrupt entries; `cacheClear` removes only
  digest-named blobs and staging files — unexpected foreign files are
  reported and preserved.
- Ops `cacheStatus/cachePut/cacheGet/cacheVerify/cacheClear/cacheRelease`
  ride the existing `drive_call` envelope (128 KiB cap; blob payloads are
  base64 inside it) with typed codes forwarded through `lib.rs`.
  `src/saf-cache.js` is the minimal JS binding (`window.AwaiDriveSaf`);
  no UI was added.
- `DriveDocuments` (read-only provider) is unchanged; no write path was
  added, no volume is opened, no manage-all-files or broad storage
  permission is requested, and AWAI-4120/4121 boundaries are untouched.

## Observed results

| Check | Result and coverage |
| --- | --- |
| DriveSaf policy (JVM) | 98 checks pass in `test/DriveSafTest.java` against a file-backed `Tree`: name/digest gates, tree-level path confinement (traversal/absolute/nested rejected), write→readback SHA-256 match, idempotent `present`, corrupt/truncated/oversize blob detection with delete-on-proof, directory-squat rejection, `inspect(verify)` reporting without deleting, `rebuilt` replacement, staging cleanup on mid-write failure, transient read failure preserving data, `permission-lost`/`cache-absent` typed errors leaving state untouched, media-cycle rebuild from re-fetch source, entry/byte ceilings, `clear` preserving foreign files |
| DriveFiles regression (JVM) | 27 checks pass unchanged |
| JS binding + node suite | `test/saf-cache.test.mjs` 3 tests pass (base64 roundtrip, op→payload mapping, typed-error propagation); full `npm test` 44 pass / 4 pre-existing skips; `npm run build` passes |
| Android debug APK | `tauri android build --apk --target aarch64 --debug` passes; Kotlin+Java compile clean, development-signed APK produced and `apksigner verify` passes |

Reproduce the JVM checks from `tauri/`:

```sh
javac -d .agent-output/drive-java \
  src-tauri/gen/android/app/src/main/java/network/awai/drive/DriveSaf.java \
  test/DriveSafTest.java
java -cp .agent-output/drive-java DriveSafTest .agent-output/NEW-SAF-TEST
```

## Explicitly unverified

- Real DocumentsUI tree picker flow, `takePersistableUriPermission`
  behaviour across providers, and grant revocation on a physical device —
  the plugin path is compile-checked only.
- `DriveSafDocuments` against real providers: document-id stability,
  auto-rename edge cases beyond the display-name check, cursor quirks,
  provider-specific size reporting (`COLUMN_SIZE` may be absent → -1,
  handled but unexercised).
- Real USB/removable-media eject timing; the `cache-absent` mapping is
  verified only through the fake tree.
- Persistable-permission loss across reboot, "forget this device" flows,
  and SAF-on-adoptable-storage combinations (out of scope per owner
  decision).
- `DocumentsProvider` read-only audit is by inspection (no diff); it was
  not re-tested on device.
- No production endpoint, no AWAI-4120 IPC, no AWAI-4121 auth was exercised.

## Failure-semantics mapping

| Acceptance scenario | Behaviour |
| --- | --- |
| User deny/cancel | `cache-cancelled`, no record written, nothing touched |
| Read-only grant | `persist-unsupported`, nothing stored |
| App restart, grant intact | record re-read, `getPersistedUriPermissions` re-checked per op |
| Permission revoked / media ejected | `permission-lost` / status `unavailable`; cache treated as absent, internal data untouched |
| Corrupt/truncated/oversize blob | `cache-corrupt`; proven-bad blob deleted on read, reported by `cacheVerify`, replaced on next `cachePut` (`rebuilt`) |
| Unexpected files in `cache/` | counted in `unexpected`, never served, never deleted by `cacheClear` |
| Network loss | no claim change: status never reports synced/offline-available; only digest-verified bytes are returned |

## Artifacts

| File | Bytes | SHA256 |
| --- | ---: | --- |
| app-universal-debug.apk (aarch64 target, debug) | 21027041 | 3669855e2ff280188c983b4f0354b38ee90671b06cbee5c582e3f66da8dc1b8f |

Development signer certificate SHA-256:
`1c73b625bebc3e897b27d74e32813c12c34628288f037eed59ec6120d6f65061`.
The APK stays in the worktree under
`src-tauri/gen/android/app/build/outputs/apk/universal/debug/` and is not
committed; it exceeds the Store 20 MiB ceiling like prior debug builds and
is not a release artifact.
