package android.system;
/** Test-only minimal android.system.OsConstants shadow for JVM boundary testing. */
public final class OsConstants {
 public static final int S_IFMT=0170000;
 public static final int S_IFREG=0100000;
 public static final int S_IFDIR=0040000;
 public static final int S_IFLNK=0120000;
 public static final int S_IFIFO=0010000;
 public static boolean S_ISREG(int mode){return (mode&S_IFMT)==S_IFREG;}
}
