const sourceKeys = [
  'architectureSource', 'repositoryId', 'workspaceScopeKey', 'workspaceId', 'branch',
  'sourceProjectId', 'sourceRequirementId', 'sourceSnapshotId', 'sourceWorkspaceId', 'sourceRepositoryId',
  'sourceBranch', 'sourceWorkspaceScopeKey', 'sourceRequirementVersionId', 'lang', 'layout', 'viewId', 'revision'
];

const nonempty = value => typeof value === 'string' && value.trim() === value && value.length > 0;
const sequenced = value => Number.isSafeInteger(value) && value > 0;
const epoch = value => Number.isFinite(value) && value > 0;

function exactRoute(value, base, pathname) {
  const url = new URL(value);
  return url.href === value && url.origin === base.origin && !url.username && !url.password
    && url.pathname === pathname ? url : null;
}

function exactQuery(url, keys) {
  return url.searchParams.size === keys.length && keys.every(key =>
    url.searchParams.getAll(key).length === 1 && nonempty(url.searchParams.get(key)));
}

function scopedRoute(value, base, pathname, pins) {
  const url = exactRoute(value, base, pathname);
  return url && !url.href.includes('#') && exactQuery(url, Object.keys(pins))
    && Object.entries(pins).every(([key, expected]) => url.searchParams.get(key) === expected);
}

function nativeStartupError(error, base, prefix) {
  const path = prefix + '/api/status/startup';
  const suffix = ' due to access control checks.';
  if (error.name !== `Fetch API cannot load ${base.protocol.slice(0, -1)}`
      || error.message !== `/${base.host}${path}${suffix}`) return false;
  const [heading, ...frames] = error.stack.split('\n');
  const expected = [
    `    at attempt (${base.origin}${prefix}/js/api/taxonomy-api-client.js:`,
    `    at unknown (${base.origin}${prefix}/js/core/taxonomy-browse.js:`
  ];
  return heading === `Fetch API cannot load ${base.origin}${path}${suffix}` && frames.length === expected.length
    && expected.every((line, index) => frames[index].startsWith(line) && /^\d+:\d+\)$/.test(frames[index].slice(line.length)));
}

function completedRead(read) {
  return read.method === 'GET' && read.status === 200 && read.finished === true && read.jsonParsed === true
    && read.mediaType === 'application/json'
    && read.failed == null && read.redirected === false && read.payload !== null && typeof read.payload === 'object'
    && [read.id, read.sequence, read.responseSequence, read.finishedSequence, read.parsedSequence].every(sequenced)
    && read.sequence < read.responseSequence && read.responseSequence < read.finishedSequence
    && read.finishedSequence < read.parsedSequence;
}

function readyStartup(data) {
  return data.initialized === true && data.phase === 'READY' && data.status === 'ready';
}

function adoptedStartup(data, render) {
  const memory = data.memory;
  const { ready, threads, text } = render;
  if (!readyStartup(data) || !memory
      || ![memory.heapUsagePercent, memory.heapUsedMB, memory.heapMaxMB, memory.threadCount].every(Number.isFinite)
      || ![ready, threads].every(value => nonempty(value) && !value.startsWith('startup.ui.'))) return false;
  const pct = memory.heapUsagePercent || 0;
  const icon = pct > 85 ? '🔴' : pct > 70 ? '🟡' : '🟢';
  return text === `${icon} ${ready} | Heap: ${pct}% (${memory.heapUsedMB || '?'}/${memory.heapMaxMB || '?'} MB)`
    + ` | ${threads}: ${memory.threadCount || '?'}`;
}

