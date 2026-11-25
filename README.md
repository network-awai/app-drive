# Awai Drive

- [Android and macOS Tauri app](tauri/README.md)
- [WNFS sync design](tauri/docs/scalable-sync-design.md)
- [Drive host IPC design (AWAI-4118)](tauri/docs/drive-host-ipc.md)
- [Compatibility service and appview](AGENTS.md)

The Tauri source moved from Awai OS PR #250's merge revision
[`540674c`](https://github.com/network-awai/awai-os/commit/540674c835b67f5c2f4209627c3f589063558e7f).
Builds use pinned snapshots of Awai UI, the Shell host and the canonical Kotoba
browser runtime. Awai OS and the Store can consume separately signed app
artifacts using the existing Android package identifier `network.awai.drive`.
