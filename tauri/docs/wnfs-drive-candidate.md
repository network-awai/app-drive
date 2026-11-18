# AWAI-3390 bounded WNFS Drive candidate

For a dedicated test namespace use
`AWAI_DRIVE_WNFS=1 AWAI_DRIVE_SYNC_ORIGIN=https://yataverse.com npm run build:android -- --debug`
from `tauri`. Existing object-v2 snapshots remain readable. The optional writer
places an actual AwaiFS WNFS CAR and the private access reference inside the
existing hybrid owner envelope. The outer CAR stays in the signed Yataverse
Drive pack protocol and exposes only ciphertext and opaque IDs. The Android
Keystore, app-scoped inventory, atomic restore, static CSP and fresh approvals
continue to use the existing app paths. A WNFS generation creates a new forest
and fresh namespace; no distributed WNFS writer or owner sharing is configured.

The app verifies the WNFS forest and all block CIDs before creating transient
app-local Kotoba Datalog indexes. The bounded query selects file paths and sizes
for an extension. The closure retains one forest CID and refuses another.
The mounted file cache stores restored files after hash and inventory checking.
The candidate carries empty directories in authenticated owner metadata. Outer
pack admission remains 12 MiB and its enclosed plaintext stays within the
existing 8 MiB bound, including WNFS overhead.

The build verifies immutable Awai Network and Kotoba source pins. WebView
uses the existing WNFS wire/crypto implementation, Web Crypto randomness
and RSA generation, and a pinned primality test. The Node disk ports are
unavailable in the browser build. This browser accumulator provider, the use
of its RSA parameters and production owner recovery need independent review
before enabling this writer in a release.

## Measured transport and cold process recovery

With the generated Drive core built, run
`node tauri/scripts/wnfs-yataverse-verify.mjs --synthetic-live 3` from this
repository root. This sends self-generated ciphertext to the real Yataverse
endpoint. Each sample does PUT, signed receipt verification and independent
GET/readback before starting a fresh process. Only the sealed synthetic identity,
unlock and trusted locator pass through the child pipe. The process downloads a
fresh CAR, verifies the requested root, restores the fixture and re-indexes at
the same forest CID. It checks all plaintext bytes and the same two Datalog
results in both processes.

| Stage | p50, ms | n |
|---|---:|---:|
| Seal WNFS and wrap CAR | 1,207.59 | 3 |
| PUT, signed receipt and separate verified GET | 2,955.00 | 3 |
| New process verified GET | 1,108.24 | 3 |
| New process open and restore all plaintext in memory | 862.05 | 3 |
| Rebuild local Kotoba Datalog index | 0.455 | 3 |
| Query rebuilt index | 2.736 | 3 |

Apple silicon Mac, Node v26.0.0, 262,124 plaintext bytes across three files,
median outer CAR 285,239 bytes. Host CLI timing excludes browser consent and
native final publication. It qualifies the shared core and service, including
real signed receipts. No device comparison is implied by this table.

## Same-snapshot verification between Android apps

On the isolated ARM64 Android 35 emulator AWAI3390, Drive published a
283,922-byte encrypted CAR to the real Yataverse endpoint from three
synthetic files (262,124 plaintext bytes). Drive accepted the signed
receipt, did an independently approved GET, and verified readback before
accepting the root. The Drive cache was evicted and verified empty.
After process restart, a newly approved GET restored all three files
with exact expected SHA-256 values.

The Itonami Yataverse dapp was freshly installed with zero local files.
The harness supplied the synthetic owner's session to the read-only
dapp host in memory. Denial was verified with zero HTTP GETs. An
independently approved GET in the dapp returned the exact same WNFS
forest CID, all three SHA-256 values, and both extension-query rows.
The dapp independently qualified native cache eviction/restart/restore
with its own synthetic one-file snapshot and fresh custody.

| Stage | Drive, ms | Itonami dapp, ms |
|---|---:|---:|
| Seal and wrap | 795.4 | — |
| PUT, signed receipt, verified GET | 5,893.9 | — |
| Empty-cache verified GET | 2,317.2 | 1,787.4 |
| Decrypt, verify WNFS and recover file bytes | 988.4 | 935.9 |
| Rebuild local Datalog projection | 0.8 | 0.6 |
| Query for txt files | 4.2 | 3.2 |
| Publish files to the native cache | 166.7 | — |

One trial per app for the same snapshot. Network times include dialog
interaction and are end-to-end app timings. The same-snapshot dapp
inspection restored bytes in memory. Session provisioning was synthetic
and in-memory. A product owner invitation, production principal
onboarding, physical Awai OS measurement and release are outside this
qualification. See the [dapp candidate](https://github.com/network-awai/app-itonami-yataverse/pull/32).
