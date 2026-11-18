import {
  DRIVE_PASSKEY_PURPOSE, compressP256, confirmationCode, createLinkRequest, fromBase64Url,
  linkUrl, openResponse, p256Did, verifyLink,
} from './account-link-core.js';
import {accountOperation} from './sync-contract.js';

const maxRelayBytes = 128 * 1024;

export function createDriveAccountLink({call, approve}) {
  const getDeviceDid = async op => {
    const {publicKey} = await call(op);
    return publicKey ? p256Did(compressP256(fromBase64Url(publicKey).slice(-65))) : null;
  };

  async function linkedAccount() {
    const saved = await call('accountRead');
    if (!saved.request) return null;
    const deviceDid = await getDeviceDid('accountPublic');
    if (saved.request.purpose !== DRIVE_PASSKEY_PURPOSE || !deviceDid) throw Error('invalid-account-link');
    try {
      const verified = verifyLink({...saved, deviceDid, atLink: false});
      if (saved.accountDid !== verified.accountDid) throw Error('invalid-account-link');
      return verified;
    } catch (error) {
      if (error.message === 'session-expired') return null;
      throw Error('invalid-account-link', {cause: error});
    }
  }

  return {
    linkedAccount,
    async begin() {
      const deviceDid = await getDeviceDid('accountPrepare');
      const {request, privateKey} = await createLinkRequest(deviceDid, Date.now(), DRIVE_PASSKEY_PURPOSE);
      let used = false;

      return {
        code: confirmationCode(request),
        url: linkUrl(request),
        async finish(signal) {
          if (used || !/^[a-f0-9]{32}$/.test(request.topic) || Date.parse(request.exp) <= Date.now()) {
            throw Error('account-link-expired');
          }
          used = true;
          const reply = await approve(accountOperation,
            accountOperation.endpoint.origin + '/' + request.topic + '/json', signal);
          if (!reply.ok || !reply.body) throw Error('relay-unavailable');
          const reader = reply.body.getReader();
          const decoder = new TextDecoder('utf-8', {fatal: true});
          try {
            let buffer = '', received = 0;
            for (;;) {
              const {value, done} = await reader.read();
              if (done) throw Error('account-link-expired');
              received += value.byteLength;
              if (received > maxRelayBytes) throw Error('relay-response-limit');
              buffer += decoder.decode(value, {stream: true});
              const lines = buffer.split('\n');
              buffer = lines.pop();
              for (const line of lines) {
                let response, verified;
                try {
                  const event = JSON.parse(line);
                  if (event.event !== 'message' || typeof event.message !== 'string' ||
                      event.message.length > 4096) continue;
                  response = await openResponse(request, privateKey, event.message);
                  verified = verifyLink({request, response, deviceDid});
                } catch {
                  continue;
                }
                if (signal.aborted) throw Error('account-link-expired');
                await call('accountSave', {link: {request, response, accountDid: verified.accountDid}});
                return verified;
              }
            }
          } finally {
            await reader.cancel().catch(() => {});
            reader.releaseLock();
          }
        },
      };
    },
  };
}
