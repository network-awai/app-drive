# Awai Drive — Android and macOS Tauri app

Tauri/runtime-wry **3.0.0-alpha.6**, tauri-build **3.0.0-alpha.4**,
Android SDK37 / NDK27.0.12077973. Shared policy and encrypted snapshot orchestration
are CLJC/CLJS; Rust/Kotlin/Java expose bounded Android platform operations.
This is not Native selfhost or a signed AOSP image.

Drive supports app-private files, folders, bounded local document import,
read-only Android Files integration and explicit viewer handoff. With a
build-pinned HTTPS storage endpoint, it can encrypt using the existing Kotoba
hybrid envelope, sync an AwaiFS owner-store/CAR snapshot, retain files offline,
and restore an emptied cache using device-protected keys and a trusted root.

**Legacy encrypted snapshots admit 8 MiB total and 1,000 entries.** Ordinary local
import admits 1 GiB, but larger files stay unsynced and cannot be evicted. This
is a bounded snapshot adapter, not WNFS private-tree or streamed large-file sync.
It creates a device-local recipient, not an Awai account/Passkey enrollment.
Uninstalling/clearing app data or losing device keys can make remote snapshots
unreadable. Keep original data; independent owner recovery is not implemented.
See [encryption, custody and transport boundaries](docs/encrypted-sync.md).
The [WNFS migration](docs/scalable-sync-design.md) adds a copy-only Android
profile with a WNFS PrivateForest and multiple CARv1 packs. It preserves the
legacy snapshot head and owner key. Enable it for a validated HTTPS storage
origin using `AWAI_DRIVE_WNFS_SYNC=1` and `AWAI_DRIVE_SYNC_ORIGIN`.
Set `AWAI_DRIVE_WNFS_SOURCE` to the absolute path of an authorized
network-awai-apex Git checkout containing the pinned revision. The build
checks each upstream file hash. WNFS source and compiled WNFS bundles stay
outside Git. Each
pack stays within the existing 12 MiB transport cap. This browser profile
admits 32 MiB of plaintext and 1,000 paths per snapshot. Its worker holds
bounded arrays while the streaming large-snapshot path is developed.

A WNFS endpoint must admit and verify both BLAKE3 WNFS blocks and SHA-256
owner-capsule blocks. The existing Yataverse Drive endpoint's CID validator
only admits SHA-256, so keep the default legacy profile with that service
until its WNFS admission has been qualified. WNFS restore creates a verified
copy in a new folder. Local eviction is disabled in this profile because
immediate remote readback does not prove durable retention.

## Search stored files

On Android with encrypted storage configured, choose **Cloud** under **Search
in** to search all paths in the latest verified snapshot available to the
device's owner key. Both legacy and WNFS snapshots work with their configured
profile. The app reads the encrypted packs using the existing approval dialog
for each GET, checks the hashes and opens the metadata on-device. Query text,
file names and the derived list stay in app memory. WNFS currently downloads
the encrypted data packs to traverse the stored tree. Search does not publish,
restore or modify a snapshot. The existing restore action is used to open a
cloud-only file.

Local and cloud search filter by name and by images, videos, audio, documents
or folders. The app uses filename extensions for file categories. The separate
Drive v3 metadata service uses stored MIME types for `mimeType contains`,
`mimeType =` and `mimeType !=` predicates in `files.list` queries.

## Safety and integration

- Upload completion requires an explicit receipt plus separately approved full
  readback. Native admission rechecks local paths, lengths and SHA256.
- Eviction refuses unsynced changes and unpublished interrupted writes. Restore
  verifies pack/root/CIDs/owner/AEAD and every file before staged publication.
- Android Keystore protects the unlock and canonical key record in
  `noBackupFilesDir`; plaintext file cache and private control are separate.
- The exact pinned canonical browser broker requires fresh each-operation
  consent for every HTTPS PUT/GET. Denial sends no request. No native HTTP
  bypass, app-origin API, redirect or silent network fallback is introduced.
