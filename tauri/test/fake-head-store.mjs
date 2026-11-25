// Fake ordering store for head tests (test-only, fake storage only).
// Moved from src/: lives in test/ because it is a test double, not a
// production component. Heads are per-owner: each owner binds to its own
// head name; headForOwner returns only that owner's heads and refuses
// unknown owners.
//
// publish(owner, headName, entry): records entry under (owner, headName) with
// a strictly-greater seq rule (equal-or-older seq rejected as rollback).
// query(headName): all entries under the name, ascending seq.
// headForOwner(owner): { headName, heads } for the owner's bound head.

export function createFakeHeadStore() {
  const byName = new Map(); // headName -> { seq, entries: [] }
  const byOwner = new Map(); // owner -> headName

  return {
    publish(owner, headName, entry) {
      const cur = byName.get(headName) ?? { seq: 0, entries: [] };
      if (!(typeof entry.seq === 'number') || entry.seq <= cur.seq) {
        throw new Error('rollback: headSeq must strictly increase');
      }
      byName.set(headName, { seq: entry.seq, entries: [...cur.entries, { ...entry }] });
      byOwner.set(owner, headName);
      return entry.seq;
    },
    query(headName) {
      return [...(byName.get(headName)?.entries ?? [])].sort((a, b) => a.seq - b.seq);
    },
    headForOwner(owner) {
      const headName = byOwner.get(owner);
      if (!headName) throw new Error('unknown owner: no heads published');
      return { headName, heads: [...(byName.get(headName)?.entries ?? [])] };
    },
  };
}
