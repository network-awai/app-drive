package network.awai.drive;
import android.content.*;
import android.net.Uri;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.*;
import java.io.*;
/** Persisted SAF tree selection and permission revalidation. The record lives
 *  in noBackupFilesDir — never on the picked tree — and stores only the tree
 *  URI the user chose. The tree receives re-fetchable ciphertext under cache/;
 *  lists, history, WAL, keys and unsynced protection copies stay internal. */
final class DriveSafSession {
 private static final String SCHEMA="awai.drive.saf.v1";
 private static final int GRANTS=Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
 private final Context context;private final AtomicFile record;
 DriveSafSession(Context c){context=c;record=new AtomicFile(new File(c.getNoBackupFilesDir(),"awai-drive-saf.v1.json"));}
 private JSONObject read()throws Exception{
  try(FileInputStream in=record.openRead()){
   long size=in.getChannel().size();
   if(size<=0||size>4096)throw new IOException("invalid-saf-record");
   byte[] b=new byte[(int)size];new DataInputStream(in).readFully(b);
   JSONObject j=new JSONObject(new String(b,"UTF-8"));
   if(!SCHEMA.equals(j.optString("schema")))throw new IOException("invalid-saf-record");
   return j;
  }catch(FileNotFoundException e){return new JSONObject();}
 }
 private void write(JSONObject j)throws Exception{
  if(j.toString().length()>4096)throw new IOException("invalid-saf-record");
  FileOutputStream out=record.startWrite();
  try{out.write(j.toString().getBytes("UTF-8"));record.finishWrite(out);}
  catch(IOException e){record.failWrite(out);throw e;}
 }
 void remember(Uri tree)throws Exception{
  JSONObject prior=read();String old=prior.optString("tree");
  if(!old.isEmpty()&&!old.equals(tree.toString())){
   try{context.getContentResolver().releasePersistableUriPermission(Uri.parse(old),GRANTS);}catch(Exception ignore){}
  }
  write(new JSONObject().put("schema",SCHEMA).put("tree",tree.toString()));
 }
 private boolean granted(Uri u){
  for(UriPermission p:context.getContentResolver().getPersistedUriPermissions())
   if(p.getUri().equals(u)&&p.isReadPermission()&&p.isWritePermission())return true;
  return false;
 }
 private Uri tree()throws IOException{
  String s;
  try{s=read().optString("tree");}catch(Exception e){throw new IOException("invalid-saf-record");}
  if(s.isEmpty())throw new IOException("cache-absent");
  Uri u=Uri.parse(s);
  if(!granted(u))throw new IOException("permission-lost");
  return u;
 }
 private DriveSafDocuments open()throws IOException{
  DriveSafDocuments t=new DriveSafDocuments(context.getContentResolver(),tree());
  t.ensure();
  return t;
 }
 /** Health probe: never claims verified content. "ready" means the tree is
 *  reachable and cache/ scans cleanly; per-blob digests are only checked by
 *  cacheGet on read and by the explicit cacheVerify op. */
 JSONObject status()throws Exception{
  JSONObject r;
  try{r=read();}catch(Exception e){return new JSONObject().put("picked",false).put("state","invalid-record");}
  if(r.optString("tree").isEmpty())return new JSONObject().put("picked",false).put("state","absent");
  Uri u=Uri.parse(r.getString("tree"));
  if(!granted(u))return new JSONObject().put("picked",true).put("state","permission-lost");
  try{
   DriveSaf.Report rep=DriveSaf.inspect(new DriveSafDocuments(context.getContentResolver(),u),false);
   return new JSONObject().put("picked",true).put("state","ready")
    .put("entries",rep.blobs).put("bytes",rep.bytes).put("staging",rep.pending)
    .put("unexpected",rep.unexpected).put("unexpectedNames",new JSONArray(rep.unexpectedNames))
    .put("clean",rep.clean);
  }catch(IOException e){
   String m=e.getMessage();
   if("permission-lost".equals(m))return new JSONObject().put("picked",true).put("state","permission-lost");
   if("cache-absent".equals(m))return new JSONObject().put("picked",true).put("state","unavailable");
   throw e;
  }
 }
 private JSONObject verify()throws Exception{
  DriveSafDocuments t=open();
  DriveSaf.Report rep=DriveSaf.inspect(t,true);
  int removed=0;
  for(String n:rep.corruptNames){try{t.delete(n);removed++;}catch(IOException ignore){}}
  return new JSONObject().put("picked",true).put("state","ready")
   .put("entries",rep.blobs).put("verified",rep.blobs-rep.corrupt).put("bytes",rep.bytes)
   .put("staging",rep.pending).put("unexpected",rep.unexpected)
   .put("unexpectedNames",new JSONArray(rep.unexpectedNames))
   .put("corrupt",rep.corrupt).put("corruptNames",new JSONArray(rep.corruptNames))
   .put("removed",removed).put("clean",rep.unexpected==0&&rep.corrupt==removed);
 }
 JSONObject execute(JSONObject q)throws Exception{
  switch(q.getString("op")){
   case "cacheStatus":return status();
   case "cacheVerify":return verify();
   case "cachePut":{
    byte[] data;
    try{data=Base64.decode(q.getString("bytes"),Base64.NO_WRAP);}
    catch(Exception e){throw new IOException("invalid-bytes");}
    String result=DriveSaf.put(open(),q.getString("sha256"),data);
    return new JSONObject().put("sha256",q.getString("sha256")).put("result",result);
   }
   case "cacheGet":{
    byte[] d=DriveSaf.get(open(),q.getString("sha256"));
    return new JSONObject().put("sha256",q.getString("sha256")).put("bytes",Base64.encodeToString(d,Base64.NO_WRAP));
   }
   case "cacheClear":return new JSONObject().put("deleted",DriveSaf.clear(open()));
   case "cacheRelease":{
    JSONObject r=read();String s=r.optString("tree");
    if(!s.isEmpty()){
     try{context.getContentResolver().releasePersistableUriPermission(Uri.parse(s),GRANTS);}catch(Exception ignore){}
    }
    record.delete();
    return new JSONObject().put("released",true);
   }
   default:throw new IOException("unsupported-operation");
  }
 }
}
