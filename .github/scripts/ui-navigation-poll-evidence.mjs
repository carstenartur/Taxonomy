import { isDeepStrictEqual } from 'node:util';

/** A proof can remove only the particular recorded error that produced it. */
export function createConsoleErrorLedger(messages, reconciled) {
  const ids = [];
  let nextId = 0;
  return {
    record(message) {
      const id = ++nextId;
      ids.push(id); messages.push(message);
      return id;
    },
    reconcile(candidates) {
      for (const candidate of candidates) {
        const index = ids.indexOf(candidate.errorId);
        if (index < 0 || messages[index] !== candidate.message) continue;
        ids.splice(index, 1); messages.splice(index, 1);
        reconciled.push(candidate);
      }
    }
  };
}

function withoutFragment(value) {
  const url = new URL(value); url.hash = ''; return url.href;
}
function translated(value) {
  return typeof value === 'string' && value.length > 0
    && !/^(?:startup\.ui\.|workspace\.sync\.|git\.|context\.)/.test(value);
}
function normalized(value) { return String(value ?? '').replace(/\s+/g, ' ').trim(); }

function adoptedReloadRead(endpoint, read, observation, messages, destination) {
  const branch = destination.searchParams.get('branch'), workspace = destination.searchParams.get('workspaceId');
  const context = observation.context, data = read.payload;
  if (!branch || !workspace || read.workspaceId !== workspace || context?.state?.branch !== branch) return null;
  if (endpoint.endsWith('/context/current')) {
    if (typeof data.contextId !== 'string' || !data.contextId || data.branch !== branch
        || !isDeepStrictEqual(context.state, data) || !context.rendered || !(context.writeCount > 0)
        || !translated(messages.contextBranch) || !translated(messages.contextMode)
        || context.branchText !== messages.contextBranch || context.modeText !== messages.contextMode) return null;
    return context;
  }
  if (data.currentBranch !== branch || typeof data.projectionStale !== 'boolean'
      || typeof data.indexStale !== 'boolean' || !Array.isArray(data.branches)
      || typeof data.totalCommits !== 'number' || typeof data.operationInProgress !== 'boolean'
      || !(observation.git?.writeCount > 0)
      || !['gitProjection', 'gitIndex', 'gitProjectionState', 'gitIndexState'].every(key => translated(messages[key]))) return null;
  const parts = [`🔀 ${branch} @ ${data.headCommit ? data.headCommit.substring(0, 7) : '—'}`, '│',
    `${messages.gitProjection}: ${messages.gitProjectionState}`, '│', `${messages.gitIndex}: ${messages.gitIndexState}`];
  for (const [shown, key] of [[data.branches.length > 0, 'gitVariants'], [data.totalCommits > 0, 'gitVersions'],
    [data.operationInProgress, 'gitOperation']]) if (shown) {
    if (!translated(messages[key])) return null;
    parts.push('│', (key === 'gitOperation' ? '🔴 ' : '') + messages[key]);
  }
  if (context.state.mode === 'READ_ONLY') parts.push('│', 'READ-ONLY');
  return normalized(observation.git.text) === normalized(parts.join(' ')) ? observation.git : null;
}

