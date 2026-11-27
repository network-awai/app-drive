package network.awai.drive;
import java.io.*;
import java.util.*;
/** Pure-Java SAF protocol adapter implementing DriveSaf.Tree with zero
 *  android.* imports so the SAF boundary (pinned cache doc ID, unambiguous
 *  root scan, strict regular-file metadata checks, pinned child identity,
 *  and regular-file/delete-flag confirmation) is directly JVM-tested. */
public final class DriveSafSafAdapter implements DriveSaf.Tree {
 /** Mirrors DocumentsContract.Document.MIME_TYPE_DIR. */
 public static final String MIME_TYPE_DIR="vnd.android.document/directory";
 /** Mirrors DocumentsContract.Document.FLAG_SUPPORTS_DELETE. */
 public static final int FLAG_SUPPORTS_DELETE=1<<2;
 /** Mirrors DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT. */
 public static final int FLAG_VIRTUAL_DOCUMENT=1<<9;

 public static final class Doc {
  public final String id,name,mime;
  public final long size;
  public final Integer flags;
  public Doc(String id,String name,String mime,long size,Integer flags){
   this.id=id;this.name=name;this.mime=mime;this.size=size;this.flags=flags;
  }
 }

 public interface Provider {
  String rootId()throws IOException;
  Doc queryDoc(String docId)throws IOException;
  List<Doc> listChildren(String parentId)throws IOException;
  Doc createDoc(String parentId,String mime,String name)throws IOException;
  void writeDoc(String docId,byte[] data)throws IOException;
  InputStream openRead(String docId)throws IOException;
  boolean confirmRegularFile(String docId,long expectedSize)throws IOException;
  void deleteDoc(String docId)throws IOException;
 }

 private final Provider provider;
 private String pinnedCacheId;

 public DriveSafSafAdapter(Provider provider){
  this.provider=Objects.requireNonNull(provider,"provider");
 }

 public String pinnedCacheId(){return pinnedCacheId;}

 private static boolean nonEmpty(String s){return s!=null&&!s.isEmpty();}

 private static boolean isDirectoryMime(String mime){return MIME_TYPE_DIR.equals(mime);}

 private static boolean isSymlinkMime(String mime){
  if(mime==null)return false;
  String m=mime.toLowerCase(Locale.ROOT);
  return m.equals("inode/symlink")||m.equals("application/x-symlink")||m.startsWith("inode/");
 }

 private static boolean isValidCacheDir(Doc d){
  return d!=null&&nonEmpty(d.id)&&DriveSaf.DIR.equals(d.name)&&MIME_TYPE_DIR.equals(d.mime)
   &&d.flags!=null&&(d.flags.intValue()&FLAG_VIRTUAL_DOCUMENT)==0;
 }

 private static boolean isRegularFileMetadata(Doc d){
  if(d==null||!nonEmpty(d.id)||!nonEmpty(d.name)||!nonEmpty(d.mime))return false;
  if(isDirectoryMime(d.mime))return false;
  String lower=d.mime.toLowerCase(Locale.ROOT);
  if(lower.startsWith("vnd.android.document/"))return false;
  if(isSymlinkMime(d.mime))return false;
  if(d.flags==null)return false;
  if((d.flags.intValue()&FLAG_VIRTUAL_DOCUMENT)!=0)return false;
  return true;
 }

 private static DriveSaf.Item toItem(Doc d){
  boolean dir=isDirectoryMime(d.mime);
  boolean sym=isSymlinkMime(d.mime);
  boolean reg=isRegularFileMetadata(d);
  return new DriveSaf.Item(d.name,d.id,dir,sym,reg,d.size);
 }

 private String requireRootId()throws IOException{
  String root=provider.rootId();
  if(!nonEmpty(root))throw new IOException("cache-absent");
  return root;
 }

 private void dropBestEffort(String docId){
  if(!nonEmpty(docId))return;
  try{provider.deleteDoc(docId);}catch(Exception ignore){}
 }

 /** Verify the already-pinned cache doc ID without ever clearing or rebinding
  *  pinnedCacheId to another document. Fails closed if the pinned doc
  *  disappears, changes metadata, is moved out of root, or has a sibling
  *  "cache" entry added at root. */
 private void verifyPinnedCache()throws IOException{
  if(pinnedCacheId==null)throw new IOException("cache-absent");
  Doc d=provider.queryDoc(pinnedCacheId);
  if(d==null)throw new IOException("cache-absent");
  if(!pinnedCacheId.equals(d.id)||!isValidCacheDir(d))throw new IOException("foreign-directory-refused");
  List<Doc> rootKids=provider.listChildren(requireRootId());
  if(rootKids==null)throw new IOException("cache-absent");
  int cacheMatches=0;boolean foundPinned=false;
  for(Doc rk:rootKids){
   if(rk!=null&&DriveSaf.DIR.equals(rk.name)){
    cacheMatches++;
    if(pinnedCacheId.equals(rk.id)){
     if(!isValidCacheDir(rk))throw new IOException("foreign-directory-refused");
     foundPinned=true;
    }
   }
  }
  if(!foundPinned||cacheMatches!=1)throw new IOException("foreign-directory-refused");
 }

