import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { connect } from 'node:net';
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';

const qaPackage = 'network.awai.drive.passkey3099';
const approvalOrigin = 'https://awai.network';
const args = process.argv.slice(2);
const reset = args[0] === '--reset-isolated-qa';
const serials = reset ? args.slice(1) : args;
assert(serials.length === 2 && new Set(serials).size === 2, 'Specify two distinct ADB serials');
assert(process.env.AWAI_QA_PLAYWRIGHT, 'Set AWAI_QA_PLAYWRIGHT to the absolute path of playwright/index.mjs');
assert(process.env.AWAI_QA_LINK_CORE, 'Set AWAI_QA_LINK_CORE to the built account-link-core.js');
const { chromium } = await import(pathToFileURL(process.env.AWAI_QA_PLAYWRIGHT));
const { confirmationCode, createLinkRequest, linkUrl, parseLinkFragment, DRIVE_PASSKEY_PURPOSE,
  compressP256, p256Did } = await import(pathToFileURL(process.env.AWAI_QA_LINK_CORE));

const forwards = [];
const proxySerial = process.env.AWAI_QA_PROXY_SERIAL;
const proxyPort = 19430;
let proxy;
let reversed = false;
const context = await chromium.launchPersistentContext('', {
  channel: 'chrome',
  headless: true,
  timeout: 15000,
});
let stage = 'setup';
try {
  const portal = context.pages()[0] ?? await context.newPage();
  if (process.env.AWAI_QA_PORTAL_ASSETS) {
    const script = resolve(process.env.AWAI_QA_PORTAL_ASSETS, 'assets/main.js');
    await context.route('https://awai.network/link/assets/main.js', route =>
      route.fulfill({path: script, contentType: 'text/javascript'}));
  }
  portal.setDefaultTimeout(12000);
  traceRelay(portal, 'Portal');
  portal.on('pageerror', error => console.error('Portal rendering error:', error.name));
  if (proxySerial) {
    assert(serials.includes(proxySerial), 'Proxy must target one of the QA devices');
    proxy = await startRelayProxy();
    const existing = adb(proxySerial, 'reverse', '--list');
    assert(!existing.split(/\s+/).includes('tcp:' + proxyPort), 'QA reverse port is occupied');
    adb(proxySerial, 'reverse', 'tcp:' + proxyPort, 'tcp:' + proxy.address().port);
    reversed = true;
  }
  const webauthn = await context.newCDPSession(portal);
  await webauthn.send('WebAuthn.enable');
  const { authenticatorId } = await webauthn.send('WebAuthn.addVirtualAuthenticator', { options: {
    protocol: 'ctap2',
    transport: 'internal',
    hasResidentKey: true,
    hasUserVerification: true,
    isUserVerified: true,
    automaticPresenceSimulation: true,
  }});

  // Start only the fixed, separate QA package. Never connect to regular Drive.
  const devices = [];
  for (const serial of serials) {
    assert(adb(serial, 'shell', 'pm', 'path', qaPackage).startsWith('package:'));
    if (reset) assert.equal(adb(serial, 'shell', 'pm', 'clear', qaPackage), 'Success');
    const page = await startQaApp(serial);
    traceRelay(page, 'Android');
    const status = page.locator('#account-status');
    await status.filter({hasText: /未認可|awaiting authorization/i}).waitFor({ state: 'visible' });
    devices.push({ serial, page, status });
  }

  // Fresh profiles must reject a Drive request without silently registering.
  stage = 'existing-only refusal';
  const first = await newDriveRequest(devices[0].page);
  await approve(first.url, first.code);
  await portal.getByText('Open this link in the browser where you set up your existing Awai Passkey.').waitFor();
  assert.equal(await portal.evaluate(() => localStorage.getItem('awai.passkey.v1')), null);
  await devices[0].page.locator('#account-cancel').click();
  console.log('PASS: public Drive approval rejects profiles with no existing Passkey');

  // Register through the existing generic flow. Deny relay delivery for this
  // bootstrap request because only the browser credential is needed.
  stage = 'QA account registration';
  const key = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign', 'verify']);
  const publicKey = new Uint8Array(await crypto.subtle.exportKey('raw', key.publicKey));
  const { request } = await createLinkRequest(p256Did(compressP256(publicKey)));
  await approve(linkUrl(request), confirmationCode(request));
  await portal.getByRole('button', { name: 'Deny', exact: true }).click();
  await portal.getByText('Nothing was sent.').waitFor();
  assert(await portal.evaluate(() => Boolean(localStorage.getItem('awai.passkey.v1'))));
  const before = await webauthn.send('WebAuthn.getCredentials', { authenticatorId });
  assert.equal(before.credentials.length, 1, 'Expected one QA Passkey');
  console.log('PASS: created one isolated QA Passkey through the public page');

  const accounts = [];
  const assignedDevices = new Set();
  for (const [index, { serial, page, status }] of devices.entries()) {
    stage = 'device ' + (index + 1) + ' request';
    const attempt = await newDriveRequest(page);
    assert(!assignedDevices.has(attempt.device), 'Device identity reused across targets');
    assignedDevices.add(attempt.device);
    await page.locator('#account-receive').click();
    stage = 'device ' + (index + 1) + ' relay GET consent';
    await approveOnce(page);
    stage = 'device ' + (index + 1) + ' browser assertion';
    await approve(attempt.url, attempt.code);
    stage = 'device ' + (index + 1) + ' relay POST consent';
    await approveOnce(portal);
    stage = 'device ' + (index + 1) + ' browser delivery';
    await portal.getByRole('heading', { name: 'Device linked', exact: true }).waitFor();
    await portal.locator('main[data-link-done] ul.devices > li')
      .filter({hasText: attempt.device}).waitFor();
    stage = 'device ' + (index + 1) + ' app persisted proof';
    await status.filter({hasText: /認可済み|Device authorized/i}).waitFor({ state: 'visible' });
    const account = (await status.textContent()).match(/did:key:z[1-9A-HJ-NP-Za-km-z]+/)?.[0];
    assert(account, 'Missing verified account DID');
    accounts.push(account);

    stage = 'device ' + (index + 1) + ' restart';
    const restartedPage = await startQaApp(serial);
    await restartedPage.locator('#account-status').filter({hasText: account}).waitFor({timeout: 12000});
    console.log('PASS: physical device ' + (index + 1) + ' authorized via the public relay and retained it after restart');
  }
  stage = 'same account';
  assert.equal(accounts[0], accounts[1], 'The two approvals must resolve to the same existing account');
  const after = await webauthn.send('WebAuthn.getCredentials', { authenticatorId });
  assert.equal(after.credentials.length, 1);
  assert.equal(before.credentials[0].credentialId, after.credentials[0].credentialId);
  assert(after.credentials[0].signCount >= before.credentials[0].signCount + 2);
  console.log('PASS: both distinct device keys used the same QA Passkey, account fingerprint ' +
    createHash('sha256').update(accounts[0]).digest('hex').slice(0, 12));
} catch (error) {
  // Playwright errors can contain the approval URL. Keep only the stage and type.
  console.error('FAIL: ' + stage + ' (' + error.name + ')');
  process.exitCode = 1;
} finally {
  await context.close();
  for (const {serial, port} of forwards) adb(serial, 'forward', '--remove', 'tcp:' + port);
  if (reversed) adb(proxySerial, 'reverse', '--remove', 'tcp:' + proxyPort);
  proxy?.close();
}
process.exit(process.exitCode ?? 0);

