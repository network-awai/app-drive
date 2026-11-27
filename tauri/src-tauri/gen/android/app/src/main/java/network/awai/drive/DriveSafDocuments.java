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
 *  DriveSafSafAdapter (SAF-OWN-01..03, AWAI-SPEC-REG-SAF-B1-v0.2 §2.3).
 *  Queries 5 columns including COLUMN_FLAGS and confirms regular files via
 *  Os.fstat(S_ISREG) + st_size check.
 *
 *  <p><b>Honest Non-Goal / Residual Risk Disclosure:</b>
 *  Android {@code DocumentsContract} / {@code ContentResolver} exposes no
 *  {@code lstat(2)}, {@code O_NOFOLLOW}, or {@code COLUMN_IS_SYMLINK} API.
 *  Calling {@code openFileDescriptor} + {@code Os.fstat(S_ISREG)} confirms
 *  only that the opened target file descriptor refers to a regular file, NOT
 *  non-symlink path traversal on arbitrary providers. Generic 3rd-party SAF
 *  non-symlink proof is explicitly NOT claimed. Deletion safety on Android SAF
 *  therefore relies on the fail-closed
 *  {@code com.android.externalstorage.documents} allowlist
 *  ({@code ExternalStorageProvider}'s {@code FileSystemProvider.deleteDocument()}
 *  calls {@code File.delete()} / {@code unlink(2)} without following a symlink
 *  target, and standard Android FUSE/removable external storage volumes do not
 *  permit POSIX symlink creation inside app-managed {@code cache/}) layered
 *  with {@code SAF-OWN-01..03} (dedicated {@code cache/} subtree, dual-bound
 *  {@code .awai-cache-owner.v1.json} + {@code instanceId}, pinned doc IDs,
 *  {@code COLUMN_FLAGS} checks, and {@code Os.fstat(S_ISREG)} + {@code st_size}). */
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

 private void validateTree()throws IOException{
  if(tree==null)throw new IOException(DriveSafPolicy.ERR_PROVIDER);
  DriveSafPolicy.requireAllowedTreeUri(tree.toString());
  String docId;
  try{docId=DocumentsContract.getTreeDocumentId(tree);}
  catch(IllegalArgumentException e){throw new IOException(DriveSafPolicy.ERR_PROVIDER);}
  DriveSafPolicy.requireAllowedParts(tree.getScheme(),tree.getAuthority(),docId);
 }

 private Uri doc(String id){return DocumentsContract.buildDocumentUriUsingTree(tree,id);}

 private static DriveSafSafAdapter.Doc readRow(Cursor c){
  String id=c.isNull(0)?null:c.getString(0);
  String name=c.isNull(1)?null:c.getString(1);
  String mime=c.isNull(2)?null:c.getString(2);
  long size=c.isNull(3)?-1L:c.getLong(3);
  Integer flags=c.isNull(4)?null:Integer.valueOf(c.getInt(4));
  return new DriveSafSafAdapter.Doc(id,name,mime,size,flags);
 }

 @Override public String treeUri()throws IOException{
  if(tree==null)throw new IOException(DriveSafPolicy.ERR_PROVIDER);
  return tree.toString();
 }

 @Override public String rootId()throws IOException{
  validateTree();
  String root;
  try{root=DocumentsContract.getTreeDocumentId(tree);}
  catch(IllegalArgumentException e){throw new IOException(DriveSafPolicy.ERR_PROVIDER);}
  if(root==null||root.isEmpty())throw new IOException("cache-absent");
  return root;
 }

 @Override public DriveSafSafAdapter.Doc queryDoc(String docId)throws IOException{
  validateTree();
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
  validateTree();
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
  validateTree();
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
  validateTree();
  try(OutputStream out=resolver.openOutputStream(doc(docId))){
   if(out==null)throw new IOException("storage-unavailable");
   out.write(data);
  }catch(SecurityException e){throw new IOException("permission-lost");}
  catch(FileNotFoundException e){throw new IOException("storage-unavailable");}
 }

 @Override public InputStream openRead(String docId)throws IOException{
  validateTree();
  InputStream in;
  try{in=resolver.openInputStream(doc(docId));}
  catch(SecurityException e){throw new IOException("permission-lost");}
  catch(FileNotFoundException e){throw new IOException("storage-unavailable");}
  if(in==null)throw new IOException("storage-unavailable");
  return in;
 }

 @Override public boolean confirmRegularFile(String docId,long expectedSize)throws IOException{
  validateTree();
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
  validateTree();
  boolean gone;
  try{gone=DocumentsContract.deleteDocument(resolver,doc(docId));}
  catch(SecurityException e){throw new IOException("permission-lost");}
  catch(FileNotFoundException e){throw new IOException("storage-unavailable");}
  if(!gone)throw new IOException("storage-unavailable");
 }

 @Override public void locate()throws IOException{validateTree();adapter.locate();}
 @Override public void ensure()throws IOException{validateTree();adapter.ensure();}
 @Override public DriveSaf.Item[] children()throws IOException{validateTree();return adapter.children();}
 @Override public InputStream read(DriveSaf.Item item)throws IOException{validateTree();return adapter.read(item);}
 @Override public DriveSaf.Item create(String name,byte[] data)throws IOException{validateTree();return adapter.create(name,data);}
 @Override public boolean confirmRegularFile(DriveSaf.Item item)throws IOException{validateTree();return adapter.confirmRegularFile(item);}
 @Override public void delete(DriveSaf.Item item)throws IOException{validateTree();adapter.delete(item);}
}
