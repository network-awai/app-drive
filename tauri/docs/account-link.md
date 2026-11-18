# Awai account authorization on Android

The opt-in Android build (`AWAI_DRIVE_AWAI_LINK=1`) shows an approval link and
confirmation code. Open the link on `awai.network` in the browser where the
existing Awai Passkey was registered. After checking the code, use the page's
Passkey button and approve the relay delivery. Drive asks separately before
receiving the encrypted reply.

The published [Awai approval page](https://awai.network/link/) uses the
existing browser WebAuthn origin. It uses the Drive-specific challenge from
[Awai OS #277](https://github.com/network-awai/awai-os/pull/277) and the
confirmation UI fix from [Awai OS #281](https://github.com/network-awai/awai-os/pull/281).
The Drive flow accepts Passkeys already registered in that browser.

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

## Automated QA on two Android devices

The QA runner uses one virtual WebAuthn Passkey in a fresh Chrome profile and
the public approval page and relay. It checks refusal before registration,
linking two distinct Android Keystore device keys to the same QA account,
the browser's confirmation UI, and persistence after restarting both apps.

From `tauri/`, prepare a new output directory inside the task worktree. In the
generated checkout, set the usual Android SDK/NDK paths along with the WNFS
checkout and build options:

```sh
node scripts/prepare-passkey-qa.mjs ../.agent-output/passkey-qa
cd ../.agent-output/passkey-qa/tauri
npm ci
AWAI_DRIVE_AWAI_LINK=1 AWAI_DRIVE_WNFS_SYNC=1 \
  AWAI_DRIVE_SYNC_ORIGIN=https://yataverse.com npm run build
npm run tauri -- android build --debug --apk --target aarch64 x86_64
```

Install the resulting universal APK on the two intended targets using their
ADB serials. It is labeled **Awai Drive Passkey QA** and uses
`network.awai.drive.passkey3099`. The runner starts and inspects only that app.
Set `AWAI_QA_PLAYWRIGHT` to an installed Playwright `index.mjs` and set
`AWAI_QA_LINK_CORE` to the generated checkout's `dist/account-link-core.js`.

```sh
node scripts/qa-passkey-android.mjs ADB_SERIAL_A ADB_SERIAL_B
```

Both QA apps must start unlinked. When rerunning, use
`--reset-isolated-qa` before the serials to clear the disposable QA apps'
data, including their test accounts and test files. For a device without a
direct route to the public relay, set `AWAI_QA_PROXY_SERIAL` to that serial.
The generated debug activity and runner use a temporary ADB reverse port and
a localhost TLS tunnel restricted to `relay.awai.network:443`. The tunnel
keeps WebView certificate verification in place. For staging a portal fix,
`AWAI_QA_PORTAL_ASSETS` can supply a local compiled JS bundle. Leave that
variable unset to test the deployed bundle.

This covers QA authentication. The production account, authoritative
latest-head discovery, and cross-device synchronization require separate
acceptance.
