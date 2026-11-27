import test from 'node:test';import assert from 'node:assert/strict';
import {createSafCache,encodeBase64,decodeBase64} from '../src/saf-cache.js';
test('base64 roundtrip across sizes and padding',()=>{
 for(const n of [0,1,2,3,4,5,100,65536]){
  const b=Uint8Array.from({length:n},(_,i)=>(i*31+7)%256);
  assert.deepEqual([...decodeBase64(encodeBase64(b))],[...b],"size "+n);
 }
 assert.throws(()=>decodeBase64("!!!="));assert.throws(()=>decodeBase64("abc"));
});
test('cache ops map to drive_call payloads and decode reads',async()=>{
 const sent=[];const call=async(op,extra={})=>{sent.push({op,...extra});
  if(op==="cacheGet")return{sha256:extra.sha256,bytes:encodeBase64(new Uint8Array([9,8,7]))};
  if(op==="cachePut")return{sha256:extra.sha256,result:"stored"};
  return{picked:true,state:"ready"};};
 const c=createSafCache(call);
 await c.pick();await c.status();await c.verify();await c.clear();await c.release();
 const blob=Uint8Array.from({length:1000},(_,i)=>(i*17)%256);
 await c.put("ab".repeat(32),blob);
 const got=await c.get("cd".repeat(32));
 assert.deepEqual([...got.bytes],[9,8,7]);
 assert.deepEqual(sent.map(s=>s.op),["cachePick","cacheStatus","cacheVerify","cacheClear","cacheRelease","cachePut","cacheGet"]);
 assert.equal(sent[5].sha256,"ab".repeat(32));
 assert.equal(sent[5].bytes,encodeBase64(blob));
 assert.deepEqual([...decodeBase64(sent[5].bytes)],[...blob]);
 assert.equal(sent[6].sha256,"cd".repeat(32));
});
test('typed errors propagate unchanged',async()=>{
 const c=createSafCache(async()=>{throw Error("permission-lost")});
 await assert.rejects(c.status,/permission-lost/);
 await assert.rejects(c.put("ab".repeat(32),new Uint8Array(1)),/permission-lost/);
});
