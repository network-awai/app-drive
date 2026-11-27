import network.awai.drive.DriveSaf;
import network.awai.drive.DriveSafPolicy;
import network.awai.drive.DriveSafRecord;
import network.awai.drive.DriveSafSafAdapter;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.Callable;

/** JVM adapter-boundary tests for DriveSafPolicy + DriveSafSafAdapter + DriveSaf
 *  covering AWAI-SPEC-REG-SAF-B1-v0.2 §2.3 strict ExternalStorageProvider
 *  allowlist, pinned cache doc IDs, unambiguous root scans, pinned child identity,
 *  regular-file & FLAG_SUPPORTS_DELETE confirmation, duplicate display names,
 *  symlink/virtual/malformed metadata preservation, oversize non-deletion,
 *  and end-to-end happy path. */
public class DriveSafDocumentsTest {
 static int checks=0;
 static void yes(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 static void eq(Object a,Object b){checks++;if(!Objects.equals(a,b))throw new AssertionError("check "+checks+" expected "+b+" got "+a);}
 static String code(Callable<?> f)throws Exception{
  checks++;
  try{f.call();throw new AssertionError("check "+checks+" accepted forbidden input");}
  catch(IOException e){return e.getMessage();}
 }
 static String sha(byte[] b)throws Exception{
  return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
 }

 static final class Node {
  String id,parentId,name,mime;
  long reportedSize;
  Integer flags;
  byte[] bytes;
  boolean regularFd=true;
  Long statSizeOverride=null;
  Node(String id,String parentId,String name,String mime,long reportedSize,Integer flags,byte[] bytes){
   this.id=id;this.parentId=parentId;this.name=name;this.mime=mime;
   this.reportedSize=reportedSize;this.flags=flags;this.bytes=bytes;
  }
  DriveSafSafAdapter.Doc toDoc(){return new DriveSafSafAdapter.Doc(id,name,mime,reportedSize,flags);}
 }

 static final class MockProvider implements DriveSafSafAdapter.Provider {
  static final String DEFAULT_TREE_URI="content://com.android.externalstorage.documents/tree/primary%3ADownload%2FAwai";
  String treeUri=DEFAULT_TREE_URI;
  final String root="root-0";
  final Map<String,Node> docs=new LinkedHashMap<>();
  final List<String> queryDocCalls=new ArrayList<>();
  final List<String> listChildrenCalls=new ArrayList<>();
  final List<String> createDocCalls=new ArrayList<>();
  final List<String> writeDocCalls=new ArrayList<>();
  final List<String> openReadCalls=new ArrayList<>();
  final List<String> confirmRegularFileCalls=new ArrayList<>();
  final List<String> deleteDocCalls=new ArrayList<>();
  int seq=1;
  Runnable onAfterOpenReadOwner;

  MockProvider(){
   docs.put(root,new Node(root,null,"tree-root",DriveSafSafAdapter.MIME_TYPE_DIR,0,DriveSafSafAdapter.FLAG_SUPPORTS_DELETE,null));
  }
  MockProvider(String treeUri){
   this();
   this.treeUri=treeUri;
  }
  void resetCalls(){
   queryDocCalls.clear();listChildrenCalls.clear();createDocCalls.clear();
   writeDocCalls.clear();openReadCalls.clear();confirmRegularFileCalls.clear();deleteDocCalls.clear();
  }
  int totalIoCalls(){
   return queryDocCalls.size()+listChildrenCalls.size()+createDocCalls.size()
    +writeDocCalls.size()+openReadCalls.size()+confirmRegularFileCalls.size()+deleteDocCalls.size();
  }
  Node add(String id,String parentId,String name,String mime,long reportedSize,Integer flags,byte[] bytes){
   Node n=new Node(id,parentId,name,mime,reportedSize,flags,bytes);
   docs.put(id,n);
   return n;
  }
  Node addDir(String id,String parentId,String name,Integer flags){
   return add(id,parentId,name,DriveSafSafAdapter.MIME_TYPE_DIR,0,flags,null);
  }
  Node addFile(String id,String parentId,String name,byte[] bytes){
   return add(id,parentId,name,"application/octet-stream",bytes.length,DriveSafSafAdapter.FLAG_SUPPORTS_DELETE,bytes);
  }
  @Override public String treeUri(){return treeUri;}
  @Override public String rootId(){return root;}
  @Override public DriveSafSafAdapter.Doc queryDoc(String docId){
   queryDocCalls.add(docId);
   Node n=docs.get(docId);
   return n==null?null:n.toDoc();
  }
  @Override public List<DriveSafSafAdapter.Doc> listChildren(String parentId){
   listChildrenCalls.add(parentId);
   if(!docs.containsKey(parentId))return null;
   List<DriveSafSafAdapter.Doc> out=new ArrayList<>();
   for(Node n:docs.values())if(parentId.equals(n.parentId))out.add(n.toDoc());
   return out;
  }
  @Override public DriveSafSafAdapter.Doc createDoc(String parentId,String mime,String name)throws IOException{
   createDocCalls.add(parentId+"/"+name);
   if(!docs.containsKey(parentId))throw new IOException("cache-absent");
   String id="doc-"+(seq++);
   Node n=new Node(id,parentId,name,mime,0,DriveSafSafAdapter.FLAG_SUPPORTS_DELETE,new byte[0]);
   docs.put(id,n);
   return n.toDoc();
  }
  @Override public void writeDoc(String docId,byte[] data)throws IOException{
   writeDocCalls.add(docId);
   Node n=docs.get(docId);
   if(n==null)throw new IOException("storage-unavailable");
   n.bytes=data.clone();
   n.reportedSize=data.length;
  }
  @Override public InputStream openRead(String docId)throws IOException{
   openReadCalls.add(docId);
   Node n=docs.get(docId);
   if(n==null||n.bytes==null)throw new IOException("storage-unavailable");
   if(DriveSaf.OWNER_FILE.equals(n.name)&&onAfterOpenReadOwner!=null){
    Runnable hook=onAfterOpenReadOwner;
    onAfterOpenReadOwner=null;
    hook.run();
   }
   return new ByteArrayInputStream(n.bytes);
  }
  @Override public boolean confirmRegularFile(String docId,long expectedSize){
   confirmRegularFileCalls.add(docId);
   Node n=docs.get(docId);
   if(n==null||!n.regularFd||n.bytes==null)return false;
   long actualSize=n.statSizeOverride!=null?n.statSizeOverride.longValue():n.bytes.length;
   return expectedSize<0||actualSize==expectedSize;
  }
  @Override public void deleteDoc(String docId)throws IOException{
   deleteDocCalls.add(docId);
   if(docs.remove(docId)==null)throw new IOException("storage-unavailable");
  }
 }

 static DriveSaf.Item findById(DriveSaf.Item[] items,String id){
  for(DriveSaf.Item it:items)if(id.equals(it.id))return it;
  throw new AssertionError("missing item id "+id);
 }

 public static void main(String[] args)throws Exception{
  String instanceId=DriveSafRecord.newInstanceId();
  byte[] blob1=new byte[256];new Random(1).nextBytes(blob1);String h1=sha(blob1);
  byte[] blob2=new byte[128];new Random(2).nextBytes(blob2);String h2=sha(blob2);
  byte[] badBytes=blob1.clone();badBytes[0]^=0x42;

  // --- Scenario 1A: Pinned cache doc ID deleted & replaced with cache-2 at root between verifyOwner and delete ---
  {
   MockProvider p=new MockProvider();
   DriveSafSafAdapter t=new DriveSafSafAdapter(p);
   DriveSaf.adopt(t,instanceId);
   String pinned=t.pinnedCacheId();
   yes(pinned!=null);
   p.addFile("bad-doc",pinned,h1,badBytes);
   p.resetCalls();
   p.onAfterOpenReadOwner=()->{
    // Swap cache-1 out and insert a brand new cache-2 at root with its own doc!
    p.docs.remove(pinned);
    p.addDir("cache-2",p.root,DriveSaf.DIR,DriveSafSafAdapter.FLAG_SUPPORTS_DELETE);
    p.addFile("victim-doc","cache-2",h1,blob1);
   };
   eq(code(()->DriveSaf.verify(t,instanceId)),"cache-absent");
   eq(p.deleteDocCalls.size(),0);
   eq(t.pinnedCacheId(),pinned); // NEVER rebound to cache-2!
   yes(p.docs.containsKey("victim-doc"));
   // Subsequent locate/clear on same adapter STILL fails closed and NEVER rebinds to cache-2!
   eq(code(()->DriveSaf.clear(t,instanceId)),"cache-absent");
   eq(p.deleteDocCalls.size(),0);
   eq(t.pinnedCacheId(),pinned);
  }

  // --- Scenario 1B: Pinned cache doc ID moved out of root & replaced with cache-2 between verifyOwner and delete ---
  {
   MockProvider p=new MockProvider();
   DriveSafSafAdapter t=new DriveSafSafAdapter(p);
   DriveSaf.adopt(t,instanceId);
   String pinned=t.pinnedCacheId();
   p.addFile("blob-doc",pinned,h1,blob1);
   p.resetCalls();
   p.onAfterOpenReadOwner=()->{
    p.docs.get(pinned).parentId="elsewhere";
    p.addDir("cache-2",p.root,DriveSaf.DIR,DriveSafSafAdapter.FLAG_SUPPORTS_DELETE);
    p.addFile("victim-doc","cache-2",h1,blob1);
   };
   eq(code(()->DriveSaf.clear(t,instanceId)),"foreign-directory-refused");
   eq(p.deleteDocCalls.size(),0);
   eq(t.pinnedCacheId(),pinned);
   yes(p.docs.containsKey("victim-doc"));
  }

  // --- Scenario 2: Duplicate "cache" entries or non-dir/virtual "cache" at root -> foreign-directory-refused, ZERO mutations ---
  {
   MockProvider p=new MockProvider();
   p.addDir("cache-a",p.root,DriveSaf.DIR,DriveSafSafAdapter.FLAG_SUPPORTS_DELETE);
   p.addDir("cache-b",p.root,DriveSaf.DIR,DriveSafSafAdapter.FLAG_SUPPORTS_DELETE);
   DriveSafSafAdapter t=new DriveSafSafAdapter(p);
   p.resetCalls();
   eq(code(()->DriveSaf.adopt(t,instanceId)),"foreign-directory-refused");
   eq(code(()->DriveSaf.inspect(t,instanceId,false)),"foreign-directory-refused");
   eq(code(()->DriveSaf.clear(t,instanceId)),"foreign-directory-refused");
   eq(p.createDocCalls.size(),0);eq(p.writeDocCalls.size(),0);eq(p.deleteDocCalls.size(),0);

   // Non-directory "cache" at root:
   MockProvider pFile=new MockProvider();
   pFile.addFile("cache-file",pFile.root,DriveSaf.DIR,new byte[]{1,2,3});
   DriveSafSafAdapter tFile=new DriveSafSafAdapter(pFile);
   pFile.resetCalls();
   eq(code(()->DriveSaf.adopt(tFile,instanceId)),"foreign-directory-refused");
   eq(pFile.createDocCalls.size(),0);eq(pFile.writeDocCalls.size(),0);eq(pFile.deleteDocCalls.size(),0);

   // Virtual directory "cache" at root:
   MockProvider pVirt=new MockProvider();
   pVirt.addDir("cache-virt",pVirt.root,DriveSaf.DIR,DriveSafSafAdapter.FLAG_SUPPORTS_DELETE|DriveSafSafAdapter.FLAG_VIRTUAL_DOCUMENT);
   DriveSafSafAdapter tVirt=new DriveSafSafAdapter(pVirt);
   pVirt.resetCalls();
   eq(code(()->DriveSaf.adopt(tVirt,instanceId)),"foreign-directory-refused");
   eq(pVirt.createDocCalls.size(),0);eq(pVirt.writeDocCalls.size(),0);eq(pVirt.deleteDocCalls.size(),0);

   // Null-flags "cache" at root:
   MockProvider pNullFlags=new MockProvider();
   pNullFlags.addDir("cache-null-flags",pNullFlags.root,DriveSaf.DIR,null);
   DriveSafSafAdapter tNullFlags=new DriveSafSafAdapter(pNullFlags);
   pNullFlags.resetCalls();
   eq(code(()->DriveSaf.adopt(tNullFlags,instanceId)),"foreign-directory-refused");
   eq(pNullFlags.createDocCalls.size(),0);eq(pNullFlags.writeDocCalls.size(),0);eq(pNullFlags.deleteDocCalls.size(),0);
  }

  // --- Scenario 3: Child doc ID swapped (same 64-hex name, different doc ID) between children() and delete(item) ---
  {
   MockProvider p=new MockProvider();
   DriveSafSafAdapter t=new DriveSafSafAdapter(p);
   DriveSaf.adopt(t,instanceId);
   String cacheId=t.pinnedCacheId();
   p.addFile("orig-id",cacheId,h1,blob1);
   DriveSaf.Item origItem=findById(t.children(),"orig-id");
   // Swap orig-id out for new-id with the exact same 64-hex display name!
   p.docs.remove("orig-id");
   p.addFile("replacement-id",cacheId,h1,blob2);
   p.resetCalls();
   eq(code(()->{t.delete(origItem);return null;}),"foreign-directory-refused");
   eq(code(()->t.read(origItem)),"foreign-directory-refused");
   eq(code(()->t.confirmRegularFile(origItem)),"foreign-directory-refused");
   eq(p.deleteDocCalls.size(),0);
   yes(p.docs.containsKey("replacement-id"));
  }

  // --- Scenario 4: Child doc renamed / moved / MIME changed / virtual / lost FLAG_SUPPORTS_DELETE / confirmRegularFile failed between children() and delete(item) ---
  {
   MockProvider p=new MockProvider();
   DriveSafSafAdapter t=new DriveSafSafAdapter(p);
   DriveSaf.adopt(t,instanceId);
   String cacheId=t.pinnedCacheId();

   // 4a) Renamed
   Node nRen=p.addFile("d-ren",cacheId,h1,blob1);
   DriveSaf.Item itRen=findById(t.children(),"d-ren");
   nRen.name="important-user-doc.pdf";
   p.resetCalls();
   eq(code(()->{t.delete(itRen);return null;}),"foreign-directory-refused");
   eq(p.deleteDocCalls.size(),0);

   // 4b) Moved out of cache/
   Node nMov=p.addFile("d-mov",cacheId,h2,blob2);
   DriveSaf.Item itMov=findById(t.children(),"d-mov");
   nMov.parentId=p.root;
   p.resetCalls();
   eq(code(()->{t.delete(itMov);return null;}),"foreign-directory-refused");
   eq(p.deleteDocCalls.size(),0);

   // 4c) Changed MIME to directory
   Node nDir=p.addFile("d-dir",cacheId,"a".repeat(64),blob1);
   DriveSaf.Item itDir=findById(t.children(),"d-dir");
   nDir.mime=DriveSafSafAdapter.MIME_TYPE_DIR;
   p.resetCalls();
   eq(code(()->{t.delete(itDir);return null;}),"foreign-directory-refused");
   eq(p.deleteDocCalls.size(),0);

   // 4d) Changed MIME to symlink
   Node nSym=p.addFile("d-sym",cacheId,"b".repeat(64),blob1);
   DriveSaf.Item itSym=findById(t.children(),"d-sym");
   nSym.mime="inode/symlink";
   p.resetCalls();
   eq(code(()->{t.delete(itSym);return null;}),"foreign-directory-refused");
   eq(p.deleteDocCalls.size(),0);

   // 4e) Became FLAG_VIRTUAL_DOCUMENT
   Node nVirt=p.addFile("d-virt",cacheId,"c".repeat(64),blob1);
   DriveSaf.Item itVirt=findById(t.children(),"d-virt");
   nVirt.flags=DriveSafSafAdapter.FLAG_SUPPORTS_DELETE|DriveSafSafAdapter.FLAG_VIRTUAL_DOCUMENT;
   p.resetCalls();
   eq(code(()->{t.delete(itVirt);return null;}),"foreign-directory-refused");
   eq(p.deleteDocCalls.size(),0);

   // 4f) Lost FLAG_SUPPORTS_DELETE
   Node nNoDel=p.addFile("d-nodel",cacheId,"d".repeat(64),blob1);
   DriveSaf.Item itNoDel=findById(t.children(),"d-nodel");
   nNoDel.flags=0;
   p.resetCalls();
   eq(code(()->{t.delete(itNoDel);return null;}),"foreign-directory-refused");
   eq(p.deleteDocCalls.size(),0);

   // 4g) Failed confirmRegularFile (S_ISREG == false)
   Node nBadFd=p.addFile("d-badfd",cacheId,"e".repeat(64),blob1);
   DriveSaf.Item itBadFd=findById(t.children(),"d-badfd");
   nBadFd.regularFd=false;
   p.resetCalls();
   eq(code(()->{t.delete(itBadFd);return null;}),"foreign-directory-refused");
   eq(p.deleteDocCalls.size(),0);

   // 4h) Failed confirmRegularFile (fstat st_size mismatch vs provider metadata size)
   Node nSizeMis=p.addFile("d-sizemis",cacheId,"f".repeat(64),blob1);
   DriveSaf.Item itSizeMis=findById(t.children(),"d-sizemis");
   nSizeMis.statSizeOverride=(long)(blob1.length+5);
   p.resetCalls();
   eq(code(()->{t.delete(itSizeMis);return null;}),"foreign-directory-refused");
   eq(p.deleteDocCalls.size(),0);
  }

  // --- Scenario 5: Duplicate display names inside cache/ (a directory AND a regular file sharing a 64-hex name) ---
  {
   MockProvider p=new MockProvider();
   DriveSafSafAdapter t=new DriveSafSafAdapter(p);
   DriveSaf.adopt(t,instanceId);
   String cacheId=t.pinnedCacheId();
   p.addDir("dup-dir-id",cacheId,h1,DriveSafSafAdapter.FLAG_SUPPORTS_DELETE);
   p.addFile("dup-file-id",cacheId,h1,blob1);
   p.resetCalls();
   DriveSaf.ClearReport cr=DriveSaf.clearDetailed(t,instanceId);
   eq(cr.deleted,1);
   eq(cr.preservedForeign,1);
   yes(cr.ownerPreserved);
   eq(p.deleteDocCalls,List.of("dup-file-id")); // ONLY regular file's doc ID deleted!
   yes(p.docs.containsKey("dup-dir-id"));        // Directory doc ID untouched!
   yes(!p.docs.containsKey("dup-file-id"));
  }

  // --- Scenario 6: Symlink MIMEs, vnd.android.document/*, null/empty MIME, null flags, FLAG_VIRTUAL_DOCUMENT, confirmRegularFile==false ---
  {
   MockProvider p=new MockProvider();
   DriveSafSafAdapter t=new DriveSafSafAdapter(p);
   DriveSaf.adopt(t,instanceId);
   String cacheId=t.pinnedCacheId();
   p.add("s1",cacheId,"1".repeat(64),"inode/symlink",10,DriveSafSafAdapter.FLAG_SUPPORTS_DELETE,new byte[10]);
   p.add("s2",cacheId,"2".repeat(64),"application/x-symlink",10,DriveSafSafAdapter.FLAG_SUPPORTS_DELETE,new byte[10]);
   p.add("s3",cacheId,"3".repeat(64),"INODE/FIFO",10,DriveSafSafAdapter.FLAG_SUPPORTS_DELETE,new byte[10]);
   p.add("s4",cacheId,"4".repeat(64),"vnd.android.document/custom",10,DriveSafSafAdapter.FLAG_SUPPORTS_DELETE,new byte[10]);
   p.add("s5",cacheId,"5".repeat(64),null,10,DriveSafSafAdapter.FLAG_SUPPORTS_DELETE,new byte[10]);
   p.add("s6",cacheId,"6".repeat(64),"",10,DriveSafSafAdapter.FLAG_SUPPORTS_DELETE,new byte[10]);
   p.add("s7",cacheId,"7".repeat(64),"application/octet-stream",10,null,new byte[10]);
   p.add("s8",cacheId,"8".repeat(64),"application/octet-stream",10,DriveSafSafAdapter.FLAG_SUPPORTS_DELETE|DriveSafSafAdapter.FLAG_VIRTUAL_DOCUMENT,new byte[10]);
   Node s9=p.addFile("s9",cacheId,"9".repeat(64),new byte[10]);
   s9.regularFd=false; // confirmRegularFile == false (e.g. S_ISREG == false)

   DriveSaf.Item[] kids=t.children();
   yes(findById(kids,"s1").symlink&&!findById(kids,"s1").regularFile());
   yes(findById(kids,"s2").symlink&&!findById(kids,"s2").regularFile());
   yes(findById(kids,"s3").symlink&&!findById(kids,"s3").regularFile());
   yes(!findById(kids,"s4").regularFile());
   yes(!findById(kids,"s5").regularFile());
   yes(!findById(kids,"s6").regularFile());
   yes(!findById(kids,"s7").regularFile());
   yes(!findById(kids,"s8").regularFile());
   yes(findById(kids,"s9").regularFile()); // metadata looks regular, caught by confirmRegularFile!

   p.resetCalls();
   DriveSaf.Report vrep=DriveSaf.verify(t,instanceId);
   eq(vrep.unexpected,9);
   eq(vrep.blobs,0);eq(vrep.corrupt,0);eq(vrep.removed,0);eq(vrep.clean,false);
   eq(p.deleteDocCalls.size(),0);

   DriveSaf.ClearReport cr=DriveSaf.clearDetailed(t,instanceId);
   eq(cr.deleted,0);
   eq(cr.preservedForeign,9);
   yes(cr.ownerPreserved);
   eq(p.deleteDocCalls.size(),0);
   for(int i=1;i<=9;i++)yes(p.docs.containsKey("s"+i));
  }

  // --- Scenario 7: Oversize entries through DriveSafSafAdapter are NEVER deleted ---
  {
   MockProvider p=new MockProvider();
   DriveSafSafAdapter t=new DriveSafSafAdapter(p);
   DriveSaf.adopt(t,instanceId);
   String cacheId=t.pinnedCacheId();
   // 7a) Metadata claims MAX_BLOB_BYTES + 1 while actual bytes are valid small blob1 (with matching statSizeOverride so confirmRegularFile passes).
   Node metaOver=p.add("over-meta",cacheId,h1,"application/octet-stream",DriveSaf.MAX_BLOB_BYTES+1,DriveSafSafAdapter.FLAG_SUPPORTS_DELETE,blob1);
   metaOver.statSizeOverride=(long)(DriveSaf.MAX_BLOB_BYTES+1);
   // 7b) Stream exceeds MAX_BLOB_BYTES with unknown metadata size (-1).
   String bigSha="a".repeat(64);
   p.add("over-stream",cacheId,bigSha,"application/octet-stream",-1L,DriveSafSafAdapter.FLAG_SUPPORTS_DELETE,new byte[DriveSaf.MAX_BLOB_BYTES+1]);

   p.resetCalls();
   DriveSaf.Report vrep=DriveSaf.verify(t,instanceId);
   eq(vrep.blobs,2);eq(vrep.verified,0);eq(vrep.corrupt,0);eq(vrep.removed,0);
   eq(vrep.oversize,2);eq(vrep.clean,false);
   eq(p.deleteDocCalls.size(),0);
   yes(p.docs.containsKey("over-meta")&&p.docs.containsKey("over-stream"));

   eq(code(()->DriveSaf.get(t,instanceId,h1)),"cache-corrupt");
   eq(code(()->DriveSaf.put(t,instanceId,h1,blob1)),"cache-corrupt");
   eq(code(()->DriveSaf.get(t,instanceId,bigSha)),"cache-corrupt");
   eq(p.deleteDocCalls.size(),0);
   yes(p.docs.containsKey("over-meta")&&p.docs.containsKey("over-stream"));
  }

  // --- Scenario 8: End-to-end happy path (adopt -> put -> get -> verify -> clear -> release) ---
  {
   MockProvider p=new MockProvider();
   DriveSafSafAdapter t=new DriveSafSafAdapter(p);
   DriveSaf.Item marker=DriveSaf.adopt(t,instanceId);
   yes(marker!=null&&marker.id!=null&&DriveSaf.OWNER_FILE.equals(marker.name));
   yes(DriveSaf.adopt(t,instanceId)==null); // idempotent re-adopt

   eq(DriveSaf.put(t,instanceId,h1,blob1),"stored");
   eq(DriveSaf.put(t,instanceId,h1,blob1),"present");
   eq(DriveSaf.put(t,instanceId,h2,blob2),"stored");
   yes(Arrays.equals(DriveSaf.get(t,instanceId,h1),blob1));
   yes(Arrays.equals(DriveSaf.get(t,instanceId,h2),blob2));

   DriveSaf.Report vrep=DriveSaf.verify(t,instanceId);
   eq(vrep.blobs,2);eq(vrep.verified,2);eq(vrep.corrupt,0);eq(vrep.oversize,0);eq(vrep.unreadable,0);eq(vrep.removed,0);eq(vrep.unexpected,0);eq(vrep.clean,true);

   // Add one foreign file to prove clear & release preserve it
   p.addFile("foreign-note",t.pinnedCacheId(),"notes.txt",new byte[]{7,8,9});
   DriveSaf.ClearReport cr=DriveSaf.clearDetailed(t,instanceId);
   eq(cr.deleted,2);eq(cr.preservedForeign,1);yes(cr.ownerPreserved);
   yes(p.docs.containsKey(marker.id));
   yes(p.docs.containsKey("foreign-note"));

   eq(DriveSaf.put(t,instanceId,h1,blob1),"stored");
   eq(DriveSaf.release(t,instanceId),1);
   yes(!p.docs.containsKey(marker.id));
   yes(p.docs.containsKey("foreign-note"));
  }

  // --- Scenario 9: DriveSafPolicy + DriveSafSafAdapter strict allowlist (AWAI-SPEC-REG-SAF-B1-v0.2 §2.3) ---
  {
   eq(DriveSafPolicy.ALLOWED_SCHEME,"content");
   eq(DriveSafPolicy.ALLOWED_AUTHORITY,"com.android.externalstorage.documents");
   eq(DriveSafPolicy.ERR_PROVIDER,"local-provider-required");

   String[] allowedUris={
    "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FAwai",
    "content://com.android.externalstorage.documents/tree/1234-5678%3AAwaiCache",
    "content://com.android.externalstorage.documents/tree/primary%3A",
    "content://com.android.externalstorage.documents/tree/0123-ABCD%3AAndroid%2Fmedia%2Fcache"
   };
   for(String good:allowedUris){
    DriveSafPolicy.requireAllowedTreeUri(good);
    yes(DriveSafPolicy.isAllowedTreeUri(good));
    MockProvider p=new MockProvider(good);
    DriveSafSafAdapter t=new DriveSafSafAdapter(p);
    DriveSaf.adopt(t,instanceId);
    eq(DriveSaf.put(t,instanceId,h1,blob1),"stored");
    yes(Arrays.equals(DriveSaf.get(t,instanceId,h1),blob1));
   }
   DriveSafPolicy.requireAllowedParts("content","com.android.externalstorage.documents","primary:Download/Awai");
   DriveSafPolicy.requireAllowedParts("content","com.android.externalstorage.documents","1234-5678:AwaiCache");

   String[] rejectedUris={
    null,
    "",
    "file:///tmp/cache",
    "https://com.android.externalstorage.documents/tree/primary%3ADownload%2FAwai",
    "CONTENT://com.android.externalstorage.documents/tree/primary%3ADownload%2FAwai",
    "content://com.example.thirdparty.documents/tree/primary%3AAwai",
    "content://com.google.android.apps.docs.storage/tree/acc%3D1%3Bdoc%3D42",
    "content://com.android.providers.downloads.documents/tree/downloads",
    "content://com.android.providers.media.documents/tree/image%3A123",
    "content://network.awai.drive.documents/tree/primary%3AAwai",
    "content://user@com.android.externalstorage.documents/tree/primary%3AAwai",
    "content://com.android.externalstorage.documents:443/tree/primary%3AAwai",
    "content://com.android.externalstorage.documents/document/primary%3ADownload%2FAwai",
    "content://com.android.externalstorage.documents/tree/",
    "content://com.android.externalstorage.documents/tree/primary%3ADownload/extra",
    "content://com.android.externalstorage.documents/tree/primary%3ADownload?q=1",
    "content://com.android.externalstorage.documents/tree/primary%3ADownload#frag",
    "content://com.android.externalstorage.documents/tree/primary%3ADownload%2F..%2Fsecret",
    "content://com.android.externalstorage.documents/tree/primary%3ADownload%2F%2e%2e%2Fsecret",
    "content://com.android.externalstorage.documents/tree/primary%3ADownload%2F%2E%2E%2Fsecret",
    "content://com.android.externalstorage.documents/tree/primary%3A.%2e",
    "content://com.android.externalstorage.documents/tree/primary%3A..%2Fescape",
    "content://com.android.externalstorage.documents/tree/primary%3Aa%00b",
    "content://com.android.externalstorage.documents/tree/primary%3Aa\0b",
    "content://com.android.externalstorage.documents/tree/primary%3Aa%0ab",
    "content://com.android.externalstorage.documents/tree/primary%3Aa%1fb",
    "content://com.android.externalstorage.documents/tree/primary%3Aa%7fb",
    "content://com.android.externalstorage.documents/tree/primary%3Aa\nb",
    "content://com.android.externalstorage.documents/tree/primary%3Aa\\b",
    "content://com.android.externalstorage.documents/tree/primary%3Aa%5cb",
    "content://com.android.externalstorage.documents/tree/primary%3",
    "content://com.android.externalstorage.documents/tree/primary%ZZ",
    "content://com.android.externalstorage.documents/tree/primary%FF",
    "content://com.android.externalstorage.documents/tree/Download%2FAwai",
    "content://com.android.externalstorage.documents/tree/%3ADownload"
   };
   DriveSaf.Item dummyItem=new DriveSaf.Item(h1,"doc-1",false,false,true,blob1.length);
   for(String bad:rejectedUris){
    yes(!DriveSafPolicy.isAllowedTreeUri(bad));
    eq(code(()->{DriveSafPolicy.requireAllowedTreeUri(bad);return null;}),"local-provider-required");
    MockProvider p=new MockProvider(bad);
    p.addDir("cache-1",p.root,DriveSaf.DIR,DriveSafSafAdapter.FLAG_SUPPORTS_DELETE);
    p.addFile("doc-1","cache-1",h1,blob1);
    p.resetCalls();
    DriveSafSafAdapter t=new DriveSafSafAdapter(p);
    eq(code(()->{t.locate();return null;}),"local-provider-required");
    eq(code(()->{t.ensure();return null;}),"local-provider-required");
    eq(code(()->t.children()),"local-provider-required");
    eq(code(()->t.read(dummyItem)),"local-provider-required");
    eq(code(()->t.create(h1,blob1)),"local-provider-required");
    eq(code(()->t.confirmRegularFile(dummyItem)),"local-provider-required");
    eq(code(()->{t.delete(dummyItem);return null;}),"local-provider-required");
    eq(code(()->DriveSaf.adopt(t,instanceId)),"local-provider-required");
    eq(code(()->DriveSaf.inspect(t,instanceId,false)),"local-provider-required");
    eq(code(()->DriveSaf.verify(t,instanceId)),"local-provider-required");
    eq(code(()->DriveSaf.put(t,instanceId,h1,blob1)),"local-provider-required");
    eq(code(()->DriveSaf.get(t,instanceId,h1)),"local-provider-required");
    eq(code(()->DriveSaf.clear(t,instanceId)),"local-provider-required");
    eq(code(()->DriveSaf.release(t,instanceId)),"local-provider-required");
    // ZERO provider I/O calls across every disallowed authority / malformed tree URI!
    eq(p.totalIoCalls(),0);
    eq(p.queryDocCalls.size(),0);
    eq(p.listChildrenCalls.size(),0);
    eq(p.createDocCalls.size(),0);
    eq(p.writeDocCalls.size(),0);
    eq(p.openReadCalls.size(),0);
    eq(p.confirmRegularFileCalls.size(),0);
    eq(p.deleteDocCalls.size(),0);
   }

   // Direct requireAllowedParts negative checks
   eq(code(()->{DriveSafPolicy.requireAllowedParts(null,"com.android.externalstorage.documents","primary:a");return null;}),"local-provider-required");
   eq(code(()->{DriveSafPolicy.requireAllowedParts("file","com.android.externalstorage.documents","primary:a");return null;}),"local-provider-required");
   eq(code(()->{DriveSafPolicy.requireAllowedParts("content","com.android.providers.downloads.documents","downloads");return null;}),"local-provider-required");
   eq(code(()->{DriveSafPolicy.requireAllowedParts("content","com.android.providers.media.documents","image:1");return null;}),"local-provider-required");
   eq(code(()->{DriveSafPolicy.requireAllowedParts("content","network.awai.drive.documents","primary:a");return null;}),"local-provider-required");
   eq(code(()->{DriveSafPolicy.requireAllowedParts("content","com.android.externalstorage.documents",null);return null;}),"local-provider-required");
   eq(code(()->{DriveSafPolicy.requireAllowedParts("content","com.android.externalstorage.documents","");return null;}),"local-provider-required");
   eq(code(()->{DriveSafPolicy.requireAllowedParts("content","com.android.externalstorage.documents","primary:a/../b");return null;}),"local-provider-required");
   eq(code(()->{DriveSafPolicy.requireAllowedParts("content","com.android.externalstorage.documents","primary:a\0b");return null;}),"local-provider-required");
   eq(code(()->{DriveSafPolicy.requireAllowedParts("content","com.android.externalstorage.documents","primary:a\u001fb");return null;}),"local-provider-required");
   eq(code(()->{DriveSafPolicy.requireAllowedParts("content","com.android.externalstorage.documents","primary:a\u007fb");return null;}),"local-provider-required");
   eq(code(()->{DriveSafPolicy.requireAllowedParts("content","com.android.externalstorage.documents","primary:a\\b");return null;}),"local-provider-required");
   eq(code(()->{DriveSafPolicy.requireAllowedParts("content","com.android.externalstorage.documents",":Download");return null;}),"local-provider-required");
   eq(code(()->{DriveSafPolicy.requireAllowedParts("content","com.android.externalstorage.documents","no-colon");return null;}),"local-provider-required");
  }

  System.out.println("DriveSafDocumentsTest: "+checks+" checks passed: DriveSafPolicy allowlist (zero provider I/O on disallowed authorities), pinned cache doc ID, unambiguous root scan, pinned child identity, regular-file & FLAG_SUPPORTS_DELETE confirmation, duplicate display names, symlink/virtual/malformed metadata preservation, oversize non-deletion, end-to-end lifecycle.");
 }
}
