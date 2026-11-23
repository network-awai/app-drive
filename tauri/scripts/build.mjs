import './check-platform.mjs';
import {mkdir,copyFile,readFile,writeFile,rm,readdir} from 'node:fs/promises';import {fileURLToPath} from 'node:url';import{resolve}from'node:path';import{createHash}from'node:crypto';
const app=fileURLToPath(new URL('../',import.meta.url)),out=resolve(app,'dist'),hash=b=>createHash('sha256').update(b).digest('hex');await mkdir(out,{recursive:true});
for(const n of ['index.html','style.css','workspace.css','workspace.js','app.js','search.js','i18n.js','sync.js','sync-contract.js','account-link.js','account-view.js','restore-view.js','account-heads.js','wnfs-sync.js','wnfs-worker.js','share-controls.js'])await copyFile(resolve(app,'src',n),resolve(out,n));
for (const name of ['drive-packs.mjs', 'bounded-response.mjs', 'account-link-core.js', 'wnfs-account.js']) {
  await copyFile(resolve(app,'vendor/platform',name),resolve(out,name));
}
const icons=(await readdir(resolve(app,'src/icons'))).map(name=>'icons/'+name);
await mkdir(resolve(out,'icons'),{recursive:true});
for(const name of icons)await copyFile(resolve(app,'src',name),resolve(out,name));
await copyFile(resolve(app,'generated/core.js'),resolve(out,'core.js'));await copyFile(resolve(app,'generated/receipt.js'),resolve(out,'receipt.js'));await copyFile(resolve(app,'vendor/platform/host.js'),resolve(out,'host.js'));await copyFile(resolve(app,'vendor/platform/awai-ui/assets/noto-jp.woff2'),resolve(out,'noto-jp.woff2'));
await copyFile(resolve(app,'vendor/platform/kotoba-client-runtime.js'),resolve(out,'runtime.js'));
const {runtimeFor}=await import('../src/sync-contract.js');const origin=process.env.AWAI_DRIVE_SYNC_ORIGIN||'';
const wnfsEnabled=process.env.AWAI_DRIVE_WNFS_SYNC==='1';
if(wnfsEnabled&&!origin)throw Error('WNFS sync requires a WNFS-capable HTTPS pack origin');
const accountLinkEnabled=process.env.AWAI_DRIVE_AWAI_LINK==='1';
const capabilities=['awai.drive.sync', 'awai.drive.account'];
const descriptor=runtimeFor(origin,wnfsEnabled,accountLinkEnabled);
const wnfsBundle=resolve(out,'wnfs-core.js');
if(wnfsEnabled)await copyFile(resolve(app,'generated/wnfs-core.js'),wnfsBundle);
else await rm(wnfsBundle,{force:true});
// Receipt keys are pinned per origin in receipt-trust.json (AWAI-3314). An origin listed there must return a
// receipt signed by one of these keys; rotating the service key needs a rebuild. Other origins get none.
const trust=JSON.parse(await readFile(resolve(app,'receipt-trust.json'),'utf8')),receiptKeys=origin&&Object.hasOwn(trust,origin)?trust[origin]:null;
if(receiptKeys&&!(Array.isArray(receiptKeys.keys)&&receiptKeys.keys.length))throw Error('empty receipt key set');
// Keep retired epochs pinned (with notAfter/revokedAt) so receipts signed under them still verify.
if(receiptKeys){const seen=new Set();for(const k of receiptKeys.keys){if(typeof k.keyId!=='string'||!/^0x[0-9a-f]{64}$/.test(k.keyId)||!Number.isSafeInteger(k.keyEpoch)||k.keyEpoch<1||seen.has(k.keyId+'/'+k.keyEpoch))throw Error('invalid receipt key set');seen.add(k.keyId+'/'+k.keyEpoch);}}
await writeFile(resolve(out,'sync-config.js'),`export const syncOrigin=${JSON.stringify(origin)};export const wnfsEnabled=${wnfsEnabled};export const accountLinkEnabled=${accountLinkEnabled};export const receiptKeys=${JSON.stringify(receiptKeys)};`);
const {clientRuntimeProblems,clientRuntimeConnectSrc}=await import('../vendor/platform/kotoba-client-runtime.js');
if(clientRuntimeProblems(descriptor,capabilities,'http://tauri.localhost').length)throw Error('invalid sync descriptor');
const tauriFile=resolve(app,'src-tauri/tauri.conf.json'),config=JSON.parse(await readFile(tauriFile));
config.app.security.csp="default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; "+(wnfsEnabled?"worker-src 'self'; ":"")+clientRuntimeConnectSrc(descriptor,capabilities,[],'http://tauri.localhost').replace("'none'",'')+" ipc: http://ipc.localhost; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'";
await writeFile(tauriFile,JSON.stringify(config,null,2)+'\n');
await writeFile(resolve(out,'descriptor.json'),JSON.stringify(descriptor,null,2));
const names=['index.html','style.css','workspace.css','workspace.js','app.js','search.js','i18n.js','sync.js','sync-contract.js','account-link.js','account-view.js','restore-view.js','account-heads.js','wnfs-sync.js','wnfs-worker.js','share-controls.js','drive-packs.mjs','bounded-response.mjs','account-link-core.js','wnfs-account.js','sync-config.js','core.js','receipt.js','host.js','noto-jp.woff2','runtime.js','descriptor.json',...icons,...(wnfsEnabled?['wnfs-core.js']:[])],files={};for(const n of names)files[n]=hash(await readFile(resolve(out,n)));
await writeFile(resolve(out,'release.json'),JSON.stringify({schema:'awai.app.artifact.v1',id:'network.awai.drive',version:'0.1.0',tauri:'3.0.0-alpha.6',runtimeWry:'3.0.0-alpha.6',sharedCore:{source:'core/awai/drive/domain.cljc',sha256:hash(await readFile(resolve(app,'core/awai/drive/domain.cljc')))},profile:wnfsEnabled?'WNFS PrivateForest + bounded CARv1; Android device custody; explicit HTTPS sync':'Bounded owner-store object-v2/CAR snapshot; Android device custody; explicit HTTPS sync',files},null,2));console.log('Drive static artifact built with pinned canonical runtime');

const { distributeUi } = await import('../vendor/platform/awai-ui/distribute.mjs');
await distributeUi(out);
async function uiFiles(dir,rel=''){let result=[];for(const e of await readdir(resolve(dir,rel),{withFileTypes:true})){const n=rel?rel+'/'+e.name:e.name;if(e.isDirectory())result.push(...await uiFiles(dir,n));else if(e.isFile())result.push(n);}return result;}
const releasePath=resolve(out,'release.json'),updated=JSON.parse(await readFile(releasePath,'utf8'));
for(const n of await uiFiles(resolve(out,'awai-ui')))updated.files['awai-ui/'+n]=hash(await readFile(resolve(out,'awai-ui',n)));
await writeFile(releasePath,JSON.stringify(updated,null,2)+'\n');
