// Operator-side synthetic fixture builder for the two isolated Drive apps.
// No account session, native app data, or device private key is needed here.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { runInThisContext } from 'node:vm';
import { pathToFileURL } from 'node:url';

const args = process.argv.slice(2);
const publish = args[0] === '--publish';
const [dist, output, mac, android] = publish ? args.slice(1) : args;
assert(args.length === (publish ? 5 : 4),
  'Usage: qa-restore-fixture.mjs [--publish] DIST NEW_OUTPUT MAC_PUBLIC_KEY ANDROID_PUBLIC_KEY');
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const release = JSON.parse(await readFile(join(dist,'release.json'),'utf8'));
assert.equal(release.schema,'awai.app.artifact.v1');
assert.equal(release.id,'network.awai.drive');
globalThis.window = globalThis;
for (const name of ['core.js', 'wnfs-core.js']) {
  const bundle = await readFile(join(dist,name));
  assert.equal(sha(bundle),release.files[name],name+' does not match the build manifest');
  runInThisContext(bundle.toString('utf8'),{filename:name});
}
const recipients = await Promise.all([mac,android].map(async file =>
  AwaiDriveWnfs.validateRecipient(JSON.parse(await readFile(file,'utf8')))));
assert(recipients.every(recipient=>typeof recipient?.id==='string') &&
  recipients[0].id!==recipients[1].id,'Two distinct Drive public recipient keys are required');
const fixtureName = 'drive-qa-' + crypto.randomUUID();
const rows = [
  {path:fixtureName,directory:true},
  {path:fixtureName+'/one.bin',bytes:new Uint8Array(8192).fill(17)},
  {path:fixtureName+'/two.bin',bytes:new Uint8Array(16384).map((_,i)=>i%251)},
];
const expected = rows.filter(row=>row.bytes).map(row=>({path:row.path,size:row.bytes.length,sha256:sha(row.bytes)}));
const secret = crypto.getRandomValues(new Uint8Array(32));
const origin = 'https://yataverse.com';
try {
  const owner = await AwaiDriveEncrypted.newRecord(secret);
  const sealed = await AwaiDriveWnfs.seal(owner,secret,rows,recipients);
  const dataNames = sealed.packs.map(sha);
  const files = new Map(sealed.packs.map((bytes,i)=>[dataNames[i]+'.car',bytes]));
  const inputs = recipients.map(recipient=>{
    const grant = sealed.grants.find(item=>item.recipient===recipient.id);
    assert(grant,'Missing QA recipient capsule');
    const capsule = sha(grant.capsule.bytes);
    files.set(capsule+'.car',grant.capsule.bytes);
    return {schema:'awai.drive.wnfs.grant.v1',recipient:recipient.id,basis:sealed.basis,
      head:{schema:'awai.drive.wnfs.v1',origin,root:grant.capsule.root,capsule,packs:dataNames}};
  });
  await mkdir(resolve(output),{mode:0o700});
  for (const [name, bytes] of files) await writeFile(join(output,name),bytes,{mode:0o600,flag:'wx'});
  for (const [i,name] of ['mac','android'].entries()) {
    await writeFile(join(output,name+'.grant.json'),JSON.stringify(inputs[i])+'\n',{mode:0o600,flag:'wx'});
  }
  const plan = {basis:sealed.basis,expected,objects:[...files].map(([file,bytes])=>({file,sha256:sha(bytes),size:bytes.length}))};
  await writeFile(join(output,'manifest.json'),JSON.stringify(plan,null,2)+'\n',{mode:0o600,flag:'wx'});
  if (publish) {
    const load = async name => {
      const file = resolve(dist,name);
      assert.equal(sha(await readFile(file)),release.files[name],name+' does not match the build manifest');
      return import(pathToFileURL(file).href);
    };
    const {verifyDrivePackReceipt}=await load('receipt.js');
    const {receiptKeys,syncOrigin}=await load('sync-config.js');
    assert(syncOrigin===origin && receiptKeys?.keys?.length,'A Yataverse build with pinned receipt keys is required');
    const receipts=[];
    for(const [file,bytes] of files){
      const id=sha(bytes);
      const url=origin+'/awai-drive/v1/packs/'+id;
      const request=options=>fetch(url,{...options,redirect:'error',signal:AbortSignal.timeout(30_000)});
      const response=await request({method:'PUT',headers:{'content-type':'application/vnd.ipld.car'},body:bytes});
      assert(response.ok,'Synthetic pack upload failed: '+response.status);
      const receipt=await response.json();
      assert.equal(receipt.sha256,id);
      assert.equal(receipt.bytes,bytes.length);
      await verifyDrivePackReceipt(receipt,receiptKeys,{id,size:bytes.length});
      const readback=await request({method:'GET',headers:{'cache-control':'no-cache'}});
      assert.equal(readback.status,200);
      assert.equal(sha(new Uint8Array(await readback.arrayBuffer())),id);
      receipts.push({file,id,receiptCid:receipt.receiptCid});
    }
    await writeFile(join(output,'published.json'),JSON.stringify(receipts,null,2)+'\n',{mode:0o600,flag:'wx'});
  }
  console.log(JSON.stringify({basis:plan.basis,files:expected.length,objects:plan.objects.length,published:publish}));
} finally {
  secret.fill(0);
}
