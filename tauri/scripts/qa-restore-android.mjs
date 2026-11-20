// Drives only the disposable Passkey QA package through its visible UI.
// Does not install, clear, or write to the regular Drive package.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFile, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

const [serial, action, directory] = process.argv.slice(2);
assert(/^[a-zA-Z0-9._:-]+$/.test(serial ?? '') &&
  ((action === 'probe' && process.argv.length === 4) ||
   (['recipient','restore'].includes(action) && process.argv.length === 5)),
  'Usage: qa-restore-android.mjs ADB_SERIAL probe|recipient OUTPUT_FILE|restore FIXTURE_DIRECTORY');
const packageName = 'network.awai.drive.passkey3099';
const source = process.env.AWAI_QA_PLAYWRIGHT;
assert(source?.startsWith('/'),'Set AWAI_QA_PLAYWRIGHT to the absolute playwright/index.mjs path');
const {chromium} = await import(pathToFileURL(source).href);
const adb = (...args) => execFileSync('adb',['-s',serial,...args],{
  encoding:'utf8',timeout:15000,stdio:['ignore','pipe','pipe'],
}).trim();
assert(adb('shell','pm','path',packageName).startsWith('package:'),'Isolated Drive QA app is missing');
assert.match(adb('shell','run-as',packageName,'id'),/^uid=/);
adb('shell','am','start','-W','-n',packageName+'/.MainActivity');
const pid=adb('shell','pidof',packageName);
assert.match(pid,/^[0-9]+$/);
const port=adb('forward','tcp:0','localabstract:webview_devtools_remote_'+pid);
assert.match(port,/^[0-9]+$/);
let browser;
let stage='connect';
try {
  browser=await chromium.connectOverCDP('http://127.0.0.1:'+port,{timeout:10000});
  const page=browser.contexts()[0].pages().find(page=>page.url()==='http://tauri.localhost/');
  assert(page,'Drive QA WebView is unavailable');
  page.setDefaultTimeout(10000);
  stage='account status';
  const status=page.locator('#account-status');
  const authorized=await status.isVisible() && /認可済み|Device authorized/i.test(await status.textContent());
  if(action==='probe'){
    console.log(JSON.stringify({authorized,restoreUI:await page.locator('#restore-form').count()===1}));
  }else{
    assert(authorized,'Link the dedicated QA identity first');
    if(action==='recipient'){
      stage='show public recipient';
      await page.getByText(/^(Snapshot sharing|スナップショットの共有)$/).click();
      await page.getByRole('button',{name:/^(Show my public key|受信者情報を表示)$/}).click();
      const field=page.getByRole('textbox',{name:/^(Sharing output|共有の出力)$/});
      await page.waitForFunction(()=>[...document.querySelectorAll('textarea[readonly]')].some(node=>node.value.startsWith('{')));
      const recipient=JSON.parse(await field.inputValue());
      assert.match(recipient.id,/^kv1:[A-Za-z0-9_-]{43}$/);
      await writeFile(directory,JSON.stringify(recipient)+'\n',{mode:0o600,flag:'wx'});
      console.log('Saved the QA device’s public recipient.');
    }else{
      stage='check fixture';
      const read=async name=>JSON.parse(await readFile(directory+'/'+name,'utf8'));
      const [plan,grant,published]=await Promise.all([read('manifest.json'),read('android.grant.json'),read('published.json')]);
      assert.equal(plan.basis,grant.basis);
      assert.equal(grant.head.origin,'https://yataverse.com');
      assert.equal(plan.expected.length,2);
      const ids=new Set([grant.head.capsule,...grant.head.packs]);
      assert([...ids].every(id=>/^[a-f0-9]{64}$/.test(id) && published.some(row=>row.id===id)));
      assert(plan.expected.every(row=>/^drive-qa-[a-f0-9-]{36}\/(one|two)\.bin$/.test(row.path)));
      const nativeFiles=()=>adb('shell','run-as',packageName,'find','files/drive-local-v1','-type','f').split('\n');
      const find=(paths,suffix)=>paths.filter(path=>path.endsWith('/'+suffix));
      const before=nativeFiles();
      assert(plan.expected.every(row=>find(before,row.path).length===0),'Restore target was already present');
      const cdp=await page.context().newCDPSession(page);
      await cdp.send('Network.enable');
      await cdp.send('Network.setCacheDisabled',{cacheDisabled:true});
      const seen=new Set();
      cdp.on('Network.responseReceived',({response})=>{
        const url=new URL(response.url);
        if(url.origin!=='https://yataverse.com'||!url.pathname.startsWith('/awai-drive/v1/packs/'))return;
        const id=url.pathname.split('/').at(-1);
        if(ids.has(id)&&response.status===200&&!response.fromDiskCache&&!response.fromServiceWorker) seen.add(id);
      });
      stage='select synthetic grant';
      await page.locator('#restore').click();
      await page.locator('#restore-import summary').click();
      await page.locator('#restore-grant').fill(JSON.stringify(grant));
      await page.locator('#restore-form button[type=submit]').click();
      await page.locator('#restore-point-id').filter({hasText:plan.basis}).waitFor();
      stage='approve QA pack reads and restore';
      const started=performance.now();
      await page.locator('#restore-primary').click();
      const done=page.locator('#restore-primary').filter({hasText:/復元したフォルダを開く|Open the restored folder/});
      const dialog=page.locator('dialog[open]');
      const allow=dialog.getByRole('button',{name:'Allow once',exact:true});
      const approvals=new Set();
      for(let i=0;i<=ids.size;i++){
        await done.or(allow).first().waitFor({timeout:30000});
        if(await done.isVisible())break;
        assert(i<ids.size,'Observed more consent requests than declared fixture packs');
        const prompt=await dialog.textContent();
        const approved=[...ids].find(id=>prompt.includes('https://yataverse.com/awai-drive/v1/packs/'+id));
        assert(approved&&!approvals.has(approved),'Unexpected storage consent request');
        approvals.add(approved);
        await allow.click();
      }
      assert(await done.isVisible());
      assert.equal(approvals.size,ids.size,'Missing pack consent');
      assert.equal(seen.size,ids.size,'Missing successful HTTPS fetch for a fixture pack');
      stage='read back restored bytes through QA app sandbox';
      const after=nativeFiles();
      for(const entry of plan.expected){
        const [path,...duplicates]=find(after,entry.path);
        assert(path && !duplicates.length && /^files\/drive-local-v1\/[\p{L}\p{N}._/-]+$/u.test(path));
        assert.equal(Number(adb('shell','run-as',packageName,'stat','-c','%s',path)),entry.size);
        assert.equal(adb('shell','run-as',packageName,'sha256sum',path).split(/\s+/)[0],entry.sha256);
      }
      console.log(JSON.stringify({basis:plan.basis,verifiedFiles:plan.expected.length,httpsPacks:seen.size,
        milliseconds:Math.round(performance.now()-started)}));
    }
  }
} catch(error) {
  console.error('Drive Android QA failed at '+stage+' ('+error.name+')');
  process.exitCode=1;
} finally {
  await browser?.close();
  adb('forward','--remove','tcp:'+port);
}
