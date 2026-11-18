# Awai Drive for Android and macOS

Follow the [Awai and Itonami app storage contract](https://github.com/network-awai/awai-os/blob/main/docs/otehon/app-storage.md).

- Keep the existing Drive owner-store format readable. Use the owning AwaiFS
  WNFS CLJC library for the versioned private-tree migration described in
  [the scalable sync design](docs/scalable-sync-design.md). Verify copied
  snapshots by reopening them before selecting a new root.
- For future relationship queries, use a client-side Kotobase projection
  bound to a verified AwaiFS snapshot. Keep file paths, plaintext, derived
  private indexes and device key material within the app's authorized context.
- Use the pinned Yataverse Drive pack library for encrypted CAR upload,
  independent readback and restore. The app supplies exactly the existing
  canonical consent broker, capability, origin, and pinned receipt verifier.
  Verify `vendor/platform/PINS.json` before building.
- Preserve Android Keystore and app sandbox boundaries. Test denied operations,
  corrupt ciphertext, bad storage signatures, and an empty-cache restore before
  qualifying a change to the sync path.
