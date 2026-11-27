package android.net;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Test-only minimal android.net.Uri shadow for JVM boundary testing. */
public final class Uri {
 private final String raw,scheme,authority,encodedPath;
 private final List<String> pathSegments;

 private Uri(String raw,String scheme,String authority,String encodedPath,List<String> pathSegments){
  this.raw=raw;this.scheme=scheme;this.authority=authority;
  this.encodedPath=encodedPath;this.pathSegments=Collections.unmodifiableList(pathSegments);
 }

 public static Uri parse(String uriString){
  if(uriString==null)throw new NullPointerException("uriString");
  int colon=uriString.indexOf(':');
  String scheme=colon>0?uriString.substring(0,colon):null;
  String authority=null;
  String path="";
  if(scheme!=null&&uriString.startsWith("://",colon)){
   int authStart=colon+3;
   int pathStart=uriString.length();
   for(int i=authStart;i<uriString.length();i++){
    char c=uriString.charAt(i);
    if(c=='/'||c=='?'||c=='#'){pathStart=i;break;}
   }
   authority=uriString.substring(authStart,pathStart);
   int pathEnd=uriString.length();
   for(int i=pathStart;i<uriString.length();i++){
    char c=uriString.charAt(i);
    if(c=='?'||c=='#'){pathEnd=i;break;}
   }
   path=pathStart<pathEnd?uriString.substring(pathStart,pathEnd):"";
  }else if(scheme!=null){
   int restStart=colon+1;
   int end=uriString.length();
   for(int i=restStart;i<uriString.length();i++){
    char c=uriString.charAt(i);
    if(c=='?'||c=='#'){end=i;break;}
   }
   path=uriString.substring(restStart,end);
  }
  List<String> segs=new ArrayList<>();
  if(!path.isEmpty()){
   String trimmed=path.startsWith("/")?path.substring(1):path;
   if(!trimmed.isEmpty()){
    for(String s:trimmed.split("/",-1)){
     if(!s.isEmpty())segs.add(decode(s));
    }
   }
  }
  return new Uri(uriString,scheme,authority,path,segs);
 }

 public static String encode(String s){
  if(s==null)return null;
  byte[] b=s.getBytes(StandardCharsets.UTF_8);
  StringBuilder out=new StringBuilder(b.length*2);
  for(byte v:b){
   int u=v&0xff;
   if((u>='a'&&u<='z')||(u>='A'&&u<='Z')||(u>='0'&&u<='9')||u=='-'||u=='_'||u=='.'||u=='~'){
    out.append((char)u);
   }else{
    out.append('%').append(Character.toUpperCase(Character.forDigit((u>>4)&15,16)))
       .append(Character.toUpperCase(Character.forDigit(u&15,16)));
   }
  }
  return out.toString();
 }

 public static String decode(String s){
  if(s==null)return null;
  ByteArrayOutputStream out=new ByteArrayOutputStream(s.length());
  for(int i=0;i<s.length();){
   char c=s.charAt(i);
   if(c=='%'&&i+2<s.length()){
    int hi=Character.digit(s.charAt(i+1),16);
    int lo=Character.digit(s.charAt(i+2),16);
    if(hi>=0&&lo>=0){out.write((hi<<4)|lo);i+=3;continue;}
   }
   byte[] utf=String.valueOf(c).getBytes(StandardCharsets.UTF_8);
   out.write(utf,0,utf.length);
   i++;
  }
  return out.toString(StandardCharsets.UTF_8);
 }

 public String getScheme(){return scheme;}
 public String getAuthority(){return authority;}
 public String getEncodedPath(){return encodedPath;}
 public List<String> getPathSegments(){return pathSegments;}
 @Override public String toString(){return raw;}
 @Override public boolean equals(Object o){return o instanceof Uri u&&Objects.equals(raw,u.raw);}
 @Override public int hashCode(){return Objects.hashCode(raw);}
}
