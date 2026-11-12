import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';

const workbook = 'taxonomy-knowledge/src/main/resources/data/C3_Taxonomy_Catalogue_25AUG2025.xlsx';

export async function measureCatalogueSearch(context, baseUrl) {
  const policy = JSON.parse(await readFile(new URL('../large-result-budget.json', import.meta.url), 'utf8'));
  const workbookBytes = await readFile(new URL('../../' + workbook, import.meta.url));
  const page = await context.newPage();
  const query = 'service', count = 100;
  try {
    await page.goto(baseUrl + '/?lang=en', {waitUntil: 'domcontentloaded'});
    await page.locator('#taxonomyTree .tax-node').first().waitFor();
    await page.waitForFunction(() => window.TaxonomyAnalysisSession?.state().ready
      && window.TaxonomyContextBar?.getCurrentContext());
    const closeOnboarding = page.locator('#onboardingDismiss');
    if (await closeOnboarding.isVisible()) await closeOnboarding.click();

    const [response, metrics] = await Promise.all([
      page.waitForResponse(response => {
        const url = new URL(response.url());
        return url.pathname.endsWith('/api/search') && url.searchParams.get('q') === query
          && url.searchParams.get('maxResults') === String(count);
      }),
      page.evaluate(({query, count}) => new Promise((resolve, reject) => {
        const area = document.getElementById('searchResultsArea');
        const started = performance.now();
        const deadline = setTimeout(() => {
          observer.disconnect();
          reject(new Error('The catalogue search did not render ' + count + ' results'));
        }, 20_000);
        const observer = new MutationObserver(() => {
          if (Number(area.dataset.totalResults) !== count) return;
          observer.disconnect();
          requestAnimationFrame(() => requestAnimationFrame(() => {
            clearTimeout(deadline);
            const painted = performance.now();
            const timing = performance.getEntriesByType('resource').find(entry => {
              const url = new URL(entry.name);
              return entry.startTime >= started && url.pathname.endsWith('/api/search')
                && url.searchParams.get('q') === query && url.searchParams.get('maxResults') === String(count);
            });
            resolve({durationMs: painted - started, renderAfterResponseMs: timing ? painted - timing.responseEnd : null,
              nodes: area.querySelectorAll('*').length, areaHeight: area.clientHeight,
              rows: [...area.querySelectorAll('.search-result-item')].map(row => row.dataset.code),
              pathAnnouncement: document.getElementById('searchCurrentPath')?.getAttribute('aria-live'),
              summaryRole: document.getElementById('searchResultSummary')?.getAttribute('role')});
          }));
        });
        observer.observe(area, {childList: true, subtree: true, attributes: true, attributeFilter: ['data-total-results']});
        window.TaxonomySearch.performSearch(query, 'fulltext', count);
      }), {query, count})
    ]);
    assert.equal(response.status(), 200);
    const bytes = await response.body(), rows = JSON.parse(bytes.toString('utf8'));
    assert.equal(rows.length, count);
    assert.deepEqual(metrics.rows, rows.slice(0, policy.resultWindowSize).map(row => row.code));
    assert.ok(metrics.nodes <= policy.maxResultAreaDomNodes, 'Result DOM exceeds the catalogue budget');
    assert.ok(metrics.areaHeight > 0 && metrics.areaHeight <= policy.maxResultAreaClientHeightPx,
      'Result area exceeds the catalogue budget or is hidden');
    assert.ok(metrics.renderAfterResponseMs !== null && metrics.renderAfterResponseMs >= 0
      && metrics.renderAfterResponseMs <= policy.maxRenderDurationMs, 'Catalogue render timing: ' + JSON.stringify(metrics));
    assert.equal(metrics.pathAnnouncement, 'polite');
    assert.equal(metrics.summaryRole, 'status');

    // Check a real long description, through the same keyboard navigation as a search user.
    const index = rows.slice(0, policy.resultWindowSize).findIndex(row => row.parentCode && row.descriptionEn?.length >= 120);
    assert.notEqual(index, -1, 'The measured catalogue response must include a long description');
    const selected = rows[index];
    const resultLink = page.locator('#searchResultsArea .search-result-item').nth(index);
    await resultLink.focus();
    await page.evaluate(() => {
      document.addEventListener('keydown', () => performance.mark('catalogue-choice-key'), {once: true});
      document.addEventListener('taxonomy:search-navigation-complete',
        () => performance.mark('catalogue-choice-focused'), {once: true});
    });
    await resultLink.press('Enter');
    await page.waitForFunction(code => document.activeElement?.closest('.tax-node')?.dataset.code === code, selected.code);
    const description = page.locator('#taxonomyTree .tax-node[data-code="' + selected.code + '"] > .tax-description');
    await page.waitForFunction(() => performance.getEntriesByName('catalogue-choice-focused').length);
    const interactionMs = await page.evaluate(() => performance.measure('catalogue-choice-latency',
      'catalogue-choice-key', 'catalogue-choice-focused').duration);
    assert.ok(interactionMs <= policy.maxInteractionLatencyMs, 'Catalogue selection latency: ' + interactionMs);
    assert.ok((await page.locator('#searchCurrentPath').innerText()).includes(selected.code));
    assert.ok((await page.locator('#searchActiveFilters').innerText()).includes(query));
    if (!(await page.locator('#showDescriptions').isChecked())) await page.locator('#showDescriptions').check();
    await description.waitFor({state: 'visible'});
    const normalize = text => text.replace(/\s+/g, ' ').trim();
    assert.equal(normalize(await description.innerText()), normalize(selected.descriptionEn));
    await page.locator('[data-search-result-nav="summary"]').press('Enter');
    await page.waitForFunction(() => document.activeElement?.id === 'searchResultSummary');
    const scrollTop = await page.locator('#searchResultsArea').evaluate(area => area.scrollTop);
    assert.equal(scrollTop, 0);
    return {query, count, workbook, interactionMs, scrollTop, workbookSha256: createHash('sha256').update(workbookBytes).digest('hex'),
      responseSha256: createHash('sha256').update(bytes).digest('hex'), selectedCode: selected.code,
      descriptionLength: selected.descriptionEn.length, ...metrics};
  } finally {
    await page.close();
  }
}
