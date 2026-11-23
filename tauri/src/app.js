import { mountWorkspace } from './workspace.js';
import { mountWnfsSharing } from './share-controls.js';
import {bind_native} from './awai-ui/index.js';
bind_native(document.documentElement,{tonal:'.tonal,.secondary',text:'.workspace-popup button'});
import*as encryptedSync from'./sync.js';
import './core.js';
import {translator,messages} from './i18n.js';
import {searchFiles,searchCatalog,fileKind} from './search.js';
import {mountAccount} from './account-view.js';
import {mountRestore} from './restore-view.js';
import {accountLinkEnabled, wnfsEnabled} from './sync-config.js';
const {lang,t,bytes}=translator(navigator.language);document.documentElement.lang=lang;document.querySelectorAll('[data-i18n]').forEach(n=>n.textContent=t(n.dataset.i18n));document.querySelectorAll('[data-i18n-aria]').forEach(n=>n.setAttribute('aria-label',t(n.dataset.i18nAria)));
import {createHost} from './host.js';
import {clientRuntimeProblems,createClientConsentHost,createClientOperationBroker} from './runtime.js';
const descriptor={schema:'kotoba.app.client-runtime.v1',target:'browser',profile:'trusted-page-v1',entry:'index.html','local-state':{adapter:'memory',schema:'awai.drive.view.v1',version:1},workers:[],operations:[]};
if(clientRuntimeProblems(descriptor,[],location.origin).length)throw Error('invalid-runtime');
createClientOperationBroker({runtime:descriptor,requestedCapabilities:[],approvedOperations:[],hostSupportedOperations:[],consentHost:createClientConsentHost()});
const core=window.AwaiDriveCore,$=id=>document.getElementById(id),host=createHost({appId:'network.awai.drive'});let parent='',busy=false,mode='mkdir';

const route=()=>location.hash||'#files';
const viewNames={'#home':'workspace.home','#files':'nav.files','#shared':'workspace.shared',
  '#starred':'workspace.starred','#trash':'workspace.trash','#device-panel':'workspace.devices','#cloud-restore':'sync.restore'};
function syncNavigation() {
  const section = route();
  for(const link of document.querySelectorAll('.drive-nav a:not(.workspace-brand)')){
    if(link.hash===(section==='#cloud-restore'?'#device-panel':section))link.setAttribute('aria-current','location');
    else link.removeAttribute('aria-current');
  }
  $('workspace-title').textContent=t(viewNames[section]||'nav.files');
}
syncNavigation();

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
const entriesIn = async folder => (await call('list', {parent:folder})).entries;
let renameTarget, moveTarget, moveFolder='', moveRevision=0, previewRevision=0, undoTimer, pendingFolder;
const workspace=mountWorkspace({t,lang,bytes,open:openItem,preview:previewItem,rename:renameItem,
  move:moveItem,trash:trashItem,recover:recoverItem,share:showSharing});

function renderEntries(entries, showLocation=false, cloud=false, config={}) {
  return workspace.render(entries.map(item=>({...item,kind:fileKind(item),cloud,
    location:showLocation?item.path.split('/').slice(0,-1).join('/') :undefined})),config);
}

function openItem(item) {
  if(item.directory) {
    if(route()!=='#files'){pendingFolder=item.path;location.hash='#files';}
    else task(async()=>{parent=item.path;await list();$('files').focus();});
  }else previewItem(item);
}

function previewItem(item) {
  if(item.directory) {openItem(item);return;}
  const dialog=$('workspace-preview'),content=$('workspace-preview-text'),revision=++previewRevision;
  $('workspace-preview-title').textContent=item.name;
  $('workspace-preview-meta').textContent=workspace.label(item)+' · '+t('workspace.noPreview');
  content.textContent='';content.hidden=true;
  $('workspace-preview-open').onclick=()=>task(()=>call('open',{path:item.path}));
  if(!dialog.open)dialog.showModal();
  if(/\.(txt|md|csv|log|json)$/i.test(item.name)) {
    void call('previewText',{path:item.path}).then(({text})=>{
      if(revision===previewRevision&&dialog.open){content.textContent=text;content.hidden=false;}
    }).catch(()=>{/* The explicit native viewer remains available. */});
  }
}

function renameItem(item){renameTarget=item;edit('organizeMove',item.name);}

function showSharing() {
  const sharing=$('drive-sharing');
  if(sharing){sharing.open=true;sharing.scrollIntoView({block:'nearest'});sharing.querySelector('summary')?.focus();}
  else message(t('workspace.noShared'));
}

