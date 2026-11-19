import { createDrivePackClient } from './drive-packs.mjs';
import './core.js';
import {createDriveAccountLink} from './account-link.js';
import {accountHeadGuard, discoverAccountHeads} from './account-heads.js';
import { createWnfsSync } from './wnfs-sync.js';
import{createClientConsentHost,createClientOperationBroker}from'./runtime.js';
import{runtimeFor}from'./sync-contract.js';import{syncOrigin,receiptKeys,wnfsEnabled,accountLinkEnabled}from'./sync-config.js';import{verifyDrivePackReceipt}from'./receipt.js';
const core=window.AwaiDriveEncrypted;
const b64=b=>{let s='';for(let i=0;i<b.length;i+=16384)s+=String.fromCharCode(...b.subarray(i,i+16384));return btoa(s);};
const unb64=s=>Uint8Array.from(atob(s),c=>c.charCodeAt(0));
const invoke=(op,extra={})=>window.__TAURI__.core.invoke('drive_call',{request:{op,...extra}});
const runtime=runtimeFor(syncOrigin,wnfsEnabled),selectors=runtime.operations.map(o=>[o.capability,o.service,o.operation]);
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
const wnfs = createWnfsSync({ invoke, origin: syncOrigin, packs, verifyReceipt, sha256: core.sha256 });
export const configured=Boolean(syncOrigin);
export const sharingEnabled=Boolean(syncOrigin && wnfsEnabled);

export async function cloudCatalog() {
  if (!configured) throw Error('cloud-snapshot-unavailable');
  const saved = await invoke('vaultSession', { initialize: false });
  if (!saved.initialized || !saved.record) throw Error('cloud-snapshot-unavailable');
  const session = { ...saved, secret: unb64(saved.secret) };
  try {
    if (session.wnfs) {
      if (!wnfsEnabled || session.wnfs.origin !== syncOrigin) throw Error('cloud-snapshot-unavailable');
      return await wnfs.catalog(session);
    }
    if (!session.root || !session.pack || session.origin !== syncOrigin) throw Error('cloud-snapshot-unavailable');
    const bytes = await packs.download(session.pack);
    return await core.snapshotCatalog(session.record, session.secret, session.root, bytes);
  } finally {
    session.secret.fill(0);
  }
}

export async function sync(){const s=await session(true);try{
 if(wnfsEnabled)return await wnfs.sync(s);
 if(s.wnfs)throw Error('recovery-required');
 const {entries}=await invoke('vaultInventory');const rows=[];
 for(const e of entries){if(e.directory)continue;const bytes=new Uint8Array(e.size);for(let at=0;at<e.size;at+=65531){const r=await invoke('vaultRead',{path:e.path,offset:at,length:Math.min(65531,e.size-at)});bytes.set(unb64(r.bytes),at);}if(await core.sha256(bytes)!==e.sha256)throw Error('source-size-changed');rows.push({path:e.path,bytes});}
 rows.directories=entries.filter(e=>e.directory).map(e=>e.path);const sealed=await core.sealSnapshot(s.record,s.secret,rows);const id=await packs.publish(sealed.bytes,verifyReceipt);
 await invoke('vaultCommit',{root:sealed.root,pack:id,origin:syncOrigin,entries});return {files:rows.length,root:sealed.root,pack:id};
 }finally{s.secret.fill(0);}}
export async function evict(){return invoke('vaultEvict',{origin:syncOrigin});}
export async function restore({legacy=false, expectedPack, signal, progress}={}){const s=await session();try{
 if(expectedPack && s.wnfs?.capsule !== expectedPack)throw Error('snapshot-basis-mismatch');
 if(s.wnfs&&!legacy){if(!wnfsEnabled)throw Error('recovery-required');return await wnfs.restore(s, { signal, progress });}
 if(!s.root||!s.pack||s.origin!==syncOrigin)throw Error('recovery-required');const bytes=await packs.download(s.pack);const rows=await core.openSnapshot(s.record,s.secret,s.root,bytes);
 if(wnfsEnabled)return await wnfs.restoreCopy(rows.map(row=>({path:row.path,chunks:[row.bytes]})),true);
 // Authenticate and validate all plaintext before staging or publishing output.
 await invoke('vaultRestoreBegin');for(const r of rows){if(r.bytes.length===0)await invoke('vaultRestoreWrite',{path:r.path,offset:0,bytes:''});for(let at=0;at<r.bytes.length;at+=65531)await invoke('vaultRestoreWrite',{path:r.path,offset:at,bytes:b64(r.bytes.subarray(at,at+65531))});r.bytes.fill(0);}await invoke('vaultRestorePublish');return {files:rows.length};
 }finally{s.secret.fill(0);}}
