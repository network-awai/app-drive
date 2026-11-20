import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

// Only the separately packaged Awai Home QA application is eligible.
const [serial, dist, playwright, qr] = process.argv.slice(2);
assert(process.argv.length === 6 && /^[a-zA-Z0-9._:-]+$/.test(serial ?? ''),
  'Usage: qa-enroll-android-home.mjs ADB_SERIAL DRIVE_DIST PLAYWRIGHT_INDEX QR_DECODER');
const qa = 'network.awai.home.passkeyqa';
const adb = (...args) => execFileSync('adb', ['-s', serial, ...args], {encoding:'utf8', timeout:15000, stdio:['ignore','pipe','pipe']}).trim();
const { chromium } = await import(pathToFileURL(resolve(playwright)));
const { compressP256, confirmationCode, createLinkRequest, linkUrl, parseLinkFragment, p256Did } =
  await import(pathToFileURL(resolve(dist, 'account-link-core.js')));
assert(adb('shell','pm','path',qa).startsWith('package:'));
const pid = adb('shell','pidof',qa);
assert(/^\d+$/.test(pid));
const port = adb('forward','tcp:0','localabstract:webview_devtools_remote_'+pid);
assert(/^\d+$/.test(port));
let stage = 'device connect';
let browser;
let profile;
try {
  browser = await chromium.connectOverCDP('http://127.0.0.1:'+port,{timeout:10000});
  const home = browser.contexts()[0].pages().find(page=>page.url()==='https://appassets.androidplatform.net/awai/index.html');
  assert(home);
  home.setDefaultTimeout(12000);
  stage='isolated setup';
  const page = home.locator('.setup-page');
  await page.waitFor();
  const getStage = () => page.getAttribute('data-stage');
  if (await getStage() === 'welcome') await home.getByRole('button',{name:/^(はじめる|Get started)$/}).click();
  for (let steps=0; steps<3; steps++) {
    if (await home.getByRole('button',{name:/スマートフォンから|From your phone/}).isVisible()) break;
    stage = 'setup '+await getStage();
    await home.getByRole('button',{name:/^(次へ|Next)$/}).click();
  }
  await home.getByRole('button',{name:/スマートフォンから|From your phone/}).click();
  stage='request relay approval';
  await home.getByRole('button',{name:/^(連携を開始|Start linking)$/}).click();
  const dialog=home.locator('dialog[open]');
  const allow=dialog.getByRole('button',{name:'Allow once',exact:true}).and(dialog.locator('button:enabled'));
  await allow.waitFor();
  const scope=await dialog.locator('pre').textContent();
  const topic=scope.match(/^Action: GET \/([a-f0-9]{32})\/json$/m)?.[1];
  assert(topic && scope.includes('Endpoint: https://relay.awai.network\n') && scope.includes('Operation: await-phone-approval\n'));
  await allow.scrollIntoViewIfNeeded();
  const address = await allow.boundingBox();
  assert(address);
  const input = await home.context().newCDPSession(home);
  assert.equal(await dialog.locator('pre').textContent(),scope);
  const touchPoints = [{x: address.x + address.width / 2, y: address.y + address.height / 2}];
  await input.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints});
  await input.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
  stage='relay consent dismissed';
  await dialog.waitFor({state:'hidden',timeout:5000});
  stage='read device QR after relay connection';
  const codeImage=home.getByRole('img',{name:/スマートフォン連携用 QR コード|QR code for phone approval/});
  await codeImage.waitFor({timeout:50000});
  const pixels=await codeImage.screenshot();
  const url=new URL(execFileSync(resolve(qr),{input:pixels,encoding:'utf8',timeout:10000}).trim());
  assert(url.origin==='https://awai.network' && url.pathname==='/link/' && !url.search);
  const request=parseLinkFragment(url.hash);
  assert.equal(request.topic,topic);
  const code=confirmationCode(request);
  assert(await home.getByText(code,{exact:false}).isVisible());

  stage='create one isolated virtual Passkey';
  profile=await chromium.launchPersistentContext('',{channel:'chrome',headless:true});
  const portal=profile.pages()[0];
  portal.setDefaultTimeout(15000);
  const cdp=await profile.newCDPSession(portal);
  await cdp.send('WebAuthn.enable');
  const {authenticatorId}=await cdp.send('WebAuthn.addVirtualAuthenticator',{options:{protocol:'ctap2',transport:'internal',
    hasResidentKey:true,hasUserVerification:true,isUserVerified:true,automaticPresenceSimulation:true}});
  const key=await crypto.subtle.generateKey({name:'ECDSA',namedCurve:'P-256'},false,['sign','verify']);
  const raw=new Uint8Array(await crypto.subtle.exportKey('raw',key.publicKey));
  const bootstrap=(await createLinkRequest(p256Did(compressP256(raw)))).request;
  await approve(portal,linkUrl(bootstrap),confirmationCode(bootstrap));
  await portal.getByRole('button',{name:'Deny',exact:true}).click();
  await portal.getByText('Nothing was sent.').waitFor();
  const before=(await cdp.send('WebAuthn.getCredentials',{authenticatorId})).credentials;
  assert.equal(before.length,1);
  stage='deliver Passkey proof to isolated Home';
  await approve(portal,url.href,code);
  await portal.getByRole('button',{name:'Allow once',exact:true}).click();
  await portal.getByRole('heading',{name:'Device linked',exact:true}).waitFor();
  const next=home.getByRole('button',{name:/^(このアカウントで続ける|Continue with this account)$/});
  await next.waitFor();
  const after=(await cdp.send('WebAuthn.getCredentials',{authenticatorId})).credentials;
  assert.equal(after.length,1);
  assert.equal(after[0].credentialId,before[0].credentialId);
  assert(after[0].signCount>before[0].signCount);
  console.log('Physical Awai Home QA verified a public Passkey proof.');
  stage='persist isolated Home account selection';
  await next.click();
  await home.getByText(/署名を確認済み · データ同期は別に設定|Signature verified · configure sync separately/).waitFor();
  console.log('Physical Awai Home QA selected the verified account.');
} catch (error) {
  // Do not emit Playwright pages, link URLs, topics or keys.
  console.error('Failed at '+stage+' ('+error.name+')');
  process.exitCode=1;
} finally {
  await profile?.close();
  await browser?.close();
  adb('forward','--remove','tcp:'+port);
}

async function approve(portal, href, code) {
  await portal.goto(href);
  await portal.reload();
  await portal.locator('[data-link-code]').filter({hasText:code}).waitFor();
  await portal.getByRole('checkbox',{name:'My device shows the same code'}).check();
  await portal.getByRole('button',{name:'Approve with Passkey'}).click();
}
