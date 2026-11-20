import assert from 'node:assert/strict';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const [dist, playwright] = process.argv.slice(2);
assert(dist && playwright, 'Usage: node qa-live-passkey-portal.mjs DIST PLAYWRIGHT_INDEX');
const { chromium } = await import(pathToFileURL(resolve(playwright)));
const { createDriveAccountLink } = await import(pathToFileURL(resolve(dist, 'account-link.js')));
const { compressP256, confirmationCode, createLinkRequest, linkUrl, parseLinkFragment, p256Did } =
  await import(pathToFileURL(resolve(dist, 'account-link-core.js')));
const portalOrigin = 'https://awai.network';
const relayOrigin = 'https://relay.awai.network';
const context = await chromium.launchPersistentContext('', { channel: 'chrome', headless: true });
let stage = 'initialize';
try {
  const page = context.pages()[0];
  page.setDefaultTimeout(15000);
  const cdp = await context.newCDPSession(page);
  await cdp.send('WebAuthn.enable');
  const { authenticatorId } = await cdp.send('WebAuthn.addVirtualAuthenticator', { options: {
    protocol: 'ctap2', transport: 'internal', hasResidentKey: true, hasUserVerification: true,
    automaticPresenceSimulation: true, isUserVerified: true,
  }});
  const credentials = () => cdp.send('WebAuthn.getCredentials', { authenticatorId });
  let posts = 0;
  page.on('request', request => {
    if (new URL(request.url()).origin === relayOrigin && request.method() === 'POST') posts++;
  });
  const first = await makeDevice();
  const second = await makeDevice();
  assert.notEqual(first.did, second.did);

  stage = 'existing-passkey requirement';
  const missing = await first.link.begin();
  await openApproval(page, missing.url, missing.code);
  await page.getByText('Open this link in the browser where you set up your existing Awai Passkey.').waitFor();
  assert.equal((await credentials()).credentials.length, 0);
  assert.equal(posts, 0);
  console.log('PASS: Drive approval without an existing Passkey made no relay POST');

  stage = 'isolated credential registration';
  const bootstrap = (await createLinkRequest(first.did)).request;
  await openApproval(page, linkUrl(bootstrap), confirmationCode(bootstrap));
  await page.getByRole('button', { name: 'Deny', exact: true }).click();
  await page.getByText('Nothing was sent.').waitFor();
  const before = (await credentials()).credentials;
  assert.equal(before.length, 1);
  assert.equal(posts, 0);
  console.log('PASS: generated one virtual Passkey with bootstrap delivery denied');

  const accounts = [];
  for (const [index, device] of [first, second].entries()) {
    stage = `device ${index + 1} relay readiness`;
    const request = await device.link.begin();
    const fragment = parseLinkFragment(new URL(request.url).hash);
    assert.equal(fragment.device, device.did);
    const { promise: ready, resolve: signalReady } = Promise.withResolvers();
    const result = request.finish(AbortSignal.timeout(25000), signalReady);
    result.catch(() => {});
    await Promise.race([ready, result]);
    stage = `device ${index + 1} public approval`;
    await openApproval(page, request.url, request.code);
    const allow = page.getByRole('button', { name: 'Allow once', exact: true });
    await allow.waitFor();
    assert.equal(posts, index);
    await allow.click();
    await page.getByRole('heading', { name: 'Device linked', exact: true }).waitFor();
    stage = `device ${index + 1} local proof verification`;
    const verified = await result;
    const reopened = await device.link.linkedAccount();
    assert.equal(reopened.accountDid, verified.accountDid);
    accounts.push(verified.accountDid);
    assert.equal(posts, index + 1);
    console.log(`PASS: independent synthetic device ${index + 1} verified public relay proof`);
  }
  stage = 'same account and credential';
  assert.equal(accounts[0], accounts[1]);
  const after = (await credentials()).credentials;
  assert.equal(after.length, 1);
  assert.equal(after[0].credentialId, before[0].credentialId);
  assert(after[0].signCount >= before[0].signCount + 2);
  console.log('PASS: both devices verified the same account and used the same virtual Passkey');
} catch (error) {
  // Browser errors can contain approval URLs and relay topics.
  console.error(`FAIL: ${stage} (${error.name})`);
  process.exitCode = 1;
} finally {
  await context.close();
}

async function openApproval(page, target, code) {
  const url = new URL(target);
  assert.equal(url.origin, portalOrigin);
  assert.equal(url.pathname, '/link/');
  await page.goto(url.href);
  await page.reload();
  await page.locator('[data-link-code]').filter({hasText: code}).waitFor();
  await page.getByRole('checkbox', { name: 'My device shows the same code' }).check();
  await page.getByRole('button', { name: 'Approve with Passkey' }).click();
}

async function makeDevice() {
  const pair = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign', 'verify']);
  const raw = new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey));
  const publicKey = Buffer.from(raw).toString('base64url');
  const did = p256Did(compressP256(raw));
  let saved = {};
  const link = createDriveAccountLink({
    async call(operation, args) {
      switch (operation) {
        case 'accountPrepare':
        case 'accountPublic': return {publicKey};
        case 'accountRead': return structuredClone(saved);
        case 'accountSave': saved = structuredClone(args.link); return {};
        default: throw Error('unexpected device operation');
      }
    },
    async approve(_operation, target, signal) {
      const url = new URL(target);
      assert.equal(url.origin, relayOrigin);
      assert.match(url.pathname, /^\/[a-f0-9]{32}\/json$/);
      assert.equal(url.search, '');
      return fetch(url, {signal, redirect: 'error'});
    },
  });
  return { did, link };
}
