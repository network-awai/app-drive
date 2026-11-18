# AWAI-3399 WNFS SHA-256 storage profile

Drive writes WNFS PrivateForest raw and DAG-CBOR CIDv1 blocks with SHA2-256.
The encrypted owner capsule and CAR pack identifiers also use SHA-256. The
existing `/awai-drive/v1/packs/<sha256>` service parses and verifies these CARs
and returns its existing signed readback receipts. This requires no alternate
storage route. Existing WNFS BLAKE3 blocks are authenticated according to their
original CIDs on read. Existing owner-store records retain their own read path.
The WNFS ratchet, key derivation, name accumulator and AEAD stay on the pinned
WNFS profile.

The snapshot locator remains the encrypted owner capsule plus ordered data
packs. Reopening the capsule yields the trusted forest CID. The shared worker
builds a local Kotoba Datalog projection on that CID. A different basis
fails the host inspection call. Inspection requires a caller already holding
the authorized owner session and fresh approval for the remote bytes. It
returns query rows and does not write the other app's vault.

Use `AWAI_DRIVE_WNFS_SYNC=1` with the existing origin and source pin. For an
isolated debug APK set `AWAI_WNFS_QA=1`. The separately installed package has
the `.awai3399` suffix, a separate Android Keystore namespace, and its own
document provider authority. Provision only synthetic data in this package.

## Physical Android verification, 2026-11-18 JST

Moto g05 (ARM64, API 37, LineageOS 24, Android WebView 156), isolated
debug package IDs. Both apps packaged identical WNFS/Datalog bundle
SHA-256 `93df9af2f314a4a60e8a6674e34e505a97c45c04b1b96a8d955296295b7888ad`.
Yataverse live policy reported the Backblaze B2 backend and a 12 MiB
per-pack limit. App signing and signed pack receipts were verified.

| Plaintext / files | Data packs (plus capsule) | Seal (s) | PUT + receipt + verified GET (s) | Cold GET (s) | Open + query (s) | Native publication (s) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 262,124 B / 3 | 1 | 1.13 | 9.04 | 3.03 | 1.11 | 0.70 |
| 4 MiB / 4 | 1 | 3.00 | 12.50 | 4.05 | 2.68 | 7.19 |
| 13 MiB / 2 | 2 | 4.85 | 17.28 | 5.66 | 4.72 | 22.67 |

Each row is one successful trial. Network times include fresh approval UI.
The 13 MiB write initially returned a generic unavailable error; a later
fresh approval succeeded. The original Android app files and credentials
were not used. The test removed only the provisioned synthetic files from
the QA app sandbox, confirmed no files, stopped and restarted the QA app,
and compared exact restored SHA-256 for every file.

The independently installed Itonami app, starting with zero files and
a synthetic owner session provided only in memory, inspected the same
small forest. Denial emitted zero GETs. Approval issued two GETs, and the
forest CID and both query rows matched Drive exactly. Datalog query time
was 11.9 ms in restarted Drive and 11.7 ms in Itonami. Product sharing
grants and revocation are separate qualification.
