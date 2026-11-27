import network.awai.drive.DriveSaf;
import java.io.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.Callable;import java.security.*;
public class DriveSafTest{
 static int checks=0;
 static void yes(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 static void eq(Object a,Object b){checks++;if(!Objects.equals(a,b))throw new AssertionError("check "+checks+" expected "+b+" got "+a);}
 static String code(Callable<?> f)throws Exception{checks++;try{f.call();throw new AssertionError("check "+checks+" accepted forbidden input");}catch(IOException e){return e.getMessage();}}
 static String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
 static void deleteTree(Path p)throws IOException{if(!Files.exists(p))return;if(Files.isDirectory(p))try(var s=Files.list(p)){for(Path c:s.toList())deleteTree(c);}Files.delete(p);}
 /** File-backed DriveSaf.Tree standing in for the DocumentsContract adapter.
  *  Root is a fake picked tree; every entry is confined to its cache/ child.
  *  revoked simulates a lost persistable permission, a missing tree root
  *  simulates ejected media, failCreate/failRead inject mid-write and
  *  transient read failures. */
 static final class Fake implements DriveSaf.Tree{
  final Path tree,cache;boolean revoked;String failCreate,failRead;
  Fake(Path base)throws IOException{tree=base.resolve("picked");cache=tree.resolve(DriveSaf.DIR);Files.createDirectories(tree);}
  void live()throws IOException{if(revoked)throw new IOException("permission-lost");if(!Files.isDirectory(tree))throw new IOException("cache-absent");}
  Path file(String name)throws IOException{
   if(name==null||name.isEmpty()||name.indexOf('/')>=0||name.indexOf('\\')>=0||name.indexOf(0)>=0||name.equals("..")||name.equals("."))throw new IOException("invalid-path");
   return cache.resolve(name);
  }
  public void ensure()throws IOException{live();Files.createDirectories(cache);}
  public DriveSaf.Item[] children()throws IOException{live();List<DriveSaf.Item> out=new ArrayList<>();try(DirectoryStream<Path> ds=Files.newDirectoryStream(cache)){for(Path p:ds)out.add(new DriveSaf.Item(p.getFileName().toString(),Files.isDirectory(p),Files.isRegularFile(p)?Files.size(p):-1));}return out.toArray(new DriveSaf.Item[0]);}
  public InputStream read(String name)throws IOException{live();if(name.equals(failRead))throw new IOException("io-error");Path f=file(name);if(!Files.isRegularFile(f))throw new IOException("blob-absent");return Files.newInputStream(f);}
  public void create(String name,byte[] data)throws IOException{live();if(name.equals(failCreate))throw new IOException("storage-unavailable");Path f=file(name);try{Files.write(f,data,StandardOpenOption.CREATE_NEW);}catch(FileAlreadyExistsException e){throw new IOException("file-exists");}}
  public void delete(String name)throws IOException{live();Files.deleteIfExists(file(name));}
  void raw(String name,byte[] data)throws IOException{Files.write(cache.resolve(name),data);}
 }
 public static void main(String[] args)throws Exception{
  Path base=Path.of(args[0]);Files.createDirectories(base);Fake f=new Fake(base);
  byte[] blob=new byte[1000];new Random(7).nextBytes(blob);String h=sha(blob);
  // Digest-named blobs only; declared digest must equal content digest.
  eq(code(()->DriveSaf.put(f,"../escape",blob)),"invalid-name");
  eq(code(()->DriveSaf.put(f,h.toUpperCase(Locale.ROOT),blob)),"invalid-name");
  eq(code(()->DriveSaf.put(f,h,new byte[DriveSaf.MAX_BLOB_BYTES+1])),"file-too-large");
  eq(code(()->DriveSaf.put(f,"0".repeat(64),blob)),"sha-mismatch");
  eq(code(()->DriveSaf.get(f,"a/b")),"invalid-name");
  eq(code(()->DriveSaf.get(f,"AB".repeat(32))),"invalid-name");
  // Tree-level confinement: no traversal, absolute or nested names reach disk.
  eq(code(()->f.read("../escape")),"invalid-path");eq(code(()->{f.create("..",new byte[1]);return null;}),"invalid-path");eq(code(()->{f.delete("x/y");return null;}),"invalid-path");eq(code(()->f.read("/abs")),"invalid-path");
  // Write then readback verifies SHA-256; identical rewrite is a no-op.
  eq(DriveSaf.put(f,h,blob),"stored");
  eq(DriveSaf.put(f,h,blob),"present");
  byte[] back=DriveSaf.get(f,h);yes(Arrays.equals(back,blob));eq(sha(back),h);
  // Corruption on disk is never served: get deletes the proven-bad blob.
  byte[] corrupt=blob.clone();corrupt[0]^=0xff;f.raw(h,corrupt);
  eq(code(()->DriveSaf.get(f,h)),"cache-corrupt");eq(code(()->DriveSaf.get(f,h)),"blob-absent");
  // inspect(verify) reports corruption without deleting; rebuild replaces it.
  f.raw(h,corrupt);
  DriveSaf.Report rep=DriveSaf.inspect(f,true);eq(rep.corrupt,1);eq(rep.blobs,1);eq(rep.clean,false);yes(Files.exists(f.cache.resolve(h)));
  rep=DriveSaf.inspect(f,false);eq(rep.corrupt,0);eq(rep.blobs,1);
  eq(DriveSaf.put(f,h,blob),"rebuilt");eq(sha(DriveSaf.get(f,h)),h);
  // Truncated and oversize entries fail the same way.
  f.raw(h,Arrays.copyOf(blob,blob.length-10));eq(code(()->DriveSaf.get(f,h)),"cache-corrupt");eq(code(()->DriveSaf.get(f,h)),"blob-absent");
  String big="1".repeat(64);Files.write(f.cache.resolve(big),new byte[DriveSaf.MAX_BLOB_BYTES+1]);
  eq(code(()->DriveSaf.get(f,big)),"cache-corrupt");yes(!Files.exists(f.cache.resolve(big)));
  // A directory squatting on a blob name is reported, never deleted or served.
  String squat="2".repeat(64);Files.createDirectories(f.cache.resolve(squat));
  eq(code(()->DriveSaf.get(f,squat)),"cache-corrupt");yes(Files.isDirectory(f.cache.resolve(squat)));
  // Unexpected entries are reported and preserved; clear removes only cache's own.
  f.raw("notes.txt",new byte[]{1,2,3});Files.createDirectories(f.cache.resolve("nested"));
  rep=DriveSaf.inspect(f,false);eq(rep.blobs,0);eq(rep.unexpected,3);eq(rep.clean,false);
  eq(DriveSaf.put(f,h,blob),"stored");eq(DriveSaf.clear(f),1);
  yes(Files.exists(f.cache.resolve("notes.txt")));yes(Files.isDirectory(f.cache.resolve("nested")));yes(Files.isDirectory(f.cache.resolve(squat)));yes(!Files.exists(f.cache.resolve(h)));
  // .pending- staging is counted separately; malformed names are unexpected.
  String stage=".pending-12345678-1234-1234-1234-123456789abc.part";
  f.raw(stage,new byte[]{9});f.raw(".pending-not-a-uuid.part",new byte[]{8});
  rep=DriveSaf.inspect(f,false);eq(rep.pending,1);eq(rep.unexpected,4);
  eq(DriveSaf.clear(f),1);yes(!Files.exists(f.cache.resolve(stage)));yes(Files.exists(f.cache.resolve(".pending-not-a-uuid.part")));
  // A mid-write failure cleans staging and leaves no partial blob.
  byte[] b2=new byte[64];new Random(3).nextBytes(b2);String h2=sha(b2);
  f.failCreate=h2;eq(code(()->DriveSaf.put(f,h2,b2)),"storage-unavailable");
  try(var s=Files.list(f.cache)){yes(s.noneMatch(p->DriveSaf.staging(p.getFileName().toString())));}
  eq(code(()->DriveSaf.get(f,h2)),"blob-absent");f.failCreate=null;
  eq(DriveSaf.put(f,h2,b2),"stored");
  // Transient read failures propagate without deleting verified data.
  f.failRead=h2;eq(code(()->DriveSaf.get(f,h2)),"io-error");yes(Files.exists(f.cache.resolve(h2)));f.failRead=null;
  eq(sha(DriveSaf.get(f,h2)),h2);
  // Lost permission and ejected media are typed errors; nothing is touched.
  f.revoked=true;
  eq(code(()->DriveSaf.inspect(f,false)),"permission-lost");
  eq(code(()->DriveSaf.put(f,h2,b2)),"permission-lost");
  eq(code(()->DriveSaf.get(f,h2)),"permission-lost");
  eq(code(()->DriveSaf.clear(f)),"permission-lost");
  f.revoked=false;eq(sha(DriveSaf.get(f,h2)),h2);
  deleteTree(f.tree);
  eq(code(()->DriveSaf.inspect(f,false)),"cache-absent");
  eq(code(()->DriveSaf.put(f,h2,b2)),"cache-absent");
  eq(code(()->DriveSaf.get(f,h2)),"cache-absent");
  Files.createDirectories(f.tree); // media returns empty: cache rebuilds from re-fetch source
  rep=DriveSaf.inspect(f,false);eq(rep.blobs,0);eq(rep.clean,true);
  eq(DriveSaf.put(f,h2,b2),"stored");eq(sha(DriveSaf.get(f,h2)),h2);
  // Entry and byte ceilings bound the cache; clear reclaims cache-owned files.
  DriveSaf.clear(f);
  for(int i=0;i<DriveSaf.MAX_ENTRIES;i++)f.raw(String.format("%064x",i),new byte[0]);
  byte[] b3=new byte[8];new Random(9).nextBytes(b3);
  eq(code(()->DriveSaf.put(f,sha(b3),b3)),"entry-limit");
  eq(DriveSaf.clear(f),DriveSaf.MAX_ENTRIES);
  String huge="3".repeat(64);
  try(RandomAccessFile raf=new RandomAccessFile(f.cache.resolve(huge).toFile(),"rw")){raf.setLength(DriveSaf.MAX_BYTES+1);}
  eq(code(()->DriveSaf.put(f,sha(b3),b3)),"cache-limit");
  eq(DriveSaf.clear(f),1);
  rep=DriveSaf.inspect(f,false);eq(rep.blobs,0);eq(rep.unexpected,0);eq(rep.clean,true);
  System.out.println("DriveSaf: "+checks+" checks passed: confinement, sha256 write/readback, corrupt/truncate/oversize detection, rebuild, staging cleanup, typed deny/lost/absent errors, entry+byte limits, clear preserves foreign files.");
 }
}
