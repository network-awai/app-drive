package org.json;
import java.util.*;
/** Test-only minimal org.json.JSONArray shadow for JVM boundary testing. */
public final class JSONArray {
 private final List<Object> items=new ArrayList<>();
 public JSONArray(){}
 public JSONArray(Collection<?> c){if(c!=null)items.addAll(c);}
 public int length(){return items.size();}
 public Object get(int i){return items.get(i);}
 @Override public String toString(){
  StringBuilder sb=new StringBuilder("[");
  for(int i=0;i<items.size();i++){
   if(i>0)sb.append(',');
   sb.append(JSONObject.valueToString(items.get(i)));
  }
  return sb.append(']').toString();
 }
}