function announceUndo(label,undo) {
  clearTimeout(undoTimer);message(label);
  const button=document.createElement('button');button.className='secondary';button.textContent=t('workspace.undo');
  button.onclick=()=>task(async()=>{await undo();await list();message(t('workspace.recovered'));});
  $('status').append(' ',button);
  function expire(){undoTimer=setTimeout(()=>{if(button.matches(':hover,:focus'))expire();else button.remove();},6000);}
  expire();
}

async function relocated(item,destination,name=item.name) {
  const from=item.path.includes('/')?item.path.slice(0,item.path.lastIndexOf('/')):'';
  const {path}=await call('organizeMove',{path:item.path,destination,name});
  workspace.rekey(item.path,path);
  const undo=async()=>{
    const restored=await call('organizeMove',{path,destination:from,name:item.name});
    workspace.rekey(path,restored.path);
  };
  return undo;
}

function moveItem(item,destination) {
  if(destination!==undefined) {
    return task(async()=>{const undo=await relocated(item,destination);await list();announceUndo(t('workspace.actionsDone'),undo);});
  }
  moveTarget=item;moveFolder=parent;
  $('workspace-move-dialog').showModal();
  void listDestinations().catch(fail);
}
async function listDestinations() {
  const revision=++moveRevision,folder=moveFolder,items=await entriesIn(folder);
  if(revision!==moveRevision||!$('workspace-move-dialog').open)return;
  $('workspace-move-path').textContent=folder||t('storage.root');
  const make=(name,target)=>{
    const li=document.createElement('li'),button=document.createElement('button');
    button.type='button';button.textContent=name;button.onclick=()=>{moveFolder=target;void listDestinations().catch(fail);};li.append(button);return li;
  };
  const rows=folder?[make(t('folder.up'),folder.split('/').slice(0,-1).join('/'))]:[];
  for(const item of items){
    if(item.directory&&item.path!==moveTarget.path&&!item.path.startsWith(moveTarget.path+'/'))rows.push(make(item.name,item.path));
  }
  $('workspace-folders').replaceChildren(...rows);
  $('workspace-move-form').querySelector('button[type=submit]').disabled=folder===moveTarget.path||folder.startsWith(moveTarget.path+'/');
}
$('workspace-move-form').onsubmit=event=>{event.preventDefault();const item=moveTarget,dest=moveFolder;$('workspace-move-dialog').close();moveItem(item,dest);};
$('workspace-move-cancel').onclick=()=>$('workspace-move-dialog').close();

function trashItem(item) {
  return task(async()=>{
    const {id}=await call('organizeTrash',{path:item.path});
    const undo=async()=>{await call('organizeRecover',{id});};
    await list();announceUndo(t('workspace.trashed'),undo);
  });
}
function recoverItem(item) {
  return task(async()=>{await call('organizeRecover',{id:item.path});await list();message(t('workspace.recovered'));});
}

async function list() {
  $('search-heading').hidden = true;
  $('search-cloud-help').hidden = true;
  $('search-form').reset();
  $('workspace-date').disabled=false;
  resetSearchState();
  $('path').closest('nav').hidden=false;
  $('path').textContent = parent || t('storage.root');
  $('back').hidden = !parent;
  $('workspace-table').hidden=route()==='#shared';
  $('workspace-view-controls').hidden=route()==='#shared';
  let entries=[], config={defaultSort:route()==='#home'?'recent':'default'};
  if(route()==='#trash') {
    entries=(await call('organizeTrashList')).entries;
    config.trash=true;
  }else if(route()==='#starred') {
    entries=await workspace.starredEntries(entriesIn);
    config.starred=true;
  }else if(route()!=='#shared')entries=await entriesIn(parent);
  const count=renderEntries(entries,config.starred,false,config);
  message(route()==='#shared'?t('workspace.noShared'):count?'':t('status.empty'));
  if(route()==='#shared'&&encryptedSync.sharingEnabled){
    const button=document.createElement('button');button.textContent=t('workspace.browseShare');button.onclick=showSharing;$('status').append(' ',button);
  }
  const syncState = await encryptedSync.state();
  $('evict').hidden = !encryptedSync.configured || !syncState.canEvict;
  $('restore-legacy').hidden = !syncState.legacyRestore;
  $('storage').textContent = t(syncState.synced ? 'sync.ready' : syncState.evicted ? 'sync.evicted' : encryptedSync.configured ? 'sync.dirty' : 'storage.state');
}

