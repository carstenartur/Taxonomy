import { isDeepStrictEqual } from 'node:util';

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

function completedJson(read) {
  return (sequence(read.id) || nonempty(read.id)) && read.method === 'GET' && read.status === 200
    && read.finished === true && read.jsonParsed === true && !read.failed && read.redirected === false
    && sequence(read.sequence) && sequence(read.finishedSequence) && sequence(read.parsedSequence)
    && read.sequence < read.finishedSequence && read.finishedSequence < read.parsedSequence && record(read.payload);
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
export function classifyPortfolioSnapshotPollCancellation(input) {
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

    // Veto failures throughout either affected document. A successful body later
    // in that document must not erase an earlier real discovery or snapshot error.
    if (reads.some(read => {
      if (!read.failed && !(read.status >= 400)) return false;
      const url = new URL(read.url);
      return url.origin === base.origin && (read.documentEpoch === oldDocument.epoch
        && [listPath, jobPath].includes(url.pathname)
        || read.documentEpoch === newDocument.epoch && url.pathname === snapshotPath);
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
}
