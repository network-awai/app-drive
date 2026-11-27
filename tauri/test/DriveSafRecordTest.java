import network.awai.drive.DriveSafRecord;
import java.nio.charset.StandardCharsets;import java.util.concurrent.Callable;
public class DriveSafRecordTest{
 static int checks=0;
 static void yes(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 static String dec(String json){checks++;try{return DriveSafRecord.decode(json.getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new AssertionError("check "+checks+" rejected valid record: "+e);}}
 static void bad(String json){checks++;try{DriveSafRecord.decode(json.getBytes(StandardCharsets.UTF_8));throw new AssertionError("check "+checks+" accepted "+json);}catch(java.io.IOException e){yes("invalid-saf-record".equals(e.getMessage()));}}
 static void badBytes(byte[] b){checks++;try{DriveSafRecord.decode(b);throw new AssertionError("check "+checks+" accepted record bytes");}catch(java.io.IOException e){yes("invalid-saf-record".equals(e.getMessage()));}}
 public static void main(String[] args)throws Exception{
  String uri="content://com.android.externalstorage.documents/tree/0123-ABCD%3AAndroid%2Fdata";
  // Canonical roundtrip; record stays a flat two-field document.
  byte[] enc=DriveSafRecord.encode(uri);
  yes(enc.length<=DriveSafRecord.MAX_BYTES);
  yes(uri.equals(DriveSafRecord.decode(enc)));
  yes(new String(enc,StandardCharsets.UTF_8).startsWith("{\"schema\":\"awai.drive.saf.v1\",\"tree\":\"content://"));
  // Whitespace and key order are tolerated; only the exact shape is admitted.
  yes("content://a/b".equals(dec(" { \"tree\" : \"content://a/b\" , \"schema\" : \"awai.drive.saf.v1\" } \n")));
  yes("content://a/b".equals(dec("{\"schema\":\"awai.drive.saf.v1\",\"tree\":\"content://a/b\"}")));
  // JSON string escapes round-trip through encode/decode.
  String tricky="content://a/b\"c\\d";
  yes(tricky.equals(DriveSafRecord.decode(DriveSafRecord.encode(tricky))));
  yes("content://a/b\"c".equals(dec("{\"schema\":\"awai.drive.saf.v1\",\"tree\":\"content://a/b\\\"c\"}")));
  yes("content://a/b\nc".equals(dec("{\"schema\":\"awai.drive.saf.v1\",\"tree\":\"content://a/b\\nc\"}")));
  // Anything outside the flat string map is invalid-saf-record, never repaired.
  badBytes(null);badBytes(new byte[0]);
  bad("");bad("{}");bad("[]");bad("null");bad("{\"schema\":\"awai.drive.saf.v1\"}");
  bad("{\"schema\":\"other.v9\",\"tree\":\"content://a/b\"}");
  bad("{\"schema\":\"awai.drive.saf.v1\",\"tree\":\"\"}");
  bad("{\"schema\":\"awai.drive.saf.v1\",\"tree\":\"http://a/b\"}");
  bad("{\"schema\":\"awai.drive.saf.v1\",\"tree\":\"content://a/b\",\"extra\":\"x\"}");
  bad("{\"schema\":\"awai.drive.saf.v1\",\"tree\":123}");
  bad("{\"schema\":\"awai.drive.saf.v1\",\"tree\":\"content://a/b\",}");
  bad("{\"schema\":\"awai.drive.saf.v1\",\"tree\":\"content://a/b\"} trailing");
  bad("{\"schema\":\"awai.drive.saf.v1\",\"tree\":\"content://a/b\"");
  bad("{\"schema\":\"awai.drive.saf.v1\",\"tree\":\"content://a/b\\q\"}");
  bad("{\"schema\":\"awai.drive.saf.v1\",\"tree\":\"content://a/b\t\"}");
  // Oversize record and non-tree URIs are refused on encode too.
  checks++;try{DriveSafRecord.encode("content://"+"a".repeat(DriveSafRecord.MAX_BYTES));throw new AssertionError("check "+checks+" accepted oversize record");}catch(java.io.IOException e){yes("invalid-saf-record".equals(e.getMessage()));}
  checks++;try{DriveSafRecord.encode("file:///sdcard");throw new AssertionError("check "+checks+" accepted non-content uri");}catch(java.io.IOException e){yes("invalid-saf-record".equals(e.getMessage()));}
  checks++;try{DriveSafRecord.encode(null);throw new AssertionError("check "+checks+" accepted null uri");}catch(java.io.IOException e){yes("invalid-saf-record".equals(e.getMessage()));}
  System.out.println("DriveSafRecord: "+checks+" checks passed: codec roundtrip, whitespace/key-order tolerance, escape handling, strict rejection of malformed/oversize/non-content records.");
 }
}
