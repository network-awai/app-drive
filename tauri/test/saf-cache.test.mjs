import test from 'node:test';import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {mkdtemp,readdir,readFile,rm,stat} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {dirname,join,relative,resolve} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {createSafCache,encodeBase64,decodeBase64,SAF_CACHE_ERRORS} from '../src/saf-cache.js';

const appDir=fileURLToPath(new URL('../',import.meta.url));
const sha256Hex=buf=>createHash('sha256').update(buf).digest('hex');

function extractStaticRelativeImports(source){
 const out=[];
 // Matches static ESM import/export declarations with relative ./ or ../ specifiers,
 // including minified forms without spaces (e.g. import*as x from'./sync.js';import{a}from'./b.js';).
 const re=/(?:^|[;\r\n}])\s*(?:import|export)\s*(?:(?:\*(?:\s*as\s+[\w$]+)?|\{[^}]*\}|[\w$]+(?:\s*,\s*(?:\*\s*as\s+[\w$]+|\{[^}]*\}))?)\s*from\s*)?['"](\.\.?\/[^'"]+)['"]/g;
 for(let m;(m=re.exec(source))!==null;)out.push(m[1]);
 return out;
}

test('base64 roundtrip across sizes and padding',()=>{
 for(const n of [0,1,2,3,4,5,100,65536]){
  const b=Uint8Array.from({length:n},(_,i)=>(i*31+7)%256);
  assert.deepEqual([...decodeBase64(encodeBase64(b))],[...b],"size "+n);
 }
 assert.throws(()=>decodeBase64("!!!="));assert.throws(()=>decodeBase64("abc"));
});

