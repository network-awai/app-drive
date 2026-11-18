# Awai account authorization on Android

The opt-in Android build (`AWAI_DRIVE_AWAI_LINK=1`) shows an approval link and
confirmation code. Open the link on `awai.network` in the browser where the
existing Awai Passkey was registered. After checking the code, use the page's
Passkey button and approve the relay delivery. Drive asks separately before
receiving the encrypted reply.

This requires the static Awai approval page built from
[Awai OS #277](https://github.com/network-awai/awai-os/pull/277). It uses the
existing browser WebAuthn origin and excludes registration and wallet fallback
for the Drive purpose. An older page cannot produce the newly scoped challenge.
Keep the option disabled until the published approval page supports it.

Drive uses an Android Keystore P-256 device key and a short-lived in-memory
ECDH request. It checks the scoped proof, user verification, RP, origin, device
and expiry using the pinned canonical Awai OS implementation. It stores the
signed proof privately and revalidates it after restart. Account changes are
refused. This proves account and device linkage. Authenticated head discovery,
recipient-key authorization and production account search still need their
own service and acceptance. No device unlock key is handed off.

`catalogVerifiedRecipientHeads` is the read adapter for the future trusted
head provider. It matches the locally verified account DID and decrypts only
grants for the reader's own WNFS recipient key. It expects the provider to
authenticate membership and establish the latest storage head for each device.

The vendored `account-link-core.js` is the ES module bundle of the
`otehon/shell/src/identity/link.js` source in the pinned Awai OS revision. From
that revision run `npm ci` in `otehon/shell` and bundle with its pinned esbuild:
`esbuild src/identity/link.js --bundle --platform=browser --format=esm
--target=es2020 --minify --legal-comments=inline`. Compare the digest with
`vendor/platform/PINS.json`.
