import assert from 'node:assert/strict';
import test from 'node:test';
import {p256} from '@noble/curves/nist.js';
import {sha256} from '@noble/hashes/sha2.js';
import {createDriveAccountLink} from '../dist/account-link.js';
import {
  DRIVE_PASSKEY_PURPOSE, LINK_ORIGIN, RP_ID, SESSION_MS, parseLinkFragment, passkeyChallenge,
  sealResponse, toBase64Url,
} from '../dist/account-link-core.js';
import {issueWnfsAccountProof} from '../dist/wnfs-account.js';
import {runtimeFor} from '../src/sync-contract.js';
import {clientRuntimeProblems} from '../vendor/platform/kotoba-client-runtime.js';

const utf8 = value => new TextEncoder().encode(value);

test('account linking declares one GET only on the configured relay and only when enabled', () => {
  const descriptor = runtimeFor('', false, true);
  assert.deepEqual(clientRuntimeProblems(descriptor, ['awai.drive.account'], 'http://tauri.localhost'), []);
  assert.equal(runtimeFor().operations.length, 0);
  assert.deepEqual(descriptor.operations.map(({endpoint, consent}) => [endpoint.origin, endpoint.routes, consent]),
    [['https://relay.awai.network', [{method: 'GET', path: '/:topic/json', 'query-params': []}], 'each-operation']]);
});

test('Drive saves and rechecks only the authorized device’s scoped existing-account proof', async () => {
  const device = await crypto.subtle.generateKey({name: 'ECDSA', namedCurve: 'P-256'}, true, ['sign', 'verify']);
  const publicKey = toBase64Url(new Uint8Array(await crypto.subtle.exportKey('spki', device.publicKey)));
  let request, stored = {}, deny = false, reads = 0, saves = 0;
  const account = createDriveAccountLink({
    async call(op, args) {
      if (op === 'accountPrepare' || op === 'accountPublic') return {publicKey};
      if (op === 'accountRead') return stored;
      if (op === 'accountBindWnfs') {
        return {proof: await issueWnfsAccountProof(device, stored, 'network.awai.drive',
          {purpose: 'bind-wnfs-key', value: args.value})};
      }
      assert.equal(op, 'accountSave');
      stored = args.link;
      saves++;
    },
    async approve(_operation, url) {
      reads++;
      assert.equal(url, 'https://relay.awai.network/' + request.topic + '/json');
      if (deny) throw Error('client operation was not approved');
      const expiresAt = new Date(Date.now() + SESSION_MS).toISOString();
      const key = p256.utils.randomPrivateKey();
      const client = utf8(JSON.stringify({
        type: 'webauthn.get', origin: LINK_ORIGIN,
        challenge: toBase64Url(passkeyChallenge({...request, expiresAt})),
      }));
      const auth = Uint8Array.from([...sha256(utf8(RP_ID)), 5, 0, 0, 0, 1]);
      const signed = sha256(Uint8Array.from([...auth, ...sha256(client)]));
      const assertion = {
        method: 'passkey', publicKey: toBase64Url(p256.getPublicKey(key)), expiresAt,
        authenticatorData: toBase64Url(auth), clientDataJSON: toBase64Url(client),
        signature: toBase64Url(p256.sign(signed, key, {prehash: false}).toDERRawBytes()),
      };
      const sealed = await sealResponse(request, assertion);
      return new Response('{"event":"keepalive"}\n' + JSON.stringify({event: 'message', message: sealed}) + '\n');
    }
  });
  const signal = new AbortController().signal;
  const pending = await account.begin();
  request = parseLinkFragment(new URL(pending.url).hash);
  assert.equal(request.purpose, DRIVE_PASSKEY_PURPOSE);
  assert.match(pending.code, /^[A-F0-9]{3}-[A-F0-9]{3}$/);
  assert.equal(reads, 0);
  assert.equal(await account.linkedAccount(), null);
  const linked = await pending.finish(signal);
  assert.equal(saves, 1);
  assert.deepEqual(await account.linkedAccount(), linked);
  const key = {id: 'kv1:' + 'a'.repeat(43)};
  const bound = await account.bindWnfsKey(key);
  assert.equal(bound.proof.claim.account, linked.accountDid);
  assert.equal(bound.proof.claim.action.value, key.id);
  await assert.rejects(account.bindWnfsKey({id: 'invalid'}), /invalid-account-action/);
  await assert.rejects(pending.finish(signal), /account-link-expired/);
  stored = {...stored, accountDid: 'did:key:zDifferent'};
  await assert.rejects(account.linkedAccount(), /invalid-account-link/);

  const next = await account.begin();
  request = parseLinkFragment(new URL(next.url).hash);
  deny = true;
  await assert.rejects(next.finish(signal), /not approved/);
  assert.equal(saves, 1);
});