test('cache ops map to drive_call payloads and decode reads',async()=>{
 const sent=[];const oversizeSha="ef".repeat(32);
 const call=async(op,extra={})=>{sent.push({op,...extra});
  if(op==="cacheGet")return{sha256:extra.sha256,bytes:encodeBase64(new Uint8Array([9,8,7]))};
  if(op==="cachePut")return{sha256:extra.sha256,result:"stored"};
  if(op==="cacheVerify")return{picked:true,state:"ready",entries:2,verified:1,corrupt:0,oversize:1,oversizeNames:[oversizeSha],unreadable:0,unreadableNames:[],removed:0,clean:false};
  if(op==="cacheClear")return{deleted:2,preservedForeign:1,ownerPreserved:true};
  return{picked:true,state:"ready"};};
 const c=createSafCache(call);
 await c.pick();await c.status();
 const verified=await c.verify();
 assert.equal(verified.verified,1);
 assert.equal(verified.corrupt,0);
 assert.equal(verified.oversize,1);
 assert.deepEqual(verified.oversizeNames,[oversizeSha]);
 assert.equal(verified.unreadable,0);
 assert.deepEqual(verified.unreadableNames,[]);
 assert.equal(verified.clean,false);
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

test('typed errors propagate unchanged including foreign-directory-refused, storage-unavailable, and local-provider-required',async()=>{
 assert.equal(SAF_CACHE_ERRORS.FOREIGN_DIRECTORY_REFUSED,"foreign-directory-refused");
 assert.equal(SAF_CACHE_ERRORS.STORAGE_UNAVAILABLE,"storage-unavailable");
 assert.equal(SAF_CACHE_ERRORS.LOCAL_PROVIDER_REQUIRED,"local-provider-required");
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
 const disallowed=createSafCache(async()=>{throw Error(SAF_CACHE_ERRORS.LOCAL_PROVIDER_REQUIRED)});
 await assert.rejects(disallowed.pick,/local-provider-required/);
 await assert.rejects(disallowed.status,/local-provider-required/);
 await assert.rejects(disallowed.put("ab".repeat(32),new Uint8Array(1)),/local-provider-required/);
 await assert.rejects(disallowed.get("ab".repeat(32)),/local-provider-required/);
 await assert.rejects(disallowed.verify,/local-provider-required/);
 await assert.rejects(disallowed.clear,/local-provider-required/);
});

async function snapshotDir(dir){
 try{
  const entries=await readdir(dir,{withFileTypes:true});
  const out=[];
  for(const e of entries.sort((a,b)=>a.name.localeCompare(b.name))){
   const p=resolve(dir,e.name);
   if(e.isDirectory())out.push({name:e.name,type:'dir',children:await snapshotDir(p)});
   else if(e.isFile()){const s=await stat(p);out.push({name:e.name,type:'file',size:s.size,mtimeMs:s.mtimeMs});}
  }
  return out;
 }catch(e){
  if(e&&e.code==='ENOENT')return null;
  throw e;
 }
}

test('isolated AWAI_DRIVE_DIST_DIR build bundles saf-cache.js, satisfies release.json hashes, loads app.js via Node ESM, and leaves shared dist/ and tauri.conf.json untouched',async()=>{
 const tauriConfPath=resolve(appDir,'src-tauri/tauri.conf.json');
 const sharedDistDir=resolve(appDir,'dist');
 const priorTauriConf=await readFile(tauriConfPath,'utf8');
 const priorSharedDist=await snapshotDir(sharedDistDir);
 const tmpDist=await mkdtemp(join(tmpdir(),'awai-drive-dist-'));
 try{
  execFileSync(process.execPath,[resolve(appDir,'scripts/build.mjs')],{
   cwd:appDir,
   env:{...process.env,AWAI_DRIVE_DIST_DIR:tmpDist},
   stdio:'pipe',
   encoding:'utf8'
  });

  const distSafPath=resolve(tmpDist,'saf-cache.js');
  const safStat=await stat(distSafPath);
  assert.equal(safStat.isFile(),true,'tmpDist/saf-cache.js must exist after isolated build');

  const release=JSON.parse(await readFile(resolve(tmpDist,'release.json'),'utf8'));
  assert.equal(typeof release.files['saf-cache.js'],'string','release.json must list saf-cache.js');
  assert.equal(release.files['saf-cache.js'],sha256Hex(await readFile(distSafPath)),'release.json saf-cache.js sha256 mismatch');

  for(const [relName,expectedHash] of Object.entries(release.files)){
   const abs=resolve(tmpDist,relName);
   const st=await stat(abs);
   assert.equal(st.isFile(),true,`release.json file missing in tmpDist/: ${relName}`);
   assert.equal(sha256Hex(await readFile(abs)),expectedHash,`release.json sha256 mismatch for ${relName}`);
  }

  // Walk every static relative ESM import reachable from tmpDist/app.js.
  const entryPath=resolve(tmpDist,'app.js');
  const visited=new Set([entryPath]);
  const queue=[entryPath];
  const discoveredRel=new Set(['app.js']);
  while(queue.length){
   const cur=queue.shift();
   const src=await readFile(cur,'utf8');
   for(const spec of extractStaticRelativeImports(src)){
    const target=resolve(dirname(cur),spec);
    const rel=relative(tmpDist,target).replaceAll('\\','/');
    assert.ok(!rel.startsWith('..')&&!rel.startsWith('/'),`static import escapes tmpDist/: ${spec} from ${relative(tmpDist,cur)}`);
    const st=await stat(target);
    assert.equal(st.isFile(),true,`static import missing in tmpDist/: ${rel} (from ${relative(tmpDist,cur)})`);
    assert.equal(typeof release.files[rel],'string',`static import missing from release.json.files: ${rel}`);
    discoveredRel.add(rel);
    if(!visited.has(target)){visited.add(target);queue.push(target);}
   }
  }
  assert.equal(discoveredRel.has('saf-cache.js'),true,'tmpDist/app.js static import graph must reach saf-cache.js');

  // Direct ESM import of tmpDist/saf-cache.js checking all exports.
  const builtSaf=await import(pathToFileURL(distSafPath).href);
  assert.equal(typeof builtSaf.createSafCache,'function');
  assert.equal(typeof builtSaf.encodeBase64,'function');
  assert.equal(typeof builtSaf.decodeBase64,'function');
  assert.equal(builtSaf.SAF_CACHE_ERRORS.FOREIGN_DIRECTORY_REFUSED,'foreign-directory-refused');
  assert.equal(builtSaf.SAF_CACHE_ERRORS.STORAGE_UNAVAILABLE,'storage-unavailable');
  assert.equal(builtSaf.SAF_CACHE_ERRORS.LOCAL_PROVIDER_REQUIRED,'local-provider-required');

  // Load tmpDist/app.js directly through Node's real ESM loader in an isolated subprocess
  // with minimal DOM/window stubs so ./saf-cache.js and every sibling static import resolve cleanly.
  const appUrl=pathToFileURL(entryPath).href;
  const loaderScript=`
const noop=()=>{};
class StubClassList{
 constructor(){this._set=new Set();}
 add(...xs){for(const x of xs)this._set.add(x);}
 remove(...xs){for(const x of xs)this._set.delete(x);}
 toggle(x,force){if(force===undefined){if(this._set.has(x)){this._set.delete(x);return false;}this._set.add(x);return true;}if(force)this._set.add(x);else this._set.delete(x);return !!force;}
 contains(x){return this._set.has(x);}
}
class StubElement extends EventTarget{
 constructor(tag='div',id=''){
  super();
  this.tagName=String(tag).toUpperCase();
  this.id=id;
  this.dataset={};
  this.classList=new StubClassList();
  this.style={};
  this.attributes=new Map();
  this.children=[];
  this.hidden=false;
  this.disabled=false;
  this.readOnly=false;
  this.open=false;
  this.value='';
  this.textContent='';
  this.className='';
  this.hash='';
  this.lang='en';
  this.elements={kind:{value:'all'},scope:{value:'local'}};
 }
 setAttribute(k,v){this.attributes.set(k,String(v));}
 getAttribute(k){return this.attributes.get(k)??null;}
 removeAttribute(k){this.attributes.delete(k);}
 querySelectorAll(){return [];}
 querySelector(){return null;}
 closest(){return new StubElement('section');}
 matches(){return false;}
 append(...nodes){this.children.push(...nodes);}
 replaceChildren(...nodes){this.children=[...nodes];}
 after(){}
 focus(){}
 reset(){}
 requestSubmit(){}
 showModal(){this.open=true;}
 close(){this.open=false;}
 get parentElement(){return new StubElement('section');}
}
const elements=new Map();
const getEl=id=>{if(!elements.has(id))elements.set(id,new StubElement('div',id));return elements.get(id);};
const docEl=new StubElement('html');
const storageMap=new Map();
const localStorageStub={
 getItem:k=>storageMap.has(k)?storageMap.get(k):null,
 setItem:(k,v)=>{storageMap.set(k,String(v));},
 removeItem:k=>{storageMap.delete(k);},
 clear:()=>{storageMap.clear();}
};
const invoked=[];
const tauriStub={
 core:{
  invoke:async(cmd,args)=>{
   invoked.push({cmd,args});
   if(cmd==='awai_host_info')return{schema:'awai.shell.host.v1',agendaProjection:false};
   if(cmd==='drive_call'){
    const op=args?.request?.op;
    if(op==='list')return{entries:[]};
    if(op==='vaultStatus')return{synced:false,evicted:false,wnfs:false,legacy:false,origin:''};
    if(op==='cacheStatus')return{picked:false,state:'absent'};
    return{ok:true};
   }
   return{};
  }
 }
};
const win=Object.assign(globalThis,{
 HTMLElement:StubElement,
 CustomEvent:globalThis.CustomEvent||class CustomEvent extends Event{constructor(t,o){super(t,o);this.detail=o?.detail;}},
 MutationObserver:class MutationObserver{observe(){}disconnect(){}},
 matchMedia:()=>({matches:false,addEventListener:noop,removeEventListener:noop}),
 location:{origin:'http://tauri.localhost',href:'http://tauri.localhost/index.html',hash:''},
 localStorage:localStorageStub,
 __TAURI__:tauriStub,
 document:{
  documentElement:docEl,
  body:new StubElement('body'),
  getElementById:getEl,
  querySelectorAll:()=>[],
  querySelector:()=>null,
  createElement:tag=>new StubElement(tag),
  createDocumentFragment:()=>new StubElement('#fragment'),
  addEventListener:noop,
  removeEventListener:noop
 },
 addEventListener:noop,
 removeEventListener:noop
});
Object.defineProperty(globalThis,'navigator',{value:{language:'en-US',userAgent:'NodeTest'},configurable:true});
globalThis.window=win;
await import(${JSON.stringify(appUrl)});
const saf=globalThis.window.AwaiDriveSaf;
if(!saf||typeof saf.pick!=='function'||typeof saf.status!=='function'||typeof saf.put!=='function'||typeof saf.get!=='function'||typeof saf.verify!=='function'||typeof saf.clear!=='function'||typeof saf.release!=='function'){
 throw Error('window.AwaiDriveSaf missing expected methods');
}
const st=await saf.status();
if(st.state!=='absent')throw Error('unexpected cacheStatus stub result: '+JSON.stringify(st));
console.log(JSON.stringify({ok:true,methods:Object.keys(saf).sort(),invokedCount:invoked.length}));
`;
  const stdout=execFileSync(process.execPath,['--input-type=module','-e',loaderScript],{cwd:appDir,encoding:'utf8',stdio:'pipe'});
  const parsed=JSON.parse(stdout.trim().split('\n').pop());
  assert.equal(parsed.ok,true);
  assert.deepEqual(parsed.methods,['clear','get','pick','put','release','status','verify']);

  // Isolated build must never mutate shared dist/ or src-tauri/tauri.conf.json.
  assert.equal(await readFile(tauriConfPath,'utf8'),priorTauriConf,'src-tauri/tauri.conf.json must not be mutated by isolated build');
  assert.deepEqual(await snapshotDir(sharedDistDir),priorSharedDist,'shared dist/ must not be mutated by isolated build');
 }finally{
  await rm(tmpDist,{recursive:true,force:true});
 }
});