/** Classify only an engine-originated cancellation with complete navigation and adoption evidence. */
export function classifyWebKitPollCancellation(input) {
  try {
    const { browserName, baseUrl, error, navigation, events, reads, observation } = input;
    if (browserName !== 'webkit' || error?.kind !== 'page-error' || !navigation?.completed) return null;
    const base = new URL(baseUrl), { oldDocument, newDocument } = navigation;
    if (!Number.isFinite(oldDocument?.epoch) || !Number.isFinite(newDocument?.epoch)
        || oldDocument.epoch === newDocument.epoch || error.documentEpoch !== oldDocument.epoch
        || observation?.documentEpoch !== newDocument.epoch) return null;
    const oldUrl = new URL(oldDocument.url), newUrl = new URL(newDocument.url);
    const reload = navigation.kind === 'reload';
    if (navigation.kind && !['reload', 'locale'].includes(navigation.kind)) return null;
    if (oldUrl.origin !== base.origin || newUrl.origin !== base.origin
        || !reload && (newUrl.searchParams.getAll('lang').length !== 1
          || newUrl.searchParams.get('lang') !== navigation.locale)
        || withoutFragment(error.pageUrl) !== withoutFragment(oldDocument.url)
        || withoutFragment(observation.url) !== withoutFragment(newDocument.url)) return null;
    if (reload) {
      if (oldUrl.href !== newUrl.href || observation.url !== newUrl.href || error.pageUrl !== oldUrl.href) return null;
    } else {
      oldUrl.hash = ''; newUrl.hash = '';
      oldUrl.searchParams.delete('lang'); newUrl.searchParams.delete('lang');
      if (oldUrl.href !== newUrl.href) return null; // All retained source pins must survive.
    }

    const prefix = base.pathname.replace(/\/$/, '');
    const syncPath = `${prefix}/api/workspace/sync-state`;
    const workspace = oldUrl.searchParams.get('workspaceId');
    const scopedSyncPath = workspace && oldUrl.searchParams.getAll('workspaceId').length === 1
      ? `${syncPath}?workspaceId=${encodeURIComponent(workspace)}` : null;
    const allowed = reload ? [`${prefix}/api/git/state?branch=${encodeURIComponent(oldUrl.searchParams.get('branch') || '')}`,
      `${prefix}/api/context/current`] : [`${prefix}/api/status/startup`, syncPath, scopedSyncPath].filter(Boolean);
    const endpoint = allowed
      .find(path => error.name === `Fetch API cannot load ${base.protocol.slice(0, -1)}`
        && error.message === `/${base.host}${path} due to access control checks.`
        && error.stack?.split('\n')[0] === `Fetch API cannot load ${base.origin}${path} due to access control checks.`);
    if (!endpoint || !(navigation.startSequence < error.sequence
        && error.sequence < navigation.endSequence)) return null;
    const oldLifecycle = events.filter(event => ['beforeunload', 'pagehide'].includes(event.type)
      && event.documentEpoch === oldDocument.epoch
      && withoutFragment(event.url) === withoutFragment(oldDocument.url));
    const beforeUnload = oldLifecycle.find(event => event.type === 'beforeunload'
      && navigation.startSequence < event.sequence && event.sequence < error.sequence);
    const pageHide = oldLifecycle.find(event => event.type === 'pagehide'
      && error.sequence < event.sequence && event.sequence < navigation.endSequence);
    if (!beforeUnload || !pageHide || events.some(event =>
      ['error', 'unhandledrejection', 'securitypolicyviolation'].includes(event.type)
      && [oldDocument.epoch, newDocument.epoch].includes(event.documentEpoch)
      && event.sequence >= navigation.startSequence)) return null;

    const endpointUrl = base.origin + endpoint;
    const scopedSync = endpoint === scopedSyncPath;
    const bootstrapSyncUrl = base.origin + syncPath;
    // The original WebKit cancellation signature names the unpinned status
    // endpoint. A successful destination GET can carry the exact retained
    // workspace as a canonical query pin, in addition to its verified header.
    // Construct that one permitted URL instead of ignoring query parameters:
    // a foreign/duplicate pin or any other query parameter remains blocking.
    let retainedReloadUrl = null;
    if (reload && workspace && oldUrl.searchParams.getAll('workspaceId').length === 1) {
      const url = new URL(endpointUrl);
      url.searchParams.set('workspaceId', workspace);
      retainedReloadUrl = url.href;
    }
    // The destination may poll before its workspace session has hydrated.
    // Include the pinned request in the failure veto as well as in adoption.
    const matchesPoll = read => read.url === endpointUrl
      || retainedReloadUrl !== null && read.url === retainedReloadUrl
      || scopedSync && read.url === bootstrapSyncUrl;
    // A real network/HTTP failure is not the engine's pre-request cancellation.
    if (reads.some(read => matchesPoll(read) && (read.failed || read.status >= 400)
        && (read.documentEpoch === newDocument.epoch
          || read.documentEpoch === oldDocument.epoch
            && read.sequence >= navigation.startSequence && read.sequence <= navigation.endSequence))) return null;
    for (const read of reads) {
      if (!matchesPoll(read) || read.method !== 'GET' || read.status !== 200
          || !read.finished || !read.jsonParsed || read.failed || read.redirected
          || !(read.sequence > navigation.startSequence)
          || read.documentEpoch !== newDocument.epoch
          || withoutFragment(read.pageUrl) !== withoutFragment(newDocument.url)) continue;
      if (scopedSync && (read.workspaceId ?? null) !== (read.url === endpointUrl ? workspace : null)) continue;
      const data = read.payload, messages = observation.messages?.[read.id];
      if (!data || typeof data !== 'object' || Array.isArray(data) || !messages) continue;
      let render;
      if (reload) {
        if (read.pageUrl !== newDocument.url) continue;
        render = adoptedReloadRead(endpoint, read, observation, messages, newUrl);
        if (!render) continue;
      } else if (endpoint.endsWith('/status/startup')) {
        if (typeof data.initialized !== 'boolean' || !data.memory
            || !translated(messages.ready) || !translated(messages.loading) || !translated(messages.threads)
            || !(observation.startup?.writeCount > 0)) continue;
        const mem = data.memory, pct = mem.heapUsagePercent || 0;
        const icon = pct > 85 ? '🔴' : pct > 70 ? '🟡' : '🟢';
        const status = data.initialized ? messages.ready : data.phaseMessage || messages.loading;
        const text = `${icon} ${status} | Heap: ${pct}% (${mem.heapUsedMB || '?'}/${mem.heapMaxMB || '?'} MB)`
          + ` | ${messages.threads}: ${mem.threadCount || '?'}`;
        if (observation.startup.text !== text) continue;
        render = observation.startup;
      } else {
        if (typeof data.syncStatus !== 'string' || !(observation.sync?.writeCount > 0)
            || !translated(messages.syncIndicator) || !translated(messages.syncPanel)
            || !isDeepStrictEqual(observation.sync.state, data)
            || !observation.sync.indicatorText?.includes(messages.syncIndicator)
            || !observation.sync.panelText?.includes(messages.syncPanel)) continue;
        render = observation.sync;
      }
      return { reason: 'webkit-provisional-navigation-poll-cancelled', endpoint,
        kind: navigation.kind || 'locale', locale: navigation.locale, requestId: read.id, oldDocument, newDocument, rawError: error,
        lifecycle: { beforeUnload, pageHide, startSequence: navigation.startSequence, endSequence: navigation.endSequence },
        request: read, render, messages };
    }
  } catch {
    // Missing/malformed evidence must preserve the original audit blocker.
  }
  return null;
}

