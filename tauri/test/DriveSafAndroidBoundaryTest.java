package network.awai.drive;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Callable;

/** JVM boundary test compiling and running the ACTUAL production
 *  DriveSafDocuments.java and DriveSafSession.java (plus DriveSafPolicy,
 *  DriveSafRecord, DriveSaf, DriveSafSafAdapter) against minimal in-memory
 *  android.* + org.json.* test shadows in test/android-stubs/.
 *
 *  Verifies AWAI-SPEC-REG-SAF-B1-v0.2 §2.3:
 *  1) Stage 1 DriveSafSession.acceptPicked rejects disallowed authorities with
 *     "local-provider-required" and takePersistableUriPermission calls == 0,
 *     enforces Android's strict READ|WRITE modeFlags contract on
 *     takePersistableUriPermission / releasePersistableUriPermission, rejects
 *     missing READ/WRITE or SecurityException with "persist-unsupported", and
 *     accepts com.android.externalstorage.documents.
 *  2) DriveSafDocuments rejects disallowed authorities on all 7 DriveSaf.Tree
 *     entry points with "local-provider-required" and 0 ContentResolver /
 *     DocumentsContract calls.
 *  3) Stage 2 tampered persisted awai-drive-saf.v1.json with a disallowed
 *     authority fails with "local-provider-required" on status, cachePut,
 *     cacheGet, cacheVerify, cacheClear with 0 DocumentsContract calls and 0
 *     deletions, while cacheRelease deletes only the local record and makes 0
 *     DocumentsContract.deleteDocument calls.
 *  4) DriveSafDocuments.confirmRegularFile fails closed (false) on S_ISREG ==
 *     false (symlink/FIFO/dir), st_size mismatch, null PFD, and fstat errors;
 *     propagates SecurityException as IOException("permission-lost"); returns
 *     true only on S_ISREG == true and matching st_size.
 *  5) DriveSafDocuments.java source contains the Honest Non-Goal disclosure. */
public class DriveSafAndroidBoundaryTest {
 static int checks=0;
 static void yes(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 static void eq(Object a,Object b){checks++;if(!Objects.equals(a,b))throw new AssertionError("check "+checks+" expected "+b+" got "+a);}
 static String code(Callable<?> f)throws Exception{
  checks++;
  try{f.call();throw new AssertionError("check "+checks+" accepted forbidden input");}
  catch(IOException e){return e.getMessage();}
 }
 static void expectIllegalArgument(Runnable r){
  checks++;
  try{r.run();throw new AssertionError("check "+checks+" expected IllegalArgumentException");}
  catch(IllegalArgumentException expected){}
 }
 static String sha(byte[] b)throws Exception{
  return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
 }
 static void deleteTree(Path p)throws IOException{
  if(!Files.exists(p,LinkOption.NOFOLLOW_LINKS))return;
  if(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)){
   try(var s=Files.list(p)){for(Path c:s.toList())deleteTree(c);}
  }
  Files.delete(p);
 }

 static final class FakeDoc {
  final String id;
  String parentId,name,mime;
  long size;
  Integer flags;
  byte[] bytes;
  int mode=OsConstants.S_IFREG|0600;
  Long statSizeOverride;
  boolean returnNullPfd;
  boolean throwSecurityOnOpenFd;
  boolean throwFstatError;
  FakeDoc(String id,String parentId,String name,String mime,long size,Integer flags,byte[] bytes){
   this.id=id;this.parentId=parentId;this.name=name;this.mime=mime;
   this.size=size;this.flags=flags;this.bytes=bytes;
  }
 }

 static final class RowsCursor implements Cursor {
  private final List<Object[]> rows;
  private int idx=-1;
  RowsCursor(List<Object[]> rows){this.rows=rows;}
  @Override public boolean moveToFirst(){idx=0;return !rows.isEmpty();}
  @Override public boolean moveToNext(){idx++;return idx<rows.size();}
  @Override public boolean isNull(int col){return rows.get(idx)[col]==null;}
  @Override public String getString(int col){return (String)rows.get(idx)[col];}
  @Override public long getLong(int col){return ((Number)rows.get(idx)[col]).longValue();}
  @Override public int getInt(int col){return ((Number)rows.get(idx)[col]).intValue();}
  @Override public void close(){}
 }

