//! The file selection is created locally by AppKit. No path is accepted from IPC.
use objc2::MainThreadMarker;
use objc2_app_kit::{NSModalResponseOK, NSOpenPanel};
use tauri::Manager;

use std::ffi::{CStr, OsStr};
use std::os::unix::ffi::OsStrExt;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};

static PICKER_ACTIVE: AtomicBool = AtomicBool::new(false);

/// RAII reset of `PICKER_ACTIVE`: the flag is released when `pick()` returns,
/// even if the main-thread closure is never run (window already torn down) or
/// panics, so a later import can never be stuck on `file-busy` forever.
struct PickerGuard;

impl Drop for PickerGuard {
    fn drop(&mut self) {
        PICKER_ACTIVE.store(false, Ordering::Release);
    }
}

fn choose() -> Result<PathBuf, String> {
    let mtm = MainThreadMarker::new().ok_or("import-unavailable")?;
    let panel = NSOpenPanel::openPanel(mtm);
    panel.setCanChooseFiles(true);
    panel.setCanChooseDirectories(false);
    panel.setAllowsMultipleSelection(false);
    panel.setResolvesAliases(false);
    if panel.runModal() != NSModalResponseOK {
        return Err("import-cancelled".into());
    }
    let url = panel.URLs().firstObject().ok_or("import-cancelled")?;
    if !url.isFileURL() {
        return Err("local-provider-required".into());
    }
    let bytes = unsafe { CStr::from_ptr(url.fileSystemRepresentation().as_ptr()) };
    Ok(PathBuf::from(OsStr::from_bytes(bytes.to_bytes())))
}

pub async fn pick(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    if PICKER_ACTIVE.swap(true, Ordering::AcqRel) {
        return Err("file-busy".into());
    }
    // Holds the flag until this function returns; `PickerGuard` restores it on
    // every return path below, including when the main-thread closure never runs.
    let _guard = PickerGuard;
    let (send, receive) = std::sync::mpsc::channel();
    if app
        .run_on_main_thread(move || {
            let selected = choose();
            let _ = send.send(selected);
        })
        .is_err()
    {
        return Err("import-unavailable".into());
    }
    tauri::async_runtime::spawn_blocking(move || {
        receive
            .recv()
            .map_err(|_| "import-unavailable".to_string())?
    })
    .await
    .map_err(|_| "import-unavailable".to_string())?
}
