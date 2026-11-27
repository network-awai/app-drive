package org.json;
import java.util.*;
/** Test-only minimal org.json.JSONObject shadow for JVM boundary testing. */
public final class JSONObject {
 private final Map<String,Object> map=new LinkedHashMap<>();
 public JSONObject put(String name,Object value){map.put(name,value);return this;}
 public JSONObject put(String name,boolean value){map.put(name,value);return this;}
 public JSONObject put(String name,int value){map.put(name,value);return this;}
 public JSONObject put(String name,long value){map.put(name,value);return this;}
 public boolean has(String name){return map.containsKey(name);}
 public Object get(String name){
  if(!map.containsKey(name))throw new RuntimeException("No value for "+name);
  return map.get(name);
 }
 public String getString(String name){
  Object v=get(name);
  if(v==null)throw new RuntimeException("Null value for "+name);
  return String.valueOf(v);
 }
 public boolean getBoolean(String name){
  Object v=get(name);
  if(v instanceof Boolean b)return b;
  throw new RuntimeException("Not boolean: "+name);
 }
 public int getInt(String name){
  Object v=get(name);
  if(v instanceof Number n)return n.intValue();
  throw new RuntimeException("Not int: "+name);
 }
 public long getLong(String name){
  Object v=get(name);
  if(v instanceof Number n)return n.longValue();
  throw new RuntimeException("Not long: "+name);
 }
 static String quote(String s){
  StringBuilder sb=new StringBuilder("\"");
  for(int i=0;i<s.length();i++){
   char c=s.charAt(i);
   if(c=='"'||c=='\\')sb.append('\\').append(c);
   else if(c=='\n')sb.append("\\n");
   else if(c=='\r')sb.append("\\r");
   else if(c=='\t')sb.append("\\t");
   else sb.append(c);
  }
  return sb.append('"').toString();
 }
 static String valueToString(Object v){
  if(v==null)return "null";
  if(v instanceof String s)return quote(s);
  if(v instanceof Boolean||v instanceof Number||v instanceof JSONObject||v instanceof JSONArray)return v.toString();
  return quote(v.toString());
 }
 @Override public String toString(){
  StringBuilder sb=new StringBuilder("{");
  boolean first=true;
  for(Map.Entry<String,Object> e:map.entrySet()){
   if(!first)sb.append(',');
   first=false;
   sb.append(quote(e.getKey())).append(':').append(valueToString(e.getValue()));
  }
  return sb.append('}').toString();
 }
}
