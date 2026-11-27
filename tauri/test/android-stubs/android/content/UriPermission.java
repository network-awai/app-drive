package android.content;
import android.net.Uri;
/** Test-only minimal android.content.UriPermission shadow for JVM boundary testing. */
public final class UriPermission {
 private final Uri uri;
 private final boolean read,write;
 public UriPermission(Uri uri,boolean read,boolean write){
  this.uri=uri;this.read=read;this.write=write;
 }
 public Uri getUri(){return uri;}
 public boolean isReadPermission(){return read;}
 public boolean isWritePermission(){return write;}
}