 static final class FakeResolver extends ContentResolver implements DocumentsContract.Backend {
  final Map<String,FakeDoc> docs=new LinkedHashMap<>();
  final Map<Uri,UriPermission> grants=new LinkedHashMap<>();
  int seq=1;
  boolean throwSecurityOnTakePersistable;

  int queryCalls;
  int openInputStreamCalls;
  int openOutputStreamCalls;
  int openFileDescriptorCalls;
  int takePersistableCalls;
  int releasePersistableCalls;
  int getPersistedCalls;
  int lastTakeFlags;
  int lastReleaseFlags;

  void resetCounts(){
   queryCalls=0;openInputStreamCalls=0;openOutputStreamCalls=0;openFileDescriptorCalls=0;
   takePersistableCalls=0;releasePersistableCalls=0;getPersistedCalls=0;
   lastTakeFlags=0;lastReleaseFlags=0;
   DocumentsContract.resetCounts();
  }

  int totalResolverIoCalls(){
   return queryCalls+openInputStreamCalls+openOutputStreamCalls+openFileDescriptorCalls;
  }

  void initRoot(String rootDocId){
   docs.put(rootDocId,new FakeDoc(rootDocId,null,"root",DocumentsContract.Document.MIME_TYPE_DIR,0,DocumentsContract.Document.FLAG_SUPPORTS_DELETE,null));
  }

  FakeDoc addFile(String id,String parentId,String name,byte[] bytes){
   FakeDoc d=new FakeDoc(id,parentId,name,"application/octet-stream",bytes.length,DocumentsContract.Document.FLAG_SUPPORTS_DELETE,bytes.clone());
   docs.put(id,d);
   return d;
  }

  private static String docIdFromUri(Uri uri){
   List<String> segs=uri.getPathSegments();
   if(segs.size()>=4&&"tree".equals(segs.get(0))&&"document".equals(segs.get(2)))return segs.get(3);
   if(segs.size()>=2&&"document".equals(segs.get(0)))return segs.get(1);
   throw new IllegalArgumentException("Invalid document URI: "+uri);
  }

  private static boolean isChildrenUri(Uri uri){
   List<String> segs=uri.getPathSegments();
   return segs.size()>=5&&"children".equals(segs.get(4));
  }

  private static Object[] toRow(FakeDoc d){
   return new Object[]{d.id,d.name,d.mime,d.size,d.flags};
  }

  @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder){
   queryCalls++;
   String id=docIdFromUri(uri);
   List<Object[]> rows=new ArrayList<>();
   if(isChildrenUri(uri)){
    if(!docs.containsKey(id))return null;
    for(FakeDoc d:docs.values())if(id.equals(d.parentId))rows.add(toRow(d));
   }else{
    FakeDoc d=docs.get(id);
    if(d!=null)rows.add(toRow(d));
   }
   return new RowsCursor(rows);
  }

  @Override public OutputStream openOutputStream(Uri uri)throws FileNotFoundException{
   openOutputStreamCalls++;
   String id=docIdFromUri(uri);
   FakeDoc d=docs.get(id);
   if(d==null)throw new FileNotFoundException(id);
   return new ByteArrayOutputStream(){
    @Override public void close()throws IOException{
     super.close();
     byte[] b=toByteArray();
     d.bytes=b;
     d.size=b.length;
    }
   };
  }

  @Override public InputStream openInputStream(Uri uri)throws FileNotFoundException{
   openInputStreamCalls++;
   String id=docIdFromUri(uri);
   FakeDoc d=docs.get(id);
   if(d==null||d.bytes==null)throw new FileNotFoundException(id);
   return new ByteArrayInputStream(d.bytes);
  }

