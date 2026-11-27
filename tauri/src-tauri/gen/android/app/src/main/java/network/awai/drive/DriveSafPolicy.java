package network.awai.drive;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
/** Pure-Java fail-closed SAF tree URI policy (AWAI-SPEC-REG-SAF-B1-v0.2 §2.3).
 *  Zero android.* imports so every positive and negative URI/authority/docId
 *  boundary is directly JVM-verified. Only local ExternalStorageProvider trees
 *  (content://com.android.externalstorage.documents/tree/<volume>:<rel>) are
 *  permitted for the SAF ciphertext cache. Any violation throws
 *  IOException("local-provider-required"), kept orthogonal to
 *  "foreign-directory-refused" (SAF-OWN-02). */
public final class DriveSafPolicy {
 public static final String ALLOWED_SCHEME="content";
 public static final String ALLOWED_AUTHORITY="com.android.externalstorage.documents";
 public static final String ERR_PROVIDER="local-provider-required";
 private static final String REQUIRED_PREFIX=ALLOWED_SCHEME+"://"+ALLOWED_AUTHORITY+"/tree/";

 private DriveSafPolicy(){}

 private static IOException reject(){return new IOException(ERR_PROVIDER);}

 private static int hexVal(char c){
  if(c>='0'&&c<='9')return c-'0';
  if(c>='a'&&c<='f')return c-'a'+10;
  if(c>='A'&&c<='F')return c-'A'+10;
  return -1;
 }

 private static boolean hasControlOrBackslash(String s){
  for(int i=0;i<s.length();i++){
   char c=s.charAt(i);
   if(c=='\0'||c<0x20||c==0x7f||c=='\\')return true;
  }
  return false;
 }

 private static String strictPercentDecodeUtf8(String raw)throws IOException{
  ByteArrayOutputStream bytes=new ByteArrayOutputStream(raw.length());
  for(int i=0;i<raw.length();){
   char c=raw.charAt(i);
   if(c=='%'){
    if(i+2>=raw.length())throw reject();
    int hi=hexVal(raw.charAt(i+1));
    int lo=hexVal(raw.charAt(i+2));
    if(hi<0||lo<0)throw reject();
    bytes.write((hi<<4)|lo);
    i+=3;
   }else{
    byte[] utf8=String.valueOf(c).getBytes(StandardCharsets.UTF_8);
    bytes.write(utf8,0,utf8.length);
    i++;
   }
  }
  try{
   return StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes.toByteArray()))
    .toString();
  }catch(CharacterCodingException e){
   throw reject();
  }
 }

 /** Validate already-parsed URI parts (scheme, authority, decoded treeDocumentId).
  *  Fails closed with IOException("local-provider-required") on any mismatch
  *  or unsafe treeDocumentId. */
 public static void requireAllowedParts(String scheme,String authority,String treeDocumentId)throws IOException{
  if(!ALLOWED_SCHEME.equals(scheme)||!ALLOWED_AUTHORITY.equals(authority))throw reject();
  if(treeDocumentId==null||treeDocumentId.isEmpty())throw reject();
  if(hasControlOrBackslash(treeDocumentId))throw reject();
  if(treeDocumentId.contains(".."))throw reject();
  int colon=treeDocumentId.indexOf(':');
  if(colon<=0)throw reject();
  String volume=treeDocumentId.substring(0,colon);
  for(int i=0;i<volume.length();i++){
   char c=volume.charAt(i);
   if(c=='/'||c=='?'||c=='#'||c=='@'||Character.isWhitespace(c))throw reject();
  }
 }

 /** Validate a raw SAF tree URI string end-to-end: exact content scheme,
  *  exact com.android.externalstorage.documents authority (no userinfo/port),
  *  single /tree/<encodedTreeDocumentId> segment (no query/fragment/extra /),
  *  strict UTF-8 percent-decoding, and safe treeDocumentId with non-empty
  *  volume prefix before ':'. */
 public static void requireAllowedTreeUri(String uriString)throws IOException{
  if(uriString==null||uriString.isEmpty())throw reject();
  if(hasControlOrBackslash(uriString))throw reject();
  if(!uriString.startsWith(REQUIRED_PREFIX))throw reject();
  String rawSegment=uriString.substring(REQUIRED_PREFIX.length());
  if(rawSegment.isEmpty())throw reject();
  if(rawSegment.contains(".."))throw reject();
  for(int i=0;i<rawSegment.length();i++){
   char c=rawSegment.charAt(i);
   if(c=='/'||c=='?'||c=='#'||c=='\\'||c=='\0'||c<0x20||c==0x7f)throw reject();
  }
  String decoded=strictPercentDecodeUtf8(rawSegment);
  requireAllowedParts(ALLOWED_SCHEME,ALLOWED_AUTHORITY,decoded);
 }

 /** Non-throwing predicate equivalent to requireAllowedTreeUri. */
 public static boolean isAllowedTreeUri(String uriString){
  try{
   requireAllowedTreeUri(uriString);
   return true;
  }catch(IOException e){
   return false;
  }
 }
}
