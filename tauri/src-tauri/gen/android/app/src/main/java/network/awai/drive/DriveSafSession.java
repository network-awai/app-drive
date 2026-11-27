package network.awai.drive;
import android.content.*;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.*;
import java.io.*;
/** Persisted SAF tree selection, ownership dual-binding (SAF-OWN-02),
 *  2-stage allowlist enforcement (AWAI-SPEC-REG-SAF-B1-v0.2 §2.3), and
 *  permission revalidation. The record lives in noBackupFilesDir — never on
 *  the picked tree — and stores only the tree URI, appId, and UUIDv4
 *  instanceId. The tree receives re-fetchable ciphertext under cache/; lists,
 *  history, WAL, keys and unsynced protection copies stay internal.
 *  Per SAF-OWN-04 / AWAI-4150 §12(e), ciphertext-only cacheClear is exempt
 *  from OS re-auth while never omitting SAF-OWN-02 ownership verification. */
final class DriveSafSession {
 private static final int GRANTS=Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
 private final Context context;private final AtomicFile record;
 DriveSafSession(Context c){context=c;record=new AtomicFile(new File(c.getNoBackupFilesDir(),"awai-drive-saf.v1.json"));}
 private static void validateTreeUri(Uri uri)throws IOException{
  if(uri==null)throw new IOException(DriveSafPolicy.ERR_PROVIDER);
  DriveSafPolicy.requireAllowedTreeUri(uri.toString());
  String docId;
  try{docId=DocumentsContract.getTreeDocumentId(uri);}
  catch(IllegalArgumentException e){throw new IOException(DriveSafPolicy.ERR_PROVIDER);}
  DriveSafPolicy.requireAllowedParts(uri.getScheme(),uri.getAuthority(),docId);
 }
 /** Stage 1 fail-closed entry point for cachePick: validates the picked tree
  *  URI against the com.android.externalstorage.documents allowlist BEFORE
  *  checking grant flags, taking persistable permissions, or adopting the
  *  directory. */
 void acceptPicked(Uri uri,int intentFlags)throws Exception{
  validateTreeUri(uri);
  if((intentFlags&GRANTS)!=GRANTS)throw new IOException("persist-unsupported");
  int flags=intentFlags&(GRANTS|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
  try{context.getContentResolver().takePersistableUriPermission(uri,flags);}
  catch(SecurityException e){throw new IOException("persist-unsupported");}
  remember(uri);
 }
 /** null means no record; malformed content is invalid-saf-record. */
 private DriveSafRecord.Record saved()throws IOException{
  try(FileInputStream in=record.openRead()){
   long size=in.getChannel().size();
   if(size<=0||size>DriveSafRecord.MAX_BYTES)throw new IOException("invalid-saf-record");
   byte[] b=new byte[(int)size];new DataInputStream(in).readFully(b);
   return DriveSafRecord.decodeRecord(b);
  }catch(FileNotFoundException e){return null;}
 }
 private void save(String tree,String instanceId)throws Exception{
  FileOutputStream out=record.startWrite();
  try{out.write(DriveSafRecord.encode(tree,instanceId));record.finishWrite(out);}
  catch(IOException e){record.failWrite(out);throw e;}
 }
 private void releaseGrant(Uri uri){
  try{context.getContentResolver().releasePersistableUriPermission(uri,GRANTS);}catch(Exception ignore){}
 }
 /** Adopt-and-commit: verify or adopt <picked>/cache/ ownership before
  *  committing the local record, and commit the new record before releasing
  *  the prior grant so a foreign-directory refusal or failed write leaves the
  *  previous selection fully usable. */
 void remember(Uri tree)throws Exception{
  validateTreeUri(tree);
  DriveSafRecord.Record prior;
  try{prior=saved();}catch(Exception e){prior=null;} // corrupt prior: overwritten only after safe adoption
  String next=tree.toString();
  DriveSafDocuments t=new DriveSafDocuments(context.getContentResolver(),tree);
  if(prior!=null&&next.equals(prior.tree)&&prior.hasOwnership()){
   DriveSaf.adopt(t,prior.instanceId);
   return;
  }
  try{
   try{
    t.locate();
    if(t.children().length>0)throw new IOException("foreign-directory-refused");
   }catch(IOException e){
    if(!"cache-absent".equals(e.getMessage()))throw e;
   }
   String instanceId=DriveSafRecord.newInstanceId();
   DriveSaf.Item marker=DriveSaf.adopt(t,instanceId);
   try{save(next,instanceId);}
   catch(Exception e){
    if(marker!=null){try{t.delete(marker);}catch(Exception ignore){}}
    throw e;
   }
  }catch(Exception e){
   if(prior==null||!next.equals(prior.tree))releaseGrant(tree);
   throw e;
  }
  if(prior!=null&&!prior.tree.isEmpty()&&!prior.tree.equals(next))releaseGrant(Uri.parse(prior.tree));
 }
 private boolean granted(Uri u){
  for(UriPermission p:context.getContentResolver().getPersistedUriPermissions())
   if(p.getUri().equals(u)&&p.isReadPermission()&&p.isWritePermission())return true;
  return false;
 }
 private DriveSafRecord.Record active()throws IOException{
  DriveSafRecord.Record r;
  try{r=saved();}catch(Exception e){throw new IOException("invalid-saf-record");}
  if(r==null)throw new IOException("cache-absent");
  Uri u=Uri.parse(r.tree);
  validateTreeUri(u);
  if(!granted(u))throw new IOException("permission-lost");
  if(!r.hasOwnership())throw new IOException("foreign-directory-refused");
  return r;
 }
 private DriveSafDocuments open(DriveSafRecord.Record r)throws IOException{
  DriveSafDocuments t=new DriveSafDocuments(context.getContentResolver(),Uri.parse(r.tree));
  t.locate();
  return t;
 }
 /** Health probe: never claims verified content. "ready" means the tree is
  *  reachable, SAF-OWN-02 ownership verified, and cache/ scans cleanly;
  *  per-blob digests are only checked by cacheGet and explicit cacheVerify. */
 JSONObject status()throws Exception{
  DriveSafRecord.Record r;
  try{r=saved();}catch(Exception e){return new JSONObject().put("picked",false).put("state","invalid-record");}
  if(r==null)return new JSONObject().put("picked",false).put("state","absent");
  Uri u=Uri.parse(r.tree);
  validateTreeUri(u);
  if(!granted(u))return new JSONObject().put("picked",true).put("state","permission-lost");
  if(!r.hasOwnership())return new JSONObject().put("picked",true).put("state","foreign-directory-refused");
  try{
   DriveSaf.Report rep=DriveSaf.inspect(new DriveSafDocuments(context.getContentResolver(),u),r.instanceId,false);
   return new JSONObject().put("picked",true).put("state","ready")
    .put("entries",rep.blobs).put("bytes",rep.bytes).put("staging",rep.pending)
    .put("unexpected",rep.unexpected).put("unexpectedNames",new JSONArray(rep.unexpectedNames))
    .put("clean",rep.clean);
  }catch(IOException e){
   String m=e.getMessage();
   if("permission-lost".equals(m))return new JSONObject().put("picked",true).put("state","permission-lost");
   if("cache-absent".equals(m))return new JSONObject().put("picked",true).put("state","unavailable");
   if("foreign-directory-refused".equals(m))return new JSONObject().put("picked",true).put("state","foreign-directory-refused");
   throw e;
  }
 }
 private JSONObject verify()throws Exception{
  DriveSafRecord.Record r=active();
  DriveSaf.Report rep=DriveSaf.verify(open(r),r.instanceId);
  return new JSONObject().put("picked",true).put("state","ready")
   .put("entries",rep.blobs).put("verified",rep.verified).put("bytes",rep.bytes)
   .put("staging",rep.pending).put("unexpected",rep.unexpected)
   .put("unexpectedNames",new JSONArray(rep.unexpectedNames))
   .put("corrupt",rep.corrupt).put("corruptNames",new JSONArray(rep.corruptNames))
   .put("oversize",rep.oversize).put("oversizeNames",new JSONArray(rep.oversizeNames))
   .put("unreadable",rep.unreadable).put("unreadableNames",new JSONArray(rep.unreadableNames))
   .put("removed",rep.removed).put("clean",rep.clean);
 }
 JSONObject execute(JSONObject q)throws Exception{
  switch(q.getString("op")){
   case "cacheStatus":return status();
   case "cacheVerify":return verify();
   case "cachePut":{
    byte[] data;
    try{data=Base64.decode(q.getString("bytes"),Base64.NO_WRAP);}
    catch(Exception e){throw new IOException("invalid-bytes");}
    DriveSafRecord.Record r=active();
    String result=DriveSaf.put(open(r),r.instanceId,q.getString("sha256"),data);
    return new JSONObject().put("sha256",q.getString("sha256")).put("result",result);
   }
   case "cacheGet":{
    DriveSafRecord.Record r=active();
    byte[] d=DriveSaf.get(open(r),r.instanceId,q.getString("sha256"));
    return new JSONObject().put("sha256",q.getString("sha256")).put("bytes",Base64.encodeToString(d,Base64.NO_WRAP));
   }
   case "cacheClear":{
    // SAF-OWN-04: ciphertext-only cache clear is exempt from OS re-auth
    // (AWAI-4150 §12(e)), while strictly enforcing SAF-OWN-02 ownership check.
    DriveSafRecord.Record r=active();
    DriveSaf.ClearReport cr=DriveSaf.clearDetailed(open(r),r.instanceId);
    return new JSONObject().put("deleted",cr.deleted)
     .put("preservedForeign",cr.preservedForeign).put("ownerPreserved",cr.ownerPreserved);
   }
   case "cacheRelease":{
    DriveSafRecord.Record r=null;
    try{r=saved();}catch(Exception ignore){} // release must always delete, even a corrupt record
    if(r!=null){
     Uri u=Uri.parse(r.tree);
     if(DriveSafPolicy.isAllowedTreeUri(r.tree)&&r.hasOwnership()&&granted(u)){
      try{
       validateTreeUri(u);
       DriveSaf.release(new DriveSafDocuments(context.getContentResolver(),u),r.instanceId);
      }catch(Exception ignore){}
     }
     releaseGrant(u);
    }
    record.delete();
    return new JSONObject().put("released",true);
   }
   default:throw new IOException("unsupported-operation");
  }
 }
}
