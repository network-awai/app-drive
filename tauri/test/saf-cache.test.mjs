import test from 'node:test';import assert from 'node:assert/strict';
import {createSafCache,encodeBase64,decodeBase64,SAF_CACHE_ERRORS} from '../src/saf-cache.js';
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
  if(op==="cacheVerify")return{picked:true,state:"ready",entries:1,verified:1,corrupt:0,unreadable:0,unreadableNames:[],removed:0,clean:true};
  if(op==="cacheClear")return{deleted:2,preservedForeign:1,ownerPreserved:true};
  return{picked:true,state:"ready"};};
 const c=createSafCache(call);
 await c.pick();await c.status();
 const verified=await c.verify();assert.equal(verified.unreadable,0);assert.equal(verified.clean,true);
 const cleared=await c.clear();assert.equal(cleared.deleted,2);assert.equal(cleared.preservedForeign,1);assert.equal(cleared.ownerPreserved,true);
 await c.release();
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
test('typed errors propagate unchanged including foreign-directory-refused and storage-unavailable',async()=>{
 assert.equal(SAF_CACHE_ERRORS.FOREIGN_DIRECTORY_REFUSED,"foreign-directory-refused");
 assert.equal(SAF_CACHE_ERRORS.STORAGE_UNAVAILABLE,"storage-unavailable");
 const lost=createSafCache(async()=>{throw Error("permission-lost")});
 await assert.rejects(lost.status,/permission-lost/);
 await assert.rejects(lost.put("ab".repeat(32),new Uint8Array(1)),/permission-lost/);
 const foreign=createSafCache(async()=>{throw Error(SAF_CACHE_ERRORS.FOREIGN_DIRECTORY_REFUSED)});
 await assert.rejects(foreign.pick,/foreign-directory-refused/);
 await assert.rejects(foreign.put("ab".repeat(32),new Uint8Array(1)),/foreign-directory-refused/);
 await assert.rejects(foreign.verify,/foreign-directory-refused/);
 await assert.rejects(foreign.clear,/foreign-directory-refused/);
 const unavailable=createSafCache(async()=>{throw Error(SAF_CACHE_ERRORS.STORAGE_UNAVAILABLE)});
 await assert.rejects(unavailable.put("ab".repeat(32),new Uint8Array(1)),/storage-unavailable/);
 await assert.rejects(unavailable.get("ab".repeat(32)),/storage-unavailable/);
});
