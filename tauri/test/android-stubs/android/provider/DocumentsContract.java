package android.provider;
import android.content.ContentResolver;
import android.net.Uri;
import java.io.FileNotFoundException;
import java.util.List;
/** Test-only minimal android.provider.DocumentsContract shadow with static
 *  call counters and pluggable backend for JVM boundary testing. */
public final class DocumentsContract {
 public static final class Document {
  public static final String COLUMN_DOCUMENT_ID="document_id";
  public static final String COLUMN_MIME_TYPE="mime_type";
  public static final String COLUMN_FLAGS="flags";
  public static final String MIME_TYPE_DIR="vnd.android.document/directory";
  public static final int FLAG_SUPPORTS_DELETE=1<<2;
  public static final int FLAG_VIRTUAL_DOCUMENT=1<<9;
 }

 public interface Backend {
  Uri createDocument(ContentResolver resolver,Uri parentDocumentUri,String mimeType,String displayName)throws FileNotFoundException;
  boolean deleteDocument(ContentResolver resolver,Uri documentUri)throws FileNotFoundException;
 }

 public static Backend backend;
 public static int getTreeDocumentIdCalls;
 public static int getDocumentIdCalls;
 public static int buildDocumentUriCalls;
 public static int buildChildDocumentsUriCalls;
 public static int createDocumentCalls;
 public static int deleteDocumentCalls;

 public static void resetCounts(){
  getTreeDocumentIdCalls=0;
  getDocumentIdCalls=0;
  buildDocumentUriCalls=0;
  buildChildDocumentsUriCalls=0;
  createDocumentCalls=0;
  deleteDocumentCalls=0;
 }

 public static int totalCalls(){
  return getTreeDocumentIdCalls+getDocumentIdCalls+buildDocumentUriCalls
   +buildChildDocumentsUriCalls+createDocumentCalls+deleteDocumentCalls;
 }

 public static String getTreeDocumentId(Uri treeUri){
  getTreeDocumentIdCalls++;
  List<String> paths=treeUri.getPathSegments();
  if(paths.size()>=2&&"tree".equals(paths.get(0)))return paths.get(1);
  throw new IllegalArgumentException("Invalid URI: "+treeUri);
 }

 public static String getDocumentId(Uri documentUri){
  getDocumentIdCalls++;
  List<String> paths=documentUri.getPathSegments();
  if(paths.size()>=4&&"tree".equals(paths.get(0))&&"document".equals(paths.get(2)))return paths.get(3);
  if(paths.size()>=2&&"document".equals(paths.get(0)))return paths.get(1);
  throw new IllegalArgumentException("Invalid URI: "+documentUri);
 }

 public static Uri buildDocumentUriUsingTree(Uri treeUri,String documentId){
  buildDocumentUriCalls++;
  String treeId=getTreeDocumentIdInternal(treeUri);
  return Uri.parse(treeUri.getScheme()+"://"+treeUri.getAuthority()
   +"/tree/"+Uri.encode(treeId)+"/document/"+Uri.encode(documentId));
 }

 public static Uri buildChildDocumentsUriUsingTree(Uri treeUri,String parentDocumentId){
  buildChildDocumentsUriCalls++;
  String treeId=getTreeDocumentIdInternal(treeUri);
  return Uri.parse(treeUri.getScheme()+"://"+treeUri.getAuthority()
   +"/tree/"+Uri.encode(treeId)+"/document/"+Uri.encode(parentDocumentId)+"/children");
 }

 private static String getTreeDocumentIdInternal(Uri treeUri){
  List<String> paths=treeUri.getPathSegments();
  if(paths.size()>=2&&"tree".equals(paths.get(0)))return paths.get(1);
  throw new IllegalArgumentException("Invalid URI: "+treeUri);
 }

 public static Uri createDocument(ContentResolver resolver,Uri parentDocumentUri,String mimeType,String displayName)throws FileNotFoundException{
  createDocumentCalls++;
  if(backend==null)throw new IllegalStateException("DocumentsContract.backend not set");
  return backend.createDocument(resolver,parentDocumentUri,mimeType,displayName);
 }

 public static boolean deleteDocument(ContentResolver resolver,Uri documentUri)throws FileNotFoundException{
  deleteDocumentCalls++;
  if(backend==null)throw new IllegalStateException("DocumentsContract.backend not set");
  return backend.deleteDocument(resolver,documentUri);
 }
}