  @Override public ParcelFileDescriptor openFileDescriptor(Uri uri,String mode)throws FileNotFoundException{
   openFileDescriptorCalls++;
   String id=docIdFromUri(uri);
   FakeDoc d=docs.get(id);
   if(d==null)throw new FileNotFoundException(id);
   if(d.throwSecurityOnOpenFd)throw new SecurityException("revoked");
   if(d.returnNullPfd)return null;
   FileDescriptor fd=new FileDescriptor();
   if(d.throwFstatError){
    Os.registerError(fd,new IOException("fstat-failed"));
   }else{
    long sz=d.statSizeOverride!=null?d.statSizeOverride.longValue():(d.bytes==null?0L:d.bytes.length);
    Os.registerStat(fd,new StructStat(d.mode,sz));
   }
   return new ParcelFileDescriptor(fd);
  }

  @Override public void takePersistableUriPermission(Uri uri,int modeFlags){
   takePersistableCalls++;
   lastTakeFlags=modeFlags;
   requireValidPersistableModeFlags(modeFlags);
   if(throwSecurityOnTakePersistable)throw new SecurityException("persist-denied");
   boolean r=(modeFlags&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0;
   boolean w=(modeFlags&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=0;
   grants.put(uri,new UriPermission(uri,r,w));
  }

  @Override public void releasePersistableUriPermission(Uri uri,int modeFlags){
   releasePersistableCalls++;
   lastReleaseFlags=modeFlags;
   requireValidPersistableModeFlags(modeFlags);
   grants.remove(uri);
  }

  @Override public List<UriPermission> getPersistedUriPermissions(){
   getPersistedCalls++;
   return new ArrayList<>(grants.values());
  }

  @Override public Uri createDocument(ContentResolver resolver,Uri parentDocumentUri,String mimeType,String displayName)throws FileNotFoundException{
   String parentId=docIdFromUri(parentDocumentUri);
   if(!docs.containsKey(parentId))throw new FileNotFoundException(parentId);
   String id=parentId+"/"+displayName+"#"+(seq++);
   boolean dir=DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType);
   FakeDoc d=new FakeDoc(id,parentId,displayName,mimeType,0,DocumentsContract.Document.FLAG_SUPPORTS_DELETE,dir?null:new byte[0]);
   d.mode=dir?(OsConstants.S_IFDIR|0700):(OsConstants.S_IFREG|0600);
   docs.put(id,d);
   List<String> segs=parentDocumentUri.getPathSegments();
   String treeId=segs.get(1);
   return Uri.parse(parentDocumentUri.getScheme()+"://"+parentDocumentUri.getAuthority()
    +"/tree/"+Uri.encode(treeId)+"/document/"+Uri.encode(id));
  }

  @Override public boolean deleteDocument(ContentResolver resolver,Uri documentUri)throws FileNotFoundException{
   String id=docIdFromUri(documentUri);
   return docs.remove(id)!=null;
  }
 }

 static final class FakeContext extends Context {
  private final File noBackupDir;
  private final ContentResolver resolver;
  FakeContext(File noBackupDir,ContentResolver resolver){
   this.noBackupDir=noBackupDir;this.resolver=resolver;
  }
  @Override public File getNoBackupFilesDir(){return noBackupDir;}
  @Override public ContentResolver getContentResolver(){return resolver;}
 }

