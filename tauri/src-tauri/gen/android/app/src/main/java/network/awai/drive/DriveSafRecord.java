package network.awai.drive;
import java.io.*;
import java.nio.charset.StandardCharsets;
/** Strict codec for the persisted SAF tree record
 *  (awai-drive-saf.v1.json in noBackupFilesDir). The record is a flat JSON
 *  object {"schema":"awai.drive.saf.v1","tree":"<content uri>"}; only string
 *  values are admitted so the parser stays small enough for the JVM harness.
 *  The picked tree URI is the only thing ever stored — never file names,
 *  contents or any internal state. */
public final class DriveSafRecord {
 public static final String SCHEMA="awai.drive.saf.v1";
 public static final int MAX_BYTES=4096;
 private DriveSafRecord(){}
 private static IOException bad(){return new IOException("invalid-saf-record");}
 private static final class P{
  final String s;int i;
  P(String s){this.s=s;}
  boolean end(){return i>=s.length();}
  char peek(){return end()?'\0':s.charAt(i);}
  char next()throws IOException{if(end())throw bad();return s.charAt(i++);}
  void ws(){while(!end()){char c=peek();if(c==' '||c=='\t'||c=='\n'||c=='\r')i++;else break;}}
  void expect(char c)throws IOException{if(next()!=c)throw bad();}
  String string()throws IOException{
   expect('"');StringBuilder out=new StringBuilder();
   for(;;){
    if(end())throw bad();char c=next();
    if(c=='"')return out.toString();
    if(c=='\\'){
     if(end())throw bad();char e=next();
     switch(e){
      case '"':out.append('"');break;case '\\':out.append('\\');break;case '/':out.append('/');break;
      case 'b':out.append('\b');break;case 'f':out.append('\f');break;case 'n':out.append('\n');break;
      case 'r':out.append('\r');break;case 't':out.append('\t');break;
      case 'u':
       if(i+4>s.length())throw bad();
       int v=0;for(int k=0;k<4;k++){char h=s.charAt(i++);v<<=4;v|=h>='0'&&h<='9'?h-'0':h>='a'&&h<='f'?h-'a'+10:h>='A'&&h<='F'?h-'A'+10:-1;if(v<0)throw bad();}
       out.append((char)v);break;
      default:throw bad();
     }
    }else{
     if(c<0x20)throw bad();
     out.append(c);
    }
   }
  }
 }
 /** Parse record bytes into the stored tree URI. Absent records are the
  *  caller's concern (missing file); any malformed content is
  *  invalid-saf-record. */
 public static String decode(byte[] data)throws IOException{
  if(data==null||data.length==0||data.length>MAX_BYTES)throw bad();
  P p=new P(new String(data,StandardCharsets.UTF_8));
  String schema=null,tree=null;
  p.ws();p.expect('{');p.ws();
  if(p.peek()!='}'){
   for(;;){
    p.ws();String k=p.string();p.ws();p.expect(':');p.ws();String v=p.string();
    if("schema".equals(k))schema=v;else if("tree".equals(k))tree=v;else throw bad();
    p.ws();char c=p.next();
    if(c=='}')break;
    if(c!=',')throw bad();
    p.ws();
   }
  }else p.next();
  p.ws();if(!p.end())throw bad();
  if(!SCHEMA.equals(schema)||tree==null||tree.isEmpty()||!tree.startsWith("content://"))throw bad();
  return tree;
 }
 /** Canonical record bytes for a picked tree URI. */
 public static byte[] encode(String tree)throws IOException{
  if(tree==null||tree.isEmpty()||!tree.startsWith("content://"))throw bad();
  StringBuilder s=new StringBuilder("{\"schema\":\"").append(SCHEMA).append("\",\"tree\":\"");
  for(int i=0;i<tree.length();i++){
   char c=tree.charAt(i);
   if(c=='"'||c=='\\')s.append('\\').append(c);
   else if(c<0x20)s.append(String.format("\\u%04x",(int)c));
   else s.append(c);
  }
  s.append("\"}");
  byte[] b=s.toString().getBytes(StandardCharsets.UTF_8);
  if(b.length>MAX_BYTES)throw bad();
  return b;
 }
}
