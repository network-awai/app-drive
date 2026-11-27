package network.awai.drive
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.ActivityResult
import androidx.core.content.FileProvider
import app.tauri.annotation.Command
import app.tauri.annotation.TauriPlugin
import app.tauri.annotation.ActivityCallback
import app.tauri.plugin.Plugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.util.concurrent.Executors
@TauriPlugin
class DrivePlugin(private val owner:Activity):Plugin(owner){
 private val worker=Executors.newSingleThreadExecutor()
 private val vault by lazy{DriveVault(owner)}
 private val account by lazy{DriveAccountLink(owner)}
 private val saf by lazy{DriveSafSession(owner)}
 private val local by lazy{DriveFiles(File(owner.filesDir,"drive-local-v1"))}
 private fun sync(parent:String){val dir=local.resolve(parent);require(dir.isDirectory);val fd=android.system.Os.open(dir.path,android.system.OsConstants.O_RDONLY,0);try{android.system.Os.fsync(fd)}finally{android.system.Os.close(fd)}}
 private fun args(i:Invoke):JSONObject{val raw=i.getArgs().getString("payload");require(raw.toByteArray().size<=131072);return JSONObject(raw)}
 private fun code(e:Exception):String{android.util.Log.w("AwaiDrive",e.javaClass.simpleName+":"+e.stackTrace.take(10).joinToString(";"){it.className+"."+it.methodName+":"+it.lineNumber});if(e is FileAlreadyExistsException)return "file-exists";return e.message?.takeIf{it in setOf("invalid-name","invalid-path","storage-full","storage-unavailable","file-too-large","entry-limit","file-busy","source-size-changed","local-provider-required","unsynced-data","recovery-required","restore-mismatch","sync-size-limit","account-mismatch","account-device-key-missing","invalid-account-link","file-exists","cache-cancelled","cache-absent","permission-lost","persist-unsupported","invalid-saf-record","invalid-bytes","blob-absent","cache-corrupt","sha-mismatch","cache-limit","foreign-directory-refused")}?:"drive-operation-failed"}
 @Command fun execute(i:Invoke){try{val q=args(i);val op=q.getString("op");if(op=="cachePick"){startActivityForResult(i,Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),"cachePicked");return};if(op.startsWith("account")||op.startsWith("vault")||op.startsWith("cache")){worker.execute{try{val response=if(op.startsWith("account")) account.execute(q) else if(op.startsWith("vault")) vault.execute(q) else saf.execute(q);i.resolve(JSObject(response.toString()))}catch(e:Exception){i.reject(code(e))}};return};when(op){
  "pick"->{local.resolve(q.getString("parent"));val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");startActivityForResult(i,intent,"picked")}
  "open"->{val f=local.resolve(q.getString("path"));require(f.isFile);val uri=FileProvider.getUriForFile(owner,"network.awai.drive.fileprovider",f);val intent=Intent(Intent.ACTION_VIEW).setDataAndType(uri,DriveDocuments.mime(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);owner.startActivity(Intent.createChooser(intent,"ファイルを開く"));i.resolve()}
  "list","mkdir","createText"->worker.execute{try{val parent=q.getString("parent");when(q.getString("op")){
   "list"->{val rows=JSONArray();for(f in local.list(parent))rows.put(JSONObject().put("name",f.name).put("path",local.path(f)).put("directory",f.isDirectory).put("bytes",f.length()).put("local",true).put("remoteVerified",false));i.resolve(JSObject().put("entries",rows))}
   "mkdir"->{local.mkdir(parent,q.getString("name"));sync(parent);i.resolve()}
   "createText"->{val bytes=q.getString("text").toByteArray(Charsets.UTF_8);require(bytes.size<=65536);val h=local.copy(parent,q.getString("name"),ByteArrayInputStream(bytes),bytes.size.toLong());sync(parent);i.resolve(JSObject().put("sha256",h))}
  }}catch(e:Exception){i.reject(code(e))}}
  else->i.reject("unsupported-operation")
 }}catch(e:Exception){i.reject(code(e))}}
 @ActivityCallback fun picked(i:Invoke,result:ActivityResult){if(result.resultCode!=Activity.RESULT_OK){i.reject("import-cancelled");return};val uri=result.data?.data;if(uri==null){i.reject("import-cancelled");return}
 worker.execute{try{
  // Network providers are not silently adopted as external storage operations.
  require(uri.scheme=="content");if(uri.authority !in setOf("com.android.externalstorage.documents","com.android.providers.downloads.documents","com.android.providers.media.documents","network.awai.drive.documents"))throw java.io.IOException("local-provider-required")
  var name:String?=null;var size=-1L;owner.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use{c->if(c.moveToFirst()){name=c.getString(0);if(!c.isNull(1))size=c.getLong(1)}}
  val hash=owner.contentResolver.openInputStream(uri)?.use{local.copy(args(i).getString("parent"),name?:"file",it,size)}?:throw java.io.IOException("unreadable-source");sync(args(i).getString("parent"));owner.contentResolver.notifyChange(Uri.parse("content://network.awai.drive.documents/root/local"),null);i.resolve(JSObject().put("sha256",hash))
 }catch(e:Exception){i.reject(code(e))}}
 }
 @ActivityCallback fun cachePicked(i:Invoke,result:ActivityResult){
  if(result.resultCode!=Activity.RESULT_OK){i.reject("cache-cancelled");return}
  val data=result.data;val uri=data?.data
  if(data==null||uri==null){i.reject("cache-cancelled");return}
  worker.execute{try{cachePickedDone(i,data,uri)}catch(e:Exception){i.reject(code(e))}}
 }
 private fun cachePickedDone(i:Invoke,data:Intent,uri:Uri){
  // Stage 1 fail-closed allowlist (AWAI-SPEC-REG-SAF-B1-v0.2 §2.3) runs in
  // DriveSafSession.acceptPicked before inspecting grant flags, taking
  // persistable permissions, or adopting the tree.
  saf.acceptPicked(uri,data.flags);i.resolve(JSObject(saf.status().toString()))
 }
}