 @Override public void locate()throws IOException{
  if(pinnedCacheId!=null){verifyPinnedCache();return;}
  String root=requireRootId();
  List<Doc> kids=provider.listChildren(root);
  if(kids==null)throw new IOException("cache-absent");
  Doc found=null;
  for(Doc d:kids){
   if(d==null||!DriveSaf.DIR.equals(d.name))continue;
   if(found!=null)throw new IOException("foreign-directory-refused");
   if(!isValidCacheDir(d))throw new IOException("foreign-directory-refused");
   found=d;
  }
  if(found==null)throw new IOException("cache-absent");
  pinnedCacheId=found.id;
  verifyPinnedCache();
 }

 @Override public void ensure()throws IOException{
  if(pinnedCacheId!=null){verifyPinnedCache();return;}
  try{locate();return;}
  catch(IOException e){if(!"cache-absent".equals(e.getMessage()))throw e;}
  String root=requireRootId();
  Doc made=provider.createDoc(root,MIME_TYPE_DIR,DriveSaf.DIR);
  if(made==null||!nonEmpty(made.id))throw new IOException("cache-absent");
  if(!DriveSaf.DIR.equals(made.name)){dropBestEffort(made.id);throw new IOException("file-exists");}
  if(!isValidCacheDir(made)){dropBestEffort(made.id);throw new IOException("foreign-directory-refused");}
  pinnedCacheId=made.id;
  verifyPinnedCache();
 }

 @Override public DriveSaf.Item[] children()throws IOException{
  if(pinnedCacheId==null)locate();
  else verifyPinnedCache();
  List<Doc> kids=provider.listChildren(pinnedCacheId);
  if(kids==null)throw new IOException("cache-absent");
  List<DriveSaf.Item> out=new ArrayList<>(kids.size());
  for(Doc d:kids){
   if(d!=null&&d.name!=null)out.add(toItem(d));
  }
  return out.toArray(new DriveSaf.Item[0]);
 }

 private Doc resolvePinnedRegularChild(DriveSaf.Item item)throws IOException{
  if(pinnedCacheId==null)throw new IOException("cache-absent");
  verifyPinnedCache();
  if(item==null||!nonEmpty(item.id)||!nonEmpty(item.name))throw new IOException("foreign-directory-refused");
  List<Doc> kids=provider.listChildren(pinnedCacheId);
  if(kids==null)throw new IOException("cache-absent");
  Doc match=null;
  for(Doc d:kids){
   if(d!=null&&item.id.equals(d.id)){
    if(match!=null)throw new IOException("foreign-directory-refused");
    match=d;
   }
  }
  if(match==null)throw new IOException("foreign-directory-refused");
  if(!item.name.equals(match.name)||!isRegularFileMetadata(match))throw new IOException("foreign-directory-refused");
  return match;
 }

 @Override public InputStream read(DriveSaf.Item item)throws IOException{
  Doc d=resolvePinnedRegularChild(item);
  InputStream in=provider.openRead(d.id);
  if(in==null)throw new IOException("storage-unavailable");
  return in;
 }

 @Override public DriveSaf.Item create(String name,byte[] data)throws IOException{
  if(pinnedCacheId==null)throw new IOException("cache-absent");
  verifyPinnedCache();
  if(!nonEmpty(name)||data==null)throw new IOException("invalid-name");
  List<Doc> kids=provider.listChildren(pinnedCacheId);
  if(kids==null)throw new IOException("cache-absent");
  for(Doc d:kids)if(d!=null&&name.equals(d.name))throw new IOException("file-exists");
  String mime=DriveSaf.ownerFile(name)?"application/json":"application/octet-stream";
  Doc made=provider.createDoc(pinnedCacheId,mime,name);
  if(made==null||!nonEmpty(made.id))throw new IOException("cache-absent");
  if(!name.equals(made.name)){dropBestEffort(made.id);throw new IOException("file-exists");}
  try{
   provider.writeDoc(made.id,data);
  }catch(IOException|RuntimeException e){
   dropBestEffort(made.id);
   if(e instanceof IOException io){
    String m=io.getMessage();
    if("permission-lost".equals(m)||"cache-absent".equals(m)||"foreign-directory-refused".equals(m))throw io;
   }
   throw new IOException("storage-unavailable");
  }
  Doc finalDoc=provider.queryDoc(made.id);
  if(finalDoc==null||!made.id.equals(finalDoc.id)||!name.equals(finalDoc.name)){
   dropBestEffort(made.id);
   throw new IOException("storage-unavailable");
  }
  return toItem(finalDoc);
 }

 @Override public boolean confirmRegularFile(DriveSaf.Item item)throws IOException{
  if(item==null||!item.regularFile())return false;
  Doc d=resolvePinnedRegularChild(item);
  return provider.confirmRegularFile(d.id,d.size);
 }

 @Override public void delete(DriveSaf.Item item)throws IOException{
  if(item==null||!item.regularFile())throw new IOException("foreign-directory-refused");
  Doc d=resolvePinnedRegularChild(item);
  if((d.flags.intValue()&FLAG_SUPPORTS_DELETE)==0)throw new IOException("foreign-directory-refused");
  if(!provider.confirmRegularFile(d.id,d.size))throw new IOException("foreign-directory-refused");
  provider.deleteDoc(d.id);
 }
}
