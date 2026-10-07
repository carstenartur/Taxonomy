import { expect } from '@playwright/test';

const projectIds = [739101, 739102];
const branchNames = ['qa-context-main', 'qa-context-review', 'qa-context-candidate'];
const preferenceKeys = ['taxonomy_language', 'taxonomy.portfolio.projectId', 'taxonomy.portfolio.analysisJobs.v2'];
const time = '2026-10-07T09:00:00Z';
const zeroEvidence = 'QA: bewusst geprüfte Abdeckung von 0 %.';
const conflictMessage = 'QA: Diese Anforderung wurde inzwischen geändert. Bitte den Entwurf prüfen und erneut versuchen.';

// These read fixtures follow PortfolioDtos, RepositoryState and ExportedPortfolioDsl.
// No project, requirement, analysis, branch or commit is created on the server.
function createPortfolioFixtures() {
  const requirement = (id, projectId, key, title) => ({
    id, projectId, requirementKey: key, title, status: 'DRAFT', priority: 50,
    criticality: 'HIGH', requirementType: 'FUNCTIONAL', reviewStatus: 'PROPOSED',
    ownerUsername: 'qa-fixture', currentVersionId: id * 10, currentAnalysisSnapshotId: null,
    createdAt: time, updatedAt: time,
    currentVersion: {
      id: id * 10, versionNumber: 4, text: title + '.\nDie Prüfung muss nachvollziehbar bleiben.',
      contentHash: 'a'.repeat(64), changeReason: 'Geprüfter Ausgangsstand', createdBy: 'qa-fixture', createdAt: time,
      source: { sourceArtifactId: 7391901, sourceVersionId: 7391902, sourceFragmentIds: [7391903],
        sectionReference: '4.2', pageNumber: 12, originalText: 'Originaltext der geprüften Anforderung.' }
    }
  });
  const requirements = [
    requirement(7391011, projectIds[0], 'QA-REQ-0', 'Pumpe bei Notabschaltung stoppen'),
    requirement(7391012, projectIds[0], 'QA-REQ-65', 'Status an die Leitstelle melden'),
    requirement(7391013, projectIds[0], 'QA-REQ-EMPTY', 'Prüfprotokoll bereitstellen'),
    requirement(7391014, projectIds[0], 'QA-REQ-UNKNOWN', 'Abdeckung fachlich nachprüfen'),
    requirement(7391021, projectIds[1], 'QA-REQ-B', 'Alarm in der Leitstelle bestätigen')
  ];
  const projects = projectIds.map((id, index) => ({
    id, projectKey: index ? 'QA-CONTEXT-B' : 'QA-CONTEXT-A',
    title: index ? 'Leitstelle Nord' : 'Pumpensteuerung im Notbetrieb',
    description: 'Lokale QA-Daten für Navigation und die Prüfung gespeicherter Anforderungen.',
    status: 'ACTIVE', ownerUsername: 'qa-fixture', workspaceId: 'qa-context-workspace',
    targetArchitecture: null, targetDate: null, budgetAmount: null, budgetCurrency: null,
    createdAt: time, updatedAt: time, requirementCount: index ? 1 : 4,
    solutionCount: index ? 0 : 1, openConflictCount: 0
  }));
  const solution = {
    id: 7391801, projectId: projectIds[0], status: 'SELECTED', actionStatus: 'REUSE', priority: 50,
    rationale: 'Geprüfte Zuordnung für den Matrix-Workflow.', createdBy: 'qa-fixture', createdAt: time, updatedAt: time,
    solution: {
      id: 7391802, solutionKey: 'QA-SOL-A', title: 'Steuerungsdienst', description: 'Lokale QA-Lösung',
      solutionType: 'APPLICATION', operatingModel: 'ON_PREMISES', lifecycleStatus: 'ACTIVE',
      maturityLevel: 3, ownerUsername: 'qa-fixture', responsibleOrganization: null,
      costAmount: null, costCurrency: null, riskNotes: null, leadTimeDays: null,
      extensionAttributes: {}, createdAt: time, updatedAt: time, taxonomyCoverage: []
    },
    requirements: [0, 1].map(index => ({
      id: 7391811 + index, requirementId: requirements[index].id,
      requirementKey: requirements[index].requirementKey, snapshotId: null,
      coveragePercent: index ? 65 : 0, role: 'USES', reviewStatus: 'CONFIRMED',
      evidence: index ? 'QA: teilweise bestätigte Abdeckung.' : zeroEvidence,
      updatedBy: 'qa-fixture', updatedAt: time
    })),
    productCandidates: []
  };
  const emptyMatrix = () => ({ rows: [], columns: [], values: {} });
  const state = { projectsAvailable: false, unknownCoverage: false, head: 'a'.repeat(40) };
  const projectRequirements = id => requirements.filter(item => item.projectId === id);
  const portfolio = id => {
    const items = projectRequirements(id);
    const hasSolution = id === projectIds[0];
    const values = { 'QA-REQ-0': 0, 'QA-REQ-65': 65 };
    // Explicit fault injection: null is an invalid/degraded response, not a normal
    // sparse Map<String, Integer> response produced by the current Java service.
    if (state.unknownCoverage) values['QA-REQ-UNKNOWN'] = null;
    return {
      project: projects.find(item => item.id === id), requirements: items,
      metrics: { totalRequirements: items.length, analyzedRequirements: 0, confirmedRequirements: 0,
        requirementsWithoutConfirmedSolution: hasSolution ? 2 : items.length,
        totalSolutions: hasSolution ? 1 : 0, solutionsByAction: hasSolution ? { REUSE: 1 } : {},
        totalProductCandidates: 0, selectedProducts: 0, openConflicts: 0, staleSnapshots: 0 },
      taxonomyNodes: [], solutions: hasSolution ? [solution] : [], conflicts: [],
      requirementTaxonomyMatrix: emptyMatrix(),
      requirementSolutionMatrix: hasSolution ? {
        rows: ['QA-SOL-A'], columns: items.map(item => item.requirementKey), values: { 'QA-SOL-A': values }
      } : emptyMatrix(),
      solutionProductMatrix: emptyMatrix()
    };
  };
  const repository = () => ({
    currentBranch: branchNames[0], headCommit: state.head, headTimestamp: time,
    headAuthor: 'qa-fixture', headMessage: 'QA read fixture', branches: [...branchNames],
    operationInProgress: false, operationKind: null, projectionCommit: state.head,
    projectionBranch: branchNames[0], projectionTimestamp: time, projectionStale: false,
    indexCommit: state.head, indexStale: false, totalCommits: 2, databaseBacked: false
  });
  const exported = () => ({
    workspaceId: 'qa-context-workspace', username: 'qa-fixture', activeBranch: branchNames[0],
    headCommit: state.head, dsl: 'project QA-CONTEXT-A\n# QA preview ' + state.head.slice(0, 8),
    projectCount: 2, requirementCount: 5, solutionCount: 1, productCount: 0, exportedAt: time
  });
  return { state, projects, requirements, projectRequirements, portfolio, repository, exported };
}

