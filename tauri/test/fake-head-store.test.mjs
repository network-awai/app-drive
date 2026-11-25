import test from 'node:test';
import assert from 'node:assert/strict';
import { createFakeHeadStore } from './fake-head-store.mjs';

test('fake head store: two heads stored in seq order, newest last', () => {
  const store = createFakeHeadStore();
  store.publish('owner-A', 'head-name-A', { seq: 1, capsuleId: 'a'.repeat(64), issuedAt: '2026-06-15T00:00:00Z' });
  store.publish('owner-A', 'head-name-A', { seq: 2, capsuleId: 'b'.repeat(64), issuedAt: '2026-11-01T00:00:00Z' });
  const heads = store.query('head-name-A');
  assert.equal(heads.length, 2);
  assert.equal(heads[0].seq, 1);
  assert.equal(heads[1].seq, 2);
  assert.equal(heads[1].issuedAt, '2026-11-01T00:00:00Z');
  assert.equal(store.query('unknown-name').length, 0);
});

test('fake head store: equal-or-older seq is rejected as rollback', () => {
  const store = createFakeHeadStore();
  store.publish('owner-A', 'head-name-A', { seq: 2, capsuleId: 'a'.repeat(64), issuedAt: 't' });
  assert.throws(
    () => store.publish('owner-A', 'head-name-A', { seq: 2, capsuleId: 'x'.repeat(64), issuedAt: 't' }),
    /rollback/i,
  );
  assert.throws(
    () => store.publish('owner-A', 'head-name-A', { seq: 1, capsuleId: 'x'.repeat(64), issuedAt: 't' }),
    /rollback/i,
  );
  assert.equal(store.query('head-name-A').length, 1, 'rejected publishes must not land');
});

test('fake head store: heads are per-owner; unknown owner is refused', () => {
  const store = createFakeHeadStore();
  store.publish('owner-A', 'head-name-A', { seq: 1, capsuleId: 'a'.repeat(64), issuedAt: 't1' });
  store.publish('owner-B', 'head-name-B', { seq: 1, capsuleId: 'c'.repeat(64), issuedAt: 't2' });
  const a = store.headForOwner('owner-A');
  assert.equal(a.headName, 'head-name-A');
  assert.equal(a.heads.length, 1);
  assert.equal(a.heads[0].capsuleId, 'a'.repeat(64));
  const b = store.headForOwner('owner-B');
  assert.equal(b.headName, 'head-name-B');
  assert.equal(b.heads[0].capsuleId, 'c'.repeat(64));
  assert.throws(() => store.headForOwner('owner-C'), /unknown owner/i);
});
