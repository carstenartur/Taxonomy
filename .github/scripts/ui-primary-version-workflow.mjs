import { randomUUID } from 'node:crypto';
import { navigateToPage } from './ui-role-fixtures.mjs';

const driverFailureReasons = new Map([
  ['Invalid isolated comparison fixture', 'invalid fixture'],
  ['Comparison fixture identity already exists', 'fixture identity collision'],
  ['A real DSL baseline is required', 'missing DSL baseline'],
  ['Invalid baseline commit result', 'invalid baseline commit'],
  ['Invalid relation change commit result', 'invalid relation change commit'],
  ['Expected exactly one changed relation', 'unexpected relation diff'],
  ['Comparison commits changed the active draft', 'active draft changed'],
  ['No branch selected on the loaded Versions page', 'no selected branch'],
  ['The relation-only restore preview must report its change', 'incomplete restore preview'],
  ['Valid preview must enable confirmation', 'valid restore disabled'],
  ['Failed comparisons must not be blank', 'blank comparison error'],
  ['Failed previews must not enable restore', 'invalid restore enabled'],
  ['QA branch inventory is unavailable', 'inventory unavailable'],
  ['QA branch cleanup was not confirmed', 'ref still present'],
]);

// Only known categories go into the reported aggregate: browser messages can include response bodies and URLs.
function failureReason(error) {
  const message = typeof error?.message === 'string' ? error.message.slice(0, 160)
    .split(/\r?\n/, 1)[0].replace(/^(?:page|locator)\.\w+: /, '').replace(/^(?:ApiError|Error): /, '') : '';
  if (driverFailureReasons.has(message)) return driverFailureReasons.get(message);
  if (message.startsWith('Real version comparison omitted ')) return 'incomplete comparison evidence';
  const http = /^HTTP ([1-5]\d{2})\b/.exec(message);
  if (http) return 'HTTP ' + http[1];
  if (error?.name === 'TimeoutError' || /^(?:Timeout \d+ms exceeded|Request timed out after \d+ ms)\b/.test(message)) return 'timeout';
  if (message === 'Network request failed') return 'network failure';
  if (message === 'Request was cancelled' || message === 'Request context was superseded') return 'cancelled';
  if (error?.name === 'AssertionError') return 'assertion';
  return 'other failure';
}

/** Allocate the ref before setup so partial failures cannot lose its cleanup identity. */
export async function usingComparisonBranch(run, cleanup, restoreSelection) {
  const branch = 'qa-compare-' + randomUUID();
  const failures = [];
  let result;
  try { result = await run(branch); } catch (error) { failures.push(['comparison', error]); }
  try { await cleanup(branch); } catch (error) { failures.push(['branch cleanup', error]); }
  if (restoreSelection) {
    try { await restoreSelection(); } catch (error) { failures.push(['selection restore', error]); }
  }
  if (failures.length === 1) throw failures[0][1];
  if (failures.length > 1) {
    throw new AggregateError(failures.map(([, error]) => error),
      failures.map(([phase, error]) => phase + ': ' + failureReason(error)).join('; '));
  }
  return result;
}

/** A relation-only comparison must not depend on catalogue seed/template relationships. */
export function comparisonDocuments(original, branch) {
  if (typeof original !== 'string' || !/^qa-compare-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(branch)) {
    throw new Error('Invalid isolated comparison fixture');
  }
  const prefix = 'QA-COMPARE-' + branch.slice('qa-compare-'.length);
  const sourceId = prefix + '-PROCESS', targetId = prefix + '-RECORD';
  if (original.includes(sourceId) || original.includes(targetId)) throw new Error('Comparison fixture identity already exists');
  const elements = `\nelement ${sourceId} type Process {\n  title: "QA comparison process";\n  taxonomy: "BP";\n}\n`
    + `element ${targetId} type InformationProduct {\n  title: "QA comparison record";\n  taxonomy: "IP";\n}\n`;
  const relation = status => `relation ${sourceId} CONSUMES ${targetId} {\n  status: ${status};\n}\n`;
  return { sourceId, targetId, left: original + elements + relation('accepted'),
    right: original + elements + relation('proposed'), before: 'accepted', after: 'proposed' };
}

