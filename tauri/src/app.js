import {bind_native} from './awai-ui/index.js';
bind_native(document.documentElement,{tonal:'.tonal,.secondary',text:'#files button'});
import*as encryptedSync from'./sync.js';
import './core.js';
import {translator,messages} from './i18n.js';
import {searchFiles,searchCatalog} from './search.js';
const {lang,t,bytes}=translator(navigator.language);document.documentElement.lang=lang;document.querySelectorAll('[data-i18n]').forEach(n=>n.textContent=t(n.dataset.i18n));document.querySelectorAll('[data-i18n-aria]').forEach(n=>n.setAttribute('aria-label',t(n.dataset.i18nAria)));
import {createHost} from './host.js';
import {clientRuntimeProblems,createClientConsentHost,createClientOperationBroker} from './runtime.js';
const descriptor={schema:'kotoba.app.client-runtime.v1',target:'browser',profile:'trusted-page-v1',entry:'index.html','local-state':{adapter:'memory',schema:'awai.drive.view.v1',version:1},workers:[],operations:[]};
if(clientRuntimeProblems(descriptor,[],location.origin).length)throw Error('invalid-runtime');
createClientOperationBroker({runtime:descriptor,requestedCapabilities:[],approvedOperations:[],hostSupportedOperations:[],consentHost:createClientConsentHost()});
const core=window.AwaiDriveCore,$=id=>document.getElementById(id),host=createHost({appId:'network.awai.drive'});let parent='',busy=false,mode='mkdir';

function message(s){$('status').textContent=s;}
async function call(op,extra={}){if(!core.validPath(parent))throw Error('invalid-path');return window.__TAURI__.core.invoke('drive_call',{request:{op,parent,...extra}});}
function fail(e) {
  const reason = String(e?.message || e);
  const key = reason === 'client operation was not approved' ? 'error.operation-denied' :
    Object.keys(messages[lang]).find(key => key.startsWith('error.') && reason.includes(key.slice(6)));
  const label = t(key || 'error.unknown');
  message(label);
  if ($('edit').open) $('form-error').textContent = label;
}
function renderEntries(entries, showLocation = false, cloud = false) {
  const rows = document.createDocumentFragment();
  for (const item of entries) {
    const row = document.createElement('li');
    const content = document.createElement(cloud ? 'div' : 'button');
    const label = document.createElement('span');
    const caption = document.createElement('small');
    label.textContent = (item.directory ? '▸ ' : '') + item.name;
    caption.textContent = cloud ? t(item.directory ? 'search.cloud.folder' : 'search.cloud.file') :
      item.directory ? t('folder.caption') : t('file.caption', { size: bytes(item.bytes) });
    content.append(label, caption);
    if (showLocation) {
      const location = document.createElement('small');
      location.textContent = item.path.split('/').slice(0, -1).join('/') || t(cloud ? 'search.cloud' : 'storage.root');
      content.append(location);
    }
    if (cloud && item.sourceDevice) {
      const source = document.createElement('small');
      source.textContent = t('search.source-device', { device: item.sourceDevice });
      content.append(source);
    }
    if (cloud) content.className = 'cloud-entry';
    else content.onclick = () => task(async () => {
      if (item.directory) {
        parent = item.path;
        await list();
        $('files').focus();
      } else {
        await call('open', { path: item.path });
      }
    });
    row.append(content);
    rows.append(row);
  }
  $('files').replaceChildren(rows);
}

async function list() {
  const { entries } = await call('list');
  $('search-heading').hidden = true;
  $('search-cloud-help').hidden = true;
  $('search-form').reset();
  $('path').closest('nav').hidden = false;
  $('import').closest('section').hidden = false;
  $('path').textContent = parent || t('storage.root');
  $('back').hidden = !parent;
  renderEntries(entries);
  message(entries.length ? '' : t('status.empty'));
  const syncState = await encryptedSync.state();
  $('evict').hidden = !encryptedSync.configured || !syncState.canEvict;
  $('restore-legacy').hidden = !syncState.legacyRestore;
  $('storage').textContent = t(syncState.synced ? 'sync.ready' : syncState.evicted ? 'sync.evicted' : encryptedSync.configured ? 'sync.dirty' : 'storage.state');
}

