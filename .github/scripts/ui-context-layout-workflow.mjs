import { expect } from '@playwright/test';
import { navigateToPage } from './ui-role-fixtures.mjs';

async function settleLayout(page) {
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

async function bounds(page, selectors) {
  return page.evaluate(selectors => Object.fromEntries(selectors.map(selector => {
    const element = document.querySelector(selector);
    if (!element) return [selector, null];
    const rect = element.getBoundingClientRect();
    return [selector, { x: rect.x, y: rect.y, width: rect.width, height: rect.height,
      right: rect.right, bottom: rect.bottom }];
  })), selectors);
}

/** Real templates, catalogue, navigation and CSS; long log content is a local QA fixture. */
export async function runMainContextLayoutWorkflow({ page, evidence }) {
  const originalViewport = page.viewportSize();
  const originalInput = await page.locator('#businessText').inputValue();
  try {
    await navigateToPage(page, 'analyze');
    await page.locator('#businessText').fill('');
    await expect(page.locator('#businessText')).toHaveValue('');
    for (const viewport of [{ width: 1366, height: 768 }, { width: 1024, height: 768 },
      { width: 390, height: 844 }]) {
      await page.setViewportSize(viewport);
      await page.evaluate(() => scrollTo(0, 0));
      await settleLayout(page);
      const geometry = await bounds(page, ['#analysisTaskProgress', '#businessText', '#copilotBtn']);
      evidence.assert(geometry['#businessText'].y + 80 < viewport.height,
        'The requirement input must have useful space in the initial viewport: ' + JSON.stringify({ viewport, geometry }));
      evidence.assert(geometry['#copilotBtn'].bottom <= viewport.height,
        'The primary action must be visible without scrolling on the empty task: ' + JSON.stringify({ viewport, geometry }));
      evidence.assert(geometry['#analysisTaskProgress'].height < 200,
        'Progress guidance must leave room for the task: ' + JSON.stringify({ viewport, geometry }));
      if (viewport.width >= 992) {
        evidence.assert(await page.locator('#searchPanel > summary').isVisible(),
          'Desktop catalogue search must be directly discoverable beside the catalogue');
      }
      await evidence.saveRequiredViewportState('qa-context-analysis-' + viewport.width, '#mainContent');
    }
    for (const viewport of [{ width: 1366, height: 768 }, { width: 1024, height: 768 }]) {
      await page.setViewportSize(viewport);
      await navigateToPage(page, 'dsl-editor');
      await settleLayout(page);
      const geometry = await bounds(page, ['#mainContent', '#dslEditorContainer', '#dslFormatBtn']);
      evidence.assert(geometry['#dslEditorContainer'].width >= geometry['#mainContent'].width * 0.6,
        'The active DSL editor must receive most of the working width: ' + JSON.stringify({ viewport, geometry }));
      evidence.assert(geometry['#dslFormatBtn'].right <= viewport.width,
        'Every DSL toolbar action must remain inside the viewport');
      evidence.assert(!(await page.locator('#leftPanel').isVisible()),
        'The separate catalogue must yield its space while editing the DSL');
      await evidence.saveRequiredViewportState('qa-context-dsl-' + viewport.width, '#tab-dsl-editor');
    }

    await page.setViewportSize({ width: 1366, height: 768 });
    await navigateToPage(page, 'analyze');
    const originalLog = await page.locator('#llmCommLogContent').innerHTML();
    try {
      await page.evaluate(() => {
        document.getElementById('analysisSecondaryTools').open = true;
        document.getElementById('llmCommLog').open = true;
        const content = document.getElementById('llmCommLogContent');
        content.replaceChildren(...Array.from({ length: 80 }, (_, index) => {
          const row = document.createElement('p');
          row.textContent = 'QA log entry ' + (index + 1);
          return row;
        }));
      });
      const geometry = await bounds(page, ['#leftPanel > .card', '#leftPanel > .card > .card-body']);
      evidence.assert(geometry['#leftPanel > .card'].bottom - geometry['#leftPanel > .card > .card-body'].bottom < 8,
        'Expanded analysis tools must not stretch an empty reference card: ' + JSON.stringify(geometry));
    } finally {
      await page.locator('#llmCommLogContent').evaluate((element, html) => { element.innerHTML = html; }, originalLog);
      await page.evaluate(() => {
        document.getElementById('analysisSecondaryTools').open = false;
        document.getElementById('llmCommLog').open = false;
      });
    }
    evidence.passed('task-first initial viewport, full-width DSL editing and independently sized catalogue at laptop and phone widths');
  } finally {
    await page.setViewportSize(originalViewport);
    await navigateToPage(page, 'analyze');
    await page.locator('#businessText').fill(originalInput);
  }
}

/** Scores are controlled QA evidence on real catalogue identities, never an LLM result. */
export async function runReviewNavigationWorkflow({ page, evidence }) {
  const original = await page.evaluate(() => ({
    input: document.getElementById('businessText').value,
    state: Object.fromEntries(['currentScores', 'currentRawScores', 'currentReasons', 'lastAnalysisScope',
      'lastAnalysisDurationMillis', 'lastAnalyzedText', 'currentView'].map(key => [key, window.TaxonomyState[key]]))
  }));
  const providerRequests = [];
  const observeRequest = request => {
    const postAnalysis = request.method() === 'POST'
      && /\/api\/(?:analyze|analyze-node|justify-leaf|analysis-sessions|copilot)(?:[/?]|$)/.test(request.url());
    // Native expansion uses GET analyze-node; EventSource uses GET analyze-stream.
    const getAnalysis = request.method() === 'GET'
      && /\/api\/analyze-(?:node|stream)(?:[/?]|$)/.test(request.url());
    if (postAnalysis || getAnalysis) providerRequests.push(request.url());
  };
  page.on('request', observeRequest);
  try {
    await page.waitForFunction(() => window.TaxonomyState.taxonomyData.length > 0);
    const selected = await page.evaluate(() => {
      const utils = window.TaxonomyUtils;
      const root = window.TaxonomyState.taxonomyData.find(node =>
        utils.isTaxonomyContainer(node) && node.children?.length);
      if (!root) throw new Error('No classified taxonomy container is available for review navigation');
      const firstEntry = utils.contentRoots([root])[0];
      if (!firstEntry || utils.isTaxonomyContainer(firstEntry)) {
        throw new Error('The selected area has no original first catalogue entry');
      }
      let leaf = firstEntry;
      while (leaf.children?.length) leaf = leaf.children[leaf.children.length - 1];
      return { root: root.code, firstEntry: firstEntry.code, leaf: leaf.code, name: leaf.name };
    });
    for (const view of ['list', 'tabs', 'tree', 'sunburst', 'decision', 'summary']) {
      const frozen = await page.evaluate(({ view, selected }) => {
        const input = document.getElementById('businessText');
        input.value = 'QA review navigation fixture';
        input.dispatchEvent(new Event('input', { bubbles: true }));
        input.classList.remove('stale-results');
        document.getElementById('statusArea').replaceChildren();
        const state = window.TaxonomyState;
        state.currentScores = { [selected.root]: 99, [selected.leaf]: 98, 'QA-NOT-IN-CATALOGUE': 100 };
        state.currentRawScores = { ...state.currentScores };
        state.currentReasons = { [selected.leaf]: 'QA rationale for the strongest catalogue match' };
        state.lastAnalyzedText = input.value;
        state.lastAnalysisDurationMillis = 12345;
        state.lastAnalysisScope = { selectedRoots: [selected.root], mode: 'TAXONOMIES_ONLY' };
        window.TaxonomyBrowse.switchView(view);
        window.TaxonomyOnboarding.syncTaskProgress();
        return JSON.stringify([state.currentScores, state.currentRawScores, state.currentReasons,
          state.lastAnalysisScope, state.lastAnalysisDurationMillis]);
      }, { view, selected });
      await expect(page.locator('#taskNextAction')).toHaveAttribute('data-action', 'review-results');
      await page.locator('#taskNextAction').click();
      await expect.poll(() => page.evaluate(() => document.activeElement?.dataset.code),
        { message: 'Review must focus the strongest known match from ' + view }).toBe(selected.leaf);
      await expect(page.locator('#taskNextAction')).toHaveAttribute('data-action', 'open-architecture');
      evidence.assert(await page.evaluate(() => {
        const state = window.TaxonomyState;
        return JSON.stringify([state.currentScores, state.currentRawScores, state.currentReasons,
          state.lastAnalysisScope, state.lastAnalysisDurationMillis]);
      }) === frozen, 'Review navigation must preserve scores, reasons, scope and measured duration');
    }
    evidence.assert(providerRequests.length === 0, 'Inspecting existing results must not start a provider request');
    evidence.passed('strongest original match wins over higher area relevance from all six views, with frozen evidence and no new analysis');

    await page.evaluate(code => {
      const input = document.getElementById('businessText');
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.classList.remove('stale-results');
      window.TaxonomyState.currentScores = { [code]: 0 };
      window.TaxonomyState.currentRawScores = { [code]: 0 };
      window.TaxonomyState.currentReasons = { [code]: 'QA rationale explaining zero relevance' };
      window.TaxonomyBrowse.switchView('sunburst');
      window.TaxonomyOnboarding.syncTaskProgress();
    }, selected.firstEntry);
    await expect(page.locator('#taskNextAction')).toHaveText('Bewertungen prüfen');
    await expect(page.locator('#taskGuidance')).toContainText('keine positive Relevanz');
    await page.locator('#taskNextAction').click();
    await expect.poll(() => page.evaluate(() => document.activeElement?.dataset.code)).toBe(selected.firstEntry);
    const zeroNode = page.locator('#taxonomyTree .tax-node[data-code="' + selected.firstEntry + '"]');
    await expect(zeroNode).toHaveAttribute('aria-label', /QA rationale explaining zero relevance/);
    await expect(zeroNode.locator(':scope > .tax-node-header > .tax-reason-icon'))
      .toHaveAttribute('title', 'QA rationale explaining zero relevance');
    await expect(page.locator('#taskNextAction')).toHaveAttribute('data-action', 'open-architecture');

    for (const score of [0, 91]) {
      const frozen = await page.evaluate(({ code, score }) => {
        const input = document.getElementById('businessText');
        input.dispatchEvent(new Event('input', { bubbles: true }));
        input.classList.remove('stale-results');
        const state = window.TaxonomyState;
        state.currentScores = { [code]: score };
        state.currentRawScores = { [code]: score };
        state.currentReasons = { [code]: 'QA area relevance evidence' };
        window.TaxonomyBrowse.switchView('sunburst');
        window.TaxonomyOnboarding.syncTaskProgress();
        return JSON.stringify([state.taxonomyData, state.currentScores, state.currentRawScores,
          state.currentReasons, state.lastAnalysisScope, state.lastAnalysisDurationMillis]);
      }, { code: selected.root, score });
      const areaRelevance = await page.evaluate(score =>
        window.TaxonomyI18n.t('browse.container.relevance', score), score);
      await expect(page.locator('#taskNextAction')).toHaveText('Bewertungen prüfen');
      await expect(page.locator('#taskNextAction')).toHaveAttribute('data-action', 'review-results');
      await expect(page.locator('#taskGuidance')).toHaveText(selected.root + ' · ' + areaRelevance);
      await page.locator('#taskNextAction').click();
      const area = page.locator('#taxonomyTree .tax-container[data-taxonomy-container="' + selected.root + '"]');
      const toggle = area.locator(':scope > .tax-container-heading > button.tax-container-toggle');
      await expect(area).toHaveAttribute('role', 'group');
      await expect(area).not.toHaveAttribute('aria-expanded', /./);
      await expect(toggle).toHaveAttribute('type', 'button');
      await expect(toggle).toBeFocused();
      await expect(toggle).toHaveAttribute('aria-expanded', 'true');
      await expect(area.locator(':scope > .tax-container-heading > .tax-container-score')).toHaveText(areaRelevance);
      await expect(page.locator('#taxonomyTree .tax-node[data-code="' + selected.root + '"]')).toHaveCount(0);
      await expect(area.locator('.tax-node .tax-pct')).toHaveCount(0);
      await expect(page.locator('#taskNextAction')).toHaveAttribute('data-action', 'open-architecture');
      evidence.assert(await page.evaluate(() => {
        const state = window.TaxonomyState;
        return JSON.stringify([state.taxonomyData, state.currentScores, state.currentRawScores,
          state.currentReasons, state.lastAnalysisScope, state.lastAnalysisDurationMillis]);
      }) === frozen, 'Area review must preserve catalogue, scores, raw evidence, reasons, scope and duration');
      evidence.assert(providerRequests.length === 0, 'Reviewing an area assessment must not start a provider request');
    }
    evidence.passed('zero and positive area-only assessments focus the native heading with distinct area relevance and unchanged evidence');

    await page.evaluate(() => {
      document.getElementById('businessText').dispatchEvent(new Event('input', { bubbles: true }));
      document.getElementById('businessText').classList.remove('stale-results');
      window.TaxonomyState.currentScores = { 'QA-NOT-IN-CATALOGUE': 100 };
      window.TaxonomyOnboarding.syncTaskProgress();
    });
    await page.locator('#taskNextAction').click();
    await expect(page.locator('#taskGuidance')).toBeVisible();
    await expect(page.locator('#taskGuidance')).toContainText('kein bewertetes Element');
    await expect(page.locator('#taskNextAction')).toHaveAttribute('data-action', 'review-results');
    evidence.assert(providerRequests.length === 0, 'Zero and missing catalogue evidence must not trigger an analysis');
    evidence.passed('zero relevance is described honestly; unavailable catalogue evidence does not falsely advance review');

    // The same catalogue search must stay visible when invoked from architecture.
    const similarRoute = '**/api/search/similar/' + encodeURIComponent(selected.root) + '?*';
    const similarHandler = route => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify([{ code: selected.leaf, name: selected.name, score: 0.98 }]) });
    await page.route(similarRoute, similarHandler);
    try {
      await navigateToPage(page, 'architecture');
      await page.evaluate(code => window.TaxonomySearch.findSimilar(code), selected.root);
      await expect(page.locator('#searchResultsArea')).toContainText(selected.leaf);
      await expect(page.locator('#searchResultsArea')).toBeVisible();
      evidence.assert(await page.locator('#analysisSecondaryTools').evaluate(element => !element.open),
        'Catalogue search must not open unrelated analysis tools');
      evidence.passed('Find Similar reveals its results beside the catalogue from the architecture context');
    } finally {
      await page.unroute(similarRoute, similarHandler);
    }
  } finally {
    page.off('request', observeRequest);
    await navigateToPage(page, 'analyze');
    await page.evaluate(original => {
      document.getElementById('businessText').value = original.input;
      document.getElementById('businessText').dispatchEvent(new Event('input', { bubbles: true }));
      Object.assign(window.TaxonomyState, original.state);
      document.getElementById('businessText').classList.remove('stale-results');
      window.TaxonomyBrowse.switchView(original.state.currentView);
      window.TaxonomyOnboarding.syncTaskProgress();
      document.getElementById('searchPanel').open = false;
    }, original);
  }
}

