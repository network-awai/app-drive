import { createDrivePackClient } from './drive-packs.mjs';
import './core.js';
import{createClientConsentHost,createClientOperationBroker}from'./runtime.js';
import{runtimeFor}from'./sync-contract.js';import{syncOrigin,receiptKeys,wnfsWrite}from'./sync-config.js';import{verifyDrivePackReceipt}from'./receipt.js';
const core=window.AwaiDriveEncrypted;
const wnfs=window.AwaiDriveWNFS;
const b64=b=>{let s='';for(let i=0;i<b.length;i+=16384)s+=String.fromCharCode(...b.subarray(i,i+16384));return btoa(s);};
const unb64=s=>Uint8Array.from(atob(s),c=>c.charCodeAt(0));
const invoke=(op,extra={})=>window.__TAURI__.core.invoke('drive_call',{request:{op,...extra}});
const runtime=runtimeFor(syncOrigin),selectors=runtime.operations.map(o=>[o.capability,o.service,o.operation]);
const broker=createClientOperationBroker({runtime,requestedCapabilities:['awai.drive.sync'],approvedOperations:runtime.operations,hostSupportedOperations:selectors,consentHost:createClientConsentHost()});
async function session(initialize=false){let s=await invoke('vaultSession',{initialize});if(!s.initialized)throw Error('recovery-required');const secret=unb64(s.secret);if(!s.record){const record=await core.newRecord(secret);await invoke('vaultRecord',{record});s={...s,record};}return {...s,secret};}
const packs = createDrivePackClient({ origin: syncOrigin, capability: 'awai.drive.sync', broker, sha256: bytes => core.sha256(bytes) });

function verifyReceipt(receipt, expected) {
  if (!receiptKeys) return;
  try {
    verifyDrivePackReceipt(receipt, receiptKeys, expected);
  } catch (error) {
    throw Error('storage-receipt-invalid:' + String(error?.message || error).slice(0, 80), { cause: error });
  }
}
export const configured=Boolean(syncOrigin);
export async function sync(){const s=await session(true);try{
 const {entries}=await invoke('vaultInventory');const rows=[];
 for(const e of entries){if(e.directory)continue;const bytes=new Uint8Array(e.size);for(let at=0;at<e.size;at+=65531){const r=await invoke('vaultRead',{path:e.path,offset:at,length:Math.min(65531,e.size-at)});bytes.set(unb64(r.bytes),at);}if(await core.sha256(bytes)!==e.sha256)throw Error('source-size-changed');rows.push({path:e.path,bytes});}
 rows.directories=entries.filter(e=>e.directory).map(e=>e.path);const start=performance.now();const sealed=await (wnfsWrite?wnfs:core).sealSnapshot(s.record,s.secret,rows);const sealedAt=performance.now();const projection=sealed.basis?await inspect(sealed,s.record,s.secret,sealed.bytes):undefined;const publishAt=performance.now();const id=await packs.publish(sealed.bytes,verifyReceipt);const readbackAt=performance.now();
 await invoke('vaultCommit',{root:sealed.root,pack:id,origin:syncOrigin,entries});return {files:rows.length,root:sealed.root,pack:id,basis:sealed.basis,projection,bytes:sealed.bytes.length,sealMs:sealedAt-start,publishMs:readbackAt-publishAt};
 }finally{s.secret.fill(0);}}
async function inspect(summary, record, secret, bytes) {
  const rows = await wnfs.openSnapshot(record, secret, summary.root, bytes);
  try {
    if (summary.basis !== rows.basis) throw Error('snapshot-basis-mismatch');
    return projectRows(rows);
  } finally {
    for (const row of rows) row.bytes.fill(0);
  }
}
function projectRows(rows) {
  const started = performance.now();
  const query = wnfs.project(rows, rows.basis);
  const ready = performance.now();
  const matches = query(rows.basis, 'txt').length;
  return { matches, indexMs: ready - started, queryMs: performance.now() - ready };
}
export async function evict(){return invoke('vaultEvict',{origin:syncOrigin});}
export async function restore(){const s=await session();try{if(!s.root||!s.pack||s.origin!==syncOrigin)throw Error('recovery-required');const start=performance.now();const bytes=await packs.download(s.pack);const fetchedAt=performance.now();const rows=await wnfs.openSnapshot(s.record,s.secret,s.root,bytes);const openedAt=performance.now();const projection=rows.basis?projectRows(rows):undefined;
 // Authenticate and validate all plaintext before staging or publishing output.
 await invoke('vaultRestoreBegin');for(const r of rows){if(r.bytes.length===0)await invoke('vaultRestoreWrite',{path:r.path,offset:0,bytes:''});for(let at=0;at<r.bytes.length;at+=65531)await invoke('vaultRestoreWrite',{path:r.path,offset:at,bytes:b64(r.bytes.subarray(at,at+65531))});r.bytes.fill(0);}await invoke('vaultRestorePublish');return {files:rows.length,basis:rows.basis,projection,bytes:bytes.length,fetchMs:fetchedAt-start,openMs:openedAt-fetchedAt,restoreMs:performance.now()-openedAt};
 }finally{s.secret.fill(0);}}
export async function state(){const s=await invoke('vaultStatus');return {...s,synced:s.synced&&s.origin===syncOrigin,configured};}
