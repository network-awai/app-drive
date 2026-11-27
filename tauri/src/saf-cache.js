// Narrow client for the Android SAF ciphertext-cache ops (AWAI-4123).
// No UI: the acceptance fixture reaches these through the drive_call
// passthrough. Bytes travel base64 inside the 128 KiB request envelope, so
// native ops bound blob size; the cache itself only ever holds verified,
// re-fetchable ciphertext under the picked tree's cache/ subtree.
const alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
export function encodeBase64(bytes){
 let out="";
 for(let i=0;i<bytes.length;i+=3){
  const a=bytes[i],b=i+1<bytes.length?bytes[i+1]:undefined,c=i+2<bytes.length?bytes[i+2]:undefined;
  const n=(a<<16)|((b||0)<<8)|(c||0);
  out+=alphabet[n>>18&63]+alphabet[n>>12&63]+(b===undefined?"=":alphabet[n>>6&63])+(c===undefined?"=":alphabet[n&63]);
 }
 return out;
}
export function decodeBase64(text){
 if(text==="")return new Uint8Array(0);
 if(typeof text!=="string"||text.length%4!==0)throw Error("invalid-base64");
 const last=text.charCodeAt(text.length-1),prev=text.charCodeAt(text.length-2);
 const pad=(last===61?1:0)+(last===61&&prev===61?1:0);
 const out=new Uint8Array(text.length/4*3-pad);let o=0,acc=0,bits=0;
 for(let i=0;i<text.length;i++){
  const ch=text[i];if(ch==="=")break;
  const v=alphabet.indexOf(ch);if(v<0)throw Error("invalid-base64");
  acc=(acc<<6)|v;bits+=6;
  if(bits>=8){bits-=8;out[o++]=(acc>>bits)&255;acc&=(1<<bits)-1;}
 }
 if(o!==out.length)throw Error("invalid-base64");
 return out;
}
export const SAF_CACHE_ERRORS=Object.freeze({
 CANCELLED:"cache-cancelled",
 ABSENT:"cache-absent",
 PERMISSION_LOST:"permission-lost",
 PERSIST_UNSUPPORTED:"persist-unsupported",
 INVALID_RECORD:"invalid-saf-record",
 INVALID_BYTES:"invalid-bytes",
 BLOB_ABSENT:"blob-absent",
 CORRUPT:"cache-corrupt",
 SHA_MISMATCH:"sha-mismatch",
 LIMIT:"cache-limit",
 ENTRY_LIMIT:"entry-limit",
 STORAGE_UNAVAILABLE:"storage-unavailable",
 FOREIGN_DIRECTORY_REFUSED:"foreign-directory-refused",
});
export function createSafCache(call){
 return {
  pick:()=>call("cachePick"),
  status:()=>call("cacheStatus"),
  put:(sha256,bytes)=>call("cachePut",{sha256,bytes:encodeBase64(bytes)}),
  get:(sha256)=>call("cacheGet",{sha256}).then(r=>r&&typeof r.bytes==="string"?{...r,bytes:decodeBase64(r.bytes)}:r),
  verify:()=>call("cacheVerify"),
  clear:()=>call("cacheClear"),
  release:()=>call("cacheRelease"),
 };
}
