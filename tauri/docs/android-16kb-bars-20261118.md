# Android: 16 KB alignment and system bars — 2026-11-18

Tracking: [AWAI-3444](https://linear.app/kotobalabs/issue/AWAI-3444), the
template-wide procedure [AWAI-3393](https://linear.app/kotobalabs/issue/AWAI-3393)
(same change as the awai-os apps, network-awai/awai-os#264–#276).

## Change

- `src-tauri/build.rs`: for an Android target, link with
  `-Wl,-z,max-page-size=16384`, so the native library's LOAD segments are 16 KB
  aligned. A build-script link argument applies even though the Tauri CLI sets
  `CARGO_TARGET_<triple>_RUSTFLAGS`.
- `MainActivity.kt`: the bottom padding also covers the on-screen keyboard
  (`max(systemBars, ime)`); the decor colour follows the page's
  `--awai-background` (#ffffff / #14110f) with dark icons in light mode and
  light icons in dark mode, also on a live theme switch (`onConfigurationChanged`;
  `uiMode` is in `configChanges`); no navigation contrast scrim on API 29+.

## Checks

Clean debug APK (APK outputs removed first, `CARGO_BUILD_JOBS=4 nice -n 10`,
under the shared AWAI-3393 build lock): 21,895,266 bytes, sha256
`1ab83e5139e42d0e5984c08b21e6174c29d7ebce45559aff4a32de1b05f0a9c0`.

| Check | Result |
| -- | -- |
| ELF | `llvm-readelf -lW lib/arm64-v8a/libawai_drive_lib.so`: 4 LOAD segments, all `Align 0x4000` |
| Zip | `zipalign -c -P 16 -v 4`: `libawai_drive_lib.so (OK)`, `Verification successful` |
| VM light (`android-16kb-bars-20261118/light.png`) | Content between the status and navigation bars; dark icons on white |
| VM dark, live switch (`android-16kb-bars-20261118/dark.png`) | Same layout; light icons on #14110F |
| Cold start in dark; switch back to light | Pixel-identical to the live-switch dark screen and to the first light screen |

VM: disposable AVD, Android 15 `AE3A.240806.019` (`sdk_phone64_arm64`),
`-wipe-data`, deleted afterwards. No `FATAL EXCEPTION` in logcat.

Not checked: a physical device; the keyboard inset (no text field was focused);
sync, storage or Keystore paths (unchanged by this change).
