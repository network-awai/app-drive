import assert from 'node:assert/strict';
import test from 'node:test';
import { accountHeadGuard, accountHeadsType, discoverAccountHeads } from '../dist/account-heads.js';
import { toBase64Url } from '../dist/account-link-core.js';

const encoder = new TextEncoder();
const encode = value => toBase64Url(encoder.encode(JSON.stringify(value)));
const header = encode({ alg: 'EdDSA', typ: accountHeadsType });
const issuer = await crypto.subtle.generateKey('Ed25519', false, ['sign', 'verify']);
const differentIssuer = await crypto.subtle.generateKey('Ed25519', false, ['sign', 'verify']);
const subject = 'did:key:z' + 'A'.repeat(44);
const device = 'did:key:z' + 'B'.repeat(44);
const recipient = 'kv1:' + 'C'.repeat(43);

async function sign(payload, key = issuer.privateKey) {
  const signingInput = header + '.' + encode(payload);
  const signature = await crypto.subtle.sign('Ed25519', key, encoder.encode(signingInput));
  return signingInput + '.' + toBase64Url(new Uint8Array(signature));
}

test('account discovery authenticates a fresh challenge and a pinned issuer', async () => {
  const clock = 100_000;
  const args = { issuerKey: issuer.publicKey, subject, device, recipient, now: () => clock };
  const peer = 'did:key:z' + 'E'.repeat(44);
  const sources = [{ deviceId: 'peer-device', deviceDid: peer }];
  let oldReply;
  const payload = scope => ({...scope, expires: clock + 10_000, policyEpoch: 1, sources});
  const valid = await discoverAccountHeads({...args, requestHeads: async scope => {
    oldReply = await sign(payload(scope));
    return oldReply;
  }});
  assert.deepEqual(valid, {subject, sources, expires: clock + 10_000,
    policyScope: {subject, device, recipient, epoch: 1, sources: [peer]}});
  let active = true;
  const guard = accountHeadGuard(valid, async scope => {
    assert.equal(scope, valid.policyScope);
    return active;
  }, () => clock);
  await guard();
  active = false;
  await assert.rejects(guard(), /account-head-policy-unavailable/);
  await assert.rejects(accountHeadGuard(valid, () => true, () => clock + 10_000)(), /invalid-account-heads/);
  assert.throws(() => accountHeadGuard(valid), /account-head-policy-unavailable/);
  await assert.rejects(discoverAccountHeads({...args, requestHeads: () => oldReply}), /invalid-account-heads/);

  for (const patch of [
    {subject: 'did:key:z' + 'D'.repeat(44)},
    {device: subject},
    {recipient: 'kv1:' + 'D'.repeat(43)},
    {expires: clock},
    {expires: clock + 60_001},
    {policyEpoch: -1},
    {sources: [{deviceId: 'peer-device'}]},
    {sources: [sources[0], sources[0]]},
  ]) {
    await assert.rejects(discoverAccountHeads({...args, requestHeads: scope => sign({...payload(scope), ...patch})}),
      /invalid-account-heads/);
  }
  await assert.rejects(discoverAccountHeads({...args, requestHeads: scope =>
    sign(payload(scope), differentIssuer.privateKey)}), /invalid-account-heads/);
  await assert.rejects(discoverAccountHeads({...args, requestHeads: () => 'a'.repeat(256 * 1024 + 1)}),
    /invalid-account-heads/);
});
