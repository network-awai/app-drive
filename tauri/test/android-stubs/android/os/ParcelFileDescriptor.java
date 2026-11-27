package android.os;
import java.io.*;
/** Test-only minimal android.os.ParcelFileDescriptor shadow for JVM boundary testing. */
public final class ParcelFileDescriptor implements Closeable {
 private final FileDescriptor fd;
 public boolean closed;
 public ParcelFileDescriptor(FileDescriptor fd){this.fd=fd;}
 public FileDescriptor getFileDescriptor(){return fd;}
 @Override public void close()throws IOException{closed=true;}
}
