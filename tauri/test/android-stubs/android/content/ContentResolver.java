package android.content;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;
import java.util.*;
/** Test-only minimal android.content.ContentResolver shadow for JVM boundary testing. */
public abstract class ContentResolver {
 public abstract Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder);
 public abstract OutputStream openOutputStream(Uri uri)throws FileNotFoundException;
 public abstract InputStream openInputStream(Uri uri)throws FileNotFoundException;
 public abstract ParcelFileDescriptor openFileDescriptor(Uri uri,String mode)throws FileNotFoundException;
 public abstract void takePersistableUriPermission(Uri uri,int modeFlags);
 public abstract void releasePersistableUriPermission(Uri uri,int modeFlags);
 public abstract List<UriPermission> getPersistedUriPermissions();
}
