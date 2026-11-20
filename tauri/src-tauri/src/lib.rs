#[cfg(target_os="android")]
struct AndroidDrive(tauri::plugin::PluginHandle<tauri::DynRuntime>);
#[cfg(target_os="macos")]
mod desktop;
#[cfg(target_os="macos")]
mod desktop_vault;
#[cfg(target_os="macos")]
mod desktop_account;
#[tauri::command]
fn awai_host_info()->serde_json::Value { serde_json::json!({"schema":"awai.shell.host.v1","driveLocal":cfg!(any(target_os="android",target_os="macos")),"sync":false}) }
#[cfg(target_os="macos")]
fn macos_call(local:&desktop::Local, vault:&mut desktop_vault::Vault, op:&str, request:&serde_json::Value)->Result<serde_json::Value,String>{
 let s=|k:&str|request.get(k).and_then(|v|v.as_str()).map(str::to_owned).ok_or_else(||"invalid-request".to_string());
 match op{
  "list"=>Ok(serde_json::json!({"entries":local.list(&s("parent")?)?})),
  "mkdir"=>{local.mkdir(&s("parent")?,&s("name")?)?;Ok(serde_json::Value::Null)}
  "createText"=>Ok(serde_json::json!({"sha256":local.create_text(&s("parent")?,&s("name")?,&s("text")?)?})),
  // Hand the app-private file to the user's default viewer, like Android's ACTION_VIEW.
  "open"=>{let f=local.file(&s("path")?)?;let ok=std::process::Command::new("/usr/bin/open").arg("--").arg(&f).status().map(|st|st.success()).unwrap_or(false);if ok{Ok(serde_json::Value::Null)}else{Err("drive-operation-failed".into())}}
  "organizeMove"=>Ok(serde_json::json!({"path":local.move_entry(&s("path")?,&s("destination")?,&s("name")?)?})),
  "organizeTrash"=>Ok(serde_json::json!({"id":local.trash(&s("path")?)?})),
  "organizeTrashList"=>Ok(serde_json::json!({"entries":local.trash_list()?})),
  "organizeRecover"=>{local.recover(&s("id")?)?;Ok(serde_json::Value::Null)}
  "previewText"=>Ok(serde_json::json!({"text":local.preview_text(&s("path")?)?})),
  "openAccountBrowser"=>open_account_browser(&s("url")?),
  "pick"=> {
   let output=std::process::Command::new("/usr/bin/osascript").args(["-e","POSIX path of (choose file)"])
       .output().map_err(|_|"import-unavailable")?;
   if !output.status.success(){
    return Err(if String::from_utf8_lossy(&output.stderr).contains("(-128)"){"import-cancelled"}else{"import-unavailable"}.into());
   }
   let selected=std::str::from_utf8(&output.stdout).map_err(|_|"invalid-path")?;
   let source=std::path::Path::new(selected.strip_suffix('\n').unwrap_or(selected));
   if !source.is_absolute(){return Err("invalid-path".into());}
   local.import_file(&s("parent")?,source)?;
   Ok(serde_json::Value::Null)
  },
  _=>vault.call(local,op,request),
 }
}
#[cfg(target_os="macos")]
fn open_account_browser(url: &str) -> Result<serde_json::Value, String> {
    let fragment = url.strip_prefix("https://awai.network/link/#").ok_or("invalid-account-link")?;
    if fragment.is_empty() || fragment.len() > 4096 ||
        !fragment.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_')) {
        return Err("invalid-account-link".into());
    }
    let opened = std::process::Command::new("/usr/bin/open")
        .arg("--").arg(url).status().is_ok_and(|status| status.success());
    if !opened { return Err("browser-unavailable".into()); }
    Ok(serde_json::Value::Null)
}
#[tauri::command]
async fn drive_call(_app:tauri::AppHandle, request:serde_json::Value)->Result<serde_json::Value,String>{
 if serde_json::to_vec(&request).map_err(|_|"invalid-request")?.len()>131072{return Err("request-limit".into())}
 let op=request.get("op").and_then(|v|v.as_str()).ok_or("invalid-operation")?.to_owned();
 if !["list","mkdir","createText","pick","open","organizeMove","organizeTrash","organizeTrashList","organizeRecover","previewText","accountPublic","openAccountBrowser","accountProof","accountPrepare","accountRead","accountSave","accountBindWnfs","vaultStatus","vaultSession","vaultRecord","vaultInventory","vaultRead","vaultCommit","vaultEvict","vaultRestoreBegin","vaultRestoreWrite","vaultRestorePublish","vaultWnfsRecipients","vaultWnfsRecipientSet","vaultWnfsInventory","vaultWnfsCommit","vaultWnfsRestoreBegin","vaultWnfsRestoreWrite","vaultWnfsRestoreAbort","vaultWnfsRestorePublish"].contains(&op.as_str()){return Err("unsupported-operation".into())}
 #[cfg(target_os="android")]{use tauri::Manager;return _app.state::<AndroidDrive>().0.run_mobile_plugin_async("execute",serde_json::json!({"payload":request.to_string()})).await.map_err(|e| { let message=e.to_string(); ["unsynced-data","recovery-required","restore-mismatch","sync-size-limit","file-busy","file-exists","entry-limit","invalid-name","invalid-path","storage-full","file-too-large","import-cancelled","source-size-changed","local-provider-required","account-broker-unavailable","account-approval-denied","account-mismatch","account-device-key-missing","invalid-account-link"].iter().find(|code|message.contains(**code)).unwrap_or(&"drive-operation-failed").to_string() });}
 #[cfg(target_os="macos")]{use tauri::Manager;let app=_app.clone();return tauri::async_runtime::spawn_blocking(move||{let local=app.state::<desktop::Local>();let vault=app.state::<std::sync::Mutex<desktop_vault::Vault>>();let mut vault=vault.lock().map_err(|_|"storage-unavailable")?;macos_call(&local,&mut vault,&op,&request)}).await.map_err(|_|"drive-operation-failed".to_string())?;}
 #[cfg(not(any(target_os="android",target_os="macos")))] Err("android-adapter-required".into())
}
#[cfg_attr(mobile,tauri::mobile_entry_point)]
pub fn run(){tauri::Builder::default().runtime(tauri_runtime_wry::Wry::default())
 .plugin(tauri::plugin::Builder::<tauri::DynRuntime,()>::new("awai-drive").setup(|_app,_api|{
 #[cfg(target_os="android")]{use tauri::Manager;let h=_api.register_android_plugin("network.awai.drive","DrivePlugin")?;_app.manage(AndroidDrive(h));}
 #[cfg(target_os="macos")]{use tauri::Manager;let dir=_app.path().app_data_dir()?;_app.manage(desktop::Local::new(dir.join("drive-local-v1")).map_err(std::io::Error::other)?);let vault=desktop_vault::Vault::new(dir.join("drive-vault-wnfs-v1"),&_app.config().identifier).map_err(std::io::Error::other)?;_app.manage(std::sync::Mutex::new(vault));}
 Ok(())}).build())
 .invoke_handler(tauri::generate_handler![awai_host_info,drive_call]).run(tauri::generate_context!()).expect("Drive initialization failed");}