/**
 * Real-template acceptance for Portfolio navigation, recovery and matrix semantics.
 * The caller supplies an authenticated ADMIN page and a running application.
 * baseUrl may include the servlet context (for example http://localhost:8080/taxonomy).
 * Only scoped API responses are substituted; Bootstrap, scripts, DOM, form submission,
 * keyboard events and geometry are real. The one POST is intercepted and returns 409.
 */
export async function runPortfolioContextWorkflow({ page, baseUrl, evidence }) {
  const base = new URL(baseUrl.replace(/\/+$/, ''));
  const prefix = base.pathname.replace(/\/+$/, '');
  const applicationUrl = path => base.origin + prefix + path;
  const relativeUrl = path => prefix + path;
  const originalUrl = page.url();
  const originalLocale = await page.locator('html').getAttribute('lang') || 'en';
  const originalViewport = page.viewportSize();
  const originalPreferences = await page.evaluate(keys =>
    Object.fromEntries(keys.map(key => [key, localStorage.getItem(key)])), preferenceKeys);
  const fixtures = createPortfolioFixtures();
  const primaryRequirement = fixtures.requirements[0];
  const routes = [];
  const unexpectedTraffic = [];
  const submittedVersions = [];
  const fixtureReads = new Set();
  let gitReadGate;
  let failure;
  const cleanupUrl = applicationUrl('/__qa_portfolio_context_cleanup__');
  const json = value => ({ status: 200, contentType: 'application/json', body: JSON.stringify(value) });
  const route = async (pattern, handler) => {
    await page.route(pattern, handler);
    routes.push([pattern, handler]);
  };
  const restorePreferences = () => page.evaluate(values => {
    for (const [key, value] of Object.entries(values)) {
      if (value === null) localStorage.removeItem(key);
      else localStorage.setItem(key, value);
    }
  }, originalPreferences);
  const waitForPage = async (heading, expectedText, busy) => {
    await expect(page.locator(heading)).toContainText(expectedText);
    await expect(page.locator(busy)).toBeHidden();
    await expect(page.locator('html')).toHaveAttribute('lang', 'de');
  };
  const assertInsideViewport = async (locator, description) => {
    await expect(locator).toBeVisible();
    const rect = await locator.boundingBox();
    const viewport = page.viewportSize();
    evidence.assert(rect && rect.width >= 24 && rect.height >= 24
      && rect.x >= -1 && rect.y >= -1
      && rect.x + rect.width <= viewport.width + 1
      && rect.y + rect.height <= viewport.height + 1,
    `${description} must be fully visible inside ${viewport.width}×${viewport.height}: ${JSON.stringify(rect)}`);
  };
  const assertNoPageOverflow = async description => {
    const geometry = await page.evaluate(() => ({
      viewport: innerWidth, document: document.documentElement.scrollWidth, body: document.body.scrollWidth
    }));
    evidence.assert(Math.max(geometry.document, geometry.body) <= geometry.viewport + 2,
      `${description} must not require page-wide horizontal scrolling: ${JSON.stringify(geometry)}`);
  };
  const detailLink = requirement => page.locator('#requirementsTable tbody a').filter({ hasText: requirement.title });
  const assertProjectLinks = async id => {
    for (const [name, suffix] of [['Import', 'import'], ['Matrices', 'matrices'], ['Reports', 'reports'], ['Versioning', 'versioning']]) {
      await expect(page.locator(`#project${name}Link`)).toHaveAttribute('href',
        relativeUrl(`/projects/${id}/${suffix}?lang=de`));
    }
    const requirement = fixtures.projectRequirements(id)[0];
    await expect(detailLink(requirement)).toHaveAttribute('href',
      relativeUrl(`/projects/${id}/requirements/${requirement.id}?lang=de`));
    await expect(detailLink(requirement)).toHaveAccessibleName(new RegExp(requirement.requirementKey));
  };
  const showProject = async id => {
    await page.locator(`.project-select[data-project-id="${id}"]`).click();
    await waitForPage('#selectedProjectKey', fixtures.projects.find(item => item.id === id).projectKey, '#portfolioBusy');
    await assertProjectLinks(id);
  };
  const closeCellDetail = async () => {
    await expect(page.locator('#cellDetail')).toHaveClass(/(?:^|\s)show(?:\s|$)/);
    await page.keyboard.press('Escape');
    await expect(page.locator('#cellDetail')).toBeHidden();
  };
  const matrixCell = key => page.locator(`#solutionMatrix table .matrix-drilldown[data-column="${key}"]`);

  try {
    evidence.assert(new URL(originalUrl).origin === base.origin,
      'Portfolio workflow must start in the authenticated application origin');
    await page.evaluate(({ keys, projectId }) => {
      localStorage.setItem(keys[0], 'de');
      localStorage.setItem(keys[1], String(projectId));
      localStorage.setItem(keys[2], '[]');
    }, { keys: preferenceKeys, projectId: projectIds[0] });
    // Stop previous page timers before the fixture/write boundary is installed.
    await page.goto('about:blank');
    await route('**/*', async intercepted => {
      const request = intercepted.request();
      const url = new URL(request.url());
      const method = request.method();
      const inApplication = url.origin === base.origin && (!prefix || url.pathname.startsWith(prefix + '/'));
      const path = inApplication ? url.pathname.slice(prefix.length) : null;
      const versionPath = `/api/projects/${projectIds[0]}/requirements/${primaryRequirement.id}/versions`;
      const rejectUnexpected = async reason => {
        unexpectedTraffic.push(`${method} ${url.origin}${url.pathname}: ${reason}`);
        await intercepted.abort('blockedbyclient').catch(() => undefined);
      };

      if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
        if (method === 'POST' && path === versionPath && !url.search) {
          submittedVersions.push(request.postDataJSON());
          return intercepted.fulfill({ status: 409, contentType: 'application/problem+json',
            body: JSON.stringify({ type: 'about:blank', title: 'Conflict', status: 409,
              detail: conflictMessage, instance: relativeUrl(versionPath) }) });
        }
        return rejectUnexpected('only the intercepted QA version submission is permitted');
      }
      if (url.href === cleanupUrl) {
        // Same-origin cleanup document only: it stops timers before restoring storage.
        return intercepted.fulfill({ status: 200, contentType: 'text/html',
          body: '<!doctype html><html lang="en"><head><meta charset="utf-8"><title>QA cleanup</title></head><body></body></html>' });
      }
      const reservedProjectPath = /\/api\/projects\/(739101|739102)(?:\/|$)/.test(url.pathname);
      const sharedFixturePath = ['/api/projects', '/api/products', '/api/git/state', '/api/projects/git/export']
        .some(endpoint => url.pathname.endsWith(endpoint));
      if (!inApplication && (reservedProjectPath || sharedFixturePath)) {
        return rejectUnexpected('fixture request escaped the exact application context');
      }
      if (!inApplication || !path.startsWith('/api/')) return intercepted.fallback();

      let payload;
      if (path === '/api/projects') payload = fixtures.state.projectsAvailable ? fixtures.projects : [];
      else if (path === '/api/products') payload = [];
      else if (path === '/api/git/state') {
        if (gitReadGate && !gitReadGate.seen) {
          gitReadGate.seen = true;
          await gitReadGate.promise;
        }
        payload = fixtures.repository();
      } else if (path === '/api/projects/git/export') payload = fixtures.exported();
      else {
        const match = path.match(/^\/api\/projects\/(739101|739102)(.*)$/);
        if (!match) return intercepted.fallback(); // Real, read-only account and automation capability endpoints.
        const id = Number(match[1]);
        const suffix = match[2];
        if (!suffix) payload = fixtures.projects.find(item => item.id === id);
        else if (suffix === '/portfolio') payload = fixtures.portfolio(id);
        else if (suffix === '/requirements') payload = fixtures.projectRequirements(id);
        else if (suffix === '/analysis-jobs') payload = [];
        else {
          const requirementPath = suffix.match(/^\/requirements\/(\d+)(.*)$/);
          const item = requirementPath && fixtures.requirements.find(candidate =>
            candidate.id === Number(requirementPath[1]) && candidate.projectId === id);
          if (!item) return rejectUnexpected('unhandled reserved QA resource');
          const resource = requirementPath[2];
          if (!resource) payload = item;
          else if (resource === '/versions') payload = [item.currentVersion];
          else if (resource === '/snapshots' || resource === '/reformulations') payload = [];
          else if (resource === '/copilot/latest') {
            fixtureReads.add(path);
            return intercepted.fulfill({ status: 204 });
          } else return rejectUnexpected('unhandled reserved QA requirement resource');
        }
      }
      fixtureReads.add(path);
      return intercepted.fulfill(json(payload)).catch(error => {
        // Navigating away may cancel an already requested read, including the gated refresh.
        if (!page.isClosed() && !request.failure()) throw error;
      });
    });

    await page.setViewportSize({ width: 1366, height: 768 });
    await page.goto(applicationUrl('/projects?lang=de'), { waitUntil: 'domcontentloaded' });
    await expect(page.locator('#projectListEmpty')).toBeVisible();
    await expect(page.locator('#noProjectSelected')).toBeVisible();
    await expect(page.locator('#portfolioBusy')).toBeHidden();
    await expect(page.locator('#projectTools')).toBeHidden();
    fixtures.state.projectsAvailable = true;
    await page.reload({ waitUntil: 'domcontentloaded' });
    await waitForPage('#selectedProjectKey', 'QA-CONTEXT-A', '#portfolioBusy');

    for (const viewport of [{ width: 1366, height: 768 }, { width: 390, height: 844 }]) {
      await page.setViewportSize(viewport);
      await showProject(projectIds[0]);
      await assertNoPageOverflow('Portfolio at ' + viewport.width + 'px');
      const toggle = page.locator('#projectToolsToggle');
      await toggle.scrollIntoViewIfNeeded();
      await expect(toggle).toHaveText('Projektwerkzeuge');
      await toggle.focus();
      await page.keyboard.press('ArrowDown');
      await expect(page.locator('#projectImportLink')).toBeFocused();
      await expect(toggle).toHaveAttribute('aria-expanded', 'true');
      for (const name of ['Import', 'Matrices', 'Reports', 'Versioning']) {
        await expect(page.locator(`#project${name}Link`)).toBeVisible();
      }
      await assertInsideViewport(toggle, 'Project tools trigger');
      await assertInsideViewport(page.locator('#projectTools .dropdown-menu'), 'Open project tools menu');
      await evidence.saveRequiredViewportState(`qa-portfolio-tools-${viewport.width}`, '#projectWorkspace');
      await page.keyboard.press('ArrowDown');
      await expect(page.locator('#projectMatricesLink')).toBeFocused();
      await page.keyboard.press('Escape');
      await expect(page.locator('#projectTools .dropdown-menu')).toBeHidden();
      await expect(toggle).toBeFocused();
      await expect(toggle).toHaveAttribute('aria-expanded', 'false');

      await showProject(projectIds[1]);
      const otherRequirement = fixtures.projectRequirements(projectIds[1])[0];
      await detailLink(otherRequirement).scrollIntoViewIfNeeded();
      await expect(detailLink(otherRequirement)).toBeInViewport();
      for (const action of ['analyze', 'snapshots', 'confirm']) {
        await expect(page.locator(`.requirement-${action}[data-requirement-id="${otherRequirement.id}"]`)).toBeVisible();
      }
      await assertNoPageOverflow('Portfolio after project switch at ' + viewport.width + 'px');
      evidence.passed(`Portfolio ${viewport.width}×${viewport.height}: project tools are visible, native ArrowDown/Escape works, and every tools/detail link follows the selected project in context "${prefix || '/'}"`);
    }

    await page.setViewportSize({ width: 1366, height: 768 });
    await showProject(projectIds[0]);
    await detailLink(primaryRequirement).focus();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(applicationUrl(`/projects/${projectIds[0]}/requirements/${primaryRequirement.id}?lang=de`));
    await waitForPage('#requirementHeading', primaryRequirement.title, '#detailBusy');
    await expect(page.locator('#newVersionButton')).toBeEnabled(); // Actual authenticated account capability.
    await page.locator('#newVersionButton').click();
    const modal = page.locator('#newVersionModal');
    await expect(modal).toHaveAttribute('aria-modal', 'true');
    const draft = {
      versionText: '  Pumpe geordnet stoppen.\nDie Bestätigung der Leitstelle abwarten.  ',
      changeReason: '  Fachliche Präzisierung nach Prüfung  ', sourceSection: '5.3.1', sourcePage: '27',
      sourceOriginal: 'Original aus Abschnitt 5.3.1.\nDiese Quellenangabe bleibt im Entwurf.'
    };
    await page.locator('#versionText').fill(draft.versionText);
    await page.locator('#changeReason').fill(draft.changeReason);
    await modal.locator('details summary').click();
    for (const id of ['sourceSection', 'sourcePage', 'sourceOriginal']) await page.locator('#' + id).fill(draft[id]);
    const [conflictResponse] = await Promise.all([
      page.waitForResponse(response => response.request().method() === 'POST'
        && response.url() === applicationUrl(`/api/projects/${projectIds[0]}/requirements/${primaryRequirement.id}/versions`)),
      page.locator('#newVersionForm button[type="submit"]').click()
    ]);
    evidence.assert(conflictResponse.status() === 409, 'The real form submission must receive the intercepted 409');
    await expect(page.locator('#detailBusy')).toBeHidden();
    const versionError = page.locator('#newVersionError');
    await expect(versionError).toHaveText(conflictMessage);
    await expect(versionError).toBeVisible();
    await expect(versionError).toBeFocused();
    await expect(modal).toHaveAttribute('aria-modal', 'true');
    await expect(page.locator('#detailError')).toBeHidden();
    for (const [id, value] of Object.entries(draft)) await expect(page.locator('#' + id)).toHaveValue(value);
    await assertInsideViewport(versionError, 'Version conflict message');
    evidence.assert(await versionError.evaluate(element => {
      const rect = element.getBoundingClientRect();
      const hit = document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2);
      return document.activeElement === element && element.closest('#newVersionModal') !== null
        && (hit === element || element.contains(hit));
    }), 'The focused error must be inside the open modal and unobscured by a backdrop or busy overlay');
    expect(submittedVersions).toEqual([{
      text: draft.versionText, changeReason: draft.changeReason,
      source: { ...primaryRequirement.currentVersion.source, sectionReference: draft.sourceSection,
        pageNumber: Number(draft.sourcePage), originalText: draft.sourceOriginal }
    }]);
    await evidence.axeState('qa-portfolio-version-conflict', '#newVersionModal');
    await evidence.saveRequiredViewportState('qa-portfolio-version-conflict', '#newVersionModal');
    evidence.passed('A real New version submit receives a locally intercepted 409; the open Bootstrap modal preserves every field and exposes/focuses its unobscured error');
    await modal.locator('.btn-close').click();
    await expect(modal).toBeHidden();

    await page.locator('#matrixLink').click();
    await expect(page).toHaveURL(applicationUrl(`/projects/${projectIds[0]}/matrices?lang=de`));
    await waitForPage('#matrixProject', 'QA-CONTEXT-A', '#matrixBusy');
    await page.locator('#solutionMatrixTab').click();
    await expect(matrixCell('QA-REQ-0')).toHaveText('0%');
    await expect(matrixCell('QA-REQ-EMPTY')).toHaveText('·');
    await expect(matrixCell('QA-REQ-EMPTY')).toHaveAccessibleName('QA-SOL-A, QA-REQ-EMPTY: Leer');
    await matrixCell('QA-REQ-0').click();
    await expect(page.locator('#cellDetail')).toHaveAttribute('aria-modal', 'true');
    await expect(page.locator('#cellDetailBody')).toContainText('0%');
    await expect(page.locator('#cellDetailBody')).toContainText(zeroEvidence);
    await expect(page.locator('#cellDetailBody')).not.toContainText('Es ist keine Beziehung gespeichert.');
    await expect(page.locator('#cellDetailBody a')).toHaveAttribute('href',
      relativeUrl(`/projects/${projectIds[0]}/requirements/${primaryRequirement.id}?lang=de`));
    await evidence.axeState('qa-portfolio-matrix-zero', '#cellDetail');
    await evidence.saveRequiredViewportState('qa-portfolio-matrix-zero', '#cellDetail');
    await closeCellDetail();
    await matrixCell('QA-REQ-EMPTY').click();
    await expect(page.locator('#cellDetailBody')).toContainText('Es ist keine Beziehung gespeichert. Dies ist kein expliziter Null-Score.');
    await expect(page.locator('#cellDetailBody')).not.toContainText(zeroEvidence);
    await closeCellDetail();
    await page.locator('#relationState').selectOption('related');
    await expect(matrixCell('QA-REQ-0')).toHaveText('0%');
    await expect(matrixCell('QA-REQ-65')).toHaveText('65%');
    await expect(matrixCell('QA-REQ-EMPTY')).toHaveCount(0);
    await page.locator('#relationState').selectOption('empty');
    await expect(matrixCell('QA-REQ-0')).toHaveCount(0);
    await expect(matrixCell('QA-REQ-EMPTY')).toHaveText('·');
    await page.locator('#resetFilters').click();
    await page.locator('#minimumValue').fill('1');
    await expect(matrixCell('QA-REQ-0')).toHaveCount(0);
    await expect(matrixCell('QA-REQ-65')).toHaveText('65%');
    evidence.passed('The valid sparse integer matrix retains a stored 0% and its evidence; missing cells and actual Relationship/Minimum filters keep different meanings');

    fixtures.state.unknownCoverage = true;
    await page.reload({ waitUntil: 'domcontentloaded' });
    await waitForPage('#matrixProject', 'QA-CONTEXT-A', '#matrixBusy');
    await page.locator('#solutionMatrixTab').click();
    await expect(matrixCell('QA-REQ-0')).toHaveText('0%');
    await expect(matrixCell('QA-REQ-EMPTY')).toHaveText('·');
    await expect(matrixCell('QA-REQ-UNKNOWN')).toHaveText('?');
    await expect(matrixCell('QA-REQ-UNKNOWN')).toHaveAccessibleName('QA-SOL-A, QA-REQ-UNKNOWN: Unbekannt');
    await page.locator('#solutionMatrix').scrollIntoViewIfNeeded();
    await evidence.saveRequiredViewportState('qa-portfolio-matrix-degraded-cells', '#matrixMain');
    await matrixCell('QA-REQ-UNKNOWN').click();
    await expect(page.locator('#cellDetailBody')).toContainText('Die Abdeckung dieser gespeicherten Beziehung ist unbekannt.');
    await expect(page.locator('#cellDetailBody')).not.toContainText('Es ist keine Beziehung gespeichert.');
    await expect(page.locator('#cellDetailBody')).not.toContainText('0%');
    await evidence.saveRequiredViewportState('qa-portfolio-matrix-unknown', '#cellDetail');
    await closeCellDetail();
    await page.locator('#relationState').selectOption('related');
    await expect(matrixCell('QA-REQ-UNKNOWN')).toHaveText('?');
    await expect(matrixCell('QA-REQ-0')).toHaveText('0%');
    await expect(matrixCell('QA-REQ-EMPTY')).toHaveCount(0);
    await page.locator('#minimumValue').fill('1');
    await expect(matrixCell('QA-REQ-UNKNOWN')).toHaveCount(0);
    await expect(matrixCell('QA-REQ-0')).toHaveCount(0);
    evidence.passed('Explicit malformed-response injection (null coverage, not normal backend output) remains unknown in the actual cells/offcanvas and never becomes missing or numeric zero');

    fixtures.state.unknownCoverage = false;
    await page.locator('#portfolioBack').click();
    await waitForPage('#selectedProjectKey', 'QA-CONTEXT-A', '#portfolioBusy');
    await page.locator('#projectToolsToggle').click();
    await page.locator('#projectVersioningLink').click();
    await expect(page).toHaveURL(applicationUrl(`/projects/${projectIds[0]}/versioning?lang=de`));
    await waitForPage('#versioningProject', 'QA-CONTEXT-A', '#versioningBusy');
    for (const id of ['commitBranch', 'materializeBranch', 'mergeSource', 'mergeTarget']) {
      await expect(page.locator('#' + id)).toBeVisible();
      await expect(page.locator(`#${id} option`)).toHaveText(branchNames);
      expect(await page.locator(`#${id} option`).evaluateAll(options => options.map(option => option.value))).toEqual(branchNames);
    }
    const messages = { commitMessage: '  Geprüfter Commit-Entwurf\nBegründung behalten.  ', mergeMessage: '  Geprüfte Zusammenführung  ' };
    const selections = { commitBranch: branchNames[1], materializeBranch: branchNames[2],
      mergeSource: branchNames[0], mergeTarget: branchNames[2] };
    for (const [id, value] of Object.entries(messages)) await page.locator('#' + id).fill(value);
    for (const [id, value] of Object.entries(selections)) await page.locator('#' + id).selectOption(value);
    gitReadGate = { seen: false };
    gitReadGate.promise = new Promise(resolve => { gitReadGate.release = resolve; });
    fixtures.state.head = 'b'.repeat(40);
    await page.locator('#refreshPreview').click();
    await expect.poll(() => gitReadGate.seen).toBe(true);
    await expect(page.locator('#versioningBusy')).toBeVisible();
    for (const [id, value] of Object.entries({ ...messages, ...selections })) await expect(page.locator('#' + id)).toHaveValue(value);
    gitReadGate.release();
    await expect(page.locator('#versioningBusy')).toBeHidden();
    await expect(page.locator('#headCommit')).toHaveText('b'.repeat(12));
    for (const [id, value] of Object.entries({ ...messages, ...selections })) await expect(page.locator('#' + id)).toHaveValue(value);
    await expect(page.locator('#versioningError')).toBeHidden();
    await page.locator('#commitMessage').scrollIntoViewIfNeeded();
    await evidence.saveRequiredViewportState('qa-portfolio-versioning-refresh', '#versioningMain');
    evidence.passed('Real RepositoryState string branches populate visible labels and option values; typed messages and independent selections survive a delayed Refresh that renders a new HEAD');
    evidence.assert(fixtureReads.has('/api/git/state') && fixtureReads.has('/api/projects/git/export'),
      'Versioning must use the actual repository-state and portfolio-export API contracts');
    evidence.assert(unexpectedTraffic.length === 0, 'Unexpected fixture traffic: ' + unexpectedTraffic.join('; '));
    evidence.assert(submittedVersions.length === 1, 'Exactly one locally intercepted write is expected; no analysis, commit, merge or materialization is submitted');
  } catch (error) {
    failure = error;
    if (unexpectedTraffic.length) error.message += '\nFixture boundary: ' + unexpectedTraffic.join('; ');
    await evidence.saveRequiredViewportState('qa-portfolio-context-failure', 'body').catch(() => undefined);
    throw error;
  } finally {
    gitReadGate?.release?.();
    let cleanupFailure;
    try {
      await page.goto(cleanupUrl, { waitUntil: 'domcontentloaded' });
      await restorePreferences();
    } catch (error) { cleanupFailure = error; }
    for (const [pattern, handler] of routes.reverse()) {
      await page.unroute(pattern, handler).catch(error => { cleanupFailure ||= error; });
    }
    try {
      if (originalViewport) await page.setViewportSize(originalViewport);
      const returnUrl = new URL(originalUrl);
      returnUrl.searchParams.set('lang', originalLocale);
      await page.goto(returnUrl.href, { waitUntil: 'domcontentloaded' });
      await page.evaluate(() => window.TaxonomyI18n?.ready?.());
      if (returnUrl.pathname === relativeUrl('/projects')) {
        // The normal project initializer also persists its selected project.
        // Let it finish before the final restoration of the caller's preference.
        await expect(page.locator('#portfolioBusy')).toBeHidden();
      }
      await restorePreferences();
    } catch (error) { cleanupFailure ||= error; }
    if (cleanupFailure && !failure) throw cleanupFailure;
    if (cleanupFailure && failure) failure.message += '\nPortfolio workflow cleanup: ' + cleanupFailure.message;
  }
}
