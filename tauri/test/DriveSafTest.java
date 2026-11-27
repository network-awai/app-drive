import network.awai.drive.DriveSaf;
import network.awai.drive.DriveSafRecord;
import java.io.*;import java.nio.charset.StandardCharsets;import java.nio.file.*;import java.util.*;import java.util.concurrent.Callable;import java.security.*;
public class DriveSafTest{
 static int checks=0;
 static void yes(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 static void eq(Object a,Object b){checks++;if(!Objects.equals(a,b))throw new AssertionError("check "+checks+" expected "+b+" got "+a);}
 static String code(Callable<?> f)throws Exception{checks++;try{f.call();throw new AssertionError("check "+checks+" accepted forbidden input");}catch(IOException e){return e.getMessage();}}
 static String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
 static void deleteTree(Path p)throws IOException{if(!Files.exists(p,LinkOption.NOFOLLOW_LINKS))return;if(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))try(var s=Files.list(p)){for(Path c:s.toList())deleteTree(c);}Files.delete(p);}
 /** File-backed DriveSaf.Tree standing in for the DocumentsContract adapter.
  *  Root is a fake picked tree; every entry is confined to its cache/ child.
  *  Tracks createCalls and deleteCalls so negative tests can prove zero writes
  *  and zero deletions on foreign-directory refusal, oversize, or transient
  *  I/O errors. */
 static final class Fake implements DriveSaf.Tree{
  final Path tree,cache;boolean revoked;
  String failCreate,failRead,failReadError="storage-unavailable",failMidStream;
  final Map<String,Long> reportedSizeOverride=new HashMap<>();
  final Map<String,String> ids=new HashMap<>();
  int nextId=1;
  final List<String> createCalls=new ArrayList<>(),deleteCalls=new ArrayList<>();
  Fake(Path base,String label)throws IOException{tree=base.resolve(label);cache=tree.resolve(DriveSaf.DIR);Files.createDirectories(tree);}
  void resetCalls(){createCalls.clear();deleteCalls.clear();}
  String idFor(String name){return ids.computeIfAbsent(name,k->"doc-"+(nextId++));}
  void live()throws IOException{if(revoked)throw new IOException("permission-lost");if(!Files.isDirectory(tree))throw new IOException("cache-absent");}
  Path file(String name)throws IOException{
   if(name==null||name.isEmpty()||name.indexOf('/')>=0||name.indexOf('\\')>=0||name.indexOf(0)>=0||name.equals("..")||name.equals("."))throw new IOException("invalid-path");
   return cache.resolve(name);
  }
  public void locate()throws IOException{
   live();
   if(!Files.exists(cache,LinkOption.NOFOLLOW_LINKS))throw new IOException("cache-absent");
   if(Files.isSymbolicLink(cache)||!Files.isDirectory(cache,LinkOption.NOFOLLOW_LINKS))throw new IOException("foreign-directory-refused");
  }
  public void ensure()throws IOException{
   try{locate();return;}
   catch(IOException e){if(!"cache-absent".equals(e.getMessage()))throw e;}
   Files.createDirectories(cache);
  }
  public DriveSaf.Item[] children()throws IOException{
   locate();List<DriveSaf.Item> out=new ArrayList<>();
   try(DirectoryStream<Path> ds=Files.newDirectoryStream(cache)){
    for(Path p:ds){
     String n=p.getFileName().toString();
     boolean sym=Files.isSymbolicLink(p);
     boolean dir=!sym&&Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS);
     boolean reg=!sym&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS);
     long sz=reportedSizeOverride.containsKey(n)?reportedSizeOverride.get(n):(reg?Files.size(p):-1);
     out.add(new DriveSaf.Item(n,idFor(n),dir,sym,reg,sz));
    }
   }
   return out.toArray(new DriveSaf.Item[0]);
  }
  public InputStream read(DriveSaf.Item item)throws IOException{
   locate();
   if(item==null)throw new IOException("invalid-path");
   String name=item.name;
   if(name!=null&&name.equals(failRead))throw new IOException(failReadError);
   Path f=file(name);
   if(Files.isSymbolicLink(f)||!Files.isRegularFile(f,LinkOption.NOFOLLOW_LINKS))throw new IOException("blob-absent");
   if(name.equals(failMidStream)){
    InputStream real=Files.newInputStream(f);
    return new InputStream(){
     int count=0;
     public int read()throws IOException{if(count++>=4)throw new IOException(failReadError);return real.read();}
     public int read(byte[] b,int off,int len)throws IOException{
      if(count>=4)throw new IOException(failReadError);
      int n=real.read(b,off,Math.min(len,4-count));if(n>0)count+=n;return n;
     }
     public void close()throws IOException{real.close();}
    };
   }
   return Files.newInputStream(f);
  }
  public DriveSaf.Item create(String name,byte[] data)throws IOException{
   locate();createCalls.add(name);
   if(name!=null&&name.equals(failCreate))throw new IOException("storage-unavailable");
   Path f=file(name);
   try{Files.write(f,data,StandardOpenOption.CREATE_NEW);}catch(FileAlreadyExistsException e){throw new IOException("file-exists");}
   String docId="doc-"+(nextId++);
   ids.put(name,docId);
   return new DriveSaf.Item(name,docId,false,false,true,data.length);
  }
  public boolean confirmRegularFile(DriveSaf.Item item)throws IOException{
   locate();
   if(item==null||!item.regularFile())return false;
   Path f=file(item.name);
   return !Files.isSymbolicLink(f)&&Files.isRegularFile(f,LinkOption.NOFOLLOW_LINKS);
  }
  public void delete(DriveSaf.Item item)throws IOException{
   locate();
   if(item==null)throw new IOException("invalid-path");
   Path f=file(item.name);
   deleteCalls.add(item.name);
   Files.deleteIfExists(f);
   ids.remove(item.name);
  }
  void raw(String name,byte[] data)throws IOException{Files.createDirectories(cache);Files.write(cache.resolve(name),data);idFor(name);}
 }
 static void assertAbsentZeroMutation(Fake f,String id,String h,byte[] blob)throws Exception{
  f.resetCalls();
  eq(code(()->DriveSaf.inspect(f,id,false)),"cache-absent");
  eq(code(()->DriveSaf.inspect(f,id,true)),"cache-absent");
  eq(code(()->DriveSaf.verify(f,id)),"cache-absent");
  eq(code(()->DriveSaf.put(f,id,h,blob)),"cache-absent");
  eq(code(()->DriveSaf.get(f,id,h)),"cache-absent");
  eq(code(()->DriveSaf.clear(f,id)),"cache-absent");
  eq(code(()->DriveSaf.clearDetailed(f,id)),"cache-absent");
  eq(code(()->DriveSaf.release(f,id)),"cache-absent");
  eq(f.createCalls.size(),0);
  eq(f.deleteCalls.size(),0);
  yes(!Files.exists(f.cache,LinkOption.NOFOLLOW_LINKS));
 }
 static void assertAllRefusedZeroMutation(Fake f,String id,String h,byte[] blob)throws Exception{
  f.resetCalls();
  eq(code(()->DriveSaf.adopt(f,id)),"foreign-directory-refused");
  eq(code(()->DriveSaf.inspect(f,id,false)),"foreign-directory-refused");
  eq(code(()->DriveSaf.inspect(f,id,true)),"foreign-directory-refused");
  eq(code(()->DriveSaf.verify(f,id)),"foreign-directory-refused");
  eq(code(()->DriveSaf.put(f,id,h,blob)),"foreign-directory-refused");
  eq(code(()->DriveSaf.get(f,id,h)),"foreign-directory-refused");
  eq(code(()->DriveSaf.clear(f,id)),"foreign-directory-refused");
  eq(code(()->DriveSaf.clearDetailed(f,id)),"foreign-directory-refused");
  eq(code(()->DriveSaf.release(f,id)),"foreign-directory-refused");
  eq(f.createCalls.size(),0);
  eq(f.deleteCalls.size(),0);
 }
 public static void main(String[] args)throws Exception{
  Path base=Path.of(args[0]);deleteTree(base);Files.createDirectories(base);
  byte[] blob=new byte[1000];new Random(7).nextBytes(blob);String h=sha(blob);
  byte[] b2=new byte[64];new Random(3).nextBytes(b2);String h2=sha(b2);
  String id=DriveSafRecord.newInstanceId();
  String otherId=DriveSafRecord.newInstanceId();
  while(otherId.equals(id))otherId=DriveSafRecord.newInstanceId();

  // --- SAF-OWN-01/02: Non-adopt ops NEVER auto-create cache/; only adopt creates cache/ and marker ---
  Fake f=new Fake(base,"picked");
  assertAbsentZeroMutation(f,id,h,blob);
  Fake fileCache=new Fake(base,"cache-is-file");
  Files.writeString(fileCache.cache,"user-file");
  assertAllRefusedZeroMutation(fileCache,id,h,blob);
  yes(Files.isRegularFile(fileCache.cache,LinkOption.NOFOLLOW_LINKS));
  eq(Files.readString(fileCache.cache),"user-file");
  Fake emptyUnadopted=new Fake(base,"empty-unadopted");
  Files.createDirectories(emptyUnadopted.cache);
  eq(code(()->DriveSaf.put(emptyUnadopted,id,h,blob)),"foreign-directory-refused");
  eq(code(()->DriveSaf.clear(emptyUnadopted,id)),"foreign-directory-refused");
  eq(emptyUnadopted.createCalls.size(),0);eq(emptyUnadopted.deleteCalls.size(),0);
  yes(!Files.exists(f.cache,LinkOption.NOFOLLOW_LINKS));
  DriveSaf.Item adoptedMarker=DriveSaf.adopt(f,id);
  yes(adoptedMarker!=null&&DriveSaf.OWNER_FILE.equals(adoptedMarker.name)&&adoptedMarker.regularFile());
  yes(Files.isDirectory(f.cache,LinkOption.NOFOLLOW_LINKS));
  yes(Files.isRegularFile(f.cache.resolve(DriveSaf.OWNER_FILE)));
  eq(DriveSafRecord.decodeOwner(Files.readAllBytes(f.cache.resolve(DriveSaf.OWNER_FILE))),id);
  try(var rootKids=Files.list(f.tree)){
   List<String> rootNames=rootKids.map(p->p.getFileName().toString()).toList();
   eq(rootNames,List.of(DriveSaf.DIR)); // SAF-OWN-01: zero files at <picked> root
  }
  Fake removedCache=new Fake(base,"removed-cache");
  DriveSaf.adopt(removedCache,id);
  eq(DriveSaf.put(removedCache,id,h,blob),"stored");
  deleteTree(removedCache.cache);
  assertAbsentZeroMutation(removedCache,id,h,blob);
  // Re-adopting an already-owned directory with the same instanceId is idempotent with zero writes and returns null.
  f.resetCalls();yes(DriveSaf.adopt(f,id)==null);eq(f.createCalls.size(),0);eq(f.deleteCalls.size(),0);
  eq(DriveSaf.put(f,id,h,blob),"stored");
  eq(DriveSaf.put(f,id,h,blob),"present");
  byte[] back=DriveSaf.get(f,id,h);yes(Arrays.equals(back,blob));eq(sha(back),h);
  DriveSaf.Report vrep=DriveSaf.verify(f,id);
  eq(vrep.blobs,1);eq(vrep.verified,1);eq(vrep.corrupt,0);eq(vrep.oversize,0);eq(vrep.unreadable,0);eq(vrep.removed,0);eq(vrep.unexpected,0);eq(vrep.clean,true);

  // --- Read-only provider document visibility filter (SAF-OWN-03) ---
  yes(!DriveSaf.isUserDocument(DriveSaf.OWNER_FILE));
  yes(!DriveSaf.isUserDocument(".pending-12345678-1234-4234-8234-123456789abc.part"));
  yes(!DriveSaf.isUserDocument(".pending-writer.lock"));
  yes(DriveSaf.isUserDocument("report.pdf"));

  // --- Transient read IOException MUST NOT delete existing entries on put, verify, inspect, or get ---
  f.resetCalls();f.failRead=h;f.failReadError="storage-unavailable";
  eq(code(()->DriveSaf.put(f,id,h,blob)),"storage-unavailable");
  eq(f.deleteCalls.size(),0);eq(f.createCalls.size(),0);
  yes(Files.exists(f.cache.resolve(h)));
  DriveSaf.Report urep=DriveSaf.verify(f,id);
  eq(urep.blobs,1);eq(urep.verified,0);eq(urep.corrupt,0);eq(urep.oversize,0);eq(urep.removed,0);
  eq(urep.unreadable,1);eq(urep.unreadableNames,List.of(h));eq(urep.clean,false);
  eq(f.deleteCalls.size(),0);yes(Files.exists(f.cache.resolve(h)));
  DriveSaf.Report irep=DriveSaf.inspect(f,id,true);
  eq(irep.corrupt,0);eq(irep.oversize,0);eq(irep.unreadable,1);eq(irep.unreadableNames,List.of(h));eq(irep.clean,false);
  eq(f.deleteCalls.size(),0);
  eq(code(()->DriveSaf.get(f,id,h)),"storage-unavailable");
  eq(f.deleteCalls.size(),0);yes(Files.exists(f.cache.resolve(h)));
  f.failRead=null;
  f.resetCalls();f.failMidStream=h;f.failReadError="storage-unavailable";
  eq(code(()->DriveSaf.put(f,id,h,blob)),"storage-unavailable");
  eq(f.deleteCalls.size(),0);yes(Files.exists(f.cache.resolve(h)));
  urep=DriveSaf.verify(f,id);
  eq(urep.corrupt,0);eq(urep.oversize,0);eq(urep.removed,0);eq(urep.unreadable,1);eq(urep.clean,false);
  eq(f.deleteCalls.size(),0);yes(Files.exists(f.cache.resolve(h)));
  f.failMidStream=null;
  yes(Arrays.equals(DriveSaf.get(f,id,h),blob));

  // --- P1 #3: Oversize entries MUST NEVER be deleted by verify, inspect, get, or put ---
  // Case A: Provider metadata reports item.size = MAX_BLOB_BYTES + 1 even though actual file bytes are valid/small!
  f.resetCalls();
  f.reportedSizeOverride.put(h,(long)(DriveSaf.MAX_BLOB_BYTES+1));
  DriveSaf.Report orep=DriveSaf.verify(f,id);
  eq(orep.blobs,1);eq(orep.verified,0);eq(orep.corrupt,0);eq(orep.removed,0);
  eq(orep.oversize,1);eq(orep.oversizeNames,List.of(h));eq(orep.unreadable,0);eq(orep.clean,false);
  eq(f.deleteCalls.size(),0);yes(Files.exists(f.cache.resolve(h)));
  DriveSaf.Report oirep=DriveSaf.inspect(f,id,true);
  eq(oirep.corrupt,0);eq(oirep.oversize,1);eq(oirep.oversizeNames,List.of(h));eq(oirep.clean,false);
  eq(f.deleteCalls.size(),0);yes(Files.exists(f.cache.resolve(h)));
  eq(code(()->DriveSaf.get(f,id,h)),"cache-corrupt");
  eq(f.deleteCalls.size(),0);yes(Files.exists(f.cache.resolve(h)));
  eq(code(()->DriveSaf.put(f,id,h,blob)),"cache-corrupt");
  eq(f.deleteCalls.size(),0);yes(Files.exists(f.cache.resolve(h)));
  f.reportedSizeOverride.remove(h);
  yes(Arrays.equals(DriveSaf.get(f,id,h),blob)); // Still intact once bad metadata header clears!

  // Case B: Actual file > MAX_BLOB_BYTES (tested both with real size and with unknown size=-1 so stream cap triggers).
  String big="1".repeat(64);
  f.raw(big,new byte[DriveSaf.MAX_BLOB_BYTES+1]);
  f.resetCalls();
  // Stream exceeds cap when metadata size is unknown (-1):
  f.reportedSizeOverride.put(big,-1L);
  orep=DriveSaf.verify(f,id);
  eq(orep.blobs,2);eq(orep.verified,1);eq(orep.corrupt,0);eq(orep.removed,0);
  eq(orep.oversize,1);eq(orep.oversizeNames,List.of(big));eq(orep.clean,false);
  eq(f.deleteCalls.size(),0);yes(Files.exists(f.cache.resolve(big)));
  oirep=DriveSaf.inspect(f,id,true);
  eq(oirep.oversize,1);eq(oirep.oversizeNames,List.of(big));eq(oirep.corrupt,0);eq(oirep.clean,false);
  eq(f.deleteCalls.size(),0);
  eq(code(()->DriveSaf.get(f,id,big)),"cache-corrupt");
  eq(f.deleteCalls.size(),0);yes(Files.exists(f.cache.resolve(big)));
  // Also test put on an actual oversize entry matching declared hash name:
  f.reportedSizeOverride.remove(big);
  String hOver=h;
  Files.write(f.cache.resolve(hOver),new byte[DriveSaf.MAX_BLOB_BYTES+1]);
  f.resetCalls();
  eq(code(()->DriveSaf.put(f,id,hOver,blob)),"cache-corrupt");
  eq(f.deleteCalls.size(),0);yes(Files.exists(f.cache.resolve(hOver)));
  eq(code(()->DriveSaf.get(f,id,hOver)),"cache-corrupt");
  eq(f.deleteCalls.size(),0);yes(Files.exists(f.cache.resolve(hOver)));
  // Restore hOver to valid blob bytes and remove big only after mixed verify test below:
  Files.write(f.cache.resolve(hOver),blob);

  // Case C: Mixed verify: 1 intact (h2) + 1 measured SHA-256 mismatch (h3) + 1 oversize (big) + 1 transient unreadable (h)
  // -> deletes ONLY the measured SHA-256 mismatch (h3)!
  byte[] b3=new byte[32];new Random(11).nextBytes(b3);String h3=sha(b3);
  eq(DriveSaf.put(f,id,h2,b2),"stored");
  byte[] bad3=b3.clone();bad3[0]^=0x55;f.raw(h3,bad3);
  f.resetCalls();f.failRead=h;f.failReadError="storage-unavailable";
  DriveSaf.Report mix=DriveSaf.verify(f,id);
  eq(mix.blobs,4);eq(mix.verified,1);eq(mix.corrupt,1);eq(mix.corruptNames,List.of(h3));
  eq(mix.removed,1);eq(mix.oversize,1);eq(mix.oversizeNames,List.of(big));
  eq(mix.unreadable,1);eq(mix.unreadableNames,List.of(h));eq(mix.clean,false);
  eq(f.deleteCalls,List.of(h3)); // ONLY measured SHA-256 mismatch h3 deleted
  yes(!Files.exists(f.cache.resolve(h3)));
  yes(Files.exists(f.cache.resolve(big))); // oversize preserved
  yes(Files.exists(f.cache.resolve(h))); // unreadable preserved
  yes(Files.exists(f.cache.resolve(h2))); // intact preserved
  yes(Files.exists(f.cache.resolve(DriveSaf.OWNER_FILE))); // owner marker preserved
  f.failRead=null;
  Files.delete(f.cache.resolve(big));

  // --- Digest/name validation & tree-level path confinement ---
  eq(code(()->DriveSaf.put(f,id,"../escape",blob)),"invalid-name");
  eq(code(()->DriveSaf.put(f,id,h.toUpperCase(Locale.ROOT),blob)),"invalid-name");
  eq(code(()->DriveSaf.put(f,id,h,new byte[DriveSaf.MAX_BLOB_BYTES+1])),"file-too-large");
  eq(code(()->DriveSaf.put(f,id,"0".repeat(64),blob)),"sha-mismatch");
  eq(code(()->DriveSaf.get(f,id,"a/b")),"invalid-name");
  eq(code(()->DriveSaf.get(f,id,"AB".repeat(32))),"invalid-name");
  eq(code(()->f.read(new DriveSaf.Item("../escape",false,1))),"invalid-path");
  eq(code(()->f.create("..",new byte[1])),"invalid-path");
  eq(code(()->{f.delete(new DriveSaf.Item("x/y",false,1));return null;}),"invalid-path");
  eq(code(()->f.read(new DriveSaf.Item("/abs",false,1))),"invalid-path");

  // --- Measured SHA-256 mismatch (bitflip, truncation) is detected, deleted, & rebuilt ---
  byte[] corrupt=blob.clone();corrupt[0]^=0xff;f.raw(h,corrupt);
  eq(code(()->DriveSaf.get(f,id,h)),"cache-corrupt");eq(code(()->DriveSaf.get(f,id,h)),"blob-absent");
  f.raw(h,corrupt);
  DriveSaf.Report rep=DriveSaf.inspect(f,id,true);eq(rep.corrupt,1);eq(rep.blobs,2);eq(rep.clean,false);yes(Files.exists(f.cache.resolve(h)));
  rep=DriveSaf.inspect(f,id,false);eq(rep.corrupt,0);eq(rep.blobs,2);
  eq(DriveSaf.put(f,id,h,blob),"rebuilt");eq(sha(DriveSaf.get(f,id,h)),h);
  // Truncated entry has measured SHA-256 mismatch -> deleted on get.
  f.raw(h,Arrays.copyOf(blob,blob.length-10));eq(code(()->DriveSaf.get(f,id,h)),"cache-corrupt");eq(code(()->DriveSaf.get(f,id,h)),"blob-absent");
  // A directory squatting on a blob name is reported, never deleted or served.
  String squat="2".repeat(64);Files.createDirectories(f.cache.resolve(squat));
  eq(code(()->DriveSaf.get(f,id,squat)),"cache-corrupt");yes(Files.isDirectory(f.cache.resolve(squat)));

  // --- SAF-OWN-03: Double-gated deletion in cacheClear preserves owner marker, foreign files, subdirs, and symlinks ---
  DriveSaf.clear(f,id);
  String stage=".pending-12345678-1234-4234-8234-123456789abc.part";
  String symHex="a".repeat(64);
  String symStage=".pending-aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.part";
  Path outsideTarget=base.resolve("outside-secret.txt");Files.writeString(outsideTarget,"do-not-delete");
  eq(DriveSaf.put(f,id,h,blob),"stored");
  f.raw(stage,new byte[]{9});
  f.raw("notes.txt",new byte[]{1,2,3});
  f.raw(".pending-not-a-uuid.part",new byte[]{8});
  Files.createDirectories(f.cache.resolve("nested"));
  Files.createSymbolicLink(f.cache.resolve(symHex),outsideTarget);
  Files.createSymbolicLink(f.cache.resolve(symStage),outsideTarget);
  rep=DriveSaf.inspect(f,id,false);
  eq(rep.blobs,1);eq(rep.pending,1);eq(rep.unexpected,6);eq(rep.clean,false);
  DriveSaf.ClearReport cr=DriveSaf.clearDetailed(f,id);
  eq(cr.deleted,2); // ONLY regular hex blob + regular .pending-<uuid>.part
  eq(cr.preservedForeign,6); // squat dir + notes.txt + malformed .pending + nested dir + 2 symlinks
  yes(cr.ownerPreserved);
  yes(!Files.exists(f.cache.resolve(h)));
  yes(!Files.exists(f.cache.resolve(stage)));
  yes(Files.isRegularFile(f.cache.resolve(DriveSaf.OWNER_FILE))); // owner marker preserved!
  yes(Files.exists(f.cache.resolve("notes.txt")));
  yes(Files.exists(f.cache.resolve(".pending-not-a-uuid.part")));
  yes(Files.isDirectory(f.cache.resolve("nested")));
  yes(Files.isDirectory(f.cache.resolve(squat)));
  yes(Files.isSymbolicLink(f.cache.resolve(symHex)));
  yes(Files.isSymbolicLink(f.cache.resolve(symStage)));
  eq(Files.readString(outsideTarget),"do-not-delete");
  Files.delete(f.cache.resolve("notes.txt"));Files.delete(f.cache.resolve(".pending-not-a-uuid.part"));
  Files.delete(f.cache.resolve("nested"));Files.delete(f.cache.resolve(squat));
  Files.delete(f.cache.resolve(symHex));Files.delete(f.cache.resolve(symStage));

  // --- Mid-write failure cleans staging and leaves no partial blob ---
  f.failCreate=h2;eq(code(()->DriveSaf.put(f,id,h2,b2)),"storage-unavailable");
  try(var s=Files.list(f.cache)){yes(s.noneMatch(p->DriveSaf.staging(p.getFileName().toString())));}
  eq(code(()->DriveSaf.get(f,id,h2)),"blob-absent");f.failCreate=null;
  eq(DriveSaf.put(f,id,h2,b2),"stored");

  // --- SAF-OWN-02: Non-empty foreign directory refusals (ZERO writes, ZERO deletions) ---
  Fake foreignNoMarker=new Fake(base,"foreign-no-marker");
  foreignNoMarker.raw(h,blob);
  foreignNoMarker.raw(stage,new byte[]{7});
  assertAllRefusedZeroMutation(foreignNoMarker,id,h,blob);
  yes(Arrays.equals(Files.readAllBytes(foreignNoMarker.cache.resolve(h)),blob));
  yes(Files.exists(foreignNoMarker.cache.resolve(stage)));
  Fake foreignMalformed=new Fake(base,"foreign-malformed");
  foreignMalformed.raw(DriveSaf.OWNER_FILE,"{not valid json".getBytes(StandardCharsets.UTF_8));
  foreignMalformed.raw(h,blob);
  assertAllRefusedZeroMutation(foreignMalformed,id,h,blob);
  yes(Files.exists(foreignMalformed.cache.resolve(h)));
  Fake foreignWrongSchema=new Fake(base,"foreign-wrong-schema");
  foreignWrongSchema.raw(DriveSaf.OWNER_FILE,("{\"schema\":\"other.v1\",\"appId\":\"network.awai.drive\",\"cacheVersion\":1,\"instanceId\":\""+id+"\"}").getBytes(StandardCharsets.UTF_8));
  foreignWrongSchema.raw(h,blob);
  assertAllRefusedZeroMutation(foreignWrongSchema,id,h,blob);
  yes(Files.exists(foreignWrongSchema.cache.resolve(h)));
  Fake foreignWrongApp=new Fake(base,"foreign-wrong-app");
  foreignWrongApp.raw(DriveSaf.OWNER_FILE,("{\"schema\":\"awai.saf-cache-owner.v1\",\"appId\":\"com.other.app\",\"cacheVersion\":1,\"instanceId\":\""+id+"\"}").getBytes(StandardCharsets.UTF_8));
  foreignWrongApp.raw(h,blob);
  assertAllRefusedZeroMutation(foreignWrongApp,id,h,blob);
  yes(Files.exists(foreignWrongApp.cache.resolve(h)));
  Fake foreignWrongVer=new Fake(base,"foreign-wrong-ver");
  foreignWrongVer.raw(DriveSaf.OWNER_FILE,("{\"schema\":\"awai.saf-cache-owner.v1\",\"appId\":\"network.awai.drive\",\"cacheVersion\":2,\"instanceId\":\""+id+"\"}").getBytes(StandardCharsets.UTF_8));
  foreignWrongVer.raw(h,blob);
  assertAllRefusedZeroMutation(foreignWrongVer,id,h,blob);
  foreignWrongVer.raw(DriveSaf.OWNER_FILE,("{\"schema\":\"awai.saf-cache-owner.v1\",\"appId\":\"network.awai.drive\",\"cacheVersion\":\"1\",\"instanceId\":\""+id+"\"}").getBytes(StandardCharsets.UTF_8));
  assertAllRefusedZeroMutation(foreignWrongVer,id,h,blob);
  yes(Files.exists(foreignWrongVer.cache.resolve(h)));
  Fake foreignWrongId=new Fake(base,"foreign-wrong-id");
  foreignWrongId.raw(DriveSaf.OWNER_FILE,DriveSafRecord.encodeOwner(otherId));
  foreignWrongId.raw(h,blob);
  assertAllRefusedZeroMutation(foreignWrongId,id,h,blob);
  yes(Files.exists(foreignWrongId.cache.resolve(h)));
  assertAllRefusedZeroMutation(f,null,h,blob);
  assertAllRefusedZeroMutation(f,"",h,blob);
  assertAllRefusedZeroMutation(f,"not-a-uuid",h,blob);
  yes(Files.exists(f.cache.resolve(h2)));
  Fake foreignDirMarker=new Fake(base,"foreign-dir-marker");
  Files.createDirectories(foreignDirMarker.cache.resolve(DriveSaf.OWNER_FILE));
  foreignDirMarker.raw(h,blob);
  assertAllRefusedZeroMutation(foreignDirMarker,id,h,blob);
  Fake foreignSymMarker=new Fake(base,"foreign-sym-marker");
  Files.createDirectories(foreignSymMarker.cache);
  Path realMarker=base.resolve("external-marker.json");Files.write(realMarker,DriveSafRecord.encodeOwner(id));
  Files.createSymbolicLink(foreignSymMarker.cache.resolve(DriveSaf.OWNER_FILE),realMarker);
  foreignSymMarker.raw(h,blob);
  assertAllRefusedZeroMutation(foreignSymMarker,id,h,blob);
  Fake failAdopt=new Fake(base,"fail-adopt");
  failAdopt.failRead=DriveSaf.OWNER_FILE;failAdopt.failReadError="storage-unavailable";
  eq(code(()->DriveSaf.adopt(failAdopt,id)),"storage-unavailable");

  // --- Explicit release removes owned blobs + owner marker while preserving foreign files ---
  Fake rel=new Fake(base,"release-test");
  DriveSaf.adopt(rel,id);
  eq(DriveSaf.put(rel,id,h,blob),"stored");
  rel.raw("keep.txt",new byte[]{4,5});
  eq(DriveSaf.release(rel,id),1);
  yes(!Files.exists(rel.cache.resolve(h)));
  yes(!Files.exists(rel.cache.resolve(DriveSaf.OWNER_FILE)));
  yes(Files.exists(rel.cache.resolve("keep.txt")));

  // --- Lost permission and ejected media are typed errors; nothing is touched ---
  f.revoked=true;
  eq(code(()->DriveSaf.inspect(f,id,false)),"permission-lost");
  eq(code(()->DriveSaf.verify(f,id)),"permission-lost");
  eq(code(()->DriveSaf.put(f,id,h2,b2)),"permission-lost");
  eq(code(()->DriveSaf.get(f,id,h2)),"permission-lost");
  eq(code(()->DriveSaf.clear(f,id)),"permission-lost");
  f.revoked=false;eq(sha(DriveSaf.get(f,id,h2)),h2);
  deleteTree(f.tree);
  eq(code(()->DriveSaf.inspect(f,id,false)),"cache-absent");
  eq(code(()->DriveSaf.put(f,id,h2,b2)),"cache-absent");
  eq(code(()->DriveSaf.get(f,id,h2)),"cache-absent");
  Files.createDirectories(f.tree);
  DriveSaf.adopt(f,id);
  rep=DriveSaf.inspect(f,id,false);eq(rep.blobs,0);eq(rep.clean,true);
  eq(DriveSaf.put(f,id,h2,b2),"stored");eq(sha(DriveSaf.get(f,id,h2)),h2);

  // --- Internal unsynced state stays byte-identical across permission loss and media absence ---
  Path internal=base.resolve("internal");Files.createDirectories(internal);Files.createDirectories(internal.resolve("nested"));
  Files.write(internal.resolve("unsynced.bin"),blob);Files.write(internal.resolve("nested/wal.bin"),b2);
  f.revoked=true;
  eq(code(()->DriveSaf.inspect(f,id,false)),"permission-lost");
  eq(code(()->DriveSaf.put(f,id,h2,b2)),"permission-lost");
  eq(code(()->DriveSaf.get(f,id,h2)),"permission-lost");
  f.revoked=false;
  deleteTree(f.tree);
  eq(code(()->DriveSaf.inspect(f,id,false)),"cache-absent");
  eq(code(()->DriveSaf.get(f,id,h2)),"cache-absent");
  Files.createDirectories(f.tree);DriveSaf.adopt(f,id);DriveSaf.inspect(f,id,false);
  yes(Arrays.equals(Files.readAllBytes(internal.resolve("unsynced.bin")),blob));
  yes(Arrays.equals(Files.readAllBytes(internal.resolve("nested/wal.bin")),b2));

  // --- Entry and byte ceilings bound the cache; clear reclaims cache-owned files ---
  DriveSaf.clear(f,id);
  for(int i=0;i<DriveSaf.MAX_ENTRIES;i++)f.raw(String.format("%064x",i),new byte[0]);
  byte[] b4=new byte[8];new Random(9).nextBytes(b4);
  eq(code(()->DriveSaf.put(f,id,sha(b4),b4)),"entry-limit");
  eq(DriveSaf.clear(f,id),DriveSaf.MAX_ENTRIES);
  yes(Files.exists(f.cache.resolve(DriveSaf.OWNER_FILE)));
  String huge="3".repeat(64);
  try(RandomAccessFile raf=new RandomAccessFile(f.cache.resolve(huge).toFile(),"rw")){raf.setLength(DriveSaf.MAX_BYTES+1);}
  eq(code(()->DriveSaf.put(f,id,sha(b4),b4)),"cache-limit");
  eq(DriveSaf.clear(f,id),1);
  rep=DriveSaf.inspect(f,id,false);eq(rep.blobs,0);eq(rep.unexpected,0);eq(rep.clean,true);
  System.out.println("DriveSaf: "+checks+" checks passed: SAF-OWN-01..04 pinned Item handles, oversize non-deletion, transient IOException preservation, sha256 verification, rebuild, staging cleanup, typed errors, entry+byte limits.");
 }
}
