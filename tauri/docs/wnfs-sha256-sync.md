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