async function showSearch(query, kind, cloud, days) {
  $('path').closest('nav').hidden = true;
  $('workspace-table').hidden = false;
  $('search-heading').textContent = t(query ? 'search.heading' : 'search.results', { query });
  $('search-heading').hidden = false;
  $('search-cloud-help').hidden = true;
  renderEntries([]);
  resetSearchState();
  message(t(cloud ? 'search.cloud.running' : 'search.running'));
  const input = $('search-query');
  input.readOnly = cloud;
  try {
    const catalog = cloud ? await encryptedSync.cloudCatalog() : null;
    const { entries, limited } = cloud
      ? searchCatalog(query, kind, catalog)
      : await searchFiles(query, async folder => (await call('list', { parent: folder })).entries, kind);
    const count = renderEntries(entries, true, cloud, {days});
    $('search-cloud-help').hidden = !cloud;
    const key = limited ? 'search.limited' : count ? 'search.count' : 'search.empty';
    message(t(key, { count: new Intl.NumberFormat(lang).format(count) }));
    $('clear-kind').hidden = count > 0 || kind === 'all';
    if (catalog) {
      const sources = new Set(catalog.map(row => row.sourceDevice).filter(Boolean));
      if (sources.size) $('cloud-scope-label').textContent = t('search.allDevices');
      showSearchNotice(sources.size
        ? t('search.verified', {count: new Intl.NumberFormat(lang).format(sources.size)})
        : t('search.snapshotVerified'),
      t('search.checkedAt', {time: new Intl.DateTimeFormat(lang, {hour:'numeric', minute:'2-digit'}).format(new Date())}));
    }
  } catch (error) {
    if (cloud && error?.message !== 'client operation was not approved') {
      showSearchNotice(t('search.unavailable'), '', true);
      $('search-recovery').hidden = false;
    }
    throw error;
  } finally {
    input.readOnly = false;
  }
}

function resetSearchState() {
  for (const id of ['search-notice', 'search-recovery', 'clear-kind']) $(id).hidden = true;
}

function showSearchNotice(title, detail, unavailable = false) {
  $('search-notice-title').textContent = title;
  $('search-notice-detail').textContent = detail;
  $('search-notice-icon').src = unavailable ? 'icons/wifi.svg' : 'icons/cloud.svg';
  $('search-notice').classList.toggle('unavailable', unavailable);
  $('search-notice').hidden = false;
}

async function task(fn){
  if(busy)return;
  busy=true;$('workspace-main').setAttribute('aria-busy','true');
  try{await fn();}catch(e){fail(e);}finally{busy=false;$('workspace-main').removeAttribute('aria-busy');}
}
$('search-form').onsubmit = event => {
  event.preventDefault();
  clearTimeout(localSearchTimer);
  const query=$('search-query').value.trim(),filters=$('search-form').elements;
  const kind=filters.kind.value,days=Number(filters.date.value);
  const cloud=encryptedSync.configured&&filters.scope.value==='cloud';
  if(days&&!query&&kind==='all'&&!cloud){
    task(async()=>{const count=renderEntries(await entriesIn(parent),false,false,{days});message(t(count?'search.count':'search.empty',{count}));});
  }else task(cloud||query||kind!=='all'?()=>showSearch(query,kind,cloud,days):list);
};
let localSearchTimer;
function queueLocalSearch() {
  clearTimeout(localSearchTimer);
  if($('search-form').elements.scope.value==='cloud')return;
  const startRoute=route();
  const run=()=>{
    if(startRoute!==route()||$('search-form').elements.scope.value==='cloud')return;
    if(busy){localSearchTimer=setTimeout(run,180);return;}
    $('search-form').requestSubmit();
  };
  localSearchTimer=setTimeout(run,180);
}
$('search-query').addEventListener('input',queueLocalSearch);
$('search-form').onchange=event=>{
  const filters=$('search-form').elements;
  if(event.target.name==='scope') {
    filters.date.disabled=filters.scope.value==='cloud';
    if(filters.date.disabled)filters.date.value='0';
    return;
  }
  // Cloud lookup always needs an intentional submit and canonical operation consent.
  if(event.target.name==='date'||event.target.name==='kind')queueLocalSearch();
};
$('search-retry').onclick=()=>$('search-form').requestSubmit();
$('search-local').onclick=()=>{$('search-form').elements.scope.value='local';$('search-form').requestSubmit();};
$('clear-kind').onclick=()=>{$('search-form').elements.kind.value='all';$('search-form').requestSubmit();};
$('search-clear').onclick=()=>task(async()=>{await list();$('search-query').focus();});
$('back').onclick=()=>task(async()=>{parent=parent.split('/').slice(0,-1).join('/');await list();});
$('refresh').onclick=()=>task(list);

