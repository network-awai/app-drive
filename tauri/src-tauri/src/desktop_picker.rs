//! The file selection is created locally by AppKit. No path is accepted from IPC.
use objc2::MainThreadMarker;
use objc2_app_kit::{NSModalResponseOK, NSOpenPanel};
use tauri::Manager;

use std::ffi::{CStr, OsStr};
use std::os::unix::ffi::OsStrExt;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};

static PICKER_ACTIVE: AtomicBool = AtomicBool::new(false);

/// RAII reset of `PICKER_ACTIVE`. The guard is created in `pick()` and moved
/// into the main-thread closure, so the flag releases when the closure falls
/// on every path: after the modal dialog closes, when the closure is dropped
/// without ever running (teardown), when it panics, or when scheduling it
/// fails (the closure is dropped by the failed call). Releasing early — when
/// `pick()`'s future is dropped — would let a second picker open while this
/// dialog is still up, so the guard is NOT owned by the future. If the closure
/// is retained but never executed, `recv()` waits forever; that state stays
/// visible (flag set) rather than silently letting another picker through.
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

/// Run `do_choose` under the moved-in `PickerGuard` and forward its result.
/// The guard lives for this closure's whole scope, so dropping the receiving
/// side (e.g. the `pick()` future) changes nothing: the flag still releases
/// when the scope exits, and a failed send is ignored.
fn run_selection_with<G: FnOnce() -> Result<PathBuf, String>>(
    guard: PickerGuard,
    do_choose: G,
    send: std::sync::mpsc::Sender<Result<PathBuf, String>>,
) {
    let _guard = guard;
    let selected = do_choose();
    let _ = send.send(selected);
}

pub async fn pick(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    if PICKER_ACTIVE.swap(true, Ordering::AcqRel) {
        return Err("file-busy".into());
    }
    let (send, receive) = std::sync::mpsc::channel();
    let guard = PickerGuard;
    if app
        .run_on_main_thread(move || {
            run_selection_with(guard, choose, send);
        })
        .is_err()
    {
        // The failed call drops the closure, which drops `guard` and releases
        // the flag; no dialog can have opened.
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc;

    static SERIAL: std::sync::Mutex<()> = std::sync::Mutex::new(());

    fn flag() -> bool {
        PICKER_ACTIVE.load(Ordering::Acquire)
    }

    /// Claim the flag as `pick()` does (first caller wins) and return the
    /// previous value.
    fn claim() -> bool {
        PICKER_ACTIVE.swap(true, Ordering::AcqRel)
    }

    #[test]
    fn picker_flag_releases_when_closure_exits() {
        let _serial = SERIAL.lock().unwrap();
        PICKER_ACTIVE.store(false, Ordering::Release);

        // (a) Execute path: flag held during the dialog, released when the
        // closure scope exits, and a second picker is blocked meanwhile.
        assert_eq!(claim(), false);
        let (send, receive) = mpsc::channel();
        run_selection_with(
            PickerGuard,
            || {
                assert!(flag(), "flag must stay set while the dialog is open");
                assert_eq!(claim(), true, "second picker must be blocked");
                Ok(PathBuf::from("/tmp/sample.bin"))
            },
            send,
        );
        assert_eq!(flag(), false, "flag releases when the closure exits");
        assert_eq!(receive.recv().unwrap().unwrap(), PathBuf::from("/tmp/sample.bin"));

        // Cancel path: an Err from the dialog also releases at scope exit.
        assert_eq!(claim(), false);
        let (send, receive) = mpsc::channel();
        run_selection_with(PickerGuard, || Err("import-cancelled".to_string()), send);
        assert_eq!(flag(), false);
        assert_eq!(receive.recv().unwrap(), Err("import-cancelled".to_string()));

        // (b) Future dropped: the receiver (pick() future) is gone before the
        // retained closure runs. The send fails silently and the flag is
        // still released when the closure scope exits.
        assert_eq!(claim(), false);
        let (send, _future) = mpsc::channel();
        drop(_future);
        run_selection_with(PickerGuard, || Ok(PathBuf::from("/tmp/sample.bin")), send);
        assert_eq!(flag(), false, "flag releases even when the future is dropped");

        // (c) Closure dropped before ever running (teardown path): the
        // session is over, recv will never answer, and the flag must still
        // release so later imports are not file-busy forever.
        assert_eq!(claim(), false);
        let (send, _receive) = mpsc::channel();
        let guard = PickerGuard;
        let closure = move || {
            run_selection_with(guard, || Ok(PathBuf::from("/tmp/sample.bin")), send);
        };
        drop(closure);
        assert_eq!(flag(), false, "flag releases when the closure is dropped");

        // Panic path: the guard releases during unwinding.
        assert_eq!(claim(), false);
        let (send, receive) = mpsc::channel();
        let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            run_selection_with(PickerGuard, || panic!("dialog blew up"), send);
        }));
        assert_eq!(flag(), false, "flag releases on panic");
        drop(receive);
    }
}
