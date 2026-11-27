import network.awai.drive.DriveSafRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
public class DriveSafRecordTest{
 static int checks=0;
 static void yes(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 static void eq(Object a,Object b){checks++;if(!java.util.Objects.equals(a,b))throw new AssertionError("check "+checks+" expected "+b+" got "+a);}
 static DriveSafRecord.Record dec(String json){
  checks++;
  try{return DriveSafRecord.decodeRecord(json.getBytes(StandardCharsets.UTF_8));}
  catch(Exception e){throw new AssertionError("check "+checks+" rejected valid record: "+e);}
 }
 static void badRec(String json){
  checks++;
  try{DriveSafRecord.decodeRecord(json.getBytes(StandardCharsets.UTF_8));throw new AssertionError("check "+checks+" accepted "+json);}
  catch(IOException e){yes("invalid-saf-record".equals(e.getMessage()));}
 }
 static void badRecBytes(byte[] b){
  checks++;
  try{DriveSafRecord.decodeRecord(b);throw new AssertionError("check "+checks+" accepted record bytes");}
  catch(IOException e){yes("invalid-saf-record".equals(e.getMessage()));}
 }
 static String decOwner(String json){
  checks++;
  try{return DriveSafRecord.decodeOwner(json.getBytes(StandardCharsets.UTF_8));}
  catch(Exception e){throw new AssertionError("check "+checks+" rejected valid owner marker: "+e);}
 }
 static void badOwner(String json){
  checks++;
  try{DriveSafRecord.decodeOwner(json.getBytes(StandardCharsets.UTF_8));throw new AssertionError("check "+checks+" accepted owner marker "+json);}
  catch(IOException e){yes("foreign-directory-refused".equals(e.getMessage()));}
 }
 static void badOwnerBytes(byte[] b){
  checks++;
  try{DriveSafRecord.decodeOwner(b);throw new AssertionError("check "+checks+" accepted owner bytes");}
  catch(IOException e){yes("foreign-directory-refused".equals(e.getMessage()));}
 }
 public static void main(String[] args)throws Exception{
  String uri="content://com.android.externalstorage.documents/tree/0123-ABCD%3AAndroid%2Fdata";
  String id="550e8400-e29b-41d4-a716-446655440000";
  // UUIDv4 validation and generation.
  yes(DriveSafRecord.validUuidV4(id));
  yes(DriveSafRecord.validUuidV4(DriveSafRecord.newInstanceId()));
  yes(!DriveSafRecord.validUuidV4(null));
  yes(!DriveSafRecord.validUuidV4(""));
  yes(!DriveSafRecord.validUuidV4("550E8400-E29B-41D4-A716-446655440000")); // uppercase rejected
  yes(!DriveSafRecord.validUuidV4("550e8400-e29b-11d4-a716-446655440000")); // v1 not v4
  yes(!DriveSafRecord.validUuidV4("550e8400-e29b-41d4-0716-446655440000")); // bad RFC4122 variant
  yes(!DriveSafRecord.validUuidV4("not-a-uuid"));
  // Canonical record roundtrip with appId + instanceId.
  byte[] enc=DriveSafRecord.encode(uri,id);
  yes(enc.length<=DriveSafRecord.MAX_BYTES);
  DriveSafRecord.Record rec=DriveSafRecord.decodeRecord(enc);
  eq(rec.tree,uri);eq(rec.appId,DriveSafRecord.APP_ID);eq(rec.instanceId,id);yes(rec.hasOwnership());
  String raw=new String(enc,StandardCharsets.UTF_8);
  yes(raw.contains("\"schema\":\"awai.drive.saf.v1\""));
  yes(raw.contains("\"appId\":\"network.awai.drive\""));
  yes(raw.contains("\"instanceId\":\""+id+"\""));
  // Whitespace and key order are tolerated.
  DriveSafRecord.Record reordered=dec(" { \"instanceId\" : \""+id+"\" , \"tree\" : \"content://a/b\" , \"appId\" : \"network.awai.drive\" , \"schema\" : \"awai.drive.saf.v1\" } \n");
  eq(reordered.tree,"content://a/b");eq(reordered.instanceId,id);yes(reordered.hasOwnership());
  // Legacy record ({schema,tree} without instanceId) decodes with hasOwnership()==false so ops fail closed.
  DriveSafRecord.Record legacy=dec("{\"schema\":\"awai.drive.saf.v1\",\"tree\":\"content://a/b\"}");
  eq(legacy.tree,"content://a/b");eq(legacy.appId,null);eq(legacy.instanceId,null);yes(!legacy.hasOwnership());
  // JSON string escapes round-trip through encode/decodeRecord.
  String tricky="content://a/b\"c\\d";
  eq(DriveSafRecord.decodeRecord(DriveSafRecord.encode(tricky,id)).tree,tricky);
  eq(dec("{\"schema\":\"awai.drive.saf.v1\",\"tree\":\"content://a/b\\\"c\"}").tree,"content://a/b\"c");
  eq(dec("{\"schema\":\"awai.drive.saf.v1\",\"tree\":\"content://a/b\\nc\"}").tree,"content://a/b\nc");
  // Strict rejection of malformed, duplicate-key, half-owned, or invalid records.
  badRecBytes(null);badRecBytes(new byte[0]);
  badRec("");badRec("{}");badRec("[]");badRec("null");badRec("{\"schema\":\"awai.drive.saf.v1\"}");
  badRec("{\"schema\":\"other.v9\",\"tree\":\"content://a/b\"}");
  badRec("{\"schema\":\"awai.drive.saf.v1\",\"tree\":\"\"}");
  badRec("{\"schema\":\"awai.drive.saf.v1\",\"tree\":\"http://a/b\"}");
  badRec("{\"schema\":\"awai.drive.saf.v1\",\"tree\":\"content://a/b\",\"extra\":\"x\"}");
  badRec("{\"schema\":\"awai.drive.saf.v1\",\"tree\":123}");
  badRec("{\"schema\":\"awai.drive.saf.v1\",\"tree\":\"content://a/b\",}");
  badRec("{\"schema\":\"awai.drive.saf.v1\",\"tree\":\"content://a/b\"} trailing");
  badRec("{\"schema\":\"awai.drive.saf.v1\",\"tree\":\"content://a/b\"");
  badRec("{\"schema\":\"awai.drive.saf.v1\",\"tree\":\"content://a/b\\q\"}");
  badRec("{\"schema\":\"awai.drive.saf.v1\",\"tree\":\"content://a/b\t\"}");
  badRec("{\"schema\":\"awai.drive.saf.v1\",\"tree\":\"content://a/b\",\"tree\":\"content://c/d\"}");
  badRec("{\"schema\":\"awai.drive.saf.v1\",\"tree\":\"content://a/b\",\"appId\":\"network.awai.drive\"}");
  badRec("{\"schema\":\"awai.drive.saf.v1\",\"tree\":\"content://a/b\",\"instanceId\":\""+id+"\"}");
  badRec("{\"schema\":\"awai.drive.saf.v1\",\"tree\":\"content://a/b\",\"appId\":\"other.app\",\"instanceId\":\""+id+"\"}");
  badRec("{\"schema\":\"awai.drive.saf.v1\",\"tree\":\"content://a/b\",\"appId\":\"network.awai.drive\",\"instanceId\":\"not-a-uuid\"}");
  // Encode rejects oversize, non-content URI, null URI, and invalid instanceId.
  checks++;try{DriveSafRecord.encode("content://"+"a".repeat(DriveSafRecord.MAX_BYTES),id);throw new AssertionError("check "+checks+" accepted oversize record");}catch(IOException e){yes("invalid-saf-record".equals(e.getMessage()));}
  checks++;try{DriveSafRecord.encode("file:///sdcard",id);throw new AssertionError("check "+checks+" accepted non-content uri");}catch(IOException e){yes("invalid-saf-record".equals(e.getMessage()));}
  checks++;try{DriveSafRecord.encode(null,id);throw new AssertionError("check "+checks+" accepted null uri");}catch(IOException e){yes("invalid-saf-record".equals(e.getMessage()));}
  checks++;try{DriveSafRecord.encode(uri,null);throw new AssertionError("check "+checks+" accepted null instanceId");}catch(IOException e){yes("invalid-saf-record".equals(e.getMessage()));}
  checks++;try{DriveSafRecord.encode(uri,"550e8400-e29b-11d4-a716-446655440000");throw new AssertionError("check "+checks+" accepted non-v4 instanceId");}catch(IOException e){yes("invalid-saf-record".equals(e.getMessage()));}
  // Ownership marker (.awai-cache-owner.v1.json) roundtrip & strict validation (SAF-OWN-02).
  byte[] ownerBytes=DriveSafRecord.encodeOwner(id);
  yes(ownerBytes.length<=DriveSafRecord.OWNER_MAX_BYTES);
  eq(DriveSafRecord.decodeOwner(ownerBytes),id);
  String ownerJson=new String(ownerBytes,StandardCharsets.UTF_8);
  eq(ownerJson,"{\"schema\":\"awai.saf-cache-owner.v1\",\"appId\":\"network.awai.drive\",\"cacheVersion\":1,\"instanceId\":\""+id+"\"}");
  eq(decOwner(" { \"cacheVersion\" : 1 , \"instanceId\" : \""+id+"\" , \"appId\" : \"network.awai.drive\" , \"schema\" : \"awai.saf-cache-owner.v1\" } "),id);
  // Owner marker rejects malformed JSON, missing/extra/duplicate keys, wrong schema/appId/cacheVersion/instanceId.
  badOwnerBytes(null);badOwnerBytes(new byte[0]);badOwnerBytes(new byte[DriveSafRecord.OWNER_MAX_BYTES+1]);
  badOwner("");badOwner("{}");badOwner("not-json");
  badOwner("{\"schema\":\"wrong\",\"appId\":\"network.awai.drive\",\"cacheVersion\":1,\"instanceId\":\""+id+"\"}");
  badOwner("{\"schema\":\"awai.saf-cache-owner.v1\",\"appId\":\"other.app\",\"cacheVersion\":1,\"instanceId\":\""+id+"\"}");
  badOwner("{\"schema\":\"awai.saf-cache-owner.v1\",\"appId\":\"network.awai.drive\",\"cacheVersion\":2,\"instanceId\":\""+id+"\"}");
  badOwner("{\"schema\":\"awai.saf-cache-owner.v1\",\"appId\":\"network.awai.drive\",\"cacheVersion\":0,\"instanceId\":\""+id+"\"}");
  badOwner("{\"schema\":\"awai.saf-cache-owner.v1\",\"appId\":\"network.awai.drive\",\"cacheVersion\":\"1\",\"instanceId\":\""+id+"\"}");
  badOwner("{\"schema\":\"awai.saf-cache-owner.v1\",\"appId\":\"network.awai.drive\",\"cacheVersion\":1.0,\"instanceId\":\""+id+"\"}");
  badOwner("{\"schema\":\"awai.saf-cache-owner.v1\",\"appId\":\"network.awai.drive\",\"cacheVersion\":01,\"instanceId\":\""+id+"\"}");
  badOwner("{\"schema\":\"awai.saf-cache-owner.v1\",\"appId\":\"network.awai.drive\",\"cacheVersion\":1,\"instanceId\":\"550e8400-e29b-11d4-a716-446655440000\"}");
  badOwner("{\"schema\":\"awai.saf-cache-owner.v1\",\"appId\":\"network.awai.drive\",\"cacheVersion\":1}");
  badOwner("{\"schema\":\"awai.saf-cache-owner.v1\",\"appId\":\"network.awai.drive\",\"cacheVersion\":1,\"instanceId\":\""+id+"\",\"extra\":\"x\"}");
  badOwner("{\"schema\":\"awai.saf-cache-owner.v1\",\"appId\":\"network.awai.drive\",\"cacheVersion\":1,\"cacheVersion\":1,\"instanceId\":\""+id+"\"}");
  badOwner("{\"schema\":\"awai.saf-cache-owner.v1\",\"appId\":\"network.awai.drive\",\"cacheVersion\":1,\"instanceId\":\""+id+"\"} trailing");
  checks++;try{DriveSafRecord.encodeOwner("bad-id");throw new AssertionError("check "+checks+" encoded bad owner id");}catch(IOException e){yes("foreign-directory-refused".equals(e.getMessage()));}
  System.out.println("DriveSafRecord: "+checks+" checks passed: record+owner marker codec roundtrip, UUIDv4 validation, legacy record fail-closed flag, strict rejection of malformed/wrong-field/duplicate-key records and markers.");
 }
}
