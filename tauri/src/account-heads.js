import { fromBase64Url, toBase64Url } from './account-link-core.js';

export const accountHeadsType = 'awai.drive.account-heads.v1';
const utf8 = new TextEncoder();
const decode = new TextDecoder('utf-8', { fatal: true });
const protectedHeader = toBase64Url(utf8.encode(JSON.stringify({ alg: 'EdDSA', typ: accountHeadsType })));
const maxEnvelopeLength = 256 * 1024;
const maxAgeMs = 60_000;
const accountDid = /^did:key:z[1-9A-HJ-NP-Za-km-z]{20,110}$/;
const recipientId = /^kv1:[A-Za-z0-9_-]{43}$/;
const b64url = /^[A-Za-z0-9_-]+$/;

const reject = () => Error('invalid-account-heads');

// The host authenticates this operation, verifies current device membership
// and supplies the issuer key from trusted configuration, never from a reply.
export async function discoverAccountHeads({ requestHeads, issuerKey, subject, device, recipient, now = Date.now }) {
  if (!accountDid.test(subject) || !accountDid.test(device) || !recipientId.test(recipient) ||
      issuerKey?.type !== 'public' || issuerKey.algorithm.name !== 'Ed25519') throw reject();

  const nonce = toBase64Url(crypto.getRandomValues(new Uint8Array(24)));
  const scope = Object.freeze({ type: accountHeadsType, subject, device, recipient, nonce });
  const issued = now();
  const envelope = await requestHeads(scope);

  if (typeof envelope !== 'string' || envelope.length > maxEnvelopeLength) throw reject();
  const parts = envelope.split('.');
  if (parts.length !== 3 || parts[0] !== protectedHeader ||
      !b64url.test(parts[1]) || parts[2].length !== 86 || !b64url.test(parts[2])) throw reject();
  const signature = fromBase64Url(parts[2]);
  if (toBase64Url(signature) !== parts[2] ||
      !await crypto.subtle.verify('Ed25519', issuerKey, signature, utf8.encode(parts[0] + '.' + parts[1]))) throw reject();

  let payload;
  try {
    payload = JSON.parse(decode.decode(fromBase64Url(parts[1])));
  } catch {
    throw reject();
  }
  const current = now();
  if (payload?.type !== accountHeadsType || payload.subject !== subject ||
      payload.device !== device || payload.recipient !== recipient || payload.nonce !== nonce ||
      !Number.isSafeInteger(payload.expires) || payload.expires <= current ||
      payload.expires > issued + maxAgeMs || current < issued ||
      !Number.isSafeInteger(payload.policyEpoch) || payload.policyEpoch < 0 ||
      !Array.isArray(payload.sources)) throw reject();

  return { subject, sources: payload.sources, expires: payload.expires };
}
