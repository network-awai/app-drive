package android.content;
import java.io.File;
/** Test-only minimal android.content.Context shadow for JVM boundary testing. */
public abstract class Context {
 public abstract File getNoBackupFilesDir();
 public abstract ContentResolver getContentResolver();
}