const create=$('workspace-create');
create.addEventListener('toggle',event=>{if(event.newState==='open')create.querySelector('button')?.focus();});
$('import').onclick=()=>{create.hidePopover();task(async()=>{message(t('status.importing'));try{await call('pick');}catch(error){if(!String(error).includes('import-cancelled'))throw error;await list();return;}await list();message(t('status.saved'));});};
function edit(op,name='') {
  if(create.matches(':popover-open'))create.hidePopover();
  mode=op;
  $('title').textContent=t(op==='mkdir'?'folder.create':op==='organizeMove'?'workspace.rename':'file.note');
  $('content-label').hidden=op!=='createText';
  $('name').value=name;$('content').value='';$('form-error').textContent='';
  $('edit').showModal();$('name').focus();
  if(name){const dot=!renameTarget?.directory?name.lastIndexOf('.'):-1;$('name').setSelectionRange(0,dot>0?dot:name.length);}
}
$('folder').onclick=()=>edit('mkdir');$('note').onclick=()=>edit('createText');$('cancel').onclick=()=>$('edit').close();
$('entry').onsubmit=event=>{
  event.preventDefault();const name=$('name').value;
  if(!core.validName(name)){$('form-error').textContent=t('error.invalid-name');return;}
  task(async()=>{
    if(mode==='organizeMove'){
      const item=renameTarget,folder=item.path.includes('/')?item.path.slice(0,item.path.lastIndexOf('/')):'';
      if(item.name!==name){const undo=await relocated(item,folder,name);$('edit').close();await list();announceUndo(t('status.saved'),undo);}
      else $('edit').close();
    }else{await call(mode,{name,text:$('content').value});$('edit').close();await list();message(t('status.saved'));}
  });
};
document.addEventListener('keydown',event=>{
  if(event.defaultPrevented||event.metaKey||event.ctrlKey||event.altKey||
    event.target.closest('input,textarea,select,[contenteditable]')||document.querySelector('dialog[open]'))return;
  if(event.key==='/'&&$('drive-search').getClientRects().length){event.preventDefault();$('search-query').focus();}
  if(event.key.toLowerCase()==='n'&&route()!=='#device-panel'&&route()!=='#cloud-restore'){event.preventDefault();edit('mkdir');}
});
function onWorkspaceRoute() {
  syncNavigation();showFlowRoute();workspace.clear();
  if(route()==='#cloud-restore') {
    if(wnfsEnabled&&encryptedSync.configured&&$('cloud-restore').hidden)void openRestore().catch(fail);
    return;
  }
  if(route()==='#device-panel')return;
  parent=pendingFolder??'';pendingFolder=undefined;void task(list);
}

$('workspace-sync-slot').append($('file-sync'));
await task(async()=>{await host.initialize();await list();});

const showGrants = mountWnfsSharing($('sync').parentElement, encryptedSync, task, encryptedSync.sharingEnabled, list);
$('sync').onclick=()=>task(async()=>{message(t('sync.busy'));showGrants(await encryptedSync.sync());await list();$('storage').textContent=t('sync.ready');message(t('sync.success'));});
async function restoreFiles(legacy=false) {
  message(t('sync.restoring'));
  const result=await encryptedSync.restore({legacy});
  parent=result.folder||'';
  await list();
  message(t(result.folder?'sync.restoredCopy':'sync.restored'));
}
const openRestore = mountRestore({
  sync: encryptedSync, t, bytes,
  onOpen: () => { location.hash = '#cloud-restore'; showFlowRoute(); },
  onFiles: (folder = '') => task(async () => {
    parent = folder;
    location.hash = '#files';
    await list();
    $('files').focus();
  }),
});
$('restore').onclick = () => wnfsEnabled ? openRestore().catch(fail) : task(() => restoreFiles());
$('restore-legacy').onclick=()=>task(()=>restoreFiles(true));
$('evict').onclick=()=>{$('evict-confirm').showModal();};
$('evict-cancel').onclick=()=>$('evict-confirm').close();
$('evict-allow').onclick=()=>{$('evict-confirm').close();task(async()=>{await encryptedSync.evict();parent='';await list();$('storage').textContent=t('sync.evicted');});};
if(!encryptedSync.configured){$('sync').hidden=true;$('restore').hidden=true;$('evict').hidden=true;}
else $('search-scope-field').hidden = false;

const platform = /Android/i.test(navigator.userAgent) ? 'android' :
  /Macintosh/i.test(navigator.userAgent) ? 'mac' : '';
document.documentElement.dataset.platform = platform;
function showFlowRoute() {
  document.body.dataset.flow = location.hash === '#device-panel' && !$('drive-account').hidden ? 'account' :
    location.hash === '#cloud-restore' && $('cloud-restore')?.hidden === false ? 'restore' : '';
}
if (accountLinkEnabled && platform) {
  mountAccount({ platform, call, t, sync: encryptedSync, openRestore: () => $('restore').click() });
}
window.addEventListener('hashchange', onWorkspaceRoute);
showFlowRoute();
if (wnfsEnabled && encryptedSync.configured && location.hash === '#cloud-restore') openRestore().catch(fail);
