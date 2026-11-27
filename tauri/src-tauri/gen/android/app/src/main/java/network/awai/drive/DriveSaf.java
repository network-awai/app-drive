package network.awai.drive;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.regex.Pattern;
/** Content-addressed ciphertext cache confined to a picked SAF tree's cache/
 *  subtree (SAF-OWN-01..04). Policy only: ownership marker dual-binding
 *  (.awai-cache-owner.v1.json + local instanceId), name/digest rules, SHA-256
 *  verification on write and on every readback, separation of confirmed
 *  corruption from transient I/O errors, and double-gated deletion. The tree
 *  implementation (DocumentsContract on Android, a file-backed double in tests)
 *  confines every call to cache/. Blobs are already-encrypted, re-fetchable
 *  units addressed by their digest; this is never a commit root and never holds
 *  lists, history, WAL, keys or unsynced protection copies. */
public final class DriveSaf {
 public static final String DIR="cache";
 public static final String OWNER_FILE=DriveSafRecord.OWNER_FILE;
 public static final int MAX_BLOB_BYTES=65531,MAX_ENTRIES=4096;
 public static final long MAX_BYTES=268435456L;
 private static final Pattern BLOB=Pattern.compile("[0-9a-f]{64}"),
  STAGE=Pattern.compile("\\.pending-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.part");
 private static final class Corrupt extends IOException{Corrupt(){super("cache-corrupt");}}
 public interface Tree{
  void locate()throws IOException;                          // locate existing cache/ without creating it
  void ensure()throws IOException;                          // locate/create cache/ (adopt only)
  Item[] children()throws IOException;                     // direct children of cache/
  InputStream read(String name)throws IOException;          // open one entry
  void create(String name,byte[] data)throws IOException;   // exclusive create+write
  void delete(String name)throws IOException;               // idempotent remove
 }
 public static final class Item{
  public final String name;public final boolean directory,symlink;public final long size;
  public Item(String name,boolean directory,long size){this(name,directory,false,size);}
  public Item(String name,boolean directory,boolean symlink,long size){
   this.name=name;this.directory=directory;this.symlink=symlink;this.size=size;
  }
  public boolean regularFile(){return !directory&&!symlink;}
 }
 public static final class Report{
  public int blobs,pending,unexpected,corrupt,unreadable,removed,verified;public long bytes;public boolean clean;
  public final List<String> unexpectedNames=new ArrayList<>(),corruptNames=new ArrayList<>(),unreadableNames=new ArrayList<>();
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
 private static byte[] readBounded(Tree t,String name,Item item)throws IOException{
  if(item.size>MAX_BLOB_BYTES)throw new Corrupt();
  ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];long total=0;int n;
  try(InputStream in=t.read(name)){
   while((n=in.read(buf))!=-1){
    total+=n;
    if(total>MAX_BLOB_BYTES)throw new Corrupt();
    out.write(buf,0,n);
   }
  }
  return out.toByteArray();
 }
 private static byte[] readOwnerBytes(Tree t,Item item)throws IOException{
  if(!item.regularFile()||item.size>DriveSafRecord.OWNER_MAX_BYTES)throw new IOException("foreign-directory-refused");
  ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[1024];long total=0;int n;
  InputStream in;
  try{in=t.read(OWNER_FILE);}
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
 /** Verify whether a blob is intact. Returns true on SHA-256 match, false ONLY
  *  on confirmed corruption (size > MAX_BLOB_BYTES or SHA-256 mismatch), and
  *  propagates transient read IOExceptions (e.g. storage-unavailable) without
  *  classifying them as corruption. */
 private static boolean intact(Tree t,String name,Item item)throws IOException{
  byte[] d;
  try{d=readBounded(t,name,item);}
  catch(Corrupt e){return false;}
  return name.equals(digest(d));
 }
 /** SAF-OWN-02: strictly verify that <picked>/cache/ contains a single regular
  *  .awai-cache-owner.v1.json marker matching schema, appId, cacheVersion and
  *  the local persisted instanceId. Never writes or deletes. */
 private static void verifyOwner(Tree t,Item[] kids,String expectedInstanceId)throws IOException{
  if(!DriveSafRecord.validUuidV4(expectedInstanceId))throw new IOException("foreign-directory-refused");
  Item owner=null;int ownerCount=0;
  for(Item it:kids)if(OWNER_FILE.equals(it.name)){owner=it;ownerCount++;}
  if(ownerCount!=1||owner==null||!owner.regularFile())throw new IOException("foreign-directory-refused");
  String found=DriveSafRecord.decodeOwner(readOwnerBytes(t,owner));
  if(!expectedInstanceId.equals(found))throw new IOException("foreign-directory-refused");
 }
 /** SAF-OWN-02: adopt an empty cache/ directory by writing and read-back
  *  verifying .awai-cache-owner.v1.json, or verify ownership of an existing
  *  non-empty directory without modifying it. Non-empty directories without a
  *  strictly matching marker fail closed with foreign-directory-refused and
  *  zero writes/deletes. */
 public static void adopt(Tree t,String instanceId)throws IOException{
  if(!DriveSafRecord.validUuidV4(instanceId))throw new IOException("foreign-directory-refused");
  t.ensure();
  Item[] kids=t.children();
  if(kids.length==0){
   byte[] marker=DriveSafRecord.encodeOwner(instanceId);
   try{t.create(OWNER_FILE,marker);}
   catch(IOException e){
    if("file-exists".equals(e.getMessage()))throw new IOException("foreign-directory-refused");
    throw e;
   }
   verifyOwner(t,t.children(),instanceId);
   return;
  }
  verifyOwner(t,kids,instanceId);
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
   if(staging(it.name)){r.pending++;continue;}
   r.blobs++;r.bytes+=Math.max(0,it.size);
   if(checkDigests){
    boolean ok;
    try{ok=intact(t,it.name,it);}
    catch(IOException e){
     String m=e.getMessage();
     if("permission-lost".equals(m)||"cache-absent".equals(m))throw e;
     r.unreadable++;if(r.unreadableNames.size()<32)r.unreadableNames.add(it.name);
     continue;
    }
    if(!ok){
     r.corrupt++;if(r.corruptNames.size()<32)r.corruptNames.add(it.name);
     if(deleteCorrupt){
      try{t.delete(it.name);r.removed++;}
      catch(IOException e){
       String m=e.getMessage();
       if("permission-lost".equals(m)||"cache-absent".equals(m))throw e;
      }
     }
    }
   }
  }
  r.verified=checkDigests?(r.blobs-r.corrupt-r.unreadable):0;
  r.clean=deleteCorrupt?(r.unexpected==0&&r.unreadable==0&&r.corrupt==r.removed)
   :(r.unexpected==0&&r.corrupt==0&&r.unreadable==0);
  return r;
 }
 /** Structural scan, or full per-blob digest verification when verify is set.
  *  Requires SAF-OWN-02 ownership verification and never deletes. Transient
  *  read failures are reported in unreadable/unreadableNames, never corrupt. */
 public static Report inspect(Tree t,String instanceId,boolean verify)throws IOException{
  return scan(t,instanceId,verify,false);
 }
 /** Full digest verification with double-gated deletion (SAF-OWN-03): requires
  *  SAF-OWN-02 ownership verification, deletes ONLY confirmed-corrupt blobs
  *  (SHA-256 mismatch or size > MAX_BLOB_BYTES), and preserves entries whose
  *  read threw a transient IOException (reported in unreadable). */
 public static Report verify(Tree t,String instanceId)throws IOException{
  return scan(t,instanceId,true,true);
 }
 /** Write a verified ciphertext blob. Requires SAF-OWN-02 ownership
  *  verification. Returns "present" (already intact), "rebuilt" (existing
  *  entry had confirmed corruption and was replaced) or "stored" (new).
  *  Transient read IOExceptions propagate without deleting any existing entry.
  *  Nothing outside cache/ is touched; unsynced internal data is never a cache
  *  source or casualty. */
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
   if(blobName(it.name)){bytes+=Math.max(0,it.size);if(it.name.equals(declared))existing=it;}
  }
  if(existing==null&&count>=MAX_ENTRIES)throw new IOException("entry-limit");
  long projected=bytes+data.length-(existing==null?0:Math.max(0,existing.size));
  if(projected>MAX_BYTES)throw new IOException("cache-limit");
  String result="stored";
  if(existing!=null){
   if(intact(t,declared,existing))return "present";
   t.delete(declared);
   result="rebuilt";
  }
  String stage=".pending-"+UUID.randomUUID()+".part";
  try{t.create(stage,data);t.create(declared,data);}
  finally{try{t.delete(stage);}catch(IOException ignore){}}
  // SAF has no atomic rename: read the published blob back and require the
  // digest to match before reporting success. Confirmed corruption deletes the
  // bad blob; transient read IOExceptions propagate without deleting.
  if(!intact(t,declared,new Item(declared,false,data.length))){
   try{t.delete(declared);}catch(IOException ignore){}
   throw new IOException("cache-corrupt");
  }
  return result;
 }
 /** Read a blob back with digest verification on every call. Requires
  *  SAF-OWN-02 ownership verification. A proven-corrupt entry (digest mismatch
  *  or oversize) is deleted because it is re-fetchable; transient read failures
  *  propagate without deleting. */
 public static byte[] get(Tree t,String instanceId,String sha)throws IOException{
  if(!blobName(sha))throw new IOException("invalid-name");
  if(!DriveSafRecord.validUuidV4(instanceId))throw new IOException("foreign-directory-refused");
  t.locate();Item[] kids=t.children();
  verifyOwner(t,kids,instanceId);
  for(Item it:kids)if(it.name.equals(sha)){
   if(!it.regularFile())throw new IOException("cache-corrupt");
   byte[] d;
   try{d=readBounded(t,sha,it);}
   catch(Corrupt e){try{t.delete(sha);}catch(IOException ignore){}throw new IOException("cache-corrupt");}
   if(!sha.equals(digest(d))){try{t.delete(sha);}catch(IOException ignore){}throw new IOException("cache-corrupt");}
   return d;
  }
  throw new IOException("blob-absent");
 }
 /** Double-gated deletion (SAF-OWN-03): runs ONLY after SAF-OWN-02 ownership
  *  verification succeeds, deleting ONLY regular files matching 64-char
  *  lowercase hex digest or .pending-<uuid>.part. Never deletes
  *  .awai-cache-owner.v1.json, foreign files, subdirectories, or symlinks. */
 public static ClearReport clearDetailed(Tree t,String instanceId)throws IOException{
  if(!DriveSafRecord.validUuidV4(instanceId))throw new IOException("foreign-directory-refused");
  t.locate();Item[] kids=t.children();
  verifyOwner(t,kids,instanceId);
  ClearReport cr=new ClearReport();
  for(Item it:kids){
   if(OWNER_FILE.equals(it.name)){cr.ownerPreserved=true;continue;}
   if(it.regularFile()&&(blobName(it.name)||staging(it.name))){t.delete(it.name);cr.deleted++;}
   else cr.preservedForeign++;
  }
  return cr;
 }
 /** Remove only this cache's own regular entries after ownership verification. */
 public static int clear(Tree t,String instanceId)throws IOException{
  return clearDetailed(t,instanceId).deleted;
 }
 /** Explicit teardown for cacheRelease: verifies SAF-OWN-02 ownership, removes
  *  cache-owned regular blobs/staging and .awai-cache-owner.v1.json, and still
  *  preserves any foreign files, subdirectories, or symlinks. */
 public static int release(Tree t,String instanceId)throws IOException{
  ClearReport cr=clearDetailed(t,instanceId);
  t.delete(OWNER_FILE);
  return cr.deleted;
 }
}