export async function state(){const s=await invoke('vaultStatus');return {...s,canEvict:!wnfsEnabled&&!s.wnfs,legacyRestore:wnfsEnabled&&s.wnfs&&s.legacy,synced:s.synced&&s.origin===syncOrigin,configured};}

export async function inspectAuthorizedSnapshot(owner, expectedBasis, extension) {
 if(!wnfsEnabled)throw Error('recovery-required');
 return wnfs.inspect(owner,expectedBasis,extension);
}

async function sharing(action) {
 if(!wnfsEnabled)throw Error('recovery-required');
 const owner = await session(true);
 try {return await action(owner);} finally {owner.secret.fill(0);}
}
export const publicSnapshotRecipient = () => sharing(owner => wnfs.publicRecipient(owner));
export const grantSnapshotRecipient = recipient => sharing(owner => wnfs.grant(owner, recipient));
export const revokeSnapshotRecipient = id => sharing(() => wnfs.revoke(id));
export const restoreSharedSnapshot = (grant, controls) => sharing(owner => wnfs.restoreShared(owner, grant, controls));

export async function restorePoint() {
  if (!configured || !wnfsEnabled) return null;
  const { wnfs: head } = await invoke('vaultSession', { initialize: false });
  if (head?.origin !== syncOrigin) return null;
  const files = (head.entries ?? []).filter(entry => !entry.directory);
  return {
    pack: head.capsule, basis: /^b[a-z2-7]{20,128}$/.test(head.basis) ? head.basis : null,
    count: files.length, size: files.reduce((total, item) => total + item.size, 0),
  };
}

export function parseRestoreGrant(text) {
  let grant;
  try { grant = JSON.parse(text); } catch { throw Error('invalid-snapshot-grant'); }
  if (Array.isArray(grant) && grant.length === 1) [grant] = grant;
  const head = grant?.head;
  const digest = /^[a-f0-9]{64}$/;
  if (grant?.schema !== 'awai.drive.wnfs.grant.v1' || !/^kv1:[A-Za-z0-9_-]{43}$/.test(grant.recipient) ||
      !/^b[a-z2-7]{20,128}$/.test(grant.basis) || head?.schema !== 'awai.drive.wnfs.v1' ||
      head.origin !== syncOrigin || !digest.test(head.root) || !digest.test(head.capsule) ||
      !Array.isArray(head.packs) || head.packs.length < 1 || head.packs.length > 8 ||
      head.packs.some(id => typeof id !== 'string' || !digest.test(id)) ||
      new Set([head.capsule, ...head.packs]).size !== head.packs.length + 1) throw Error('invalid-snapshot-grant');
  return { schema: grant.schema, recipient: grant.recipient, basis: grant.basis, head: {
    schema: head.schema, origin: head.origin, root: head.root, capsule: head.capsule, packs: head.packs,
  } };
}

export const restoreOrigin = syncOrigin;
export async function catalogAuthorizedAccount(grant) {
  if (!configured || !wnfsEnabled) throw Error('recovery-required');
  return wnfs.catalogAuthorizedAccount(grant);
}


// Only an authenticated host may provide the discovery callback and issuer key.
export async function catalogVerifiedRecipientHeads({requestHeads, issuerKey, checkCurrentPolicy}) {
  if (!configured || !wnfsEnabled || !accountLinkEnabled) throw Error('recovery-required');
  if (typeof checkCurrentPolicy !== 'function') throw Error('account-head-policy-unavailable');
  const link = createDriveAccountLink({call: invoke});
  const account = await link.linkedAccount();
  if (!account) throw Error('invalid-account-snapshot-grant');
  const device = await link.linkedDevice();
  const saved = await invoke('vaultSession', {initialize: false});
  if (!saved.initialized || !saved.record) throw Error('recovery-required');
  const secret = unb64(saved.secret);
  try {
    const reader = {record: saved.record, secret};
    const recipient = await wnfs.publicRecipient(reader);
    const headList = await discoverAccountHeads({
      requestHeads, issuerKey, subject: account.accountDid, device, recipient: recipient.id,
    });
    const current = await link.linkedAccount();
    if (current?.accountDid !== account.accountDid) throw Error('invalid-account-snapshot-grant');
    const guard = accountHeadGuard(headList, checkCurrentPolicy);
    return await wnfs.catalogAccountRecipients(reader, headList, guard);
  } finally {
    secret.fill(0);
  }
}