/** Uses the caller's explicit snapshot fixture with real renderer and browser layout. */
export async function runWorkbenchContextLayoutWorkflow({ page, evidence }) {
  const originalViewport = page.viewportSize();
  try {
    for (const viewport of [{ width: 1920, height: 1080 }, { width: 1366, height: 768 },
      { width: 1024, height: 768 }, { width: 390, height: 844 }]) {
      await page.setViewportSize(viewport);
      await page.evaluate(() => scrollTo(0, 0));
      await settleLayout(page);
      const geometry = await bounds(page, ['.workbench-toolbar', '.workbench-grid',
        '#architectureCanvasShell', '.workbench-detail-panel']);
      const canvas = geometry['#architectureCanvasShell'];
      if (viewport.width > 900) {
        evidence.assert(canvas.bottom <= viewport.height + 1
          && geometry['.workbench-detail-panel'].bottom <= viewport.height + 1,
        'Diagram and independently scrolling details must fit the real viewport: ' + JSON.stringify({ viewport, geometry }));
        evidence.assert(canvas.height >= viewport.height * 0.5,
          'The diagram must receive at least half of the desktop viewport height: ' + JSON.stringify({ viewport, geometry }));
      } else {
        evidence.assert(canvas.y <= viewport.height * 0.7,
          'Mobile users must encounter useful diagram content before the first screen ends: ' + JSON.stringify({ viewport, geometry }));
      }
      evidence.assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1),
        'Workbench controls must reflow without page-wide horizontal scrolling');
      await evidence.saveRequiredViewportState('qa-context-workbench-' + viewport.width, '.workbench-grid');
    }
    const disclosure = page.locator('#architectureExportOptions');
    for (const width of [1024, 390, 320]) {
      await page.setViewportSize({ width, height: 844 });
      await page.evaluate(() => scrollTo(0, 0));
      await disclosure.locator('summary').focus();
      await page.keyboard.press('Enter');
      await expect(disclosure).toHaveAttribute('open', '');
      const menu = await bounds(page, ['.workbench-export-content']);
      evidence.assert(menu['.workbench-export-content'].x >= 0
        && menu['.workbench-export-content'].right <= width,
      'Wrapped export trigger must not push its menu offscreen: ' + JSON.stringify({ width, menu }));
      await expect(page.locator('#downloadArchitectureSvg')).toBeVisible();
      await expect(page.locator('#downloadArchitecturePdf')).toBeVisible();
      await expect(page.locator('#downloadArchitectureWord')).toBeVisible();
      await expect(page.locator('#downloadDecisionWord')).toBeVisible();
      if (width === 390) {
        await evidence.axeState('qa-context-workbench-export-options', '#architectureExportOptions');
        await evidence.saveRequiredViewportState('qa-context-workbench-mobile-exports', '#architectureExportOptions');
      }
      await page.keyboard.press('Escape');
      await expect(disclosure).not.toHaveAttribute('open', '');
      await expect(disclosure.locator('summary')).toBeFocused();
    }
    evidence.passed('workbench allocates remaining height from actual toolbar geometry; all export formats remain keyboard reachable');
  } finally {
    await page.setViewportSize(originalViewport);
  }
}
