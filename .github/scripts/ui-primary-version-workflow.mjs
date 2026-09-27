import { randomUUID } from 'node:crypto';

/** Allocate the ref before setup so partial failures cannot lose its cleanup identity. */
export async function usingComparisonBranch(run, cleanup) {
  const branch = 'qa-compare-' + randomUUID();
  let failure, result;
  try { result = await run(branch); } catch (error) { failure = error; }
  try { await cleanup(branch); } catch (error) {
    if (failure) throw new AggregateError([failure, error], 'Comparison and branch cleanup failed');
    throw error;
  }
  if (failure) throw failure;
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
    const branches = data.branches || data;
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
  const previousBranch = await page.locator('#versionsBranchSelect').inputValue();
  await usingComparisonBranch(async branch => {
    const fixture = comparisonDocuments('', branch);
    const versions = await page.evaluate(async ({ branch, fixture }) => {
      const api = window.TaxonomyApiClient;
      const head = await api.getJson('/api/dsl/git/head?branch=draft');
      const original = head.dslText;
      if (!original) throw new Error('A real DSL baseline is required');
      if (original.includes(fixture.sourceId) || original.includes(fixture.targetId)) throw new Error('Comparison fixture identity already exists');
      const { before, after } = fixture;
      async function commit(text, message) {
        const response = await api.request('/api/dsl/commit?branch=' + encodeURIComponent(branch)
          + '&message=' + encodeURIComponent(message), {
          method: 'POST', headers: { 'Content-Type': 'text/plain;charset=UTF-8' }, body: text
        });
        const result = await response.json();
        if (!result.valid || !result.commitId) throw new Error('QA version commit failed');
        return result.commitId;
      }
      const left = await commit(original + fixture.left, 'QA isolated comparison baseline');
      const right = await commit(original + fixture.right, 'QA isolated relation status change');
      const diff = await api.getJson('/api/dsl/diff/' + left + '/' + right);
      if (diff.changedRelations !== 1 || diff.totalChanges !== 1) throw new Error('Expected exactly one changed relation');
      const unchanged = await api.getJson('/api/dsl/git/head?branch=draft');
      if (unchanged.dslText !== original) throw new Error('Comparison commits changed the active draft');
      return { branch, left, right, before, after, description: diff.semanticChanges[0].description };
    }, { branch, fixture });
    await page.locator('#mainNavTabs [data-page="versions"]').click();
    await page.evaluate(() => window.TaxonomyVersions.loadTimeline());
    await page.locator(`#versionsBranchSelect option[value="${versions.branch}"]`).waitFor({ state: 'attached' });
    await page.locator('#versionsBranchSelect').selectOption(versions.branch);
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
    const preview = page.waitForRequest(request => request.url().includes('/api/dsl/diff/' + versions.right + '/' + versions.left));
    await page.locator(`[data-action="restore"][data-commit="${versions.left}"]`).click();
    await preview;
    await page.locator('#versionsConfirmModal .restore-preview').waitFor();
    evidence.assert((await page.locator('#versionsConfirmModal .restore-preview').innerText()).includes('1'),
      'The relation-only restore preview must report its change');
    evidence.assert(await page.locator('#versionsConfirmBtn').isEnabled(), 'Valid preview must enable confirmation');
    await page.locator('#versionsConfirmModal .btn-close').click();
    await page.locator('#versionsConfirmModal').waitFor({ state: 'detached' });
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
    } finally {
      await page.unroute(diffRoute, fail);
    }
  }, async branch => {
    try {
      await page.evaluate(removeComparisonBranch, branch);
    } finally {
      await page.locator('#versionsBranchSelect').selectOption(previousBranch || 'draft');
      await page.evaluate(() => window.TaxonomyVersions.loadTimeline());
    }
  });
  evidence.passed('Real relation-only version comparison, correct restore direction, visible failures and QA branch cleanup');
}
