import assert from 'node:assert/strict';
import test from 'node:test';
import { createAccountSharing } from '../src/account-share.js';

test('an unsigned recipient cannot enter the sharing ACL', async () => {
  const sharing = createAccountSharing({
    invoke: () => assert.fail('no account request for unsigned data'),
    wnfs: { grant: () => assert.fail('no ACL write for unsigned data') },
  });
  await assert.rejects(sharing.grant({}, { id: 'legacy-key' }), /account-recipient-required/);
});

test('an unsigned or malformed share is refused before restore', async () => {
  const sharing = createAccountSharing({
    invoke: () => assert.fail('no native call for unsigned data'),
    wnfs: { restoreShared: () => assert.fail('no storage request for unsigned data') },
  });
  await assert.rejects(sharing.restore({}, { schema: 'awai.drive.wnfs.grant.v2' }), /account-share-required/);
  await assert.rejects(sharing.restore({}, { schema: 'awai.wnfs.account-share.v1', grant: {} }), /invalid-account-share/);
});

test('ordinary sync does not silently publish to old unsigned recipients', async () => {
  const items = { legacy: { id: 'legacy' }, expired: { id: 'expired' } };
  const sharing = createAccountSharing({
    invoke: () => assert.fail('no account request for owner-only sync'),
    wnfs: { async sync(_session, select) {
      assert.deepEqual(await select(items, { expired: { schema: 'awai.wnfs.account-proof.v1', claim: { expires: 1 }, key: 'A'.repeat(87), signature: 'A'.repeat(86) } }), []);
      return { grants: [] };
    } },
  });
  assert.deepEqual(await sharing.sync({}), { grants: [], unverifiedRecipients: ['legacy', 'expired'], unpublishedRecipients: [] });
});
