import { createDriveAccountLink } from './account-link.js';
import { runtimeFor, accountOperation } from './sync-contract.js';
import { verifyWnfsAccountProof } from './wnfs-account.js';
import { createClientConsentHost, createClientOperationBroker } from './runtime.js';

export function mountAccount({ platform, call, t, sync, openRestore }) {
  const get = id => document.getElementById('account-' + id);
  const mac = platform === 'mac';
  const begin = get('begin');
  const receive = get('receive');
  const cancel = get('cancel');
  let pending, waiting, expiryTimer, linked, proven = false;
  const status = key => { get('status').textContent = key ? t(key) : ''; };

  const broker = mac && createClientOperationBroker({
    runtime: runtimeFor('', false, true),
    requestedCapabilities: [accountOperation.capability],
    approvedOperations: [accountOperation],
    hostSupportedOperations: [[accountOperation.capability, accountOperation.service, accountOperation.operation]],
    consentHost: createClientConsentHost(),
  });
  const account = mac && createDriveAccountLink({
    call,
    approve: ({ capability, service, operation }, input, signal) =>
      broker.invoke({ capability, service, operation }, { input, init: { method: 'GET' } }, { signal }),
  });

  function render() {
    const request = Boolean(pending);
    get('title').textContent = t(request ? 'account.browserTitle' : proven ?
      (mac ? 'account.linkedMac' : 'account.linkedAndroid') : mac ? 'account.macTitle' : 'account.androidTitle');
    get('intro').textContent = t(request ? 'account.compare' : proven ? 'account.linkedIntro' :
      mac ? 'account.macIntro' : 'account.androidIntro');
    get('device').textContent = t(mac ? 'account.macDevice' : 'account.androidDevice');
    get('identity').textContent = t(linked || proven ? 'account.identityVerified' : mac ? 'account.unlinked' : 'account.osIdentity');
    get('key').textContent = t(proven ? 'account.proofVerified' : 'account.unregistered');
    get('summary').dataset.tone = proven ? 'ok' : '';
    get('summary').hidden = request;
    get('request').hidden = !request;
    receive.hidden = !request;
    cancel.hidden = !request;
    begin.hidden = request || proven;
    get('restore').hidden = !proven || !sync.configured;
    begin.textContent = t(mac ? (linked ? 'account.retryKey' : 'account.passkeyLink') : 'account.registerDevice');
  }

  function clearRequest() {
    clearTimeout(expiryTimer);
    pending = undefined;
    get('manual').hidden = true;
    get('url').value = '';
    get('code').textContent = '';
    render();
  }

  async function verifyMacKey() {
    if (!linked || !sync.sharingEnabled) return;
    try {
      await account.bindWnfsKey(await sync.publicSnapshotRecipient());
      proven = true;
    } catch {
      status('account.keyFailed');
    }
  }

  async function registerAndroid() {
    const key = await sync.publicSnapshotRecipient();
    const { proof } = await call('accountProof', { value: key.id });
    const verified = await verifyWnfsAccountProof(proof, { purpose: 'bind-wnfs-key', value: key.id });
    if (verified.app !== 'network.awai.drive') throw Error('invalid-account-link');
    linked = verified;
    proven = true;
  }

  document.getElementById('drive-account').hidden = false;
  render();
  if (mac) {
    begin.disabled = true;
    account.linkedAccount().then(async value => {
      linked = value;
      await verifyMacKey();
    }).catch(() => status('account.failed')).finally(() => {
      begin.disabled = false;
      render();
    });
  }
  begin.onclick = async () => {
    if (waiting || pending) return;
    begin.disabled = true;
    status('');
    try {
      if (!mac) await registerAndroid();
      else if (linked) await verifyMacKey();
      else {
        pending = await account.begin();
        get('code').textContent = pending.code;
        get('expiration').dateTime = new Date(pending.expiresAt).toISOString();
        get('expiration').textContent = t('account.expiration', {
          time: new Intl.DateTimeFormat(document.documentElement.lang, { hour: 'numeric', minute: '2-digit' })
            .format(new Date(pending.expiresAt)),
        });
        expiryTimer = setTimeout(() => {
          if (waiting) waiting.abort('expired');
          else { clearRequest(); status('account.expired'); begin.focus(); }
        }, Math.max(0, pending.expiresAt - Date.now()));
      }
    } catch (error) {
      const code = String(error?.message ?? error);
      status(code === 'account-broker-unavailable' ? 'account.brokerUnavailable' :
        code === 'account-approval-denied' ? 'account.cancelled' : 'account.failed');
    } finally {
      begin.disabled = false;
      render();
      if (pending || proven) get('title').focus();
    }
  };
  receive.onclick = async () => {
    if (!pending || waiting) return;
    const request = pending;
    waiting = new AbortController();
    const signal = waiting.signal;
    receive.disabled = true;
    try {
      linked = await request.finish(signal, async () => {
        signal.throwIfAborted();
        status('account.waiting');
        try { await call('openAccountBrowser', { url: request.url }); }
        catch {
          get('url').value = request.url;
          get('manual').hidden = false;
          get('manual').open = true;
          status('account.browserError');
        }
      });
      status('');
      await verifyMacKey();
    } catch (error) {
      const code = String(error?.message ?? error);
      status(signal.reason === 'expired' || code === 'account-link-expired' ? 'account.expired' :
        signal.aborted || code === 'client operation was not approved' ? 'account.cancelled' :
        code === 'account-mismatch' ? 'account.mismatch' : 'account.failed');
    } finally {
      waiting = undefined;
      clearRequest();
      receive.disabled = false;
      (proven ? get('title') : begin).focus();
    }
  };
  cancel.onclick = () => {
    if (waiting) waiting.abort();
    else { clearRequest(); status('account.cancelled'); begin.focus(); }
  };
  get('restore').onclick = openRestore;
}
