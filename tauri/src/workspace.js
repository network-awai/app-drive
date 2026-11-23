// File-list presentation only. All reads, writes, and external consent stay with the host.
export function mountWorkspace({t, lang, bytes, open, preview, rename, move, trash, recover, share}) {
  const $ = id => document.getElementById(id);
  const root = $('files');
  const menu = $('workspace-menu');
  const selection = new Set();
  const collator = new Intl.Collator(lang, {numeric:true,sensitivity:'base'});
  const dateFormat = new Intl.DateTimeFormat(lang,{month:'short',day:'numeric'});
  let entries = [], view = [], options = {}, anchor, menuItem, menuTrigger, active, dragged;
  const stars = new Set(readStars());
  const kindNames = {image:'search.image',video:'search.video',audio:'search.audio',document:'search.document',folder:'workspace.directory'};
  function readStars() {
    try {
      const saved = JSON.parse(localStorage.getItem('awai.drive.stars.v1'));
      return Array.isArray(saved) ? saved.filter(s=>typeof s==='string').slice(0, 4096) : [];
    } catch { return []; }
  }
  function persist() {
    try { localStorage.setItem('awai.drive.stars.v1',JSON.stringify([...stars])); } catch { /* Star for this session. */ }
  }
  function rekey(from,to) {
    for (const key of [...stars]) {
      if (key!==from && !key.startsWith(from+'/')) continue;
      stars.delete(key);
      if (to) stars.add(to+key.slice(from.length));
    }
    persist();
  }
  const kind = item => {
    const label = kindNames[item.kind];
    const ext = item.name.match(/\.([A-Za-z0-9]{1,8})$/)?.[1];
    return item.directory ? t('workspace.directory') : ext ? ext.toUpperCase() : label ? t(label) : t('workspace.file');
  };
  const residency = item => t(item.cloud ? 'workspace.cloud' : 'workspace.local');
  const label = item => [kind(item), item.directory ? null : bytes(item.bytes ?? 0), residency(item)].filter(Boolean).join(' · ');
  const rowKey=item=>item.cloud?JSON.stringify([item.sourceDevice??'',item.path]):item.path;
  const selected = () => view.filter(item=>selection.has(rowKey(item)));
  const itemFor = path => view.find(item=>rowKey(item)===path);
  const fileButton = path => [...root.querySelectorAll('.workspace-file')].find(node=>node.dataset.path===path);
  function update() {
    const values = selected();
    const single = values.length===1 ? values[0] : undefined;
    $('workspace-selection').hidden = values.length===0;
    $('workspace-count').textContent = t('workspace.selected',{count:values.length});
    $('workspace-open').hidden = !single || single.cloud || options.trash;
    $('workspace-share').hidden = options.trash || !values.length;
    $('workspace-move').hidden = options.trash || !single || single.cloud;
    $('workspace-file-details').hidden = !single;
    if(single) {
      $('workspace-detail-name').textContent = single.name;
      $('workspace-detail-meta').textContent = label(single);
    }
    for (const row of root.children) {
      const checked=selection.has(row.dataset.path);
      row.toggleAttribute('data-selected',checked);
      row.firstElementChild.setAttribute('aria-pressed',String(checked));
      row.firstElementChild.tabIndex = row.dataset.path===active ? 0 : -1;
    }
  }
  function select(item,event={}) {
    if(event.shiftKey && anchor && itemFor(anchor)) {
      const [start,end]=[view.indexOf(itemFor(anchor)),view.indexOf(item)].sort((a,b)=>a-b);
      if(!event.metaKey&&!event.ctrlKey)selection.clear();
      for(const entry of view.slice(start,end+1))selection.add(rowKey(entry));
    } else if(event.metaKey||event.ctrlKey) {
      if(selection.has(rowKey(item)))selection.delete(rowKey(item));else selection.add(rowKey(item));
      anchor=rowKey(item);
    } else {
      selection.clear();selection.add(rowKey(item));anchor=rowKey(item);
    }
    active=rowKey(item);
    update();
  }
  function clear() {selection.clear();anchor=undefined;update();}
  function buttonIcon(icon) {
    const n=document.createElement('img');n.alt='';n.src='icons/drive-'+icon+'.svg';n.width=20;n.height=20;return n;
  }
  function span(name,value) {const n=document.createElement('span');n.className=name;n.textContent=value;return n;}
  function showMenu(item,button) {
    menuItem=item;menuTrigger=button;
    $('workspace-menu-open').hidden=item.cloud||options.trash;
    $('workspace-menu-star').hidden=options.trash||item.cloud;
    $('workspace-menu-star').textContent=t(stars.has(item.path)?'workspace.unstar':'workspace.star');
    for(const k of ['rename','move','trash'])$('workspace-menu-'+k).hidden=item.cloud||options.trash;
    $('workspace-menu-recover').hidden=!options.trash;
    const box=button.getBoundingClientRect();
    menu.style.left=Math.max(12,Math.min(box.right-190,window.innerWidth-212))+'px';
    menu.style.top=Math.max(12,Math.min(box.bottom,window.innerHeight-300))+'px';
    menu.showPopover();
    menu.querySelector('button:not([hidden])')?.focus();
  }
  menu.addEventListener('toggle',e=>{if(e.newState==='closed'&&menu.contains(document.activeElement))menuTrigger?.focus();});
  function render(list=entries, config=options) {
    entries=list;options=config;
    const choice=$('workspace-sort').value;
    const sort=choice==='default'?(config.defaultSort||'default'):choice;
    const cutoff=config.days ? Date.now()-config.days*86400000 : 0;
    view=entries.filter(item=>(!config.starred||stars.has(item.path))&&(!cutoff||Number(item.modified)>=cutoff));
    if(sort!=='default')view.sort((a,b)=>Number(b.directory)-Number(a.directory)||
      (sort==='recent'?(b.modified||0)-(a.modified||0):sort==='size'?(b.bytes||0)-(a.bytes||0):0)||collator.compare(a.name,b.name));
    for(const path of selection)if(!itemFor(path))selection.delete(path);
    if(!itemFor(active))active=view[0]?rowKey(view[0]):undefined;
    const rows=document.createDocumentFragment();
    for(const item of view) {
      const row=document.createElement('li');row.className='workspace-item';row.dataset.path=rowKey(item);
      const name=document.createElement('button');name.type='button';name.className='workspace-file';name.dataset.path=rowKey(item);
      name.append(buttonIcon(item.directory?'folder':'file'),span('',item.name));
      name.setAttribute('aria-label',[item.name,label(item),item.location,item.sourceDevice].filter(Boolean).join(' · '));
      name.onclick=e=> {
        if(suppressTap){suppressTap=false;return;}
        const touchSelection=matchMedia('(pointer: coarse)').matches&&selection.size>0;
        select(item,touchSelection?{ctrlKey:true}:e);
        if(!touchSelection&&matchMedia('(pointer: coarse)').matches&&!e.metaKey&&!e.ctrlKey&&!e.shiftKey&&!item.cloud&&!options.trash) {
          open(item);
        }
      };
      name.ondblclick=()=>{if(!options.trash&&!item.cloud)open(item);};
      row.append(name,span('workspace-meta kind',kind(item)),
        span('workspace-meta modified',item.modified?dateFormat.format(item.modified):'—'),
        span('workspace-meta residency',residency(item)));
      const more=document.createElement('button');more.type='button';more.className='workspace-more';
      more.append(buttonIcon('more'));more.setAttribute('aria-label',t('workspace.more',{name:item.name}));
      more.setAttribute('aria-haspopup','true');more.onclick=()=>showMenu(item,more);
      row.append(more);
      row.oncontextmenu=e=>{e.preventDefault();showMenu(item,more);};
      if(!item.cloud&&!options.trash){
        name.draggable=true;
        name.ondragstart=e=>{dragged=item;e.dataTransfer.effectAllowed='move';e.dataTransfer.setData('text/plain',item.name);};
        name.ondragend=()=>{dragged=undefined;for(const target of root.children)target.removeAttribute('data-drop');};
        if(item.directory) {
          const valid=()=>dragged&&dragged.path!==item.path&&!item.path.startsWith(dragged.path+'/');
          row.ondragover=e=>{if(valid()){e.preventDefault();e.dataTransfer.dropEffect='move';row.setAttribute('data-drop','');}};
          row.ondragleave=e=>{if(!row.contains(e.relatedTarget))row.removeAttribute('data-drop');};
          row.ondrop=e=>{e.preventDefault();row.removeAttribute('data-drop');if(valid())move(dragged,item.path);};
        }
      }
      rows.append(row);
    }
    root.replaceChildren(rows);
    update();
    return view.length;
  }
  let touchTimer, touchStart, suppressTap=false;
  function cancelTouch(){clearTimeout(touchTimer);touchStart=undefined;}
  root.addEventListener('pointerdown',e=>{
    if(e.pointerType!=='touch')return;
    const item=itemFor(e.target.closest('.workspace-file')?.dataset.path);
    if(!item)return;
    suppressTap=false;touchStart=[e.clientX,e.clientY];
    touchTimer=setTimeout(()=>{suppressTap=true;select(item,{ctrlKey:true});},450);
  });
  root.addEventListener('pointermove',e=>{if(touchStart&&Math.hypot(touchStart[0]-e.clientX,touchStart[1]-e.clientY)>10)cancelTouch();});
  for(const type of ['pointerup','pointercancel'])root.addEventListener(type,cancelTouch);
  root.addEventListener('keydown',e=>{
    const path=e.target.closest('.workspace-file')?.dataset.path;
    const item=itemFor(path);
    if(!item)return;
    if(['ArrowUp','ArrowDown','ArrowLeft','ArrowRight','Home','End'].includes(e.key)) {
      e.preventDefault();
      const i=view.indexOf(item), forward=e.key==='ArrowDown'||e.key==='ArrowRight';
      const n=e.key==='Home'?0:e.key==='End'?view.length-1:Math.min(view.length-1,Math.max(0,i+(forward?1:-1)));
      active=view[n]?rowKey(view[n]):undefined;
      if(e.shiftKey&&view[n]) {anchor??=rowKey(item);select(view[n],e);}else update();
      fileButton(active)?.focus();
    }else if(e.key==='Enter'&&!item.cloud&&!options.trash) {e.preventDefault();open(item);}
    else if(e.key===' '&&!item.cloud&&!options.trash) {e.preventDefault();if(e.metaKey||e.ctrlKey)select(item,e);else preview(item);}
    else if(e.key==='Escape'&&selection.size){e.preventDefault();clear();}
    else if((e.key==='a'||e.key==='A')&&(e.metaKey||e.ctrlKey)){e.preventDefault();for(const el of view)selection.add(rowKey(el));update();}
    else if(e.key==='F2'&&!options.trash&&!item.cloud){e.preventDefault();rename(item);}
    else if(e.key==='Delete'&&!options.trash&&!item.cloud){e.preventDefault();trash(item);}
  });
  function action(id,fn){$(id).onclick=()=>{const item=menuItem;menu.hidePopover();menuTrigger?.focus();if(item)fn(item);};}
  action('workspace-menu-open',open);
  action('workspace-menu-star',item=>{if(stars.has(item.path))stars.delete(item.path);else stars.add(item.path);persist();render();fileButton(item.path)?.focus();});
  action('workspace-menu-rename',rename);action('workspace-menu-move',item=>move(item));action('workspace-menu-trash',trash);action('workspace-menu-recover',recover);
  $('workspace-open').onclick=()=>{const [item]=selected();if(item)open(item);};
  $('workspace-move').onclick=()=>{const [item]=selected();if(item)move(item);};
  $('workspace-share').onclick=share;
  $('workspace-clear').onclick=()=>{clear();fileButton(active)?.focus();};
  $('workspace-sort').onchange=()=>render();
  $('workspace-layout').onclick=()=>{
    const table=$('workspace-table'),grid=table.dataset.layout!=='grid';
    table.dataset.layout=grid?'grid':'list';
    $('workspace-layout').setAttribute('aria-pressed',String(grid));
    $('workspace-layout').textContent=t(grid?'workspace.list':'workspace.grid');
  };
  async function starredEntries(listFolder) {
    const folders=new Map();
    for(const path of stars) {
      const parent=path.split('/').slice(0,-1).join('/');
      if(!folders.has(parent))folders.set(parent,listFolder(parent).catch(error=>{
        if(String(error).includes('invalid-path'))return [];
        throw error;
      }));
    }
    return (await Promise.all(folders.values())).flat().filter(item=>stars.has(item.path));
  }
  return {render,clear,rekey,label,starredEntries};
}
