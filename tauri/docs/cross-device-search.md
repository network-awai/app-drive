# Catalog search across authorized Android devices

Drive can combine metadata from independently encrypted WNFS snapshots after an
Awai account host supplies trusted, request-scoped read grants. Each source
identifies a device, the same verified account DID, the selected WNFS forest CID,
and the owner context needed to authenticate and open the snapshot.

The account catalog adapter checks account and device scope, the configured
storage origin and each head before performing network I/O. It uses the existing
AwaiFS broker and pack client for each approved HTTPS GET. It checks the forest
CID of each opened snapshot against its grant and retains source device
information for same-path results. The catalog contains paths and directory
markers only. Search terms, type filtering and catalog data stay on the client.
A denial or failure reports an error for the entire request.

`catalogAuthorizedAccount` in `src/sync.js` is a read capability for a trusted
host integration. Its input must come from a host that verifies membership,
expiry, current heads and per-device custody. A caller-supplied DID, saved
filename, device label or raw JSON is not account authorization. The app does
not mint account sessions or write foreign device heads.

The normal cloud search continues to use the device's own saved head. Production
connecting work is tracked in
[AWAI-3099](https://linear.app/kotobalabs/issue/AWAI-3099) and depends on the
existing account binding ([AWAI-2898](https://linear.app/kotobalabs/issue/AWAI-2898)),
authenticated head discovery, and an owner-custody grant handoff.

## Signed discovery response

The recipient-based adapter in `src/sync.js` verifies a short-lived compact
Ed25519 JWS from a configured, independently authenticated host. The response
echoes the one-time challenge, account DID, Android device DID, and WNFS
recipient ID. It carries an expiry, authority policy epoch, and recipient
capsules for the authorized source devices. The trust key comes from the host
configuration. The app checks expiry before each pack read and rejects an
unverified forest basis even after successful decryption.

The host must verify membership, request identity and current revocations before
discovering the authoritative heads on each request. The adapter does not
declare a service endpoint. No production host currently supplies that
contract, so the existing device-only cloud search remains in normal builds.

### Two-device QA, 2026-11-18

Drive revision `162be23` was built as an isolated QA APK, SHA-256
`7c391ccc1949a6bbb7c274da1a85ed964e54fb7d881f4dfcc3787ce6afd1dbe1`.
The QA build injects an in-process discovery provider and uses a loopback HTTPS
pack store. Its certificate is confined to the debug app's localhost policy.
The issuer uses WebCrypto Ed25519, available in Chrome and Android WebView 137
and later for this path. [Chrome 137 support](https://developer.chrome.com/blog/chrome-137-beta#curve25519-in-web-cryptography).

- 7735HS and moto g05 authorized through the public Awai approval page with one
  virtual Passkey and distinct device keys. Both retained authorization after
  restart. The 7735HS reached the approval relay through a QA-only TLS tunnel.
- Each device synced a JPEG and an MP4 with a WNFS grant for the other device.
  Both devices found each peer file using the appropriate Drive type filter.
- After a second sync from 7735HS, moto g05 found the newer image from a fresh
  signed response. A signed reply scoped to a different account caused zero
  additional pack operations.

The fixture issuer keeps only the latest head from each QA sync in memory. The
production authorization policy, publication of current heads, and the live
endpoint are still tracked in [AWAI-2901](https://linear.app/kotobalabs/issue/AWAI-2901).
The [Figma redesign](https://www.figma.com/design/umqCqP5KSjLWh9fhCI1HaN?node-id=333-2)
covers both Android sizes, authorization, empty results and unavailable heads.
