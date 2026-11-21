# Signing and notarizing the macOS build (AWAI-3791)

`npm run build:macos` ([scripts/build-macos.mjs](../scripts/build-macos.mjs)) builds the web
artifact, runs `tauri build --bundles app,dmg`, signs with a Developer ID Application
identity, optionally notarizes and staples, and prints the paths, SHA-256 digests and the
Gatekeeper assessment. No signing value is stored in this repository; everything comes
from the environment and the login keychain.

| Variable | Meaning |
|---|---|
| `APPLE_SIGNING_IDENTITY` | Developer ID Application identity, by name or SHA-1, as listed by `security find-identity -v -p codesigning`. Required. |
| `AWAI_NOTARY_PROFILE` | Name of a notarytool keychain profile. Unset: signed but not notarized. |
| `AWAI_DRIVE_SKIP_WEB_BUILD=1` | Reuse an existing `dist/` instead of running `npm run build`. |
| `AWAI_DRIVE_UNSIGNED=1` | Ad-hoc bundle for local layout checks. Never distribute it. |

The script removes `APPLE_ID`, `APPLE_PASSWORD`, `APPLE_TEAM_ID` and `APPLE_API_*` from
the Tauri child process, so notarization takes only the `AWAI_NOTARY_PROFILE` path.
Sync build variables (`AWAI_DRIVE_SYNC_ORIGIN`, `AWAI_DRIVE_WNFS_SYNC`, ...) pass through
to `npm run build` unchanged.

## One-time setup

1. Install the Developer ID Application certificate and private key in the login keychain.
   Check it with `security find-identity -v -p codesigning`.
2. For notarization, store App Store Connect credentials once as a keychain profile:

   ```sh
   xcrun notarytool store-credentials awai-notary \
     --key /path/to/AuthKey_XXXXXXXXXX.p8 --key-id XXXXXXXXXX --issuer <issuer-uuid>
   ```

   (or `--apple-id ... --team-id ...` with an app-specific password). The secret then
   lives only in the keychain; pass just the profile name.

## Build

```sh
export PATH="$HOME/.rustup/toolchains/1.95.0-aarch64-apple-darwin/bin:$PATH"  # if cargo is not on PATH
npm ci
APPLE_SIGNING_IDENTITY="Developer ID Application: <Team> (<TEAMID>)" \
AWAI_NOTARY_PROFILE=awai-notary \
npm run build:macos
```

Output (aarch64, the only installed Rust target on the build Mac):

- `src-tauri/target/release/bundle/macos/Awai Drive.app`
- `src-tauri/target/release/bundle/dmg/Awai Drive_0.1.0_aarch64.dmg`

## Bundle identity

- Bundle `Awai Drive.app`, ID `network.awai.drive`, version 0.1.0, minimum macOS 13.0.
  The older Swift `/Applications/AwaiDrive.app` (`com.kotoba.awaivol.drive`) has a
  different name and ID, so both can be installed side by side and keep separate
  storage and Keychain scope.
- Hardened runtime is on. [Entitlements.plist](../src-tauri/Entitlements.plist) is
  intentionally empty: the app is not sandboxed, so network access needs no
  entitlement, and WKWebView JIT runs in WebKit's process. The vault's login-Keychain
  items (`network.awai.drive.vault`) are in the legacy file keychain, whose ACL trusts
  the app's designated requirement (bundle ID plus Developer ID team). Keep the same
  team and bundle ID across releases, or existing items prompt or become unreadable.
  `keychain-access-groups` would need a provisioning profile and is not used.
- [Info.plist](../src-tauri/Info.plist) is merged into the Info.plist Tauri generates.

## Verify

```sh
codesign --verify --deep --strict --verbose=2 "src-tauri/target/release/bundle/macos/Awai Drive.app"
spctl -a -vv -t exec "src-tauri/target/release/bundle/macos/Awai Drive.app"
spctl -a -vv -t open --context context:primary-signature "src-tauri/target/release/bundle/dmg/Awai Drive_0.1.0_aarch64.dmg"
xcrun stapler validate "src-tauri/target/release/bundle/dmg/Awai Drive_0.1.0_aarch64.dmg"
```

Without notarization `spctl` reports `rejected (Unnotarized Developer ID)`; this is
expected and the build must not be distributed in that state.