async function showSearch(query, kind, cloud) {
  $('path').closest('nav').hidden = true;
  $('import').closest('section').hidden = true;
  $('search-heading').textContent = t(query ? 'search.heading' : 'search.results', { query });
  $('search-heading').hidden = false;
  $('search-cloud-help').hidden = true;
  renderEntries([]);
  message(t(cloud ? 'search.cloud.running' : 'search.running'));
  const input = $('search-query');
  input.readOnly = true;
  try {
    const { entries, limited } = cloud
      ? searchCatalog(query, kind, await encryptedSync.cloudCatalog())
      : await searchFiles(query, async folder => (await call('list', { parent: folder })).entries, kind);
    renderEntries(entries, true, cloud);
    $('search-cloud-help').hidden = !cloud;
    const key = limited ? 'search.limited' : entries.length ? 'search.count' : 'search.empty';
    message(t(key, { count: new Intl.NumberFormat(lang).format(entries.length) }));
  } finally {
    input.readOnly = false;
  }
}

async function task(fn){if(busy)return;busy=true;document.querySelectorAll('main button, .drive-search select, #edit button').forEach(x=>x.disabled=true);try{await fn();}catch(e){fail(e);}finally{busy=false;document.querySelectorAll('main button, .drive-search select, #edit button').forEach(x=>x.disabled=false);}}
$('search-form').onsubmit = event => {
  event.preventDefault();
  const query = $('search-query').value.trim();
  const kind = $('search-kind').value;
  const cloud = encryptedSync.configured && $('search-scope').value === 'cloud';
  task(cloud || query || kind !== 'all' ? () => showSearch(query, kind, cloud) : list);
};
$('search-clear').onclick = () => task(async () => {
  $('search-query').focus();
  await list();
});
$('back').onclick=()=>task(async()=>{parent=parent.split('/').slice(0,-1).join('/');await list();});$('refresh').onclick=()=>task(list);
$('import').onclick=()=>task(async()=>{message(t('status.importing'));await call('pick');await list();message(t('status.saved'));});
function edit(op){mode=op;$('title').textContent=t(op==='mkdir'?'folder.create':'file.note');$('content-label').hidden=op==='mkdir';$('name').value='';$('content').value='';$('form-error').textContent='';$('edit').showModal();$('name').focus();}
$('folder').onclick=()=>edit('mkdir');$('note').onclick=()=>edit('createText');$('cancel').onclick=()=>$('edit').close();
$('entry').onsubmit=e=>{e.preventDefault();if(!core.validName($('name').value)){$('form-error').textContent=t('error.invalid-name');return;}task(async()=>{await call(mode,{name:$('name').value,text:$('content').value});$('edit').close();await list();message(t('status.saved'));});};
await task(async()=>{await host.initialize();await list();});

$('sync').onclick=()=>task(async()=>{message(t('sync.busy'));await encryptedSync.sync();await list();$('storage').textContent=t('sync.ready');message(t('sync.success'));});
async function restoreFiles(legacy=false) {
  message(t('sync.restoring'));
  const result=await encryptedSync.restore({legacy});
  parent=result.folder||'';
  await list();
  message(t(result.folder?'sync.restoredCopy':'sync.restored'));
}
$('restore').onclick=()=>task(()=>restoreFiles());
$('restore-legacy').onclick=()=>task(()=>restoreFiles(true));
$('evict').onclick=()=>{$('evict-confirm').showModal();};
$('evict-cancel').onclick=()=>$('evict-confirm').close();
$('evict-allow').onclick=()=>{$('evict-confirm').close();task(async()=>{await encryptedSync.evict();parent='';await list();$('storage').textContent=t('sync.evicted');});};
if(!encryptedSync.configured){$('sync').hidden=true;$('restore').hidden=true;$('evict').hidden=true;}
else $('search-scope-field').hidden = false;
