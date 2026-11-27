package android.util;
/** Test-only minimal android.util.Base64 shadow for JVM boundary testing. */
public final class Base64 {
 public static final int NO_WRAP=2;
 public static byte[] decode(String str,int flags){
  if(str==null)throw new IllegalArgumentException("null");
  return java.util.Base64.getDecoder().decode(str);
 }
 public static String encodeToString(byte[] input,int flags){
  return java.util.Base64.getEncoder().encodeToString(input);
 }
}