// Browser/HTTP observations only: no replacement requests, timers, fetch
// wrappers or application state changes. The epoch identifies a real document;
// the sequence orders events received by this one Playwright connection.
export function createNavigationPollEvidence({ browserName, baseUrl }) {
  const events = [], reads = [], errors = [], navigations = [];
  const requests = new WeakMap(), bodyTasks = new Set();
  const base = new URL(baseUrl);
  const prefix = base.pathname.replace(/\/$/, '');
  const endpoints = [`${prefix}/api/status/startup`, `${prefix}/api/workspace/sync-state`,
    `${prefix}/api/git/state`, `${prefix}/api/context/current`];
  let page, currentDocument = null, activeNavigation = null, sequence = 0, nextRead = 0;
  function record(value) {
    const event = { ...value, sequence: ++sequence };
    events.push(event); return event;
  }
  function documentIdentity() {
    return { epoch: performance.timeOrigin, url: location.href };
  }
  function installDocumentObserver() {
    const epoch = performance.timeOrigin;
    if (window.__taxonomyPollEvidence?.epoch === epoch) return { epoch, url: location.href };
    const state = { epoch, startupWrites: 0, syncWrites: 0, gitWrites: 0, contextWrites: 0 };
    window.__taxonomyPollEvidence = state;
    const send = (type, detail = {}) => {
      window.__taxonomyQaPollLifecycle({ type, documentEpoch: epoch, url: location.href,
        nativeTimestamp: Date.now(), ...detail }).catch(() => {});
    };
    send('document-start');
    for (const type of ['beforeunload', 'pagehide', 'error', 'unhandledrejection', 'securitypolicyviolation']) {
      window.addEventListener(type, event => send(type, {
        persisted: event.persisted ?? null, message: event.message ?? null,
        name: event.error?.name ?? event.reason?.name ?? null,
        stack: event.error?.stack ?? event.reason?.stack ?? null,
        blockedURI: event.blockedURI ?? null
      }));
    }
    function observe() {
      for (const [id, counter] of [['systemStatusBar', 'startupWrites'], ['syncStatePanel', 'syncWrites'],
        ['gitStatusBar', 'gitWrites'], ['contextBar', 'contextWrites']]) {
        const element = document.getElementById(id);
        if (!element) continue;
        new MutationObserver(() => {
          state[counter]++;
          send('poll-render', { id, writes: state[counter], text: element.textContent });
        }).observe(element, { childList: true, subtree: true, characterData: true });
      }
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', observe, { once: true });
    else observe();
    return { epoch, url: location.href };
  }
  return {
    async attach(observedPage, context) {
      page = observedPage;
      if (browserName !== 'webkit') return;
      await context.exposeBinding('__taxonomyQaPollLifecycle', (source, value) => {
        if (source.page !== page || source.frame !== page.mainFrame()) return;
        record(value);
        if (value.type === 'document-start') currentDocument = { epoch: value.documentEpoch, url: value.url };
      });
      await context.addInitScript(installDocumentObserver);
      currentDocument = await page.evaluate(installDocumentObserver);
      page.on('request', request => {
        const url = new URL(request.url());
        const projectRead = url.pathname.startsWith(prefix + '/')
          && /^\/api\/projects\/[1-9]\d*\/(?:analysis-jobs(?:\/[A-Za-z0-9._-]+)?|architecture-workbench\/[A-Za-z0-9._-]+)$/.test(url.pathname.slice(prefix.length));
        if (url.origin !== base.origin || !endpoints.includes(url.pathname) && !projectRead) return;
        const read = { id: ++nextRead, url: request.url(), method: request.method(),
          documentEpoch: currentDocument?.epoch, pageUrl: page.url(),
          workspaceId: request.headers()['x-taxonomy-workspace-id'] || null,
          redirected: Boolean(request.redirectedFrom()), finished: false, jsonParsed: false };
        read.sequence = record({ type: 'poll-request', ...read }).sequence;
        requests.set(request, read); reads.push(read);
      });
      page.on('response', response => {
        const read = requests.get(response.request());
        if (!read) return;
        read.status = response.status();
        record({ type: 'poll-response', requestId: read.id, documentEpoch: read.documentEpoch,
          url: read.url, status: read.status });
      });
      page.on('requestfailed', request => {
        const read = requests.get(request);
        if (!read) return;
        read.failed = request.failure()?.errorText || 'request failed';
        record({ type: 'poll-request-failed', requestId: read.id, documentEpoch: read.documentEpoch,
          url: read.url, failure: read.failed });
      });
      page.on('requestfinished', request => {
        const read = requests.get(request);
        if (!read) return;
        read.finished = true;
        read.finishedSequence = record({ type: 'poll-request-finished', requestId: read.id,
          documentEpoch: read.documentEpoch, url: read.url }).sequence;
        const task = (async () => {
          try {
            const response = await request.response();
            if (response?.status() !== 200 || !/\bapplication\/json\b/i.test(response.headers()['content-type'] || '')) return;
            read.payload = await response.json();
            read.jsonParsed = true;
            read.parsedSequence = record({ type: 'poll-json-consumed', requestId: read.id,
              documentEpoch: read.documentEpoch, url: read.url }).sequence;
          } catch {
            read.jsonParsed = false;
          }
        })();
        bodyTasks.add(task);
        task.finally(() => bodyTasks.delete(task));
      });
    },
    async begin(locale, kind = 'locale', declaration) {
      if (browserName !== 'webkit') return;
      const oldDocument = await page.evaluate(documentIdentity);
      currentDocument = oldDocument;
      activeNavigation = { kind, locale, oldDocument, completed: false };
      if (kind === 'portfolio-snapshot') {
        await Promise.all([...bodyTasks]);
        activeNavigation.declaration = structuredClone(declaration);
        activeNavigation.sourceObservation = await page.evaluate(expected => {
          const title = document.getElementById('job-' + expected.jobId);
          const card = title?.closest('.portfolio-job');
          const row = [...(card?.querySelectorAll('tbody tr') || [])]
            .find(item => item.querySelector('td')?.textContent.trim() === expected.requirementKey);
          const cells = row?.querySelectorAll('td');
          return { documentEpoch: performance.timeOrigin, url: location.href,
            scope: window.TaxonomyArchitectureWorkflow?.getScope?.(),
            projectId: window.localStorage.getItem('taxonomy.portfolio.projectId'),
            job: { url: card?.getAttribute('data-job-url'), heading: title?.textContent.trim(),
              item: { key: cells?.[0]?.textContent.trim(), status: cells?.[1]?.textContent.trim(),
                snapshot: cells?.[3]?.querySelector(':scope > code')?.textContent.trim() },
              warnings: card?.querySelectorAll('.text-warning').length } };
        }, declaration);
      }
      activeNavigation.startSequence = record({ type: `${kind}-navigation-start`, locale, ...oldDocument }).sequence;
      navigations.push(activeNavigation);
    },
    async end(locale, succeeded = true, kind = 'locale') {
      if (browserName !== 'webkit' || activeNavigation?.locale !== locale || activeNavigation?.kind !== kind) return;
      if (!succeeded) {
        activeNavigation.endSequence = record({ type: `${kind}-navigation-failed`, locale }).sequence;
        activeNavigation = null;
        return;
      }
      activeNavigation.newDocument = await page.evaluate(documentIdentity);
      activeNavigation.endSequence = record({ type: `${kind}-navigation-end`, locale, ...activeNavigation.newDocument }).sequence;
      activeNavigation.completed = activeNavigation.newDocument.epoch !== activeNavigation.oldDocument.epoch;
      currentDocument = activeNavigation.newDocument;
      activeNavigation = null;
    },
    recordError(error, errorId, pageUrl = page?.url()) {
      if (browserName !== 'webkit') return;
      const raw = record({ type: 'poll-page-error', kind: 'page-error', name: error.name,
        message: error.message, stack: error.stack, pageUrl,
        documentEpoch: currentDocument?.epoch });
      errors.push({ error: raw, errorId, navigation: activeNavigation });
    },
    async verifyDestination(locale, kind = 'locale') {
      if (browserName !== 'webkit') return;
      const navigation = navigations.findLast(item => item.locale === locale && item.kind === kind);
      if (!navigation?.completed) return;
      if (kind === 'reload') await page.waitForFunction(() =>
        window.__taxonomyPollEvidence?.gitWrites > 0 && window.__taxonomyPollEvidence?.contextWrites > 0
          && window.TaxonomyContextBar?.getCurrentContext?.(), null, { timeout: 15000 });
      // Only already finished original requests can contribute proof. An open
      // response/body is never turned into a successful read by a new GET.
      await Promise.all([...bodyTasks]);
      if (kind === 'portfolio-snapshot') {
        navigation.observation = await page.evaluate(() => {
          const workspace = window.TaxonomyArchitectureWorkspace;
          const surface = document.getElementById('architectureSnapshotSource');
          const canvas = surface?.querySelector('#architectureCanvas');
          return { documentEpoch: performance.timeOrigin, url: location.href,
            snapshot: { source: workspace?.getSource?.(), data: workspace?.getData?.(),
              rendered: workspace?.isActive?.() === true && surface?.hidden === false
                && (canvas?.getClientRects().length || 0) > 0,
              nodeCount: surface?.querySelectorAll('#architectureCanvas [data-node-id]').length || 0,
              requirementText: document.getElementById('requirementText')?.textContent,
              workflowState: document.getElementById('workflowState')?.textContent } };
        });
        return;
      }
      const fresh = reads.filter(read => read.documentEpoch === navigation.newDocument.epoch
        && read.finished && read.jsonParsed && !read.failed);
      navigation.observation = await page.evaluate(freshReads => {
        const qa = window.__taxonomyPollEvidence;
        const t = window.TaxonomyI18n?.t;
        const messages = {};
        if (t) for (const read of freshReads) {
          const data = read.payload;
          if (!data || typeof data !== 'object' || Array.isArray(data)) continue;
          const status = data.syncStatus;
          const suffix = status === 'AHEAD' ? 'ahead' : status === 'BEHIND' ? 'behind'
            : status === 'DIVERGED' ? 'diverged' : 'uptodate';
          const indicator = status === 'AHEAD' ? 'ahead' : status === 'BEHIND' ? 'behind'
            : status === 'DIVERGED' ? 'diverged' : 'synced';
          messages[read.id] = {
            ready: t('startup.ui.ready'), loading: t('startup.ui.loading', data.status || '…'),
            threads: t('startup.ui.threads'),
            syncIndicator: t('workspace.sync.status.' + indicator, data.unpublishedCommitCount),
            syncPanel: t('workspace.sync.panel.' + suffix, data.unpublishedCommitCount),
            gitProjection: t('git.projection'), gitIndex: t('git.index'),
            gitProjectionState: t(data.projectionStale ? 'git.stale' : 'git.fresh'),
            gitIndexState: t(data.indexStale ? 'git.stale' : 'git.fresh'),
            gitVariants: t('git.variants.count', data.branches?.length || 0),
            gitVersions: t('git.versions.count', data.totalCommits || 0),
            gitOperation: t('git.operation.in.progress', data.operationKind || 'operation'),
            contextBranch: window.TaxonomyI18n.formatBranch(data.branch),
            contextMode: data.mode === 'READ_ONLY' ? '🟡 ' + t('context.mode.readonly.label')
              : data.mode === 'TEMPORARY' ? '⚪ ' + t('context.mode.temporary') : '🟢 ' + t('context.mode.editable')
          };
        }
        const git = document.getElementById('gitStatusBar')?.cloneNode(true);
        git?.querySelector('#syncStatusIndicator')?.remove();
        const context = document.getElementById('contextBar');
        return { documentEpoch: performance.timeOrigin, url: location.href,
          startup: { text: document.getElementById('systemStatusBar')?.textContent,
            writeCount: qa?.startupWrites || 0 },
          sync: { state: window.TaxonomyWorkspaceSync?.getSyncState?.(),
            indicatorText: document.getElementById('syncStatusIndicator')?.textContent,
            panelText: document.getElementById('syncStatePanel')?.textContent,
            writeCount: qa?.syncWrites || 0 },
          git: { text: git?.textContent, writeCount: qa?.gitWrites || 0 },
          context: { state: window.TaxonomyContextBar?.getCurrentContext?.(),
            branchText: context?.querySelector('.wb-row > strong')?.textContent,
            modeText: context?.querySelector('.wb-row > .badge')?.textContent,
            rendered: context?.getAttribute('data-context-rendered') === 'true', writeCount: qa?.contextWrites || 0 }, messages };
      }, fresh);
    },
    reconciliations() {
      return errors.flatMap(item => {
        const classify = item.navigation?.kind === 'portfolio-snapshot'
          ? classifyPortfolioSnapshotPollCancellation : classifyWebKitPollCancellation;
        const proof = classify({ browserName, baseUrl, error: item.error,
          navigation: item.navigation, events, reads, observation: item.navigation?.observation });
        return proof ? [{ ...proof, errorId: item.errorId, message: item.error.message }] : [];
      });
    },
    snapshot() { return { events, reads, errors: errors.map(item => item.error), navigations }; }
  };
}

// Snapshot departures share this existing navigation-audit owner. Keep their
// predicate helpers scoped independently from the locale/reload proof.
export const classifyPortfolioSnapshotPollCancellation = (() => {
  const terminalStatuses = new Set(['SUCCESS', 'PARTIAL']);
  const statusLabel = status => status === 'SUCCESS' ? 'Successful' : 'Partial';
  const record = value => value !== null && typeof value === 'object' && !Array.isArray(value);
  const nonempty = value => typeof value === 'string' && value.length > 0 && value.trim() === value;
  const sequence = value => Number.isSafeInteger(value) && value > 0;
  const identifier = value => nonempty(value) && /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(value);
  const positiveId = value => /^(?:[1-9]\d*)$/.test(String(value)) && Number.isSafeInteger(Number(value));
  const sameId = (left, right) => positiveId(left) && positiveId(right) && String(left) === String(right);

  /** Query order is immaterial; every permitted pin must be unique and exact. */
  function exactUrl(value, base, pathname, required, optional = {}, hash = '') {
    const url = new URL(value);
    if (url.origin !== base.origin || url.username || url.password || url.pathname !== pathname || url.hash !== hash) return null;
    const allowed = { ...required, ...optional };
    if (Object.entries(required).some(([key, expected]) => url.searchParams.getAll(key).length !== 1
        || url.searchParams.get(key) !== String(expected))) return null;
    for (const [key, actual] of url.searchParams) {
      if (!Object.hasOwn(allowed, key) || url.searchParams.getAll(key).length !== 1
          || actual !== String(allowed[key])) return null;
    }
    return url;
  }

  function nativeDiscoveryUrl(error, base, prefix, path, scope) {
    if (typeof error.stack !== 'string' || error.name !== `Fetch API cannot load ${base.protocol.slice(0, -1)}`) return null;
    const lines = error.stack.split('\n'), lead = 'Fetch API cannot load ', tail = ' due to access control checks.';
    if (!lines[0].startsWith(lead) || !lines[0].endsWith(tail)) return null;
    const rawUrl = lines[0].slice(lead.length, -tail.length);
    const url = exactUrl(rawUrl, base, path, scope);
    if (!url || url.href !== rawUrl || error.message !== `/${url.host}${url.pathname}${url.search}${tail}`) return null;
    const frames = [
      ['portfolioAwareFetch', '/js/portfolio/taxonomy-portfolio-async.js'],
      ['getResponse', '/js/api/portfolio-api.js'],
      ['getJson', '/js/api/portfolio-api.js'],
      ['synchronizeCurrentProjectJobs', '/js/portfolio/portfolio-analysis-job-synchronizer.js']
    ];
    if (lines.length !== frames.length + 1 || !frames.every(([name, file], index) => {
      const start = `    at ${name} (${base.origin}${prefix}${file}:`, line = lines[index + 1];
      return line.startsWith(start) && /^\d+:\d+\)$/.test(line.slice(start.length));
    })) return null;
    return url;
  }

  function completedJsonBody(read) {
    return (sequence(read.id) || nonempty(read.id)) && read.method === 'GET' && read.status === 200
      && read.finished === true && read.jsonParsed === true && !read.failed && read.redirected === false
      && sequence(read.sequence) && sequence(read.finishedSequence) && sequence(read.parsedSequence)
      && read.sequence < read.finishedSequence && read.finishedSequence < read.parsedSequence
      && (record(read.payload) || Array.isArray(read.payload));
  }

  function completedJson(read) {
    return completedJsonBody(read) && record(read.payload);
  }

  function sourceJobMatches(data, declaration, version) {
    if (data.id !== declaration.jobId || !sameId(data.projectId, declaration.projectId)
        || data.workspaceId !== declaration.workspaceId || data.status !== declaration.jobStatus || !Array.isArray(data.items)) return false;
    const items = data.items.filter(item => record(item) && sameId(item.requirementId, declaration.requirementId));
    return items.length === 1 && items[0].requirementKey === declaration.requirementKey
      && items[0].status === declaration.itemStatus && items[0].snapshotId === declaration.snapshotId
      && sameId(items[0].requirementVersionId, version);
  }

  function snapshotMatches(snapshot, declaration) {
    const data = snapshot?.data, source = snapshot?.source;
    if (!record(data) || !record(source) || !record(data.exportProvenance)
        || !positiveId(data.exportProvenance.requirementVersionId)
        || !sameId(data.projectId, declaration.projectId) || !sameId(data.requirementId, declaration.requirementId)
        || data.requirementKey !== declaration.requirementKey || data.requirementText !== declaration.requirementText
        || data.snapshotId !== declaration.snapshotId || data.snapshotStatus !== declaration.itemStatus
        || data.workspaceId !== declaration.workspaceId || data.branchName !== declaration.branch
        || data.exportProvenance.repositoryId !== declaration.repositoryId) return false;
    return source.kind === 'snapshot' && sameId(source.projectId, declaration.projectId)
      && sameId(source.requirementId, declaration.requirementId)
      && sameId(source.requirementVersionId, data.exportProvenance.requirementVersionId)
      && source.snapshotId === declaration.snapshotId && source.repositoryId === declaration.repositoryId
      && source.workspaceId === declaration.workspaceId && source.workspaceScopeKey === declaration.workspaceId
      && source.branch === declaration.branch && snapshot.rendered === true
      && sequence(snapshot.nodeCount) && snapshot.requirementText === declaration.requirementText
      && snapshot.workflowState === `Saved analysis ${declaration.snapshotId}`;
  }

  /**
   * This proof concerns one declared departure from a completed portfolio job to
   * its immutable snapshot. The job-list GET is unnecessary after that departure,
   * but an actual failed request, an unadopted body, or incomplete evidence must
   * preserve the original audit blocker. This function never requests new data.
   */
  return function classifyPortfolioSnapshotPollCancellation(input) {
    try {
      const { browserName, baseUrl, error, navigation, events, reads, observation } = input;
      if (browserName !== 'webkit' || error?.kind !== 'page-error' || navigation?.kind !== 'portfolio-snapshot'
          || navigation.completed !== true || navigation.locale !== 'en' || !Array.isArray(events) || !Array.isArray(reads)) return null;
      const declaration = navigation.declaration;
      if (!record(declaration) || declaration.locale !== 'en' || !positiveId(declaration.projectId)
          || !positiveId(declaration.requirementId) || !identifier(declaration.jobId) || !identifier(declaration.snapshotId)
          || !['repositoryId', 'workspaceId', 'branch', 'requirementKey', 'requirementText'].every(key => nonempty(declaration[key]))
          || !terminalStatuses.has(declaration.jobStatus) || !terminalStatuses.has(declaration.itemStatus)) return null;
      const { oldDocument, newDocument, startSequence, endSequence, sourceObservation } = navigation;
      if (!Number.isFinite(oldDocument?.epoch) || !Number.isFinite(newDocument?.epoch)
          || oldDocument.epoch === newDocument.epoch || error.documentEpoch !== oldDocument.epoch
          || sourceObservation?.documentEpoch !== oldDocument.epoch || observation?.documentEpoch !== newDocument.epoch
          || !sequence(startSequence) || !sequence(endSequence) || !sequence(error.sequence)
          || !(startSequence < error.sequence && error.sequence < endSequence)
          || !snapshotMatches(observation.snapshot, declaration)) return null;

      const base = new URL(baseUrl);
      if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password || base.search || base.hash) return null;
      const prefix = base.pathname.replace(/\/$/, '');
      const scope = { repositoryId: declaration.repositoryId, workspaceId: declaration.workspaceId,
        workspaceScopeKey: declaration.workspaceId, branch: declaration.branch };
      const sourcePins = { projectId: declaration.projectId, repositoryId: declaration.repositoryId,
        workspaceId: declaration.workspaceId, branch: declaration.branch, lang: declaration.locale };
      const version = observation.snapshot.data.exportProvenance.requirementVersionId;
      const destinationPins = { ...sourcePins, architectureSource: 'snapshot', snapshotId: declaration.snapshotId };
      const destinationDerived = { workspaceScopeKey: declaration.workspaceId,
        requirementId: declaration.requirementId, requirementVersionId: version };
      const sourcePage = value => exactUrl(value, base, `${prefix}/projects`, sourcePins, { workspaceScopeKey: declaration.workspaceId });
      const destinationPage = value => exactUrl(value, base, `${prefix}/`, destinationPins, destinationDerived, '#architecture');
      const oldUrl = sourcePage(oldDocument.url), newUrl = destinationPage(newDocument.url);
      if (!oldUrl || !newUrl || sourceObservation.url !== oldUrl.href || error.pageUrl !== oldUrl.href
          || !destinationPage(observation.url) || !isDeepStrictEqual(sourceObservation.scope, scope)
          || sourceObservation.projectId !== String(declaration.projectId)) return null;

      const listPath = `${prefix}/api/projects/${declaration.projectId}/analysis-jobs`;
      const jobPath = `${listPath}/${declaration.jobId}`;
      const snapshotPath = `${prefix}/api/projects/${declaration.projectId}/architecture-workbench/${declaration.snapshotId}`;
      const endpoint = nativeDiscoveryUrl(error, base, prefix, listPath, scope);
      const displayedJob = sourceObservation.job;
      if (!endpoint || displayedJob?.url !== base.origin + jobPath || displayedJob.warnings !== 0
          || displayedJob.heading !== `${declaration.jobId.slice(0, 12)} · ${statusLabel(declaration.jobStatus)}`
          || displayedJob.item?.key !== declaration.requirementKey || displayedJob.item.status !== statusLabel(declaration.itemStatus)
          || displayedJob.item.snapshot !== declaration.snapshotId.slice(0, 12)) return null;

      const beforeUnload = events.find(event => event.type === 'beforeunload' && event.documentEpoch === oldDocument.epoch
        && event.url === oldUrl.href && sequence(event.sequence) && startSequence < event.sequence && event.sequence < error.sequence);
      const pageHide = events.find(event => event.type === 'pagehide' && event.documentEpoch === oldDocument.epoch
        && event.url === oldUrl.href && sequence(event.sequence) && error.sequence < event.sequence && event.sequence < endSequence);
      const documentStart = pageHide && events.find(event => event.type === 'document-start'
        && event.documentEpoch === newDocument.epoch && sequence(event.sequence)
        && pageHide.sequence < event.sequence && event.sequence < endSequence && destinationPage(event.url));
      if (!beforeUnload || !pageHide || !documentStart || events.some(event =>
        ['error', 'unhandledrejection', 'securitypolicyviolation'].includes(event.type)
        && [oldDocument.epoch, newDocument.epoch].includes(event.documentEpoch))) return null;

      // Veto failures throughout either affected document. An original GET still
      // open at the first reconciliation can report its real failure later.
      if (reads.some(read => {
        const url = new URL(read.url);
        const relevant = url.origin === base.origin && (read.documentEpoch === oldDocument.epoch
          && [listPath, jobPath].includes(url.pathname)
          || read.documentEpoch === newDocument.epoch && url.pathname === snapshotPath);
        return relevant && (read.failed || read.status >= 400 || read.method === 'GET' && !completedJsonBody(read));
      })) return null;

      const sourceRequest = reads.find(read => completedJson(read) && read.documentEpoch === oldDocument.epoch
        && read.parsedSequence < startSequence && sourcePage(read.pageUrl)
        && exactUrl(read.url, base, jobPath, scope)
        && (read.workspaceId == null || read.workspaceId === declaration.workspaceId)
        && sourceJobMatches(read.payload, declaration, version));
      if (!sourceRequest) return null;
      const request = reads.find(read => completedJson(read) && read.documentEpoch === newDocument.epoch
        && read.sequence > startSequence && read.sequence > documentStart.sequence && destinationPage(read.pageUrl)
        && exactUrl(read.url, base, snapshotPath, scope) && read.workspaceId === declaration.workspaceId
        && isDeepStrictEqual(read.payload, observation.snapshot.data));
      if (!request) return null;
      return { reason: 'webkit-provisional-navigation-poll-cancelled', kind: navigation.kind, locale: declaration.locale,
        endpoint: endpoint.pathname + endpoint.search, sourceRequestId: sourceRequest.id, requestId: request.id,
        oldDocument, newDocument, declaration, rawError: error,
        lifecycle: { beforeUnload, pageHide, documentStart, startSequence, endSequence },
        sourceRequest, request, sourceRender: sourceObservation, render: observation.snapshot };
    } catch {
      // Malformed or incomplete observations can never remove an audit blocker.
    }
    return null;
  };
})();
