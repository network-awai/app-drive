export function mountRestore({ sync, t, bytes, onOpen, onFiles }) {
  const get = id => document.getElementById('restore-' + id);
  const panel = document.getElementById('cloud-restore');
  const bookmark = 'awai.drive.pending-restore.v1';
  let point, result, controller, phase, stage = 'choose';
  const primary = get('primary'), secondary = get('secondary');
  const status = key => { get('status').textContent = key ? t(key) : ''; };

  function setBookmark(value) {
    try {
      if (value) localStorage.setItem(bookmark, JSON.stringify(value));
      else localStorage.removeItem(bookmark);
    } catch { /* The current attempt remains available in memory. */ }
  }

  function render(title, description, tone = '') {
    get('title').textContent = t(title);
    get('intro').textContent = t(description, { count: result?.files ?? 0 });
    get('card').dataset.tone = tone;
    get('card').hidden = !point;
    get('name').textContent = t(result ? 'restore.destination' : point?.grant ? 'restore.shared' : 'restore.latest');
    const basis = result?.basis || point?.grant?.basis || point?.basis;
    get('point-id').textContent = basis ? t('restore.basis', { basis }) :
      point?.pack ? t('restore.pack', { pack: point.pack }) : '';
    const details = get('point-details');
    details.hidden = Boolean(result) || !Number.isFinite(point?.size);
    details.textContent = details.hidden ? '' : t('restore.details', {count: point.count, size: bytes(point.size)});
    get('import').hidden = stage !== 'choose';
    get('progress').hidden = stage !== 'running';
    primary.hidden = !point && stage === 'choose';
    primary.disabled = stage === 'running' && (phase === 'publish' || controller?.signal.aborted);
    primary.textContent = t({
      choose: 'restore.select', running: 'restore.pause', paused: 'restore.resume',
      error: 'restore.retry', finished: 'restore.open',
    }[stage]);
    secondary.hidden = stage === 'running';
    secondary.textContent = t(stage === 'choose' ? 'restore.back' : 'restore.return');
  }

  async function choose() {
    point = await sync.restorePoint();
    stage = 'choose';
    result = undefined;
    render('restore.choose', point ? 'restore.chooseHelp' : 'restore.noPoint');
  }

  function reportProgress({ phase: next, bytes: received }) {
    phase = next;
    primary.disabled = next === 'publish' || controller.signal.aborted;
    const current = get('progress').querySelector('[aria-current]');
    current?.removeAttribute('aria-current');
    get('progress').querySelector('[data-stage="' + next + '"]').setAttribute('aria-current', 'step');
    get('bar').value = { fetch: 1, verify: 2, publish: 3 }[next];
    get('stage').textContent = received ? t('restore.received', { size: bytes(received) }) : t('restore.' + next);
  }

  async function run() {
    controller = new AbortController();
    const started = performance.now();
    const { signal } = controller;
    stage = 'running';
    phase = 'fetch';
    setBookmark(point);
    status('');
    render('restore.running', 'restore.runningHelp');
    get('title').focus();
    try {
      const controls = { signal, progress: reportProgress, expectedPack: point.pack };
      result = point.grant ? await sync.restoreSharedSnapshot(point.grant, controls) : await sync.restore(controls);
      setBookmark(null);
      stage = 'finished';
      render('restore.finished', 'restore.finishedHelp', 'ok');
      const seconds = ((performance.now() - started) / 1000).toFixed(2);
      get('status').textContent = t('restore.timing', { seconds });
    } catch (error) {
      const code = String(error?.message ?? error);
      if (signal.aborted || code === 'client operation was not approved') {
        stage = 'paused';
        render('restore.paused', 'restore.pausedHelp', 'warn');
      } else {
        stage = 'error';
        render('restore.unavailable', code === 'invalid-snapshot-grant' ? 'restore.wrongRecipient' : 'restore.verifyFailed', 'error');
      }
    } finally {
      controller = undefined;
      get('title').focus();
    }
  }

  primary.onclick = async () => {
    switch (stage) {
      case 'running':
        if (phase !== 'publish') {
          controller.abort();
          primary.disabled = true;
          status('restore.pauseRequested');
        }
        return;
      case 'finished':
        onFiles(result?.folder);
        return;
      default:
        if (point) await run();
    }
  };
  secondary.onclick = async () => {
    if (stage === 'choose') onFiles();
    else {
      setBookmark(null);
      await choose();
      get('title').focus();
    }
  };

  get('form').onsubmit = async event => {
    event.preventDefault();
    status('');
    try {
      const grant = sync.parseRestoreGrant(get('grant').value.trim());
      const key = await sync.publicSnapshotRecipient();
      if (grant.recipient !== key.id) throw Error('invalid-snapshot-grant');
      point = { grant };
      get('grant').value = '';
      get('import').open = false;
      render('restore.choose', 'restore.chooseHelp');
      primary.focus();
    } catch {
      status('restore.wrongRecipient');
    }
  };

  return async () => {
    panel.hidden = false;
    onOpen();
    if (controller) return;
    status('');
    await choose();
    try {
      const saved = JSON.parse(localStorage.getItem(bookmark));
      if (saved?.grant) point = { grant: sync.parseRestoreGrant(JSON.stringify(saved.grant)) };
      else if (point?.pack !== saved?.pack) return;
      if (point) {
        stage = 'paused';
        render('restore.paused', 'restore.pausedHelp', 'warn');
      }
    } catch { /* Invalid local bookmark: use the normal selection. */ }
    finally { get('title').focus(); }
  };
}
