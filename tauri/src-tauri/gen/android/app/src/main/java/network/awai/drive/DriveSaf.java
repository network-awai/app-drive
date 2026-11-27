package network.awai.drive;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.regex.Pattern;
/** Content-addressed ciphertext cache confined to a picked SAF tree's cache/
 *  subtree (SAF-OWN-01..04). Policy only: ownership marker dual-binding
 *  (.awai-cache-owner.v1.json + local instanceId), name/digest rules, SHA-256
 *  verification on write and on every readback, separation of measured
 *  SHA-256 mismatch from oversize and transient I/O errors, pinned Item
 *  handles (never re-searching by display name), and double-gated deletion.
 *  Blobs are already-encrypted, re-fetchable units addressed by their digest;
 *  this is never a commit root and never holds lists, history, WAL, keys or
 *  unsynced protection copies. */
public final class DriveSaf {
 public static final String DIR="cache";
 public static final String OWNER_FILE=DriveSafRecord.OWNER_FILE;
 public static final int MAX_BLOB_BYTES=65531,MAX_ENTRIES=4096;
 public static final long MAX_BYTES=268435456L;
 private static final Pattern BLOB=Pattern.compile("[0-9a-f]{64}"),
  STAGE=Pattern.compile("\\.pending-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.part");
 private static final class Oversize extends IOException{Oversize(){super("cache-oversize");}}
 private enum CheckResult{INTACT,DIGEST_MISMATCH,OVERSIZE}
 public interface Tree{
  void locate()throws IOException;                           // pin existing cache/ doc ID once without creating it; never rebind once pinned
  void ensure()throws IOException;                           // pin existing cache/ or create it (adopt only); never rebind once pinned
  Item[] children()throws IOException;                       // direct children of pinned cache/ doc ID, each carrying its id
  InputStream read(Item item)throws IOException;             // open one pinned entry by Item handle
  Item create(String name,byte[] data)throws IOException;    // exclusive create+write returning created Item with exact id
  boolean confirmRegularFile(Item item)throws IOException;   // confirm pinned entry is a regular file (e.g. Os.fstat S_ISREG)
  void delete(Item item)throws IOException;                  // delete pinned entry by Item handle
 }
 public static final class Item{
  public final String name;
  public final String id;
  public final boolean directory,symlink,verifiedRegularFile;
  public final long size;
  public Item(String name,boolean directory,long size){this(name,null,directory,false,!directory,size);}
  public Item(String name,boolean directory,boolean symlink,long size){this(name,null,directory,symlink,!directory&&!symlink,size);}
  public Item(String name,String id,boolean directory,boolean symlink,boolean verifiedRegularFile,long size){
   this.name=name;this.id=id;this.directory=directory;this.symlink=symlink;this.verifiedRegularFile=verifiedRegularFile;this.size=size;
  }
  public boolean regularFile(){return verifiedRegularFile&&!directory&&!symlink;}
 }
 public static final class Report{
  public int blobs,pending,unexpected,corrupt,oversize,unreadable,removed,verified;public long bytes;public boolean clean;
  public final List<String> unexpectedNames=new ArrayList<>(),corruptNames=new ArrayList<>(),oversizeNames=new ArrayList<>(),unreadableNames=new ArrayList<>();
 }
 public static final class ClearReport{
  public int deleted,preservedForeign;public boolean ownerPreserved;
 }
 public static boolean blobName(String n){return n!=null&&BLOB.matcher(n).matches();}
 public static boolean staging(String n){return n!=null&&STAGE.matcher(n).matches();}
 public static boolean ownerFile(String n){return OWNER_FILE.equals(n);}
 public static boolean isInternalFile(String n){return ownerFile(n)||staging(n);}
 /** Filter for read-only document projections: internal ownership markers and
  *  staging files are never exposed as user documents (SAF-OWN-03). */
 public static boolean isUserDocument(String n){
  return n!=null&&!ownerFile(n)&&!staging(n)&&!n.startsWith(".pending-");
 }
 static String digest(byte[] data)throws IOException{
  MessageDigest d;try{d=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IOException("sha-unavailable");}
  byte[] h=d.digest(data);StringBuilder s=new StringBuilder(64);
  for(byte b:h)s.append(Character.forDigit((b>>4)&15,16)).append(Character.forDigit(b&15,16));
  return s.toString();
 }
 private static boolean fatalError(IOException e){
  String m=e.getMessage();
  return "permission-lost".equals(m)||"cache-absent".equals(m)||"foreign-directory-refused".equals(m);
 }
 /** Bounded read enforcing MAX_BLOB_BYTES without deleting. Throws Oversize
  *  if provider metadata reports item.size > MAX_BLOB_BYTES or if the stream
  *  exceeds MAX_BLOB_BYTES before EOF (full SHA-256 is NOT measured). */
 private static byte[] readBounded(Tree t,Item item)throws IOException{
  if(item.size>MAX_BLOB_BYTES)throw new Oversize();
  ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];long total=0;int n;
  try(InputStream in=t.read(item)){
   while((n=in.read(buf))!=-1){
    total+=n;
    if(total>MAX_BLOB_BYTES)throw new Oversize();
    out.write(buf,0,n);
   }
  }
  return out.toByteArray();
 }
 private static byte[] readOwnerBytes(Tree t,Item owner)throws IOException{
  if(!owner.regularFile()||owner.size>DriveSafRecord.OWNER_MAX_BYTES)throw new IOException("foreign-directory-refused");
  ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[1024];long total=0;int n;
  InputStream in;
  try{in=t.read(owner);}
  catch(IOException e){
   if("blob-absent".equals(e.getMessage()))throw new IOException("foreign-directory-refused");
   throw e;
  }
  try(InputStream closeable=in){
   while((n=closeable.read(buf))!=-1){
    total+=n;
    if(total>DriveSafRecord.OWNER_MAX_BYTES)throw new IOException("foreign-directory-refused");
    out.write(buf,0,n);
   }
  }
  return out.toByteArray();
 }
 /** Classify a blob entry: INTACT on measured SHA-256 match, DIGEST_MISMATCH
  *  ONLY when the entire blob (<= MAX_BLOB_BYTES) was read and SHA-256(bytes)
  *  != item.name, and OVERSIZE when metadata or stream exceeds MAX_BLOB_BYTES
  *  (full SHA-256 not measured). Transient read IOExceptions propagate. */
 private static CheckResult checkBlob(Tree t,Item item)throws IOException{
  byte[] d;
  try{d=readBounded(t,item);}
  catch(Oversize e){return CheckResult.OVERSIZE;}
  return item.name.equals(digest(d))?CheckResult.INTACT:CheckResult.DIGEST_MISMATCH;
 }
 /** SAF-OWN-02: strictly verify that <picked>/cache/ contains a single regular
  *  .awai-cache-owner.v1.json marker matching schema, appId, cacheVersion and
  *  the local persisted instanceId. Returns the verified pinned owner Item.
  *  Never writes or deletes. */
 private static Item verifyOwner(Tree t,Item[] kids,String expectedInstanceId)throws IOException{
  if(!DriveSafRecord.validUuidV4(expectedInstanceId))throw new IOException("foreign-directory-refused");
  Item owner=null;int ownerCount=0;
  for(Item it:kids)if(OWNER_FILE.equals(it.name)){owner=it;ownerCount++;}
  if(ownerCount!=1||owner==null||!owner.regularFile()||!t.confirmRegularFile(owner))throw new IOException("foreign-directory-refused");
  String found=DriveSafRecord.decodeOwner(readOwnerBytes(t,owner));
  if(!expectedInstanceId.equals(found))throw new IOException("foreign-directory-refused");
  return owner;
 }
 /** SAF-OWN-02: adopt an empty cache/ directory by writing and read-back
  *  verifying .awai-cache-owner.v1.json, returning the newly created Item
  *  marker, or verify ownership of an existing non-empty directory without
  *  modifying it (returning null). Non-empty directories without a strictly
  *  matching marker fail closed with foreign-directory-refused and zero
  *  writes/deletes. */
 public static Item adopt(Tree t,String instanceId)throws IOException{
  if(!DriveSafRecord.validUuidV4(instanceId))throw new IOException("foreign-directory-refused");
  t.ensure();
  Item[] kids=t.children();
  if(kids.length==0){
   byte[] marker=DriveSafRecord.encodeOwner(instanceId);
   Item created;
   try{created=t.create(OWNER_FILE,marker);}
   catch(IOException e){
    if("file-exists".equals(e.getMessage()))throw new IOException("foreign-directory-refused");
    throw e;
   }
   Item verified=verifyOwner(t,t.children(),instanceId);
   if(created!=null&&created.id!=null&&!created.id.equals(verified.id))throw new IOException("foreign-directory-refused");
   return verified;
  }
  verifyOwner(t,kids,instanceId);
  return null;
 }
 private static Report scan(Tree t,String instanceId,boolean checkDigests,boolean deleteCorrupt)throws IOException{
  if(!DriveSafRecord.validUuidV4(instanceId))throw new IOException("foreign-directory-refused");
  t.locate();Item[] kids=t.children();
  verifyOwner(t,kids,instanceId);
  Report r=new Report();
  for(Item it:kids){
   if(OWNER_FILE.equals(it.name))continue;
   if(!it.regularFile()||(!blobName(it.name)&&!staging(it.name))){
    r.unexpected++;if(r.unexpectedNames.size()<32)r.unexpectedNames.add(it.name);continue;
   }
   if(staging(it.name)){
    if(checkDigests&&!t.confirmRegularFile(it)){
     r.unexpected++;if(r.unexpectedNames.size()<32)r.unexpectedNames.add(it.name);continue;
    }
    r.pending++;continue;
   }
   if(checkDigests&&!t.confirmRegularFile(it)){
    r.unexpected++;if(r.unexpectedNames.size()<32)r.unexpectedNames.add(it.name);continue;
   }
   r.blobs++;r.bytes+=Math.max(0,it.size);
   if(checkDigests){
    CheckResult res;
    try{res=checkBlob(t,it);}
    catch(IOException e){
     if(fatalError(e))throw e;
     r.unreadable++;if(r.unreadableNames.size()<32)r.unreadableNames.add(it.name);
     continue;
    }
    if(res==CheckResult.OVERSIZE){
     r.oversize++;if(r.oversizeNames.size()<32)r.oversizeNames.add(it.name);
    }else if(res==CheckResult.DIGEST_MISMATCH){
     r.corrupt++;if(r.corruptNames.size()<32)r.corruptNames.add(it.name);
     if(deleteCorrupt){
      try{
       if(t.confirmRegularFile(it)){t.delete(it);r.removed++;}
      }catch(IOException e){
       if(fatalError(e))throw e;
      }
     }
    }
   }
  }
  r.verified=checkDigests?(r.blobs-r.corrupt-r.unreadable-r.oversize):0;
  r.clean=deleteCorrupt?(r.unexpected==0&&r.unreadable==0&&r.oversize==0&&r.corrupt==r.removed)
   :(r.unexpected==0&&r.corrupt==0&&r.oversize==0&&r.unreadable==0);
  return r;
 }
 /** Structural scan, or full per-blob digest verification when verify is set.
  *  Requires SAF-OWN-02 ownership verification and never deletes. Transient
  *  read failures are reported in unreadable/unreadableNames and oversize
  *  entries in oversize/oversizeNames, never corrupt. */
 public static Report inspect(Tree t,String instanceId,boolean verify)throws IOException{
  return scan(t,instanceId,verify,false);
 }
 /** Full digest verification with double-gated deletion (SAF-OWN-03): requires
  *  SAF-OWN-02 ownership verification, deletes ONLY entries with measured
  *  SHA-256 digest mismatch (read <= MAX_BLOB_BYTES and digest != name), and
  *  NEVER deletes oversize entries (reported in oversize/oversizeNames with
  *  clean=false) or transient read failures (reported in unreadable). */
 public static Report verify(Tree t,String instanceId)throws IOException{
  return scan(t,instanceId,true,true);
 }
 /** Write a verified ciphertext blob. Requires SAF-OWN-02 ownership
  *  verification. Returns "present" (already intact), "rebuilt" (existing
  *  entry had measured SHA-256 mismatch and was replaced) or "stored" (new).
  *  Oversize existing entries throw cache-corrupt WITHOUT deleting; transient
  *  read IOExceptions propagate without deleting. Nothing outside cache/ is
  *  touched; unsynced internal data is never a cache source or casualty. */
 public static String put(Tree t,String instanceId,String declared,byte[] data)throws IOException{
  if(!blobName(declared))throw new IOException("invalid-name");
  if(data==null||data.length>MAX_BLOB_BYTES)throw new IOException("file-too-large");
  if(!declared.equals(digest(data)))throw new IOException("sha-mismatch");
  if(!DriveSafRecord.validUuidV4(instanceId))throw new IOException("foreign-directory-refused");
  t.locate();Item[] kids=t.children();
  verifyOwner(t,kids,instanceId);
  int count=0;long bytes=0;Item existing=null;
  for(Item it:kids){
   if(OWNER_FILE.equals(it.name))continue;
   count++;
   if(it.name.equals(declared)&&!it.regularFile())throw new IOException("cache-corrupt");
   if(!it.regularFile())continue;
   if(blobName(it.name)){
    bytes+=Math.max(0,it.size);
    if(it.name.equals(declared)){
     if(existing!=null)throw new IOException("foreign-directory-refused");
     existing=it;
    }
   }
  }
  if(existing==null&&count>=MAX_ENTRIES)throw new IOException("entry-limit");
  long projected=bytes+data.length-(existing==null?0:Math.max(0,existing.size));
  if(projected>MAX_BYTES)throw new IOException("cache-limit");
  String result="stored";
  if(existing!=null){
   if(!t.confirmRegularFile(existing))throw new IOException("cache-corrupt");
   CheckResult cr=checkBlob(t,existing);
   if(cr==CheckResult.INTACT)return "present";
   if(cr==CheckResult.OVERSIZE)throw new IOException("cache-corrupt");
   if(!t.confirmRegularFile(existing))throw new IOException("foreign-directory-refused");
   t.delete(existing);
   result="rebuilt";
  }
  String stage=".pending-"+UUID.randomUUID()+".part";
  Item staged=null,created;
  try{
   staged=t.create(stage,data);
   created=t.create(declared,data);
  }finally{
   if(staged!=null){try{t.delete(staged);}catch(IOException ignore){}}
  }
  // SAF has no atomic rename: read the published blob back via its created Item
  // handle and require the digest to match before reporting success. Only
  // measured DIGEST_MISMATCH deletes the bad blob; Oversize throws
  // cache-corrupt without deleting; transient read IOExceptions propagate
  // without deleting.
  CheckResult published=checkBlob(t,created);
  if(published==CheckResult.DIGEST_MISMATCH){
   try{t.delete(created);}catch(IOException ignore){}
   throw new IOException("cache-corrupt");
  }
  if(published==CheckResult.OVERSIZE)throw new IOException("cache-corrupt");
  return result;
 }
 /** Read a blob back with digest verification on every call. Requires
  *  SAF-OWN-02 ownership verification. A measured SHA-256 mismatch is deleted
  *  because it is re-fetchable; oversize entries throw cache-corrupt WITHOUT
  *  deleting; transient read failures propagate without deleting. */
 public static byte[] get(Tree t,String instanceId,String sha)throws IOException{
  if(!blobName(sha))throw new IOException("invalid-name");
  if(!DriveSafRecord.validUuidV4(instanceId))throw new IOException("foreign-directory-refused");
  t.locate();Item[] kids=t.children();
  verifyOwner(t,kids,instanceId);
  Item target=null;
  for(Item it:kids)if(it.name.equals(sha)){
   if(!it.regularFile())throw new IOException("cache-corrupt");
   if(target!=null)throw new IOException("foreign-directory-refused");
   target=it;
  }
  if(target==null)throw new IOException("blob-absent");
  if(!t.confirmRegularFile(target))throw new IOException("cache-corrupt");
  byte[] d;
  try{d=readBounded(t,target);}
  catch(Oversize e){throw new IOException("cache-corrupt");}
  if(!sha.equals(digest(d))){
   try{
    if(t.confirmRegularFile(target))t.delete(target);
   }catch(IOException e){
    if(fatalError(e))throw e;
   }
   throw new IOException("cache-corrupt");
  }
  return d;
 }
 private static ClearReport clearKids(Tree t,Item[] kids)throws IOException{
  ClearReport cr=new ClearReport();
  for(Item it:kids){
   if(OWNER_FILE.equals(it.name)){cr.ownerPreserved=true;continue;}
   if(it.regularFile()&&(blobName(it.name)||staging(it.name))){
    if(!t.confirmRegularFile(it)){cr.preservedForeign++;continue;}
    t.delete(it);cr.deleted++;
   }else cr.preservedForeign++;
  }
  return cr;
 }
 /** Double-gated deletion (SAF-OWN-03): runs ONLY after SAF-OWN-02 ownership
  *  verification succeeds, deleting ONLY confirmed regular files matching
  *  64-char lowercase hex digest or .pending-<uuid>.part. Entries that fail
  *  regular-file confirmation are preserved in preservedForeign. Never deletes
  *  .awai-cache-owner.v1.json, foreign files, subdirectories, or symlinks. */
 public static ClearReport clearDetailed(Tree t,String instanceId)throws IOException{
  if(!DriveSafRecord.validUuidV4(instanceId))throw new IOException("foreign-directory-refused");
  t.locate();Item[] kids=t.children();
  verifyOwner(t,kids,instanceId);
  return clearKids(t,kids);
 }
 /** Remove only this cache's own regular entries after ownership verification. */
 public static int clear(Tree t,String instanceId)throws IOException{
  return clearDetailed(t,instanceId).deleted;
 }
 /** Explicit teardown for cacheRelease: verifies SAF-OWN-02 ownership once,
  *  removes cache-owned regular blobs/staging and reuses the verified pinned
  *  owner Item (confirming regular file before deleting), preserving any
  *  foreign files, subdirectories, or symlinks. */
 public static int release(Tree t,String instanceId)throws IOException{
  if(!DriveSafRecord.validUuidV4(instanceId))throw new IOException("foreign-directory-refused");
  t.locate();Item[] kids=t.children();
  Item owner=verifyOwner(t,kids,instanceId);
  ClearReport cr=clearKids(t,kids);
  if(!t.confirmRegularFile(owner))throw new IOException("foreign-directory-refused");
  t.delete(owner);
  return cr.deleted;
 }
}