/** Runs both directly in contract tests and inside the real authenticated browser. */
export async function removeComparisonBranch(name, api = window.TaxonomyApiClient) {
  const inventory = async () => {
    const data = await api.getJson('/api/git/branches', { retries: 0 });
    const branches = data?.branches || data;
    if (!Array.isArray(branches)) throw new Error('QA branch inventory is unavailable');
    return branches;
  };
  // A setup failure before the first commit has no ref to delete; do not manufacture an HTTP error.
  if ((await inventory()).includes(name)) {
    await api.request('/api/dsl/branch?name=' + encodeURIComponent(name), { method: 'DELETE' });
  }
  if ((await inventory()).includes(name)) throw new Error('QA branch cleanup was not confirmed');
}

/** Full application acceptance. The happy path compares real Git versions; no LLM calls. */
export async function runVersionComparisonWorkflow({ page, role, evidence }) {
  if (role !== 'ADMIN') return;
  // Returning from the graph fixture reloads the application. The API client
  // invalidates outstanding Git requests while the initial selection is published.
  await page.waitForFunction(() => window.TaxonomyAnalysisSession?.state?.().ready === true);
  let previousBranch;
  await usingComparisonBranch(async branch => {
    const fixture = comparisonDocuments('', branch);
    const versions = await page.evaluate(async ({ branch, fixture }) => {
      const api = window.TaxonomyApiClient;
      // Preserve the failed phase without logging a DSL, URL or response body.
      async function requestStep(phase, operation) {
        try { return await operation(); } catch (error) {
          const status = Number(error?.status ?? error?.statusCode ?? error?.httpStatus);
          throw new Error('Version comparison API ' + phase + ' failed'
            + (Number.isInteger(status) && status >= 400 && status <= 599 ? ' (HTTP ' + status + ')'
              : ['ABORTED', 'TIMEOUT', 'NETWORK_ERROR'].includes(error?.code) ? ' (' + error.code + ')' : ''));
        }
      }
      const head = await requestStep('draft head read',
        () => api.getJson('/api/dsl/git/head?branch=draft'));
      const original = head?.dslText;
      if (!original) throw new Error('A real DSL baseline is required');
      if (original.includes(fixture.sourceId) || original.includes(fixture.targetId)) throw new Error('Comparison fixture identity already exists');
      const { before, after } = fixture;
      async function commit(text, message) {
        const response = await requestStep('isolated comparison commit',
          () => api.request('/api/dsl/commit?branch=' + encodeURIComponent(branch)
            + '&message=' + encodeURIComponent(message), {
            method: 'POST', headers: { 'Content-Type': 'text/plain;charset=UTF-8' }, body: text
          }));
        const result = await response.json();
        return result?.valid && result.commitId;
      }
      const left = await commit(original + fixture.left, 'QA isolated comparison baseline');
      if (!left) throw new Error('Invalid baseline commit result');
      const right = await commit(original + fixture.right, 'QA isolated relation status change');
      if (!right) throw new Error('Invalid relation change commit result');
      const diff = await requestStep('semantic diff',
        () => api.getJson('/api/dsl/diff/' + left + '/' + right));
      if (diff.changedRelations !== 1 || diff.totalChanges !== 1) throw new Error('Expected exactly one changed relation');
      const unchanged = await requestStep('draft isolation verification',
        () => api.getJson('/api/dsl/git/head?branch=draft'));
      if (unchanged.dslText !== original) throw new Error('Comparison commits changed the active draft');
      return { branch, left, right, before, after, description: diff.semanticChanges[0].description };
    }, { branch, fixture });
    await navigateToPage(page,'versions');
    await page.evaluate(() => window.TaxonomyVersions.loadTimeline());
    await page.locator(`#versionsBranchSelect option[value="${versions.branch}"]`).waitFor({ state: 'attached' });
    const select = page.locator('#versionsBranchSelect');
    const selected = await select.inputValue();
    if (!selected) throw new Error('No branch selected on the loaded Versions page');
    // selectOption may change the DOM before its promise rejects.
    if (selected !== branch) previousBranch = selected;
    await select.selectOption(versions.branch);
    const compare = page.locator(`[data-action="compare"][data-commit="${versions.left}"]`);
    await compare.click();
    const result = page.locator('#versionComparisonResults');
    await result.locator('[data-compare-section="relations"] .item-changed').waitFor();
    const text = await result.innerText();
    for (const expected of [versions.left.slice(0, 7), versions.right.slice(0, 7), versions.description]) {
      evidence.assert(text.includes(expected), `Real version comparison omitted ${expected}`);
    }
    await page.waitForFunction(() => {
      const modal = document.querySelector('#versionsModal.show');
      return modal && Number(getComputedStyle(modal).opacity) === 1
        && !modal.querySelector('.modal-dialog').getAnimations().length;
    });
    await evidence.saveRequiredViewportState('version-comparison-real-relation-change', '#versionsModal');
    await page.locator('#versionsModal .btn-close').click();
    await page.locator('#versionsModal').waitFor({ state: 'detached' });
    evidence.assert(await compare.evaluate(button => button === document.activeElement),
      'Closing the comparison should return focus to its original Git version');
    const preview = page.waitForRequest(request => request.url().includes('/api/dsl/diff/' + versions.right + '/' + versions.left));
    const restore = page.locator(`[data-action="restore"][data-commit="${versions.left}"]`);
    await restore.press('Enter');
    await preview;
    await page.locator('#versionsConfirmModal .restore-preview').waitFor();
    evidence.assert((await page.locator('#versionsConfirmModal .restore-preview').innerText()).includes('1'),
      'The relation-only restore preview must report its change');
    evidence.assert(await page.locator('#versionsConfirmBtn').isEnabled(), 'Valid preview must enable confirmation');
    await page.locator('#versionsConfirmModal .btn-close').click();
    await page.locator('#versionsConfirmModal').waitFor({ state: 'detached' });
    evidence.assert(await restore.evaluate(button => button === document.activeElement),
      'Cancelling the restore should return focus to its original Git version');
    // Inject a transport failure only after the genuine success path. No fallback may restore.
    const diffRoute = '**/api/dsl/diff/**';
    const fail = route => route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"QA comparison unavailable"}' });
    await page.route(diffRoute, fail);
    try {
      await compare.click();
      await page.locator('#versionsModal .text-danger').waitFor();
      evidence.assert((await page.locator('#versionsModal .modal-body').innerText()).trim().length > 0,
        'Failed comparisons must not be blank');
      await page.locator('#versionsModal .btn-close').click();
      await page.locator('#versionsModal').waitFor({ state: 'detached' });
      await page.locator(`[data-action="restore"][data-commit="${versions.left}"]`).click();
      await page.locator('#versionsConfirmModal [role="alert"]').waitFor();
      evidence.assert(await page.locator('#versionsConfirmBtn').isDisabled(), 'Failed previews must not enable restore');
      await page.locator('#versionsConfirmModal .btn-close').click();
      await page.locator('#versionsConfirmModal').waitFor({ state: 'detached' });
      evidence.assert(await restore.evaluate(button => button === document.activeElement),
        'Closing a failed restore preview should return focus to its original Git version');
    } finally {
      await page.unroute(diffRoute, fail);
    }
  }, branch => page.evaluate(removeComparisonBranch, branch), async () => {
    if (!previousBranch) return;
    await page.locator('#versionsBranchSelect').selectOption(previousBranch);
    await page.evaluate(() => window.TaxonomyVersions.loadTimeline());
  });
  evidence.passed('Real relation-only version comparison, correct restore direction, visible failures and QA branch cleanup');
}
