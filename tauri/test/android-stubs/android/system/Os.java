package android.system;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.*;
/** Test-only minimal android.system.Os shadow for JVM boundary testing. */
public final class Os {
 private static final Map<FileDescriptor,StructStat> stats=new IdentityHashMap<>();
 private static final Map<FileDescriptor,Exception> errors=new IdentityHashMap<>();

 public static void reset(){stats.clear();errors.clear();}
 public static void registerStat(FileDescriptor fd,StructStat st){stats.put(fd,st);errors.remove(fd);}
 public static void registerError(FileDescriptor fd,Exception e){errors.put(fd,e);stats.remove(fd);}

 public static StructStat fstat(FileDescriptor fd)throws Exception{
  if(errors.containsKey(fd))throw errors.get(fd);
  if(stats.containsKey(fd))return stats.get(fd);
  throw new IOException("unregistered-fd");
 }
}