- Default builds have no storage endpoint/remote operations and hide sync
  controls. An explicit build origin derives the descriptor and static CSP.
- Android Files/pickers expose `network.awai.drive.documents` as read-only under
  `MANAGE_DOCUMENTS` and URI grants. FileProvider grants only read access to the
  local tier. External providers cannot write through either projection.
- Existing Home discovers the installed launcher through PackageManager/Awai
  Shell; no second OS setup or account authentication bypass is added.

Figma [initial states](https://www.figma.com/design/umqCqP5KSjLWh9fhCI1HaN?node-id=257-1470)
and [sync/recovery states](https://www.figma.com/design/umqCqP5KSjLWh9fhCI1HaN?node-id=259-1506)
reuse Android v2 buttons, color variables, cloud icon and Noto Sans JP. Existing
Core/Apps/System composition is preserved. Shared canonical consent UI remains
its owning implementation; app labels use stable JP/EN keys.

## Build and check

Run from this directory with Node.js, Clojure CLI, JDK 21 and the Android SDK/NDK installed. The pinned dependencies in [vendor/platform/PINS.json](vendor/platform/PINS.json) travel with this checkout.

```sh
npm ci
npm run build
npm test
npm run tauri -- android build --apk --target aarch64 --debug
npm run tauri -- android build --apk --target x86_64 --debug
```

To build for an admitted storage endpoint:

```sh
AWAI_DRIVE_SYNC_ORIGIN=https://storage.example npm run build
```

The endpoint must implement the bounded immutable CAR protocol described in
[encrypted-sync.md](docs/encrypted-sync.md). This does not deploy that service.
Do not silently substitute the loopback VM fixture as a production endpoint.

`https://yataverse.com` implements this protocol (Backblaze B2 storage, no
authentication, size/hash/CAR limits). Its signed storage receipt is verified
against the build-pinned key set in `receipt-trust.json`; an upload without a
valid receipt is not marked synced. Build with
`AWAI_DRIVE_SYNC_ORIGIN=https://yataverse.com` to use it; see
[the yataverse.com section](docs/encrypted-sync.md#yataversecom) and the
[VM qualification](docs/encrypted-sync-yataverse-qualification-20261118.md).

Pure Java adapter checks, from the repository root:

```sh
javac -d .agent-output/drive-java src-tauri/gen/android/app/src/main/java/network/awai/drive/DriveFiles.java test/DriveFilesTest.java
java -cp .agent-output/drive-java DriveFilesTest .agent-output/NEW-DRIVE-TEST
```

Use a fresh test root. Preserve ABI-specific APKs and source/digest/signer
bindings. APKs are development-signed; Store size and production signing gates
remain separate. GitHub Actions is disabled and was not enabled by this task.

[Initial local-tier qualification](docs/android-qualification-20261117.md) is
historical. [Encrypted VM qualification](docs/encrypted-sync-qualification-20261117.md)
records the new evidence and handoff. Physical6600H installation is explicitly
held by the owner; no physical disks, Nix/EFI, accounts or keys were modified.

## macOS build (AWAI-3256)

The same app builds for macOS with Tauri. File operations use a bounded Rust adapter
(`src-tauri/src/desktop.rs`) with the Android `DriveFiles` rules: validated, NFC-normalised
names; paths confined to one app-private root
(`~/Library/Application Support/network.awai.drive/drive-local-v1`); symlinks refused; at most
1000 entries per folder; notes up to 64 KiB; saves published by an atomic no-clobber link.
`open` hands a file to the default viewer through `/usr/bin/open`. File import and encrypted
sync are not available on macOS yet: `pick` returns `import-unavailable`, `vaultStatus` reports
not configured, and the other vault operations return `android-adapter-required`. This app is
separate from the existing Swift AwaiDrive.app (`com.kotoba.awaivol.drive`).

```sh
npm ci && npm run build
(cd src-tauri && cargo test --lib)
npx tauri build --no-bundle   # binary: src-tauri/target/release/awai-drive
```