 public static void main(String[] args)throws Exception{
  Path tmp=Files.createTempDirectory("awai-saf-android-boundary-");
  try{
   byte[] blob1=new byte[256];new Random(11).nextBytes(blob1);String h1=sha(blob1);
   String b64Blob1=Base64.encodeToString(blob1,Base64.NO_WRAP);
   int rwOnly=Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
   int rwPersist=rwOnly|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;

   String[] disallowedUris={
    "content://com.android.providers.downloads.documents/tree/downloads",
    "content://com.android.providers.media.documents/tree/image%3A123",
    "content://network.awai.drive.documents/tree/primary%3AAwai",
    "content://com.example.thirdparty.documents/tree/primary%3AAwai",
    "content://com.google.android.apps.docs.storage/tree/acc%3D1",
    "file:///tmp/cache",
    "content://com.android.externalstorage.documents/tree/primary%3ADownload%2F..%2Fsecret",
    "content://com.android.externalstorage.documents/tree/primary%3ADownload%2F%2e%2e%2Fsecret",
    "content://com.android.externalstorage.documents/tree/primary%3Aa%00b",
    "content://com.android.externalstorage.documents/tree/%3ADownload",
    "content://com.android.externalstorage.documents/document/primary%3ADownload"
   };

   // =========================================================================
   // 1) Stage 1: DriveSafSession.acceptPicked rejects disallowed authorities
   //    with "local-provider-required" and takePersistableUriPermission == 0,
   //    and accepts com.android.externalstorage.documents.
   // =========================================================================
   {
    File nbDir=tmp.resolve("nb-stage1").toFile();
    FakeResolver resolver=new FakeResolver();
    DocumentsContract.backend=resolver;
    Os.reset();
    DriveSafSession session=new DriveSafSession(new FakeContext(nbDir,resolver));

    for(String bad:disallowedUris){
     resolver.resetCounts();
     eq(code(()->{session.acceptPicked(Uri.parse(bad),rwPersist);return null;}),"local-provider-required");
     eq(resolver.takePersistableCalls,0);
     eq(resolver.totalResolverIoCalls(),0);
     eq(DocumentsContract.totalCalls(),0);
     yes(!new File(nbDir,"awai-drive-saf.v1.json").exists());
    }
    // Null URI rejected with 0 calls
    resolver.resetCounts();
    eq(code(()->{session.acceptPicked(null,rwPersist);return null;}),"local-provider-required");
    eq(resolver.takePersistableCalls,0);
    eq(DocumentsContract.totalCalls(),0);

    // Direct ContentResolver contract check: Android throws IllegalArgumentException
    // for any flag outside READ|WRITE (including FLAG_GRANT_PERSISTABLE_URI_PERMISSION) or 0.
    Uri primaryUri=Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FAwai");
    resolver.initRoot("primary:Download/Awai");
    expectIllegalArgument(()->resolver.takePersistableUriPermission(primaryUri,rwPersist));
    expectIllegalArgument(()->resolver.takePersistableUriPermission(primaryUri,0));
    expectIllegalArgument(()->resolver.takePersistableUriPermission(primaryUri,Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION));
    expectIllegalArgument(()->resolver.releasePersistableUriPermission(primaryUri,rwPersist));
    expectIllegalArgument(()->resolver.releasePersistableUriPermission(primaryUri,0));
    yes(resolver.grants.isEmpty());

    // Allowed authority with missing READ or WRITE flag -> persist-unsupported, 0 takePersistable calls
    int[] incompleteFlags={
     0,
     Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
     Intent.FLAG_GRANT_READ_URI_PERMISSION,
     Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
     Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
     Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    };
    for(int badFlags:incompleteFlags){
     resolver.resetCounts();
     eq(code(()->{session.acceptPicked(primaryUri,badFlags);return null;}),"persist-unsupported");
     eq(resolver.takePersistableCalls,0);
     eq(resolver.totalResolverIoCalls(),0);
     eq(DocumentsContract.buildDocumentUriCalls,0);
     eq(DocumentsContract.buildChildDocumentsUriCalls,0);
     eq(DocumentsContract.createDocumentCalls,0);
     eq(DocumentsContract.deleteDocumentCalls,0);
     yes(!new File(nbDir,"awai-drive-saf.v1.json").exists());
    }

    // Allowed authority when takePersistableUriPermission throws SecurityException -> persist-unsupported
    resolver.throwSecurityOnTakePersistable=true;
    resolver.resetCounts();
    eq(code(()->{session.acceptPicked(primaryUri,rwPersist);return null;}),"persist-unsupported");
    eq(resolver.takePersistableCalls,1);
    eq(resolver.lastTakeFlags,rwOnly);
    eq(resolver.lastTakeFlags&Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,0);
    eq(resolver.totalResolverIoCalls(),0);
    eq(DocumentsContract.buildDocumentUriCalls,0);
    eq(DocumentsContract.buildChildDocumentsUriCalls,0);
    eq(DocumentsContract.createDocumentCalls,0);
    eq(DocumentsContract.deleteDocumentCalls,0);
    yes(!new File(nbDir,"awai-drive-saf.v1.json").exists());
    resolver.throwSecurityOnTakePersistable=false;

    // Positive: acceptPicked on com.android.externalstorage.documents (primary:) passes ONLY READ|WRITE
    resolver.resetCounts();
    session.acceptPicked(primaryUri,rwPersist);
    eq(resolver.takePersistableCalls,1);
    eq(resolver.lastTakeFlags,rwOnly);
    eq(resolver.lastTakeFlags&Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,0);
    yes(new File(nbDir,"awai-drive-saf.v1.json").exists());
    JSONObject st=session.status();
    eq(st.getBoolean("picked"),true);
    eq(st.getString("state"),"ready");
    eq(st.getBoolean("clean"),true);

    // End-to-end cachePut -> cacheGet -> cacheVerify -> cacheClear -> cacheRelease
    JSONObject putRes=session.execute(new JSONObject().put("op","cachePut").put("sha256",h1).put("bytes",b64Blob1));
    eq(putRes.getString("result"),"stored");
    JSONObject getRes=session.execute(new JSONObject().put("op","cacheGet").put("sha256",h1));
    yes(Arrays.equals(Base64.decode(getRes.getString("bytes"),Base64.NO_WRAP),blob1));
    JSONObject verRes=session.execute(new JSONObject().put("op","cacheVerify"));
    eq(verRes.getInt("verified"),1);
    eq(verRes.getBoolean("clean"),true);
    JSONObject clrRes=session.execute(new JSONObject().put("op","cacheClear"));
    eq(clrRes.getInt("deleted"),1);
    eq(clrRes.getBoolean("ownerPreserved"),true);
    JSONObject relRes=session.execute(new JSONObject().put("op","cacheRelease"));
    eq(relRes.getBoolean("released"),true);
    eq(resolver.releasePersistableCalls,1);
    eq(resolver.lastReleaseFlags,rwOnly);
    eq(resolver.lastReleaseFlags&Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,0);
    yes(!new File(nbDir,"awai-drive-saf.v1.json").exists());

    // Positive: removable volume XXXX-XXXX: also accepted with READ|WRITE modeFlags only
    Uri removableUri=Uri.parse("content://com.android.externalstorage.documents/tree/1234-5678%3AAwaiCache");
    resolver.initRoot("1234-5678:AwaiCache");
    resolver.resetCounts();
    session.acceptPicked(removableUri,rwPersist);
    eq(resolver.takePersistableCalls,1);
    eq(resolver.lastTakeFlags,rwOnly);
    eq(resolver.lastTakeFlags&Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,0);
    eq(session.status().getString("state"),"ready");
   }

   // =========================================================================
   // 2) DriveSafDocuments rejects disallowed authorities on all 7 DriveSaf.Tree
   //    methods with "local-provider-required" and 0 ContentResolver /
   //    DocumentsContract calls.
   // =========================================================================
   {
    FakeResolver resolver=new FakeResolver();
    DocumentsContract.backend=resolver;
    Os.reset();
    DriveSaf.Item dummyItem=new DriveSaf.Item(h1,"doc-1",false,false,true,blob1.length);

    for(String bad:disallowedUris){
     resolver.resetCounts();
     DriveSafDocuments docs=new DriveSafDocuments(resolver,Uri.parse(bad));
     eq(code(()->{docs.locate();return null;}),"local-provider-required");
     eq(code(()->{docs.ensure();return null;}),"local-provider-required");
     eq(code(()->docs.children()),"local-provider-required");
     eq(code(()->docs.read(dummyItem)),"local-provider-required");
     eq(code(()->docs.create(h1,blob1)),"local-provider-required");
     eq(code(()->docs.confirmRegularFile(dummyItem)),"local-provider-required");
     eq(code(()->{docs.delete(dummyItem);return null;}),"local-provider-required");
     eq(code(()->docs.confirmRegularFile("doc-1",blob1.length)),"local-provider-required");
     eq(resolver.queryCalls,0);
     eq(resolver.openInputStreamCalls,0);
     eq(resolver.openOutputStreamCalls,0);
     eq(resolver.openFileDescriptorCalls,0);
     eq(DocumentsContract.createDocumentCalls,0);
     eq(DocumentsContract.deleteDocumentCalls,0);
     eq(DocumentsContract.totalCalls(),0);
    }
   }

   // =========================================================================
   // 3) Stage 2: Tampered persisted awai-drive-saf.v1.json containing a
   //    disallowed authority fails with "local-provider-required" on status,
   //    cachePut, cacheGet, cacheVerify, cacheClear with 0 DocumentsContract
   //    calls and 0 deletions; cacheRelease deletes ONLY the local record and
   //    makes 0 DocumentsContract.deleteDocument calls.
   // =========================================================================
   {
    String[] tamperedTrees={
     "content://com.android.providers.downloads.documents/tree/downloads",
     "content://com.android.providers.media.documents/tree/image%3A123",
     "content://network.awai.drive.documents/tree/primary%3AAwai",
     "content://com.example.cloud.documents/tree/primary%3AAwai",
     "content://com.android.externalstorage.documents/tree/primary%3ADownload%2F%2e%2e%2Fsecret"
    };
    int caseIdx=0;
    for(String badTree:tamperedTrees){
     File nbDir=tmp.resolve("nb-tampered-"+(caseIdx++)).toFile();
     nbDir.mkdirs();
     String instId=DriveSafRecord.newInstanceId();
     File recFile=new File(nbDir,"awai-drive-saf.v1.json");
     Files.write(recFile.toPath(),DriveSafRecord.encode(badTree,instId));

     FakeResolver resolver=new FakeResolver();
     // Even if a persisted grant somehow exists for the tampered URI:
     Uri badUri=Uri.parse(badTree);
     resolver.grants.put(badUri,new UriPermission(badUri,true,true));
     DocumentsContract.backend=resolver;
     Os.reset();
     DriveSafSession session=new DriveSafSession(new FakeContext(nbDir,resolver));

     resolver.resetCounts();
     eq(code(()->session.status()),"local-provider-required");
     eq(code(()->session.execute(new JSONObject().put("op","cacheStatus"))),"local-provider-required");
     eq(code(()->session.execute(new JSONObject().put("op","cachePut").put("sha256",h1).put("bytes",b64Blob1))),"local-provider-required");
     eq(code(()->session.execute(new JSONObject().put("op","cacheGet").put("sha256",h1))),"local-provider-required");
     eq(code(()->session.execute(new JSONObject().put("op","cacheVerify"))),"local-provider-required");
     eq(code(()->session.execute(new JSONObject().put("op","cacheClear"))),"local-provider-required");
     eq(resolver.queryCalls,0);
     eq(resolver.totalResolverIoCalls(),0);
     eq(DocumentsContract.totalCalls(),0);
     eq(DocumentsContract.deleteDocumentCalls,0);
     yes(recFile.exists());

     // cacheRelease MUST delete only the local record & release grant with 0 DocumentsContract.deleteDocument calls!
     resolver.resetCounts();
     JSONObject rel=session.execute(new JSONObject().put("op","cacheRelease"));
     eq(rel.getBoolean("released"),true);
     yes(!recFile.exists());
     eq(resolver.releasePersistableCalls,1);
     eq(resolver.lastReleaseFlags,rwOnly);
     eq(resolver.totalResolverIoCalls(),0);
     eq(DocumentsContract.deleteDocumentCalls,0);
     eq(DocumentsContract.totalCalls(),0);
    }
   }

   // =========================================================================
   // 4) DriveSafDocuments.confirmRegularFile fail-closed behavior
   // =========================================================================
   {
    FakeResolver resolver=new FakeResolver();
    DocumentsContract.backend=resolver;
    Os.reset();
    Uri okTree=Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FAwai");
    resolver.initRoot("primary:Download/Awai");
    DriveSafDocuments docs=new DriveSafDocuments(resolver,okTree);

    // 4a) Valid regular file with matching st_size -> true
    FakeDoc reg=resolver.addFile("doc-reg","primary:Download/Awai",h1,blob1);
    reg.mode=OsConstants.S_IFREG|0600;
    yes(docs.confirmRegularFile("doc-reg",blob1.length));
    yes(docs.confirmRegularFile("doc-reg",-1L)); // unknown expected size (-1) allowed when S_ISREG == true

    // 4b) Symlink mode (S_ISREG == false) -> false
    FakeDoc sym=resolver.addFile("doc-sym","primary:Download/Awai","1".repeat(64),blob1);
    sym.mode=OsConstants.S_IFLNK|0777;
    yes(!docs.confirmRegularFile("doc-sym",blob1.length));

    // 4c) FIFO mode (S_ISREG == false) -> false
    FakeDoc fifo=resolver.addFile("doc-fifo","primary:Download/Awai","2".repeat(64),blob1);
    fifo.mode=OsConstants.S_IFIFO|0600;
    yes(!docs.confirmRegularFile("doc-fifo",blob1.length));

    // 4d) Directory mode (S_ISREG == false) -> false
    FakeDoc dir=resolver.addFile("doc-dir","primary:Download/Awai","3".repeat(64),blob1);
    dir.mode=OsConstants.S_IFDIR|0755;
    yes(!docs.confirmRegularFile("doc-dir",blob1.length));

    // 4e) st_size != expectedSize -> false
    FakeDoc szMis=resolver.addFile("doc-szmis","primary:Download/Awai","4".repeat(64),blob1);
    szMis.mode=OsConstants.S_IFREG|0600;
    szMis.statSizeOverride=(long)(blob1.length+1);
    yes(!docs.confirmRegularFile("doc-szmis",blob1.length));

    // 4f) openFileDescriptor returns null -> false
    FakeDoc nullPfd=resolver.addFile("doc-nullpfd","primary:Download/Awai","5".repeat(64),blob1);
    nullPfd.returnNullPfd=true;
    yes(!docs.confirmRegularFile("doc-nullpfd",blob1.length));

    // 4g) Os.fstat throws exception -> false
    FakeDoc fstatErr=resolver.addFile("doc-fstaterr","primary:Download/Awai","6".repeat(64),blob1);
    fstatErr.throwFstatError=true;
    yes(!docs.confirmRegularFile("doc-fstaterr",blob1.length));

    // 4h) Missing doc (FileNotFoundException) -> false
    yes(!docs.confirmRegularFile("doc-missing",blob1.length));

    // 4i) SecurityException from openFileDescriptor -> IOException("permission-lost")
    FakeDoc secErr=resolver.addFile("doc-sec","primary:Download/Awai","7".repeat(64),blob1);
    secErr.throwSecurityOnOpenFd=true;
    eq(code(()->docs.confirmRegularFile("doc-sec",blob1.length)),"permission-lost");
   }

   // =========================================================================
   // 5) DriveSafDocuments.java source contains Honest Non-Goal disclosure
   // =========================================================================
   {
    Path docSrc=args.length>0
     ?Path.of(args[0])
     :Path.of("src-tauri/gen/android/app/src/main/java/network/awai/drive/DriveSafDocuments.java");
    if(!Files.exists(docSrc)){
     docSrc=Path.of("tauri/src-tauri/gen/android/app/src/main/java/network/awai/drive/DriveSafDocuments.java");
    }
    yes(Files.isRegularFile(docSrc));
    String src=Files.readString(docSrc,StandardCharsets.UTF_8);
    yes(src.contains("Honest Non-Goal"));
    yes(src.contains("lstat(2)"));
    yes(src.contains("O_NOFOLLOW"));
    yes(src.contains("COLUMN_IS_SYMLINK"));
    yes(src.contains("com.android.externalstorage.documents"));
    yes(src.contains("Os.fstat"));
   }

   System.out.println("DriveSafAndroidBoundaryTest: "+checks+" checks passed: Stage 1 acceptPicked allowlist + strict READ|WRITE persistable modeFlags, DriveSafDocuments zero-call rejection, Stage 2 tampered-record fail-closed + safe cacheRelease, confirmRegularFile S_ISREG/st_size/null-PFD/SecurityException, Honest Non-Goal disclosure.");
  }finally{
   deleteTree(tmp);
  }
 }
}
