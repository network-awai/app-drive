package android.database;
import java.io.Closeable;
/** Test-only minimal android.database.Cursor shadow for JVM boundary testing. */
public interface Cursor extends Closeable {
 boolean moveToFirst();
 boolean moveToNext();
 boolean isNull(int columnIndex);
 String getString(int columnIndex);
 long getLong(int columnIndex);
 int getInt(int columnIndex);
 @Override void close();
}
