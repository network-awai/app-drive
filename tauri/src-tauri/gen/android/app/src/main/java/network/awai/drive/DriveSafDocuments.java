package network.awai.drive;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.*;
import java.util.*;
/** Thin Android ContentResolver / DocumentsContract bridge implementing
 *  DriveSafSafAdapter.Provider and delegating all DriveSaf.Tree operations to
 *  DriveSafSafAdapter (SAF-OWN-01..03). Queries 5 columns including
 *  COLUMN_FLAGS and confirms regular files via Os.fstat(S_ISREG) + size check. */
final class DriveSafDocuments implements DriveSaf.Tree, DriveSafSafAdapter.Provider {
 private static final String[] COLS={
  DocumentsContract.Document.COLUMN_DOCUMENT_ID,
  OpenableColumns.DISPLAY_NAME,
  DocumentsContract.Document.COLUMN_MIME_TYPE,
  OpenableColumns.SIZE,
  DocumentsContract.Document.COLUMN_FLAGS
 };
 private final ContentResolver resolver;
 private final Uri tree;
 private final DriveSafSafAdapter adapter;

 DriveSafDocuments(ContentResolver r,Uri tree){
  this.resolver=r;
  this.tree=tree;
  this.adapter=new DriveSafSafAdapter(this);
 }

 static boolean isUserDocument(String name){return DriveSaf.isUserDocument(name);}

 private Uri doc(String id){return DocumentsContract.buildDocumentUriUsingTree(tree,id);}

 private static DriveSafSafAdapter.Doc readRow(Cursor c){
  String id=c.isNull(0)?null:c.getString(0);
  String name=c.isNull(1)?null:c.getString(1);
  String mime=c.isNull(2)?null:c.getString(2);
  long size=c.isNull(3)?-1L:c.getLong(3);
  Integer flags=c.isNull(4)?null:Integer.valueOf(c.getInt(4));
  return new DriveSafSafAdapter.Doc(id,name,mime,size,flags);
 }

 @Override public String rootId()throws IOException{
  String root=DocumentsContract.getTreeDocumentId(tree);
  if(root==null||root.isEmpty())throw new IOException("cache-absent");
  return root;
 }

 @Override public DriveSafSafAdapter.Doc queryDoc(String docId)throws IOException{
  Cursor c;
  try{c=resolver.query(doc(docId),COLS,null,null,null);}
  catch(SecurityException e){throw new IOException("permission-lost");}
  catch(IllegalArgumentException e){return null;}
  if(c==null)return null;
  try(Cursor closeable=c){
   if(!closeable.moveToFirst())return null;
   return readRow(closeable);
  }
 }

 @Override public List<DriveSafSafAdapter.Doc> listChildren(String parentId)throws IOException{
  Cursor c;
  try{c=resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,parentId),COLS,null,null,null);}
  catch(SecurityException e){throw new IOException("permission-lost");}
  if(c==null)throw new IOException("cache-absent");
  List<DriveSafSafAdapter.Doc> out=new ArrayList<>();
  try(Cursor closeable=c){
   while(closeable.moveToNext())out.add(readRow(closeable));
  }
  return out;
 }

 @Override public DriveSafSafAdapter.Doc createDoc(String parentId,String mime,String name)throws IOException{
  Uri made;
  try{made=DocumentsContract.createDocument(resolver,doc(parentId),mime,name);}
  catch(SecurityException e){throw new IOException("permission-lost");}
  catch(FileNotFoundException e){throw new IOException("cache-absent");}
  if(made==null)throw new IOException("cache-absent");
  String madeId=DocumentsContract.getDocumentId(made);
  DriveSafSafAdapter.Doc queried=queryDoc(madeId);
  if(queried==null){
   try{DocumentsContract.deleteDocument(resolver,made);}catch(Exception ignore){}
   throw new IOException("storage-unavailable");
  }
  return queried;
 }

 @Override public void writeDoc(String docId,byte[] data)throws IOException{
  try(OutputStream out=resolver.openOutputStream(doc(docId))){
   if(out==null)throw new IOException("storage-unavailable");
   out.write(data);
  }catch(SecurityException e){throw new IOException("permission-lost");}
  catch(FileNotFoundException e){throw new IOException("storage-unavailable");}
 }

 @Override public InputStream openRead(String docId)throws IOException{
  InputStream in;
  try{in=resolver.openInputStream(doc(docId));}
  catch(SecurityException e){throw new IOException("permission-lost");}
  catch(FileNotFoundException e){throw new IOException("storage-unavailable");}
  if(in==null)throw new IOException("storage-unavailable");
  return in;
 }

 @Override public boolean confirmRegularFile(String docId,long expectedSize)throws IOException{
  try(ParcelFileDescriptor pfd=resolver.openFileDescriptor(doc(docId),"r")){
   if(pfd==null)return false;
   StructStat st=Os.fstat(pfd.getFileDescriptor());
   return st!=null&&OsConstants.S_ISREG(st.st_mode)&&(expectedSize<0||st.st_size==expectedSize);
  }catch(SecurityException e){
   throw new IOException("permission-lost");
  }catch(Exception e){
   return false;
  }
 }

 @Override public void deleteDoc(String docId)throws IOException{
  boolean gone;
  try{gone=DocumentsContract.deleteDocument(resolver,doc(docId));}
  catch(SecurityException e){throw new IOException("permission-lost");}
  catch(FileNotFoundException e){throw new IOException("storage-unavailable");}
  if(!gone)throw new IOException("storage-unavailable");
 }

 @Override public void locate()throws IOException{adapter.locate();}
 @Override public void ensure()throws IOException{adapter.ensure();}
 @Override public DriveSaf.Item[] children()throws IOException{return adapter.children();}
 @Override public InputStream read(DriveSaf.Item item)throws IOException{return adapter.read(item);}
 @Override public DriveSaf.Item create(String name,byte[] data)throws IOException{return adapter.create(name,data);}
 @Override public boolean confirmRegularFile(DriveSaf.Item item)throws IOException{return adapter.confirmRegularFile(item);}
 @Override public void delete(DriveSaf.Item item)throws IOException{adapter.delete(item);}
}