function adoptedProfile(profiles, render) {
  if (!Array.isArray(profiles) || !profiles.every(profile => nonempty(profile?.id) && nonempty(profile.version))
      || !Array.isArray(render?.options) || render.refreshEnabled !== true || render.connectionEnabled !== true
      || render.profileEnabled !== true || render.createEnabled !== true || render.errorHidden !== true) return null;
  const identities = profiles.map(profile => `${profile.id}@${profile.version}`);
  if (new Set(identities).size !== identities.length) return null;
  const value = 'archimate-3.1@1';
  const profile = profiles.find(item => item.id === 'archimate-3.1' && item.version === '1');
  const options = render.options.filter(option => option.value === value);
  return nonempty(profile?.title) && options.length === 1 && options[0].text === `${profile.title} · ${value}` ? profile : null;
}

/** Classify the initial integrations departure only when both original renders are attributable. */
export function classifyIntegrationNavigationCancellation(input) {
  try {
    const { browserName, baseUrl, error, navigation, events, reads, observation } = input;
    if (browserName !== 'webkit' || error?.kind !== 'page-error' || error.onlyMainFrame !== true
        || navigation?.kind !== 'integrations-entry'
        || navigation.locale !== 'en' || navigation.completed !== true) return null;
    const { oldDocument, newDocument, startSequence, endSequence, declaration, sourceObservation } = navigation;
    const base = new URL(baseUrl), prefix = base.pathname.replace(/\/$/, '');
    if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password || base.search || base.hash) return null;
    const oldUrl = exactRoute(oldDocument.url, base, prefix + '/');
    if (!oldUrl || oldUrl.hash !== '#architecture' || !exactQuery(oldUrl, sourceKeys)
        || oldUrl.searchParams.get('architectureSource') !== 'working') return null;
    const workspaceId = oldUrl.searchParams.get('workspaceId');
    const historicalWorkspace = oldUrl.searchParams.get('sourceWorkspaceId');
    if (workspaceId === '__shared__' || workspaceId !== oldUrl.searchParams.get('workspaceScopeKey')
        || historicalWorkspace === workspaceId || historicalWorkspace !== oldUrl.searchParams.get('sourceWorkspaceScopeKey')) return null;
    const pins = { repositoryId: oldUrl.searchParams.get('repositoryId'), branch: oldUrl.searchParams.get('branch'), workspaceId };
    const oldEpoch = oldDocument.epoch, newEpoch = newDocument.epoch;
    if (!scopedRoute(newDocument.url, base, prefix + '/integrations', { ...pins, lang: 'en' })
        || declaration?.sourceUrl !== oldDocument.url || declaration?.destinationUrl !== newDocument.url
        || !epoch(oldEpoch) || !epoch(newEpoch) || oldEpoch === newEpoch
        || error.pageUrl !== oldDocument.url || error.documentEpoch !== oldEpoch
        || sourceObservation?.url !== oldDocument.url || sourceObservation.documentEpoch !== oldEpoch
        || sourceObservation.workingArchitecture !== true
        || observation?.url !== newDocument.url || observation.documentEpoch !== newEpoch
        || ![sourceObservation.sequence, startSequence, error.sequence, endSequence, observation.sequence].every(sequenced)
        || !(sourceObservation.sequence < startSequence && startSequence < error.sequence
          && error.sequence < endSequence && endSequence < observation.sequence)
        || !nativeStartupError(error, base, prefix)) return null;

    const windowEvents = events.filter(event => startSequence < event.sequence && event.sequence < endSequence);
    const departures = windowEvents.filter(event => event.documentEpoch === oldEpoch
      && ['beforeunload', 'pagehide', 'poll-page-error'].includes(event.type));
    const [beforeUnload, reportedError, pageHide] = departures;
    const documentStarts = windowEvents.filter(event => event.type === 'document-start');
    const [documentStart] = documentStarts;
    if (departures.length !== 3 || documentStarts.length !== 1 || beforeUnload.type !== 'beforeunload' || reportedError.type !== 'poll-page-error'
        || reportedError.sequence !== error.sequence || reportedError.pageUrl !== oldDocument.url || pageHide.type !== 'pagehide'
        || beforeUnload.url !== oldDocument.url || pageHide.url !== oldDocument.url
        || documentStart?.url !== newDocument.url || documentStart.documentEpoch !== newEpoch
        || ![beforeUnload.sequence, pageHide.sequence, documentStart.sequence].every(sequenced)
        || !(beforeUnload.sequence < error.sequence && error.sequence < pageHide.sequence && pageHide.sequence < documentStart.sequence)
        || events.some(event => [oldEpoch, newEpoch].includes(event.documentEpoch)
          && ['error', 'unhandledrejection', 'securitypolicyviolation'].includes(event.type))) return null;

    const startupPath = prefix + '/api/status/startup', profilesPath = prefix + '/api/integrations/profiles';
    const isEndpoint = (read, pathname) => {
      const url = new URL(read.url);
      return url.origin === base.origin && url.pathname === pathname;
    };
    const startupReads = reads.filter(read => read.documentEpoch === oldEpoch && isEndpoint(read, startupPath));
    const profilesRequests = reads.filter(read => isEndpoint(read, profilesPath));
    // The network event can precede the destination binding even after the URL changed.
    // Such a request cannot prove adoption, and a later GET cannot hide it.
    if (profilesRequests.some(read => read.sequence < observation.sequence
        && (read.sequence > pageHide.sequence || (read.sequence > startSequence && read.pageUrl === newDocument.url))
        && (read.sequence <= documentStart.sequence || read.documentEpoch !== newEpoch))) return null;
    const profileReads = profilesRequests.filter(read => read.documentEpoch === newEpoch);
    // Use live read state so a genuine failure is still visible if its callback arrives later.
    if (![...startupReads, ...profileReads].every(completedRead)
        || startupReads.some(read => read.payload.phase === 'FAILED' || read.payload.status === 'error')) return null;

    const startup = sourceObservation.startup;
    const startupRender = events.findLast(event => event.type === 'poll-render' && event.documentEpoch === oldEpoch
      && event.id === 'systemStatusBar' && event.sequence < sourceObservation.sequence);
    if (!startupRender || !exactRoute(startupRender.url, base, prefix + '/')
        || !sequenced(startup?.writeCount) || startupRender.writes !== startup.writeCount
        || startupRender.text !== startup.text) return null;
    // The global read can have shared-preview pins in the same document, prior to private adoption.
    const sourceRequest = startupReads.findLast(read => read.mainFrame === true && read.url === base.origin + startupPath
      && read.workspaceHeaderPresent === false
      && read.responseSequence < startupRender.sequence && read.parsedSequence < sourceObservation.sequence
      && exactRoute(read.pageUrl, base, prefix + '/') && adoptedStartup(read.payload, startup));
    // Earlier initialization can progress to ready. Later contradictory state invalidates the witness.
    if (!sourceRequest || startupReads.some(read => read.sequence > sourceRequest.sequence && !readyStartup(read.payload))) return null;

    const originals = profileReads.filter(read => read.sequence < observation.sequence);
    if (originals.length !== 1) return null;
    const [request] = originals;
    if (request.mainFrame !== true || request.pageUrl !== newDocument.url || request.workspaceId !== workspaceId
        || request.sequence <= documentStart.sequence || request.parsedSequence >= observation.sequence
        || !scopedRoute(request.url, base, profilesPath, pins)) return null;
    const profile = adoptedProfile(request.payload, observation.profiles);
    if (!profile) return null;

    return { reason: 'webkit-provisional-navigation-poll-cancelled', kind: navigation.kind, locale: navigation.locale,
      endpoint: startupPath, sourceRequestId: sourceRequest.id, requestId: request.id,
      oldDocument, newDocument, declaration, rawError: error, profile,
      lifecycle: { beforeUnload, pageHide, documentStart, startSequence, endSequence },
      sourceRequest, sourceRender: sourceObservation, startupRender, request,
      render: { ...observation.profiles, documentEpoch: observation.documentEpoch, url: observation.url, sequence: observation.sequence } };
  } catch {
    // The audit blocker remains when the original evidence is malformed or incomplete.
    return null;
  }
}
