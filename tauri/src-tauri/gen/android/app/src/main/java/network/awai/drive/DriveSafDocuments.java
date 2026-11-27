package network.awai.drive;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.*;
/** DocumentsContract adapter for DriveSaf.Tree. Every call stays inside the
 *  "cache" document of the user-picked SAF tree; document ids, never display
 *  names, address entries, and the picked tree is the only URI ever touched.
 *  SAF has no atomic rename, so integrity comes from per-read SHA-256
 *  verification rather than rename atomicity. */
final class DriveSafDocuments implements DriveSaf.Tree {
 private static final String[] COLS={DocumentsContract.Document.COLUMN_DOCUMENT_ID,OpenableColumns.DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,OpenableColumns.SIZE};
 private final ContentResolver resolver;private final Uri tree;private String cache;
 DriveSafDocuments(ContentResolver r,Uri tree){resolver=r;this.tree=tree;}
 private Uri doc(String id){return DocumentsContract.buildDocumentUriUsingTree(tree,id);}
 private Cursor query(Uri uri,String[] cols)throws IOException{
  Cursor c;try{c=resolver.query(uri,cols,null,null,null);}catch(SecurityException e){cache=null;throw new IOException("permission-lost");}
  if(c==null){cache=null;throw new IOException("cache-absent");}return c;
 }
 private void drop(Uri u){try{DocumentsContract.deleteDocument(resolver,u);}catch(Exception ignore){}}
 /** Create a document and require the provider to keep the exact display name;
 *  SAF providers auto-rename on conflicts, which would break name=digest. */
 private Uri create(Uri parent,String mime,String name)throws IOException{
  Uri made;try{made=DocumentsContract.createDocument(resolver,parent,mime,name);}catch(SecurityException e){throw new IOException("permission-lost");}
  if(made==null)throw new IOException("cache-absent");
  Cursor c=null;
  try{c=resolver.query(made,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null);}
  catch(SecurityException e){drop(made);throw new IOException("permission-lost");}
  try{
   if(c==null){drop(made);throw new IOException("storage-unavailable");}
   if(!c.moveToFirst()||!name.equals(c.getString(0))){drop(made);throw new IOException("file-exists");}
  }finally{if(c!=null)c.close();}
  return made;
 }
 public void ensure()throws IOException{
  if(cache!=null){
   Cursor c=null;
   try{c=resolver.query(doc(cache),new String[]{OpenableColumns.DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null);}
   catch(SecurityException e){cache=null;throw new IOException("permission-lost");}
   try{
    if(c!=null&&c.moveToFirst()&&DriveSaf.DIR.equals(c.getString(0))&&DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(1)))return;
   }finally{if(c!=null)c.close();}
   cache=null;
  }
  String root=DocumentsContract.getTreeDocumentId(tree);
  try(Cursor c=query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,root),COLS)){
   while(c.moveToNext())if(DriveSaf.DIR.equals(c.getString(1))&&DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))){cache=c.getString(0);return;}
  }
  cache=DocumentsContract.getDocumentId(create(doc(root),DocumentsContract.Document.MIME_TYPE_DIR,DriveSaf.DIR));
 }
 private String id(String name)throws IOException{
  ensure();
  try(Cursor c=query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,cache),COLS)){
   while(c.moveToNext()){String n=c.getString(1);if(n!=null&&name.equals(n))return c.getString(0);}
  }
  return null;
 }
 public DriveSaf.Item[] children()throws IOException{
  ensure();List<DriveSaf.Item> out=new ArrayList<>();
  try(Cursor c=query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,cache),COLS)){
   while(c.moveToNext()){String n=c.getString(1);if(n!=null)out.add(new DriveSaf.Item(n,DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2)),c.isNull(3)?-1:c.getLong(3)));}
  }
  return out.toArray(new DriveSaf.Item[0]);
 }
 public InputStream read(String name)throws IOException{
  String i=id(name);if(i==null)throw new IOException("blob-absent");
  InputStream in;
  try{in=resolver.openInputStream(doc(i));}
  catch(SecurityException e){throw new IOException("permission-lost");}
  catch(FileNotFoundException e){throw new IOException("storage-unavailable");}
  if(in==null)throw new IOException("storage-unavailable");
  return in;
 }
 public void create(String name,byte[] data)throws IOException{
  ensure();if(id(name)!=null)throw new IOException("file-exists");
  Uri made=create(doc(cache),"application/octet-stream",name);
  try(OutputStream out=resolver.openOutputStream(made)){
   if(out==null){drop(made);throw new IOException("storage-unavailable");}
   out.write(data);
  }catch(SecurityException e){drop(made);throw new IOException("permission-lost");}
  catch(IOException|RuntimeException e){drop(made);throw new IOException("storage-unavailable");}
 }
 public void delete(String name)throws IOException{
  String i=id(name);if(i==null)return;
  boolean gone;try{gone=DocumentsContract.deleteDocument(resolver,doc(i));}
  catch(SecurityException e){throw new IOException("permission-lost");}
  if(!gone)throw new IOException("storage-unavailable");
 }
}