function adb(serial, ...args) {
  return execFileSync('adb', ['-s', serial, ...args], { encoding: 'utf8', timeout: 15000,
    stdio: ['ignore', 'pipe', 'pipe'] }).trim();
}

async function newDriveRequest(page) {
  await page.locator('#account-begin').click();
  const field = page.locator('#account-url');
  await field.waitFor();
  const url = new URL(await field.inputValue());
  assert(url.origin === approvalOrigin && url.pathname === '/link/');
  const request = parseLinkFragment(url.hash);
  assert.equal(request.purpose, DRIVE_PASSKEY_PURPOSE);
  const code = confirmationCode(request);
  assert((await page.locator('#account-code').textContent()).includes(code), 'Drive confirmation code differs');
  return { url: url.href, code, device: request.device };
}

async function approve(url, code) {
  const page = context.pages()[0];
  await page.goto(url);
  await page.reload();
  await page.locator('[data-link-code]').filter({hasText: code}).waitFor();
  await page.getByRole('checkbox', { name: 'My device shows the same code' }).check();
  await page.getByRole('button', { name: 'Approve with Passkey' }).click();
}

async function approveOnce(page) {
  const button = page.getByRole('button', { name: 'Allow once', exact: true });
  await button.waitFor();
  await button.click();
}

function traceRelay(page, source) {
  const isRelay = request => new URL(request.url()).origin === 'https://relay.awai.network';
  page.on('response', response => {
    if (isRelay(response.request())) console.log(source, response.request().method(), response.status());
  });
  page.on('requestfailed', request => {
    if (isRelay(request)) console.log(source, request.method(), request.failure()?.errorText);
  });
}

async function startQaApp(serial) {
  adb(serial, 'shell', 'am', 'force-stop', qaPackage);
  adb(serial, 'shell', 'am', 'start', '-W', '-n', qaPackage + '/.MainActivity',
    ...(serial === proxySerial ? ['--ez', 'qaRelayProxy', 'true'] : []));
  const pid = adb(serial, 'shell', 'pidof', qaPackage);
  assert(/^\d+$/.test(pid), 'Expected one QA WebView process');
  const port = adb(serial, 'forward', 'tcp:0', 'localabstract:webview_devtools_remote_' + pid);
  assert(/^\d+$/.test(port), 'Expected an allocated local port');
  forwards.push({ serial, port });
  const browser = await connectQa(port);
  const page = browser.contexts()[0].pages().find(candidate => candidate.url() === 'http://tauri.localhost/');
  assert(page, 'QA app WebView unavailable');
  page.setDefaultTimeout(12000);
  return page;
}

async function startRelayProxy() {
  const relayHost = 'relay.awai.network';
  const server = createServer((request, response) => {
    response.writeHead(403);
    response.end();
  });
  server.on('connect', (request, downstream, head) => {
    downstream.on('error', () => downstream.destroy());
    if (request.url !== relayHost + ':443') {
      downstream.end('HTTP/1.1 403 Forbidden\r\n\r\n');
      return;
    }
    const upstream = connect(443, relayHost);
    upstream.setTimeout(15000, () => upstream.destroy());
    upstream.on('error', () => downstream.destroy());
    downstream.on('close', () => upstream.destroy());
    upstream.once('connect', () => {
      upstream.setTimeout(0);
      downstream.write('HTTP/1.1 200 Connection Established\r\n\r\n');
      if (head.length) upstream.write(head);
      downstream.pipe(upstream).pipe(downstream);
    });
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  return server;
}

async function connectQa(port) {
  const url = 'http://127.0.0.1:' + port;
  let error;
  for (let attempt = 0; attempt < 8; attempt++) {
    try {
      const response = await fetch(url + '/json/version');
      if (response.ok) return await chromium.connectOverCDP(url, {timeout: 3000});
    } catch (cause) {
      error = cause;
    }
    await new Promise(resolve => setTimeout(resolve, 300));
  }
  throw error ?? Error('QA WebView DevTools unavailable');
}
