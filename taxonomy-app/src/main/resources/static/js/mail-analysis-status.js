(() => {
  'use strict';
  const root = document.documentElement;
  const byId = id => document.getElementById(id);
  if (!byId('job-heading')) return;
  const english = root.lang.toLowerCase().startsWith('en');
  const t = (de, en) => english ? en : de;
  const lang = t('de-DE', 'en-GB');
  const paths = {
    brand: '<path d="m3 8 9-5 9 5-9 5z M3 12l9 5 9-5 M3 16l9 5 9-5"/>',
    layers: '<path d="m3 7 9-4 9 4-9 4z M3 12l9 4 9-4 M3 17l9 4 9-4"/>',
    server: '<rect x="3" y="4" width="18" height="7" rx="2"/><rect x="3" y="14" width="18" height="7" rx="2"/><path d="M7 7.5h.01 M7 17.5h.01"/>',
    database: '<ellipse cx="12" cy="5.5" rx="8" ry="3"/><path d="M4 5.5v13c0 4 16 4 16 0v-13 M4 12c0 4 16 4 16 0"/>',
    mail: '<rect x="3" y="5" width="18" height="14" rx="2.5"/><path d="m4 7 8 6 8-6"/>',
    clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7v5l3 2"/>',
    inbox: '<path d="m3 14 3-9h12l3 9v5H3z M3 14h5l2 2h4l2-2h5"/>',
    refresh: '<path d="M4.5 10a7.7 7.7 0 0 1 13-4L20 8 M20 3v5h-5 M19.5 14a7.7 7.7 0 0 1-13 4L4 16 M4 21v-5h5"/>',
    pulse: '<path d="M2 12h5l3-7 4 14 3-7h5"/>',
    cpu: '<rect x="6" y="6" width="12" height="12" rx="2"/><rect x="9" y="9" width="6" height="6" rx="1"/><path d="M9 3v3m6-3v3M9 18v3m6-3v3M3 9h3m-3 6h3m12-6h3m-3 6h3"/>',
    timer: '<circle cx="12" cy="14" r="7.5"/><path d="M12 11v3l3 1M9 3h6m-3 0v3m6 1 2-2"/>',
    radio: '<circle cx="12" cy="12" r="1.3"/><path d="M8 8a6 6 0 0 0 0 8m8-8a6 6 0 0 1 0 8M5 5a10 10 0 0 0 0 14M19 5a10 10 0 0 1 0 14"/>',
    lock: '<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 1 1 8 0v3m-4 5v2"/>',
    file: '<path d="M6 3h8l4 4v14H6zM14 3v5h4m-9 5h6m-6 4h4"/>',
    network: '<rect x="9" y="3" width="6" height="5" rx="1"/><rect x="3" y="16" width="6" height="5" rx="1"/><rect x="15" y="16" width="6" height="5" rx="1"/><path d="M12 8v4m-6 4v-4h12v4"/>',
    link: '<path d="m10 13 4-4m-2-2 2-2a4 4 0 0 1 6 6l-3 3m-5 3-2 2a4 4 0 0 1-6-6l3-3"/>',
    'chevron-down': '<path d="m6 9 6 6 6-6"/>',
    'chevron-right': '<path d="m9 5 7 7-7 7"/>',
    x: '<path d="m6 6 12 12M18 6 6 18"/>'
  };
  function make(tag, className, content) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (content != null) node.textContent = String(content);
    return node;
  }
  function icon(name) {
    const el = make('i');
    el.dataset.icon = name;
    putIcon(el);
    return el;
  }
  function putIcon(el) {
    const path = paths[el.dataset.icon];
    if (!path) return;
    el.setAttribute('aria-hidden', 'true');
    // All SVG markup comes from the constant icon set above.
    el.innerHTML = '<svg class="icon" viewBox="0 0 24 24" focusable="false">' + path + '</svg>';
  }
  document.querySelectorAll('i[data-icon]').forEach(putIcon);
  if (english) {
    document.querySelectorAll('[data-en]').forEach(n => { n.textContent = n.dataset.en; });
    document.querySelectorAll('[data-close]').forEach(n => n.setAttribute('aria-label', 'Close'));
    document.querySelector('.filters').setAttribute('aria-label', 'Queue filter');
    document.title = 'Processing monitor · Taxonomy';
  }

  const states = {
    QUEUED: [t('Wartend', 'Waiting'), 'queued'],
    RUNNING: [t('In Bearbeitung', 'Processing'), 'running'],
    DELIVERING: [t('Im Versand', 'Delivering'), 'running'],
    COMPLETED: [t('Abgeschlossen', 'Completed'), 'done'],
    PARTIAL: [t('Teilergebnis', 'Partial result'), 'neutral'],
    FAILED: [t('Fehlgeschlagen', 'Failed'), 'failed']
  };
  function statusChip(state) {
    const [label, style] = states[state] || [t('Unbekannt', 'Unknown'), 'neutral'];
    return make('span', 'chip ' + style, label);
  }
  function modeName(job) {
    if (job.settings?.mode === 'ARCHITECTURE') return t('Architekturanalyse', 'Architecture analysis');
    if (job.settings?.mode === 'DECISION_TREE') return t('Entscheidungsbaum', 'Decision tree');
    return t('E-Mail-Analyse', 'Email analysis');
  }
  const uuid = '[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}';
  const receiptPath = new RegExp('/mail-analysis/jobs/' + uuid + '(/status)?/?$');
  function receiptUrl(value) {
    try {
      const url = new URL(value);
      if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash || !receiptPath.test(url.pathname)) return null;
      return url;
    } catch { return null; }
  }
  function source() {
    if (root.dataset.url) return { url: new URL(root.dataset.url, location.href), backend: true };
    if (/^#status(?:=|&|$)/i.test(location.hash)) {
      const url = receiptUrl(new URLSearchParams(location.hash.slice(1)).get('status'));
      return url && /\/status\/?$/.test(url.pathname) ? { url } : { invalid: true };
    }
    return { demo: true };
  }
  const connection = source();
  let snapshot = null;
  let reachable = null;
  let probe = null;
  let filter = 'all';
  let activeId = null;
  let timer;
  let busy = false;
  let auto = true;
  let expired = false;
  let errors = 0;
  const interval = 15000;
  const unknown = '—';

  // A fixed, explicitly labelled view is available before opening a receipt.
  // No example entries are rendered for a recognized live or malformed status link.
  function exampleSnapshot() {
    const date = new Date('2026-11-24T14:32:08');
    const at = (hour, minute) => {
      const value = new Date(date);
      value.setHours(hour, minute, 0, 0);
      return value.toISOString();
    };
    const tree = {mode: 'DECISION_TREE', taxonomyCodes: [], taxonomyCount: 2, exports: ['PDF', 'SVG']};
    const architecture = {mode: 'ARCHITECTURE', taxonomyCodes: [], taxonomyCount: 0, exports: ['PDF', 'ARCHIMATE']};
    const exampleSettings = [tree, architecture, tree, architecture, null, null];
    const exampleJobs = [
      [1045, 'RUNNING', 14, 9, null],
      [1046, 'QUEUED', 14, 12, 120],
      [1047, 'QUEUED', 14, 15, 300],
      [1048, 'QUEUED', 14, 19, 480],
      [1049, 'QUEUED', 14, 22, 660],
      [1050, 'QUEUED', 14, 26, 840]
    ].map(([id, state, hour, minute, eta], index) => ({
      displayId: 'TX-' + id, own: id === 1048, state,
      position: index || null, submittedAt: at(hour, minute),
      startedAt: index === 0 ? at(14, 31) : null,
      settings: exampleSettings[index],
      estimatedWaitSeconds: eta
    }));
    return {observedAt: date.toISOString(), workerOnline: true, workerCount: 1, queuedJobs: 5,
      runningJobs: 1, deliveringJobs: 0, capacity: 16, estimateSamples: 18, typicalDurationSeconds: 180,
      job: exampleJobs[3], jobs: exampleJobs};
  }
  const timeFmt = new Intl.DateTimeFormat(lang, {hour: '2-digit', minute: '2-digit'});
  const fullTimeFmt = new Intl.DateTimeFormat(lang, {hour: '2-digit', minute: '2-digit', second: '2-digit'});
  const dayFmt = new Intl.DateTimeFormat(lang, {day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit'});
  function clock(value, fmt = timeFmt) {
    const date = value ? new Date(value) : null;
    return date && Number.isFinite(date.getTime()) ? fmt.format(date) : unknown;
  }
  function duration(seconds) {
    if (!Number.isFinite(seconds) || seconds < 0) return unknown;
    if (seconds === 0) return t('In Kürze', 'Shortly');
    if (seconds < 60) return '< 1 min';
    const minutes = Math.ceil(seconds / 60);
    if (minutes < 60) return minutes + t(' Min.', ' min');
    return Math.floor(minutes / 60) + ' h' + (minutes % 60 ? ' ' + (minutes % 60) + ' min' : '');
  }
  function eta(job) {
    if (job?.state !== 'QUEUED' || !Number.isFinite(job.estimatedWaitSeconds)) return unknown;
    return (job.estimatedWaitSeconds >= 60 ? t('ca. ', '~') : '') + duration(job.estimatedWaitSeconds);
  }
  function metric(id, value, unit) {
    const dd = byId(id);
    dd.replaceChildren(document.createTextNode(String(value)));
    if (unit) dd.append(document.createTextNode(' '), make('small', '', unit));
  }
  function paintHero() {
    const own = snapshot?.job;
    byId('job-heading').textContent = own ? (own.displayId ? t('Auftrag ', 'Job ') + own.displayId : t('Ihr E-Mail-Auftrag', 'Your email job'))
      : expired ? t('Statuslink abgelaufen', 'Status link expired') : t('Auftragsstatus', 'Job status');
    byId('own-state').replaceChildren(...(own ? [statusChip(own.state)] : []));
    metric('own-position', own?.position ?? unknown, own?.position && snapshot?.queuedJobs ? t('von ', 'of ') + snapshot.queuedJobs : '');
    metric('own-eta', eta(own));
    metric('own-received', clock(own?.submittedAt), own?.submittedAt ? t('Uhr', '') : '');
    byId('own-details').disabled = !own;
  }
  function jobRow(job) {
    const tr = make('tr', job.own ? 'own' : '');
    const td = make('td');
    const cell = make('div', 'job-cell');
    const mark = make('span', 'job-icon' + (job.state === 'RUNNING' ? ' active' : ''));
    mark.append(icon(job.state === 'RUNNING' ? 'network' : job.own ? 'mail' : 'file'));
    const info = make('div');
    const name = make('div', 'job-name');
    name.append(make('span', 'job-id', job.displayId || t('Ihr Auftrag', 'Your job')));
    if (job.own) name.append(make('span', 'own-tag', t('Ihr Auftrag', 'Yours')));
    info.append(name, make('span', 'job-sub', modeName(job)));
    cell.append(mark, info);
    td.append(cell);
    const state = make('td');
    state.append(statusChip(job.state));
    tr.append(td, state, make('td', 'num time-col', clock(job.submittedAt)), make('td', 'num eta-col', eta(job)));
    const action = make('td');
    const btn = make('button', 'row-btn');
    btn.type = 'button';
    btn.setAttribute('aria-label', t('Details zu ', 'Details for ') + (job.displayId || t('Ihrem Auftrag', 'your job')));
    btn.append(icon('chevron-right'));
    action.append(btn);
    tr.append(action);
    tr.addEventListener('click', () => { btn.focus({preventScroll: true}); openDetail(job); });
    return tr;
  }
  function paintTable() {
    const rows = snapshot?.jobs ?? [];
    const filtered = filter === 'all' ? rows : rows.filter(job => job.state === filter);
    const focused = document.activeElement?.closest('#jobs .row-btn')?.getAttribute('aria-label');
    const body = byId('jobs');
    body.replaceChildren(...filtered.map(jobRow));
    if (focused) Array.from(body.querySelectorAll('button')).find(el => el.getAttribute('aria-label') === focused)?.focus({preventScroll: true});
    const count = snapshot ? (snapshot.queuedJobs + snapshot.runningJobs + snapshot.deliveringJobs) : null;
    byId('queue-total').textContent = count ?? unknown;
    const empty = byId('queue-empty');
    empty.hidden = filtered.length > 0;
    empty.textContent = !snapshot ? t('Keine aktuellen Queue-Daten verfügbar.', 'No current queue data available.')
      : snapshot.legacy ? t('Diese Backend-Version liefert nur den persönlichen Jobstatus.', 'This backend version only supplies the personal job status.')
      : t('Keine Aufträge in dieser Ansicht.', 'No jobs in this view.');
    byId('row-count').textContent = count === null ? unknown : filter === 'all'
      ? (filtered.length === 1 ? t('1 Auftrag in der Ansicht', '1 job shown') : t(filtered.length + ' Aufträge in der Ansicht', filtered.length + ' jobs shown'))
      : t(filtered.length + ' von ' + count + ' Aufträgen', filtered.length + ' of ' + count + ' jobs');
  }
  function stateLine(id, text, state) {
    const el = byId(id);
    el.textContent = text;
    el.className = state ? 'state-dot ' + state : '';
  }
  function paintServer() {
    const worker = snapshot?.workerOnline;
    const state = snapshot?.workerState;
    const paused = state === 'WAITING_FOR_SOURCE' || state === 'RETRYING';
    const workerLabel = {
      IDLE: t('Bereit', 'Ready'), RUNNING: t('Aktiv', 'Active'),
      WAITING_FOR_SOURCE: t('Wartet auf Daten', 'Waiting for data'),
      RETRYING: t('Wiederanlauf', 'Retrying'), DELIVERING: t('Im Versand', 'Delivering'),
      STOPPED: t('Gestoppt', 'Stopped')
    }[state];
    const degraded = reachable === false || worker === false || paused || probe === false;
    const ok = reachable && worker === true && !degraded;
    const health = byId('health-box');
    health.className = 'health-box ' + (degraded ? 'warn' : ok ? '' : 'unknown');
    byId('health-title').textContent = degraded ? t('Eingeschränkt', 'Degraded') : ok ? t('Betriebsbereit', 'Operational') : t('Status offen', 'Status unknown');
    byId('health-caption').textContent = reachable === false ? t('Statusabruf fehlgeschlagen', 'Status request failed')
      : probe === false ? t('Server meldet eine Einschränkung', 'Server reports a problem')
      : state === 'WAITING_FOR_SOURCE' ? t('Datenquelle derzeit nicht verfügbar', 'Data source temporarily unavailable')
      : state === 'RETRYING' ? t('Erneuter Verarbeitungsversuch ausstehend', 'Waiting to retry')
      : worker === false ? t('Queue-Worker pausiert', 'Queue worker paused')
      : ok ? t('Backend und Worker erreichbar', 'Backend and worker available') : t('Warte auf Statusdaten', 'Waiting for status data');
    stateLine('api-state', reachable === null ? unknown : reachable ? t('Online', 'Online') : t('Unerreichbar', 'Unreachable'), reachable === null ? '' : reachable ? 'ok' : 'warn');
    stateLine('worker-state', reachable === false || worker == null ? unknown : workerLabel || (worker ? t('Aktiv', 'Active') : t('Pausiert', 'Paused')),
      reachable === false || worker == null ? '' : worker && !paused ? 'ok' : 'warn');
    byId('duration').textContent = duration(snapshot?.typicalDurationSeconds);
    const capacity = snapshot?.capacity;
    const used = snapshot?.queuedJobs;
    const pct = capacity > 0 && Number.isFinite(used) ? Math.round(100 * used / capacity) : null;
    byId('capacity-pct').textContent = pct == null ? unknown : pct + ' %';
    byId('capacity-bars').replaceChildren(...Array.from({length: 30}, (_, i) => make('span', pct != null && i < Math.round(pct * .3) ? 'on' : '')));
    byId('capacity-caption').textContent = pct == null ? t('Kapazität unbekannt', 'Capacity unknown')
      : t(used + ' von ' + capacity + ' Warteplätzen belegt', used + ' of ' + capacity + ' queue slots in use');
  }
  function dataRow(list, label, value) {
    const row = make('div');
    const dd = make('dd');
    if (value instanceof Node) dd.append(value); else dd.textContent = value;
    row.append(make('dt', '', label), dd);
    list.append(row);
  }
  function detailBody(job) {
    const body = byId('detail-body');
    const heading = make('h2', '', job.displayId || t('Ihr Auftrag', 'Your job'));
    const data = make('dl', 'detail-data');
    dataRow(data, 'Status', statusChip(job.state));
    dataRow(data, t('Eingegangen', 'Received'), clock(job.submittedAt, dayFmt));
    if (job.startedAt) dataRow(data, t('Gestartet', 'Started'), clock(job.startedAt, dayFmt));
    if (job.state === 'QUEUED') {
      dataRow(data, t('Queue-Position', 'Queue position'), job.position ?? unknown);
      dataRow(data, t('Geschätzte Wartezeit', 'Estimated wait'), Number.isFinite(job.estimatedWaitSeconds) ? eta(job) : t('Noch offen', 'Pending'));
    }
    body.replaceChildren(heading, data, make('h3', '', t('Analyse-Einstellungen', 'Analysis settings')));
    if (job.settings) {
      const config = make('dl', 'detail-data');
      dataRow(config, t('Modus', 'Mode'), modeName(job));
      if (job.settings.taxonomyCodes?.length) {
        dataRow(config, t('Teiltaxonomien', 'Taxonomy scopes'), job.settings.taxonomyCodes.join(', '));
      } else if (job.settings.taxonomyCount > 0) {
        dataRow(config, t('Anzahl Teiltaxonomien', 'Number of taxonomy scopes'), job.settings.taxonomyCount);
      }
      const formats = make('span', 'detail-list');
      for (const f of job.settings.exports ?? []) formats.append(make('span', '', f));
      dataRow(config, t('Exportformate', 'Export formats'), formats);
      body.append(config);
    } else {
      body.append(make('p', '', job.state === 'QUEUED'
        ? t('Taxonomy hat Modus, Teiltaxonomien und Exportformate für diesen Auftrag noch nicht ermittelt.', 'Taxonomy has not yet determined the mode, taxonomy scopes and export formats for this job.')
        : t('Für diesen Auftrag liegen noch keine Einstellungen vor.', 'No settings are available for this job yet.')));
    }
    if (job.state === 'QUEUED') {
      const hint = Number.isFinite(job.estimatedWaitSeconds) && snapshot.estimateSamples > 0
        ? t('Die Schätzung basiert auf ' + snapshot.estimateSamples + ' gemessenen Analyselaufzeiten und der Queue-Position.', 'The estimate uses ' + snapshot.estimateSamples + ' measured processing times and the queue position.')
        : t('Eine Schätzung erscheint, wenn genügend Laufzeiten vorliegen und die Queue verarbeitet werden kann.', 'An estimate appears once enough processing times are available and the queue can run.');
      body.append(make('p', '', hint));
    }
  }
  function openDetail(job) {
    activeId = job.displayId;
    detailBody(job);
    byId('detail-dialog').showModal();
  }
  function render() {
    if (snapshot || expired) document.querySelector('[data-server-fallback]')?.setAttribute('hidden', '');
    document.querySelector('[data-enhanced-dashboard]')?.removeAttribute('hidden');
    paintHero();
    paintTable();
    paintServer();
    byId('freshness').textContent = snapshot ? t('Stand ', 'As of ') + clock(snapshot.observedAt, fullTimeFmt) : '';
    if (byId('detail-dialog').open) {
      const updated = [snapshot?.job, ...(snapshot?.jobs ?? [])].find(x => x && x.displayId === activeId);
      if (updated) detailBody(updated);
      else byId('detail-body').replaceChildren(make('p', '', t('Dieser Auftrag ist nicht mehr in der aktiven Queue.', 'This job is no longer in the active queue.')));
    }
  }
  function notice(msg) {
    byId('notice').textContent = msg || '';
    byId('notice').hidden = !msg;
  }
  async function json(url, signal) {
    const options = {cache: 'no-store', redirect: 'error', credentials: 'omit', referrerPolicy: 'no-referrer', signal};
    return window.TaxonomyApiClient.requestJson(url, options, {workspaceScoped: false, contextSensitive: false});
  }
  function normalize(raw) {
    if (!raw || !states[raw.state] || !Number.isInteger(raw.queuedJobs) || !Number.isInteger(raw.capacity)
        || !Number.isInteger(raw.runningJobs) || !Number.isInteger(raw.deliveringJobs)) throw new Error('Invalid status');
    if (Array.isArray(raw.jobs) && raw.job && states[raw.job.state]) return raw;
    return {...raw, legacy: true, observedAt: new Date().toISOString(), job: {state: raw.state, position: raw.position, own: true}, jobs: []};
  }
  function schedule() {
    clearTimeout(timer);
    if (!auto || expired || connection.invalid || document.hidden) return;
    timer = setTimeout(() => refresh(), interval * (errors ? Math.min(4, errors + 1) : 1));
  }
  async function refresh() {
    if (busy || expired || connection.invalid) return;
    busy = true;
    clearTimeout(timer);
    const button = byId('refresh');
    button.disabled = true;
    button.setAttribute('aria-busy', 'true');
    const control = new AbortController();
    const timeout = setTimeout(() => control.abort(), 9000);
    try {
      if (connection.demo) {
        snapshot = exampleSnapshot();
        reachable = true;
      } else {
        const url = connection.url.href;
        snapshot = normalize(await json(url, control.signal));
        reachable = true;
        const healthUrls = [root.dataset.readinessUrl, root.dataset.livenessUrl].filter(Boolean);
        if (connection.backend && healthUrls.length) {
          const probes = await Promise.allSettled(healthUrls.map(u => json(u, control.signal)));
          probe = probes.some(p => p.status === 'fulfilled' && p.value?.status !== 'UP' ||
            p.status === 'rejected' && p.reason?.status === 503) ? false
            : probes.every(p => p.status === 'fulfilled' && p.value?.status === 'UP') ? true : null;
        }
      }
      errors = 0;
      notice(null);
    } catch (e) {
      errors++;
      if (e?.status === 404) {
        expired = true;
        snapshot = null;
        reachable = null;
        notice(t('Dieser Statuslink ist abgelaufen oder unbekannt. Prüfen Sie den Link in Ihrer Bestätigungsmail.', 'This status link has expired or is unknown. Check the link in your receipt email.'));
      } else {
        reachable = false;
        notice(t('Der Live-Status ist gerade nicht abrufbar.', 'The live status is currently unavailable.') + (snapshot ? t(' Angezeigt wird der letzte erfolgreiche Abruf.', ' Showing the last successful update.') : ''));
      }
    } finally {
      clearTimeout(timeout);
      busy = false;
      button.disabled = expired;
      button.removeAttribute('aria-busy');
      render();
      schedule();
    }
  }
  document.querySelectorAll('[data-filter]').forEach(button => button.addEventListener('click', () => {
    filter = button.dataset.filter;
    document.querySelectorAll('[data-filter]').forEach(b => b.setAttribute('aria-pressed', String(b === button)));
    paintTable();
  }));
  byId('own-details').addEventListener('click', () => { if (snapshot?.job) openDetail(snapshot.job); });
  byId('refresh').addEventListener('click', refresh);
  byId('auto').addEventListener('click', () => {
    auto = !auto;
    byId('auto').setAttribute('aria-checked', String(auto));
    if (auto) refresh(); else clearTimeout(timer);
  });
  document.addEventListener('visibilitychange', () => { if (document.hidden) clearTimeout(timer); else if (auto) refresh(); });
  const navItems = Array.from(document.querySelectorAll('.primary-nav a'));
  document.querySelectorAll('.primary-nav a,.brand,.skip').forEach(link => link.addEventListener('click', e => {
    e.preventDefault();
    const section = document.querySelector(link.getAttribute('href'));
    section?.scrollIntoView({block: 'start'});
    if (section) {section.tabIndex = -1; section.focus({preventScroll: true});}
    navItems.forEach(n => n.classList.toggle('selected', n.hash === link.hash || (link.hash === '#main' && n.hash === '#overview')));
  }));
  document.querySelectorAll('dialog').forEach(d => {
    d.querySelector('[data-close]').addEventListener('click', () => d.close());
    d.addEventListener('click', e => { if (e.target === d && !e.target.closest('.dialog-body,.dialog-head')) {
      const rect = d.getBoundingClientRect();
      if (e.clientX < rect.left || e.clientX > rect.right || e.clientY < rect.top || e.clientY > rect.bottom) d.close();
    }});
  });
  const mode = byId('mode-control');
  mode.hidden = false;
  mode.classList.toggle('live', !connection.demo && !connection.invalid);
  byId('mode-label').textContent = connection.demo ? t('Beispielansicht', 'Example view') : connection.invalid ? t('Statuslink', 'Status link') : t('Live-Ansicht', 'Live view');
  mode.disabled = Boolean(connection.backend);
  mode.addEventListener('click', () => byId('connect-dialog').showModal());
  byId('connect-form').addEventListener('submit', e => {
    e.preventDefault();
    const val = byId('receipt').value.trim();
    let target = receiptUrl(val);
    if (target) {
      target.pathname = target.pathname.replace(/\/status\/?$/, '');
    } else {
      try {
        const fromMail = new URL(val);
        const nested = receiptUrl(new URLSearchParams(fromMail.hash.slice(1)).get('status'));
        if (fromMail.origin === location.origin && nested && /\/status\/?$/.test(nested.pathname)) {
          target = new URL(location.pathname, location.origin);
          target.hash = 'status=' + encodeURIComponent(nested.href);
        }
      } catch { /* Report a malformed status link below. */ }
    }
    if (!target) {
      byId('link-error').textContent = t('Geben Sie den vollständigen HTTPS-Statuslink aus Ihrer E-Mail ein.', 'Enter the full HTTPS status link from your email.');
      byId('link-error').hidden = false;
      return;
    }
    if (target.origin === location.origin && target.hash) { location.replace(target.href); location.reload(); }
    else location.assign(target.href);
  });
  if (connection.invalid) {
    notice(t('Dieser Statuslink ist ungültig. Öffnen Sie den vollständigen Link aus Ihrer Bestätigungsmail.', 'This status link is invalid. Open the full link in your receipt email.'));
    byId('refresh').disabled = true;
    byId('auto').disabled = true;
    byId('auto').setAttribute('aria-checked', 'false');
    render();
  } else {
    refresh();
  }
})();
