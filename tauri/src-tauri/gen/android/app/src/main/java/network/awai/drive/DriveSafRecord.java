package network.awai.drive;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
/** Strict codec for the persisted SAF tree record
 *  (awai-drive-saf.v1.json in noBackupFilesDir) and the SAF directory
 *  ownership marker (.awai-cache-owner.v1.json inside <picked>/cache/).
 *  Local record stores {"schema":"awai.drive.saf.v1","tree":"<content uri>",
 *  "appId":"network.awai.drive","instanceId":"<uuidv4>"}; legacy records
 *  without instanceId decode with hasOwnership()==false so cache operations
 *  fail closed with foreign-directory-refused while cacheRelease can still
 *  drop the grant. The owner marker stores {"schema":"awai.saf-cache-owner.v1",
 *  "appId":"network.awai.drive","cacheVersion":1,"instanceId":"<uuidv4>"}. */
public final class DriveSafRecord {
 public static final String SCHEMA="awai.drive.saf.v1";
 public static final String APP_ID="network.awai.drive";
 public static final String OWNER_FILE=".awai-cache-owner.v1.json";
 public static final String OWNER_SCHEMA="awai.saf-cache-owner.v1";
 public static final int CACHE_VERSION=1;
 public static final int MAX_BYTES=4096;
 public static final int OWNER_MAX_BYTES=1024;
 private static final Pattern UUID_V4=Pattern.compile(
  "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
 private DriveSafRecord(){}
 public static final class Record {
  public final String tree,appId,instanceId;
  public Record(String tree,String appId,String instanceId){
   this.tree=tree;this.appId=appId;this.instanceId=instanceId;
  }
  public boolean hasOwnership(){return APP_ID.equals(appId)&&validUuidV4(instanceId);}
 }
 public static boolean validUuidV4(String s){return s!=null&&UUID_V4.matcher(s).matches();}
 public static String newInstanceId(){return UUID.randomUUID().toString().toLowerCase(Locale.ROOT);}
 private static IOException bad(String code){return new IOException(code);}
 private static final class P{
  final String s,err;int i;
  P(String s,String err){this.s=s;this.err=err;}
  boolean end(){return i>=s.length();}
  char peek(){return end()?'\0':s.charAt(i);}
  char next()throws IOException{if(end())throw bad(err);return s.charAt(i++);}
  void ws(){while(!end()){char c=peek();if(c==' '||c=='\t'||c=='\n'||c=='\r')i++;else break;}}
  void expect(char c)throws IOException{if(next()!=c)throw bad(err);}
  String string()throws IOException{
   expect('"');StringBuilder out=new StringBuilder();
   for(;;){
    if(end())throw bad(err);char c=next();
    if(c=='"')return out.toString();
    if(c=='\\'){
     if(end())throw bad(err);char e=next();
     switch(e){
      case '"':out.append('"');break;case '\\':out.append('\\');break;case '/':out.append('/');break;
      case 'b':out.append('\b');break;case 'f':out.append('\f');break;case 'n':out.append('\n');break;
      case 'r':out.append('\r');break;case 't':out.append('\t');break;
      case 'u':
       if(i+4>s.length())throw bad(err);
       int v=0;for(int k=0;k<4;k++){char h=s.charAt(i++);v<<=4;v|=h>='0'&&h<='9'?h-'0':h>='a'&&h<='f'?h-'a'+10:h>='A'&&h<='F'?h-'A'+10:-1;if(v<0)throw bad(err);}
       out.append((char)v);break;
      default:throw bad(err);
     }
    }else{
     if(c<0x20)throw bad(err);
     out.append(c);
    }
   }
  }
  int nonNegativeInt()throws IOException{
   char c=peek();
   if(c=='0'){
    i++;
    char n=peek();
    if((n>='0'&&n<='9')||n=='.'||n=='e'||n=='E')throw bad(err);
    return 0;
   }
   if(c<'1'||c>'9')throw bad(err);
   long v=0;
   while(!end()){
    char d=peek();
    if(d>='0'&&d<='9'){v=v*10+(d-'0');if(v>Integer.MAX_VALUE)throw bad(err);i++;}
    else break;
   }
   char n=peek();
   if(n=='.'||n=='e'||n=='E')throw bad(err);
   return (int)v;
  }
 }
 private static void appendEscaped(StringBuilder out,String value){
  for(int i=0;i<value.length();i++){
   char c=value.charAt(i);
   if(c=='"'||c=='\\')out.append('\\').append(c);
   else if(c<0x20)out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));
   else out.append(c);
  }
 }
 /** Parse persisted record bytes into a Record. Absent records are the
  *  caller's concern (missing file); any malformed content is
  *  invalid-saf-record. Legacy records with only {schema,tree} decode with
  *  null appId/instanceId so callers fail closed with
  *  foreign-directory-refused on cache operations. */
 public static Record decodeRecord(byte[] data)throws IOException{
  if(data==null||data.length==0||data.length>MAX_BYTES)throw bad("invalid-saf-record");
  P p=new P(new String(data,StandardCharsets.UTF_8),"invalid-saf-record");
  String schema=null,tree=null,appId=null,instanceId=null;
  boolean seenSchema=false,seenTree=false,seenAppId=false,seenInstanceId=false;
  p.ws();p.expect('{');p.ws();
  if(p.peek()!='}'){
   for(;;){
    p.ws();String k=p.string();p.ws();p.expect(':');p.ws();String v=p.string();
    if("schema".equals(k)){if(seenSchema)throw bad("invalid-saf-record");seenSchema=true;schema=v;}
    else if("tree".equals(k)){if(seenTree)throw bad("invalid-saf-record");seenTree=true;tree=v;}
    else if("appId".equals(k)){if(seenAppId)throw bad("invalid-saf-record");seenAppId=true;appId=v;}
    else if("instanceId".equals(k)){if(seenInstanceId)throw bad("invalid-saf-record");seenInstanceId=true;instanceId=v;}
    else throw bad("invalid-saf-record");
    p.ws();char c=p.next();
    if(c=='}')break;
    if(c!=',')throw bad("invalid-saf-record");
    p.ws();
   }
  }else p.next();
  p.ws();if(!p.end())throw bad("invalid-saf-record");
  if(!SCHEMA.equals(schema)||tree==null||tree.isEmpty()||!tree.startsWith("content://"))throw bad("invalid-saf-record");
  if(!seenAppId&&!seenInstanceId)return new Record(tree,null,null);
  if(!seenAppId||!seenInstanceId||!APP_ID.equals(appId)||!validUuidV4(instanceId))throw bad("invalid-saf-record");
  return new Record(tree,appId,instanceId);
 }
 /** Canonical record bytes for a picked tree URI and ownership instanceId. */
 public static byte[] encode(String tree,String instanceId)throws IOException{
  if(tree==null||tree.isEmpty()||!tree.startsWith("content://")||!validUuidV4(instanceId))throw bad("invalid-saf-record");
  StringBuilder s=new StringBuilder("{\"schema\":\"").append(SCHEMA).append("\",\"tree\":\"");
  appendEscaped(s,tree);
  s.append("\",\"appId\":\"").append(APP_ID).append("\",\"instanceId\":\"").append(instanceId).append("\"}");
  byte[] b=s.toString().getBytes(StandardCharsets.UTF_8);
  if(b.length>MAX_BYTES)throw bad("invalid-saf-record");
  return b;
 }
 /** Canonical ownership marker bytes for <picked>/cache/.awai-cache-owner.v1.json
  *  (SAF-OWN-02). */
 public static byte[] encodeOwner(String instanceId)throws IOException{
  if(!validUuidV4(instanceId))throw bad("foreign-directory-refused");
  String s="{\"schema\":\""+OWNER_SCHEMA+"\",\"appId\":\""+APP_ID+"\",\"cacheVersion\":"+CACHE_VERSION+",\"instanceId\":\""+instanceId+"\"}";
  byte[] b=s.getBytes(StandardCharsets.UTF_8);
  if(b.length>OWNER_MAX_BYTES)throw bad("foreign-directory-refused");
  return b;
 }
 /** Strictly validate and decode .awai-cache-owner.v1.json bytes, returning its
  *  UUIDv4 instanceId. Any malformed JSON, unexpected key, wrong schema/appId/
  *  cacheVersion, or invalid instanceId fails closed with
  *  foreign-directory-refused. */
 public static String decodeOwner(byte[] data)throws IOException{
  if(data==null||data.length==0||data.length>OWNER_MAX_BYTES)throw bad("foreign-directory-refused");
  P p=new P(new String(data,StandardCharsets.UTF_8),"foreign-directory-refused");
  String schema=null,appId=null,instanceId=null;
  Integer cacheVersion=null;
  boolean seenSchema=false,seenAppId=false,seenVersion=false,seenInstanceId=false;
  p.ws();p.expect('{');p.ws();
  if(p.peek()!='}'){
   for(;;){
    p.ws();String k=p.string();p.ws();p.expect(':');p.ws();
    if("schema".equals(k)){if(seenSchema)throw bad("foreign-directory-refused");seenSchema=true;schema=p.string();}
    else if("appId".equals(k)){if(seenAppId)throw bad("foreign-directory-refused");seenAppId=true;appId=p.string();}
    else if("cacheVersion".equals(k)){if(seenVersion)throw bad("foreign-directory-refused");seenVersion=true;cacheVersion=p.nonNegativeInt();}
    else if("instanceId".equals(k)){if(seenInstanceId)throw bad("foreign-directory-refused");seenInstanceId=true;instanceId=p.string();}
    else throw bad("foreign-directory-refused");
    p.ws();char c=p.next();
    if(c=='}')break;
    if(c!=',')throw bad("foreign-directory-refused");
    p.ws();
   }
  }else p.next();
  p.ws();if(!p.end())throw bad("foreign-directory-refused");
  if(!seenSchema||!seenAppId||!seenVersion||!seenInstanceId)throw bad("foreign-directory-refused");
  if(!OWNER_SCHEMA.equals(schema)||!APP_ID.equals(appId)||cacheVersion==null||cacheVersion.intValue()!=CACHE_VERSION||!validUuidV4(instanceId))
   throw bad("foreign-directory-refused");
  return instanceId;
 }
}
